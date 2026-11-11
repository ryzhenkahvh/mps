package jxpix.proger.mps.cloud

import jxpix.proger.mps.cloud.CloudException.Kind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * [CloudApi] on a Supabase project, spoken over plain HTTPS: `/auth/v1` for accounts,
 * `/rest/v1` for the database, `/storage/v1` for files. What the database allows is decided
 * by the rules in `supabase/schema.sql`, not by this class.
 *
 * @param baseUrl address of the project, e.g. `https://abcd.supabase.co`
 * @param key its public ("publishable" or "anon") key; it is meant to be shipped inside apps
 * @param fallbackUrl optional second address that forwards to the same project. Some mobile
 *                    networks do not let `supabase.co` through; a forwarder on another domain helps.
 */
class SupabaseApi(baseUrl: String, private val key: String, fallbackUrl: String = "") : CloudApi {

    private val primary = baseUrl.trim().trimEnd('/')
    private val fallback = fallbackUrl.trim().trimEnd('/')

    /** Set once the main address could not be reached and the second one answered. */
    @Volatile private var onFallback = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    override val configured: Boolean get() = primary.isNotEmpty() && key.isNotEmpty()

    private fun base(): String = if (onFallback && fallback.isNotEmpty()) fallback else primary

    // ------------------------------------------------------------------ plumbing

    /** Runs [request] against the address in use; when it cannot be reached at all, tries the other one. */
    private fun <T> reach(request: (String) -> T): T {
        if (!configured) throw CloudException(Kind.NOT_CONFIGURED)
        try {
            return request(base())
        } catch (e: CloudException) {
            throw e
        } catch (e: IOException) {
            if (fallback.isEmpty()) throw CloudException(Kind.NETWORK, e.message)
            onFallback = !onFallback
            try {
                return request(base())
            } catch (e2: CloudException) {
                throw e2
            } catch (e2: IOException) {
                onFallback = !onFallback
                throw CloudException(Kind.NETWORK, e2.message)
            }
        }
    }

    private fun headers(token: String?, json: Boolean): HashMap<String, String> {
        val h = HashMap<String, String>()
        h["apikey"] = key
        // Without a signed-in person the key itself is the credential.
        h["Authorization"] = "Bearer " + (token ?: key)
        if (json) h["Content-Type"] = "application/json"
        return h
    }

    private fun call(method: String, path: String, token: String?, body: JsonElement? = null, prefer: String? = null): HttpResponse {
        val h = headers(token, body != null)
        if (prefer != null) h["Prefer"] = prefer
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        return reach { base -> Http.send(method, base + path, h, bytes) }
    }

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------------ errors

    /** The gateway in front of every service refuses a key it does not know. */
    private fun badKey(r: HttpResponse): Boolean = r.code == 401 && r.body.contains("Invalid API key")

    private fun authError(r: HttpResponse): CloudException {
        if (badKey(r)) return CloudException(Kind.NOT_CONFIGURED, "invalid key", r.code)
        val o = parse(r.body)
        val code = o?.str("error_code") ?: o?.str("error") ?: ""
        val text = o?.str("msg") ?: o?.str("error_description") ?: o?.str("message") ?: ""
        val kind = when {
            code == "invalid_credentials" || text.contains("Invalid login credentials", true) -> Kind.INVALID_LOGIN
            code == "user_already_exists" || code == "email_exists" || text.contains("already registered", true) -> Kind.EMAIL_TAKEN
            code == "weak_password" || text.contains("Password should", true) -> Kind.WEAK_PASSWORD
            code == "email_address_invalid" || text.contains("invalid format", true) ||
                (code == "validation_failed" && text.contains("email", true)) -> Kind.BAD_EMAIL
            code == "email_not_confirmed" -> Kind.CONFIRM_EMAIL
            code == "otp_expired" || text.contains("Token has expired or is invalid", true) -> Kind.BAD_CODE
            // "you can only request this after 57 seconds": a letter to this address is on its way already
            code == "over_email_send_rate_limit" && text.contains("seconds") -> Kind.MAIL_WAIT
            // the last one: the project confirms addresses by e-mail but has no mail service of its own
            code == "signup_disabled" || code == "email_provider_disabled" || code == "email_address_not_authorized" -> Kind.SIGNUP_CLOSED
            r.code == 429 || code.startsWith("over_") -> Kind.RATE_LIMIT
            code in SESSION_CODES || r.code == 401 || r.code == 403 -> Kind.SESSION
            else -> Kind.SERVER
        }
        val wait = if (kind == Kind.MAIL_WAIT) Regex("(\\d+) seconds").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 60 else 0
        return CloudException(kind, "$code $text".trim(), r.code, wait)
    }

