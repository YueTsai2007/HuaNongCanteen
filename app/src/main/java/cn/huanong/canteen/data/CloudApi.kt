package cn.huanong.canteen.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CloudAccount(val id: String, val email: String)
data class CloudSession(val token: String, val account: CloudAccount)
data class CloudState(val revision: Int, val payload: String?)

/** Stores only the opaque bearer token; AES-GCM key is hardware-backed when available. */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud_session", Context.MODE_PRIVATE)
    private val alias = "huanong_canteen_session_key"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    fun save(session: CloudSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(session.token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("token", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
            .putString("email", session.account.email).putString("id", session.account.id).apply()
    }
    fun token(): String? = runCatching {
        val bytes = Base64.decode(prefs.getString("token", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()
    fun account(): CloudAccount? {
        val id = prefs.getString("id", null) ?: return null
        val email = prefs.getString("email", null) ?: return null
        return CloudAccount(id, email)
    }
    fun clear() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    }
}

/** Small dependency-free HTTP client. Call from Dispatchers.IO only. */
class CloudApi(private val baseUrl: String, private val sessions: SessionStore, private val siteGateToken: String) {
    private fun request(path: String, method: String = "GET", body: ByteArray? = null, contentType: String = "application/json"): ByteArray {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        sessions.token()?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        connection.setRequestProperty("OAI-Sites-Authorization", "Bearer $siteGateToken")
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        if (status !in 200..299) {
            val message = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).optString("error") }.getOrNull()
            throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "云端请求失败 ($status)")
        }
        return bytes
    }
    private fun json(path: String, method: String = "GET", body: JSONObject? = null) =
        JSONObject(request(path, method, body?.toString()?.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8))

    fun register(email: String, password: String) = session(json("/api/v1/auth/register", "POST", JSONObject().put("email", email).put("password", password)))
    fun login(email: String, password: String) = session(json("/api/v1/auth/login", "POST", JSONObject().put("email", email).put("password", password)))
    private fun session(value: JSONObject): CloudSession {
        val account = value.getJSONObject("account")
        return CloudSession(value.getString("token"), CloudAccount(account.getString("id"), account.getString("email")))
    }
    fun checkSession(): CloudAccount = json("/api/v1/auth/me").getJSONObject("account").let { CloudAccount(it.getString("id"), it.getString("email")) }
    fun logout() { request("/api/v1/auth/logout", "POST", "{}".toByteArray()) }
    fun getState(): CloudState {
        val x = json("/api/v1/state")
        return CloudState(x.optInt("revision"), if (x.isNull("payload")) null else x.getJSONObject("payload").toString())
    }
    fun putState(revision: Int, payload: String): Int {
        val result = json("/api/v1/state", "PUT", JSONObject().put("revision", revision).put("payload", JSONObject(payload)))
        return result.getInt("revision")
    }
    fun uploadImageRaw(bytes: ByteArray): String = JSONObject(request("/api/v1/images", "POST", bytes, "application/octet-stream").toString(Charsets.UTF_8)).getString("imageId")
    fun downloadImage(imageId: String): ByteArray = request("/api/v1/images?id=$imageId")
}
