package de.tankradar.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [StationEntity::class, FuelPriceEntity::class, RefuelLogEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TankRadarDatabase : RoomDatabase() {

    abstract fun stationDao(): StationDao
    abstract fun priceDao(): PriceDao
    abstract fun refuelDao(): RefuelDao

    companion object {
        @Volatile
        private var instance: TankRadarDatabase? = null

        fun get(context: Context): TankRadarDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TankRadarDatabase::class.java,
                "tankradar.db",
            ).build().also { instance = it }
        }
    }
}
