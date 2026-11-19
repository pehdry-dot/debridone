package com.debridone.platform.media

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure

/** Decoded pixels: [width] × [height], four bytes per pixel in R, G, B, A order, alpha premultiplied, rows top to bottom. */
class RgbaPixels(val width: Int, val height: Int, val bytes: ByteArray)

/**
 * Decodes pictures with the operating system's own decoders, for the formats the app's drawing
 * library does not read. On macOS that is ImageIO, which reads AVIF (macOS 13 and later), HEIF and
 * JPEG XL (macOS 14 and later) among others. Elsewhere there is no such decoder and [decode]
 * returns null.
 */
object SystemImageDecoder {
    /** Largest picture decoded this way, in pixels (about 400 MB of memory at four bytes each). */
    private const val MAX_PIXELS = 100_000_000L

    private val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    /** True when this computer has a system decoder [decode] can use. */
    val available: Boolean get() = isMac && apis != null

    /** The picture in [bytes], or null when the system cannot decode it (or there is no system decoder). */
    fun decode(bytes: ByteArray): RgbaPixels? {
        if (!isMac || bytes.isEmpty()) return null
        val api = apis ?: return null
        val data = api.cf.CFDataCreate(null, bytes, NativeLong(bytes.size.toLong())) ?: return null
        try {
            val source = api.io.CGImageSourceCreateWithData(data, null) ?: return null
            try {
                if (api.io.CGImageSourceGetCount(source).toLong() < 1) return null
                val image = api.io.CGImageSourceCreateImageAtIndex(source, NativeLong(0), null) ?: return null
                try {
                    val width = api.cg.CGImageGetWidth(image).toLong()
                    val height = api.cg.CGImageGetHeight(image).toLong()
                    if (width <= 0 || height <= 0 || width * height > MAX_PIXELS) return null
                    val rowBytes = width * 4
                    val memory = Memory(rowBytes * height)
                    memory.clear()
                    val space = api.cg.CGColorSpaceCreateDeviceRGB() ?: return null
                    try {
                        val context = api.cg.CGBitmapContextCreate(
                            memory, NativeLong(width), NativeLong(height), NativeLong(8), NativeLong(rowBytes), space,
                            K_CG_IMAGE_ALPHA_PREMULTIPLIED_LAST or K_CG_BITMAP_BYTE_ORDER_32_BIG,
                        ) ?: return null
                        try {
                            api.cg.CGContextDrawImage(context, CGRect.ByValue(0.0, 0.0, width.toDouble(), height.toDouble()), image)
                        } finally {
                            api.cf.CFRelease(context)
                        }
                    } finally {
                        api.cf.CFRelease(space)
                    }
                    return RgbaPixels(width.toInt(), height.toInt(), memory.getByteArray(0, (rowBytes * height).toInt()))
                } finally {
                    api.cf.CFRelease(image)
                }
            } finally {
                api.cf.CFRelease(source)
            }
        } catch (_: Throwable) {
            return null
        } finally {
            api.cf.CFRelease(data)
        }
    }

    private const val K_CG_IMAGE_ALPHA_PREMULTIPLIED_LAST = 1
    private const val K_CG_BITMAP_BYTE_ORDER_32_BIG = 4 shl 12

    private class Apis(val cf: CoreFoundation, val io: ImageIO, val cg: CoreGraphics)

    private val apis: Apis? by lazy {
        if (!isMac) return@lazy null
        runCatching {
            Apis(
                Native.load("CoreFoundation", CoreFoundation::class.java),
                Native.load("ImageIO", ImageIO::class.java),
                Native.load("CoreGraphics", CoreGraphics::class.java),
            )
        }.getOrNull()
    }

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFDataCreate(allocator: Pointer?, bytes: ByteArray, length: NativeLong): Pointer?
        fun CFRelease(reference: Pointer)
    }

    @Suppress("FunctionName")
    private interface ImageIO : Library {
        fun CGImageSourceCreateWithData(data: Pointer, options: Pointer?): Pointer?
        fun CGImageSourceGetCount(source: Pointer): NativeLong
        fun CGImageSourceCreateImageAtIndex(source: Pointer, index: NativeLong, options: Pointer?): Pointer?
    }

    @Suppress("FunctionName")
    private interface CoreGraphics : Library {
        fun CGImageGetWidth(image: Pointer): NativeLong
        fun CGImageGetHeight(image: Pointer): NativeLong
        fun CGColorSpaceCreateDeviceRGB(): Pointer?
        fun CGBitmapContextCreate(
            data: Pointer, width: NativeLong, height: NativeLong, bitsPerComponent: NativeLong, bytesPerRow: NativeLong, space: Pointer, bitmapInfo: Int,
        ): Pointer?
        fun CGContextDrawImage(context: Pointer, rect: CGRect.ByValue, image: Pointer)
    }

    /** Quartz's rectangle: origin and size, four doubles. */
    @Structure.FieldOrder("x", "y", "width", "height")
    open class CGRect(@JvmField var x: Double = 0.0, @JvmField var y: Double = 0.0, @JvmField var width: Double = 0.0, @JvmField var height: Double = 0.0) : Structure() {
        class ByValue(x: Double, y: Double, width: Double, height: Double) : CGRect(x, y, width, height), Structure.ByValue
    }
}
