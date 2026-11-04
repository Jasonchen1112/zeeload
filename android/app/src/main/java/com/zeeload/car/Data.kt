package com.zeeload.car

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "apps")
data class ManagedApp(
    @PrimaryKey val packageName: String,
    val name: String,
    val url: String = "",
    val origin: String,
    val versionCode: Long = 0,
    val versionName: String = "",
    val certificateSha256: String = "",
    val apkSha256: String = "",
    val installedAt: Long = 0,
    val updatedAt: Long = 0,
    val lastResult: String = ""
)

@Entity(tableName = "history")
data class InstallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val at: Long,
    val versionCode: Long,
    val result: String,
    val apkSha256: String
)

@Entity(tableName = "events")
data class DiagnosticEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val level: String,
    val stage: String,
    val packageName: String = "",
    val versionCode: Long = 0,
    val url: String = "",
    val httpStatus: Int = 0,
    val errorCode: String = "",
    val message: String = "",
    val sizeBytes: Int = 0,
    val uploaded: Boolean = false
)

@Dao
interface AppDao {
    @Query("SELECT * FROM apps ORDER BY name COLLATE NOCASE")
    fun observe(): Flow<List<ManagedApp>>

    @Query("SELECT * FROM apps WHERE packageName = :name LIMIT 1")
    suspend fun get(name: String): ManagedApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(app: ManagedApp)

    @Insert
    suspend fun record(record: InstallRecord)

    @Query("SELECT * FROM history ORDER BY id DESC LIMIT 1")
    fun latestRecord(): Flow<InstallRecord?>
}

@Dao
interface EventDao {
    @Insert
    suspend fun add(event: DiagnosticEvent)

    @Query("SELECT * FROM events WHERE uploaded = 0 ORDER BY id LIMIT :limit")
    suspend fun pending(limit: Int): List<DiagnosticEvent>

    @Query("DELETE FROM events WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<Long>)

    @Query("DELETE FROM events WHERE ts < :cutoff")
    suspend fun deleteBefore(cutoff: Long)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM events")
    suspend fun totalBytes(): Long

    @Query("SELECT id FROM events ORDER BY id LIMIT 1")
    suspend fun oldestId(): Long?

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM events WHERE uploaded = 0")
    suspend fun pendingCount(): Int

    @Query("DELETE FROM events")
    suspend fun clear()
}

@Database(
    entities = [ManagedApp::class, InstallRecord::class, DiagnosticEvent::class],
    version = 1,
    exportSchema = false
)
abstract class CarDatabase : RoomDatabase() {
    abstract fun apps(): AppDao
    abstract fun events(): EventDao

    companion object {
        fun open(context: Context): CarDatabase =
            Room.databaseBuilder(context, CarDatabase::class.java, "zeeload-car.db").build()
    }
}
