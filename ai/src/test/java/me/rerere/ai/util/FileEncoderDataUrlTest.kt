package me.rerere.ai.util

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64 as KotlinBase64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 发送前的图片编码校验。
 *
 * 覆盖两个曾经把"格式没错的图"变成"服务商看不懂的图"的坑：
 * 1. data URL 的纯 base64 请求（Anthropic 的 source.data / Google 的 inlineData.data）
 *    拿到的是**整个 data URL**，于是 payload 以 "data:image/png;base64," 开头，
 *    被服务商判为非法图片；
 * 2. http URL 被原样当成 base64 塞进上述字段——同样是非法图片。
 */
class FileEncoderDataUrlTest {

    @OptIn(ExperimentalEncodingApi::class)
    private fun pngPayload(): String {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
        return KotlinBase64.encode(bytes)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun jpegPayload(): String =
        KotlinBase64.encode(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))

    private fun image(url: String) = UIMessagePart.Image(url = url)

    @Test
    fun `data url without prefix returns only the base64 payload`() {
        val payload = pngPayload()
        val encoded = image("data:image/png;base64,$payload").encodeBase64(withPrefix = false).getOrThrow()

        assertEquals(payload, encoded.base64)
        assertFalse(encoded.base64.startsWith("data:"))
        assertEquals("image/png", encoded.mimeType)
    }

    @Test
    fun `data url with prefix returns the whole data url`() {
        val payload = pngPayload()
        val url = "data:image/png;base64,$payload"
        val encoded = image(url).encodeBase64(withPrefix = true).getOrThrow()

        assertEquals(url, encoded.base64)
    }

    @Test
    fun `data url mime type comes from real bytes not the declared header`() {
        // 声明成 png、实际是 jpeg：按真实内容上报，别把不匹配的声明转给服务商
        val encoded = image("data:image/png;base64,${jpegPayload()}").encodeBase64().getOrThrow()

        assertEquals("image/jpeg", encoded.mimeType)
    }

    @Test
    fun `garbage payload fails instead of being sent as an image`() {
        @OptIn(ExperimentalEncodingApi::class)
        val garbage = KotlinBase64.encode("this is not an image".toByteArray())
        val result = image("data:image/png;base64,$garbage").encodeBase64()

        assertTrue("非图片 payload 必须失败，否则服务商会回一个笼统的非法图片错误", result.isFailure)
    }

    @Test
    fun `empty payload fails`() {
        assertTrue(image("data:image/png;base64,").encodeBase64().isFailure)
    }

    @Test
    fun `remote url is only usable when the caller accepts a url`() {
        val url = "https://example.com/a.png"

        assertEquals(url, image(url).encodeBase64(withPrefix = true).getOrThrow().base64)
        // 需要纯 base64 的调用方（Anthropic / Google）绝不能拿到 URL 字符串
        assertTrue(image(url).encodeBase64(withPrefix = false).isFailure)
    }

    @Test
    fun `unsupported url format fails`() {
        assertTrue(image("content://media/external/images/1").encodeBase64().isFailure)
    }

    @Test
    fun `sniffing recognises the supported formats`() {
        assertEquals("image/png", sniffImageMimeType(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("image/jpeg", sniffImageMimeType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif", sniffImageMimeType("GIF89a".toByteArray() + ByteArray(4)))
        assertEquals("image/webp", sniffImageMimeType("RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()))
        assertNull(sniffImageMimeType("hello world".toByteArray()))
        assertNull(sniffImageMimeType(ByteArray(0)))
    }

    @Test
    fun `sniffing rejects payloads that are too short to judge`() {
        assertNull(sniffDataUrlImageMimeType(""))
        assertNull(sniffDataUrlImageMimeType("AAA"))
    }
}
