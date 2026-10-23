@file:Suppress("unused")

package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.File
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 图片处理工具类
 * 提供图片压缩、旋转修正、二维码解析等功能
 */
object ImageUtils {

    /**
     * 优化的图片加载方法，避免OOM
     * 1. 先获取图片尺寸
     * 2. 计算合适的采样率
     * 3. 加载压缩后的图片
     * 4. 处理图片旋转
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param maxSize 最大尺寸限制，默认1024px
     * @return 压缩后的Bitmap，失败返回null
     */
    fun loadOptimizedBitmap(
        context: Context,
        uri: Uri,
        maxSize: Int = 1024
    ): Bitmap? {
        return runCatching {
            // 第一步：获取图片的原始尺寸，不加载到内存
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // 计算合适的采样率
            val sampleSize = calculateInSampleSize(options, maxSize, maxSize)

            // 第二步：使用采样率加载压缩后的图片
            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // 使用RGB_565减少内存占用
            }

            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, loadOptions)
            }

            // 第三步：处理图片旋转（如果需要）
            bitmap?.let { correctImageOrientation(context, uri, it) }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 计算合适的采样率
     *
     * @param options BitmapFactory.Options包含原始图片尺寸信息
     * @param reqWidth 目标宽度
     * @param reqHeight 目标高度
     * @return 采样率（2的幂）
     */
    fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // 计算最大的inSampleSize值，该值是2的幂，并且保持高度和宽度都大于请求的高度和宽度
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * 修正图片旋转
     * 根据EXIF信息自动旋转图片到正确方向
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param bitmap 原始bitmap
     * @return 旋转后的bitmap
     */
    fun correctImageOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return runCatching {
            val inputStream = context.contentResolver.openInputStream(uri)
            val exif = inputStream?.let { ExifInterface(it) }
            inputStream?.close()

            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap // 不需要旋转
            }

            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotatedBitmap != bitmap) {
                bitmap.recycle() // 回收原始bitmap
            }
            rotatedBitmap
        }.onFailure {
            it.printStackTrace()
        }.getOrDefault(bitmap)
    }

    /**
     * 判断 uri 指向的图片是否为 HEIF/HEIC 格式
     * 优先使用 ContentResolver 的 MIME 类型，回退到读取文件头魔数嗅探
     */
    fun isHeifImage(context: Context, uri: Uri): Boolean {
        context.contentResolver.getType(uri)?.lowercase()?.let { mime ->
            if (mime.contains("heic") || mime.contains("heif")) return true
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                if (read < 12) return@use false
                if (header.copyOfRange(4, 8).toString(Charsets.US_ASCII) != "ftyp") return@use false
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) in HEIF_BRANDS
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * 将 HEIF/HEIC 图片解码后重编码为 JPEG 写入 [target]
     * 用于规避部分组件（如 UCrop）对 HEIF（尤其 HDR HEIF）解码兼容性不佳的问题
     *
     * @return 转换成功返回 true，失败（如系统无法解码 HEIF）返回 false
     */
    fun convertHeifToJpeg(
        context: Context,
        uri: Uri,
        target: File,
        maxSize: Int = 4096,
        quality: Int = 95,
    ): Boolean = runCatching {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }
        val loadOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(boundsOptions, maxSize, maxSize)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, loadOptions)
        } ?: return@runCatching false
        // 将 EXIF 旋转烘焙进像素，输出的 JPEG 不再带方向信息，避免下游二次旋转
        val oriented = correctImageOrientation(context, uri, decoded)
        try {
            target.outputStream().use { output ->
                oriented.compress(Bitmap.CompressFormat.JPEG, quality, output)
            }
            true
        } finally {
            recycleBitmapSafely(oriented)
        }
    }.onFailure {
        it.printStackTrace()
    }.getOrDefault(false)

    private val HEIF_BRANDS = setOf(
        "heic", "heix", "heim", "heis",
        "hevc", "hevx", "hevm", "hevs",
        "mif1", "msf1", "heif",
    )

    /**
     * 从图片中解析二维码
     *
     * @param bitmap 要解析的图片
     * @return 二维码内容，解析失败返回null
     */
    fun decodeQRCodeFromBitmap(bitmap: Bitmap): String? {
        return runCatching {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            val reader = MultiFormatReader()
            val result = reader.decode(binaryBitmap)

            result.text
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 从URI加载图片并解析二维码（组合方法）
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param maxSize 最大尺寸限制，默认1024px
     * @return 二维码内容，解析失败返回null
     */
    fun decodeQRCodeFromUri(
        context: Context,
        uri: Uri,
        maxSize: Int = 1024
    ): String? {
        val bitmap = loadOptimizedBitmap(context, uri, maxSize) ?: return null
        return try {
            decodeQRCodeFromBitmap(bitmap)
        } finally {
            bitmap.recycle() // 确保释放内存
        }
    }

    /**
     * 安全地回收Bitmap内存
     *
     * @param bitmap 要回收的bitmap
     */
    fun recycleBitmapSafely(bitmap: Bitmap?) {
        bitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
    }

    /**
     * 获取图片的基本信息（不加载到内存）
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @return ImageInfo包含宽度、高度、MIME类型等信息
     */
    fun getImageInfo(context: Context, uri: Uri): ImageInfo? {
        return runCatching {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            if (options.outWidth > 0 && options.outHeight > 0) {
                ImageInfo(
                    width = options.outWidth,
                    height = options.outHeight,
                    mimeType = options.outMimeType
                )
            } else null
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 获取酒馆角色卡中的角色元数据（如果存在）
     *
     * 与 SillyTavern 的 `src/character-card-parser.js` 对齐，要点：
     * 1. **优先级** `ccv3` > `chara` —— ST 写卡时会同时写两个 tEXt，v3 优先；
     * 2. **按 keyword\0text 拆**，不做正则、不依赖 metadata-extractor 的
     *    `[keyword: text]` 拼装格式（那是库自己拼的字符串，格式随版本变）；
     * 3. 支持 `tEXt` / `iTXt`（UTF-8，部分工具用它导出），`zTXt` 压缩内容不展开。
     *
     * 旧实现只认 `tEXt` + `[chara:` 前缀 + 正则抓取，遇到 ccv3、iTXt 或
     * 库版本变化就会失败，且拿不到 v3 独有字段。
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @return Result<String> base64 编码的角色卡 JSON
     */
    fun getTavernCharacterMeta(context: Context, uri: Uri): Result<String> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot open the image, please check if the file is readable")

        val entries = readPngTextEntries(bytes)
        if (entries.isEmpty()) {
            error("No text chunk found in this PNG, please check if it is a character card")
        }

        // 与 ST 一致：ccv3 优先，其次 chara；keyword 大小写不敏感
        val payload = entries.firstOrNull { it.keyword.equals("ccv3", ignoreCase = true) }
            ?: entries.firstOrNull { it.keyword.equals("chara", ignoreCase = true) }
            ?: error("No 'chara'/'ccv3' chunk found, please check if it is a character card")

        val decoded = decodeCardPayload(payload.text)
        // 解出来的必须是 JSON 对象，否则说明这张卡不是标准格式
        decoded.trim().let { text ->
            require(text.startsWith("{")) { "Character data is not a JSON object" }
        }
        decoded
    }

    /** 一张 PNG 里的文本 chunk */
    private data class PngTextEntry(val keyword: String, val text: String)

    /**
     * 遍历 PNG chunk，取出所有 tEXt / iTXt 的 (keyword, text)。
     *
     * PNG 结构：8 字节魔数 + 若干 `[len(4B BE)][type(4B)][data(len)][crc(4B)]`。
     * 这里只读 type 与 data，不校验 CRC（角色卡不是安全边界，且省一次全量计算）。
     */
    private fun readPngTextEntries(bytes: ByteArray): List<PngTextEntry> {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        if (bytes.size < signature.size) return emptyList()
        for (i in signature.indices) {
            if (bytes[i] != signature[i]) return emptyList()
        }

        val out = mutableListOf<PngTextEntry>()
        var idx = signature.size
        while (idx + 8 <= bytes.size) {
            val length = readUInt32(bytes, idx)
            if (length < 0 || idx + 12L + length > bytes.size) break
            val type = String(bytes, idx + 4, 4, Charsets.US_ASCII)
            val dataStart = idx + 8
            val dataLen = length.toInt()

            when (type) {
                "tEXt" -> parseKeywordText(bytes, dataStart, dataLen)?.let(out::add)
                "iTXt" -> parseInternationalText(bytes, dataStart, dataLen)?.let(out::add)
                // zTXt 是 zlib 压缩的 Latin-1；角色卡不用它，遇到直接跳过
                "IEND" -> return out
            }
            idx = dataStart + dataLen + 4 // 跳过 CRC
        }
        return out
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    /** tEXt: keyword \0 text（都是 Latin-1，实际都是 ASCII） */
    private fun parseKeywordText(bytes: ByteArray, start: Int, len: Int): PngTextEntry? {
        val end = start + len
        var sep = -1
        for (i in start until end) {
            if (bytes[i] == 0.toByte()) {
                sep = i
                break
            }
        }
        if (sep < 0) return null
        val keyword = String(bytes, start, sep - start, Charsets.ISO_8859_1)
        val text = String(bytes, sep + 1, end - sep - 1, Charsets.ISO_8859_1)
        return PngTextEntry(keyword, text)
    }

    /** iTXt: keyword \0 compressionFlag compressionMethod languageTag \0 translatedKeyword \0 text */
    private fun parseInternationalText(bytes: ByteArray, start: Int, len: Int): PngTextEntry? {
        val end = start + len
        var sep = -1
        for (i in start until end) {
            if (bytes[i] == 0.toByte()) {
                sep = i
                break
            }
        }
        if (sep < 0 || sep + 2 >= end) return null
        val keyword = String(bytes, start, sep - start, Charsets.ISO_8859_1)
        val compressionFlag = bytes[sep + 1].toInt()
        // 压缩过的 iTXt（flag=1）这里不展开：角色卡不会这么写
        if (compressionFlag != 0) return null

        // 跳过 compressionMethod(1B) 后是两个 \0 结尾的字段，剩下的才是正文
        var cursor = sep + 3
        // languageTag
        while (cursor < end && bytes[cursor] != 0.toByte()) cursor++
        cursor++
        // translatedKeyword
        while (cursor < end && bytes[cursor] != 0.toByte()) cursor++
        cursor++
        if (cursor >= end) return null

        val text = String(bytes, cursor, end - cursor, Charsets.UTF_8)
        return PngTextEntry(keyword, text)
    }

    /**
     * 把 chunk 里的内容解成 JSON 字符串。
     *
     * 标准卡是 base64；但有人手工塞过明文 JSON，两种都接。
     */
    private fun decodeCardPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return trimmed
        return try {
            String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            error("Character data is neither base64 nor JSON")
        }
    }

    data class ImageInfo(
        val width: Int,
        val height: Int,
        val mimeType: String?
    )
}
