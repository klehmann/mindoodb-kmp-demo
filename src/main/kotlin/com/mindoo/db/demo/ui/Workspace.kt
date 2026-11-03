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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mindoo.db.bridge.DatabaseMode
import com.mindoo.db.bridge.SyncProgressEvent
import com.mindoo.db.demo.AppState
import com.mindoo.db.demo.OpenTenant

/** What the main area shows. */
sealed interface Page {
    data class Database(val id: String) : Page
    data object Server : Page
    data object Admin : Page
}

/** Live sync status of the open session, fed by `syncProgress()`. */
class SyncStatus {
    /** Latest event per "db/direction/store". */
    val latest = mutableStateMapOf<String, SyncProgressEvent>()
    var lastCompleted by mutableStateOf<Long?>(null)

    fun onEvent(event: SyncProgressEvent) {
        latest["${event.dbId}/${event.direction}/${event.store}"] = event
        if (event.phase == "complete") lastCompleted = System.currentTimeMillis()
    }

    val running: List<SyncProgressEvent> get() = latest.values.filter { it.phase != "complete" }
}

@Composable
fun Workspace(state: AppState, tenant: OpenTenant) {
    var page by remember { mutableStateOf<Page>(Page.Database(tenant.profile.databases.firstOrNull() ?: "notes")) }
    val sync = remember(tenant.session) { SyncStatus() }
    LaunchedEffect(tenant.session) {
        tenant.session.syncProgress().collect { sync.onEvent(it) }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(state, tenant, sync)
        HorizontalDivider()
        Row(Modifier.fillMaxSize()) {
            Sidebar(state, tenant, page) { page = it }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (val p = page) {
                    is Page.Database -> DocumentBrowser(state, tenant, p.id)
                    Page.Server -> ServerPanel(state, tenant, sync)
                    Page.Admin -> AdminPanel(state, tenant)
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: AppState, tenant: OpenTenant, sync: SyncStatus) {
    val running = sync.running
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("MindooDB", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Chip(tenant.profile.tenantId)
            Text(tenant.profile.userId, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            val server = tenant.profile.serverUrl
            Icon(if (server != null) Icons.Outlined.Cloud else Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when {
                    server == null -> "Local only"
                    running.isNotEmpty() -> "Syncing ${running.first().dbId}…"
                    else -> "Synced with $server"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (server != null) {
                TextButton(onClick = { state.run("Syncing…") { tenant.session.refresh() } }) {
                    Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sync now")
                }
            }
            IconButton(onClick = { state.closeTenant() }) { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Close tenant") }
        }
        if (running.isNotEmpty()) {
            val e = running.first()
            val total = e.totalSourceEntries
            if (total != null && total > 0) {
                LinearProgressIndicator(progress = { (e.scannedEntries.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            }
        } else {
            Spacer(Modifier.height(3.dp))
        }
    }
}

@Composable
private fun Sidebar(state: AppState, tenant: OpenTenant, page: Page, onPage: (Page) -> Unit) {
    Column(
        Modifier.width(230.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("DATABASES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        tenant.profile.databases.forEach { db ->
            NavItem(Icons.Outlined.Storage, db, page == Page.Database(db)) { onPage(Page.Database(db)) }
        }
        var newDb by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("Open database", newDb, { newDb = it.lowercase() }, Modifier.weight(1f))
        }
        TextButton(
            onClick = {
                val id = newDb.trim()
                state.run("Opening $id…") {
                    tenant.session.openDatabase(id, DatabaseMode.Local)
                    state.updateProfile { it.copy(databases = (it.databases + id).distinct()) }
                    onPage(Page.Database(id))
                }
                newDb = ""
            },
            enabled = newDb.isNotBlank(),
        ) { Text("Open") }
        Spacer(Modifier.height(12.dp))
        Text("TENANT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        NavItem(Icons.Outlined.Cloud, "Server & sync", page == Page.Server) { onPage(Page.Server) }
        NavItem(Icons.Outlined.AdminPanelSettings, "Join requests", page == Page.Admin) { onPage(Page.Admin) }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
