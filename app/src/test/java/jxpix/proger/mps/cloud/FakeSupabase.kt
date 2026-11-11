package jxpix.proger.mps.cloud

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A stand-in for a Supabase project, for tests.
 *
 * The database part is real: requests to `/rest/v1` are passed on to a local PostgREST that
 * serves a PostgreSQL with `supabase/schema.sql` applied, so the access rules under test are
 * the ones that will run in production. Accounts and file storage are imitated in memory,
 * with the answers (and the error texts) the real services give.
 *
 * Tests that need it are skipped when no local PostgREST is running.
 */
class FakeSupabase(private val rest: String = REST, private val secret: String = SECRET) : AutoCloseable {

    private class User(val id: String, val email: String, var password: String)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val users = ConcurrentHashMap<String, User>()
    private val refreshTokens = ConcurrentHashMap<String, String>()
    private val oauth = ConcurrentHashMap<String, Pair<String, String>>()
    val files = ConcurrentHashMap<String, ByteArray>()

    /** Lifetime of the access tokens handed out, in seconds. */
    @Volatile var tokenSeconds = 3600L
    @Volatile var google = false
    @Volatile var confirmEmail = false
    @Volatile var maxFileBytes = 10L * 1024 * 1024
    @Volatile var userQuota = 50L * 1024 * 1024

    /** How many requests of any kind have arrived. */
    @Volatile var requests = 0

    val url: String get() = "http://127.0.0.1:${server.address.port}"
    val anonKey: String = jwt(mapOf("role" to "anon"), 10L * 365 * 24 * 3600)
    private val serviceKey: String = jwt(mapOf("role" to "service_role"), 10L * 365 * 24 * 3600)

    init {
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/auth/v1/") { x -> handle(x) { auth(x) } }
        server.createContext("/rest/v1/") { x -> handle(x) { forward(x) } }
        server.createContext("/storage/v1/") { x -> handle(x) { storage(x) } }
        server.start()
    }

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    // ------------------------------------------------------------------ helpers for tests

    /** Calls a function of the database with full rights (as the dashboard would). */
    fun admin(function: String, args: JsonObject): String {
        val c = URL("$rest/rpc/$function").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $serviceKey")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(args.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        check(code in 200..299) { "$function failed: $code $text" }
        return text
    }

    fun config(userQuota: Long = 50L * 1024 * 1024, daily: Int = 40, hideAfter: Int = 3) {
        this.userQuota = userQuota
        admin("test_config", buildJsonObject { put("user_quota", userQuota); put("daily", daily); put("hide_after", hideAfter) })
    }

    /** Forgets every account, item and file (the database is shared by all tests). */
    fun reset() {
        admin("test_reset", JsonObject(emptyMap()))
        users.clear()
        refreshTokens.clear()
        files.clear()
        config()
    }

    fun makeAdmin(userId: String) {
        admin("test_make_admin", buildJsonObject { put("uid", userId) })
    }

    /** What "Sign in with Google" ends with: the code that the browser brings back to the app. */
    fun googleCode(challenge: String, email: String, name: String): String {
        val code = UUID.randomUUID().toString()
        val user = users.getOrPut(email) {
            val u = User(UUID.randomUUID().toString(), email, UUID.randomUUID().toString())
            admin("test_create_user", buildJsonObject {
                put("uid", u.id); put("mail", email); putJsonObject("meta") { put("name", name) }
            })
            u
        }
        oauth[code] = challenge to user.id
        return code
    }

