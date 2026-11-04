package com.piececounter.app.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.piececounter.app.domain.model.CountingSession
import com.piececounter.app.domain.model.PlateItem
import com.piececounter.app.domain.vision.NormalizedRect
import com.piececounter.app.domain.vision.PartDetectionBox

@Entity(tableName = "counting_sessions")
data class CountingSessionEntity(
    @PrimaryKey val sessionId: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "plate_records",
    foreignKeys = [
        ForeignKey(
            entity = CountingSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class PlateRecordEntity(
    @PrimaryKey val plateId: String,
    val sessionId: String,
    val plateOrderIndex: Int,
    val imagePath: String,
    val elementName: String,
    val selectedCount: Int,
    val totalDetectedCount: Int,
    val referenceBoxJson: String,
    val detectedBoxesJson: String,
    val sensitivity: Float
) {
    fun toDomainModel(): PlateItem {
        val refRect = NormalizedRect.deserialize(referenceBoxJson)
            ?: NormalizedRect(0.35f, 0.35f, 0.55f, 0.55f)
        val detections = PartDetectionBox.deserializeList(detectedBoxesJson)
        return PlateItem(
            plateId = plateId,
            sessionId = sessionId,
            plateOrderIndex = plateOrderIndex,
            imagePath = imagePath,
            elementName = elementName,
            referenceRect = refRect,
            detections = detections,
            sensitivity = sensitivity
        )
    }

    companion object {
        fun fromDomainModel(item: PlateItem): PlateRecordEntity {
            return PlateRecordEntity(
                plateId = item.plateId,
                sessionId = item.sessionId,
                plateOrderIndex = item.plateOrderIndex,
                imagePath = item.imagePath,
                elementName = item.elementName,
                selectedCount = item.selectedCount,
                totalDetectedCount = item.totalDetectedCount,
                referenceBoxJson = item.referenceRect.serialize(),
                detectedBoxesJson = PartDetectionBox.serializeList(item.detections),
                sensitivity = item.sensitivity
            )
        }
    }
}

data class SessionWithPlatesRelation(
    @Embedded val session: CountingSessionEntity,
    @Relation(
        parentColumn = "sessionId",
        entityColumn = "sessionId"
    )
    val plates: List<PlateRecordEntity>
) {
    fun toDomainModel(): CountingSession {
        return CountingSession(
            sessionId = session.sessionId,
            title = session.title,
            createdAtEpochMs = session.createdAtEpochMs,
            updatedAtEpochMs = session.updatedAtEpochMs,
            plates = plates
                .sortedBy { it.plateOrderIndex }
                .map { it.toDomainModel() }
        )
    }
}
