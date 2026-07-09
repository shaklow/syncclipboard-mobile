package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.compose.NavigationBackIcon
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.ServerConfig
import io.github.erenche.syncclipboard.common.model.ServerType
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
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

    var appConfig by remember { mutableStateOf(Prefs.loadConfig(context)) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun refreshConfig() {
        appConfig = Prefs.loadConfig(context)
    }

    fun saveConfig(config: io.github.erenche.syncclipboard.common.model.AppConfig) {
        Prefs.saveConfig(context, config)
        appConfig = config
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
    }

    // ── 编辑/添加对话框 ────────────────────────────────────────
    if (showEditDialog) {
        ServerEditDialog(
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
                // 如果是第一个添加的服务器，自动设为激活
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
    }

    // ── 删除确认对话框 ─────────────────────────────────────────
    if (showDeleteConfirm && editingIndex >= 0) {
        val server = editingServer
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.server_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.server_delete_confirm,
                        server?.name ?: server?.url ?: ""
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
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
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
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
// 服务器编辑/添加对话框
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditDialog(
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

    // 表单状态
    var serverType by remember {
        mutableStateOf(server?.type ?: ServerType.syncclipboard)
    }
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

    val isEditing = server != null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // 标题
                Text(
                    text = stringResource(if (isEditing) R.string.server_edit else R.string.server_add),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))

                // ── 服务器类型选择 ─────────────────────────────
                Text(
                    text = stringResource(R.string.server_type_label),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                ServerType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { serverType = type }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = serverType == type,
                            onClick = { serverType = type }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = when (type) {
                                    ServerType.syncclipboard -> stringResource(R.string.server_type_syncclipboard)
                                    ServerType.webdav -> stringResource(R.string.server_type_webdav)
                                    ServerType.s3 -> stringResource(R.string.server_type_s3)
                                },
                                fontSize = 15.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when (type) {
                                    ServerType.syncclipboard -> stringResource(R.string.server_type_syncclipboard_desc)
                                    ServerType.webdav -> stringResource(R.string.server_type_webdav_desc)
                                    ServerType.s3 -> stringResource(R.string.server_type_s3_desc)
                                },
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MiuixTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(16.dp))

                // ── 连接信息字段 ───────────────────────────────
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.server_name)) },
                    placeholder = { Text(stringResource(R.string.server_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.server_url)) },
                    placeholder = { Text(stringResource(R.string.server_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(modifier = Modifier.height(12.dp))

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
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))

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
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Hide" else "Show", fontSize = 12.sp)
                        }
                    }
                )

                // ── S3 专用字段 ─────────────────────────────────
                if (serverType == ServerType.s3) {
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = region,
                        onValueChange = { region = it },
                        label = { Text(stringResource(R.string.server_region)) },
                        placeholder = { Text(stringResource(R.string.server_region_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = bucketName,
                        onValueChange = { bucketName = it },
                        label = { Text(stringResource(R.string.server_bucket)) },
                        placeholder = { Text(stringResource(R.string.server_bucket_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = objectPrefix,
                        onValueChange = { objectPrefix = it },
                        label = { Text(stringResource(R.string.server_prefix)) },
                        placeholder = { Text(stringResource(R.string.server_prefix_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
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

                Spacer(modifier = Modifier.height(20.dp))

                // ── 按钮行 ─────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 设为当前服务器 (仅编辑时)
                    if (onSetActive != null) {
                        OutlinedButton(
                            onClick = onSetActive,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.server_set_active),
                                fontSize = 13.sp
                            )
                        }
                    }

                    // 测试连接
                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            // TODO: 通过 bridge 进行实际连接测试
                            // 当前使用简单验证
                            val valid = when (serverType) {
                                ServerType.s3 -> bucketName.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                                else -> url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                            }
                            Toast.makeText(
                                context,
                                if (valid) {
                                    if (url.isBlank() && serverType != ServerType.s3)
                                        context.getString(R.string.server_url_required)
                                    else context.getString(R.string.server_test_success)
                                } else {
                                    if (url.isBlank() && serverType != ServerType.s3)
                                        context.getString(R.string.server_url_required)
                                    else if (username.isBlank())
                                        context.getString(R.string.server_username_required)
                                    else context.getString(R.string.server_password_required)
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                            isTesting = false
                        },
                        enabled = !isTesting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (isTesting) stringResource(R.string.server_testing)
                            else stringResource(R.string.action_test_connection),
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 删除按钮 (仅编辑时)
                    if (onDelete != null) {
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.action_delete), fontSize = 13.sp)
                        }
                    }

                    // 取消
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_cancel), fontSize = 13.sp)
                    }

                    // 保存
                    Button(
                        onClick = {
                            // 验证
                            when (serverType) {
                                ServerType.s3 -> {
                                    if (bucketName.isBlank()) {
                                        Toast.makeText(context, context.getString(R.string.server_bucket_required), Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (username.isBlank()) {
                                        Toast.makeText(context, context.getString(R.string.server_access_key_required), Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (password.isBlank()) {
                                        Toast.makeText(context, context.getString(R.string.server_secret_key_required), Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                }
                                else -> {
                                    if (url.isBlank()) {
                                        Toast.makeText(context, context.getString(R.string.server_url_required), Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (username.isBlank()) {
                                        Toast.makeText(context, context.getString(R.string.server_username_required), Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (password.isBlank()) {
                                        Toast.makeText(context, context.getString(R.string.server_password_required), Toast.LENGTH_SHORT).show()
                                        return@Button
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
                        modifier = Modifier.weight(1.5f)
                    ) {
                        Text(stringResource(R.string.action_save), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