    // ------------------------------------------------------------------ JSON web tokens

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun sign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return b64(mac.doFinal(data.toByteArray()))
    }

    private fun jwt(claims: Map<String, String>, seconds: Long): String {
        val payload = buildJsonObject {
            for ((k, v) in claims) put(k, v)
            put("exp", System.currentTimeMillis() / 1000 + seconds)
        }
        val head = b64("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val body = b64(payload.toString().toByteArray())
        return "$head.$body." + sign("$head.$body")
    }

    /** The id of the person a token belongs to, or null when it is forged, expired or anonymous. */
    private fun subject(x: HttpExchange): String? {
        val token = x.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim() ?: return null
        val parts = token.split('.')
        if (parts.size != 3 || sign(parts[0] + "." + parts[1]) != parts[2]) return null
        val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
        val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: 0
        if (exp < System.currentTimeMillis() / 1000) return null
        return (claims["sub"] as? JsonPrimitive)?.contentOrNull
    }

    // ------------------------------------------------------------------ plumbing

    private fun handle(x: HttpExchange, block: () -> Unit) {
        requests++
        try {
            block()
        } catch (e: Exception) {
            try {
                reply(x, 500, """{"message":"${e.javaClass.simpleName}: ${e.message?.replace('"', '\'')}"}""")
            } catch (_: Exception) {
            }
        } finally {
            x.close()
        }
    }

    private fun reply(x: HttpExchange, code: Int, body: String) = reply(x, code, body.toByteArray(Charsets.UTF_8), "application/json")

    private fun reply(x: HttpExchange, code: Int, body: ByteArray, type: String) {
        x.responseHeaders.set("Content-Type", type)
        if (body.isEmpty()) {
            x.sendResponseHeaders(code, -1)
        } else {
            x.sendResponseHeaders(code, body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
    }

    private fun query(x: HttpExchange): Map<String, String> =
        (x.requestURI.rawQuery ?: "").split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    // ------------------------------------------------------------------ accounts

    private fun sessionJson(user: User): String {
        val refresh = UUID.randomUUID().toString()
        refreshTokens[refresh] = user.id
        return buildJsonObject {
            put("access_token", jwt(mapOf("role" to "authenticated", "sub" to user.id), tokenSeconds))
            put("token_type", "bearer")
            put("expires_in", tokenSeconds)
            put("refresh_token", refresh)
            putJsonObject("user") { put("id", user.id); put("email", user.email) }
        }.toString()
    }

    private fun authError(x: HttpExchange, status: Int, code: String, message: String) =
        reply(x, status, buildJsonObject { put("code", status); put("error_code", code); put("msg", message) }.toString())

    private fun auth(x: HttpExchange) {
        val path = x.requestURI.path.removePrefix("/auth/v1")
        val body = x.requestBody.readBytes().toString(Charsets.UTF_8)
        val o = if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body).jsonObject
        fun field(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull ?: ""
        when {
            path == "/settings" -> reply(x, 200, """{"external":{"email":true,"google":$google},"disable_signup":false,"mailer_autoconfirm":${!confirmEmail}}""")
            path == "/signup" -> {
                val email = field("email").lowercase()
                val password = field("password")
                when {
                    !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email) ->
                        authError(x, 400, "email_address_invalid", "Email address \"$email\" is invalid")
                    password.length < 6 -> authError(x, 422, "weak_password", "Password should be at least 6 characters.")
                    users.containsKey(email) -> authError(x, 422, "user_already_exists", "User already registered")
                    else -> {
                        val user = User(UUID.randomUUID().toString(), email, password)
                        admin("test_create_user", buildJsonObject {
                            put("uid", user.id); put("mail", email); put("meta", o["data"] ?: JsonObject(emptyMap()))
                        })
                        users[email] = user
                        if (confirmEmail) {
                            reply(x, 200, buildJsonObject { put("id", user.id); put("email", email); put("confirmation_sent_at", "now") }.toString())
                        } else {
                            reply(x, 200, sessionJson(user))
                        }
                    }
                }
            }
            path == "/token" -> when (query(x)["grant_type"]) {
                "password" -> {
                    val user = users[field("email").lowercase()]
                    if (user == null || user.password != field("password")) authError(x, 400, "invalid_credentials", "Invalid login credentials")
                    else reply(x, 200, sessionJson(user))
                }
                "refresh_token" -> {
                    val id = refreshTokens.remove(field("refresh_token"))
                    val user = users.values.firstOrNull { it.id == id }
                    if (user == null) authError(x, 400, "refresh_token_not_found", "Invalid Refresh Token: Refresh Token Not Found")
                    else reply(x, 200, sessionJson(user))
                }
                "pkce" -> {
                    val pending = oauth.remove(field("auth_code"))
                    val digest = MessageDigest.getInstance("SHA-256").digest(field("code_verifier").toByteArray())
                    val user = users.values.firstOrNull { it.id == pending?.second }
                    if (pending == null || user == null) authError(x, 404, "flow_state_not_found", "invalid flow state, no valid flow state found")
                    else if (b64(digest) != pending.first) authError(x, 400, "bad_code_verifier", "code challenge does not match previously saved code verifier")
                    else reply(x, 200, sessionJson(user))
                }
                else -> authError(x, 400, "invalid_request", "unsupported grant type")
            }
            path == "/logout" -> reply(x, 204, "")
            else -> reply(x, 404, """{"message":"no such route"}""")
        }
    }

    // ------------------------------------------------------------------ database: the real thing

    private fun forward(x: HttpExchange) {
        val target = rest + x.requestURI.rawPath.removePrefix("/rest/v1") + (x.requestURI.rawQuery?.let { "?$it" } ?: "")
        val c = URL(target).openConnection() as HttpURLConnection
        c.requestMethod = x.requestMethod
        for (name in listOf("Authorization", "Content-Type", "Prefer", "Accept")) {
            x.requestHeaders.getFirst(name)?.let { c.setRequestProperty(name, it) }
        }
        val body = x.requestBody.readBytes()
        if (x.requestMethod == "POST") {
            c.doOutput = true
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
        reply(x, code, bytes, c.contentType ?: "application/json")
    }

    // ------------------------------------------------------------------ files

    private fun storageError(x: HttpExchange, http: Int, status: String, error: String, message: String) =
        reply(x, http, buildJsonObject { put("statusCode", status); put("error", error); put("message", message) }.toString())

    private fun storage(x: HttpExchange) {
        val path = URLDecoder.decode(x.requestURI.rawPath.removePrefix("/storage/v1"), "UTF-8")
        when {
            path.startsWith("/object/public/$BUCKET/") -> {
                val bytes = files[path.removePrefix("/object/public/$BUCKET/")]
                if (bytes == null) storageError(x, 400, "404", "not_found", "Object not found")
                else reply(x, 200, bytes, "application/octet-stream")
            }
            path.startsWith("/object/$BUCKET/") -> {
                val name = path.removePrefix("/object/$BUCKET/")
                val who = subject(x)
                if (who == null) {
                    x.requestBody.readBytes()
                    storageError(x, 400, "403", "Unauthorized", "jwt expired")
                    return
                }
                if (x.requestMethod == "POST") {
                    val bytes = x.requestBody.readBytes()
                    val type = x.requestHeaders.getFirst("Content-Type") ?: ""
                    val used = files.filterKeys { it.startsWith("$who/") }.values.sumOf { it.size.toLong() }
                    when {
                        bytes.size > maxFileBytes -> storageError(x, 413, "413", "Payload too large", "The object exceeded the maximum allowed size")
                        type !in MIME -> storageError(x, 415, "415", "invalid_mime_type", "mime type $type is not supported")
                        !name.startsWith("$who/") || used >= userQuota ->
                            storageError(x, 400, "403", "Unauthorized", "new row violates row-level security policy")
                        files.containsKey(name) -> storageError(x, 400, "409", "Duplicate", "The resource already exists")
                        else -> {
                            files[name] = bytes
                            admin("test_storage_put", buildJsonObject { put("path", name); put("bytes", bytes.size) })
                            reply(x, 200, """{"Key":"$BUCKET/$name","Id":"${UUID.randomUUID()}"}""")
                        }
                    }
                } else if (x.requestMethod == "DELETE") {
                    if (!name.startsWith("$who/") || files.remove(name) == null) {
                        storageError(x, 400, "404", "not_found", "Object not found")
                    } else {
                        admin("test_storage_delete", buildJsonObject { put("path", name) })
                        reply(x, 200, """{"message":"Successfully deleted"}""")
                    }
                } else reply(x, 405, "")
            }
            else -> reply(x, 404, """{"message":"no such route"}""")
        }
    }

    companion object {
        /** Where `supabase/local/start.sh` (or any PostgREST over the schema) listens. */
        val REST: String = System.getenv("MPS_TEST_REST") ?: "http://127.0.0.1:54330"
        val SECRET: String = System.getenv("MPS_TEST_JWT_SECRET") ?: "mps-local-test-secret-0123456789abcdef0123456789"
        private const val BUCKET = SupabaseApi.BUCKET
        private val MIME = setOf("audio/wav", "audio/x-wav", "audio/mpeg", "application/zip")

        /** True when the local database with its REST layer is running. */
        fun available(): Boolean = try {
            val u = URL(REST)
            Socket().use { it.connect(InetSocketAddress(u.host, if (u.port > 0) u.port else 80), 300) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
