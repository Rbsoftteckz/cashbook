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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RegisteredAccount(
    val name: String,
    val email: String,
    val username: String,
    val pass: String
)

data class TeamInviteRecord(
    val inviteCode: String,
    val ownerEmail: String,
    val ownerDocId: String,
    val businessName: String,
    val businessCreatedAt: Long,
    val invitedEmail: String,
    val invitedName: String,
    val role: String,
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis(),
    val allowedBookNames: String = ""
)

data class SharedWorkspaceRef(
    val ownerDocId: String,
    val ownerEmail: String,
    val businessName: String,
    val businessCreatedAt: Long,
    val role: String,
    val inviteCode: String,
    val allowedBookNames: String = ""
)

class GoogleDriveSyncManager(private val context: Context) {

    private val syncMutex = Mutex()
    private val prefs = context.getSharedPreferences("google_drive_sync_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var detectedClientId: String = DEFAULT_CLIENT_ID
    private var detectedRedirectUri: String = "https://localhost"
    private var firebaseProjectId: String = "cashbook-8d579"
    private var firebaseApiKey: String = "BCJ_4LbHKtiGOSxjVYH1J-jP7tibnIrTeFqrL233DknUm4lmAM95NlEpfgE13Yx5Or0ftg2TPW4woTbNe-HOrGU"

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
                firebaseProjectId = projectId
                detectedRedirectUri = "https://$projectId.firebaseapp.com/__/auth/handler"
            } else {
                detectedRedirectUri = "https://localhost"
            }
            val apiKey = obj.optString("apiKey", "")
            if (apiKey.isNotBlank()) {
                firebaseApiKey = apiKey
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Failed to load firebase-applet-config.json from assets", e)
            detectedRedirectUri = "https://localhost"
        }

        // Migration/Overriding old default project ID if present in SharedPreferences
        val savedProjId = prefs.getString("custom_firebase_project_id", "")
        if (savedProjId == "gen-lang-client-0052637237" || savedProjId.isNullOrBlank()) {
            saveFirebaseProjectId("cashbook-8d579")
        }
        val savedApiKey = prefs.getString("custom_firebase_api_key", "")
        if (savedApiKey == "AIzaSyDDLCoD8_9lyN1wJVR5sOTNHbKgCdLqZDs" || savedApiKey.isNullOrBlank()) {
            saveFirebaseApiKey("BCJ_4LbHKtiGOSxjVYH1J-jP7tibnIrTeFqrL233DknUm4lmAM95NlEpfgE13Yx5Or0ftg2TPW4woTbNe-HOrGU")
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

    fun getFirebaseProjectId(): String {
        val custom = prefs.getString("custom_firebase_project_id", "")
        return if (!custom.isNullOrBlank()) custom else firebaseProjectId
    }

    fun saveFirebaseProjectId(projectId: String) {
        prefs.edit().putString("custom_firebase_project_id", projectId).apply()
    }

    fun getFirebaseApiKey(): String {
        val custom = prefs.getString("custom_firebase_api_key", "")
        return if (!custom.isNullOrBlank()) custom else firebaseApiKey
    }

    fun saveFirebaseApiKey(apiKey: String) {
        prefs.edit().putString("custom_firebase_api_key", apiKey).apply()
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
        val userEmail = prefs.getString("user_email", "") ?: ""
        if (userEmail.isNotBlank()) return userEmail
        val oldEmail = prefs.getString("email", "") ?: ""
        if (oldEmail.isNotBlank()) return oldEmail
        return ""
    }

    fun getName(): String {
        val googleName = getGoogleName()
        if (googleName.isNotBlank()) return googleName
        val userName = prefs.getString("user_name", "") ?: ""
        if (userName.isNotBlank()) return userName
        val oldName = prefs.getString("name", "") ?: ""
        if (oldName.isNotBlank()) return oldName
        return ""
    }

    fun getAvatarUrl(): String = prefs.getString("google_avatar_url", "") ?: ""

    fun getDeviceId(): String {
        var id = prefs.getString("device_id", "")
        if (id.isNullOrBlank()) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

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

    fun isRealCloudAccount(): Boolean {
        val email = getEmail().trim().lowercase()
        if (email.isBlank() || email == "offline@cashbook.local" || email.contains("offline.local")) {
            return false
        }
        return isUserSignedIn()
    }

    fun isUserSignedIn(): Boolean = isUserLoggedIn() || hasGoogleDriveToken()

    // --- Firestore REST Helper Methods ---

    private fun buildFirestoreFields(vararg pairs: Pair<String, Any>): JSONObject {
        val fields = JSONObject()
        for ((key, value) in pairs) {
            val valObj = JSONObject()
            when (value) {
                is String -> valObj.put("stringValue", value)
                is Long -> valObj.put("integerValue", value.toString())
                is Int -> valObj.put("integerValue", value.toString())
                is Boolean -> valObj.put("booleanValue", value)
                else -> valObj.put("stringValue", value.toString())
            }
            fields.put(key, valObj)
        }
        val root = JSONObject()
        root.put("fields", fields)
        return root
    }

    private fun parseFirestoreUser(docObj: JSONObject): RegisteredAccount {
        val fields = docObj.optJSONObject("fields") ?: JSONObject()
        val name = fields.optJSONObject("name")?.optString("stringValue", "") ?: ""
        val email = fields.optJSONObject("email")?.optString("stringValue", "") ?: ""
        val username = fields.optJSONObject("username")?.optString("stringValue", "") ?: ""
        val pass = fields.optJSONObject("pass")?.optString("stringValue", "") ?: ""
        return RegisteredAccount(name, email, username, pass)
    }

    private fun syncUserToMasterCloud(acc: RegisteredAccount) {
        try {
            val cleanEmail = acc.email.trim().lowercase()
            val cleanUser = acc.username.trim().lowercase()
            if (cleanEmail.isBlank() && cleanUser.isBlank()) return

            val targetKey = cleanEmail.ifBlank { cleanUser }
            val docId = "user_" + targetKey.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val payload = buildFirestoreFields(
                "name" to acc.name.trim(),
                "email" to cleanEmail,
                "username" to cleanUser,
                "pass" to acc.pass.trim(),
                "updatedAt" to System.currentTimeMillis()
            )
            val idToken = getFirebaseAuthToken()
            patchOrPostFirestoreDoc("users", docId, payload, idToken)
            // Mirror inside cashbooks collection so direct lookup works even if /users collection rules differ
            val mirrorId = "useraccount_" + targetKey.replace(Regex("[^a-zA-Z0-9_]"), "_")
            patchOrPostFirestoreDoc("cashbooks", mirrorId, payload, idToken)
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error syncing user to Firestore", e)
        }
    }

    private fun extractAccountWithTimestampFromCashbookFields(fields: JSONObject, targetClean: String): Pair<RegisteredAccount, Long>? {
        val dataJson = fields.optJSONObject("dataJson")?.optString("stringValue", "") ?: ""
        if (dataJson.isBlank()) return null
        return try {
            val root = JSONObject(dataJson)
            val docTs = root.optLong("timestamp", 0L).coerceAtLeast(
                fields.optJSONObject("updatedAt")?.optString("integerValue", "0")?.toLongOrNull() ?: 0L
            )
            val info = root.optJSONObject("account_info")
            if (info != null) {
                val e = info.optString("user_email", "").trim()
                val u = info.optString("username", "").trim()
                val n = info.optString("user_name", "").trim()
                val p = info.optString("user_password", "").trim()
                if (e.equals(targetClean, true) || u.equals(targetClean, true) ||
                    (e.contains("@") && e.substringBefore("@").equals(targetClean, true))) {
                    return Pair(RegisteredAccount(n, e, u, p), docTs)
                }
            }
            val globalArr = root.optJSONArray("global_accounts")
            if (globalArr != null) {
                for (i in 0 until globalArr.length()) {
                    val obj = globalArr.getJSONObject(i)
                    val e = obj.optString("email", "").trim()
                    val u = obj.optString("username", "").trim()
                    val n = obj.optString("name", "").trim()
                    val p = obj.optString("pass", "").trim()
                    if (e.equals(targetClean, true) || u.equals(targetClean, true) ||
                        (e.contains("@") && e.substringBefore("@").equals(targetClean, true))) {
                        return Pair(RegisteredAccount(n, e, u, p), docTs)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAccountFromCashbookFields(fields: JSONObject, targetClean: String): RegisteredAccount? {
        return extractAccountWithTimestampFromCashbookFields(fields, targetClean)?.first
    }

    private fun findUserAccountDirectlyCloud(input: String): RegisteredAccount? {
        val cleanInput = input.trim().lowercase()
        if (cleanInput.isBlank()) return null
        val idToken = getFirebaseAuthToken()

        val keyVariants = mutableListOf(cleanInput.replace(Regex("[^a-zA-Z0-9_]"), "_"))
        if (cleanInput.contains("@")) {
            val prefixSanitized = cleanInput.substringBefore("@").replace(Regex("[^a-zA-Z0-9_]"), "_")
            if (!keyVariants.contains(prefixSanitized)) keyVariants.add(prefixSanitized)
        }

        val candidates = mutableListOf<Pair<RegisteredAccount, Long>>()

        for (sanitized in keyVariants) {
            // 1. Direct GET /users/user_<sanitized>
            fetchFirestoreFields("users", "user_$sanitized", idToken)?.let { f ->
                val e = f.optJSONObject("email")?.optString("stringValue", "")?.trim() ?: ""
                val u = f.optJSONObject("username")?.optString("stringValue", "")?.trim() ?: ""
                val n = f.optJSONObject("name")?.optString("stringValue", "")?.trim() ?: ""
                val p = f.optJSONObject("pass")?.optString("stringValue", "")?.trim() ?: ""
                val ts = f.optJSONObject("updatedAt")?.optString("integerValue", "0")?.toLongOrNull() ?: 0L
                if (e.isNotBlank() || u.isNotBlank()) {
                    candidates.add(Pair(RegisteredAccount(n, e, u, p), ts))
                }
            }
            // 2. Direct GET /cashbooks/useraccount_<sanitized>
            fetchFirestoreFields("cashbooks", "useraccount_$sanitized", idToken)?.let { f ->
                val e = f.optJSONObject("email")?.optString("stringValue", "")?.trim() ?: ""
                val u = f.optJSONObject("username")?.optString("stringValue", "")?.trim() ?: ""
                val n = f.optJSONObject("name")?.optString("stringValue", "")?.trim() ?: ""
                val p = f.optJSONObject("pass")?.optString("stringValue", "")?.trim() ?: ""
                val ts = f.optJSONObject("updatedAt")?.optString("integerValue", "0")?.toLongOrNull() ?: 0L
                if (e.isNotBlank() || u.isNotBlank()) {
                    candidates.add(Pair(RegisteredAccount(n, e, u, p), ts))
                }
            }
            // 3. Direct GET /cashbooks/cashbook_<sanitized> -> parse account_info / global_accounts inside dataJson
            fetchFirestoreFields("cashbooks", "cashbook_$sanitized", idToken)?.let { f ->
                extractAccountWithTimestampFromCashbookFields(f, cleanInput)?.let { found ->
                    candidates.add(found)
                }
            }
        }

        if (candidates.isEmpty()) return null
        return candidates
            .sortedWith(compareByDescending<Pair<RegisteredAccount, Long>> { it.second }.thenByDescending { it.first.pass.isNotBlank() })
            .first()
            .first
    }

    suspend fun addUserCloud(name: String, email: String, username: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        val acc = RegisteredAccount(name.trim(), email.trim(), username.trim(), pass.trim())
        registerUserCloud(acc.name, acc.email, acc.username, acc.pass)
        syncUserToMasterCloud(acc)
        true
    }

    suspend fun updateUserCloud(oldEmail: String, newName: String, newEmail: String, newUsername: String, newPass: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanOld = oldEmail.trim().lowercase()
            val newAcc = RegisteredAccount(newName.trim(), newEmail.trim(), newUsername.trim(), newPass.trim())

            // 1. Update local global accounts
            val globalAccs = getGlobalAccounts().toMutableList()
            val idx = globalAccs.indexOfFirst { it.email.trim().lowercase() == cleanOld || it.username.trim().lowercase() == cleanOld }
            if (idx >= 0) {
                globalAccs[idx] = newAcc
            } else {
                globalAccs.add(newAcc)
            }
            saveGlobalAccounts(globalAccs)

            // 2. Push to Firestore
            syncUserToMasterCloud(newAcc)
            true
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error updating user in Firestore", e)
            false
        }
    }

    suspend fun deleteUserCloud(emailToDelete: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanTarget = emailToDelete.trim().lowercase()

            // 1. Remove from local global accounts list
            val globalAccs = getGlobalAccounts().toMutableList()
            globalAccs.removeAll { it.email.trim().lowercase() == cleanTarget || it.username.trim().lowercase() == cleanTarget }
            saveGlobalAccounts(globalAccs)

            // 2. Delete document from Firestore
            val docId = "user_" + cleanTarget.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val projId = getFirebaseProjectId()
            val apiKey = getFirebaseApiKey()
            val url = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/users/$docId?key=$apiKey"
            val req = Request.Builder().url(url).delete().build()
            client.newCall(req).execute().close()
            true
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error deleting user from Firestore", e)
            false
        }
    }

    suspend fun fetchFirebaseAccountsCloud(): List<RegisteredAccount> = withContext(Dispatchers.IO) {
        val list = mutableListOf<RegisteredAccount>()

        // 1. Fetch from Firestore users collection with pagination so >20 accounts are never truncated
        try {
            val projId = getFirebaseProjectId()
            val apiKey = getFirebaseApiKey()
            var nextPageToken: String? = null
            var pagesFetched = 0
            do {
                val tokenParam = if (!nextPageToken.isNullOrBlank()) "&pageToken=${java.net.URLEncoder.encode(nextPageToken, "UTF-8")}" else ""
                val firestoreUsersUrl = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/users?pageSize=300$tokenParam&key=$apiKey"
                val req = Request.Builder().url(firestoreUsersUrl).get().build()
                var newPageToken: String? = null
                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        if (bodyStr.isNotBlank()) {
                            val root = JSONObject(bodyStr)
                            newPageToken = root.optString("nextPageToken", "").ifBlank { null }
                            val docs = root.optJSONArray("documents")
                            if (docs != null) {
                                for (i in 0 until docs.length()) {
                                    val doc = docs.getJSONObject(i)
                                    val fields = doc.optJSONObject("fields") ?: continue
                                    val n = fields.optJSONObject("name")?.optString("stringValue", "") ?: ""
                                    val e = fields.optJSONObject("email")?.optString("stringValue", "") ?: ""
                                    val un = fields.optJSONObject("username")?.optString("stringValue", "") ?: ""
                                    val p = fields.optJSONObject("pass")?.optString("stringValue", "") ?: ""
                                    if ((e.isNotBlank() || un.isNotBlank()) && !list.any { it.email.equals(e, true) || (un.isNotBlank() && it.username.equals(un, true)) }) {
                                        list.add(RegisteredAccount(n, e, un, p))
                                    }
                                }
                            }
                        }
                    }
                }
                nextPageToken = newPageToken
                pagesFetched++
            } while (!nextPageToken.isNullOrBlank() && pagesFetched < 5)
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error fetching cloud accounts from Firestore /users", e)
        }

        // 2. Compare against local accounts (preferring newer local password resets so stale cloud docs never overwrite a recent reset)
        val localAccs = getGlobalAccounts()
        for (acc in localAccs) {
            val cleanLocalEmail = acc.email.trim().lowercase()
            val cleanLocalUser = acc.username.trim().lowercase()
            val existingIdx = list.indexOfFirst {
                (cleanLocalEmail.isNotBlank() && it.email.equals(cleanLocalEmail, ignoreCase = true)) ||
                    (cleanLocalUser.isNotBlank() && it.username.equals(cleanLocalUser, ignoreCase = true))
            }
            if (existingIdx < 0) {
                if (cleanLocalEmail.isNotBlank() || cleanLocalUser.isNotBlank()) {
                    list.add(acc)
                    try { syncUserToMasterCloud(acc) } catch (_: Exception) {}
                }
            } else {
                val resetKey = "pw_reset_ts_" + (cleanLocalEmail.ifBlank { cleanLocalUser }).replace(Regex("[^a-zA-Z0-9_]"), "_")
                val localResetTs = prefs.getLong(resetKey, 0L)
                if (localResetTs > 0L && acc.pass.isNotBlank() && list[existingIdx].pass != acc.pass) {
                    list[existingIdx] = acc
                    try { syncUserToMasterCloud(acc) } catch (_: Exception) {}
                }
            }
        }

        if (list.isNotEmpty()) {
            saveGlobalAccounts(list)
        }
        return@withContext getGlobalAccounts()
    }

    suspend fun checkEmailExistsCloud(input: String): Boolean = withContext(Dispatchers.IO) {
        val cleanInput = input.trim().lowercase()
        if (cleanInput.isBlank()) return@withContext false

        // 1. Check local memory/prefs
        if (checkEmailExists(cleanInput)) return@withContext true

        // 2. Direct lookup in Firestore (/users/user_<email> and /cashbooks/cashbook_<email>)
        findUserAccountDirectlyCloud(cleanInput)?.let { found ->
            saveGlobalAccounts(listOf(found))
            return@withContext true
        }

        // 3. Query Firebase Auth API via createAuthUri (only when a valid AIza Web API key is configured)
        val activeApiKey = getFirebaseApiKey()
        val targetEmail = if (cleanInput.contains("@")) cleanInput else "$cleanInput@cashbook.com"
        if (activeApiKey.startsWith("AIza")) {
            try {
                val url = "https://identitytoolkit.googleapis.com/v1/accounts:createAuthUri?key=$activeApiKey"
                val payload = JSONObject().apply {
                    put("identifier", targetEmail)
                    put("continueUri", "https://localhost")
                }.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url).post(payload).build()
                client.newCall(req).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyStr.isNotBlank()) {
                        val json = JSONObject(bodyStr)
                        if (json.optBoolean("registered", false)) {
                            return@withContext true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GoogleDriveSyncManager", "Error checking Firebase createAuthUri", e)
            }
        }

        // 4. Fetch fresh accounts from online cloud directory
        val cloudAccounts = fetchFirebaseAccountsCloud()
        cloudAccounts.any { acc ->
            val accEmail = acc.email.trim().lowercase()
            val accUser = acc.username.trim().lowercase()
            val accName = acc.name.trim().lowercase()
            cleanInput == accEmail ||
            cleanInput == accUser ||
            cleanInput == accName ||
            (accEmail.contains("@") && cleanInput == accEmail.substringBefore("@"))
        }
    }

    suspend fun sendFirebasePasswordResetEmail(email: String): String = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank()) {
            return@withContext "INVALID_EMAIL"
        }

        val exists = checkEmailExistsCloud(cleanEmail)
        if (!exists) {
            return@withContext "EMAIL_NOT_FOUND"
        }

        val activeApiKey = getFirebaseApiKey()
        if (!activeApiKey.startsWith("AIza")) {
            return@withContext "SUCCESS"
        }

        try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=$activeApiKey"
            val payload = JSONObject().apply {
                put("requestType", "PASSWORD_RESET")
                put("email", if (cleanEmail.contains("@")) cleanEmail else "$cleanEmail@cashbook.com")
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(payload).build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    return@withContext "SUCCESS"
                } else {
                    Log.w("GoogleDriveSyncManager", "sendOobCode response ${response.code}: $bodyStr")
                    if (bodyStr.contains("EMAIL_NOT_FOUND")) {
                        return@withContext "EMAIL_NOT_FOUND"
                    }
                    return@withContext "SUCCESS"
                }
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error sending password reset email", e)
            return@withContext "ERROR"
        }
    }

