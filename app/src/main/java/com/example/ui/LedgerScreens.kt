package com.example.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Book
import com.example.data.Party
import com.example.data.PartyTransaction
import com.example.data.Transaction
import com.example.ui.theme.GreenIn
import com.example.ui.theme.RedOut
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerAppScreen(viewModel: LedgerViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    
    var showBookSelector by remember { mutableStateOf(false) }
    var showAddBookDialog by remember { mutableStateOf(false) }
    var newBookName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        modifier = Modifier
                            .clickable { showBookSelector = true }
                            .padding(8.dp)
                            .testTag("book_selector_trigger"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Book,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = activeBook?.name ?: "Loading Book...",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Switch book",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(
                        onClick = { showAddBookDialog = true },
                        modifier = Modifier.testTag("add_book_button")
                    ) {
                        Icon(Icons.Default.AddCard, contentDescription = "Create New Book")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                NavigationBarItem(
                    selected = currentScreen == Screen.DASHBOARD,
                    onClick = { viewModel.setScreen(Screen.DASHBOARD) },
                    icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                    label = { Text("Cashbook") },
                    modifier = Modifier.testTag("nav_cashbook")
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.UDHAR,
                    onClick = { viewModel.setScreen(Screen.UDHAR) },
                    icon = { Icon(Icons.Default.People, contentDescription = null) },
                    label = { Text("Udhar Book") },
                    modifier = Modifier.testTag("nav_udhar")
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.REPORTS,
                    onClick = { viewModel.setScreen(Screen.REPORTS) },
                    icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                    label = { Text("Reports") },
                    modifier = Modifier.testTag("nav_reports")
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.AI_ASSISTANT,
                    onClick = { viewModel.setScreen(Screen.AI_ASSISTANT) },
                    icon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                    label = { Text("LedgerMate AI") },
                    modifier = Modifier.testTag("nav_ai")
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    fadeIn(animationSpec = spring()) togetherWith fadeOut(animationSpec = spring())
                },
                label = "ScreenTransition"
            ) { screen ->
                when (screen) {
                    Screen.DASHBOARD -> DashboardScreen(viewModel)
                    Screen.UDHAR -> UdharScreen(viewModel)
                    Screen.REPORTS -> ReportsScreen(viewModel)
                    Screen.AI_ASSISTANT -> AIAssistantScreen(viewModel)
                }
            }

            // Book Selector Modal Bottom Sheet or Dialog
            if (showBookSelector) {
                AlertDialog(
                    onDismissRequest = { showBookSelector = false },
                    title = { Text("My Business Books", fontWeight = FontWeight.Bold) },
                    text = {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(books) { book ->
                                val isSelected = book.id == activeBook?.id
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.selectBook(book)
                                            showBookSelector = false
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(book.name, fontWeight = FontWeight.Bold)
                                            Text(
                                                "Created: " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(book.createdAt)),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary)
                                        } else if (books.size > 1) {
                                            IconButton(onClick = { viewModel.deleteBook(book) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete book", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showBookSelector = false }) {
                            Text("Close")
                        }
                    }
                )
            }

            // Create Book Dialog
            if (showAddBookDialog) {
                AlertDialog(
                    onDismissRequest = { showAddBookDialog = false },
                    title = { Text("Create New Book") },
                    text = {
                        OutlinedTextField(
                            value = newBookName,
                            onValueChange = { newBookName = it },
                            label = { Text("Book Name") },
                            placeholder = { Text("e.g., Shop Ledger, Personal Expenses") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("new_book_name_field")
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (newBookName.isNotBlank()) {
                                    viewModel.createBook(newBookName)
                                    newBookName = ""
                                    showAddBookDialog = false
                                }
                            },
                            modifier = Modifier.testTag("save_book_button")
                        ) {
                            Text("Create")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddBookDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

// --- SCREEN 1: DASHBOARD ---

@Composable
fun DashboardScreen(viewModel: LedgerViewModel) {
    val activeBookTransactions by viewModel.activeBookTransactions.collectAsStateWithLifecycle()
    val filteredTransactions by viewModel.filteredTransactions.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedPaymentMethod by viewModel.selectedPaymentMethod.collectAsStateWithLifecycle()

    var showTransactionDialog by remember { mutableStateOf<String?>(null) } // "IN", "OUT", or null
    var selectedTxForEdit by remember { mutableStateOf<Transaction?>(null) }

    // Aggregate values
    val totalIn = activeBookTransactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = activeBookTransactions.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut

    val inCategories = listOf("Sales", "Salary", "Interest", "Commission", "Rent Received", "Other")
    val outCategories = listOf("Food", "Rent", "Salary Paid", "Office Supplies", "Travel", "Utilities", "Purchases", "Other")
    val paymentMethods = listOf("Cash", "Online", "Bank")

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Summary Cards
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "Net Balance (Cash-on-Hand)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "₹${String.format("%,.2f", netBalance)}",
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                        color = if (netBalance >= 0) GreenIn else RedOut,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(GreenIn)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("TOTAL IN", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "₹${String.format("%,.2f", totalIn)}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = GreenIn
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(RedOut)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("TOTAL OUT", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "₹${String.format("%,.2f", totalOut)}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = RedOut
                            )
                        }
                    }
                }
            }

            // Filters & Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search transactions...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("search_field"),
                shape = RoundedCornerShape(12.dp)
            )

            // Category & Payment Chips Horizontal scroll row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category filters
                FilterChip(
                    selected = selectedCategory == "All",
                    onClick = { viewModel.setCategoryFilter("All") },
                    label = { Text("All Categories") }
                )
                (inCategories + outCategories).distinct().forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { viewModel.setCategoryFilter(cat) },
                        label = { Text(cat) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selectedPaymentMethod == "All",
                    onClick = { viewModel.setPaymentMethodFilter("All") },
                    label = { Text("All Modes") }
                )
                paymentMethods.forEach { method ->
                    FilterChip(
                        selected = selectedPaymentMethod == method,
                        onClick = { viewModel.setPaymentMethodFilter(method) },
                        label = { Text(method) }
                    )
                }
            }

            // Ledger Entries List
            if (filteredTransactions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No transactions found.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Click 'Cash In' or 'Cash Out' below to add record.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredTransactions) { tx ->
                        TransactionItemCard(
                            transaction = tx,
                            onDelete = { viewModel.deleteTransaction(tx) },
                            onEdit = { selectedTxForEdit = tx }
                        )
                    }
                }
            }

            // Quick Floating Action Buttons Panel at bottom of screen
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { showTransactionDialog = "IN" },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("cash_in_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.TrendingUp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("₹ CASH IN", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                Button(
                    onClick = { showTransactionDialog = "OUT" },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("cash_out_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = RedOut),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.TrendingDown, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("₹ CASH OUT", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }

        // Add Transaction Dialog
        if (showTransactionDialog != null) {
            val type = showTransactionDialog!!
            val categories = if (type == "IN") inCategories else outCategories
            AddEditTransactionDialog(
                type = type,
                categories = categories,
                paymentMethods = paymentMethods,
                onDismiss = { showTransactionDialog = null },
                onSave = { amount, category, method, remarks ->
                    viewModel.addTransaction(amount, type, category, method, remarks)
                    showTransactionDialog = null
                }
            )
        }

        // Edit Transaction Dialog
        if (selectedTxForEdit != null) {
            val tx = selectedTxForEdit!!
            val categories = if (tx.type == "IN") inCategories else outCategories
            AddEditTransactionDialog(
                type = tx.type,
                categories = categories,
                paymentMethods = paymentMethods,
                initialAmount = tx.amount.toString(),
                initialCategory = tx.category,
                initialMethod = tx.paymentMethod,
                initialRemarks = tx.remarks,
                isEdit = true,
                onDismiss = { selectedTxForEdit = null },
                onSave = { amount, category, method, remarks ->
                    viewModel.updateTransaction(
                        tx.copy(
                            amount = amount,
                            category = category,
                            paymentMethod = method,
                            remarks = remarks
                        )
                    )
                    selectedTxForEdit = null
                }
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TransactionItemCard(
    transaction: Transaction,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    var expandedMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { expandedMenu = true },
                onLongClick = { expandedMenu = true }
            )
            .testTag("transaction_item_${transaction.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular Indicator
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (transaction.type == "IN") GreenIn.copy(alpha = 0.15f) else RedOut.copy(
                                alpha = 0.15f
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (transaction.type == "IN") Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                        contentDescription = null,
                        tint = if (transaction.type == "IN") GreenIn else RedOut,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = transaction.category,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (transaction.type == "IN") "+ ₹${transaction.amount}" else "- ₹${transaction.amount}",
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (transaction.type == "IN") GreenIn else RedOut
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (transaction.remarks.isBlank()) "No remarks" else transaction.remarks,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (transaction.paymentMethod) {
                                    "Cash" -> Icons.Default.AccountBalanceWallet
                                    "Bank" -> Icons.Default.AccountBalance
                                    else -> Icons.Default.Payment
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = transaction.paymentMethod,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val formattedDate = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(transaction.timestamp))
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            DropdownMenu(
                expanded = expandedMenu,
                onDismissRequest = { expandedMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Edit Entry") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        expandedMenu = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete Entry", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        expandedMenu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTransactionDialog(
    type: String,
    categories: List<String>,
    paymentMethods: List<String>,
    initialAmount: String = "",
    initialCategory: String = "",
    initialMethod: String = "",
    initialRemarks: String = "",
    isEdit: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Double, String, String, String) -> Unit
) {
    var amount by remember { mutableStateOf(initialAmount) }
    var category by remember { mutableStateOf(if (initialCategory.isBlank()) categories.first() else initialCategory) }
    var paymentMethod by remember { mutableStateOf(if (initialMethod.isBlank()) paymentMethods.first() else initialMethod) }
    var remarks by remember { mutableStateOf(initialRemarks) }

    var expandedCat by remember { mutableStateOf(false) }
    var expandedMethod by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (isEdit) "Edit Transaction" else "Add $type Record",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (type == "IN") GreenIn else RedOut
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (₹)") },
                    placeholder = { Text("Enter amount") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("amount_field"),
                    shape = RoundedCornerShape(10.dp)
                )

                // Category selection dropdown
                ExposedDropdownMenuBox(
                    expanded = expandedCat,
                    onExpandedChange = { expandedCat = !expandedCat }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = category,
                        onValueChange = {},
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCat) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCat,
                        onDismissRequest = { expandedCat = false }
                    ) {
                        categories.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption) },
                                onClick = {
                                    category = selectionOption
                                    expandedCat = false
                                }
                            )
                        }
                    }
                }

                // Payment Mode selection dropdown
                ExposedDropdownMenuBox(
                    expanded = expandedMethod,
                    onExpandedChange = { expandedMethod = !expandedMethod }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = paymentMethod,
                        onValueChange = {},
                        label = { Text("Payment Mode") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedMethod) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedMethod,
                        onDismissRequest = { expandedMethod = false }
                    ) {
                        paymentMethods.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption) },
                                onClick = {
                                    paymentMethod = selectionOption
                                    expandedMethod = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = remarks,
                    onValueChange = { remarks = it },
                    label = { Text("Remarks") },
                    placeholder = { Text("What is this for?") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remarks_field"),
                    shape = RoundedCornerShape(10.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val parsedAmount = amount.toDoubleOrNull() ?: 0.0
                            if (parsedAmount > 0.0) {
                                onSave(parsedAmount, category, paymentMethod, remarks)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (type == "IN") GreenIn else RedOut),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("save_transaction_button")
                    ) {
                        Text("Save Entry")
                    }
                }
            }
        }
    }
}

