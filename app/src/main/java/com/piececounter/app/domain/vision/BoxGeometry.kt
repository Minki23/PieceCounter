package com.piececounter.app.domain.vision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Exact geometry for (optionally rotated) [NormalizedRect] boxes.
 *
 * All computations are performed in an aspect-corrected space where the image height is 1 and the
 * image width is [imageAspect] (= imageWidthPx / imageHeightPx), so rotations match what is drawn
 * on screen (rotation happens in pixel space, not in normalized space).
 */
object BoxGeometry {

    /** Maximum allowed overlap between two counted boxes, as a fraction of the smaller box's area. */
    const val MAX_OVERLAP_RATIO = 0.10f

    /**
     * Returns the 4 corners (x0,y0,x1,y1,x2,y2,x3,y3) of [rect] in aspect-corrected space,
     * ordered TL, TR, BR, BL of the unrotated box.
     */
    fun corners(rect: NormalizedRect, imageAspect: Float): FloatArray {
        val cx = rect.centerX * imageAspect
        val cy = rect.centerY
        val hw = rect.width * imageAspect * 0.5f
        val hh = rect.height * 0.5f
        val rad = Math.toRadians(rect.angleDegrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val local = floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val lx = local[i * 2]
            val ly = local[i * 2 + 1]
            out[i * 2] = cx + lx * c - ly * s
            out[i * 2 + 1] = cy + lx * s + ly * c
        }
        return out
    }

    /** Area of [rect] in aspect-corrected space. */
    fun area(rect: NormalizedRect, imageAspect: Float): Float =
        rect.width * imageAspect * rect.height

    /**
     * Overlap ratio = intersection area / area of the smaller box (0 = disjoint, 1 = one box fully
     * inside the other). Handles arbitrary rotations exactly via convex polygon clipping.
     */
    fun overlapRatio(a: NormalizedRect, b: NormalizedRect, imageAspect: Float): Float {
        val areaA = area(a, imageAspect)
        val areaB = area(b, imageAspect)
        val minArea = min(areaA, areaB)
        if (minArea <= 1e-9f) return 0f

        // Fast reject using circumscribed circles
        val dx = (a.centerX - b.centerX) * imageAspect
        val dy = a.centerY - b.centerY
        val ra = hypot(a.width * imageAspect, a.height) * 0.5f
        val rb = hypot(b.width * imageAspect, b.height) * 0.5f
        if (hypot(dx, dy) >= ra + rb) return 0f

        val inter = if (a.angleDegrees == 0f && b.angleDegrees == 0f) {
            val iw = (min(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) * imageAspect
            val ih = (min(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
            iw * ih
        } else {
            convexIntersectionArea(corners(a, imageAspect), corners(b, imageAspect))
        }
        return (inter / minArea).coerceIn(0f, 1f)
    }

    /** True when [candidate] overlaps any of [others] by more than [maxRatio]. */
    fun overlapsAny(
        candidate: NormalizedRect,
        others: List<NormalizedRect>,
        imageAspect: Float,
        maxRatio: Float = MAX_OVERLAP_RATIO
    ): Boolean = others.any { overlapRatio(candidate, it, imageAspect) > maxRatio }

    /** Sutherland–Hodgman clipping of convex polygon [subject] by convex polygon [clip]; returns area. */
    private fun convexIntersectionArea(subject: FloatArray, clip: FloatArray): Float {
        var output = subject.toMutableList()
        val clipCount = clip.size / 2
        // Ensure counter-clockwise orientation of clip polygon for the inside test
        val clipSign = if (signedArea(clip) >= 0f) 1f else -1f

        for (i in 0 until clipCount) {
            if (output.isEmpty()) return 0f
            val ax = clip[i * 2]
            val ay = clip[i * 2 + 1]
            val bx = clip[((i + 1) % clipCount) * 2]
            val by = clip[((i + 1) % clipCount) * 2 + 1]

            val input = output
            output = ArrayList(input.size + 4)
            val n = input.size / 2
            for (j in 0 until n) {
                val px = input[j * 2]
                val py = input[j * 2 + 1]
                val qx = input[((j + 1) % n) * 2]
                val qy = input[((j + 1) % n) * 2 + 1]
                val pIn = clipSign * cross(ax, ay, bx, by, px, py) >= 0f
                val qIn = clipSign * cross(ax, ay, bx, by, qx, qy) >= 0f
                if (pIn) {
                    output.add(px); output.add(py)
                }
                if (pIn != qIn) {
                    val hit = segmentLineIntersection(px, py, qx, qy, ax, ay, bx, by)
                    if (hit != null) {
                        output.add(hit.first); output.add(hit.second)
                    }
                }
            }
        }
        if (output.size < 6) return 0f
        return abs(signedArea(output.toFloatArray()))
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)

    private fun segmentLineIntersection(
        px: Float, py: Float, qx: Float, qy: Float,
        ax: Float, ay: Float, bx: Float, by: Float
    ): Pair<Float, Float>? {
        val rX = qx - px
        val rY = qy - py
        val sX = bx - ax
        val sY = by - ay
        val denom = rX * sY - rY * sX
        if (abs(denom) < 1e-12f) return null
        val t = ((ax - px) * sY - (ay - py) * sX) / denom
        return Pair(px + t * rX, py + t * rY)
    }

    private fun signedArea(poly: FloatArray): Float {
        val n = poly.size / 2
        var sum = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1]
        }
        return sum * 0.5f
    }
}
