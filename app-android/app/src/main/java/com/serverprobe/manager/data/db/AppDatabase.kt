package com.serverprobe.manager.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ProbeHostDao {
    @Query("SELECT * FROM probe_hosts ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ProbeHostEntity>>

    @Query("SELECT * FROM probe_hosts ORDER BY sortOrder, id")
    suspend fun all(): List<ProbeHostEntity>

    @Query("SELECT * FROM probe_hosts WHERE id = :id")
    suspend fun byId(id: Long): ProbeHostEntity?

    @Query("SELECT * FROM probe_hosts WHERE host = :host AND port = :port LIMIT 1")
    suspend fun findByAddress(host: String, port: Int): ProbeHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: ProbeHostEntity): Long

    @Delete
    suspend fun delete(e: ProbeHostEntity)
}

@Dao
interface SshHostDao {
    @Query("SELECT * FROM ssh_hosts ORDER BY alias COLLATE NOCASE")
    fun observeAll(): Flow<List<SshHostEntity>>

    @Query("SELECT * FROM ssh_hosts ORDER BY alias COLLATE NOCASE")
    suspend fun all(): List<SshHostEntity>

    @Query("SELECT * FROM ssh_hosts WHERE id = :id")
    suspend fun byId(id: Long): SshHostEntity?

    @Query("SELECT * FROM ssh_hosts WHERE host = :host AND port = :port AND username = :username LIMIT 1")
    suspend fun findMatch(host: String, port: Int, username: String): SshHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: SshHostEntity): Long

    @Delete
    suspend fun delete(e: SshHostEntity)
}

@Database(
    entities = [ProbeHostEntity::class, SshHostEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun probeHostDao(): ProbeHostDao
    abstract fun sshHostDao(): SshHostDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "serverprobe.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