    suspend fun registerUserCloud(name: String, email: String, username: String, pass: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val cleanUser = username.trim().lowercase()
        val cleanPass = pass.trim()

        // 1. Strict Email Format Validation
        val isValidEmail = cleanEmail.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()
        if (!isValidEmail) {
            return@withContext Pair(false, "Please enter a valid email address (e.g., user@example.com).")
        }

        // 2. Strict Password Length Validation
        if (cleanPass.length < 6) {
            return@withContext Pair(false, "Password must be at least 6 characters long.")
        }

        // 3. Check direct Firestore user/cashbook doc OR full cloud accounts list
        val directExisting = findUserAccountDirectlyCloud(cleanEmail)
        val cloudAccounts = if (directExisting != null) listOf(directExisting) else fetchFirebaseAccountsCloud()
        val existingAcc = directExisting
            ?: cloudAccounts.find { it.email.lowercase() == cleanEmail || (cleanUser.isNotBlank() && it.username.lowercase() == cleanUser) }
            ?: getGlobalAccounts().find { it.email.lowercase() == cleanEmail || (cleanUser.isNotBlank() && it.username.lowercase() == cleanUser) }

        if (existingAcc != null) {
            val storedPass = existingAcc.pass.trim()
            if (storedPass.isNotBlank() && storedPass != cleanPass) {
                return@withContext Pair(false, "An account with email '$cleanEmail' already exists. Please switch to Sign In with your password (or use Forgot Password).")
            }
            Log.i("GoogleDriveSyncManager", "Existing account verified for $cleanEmail. Restoring account session.")
            registerUser(existingAcc.name.ifBlank { name.trim() }, existingAcc.email, existingAcc.username, cleanPass.ifBlank { storedPass })
            syncUserToMasterCloud(existingAcc.copy(pass = cleanPass.ifBlank { storedPass }))
            return@withContext Pair(true, "EXISTING_ACCOUNT_RESTORED")
        }

        val acc = RegisteredAccount(name.trim(), cleanEmail, cleanUser, cleanPass)
        val activeApiKey = getFirebaseApiKey()

        // 4. Try Firebase Auth API when AIza Web API key is active so existing Auth emails cannot be overwritten silently
        if (activeApiKey.startsWith("AIza")) {
            try {
                val url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$activeApiKey"
                val payload = JSONObject().apply {
                    put("email", cleanEmail)
                    put("password", cleanPass)
                    put("returnSecureToken", true)
                }.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url).post(payload).build()
                var authEmailAlreadyExists = false
                client.newCall(req).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val json = JSONObject(bodyStr)
                        val idToken = json.optString("idToken", "")
                        if (idToken.isNotBlank()) {
                            prefs.edit().putString("firebase_auth_token", idToken).apply()
                        }
                    } else if (bodyStr.contains("EMAIL_EXISTS")) {
                        authEmailAlreadyExists = true
                    }
                }
                if (authEmailAlreadyExists) {
                    val signInUrl = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$activeApiKey"
                    val signInPayload = JSONObject().apply {
                        put("email", cleanEmail)
                        put("password", cleanPass)
                        put("returnSecureToken", true)
                    }.toString().toRequestBody("application/json".toMediaType())
                    var verifiedExisting = false
                    client.newCall(Request.Builder().url(signInUrl).post(signInPayload).build()).execute().use { res ->
                        val b = res.body?.string() ?: ""
                        if (res.isSuccessful && b.isNotBlank()) {
                            val json = JSONObject(b)
                            val idToken = json.optString("idToken", "")
                            if (idToken.isNotBlank()) {
                                prefs.edit().putString("firebase_auth_token", idToken).apply()
                            }
                            verifiedExisting = true
                        }
                    }
                    if (!verifiedExisting) {
                        return@withContext Pair(false, "An account with email '$cleanEmail' already exists. Please switch to Sign In with your password (or use Forgot Password).")
                    }
                    registerUser(name.trim(), cleanEmail, cleanUser, cleanPass)
                    syncUserToMasterCloud(acc)
                    return@withContext Pair(true, "EXISTING_ACCOUNT_RESTORED")
                }
            } catch (e: Exception) {
                Log.e("GoogleDriveSyncManager", "Firebase Auth sign up notice", e)
            }
        }

        // 5. Register locally & push to Online Cloud Master Directory + cashbooks mirror
        registerUser(name.trim(), cleanEmail, cleanUser, cleanPass)
        syncUserToMasterCloud(acc)

        Pair(true, "Account registered & synced to Cloud Database! You can Sign In from any device.")
    }

    suspend fun loginUserCloud(userOrEmail: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        val cleanInput = userOrEmail.trim().lowercase()
        val cleanPass = pass.trim()

        if (cleanInput.isBlank()) return@withContext false

        // 1. Direct Firestore lookup (/users/user_<email> + /cashbooks/useraccount_ + /cashbooks/cashbook_<email>)
        // Check Firestore first because in-app Forgot Password resets update Firestore docs with the newest timestamp.
        val directMatched = findUserAccountDirectlyCloud(cleanInput)
        val matched = directMatched ?: run {
            val cloudAccounts = fetchFirebaseAccountsCloud()
            cloudAccounts.find { acc ->
                val accEmail = acc.email.trim().lowercase()
                val accUser = acc.username.trim().lowercase()
                accEmail == cleanInput ||
                accUser == cleanInput ||
                (accEmail.contains("@") && accEmail.substringBefore("@") == cleanInput)
            }
        }

        if (matched != null) {
            val isAdminAccount = matched.email.equals("admin@cashbook.com", ignoreCase = true) ||
                matched.username.equals("admin", ignoreCase = true) ||
                matched.username.equals("superadmin", ignoreCase = true)
            val passMatches = if (isAdminAccount) {
                cleanPass == getSuperAdminMasterPassword() ||
                    (matched.pass.isNotBlank() && cleanPass == matched.pass.trim())
            } else {
                matched.pass.trim() == cleanPass ||
                    (matched.pass.isBlank() && cleanPass.isBlank())
            }
            if (passMatches) {
                val passToUse = if (cleanPass.isNotBlank()) cleanPass else matched.pass
                registerUser(matched.name, matched.email, matched.username, passToUse)
                syncUserToMasterCloud(matched.copy(pass = passToUse))
                return@withContext true
            }
        }

        // 2. Try Firebase Auth signInWithPassword API (handles accounts registered or reset via Firebase Auth email link)
        val activeApiKey = getFirebaseApiKey()
        val targetEmail = if (cleanInput.contains("@")) cleanInput else "$cleanInput@cashbook.com"
        if (cleanPass.isNotBlank() && activeApiKey.startsWith("AIza")) {
            try {
                val url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$activeApiKey"
                val payload = JSONObject().apply {
                    put("email", targetEmail)
                    put("password", cleanPass)
                    put("returnSecureToken", true)
                }.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url).post(payload).build()
                client.newCall(req).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyStr.isNotBlank()) {
                        val json = JSONObject(bodyStr)
                        val idToken = json.optString("idToken", "")
                        val resEmail = json.optString("email", targetEmail)
                        if (idToken.isNotBlank()) {
                            prefs.edit().putString("firebase_auth_token", idToken).commit()
                            val username = matched?.username?.ifBlank { null }
                                ?: if (resEmail.contains("@")) resEmail.substringBefore("@") else resEmail
                            val name = matched?.name?.ifBlank { null }
                                ?: username.replaceFirstChar { it.uppercase() }
                            registerUser(name, resEmail, username, cleanPass)
                            syncUserToMasterCloud(RegisteredAccount(name, resEmail, username, cleanPass))
                            return@withContext true
                        }
                    } else if (bodyStr.contains("INVALID_PASSWORD") || bodyStr.contains("INVALID_LOGIN_CREDENTIALS")) {
                        Log.w("GoogleDriveSyncManager", "Firebase Auth rejected password for $targetEmail")
                    }
                }
            } catch (e: Exception) {
                Log.e("GoogleDriveSyncManager", "Firebase Auth signInWithPassword error", e)
            }
        }

        if (matched != null) {
            return@withContext false // Cloud account found, password did not match Firestore or Firebase Auth
        }

        // 3. Fallback to local accounts (offline / local-only)
        return@withContext loginUser(userOrEmail, pass)
    }

    suspend fun resetPasswordCloud(userOrEmail: String, newPass: String): Boolean = withContext(Dispatchers.IO) {
        val cleanInput = userOrEmail.trim().lowercase()
        val trimmedNewPass = newPass.trim()
        if (cleanInput.isBlank() || trimmedNewPass.length < 4) return@withContext false
        if (cleanInput == "admin" || cleanInput == "superadmin" || cleanInput == "admin@cashbook.com") {
            return@withContext false
        }

        val directAcc = findUserAccountDirectlyCloud(cleanInput)
        val cloudAccounts = if (directAcc != null) listOf(directAcc) else fetchFirebaseAccountsCloud()
        val existingAcc = directAcc
            ?: cloudAccounts.find {
                it.email.lowercase() == cleanInput ||
                    it.username.lowercase() == cleanInput ||
                    (it.email.contains("@") && it.email.substringBefore("@").lowercase() == cleanInput)
            }
            ?: getGlobalAccounts().find {
                it.email.lowercase() == cleanInput ||
                    it.username.lowercase() == cleanInput ||
                    (it.email.contains("@") && it.email.substringBefore("@").lowercase() == cleanInput)
            }

        val resolvedAcc = if (existingAcc != null) {
            existingAcc.copy(pass = trimmedNewPass)
        } else {
            val autoEmail = if (cleanInput.contains("@")) cleanInput else "$cleanInput@cashbook.local"
            val autoUser = if (cleanInput.contains("@")) cleanInput.substringBefore("@") else cleanInput
            val autoName = autoUser.replaceFirstChar { it.uppercase() }
            RegisteredAccount(autoName, autoEmail, autoUser, trimmedNewPass)
        }

        val targetEmail = resolvedAcc.email.trim().lowercase()
        val resetKey = "pw_reset_ts_" + (targetEmail.ifBlank { cleanInput }).replace(Regex("[^a-zA-Z0-9_]"), "_")
        prefs.edit().putLong(resetKey, System.currentTimeMillis()).commit()

        // 1. Save locally in active prefs & registered_accounts_json
        registerUser(resolvedAcc.name, resolvedAcc.email, resolvedAcc.username, trimmedNewPass)

        // 2. Push to /users/user_<key> AND /cashbooks/useraccount_<key>
        syncUserToMasterCloud(resolvedAcc)

        // 3. Also update password inside /cashbooks/cashbook_<key> backup JSON so old snapshots never restore old password
        try {
            val sanitizedTarget = (targetEmail.ifBlank { cleanInput }).replace(Regex("[^a-zA-Z0-9_]"), "_")
            val cashbookDocId = "cashbook_$sanitizedTarget"
            val idToken = getFirebaseAuthToken()
            val fields = fetchFirestoreFields("cashbooks", cashbookDocId, idToken)
            val dataJsonStr = fields?.optJSONObject("dataJson")?.optString("stringValue", "") ?: ""
            if (dataJsonStr.isNotBlank()) {
                val root = JSONObject(dataJsonStr)
                val info = root.optJSONObject("account_info")
                if (info != null) {
                    info.put("user_password", trimmedNewPass)
                    root.put("account_info", info)
                }
                val globalArr = root.optJSONArray("global_accounts")
                if (globalArr != null) {
                    var updatedEntry = false
                    for (i in 0 until globalArr.length()) {
                        val obj = globalArr.getJSONObject(i)
                        val e = obj.optString("email", "").trim().lowercase()
                        val u = obj.optString("username", "").trim().lowercase()
                        if (e == targetEmail || u == resolvedAcc.username.trim().lowercase()) {
                            obj.put("pass", trimmedNewPass)
                            updatedEntry = true
                        }
                    }
                    if (!updatedEntry) {
                        globalArr.put(JSONObject().apply {
                            put("name", resolvedAcc.name)
                            put("email", resolvedAcc.email)
                            put("username", resolvedAcc.username)
                            put("pass", trimmedNewPass)
                        })
                    }
                    root.put("global_accounts", globalArr)
                }
                val payload = buildFirestoreFields(
                    "userEmail" to targetEmail,
                    "dataJson" to root.toString(2),
                    "updatedAt" to System.currentTimeMillis()
                )
                patchOrPostFirestoreDoc("cashbooks", cashbookDocId, payload, idToken)
                prefs.edit().putString("cloud_vault_$cashbookDocId", root.toString(2)).apply()
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error updating cashbook backup password", e)
        }

        true
    }

    // --- Global Accounts Registry & Management ---

    fun getGlobalAccounts(): List<RegisteredAccount> {
        val list = mutableListOf<RegisteredAccount>()
        val jsonStr = prefs.getString("registered_accounts_json", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val e = obj.optString("email", "")
                val u = obj.optString("username", "")
                if (!e.endsWith("@cashbook.local") && !u.endsWith("@cashbook.local")) {
                    list.add(
                        RegisteredAccount(
                            name = obj.optString("name", ""),
                            email = e,
                            username = u,
                            pass = obj.optString("pass", "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error parsing registered_accounts_json", e)
        }

        // Include current active user from prefs if not already in list
        val savedUser = (prefs.getString("username", "") ?: "").trim()
        val savedEmail = (prefs.getString("user_email", "") ?: "").trim()
        val savedName = (prefs.getString("user_name", "") ?: "").trim()
        val savedPass = (prefs.getString("user_password", "") ?: "").trim()
        if ((savedEmail.isNotBlank() || savedUser.isNotBlank()) && !savedEmail.endsWith("@cashbook.local") && !savedUser.endsWith("@cashbook.local")) {
            if (list.none { it.email.equals(savedEmail, ignoreCase = true) || it.username.equals(savedUser, ignoreCase = true) }) {
                list.add(RegisteredAccount(savedName, savedEmail, savedUser, savedPass))
            }
        }

        if (list.none { it.email.equals("admin@cashbook.com", ignoreCase = true) || it.username.equals("admin", ignoreCase = true) }) {
            list.add(0, RegisteredAccount("Admin", "admin@cashbook.com", "admin", getSuperAdminMasterPassword()))
        }

        return list
    }

    fun getSuperAdminMasterPassword(): String {
        val customMaster = prefs.getString("super_admin_master_password", "")?.trim() ?: ""
        if (customMaster.isNotBlank()) return customMaster
        // Check if admin account already has a password persisted in registered_accounts_json
        val jsonStr = prefs.getString("registered_accounts_json", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val e = obj.optString("email", "")
                val u = obj.optString("username", "")
                val p = obj.optString("pass", "").trim()
                if ((e.equals("admin@cashbook.com", ignoreCase = true) || u.equals("admin", ignoreCase = true)) && p.isNotBlank()) {
                    return p
                }
            }
        } catch (_: Exception) { }
        return "superadmin123"
    }

    fun setSuperAdminMasterPassword(newPass: String): Boolean {
        val trimmed = newPass.trim()
        if (trimmed.length < 4) return false
        prefs.edit().putString("super_admin_master_password", trimmed).apply()

        val accounts = getGlobalAccounts().toMutableList()
        val idx = accounts.indexOfFirst {
            it.email.equals("admin@cashbook.com", ignoreCase = true) || it.username.equals("admin", ignoreCase = true)
        }
        if (idx >= 0) {
            val existing = accounts[idx]
            accounts[idx] = existing.copy(pass = trimmed)
        } else {
            accounts.add(0, RegisteredAccount("Super Admin", "admin@cashbook.com", "admin", trimmed))
        }
        saveGlobalAccounts(accounts)

        if (isSuperAdminLoggedIn()) {
            prefs.edit().putString("user_password", trimmed).apply()
        }
        return true
    }

    fun saveGlobalAccounts(accounts: List<RegisteredAccount>) {
        val existingList = mutableListOf<RegisteredAccount>()
        val jsonStr = prefs.getString("registered_accounts_json", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val e = obj.optString("email", "")
                val u = obj.optString("username", "")
                if (e.isNotBlank() || u.isNotBlank()) {
                    existingList.add(
                        RegisteredAccount(
                            name = obj.optString("name", ""),
                            email = e,
                            username = u,
                            pass = obj.optString("pass", "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error parsing registered_accounts_json in saveGlobalAccounts", e)
        }

        // Merge incoming accounts into existingList
        accounts.forEach { newAcc ->
            if (newAcc.email.isNotBlank() || newAcc.username.isNotBlank()) {
                val idx = existingList.indexOfFirst {
                    (it.email.isNotBlank() && it.email.equals(newAcc.email, ignoreCase = true)) ||
                    (it.username.isNotBlank() && it.username.equals(newAcc.username, ignoreCase = true))
                }
                if (idx >= 0) {
                    val current = existingList[idx]
                    existingList[idx] = RegisteredAccount(
                        name = if (newAcc.name.isNotBlank()) newAcc.name else current.name,
                        email = if (newAcc.email.isNotBlank()) newAcc.email else current.email,
                        username = if (newAcc.username.isNotBlank()) newAcc.username else current.username,
                        pass = if (newAcc.pass.isNotBlank()) newAcc.pass else current.pass
                    )
                } else {
                    existingList.add(newAcc)
                }
            }
        }

        val arr = JSONArray()
        existingList.forEach { acc ->
            arr.put(JSONObject().apply {
                put("name", acc.name)
                put("email", acc.email)
                put("username", acc.username)
                put("pass", acc.pass)
            })
        }
        prefs.edit().putString("registered_accounts_json", arr.toString()).commit()
    }

    fun hasRegisteredAccount(): Boolean {
        return getGlobalAccounts().isNotEmpty() || !prefs.getString("username", "").isNullOrBlank()
    }

    fun isUserLoggedIn(): Boolean {
        return prefs.getBoolean("is_user_logged_in", false)
    }

    fun isSuperAdminLoggedIn(): Boolean {
        if (!isUserLoggedIn()) return false
        val email = getEmail().trim().lowercase()
        val username = (prefs.getString("username", "") ?: "").trim().lowercase()
        val isAdminAccount = email == "admin@cashbook.com" || username == "admin" || username == "superadmin"
        if (!isAdminAccount) {
            if (prefs.getBoolean("is_super_admin", false)) {
                prefs.edit().putBoolean("is_super_admin", false).apply()
            }
            return false
        }
        return true
    }

    fun registerUser(name: String, email: String, username: String, pass: String) {
        val trimmedName = name.trim()
        val trimmedEmail = email.trim()
        val trimmedUser = username.trim().ifBlank {
            if (trimmedEmail.contains("@")) trimmedEmail.substringBefore("@")
            else if (trimmedName.isNotBlank()) trimmedName.replace(" ", "").lowercase()
            else "user"
        }
        val finalEmail = if (trimmedEmail.isBlank()) {
            if (trimmedUser.equals("admin", ignoreCase = true) || trimmedUser.equals("superadmin", ignoreCase = true)) "admin@cashbook.com"
            else "$trimmedUser@cashbook.local"
        } else trimmedEmail
        val finalName = if (trimmedName.isBlank()) (if (trimmedUser.equals("admin", ignoreCase = true)) "Super Admin" else trimmedUser) else trimmedName
        val finalPass = pass.trim()

        val isSuperAdmin = finalEmail.equals("admin@cashbook.com", ignoreCase = true) || trimmedUser.equals("admin", ignoreCase = true) || trimmedUser.equals("superadmin", ignoreCase = true)

        val currentAccounts = getGlobalAccounts().toMutableList()
        val existingIndex = currentAccounts.indexOfFirst {
            it.email.equals(finalEmail, ignoreCase = true) ||
            it.username.equals(trimmedUser, ignoreCase = true)
        }
        val newAccount = RegisteredAccount(finalName, finalEmail, trimmedUser, finalPass)
        if (existingIndex >= 0) {
            currentAccounts[existingIndex] = newAccount
        } else {
            currentAccounts.add(newAccount)
        }
        saveGlobalAccounts(currentAccounts)

        prefs.edit()
            .putString("user_name", finalName)
            .putString("user_email", finalEmail)
            .putString("username", trimmedUser)
            .putString("user_password", finalPass)
            .putBoolean("is_user_logged_in", true)
            .putBoolean("is_super_admin", isSuperAdmin)
            .commit()
    }

    fun registerCustomUser(name: String, email: String, username: String, pass: String) = registerUser(name, email, username, pass)

    fun loginUser(userOrEmail: String, pass: String): Boolean {
        val trimmedUser = userOrEmail.trim().lowercase()
        val trimmedPass = pass.trim()

        if (trimmedUser.isBlank()) return false

        val globalAccounts = getGlobalAccounts()
        val matchedAccount = globalAccounts.firstOrNull { acc ->
            val accEmail = acc.email.trim().lowercase()
            val accUser = acc.username.trim().lowercase()

            trimmedUser == accEmail ||
            (accEmail.contains("@") && trimmedUser == accEmail.substringBefore("@")) ||
            trimmedUser == accUser
        }

        if (matchedAccount != null) {
            val isAdminEntry = matchedAccount.email.equals("admin@cashbook.com", ignoreCase = true) ||
                matchedAccount.username.equals("admin", ignoreCase = true)
            val passMatches = if (isAdminEntry) {
                trimmedPass == getSuperAdminMasterPassword() ||
                    (matchedAccount.pass.isNotBlank() && trimmedPass == matchedAccount.pass.trim())
            } else {
                matchedAccount.pass.trim() == trimmedPass ||
                    (matchedAccount.pass.isBlank() && trimmedPass.isBlank())
            }

            if (passMatches) {
                registerUser(matchedAccount.name, matchedAccount.email, matchedAccount.username, if (trimmedPass.isNotBlank()) trimmedPass else matchedAccount.pass)
                return true
            }
            return false // Password incorrect!
        }

        if ((trimmedUser == "admin" || trimmedUser == "superadmin" || trimmedUser == "admin@cashbook.com") &&
            trimmedPass == getSuperAdminMasterPassword()) {
            registerUser("Super Admin", "admin@cashbook.com", "admin", trimmedPass)
            return true
        }

        return false // Account does not exist
    }

    fun loginSuperAdmin(user: String, pass: String): Boolean = loginUser(user, pass)

    fun loginSuperAdminWithPassword(pass: String): Boolean {
        val trimmed = pass.trim()
        if (trimmed.isBlank()) return false

        val masterPass = getSuperAdminMasterPassword()
        val globalAccounts = getGlobalAccounts()
        val adminAccount = globalAccounts.firstOrNull {
            it.email.equals("admin@cashbook.com", ignoreCase = true) || it.username.equals("admin", ignoreCase = true)
        }
        val hasCustomMaster = !prefs.getString("super_admin_master_password", "").isNullOrBlank()
        val isValid = if (hasCustomMaster) {
            trimmed == masterPass
        } else {
            trimmed == masterPass || (adminAccount != null && adminAccount.pass.isNotBlank() && trimmed == adminAccount.pass.trim())
        }

        if (isValid) {
            registerUser("Super Admin", "admin@cashbook.com", "admin", masterPass)
            return true
        }
        return false
    }

    fun logoutUser() {
        clearGoogleAuth()
        prefs.edit()
            .putBoolean("is_user_logged_in", false)
            .putBoolean("is_super_admin", false)
            .putBoolean("user_has_modified_local_db", false)
            .putLong("local_db_last_modified_ts", 0L)
            .putLong("local_db_last_synced_ts", 0L)
            .remove("user_email")
            .remove("username")
            .remove("user_name")
            .remove("user_password")
            .remove("email")
            .remove("name")
            .remove("google_email")
            .remove("google_name")
            .remove("google_avatar_url")
            .remove("access_token")
            .remove("token_saved_time")
            .commit()
    }

    fun logoutSuperAdmin() = logoutUser()

    fun checkEmailExists(email: String, teamMembers: List<TeamMember> = emptyList()): Boolean {
        val target = email.trim().lowercase()
        if (target.isBlank()) return false

        // 1. Check Global Registered Accounts list
        val globalAccounts = getGlobalAccounts()
        val inGlobal = globalAccounts.any { acc ->
            val accEmail = acc.email.trim().lowercase()
            val accUser = acc.username.trim().lowercase()
            val accName = acc.name.trim().lowercase()

            target == accEmail ||
            (accEmail.contains("@") && target == accEmail.substringBefore("@")) ||
            target == accUser ||
            target == accName
        }
        if (inGlobal) return true

        // 2. Check current saved active user in SharedPreferences
        val savedEmail = (prefs.getString("user_email", "") ?: "").trim().lowercase()
        val savedUser = (prefs.getString("username", "") ?: "").trim().lowercase()
        if (savedEmail.isNotBlank() && (target == savedEmail || target == savedUser || (savedEmail.contains("@") && target == savedEmail.substringBefore("@")))) return true

        // 3. Check Google authenticated email
        val googleEmail = (prefs.getString("google_email", "") ?: "").trim().lowercase()
        if (googleEmail.isNotBlank() && target == googleEmail) return true

        // 4. Static admin identifiers
        if (target == "admin@cashbook.com" || target == "admin" || target == "superadmin") return true

        return false
    }

    fun resetPassword(userOrEmail: String, newPass: String): Boolean {
        val trimmed = userOrEmail.trim().lowercase()
        val newTrimmedPass = newPass.trim()
        if (trimmed.isBlank() || newTrimmedPass.isBlank()) return false
        if (trimmed == "admin" || trimmed == "superadmin" || trimmed == "admin@cashbook.com") return false

        val globalAccounts = getGlobalAccounts().toMutableList()
        val index = globalAccounts.indexOfFirst { acc ->
            val accEmail = acc.email.trim().lowercase()
            val accUser = acc.username.trim().lowercase()
            val accName = acc.name.trim().lowercase()

            trimmed == accEmail ||
            (accEmail.contains("@") && trimmed == accEmail.substringBefore("@")) ||
            trimmed == accUser ||
            trimmed == accName
        }

        if (index >= 0) {
            val existing = globalAccounts[index]
            if (existing.email.equals("admin@cashbook.com", ignoreCase = true) ||
                existing.username.equals("admin", ignoreCase = true) ||
                existing.username.equals("superadmin", ignoreCase = true)) {
                return false
            }
            val updated = RegisteredAccount(existing.name, existing.email, existing.username, newTrimmedPass)
            globalAccounts[index] = updated
            saveGlobalAccounts(globalAccounts)

            val currentEmail = (prefs.getString("user_email", "") ?: "").trim().lowercase()
            val currentUsername = (prefs.getString("username", "") ?: "").trim().lowercase()
            if (trimmed == currentEmail || trimmed == currentUsername || existing.email.lowercase() == currentEmail) {
                prefs.edit().putString("user_password", newTrimmedPass).apply()
            }
            return true
        }

        val savedUser = (prefs.getString("username", "") ?: "").trim().lowercase()
        val savedEmail = (prefs.getString("user_email", "") ?: "").trim().lowercase()
        if (trimmed == savedUser || trimmed == savedEmail) {
            val finalEmail = if (savedEmail.isNotBlank()) savedEmail else if (trimmed.contains("@")) trimmed else "$trimmed@cashbook.local"
            val finalName = if (trimmed.contains("@")) trimmed.substringBefore("@").replaceFirstChar { it.uppercase() } else trimmed.replaceFirstChar { it.uppercase() }
            val finalUser = if (savedUser.isNotBlank()) savedUser else trimmed
            registerUser(finalName, finalEmail, finalUser, newTrimmedPass)
            return true
        }

        // Fallback for any email or username supplied during reset: register or update account so user can sign in immediately
        val autoName = if (trimmed.contains("@")) trimmed.substringBefore("@").replaceFirstChar { it.uppercase() } else trimmed.replaceFirstChar { it.uppercase() }
        val autoEmail = if (trimmed.contains("@")) trimmed else "$trimmed@cashbook.local"
        val autoUser = if (trimmed.contains("@")) trimmed.substringBefore("@") else trimmed
        registerUser(autoName, autoEmail, autoUser, newTrimmedPass)
        return true
    }

    // --- Database Backup Serialization ---

    fun serializeDatabase(
        businesses: List<Business>,
        books: List<Book>,
        transactions: List<Transaction>,
        parties: List<Party>,
        partyTransactions: List<PartyTransaction>,
        teamMembers: List<TeamMember>,
        isPartialExport: Boolean = false
    ): String {
        val root = JSONObject()
        root.put("version", 2)
        root.put("is_partial_export", isPartialExport)
        root.put("timestamp", System.currentTimeMillis())
        root.put("device_id", getDeviceId())

        // Save Account Credentials Metadata to Cloud Backup
        val accountObj = JSONObject().apply {
            put("username", prefs.getString("username", ""))
            put("user_email", prefs.getString("user_email", ""))
            put("user_name", prefs.getString("user_name", ""))
            put("user_password", prefs.getString("user_password", ""))
        }
        root.put("account_info", accountObj)

        val globalAccountsArr = JSONArray()
        getGlobalAccounts().forEach { acc ->
            globalAccountsArr.put(JSONObject().apply {
                put("name", acc.name)
                put("email", acc.email)
                put("username", acc.username)
                put("pass", acc.pass)
            })
        }
        root.put("global_accounts", globalAccountsArr)

        // Businesses array
        val bizArray = JSONArray()
        businesses.forEach { biz ->
            bizArray.put(JSONObject().apply {
                put("id", biz.id)
                put("name", biz.name)
                put("userEmail", biz.userEmail)
                put("createdAt", biz.createdAt)
                put("isSynced", biz.isSynced)
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
                put("phone", book.phone)
                put("createdAt", book.createdAt)
                put("isSynced", book.isSynced)
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
                put("allowedBookNames", tm.allowedBookNames)
            })
        }
        root.put("team_members", teamArray)

        // Synced Tombstones (timestamped so deletions replicate without resurrecting and allow re-creation later)
        if (!isPartialExport) {
            root.put("tombstones", exportTombstonesJson())
        }

        return root.toString(2)
    }

    private fun isOfflineBusinessOrEmail(email: String?, name: String?): Boolean {
        val cleanEmail = (email ?: "").trim().lowercase()
        val cleanName = (name ?: "").trim().lowercase()
        return cleanEmail == "offline@cashbook.local" ||
            cleanEmail == "offline_user@cashbook.local" ||
            cleanEmail.endsWith(".local") ||
            cleanName == "offline user's business" ||
            cleanName == "offline business"
    }

    suspend fun serializeDatabaseFromDao(dao: LedgerDao): String = withContext(Dispatchers.IO) {
        val activeEmail = getEmail().trim().lowercase()
        val isCloudSession = isRealCloudAccount()
        val allBusinesses = dao.getAllBusinessesList()
        val allTeam = dao.getAllTeamMembersList()

        val ownedOrRelevantBusinesses = when {
            isCloudSession -> {
                allBusinesses.filter { biz ->
                    val owner = biz.userEmail.trim().lowercase()
                    if (isOfflineBusinessOrEmail(owner, biz.name)) {
                        false
                    } else {
                        owner == activeEmail || (owner.isBlank() && !isBusinessDeleted(biz.name, biz.createdAt))
                    }
                }
            }
            activeEmail == "offline@cashbook.local" -> {
                allBusinesses.filter { biz ->
                    val owner = biz.userEmail.trim().lowercase()
                    owner == "offline@cashbook.local" || owner.isBlank()
                }
            }
            else -> allBusinesses
        }

        val exportedBizIds = ownedOrRelevantBusinesses.map { it.id }.toSet()
        val books = dao.getAllBooksList().filter { it.businessId in exportedBizIds }
        val exportedBookIds = books.map { it.id }.toSet()
        val transactions = dao.getAllTransactionsList().filter { it.bookId in exportedBookIds }
        val parties = dao.getAllPartiesList()
        val partyTransactions = dao.getAllPartyTransactionsList()
        val teamMembers = allTeam.filter { it.businessId in exportedBizIds }

        serializeDatabase(
            businesses = ownedOrRelevantBusinesses,
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

            // Never overwrite active user identity or elevate Super Admin privileges from synced/restored JSON data.
            // Ledger restore only merges business/book/transaction/party records for the signed-in session.

            if (root.has("global_accounts")) {
                val accArr = root.getJSONArray("global_accounts")
                val remoteTs = root.optLong("timestamp", 0L)
                val existingLocal = getGlobalAccounts()
                val activeLocalPass = (prefs.getString("user_password", "") ?: "").trim()
                val activeLocalEmail = getEmail().trim().lowercase()
                val restoredList = mutableListOf<RegisteredAccount>()
                for (i in 0 until accArr.length()) {
                    val obj = accArr.getJSONObject(i)
                    val rEmail = obj.optString("email", "")
                    val rUser = obj.optString("username", "")
                    var rPass = obj.optString("pass", "")

                    val cleanREmail = rEmail.trim().lowercase()
                    val resetKey = "pw_reset_ts_" + (cleanREmail.ifBlank { rUser.trim().lowercase() }).replace(Regex("[^a-zA-Z0-9_]"), "_")
                    val localResetTs = prefs.getLong(resetKey, 0L)
                    val matchedLocal = existingLocal.find {
                        (cleanREmail.isNotBlank() && it.email.equals(cleanREmail, true)) ||
                            (rUser.isNotBlank() && it.username.equals(rUser, true))
                    }
                    if (cleanREmail.isNotBlank() && cleanREmail == activeLocalEmail && activeLocalPass.isNotBlank()) {
                        rPass = activeLocalPass
                    } else if (localResetTs > remoteTs && matchedLocal != null && matchedLocal.pass.isNotBlank()) {
                        rPass = matchedLocal.pass
                    }

                    restoredList.add(
                        RegisteredAccount(
                            name = obj.optString("name", ""),
                            email = rEmail,
                            username = rUser,
                            pass = rPass
                        )
                    )
                }
                if (restoredList.isNotEmpty()) {
                    saveGlobalAccounts(restoredList)
                }
            }

            val isPartialExport = root.optBoolean("is_partial_export", false)
            val remoteTimestamp = root.optLong("timestamp", 0L)
            val sessionEmail = getEmail().trim().lowercase()
            val isCloudSession = isRealCloudAccount()
            val localModifiedTs = prefs.getLong("local_db_last_modified_ts", 0L)

            if (!isPartialExport && root.has("tombstones")) {
                importTombstonesJson(root.getJSONObject("tombstones"))
            }

            // Purge any locally remaining offline or tombstoned businesses before merge
            if (isCloudSession) {
                val toPurge = dao.getAllBusinessesList().filter { localBiz ->
                    val deletedByTombstone = isBusinessDeleted(localBiz.name, localBiz.createdAt)
                    val isOfflineLeaked = isOfflineBusinessOrEmail(localBiz.userEmail, localBiz.name) &&
                        !localBiz.userEmail.equals(sessionEmail, ignoreCase = true) &&
                        isBusinessDeleted(localBiz.name, 0L)
                    deletedByTombstone || isOfflineLeaked
                }
                toPurge.forEach { localBiz ->
                    dao.deleteBooksForBusiness(localBiz.id)
                    dao.deleteTeamMembersForBusiness(localBiz.id)
                    dao.deleteBusiness(localBiz)
                }
            }

            var existingBizList = dao.getAllBusinessesList().toMutableList()
            var existingBooksList = dao.getAllBooksList().toMutableList()
            val existingTxList = dao.getAllTransactionsList().toMutableList()
            val existingPartiesList = dao.getAllPartiesList().toMutableList()
            val existingPartyTxList = dao.getAllPartyTransactionsList().toMutableList()
            val existingTeamList = dao.getAllTeamMembersList().toMutableList()

            val bizIdMap = mutableMapOf<Int, Int>()
            val bookIdMap = mutableMapOf<Int, Int>()
            val partyIdMap = mutableMapOf<Int, Int>()

            // Restore/Merge Businesses
            if (root.has("businesses")) {
                val bizArray = root.getJSONArray("businesses")
                val docAccountEmail = root.optJSONObject("account_info")?.optString("user_email", "")?.trim()?.lowercase() ?: ""
                for (i in 0 until bizArray.length()) {
                    val obj = bizArray.getJSONObject(i)
                    val oldId = obj.getInt("id")
                    val bName = obj.getString("name")
                    val rawBizEmail = obj.optString("userEmail", "").trim().lowercase()
                    val createdAt = obj.getLong("createdAt")

                    if (isCloudSession && isOfflineBusinessOrEmail(rawBizEmail, bName)) continue
                    if (isBusinessDeleted(bName, createdAt)) continue

                    val ownerEmail = when {
                        rawBizEmail.isNotBlank() -> rawBizEmail
                        docAccountEmail.isNotBlank() && !docAccountEmail.endsWith(".local") -> docAccountEmail
                        isCloudSession -> sessionEmail
                        else -> sessionEmail
                    }

                    val defaultMatch = if (existingBizList.size == 1 && existingTxList.isEmpty() &&
                        (existingBizList[0].userEmail.isBlank() || existingBizList[0].userEmail.equals(ownerEmail, ignoreCase = true)) &&
                        (existingBizList[0].name.contains("@") || existingBizList[0].name == "My Business" || existingBizList[0].name == "Main Business")) {
                        existingBizList[0]
                    } else null

                    val matchedByName = existingBizList.find {
                        it.name.equals(bName, ignoreCase = true) &&
                            (it.userEmail.isBlank() || it.userEmail.equals(ownerEmail, ignoreCase = true))
                    } ?: defaultMatch

                    if (matchedByName != null) {
                        bizIdMap[oldId] = matchedByName.id
                        val needsEmailFix = matchedByName.userEmail.isBlank() && ownerEmail.isNotBlank()
                        val needsNameFix = !matchedByName.name.equals(bName, ignoreCase = true) && bName.isNotBlank()
                        if (needsEmailFix || needsNameFix) {
                            val updatedBiz = matchedByName.copy(
                                name = if (needsNameFix) bName else matchedByName.name,
                                userEmail = if (needsEmailFix) ownerEmail else matchedByName.userEmail,
                                isSynced = true
                            )
                            dao.updateBusiness(updatedBiz)
                            val idx = existingBizList.indexOfFirst { it.id == matchedByName.id }
                            if (idx >= 0) existingBizList[idx] = updatedBiz
                        }
                    } else {
                        val newBiz = Business(
                            id = 0,
                            name = bName,
                            userEmail = ownerEmail,
                            createdAt = createdAt,
                            isSynced = true
                        )
                        val newId = dao.insertBusiness(newBiz).toInt()
                        bizIdMap[oldId] = newId
                        existingBizList.add(newBiz.copy(id = newId))
                    }
                }

                // Delete local businesses owned by this user that were deleted on remote (Skip for partial export or when local has newer edits)
                if (!isPartialExport && remoteTimestamp > localModifiedTs) {
                    val remoteBizIdsInLocal = bizIdMap.values.toSet()
                    val remoteBizNames = mutableSetOf<String>()
                    for (i in 0 until bizArray.length()) {
                        remoteBizNames.add(bizArray.getJSONObject(i).getString("name").trim().lowercase())
                    }
                    val bizToDelete = existingBizList.filter { localBiz ->
                        val belongsToSession = if (isCloudSession) {
                            localBiz.userEmail.equals(sessionEmail, ignoreCase = true)
                        } else {
                            localBiz.userEmail.equals(sessionEmail, ignoreCase = true) || localBiz.userEmail.isBlank()
                        }
                        belongsToSession && localBiz.isSynced && !remoteBizIdsInLocal.contains(localBiz.id) && !remoteBizNames.contains(localBiz.name.trim().lowercase())
                    }
                    bizToDelete.forEach { localBiz ->
                        dao.deleteBooksForBusiness(localBiz.id)
                        dao.deleteTeamMembersForBusiness(localBiz.id)
                        dao.deleteBusiness(localBiz)
                        existingBizList.remove(localBiz)
                    }
                }
            }

            // Restore/Merge Books
            if (root.has("books")) {
                val booksArray = root.getJSONArray("books")
                val remoteBookKeys = mutableSetOf<String>()
                for (i in 0 until booksArray.length()) {
                    val obj = booksArray.getJSONObject(i)
                    val oldId = obj.getInt("id")
                    val oldBizId = obj.optInt("businessId", 1)
                    val mappedBizId = bizIdMap[oldBizId] ?: oldBizId
                    val bkName = obj.getString("name")
                    val phone = obj.optString("phone", "")
                    val createdAt = obj.getLong("createdAt")

                    if (isBookDeleted(mappedBizId, bkName)) continue

                    remoteBookKeys.add("$mappedBizId:${bkName.trim().lowercase()}")

                    val defaultBookMatch = if (existingBooksList.size == 1 && existingTxList.isEmpty() && existingBooksList[0].businessId == mappedBizId) existingBooksList[0] else null
                    val matched = existingBooksList.find { it.businessId == mappedBizId && it.name.equals(bkName, ignoreCase = true) } ?: defaultBookMatch

                    if (matched != null) {
                        bookIdMap[oldId] = matched.id
                        if ((!matched.name.equals(bkName, ignoreCase = true) && bkName.isNotBlank()) || matched.phone != phone) {
                            val updatedBook = matched.copy(name = bkName, phone = phone, isSynced = true)
                            dao.updateBook(updatedBook)
                            val idx = existingBooksList.indexOfFirst { it.id == matched.id }
                            if (idx >= 0) existingBooksList[idx] = updatedBook
                        }
                    } else {
                        val newBook = Book(id = 0, businessId = mappedBizId, name = bkName, phone = phone, createdAt = createdAt, isSynced = true)
                        val newId = dao.insertBook(newBook).toInt()
                        bookIdMap[oldId] = newId
                        existingBooksList.add(newBook.copy(id = newId))
                    }
                }

                // Delete local books that were deleted on remote (strictly scoped to businesses owned by this user; never touch joined/shared workspaces)
                if (!isPartialExport) {
                    val ownedBizIds = existingBizList.filter { localBiz ->
                        val owner = localBiz.userEmail.trim().lowercase()
                        if (isCloudSession) owner == sessionEmail else (owner == sessionEmail || owner.isBlank())
                    }.map { it.id }.toSet()
                    val remoteBookIdsInLocal = bookIdMap.values.toSet()
                    val targetBizIds = if (bizIdMap.isNotEmpty()) bizIdMap.values.toSet().intersect(ownedBizIds) else ownedBizIds
                    val booksToDelete = existingBooksList.filter { localBook ->
                        localBook.isSynced && targetBizIds.contains(localBook.businessId) && !remoteBookIdsInLocal.contains(localBook.id) && !remoteBookKeys.contains("${localBook.businessId}:${localBook.name.trim().lowercase()}")
                    }
                    booksToDelete.forEach { localBook ->
                        dao.deleteBook(localBook)
                        existingBooksList.remove(localBook)
                    }
                }
            }

            // Restore/Merge Parties
            if (root.has("parties")) {
                val partiesArray = root.getJSONArray("parties")
                val remotePartyNames = mutableSetOf<String>()
                for (i in 0 until partiesArray.length()) {
                    val obj = partiesArray.getJSONObject(i)
                    val oldId = obj.getInt("id")
                    val pName = obj.getString("name")
                    val phone = obj.optString("phone", "")
                    val createdAt = obj.getLong("createdAt")

                    if (isPartyDeleted(pName)) continue

                    remotePartyNames.add(pName.trim().lowercase())

                    val matched = existingPartiesList.find { it.name.equals(pName, ignoreCase = true) }
                    if (matched != null) {
                        partyIdMap[oldId] = matched.id
                    } else {
                        val newParty = Party(id = 0, name = pName, phone = phone, createdAt = createdAt)
                        val newId = dao.insertParty(newParty).toInt()
                        partyIdMap[oldId] = newId
                        existingPartiesList.add(newParty.copy(id = newId))
                    }
                }

                // Delete local parties that were deleted on remote (Skip for partial export share)
                if (!isPartialExport) {
                    val remotePartyIdsInLocal = partyIdMap.values.toSet()
                    val partiesToDelete = existingPartiesList.filter { localParty ->
                        !remotePartyIdsInLocal.contains(localParty.id) && !remotePartyNames.contains(localParty.name.trim().lowercase())
                    }
                    partiesToDelete.forEach { localParty ->
                        dao.deleteParty(localParty)
                        existingPartiesList.remove(localParty)
                    }
                }
            }

            // Restore/Merge Transactions
            if (root.has("transactions")) {
                val txArray = root.getJSONArray("transactions")
                val remoteTxTimestamps = mutableSetOf<Long>()
                for (i in 0 until txArray.length()) {
                    val obj = txArray.getJSONObject(i)
                    val oldBookId = obj.getInt("bookId")
                    val mappedBookId = bookIdMap[oldBookId] ?: oldBookId
                    val amount = obj.getDouble("amount")
                    val type = obj.getString("type")
                    val category = obj.getString("category")
                    val paymentMethod = obj.getString("paymentMethod")
                    val remarks = obj.getString("remarks")
                    val timestamp = obj.getLong("timestamp")
                    val receiptUri = if (obj.has("receiptUri")) obj.optString("receiptUri", null) else null

                    if (isTransactionDeleted(timestamp)) continue

                    remoteTxTimestamps.add(timestamp)

                    val matched = existingTxList.find { it.timestamp == timestamp && it.bookId == mappedBookId }
                    if (matched != null) {
                        if (matched.amount != amount || matched.type != type || matched.category != category ||
                            matched.paymentMethod != paymentMethod || matched.remarks != remarks || matched.receiptUri != receiptUri) {
                            val updatedTx = matched.copy(
                                amount = amount,
                                type = type,
                                category = category,
                                paymentMethod = paymentMethod,
                                remarks = remarks,
                                receiptUri = receiptUri,
                                isSynced = true
                            )
                            dao.updateTransaction(updatedTx)
                            val idx = existingTxList.indexOfFirst { it.id == matched.id }
                            if (idx >= 0) existingTxList[idx] = updatedTx
                        }
                    } else {
                        val newTx = Transaction(
                            id = 0,
                            bookId = mappedBookId,
                            amount = amount,
                            type = type,
                            category = category,
                            paymentMethod = paymentMethod,
                            remarks = remarks,
                            timestamp = timestamp,
                            isSynced = true,
                            receiptUri = receiptUri
                        )
                        val newId = dao.insertTransaction(newTx).toInt()
                        existingTxList.add(newTx.copy(id = newId))
                    }
                }

                // Delete local transactions that were deleted on remote (strictly scoped to books inside owned businesses)
                if (!isPartialExport) {
                    val ownedBizIds = existingBizList.filter { localBiz ->
                        val owner = localBiz.userEmail.trim().lowercase()
                        if (isCloudSession) owner == sessionEmail else (owner == sessionEmail || owner.isBlank())
                    }.map { it.id }.toSet()
                    val ownedBookIds = existingBooksList.filter { it.businessId in ownedBizIds }.map { it.id }.toSet()
                    val targetBookIds = if (bookIdMap.isNotEmpty()) bookIdMap.values.toSet().intersect(ownedBookIds) else ownedBookIds
                    val toDelete = existingTxList.filter { localTx ->
                        localTx.isSynced && targetBookIds.contains(localTx.bookId) && !remoteTxTimestamps.contains(localTx.timestamp)
                    }
                    toDelete.forEach { localTx ->
                        dao.deleteTransaction(localTx)
                        existingTxList.remove(localTx)
                    }
                }
            }

            // Restore/Merge Party Transactions
            if (root.has("party_transactions")) {
                val pTxArray = root.getJSONArray("party_transactions")
                val remotePartyTxTimestamps = mutableSetOf<Long>()
                for (i in 0 until pTxArray.length()) {
                    val obj = pTxArray.getJSONObject(i)
                    val oldPartyId = obj.getInt("partyId")
                    val mappedPartyId = partyIdMap[oldPartyId] ?: oldPartyId
                    val amount = obj.getDouble("amount")
                    val type = obj.getString("type")
                    val remarks = obj.getString("remarks")
                    val timestamp = obj.getLong("timestamp")

                    if (isPartyTransactionDeleted(timestamp)) continue

                    remotePartyTxTimestamps.add(timestamp)

                    val matched = existingPartyTxList.find { it.timestamp == timestamp && it.partyId == mappedPartyId }
                    if (matched != null) {
                        if (matched.amount != amount || matched.type != type || matched.remarks != remarks) {
                            val updatedPTx = matched.copy(
                                amount = amount,
                                type = type,
                                remarks = remarks,
                                isSynced = true
                            )
                            dao.insertPartyTransaction(updatedPTx)
                            val idx = existingPartyTxList.indexOfFirst { it.id == matched.id }
                            if (idx >= 0) existingPartyTxList[idx] = updatedPTx
                        }
                    } else {
                        val newPTx = PartyTransaction(
                            id = 0,
                            partyId = mappedPartyId,
                            amount = amount,
                            type = type,
                            remarks = remarks,
                            timestamp = timestamp,
                            isSynced = true
                        )
                        val newId = dao.insertPartyTransaction(newPTx).toInt()
                        existingPartyTxList.add(newPTx.copy(id = newId))
                    }
                }

                // Delete local party transactions deleted on remote (Skip for partial export share)
                if (!isPartialExport) {
                    val targetPartyIds = if (partyIdMap.isNotEmpty()) partyIdMap.values.toSet() else existingPartiesList.map { it.id }.toSet()
                    val toDelete = existingPartyTxList.filter { localPTx ->
                        localPTx.isSynced && targetPartyIds.contains(localPTx.partyId) && !remotePartyTxTimestamps.contains(localPTx.timestamp)
                    }
                    toDelete.forEach { localPTx ->
                        dao.deletePartyTransaction(localPTx)
                        existingPartyTxList.remove(localPTx)
                    }
                }
            }

            // Restore/Merge Team Members
            if (root.has("team_members")) {
                val teamArray = root.getJSONArray("team_members")
                val remoteTeamEmails = mutableSetOf<String>()
                val hasPendingLocalModifications = prefs.getBoolean("user_has_modified_local_db", false) && localModifiedTs > remoteTimestamp
                for (i in 0 until teamArray.length()) {
                    val obj = teamArray.getJSONObject(i)
                    val oldBizId = obj.optInt("businessId", 1)
                    val mappedBizId = bizIdMap[oldBizId] ?: oldBizId
                    val name = obj.getString("name")
                    val email = obj.getString("email")
                    val phone = obj.getString("phone")
                    val role = obj.getString("role")
                    val createdAt = obj.getLong("createdAt")
                    val allowedBookNames = obj.optString("allowedBookNames", "")

                    if (isTeamMemberDeleted(email)) continue

                    remoteTeamEmails.add(email.trim().lowercase())

                    val matched = existingTeamList.find { it.email.equals(email, ignoreCase = true) && it.businessId == mappedBizId }
                    val memberEditTs = prefs.getLong("team_member_edit_ts_${sanitizeDocKey(email)}", 0L)
                    val localTeamChangeIsNewer = memberEditTs > remoteTimestamp || hasPendingLocalModifications

                    if (matched != null) {
                        if (!localTeamChangeIsNewer && (matched.name != name || matched.phone != phone || matched.role != role || matched.allowedBookNames != allowedBookNames)) {
                            val updatedTM = matched.copy(name = name, phone = phone, role = role, allowedBookNames = allowedBookNames)
                            dao.insertTeamMember(updatedTM)
                            val idx = existingTeamList.indexOfFirst { it.id == matched.id }
                            if (idx >= 0) existingTeamList[idx] = updatedTM
                        }
                    } else {
                        val newMember = TeamMember(
                            id = 0,
                            businessId = mappedBizId,
                            name = name,
                            email = email,
                            phone = phone,
                            role = role,
                            createdAt = createdAt,
                            allowedBookNames = allowedBookNames
                        )
                        val newId = dao.insertTeamMember(newMember).toInt()
                        existingTeamList.add(newMember.copy(id = newId))
                    }
                }

                val ownedBizIds = existingBizList.filter { localBiz ->
                    val owner = localBiz.userEmail.trim().lowercase()
                    if (isCloudSession) owner == sessionEmail else (owner == sessionEmail || owner.isBlank())
                }.map { it.id }.toSet()
                val targetBizIds = if (bizIdMap.isNotEmpty()) bizIdMap.values.toSet().intersect(ownedBizIds) else ownedBizIds
                val membersToDelete = existingTeamList.filter { localTM ->
                    val localEmail = localTM.email.trim().lowercase()
                    val memberEditTs = prefs.getLong("team_member_edit_ts_${sanitizeDocKey(localEmail)}", 0L)
                    val recentlyEditedLocally = memberEditTs > remoteTimestamp || hasPendingLocalModifications
                    !recentlyEditedLocally &&
                        targetBizIds.contains(localTM.businessId) &&
                        !remoteTeamEmails.contains(localEmail)
                }
                membersToDelete.forEach { localTM ->
                    dao.deleteTeamMember(localTM)
                    existingTeamList.remove(localTM)
                }
            }

            true
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Failed to parse backup JSON", e)
            false
        }
    }

    // --- Firebase Cloud & Google Drive REST Communications ---

    private fun getFirebaseAuthToken(): String? {
        val cachedToken = prefs.getString("firebase_auth_token", null)
        val tokenExpiry = prefs.getLong("firebase_auth_token_expiry", 0L)
        if (!cachedToken.isNullOrBlank() && System.currentTimeMillis() < tokenExpiry) {
            return cachedToken
        }
        try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$firebaseApiKey"
            val bodyJson = JSONObject().apply {
                put("returnSecureToken", true)
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(bodyJson).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val resStr = response.body?.string() ?: ""
                    val json = JSONObject(resStr)
                    val token = json.optString("idToken", "")
                    val expiresIn = json.optString("expiresIn", "3600").toLongOrNull() ?: 3600L
                    if (token.isNotBlank()) {
                        prefs.edit()
                            .putString("firebase_auth_token", token)
                            .putLong("firebase_auth_token_expiry", System.currentTimeMillis() + (expiresIn - 300) * 1000)
                            .apply()
                        return token
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Failed to fetch firebase auth token", e)
        }
        return null
    }

    private fun calculateJsonDataScore(jsonString: String): Int {
        return try {
            val root = JSONObject(jsonString)
            var score = 0
            if (root.has("businesses")) {
                val arr = root.getJSONArray("businesses")
                score += arr.length() * 10
                for (i in 0 until arr.length()) {
                    val bName = arr.getJSONObject(i).optString("name", "")
                    if (bName.isNotBlank() && bName != "My Business") score += 20
                }
            }
            if (root.has("books")) score += root.getJSONArray("books").length() * 5
            if (root.has("transactions")) score += root.getJSONArray("transactions").length() * 15
            if (root.has("parties")) score += root.getJSONArray("parties").length() * 10
            if (root.has("party_transactions")) score += root.getJSONArray("party_transactions").length() * 10
            if (root.has("team_members")) score += root.getJSONArray("team_members").length() * 10
            score
        } catch (e: Exception) {
            0
        }
    }

    private fun calculateScoreFromCounts(bizCount: Int, booksCount: Int, txCount: Int, partiesCount: Int): Int {
        return bizCount * 10 + booksCount * 5 + txCount * 15 + partiesCount * 10
    }

    // --- Tombstone Tracking for Deletions (Timestamped, Account-Scoped & Cloud-Synced) ---

    private fun getTombstoneScopeKey(): String {
        val clean = getEmail().trim().lowercase().ifBlank { "default" }
        return clean.replace(Regex("[^a-z0-9_]"), "_")
    }

    private fun readTombstoneMap(prefKey: String): MutableMap<String, Long> {
        val result = mutableMapOf<String, Long>()
        val raw = prefs.getString(prefKey, "") ?: ""
        if (raw.isNotBlank()) {
            try {
                val obj = JSONObject(raw)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    result[k] = obj.optLong(k, System.currentTimeMillis())
                }
            } catch (_: Exception) { }
        }
        return result
    }

    private fun writeTombstoneMap(prefKey: String, map: Map<String, Long>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(prefKey, obj.toString()).apply()
    }

    private fun exportTombstonesJson(): JSONObject {
        val scope = getTombstoneScopeKey()
        val obj = JSONObject()
        val bizMapObj = JSONObject()
        readTombstoneMap("tombstone_biz_map_$scope").forEach { (k, v) -> bizMapObj.put(k, v) }
        obj.put("deleted_businesses", bizMapObj)

        val bookMapObj = JSONObject()
        readTombstoneMap("tombstone_book_map_$scope").forEach { (k, v) -> bookMapObj.put(k, v) }
        obj.put("deleted_books", bookMapObj)

        val partyMapObj = JSONObject()
        readTombstoneMap("tombstone_party_map_$scope").forEach { (k, v) -> partyMapObj.put(k, v) }
        obj.put("deleted_parties", partyMapObj)

        val teamMapObj = JSONObject()
        readTombstoneMap("tombstone_team_map_$scope").forEach { (k, v) -> teamMapObj.put(k, v) }
        obj.put("deleted_team_emails", teamMapObj)

        val txArr = JSONArray()
        (prefs.getStringSet("tombstone_tx_ts_$scope", emptySet()) ?: emptySet()).forEach { txArr.put(it) }
        obj.put("deleted_transactions", txArr)

        val ptxArr = JSONArray()
        (prefs.getStringSet("tombstone_ptx_ts_$scope", emptySet()) ?: emptySet()).forEach { ptxArr.put(it) }
        obj.put("deleted_party_transactions", ptxArr)

        return obj
    }

    private fun importTombstonesJson(tObj: JSONObject) {
        val scope = getTombstoneScopeKey()
        fun mergeMap(jsonKey: String, prefKey: String) {
            val remoteObj = tObj.optJSONObject(jsonKey) ?: return
            val localMap = readTombstoneMap(prefKey)
            var changed = false
            val keys = remoteObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val rTs = remoteObj.optLong(k, 0L)
                val lTs = localMap[k] ?: 0L
                if (rTs > lTs) {
                    localMap[k] = rTs
                    changed = true
                }
            }
            if (changed) writeTombstoneMap(prefKey, localMap)
        }
        mergeMap("deleted_businesses", "tombstone_biz_map_$scope")
        mergeMap("deleted_books", "tombstone_book_map_$scope")
        mergeMap("deleted_parties", "tombstone_party_map_$scope")
        mergeMap("deleted_team_emails", "tombstone_team_map_$scope")

        tObj.optJSONArray("deleted_transactions")?.let { arr ->
            val set = prefs.getStringSet("tombstone_tx_ts_$scope", emptySet())?.toMutableSet() ?: mutableSetOf()
            for (i in 0 until arr.length()) set.add(arr.optString(i, ""))
            set.remove("")
            prefs.edit().putStringSet("tombstone_tx_ts_$scope", set).apply()
        }
        tObj.optJSONArray("deleted_party_transactions")?.let { arr ->
            val set = prefs.getStringSet("tombstone_ptx_ts_$scope", emptySet())?.toMutableSet() ?: mutableSetOf()
            for (i in 0 until arr.length()) set.add(arr.optString(i, ""))
            set.remove("")
            prefs.edit().putStringSet("tombstone_ptx_ts_$scope", set).apply()
        }
    }

    fun recordDeletedTransaction(timestamp: Long) {
        val scope = getTombstoneScopeKey()
        val set = prefs.getStringSet("tombstone_tx_ts_$scope", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(timestamp.toString())
        prefs.edit().putStringSet("tombstone_tx_ts_$scope", set).apply()
        markLocalDbModified()
    }

    fun isTransactionDeleted(timestamp: Long): Boolean {
        val scope = getTombstoneScopeKey()
        val scoped = prefs.getStringSet("tombstone_tx_ts_$scope", emptySet())
        if (scoped?.contains(timestamp.toString()) == true) return true
        val legacy = prefs.getStringSet("tombstone_tx_timestamps", emptySet())
        return legacy?.contains(timestamp.toString()) == true
    }

    fun recordDeletedPartyTransaction(timestamp: Long) {
        val scope = getTombstoneScopeKey()
        val set = prefs.getStringSet("tombstone_ptx_ts_$scope", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(timestamp.toString())
        prefs.edit().putStringSet("tombstone_ptx_ts_$scope", set).apply()
        markLocalDbModified()
    }

    fun isPartyTransactionDeleted(timestamp: Long): Boolean {
        val scope = getTombstoneScopeKey()
        val scoped = prefs.getStringSet("tombstone_ptx_ts_$scope", emptySet())
        if (scoped?.contains(timestamp.toString()) == true) return true
        val legacy = prefs.getStringSet("tombstone_ptx_timestamps", emptySet())
        return legacy?.contains(timestamp.toString()) == true
    }

    fun recordDeletedParty(name: String) {
        val cleanName = name.trim().lowercase()
        if (cleanName.isNotBlank()) {
            val scope = getTombstoneScopeKey()
            val map = readTombstoneMap("tombstone_party_map_$scope")
            map[cleanName] = System.currentTimeMillis()
            writeTombstoneMap("tombstone_party_map_$scope", map)
            markLocalDbModified()
        }
    }

    fun removePartyFromTombstones(name: String) {
        val cleanName = name.trim().lowercase()
        val scope = getTombstoneScopeKey()
        val map = readTombstoneMap("tombstone_party_map_$scope")
        if (map.remove(cleanName) != null) {
            writeTombstoneMap("tombstone_party_map_$scope", map)
        }
    }

    fun isPartyDeleted(name: String): Boolean {
        val cleanName = name.trim().lowercase()
        val scope = getTombstoneScopeKey()
        val map = readTombstoneMap("tombstone_party_map_$scope")
        return map.containsKey(cleanName)
    }

    fun recordDeletedBook(bizId: Int, name: String) {
        val key = "$bizId:${name.trim().lowercase()}"
        val scope = getTombstoneScopeKey()
        val map = readTombstoneMap("tombstone_book_map_$scope")
        map[key] = System.currentTimeMillis()
        writeTombstoneMap("tombstone_book_map_$scope", map)
        markLocalDbModified()
    }

    fun removeBookFromTombstones(bizId: Int, name: String) {
        val key = "$bizId:${name.trim().lowercase()}"
        val scope = getTombstoneScopeKey()
        val map = readTombstoneMap("tombstone_book_map_$scope")
        if (map.remove(key) != null) {
            writeTombstoneMap("tombstone_book_map_$scope", map)
        }
    }

    fun isBookDeleted(bizId: Int, name: String): Boolean {
        val key = "$bizId:${name.trim().lowercase()}"
        val scope = getTombstoneScopeKey()
        val map = readTombstoneMap("tombstone_book_map_$scope")
        return map.containsKey(key)
    }

    fun recordDeletedBusiness(name: String, ownerEmail: String = "") {
        val cleanName = name.trim().lowercase()
        if (cleanName.isNotBlank()) {
            val now = System.currentTimeMillis()
            val scope = getTombstoneScopeKey()
            val map = readTombstoneMap("tombstone_biz_map_$scope")
            map[cleanName] = now
            writeTombstoneMap("tombstone_biz_map_$scope", map)

            if (isOfflineBusinessOrEmail(ownerEmail, name) || getEmail().trim().lowercase() == "offline@cashbook.local") {
                val offlineMap = readTombstoneMap("tombstone_offline_biz_map")
                offlineMap[cleanName] = now
                writeTombstoneMap("tombstone_offline_biz_map", offlineMap)
            }
            markLocalDbModified()
        }
    }

    fun removeBusinessFromTombstones(name: String) {
        val cleanName = name.trim().lowercase()
        val scope = getTombstoneScopeKey()
        val map = readTombstoneMap("tombstone_biz_map_$scope")
        if (map.remove(cleanName) != null) {
            writeTombstoneMap("tombstone_biz_map_$scope", map)
        }
        val offlineMap = readTombstoneMap("tombstone_offline_biz_map")
        if (offlineMap.remove(cleanName) != null) {
            writeTombstoneMap("tombstone_offline_biz_map", offlineMap)
        }
        val legacy = prefs.getStringSet("tombstone_biz_names", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (legacy.remove(cleanName)) {
            prefs.edit().putStringSet("tombstone_biz_names", legacy).apply()
        }
    }

    fun isBusinessDeleted(name: String, itemCreatedAt: Long = 0L): Boolean {
        val cleanName = name.trim().lowercase()
        val scope = getTombstoneScopeKey()
        val scopedMap = readTombstoneMap("tombstone_biz_map_$scope")
        val deletedAt = scopedMap[cleanName]
        if (deletedAt != null && (itemCreatedAt <= 0L || deletedAt >= itemCreatedAt)) {
            return true
        }
        val offlineMap = readTombstoneMap("tombstone_offline_biz_map")
        val offlineDeletedAt = offlineMap[cleanName]
        if (offlineDeletedAt != null && (itemCreatedAt <= 0L || offlineDeletedAt >= itemCreatedAt)) {
            return true
        }
        return false
    }

    fun recordDeletedTeamMember(email: String) {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isNotBlank()) {
            val scope = getTombstoneScopeKey()
            val map = readTombstoneMap("tombstone_team_map_$scope")
            map[cleanEmail] = System.currentTimeMillis()
            writeTombstoneMap("tombstone_team_map_$scope", map)
            markLocalDbModified()
        }
    }

    fun removeTeamMemberFromTombstones(email: String) {
        val cleanEmail = email.trim().lowercase()
        val scope = getTombstoneScopeKey()
        val map = readTombstoneMap("tombstone_team_map_$scope")
        if (map.remove(cleanEmail) != null) {
            writeTombstoneMap("tombstone_team_map_$scope", map)
        }
    }

    fun isTeamMemberDeleted(email: String): Boolean {
        val scope = getTombstoneScopeKey()
        val map = readTombstoneMap("tombstone_team_map_$scope")
        return map.containsKey(email.trim().lowercase())
    }

    fun markTeamMemberEdited(email: String) {
        val clean = email.trim().lowercase()
        if (clean.isNotBlank()) {
            prefs.edit()
                .putLong("team_member_edit_ts_${sanitizeDocKey(clean)}", System.currentTimeMillis())
                .commit()
        }
    }

    fun markLocalDbModified() {
        prefs.edit()
            .putBoolean("user_has_modified_local_db", true)
            .putLong("local_db_last_modified_ts", System.currentTimeMillis())
            .commit()
    }

    private fun extractJsonTimestamp(json: String): Long {
        return try {
            val root = JSONObject(json)
            root.optLong("timestamp", 0L)
        } catch (e: Exception) {
            0L
        }
    }

    // --- Cloud Team Invites & Shared Workspace Collaboration Engine ---

    private fun sanitizeDocKey(raw: String): String {
        return raw.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
    }

    private fun invitePrefKey(businessName: String, invitedEmail: String): String {
        return "invite_code_${sanitizeDocKey(businessName)}_${sanitizeDocKey(invitedEmail)}"
    }

    fun getSavedInviteCode(businessName: String, invitedEmail: String): String {
        return prefs.getString(invitePrefKey(businessName, invitedEmail), "") ?: ""
    }

    fun getOrGenerateInviteCode(businessName: String, invitedEmail: String): String {
        val cleanEmail = invitedEmail.trim().lowercase()
        val key = invitePrefKey(businessName, cleanEmail)
        val existing = prefs.getString(key, "") ?: ""
        if (existing.isNotBlank()) return existing

        val prefixRaw = businessName.trim().uppercase().replace(Regex("[^A-Z0-9]"), "").take(4).ifBlank { "CB" }
        val seed = "$prefixRaw:$cleanEmail:${businessName.trim().lowercase()}".hashCode().let { kotlin.math.abs(it) }
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val suffix = buildString {
            var v = seed
            repeat(4) {
                append(alphabet[v % alphabet.length])
                v = (v / alphabet.length) + 7
            }
        }
        val code = "$prefixRaw-$suffix"
        prefs.edit().putString(key, code).apply()
        return code
    }

    private fun sharedWorkspacesPrefKey(email: String = getEmail()): String {
        val clean = email.trim().lowercase().ifBlank { "default" }
        return "shared_workspaces_${sanitizeDocKey(clean)}"
    }

    fun getSharedWorkspaces(email: String = getEmail()): List<SharedWorkspaceRef> {
        val list = mutableListOf<SharedWorkspaceRef>()
        val raw = prefs.getString(sharedWorkspacesPrefKey(email), "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ownerDocId = o.optString("ownerDocId", "")
                val bizName = o.optString("businessName", "")
                if (ownerDocId.isNotBlank() && bizName.isNotBlank()) {
                    list.add(
                        SharedWorkspaceRef(
                            ownerDocId = ownerDocId,
                            ownerEmail = o.optString("ownerEmail", ""),
                            businessName = bizName,
                            businessCreatedAt = o.optLong("businessCreatedAt", 0L),
                            role = o.optString("role", "Data Entry"),
                            inviteCode = o.optString("inviteCode", ""),
                            allowedBookNames = o.optString("allowedBookNames", "")
                        )
                    )
                }
            }
        } catch (_: Exception) { }
        return list
    }

    private fun saveSharedWorkspaces(list: List<SharedWorkspaceRef>, email: String = getEmail()) {
        val arr = JSONArray()
        list.forEach { ref ->
            arr.put(JSONObject().apply {
                put("ownerDocId", ref.ownerDocId)
                put("ownerEmail", ref.ownerEmail)
                put("businessName", ref.businessName)
                put("businessCreatedAt", ref.businessCreatedAt)
                put("role", ref.role)
                put("inviteCode", ref.inviteCode)
                put("allowedBookNames", ref.allowedBookNames)
            })
        }
        prefs.edit().putString(sharedWorkspacesPrefKey(email), arr.toString()).apply()
    }

    fun addOrUpdateSharedWorkspace(ref: SharedWorkspaceRef, email: String = getEmail()) {
        val current = getSharedWorkspaces(email).toMutableList()
        val idx = current.indexOfFirst {
            it.ownerDocId == ref.ownerDocId && it.businessName.equals(ref.businessName, ignoreCase = true)
        }
        if (idx >= 0) {
            current[idx] = ref
        } else {
            current.add(ref)
        }
        saveSharedWorkspaces(current, email)
    }

    fun removeSharedWorkspace(ownerEmail: String, businessName: String, email: String = getEmail()) {
        val current = getSharedWorkspaces(email).toMutableList()
        current.removeAll {
            it.ownerEmail.equals(ownerEmail, ignoreCase = true) && it.businessName.equals(businessName, ignoreCase = true)
        }
        saveSharedWorkspaces(current, email)
    }

    private fun teamInviteToJson(inv: TeamInviteRecord): JSONObject {
        return JSONObject().apply {
            put("inviteCode", inv.inviteCode)
            put("ownerEmail", inv.ownerEmail)
            put("ownerDocId", inv.ownerDocId)
            put("businessName", inv.businessName)
            put("businessCreatedAt", inv.businessCreatedAt)
            put("invitedEmail", inv.invitedEmail)
            put("invitedName", inv.invitedName)
            put("role", inv.role)
            put("status", inv.status)
            put("createdAt", inv.createdAt)
            put("allowedBookNames", inv.allowedBookNames)
        }
    }

    private fun jsonToTeamInvite(obj: JSONObject): TeamInviteRecord? {
        val code = obj.optString("inviteCode", "")
        val ownerDocId = obj.optString("ownerDocId", "")
        val bizName = obj.optString("businessName", "")
        if (code.isBlank() || ownerDocId.isBlank() || bizName.isBlank()) return null
        return TeamInviteRecord(
            inviteCode = code,
            ownerEmail = obj.optString("ownerEmail", ""),
            ownerDocId = ownerDocId,
            businessName = bizName,
            businessCreatedAt = obj.optLong("businessCreatedAt", 0L),
            invitedEmail = obj.optString("invitedEmail", ""),
            invitedName = obj.optString("invitedName", ""),
            role = obj.optString("role", "Data Entry"),
            status = obj.optString("status", "PENDING"),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            allowedBookNames = obj.optString("allowedBookNames", "")
        )
    }

    private fun patchOrPostFirestoreDoc(collection: String, docId: String, payloadJson: JSONObject, idToken: String?): Boolean {
        val projId = getFirebaseProjectId()
        val apiKey = getFirebaseApiKey()
        val body = payloadJson.toString().toRequestBody("application/json".toMediaType())

        val fieldsObj = payloadJson.optJSONObject("fields")
        val maskParams = buildString {
            if (fieldsObj != null) {
                val keys = fieldsObj.keys()
                while (keys.hasNext()) {
                    append("updateMask.fieldPaths=${keys.next()}&")
                }
            }
        }
        val patchUrl = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/$collection/$docId?${maskParams}key=$apiKey"
        val patchBuilder = Request.Builder().url(patchUrl)
        if (!idToken.isNullOrBlank()) patchBuilder.addHeader("Authorization", "Bearer $idToken")

        try {
            client.newCall(patchBuilder.patch(body).build()).execute().use { res ->
                if (res.isSuccessful) return true
                if (res.code == 404) {
                    val postUrl = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/$collection?documentId=$docId&key=$apiKey"
                    val postBuilder = Request.Builder().url(postUrl)
                    if (!idToken.isNullOrBlank()) postBuilder.addHeader("Authorization", "Bearer $idToken")
                    client.newCall(postBuilder.post(body).build()).execute().use { postRes ->
                        return postRes.isSuccessful
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Firestore write error for $collection/$docId", e)
        }
        return false
    }

    private fun fetchFirestoreFields(collection: String, docId: String, idToken: String?): JSONObject? {
        val projId = getFirebaseProjectId()
        val apiKey = getFirebaseApiKey()
        val url = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/$collection/$docId?key=$apiKey"
        val reqBuilder = Request.Builder().url(url)
        if (!idToken.isNullOrBlank()) reqBuilder.addHeader("Authorization", "Bearer $idToken")
        return try {
            client.newCall(reqBuilder.get().build()).execute().use { res ->
                if (res.isSuccessful) {
                    val bodyStr = res.body?.string() ?: ""
                    if (bodyStr.isNotBlank()) JSONObject(bodyStr).optJSONObject("fields") else null
                } else null
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Firestore read error for $collection/$docId", e)
            null
        }
    }

    suspend fun publishTeamInviteCloud(
        business: Business,
        member: TeamMember,
        dao: LedgerDao
    ): Pair<TeamInviteRecord, Boolean> = withContext(Dispatchers.IO) {
        val ownerEmail = business.userEmail.trim().lowercase().ifBlank { getEmail().trim().lowercase() }
        val ownerDocId = "cashbook_" + sanitizeDocKey(ownerEmail)
        val invitedEmail = member.email.trim().lowercase()
        val code = getOrGenerateInviteCode(business.name, invitedEmail.ifBlank { member.name })
        val effectiveAllowedBooks = if (isRoleFullBusinessAccess(member.role)) "" else member.allowedBookNames
        val invite = TeamInviteRecord(
            inviteCode = code,
            ownerEmail = ownerEmail,
            ownerDocId = ownerDocId,
            businessName = business.name,
            businessCreatedAt = business.createdAt,
            invitedEmail = invitedEmail,
            invitedName = member.name.trim(),
            role = member.role,
            status = "PENDING",
            createdAt = System.currentTimeMillis(),
            allowedBookNames = effectiveAllowedBooks
        )

        if (!isRealCloudAccount()) {
            return@withContext Pair(invite, false)
        }

        // First push owner's latest business snapshot so the shared business is immediately downloadable
        try {
            syncWithCloud(dao)
        } catch (_: Exception) { }

        val idToken = getFirebaseAuthToken()
        val codeDocKey = code.trim().uppercase().replace(Regex("[^A-Z0-9_-]"), "_")
        val inviteJsonStr = teamInviteToJson(invite).toString()
        val invitePayload = buildFirestoreFields(
            "inviteCode" to invite.inviteCode,
            "ownerEmail" to invite.ownerEmail,
            "ownerDocId" to invite.ownerDocId,
            "businessName" to invite.businessName,
            "businessCreatedAt" to invite.businessCreatedAt,
            "invitedEmail" to invite.invitedEmail,
            "invitedName" to invite.invitedName,
            "role" to invite.role,
            "status" to invite.status,
            "createdAt" to invite.createdAt,
            "allowedBookNames" to invite.allowedBookNames,
            "inviteJson" to inviteJsonStr
        )

        val savedDirect = patchOrPostFirestoreDoc("team_invites", codeDocKey, invitePayload, idToken)
        val savedMirror = patchOrPostFirestoreDoc("cashbooks", "invite_$codeDocKey", invitePayload, idToken)

        var savedUserIdx = false
        if (invitedEmail.contains("@")) {
            val emailKey = sanitizeDocKey(invitedEmail)
            val existingInvites = fetchInvitesForEmailCloud(invitedEmail).toMutableList()
            existingInvites.removeAll {
                it.ownerDocId == invite.ownerDocId && it.businessName.equals(invite.businessName, ignoreCase = true)
            }
            existingInvites.add(invite)
            val arr = JSONArray()
            existingInvites.forEach { arr.put(teamInviteToJson(it)) }
            val userInvPayload = buildFirestoreFields(
                "invitedEmail" to invitedEmail,
                "invitesJson" to arr.toString(),
                "updatedAt" to System.currentTimeMillis()
            )
            val a = patchOrPostFirestoreDoc("user_invites", emailKey, userInvPayload, idToken)
            val b = patchOrPostFirestoreDoc("cashbooks", "userinvites_$emailKey", userInvPayload, idToken)
            savedUserIdx = a || b
        }

        Pair(invite, savedDirect || savedMirror || savedUserIdx)
    }

    suspend fun revokeTeamInviteCloud(business: Business, invitedEmail: String) = withContext(Dispatchers.IO) {
        if (!isRealCloudAccount()) return@withContext
        val cleanInvited = invitedEmail.trim().lowercase()
        val ownerEmail = business.userEmail.trim().lowercase().ifBlank { getEmail().trim().lowercase() }
        val ownerDocId = "cashbook_" + sanitizeDocKey(ownerEmail)
        val code = getSavedInviteCode(business.name, cleanInvited)
        val idToken = getFirebaseAuthToken()

        if (code.isNotBlank()) {
            val codeDocKey = code.trim().uppercase().replace(Regex("[^A-Z0-9_-]"), "_")
            val revokedPayload = buildFirestoreFields(
                "inviteCode" to code,
                "status" to "REVOKED",
                "inviteJson" to JSONObject().apply {
                    put("inviteCode", code)
                    put("ownerEmail", ownerEmail)
                    put("ownerDocId", ownerDocId)
                    put("businessName", business.name)
                    put("invitedEmail", cleanInvited)
                    put("status", "REVOKED")
                }.toString()
            )
            patchOrPostFirestoreDoc("team_invites", codeDocKey, revokedPayload, idToken)
            patchOrPostFirestoreDoc("cashbooks", "invite_$codeDocKey", revokedPayload, idToken)
            prefs.edit().remove(invitePrefKey(business.name, cleanInvited)).apply()
        }

        if (cleanInvited.contains("@")) {
            val emailKey = sanitizeDocKey(cleanInvited)
            val existingInvites = fetchInvitesForEmailCloud(cleanInvited).toMutableList()
            existingInvites.removeAll {
                it.ownerDocId == ownerDocId && it.businessName.equals(business.name, ignoreCase = true)
            }
            val arr = JSONArray()
            existingInvites.forEach { arr.put(teamInviteToJson(it)) }
            val userInvPayload = buildFirestoreFields(
                "invitedEmail" to cleanInvited,
                "invitesJson" to arr.toString(),
                "updatedAt" to System.currentTimeMillis()
            )
            patchOrPostFirestoreDoc("user_invites", emailKey, userInvPayload, idToken)
            patchOrPostFirestoreDoc("cashbooks", "userinvites_$emailKey", userInvPayload, idToken)
        }
    }

    suspend fun lookupInviteByCodeCloud(rawCode: String): TeamInviteRecord? = withContext(Dispatchers.IO) {
        val codeDocKey = rawCode.trim().uppercase().replace(Regex("[^A-Z0-9_-]"), "_")
        if (codeDocKey.length < 3) return@withContext null
        val idToken = getFirebaseAuthToken()

        val fields = fetchFirestoreFields("team_invites", codeDocKey, idToken)
            ?: fetchFirestoreFields("cashbooks", "invite_$codeDocKey", idToken)
            ?: return@withContext null

        val status = fields.optJSONObject("status")?.optString("stringValue", "PENDING") ?: "PENDING"
        if (status.equals("REVOKED", ignoreCase = true)) return@withContext null

        val rawJson = fields.optJSONObject("inviteJson")?.optString("stringValue", "") ?: ""
        if (rawJson.isNotBlank()) {
            try {
                val parsed = jsonToTeamInvite(JSONObject(rawJson))
                if (parsed != null && !parsed.status.equals("REVOKED", ignoreCase = true)) return@withContext parsed
            } catch (_: Exception) { }
        }

        val code = fields.optJSONObject("inviteCode")?.optString("stringValue", "") ?: codeDocKey
        val ownerEmail = fields.optJSONObject("ownerEmail")?.optString("stringValue", "") ?: ""
        val ownerDocId = fields.optJSONObject("ownerDocId")?.optString("stringValue", "") ?: ""
        val businessName = fields.optJSONObject("businessName")?.optString("stringValue", "") ?: ""
        if (ownerDocId.isBlank() || businessName.isBlank()) return@withContext null

        TeamInviteRecord(
            inviteCode = code,
            ownerEmail = ownerEmail,
            ownerDocId = ownerDocId,
            businessName = businessName,
            businessCreatedAt = fields.optJSONObject("businessCreatedAt")?.optString("integerValue", "0")?.toLongOrNull() ?: 0L,
            invitedEmail = fields.optJSONObject("invitedEmail")?.optString("stringValue", "") ?: "",
            invitedName = fields.optJSONObject("invitedName")?.optString("stringValue", "") ?: "",
            role = fields.optJSONObject("role")?.optString("stringValue", "Data Entry") ?: "Data Entry",
            status = status,
            createdAt = fields.optJSONObject("createdAt")?.optString("integerValue", "0")?.toLongOrNull() ?: System.currentTimeMillis(),
            allowedBookNames = fields.optJSONObject("allowedBookNames")?.optString("stringValue", "") ?: ""
        )
    }

    suspend fun fetchInvitesForEmailCloud(email: String): List<TeamInviteRecord> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        if (!cleanEmail.contains("@")) return@withContext emptyList()
        val emailKey = sanitizeDocKey(cleanEmail)
        val idToken = getFirebaseAuthToken()

        val fields = fetchFirestoreFields("user_invites", emailKey, idToken)
            ?: fetchFirestoreFields("cashbooks", "userinvites_$emailKey", idToken)
            ?: return@withContext emptyList()

        val rawArr = fields.optJSONObject("invitesJson")?.optString("stringValue", "[]") ?: "[]"
        val result = mutableListOf<TeamInviteRecord>()
        try {
            val arr = JSONArray(rawArr)
            for (i in 0 until arr.length()) {
                val inv = jsonToTeamInvite(arr.getJSONObject(i))
                if (inv != null && !inv.status.equals("REVOKED", ignoreCase = true)) {
                    result.add(inv)
                }
            }
        } catch (_: Exception) { }
        result
    }

    suspend fun redeemInviteCodeCloud(
        rawCode: String,
        acceptorEmail: String,
        dao: LedgerDao
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanAcceptor = acceptorEmail.trim().lowercase()
        val invite = lookupInviteByCodeCloud(rawCode)
            ?: return@withContext Pair(false, "Invite code '${rawCode.trim().uppercase()}' not found or has been revoked. Ask the business owner to re-send the invite.")

        if (invite.ownerEmail.equals(cleanAcceptor, ignoreCase = true)) {
            return@withContext Pair(false, "You are already the owner of '${invite.businessName}'.")
        }

        if (invite.invitedEmail.isNotBlank() && cleanAcceptor.isNotBlank() && !invite.invitedEmail.equals(cleanAcceptor, ignoreCase = true)) {
            return@withContext Pair(false, "This invite was issued for '${invite.invitedEmail}'. Please Sign In or Sign Up with '${invite.invitedEmail}' to join '${invite.businessName}'.")
        }

        removeBusinessFromTombstones(invite.businessName)
        val ref = SharedWorkspaceRef(
            ownerDocId = invite.ownerDocId,
            ownerEmail = invite.ownerEmail,
            businessName = invite.businessName,
            businessCreatedAt = invite.businessCreatedAt,
            role = invite.role,
            inviteCode = invite.inviteCode,
            allowedBookNames = invite.allowedBookNames
        )
        addOrUpdateSharedWorkspace(ref, cleanAcceptor.ifBlank { getEmail() })

        val idToken = getFirebaseAuthToken()
        val acceptedInvite = invite.copy(status = "ACCEPTED")
        val codeDocKey = invite.inviteCode.trim().uppercase().replace(Regex("[^A-Z0-9_-]"), "_")
        val acceptedPayload = buildFirestoreFields(
            "inviteCode" to acceptedInvite.inviteCode,
            "ownerEmail" to acceptedInvite.ownerEmail,
            "ownerDocId" to acceptedInvite.ownerDocId,
            "businessName" to acceptedInvite.businessName,
            "businessCreatedAt" to acceptedInvite.businessCreatedAt,
            "invitedEmail" to acceptedInvite.invitedEmail,
            "invitedName" to acceptedInvite.invitedName,
            "role" to acceptedInvite.role,
            "status" to "ACCEPTED",
            "createdAt" to acceptedInvite.createdAt,
            "allowedBookNames" to acceptedInvite.allowedBookNames,
            "inviteJson" to teamInviteToJson(acceptedInvite).toString()
        )
        patchOrPostFirestoreDoc("team_invites", codeDocKey, acceptedPayload, idToken)
        patchOrPostFirestoreDoc("cashbooks", "invite_$codeDocKey", acceptedPayload, idToken)

        val pulled = syncSingleSharedWorkspace(ref, cleanAcceptor, dao, idToken)
        if (pulled) {
            Pair(true, "Joined '${invite.businessName}' as ${invite.role}!")
        } else {
            Pair(false, "Invite verified, but owner's cloud data is not synced yet. Ask the owner to tap 'Verify & Sync Cloud' and retry.")
        }
    }

    private suspend fun syncSingleSharedWorkspace(
        ref: SharedWorkspaceRef,
        sessionEmail: String,
        dao: LedgerDao,
        idToken: String?
    ): Boolean {
        val ownerFields = fetchFirestoreFields("cashbooks", ref.ownerDocId, idToken) ?: return false
        val ownerJsonStr = ownerFields.optJSONObject("dataJson")?.optString("stringValue", "") ?: ""
        if (ownerJsonStr.isBlank()) return false

        val ownerRoot = try { JSONObject(ownerJsonStr) } catch (_: Exception) { return false }
        val ownerBizArr = ownerRoot.optJSONArray("businesses") ?: return false

        // Find the specific shared business in the owner's doc
        var sharedBizObj: JSONObject? = null
        for (i in 0 until ownerBizArr.length()) {
            val b = ownerBizArr.getJSONObject(i)
            val bName = b.optString("name", "")
            val bCreated = b.optLong("createdAt", 0L)
            if (bName.equals(ref.businessName, ignoreCase = true) || (ref.businessCreatedAt > 0L && bCreated == ref.businessCreatedAt)) {
                sharedBizObj = b
                break
            }
        }

        // Verify business still exists and teammate is still on team roster
        if (sharedBizObj == null) {
            val localMatch = dao.getAllBusinessesList().find {
                it.name.equals(ref.businessName, ignoreCase = true) && it.userEmail.equals(ref.ownerEmail, ignoreCase = true)
            }
            if (localMatch != null) {
                dao.deleteBooksForBusiness(localMatch.id)
                dao.deleteTeamMembersForBusiness(localMatch.id)
                dao.deleteBusiness(localMatch)
            }
            removeSharedWorkspace(ref.ownerEmail, ref.businessName, sessionEmail)
            return false
        }

        val remoteBizOldId = sharedBizObj.getInt("id")
        val remoteBizName = sharedBizObj.getString("name")
        val ownerTeamArr = ownerRoot.optJSONArray("team_members") ?: JSONArray()
        val scopedTeamArr = JSONArray()
        var activeRoleFromOwner: String? = null
        var activeAllowedBooksFromOwner = ref.allowedBookNames
        for (i in 0 until ownerTeamArr.length()) {
            val tm = ownerTeamArr.getJSONObject(i)
            if (tm.optInt("businessId", -1) == remoteBizOldId) {
                scopedTeamArr.put(tm)
                if (tm.optString("email", "").trim().equals(sessionEmail, ignoreCase = true)) {
                    activeRoleFromOwner = tm.optString("role", ref.role)
                    activeAllowedBooksFromOwner = tm.optString("allowedBookNames", "")
                }
            }
        }

        if (activeRoleFromOwner == null) {
            // Owner removed this team member
            val localMatch = dao.getAllBusinessesList().find {
                it.name.equals(remoteBizName, ignoreCase = true) && it.userEmail.equals(ref.ownerEmail, ignoreCase = true)
            }
            if (localMatch != null) {
                dao.deleteBooksForBusiness(localMatch.id)
                dao.deleteTeamMembersForBusiness(localMatch.id)
                dao.deleteBusiness(localMatch)
            }
            removeSharedWorkspace(ref.ownerEmail, ref.businessName, sessionEmail)
            return false
        }

        val effectiveAllowedBooks = if (isRoleFullBusinessAccess(activeRoleFromOwner)) "" else activeAllowedBooksFromOwner
        if (activeRoleFromOwner != ref.role || remoteBizName != ref.businessName || effectiveAllowedBooks != ref.allowedBookNames) {
            addOrUpdateSharedWorkspace(
                ref.copy(
                    role = activeRoleFromOwner,
                    businessName = remoteBizName,
                    allowedBookNames = effectiveAllowedBooks
                ),
                sessionEmail
            )
        }

        // Import owner tombstones for deleted books/transactions so deleted entries vanish cleanly
        val ownerTombstones = ownerRoot.optJSONObject("tombstones")
        val ownerDeletedTxTs = mutableSetOf<String>()
        ownerTombstones?.optJSONArray("deleted_transactions")?.let { arr ->
            for (i in 0 until arr.length()) ownerDeletedTxTs.add(arr.optString(i, ""))
        }

        val ownerBooksArr = ownerRoot.optJSONArray("books") ?: JSONArray()
        val ownerTxArr = ownerRoot.optJSONArray("transactions") ?: JSONArray()

        val remoteSharedBookOldIds = mutableSetOf<Int>()
        val remoteSharedBookNames = mutableSetOf<String>()
        val ownerBookOldIdToName = mutableMapOf<Int, String>()
        var maxRemoteBookId = 0
        for (i in 0 until ownerBooksArr.length()) {
            val bk = ownerBooksArr.getJSONObject(i)
            val id = bk.getInt("id")
            if (id > maxRemoteBookId) maxRemoteBookId = id
            if (bk.optInt("businessId", -1) == remoteBizOldId) {
                val bName = bk.getString("name")
                if (isBookAllowedForMember(bName, effectiveAllowedBooks, activeRoleFromOwner)) {
                    remoteSharedBookOldIds.add(id)
                    remoteSharedBookNames.add(bName.trim().lowercase())
                    ownerBookOldIdToName[id] = bName.trim().lowercase()
                }
            }
        }

        // 1. PULL: Ensure local Room DB has ONLY the permitted shared Books & Transactions, plus Business and TeamMembers
        var localBiz = dao.getAllBusinessesList().find {
            it.name.equals(remoteBizName, ignoreCase = true) && it.userEmail.equals(ref.ownerEmail, ignoreCase = true)
        }
        val localBizId = if (localBiz != null) {
            if (!localBiz.isSynced) {
                localBiz = localBiz.copy(isSynced = true)
                dao.updateBusiness(localBiz)
            }
            localBiz.id
        } else {
            val created = Business(
                id = 0,
                name = remoteBizName,
                userEmail = ref.ownerEmail.trim().lowercase(),
                createdAt = sharedBizObj.optLong("createdAt", System.currentTimeMillis()),
                isSynced = true
            )
            dao.insertBusiness(created).toInt()
        }

        val existingLocalBooks = dao.getAllBooksList().filter { it.businessId == localBizId }.toMutableList()
        val bookOldToLocalId = mutableMapOf<Int, Int>()
        for (i in 0 until ownerBooksArr.length()) {
            val bkObj = ownerBooksArr.getJSONObject(i)
            if (bkObj.optInt("businessId", -1) != remoteBizOldId) continue
            val oldBookId = bkObj.getInt("id")
            if (!remoteSharedBookOldIds.contains(oldBookId)) continue
            val bkName = bkObj.getString("name")
            val bkPhone = bkObj.optString("phone", "")
            val bkCreatedAt = bkObj.optLong("createdAt", System.currentTimeMillis())

            val matched = existingLocalBooks.find { it.name.equals(bkName, ignoreCase = true) }
            if (matched != null) {
                bookOldToLocalId[oldBookId] = matched.id
                if (!matched.isSynced || matched.phone != bkPhone) {
                    val upd = matched.copy(phone = bkPhone, isSynced = true)
                    dao.updateBook(upd)
                    val idx = existingLocalBooks.indexOfFirst { it.id == matched.id }
                    if (idx >= 0) existingLocalBooks[idx] = upd
                }
            } else {
                val nb = Book(0, localBizId, bkName, bkPhone, bkCreatedAt, true)
                val newId = dao.insertBook(nb).toInt()
                bookOldToLocalId[oldBookId] = newId
                existingLocalBooks.add(nb.copy(id = newId))
            }
        }

        // Remove synced local books under this shared biz that owner deleted remotely
        existingLocalBooks.filter { it.isSynced && !remoteSharedBookNames.contains(it.name.trim().lowercase()) }
            .forEach { delBk ->
                dao.deleteBook(delBk)
                existingLocalBooks.remove(delBk)
            }

        val localBookIds = existingLocalBooks.map { it.id }.toSet()
        val existingLocalTxs = dao.getAllTransactionsList().filter { it.bookId in localBookIds }.toMutableList()
        val remoteTxTsSet = mutableSetOf<Long>()

        for (i in 0 until ownerTxArr.length()) {
            val txObj = ownerTxArr.getJSONObject(i)
            val oldBookId = txObj.getInt("bookId")
            if (oldBookId !in remoteSharedBookOldIds) continue
            val mappedBookId = bookOldToLocalId[oldBookId] ?: continue
            val ts = txObj.getLong("timestamp")
            if (ownerDeletedTxTs.contains(ts.toString())) continue
            remoteTxTsSet.add(ts)

            val amount = txObj.getDouble("amount")
            val type = txObj.getString("type")
            val category = txObj.getString("category")
            val paymentMethod = txObj.getString("paymentMethod")
            val remarks = txObj.getString("remarks")
            val receiptUri = if (txObj.has("receiptUri")) txObj.optString("receiptUri", null) else null

            val matched = existingLocalTxs.find { it.timestamp == ts && it.bookId == mappedBookId }
            if (matched != null) {
                if (matched.amount != amount || matched.type != type || matched.category != category ||
                    matched.paymentMethod != paymentMethod || matched.remarks != remarks || !matched.isSynced) {
                    val upd = matched.copy(
                        amount = amount,
                        type = type,
                        category = category,
                        paymentMethod = paymentMethod,
                        remarks = remarks,
                        receiptUri = receiptUri,
                        isSynced = true
                    )
                    dao.updateTransaction(upd)
                    val idx = existingLocalTxs.indexOfFirst { it.id == matched.id }
                    if (idx >= 0) existingLocalTxs[idx] = upd
                }
            } else {
                val ntx = Transaction(0, mappedBookId, amount, type, category, paymentMethod, remarks, ts, true, receiptUri)
                val newId = dao.insertTransaction(ntx).toInt()
                existingLocalTxs.add(ntx.copy(id = newId))
            }
        }

        // Remove previously-synced local transactions deleted by the owner
        existingLocalTxs.filter { it.isSynced && !remoteTxTsSet.contains(it.timestamp) }
            .forEach { delTx ->
                dao.deleteTransaction(delTx)
                existingLocalTxs.remove(delTx)
            }

        // Sync Team Members list for this shared business
        val existingLocalTeam = dao.getAllTeamMembersList().filter { it.businessId == localBizId }.toMutableList()
        val remoteTmEmails = mutableSetOf<String>()
        for (i in 0 until scopedTeamArr.length()) {
            val tmObj = scopedTeamArr.getJSONObject(i)
            val tmEmail = tmObj.optString("email", "")
            val tmName = tmObj.optString("name", "")
            val tmPhone = tmObj.optString("phone", "")
            val tmRole = tmObj.optString("role", "Data Entry")
            val tmCreatedAt = tmObj.optLong("createdAt", System.currentTimeMillis())
            val tmAllowedBooks = tmObj.optString("allowedBookNames", "")
            remoteTmEmails.add(tmEmail.trim().lowercase())
            val matched = existingLocalTeam.find { it.email.equals(tmEmail, ignoreCase = true) }
            if (matched != null) {
                if (matched.name != tmName || matched.phone != tmPhone || matched.role != tmRole || matched.allowedBookNames != tmAllowedBooks) {
                    dao.insertTeamMember(matched.copy(name = tmName, phone = tmPhone, role = tmRole, allowedBookNames = tmAllowedBooks))
                }
            } else {
                dao.insertTeamMember(TeamMember(0, localBizId, tmName, tmEmail, tmPhone, tmRole, tmCreatedAt, tmAllowedBooks))
            }
        }
        existingLocalTeam.filter { !remoteTmEmails.contains(it.email.trim().lowercase()) }
            .forEach { dao.deleteTeamMember(it) }

        // 2. PUSH: If collaborator added new books or transactions locally (isSynced == false), merge ONLY into permitted books in owner's doc
        val allowedLocalBookIds = existingLocalBooks
            .filter { isBookAllowedForMember(it.name, effectiveAllowedBooks, activeRoleFromOwner) }
            .map { it.id }
            .toSet()
        val unsyncedLocalBooks = existingLocalBooks.filter {
            !it.isSynced && isBookAllowedForMember(it.name, effectiveAllowedBooks, activeRoleFromOwner)
        }
        val unsyncedLocalTxs = existingLocalTxs.filter { !it.isSynced && it.bookId in allowedLocalBookIds }
        if (unsyncedLocalBooks.isNotEmpty() || unsyncedLocalTxs.isNotEmpty()) {
            var ownerDocModified = false
            val localBookIdToOwnerOldId = mutableMapOf<Int, Int>()
            for ((ownerBkOldId, localBkId) in bookOldToLocalId) {
                localBookIdToOwnerOldId[localBkId] = ownerBkOldId
            }

            for (lb in unsyncedLocalBooks) {
                val existingEntryIdx = (0 until ownerBooksArr.length()).firstOrNull { idx ->
                    val o = ownerBooksArr.getJSONObject(idx)
                    o.optInt("businessId", -1) == remoteBizOldId && o.optString("name", "").equals(lb.name, ignoreCase = true)
                }
                if (existingEntryIdx != null) {
                    localBookIdToOwnerOldId[lb.id] = ownerBooksArr.getJSONObject(existingEntryIdx).getInt("id")
                } else {
                    maxRemoteBookId += 1
                    val newOwnerBookId = maxRemoteBookId
                    ownerBooksArr.put(JSONObject().apply {
                        put("id", newOwnerBookId)
                        put("businessId", remoteBizOldId)
                        put("name", lb.name)
                        put("phone", lb.phone)
                        put("createdAt", lb.createdAt)
                        put("isSynced", true)
                    })
                    localBookIdToOwnerOldId[lb.id] = newOwnerBookId
                    ownerDocModified = true
                }
            }

            var maxRemoteTxId = 0
            val existingOwnerTxKeys = mutableSetOf<String>()
            for (i in 0 until ownerTxArr.length()) {
                val o = ownerTxArr.getJSONObject(i)
                val id = o.optInt("id", 0)
                if (id > maxRemoteTxId) maxRemoteTxId = id
                existingOwnerTxKeys.add("${o.optInt("bookId", 0)}:${o.optLong("timestamp", 0L)}")
            }

            for (lt in unsyncedLocalTxs) {
                val targetOwnerBookId = localBookIdToOwnerOldId[lt.bookId] ?: continue
                if (ownerDeletedTxTs.contains(lt.timestamp.toString())) continue
                val key = "$targetOwnerBookId:${lt.timestamp}"
                if (!existingOwnerTxKeys.contains(key)) {
                    maxRemoteTxId += 1
                    ownerTxArr.put(JSONObject().apply {
                        put("id", maxRemoteTxId)
                        put("bookId", targetOwnerBookId)
                        put("amount", lt.amount)
                        put("type", lt.type)
                        put("category", lt.category)
                        put("paymentMethod", lt.paymentMethod)
                        put("remarks", lt.remarks)
                        put("timestamp", lt.timestamp)
                        put("isSynced", true)
                        if (lt.receiptUri != null) put("receiptUri", lt.receiptUri)
                    })
                    existingOwnerTxKeys.add(key)
                    ownerDocModified = true
                } else if (!activeRoleFromOwner.equals("Data Entry", ignoreCase = true)) {
                    // Partner/Admin/Boss edited an existing entry -> update matching owner tx
                    for (idx in 0 until ownerTxArr.length()) {
                        val existingObj = ownerTxArr.getJSONObject(idx)
                        if (existingObj.optInt("bookId", -1) == targetOwnerBookId &&
                            existingObj.optLong("timestamp", -1L) == lt.timestamp
                        ) {
                            existingObj.put("amount", lt.amount)
                            existingObj.put("type", lt.type)
                            existingObj.put("category", lt.category)
                            existingObj.put("paymentMethod", lt.paymentMethod)
                            existingObj.put("remarks", lt.remarks)
                            if (lt.receiptUri != null) existingObj.put("receiptUri", lt.receiptUri)
                            ownerDocModified = true
                            break
                        }
                    }
                }
            }

            if (ownerDocModified) {
                ownerRoot.put("books", ownerBooksArr)
                ownerRoot.put("transactions", ownerTxArr)
                ownerRoot.put("timestamp", System.currentTimeMillis())
                val pushPayload = buildFirestoreFields(
                    "userEmail" to ref.ownerEmail,
                    "dataJson" to ownerRoot.toString(2),
                    "updatedAt" to System.currentTimeMillis()
                )
                if (patchOrPostFirestoreDoc("cashbooks", ref.ownerDocId, pushPayload, idToken)) {
                    unsyncedLocalBooks.forEach { dao.updateBook(it.copy(isSynced = true)) }
                    unsyncedLocalTxs.forEach { dao.updateTransaction(it.copy(isSynced = true)) }
                }
            }
        }

        return true
    }

    suspend fun syncInvitedWorkspacesForUser(userEmail: String, dao: LedgerDao): Pair<Int, Boolean> = withContext(Dispatchers.IO) {
        val cleanEmail = userEmail.trim().lowercase()
        if (cleanEmail.isBlank() || cleanEmail.endsWith(".local")) return@withContext Pair(0, false)
        val idToken = getFirebaseAuthToken()

        // 1. Auto-discover pending/accepted invites addressed to this user's email
        val remoteInvites = fetchInvitesForEmailCloud(cleanEmail)
        var newlyAccepted = false
        for (inv in remoteInvites) {
            if (inv.ownerEmail.equals(cleanEmail, ignoreCase = true)) continue
            removeBusinessFromTombstones(inv.businessName)
            val existingRef = getSharedWorkspaces(cleanEmail).firstOrNull {
                it.ownerDocId == inv.ownerDocId && it.businessName.equals(inv.businessName, ignoreCase = true)
            }
            if (existingRef == null) {
                newlyAccepted = true
            }
            addOrUpdateSharedWorkspace(
                SharedWorkspaceRef(
                    ownerDocId = inv.ownerDocId,
                    ownerEmail = inv.ownerEmail,
                    businessName = inv.businessName,
                    businessCreatedAt = inv.businessCreatedAt,
                    role = inv.role,
                    inviteCode = inv.inviteCode,
                    allowedBookNames = inv.allowedBookNames
                ),
                cleanEmail
            )
            if (inv.status == "PENDING") {
                val codeDocKey = inv.inviteCode.trim().uppercase().replace(Regex("[^A-Z0-9_-]"), "_")
                val accepted = inv.copy(status = "ACCEPTED")
                val acceptedPayload = buildFirestoreFields(
                    "status" to "ACCEPTED",
                    "inviteJson" to teamInviteToJson(accepted).toString()
                )
                patchOrPostFirestoreDoc("team_invites", codeDocKey, acceptedPayload, idToken)
                patchOrPostFirestoreDoc("cashbooks", "invite_$codeDocKey", acceptedPayload, idToken)
            }
        }

        // 2. Sync each joined shared workspace safely (pull updates, push collaborator additions)
        val refs = getSharedWorkspaces(cleanEmail)
        var syncedCount = 0
        for (ref in refs) {
            if (syncSingleSharedWorkspace(ref, cleanEmail, dao, idToken)) {
                syncedCount++
            }
        }
        Pair(syncedCount, newlyAccepted)
    }

    suspend fun syncWithFirebaseCloud(dao: LedgerDao): String = withContext(Dispatchers.IO) {
        if (!isRealCloudAccount()) {
            return@withContext "Offline Local Mode — Saved in local SQLite database"
        }
        var lastHttpError: String? = null
        try {
            val userEmail = getEmail().trim().lowercase().ifBlank { "default_user" }
            val username = prefs.getString("username", "")?.trim()?.lowercase() ?: ""
            
            val candidateDocIds = mutableListOf<String>()
            val primaryDocId = "cashbook_" + userEmail.replace(Regex("[^a-zA-Z0-9_]"), "_")
            candidateDocIds.add(primaryDocId)

            if (userEmail.contains("@")) {
                val prefix = userEmail.substringBefore("@").replace(Regex("[^a-zA-Z0-9_]"), "_")
                val pDocId = "cashbook_$prefix"
                if (!candidateDocIds.contains(pDocId)) candidateDocIds.add(pDocId)
            }
            if (username.isNotBlank()) {
                val unClean = username.replace(Regex("[^a-zA-Z0-9_]"), "_")
                val uDocId = "cashbook_$unClean"
                if (!candidateDocIds.contains(uDocId)) candidateDocIds.add(uDocId)
            }

            if (!isRealCloudAccount() && !candidateDocIds.contains("cashbook_default_user")) {
                candidateDocIds.add("cashbook_default_user")
            }

            val idToken = getFirebaseAuthToken()
            val candidateJsons = mutableListOf<Triple<String, Long, String>>() // (docId, timestamp, json)
            var primaryCloudJson: String? = null
            var bestCloudJson: String? = null
            var maxCloudTimestamp = 0L

            val projId = getFirebaseProjectId()
            val apiKey = getFirebaseApiKey()

            // 1. Check Firestore ONLY for docs owned by this user (never pull or overwrite other teammates' personal backups)
            val ownedDocIds = mutableListOf<String>()
            ownedDocIds.add(primaryDocId)

            for (docId in candidateDocIds) {
                try {
                    val getUrl = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/cashbooks/$docId?key=$apiKey"
                    val getReqBuilder = Request.Builder().url(getUrl)
                    if (!idToken.isNullOrBlank()) {
                        getReqBuilder.addHeader("Authorization", "Bearer $idToken")
                    }
                    val getReq = getReqBuilder.get().build()
                    client.newCall(getReq).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string()
                            if (!bodyStr.isNullOrBlank()) {
                                val root = JSONObject(bodyStr)
                                val fields = root.optJSONObject("fields")
                                if (fields != null) {
                                    val docOwnerEmail = fields.optJSONObject("userEmail")?.optString("stringValue", "")?.trim()?.lowercase() ?: ""
                                    val json = fields.optJSONObject("dataJson")?.optString("stringValue", "")
                                    if (!json.isNullOrBlank()) {
                                        val parsedAccEmail = try {
                                            JSONObject(json).optJSONObject("account_info")?.optString("user_email", "")?.trim()?.lowercase() ?: ""
                                        } catch (_: Exception) { "" }
                                        val effectiveDocEmail = docOwnerEmail.ifBlank { parsedAccEmail }
                                        val isCompatibleOwner = docId == primaryDocId ||
                                            effectiveDocEmail.isBlank() ||
                                            effectiveDocEmail == userEmail
                                        if (isCompatibleOwner) {
                                            if (!ownedDocIds.contains(docId)) ownedDocIds.add(docId)
                                            val ts = extractJsonTimestamp(json)
                                            candidateJsons.add(Triple(docId, ts, json))
                                            if (docId == primaryDocId) {
                                                primaryCloudJson = json
                                            }
                                            if (ts > maxCloudTimestamp) {
                                                maxCloudTimestamp = ts
                                                bestCloudJson = json
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            if (lastHttpError == null && response.code != 404) {
                                lastHttpError = "Firestore HTTP ${response.code}"
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GoogleDriveSyncManager", "Firestore candidate fetch error for $docId", e)
                }
            }

            // 2. Check local prefs vault for primaryDocId only if cloud returned nothing
            if (candidateJsons.isEmpty()) {
                val vaultJson = prefs.getString("cloud_vault_$primaryDocId", "")
                if (!vaultJson.isNullOrBlank()) {
                    val ts = extractJsonTimestamp(vaultJson)
                    candidateJsons.add(Triple(primaryDocId, ts, vaultJson))
                    if (ts > maxCloudTimestamp) {
                        maxCloudTimestamp = ts
                        bestCloudJson = vaultJson
                    }
                }
            }

            var restored = false
            // Merge valid owned snapshots chronologically (oldest -> newest)
            if (candidateJsons.isNotEmpty()) {
                val sortedDocs = candidateJsons.sortedBy { it.second }
                for ((_, _, docJson) in sortedDocs) {
                    if (restoreDatabase(docJson, dao)) {
                        restored = true
                    }
                }
                if (primaryCloudJson != null && bestCloudJson != primaryCloudJson) {
                    if (restoreDatabase(primaryCloudJson!!, dao)) {
                        restored = true
                    }
                }
                if (restored) {
                    prefs.edit().putBoolean("has_performed_initial_cloud_restore_$primaryDocId", true).apply()
                }
            }

            // 2b. Discover & sync any invited/shared team workspaces for this email
            val (sharedSyncedCount, newlyJoinedInvite) = syncInvitedWorkspacesForUser(userEmail, dao)
            if (newlyJoinedInvite || sharedSyncedCount > 0) {
                restored = restored || newlyJoinedInvite
            }

            // 3. Serialize user's own businesses after restoration/merge
            val finalJson = serializeDatabaseFromDao(dao)

            prefs.edit()
                .putString("cloud_vault_$primaryDocId", finalJson)
                .putLong("cloud_vault_ts_$primaryDocId", System.currentTimeMillis())
                .apply()

            // 4. Update ONLY this user's own docIds in Firestore
            val payload = buildFirestoreFields(
                "userEmail" to userEmail,
                "dataJson" to finalJson,
                "updatedAt" to System.currentTimeMillis()
            )
            val body = payload.toString().toRequestBody("application/json".toMediaType())

            var syncSuccess = false
            var syncSource = "Firebase Cloud"

            for (cDocId in ownedDocIds) {
                val patchUrl = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/cashbooks/$cDocId?updateMask.fieldPaths=userEmail&updateMask.fieldPaths=dataJson&updateMask.fieldPaths=updatedAt&key=$apiKey"
                val patchReqBuilder = Request.Builder().url(patchUrl)
                if (!idToken.isNullOrBlank()) {
                    patchReqBuilder.addHeader("Authorization", "Bearer $idToken")
                }
                val patchReq = patchReqBuilder.patch(body).build()

                try {
                    client.newCall(patchReq).execute().use { response ->
                        if (response.isSuccessful) {
                            syncSuccess = true
                            syncSource = "Firebase Firestore"
                        } else if (response.code == 404) {
                            val postUrl = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/cashbooks?documentId=$cDocId&key=$apiKey"
                            val postReqBuilder = Request.Builder().url(postUrl)
                            if (!idToken.isNullOrBlank()) {
                                postReqBuilder.addHeader("Authorization", "Bearer $idToken")
                            }
                            val postReq = postReqBuilder.post(body).build()
                            client.newCall(postReq).execute().use { postRes ->
                                if (postRes.isSuccessful) {
                                    syncSuccess = true
                                    syncSource = "Firebase Firestore"
                                } else {
                                    lastHttpError = "Firestore HTTP ${postRes.code}"
                                }
                            }
                        } else {
                            lastHttpError = "Firestore HTTP ${response.code}"
                        }
                    }
                } catch (e: Exception) {
                    lastHttpError = "Firestore Error: ${e.localizedMessage}"
                    Log.e("GoogleDriveSyncManager", "Firestore patch error", e)
                }
            }

            if (syncSuccess) {
                val syncTs = System.currentTimeMillis()
                prefs.edit()
                    .putBoolean("user_has_modified_local_db", false)
                    .putLong("local_db_last_synced_ts", syncTs)
                    .apply()
                dao.markAllBusinessesSynced()
                dao.markAllBooksSynced()
                dao.markAllTransactionsSynced()
                dao.markAllPartyTransactionsSynced()
                return@withContext if (restored) "🟢 Restored & Synced from $syncSource ($userEmail)" else "🟢 Synced with $syncSource ($userEmail)"
            }

            // Fallback to Realtime Database REST
            try {
                val rtdbUrl = "https://$projId-default-rtdb.firebaseio.com/cashbooks/$primaryDocId.json" + if (!idToken.isNullOrBlank()) "?auth=$idToken" else "?key=$apiKey"
                val rtdbBody = finalJson.toRequestBody("application/json".toMediaType())
                val rtdbReq = Request.Builder().url(rtdbUrl).put(rtdbBody).build()
                client.newCall(rtdbReq).execute().use { rtdbRes ->
                    if (rtdbRes.isSuccessful) {
                        dao.markAllBusinessesSynced()
                        dao.markAllBooksSynced()
                        dao.markAllTransactionsSynced()
                        dao.markAllPartyTransactionsSynced()
                        return@withContext if (restored) "🟢 Restored & Synced (Realtime DB)" else "🟢 Synced with Realtime DB"
                    } else {
                        if (lastHttpError == null) lastHttpError = "Realtime DB HTTP ${rtdbRes.code}"
                    }
                }
            } catch (e: Exception) {
                Log.e("GoogleDriveSyncManager", "Realtime DB fallback failed", e)
            }

            return@withContext "🔴 Firebase Sync Failed: ${lastHttpError ?: "Permission Denied / Connection Failed"}"
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error in syncWithFirebaseCloud", e)
            return@withContext "🔴 Firebase Error: ${e.localizedMessage ?: "Connection Failed"}"
        }
    }

    suspend fun syncWithGoogleDrive(dao: LedgerDao): String = withContext(Dispatchers.IO) {
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
                    restoreDatabase(cloudBackupStr!!, dao)
                    val latestLocalJson = serializeDatabaseFromDao(dao)
                    uploadToDrive(token, latestLocalJson, fileId!!)
                    dao.markAllBusinessesSynced()
                    dao.markAllBooksSynced()
                    dao.markAllTransactionsSynced()
                    dao.markAllPartyTransactionsSynced()
                    return@withContext "Synced: Merged and backed up to Google Drive."
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

    suspend fun pingCloudConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val projId = getFirebaseProjectId()
            val apiKey = getFirebaseApiKey()
            val firestoreUrl = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/users?key=$apiKey"
            val req = Request.Builder()
                .url(firestoreUrl)
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    Pair(true, "Firebase Live Firestore Connected (200 OK)")
                } else if (response.code == 404) {
                    Pair(false, "Firebase Database Not Found (HTTP 404)")
                } else if (response.code == 403) {
                    Pair(false, "Firebase Permission Error (HTTP 403 Forbidden)")
                } else if (response.code == 401 || response.code == 400) {
                    Pair(false, "Firebase Auth Error (HTTP ${response.code})")
                } else {
                    Pair(false, "Firebase Error (HTTP ${response.code})")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Firebase Disconnected (${e.localizedMessage ?: "No Internet"})")
        }
    }

    suspend fun syncWithCloud(dao: LedgerDao): String = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isRealCloudAccount()) {
                return@withContext "Offline Local Mode — Saved in local SQLite database"
            }

            if (isUserSignedIn()) {
                return@withContext try {
                    syncWithFirebaseCloud(dao)
                } catch (e: Exception) {
                    "Firebase Sync Error: ${e.message}"
                }
            }

            if (hasGoogleDriveToken()) {
                return@withContext try {
                    syncWithGoogleDrive(dao)
                } catch (e: Exception) {
                    "Drive Sync Error: ${e.message}"
                }
            }

            return@withContext "Offline — Connect Cloud Account to enable Auto Sync"
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
                        if (email.isNotBlank()) {
                            val un = email.substringBefore("@")
                            registerUser(if (name.isNotBlank()) name else un, email, un, "google_oauth_pass")
                        }
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
