package com.piececounter.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Handles saving captured/imported plate images to app-private storage, loading bitmaps with
 * EXIF orientation correction, and generating synthetic 3D printer plate images for instant testing.
 */
object ImageStorageHelper {

    fun getPlatesDirectory(context: Context): File {
        val dir = File(context.filesDir, "plate_photos")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun createNewPlateImageFile(context: Context): File {
        val filename = "plate_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"
        return File(getPlatesDirectory(context), filename)
    }

    /**
     * Copies a photo from a gallery/content [Uri] into app-private plate storage and normalizes orientation.
     */
    fun copyUriToInternalFile(context: Context, uri: Uri): String? {
        return try {
            val bitmap = decodeSampledBitmapFromUri(context, uri, maxDimension = 1600) ?: return null
            val outFile = createNewPlateImageFile(context)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Loads a bitmap from an absolute file path with EXIF rotation applied and downsampling.
     */
    fun loadBitmapFromPath(path: String, maxDimension: Int = 1440): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, boundsOptions)
            val sampleSize = calculateInSampleSize(
                boundsOptions.outWidth,
                boundsOptions.outHeight,
                maxDimension
            )
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val rawBitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return null
            val exif = ExifInterface(path)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            rotateBitmapIfRequired(rawBitmap, orientation)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        maxDimension: Int
    ): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        val sampleSize = calculateInSampleSize(
            boundsOptions.outWidth,
            boundsOptions.outHeight,
            maxDimension
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        return rotateBitmapIfRequired(rawBitmap, orientation)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        val longest = max(width, height)
        if (longest > maxDimension) {
            val halfLongest = longest / 2
            while ((halfLongest / inSampleSize) >= maxDimension) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun rotateBitmapIfRequired(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Generates a batch of 3 realistic synthetic 3D printer build-plate photos so the user can test
     * multi-plate counting, zooming, deselection, and element aggregation immediately.
     *
     * - Plate 1: 12x Cyan "Cable Chain Link" parts arranged in a 4x3 grid on a dark PEI textured plate
     * - Plate 2: 15x Orange "M3 Standoff Spacer" parts arranged in a 5x3 grid
     * - Plate 3: 8x Cyan "Cable Chain Link" parts arranged in a 4x2 grid (same part as Plate 1!)
     */
    fun generateDemoPlateFiles(context: Context): List<String> {
        val specs = listOf(
            DemoPlateSpec(
                cols = 4,
                rows = 3,
                primaryColor = Color.rgb(34, 211, 238),
                highlightColor = Color.rgb(165, 243, 252),
                holeColor = Color.rgb(32, 36, 44),
                shapeType = PartShape.BRACKET
            ),
            DemoPlateSpec(
                cols = 5,
                rows = 3,
                primaryColor = Color.rgb(251, 146, 60),
                highlightColor = Color.rgb(254, 215, 170),
                holeColor = Color.rgb(32, 36, 44),
                shapeType = PartShape.GEAR_WASHER
            ),
            DemoPlateSpec(
                cols = 4,
                rows = 2,
                primaryColor = Color.rgb(34, 211, 238),
                highlightColor = Color.rgb(165, 243, 252),
                holeColor = Color.rgb(32, 36, 44),
                shapeType = PartShape.BRACKET
            )
        )

        return specs.map { spec ->
            val bmp = renderSyntheticPlateBitmap(spec)
            val file = createNewPlateImageFile(context)
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
            }
            file.absolutePath
        }
    }

    private enum class PartShape { BRACKET, GEAR_WASHER }

    private data class DemoPlateSpec(
        val cols: Int,
        val rows: Int,
        val primaryColor: Int,
        val highlightColor: Int,
        val holeColor: Int,
        val shapeType: PartShape
    )

    private fun renderSyntheticPlateBitmap(spec: DemoPlateSpec): Bitmap {
        val width = 800
        val height = 800
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw dark charcoal PEI build plate surface
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 34, 42)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Subtle build plate border frame (keep interior clean so parts stand out clearly)
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(52, 58, 70)
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawRoundRect(RectF(28f, 28f, width - 28f, height - 28f), 24f, 24f, framePaint)

        val partFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.primaryColor
            style = Paint.Style.FILL
        }
        val partRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.highlightColor
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.holeColor
            style = Paint.Style.FILL
        }

        val marginX = 115f
        val marginY = 135f
        val usableW = width - 2 * marginX
        val usableH = height - 2 * marginY
        val stepX = if (spec.cols > 1) usableW / (spec.cols - 1) else 0f
        val stepY = if (spec.rows > 1) usableH / (spec.rows - 1) else 0f

        val partHalfW = min(46f, stepX * 0.30f)
        val partHalfH = min(38f, stepY * 0.28f)

        for (r in 0 until spec.rows) {
            val cy = if (spec.rows > 1) marginY + r * stepY else height * 0.5f
            for (c in 0 until spec.cols) {
                val cx = if (spec.cols > 1) marginX + c * stepX else width * 0.5f
                val rect = RectF(
                    cx - partHalfW,
                    cy - partHalfH,
                    cx + partHalfW,
                    cy + partHalfH
                )
                when (spec.shapeType) {
                    PartShape.BRACKET -> {
                        canvas.drawRoundRect(rect, 12f, 12f, partFillPaint)
                        canvas.drawRoundRect(rect, 12f, 12f, partRimPaint)
                        // Two mounting holes inside the 3D printed bracket
                        canvas.drawCircle(cx - partHalfW * 0.42f, cy, partHalfH * 0.32f, holePaint)
                        canvas.drawCircle(cx + partHalfW * 0.42f, cy, partHalfH * 0.32f, holePaint)
                    }
                    PartShape.GEAR_WASHER -> {
                        val radius = min(partHalfW, partHalfH)
                        canvas.drawCircle(cx, cy, radius, partFillPaint)
                        canvas.drawCircle(cx, cy, radius * 0.85f, partRimPaint)
                        canvas.drawCircle(cx, cy, radius * 0.38f, holePaint)
                    }
                }
            }
        }
        return bitmap
    }
}
