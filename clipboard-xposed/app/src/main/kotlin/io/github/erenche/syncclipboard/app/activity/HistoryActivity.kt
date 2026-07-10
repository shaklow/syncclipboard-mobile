package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.*

data class HistoryDisplayItem(
    val id: String,
    val text: String,
    val type: String,
    val timestamp: Long,
    val starred: Boolean
)

class HistoryActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HistoryScreen(items = remember { sampleHistoryItems() })
        }
    }
}

/**
 * 剪贴板历史页面 — MIUI X 风格
 */
@Composable
fun HistoryScreen(
    items: List<HistoryDisplayItem>,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    AppToolBarListContainer(
        title = stringResource(R.string.activity_history),
        canBack = true,
        onBack = { activity?.finish() }
    ) {
        if (items.isEmpty()) {
            item("empty") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 32.dp)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.history_empty),
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else {
            items.forEach { historyItem ->
                item("history_${historyItem.id}") {
                    HistoryItemCard(item = historyItem, context = context)
                }
            }
        }
    }
}

@Composable
fun HistoryItemCard(item: HistoryDisplayItem, context: Context) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = item.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${item.type} · ${dateFormat.format(Date(item.timestamp))}",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.action_copy),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = ClipData.newPlainText("SyncClipboard", item.text)
                            cm?.setPrimaryClip(clip)
                            Toast.makeText(context, R.string.history_copied, Toast.LENGTH_SHORT).show()
                        }
                )
            }
        }
    }
}

fun sampleHistoryItems(): List<HistoryDisplayItem> = listOf(
    HistoryDisplayItem("1", "Hello, this is a test clipboard item", "Text", System.currentTimeMillis() - 60000, false),
    HistoryDisplayItem("2", "https://github.com/erenche/syncclipboard", "Text", System.currentTimeMillis() - 300000, true),
    HistoryDisplayItem("3", "Lorem ipsum dolor sit amet, consectetur adipiscing elit.", "Text", System.currentTimeMillis() - 3600000, false)
)
