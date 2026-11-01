package apw.android.hubspace.backend

import android.util.Log
import apw.android.hubspace.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object GeminiApiClient {
    private const val API_KEY = BuildConfig.GEMINI_API_KEY
    private const val MODEL_NAME = "gemini-3-flash-preview"
    private const val TAG = "GeminiApiClient"

    suspend fun generateText(prompt: String): String = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val urlString =
                "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent?key=$API_KEY"
            val url = URL(urlString)

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }

            val jsonBody = JSONObject().apply {
                put(
                    "contents", JSONArray().put(
                        JSONObject().put(
                            "parts", JSONArray().put(
                                JSONObject().put("text", prompt)
                            )
                        )
                    )
                )
            }

            connection.getOutputStream().use { os ->
                val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseStream = BufferedReader(
                    InputStreamReader(
                        connection.getInputStream(), Charsets.UTF_8
                    )
                )
                val responseString = StringBuilder()
                var line: String?
                while (responseStream.readLine().also { line = it } != null) {
                    responseString.append(line)
                }
                responseStream.close()

                val jsonResponse = JSONObject(responseString.toString())
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val content = candidates.getJSONObject(0).optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        return@withContext parts.getJSONObject(0).optString("text", "").trim()
                    }
                }
                return@withContext "You're all caught up for today! Keep pushing."
            } else {
                val errorStream = BufferedReader(
                    InputStreamReader(
                        connection.errorStream ?: connection.inputStream, Charsets.UTF_8
                    )
                )

                val errString = errorStream.readText()
                Log.e(TAG, "API Error: $responseCode - $errString")
                return@withContext "Stay focused! Check your pending tasks and team feed."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling Gemini REST API", e)
            return@withContext "Keep up the great work today!"
        } finally {
            connection?.disconnect()
        }
    }
}