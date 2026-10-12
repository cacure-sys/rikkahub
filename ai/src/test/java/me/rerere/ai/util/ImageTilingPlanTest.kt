package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切片规划的行为约束。
 *
 * 这些断言全部来自这一条真实事故：相册里的长截图（1260x9652、1260x18963 等）被原样发出，
 * 服务商以 "unsupported image" 拒绝；而手动截短到 8000 像素以下就能正常发送。
 */
class ImageTilingPlanTest {

    /** 服务商单边上限 */
    private val maxSide = ImageTilingPolicy.DEFAULT_MAX_SIDE

    private fun assertCoversExactly(ranges: List<TileRange>, height: Int) {
        assertEquals("首片必须从 0 开始", 0, ranges.first().top)
        assertEquals("末片必须覆盖到最后一行", height, ranges.last().bottom)
        ranges.zipWithNext { a, b ->
            assertTrue("相邻片之间不能有缝隙: $a -> $b", b.top < a.bottom)
            assertTrue("相邻片之间必须有重叠: $a -> $b", b.top <= a.bottom)
        }
    }

    private fun assertWithinLimit(ranges: List<TileRange>, limit: Int) {
        ranges.forEach { range ->
            assertTrue("片高 ${range.height} 超过上限 $limit", range.height <= limit)
        }
    }

    @Test
    fun `short image stays a single tile`() {
        val ranges = planVerticalTiles(width = 1260, height = 800, maxSide = maxSide)
        assertEquals(1, ranges.size)
        assertEquals(TileRange(0, 800), ranges.single())
    }

    @Test
    fun `image exactly at the limit stays a single tile`() {
        val ranges = planVerticalTiles(width = 1260, height = maxSide, maxSide = maxSide)
        assertEquals(1, ranges.size)
        assertEquals(TileRange(0, maxSide), ranges.single())
    }

    @Test
    fun `real world long screenshots are tiled within the side limit`() {
        // 用户相册里的真实尺寸：前四个是当初被服务商拒绝的"危险窗口"高度
        listOf(8617, 8956, 9541, 9652, 18963, 21583).forEach { height ->
            val ranges = planVerticalTiles(width = 1260, height = height, maxSide = maxSide)
            assertTrue("1260x$height 应当被切片", ranges.size > 1)
            assertCoversExactly(ranges, height)
            assertWithinLimit(ranges, maxSide)
            assertTrue(
                "1260x$height 切了 ${ranges.size} 片，超过片数上限",
                ranges.size <= ImageTilingPolicy.DEFAULT.maxTiles
            )
        }
    }

    @Test
    fun `tile count respects the configured ceiling`() {
        val policy = ImageTilingPolicy.DEFAULT
        // 极长截图：优先保证不超上限，片数上限可以退让
        val height = 120_000
        val ranges = planVerticalTiles(width = 1260, height = height, maxSide = maxSide, policy = policy)
        assertCoversExactly(ranges, height)
        assertWithinLimit(ranges, maxSide)
    }

    @Test
    fun `pixel budget can be the binding constraint for wide images`() {
        // 宽 4000：16M 像素闸只允许 4000 高，比单边上限更严
        val height = 30_000
        val ranges = planVerticalTiles(width = 4000, height = height, maxSide = maxSide)
        assertCoversExactly(ranges, height)
        assertWithinLimit(ranges, 16_000_000 / 4000)
    }

    @Test
    fun `tall and narrow screenshots keep readable tile heights`() {
        // 可读性目标：1260 宽时每片约 1341 高（≈169 万像素），且必须在片数上限内
        val height = 9652
        val ranges = planVerticalTiles(width = 1260, height = height, maxSide = maxSide)
        assertTrue("片高应当够大才不糊，实际 ${ranges.first().height}", ranges.first().height >= 1000)
        assertTrue("1260x$height 切了 ${ranges.size} 片", ranges.size <= ImageTilingPolicy.DEFAULT.maxTiles)
    }

    @Test
    fun `many images tighten the effective side limit`() {
        val policy = ImageTilingPolicy.DEFAULT
        assertEquals(8192, policy.effectiveMaxSide(imageCount = 1))
        assertEquals(8192, policy.effectiveMaxSide(imageCount = 14))
        assertEquals(4096, policy.effectiveMaxSide(imageCount = 15))
        assertEquals(4096, policy.effectiveMaxSide(imageCount = 40))
    }

    @Test
    fun `tightened limit still produces legal tiles`() {
        val policy = ImageTilingPolicy.DEFAULT
        val height = 9652
        val tightened = policy.effectiveMaxSide(imageCount = 15)
        val ranges = planVerticalTiles(width = 1260, height = height, maxSide = tightened, policy = policy)
        assertCoversExactly(ranges, height)
        assertWithinLimit(ranges, tightened)
    }

    @Test
    fun `invalid dimensions fall back to a single tile`() {
        assertEquals(listOf(TileRange(0, 1)), planVerticalTiles(width = 0, height = 0, maxSide = maxSide))
        assertEquals(listOf(TileRange(0, 1)), planVerticalTiles(width = -5, height = -5, maxSide = maxSide))
    }

    @Test
    fun `extremely long narrow screenshot still respects the tile ceiling`() {
        // 片高与片数上限互相拉扯：取"满足片数上限的最小片高"，但不能超过单边上限
        val ranges = planVerticalTiles(width = 600, height = 40_000, maxSide = maxSide)
        assertCoversExactly(ranges, 40_000)
        assertWithinLimit(ranges, maxSide)
        assertTrue("片数应受上限约束，实际 ${ranges.size}", ranges.size <= ImageTilingPolicy.DEFAULT.maxTiles)
    }

    @Test
    fun `ultra wide image hits the pixel gate without crashing`() {
        // 回归：像素闸把片高压到最小片高以下时，曾因 coerceIn(min > max) 直接抛异常
        val ranges = planVerticalTiles(width = 100_000, height = 40_000, maxSide = maxSide)
        assertCoversExactly(ranges, 40_000)
        assertWithinLimit(ranges, 16_000_000 / 100_000)
    }
}
