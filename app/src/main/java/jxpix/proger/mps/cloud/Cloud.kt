package jxpix.proger.mps.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jxpix.proger.mps.BuildConfig
import jxpix.proger.mps.R
import jxpix.proger.mps.audio.AudioExporter
import jxpix.proger.mps.audio.Mp3Encoder
import jxpix.proger.mps.cloud.CloudException.Kind
import jxpix.proger.mps.data.PresetStore
import jxpix.proger.mps.data.UserPreset
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.OfflineRenderer
import jxpix.proger.mps.engine.SamplePool
import jxpix.proger.mps.engine.Wav
import jxpix.proger.mps.model.usedSamples
import jxpix.proger.mps.studio.Studio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * The community section: what its screens show, who is signed in, and everything that can be
 * done there - browsing, listening, downloading, publishing, rating.
 *
 * Talks to the server through [api] on background threads; all state is read and changed on
 * the main thread. Long jobs use the progress dialog and the message bar of the [studio].
 */
class Cloud(private val app: Context, private val studio: Studio) {

    /** Replaceable, so that the screens can be tried against a test server. */
    var api: CloudApi = SupabaseApi(BuildConfig.CLOUD_URL, BuildConfig.CLOUD_KEY, BuildConfig.CLOUD_FALLBACK_URL)
        set(value) {
            field = value
            feedRun++
            items = emptyList()
            failure = null
            loading = false
            accountLoaded = false
            googleSignIn = false
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir = File(app.cacheDir, "cloud")
    private val player = StreamPlayer()

    /** Stands for this installation when downloads of people who are not signed in are counted. */
    private val device: String = prefs.getString(KEY_DEVICE, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    /** The sign-in as background threads see it; [session] mirrors it for the screens. */
    @Volatile private var live: CloudSession? = readSession()
    private val renewLock = Any()
    @Volatile private var cancelled = false

    // ------------------------------------------------------------------ state for the screens

    /** True while the community screen covers the app. */
    var visible by mutableStateOf(false)
        private set

    var session by mutableStateOf(live)
        private set

    /** The profile of the signed-in person (null until it has been fetched). */
    var me by mutableStateOf<CloudProfile?>(null)
        private set
    var storage by mutableStateOf<StorageUse?>(null)
        private set

    /** Whether the server offers "Sign in with Google". */
    var googleSignIn by mutableStateOf(false)
        private set

    var kind by mutableStateOf<CloudKind?>(null)
        private set
    var sort by mutableStateOf(CloudSort.NEW)
        private set
    var search by mutableStateOf("")
        private set

    /** Moderators: show what was hidden after complaints instead of the catalogue. */
    var hiddenOnly by mutableStateOf(false)
        private set
    var items by mutableStateOf<List<CloudItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var canLoadMore by mutableStateOf(false)
        private set

    /** Why the list could not be loaded, or null. */
    var failure by mutableStateOf<Kind?>(null)
        private set

    /** The item whose details are open. */
    var opened by mutableStateOf<CloudItem?>(null)
        private set

    /** The mark the signed-in person gave to [opened] (0 = none). */
    var myStars by mutableIntStateOf(0)
        private set

    /** The author whose page is open: their id first, the profile and items once they have arrived. */
    var authorId by mutableStateOf<String?>(null)
        private set
    var author by mutableStateOf<CloudProfile?>(null)
        private set
    var authorItems by mutableStateOf<List<CloudItem>>(emptyList())
        private set
    var authorLoading by mutableStateOf(false)
        private set

    var askSignIn by mutableStateOf(false)
    var signingIn by mutableStateOf(false)
        private set
    var signInError by mutableStateOf<Kind?>(null)

    /** What a code from a letter is awaited for. */
    enum class CodeStep { SIGN_UP, PASSWORD }

    /** The address a letter with a code went to; non-null while the sign-in sheet asks for that code. */
    var codeFor by mutableStateOf<String?>(null)
        private set
    var codeStep by mutableStateOf(CodeStep.SIGN_UP)
        private set

    /** Seconds until another letter may be asked for. */
    var resendIn by mutableIntStateOf(0)
        private set
    private var resendJob: Job? = null

    /** What was typed when the account was asked for: applied once the code is accepted. */
    private var pendingPassword: String? = null
    private var pendingName: String? = null

    /** The item that is playing, and the one that is still being fetched. */
    var playingId by mutableStateOf<String?>(null)
        private set
    var preparingId by mutableStateOf<String?>(null)
        private set

    val configured: Boolean get() = api.configured
    val signedIn: Boolean get() = session != null
    fun isMine(item: CloudItem): Boolean = session?.userId == item.owner
    val isModerator: Boolean get() = me?.isAdmin == true

    private var feedRun = 0
    private var playRun = 0
    private var accountLoaded = false

    // ------------------------------------------------------------------ small tools

    private fun text(id: Int): String = studio.strings(id)
    private fun say(id: Int) {
        studio.message = text(id)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** The text for something that went wrong. */
    fun errorText(kind: Kind): String = text(
        when (kind) {
            Kind.NOT_CONFIGURED -> R.string.cloud_not_configured
            Kind.NETWORK -> R.string.cloud_err_network
            Kind.SESSION -> R.string.cloud_err_session
            Kind.INVALID_LOGIN -> R.string.cloud_err_login
            Kind.EMAIL_TAKEN -> R.string.cloud_err_email_taken
            Kind.WEAK_PASSWORD -> R.string.cloud_err_weak_password
            Kind.BAD_EMAIL -> R.string.cloud_err_bad_email
            Kind.CONFIRM_EMAIL -> R.string.cloud_err_confirm_email
            Kind.BAD_CODE -> R.string.cloud_err_bad_code
            Kind.MAIL_WAIT -> R.string.cloud_err_mail_wait
            Kind.SIGNUP_CLOSED -> R.string.cloud_err_signup_closed
            Kind.NAME_TAKEN -> R.string.cloud_err_name_taken
            Kind.BAD_NAME -> R.string.cloud_err_bad_name
            Kind.RATE_LIMIT -> R.string.cloud_err_rate_limit
            Kind.NO_ROOM -> R.string.cloud_err_no_room
            Kind.TOO_LARGE -> R.string.cloud_err_too_large
            Kind.DAILY_LIMIT -> R.string.cloud_err_daily
            Kind.NOT_ALLOWED -> R.string.cloud_err_not_allowed
            Kind.NOT_FOUND -> R.string.cloud_err_not_found
            Kind.CANCELLED -> R.string.cloud_cancelled
            Kind.SERVER -> R.string.cloud_err_server
        }
    )

    private fun kindOf(e: Throwable): Kind = (e as? CloudException)?.kind ?: Kind.SERVER

    /** Shows what went wrong; an expired sign-in also brings up the sign-in sheet. */
    private fun report(e: Throwable) {
        val kind = kindOf(e)
        if (kind == Kind.CANCELLED) return
        if (kind == Kind.SESSION) {
            if (live == null) session = null
            askSignIn = true
        }
        studio.message = errorText(kind)
    }

    // ------------------------------------------------------------------ the sign-in

    private fun readSession(): CloudSession? = try {
        prefs.getString(KEY_SESSION, null)?.let { json.decodeFromString(CloudSession.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    /** Any thread. */
    private fun store(s: CloudSession?) {
        live = s
        val edit = prefs.edit()
        if (s == null) edit.remove(KEY_SESSION) else edit.putString(KEY_SESSION, json.encodeToString(CloudSession.serializer(), s))
        edit.apply()
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    /**
     * Background thread. Renews the sign-in that [old] belongs to; if the server refuses, the person is signed out.
     * Several requests may ask for this at once: only the first one talks to the server.
     */
    private fun renew(old: CloudSession): CloudSession = synchronized(renewLock) {
        // signed out while this request was on its way: its sign-in must not come back
        val current = live ?: throw CloudException(Kind.SESSION)
        // another request has renewed it already; the old refresh token is used up
        if (current.accessToken != old.accessToken) return current
        try {
            val fresh = api.refresh(current.refreshToken)
            store(fresh)
            fresh
        } catch (e: CloudException) {
            if (e.kind == Kind.SESSION) store(null)
            throw e
        }
    }

    /** Background thread. Runs [block] as the signed-in person, renewing the sign-in when the server asks for it. */
    private fun <T> authed(block: (CloudSession) -> T): T {
        var s = live ?: throw CloudException(Kind.SESSION)
        if (s.expiresAt - now() < 90) s = renew(s)
        return try {
            block(s)
        } catch (e: CloudException) {
            if (e.kind != Kind.SESSION) throw e
            block(renew(s))
        }
    }

    /** Background thread. As the signed-in person when there is one (they see their hidden items), else as nobody. */
    private fun <T> maybeAuthed(block: (CloudSession?) -> T): T {
        if (live == null) return block(null)
        return try {
            authed { block(it) }
        } catch (e: CloudException) {
            if (e.kind != Kind.SESSION) throw e
            block(null)
        }
    }

    private fun syncSession() {
        if (session != live) {
            session = live
            if (live == null) {
                me = null
                storage = null
            }
        }
    }

    private fun accept(s: CloudSession) {
        store(s)
        session = s
        askSignIn = false
        signInError = null
        leaveCodeStep()
        me = null
        accountLoaded = false
        loadAccount()
        refresh()
    }

    fun signIn(email: String, password: String) {
        if (signingIn) return
        if (email.isBlank() || password.isEmpty()) {
            signInError = Kind.INVALID_LOGIN
            return
        }
        signingIn = true
        signInError = null
        scope.launch {
            try {
                accept(io { api.signIn(email, password) })
            } catch (e: Exception) {
                if (kindOf(e) == Kind.CONFIRM_EMAIL) {
                    // the account exists but its address was never confirmed: the code may be long gone, so ask for a new letter
                    awaitCode(email, CodeStep.SIGN_UP, password, null, 0)
                    signingIn = false
                    resendCode(quiet = true)
                } else {
                    signInError = kindOf(e)
                }
            } finally {
                signingIn = false
            }
        }
    }

    fun signUp(email: String, password: String, username: String) {
        if (signingIn) return
        val name = username.trim()
        signInError = when {
            !validName(name) -> Kind.BAD_NAME
            !email.contains('@') -> Kind.BAD_EMAIL
            password.length < 6 -> Kind.WEAK_PASSWORD
            else -> null
        }
        if (signInError != null) return
        signingIn = true
        scope.launch {
            try {
                accept(io {
                    if (!api.usernameFree(name)) throw CloudException(Kind.NAME_TAKEN)
                    api.signUp(email, password, name)
                })
            } catch (e: Exception) {
                when (kindOf(e)) {
                    // the usual way: a letter with a code is on its way
                    Kind.CONFIRM_EMAIL -> awaitCode(email, CodeStep.SIGN_UP, password, name, MAIL_SECONDS)
                    // asked twice within a minute: the letter of the first time is the one to use
                    Kind.MAIL_WAIT -> awaitCode(email, CodeStep.SIGN_UP, password, name, (e as CloudException).waitSeconds)
                    else -> signInError = kindOf(e)
                }
            } finally {
                signingIn = false
            }
        }
    }

    /** Sends a letter with a code for choosing a new password. */
    fun forgotPassword(email: String) {
        if (signingIn) return
        if (!email.contains('@')) {
            signInError = Kind.BAD_EMAIL
            return
        }
        signingIn = true
        signInError = null
        scope.launch {
            try {
                io { api.requestPasswordReset(email) }
                awaitCode(email, CodeStep.PASSWORD, null, null, MAIL_SECONDS)
            } catch (e: Exception) {
                if (kindOf(e) == Kind.MAIL_WAIT) awaitCode(email, CodeStep.PASSWORD, null, null, (e as CloudException).waitSeconds)
                else signInError = kindOf(e)
            } finally {
                signingIn = false
            }
        }
    }

    private fun awaitCode(email: String, step: CodeStep, password: String?, name: String?, wait: Int) {
        codeFor = email.trim()
        codeStep = step
        pendingPassword = password
        pendingName = name
        signInError = null
        countDown(wait)
    }

    private fun countDown(seconds: Int) {
        resendJob?.cancel()
        resendIn = seconds.coerceIn(0, 600)
        if (resendIn == 0) return
        resendJob = scope.launch {
            while (resendIn > 0) {
                delay(1000)
                resendIn--
            }
        }
    }

    private fun leaveCodeStep() {
        resendJob?.cancel()
        resendIn = 0
        codeFor = null
        pendingPassword = null
        pendingName = null
    }

    /** Back from the code to the form (to correct the address). */
    fun cancelCode() {
        leaveCodeStep()
        signInError = null
    }

    /**
     * The code from the letter. For a new account that is all; for a forgotten password
     * [newPassword] is what the person wants to sign in with from now on.
     */
    fun confirmCode(code: String, newPassword: String = "") {
        val email = codeFor ?: return
        if (signingIn) return
        val step = codeStep
        signInError = when {
            code.trim().length < 6 -> Kind.BAD_CODE
            step == CodeStep.PASSWORD && newPassword.length < 6 -> Kind.WEAK_PASSWORD
            else -> null
        }
        if (signInError != null) return
        val password = pendingPassword
        val name = pendingName
        signingIn = true
        scope.launch {
            try {
                val fresh = io {
                    if (step == CodeStep.PASSWORD) return@io api.resetPassword(email, code, newPassword)
                    val s = api.confirmSignUp(email, code)
                    // When somebody asks for an account twice before confirming, the server keeps what was
                    // typed the first time. What was typed last is what the person remembers: make it so.
                    if (password != null) runCatching { api.setPassword(s, password) }
                    if (name != null) runCatching {
                        val profile = api.author(s.userId)
                        if (profile != null && profile.username != name && api.usernameFree(name)) {
                            api.updateProfile(s, name, profile.bio, profile.avatar)
                        }
                    }
                    s
                }
                accept(fresh)
                if (step == CodeStep.PASSWORD) say(R.string.cloud_password_changed)
            } catch (e: Exception) {
                signInError = kindOf(e)
            } finally {
                signingIn = false
            }
        }
    }

    /** Asks for the letter once more. [quiet]: say nothing when it works. */
    fun resendCode(quiet: Boolean = false) {
        val email = codeFor ?: return
        if (signingIn || resendIn > 0) return
        val step = codeStep
        signingIn = true
        scope.launch {
            try {
                io { if (step == CodeStep.PASSWORD) api.requestPasswordReset(email) else api.resendSignUpCode(email) }
                countDown(MAIL_SECONDS)
                signInError = null
                if (!quiet) say(R.string.cloud_code_resent)
            } catch (e: Exception) {
                if (kindOf(e) == Kind.MAIL_WAIT) countDown((e as CloudException).waitSeconds) else signInError = kindOf(e)
            } finally {
                signingIn = false
            }
        }
    }

    /** The browser page that starts "Sign in with Google"; the answer comes back through [onAuthRedirect]. */
    fun googleIntent(): Intent {
        val pkce = Pkce.create()
        prefs.edit().putString(KEY_PKCE, pkce.verifier).apply()
        return Intent(Intent.ACTION_VIEW, Uri.parse(api.googleUrl(REDIRECT, pkce.challenge)))
    }

    /** Called when the browser returns to the app after the sign-in with Google. */
    fun onAuthRedirect(uri: Uri) {
        val code = uri.getQueryParameter("code")
        val verifier = prefs.getString(KEY_PKCE, null)
        prefs.edit().remove(KEY_PKCE).apply()
        if (code.isNullOrEmpty() || verifier == null) {
            say(R.string.cloud_err_google)
            return
        }
        signingIn = true
        scope.launch {
            try {
                accept(io { api.exchangeCode(code, verifier) })
                visible = true
            } catch (e: Exception) {
                say(R.string.cloud_err_google)
            } finally {
                signingIn = false
            }
        }
    }

    fun signOut() {
        leaveCodeStep()
        val s = live ?: return
        store(null)
        session = null
        me = null
        storage = null
        closeAuthor()
        scope.launch {
            try {
                io { api.signOut(s) }
            } catch (_: Exception) {
            }
        }
        refresh()
    }

    /** Fetches what the screens need to know about the account and the server. */
    private fun loadAccount() {
        if (!api.configured || accountLoaded) return
        accountLoaded = true
        scope.launch {
            try {
                googleSignIn = io { api.authSettings() }.google
            } catch (e: Exception) {
                accountLoaded = false
            }
        }
        refreshMe()
    }

    private fun refreshMe() {
        if (live == null) return
        scope.launch {
            try {
                val (profile, use) = io { authed { s -> api.author(s.userId) to api.storageUse(s) } }
                syncSession()
                me = profile
                storage = use
            } catch (e: Exception) {
                syncSession()
            }
        }
    }

    fun saveProfile(username: String, bio: String, avatar: Int, onDone: (Kind?) -> Unit) {
        val name = username.trim()
        if (!validName(name)) {
            onDone(Kind.BAD_NAME)
            return
        }
        scope.launch {
            try {
                val profile = io { authed { s -> api.updateProfile(s, name, bio.trim().take(300), avatar.coerceIn(0, 15)); api.author(s.userId) } }
                me = profile
                if (authorId == profile?.id) author = profile
                say(R.string.cloud_profile_saved)
                onDone(null)
                refresh()
            } catch (e: Exception) {
                syncSession()
                onDone(kindOf(e))
            }
        }
    }

    /** Removes the account and everything it published. */
    fun deleteAccount() {
        if (live == null || !studio.beginBusy(text(R.string.cloud_busy_deleting))) return
        scope.launch {
            try {
                io {
                    authed { s ->
                        // the files first: once the account is gone nobody may remove them
                        while (true) {
                            val mine = api.feed(FeedQuery(owner = s.userId, limit = 100), s)
                            if (mine.isEmpty()) break
                            for (item in mine) api.deleteItem(s, item)
                        }
                        api.deleteAccount(s)
                    }
                }
                store(null)
                session = null
                me = null
                storage = null
                closeAuthor()
                say(R.string.cloud_account_deleted)
                refresh()
            } catch (e: Exception) {
                syncSession()
                report(e)
            } finally {
                studio.endBusy()
            }
        }
    }

    // ------------------------------------------------------------------ the catalogue

    fun show() {
        visible = true
        loadAccount()
        if (items.isEmpty() && !loading) refresh()
    }

    fun hide() {
        stopPlaying()
        visible = false
    }

    fun filter(kind: CloudKind?) {
        if (this.kind == kind && !hiddenOnly) return
        this.kind = kind
        hiddenOnly = false
        refresh()
    }

    fun sortBy(sort: CloudSort) {
        if (this.sort == sort) return
        this.sort = sort
        refresh()
    }

    fun searchFor(text: String) {
        val clean = text.take(40)
        if (search == clean) return
        search = clean
        refresh()
    }

    fun showHidden(only: Boolean) {
        if (hiddenOnly == only) return
        hiddenOnly = only
        refresh()
    }

    private fun query(offset: Int) =
        FeedQuery(if (hiddenOnly) null else kind, sort, search, hiddenOnly = hiddenOnly, limit = PAGE + 1, offset = offset)

    fun refresh() {
        val run = ++feedRun
        loadingMore = false
        if (!api.configured) {
            items = emptyList()
            canLoadMore = false
            loading = false
            failure = Kind.NOT_CONFIGURED
            return
        }
        loading = true
        failure = null
        val q = query(0)
        scope.launch {
            try {
                val list = io { maybeAuthed { s -> api.feed(q, s) } }
                if (run != feedRun) return@launch
                items = list.take(PAGE)
                canLoadMore = list.size > PAGE
            } catch (e: Exception) {
                if (run != feedRun) return@launch
                items = emptyList()
                canLoadMore = false
                failure = kindOf(e)
            }
            syncSession()
            loading = false
        }
    }

    fun loadMore() {
        if (loading || loadingMore || !canLoadMore) return
        val run = feedRun
        loadingMore = true
        val q = query(items.size)
        scope.launch {
            try {
                val list = io { maybeAuthed { s -> api.feed(q, s) } }
                if (run != feedRun) return@launch
                val known = items.map { it.id }.toSet()
                items = items + list.take(PAGE).filter { it.id !in known }
                canLoadMore = list.size > PAGE
            } catch (e: Exception) {
                if (run != feedRun) return@launch
                report(e)
            }
            loadingMore = false
        }
    }

    /** Puts a changed item into every list that shows it; null removes it. */
    private fun replace(id: String, item: CloudItem?) {
        fun List<CloudItem>.swap() = if (item == null) filter { it.id != id } else map { if (it.id == id) item else it }
        items = items.swap()
        authorItems = authorItems.swap()
        if (opened?.id == id) opened = item
    }

    fun open(item: CloudItem) {
        opened = item
        myStars = 0
        if (live != null && !isMine(item)) {
            scope.launch {
                try {
                    val stars = io { authed { s -> api.myRating(s, item.id) } }
                    if (opened?.id == item.id) myStars = stars
                } catch (_: Exception) {
                    syncSession()
                }
            }
        }
    }

    fun closeItem() {
        opened = null
    }

    fun showAuthor(id: String) {
        authorId = id
        author = if (me?.id == id) me else null
        authorItems = emptyList()
        authorLoading = true
        scope.launch {
            try {
                val (profile, list) = io { maybeAuthed { s -> api.author(id) to api.feed(FeedQuery(owner = id, limit = 100), s) } }
                if (authorId != id) return@launch
                author = profile
                authorItems = list
                if (profile != null && profile.id == live?.userId) me = profile
            } catch (e: Exception) {
                if (authorId == id) report(e)
            }
            if (authorId == id) authorLoading = false
            if (id == live?.userId) refreshMe()
        }
    }

    fun closeAuthor() {
        authorId = null
        author = null
        authorItems = emptyList()
        authorLoading = false
    }

    // ------------------------------------------------------------------ marks, complaints, removal

    /** [stars] 1..5; the mark already given, again, takes it back. */
    fun rate(item: CloudItem, stars: Int) {
        if (live == null) {
            askSignIn = true
            return
        }
        if (isMine(item)) return
        val wanted = if (stars == myStars) 0 else stars.coerceIn(1, 5)
        val before = myStars
        myStars = wanted
        scope.launch {
            try {
                val (sum, count) = io { authed { s -> api.rate(s, item.id, wanted) } }
                replace(item.id, (opened?.takeIf { it.id == item.id } ?: item).copy(ratingSum = sum, ratingCount = count))
            } catch (e: Exception) {
                if (opened?.id == item.id) myStars = before
                syncSession()
                report(e)
            }
        }
    }

    fun report(item: CloudItem, reason: String) {
        if (live == null) {
            askSignIn = true
            return
        }
        scope.launch {
            try {
                io { authed { s -> api.report(s, item.id, reason) } }
                say(R.string.cloud_report_sent)
            } catch (e: Exception) {
                syncSession()
                report(e)
            }
        }
    }

    fun delete(item: CloudItem) {
        if (live == null) return
        if (playingId == item.id || preparingId == item.id) stopPlaying()
        scope.launch {
            try {
                io { authed { s -> api.deleteItem(s, item) } }
                replace(item.id, null)
                say(R.string.cloud_deleted)
                refreshMe()
            } catch (e: Exception) {
                syncSession()
                report(e)
            }
        }
    }

    /** Moderators: hide an item from everyone, or show it again. */
    fun moderate(item: CloudItem, hide: Boolean) {
        scope.launch {
            try {
                val fresh = io { authed { s -> api.moderate(s, item.id, hide); api.item(item.id, s) } }
                if (hiddenOnly && fresh != null && !fresh.hidden) replace(item.id, null) else replace(item.id, fresh)
            } catch (e: Exception) {
                syncSession()
                report(e)
            }
        }
    }

    // ------------------------------------------------------------------ listening

    /** True when this item can be listened to before taking it. */
    fun canPlay(item: CloudItem): Boolean = when (item.cloudKind) {
        CloudKind.SOUND, CloudKind.TRACK -> item.filePath != null
        CloudKind.PROJECT -> item.previewPath != null
        CloudKind.PRESET -> PresetDemo.canPlay(item.device)
        null -> false
    }

    fun togglePlay(item: CloudItem) {
        if (playingId == item.id || preparingId == item.id) {
            stopPlaying()
            return
        }
        stopPlaying()
        if (!canPlay(item)) return
        val run = ++playRun
        preparingId = item.id
        when (item.cloudKind) {
            CloudKind.TRACK, CloudKind.PROJECT -> {
                val path = (if (item.cloudKind == CloudKind.TRACK) item.filePath else item.previewPath) ?: return
                player.play(
                    api.fileUrl(path),
                    onReady = {
                        if (run == playRun) {
                            preparingId = null
                            playingId = item.id
                            // a track is taken by listening to it
                            if (item.cloudKind == CloudKind.TRACK) countDownload(item)
                        }
                    },
                    onEnd = { if (run == playRun) playingId = null },
                    onError = {
                        if (run == playRun) {
                            preparingId = null
                            playingId = null
                            say(R.string.cloud_err_network)
                        }
                    },
                )
            }
            else -> scope.launch {
                try {
                    val data = io {
                        if (item.cloudKind == CloudKind.SOUND) Wav.read(fetch(item.filePath!!) { true })
                        else PresetDemo.render(item.device!!, paramsOf(item), File(cacheDir, "demo-$run.wav").also { cacheDir.mkdirs() })
                    }
                    if (run != playRun) return@launch
                    if (data == null) {
                        preparingId = null
                        say(R.string.cloud_preset_unknown)
                        return@launch
                    }
                    studio.engine.audition(data)
                    preparingId = null
                    playingId = item.id
                    // the engine says when the sound has run out
                    delay(150)
                    while (run == playRun && studio.engine.auditioning) delay(80)
                    if (run == playRun) playingId = null
                } catch (e: Exception) {
                    if (run == playRun) {
                        preparingId = null
                        playingId = null
                        report(e)
                    }
                }
            }
        }
    }

    fun stopPlaying() {
        playRun++
        if (playingId != null || preparingId != null) studio.engine.audition(null)
        player.stop()
        playingId = null
        preparingId = null
    }

    /** The knob positions of a published preset. */
    fun paramsOf(item: CloudItem): Map<String, Float> {
        val params = item.payload?.get("params") as? JsonObject ?: return emptyMap()
        val out = HashMap<String, Float>()
        for ((name, value) in params) (value as? JsonPrimitive)?.floatOrNull?.let { if (!it.isNaN()) out[name] = it }
        return out
    }

    // ------------------------------------------------------------------ taking things

    /** Background thread: the file behind [path], from the cache or the server. */
    private fun fetch(path: String, progress: (Float) -> Boolean): File {
        cacheDir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) } + "." + path.substringAfterLast('.', "bin").take(5)
        val file = File(cacheDir, name)
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        api.download(api.fileUrl(path), file, progress)
        trimCache(file)
        return file
    }

    private fun trimCache(keep: File) {
        val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_BYTES) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
        }
    }

    private fun countDownload(item: CloudItem) {
        // taking one's own work is not a download (the server would not count it either)
        if (isMine(item)) return
        scope.launch {
            try {
                val total = io { maybeAuthed { s -> api.addDownload(s, item.id, device) } }
                val shown = (opened?.takeIf { it.id == item.id } ?: items.firstOrNull { it.id == item.id } ?: item)
                if (total > shown.downloads) replace(item.id, shown.copy(downloads = total))
            } catch (_: Exception) {
                // counting is not worth a message
            }
        }
    }

    /** Takes an item: a sound goes to "My sounds", a preset to the presets, a project to the projects, a track to Music. */
    fun use(item: CloudItem, onProject: (String) -> Unit = {}) {
        when (item.cloudKind) {
            CloudKind.PRESET -> usePreset(item)
            CloudKind.SOUND, CloudKind.PROJECT, CloudKind.TRACK -> useFile(item, onProject)
            null -> {}
        }
    }

    private fun usePreset(item: CloudItem) {
        val type = Devices[item.device ?: ""]
        if (type == null) {
            say(R.string.cloud_preset_unknown)
            return
        }
        val params = paramsOf(item)
        scope.launch {
            try {
                io {
                    val taken = studio.presetStore.list(type.id).map { it.name.lowercase() }.toSet()
                    val base = item.title.trim().take(PresetStore.MAX_NAME - 3).ifEmpty { "Preset" }
                    val name = if (base.lowercase() !in taken) base else generateSequence(2) { it + 1 }.map { "$base $it" }.first { it.lowercase() !in taken }
                    // only what this version of the device knows, and inside its ranges
                    val clean = HashMap(type.defaults())
                    for ((id, value) in params) type.spec(id)?.let { clean[id] = value.coerceIn(it.min, it.max) }
                    studio.presetStore.save(type.id, UserPreset(name, clean))
                }
                studio.reloadLibrary()
                studio.message = String.format(text(R.string.cloud_preset_saved), text(type.nameRes))
                countDownload(item)
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    private fun useFile(item: CloudItem, onProject: (String) -> Unit) {
        // a track goes to the Music folder, which Android 9 lets the app write to only with a permission
        if (item.cloudKind == CloudKind.TRACK) studio.withMusicFolder { fetchFile(item, onProject) } else fetchFile(item, onProject)
    }

    private fun fetchFile(item: CloudItem, onProject: (String) -> Unit) {
        val path = item.filePath ?: return
        if (!studio.beginBusy(text(R.string.cloud_busy_downloading), 0f)) return
        cancelled = false
        scope.launch {
            var handedOver = false
            try {
                val file = io { fetch(path) { p -> studio.busyProgress(p); !cancelled } }
                when (item.cloudKind) {
                    CloudKind.SOUND -> {
                        io { studio.library.add(item.title, Wav.read(file)) }
                        studio.reloadLibrary()
                        say(R.string.cloud_sound_saved)
                    }
                    CloudKind.TRACK -> {
                        io { AudioExporter.saveToMusic(app, file, safeName(item.title) + ".mp3", "audio/mpeg") }
                        say(R.string.cloud_track_saved)
                    }
                    CloudKind.PROJECT -> {
                        // the studio shows its own progress and messages for this
                        studio.endBusy()
                        handedOver = true
                        studio.importProjectFile({ FileInputStream(file) }) { id -> if (id != null) onProject(id) }
                    }
                    else -> {}
                }
                countDownload(item)
            } catch (e: Exception) {
                report(e)
            } finally {
                if (!handedOver) studio.endBusy()
            }
        }
    }

    /** Stops the upload or download that the progress dialog is showing. */
    fun cancel() {
        cancelled = true
    }

    // ------------------------------------------------------------------ publishing

    /** Splits what was typed into tags: lower case, at most eight, no repeats. */
    fun tagsOf(text: String): List<String> =
        text.split(' ', ',', ';', '#', '\n', '\t').map { it.trim().lowercase().take(20) }.filter { it.isNotEmpty() }.distinct().take(8)

    private fun needSession(): Boolean {
        if (live != null) return true
        askSignIn = true
        return false
    }

    private fun validTitle(title: String): Boolean {
        if (title.trim().length in 2..60) return true
        say(R.string.cloud_title_short)
        return false
    }

    private fun publishJob(busyText: Int, onDone: (Boolean) -> Unit, work: () -> CloudItem) {
        if (!studio.beginBusy(text(busyText), 0f)) return
        cancelled = false
        scope.launch {
            var ok = false
            try {
                val item = io { work() }
                ok = true
                say(R.string.cloud_published)
                // the new item goes on top of what is shown, when it belongs there
                if (!hiddenOnly && (kind == null || kind == item.cloudKind) && search.isEmpty() && sort == CloudSort.NEW) {
                    items = listOf(item) + items.filter { it.id != item.id }
                }
                if (authorId == item.owner) authorItems = listOf(item) + authorItems
                refreshMe()
            } catch (e: Exception) {
                syncSession()
                report(e)
            } finally {
                studio.endBusy()
                onDone(ok)
            }
        }
    }

    /** Uploads [file] into the folder of the signed-in person and returns its path on the server. */
    private fun send(file: File, extension: String, mime: String, from: Float, to: Float): String = authed { s ->
        val path = s.userId + "/" + UUID.randomUUID().toString() + "." + extension
        api.upload(s, path, file, mime) { p ->
            studio.busyProgress(from + (to - from) * p)
            !cancelled
        }
        path
    }

    /** Publishes the row; when that fails the uploaded files are taken back so that they do not use up the quota. */
    private fun announce(item: NewItem): CloudItem = try {
        authed { s -> api.publish(s, item) }
    } catch (e: Exception) {
        try {
            authed { s -> api.deleteFiles(s, listOfNotNull(item.filePath, item.previewPath)) }
        } catch (_: Exception) {
        }
        throw e
    }

    fun publishSound(file: String, title: String, description: String, tags: String, onDone: (Boolean) -> Unit = {}) {
        if (!needSession() || !validTitle(title)) return
        val source = studio.library.fileOf(file)
        if (source.length() > MAX_SOUND_BYTES) {
            say(R.string.cloud_err_too_large)
            return
        }
        publishJob(R.string.cloud_busy_uploading, onDone) {
            val info = Wav.info(source) ?: throw CloudException(Kind.NOT_FOUND)
            val path = send(source, "wav", "audio/wav", 0f, 0.95f)
            announce(
                NewItem(
                    CloudKind.SOUND, title, description, tagsOf(tags), filePath = path, sizeBytes = source.length(),
                    durationMs = (info.seconds * 1000).toLong(),
                )
            )
        }
    }

    fun publishPreset(type: String, preset: UserPreset, title: String, description: String, tags: String, onDone: (Boolean) -> Unit = {}) {
        if (!needSession() || !validTitle(title)) return
        publishJob(R.string.cloud_busy_uploading, onDone) {
            val payload = buildJsonObject { putJsonObject("params") { for ((id, value) in preset.params) put(id, value) } }
            announce(NewItem(CloudKind.PRESET, title, description, tagsOf(tags), device = type, payload = payload))
        }
    }

    /**
     * Publishes a saved project: as a project file that others can open (with an MP3 to listen to
     * first), or - [asTrack] - as finished music.
     */
    fun publishProject(projectId: String, title: String, description: String, tags: String, asTrack: Boolean, onDone: (Boolean) -> Unit = {}) {
        if (!needSession() || !validTitle(title)) return
        if (projectId == studio.projectId) {
            if (studio.playing) studio.stop()
            studio.saveNow()
        }
        val open = if (projectId == studio.projectId) studio.project else null
        publishJob(R.string.cloud_busy_rendering, onDone) {
            val wav = File(cacheDir, "publish.wav")
            val mp3 = File(cacheDir, "publish.mp3")
            val pack = File(cacheDir, "publish.mps")
            cacheDir.mkdirs()
            try {
                val project = open ?: studio.store.load(projectId) ?: throw CloudException(Kind.NOT_FOUND)
                val pool = if (open != null) studio.samples else SamplePool().also { pool ->
                    for (name in project.usedSamples()) studio.store.loadSample(projectId, name)?.let { pool.put(name, it) }
                }
                val pattern = if (project.clips.isEmpty()) project.patterns.firstOrNull { !it.isEmpty }?.id ?: 0 else 0
                if (project.clips.isEmpty() && pattern == 0) throw Silent()
                val rendered = OfflineRenderer.render(project, pool, wav, RENDER_RATE, patternId = pattern, loops = 4) { p ->
                    studio.busyProgress(p * 0.45f)
                    !cancelled
                } ?: throw CloudException(Kind.CANCELLED)
                if (rendered.peak < 0.0005f) throw Silent()
                val millis = (rendered.seconds * 1000).toLong()

                if (asTrack) {
                    encode(wav, mp3, 192, project.name, 0.45f, 0.65f)
                    // long pieces get a lower bit rate rather than a refusal
                    if (mp3.length() > MAX_FILE_BYTES) encode(wav, mp3, 128, project.name, 0.45f, 0.65f)
                    if (mp3.length() > MAX_FILE_BYTES) throw CloudException(Kind.TOO_LARGE)
                    val path = send(mp3, "mp3", "audio/mpeg", 0.65f, 0.97f)
                    announce(
                        NewItem(
                            CloudKind.TRACK, title, description, tagsOf(tags), filePath = path, sizeBytes = mp3.length(),
                            durationMs = millis, bpm = project.bpm,
                        )
                    )
                } else {
                    FileOutputStream(pack).use { studio.store.exportTo(projectId, it) }
                    if (pack.length() > MAX_PROJECT_BYTES) throw CloudException(Kind.TOO_LARGE)
                    // the first minute and a half is enough to decide whether to download
                    val limit = 44L + PREVIEW_SECONDS * RENDER_RATE * 4L
                    if (wav.length() > limit) RandomAccessFile(wav, "rw").use { it.setLength(limit) }
                    encode(wav, mp3, 128, project.name, 0.45f, 0.6f)
                    val preview = send(mp3, "mp3", "audio/mpeg", 0.6f, 0.75f)
                    val path = try {
                        send(pack, "mps", "application/zip", 0.75f, 0.97f)
                    } catch (e: Exception) {
                        try {
                            authed { s -> api.deleteFiles(s, listOf(preview)) }
                        } catch (_: Exception) {
                        }
                        throw e
                    }
                    announce(
                        NewItem(
                            CloudKind.PROJECT, title, description, tagsOf(tags), filePath = path, previewPath = preview,
                            sizeBytes = pack.length() + mp3.length(), durationMs = millis, bpm = project.bpm,
                        )
                    )
                }
            } catch (e: Silent) {
                // not an error of the server: say what is wrong with the project instead
                scope.launch { say(R.string.cloud_silent_project) }
                throw CloudException(Kind.CANCELLED)
            } finally {
                wav.delete()
                mp3.delete()
                pack.delete()
            }
        }
    }

    private class Silent : Exception()

    private fun encode(wav: File, mp3: File, kbps: Int, title: String, from: Float, to: Float) {
        val done = Mp3Encoder.encode(wav, mp3, RENDER_RATE, kbps = kbps, title = title) { p ->
            studio.busyProgress(from + (to - from) * p)
            !cancelled
        }
        if (!done) throw CloudException(Kind.CANCELLED)
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(60).ifEmpty { "MPS" }

    companion object {
        /** Where the browser returns to after "Sign in with Google" (see the manifest). */
        const val REDIRECT = "jxpix.proger.mps://auth"

        /** The server sends one letter per address per minute. */
        const val MAIL_SECONDS = 60

        const val PAGE = 30
        const val MAX_SOUND_BYTES = 5L * 1024 * 1024
        const val MAX_PROJECT_BYTES = 8L * 1024 * 1024

        /** The server takes no file larger than this. */
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        private const val CACHE_BYTES = 60L * 1024 * 1024
        private const val RENDER_RATE = 44100
        private const val PREVIEW_SECONDS = 90

        private const val KEY_SESSION = "session"
        private const val KEY_DEVICE = "device"
        private const val KEY_PKCE = "pkce"

        /** 3-24 letters, digits, dots, dashes or underscores. */
        fun validName(name: String): Boolean =
            name.length in 3..24 && name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }
}
