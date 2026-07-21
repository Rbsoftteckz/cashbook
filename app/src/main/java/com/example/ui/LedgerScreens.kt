package com.example.ui

import android.content.Intent
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.*
import com.example.ui.theme.GreenIn
import com.example.ui.theme.RedOut
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

// --- Permissions Helper ---
fun hasPermission(role: String, action: String): Boolean {
    return when (role) {
        "Boss" -> true
        "Admin" -> action != "delete_business" && action != "clear_sync"
        "Partner" -> action == "view" || action == "switch_business"
        "Data Entry" -> action == "view" || action == "add_transaction" || action == "add_book"
        else -> false
    }
}

// --- Dynamic Mathematical Expression Evaluator ---
fun evaluateMathExpression(expr: String): Double? {
    try {
        val sanitized = expr.replace(" ", "").replace("Rs.", "").replace("Rs", "")
        if (!sanitized.matches(Regex("[0-9+\\-*/.()]+"))) return null
        return parseExpr(sanitized)
    } catch (e: Exception) {
        return null
    }
}

private fun parseExpr(str: String): Double {
    return object : Any() {
        var pos = -1
        var ch = 0

        fun nextChar() {
            ch = if (++pos < str.length) str[pos].code else -1
        }

        fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < str.length) throw RuntimeException("Unexpected character")
            return x
        }

        fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                if (eat('+'.code)) x += parseTerm()
                else if (eat('-'.code)) x -= parseTerm()
                else return x
            }
        }

        fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                if (eat('*'.code)) x *= parseFactor()
                else if (eat('/'.code)) {
                    val divisor = parseFactor()
                    if (divisor == 0.0) throw ArithmeticException("Division by zero")
                    x /= divisor
                }
                else return x
            }
        }

        fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()

            var x: Double
            val startPos = pos
            if (eat('('.code)) {
                x = parseExpression()
                eat(')'.code)
            } else if (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) {
                while (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) nextChar()
                x = str.substring(startPos, pos).toDouble()
            } else {
                throw RuntimeException("Unexpected character")
            }
            return x
        }
    }.parse()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerAppScreen(viewModel: LedgerViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val businesses by viewModel.businesses.collectAsStateWithLifecycle()
    val activeBusiness by viewModel.activeBusiness.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    val isAppLockEnabled by viewModel.isAppLockEnabled.collectAsStateWithLifecycle()
    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    var showSecuritySettingsDialog by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showBookSelector by remember { mutableStateOf(false) }
    var showAddBookDialog by remember { mutableStateOf(false) }
    var newBookName by remember { mutableStateOf("") }

    var showAddBusinessDialog by remember { mutableStateOf(false) }
    var newBusinessName by remember { mutableStateOf("") }

    if (isAppLockEnabled && !isAppUnlocked) {
        AppSecureLockScreen(viewModel = viewModel, onUnlockSuccess = {})
    } else {
        ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp),
                drawerContainerColor = Color(0xFFF8FAFC), // Premium Light Slate-50 color
                drawerContentColor = Color(0xFF0F172A)    // Dark Slate-900 text color
            ) {
                // Header Profile Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFFE2E8F0), // Light Slate-200
                                    Color(0xFFF1F5F9)  // Light Slate-100
                                )
                            )
                        )
                        .padding(24.dp)
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(GreenIn),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (activeBusiness?.name?.take(2) ?: "CP").uppercase(),
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = activeBusiness?.name ?: "Cashbook Pro Business",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF0F172A)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = GreenIn,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = syncStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF475569), // Slate-600
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Divider(color = Color(0xFFE2E8F0))

                // Scrollable Drawer Items
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Simulated permission active role selector inside drawer
                    Text(
                        "Demo Security Role:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Boss", "Partner", "Data Entry").forEach { role ->
                            FilterChip(
                                selected = simulatedRole == role,
                                onClick = { viewModel.setSimulatedRole(role) },
                                label = { Text(role, fontSize = 10.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Core Ledgers",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B) // Slate-500
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                        label = { Text("Cashbook Ledger") },
                        selected = currentScreen == Screen.DASHBOARD,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = GreenIn.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = GreenIn,
                            unselectedIconColor = Color(0xFF475569),
                            selectedTextColor = GreenIn,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            viewModel.setScreen(Screen.DASHBOARD)
                            scope.launch { drawerState.close() }
                        }
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                        label = { Text("Reports & Analytics") },
                        selected = currentScreen == Screen.REPORTS,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = GreenIn.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = GreenIn,
                            unselectedIconColor = Color(0xFF475569),
                            selectedTextColor = GreenIn,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            viewModel.setScreen(Screen.REPORTS)
                            scope.launch { drawerState.close() }
                        }
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                        label = { Text("LedgerMate AI Copilot") },
                        selected = currentScreen == Screen.AI_ASSISTANT,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = GreenIn.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = GreenIn,
                            unselectedIconColor = Color(0xFF475569),
                            selectedTextColor = GreenIn,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            viewModel.setScreen(Screen.AI_ASSISTANT)
                            scope.launch { drawerState.close() }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Enterprise",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B) // Slate-500
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Groups, contentDescription = null) },
                        label = { Text("Staff & Team Management") },
                        selected = currentScreen == Screen.TEAM_MANAGEMENT,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = GreenIn.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = GreenIn,
                            unselectedIconColor = Color(0xFF475569),
                            selectedTextColor = GreenIn,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            viewModel.setScreen(Screen.TEAM_MANAGEMENT)
                            scope.launch { drawerState.close() }
                        }
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.CloudSync, contentDescription = null) },
                        label = { Text("Google Drive Cloud Sync") },
                        selected = currentScreen == Screen.SYNC_CENTER,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = GreenIn.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = GreenIn,
                            unselectedIconColor = Color(0xFF475569),
                            selectedTextColor = GreenIn,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            viewModel.setScreen(Screen.SYNC_CENTER)
                            scope.launch { drawerState.close() }
                        }
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.NewReleases, contentDescription = null) },
                        label = { Text("What's New") },
                        selected = currentScreen == Screen.WHATS_NEW,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = GreenIn.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = GreenIn,
                            unselectedIconColor = Color(0xFF475569),
                            selectedTextColor = GreenIn,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            viewModel.setScreen(Screen.WHATS_NEW)
                            scope.launch { drawerState.close() }
                        }
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Help, contentDescription = null) },
                        label = { Text("Help & FAQs") },
                        selected = currentScreen == Screen.HELP_DOCS,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = GreenIn.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = GreenIn,
                            unselectedIconColor = Color(0xFF475569),
                            selectedTextColor = GreenIn,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            viewModel.setScreen(Screen.HELP_DOCS)
                            scope.launch { drawerState.close() }
                        }
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.ContactSupport, contentDescription = null) },
                        label = { Text("Contact Support") },
                        selected = currentScreen == Screen.CONTACT_US,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = GreenIn.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = GreenIn,
                            unselectedIconColor = Color(0xFF475569),
                            selectedTextColor = GreenIn,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            viewModel.setScreen(Screen.CONTACT_US)
                            scope.launch { drawerState.close() }
                        }
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        label = { Text("App PIN Lock / Password") },
                        selected = showSecuritySettingsDialog,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = GreenIn.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = GreenIn,
                            unselectedIconColor = Color(0xFF475569),
                            selectedTextColor = GreenIn,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            showSecuritySettingsDialog = true
                            scope.launch { drawerState.close() }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = Color(0xFFE2E8F0))
                    Spacer(modifier = Modifier.height(8.dp))

                    // Multi-Business Switcher Section inside Drawer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "My Businesses",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        IconButton(onClick = { showAddBusinessDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Business", modifier = Modifier.size(16.dp))
                        }
                    }

                    businesses.forEach { biz ->
                        val isSelected = biz.id == activeBusiness?.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectBusiness(biz)
                                    scope.launch { drawerState.close() }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Storefront,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        biz.name,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                } else if (businesses.size > 1 && hasPermission(simulatedRole, "delete_business")) {
                                    IconButton(
                                        onClick = { viewModel.deleteBusiness(biz) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete business", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open Navigation Drawer")
                        }
                    },
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
                                text = activeBook?.name ?: "No Book Active",
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
                            onClick = {
                                if (hasPermission(simulatedRole, "add_book")) {
                                    showAddBookDialog = true
                                } else {
                                    Toast.makeText(context, "Unauthorized: Data Entry or read-only Partner cannot create books.", Toast.LENGTH_SHORT).show()
                                }
                            },
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
                        Screen.UDHAR -> BookDetailScreen(viewModel)
                        Screen.REPORTS -> ReportsScreen(viewModel)
                        Screen.AI_ASSISTANT -> AIAssistantScreen(viewModel)
                        Screen.TEAM_MANAGEMENT -> TeamManagementScreen(viewModel)
                        Screen.SYNC_CENTER -> SyncCenterScreen(viewModel)
                        Screen.WHATS_NEW -> WhatsNewScreen(viewModel)
                        Screen.HELP_DOCS -> HelpDocsScreen(viewModel)
                        Screen.CONTACT_US -> ContactUsScreen(viewModel)
                    }
                }

                // Book Selector Modal Dialog
                if (showBookSelector) {
                    AlertDialog(
                        onDismissRequest = { showBookSelector = false },
                        title = { Text("Business Cashbooks", fontWeight = FontWeight.Bold) },
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
                                            } else if (books.size > 1 && hasPermission(simulatedRole, "delete_book")) {
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
                        title = { Text("Create New Cashbook") },
                        text = {
                            OutlinedTextField(
                                value = newBookName,
                                onValueChange = { newBookName = it },
                                label = { Text("Book Name") },
                                placeholder = { Text("e.g., Shop Daily, Personal Wallet") },
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

                // Create Business Dialog
                if (showAddBusinessDialog) {
                    AlertDialog(
                        onDismissRequest = { showAddBusinessDialog = false },
                        title = { Text("Add Corporate Business Account") },
                        text = {
                            OutlinedTextField(
                                value = newBusinessName,
                                onValueChange = { newBusinessName = it },
                                label = { Text("Business Entity Name") },
                                placeholder = { Text("e.g. Apex Traders Ltd, Personal") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (newBusinessName.isNotBlank()) {
                                        viewModel.createBusiness(newBusinessName)
                                        newBusinessName = ""
                                        showAddBusinessDialog = false
                                    }
                                }
                            ) {
                                Text("Create Account")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddBusinessDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // Security PIN Lock Settings Dialog
                if (showSecuritySettingsDialog) {
                    AppLockSettingsDialog(
                        viewModel = viewModel,
                        onDismiss = { showSecuritySettingsDialog = false }
                    )
                }
            }
        }
    }
}
}

// --- SCREEN 1: DASHBOARD ---

@Composable
fun DashboardScreen(viewModel: LedgerViewModel) {
    val businesses by viewModel.businesses.collectAsStateWithLifecycle()
    val activeBusiness by viewModel.activeBusiness.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
    val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()

    var showTransactionDialog by remember { mutableStateOf<String?>(null) }
    var dashboardSearchQuery by remember { mutableStateOf("") }
    var dashboardFilterType by remember { mutableStateOf("all") }
    val context = LocalContext.current

    // Aggregate statistics across ALL books in the active business
    val activeBookIds = books.map { it.id }.toSet()
    val businessTransactions = allTransactions.filter { activeBookIds.contains(it.bookId) }

    val totalIn = businessTransactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = businessTransactions.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut

    val inCategories = listOf("Sales", "Salary", "Interest", "Commission", "Rent Received", "Other")
    val outCategories = listOf("Food", "Rent", "Salary Paid", "Office Supplies", "Travel", "Utilities", "Purchases", "Other")
    val paymentMethods = listOf("Cash", "Online", "Bank")

    // Real-time UTC local timestamp reference
    val liveClockString = remember {
        val sdf = SimpleDateFormat("EEE, d MMM yyyy • hh:mm a", Locale.getDefault())
        sdf.format(Date())
    }

    val filteredTransactions = businessTransactions.filter { tx ->
        val matchesQuery = dashboardSearchQuery.isBlank() || 
                tx.remarks.contains(dashboardSearchQuery, ignoreCase = true) ||
                tx.category.contains(dashboardSearchQuery, ignoreCase = true)
        val matchesType = dashboardFilterType == "all" || tx.type == dashboardFilterType
        matchesQuery && matchesType
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Business Profile Header Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeBusiness?.name ?: "Personal Account",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = liveClockString,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AccountBalance,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // Google Drive Cloud Sync Status Banner
        item {
            val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
            val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
            val isUserSignedIn = viewModel.syncManager.isUserSignedIn()
            val userEmail = viewModel.syncManager.getEmail()

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setScreen(Screen.SYNC_CENTER) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isUserSignedIn) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f)
                    }
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, 
                    if (isUserSignedIn) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = if (isUserSignedIn) GreenIn else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Google Drive Cloud Sync",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Surface(
                                    color = if (isUserSignedIn) GreenIn.copy(alpha = 0.12f) else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = if (isUserSignedIn) "CONNECTED" else "OFFLINE WORKSPACE",
                                        color = if (isUserSignedIn) GreenIn else MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isUserSignedIn) {
                                    "Your cashbook is backed up automatically. Account: $userEmail"
                                } else {
                                    "Connect to Google Drive to securely back up data across your devices."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Manage Sync",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Unified Bento-style Grid Financial Summary Metrics
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Bento Part 1: Net Balance Primary Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (netBalance >= 0) GreenIn.copy(alpha = 0.08f) else RedOut.copy(alpha = 0.08f)
                    ),
                    border = BorderStroke(1.5.dp, if (netBalance >= 0) GreenIn.copy(alpha = 0.4f) else RedOut.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Unified Business Capital Balance",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = if (netBalance >= 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                contentDescription = null,
                                tint = if (netBalance >= 0) GreenIn else RedOut,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Rs. ${String.format("%,.2f", netBalance)}",
                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = if (netBalance >= 0) GreenIn else RedOut
                        )
                    }
                }

                // Bento Part 2: Horizontal Side-by-Side In & Out Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(GreenIn.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TrendingUp,
                                        contentDescription = null,
                                        tint = GreenIn,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(
                                    text = "Total Cash In",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Rs. ${String.format("%,.0f", totalIn)}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = GreenIn
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(RedOut.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TrendingDown,
                                        contentDescription = null,
                                        tint = RedOut,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(
                                    text = "Total Cash Out",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Rs. ${String.format("%,.0f", totalOut)}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = RedOut
                            )
                        }
                    }
                }
            }
        }

        // Quick Entry Panel for Active Book
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Quick Book Entry",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Active Book: ${activeBook?.name ?: "Select a Cashbook"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (activeBook == null) {
                                    Toast.makeText(context, "Please select or create a cashbook first", Toast.LENGTH_SHORT).show()
                                } else if (hasPermission(simulatedRole, "add_transaction")) {
                                    showTransactionDialog = "IN"
                                } else {
                                    Toast.makeText(context, "Unauthorized role", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("quick_cash_in_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("CASH IN", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (activeBook == null) {
                                    Toast.makeText(context, "Please select or create a cashbook first", Toast.LENGTH_SHORT).show()
                                } else if (hasPermission(simulatedRole, "add_transaction")) {
                                    showTransactionDialog = "OUT"
                                } else {
                                    Toast.makeText(context, "Unauthorized role", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("quick_cash_out_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = RedOut),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("CASH OUT", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Our Cashbooks Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Our Cashbooks",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "${books.size} Books",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (books.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Book,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No Cashbooks Created Yet",
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Click the top right icon to create your first cashbook",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(books) { book ->
                val bookTxs = allTransactions.filter { it.bookId == book.id }
                val bookIn = bookTxs.filter { it.type == "IN" }.sumOf { it.amount }
                val bookOut = bookTxs.filter { it.type == "OUT" }.sumOf { it.amount }
                val bookBalance = bookIn - bookOut

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Book,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = book.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "In: Rs. ${String.format("%,.0f", bookIn)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GreenIn
                                    )
                                    Text(
                                        text = "Out: Rs. ${String.format("%,.0f", bookOut)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = RedOut
                                    )
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Rs. ${String.format("%,.0f", bookBalance)}",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                color = if (bookBalance >= 0) GreenIn else RedOut
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = {
                                    viewModel.selectBook(book)
                                    viewModel.setScreen(Screen.UDHAR) // Navigate to Book Detail Ledger
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Open Book", fontSize = 12.sp)
                                Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }
        }

        // Interactive Filter & Search Controls Panel
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Global Cashbook Search & Filters",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = dashboardSearchQuery,
                        onValueChange = { dashboardSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search by remark or category...", fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        trailingIcon = {
                            if (dashboardSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { dashboardSearchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("all" to "All Trans", "IN" to "Cash In Only", "OUT" to "Cash Out Only").forEach { (type, label) ->
                            FilterChip(
                                selected = dashboardFilterType == type,
                                onClick = { dashboardFilterType = type },
                                label = { Text(label, fontSize = 12.sp) },
                                leadingIcon = if (dashboardFilterType == type) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }
        }

        // Recent Entries Title
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Cashbook Entries",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                if (dashboardSearchQuery.isNotEmpty() || dashboardFilterType != "all") {
                    Text(
                        text = "${filteredTransactions.size} found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (filteredTransactions.isEmpty()) {
            item {
                Text(
                    text = "No recent transactions found matching filters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            val recentTxs = filteredTransactions.sortedByDescending { it.timestamp }.take(10)
            items(recentTxs) { tx ->
                val bookName = books.firstOrNull { it.id == tx.bookId }?.name ?: "Unknown Book"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        if (tx.type == "IN") GreenIn.copy(alpha = 0.15f) else RedOut.copy(alpha = 0.15f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (tx.type == "IN") Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                    contentDescription = null,
                                    tint = if (tx.type == "IN") GreenIn else RedOut,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = tx.category,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = bookName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                if (tx.remarks.isNotBlank()) {
                                    Text(
                                        text = tx.remarks,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Rs. ${if (tx.type == "IN") "+" else "-"}${String.format("%,.0f", tx.amount)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (tx.type == "IN") GreenIn else RedOut
                            )
                            Text(
                                text = tx.paymentMethod,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
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
}

// --- SCREEN 1: DETAILED TRANSACTIONS ---

@Composable
fun BookDetailScreen(viewModel: LedgerViewModel) {
    val activeBookTransactions by viewModel.activeBookTransactions.collectAsStateWithLifecycle()
    val filteredTransactions by viewModel.filteredTransactions.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedPaymentMethod by viewModel.selectedPaymentMethod.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedTransactionIds.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()

    var showTransactionDialog by remember { mutableStateOf<String?>(null) }
    var selectedTxForEdit by remember { mutableStateOf<Transaction?>(null) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Aggregate values
    val totalIn = activeBookTransactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = activeBookTransactions.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut

    val inCategories = listOf("Sales", "Salary", "Interest", "Commission", "Rent Received", "Other")
    val outCategories = listOf("Food", "Rent", "Salary Paid", "Office Supplies", "Travel", "Utilities", "Purchases", "Other")
    val paymentMethods = listOf("Cash", "Online", "Bank")

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // Batch Selection Header Banner (If selectedIds is not empty)
            if (selectedIds.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.clearTransactionSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "${selectedIds.size} selected",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Copy batch details
                            IconButton(
                                onClick = {
                                    val csvBatch = viewModel.batchGetSelectedCSV()
                                    clipboardManager.setText(AnnotatedString(csvBatch))
                                    Toast.makeText(context, "Copied selected batch to clipboard as CSV!", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy batch")
                            }

                            // Share batch CSV
                            IconButton(
                                onClick = {
                                    val csvBatch = viewModel.batchGetSelectedCSV()
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, csvBatch)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Selected Entries"))
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share batch")
                            }

                            // Delete batch
                            IconButton(
                                onClick = {
                                    if (hasPermission(simulatedRole, "delete_transaction")) {
                                        viewModel.batchDeleteSelectedTransactions()
                                        Toast.makeText(context, "Batch transactions deleted successfully.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Unauthorized: Only Boss/Admin can bulk delete transactions.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

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
                        "Net Cash Balance (Cash-on-Hand)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Rs. ${String.format("%,.2f", netBalance)}",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
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
                                "Rs. ${String.format("%,.2f", totalIn)}",
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
                                "Rs. ${String.format("%,.2f", totalOut)}",
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
                placeholder = { Text("Search comments or category...") },
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
                            Icons.Default.ReceiptLong,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No transactions matched filters.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Long-press an entry to initiate bulk operations.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                        val isSelected = selectedIds.contains(tx.id)
                        TransactionItemCard(
                            transaction = tx,
                            isSelected = isSelected,
                            onToggleSelection = { viewModel.toggleTransactionSelection(tx.id) },
                            onDelete = {
                                if (hasPermission(simulatedRole, "delete_transaction")) {
                                    viewModel.deleteTransaction(tx)
                                } else {
                                    Toast.makeText(context, "Unauthorized role: Partner cannot delete transactions.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onEdit = {
                                if (hasPermission(simulatedRole, "edit_transaction")) {
                                    selectedTxForEdit = tx
                                } else {
                                    Toast.makeText(context, "Unauthorized role: Partner/Data Entry cannot modify entries.", Toast.LENGTH_SHORT).show()
                                }
                            }
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
                    onClick = {
                        if (hasPermission(simulatedRole, "add_transaction")) {
                            showTransactionDialog = "IN"
                        } else {
                            Toast.makeText(context, "Unauthorized role: Read-only partners cannot add entries.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("cash_in_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.TrendingUp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Rs. CASH IN", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                Button(
                    onClick = {
                        if (hasPermission(simulatedRole, "add_transaction")) {
                            showTransactionDialog = "OUT"
                        } else {
                            Toast.makeText(context, "Unauthorized role: Read-only partners cannot add entries.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("cash_out_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = RedOut),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.TrendingDown, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Rs. CASH OUT", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionItemCard(
    transaction: Transaction,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    var expandedMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelected) {
                        onToggleSelection()
                    } else {
                        expandedMenu = true
                    }
                },
                onLongClick = { onToggleSelection() }
            )
            .testTag("transaction_item_${transaction.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular Indicator / Checkbox Selector
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else if (transaction.type == "IN") GreenIn.copy(alpha = 0.15f)
                            else RedOut.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(
                            imageVector = if (transaction.type == "IN") Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                            contentDescription = null,
                            tint = if (transaction.type == "IN") GreenIn else RedOut,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
                            text = if (transaction.type == "IN") "+ Rs. ${transaction.amount}" else "- Rs. ${transaction.amount}",
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
                            text = if (transaction.remarks.isBlank()) "No comment" else transaction.remarks,
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
                    text = { Text("Select / Bulk Actions") },
                    leadingIcon = { Icon(Icons.Default.LibraryAddCheck, contentDescription = null) },
                    onClick = {
                        expandedMenu = false
                        onToggleSelection()
                    }
                )
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
    var amountInput by remember { mutableStateOf(initialAmount) }
    var category by remember { mutableStateOf(if (initialCategory.isBlank()) categories.first() else initialCategory) }
    var paymentMethod by remember { mutableStateOf(if (initialMethod.isBlank()) paymentMethods.first() else initialMethod) }
    var remarks by remember { mutableStateOf(initialRemarks) }

    var expandedCat by remember { mutableStateOf(false) }
    var expandedMethod by remember { mutableStateOf(false) }
    var showCalculator by remember { mutableStateOf(false) }

    // Dynamic Math Calculator Parser
    val evaluatedValue = remember(amountInput) {
        evaluateMathExpression(amountInput)
    }

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
                    text = if (isEdit) "Edit Transaction" else "Add $type Entry",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (type == "IN") GreenIn else RedOut
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text("Amount / Formula (Rs.)") },
                        placeholder = { Text("e.g. 1500+250 or 450") },
                        trailingIcon = {
                            IconButton(onClick = { showCalculator = !showCalculator }) {
                                Icon(
                                    imageVector = Icons.Default.Calculate,
                                    contentDescription = "Toggle Calculator",
                                    tint = if (showCalculator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("amount_field"),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Embedded Dynamic Math Calculator Panel
                    if (showCalculator) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (evaluatedValue != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Preview: Rs. $evaluatedValue",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            "Use Result",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clickable {
                                                    amountInput = evaluatedValue.toString()
                                                    showCalculator = false
                                                }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                val keypadRows = listOf(
                                    listOf("7", "8", "9", "/"),
                                    listOf("4", "5", "6", "*"),
                                    listOf("1", "2", "3", "-"),
                                    listOf("0", ".", "C", "+")
                                )

                                keypadRows.forEach { rowKeys ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        rowKeys.forEach { key ->
                                            val isOperator = key in listOf("+", "-", "*", "/")
                                            val isClear = key == "C"
                                            Button(
                                                onClick = {
                                                    if (isClear) {
                                                        amountInput = ""
                                                    } else {
                                                        val lastChar = amountInput.lastOrNull()?.toString() ?: ""
                                                        val isKeyOperator = key in listOf("+", "-", "*", "/")
                                                        val isLastOperator = lastChar in listOf("+", "-", "*", "/")
                                                        if (!(isKeyOperator && isLastOperator)) {
                                                            amountInput += key
                                                        }
                                                    }
                                                },
                                                colors = if (isClear) {
                                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                                                } else if (isOperator) {
                                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                                                } else {
                                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)
                                                },
                                                modifier = Modifier.weight(1f).height(38.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                border = if (!isClear && !isOperator) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
                                            ) {
                                                Text(key, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            if (amountInput.isNotEmpty()) {
                                                amountInput = amountInput.dropLast(1)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                        modifier = Modifier.weight(1.5f).height(38.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Backspace, contentDescription = "Backspace", modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Backspace", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            if (evaluatedValue != null) {
                                                amountInput = evaluatedValue.toString()
                                            }
                                            showCalculator = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = if (type == "IN") GreenIn else RedOut, contentColor = Color.White),
                                        modifier = Modifier.weight(2.5f).height(38.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("Use Result", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }
                    }

                    // Real-time calculator result box (as a fallback simple tap preview)
                    if (!showCalculator && evaluatedValue != null && evaluatedValue.toString() != amountInput) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth().clickable {
                                amountInput = evaluatedValue.toString()
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Calculator Evaluation: Rs. $evaluatedValue",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Use Result",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }

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
                    label = { Text("Remarks / Description") },
                    placeholder = { Text("What is this transaction for?") },
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
                            val finalAmount = evaluatedValue ?: amountInput.toDoubleOrNull() ?: 0.0
                            if (finalAmount > 0.0) {
                                onSave(finalAmount, category, paymentMethod, remarks)
                            } else {
                                onDismiss()
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

// --- SCREEN 2: UDHAR BOOK ---

@Composable
fun UdharScreen(viewModel: LedgerViewModel) {
    val parties by viewModel.parties.collectAsStateWithLifecycle()
    val activeParty by viewModel.activeParty.collectAsStateWithLifecycle()
    val activePartyTransactions by viewModel.activePartyTransactions.collectAsStateWithLifecycle()
    val allPartyTransactions by viewModel.allPartyTransactions.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()

    var showAddPartyDialog by remember { mutableStateOf(false) }
    var partyName by remember { mutableStateOf("") }
    var partyPhone by remember { mutableStateOf("") }

    var showAddPartyTxDialog by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    if (activeParty == null) {
        // --- PARTIES LIST ---
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
                        Text("You Will Collect", fontSize = 12.sp, color = GreenIn)
                        Text(
                            "Rs. ${String.format("%,.0f", totalGet)}",
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
                        Text("You Will Pay", fontSize = 12.sp, color = RedOut)
                        Text(
                            "Rs. ${String.format("%,.0f", totalGive)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = RedOut
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Customers & Suppliers Ledger", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        if (hasPermission(simulatedRole, "add_transaction")) {
                            showAddPartyDialog = true
                        } else {
                            Toast.makeText(context, "Unauthorized: Only Boss, Admin, or Data Entry can register parties.", Toast.LENGTH_SHORT).show()
                        }
                    },
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
                            Icons.Default.PersonSearch,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No customers or suppliers yet.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                .clickable { viewModel.selectParty(party) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(party.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    if (party.phone.isNotBlank()) {
                                        Text(party.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    if (netPartyBalance > 0.0) {
                                        Text("Collect: Rs. ${String.format("%,.0f", netPartyBalance)}", color = GreenIn, fontWeight = FontWeight.Black)
                                    } else if (netPartyBalance < 0.0) {
                                        Text("Pay: Rs. ${String.format("%,.0f", -netPartyBalance)}", color = RedOut, fontWeight = FontWeight.Black)
                                    } else {
                                        Text("Settled", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
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
                            label = { Text("Name") },
                            placeholder = { Text("e.g. John Doe, ABC Enterprises") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("party_name_field"),
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = partyPhone,
                            onValueChange = { partyPhone = it },
                            label = { Text("Phone Number") },
                            placeholder = { Text("e.g. +92 300 1234567") },
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
                        Text("Register Party")
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
        // --- SINGLE PARTY DETAIL LEDGER ---
        val party = activeParty!!
        val netBalance = activePartyTransactions.filter { it.type == "GAVE" }.sumOf { it.amount } - 
                         activePartyTransactions.filter { it.type == "GOT" }.sumOf { it.amount }

        Column(modifier = Modifier.fillMaxSize()) {
            // Header bar
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.selectParty(null) }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                            Column {
                                Text(party.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                if (party.phone.isNotBlank()) {
                                    Text(party.phone, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text("Net Account Balance", style = MaterialTheme.typography.bodySmall)
                        if (netBalance > 0.0) {
                            Text("Will Get: Rs. $netBalance", color = GreenIn, fontWeight = FontWeight.Black)
                        } else if (netBalance < 0.0) {
                            Text("Will Give: Rs. ${-netBalance}", color = RedOut, fontWeight = FontWeight.Black)
                        } else {
                            Text("Balanced Account", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Customer ledger listings
            if (activePartyTransactions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No credits or receipts recorded with this customer yet.")
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
                                            text = if (pTx.remarks.isNotBlank()) pTx.remarks else if (pTx.type == "GAVE") "Credit Issued" else "Credit Settled",
                                            fontWeight = FontWeight.Bold
                                        )
                                        val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(pTx.timestamp))
                                        Text(dateStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Rs. ${pTx.amount}",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 16.sp,
                                        color = if (pTx.type == "GAVE") GreenIn else RedOut
                                    )
                                    if (hasPermission(simulatedRole, "delete_transaction")) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(onClick = { viewModel.deletePartyTransaction(pTx) }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Delete credit entry", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Party Transaction Add Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        if (hasPermission(simulatedRole, "add_transaction")) {
                            showAddPartyTxDialog = "GAVE"
                        } else {
                            Toast.makeText(context, "Unauthorized: Partners cannot record debts.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("party_gave_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Rs. YOU GAVE (Credit)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Button(
                    onClick = {
                        if (hasPermission(simulatedRole, "add_transaction")) {
                            showAddPartyTxDialog = "GOT"
                        } else {
                            Toast.makeText(context, "Unauthorized: Partners cannot record cash payments.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("party_got_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = RedOut),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Rs. YOU GOT (Pay)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        // Add Party Transaction Dialog
        if (showAddPartyTxDialog != null) {
            val type = showAddPartyTxDialog!!
            var amount by remember { mutableStateOf("") }
            var remarks by remember { mutableStateOf("") }
            var showPartyCalculator by remember { mutableStateOf(false) }
            val evaluatedPartyValue = remember(amount) {
                evaluateMathExpression(amount)
            }

            AlertDialog(
                onDismissRequest = { showAddPartyTxDialog = null },
                title = { Text(if (type == "GAVE") "Record Credit Given" else "Record Payment Received") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it },
                            label = { Text("Amount / Formula (Rs.)") },
                            placeholder = { Text("Enter amount or e.g. 1500+250") },
                            trailingIcon = {
                                IconButton(onClick = { showPartyCalculator = !showPartyCalculator }) {
                                    Icon(
                                        imageVector = Icons.Default.Calculate,
                                        contentDescription = "Toggle Calculator",
                                        tint = if (showPartyCalculator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("party_amount_field"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // Embedded Dynamic Math Calculator Panel for Party Transactions
                        if (showPartyCalculator) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (evaluatedPartyValue != null) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "Preview: Rs. $evaluatedPartyValue",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                "Use Result",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Black,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clickable {
                                                        amount = evaluatedPartyValue.toString()
                                                        showPartyCalculator = false
                                                    }
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    val keypadRows = listOf(
                                        listOf("7", "8", "9", "/"),
                                        listOf("4", "5", "6", "*"),
                                        listOf("1", "2", "3", "-"),
                                        listOf("0", ".", "C", "+")
                                    )

                                    keypadRows.forEach { rowKeys ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            rowKeys.forEach { key ->
                                                val isOperator = key in listOf("+", "-", "*", "/")
                                                val isClear = key == "C"
                                                Button(
                                                    onClick = {
                                                        if (isClear) {
                                                            amount = ""
                                                        } else {
                                                            val lastChar = amount.lastOrNull()?.toString() ?: ""
                                                            val isKeyOperator = key in listOf("+", "-", "*", "/")
                                                            val isLastOperator = lastChar in listOf("+", "-", "*", "/")
                                                            if (!(isKeyOperator && isLastOperator)) {
                                                                amount += key
                                                            }
                                                        }
                                                    },
                                                    colors = if (isClear) {
                                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                                                    } else if (isOperator) {
                                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                                                    } else {
                                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)
                                                    },
                                                    modifier = Modifier.weight(1f).height(38.dp),
                                                    contentPadding = PaddingValues(0.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    border = if (!isClear && !isOperator) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
                                                ) {
                                                    Text(key, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                                }
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                if (amount.isNotEmpty()) {
                                                    amount = amount.dropLast(1)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                            modifier = Modifier.weight(1.5f).height(38.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Backspace, contentDescription = "Backspace", modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Backspace", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                if (evaluatedPartyValue != null) {
                                                    amount = evaluatedPartyValue.toString()
                                                }
                                                showPartyCalculator = false
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = if (type == "GAVE") GreenIn else RedOut, contentColor = Color.White),
                                            modifier = Modifier.weight(2.5f).height(38.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("Use Result", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                        }
                                    }
                                }
                            }
                        }

                        // Fallback Preview Row
                        if (!showPartyCalculator && evaluatedPartyValue != null && evaluatedPartyValue.toString() != amount) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    amount = evaluatedPartyValue.toString()
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Evaluation: Rs. $evaluatedPartyValue",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "Use Result",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = remarks,
                            onValueChange = { remarks = it },
                            label = { Text("Remarks (Product/Service info)") },
                            placeholder = { Text("e.g. Soap stock delivery, Partial payment") },
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
                            val parsedAmount = evaluatedPartyValue ?: amount.toDoubleOrNull() ?: 0.0
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

// --- SCREEN 3: REPORTS ---

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

    // Category aggregations
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

        // Account Breakdown
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Liquid Cash Assets by Mode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("💵 Cash Vault")
                        Text("Rs. ${String.format("%,.0f", modeCash)}", fontWeight = FontWeight.Bold, color = if (modeCash >= 0) GreenIn else RedOut)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("⚡ UPI / Online Account")
                        Text("Rs. ${String.format("%,.0f", modeOnline)}", fontWeight = FontWeight.Bold, color = if (modeOnline >= 0) GreenIn else RedOut)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("🏦 Bank Deposits")
                        Text("Rs. ${String.format("%,.0f", modeBank)}", fontWeight = FontWeight.Bold, color = if (modeBank >= 0) GreenIn else RedOut)
                    }
                }
            }
        }

        // Expenses Distribution Chart
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Expenditure Distribution", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))

                    if (categoryTotals.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No expenses logged to model distribution.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        Text("Rs. ${String.format("%,.0f", total)}", style = MaterialTheme.typography.bodyMedium, color = RedOut)
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

        // Export Statement CSV
        item {
            Button(
                onClick = {
                    val csvData = viewModel.getCSVData()
                    clipboardManager.setText(AnnotatedString(csvData))
                    Toast.makeText(context, "Full Statement CSV copied to clipboard!", Toast.LENGTH_LONG).show()

                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, csvData)
                        type = "text/comma-separated-values"
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Share CSV Statement Report"))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("export_csv_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export and Share Corporate Statement CSV", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// --- SCREEN 4: AI ASSISTANT ---

@Composable
fun AIAssistantScreen(viewModel: LedgerViewModel) {
    val chatHistory by viewModel.chatHistory.collectAsStateWithLifecycle()
    val aiLoading by viewModel.aiLoading.collectAsStateWithLifecycle()
    val aiDraftTransaction by viewModel.aiDraftTransaction.collectAsStateWithLifecycle()

    var inputMessage by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    LaunchedEffect(chatHistory.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(modifier = Modifier.fillMaxSize()) {
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

        // AI Draft Approval Dialog Card
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
                        "Formulated AI Ledger Entry Draft",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column {
                        Text("Type: ${draft.type}", fontWeight = FontWeight.Bold, color = if (draft.type == "IN") GreenIn else RedOut)
                        Text("Amount: Rs. ${draft.amount}", fontWeight = FontWeight.Bold)
                        Text("Category: ${draft.category}")
                        Text("Mode: ${draft.paymentMethod}")
                        Text("Remarks: ${draft.remarks}", maxLines = 1, overflow = TextOverflow.Ellipsis)
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

        // Chat Input Bar
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
                placeholder = { Text("e.g. Got 2500 for product sales online...") },
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

// --- SCREEN 5: TEAM MANAGEMENT & STAFF ROSTER ---

@Composable
fun TeamManagementScreen(viewModel: LedgerViewModel) {
    val activeTeamMembers by viewModel.activeBusinessTeamMembers.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()
    val activeBusiness by viewModel.activeBusiness.collectAsStateWithLifecycle()

    var showAddStaffDialog by remember { mutableStateOf(false) }
    var staffName by remember { mutableStateOf("") }
    var staffEmail by remember { mutableStateOf("") }
    var staffPhone by remember { mutableStateOf("") }
    var staffRole by remember { mutableStateOf("Data Entry") } // Default role

    var showInvitationSuccessDialog by remember { mutableStateOf(false) }
    var lastAddedCollaboratorName by remember { mutableStateOf("") }
    var lastAddedCollaboratorRole by remember { mutableStateOf("") }
    var lastAddedCollaboratorPhone by remember { mutableStateOf("") }
    var lastAddedCollaboratorEmail by remember { mutableStateOf("") }

    val roles = listOf("Boss", "Admin", "Partner", "Data Entry")
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Digital Staff Roster",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "Manage collaborator permissions safely",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = {
                    if (hasPermission(simulatedRole, "add_team_member")) {
                        showAddStaffDialog = true
                    } else {
                        Toast.makeText(context, "Unauthorized: Only Boss/Admin can recruit team members.", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Icon(Icons.Default.PersonAddAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Team")
            }
        }

        // Active testing chip
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Switch Simulated Security Role (Sandbox Mode):",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    roles.filter { it != "Boss" }.forEach { r ->
                        val isSelected = simulatedRole == r
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setSimulatedRole(r) },
                            label = { Text(r, fontSize = 11.sp) }
                        )
                    }
                    val isBoss = simulatedRole == "Boss"
                    FilterChip(
                        selected = isBoss,
                        onClick = { viewModel.setSimulatedRole("Boss") },
                        label = { Text("Boss (Owner)", fontSize = 11.sp) }
                    )
                }
            }
        }

        // Team members lazy column
        if (activeTeamMembers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No personnel added to this business. Add staff to delegate entries.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(activeTeamMembers) { tm ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
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
                                        .background(MaterialTheme.colorScheme.secondary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        tm.name.take(1).uppercase(),
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.onSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(tm.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Text("Role: ${tm.role}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                    if (tm.email.isNotBlank()) {
                                        Text(tm.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            if (hasPermission(simulatedRole, "delete_team_member")) {
                                IconButton(onClick = { viewModel.deleteTeamMember(tm) }) {
                                    Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Remove Staff", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddStaffDialog) {
        var expandedRoleMenu by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddStaffDialog = false },
            title = { Text("Recruit Collaborator / Staff") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = staffName,
                        onValueChange = { staffName = it },
                        label = { Text("Staff Full Name") },
                        placeholder = { Text("e.g. Sarah Connor") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = staffEmail,
                        onValueChange = { staffEmail = it },
                        label = { Text("Email Address") },
                        placeholder = { Text("e.g. sarah@cashbook.com") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = staffPhone,
                        onValueChange = { staffPhone = it },
                        label = { Text("Phone / Contact Number") },
                        placeholder = { Text("e.g. +923158913912") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Role Picker dropdown
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expandedRoleMenu = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Assigned Role: $staffRole")
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = expandedRoleMenu,
                            onDismissRequest = { expandedRoleMenu = false }
                        ) {
                            roles.forEach { roleOpt ->
                                DropdownMenuItem(
                                    text = { Text(roleOpt) },
                                    onClick = {
                                        staffRole = roleOpt
                                        expandedRoleMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (staffName.isNotBlank()) {
                            viewModel.addTeamMember(staffName, staffEmail, staffPhone, staffRole)
                            lastAddedCollaboratorName = staffName
                            lastAddedCollaboratorRole = staffRole
                            lastAddedCollaboratorPhone = staffPhone
                            lastAddedCollaboratorEmail = staffEmail
                            staffName = ""
                            staffEmail = ""
                            staffPhone = ""
                            staffRole = "Data Entry"
                            showAddStaffDialog = false
                            showInvitationSuccessDialog = true
                        }
                    }
                ) {
                    Text("Recruit Collaborator")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddStaffDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showInvitationSuccessDialog) {
        val bizName = activeBusiness?.name ?: "our Business"
        val inviteLink = "https://ai.studio/build/ledger-mate?bizId=${activeBusiness?.id ?: 1}&role=$lastAddedCollaboratorRole"
        val appName = "CashBook"
        val appVersion = "v1.1"
        val apkFileName = "CashBook_${appVersion}_Debug.apk"
        val downloadUrl = "https://ais-pre-da4saffzzctvdmze42ct3v-707128247986.asia-east1.run.app/apk"
        
        val inviteText = """
            🌟 Invitation to join ${bizName} on ${appName} (${appVersion})!
            
            Hi $lastAddedCollaboratorName,
            You've been invited to join "${bizName}" as a *$lastAddedCollaboratorRole* on the ${appName} app.
            
            📥 Download App (Debug APK):
            App Name: ${appName}
            Version: ${appVersion}
            File Name: ${apkFileName}
            Download Link: ${downloadUrl}
            
            🔑 To connect your profile, use this link or invite code:
            Invite Link: $inviteLink
            Invite Code: ${activeBusiness?.id ?: 1}-$lastAddedCollaboratorRole
            
            Happy Ledger Accounting!
            ${appName} Team
        """.trimIndent()

        AlertDialog(
            onDismissRequest = { showInvitationSuccessDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = GreenIn,
                        modifier = Modifier.size(24.dp)
                    )
                    Text("🎉 Team Invite Generated!")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Invitation details for $lastAddedCollaboratorName (properly configured for ${appName} ${appVersion}).",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    OutlinedTextField(
                        value = inviteLink,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Invite Link") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(inviteLink))
                                Toast.makeText(context, "📋 Copied invite link to clipboard!", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = inviteText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (lastAddedCollaboratorEmail.isNotBlank()) {
                        Button(
                            onClick = {
                                val emailIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                    data = android.net.Uri.parse("mailto:")
                                    putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(lastAddedCollaboratorEmail))
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Invitation to join ${bizName} on ${appName} (${appVersion})")
                                    putExtra(android.content.Intent.EXTRA_TEXT, inviteText)
                                }
                                try {
                                    context.startActivity(android.content.Intent.createChooser(emailIntent, "Send Email Invitation"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No email client found", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Email Invite")
                        }
                    }

                    Button(
                        onClick = {
                            val shareIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, inviteText)
                                type = "text/plain"
                            }
                            context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Invitation"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showInvitationSuccessDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

// --- SCREEN 6: GOOGLE DRIVE SYNC CENTER ---

@Composable
fun SyncCenterScreen(viewModel: LedgerViewModel) {
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val syncManager = viewModel.syncManager

    var showOAuthDialog by remember { mutableStateOf(false) }
    var customClientIdInput by remember { mutableStateOf(syncManager.getClientId()) }
    var customRedirectUriInput by remember { mutableStateOf(syncManager.getRedirectUri()) }
    var rawJsonText by remember { mutableStateOf("") }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }
    var isAdvancedOpen by remember { mutableStateOf(false) }

    val isUserSignedIn = syncManager.isUserSignedIn()
    val email = syncManager.getEmail()
    val displayName = syncManager.getName()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Icon(
                Icons.Default.CloudSync,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Google Drive Sync Center",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Backup offline cashbook safely to personal cloud storage",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // Account Status Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Cloud Sync Status", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Connection Type:")
                        Text(
                            text = if (isUserSignedIn) "Google Cloud Connected" else "Local Only (Offline)",
                            color = if (isUserSignedIn) GreenIn else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isUserSignedIn) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Google Account:")
                            Text(email, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Synchronizer Output:")
                        Text(syncStatus, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (!isUserSignedIn) {
                        Button(
                            onClick = { 
                                // Auto-save any modified input fields before launching the webview
                                syncManager.saveClientId(customClientIdInput)
                                syncManager.saveRedirectUri(customRedirectUriInput)
                                showOAuthDialog = true 
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Login, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign in with Google")
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { viewModel.triggerCloudSync() },
                                modifier = Modifier.weight(1f),
                                enabled = !isSyncing,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Sync, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Force Sync")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    if (hasPermission(simulatedRole, "clear_sync")) {
                                        syncManager.clearAuth()
                                        viewModel.triggerCloudSync()
                                        Toast.makeText(context, "Logged out from Google Account.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Unauthorized: Data Entry or read-only Partners cannot wipe cloud credentials.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Logout, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Log Out")
                            }
                        }
                    }
                }
            }
        }

        // Custom Google Client ID Setting (Enterprise grade configuration) - Collapsible
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isAdvancedOpen = !isAdvancedOpen },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text("Advanced Setup (Self-Hosted OAuth)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(
                            imageVector = if (isAdvancedOpen) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = if (isAdvancedOpen) "Collapse" else "Expand"
                        )
                    }

                    if (isAdvancedOpen) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "The app automatically uses a cloud-hosted OAuth client out-of-the-box. If you are a developer or enterprise, you can optionally supply your own custom credentials below. Leave these blank to use the built-in system.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        OutlinedTextField(
                            value = customClientIdInput,
                            onValueChange = { customClientIdInput = it },
                            label = { Text("Custom Web Client ID") },
                            placeholder = { Text("Using built-in automatic client...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = customRedirectUriInput,
                            onValueChange = { customRedirectUriInput = it },
                            label = { Text("Custom Redirect URI") },
                            placeholder = { Text("Using built-in redirect URI...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        
                        // Setup Instructions Guide
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Config Help",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        "How to Fix 'redirect_uri_mismatch' Error:",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    "1. Go to your Google Cloud Console -> APIs & Services -> Credentials.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "2. Click on your OAuth 2.0 Client ID (under 'Web application') to edit it.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "3. Scroll to 'Authorized redirect URIs' and add this EXACT URI:",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = customRedirectUriInput.ifBlank { syncManager.getRedirectUri() },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(customRedirectUriInput.ifBlank { syncManager.getRedirectUri() }))
                                                Toast.makeText(context, "Redirect URI copied to clipboard!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy URI",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "4. Click Save in Google Cloud Console. Google takes 1-2 minutes to apply changes. Then sign in again!",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    customClientIdInput = ""
                                    customRedirectUriInput = ""
                                    syncManager.saveClientId("")
                                    syncManager.saveRedirectUri("")
                                    Toast.makeText(context, "Reset to built-in automatic client settings!", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Reset to Default")
                            }
                            Button(
                                onClick = {
                                    syncManager.saveClientId(customClientIdInput)
                                    syncManager.saveRedirectUri(customRedirectUriInput)
                                    Toast.makeText(context, "OAuth credentials configured successfully!", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Save Config")
                            }
                        }
                    }
                }
            }
        }

        // Manual Backup and JSON Restore Tools
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Manual SQLite Backups", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Generate completely portable, fully offline-restorable database backups as simple raw JSON text files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                val bizList = viewModel.businesses.value
                                val bookList = viewModel.books.value
                                val txList = viewModel.allTransactions.value
                                val partyList = viewModel.parties.value
                                val pTxList = viewModel.allPartyTransactions.value
                                val teamList = viewModel.allTeamMembers.value

                                val backupJsonStr = syncManager.serializeDatabase(
                                    businesses = bizList,
                                    books = bookList,
                                    transactions = txList,
                                    parties = partyList,
                                    partyTransactions = pTxList,
                                    teamMembers = teamList
                                )
                                clipboardManager.setText(AnnotatedString(backupJsonStr))
                                Toast.makeText(context, "Complete backup JSON copied to clipboard!", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export JSON")
                        }

                        OutlinedButton(
                            onClick = { showBackupRestoreDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import JSON")
                        }
                    }
                }
            }
        }
    }

    // Google Sign-In WebView Dialog
    if (showOAuthDialog) {
        Dialog(onDismissRequest = { showOAuthDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 24.dp, horizontal = 12.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sign In with Google", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { showOAuthDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close WebView")
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                val defaultUa = android.webkit.WebSettings.getDefaultUserAgent(ctx)
                                val sanitizedUa = if (defaultUa.isNullOrBlank()) {
                                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                } else {
                                    defaultUa
                                        .replace("; wv", "")
                                        .replace("Version/4.0 ", "")
                                        .replace("Version/4.0", "")
                                }
                                settings.userAgentString = sanitizedUa
                                
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                        return handleRedirect(url)
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                        return handleRedirect(request?.url?.toString())
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        handleRedirect(url)
                                    }

                                    private fun handleRedirect(url: String?): Boolean {
                                        val currentRedirectUri = customRedirectUriInput.ifBlank { syncManager.getRedirectUri() }
                                        if (url != null && url.startsWith(currentRedirectUri)) {
                                            val token = extractAccessToken(url)
                                            if (token != null) {
                                                syncManager.saveAccessToken(token)
                                                viewModel.triggerCloudSync()
                                                showOAuthDialog = false
                                                Toast.makeText(context, "Google Authorization successful! Sync active.", Toast.LENGTH_LONG).show()
                                                return true
                                            }
                                        }
                                        return false
                                    }
                                }

                                val finalClientId = customClientIdInput.ifBlank { syncManager.getClientId() }
                                val finalRedirectUri = customRedirectUriInput.ifBlank { syncManager.getRedirectUri() }
                                val authUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
                                        "client_id=$finalClientId&" +
                                        "redirect_uri=$finalRedirectUri&" +
                                        "response_type=token&" +
                                        "scope=https://www.googleapis.com/auth/drive.appdata%20email%20profile"
                                loadUrl(authUrl)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }

    // Import / Restore Backup Dialog
    if (showBackupRestoreDialog) {
        val coroutineScope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { showBackupRestoreDialog = false },
            title = { Text("Restore Ledger Database") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste a previously copied Backup JSON text payload below. This will merge existing business files without duplicates.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = rawJsonText,
                        onValueChange = { rawJsonText = it },
                        label = { Text("Raw Backup JSON payload") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (rawJsonText.isNotBlank()) {
                            coroutineScope.launch {
                                val db = LedgerDatabase.getDatabase(context)
                                val outcome = syncManager.restoreDatabase(rawJsonText, db.ledgerDao())
                                if (outcome) {
                                    Toast.makeText(context, "Database restored and merged successfully!", Toast.LENGTH_SHORT).show()
                                    viewModel.triggerCloudSync()
                                    rawJsonText = ""
                                    showBackupRestoreDialog = false
                                } else {
                                    Toast.makeText(context, "Invalid Backup JSON structure. Please check and retry.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                ) {
                    Text("Import and Recover")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupRestoreDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// --- OAuth Access Token Parser Utility ---
fun extractAccessToken(url: String): String? {
    try {
        val fragment = url.substringAfter("#", "")
        if (fragment.isNotEmpty()) {
            val params = fragment.split("&")
            for (p in params) {
                val kv = p.split("=")
                if (kv.size == 2 && kv[0] == "access_token") {
                    return kv[1]
                }
            }
        }
    } catch (e: Exception) {
        Log.e("OAuthWebView", "Failed to parse access token", e)
    }
    return null
}

// --- SCREEN 7: WHAT'S NEW ---
@Composable
fun WhatsNewScreen(viewModel: LedgerViewModel) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NewReleases,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = "What's New in Cashbook Web",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Explore the latest features and security updates in v1.2.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "NEW RELEASE",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = "1. Instant WhatsApp Business Reminders",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Now send direct book balances and transactional receipts straight to your suppliers or customers via WhatsApp. Real-time updates prevent reconciliation conflicts and speed up collections.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = GreenIn.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "UPGRADE",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = GreenIn,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = "2. Secure Cloud Auto-Sync Engine",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Your offline database automatically backs up to your secure Cloud Google Drive storage silently on every change. When you sign in on another device, your files restore instantly with zero risk of data loss.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "IMPROVEMENT",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = "3. Granular Role-Based Permissions",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Collaborate securely by inviting staff with restricted access. Assign operators specific roles like 'Data Entry' so they can add transactions without permission to delete historical records.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// --- SCREEN 8: HELP & FAQS ---
@Composable
fun HelpDocsScreen(viewModel: LedgerViewModel) {
    val faqs = listOf(
        "How do I add transactions offline?" to "Our Digital Cashbook runs entirely client-side. Every entry is persisted in local device storage instantly, and is automatically uploaded to Google Drive when networks reconnect.",
        "Is my financial data secure?" to "Yes! Your books are stored securely in local app-sandboxes. When you configure Google Drive sync, your data resides entirely inside your personal cloud account and is never stored on third-party servers.",
        "Can multiple operators log entries?" to "Absolutely. You can invite operators from the 'Staff & Team' menu and assign specific roles. For instance, 'Data Entry' operators can input Cash In/Out logs but cannot delete any history.",
        "How to export reports for tax filing?" to "Navigate to the Reports page or open any active book and click 'Export CSV'. This generates a standard spreadsheet list containing timestamps, remarks, tags, and payment modes ready to use."
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Help,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = "Help Center & Documentation",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Learn how to get the most out of your multi-book digital Cashbook",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(faqs) { (q, a) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = q,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = a,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// --- SCREEN 9: CONTACT US ---
@Composable
fun ContactUsScreen(viewModel: LedgerViewModel) {
    val context = LocalContext.current
    var feedbackText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContactSupport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = "Contact Customer Support",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Reach out to our specialists or submit feature feedback",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(GreenIn.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Message, contentDescription = null, tint = GreenIn)
                        }
                        Column {
                            Text("WhatsApp Support", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Chat with support agents for direct help", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                data = android.net.Uri.parse("https://wa.me/923337998373")
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Chat: +92 333 7998373", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Column {
                            Text("Support Hotline", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Available Mon-Sat, 9AM-6PM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                                data = android.net.Uri.parse("tel:03337998373")
                            }
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Call: +92 333 7998373", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        }
                        Column {
                            Text("Email Support", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Send your inquiries directly to our inbox", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                data = android.net.Uri.parse("mailto:Rbmengal@live.com")
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "Cashbook Pro Customer Inquiry")
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "No email app found!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Email: Rbmengal@live.com", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Feedback, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        }
                        Column {
                            Text("Feedback & Feature Ideas", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Suggest payment modes, formats, or visual features", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedTextField(
                        value = feedbackText,
                        onValueChange = { feedbackText = it },
                        label = { Text("Tell us how we can improve...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Button(
                        onClick = {
                            if (feedbackText.isNotBlank()) {
                                Toast.makeText(context, "Thank you for your valuable feedback!", Toast.LENGTH_SHORT).show()
                                feedbackText = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Submit Idea", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun AppSecureLockScreen(
    viewModel: LedgerViewModel,
    onUnlockSuccess: () -> Unit
) {
    val context = LocalContext.current
    val securityQuestion by viewModel.securityQuestion.collectAsStateWithLifecycle()
    
    var enteredPin by remember { mutableStateOf("") }
    var showForgotDialog by remember { mutableStateOf(false) }
    var securityAnswerInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC)), // Premium Light Slate-50 background
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color(0xFFE2E8F0), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "App Locked",
                    tint = Color(0xFF0F172A),
                    modifier = Modifier.size(40.dp)
                )
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "CASHBOOK PRO",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    ),
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "App is secured with PIN lock",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF475569)
                )
            }
            
            // PIN Display (4 Circles)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                for (i in 0 until 4) {
                    val isFilled = i < enteredPin.length
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .border(2.dp, Color(0xFF0F172A), CircleShape)
                            .background(
                                if (isFilled) Color(0xFF0F172A) else Color.Transparent,
                                CircleShape
                            )
                    )
                }
            }
            
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Keypad
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.width(280.dp)
            ) {
                val keys = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("C", "0", "OK")
                )
                
                for (row in keys) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (key in row) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1.5f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        when (key) {
                                            "OK" -> GreenIn
                                            "C" -> Color(0xFFE2E8F0)
                                            else -> Color.White
                                        }
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (key == "OK" || key == "C") Color.Transparent else Color(0xFFCBD5E1),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        errorMessage = null
                                        when (key) {
                                            "C" -> {
                                                if (enteredPin.isNotEmpty()) {
                                                    enteredPin = enteredPin.dropLast(1)
                                                }
                                            }
                                            "OK" -> {
                                                if (viewModel.unlockApp(enteredPin)) {
                                                    onUnlockSuccess()
                                                } else {
                                                    errorMessage = "Incorrect PIN. Please try again."
                                                    enteredPin = ""
                                                }
                                            }
                                            else -> {
                                                if (enteredPin.length < 4) {
                                                    enteredPin += key
                                                    if (enteredPin.length == 4) {
                                                        // Auto check
                                                        if (viewModel.unlockApp(enteredPin)) {
                                                            onUnlockSuccess()
                                                        } else {
                                                            errorMessage = "Incorrect PIN. Please try again."
                                                            enteredPin = ""
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (key == "OK") Color.White else Color(0xFF0F172A)
                                )
                            }
                        }
                    }
                }
            }
            
            TextButton(
                onClick = { showForgotDialog = true },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = "Forgot PIN/Password?",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    
    // Forgot Password Dialog
    if (showForgotDialog) {
        AlertDialog(
            onDismissRequest = { showForgotDialog = false },
            title = {
                Text(
                    "Reset PIN / Forgot Password",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "To recover or reset your PIN, please answer your security question or contact customer support directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF475569)
                    )
                    
                    // Security Question Section
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Security Question:",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF1E293B)
                            )
                            Text(
                                text = securityQuestion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF0F172A)
                            )
                            
                            OutlinedTextField(
                                value = securityAnswerInput,
                                onValueChange = { securityAnswerInput = it },
                                placeholder = { Text("Your answer") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp)
                            )
                            
                            OutlinedTextField(
                                value = newPinInput,
                                onValueChange = { 
                                    if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                                        newPinInput = it
                                    }
                                },
                                placeholder = { Text("New 4-digit PIN") },
                                label = { Text("Set New PIN") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp)
                            )
                            
                            Button(
                                onClick = {
                                    if (securityAnswerInput.isBlank() || newPinInput.length < 4) {
                                        Toast.makeText(context, "Please answer and set a valid 4-digit PIN", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val success = viewModel.resetPasscodeViaSecurityAnswer(securityAnswerInput, newPinInput)
                                        if (success) {
                                            Toast.makeText(context, "PIN successfully reset & unlocked!", Toast.LENGTH_LONG).show()
                                            showForgotDialog = false
                                        } else {
                                            Toast.makeText(context, "Incorrect security answer!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Verify & Reset PIN")
                            }
                        }
                    }
                    
                    // Support Contact Section
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE2E8F0))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Customer Support Assistance:",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF1E293B)
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Call/WhatsApp Support Button
                                Button(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            data = android.net.Uri.parse("https://wa.me/923337998373?text=Hello%20Support%20I%20forgot%20my%20Cashbook%20Pro%20passcode")
                                        }
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("WhatsApp", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                
                                // Email Support Button
                                Button(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                                            data = android.net.Uri.parse("mailto:Rbmengal@live.com")
                                            putExtra(Intent.EXTRA_SUBJECT, "Forgot Passcode - Cashbook Pro")
                                            putExtra(Intent.EXTRA_TEXT, "Hello, I forgot my Cashbook Pro passcode. Please help me recover it.")
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "No email client found!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Email Support", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showForgotDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockSettingsDialog(
    viewModel: LedgerViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isAppLockEnabled by viewModel.isAppLockEnabled.collectAsStateWithLifecycle()
    
    var pinVal by remember { mutableStateOf("") }
    var securityAnswerVal by remember { mutableStateOf("") }
    var selectedQuestion by remember { mutableStateOf("What was your first business name?") }
    
    val questions = listOf(
        "What was your first business name?",
        "What is your mother's maiden name?",
        "What was the name of your first school?",
        "What is your favorite city?"
    )
    
    var showDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "App PIN Lock Settings",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Secure your financial ledgers and cashbooks by enabling a 4-digit PIN lock at startup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475569)
                )

                if (isAppLockEnabled) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)) // Very light mint green
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Verified, contentDescription = null, tint = GreenIn)
                                Text("PIN Lock is ACTIVE", fontWeight = FontWeight.Bold, color = Color(0xFF065F46))
                            }
                            
                            Button(
                                onClick = {
                                    viewModel.disableAppLock()
                                    Toast.makeText(context, "PIN Lock is now deactivated.", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Deactivate PIN Lock", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = pinVal,
                        onValueChange = { 
                            if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                                pinVal = it
                            }
                        },
                        label = { Text("Enter 4-Digit PIN") },
                        placeholder = { Text("e.g. 1234") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )

                    // Security Question Dropdown
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedQuestion,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Security Question") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { showDropdown = !showDropdown }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Select question")
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                        DropdownMenu(
                            expanded = showDropdown,
                            onDismissRequest = { showDropdown = false },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            questions.forEach { q ->
                                DropdownMenuItem(
                                    text = { Text(q) },
                                    onClick = {
                                        selectedQuestion = q
                                        showDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = securityAnswerVal,
                        onValueChange = { securityAnswerVal = it },
                        label = { Text("Security Answer") },
                        placeholder = { Text("Provide recovery answer") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )

                    Button(
                        onClick = {
                            if (pinVal.length < 4 || securityAnswerVal.isBlank()) {
                                Toast.makeText(context, "Please enter a 4-digit PIN and provide security answer", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.enableAppLock(pinVal, selectedQuestion, securityAnswerVal)
                                Toast.makeText(context, "PIN lock successfully activated!", Toast.LENGTH_LONG).show()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Enable Secure PIN Lock", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

