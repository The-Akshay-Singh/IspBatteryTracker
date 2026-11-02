package com.isp.batterytracker

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Entity
data class Battery(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serial: String, val model: String, val site: String, val area: String,
    val capacityAh: Double, val loadAmps: Double,
    val installEpochDay: Long, val lifeMonths: Int
)

@Entity(
    foreignKeys = [ForeignKey(entity = Battery::class, parentColumns = ["id"], childColumns = ["batteryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("batteryId")]
)
data class HealthCheck(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batteryId: Long, val epochDay: Long, val healthPct: Int, val voltage: Double
)

@Dao
interface IspDao {
    @Query("SELECT * FROM Battery ORDER BY installEpochDay DESC") fun batteries(): Flow<List<Battery>>
    @Query("SELECT * FROM HealthCheck ORDER BY epochDay DESC") fun checks(): Flow<List<HealthCheck>>
    @Insert suspend fun add(b: Battery)
    @Insert suspend fun addCheck(c: HealthCheck)
    @Delete suspend fun delete(b: Battery)
}

@Database(entities = [Battery::class, HealthCheck::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): IspDao
    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "isp.db").build().also { inst = it }
        }
    }
}

data class Stat(val b: Battery, val health: Int, val backupHrs: Double, val dueDay: Long, val ageMonths: Int, val measured: Boolean)

/** Health = latest manual check, else linear estimate (100% new -> 80% at rated life). */
fun stat(b: Battery, checks: List<HealthCheck>): Stat {
    val today = LocalDate.now().toEpochDay()
    val ageM = ((today - b.installEpochDay) / 30.4).toInt()
    val last = checks.filter { it.batteryId == b.id }.maxByOrNull { it.epochDay }
    val h = last?.healthPct ?: (100 - 20.0 * ageM / b.lifeMonths).toInt().coerceIn(0, 100)
    val hrs = b.capacityAh * h / 100 * 0.8 / b.loadAmps   // 80% usable depth of discharge
    return Stat(b, h, hrs, b.installEpochDay + (b.lifeMonths * 30.4).toLong(), ageM, last != null)
}
