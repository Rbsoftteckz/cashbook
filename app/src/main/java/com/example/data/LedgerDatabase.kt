package com.example.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

// --- Entities ---

@Entity(tableName = "businesses")
data class Business(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val userEmail: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val businessId: Int = 1, // Links books to specific business
    val name: String,
    val phone: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
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
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val receiptUri: String? = null,
    val addedByName: String = "",
    val addedByEmail: String = ""
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
    val type: String, // "GAVE" or "GOT"
    val remarks: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)

@Entity(tableName = "team_members")
data class TeamMember(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val businessId: Int = 1,
    val name: String,
    val email: String,
    val phone: String,
    val role: String, // "Boss", "Admin", "Partner", "Data Entry", "Viewer"
    val createdAt: Long = System.currentTimeMillis(),
    val allowedBookNames: String = "", // Empty = All Books; JSON array = Selected Books Only
    val hideBalance: Boolean = false, // Hide Total / Net / Split balances for Data Entry & Viewer
    val lockPastEntries: Boolean = false // Prevent Data Entry / Partner from editing or deleting past days' entries
)

@Entity(tableName = "audit_logs")
data class AuditLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val businessId: Int = 0,
    val businessName: String = "",
    val bookId: Int = 0,
    val bookName: String = "",
    val transactionId: Int = 0,
    val action: String, // "EDITED", "DELETED", "RESTORED"
    val summary: String,
    val oldDetails: String = "",
    val newDetails: String = "",
    val actorName: String = "",
    val actorEmail: String = "",
    val actorRole: String = "",
    val userEmail: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "trash_items")
data class TrashItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val itemType: String, // "ENTRY", "BOOK", "BUSINESS", "PARTY", "PARTY_ENTRY"
    val title: String,
    val subtitle: String = "",
    val businessName: String = "",
    val bookName: String = "",
    val userEmail: String = "",
    val amount: Double = 0.0,
    val entryType: String = "", // "IN", "OUT", "GAVE", "GOT", or ""
    val childCount: Int = 0,
    val deletedByName: String = "",
    val deletedAt: Long = System.currentTimeMillis(),
    val payloadJson: String
)

fun isRoleFullBusinessAccess(role: String?): Boolean {
    val clean = (role ?: "").trim()
    return clean.equals("Super Admin", ignoreCase = true) ||
        clean.equals("Boss", ignoreCase = true) ||
        clean.equals("Owner", ignoreCase = true) ||
        clean.equals("Admin", ignoreCase = true)
}

fun canRoleHideBalance(role: String?): Boolean {
    val clean = (role ?: "").trim()
    return clean.equals("Data Entry", ignoreCase = true) ||
        clean.equals("Viewer", ignoreCase = true) ||
        clean.equals("Read Only", ignoreCase = true) ||
        clean.equals("Read-Only", ignoreCase = true)
}

fun canRoleLockPastEntries(role: String?): Boolean {
    val clean = (role ?: "").trim()
    return clean.equals("Data Entry", ignoreCase = true) ||
        clean.equals("Partner", ignoreCase = true)
}

fun defaultLockPastEntriesForRole(role: String?): Boolean {
    return (role ?: "").trim().equals("Data Entry", ignoreCase = true)
}

