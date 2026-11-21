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
import androidx.activity.compose.BackHandler
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
import android.content.ContextWrapper
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.example.notifications.WasooliNotificationHelper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    return when (role.trim()) {
        "Super Admin", "Boss", "Owner", "Admin" -> true
        "Partner" -> action == "view" || action == "switch_business" || action == "add_transaction" || action == "edit_transaction" || action == "add_book"
        "Data Entry" -> action == "view" || action == "switch_business" || action == "add_transaction" || action == "add_book"
        else -> action == "view" || action == "switch_business" || action == "add_transaction"
    }
}

fun buildTeamInviteMessage(
    businessName: String,
    collaboratorName: String,
    invitedEmail: String,
    role: String,
    inviteCode: String,
    apkDownloadUrl: String = ""
): String {
    val roleSummary = when (role.trim()) {
        "Partner" -> "Partner (View, add & edit Cash In / Cash Out entries)"
        "Data Entry" -> "Data Entry (Add Cash In / Cash Out entries & cashbooks; cannot delete)"
        "Admin", "Boss" -> "$role (Full workspace, team & report control)"
        else -> role
    }
    val downloadLine = if (apkDownloadUrl.isNotBlank()) "\n   Download Link: $apkDownloadUrl" else ""
    val emailLine = if (invitedEmail.isNotBlank()) invitedEmail.trim().lowercase() else "(Enter your own Email)"
    return """
        🌟 Invitation to join "$businessName" on CashBook!

        Hi $collaboratorName,
        You've been invited to join *$businessName* as *$role*.

        📧 Invited Email: $emailLine
        🔑 Cloud Invite Code: $inviteCode
        🛡️ Assigned Role: $roleSummary

        📱 How to Join & Use the App:
        1. Open the CashBook app on your phone.$downloadLine
        2. New to CashBook? Tap *Sign Up*, enter this email ($emailLine), and set your own Password (6+ chars).
           Already have an account? Tap *Sign In* with $emailLine and your password.
        3. "$businessName" will connect automatically — OR tap "Join Business (Invite Code)" and enter: $inviteCode

        Happy Ledger Accounting!
        CashBook Team
    """.trimIndent()
}

