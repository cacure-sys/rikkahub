package me.rerere.ai.util

import android.media.ExifInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Base64OutputStream
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.encoding.Base64 as KotlinBase64
import kotlin.io.encoding.ExperimentalEncodingApi

private val supportedTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
)

data class EncodedImage(
    val base64: String,
    val mimeType: String
)

internal enum class ExifTransformType {
    NONE,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270,
}

internal fun mapExifOrientationToTransform(orientation: Int): ExifTransformType = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransformType.FLIP_HORIZONTAL
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransformType.ROTATE_180
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransformType.FLIP_VERTICAL
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransformType.TRANSPOSE
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransformType.ROTATE_90
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransformType.TRANSVERSE
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransformType.ROTATE_270
    ExifInterface.ORIENTATION_NORMAL,
    ExifInterface.ORIENTATION_UNDEFINED
    -> ExifTransformType.NONE

    else -> ExifTransformType.NONE
}

/**
 * 把图片编码成服务商要的形式。
 *
 * @param withPrefix true 返回完整 data URL（OpenAI 兼容的 image_url.url 用），
 *                   false 只返回纯 base64 内容（Anthropic 的 source.data / Google 的 inlineData.data 用）。
 * @param maxSide 单边像素上限，超过就降采样。默认取服务商里最严的档位（DeepSeek 8192），
 *                由 [ImageTilingPolicy] 决定；超长图会先被切片，所以这里不会把长截图压糊。
 */
fun UIMessagePart.Image.encodeBase64(
    withPrefix: Boolean = true,
    maxSide: Int = ImageTilingPolicy.DEFAULT_MAX_SIDE,
): Result<EncodedImage> = runCatching {
    when {
        this.url.startsWith("file://") || this.url.startsWith("/") -> {
            val filePath = if (this.url.startsWith("file://")) {
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            } else {
                this.url
            }
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val mimeType = file.guessMimeType().getOrThrow()
            // 统一进行压缩处理
            val (encoded, outputMimeType) = file.compressAndEncode(mimeType, maxDimension = maxSide)
            EncodedImage(
                base64 = if (withPrefix) "data:$outputMimeType;base64,$encoded" else encoded,
                mimeType = outputMimeType
            )
        }

        this.url.startsWith("data:") -> {
            val header = this.url.substringAfter("data:").substringBefore(",")
            val payload = this.url.substringAfter(",", "")
            val declared = header.substringBefore(";").trim().lowercase().ifBlank { "image/png" }
            if (payload.isBlank()) {
                throw IllegalArgumentException("Empty data URL payload")
            }
            // 发送前按真实字节判格式：非法 payload 宁可让上层用占位文本，也不要发给服务商换一个看不懂的报错
            val sniffed = sniffDataUrlImageMimeType(payload)
                ?: throw IllegalArgumentException("data URL payload is not a supported image")
            EncodedImage(
                // 修 bug：原先无视 withPrefix，把整个 data URL 当成 base64 返回，
                // 于是 Anthropic 的 source.data / Google 的 inlineData.data 里被塞进了
                // "data:image/png;base64," 前缀 → 服务商判为非法图片（"unsupported image"）。
                base64 = if (withPrefix) this.url else payload,
                mimeType = sniffed.ifBlank { declared },
            )
        }

        this.url.startsWith("http") -> {
            // 远程 URL 无法确定 mime type，默认使用 image/png。
            // 只有接受 URL 的调用方（OpenAI 兼容的 image_url.url）可以用：需要纯 base64 的
            // 调用方必须先把图下载下来，否则宁可失败让上层用占位文本，
            // 也不能把 URL 字符串当 base64 发出去（同样会被判为非法图片）。
            if (withPrefix) {
                EncodedImage(base64 = url, mimeType = "image/png")
            } else {
                throw IllegalArgumentException("Remote image must be downloaded before inlining: $url")
            }
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * 只靠前若干字节判断真实图片格式。
 * 解 base64 的前 12 个字符就够（9 字节），不必为了校验把整张图解一遍。
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun sniffDataUrlImageMimeType(payload: String): String? {
    val head = payload.filterNot { it.isWhitespace() }.take(12)
    if (head.length < 4) return null
    val bytes = runCatching { KotlinBase64.decode(head) }.getOrNull() ?: return null
    return sniffImageMimeType(bytes)
}

/** 按魔数判断图片格式，认不出返回 null。 */
internal fun sniffImageMimeType(bytes: ByteArray): String? = when {
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
        "image/jpeg"

    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "image/png"

    bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"

    bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF89a", "GIF87a") ->
        "image/gif"

    else -> null
}

fun UIMessagePart.Video.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:video/mp4;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Audio.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:audio/mp3;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

private fun File.compressAndEncode(
    mimeType: String,
    maxDimension: Int = ImageTilingPolicy.DEFAULT_MAX_SIDE,
    maxPixels: Long = 16_000_000L,
    quality: Int = 85
): Pair<String, String> {
    // 读取图片尺寸
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(absolutePath, options)

    // GIF 保持原样（可能是动图）；但超过单边上限时必须落到下面的静态图路径，
    // 否则会被服务商的单边像素上限拒绝。
    if (mimeType == "image/gif" &&
        options.outWidth in 1..maxDimension &&
        options.outHeight in 1..maxDimension
    ) {
        return Pair(encodeToBase64Streaming(), mimeType)
    }

    options.inSampleSize = calculateImageInSampleSize(
        width = options.outWidth,
        height = options.outHeight,
        maxDimension = maxDimension,
        maxPixels = maxPixels
    )
    options.inJustDecodeBounds = false

    val bitmap = BitmapFactory.decodeFile(absolutePath, options)
        ?: throw IllegalArgumentException("Failed to decode image: $absolutePath")
    val normalizedBitmap = normalizeByExif(bitmap)

    return try {
        val byteArrayOutputStream = ByteArrayOutputStream()
        // 强制使用 JPEG 格式，因为很多提供商不支持 webp
        Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP).use { base64Stream ->
            normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, base64Stream)
        }
        Pair(byteArrayOutputStream.toString(Charsets.ISO_8859_1.name()), "image/jpeg")
    } finally {
        if (normalizedBitmap !== bitmap) {
            normalizedBitmap.recycle()
        }
        bitmap.recycle()
    }
}

private fun File.normalizeByExif(bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = mapExifOrientationToTransform(orientation)
    return applyExifTransform(bitmap, transform)
}

private fun applyExifTransform(bitmap: Bitmap, transform: ExifTransformType): Bitmap {
    if (transform == ExifTransformType.NONE) return bitmap

    val matrix = Matrix()
    when (transform) {
        ExifTransformType.NONE -> return bitmap
        ExifTransformType.FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifTransformType.ROTATE_180 -> matrix.setRotate(180f)
        ExifTransformType.FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifTransformType.TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_90 -> matrix.setRotate(90f)
        ExifTransformType.TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_270 -> matrix.setRotate(270f)
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrElse { bitmap }
}

private fun File.encodeToBase64Streaming(): String {
    val byteArrayOutputStream = ByteArrayOutputStream()
    Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP).use { base64Stream ->
        inputStream().use { input ->
            input.copyTo(base64Stream, bufferSize = 8 * 1024)
        }
    }
    return byteArrayOutputStream.toString(Charsets.ISO_8859_1.name())
}

internal fun calculateImageInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxPixels: Long
): Int {
    if (width <= 0 || height <= 0) return 1

    var inSampleSize = 1
    while (
        (height / inSampleSize) > maxDimension ||
        (width / inSampleSize) > maxDimension ||
        (width.toLong() / inSampleSize) * (height.toLong() / inSampleSize) > maxPixels
    ) {
        inSampleSize *= 2
    }
    return inSampleSize
}

