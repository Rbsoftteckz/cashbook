package com.example.data

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleDriveSyncManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("google_drive_sync_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var detectedClientId: String = DEFAULT_CLIENT_ID
    private var detectedRedirectUri: String = "https://localhost"

    init {
        try {
            val jsonString = context.assets.open("firebase-applet-config.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(jsonString)
            val oAuthClientId = obj.optString("oAuthClientId", "")
            if (oAuthClientId.isNotBlank()) {
                detectedClientId = oAuthClientId
            }
            val projectId = obj.optString("projectId", "")
            if (projectId.isNotBlank()) {
                // Every Google OAuth Client ID automatically created by Firebase has 
                // https://<projectId>.firebaseapp.com/__/auth/handler pre-registered as an Authorized Redirect URI
                // in the Google Developer Console. Using this guarantees zero redirect_uri_mismatch errors.
                detectedRedirectUri = "https://$projectId.firebaseapp.com/__/auth/handler"
            } else {
                detectedRedirectUri = "https://localhost"
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Failed to load firebase-applet-config.json from assets", e)
            detectedRedirectUri = "https://localhost"
        }

        val savedUri = prefs.getString("custom_redirect_uri", "")
        if (savedUri == "https://localhost/oauth2redirect" || savedUri == "https://localhost" || savedUri?.contains("asia-east1.run.app") == true) {
            prefs.edit().remove("custom_redirect_uri").apply()
        }
    }

    companion object {
        // Default Client ID for AI Studio Demo (users can also supply their own client ID in settings)
        val DEFAULT_CLIENT_ID: String = if (com.example.BuildConfig.OAUTH_CLIENT_ID.isNotBlank() && !com.example.BuildConfig.OAUTH_CLIENT_ID.startsWith("MY_")) {
            com.example.BuildConfig.OAUTH_CLIENT_ID
        } else {
            "755700558600-jl4pipc2klikiac22ivk8s3qvn0pjtc7.apps.googleusercontent.com"
        }
        const val REDIRECT_URI = "https://localhost/oauth2redirect"
    }

    fun getClientId(): String {
        val saved = prefs.getString("custom_client_id", "")
        return if (!saved.isNullOrBlank()) saved else detectedClientId
    }

    fun saveClientId(clientId: String) {
        prefs.edit().putString("custom_client_id", clientId).apply()
    }

    fun getRedirectUri(): String {
        val saved = prefs.getString("custom_redirect_uri", "")
        return if (!saved.isNullOrBlank() && saved != "https://localhost/oauth2redirect" && saved != "https://localhost") saved else detectedRedirectUri
    }

    fun saveRedirectUri(uri: String) {
        prefs.edit().putString("custom_redirect_uri", uri).apply()
    }

    fun getApkDownloadUrl(): String {
        return prefs.getString("custom_apk_download_url", "") ?: ""
    }

    fun saveApkDownloadUrl(url: String) {
        prefs.edit().putString("custom_apk_download_url", url).apply()
    }

    // --- SharedPreferences Auth Storage ---

    fun saveAccessToken(token: String, email: String = "", name: String = "", avatarUrl: String = "") {
        val editor = prefs.edit()
            .putString("access_token", token)
            .putLong("token_saved_time", System.currentTimeMillis())
        if (email.isNotBlank()) editor.putString("google_email", email)
        if (name.isNotBlank()) editor.putString("google_name", name)
        if (avatarUrl.isNotBlank()) editor.putString("google_avatar_url", avatarUrl)
        editor.apply()
    }

    fun getAccessToken(): String? {
        val token = prefs.getString("access_token", null)
        val savedTime = prefs.getLong("token_saved_time", 0)
        // Token expires after 1 hour (3600000 ms)
        if (token != null && System.currentTimeMillis() - savedTime > 3600000) {
            clearGoogleAuth()
            return null
        }
        return token
    }

    fun hasGoogleDriveToken(): Boolean = getAccessToken() != null
    fun isGoogleDriveConnected(): Boolean = getAccessToken() != null

    fun getGoogleEmail(): String = if (hasGoogleDriveToken()) prefs.getString("google_email", "") ?: "" else ""
    fun getGoogleName(): String = if (hasGoogleDriveToken()) prefs.getString("google_name", "") ?: "" else ""

    fun getEmail(): String {
        val googleEmail = getGoogleEmail()
        if (googleEmail.isNotBlank()) return googleEmail
        return prefs.getString("user_email", "") ?: prefs.getString("email", "") ?: ""
    }

    fun getName(): String {
        val googleName = getGoogleName()
        if (googleName.isNotBlank()) return googleName
        return prefs.getString("user_name", "") ?: prefs.getString("name", "") ?: ""
    }

    fun getAvatarUrl(): String = prefs.getString("google_avatar_url", "") ?: ""

    fun clearGoogleAuth() {
        prefs.edit()
            .remove("access_token")
            .remove("google_email")
            .remove("google_name")
            .remove("google_avatar_url")
            .remove("token_saved_time")
            .apply()
    }

    fun clearAuth() {
        clearGoogleAuth()
    }

    fun isUserSignedIn(): Boolean = hasGoogleDriveToken()

    // --- User Account Registration & Authentication ---
    fun hasRegisteredAccount(): Boolean {
        val username = prefs.getString("username", "")
        return !username.isNullOrBlank()
    }

    fun isUserLoggedIn(): Boolean {
        return prefs.getBoolean("is_user_logged_in", false) || prefs.getBoolean("is_super_admin", false)
    }

    fun isSuperAdminLoggedIn(): Boolean = isUserLoggedIn()

    fun registerUser(name: String, email: String, username: String, pass: String) {
        val trimmedName = name.trim()
        val trimmedEmail = email.trim()
        val trimmedUser = username.trim().ifBlank {
            if (trimmedEmail.contains("@")) trimmedEmail.substringBefore("@")
            else if (trimmedName.isNotBlank()) trimmedName.replace(" ", "").lowercase()
            else "user"
        }
        val finalEmail = if (trimmedEmail.isBlank()) "$trimmedUser@cashbook.local" else trimmedEmail
        val finalName = if (trimmedName.isBlank()) trimmedUser else trimmedName

        prefs.edit()
            .putString("user_name", finalName)
            .putString("user_email", finalEmail)
            .putString("username", trimmedUser)
            .putString("user_password", pass.trim())
            .putBoolean("is_user_logged_in", true)
            .putBoolean("is_super_admin", true)
            .apply()
    }

    fun registerCustomUser(name: String, email: String, username: String, pass: String) = registerUser(name, email, username, pass)

    fun loginUser(userOrEmail: String, pass: String): Boolean {
        val trimmedUser = userOrEmail.trim().lowercase()
        val trimmedPass = pass.trim()

        val savedUser = (prefs.getString("username", "") ?: "").trim()
        val savedEmail = (prefs.getString("user_email", "") ?: "").trim()
        val savedPass = (prefs.getString("user_password", "") ?: "").trim()
        val savedName = (prefs.getString("user_name", "User") ?: "User").trim()

        // Flexible matching for custom registered account
        val matchesUser = savedUser.isNotBlank() && trimmedUser == savedUser.lowercase()
        val matchesEmail = savedEmail.isNotBlank() && (trimmedUser == savedEmail.lowercase() || (savedEmail.contains("@") && trimmedUser == savedEmail.substringBefore("@").lowercase()))
        val matchesName = savedName.isNotBlank() && trimmedUser == savedName.lowercase()

        if (matchesUser || matchesEmail || matchesName) {
            if (savedPass.isBlank() || trimmedPass == savedPass) {
                prefs.edit()
                    .putBoolean("is_user_logged_in", true)
                    .putBoolean("is_super_admin", true)
                    .putString("user_email", savedEmail.ifBlank { "$savedUser@cashbook.local" })
                    .putString("user_name", savedName.ifBlank { savedUser })
                    .apply()
                return true
            }
        }

        // Fallback static account if no custom user exists yet or superadmin login
        if ((trimmedUser == "superadmin" || trimmedUser == "admin@cashbook.com" || trimmedUser == "admin") &&
            (trimmedPass == "superadmin123" || trimmedPass == "admin123")) {
            prefs.edit()
                .putBoolean("is_user_logged_in", true)
                .putBoolean("is_super_admin", true)
                .putString("user_email", "admin@cashbook.com")
                .putString("user_name", "Admin")
                .apply()
            return true
        }

        return false
    }

    fun loginSuperAdmin(user: String, pass: String): Boolean = loginUser(user, pass)

    fun logoutUser() {
        clearGoogleAuth()
        prefs.edit()
            .putBoolean("is_user_logged_in", false)
            .putBoolean("is_super_admin", false)
            .apply()
    }

    fun logoutSuperAdmin() = logoutUser()

    fun checkEmailExists(email: String, teamMembers: List<TeamMember> = emptyList()): Boolean {
        val target = email.trim().lowercase()
        if (target.isBlank()) return false
        val savedEmail = (prefs.getString("user_email", "") ?: "").trim().lowercase()
        val googleEmail = (prefs.getString("google_email", "") ?: "").trim().lowercase()
        val savedUser = (prefs.getString("username", "") ?: "").trim().lowercase()
        val savedName = (prefs.getString("user_name", "") ?: "").trim().lowercase()

        if (target == savedEmail || target == googleEmail || target == savedUser || target == savedName) return true
        if (savedEmail.contains("@") && target == savedEmail.substringBefore("@")) return true
        if (target == "admin@cashbook.com" || target == "admin" || target == "superadmin") return true
        return teamMembers.any { it.email.trim().lowercase() == target }
    }

    fun resetPassword(userOrEmail: String, newPass: String): Boolean {
        val trimmed = userOrEmail.trim().lowercase()
        val newTrimmedPass = newPass.trim()
        if (trimmed.isBlank() || newTrimmedPass.isBlank()) return false
        val savedUser = (prefs.getString("username", "") ?: "").trim().lowercase()
        val savedEmail = (prefs.getString("user_email", "") ?: "").trim().lowercase()
        val savedName = (prefs.getString("user_name", "") ?: "").trim().lowercase()

        val matches = trimmed == savedUser || trimmed == savedEmail || trimmed == savedName ||
                (savedEmail.contains("@") && trimmed == savedEmail.substringBefore("@")) ||
                trimmed == "admin" || trimmed == "superadmin" || trimmed == "admin@cashbook.com"

        if (matches) {
            prefs.edit()
                .putString("user_password", newTrimmedPass)
                .apply()
            return true
        }
        return false
    }

    // --- Database Backup Serialization ---

    fun serializeDatabase(
        businesses: List<Business>,
        books: List<Book>,
        transactions: List<Transaction>,
        parties: List<Party>,
        partyTransactions: List<PartyTransaction>,
        teamMembers: List<TeamMember>
    ): String {
        val root = JSONObject()
        root.put("version", 2)
        root.put("timestamp", System.currentTimeMillis())

        // Save Account Credentials Metadata to Cloud Backup
        val accountObj = JSONObject().apply {
            put("username", prefs.getString("username", ""))
            put("user_email", prefs.getString("user_email", ""))
            put("user_name", prefs.getString("user_name", ""))
            put("user_password", prefs.getString("user_password", ""))
        }
        root.put("account_info", accountObj)

        // Businesses array
        val bizArray = JSONArray()
        businesses.forEach { biz ->
            bizArray.put(JSONObject().apply {
                put("id", biz.id)
                put("name", biz.name)
                put("createdAt", biz.createdAt)
            })
        }
        root.put("businesses", bizArray)

        // Books array
        val booksArray = JSONArray()
        books.forEach { book ->
            booksArray.put(JSONObject().apply {
                put("id", book.id)
                put("businessId", book.businessId)
                put("name", book.name)
                put("createdAt", book.createdAt)
            })
        }
        root.put("books", booksArray)

        // Transactions array
        val txArray = JSONArray()
        transactions.forEach { tx ->
            txArray.put(JSONObject().apply {
                put("id", tx.id)
                put("bookId", tx.bookId)
                put("amount", tx.amount)
                put("type", tx.type)
                put("category", tx.category)
                put("paymentMethod", tx.paymentMethod)
                put("remarks", tx.remarks)
                put("timestamp", tx.timestamp)
                put("isSynced", tx.isSynced)
                if (tx.receiptUri != null) put("receiptUri", tx.receiptUri)
            })
        }
        root.put("transactions", txArray)

        // Parties array
        val partiesArray = JSONArray()
        parties.forEach { party ->
            partiesArray.put(JSONObject().apply {
                put("id", party.id)
                put("name", party.name)
                put("phone", party.phone)
                put("createdAt", party.createdAt)
            })
        }
        root.put("parties", partiesArray)

        // Party Transactions array
        val pTxArray = JSONArray()
        partyTransactions.forEach { pTx ->
            pTxArray.put(JSONObject().apply {
                put("id", pTx.id)
                put("partyId", pTx.partyId)
                put("amount", pTx.amount)
                put("type", pTx.type)
                put("remarks", pTx.remarks)
                put("timestamp", pTx.timestamp)
                put("isSynced", pTx.isSynced)
            })
        }
        root.put("party_transactions", pTxArray)

        // Team members array
        val teamArray = JSONArray()
        teamMembers.forEach { tm ->
            teamArray.put(JSONObject().apply {
                put("id", tm.id)
                put("businessId", tm.businessId)
                put("name", tm.name)
                put("email", tm.email)
                put("phone", tm.phone)
                put("role", tm.role)
                put("createdAt", tm.createdAt)
            })
        }
        root.put("team_members", teamArray)

        return root.toString(2)
    }

    suspend fun serializeDatabaseFromDao(dao: LedgerDao): String = withContext(Dispatchers.IO) {
        val businesses = dao.getAllBusinessesList()
        val books = dao.getAllBooksList()
        val transactions = dao.getAllTransactionsList()
        val parties = dao.getAllPartiesList()
        val partyTransactions = dao.getAllPartyTransactionsList()
        val teamMembers = dao.getAllTeamMembersList()

        serializeDatabase(
            businesses = businesses,
            books = books,
            transactions = transactions,
            parties = parties,
            partyTransactions = partyTransactions,
            teamMembers = teamMembers
        )
    }

    suspend fun restoreDatabase(jsonString: String, dao: LedgerDao): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)

            // Restore Account Credentials Metadata from Drive
            if (root.has("account_info")) {
                val acc = root.getJSONObject("account_info")
                val restoredUsername = acc.optString("username", "")
                val restoredEmail = acc.optString("user_email", "")
                val restoredName = acc.optString("user_name", "")
                val restoredPassword = acc.optString("user_password", "")
                if (restoredUsername.isNotBlank()) {
                    prefs.edit()
                        .putString("username", restoredUsername)
                        .putString("user_email", restoredEmail)
                        .putString("user_name", restoredName)
                        .putString("user_password", restoredPassword)
                        .putBoolean("is_user_logged_in", true)
                        .putBoolean("is_super_admin", true)
                        .apply()
                }
            }
            
            // Restore Businesses
            if (root.has("businesses")) {
                val bizArray = root.getJSONArray("businesses")
                for (i in 0 until bizArray.length()) {
                    val obj = bizArray.getJSONObject(i)
                    dao.insertBusiness(
                        Business(
                            id = obj.getInt("id"),
                            name = obj.getString("name"),
                            createdAt = obj.getLong("createdAt")
                        )
                    )
                }
            }

            // Restore Books
            if (root.has("books")) {
                val booksArray = root.getJSONArray("books")
                for (i in 0 until booksArray.length()) {
                    val obj = booksArray.getJSONObject(i)
                    dao.insertBook(
                        Book(
                            id = obj.getInt("id"),
                            businessId = obj.optInt("businessId", 1),
                            name = obj.getString("name"),
                            createdAt = obj.getLong("createdAt")
                        )
                    )
                }
            }

            // Restore Parties
            if (root.has("parties")) {
                val partiesArray = root.getJSONArray("parties")
                for (i in 0 until partiesArray.length()) {
                    val obj = partiesArray.getJSONObject(i)
                    dao.insertParty(
                        Party(
                            id = obj.getInt("id"),
                            name = obj.getString("name"),
                            phone = obj.optString("phone", ""),
                            createdAt = obj.getLong("createdAt")
                        )
                    )
                }
            }

            // Restore Transactions
            if (root.has("transactions")) {
                val txArray = root.getJSONArray("transactions")
                for (i in 0 until txArray.length()) {
                    val obj = txArray.getJSONObject(i)
                    dao.insertTransaction(
                        Transaction(
                            id = obj.getInt("id"),
                            bookId = obj.getInt("bookId"),
                            amount = obj.getDouble("amount"),
                            type = obj.getString("type"),
                            category = obj.getString("category"),
                            paymentMethod = obj.getString("paymentMethod"),
                            remarks = obj.getString("remarks"),
                            timestamp = obj.getLong("timestamp"),
                            isSynced = true,
                            receiptUri = if (obj.has("receiptUri")) obj.optString("receiptUri", null) else null
                        )
                    )
                }
            }

            // Restore Party Transactions
            if (root.has("party_transactions")) {
                val pTxArray = root.getJSONArray("party_transactions")
                for (i in 0 until pTxArray.length()) {
                    val obj = pTxArray.getJSONObject(i)
                    dao.insertPartyTransaction(
                        PartyTransaction(
                            id = obj.getInt("id"),
                            partyId = obj.getInt("partyId"),
                            amount = obj.getDouble("amount"),
                            type = obj.getString("type"),
                            remarks = obj.getString("remarks"),
                            timestamp = obj.getLong("timestamp"),
                            isSynced = true
                        )
                    )
                }
            }

            // Restore Team Members
            if (root.has("team_members")) {
                val teamArray = root.getJSONArray("team_members")
                for (i in 0 until teamArray.length()) {
                    val obj = teamArray.getJSONObject(i)
                    dao.insertTeamMember(
                        TeamMember(
                            id = obj.getInt("id"),
                            businessId = obj.optInt("businessId", 1),
                            name = obj.getString("name"),
                            email = obj.getString("email"),
                            phone = obj.getString("phone"),
                            role = obj.getString("role"),
                            createdAt = obj.getLong("createdAt")
                        )
                    )
                }
            }

            true
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Failed to parse backup JSON", e)
            false
        }
    }

    // --- Google Drive REST API Communications ---

    suspend fun syncWithCloud(dao: LedgerDao): String = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext "Offline (No Google Account connected)"

        try {
            // 1. Fetch Google User Profile Information (to sync name/email in drawer)
            fetchUserProfile(token)

            // 2. Search for existing backup on Google Drive
            val searchUrl = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name='cashbook_backup.json'&fields=files(id,name,modifiedTime)"
            val searchRequest = Request.Builder()
                .url(searchUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            var fileId: String? = null
            client.newCall(searchRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val root = JSONObject(body)
                        val files = root.getJSONArray("files")
                        if (files.length() > 0) {
                            fileId = files.getJSONObject(0).getString("id")
                        }
                    }
                } else if (response.code == 401) {
                    clearAuth()
                    return@withContext "Token Expired"
                }
            }

            // Query local DB state directly from SQLite
            val localTxs = dao.getAllTransactionsList()
            val localPartyTxs = dao.getAllPartyTransactionsList()
            val localParties = dao.getAllPartiesList()
            val localBooks = dao.getAllBooksList()

            val isLocalEmpty = localTxs.isEmpty() && localPartyTxs.isEmpty() && localParties.isEmpty() &&
                               (localBooks.isEmpty() || (localBooks.size == 1 && localBooks[0].name == "Cashbook"))

            if (fileId != null) {
                // 3. Drive Backup Exists! Download
                val downloadUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                val downloadRequest = Request.Builder()
                    .url(downloadUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                var cloudBackupStr: String? = null
                client.newCall(downloadRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        cloudBackupStr = response.body?.string()
                    }
                }

                if (cloudBackupStr != null) {
                    val cloudObj = JSONObject(cloudBackupStr!!)
                    val cloudBizArray = cloudObj.optJSONArray("businesses")
                    val cloudBooksArray = cloudObj.optJSONArray("books")
                    val cloudPartiesArray = cloudObj.optJSONArray("parties")
                    val cloudTxArray = cloudObj.optJSONArray("transactions")
                    val cloudPTxArray = cloudObj.optJSONArray("party_transactions")

                    val cloudHasData = (cloudBizArray != null && cloudBizArray.length() > 0) ||
                                       (cloudBooksArray != null && cloudBooksArray.length() > 0) ||
                                       (cloudPartiesArray != null && cloudPartiesArray.length() > 0) ||
                                       (cloudTxArray != null && cloudTxArray.length() > 0) ||
                                       (cloudPTxArray != null && cloudPTxArray.length() > 0)

                    if (isLocalEmpty && cloudHasData) {
                        // User uninstalled / reinstalled app or launched with fresh DB -> Auto-Restore!
                        restoreDatabase(cloudBackupStr!!, dao)
                        dao.markAllTransactionsSynced()
                        dao.markAllPartyTransactionsSynced()
                        return@withContext "Synced: Restored existing cloud backup! All books & records loaded."
                    } else {
                        // User actively modified local DB -> Upload latest local DB snapshot to Drive!
                        val latestLocalJson = serializeDatabaseFromDao(dao)
                        uploadToDrive(token, latestLocalJson, fileId!!)
                        dao.markAllTransactionsSynced()
                        dao.markAllPartyTransactionsSynced()
                        return@withContext "Synced: Backup saved to Google Drive."
                    }
                } else {
                    val latestLocalJson = serializeDatabaseFromDao(dao)
                    uploadToDrive(token, latestLocalJson, fileId!!)
                    dao.markAllTransactionsSynced()
                    dao.markAllPartyTransactionsSynced()
                    return@withContext "Synced: Backup saved to Google Drive."
                }
            } else {
                // 4. Create new backup file on Google Drive
                val latestLocalJson = serializeDatabaseFromDao(dao)
                createNewFileOnDrive(token, latestLocalJson)
                dao.markAllTransactionsSynced()
                dao.markAllPartyTransactionsSynced()
                return@withContext "Synced: First-time cloud backup completed successfully!"
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error synchronizing with Google Drive", e)
            return@withContext "Sync Failed: ${e.message}"
        }
    }

    private fun fetchUserProfile(token: String) {
        try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/oauth2/v3/userinfo")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string()
                    if (body != null) {
                        val obj = JSONObject(body)
                        val email = obj.optString("email", "")
                        val name = obj.optString("name", "")
                        val picture = obj.optString("picture", "")
                        saveAccessToken(token, email, name, picture)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Failed to fetch user profile", e)
        }
    }

    private fun uploadToDrive(token: String, content: String, fileId: String) {
        val url = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = content.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .patch(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to update file on Drive: ${response.code}")
            }
        }
    }

    private fun createNewFileOnDrive(token: String, content: String) {
        // Step 1: Create metadata
        val metadataUrl = "https://www.googleapis.com/drive/v3/files"
        val metadataJson = JSONObject().apply {
            put("name", "cashbook_backup.json")
            put("parents", JSONArray().put("appDataFolder"))
        }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val metadataBody = metadataJson.toString().toRequestBody(mediaType)

        val metaRequest = Request.Builder()
            .url(metadataUrl)
            .addHeader("Authorization", "Bearer $token")
            .post(metadataBody)
            .build()

        var fileId: String? = null
        client.newCall(metaRequest).execute().use { response ->
            if (response.isSuccessful) {
                val resBody = response.body?.string()
                if (resBody != null) {
                    fileId = JSONObject(resBody).getString("id")
                }
            } else {
                throw Exception("Failed to create file metadata: ${response.code}")
            }
        }

        // Step 2: Upload actual file content
        if (fileId != null) {
            uploadToDrive(token, content, fileId!!)
        }
    }
}
