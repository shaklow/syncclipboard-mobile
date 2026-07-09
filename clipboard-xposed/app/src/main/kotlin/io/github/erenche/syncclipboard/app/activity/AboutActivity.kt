package io.github.erenche.syncclipboard.app.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("SyncClipboard", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Version 1.0.0-alpha1", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text("An LSPosed module for silent clipboard synchronization between devices.")
            Text("Supports SyncClipboard Server, WebDAV, and S3-compatible storage backends.")

            Spacer(modifier = Modifier.height(16.dp))
            Text("How it works", fontWeight = FontWeight.SemiBold)
            Text("This module hooks into Android's ClipboardService at the system level to detect clipboard changes in real-time without polling. Changes are automatically synced to your configured server.")

            Spacer(modifier = Modifier.height(16.dp))
            Text("License", fontWeight = FontWeight.SemiBold)
            Text("Apache License 2.0")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AboutScreenPreview() {
    MaterialTheme { AboutScreen() }
}
