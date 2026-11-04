package com.piececounter.app.domain.model

import com.piececounter.app.domain.vision.NormalizedRect
import com.piececounter.app.domain.vision.PartDetectionBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Domain model for a single 3D printer plate within a counting session.
 */
data class PlateItem(
    val plateId: String,
    val sessionId: String,
    val plateOrderIndex: Int,
    val imagePath: String,
    val elementName: String,
    val referenceRect: NormalizedRect,
    val detections: List<PartDetectionBox>,
    val sensitivity: Float = 0.60f
) {
    val selectedCount: Int
        get() = detections.count { it.isSelected }

    val totalDetectedCount: Int
        get() = detections.size

    val isNamed: Boolean
        get() = elementName.isNotBlank()
}

/**
 * Aggregated count of a single named element summed across one or more plates.
 */
data class AggregatedElementCount(
    val elementName: String,
    val totalCount: Int,
    val plateCount: Int,
    val plateBreakdown: List<Pair<Int, Int>> // Pair(plateIndex 1-based, count on that plate)
)

/**
 * Complete counting session containing multiple plates and their summed totals by element name.
 */
data class CountingSession(
    val sessionId: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val plates: List<PlateItem>
) {
    val aggregatedElements: List<AggregatedElementCount>
        get() = SessionAggregator.aggregateByElementName(plates)

    val grandTotalPieces: Int
        get() = plates.sumOf { it.selectedCount }

    val totalPlates: Int
        get() = plates.size

    fun formatForClipboard(): String = SessionAggregator.formatForClipboard(this)
}

/**
 * Aggregates element counts across multiple plates by element name and formats summaries for clipboard.
 */
object SessionAggregator {

    /**
     * Sums all selected elements across [plates] grouped by normalized element name
     * (case-insensitive grouping while preserving the user's preferred display casing).
     */
    fun aggregateByElementName(plates: List<PlateItem>): List<AggregatedElementCount> {
        if (plates.isEmpty()) return emptyList()

        data class MutableGroup(
            val displayName: String,
            var totalCount: Int = 0,
            var plateCount: Int = 0,
            val breakdown: MutableList<Pair<Int, Int>> = mutableListOf()
        )

        val groups = linkedMapOf<String, MutableGroup>()
        plates.sortedBy { it.plateOrderIndex }.forEachIndexed { idx, plate ->
            val rawName = plate.elementName.trim().ifEmpty { "Unnamed Element" }
            val key = rawName.lowercase(Locale.ROOT)
            val group = groups.getOrPut(key) { MutableGroup(displayName = rawName) }
            val count = plate.selectedCount
            group.totalCount += count
            group.plateCount += 1
            group.breakdown.add((idx + 1) to count)
        }

        return groups.values.map { g ->
            AggregatedElementCount(
                elementName = g.displayName,
                totalCount = g.totalCount,
                plateCount = g.plateCount,
                plateBreakdown = g.breakdown.toList()
            )
        }
    }

    /**
     * Generates a clean, human-readable summary string ready to be copied to the clipboard.
     */
    fun formatForClipboard(session: CountingSession): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(session.updatedAtEpochMs))
        val aggregated = aggregateByElementName(session.plates)
        val sb = StringBuilder()
        sb.appendLine("${session.title} ($dateStr)")
        sb.appendLine("----------------------------------------")
        if (aggregated.isEmpty()) {
            sb.appendLine("No elements counted.")
        } else {
            for (item in aggregated) {
                val platesLabel = if (item.plateCount == 1) "1 plate" else "${item.plateCount} plates"
                sb.appendLine("• ${item.elementName}: ${item.totalCount} pcs ($platesLabel)")
            }
        }
        sb.appendLine("----------------------------------------")
        return sb.toString()
    }
}