    private fun restError(r: HttpResponse): CloudException {
        if (badKey(r)) return CloudException(Kind.NOT_CONFIGURED, "invalid key", r.code)
        val o = parse(r.body)
        val code = o?.str("code") ?: ""
        val text = (o?.str("message") ?: "") + " " + (o?.str("details") ?: "")
        val kind = when {
            // the tables or functions are not there: schema.sql has not been run on this project
            code.startsWith("PGRST2") -> Kind.NOT_CONFIGURED
            code.startsWith("PGRST3") || text.contains("JWT", true) -> Kind.SESSION
            text.contains("MPS_SIGN_IN") -> Kind.SESSION
            text.contains("MPS_DAILY_LIMIT") -> Kind.DAILY_LIMIT
            text.contains("MPS_NOT_ALLOWED") -> Kind.NOT_ALLOWED
            // the database says "not allowed": for somebody who is not signed in that means "sign in first"
            code == "42501" -> if (r.code == 401) Kind.SESSION else Kind.NOT_ALLOWED
            code == "23505" && text.contains("username", true) -> Kind.NAME_TAKEN
            code == "23514" && text.contains("username", true) -> Kind.BAD_NAME
            code == "23503" || r.code == 404 -> Kind.NOT_FOUND
            r.code == 401 -> Kind.SESSION
            r.code == 429 -> Kind.RATE_LIMIT
            else -> Kind.SERVER
        }
        return CloudException(kind, "$code $text".trim(), r.code)
    }

    private fun storageError(r: HttpResponse): CloudException {
        if (badKey(r)) return CloudException(Kind.NOT_CONFIGURED, "invalid key", r.code)
        val o = parse(r.body)
        val status = o?.str("statusCode") ?: ""
        val error = o?.str("error") ?: ""
        val text = o?.str("message") ?: ""
        val kind = when {
            r.code == 413 || status == "413" || text.contains("maximum allowed size", true) -> Kind.TOO_LARGE
            error.contains("mime", true) || text.contains("mime", true) || status == "415" -> Kind.NOT_ALLOWED
            // the rules of the database said no: in their own folder the only one a person can break is the limit on space
            text.contains("row-level security", true) -> Kind.NO_ROOM
            r.code == 404 || status == "404" -> Kind.NOT_FOUND
            r.code == 429 || status == "429" -> Kind.RATE_LIMIT
            // any other refusal is about who is asking: a sign-in that has run out or is not accepted
            r.code == 401 || r.code == 403 || status == "401" || status == "403" || text.contains("jwt", true) -> Kind.SESSION
            else -> Kind.SERVER
        }
        return CloudException(kind, "$status $error $text".trim(), r.code)
    }

    // ------------------------------------------------------------------ accounts

    private fun session(o: JsonObject): CloudSession {
        val user = o["user"] as? JsonObject
        // counted on the clock of this phone: it may differ from the clock of the server by hours
        val expiresIn = (o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L
        val expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        return CloudSession(
            accessToken = o.str("access_token") ?: throw CloudException(Kind.SERVER, "no access token"),
            refreshToken = o.str("refresh_token") ?: "",
            expiresAt = expiresAt,
            userId = user?.str("id") ?: throw CloudException(Kind.SERVER, "no user"),
            email = user.str("email") ?: "",
        )
    }

    private fun token(grant: String, body: JsonObject): CloudSession {
        val r = call("POST", "/auth/v1/token?grant_type=$grant", null, body)
        if (!r.ok) throw authError(r)
        return session(parse(r.body) ?: throw CloudException(Kind.SERVER, "unreadable answer"))
    }

    override fun authSettings(): AuthSettings {
        val r = call("GET", "/auth/v1/settings", null)
        if (!r.ok) throw authError(r)
        val o = parse(r.body)
        val external = o?.get("external") as? JsonObject
        return AuthSettings(
            google = (external?.get("google") as? JsonPrimitive)?.contentOrNull == "true",
            signUpOpen = (o?.get("disable_signup") as? JsonPrimitive)?.contentOrNull != "true",
        )
    }

    override fun signUp(email: String, password: String, username: String): CloudSession {
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
            putJsonObject("data") { put("username", username.trim()) }
        }
        val r = call("POST", "/auth/v1/signup", null, body)
        if (!r.ok) throw authError(r)
        val o = parse(r.body) ?: throw CloudException(Kind.SERVER, "unreadable answer")
        if (o["access_token"] == null) {
            // No session: the address has to be confirmed with the code from the letter. For an address
            // that has an account already no letter is sent; the answer then lists no way to sign in.
            if ((o["identities"] as? JsonArray)?.isEmpty() == true) throw CloudException(Kind.EMAIL_TAKEN)
            throw CloudException(Kind.CONFIRM_EMAIL)
        }
        return session(o)
    }

