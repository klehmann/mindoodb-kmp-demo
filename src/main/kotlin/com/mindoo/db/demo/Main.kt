package com.mindoo.db.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mindoo.db.demo.ui.DemoTheme
import com.mindoo.db.demo.ui.StartScreen
import com.mindoo.db.demo.ui.Workspace
import kotlinx.coroutines.delay
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Arguments: `--data <dir>` (default `./demo-data`), `--bridge <path to dist/index.js>`
 * (default `../mindoodb-jvm-bridge/dist/index.js`), `--title <window title>`.
 * Run two instances with different data directories to play admin and new user.
 */
fun main(args: Array<String>) {
    val opts = args.toList().windowed(2, 2, partialWindows = false).associate { (k, v) -> k.removePrefix("--") to v }
    val config = AppConfig(
        dataRoot = Paths.get(opts["data"] ?: "demo-data").toAbsolutePath(),
        bridgeScript = Paths.get(opts["bridge"] ?: "../mindoodb-jvm-bridge/dist/index.js").toAbsolutePath().normalize(),
    )
    val title = opts["title"] ?: "MindooDB Kotlin Demo"
    val state = AppState(config)
    state.startSidecar()
    application {
        Window(
            onCloseRequest = { state.shutdown(); exitApplication() },
            title = title,
            state = rememberWindowState(size = DpSize(1320.dp, 860.dp)),
        ) {
            DemoTheme(dark = opts["theme"] == "dark") { App(state, config.dataRoot) }
        }
    }
}

/** The whole app for one [AppState]; also rendered by the UI tests. */
@Composable
fun App(state: AppState, dataRoot: Path) {
    Box(Modifier.fillMaxSize()) {
        val open = state.open
        if (open == null) StartScreen(state) else Workspace(state, open)

        state.sidecarError?.let {
            AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("MindooDB engine not available") }, text = { Text(it) })
        }
        state.error?.let { message ->
            AlertDialog(
                onDismissRequest = { state.error = null },
                confirmButton = { TextButton(onClick = { state.error = null }) { Text("OK") } },
                title = { Text("Something went wrong") },
                text = { Text(message) },
            )
        }
        state.busy?.let { label ->
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(24.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(label)
                }
            }
        }
        state.notice?.let { message ->
            LaunchedEffect(message) {
                delay(3500)
                state.notice = null
            }
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(24.dp)) { Text(message) }
        }
    }
}
