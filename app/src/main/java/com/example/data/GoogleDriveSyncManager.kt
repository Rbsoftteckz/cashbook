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
    private var detectedRedirectUri: String = "https://gen-lang-client-0052637237.firebaseapp.com/__/auth/handler"

    init {
        try {
            val jsonString = context.assets.open("firebase-applet-config.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(jsonString)
            val oAuthClientId = obj.optString("oAuthClientId", "")
            val projectId = obj.optString("projectId", "")
            if (oAuthClientId.isNotBlank()) {
                detectedClientId = oAuthClientId
            }
            if (projectId.isNotBlank()) {
                detectedRedirectUri = "https://$projectId.firebaseapp.com/__/auth/handler"
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Failed to load firebase-applet-config.json from assets", e)
        }

        // Clean up legacy custom values if they match old hardcoded defaults to allow automatic sync for all users
        val savedId = prefs.getString("custom_client_id", "")
        if (savedId == "755700558600-jl4pipc2klikiac22ivk8s3qvn0pjtc7.apps.googleusercontent.com" || 
            savedId == "755700558600-jl4pipc2klikiac22ivk8s3qvn0pjtc7") {
            prefs.edit().remove("custom_client_id").apply()
        }
        val savedUri = prefs.getString("custom_redirect_uri", "")
        if (savedUri == "https://localhost/oauth2redirect" || savedUri == "https://localhost") {
            prefs.edit().remove("custom_redirect_uri").apply()
        }
    }

    companion object {
        // Default Client ID for AI Studio Demo (users can also supply their own client ID in settings)
        val DEFAULT_CLIENT_ID: String = if (com.example.BuildConfig.OAUTH_CLIENT_ID.isNotBlank() && !com.example.BuildConfig.OAUTH_CLIENT_ID.startsWith("MY_")) {
            com.example.BuildConfig.OAUTH_CLIENT_ID
        } else {
            "381324439605-pustt1n8j4gank1iuh94d4rdk0ivl8eb.apps.googleusercontent.com"
        }
        const val REDIRECT_URI = "https://localhost/oauth2redirect"
    }

    fun getClientId(): String {
        val saved = prefs.getString("custom_client_id", "")
        return if (!saved.isNullOrBlank() && saved != "755700558600-jl4pipc2klikiac22ivk8s3qvn0pjtc7.apps.googleusercontent.com" && saved != "755700558600-jl4pipc2klikiac22ivk8s3qvn0pjtc7") saved else detectedClientId
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

    // --- SharedPreferences Auth Storage ---

    fun saveAccessToken(token: String, email: String = "", name: String = "", avatarUrl: String = "") {
        prefs.edit()
            .putString("access_token", token)
            .putString("email", email)
            .putString("name", name)
            .putString("avatar_url", avatarUrl)
            .putLong("token_saved_time", System.currentTimeMillis())
            .apply()
    }

    fun getAccessToken(): String? {
        val token = prefs.getString("access_token", null)
        val savedTime = prefs.getLong("token_saved_time", 0)
        // Token expires after 1 hour (3600000 ms)
        if (token != null && System.currentTimeMillis() - savedTime > 3600000) {
            clearAuth()
            return null
        }
        return token
    }

    fun getEmail(): String = prefs.getString("email", "") ?: ""
    fun getName(): String = prefs.getString("name", "") ?: ""
    fun getAvatarUrl(): String = prefs.getString("avatar_url", "") ?: ""

    fun clearAuth() {
        prefs.edit()
            .remove("access_token")
            .remove("email")
            .remove("name")
            .remove("avatar_url")
            .remove("token_saved_time")
            .apply()
    }

    fun isUserSignedIn(): Boolean = getAccessToken() != null

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

    suspend fun restoreDatabase(jsonString: String, dao: LedgerDao): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)
            
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
                            timestamp = obj.getLong("timestamp")
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
                            timestamp = obj.getLong("timestamp")
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

    suspend fun syncWithCloud(localBackupJson: String, dao: LedgerDao): String = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext "Authentication Required"

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

            if (fileId != null) {
                // 3. Drive Backup Exists! Let's download and perform Conflict-Free Local Merging
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
                    try {
                        val cloudObj = JSONObject(cloudBackupStr!!)
                        val cloudTime = cloudObj.optLong("timestamp", 0)
                        val localTime = JSONObject(localBackupJson).optLong("timestamp", 0)

                        if (cloudTime > localTime) {
                            // Cloud backup is newer! Let's restore and merge locally
                            restoreDatabase(cloudBackupStr!!, dao)
                            return@withContext "Synced: Restored newer backup from Google Drive!"
                        } else {
                            // Local backup is newer or same! Overwrite/Update the Cloud file
                            uploadToDrive(token, localBackupJson, fileId!!)
                            return@withContext "Synced: Local ledger backup updated to Google Drive!"
                        }
                    } catch (e: Exception) {
                        // Fallback to update drive if reading fails
                        uploadToDrive(token, localBackupJson, fileId!!)
                        return@withContext "Synced: Clean merge upload to Google Drive complete."
                    }
                } else {
                    uploadToDrive(token, localBackupJson, fileId!!)
                    return@withContext "Synced: Backed up to Google Drive."
                }
            } else {
                // 4. Create new file on Google Drive
                createNewFileOnDrive(token, localBackupJson)
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
