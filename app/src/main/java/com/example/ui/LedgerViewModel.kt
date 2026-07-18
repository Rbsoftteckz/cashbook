package com.example.ui

import android.app.Application
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
    AI_ASSISTANT
}

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val database = LedgerDatabase.getDatabase(application)
    private val repository = LedgerRepository(database.ledgerDao())
    private val geminiService = GeminiService()

    // Navigation and screen state
    private val _currentScreen = MutableStateFlow(Screen.DASHBOARD)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // Book state
    val books: StateFlow<List<Book>> = repository.allBooks.stateIn(
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
        ChatMessage("assistant", "Hello! I'm your digital ledger assistant. Tell me something like: 'Got 1500 for web design work online' or 'Paid 300 cash for gas' and I will structure it for you! You can also ask me about your expenses.")
    ))
    val chatHistory: StateFlow<List<ChatMessage>> = _chatHistory.asStateFlow()

    init {
        // Initialize with default book if empty
        viewModelScope.launch {
            books.collect { list ->
                if (list.isEmpty() && _activeBook.value == null) {
                    val defaultBookId = repository.insertBook(Book(name = "General Book"))
                    _activeBook.value = Book(id = defaultBookId.toInt(), name = "General Book")
                } else if (list.isNotEmpty() && _activeBook.value == null) {
                    _activeBook.value = list.first()
                }
            }
        }
    }

    // --- Action Methods ---

    fun setScreen(screen: Screen) {
        _currentScreen.value = screen
    }

    fun selectBook(book: Book) {
        _activeBook.value = book
    }

    fun createBook(name: String) {
        viewModelScope.launch {
            val id = repository.insertBook(Book(name = name))
            val newBook = Book(id = id.toInt(), name = name)
            _activeBook.value = newBook
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            repository.deleteBook(book)
            if (_activeBook.value?.id == book.id) {
                _activeBook.value = books.value.firstOrNull { it.id != book.id }
            }
        }
    }

    fun addTransaction(amount: Double, type: String, category: String, paymentMethod: String, remarks: String) {
        val bookId = _activeBook.value?.id ?: return
        viewModelScope.launch {
            repository.insertTransaction(
                Transaction(
                    bookId = bookId,
                    amount = amount,
                    type = type,
                    category = category,
                    paymentMethod = paymentMethod,
                    remarks = remarks
                )
            )
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            repository.deleteTransaction(transaction)
        }
    }

    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch {
            repository.updateTransaction(transaction)
        }
    }

    // Filters
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCategoryFilter(category: String) {
        _selectedCategory.value = category
    }

    fun setPaymentMethodFilter(method: String) {
        _selectedPaymentMethod.value = method
    }

    // Party / Udhar Actions
    fun addParty(name: String, phone: String) {
        viewModelScope.launch {
            repository.insertParty(Party(name = name, phone = phone))
        }
    }

    fun deleteParty(party: Party) {
        viewModelScope.launch {
            repository.deleteParty(party)
            if (_activeParty.value?.id == party.id) {
                _activeParty.value = null
            }
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
                    remarks = remarks
                )
            )
        }
    }

    fun deletePartyTransaction(partyTransaction: PartyTransaction) {
        viewModelScope.launch {
            repository.deletePartyTransaction(partyTransaction)
        }
    }

    // AI smart actions

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
        _chatHistory.value = _chatHistory.value + ChatMessage("assistant", "✅ Transaction saved: ${draft.type} ₹${draft.amount} [${draft.category}] for ${draft.remarks} via ${draft.paymentMethod}")
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
                    _chatHistory.value = _chatHistory.value + ChatMessage("assistant", "I parsed your entry! Would you like to confirm and save this ledger entry?")
                } else {
                    // It's a general question or search, compile a summary of books to feed context to Gemini
                    val contextPrompt = buildAIContextPrompt(message)
                    val reply = geminiService.askFinancialAssistant(contextPrompt)
                    _aiLoading.value = false
                    _chatHistory.value = _chatHistory.value + ChatMessage("assistant", reply)
                }
            } catch (e: Exception) {
                _aiLoading.value = false
                _aiError.value = e.message
                _chatHistory.value = _chatHistory.value + ChatMessage("assistant", "Oops, I encountered an error while analyzing that: ${e.message}")
            }
        }
    }

    private fun buildAIContextPrompt(userQuestion: String): String {
        val totalIn = activeBookTransactions.value.filter { it.type == "IN" }.sumOf { it.amount }
        val totalOut = activeBookTransactions.value.filter { it.type == "OUT" }.sumOf { it.amount }
        val balance = totalIn - totalOut

        val txHistory = activeBookTransactions.value.take(20).joinToString("\n") { tx ->
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(tx.timestamp))
            "- $dateStr: ${tx.type} ₹${tx.amount} (Category: ${tx.category}, Method: ${tx.paymentMethod}, Remarks: ${tx.remarks})"
        }

        return """
            You are "LedgerMate", an interactive financial analysis and accounting expert assistant for the CashBook mobile app.
            
            Here is the current user's ledger summary:
            - Active Book: "${_activeBook.value?.name ?: "Default Book"}"
            - Total Money In: ₹$totalIn
            - Total Money Out: ₹$totalOut
            - Net Cash Balance: ₹$balance
            
            Recent Transactions (up to 20):
            $txHistory
            
            User's question/input: "$userQuestion"
            
            Please provide a helpful, concise, and professional financial response. If they asked for advice, analyze their expenses and point out areas they can optimize. Keep it friendly and clear. Use Rupee symbol (₹) for values.
        """.trimIndent()
    }

    // Report / Export utilities
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
}

data class ChatMessage(
    val sender: String, // "user" or "assistant"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
