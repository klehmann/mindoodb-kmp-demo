package com.mindoo.db.demo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path

/** A local identity this app can open again (no secrets: identities on disk are password-locked). */
@Serializable
data class Profile(
    val tenantId: String,
    val userId: String,
    val databases: List<String>,
    /** True when this profile created the tenant, so the bundle holds the tenant admin. */
    val isAdmin: Boolean = false,
    val serverUrl: String? = null,
    /** The tenant's human-readable name (the ID is generated). */
    val tenantLabel: String? = null,
    /** Off: changes stay on this machine until "Sync now" (handy to test concurrent edits). */
    val backgroundSync: Boolean = true,
) {
    val displayName: String get() = tenantLabel ?: tenantId
}

/** A join that waits for the admin's response: the identity is password-locked JSON. */
@Serializable
data class PendingJoin(
    val userId: String,
    val identity: JsonElement,
    val joinRequest: JsonElement,
)

@Serializable
data class ProfileFile(
    val profiles: List<Profile> = emptyList(),
    val pendingJoins: List<PendingJoin> = emptyList(),
)

/** `profiles.json` in the data directory. */
class ProfileStore(private val dataRoot: Path) {
    private val file: Path = dataRoot.resolve("profiles.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): ProfileFile =
        if (Files.exists(file)) json.decodeFromString(ProfileFile.serializer(), Files.readString(file)) else ProfileFile()

    fun save(content: ProfileFile) {
        Files.createDirectories(dataRoot)
        Files.writeString(file, json.encodeToString(ProfileFile.serializer(), content))
    }

    fun upsertProfile(profile: Profile): ProfileFile {
        val current = load()
        val others = current.profiles.filterNot { it.tenantId == profile.tenantId && it.userId == profile.userId }
        return current.copy(profiles = others + profile).also(::save)
    }

    fun setPendingJoin(pending: PendingJoin?, userId: String): ProfileFile {
        val current = load()
        val others = current.pendingJoins.filterNot { it.userId == userId }
        return current.copy(pendingJoins = others + listOfNotNull(pending)).also(::save)
    }
}
