package com.example.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.notifications.WasooliNotificationHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class Screen {
    DASHBOARD,
    UDHAR,
    REPORTS,
    AI_ASSISTANT,
    TEAM_MANAGEMENT,
    SYNC_CENTER,
    WHATS_NEW,
    HELP_DOCS,
    CONTACT_US,
    SETTINGS,
    MANAGE_WORKSPACE,
    PROFILE,
    TRASH,
    AUDIT_HISTORY
}

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val database = LedgerDatabase.getDatabase(application)
    private val repository = LedgerRepository(database.ledgerDao())
    private val geminiService = GeminiService()
    val syncManager = GoogleDriveSyncManager(application)

    // Navigation and screen state
    private val _currentScreen = MutableStateFlow(Screen.DASHBOARD)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // cashbook.in-style 3-level Home hierarchy:
    // Level 1: isHomeBusinessesListVisible = true -> Show All Businesses so user can choose
    // Level 2: isHomeBusinessesListVisible = false, isHomeBooksHubVisible = true -> Show Selected Business's Books
    // Level 3: isHomeBusinessesListVisible = false, isHomeBooksHubVisible = false -> Open Book Ledger
    private val _isHomeBusinessesListVisible = MutableStateFlow(true)
    val isHomeBusinessesListVisible: StateFlow<Boolean> = _isHomeBusinessesListVisible.asStateFlow()

    private val _isHomeBooksHubVisible = MutableStateFlow(true)
    val isHomeBooksHubVisible: StateFlow<Boolean> = _isHomeBooksHubVisible.asStateFlow()

    // Currently inspected business ID in Manage Workspace (so TopAppBar always reflects the related business)
    private val _manageSelectedBusinessId = MutableStateFlow<Int?>(null)
    val manageSelectedBusinessId: StateFlow<Int?> = _manageSelectedBusinessId.asStateFlow()

    fun setManageSelectedBusinessId(id: Int?) {
        _manageSelectedBusinessId.value = id
    }

    // 3-level Reports hierarchy (always separate Businesses -> separate Books -> Single Book Report):
    // Level 1: reportSelectedBusinessId == null -> All Businesses list
    // Level 2: reportSelectedBusinessId != null, reportSelectedBookId == null -> Selected Business & its Books
    // Level 3: reportSelectedBookId != null -> Single Book Report
    private val _reportSelectedBusinessId = MutableStateFlow<Int?>(null)
    val reportSelectedBusinessId: StateFlow<Int?> = _reportSelectedBusinessId.asStateFlow()

    private val _reportSelectedBookId = MutableStateFlow<Int?>(null)
    val reportSelectedBookId: StateFlow<Int?> = _reportSelectedBookId.asStateFlow()

    // Sync state
    private val _syncStatus = MutableStateFlow("Offline Mode - Ready to Sync")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    fun checkIsOnline(): Boolean {
        return try {
            val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                _isOnline.value = false
                return false
            }
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork == null) {
                _isOnline.value = false
                return false
            }
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (capabilities == null) {
                _isOnline.value = false
                return false
            }
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            _isOnline.value = hasInternet
            hasInternet
        } catch (e: Exception) {
            _isOnline.value = false
            false
        }
    }

    fun verifyRealCloudConnection(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!checkIsOnline()) {
            _syncStatus.value = "Offline Mode - No Network Connection"
            onResult(false, "Device is Offline: No Internet Connection")
            return
        }

        viewModelScope.launch {
            _isSyncing.value = true
            _syncStatus.value = "Pinging Cloud Server..."
            val (online, msg) = syncManager.pingCloudConnection()
            _isOnline.value = online
            _syncStatus.value = msg
            _isSyncing.value = false
            onResult(online, msg)
        }
    }

    // --- Super Admin & User Auth State & Actions ---
    val isSuperAdmin = MutableStateFlow(syncManager.isSuperAdminLoggedIn())
    val isUserSignedIn = MutableStateFlow(syncManager.isUserSignedIn())
    private val _currentUserEmail = MutableStateFlow(syncManager.getEmail().trim().lowercase())
    val currentUserEmail: StateFlow<String> = _currentUserEmail.asStateFlow()

    @Volatile
    private var pendingCloudSync = false

    private fun filterBusinessesForUser(
        list: List<Business>,
        activeEmail: String,
        superAdmin: Boolean,
        teamMembers: List<TeamMember>
    ): List<Business> {
        val cleanEmail = activeEmail.trim().lowercase()
        val isOfflineUser = cleanEmail == "offline@cashbook.local" || cleanEmail == "offline_user@cashbook.local" || cleanEmail.endsWith(".local")
        return when {
            isOfflineUser -> list.filter { biz ->
                val owner = biz.userEmail.trim().lowercase()
                (owner == cleanEmail || owner.isBlank()) && !syncManager.isBusinessDeleted(biz.name, biz.createdAt)
            }
            superAdmin -> list.filter { biz ->
                val owner = biz.userEmail.trim().lowercase()
                val isOfflineBiz = owner == "offline@cashbook.local" || owner == "offline_user@cashbook.local" || owner.endsWith(".local") ||
                    biz.name.equals("Offline User's Business", ignoreCase = true) || biz.name.equals("Offline Business", ignoreCase = true)
                !isOfflineBiz && !syncManager.isBusinessDeleted(biz.name, biz.createdAt)
            }
            cleanEmail.isBlank() -> emptyList()
            else -> list.filter { biz ->
                val owner = biz.userEmail.trim().lowercase()
                val isOfflineBiz = owner == "offline@cashbook.local" || owner == "offline_user@cashbook.local" || owner.endsWith(".local") ||
                    biz.name.equals("Offline User's Business", ignoreCase = true) || biz.name.equals("Offline Business", ignoreCase = true)
                if (isOfflineBiz || syncManager.isBusinessDeleted(biz.name, biz.createdAt)) {
                    false
                } else {
                    owner == cleanEmail ||
                        owner.isBlank() ||
                        teamMembers.any { tm -> tm.businessId == biz.id && tm.email.trim().lowercase() == cleanEmail }
                }
            }
        }
    }

    // Simulated Active Role (owner preview/override; invited collaborators strictly use their assigned role)
    private val _simulatedRole = MutableStateFlow("Boss") // "Boss", "Admin", "Partner", "Data Entry"

    val allTeamMembers: StateFlow<List<TeamMember>> = repository.allTeamMembers.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Multi-Business states (reactive to room changes AND account switches/team membership)
    val businesses: StateFlow<List<Business>> = combine(
        repository.allBusinesses,
        _currentUserEmail,
        isSuperAdmin,
        repository.allTeamMembers
    ) { list, activeEmail, superAdmin, teamMembers ->
        filterBusinessesForUser(list, activeEmail, superAdmin, teamMembers)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _activeBusiness = MutableStateFlow<Business?>(null)
    val activeBusiness: StateFlow<Business?> = _activeBusiness.asStateFlow()

    private fun filterBooksForUser(
        booksList: List<Book>,
        visibleBusinesses: List<Business>,
        activeEmail: String,
        superAdmin: Boolean,
        teamMembers: List<TeamMember>
    ): List<Book> {
        val cleanEmail = activeEmail.trim().lowercase()
        val isOfflineUser = cleanEmail == "offline@cashbook.local" || cleanEmail == "offline_user@cashbook.local" || cleanEmail.endsWith(".local")
        val bizById = visibleBusinesses.associateBy { it.id }
        if (isOfflineUser || superAdmin || cleanEmail.isBlank()) {
            return booksList.filter { it.businessId in bizById }
        }
        val sharedRefs = syncManager.getSharedWorkspaces(cleanEmail)
        return booksList.filter { book ->
            val biz = bizById[book.businessId] ?: return@filter false
            val owner = biz.userEmail.trim().lowercase()
            if (owner == cleanEmail || owner.isBlank()) {
                true
            } else {
                val tm = teamMembers.firstOrNull {
                    it.businessId == biz.id && it.email.trim().lowercase() == cleanEmail
                }
                val ref = sharedRefs.firstOrNull {
                    it.businessName.equals(biz.name, ignoreCase = true) && it.ownerEmail.equals(owner, ignoreCase = true)
                }
                val role = tm?.role ?: ref?.role ?: "Data Entry"
                val allowedRaw = tm?.allowedBookNames ?: ref?.allowedBookNames ?: ""
                isBookAllowedForMember(book.name, allowedRaw, role)
            }
        }
    }

    // Scoped books belonging only to the signed-in user's visible businesses & shared-book access
    val allBooks: StateFlow<List<Book>> = combine(
        repository.allBooks,
        businesses,
        _currentUserEmail,
        isSuperAdmin,
        repository.allTeamMembers
    ) { booksList, visibleBusinesses, activeEmail, superAdmin, teamMembers ->
        filterBooksForUser(booksList, visibleBusinesses, activeEmail, superAdmin, teamMembers)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Books automatically retrieved and access-filtered for the active business
    val books: StateFlow<List<Book>> = combine(
        _activeBusiness.flatMapLatest { biz ->
            if (biz != null) repository.getBooksForBusiness(biz.id) else flowOf(emptyList())
        },
        _activeBusiness,
        _currentUserEmail,
        isSuperAdmin,
        repository.allTeamMembers
    ) { bizBooks, biz, activeEmail, superAdmin, teamMembers ->
        if (biz == null) {
            emptyList()
        } else {
            filterBooksForUser(bizBooks, listOf(biz), activeEmail, superAdmin, teamMembers)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _activeBook = MutableStateFlow<Book?>(null)
    val activeBook: StateFlow<Book?> = _activeBook.asStateFlow()

    // Search & Filters state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _selectedPaymentMethod = MutableStateFlow("All")
    val selectedPaymentMethod: StateFlow<String> = _selectedPaymentMethod.asStateFlow()

    // Multi-Select Batch Operations State
    private val _selectedTransactionIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedTransactionIds: StateFlow<Set<Int>> = _selectedTransactionIds.asStateFlow()

    // Scoped transactions belonging only to the signed-in user's visible books
    val allTransactions: StateFlow<List<Transaction>> = combine(
        repository.allTransactions,
        allBooks
    ) { txList, visibleBooks ->
        val visibleBookIds = visibleBooks.map { it.id }.toSet()
        txList.filter { it.bookId in visibleBookIds }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Scoped Trash Items for the signed-in account
    val trashItems: StateFlow<List<TrashItem>> = combine(
        repository.allTrashItems,
        _currentUserEmail,
        isSuperAdmin
    ) { items, activeEmail, superAdmin ->
        val cleanEmail = activeEmail.trim().lowercase()
        items.filter { item ->
            superAdmin ||
                cleanEmail.isBlank() ||
                item.userEmail.isBlank() ||
                item.userEmail.trim().equals(cleanEmail, ignoreCase = true)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Scoped Audit History logs for visible businesses / signed-in account
    val auditLogs: StateFlow<List<AuditLog>> = combine(
        repository.allAuditLogs,
        businesses,
        _currentUserEmail,
        isSuperAdmin
    ) { logs, visibleBusinesses, activeEmail, superAdmin ->
        val cleanEmail = activeEmail.trim().lowercase()
        val bizIds = visibleBusinesses.map { it.id }.toSet()
        val bizNames = visibleBusinesses.map { it.name.trim().lowercase() }.toSet()
        logs.filter { log ->
            superAdmin ||
                log.businessId in bizIds ||
                log.businessName.trim().lowercase() in bizNames ||
                (cleanEmail.isBlank() && log.userEmail.isBlank()) ||
                (cleanEmail.isNotBlank() && log.userEmail.trim().equals(cleanEmail, ignoreCase = true))
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Team members for the active business
    val activeBusinessTeamMembers: StateFlow<List<TeamMember>> = _activeBusiness
        .flatMapLatest { biz ->
            if (biz != null) {
                repository.getTeamMembersForBusiness(biz.id)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val isActiveBusinessOwner: StateFlow<Boolean> = combine(
        _activeBusiness,
        _currentUserEmail,
        isSuperAdmin,
        activeBusinessTeamMembers
    ) { biz, email, superAdmin, teamMembers ->
        val cleanEmail = email.trim().lowercase()
        if (superAdmin || biz == null || cleanEmail.isBlank() || cleanEmail.endsWith(".local")) {
            true
        } else {
            val ownerEmail = biz.userEmail.trim().lowercase()
            ownerEmail.isBlank() || ownerEmail == cleanEmail ||
                teamMembers.any {
                    it.email.trim().lowercase() == cleanEmail &&
                        (it.role.equals("Boss", true) || it.role.equals("Owner", true))
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val actualUserRoleForActiveBusiness: StateFlow<String> = combine(
        _activeBusiness,
        _currentUserEmail,
        isSuperAdmin,
        activeBusinessTeamMembers
    ) { biz, email, superAdmin, teamMembers ->
        val cleanEmail = email.trim().lowercase()
        if (superAdmin || biz == null || cleanEmail.isBlank() || cleanEmail.endsWith(".local")) {
            "Boss"
        } else {
            val ownerEmail = biz.userEmail.trim().lowercase()
            if (ownerEmail.isBlank() || ownerEmail == cleanEmail) {
                "Boss"
            } else {
                teamMembers.firstOrNull { it.email.trim().lowercase() == cleanEmail }?.role ?: "Data Entry"
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Boss")

    val simulatedRole: StateFlow<String> = combine(
        _simulatedRole,
        actualUserRoleForActiveBusiness,
        isActiveBusinessOwner
    ) { simRole, actualRole, isOwner ->
        if (isOwner) simRole else actualRole
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Boss")

    val activeBusinessAllowedBookNames: StateFlow<String> = combine(
        _activeBusiness,
        _currentUserEmail,
        isSuperAdmin,
        activeBusinessTeamMembers,
        actualUserRoleForActiveBusiness
    ) { biz, email, superAdmin, teamMembers, role ->
        val cleanEmail = email.trim().lowercase()
        if (superAdmin || biz == null || cleanEmail.isBlank() || cleanEmail.endsWith(".local")) {
            ""
        } else {
            val ownerEmail = biz.userEmail.trim().lowercase()
            if (ownerEmail.isBlank() || ownerEmail == cleanEmail || isRoleFullBusinessAccess(role)) {
                ""
            } else {
                val tm = teamMembers.firstOrNull { it.email.trim().lowercase() == cleanEmail }
                val ref = syncManager.getSharedWorkspaces(cleanEmail).firstOrNull {
                    it.businessName.equals(biz.name, ignoreCase = true) && it.ownerEmail.equals(ownerEmail, ignoreCase = true)
                }
                tm?.allowedBookNames ?: ref?.allowedBookNames ?: ""
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val isActiveBusinessBookRestricted: StateFlow<Boolean> = activeBusinessAllowedBookNames
        .map { parseAllowedBooks(it).isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun isBalanceHiddenForBusiness(
        biz: Business?,
        teamMembers: List<TeamMember> = allTeamMembers.value
    ): Boolean {
        if (biz == null || isSuperAdmin.value) return false
        val cleanEmail = _currentUserEmail.value.trim().lowercase()
        if (cleanEmail.isBlank() || cleanEmail.endsWith(".local")) return false
        val ownerEmail = biz.userEmail.trim().lowercase()
        if (ownerEmail.isBlank() || ownerEmail == cleanEmail) return false
        val tm = teamMembers.firstOrNull {
            it.businessId == biz.id && it.email.trim().lowercase() == cleanEmail
        }
        val ref = syncManager.getSharedWorkspaces(cleanEmail).firstOrNull {
            it.businessName.equals(biz.name, ignoreCase = true) && it.ownerEmail.equals(ownerEmail, ignoreCase = true)
        }
        val role = tm?.role ?: ref?.role ?: "Data Entry"
        if (!canRoleHideBalance(role)) return false
        return tm?.hideBalance ?: ref?.hideBalance ?: false
    }

    val hideTotalBalanceForActiveBusiness: StateFlow<Boolean> = combine(
        _activeBusiness,
        _currentUserEmail,
        isSuperAdmin,
        activeBusinessTeamMembers
    ) { biz, _, _, teamMembers ->
        isBalanceHiddenForBusiness(biz, teamMembers)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun isLockPastEntriesForBusiness(
        biz: Business?,
        teamMembers: List<TeamMember> = allTeamMembers.value
    ): Boolean {
        if (biz == null || isSuperAdmin.value) return false
        val cleanEmail = _currentUserEmail.value.trim().lowercase()
        if (cleanEmail.isBlank() || cleanEmail.endsWith(".local")) return false
        val ownerEmail = biz.userEmail.trim().lowercase()
        if (ownerEmail.isBlank() || ownerEmail == cleanEmail) return false
        val tm = teamMembers.firstOrNull {
            it.businessId == biz.id && it.email.trim().lowercase() == cleanEmail
        }
        val ref = syncManager.getSharedWorkspaces(cleanEmail).firstOrNull {
            it.businessName.equals(biz.name, ignoreCase = true) && it.ownerEmail.equals(ownerEmail, ignoreCase = true)
        }
        val role = tm?.role ?: ref?.role ?: "Data Entry"
        if (!canRoleLockPastEntries(role)) return false
        return tm?.lockPastEntries ?: ref?.lockPastEntries ?: defaultLockPastEntriesForRole(role)
    }

    val lockPastEntriesForActiveBusiness: StateFlow<Boolean> = combine(
        _activeBusiness,
        _currentUserEmail,
        isSuperAdmin,
        activeBusinessTeamMembers,
        _simulatedRole,
        isActiveBusinessOwner
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val biz = values[0] as Business?
        @Suppress("UNCHECKED_CAST")
        val teamMembers = values[3] as List<TeamMember>
        val simRole = values[4] as String
        val isOwner = values[5] as Boolean
        if (isOwner) {
            if (!isRoleFullBusinessAccess(simRole) && canRoleLockPastEntries(simRole)) {
                teamMembers.firstOrNull { it.role.equals(simRole, ignoreCase = true) }?.lockPastEntries
                    ?: defaultLockPastEntriesForRole(simRole)
            } else {
                false
            }
        } else {
            isLockPastEntriesForBusiness(biz, teamMembers)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun canModifyTransactionInActiveBusiness(tx: Transaction, action: String): Boolean {
        return canModifyTransactionByRoleAndLock(
            role = simulatedRole.value,
            action = action,
            txTimestamp = tx.timestamp,
            lockPastEntries = lockPastEntriesForActiveBusiness.value
        )
    }

    private val _pendingEmailInvites = MutableStateFlow<List<TeamInviteRecord>>(emptyList())
    val pendingEmailInvites: StateFlow<List<TeamInviteRecord>> = _pendingEmailInvites.asStateFlow()

    // Automatically retrieve transactions for the active book
    val activeBookTransactions: StateFlow<List<Transaction>> = _activeBook
        .flatMapLatest { book ->
            if (book != null) {
                repository.getTransactionsForBook(book.id)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Filtered transaction list for display
    val filteredTransactions: StateFlow<List<Transaction>> = combine(
        activeBookTransactions,
        _searchQuery,
        _selectedCategory,
        _selectedPaymentMethod
    ) { list, query, category, payment ->
        list.filter { tx ->
            val matchesQuery = tx.remarks.contains(query, ignoreCase = true) || 
                               tx.category.contains(query, ignoreCase = true)
            val matchesCategory = category == "All" || tx.category.equals(category, ignoreCase = true)
            val matchesPayment = payment == "All" || tx.paymentMethod.equals(payment, ignoreCase = true)
            matchesQuery && matchesCategory && matchesPayment
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Parties/Udhar state
    val parties: StateFlow<List<Party>> = repository.allParties.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _activeParty = MutableStateFlow<Party?>(null)
    val activeParty: StateFlow<Party?> = _activeParty.asStateFlow()

    val activePartyTransactions: StateFlow<List<PartyTransaction>> = _activeParty
        .flatMapLatest { party ->
            if (party != null) {
                repository.getPartyTransactions(party.id)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // All party transactions (to calculate net Udhar summary)
    val allPartyTransactions: StateFlow<List<PartyTransaction>> = repository.allPartyTransactions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Gemini/AI state
    private val _aiDraftTransaction = MutableStateFlow<ParsedTransaction?>(null)
    val aiDraftTransaction: StateFlow<ParsedTransaction?> = _aiDraftTransaction.asStateFlow()

    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading.asStateFlow()

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError.asStateFlow()

    private val _chatHistory = MutableStateFlow<List<ChatMessage>>(listOf(
        ChatMessage("assistant", "Hello! I'm your Cashbook Pro assistant. Tell me something like: 'Got 1500 for retail sales' or 'Paid 300 cash for courier charges' and I will record it instantly! You can also consult me about financial statement summaries.")
    ))
    val chatHistory: StateFlow<List<ChatMessage>> = _chatHistory.asStateFlow()

    init {
        // Keep activeBusiness always valid for the active user's businesses list
        viewModelScope.launch {
            businesses.collect { list ->
                val current = _activeBusiness.value
                if (list.isEmpty()) {
                    _activeBusiness.value = null
                    _activeBook.value = null
                } else if (current == null || list.none { it.id == current.id }) {
                    val preferred = list.find { it.name != "My Business" && !it.name.contains("@") } ?: list.first()
                    _activeBusiness.value = preferred
                    _activeBook.value = null
                } else {
                    val refreshed = list.first { it.id == current.id }
                    if (refreshed != current) {
                        _activeBusiness.value = refreshed
                    }
                }
            }
        }

        // Keep activeBook always valid for the active business
        viewModelScope.launch {
            books.collect { list ->
                val current = _activeBook.value
                if (list.isEmpty()) {
                    _activeBook.value = null
                } else if (current == null || list.none { it.id == current.id }) {
                    _activeBook.value = list.first()
                } else {
                    val refreshed = list.first { it.id == current.id }
                    if (refreshed != current) {
                        _activeBook.value = refreshed
                    }
                }
            }
        }

        // Initialize Wasooli Notification Channel & reschedule upcoming reminders
        WasooliNotificationHelper.createNotificationChannel(getApplication())
        viewModelScope.launch {
            allBooks.first { it.isNotEmpty() }.let { loadedBooks ->
                val txs = repository.allTransactions.first()
                _wasooliDueDates.value.forEach { (bookId, dueMillis) ->
                    val book = loadedBooks.find { it.id == bookId }
                    if (book != null) {
                        val bookTxs = txs.filter { it.bookId == bookId }
                        val net = bookTxs.filter { it.type == "IN" }.sumOf { it.amount } -
                            bookTxs.filter { it.type == "OUT" }.sumOf { it.amount }
                        WasooliNotificationHelper.scheduleWasooliReminder(
                            context = getApplication(),
                            bookId = bookId,
                            bookName = book.name,
                            netBalance = net,
                            dueMillis = dueMillis
                        )
                    }
                }
            }
        }

        // Automatic Firebase Cloud & Google Drive Sync on app launch
        viewModelScope.launch {
            syncManager.fetchFirebaseAccountsCloud()
            triggerCloudSync()
        }

        // Initial network check
        checkIsOnline()

        // Observe network state to trigger auto-sync when back online
        try {
            val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager != null) {
                val networkRequest = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        _isOnline.value = true
                        Log.d("LedgerViewModel", "Network available - triggering auto-sync")
                        triggerCloudSync()
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        _isOnline.value = false
                        _syncStatus.value = "Offline Mode - No Network Connection"
                        Log.d("LedgerViewModel", "Network lost - switched to Offline Mode")
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        _isOnline.value = false
                        _syncStatus.value = "Offline Mode - Network Unavailable"
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("LedgerViewModel", "Failed to register network callback", e)
        }

        // Real-time periodic cloud polling (every 15 seconds) so team members and transactions auto-update live silently
        viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(15000L)
                if (checkIsOnline() && syncManager.isUserSignedIn() && syncManager.isRealCloudAccount() && !_isSyncing.value) {
                    try {
                        val msg = syncManager.syncWithCloud(database.ledgerDao())
                        if (checkIsOnline()) {
                            _syncStatus.value = msg
                        }
                    } catch (e: Exception) {
                        Log.e("LedgerViewModel", "Background sync error", e)
                    }
                }
            }
        }
    }

    // --- Core Cloud Sync Mechanism ---

    fun triggerCloudSync() {
        if (!checkIsOnline()) {
            _syncStatus.value = "Offline Mode - No Network Connection"
            return
        }
        if (!syncManager.isUserSignedIn()) {
            _syncStatus.value = "Sign in to synchronize automatically"
            return
        }
        if (_isSyncing.value) {
            pendingCloudSync = true
            return
        }

        _isSyncing.value = true
        _syncStatus.value = "Cloud Syncing..."

        viewModelScope.launch {
            try {
                val message = syncManager.syncWithCloud(database.ledgerDao())
                if (checkIsOnline()) {
                    _syncStatus.value = message
                } else {
                    _syncStatus.value = "Offline Mode - No Network Connection"
                }

                // After sync, preserve current active business & active book strictly scoped to active user
                val allBiz = repository.allBusinesses.first()
                val allTeam = repository.allTeamMembers.first()
                val currentBizList = filterBusinessesForUser(
                    list = allBiz,
                    activeEmail = _currentUserEmail.value,
                    superAdmin = isSuperAdmin.value,
                    teamMembers = allTeam
                )
                if (currentBizList.isNotEmpty()) {
                    val currentBiz = _activeBusiness.value
                    val validBiz = if (currentBiz != null && currentBizList.any { it.id == currentBiz.id }) {
                        currentBizList.first { it.id == currentBiz.id }
                    } else {
                        currentBizList.find { it.name != "My Business" && !it.name.contains("@") } ?: currentBizList.first()
                    }
                    if (_activeBusiness.value != validBiz) {
                        _activeBusiness.value = validBiz
                    }

                    val activeBizId = validBiz.id
                    val rawBooksList = repository.getBooksForBusiness(activeBizId).first()
                    val currentBooksList = filterBooksForUser(
                        booksList = rawBooksList,
                        visibleBusinesses = listOf(validBiz),
                        activeEmail = _currentUserEmail.value,
                        superAdmin = isSuperAdmin.value,
                        teamMembers = allTeam
                    )
                    if (currentBooksList.isNotEmpty()) {
                        val currentBook = _activeBook.value
                        val validBook = if (currentBook != null && currentBook.businessId == activeBizId && currentBooksList.any { it.id == currentBook.id }) {
                            currentBooksList.first { it.id == currentBook.id }
                        } else {
                            currentBooksList.first()
                        }
                        if (_activeBook.value != validBook) {
                            _activeBook.value = validBook
                        }
                    } else {
                        _activeBook.value = null
                    }
                } else if (syncManager.isUserSignedIn() && !pendingCloudSync) {
                    val email = syncManager.getEmail()
                    val globalAcc = syncManager.getGlobalAccounts().find { it.email.equals(email, ignoreCase = true) }
                    val rawName = globalAcc?.name?.trim()?.ifBlank { null } ?: syncManager.getName().trim().ifBlank { null }
                    val bizName = if (!rawName.isNullOrBlank() && !rawName.contains("@")) "${rawName}'s Business" else "Main Business"
                    createBusinessAndBook(bizName, "")
                }
            } catch (e: Exception) {
                _syncStatus.value = "Sync Interrupted: ${e.message}"
            } finally {
                _isSyncing.value = false
                if (pendingCloudSync) {
                    pendingCloudSync = false
                    triggerCloudSync()
                }
            }
        }
    }

    fun triggerDriveSync(onResult: (String) -> Unit = {}) {
        if (!syncManager.hasGoogleDriveToken()) {
            onResult("Google Drive not connected")
            return
        }
        viewModelScope.launch {
            _isSyncing.value = true
            _syncStatus.value = "Google Drive Syncing..."
            try {
                val message = syncManager.syncWithGoogleDrive(database.ledgerDao())
                _syncStatus.value = message
                onResult(message)
            } catch (e: Exception) {
                val errMessage = "Drive Sync Error: ${e.message}"
                _syncStatus.value = errMessage
                onResult(errMessage)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    // --- Multi-Business and Multi-Book Actions ---

    fun setSimulatedRole(role: String) {
        _simulatedRole.value = role
    }

    fun selectBusiness(business: Business) {
        _activeBusiness.value = business
        _activeBook.value = null // Let books.collect auto-select the first book
    }

    fun createBusiness(name: String, firstBookName: String = "", firstBookPhone: String = "") {
        createBusinessAndBook(name, firstBookName, firstBookPhone)
    }

    fun updateBusiness(business: Business) {
        viewModelScope.launch {
            syncManager.markLocalDbModified()
            val updated = business.copy(isSynced = false)
            repository.updateBusiness(updated)
            if (_activeBusiness.value?.id == business.id) {
                _activeBusiness.value = updated
            }
            triggerCloudSync()
        }
    }

    fun createBusinessAndBook(businessName: String, bookName: String = "", bookPhone: String = "") {
        val cleanBizName = businessName.trim()
        val cleanBookName = bookName.trim()
        val cleanBookPhone = bookPhone.trim()
        if (cleanBizName.isBlank()) return
        val userEmail = syncManager.getEmail().trim().lowercase()
        viewModelScope.launch {
            syncManager.removeBusinessFromTombstones(cleanBizName)
            syncManager.markLocalDbModified()
            val id = repository.insertBusiness(Business(name = cleanBizName, userEmail = userEmail, isSynced = false))
            val newBiz = Business(id = id.toInt(), name = cleanBizName, userEmail = userEmail, isSynced = false)
            _activeBusiness.value = newBiz
            if (cleanBookName.isNotBlank()) {
                syncManager.removeBookFromTombstones(newBiz.id, cleanBookName)
                val bookId = repository.insertBook(Book(businessId = newBiz.id, name = cleanBookName, phone = cleanBookPhone, isSynced = false))
                _activeBook.value = Book(id = bookId.toInt(), businessId = newBiz.id, name = cleanBookName, phone = cleanBookPhone, isSynced = false)
            } else {
                _activeBook.value = null
            }
            _isHomeBusinessesListVisible.value = false
            _isHomeBooksHubVisible.value = true
            triggerCloudSync()
        }
    }

    fun deleteBusiness(business: Business) {
        viewModelScope.launch {
            saveBusinessToTrash(business)
            syncManager.recordDeletedBusiness(business.name, business.userEmail)
            repository.deleteBusiness(business)
            if (_activeBusiness.value?.id == business.id) {
                _activeBusiness.value = businesses.value.firstOrNull { it.id != business.id }
                _activeBook.value = null
            }
            triggerCloudSync()
        }
    }

    fun setScreen(screen: Screen) {
        if (screen == Screen.DASHBOARD) {
            _isHomeBusinessesListVisible.value = true
            _isHomeBooksHubVisible.value = true
        } else if (screen == Screen.REPORTS) {
            _reportSelectedBusinessId.value = null
            _reportSelectedBookId.value = null
        }
        _currentScreen.value = screen
    }

    fun openReportsHome() {
        _reportSelectedBusinessId.value = null
        _reportSelectedBookId.value = null
        _currentScreen.value = Screen.REPORTS
    }

    fun openBusinessReport(business: Business) {
        selectBusiness(business)
        _reportSelectedBusinessId.value = business.id
        _reportSelectedBookId.value = null
        _currentScreen.value = Screen.REPORTS
    }

    fun openBookReport(book: Book) {
        selectBook(book)
        _reportSelectedBusinessId.value = book.businessId
        _reportSelectedBookId.value = book.id
        _currentScreen.value = Screen.REPORTS
    }

    fun selectReportBook(bookId: Int?) {
        _reportSelectedBookId.value = bookId
    }

    fun openBusinessesHome() {
        _isHomeBusinessesListVisible.value = true
        _isHomeBooksHubVisible.value = true
        _currentScreen.value = Screen.DASHBOARD
    }

    fun openBusinessBooks(business: Business) {
        selectBusiness(business)
        _isHomeBusinessesListVisible.value = false
        _isHomeBooksHubVisible.value = true
        _currentScreen.value = Screen.DASHBOARD
    }

    fun openBooksHub() {
        _isHomeBusinessesListVisible.value = false
        _isHomeBooksHubVisible.value = true
        _currentScreen.value = Screen.DASHBOARD
    }

    fun openBookLedger(book: Book) {
        selectBook(book)
        _isHomeBusinessesListVisible.value = false
        _isHomeBooksHubVisible.value = false
        _currentScreen.value = Screen.DASHBOARD
    }

    fun selectBook(book: Book) {
        val parentBiz = businesses.value.find { it.id == book.businessId }
        if (parentBiz != null && _activeBusiness.value?.id != parentBiz.id) {
            _activeBusiness.value = parentBiz
        }
        _activeBook.value = book
    }

    fun createBook(name: String, phone: String = "") {
        val bizId = _activeBusiness.value?.id ?: return
        viewModelScope.launch {
            syncManager.removeBookFromTombstones(bizId, name)
            syncManager.markLocalDbModified()
            val id = repository.insertBook(Book(businessId = bizId, name = name, phone = phone, isSynced = false))
            val newBook = Book(id = id.toInt(), businessId = bizId, name = name, phone = phone, isSynced = false)
            _activeBook.value = newBook
            triggerCloudSync()
        }
    }

    fun updateBook(book: Book) {
        viewModelScope.launch {
            syncManager.markLocalDbModified()
            val updated = book.copy(isSynced = false)
            repository.updateBook(updated)
            if (_activeBook.value?.id == book.id) {
                _activeBook.value = updated
            }
            triggerCloudSync()
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            saveBookToTrash(book)
            syncManager.recordDeletedBook(book.businessId, book.name)
            repository.deleteBook(book)
            if (_activeBook.value?.id == book.id) {
                _activeBook.value = books.value.firstOrNull { it.id != book.id }
            }
            triggerCloudSync()
        }
    }

    // --- Auth State Updates ---
    fun updateAuthState() {
        val prevEmail = _currentUserEmail.value
        val newEmail = syncManager.getEmail().trim().lowercase()
        isUserSignedIn.value = syncManager.isUserSignedIn()
        isSuperAdmin.value = syncManager.isSuperAdminLoggedIn()
        _currentUserEmail.value = newEmail
        if (prevEmail != newEmail) {
            _activeBusiness.value = null
            _activeBook.value = null
            _activeParty.value = null
            _isHomeBusinessesListVisible.value = true
            _isHomeBooksHubVisible.value = true
        }
    }

    fun registerCustomUser(name: String, email: String, username: String, pass: String) {
        syncManager.registerCustomUser(name, email, username, pass)
        updateAuthState()
        _simulatedRole.value = "Boss"
        viewModelScope.launch {
            syncManager.registerUserCloud(name, email, username, pass)
            triggerCloudSync()
        }
    }

    suspend fun registerUserWithOptionalInvite(
        name: String,
        email: String,
        username: String,
        pass: String,
        inviteCode: String = "",
        defaultBusinessName: String = "",
        defaultBookName: String = ""
    ): Pair<Boolean, String> {
        val cleanCode = inviteCode.trim().uppercase()
        val cleanEmail = email.trim().lowercase()
        if (cleanCode.isNotBlank()) {
            val inv = syncManager.lookupInviteByCodeCloud(cleanCode)
                ?: return Pair(false, "Invite code '$cleanCode' was not found or has expired. Check the code or clear it to create your own account.")
            if (inv.invitedEmail.isNotBlank() && !inv.invitedEmail.equals(cleanEmail, ignoreCase = true)) {
                return Pair(false, "This invite code was sent to '${inv.invitedEmail}'. Please Sign Up with '${inv.invitedEmail}'.")
            }
        }

        val (registered, regMsg) = syncManager.registerUserCloud(name, email, username, pass)
        if (!registered) return Pair(false, regMsg)

        updateAuthState()
        _simulatedRole.value = "Boss"

        var joinedInviteMsg: String? = null
        if (cleanCode.isNotBlank()) {
            val (ok, msg) = syncManager.redeemInviteCodeCloud(cleanCode, cleanEmail, database.ledgerDao())
            if (ok) joinedInviteMsg = msg
        }

        val syncMsg = syncManager.syncWithCloud(database.ledgerDao())
        _syncStatus.value = syncMsg

        val visibleBusinesses = filterBusinessesForUser(
            list = repository.allBusinesses.first(),
            activeEmail = _currentUserEmail.value,
            superAdmin = isSuperAdmin.value,
            teamMembers = repository.allTeamMembers.first()
        )

        return if (visibleBusinesses.isEmpty()) {
            val bizName = defaultBusinessName.trim().ifBlank {
                if (name.isNotBlank()) "${name.trim()}'s Business" else "Main Business"
            }
            val bkName = defaultBookName.trim()
            syncManager.removeBusinessFromTombstones(bizName)
            syncManager.markLocalDbModified()
            val id = repository.insertBusiness(Business(name = bizName, userEmail = cleanEmail, isSynced = false))
            val newBiz = Business(id = id.toInt(), name = bizName, userEmail = cleanEmail, isSynced = false)
            _activeBusiness.value = newBiz
            if (bkName.isNotBlank()) {
                syncManager.removeBookFromTombstones(newBiz.id, bkName)
                val bookId = repository.insertBook(Book(businessId = newBiz.id, name = bkName, isSynced = false))
                _activeBook.value = Book(id = bookId.toInt(), businessId = newBiz.id, name = bkName, isSynced = false)
            } else {
                _activeBook.value = null
            }
            _isHomeBusinessesListVisible.value = false
            _isHomeBooksHubVisible.value = true
            triggerCloudSync()
            Pair(true, "Welcome ${name.ifBlank { username }}! Account registered.")
        } else {
            val preferred = visibleBusinesses.find {
                it.userEmail.isNotBlank() && !it.userEmail.equals(cleanEmail, ignoreCase = true)
            } ?: visibleBusinesses.first()
            _activeBusiness.value = preferred
            _activeBook.value = null
            _isHomeBusinessesListVisible.value = true
            _isHomeBooksHubVisible.value = true
            val displayMsg = joinedInviteMsg ?: if (regMsg == "EXISTING_ACCOUNT_RESTORED") {
                "Existing account verified! Restored your cloud data."
            } else {
                "Connected to '${preferred.name}'!"
            }
            Pair(true, displayMsg)
        }
    }

    suspend fun loginSuperAdminCloud(user: String, pass: String): Boolean {
        val success = syncManager.loginUserCloud(user, pass)
        if (success) {
            updateAuthState()
            _simulatedRole.value = "Boss"
            triggerCloudSync()
        }
        return success
    }

    fun loginSuperAdmin(user: String, pass: String): Boolean {
        val success = syncManager.loginSuperAdmin(user, pass)
        if (success) {
            updateAuthState()
            _simulatedRole.value = "Boss"
            viewModelScope.launch {
                syncManager.loginUserCloud(user, pass)
                triggerCloudSync()
            }
        }
        return success
    }

    fun loginSuperAdminWithPassword(pass: String): Boolean {
        val success = syncManager.loginSuperAdminWithPassword(pass)
        if (success) {
            updateAuthState()
            _simulatedRole.value = "Boss"
            viewModelScope.launch {
                syncManager.loginUserCloud("admin", pass)
                triggerCloudSync()
            }
        }
        return success
    }

    fun updateSuperAdminMasterPassword(currentPass: String, newPass: String): Pair<Boolean, String> {
        if (!syncManager.loginSuperAdminWithPassword(currentPass)) {
            return Pair(false, "Current Master Password is incorrect")
        }
        val trimmedNew = newPass.trim()
        if (trimmedNew.length < 4) {
            return Pair(false, "New Master Password must be at least 4 characters")
        }
        val saved = syncManager.setSuperAdminMasterPassword(trimmedNew)
        if (!saved) {
            return Pair(false, "Failed to save Master Password")
        }
        globalAccounts.value = syncManager.getGlobalAccounts()
        viewModelScope.launch {
            syncManager.registerUserCloud("Super Admin", "admin@cashbook.com", "admin", trimmedNew)
        }
        return Pair(true, "Master Password updated successfully!")
    }

    fun loginUser(userOrEmail: String, pass: String): Boolean {
        val success = syncManager.loginUser(userOrEmail, pass)
        if (success) {
            updateAuthState()
            _simulatedRole.value = "Boss"
            viewModelScope.launch {
                syncManager.loginUserCloud(userOrEmail, pass)
                triggerCloudSync()
            }
        }
        return success
    }

    fun logoutSuperAdmin() {
        syncManager.logoutSuperAdmin()
        _activeBusiness.value = null
        _activeBook.value = null
        _activeParty.value = null
        updateAuthState()
        _simulatedRole.value = "Boss"
    }

    fun logoutUser() {
        syncManager.logoutUser()
        _activeBusiness.value = null
        _activeBook.value = null
        _activeParty.value = null
        updateAuthState()
        _simulatedRole.value = "Boss"
    }

    val globalAccounts = MutableStateFlow<List<com.example.data.RegisteredAccount>>(syncManager.getGlobalAccounts())
    val isRefreshingAccounts = MutableStateFlow(false)

    fun refreshCloudAccounts(onComplete: ((List<com.example.data.RegisteredAccount>) -> Unit)? = null) {
        viewModelScope.launch {
            isRefreshingAccounts.value = true
            try {
                syncManager.fetchFirebaseAccountsCloud()
                val updated = syncManager.getGlobalAccounts()
                globalAccounts.value = updated
                onComplete?.invoke(updated)
            } catch (e: Exception) {
                Log.e("LedgerViewModel", "Error refreshing cloud accounts", e)
            } finally {
                isRefreshingAccounts.value = false
            }
        }
    }

    fun addUserCloud(name: String, email: String, username: String, pass: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            isRefreshingAccounts.value = true
            try {
                val success = syncManager.addUserCloud(name, email, username, pass)
                refreshCloudAccounts()
                onComplete?.invoke(success)
            } catch (e: Exception) {
                Log.e("LedgerViewModel", "Error adding user cloud", e)
                onComplete?.invoke(false)
            } finally {
                isRefreshingAccounts.value = false
            }
        }
    }

    fun updateUserCloud(oldEmail: String, newName: String, newEmail: String, newUsername: String, newPass: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            isRefreshingAccounts.value = true
            try {
                val success = syncManager.updateUserCloud(oldEmail, newName, newEmail, newUsername, newPass)
                refreshCloudAccounts()
                onComplete?.invoke(success)
            } catch (e: Exception) {
                Log.e("LedgerViewModel", "Error updating user cloud", e)
                onComplete?.invoke(false)
            } finally {
                isRefreshingAccounts.value = false
            }
        }
    }

    fun deleteUserCloud(emailToDelete: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            isRefreshingAccounts.value = true
            try {
                val success = syncManager.deleteUserCloud(emailToDelete)
                refreshCloudAccounts()
                onComplete?.invoke(success)
            } catch (e: Exception) {
                Log.e("LedgerViewModel", "Error deleting user cloud", e)
                onComplete?.invoke(false)
            } finally {
                isRefreshingAccounts.value = false
            }
        }
    }

    suspend fun checkEmailExistsCloud(email: String): Boolean {
        val exists = syncManager.checkEmailExistsCloud(email)
        globalAccounts.value = syncManager.getGlobalAccounts()
        return exists
    }

    fun checkEmailExists(email: String): Boolean {
        viewModelScope.launch {
            syncManager.fetchFirebaseAccountsCloud()
            globalAccounts.value = syncManager.getGlobalAccounts()
        }
        val target = email.trim().lowercase()
        val inCloudList = globalAccounts.value.any { acc ->
            val accEmail = acc.email.trim().lowercase()
            val accUser = acc.username.trim().lowercase()
            target == accEmail ||
            target == accUser ||
            (accEmail.contains("@") && target == accEmail.substringBefore("@"))
        }
        if (inCloudList) return true
        return syncManager.checkEmailExists(email, allTeamMembers.value)
    }

    fun resetPassword(userOrEmail: String, newPass: String): Boolean {
        val success = syncManager.resetPassword(userOrEmail, newPass)
        if (success) {
            viewModelScope.launch {
                syncManager.resetPasswordCloud(userOrEmail, newPass)
            }
        }
        return success
    }

    suspend fun resetPasswordAndLoginCloud(userOrEmail: String, newPass: String): Boolean {
        val cloudSaved = syncManager.resetPasswordCloud(userOrEmail, newPass)
        val localSaved = if (!cloudSaved) syncManager.resetPassword(userOrEmail, newPass) else true
        if (!localSaved) return false
        syncManager.loginUser(userOrEmail, newPass)
        updateAuthState()
        _simulatedRole.value = "Boss"
        triggerCloudSync()
        return true
    }

    // --- Transactions Actions ---

    fun addTransaction(amount: Double, type: String, category: String, paymentMethod: String, remarks: String, receiptUri: String? = null, timestamp: Long = System.currentTimeMillis()) {
        val bookId = _activeBook.value?.id ?: return
        val currentRole = actualUserRoleForActiveBusiness.value.trim().lowercase()
        if (!isActiveBusinessOwner.value && (currentRole == "viewer" || currentRole == "read only" || currentRole == "read-only")) {
            return
        }
        val creatorEmail = syncManager.getEmail().trim().lowercase()
        val biz = _activeBusiness.value
        val bizOwnerEmail = biz?.userEmail?.trim()?.lowercase() ?: ""
        val isOwner = isSuperAdmin.value || bizOwnerEmail.isBlank() || bizOwnerEmail == creatorEmail
        val rawSyncName = syncManager.getName().trim().let {
            if (it.equals("Offline User", true) || it.equals("CashBook User", true) || it.equals("User", true)) "" else it
        }
        val memberEntryName = allTeamMembers.value.firstOrNull {
            (biz == null || it.businessId == biz.id) &&
                creatorEmail.isNotBlank() &&
                it.email.trim().equals(creatorEmail, ignoreCase = true)
        }?.name?.trim().orEmpty()
        val globalAccName = globalAccounts.value.firstOrNull {
            creatorEmail.isNotBlank() && it.email.trim().equals(creatorEmail, ignoreCase = true)
        }?.name?.trim().orEmpty()
        val creatorName = if (!isOwner) {
            memberEntryName.ifBlank { rawSyncName }.ifBlank { globalAccName }.ifBlank {
                creatorEmail.substringBefore("@").ifBlank { "Team Member" }
            }
        } else {
            rawSyncName.ifBlank { globalAccName }.ifBlank {
                creatorEmail.substringBefore("@").ifBlank { "Owner" }
            }
        }
        viewModelScope.launch {
            syncManager.markLocalDbModified()
            repository.insertTransaction(
                Transaction(
                    bookId = bookId,
                    amount = amount,
                    type = type,
                    category = category,
                    paymentMethod = paymentMethod,
                    remarks = remarks,
                    timestamp = timestamp,
                    isSynced = false,
                    receiptUri = receiptUri,
                    addedByName = creatorName,
                    addedByEmail = creatorEmail
                )
            )
            triggerCloudSync()
        }
    }

    private suspend fun recordAuditLogForTransaction(
        action: String,
        tx: Transaction,
        summary: String,
        oldDetails: String = "",
        newDetails: String = ""
    ) {
        val dao = database.ledgerDao()
        val book = dao.getAllBooksList().find { it.id == tx.bookId }
        val biz = if (book != null) dao.getAllBusinessesList().find { it.id == book.businessId } else _activeBusiness.value
        val bizName = biz?.name ?: _activeBusiness.value?.name ?: ""
        val bookName = book?.name ?: _activeBook.value?.name ?: "Cashbook"
        val ownerScope = (biz?.userEmail ?: "").trim().lowercase().ifBlank { currentScopeEmail() }
        repository.insertAuditLog(
            AuditLog(
                businessId = biz?.id ?: book?.businessId ?: 0,
                businessName = bizName,
                bookId = book?.id ?: tx.bookId,
                bookName = bookName,
                transactionId = tx.id,
                action = action,
                summary = summary,
                oldDetails = oldDetails,
                newDetails = newDetails,
                actorName = currentActorDisplayName(),
                actorEmail = currentScopeEmail(),
                actorRole = actualUserRoleForActiveBusiness.value,
                userEmail = ownerScope,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun formatTxSnapshot(tx: Transaction): String {
        val df = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
        val typeLabel = if (tx.type == "IN") "Cash In" else "Cash Out"
        val amtLabel = "Rs. ${String.format("%,.0f", tx.amount)}"
        val parts = mutableListOf("$typeLabel $amtLabel")
        if (tx.category.isNotBlank()) parts.add("Category: ${tx.category}")
        if (tx.paymentMethod.isNotBlank()) parts.add("Mode: ${tx.paymentMethod}")
        if (tx.remarks.isNotBlank()) parts.add("Note: ${tx.remarks}")
        parts.add("Entry Date: ${df.format(Date(tx.timestamp))}")
        if (tx.addedByName.isNotBlank()) parts.add("Added By: ${tx.addedByName}")
        return parts.joinToString(" • ")
    }

    private fun buildEditAuditInfo(oldTx: Transaction, newTx: Transaction): Triple<String, String, String> {
        val changes = mutableListOf<String>()
        val oldParts = mutableListOf<String>()
        val newParts = mutableListOf<String>()
        val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

        if (oldTx.amount != newTx.amount) {
            val o = "Rs. ${String.format("%,.0f", oldTx.amount)}"
            val n = "Rs. ${String.format("%,.0f", newTx.amount)}"
            changes.add("Amount $o → $n")
            oldParts.add("Amount: $o")
            newParts.add("Amount: $n")
        }
        if (!oldTx.type.equals(newTx.type, ignoreCase = true)) {
            val o = if (oldTx.type == "IN") "Cash In" else "Cash Out"
            val n = if (newTx.type == "IN") "Cash In" else "Cash Out"
            changes.add("Type $o → $n")
            oldParts.add("Type: $o")
            newParts.add("Type: $n")
        }
        if (oldTx.category != newTx.category) {
            changes.add("Category ${oldTx.category} → ${newTx.category}")
            oldParts.add("Category: ${oldTx.category}")
            newParts.add("Category: ${newTx.category}")
        }
        if (oldTx.paymentMethod != newTx.paymentMethod) {
            changes.add("Mode ${oldTx.paymentMethod} → ${newTx.paymentMethod}")
            oldParts.add("Mode: ${oldTx.paymentMethod}")
            newParts.add("Mode: ${newTx.paymentMethod}")
        }
        if (oldTx.remarks != newTx.remarks) {
            val o = oldTx.remarks.ifBlank { "(empty)" }
            val n = newTx.remarks.ifBlank { "(empty)" }
            changes.add("Note \"$o\" → \"$n\"")
            oldParts.add("Note: $o")
            newParts.add("Note: $n")
        }
        if (oldTx.timestamp != newTx.timestamp) {
            val o = df.format(Date(oldTx.timestamp))
            val n = df.format(Date(newTx.timestamp))
            changes.add("Date $o → $n")
            oldParts.add("Date: $o")
            newParts.add("Date: $n")
        }
        if ((oldTx.receiptUri ?: "") != (newTx.receiptUri ?: "")) {
            val o = if (oldTx.receiptUri.isNullOrBlank()) "None" else "Attached"
            val n = if (newTx.receiptUri.isNullOrBlank()) "Removed" else "Attached"
            changes.add("Receipt $o → $n")
            oldParts.add("Receipt: $o")
            newParts.add("Receipt: $n")
        }

        val summary = if (changes.isNotEmpty()) {
            changes.joinToString(" • ")
        } else {
            "Updated ${if (newTx.type == "IN") "Cash In" else "Cash Out"} Rs. ${String.format("%,.0f", newTx.amount)}"
        }
        return Triple(
            summary,
            oldParts.joinToString(" • ").ifBlank { formatTxSnapshot(oldTx) },
            newParts.joinToString(" • ").ifBlank { formatTxSnapshot(newTx) }
        )
    }

    fun deleteTransaction(transaction: Transaction, onBlocked: ((String) -> Unit)? = null) {
        if (!canModifyTransactionInActiveBusiness(transaction, "delete_transaction")) {
            onBlocked?.invoke("Locked: Your role cannot delete past days' entries.")
            return
        }
        viewModelScope.launch {
            saveTransactionToTrash(transaction)
            val label = if (transaction.type == "IN") "Cash In" else "Cash Out"
            val notePart = transaction.remarks.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            recordAuditLogForTransaction(
                action = "DELETED",
                tx = transaction,
                summary = "Deleted $label Rs. ${String.format("%,.0f", transaction.amount)}$notePart",
                oldDetails = formatTxSnapshot(transaction),
                newDetails = "Moved to Trash"
            )
            syncManager.recordDeletedTransaction(transaction.timestamp)
            repository.deleteTransaction(transaction)
            triggerCloudSync()
        }
    }

    fun updateTransaction(transaction: Transaction, onBlocked: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val oldTx = database.ledgerDao().getAllTransactionsList().find { it.id == transaction.id } ?: transaction
            if (!canModifyTransactionInActiveBusiness(oldTx, "edit_transaction")) {
                onBlocked?.invoke("Locked: Your role cannot edit past days' entries.")
                return@launch
            }
            val (summary, oldDetails, newDetails) = buildEditAuditInfo(oldTx, transaction)
            syncManager.markLocalDbModified()
            repository.updateTransaction(transaction.copy(isSynced = false))
            recordAuditLogForTransaction(
                action = "EDITED",
                tx = transaction,
                summary = summary,
                oldDetails = oldDetails,
                newDetails = newDetails
            )
            triggerCloudSync()
        }
    }

    // --- Multi-Select Batch Operations ---

    fun toggleTransactionSelection(id: Int) {
        val current = _selectedTransactionIds.value
        _selectedTransactionIds.value = if (current.contains(id)) {
            current - id
        } else {
            current + id
        }
    }

    fun clearTransactionSelection() {
        _selectedTransactionIds.value = emptySet()
    }

    fun batchDeleteSelectedTransactions(onResult: ((Int, Int) -> Unit)? = null) {
        viewModelScope.launch {
            val idsToDelete = _selectedTransactionIds.value
            val selectedTxs = activeBookTransactions.value.filter { idsToDelete.contains(it.id) }
            val allowedToDelete = selectedTxs.filter { canModifyTransactionInActiveBusiness(it, "delete_transaction") }
            val skippedCount = selectedTxs.size - allowedToDelete.size
            allowedToDelete.forEach { tx ->
                saveTransactionToTrash(tx)
                val label = if (tx.type == "IN") "Cash In" else "Cash Out"
                val notePart = tx.remarks.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                recordAuditLogForTransaction(
                    action = "DELETED",
                    tx = tx,
                    summary = "Deleted $label Rs. ${String.format("%,.0f", tx.amount)}$notePart",
                    oldDetails = formatTxSnapshot(tx),
                    newDetails = "Moved to Trash"
                )
                syncManager.recordDeletedTransaction(tx.timestamp)
                repository.deleteTransaction(tx)
            }
            clearTransactionSelection()
            if (allowedToDelete.isNotEmpty()) {
                triggerCloudSync()
            }
            onResult?.invoke(allowedToDelete.size, skippedCount)
        }
    }

    fun batchGetSelectedCSV(): String {
        val idsToExport = _selectedTransactionIds.value
        val txsToExport = activeBookTransactions.value.filter { idsToExport.contains(it.id) }
        val sb = StringBuilder()
        sb.append("Date,Type,Amount,Category,Payment Method,Remarks\n")
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (tx in txsToExport) {
            val dateStr = df.format(Date(tx.timestamp))
            val escapedRemarks = tx.remarks.replace("\"", "\"\"")
            sb.append("$dateStr,${tx.type},${tx.amount},${tx.category},${tx.paymentMethod},\"$escapedRemarks\"\n")
        }
        return sb.toString()
    }

    // --- Search & Filtering ---

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCategoryFilter(category: String) {
        _selectedCategory.value = category
    }

    fun setPaymentMethodFilter(method: String) {
        _selectedPaymentMethod.value = method
    }

    // --- Customer / Supplier credit ledger (Party Book) ---

    fun addParty(name: String, phone: String) {
        viewModelScope.launch {
            syncManager.removePartyFromTombstones(name)
            syncManager.markLocalDbModified()
            repository.insertParty(Party(name = name, phone = phone))
            triggerCloudSync()
        }
    }

    fun deleteParty(party: Party) {
        viewModelScope.launch {
            savePartyToTrash(party)
            syncManager.recordDeletedParty(party.name)
            repository.deleteParty(party)
            if (_activeParty.value?.id == party.id) {
                _activeParty.value = null
            }
            triggerCloudSync()
        }
    }

    fun selectParty(party: Party?) {
        _activeParty.value = party
    }

    fun addPartyTransaction(partyId: Int, amount: Double, type: String, remarks: String) {
        viewModelScope.launch {
            syncManager.markLocalDbModified()
            repository.insertPartyTransaction(
                PartyTransaction(
                    partyId = partyId,
                    amount = amount,
                    type = type,
                    remarks = remarks,
                    isSynced = false
                )
            )
            triggerCloudSync()
        }
    }

    fun deletePartyTransaction(partyTransaction: PartyTransaction) {
        viewModelScope.launch {
            savePartyTransactionToTrash(partyTransaction)
            syncManager.recordDeletedPartyTransaction(partyTransaction.timestamp)
            repository.deletePartyTransaction(partyTransaction)
            triggerCloudSync()
        }
    }

    // --- Digital Team Management Actions & Cloud Invites ---

    fun checkPendingInvitesForEmail(email: String) {
        val clean = email.trim().lowercase()
        if (!clean.contains("@")) {
            _pendingEmailInvites.value = emptyList()
            return
        }
        viewModelScope.launch {
            _pendingEmailInvites.value = syncManager.fetchInvitesForEmailCloud(clean)
        }
    }

    suspend fun lookupInviteCode(code: String): TeamInviteRecord? {
        return syncManager.lookupInviteByCodeCloud(code)
    }

    fun joinBusinessByInviteCode(code: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                val activeEmail = syncManager.getEmail().trim().lowercase()
                val (ok, msg) = syncManager.redeemInviteCodeCloud(code, activeEmail, database.ledgerDao())
                if (ok) {
                    val bizList = filterBusinessesForUser(
                        list = repository.allBusinesses.first(),
                        activeEmail = _currentUserEmail.value,
                        superAdmin = isSuperAdmin.value,
                        teamMembers = repository.allTeamMembers.first()
                    )
                    val joinedBiz = bizList.find {
                        it.userEmail.isNotBlank() && !it.userEmail.equals(activeEmail, ignoreCase = true)
                    } ?: bizList.firstOrNull()
                    if (joinedBiz != null) {
                        _activeBusiness.value = joinedBiz
                        _activeBook.value = null
                    }
                    _syncStatus.value = "🟢 $msg"
                }
                onResult(ok, msg)
            } catch (e: Exception) {
                onResult(false, "Failed to join: ${e.localizedMessage ?: "Network error"}")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun leaveSharedBusiness(business: Business, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            val activeEmail = syncManager.getEmail().trim().lowercase()
            syncManager.removeSharedWorkspace(business.userEmail, business.name, activeEmail)
            syncManager.recordDeletedBusiness(business.name, business.userEmail)
            repository.deleteBusiness(business)
            if (_activeBusiness.value?.id == business.id) {
                _activeBusiness.value = businesses.value.firstOrNull { it.id != business.id }
                _activeBook.value = null
            }
            onResult("Left '${business.name}'.")
        }
    }

    fun getOrCreateInviteForMember(
        business: Business,
        member: TeamMember,
        onResult: (TeamInviteRecord, Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val (inv, published) = syncManager.publishTeamInviteCloud(business, member, database.ledgerDao())
            onResult(inv, published)
        }
    }

    fun addTeamMember(
        name: String,
        email: String,
        phone: String,
        role: String,
        allowedBookNames: String = "",
        hideBalance: Boolean = false,
        lockPastEntries: Boolean = defaultLockPastEntriesForRole(role),
        targetBusinessId: Int? = null,
        onInvitePublished: ((TeamInviteRecord, Boolean) -> Unit)? = null
    ) {
        val targetBiz = (targetBusinessId?.let { id -> businesses.value.find { it.id == id } }) ?: _activeBusiness.value
        val bizId = targetBiz?.id ?: 1
        val cleanEmail = email.trim().lowercase()
        val effectiveAllowed = if (isRoleFullBusinessAccess(role)) "" else allowedBookNames
        val effectiveHideBalance = hideBalance && canRoleHideBalance(role)
        val effectiveLockPast = lockPastEntries && canRoleLockPastEntries(role)
        val member = TeamMember(
            businessId = bizId,
            name = name.trim(),
            email = cleanEmail,
            phone = phone.trim(),
            role = role,
            allowedBookNames = effectiveAllowed,
            hideBalance = effectiveHideBalance,
            lockPastEntries = effectiveLockPast
        )
        viewModelScope.launch {
            syncManager.markTeamMemberEdited(cleanEmail)
            syncManager.removeTeamMemberFromTombstones(cleanEmail)
            syncManager.markLocalDbModified()
            repository.insertTeamMember(member)
            if (targetBiz != null) {
                val (invite, published) = syncManager.publishTeamInviteCloud(targetBiz, member, database.ledgerDao())
                onInvitePublished?.invoke(invite, published)
            }
            triggerCloudSync()
        }
    }

    fun updateTeamMember(
        oldMember: TeamMember,
        newName: String,
        newEmail: String,
        newPhone: String,
        newRole: String,
        newAllowedBookNames: String = oldMember.allowedBookNames,
        newHideBalance: Boolean = oldMember.hideBalance,
        newLockPastEntries: Boolean = oldMember.lockPastEntries,
        onCompleted: ((TeamInviteRecord?, Boolean) -> Unit)? = null
    ) {
        val targetBiz = businesses.value.find { it.id == oldMember.businessId } ?: _activeBusiness.value
        val cleanNewEmail = newEmail.trim().lowercase()
        val effectiveAllowed = if (isRoleFullBusinessAccess(newRole)) "" else newAllowedBookNames
        val effectiveHideBalance = newHideBalance && canRoleHideBalance(newRole)
        val effectiveLockPast = newLockPastEntries && canRoleLockPastEntries(newRole)
        val updated = oldMember.copy(
            name = newName.trim(),
            email = cleanNewEmail,
            phone = newPhone.trim(),
            role = newRole,
            allowedBookNames = effectiveAllowed,
            hideBalance = effectiveHideBalance,
            lockPastEntries = effectiveLockPast
        )
        viewModelScope.launch {
            if (targetBiz != null && oldMember.email.isNotBlank() && !oldMember.email.equals(cleanNewEmail, ignoreCase = true)) {
                syncManager.recordDeletedTeamMember(oldMember.email)
                syncManager.revokeTeamInviteCloud(targetBiz, oldMember.email)
            }
            syncManager.markTeamMemberEdited(cleanNewEmail)
            syncManager.removeTeamMemberFromTombstones(cleanNewEmail)
            syncManager.markLocalDbModified()
            repository.updateTeamMember(updated)
            var publishedInvite: TeamInviteRecord? = null
            var publishedOk = false
            if (targetBiz != null) {
                val (inv, ok) = syncManager.publishTeamInviteCloud(targetBiz, updated, database.ledgerDao())
                publishedInvite = inv
                publishedOk = ok
            }
            onCompleted?.invoke(publishedInvite, publishedOk)
            triggerCloudSync()
        }
    }

    fun setLockPastEntriesForBusinessDataEntry(businessId: Int, locked: Boolean) {
        viewModelScope.launch {
            val targetBiz = businesses.value.find { it.id == businessId } ?: _activeBusiness.value
            val members = database.ledgerDao().getAllTeamMembersList().filter {
                it.businessId == businessId && it.role.equals("Data Entry", ignoreCase = true)
            }
            if (members.isEmpty()) return@launch
            syncManager.markLocalDbModified()
            members.forEach { member ->
                val updated = member.copy(lockPastEntries = locked)
                if (member.email.isNotBlank()) {
                    syncManager.markTeamMemberEdited(member.email.trim().lowercase())
                }
                repository.updateTeamMember(updated)
                if (targetBiz != null) {
                    syncManager.publishTeamInviteCloud(targetBiz, updated, database.ledgerDao())
                }
            }
            triggerCloudSync()
        }
    }

    fun clearAuditLogs() {
        viewModelScope.launch {
            val email = currentScopeEmail()
            if (isSuperAdmin.value || email.isBlank()) {
                repository.clearAllAuditLogs()
            } else {
                repository.clearAuditLogsForUser(email)
            }
            syncManager.markLocalDbModified()
            triggerCloudSync()
        }
    }

    fun deleteTeamMember(teamMember: TeamMember) {
        viewModelScope.launch {
            val targetBiz = businesses.value.find { it.id == teamMember.businessId } ?: _activeBusiness.value
            syncManager.recordDeletedTeamMember(teamMember.email)
            repository.deleteTeamMember(teamMember)
            if (targetBiz != null) {
                syncManager.revokeTeamInviteCloud(targetBiz, teamMember.email)
            }
            triggerCloudSync()
        }
    }

    // --- Smart AI parsing ---

    fun clearDraftTransaction() {
        _aiDraftTransaction.value = null
    }

    fun saveDraftTransaction() {
        val draft = _aiDraftTransaction.value ?: return
        addTransaction(
            amount = draft.amount,
            type = draft.type,
            category = draft.category,
            paymentMethod = draft.paymentMethod,
            remarks = draft.remarks
        )
        _aiDraftTransaction.value = null
        _chatHistory.value = _chatHistory.value + ChatMessage("assistant", "✅ Entry logged: ${draft.type} Rs.${draft.amount} [${draft.category}] for ${draft.remarks} via ${draft.paymentMethod}")
    }

    fun sendChatMessage(message: String) {
        if (message.isBlank()) return
        _chatHistory.value = _chatHistory.value + ChatMessage("user", message)
        _aiLoading.value = true
        _aiError.value = null

        viewModelScope.launch {
            try {
                // Try parsing as transaction first
                val parsed = geminiService.parseTransaction(message)
                if (parsed != null && parsed.amount > 0.0) {
                    _aiDraftTransaction.value = parsed
                    _aiLoading.value = false
                    _chatHistory.value = _chatHistory.value + ChatMessage("assistant", "I formulated your record! Click 'Save to Ledger' below to approve.")
                } else {
                    val contextPrompt = buildAIContextPrompt(message)
                    val reply = geminiService.askFinancialAssistant(contextPrompt)
                    _aiLoading.value = false
                    _chatHistory.value = _chatHistory.value + ChatMessage("assistant", reply)
                }
            } catch (e: Exception) {
                _aiLoading.value = false
                _aiError.value = e.message
                _chatHistory.value = _chatHistory.value + ChatMessage("assistant", "Sorry, I had trouble parsing that input: ${e.message}")
            }
        }
    }

    private fun buildAIContextPrompt(userQuestion: String): String {
        val totalIn = activeBookTransactions.value.filter { it.type == "IN" }.sumOf { it.amount }
        val totalOut = activeBookTransactions.value.filter { it.type == "OUT" }.sumOf { it.amount }
        val balance = totalIn - totalOut

        val txHistory = activeBookTransactions.value.take(20).joinToString("\n") { tx ->
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(tx.timestamp))
            "- $dateStr: ${tx.type} Rs.${tx.amount} (Category: ${tx.category}, Method: ${tx.paymentMethod}, Remarks: ${tx.remarks})"
        }

        return """
            You are "LedgerMate", an interactive financial analysis and accounting expert assistant for the CashBook mobile app.
            
            Here is the current user's ledger summary:
            - Active Book: "${_activeBook.value?.name ?: "Default Book"}"
            - Total Money In: Rs.$totalIn
            - Total Money Out: Rs.$totalOut
            - Net Cash Balance: Rs.$balance
            
            Recent Transactions (up to 20):
            $txHistory
            
            User's question/input: "$userQuestion"
            
            Please provide a helpful, concise, and professional financial response. If they asked for advice, analyze their expenses and point out areas they can optimize. Keep it friendly and clear. Use Rs. for values.
        """.trimIndent()
    }

    // --- Report Utilities ---

    fun getCSVData(): String {
        return getCSVDataForTransactions(activeBookTransactions.value)
    }

    fun getCSVDataForTransactions(
        transactions: List<Transaction>,
        bookLookup: Map<Int, Book> = emptyMap(),
        bizLookup: Map<Int, Business> = emptyMap()
    ): String {
        val sb = StringBuilder()
        val includeScopeCols = bookLookup.isNotEmpty()
        sb.append(
            if (includeScopeCols) "Date,Business,Cashbook,Type,Amount,Category,Payment Method,Remarks\n"
            else "Date,Type,Amount,Category,Payment Method,Remarks\n"
        )
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (tx in transactions) {
            val dateStr = df.format(Date(tx.timestamp))
            val escapedRemarks = tx.remarks.replace("\"", "\"\"")
            if (includeScopeCols) {
                val book = bookLookup[tx.bookId]
                val biz = book?.businessId?.let { bizLookup[it] }
                val bizName = (biz?.name ?: "").replace("\"", "\"\"")
                val bkName = (book?.name ?: "").replace("\"", "\"\"")
                sb.append("$dateStr,\"$bizName\",\"$bkName\",${tx.type},${tx.amount},${tx.category},${tx.paymentMethod},\"$escapedRemarks\"\n")
            } else {
                sb.append("$dateStr,${tx.type},${tx.amount},${tx.category},${tx.paymentMethod},\"$escapedRemarks\"\n")
            }
        }
        return sb.toString()
    }

    // --- PASSCODE / LOCK STATES ---
    private val appPrefs = getApplication<Application>().getSharedPreferences("app_lock_prefs", android.content.Context.MODE_PRIVATE)

    private val _isAppLockEnabled = MutableStateFlow(appPrefs.getBoolean("is_lock_enabled", false))
    val isAppLockEnabled: StateFlow<Boolean> = _isAppLockEnabled.asStateFlow()

    private val _appPasscode = MutableStateFlow(appPrefs.getString("app_passcode", "") ?: "")
    val appPasscode: StateFlow<String> = _appPasscode.asStateFlow()

    private val _securityQuestion = MutableStateFlow(appPrefs.getString("security_question", "What was your first business name?") ?: "What was your first business name?")
    val securityQuestion: StateFlow<String> = _securityQuestion.asStateFlow()

    private val _securityAnswer = MutableStateFlow(appPrefs.getString("security_answer", "") ?: "")
    val securityAnswer: StateFlow<String> = _securityAnswer.asStateFlow()

    private val _isBiometricEnabled = MutableStateFlow(appPrefs.getBoolean("is_biometric_enabled", false))
    val isBiometricEnabled: StateFlow<Boolean> = _isBiometricEnabled.asStateFlow()

    // Initially, if app lock is enabled, we start in locked state (false). Otherwise, we start unlocked (true).
    private val _isAppUnlocked = MutableStateFlow(!appPrefs.getBoolean("is_lock_enabled", false))
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    fun unlockApp(enteredPin: String): Boolean {
        if (enteredPin == _appPasscode.value) {
            _isAppUnlocked.value = true
            return true
        }
        return false
    }

    fun unlockAppWithBiometric() {
        _isAppUnlocked.value = true
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        appPrefs.edit().putBoolean("is_biometric_enabled", enabled).apply()
        _isBiometricEnabled.value = enabled
    }

    fun lockApp() {
        if (_isAppLockEnabled.value) {
            _isAppUnlocked.value = false
        }
    }

    fun resetPasscodeViaSecurityAnswer(answer: String, newPin: String): Boolean {
        if (answer.trim().equals(_securityAnswer.value.trim(), ignoreCase = true)) {
            appPrefs.edit().apply {
                putString("app_passcode", newPin)
                apply()
            }
            _appPasscode.value = newPin
            _isAppUnlocked.value = true
            return true
        }
        return false
    }

    fun enableAppLock(pin: String, question: String, answer: String, enableBiometric: Boolean = _isBiometricEnabled.value) {
        appPrefs.edit().apply {
            putBoolean("is_lock_enabled", true)
            putBoolean("is_biometric_enabled", enableBiometric)
            putString("app_passcode", pin)
            putString("security_question", question)
            putString("security_answer", answer)
            apply()
        }
        _isAppLockEnabled.value = true
        _isBiometricEnabled.value = enableBiometric
        _appPasscode.value = pin
        _securityQuestion.value = question
        _securityAnswer.value = answer
        _isAppUnlocked.value = true // unlocked immediately when set up
    }

    fun disableAppLock() {
        appPrefs.edit().apply {
            putBoolean("is_lock_enabled", false)
            putBoolean("is_biometric_enabled", false)
            putString("app_passcode", "")
            putString("security_question", "")
            putString("security_answer", "")
            apply()
        }
        _isAppLockEnabled.value = false
        _isBiometricEnabled.value = false
        _appPasscode.value = ""
        _securityQuestion.value = ""
        _securityAnswer.value = ""
        _isAppUnlocked.value = true
    }

    // --- WASOOLI (COLLECTION DUE DATES) PER BOOK ---
    private val wasooliPrefs = getApplication<Application>().getSharedPreferences("wasooli_due_dates", android.content.Context.MODE_PRIVATE)

    private fun loadWasooliDueDates(): Map<Int, Long> {
        val result = mutableMapOf<Int, Long>()
        for ((key, value) in wasooliPrefs.all) {
            val bookId = key.removePrefix("book_").toIntOrNull()
            val timestamp = (value as? Long)
            if (bookId != null && timestamp != null && timestamp > 0L) {
                result[bookId] = timestamp
            }
        }
        return result
    }

    private val _wasooliDueDates = MutableStateFlow(loadWasooliDueDates())
    val wasooliDueDates: StateFlow<Map<Int, Long>> = _wasooliDueDates.asStateFlow()

    fun setWasooliDueDate(bookId: Int, dueDateMillis: Long?) {
        val editor = wasooliPrefs.edit()
        if (dueDateMillis == null || dueDateMillis <= 0L) {
            editor.remove("book_$bookId")
            _wasooliDueDates.value = _wasooliDueDates.value - bookId
            WasooliNotificationHelper.cancelWasooliReminder(getApplication(), bookId)
        } else {
            editor.putLong("book_$bookId", dueDateMillis)
            _wasooliDueDates.value = _wasooliDueDates.value + (bookId to dueDateMillis)
            val bookName = allBooks.value.find { it.id == bookId }?.name
                ?: _activeBook.value?.takeIf { it.id == bookId }?.name
                ?: "Account"
            val bookTxs = allTransactions.value.filter { it.bookId == bookId }
                .ifEmpty { activeBookTransactions.value.filter { it.bookId == bookId } }
            val netBalance = bookTxs.filter { it.type == "IN" }.sumOf { it.amount } -
                bookTxs.filter { it.type == "OUT" }.sumOf { it.amount }
            WasooliNotificationHelper.scheduleWasooliReminder(
                context = getApplication(),
                bookId = bookId,
                bookName = bookName,
                netBalance = netBalance,
                dueMillis = dueDateMillis
            )
        }
        editor.apply()
    }

    fun sendTestWasooliNotification(bookId: Int, bookName: String, netBalance: Double, dueMillis: Long?) {
        WasooliNotificationHelper.showWasooliNotification(
            context = getApplication(),
            bookId = bookId,
            bookName = bookName,
            netBalance = netBalance,
            dueMillis = dueMillis,
            isTest = true
        )
    }

    // --- TRASH / RECYCLE BIN (RESTORE OR PERMANENTLY DELETE) ---

    private fun currentActorDisplayName(): String {
        val syncName = syncManager.getName().trim()
        if (syncName.isNotBlank() && !syncName.equals("Offline User", true) && !syncName.equals("CashBook User", true)) {
            return syncName
        }
        val email = syncManager.getEmail().trim()
        return if (email.contains("@")) email.substringBefore("@") else "Owner"
    }

    private fun currentScopeEmail(): String =
        syncManager.getEmail().trim().lowercase()

    private fun txToJson(tx: Transaction): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("bookId", tx.bookId)
            put("amount", tx.amount)
            put("type", tx.type)
            put("category", tx.category)
            put("paymentMethod", tx.paymentMethod)
            put("remarks", tx.remarks)
            put("timestamp", tx.timestamp)
            put("receiptUri", tx.receiptUri ?: "")
            put("addedByName", tx.addedByName)
            put("addedByEmail", tx.addedByEmail)
        }

    private fun jsonToTx(obj: org.json.JSONObject, targetBookId: Int): Transaction =
        Transaction(
            bookId = targetBookId,
            amount = obj.optDouble("amount", 0.0),
            type = obj.optString("type", "OUT"),
            category = obj.optString("category", "General"),
            paymentMethod = obj.optString("paymentMethod", "Cash"),
            remarks = obj.optString("remarks", ""),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            isSynced = false,
            receiptUri = obj.optString("receiptUri", "").ifBlank { null },
            addedByName = obj.optString("addedByName", ""),
            addedByEmail = obj.optString("addedByEmail", "")
        )

    private suspend fun saveTransactionToTrash(tx: Transaction) {
        val dao = database.ledgerDao()
        val book = dao.getAllBooksList().find { it.id == tx.bookId }
        val biz = if (book != null) dao.getAllBusinessesList().find { it.id == book.businessId } else null
        val bizName = biz?.name ?: _activeBusiness.value?.name ?: ""
        val bookName = book?.name ?: _activeBook.value?.name ?: "Cashbook"
        val txTitle = tx.remarks.ifBlank { tx.category.ifBlank { if (tx.type == "IN") "Cash In" else "Cash Out" } }
        val subtitle = listOfNotNull(
            bizName.takeIf { it.isNotBlank() },
            bookName.takeIf { it.isNotBlank() },
            tx.paymentMethod.takeIf { it.isNotBlank() }
        ).joinToString(" • ")

        val payload = org.json.JSONObject().apply {
            put("transaction", txToJson(tx))
            put("bookName", bookName)
            put("bookPhone", book?.phone ?: "")
            put("businessId", biz?.id ?: book?.businessId ?: 0)
            put("businessName", bizName)
            put("businessUserEmail", biz?.userEmail ?: currentScopeEmail())
        }.toString()

        repository.insertTrashItem(
            TrashItem(
                itemType = "ENTRY",
                title = txTitle,
                subtitle = subtitle,
                businessName = bizName,
                bookName = bookName,
                userEmail = currentScopeEmail(),
                amount = tx.amount,
                entryType = tx.type,
                childCount = 0,
                deletedByName = currentActorDisplayName(),
                deletedAt = System.currentTimeMillis(),
                payloadJson = payload
            )
        )
    }

    private suspend fun saveBookToTrash(book: Book) {
        val dao = database.ledgerDao()
        val biz = dao.getAllBusinessesList().find { it.id == book.businessId }
        val txs = dao.getAllTransactionsList().filter { it.bookId == book.id }
        val bizName = biz?.name ?: _activeBusiness.value?.name ?: ""
        val net = txs.filter { it.type == "IN" }.sumOf { it.amount } - txs.filter { it.type == "OUT" }.sumOf { it.amount }
        val txArray = org.json.JSONArray().apply {
            txs.forEach { put(txToJson(it)) }
        }
        val payload = org.json.JSONObject().apply {
            put("bookId", book.id)
            put("bookName", book.name)
            put("bookPhone", book.phone)
            put("bookCreatedAt", book.createdAt)
            put("businessId", book.businessId)
            put("businessName", bizName)
            put("businessUserEmail", biz?.userEmail ?: currentScopeEmail())
            put("transactions", txArray)
        }.toString()

        val entryWord = if (txs.size == 1) "1 entry" else "${txs.size} entries"
        repository.insertTrashItem(
            TrashItem(
                itemType = "BOOK",
                title = book.name,
                subtitle = "${if (bizName.isNotBlank()) "$bizName • " else ""}$entryWord • Net Rs. ${String.format("%,.0f", net)}",
                businessName = bizName,
                bookName = book.name,
                userEmail = currentScopeEmail(),
                amount = net,
                entryType = "",
                childCount = txs.size,
                deletedByName = currentActorDisplayName(),
                deletedAt = System.currentTimeMillis(),
                payloadJson = payload
            )
        )
    }

    private suspend fun saveBusinessToTrash(business: Business) {
        val dao = database.ledgerDao()
        val bizBooks = dao.getAllBooksList().filter { it.businessId == business.id }
        val allTxList = dao.getAllTransactionsList()
        val bizMembers = dao.getAllTeamMembersList().filter { it.businessId == business.id }
        var totalEntries = 0
        var totalIn = 0.0
        var totalOut = 0.0

        val booksArray = org.json.JSONArray()
        bizBooks.forEach { bk ->
            val bkTxs = allTxList.filter { it.bookId == bk.id }
            totalEntries += bkTxs.size
            totalIn += bkTxs.filter { it.type == "IN" }.sumOf { it.amount }
            totalOut += bkTxs.filter { it.type == "OUT" }.sumOf { it.amount }
            val txArr = org.json.JSONArray().apply {
                bkTxs.forEach { put(txToJson(it)) }
            }
            booksArray.put(
                org.json.JSONObject().apply {
                    put("name", bk.name)
                    put("phone", bk.phone)
                    put("createdAt", bk.createdAt)
                    put("transactions", txArr)
                }
            )
        }

        val teamArray = org.json.JSONArray().apply {
            bizMembers.forEach { m ->
                put(
                    org.json.JSONObject().apply {
                        put("name", m.name)
                        put("email", m.email)
                        put("phone", m.phone)
                        put("role", m.role)
                        put("createdAt", m.createdAt)
                        put("allowedBookNames", m.allowedBookNames)
                        put("hideBalance", m.hideBalance)
                    }
                )
            }
        }

        val payload = org.json.JSONObject().apply {
            put("businessName", business.name)
            put("userEmail", business.userEmail)
            put("createdAt", business.createdAt)
            put("books", booksArray)
            put("teamMembers", teamArray)
        }.toString()

        val bookWord = if (bizBooks.size == 1) "1 book" else "${bizBooks.size} books"
        val entryWord = if (totalEntries == 1) "1 entry" else "$totalEntries entries"
        val net = totalIn - totalOut
        repository.insertTrashItem(
            TrashItem(
                itemType = "BUSINESS",
                title = business.name,
                subtitle = "$bookWord • $entryWord • Net Rs. ${String.format("%,.0f", net)}",
                businessName = business.name,
                bookName = "",
                userEmail = currentScopeEmail(),
                amount = net,
                entryType = "",
                childCount = totalEntries,
                deletedByName = currentActorDisplayName(),
                deletedAt = System.currentTimeMillis(),
                payloadJson = payload
            )
        )
    }

    private suspend fun savePartyToTrash(party: Party) {
        val dao = database.ledgerDao()
        val pTxs = dao.getAllPartyTransactionsList().filter { it.partyId == party.id }
        val arr = org.json.JSONArray().apply {
            pTxs.forEach { ptx ->
                put(
                    org.json.JSONObject().apply {
                        put("amount", ptx.amount)
                        put("type", ptx.type)
                        put("remarks", ptx.remarks)
                        put("timestamp", ptx.timestamp)
                    }
                )
            }
        }
        val payload = org.json.JSONObject().apply {
            put("name", party.name)
            put("phone", party.phone)
            put("createdAt", party.createdAt)
            put("transactions", arr)
        }.toString()

        val entryWord = if (pTxs.size == 1) "1 entry" else "${pTxs.size} entries"
        repository.insertTrashItem(
            TrashItem(
                itemType = "PARTY",
                title = party.name,
                subtitle = "Customer Khata • $entryWord",
                businessName = "Customer Khata",
                bookName = party.name,
                userEmail = currentScopeEmail(),
                amount = 0.0,
                entryType = "",
                childCount = pTxs.size,
                deletedByName = currentActorDisplayName(),
                deletedAt = System.currentTimeMillis(),
                payloadJson = payload
            )
        )
    }

    private suspend fun savePartyTransactionToTrash(pTx: PartyTransaction) {
        val dao = database.ledgerDao()
        val party = dao.getAllPartiesList().find { it.id == pTx.partyId }
        val partyName = party?.name ?: "Customer"
        val payload = org.json.JSONObject().apply {
            put("partyId", pTx.partyId)
            put("partyName", partyName)
            put("partyPhone", party?.phone ?: "")
            put("amount", pTx.amount)
            put("type", pTx.type)
            put("remarks", pTx.remarks)
            put("timestamp", pTx.timestamp)
        }.toString()

        repository.insertTrashItem(
            TrashItem(
                itemType = "PARTY_ENTRY",
                title = pTx.remarks.ifBlank { if (pTx.type == "GAVE") "You Gave" else "You Got" },
                subtitle = "Customer Khata • $partyName",
                businessName = "Customer Khata",
                bookName = partyName,
                userEmail = currentScopeEmail(),
                amount = pTx.amount,
                entryType = pTx.type,
                childCount = 0,
                deletedByName = currentActorDisplayName(),
                deletedAt = System.currentTimeMillis(),
                payloadJson = payload
            )
        )
    }

    fun restoreTrashItem(item: TrashItem, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val dao = database.ledgerDao()
                val obj = org.json.JSONObject(item.payloadJson)
                syncManager.markLocalDbModified()

                val message = when (item.itemType) {
                    "ENTRY" -> {
                        val txObj = obj.getJSONObject("transaction")
                        val origBookId = txObj.optInt("bookId", 0)
                        val bookName = obj.optString("bookName", item.bookName).ifBlank { "Restored Book" }
                        val bookPhone = obj.optString("bookPhone", "")
                        val origBizId = obj.optInt("businessId", 0)
                        val bizName = obj.optString("businessName", item.businessName).ifBlank { "My Business" }
                        val bizEmail = obj.optString("businessUserEmail", currentScopeEmail())

                        val allBiz = dao.getAllBusinessesList()
                        val targetBiz = allBiz.find { it.id == origBizId }
                            ?: allBiz.find { it.name.equals(bizName, ignoreCase = true) }
                            ?: run {
                                syncManager.removeBusinessFromTombstones(bizName)
                                val newBizId = repository.insertBusiness(
                                    Business(name = bizName, userEmail = bizEmail, isSynced = false)
                                ).toInt()
                                Business(id = newBizId, name = bizName, userEmail = bizEmail, isSynced = false)
                            }

                        val allBk = dao.getAllBooksList()
                        val targetBook = allBk.find { it.id == origBookId }
                            ?: allBk.find { it.businessId == targetBiz.id && it.name.equals(bookName, ignoreCase = true) }
                            ?: run {
                                syncManager.removeBookFromTombstones(targetBiz.id, bookName)
                                val newBkId = repository.insertBook(
                                    Book(businessId = targetBiz.id, name = bookName, phone = bookPhone, isSynced = false)
                                ).toInt()
                                Book(id = newBkId, businessId = targetBiz.id, name = bookName, phone = bookPhone, isSynced = false)
                            }

                        val restoredTx = jsonToTx(txObj, targetBook.id)
                        syncManager.removeTransactionFromTombstones(restoredTx.timestamp)
                        val newTxId = repository.insertTransaction(restoredTx).toInt()
                        recordAuditLogForTransaction(
                            action = "RESTORED",
                            tx = restoredTx.copy(id = newTxId),
                            summary = "Restored ${if (restoredTx.type == "IN") "Cash In" else "Cash Out"} Rs. ${String.format("%,.0f", restoredTx.amount)} from Trash",
                            oldDetails = "Deleted by ${item.deletedByName.ifBlank { "User" }}",
                            newDetails = formatTxSnapshot(restoredTx)
                        )
                        "Restored entry to '${targetBook.name}'"
                    }

                    "BOOK" -> {
                        val bookName = obj.optString("bookName", item.title).ifBlank { "Restored Book" }
                        val bookPhone = obj.optString("bookPhone", "")
                        val bookCreatedAt = obj.optLong("bookCreatedAt", System.currentTimeMillis())
                        val origBizId = obj.optInt("businessId", 0)
                        val bizName = obj.optString("businessName", item.businessName).ifBlank { "My Business" }
                        val bizEmail = obj.optString("businessUserEmail", currentScopeEmail())

                        val allBiz = dao.getAllBusinessesList()
                        val targetBiz = allBiz.find { it.id == origBizId }
                            ?: allBiz.find { it.name.equals(bizName, ignoreCase = true) }
                            ?: run {
                                syncManager.removeBusinessFromTombstones(bizName)
                                val newBizId = repository.insertBusiness(
                                    Business(name = bizName, userEmail = bizEmail, isSynced = false)
                                ).toInt()
                                Business(id = newBizId, name = bizName, userEmail = bizEmail, isSynced = false)
                            }

                        syncManager.removeBookFromTombstones(targetBiz.id, bookName)
                        val existingBook = dao.getAllBooksList().find {
                            it.businessId == targetBiz.id && it.name.equals(bookName, ignoreCase = true)
                        }
                        val targetBookId = existingBook?.id ?: repository.insertBook(
                            Book(
                                businessId = targetBiz.id,
                                name = bookName,
                                phone = bookPhone,
                                createdAt = bookCreatedAt,
                                isSynced = false
                            )
                        ).toInt()

                        val txArr = obj.optJSONArray("transactions") ?: org.json.JSONArray()
                        for (i in 0 until txArr.length()) {
                            val tx = jsonToTx(txArr.getJSONObject(i), targetBookId)
                            syncManager.removeTransactionFromTombstones(tx.timestamp)
                            repository.insertTransaction(tx)
                        }
                        "Restored cashbook '$bookName' (${txArr.length()} entries)"
                    }

                    "BUSINESS" -> {
                        val bizName = obj.optString("businessName", item.title).ifBlank { "Restored Business" }
                        val bizEmail = obj.optString("userEmail", currentScopeEmail())
                        val bizCreatedAt = obj.optLong("createdAt", System.currentTimeMillis())
                        syncManager.removeBusinessFromTombstones(bizName)

                        val existingBiz = dao.getAllBusinessesList().find {
                            it.name.equals(bizName, ignoreCase = true)
                        }
                        val targetBizId = existingBiz?.id ?: repository.insertBusiness(
                            Business(
                                name = bizName,
                                userEmail = bizEmail,
                                createdAt = bizCreatedAt,
                                isSynced = false
                            )
                        ).toInt()

                        val booksArr = obj.optJSONArray("books") ?: org.json.JSONArray()
                        for (i in 0 until booksArr.length()) {
                            val bkObj = booksArr.getJSONObject(i)
                            val bkName = bkObj.optString("name", "Book").ifBlank { "Book" }
                            val bkPhone = bkObj.optString("phone", "")
                            val bkCreatedAt = bkObj.optLong("createdAt", System.currentTimeMillis())
                            syncManager.removeBookFromTombstones(targetBizId, bkName)

                            val newBkId = repository.insertBook(
                                Book(
                                    businessId = targetBizId,
                                    name = bkName,
                                    phone = bkPhone,
                                    createdAt = bkCreatedAt,
                                    isSynced = false
                                )
                            ).toInt()
                            val txArr = bkObj.optJSONArray("transactions") ?: org.json.JSONArray()
                            for (j in 0 until txArr.length()) {
                                val tx = jsonToTx(txArr.getJSONObject(j), newBkId)
                                syncManager.removeTransactionFromTombstones(tx.timestamp)
                                repository.insertTransaction(tx)
                            }
                        }

                        val teamArr = obj.optJSONArray("teamMembers") ?: org.json.JSONArray()
                        val existingMembers = dao.getAllTeamMembersList().filter { it.businessId == targetBizId }
                        for (i in 0 until teamArr.length()) {
                            val mObj = teamArr.getJSONObject(i)
                            val mEmail = mObj.optString("email", "")
                            if (existingMembers.none { it.email.equals(mEmail, ignoreCase = true) }) {
                                repository.insertTeamMember(
                                    TeamMember(
                                        businessId = targetBizId,
                                        name = mObj.optString("name", ""),
                                        email = mEmail,
                                        phone = mObj.optString("phone", ""),
                                        role = mObj.optString("role", "Partner"),
                                        createdAt = mObj.optLong("createdAt", System.currentTimeMillis()),
                                        allowedBookNames = mObj.optString("allowedBookNames", ""),
                                        hideBalance = mObj.optBoolean("hideBalance", false),
                                        lockPastEntries = if (mObj.has("lockPastEntries")) {
                                            mObj.optBoolean("lockPastEntries", false)
                                        } else {
                                            defaultLockPastEntriesForRole(mObj.optString("role", "Partner"))
                                        }
                                    )
                                )
                            }
                        }
                        "Restored business '$bizName'"
                    }

                    "PARTY" -> {
                        val pName = obj.optString("name", item.title).ifBlank { "Customer" }
                        val pPhone = obj.optString("phone", "")
                        val pCreated = obj.optLong("createdAt", System.currentTimeMillis())
                        syncManager.removePartyFromTombstones(pName)
                        val existingParty = dao.getAllPartiesList().find { it.name.equals(pName, ignoreCase = true) }
                        val targetPartyId = existingParty?.id ?: repository.insertParty(
                            Party(name = pName, phone = pPhone, createdAt = pCreated)
                        ).toInt()
                        val txArr = obj.optJSONArray("transactions") ?: org.json.JSONArray()
                        for (i in 0 until txArr.length()) {
                            val pObj = txArr.getJSONObject(i)
                            val ts = pObj.optLong("timestamp", System.currentTimeMillis())
                            syncManager.removePartyTransactionFromTombstones(ts)
                            repository.insertPartyTransaction(
                                PartyTransaction(
                                    partyId = targetPartyId,
                                    amount = pObj.optDouble("amount", 0.0),
                                    type = pObj.optString("type", "GAVE"),
                                    remarks = pObj.optString("remarks", ""),
                                    timestamp = ts,
                                    isSynced = false
                                )
                            )
                        }
                        "Restored customer '$pName'"
                    }

                    "PARTY_ENTRY" -> {
                        val origPartyId = obj.optInt("partyId", 0)
                        val pName = obj.optString("partyName", item.bookName).ifBlank { "Customer" }
                        val pPhone = obj.optString("partyPhone", "")
                        val targetParty = dao.getAllPartiesList().find { it.id == origPartyId }
                            ?: dao.getAllPartiesList().find { it.name.equals(pName, ignoreCase = true) }
                            ?: run {
                                syncManager.removePartyFromTombstones(pName)
                                val id = repository.insertParty(Party(name = pName, phone = pPhone)).toInt()
                                Party(id = id, name = pName, phone = pPhone)
                            }
                        val ts = obj.optLong("timestamp", System.currentTimeMillis())
                        syncManager.removePartyTransactionFromTombstones(ts)
                        repository.insertPartyTransaction(
                            PartyTransaction(
                                partyId = targetParty.id,
                                amount = obj.optDouble("amount", 0.0),
                                type = obj.optString("type", "GAVE"),
                                remarks = obj.optString("remarks", ""),
                                timestamp = ts,
                                isSynced = false
                            )
                        )
                        "Restored entry to '$pName'"
                    }

                    else -> "Restored item"
                }

                repository.deleteTrashItem(item)
                triggerCloudSync()
                onResult(message)
            } catch (e: Exception) {
                onResult("Could not restore: ${e.localizedMessage ?: "Invalid data"}")
            }
        }
    }

    fun permanentlyDeleteTrashItem(item: TrashItem) {
        viewModelScope.launch {
            repository.deleteTrashItem(item)
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val email = currentScopeEmail()
            if (isSuperAdmin.value || email.isBlank()) {
                repository.clearAllTrash()
            } else {
                repository.clearTrashForUser(email)
            }
        }
    }
}

data class ChatMessage(
    val sender: String, // "user" or "assistant"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
