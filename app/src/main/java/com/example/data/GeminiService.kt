package com.example.data

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun parseTransaction(input: String): ParsedTransaction? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e("GeminiService", "API Key is missing or default placeholder")
            return@withContext null
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

        val prompt = """
            You are an expert bookkeeping AI. Parse the following natural language finance record into a structured JSON object.
            Today is July 18, 2026.
            
            Input: "$input"
            
            Return a JSON object with EXACTLY these fields:
            {
              "type": "IN" or "OUT", 
              "amount": number, 
              "category": "Sales" / "Salary" / "Rent" / "Food" / "Travel" / "Office" / "Utilities" / "Other",
              "remarks": "short description of transaction",
              "paymentMethod": "Cash" or "Online" or "Bank"
            }
            Do NOT include any markdown formatting like ```json ... ```, return ONLY the raw JSON string.
        """.trimIndent()

        val jsonRequest = JSONObject().apply {
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonRequest.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("GeminiService", "API call failed with code: ${response.code}")
                    return@withContext null
                }
                val responseString = response.body?.string() ?: return@withContext null
                Log.d("GeminiService", "Raw Response: $responseString")

                val root = JSONObject(responseString)
                val candidates = root.getJSONArray("candidates")
                val parts = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                val text = parts.getJSONObject(0).getString("text")

                val parsedJson = JSONObject(text.trim())
                return@withContext ParsedTransaction(
                    type = parsedJson.optString("type", "OUT").uppercase(),
                    amount = parsedJson.optDouble("amount", 0.0),
                    category = parsedJson.optString("category", "Other"),
                    remarks = parsedJson.optString("remarks", ""),
                    paymentMethod = parsedJson.optString("paymentMethod", "Cash")
                )
            }
        } catch (e: Exception) {
            Log.e("GeminiService", "Error calling or parsing Gemini response", e)
            null
        }
    }

    suspend fun askFinancialAssistant(historyPrompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Gemini API key is not configured. Please add your key to the AI Studio Secrets panel."
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

        val jsonRequest = JSONObject().apply {
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", historyPrompt)
                        })
                    })
                })
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonRequest.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Failed to get response from AI. Code: ${response.code}"
                }
                val responseString = response.body?.string() ?: return@withContext "Empty response from AI."
                val root = JSONObject(responseString)
                val candidates = root.getJSONArray("candidates")
                val parts = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                parts.getJSONObject(0).getString("text")
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

data class ParsedTransaction(
    val type: String,
    val amount: Double,
    val category: String,
    val remarks: String,
    val paymentMethod: String
)
