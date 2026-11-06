package com.mindoo.db.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.getOrNull
import com.mindoo.db.demo.ui.DemoTheme
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The "How it works" flow through the real UI of two app instances (admin and
 * new user, separate data directories and sidecars) against a MindooDB server:
 * create a tenant, publish, write documents, request access, inspect and
 * approve, join, see the admin's documents. Saves a screenshot of each step to
 * `build/screenshots`. Needs `MINDOODB_TEST_SERVER_URL`.
 */
@OptIn(ExperimentalTestApi::class)
class WalkthroughTest {
    private val serverUrl = System.getenv("MINDOODB_TEST_SERVER_URL").orEmpty()
    private val bridge = Paths.get("..", "mindoodb-jvm-bridge", "dist", "index.js").toAbsolutePath().normalize()
    private val shots = File(System.getProperty("demo.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @Test
    fun adminAndNewUser() {
        if (serverUrl.isBlank() || !Files.exists(bridge)) {
            println("[walkthrough] skipped: set MINDOODB_TEST_SERVER_URL and build the sidecar")
            return
        }
        val admin = AppState(AppConfig(Files.createTempDirectory("demo-admin"), bridge)).also { states += it }
        val user = AppState(AppConfig(Files.createTempDirectory("demo-user"), bridge)).also { states += it }
        admin.startSidecar()
        user.startSidecar()
        try {
            // -Ddemo.density=2 renders the screenshots at twice the resolution (blog, README)
            val density = (System.getProperty("demo.density") ?: "1").toFloat()
            runDesktopComposeUiTest(
                width = (1320 * density).toInt(), height = (860 * density).toInt(), testTimeout = 5.minutes,
            ) {
                var active by mutableStateOf(admin)
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(density)) {
                        DemoTheme(dark = false) { App(active, active.config.dataRoot) }
                    }
                }
                waitFor("sidecars") { admin.sidecar != null && user.sidecar != null }

                // --- Admin: create the tenant ---
                type("Tenant name", "Demo Corp")
                type("Admin name", "cn=admin/o=demo")
                type("Admin password", "admin-pass")
                type("Your user name", "cn=anna/o=demo")
                type("Your password", "anna-pass")
                type("Databases", "notes")
                shot("01-create-tenant")
                click("Create tenant")
                waitFor("tenant open") { admin.open != null && admin.busy == null }
                assertTrue(Regex("^[0-9a-f]{24}$").matches(admin.open!!.profile.tenantId), "generated tenant ID")
                assertEquals("Demo Corp", admin.open!!.profile.tenantLabel)

                // --- Admin: publish ---
                click("Server & sync")
                waitForText("MindooDB Demo Server 1")   // one-click preset is offered and preselected
                // index 1: the first admin password field belongs to the rename section
                type("Admin password", "admin-pass", index = 1)
                shot("02-choose-server")
                type("Or your own server", serverUrl)
                click("Publish")
                waitFor("published") { admin.open?.profile?.serverUrl != null && admin.busy == null }
                shot("02-published")

                // --- Admin: rename the tenant ---
                type("Tenant name", "Demo Corp Europe")
                type("Admin password", "admin-pass")
                click("Rename")
                waitFor("renamed") { admin.open?.profile?.tenantLabel == "Demo Corp Europe" && admin.busy == null }
                shot("02b-tenant-renamed")

                // --- Admin: two documents ---
                click("notes")
                newDocument("{\n  \"type\": \"note\",\n  \"title\": \"Quarterly report\",\n  \"status\": {\"\$mindoo\": \"atomic\", \"value\": \"draft\"}\n}")
                newDocument("{\n  \"type\": \"note\",\n  \"title\": \"Team offsite\",\n  \"place\": \"Lake Constance\"\n}")
                waitForText("Quarterly report")
                admin.run("sync") { admin.open!!.session.refresh() }
                waitFor("synced") { admin.busy == null }
                shot("03-admin-browser")

                // --- New user: request access ---
                active = user
                waitForIdle()
                click("Join a tenant")
                type("Your user name", "cn=ben/o=demo")
                type("Your password", "ben-pass")
                click("Create join request")
                waitFor("join request") { user.profiles.pendingJoins.isNotEmpty() && user.busy == null }
                shot("04-join-request")
                val joinRequest = user.profiles.pendingJoins.single().joinRequest.jsonPrimitive.content

                // --- Admin: inspect and approve ---
                active = admin
                waitForIdle()
                click("Join requests")
                type("Join request", joinRequest)
                click("Check request")
                waitForText("Who is asking")
                // The admin sees the fingerprint the joining device shows.
                val fp = signingFingerprintOf(user.profiles.pendingJoins.single().identity)!!
                waitForText(fp)
                shot("05-inspect-request")
                type("Admin password", "admin-pass")
                click("Approve")
                waitForText("Send this back to the new user", substring = true)
                shot("06-approved")
                val response = onAllNodes(hasText("mdb://join-response", substring = true)).fetchSemanticsNodes()
                    .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") }
                    .first { it.startsWith("mdb://join-response") }

                // --- New user: join and see the data ---
                active = user
                waitForIdle()
                type("Join response", response)
                type("Your password", "ben-pass", index = 0)
                click("Join tenant")
                waitFor("joined") { user.open != null && user.busy == null }
                waitFor("label") { user.open?.profile?.tenantLabel == "Demo Corp Europe" }
                user.run("sync") { user.open!!.session.refresh() }
                waitFor("user synced") { user.busy == null }
                waitForText("Quarterly report")
                click("Quarterly report")
                waitForText("draft", substring = true)
                shot("07-user-sees-documents")

                // The user edits; the admin sees it after a sync.
                assertEquals("cn=ben/o=demo", user.open!!.profile.userId)
                onAllNodes(hasSetTextAction() and hasText("Quarterly report", substring = true))[0]
                    .performTextReplacement("{\n  \"type\": \"note\",\n  \"title\": \"Quarterly report (reviewed by Ben)\",\n  \"status\": \"draft\"\n}")
                click("Save")
                waitForText("Quarterly report (reviewed by Ben)")
                click("History")
                waitForText("by key", substring = true)
                shot("08-user-history")
                click("Server & sync")
                click("Sync now", index = 0)
                waitFor("user sync") { user.busy == null }
                shot("09-user-sync")

                active = admin
                waitForIdle()
                click("notes")
                admin.run("sync") { admin.open!!.session.refresh() }
                waitFor("admin sync") { admin.busy == null }
                waitForText("Quarterly report (reviewed by Ben)")
                shot("10-admin-sees-edit")
            }
        } finally {
            admin.shutdown()
            user.shutdown()
        }
        assertTrue(File(shots, "07-user-sees-documents.png").exists())
    }

