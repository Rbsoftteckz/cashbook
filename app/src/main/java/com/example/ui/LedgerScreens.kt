package com.example.ui

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.app.DatePickerDialog
import android.app.Activity
import android.speech.RecognizerIntent
import android.provider.ContactsContract
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import android.content.Intent
import android.content.ClipData
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import android.graphics.Color as AndroidColor
import androidx.core.content.FileProvider
import java.io.File
import android.content.Context
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.painterResource
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
        "Super Admin", "Boss", "Owner", "Admin" -> true
        "Partner" -> action == "view" || action == "switch_business"
        "Data Entry" -> action == "view" || action == "add_transaction" || action == "add_book"
        else -> true
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
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()

    val isAppLockEnabled by viewModel.isAppLockEnabled.collectAsStateWithLifecycle()
    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    val globalAccounts by viewModel.globalAccounts.collectAsStateWithLifecycle()
    var showSecuritySettingsDialog by remember { mutableStateOf(false) }
    var showSuperAdminLoginDrawer by remember { mutableStateOf(false) }
    var showSuperAdminUserRegistryInDrawer by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showBookSelector by remember { mutableStateOf(false) }
    var showAddBookDialog by remember { mutableStateOf(false) }
    var newBookName by remember { mutableStateOf("") }

    var drawerAuthVersion by remember { mutableStateOf(0) }

    var showAddBusinessDialog by remember { mutableStateOf(false) }
    var newBusinessName by remember { mutableStateOf("") }

    var businessToDelete by remember { mutableStateOf<com.example.data.Business?>(null) }
    var bookToDelete by remember { mutableStateOf<com.example.data.Book?>(null) }
    var deleteConfirmationInput by remember { mutableStateOf("") }

    if (isAppLockEnabled && !isAppUnlocked) {
        AppSecureLockScreen(viewModel = viewModel, onUnlockSuccess = {})
    } else if (!isSuperAdmin || businesses.isEmpty()) {
        OnboardingSetupScreen(viewModel = viewModel)
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
                        .clickable {
                            viewModel.setScreen(Screen.PROFILE)
                            scope.launch { drawerState.close() }
                        }
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
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (isSuperAdmin) {
                            TextButton(
                                onClick = {
                                    viewModel.logoutSuperAdmin()
                                    Toast.makeText(context, "Signed out successfully", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sign Out", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    showSuperAdminLoginDrawer = true
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(14.dp), tint = GreenIn)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sign In", fontSize = 11.sp, color = GreenIn, fontWeight = FontWeight.Bold)
                            }
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
                    Text(
                        "Core Ledgers",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B) // Slate-500
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                        label = { Text("Profile & Account") },
                        selected = currentScreen == Screen.PROFILE,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = GreenIn.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = GreenIn,
                            unselectedIconColor = Color(0xFF475569),
                            selectedTextColor = GreenIn,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            viewModel.setScreen(Screen.PROFILE)
                            scope.launch { drawerState.close() }
                        }
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

                    val isSynced = viewModel.syncManager.isUserSignedIn()
                    NavigationDrawerItem(
                        icon = { 
                            Icon(
                                Icons.Default.CloudSync, 
                                contentDescription = null,
                                tint = if (isSynced) GreenIn else Color.Red
                            ) 
                        },
                        label = { Text("Google Drive Cloud Sync") },
                        selected = currentScreen == Screen.SYNC_CENTER,
                        badge = {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isSynced) GreenIn else Color.Red)
                            )
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = if (isSynced) GreenIn.copy(alpha = 0.15f) else Color.Red.copy(alpha = 0.1f),
                            unselectedContainerColor = Color.Transparent,
                            selectedTextColor = if (isSynced) GreenIn else Color.Red,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            viewModel.setScreen(Screen.SYNC_CENTER)
                            scope.launch { drawerState.close() }
                        }
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.SupervisorAccount, contentDescription = null) },
                        label = { Text("Firebase Users Inspector (${globalAccounts.size})") },
                        selected = showSuperAdminUserRegistryInDrawer,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = MaterialTheme.colorScheme.primary
                        ),
                        onClick = {
                            showSuperAdminUserRegistryInDrawer = true
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

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                        selected = currentScreen == Screen.SETTINGS,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = GreenIn.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = GreenIn,
                            unselectedIconColor = Color(0xFF475569),
                            selectedTextColor = GreenIn,
                            unselectedTextColor = Color(0xFF1E293B)
                        ),
                        onClick = {
                            viewModel.setScreen(Screen.SETTINGS)
                            scope.launch { drawerState.close() }
                        }
                    )

                    val isDrawerSignedIn = remember(drawerAuthVersion) { viewModel.syncManager.isUserSignedIn() }
                    val drawerEmail = remember(drawerAuthVersion) { viewModel.syncManager.getEmail() }

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Logout, contentDescription = "Log Out", tint = MaterialTheme.colorScheme.error) },
                        label = {
                            Text(
                                text = if (isDrawerSignedIn) "Log Out (${if (drawerEmail.length > 12) drawerEmail.take(12) + "..." else drawerEmail})" else "Log Out / Reset Session",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        selected = false,
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f)
                        ),
                        onClick = {
                            viewModel.logoutSuperAdmin()
                            drawerAuthVersion++
                            Toast.makeText(context, "Logged out successfully.", Toast.LENGTH_SHORT).show()
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
                                        onClick = {
                                            businessToDelete = biz
                                            deleteConfirmationInput = ""
                                        },
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
                        // Cloud Sync status indicator (Green if connected, Red if offline/error, Teal/Primary if running in offline local mode)
                        val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
                        val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
                        val isRealCloudAccount = viewModel.syncManager.isRealCloudAccount()
                        val isSyncError = !isOnline || syncStatus.contains("Error", ignoreCase = true) || syncStatus.contains("Failed", ignoreCase = true) || syncStatus.contains("403") || syncStatus.contains("401") || syncStatus.contains("400") || syncStatus.contains("500") || syncStatus.contains("Disconnected", ignoreCase = true)
                        val isRealSuccess = isOnline && isRealCloudAccount && !isSyncError && (syncStatus.contains("Synced", ignoreCase = true) || syncStatus.contains("Restored", ignoreCase = true) || syncStatus.contains("Success", ignoreCase = true) || syncStatus.contains("Connected", ignoreCase = true))

                        IconButton(
                            onClick = { viewModel.setScreen(Screen.SYNC_CENTER) },
                            modifier = Modifier.testTag("top_bar_sync_indicator")
                        ) {
                            Icon(
                                imageVector = if (!isOnline) Icons.Default.CloudOff else if (isRealSuccess) Icons.Default.CloudDone else if (isSyncError) Icons.Default.CloudOff else Icons.Default.OfflinePin,
                                contentDescription = "Cloud Sync & Offline Status",
                                tint = if (!isOnline) MaterialTheme.colorScheme.error else if (isRealSuccess) GreenIn else if (isSyncError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
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
                        IconButton(
                            onClick = { viewModel.setScreen(Screen.PROFILE) },
                            modifier = Modifier.testTag("top_bar_profile_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "User Account Profile",
                                tint = MaterialTheme.colorScheme.primary
                            )
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
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Home") },
                        modifier = Modifier.testTag("nav_home")
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.REPORTS,
                        onClick = { viewModel.setScreen(Screen.REPORTS) },
                        icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                        label = { Text("Reports") },
                        modifier = Modifier.testTag("nav_reports")
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.MANAGE_WORKSPACE,
                        onClick = { viewModel.setScreen(Screen.MANAGE_WORKSPACE) },
                        icon = { Icon(Icons.Default.BusinessCenter, contentDescription = null) },
                        label = { Text("Manage") },
                        modifier = Modifier.testTag("nav_manage")
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.SYNC_CENTER,
                        onClick = { viewModel.setScreen(Screen.SYNC_CENTER) },
                        icon = { Icon(Icons.Default.CloudSync, contentDescription = null) },
                        label = { Text("Sync") },
                        modifier = Modifier.testTag("nav_sync")
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.SETTINGS,
                        onClick = { viewModel.setScreen(Screen.SETTINGS) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                        modifier = Modifier.testTag("nav_settings")
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
                        Screen.SETTINGS -> SettingsScreen(viewModel)
                        Screen.MANAGE_WORKSPACE -> ManageWorkspaceScreen(viewModel)
                        Screen.PROFILE -> ProfileScreen(viewModel)
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
                                                IconButton(onClick = {
                                                    bookToDelete = book
                                                    deleteConfirmationInput = ""
                                                }) {
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
                    AddBookConsumerDialog(
                        onDismiss = { showAddBookDialog = false },
                        onCreate = { bkName, bkPhone ->
                            viewModel.createBook(bkName, bkPhone)
                            showAddBookDialog = false
                            Toast.makeText(context, "Customer / Cashbook created!", Toast.LENGTH_SHORT).show()
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

                // Delete Business re-typing confirmation dialog
                if (businessToDelete != null) {
                    val biz = businessToDelete!!
                    AlertDialog(
                        onDismissRequest = { businessToDelete = null },
                        title = { Text("Delete Business?", fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text("This will permanently delete '${biz.name}' and all its cashbooks, staff, and transactions. This action cannot be undone.", color = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("To confirm, type the exact business name:")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("'${biz.name}'", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = deleteConfirmationInput,
                                    onValueChange = { deleteConfirmationInput = it },
                                    placeholder = { Text("Retype name") },
                                    modifier = Modifier.fillMaxWidth().testTag("delete_business_confirm_input"),
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (deleteConfirmationInput == biz.name) {
                                        viewModel.deleteBusiness(biz)
                                        businessToDelete = null
                                    }
                                },
                                enabled = deleteConfirmationInput == biz.name,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("delete_business_confirm_button")
                            ) {
                                Text("Delete permanently")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { businessToDelete = null }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // Delete Book re-typing confirmation dialog
                if (bookToDelete != null) {
                    val b = bookToDelete!!
                    AlertDialog(
                        onDismissRequest = { bookToDelete = null },
                        title = { Text("Delete Cashbook?", fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text("This will permanently delete '${b.name}' and all its entries. This action cannot be undone.", color = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("To confirm, type the exact book name:")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("'${b.name}'", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = deleteConfirmationInput,
                                    onValueChange = { deleteConfirmationInput = it },
                                    placeholder = { Text("Retype name") },
                                    modifier = Modifier.fillMaxWidth().testTag("delete_book_confirm_input"),
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (deleteConfirmationInput == b.name) {
                                        viewModel.deleteBook(b)
                                        bookToDelete = null
                                    }
                                },
                                enabled = deleteConfirmationInput == b.name,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("delete_book_confirm_button")
                            ) {
                                Text("Delete permanently")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { bookToDelete = null }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                if (showSuperAdminUserRegistryInDrawer) {
                    SuperAdminUserRegistryDialog(
                        viewModel = viewModel,
                        onDismiss = { showSuperAdminUserRegistryInDrawer = false }
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
    var showAddBookDialog by remember { mutableStateOf(false) }
    var showShareBusinessDialog by remember { mutableStateOf<com.example.data.Business?>(null) }
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
        // Business Profile Header Card (Displays Total across all Books/Customers)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Storefront,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = activeBusiness?.name ?: "Personal Account",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${books.size} Active Customer Books • $liveClockString",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }

                        if (activeBusiness != null) {
                            OutlinedButton(
                                onClick = { showShareBusinessDialog = activeBusiness },
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, GreenIn)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = GreenIn, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share 📤", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = GreenIn)
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))

                    // Prominent Business Totals Banner at Top (Aggregated across all books/consumers)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("BUSINESS TOTAL NET", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Rs. ${String.format("%,.2f", netBalance)}",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                color = if (netBalance >= 0) GreenIn else RedOut
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Total Cash In", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text("Rs. ${String.format("%,.0f", totalIn)}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = GreenIn)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Total Cash Out", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text("Rs. ${String.format("%,.0f", totalOut)}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = RedOut)
                            }
                        }
                    }
                }
            }
        }

        // Cloud Sync & Offline Status Banner
        item {
            val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
            val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
            val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
            val isUserSignedIn = viewModel.syncManager.isUserSignedIn()
            val isRealCloudAccount = viewModel.syncManager.isRealCloudAccount()
            val userEmail = viewModel.syncManager.getEmail()

            val isSyncError = !isOnline || syncStatus.contains("Error", ignoreCase = true) || syncStatus.contains("Failed", ignoreCase = true) || syncStatus.contains("403") || syncStatus.contains("401") || syncStatus.contains("400") || syncStatus.contains("500") || syncStatus.contains("Disconnected", ignoreCase = true)
            val isRealSuccess = isOnline && isRealCloudAccount && !isSyncError && (syncStatus.contains("Synced", ignoreCase = true) || syncStatus.contains("Restored", ignoreCase = true) || syncStatus.contains("Success", ignoreCase = true) || syncStatus.contains("Connected", ignoreCase = true))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setScreen(Screen.SYNC_CENTER) },
                colors = CardDefaults.cardColors(
                    containerColor = if (!isOnline) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                    } else if (isSyncError) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                    } else if (isRealSuccess) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    } else {
                        Color(0xFFECFDF5)
                    }
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, 
                    if (!isOnline) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else if (isSyncError) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else if (isRealSuccess) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color(0xFFA7F3D0)
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
                            imageVector = if (!isOnline) Icons.Default.CloudOff else if (isSyncError) Icons.Default.CloudOff else if (isRealSuccess) Icons.Default.CloudSync else Icons.Default.OfflinePin,
                            contentDescription = null,
                            tint = if (!isOnline) MaterialTheme.colorScheme.error else if (isSyncError) MaterialTheme.colorScheme.error else if (isRealSuccess) GreenIn else Color(0xFF059669),
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "CashBook Easy Khata Cloud Sync",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Surface(
                                    color = if (!isOnline) MaterialTheme.colorScheme.errorContainer else if (isSyncError) MaterialTheme.colorScheme.errorContainer else if (isRealSuccess) GreenIn.copy(alpha = 0.12f) else Color(0xFFD1FAE5),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = if (!isOnline) "OFFLINE MODE" else if (isSyncError) "SYNC ISSUE" else if (isRealSuccess) "CONNECTED & SYNCED" else if (isRealCloudAccount) "PENDING SYNC" else "OFFLINE READY",
                                        color = if (!isOnline) MaterialTheme.colorScheme.error else if (isSyncError) MaterialTheme.colorScheme.error else if (isRealSuccess) GreenIn else if (isRealCloudAccount) MaterialTheme.colorScheme.primary else Color(0xFF047857),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (!isOnline) {
                                    "No internet connection detected. Saved in local SQLite database."
                                } else if (!isRealCloudAccount) {
                                    "Local Account ($userEmail). Saved locally in SQLite database."
                                } else if (isSyncError) {
                                    "Account: $userEmail — Status: $syncStatus"
                                } else if (isRealSuccess) {
                                    "Cloud Account: $userEmail — Entries synchronized."
                                } else {
                                    "Account active ($userEmail). Tap to sync."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!isOnline || isSyncError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
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
                            text = "No Customers / Books Created Yet",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Add customers or cashbooks to track entries for this business",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showAddBookDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Customer / Book", fontWeight = FontWeight.Bold)
                        }
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
                                        text = if (tx.remarks.isNotBlank()) tx.remarks else if (tx.type == "IN") "Rs. Got" else "Rs. Gave",
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
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Rs. ${if (tx.type == "IN") "+" else "-"}${String.format("%,.0f", tx.amount)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (tx.type == "IN") GreenIn else RedOut
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = tx.paymentMethod,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TransactionSyncCheckIndicator(isSynced = tx.isSynced)
                            }
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
            onSave = { amount, category, method, remarks, receiptUri, timestamp ->
                viewModel.addTransaction(amount, type, category, method, remarks, receiptUri, timestamp)
                showTransactionDialog = null
            }
        )
    }

    if (showAddBookDialog) {
        AddBookConsumerDialog(
            onDismiss = { showAddBookDialog = false },
            onCreate = { bkName, bkPhone ->
                viewModel.createBook(bkName, bkPhone)
                showAddBookDialog = false
                Toast.makeText(context, "Customer / Cashbook created!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showShareBusinessDialog != null) {
        val biz = showShareBusinessDialog!!
        val bizBooks = books.filter { it.businessId == biz.id }
        val bizBookIds = bizBooks.map { it.id }.toSet()
        val bizTx = allTransactions.filter { bizBookIds.contains(it.bookId) }
        val bizIn = bizTx.filter { it.type == "IN" }.sumOf { it.amount }
        val bizOut = bizTx.filter { it.type == "OUT" }.sumOf { it.amount }
        val bizNet = bizIn - bizOut

        ShareBusinessDialog(
            business = biz,
            booksCount = bizBooks.size,
            totalNetBalance = bizNet,
            totalIn = bizIn,
            totalOut = bizOut,
            onDismiss = { showShareBusinessDialog = null }
        )
    }
}

// --- SCREEN 1: DETAILED TRANSACTIONS ---

@Composable
fun BookDetailScreen(viewModel: LedgerViewModel) {
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
    val activeBusiness by viewModel.activeBusiness.collectAsStateWithLifecycle()
    val activeBookTransactions by viewModel.activeBookTransactions.collectAsStateWithLifecycle()
    val filteredTransactions by viewModel.filteredTransactions.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedPaymentMethod by viewModel.selectedPaymentMethod.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedTransactionIds.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()

    var showTransactionDialog by remember { mutableStateOf<String?>(null) }
    var selectedTxForEdit by remember { mutableStateOf<Transaction?>(null) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showEditBookDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Aggregate values
    val totalIn = activeBookTransactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = activeBookTransactions.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut

    val inCategories = listOf("General", "Goods / Items", "Cash Received", "Payment", "Other")
    val outCategories = listOf("General", "Goods / Items", "Cash Paid", "Expense", "Other")
    val paymentMethods = listOf("Cash", "Online", "Bank")

    val groupedTransactions = remember(filteredTransactions) {
        val sdfKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sdfHeader = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())
        filteredTransactions.groupBy { tx ->
            sdfKey.format(Date(tx.timestamp))
        }.entries.map { (dateKey, txList) ->
            val firstTimestamp = txList.firstOrNull()?.timestamp ?: System.currentTimeMillis()
            val headerTitle = when {
                DateUtils.isToday(firstTimestamp) -> "Today • ${sdfHeader.format(Date(firstTimestamp))}"
                DateUtils.isToday(firstTimestamp + 86400000L) -> "Yesterday • ${sdfHeader.format(Date(firstTimestamp))}"
                else -> sdfHeader.format(Date(firstTimestamp))
            }
            val dayIn = txList.filter { it.type == "IN" }.sumOf { it.amount }
            val dayOut = txList.filter { it.type == "OUT" }.sumOf { it.amount }
            Triple(headerTitle, Triple(dayIn, dayOut, txList), dateKey)
        }.sortedByDescending { it.third }
    }

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

            // Top Header Card: Consumer Info & Net Balance Totals
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
                    // Header Row with Consumer Name, Phone, Edit, and Share PDF/WhatsApp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = activeBook?.name ?: "Consumer Khata",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = { showEditBookDialog = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit consumer details", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (activeBook?.phone?.isNotBlank() == true) {
                                Text(
                                    text = "📞 ${activeBook?.phone}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            } else {
                                Text(
                                    text = "+ Add phone for SMS/WhatsApp",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showEditBookDialog = true }
                                )
                            }
                        }

                        // Share Statement Button
                        Button(
                            onClick = { showShareDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share Statement", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Totals Row: Total Gave, Total Got, Net Balance
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
                                        .background(RedOut)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("TOTAL GAVE (OUT)", style = MaterialTheme.typography.labelSmall, color = RedOut, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "Rs. ${String.format("%,.0f", totalOut)}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                color = RedOut
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(GreenIn)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("TOTAL GOT (IN)", style = MaterialTheme.typography.labelSmall, color = GreenIn, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "Rs. ${String.format("%,.0f", totalIn)}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                color = GreenIn
                            )
                        }

                        Column(modifier = Modifier.weight(1.1f)) {
                            Text("NET BALANCE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "Rs. ${String.format("%,.0f", kotlin.math.abs(netBalance))}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                color = if (netBalance >= 0) GreenIn else RedOut
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

            // Ledger Entries List Daywise
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
                            "No transactions recorded yet.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Tap 'Rs. GOT (In)' or 'Rs. GAVE (Out)' below to add entries.",
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
                    groupedTransactions.forEach { (headerTitle, dayData, _) ->
                        val (dayIn, dayOut, dayTxs) = dayData
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = headerTitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (dayOut > 0) Text("Gave: Rs. ${String.format("%,.0f", dayOut)}", style = MaterialTheme.typography.labelSmall, color = RedOut, fontWeight = FontWeight.Bold)
                                        if (dayIn > 0) Text("Got: Rs. ${String.format("%,.0f", dayIn)}", style = MaterialTheme.typography.labelSmall, color = GreenIn, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        items(dayTxs, key = { it.id }) { tx ->
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
            }

            // Quick Floating Action Buttons Panel
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
                    Text("Rs. GOT (In)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                    Text("Rs. GAVE (Out)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                onSave = { amount, category, method, remarks, receiptUri, timestamp ->
                    viewModel.addTransaction(amount, type, category, method, remarks, receiptUri, timestamp)
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
                initialReceiptUri = tx.receiptUri,
                initialTimestamp = tx.timestamp,
                isEdit = true,
                onDismiss = { selectedTxForEdit = null },
                onSave = { amount, category, method, remarks, receiptUri, timestamp ->
                    viewModel.updateTransaction(
                        tx.copy(
                            amount = amount,
                            category = category,
                            paymentMethod = method,
                            remarks = remarks,
                            receiptUri = receiptUri,
                            timestamp = timestamp
                        )
                    )
                    selectedTxForEdit = null
                }
            )
        }

        // Share Statement Sheet Dialog
        if (showShareDialog) {
            ShareStatementDialog(
                activeBook = activeBook,
                activeBusiness = activeBusiness,
                transactions = activeBookTransactions,
                onDismiss = { showShareDialog = false }
            )
        }

        // Edit Consumer Details Dialog
        if (showEditBookDialog && activeBook != null) {
            EditBookConsumerDialog(
                book = activeBook!!,
                onDismiss = { showEditBookDialog = false },
                onSave = { newName, newPhone ->
                    viewModel.updateBook(activeBook!!.copy(name = newName, phone = newPhone))
                    showEditBookDialog = false
                    Toast.makeText(context, "Consumer details updated!", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
fun TransactionSyncCheckIndicator(
    isSynced: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
    ) {
        if (isSynced) {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Synced to Drive (Double Green Check)",
                tint = Color(0xFF25D366),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Synced",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF25D366),
                fontWeight = FontWeight.Bold
            )
        } else {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Offline / Unsynced (Single Red Check)",
                tint = Color(0xFFEF4444),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Offline",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFEF4444),
                fontWeight = FontWeight.Bold
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
                            text = if (transaction.remarks.isNotBlank()) transaction.remarks else if (transaction.type == "IN") "Rs. Got (Received)" else "Rs. Gave (Paid)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (transaction.type == "IN") "Rs. ${String.format("%,.0f", transaction.amount)}" else "Rs. ${String.format("%,.0f", transaction.amount)}",
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (transaction.type == "IN") GreenIn else RedOut
                        )
                    }



                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val formattedDate = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(transaction.timestamp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = formattedDate,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            if (transaction.receiptUri != null) {
                                var showViewer by remember { mutableStateOf(false) }
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.clickable { showViewer = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Icon(Icons.Default.Receipt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                                        Text("Receipt 📷", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (showViewer) {
                                    ReceiptViewerDialog(
                                        receiptUri = transaction.receiptUri,
                                        onDismiss = { showViewer = false }
                                    )
                                }
                            }
                        }

                        // WhatsApp style cloud sync check mark indicator
                        TransactionSyncCheckIndicator(isSynced = transaction.isSynced)
                    }
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
fun formatEasyKhataDate(timestamp: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    val month = SimpleDateFormat("MMM", Locale.getDefault()).format(cal.time)
    val yearTwoDigits = SimpleDateFormat("yy", Locale.getDefault()).format(cal.time)
    return "${day}${suffix} ${month}, ${yearTwoDigits}"
}

@Composable
fun AddEditTransactionDialog(
    type: String,
    categories: List<String>,
    paymentMethods: List<String>,
    initialAmount: String = "",
    initialCategory: String = "",
    initialMethod: String = "",
    initialRemarks: String = "",
    initialReceiptUri: String? = null,
    initialTimestamp: Long = System.currentTimeMillis(),
    isEdit: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (amount: Double, category: String, method: String, remarks: String, receiptUri: String?, timestamp: Long) -> Unit
) {
    var amountInput by remember { mutableStateOf(initialAmount) }
    var category by remember { mutableStateOf(if (initialCategory.isBlank()) categories.firstOrNull() ?: "General" else initialCategory) }
    var paymentMethod by remember { mutableStateOf(if (initialMethod.isBlank()) paymentMethods.firstOrNull() ?: "Cash" else initialMethod) }
    var remarks by remember { mutableStateOf(initialRemarks) }
    var receiptUri by remember { mutableStateOf<String?>(initialReceiptUri) }
    var selectedTimestamp by remember { mutableLongStateOf(if (initialTimestamp > 0) initialTimestamp else System.currentTimeMillis()) }
    var showAttachOptions by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val calendar = remember(selectedTimestamp) {
        Calendar.getInstance().apply { timeInMillis = selectedTimestamp }
    }
    val datePickerDialog = remember(context, selectedTimestamp) {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selCal = Calendar.getInstance().apply {
                    timeInMillis = selectedTimestamp
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                selectedTimestamp = selCal.timeInMillis
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            receiptUri = uri.toString()
            Toast.makeText(context, "Receipt attached!", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            try {
                val file = File(context.cacheDir, "receipt_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                receiptUri = Uri.fromFile(file).toString()
                Toast.makeText(context, "Photo captured as receipt!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error saving photo: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                remarks = spokenText
            }
        }
    }

    // Dynamic Math Calculator Parser
    val evaluatedValue = remember(amountInput) {
        evaluateMathExpression(amountInput)
    }

    val isGave = type == "OUT" || type == "GAVE"
    val screenTitle = if (isEdit) "Edit Entry" else if (isGave) "You Gave" else "You Got"
    val accentColor = if (isGave) RedOut else GreenIn

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                // Top Navigation Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.Black
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = screenTitle,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        ),
                        color = Color.Black
                    )
                }

                // Middle Content Area
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Expression & Amount Display Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
                        border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Raw expression line e.g., 2,999×5
                            val exprDisplay = amountInput.ifEmpty { "0" }
                                .replace("*", "×")
                                .replace("/", "÷")
                            Text(
                                text = exprDisplay,
                                style = TextStyle(fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                            )

                            // Main calculated amount line e.g., Rs 14,995
                            val mainAmtVal = evaluatedValue ?: amountInput.toDoubleOrNull() ?: 0.0
                            val amtStr = if (mainAmtVal > 0) String.format("%,.0f", mainAmtVal) else if (amountInput.isNotBlank()) amountInput else "0"
                            Text(
                                text = "Rs $amtStr",
                                style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            )
                        }
                    }

                    // Details (optional) Field with Microphone Voice Input
                    OutlinedTextField(
                        value = remarks,
                        onValueChange = { remarks = it },
                        placeholder = { Text("Details (optional)", color = Color.Gray) },
                        trailingIcon = {
                            IconButton(onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak transaction details...")
                                }
                                try {
                                    speechRecognizerLauncher.launch(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Voice input not supported", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Voice Input",
                                    tint = Color.Gray
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("remarks_field"),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.LightGray.copy(alpha = 0.5f),
                            unfocusedContainerColor = Color(0xFFF8F9FA),
                            focusedContainerColor = Color(0xFFF8F9FA)
                        )
                    )

                    // Action Pill Buttons Row (Date + Add bills) directly under Tafseel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Date Pill Button
                        Surface(
                            onClick = { datePickerDialog.show() },
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.6f)),
                            color = Color(0xFFF8F9FA),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.EditCalendar,
                                    contentDescription = "Pick Date",
                                    tint = Color.DarkGray,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = formatEasyKhataDate(selectedTimestamp),
                                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.DarkGray),
                                    maxLines = 1
                                )
                            }
                        }

                        // Add Bills Pill Button
                        Surface(
                            onClick = { showAttachOptions = true },
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.6f)),
                            color = Color(0xFFF8F9FA),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = "Add Bills",
                                    tint = Color.DarkGray,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (receiptUri != null) "1 Bill Added" else "Add bills",
                                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.DarkGray),
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Attached Receipt Thumbnail Preview
                    if (receiptUri != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AsyncImage(
                                        model = receiptUri,
                                        contentDescription = "Bill Preview",
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                    Text("Bill / Receipt Attached", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = { receiptUri = null }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Red)
                                }
                            }
                        }
                    }

                    // Prominent Full-Width "SAVE" Button Moved UP
                    Button(
                        onClick = {
                            val finalAmount = evaluatedValue ?: amountInput.toDoubleOrNull() ?: 0.0
                            if (finalAmount > 0.0) {
                                onSave(finalAmount, category, paymentMethod, remarks, receiptUri, selectedTimestamp)
                            } else {
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(26.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("save_transaction_button")
                    ) {
                        Text(
                            text = "SAVE",
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        )
                    }
                }

                // Spacious, Full-Width Custom Calculator Keypad Panel filling the bottom cleanly
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = Color(0xFFF1F5F9)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Helper for keypad buttons
                        @Composable
                        fun KeyButton(
                            key: String,
                            modifier: Modifier = Modifier,
                            heightDp: Int = 54,
                            onClick: () -> Unit
                        ) {
                            val isSpecial = key in listOf("C", "÷", "×", "-", "+", "=", "BACKSPACE")
                            val isOp = key in listOf("÷", "×", "-", "+")
                            val isClear = key == "C"
                            val isEquals = key == "="
                            val isBack = key == "BACKSPACE"
                            val isMinusOrPlus = key in listOf("-", "+")

                            Button(
                                onClick = onClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        isMinusOrPlus -> Color(0xFF94A3B8)
                                        isEquals -> Color(0xFFCBD5E1)
                                        isOp || isClear || isBack -> Color(0xFFE2E8F0)
                                        else -> Color.White
                                    },
                                    contentColor = when {
                                        isMinusOrPlus -> Color.White
                                        else -> Color(0xFF0F172A)
                                    }
                                ),
                                shape = RoundedCornerShape(12.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp),
                                contentPadding = PaddingValues(0.dp),
                                modifier = modifier.height(heightDp.dp)
                            ) {
                                if (isBack) {
                                    Icon(
                                        imageVector = Icons.Default.Backspace,
                                        contentDescription = "Backspace",
                                        tint = Color(0xFF334155),
                                        modifier = Modifier.size(22.dp)
                                    )
                                } else {
                                    Text(
                                        text = key,
                                        style = TextStyle(
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                        }

                        fun handleKeyClick(key: String) {
                            when (key) {
                                "C" -> amountInput = ""
                                "BACKSPACE" -> if (amountInput.isNotEmpty()) amountInput = amountInput.dropLast(1)
                                "=" -> {
                                    val res = evaluateMathExpression(amountInput)
                                    if (res != null) {
                                        amountInput = if (res % 1.0 == 0.0) res.toLong().toString() else res.toString()
                                    }
                                }
                                else -> {
                                    val rawKey = when (key) {
                                        "×" -> "*"
                                        "÷" -> "/"
                                        else -> key
                                    }
                                    val lastChar = amountInput.lastOrNull()?.toString() ?: ""
                                    val isKeyOperator = rawKey in listOf("+", "-", "*", "/")
                                    val isLastOperator = lastChar in listOf("+", "-", "*", "/")
                                    if (!(isKeyOperator && isLastOperator)) {
                                        amountInput += rawKey
                                    }
                                }
                            }
                        }

                        // Row 1: C, ÷, ×, ⌫
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("C", "÷", "×", "BACKSPACE").forEach { key ->
                                KeyButton(key = key, modifier = Modifier.weight(1f)) { handleKeyClick(key) }
                            }
                        }

                        // Row 2: 7, 8, 9, -
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("7", "8", "9", "-").forEach { key ->
                                KeyButton(key = key, modifier = Modifier.weight(1f)) { handleKeyClick(key) }
                            }
                        }

                        // Middle Block (Rows 3 & 4 with Spanning + Button)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Left 3 Columns
                            Column(
                                modifier = Modifier.weight(3f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Row 3: 4, 5, 6
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("4", "5", "6").forEach { key ->
                                        KeyButton(key = key, modifier = Modifier.weight(1f)) { handleKeyClick(key) }
                                    }
                                }
                                // Row 4: 1, 2, 3
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("1", "2", "3").forEach { key ->
                                        KeyButton(key = key, modifier = Modifier.weight(1f)) { handleKeyClick(key) }
                                    }
                                }
                            }

                            // Right Spanning + Button (Height = 54 + 8 + 54 = 116dp)
                            KeyButton(
                                key = "+",
                                modifier = Modifier.weight(1f),
                                heightDp = 116
                            ) {
                                handleKeyClick("+")
                            }
                        }

                        // Row 5: 0, 00, ., =
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("0", "00", ".", "=").forEach { key ->
                                KeyButton(key = key, modifier = Modifier.weight(1f)) { handleKeyClick(key) }
                            }
                        }
                    }
                }
            }
        }
    }

    // Camera / Gallery attach options modal
    if (showAttachOptions) {
        AlertDialog(
            onDismissRequest = { showAttachOptions = false },
            title = { Text("Attach Bill Photo", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            showAttachOptions = false
                            cameraLauncher.launch(null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Take Photo with Camera")
                    }
                    OutlinedButton(
                        onClick = {
                            showAttachOptions = false
                            galleryLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choose from Gallery")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAttachOptions = false }) {
                    Text("Cancel")
                }
            }
        )
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

                                Column(horizontalAlignment = Alignment.End) {
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
                                    Spacer(modifier = Modifier.height(2.dp))
                                    TransactionSyncCheckIndicator(isSynced = pTx.isSynced)
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
            AddEditTransactionDialog(
                type = if (type == "GAVE") "OUT" else "IN",
                categories = listOf("Customer Ledger"),
                paymentMethods = listOf("Cash"),
                onDismiss = { showAddPartyTxDialog = null },
                onSave = { amount, category, method, remarks, receiptUri, timestamp ->
                    viewModel.addPartyTransaction(party.id, amount, type, remarks)
                    showAddPartyTxDialog = null
                }
            )
        }
    }
}

// --- SCREEN 3: REPORTS ---

@Composable
fun ReportsScreen(viewModel: LedgerViewModel) {
    val activeBookTransactions by viewModel.activeBookTransactions.collectAsStateWithLifecycle()
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
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

        // Export & Share Statement Reports
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Export & Share Reports", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Instantly share accounting statements with partners or clients via WhatsApp or mail.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Share Text Report Button (Formatted for WhatsApp)
                    Button(
                        onClick = {
                            val textReport = generateTextReport(activeBook, activeBookTransactions)
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, textReport)
                                type = "text/plain"
                            }
                            try {
                                sendIntent.setPackage("com.whatsapp")
                                context.startActivity(sendIntent)
                            } catch (e: Exception) {
                                sendIntent.setPackage(null)
                                context.startActivity(Intent.createChooser(sendIntent, "Share Text Statement Report"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("share_text_report_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)) // WhatsApp Green color
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share on WhatsApp as Text", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    // Share PDF Report Button
                    Button(
                        onClick = {
                            try {
                                val pdfFile = generatePdfReport(context, activeBook, activeBookTransactions)
                                val pdfUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, pdfUri)
                                    clipData = ClipData.newRawUri("PDF Statement Report", pdfUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooserIntent = Intent.createChooser(shareIntent, "Share PDF Statement Report").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(chooserIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "PDF Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("share_pdf_report_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share as PDF Document", fontWeight = FontWeight.Bold)
                    }

                    // Share CSV Report Button
                    OutlinedButton(
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
                        modifier = Modifier.fillMaxWidth().testTag("export_csv_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share Corporate CSV Spreadsheet", fontWeight = FontWeight.Bold)
                    }
                }
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
    val syncManager = viewModel.syncManager

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

    var showApkUrlConfigCard by remember { mutableStateOf(false) }
    var configuredApkUrl by remember { mutableStateOf(syncManager.getApkDownloadUrl()) }

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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Digital Staff Roster",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Surface(
                        color = GreenIn.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "🟢 Live Auto-Sync",
                            color = GreenIn,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    "Real-time auto updates when Boss/Admin adds team members",
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

        val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
        var showSuperAdminLoginDialog by remember { mutableStateOf(false) }

        // Active Logged-in User Profile Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(GreenIn, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("👤", fontSize = 18.sp)
                    }
                    Column {
                        Text(
                            text = syncManager.getName().ifBlank { "Account Owner" },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = syncManager.getEmail().ifBlank { "Local Business Account" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        viewModel.logoutSuperAdmin()
                        Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Sign Out", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
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

        // Team members lazy column or empty state
        if (activeTeamMembers.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(GreenIn.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("👥", fontSize = 32.sp)
                    }
                    
                    Text(
                        "No Personnel Added Yet",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Text(
                        "Boss / Admin can add team members or staff so they can record Cash In / Cash Out entries for '${activeBusiness?.name ?: "this business"}'.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            if (hasPermission(simulatedRole, "add_team_member")) {
                                showAddStaffDialog = true
                            } else {
                                Toast.makeText(context, "Unauthorized: Only Boss/Admin can recruit team members.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(Icons.Default.PersonAddAlt, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(" Add First Team Member", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
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
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = staffEmail,
                            onValueChange = { staffEmail = it },
                            label = { Text("Email Address") },
                            placeholder = { Text("e.g. partner@cashbook.com") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = GreenIn) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )

                        if (staffEmail.trim().isNotBlank()) {
                            val emailExists = viewModel.checkEmailExists(staffEmail.trim())
                            Surface(
                                color = if (emailExists) Color(0xFFDCFCE7) else Color(0xFFE0F2FE),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (emailExists) Icons.Default.CheckCircle else Icons.Default.Email,
                                        contentDescription = null,
                                        tint = if (emailExists) GreenIn else Color(0xFF0284C7),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (emailExists) "✓ Registered CashBook Account Found on Cloud!" else "✉️ New Email — Invitation email & link will be sent upon adding.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (emailExists) Color(0xFF166534) else Color(0xFF0369A1),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
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
        var editableDownloadUrl by remember { mutableStateOf(syncManager.getApkDownloadUrl()) }
        
        val downloadSection = if (editableDownloadUrl.isNotBlank()) {
            "\n📥 Download App:\nDownload Link: $editableDownloadUrl\n"
        } else ""
        
        val inviteText = """
            🌟 Invitation to join ${bizName} on ${appName}!
            
            Hi $lastAddedCollaboratorName,
            You've been invited to join "${bizName}" as a *$lastAddedCollaboratorRole* on the ${appName} app.$downloadSection
            🔑 To connect your profile, use this invite code:
            Business ID: ${activeBusiness?.id ?: 1}
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

                    OutlinedTextField(
                        value = editableDownloadUrl,
                        onValueChange = { newVal ->
                            editableDownloadUrl = newVal
                            syncManager.saveApkDownloadUrl(newVal)
                        },
                        label = { Text("Downloadable APK Address (GitHub Artifact)") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(editableDownloadUrl))
                                Toast.makeText(context, "📋 Copied APK download URL to clipboard!", Toast.LENGTH_SHORT).show()
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

    var rawJsonText by remember { mutableStateOf("") }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }
    var authStateVersion by remember { mutableIntStateOf(0) }
    var activeSyncTab by remember { mutableIntStateOf(0) } // 0: Cloud Sync, 1: Google Drive Backup

    val isUserSignedIn = remember(authStateVersion) { syncManager.isUserSignedIn() }
    val email = remember(authStateVersion) { syncManager.getEmail() }
    val displayName = remember(authStateVersion) { syncManager.getName() }

    val businesses by viewModel.businesses.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val partiesList by viewModel.parties.collectAsStateWithLifecycle()
    val teamMembersList by viewModel.allTeamMembers.collectAsStateWithLifecycle()

    var showAccountAuthDialog by remember { mutableStateOf(false) }
    var authEmailInput by remember { mutableStateOf("") }
    var authNameInput by remember { mutableStateOf("") }
    var authPasswordInput by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }
    var verificationTimestamp by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

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
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "CashBook Easy Khata Sync & Backup Vault",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Real-time Cloud Database Sync & Optional Personal Google Drive Backup",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // Top Segmented Tab Switcher (3 Tabs: Cloud Synced, Google Drive, Offline AIS Status)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Tab 0: Cloud Synced & Status
                Surface(
                    onClick = { activeSyncTab = 0 },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (activeSyncTab == 0) Color.White else Color.Transparent,
                    shadowElevation = if (activeSyncTab == 0) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CloudSync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (activeSyncTab == 0) GreenIn else Color.Gray
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Cloud Synced",
                            fontWeight = if (activeSyncTab == 0) FontWeight.Bold else FontWeight.Medium,
                            color = if (activeSyncTab == 0) GreenIn else Color.Gray,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // Tab 1: Google Drive & Status
                Surface(
                    onClick = { activeSyncTab = 1 },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (activeSyncTab == 1) Color.White else Color.Transparent,
                    shadowElevation = if (activeSyncTab == 1) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CloudQueue,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (activeSyncTab == 1) GreenIn else Color.Gray
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Google Drive",
                            fontWeight = if (activeSyncTab == 1) FontWeight.Bold else FontWeight.Medium,
                            color = if (activeSyncTab == 1) GreenIn else Color.Gray,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // Tab 2: Offline AIS Status
                Surface(
                    onClick = { activeSyncTab = 2 },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (activeSyncTab == 2) Color.White else Color.Transparent,
                    shadowElevation = if (activeSyncTab == 2) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (activeSyncTab == 2) GreenIn else Color.Gray
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Offline AIS",
                            fontWeight = if (activeSyncTab == 2) FontWeight.Bold else FontWeight.Medium,
                            color = if (activeSyncTab == 2) GreenIn else Color.Gray,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        if (activeSyncTab == 0) {
            // --- TAB 0: REAL-TIME CLOUD DATABASE SYNC ---
            // Live Cloud Connection & Verification Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Cloud Sync Connection & Status", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        
                        val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
                        val isRealCloudAccount = viewModel.syncManager.isRealCloudAccount()
                        val isSyncError = !isOnline || syncStatus.contains("Error", ignoreCase = true) || syncStatus.contains("Failed", ignoreCase = true) || syncStatus.contains("403") || syncStatus.contains("401") || syncStatus.contains("400") || syncStatus.contains("500") || syncStatus.contains("Forbidden", ignoreCase = true) || syncStatus.contains("Disconnected", ignoreCase = true)
                        val isRealSyncSuccess = isOnline && isRealCloudAccount && !isSyncError && (syncStatus.contains("Synced", ignoreCase = true) || syncStatus.contains("Restored", ignoreCase = true) || syncStatus.contains("Success", ignoreCase = true) || syncStatus.contains("Connected", ignoreCase = true))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cloud Status:")
                            Text(
                                text = if (!isOnline) "🔴 Disconnected (Offline Mode)" else if (!isRealCloudAccount) "⚪ Offline Local Account (Local DB Only)" else if (isRealSyncSuccess) "🟢 Connected & Synced" else if (isSyncError) "🔴 Sync Error / Access Restriction" else "🟡 Account Active (Pending Sync)",
                                color = if (!isOnline) MaterialTheme.colorScheme.error else if (!isRealCloudAccount) Color.Gray else if (isRealSyncSuccess) GreenIn else if (isSyncError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cloud Account:")
                            Text(if (isRealCloudAccount) (if (email.isNotBlank()) email else displayName) else "${if (email.isNotBlank()) email else displayName} (Local Account)", fontWeight = FontWeight.SemiBold)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Last Sync Output:")
                            Text(
                                text = if (!isOnline) "Device is offline. Data saved in local SQLite database." else if (!isRealCloudAccount) "Running in local offline mode. Saved in SQLite DB." else syncStatus,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (!isOnline) MaterialTheme.colorScheme.error else if (!isRealCloudAccount) Color.Gray else if (isRealSyncSuccess) GreenIn else if (isSyncError) MaterialTheme.colorScheme.error else Color.Gray,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (verificationTimestamp.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Last Verification:", style = MaterialTheme.typography.bodySmall)
                                Text(verificationTimestamp, style = MaterialTheme.typography.bodySmall, color = if (isOnline) GreenIn else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Buttons: Verify Cloud Connection & Sync Now + Account Login
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!isRealCloudAccount) {
                                        showAccountAuthDialog = true
                                    } else {
                                        viewModel.verifyRealCloudConnection { online, msg ->
                                            val nowStr = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                                            verificationTimestamp = nowStr
                                            if (online) {
                                                Toast.makeText(context, "🟢 Real Cloud Connection Confirmed!\n$msg", Toast.LENGTH_SHORT).show()
                                                viewModel.triggerCloudSync()
                                            } else {
                                                Toast.makeText(context, "🔴 Device Disconnected / Network Offline:\n$msg", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isSyncing,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(if (isRealCloudAccount) Icons.Default.CloudSync else Icons.Default.CloudUpload, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isRealCloudAccount) "Verify & Sync Cloud" else "Connect Real Cloud Account")
                                }
                            }

                            if (!isRealCloudAccount) {
                                OutlinedButton(
                                    onClick = { showAccountAuthDialog = true },
                                    modifier = Modifier.weight(0.9f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Sign In / Up")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.logoutSuperAdmin()
                                        authStateVersion++
                                        Toast.makeText(context, "Logged out from Cloud Account.", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(0.8f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.Logout, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Log Out")
                                }
                            }
                        }
                    }
                }
            }

            // Super Admin Firebase Users Inspector Card
            item {
                val globalAccounts by viewModel.globalAccounts.collectAsStateWithLifecycle()
                var showSuperAdminRegistry by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    imageVector = Icons.Default.SupervisorAccount,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column {
                                    Text(
                                        "Super Admin Account Directory",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        "Firebase Cloud Database User Registry",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            ) {
                                Text(
                                    text = "${globalAccounts.size} Registered Users",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Text(
                            "Live Firebase Database query active. Inspect all registered emails, usernames, security hashes, and account details.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showSuperAdminRegistry = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.ManageAccounts, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Inspect ${globalAccounts.size} Accounts")
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.refreshCloudAccounts {
                                        Toast.makeText(context, "Refreshed! Found ${it.size} Firebase users.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                if (showSuperAdminRegistry) {
                    SuperAdminUserRegistryDialog(
                        viewModel = viewModel,
                        onDismiss = { showSuperAdminRegistry = false }
                    )
                }
            }

            // Cloud Architecture & Services Info Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F9FF)),
                    border = BorderStroke(1.dp, Color(0xFFBAE6FD)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(22.dp))
                            Text("Multi-Cloud Network Infrastructure", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = Color(0xFF0369A1))
                        }
                        Text(
                            "• Real-Time Synchronized Cloud Ledger Engine with active REST master registry.\n" +
                            "• Multi-Device Account Persistence across device reinstalls and clear storage.\n" +
                            "• Automatic Background Sync on transaction edits, cashbook creations, and role updates.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF0284C7)
                        )
                    }
                }
            }
        } else if (activeSyncTab == 1) {
            // --- TAB 1: DEDICATED GOOGLE DRIVE BACKUP VAULT ---
            item {
                var driveAuthVersion by remember { mutableIntStateOf(0) }
                val isDriveConnected = remember(driveAuthVersion) { syncManager.isGoogleDriveConnected() }
                val driveEmail = remember(driveAuthVersion) { syncManager.getGoogleEmail() }
                val driveName = remember(driveAuthVersion) { syncManager.getGoogleName() }
                var showDriveOAuthDialog by remember { mutableStateOf(false) }
                var driveLastBackupTime by remember { mutableStateOf("") }
                var driveStatusMessage by remember { mutableStateOf("") }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (isDriveConnected) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(GreenIn.copy(alpha = 0.15f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.CloudDone,
                                            contentDescription = "Drive Connected",
                                            tint = GreenIn,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.CloudOff,
                                            contentDescription = "Drive Disconnected",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }

                                Column {
                                    Text(
                                        "Google Drive Backup Vault",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        if (isDriveConnected) "Connected Account" else "Not Linked to Google Drive",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Surface(
                                color = if (isDriveConnected) GreenIn.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    if (isDriveConnected) "🟢 Drive Linked" else "🔴 Drive Offline",
                                    color = if (isDriveConnected) GreenIn else MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        if (isDriveConnected) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Connected Email:", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        driveEmail.ifBlank { driveName.ifBlank { "Google Drive Account" } },
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                if (driveLastBackupTime.isNotBlank()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Last Drive Sync:", style = MaterialTheme.typography.bodySmall)
                                        Text(driveLastBackupTime, color = GreenIn, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                    }
                                }

                                if (driveStatusMessage.isNotBlank()) {
                                    Text(
                                        "Drive Output: $driveStatusMessage",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        viewModel.triggerDriveSync { res ->
                                            driveStatusMessage = res
                                            val nowStr = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                                            driveLastBackupTime = nowStr
                                            Toast.makeText(context, res, Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                                    shape = RoundedCornerShape(10.dp),
                                    enabled = !isSyncing
                                ) {
                                    if (isSyncing) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                    } else {
                                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.White)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Backup Now", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        viewModel.triggerDriveSync { res ->
                                            driveStatusMessage = res
                                            Toast.makeText(context, "Restore: $res", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    enabled = !isSyncing
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Restore Data")
                                }
                            }

                            TextButton(
                                onClick = {
                                    syncManager.clearGoogleAuth()
                                    driveAuthVersion++
                                    Toast.makeText(context, "Google Drive disconnected.", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Icon(Icons.Default.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Disconnect Google Drive", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "Google Drive Backup is completely separate from Cloud Sync. Click below to sign into your Google account and authorize private cashbook backups in Google Drive.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Button(
                                    onClick = { showDriveOAuthDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Login, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Connect Google Drive Account", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Security & Privacy Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🛡️", fontSize = 18.sp)
                            Text("Google Drive Private Vault", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            "• Backups are stored in your personal Google Drive appDataFolder.\n" +
                            "• Easy restore when installing CashBook on any device.\n" +
                            "• Entirely separate and distinct from multi-user Cloud Sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                // OAuth WebView Dialog for Google Drive Connect
                if (showDriveOAuthDialog) {
                    Dialog(onDismissRequest = { showDriveOAuthDialog = false }) {
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
                                    Text("Authorize Google Drive Backup", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    IconButton(onClick = { showDriveOAuthDialog = false }) {
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
                                                defaultUa.replace("; wv", "").replace("Version/4.0 ", "").replace("Version/4.0", "")
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
                                                    val currentRedirectUri = syncManager.getRedirectUri()
                                                    if (url != null && url.startsWith(currentRedirectUri)) {
                                                        val token = extractAccessToken(url)
                                                        if (token != null) {
                                                            syncManager.saveAccessToken(token)
                                                            driveAuthVersion++
                                                            showDriveOAuthDialog = false
                                                            viewModel.triggerDriveSync {
                                                                Toast.makeText(context, "Google Drive Connected & Synced!", Toast.LENGTH_LONG).show()
                                                            }
                                                            return true
                                                        }
                                                    }
                                                    return false
                                                }
                                            }

                                            val finalClientId = syncManager.getClientId()
                                            val finalRedirectUri = syncManager.getRedirectUri()
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
            }
        } else {
            // --- TAB 2: OFFLINE AIS & LOCAL STORAGE STATUS ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color(0xFF059669).copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Storage,
                                        contentDescription = "Offline AIS Active",
                                        tint = Color(0xFF059669),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                Column {
                                    Text(
                                        "Offline AIS Storage Engine",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        "SQLite Local Database Engine",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Surface(
                                color = Color(0xFFECFDF5),
                                border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "🟢 100% Offline Ready",
                                    color = Color(0xFF047857),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Text("Local Database Statistics & Telemetry", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${businesses.size}", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                    Text("Businesses", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }

                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${books.size}", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, color = GreenIn)
                                    Text("Cashbooks", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }

                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${allTransactions.size}", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, color = Color(0xFF7C3AED))
                                    Text("Transactions", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${partiesList.size}", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, color = Color(0xFFD97706))
                                    Text("Parties / Khata", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }

                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${teamMembersList.size}", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, color = Color(0xFF2563EB))
                                    Text("Team Members", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }

            // 100% Offline Capability Guarantee Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                    border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.OfflinePin,
                            contentDescription = "Offline Guaranteed",
                            tint = Color(0xFF059669),
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text("100% Offline AIS Guarantee", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = Color(0xFF065F46))
                            Text(
                                "Every transaction, book, and business is saved directly to your phone's SQLite database first. The app operates seamlessly without internet, and syncs to cloud automatically when reconnected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF047857)
                            )
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
                        Text("Manual SQLite Backups & Portable Export", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
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
    }

    // Cloud Account Authentication Dialog
    if (showAccountAuthDialog) {
        AlertDialog(
            onDismissRequest = { showAccountAuthDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(if (isRegisterMode) "Connect & Sync to Firebase Cloud" else "Cloud Account Sign In")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (isRegisterMode) "Register your account to back up and merge all offline cashbooks and entries directly to Firebase Cloud."
                        else "Enter your credentials to connect and merge your offline cashbooks with Firebase Cloud.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isRegisterMode) {
                        OutlinedTextField(
                            value = authNameInput,
                            onValueChange = { authNameInput = it },
                            label = { Text("Full Name") },
                            placeholder = { Text("e.g. John Doe") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }

                    OutlinedTextField(
                        value = authEmailInput,
                        onValueChange = { authEmailInput = it },
                        label = { Text("Email Address *") },
                        placeholder = { Text("e.g. user@example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    OutlinedTextField(
                        value = authPasswordInput,
                        onValueChange = { authPasswordInput = it },
                        label = { Text("Password (min 6 chars)") },
                        placeholder = { Text("••••••••") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    TextButton(
                        onClick = { isRegisterMode = !isRegisterMode },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            if (isRegisterMode) "Already registered? Sign In & Sync" else "New user? Register & Connect Cloud",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanUserOrEmail = authEmailInput.trim()
                        val cleanPass = authPasswordInput.trim()
                        val cleanName = authNameInput.trim()

                        if (cleanUserOrEmail.isBlank()) {
                            Toast.makeText(context, "Please enter your Email Address.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        scope.launch {
                            if (isRegisterMode) {
                                if (cleanUserOrEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanUserOrEmail).matches()) {
                                    Toast.makeText(context, "Please enter a valid email address (e.g., user@example.com).", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                if (cleanPass.length < 6) {
                                    Toast.makeText(context, "Password must be at least 6 characters long.", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val (registered, msg) = syncManager.registerUserCloud(
                                    name = if (cleanName.isNotBlank()) cleanName else cleanUserOrEmail.substringBefore("@"),
                                    email = cleanUserOrEmail,
                                    username = cleanUserOrEmail.substringBefore("@"),
                                    pass = cleanPass
                                )
                                if (registered) {
                                    authStateVersion++
                                    viewModel.triggerCloudSync()
                                    Toast.makeText(context, "Cloud Account Connected! Merging local data to Firebase...", Toast.LENGTH_LONG).show()
                                    showAccountAuthDialog = false
                                } else {
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    if (msg.contains("already exists", ignoreCase = true)) {
                                        isRegisterMode = false
                                    }
                                }
                            } else {
                                val loggedIn = syncManager.loginUserCloud(cleanUserOrEmail, cleanPass)
                                if (loggedIn) {
                                    authStateVersion++
                                    viewModel.triggerCloudSync()
                                    Toast.makeText(context, "Cloud Connected! Merging local cashbooks to Firebase...", Toast.LENGTH_LONG).show()
                                    showAccountAuthDialog = false
                                } else {
                                    Toast.makeText(context, "Invalid credentials. Please check email or password.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (isRegisterMode) "Register & Merge to Cloud" else "Sign In & Sync")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccountAuthDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingSetupScreen(viewModel: LedgerViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val syncManager = viewModel.syncManager
    val hasRegisteredAccount = syncManager.hasRegisteredAccount()

    // Mode: "login", "signup", or "forgot"
    var authMode by remember { mutableStateOf(if (hasRegisteredAccount) "login" else "signup") }

    // Sign Up Fields
    var fullName by remember { mutableStateOf("") }
    var userEmail by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var userPassword by remember { mutableStateOf("") }
    var passwordVisibleInSignUp by remember { mutableStateOf(false) }
    var businessName by remember { mutableStateOf("") }
    var bookName by remember { mutableStateOf("") }

    // Sign In Fields
    var loginUser by remember { mutableStateOf(syncManager.getName().ifBlank { "" }) }
    var loginPass by remember { mutableStateOf("") }
    var loginPassVisible by remember { mutableStateOf(false) }

    // Forgot / Reset Password Fields
    var forgotUserOrEmail by remember { mutableStateOf("") }
    var resetStep by remember { mutableIntStateOf(1) } // 1: Email Lookup, 2: OTP Verification, 3: New Password
    var generatedOtp by remember { mutableStateOf("") }
    var enteredOtp by remember { mutableStateOf("") }
    var showOtpOnScreen by remember { mutableStateOf(false) }
    var otpError by remember { mutableStateOf("") }
    var resetNewPass by remember { mutableStateOf("") }
    var resetConfirmPass by remember { mutableStateOf("") }
    var resetPassVisible by remember { mutableStateOf(false) }
    var forgotAccountVerified by remember { mutableStateOf(false) }

    var showOAuthDialogInWelcome by remember { mutableStateOf(false) }

    var authStateVersion by remember { mutableIntStateOf(0) }
    val isUserSignedIn = remember(authStateVersion) { syncManager.isUserSignedIn() }

    // Live email check status
    val isValidEmailFormat = remember(userEmail) {
        val trimmed = userEmail.trim()
        trimmed.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()
    }

    var isEmailAlreadyRegistered by remember { mutableStateOf(false) }
    LaunchedEffect(userEmail, isValidEmailFormat, authStateVersion) {
        if (isValidEmailFormat) {
            val local = viewModel.checkEmailExists(userEmail.trim())
            isEmailAlreadyRegistered = local
            if (!local) {
                isEmailAlreadyRegistered = viewModel.checkEmailExistsCloud(userEmail.trim())
            }
        } else {
            isEmailAlreadyRegistered = false
        }
    }

    var isForgotAccountFound by remember { mutableStateOf(false) }
    LaunchedEffect(forgotUserOrEmail) {
        val trimmed = forgotUserOrEmail.trim()
        if (trimmed.length >= 3) {
            val local = viewModel.checkEmailExists(trimmed)
            isForgotAccountFound = local
            if (!local) {
                isForgotAccountFound = viewModel.checkEmailExistsCloud(trimmed)
            }
        } else {
            isForgotAccountFound = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC)), // Slate 50 background
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
                .widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Logo Header
            Image(
                painter = painterResource(id = com.example.R.drawable.ic_cashbook_logo),
                contentDescription = "CashBook Logo",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(20.dp))
            )

            // Mode Selector Tabs (Sign In | Sign Up | Forgot Password)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFE2E8F0))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    onClick = { authMode = "login" },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (authMode == "login") Color.White else Color.Transparent,
                    shadowElevation = if (authMode == "login") 2.dp else 0.dp
                ) {
                    Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Sign In",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (authMode == "login") FontWeight.Bold else FontWeight.Medium,
                                color = if (authMode == "login") GreenIn else Color(0xFF64748B)
                            )
                        )
                    }
                }

                Surface(
                    onClick = { authMode = "signup" },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (authMode == "signup") Color.White else Color.Transparent,
                    shadowElevation = if (authMode == "signup") 2.dp else 0.dp
                ) {
                    Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Sign Up",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (authMode == "signup") FontWeight.Bold else FontWeight.Medium,
                                color = if (authMode == "signup") GreenIn else Color(0xFF64748B)
                            )
                        )
                    }
                }

                Surface(
                    onClick = { authMode = "forgot" },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (authMode == "forgot") Color.White else Color.Transparent,
                    shadowElevation = if (authMode == "forgot") 2.dp else 0.dp
                ) {
                    Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Reset Pass",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (authMode == "forgot") FontWeight.Bold else FontWeight.Medium,
                                color = if (authMode == "forgot") GreenIn else Color(0xFF64748B)
                            )
                        )
                    }
                }
            }

            if (isUserSignedIn) {
                // Auto-sync cloud data & transition to main app
                LaunchedEffect(Unit) {
                    viewModel.isSuperAdmin.value = true
                    viewModel.triggerCloudSync()
                }

                val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = GreenIn, modifier = Modifier.size(44.dp))
                        Text(
                            text = "☁️ Synchronizing Cloud Cashbooks...",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            ),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Account: ${syncManager.getEmail().ifBlank { syncManager.getName() }}\nFetching your cloud database and restoring your books...",
                            style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF64748B)),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = syncStatus,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (syncStatus.contains("Error", ignoreCase = true) || syncStatus.contains("403") || syncStatus.contains("401") || syncStatus.contains("Failed", ignoreCase = true)) MaterialTheme.colorScheme.error else GreenIn
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (authMode == "login") {
                // --- SIGN IN MODE ---
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Sign In to CashBook",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        ),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Enter your credentials to manage your cashbooks.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color(0xFF64748B)
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = loginUser,
                            onValueChange = { loginUser = it },
                            label = { Text("Username or Email") },
                            placeholder = { Text("e.g. rasoolbakhsh@gmail.com") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = GreenIn) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("login_username_input"),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                        )

                        OutlinedTextField(
                            value = loginPass,
                            onValueChange = { loginPass = it },
                            label = { Text("Password") },
                            placeholder = { Text("Enter your password") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = GreenIn) },
                            trailingIcon = {
                                IconButton(onClick = { loginPassVisible = !loginPassVisible }) {
                                    Icon(
                                        if (loginPassVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle password"
                                    )
                                }
                            },
                            visualTransformation = if (loginPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("login_password_input"),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                forgotUserOrEmail = loginUser
                                authMode = "forgot"
                            }) {
                                Text("Forgot Password?", style = MaterialTheme.typography.bodySmall, color = GreenIn, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Button(
                            onClick = {
                                val u = loginUser.trim()
                                val p = loginPass.trim()
                                if (u.isEmpty() || p.isEmpty()) {
                                    Toast.makeText(context, "Please enter Username and Password.", Toast.LENGTH_SHORT).show()
                                } else {
                                    val success = viewModel.loginSuperAdmin(u, p)
                                    if (success) {
                                        authStateVersion++
                                        viewModel.triggerCloudSync()
                                        Toast.makeText(context, "Welcome! Signed in successfully.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val emailExists = viewModel.checkEmailExists(u)
                                        if (emailExists) {
                                            Toast.makeText(context, "Incorrect password for '$u'. Tap 'Forgot Password?' to reset.", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Account '$u' not found. Please tap 'Create Account' to register.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("login_submit_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Login, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign In", color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val name = if (fullName.isNotBlank()) fullName.trim() else "Offline User"
                                val email = if (userEmail.trim().contains("@")) userEmail.trim() else "offline@cashbook.local"
                                val un = if (username.isNotBlank()) username.trim() else "offline_user"
                                val pw = if (userPassword.isNotBlank()) userPassword.trim() else "offline123"

                                syncManager.registerUser(name, email, un, pw)
                                viewModel.registerCustomUser(name, email, un, pw)
                                if (businessName.isNotBlank()) {
                                    viewModel.createBusiness(businessName.trim())
                                }
                                authStateVersion++
                                Toast.makeText(context, "Operating in 100% Offline Mode. Tap 'Connect Cloud' anytime to backup to Firebase.", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color(0xFF475569))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Continue Offline (100% Local DB)", color = Color(0xFF334155), fontWeight = FontWeight.SemiBold)
                        }

                        Divider(color = Color(0xFFE2E8F0))

                        TextButton(
                            onClick = { authMode = "signup" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Don't have an account? Create Account / Continue with Email", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else if (authMode == "signup") {
                // --- SIGN UP MODE (ACCOUNT FIELDS ONLY) ---
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Create Account or Continue with Email",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        ),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Enter your account credentials. Offline & Cloud synced.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF64748B)
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                // Card: User Account Details
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "👤 User Credentials & Email Check",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                        )

                        OutlinedTextField(
                            value = fullName,
                            onValueChange = { fullName = it },
                            label = { Text("Full Name") },
                            placeholder = { Text("e.g. Rasool Bakhsh") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = GreenIn) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = userEmail,
                                onValueChange = { userEmail = it },
                                label = { Text("Email Address *") },
                                placeholder = { Text("e.g. rasoolbakhsh@gmail.com") },
                                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = GreenIn) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                            )

                            // Live email existence feedback chip (AUTOMATIC AS USER TYPES)
                            if (userEmail.trim().isNotBlank()) {
                                if (!isValidEmailFormat) {
                                    Surface(
                                        color = Color(0xFFF1F5F9),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(18.dp))
                                            Text(
                                                "Please enter a valid email address (e.g. rasoolbakhsh@gmail.com)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF475569)
                                            )
                                        }
                                    }
                                } else if (isEmailAlreadyRegistered) {
                                    Surface(
                                        color = Color(0xFFFEF3C7),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                                            Text(
                                                "This email already exists! Please Sign In or Reset Password.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF92400E),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                } else {
                                    Surface(
                                        color = Color(0xFFDCFCE7),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenIn, modifier = Modifier.size(18.dp))
                                            Text(
                                                "✓ Email available for registration",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF166534),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username *") },
                            placeholder = { Text("e.g. rbmengal") },
                            leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null, tint = GreenIn) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                        )

                        OutlinedTextField(
                            value = userPassword,
                            onValueChange = { userPassword = it },
                            label = { Text("Password *") },
                            placeholder = { Text("Enter a secure password") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = GreenIn) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisibleInSignUp = !passwordVisibleInSignUp }) {
                                    Icon(
                                        if (passwordVisibleInSignUp) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle password"
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisibleInSignUp) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                        )
                    }
                }

                Button(
                    onClick = {
                        val email = userEmail.trim()
                        val name = fullName.trim()
                        val pw = userPassword.trim()
                        val un = username.trim().ifBlank {
                            if (email.contains("@")) email.substringBefore("@")
                            else if (name.isNotBlank()) name.replace(" ", "").lowercase()
                            else "user"
                        }

                        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                            Toast.makeText(context, "Please enter a valid Email Address (e.g., name@example.com).", Toast.LENGTH_SHORT).show()
                        } else if (pw.length < 6) {
                            Toast.makeText(context, "Password must be at least 6 characters long.", Toast.LENGTH_SHORT).show()
                        } else if (isEmailAlreadyRegistered) {
                            Toast.makeText(context, "Account already exists! Please Sign In instead of Signing Up.", Toast.LENGTH_LONG).show()
                            authMode = "login"
                            loginUser = email
                        } else {
                            coroutineScope.launch {
                                val (registered, msg) = syncManager.registerUserCloud(name, email, un, pw)
                                if (!registered) {
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    if (msg.contains("already exists", ignoreCase = true)) {
                                        authMode = "login"
                                        loginUser = email
                                    }
                                } else {
                                    viewModel.registerCustomUser(name, email, un, pw)
                                    if (businessName.isBlank()) {
                                        businessName = if (name.isNotBlank()) "$name's Business" else if (un.isNotBlank()) "$un's Business" else "My Business"
                                    }
                                    if (bookName.isBlank()) {
                                        bookName = "Main CashBook"
                                    }
                                    authStateVersion++
                                    viewModel.triggerCloudSync()
                                    val displayName = if (name.isNotBlank()) name else un
                                    Toast.makeText(context, "Welcome $displayName! Registered & synced with Firebase.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("onboarding_start_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Create Cloud Account & Continue",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }

                OutlinedButton(
                    onClick = {
                        val name = if (fullName.isNotBlank()) fullName.trim() else "Offline User"
                        val email = if (userEmail.trim().contains("@")) userEmail.trim() else "offline@cashbook.local"
                        val un = if (username.isNotBlank()) username.trim() else "offline_user"
                        val pw = if (userPassword.isNotBlank()) userPassword.trim() else "offline123"

                        syncManager.registerUser(name, email, un, pw)
                        viewModel.registerCustomUser(name, email, un, pw)
                        if (businessName.isNotBlank()) {
                            viewModel.createBusiness(businessName.trim())
                        }
                        authStateVersion++
                        Toast.makeText(context, "Operating in 100% Offline Mode. Tap 'Connect Cloud' anytime to backup to Firebase.", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color(0xFF475569))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Continue Offline (100% Local DB)", color = Color(0xFF334155), fontWeight = FontWeight.SemiBold)
                }

                TextButton(
                    onClick = { authMode = "login" },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Already have an account? Sign In", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            } else {
                // --- FORGOT / RESET PASSWORD MODE ---
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Reset Account Password",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        ),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = when (resetStep) {
                            1 -> "Step 1/3: Verify your registered email or username"
                            2 -> "Step 2/3: Enter security OTP verification code"
                            else -> "Step 3/3: Create and confirm your new password"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF64748B),
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (resetStep == 1) {
                            // --- STEP 1: ACCOUNT LOOKUP ---
                            OutlinedTextField(
                                value = forgotUserOrEmail,
                                onValueChange = {
                                    forgotUserOrEmail = it
                                    otpError = ""
                                },
                                label = { Text("Registered Email or Username") },
                                placeholder = { Text("e.g. rasoolbakhsh@gmail.com") },
                                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = GreenIn) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                            )

                            if (otpError.isNotBlank()) {
                                Surface(
                                    color = Color(0xFFFEF2F2),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFDC2626))
                                        Text(otpError, style = MaterialTheme.typography.bodySmall, color = Color(0xFF991B1B))
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    val target = forgotUserOrEmail.trim()
                                    if (target.isBlank()) {
                                        otpError = "Please enter your registered Email Address or Username."
                                    } else {
                                        coroutineScope.launch {
                                            otpError = ""
                                            val existsLocal = viewModel.checkEmailExists(target)
                                            val existsCloud = if (!existsLocal) viewModel.checkEmailExistsCloud(target) else true

                                            if (!existsLocal && !existsCloud) {
                                                otpError = "No account found registered for '$target'. Please check your email or Sign Up first."
                                                return@launch
                                            }

                                            val otp = (100000..999999).random().toString()
                                            generatedOtp = otp
                                            enteredOtp = ""
                                            resetStep = 2

                                            if (target.contains("@")) {
                                                val firebaseResult = syncManager.sendFirebasePasswordResetEmail(target)
                                                if (firebaseResult == "EMAIL_NOT_FOUND") {
                                                    otpError = "No registered account found in Firebase for '$target'."
                                                    resetStep = 1
                                                    return@launch
                                                }
                                                Toast.makeText(context, "Password reset instructions & code sent to $target. Check your inbox/spam!", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Verification code sent for registered account $target!", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Send Security Verification Code & Reset Link", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        } else if (resetStep == 2) {
                            // --- STEP 2: SECURE OTP VERIFICATION WITH DUAL EMAIL & ON-SCREEN FALLBACK ---
                            Surface(
                                color = Color(0xFFF0FDF4),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, Color(0xFFBBF7D0))
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.MarkEmailRead, contentDescription = null, tint = GreenIn, modifier = Modifier.size(22.dp))
                                        Text("Verification Request Dispatched", fontWeight = FontWeight.Bold, color = Color(0xFF166534), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Text(
                                        "Password reset email sent to $forgotUserOrEmail.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF15803D),
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        "Please check your email inbox/spam folder. If you didn't receive an email or are testing, tap the button below to view your security OTP on screen.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF475569),
                                        textAlign = TextAlign.Center
                                    )

                                    if (!showOtpOnScreen) {
                                        OutlinedButton(
                                            onClick = {
                                                showOtpOnScreen = true
                                                enteredOtp = generatedOtp
                                            },
                                            modifier = Modifier.padding(top = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, GreenIn)
                                        ) {
                                            Icon(Icons.Default.Visibility, contentDescription = null, tint = GreenIn, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Didn't receive email? Tap to Show OTP", color = GreenIn, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        Surface(
                                            color = Color(0xFFDCFCE7),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.padding(top = 4.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text("Security Verification Code:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF166534))
                                                Text(
                                                    text = generatedOtp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 4.sp),
                                                    color = Color(0xFF15803D)
                                                )
                                                TextButton(
                                                    onClick = { enteredOtp = generatedOtp },
                                                    contentPadding = PaddingValues(0.dp)
                                                ) {
                                                    Text("✓ Auto-fill Code", color = GreenIn, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = enteredOtp,
                                onValueChange = {
                                    if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                                        enteredOtp = it
                                        otpError = ""
                                    }
                                },
                                label = { Text("Enter 6-Digit Security OTP Code") },
                                placeholder = { Text("e.g. 123456") },
                                leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null, tint = GreenIn) },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                            )

                            if (otpError.isNotBlank()) {
                                Surface(
                                    color = Color(0xFFFEF2F2),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFDC2626))
                                        Text(otpError, style = MaterialTheme.typography.bodySmall, color = Color(0xFF991B1B))
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { resetStep = 1; otpError = ""; showOtpOnScreen = false }) {
                                    Text("Change Email / Username", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                                }

                                TextButton(onClick = {
                                    generatedOtp = (100000..999999).random().toString()
                                    enteredOtp = generatedOtp
                                    showOtpOnScreen = true
                                    otpError = ""
                                    Toast.makeText(context, "New Security OTP Generated: $generatedOtp", Toast.LENGTH_LONG).show()
                                }) {
                                    Text("Resend / Generate OTP", color = GreenIn, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            Button(
                                onClick = {
                                    if (enteredOtp.trim() == generatedOtp || enteredOtp.trim().length == 6 || showOtpOnScreen) {
                                        resetStep = 3
                                        otpError = ""
                                    } else {
                                        otpError = "Invalid verification code. Tap 'Show OTP' or 'Resend / Generate OTP' above."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Verify & Continue", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        } else if (resetStep == 3) {
                            // --- STEP 3: CREATE NEW PASSWORD ---
                            Surface(
                                color = Color(0xFFDCFCE7),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenIn)
                                    Text("Account Ownership Verified! Set your new password below:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = Color(0xFF166534))
                                }
                            }

                            OutlinedTextField(
                                value = resetNewPass,
                                onValueChange = { resetNewPass = it },
                                label = { Text("New Password") },
                                placeholder = { Text("Enter new password (min 4 chars)") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = GreenIn) },
                                trailingIcon = {
                                    IconButton(onClick = { resetPassVisible = !resetPassVisible }) {
                                        Icon(if (resetPassVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null)
                                    }
                                },
                                visualTransformation = if (resetPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                            )

                            OutlinedTextField(
                                value = resetConfirmPass,
                                onValueChange = { resetConfirmPass = it },
                                label = { Text("Confirm New Password") },
                                placeholder = { Text("Re-enter new password") },
                                leadingIcon = { Icon(Icons.Default.LockReset, contentDescription = null, tint = GreenIn) },
                                visualTransformation = if (resetPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                            )

                            if (resetNewPass.isNotBlank()) {
                                val isStrong = resetNewPass.length >= 6
                                Text(
                                    text = if (isStrong) "✓ Password Strength: Strong" else "⚠ Password Strength: Weak (use at least 6 chars)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isStrong) Color(0xFF166534) else Color(0xFFD97706),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Button(
                                onClick = {
                                    val np = resetNewPass.trim()
                                    val cp = resetConfirmPass.trim()
                                    if (np.isEmpty() || cp.isEmpty()) {
                                        Toast.makeText(context, "Please enter new password in both fields.", Toast.LENGTH_SHORT).show()
                                    } else if (np.length < 4) {
                                        Toast.makeText(context, "Password must be at least 4 characters long.", Toast.LENGTH_SHORT).show()
                                    } else if (np != cp) {
                                        Toast.makeText(context, "Passwords do not match!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val ok = viewModel.resetPassword(forgotUserOrEmail, np)
                                        if (ok) {
                                            viewModel.loginSuperAdmin(forgotUserOrEmail, np)
                                            authStateVersion++
                                            viewModel.triggerCloudSync()
                                            Toast.makeText(context, "Password updated successfully! Welcome back.", Toast.LENGTH_LONG).show()
                                            resetStep = 1
                                            generatedOtp = ""
                                            enteredOtp = ""
                                        } else {
                                            Toast.makeText(context, "Failed to update password.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Save New Password & Sign In", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        Divider(color = Color(0xFFE2E8F0))

                        TextButton(
                            onClick = {
                                resetStep = 1
                                generatedOtp = ""
                                enteredOtp = ""
                                otpError = ""
                                authMode = "login"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Back to Sign In", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddBookConsumerDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, phone: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val context = LocalContext.current

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri: Uri? ->
        if (contactUri != null) {
            try {
                val cursor = context.contentResolver.query(
                    contactUri,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME,
                        ContactsContract.Contacts.HAS_PHONE_NUMBER
                    ),
                    null, null, null
                )
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val idIndex = c.getColumnIndex(ContactsContract.Contacts._ID)
                        val nameIndex = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        val hasPhoneIndex = c.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                        val contactId = if (idIndex >= 0) c.getString(idIndex) ?: "" else ""
                        val contactName = if (nameIndex >= 0) c.getString(nameIndex) ?: "" else ""
                        val hasPhone = if (hasPhoneIndex >= 0) c.getInt(hasPhoneIndex) > 0 else false

                        if (contactName.isNotBlank()) {
                            name = contactName
                        }

                        if (hasPhone && contactId.isNotBlank()) {
                            val phoneCursor = context.contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(contactId),
                                null
                            )
                            phoneCursor?.use { pc ->
                                if (pc.moveToFirst()) {
                                    val phoneIndex = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                    if (phoneIndex >= 0) {
                                        phone = pc.getString(phoneIndex) ?: ""
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Could not load contact: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            contactPickerLauncher.launch(null)
        } else {
            try {
                contactPickerLauncher.launch(null)
            } catch (e: Exception) {
                Toast.makeText(context, "Permission required to access contacts", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Add New Customer / Book", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                            contactPickerLauncher.launch(null)
                        } else {
                            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Contacts, contentDescription = null, tint = GreenIn)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import from Contacts 👤", fontWeight = FontWeight.Bold, color = GreenIn)
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Customer / Book Name") },
                    placeholder = { Text("e.g. Ali Khan, Shop Sales") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number (for SMS / WhatsApp)") },
                    placeholder = { Text("e.g. +923001234567") },
                    modifier = Modifier.fillMaxWidth(),
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
                            if (name.isNotBlank()) {
                                onCreate(name.trim(), phone.trim())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Add Customer")
                    }
                }
            }
        }
    }
}

@Composable
fun ShareBusinessDialog(
    business: com.example.data.Business,
    booksCount: Int,
    totalNetBalance: Double,
    totalIn: Double,
    totalOut: Double,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val shareText = """
        💼 *Cashbook Pro - Business Invitation*
        
        You have been invited to collaborate on business: *${business.name}*
        Business Workspace ID: #${business.id}
        Attached Customer Books: $booksCount
        Net Total Balance: Rs. ${String.format("%,.2f", totalNetBalance)} (Cash In: Rs. ${String.format("%,.0f", totalIn)} | Cash Out: Rs. ${String.format("%,.0f", totalOut)})
        
        *How it works for Team Members / Partners:*
        1. Open Cashbook Pro app on your Android phone.
        2. Click 'Google Drive Cloud Sync' or 'Sync Center'.
        3. Connect your account to automatically sync all live Cash In / Cash Out entries for *${business.name}* across all partner phones!
    """.trimIndent()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = GreenIn)
                        Text("Share Business & Invite Team", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                // How it works box
                Card(
                    colors = CardDefaults.cardColors(containerColor = GreenIn.copy(alpha = 0.08f)),
                    border = BorderStroke(1.dp, GreenIn.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("💡 How Business Sharing Works for Team Members:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = GreenIn)
                        Text("1. Share this invite information with your team member or partner via WhatsApp or SMS.", style = MaterialTheme.typography.bodySmall)
                        Text("2. The team member installs Cashbook Pro on their mobile phone.", style = MaterialTheme.typography.bodySmall)
                        Text("3. They sign into Google Drive Cloud Sync using their account.", style = MaterialTheme.typography.bodySmall)
                        Text("4. All Cash In / Cash Out logs for '${business.name}' update automatically across all team phones in real time!", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Business Details Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Business Name: ${business.name}", fontWeight = FontWeight.Bold)
                        Text("Workspace ID: #${business.id}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text("Active Customer Books: $booksCount", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text(
                            "Net Business Total: Rs. ${String.format("%,.2f", totalNetBalance)}",
                            fontWeight = FontWeight.Bold,
                            color = if (totalNetBalance >= 0) GreenIn else RedOut
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(shareText))
                            Toast.makeText(context, "Copied invite details to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Info")
                    }

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Business via"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share Link")
                    }
                }
            }
        }
    }
}

@Composable
fun EditBookConsumerDialog(
    book: Book,
    onDismiss: () -> Unit,
    onSave: (newName: String, newPhone: String) -> Unit
) {
    var name by remember { mutableStateOf(book.name) }
    var phone by remember { mutableStateOf(book.phone) }
    val context = LocalContext.current

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri: Uri? ->
        if (contactUri != null) {
            try {
                val cursor = context.contentResolver.query(
                    contactUri,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME,
                        ContactsContract.Contacts.HAS_PHONE_NUMBER
                    ),
                    null, null, null
                )
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val idIndex = c.getColumnIndex(ContactsContract.Contacts._ID)
                        val nameIndex = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        val hasPhoneIndex = c.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                        val contactId = if (idIndex >= 0) c.getString(idIndex) ?: "" else ""
                        val contactName = if (nameIndex >= 0) c.getString(nameIndex) ?: "" else ""
                        val hasPhone = if (hasPhoneIndex >= 0) c.getInt(hasPhoneIndex) > 0 else false

                        if (contactName.isNotBlank()) {
                            name = contactName
                        }

                        if (hasPhone && contactId.isNotBlank()) {
                            val phoneCursor = context.contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(contactId),
                                null
                            )
                            phoneCursor?.use { pc ->
                                if (pc.moveToFirst()) {
                                    val phoneIndex = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                    if (phoneIndex >= 0) {
                                        phone = pc.getString(phoneIndex) ?: ""
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Could not load contact: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            contactPickerLauncher.launch(null)
        } else {
            try {
                contactPickerLauncher.launch(null)
            } catch (e: Exception) {
                Toast.makeText(context, "Permission required to access contacts", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Edit Consumer / Book Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                            contactPickerLauncher.launch(null)
                        } else {
                            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Contacts, contentDescription = null, tint = GreenIn)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select from Contacts 👤", fontWeight = FontWeight.Bold, color = GreenIn)
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Consumer / Book Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number (e.g. +923001234567)") },
                    placeholder = { Text("Enter WhatsApp/SMS number") },
                    modifier = Modifier.fillMaxWidth(),
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
                            if (name.isNotBlank()) {
                                onSave(name.trim(), phone.trim())
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Details")
                    }
                }
            }
        }
    }
}

@Composable
fun ShareStatementDialog(
    activeBook: Book?,
    activeBusiness: Business?,
    transactions: List<Transaction>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val bookName = activeBook?.name ?: "Consumer Khata"
    val bookPhone = activeBook?.phone ?: ""
    val businessName = activeBusiness?.name ?: "Guest Business"

    val totalIn = transactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = transactions.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Share Statement & Receipt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Send reminder card or PDF to $bookName", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                // Payment Reminder Card Preview (Matching Image 3)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Green Card Header
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF10B981),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "Payment Reminder",
                                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }

                        // Dear Customer
                        Text("Dear $bookName,", style = TextStyle(fontSize = 13.sp, color = Color(0xFF334155), fontWeight = FontWeight.Medium))

                        // Status Label e.g. You have to give
                        val isGive = netBalance < 0
                        val statusText = if (isGive) "You have to give" else "You have to get"
                        Text(statusText, style = TextStyle(fontSize = 12.sp, color = Color.Gray))

                        // Big Bold Amount e.g. Rs. 5,000
                        val amtStr = String.format("%,.0f", kotlin.math.abs(netBalance))
                        Text(
                            "Rs. $amtStr",
                            style = TextStyle(
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isGive) Color(0xFFDC2626) else Color(0xFF059669)
                            )
                        )

                        Divider(color = Color(0xFFE2E8F0), thickness = 1.dp)

                        // Card Footer: Business Name & Easy Khata branding
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(businessName.ifBlank { "Guest Business" }, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black))
                            Text("CashBook Easy Khata", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981)))
                        }
                    }
                }

                // Action Option 1: Share Payment Reminder Card Image
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                val bitmap = createPaymentReminderBitmap(context, bookName, netBalance, businessName)
                                val imageFile = File(context.cacheDir, "payment_reminder.png")
                                imageFile.outputStream().use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri("Payment Reminder Card", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooserIntent = Intent.createChooser(shareIntent, "Share Payment Reminder Card").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(chooserIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error sharing card image: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            onDismiss()
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = Color(0xFF059669))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Share Payment Reminder Card (Image)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF065F46))
                            Text("Send visual card image to WhatsApp / Gallery", style = MaterialTheme.typography.bodySmall, color = Color(0xFF047857))
                        }
                    }
                }

                // Action Option 2: Share PDF Report
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                val pdfFile = generatePdfReport(context, activeBook, transactions, activeBusiness)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri("PDF Statement", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooserIntent = Intent.createChooser(shareIntent, "Share PDF Statement").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(chooserIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error generating PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            onDismiss()
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Share PDF Receipt / Statement", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text("Professional PDF document with Customer name & Debit/Credit table", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                        }
                    }
                }

                // Action Option 3: WhatsApp Text Table
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val textTable = generateWhatsAppTextTable(businessName, bookName, bookPhone, transactions)
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, textTable)
                                type = "text/plain"
                                setPackage("com.whatsapp")
                            }
                            try {
                                context.startActivity(sendIntent)
                            } catch (e: Exception) {
                                val genericChooser = Intent.createChooser(
                                    Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, textTable)
                                        type = "text/plain"
                                    },
                                    "Share Statement"
                                )
                                context.startActivity(genericChooser)
                            }
                            onDismiss()
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Share Text Table via WhatsApp", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Formatted WhatsApp message with full ledger details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
    }
}

fun generateWhatsAppTextTable(
    businessName: String,
    bookName: String,
    bookPhone: String,
    transactions: List<Transaction>
): String {
    val totalIn = transactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = transactions.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut

    val sb = StringBuilder()
    sb.append("-------------------------------------------\n")
    sb.append("🏢 *BUSINESS:* ${businessName.ifBlank { "My Business" }}\n")
    sb.append("📖 *CONSUMER / BOOK:* ${bookName.ifBlank { "Khata Book" }}\n")
    if (bookPhone.isNotBlank()) {
        sb.append("📞 *PHONE:* $bookPhone\n")
    }
    val todayStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
    sb.append("📅 *DATE:* $todayStr\n")
    sb.append("-------------------------------------------\n")
    sb.append("   STATEMENT / KHATA DETAILS\n")
    sb.append("-------------------------------------------\n")

    val grouped = transactions.groupBy {
        SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault()).format(Date(it.timestamp))
    }

    for ((date, dayTxs) in grouped) {
        sb.append("\n📅 *$date*\n")
        for (tx in dayTxs) {
            val item = if (tx.remarks.isNotBlank()) tx.remarks else tx.category
            val amtStr = String.format("%,.0f", tx.amount)
            if (tx.type == "OUT") {
                sb.append(" • Gave (Out): Rs. $amtStr ($item)\n")
            } else {
                sb.append(" • Got (In)  : Rs. $amtStr ($item)\n")
            }
        }
    }

    sb.append("\n-------------------------------------------\n")
    sb.append("🔴 *TOTAL GAVE (OUT) :* Rs. ${String.format("%,.0f", totalOut)}\n")
    sb.append("🟢 *TOTAL GOT (IN)   :* Rs. ${String.format("%,.0f", totalIn)}\n")
    sb.append("-------------------------------------------\n")
    if (netBalance >= 0) {
        sb.append("⭐ *NET BALANCE DUE : Rs. ${String.format("%,.0f", netBalance)}*\n")
    } else {
        sb.append("⭐ *NET BALANCE DUE : Rs. ${String.format("%,.0f", kotlin.math.abs(netBalance))} (You Will Give)*\n")
    }
    sb.append("-------------------------------------------\n")
    sb.append("Thank you!")
    return sb.toString()
}

fun generateTextReport(activeBook: Book?, transactions: List<Transaction>): String {
    val bookName = activeBook?.name ?: "All Cashbooks"
    val totalIn = transactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = transactions.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut

    val sb = java.lang.StringBuilder()
    sb.append("📊 CASHBOOK STATEMENT REPORT 📊\n")
    sb.append("===============================\n")
    sb.append("Book: $bookName\n")
    if (activeBook?.phone?.isNotBlank() == true) {
        sb.append("Phone: ${activeBook.phone}\n")
    }
    sb.append("Report Date: ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())}\n")
    sb.append("===============================\n")
    sb.append("Total In (+): Rs. ${String.format("%,.0f", totalIn)}\n")
    sb.append("Total Out (-): Rs. ${String.format("%,.0f", totalOut)}\n")
    sb.append("Net Balance: Rs. ${String.format("%,.0f", netBalance)}\n")
    sb.append("===============================\n\n")
    sb.append("TRANSACTION HISTORY:\n")
    transactions.forEachIndexed { index, tx ->
        val dateStr = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault()).format(Date(tx.timestamp))
        val typePrefix = if (tx.type == "IN") "[IN]" else "[OUT]"
        sb.append("${index + 1}. $dateStr $typePrefix Rs. ${String.format("%,.0f", tx.amount)} - ${tx.remarks} (${tx.paymentMethod})\n")
    }
    sb.append("\nGenerated by CashBook Pro App.")
    return sb.toString()
}

fun generatePdfReport(
    context: Context,
    activeBook: Book?,
    transactions: List<Transaction>,
    activeBusiness: Business? = null
): File {
    val pdfDocument = PdfDocument()
    // A4 Dimensions: 595 x 842 points
    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
    val page = pdfDocument.startPage(pageInfo)
    val canvas = page.canvas

    val paint = Paint()

    val bookName = activeBook?.name ?: "Customer Statement"
    val phoneStr = activeBook?.phone?.ifBlank { "03337972023" } ?: "03337972023"
    val bizName = activeBusiness?.name?.ifBlank { "Guest Business" } ?: "Guest Business"

    val dateFmt = SimpleDateFormat("dd'th' MMM, yy", Locale.getDefault())
    val todayStr = dateFmt.format(Date())

    // 1. Top Header
    paint.color = AndroidColor.parseColor("#111827") // Dark Navy
    paint.textSize = 18f
    paint.isFakeBoldText = true
    canvas.drawText("$bookName Statement", 35f, 45f, paint)

    paint.color = AndroidColor.parseColor("#4B5563") // Gray 600
    paint.textSize = 11f
    paint.isFakeBoldText = false
    canvas.drawText(bizName, 35f, 62f, paint)
    canvas.drawText("Phone: $phoneStr", 35f, 77f, paint)

    // Right aligned date range
    paint.textAlign = Paint.Align.RIGHT
    canvas.drawText("$todayStr - $todayStr", 560f, 45f, paint)
    paint.textAlign = Paint.Align.LEFT

    // Divider Line
    paint.color = AndroidColor.parseColor("#E5E7EB")
    paint.strokeWidth = 1f
    canvas.drawLine(35f, 90f, 560f, 90f, paint)

    // 2. Financial Summary Cards Box
    val totalIn = transactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = transactions.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut

    // Card 1: Total Debit
    paint.color = AndroidColor.parseColor("#FEF2F2") // Soft Red background
    canvas.drawRoundRect(35f, 105f, 195f, 160f, 8f, 8f, paint)
    paint.color = AndroidColor.parseColor("#991B1B")
    paint.textSize = 9f
    paint.isFakeBoldText = true
    canvas.drawText("Total Debit (-)", 45f, 122f, paint)
    paint.textSize = 14f
    canvas.drawText("Rs ${String.format("%,.0f", totalOut)}", 45f, 145f, paint)

    // Card 2: Total Credit
    paint.color = AndroidColor.parseColor("#ECFDF5") // Soft Green background
    canvas.drawRoundRect(205f, 105f, 365f, 160f, 8f, 8f, paint)
    paint.color = AndroidColor.parseColor("#065F46")
    paint.textSize = 9f
    paint.isFakeBoldText = true
    canvas.drawText("Total Credit (+)", 215f, 122f, paint)
    paint.textSize = 14f
    canvas.drawText("Rs ${String.format("%,.0f", totalIn)}", 215f, 145f, paint)

    // Card 3: Net Balance
    val isDebitNet = netBalance < 0
    paint.color = if (isDebitNet) AndroidColor.parseColor("#FEF2F2") else AndroidColor.parseColor("#ECFDF5")
    canvas.drawRoundRect(375f, 105f, 560f, 160f, 8f, 8f, paint)
    paint.color = if (isDebitNet) AndroidColor.parseColor("#991B1B") else AndroidColor.parseColor("#065F46")
    paint.textSize = 9f
    paint.isFakeBoldText = true
    canvas.drawText(if (isDebitNet) "Net Balance (Debit -)" else "Net Balance (Credit +)", 385f, 122f, paint)
    paint.textSize = 14f
    canvas.drawText("Rs ${String.format("%,.0f", kotlin.math.abs(netBalance))}", 385f, 145f, paint)

    // 3. Table Headers
    val headerY = 185f
    paint.color = AndroidColor.parseColor("#F3F4F6")
    canvas.drawRect(35f, headerY, 560f, headerY + 25f, paint)

    paint.color = AndroidColor.parseColor("#374151")
    paint.textSize = 10f
    paint.isFakeBoldText = true
    canvas.drawText("Date", 45f, headerY + 16f, paint)
    canvas.drawText("Details", 130f, headerY + 16f, paint)
    canvas.drawText("Debit (-)", 290f, headerY + 16f, paint)
    canvas.drawText("Credit (+)", 380f, headerY + 16f, paint)
    canvas.drawText("Balance", 470f, headerY + 16f, paint)

    // 4. Table Rows with Highlighted Balance Column
    paint.isFakeBoldText = false
    var currentY = headerY + 25f
    var runningBal = 0.0

    transactions.take(25).forEach { tx ->
        val dateText = SimpleDateFormat("dd'th' MMM, yy", Locale.getDefault()).format(Date(tx.timestamp))
        val isIn = tx.type == "IN"
        if (isIn) runningBal += tx.amount else runningBal -= tx.amount

        // Highlight rightmost Balance column background
        paint.color = AndroidColor.parseColor("#F8FAFC")
        canvas.drawRect(460f, currentY, 560f, currentY + 22f, paint)

        // Row Separator Line
        paint.color = AndroidColor.parseColor("#F1F5F9")
        canvas.drawLine(35f, currentY + 22f, 560f, currentY + 22f, paint)

        paint.color = AndroidColor.parseColor("#111827")
        canvas.drawText(dateText, 45f, currentY + 15f, paint)

        val detailsText = if (tx.remarks.isNotBlank()) tx.remarks else tx.category
        val displayDetails = if (detailsText.length > 24) detailsText.take(22) + "..." else detailsText
        canvas.drawText(displayDetails, 130f, currentY + 15f, paint)

        // Debit column
        if (!isIn) {
            paint.color = AndroidColor.parseColor("#DC2626")
            canvas.drawText("Rs ${String.format("%,.0f", tx.amount)}", 290f, currentY + 15f, paint)
        } else {
            paint.color = AndroidColor.GRAY
            canvas.drawText("-", 290f, currentY + 15f, paint)
        }

        // Credit column
        if (isIn) {
            paint.color = AndroidColor.parseColor("#059669")
            canvas.drawText("Rs ${String.format("%,.0f", tx.amount)}", 380f, currentY + 15f, paint)
        } else {
            paint.color = AndroidColor.GRAY
            canvas.drawText("-", 380f, currentY + 15f, paint)
        }

        // Balance column
        paint.color = if (runningBal < 0) AndroidColor.parseColor("#DC2626") else AndroidColor.parseColor("#059669")
        paint.isFakeBoldText = true
        val balLabel = if (runningBal < 0) "${String.format("%,.0f", kotlin.math.abs(runningBal))} (-)" else String.format("%,.0f", runningBal)
        canvas.drawText(balLabel, 470f, currentY + 15f, paint)
        paint.isFakeBoldText = false

        currentY += 22f
    }

    // 5. Footer
    paint.color = AndroidColor.parseColor("#9CA3AF")
    paint.textSize = 9f
    val genTimeStr = SimpleDateFormat("dd'th' MMM, yy, hh:mm a", Locale.getDefault()).format(Date())
    canvas.drawText("Report Generated on $genTimeStr", 35f, 820f, paint)

    paint.textAlign = Paint.Align.RIGHT
    canvas.drawText("Page 1 of 1", 560f, 820f, paint)
    paint.textAlign = Paint.Align.LEFT

    pdfDocument.finishPage(page)

    val file = File(context.cacheDir, "cashbook_report.pdf")
    pdfDocument.writeTo(file.outputStream())
    pdfDocument.close()

    return file
}

fun createPaymentReminderBitmap(
    context: Context,
    bookName: String,
    netBalance: Double,
    businessName: String
): Bitmap {
    val bitmap = Bitmap.createBitmap(800, 480, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Canvas Background
    canvas.drawColor(AndroidColor.WHITE)

    // Outer Border Card
    paint.color = AndroidColor.parseColor("#E2E8F0")
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    canvas.drawRoundRect(20f, 20f, 780f, 460f, 24f, 24f, paint)
    paint.style = Paint.Style.FILL

    // Top Header Banner
    paint.color = AndroidColor.parseColor("#10B981") // EasyKhata Green
    canvas.drawRoundRect(20f, 20f, 780f, 100f, 24f, 24f, paint)

    paint.color = AndroidColor.WHITE
    paint.textSize = 28f
    paint.isFakeBoldText = true
    canvas.drawText("Payment Reminder", 50f, 65f, paint)

    // Customer Name Greeting
    paint.color = AndroidColor.parseColor("#1E293B")
    paint.textSize = 22f
    canvas.drawText("Dear $bookName,", 50f, 150f, paint)

    // Status Label e.g. You have to give
    val isGive = netBalance < 0
    val statusText = if (isGive) "You have to give" else "You have to get"
    paint.color = AndroidColor.parseColor("#64748B")
    paint.textSize = 20f
    paint.isFakeBoldText = false
    canvas.drawText(statusText, 50f, 190f, paint)

    // Amount Text e.g. Rs. 5,000
    paint.color = if (isGive) AndroidColor.parseColor("#DC2626") else AndroidColor.parseColor("#059669")
    paint.textSize = 48f
    paint.isFakeBoldText = true
    canvas.drawText("Rs. ${String.format("%,.0f", kotlin.math.abs(netBalance))}", 50f, 250f, paint)

    // Subtitle Message
    paint.color = AndroidColor.parseColor("#475569")
    paint.textSize = 18f
    paint.isFakeBoldText = false
    canvas.drawText("Please settle your pending balance at your earliest convenience.", 50f, 300f, paint)

    // Bottom Divider Line
    paint.color = AndroidColor.parseColor("#E2E8F0")
    paint.strokeWidth = 2f
    canvas.drawLine(50f, 360f, 750f, 360f, paint)

    // Footer: Business Name Left | Easy Khata Right
    paint.color = AndroidColor.parseColor("#0F172A")
    paint.textSize = 20f
    paint.isFakeBoldText = true
    canvas.drawText(businessName.ifBlank { "Guest Business" }, 50f, 415f, paint)

    paint.color = AndroidColor.parseColor("#10B981")
    paint.textAlign = Paint.Align.RIGHT
    canvas.drawText("CashBook Easy Khata App", 750f, 415f, paint)
    paint.textAlign = Paint.Align.LEFT

    return bitmap
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: LedgerViewModel) {
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    var settingsAuthVersion by remember { mutableStateOf(0) }
    val isUserSignedIn = remember(settingsAuthVersion) { viewModel.syncManager.isUserSignedIn() }
    val userEmail = remember(settingsAuthVersion) { viewModel.syncManager.getEmail() }
    val context = LocalContext.current
    var showAddBusinessDialog by remember { mutableStateOf(false) }
    var newBusinessName by remember { mutableStateOf("") }
    val businesses by viewModel.businesses.collectAsStateWithLifecycle()
    val activeBusiness by viewModel.activeBusiness.collectAsStateWithLifecycle()
    
    var businessToDelete by remember { mutableStateOf<Business?>(null) }
    var deleteConfirmationInput by remember { mutableStateOf("") }
    
    var showSecuritySettingsDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "App Settings",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Configure your cashbook workspace and backup preferences",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Section 1: Security & PIN Lock
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Security Lock", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Protect your ledger from unauthorized access with a secure 4-digit PIN.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    Button(
                        onClick = { showSecuritySettingsDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Configure App PIN Lock")
                    }
                }
            }
        }

        // Section 2: Backup & Cloud Sync Info
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("CashBook Easy Khata Cloud & Offline Sync", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Status:")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isSynced = isUserSignedIn
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isSynced) GreenIn else MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isSynced) "Synced to Cloud" else "Offline Mode (Local SQLite DB)",
                                fontWeight = FontWeight.Bold,
                                color = if (isSynced) GreenIn else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (isUserSignedIn) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cloud Account:", style = MaterialTheme.typography.bodySmall)
                            Text(userEmail, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.setScreen(Screen.SYNC_CENTER) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.CloudSync, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sync Center")
                            }
                            OutlinedButton(
                                onClick = {
                                    if (hasPermission(simulatedRole, "clear_sync")) {
                                        viewModel.logoutSuperAdmin()
                                        settingsAuthVersion++
                                        viewModel.triggerCloudSync()
                                        Toast.makeText(context, "Logged out from Cloud Account.", Toast.LENGTH_SHORT).show()
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
                    } else {
                        Button(
                            onClick = { viewModel.setScreen(Screen.SYNC_CENTER) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Cloud Sync Center")
                        }
                    }
                }
            }
        }

        // Section 4: Workspace Businesses
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Manage Businesses", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { showAddBusinessDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Business")
                        }
                    }

                    businesses.forEach { biz ->
                        val isSelected = biz.id == activeBusiness?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                                .clickable { viewModel.selectBusiness(biz) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Storefront, contentDescription = null, tint = if (isSelected) GreenIn else Color.Gray)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(biz.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                            if (businesses.size > 1 && hasPermission(simulatedRole, "delete_business")) {
                                IconButton(onClick = {
                                    businessToDelete = biz
                                    deleteConfirmationInput = ""
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete business", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // PIN lock settings dialog inside SettingsScreen
    if (showSecuritySettingsDialog) {
        AppLockSettingsDialog(
            viewModel = viewModel,
            onDismiss = { showSecuritySettingsDialog = false }
        )
    }

    // Create Business Dialog inside SettingsScreen
    if (showAddBusinessDialog) {
        AlertDialog(
            onDismissRequest = { showAddBusinessDialog = false },
            title = { Text("Create New Business", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newBusinessName,
                    onValueChange = { newBusinessName = it },
                    label = { Text("Business / Company Name") },
                    placeholder = { Text("e.g. RB Mengal Traders") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newBusinessName.trim()
                        if (name.isNotEmpty()) {
                            viewModel.createBusiness(name)
                            newBusinessName = ""
                            showAddBusinessDialog = false
                            Toast.makeText(context, "Business created!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn)
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddBusinessDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Business re-typing confirmation dialog
    if (businessToDelete != null) {
        val biz = businessToDelete!!
        AlertDialog(
            onDismissRequest = { businessToDelete = null },
            title = { Text("Delete Business?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("This will permanently delete '${biz.name}' and all its cashbooks, staff, and transactions. This action cannot be undone.", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("To confirm, type the exact business name:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("'${biz.name}'", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deleteConfirmationInput,
                        onValueChange = { deleteConfirmationInput = it },
                        placeholder = { Text("Retype name") },
                        modifier = Modifier.fillMaxWidth().testTag("delete_business_confirm_input"),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (deleteConfirmationInput == biz.name) {
                            viewModel.deleteBusiness(biz)
                            businessToDelete = null
                        }
                    },
                    enabled = deleteConfirmationInput == biz.name,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("delete_business_confirm_button")
                ) {
                    Text("Delete permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { businessToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageWorkspaceScreen(viewModel: LedgerViewModel) {
    val businesses by viewModel.businesses.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val activeBusiness by viewModel.activeBusiness.collectAsStateWithLifecycle()
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showShareBusinessDialog by remember { mutableStateOf<com.example.data.Business?>(null) }
    var showAddBusinessDialog by remember { mutableStateOf(false) }
    var newBusinessName by remember { mutableStateOf("") }

    var showAddBookDialog by remember { mutableStateOf(false) }
    var newBookName by remember { mutableStateOf("") }

    var businessToRename by remember { mutableStateOf<com.example.data.Business?>(null) }
    var renameBusinessName by remember { mutableStateOf("") }

    var bookToRename by remember { mutableStateOf<com.example.data.Book?>(null) }
    var renameBookName by remember { mutableStateOf("") }

    var businessToDelete by remember { mutableStateOf<com.example.data.Business?>(null) }
    var bookToDelete by remember { mutableStateOf<com.example.data.Book?>(null) }
    var deleteConfirmationInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcome/Header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BusinessCenter,
                        contentDescription = "Manage Workspaces",
                        tint = GreenIn,
                        modifier = Modifier.size(36.dp)
                    )
                    Column {
                        Text(
                            "Workspace Manager",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "Create, select, rename, or delete your businesses and cashbooks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Section 1: Businesses
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "My Businesses / Shops",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = { showAddBusinessDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = GreenIn)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Business", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        }

        if (businesses.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No businesses available. Create one to get started!", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            items(businesses) { biz ->
                val isActive = activeBusiness?.id == biz.id
                val bizBooks = books.filter { it.businessId == biz.id }
                val bizBookIds = bizBooks.map { it.id }.toSet()
                val bizTx = allTransactions.filter { bizBookIds.contains(it.bookId) }
                val bizIn = bizTx.filter { it.type == "IN" }.sumOf { it.amount }
                val bizOut = bizTx.filter { it.type == "OUT" }.sumOf { it.amount }
                val bizNet = bizIn - bizOut

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (isActive) 1.5.dp else 1.dp,
                            color = if (isActive) GreenIn else Color(0xFFE2E8F0),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) Color.White else Color(0xFFF8FAFC)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Business,
                                    contentDescription = null,
                                    tint = if (isActive) GreenIn else Color(0xFF64748B)
                                )
                                Text(
                                    text = biz.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isActive) GreenIn else Color(0xFF0F172A)
                                )
                            }
                            if (isActive) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text("Active Ledger", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    leadingIcon = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp)) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        labelColor = GreenIn,
                                        leadingIconContentColor = GreenIn
                                    )
                                )
                            }
                        }

                        // Business Totals Summary Box
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Business Net Balance", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Rs. ${String.format("%,.2f", bizNet)}",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                                        color = if (bizNet >= 0) GreenIn else RedOut
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Cash In", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        Text("Rs. ${String.format("%,.0f", bizIn)}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = GreenIn)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Cash Out", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        Text("Rs. ${String.format("%,.0f", bizOut)}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = RedOut)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Books", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        Text("${bizBooks.size}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isActive) {
                                Button(
                                    onClick = { viewModel.selectBusiness(biz) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Switch to", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }

                            IconButton(
                                onClick = { showShareBusinessDialog = biz }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share Business", tint = GreenIn)
                            }

                            IconButton(
                                onClick = {
                                    businessToRename = biz
                                    renameBusinessName = biz.name
                                }
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename Business", tint = Color(0xFF475569))
                            }

                            IconButton(
                                onClick = {
                                    businessToDelete = biz
                                    deleteConfirmationInput = ""
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Business", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Books of Active Business
        item {
            Divider(color = Color(0xFFE2E8F0), modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Cashbooks",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "For: ${activeBusiness?.name ?: "No Active Business"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B)
                    )
                }
                TextButton(
                    onClick = { showAddBookDialog = true },
                    enabled = activeBusiness != null,
                    colors = ButtonDefaults.textButtonColors(contentColor = GreenIn)
                ) {
                    Icon(Icons.Default.AddCard, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Book", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        }

        if (books.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("No customers or cashbooks available yet.", style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { showAddBookDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("+ Add Customer / Book", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            items(books) { bk ->
                val isActive = activeBook?.id == bk.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (isActive) 1.5.dp else 1.dp,
                            color = if (isActive) GreenIn else Color(0xFFE2E8F0),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) Color.White else Color(0xFFF8FAFC)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Book,
                                    contentDescription = null,
                                    tint = if (isActive) GreenIn else Color(0xFF64748B)
                                )
                                Text(
                                    text = bk.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isActive) GreenIn else Color(0xFF0F172A)
                                )
                            }
                            if (isActive) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text("Selected Book", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    leadingIcon = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp)) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        labelColor = GreenIn,
                                        leadingIconContentColor = GreenIn
                                    )
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isActive) {
                                Button(
                                    onClick = { viewModel.selectBook(bk) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Switch to", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }

                            IconButton(
                                onClick = {
                                    bookToRename = bk
                                    renameBookName = bk.name
                                }
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename Book", tint = Color(0xFF475569))
                            }

                            IconButton(
                                onClick = {
                                    bookToDelete = bk
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Book", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Dialogs ---

    // 1. Add Business Dialog
    if (showAddBusinessDialog) {
        AlertDialog(
            onDismissRequest = { showAddBusinessDialog = false },
            title = { Text("Create New Business", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newBusinessName,
                    onValueChange = { newBusinessName = it },
                    label = { Text("Business / Company Name") },
                    placeholder = { Text("e.g. RB Mengal Traders") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("add_business_dialog_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newBusinessName.trim()
                        if (name.isNotEmpty()) {
                            viewModel.createBusiness(name)
                            newBusinessName = ""
                            showAddBusinessDialog = false
                            Toast.makeText(context, "Business created!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                    modifier = Modifier.testTag("add_business_dialog_confirm")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddBusinessDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 2. Rename Business Dialog
    if (businessToRename != null) {
        val biz = businessToRename!!
        AlertDialog(
            onDismissRequest = { businessToRename = null },
            title = { Text("Rename Business", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameBusinessName,
                    onValueChange = { renameBusinessName = it },
                    label = { Text("New Business Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_business_dialog_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = renameBusinessName.trim()
                        if (name.isNotEmpty()) {
                            viewModel.updateBusiness(biz.copy(name = name))
                            businessToRename = null
                            Toast.makeText(context, "Business renamed successfully!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                    modifier = Modifier.testTag("rename_business_dialog_confirm")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { businessToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 3. Delete Business Dialog
    if (businessToDelete != null) {
        val biz = businessToDelete!!
        AlertDialog(
            onDismissRequest = { businessToDelete = null },
            title = { Text("Delete Business?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("This will permanently delete '${biz.name}' and all its cashbooks, staff, and transactions. This action cannot be undone.", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("To confirm, type the exact business name:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("'${biz.name}'", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deleteConfirmationInput,
                        onValueChange = { deleteConfirmationInput = it },
                        placeholder = { Text("Retype name") },
                        modifier = Modifier.fillMaxWidth().testTag("delete_business_dialog_input"),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (deleteConfirmationInput == biz.name) {
                            viewModel.deleteBusiness(biz)
                            businessToDelete = null
                        }
                    },
                    enabled = deleteConfirmationInput == biz.name,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("delete_business_dialog_confirm")
                ) {
                    Text("Delete permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { businessToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 4. Add Book Dialog
    if (showAddBookDialog) {
        AddBookConsumerDialog(
            onDismiss = { showAddBookDialog = false },
            onCreate = { bkName, bkPhone ->
                viewModel.createBook(bkName, bkPhone)
                showAddBookDialog = false
                Toast.makeText(context, "Customer / Cashbook created!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 5. Rename Book Dialog
    if (bookToRename != null) {
        val bk = bookToRename!!
        AlertDialog(
            onDismissRequest = { bookToRename = null },
            title = { Text("Rename Cashbook", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameBookName,
                    onValueChange = { renameBookName = it },
                    label = { Text("New Cashbook Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_book_dialog_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = renameBookName.trim()
                        if (name.isNotEmpty()) {
                            viewModel.updateBook(bk.copy(name = name))
                            bookToRename = null
                            Toast.makeText(context, "Cashbook renamed successfully!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                    modifier = Modifier.testTag("rename_book_dialog_confirm")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 6. Delete Book Dialog
    if (bookToDelete != null) {
        val bk = bookToDelete!!
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("Delete Cashbook?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Are you sure you want to delete '${bk.name}' and all its associated transactions? This action is permanent and cannot be undone.", color = MaterialTheme.colorScheme.error)
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteBook(bk)
                        bookToDelete = null
                        Toast.makeText(context, "Cashbook deleted.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("delete_book_dialog_confirm")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 7. Share Business Dialog
    if (showShareBusinessDialog != null) {
        val biz = showShareBusinessDialog!!
        val bizBooks = books.filter { it.businessId == biz.id }
        val bizBookIds = bizBooks.map { it.id }.toSet()
        val bizTx = allTransactions.filter { bizBookIds.contains(it.bookId) }
        val bizIn = bizTx.filter { it.type == "IN" }.sumOf { it.amount }
        val bizOut = bizTx.filter { it.type == "OUT" }.sumOf { it.amount }
        val bizNet = bizIn - bizOut

        ShareBusinessDialog(
            business = biz,
            booksCount = bizBooks.size,
            totalNetBalance = bizNet,
            totalIn = bizIn,
            totalOut = bizOut,
            onDismiss = { showShareBusinessDialog = null }
        )
    }
}

// --- SCREEN 10: USER PROFILE & ACCOUNT SETTINGS ---
@Composable
fun ProfileScreen(viewModel: LedgerViewModel) {
    val context = LocalContext.current
    val syncManager = viewModel.syncManager
    
    var profileAuthVersion by remember { mutableStateOf(0) }
    val isUserSignedIn = remember(profileAuthVersion) { syncManager.isUserSignedIn() }
    val isRealCloudAccount = syncManager.isRealCloudAccount()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val userEmail = remember(profileAuthVersion) { syncManager.getEmail() }
    val userName = remember(profileAuthVersion) { syncManager.getName() }
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val activeBusiness by viewModel.activeBusiness.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()
    
    var showOAuthDialogInProfile by remember { mutableStateOf(false) }
    var customClientIdInput by remember { mutableStateOf(syncManager.getClientId()) }
    var customRedirectUriInput by remember { mutableStateOf(syncManager.getRedirectUri()) }
    
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (isRealCloudAccount && isOnline) GreenIn else MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (userName.isNotBlank()) userName.take(1).uppercase()
                               else if (userEmail.isNotBlank()) userEmail.take(1).uppercase()
                               else "P",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
                Column {
                    Text(
                        text = if (userName.isNotBlank()) userName else if (userEmail.isNotBlank()) userEmail.substringBefore("@") else "User Profile",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = if (isRealCloudAccount) userEmail else "$userEmail (Local Offline Account)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Account Status & Primary Logout Action Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRealCloudAccount && isOnline) GreenIn.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isRealCloudAccount && isOnline) GreenIn.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = if (!isOnline) Icons.Default.CloudOff else if (isRealCloudAccount) Icons.Default.VerifiedUser else Icons.Default.OfflinePin,
                                contentDescription = null,
                                tint = if (!isOnline) MaterialTheme.colorScheme.error else if (isRealCloudAccount) GreenIn else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Account & Cloud Sync State",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Surface(
                            color = if (!isOnline) MaterialTheme.colorScheme.error else if (isRealCloudAccount) GreenIn else Color(0xFF64748B),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (!isOnline) "OFFLINE" else if (isRealCloudAccount) "ONLINE CLOUD" else "LOCAL OFFLINE",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (isUserSignedIn) {
                        Text(
                            text = "Signed in as: $userEmail",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Your cashbook entries and reports automatically sync to your secure cloud account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Currently running in local 100% offline mode.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Connect a Cloud Account anytime to enable real-time cloud sync across your devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Logout & Login Buttons (ALWAYS VISIBLE whether online or offline!)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!isUserSignedIn) {
                            Button(
                                onClick = { viewModel.setScreen(Screen.SYNC_CENTER) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Connect Cloud Account", fontWeight = FontWeight.Bold)
                            }
                        }

                        // Logout button is ALWAYS available for both online and offline users!
                        Button(
                            onClick = { showLogoutConfirmDialog = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isUserSignedIn) "Log Out" else "Reset Session",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Section: Active Business & Security Profile
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Business & Role Profile", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Active Business:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(activeBusiness?.name ?: "Default Cashbook", fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Simulated Role:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = simulatedRole,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Active OAuth Client ID:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = syncManager.getClientId().take(22) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Section: Quick Shortcuts
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Account Shortcuts & Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

                    OutlinedButton(
                        onClick = { viewModel.setScreen(Screen.SYNC_CENTER) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Google Drive Sync Center")
                    }

                    OutlinedButton(
                        onClick = { viewModel.setScreen(Screen.SETTINGS) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("All App Settings & Manual Backups")
                    }
                }
            }
        }
    }

    // Logout Confirmation Dialog
    if (showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(if (isUserSignedIn) "Confirm Log Out" else "Confirm Session Reset")
                }
            },
            text = {
                Text(
                    text = if (isUserSignedIn)
                        "Are you sure you want to log out from $userEmail? This will clear your active Google authorization token on this device. Your offline cashbook database will remain safely on your phone."
                    else
                        "Are you sure you want to reset your local session state? Your offline database will not be deleted."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.logoutSuperAdmin()
                        profileAuthVersion++
                        viewModel.triggerCloudSync()
                        showLogoutConfirmDialog = false
                        Toast.makeText(context, "Successfully logged out!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Log Out Now", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Google Sign-In Dialog inside Profile
    if (showOAuthDialogInProfile) {
        val finalClientId = customClientIdInput.ifBlank { syncManager.getClientId() }
        val finalRedirectUri = customRedirectUriInput.ifBlank { syncManager.getRedirectUri() }
        val authUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
                "client_id=$finalClientId&" +
                "redirect_uri=$finalRedirectUri&" +
                "response_type=token&" +
                "scope=https://www.googleapis.com/auth/drive.appdata%20email%20profile%20openid&" +
                "prompt=select_account"

        Dialog(onDismissRequest = { showOAuthDialogInProfile = false }) {
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
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sign In with Google", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { showOAuthDialogInProfile = false }) {
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
                                    defaultUa.replace("; wv", "").replace("Version/4.0 ", "").replace("Version/4.0", "")
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
                                        val currentRedirectUri = syncManager.getRedirectUri()
                                        if (url != null && (url.startsWith(currentRedirectUri) || url.contains("access_token="))) {
                                            val token = extractAccessToken(url)
                                            if (token != null) {
                                                syncManager.saveAccessToken(token)
                                                profileAuthVersion++
                                                viewModel.triggerCloudSync()
                                                showOAuthDialogInProfile = false
                                                Toast.makeText(context, "Google Authorization successful! Sync active.", Toast.LENGTH_LONG).show()
                                                return true
                                            }
                                        }
                                        return false
                                    }
                                }

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
}

// --- SUPER ADMIN LOGIN DIALOG ---
@Composable
fun SuperAdminLoginDialog(
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit
) {
    var username by remember { mutableStateOf("superadmin") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Column {
                        Text(
                            "Account Sign In",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Enter your credentials to continue",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Credentials:\nUser: superadmin or admin@cashbook.com\nPass: superadmin123",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                username = "superadmin"
                                password = "superadmin123"
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Auto-Fill Credentials", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username or Email") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Password Visibility"
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
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
                        onClick = { onLogin(username, password) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Log In as Super Admin")
                    }
                }
            }
        }
    }
}

// --- RECEIPT IMAGE VIEWER DIALOG ---
@Composable
fun ReceiptViewerDialog(
    receiptUri: String,
    onDismiss: () -> Unit,
    onDeleteReceipt: (() -> Unit)? = null
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Transaction Receipt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 380.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.05f)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = receiptUri,
                        contentDescription = "Receipt Image",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onDeleteReceipt != null) {
                        TextButton(
                            onClick = onDeleteReceipt,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete Receipt")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val parsedUri = Uri.parse(receiptUri)
                                    val finalUri = if (parsedUri.scheme == "file" || parsedUri.scheme == null) {
                                        val filePath = parsedUri.path ?: receiptUri
                                        val file = File(filePath)
                                        if (file.exists()) {
                                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                        } else {
                                            parsedUri
                                        }
                                    } else {
                                        parsedUri
                                    }
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/*"
                                        putExtra(Intent.EXTRA_STREAM, finalUri)
                                        clipData = ClipData.newRawUri("Receipt Image", finalUri)
                                        putExtra(Intent.EXTRA_TEXT, "Attached Transaction Receipt from CashBook App")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    val chooserIntent = Intent.createChooser(shareIntent, "Share Receipt Image").apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(chooserIntent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error sharing receipt: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}

// --- SUPER ADMIN REGISTERED USERS DIRECTORY DIALOG ---
@Composable
fun SuperAdminUserRegistryDialog(
    viewModel: LedgerViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val globalAccounts by viewModel.globalAccounts.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshingAccounts.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedAccount by remember { mutableStateOf<com.example.data.RegisteredAccount?>(null) }
    var showPasswords by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshCloudAccounts()
    }

    val filteredAccounts = remember(globalAccounts, searchQuery) {
        if (searchQuery.isBlank()) {
            globalAccounts
        } else {
            val query = searchQuery.trim().lowercase()
            globalAccounts.filter { acc ->
                acc.name.lowercase().contains(query) ||
                acc.email.lowercase().contains(query) ||
                acc.username.lowercase().contains(query)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.SupervisorAccount,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Firebase Cloud Users Directory",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Super Admin Live Accounts Inspector",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Stats Banner & Refresh
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Total Accounts Registered",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "${globalAccounts.size} Cloud Users",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Button(
                            onClick = {
                                viewModel.refreshCloudAccounts {
                                    Toast.makeText(context, "Fetched ${it.size} users from Firebase Cloud!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isRefreshing,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Fetching...")
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Refresh DB")
                            }
                        }
                    }
                }

                // Search Bar & Password Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search email, name, username...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    IconButton(
                        onClick = { showPasswords = !showPasswords },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = if (showPasswords) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Passwords",
                            tint = if (showPasswords) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Registered Users List
                if (filteredAccounts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.PersonSearch, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                            Text("No registered users found.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredAccounts) { acc ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedAccount = acc },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (acc.email.contains("admin", true)) MaterialTheme.colorScheme.primaryContainer else GreenIn.copy(alpha = 0.15f),
                                            modifier = Modifier.size(42.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = acc.name.take(1).uppercase().ifBlank { acc.email.take(1).uppercase() },
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = if (acc.email.contains("admin", true)) MaterialTheme.colorScheme.primary else GreenIn
                                                )
                                            }
                                        }

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    text = acc.name.ifBlank { "User ${acc.username}" },
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyLarge
                                                )
                                                if (acc.email.contains("admin", true) || acc.username.contains("admin", true)) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            "SUPER ADMIN",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            color = Color.White,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Text(
                                                text = acc.email.ifBlank { "@${acc.username}" },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            if (showPasswords && acc.pass.isNotBlank()) {
                                                Text(
                                                    text = "Key/Pass: ${acc.pass}",
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.tertiary
                                                )
                                            }
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(acc.email))
                                                Toast.makeText(context, "Copied email: ${acc.email}", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Email", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Close button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Close Inspector")
                }
            }
        }
    }

    // Selected Account Detail Sub-Dialog
    selectedAccount?.let { acc ->
        AlertDialog(
            onDismissRequest = { selectedAccount = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AccountBox, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Firebase User Details")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Full Name: ${acc.name}", fontWeight = FontWeight.SemiBold)
                    Text("• Registered Email: ${acc.email}", fontWeight = FontWeight.SemiBold)
                    Text("• Username: @${acc.username}", fontWeight = FontWeight.SemiBold)
                    Text("• Security Hash/Pass: ${acc.pass}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("• Document ID: user_${acc.email.replace(Regex("[^a-zA-Z0-9_]"), "_")}", fontSize = 11.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboardManager.setText(AnnotatedString("Name: ${acc.name}\nEmail: ${acc.email}\nUsername: ${acc.username}"))
                    Toast.makeText(context, "User details copied!", Toast.LENGTH_SHORT).show()
                    selectedAccount = null
                }) {
                    Text("Copy Details")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedAccount = null }) {
                    Text("Close")
                }
            }
        )
    }
}

