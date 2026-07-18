package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// --- Entities ---

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val bookId: Int,
    val amount: Double,
    val type: String, // "IN" or "OUT"
    val category: String,
    val paymentMethod: String, // "Cash", "Online", "Bank"
    val remarks: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "parties")
data class Party(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val phone: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "party_transactions",
    foreignKeys = [
        ForeignKey(
            entity = Party::class,
            parentColumns = ["id"],
            childColumns = ["partyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("partyId")]
)
data class PartyTransaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val partyId: Int,
    val amount: Double,
    val type: String, // "GAVE" (you gave, they owe you) or "GOT" (you got, you owe them)
    val remarks: String,
    val timestamp: Long = System.currentTimeMillis()
)

// --- DAO ---

@Dao
interface LedgerDao {
    // Books
    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    @Delete
    suspend fun deleteBook(book: Book)

    // Transactions
    @Query("SELECT * FROM transactions WHERE bookId = :bookId ORDER BY timestamp DESC")
    fun getTransactionsForBook(bookId: Int): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<Transaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction): Long

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)

    @Update
    suspend fun updateTransaction(transaction: Transaction)

    // Parties
    @Query("SELECT * FROM parties ORDER BY name ASC")
    fun getAllParties(): Flow<List<Party>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParty(party: Party): Long

    @Delete
    suspend fun deleteParty(party: Party)

    // Party Transactions
    @Query("SELECT * FROM party_transactions WHERE partyId = :partyId ORDER BY timestamp DESC")
    fun getPartyTransactions(partyId: Int): Flow<List<PartyTransaction>>

    @Query("SELECT * FROM party_transactions ORDER BY timestamp DESC")
    fun getAllPartyTransactions(): Flow<List<PartyTransaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPartyTransaction(partyTransaction: PartyTransaction): Long

    @Delete
    suspend fun deletePartyTransaction(partyTransaction: PartyTransaction)
}

// --- Database ---

@Database(
    entities = [Book::class, Transaction::class, Party::class, PartyTransaction::class],
    version = 1,
    exportSchema = false
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    companion object {
        @Volatile
        private var INSTANCE: LedgerDatabase? = null

        fun getDatabase(context: Context): LedgerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    "ledger_database"
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

class LedgerRepository(private val ledgerDao: LedgerDao) {
    val allBooks: Flow<List<Book>> = ledgerDao.getAllBooks()
    val allParties: Flow<List<Party>> = ledgerDao.getAllParties()
    val allTransactions: Flow<List<Transaction>> = ledgerDao.getAllTransactions()
    val allPartyTransactions: Flow<List<PartyTransaction>> = ledgerDao.getAllPartyTransactions()

    fun getTransactionsForBook(bookId: Int): Flow<List<Transaction>> {
        return ledgerDao.getTransactionsForBook(bookId)
    }

    fun getPartyTransactions(partyId: Int): Flow<List<PartyTransaction>> {
        return ledgerDao.getPartyTransactions(partyId)
    }

    suspend fun insertBook(book: Book): Long {
        return ledgerDao.insertBook(book)
    }

    suspend fun deleteBook(book: Book) {
        ledgerDao.deleteBook(book)
    }

    suspend fun insertTransaction(transaction: Transaction): Long {
        return ledgerDao.insertTransaction(transaction)
    }

    suspend fun updateTransaction(transaction: Transaction) {
        ledgerDao.updateTransaction(transaction)
    }

    suspend fun deleteTransaction(transaction: Transaction) {
        ledgerDao.deleteTransaction(transaction)
    }

    suspend fun insertParty(party: Party): Long {
        return ledgerDao.insertParty(party)
    }

    suspend fun deleteParty(party: Party) {
        ledgerDao.deleteParty(party)
    }

    suspend fun insertPartyTransaction(partyTransaction: PartyTransaction): Long {
        return ledgerDao.insertPartyTransaction(partyTransaction)
    }

    suspend fun deletePartyTransaction(partyTransaction: PartyTransaction) {
        ledgerDao.deletePartyTransaction(partyTransaction)
    }
}
