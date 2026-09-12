package com.unscathed.monitor.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import timber.log.Timber

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val type: String,
    val title: String,
    val detail: String,
)

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity)

    @Query("SELECT * FROM events ORDER BY timestampMs DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<EventEntity>>

    @Query("DELETE FROM events WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}

@Database(entities = [EventEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "watchdog.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

object EventType {
    const val STATE = "STATE"
    const val ALERT = "ALERT"
    const val RECOVERY = "RECOVERY"
    const val SYSTEM = "SYSTEM"
    const val ERROR = "ERROR"
}

/** Fire-and-forget event log; also mirrored to logcat through Timber. */
class EventLog(private val dao: EventDao, private val scope: CoroutineScope) {
    init {
        scope.launch(Dispatchers.IO) {
            dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
        }
    }

    fun recent(limit: Int = 300): Flow<List<EventEntity>> = dao.recent(limit)

    fun log(type: String, title: String, detail: String = "") {
        Timber.i("[%s] %s %s", type, title, detail)
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.insert(EventEntity(timestampMs = System.currentTimeMillis(), type = type, title = title, detail = detail))
            }.onFailure { Timber.e(it, "Event log write failed") }
        }
    }

    private companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
