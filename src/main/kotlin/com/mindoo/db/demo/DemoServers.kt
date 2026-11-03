package com.mindoo.db.demo

/** A server offered with one click, like Haven's `demoServerPresets`. */
data class ServerPreset(val label: String, val url: String, val description: String)

/** Keep in sync with Haven's `src/lib/mindoodb/demoServers.ts`. */
val demoServerPresets = listOf(
    ServerPreset(
        label = "MindooDB Demo Server 1",
        url = "https://demo-server1.mindoodb.com",
        description = "Demo environment for testing collaborative work. Data is periodically deleted.",
    ),
)
