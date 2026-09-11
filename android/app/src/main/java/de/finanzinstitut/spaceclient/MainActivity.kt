package de.finanzinstitut.spaceclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import de.finanzinstitut.spaceclient.launch.Runtimes
import de.finanzinstitut.spaceclient.setup.InstanceSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole app, for now.
 *
 * One screen on purpose. The desktop client earned its menus by having things
 * to put in them; this has exactly one question to answer - does Minecraft 26.2
 * run on this phone at all - and wrapping that in a five screen shell would be
 * decoration over an unanswered question.
 *
 * The colours and the shape of it match the desktop menu so that what gets
 * built later feels like the same client, not a different product with the
 * same name.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpaceClientApp() }
    }

    @Composable
    private fun SpaceClientApp() {
        val context = this
        var log by remember { mutableStateOf(listOf<String>()) }
        var busy by remember { mutableStateOf(false) }
        var instancePath by remember { mutableStateOf<String?>(null) }

        val runtimes = remember { Runtimes.installed(context) }

        fun note(line: String) {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
            log = log + "$stamp  $line"
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0B0820)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Space Client",
                    color = Color(0xFF38E0FF),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Minecraft ${InstanceSetup.MINECRAFT_VERSION} · Fabric",
                    color = Color(0xFF9A95C9),
                    fontSize = 14.sp
                )

                Spacer(Modifier.height(24.dp))

                RuntimeCard(runtimes)

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        log = emptyList()

                        lifecycleScope.launch {
                            try {
                                val dir = withContext(Dispatchers.IO) {
                                    InstanceSetup(context).prepare { message ->
                                        lifecycleScope.launch { note(message) }
                                    }
                                }
                                instancePath = dir.absolutePath
                                note("Instance ready")
                            } catch (e: Exception) {
                                // Said plainly. This app exists to find out what
                                // does not work, so a failure is a result rather
                                // than something to hide behind a spinner.
                                note("Failed: ${e.message ?: e.javaClass.simpleName}")
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7C5CFF),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Working" else "Prepare instance")
                }

                instancePath?.let { path ->
                    Spacer(Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF161235)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Instance folder", color = Color(0xFFE9E6FF), fontSize = 14.sp)
                            Text(path, color = Color(0xFF9A95C9), fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Point your runtime at this folder, then start it. "
                                    + "The control layout with the menu button is in here too.",
                                color = Color(0xFF7E79AC),
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (runtimes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        runtimes.forEach { runtime ->
                            OutlinedButton(
                                onClick = { Runtimes.open(context, runtime.packageName) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Open ${runtime.label}", color = Color(0xFF38E0FF))
                            }
                        }
                    }
                }

                if (log.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text("Log", color = Color(0xFFE9E6FF), fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    log.forEach { line ->
                        Text(line, color = Color(0xFF9A95C9), fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(28.dp))
                Text(
                    "This build prepares the instance and hands the start over to an "
                        + "installed runtime. Running Java on Android is not something "
                        + "this app does yet.",
                    color = Color(0xFF5E5988),
                    fontSize = 11.sp
                )
            }
        }
    }

    @Composable
    private fun RuntimeCard(installed: List<Runtimes.Runtime>) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161235)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Runtime", color = Color(0xFFE9E6FF), fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))

                if (installed.isEmpty()) {
                    Text(
                        "None found. Install one of these, then come back:",
                        color = Color(0xFFE8C46A),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Runtimes.known().distinctBy { it.label.substringBefore(" (") }
                        .forEach { runtime ->
                            Text("· ${runtime.label}", color = Color(0xFF9A95C9), fontSize = 12.sp)
                        }
                } else {
                    installed.forEach { runtime ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(Color(0xFF6ADF8F), RoundedCornerShape(4.dp))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(runtime.label, color = Color(0xFF9A95C9), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
