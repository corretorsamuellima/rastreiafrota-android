package com.rastreiafrota.app.data.remote

import com.rastreiafrota.app.data.prefs.SettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cliente HTTP seguro do dispositivo.
 *
 * Requisições protegidas usam:
 * Bearer + timestamp + nonce + assinatura HMAC-SHA256 do método/caminho/corpo.
 * A renovação de token é serializada para evitar que múltiplas requisições
 * concorrentes revoguem os tokens umas das outras.
 */
object ApiClient {

    @Volatile private var retrofit: Retrofit? = null
    @Volatile private var cachedBaseUrl: String? = null
    private val refreshLock = Any()

    fun service(settings: SettingsStore): ApiService {
        val baseUrl = runBlocking { settings.baseUrl() }.trim().let {
            if (it.endsWith("/")) it else "$it/"
        }
        retrofit?.let { if (cachedBaseUrl == baseUrl) return it.create(ApiService::class.java) }
        synchronized(this) {
            retrofit?.let { if (cachedBaseUrl == baseUrl) return it.create(ApiService::class.java) }
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(RefreshInterceptor(settings, baseUrl))
                .addInterceptor(SigningInterceptor(settings))
                .build()
            val built = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            retrofit = built
            cachedBaseUrl = baseUrl
            return built.create(ApiService::class.java)
        }
    }

    fun reset() {
        synchronized(this) {
            retrofit = null
            cachedBaseUrl = null
        }
    }

    private fun isPublicPath(path: String): Boolean =
        path.endsWith("/device/activate") || path.endsWith("/device/refresh") ||
            path.endsWith("/app/version") || path.endsWith("/health")

    /** Caminho canônico usado igualmente pelo app e pelo PHP, mesmo em subpasta. */
    private fun canonicalApiPath(path: String): String {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val index = normalized.indexOf("/api/")
        return if (index >= 0) normalized.substring(index) else normalized
    }

    private class SigningInterceptor(private val settings: SettingsStore) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            val token = settings.accessToken
            val secret = settings.hmacSecret

            if (isPublicPath(path) || token.isNullOrBlank() || secret.isNullOrBlank()) {
                return chain.proceed(request)
            }

            val timestamp = iso8601(Date())
            val nonce = UUID.randomUUID().toString()
            val bodyHash = sha256Hex(bodyBytes(request))
            val canonicalPath = canonicalApiPath(path)
            val payload = "${request.method.uppercase()}|$canonicalPath|$timestamp|$nonce|$bodyHash"
            val signature = hmacSha256Hex(secret, payload)

            return chain.proceed(
                request.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("X-Timestamp", timestamp)
                    .header("X-Nonce", nonce)
                    .header("X-Signature", signature)
                    .header("Accept", "application/json")
                    .build()
            )
        }

        private fun bodyBytes(request: Request): ByteArray {
            val body = request.body ?: return ByteArray(0)
            val buffer = Buffer()
            body.writeTo(buffer)
            return buffer.readByteArray()
        }
    }

    private class RefreshInterceptor(
        private val settings: SettingsStore,
        private val baseUrl: String
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val failedAccessToken = settings.accessToken
            val response = chain.proceed(originalRequest)
            val path = originalRequest.url.encodedPath

            if (response.code != 401 || isPublicPath(path)) return response

            // Refresh não corrige cabeçalho perdido, relógio errado ou assinatura inválida.
            val serverCode = errorCode(response)
            if (serverCode != null && serverCode != "token_invalid") {
                return response
            }

            val refreshed = synchronized(refreshLock) {
                // Outra requisição já renovou enquanto esta aguardava o lock.
                val currentToken = settings.accessToken
                if (!currentToken.isNullOrBlank() && currentToken != failedAccessToken) {
                    true
                } else {
                    val refreshToken = settings.refreshToken ?: return@synchronized false
                    tryRefresh(refreshToken)
                }
            }

            if (!refreshed) return response

            response.close()
            // O retry volta a passar pelo SigningInterceptor e usa as credenciais novas.
            return chain.proceed(originalRequest)
        }

        private fun tryRefresh(refreshToken: String): Boolean {
            return try {
                val json = JSONObject().put("refresh_token", refreshToken).toString()
                val request = Request.Builder()
                    .url(baseUrl + "api/v1/device/refresh")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .build()

                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { refreshResponse ->
                        if (refreshResponse.code == 200) {
                            val data = JSONObject(refreshResponse.body?.string() ?: "{}")
                                .optJSONObject("data") ?: return false
                            val access = data.optString("access_token")
                            val refresh = data.optString("refresh_token")
                            val secret = data.optString("hmac_secret")
                            if (access.isBlank() || refresh.isBlank() || secret.isBlank()) return false
                            settings.accessToken = access
                            settings.refreshToken = refresh
                            settings.hmacSecret = secret
                            true
                        } else {
                            if (refreshResponse.code == 401 && settings.refreshToken == refreshToken) {
                                settings.clearCredentials()
                            }
                            false
                        }
                    }
            } catch (_: Exception) {
                false
            }
        }

        private fun errorCode(response: Response): String? {
            return try {
                val raw = response.peekBody(128 * 1024L).string()
                JSONObject(raw).optJSONObject("data")?.optString("code")?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Lê a mensagem JSON de um erro Retrofit sem exibir apenas "HTTP 401". */
    fun errorMessage(response: retrofit2.Response<*>): String {
        val fallback = "HTTP ${response.code()}"
        return try {
            val raw = response.errorBody()?.string().orEmpty()
            if (raw.isBlank()) return fallback
            val json = JSONObject(raw)
            val message = json.optString("message").ifBlank { fallback }
            val code = json.optJSONObject("data")?.optString("code").orEmpty()
            if (code.isBlank()) message else "$message [$code]"
        } catch (_: Exception) {
            fallback
        }
    }

    fun iso8601(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(date)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hmacSha256Hex(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
