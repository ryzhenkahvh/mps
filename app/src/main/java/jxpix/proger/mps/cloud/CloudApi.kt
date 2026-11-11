package jxpix.proger.mps.cloud

import java.io.File

/**
 * Everything the app asks of the server. All calls block and throw [CloudException]:
 * they are made from a background thread.
 *
 * Kept as an interface so that the screens can be tried out without a network.
 */
interface CloudApi {
    /** False when the app was built without a server address: the community section explains that. */
    val configured: Boolean

    // ---- accounts
    fun authSettings(): AuthSettings
    /**
     * Creates the account. Where the server confirms addresses (the usual case) it sends a letter
     * with a code and this throws [CloudException.Kind.CONFIRM_EMAIL]: the account is ready once
     * [confirmSignUp] got that code.
     */
    fun signUp(email: String, password: String, username: String): CloudSession

    /** The code from the letter that followed [signUp]; the person is signed in. */
    fun confirmSignUp(email: String, code: String): CloudSession

    /** Asks for the letter with the sign-up code once more. */
    fun resendSignUpCode(email: String)

    /** Throws [CloudException.Kind.CONFIRM_EMAIL] for an account whose address is not confirmed yet. */
    fun signIn(email: String, password: String): CloudSession

    /** Sends a letter with a code for choosing a new password. Does not tell whether the address is known. */
    fun requestPasswordReset(email: String)

    /** The code from that letter and the new password; the person is signed in. */
    fun resetPassword(email: String, code: String, newPassword: String): CloudSession
    fun setPassword(session: CloudSession, password: String)
    fun refresh(refreshToken: String): CloudSession
    fun signOut(session: CloudSession)

    /** Address to open in the browser for "Sign in with Google"; it comes back to [redirect] with a code. */
    fun googleUrl(redirect: String, challenge: String): String
    fun exchangeCode(code: String, verifier: String): CloudSession
    fun deleteAccount(session: CloudSession)

    // ---- authors
    fun author(id: String): CloudProfile?
    fun usernameFree(name: String): Boolean
    fun updateProfile(session: CloudSession, username: String, bio: String, avatar: Int)

    // ---- the catalogue
    fun feed(query: FeedQuery, session: CloudSession?): List<CloudItem>
    fun item(id: String, session: CloudSession?): CloudItem?
    fun publish(session: CloudSession, item: NewItem): CloudItem
    fun updateItem(session: CloudSession, id: String, title: String, description: String, tags: List<String>)

    /** Removes the item and its files. */
    fun deleteItem(session: CloudSession, item: CloudItem)

    /** The mark the signed-in person gave, 0 for none. */
    fun myRating(session: CloudSession, itemId: String): Int

    /** [stars] 1..5, or 0 to take the mark back. Returns the new sum and count of the item. */
    fun rate(session: CloudSession, itemId: String, stars: Int): Pair<Int, Int>
    fun report(session: CloudSession, itemId: String, reason: String)

    /** Counts a download once per person (or per installation, [device], when not signed in). */
    fun addDownload(session: CloudSession?, itemId: String, device: String): Int
    fun moderate(session: CloudSession, itemId: String, hide: Boolean)

    // ---- files
    fun storageUse(session: CloudSession): StorageUse

    /** Uploads [file] to [path] (which must start with the id of the person). [progress] returns false to cancel. */
    fun upload(session: CloudSession, path: String, file: File, mime: String, progress: (Float) -> Boolean = { true })
    fun deleteFiles(session: CloudSession, paths: List<String>)
    fun fileUrl(path: String): String
    fun download(url: String, to: File, progress: (Float) -> Boolean = { true })
}
