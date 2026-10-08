package com.shobi.labourtracker

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

// Registered by the TM (first menu option)
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey val drrCode: String,
    val activity: String,
    val block: String,
    val subBlock: String,
    val startDate: String,      // yyyy-MM-dd
    val endDate: String = "",
    val status: String = "Ongoing",
    val progress: Int = 0,      // 0-100
    val synced: Boolean = false
)

// One row per project per day (saving again on the same day replaces it)
@Entity(tableName = "daily_updates", primaryKeys = ["drrCode", "date"])
data class DailyUpdate(
    val drrCode: String,
    val date: String,           // yyyy-MM-dd
    val status: String,
    val progress: Int,
    val skilled: Int,
    val unskilled: Int,
    val synced: Boolean = false
)

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProject(p: Project)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUpdate(u: DailyUpdate)

    @Query("SELECT * FROM projects WHERE block = :block ORDER BY subBlock")
    fun projects(block: String): Flow<List<Project>>

    @Query("SELECT * FROM projects")
    fun allProjects(): Flow<List<Project>>

    @Query("SELECT * FROM daily_updates")
    fun allUpdates(): Flow<List<DailyUpdate>>

    @Query("SELECT * FROM projects WHERE drrCode = :c")
    suspend fun getProject(c: String): Project?

    @Query("SELECT * FROM daily_updates WHERE drrCode = :c AND date = :d")
    suspend fun getUpdate(c: String, d: String): DailyUpdate?

    @Query("SELECT * FROM projects WHERE synced = 0")
    suspend fun unsyncedProjects(): List<Project>

    @Query("SELECT * FROM daily_updates WHERE synced = 0")
    suspend fun unsyncedUpdates(): List<DailyUpdate>

    @Query("SELECT COUNT(*) FROM projects WHERE synced = 0")
    fun pendingProjects(): Flow<Int>

    @Query("SELECT COUNT(*) FROM daily_updates WHERE synced = 0")
    fun pendingUpdates(): Flow<Int>

    @Query("UPDATE projects SET synced = 0")
    suspend fun markAllProjectsUnsynced()

    @Query("UPDATE daily_updates SET synced = 0")
    suspend fun markAllUpdatesUnsynced()

    @Query("UPDATE projects SET synced = 1 WHERE drrCode = :c")
    suspend fun markProjectSynced(c: String)

    @Query("UPDATE daily_updates SET synced = 1 WHERE drrCode = :c AND date = :d")
    suspend fun markUpdateSynced(c: String, d: String)
}

suspend fun AppDao.markAllUnsynced() { markAllProjectsUnsynced(); markAllUpdatesUnsynced() }

@Database(entities = [Project::class, DailyUpdate::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "labour.db")
                .build().also { inst = it }
        }
    }
}

// Connects to Report.kt
fun DailyUpdate.toEntry(p: Project) = DailyEntry(
    date = LocalDate.parse(date),
    block = p.block,
    subBlock = p.subBlock,
    drrCode = p.drrCode,
    activity = p.activity,
    skilled = skilled,
    unskilled = unskilled,
    progress = progress
)
