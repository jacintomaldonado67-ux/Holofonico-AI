package com.example.data

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

// --- Entities ---

@Entity(tableName = "acoustic_presets")
data class AcousticPreset(
    @PrimaryKey val id: String, // e.g. "catedral", "estudio", "parque", "pasillo"
    val name: String,
    val type: String, // Cerrado, Abierto, Catedral, Pasillo
    val rt60: Float, // Reverberation time in seconds
    val material: String, // Madera, Piedra, Metal, Aire Libre
    val isCustom: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "session_logs")
data class SessionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val presetId: String,
    val totalCoordinatesTracked: Int,
    val avgCpuUsage: Float,
    val energySavingMode: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// --- DAOs ---

@Dao
interface AcousticPresetDao {
    @Query("SELECT * FROM acoustic_presets ORDER BY name ASC")
    fun getAllPresets(): Flow<List<AcousticPreset>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: AcousticPreset)

    @Query("DELETE FROM acoustic_presets WHERE id = :id")
    suspend fun deletePresetById(id: String)
}

@Dao
interface SessionLogDao {
    @Query("SELECT * FROM session_logs ORDER BY timestamp DESC LIMIT 10")
    fun getRecentLogs(): Flow<List<SessionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SessionLog)

    @Query("DELETE FROM session_logs")
    suspend fun clearLogs()
}

// --- Database ---

@Database(entities = [AcousticPreset::class, SessionLog::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun presetDao(): AcousticPresetDao
    abstract fun logDao(): SessionLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "spatial_audio_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- Repository ---

class SpatialAudioRepository(private val db: AppDatabase) {
    val allPresets: Flow<List<AcousticPreset>> = db.presetDao().getAllPresets()
    val recentLogs: Flow<List<SessionLog>> = db.logDao().getRecentLogs()

    suspend fun insertPreset(preset: AcousticPreset) {
        db.presetDao().insertPreset(preset)
    }

    suspend fun deletePreset(id: String) {
        db.presetDao().deletePresetById(id)
    }

    suspend fun insertLog(log: SessionLog) {
        db.logDao().insertLog(log)
    }

    suspend fun clearLogs() {
        db.logDao().clearLogs()
    }

    suspend fun populateDefaults() {
        val defaults = listOf(
            AcousticPreset("catedral", "Catedral Gótica", "Catedral", 4.5f, "Piedra"),
            AcousticPreset("estudio", "Estudio de Grabación", "Cerrado", 0.4f, "Madera"),
            AcousticPreset("parque", "Parque Abierto", "Abierto", 0.05f, "Aire Libre"),
            AcousticPreset("pasillo", "Pasillo Metálico", "Pasillo", 1.8f, "Metal")
        )
        for (preset in defaults) {
            db.presetDao().insertPreset(preset)
        }
    }
}
