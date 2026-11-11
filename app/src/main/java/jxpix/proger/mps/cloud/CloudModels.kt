package jxpix.proger.mps.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** What can be shared. The ids are the values of `mps_items.kind` on the server. */
enum class CloudKind(val id: String) {
    SOUND("sound"), PRESET("preset"), PROJECT("project"), TRACK("track");

    companion object {
        fun of(id: String): CloudKind? = entries.firstOrNull { it.id == id }
    }
}

enum class CloudSort { NEW, POPULAR, BEST }

/** One published thing, as the server lists it (view `mps_feed`). */
@Serializable
data class CloudItem(
    val id: String,
    val owner: String = "",
    val kind: String = "",
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    /** Path of the file in the storage; presets have none. */
    @SerialName("file_path") val filePath: String? = null,
    /** Projects: an MP3 to listen to before downloading. */
    @SerialName("preview_path") val previewPath: String? = null,
    @SerialName("size_bytes") val sizeBytes: Int = 0,
    @SerialName("duration_ms") val durationMs: Int = 0,
    val bpm: Float? = null,
    /** Presets: type id of the instrument or effect. */
    val device: String? = null,
    /** Presets: `{"params": {"cutoff": 0.4, ...}}`. */
    val payload: JsonObject? = null,
    val downloads: Int = 0,
    @SerialName("rating_sum") val ratingSum: Int = 0,
    @SerialName("rating_count") val ratingCount: Int = 0,
    val reports: Int = 0,
    val hidden: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    val author: String = "",
    @SerialName("author_avatar") val authorAvatar: Int = 0,
) {
    val cloudKind: CloudKind? get() = CloudKind.of(kind)

    /** Average mark, 0 when nobody has rated it yet. */
    val rating: Float get() = if (ratingCount > 0) ratingSum.toFloat() / ratingCount else 0f
}

/** An author with the totals of their page (view `mps_authors`). */
@Serializable
data class CloudProfile(
    val id: String,
    val username: String = "",
    val bio: String = "",
    val avatar: Int = 0,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    val items: Int = 0,
    val downloads: Int = 0,
    @SerialName("rating_sum") val ratingSum: Int = 0,
    @SerialName("rating_count") val ratingCount: Int = 0,
) {
    val rating: Float get() = if (ratingCount > 0) ratingSum.toFloat() / ratingCount else 0f
}

/** A signed-in person. [expiresAt] is in seconds since 1970. */
@Serializable
data class CloudSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String = "",
)

/** What the app sends to publish something. */
class NewItem(
    val kind: CloudKind,
    val title: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val filePath: String? = null,
    val previewPath: String? = null,
    val sizeBytes: Long = 0,
    val durationMs: Long = 0,
    val bpm: Float? = null,
    val device: String? = null,
    val payload: JsonObject? = null,
)

/** Which part of the catalogue to list. */
class FeedQuery(
    val kind: CloudKind? = null,
    val sort: CloudSort = CloudSort.NEW,
    val search: String = "",
    val owner: String? = null,
    val device: String? = null,
    /** For moderators: only what has been hidden after complaints. */
    val hiddenOnly: Boolean = false,
    val limit: Int = 30,
    val offset: Int = 0,
)

class AuthSettings(val google: Boolean, val signUpOpen: Boolean)

class StorageUse(val used: Long, val quota: Long)

/** Anything that went wrong while talking to the server, sorted by what the person can do about it. */
class CloudException(
    val kind: Kind,
    detail: String? = null,
    val status: Int = 0,
    /** For [Kind.MAIL_WAIT]: how long until another letter may be asked for. */
    val waitSeconds: Int = 0,
) : IOException(kind.name + (if (detail.isNullOrEmpty()) "" else ": $detail")) {
    enum class Kind {
        /** No server address or key was built into the app. */
        NOT_CONFIGURED,
        NETWORK,
        /** Not signed in, or the sign-in has expired. */
        SESSION,
        INVALID_LOGIN,
        EMAIL_TAKEN,
        WEAK_PASSWORD,
        BAD_EMAIL,
        /** The address has to be confirmed first, with the code from the letter that was sent to it. */
        CONFIRM_EMAIL,
        /** The code from the letter is wrong, used up or too old. */
        BAD_CODE,
        /** A letter went to this address less than a minute ago; the next one has to wait. */
        MAIL_WAIT,
        SIGNUP_CLOSED,
        NAME_TAKEN,
        BAD_NAME,
        RATE_LIMIT,
        /** The personal quota, or the whole storage, is full. */
        NO_ROOM,
        TOO_LARGE,
        DAILY_LIMIT,
        NOT_ALLOWED,
        NOT_FOUND,
        CANCELLED,
        SERVER,
    }
}
