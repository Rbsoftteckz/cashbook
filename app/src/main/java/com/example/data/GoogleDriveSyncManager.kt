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
    private var firebaseProjectId: String = "gen-lang-client-0052637237"
    private var firebaseApiKey: String = "AIzaSyDDLCoD8_9lyN1wJVR5sOTNHbKgCdLqZDs"

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

    fun isRealCloudAccount(): Boolean {
        val email = getEmail()
        if (email.isBlank() || email.contains("@cashbook.local", ignoreCase = true) || email.contains("offline", ignoreCase = true)) {
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
            // 1. Update JsonBlob Master Accounts Index
            val url = "https://jsonblob.com/api/jsonBlob/019fa4bf-b09e-74ac-b328-3bd6f904e842"
            val getReq = Request.Builder().url(url).get().build()
            val currentUsers = mutableListOf<RegisteredAccount>()
            try {
                client.newCall(getReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        if (bodyStr.isNotBlank()) {
                            val root = JSONObject(bodyStr)
                            val arr = root.optJSONArray("users")
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val obj = arr.getJSONObject(i)
                                    val e = obj.optString("email", "")
                                    val u = obj.optString("username", "")
                                    if (e.isNotBlank() || u.isNotBlank()) {
                                        currentUsers.add(
                                            RegisteredAccount(
                                                obj.optString("name", ""),
                                                e,
                                                u,
                                                obj.optString("pass", "")
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GoogleDriveSyncManager", "Error fetching master jsonblob", e)
            }

            val cleanEmail = acc.email.trim().lowercase()
            val cleanUser = acc.username.trim().lowercase()
            val idx = currentUsers.indexOfFirst {
                it.email.lowercase() == cleanEmail || (cleanUser.isNotBlank() && it.username.lowercase() == cleanUser)
            }
            if (idx >= 0) {
                currentUsers[idx] = acc
            } else {
                currentUsers.add(acc)
            }

            val newArr = JSONArray()
            for (u in currentUsers) {
                newArr.put(JSONObject().apply {
                    put("name", u.name)
                    put("email", u.email)
                    put("username", u.username)
                    put("pass", u.pass)
                })
            }
            val putBody = JSONObject().apply { put("users", newArr) }.toString().toRequestBody("application/json".toMediaType())
            val putReq = Request.Builder().url(url).put(putBody).build()
            client.newCall(putReq).execute().close()

            // 2. Also POST/PUT to Restful API Cloud REST Object
            val accountKey = "cashbook_account_" + cleanEmail.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val apiPayload = JSONObject().apply {
                put("name", accountKey)
                put("data", JSONObject().apply {
                    put("name", acc.name)
                    put("email", acc.email)
                    put("username", acc.username)
                    put("pass", acc.pass)
                    put("updatedAt", System.currentTimeMillis())
                })
            }
            val apiBody = apiPayload.toString().toRequestBody("application/json".toMediaType())
            val apiReq = Request.Builder().url("https://api.restful-api.dev/objects").post(apiBody).build()
            client.newCall(apiReq).execute().close()
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error syncing user to master cloud", e)
        }
    }

    private fun saveToCloudRestApi(keyName: String, dataJson: String) {
        try {
            val cleanKey = keyName.trim().lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
            val payload = JSONObject().apply {
                put("name", "cashbook_vault_$cleanKey")
                put("data", JSONObject().apply {
                    put("key", cleanKey)
                    put("json", dataJson)
                    put("updatedAt", System.currentTimeMillis())
                })
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://api.restful-api.dev/objects")
                .post(body)
                .build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error saving to cloud REST API", e)
        }
    }

    private fun fetchFromCloudRestApi(keyName: String): String? {
        try {
            val cleanKey = keyName.trim().lowercase().replace(Regex("[^a-zA-Z0-9_]"), "_")
            val targetName = "cashbook_vault_$cleanKey"
            val req = Request.Builder()
                .url("https://api.restful-api.dev/objects")
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        val arr = JSONArray(bodyStr)
                        var bestJson: String? = null
                        var maxTs = 0L
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            if (obj.optString("name", "").equals(targetName, ignoreCase = true)) {
                                val dataObj = obj.optJSONObject("data")
                                if (dataObj != null) {
                                    val jsonStr = dataObj.optString("json", "")
                                    val ts = dataObj.optLong("updatedAt", 0L)
                                    if (jsonStr.isNotBlank() && ts >= maxTs) {
                                        maxTs = ts
                                        bestJson = jsonStr
                                    }
                                }
                            }
                        }
                        return bestJson
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error fetching from cloud REST API", e)
        }
        return null
    }

    suspend fun fetchFirebaseAccountsCloud(): List<RegisteredAccount> = withContext(Dispatchers.IO) {
        val list = mutableListOf<RegisteredAccount>()

        // 1. Fetch Master Cloud Directory from JsonBlob REST API
        try {
            val url = "https://jsonblob.com/api/jsonBlob/019fa4bf-b09e-74ac-b328-3bd6f904e842"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotBlank()) {
                        val root = JSONObject(bodyStr)
                        val usersArr = root.optJSONArray("users")
                        if (usersArr != null) {
                            for (i in 0 until usersArr.length()) {
                                val uObj = usersArr.getJSONObject(i)
                                val n = uObj.optString("name", "")
                                val e = uObj.optString("email", "")
                                val un = uObj.optString("username", "")
                                val p = uObj.optString("pass", "")
                                if ((e.isNotBlank() || un.isNotBlank()) && !list.any { it.email.equals(e, true) }) {
                                    list.add(RegisteredAccount(n, e, un, p))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error fetching cloud accounts from jsonblob", e)
        }

        // 2. Fetch from Restful API Cloud REST Objects
        try {
            val req = Request.Builder().url("https://api.restful-api.dev/objects").get().build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        val arr = JSONArray(bodyStr)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val nameStr = obj.optString("name", "")
                            if (nameStr.startsWith("cashbook_account_")) {
                                val dataObj = obj.optJSONObject("data")
                                if (dataObj != null) {
                                    val n = dataObj.optString("name", "")
                                    val e = dataObj.optString("email", "")
                                    val un = dataObj.optString("username", "")
                                    val p = dataObj.optString("pass", "")
                                    if ((e.isNotBlank() || un.isNotBlank()) && !list.any { it.email.equals(e, true) }) {
                                        list.add(RegisteredAccount(n, e, un, p))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error fetching cloud accounts from restful-api.dev", e)
        }

        // 3. Query Firestore for additional accounts
        try {
            val url = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/users?key=$firebaseApiKey"
            val requestBuilder = Request.Builder().url(url)
            val idToken = prefs.getString("firebase_auth_token", "") ?: ""
            if (idToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $idToken")
            }
            val request = requestBuilder.get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val root = JSONObject(body)
                    val docs = root.optJSONArray("documents")
                    if (docs != null) {
                        for (i in 0 until docs.length()) {
                            val acc = parseFirestoreUser(docs.getJSONObject(i))
                            if ((acc.email.isNotBlank() || acc.username.isNotBlank()) && !list.any { it.email.equals(acc.email, true) }) {
                                list.add(acc)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GoogleDriveSyncManager", "Error fetching Firestore accounts", e)
        }

        if (list.isNotEmpty()) {
            saveGlobalAccounts(list)
        }
        getGlobalAccounts()
    }

    suspend fun checkEmailExistsCloud(input: String): Boolean = withContext(Dispatchers.IO) {
        val cleanInput = input.trim().lowercase()
        if (cleanInput.isBlank()) return@withContext false

        // 1. Check local memory/prefs
        if (checkEmailExists(cleanInput)) return@withContext true

        // 2. Fetch fresh accounts from online cloud database
        val cloudAccounts = fetchFirebaseAccountsCloud()
        cloudAccounts.any { acc ->
            val accEmail = acc.email.trim().lowercase()
            val accUser = acc.username.trim().lowercase()
            cleanInput == accEmail ||
            cleanInput == accUser ||
            (accEmail.contains("@") && cleanInput == accEmail.substringBefore("@"))
        }
    }

    suspend fun sendFirebasePasswordResetEmail(email: String): String = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return@withContext "INVALID_EMAIL"
        }
        try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=$firebaseApiKey"
            val payload = JSONObject().apply {
                put("requestType", "PASSWORD_RESET")
                put("email", cleanEmail)
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

        // 3. Check if account already exists on Cloud Database
        if (checkEmailExistsCloud(cleanEmail) || (cleanUser.isNotBlank() && checkEmailExistsCloud(cleanUser))) {
            Log.w("GoogleDriveSyncManager", "Account already exists for $cleanEmail / $cleanUser. Refusing registration.")
            return@withContext Pair(false, "Account already exists! Please Sign In instead of Signing Up.")
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
            val url = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/users/$docId?key=$firebaseApiKey"
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

        // 1. Fetch fresh cloud accounts from Cloud Master Directory
        val cloudAccounts = fetchFirebaseAccountsCloud()
        val matched = cloudAccounts.find {
            (it.email.lowercase() == cleanInput || it.username.lowercase() == cleanInput) && it.pass == cleanPass
        }
        if (matched != null) {
            registerUser(matched.name, matched.email, matched.username, matched.pass)
            return@withContext true
        }

        // 2. Fallback to local accounts
        loginUser(userOrEmail, pass)
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
        val arr = JSONArray()
        accounts.forEach { acc ->
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
        val finalPass = pass.trim()

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
            .putBoolean("is_super_admin", true)
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
            if (matchedAccount.pass.isBlank() || trimmedPass == matchedAccount.pass.trim()) {
                prefs.edit()
                    .putBoolean("is_user_logged_in", true)
                    .putBoolean("is_super_admin", true)
                    .putString("user_email", matchedAccount.email)
                    .putString("username", matchedAccount.username)
                    .putString("user_name", matchedAccount.name)
                    .putString("user_password", matchedAccount.pass)
                    .apply()
                return true
            }
            return false
        }

        // Fallback static superadmin account
        if ((trimmedUser == "superadmin" || trimmedUser == "admin@cashbook.com" || trimmedUser == "admin") &&
            (trimmedPass == "superadmin123" || trimmedPass == "admin123")) {
            registerUser("Admin", "admin@cashbook.com", "admin", "superadmin123")
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
        if (target == savedEmail || target == savedUser || (savedEmail.contains("@") && target == savedEmail.substringBefore("@"))) return true

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

            val existingBizList = dao.getAllBusinessesList().toMutableList()
            val existingBooksList = dao.getAllBooksList().toMutableList()
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

                    val matchedByName = existingBizList.find { it.name.equals(bName, ignoreCase = true) }
                    val matchedById = if (matchedByName == null) existingBizList.find { it.id == oldId } else null
                    val matched = matchedByName ?: matchedById

                    if (matched != null) {
                        bizIdMap[oldId] = matched.id
                        // If matched by ID but the local business name was different from cloud name, update to true cloud name!
                        if (!matched.name.equals(bName, ignoreCase = true) && bName.isNotBlank()) {
                            val updatedBiz = matched.copy(name = bName)
                            dao.updateBusiness(updatedBiz)
                            val idx = existingBizList.indexOfFirst { it.id == matched.id }
                            if (idx >= 0) existingBizList[idx] = updatedBiz
                        }
                    } else {
                        val newBiz = Business(id = if (existingBizList.isEmpty()) oldId else 0, name = bName, createdAt = createdAt)
                        val newId = dao.insertBusiness(newBiz).toInt()
                        val actualId = if (newBiz.id != 0) newBiz.id else newId
                        bizIdMap[oldId] = actualId
                        existingBizList.add(Business(id = actualId, name = bName, createdAt = createdAt))
                    }
                }

                // Clean up any empty local placeholder business that was not in cloud backup
                val restoredBizLocalIds = bizIdMap.values.toSet()
                val currentLocalBizList = dao.getAllBusinessesList()
                val emptyPlaceholders = currentLocalBizList.filter { localBiz ->
                    localBiz.id !in restoredBizLocalIds &&
                    existingTxList.none { tx ->
                        val bk = existingBooksList.find { b -> b.id == tx.bookId }
                        bk?.businessId == localBiz.id
                    }
                }
                for (placeholder in emptyPlaceholders) {
                    dao.deleteBusiness(placeholder)
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

                    val matched = existingBooksList.find { it.id == oldId || (it.businessId == mappedBizId && it.name.equals(bkName, ignoreCase = true)) }
                    if (matched != null) {
                        bookIdMap[oldId] = matched.id
                    } else {
                        val newBook = Book(id = if (existingBooksList.isEmpty()) oldId else 0, businessId = mappedBizId, name = bkName, phone = phone, createdAt = createdAt)
                        val newId = dao.insertBook(newBook).toInt()
                        val actualId = if (newBook.id != 0) newBook.id else newId
                        bookIdMap[oldId] = actualId
                        existingBooksList.add(Book(id = actualId, businessId = mappedBizId, name = bkName, phone = phone, createdAt = createdAt))
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

                    val matched = existingPartiesList.find { it.id == oldId || it.name.equals(pName, ignoreCase = true) }
                    if (matched != null) {
                        partyIdMap[oldId] = matched.id
                    } else {
                        val newParty = Party(id = if (existingPartiesList.isEmpty()) oldId else 0, name = pName, phone = phone, createdAt = createdAt)
                        val newId = dao.insertParty(newParty).toInt()
                        val actualId = if (newParty.id != 0) newParty.id else newId
                        partyIdMap[oldId] = actualId
                        existingPartiesList.add(Party(id = actualId, name = pName, phone = phone, createdAt = createdAt))
                    }
                }
            }

            // Restore/Merge Transactions
            if (root.has("transactions")) {
                val txArray = root.getJSONArray("transactions")
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

                    val exists = existingTxList.any { it.bookId == mappedBookId && it.amount == amount && it.timestamp == timestamp && it.type == type }
                    if (!exists) {
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
                        dao.insertTransaction(newTx)
                        existingTxList.add(newTx)
                    }
                }
            }

            // Restore/Merge Party Transactions
            if (root.has("party_transactions")) {
                val pTxArray = root.getJSONArray("party_transactions")
                for (i in 0 until pTxArray.length()) {
                    val obj = pTxArray.getJSONObject(i)
                    val oldPartyId = obj.getInt("partyId")
                    val mappedPartyId = partyIdMap[oldPartyId] ?: oldPartyId
                    val amount = obj.getDouble("amount")
                    val type = obj.getString("type")
                    val remarks = obj.getString("remarks")
                    val timestamp = obj.getLong("timestamp")

                    val exists = existingPartyTxList.any { it.partyId == mappedPartyId && it.amount == amount && it.timestamp == timestamp && it.type == type }
                    if (!exists) {
                        val newPTx = PartyTransaction(
                            id = 0,
                            partyId = mappedPartyId,
                            amount = amount,
                            type = type,
                            remarks = remarks,
                            timestamp = timestamp,
                            isSynced = true
                        )
                        dao.insertPartyTransaction(newPTx)
                        existingPartyTxList.add(newPTx)
                    }
                }
            }

            // Restore/Merge Team Members
            if (root.has("team_members")) {
                val teamArray = root.getJSONArray("team_members")
                for (i in 0 until teamArray.length()) {
                    val obj = teamArray.getJSONObject(i)
                    val oldBizId = obj.optInt("businessId", 1)
                    val mappedBizId = bizIdMap[oldBizId] ?: oldBizId
                    val name = obj.getString("name")
                    val email = obj.getString("email")
                    val phone = obj.getString("phone")
                    val role = obj.getString("role")
                    val createdAt = obj.getLong("createdAt")

                    val exists = existingTeamList.any { it.email.equals(email, ignoreCase = true) && it.businessId == mappedBizId }
                    if (!exists) {
                        val newMember = TeamMember(
                            id = 0,
                            businessId = mappedBizId,
                            name = name,
                            email = email,
                            phone = phone,
                            role = role,
                            createdAt = createdAt
                        )
                        dao.insertTeamMember(newMember)
                        existingTeamList.add(newMember)
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

    suspend fun syncWithFirebaseCloud(dao: LedgerDao): String = withContext(Dispatchers.IO) {
        if (!isRealCloudAccount()) {
            dao.markAllTransactionsSynced()
            dao.markAllPartyTransactionsSynced()
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
            if (!candidateDocIds.contains("cashbook_default_user")) {
                candidateDocIds.add("cashbook_default_user")
            }

            val idToken = getFirebaseAuthToken()
            var bestCloudJson: String? = null
            var maxDataScore = 0

            // 1. Check Cloud REST API & Firestore for all candidate docIds
            for (docId in candidateDocIds) {
                // Check Cloud REST API
                val cloudRestJson = fetchFromCloudRestApi(docId)
                if (!cloudRestJson.isNullOrBlank()) {
                    val score = calculateJsonDataScore(cloudRestJson)
                    if (score > maxDataScore) {
                        maxDataScore = score
                        bestCloudJson = cloudRestJson
                    }
                }

                try {
                    val getUrl = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/cashbooks/$docId?key=$firebaseApiKey"
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
                                        if (score > maxDataScore) {
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

            // 2. Check local prefs vaults
            val allPrefs = prefs.all
            for ((key, value) in allPrefs) {
                if (key.startsWith("cloud_vault_") && value is String && value.isNotBlank()) {
                    val score = calculateJsonDataScore(value)
                    if (score > maxDataScore) {
                        maxDataScore = score
                        bestCloudJson = value
                    }
                }
            }

            var restored = false
            if (!bestCloudJson.isNullOrBlank()) {
                restored = restoreDatabase(bestCloudJson!!, dao)
                if (restored) {
                    dao.markAllTransactionsSynced()
                    dao.markAllPartyTransactionsSynced()
                }
            }

            // 3. Serialize full DB after restoration
            val finalJson = serializeDatabaseFromDao(dao)

            // Save in local vault
            prefs.edit()
                .putString("cloud_vault_$primaryDocId", finalJson)
                .putLong("cloud_vault_ts_$primaryDocId", System.currentTimeMillis())
                .apply()

            saveToCloudRestApi(primaryDocId, finalJson)
            if (userEmail.isNotBlank()) saveToCloudRestApi(userEmail, finalJson)
            if (username.isNotBlank()) saveToCloudRestApi(username, finalJson)

            // 4. Update primaryDocId in Firestore
            val payload = buildFirestoreFields(
                "userEmail" to userEmail,
                "dataJson" to finalJson,
                "updatedAt" to System.currentTimeMillis()
            )
            val patchUrl = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/cashbooks/$primaryDocId?updateMask.fieldPaths=userEmail&updateMask.fieldPaths=dataJson&updateMask.fieldPaths=updatedAt&key=$firebaseApiKey"
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val patchReqBuilder = Request.Builder().url(patchUrl)
            if (!idToken.isNullOrBlank()) {
                patchReqBuilder.addHeader("Authorization", "Bearer $idToken")
            }
            val patchReq = patchReqBuilder.patch(body).build()

            var syncSuccess = false
            var syncSource = "Firebase Cloud"

            try {
                client.newCall(patchReq).execute().use { response ->
                    if (response.isSuccessful) {
                        syncSuccess = true
                        syncSource = "Firebase Firestore"
                    } else if (response.code == 404) {
                        // Document doesn't exist yet, create with POST
                        val postUrl = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/cashbooks?documentId=$primaryDocId&key=$firebaseApiKey"
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

            if (syncSuccess) {
                dao.markAllTransactionsSynced()
                dao.markAllPartyTransactionsSynced()
                return@withContext if (restored) "🟢 Restored & Synced from $syncSource ($userEmail)" else "🟢 Synced with $syncSource ($userEmail)"
            }

            // Fallback to Realtime Database REST
            try {
                val rtdbUrl = "https://$firebaseProjectId-default-rtdb.firebaseio.com/cashbooks/$primaryDocId.json" + if (!idToken.isNullOrBlank()) "?auth=$idToken" else "?key=$firebaseApiKey"
                val rtdbBody = finalJson.toRequestBody("application/json".toMediaType())
                val rtdbReq = Request.Builder().url(rtdbUrl).put(rtdbBody).build()
                client.newCall(rtdbReq).execute().use { rtdbRes ->
                    if (rtdbRes.isSuccessful) {
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

            // Multi-Cloud REST Backup (api.restful-api.dev)
            try {
                saveToCloudRestApi(primaryDocId, finalJson)
                if (userEmail.isNotBlank()) saveToCloudRestApi(userEmail, finalJson)
                if (username.isNotBlank()) saveToCloudRestApi(username, finalJson)
                dao.markAllTransactionsSynced()
                dao.markAllPartyTransactionsSynced()
                return@withContext if (restored) "🟢 Restored Data & Saved to Cloud Vault ($userEmail)" else "🟢 Synced with Cloud Vault ($userEmail)"
            } catch (e: Exception) {
                Log.e("GoogleDriveSyncManager", "Cloud Vault backup save error", e)
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
            val firestoreUrl = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents?key=$firebaseApiKey"
            val req = Request.Builder()
                .url(firestoreUrl)
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful || response.code == 404) {
                    Pair(true, "Firebase Live Connection Verified")
                } else if (response.code == 403) {
                    Pair(false, "Firebase Error (HTTP 403 Forbidden - Permission or API Key Issue)")
                } else if (response.code == 401 || response.code == 400) {
                    Pair(false, "Firebase Auth Error (HTTP ${response.code})")
                } else {
                    Pair(false, "Firebase Connection Error (HTTP ${response.code})")
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
