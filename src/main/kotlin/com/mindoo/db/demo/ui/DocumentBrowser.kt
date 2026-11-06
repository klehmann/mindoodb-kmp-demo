package com.mindoo.db.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindoo.db.bridge.AttachmentInfo
import com.mindoo.db.bridge.Database
import com.mindoo.db.bridge.HistoryEntry
import com.mindoo.db.bridge.MindooDocument
import com.mindoo.db.bridge.QueryRow
import com.mindoo.db.bridge.SortDirection
import com.mindoo.db.bridge.TextClause
import com.mindoo.db.bridge.asByteFlow
import com.mindoo.db.bridge.mindooQuery
import com.mindoo.db.demo.AppState
import com.mindoo.db.demo.OpenTenant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

private val prettyJson = Json { prettyPrint = true }
private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

/** What the right-hand editor shows. */
private sealed interface Selection {
    data object None : Selection
    data object New : Selection
    data class Existing(val docId: String) : Selection
}

@Composable
fun DocumentBrowser(state: AppState, tenant: OpenTenant, dbId: String) {
    val db = remember(tenant.session, dbId) { tenant.session.database(dbId) }
    var search by remember(dbId) { mutableStateOf("") }
    var fulltext by remember(dbId) { mutableStateOf(false) }
    var rows by remember(dbId) { mutableStateOf<List<QueryRow>>(emptyList()) }
    var total by remember(dbId) { mutableIntStateOf(0) }
    var selection by remember(dbId) { mutableStateOf<Selection>(Selection.None) }
    // Counts the live query's results: the open document re-checks its heads on each.
    // (`_lastModified` alone is not enough: after a merge it can keep a value seen before.)
    var listVersion by remember(dbId) { mutableIntStateOf(0) }

    // Live list: re-emits after every local change and every sync.
    LaunchedEffect(db, fulltext, if (fulltext) search else "") {
        val query = mindooQuery {
            if (fulltext && search.isNotBlank()) {
                text = TextClause(search)
                sortByTextScore()
            } else {
                sort("_lastModified", SortDirection.DESCENDING)
            }
            limit = 500
        }
        try {
            db.liveQuery(query).collect { result ->
                rows = result.rows
                total = result.total
                listVersion++
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            state.error = t.message
        }
    }
    val visible = if (fulltext || search.isBlank()) rows else rows.filter { row ->
        row.docId.contains(search, ignoreCase = true) || row.fields.toString().contains(search, ignoreCase = true)
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(380.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dbId, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(onClick = { selection = Selection.New }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New")
                }
            }
            OutlinedTextField(
                search, { search = it },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text(if (fulltext) "Full-text search" else "Filter by ID or content") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(fulltext, onCheckedChange = { on ->
                    if (on) {
                        state.run("Enabling full-text search…") {
                            db.setFulltextSetup(buildJsonObject { put("enabled", true) })
                            fulltext = true
                        }
                    } else fulltext = false
                })
                Text("Full-text index", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text("${visible.size} of $total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visible, key = { it.docId }) { row ->
                    DocumentRow(row, selection == Selection.Existing(row.docId)) { selection = Selection.Existing(row.docId) }
                }
            }
        }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (val s = selection) {
                Selection.None -> Placeholder()
                Selection.New -> NewDocumentEditor(state, db) { selection = Selection.Existing(it) }
                is Selection.Existing -> {
                    DocumentDetail(state, db, s.docId, listVersion) { selection = Selection.None }
                }
            }
        }
    }
}