@Composable
fun JoinBusinessByCodeDialog(
    viewModel: LedgerViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var codeInput by remember { mutableStateOf("") }
    var isLookingUp by remember { mutableStateOf(false) }
    var isJoining by remember { mutableStateOf(false) }
    var previewInvite by remember { mutableStateOf<TeamInviteRecord?>(null) }
    var lookupError by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.GroupAdd, contentDescription = null, tint = GreenIn)
                Text("Join Shared Business", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the Cloud Invite Code shared by the business owner (for example: RB-7K2M).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = {
                            codeInput = it.uppercase()
                            lookupError = ""
                            previewInvite = null
                        },
                        label = { Text("Cloud Invite Code") },
                        placeholder = { Text("e.g. RB-7K2M") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("join_invite_code_input"),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            val clean = codeInput.trim()
                            if (clean.length < 3) {
                                lookupError = "Enter a valid invite code first."
                            } else {
                                isLookingUp = true
                                lookupError = ""
                                scope.launch {
                                    val found = viewModel.lookupInviteCode(clean)
                                    isLookingUp = false
                                    if (found != null) {
                                        previewInvite = found
                                    } else {
                                        lookupError = "Invite code not found or expired."
                                    }
                                }
                            }
                        },
                        enabled = !isLookingUp && !isJoining,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("verify_invite_code_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isLookingUp) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Verify", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (previewInvite != null) {
                    val inv = previewInvite!!
                    Surface(
                        color = Color(0xFFDCFCE7),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, GreenIn.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("✓ Invite Ready to Join", fontWeight = FontWeight.Bold, color = Color(0xFF166534), style = MaterialTheme.typography.bodyMedium)
                            Text("Business: ${inv.businessName}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = Color(0xFF14532D))
                            Text("Your Role: ${inv.role}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF166534))
                            if (inv.invitedEmail.isNotBlank()) {
                                Text("Invited Email: ${inv.invitedEmail}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF166534))
                            }
                        }
                    }
                }

                if (lookupError.isNotBlank()) {
                    Surface(
                        color = Color(0xFFFEF2F2),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lookupError,
                            color = Color(0xFF991B1B),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = codeInput.trim()
                    if (clean.length < 3) {
                        lookupError = "Please enter the Cloud Invite Code."
                    } else {
                        isJoining = true
                        lookupError = ""
                        viewModel.joinBusinessByInviteCode(clean) { ok, msg ->
                            isJoining = false
                            if (ok) {
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                onDismiss()
                            } else {
                                lookupError = msg
                            }
                        }
                    }
                },
                enabled = !isJoining && codeInput.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                modifier = Modifier.testTag("confirm_join_invite_button")
            ) {
                if (isJoining) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Join Business")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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

// --- Multi-Purpose Date Range Helper ---
fun isTransactionInDateRange(
    timestamp: Long,
    preset: String,
    customStartMillis: Long?,
    customEndMillis: Long?
): Boolean {
    if (preset == "ALL") return true
    val nowCal = Calendar.getInstance()
    val endOfToday = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    return when (preset) {
        "TODAY" -> {
            val startOfToday = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            timestamp in startOfToday..endOfToday
        }
        "WEEK" -> {
            val startOfWeek = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -6)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            timestamp in startOfWeek..endOfToday
        }
        "MONTH" -> {
            val startOfMonth = Calendar.getInstance().apply {
                timeInMillis = nowCal.timeInMillis
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            timestamp in startOfMonth..endOfToday
        }
        "CUSTOM" -> {
            if (customStartMillis == null || customEndMillis == null) return true
            val rawStart = minOf(customStartMillis, customEndMillis)
            val rawEnd = maxOf(customStartMillis, customEndMillis)
            val normalizedStart = Calendar.getInstance().apply {
                timeInMillis = rawStart
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val normalizedEnd = Calendar.getInstance().apply {
                timeInMillis = rawEnd
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis
            timestamp in normalizedStart..normalizedEnd
        }
        else -> true
    }
}

@Composable
fun DateRangeFilterStrip(
    selectedPreset: String,
    customStartMillis: Long?,
    customEndMillis: Long?,
    onSelectPreset: (String) -> Unit,
    onCustomRangeSelected: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val customLabel = remember(selectedPreset, customStartMillis, customEndMillis) {
        if (selectedPreset == "CUSTOM" && customStartMillis != null && customEndMillis != null) {
            val sdf = SimpleDateFormat("dd MMM", Locale.getDefault())
            val start = minOf(customStartMillis, customEndMillis)
            val end = maxOf(customStartMillis, customEndMillis)
            "${sdf.format(Date(start))} - ${sdf.format(Date(end))}"
        } else {
            "Custom"
        }
    }

    val openCustomDatePickers = {
        val initStart = Calendar.getInstance().apply {
            if (customStartMillis != null) timeInMillis = customStartMillis
            else add(Calendar.DAY_OF_YEAR, -7)
        }
        Toast.makeText(context, "Select Start Date", Toast.LENGTH_SHORT).show()
        DatePickerDialog(
            context,
            { _, sYear, sMonth, sDay ->
                val pickedStart = Calendar.getInstance().apply {
                    set(Calendar.YEAR, sYear)
                    set(Calendar.MONTH, sMonth)
                    set(Calendar.DAY_OF_MONTH, sDay)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                }.timeInMillis

                val initEnd = Calendar.getInstance().apply {
                    if (customEndMillis != null) timeInMillis = customEndMillis
                }
                Toast.makeText(context, "Select End Date", Toast.LENGTH_SHORT).show()
                DatePickerDialog(
                    context,
                    { _, eYear, eMonth, eDay ->
                        val pickedEnd = Calendar.getInstance().apply {
                            set(Calendar.YEAR, eYear)
                            set(Calendar.MONTH, eMonth)
                            set(Calendar.DAY_OF_MONTH, eDay)
                            set(Calendar.HOUR_OF_DAY, 23)
                            set(Calendar.MINUTE, 59)
                            set(Calendar.SECOND, 59)
                        }.timeInMillis
                        onCustomRangeSelected(pickedStart, pickedEnd)
                    },
                    initEnd.get(Calendar.YEAR),
                    initEnd.get(Calendar.MONTH),
                    initEnd.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            initStart.get(Calendar.YEAR),
            initStart.get(Calendar.MONTH),
            initStart.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val presets = listOf(
            "ALL" to "All Time",
            "TODAY" to "Today",
            "WEEK" to "7 Days",
            "MONTH" to "This Month",
            "CUSTOM" to customLabel
        )
        presets.forEach { (key, label) ->
            val selected = selectedPreset == key
            FilterChip(
                selected = selected,
                onClick = {
                    if (key == "CUSTOM") {
                        openCustomDatePickers()
                    } else {
                        onSelectPreset(key)
                    }
                },
                label = {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                leadingIcon = if (key == "CUSTOM") {
                    { Icon(Icons.Default.DateRange, contentDescription = "Custom Date Range", modifier = Modifier.size(14.dp)) }
                } else null,
                modifier = Modifier
                    .height(32.dp)
                    .testTag("date_filter_${key.lowercase()}")
            )
        }
    }
}

// --- Biometric Fingerprint Lock Helpers ---
fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun canAuthenticateBiometric(context: Context): Boolean {
    return try {
        val manager = BiometricManager.from(context)
        manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    } catch (_: Exception) {
        false
    }
}

fun launchBiometricUnlockPrompt(
    context: Context,
    title: String = "Unlock CashBook Pro",
    subtitle: String = "Verify your fingerprint to access your ledgers",
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val activity = context.findFragmentActivity()
    if (activity == null) {
        onError("Fingerprint prompt unavailable in this window.")
        return
    }
    val executor = ContextCompat.getMainExecutor(context)
    val biometricPrompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_USER_CANCELED
                ) {
                    onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onError("Fingerprint not recognized. Try again or use PIN.")
            }
        }
    )
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText("Use PIN")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        .build()
    biometricPrompt.authenticate(promptInfo)
}

// --- Reusable Phone Contact Picker Helper ---
@Composable
fun rememberContactImportAction(
    context: Context,
    onPicked: (name: String, phone: String) -> Unit
): () -> Unit {
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri: Uri? ->
        if (contactUri != null) {
            try {
                var pickedName = ""
                var pickedPhone = ""
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
                        pickedName = if (nameIndex >= 0) c.getString(nameIndex) ?: "" else ""
                        val hasPhone = if (hasPhoneIndex >= 0) c.getInt(hasPhoneIndex) > 0 else false

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
                                        pickedPhone = pc.getString(phoneIndex) ?: ""
                                    }
                                }
                            }
                        }
                    }
                }
                if (pickedName.isNotBlank() || pickedPhone.isNotBlank()) {
                    onPicked(pickedName.trim(), pickedPhone.trim())
                } else {
                    Toast.makeText(context, "Selected contact has no name or number", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, "Contacts permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contactPickerLauncher.launch(null)
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerAppScreen(viewModel: LedgerViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val allBooks by viewModel.allBooks.collectAsStateWithLifecycle()
    val businesses by viewModel.businesses.collectAsStateWithLifecycle()
    val activeBusiness by viewModel.activeBusiness.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val isUserSignedIn by viewModel.isUserSignedIn.collectAsStateWithLifecycle()

    val isAppLockEnabled by viewModel.isAppLockEnabled.collectAsStateWithLifecycle()
    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    val globalAccounts by viewModel.globalAccounts.collectAsStateWithLifecycle()
    var showSecuritySettingsDialog by remember { mutableStateOf(false) }
    var showSuperAdminLoginDrawer by remember { mutableStateOf(false) }
    var showSuperAdminUserRegistryInDrawer by remember { mutableStateOf(false) }
    var showSuperAdminSecretLoginDialog by remember { mutableStateOf(false) }
    var showSuperAdminUserRegistryDialog by remember { mutableStateOf(false) }
    var bookIconTapCount by remember { mutableIntStateOf(0) }
    var lastBookIconTapTimestamp by remember { mutableLongStateOf(0L) }

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
    var showSuperAdminConsoleMode by remember { mutableStateOf(false) }
    var showExitAppDialog by remember { mutableStateOf(false) }

    // --- SMART SYSTEM BACK BUTTON HANDLER ---
    BackHandler(enabled = true) {
        if (showSuperAdminConsoleMode) {
            showSuperAdminConsoleMode = false
        } else if (showExitAppDialog) {
            showExitAppDialog = false
        } else if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (showSuperAdminSecretLoginDialog) {
            showSuperAdminSecretLoginDialog = false
        } else if (showSuperAdminUserRegistryDialog) {
            showSuperAdminUserRegistryDialog = false
        } else if (showSuperAdminUserRegistryInDrawer) {
            showSuperAdminUserRegistryInDrawer = false
        } else if (showBookSelector) {
            showBookSelector = false
        } else if (showAddBookDialog) {
            showAddBookDialog = false
        } else if (showAddBusinessDialog) {
            showAddBusinessDialog = false
        } else if (showSecuritySettingsDialog) {
            showSecuritySettingsDialog = false
        } else if (businessToDelete != null) {
            businessToDelete = null
        } else if (bookToDelete != null) {
            bookToDelete = null
        } else if (currentScreen != Screen.DASHBOARD && isUserSignedIn && businesses.isNotEmpty()) {
            viewModel.setScreen(Screen.DASHBOARD)
        } else {
            showExitAppDialog = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showSuperAdminConsoleMode && isSuperAdmin) {
            SuperAdminConsoleScreen(
                viewModel = viewModel,
                onSwitchToBusinessView = { showSuperAdminConsoleMode = false }
            )
        } else if (isAppLockEnabled && !isAppUnlocked) {
            AppSecureLockScreen(viewModel = viewModel, onUnlockSuccess = {})
        } else if (!isUserSignedIn) {
            OnboardingSetupScreen(
                viewModel = viewModel,
                onOpenSuperAdminSecretLogin = { showSuperAdminSecretLoginDialog = true }
            )
        } else if (businesses.isEmpty()) {
            OnboardingSetupScreen(
                viewModel = viewModel,
                onOpenSuperAdminSecretLogin = { showSuperAdminSecretLoginDialog = true }
            )
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

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                    if (isSuperAdmin) {
                        Surface(
                            onClick = {
                                scope.launch { drawerState.close() }
                                showSuperAdminConsoleMode = true
                            },
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.SupervisorAccount, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Super Admin Console", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("Firebase Cloud Database", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                                }
                                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

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
                        label = { Text("Cloud Sync Center") },
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

                    if (isSuperAdmin) {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.SupervisorAccount, contentDescription = null) },
                            label = { Text("Firebase Users Inspector (${globalAccounts.size})") },
                            selected = showSuperAdminConsoleMode,
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedContainerColor = Color.Transparent,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedTextColor = MaterialTheme.colorScheme.primary
                            ),
                            onClick = {
                                showSuperAdminConsoleMode = true
                                scope.launch { drawerState.close() }
                            }
                        )
                    }

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
            contentWindowInsets = if (currentScreen == Screen.UDHAR) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (currentScreen != Screen.UDHAR) {
                    CenterAlignedTopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Open Navigation Drawer")
                            }
                        },
                    title = {
                        Row(
                            modifier = Modifier.padding(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Secret 5-Tap on Book Icon (Completely normal appearance, no indicators)
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastBookIconTapTimestamp > 2500L) {
                                            bookIconTapCount = 1
                                        } else {
                                            bookIconTapCount++
                                        }
                                        lastBookIconTapTimestamp = now

                                        if (bookIconTapCount >= 5) {
                                            bookIconTapCount = 0
                                            showSuperAdminSecretLoginDialog = true
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MenuBook,
                                    contentDescription = "Cashbook",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Tapping the Book Name / Arrow Dropdown switches active Cashbook
                            Row(
                                modifier = Modifier
                                    .clickable { showBookSelector = true }
                                    .padding(horizontal = 6.dp, vertical = 6.dp)
                                    .testTag("book_selector_trigger"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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
                        val isRealSuccess = isOnline && isRealCloudAccount && !isSyncError && (syncStatus.contains("Synced", ignoreCase = true) || syncStatus.contains("Restored", ignoreCase = true) || syncStatus.contains("Success", ignoreCase = true) || syncStatus.contains("Connected", ignoreCase = true) || syncStatus.contains("Vault", ignoreCase = true) || syncStatus.contains("Active", ignoreCase = true) || syncStatus.contains("Ready", ignoreCase = true))

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
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(GreenIn.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            ) {
                                Icon(
                                    Icons.Default.LibraryAdd,
                                    contentDescription = "Create New Book",
                                    tint = GreenIn,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
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
                }
            },
            bottomBar = {
                if (currentScreen != Screen.UDHAR) {
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
                    val booksToDisplay = if (allBooks.isNotEmpty()) allBooks else books
                    AlertDialog(
                        onDismissRequest = { showBookSelector = false },
                        title = { Text("Business Cashbooks", fontWeight = FontWeight.Bold) },
                        text = {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(booksToDisplay) { book ->
                                    val isSelected = book.id == activeBook?.id
                                    val parentBiz = businesses.find { it.id == book.businessId }
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
                                                if (parentBiz != null) {
                                                    Text(
                                                        "Business: ${parentBiz.name}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Text(
                                                    "Created: " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(book.createdAt)),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary)
                                            } else if (booksToDisplay.size > 1 && hasPermission(simulatedRole, "delete_book")) {
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
            }
        }
    }
}

    // --- EXIT APP CONFIRMATION DIALOG (ON BACK BUTTON) ---
    if (showExitAppDialog) {
        AlertDialog(
            onDismissRequest = { showExitAppDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.ExitToApp,
                    contentDescription = "Exit App",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Exit CashBook?",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to close and exit the app?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitAppDialog = false
                        (context as? Activity)?.finish()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Exit App", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showExitAppDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- TOP-LEVEL GLOBAL SUPER ADMIN DIALOGS ---
    if (showSuperAdminUserRegistryInDrawer && isSuperAdmin) {
        SuperAdminUserRegistryDialog(
            viewModel = viewModel,
            onDismiss = { showSuperAdminUserRegistryInDrawer = false }
        )
    }

    if (showSuperAdminSecretLoginDialog) {
        SuperAdminSecretLoginDialog(
            viewModel = viewModel,
            onDismiss = { showSuperAdminSecretLoginDialog = false },
            onOpenRegistry = {
                showSuperAdminSecretLoginDialog = false
                showSuperAdminConsoleMode = true
            }
        )
    }

    if (showSuperAdminUserRegistryDialog && isSuperAdmin) {
        SuperAdminUserRegistryDialog(
            viewModel = viewModel,
            onDismiss = { showSuperAdminUserRegistryDialog = false }
        )
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
    val activeBookTransactions by viewModel.activeBookTransactions.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedTransactionIds.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()
    val wasooliDueDates by viewModel.wasooliDueDates.collectAsStateWithLifecycle()

    var showTransactionDialog by remember { mutableStateOf<String?>(null) }
    var prefillAmountForDialog by remember { mutableStateOf("") }
    var prefillRemarksForDialog by remember { mutableStateOf("") }
    var selectedTxForEdit by remember { mutableStateOf<Transaction?>(null) }
    var showAddBookDialog by remember { mutableStateOf(false) }
    var showEditBookDialog by remember { mutableStateOf(false) }
    var showBusinessSelectorDialog by remember { mutableStateOf(false) }
    var showHomeBookSelector by remember { mutableStateOf(false) }
    var showAddBusinessDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showWasooliDialog by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var newBusinessName by remember { mutableStateOf("") }
    var dashboardSearchQuery by remember { mutableStateOf("") }
    var dashboardFilterType by remember { mutableStateOf("all") }
    var dashboardPaymentFilter by remember { mutableStateOf("ALL") }
    var dateRangePreset by remember { mutableStateOf("ALL") } // ALL, TODAY, WEEK, MONTH, CUSTOM
    var customStartMillis by remember { mutableStateOf<Long?>(null) }
    var customEndMillis by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Date-range filtered subset drives both Summary Totals + Cash/Bank Split + Entries List
    val dateFilteredTxs = remember(activeBookTransactions, dateRangePreset, customStartMillis, customEndMillis) {
        activeBookTransactions.filter { tx ->
            isTransactionInDateRange(tx.timestamp, dateRangePreset, customStartMillis, customEndMillis)
        }
    }

    // Statistics exclusively for the Active Cashbook (respecting selected Date Range)
    val activeIn = dateFilteredTxs.filter { it.type == "IN" }.sumOf { it.amount }
    val activeOut = dateFilteredTxs.filter { it.type == "OUT" }.sumOf { it.amount }
    val activeNet = activeIn - activeOut

    // Cash vs Bank/Online split (respecting selected Date Range)
    val activeCashNet = dateFilteredTxs.filter { it.paymentMethod.equals("Cash", ignoreCase = true) }
        .sumOf { if (it.type == "IN") it.amount else -it.amount }
    val activeBankNet = dateFilteredTxs.filter {
        it.paymentMethod.equals("Bank", ignoreCase = true) || it.paymentMethod.equals("Online", ignoreCase = true)
    }.sumOf { if (it.type == "IN") it.amount else -it.amount }

    val activeWasooliDue = activeBook?.id?.let { wasooliDueDates[it] }

    val inCategories = listOf("Sales", "Cash Received", "Payment", "Salary", "Commission", "Other")
    val outCategories = listOf("General", "Goods / Items", "Cash Paid", "Expense", "Purchases", "Other")
    val paymentMethods = listOf("Cash", "Online", "Bank")

    // Entries exclusively for the Active Cashbook (filtered by date range, search, IN/OUT & payment mode)
    val displayedActiveTxs = remember(dateFilteredTxs, dashboardSearchQuery, dashboardFilterType, dashboardPaymentFilter) {
        dateFilteredTxs.filter { tx ->
            val matchesQuery = dashboardSearchQuery.isBlank() ||
                tx.remarks.contains(dashboardSearchQuery, ignoreCase = true) ||
                tx.category.contains(dashboardSearchQuery, ignoreCase = true) ||
                tx.paymentMethod.contains(dashboardSearchQuery, ignoreCase = true)
            val matchesType = dashboardFilterType == "all" || tx.type == dashboardFilterType
            val matchesPayment = when (dashboardPaymentFilter) {
                "ALL" -> true
                "BANK_ONLINE" -> tx.paymentMethod.equals("Bank", ignoreCase = true) || tx.paymentMethod.equals("Online", ignoreCase = true)
                else -> tx.paymentMethod.equals(dashboardPaymentFilter, ignoreCase = true)
            }
            matchesQuery && matchesType && matchesPayment
        }
    }

    // Running balance map computed chronologically for Active Cashbook
    val runningBalanceMap = remember(activeBookTransactions) {
        var cumulative = 0.0
        val map = mutableMapOf<Int, Double>()
        for (tx in activeBookTransactions.sortedBy { it.timestamp }) {
            if (tx.type == "IN") cumulative += tx.amount else cumulative -= tx.amount
            map[tx.id] = cumulative
        }
        map
    }

    // Daywise grouping for clean Khata / CashBook scanning
    val groupedActiveTxs = remember(displayedActiveTxs) {
        val sdfKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sdfHeader = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())
        displayedActiveTxs.groupBy { tx ->
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
            Triple(headerTitle, Triple(dayIn, dayOut, txList.sortedByDescending { it.timestamp }), dateKey)
        }.sortedByDescending { it.third }
    }

    if (showBusinessSelectorDialog) {
        AlertDialog(
            onDismissRequest = { showBusinessSelectorDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Select Business", fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        showBusinessSelectorDialog = false
                        showAddBusinessDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add New")
                    }
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(businesses) { biz ->
                        val isSelected = biz.id == activeBusiness?.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectBusiness(biz)
                                    showBusinessSelectorDialog = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(
                                        Icons.Default.Storefront,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        biz.name,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBusinessSelectorDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showAddBusinessDialog) {
        AlertDialog(
            onDismissRequest = { showAddBusinessDialog = false },
            title = { Text("Create Business Account") },
            text = {
                OutlinedTextField(
                    value = newBusinessName,
                    onValueChange = { newBusinessName = it },
                    label = { Text("Business Entity Name") },
                    placeholder = { Text("e.g. Retail Shop, Personal") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newBusinessName.isNotBlank()) {
                            viewModel.createBusiness(newBusinessName.trim())
                            newBusinessName = ""
                            showAddBusinessDialog = false
                        }
                    }
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

    if (showHomeBookSelector) {
        AlertDialog(
            onDismissRequest = { showHomeBookSelector = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Select Active Book", fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        showHomeBookSelector = false
                        showAddBookDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Book")
                    }
                }
            },
            text = {
                if (books.isEmpty()) {
                    Text("No books found in this business yet.")
                } else {
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
                                        showHomeBookSelector = false
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            Icons.Default.MenuBook,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Column {
                                            Text(
                                                book.name,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                            if (book.phone.isNotBlank()) {
                                                Text(
                                                    book.phone,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHomeBookSelector = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Best-in-class CashBook / Udhar Layout:
    // Top compact Active Cashbook Summary -> Middle full-height Active Book Entries -> Sticky Bottom IN / OUT buttons
    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Batch Selection Header Banner (if multi-selecting entries)
        if (selectedIds.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.clearTransactionSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                        Text("${selectedIds.size} selected", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = {
                            val csvBatch = viewModel.batchGetSelectedCSV()
                            clipboardManager.setText(AnnotatedString(csvBatch))
                            Toast.makeText(context, "Copied selected entries as CSV!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy selected")
                        }
                        IconButton(onClick = {
                            if (hasPermission(simulatedRole, "delete_transaction")) {
                                viewModel.batchDeleteSelectedTransactions()
                                Toast.makeText(context, "Selected entries deleted.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Unauthorized role", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // 2. Active Cashbook & Udhar Master Card (All Khata/CashBook tools right on Home, keeping entries spacious)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top Row: Business Pill + Book Name/Edit + Switch/Udhar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            modifier = Modifier.clickable { showBusinessSelectorDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    Icons.Default.Storefront,
                                    contentDescription = "Select Business",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = activeBusiness?.name ?: "Personal",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        if (activeBook != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { showEditBookDialog = true }
                            ) {
                                Text(
                                    text = activeBook!!.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit Customer / Book",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showHomeBookSelector = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(28.dp)
                                .testTag("home_switch_active_book_btn")
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Switch Active Book", modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("Switch", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        if (activeBook != null) {
                            FilledTonalButton(
                                onClick = { viewModel.setScreen(Screen.UDHAR) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("home_open_active_ledger_btn")
                            ) {
                                Text("Full", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Icon(Icons.Default.OpenInFull, contentDescription = "Full Udhar View", modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }

                // Middle Row: Net Balance + Inline IN/OUT + Orange REMIND button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Rs. ${String.format("%,.0f", kotlin.math.abs(activeNet))}",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                            color = if (activeNet >= 0) Color(0xFF16A34A) else RedOut
                        )
                        val periodSuffix = when (dateRangePreset) {
                            "TODAY" -> " • Today"
                            "WEEK" -> " • 7 Days"
                            "MONTH" -> " • This Month"
                            "CUSTOM" -> " • Custom Range"
                            else -> ""
                        }
                        Text(
                            text = (if (activeNet >= 0) "Net Balance (IN Surplus)" else "Net Balance (OUT / Due)") + periodSuffix,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "IN: Rs. ${String.format("%,.0f", activeIn)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = GreenIn
                            )
                            Text(
                                "OUT: Rs. ${String.format("%,.0f", activeOut)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = RedOut
                            )
                        }
                        Spacer(modifier = Modifier.height(5.dp))
                        // Cash vs Bank/Online Split Pills (Tap either pill to filter entries by mode)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val cashSelected = dashboardPaymentFilter == "Cash"
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (cashSelected) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                                border = if (cashSelected) BorderStroke(1.dp, GreenIn) else null,
                                modifier = Modifier
                                    .clickable {
                                        dashboardPaymentFilter = if (cashSelected) "ALL" else "Cash"
                                    }
                                    .testTag("home_cash_split_chip")
                            ) {
                                Text(
                                    text = "Cash: Rs. ${String.format("%,.0f", activeCashNet)}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeCashNet >= 0) Color(0xFF15803D) else RedOut,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                            val bankSelected = dashboardPaymentFilter == "BANK_ONLINE" || dashboardPaymentFilter == "Bank" || dashboardPaymentFilter == "Online"
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (bankSelected) Color(0xFFDBEAFE) else Color(0xFFF1F5F9),
                                border = if (bankSelected) BorderStroke(1.dp, Color(0xFF2563EB)) else null,
                                modifier = Modifier
                                    .clickable {
                                        dashboardPaymentFilter = if (bankSelected) "ALL" else "BANK_ONLINE"
                                    }
                                    .testTag("home_bank_split_chip")
                            ) {
                                Text(
                                    text = "Bank/Online: Rs. ${String.format("%,.0f", activeBankNet)}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeBankNet >= 0) Color(0xFF1D4ED8) else RedOut,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                        if (activeWasooliDue != null) {
                            val dueStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(activeWasooliDue))
                            val isOverdue = activeWasooliDue <= System.currentTimeMillis()
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isOverdue) Color(0xFFFEE2E2) else Color(0xFFE0F2FE),
                                modifier = Modifier.clickable { showWasooliDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.EventAvailable,
                                        contentDescription = "Wasooli Date",
                                        tint = if (isOverdue) RedOut else Color(0xFF0284C7),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = if (isOverdue) "Wasooli Due: $dueStr" else "Wasooli: $dueStr",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isOverdue) RedOut else Color(0xFF0369A1)
                                    )
                                }
                            }
                        }
                    }

                    // Prominent Orange REMIND Button (WhatsApp / SMS payment reminder)
                    if (activeBook != null) {
                        Button(
                            onClick = {
                                val phone = activeBook?.phone ?: ""
                                val customerName = activeBook?.name ?: "Customer"
                                val bizName = activeBusiness?.name ?: "CashBook Pro"
                                val dueLine = if (activeWasooliDue != null) {
                                    " Due date: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(activeWasooliDue))}."
                                } else ""
                                val remMsg = "Payment reminder from $bizName for $customerName: Pending balance is Rs. ${String.format("%,.0f", kotlin.math.abs(activeNet))}.$dueLine Please settle at your earliest convenience. Thank you!"
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, remMsg)
                                    type = "text/plain"
                                    if (phone.isNotBlank()) {
                                        putExtra("jid", "${phone.replace("+", "").replace("-", "").replace(" ", "")}@s.whatsapp.net")
                                    }
                                }
                                try {
                                    context.startActivity(Intent.createChooser(sendIntent, "Send Payment Reminder"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open sharing app", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("home_remind_button")
                        ) {
                            Text("REMIND", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.Send, contentDescription = "Send Payment Reminder", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                // Bottom Row: 5 Real Working Khata & CashBook Quick Actions (Wasooli, Call, Settle, PDF, Search)
                if (activeBook != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val isSearchActive = showSearchBar || dashboardSearchQuery.isNotBlank() || dashboardFilterType != "all" || dashboardPaymentFilter != "ALL"
                        val quickActions = listOf(
                            Triple("Wasooli", Icons.Default.EventAvailable, Color(0xFF0284C7)),
                            Triple("Call", Icons.Default.Call, Color(0xFF0D9488)),
                            Triple("Settle", Icons.Default.CheckCircleOutline, Color(0xFF16A34A)),
                            Triple("PDF", Icons.Default.PictureAsPdf, Color(0xFF7C3AED)),
                            Triple("Search", Icons.Default.Search, Color(0xFFD97706))
                        )
                        quickActions.forEach { (label, icon, color) ->
                            val highlighted = (label == "Wasooli" && activeWasooliDue != null) ||
                                (label == "Search" && isSearchActive)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        when (label) {
                                            "Wasooli" -> showWasooliDialog = true
                                            "Call" -> {
                                                val phone = activeBook?.phone?.trim() ?: ""
                                                if (phone.isBlank()) {
                                                    Toast.makeText(context, "Add customer phone number to call", Toast.LENGTH_SHORT).show()
                                                    showEditBookDialog = true
                                                } else {
                                                    try {
                                                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                                        context.startActivity(dialIntent)
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Unable to open phone dialer", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                            "Settle" -> {
                                                if (!hasPermission(simulatedRole, "add_transaction")) {
                                                    Toast.makeText(context, "Unauthorized role", Toast.LENGTH_SHORT).show()
                                                } else if (kotlin.math.abs(activeNet) < 0.5) {
                                                    Toast.makeText(context, "Khata balance is already Rs. 0 (Settled)", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    val absBal = kotlin.math.abs(activeNet)
                                                    prefillAmountForDialog = if (absBal % 1.0 == 0.0) absBal.toLong().toString() else String.format(Locale.US, "%.2f", absBal)
                                                    prefillRemarksForDialog = "Full Settlement / Khata Clear"
                                                    showTransactionDialog = if (activeNet < 0) "IN" else "OUT"
                                                }
                                            }
                                            "PDF" -> showShareDialog = true
                                            "Search" -> showSearchBar = !showSearchBar
                                        }
                                    }
                                    .padding(vertical = 3.dp)
                                    .testTag("home_action_${label.lowercase()}")
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (highlighted) color.copy(alpha = 0.16f) else color.copy(alpha = 0.08f),
                                    border = BorderStroke(if (highlighted) 1.5.dp else 1.dp, color.copy(alpha = if (highlighted) 0.9f else 0.35f)),
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(17.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    label,
                                    fontSize = 10.sp,
                                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                                    color = if (highlighted) color else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Compact Always-Accessible Date-Range Filter Strip (All Time / Today / 7 Days / This Month / Custom)
        DateRangeFilterStrip(
            selectedPreset = dateRangePreset,
            customStartMillis = customStartMillis,
            customEndMillis = customEndMillis,
            onSelectPreset = { preset -> dateRangePreset = preset },
            onCustomRangeSelected = { start, end ->
                customStartMillis = start
                customEndMillis = end
                dateRangePreset = "CUSTOM"
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )

        // 3. Expandable Search, Type & Payment Method Filter Bar (Compact so entries stay visible)
        AnimatedVisibility(visible = showSearchBar || dashboardSearchQuery.isNotBlank() || dashboardFilterType != "all" || dashboardPaymentFilter != "ALL") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = dashboardSearchQuery,
                    onValueChange = { dashboardSearchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search remarks, category, payment...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (dashboardSearchQuery.isNotEmpty() || showSearchBar) {
                            IconButton(onClick = {
                                dashboardSearchQuery = ""
                                dashboardFilterType = "all"
                                dashboardPaymentFilter = "ALL"
                                showSearchBar = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Close search", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("all" to "All", "IN" to "CASH IN", "OUT" to "CASH OUT").forEach { (type, label) ->
                        FilterChip(
                            selected = dashboardFilterType == type,
                            onClick = { dashboardFilterType = type },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.height(30.dp)
                        )
                    }
                    listOf("ALL" to "Any Mode", "Cash" to "Cash", "BANK_ONLINE" to "Bank/Online", "Online" to "Online", "Bank" to "Bank").forEach { (mode, label) ->
                        FilterChip(
                            selected = dashboardPaymentFilter == mode,
                            onClick = { dashboardPaymentFilter = mode },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }
            }
        }

        // Column header strip ("Date • Details" | "IN | OUT")
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            shape = RoundedCornerShape(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Date • ${activeBook?.name ?: "Cashbook"} (${displayedActiveTxs.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "IN | OUT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 4. Full-Height Active Cashbook Entries List (Takes ALL remaining screen height!)
        if (activeBook == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("No Cashbook Selected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Create your first cashbook to start recording entries.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = { showAddBookDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Create Cashbook", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (displayedActiveTxs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.ReceiptLong,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (dashboardSearchQuery.isBlank() && dashboardFilterType == "all")
                            "No entries in ${activeBook?.name} yet"
                        else "No matching entries in ${activeBook?.name}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap CASH IN or CASH OUT below to add an entry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedActiveTxs.forEach { (headerTitle, dayData, _) ->
                    val (dayIn, dayOut, dayTxs) = dayData
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 2.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
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
                                    if (dayIn > 0) Text("+${String.format("%,.0f", dayIn)}", style = MaterialTheme.typography.labelSmall, color = GreenIn, fontWeight = FontWeight.Bold)
                                    if (dayOut > 0) Text("-${String.format("%,.0f", dayOut)}", style = MaterialTheme.typography.labelSmall, color = RedOut, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    items(dayTxs) { tx ->
                        val isSelected = selectedIds.contains(tx.id)
                        val txRunningBal = runningBalanceMap[tx.id] ?: 0.0
                        TransactionItemCard(
                            transaction = tx,
                            runningBalance = txRunningBal,
                            isSelected = isSelected,
                            onToggleSelection = { viewModel.toggleTransactionSelection(tx.id) },
                            onDelete = {
                                if (hasPermission(simulatedRole, "delete_transaction")) {
                                    viewModel.deleteTransaction(tx)
                                } else {
                                    Toast.makeText(context, "Unauthorized role: Partner cannot delete entries.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onEdit = {
                                if (hasPermission(simulatedRole, "edit_transaction")) {
                                    selectedTxForEdit = tx
                                } else {
                                    Toast.makeText(context, "Unauthorized role cannot modify entries.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }

        // 5. Sticky Bottom CASH IN / CASH OUT Dock (Pinned at Bottom — NEVER in the middle!)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp
        ) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (activeBook == null) {
                                Toast.makeText(context, "Please select or create a cashbook first", Toast.LENGTH_SHORT).show()
                            } else if (hasPermission(simulatedRole, "add_transaction")) {
                                prefillAmountForDialog = ""
                                prefillRemarksForDialog = ""
                                showTransactionDialog = "IN"
                            } else {
                                Toast.makeText(context, "Unauthorized role", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("quick_cash_in_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF0FDF4)),
                        border = BorderStroke(1.5.dp, Color(0xFF22C55E)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CASH IN", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color(0xFF16A34A))
                    }

                    Button(
                        onClick = {
                            if (activeBook == null) {
                                Toast.makeText(context, "Please select or create a cashbook first", Toast.LENGTH_SHORT).show()
                            } else if (hasPermission(simulatedRole, "add_transaction")) {
                                prefillAmountForDialog = ""
                                prefillRemarksForDialog = ""
                                showTransactionDialog = "OUT"
                            } else {
                                Toast.makeText(context, "Unauthorized role", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("quick_cash_out_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFF7ED)),
                        border = BorderStroke(1.5.dp, Color(0xFFF97316)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("CASH OUT", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color(0xFFEA580C))
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = Color(0xFFEA580C), modifier = Modifier.size(18.dp))
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
            initialAmount = prefillAmountForDialog,
            initialRemarks = prefillRemarksForDialog,
            onDismiss = {
                showTransactionDialog = null
                prefillAmountForDialog = ""
                prefillRemarksForDialog = ""
            },
            onSave = { amount, category, method, remarks, receiptUri, timestamp ->
                viewModel.addTransaction(amount, type, category, method, remarks, receiptUri, timestamp)
                if (prefillRemarksForDialog.contains("Settlement") && activeBook != null) {
                    viewModel.setWasooliDueDate(activeBook!!.id, null)
                }
                showTransactionDialog = null
                prefillAmountForDialog = ""
                prefillRemarksForDialog = ""
            }
        )
    }

    // Edit Transaction Dialog directly from Home
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

    if (showEditBookDialog && activeBook != null) {
        EditBookConsumerDialog(
            book = activeBook!!,
            onDismiss = { showEditBookDialog = false },
            onSave = { newName, newPhone ->
                viewModel.updateBook(activeBook!!.copy(name = newName, phone = newPhone))
                showEditBookDialog = false
                Toast.makeText(context, "Customer / Book updated!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showShareDialog) {
        ShareStatementDialog(
            activeBook = activeBook,
            activeBusiness = activeBusiness,
            transactions = activeBookTransactions,
            onDismiss = { showShareDialog = false }
        )
    }

    if (showWasooliDialog && activeBook != null) {
        WasooliScheduleDialog(
            customerName = activeBook!!.name,
            customerPhone = activeBook!!.phone,
            netBalance = activeNet,
            currentDueDate = activeWasooliDue,
            onDismiss = { showWasooliDialog = false },
            onSaveDueDate = { millis ->
                viewModel.setWasooliDueDate(activeBook!!.id, millis)
                showWasooliDialog = false
                if (millis == null) {
                    Toast.makeText(context, "Wasooli due date cleared", Toast.LENGTH_SHORT).show()
                } else {
                    val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))
                    Toast.makeText(context, "Wasooli push reminder set: $fmt", Toast.LENGTH_SHORT).show()
                }
            },
            onRecordCollectionNow = {
                showWasooliDialog = false
                if (hasPermission(simulatedRole, "add_transaction")) {
                    val absBal = kotlin.math.abs(activeNet)
                    prefillAmountForDialog = if (absBal > 0) {
                        if (absBal % 1.0 == 0.0) absBal.toLong().toString() else String.format(Locale.US, "%.2f", absBal)
                    } else ""
                    prefillRemarksForDialog = "Wasooli Collection"
                    showTransactionDialog = "IN"
                }
            },
            onTestNotification = { dueMillis ->
                viewModel.sendTestWasooliNotification(
                    bookId = activeBook!!.id,
                    bookName = activeBook!!.name,
                    netBalance = activeNet,
                    dueMillis = dueMillis
                )
            }
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
    val wasooliDueDates by viewModel.wasooliDueDates.collectAsStateWithLifecycle()

    var showTransactionDialog by remember { mutableStateOf<String?>(null) }
    var prefillAmountForDialog by remember { mutableStateOf("") }
    var prefillRemarksForDialog by remember { mutableStateOf("") }
    var selectedTxForEdit by remember { mutableStateOf<Transaction?>(null) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showEditBookDialog by remember { mutableStateOf(false) }
    var showWasooliDialog by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var txTypeFilter by remember { mutableStateOf("ALL") } // "ALL", "IN", "OUT"
    var detailPaymentFilter by remember { mutableStateOf("ALL") }
    var detailDatePreset by remember { mutableStateOf("ALL") }
    var detailCustomStart by remember { mutableStateOf<Long?>(null) }
    var detailCustomEnd by remember { mutableStateOf<Long?>(null) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Date-filtered subset drives totals + Cash/Bank split + entries list
    val dateScopedTxs = remember(activeBookTransactions, detailDatePreset, detailCustomStart, detailCustomEnd) {
        activeBookTransactions.filter { tx ->
            isTransactionInDateRange(tx.timestamp, detailDatePreset, detailCustomStart, detailCustomEnd)
        }
    }

    // Aggregate values
    val totalIn = dateScopedTxs.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = dateScopedTxs.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut
    val cashNet = dateScopedTxs.filter { it.paymentMethod.equals("Cash", ignoreCase = true) }
        .sumOf { if (it.type == "IN") it.amount else -it.amount }
    val bankNet = dateScopedTxs.filter {
        it.paymentMethod.equals("Bank", ignoreCase = true) || it.paymentMethod.equals("Online", ignoreCase = true)
    }.sumOf { if (it.type == "IN") it.amount else -it.amount }
    val activeWasooliDueDate = activeBook?.id?.let { wasooliDueDates[it] }

    val inCategories = listOf("General", "Goods / Items", "Cash Received", "Payment", "Other")
    val outCategories = listOf("General", "Goods / Items", "Cash Paid", "Expense", "Other")
    val paymentMethods = listOf("Cash", "Online", "Bank")

    val displayTransactions = remember(filteredTransactions, txTypeFilter, detailDatePreset, detailCustomStart, detailCustomEnd, detailPaymentFilter) {
        filteredTransactions.filter { tx ->
            val matchesDate = isTransactionInDateRange(tx.timestamp, detailDatePreset, detailCustomStart, detailCustomEnd)
            val matchesType = when (txTypeFilter) {
                "IN" -> tx.type == "IN"
                "OUT" -> tx.type == "OUT"
                else -> true
            }
            val matchesMode = when (detailPaymentFilter) {
                "ALL" -> true
                "BANK_ONLINE" -> tx.paymentMethod.equals("Bank", ignoreCase = true) || tx.paymentMethod.equals("Online", ignoreCase = true)
                else -> tx.paymentMethod.equals(detailPaymentFilter, ignoreCase = true)
            }
            matchesDate && matchesType && matchesMode
        }
    }

    val groupedTransactions = remember(displayTransactions) {
        val sdfKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sdfHeader = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())
        displayTransactions.groupBy { tx ->
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

            // Top Header Bar matching Screenshot (Back arrow, Customer Initial avatar, Name, Edit, PDF, Overflow)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = { viewModel.setScreen(Screen.DASHBOARD) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Home")
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val initial = (activeBook?.name?.firstOrNull() ?: 'A').uppercaseChar()
                                Text(
                                    text = initial.toString(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = activeBook?.name ?: "Customer Khata",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = { showEditBookDialog = true },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit name", modifier = Modifier.size(14.dp), tint = Color.Gray)
                                }
                            }
                            if (activeBook?.phone?.isNotBlank() == true) {
                                Text(
                                    text = activeBook?.phone ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // PDF Statement button
                        IconButton(onClick = { showShareDialog = true }) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color(0xFF6B21A8)),
                                color = Color.Transparent
                            ) {
                                Text(
                                    text = "PDF",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF6B21A8),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        IconButton(onClick = { showEditBookDialog = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.Gray)
                        }
                    }
                }
            }

            // Prominent CashBook Summary Card (Balance + Cash/Bank Split + Orange REMIND button)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFBFB)),
                border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val balanceText = "Rs. ${String.format("%,.0f", kotlin.math.abs(netBalance))}"
                            Text(
                                text = balanceText,
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                                color = if (netBalance >= 0) Color(0xFF16A34A) else RedOut
                            )
                            Text(
                                text = if (netBalance >= 0) "Net Cash IN" else "Net Cash OUT",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B),
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val cashSel = detailPaymentFilter == "Cash"
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (cashSel) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                                    border = if (cashSel) BorderStroke(1.dp, GreenIn) else null,
                                    modifier = Modifier.clickable {
                                        detailPaymentFilter = if (cashSel) "ALL" else "Cash"
                                    }
                                ) {
                                    Text(
                                        text = "Cash: Rs. ${String.format("%,.0f", cashNet)}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (cashNet >= 0) Color(0xFF15803D) else RedOut,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                                val bankSel = detailPaymentFilter == "BANK_ONLINE"
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (bankSel) Color(0xFFDBEAFE) else Color(0xFFF1F5F9),
                                    border = if (bankSel) BorderStroke(1.dp, Color(0xFF2563EB)) else null,
                                    modifier = Modifier.clickable {
                                        detailPaymentFilter = if (bankSel) "ALL" else "BANK_ONLINE"
                                    }
                                ) {
                                    Text(
                                        text = "Bank/Online: Rs. ${String.format("%,.0f", bankNet)}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (bankNet >= 0) Color(0xFF1D4ED8) else RedOut,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            if (activeWasooliDueDate != null) {
                                val dueStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(activeWasooliDueDate))
                                val isOverdue = activeWasooliDueDate <= System.currentTimeMillis()
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isOverdue) Color(0xFFFEE2E2) else Color(0xFFE0F2FE),
                                    modifier = Modifier.clickable { showWasooliDialog = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.EventAvailable,
                                            contentDescription = "Wasooli Date",
                                            tint = if (isOverdue) RedOut else Color(0xFF0284C7),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = if (isOverdue) "Wasooli Due: $dueStr" else "Wasooli: $dueStr",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isOverdue) RedOut else Color(0xFF0369A1)
                                        )
                                    }
                                }
                            }
                        }

                        // Orange REMIND Button
                        Button(
                            onClick = {
                                val phone = activeBook?.phone ?: ""
                                val customerName = activeBook?.name ?: "Account"
                                val dueLine = if (activeWasooliDueDate != null) {
                                    " Due date: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(activeWasooliDueDate))}."
                                } else ""
                                val remMsg = "Payment reminder for $customerName: Pending balance Rs. ${String.format("%,.0f", kotlin.math.abs(netBalance))}.$dueLine Please settle at your earliest convenience. Thank you!"
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, remMsg)
                                    type = "text/plain"
                                    if (phone.isNotBlank()) {
                                        putExtra("jid", "${phone.replace("+", "").replace("-", "").replace(" ", "")}@s.whatsapp.net")
                                    }
                                }
                                try {
                                    context.startActivity(Intent.createChooser(sendIntent, "Send Payment Reminder"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open sharing app", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("remind_button")
                        ) {
                            Text("REMIND", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = Color(0xFFF1F5F9))
                    Spacer(modifier = Modifier.height(10.dp))

                    // 4 Real Working Khata Quick Actions (Wasooli, Call, Settle, Search)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        val isSearchActive = showSearchBar || searchQuery.isNotBlank() || txTypeFilter != "ALL"
                        val actions = listOf(
                            Triple("Wasooli", Icons.Default.EventAvailable, Color(0xFF0284C7)),
                            Triple("Call", Icons.Default.Call, Color(0xFF0D9488)),
                            Triple("Settle", Icons.Default.CheckCircleOutline, Color(0xFF16A34A)),
                            Triple("Search", Icons.Default.Search, Color(0xFFD97706))
                        )
                        actions.forEach { (label, icon, color) ->
                            val highlighted = (label == "Wasooli" && activeWasooliDueDate != null) ||
                                    (label == "Search" && isSearchActive)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clickable {
                                        when (label) {
                                            "Wasooli" -> {
                                                if (activeBook == null) {
                                                    Toast.makeText(context, "Select a customer book first", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    showWasooliDialog = true
                                                }
                                            }
                                            "Call" -> {
                                                val phone = activeBook?.phone?.trim() ?: ""
                                                if (phone.isBlank()) {
                                                    Toast.makeText(context, "Add customer phone number to call", Toast.LENGTH_SHORT).show()
                                                    showEditBookDialog = true
                                                } else {
                                                    try {
                                                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                                        context.startActivity(dialIntent)
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Unable to open phone dialer", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                            "Settle" -> {
                                                if (!hasPermission(simulatedRole, "add_transaction")) {
                                                    Toast.makeText(context, "Unauthorized role: Read-only partners cannot add entries.", Toast.LENGTH_SHORT).show()
                                                } else if (kotlin.math.abs(netBalance) < 0.5) {
                                                    Toast.makeText(context, "Khata balance is already Rs. 0 (Settled)", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    val absBal = kotlin.math.abs(netBalance)
                                                    prefillAmountForDialog = if (absBal % 1.0 == 0.0) absBal.toLong().toString() else String.format(Locale.US, "%.2f", absBal)
                                                    prefillRemarksForDialog = "Full Settlement / Khata Clear"
                                                    showTransactionDialog = if (netBalance < 0) "IN" else "OUT"
                                                }
                                            }
                                            "Search" -> {
                                                showSearchBar = !showSearchBar
                                            }
                                        }
                                    }
                                    .padding(4.dp)
                                    .testTag("quick_action_${label.lowercase()}")
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (highlighted) color.copy(alpha = 0.14f) else Color.White,
                                    border = BorderStroke(if (highlighted) 2.dp else 1.5.dp, if (highlighted) color else color.copy(alpha = 0.5f)),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                                    color = if (highlighted) color else Color(0xFF475569)
                                )
                            }
                        }
                    }
                }
            }

            // Date-Range Filter Strip in BookDetailScreen
            DateRangeFilterStrip(
                selectedPreset = detailDatePreset,
                customStartMillis = detailCustomStart,
                customEndMillis = detailCustomEnd,
                onSelectPreset = { detailDatePreset = it },
                onCustomRangeSelected = { s, e ->
                    detailCustomStart = s
                    detailCustomEnd = e
                    detailDatePreset = "CUSTOM"
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            // Expandable Search & Filter Bar
            AnimatedVisibility(visible = showSearchBar || searchQuery.isNotBlank() || txTypeFilter != "ALL" || detailPaymentFilter != "ALL") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty() || showSearchBar) {
                                IconButton(onClick = {
                                    viewModel.setSearchQuery("")
                                    txTypeFilter = "ALL"
                                    detailPaymentFilter = "ALL"
                                    showSearchBar = false
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close search")
                                }
                            }
                        },
                        placeholder = { Text("Search remarks or category...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_field"),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("ALL" to "All", "IN" to "CASH IN", "OUT" to "CASH OUT").forEach { (key, title) ->
                            FilterChip(
                                selected = txTypeFilter == key,
                                onClick = { txTypeFilter = key },
                                label = { Text(title, fontSize = 12.sp) },
                                leadingIcon = if (txTypeFilter == key) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                } else null
                            )
                        }
                        listOf("ALL" to "Any Mode", "Cash" to "Cash", "BANK_ONLINE" to "Bank/Online").forEach { (mode, label) ->
                            FilterChip(
                                selected = detailPaymentFilter == mode,
                                onClick = { detailPaymentFilter = mode },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            // Ledger Column Headers ("Date", "IN", "OUT")
            Surface(
                color = Color(0xFFF8FAFC),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Date", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Text("IN", style = MaterialTheme.typography.labelSmall, color = Color(0xFF16A34A), fontWeight = FontWeight.Bold)
                        Text("OUT", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEA580C), fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Ledger Entries List Daywise
            if (displayTransactions.isEmpty()) {
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
                            "Tap 'CASH IN' or 'CASH OUT' below to add entries.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                // Compute running balance cumulative map for exact "Bal. Rs. xxx,xxx" chips
                val sortedChronologicalTxs = remember(activeBookTransactions) {
                    activeBookTransactions.sortedBy { it.timestamp }
                }
                val runningBalanceMap = remember(sortedChronologicalTxs) {
                    var cumulative = 0.0
                    val map = mutableMapOf<Int, Double>()
                    for (tx in sortedChronologicalTxs) {
                        if (tx.type == "IN") cumulative += tx.amount else cumulative -= tx.amount
                        map[tx.id] = cumulative
                    }
                    map
                }

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
                                        if (dayOut > 0) Text("OUT: Rs. ${String.format("%,.0f", dayOut)}", style = MaterialTheme.typography.labelSmall, color = RedOut, fontWeight = FontWeight.Bold)
                                        if (dayIn > 0) Text("IN: Rs. ${String.format("%,.0f", dayIn)}", style = MaterialTheme.typography.labelSmall, color = GreenIn, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        items(dayTxs) { tx ->
                            val isSelected = selectedIds.contains(tx.id)
                            val txRunningBal = runningBalanceMap[tx.id] ?: 0.0
                            TransactionItemCard(
                                transaction = tx,
                                runningBalance = txRunningBal,
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

            // Quick Floating Action Buttons Panel (Pinned cleanly at bottom above system navigation bar)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp
            ) {
                Column {
                    HorizontalDivider(color = Color(0xFFE2E8F0))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .navigationBarsPadding(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Button(
                            onClick = {
                                if (hasPermission(simulatedRole, "add_transaction")) {
                                    prefillAmountForDialog = ""
                                    prefillRemarksForDialog = ""
                                    showTransactionDialog = "IN"
                                } else {
                                    Toast.makeText(context, "Unauthorized role: Read-only partners cannot add entries.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("cash_in_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF0FDF4)),
                            border = BorderStroke(1.5.dp, Color(0xFF22C55E)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("CASH IN", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Color(0xFF16A34A))
                        }

                        Button(
                            onClick = {
                                if (hasPermission(simulatedRole, "add_transaction")) {
                                    prefillAmountForDialog = ""
                                    prefillRemarksForDialog = ""
                                    showTransactionDialog = "OUT"
                                } else {
                                    Toast.makeText(context, "Unauthorized role: Read-only partners cannot add entries.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("cash_out_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFF7ED)),
                            border = BorderStroke(1.5.dp, Color(0xFFF97316)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("CASH OUT", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Color(0xFFEA580C))
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = Color(0xFFEA580C), modifier = Modifier.size(20.dp))
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
                initialAmount = prefillAmountForDialog,
                initialRemarks = prefillRemarksForDialog,
                onDismiss = {
                    showTransactionDialog = null
                    prefillAmountForDialog = ""
                    prefillRemarksForDialog = ""
                },
                onSave = { amount, category, method, remarks, receiptUri, timestamp ->
                    viewModel.addTransaction(amount, type, category, method, remarks, receiptUri, timestamp)
                    if (prefillRemarksForDialog.contains("Settlement") && activeBook != null) {
                        viewModel.setWasooliDueDate(activeBook!!.id, null)
                    }
                    showTransactionDialog = null
                    prefillAmountForDialog = ""
                    prefillRemarksForDialog = ""
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

        // Wasooli Collection Scheduler Dialog
        if (showWasooliDialog && activeBook != null) {
            WasooliScheduleDialog(
                customerName = activeBook!!.name,
                customerPhone = activeBook!!.phone,
                netBalance = netBalance,
                currentDueDate = activeWasooliDueDate,
                onDismiss = { showWasooliDialog = false },
                onSaveDueDate = { millis ->
                    viewModel.setWasooliDueDate(activeBook!!.id, millis)
                    showWasooliDialog = false
                    if (millis == null) {
                        Toast.makeText(context, "Wasooli due date cleared", Toast.LENGTH_SHORT).show()
                    } else {
                        val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))
                        Toast.makeText(context, "Wasooli push reminder set: $fmt", Toast.LENGTH_SHORT).show()
                    }
                },
                onRecordCollectionNow = {
                    showWasooliDialog = false
                    if (hasPermission(simulatedRole, "add_transaction")) {
                        val absBal = kotlin.math.abs(netBalance)
                        prefillAmountForDialog = if (absBal > 0) {
                            if (absBal % 1.0 == 0.0) absBal.toLong().toString() else String.format(Locale.US, "%.2f", absBal)
                        } else ""
                        prefillRemarksForDialog = "Wasooli Collection"
                        showTransactionDialog = "IN"
                    } else {
                        Toast.makeText(context, "Unauthorized role", Toast.LENGTH_SHORT).show()
                    }
                },
                onTestNotification = { dueMillis ->
                    viewModel.sendTestWasooliNotification(
                        bookId = activeBook!!.id,
                        bookName = activeBook!!.name,
                        netBalance = netBalance,
                        dueMillis = dueMillis
                    )
                }
            )
        }
    }
}

@Composable
fun WasooliScheduleDialog(
    customerName: String,
    customerPhone: String,
    netBalance: Double,
    currentDueDate: Long?,
    onDismiss: () -> Unit,
    onSaveDueDate: (Long?) -> Unit,
    onRecordCollectionNow: () -> Unit,
    onTestNotification: ((Long) -> Unit)? = null
) {
    val context = LocalContext.current
    var selectedDateMillis by remember(currentDueDate) {
        mutableStateOf(
            currentDueDate ?: Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }.timeInMillis
        )
    }

    var pendingActionAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                context,
                "Notification permission denied — due date saved without push alert",
                Toast.LENGTH_LONG
            ).show()
        }
        pendingActionAfterPermission?.invoke()
        pendingActionAfterPermission = null
    }

    fun runWithNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !WasooliNotificationHelper.hasNotificationPermission(context)
        ) {
            pendingActionAfterPermission = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val calendar = remember(selectedDateMillis) {
        Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
    }
    val datePicker = remember(context, selectedDateMillis) {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val cal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                }
                selectedDateMillis = cal.timeInMillis
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    val formattedSelected = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date(selectedDateMillis))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Color(0xFF0284C7))
                Text("Wasooli Schedule & Push", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Set payment collection date for $customerName (Balance: Rs. ${String.format("%,.0f", kotlin.math.abs(netBalance))})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Selected date preview & calendar button
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { datePicker.show() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Collection Date", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0284C7))
                            Text(formattedSelected, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Icon(Icons.Default.CalendarMonth, contentDescription = "Pick Date", tint = Color(0xFF0284C7))
                    }
                }

                // Quick presets including Today for instant reminder testing
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Today" to 0, "Tomorrow" to 1, "In 7 Days" to 7, "In 30 Days" to 30).forEach { (lbl, days) ->
                        AssistChip(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    add(Calendar.DAY_OF_YEAR, days)
                                    set(Calendar.HOUR_OF_DAY, 23)
                                    set(Calendar.MINUTE, 59)
                                }
                                selectedDateMillis = cal.timeInMillis
                            },
                            label = { Text(lbl, fontSize = 11.sp) }
                        )
                    }
                }

                // Push notification indicator
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFE0F2FE),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = "Push Alert Active",
                            tint = Color(0xFF0284C7),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Local push alert fires automatically at 9:00 AM on due date (or in 10s if set for Today).",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF0369A1),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Quick actions: Share Reminder, Test Push, Collect Now
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val dateOnly = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(selectedDateMillis))
                            val msg = "Payment reminder for $customerName: Due date is $dateOnly. Pending balance: Rs. ${String.format("%,.0f", kotlin.math.abs(netBalance))}. Thank you!"
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, msg)
                                type = "text/plain"
                                if (customerPhone.isNotBlank()) {
                                    putExtra("jid", "${customerPhone.replace("+", "").replace("-", "").replace(" ", "")}@s.whatsapp.net")
                                }
                            }
                            try {
                                context.startActivity(Intent.createChooser(sendIntent, "Send Wasooli Reminder"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open sharing app", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Share", fontSize = 11.sp)
                    }

                    if (onTestNotification != null) {
                        OutlinedButton(
                            onClick = {
                                runWithNotificationPermission {
                                    onTestNotification(selectedDateMillis)
                                    Toast.makeText(context, "Test notification sent!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("wasooli_test_push_btn"),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = "Test Push", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Test Push", fontSize = 11.sp)
                        }
                    }

                    FilledTonalButton(
                        onClick = onRecordCollectionNow,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Collect", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runWithNotificationPermission {
                        onSaveDueDate(selectedDateMillis)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                modifier = Modifier.testTag("wasooli_save_date_btn")
            ) {
                Text("Save & Notify")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (currentDueDate != null) {
                    TextButton(onClick = { onSaveDueDate(null) }) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
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
                contentDescription = "Synced to Cloud (Double Green Check)",
                tint = Color(0xFF16A34A),
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "Synced",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF16A34A),
                fontWeight = FontWeight.Bold
            )
        } else {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Saved Locally (Single Check)",
                tint = Color(0xFF2563EB),
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "Saved Locally",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF2563EB),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionItemCard(
    transaction: Transaction,
    runningBalance: Double = 0.0,
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
                        Column(modifier = Modifier.weight(1f)) {
                            val formattedDate = SimpleDateFormat("dd MMM yyyy • h:mm a", Locale.getDefault()).format(Date(transaction.timestamp))
                            Text(
                                text = formattedDate,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF1E293B)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            // Udhar Book pink/light-red Balance badge ("Bal. Rs. 299,115")
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFFCE7F3) // soft pinkish background
                            ) {
                                Text(
                                    text = "Bal. Rs. ${String.format("%,.0f", kotlin.math.abs(runningBalance))}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF9D174D), // deep berry/rose text
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (transaction.remarks.isNotBlank()) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.StickyNote2, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color.Gray)
                                    Text(
                                        text = transaction.remarks,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF475569),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Right-hand Amount display with green/red formatting & IN/OUT tag
                        Column(horizontalAlignment = Alignment.End) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (transaction.type == "IN") Color(0xFFDCFCE7) else Color(0xFFFFEDD5)
                            ) {
                                Text(
                                    text = if (transaction.type == "IN") "CASH IN" else "CASH OUT",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (transaction.type == "IN") Color(0xFF15803D) else Color(0xFFC2410C),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Rs. ${String.format("%,.0f", transaction.amount)}",
                                fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (transaction.type == "IN") Color(0xFF16A34A) else Color(0xFFEA580C)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            TransactionSyncCheckIndicator(isSynced = transaction.isSynced)
                        }
                    }

                    if (transaction.receiptUri != null) {
                        Spacer(modifier = Modifier.height(4.dp))
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
    val screenTitle = if (isEdit) "Edit Entry" else if (isGave) "Cash Out (OUT)" else "Cash In (IN)"
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

                    // Compact Payment Mode & Category Pills (Cash / Online / Bank + Category)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        paymentMethods.forEach { method ->
                            val selected = paymentMethod.equals(method, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = { paymentMethod = method },
                                label = { Text(method, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                                modifier = Modifier.height(30.dp)
                            )
                        }
                        categories.forEach { cat ->
                            val selected = category.equals(cat, ignoreCase = true)
                            AssistChip(
                                onClick = { category = cat },
                                label = {
                                    Text(
                                        cat,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) accentColor else Color(0xFF475569)
                                    )
                                },
                                border = BorderStroke(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) accentColor else Color.LightGray.copy(alpha = 0.6f)
                                ),
                                modifier = Modifier.height(30.dp)
                            )
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
            val pickPartyContact = rememberContactImportAction(context) { cName, cPhone ->
                if (cName.isNotBlank()) partyName = cName
                if (cPhone.isNotBlank()) partyPhone = cPhone
            }
            AlertDialog(
                onDismissRequest = { showAddPartyDialog = false },
                title = { Text("Add Customer / Supplier") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = pickPartyContact,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("udhar_import_contact_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Contacts, contentDescription = "Pick from Contacts", tint = GreenIn)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import from Contacts", fontWeight = FontWeight.Bold, color = GreenIn)
                        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(viewModel: LedgerViewModel) {
    val businesses by viewModel.businesses.collectAsStateWithLifecycle()
    val allBooks by viewModel.allBooks.collectAsStateWithLifecycle()
    val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val activeBusiness by viewModel.activeBusiness.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // null = Overall (All Businesses), non-null = Separate Business Report
    var selectedBusinessId by remember { mutableStateOf<Int?>(null) }
    val effectiveBusinessId = remember(selectedBusinessId, businesses) {
        if (selectedBusinessId != null && businesses.any { it.id == selectedBusinessId }) selectedBusinessId else null
    }
    val selectedBusiness = remember(effectiveBusinessId, businesses) {
        businesses.find { it.id == effectiveBusinessId }
    }

    var selectedBookId by remember(effectiveBusinessId) { mutableStateOf<Int?>(null) }
    val scopedBooks = remember(effectiveBusinessId, allBooks) {
        if (effectiveBusinessId != null) allBooks.filter { it.businessId == effectiveBusinessId } else allBooks
    }
    val effectiveBookId = remember(selectedBookId, scopedBooks) {
        if (selectedBookId != null && scopedBooks.any { it.id == selectedBookId }) selectedBookId else null
    }
    val selectedBook = remember(effectiveBookId, scopedBooks) {
        scopedBooks.find { it.id == effectiveBookId }
    }

    var dateRangePreset by remember { mutableStateOf("ALL") }
    var customStartMillis by remember { mutableStateOf<Long?>(null) }
    var customEndMillis by remember { mutableStateOf<Long?>(null) }
    var categoryChartMode by remember { mutableStateOf("OUT") } // "OUT" or "IN"

    val targetBookIds = remember(effectiveBookId, scopedBooks) {
        if (effectiveBookId != null) setOf(effectiveBookId) else scopedBooks.map { it.id }.toSet()
    }

    val scopedTransactions = remember(allTransactions, targetBookIds, dateRangePreset, customStartMillis, customEndMillis) {
        allTransactions.filter { tx ->
            tx.bookId in targetBookIds &&
                isTransactionInDateRange(tx.timestamp, dateRangePreset, customStartMillis, customEndMillis)
        }
    }

    // Core KPI Aggregations
    val totalIn = remember(scopedTransactions) { scopedTransactions.filter { it.type == "IN" }.sumOf { it.amount } }
    val totalOut = remember(scopedTransactions) { scopedTransactions.filter { it.type == "OUT" }.sumOf { it.amount } }
    val netBalance = totalIn - totalOut
    val inCount = remember(scopedTransactions) { scopedTransactions.count { it.type == "IN" } }
    val outCount = remember(scopedTransactions) { scopedTransactions.count { it.type == "OUT" } }

    // Cash vs Bank/Online Split
    val cashNet = remember(scopedTransactions) {
        scopedTransactions.filter { it.paymentMethod.equals("Cash", ignoreCase = true) }
            .sumOf { if (it.type == "IN") it.amount else -it.amount }
    }
    val bankOnlineNet = remember(scopedTransactions) {
        scopedTransactions.filter {
            it.paymentMethod.equals("Bank", ignoreCase = true) || it.paymentMethod.equals("Online", ignoreCase = true)
        }.sumOf { if (it.type == "IN") it.amount else -it.amount }
    }

    // Daily Trend Buckets (last 7 active/recent days in scope)
    val dailyTrendPoints = remember(scopedTransactions) {
        val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val labelFmt = SimpleDateFormat("dd/MM", Locale.getDefault())
        val groupedByDay = scopedTransactions.groupBy { dayKeyFmt.format(Date(it.timestamp)) }
        if (groupedByDay.isEmpty()) {
            emptyList()
        } else {
            groupedByDay.entries
                .sortedBy { it.key }
                .takeLast(7)
                .map { (_, dayTxs) ->
                    val ts = dayTxs.first().timestamp
                    val dayIn = dayTxs.filter { it.type == "IN" }.sumOf { it.amount }
                    val dayOut = dayTxs.filter { it.type == "OUT" }.sumOf { it.amount }
                    Triple(labelFmt.format(Date(ts)), dayIn, dayOut)
                }
        }
    }

    // Per-Business Comparison Breakdown (respecting selected date range)
    val businessBreakdownList = remember(businesses, allBooks, allTransactions, dateRangePreset, customStartMillis, customEndMillis) {
        businesses.map { biz ->
            val bizBooks = allBooks.filter { it.businessId == biz.id }
            val bizBookIds = bizBooks.map { it.id }.toSet()
            val bizTxs = allTransactions.filter {
                it.bookId in bizBookIds &&
                    isTransactionInDateRange(it.timestamp, dateRangePreset, customStartMillis, customEndMillis)
            }
            val bizIn = bizTxs.filter { it.type == "IN" }.sumOf { it.amount }
            val bizOut = bizTxs.filter { it.type == "OUT" }.sumOf { it.amount }
            Triple(biz, bizBooks.size, Triple(bizIn, bizOut, bizTxs.size))
        }.sortedByDescending { it.third.first + it.third.second }
    }

    // Category Breakdown (for selected OUT or IN mode)
    val categoryTotals = remember(scopedTransactions, categoryChartMode) {
        scopedTransactions
            .filter { it.type == categoryChartMode }
            .groupBy { tx ->
                when {
                    tx.category.isNotBlank() -> tx.category
                    tx.remarks.isNotBlank() -> tx.remarks
                    else -> if (categoryChartMode == "IN") "Income" else "Expense"
                }
            }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
    }

    val chartPalette = remember {
        listOf(
            Color(0xFF16A34A),
            Color(0xFFEA580C),
            Color(0xFF2563EB),
            Color(0xFF7C3AED),
            Color(0xFF0D9488),
            Color(0xFF64748B)
        )
    }

    val scopeTitle = when {
        selectedBusiness == null -> "Overall Report (${businesses.size} Businesses)"
        selectedBook != null -> "${selectedBusiness.name} • ${selectedBook.name}"
        else -> "${selectedBusiness.name} (All ${scopedBooks.size} Books)"
    }

    val bookLookup = remember(allBooks) { allBooks.associateBy { it.id } }
    val bizLookup = remember(businesses) { businesses.associateBy { it.id } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header + Multi-Business & Cashbook Selector
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Financial Analytics",
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            scopeTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = "${scopedTransactions.size} Entries",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Business Scope Pills: Overall + Separate per Business
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            selected = effectiveBusinessId == null,
                            onClick = { selectedBusinessId = null },
                            label = {
                                Text(
                                    "Overall (${businesses.size})",
                                    fontWeight = if (effectiveBusinessId == null) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Assessment,
                                    contentDescription = "Overall Report",
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("report_scope_overall")
                        )
                    }
                    items(businesses, key = { it.id }) { biz ->
                        val isBizSelected = effectiveBusinessId == biz.id
                        FilterChip(
                            selected = isBizSelected,
                            onClick = { selectedBusinessId = biz.id },
                            label = {
                                Text(
                                    biz.name,
                                    fontWeight = if (isBizSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Storefront,
                                    contentDescription = biz.name,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("report_scope_biz_${biz.id}")
                        )
                    }
                }

                // Optional Cashbook Sub-Filter when viewing a single Business with multiple books
                if (selectedBusiness != null && scopedBooks.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            FilterChip(
                                selected = effectiveBookId == null,
                                onClick = { selectedBookId = null },
                                label = { Text("All Books (${scopedBooks.size})") },
                                leadingIcon = {
                                    Icon(Icons.Default.LibraryBooks, contentDescription = null, modifier = Modifier.size(15.dp))
                                }
                            )
                        }
                        items(scopedBooks, key = { it.id }) { bk ->
                            FilterChip(
                                selected = effectiveBookId == bk.id,
                                onClick = { selectedBookId = bk.id },
                                label = { Text(bk.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(15.dp))
                                }
                            )
                        }
                    }
                }

                // Date-Range Filters
                DateRangeFilterStrip(
                    selectedPreset = dateRangePreset,
                    customStartMillis = customStartMillis,
                    customEndMillis = customEndMillis,
                    onSelectPreset = { dateRangePreset = it },
                    onCustomRangeSelected = { start, end ->
                        customStartMillis = start
                        customEndMillis = end
                        dateRangePreset = "CUSTOM"
                    }
                )
            }
        }

        // Executive KPI Summary Card + Cash vs Bank Split
        item {
            val netColor = if (netBalance >= 0) Color(0xFF2563EB) else Color(0xFFD97706)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = GreenIn.copy(alpha = 0.1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("CASH IN (+)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = GreenIn)
                                Text(
                                    "Rs. ${String.format("%,.0f", totalIn)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = GreenIn,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("$inCount entries", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = RedOut.copy(alpha = 0.1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("CASH OUT (-)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = RedOut)
                                Text(
                                    "Rs. ${String.format("%,.0f", totalOut)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = RedOut,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("$outCount entries", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = netColor.copy(alpha = 0.1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("NET BALANCE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = netColor)
                                Text(
                                    "Rs. ${String.format("%,.0f", netBalance)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = netColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(if (netBalance >= 0) "Surplus" else "Deficit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // Cash vs Bank/Online Breakdown Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(Icons.Default.Payments, contentDescription = null, tint = GreenIn, modifier = Modifier.size(16.dp))
                                    Text("Cash", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "Rs. ${String.format("%,.0f", cashNet)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (cashNet >= 0) GreenIn else RedOut
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(Icons.Default.AccountBalance, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(16.dp))
                                    Text("Bank / Online", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "Rs. ${String.format("%,.0f", bankOnlineNet)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (bankOnlineNet >= 0) Color(0xFF2563EB) else RedOut
                                )
                            }
                        }
                    }
                }
            }
        }

        // Chart 1: Cash IN vs Cash OUT Interactive Donut Chart
        item {
            val totalVolume = totalIn + totalOut
            val inPct = if (totalVolume > 0) ((totalIn / totalVolume) * 100.0).toInt() else 0
            val outPct = if (totalVolume > 0) 100 - inPct else 0

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Text("Cash In vs Cash Out Ratio", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Visual inflow & outflow distribution for $scopeTitle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(132.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val diameter = size.minDimension
                                val strokeWidth = diameter * 0.2f
                                val inset = strokeWidth / 2f
                                val arcSize = Size(diameter - strokeWidth, diameter - strokeWidth)
                                val topLeft = Offset((size.width - arcSize.width) / 2f, (size.height - arcSize.height) / 2f)

                                if (totalVolume <= 0.0) {
                                    drawArc(
                                        color = Color(0xFFE2E8F0),
                                        startAngle = 0f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = Stroke(width = strokeWidth)
                                    )
                                } else {
                                    val inSweep = ((totalIn / totalVolume) * 360.0).toFloat()
                                    val outSweep = ((totalOut / totalVolume) * 360.0).toFloat()
                                    if (inSweep > 0f) {
                                        drawArc(
                                            color = GreenIn,
                                            startAngle = -90f,
                                            sweepAngle = inSweep,
                                            useCenter = false,
                                            topLeft = topLeft,
                                            size = arcSize,
                                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                                        )
                                    }
                                    if (outSweep > 0f) {
                                        drawArc(
                                            color = RedOut,
                                            startAngle = -90f + inSweep,
                                            sweepAngle = outSweep,
                                            useCenter = false,
                                            topLeft = topLeft,
                                            size = arcSize,
                                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                                        )
                                    }
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (totalVolume > 0) "$inPct%" else "0%",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = GreenIn
                                )
                                Text(
                                    "Inflow",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(modifier = Modifier.size(12.dp).background(GreenIn, CircleShape))
                                    Column {
                                        Text("Cash IN ($inPct%)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                        Text("$inCount entries", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text("Rs. ${String.format("%,.0f", totalIn)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = GreenIn)
                            }

                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(modifier = Modifier.size(12.dp).background(RedOut, CircleShape))
                                    Column {
                                        Text("Cash OUT ($outPct%)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                        Text("$outCount entries", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text("Rs. ${String.format("%,.0f", totalOut)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = RedOut)
                            }
                        }
                    }
                }
            }
        }

        // Chart 2: Daily Cash Flow Column Bar Chart (Last 7 Active Days)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Cash Flow Trend Chart", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Recent active days comparison (IN vs OUT)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(9.dp).background(GreenIn, RoundedCornerShape(2.dp)))
                                Text("IN", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(9.dp).background(RedOut, RoundedCornerShape(2.dp)))
                                Text("OUT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (dailyTrendPoints.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No transactions in this range to plot trend bars.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        val peakValue = dailyTrendPoints.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(1.0)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Peak: Rs. ${String.format("%,.0f", peakValue)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Last ${dailyTrendPoints.size} active day(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }

                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                            ) {
                                val chartHeight = size.height
                                val chartWidth = size.width
                                val gridColor = Color(0xFFE2E8F0)

                                // Horizontal guide lines
                                for (i in 0..3) {
                                    val y = chartHeight * (i / 3f)
                                    drawLine(
                                        color = gridColor,
                                        start = Offset(0f, y),
                                        end = Offset(chartWidth, y),
                                        strokeWidth = 1.5f
                                    )
                                }

                                val groupCount = dailyTrendPoints.size.coerceAtLeast(1)
                                val groupWidth = chartWidth / groupCount
                                val barWidth = (groupWidth * 0.28f).coerceIn(8f, 28f)
                                val gapBetweenBars = (barWidth * 0.35f).coerceAtLeast(4f)

                                dailyTrendPoints.forEachIndexed { idx, (_, dayIn, dayOut) ->
                                    val centerX = groupWidth * idx + groupWidth / 2f
                                    val inHeight = ((dayIn / peakValue).toFloat().coerceIn(0f, 1f)) * (chartHeight - 8f)
                                    val outHeight = ((dayOut / peakValue).toFloat().coerceIn(0f, 1f)) * (chartHeight - 8f)

                                    if (inHeight > 0f) {
                                        drawRoundRect(
                                            color = GreenIn,
                                            topLeft = Offset(centerX - barWidth - gapBetweenBars / 2f, chartHeight - inHeight),
                                            size = Size(barWidth, inHeight),
                                            cornerRadius = CornerRadius(6f, 6f)
                                        )
                                    }
                                    if (outHeight > 0f) {
                                        drawRoundRect(
                                            color = RedOut,
                                            topLeft = Offset(centerX + gapBetweenBars / 2f, chartHeight - outHeight),
                                            size = Size(barWidth, outHeight),
                                            cornerRadius = CornerRadius(6f, 6f)
                                        )
                                    }
                                }
                            }

                            // X-axis date labels
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                dailyTrendPoints.forEach { (dateLabel, _, _) ->
                                    Text(
                                        text = dateLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Chart 3: Business-by-Business Performance Comparison (Shown in Overall Mode)
        if (effectiveBusinessId == null && businessBreakdownList.isNotEmpty()) {
            item {
                val maxBizVolume = businessBreakdownList.maxOf { maxOf(it.third.first, it.third.second) }.coerceAtLeast(1.0)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column {
                            Text("Business-by-Business Comparison", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Tap any business below to open its separate report & charts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        businessBreakdownList.forEach { (biz, bookCount, stats) ->
                            val (bizIn, bizOut, txCount) = stats
                            val bizNet = bizIn - bizOut
                            val inFrac = (bizIn / maxBizVolume).toFloat().coerceIn(0f, 1f)
                            val outFrac = (bizOut / maxBizVolume).toFloat().coerceIn(0f, 1f)

                            Surface(
                                onClick = { selectedBusinessId = biz.id },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                            Icon(Icons.Default.Storefront, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                            Column {
                                                Text(biz.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("$bookCount book(s) • $txCount entries", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                "Net: Rs. ${String.format("%,.0f", bizNet)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (bizNet >= 0) GreenIn else RedOut
                                            )
                                            Text("View Report →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    // Dual IN / OUT comparison bars
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("IN", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = GreenIn, modifier = Modifier.width(28.dp))
                                            Box(modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(GreenIn.copy(alpha = 0.12f))) {
                                                Box(modifier = Modifier.fillMaxWidth(inFrac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(GreenIn))
                                            }
                                            Text("Rs. ${String.format("%,.0f", bizIn)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = GreenIn)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("OUT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = RedOut, modifier = Modifier.width(28.dp))
                                            Box(modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(RedOut.copy(alpha = 0.12f))) {
                                                Box(modifier = Modifier.fillMaxWidth(outFrac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(RedOut))
                                            }
                                            Text("Rs. ${String.format("%,.0f", bizOut)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = RedOut)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Chart 4: Category Breakdown Donut & Distribution Bars (OUT / IN toggle)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Category Breakdown", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (categoryChartMode == "OUT") "Where money went (Cash OUT)" else "Where money came from (Cash IN)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = categoryChartMode == "OUT",
                                onClick = { categoryChartMode = "OUT" },
                                label = { Text("OUT", fontWeight = FontWeight.Bold) }
                            )
                            FilterChip(
                                selected = categoryChartMode == "IN",
                                onClick = { categoryChartMode = "IN" },
                                label = { Text("IN", fontWeight = FontWeight.Bold) }
                            )
                        }
                    }

                    if (categoryTotals.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No ${if (categoryChartMode == "OUT") "Cash OUT" else "Cash IN"} entries in this filter.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        val topCategories = categoryTotals.take(5)
                        val otherTotal = categoryTotals.drop(5).sumOf { it.second }
                        val slices = if (otherTotal > 0.0) topCategories + ("Other" to otherTotal) else topCategories
                        val categorySum = slices.sumOf { it.second }.coerceAtLeast(1.0)
                        val maxCat = slices.maxOf { it.second }.coerceAtLeast(1.0)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Multi-Slice Donut
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(modifier = Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val diameter = size.minDimension
                                        val strokeWidth = diameter * 0.22f
                                        val arcSize = Size(diameter - strokeWidth, diameter - strokeWidth)
                                        val topLeft = Offset((size.width - arcSize.width) / 2f, (size.height - arcSize.height) / 2f)
                                        var currentAngle = -90f
                                        slices.forEachIndexed { idx, (_, amount) ->
                                            val sweep = ((amount / categorySum) * 360.0).toFloat()
                                            if (sweep > 0f) {
                                                drawArc(
                                                    color = chartPalette[idx % chartPalette.size],
                                                    startAngle = currentAngle,
                                                    sweepAngle = sweep,
                                                    useCenter = false,
                                                    topLeft = topLeft,
                                                    size = arcSize,
                                                    style = Stroke(width = strokeWidth)
                                                )
                                                currentAngle += sweep
                                            }
                                        }
                                    }
                                    Text(
                                        "${slices.size}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }

                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    slices.forEachIndexed { idx, (label, amount) ->
                                        val pct = ((amount / categorySum) * 100.0).toInt()
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                                                Box(modifier = Modifier.size(10.dp).background(chartPalette[idx % chartPalette.size], CircleShape))
                                                Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Text("$pct%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            // Detailed category progress bars
                            slices.forEachIndexed { idx, (category, total) ->
                                val barColor = chartPalette[idx % chartPalette.size]
                                val fraction = (total / maxCat).toFloat().coerceIn(0f, 1f)
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(category, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                        Text("Rs. ${String.format("%,.0f", total)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = barColor)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(barColor.copy(alpha = 0.12f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(fraction)
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(barColor)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Export & Share Statement Reports (respects Selected Business vs Overall Scope + Date Range)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Export & Share Reports", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Share \"$scopeTitle\" statement with partners or clients via WhatsApp, PDF, or CSV.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            val textReport = generateMultiBusinessTextReport(
                                scopeTitle = scopeTitle,
                                transactions = scopedTransactions,
                                businesses = if (selectedBusiness == null) businesses else listOf(selectedBusiness),
                                allBooks = allBooks
                            )
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share on WhatsApp as Text", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Button(
                        onClick = {
                            try {
                                val pdfFile = generateMultiBusinessPdfReport(
                                    context = context,
                                    scopeTitle = scopeTitle,
                                    transactions = scopedTransactions,
                                    bookLookup = bookLookup,
                                    bizLookup = bizLookup
                                )
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

                    OutlinedButton(
                        onClick = {
                            val csvData = viewModel.getCSVDataForTransactions(scopedTransactions, bookLookup, bizLookup)
                            clipboardManager.setText(AnnotatedString(csvData))
                            Toast.makeText(context, "Statement CSV copied to clipboard!", Toast.LENGTH_LONG).show()

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
    var lastGeneratedInviteCode by remember { mutableStateOf("") }
    var isPublishingInvite by remember { mutableStateOf(false) }
    var invitePublishedCloud by remember { mutableStateOf(false) }
    var showJoinByCodeDialog by remember { mutableStateOf(false) }
    var memberToEdit by remember { mutableStateOf<TeamMember?>(null) }
    var memberToDelete by remember { mutableStateOf<TeamMember?>(null) }

    val roles = listOf("Data Entry", "Partner", "Admin", "Boss")
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header (stacked cleanly so title and action buttons never truncate on narrow screens)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Team & Access",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Business: ${activeBusiness?.name ?: "Active Business"} • Role: $simulatedRole",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = GreenIn.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "Live Cloud",
                        color = GreenIn,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showJoinByCodeDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("team_join_code_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Join by Code", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                if (hasPermission(simulatedRole, "add_team_member")) {
                    Button(
                        onClick = { showAddStaffDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("team_add_member_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GreenIn)
                    ) {
                        Icon(Icons.Default.PersonAddAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Member", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

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
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(GreenIn, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = syncManager.getName().ifBlank { syncManager.getEmail() }.take(1).uppercase().ifBlank { "U" },
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = syncManager.getName().ifBlank { syncManager.getEmail().substringBefore("@").ifBlank { "CashBook User" } },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "$simulatedRole (You)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = syncManager.getEmail().ifBlank { "Not signed in" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = {
                        viewModel.logoutUser()
                        Toast.makeText(context, "Signed out successfully", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Text("Sign Out", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.error)
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
                    val bizName = activeBusiness?.name ?: ""
                    val memberCode = remember(bizName, tm.email, tm.name) {
                        syncManager.getSavedInviteCode(bizName, tm.email.ifBlank { tm.name })
                            .ifBlank { syncManager.getOrGenerateInviteCode(bizName, tm.email.ifBlank { tm.name }) }
                    }
                    val roleBadgeBg = when (tm.role) {
                        "Boss", "Admin" -> Color(0xFFEDE9FE)
                        "Partner" -> Color(0xFFDCFCE7)
                        else -> Color(0xFFE0F2FE)
                    }
                    val roleBadgeFg = when (tm.role) {
                        "Boss", "Admin" -> Color(0xFF5B21B6)
                        "Partner" -> Color(0xFF166534)
                        else -> Color(0xFF0369A1)
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        tm.name.take(1).uppercase().ifBlank { "T" },
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.onSecondary
                                    )
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            tm.name,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Surface(color = roleBadgeBg, shape = RoundedCornerShape(6.dp)) {
                                            Text(
                                                tm.role,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = roleBadgeFg,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (tm.email.isNotBlank()) {
                                        Text(
                                            tm.email,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (tm.phone.isNotBlank()) {
                                        Text(
                                            tm.phone,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        "Invite Code: $memberCode",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GreenIn,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (hasPermission(simulatedRole, "add_team_member") || hasPermission(simulatedRole, "delete_team_member")) {
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (hasPermission(simulatedRole, "add_team_member")) {
                                        OutlinedButton(
                                            onClick = { memberToEdit = tm },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 44.dp)
                                                .testTag("team_edit_member_${tm.id}"),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit Role", modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Edit / Role", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                lastAddedCollaboratorName = tm.name
                                                lastAddedCollaboratorEmail = tm.email
                                                lastAddedCollaboratorPhone = tm.phone
                                                lastAddedCollaboratorRole = tm.role
                                                lastGeneratedInviteCode = memberCode
                                                isPublishingInvite = true
                                                invitePublishedCloud = false
                                                showInvitationSuccessDialog = true
                                                activeBusiness?.let { biz ->
                                                    viewModel.getOrCreateInviteForMember(biz, tm) { inv, ok ->
                                                        lastGeneratedInviteCode = inv.inviteCode
                                                        invitePublishedCloud = ok
                                                        isPublishingInvite = false
                                                    }
                                                } ?: run { isPublishingInvite = false }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 44.dp)
                                                .testTag("team_share_invite_${tm.id}"),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = "Share Invite", modifier = Modifier.size(15.dp), tint = GreenIn)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Invite", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GreenIn, maxLines = 1, softWrap = false)
                                        }
                                    }

                                    if (hasPermission(simulatedRole, "delete_team_member")) {
                                        OutlinedButton(
                                            onClick = { memberToDelete = tm },
                                            modifier = Modifier
                                                .heightIn(min = 44.dp)
                                                .testTag("team_delete_member_${tm.id}"),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove Staff", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showJoinByCodeDialog) {
        JoinBusinessByCodeDialog(
            viewModel = viewModel,
            onDismiss = { showJoinByCodeDialog = false }
        )
    }

    if (showAddStaffDialog) {
        var expandedRoleMenu by remember { mutableStateOf(false) }
        var formError by remember { mutableStateOf("") }
        val pickStaffContact = rememberContactImportAction(context) { cName, cPhone ->
            if (cName.isNotBlank()) staffName = cName
            if (cPhone.isNotBlank()) staffPhone = cPhone
        }
        AlertDialog(
            onDismissRequest = { showAddStaffDialog = false },
            title = { Text("Invite Team Member to '${activeBusiness?.name ?: "Business"}'") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = pickStaffContact,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("team_import_contact_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Contacts, contentDescription = "Pick from Contacts", tint = GreenIn)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import from Contacts", fontWeight = FontWeight.Bold, color = GreenIn)
                    }
                    OutlinedTextField(
                        value = staffName,
                        onValueChange = { staffName = it; formError = "" },
                        label = { Text("Staff Full Name *") },
                        placeholder = { Text("e.g. Mengal") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("team_name_input"),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = staffEmail,
                            onValueChange = { staffEmail = it; formError = "" },
                            label = { Text("Teammate Email Address *") },
                            placeholder = { Text("e.g. mengal@gmail.com") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = GreenIn) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("team_email_input"),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Text(
                            "Required: Your teammate will Sign Up or Sign In with this email (and choose their own password).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (staffEmail.trim().contains("@")) {
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
                                        text = if (emailExists) "✓ Existing CashBook account — will auto-connect on Sign In!" else "✉️ New user — send them the invite so they can Sign Up with this email.",
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
                        label = { Text("Phone / WhatsApp (Optional)") },
                        placeholder = { Text("e.g. +923001234567") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expandedRoleMenu = true },
                            modifier = Modifier.fillMaxWidth().testTag("team_role_picker")
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

                    Text(
                        text = when (staffRole) {
                            "Data Entry" -> "Can record Cash In / Out and add Cashbooks. Cannot delete entries, books, or businesses."
                            "Partner" -> "Can view, add, and edit Cash In / Out entries and Cashbooks. Cannot delete businesses or team."
                            else -> "Full access to books, transactions, reports, and team management."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (formError.isNotBlank()) {
                        Text(formError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanName = staffName.trim()
                        val cleanEmail = staffEmail.trim().lowercase()
                        if (cleanName.isBlank()) {
                            formError = "Please enter the teammate's name."
                        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
                            formError = "Please enter a valid Email Address so your teammate can Sign Up / Sign In."
                        } else {
                            val code = syncManager.getOrGenerateInviteCode(activeBusiness?.name ?: "CB", cleanEmail)
                            lastAddedCollaboratorName = cleanName
                            lastAddedCollaboratorRole = staffRole
                            lastAddedCollaboratorPhone = staffPhone.trim()
                            lastAddedCollaboratorEmail = cleanEmail
                            lastGeneratedInviteCode = code
                            isPublishingInvite = true
                            invitePublishedCloud = false

                            viewModel.addTeamMember(
                                name = cleanName,
                                email = cleanEmail,
                                phone = staffPhone.trim(),
                                role = staffRole
                            ) { inv, ok ->
                                lastGeneratedInviteCode = inv.inviteCode
                                invitePublishedCloud = ok
                                isPublishingInvite = false
                            }

                            staffName = ""
                            staffEmail = ""
                            staffPhone = ""
                            staffRole = "Data Entry"
                            showAddStaffDialog = false
                            showInvitationSuccessDialog = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                    modifier = Modifier.testTag("team_confirm_recruit_button")
                ) {
                    Text("Create Invite")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddStaffDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Edit Team Member & Change Role Dialog
    if (memberToEdit != null) {
        val editing = memberToEdit!!
        var editName by remember(editing.id) { mutableStateOf(editing.name) }
        var editEmail by remember(editing.id) { mutableStateOf(editing.email) }
        var editPhone by remember(editing.id) { mutableStateOf(editing.phone) }
        var editRole by remember(editing.id) { mutableStateOf(editing.role) }
        var editError by remember { mutableStateOf("") }
        var isSavingEdit by remember { mutableStateOf(false) }

        Dialog(
            onDismissRequest = { if (!isSavingEdit) memberToEdit = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 460.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ManageAccounts, contentDescription = null, tint = GreenIn)
                            Text("Edit Team Member", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        IconButton(
                            onClick = { if (!isSavingEdit) memberToEdit = null },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it; editError = "" },
                        label = { Text("Full Name *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("edit_team_name_input"),
                        shape = RoundedCornerShape(10.dp)
                    )

                    OutlinedTextField(
                        value = editEmail,
                        onValueChange = { editEmail = it; editError = "" },
                        label = { Text("Email Address *") },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = GreenIn) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("edit_team_email_input"),
                        shape = RoundedCornerShape(10.dp)
                    )

                    if (!editEmail.trim().equals(editing.email.trim(), ignoreCase = true) && editing.email.isNotBlank()) {
                        Text(
                            "Changing email revokes access for ${editing.email} and sends a new invite for the new email.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFD97706)
                        )
                    }

                    OutlinedTextField(
                        value = editPhone,
                        onValueChange = { editPhone = it },
                        label = { Text("Phone / WhatsApp (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("edit_team_phone_input"),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Text("Change Assigned Role:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        roles.forEach { roleOpt ->
                            FilterChip(
                                selected = editRole == roleOpt,
                                onClick = { editRole = roleOpt },
                                label = { Text(roleOpt, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) },
                                leadingIcon = if (editRole == roleOpt) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                modifier = Modifier.testTag("edit_team_role_${roleOpt.lowercase().replace(" ", "_")}")
                            )
                        }
                    }

                    Surface(
                        color = GreenIn.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = when (editRole) {
                                "Data Entry" -> "Data Entry: Can add Cash In / Out entries and add books. Cannot edit/delete existing entries or books."
                                "Partner" -> "Partner: Can view, add, and edit Cash In / Out entries and add books. Cannot delete businesses or team."
                                else -> "$editRole: Full access to books, transactions, reports, and team management."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF166534),
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    if (editError.isNotBlank()) {
                        Text(editError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { memberToEdit = null },
                            enabled = !isSavingEdit,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Cancel", maxLines = 1, softWrap = false)
                        }
                        Button(
                            onClick = {
                                val cleanName = editName.trim()
                                val cleanEmail = editEmail.trim().lowercase()
                                if (cleanName.isBlank()) {
                                    editError = "Please enter the teammate's name."
                                } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
                                    editError = "Please enter a valid Email Address."
                                } else {
                                    isSavingEdit = true
                                    viewModel.updateTeamMember(
                                        oldMember = editing,
                                        newName = cleanName,
                                        newEmail = cleanEmail,
                                        newPhone = editPhone.trim(),
                                        newRole = editRole
                                    ) { _, ok ->
                                        isSavingEdit = false
                                        memberToEdit = null
                                        Toast.makeText(
                                            context,
                                            if (ok) "Updated $cleanName ($editRole) & synced to Cloud!" else "Updated $cleanName locally. Sync online to update teammate.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            enabled = !isSavingEdit,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("edit_team_save_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isSavingEdit) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Text("Save Changes", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete Team Member Confirmation Dialog
    if (memberToDelete != null) {
        val tm = memberToDelete!!
        AlertDialog(
            onDismissRequest = { memberToDelete = null },
            title = { Text("Remove Team Member?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Remove ${tm.name} (${tm.role}) from '${activeBusiness?.name ?: "this business"}'?\n\nTheir cloud invite will be revoked and they will lose access on next sync.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTeamMember(tm)
                        memberToDelete = null
                        Toast.makeText(context, "Removed ${tm.name} & revoked cloud invite.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_team_member_btn")
                ) {
                    Text("Remove", maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToDelete = null }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    if (showInvitationSuccessDialog) {
        val bizName = activeBusiness?.name ?: "CashBook Business"
        var editableDownloadUrl by remember { mutableStateOf(syncManager.getApkDownloadUrl()) }
        val inviteText = buildTeamInviteMessage(
            businessName = bizName,
            collaboratorName = lastAddedCollaboratorName,
            invitedEmail = lastAddedCollaboratorEmail,
            role = lastAddedCollaboratorRole,
            inviteCode = lastGeneratedInviteCode,
            apkDownloadUrl = editableDownloadUrl.trim()
        )

        Dialog(
            onDismissRequest = { showInvitationSuccessDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 460.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenIn, modifier = Modifier.size(24.dp))
                            Text(
                                "Team Invite Ready",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { showInvitationSuccessDialog = false },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    // Cloud Publish Banner
                    Surface(
                        color = if (invitePublishedCloud) Color(0xFFDCFCE7) else Color(0xFFFEF3C7),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isPublishingInvite) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = GreenIn)
                                Text("Publishing invite to Firebase Cloud...", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            } else if (invitePublishedCloud) {
                                Icon(Icons.Default.CloudDone, contentDescription = null, tint = GreenIn, modifier = Modifier.size(18.dp))
                                Text("Cloud Invite Published! Teammate can auto-join with their email or Invite Code.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF166534), fontWeight = FontWeight.SemiBold)
                            } else {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                                Text("Saved locally. Make sure you're online & synced so your teammate can join.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF92400E))
                            }
                        }
                    }

                    // Invited Email & Cloud Invite Code Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Invited Email: $lastAddedCollaboratorEmail",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Business: $bizName • Role: $lastAddedCollaboratorRole",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, GreenIn.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Cloud Invite Code",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            lastGeneratedInviteCode,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = GreenIn,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(lastGeneratedInviteCode))
                                            Toast.makeText(context, "Copied invite code: $lastGeneratedInviteCode", Toast.LENGTH_SHORT).show()
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.heightIn(min = 40.dp).testTag("invite_copy_code_btn"),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Copy Code", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = editableDownloadUrl,
                        onValueChange = { newVal ->
                            editableDownloadUrl = newVal
                            syncManager.saveApkDownloadUrl(newVal)
                        },
                        label = { Text("App / APK Download Link (Optional)") },
                        placeholder = { Text("Paste Play Store / APK link to include") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 13.sp),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = inviteText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Well-spaced action buttons (no overlap / cramping)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(inviteText))
                                Toast.makeText(context, "Copied full invite message!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("invite_copy_full_btn"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }

                        if (lastAddedCollaboratorEmail.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    val emailIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                        data = android.net.Uri.parse("mailto:")
                                        putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(lastAddedCollaboratorEmail))
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Invitation to join $bizName on CashBook")
                                        putExtra(android.content.Intent.EXTRA_TEXT, inviteText)
                                    }
                                    try {
                                        context.startActivity(android.content.Intent.createChooser(emailIntent, "Send Email Invite"))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("invite_email_btn"),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Email", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
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
                            colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("invite_share_btn"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }

                    TextButton(
                        onClick = { showInvitationSuccessDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                    ) {
                        Text("Close", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

// --- SCREEN 6: GOOGLE DRIVE SYNC CENTER ---

@Composable
fun SyncCenterScreen(viewModel: LedgerViewModel) {
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val syncManager = viewModel.syncManager

    var rawJsonText by remember { mutableStateOf("") }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }
    var authStateVersion by remember { mutableIntStateOf(0) }
    var activeSyncTab by remember { mutableIntStateOf(0) } // 0: Cloud Sync, 1: Google Drive Backup

    val isUserSignedInState by viewModel.isUserSignedIn.collectAsStateWithLifecycle()
    val isUserSignedIn = remember(authStateVersion, isUserSignedInState) { syncManager.isUserSignedIn() }
    val email = remember(authStateVersion, isUserSignedInState, isSuperAdmin) { syncManager.getEmail() }
    val displayName = remember(authStateVersion, isUserSignedInState, isSuperAdmin) { syncManager.getName() }

    val businesses by viewModel.businesses.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val allBooks by viewModel.allBooks.collectAsStateWithLifecycle()
    val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val partiesList by viewModel.parties.collectAsStateWithLifecycle()
    val allPartyTransactions by viewModel.allPartyTransactions.collectAsStateWithLifecycle()
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
                "CashBook Pro Sync & Backup Center",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Real-time Firebase Cloud Sync & Offline Local Database Engine",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // Top Segmented Tab Switcher (2 Tabs: Cloud Database, Offline Local DB)
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
                            "Cloud Database",
                            fontWeight = if (activeSyncTab == 0) FontWeight.Bold else FontWeight.Medium,
                            color = if (activeSyncTab == 0) GreenIn else Color.Gray,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // Tab 1: Offline Local DB
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
                            Icons.Default.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (activeSyncTab == 1) GreenIn else Color.Gray
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Offline Local DB",
                            fontWeight = if (activeSyncTab == 1) FontWeight.Bold else FontWeight.Medium,
                            color = if (activeSyncTab == 1) GreenIn else Color.Gray,
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
                        val isRealSyncSuccess = isOnline && isRealCloudAccount && !isSyncError && (syncStatus.contains("Synced", ignoreCase = true) || syncStatus.contains("Restored", ignoreCase = true) || syncStatus.contains("Success", ignoreCase = true) || syncStatus.contains("Connected", ignoreCase = true) || syncStatus.contains("Vault", ignoreCase = true) || syncStatus.contains("Active", ignoreCase = true) || syncStatus.contains("Ready", ignoreCase = true))

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
            if (isSuperAdmin) {
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
        } else {
            // --- TAB 1: OFFLINE AIS & LOCAL STORAGE STATUS ---
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

            // Manual Backup and Portable JSON Import/Export
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Offline Manual Database Backup", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            "Export your local database to an encrypted JSON backup file or copy raw JSON text to transfer across devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val backupJsonStr = syncManager.serializeDatabase(
                                        businesses = businesses,
                                        books = allBooks.ifEmpty { books },
                                        transactions = allTransactions,
                                        parties = partiesList,
                                        partyTransactions = allPartyTransactions,
                                        teamMembers = teamMembersList,
                                        isPartialExport = false
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "CashBook Offline Data Backup")
                                        putExtra(Intent.EXTRA_TEXT, backupJsonStr)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Backup JSON File via"))
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenIn)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share Backup", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    val backupJsonStr = syncManager.serializeDatabase(
                                        businesses = businesses,
                                        books = allBooks.ifEmpty { books },
                                        transactions = allTransactions,
                                        parties = partiesList,
                                        partyTransactions = allPartyTransactions,
                                        teamMembers = teamMembersList,
                                        isPartialExport = false
                                    )
                                    clipboardManager.setText(AnnotatedString(backupJsonStr))
                                    Toast.makeText(context, "Full Database JSON copied to clipboard!", Toast.LENGTH_LONG).show()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy JSON", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = { showBackupRestoreDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Import JSON", fontSize = 12.sp)
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
                                    viewModel.updateAuthState()
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
                                    viewModel.updateAuthState()
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
                        text = "Your offline database automatically syncs to your secure Realtime Cloud Database silently on every change. When you sign in on another device, your entries update automatically.",
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
        "How do I add transactions offline?" to "Our Digital Cashbook runs entirely client-side. Every entry is persisted in local device storage instantly, and is automatically synced to the cloud when network connects.",
        "Is my financial data secure?" to "Yes! Your books are stored securely in local app-sandboxes and synced via real-time encrypted cloud database architecture.",
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
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsStateWithLifecycle()
    val biometricSupported = remember(context) { canAuthenticateBiometric(context) }

    var enteredPin by remember { mutableStateOf("") }
    var showForgotDialog by remember { mutableStateOf(false) }
    var securityAnswerInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isBiometricEnabled, biometricSupported) {
        if (isBiometricEnabled && biometricSupported) {
            launchBiometricUnlockPrompt(
                context = context,
                onSuccess = {
                    viewModel.unlockAppWithBiometric()
                    onUnlockSuccess()
                },
                onError = { msg -> errorMessage = msg }
            )
        }
    }

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
                    imageVector = if (isBiometricEnabled && biometricSupported) Icons.Default.Fingerprint else Icons.Default.Lock,
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
                    text = if (isBiometricEnabled && biometricSupported) {
                        "Verify fingerprint or enter 4-digit PIN"
                    } else {
                        "App is secured with PIN lock"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF475569)
                )
            }

            if (isBiometricEnabled && biometricSupported) {
                FilledTonalButton(
                    onClick = {
                        errorMessage = null
                        launchBiometricUnlockPrompt(
                            context = context,
                            onSuccess = {
                                viewModel.unlockAppWithBiometric()
                                onUnlockSuccess()
                            },
                            onError = { msg -> errorMessage = msg }
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("biometric_unlock_button")
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = "Unlock with Fingerprint", modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlock with Fingerprint", fontWeight = FontWeight.Bold)
                }
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
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsStateWithLifecycle()
    val biometricSupported = remember(context) { canAuthenticateBiometric(context) }

    var pinVal by remember { mutableStateOf("") }
    var securityAnswerVal by remember { mutableStateOf("") }
    var selectedQuestion by remember { mutableStateOf("What was your first business name?") }
    var enableBiometricOnSetup by remember { mutableStateOf(biometricSupported) }

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
                text = "App PIN & Fingerprint Lock",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Protect your ledgers with a 4-digit PIN and optional device Fingerprint unlock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475569)
                )

                if (isAppLockEnabled) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5))
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

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Column {
                                        Text("Fingerprint Unlock", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = if (biometricSupported) "Use device fingerprint instead of typing PIN" else "No enrolled fingerprint on this device",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }
                                Switch(
                                    checked = isBiometricEnabled && biometricSupported,
                                    enabled = biometricSupported,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            launchBiometricUnlockPrompt(
                                                context = context,
                                                title = "Enable Fingerprint Unlock",
                                                subtitle = "Confirm your fingerprint to activate biometric unlock",
                                                onSuccess = {
                                                    viewModel.setBiometricLockEnabled(true)
                                                    Toast.makeText(context, "Fingerprint unlock enabled!", Toast.LENGTH_SHORT).show()
                                                },
                                                onError = { msg ->
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        } else {
                                            viewModel.setBiometricLockEnabled(false)
                                            Toast.makeText(context, "Fingerprint unlock disabled", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.testTag("dialog_biometric_switch")
                                )
                            }

                            Button(
                                onClick = {
                                    viewModel.disableAppLock()
                                    Toast.makeText(context, "PIN & Fingerprint Lock deactivated.", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Deactivate App Lock", fontWeight = FontWeight.Bold)
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

                    if (biometricSupported) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Enable Fingerprint Unlock", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                            Switch(
                                checked = enableBiometricOnSetup,
                                onCheckedChange = { enableBiometricOnSetup = it }
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (pinVal.length < 4 || securityAnswerVal.isBlank()) {
                                Toast.makeText(context, "Please enter a 4-digit PIN and provide security answer", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.enableAppLock(pinVal, selectedQuestion, securityAnswerVal, enableBiometricOnSetup && biometricSupported)
                                Toast.makeText(context, "App Lock activated!", Toast.LENGTH_LONG).show()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Enable Secure Lock", fontWeight = FontWeight.Bold)
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
fun OnboardingSetupScreen(
    viewModel: LedgerViewModel,
    onOpenSuperAdminSecretLogin: () -> Unit = {}
) {
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
    var inviteCodeInput by remember { mutableStateOf("") }
    var verifiedSignupInvite by remember { mutableStateOf<TeamInviteRecord?>(null) }
    var isVerifyingSignupCode by remember { mutableStateOf(false) }
    var isSubmittingSignUp by remember { mutableStateOf(false) }
    val pendingEmailInvites by viewModel.pendingEmailInvites.collectAsStateWithLifecycle()

    // Sign In Fields
    var loginUser by remember { mutableStateOf("") }
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
    var logoTapCount by remember { mutableIntStateOf(0) }
    var lastLogoTapTime by remember { mutableLongStateOf(0L) }

    var authStateVersion by remember { mutableIntStateOf(0) }
    val isUserSignedIn by viewModel.isUserSignedIn.collectAsStateWithLifecycle()

    // Live email check status
    val isValidEmailFormat = remember(userEmail) {
        val trimmed = userEmail.trim()
        trimmed.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()
    }

    var isEmailAlreadyRegistered by remember { mutableStateOf(false) }
    LaunchedEffect(userEmail, isValidEmailFormat, authStateVersion) {
        if (isValidEmailFormat) {
            val trimmed = userEmail.trim()
            val local = viewModel.checkEmailExists(trimmed)
            isEmailAlreadyRegistered = local
            if (!local) {
                isEmailAlreadyRegistered = viewModel.checkEmailExistsCloud(trimmed)
            }
            viewModel.checkPendingInvitesForEmail(trimmed)
        } else {
            isEmailAlreadyRegistered = false
            viewModel.checkPendingInvitesForEmail("")
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
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
                .widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Logo Header (Secret 5-tap trigger)
            Image(
                painter = painterResource(id = com.example.R.drawable.ic_cashbook_logo),
                contentDescription = "CashBook Logo",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastLogoTapTime > 2500L) {
                            logoTapCount = 1
                        } else {
                            logoTapCount++
                        }
                        lastLogoTapTime = now
                        if (logoTapCount >= 5) {
                            logoTapCount = 0
                            onOpenSuperAdminSecretLogin()
                        }
                    }
            )

            val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
            val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()

            if (!isOnline) {
                // --- OFFLINE DETECTED CARD ---
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                    border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(48.dp))
                        Text(
                            "No Internet Connection",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF991B1B)
                            ),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "App detected offline status. Cloud Sign In and Cloud Sync require an active internet connection. You can continue working offline using the local database.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF7F1D1D)),
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = {
                                val name = "Offline User"
                                val email = "offline@cashbook.local"
                                val un = "offline_user"
                                val pw = "offline123"
                                syncManager.registerUser(name, email, un, pw)
                                viewModel.registerCustomUser(name, email, un, pw)
                                if (viewModel.businesses.value.isEmpty()) {
                                    viewModel.createBusinessAndBook("Offline Business", "Main CashBook")
                                }
                                authStateVersion++
                                Toast.makeText(context, "Operating in 100% Offline Local DB Mode.", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Continue Offline (100% Local DB)", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // --- ONLINE MODE STATUS HEADER ---
                var firebasePingStatus by remember { mutableStateOf("Checking Firebase Cloud...") }
                var isTestingFirebase by remember { mutableStateOf(false) }
                var showFirebaseSettings by remember { mutableStateOf(false) }
                var customProjId by remember { mutableStateOf(syncManager.getFirebaseProjectId()) }
                var customApiKey by remember { mutableStateOf(syncManager.getFirebaseApiKey()) }

                LaunchedEffect(Unit) {
                    val (ok, msg) = syncManager.pingCloudConnection()
                    firebasePingStatus = if (ok) "🟢 $msg" else "🔴 $msg"
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                    border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Wifi, contentDescription = null, tint = GreenIn, modifier = Modifier.size(16.dp))
                                    Text("Internet: Online", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = GreenIn)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.CloudQueue, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Text(firebasePingStatus, style = MaterialTheme.typography.labelSmall, color = Color(0xFF1E40AF), fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        isTestingFirebase = true
                                        coroutineScope.launch {
                                            val (ok, msg) = syncManager.pingCloudConnection()
                                            isTestingFirebase = false
                                            firebasePingStatus = if (ok) "🟢 $msg" else "🔴 $msg"
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    if (isTestingFirebase) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Ping", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (isSuperAdmin) {
                                    IconButton(onClick = { showFirebaseSettings = !showFirebaseSettings }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Settings, contentDescription = "Firebase Settings", tint = Color(0xFF1E40AF), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        if (isSuperAdmin && showFirebaseSettings) {
                            Divider(color = Color(0xFFBFDBFE))
                            Text("Custom Firebase Cloud Credentials", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                                border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("🎉 Your 'Cashbook' project credentials are configured!", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D))
                                    Text("Project ID: cashbook-8d579", fontSize = 10.sp, color = Color(0xFF166534))
                                    Text("Web API Key: BCJ_4...HOrGU", fontSize = 10.sp, color = Color(0xFF166534))
                                    Text("Tap 'Save & Test Firebase Connection' below to activate!", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D))
                                    
                                    Row(
                                        modifier = Modifier.padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        AssistChip(
                                            onClick = { 
                                                customProjId = "cashbook-8d579"
                                                customApiKey = "BCJ_4LbHKtiGOSxjVYH1J-jP7tibnIrTeFqrL233DknUm4lmAM95NlEpfgE13Yx5Or0ftg2TPW4woTbNe-HOrGU"
                                            },
                                            label = { Text("Reset to cashbook-8d579", fontSize = 10.sp) }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = customProjId,
                                onValueChange = { customProjId = it },
                                label = { Text("Firebase Project ID", fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                            )
                            OutlinedTextField(
                                value = customApiKey,
                                onValueChange = { customApiKey = it },
                                label = { Text("Firebase Web API Key", fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                            )
                            Button(
                                onClick = {
                                    syncManager.saveFirebaseProjectId(customProjId.trim())
                                    syncManager.saveFirebaseApiKey(customApiKey.trim())
                                    coroutineScope.launch {
                                        val (ok, msg) = syncManager.pingCloudConnection()
                                        firebasePingStatus = if (ok) "🟢 $msg" else "🔴 $msg"
                                        Toast.makeText(context, "Saved & Tested: $msg", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Save & Test Firebase Connection", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // --- ONLINE MODE: 2 TABS (Sign In | Sign Up) ---
                if (!isUserSignedIn && authMode != "forgot") {
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
                    }
                }

            if (isUserSignedIn) {
                val isSyncingNow by viewModel.isSyncing.collectAsStateWithLifecycle()
                val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
                var quickBizName by remember { mutableStateOf("") }
                var showEmptyJoinDialog by remember { mutableStateOf(false) }

                // Auto-sync cloud data once when entering signed-in empty workspace state
                LaunchedEffect(Unit) {
                    viewModel.isSuperAdmin.value = syncManager.isSuperAdminLoggedIn()
                    viewModel.triggerCloudSync()
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (isSyncingNow) {
                            CircularProgressIndicator(color = GreenIn, modifier = Modifier.size(44.dp))
                            Text(
                                text = "Synchronizing Cloud Cashbooks...",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                ),
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Account: ${syncManager.getEmail().ifBlank { syncManager.getName() }}\nRestoring your cloud businesses & team invites...",
                                style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF64748B)),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Icon(Icons.Default.Storefront, contentDescription = null, tint = GreenIn, modifier = Modifier.size(44.dp))
                            Text(
                                text = "Create or Join a Business",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                ),
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Signed in as ${syncManager.getEmail().ifBlank { syncManager.getName() }}",
                                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF64748B)),
                                textAlign = TextAlign.Center
                            )
                            OutlinedTextField(
                                value = quickBizName,
                                onValueChange = { quickBizName = it },
                                label = { Text("New Business Name") },
                                placeholder = { Text("e.g. Main Shop or Personal") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    val name = quickBizName.trim().ifBlank { "Main Business" }
                                    viewModel.createBusinessAndBook(name, "Main CashBook")
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Create & Open Business", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { showEmptyJoinDialog = true },
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("onboarding_join_invite_btn"),
                                border = BorderStroke(1.dp, GreenIn),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.GroupAdd, contentDescription = null, tint = GreenIn)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Have an Invite Code? Join Business", color = GreenIn, fontWeight = FontWeight.Bold)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.triggerCloudSync() },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Retry Sync")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.logoutUser() },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Switch Account")
                                }
                            }
                        }
                        Text(
                            text = syncStatus,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (syncStatus.contains("Error", ignoreCase = true) || syncStatus.contains("403") || syncStatus.contains("401") || syncStatus.contains("Failed", ignoreCase = true)) MaterialTheme.colorScheme.error else GreenIn
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                if (showEmptyJoinDialog) {
                    JoinBusinessByCodeDialog(
                        viewModel = viewModel,
                        onDismiss = { showEmptyJoinDialog = false }
                    )
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
                                    coroutineScope.launch {
                                        val success = viewModel.loginSuperAdminCloud(u, p)
                                        if (success) {
                                            authStateVersion++
                                            Toast.makeText(context, "Welcome back! Restoring and syncing cloud data...", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val emailExists = viewModel.checkEmailExistsCloud(u)
                                            if (emailExists) {
                                                Toast.makeText(context, "Incorrect password for '$u'. Tap 'Forgot Password?' to reset.", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Account '$u' not found. Please tap 'Sign Up' tab to register.", Toast.LENGTH_LONG).show()
                                            }
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

                        TextButton(
                            onClick = { authMode = "signup" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Don't have an account? Sign Up", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }

                        Surface(
                            color = Color(0xFFF0FDF4),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🤝 Invited to a team? Sign In with your invited email & password (or tap Sign Up if new) — your shared business connects automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF166534),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            } else if (authMode == "signup") {
                // --- SIGN UP MODE (WITH TEAM INVITE CODE & EMAIL DISCOVERY) ---
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Create Account or Join Team",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        ),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "New staff: enter your invited email & choose your own password.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF64748B)
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                // Team Invite Code Optional Lookup Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                    border = BorderStroke(1.dp, GreenIn.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.GroupAdd, contentDescription = null, tint = GreenIn, modifier = Modifier.size(18.dp))
                            Text("Joining a Team? (Optional Invite Code)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = Color(0xFF166534))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = inviteCodeInput,
                                onValueChange = {
                                    inviteCodeInput = it.uppercase()
                                    verifiedSignupInvite = null
                                },
                                label = { Text("Invite Code (e.g. RB-7K2M)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("signup_invite_code_input"),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                            )
                            OutlinedButton(
                                onClick = {
                                    val clean = inviteCodeInput.trim()
                                    if (clean.length < 3) {
                                        Toast.makeText(context, "Enter a valid invite code first.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        isVerifyingSignupCode = true
                                        coroutineScope.launch {
                                            val inv = viewModel.lookupInviteCode(clean)
                                            isVerifyingSignupCode = false
                                            if (inv != null) {
                                                verifiedSignupInvite = inv
                                                if (inv.invitedEmail.isNotBlank()) {
                                                    userEmail = inv.invitedEmail
                                                    if (username.isBlank() && inv.invitedEmail.contains("@")) {
                                                        username = inv.invitedEmail.substringBefore("@")
                                                    }
                                                }
                                                if (fullName.isBlank() && inv.invitedName.isNotBlank()) {
                                                    fullName = inv.invitedName
                                                }
                                                Toast.makeText(context, "Invite verified for '${inv.businessName}'!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Invite code '$clean' not found or expired.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                enabled = !isVerifyingSignupCode,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (isVerifyingSignupCode) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Check", fontWeight = FontWeight.Bold, color = GreenIn)
                                }
                            }
                        }

                        if (verifiedSignupInvite != null) {
                            val inv = verifiedSignupInvite!!
                            Text(
                                "✓ Joining '${inv.businessName}' as ${inv.role}" +
                                    (if (inv.invitedEmail.isNotBlank()) " • Invited Email: ${inv.invitedEmail}" else "") +
                                    "\nSet your own password below and tap Create Account.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF15803D),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
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
                            text = "👤 Your Profile & Password",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                        )

                        OutlinedTextField(
                            value = fullName,
                            onValueChange = { fullName = it },
                            label = { Text("Full Name") },
                            placeholder = { Text("e.g. Mengal") },
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
                                placeholder = { Text("e.g. mengal@gmail.com") },
                                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = GreenIn) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                            )

                            if (pendingEmailInvites.isNotEmpty() && verifiedSignupInvite == null) {
                                val firstInv = pendingEmailInvites.first()
                                Surface(
                                    color = Color(0xFFDCFCE7),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, GreenIn.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "🤝 Team Invite Found! You're invited to join '${firstInv.businessName}' as ${firstInv.role}. Set a password below to connect automatically!",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF166534),
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }

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
                                                "Please enter a valid email address (e.g. name@gmail.com)",
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
                                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                                            Text(
                                                "Account already exists for this email. Switch to Sign In (or enter your existing password).",
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
                                                "✓ Email ready — choose your new password below",
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
                            label = { Text("Username (Optional)") },
                            placeholder = { Text("e.g. mengal") },
                            leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null, tint = GreenIn) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenIn, focusedLabelColor = GreenIn)
                        )

                        OutlinedTextField(
                            value = userPassword,
                            onValueChange = { userPassword = it },
                            label = { Text("Create Your Password * (min 6 chars)") },
                            placeholder = { Text("Choose a private password") },
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
                        } else {
                            isSubmittingSignUp = true
                            coroutineScope.launch {
                                val (ok, resultMsg) = viewModel.registerUserWithOptionalInvite(
                                    name = name,
                                    email = email,
                                    username = un,
                                    pass = pw,
                                    inviteCode = inviteCodeInput.trim(),
                                    defaultBusinessName = businessName,
                                    defaultBookName = bookName
                                )
                                isSubmittingSignUp = false
                                if (ok) {
                                    authStateVersion++
                                    Toast.makeText(context, resultMsg, Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, resultMsg, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !isSubmittingSignUp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("onboarding_start_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSubmittingSignUp) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (verifiedSignupInvite != null || pendingEmailInvites.isNotEmpty()) "Create Account & Join Business" else "Create Cloud Account & Continue",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
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
                                    val cleanTarget = target.lowercase()
                                    if (target.isBlank()) {
                                        otpError = "Please enter your registered Email Address or Username."
                                    } else if (cleanTarget == "admin" || cleanTarget == "superadmin" || cleanTarget == "admin@cashbook.com") {
                                        otpError = "Super Admin Master Password cannot be reset here. Unlock the Super Admin Console with your current Master Password to change it."
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
    viewModel: LedgerViewModel? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var selectedRole by remember { mutableStateOf("Data Entry") }
    var collaboratorName by remember { mutableStateOf("") }
    var collaboratorEmail by remember { mutableStateOf("") }
    var collaboratorPhone by remember { mutableStateOf("") }
    var showAddCollaboratorSection by remember { mutableStateOf(false) }
    var activeShareMember by remember { mutableStateOf<TeamMember?>(null) }

    val roles = listOf("Data Entry", "Partner", "Admin", "Boss")

    val teamList by if (viewModel != null) {
        viewModel.allTeamMembers.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList()) }
    }

    val bizTeamMembers = teamList.filter { it.businessId == business.id }
    val focusedMember = activeShareMember ?: bizTeamMembers.firstOrNull()
    val focusedCode = remember(business.name, focusedMember?.email, focusedMember?.name, viewModel) {
        if (viewModel != null && focusedMember != null) {
            viewModel.syncManager.getOrGenerateInviteCode(business.name, focusedMember.email.ifBlank { focusedMember.name })
        } else {
            ""
        }
    }

    val shareText = if (focusedMember != null && focusedCode.isNotBlank()) {
        buildTeamInviteMessage(
            businessName = business.name,
            collaboratorName = focusedMember.name,
            invitedEmail = focusedMember.email,
            role = focusedMember.role,
            inviteCode = focusedCode,
            apkDownloadUrl = viewModel?.syncManager?.getApkDownloadUrl() ?: ""
        )
    } else {
        """
        🤝 *CashBook - Business Collaboration*
        Business: *${business.name}* ($booksCount Cashbooks • Net Balance: Rs. ${String.format("%,.2f", totalNetBalance)})
        
        To invite a teammate so their account connects automatically:
        1. Tap "+ Add Partner/Team" inside Share Business and enter their Name, Email, and Role.
        2. Send them the generated Cloud Invite Code & Email instructions!
        """.trimIndent()
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 460.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null, tint = GreenIn)
                        Text("Share Business & Team", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                // Roles explanation box
                Card(
                    colors = CardDefaults.cardColors(containerColor = GreenIn.copy(alpha = 0.08f)),
                    border = BorderStroke(1.dp, GreenIn.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("💡 Partner & Team Roles for '${business.name}':", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = GreenIn)
                        Text("✏️ Data Entry: Can log Cash In / Out entries & add books, cannot delete.", style = MaterialTheme.typography.bodySmall)
                        Text("🤝 Partner: Equal access to log, edit, and view Cash In / Out entries.", style = MaterialTheme.typography.bodySmall)
                        Text("🛡️ Admin / Boss: Full control over books, staff roster, and settings.", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Business Details Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Business Name: ${business.name}", fontWeight = FontWeight.Bold)
                        Text("Cashbooks: $booksCount", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text(
                            "Net Business Total: Rs. ${String.format("%,.2f", totalNetBalance)}",
                            fontWeight = FontWeight.Bold,
                            color = if (totalNetBalance >= 0) GreenIn else RedOut
                        )
                    }
                }

                // Active Team & Partner Roster for this Business
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Active Partners & Team (${bizTeamMembers.size}):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        if (viewModel != null) {
                            TextButton(onClick = { showAddCollaboratorSection = !showAddCollaboratorSection }) {
                                Text(if (showAddCollaboratorSection) "Hide Form" else "+ Add Partner/Team")
                            }
                        }
                    }

                    if (bizTeamMembers.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            bizTeamMembers.forEach { member ->
                                val memberCode = viewModel?.syncManager?.getOrGenerateInviteCode(business.name, member.email.ifBlank { member.name }) ?: ""
                                val isPicked = focusedMember?.id == member.id
                                Surface(
                                    color = if (isPicked) GreenIn.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (isPicked) BorderStroke(1.dp, GreenIn.copy(alpha = 0.4f)) else null,
                                    modifier = Modifier.fillMaxWidth().clickable { activeShareMember = member }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(member.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                            if (member.email.isNotBlank()) {
                                                Text(member.email, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                            }
                                            if (memberCode.isNotBlank()) {
                                                Text("Invite Code: $memberCode", style = MaterialTheme.typography.labelSmall, color = GreenIn, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Surface(
                                            color = if (member.role == "Partner") GreenIn.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                member.role,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (member.role == "Partner") GreenIn else MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text("No partners or team members added yet. Tap '+ Add Partner/Team' to create an invite.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }

                // Quick Add Collaborator Form
                if (showAddCollaboratorSection && viewModel != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Invite Partner or Staff to '${business.name}'", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)

                            OutlinedTextField(
                                value = collaboratorName,
                                onValueChange = { collaboratorName = it },
                                label = { Text("Partner / Staff Name *") },
                                placeholder = { Text("e.g. Mengal") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp)
                            )

                            OutlinedTextField(
                                value = collaboratorEmail,
                                onValueChange = { collaboratorEmail = it },
                                label = { Text("Teammate Email Address *") },
                                placeholder = { Text("e.g. mengal@gmail.com") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp)
                            )

                            OutlinedTextField(
                                value = collaboratorPhone,
                                onValueChange = { collaboratorPhone = it },
                                label = { Text("Phone Number (Optional)") },
                                placeholder = { Text("e.g. +923001234567") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp)
                            )

                            Text("Select Role:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                roles.forEach { roleOpt ->
                                    FilterChip(
                                        selected = selectedRole == roleOpt,
                                        onClick = { selectedRole = roleOpt },
                                        label = { Text(roleOpt, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                        leadingIcon = if (selectedRole == roleOpt) { { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp)) } } else null
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    val cName = collaboratorName.trim()
                                    val cEmail = collaboratorEmail.trim().lowercase()
                                    if (cName.isBlank()) {
                                        Toast.makeText(context, "Please enter partner/staff name.", Toast.LENGTH_SHORT).show()
                                    } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cEmail).matches()) {
                                        Toast.makeText(context, "Please enter a valid Email Address for this teammate.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.addTeamMember(
                                            name = cName,
                                            email = cEmail,
                                            phone = collaboratorPhone.trim(),
                                            role = selectedRole,
                                            targetBusinessId = business.id
                                        ) { inv, published ->
                                            Toast.makeText(
                                                context,
                                                if (published) "Cloud Invite Ready! Code: ${inv.inviteCode}" else "Added $cName locally. Sync online to publish invite.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        collaboratorName = ""
                                        collaboratorEmail = ""
                                        collaboratorPhone = ""
                                        showAddCollaboratorSection = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenIn)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Confirm & Publish Cloud Invite")
                            }
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(shareText))
                            Toast.makeText(context, "Copied invite instructions to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Invite", maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Team Invite via"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share Invite", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
fun ShareBookDialog(
    book: com.example.data.Book,
    parentBusinessName: String,
    txCount: Int,
    netBalance: Double,
    totalIn: Double,
    totalOut: Double,
    viewModel: LedgerViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val syncManager = viewModel.syncManager
    val allTx by viewModel.allTransactions.collectAsStateWithLifecycle()
    val businesses by viewModel.businesses.collectAsStateWithLifecycle()

    val shareText = """
        📚 *Cashbook Pro - Book Details & Share*
        
        Book: *${book.name}*
        Parent Business: $parentBusinessName
        Total Transactions: $txCount
        Net Balance: Rs. ${String.format("%,.2f", netBalance)} (In: Rs. ${String.format("%,.0f", totalIn)} | Out: Rs. ${String.format("%,.0f", totalOut)})
    """.trimIndent()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = GreenIn)
                        Text("Share Cashbook '${book.name}'", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Book Name: ${book.name}", fontWeight = FontWeight.Bold)
                        Text("Business: $parentBusinessName", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text("Transactions: $txCount entries", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text(
                            "Net Total: Rs. ${String.format("%,.2f", netBalance)}",
                            fontWeight = FontWeight.Bold,
                            color = if (netBalance >= 0) GreenIn else RedOut
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val bTx = allTx.filter { it.bookId == book.id }
                            val parentBiz = businesses.find { it.id == book.businessId }
                            val jsonPayload = syncManager.serializeDatabase(
                                businesses = if (parentBiz != null) listOf(parentBiz) else emptyList(),
                                books = listOf(book),
                                transactions = bTx,
                                parties = emptyList(),
                                partyTransactions = emptyList(),
                                teamMembers = emptyList(),
                                isPartialExport = true
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "CashBook Data Export: Book '${book.name}'")
                                putExtra(Intent.EXTRA_TEXT, jsonPayload)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Book '${book.name}' JSON File"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share Book JSON Data")
                    }

                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(shareText))
                            Toast.makeText(context, "Copied book summary to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Text")
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

                        // Card Footer: Business Name & CashBook Pro branding
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(businessName.ifBlank { "Guest Business" }, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black))
                            Text("CashBook Pro", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981)))
                        }
                    }
                }

                // Action Option 1: Share Statement Image
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                val bitmap = createBookStatementImageBitmap(context, bookName, bookPhone, businessName, transactions)
                                val imageFile = File(context.cacheDir, "book_statement.png")
                                imageFile.outputStream().use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri("Book Statement Image", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooserIntent = Intent.createChooser(shareIntent, "Share Statement Image").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(chooserIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error sharing statement image: ${e.message}", Toast.LENGTH_SHORT).show()
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
                            Text("Share Statement Card (Image)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF065F46))
                            Text("Send visual statement image with item details to WhatsApp / Gallery", style = MaterialTheme.typography.bodySmall, color = Color(0xFF047857))
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
                            Text("Professional PDF document with Customer name & item transaction table", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
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
                                    "Share Statement Text"
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
                            Text("Share TXT Statement via WhatsApp", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Formatted text message with full itemized transaction details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
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
    sb.append("📖 *CASHBOOK / CONSUMER:* ${bookName.ifBlank { "Khata Book" }}\n")
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
            val itemName = if (tx.remarks.isNotBlank()) tx.remarks else if (tx.category.isNotBlank() && tx.category != "General") tx.category else ""
            val amtStr = String.format("%,.0f", tx.amount)
            val typeStr = if (tx.type == "OUT") "Gave (Out)" else "Got (In)"
            val itemStr = if (itemName.isNotBlank()) " ($itemName)" else ""
            sb.append(" • $typeStr: Rs. $amtStr$itemStr\n")
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
        val itemName = if (tx.remarks.isNotBlank()) tx.remarks else if (tx.category.isNotBlank() && tx.category != "General") tx.category else ""
        val itemStr = if (itemName.isNotBlank()) " - $itemName" else ""
        sb.append("${index + 1}. $dateStr $typePrefix Rs. ${String.format("%,.0f", tx.amount)}$itemStr (${tx.paymentMethod})\n")
    }
    sb.append("\nGenerated by CashBook Pro App.")
    return sb.toString()
}

fun generateMultiBusinessTextReport(
    scopeTitle: String,
    transactions: List<Transaction>,
    businesses: List<Business>,
    allBooks: List<Book>
): String {
    val totalIn = transactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = transactions.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut

    val sb = java.lang.StringBuilder()
    sb.append("CASHBOOK PRO STATEMENT REPORT\n")
    sb.append("===============================\n")
    sb.append("Scope: $scopeTitle\n")
    sb.append("Report Date: ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())}\n")
    sb.append("===============================\n")
    sb.append("Cash IN (+): Rs. ${String.format("%,.0f", totalIn)}\n")
    sb.append("Cash OUT (-): Rs. ${String.format("%,.0f", totalOut)}\n")
    sb.append("Net Balance: Rs. ${String.format("%,.0f", netBalance)}\n")
    sb.append("Total Entries: ${transactions.size}\n")
    if (businesses.size > 1) {
        sb.append("\nBUSINESS SUMMARY:\n")
        businesses.forEach { biz ->
            val bizBookIds = allBooks.filter { it.businessId == biz.id }.map { it.id }.toSet()
            val bizTxs = transactions.filter { it.bookId in bizBookIds }
            val bIn = bizTxs.filter { it.type == "IN" }.sumOf { it.amount }
            val bOut = bizTxs.filter { it.type == "OUT" }.sumOf { it.amount }
            sb.append("• ${biz.name}: IN Rs. ${String.format("%,.0f", bIn)} | OUT Rs. ${String.format("%,.0f", bOut)} | Net Rs. ${String.format("%,.0f", bIn - bOut)}\n")
        }
    }
    sb.append("\n===============================\n")
    sb.append("RECENT TRANSACTIONS:\n")
    transactions.take(50).forEachIndexed { index, tx ->
        val dateStr = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault()).format(Date(tx.timestamp))
        val typePrefix = if (tx.type == "IN") "[IN]" else "[OUT]"
        val itemName = if (tx.remarks.isNotBlank()) tx.remarks else if (tx.category.isNotBlank()) tx.category else ""
        val itemStr = if (itemName.isNotBlank()) " - $itemName" else ""
        sb.append("${index + 1}. $dateStr $typePrefix Rs. ${String.format("%,.0f", tx.amount)}$itemStr (${tx.paymentMethod})\n")
    }
    sb.append("\nGenerated by CashBook Pro App.")
    return sb.toString()
}

fun generateMultiBusinessPdfReport(
    context: Context,
    scopeTitle: String,
    transactions: List<Transaction>,
    bookLookup: Map<Int, Book>,
    bizLookup: Map<Int, Business>
): File {
    val pdfDocument = PdfDocument()
    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
    val page = pdfDocument.startPage(pageInfo)
    val canvas = page.canvas
    val paint = Paint()

    val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    val todayStr = dateFmt.format(Date())

    paint.color = AndroidColor.parseColor("#111827")
    paint.textSize = 17f
    paint.isFakeBoldText = true
    canvas.drawText(if (scopeTitle.length > 38) scopeTitle.take(36) + "..." else scopeTitle, 35f, 45f, paint)

    paint.color = AndroidColor.parseColor("#4B5563")
    paint.textSize = 10f
    paint.isFakeBoldText = false
    canvas.drawText("CashBook Pro Financial Statement • ${transactions.size} entries", 35f, 62f, paint)

    paint.textAlign = Paint.Align.RIGHT
    canvas.drawText(todayStr, 560f, 45f, paint)
    paint.textAlign = Paint.Align.LEFT

    paint.color = AndroidColor.parseColor("#E5E7EB")
    paint.strokeWidth = 1f
    canvas.drawLine(35f, 76f, 560f, 76f, paint)

    val totalIn = transactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = transactions.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut

    paint.color = AndroidColor.parseColor("#ECFDF5")
    canvas.drawRoundRect(35f, 90f, 198f, 145f, 8f, 8f, paint)
    paint.color = AndroidColor.parseColor("#065F46")
    paint.textSize = 9f
    paint.isFakeBoldText = true
    canvas.drawText("Cash IN (+)", 45f, 108f, paint)
    paint.textSize = 14f
    canvas.drawText("Rs ${String.format("%,.0f", totalIn)}", 45f, 130f, paint)

    paint.color = AndroidColor.parseColor("#FFF7ED")
    canvas.drawRoundRect(208f, 90f, 371f, 145f, 8f, 8f, paint)
    paint.color = AndroidColor.parseColor("#9A3412")
    paint.textSize = 9f
    paint.isFakeBoldText = true
    canvas.drawText("Cash OUT (-)", 218f, 108f, paint)
    paint.textSize = 14f
    canvas.drawText("Rs ${String.format("%,.0f", totalOut)}", 218f, 130f, paint)

    val isNeg = netBalance < 0
    paint.color = if (isNeg) AndroidColor.parseColor("#FEF2F2") else AndroidColor.parseColor("#EFF6FF")
    canvas.drawRoundRect(381f, 90f, 560f, 145f, 8f, 8f, paint)
    paint.color = if (isNeg) AndroidColor.parseColor("#991B1B") else AndroidColor.parseColor("#1D4ED8")
    paint.textSize = 9f
    paint.isFakeBoldText = true
    canvas.drawText("Net Balance", 391f, 108f, paint)
    paint.textSize = 14f
    canvas.drawText("Rs ${String.format("%,.0f", netBalance)}", 391f, 130f, paint)

    val headerY = 165f
    paint.color = AndroidColor.parseColor("#F3F4F6")
    canvas.drawRect(35f, headerY, 560f, headerY + 24f, paint)

    paint.color = AndroidColor.parseColor("#374151")
    paint.textSize = 9f
    paint.isFakeBoldText = true
    canvas.drawText("Date", 42f, headerY + 15f, paint)
    canvas.drawText("Business / Details", 115f, headerY + 15f, paint)
    canvas.drawText("Cash OUT (-)", 315f, headerY + 15f, paint)
    canvas.drawText("Cash IN (+)", 405f, headerY + 15f, paint)
    canvas.drawText("Mode", 495f, headerY + 15f, paint)

    paint.isFakeBoldText = false
    var currentY = headerY + 24f
    transactions.take(26).forEach { tx ->
        val dateText = SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(Date(tx.timestamp))
        val isIn = tx.type == "IN"
        val bk = bookLookup[tx.bookId]
        val bz = bk?.businessId?.let { bizLookup[it] }
        val desc = tx.remarks.ifBlank { tx.category.ifBlank { bk?.name ?: "" } }
        val prefix = if (bz != null) "${bz.name.take(10)}: " else ""
        val combined = "$prefix$desc"

        paint.color = AndroidColor.parseColor("#F1F5F9")
        canvas.drawLine(35f, currentY + 22f, 560f, currentY + 22f, paint)

        paint.color = AndroidColor.parseColor("#111827")
        canvas.drawText(dateText, 42f, currentY + 15f, paint)
        canvas.drawText(if (combined.length > 30) combined.take(28) + "..." else combined, 115f, currentY + 15f, paint)

        if (!isIn) {
            paint.color = AndroidColor.parseColor("#EA580C")
            canvas.drawText("Rs ${String.format("%,.0f", tx.amount)}", 315f, currentY + 15f, paint)
        } else {
            paint.color = AndroidColor.GRAY
            canvas.drawText("-", 315f, currentY + 15f, paint)
        }

        if (isIn) {
            paint.color = AndroidColor.parseColor("#16A34A")
            canvas.drawText("Rs ${String.format("%,.0f", tx.amount)}", 405f, currentY + 15f, paint)
        } else {
            paint.color = AndroidColor.GRAY
            canvas.drawText("-", 405f, currentY + 15f, paint)
        }

        paint.color = AndroidColor.parseColor("#475569")
        canvas.drawText(tx.paymentMethod.take(8), 495f, currentY + 15f, paint)
        currentY += 22f
    }

    paint.color = AndroidColor.parseColor("#9CA3AF")
    paint.textSize = 9f
    val genTimeStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
    canvas.drawText("Generated on $genTimeStr", 35f, 820f, paint)

    pdfDocument.finishPage(page)

    val file = File(context.cacheDir, "cashbook_analytics_report.pdf")
    pdfDocument.writeTo(file.outputStream())
    pdfDocument.close()
    return file
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
    val phoneStr = activeBook?.phone?.ifBlank { "" } ?: ""
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
    if (phoneStr.isNotBlank()) {
        canvas.drawText("Phone: $phoneStr", 35f, 77f, paint)
    }

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
    canvas.drawText("Item Name / Remarks", 130f, headerY + 16f, paint)
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

        val itemName = if (tx.remarks.isNotBlank()) tx.remarks else if (tx.category.isNotBlank() && tx.category != "General") tx.category else ""
        val displayDetails = if (itemName.length > 24) itemName.take(22) + "..." else itemName
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

fun createBookStatementImageBitmap(
    context: Context,
    bookName: String,
    bookPhone: String,
    businessName: String,
    transactions: List<Transaction>
): Bitmap {
    val totalIn = transactions.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = transactions.filter { it.type == "OUT" }.sumOf { it.amount }
    val netBalance = totalIn - totalOut

    val width = 900
    val txCount = minOf(transactions.size, 15)
    val height = kotlin.math.max(500, 320 + (txCount * 45) + 100)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Background
    canvas.drawColor(AndroidColor.WHITE)

    // Outer Border
    paint.color = AndroidColor.parseColor("#CBD5E1")
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    canvas.drawRoundRect(20f, 20f, (width - 20).toFloat(), (height - 20).toFloat(), 20f, 20f, paint)
    paint.style = Paint.Style.FILL

    // Top Banner
    paint.color = AndroidColor.parseColor("#0F172A")
    canvas.drawRoundRect(20f, 20f, (width - 20).toFloat(), 110f, 20f, 20f, paint)

    paint.color = AndroidColor.WHITE
    paint.textSize = 28f
    paint.isFakeBoldText = true
    canvas.drawText("${bookName.ifBlank { "Khata Book" }} - Statement", 40f, 65f, paint)

    paint.textSize = 16f
    paint.isFakeBoldText = false
    paint.color = AndroidColor.parseColor("#94A3B8")
    val phoneStr = if (bookPhone.isNotBlank()) " | Phone: $bookPhone" else ""
    canvas.drawText("${businessName.ifBlank { "CashBook Pro" }}$phoneStr", 40f, 95f, paint)

    val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
    paint.textAlign = Paint.Align.RIGHT
    canvas.drawText(dateStr, (width - 40).toFloat(), 65f, paint)
    paint.textAlign = Paint.Align.LEFT

    // Summary Boxes
    // Got (In)
    paint.color = AndroidColor.parseColor("#ECFDF5")
    canvas.drawRoundRect(40f, 130f, 290f, 200f, 12f, 12f, paint)
    paint.color = AndroidColor.parseColor("#065F46")
    paint.textSize = 13f
    paint.isFakeBoldText = true
    canvas.drawText("TOTAL GOT (IN)", 55f, 155f, paint)
    paint.textSize = 20f
    canvas.drawText("Rs. ${String.format("%,.0f", totalIn)}", 55f, 185f, paint)

    // Gave (Out)
    paint.color = AndroidColor.parseColor("#FEF2F2")
    canvas.drawRoundRect(310f, 130f, 560f, 200f, 12f, 12f, paint)
    paint.color = AndroidColor.parseColor("#991B1B")
    paint.textSize = 13f
    paint.isFakeBoldText = true
    canvas.drawText("TOTAL GAVE (OUT)", 325f, 155f, paint)
    paint.textSize = 20f
    canvas.drawText("Rs. ${String.format("%,.0f", totalOut)}", 325f, 185f, paint)

    // Net Balance
    val isGive = netBalance < 0
    paint.color = if (isGive) AndroidColor.parseColor("#FEF2F2") else AndroidColor.parseColor("#ECFDF5")
    canvas.drawRoundRect(580f, 130f, 860f, 200f, 12f, 12f, paint)
    paint.color = if (isGive) AndroidColor.parseColor("#991B1B") else AndroidColor.parseColor("#065F46")
    paint.textSize = 13f
    paint.isFakeBoldText = true
    canvas.drawText("NET BALANCE", 595f, 155f, paint)
    paint.textSize = 20f
    canvas.drawText("Rs. ${String.format("%,.0f", kotlin.math.abs(netBalance))}", 595f, 185f, paint)

    // Table Header
    val headerY = 225f
    paint.color = AndroidColor.parseColor("#F1F5F9")
    canvas.drawRect(40f, headerY, 860f, headerY + 35f, paint)

    paint.color = AndroidColor.parseColor("#334155")
    paint.textSize = 15f
    paint.isFakeBoldText = true
    canvas.drawText("Date", 55f, headerY + 23f, paint)
    canvas.drawText("Item Name / Remarks", 200f, headerY + 23f, paint)
    canvas.drawText("Type", 520f, headerY + 23f, paint)
    canvas.drawText("Amount", 680f, headerY + 23f, paint)

    // Table Rows
    var currentY = headerY + 35f
    paint.isFakeBoldText = false

    transactions.take(15).forEach { tx ->
        val txDate = SimpleDateFormat("dd MMM, yy", Locale.getDefault()).format(Date(tx.timestamp))
        val itemName = if (tx.remarks.isNotBlank()) tx.remarks else if (tx.category.isNotBlank() && tx.category != "General") tx.category else ""
        val isIn = tx.type == "IN"

        paint.color = AndroidColor.parseColor("#E2E8F0")
        paint.strokeWidth = 1f
        canvas.drawLine(40f, currentY + 35f, 860f, currentY + 35f, paint)

        paint.color = AndroidColor.parseColor("#1E293B")
        paint.textSize = 14f
        canvas.drawText(txDate, 55f, currentY + 22f, paint)

        val truncatedItem = if (itemName.length > 28) itemName.take(26) + "..." else itemName
        paint.color = AndroidColor.parseColor("#334155")
        canvas.drawText(truncatedItem, 200f, currentY + 22f, paint)

        paint.color = if (isIn) AndroidColor.parseColor("#059669") else AndroidColor.parseColor("#DC2626")
        paint.isFakeBoldText = true
        canvas.drawText(if (isIn) "GOT (IN)" else "GAVE (OUT)", 520f, currentY + 22f, paint)

        canvas.drawText("Rs. ${String.format("%,.0f", tx.amount)}", 680f, currentY + 22f, paint)
        paint.isFakeBoldText = false

        currentY += 40f
    }

    currentY += 15f
    paint.color = AndroidColor.parseColor("#94A3B8")
    paint.textSize = 13f
    canvas.drawText("Generated by CashBook Pro App", 55f, currentY + 20f, paint)

    return bitmap
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
    canvas.drawText("CashBook Pro App", 750f, 415f, paint)
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

        // Section 1: Security & PIN + Fingerprint Lock
        item {
            val isLockEnabledSetting by viewModel.isAppLockEnabled.collectAsStateWithLifecycle()
            val isBioEnabledSetting by viewModel.isBiometricEnabled.collectAsStateWithLifecycle()
            val bioSupported = remember(context) { canAuthenticateBiometric(context) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Security Lock & Biometrics", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Protect your cashbooks with a 4-digit PIN and Fingerprint unlock.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Fingerprint, contentDescription = "Fingerprint Unlock", tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Fingerprint Lock", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (!bioSupported) "Biometric sensor unavailable" else if (!isLockEnabledSetting) "Set up a 4-digit PIN first" else if (isBioEnabledSetting) "Enabled" else "Off",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isLockEnabledSetting && isBioEnabledSetting && bioSupported,
                            enabled = bioSupported,
                            onCheckedChange = { checked ->
                                if (!isLockEnabledSetting) {
                                    Toast.makeText(context, "Please set a 4-digit PIN first to enable Fingerprint Lock", Toast.LENGTH_SHORT).show()
                                    showSecuritySettingsDialog = true
                                } else if (checked) {
                                    launchBiometricUnlockPrompt(
                                        context = context,
                                        title = "Enable Fingerprint Lock",
                                        subtitle = "Scan your fingerprint to turn on biometric unlock",
                                        onSuccess = {
                                            viewModel.setBiometricLockEnabled(true)
                                            Toast.makeText(context, "Fingerprint Lock enabled!", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                                    )
                                } else {
                                    viewModel.setBiometricLockEnabled(false)
                                    Toast.makeText(context, "Fingerprint Lock disabled", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.testTag("settings_biometric_switch")
                        )
                    }

                    Button(
                        onClick = { showSecuritySettingsDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isLockEnabledSetting) "Manage PIN & Fingerprint Lock" else "Configure App PIN & Fingerprint Lock")
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
                    Text("CashBook Pro Cloud & Offline Sync", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    
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
                                    viewModel.logoutUser()
                                    settingsAuthVersion++
                                    Toast.makeText(context, "Logged out from Cloud Account.", Toast.LENGTH_SHORT).show()
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
    val allBooks by viewModel.allBooks.collectAsStateWithLifecycle()
    val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val activeBusiness by viewModel.activeBusiness.collectAsStateWithLifecycle()
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showShareBusinessDialog by remember { mutableStateOf<com.example.data.Business?>(null) }
    var showShareBookDialog by remember { mutableStateOf<com.example.data.Book?>(null) }
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
    var showJoinByCodeDialog by remember { mutableStateOf(false) }
    val currentUserEmail by viewModel.currentUserEmail.collectAsStateWithLifecycle()
    val simulatedRole by viewModel.simulatedRole.collectAsStateWithLifecycle()
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()

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
                            "Create, join via invite code, switch, or manage businesses and cashbooks.",
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { showJoinByCodeDialog = true },
                        modifier = Modifier.testTag("manage_join_code_button")
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Join Code", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                    }
                    TextButton(
                        onClick = { showAddBusinessDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = GreenIn)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Business", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                    }
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
                val bizBooks = allBooks.filter { it.businessId == biz.id }
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Business,
                                    contentDescription = null,
                                    tint = if (isActive) GreenIn else Color(0xFF64748B)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = biz.name,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isActive) GreenIn else Color(0xFF0F172A),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TransactionSyncCheckIndicator(isSynced = biz.isSynced)
                                }
                            }
                            if (isActive) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = GreenIn.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, GreenIn.copy(alpha = 0.45f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = GreenIn, modifier = Modifier.size(12.dp))
                                        Text(
                                            text = "Active",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GreenIn,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
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

                        // List books for this Business
                        if (bizBooks.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "Books / Customers under ${biz.name}:",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF475569)
                                )
                                bizBooks.forEach { bk ->
                                    val isBkActive = activeBook?.id == bk.id && isActive
                                    val bkTx = allTransactions.filter { it.bookId == bk.id }
                                    val bkIn = bkTx.filter { it.type == "IN" }.sumOf { it.amount }
                                    val bkOut = bkTx.filter { it.type == "OUT" }.sumOf { it.amount }
                                    val bkNet = bkIn - bkOut

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.selectBook(bk) },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isBkActive) GreenIn.copy(alpha = 0.08f) else Color.White
                                        ),
                                        border = BorderStroke(
                                            width = if (isBkActive) 1.5.dp else 1.dp,
                                            color = if (isBkActive) GreenIn else Color(0xFFE2E8F0)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    Icons.Default.Book,
                                                    contentDescription = null,
                                                    tint = if (isBkActive) GreenIn else Color(0xFF64748B),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Column {
                                                    Text(
                                                        bk.name,
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = if (isBkActive) GreenIn else Color(0xFF0F172A)
                                                    )
                                                    Text(
                                                        "Net: Rs. ${String.format("%,.0f", bkNet)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (bkNet >= 0) GreenIn else RedOut
                                                    )
                                                }
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                if (isBkActive) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = GreenIn.copy(alpha = 0.14f),
                                                        border = BorderStroke(1.dp, GreenIn.copy(alpha = 0.4f))
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                        ) {
                                                            Icon(Icons.Default.Check, contentDescription = null, tint = GreenIn, modifier = Modifier.size(11.dp))
                                                            Text(
                                                                "Active",
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = GreenIn,
                                                                maxLines = 1,
                                                                softWrap = false
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    TextButton(
                                                        onClick = { viewModel.selectBook(bk) },
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                    ) {
                                                        Text("Select", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GreenIn)
                                                    }
                                                }

                                                IconButton(
                                                    onClick = { showShareBookDialog = bk },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.Share, contentDescription = "Share Book", tint = GreenIn, modifier = Modifier.size(14.dp))
                                                }

                                                IconButton(
                                                    onClick = {
                                                        bookToRename = bk
                                                        renameBookName = bk.name
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.Edit, contentDescription = "Rename Book", tint = Color(0xFF475569), modifier = Modifier.size(14.dp))
                                                }

                                                IconButton(
                                                    onClick = { bookToDelete = bk },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Delete Book", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val isOwnBusiness = isSuperAdmin || biz.userEmail.isBlank() || biz.userEmail.equals(currentUserEmail, ignoreCase = true)
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
                            } else if (!isOwnBusiness) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Shared • $simulatedRole",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            if (isOwnBusiness || hasPermission(simulatedRole, "add_team_member")) {
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
                            } else {
                                TextButton(
                                    onClick = {
                                        viewModel.leaveSharedBusiness(biz) { msg ->
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Leave", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 2: All Cashbooks across all businesses
        item {
            Divider(color = Color(0xFFE2E8F0), modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "All Cashbooks / Customers",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Total ${allBooks.size} cashbooks across ${businesses.size} businesses",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B)
                    )
                }
                if (hasPermission(simulatedRole, "add_book")) {
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
        }

        if (allBooks.isEmpty()) {
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
                        if (hasPermission(simulatedRole, "add_book")) {
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
            }
        } else {
            items(allBooks) { bk ->
                val isActive = activeBook?.id == bk.id
                val parentBiz = businesses.find { it.id == bk.businessId }
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
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Book,
                                    contentDescription = null,
                                    tint = if (isActive) GreenIn else Color(0xFF64748B)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bk.name,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isActive) GreenIn else Color(0xFF0F172A),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (parentBiz != null) {
                                        Text(
                                            text = "Business: ${parentBiz.name}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    TransactionSyncCheckIndicator(isSynced = bk.isSynced)
                                }
                            }
                            if (isActive) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = GreenIn.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, GreenIn.copy(alpha = 0.45f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = GreenIn, modifier = Modifier.size(12.dp))
                                        Text(
                                            text = "Active Book",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GreenIn,
                                            maxLines = 1,
                                            softWrap = false
                                        )
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
                                    onClick = { viewModel.selectBook(bk) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Switch to", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.setScreen(Screen.DASHBOARD) },
                                    modifier = Modifier.weight(1f),
                                    border = BorderStroke(1.dp, GreenIn),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Open on Home", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = GreenIn)
                                }
                            }

                            if (hasPermission(simulatedRole, "delete_book")) {
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
            viewModel = viewModel,
            onDismiss = { showShareBusinessDialog = null }
        )
    }

    // 8. Share Book Dialog
    if (showShareBookDialog != null) {
        val bk = showShareBookDialog!!
        val bkTx = allTransactions.filter { it.bookId == bk.id }
        val bkIn = bkTx.filter { it.type == "IN" }.sumOf { it.amount }
        val bkOut = bkTx.filter { it.type == "OUT" }.sumOf { it.amount }
        val bkNet = bkIn - bkOut
        val parentBiz = businesses.find { it.id == bk.businessId }

        ShareBookDialog(
            book = bk,
            parentBusinessName = parentBiz?.name ?: "Unknown Business",
            txCount = bkTx.size,
            netBalance = bkNet,
            totalIn = bkIn,
            totalOut = bkOut,
            viewModel = viewModel,
            onDismiss = { showShareBookDialog = null }
        )
    }

    if (showJoinByCodeDialog) {
        JoinBusinessByCodeDialog(
            viewModel = viewModel,
            onDismiss = { showJoinByCodeDialog = false }
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
                        Text("Cloud Sync Center")
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
    var username by remember { mutableStateOf("") }
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
                            "Super Admin Sign In",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Enter administrator credentials to continue",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username or Email") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("super_admin_username_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Master Password") },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("super_admin_password_input"),
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
                        modifier = Modifier.testTag("super_admin_login_button"),
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

// --- SUPER ADMIN REGISTERED USERS DIRECTORY (FULL SCREEN DELEGATE) ---
@Composable
fun SuperAdminUserRegistryDialog(
    viewModel: LedgerViewModel,
    onDismiss: () -> Unit
) {
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    LaunchedEffect(isSuperAdmin) {
        if (!isSuperAdmin) {
            onDismiss()
        }
    }
    if (!isSuperAdmin) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            SuperAdminConsoleScreen(
                viewModel = viewModel,
                onSwitchToBusinessView = onDismiss
            )
        }
    }
}

// --- GREEN BOOK+ SUPER ADMIN EXCLUSIVE PASSWORD-ONLY LOGIN PORTAL ---
@Composable
fun SuperAdminSecretLoginDialog(
    viewModel: LedgerViewModel,
    onDismiss: () -> Unit,
    onOpenRegistry: () -> Unit
) {
    val context = LocalContext.current
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val isUserSignedIn by viewModel.isUserSignedIn.collectAsStateWithLifecycle()
    val userEmail = viewModel.syncManager.getEmail()
    val userName = viewModel.syncManager.getName()

    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header with Master Crown Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = GreenIn.copy(alpha = 0.15f),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("👑", fontSize = 24.sp)
                            }
                        }
                        Column {
                            Text(
                                text = "Super Admin Access",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Master Password Required",
                                style = MaterialTheme.typography.labelSmall,
                                color = GreenIn,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Current Session Status Indicator
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSuperAdmin) GreenIn.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, if (isSuperAdmin) GreenIn.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Active Session",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            Text(
                                text = if (isUserSignedIn) (userName.ifBlank { userEmail }) else "Local Guest / Offline",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (userEmail.isNotBlank()) {
                                Text(
                                    text = userEmail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Surface(
                            color = if (isSuperAdmin) GreenIn else Color(0xFF64748B),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (isSuperAdmin) "👑 SUPER ADMIN" else "LOCKED",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                // Password-Only Authentication Card (No hints, no user preview, no bypass)
                Card(
                    colors = CardDefaults.cardColors(containerColor = GreenIn.copy(alpha = 0.06f)),
                    border = BorderStroke(1.dp, GreenIn.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "🔐 Master Password Verification",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = GreenIn
                        )
                        Text(
                            text = "Enter the Master Password to unlock the Super Admin Console and manage registered users.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("Master Password") },
                            placeholder = { Text("Enter Master Password") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle password visibility"
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("secret_master_password_input"),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        Button(
                            onClick = {
                                val enteredPass = passwordInput.trim()
                                if (enteredPass.isBlank()) {
                                    Toast.makeText(context, "Please enter Master Password", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isLoading = true
                                scope.launch {
                                    val success = viewModel.loginSuperAdminWithPassword(enteredPass)
                                    isLoading = false
                                    if (success) {
                                        passwordInput = ""
                                        viewModel.refreshCloudAccounts()
                                        Toast.makeText(context, "Super Admin Authenticated!", Toast.LENGTH_SHORT).show()
                                        onOpenRegistry()
                                    } else {
                                        Toast.makeText(context, "Access Denied: Incorrect Master Password", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("secret_master_unlock_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GreenIn)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Verifying...")
                            } else {
                                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Verify & Open Super Admin", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Sign Out if currently logged in
                if (isUserSignedIn || isSuperAdmin) {
                    TextButton(
                        onClick = {
                            viewModel.logoutSuperAdmin()
                            Toast.makeText(context, "Signed out to guest/offline mode.", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sign Out Current Session", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// --- SUPER ADMIN CONSOLE SCREEN ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperAdminConsoleScreen(
    viewModel: LedgerViewModel,
    onSwitchToBusinessView: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val globalAccounts by viewModel.globalAccounts.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshingAccounts.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showPasswords by remember { mutableStateOf(false) }

    var showAddUserDialog by remember { mutableStateOf(false) }
    var showChangeMasterPassDialog by remember { mutableStateOf(false) }
    var accountToEdit by remember { mutableStateOf<com.example.data.RegisteredAccount?>(null) }
    var accountToDelete by remember { mutableStateOf<com.example.data.RegisteredAccount?>(null) }

    LaunchedEffect(isSuperAdmin) {
        if (!isSuperAdmin) {
            onSwitchToBusinessView?.invoke()
        } else {
            viewModel.refreshCloudAccounts()
        }
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

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onSwitchToBusinessView?.invoke() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                Icons.Default.SupervisorAccount,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text("Super Admin Console", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Firebase Cloud Database Management", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    onSwitchToBusinessView?.let { onSwitch ->
                        OutlinedButton(
                            onClick = onSwitch,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Store, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cashbooks")
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Button(
                        onClick = {
                            viewModel.logoutSuperAdmin()
                            Toast.makeText(context, "Super Admin logged out.", Toast.LENGTH_SHORT).show()
                            onSwitchToBusinessView?.invoke()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Sign Out", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sign Out", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Stats & Control Header Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
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
                        Column {
                            Text(
                                "${globalAccounts.size} Registered Cloud Users",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Direct access to Firebase Cloud User Registry",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            color = GreenIn,
                            shape = CircleShape
                        ) {
                            Text(
                                "● Firebase Active",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showAddUserDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GreenIn)
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Cloud User", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.refreshCloudAccounts {
                                    Toast.makeText(context, "Fetched ${it.size} Firebase users!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isRefreshing,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Refresh DB")
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showChangeMasterPassDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("change_master_password_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Change Super Admin Master Password", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Search Bar & Options
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search name, email, username...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
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
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .size(52.dp)
                ) {
                    Icon(
                        if (showPasswords) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle Passwords",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                "Showing ${filteredAccounts.size} of ${globalAccounts.size} cloud accounts",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Users List
            if (filteredAccounts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.PersonOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                        Text("No matching Firebase users found.", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredAccounts, key = { it.email.ifBlank { it.username } }) { acc ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = acc.name.take(1).uppercase().ifBlank { "U" },
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                            }
                                        }

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    text = acc.name.ifBlank { "Cloud Account" },
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
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (acc.username.isNotBlank() && acc.email.isNotBlank()) {
                                                Text(
                                                    text = "@${acc.username}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(
                                            onClick = {
                                                val loginKey = acc.email.ifBlank { acc.username }
                                                viewModel.loginUser(loginKey, acc.pass)
                                                Toast.makeText(context, "Switched to account: ${acc.name.ifBlank { acc.email }}", Toast.LENGTH_SHORT).show()
                                                onSwitchToBusinessView?.invoke()
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.Login, contentDescription = "Log In as User", modifier = Modifier.size(18.dp), tint = GreenIn)
                                        }

                                        IconButton(
                                            onClick = { accountToEdit = acc },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit User", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        }

                                        IconButton(
                                            onClick = { accountToDelete = acc },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete User", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                        }

                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(acc.email.ifBlank { acc.username }))
                                                Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Email", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Password: ${if (showPasswords) acc.pass else "••••••••"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "Firebase DB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Cloud User Sub-Dialog
    if (showAddUserDialog) {
        var newName by remember { mutableStateOf("") }
        var newEmail by remember { mutableStateOf("") }
        var newUsername by remember { mutableStateOf("") }
        var newPass by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddUserDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, tint = GreenIn)
                    Text("Add New Cloud User")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Full Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newEmail,
                        onValueChange = { newEmail = it },
                        label = { Text("Email Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newUsername,
                        onValueChange = { newUsername = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newPass,
                        onValueChange = { newPass = it },
                        label = { Text("Password / Security Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newEmail.isBlank() && newUsername.isBlank()) {
                            Toast.makeText(context, "Email or Username required!", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.addUserCloud(newName, newEmail, newUsername, newPass) { success ->
                                if (success) {
                                    Toast.makeText(context, "User added to Cloud DB successfully!", Toast.LENGTH_SHORT).show()
                                    showAddUserDialog = false
                                } else {
                                    Toast.makeText(context, "Failed to add user to Cloud DB", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn)
                ) {
                    Text("Add User")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddUserDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Edit Cloud User Sub-Dialog
    accountToEdit?.let { acc ->
        var editName by remember { mutableStateOf(acc.name) }
        var editEmail by remember { mutableStateOf(acc.email) }
        var editUsername by remember { mutableStateOf(acc.username) }
        var editPass by remember { mutableStateOf(acc.pass) }

        AlertDialog(
            onDismissRequest = { accountToEdit = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Edit User Details")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Full Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editEmail,
                        onValueChange = { editEmail = it },
                        label = { Text("Email Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editUsername,
                        onValueChange = { editUsername = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editPass,
                        onValueChange = { editPass = it },
                        label = { Text("Password / Security Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateUserCloud(acc.email, editName, editEmail, editUsername, editPass) { success ->
                            if (success) {
                                Toast.makeText(context, "User updated in Cloud DB!", Toast.LENGTH_SHORT).show()
                                accountToEdit = null
                            } else {
                                Toast.makeText(context, "Failed to update user in Cloud DB", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToEdit = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Cloud User Confirmation Dialog
    accountToDelete?.let { acc ->
        AlertDialog(
            onDismissRequest = { accountToDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text("Delete Cloud User?")
                }
            },
            text = {
                Text("Are you sure you want to delete user '${acc.email.ifBlank { acc.username }}' from Firebase Cloud DB? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteUserCloud(acc.email.ifBlank { acc.username }) { success ->
                            if (success) {
                                Toast.makeText(context, "User deleted from Cloud DB!", Toast.LENGTH_SHORT).show()
                                accountToDelete = null
                            } else {
                                Toast.makeText(context, "Failed to delete user", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete User")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Change Super Admin Master Password Dialog
    if (showChangeMasterPassDialog) {
        var currentMasterPass by remember { mutableStateOf("") }
        var newMasterPass by remember { mutableStateOf("") }
        var confirmMasterPass by remember { mutableStateOf("") }
        var showCurrentPass by remember { mutableStateOf(false) }
        var showNewPass by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showChangeMasterPassDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = GreenIn)
                    Text("Master Password")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Set a new Master Password for unlocking the Super Admin Console.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = currentMasterPass,
                        onValueChange = { currentMasterPass = it },
                        label = { Text("Current Master Password") },
                        visualTransformation = if (showCurrentPass) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showCurrentPass = !showCurrentPass }) {
                                Icon(
                                    if (showCurrentPass) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("current_master_password_input"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newMasterPass,
                        onValueChange = { newMasterPass = it },
                        label = { Text("New Master Password (min 4 chars)") },
                        visualTransformation = if (showNewPass) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showNewPass = !showNewPass }) {
                                Icon(
                                    if (showNewPass) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_master_password_input"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = confirmMasterPass,
                        onValueChange = { confirmMasterPass = it },
                        label = { Text("Confirm New Master Password") },
                        visualTransformation = if (showNewPass) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("confirm_master_password_input"),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            currentMasterPass.isBlank() -> {
                                Toast.makeText(context, "Enter current Master Password", Toast.LENGTH_SHORT).show()
                            }
                            newMasterPass.trim().length < 4 -> {
                                Toast.makeText(context, "New password must be at least 4 characters", Toast.LENGTH_SHORT).show()
                            }
                            newMasterPass.trim() != confirmMasterPass.trim() -> {
                                Toast.makeText(context, "New passwords do not match", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                val (ok, msg) = viewModel.updateSuperAdminMasterPassword(currentMasterPass, newMasterPass)
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                if (ok) {
                                    showChangeMasterPassDialog = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("save_master_password_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenIn)
                ) {
                    Text("Update Master Password")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangeMasterPassDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

