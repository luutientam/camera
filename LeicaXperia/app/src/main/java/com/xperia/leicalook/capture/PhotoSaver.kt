package com.xperia.leicalook.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.location.Location
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object PhotoSaver {

    data class Meta(
        val result: CaptureResult,
        val lookName: String,
        val timeMs: Long = System.currentTimeMillis(),
        val location: Location? = null,
    )

    /** Encodes [bitmap] as JPEG, writes EXIF, and publishes it in DCIM/LeicaLook. */
    fun save(context: Context, bitmap: Bitmap, meta: Meta, quality: Int = 95): Uri {
        val name = "LEICA_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(meta.timeMs)) + ".jpg"
        // Encode into app cache first: ExifInterface needs a seekable file, MediaStore streams do not qualify.
        val tmp = File(context.cacheDir, name)
        tmp.outputStream().buffered(1 shl 20).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        writeExif(tmp, bitmap, meta)

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/LeicaLook")
            put(MediaStore.Images.Media.DATE_TAKEN, meta.timeMs)
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 20) } }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        tmp.delete()
        return uri
    }

    private fun writeExif(file: File, bmp: Bitmap, meta: Meta) {
        val r = meta.result
        val exif = ExifInterface(file)
        val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(meta.timeMs))
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Leica Look (${meta.lookName})")
        exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
        exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bmp.width.toString())
        exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bmp.height.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, bmp.width.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, bmp.height.toString())
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
        r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e9).toString())
        }
        r.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
            val boost = r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, (it * boost / 100).toString())
        }
        r.get(CaptureResult.LENS_APERTURE)?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
        r.get(CaptureResult.LENS_FOCAL_LENGTH)?.let { f ->
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, rational(f.toDouble()))
        }
        r.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)?.let {
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, "$it/12")
        }
        exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, "0")
        exif.setAttribute(ExifInterface.TAG_FLASH, "0")
        meta.location?.let { loc ->
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, dms(loc.latitude))
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (loc.latitude >= 0) "N" else "S")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, dms(loc.longitude))
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (loc.longitude >= 0) "E" else "W")
            if (loc.hasAltitude()) {
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, rational(loc.altitude))
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (loc.altitude >= 0) "0" else "1")
            }
        }
        exif.saveAttributes()
    }

    private fun dms(v: Double): String {
        val a = abs(v)
        val d = a.toInt(); val m = ((a - d) * 60).toInt(); val sec = (a - d - m / 60.0) * 3600
        return "$d/1,$m/1,${Math.round(sec * 1000)}/1000"
    }

    private fun rational(v: Double): String {
        val den = 1000
        return "${Math.round(abs(v) * den)}/$den"
    }
}
