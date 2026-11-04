package com.piececounter.app.domain.vision

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Normalized rectangle in [0f..1f] image coordinates, optionally rotated by [angleDegrees]
 * clockwise around its center ([centerX], [centerY]).
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val angleDegrees: Float = 0f
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val area: Float get() = width * height

    /**
     * Checks whether normalized point ([x], [y]) is inside this (potentially rotated) rectangle,
     * with an optional [margin] around each half-dimension.
     *
     * Rotation is evaluated in pixel space, so pass [imageAspect] (= image width / height) for
     * non-square images to match exactly what is drawn on screen.
     */
    fun contains(x: Float, y: Float, margin: Float = 0f, imageAspect: Float = 1f): Boolean {
        if (angleDegrees == 0f) {
            val halfW = width * 0.5f + margin
            val halfH = height * 0.5f + margin
            val dx0 = x - centerX
            val dy0 = y - centerY
            return dx0 in -halfW..halfW && dy0 in -halfH..halfH
        }
        // Aspect-corrected space: height = 1, width = imageAspect
        val halfW = (width * 0.5f + margin) * imageAspect
        val halfH = height * 0.5f + margin
        val dx = (x - centerX) * imageAspect
        val dy = y - centerY
        val rad = -Math.toRadians(angleDegrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val localX = dx * cosA - dy * sinA
        val localY = dx * sinA + dy * cosA
        return localX in -halfW..halfW && localY in -halfH..halfH
    }

    fun clamp(minSize: Float = 0.015f): NormalizedRect {
        val l = left.coerceIn(0f, 1f - minSize)
        val t = top.coerceIn(0f, 1f - minSize)
        val r = right.coerceIn(l + minSize, 1f)
        val b = bottom.coerceIn(t + minSize, 1f)
        val normalizedAngle = ((angleDegrees + 180f) % 360f + 360f) % 360f - 180f
        return NormalizedRect(l, t, r, b, normalizedAngle)
    }

    fun iou(other: NormalizedRect): Float {
        val interLeft = max(left, other.left)
        val interTop = max(top, other.top)
        val interRight = min(right, other.right)
        val interBottom = min(bottom, other.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f
        val unionArea = area + other.area - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    fun serialize(): String = "$left,$top,$right,$bottom,$angleDegrees"

    companion object {
        fun deserialize(raw: String): NormalizedRect? {
            if (raw.isBlank()) return null
            val parts = raw.split(",")
            if (parts.size < 4) return null
            return try {
                val angle = if (parts.size >= 5) parts[4].toFloatOrNull() ?: 0f else 0f
                NormalizedRect(
                    left = parts[0].toFloat(),
                    top = parts[1].toFloat(),
                    right = parts[2].toFloat(),
                    bottom = parts[3].toFloat(),
                    angleDegrees = angle
                ).clamp()
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}

/**
 * Represents a single detected or manually added element on a 3D printer plate.
 * By default, [isSelected] is true so all detected elements are selected initially.
 */
data class PartDetectionBox(
    val id: String,
    val rect: NormalizedRect,
    val confidence: Float,
    val isSelected: Boolean = true,
    val isManual: Boolean = false
) {
    fun serialize(): String {
        return "$id|${rect.serialize()}|$confidence|$isSelected|$isManual"
    }

    companion object {
        fun deserialize(raw: String): PartDetectionBox? {
            val tokens = raw.split("|")
            if (tokens.size != 5) return null
            val rect = NormalizedRect.deserialize(tokens[1]) ?: return null
            return try {
                PartDetectionBox(
                    id = tokens[0],
                    rect = rect,
                    confidence = tokens[2].toFloat(),
                    isSelected = tokens[3].toBooleanStrictOrNull() ?: true,
                    isManual = tokens[4].toBooleanStrictOrNull() ?: false
                )
            } catch (_: Exception) {
                null
            }
        }

        fun serializeList(boxes: List<PartDetectionBox>): String {
            return boxes.joinToString(separator = ";") { it.serialize() }
        }

        fun deserializeList(raw: String): List<PartDetectionBox> {
            if (raw.isBlank()) return emptyList()
            return raw.split(";").mapNotNull { deserialize(it) }
        }
    }
}

/**
 * Result of analyzing a plate image with a reference part template.
 */
data class PlateAnalysisResult(
    val referenceRect: NormalizedRect,
    val candidateRects: List<NormalizedRect>,
    val allMatchedPeaks: List<PartDetectionBox>,
    val activeDetections: List<PartDetectionBox>
)
