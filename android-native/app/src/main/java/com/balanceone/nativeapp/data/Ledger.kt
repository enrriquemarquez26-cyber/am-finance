package com.balanceone.nativeapp.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transactions")
data class Movement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val cents: Long,
    val income: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM transactions ORDER BY createdAt DESC, id DESC")
    fun observe(): Flow<List<Movement>>
    @Insert suspend fun insert(movement: Movement)
    @Update suspend fun update(movement: Movement)
    @Delete suspend fun delete(movement: Movement)
}

@Database(entities = [Movement::class], version = 1, exportSchema = false)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledger(): LedgerDao
    companion object {
        @Volatile private var instance: LedgerDatabase? = null
        fun get(context: Context): LedgerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "balance-one-native.db")
                .build().also { instance = it }
        }
    }
}