    private fun verify(type: String, email: String, code: String): CloudSession {
        val body = buildJsonObject { put("type", type); put("email", email.trim()); put("token", code.trim()) }
        val r = call("POST", "/auth/v1/verify", null, body)
        if (!r.ok) throw authError(r)
        return session(parse(r.body) ?: throw CloudException(Kind.SERVER, "unreadable answer"))
    }

    override fun confirmSignUp(email: String, code: String): CloudSession = verify("signup", email, code)

    override fun resendSignUpCode(email: String) {
        val r = call("POST", "/auth/v1/resend", null, buildJsonObject { put("type", "signup"); put("email", email.trim()) })
        if (!r.ok) throw authError(r)
    }

    override fun requestPasswordReset(email: String) {
        val r = call("POST", "/auth/v1/recover", null, buildJsonObject { put("email", email.trim()) })
        if (!r.ok) throw authError(r)
    }

    override fun resetPassword(email: String, code: String, newPassword: String): CloudSession {
        val session = verify("recovery", email, code)
        setPassword(session, newPassword)
        return session
    }

    override fun setPassword(session: CloudSession, password: String) {
        val r = call("PUT", "/auth/v1/user", session.accessToken, buildJsonObject { put("password", password) })
        // being told that this is the password already is as good as having set it
        if (!r.ok && !r.body.contains("same_password")) throw authError(r)
    }

    override fun signIn(email: String, password: String): CloudSession =
        token("password", buildJsonObject { put("email", email.trim()); put("password", password) })

    override fun refresh(refreshToken: String): CloudSession =
        token("refresh_token", buildJsonObject { put("refresh_token", refreshToken) })

    override fun signOut(session: CloudSession) {
        try {
            call("POST", "/auth/v1/logout?scope=local", session.accessToken)
        } catch (_: IOException) {
            // signing out works without the server: the app simply forgets the session
        }
    }

    override fun googleUrl(redirect: String, challenge: String): String =
        base() + "/auth/v1/authorize?provider=google&redirect_to=" + enc(redirect) +
            "&code_challenge=" + enc(challenge) + "&code_challenge_method=s256"

    override fun exchangeCode(code: String, verifier: String): CloudSession =
        token("pkce", buildJsonObject { put("auth_code", code); put("code_verifier", verifier) })

    override fun deleteAccount(session: CloudSession) {
        rpc("delete_account", JsonObject(emptyMap()), session.accessToken)
    }

    // ------------------------------------------------------------------ database

    private fun rpc(name: String, args: JsonObject, token: String?): String {
        val r = call("POST", "/rest/v1/rpc/$PREFIX$name", token, args)
        if (!r.ok) throw restError(r)
        return r.body
    }

    private fun <T> list(path: String, token: String?, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val r = call("GET", path, token)
        if (!r.ok) throw restError(r)
        return try {
            json.decodeFromString(ListSerializer(serializer), r.body)
        } catch (e: Exception) {
            throw CloudException(Kind.SERVER, "unreadable answer: ${e.message}")
        }
    }

    override fun author(id: String): CloudProfile? =
        list("/rest/v1/${PREFIX}authors?select=*&id=eq.${enc(id)}&limit=1", null, CloudProfile.serializer()).firstOrNull()

    override fun usernameFree(name: String): Boolean =
        rpc("username_free", buildJsonObject { put("name", name) }, null).trim() == "true"

    override fun updateProfile(session: CloudSession, username: String, bio: String, avatar: Int) {
        rpc(
            "update_profile",
            buildJsonObject { put("new_username", username.trim()); put("new_bio", bio.trim()); put("new_avatar", avatar) },
            session.accessToken,
        )
    }

    override fun feed(query: FeedQuery, session: CloudSession?): List<CloudItem> =
        list(feedPath(query), session?.accessToken, CloudItem.serializer())

    override fun item(id: String, session: CloudSession?): CloudItem? =
        list("/rest/v1/${PREFIX}feed?select=*&id=eq.${enc(id)}&limit=1", session?.accessToken, CloudItem.serializer()).firstOrNull()

