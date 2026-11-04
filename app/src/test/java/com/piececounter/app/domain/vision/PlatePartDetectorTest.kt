package com.piececounter.app.domain.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatePartDetectorTest {

    /**
     * Builds a synthetic 240x240 3D printer build plate containing a 3x3 grid (9 total)
     * of bright cyan 3D-printed parts on a dark charcoal PEI plate background.
     */
    private fun createSyntheticPlateBuffer(): Pair<PlatePartDetector.PixelBuffer, NormalizedRect> {
        val w = 240
        val h = 240
        val bgArgb = (0xFF shl 24) or (30 shl 16) or (34 shl 8) or 42
        val partArgb = (0xFF shl 24) or (34 shl 16) or (211 shl 8) or 238
        val holeArgb = bgArgb

        val pixels = IntArray(w * h) { bgArgb }

        val centers = listOf(55, 120, 185)
        val halfW = 14
        val halfH = 12

        for (cy in centers) {
            for (cx in centers) {
                for (y in (cy - halfH)..(cy + halfH)) {
                    for (x in (cx - halfW)..(cx + halfW)) {
                        val isInnerHole = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= 16
                        pixels[y * w + x] = if (isInnerHole) holeArgb else partArgb
                    }
                }
            }
        }

        // Reference rect around the top-left part centered at (55, 55)
        val refRect = NormalizedRect(
            left = (55 - halfW - 2).toFloat() / w,
            top = (55 - halfH - 2).toFloat() / h,
            right = (55 + halfW + 2).toFloat() / w,
            bottom = (55 + halfH + 2).toFloat() / h
        )

        return PlatePartDetector.PixelBuffer(pixels, w, h) to refRect
    }

    @Test
    fun `detectCandidateParts finds foreground parts on build plate`() {
        val (buffer, _) = createSyntheticPlateBuffer()
        val candidates = PlatePartDetector.detectCandidateParts(buffer)
        assertTrue("Expected at least 6 candidate parts segmented, got ${candidates.size}", candidates.size >= 6)
    }

    @Test
    fun `matchPartsFromReference finds all 9 parts and selects all by default`() {
        val (buffer, refRect) = createSyntheticPlateBuffer()
        val result = PlatePartDetector.matchPartsFromReference(
            buffer = buffer,
            referenceRect = refRect,
            sensitivity = 0.60f
        )

        assertEquals(9, result.activeDetections.size)
        assertTrue(
            "All detected parts must be selected by default",
            result.activeDetections.all { it.isSelected }
        )
    }

    @Test
    fun `applySensitivityAndSelections preserves deselected and manual parts`() {
        val (buffer, refRect) = createSyntheticPlateBuffer()
        val result = PlatePartDetector.matchPartsFromReference(
            buffer = buffer,
            referenceRect = refRect,
            sensitivity = 0.60f
        )

        val firstId = result.activeDetections.first().id
        val manualBox = PartDetectionBox(
            id = "manual_1",
            rect = NormalizedRect(0.86f, 0.86f, 0.96f, 0.96f),
            confidence = 1.0f,
            isSelected = true,
            isManual = true
        )

        val updated = PlatePartDetector.applySensitivityAndSelections(
            allMatchedPeaks = result.allMatchedPeaks,
            sensitivity = 0.65f,
            deselectedIds = setOf(firstId),
            manualBoxes = listOf(manualBox)
        )

        // 9 auto-detected + 1 manual = 10 total; 1 deselected -> 9 selected
        assertEquals(10, updated.size)
        assertEquals(9, updated.count { it.isSelected })
    }

    @Test
    fun `PartDetectionBox serialization round-trips accurately`() {
        val boxes = listOf(
            PartDetectionBox(
                id = "p1",
                rect = NormalizedRect(0.1f, 0.2f, 0.3f, 0.4f),
                confidence = 0.92f,
                isSelected = true,
                isManual = false
            ),
            PartDetectionBox(
                id = "p2",
                rect = NormalizedRect(0.5f, 0.5f, 0.7f, 0.7f),
                confidence = 0.84f,
                isSelected = false,
                isManual = true
            )
        )

        val serialized = PartDetectionBox.serializeList(boxes)
        val restored = PartDetectionBox.deserializeList(serialized)

        assertEquals(2, restored.size)
        assertEquals("p1", restored[0].id)
        assertTrue(restored[0].isSelected)
        assertEquals("p2", restored[1].id)
        assertEquals(false, restored[1].isSelected)
        assertTrue(restored[1].isManual)
        assertNotNull(NormalizedRect.deserialize(boxes[0].rect.serialize()))
    }

    @Test
    fun `angled NormalizedRect serializes and preserves angle across template matching`() {
        val (buffer, baseRefRect) = createSyntheticPlateBuffer()
        val angledRef = baseRefRect.copy(angleDegrees = 35f)

        val restored = NormalizedRect.deserialize(angledRef.serialize())
        assertNotNull(restored)
        assertEquals(35f, restored!!.angleDegrees, 0.01f)
        assertTrue(angledRef.contains(angledRef.centerX, angledRef.centerY))

        val result = PlatePartDetector.matchPartsFromReference(
            buffer = buffer,
            referenceRect = angledRef,
            sensitivity = 0.60f
        )
        assertTrue(result.activeDetections.isNotEmpty())
        // Verify that no two detections overlap by more than 10%
        val boxes = result.activeDetections
        for (i in boxes.indices) {
            for (j in (i + 1) until boxes.size) {
                val overlap = BoxGeometry.overlapRatio(boxes[i].rect, boxes[j].rect, 1f)
                assertTrue(
                    "Detections $i and $j overlap by $overlap (> 0.10)",
                    overlap <= BoxGeometry.MAX_OVERLAP_RATIO + 1e-4f
                )
            }
        }
    }

    @Test
    fun `BoxGeometry computes exact overlap ratio and enforces 10 percent maximum overlap`() {
        val base = NormalizedRect(0.20f, 0.20f, 0.40f, 0.40f, angleDegrees = 0f)
        // Identical box -> 100% overlap
        assertEquals(1.0f, BoxGeometry.overlapRatio(base, base, 1f), 0.01f)

        // Shifted horizontally by 95% of width -> 5% overlap (allowed, <= 10%)
        val shifted95 = NormalizedRect(0.39f, 0.20f, 0.59f, 0.40f, angleDegrees = 0f)
        val overlap5 = BoxGeometry.overlapRatio(base, shifted95, 1f)
        assertEquals(0.05f, overlap5, 0.01f)
        assertTrue(overlap5 <= BoxGeometry.MAX_OVERLAP_RATIO)

        // Shifted horizontally by 80% of width -> 20% overlap (exceeds 10% limit)
        val shifted80 = NormalizedRect(0.36f, 0.20f, 0.56f, 0.40f, angleDegrees = 0f)
        val overlap20 = BoxGeometry.overlapRatio(base, shifted80, 1f)
        assertEquals(0.20f, overlap20, 0.01f)
        assertTrue(overlap20 > BoxGeometry.MAX_OVERLAP_RATIO)

        // Verify applySensitivityAndSelections suppresses any peak that overlaps a manual box by > 10%
        val (buffer, refRect) = createSyntheticPlateBuffer()
        val result = PlatePartDetector.matchPartsFromReference(
            buffer = buffer,
            referenceRect = refRect,
            sensitivity = 0.20f
        )
        val firstAuto = result.activeDetections.first().rect
        val overlappingManual = PartDetectionBox(
            id = "manual_overlap",
            rect = firstAuto.copy(
                left = firstAuto.left + firstAuto.width * 0.15f,
                right = firstAuto.right + firstAuto.width * 0.15f
            ),
            confidence = 1.0f,
            isSelected = true,
            isManual = true
        )
        val combined = PlatePartDetector.applySensitivityAndSelections(
            allMatchedPeaks = result.allMatchedPeaks,
            sensitivity = 0.20f,
            deselectedIds = emptySet(),
            manualBoxes = listOf(overlappingManual),
            imageAspect = 1f
        )
        for (i in combined.indices) {
            for (j in (i + 1) until combined.size) {
                val ratio = BoxGeometry.overlapRatio(combined[i].rect, combined[j].rect, 1f)
                assertTrue(
                    "Boxes $i and $j overlapped by $ratio (> 10%)",
                    ratio <= BoxGeometry.MAX_OVERLAP_RATIO + 1e-4f
                )
            }
        }
    }

    @Test
    fun `matchPartsFromReference handles uneven printer lighting and perspective scale variation`() {
        val w = 240
        val h = 240
        val pixels = IntArray(w * h)
        val centers = listOf(55, 120, 185)
        // Row-dependent scale simulating camera tilt (back row smaller, front row larger)
        val rowScales = listOf(0.92f, 1.00f, 1.08f)
        val baseHalfW = 14
        val baseHalfH = 12

        // Fill background with a diagonal illumination gradient (brighter top-left, shadowed bottom-right)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val illumOffset = ((1f - (x + y) / (2f * w)) * 32f - 16f).toInt()
                val r = (30 + illumOffset).coerceIn(0, 255)
                val g = (34 + illumOffset).coerceIn(0, 255)
                val b = (42 + illumOffset).coerceIn(0, 255)
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        for ((rowIdx, cy) in centers.withIndex()) {
            val scale = rowScales[rowIdx]
            val hw = (baseHalfW * scale).toInt()
            val hh = (baseHalfH * scale).toInt()
            for (cx in centers) {
                val illumOffset = ((1f - (cx + cy) / (2f * w)) * 32f - 16f).toInt()
                for (y in (cy - hh)..(cy + hh)) {
                    for (x in (cx - hw)..(cx + hw)) {
                        val isInnerHole = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= (16 * scale * scale).toInt()
                        if (!isInnerHole) {
                            val pr = (34 + illumOffset).coerceIn(0, 255)
                            val pg = (211 + illumOffset).coerceIn(0, 255)
                            val pb = (238 + illumOffset).coerceIn(0, 255)
                            pixels[y * w + x] = (0xFF shl 24) or (pr shl 16) or (pg shl 8) or pb
                        }
                    }
                }
            }
        }

        // Reference box on the middle-row left part (scale = 1.00f)
        val refRect = NormalizedRect(
            left = (55 - baseHalfW - 2).toFloat() / w,
            top = (120 - baseHalfH - 2).toFloat() / h,
            right = (55 + baseHalfW + 2).toFloat() / w,
            bottom = (120 + baseHalfH + 2).toFloat() / h
        )

        val buffer = PlatePartDetector.PixelBuffer(pixels, w, h)
        val result = PlatePartDetector.matchPartsFromReference(
            buffer = buffer,
            referenceRect = refRect,
            sensitivity = 0.65f
        )

        assertEquals(9, result.activeDetections.size)
        for (i in result.activeDetections.indices) {
            for (j in (i + 1) until result.activeDetections.size) {
                val overlap = BoxGeometry.overlapRatio(
                    result.activeDetections[i].rect,
                    result.activeDetections[j].rect,
                    1f
                )
                assertTrue(overlap <= BoxGeometry.MAX_OVERLAP_RATIO + 1e-4f)
            }
        }
    }

    @Test
    fun `sensitivity slider dynamically adjusts active detections count`() {
        val (buffer, refRect) = createSyntheticPlateBuffer()
        val result = PlatePartDetector.matchPartsFromReference(
            buffer = buffer,
            referenceRect = refRect,
            sensitivity = 0.60f
        )

        val lowSens = PlatePartDetector.applySensitivityAndSelections(
            allMatchedPeaks = result.allMatchedPeaks,
            sensitivity = 0.15f,
            deselectedIds = emptySet(),
            manualBoxes = emptyList()
        )
        val midSens = PlatePartDetector.applySensitivityAndSelections(
            allMatchedPeaks = result.allMatchedPeaks,
            sensitivity = 0.35f,
            deselectedIds = emptySet(),
            manualBoxes = emptyList()
        )
        val defaultSens = PlatePartDetector.applySensitivityAndSelections(
            allMatchedPeaks = result.allMatchedPeaks,
            sensitivity = 0.60f,
            deselectedIds = emptySet(),
            manualBoxes = emptyList()
        )

        assertTrue(
            "Low sensitivity (15%) should return fewer parts than medium (35%), got ${lowSens.size} vs ${midSens.size}",
            lowSens.size < midSens.size
        )
        assertTrue(
            "Medium sensitivity (35%) should return fewer parts than default (60%), got ${midSens.size} vs ${defaultSens.size}",
            midSens.size < defaultSens.size
        )
        assertEquals(9, defaultSens.size)
    }
}
