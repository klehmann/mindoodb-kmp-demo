package com.mindoo.db.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mindoo.db.demo.AppState
import com.mindoo.db.demo.PendingJoin
import com.mindoo.db.demo.Profile
import com.mindoo.db.demo.signingFingerprintOf
import kotlinx.serialization.json.jsonPrimitive

/** First screen: open a saved identity, create a tenant, or join one. */
@Composable
fun StartScreen(state: AppState) {
    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("MindooDB", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "A Kotlin desktop client for end-to-end encrypted, local-first MindooDB tenants. " +
                    "Everything is encrypted on this machine before it reaches a server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SavedProfiles(state, Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // A join waiting for the admin's answer is what the user comes back for.
            var tab by remember { mutableIntStateOf(if (state.profiles.pendingJoins.isNotEmpty()) 1 else 0) }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Create a tenant") })
                    Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Join a tenant") })
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (tab == 0) CreateTenantForm(state) else JoinTenantForm(state)
                }
            }
        }
    }
}

@Composable
private fun SavedProfiles(state: AppState, modifier: Modifier) {
    Section("Open", modifier.fillMaxWidth(), subtitle = "Identities stored on this machine") {
        val profiles = state.profiles.profiles
        if (profiles.isEmpty()) {
            Text("None yet. Create a tenant or join one.", style = MaterialTheme.typography.bodySmall)
        }
        var selected by remember { mutableStateOf<Profile?>(null) }
        var password by remember { mutableStateOf("") }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            profiles.forEach { profile ->
                val isSelected = selected == profile
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (profile.isAdmin) Icons.Outlined.AdminPanelSettings else Icons.Outlined.Person, null)
                    Column(Modifier.weight(1f)) {
                        Text(profile.tenantId, fontWeight = FontWeight.SemiBold)
                        Text(profile.userId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { selected = profile; password = "" }) { Text(if (isSelected) "Selected" else "Select") }
                }
                if (isSelected) {
                    Field("Password", password, { password = it }, password = true)
                    Button(
                        onClick = { state.openProfile(profile, password) },
                        enabled = password.isNotEmpty() && state.sidecar != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open ${profile.tenantId}") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CreateTenantForm(state: AppState) {
    var tenantId by remember { mutableStateOf("") }
    var adminName by remember { mutableStateOf("cn=admin/o=") }
    var adminPassword by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("cn=") }
    var password by remember { mutableStateOf("") }
    var databases by remember { mutableStateOf("notes") }
    Section(
        "Create a tenant",
        Modifier.fillMaxWidth(),
        subtitle = "Generates the admin and user keys locally. Nothing leaves this machine until you publish.",
    ) {
        Field("Tenant ID", tenantId, { tenantId = it.lowercase() }, placeholder = "acme (lowercase letters, digits, - and _)")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Admin name", adminName, { adminName = it }, Modifier.weight(1f))
            Field("Admin password", adminPassword, { adminPassword = it }, Modifier.weight(1f), password = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Your user name", userId, { userId = it }, Modifier.weight(1f))
            Field("Your password", password, { password = it }, Modifier.weight(1f), password = true)
        }
        Field("Databases", databases, { databases = it }, placeholder = "comma-separated, e.g. notes, tasks")
        Text(
            "The admin key signs the tenant directory (who belongs to it). Keep both passwords; they cannot be recovered.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { state.createTenant(tenantId.trim(), adminName.trim(), adminPassword, userId.trim(), password, splitList(databases)) },
            enabled = state.sidecar != null && tenantId.isNotBlank() && adminPassword.isNotEmpty() && password.isNotEmpty(),
        ) {
            Icon(Icons.Outlined.Domain, null)
            Spacer(Modifier.width(8.dp))
                    Text("Create tenant")
        }
    }
}

@Composable
private fun JoinTenantForm(state: AppState) {
    val pending = state.profiles.pendingJoins
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        pending.forEach { PendingJoinCard(state, it) }
        var userId by remember { mutableStateOf("cn=") }
        var password by remember { mutableStateOf("") }
        Section(
            "1. Request access",
            Modifier.fillMaxWidth(),
            subtitle = "Creates your identity on this machine and a join request for the tenant admin. Your private keys never leave this device.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Your user name", userId, { userId = it }, Modifier.weight(1f))
                Field("Your password", password, { password = it }, Modifier.weight(1f), password = true)
            }
            Button(onClick = { state.startJoin(userId.trim(), password) }, enabled = state.sidecar != null && password.isNotEmpty()) {
                Text("Create join request")
            }
        }
    }
}

@Composable
private fun PendingJoinCard(state: AppState, pending: PendingJoin) {
    var response by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var databases by remember { mutableStateOf("notes") }
    Section(
        "2. Waiting for the admin: ${pending.userId}",
        Modifier.fillMaxWidth(),
        subtitle = "Send this request to the tenant admin (Haven or this app can approve it). Paste the response below.",
    ) {
        CopyBox("Join request", pending.joinRequest.jsonPrimitive.content)
        signingFingerprintOf(pending.identity)?.let { fp ->
            KeyValue("Your signing key", fp, mono = true)
            Text(
                "The admin sees the same fingerprint before approving. Compare it, for example on the phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Field("Join response", response, { response = it }, singleLine = false, minLines = 3, monospace = true, placeholder = "mdb://join-response/…")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Your password", password, { password = it }, Modifier.weight(1f), password = true)
            Field("Databases to open", databases, { databases = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { state.completeJoin(pending, response, password, splitList(databases)) },
                enabled = response.isNotBlank() && password.isNotEmpty(),
            ) { Text("Join tenant") }
            OutlinedButton(onClick = { state.discardPendingJoin(pending) }) { Text("Discard") }
        }
    }
}

fun splitList(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
