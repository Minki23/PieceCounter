package com.piececounter.app.data.repository

import androidx.room.withTransaction
import com.piececounter.app.data.local.AppDatabase
import com.piececounter.app.data.local.CountingSessionEntity
import com.piececounter.app.data.local.PlateRecordEntity
import com.piececounter.app.domain.model.CountingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SessionRepository(
    private val database: AppDatabase
) {
    private val dao = database.pieceCounterDao()

    val allSessions: Flow<List<CountingSession>> =
        dao.observeAllSessions().map { list ->
            list.map { it.toDomainModel() }
        }

    val recentElementNames: Flow<List<String>> =
        dao.observeRecentElementNames()

    suspend fun getSession(sessionId: String): CountingSession? {
        return dao.getSessionWithPlates(sessionId)?.toDomainModel()
    }

    suspend fun saveSession(session: CountingSession) {
        database.withTransaction {
            dao.upsertSession(
                CountingSessionEntity(
                    sessionId = session.sessionId,
                    title = session.title,
                    createdAtEpochMs = session.createdAtEpochMs,
                    updatedAtEpochMs = session.updatedAtEpochMs
                )
            )
            dao.deletePlatesForSession(session.sessionId)
            dao.upsertPlates(
                session.plates.mapIndexed { index, plate ->
                    PlateRecordEntity.fromDomainModel(
                        plate.copy(plateOrderIndex = index)
                    )
                }
            )
        }
    }

    suspend fun deleteSession(sessionId: String) {
        dao.deleteSessionById(sessionId)
    }
}