    // ----- helpers -----

    private val states = mutableListOf<AppState>()

    private fun ComposeUiTest.type(label: String, text: String, index: Int = 0) {
        val nodes = onAllNodes(hasSetTextAction() and hasText(label))
        if (nodes.fetchSemanticsNodes().size <= index) {
            shot("fail-type-${label.replace(' ', '-')}")
            val tree = onRoot().printToString(maxDepth = 60)
            throw AssertionError("No text field '$label'. Fields:\n" + tree.lines().filter { "SetText" in it || "Text = " in it }.take(60).joinToString("\n"))
        }
        nodes[index].performTextReplacement(text)
    }

    private fun ComposeUiTest.click(text: String, index: Int = 0) {
        val nodes = onAllNodes(hasClickAction() and (hasText(text) or hasTextInChildren(text)), useUnmergedTree = false)
        nodes[index].performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun hasTextInChildren(text: String) =
        SemanticsMatcher("has child with text $text") { node ->
            node.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { it.text.trim() == text } == true }
        }

    private fun ComposeUiTest.newDocument(json: String) {
        click("New")
        onAllNodes(hasSetTextAction() and hasText("\"type\"", substring = true))[0].performTextReplacement(json)
        click("Create")
        waitFor("created") { true }
        Thread.sleep(400)
        waitForIdle()
    }

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false) {
        waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.waitFor(what: String, condition: () -> Boolean) {
        try {
            waitUntil(timeoutMillis = 60_000) {
                states.firstNotNullOfOrNull { it.error }?.let { throw AssertionError("While waiting for $what: $it") }
                condition()
            }
        } catch (t: androidx.compose.ui.test.ComposeTimeoutException) {
            runCatching { shot("fail-${what.replace(' ', '-')}") }
            throw AssertionError("Timed out waiting for $what (busy: ${states.map { it.busy }}, servers: ${states.map { it.open?.profile?.serverUrl }})", t)
        }
        waitForIdle()
    }

    private fun ComposeUiTest.shot(name: String) {
        states.forEach { it.notice = null }   // no snackbar over the screenshot
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(shots, "$name.png"))
        assertNotNull(image)
    }
}
