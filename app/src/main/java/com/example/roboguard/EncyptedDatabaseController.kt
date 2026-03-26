package com.example.roboguard


import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.*
import net.sqlcipher.database.SupportFactory

/**
 * Represents a registered client in the database.
 * 
 * @property clientId Unique identifier for the client (auto-generated).
 * @property sharedSecret The Base64 encoded shared secret used for HMAC authentication.
 * @property clientName A human-readable name for the client device.
 */
@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey(autoGenerate = true) val clientId: Long = 0,
    val sharedSecret: String,
    var clientName: String
)

/**
 * Data Access Object for managing [ClientEntity] operations.
 */
@Dao
interface ClientDao {
    /**
     * Inserts a new client or updates an existing one if the ID conflicts.
     * @return The ID of the inserted client.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClient(client: ClientEntity): Long

    /**
     * Retrieves a client by their unique ID.
     */
    @Query("SELECT * FROM clients WHERE clientId = :id LIMIT 1")
    suspend fun getClientById(id: Int): ClientEntity?

    /**
     * Removes a client from the registry.
     */
    @Query("DELETE FROM clients WHERE clientId = :id")
    suspend fun deleteClientById(id: Int)
}

/**
 * The Room database for storing client information.
 * This database is encrypted using SQLCipher.
 */
@Database(entities = [ClientEntity::class], version = 1)
abstract class ClientDatabase : RoomDatabase() {
    /** Returns the DAO for client operations. */
    abstract fun clientDao(): ClientDao

    companion object {
        @Volatile
        private var INSTANCE: ClientDatabase? = null

        /**
         * Gets the singleton instance of the [ClientDatabase].
         * Initializes the database with SQLCipher encryption using the provided passphrase.
         *
         * @param context Android context.
         * @param passphrase The key used to encrypt the database file.
         * @return The initialized ClientDatabase instance.
         */
        fun getDatabase(context: Context, passphrase: ByteArray): ClientDatabase {
            return INSTANCE ?: synchronized(this) {
                // Load SQLCipher native library
                System.loadLibrary("sqlcipher")
                val factory = SupportFactory(passphrase)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ClientDatabase::class.java,
                    "client_db.db"
                ).openHelperFactory(factory)
                    .fallbackToDestructiveMigration() // Allows schema changes during development
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}