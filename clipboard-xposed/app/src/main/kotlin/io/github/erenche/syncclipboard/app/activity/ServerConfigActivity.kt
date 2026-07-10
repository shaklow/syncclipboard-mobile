package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge

import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.model.ServerConfig
import io.github.erenche.syncclipboard.common.model.ServerType
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ServerConfigActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ServerConfigScreen() }
    }
}

/**
 * 服务器管理界面 — MIUI X 风格
 */
@Composable
fun ServerConfigScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var appConfig by remember { mutableStateOf(Prefs.loadConfig(context)) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Push current config to xposed process on screen load
    LaunchedEffect(Unit) {
        try {
            val configJson = Json.encodeToString(AppConfig.serializer(), appConfig)
            SyncClipboardBridge.with(context)
                                .key(BridgeKeys.PUSH_CONFIG)
                .payload(android.os.Bundle().apply { putString("config", configJson) })
                .send()
        } catch (_: Exception) {}
    }

    fun refreshConfig() {
        appConfig = Prefs.loadConfig(context)
    }

    fun saveConfig(config: AppConfig) {
        Prefs.saveConfig(context, config)
        appConfig = config
        // Push config to xposed process so SyncEngine can use it
        scope.launch {
            try {
                val configJson = Json.encodeToString(AppConfig.serializer(), config)
                SyncClipboardBridge.with(context)
                                        .key(BridgeKeys.PUSH_CONFIG)
                    .payload(android.os.Bundle().apply { putString("config", configJson) })
                    .send()
            } catch (_: Exception) {}
        }
    }

    AppToolBarListContainer(
        title = stringResource(R.string.activity_server_config),
        canBack = true,
        onBack = { activity?.finish() },
        actions = {
            IconButton(onClick = {
                editingServer = null
                editingIndex = -1
                showEditDialog = true
            }) {
                Icon(
                    modifier = Modifier.size(26.dp),
                    imageVector = MiuixIcons.Add,
                    contentDescription = stringResource(R.string.server_add)
                )
            }
        }
    ) {
        val servers = appConfig.servers
        val activeIndex = appConfig.activeServerIndex

        if (servers.isEmpty()) {
            item("empty") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 32.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.no_server_configured),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.no_server_hint),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        } else {
            item("server_list") {
                Card(
                    modifier = Modifier
                        .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                        .fillMaxWidth()
                ) {
                    servers.forEachIndexed { index, server ->
                        val isActive = index == activeIndex
                        Column {
                            ArrowPreference(
                                title = server.name ?: server.url,
                                summary = buildServerSummary(server, isActive, context),
                                onClick = {
                                    editingServer = server
                                    editingIndex = index
                                    showEditDialog = true
                                }
                            )
                            if (index < servers.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MiuixTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 对话框必须放在 Scaffold 内部才能渲染 OverlayDialog ──
        item("dialogs") {
            ServerEditDialog(
                show = showEditDialog,
                server = editingServer,
                serverIndex = editingIndex,
                existingServers = appConfig.servers,
                activeServerIndex = appConfig.activeServerIndex,
                onSave = { newServer ->
                    val servers = appConfig.servers.toMutableList()
                    if (editingIndex >= 0) {
                        servers[editingIndex] = newServer
                    } else {
                        servers.add(newServer)
                    }
                    var newConfig = appConfig.copy(servers = servers)
                    if (appConfig.activeServerIndex < 0) {
                        newConfig = newConfig.copy(activeServerIndex = 0)
                    }
                    saveConfig(newConfig)
                    showEditDialog = false
                    Toast.makeText(
                        context,
                        if (editingIndex >= 0) context.getString(R.string.server_test_success)
                            .replace("successful", "updated")
                        else context.getString(R.string.server_test_success)
                            .replace("successful", "added"),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onDelete = if (editingIndex >= 0) {{
                    showDeleteConfirm = true
                }} else null,
                onSetActive = if (editingIndex >= 0 && editingIndex != appConfig.activeServerIndex) {{
                    saveConfig(appConfig.copy(activeServerIndex = editingIndex))
                    showEditDialog = false
                }} else null,
                onDismiss = { showEditDialog = false }
            )

            // ── 删除确认对话框 ─────────────────────────────────────────
            if (showDeleteConfirm && editingIndex >= 0) {
                OverlayDialog(
                    show = true,
                    title = stringResource(R.string.server_delete),
                    summary = stringResource(
                        R.string.server_delete_confirm,
                        editingServer?.name ?: editingServer?.url ?: ""
                    ),
                    onDismissRequest = { showDeleteConfirm = false }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { showDeleteConfirm = false },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = stringResource(R.string.action_delete),
                            onClick = {
                                val servers = appConfig.servers.toMutableList()
                                servers.removeAt(editingIndex)
                                var newConfig = appConfig.copy(servers = servers)
                                if (editingIndex == appConfig.activeServerIndex) {
                                    newConfig = newConfig.copy(
                                        activeServerIndex = if (servers.isEmpty()) -1 else 0
                                    )
                                } else if (editingIndex < appConfig.activeServerIndex) {
                                    newConfig = newConfig.copy(activeServerIndex = appConfig.activeServerIndex - 1)
                                }
                                saveConfig(newConfig)
                                showEditDialog = false
                                showDeleteConfirm = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 构建服务器摘要文本
 */
private fun buildServerSummary(server: ServerConfig, isActive: Boolean, context: android.content.Context): String {
    val typeLabel = when (server.type) {
        ServerType.syncclipboard -> context.getString(R.string.server_type_syncclipboard)
        ServerType.webdav -> context.getString(R.string.server_type_webdav)
        ServerType.s3 -> context.getString(R.string.server_type_s3)
    }
    return buildString {
        append(typeLabel)
        if (isActive) {
            append(" · ")
            append(context.getString(R.string.server_active))
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 服务器编辑/添加对话框 — MIUI X 风格
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditDialog(
    show: Boolean,
    server: ServerConfig?,
    serverIndex: Int,
    existingServers: List<ServerConfig>,
    activeServerIndex: Int,
    onSave: (ServerConfig) -> Unit,
    onDelete: (() -> Unit)?,
    onSetActive: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEditing = server != null

    if (!show) return

    // Form state — resets when dialog is removed from composition and recomposed
    var serverType by remember { mutableStateOf(server?.type ?: ServerType.syncclipboard) }
    var name by remember { mutableStateOf(server?.name ?: "") }
    var url by remember { mutableStateOf(server?.url ?: "") }
    var username by remember { mutableStateOf(server?.username ?: "") }
    var password by remember { mutableStateOf(server?.password ?: "") }
    var region by remember { mutableStateOf(server?.region ?: "") }
    var bucketName by remember { mutableStateOf(server?.bucketName ?: "") }
    var objectPrefix by remember { mutableStateOf(server?.objectPrefix ?: "") }
    var forcePathStyle by remember { mutableStateOf(server?.forcePathStyle ?: false) }
    var showPassword by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }

    OverlayDialog(
        show = true,
        title = stringResource(if (isEditing) R.string.server_edit else R.string.server_add),
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            // ── 服务器类型选择 ─────────────────────────────
            Text(
                text = stringResource(R.string.server_type_label),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            ServerType.entries.forEach { type ->
                val isSelected = serverType == type
                val selectColor = if (isSelected) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { serverType = type }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // MIUIX-style selection indicator
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MiuixTheme.colorScheme.primary
                                else Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(MiuixTheme.colorScheme.outline)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = when (type) {
                                ServerType.syncclipboard -> stringResource(R.string.server_type_syncclipboard)
                                ServerType.webdav -> stringResource(R.string.server_type_webdav)
                                ServerType.s3 -> stringResource(R.string.server_type_s3)
                            },
                            fontSize = 15.sp,
                            color = selectColor
                        )
                        Text(
                            text = when (type) {
                                ServerType.syncclipboard -> stringResource(R.string.server_type_syncclipboard_desc)
                                ServerType.webdav -> stringResource(R.string.server_type_webdav_desc)
                                ServerType.s3 -> stringResource(R.string.server_type_s3_desc)
                            },
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MiuixTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(12.dp))

            // ── 连接信息字段 ───────────────────────────────
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.server_name)) },
                placeholder = { Text(stringResource(R.string.server_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = miuixTextFieldColors()
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.server_url)) },
                placeholder = { Text(stringResource(R.string.server_url_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = miuixTextFieldColors()
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = {
                    Text(
                        when (serverType) {
                            ServerType.s3 -> "Access Key ID"
                            else -> stringResource(R.string.server_username)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = miuixTextFieldColors()
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = {
                    Text(
                        when (serverType) {
                            ServerType.s3 -> "Secret Access Key"
                            else -> stringResource(R.string.server_password)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = miuixTextFieldColors(),
                trailingIcon = {
                    TextButton(
                        onClick = { showPassword = !showPassword },
                        text = if (showPassword) "Hide" else "Show"
                    )
                }
            )

            // ── S3 专用字段 ─────────────────────────────────
            if (serverType == ServerType.s3) {
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it },
                    label = { Text(stringResource(R.string.server_region)) },
                    placeholder = { Text(stringResource(R.string.server_region_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = miuixTextFieldColors()
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = bucketName,
                    onValueChange = { bucketName = it },
                    label = { Text(stringResource(R.string.server_bucket)) },
                    placeholder = { Text(stringResource(R.string.server_bucket_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = miuixTextFieldColors()
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = objectPrefix,
                    onValueChange = { objectPrefix = it },
                    label = { Text(stringResource(R.string.server_prefix)) },
                    placeholder = { Text(stringResource(R.string.server_prefix_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = miuixTextFieldColors()
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { forcePathStyle = !forcePathStyle }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = forcePathStyle,
                        onCheckedChange = { forcePathStyle = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.server_path_style),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── 按钮行 ─────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 设为当前服务器 (仅编辑时)
                if (onSetActive != null) {
                    TextButton(
                        text = stringResource(R.string.server_set_active),
                        onClick = onSetActive,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 测试连接 — 通过 bridge 实际测试
                TextButton(
                    text = if (isTesting) stringResource(R.string.server_testing)
                    else stringResource(R.string.action_test_connection),
                    onClick = {
                        if (isTesting) return@TextButton
                        // 先验证必填字段
                        val validateError = when (serverType) {
                            ServerType.s3 -> {
                                when {
                                    url.isBlank() -> null  // S3 URL is optional
                                    username.isBlank() -> context.getString(R.string.server_access_key_required)
                                    password.isBlank() -> context.getString(R.string.server_secret_key_required)
                                    bucketName.isBlank() -> context.getString(R.string.server_bucket_required)
                                    else -> null
                                }
                            }
                            else -> {
                                when {
                                    url.isBlank() -> context.getString(R.string.server_url_required)
                                    username.isBlank() -> context.getString(R.string.server_username_required)
                                    password.isBlank() -> context.getString(R.string.server_password_required)
                                    else -> null
                                }
                            }
                        }
                        if (validateError != null) {
                            Toast.makeText(context, validateError, Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }

                        isTesting = true
                        scope.launch {
                            try {
                                val testConfig = ServerConfig(
                                    type = serverType,
                                    name = name.ifBlank { null },
                                    url = url,
                                    username = username,
                                    password = password,
                                    region = if (serverType == ServerType.s3 && region.isNotBlank()) region else null,
                                    bucketName = if (serverType == ServerType.s3) bucketName else null,
                                    objectPrefix = if (serverType == ServerType.s3 && objectPrefix.isNotBlank()) objectPrefix else null,
                                    forcePathStyle = serverType == ServerType.s3 && forcePathStyle
                                )
                                val success = performTestConnection(testConfig)
                                Toast.makeText(
                                    context,
                                    if (success) context.getString(R.string.server_test_success)
                                    else context.getString(R.string.server_test_fail),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.server_test_fail) + ": ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isTesting
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 删除按钮 (仅编辑时)
                if (onDelete != null) {
                    TextButton(
                        text = stringResource(R.string.action_delete),
                        onClick = onDelete,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 取消
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )

                // 保存
                TextButton(
                    text = stringResource(R.string.action_save),
                    onClick = {
                        // 验证
                        when (serverType) {
                            ServerType.s3 -> {
                                if (bucketName.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_bucket_required), Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                if (username.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_access_key_required), Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                if (password.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_secret_key_required), Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                            }
                            else -> {
                                if (url.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_url_required), Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                if (username.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_username_required), Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                if (password.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.server_password_required), Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                            }
                        }

                        onSave(
                            ServerConfig(
                                type = serverType,
                                name = name.ifBlank { null },
                                url = url,
                                username = username,
                                password = password,
                                region = if (serverType == ServerType.s3 && region.isNotBlank()) region else null,
                                bucketName = if (serverType == ServerType.s3) bucketName else null,
                                objectPrefix = if (serverType == ServerType.s3 && objectPrefix.isNotBlank()) objectPrefix else null,
                                forcePathStyle = serverType == ServerType.s3 && forcePathStyle
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 直接 HTTP 测试服务器连接 — 绕过 Bridge IPC 避免跨进程广播被系统屏蔽。
 */
private suspend fun performTestConnection(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
    try {
        val urlStr = buildTestUrl(config)
        val url = java.net.URL(urlStr)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true

        if (!config.username.isNullOrBlank() && !config.password.isNullOrBlank()) {
            val credentials = "${config.username}:${config.password}"
            val encoded = android.util.Base64.encodeToString(
                credentials.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
            conn.setRequestProperty("Authorization", "Basic $encoded")
        }

        // 任意 HTTP 响应（包括 401/404）表示服务器可达
        conn.responseCode > 0
    } catch (e: Exception) {
        false
    }
}

/** 根据服务器类型构建测试 URL */
private fun buildTestUrl(config: ServerConfig): String {
    return when (config.type) {
        ServerType.syncclipboard -> "${config.url.trimEnd('/')}/clipboard"
        ServerType.webdav -> config.url.trimEnd('/')
        ServerType.s3 -> {
            config.url.ifBlank {
                "https://s3.${config.region ?: "us-east-1"}.amazonaws.com"
            }
        }
    }
}

/**
 * MIUIX 风格的 OutlinedTextField 颜色配置
 */
@Composable
private fun miuixTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MiuixTheme.colorScheme.onSurface,
    unfocusedTextColor = MiuixTheme.colorScheme.onSurface,
    cursorColor = MiuixTheme.colorScheme.primary,
    focusedBorderColor = MiuixTheme.colorScheme.primary,
    unfocusedBorderColor = MiuixTheme.colorScheme.outline,
    focusedLabelColor = MiuixTheme.colorScheme.primary,
    unfocusedLabelColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    focusedPlaceholderColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f),
    unfocusedPlaceholderColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
)
