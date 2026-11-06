package com.mindoo.db.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.mindoo.db.bridge.MindooValue
import com.mindoo.db.demo.ui.DemoTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Two app windows side by side (admin and member of one tenant) with
 * background sync turned off: both edit the same document, sync by hand, and
 * the open editors follow the merged result. Shows that saving only writes the
 * user's own changes (edits of the same text merge) and that an editor with
 * unsaved edits keeps them when a newer version arrives. Needs
 * `MINDOODB_TEST_SERVER_URL`; screenshots go to `build/screenshots/concurrent-*`.
 */
@OptIn(ExperimentalTestApi::class)
class ConcurrentEditTest {
    private val serverUrl = System.getenv("MINDOODB_TEST_SERVER_URL").orEmpty()
    private val bridge = Paths.get("..", "mindoodb-jvm-bridge", "dist", "index.js").toAbsolutePath().normalize()
    private val shots = File(System.getProperty("demo.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val states = mutableListOf<AppState>()

    @Test
    fun twoEditorsMergeAndFollowSync() {
        if (serverUrl.isBlank() || !Files.exists(bridge)) {
            println("[concurrent] skipped: set MINDOODB_TEST_SERVER_URL and build the sidecar")
            return
        }
        val admin = AppState(AppConfig(Files.createTempDirectory("demo-admin"), bridge)).also { states += it }
        val user = AppState(AppConfig(Files.createTempDirectory("demo-user"), bridge)).also { states += it }
        admin.startSidecar()
        user.startSidecar()
        try {
            runDesktopComposeUiTest(width = 2400, height = 820, testTimeout = 5.minutes) {
                setContent {
                    DemoTheme(dark = false) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) { App(admin, admin.config.dataRoot) }
                            VerticalDivider()
                            Box(Modifier.weight(1f)) { App(user, user.config.dataRoot) }
                        }
                    }
                }
                waitFor("sidecars") { admin.sidecar != null && user.sidecar != null }

                // --- Setup through the API: tenant, server, one document, a member ---
                admin.createTenant("Shop", "cn=admin/o=shop", "admin-pass", "cn=anna/o=shop", "anna-pass", listOf("notes"))
                waitFor("tenant") { admin.open != null && admin.busy == null }
                var docId = ""
                admin.run("setup") {
                    val session = admin.open!!.session
                    session.publishToServer(serverUrl, adminPassword = "admin-pass")
                    admin.updateProfile { it.copy(serverUrl = serverUrl) }
                    docId = session.database("notes").documents.create(mapOf(
                        "type" to MindooValue.atomic("list"),
                        "title" to "Shopping list",
                        "items" to "Milk\nBread\n",
                        "status" to MindooValue.atomic("open"),
                    )).id
                    session.refresh()
                }
                waitFor("published") { admin.busy == null && docId.isNotEmpty() }
                user.startJoin("cn=ben/o=shop", "ben-pass")
                waitFor("join request") { user.profiles.pendingJoins.isNotEmpty() && user.busy == null }
                val pending = user.profiles.pendingJoins.single()
                var response = ""
                admin.run("approve") {
                    response = admin.open!!.session.approveJoinRequest(pending.joinRequest, adminPassword = "admin-pass")
                        .joinResponse.jsonPrimitive.content
                }
                waitFor("approved") { response.isNotEmpty() && admin.busy == null }
                user.completeJoin(pending, response, "ben-pass", listOf("notes"))
                waitFor("joined") { user.open != null && user.busy == null }
                user.run("pull") { user.open!!.session.refresh() }
                waitFor("pulled") { user.busy == null }

                // --- Both turn background sync off and open the document ---
                click("Background sync", 0)
                waitFor("admin manual") { admin.open?.profile?.backgroundSync == false && admin.busy == null }
                click("Background sync", 1)
                waitFor("user manual") { user.open?.profile?.backgroundSync == false && user.busy == null }
                waitForText("Manual sync with", count = 2)
                click("Shopping list", 0)
                click("Shopping list", 1)
                waitFor("both editors") { editors().size == 2 }

                // --- Both edit the same text and save locally ---
                editor(0).performTextReplacement(editors()[0].replace("Bread\\n", "Bread\\nEggs\\n"))
                click("Save", 0)
                waitFor("admin saved") { admin.busy == null && "Eggs" in editors()[0] }
                editor(1).performTextReplacement(editors()[1].replace("\"Milk", "\"Oat milk"))
                click("Save", 1)
                waitFor("user saved") { user.busy == null && "Oat milk" in editors()[1] }
                // Background sync is off: after more than one interval, nothing arrived.
                Thread.sleep(7_000)
                waitForIdle()
                assertTrue("Oat milk" !in editors()[0] && "Eggs" !in editors()[1], "no background sync: ${editors()}")
                shot("concurrent-01-saved-locally")

                // --- Sync by hand: both open editors show the merged text ---
                click("Sync now", 0)
                waitFor("admin push") { admin.busy == null }
                click("Sync now", 1)
                waitFor("user merged") { user.busy == null && "Oat milk\\nBread\\nEggs\\n" in editors()[1] }
                click("Sync now", 0)
                waitFor("admin merged") { admin.busy == null && "Oat milk\\nBread\\nEggs\\n" in editors()[0] }
                shot("concurrent-02-merged")

                // --- Unsaved edits survive a newer version ---
                editor(0).performTextReplacement(editors()[0].replace("Shopping list", "Shopping list for Saturday"))
                editor(1).performTextReplacement(editors()[1].replace("\"open\"", "\"done\""))
                click("Save", 1)
                waitFor("user saved status") { user.busy == null && "\"done\"" in editors()[1] && "edited" !in texts(1) }
                click("Sync now", 1)
                waitFor("user pushed") { user.busy == null }
                click("Sync now", 0)
                waitForText("Changed elsewhere while you edit", substring = true)
                assertTrue("Shopping list for Saturday" in editors()[0] && "\"open\"" in editors()[0], "unsaved edit kept: ${editors()[0]}")
                shot("concurrent-03-incoming-while-editing")
                click("Save", 0)
                waitFor("admin merged both") {
                    admin.busy == null && "Shopping list for Saturday" in editors()[0] && "\"done\"" in editors()[0]
                }
                shot("concurrent-04-saved-merge")
                click("Sync now", 0)
                waitFor("admin pushed") { admin.busy == null }
                click("Sync now", 1)
                waitFor("user sees title") { user.busy == null && "Shopping list for Saturday" in editors()[1] }

                // The stored values kept their types: status stays atomic, items stays text.
                var types: Map<String, String> = emptyMap()
                user.run("types") { types = user.open!!.session.database("notes").documents.get(docId, withFieldTypes = true).fieldTypes }
                waitFor("types") { user.busy == null && types.isNotEmpty() }
                assertEquals("atomic", types["/status"])
                assertEquals(null, types["/items"])
            }
        } finally {
            admin.shutdown()
            user.shutdown()
        }
    }

    // ----- helpers -----

    /** The JSON editors' text, left window first. */
    private fun ComposeUiTest.editors(): List<String> =
        onAllNodes(hasSetTextAction() and hasText("\"title\"", substring = true)).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }

    private fun ComposeUiTest.editor(index: Int) =
        onAllNodes(hasSetTextAction() and hasText("\"title\"", substring = true))[index]

    /** All texts of one window (0 = left), to check chips and banners. */
    private fun ComposeUiTest.texts(window: Int): String {
        val all = onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
        val mid = onRoot().fetchSemanticsNode().size.width / 2
        return all.filter { (it.boundsInRoot.left < mid) == (window == 0) }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("|") { it.text }
    }

    private fun ComposeUiTest.click(text: String, index: Int = 0) {
        val nodes = onAllNodes(hasClickAction() and (hasText(text) or hasTextInChildren(text)))
        nodes[index].performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun hasTextInChildren(text: String) =
        SemanticsMatcher("has child with text $text") { node ->
            node.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { it.text.trim() == text } == true }
        }

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = true, count: Int = 1) {
        waitFor("text '$text'") { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size >= count }
    }

    private fun ComposeUiTest.waitFor(what: String, condition: () -> Boolean) {
        try {
            waitUntil(timeoutMillis = 60_000) {
                states.firstNotNullOfOrNull { it.error }?.let { throw AssertionError("While waiting for $what: $it") }
                condition()
            }
        } catch (t: androidx.compose.ui.test.ComposeTimeoutException) {
            runCatching { shot("concurrent-fail-${what.replace(' ', '-')}") }
            val stored = states.map { st ->
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        val db = st.open!!.session.database("notes")
                        db.query(com.mindoo.db.bridge.mindooQuery { limit = 5 }).rows.map { r -> r.lastModified to db.documents.get(r.docId).data }
                    }
                }.getOrElse { it.toString() }
            }
            throw AssertionError("Timed out waiting for $what (busy: ${states.map { it.busy }}, editors: ${runCatching { editors() }.getOrNull()}, stored: $stored)", t)
        }
        waitForIdle()
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))
    }
}