    override fun publish(session: CloudSession, item: NewItem): CloudItem {
        val body = buildJsonObject {
            put("kind", item.kind.id)
            put("title", item.title.trim())
            put("description", item.description.trim())
            put("tags", buildJsonArray { item.tags.forEach { add(JsonPrimitive(it)) } })
            if (item.filePath != null) put("file_path", item.filePath)
            if (item.previewPath != null) put("preview_path", item.previewPath)
            put("size_bytes", item.sizeBytes)
            put("duration_ms", item.durationMs)
            if (item.bpm != null) put("bpm", item.bpm)
            if (item.device != null) put("device", item.device)
            if (item.payload != null) put("payload", item.payload)
        }
        val r = call("POST", "/rest/v1/${PREFIX}items?select=id", session.accessToken, body, prefer = "return=representation")
        if (!r.ok) throw restError(r)
        val id = try {
            (json.parseToJsonElement(r.body) as JsonArray)[0].jsonObject["id"]!!.jsonPrimitive.content
        } catch (e: Exception) {
            throw CloudException(Kind.SERVER, "unreadable answer")
        }
        return item(id, session) ?: throw CloudException(Kind.NOT_FOUND)
    }

    override fun updateItem(session: CloudSession, id: String, title: String, description: String, tags: List<String>) {
        rpc(
            "update_item",
            buildJsonObject {
                put("item", id)
                put("new_title", title)
                put("new_description", description)
                put("new_tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
            },
            session.accessToken,
        )
    }

    override fun deleteItem(session: CloudSession, item: CloudItem) {
        // files first: once the row is gone nothing points at them any more
        deleteFiles(session, listOfNotNull(item.filePath, item.previewPath))
        val r = call("DELETE", "/rest/v1/${PREFIX}items?id=eq.${enc(item.id)}", session.accessToken)
        if (!r.ok) throw restError(r)
    }

    override fun myRating(session: CloudSession, itemId: String): Int {
        val path = "/rest/v1/${PREFIX}ratings?select=stars&item_id=eq.${enc(itemId)}&user_id=eq.${enc(session.userId)}&limit=1"
        val r = call("GET", path, session.accessToken)
        if (!r.ok) throw restError(r)
        return try {
            ((json.parseToJsonElement(r.body) as JsonArray).firstOrNull() as? JsonObject)?.get("stars")?.jsonPrimitive?.intOrNull ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun rate(session: CloudSession, itemId: String, stars: Int): Pair<Int, Int> {
        val text = rpc("rate", buildJsonObject { put("item", itemId); put("stars", stars) }, session.accessToken)
        return try {
            val o = (json.parseToJsonElement(text) as JsonArray)[0].jsonObject
            (o["rating_sum"]!!.jsonPrimitive.intOrNull ?: 0) to (o["rating_count"]!!.jsonPrimitive.intOrNull ?: 0)
        } catch (e: Exception) {
            throw CloudException(Kind.NOT_FOUND)
        }
    }

    override fun report(session: CloudSession, itemId: String, reason: String) {
        val body = buildJsonObject { put("item_id", itemId); put("reason", reason.trim().take(300)) }
        val r = call("POST", "/rest/v1/${PREFIX}reports", session.accessToken, body, prefer = "return=minimal")
        // a second complaint from the same person changes nothing and is not an error
        if (!r.ok && r.code != 409) throw restError(r)
    }

    override fun addDownload(session: CloudSession?, itemId: String, device: String): Int {
        val text = rpc("add_download", buildJsonObject { put("item", itemId); put("device", device) }, session?.accessToken)
        return text.trim().toIntOrNull() ?: 0
    }

    override fun moderate(session: CloudSession, itemId: String, hide: Boolean) {
        rpc("moderate", buildJsonObject { put("item", itemId); put("hide", hide) }, session.accessToken)
    }

    override fun storageUse(session: CloudSession): StorageUse {
        val text = rpc("my_storage", JsonObject(emptyMap()), session.accessToken)
        return try {
            val o = (json.parseToJsonElement(text) as JsonArray)[0].jsonObject
            StorageUse(o["used"]!!.jsonPrimitive.longOrNull ?: 0L, o["quota"]!!.jsonPrimitive.longOrNull ?: 0L)
        } catch (e: Exception) {
            StorageUse(0, 0)
        }
    }

    // ------------------------------------------------------------------ files

    override fun upload(session: CloudSession, path: String, file: File, mime: String, progress: (Float) -> Boolean) {
        val h = headers(session.accessToken, false)
        h["Content-Type"] = mime
        h["x-upsert"] = "false"
        // Names are never reused, so copies kept by the delivery network cannot go stale. When a file
        // is deleted the server makes the network forget it (within about a minute); a day is the
        // longest a copy could outlive its file if that ever failed.
        h["cache-control"] = "max-age=86400"
        val r = reach { base -> Http.upload("$base/storage/v1/object/$BUCKET/$path", h, file, progress) }
        if (!r.ok) throw storageError(r)
    }

    override fun deleteFiles(session: CloudSession, paths: List<String>) {
        for (path in paths) {
            val r = call("DELETE", "/storage/v1/object/$BUCKET/$path", session.accessToken)
            // a file that is already gone is what was wanted
            if (!r.ok && r.code != 404 && storageError(r).kind != Kind.NOT_FOUND) throw storageError(r)
        }
    }

    override fun fileUrl(path: String): String = base() + "/storage/v1/object/public/$BUCKET/" + path

    override fun download(url: String, to: File, progress: (Float) -> Boolean) {
        val code = try {
            Http.download(url, to, progress)
        } catch (e: CloudException) {
            throw e
        } catch (e: IOException) {
            // the address may have been made before the switch to the second server
            val other = if (fallback.isNotEmpty() && url.startsWith(primary)) fallback + url.substring(primary.length) else null
            if (other == null) throw CloudException(Kind.NETWORK, e.message)
            try {
                Http.download(other, to, progress)
            } catch (e2: CloudException) {
                throw e2
            } catch (e2: IOException) {
                throw CloudException(Kind.NETWORK, e2.message)
            }
        }
        if (code !in 200..299) throw CloudException(if (code == 404 || code == 400) Kind.NOT_FOUND else Kind.SERVER, "HTTP $code", code)
    }

    companion object {
        /** Every table, view and function of the app on the server starts with this (see schema.sql). */
        const val PREFIX = "mps_"
        const val BUCKET = "mps-files"

        private val SESSION_CODES = setOf(
            "refresh_token_not_found", "refresh_token_already_used", "session_not_found", "session_expired",
            "invalid_grant", "bad_jwt", "no_authorization", "user_not_found", "flow_state_not_found", "flow_state_expired",
            "bad_code_verifier",
        )

        /** What is left of a search text once everything that could upset the query syntax is gone. */
        fun cleanSearch(text: String): String {
            val sb = StringBuilder()
            for (ch in text.trim()) {
                when {
                    ch.isLetterOrDigit() || ch == '-' || ch == '_' -> sb.append(ch)
                    sb.isNotEmpty() && sb.last() != ' ' -> sb.append(' ')
                }
            }
            return sb.toString().trim().take(40)
        }

        /** The request that lists a part of the catalogue. */
        fun feedPath(q: FeedQuery): String {
            fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            val sb = StringBuilder("/rest/v1/${PREFIX}feed?select=*")
            if (q.kind != null) sb.append("&kind=eq.").append(q.kind.id)
            if (q.owner != null) sb.append("&owner=eq.").append(enc(q.owner))
            if (q.device != null) sb.append("&device=eq.").append(enc(q.device))
            if (q.hiddenOnly) sb.append("&hidden=is.true")
            val s = cleanSearch(q.search)
            if (s.isNotEmpty()) {
                val like = "*" + s.replace(' ', '*') + "*"
                val any = ArrayList<String>()
                any += "title.ilike.$like"
                any += "author.ilike.$like"
                if (' ' !in s) any += "tags.cs.{" + s.lowercase() + "}"
                sb.append("&or=").append(enc("(" + any.joinToString(",") + ")"))
            }
            sb.append("&order=").append(
                when (q.sort) {
                    CloudSort.NEW -> "created_at.desc"
                    CloudSort.POPULAR -> "downloads.desc,created_at.desc"
                    CloudSort.BEST -> "score.desc,rating_count.desc,created_at.desc"
                }
            )
            sb.append("&limit=").append(q.limit.coerceIn(1, 100)).append("&offset=").append(q.offset.coerceAtLeast(0))
            return sb.toString()
        }
    }
}

/** The secret and its fingerprint for signing in through the browser (PKCE). */
class Pkce private constructor(val verifier: String, val challenge: String) {
    companion object {
        fun create(random: SecureRandom = SecureRandom()): Pkce {
            val bytes = ByteArray(48)
            random.nextBytes(bytes)
            return of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }

        fun of(verifier: String): Pkce {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
        }
    }
}
