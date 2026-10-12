package me.rerere.ai.util

/**
 * 图片发送策略。
 *
 * 背景：各服务商对"单张图片的边长"有硬上限（DeepSeek：单边最长 8192 像素，
 * 且单个请求包含 15 张及以上图片时收紧到 4096）。RikkaHub 原先把这个上限写死为
 * 10_000，并且只用 2 的幂做降采样，于是高度落在 (8192, 10000]、(16384, 20000]、
 * (24576, 30000] 这类区间的长截图会被**原样**（或减半后仍然超限地）发出去，
 * 被服务商以 "unsupported image" 这类笼统错误拒绝——而截短一点就能发成功。
 *
 * 本策略不靠"压得更狠"来合规（那会把字压糊，上游 issue #1159 修的正是这个），
 * 而是把超长图切成互相重叠的若干片：每片既在上限内，又接近服务商缩放前的像素预算。
 */
data class ImageTilingPolicy(
    /** 单张图片单边像素上限。默认取 DeepSeek 的 8192；对 OpenAI/Gemini 也不会更差。 */
    val maxSide: Int = DEFAULT_MAX_SIDE,
    /** 单请求图片数量达到该值时，服务商收紧单边上限 */
    val manyImagesThreshold: Int = 15,
    /** 收紧后的单边上限 */
    val maxSideWhenManyImages: Int = 4096,
    /** 每片的目标像素数。服务商进模型前会把图缩到约 1300x1300（≈169 万像素），超过就白给。 */
    val readableTilePixels: Long = READABLE_TILE_PIXELS,
    /** 片数上限。每片都会进上下文并在后续每轮重发，所以必须有界。 */
    val maxTiles: Int = 8,
    /** 相邻片的重叠比例，避免把横穿切缝的文字/表格切断 */
    val overlapRatio: Double = 0.05,
    /** 整张图的像素闸（沿用上游 16M，防止解码 OOM） */
    val maxPixelsPerImage: Long = 16_000_000L,
) {
    /** 按本次请求的图片总数取实际生效的单边上限 */
    fun effectiveMaxSide(imageCount: Int): Int =
        if (imageCount >= manyImagesThreshold) minOf(maxSide, maxSideWhenManyImages) else maxSide

    companion object {
        const val DEFAULT_MAX_SIDE = 8192
        const val READABLE_TILE_PIXELS = 1_690_000L
        const val MIN_TILE_HEIGHT = 320
        val DEFAULT = ImageTilingPolicy()
    }
}

/** 一片在源图中的纵向范围（含与相邻片的重叠）。 */
data class TileRange(val top: Int, val height: Int) {
    val bottom: Int get() = top + height
}

/**
 * 计算纵向切片方案。
 *
 * 保证：① 首片从 0 开始、末片到 [height] 结束（全覆盖，无缝隙）；
 * ② 每片高度 ≤ 实际生效的单边上限；③ 相邻片重叠约 [ImageTilingPolicy.overlapRatio]；
 * ④ 片数 ≤ [ImageTilingPolicy.maxTiles]（除非把片高顶到硬上限也满足不了，此时以"不超上限"优先）。
 *
 * [width] 或 [height] 非正时返回单片，交由调用方按原路径处理。
 */
fun planVerticalTiles(
    width: Int,
    height: Int,
    maxSide: Int,
    policy: ImageTilingPolicy = ImageTilingPolicy.DEFAULT,
): List<TileRange> {
    if (width <= 0 || height <= 0) return listOf(TileRange(0, height.coerceAtLeast(1)))

    // 单边上限 与 整图像素闸 双重约束下的最大片高
    val pixelLimited = (policy.maxPixelsPerImage / width.coerceAtLeast(1)).toInt()
    val hardLimit = minOf(maxSide, pixelLimited).coerceAtLeast(1)
    if (height <= hardLimit) return listOf(TileRange(0, height))

    // 可读性目标片高：把服务商的像素预算用满（再高也会被服务商缩回去）
    val readable = if (hardLimit <= ImageTilingPolicy.MIN_TILE_HEIGHT) {
        hardLimit // 超宽图：像素闸已经把片高压到最小片高以下，不能再夹逼
    } else {
        (policy.readableTilePixels / width.coerceAtLeast(1)).toInt()
            .coerceIn(ImageTilingPolicy.MIN_TILE_HEIGHT, hardLimit)
    }

    // 片高越大 → 片数越少且越糊。二分找"满足片数上限的最小片高"，即在上限内保留最多细节。
    val tileHeight = if (countTiles(hardLimit, height, overlapFor(hardLimit, policy)) > policy.maxTiles) {
        hardLimit // 片数上限无论如何满足不了：优先保证不超单边上限
    } else {
        var low = readable
        var high = hardLimit
        while (low < high) {
            val mid = low + (high - low) / 2
            if (countTiles(mid, height, overlapFor(mid, policy)) <= policy.maxTiles) high = mid else low = mid + 1
        }
        low
    }

    return buildTiles(height, tileHeight, overlapFor(tileHeight, policy))
}

internal fun overlapFor(tileHeight: Int, policy: ImageTilingPolicy): Int =
    (tileHeight * policy.overlapRatio).toInt().coerceIn(0, (tileHeight - 1).coerceAtLeast(0))

/** 给定片高与重叠，算出需要多少片才能覆盖整图。 */
internal fun countTiles(tileHeight: Int, height: Int, overlap: Int): Int {
    if (tileHeight <= 0) return Int.MAX_VALUE
    if (height <= tileHeight) return 1
    val step = (tileHeight - overlap).coerceAtLeast(1)
    var count = 1
    var top = 0
    while (top + tileHeight < height) {
        top += step
        count++
        if (count > MAX_TILE_PROBE) break
    }
    return count
}

private fun buildTiles(height: Int, tileHeight: Int, overlap: Int): List<TileRange> {
    val step = (tileHeight - overlap).coerceAtLeast(1)
    val out = ArrayList<TileRange>()
    var top = 0
    while (true) {
        val h = minOf(tileHeight, height - top)
        out += TileRange(top, h)
        if (top + h >= height) break
        top += step
        if (out.size > MAX_TILE_PROBE) break
    }
    return out
}

private const val MAX_TILE_PROBE = 512
