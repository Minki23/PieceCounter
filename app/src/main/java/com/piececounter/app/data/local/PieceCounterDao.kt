package com.piececounter.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PieceCounterDao {

    @Transaction
    @Query("SELECT * FROM counting_sessions ORDER BY updatedAtEpochMs DESC")
    fun observeAllSessions(): Flow<List<SessionWithPlatesRelation>>

    @Transaction
    @Query("SELECT * FROM counting_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionWithPlates(sessionId: String): SessionWithPlatesRelation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: CountingSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlates(plates: List<PlateRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlate(plate: PlateRecordEntity)

    @Query("DELETE FROM plate_records WHERE sessionId = :sessionId")
    suspend fun deletePlatesForSession(sessionId: String)

    @Query("DELETE FROM counting_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    /**
     * Returns all previously used element names ordered from most recently named to oldest,
     * so the app can suggest the previously named element and provide autocomplete chips.
     */
    @Query(
        """
        SELECT elementName FROM plate_records
        WHERE TRIM(elementName) != ''
        GROUP BY LOWER(TRIM(elementName))
        ORDER BY MAX(rowid) DESC
        """
    )
    fun observeRecentElementNames(): Flow<List<String>>
}
