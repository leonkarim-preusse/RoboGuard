package com.example.roboguard


import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.*
import net.sqlcipher.database.SupportFactory

@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey(autoGenerate = true) val clientId: Int = 0,
    val sharedSecret: String,
    var clientName: String
)

@Dao
interface ClientDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClient(client: ClientEntity): Long


    @Query("SELECT * FROM clients WHERE clientId = :id LIMIT 1")
    suspend fun getClientById(id: Int): ClientEntity?

    @Query("DELETE FROM clients WHERE clientId = :id")
    suspend fun deleteClientById(id: Int)
}


@Database(entities = [ClientEntity::class], version = 1)
abstract class ClientDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao

    companion object {
        @Volatile
        private var INSTANCE: ClientDatabase? = null

        fun getDatabase(context: Context, passphrase: ByteArray): ClientDatabase {
            return INSTANCE ?: synchronized(this) {
                System.loadLibrary("sqlcipher")
                val factory = SupportFactory(passphrase)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ClientDatabase::class.java,
                    "client_db.db"
                ).openHelperFactory(factory)
                    .fallbackToDestructiveMigration() // Optional: für Entwicklung
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}