fun startOfTodayMillis(nowMillis: Long = System.currentTimeMillis()): Long {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

fun isPastDayTimestamp(timestamp: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
    return timestamp < startOfTodayMillis(nowMillis)
}

fun canModifyTransactionByRoleAndLock(
    role: String?,
    action: String,
    txTimestamp: Long,
    lockPastEntries: Boolean
): Boolean {
    return when ((role ?: "").trim().lowercase()) {
        "super admin", "boss", "owner", "admin" -> true
        "viewer", "read only", "read-only" -> false
        "partner" -> when (action) {
            "edit_transaction" -> !lockPastEntries || !isPastDayTimestamp(txTimestamp)
            else -> false
        }
        "data entry" -> when (action) {
            "edit_transaction", "delete_transaction" -> !lockPastEntries || !isPastDayTimestamp(txTimestamp)
            else -> false
        }
        else -> false
    }
}

fun parseAllowedBooks(raw: String?): List<String> {
    val clean = (raw ?: "").trim()
    if (clean.isBlank()) return emptyList()
    return try {
        if (clean.startsWith("[")) {
            val arr = JSONArray(clean)
            (0 until arr.length())
                .mapNotNull { arr.optString(it, "").trim().takeIf { s -> s.isNotBlank() } }
                .distinctBy { it.lowercase() }
        } else {
            clean.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun encodeAllowedBooks(bookNames: List<String>): String {
    val cleaned = bookNames.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    if (cleaned.isEmpty()) return ""
    val arr = JSONArray()
    cleaned.forEach { arr.put(it) }
    return arr.toString()
}

fun isBookAllowedForMember(bookName: String, allowedBookNamesRaw: String?, role: String? = null): Boolean {
    if (isRoleFullBusinessAccess(role)) return true
    val allowed = parseAllowedBooks(allowedBookNamesRaw)
    if (allowed.isEmpty()) return true
    val target = bookName.trim()
    return allowed.any { it.equals(target, ignoreCase = true) }
}

fun formatAllowedBooksSummary(allowedBookNamesRaw: String?, role: String?): String {
    if (isRoleFullBusinessAccess(role)) return "All Books (Full Business)"
    val allowed = parseAllowedBooks(allowedBookNamesRaw)
    return when {
        allowed.isEmpty() -> "All Books"
        allowed.size == 1 -> "1 Book: ${allowed.first()}"
        allowed.size <= 3 -> "${allowed.size} Books: ${allowed.joinToString(", ")}"
        else -> "${allowed.size} Books: ${allowed.take(2).joinToString(", ")} +${allowed.size - 2} more"
    }
}

// --- DAO ---

@Dao
interface LedgerDao {
    @Query("SELECT * FROM businesses")
    suspend fun getAllBusinessesList(): List<Business>

    @Query("SELECT * FROM books")
    suspend fun getAllBooksList(): List<Book>

    @Query("SELECT * FROM transactions")
    suspend fun getAllTransactionsList(): List<Transaction>

    @Query("SELECT * FROM parties")
    suspend fun getAllPartiesList(): List<Party>

    @Query("SELECT * FROM party_transactions")
    suspend fun getAllPartyTransactionsList(): List<PartyTransaction>

    @Query("SELECT * FROM team_members")
    suspend fun getAllTeamMembersList(): List<TeamMember>

    // Businesses
    @Query("SELECT * FROM businesses ORDER BY createdAt DESC")
    fun getAllBusinesses(): Flow<List<Business>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBusiness(business: Business): Long

    @Update
    suspend fun updateBusiness(business: Business)

    @Delete
    suspend fun deleteBusiness(business: Business)

    @Query("DELETE FROM books WHERE businessId = :businessId")
    suspend fun deleteBooksForBusiness(businessId: Int)

    @Query("DELETE FROM team_members WHERE businessId = :businessId")
    suspend fun deleteTeamMembersForBusiness(businessId: Int)

    // Books
    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE businessId = :businessId ORDER BY createdAt DESC")
    fun getBooksForBusiness(businessId: Int): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    @Update
    suspend fun updateBook(book: Book)

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

    @Query("UPDATE transactions SET isSynced = 1")
    suspend fun markAllTransactionsSynced()

    @Query("UPDATE party_transactions SET isSynced = 1")
    suspend fun markAllPartyTransactionsSynced()

    @Query("UPDATE businesses SET isSynced = 1")
    suspend fun markAllBusinessesSynced()

    @Query("UPDATE books SET isSynced = 1")
    suspend fun markAllBooksSynced()

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

    // Team Members
    @Query("SELECT * FROM team_members WHERE businessId = :businessId ORDER BY createdAt DESC")
    fun getTeamMembersForBusiness(businessId: Int): Flow<List<TeamMember>>

    @Query("SELECT * FROM team_members ORDER BY createdAt DESC")
    fun getAllTeamMembers(): Flow<List<TeamMember>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTeamMember(teamMember: TeamMember): Long

    @Update
    suspend fun updateTeamMember(teamMember: TeamMember)

    @Delete
    suspend fun deleteTeamMember(teamMember: TeamMember)

    // Trash Items
    @Query("SELECT * FROM trash_items ORDER BY deletedAt DESC")
    fun getAllTrashItems(): Flow<List<TrashItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrashItem(trashItem: TrashItem): Long

    @Delete
    suspend fun deleteTrashItem(trashItem: TrashItem)

    @Query("DELETE FROM trash_items WHERE LOWER(userEmail) = LOWER(:userEmail) OR userEmail = ''")
    suspend fun clearTrashForUser(userEmail: String)

    @Query("DELETE FROM trash_items")
    suspend fun clearAllTrash()

    // Audit Logs
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    fun getAllAuditLogs(): Flow<List<AuditLog>>

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    suspend fun getAllAuditLogsList(): List<AuditLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(auditLog: AuditLog): Long

    @Delete
    suspend fun deleteAuditLog(auditLog: AuditLog)

    @Query("DELETE FROM audit_logs WHERE LOWER(userEmail) = LOWER(:userEmail) OR userEmail = ''")
    suspend fun clearAuditLogsForUser(userEmail: String)

    @Query("DELETE FROM audit_logs")
    suspend fun clearAllAuditLogs()
}

// --- Database ---

@Database(
    entities = [Business::class, Book::class, Transaction::class, Party::class, PartyTransaction::class, TeamMember::class, TrashItem::class, AuditLog::class],
    version = 10,
    exportSchema = false
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    companion object {
        @Volatile
        private var INSTANCE: LedgerDatabase? = null

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE team_members ADD COLUMN allowedBookNames TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN addedByName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN addedByEmail TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE team_members ADD COLUMN hideBalance INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trash_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `itemType` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `subtitle` TEXT NOT NULL,
                        `businessName` TEXT NOT NULL,
                        `bookName` TEXT NOT NULL,
                        `userEmail` TEXT NOT NULL,
                        `amount` REAL NOT NULL,
                        `entryType` TEXT NOT NULL,
                        `childCount` INTEGER NOT NULL,
                        `deletedByName` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        `payloadJson` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE team_members ADD COLUMN lockPastEntries INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE team_members SET lockPastEntries = 1 WHERE LOWER(TRIM(role)) = 'data entry'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audit_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `businessId` INTEGER NOT NULL,
                        `businessName` TEXT NOT NULL,
                        `bookId` INTEGER NOT NULL,
                        `bookName` TEXT NOT NULL,
                        `transactionId` INTEGER NOT NULL,
                        `action` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `oldDetails` TEXT NOT NULL,
                        `newDetails` TEXT NOT NULL,
                        `actorName` TEXT NOT NULL,
                        `actorEmail` TEXT NOT NULL,
                        `actorRole` TEXT NOT NULL,
                        `userEmail` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): LedgerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    "ledger_database"
                )
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
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
    val allBusinesses: Flow<List<Business>> = ledgerDao.getAllBusinesses()
    val allBooks: Flow<List<Book>> = ledgerDao.getAllBooks()
    val allParties: Flow<List<Party>> = ledgerDao.getAllParties()
    val allTransactions: Flow<List<Transaction>> = ledgerDao.getAllTransactions()
    val allPartyTransactions: Flow<List<PartyTransaction>> = ledgerDao.getAllPartyTransactions()
    val allTeamMembers: Flow<List<TeamMember>> = ledgerDao.getAllTeamMembers()
    val allTrashItems: Flow<List<TrashItem>> = ledgerDao.getAllTrashItems()
    val allAuditLogs: Flow<List<AuditLog>> = ledgerDao.getAllAuditLogs()

    fun getBooksForBusiness(businessId: Int): Flow<List<Book>> {
        return ledgerDao.getBooksForBusiness(businessId)
    }

    fun getTransactionsForBook(bookId: Int): Flow<List<Transaction>> {
        return ledgerDao.getTransactionsForBook(bookId)
    }

    fun getPartyTransactions(partyId: Int): Flow<List<PartyTransaction>> {
        return ledgerDao.getPartyTransactions(partyId)
    }

    fun getTeamMembersForBusiness(businessId: Int): Flow<List<TeamMember>> {
        return ledgerDao.getTeamMembersForBusiness(businessId)
    }

    suspend fun insertBusiness(business: Business): Long {
        return ledgerDao.insertBusiness(business)
    }

    suspend fun updateBusiness(business: Business) {
        ledgerDao.updateBusiness(business)
    }

    suspend fun deleteBusiness(business: Business) {
        ledgerDao.deleteBooksForBusiness(business.id)
        ledgerDao.deleteTeamMembersForBusiness(business.id)
        ledgerDao.deleteBusiness(business)
    }

    suspend fun insertBook(book: Book): Long {
        return ledgerDao.insertBook(book)
    }

    suspend fun updateBook(book: Book) {
        ledgerDao.updateBook(book)
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

    suspend fun markAllTransactionsSynced() {
        ledgerDao.markAllTransactionsSynced()
        ledgerDao.markAllPartyTransactionsSynced()
        ledgerDao.markAllBusinessesSynced()
        ledgerDao.markAllBooksSynced()
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

    suspend fun insertTeamMember(teamMember: TeamMember): Long {
        return ledgerDao.insertTeamMember(teamMember)
    }

    suspend fun updateTeamMember(teamMember: TeamMember) {
        ledgerDao.updateTeamMember(teamMember)
    }

    suspend fun deleteTeamMember(teamMember: TeamMember) {
        ledgerDao.deleteTeamMember(teamMember)
    }

    suspend fun insertTrashItem(trashItem: TrashItem): Long {
        return ledgerDao.insertTrashItem(trashItem)
    }

    suspend fun deleteTrashItem(trashItem: TrashItem) {
        ledgerDao.deleteTrashItem(trashItem)
    }

    suspend fun clearTrashForUser(userEmail: String) {
        ledgerDao.clearTrashForUser(userEmail)
    }

    suspend fun clearAllTrash() {
        ledgerDao.clearAllTrash()
    }

    suspend fun insertAuditLog(auditLog: AuditLog): Long {
        return ledgerDao.insertAuditLog(auditLog)
    }

    suspend fun clearAuditLogsForUser(userEmail: String) {
        ledgerDao.clearAuditLogsForUser(userEmail)
    }

    suspend fun clearAllAuditLogs() {
        ledgerDao.clearAllAuditLogs()
    }
}