private fun File.guessMimeType(): Result<String> = runCatching {
    inputStream().use { input ->
        val bytes = ByteArray(16)
        val read = input.read(bytes)
        if (read < 12) error("File too short to determine MIME type")

        // 判断 HEIF/HEIC/AVIF 格式：ISO-BMFF 容器，"ftyp" box 位于字节 4..8，主品牌码位于 8..12
        // 新手机的 HDR HEIF 照片常用 heix/hevc/mif1/msf1 等品牌码，而非仅 heic，需全部识别
        if (bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
            when (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)) {
                "heic", "heix", "heim", "heis",
                "hevc", "hevx", "hevm", "hevs",
                "mif1", "msf1", "heif",
                    -> return@runCatching "image/heic"

                "avif", "avis" -> return@runCatching "image/avif"
            }
        }

        // 判断 JPEG 格式：开头为 0xFF 0xD8
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return@runCatching "image/jpeg"
        }

        // 判断 PNG 格式：开头为 89 50 4E 47 0D 0A 1A 0A
        if (bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) {
            return@runCatching "image/png"
        }

        // 判断WebP格式：开头为 "RIFF" + 4字节长度 + "WEBP"
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12)
                .toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return@runCatching "image/webp"
        }

        // 判断 GIF 格式：开头为 "GIF89a" 或 "GIF87a"
        val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (header == "GIF89a" || header == "GIF87a") {
            return@runCatching "image/gif"
        }

        error(
            "Failed to guess MIME type: $header, ${
                bytes.joinToString(",") {
                    it.toUByte().toString()
                }
            }"
        )
    }
}