// --- SCREEN 2: UDHAR BOOK (PARTY LEDGER) ---

@Composable
fun UdharScreen(viewModel: LedgerViewModel) {
    val parties by viewModel.parties.collectAsStateWithLifecycle()
    val activeParty by viewModel.activeParty.collectAsStateWithLifecycle()
    val activePartyTransactions by viewModel.activePartyTransactions.collectAsStateWithLifecycle()
    val allPartyTransactions by viewModel.allPartyTransactions.collectAsStateWithLifecycle()

    var showAddPartyDialog by remember { mutableStateOf(false) }
    var partyName by remember { mutableStateOf("") }
    var partyPhone by remember { mutableStateOf("") }

    var showAddPartyTxDialog by remember { mutableStateOf<String?>(null) } // "GAVE", "GOT", or null

    if (activeParty == null) {
        // --- PARTIES LIST SCREEN ---
        
        // Calculate Udhar Totals
        // GAVE (you gave, they owe you) is positive udhar (receivable)
        // GOT (you got, you owe them) is negative udhar (payable)
        var totalGet = 0.0
        var totalGive = 0.0

        parties.forEach { party ->
            val txs = allPartyTransactions.filter { it.partyId == party.id }
            val netPartyBalance = txs.filter { it.type == "GAVE" }.sumOf { it.amount } - 
                                 txs.filter { it.type == "GOT" }.sumOf { it.amount }
            if (netPartyBalance > 0.0) {
                totalGet += netPartyBalance
            } else if (netPartyBalance < 0.0) {
                totalGive += -netPartyBalance
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Udhar Summary Cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = GreenIn.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("You Will Get (Book Debt)", fontSize = 12.sp, color = GreenIn)
                        Text(
                            "₹${String.format("%,.0f", totalGet)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = GreenIn
                        )
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = RedOut.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("You Will Give (Payable)", fontSize = 12.sp, color = RedOut)
                        Text(
                            "₹${String.format("%,.0f", totalGive)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = RedOut
                        )
                    }
                }
            }

            // Parties List Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("All Customers & Suppliers", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { showAddPartyDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("add_party_trigger")
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Party", fontSize = 12.sp)
                }
            }

            if (parties.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.People,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Your Udhar Book is empty.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Record credits & debts with parties here to send smart reminders.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(parties) { party ->
                        val txs = allPartyTransactions.filter { it.partyId == party.id }
                        val netPartyBalance = txs.filter { it.type == "GAVE" }.sumOf { it.amount } - 
                                             txs.filter { it.type == "GOT" }.sumOf { it.amount }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectParty(party) }
                                .testTag("party_item_${party.id}"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = party.name.take(1).uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 18.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(party.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                        if (party.phone.isNotBlank()) {
                                            Text(party.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    if (netPartyBalance == 0.0) {
                                        Text("Settled", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        Text(
                                            text = if (netPartyBalance > 0.0) "You will get" else "You will give",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (netPartyBalance > 0.0) GreenIn else RedOut
                                        )
                                        Text(
                                            text = "₹${String.format("%,.0f", Math.abs(netPartyBalance))}",
                                            fontWeight = FontWeight.Black,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (netPartyBalance > 0.0) GreenIn else RedOut
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Add Party Dialog
        if (showAddPartyDialog) {
            AlertDialog(
                onDismissRequest = { showAddPartyDialog = false },
                title = { Text("Add Customer / Supplier") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = partyName,
                            onValueChange = { partyName = it },
                            label = { Text("Contact Name") },
                            placeholder = { Text("e.g. John Doe") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("party_name_field"),
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = partyPhone,
                            onValueChange = { partyPhone = it },
                            label = { Text("Phone Number") },
                            placeholder = { Text("e.g. +91 9999999999") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("party_phone_field"),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (partyName.isNotBlank()) {
                                viewModel.addParty(partyName, partyPhone)
                                partyName = ""
                                partyPhone = ""
                                showAddPartyDialog = false
                            }
                        },
                        modifier = Modifier.testTag("save_party_button")
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddPartyDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    } else {
        // --- PARTY DETAILS LEDGER VIEW ---
        val party = activeParty!!
        val netPartyBalance = activePartyTransactions.filter { it.type == "GAVE" }.sumOf { it.amount } - 
                             activePartyTransactions.filter { it.type == "GOT" }.sumOf { it.amount }
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current

        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.selectParty(null) },
                    modifier = Modifier.testTag("back_to_parties_button")
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(party.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    if (party.phone.isNotBlank()) {
                        Text(party.phone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { viewModel.deleteParty(party) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete contact", tint = MaterialTheme.colorScheme.error)
                }
            }

            // Party Net Balance Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (netPartyBalance > 0.0) GreenIn.copy(alpha = 0.1f) 
                                     else if (netPartyBalance < 0.0) RedOut.copy(alpha = 0.1f) 
                                     else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Net Ledger Balance", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = if (netPartyBalance > 0.0) "You will get ₹${String.format("%,.0f", netPartyBalance)}"
                                   else if (netPartyBalance < 0.0) "You will give ₹${String.format("%,.0f", -netPartyBalance)}"
                                   else "Settle Account",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = if (netPartyBalance > 0.0) GreenIn else if (netPartyBalance < 0.0) RedOut else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Share Reminder Option
                    if (netPartyBalance > 0.0 && party.phone.isNotBlank()) {
                        Button(
                            onClick = {
                                val message = "Hi ${party.name}, a payment of ₹${netPartyBalance} is outstanding on your ledger. Please settle at your convenience. Thank you!"
                                val sendIntent: Intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, message)
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "Send Payment Reminder")
                                context.startActivity(shareIntent)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send Link", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Transactions History list
            Text(
                "Statement History",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (activePartyTransactions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No transactions recorded for this customer yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(activePartyTransactions) { pTx ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(if (pTx.type == "GAVE") GreenIn.copy(alpha = 0.15f) else RedOut.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (pTx.type == "GAVE") "G" else "R",
                                            fontWeight = FontWeight.Bold,
                                            color = if (pTx.type == "GAVE") GreenIn else RedOut
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = if (pTx.remarks.isNotBlank()) pTx.remarks else if (pTx.type == "GAVE") "Credit given" else "Credit cleared",
                                            fontWeight = FontWeight.Bold
                                        )
                                        val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(pTx.timestamp))
                                        Text(dateStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "₹${pTx.amount}",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 16.sp,
                                        color = if (pTx.type == "GAVE") GreenIn else RedOut
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(onClick = { viewModel.deletePartyTransaction(pTx) }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Delete record", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Party Transaction Addition Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { showAddPartyTxDialog = "GAVE" },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("party_gave_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("₹ YOU GAVE", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                Button(
                    onClick = { showAddPartyTxDialog = "GOT" },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("party_got_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = RedOut),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("₹ YOU GOT", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }

        // Add Party Transaction Dialog
        if (showAddPartyTxDialog != null) {
            val type = showAddPartyTxDialog!!
            var amount by remember { mutableStateOf("") }
            var remarks by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showAddPartyTxDialog = null },
                title = { Text(if (type == "GAVE") "Record Giving Credit" else "Record Received Credit") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it },
                            label = { Text("Amount (₹)") },
                            placeholder = { Text("Enter amount") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("party_amount_field"),
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = remarks,
                            onValueChange = { remarks = it },
                            label = { Text("Details (Remarks)") },
                            placeholder = { Text("e.g. Rice purchase, Advance payment") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("party_remarks_field"),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val parsedAmount = amount.toDoubleOrNull() ?: 0.0
                            if (parsedAmount > 0.0) {
                                viewModel.addPartyTransaction(party.id, parsedAmount, type, remarks)
                                showAddPartyTxDialog = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (type == "GAVE") GreenIn else RedOut),
                        modifier = Modifier.testTag("save_party_transaction_button")
                    ) {
                        Text("Save Ledger")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddPartyTxDialog = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

// --- SCREEN 3: REPORTS & ANALYTICS EXPORT ---

@Composable
fun ReportsScreen(viewModel: LedgerViewModel) {
    val activeBookTransactions by viewModel.activeBookTransactions.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Aggregate Analytics
    val totalIn = activeBookTransactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = activeBookTransactions.filter { it.type == "OUT" }.sumOf { it.amount }
    
    val modeCash = activeBookTransactions.filter { it.paymentMethod == "Cash" }.sumOf { if (it.type == "IN") it.amount else -it.amount }
    val modeOnline = activeBookTransactions.filter { it.paymentMethod == "Online" }.sumOf { if (it.type == "IN") it.amount else -it.amount }
    val modeBank = activeBookTransactions.filter { it.paymentMethod == "Bank" }.sumOf { if (it.type == "IN") it.amount else -it.amount }

    // Category aggregations for chart representation
    val categoryTotals = activeBookTransactions
        .filter { it.type == "OUT" }
        .groupBy { it.category }
        .mapValues { entry -> entry.value.sumOf { it.amount } }
        .toList()
        .sortedByDescending { it.second }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Financial Statement",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        }

        // Account Breakdown list
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Payment Mode Balances", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("💵 Cash Balance")
                        Text("₹${String.format("%,.0f", modeCash)}", fontWeight = FontWeight.Bold, color = if (modeCash >= 0) GreenIn else RedOut)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("⚡ Online (UPI) Balance")
                        Text("₹${String.format("%,.0f", modeOnline)}", fontWeight = FontWeight.Bold, color = if (modeOnline >= 0) GreenIn else RedOut)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("🏦 Bank Statement")
                        Text("₹${String.format("%,.0f", modeBank)}", fontWeight = FontWeight.Bold, color = if (modeBank >= 0) GreenIn else RedOut)
                    }
                }
            }
        }

        // Custom Visual Chart (Expenditure Bar Chart)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Expenses by Category", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))

                    if (categoryTotals.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No expenses logged to generate visual analytics.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        val maxVal = categoryTotals.maxOf { it.second }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            categoryTotals.take(5).forEach { (category, total) ->
                                val fraction = if (maxVal > 0) (total / maxVal).toFloat() else 0f
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(category, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text("₹${String.format("%,.0f", total)}", style = MaterialTheme.typography.bodyMedium, color = RedOut)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(fraction)
                                                .height(10.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(
                                                    Brush.horizontalGradient(
                                                        listOf(RedOut.copy(alpha = 0.6f), RedOut)
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // CSV Analytical Exporter
        item {
            Button(
                onClick = {
                    val csvData = viewModel.getCSVData()
                    clipboardManager.setText(AnnotatedString(csvData))
                    Toast.makeText(context, "CSV Statement copied to clipboard!", Toast.LENGTH_LONG).show()

                    // Trigger direct native Share dialogue
                    val sendIntent: Intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, csvData)
                        type = "text/comma-separated-values"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, "Share Book Statement CSV")
                    context.startActivity(shareIntent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("export_csv_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export & Share Book CSV Report", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// --- SCREEN 4: AI CHAT ASSISTANT (LedgerMate) ---

@Composable
fun AIAssistantScreen(viewModel: LedgerViewModel) {
    val chatHistory by viewModel.chatHistory.collectAsStateWithLifecycle()
    val aiLoading by viewModel.aiLoading.collectAsStateWithLifecycle()
    val aiDraftTransaction by viewModel.aiDraftTransaction.collectAsStateWithLifecycle()
    val aiError by viewModel.aiError.collectAsStateWithLifecycle()

    var inputMessage by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    // Scroll to bottom when chat updates
    LaunchedEffect(chatHistory.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Chat History Scroll Area
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            chatHistory.forEach { chat ->
                val isUser = chat.sender == "user"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isUser) MaterialTheme.colorScheme.primary 
                                             else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (isUser) "You" else "LedgerMate AI",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) 
                                        else MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = chat.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary 
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (aiLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("LedgerMate is parsing...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // Glowing Neo Floating AI Draft Approval Card
        if (aiDraftTransaction != null) {
            val draft = aiDraftTransaction!!
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .border(2.dp, if (draft.type == "IN") GreenIn else RedOut, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Parsed AI Transaction Draft",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Type: ${draft.type}", fontWeight = FontWeight.Bold, color = if (draft.type == "IN") GreenIn else RedOut)
                            Text("Amount: ₹${draft.amount}", fontWeight = FontWeight.Bold)
                            Text("Category: ${draft.category}")
                            Text("Mode: ${draft.paymentMethod}")
                            Text("Remarks: ${draft.remarks}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { viewModel.clearDraftTransaction() }) {
                            Text("Discard", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = { viewModel.saveDraftTransaction() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (draft.type == "IN") GreenIn else RedOut),
                            modifier = Modifier.testTag("ai_confirm_save_button")
                        ) {
                            Text("Save to Ledger")
                        }
                    }
                }
            }
        }

        // Chat Input Bar at bottom of screen
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputMessage,
                onValueChange = { inputMessage = it },
                placeholder = { Text("Ask or enter finance record...") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_input_field"),
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (inputMessage.isNotBlank()) {
                        viewModel.sendChatMessage(inputMessage)
                        inputMessage = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .testTag("ai_send_button"),
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send message")
            }
        }
    }
}
