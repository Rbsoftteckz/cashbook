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
    PROFILE
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

    // Simulated Active Role (for granular testing of team permissions)
    private val _simulatedRole = MutableStateFlow("Boss") // "Boss", "Admin", "Partner", "Data Entry"
    val simulatedRole: StateFlow<String> = _simulatedRole.asStateFlow()

    // Multi-Business states
    val businesses: StateFlow<List<Business>> = repository.allBusinesses
        .map { list ->
            val activeEmail = syncManager.getEmail().trim().lowercase()
            if (activeEmail.isBlank() || isSuperAdmin.value) {
                list
            } else {
                list.filter { biz ->
                    biz.userEmail.isBlank() || biz.userEmail.trim().lowercase() == activeEmail
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _activeBusiness = MutableStateFlow<Business?>(null)
    val activeBusiness: StateFlow<Business?> = _activeBusiness.asStateFlow()

    // Books automatically retrieved for the active business
    val books: StateFlow<List<Book>> = _activeBusiness
        .flatMapLatest { biz ->
            if (biz != null) {
                repository.getBooksForBusiness(biz.id)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
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

    // Exposure of total transaction and team streams
    val allTransactions: StateFlow<List<Transaction>> = repository.allTransactions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allTeamMembers: StateFlow<List<TeamMember>> = repository.allTeamMembers.stateIn(
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
        // Only auto-select first business if list is not empty
        viewModelScope.launch {
            businesses.collect { list ->
                if (list.isNotEmpty() && _activeBusiness.value == null) {
                    _activeBusiness.value = list.first()
                }
            }
        }

        // Only auto-select first book if list is not empty
        viewModelScope.launch {
            books.collect { list ->
                if (list.isNotEmpty() && _activeBook.value == null) {
                    _activeBook.value = list.first()
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

        // Real-time periodic cloud polling (every 10 seconds) so team members and transactions auto-update live silently
        viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(10000L)
                if (checkIsOnline() && syncManager.isUserSignedIn() && !_isSyncing.value) {
                    try {
                        syncManager.syncWithCloud(database.ledgerDao())
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
        if (_isSyncing.value) return

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

                // After sync, preserve current active business & active book if valid
                val currentBizList = repository.allBusinesses.first()
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
                    val currentBooksList = repository.getBooksForBusiness(activeBizId).first()
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
                    }
                } else if (syncManager.isUserSignedIn()) {
                    val email = syncManager.getEmail()
                    val globalAcc = syncManager.getGlobalAccounts().find { it.email.equals(email, ignoreCase = true) }
                    val rawName = globalAcc?.name?.trim()?.ifBlank { null } ?: syncManager.getName().trim().ifBlank { null }
                    val bizName = if (!rawName.isNullOrBlank() && !rawName.contains("@")) "${rawName}'s Business" else "Main Business"
                    createBusinessAndBook(bizName, "Main CashBook")
                }
            } catch (e: Exception) {
                _syncStatus.value = "Sync Interrupted: ${e.message}"
            } finally {
                _isSyncing.value = false
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

    fun createBusiness(name: String) {
        val userEmail = syncManager.getEmail().trim().lowercase()
        viewModelScope.launch {
            syncManager.markLocalDbModified()
            val id = repository.insertBusiness(Business(name = name, userEmail = userEmail, isSynced = false))
            val newBiz = Business(id = id.toInt(), name = name, userEmail = userEmail, isSynced = false)
            _activeBusiness.value = newBiz
            val bookId = repository.insertBook(Book(businessId = newBiz.id, name = "Main CashBook", isSynced = false))
            _activeBook.value = Book(id = bookId.toInt(), businessId = newBiz.id, name = "Main CashBook", isSynced = false)
            triggerCloudSync()
        }
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

    fun createBusinessAndBook(businessName: String, bookName: String = "") {
        val userEmail = syncManager.getEmail().trim().lowercase()
        viewModelScope.launch {
            syncManager.markLocalDbModified()
            val id = repository.insertBusiness(Business(name = businessName, userEmail = userEmail, isSynced = false))
            val newBiz = Business(id = id.toInt(), name = businessName, userEmail = userEmail, isSynced = false)
            _activeBusiness.value = newBiz
            if (bookName.isNotBlank()) {
                val bookId = repository.insertBook(Book(businessId = newBiz.id, name = bookName, isSynced = false))
                _activeBook.value = Book(id = bookId.toInt(), businessId = newBiz.id, name = bookName, isSynced = false)
            } else {
                _activeBook.value = null
            }
            triggerCloudSync()
        }
    }

    fun deleteBusiness(business: Business) {
        viewModelScope.launch {
            syncManager.markLocalDbModified()
            repository.deleteBusiness(business)
            if (_activeBusiness.value?.id == business.id) {
                _activeBusiness.value = businesses.value.firstOrNull { it.id != business.id }
                _activeBook.value = null
            }
            triggerCloudSync()
        }
    }

    fun setScreen(screen: Screen) {
        _currentScreen.value = screen
    }

    fun selectBook(book: Book) {
        _activeBook.value = book
    }

    fun createBook(name: String, phone: String = "") {
        val bizId = _activeBusiness.value?.id ?: 1
        viewModelScope.launch {
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
            syncManager.markLocalDbModified()
            repository.deleteBook(book)
            if (_activeBook.value?.id == book.id) {
                _activeBook.value = books.value.firstOrNull { it.id != book.id }
            }
            triggerCloudSync()
        }
    }

    // --- Super Admin & User Auth State & Actions ---
    val isSuperAdmin = MutableStateFlow(syncManager.isSuperAdminLoggedIn())
    val isUserSignedIn = MutableStateFlow(syncManager.isUserSignedIn())

    fun updateAuthState() {
        isUserSignedIn.value = syncManager.isUserSignedIn()
        isSuperAdmin.value = syncManager.isSuperAdminLoggedIn()
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

    fun logoutSuperAdmin() {
        syncManager.logoutSuperAdmin()
        updateAuthState()
        _simulatedRole.value = "Boss"
    }

    fun logoutUser() {
        syncManager.logoutUser()
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

    // --- Transactions Actions ---

    fun addTransaction(amount: Double, type: String, category: String, paymentMethod: String, remarks: String, receiptUri: String? = null, timestamp: Long = System.currentTimeMillis()) {
        val bookId = _activeBook.value?.id ?: return
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
                    receiptUri = receiptUri
                )
            )
            triggerCloudSync()
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            syncManager.markLocalDbModified()
            repository.deleteTransaction(transaction)
            triggerCloudSync()
        }
    }

    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch {
            syncManager.markLocalDbModified()
            repository.updateTransaction(transaction)
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

    fun batchDeleteSelectedTransactions() {
        viewModelScope.launch {
            syncManager.markLocalDbModified()
            val idsToDelete = _selectedTransactionIds.value
            val txsToDelete = activeBookTransactions.value.filter { idsToDelete.contains(it.id) }
            txsToDelete.forEach { tx ->
                repository.deleteTransaction(tx)
            }
            clearTransactionSelection()
            triggerCloudSync()
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
            repository.insertParty(Party(name = name, phone = phone))
            triggerCloudSync()
        }
    }

    fun deleteParty(party: Party) {
        viewModelScope.launch {
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
            repository.deletePartyTransaction(partyTransaction)
            triggerCloudSync()
        }
    }

    // --- Digital Team Management Actions ---

    fun addTeamMember(name: String, email: String, phone: String, role: String) {
        val bizId = _activeBusiness.value?.id ?: 1
        viewModelScope.launch {
            repository.insertTeamMember(
                TeamMember(
                    businessId = bizId,
                    name = name,
                    email = email,
                    phone = phone,
                    role = role
                )
            )
            triggerCloudSync()
        }
    }

    fun deleteTeamMember(teamMember: TeamMember) {
        viewModelScope.launch {
            repository.deleteTeamMember(teamMember)
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
        val sb = StringBuilder()
        sb.append("Date,Type,Amount,Category,Payment Method,Remarks\n")
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (tx in activeBookTransactions.value) {
            val dateStr = df.format(Date(tx.timestamp))
            val escapedRemarks = tx.remarks.replace("\"", "\"\"")
            sb.append("$dateStr,${tx.type},${tx.amount},${tx.category},${tx.paymentMethod},\"$escapedRemarks\"\n")
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

    fun enableAppLock(pin: String, question: String, answer: String) {
        appPrefs.edit().apply {
            putBoolean("is_lock_enabled", true)
            putString("app_passcode", pin)
            putString("security_question", question)
            putString("security_answer", answer)
            apply()
        }
        _isAppLockEnabled.value = true
        _appPasscode.value = pin
        _securityQuestion.value = question
        _securityAnswer.value = answer
        _isAppUnlocked.value = true // unlocked immediately when set up
    }

    fun disableAppLock() {
        appPrefs.edit().apply {
            putBoolean("is_lock_enabled", false)
            putString("app_passcode", "")
            putString("security_question", "")
            putString("security_answer", "")
            apply()
        }
        _isAppLockEnabled.value = false
        _appPasscode.value = ""
        _securityQuestion.value = ""
        _securityAnswer.value = ""
        _isAppUnlocked.value = true
    }
}

data class ChatMessage(
    val sender: String, // "user" or "assistant"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
