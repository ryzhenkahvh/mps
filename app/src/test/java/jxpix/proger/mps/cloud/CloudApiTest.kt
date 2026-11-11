package jxpix.proger.mps.cloud

import jxpix.proger.mps.cloud.CloudException.Kind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The client of the community server against a real database with the real access rules
 * (see [FakeSupabase]). Skipped when no local database is running; the last three tests need nothing.
 */
class CloudApiTest {

    private var server: FakeSupabase? = null
    private lateinit var api: SupabaseApi
    private val tmp = File(System.getProperty("java.io.tmpdir"), "mps-cloud-test").apply { mkdirs() }

    @Before
    fun start() {
        if (FakeSupabase.available()) {
            val s = FakeSupabase()
            s.config()
            server = s
            api = SupabaseApi(s.url, s.anonKey)
        }
    }

    @After
    fun stop() {
        server?.close()
        tmp.listFiles()?.forEach { it.delete() }
    }

    private fun needServer(): FakeSupabase {
        assumeTrue("no local database: run supabase/local/start.sh", server != null)
        return server!!
    }

    /** A fresh person; names are unique so that the tests can share one database. */
    private fun person(prefix: String): Pair<CloudSession, String> {
        val name = prefix + "_" + UUID.randomUUID().toString().take(6)
        return api.signUp("$name@example.com", "secret-$name", name) to name
    }

    private fun kindOf(block: () -> Unit): Kind? = try {
        block()
        null
    } catch (e: CloudException) {
        e.kind
    }

