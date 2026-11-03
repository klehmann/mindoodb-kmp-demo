package com.mindoo.db.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mindoo.db.bridge.BridgeEndpoint
import com.mindoo.db.bridge.MindooDBBridge
import com.mindoo.db.bridge.MindooDBSession
import com.mindoo.db.bridge.Sidecar
import com.mindoo.db.bridge.SidecarProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Where the app keeps its data and finds the sidecar. */
data class AppConfig(val dataRoot: Path, val bridgeScript: Path)

/** An open tenant: the session plus the profile it was opened from. */
class OpenTenant(val profile: Profile, val session: MindooDBSession)

/**
 * App-wide state: the sidecar, the saved profiles, the open session, and one
 * place for busy flags and error messages. All UI reads it through Compose state.
 */
class AppState(val config: AppConfig) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val store = ProfileStore(config.dataRoot)

    var sidecar by mutableStateOf<SidecarProcess?>(null)
        private set
    var sidecarError by mutableStateOf<String?>(null)
        private set
    var profiles by mutableStateOf(store.load())
        private set
    var open by mutableStateOf<OpenTenant?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    val endpoint: BridgeEndpoint get() = sidecar?.endpoint ?: error("The sidecar is not running")

    fun startSidecar() {
        scope.launch {
            busy = "Starting the MindooDB engine…"
            try {
                sidecar = withContext(Dispatchers.IO) { Sidecar.start(config.bridgeScript, config.dataRoot) }
            } catch (t: Throwable) {
                sidecarError = "Could not start the sidecar at ${config.bridgeScript}: ${t.message}"
            } finally {
                busy = null
            }
        }
    }

    /** Run a long operation with a busy label; errors land in [error]. */
    fun run(label: String, block: suspend () -> Unit) {
        scope.launch {
            busy = label
            try {
                block()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                busy = null
            }
        }
    }

    fun createTenant(
        tenantId: String, adminName: String, adminPassword: String,
        userId: String, password: String, databases: List<String>,
    ) = run("Creating tenant “$tenantId”…") {
        MindooDBBridge.createTenant(endpoint, tenantId, adminName, adminPassword, userId, password, databases)
        val profile = Profile(tenantId, userId, databases, isAdmin = true)
        profiles = store.upsertProfile(profile)
        openSession(profile, password)
    }

    /** Step 1 of joining: a new identity and its join request, kept until the admin answers. */
    fun startJoin(userId: String, password: String) = run("Creating identity and join request…") {
        val user = MindooDBBridge.createUser(endpoint, userId, password)
        val request = MindooDBBridge.createJoinRequest(endpoint, user.identity)
        profiles = store.setPendingJoin(PendingJoin(userId, user.identity, request.joinRequest), userId)
    }

    /** Step 2 of joining: the admin's response; pulls the directory, then opens the tenant. */
    fun completeJoin(pending: PendingJoin, joinResponse: String, password: String, databases: List<String>) =
        run("Joining the tenant…") {
            val joined = MindooDBBridge.joinTenant(
                endpoint, kotlinx.serialization.json.JsonPrimitive(joinResponse.trim()),
                pending.identity, password, databases = databases,
            )
            val profile = Profile(joined.tenantId, joined.userId, databases, serverUrl = joined.serverUrl)
            store.setPendingJoin(null, pending.userId)
            profiles = store.upsertProfile(profile)
            openSession(profile, password)
        }

    fun discardPendingJoin(pending: PendingJoin) {
        profiles = store.setPendingJoin(null, pending.userId)
    }

    fun openProfile(profile: Profile, password: String) = run("Opening ${profile.tenantId}…") {
        openSession(profile, password)
    }

    private suspend fun openSession(profile: Profile, password: String) {
        val session = MindooDBBridge.openSession(endpoint, profile.tenantId, profile.userId, password, profile.databases)
        open = OpenTenant(profile, session)
    }

    /** Remember a newly opened database or server URL for the next start. */
    fun updateProfile(transform: (Profile) -> Profile) {
        val current = open ?: return
        val next = transform(current.profile)
        profiles = store.upsertProfile(next)
        open = OpenTenant(next, current.session)
    }

    fun closeTenant() = run("Closing…") {
        open?.session?.close()
        open = null
    }

    fun shutdown() {
        runBlocking { runCatching { open?.session?.close() } }
        sidecar?.stop()
    }
}
