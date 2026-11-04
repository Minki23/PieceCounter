package com.piececounter.app.domain.model

import com.piececounter.app.domain.vision.NormalizedRect
import com.piececounter.app.domain.vision.PartDetectionBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAggregatorTest {

    private fun makePlate(
        index: Int,
        elementName: String,
        selectedCount: Int,
        deselectedCount: Int = 0
    ): PlateItem {
        val ref = NormalizedRect(0.2f, 0.2f, 0.3f, 0.3f)
        val boxes = mutableListOf<PartDetectionBox>()
        repeat(selectedCount) { i ->
            boxes.add(
                PartDetectionBox(
                    id = "sel_${index}_$i",
                    rect = ref,
                    confidence = 0.9f,
                    isSelected = true
                )
            )
        }
        repeat(deselectedCount) { i ->
            boxes.add(
                PartDetectionBox(
                    id = "desel_${index}_$i",
                    rect = ref,
                    confidence = 0.8f,
                    isSelected = false
                )
            )
        }
        return PlateItem(
            plateId = "plate_$index",
            sessionId = "session_1",
            plateOrderIndex = index,
            imagePath = "/fake/plate_$index.jpg",
            elementName = elementName,
            referenceRect = ref,
            detections = boxes
        )
    }

    @Test
    fun `aggregateByElementName sums matching names across multiple plates`() {
        val plates = listOf(
            makePlate(index = 0, elementName = "Cable Chain Link", selectedCount = 12, deselectedCount = 2),
            makePlate(index = 1, elementName = "M3 Spacer", selectedCount = 15, deselectedCount = 0),
            makePlate(index = 2, elementName = "cable chain link ", selectedCount = 8, deselectedCount = 1)
        )

        val session = CountingSession(
            sessionId = "session_1",
            title = "Voron Print Batch",
            createdAtEpochMs = 1700000000000L,
            updatedAtEpochMs = 1700000000000L,
            plates = plates
        )

        val aggregated = session.aggregatedElements
        assertEquals(2, aggregated.size)

        val chainLink = aggregated[0]
        assertEquals("Cable Chain Link", chainLink.elementName)
        assertEquals(20, chainLink.totalCount)
        assertEquals(2, chainLink.plateCount)

        val spacer = aggregated[1]
        assertEquals("M3 Spacer", spacer.elementName)
        assertEquals(15, spacer.totalCount)
        assertEquals(1, spacer.plateCount)

        assertEquals(35, session.grandTotalPieces)

        val clipboard = session.formatForClipboard()
        assertTrue(clipboard.contains("Cable Chain Link: 20 pcs (2 plates)"))
        assertTrue(clipboard.contains("M3 Spacer: 15 pcs (1 plate)"))
        assertTrue(clipboard.contains("Total: 35 pcs across 3 plates"))
    }
}