    private fun file(name: String, bytes: Int): File = File(tmp, name).apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }

    private fun preset(title: String, tags: List<String> = emptyList(), device: String = "fm") = NewItem(
        CloudKind.PRESET, title, tags = tags, device = device,
        payload = buildJsonObject { putJsonObject("params") { put("ratio", 2.0); put("amount", 0.5) } },
    )

    @Test
    fun accounts() {
        needServer()
        val (alice, name) = person("alice")
        assertTrue(alice.accessToken.isNotEmpty() && alice.refreshToken.isNotEmpty() && alice.userId.isNotEmpty())
        assertEquals("$name@example.com", alice.email)

        // the profile was made by the database, with the name asked for
        val profile = api.author(alice.userId)!!
        assertEquals(name, profile.username)
        assertEquals(0, profile.items)
        assertFalse(api.usernameFree(name.uppercase()))
        assertTrue(api.usernameFree(name + "x"))

        // sign-in and the ways it fails
        assertEquals(alice.userId, api.signIn("$name@example.com", "secret-$name").userId)
        assertEquals(Kind.INVALID_LOGIN, kindOf { api.signIn("$name@example.com", "wrong") })
        assertEquals(Kind.INVALID_LOGIN, kindOf { api.signIn("nobody-$name@example.com", "secret") })
        assertEquals(Kind.EMAIL_TAKEN, kindOf { api.signUp("$name@example.com", "another-secret", "other") })
        assertEquals(Kind.WEAK_PASSWORD, kindOf { api.signUp("w$name@example.com", "123", "weak") })
        assertEquals(Kind.BAD_EMAIL, kindOf { api.signUp("not an address", "secret-secret", "bad") })

        // a taken name does not block the sign-up: the person gets a made-up one and can change it
        val second = api.signUp("second-$name@example.com", "secret-secret", name)
        assertTrue(api.author(second.userId)!!.username.startsWith("user_"))

        // editing the profile
        api.updateProfile(alice, "${name}2", "Beats and bass", 5)
        val edited = api.author(alice.userId)!!
        assertEquals("${name}2", edited.username)
        assertEquals("Beats and bass", edited.bio)
        assertEquals(5, edited.avatar)
        assertEquals(Kind.NAME_TAKEN, kindOf { api.updateProfile(second, "${name}2".uppercase(), "", 0) })
        assertEquals(Kind.BAD_NAME, kindOf { api.updateProfile(second, "a b", "", 0) })
        assertEquals(Kind.BAD_NAME, kindOf { api.updateProfile(second, "x", "", 0) })

        // a refreshed session works, the old refresh token does not
        val renewed = api.refresh(alice.refreshToken)
        assertEquals(alice.userId, renewed.userId)
        assertEquals(Kind.SESSION, kindOf { api.refresh(alice.refreshToken) })
        api.updateProfile(renewed, "${name}3", "", 0)
        api.signOut(renewed)
    }

    @Test
    fun publishingAndBrowsing() {
        val s = needServer()
        val (alice, aliceName) = person("anna")
        val (bob, _) = person("boris")
        val mark = "t" + UUID.randomUUID().toString().take(8)

        // a preset is only a row; a sound is a file plus a row
        val keys = api.publish(alice, preset("Glass $mark", listOf("Keys", "keys ", mark)))
        assertEquals("preset", keys.kind)
        assertEquals(aliceName, keys.author)
        assertEquals(listOf("keys", mark), keys.tags.sorted())
        assertEquals(2.0, keys.payload!!["params"]!!.jsonObject["ratio"]!!.jsonPrimitive.content.toDouble(), 1e-9)

        val wav = file("kick.wav", 20_000)
        val path = "${alice.userId}/${UUID.randomUUID()}.wav"
        var last = 0f
        api.upload(alice, path, wav, "audio/wav") { last = it; true }
        assertEquals(1f, last, 1e-6f)
        val kick = api.publish(alice, NewItem(CloudKind.SOUND, "Kick $mark", "punchy", listOf("drums"), filePath = path, sizeBytes = wav.length(), durationMs = 400))
        assertEquals(path, kick.filePath)
        assertEquals(20_000, kick.sizeBytes)

        // anyone can list, filter, search and download
        val all = api.feed(FeedQuery(search = mark), null)
        assertEquals(setOf(keys.id, kick.id), all.map { it.id }.toSet())
        assertEquals(listOf(kick.id), api.feed(FeedQuery(kind = CloudKind.SOUND, search = mark), null).map { it.id })
        assertEquals(listOf(keys.id), api.feed(FeedQuery(device = "fm", search = mark), null).map { it.id })
        assertEquals(listOf(kick.id), api.feed(FeedQuery(search = "kick $mark"), null).map { it.id })
        assertTrue(api.feed(FeedQuery(search = aliceName), null).size >= 2)
        assertEquals(2, api.feed(FeedQuery(owner = alice.userId), null).size)
        assertEquals(1, api.feed(FeedQuery(owner = alice.userId, limit = 1), null).size)
        assertEquals(1, api.feed(FeedQuery(owner = alice.userId, limit = 1, offset = 1), null).size)
        assertEquals(0, api.feed(FeedQuery(owner = alice.userId, offset = 2), null).size)
        assertEquals(0, api.feed(FeedQuery(search = "no-such-thing-$mark"), null).size)
        // the newest comes first
        assertEquals(kick.id, api.feed(FeedQuery(owner = alice.userId), null).first().id)

        val copy = File(tmp, "copy.wav")
        api.download(api.fileUrl(kick.filePath!!), copy)
        assertTrue(copy.readBytes().contentEquals(wav.readBytes()))
        assertEquals(Kind.NOT_FOUND, kindOf { api.download(api.fileUrl("${alice.userId}/missing.wav"), File(tmp, "x")) })

        // downloads are counted once per installation or person
        assertEquals(1, api.addDownload(null, kick.id, "installation-0001"))
        assertEquals(1, api.addDownload(null, kick.id, "installation-0001"))
        assertEquals(2, api.addDownload(bob, kick.id, "installation-0001"))
        assertEquals(2, api.addDownload(bob, kick.id, "installation-0002"))
        // authors do not raise their own numbers
        assertEquals(2, api.addDownload(alice, kick.id, "installation-0003"))
        assertEquals(kick.id, api.feed(FeedQuery(sort = CloudSort.POPULAR, search = mark), null).first().id)

        // marks
        assertEquals(0, api.myRating(bob, keys.id))
        assertEquals(5 to 1, api.rate(bob, keys.id, 5))
        assertEquals(4 to 1, api.rate(bob, keys.id, 4))
        assertEquals(4, api.myRating(bob, keys.id))
        assertEquals(Kind.NOT_ALLOWED, kindOf { api.rate(alice, keys.id, 5) })
        assertEquals(keys.id, api.feed(FeedQuery(sort = CloudSort.BEST, search = mark), null).first().id)
        assertEquals(0 to 0, api.rate(bob, keys.id, 0))
        val author = api.author(alice.userId)!!
        assertEquals(2, author.items)
        assertEquals(2, author.downloads)

        // what the rules refuse
        assertEquals(Kind.SESSION, kindOf { api.rate(CloudSession("", "", 0, bob.userId).copy(accessToken = s.anonKey), keys.id, 5) })
        assertEquals(Kind.NOT_ALLOWED, kindOf { api.updateItem(bob, keys.id, "Stolen", "", emptyList()) })
        assertEquals(
            Kind.NOT_ALLOWED,
            kindOf { api.publish(bob, NewItem(CloudKind.SOUND, "Not mine", filePath = path, sizeBytes = 1)) },
        )
        api.deleteItem(bob, keys)   // silently does nothing: the row is not his
        assertNotNull(api.item(keys.id, null))

        // editing and deleting one's own
        api.updateItem(alice, keys.id, "Glass keys $mark", "bright", listOf("Bright"))
        val edited = api.item(keys.id, null)!!
        assertEquals("Glass keys $mark", edited.title)
        assertEquals(listOf("bright"), edited.tags)
        assertEquals(20_000L, api.storageUse(alice).used)
        api.deleteItem(alice, kick)
        assertNull(api.item(kick.id, null))
        assertFalse(s.files.containsKey(path))
        assertEquals(0L, api.storageUse(alice).used)
    }

    @Test
    fun complaintsAndModeration() {
        val s = needServer()
        val (author, _) = person("author")
        val (boss, _) = person("boss")
        s.makeAdmin(boss.userId)
        val item = api.publish(author, preset("Reported " + UUID.randomUUID().toString().take(6)))

        val people = (1..3).map { person("p$it").first }
        api.report(people[0], item.id, "copied")
        api.report(people[0], item.id, "copied again")   // the same person twice: no error, no effect
        api.report(people[1], item.id, "")
        assertNotNull(api.item(item.id, null))
        api.report(people[2], item.id, "spam")

        // three different people: hidden from everyone but the author and moderators
        assertNull(api.item(item.id, null))
        assertNull(api.item(item.id, people[0]))
        assertTrue(api.item(item.id, author)!!.hidden)
        assertEquals(3, api.item(item.id, boss)!!.reports)
        assertTrue(api.feed(FeedQuery(hiddenOnly = true), boss).any { it.id == item.id })
        assertEquals(Kind.NOT_ALLOWED, kindOf { api.moderate(people[0], item.id, false) })

        api.moderate(boss, item.id, false)
        assertEquals(0, api.item(item.id, null)!!.reports)
        api.moderate(boss, item.id, true)
        assertNull(api.item(item.id, null))
        // a moderator can remove what is not theirs
        api.deleteItem(boss, api.item(item.id, boss)!!)
        assertNull(api.item(item.id, author))
        assertEquals(Kind.SESSION, kindOf { api.report(CloudSession(s.anonKey, "", 0, "x"), item.id, "") })
    }

    @Test
    fun limitsOnFilesAndPublications() {
        val s = needServer()
        val (alice, _) = person("limits")
        val small = file("a.wav", 3000)
        fun path() = "${alice.userId}/${UUID.randomUUID()}.wav"

        s.maxFileBytes = 2000
        assertEquals(Kind.TOO_LARGE, kindOf { api.upload(alice, path(), small, "audio/wav") })
        s.maxFileBytes = 10L * 1024 * 1024
        assertEquals(Kind.NOT_ALLOWED, kindOf { api.upload(alice, path(), small, "video/mp4") })
        assertEquals(Kind.NO_ROOM, kindOf { api.upload(alice, "someone-else/x.wav", small, "audio/wav") })

        s.config(userQuota = 5000)
        api.upload(alice, path(), small, "audio/wav")
        api.upload(alice, path(), small, "audio/wav")   // the quota is checked before a file arrives
        assertEquals(Kind.NO_ROOM, kindOf { api.upload(alice, path(), small, "audio/wav") })
        val use = api.storageUse(alice)
        assertEquals(6000L, use.used)
        assertEquals(5000L, use.quota)

        // an upload can be cancelled half way
        s.config()
        val big = file("big.wav", 400_000)
        assertEquals(Kind.CANCELLED, kindOf { api.upload(alice, path(), big, "audio/wav") { it < 0.3f } })

        s.config(daily = 2)
        api.publish(alice, preset("One"))
        api.publish(alice, preset("Two"))
        assertEquals(Kind.DAILY_LIMIT, kindOf { api.publish(alice, preset("Three")) })
        s.config()
    }

    @Test
    fun expiredSignInAndAccountRemoval() {
        val s = needServer()
        val (alice, _) = person("expiring")
        val item = api.publish(alice, preset("Short lived"))
        // a sign-in that ran out two minutes ago (servers forgive half a minute of clock difference)
        s.tokenSeconds = -120
        val expired = api.refresh(alice.refreshToken)
        // every part of the server says the same thing about it
        assertEquals(Kind.SESSION, kindOf { api.publish(expired, preset("Too late")) })
        assertEquals(Kind.SESSION, kindOf { api.rate(expired, item.id, 5) })
        assertEquals(Kind.SESSION, kindOf { api.upload(expired, "${alice.userId}/x.wav", file("x.wav", 10), "audio/wav") })
        s.tokenSeconds = 3600
        val renewed = api.refresh(expired.refreshToken)
        assertEquals(1, api.feed(FeedQuery(owner = alice.userId), renewed).size)

        api.deleteAccount(renewed)
        assertNull(api.author(alice.userId))
        assertNull(api.item(item.id, null))
    }

    @Test
    fun signInWithGoogle() {
        val s = needServer()
        assertFalse(api.authSettings().google)
        s.google = true
        assertTrue(api.authSettings().google)

        val pkce = Pkce.create()
        val url = api.googleUrl("jxpix.proger.mps://auth", pkce.challenge)
        assertTrue(url, url.startsWith(s.url + "/auth/v1/authorize?provider=google&redirect_to=jxpix.proger.mps%3A%2F%2Fauth&code_challenge="))
        assertTrue(url.endsWith("&code_challenge_method=s256"))

        val mail = "g" + UUID.randomUUID().toString().take(6) + "@gmail.com"
        val session = api.exchangeCode(s.googleCode(pkce.challenge, mail, "Gena  Bukin"), pkce.verifier)
        assertEquals(mail, session.email)
        // no name was chosen: it is made from the Google name (or made up when that one is taken)
        val name = api.author(session.userId)!!.username
        assertTrue(name, name == "Gena_Bukin" || name.startsWith("user_"))
        // a code is good for one use, and only with the secret it was asked with
        assertEquals(Kind.SESSION, kindOf { api.exchangeCode(s.googleCode(pkce.challenge, mail, "x"), "another-verifier") })
    }

    @Test
    fun secondAddressIsUsedWhenTheFirstCannotBeReached() {
        val s = needServer()
        // nothing listens on port 9 of this machine
        val detour = SupabaseApi("http://127.0.0.1:9", s.anonKey, s.url)
        val before = s.requests
        detour.feed(FeedQuery(limit = 1), null)
        detour.feed(FeedQuery(limit = 1), null)
        assertEquals(before + 2, s.requests)
        assertTrue(detour.fileUrl("a/b.wav").startsWith(s.url))

        val alone = SupabaseApi("http://127.0.0.1:9", s.anonKey)
        assertEquals(Kind.NETWORK, kindOf { alone.feed(FeedQuery(), null) })
    }

    // ---- no server needed below

    @Test
    fun nothingHappensWithoutAnAddress() {
        val none = SupabaseApi("", "")
        assertFalse(none.configured)
        assertEquals(Kind.NOT_CONFIGURED, kindOf { none.feed(FeedQuery(), null) })
        assertEquals(Kind.NOT_CONFIGURED, kindOf { none.signIn("a@b.c", "secret") })
    }

    @Test
    fun answersOfAProjectThatIsNotReadyAreUnderstood() {
        // bodies copied from a real project: a key it does not know, and a database without schema.sql;
        // the third one is what the sign-up says while "Confirm email" is on and no mail service is set up
        val canned = mapOf(
            "/rest/v1/mps_feed" to (401 to """{"message":"Invalid API key","hint":"Double check your API key."}"""),
            "/rest/v1/mps_authors" to (404 to """{"code":"PGRST205","details":null,"hint":null,"message":"Could not find the table 'public.mps_authors' in the schema cache"}"""),
            "/auth/v1/signup" to (400 to """{"code":400,"error_code":"email_address_not_authorized","msg":"Email address not authorized"}"""),
        )
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        for ((path, answer) in canned) {
            http.createContext(path) { x ->
                x.requestBody.readBytes()
                val bytes = answer.second.toByteArray()
                x.responseHeaders.set("Content-Type", "application/json")
                x.sendResponseHeaders(answer.first, bytes.size.toLong())
                x.responseBody.use { it.write(bytes) }
            }
        }
        http.start()
        try {
            val real = SupabaseApi("http://127.0.0.1:${http.address.port}", "sb_publishable_test")
            assertEquals(Kind.NOT_CONFIGURED, kindOf { real.feed(FeedQuery(), null) })
            assertEquals(Kind.NOT_CONFIGURED, kindOf { real.author("0d9c1a52-0000-4000-8000-000000000000") })
            assertEquals(Kind.SIGNUP_CLOSED, kindOf { real.signUp("someone@gmail.com", "secret-secret", "someone") })
        } finally {
            http.stop(0)
        }
    }

    @Test
    fun requestsAreBuiltSafely() {
        assertEquals(
            "/rest/v1/mps_feed?select=*&order=created_at.desc&limit=30&offset=0",
            SupabaseApi.feedPath(FeedQuery()),
        )
        assertEquals(
            "/rest/v1/mps_feed?select=*&kind=eq.sound&order=downloads.desc,created_at.desc&limit=10&offset=20",
            SupabaseApi.feedPath(FeedQuery(kind = CloudKind.SOUND, sort = CloudSort.POPULAR, limit = 10, offset = 20)),
        )
        // whatever is typed, the search text cannot break out of its place in the request
        assertEquals("kick 808", SupabaseApi.cleanSearch("  kick,  808)  "))
        assertEquals("бас", SupabaseApi.cleanSearch("бас"))
        assertEquals("a b", SupabaseApi.cleanSearch("a.b"))
        assertEquals("", SupabaseApi.cleanSearch("()*,."))
        val path = SupabaseApi.feedPath(FeedQuery(search = "Deep),(id.gt.0"))
        assertTrue(path, path.contains("&or=%28title.ilike.*Deep*id*gt*0*%2Cauthor.ilike.*Deep*id*gt*0*%29&order="))
        val tag = SupabaseApi.feedPath(FeedQuery(search = "Bass"))
        assertTrue(tag, tag.contains("tags.cs.%7Bbass%7D"))
        assertFalse(SupabaseApi.feedPath(FeedQuery(search = "()")).contains("or="))
    }

    @Test
    fun browserSignInSecret() {
        // the example of RFC 7636, appendix B
        val pkce = Pkce.of("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
        val fresh = Pkce.create()
        assertTrue(fresh.verifier.length in 43..128)
        assertTrue(fresh.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(fresh.verifier == Pkce.create().verifier)
        if (false) fail()
    }
}
