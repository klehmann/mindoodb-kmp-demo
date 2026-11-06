package com.mindoo.db.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mindoo.db.bridge.JoinRequestInfo
import com.mindoo.db.bridge.RefreshResult
import com.mindoo.db.demo.AppState
import com.mindoo.db.demo.OpenTenant
import com.mindoo.db.demo.demoServerPresets
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun ServerPanel(state: AppState, tenant: OpenTenant, sync: SyncStatus) {
    val profile = tenant.profile
    var url by remember { mutableStateOf(profile.serverUrl ?: demoServerPresets.first().url) }
    var adminPassword by remember { mutableStateOf("") }
    var lastRefresh by remember { mutableStateOf<RefreshResult?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 900.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TenantSection(state, tenant)
        if (profile.serverUrl == null && profile.isAdmin) {
            Section(
                "Publish to a server",
                Modifier.fillMaxWidth(),
                subtitle = "Registers the tenant on a MindooDB server and uploads the admin-signed directory. The server only ever stores ciphertext.",
            ) {
                ServerChoice(url) { url = it }
                Field("Admin password", adminPassword, { adminPassword = it }, password = true)
                Text(
                    "Servers that let any signed user register tenants accept the tenant admin; otherwise a server system admin has to register it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        state.run("Publishing to $url…") {
                            tenant.session.publishToServer(url, adminPassword = adminPassword)
                            state.updateProfile { it.copy(serverUrl = url.trimEnd('/')) }
                            lastRefresh = tenant.session.refresh()
                            state.notice = "Published to $url"
                        }
                    },
                    enabled = url.isNotBlank() && adminPassword.isNotEmpty(),
                ) { Text("Publish") }
            }
        } else if (profile.serverUrl == null) {
            Section("Connect to a server", Modifier.fillMaxWidth()) {
                ServerChoice(url) { url = it }
                Button(onClick = {
                    state.run("Connecting…") {
                        tenant.session.setServerUrl(url)
                        state.updateProfile { it.copy(serverUrl = url.trimEnd('/')) }
                    }
                }) { Text("Use this server") }
            }
        }
        Section(
            "Sync",
            Modifier.fillMaxWidth(),
            subtitle = if (profile.serverUrl != null) {
                if (profile.backgroundSync) {
                    "With ${profile.serverUrl}. Runs in the background every 5 seconds: directory, userdirectory and every open database, documents and attachments. Turn it off in the title bar to test concurrent edits."
                } else {
                    "With ${profile.serverUrl}. Background sync is off (title bar): changes stay on this machine until you sync now."
                }
            } else "Publish the tenant or connect to a server first.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { state.run("Syncing…") { lastRefresh = tenant.session.refresh(); state.refreshTenantLabel() } },
                    enabled = profile.serverUrl != null,
                ) { Text("Sync now") }
            }
            lastRefresh?.let { r ->
                Text("Last manual sync", style = MaterialTheme.typography.labelMedium)
                r.databases.forEach { d ->
                    KeyValue(d.dbId, "pulled ${d.pulledEntries} · pushed ${d.pushedEntries} (${d.pushedBytes / 1024} KB)" + if (d.rejectedEntries > 0) " · ${d.rejectedEntries} rejected" else "")
                }
            }
            if (sync.latest.isNotEmpty()) {
                Text("Activity", style = MaterialTheme.typography.labelMedium)
                sync.latest.values.sortedBy { "${it.dbId}/${it.direction}/${it.store}" }.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${e.dbId} · ${e.direction} · ${e.store}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(min = 260.dp))
                        val total = e.totalSourceEntries
                        if (e.phase == "complete") {
                            Chip("${e.transferredEntries} entries", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        } else if (total != null && total > 0) {
                            LinearProgressIndicator(progress = { (e.scannedEntries.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
                        } else {
                            LinearProgressIndicator(Modifier.weight(1f))
                        }
                        Text(e.phase, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Tenant name (editable by the admin, like in Haven) and the generated ID. */
@Composable
private fun TenantSection(state: AppState, tenant: OpenTenant) {
    val profile = tenant.profile
    var label by remember(profile.tenantLabel) { mutableStateOf(profile.tenantLabel ?: "") }
    var adminPassword by remember { mutableStateOf("") }
    Section(
        "Tenant",
        Modifier.fillMaxWidth(),
        subtitle = if (profile.isAdmin) {
            "The name is stored encrypted in the admin-signed directory; members see a new name after their next sync."
        } else "Only the tenant admin can rename the tenant.",
    ) {
        CopyBox("Tenant ID (generated)", profile.tenantId)
        if (profile.isAdmin) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Field("Tenant name", label, { label = it }, Modifier.weight(1f))
                Field("Admin password", adminPassword, { adminPassword = it }, Modifier.weight(1f), password = true)
            }
            Button(
                onClick = {
                    state.run("Renaming…") {
                        tenant.session.setTenantLabel(label.trim(), adminPassword)
                        state.updateProfile { it.copy(tenantLabel = label.trim()) }
                        adminPassword = ""
                        state.notice = "Renamed to ${label.trim()}"
                    }
                },
                enabled = label.isNotBlank() && label.trim() != profile.tenantLabel && adminPassword.isNotEmpty(),
            ) { Text("Rename") }
        } else {
            KeyValue("Tenant name", profile.tenantLabel ?: "–")
        }
    }
}

/** One-click presets (the demo server Haven offers) plus a free URL field. */
@Composable
private fun ServerChoice(url: String, onUrl: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        demoServerPresets.forEach { preset ->
            val selected = url.trimEnd('/') == preset.url
            OutlinedCard(
                onClick = { onUrl(preset.url) },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = selected, onClick = { onUrl(preset.url) })
                    Column(Modifier.weight(1f)) {
                        Text(preset.label, fontWeight = FontWeight.SemiBold)
                        Text(preset.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Chip("One click")
                }
            }
        }
        Field("Or your own server", url, { onUrl(it.trim()) }, placeholder = "https://sync.example.com")
    }
}

@Composable
fun AdminPanel(state: AppState, tenant: OpenTenant) {
    var request by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<JoinRequestInfo?>(null) }
    var label by remember { mutableStateOf("") }
    var adminPassword by remember { mutableStateOf("") }
    var response by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 900.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!tenant.profile.isAdmin) {
            Text(
                "This identity did not create the tenant, so it cannot approve join requests here. The tenant admin approves them, in this app or in Haven.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        Section("Approve a join request", Modifier.fillMaxWidth(), subtitle = "Paste the request a new user or device sent you.") {
            Field("Join request", request, { request = it; info = null; response = null }, singleLine = false, minLines = 3, monospace = true, placeholder = "mdb://join-request/…")
            Button(onClick = {
                state.run("Reading the request…") {
                    val i = tenant.session.inspectJoinRequest(JsonPrimitive(request.trim()))
                    info = i
                    label = i.label ?: ""
                }
            }, enabled = request.isNotBlank()) { Text("Check request") }
        }
        info?.let { i ->
            Section(
                "Who is asking",
                Modifier.fillMaxWidth(),
                subtitle = "Compare the fingerprints with the ones the joining device shows before you approve.",
            ) {
                KeyValue("User", i.username ?: "(no name: the request leaves it to you)")
                KeyValue("Device label", i.label ?: "–")
                KeyValue("Signing key", i.signingPublicKeyFingerprint, mono = true)
                KeyValue("Encryption key", i.encryptionPublicKeyFingerprint, mono = true)
                KeyValue("User-Key", if (i.hasUserKey) "included" else "missing: MindooDB cannot approve this request")
                KeyValue("Requested keys", i.requestedDocKeyIds?.joinToString() ?: "default set")
                val match = i.directoryUser
                KeyValue(
                    "Directory",
                    when {
                        match == null -> "–"
                        match.deviceAlreadyGranted -> "this device already has access"
                        match.exists -> "existing person, adds device #${match.devices.size + 1}"
                        else -> "new person"
                    },
                )
                Field("Device label", label, { label = it })
                Field("Admin password", adminPassword, { adminPassword = it }, password = true)
                Button(
                    onClick = {
                        state.run("Approving…") {
                            val approved = tenant.session.approveJoinRequest(
                                JsonPrimitive(request.trim()),
                                adminPassword = adminPassword,
                                label = label.ifBlank { null },
                            )
                            response = approved.joinResponse.jsonPrimitive.content
                        }
                    },
                    enabled = adminPassword.isNotEmpty() && i.hasUserKey && !(i.directoryUser?.deviceAlreadyGranted ?: false),
                ) { Text("Approve") }
            }
        }
        response?.let { r ->
            Section("Join response", Modifier.fillMaxWidth(), subtitle = "Send this back to the new user. The grant is already on the server.") {
                CopyBox("Join response", r)
                OutlinedButton(onClick = { request = ""; info = null; response = null; adminPassword = "" }) { Text("Next request") }
            }
        }
    }
}