@Composable
private fun Placeholder() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Description, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        Text("Select a document or create one", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** First short text field of a summary row, as its title. */
private fun titleOf(row: QueryRow): String {
    val preferred = listOf("title", "name", "subject", "customer", "type")
    for (key in preferred) {
        (row.fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { return it }
    }
    return row.fields.entries.firstOrNull { !it.key.startsWith("_") && it.value is JsonPrimitive && (it.value as JsonPrimitive).isString }
        ?.let { (it.value as JsonPrimitive).content } ?: row.docId
}

@Composable
private fun DocumentRow(row: QueryRow, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(titleOf(row), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row {
            Text(row.docId, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
            if (row.lastModified > 0) {
                Text(timeFormat.format(Date(row.lastModified)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The document's own fields, without the `_` fields MindooDB manages. */
private fun editableJson(data: JsonObject): String =
    prettyJson.encodeToString(JsonObject.serializer(), JsonObject(data.filterKeys { !it.startsWith("_") }))

@Composable
private fun JsonEditor(text: String, onChange: (String) -> Unit, error: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            text, onChange,
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            isError = error != null,
        )
        Text(
            error ?: "Plain strings are collaborative text. Write {\"\$mindoo\": \"atomic\", \"value\": \"…\"} for IDs and status values.",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun parseObject(text: String): Pair<JsonObject?, String?> = try {
    Json.parseToJsonElement(text).jsonObject to null
} catch (t: Throwable) {
    null to "Not a JSON object: ${t.message?.lineSequence()?.firstOrNull()}"
}

@Composable
private fun NewDocumentEditor(state: AppState, db: Database, onCreated: (String) -> Unit) {
    var text by remember { mutableStateOf("{\n  \"type\": \"note\",\n  \"title\": \"\"\n}") }
    val (parsed, error) = parseObject(text)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("New document", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        JsonEditor(text, { text = it }, error, Modifier.weight(1f))
        Button(onClick = {
            state.run("Creating…") { onCreated(db.documents.create(parsed!!).id) }
        }, enabled = parsed != null) {
            Icon(Icons.Outlined.Save, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
                    Text("Create")
        }
    }
}

@Composable
private fun DocumentDetail(state: AppState, db: Database, docId: String, listVersion: Int, onDeleted: () -> Unit) {
    // The version the editor started from; saving diffs the editor against it.
    var base by remember(docId) { mutableStateOf<MindooDocument?>(null) }
    var text by remember(docId) { mutableStateOf("") }
    // A newer version that arrived while the editor had unsaved edits.
    var incoming by remember(docId) { mutableStateOf<MindooDocument?>(null) }
    var reloadToken by remember(docId) { mutableIntStateOf(0) }
    var forceReload by remember(docId) { mutableStateOf(false) }
    var tab by remember(docId) { mutableIntStateOf(0) }

    // Follow the document (local saves, other windows, sync): a clean editor
    // shows the new version right away; one with unsaved edits keeps them and
    // says that the document moved on.
    LaunchedEffect(docId, listVersion, reloadToken) {
        try {
            val latest = db.documents.get(docId, withFieldTypes = true)
            val shown = base
            val force = forceReload
            forceReload = false
            when {
                !force && shown != null && latest.heads.toSet() == shown.heads.toSet() -> Unit
                shown == null || force || text == editableJson(shown.data) -> {
                    base = latest
                    text = editableJson(latest.data)
                    incoming = null
                }
                latest.heads.toSet() != shown.heads.toSet() -> incoming = latest
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            state.error = t.message
        }
    }
    val current = base ?: return
    val dirty = text != editableJson(current.data)
    fun reload() {
        forceReload = true
        reloadToken++
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(docId, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Text("heads ${current.heads.joinToString { it.take(8) }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (dirty) Chip("edited")
            IconButton(onClick = {
                state.run("Deleting…") {
                    db.documents.delete(docId)
                    onDeleted()
                }
            }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
        }
        SecondaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Data") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Attachments (${current.attachments.size})") })
            Tab(tab == 2, { tab = 2 }, text = { Text("History") })
        }
        when (tab) {
            0 -> {
                val (parsed, error) = parseObject(text)
                if (dirty && incoming != null) {
                    Text(
                        "Changed elsewhere while you edit (another window or a sync). Your edits are kept: " +
                            "Save merges them into the new version, Discard shows it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                JsonEditor(text, { text = it }, error, Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val edited = parsed!!
                        state.run("Saving…") {
                            // Only what the user changed, as patches against the version shown:
                            // concurrent edits, even in the same text, merge.
                            val result = db.documents.applyEdit(current, edited)
                            if (result?.conflictExpected == true) state.notice = "Merged with changes made in the meantime"
                            reload()
                        }
                    }, enabled = parsed != null && dirty) {
                        Icon(Icons.Outlined.Save, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save")
                    }
                    OutlinedButton(onClick = { reload() }, enabled = dirty) { Text("Discard") }
                }
            }
            1 -> Attachments(state, db, current) { reloadToken++ }
            2 -> History(state, db, docId, current.heads)
        }
    }
}

@Composable
private fun Attachments(state: AppState, db: Database, doc: MindooDocument, onChanged: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val dialog = FileDialog(null as Frame?, "Attach a file", FileDialog.LOAD).apply { isVisible = true }
            val file = dialog.file?.let { File(dialog.directory, it) } ?: return@OutlinedButton
            state.run("Uploading ${file.name}…") {
                val mime = java.nio.file.Files.probeContentType(file.toPath()) ?: "application/octet-stream"
                file.inputStream().use { input -> db.attachments.upload(doc.id, file.name, mime, input.asByteFlow()) }
                onChanged()
            }
        }) {
            Icon(Icons.Outlined.Upload, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
                    Text("Attach file")
        }
        if (doc.attachments.isEmpty()) {
            Text("No attachments. They are encrypted with the document's key and synced like documents.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        doc.attachments.forEach { a -> AttachmentRow(state, db, doc, a, onChanged) }
    }
}

@Composable
private fun AttachmentRow(state: AppState, db: Database, doc: MindooDocument, a: AttachmentInfo, onChanged: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.AttachFile, null)
        Column(Modifier.weight(1f)) {
            Text(a.fileName, fontWeight = FontWeight.Medium)
            Text("${a.mimeType} · ${formatSize(a.size)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = {
            val dialog = FileDialog(null as Frame?, "Save attachment", FileDialog.SAVE).apply { file = a.fileName; isVisible = true }
            val target = dialog.file?.let { File(dialog.directory, it) } ?: return@IconButton
            state.run("Downloading ${a.fileName}…") {
                target.outputStream().use { out -> db.attachments.readById(doc.id, a.attachmentId).collect { out.write(it) } }
                state.notice = "Saved ${target.name}"
            }
        }) { Icon(Icons.Outlined.Download, contentDescription = "Download") }
        IconButton(onClick = {
            state.run("Removing ${a.fileName}…") {
                db.attachments.removeById(doc.id, a.attachmentId)
                onChanged()
            }
        }) { Icon(Icons.Outlined.Delete, contentDescription = "Remove") }
    }
}

@Composable
private fun History(state: AppState, db: Database, docId: String, heads: List<String>) {
    var entries by remember(docId) { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var shown by remember(docId) { mutableStateOf<HistoryEntry?>(null) }
    LaunchedEffect(docId, heads) {
        try {
            entries = db.documents.history(docId).reversed()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            state.error = t.message
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyColumn(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries) { e ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (shown == e) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                        .clickable { shown = e }
                        .padding(10.dp),
                ) {
                    Text(timeFormat.format(Date(e.changeCreatedAt)), fontWeight = FontWeight.Medium)
                    Text(
                        (if (e.isDeleted) "deleted · " else "") + "by ${authorOf(e)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            shown?.let {
                Text(editableJson(it.data), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            } ?: Text("Select a revision to see the document as it was.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A short, stable label for the signing key that wrote a revision. */
private fun authorOf(e: HistoryEntry): String {
    val key = e.changeCreatedByPublicKey ?: return "unknown"
    val body = key.lines().filterNot { it.startsWith("-----") }.joinToString("")
    return "key …" + body.takeLast(10)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
