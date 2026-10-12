package me.rerere.rikkahub.data.ai.transformers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.ImageTilingPolicy
import me.rerere.ai.util.TileRange
import me.rerere.ai.util.planVerticalTiles
import java.io.ByteArrayOutputStream
import java.io.File

private const val TAG = "ImageTilingTransformer"

/**
 * 超长截图切片发送器。
 *
 * 为什么需要：服务商对"单张图片的边长"有硬上限（DeepSeek：单边 8192，单请求 ≥15 张图时 4096）。
 * 相册里的长截图动辄 9000~21000 像素高，直接发会被以 "unsupported image" 之类的笼统错误拒绝
 * ——而手动截短一点就能成功发送。
 *
 * 做法：把超长图按 [planVerticalTiles] 切成互相重叠的若干片，每片单独编码成 JPEG data URL。
 * 好处是"既不超上限、也不压糊"：每片都以接近原始分辨率发送，文字不会像整体降采样那样糊掉。
 *
 * 边界：
 * - 只对**支持图片输入**的模型生效；纯文本模型走 OcrTransformer 的 OCR 路径。
 * - 只处理本地文件（`file://` 与绝对路径）。`data:` / `http` / `content://` 原样交给常规编码路径。
 * - 图片宽度超过单边上限（超宽图）时不做纵向切片（切了也还是超宽），交回常规路径降采样。
 * - 有 EXIF 旋转的照片不做切片：区域解码拿不到旋转信息，交回常规路径处理。
 * - 切片只存在于**本次请求**，不会写回会话数据库；原始图片在会话里保持一份。
 */
object ImageTilingTransformer : InputMessageTransformer {
    /** 切片 JPEG 质量。85 与原编码路径一致，兼顾体积与文字锐度。 */
    private const val TILE_QUALITY = 85

    /**
     * 切片结果缓存条数。
     * 历史消息里的图片在**每一轮**请求都会重新编码，不缓存的话每轮都要重切一遍。
     * 缓存里存的是与请求共用的同一批字符串实例，不会额外复制一份像素数据。
     */
    private const val CACHE_CAPACITY = 3

    private val cache = object : LinkedHashMap<String, List<String>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean =
            size > CACHE_CAPACITY
    }

    private data class TilePlan(
        val file: File,
        val width: Int,
        val height: Int,
        val tiles: List<TileRange>,
        val maxSide: Int,
    )

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.model.inputModalities.contains(Modality.IMAGE)) return messages

        val policy = ImageTilingPolicy.DEFAULT
        val images = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Image>()
        if (images.isEmpty()) return messages

        return withContext(Dispatchers.IO) {
            val plans = HashMap<String, TilePlan?>()

            fun planAll(maxSide: Int) {
                plans.clear()
                images.distinctBy { it.url }.forEach { part ->
                    plans[part.url] = planTiles(part, maxSide, policy)
                }
            }

            planAll(policy.maxSide)

            // 切片会让图片张数变多，可能把整个请求推过"数量收紧阈值"，
            // 这时必须改用更严的单边上限重算，否则切出来的片仍然会被拒。
            var projected = images.size
            plans.values.filterNotNull().forEach { projected += it.tiles.size - 1 }
            val tightened = policy.effectiveMaxSide(projected)
            if (tightened < policy.maxSide) {
                planAll(tightened)
            }

            messages.map { message ->
                val newParts = message.parts.flatMap { part ->
                    if (part !is UIMessagePart.Image) {
                        listOf(part)
                    } else {
                        render(part, plans[part.url])
                    }
                }
                if (newParts == message.parts) message else message.copy(parts = newParts)
            }
        }
    }

    /** 需要切片时返回切片计划，否则返回 null（交给常规编码路径）。 */
    private fun planTiles(
        part: UIMessagePart.Image,
        maxSide: Int,
        policy: ImageTilingPolicy,
    ): TilePlan? {
        val file = resolveLocalFile(part.url) ?: return null
        if (!file.exists() || !file.isFile) return null

        val (width, height) = readBounds(file) ?: return null
        // 超宽图纵向切片也没用（切完还是超宽），交回常规路径降采样
        if (width > maxSide) return null
        // 没超上限：不做无用功
        if (height <= maxSide) return null
        // 区域解码不处理 EXIF 旋转，带旋转的照片交回常规路径
        if (!hasNormalOrientation(file)) return null

        val tiles = planVerticalTiles(width, height, maxSide, policy)
        if (tiles.size <= 1) return null
        return TilePlan(file, width, height, tiles, maxSide)
    }

    private fun render(part: UIMessagePart.Image, plan: TilePlan?): List<UIMessagePart> {
        if (plan == null) return listOf(part)
        val urls = tilesOf(part, plan) ?: return listOf(part)
        if (urls.size <= 1) return listOf(part)
        return urls.mapIndexed { index, url ->
            // 第一片继承原 part 的 metadata（例如 Google 的 thoughtSignature 需要原样回传），
            // 其余片留空，避免同一个签名被重复回传。
            UIMessagePart.Image(url = url, metadata = if (index == 0) part.metadata else null)
        }
    }

    private fun tilesOf(part: UIMessagePart.Image, plan: TilePlan): List<String>? {
        val file = plan.file
        val key = "${part.url}|${file.length()}|${file.lastModified()}|${plan.maxSide}"
        synchronized(cache) { cache[key]?.let { return it } }

        val tiles = runCatching { encodeTiles(plan) }.getOrElse { e ->
            Log.w(TAG, "切片失败，按原图发送: ${part.url}", e)
            return null
        }
        if (tiles.isEmpty()) return null

        Log.i(
            TAG,
            "长图切片: ${plan.width}x${plan.height} -> ${tiles.size} 片 (单边上限 ${plan.maxSide})"
        )
        synchronized(cache) { cache[key] = tiles }
        return tiles
    }

    @Suppress("DEPRECATION")
    private fun encodeTiles(plan: TilePlan): List<String> {
        val decoder = BitmapRegionDecoder.newInstance(plan.file.absolutePath, false) ?: return emptyList()
        try {
            val result = ArrayList<String>(plan.tiles.size)
            for (tile in plan.tiles) {
                // 逐片区域解码：整张 1260x18963 的 ARGB 位图约 96MB，一次性解码会 OOM
                val bitmap = decoder.decodeRegion(
                    Rect(0, tile.top, plan.width, tile.bottom),
                    BitmapFactory.Options()
                ) ?: continue
                try {
                    val bytes = ByteArrayOutputStream().use { os ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, TILE_QUALITY, os)
                        os.toByteArray()
                    }
                    result += "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                } finally {
                    bitmap.recycle()
                }
            }
            return result
        } finally {
            decoder.recycle()
        }
    }

    private fun resolveLocalFile(url: String): File? = when {
        url.startsWith("file://") -> url.toUri().path?.let { File(it) }
        url.startsWith("/") -> File(url)
        else -> null
    }

    private fun readBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    private fun hasNormalOrientation(file: File): Boolean = runCatching {
        val orientation = ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED
    }.getOrDefault(true)
}
