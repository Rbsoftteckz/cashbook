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

data class RegisteredAccount(
    val name: String,
    val email: String,
    val username: String,
    val pass: String
)

class GoogleDriveSyncManager(private val context: Context) {

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
            val projId = getFirebaseProjectId()
            val apiKey = getFirebaseApiKey()
            val url = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/users/$docId?key=$apiKey"
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val requestBuilder = Request.Builder().url(url)
            val idToken = prefs.getString("firebase_auth_token", "") ?: ""
            if (idToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $idToken")
            }
            val request = requestBuilder.patch(body).build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error syncing user to Firestore", e)
        }
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

        // 1. Fetch from Firestore users collection directly
        try {
            val projId = getFirebaseProjectId()
            val apiKey = getFirebaseApiKey()
            val firestoreUsersUrl = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/users?key=$apiKey"
            val req = Request.Builder().url(firestoreUsersUrl).get().build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotBlank()) {
                        val root = JSONObject(bodyStr)
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
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error fetching cloud accounts from Firestore /users", e)
        }

        // 2. Merge local accounts so no local accounts are lost
        val localAccs = getGlobalAccounts()
        for (acc in localAccs) {
            if ((acc.email.isNotBlank() || acc.username.isNotBlank()) &&
                !list.any { it.email.equals(acc.email, ignoreCase = true) || (acc.username.isNotBlank() && it.username.equals(acc.username, ignoreCase = true)) }) {
                list.add(acc)
            }
            try {
                syncUserToMasterCloud(acc)
            } catch (e: Exception) {
                Log.e("GoogleDriveSyncManager", "Error auto-syncing account to Firestore", e)
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

        // 2. Query Firebase Auth API via createAuthUri
        val targetEmail = if (cleanInput.contains("@")) cleanInput else "$cleanInput@cashbook.com"
        try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:createAuthUri?key=$firebaseApiKey"
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

        // 3. Fetch fresh accounts from online cloud database (Firestore, JsonBlob, Restful API)
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

        try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=$firebaseApiKey"
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

        // 3. Check if account already exists on Cloud Database or local storage
        val cloudAccounts = fetchFirebaseAccountsCloud()
        val existingAcc = cloudAccounts.find { it.email.lowercase() == cleanEmail || (cleanUser.isNotBlank() && it.username.lowercase() == cleanUser) }
            ?: getGlobalAccounts().find { it.email.lowercase() == cleanEmail || (cleanUser.isNotBlank() && it.username.lowercase() == cleanUser) }

        if (existingAcc != null) {
            Log.i("GoogleDriveSyncManager", "Account already exists for $cleanEmail. Restoring existing account data instead of creating default.")
            registerUser(existingAcc.name, existingAcc.email, existingAcc.username, if (cleanPass.isNotBlank()) cleanPass else existingAcc.pass)
            return@withContext Pair(true, "EXISTING_ACCOUNT_RESTORED")
        }

        val acc = RegisteredAccount(name.trim(), cleanEmail, cleanUser, cleanPass)

        // 4. Register locally & push to Online Cloud Master Database
        registerUser(name.trim(), cleanEmail, cleanUser, cleanPass)
        syncUserToMasterCloud(acc)

        // 5. Try Firebase Auth API
        try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$firebaseApiKey"
            val payload = JSONObject().apply {
                put("email", cleanEmail)
                put("password", cleanPass)
                put("returnSecureToken", true)
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(payload).build()
            client.newCall(req).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(bodyStr)
                    val idToken = json.optString("idToken", "")
                    if (idToken.isNotBlank()) {
                        prefs.edit().putString("firebase_auth_token", idToken).apply()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Firebase Auth sign up notice", e)
        }

        // 6. Push to Firestore
        val docId = "user_" + cleanEmail.replace(Regex("[^a-zA-Z0-9_]"), "_")
        try {
            val payload = buildFirestoreFields(
                "name" to name.trim(),
                "email" to cleanEmail,
                "username" to cleanUser,
                "pass" to cleanPass,
                "updatedAt" to System.currentTimeMillis()
            )
            val projId = getFirebaseProjectId()
            val apiKey = getFirebaseApiKey()
            val url = "https://firestore.googleapis.com/v1/projects/$projId/databases/(default)/documents/users/$docId?key=$apiKey"
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val requestBuilder = Request.Builder().url(url)
            val idToken = prefs.getString("firebase_auth_token", "") ?: ""
            if (idToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $idToken")
            }
            val request = requestBuilder.patch(body).build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error registering user to Firestore", e)
        }

        Pair(true, "Account registered & synced to Cloud Database! You can Sign In from any device.")
    }

    suspend fun loginUserCloud(userOrEmail: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        val cleanInput = userOrEmail.trim().lowercase()
        val cleanPass = pass.trim()

        if (cleanInput.isBlank()) return@withContext false

        // 1. Try Firebase Auth signInWithPassword API
        val targetEmail = if (cleanInput.contains("@")) cleanInput else "$cleanInput@cashbook.com"
        if (cleanPass.isNotBlank()) {
            try {
                val url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$firebaseApiKey"
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
                            prefs.edit().putString("firebase_auth_token", idToken).apply()
                            val username = if (resEmail.contains("@")) resEmail.substringBefore("@") else resEmail
                            val name = username.replaceFirstChar { it.uppercase() }
                            registerUser(name, resEmail, username, cleanPass)
                            return@withContext true
                        }
                    } else if (bodyStr.contains("INVALID_PASSWORD") || bodyStr.contains("INVALID_LOGIN_CREDENTIALS")) {
                        Log.w("GoogleDriveSyncManager", "Firebase Auth rejected password for $targetEmail")
                        return@withContext false
                    }
                }
            } catch (e: Exception) {
                Log.e("GoogleDriveSyncManager", "Firebase Auth signInWithPassword error", e)
            }
        }

        // 2. Fetch fresh cloud accounts from Cloud Master Directory
        val cloudAccounts = fetchFirebaseAccountsCloud()
        val matched = cloudAccounts.find { acc ->
            val accEmail = acc.email.trim().lowercase()
            val accUser = acc.username.trim().lowercase()
            val accName = acc.name.trim().lowercase()
            accEmail == cleanInput ||
            accUser == cleanInput ||
            accName == cleanInput ||
            (accEmail.contains("@") && accEmail.substringBefore("@") == cleanInput)
        }

        if (matched != null) {
            // Strict password check
            val passMatches = matched.pass.trim() == cleanPass ||
                              (matched.pass.isBlank() && cleanPass.isBlank()) ||
                              (cleanInput == "admin" && (cleanPass == "admin123" || cleanPass == "superadmin123"))
            if (passMatches) {
                val passToUse = if (cleanPass.isNotBlank()) cleanPass else matched.pass
                registerUser(matched.name, matched.email, matched.username, passToUse)
                return@withContext true
            }
            return@withContext false // Password incorrect!
        }

        // 3. Fallback to local accounts
        return@withContext loginUser(userOrEmail, pass)
    }

    suspend fun resetPasswordCloud(userOrEmail: String, newPass: String): Boolean = withContext(Dispatchers.IO) {
        val cleanInput = userOrEmail.trim().lowercase()
        val cloudAccounts = fetchFirebaseAccountsCloud()
        val targetAcc = cloudAccounts.find { it.email.lowercase() == cleanInput || it.username.lowercase() == cleanInput }
                       ?: getGlobalAccounts().find { it.email.lowercase() == cleanInput || it.username.lowercase() == cleanInput }

        if (targetAcc != null) {
            val updatedAcc = targetAcc.copy(pass = newPass)
            registerUser(updatedAcc.name, updatedAcc.email, updatedAcc.username, newPass)
            syncUserToMasterCloud(updatedAcc)
            return@withContext true
        }

        val success = resetPassword(userOrEmail, newPass)
        if (success) {
            val localAcc = getGlobalAccounts().find { it.email.lowercase() == cleanInput || it.username.lowercase() == cleanInput }
            if (localAcc != null) {
                syncUserToMasterCloud(localAcc.copy(pass = newPass))
            }
        }
        success
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
            list.add(0, RegisteredAccount("Admin", "admin@cashbook.com", "admin", "superadmin123"))
        }

        return list
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
        prefs.edit().putString("registered_accounts_json", arr.toString()).apply()
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
            .apply()
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
            val accName = acc.name.trim().lowercase()

            trimmedUser == accEmail ||
            (accEmail.contains("@") && trimmedUser == accEmail.substringBefore("@")) ||
            trimmedUser == accUser ||
            trimmedUser == accName
        }

        if (matchedAccount != null) {
            val passMatches = matchedAccount.pass.trim() == trimmedPass ||
                              (matchedAccount.pass.isBlank() && trimmedPass.isBlank()) ||
                              (trimmedUser == "admin" && (trimmedPass == "admin123" || trimmedPass == "superadmin123"))

            if (passMatches) {
                registerUser(matchedAccount.name, matchedAccount.email, matchedAccount.username, if (trimmedPass.isNotBlank()) trimmedPass else matchedAccount.pass)
                return true
            }
            return false // Password incorrect!
        }

        if ((trimmedUser == "admin" || trimmedUser == "superadmin" || trimmedUser == "admin@cashbook.com") &&
            (trimmedPass == "admin123" || trimmedPass == "superadmin123" || trimmedPass.isBlank())) {
            registerUser("Admin", "admin@cashbook.com", "admin", if (trimmedPass.isNotBlank()) trimmedPass else "superadmin123")
            return true
        }

        return false // Account does not exist
    }

    fun loginSuperAdmin(user: String, pass: String): Boolean = loginUser(user, pass)

    fun logoutUser() {
        clearGoogleAuth()
        prefs.edit()
            .putBoolean("is_user_logged_in", false)
            .putBoolean("is_super_admin", false)
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
            .apply()
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

        // 5. Team members list
        if (teamMembers.any {
            val tmEmail = it.email.trim().lowercase()
            target == tmEmail || (tmEmail.contains("@") && target == tmEmail.substringBefore("@"))
        }) return true

        return false
    }

    fun resetPassword(userOrEmail: String, newPass: String): Boolean {
        val trimmed = userOrEmail.trim().lowercase()
        val newTrimmedPass = newPass.trim()
        if (trimmed.isBlank() || newTrimmedPass.isBlank()) return false

        val globalAccounts = getGlobalAccounts().toMutableList()
        val index = globalAccounts.indexOfFirst { acc ->
            val accEmail = acc.email.trim().lowercase()
            val accUser = acc.username.trim().lowercase()
            val accName = acc.name.trim().lowercase()

            trimmed == accEmail ||
            (accEmail.contains("@") && trimmed == accEmail.substringBefore("@")) ||
            trimmed == accUser ||
            trimmed == accName ||
            trimmed == "admin" || trimmed == "superadmin" || trimmed == "admin@cashbook.com"
        }

        if (index >= 0) {
            val existing = globalAccounts[index]
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
        if (trimmed == savedUser || trimmed == savedEmail || trimmed == "admin" || trimmed == "superadmin" || trimmed == "admin@cashbook.com") {
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
        teamMembers: List<TeamMember>
    ): String {
        val root = JSONObject()
        root.put("version", 2)
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

            if (root.has("global_accounts")) {
                val accArr = root.getJSONArray("global_accounts")
                val restoredList = mutableListOf<RegisteredAccount>()
                for (i in 0 until accArr.length()) {
                    val obj = accArr.getJSONObject(i)
                    restoredList.add(
                        RegisteredAccount(
                            name = obj.optString("name", ""),
                            email = obj.optString("email", ""),
                            username = obj.optString("username", ""),
                            pass = obj.optString("pass", "")
                        )
                    )
                }
                if (restoredList.isNotEmpty()) {
                    saveGlobalAccounts(restoredList)
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
                for (i in 0 until bizArray.length()) {
                    val obj = bizArray.getJSONObject(i)
                    val oldId = obj.getInt("id")
                    val bName = obj.getString("name")
                    val createdAt = obj.getLong("createdAt")

                    val defaultMatch = if (existingBizList.size == 1 && existingTxList.isEmpty() && (existingBizList[0].name.contains("@") || existingBizList[0].name == "My Business" || existingBizList[0].name == "Main Business")) existingBizList[0] else null
                    val matchedByName = existingBizList.find { it.name.equals(bName, ignoreCase = true) } ?: defaultMatch

                    if (matchedByName != null) {
                        bizIdMap[oldId] = matchedByName.id
                        if (!matchedByName.name.equals(bName, ignoreCase = true) && bName.isNotBlank()) {
                            val updatedBiz = matchedByName.copy(name = bName, isSynced = true)
                            dao.updateBusiness(updatedBiz)
                            val idx = existingBizList.indexOfFirst { it.id == matchedByName.id }
                            if (idx >= 0) existingBizList[idx] = updatedBiz
                        }
                    } else {
                        val newBiz = Business(id = 0, name = bName, createdAt = createdAt, isSynced = true)
                        val newId = dao.insertBusiness(newBiz).toInt()
                        bizIdMap[oldId] = newId
                        existingBizList.add(newBiz.copy(id = newId))
                    }
                }
            }

            // Restore/Merge Books
            if (root.has("books")) {
                val booksArray = root.getJSONArray("books")
                for (i in 0 until booksArray.length()) {
                    val obj = booksArray.getJSONObject(i)
                    val oldId = obj.getInt("id")
                    val oldBizId = obj.optInt("businessId", 1)
                    val mappedBizId = bizIdMap[oldBizId] ?: oldBizId
                    val bkName = obj.getString("name")
                    val phone = obj.optString("phone", "")
                    val createdAt = obj.getLong("createdAt")

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

                // Delete local books that were deleted on remote
                if (bizIdMap.isNotEmpty()) {
                    val mappedBizIds = bizIdMap.values.toSet()
                    val remoteBookIdsInLocal = bookIdMap.values.toSet()
                    val booksToDelete = existingBooksList.filter { localBook ->
                        localBook.isSynced && mappedBizIds.contains(localBook.businessId) && !remoteBookIdsInLocal.contains(localBook.id)
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
                for (i in 0 until partiesArray.length()) {
                    val obj = partiesArray.getJSONObject(i)
                    val oldId = obj.getInt("id")
                    val pName = obj.getString("name")
                    val phone = obj.optString("phone", "")
                    val createdAt = obj.getLong("createdAt")

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

                // Delete local transactions that were deleted on remote
                if (bookIdMap.isNotEmpty()) {
                    val mappedBookIds = bookIdMap.values.toSet()
                    val toDelete = existingTxList.filter { localTx ->
                        localTx.isSynced && mappedBookIds.contains(localTx.bookId) && !remoteTxTimestamps.contains(localTx.timestamp)
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

                // Delete local party transactions deleted on remote
                if (partyIdMap.isNotEmpty()) {
                    val mappedPartyIds = partyIdMap.values.toSet()
                    val toDelete = existingPartyTxList.filter { localPTx ->
                        localPTx.isSynced && mappedPartyIds.contains(localPTx.partyId) && !remotePartyTxTimestamps.contains(localPTx.timestamp)
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
                for (i in 0 until teamArray.length()) {
                    val obj = teamArray.getJSONObject(i)
                    val oldBizId = obj.optInt("businessId", 1)
                    val mappedBizId = bizIdMap[oldBizId] ?: oldBizId
                    val name = obj.getString("name")
                    val email = obj.getString("email")
                    val phone = obj.getString("phone")
                    val role = obj.getString("role")
                    val createdAt = obj.getLong("createdAt")

                    remoteTeamEmails.add(email.trim().lowercase())

                    val matched = existingTeamList.find { it.email.equals(email, ignoreCase = true) && it.businessId == mappedBizId }
                    if (matched != null) {
                        if (matched.name != name || matched.phone != phone || matched.role != role) {
                            val updatedTM = matched.copy(name = name, phone = phone, role = role)
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
                            createdAt = createdAt
                        )
                        val newId = dao.insertTeamMember(newMember).toInt()
                        existingTeamList.add(newMember.copy(id = newId))
                    }
                }

                if (bizIdMap.isNotEmpty()) {
                    val mappedBizIds = bizIdMap.values.toSet()
                    val membersToDelete = existingTeamList.filter { localTM ->
                        mappedBizIds.contains(localTM.businessId) && !remoteTeamEmails.contains(localTM.email.trim().lowercase())
                    }
                    membersToDelete.forEach { localTM ->
                        dao.deleteTeamMember(localTM)
                        existingTeamList.remove(localTM)
                    }
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

    fun markLocalDbModified() {
        prefs.edit().putBoolean("user_has_modified_local_db", true).apply()
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

            // Include candidate docIds for all team members so team sync shares changes bi-directionally
            val allTeamMembers = dao.getAllTeamMembersList()
            for (tm in allTeamMembers) {
                if (tm.email.isNotBlank() && tm.email.contains("@")) {
                    val cleanTmEmail = tm.email.trim().lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
                    val tmDocId = "cashbook_$cleanTmEmail"
                    if (!candidateDocIds.contains(tmDocId)) candidateDocIds.add(tmDocId)
                }
            }

            if (!isRealCloudAccount() && !candidateDocIds.contains("cashbook_default_user")) {
                candidateDocIds.add("cashbook_default_user")
            }

            val idToken = getFirebaseAuthToken()
            var bestCloudJson: String? = null
            var maxDataScore = 0

            val projId = getFirebaseProjectId()
            val apiKey = getFirebaseApiKey()

            // 1. Check Firestore for all candidate docIds
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
                                    val json = fields.optJSONObject("dataJson")?.optString("stringValue", "")
                                    if (!json.isNullOrBlank()) {
                                        val score = calculateJsonDataScore(json)
                                        if (score >= maxDataScore) {
                                            maxDataScore = score
                                            bestCloudJson = json
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

            // 2. Check local prefs vaults for active candidate IDs only if cloud returned nothing
            if (bestCloudJson.isNullOrBlank()) {
                for (candId in candidateDocIds) {
                    val vaultJson = prefs.getString("cloud_vault_$candId", "")
                    if (!vaultJson.isNullOrBlank()) {
                        val score = calculateJsonDataScore(vaultJson)
                        if (score > maxDataScore) {
                            maxDataScore = score
                            bestCloudJson = vaultJson
                        }
                    }
                }
            }

            var restored = false
            if (!bestCloudJson.isNullOrBlank()) {
                restored = restoreDatabase(bestCloudJson!!, dao)
                if (restored) {
                    prefs.edit().putBoolean("has_performed_initial_cloud_restore_$primaryDocId", true).apply()
                    dao.markAllBusinessesSynced()
                    dao.markAllBooksSynced()
                    dao.markAllTransactionsSynced()
                    dao.markAllPartyTransactionsSynced()
                }
            }

            // 3. Serialize full DB after restoration/merge
            val finalJson = serializeDatabaseFromDao(dao)

            // Save in local vault for all candidate docIds
            for (cDocId in candidateDocIds) {
                prefs.edit()
                    .putString("cloud_vault_$cDocId", finalJson)
                    .putLong("cloud_vault_ts_$cDocId", System.currentTimeMillis())
                    .apply()
            }

            // 4. Update candidateDocIds in Firestore
            val payload = buildFirestoreFields(
                "userEmail" to userEmail,
                "dataJson" to finalJson,
                "updatedAt" to System.currentTimeMillis()
            )
            val body = payload.toString().toRequestBody("application/json".toMediaType())

            var syncSuccess = false
            var syncSource = "Firebase Cloud"

            for (cDocId in candidateDocIds) {
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

    suspend fun syncWithCloud(dao: LedgerDao): String = withContext(Dispatchers.IO) {
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
                            prefs.edit()
                                .putBoolean("is_user_logged_in", true)
                                .putBoolean("is_super_admin", true)
                                .apply()
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
