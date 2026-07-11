package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import android.provider.MediaStore
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.net.ServerApi
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.HistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HistoryScreen() }
    }

    companion object {
        // 图片预览缓存：itemId -> 已下载的本地文件
        val previewCache = mutableMapOf<String, File>()
    }
}

/**
 * 剪贴板历史页面 — MIUI X 风格
 * 通过 bridge 从 system_server 的 SyncEngine 加载真实数据
 */
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val bundle = SyncClipboardBridge.with(context)
                    .to("com.android.systemui")
                    .key(BridgeKeys.GET_HISTORY)
                    .await()
                val json = bundle.getString("items")
                if (!json.isNullOrBlank()) {
                    items = Json { ignoreUnknownKeys = true }
                        .decodeFromString(ListSerializer(HistoryItem.serializer()), json)
                }
            } catch (_: Exception) {
            } finally {
                loading = false
            }
        }
    }

    AppToolBarListContainer(
        title = stringResource(R.string.activity_history),
        canBack = true,
        onBack = { activity?.finish() },
        actions = {
            if (!loading && items.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.action_clear),
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clickable {
                            scope.launch {
                                SyncClipboardBridge.with(context)
                                    .to("com.android.systemui")
                                    .key(BridgeKeys.CLEAR_HISTORY)
                                    .send()
                                items = emptyList()
                                Toast.makeText(
                                    context,
                                    R.string.history_cleared,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                )
            }
        }
    ) {
        if (loading) {
            item("loading") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 32.dp)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Loading...",
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else if (items.isEmpty()) {
            item("empty") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 32.dp)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
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
fun HistoryItemCard(item: HistoryItem, context: Context) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val scope = rememberCoroutineScope()

    // 图片预览：仅在图片类型且有 dataName 时加载
    var previewFile by remember(item.id) {
        mutableStateOf(HistoryActivity.previewCache[item.id])
    }
    var previewLoading by remember(item.id) { mutableStateOf(false) }

    if (item.type == ClipboardContentType.Image && !item.dataName.isNullOrBlank() && previewFile == null) {
        LaunchedEffect(item.id, item.dataName) {
            previewLoading = true
            try {
                val config = Prefs.loadConfig(context)
                val server = config.servers.getOrNull(config.activeServerIndex)
                if (server != null) {
                    val api = ServerApi(server)
                    val destFile = File(context.cacheDir, "hist_${item.id}_${item.dataName}")
                    val downloaded = withContext(Dispatchers.IO) {
                        api.downloadFile(item.dataName!!, destFile)
                    }
                    if (downloaded != null) {
                        HistoryActivity.previewCache[item.id] = downloaded
                        previewFile = downloaded
                    }
                }
            } catch (_: Exception) {
            } finally {
                previewLoading = false
            }
        }
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 根据类型显示不同前缀
            val displayText = when (item.type) {
                ClipboardContentType.Image -> "[${stringResource(R.string.type_image)}] ${item.dataName ?: item.text}"
                ClipboardContentType.File -> "[${stringResource(R.string.type_file)}] ${item.dataName ?: item.text}"
                else -> item.text
            }
            Text(
                text = displayText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface
            )

            // 图片预览
            if (item.type == ClipboardContentType.Image) {
                Spacer(modifier = Modifier.height(8.dp))
                when {
                    previewLoading -> {
                        Text(
                            text = stringResource(R.string.main_loading),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                    previewFile != null -> {
                        val bitmap = remember(previewFile) {
                            BitmapFactory.decodeFile(previewFile!!.absolutePath)
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Preview",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val typeLabel = when (item.type) {
                    ClipboardContentType.Image -> stringResource(R.string.type_image)
                    ClipboardContentType.File -> stringResource(R.string.type_file)
                    ClipboardContentType.Text -> stringResource(R.string.type_text)
                    ClipboardContentType.Group -> stringResource(R.string.type_group)
                }
                Text(
                    text = "$typeLabel · ${dateFormat.format(Date(item.timestamp))}",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Row {
                    if (item.starred) {
                        Text(
                            text = "★",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    // 图片/文件类型显示下载按钮
                    if ((item.type == ClipboardContentType.Image || item.type == ClipboardContentType.File)
                        && !item.dataName.isNullOrBlank()
                    ) {
                        Text(
                            text = stringResource(R.string.action_download),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clickable {
                                    scope.launch {
                                        downloadHistoryFile(context, item)
                                    }
                                }
                        )
                    }
                    // 文本类型显示复制按钮
                    if (item.type == ClipboardContentType.Text) {
                        Text(
                            text = stringResource(R.string.action_copy),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clickable {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                    val clip = ClipData.newPlainText("SyncClipboard", item.text)
                                    cm?.setPrimaryClip(clip)
                                    Toast.makeText(context, R.string.history_copied, Toast.LENGTH_SHORT).show()
                                }
                        )
                    }
                    Text(
                        text = stringResource(R.string.action_delete),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .clickable {
                                scope.launch {
                                    val payload = android.os.Bundle().apply {
                                        putString("id", item.id)
                                    }
                                    SyncClipboardBridge.with(context)
                                        .to("com.android.systemui")
                                        .key(BridgeKeys.DELETE_HISTORY_ITEM)
                                        .payload(payload)
                                        .send()
                                }
                            }
                    )
                }
            }
        }
    }
}

/**
 * 下载历史项中的图片/文件到相册或下载目录
 */
private suspend fun downloadHistoryFile(context: Context, item: HistoryItem) {
    try {
        val config = Prefs.loadConfig(context)
        val server = config.servers.getOrNull(config.activeServerIndex)
        if (server == null) {
            Toast.makeText(context, "未配置服务器", Toast.LENGTH_SHORT).show()
            return
        }
        val api = ServerApi(server)
        val fileName = item.dataName ?: "file_${System.currentTimeMillis()}"
        val destFile = File(context.cacheDir, "dl_$fileName")
        val downloaded = withContext(Dispatchers.IO) {
            api.downloadFile(fileName, destFile)
        }
        if (downloaded == null) {
            Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
            return
        }
        val resolver = context.contentResolver
        if (item.type == ClipboardContentType.Image) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/*")
            }
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    downloaded.inputStream().use { input -> input.copyTo(out) }
                }
                Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
            }
        } else {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "*/*")
            }
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    downloaded.inputStream().use { input -> input.copyTo(out) }
                }
                Toast.makeText(context, "已保存到下载", Toast.LENGTH_SHORT).show()
            }
        }
        downloaded.delete()
    } catch (e: Exception) {
        Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
