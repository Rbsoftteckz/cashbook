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

    // Simulated Active Role (for granular testing of team permissions)
    private val _simulatedRole = MutableStateFlow("Boss") // "Boss", "Admin", "Partner", "Data Entry"
    val simulatedRole: StateFlow<String> = _simulatedRole.asStateFlow()

    // Multi-Business states
    val businesses: StateFlow<List<Business>> = repository.allBusinesses.stateIn(
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

        // Automatic Google Drive Sync on app launch
        triggerCloudSync()

        // Observe network state to trigger auto-sync when back online
        try {
            val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager != null) {
                val networkRequest = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.d("LedgerViewModel", "Network available - triggering auto-sync")
                        triggerCloudSync()
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("LedgerViewModel", "Failed to register network callback", e)
        }
    }

    // --- Core Cloud Sync Mechanism ---

    fun triggerCloudSync() {
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
                _syncStatus.value = message

                // After sync, ensure active business & active book are selected from restored list if needed
                val currentBizList = repository.allBusinesses.first()
                if (currentBizList.isNotEmpty()) {
                    if (_activeBusiness.value == null || _activeBusiness.value?.name == "My Business") {
                        // Prefer non-default business if available
                        val restoredBiz = currentBizList.find { it.name != "My Business" } ?: currentBizList.first()
                        _activeBusiness.value = restoredBiz
                    }
                    val activeBizId = _activeBusiness.value?.id ?: 1
                    val currentBooksList = repository.getBooksForBusiness(activeBizId).first()
                    if (currentBooksList.isNotEmpty()) {
                        if (_activeBook.value == null || !currentBooksList.any { it.id == _activeBook.value?.id }) {
                            _activeBook.value = currentBooksList.first()
                        }
                    }
                }
            } catch (e: Exception) {
                _syncStatus.value = "Sync Interrupted: ${e.message}"
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
        viewModelScope.launch {
            val id = repository.insertBusiness(Business(name = name))
            val newBiz = Business(id = id.toInt(), name = name)
            _activeBusiness.value = newBiz
            val defaultBookId = repository.insertBook(Book(businessId = newBiz.id, name = "Daily Cashbook"))
            _activeBook.value = Book(id = defaultBookId.toInt(), businessId = newBiz.id, name = "Daily Cashbook")
            triggerCloudSync()
        }
    }

    fun updateBusiness(business: Business) {
        viewModelScope.launch {
            repository.updateBusiness(business)
            if (_activeBusiness.value?.id == business.id) {
                _activeBusiness.value = business
            }
            triggerCloudSync()
        }
    }

    fun createBusinessAndBook(businessName: String, bookName: String) {
        viewModelScope.launch {
            val id = repository.insertBusiness(Business(name = businessName))
            val newBiz = Business(id = id.toInt(), name = businessName)
            _activeBusiness.value = newBiz
            val bookId = repository.insertBook(Book(businessId = newBiz.id, name = bookName))
            _activeBook.value = Book(id = bookId.toInt(), businessId = newBiz.id, name = bookName)
            triggerCloudSync()
        }
    }

    fun deleteBusiness(business: Business) {
        viewModelScope.launch {
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

    fun createBook(name: String) {
        val bizId = _activeBusiness.value?.id ?: 1
        viewModelScope.launch {
            val id = repository.insertBook(Book(businessId = bizId, name = name))
            val newBook = Book(id = id.toInt(), businessId = bizId, name = name)
            _activeBook.value = newBook
            triggerCloudSync()
        }
    }

    fun updateBook(book: Book) {
        viewModelScope.launch {
            repository.updateBook(book)
            if (_activeBook.value?.id == book.id) {
                _activeBook.value = book
            }
            triggerCloudSync()
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            repository.deleteBook(book)
            if (_activeBook.value?.id == book.id) {
                _activeBook.value = books.value.firstOrNull { it.id != book.id }
            }
            triggerCloudSync()
        }
    }

    // --- Super Admin State & Actions ---
    val isSuperAdmin = MutableStateFlow(syncManager.isSuperAdminLoggedIn())

    fun loginSuperAdmin(user: String, pass: String): Boolean {
        val success = syncManager.loginSuperAdmin(user, pass)
        if (success) {
            isSuperAdmin.value = true
            _simulatedRole.value = "Super Admin"
        }
        return success
    }

    fun logoutSuperAdmin() {
        syncManager.logoutSuperAdmin()
        isSuperAdmin.value = false
        _simulatedRole.value = "Owner"
    }

    // --- Transactions Actions ---

    fun addTransaction(amount: Double, type: String, category: String, paymentMethod: String, remarks: String, receiptUri: String? = null) {
        val bookId = _activeBook.value?.id ?: return
        viewModelScope.launch {
            repository.insertTransaction(
                Transaction(
                    bookId = bookId,
                    amount = amount,
                    type = type,
                    category = category,
                    paymentMethod = paymentMethod,
                    remarks = remarks,
                    isSynced = syncManager.isUserSignedIn(),
                    receiptUri = receiptUri
                )
            )
            triggerCloudSync()
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            repository.deleteTransaction(transaction)
            triggerCloudSync()
        }
    }

    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch {
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
                    isSynced = syncManager.isUserSignedIn()
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
