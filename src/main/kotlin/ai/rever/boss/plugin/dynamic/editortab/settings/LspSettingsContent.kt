@file:OptIn(ExperimentalMaterialApi::class)

package ai.rever.boss.plugin.dynamic.editortab.settings

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossDarkAccent
import ai.rever.boss.plugin.ui.BossDarkBorder
import ai.rever.boss.plugin.ui.BossDarkError
import ai.rever.boss.plugin.ui.BossDarkSuccess
import ai.rever.boss.plugin.ui.BossDarkSurface
import ai.rever.boss.plugin.ui.BossDarkTextMuted
import ai.rever.boss.plugin.ui.BossDarkTextPrimary
import ai.rever.boss.plugin.dynamic.editortab.LspNavigation
import ai.rever.bosseditor.lsp.config.*
import ai.rever.bosseditor.lsp.logging.LogLevel
import ai.rever.bosseditor.lsp.server.LanguageServerRegistry
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings UI section for Language Server Protocol (LSP) configuration.
 *
 * Provides comprehensive control over:
 * - Global LSP enable/disable
 * - Built-in language server management
 * - Custom language server configuration
 * - Timeouts and performance settings
 * - Logging configuration
 */
@Composable
private fun LspSettingsBody() {
    val config by LspSettingsManager.instance.configuration.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Dialog states
    var showAddServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<CustomLanguageServer?>(null) }
    var showLoggingDialog by remember { mutableStateOf(false) }
    var showAdvancedDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // Global Enable/Disable
        SettingsSection(
            title = "LSP Support",
            description = "Enable or disable Language Server Protocol support"
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = if (config.enabled) "Enabled" else "Disabled",
                        fontSize = 14.sp,
                        fontWeight = if (config.enabled) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (config.enabled) BossDarkAccent else MaterialTheme.colors.onSurface
                    )
                    Text(
                        text = "Provides completions, diagnostics, and navigation",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }

                Switch(
                    checked = config.enabled,
                    onCheckedChange = { enabled ->
                        LspSettingsManager.instance.setEnabled(enabled)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BossDarkAccent,
                        checkedTrackColor = BossDarkAccent.copy(alpha = 0.5f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Built-in Language Servers
        SettingsSection(
            title = "Built-in Language Servers",
            description = "Manage pre-configured language servers"
        ) {
            BuiltInServersSection(
                disabledServers = config.disabledServers,
                onToggleServer = { languageId, enabled ->
                    if (enabled) {
                        LspSettingsManager.instance.enableBuiltInServer(languageId)
                    } else {
                        LspSettingsManager.instance.disableBuiltInServer(languageId)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Custom Language Servers
        SettingsSection(
            title = "Custom Language Servers",
            description = "Add your own language server configurations"
        ) {
            Column {
                if (config.customServers.isEmpty()) {
                    EmptyCustomServersPlaceholder(
                        onAddClick = { showAddServerDialog = true }
                    )
                } else {
                    config.customServers.forEach { server ->
                        CustomServerCard(
                            server = server,
                            onEdit = { editingServer = server },
                            onDelete = {
                                LspSettingsManager.instance.removeCustomServer(server.id)
                            },
                            onToggle = { enabled ->
                                LspSettingsManager.instance.setCustomServerEnabled(server.id, enabled)
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    AddServerButton(onClick = { showAddServerDialog = true })
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Timeouts Section
        SettingsSection(
            title = "Timeouts",
            description = "Configure request and initialization timeouts"
        ) {
            Column {
                TimeoutSlider(
                    label = "Request Timeout",
                    value = config.defaultRequestTimeoutMs,
                    range = 5_000f..120_000f,
                    onValueChange = { value ->
                        LspSettingsManager.instance.setRequestTimeout(value.toLong())
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                TimeoutSlider(
                    label = "Initialize Timeout",
                    value = config.initializeTimeoutMs,
                    range = 10_000f..300_000f,
                    onValueChange = { value ->
                        LspSettingsManager.instance.updateConfiguration { current ->
                            current.copy(initializeTimeoutMs = value.toLong())
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Debug Options
        SettingsSection(
            title = "Debugging",
            description = "Options for troubleshooting LSP issues"
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "Trace Messages",
                            fontSize = 14.sp,
                            color = MaterialTheme.colors.onSurface
                        )
                        Text(
                            text = "Log all LSP protocol messages",
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    Switch(
                        checked = config.traceMessages,
                        onCheckedChange = { enabled ->
                            LspSettingsManager.instance.setTraceMessages(enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BossDarkAccent,
                            checkedTrackColor = BossDarkAccent.copy(alpha = 0.5f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Logging Settings Button
                OutlinedButton(
                    onClick = { showLoggingDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = BossDarkAccent
                    ),
                    border = BorderStroke(1.dp, BossDarkBorder)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Configure Logging")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Advanced Settings Button
                OutlinedButton(
                    onClick = { showAdvancedDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    ),
                    border = BorderStroke(1.dp, BossDarkBorder)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Build,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Advanced Settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Reset to Defaults
        SettingsSection(
            title = "Reset",
            description = "Restore default LSP settings"
        ) {
            OutlinedButton(
                onClick = {
                    LspSettingsManager.instance.resetToDefaults()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = BossDarkError
                ),
                border = BorderStroke(1.dp, BossDarkError.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset to Defaults")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // Dialogs
    if (showAddServerDialog) {
        AddEditServerDialog(
            server = null,
            onDismiss = { showAddServerDialog = false },
            onSave = { server ->
                LspSettingsManager.instance.addCustomServer(server)
                showAddServerDialog = false
            }
        )
    }

    editingServer?.let { server ->
        AddEditServerDialog(
            server = server,
            onDismiss = { editingServer = null },
            onSave = { updatedServer ->
                LspSettingsManager.instance.addCustomServer(updatedServer)
                editingServer = null
            }
        )
    }

    if (showLoggingDialog) {
        LoggingSettingsDialog(
            config = config.logging,
            onDismiss = { showLoggingDialog = false },
            onSave = { loggingConfig ->
                LspSettingsManager.instance.setLoggingConfig(loggingConfig)
                showLoggingDialog = false
            }
        )
    }

    if (showAdvancedDialog) {
        AdvancedSettingsDialog(
            config = config,
            onDismiss = { showAdvancedDialog = false },
            onSave = { newConfig ->
                LspSettingsManager.instance.updateConfiguration { newConfig }
                showAdvancedDialog = false
            }
        )
    }
}

@Composable
private fun BuiltInServersSection(
    disabledServers: Set<String>,
    onToggleServer: (String, Boolean) -> Unit
) {
    var serverAvailability by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val availability = LanguageServerRegistry.getAllConfigs().associate { config ->
                // Use the same recovered shell PATH and explicit-path rules as navigation.
                // ServerDiscovery only sees the Dock-launched process PATH, which made a server
                // show as "not installed" here while LspNavigation could launch it successfully.
                config.languageId to (LspNavigation.shared.launchConfig(config) != null)
            }
            serverAvailability = availability
        }
    }

    Column {
        LanguageServerRegistry.getAllConfigs().forEach { config ->
            val isAvailable = serverAvailability[config.languageId] ?: false
            val isEnabled = !disabledServers.contains(config.languageId)

            BuiltInServerRow(
                displayName = config.displayName,
                languageId = config.languageId,
                command = config.command.first(),
                isAvailable = isAvailable,
                isEnabled = isEnabled,
                onToggle = { enabled ->
                    onToggleServer(config.languageId, enabled)
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BuiltInServerRow(
    displayName: String,
    languageId: String,
    command: String,
    isAvailable: Boolean,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                1.dp,
                if (isEnabled && isAvailable) BossDarkAccent.copy(alpha = 0.3f) else BossDarkBorder,
                RoundedCornerShape(8.dp)
            )
            .background(
                if (isEnabled && isAvailable) BossDarkAccent.copy(alpha = 0.05f) else Color.Transparent
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(isAvailable = isAvailable)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = command,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }

        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
            enabled = isAvailable,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BossDarkAccent,
                checkedTrackColor = BossDarkAccent.copy(alpha = 0.5f),
                disabledCheckedThumbColor = BossDarkTextMuted,
                disabledCheckedTrackColor = BossDarkTextMuted.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun StatusBadge(isAvailable: Boolean) {
    val color = if (isAvailable) BossDarkSuccess else BossDarkError
    val text = if (isAvailable) "Installed" else "Not Found"

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

@Composable
private fun CustomServerCard(
    server: CustomLanguageServer,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = BossDarkSurface,
        elevation = 0.dp,
        border = BorderStroke(1.dp, BossDarkBorder)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.displayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = server.command.joinToString(" "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Language: ${server.languageId} | Extensions: ${server.fileExtensions.joinToString(", ")}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                )
            }

            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = BossDarkError,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BossDarkAccent,
                        checkedTrackColor = BossDarkAccent.copy(alpha = 0.5f)
                    )
                )
            }
        }
    }
}

@Composable
private fun EmptyCustomServersPlaceholder(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, BossDarkBorder, RoundedCornerShape(8.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No custom servers configured",
            fontSize = 14.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Add your own language server configurations",
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onAddClick,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = BossDarkAccent
            )
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Server")
        }
    }
}

@Composable
private fun AddServerButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = BossDarkAccent
        ),
        border = BorderStroke(1.dp, BossDarkAccent.copy(alpha = 0.5f))
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Add Custom Server")
    }
}

@Composable
private fun TimeoutSlider(
    label: String,
    value: Long,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = MaterialTheme.colors.onSurface
            )
            Text(
                text = "${value / 1000}s",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossDarkAccent
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = BossDarkAccent,
                activeTrackColor = BossDarkAccent
            )
        )
    }
}

@Composable
private fun AddEditServerDialog(
    server: CustomLanguageServer?,
    onDismiss: () -> Unit,
    onSave: (CustomLanguageServer) -> Unit
) {
    val isEdit = server != null

    var id by remember { mutableStateOf(server?.id ?: "") }
    var displayName by remember { mutableStateOf(server?.displayName ?: "") }
    var languageId by remember { mutableStateOf(server?.languageId ?: "") }
    var command by remember { mutableStateOf(server?.command?.joinToString(" ") ?: "") }
    var fileExtensions by remember { mutableStateOf(server?.fileExtensions?.joinToString(", ") ?: "") }
    var description by remember { mutableStateOf(server?.description ?: "") }
    var requestTimeoutMs by remember { mutableStateOf(server?.requestTimeoutMs ?: 30_000L) }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Template selection
    var showTemplates by remember { mutableStateOf(!isEdit) }

    BossDialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(500.dp)
                .heightIn(max = 600.dp),
            backgroundColor = BossDarkSurface,
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (isEdit) "Edit Language Server" else "Add Language Server",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Template selector for new servers
                if (!isEdit && showTemplates) {
                    Text(
                        text = "Choose a template or configure manually",
                        fontSize = 14.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Column {
                        LanguageServerTemplates.ALL.forEach { template ->
                            TemplateOption(
                                template = template,
                                onClick = {
                                    id = template.id + "-" + System.currentTimeMillis()
                                    displayName = template.displayName
                                    languageId = template.languageId
                                    command = template.command.joinToString(" ")
                                    fileExtensions = template.fileExtensions.joinToString(", ")
                                    description = template.description
                                    showTemplates = false
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(onClick = { showTemplates = false }) {
                        Text("Configure Manually", color = BossDarkAccent)
                    }
                } else {
                    // Form fields
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Display Name") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = languageId,
                        onValueChange = { languageId = it.lowercase().replace(" ", "-") },
                        label = { Text("Language ID") },
                        placeholder = { Text("e.g., python, rust, kotlin") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Command") },
                        placeholder = { Text("e.g., pylsp, rust-analyzer") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = fileExtensions,
                        onValueChange = { fileExtensions = it },
                        label = { Text("File Extensions") },
                        placeholder = { Text("e.g., py, pyw, pyi") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        maxLines = 2
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Request Timeout: ${requestTimeoutMs / 1000}s",
                        fontSize = 14.sp,
                        color = MaterialTheme.colors.onSurface
                    )
                    Slider(
                        value = requestTimeoutMs.toFloat(),
                        onValueChange = { requestTimeoutMs = it.toLong() },
                        valueRange = 5_000f..120_000f,
                        colors = SliderDefaults.colors(
                            thumbColor = BossDarkAccent,
                            activeTrackColor = BossDarkAccent
                        )
                    )

                    errorMessage?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            fontSize = 12.sp,
                            color = BossDarkError
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                // Validate and save
                                val newServer = CustomLanguageServer(
                                    id = if (id.isBlank()) "custom-${System.currentTimeMillis()}" else id,
                                    displayName = displayName,
                                    languageId = languageId,
                                    command = command.split(" ").filter { it.isNotBlank() },
                                    fileExtensions = fileExtensions.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                    enabled = true,
                                    requestTimeoutMs = requestTimeoutMs,
                                    description = description
                                )

                                // Validate
                                when {
                                    displayName.isBlank() -> errorMessage = "Display name is required"
                                    languageId.isBlank() -> errorMessage = "Language ID is required"
                                    command.isBlank() -> errorMessage = "Command is required"
                                    newServer.fileExtensions.isEmpty() -> errorMessage = "At least one file extension is required"
                                    else -> onSave(newServer)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = BossDarkAccent
                            )
                        ) {
                            Text(if (isEdit) "Save" else "Add")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateOption(
    template: CustomLanguageServer,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, BossDarkBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = template.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.onSurface
            )
            Text(
                text = template.description,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
        )
    }
}

@Composable
private fun LoggingSettingsDialog(
    config: LspLoggingConfiguration,
    onDismiss: () -> Unit,
    onSave: (LspLoggingConfiguration) -> Unit
) {
    var globalLevel by remember { mutableStateOf(config.globalLevel) }
    var fileLoggingEnabled by remember { mutableStateOf(config.fileLoggingEnabled) }
    var logFilePath by remember { mutableStateOf(config.logFilePath ?: "") }

    BossDialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.width(450.dp),
            backgroundColor = BossDarkSurface,
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Logging Settings",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onSurface
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Log Level
                Text(
                    text = "Log Level",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                val levels = LogLevel.entries.filter { it != LogLevel.OFF }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    levels.forEach { level ->
                        val isSelected = globalLevel == level.name
                        FilterChip(
                            selected = isSelected,
                            onClick = { globalLevel = level.name },
                            colors = ChipDefaults.filterChipColors(
                                selectedBackgroundColor = BossDarkAccent,
                                selectedContentColor = Color.White,
                                backgroundColor = BossDarkSurface,
                                contentColor = MaterialTheme.colors.onSurface
                            ),
                            border = BorderStroke(1.dp, if (isSelected) BossDarkAccent else BossDarkBorder)
                        ) {
                            Text(level.name, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // File Logging
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "File Logging",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colors.onSurface
                        )
                        Text(
                            text = "Write logs to a file",
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = fileLoggingEnabled,
                        onCheckedChange = { fileLoggingEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BossDarkAccent,
                            checkedTrackColor = BossDarkAccent.copy(alpha = 0.5f)
                        )
                    )
                }

                if (fileLoggingEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = logFilePath,
                        onValueChange = { logFilePath = it },
                        label = { Text("Log File Path (optional)") },
                        placeholder = { Text("~/.boss/logs/lsp.log") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                config.copy(
                                    globalLevel = globalLevel,
                                    fileLoggingEnabled = fileLoggingEnabled,
                                    logFilePath = logFilePath.ifBlank { null }
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossDarkAccent
                        )
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedSettingsDialog(
    config: LspConfiguration,
    onDismiss: () -> Unit,
    onSave: (LspConfiguration) -> Unit
) {
    var maxPendingRequests by remember { mutableStateOf(config.maxPendingRequests) }

    BossDialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.width(450.dp),
            backgroundColor = BossDarkSurface,
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Advanced Settings",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onSurface
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Max Pending Requests
                Text(
                    text = "Max Pending Requests: $maxPendingRequests",
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = maxPendingRequests.toFloat(),
                    onValueChange = { maxPendingRequests = it.toInt() },
                    valueRange = 10f..500f,
                    colors = SliderDefaults.colors(
                        thumbColor = BossDarkAccent,
                        activeTrackColor = BossDarkAccent
                    )
                )
                Text(
                    text = "Maximum number of concurrent LSP requests",
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Warning
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BossDarkError.copy(alpha = 0.1f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = BossDarkError,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Changing these settings may affect stability. Only modify if you know what you're doing.",
                        fontSize = 12.sp,
                        color = BossDarkError
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(config.copy(maxPendingRequests = maxPendingRequests))
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossDarkAccent
                        )
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun textFieldColors() = TextFieldDefaults.outlinedTextFieldColors(
    textColor = BossDarkTextPrimary,
    focusedBorderColor = BossDarkAccent,
    unfocusedBorderColor = BossDarkBorder,
    focusedLabelColor = BossDarkAccent,
    unfocusedLabelColor = BossDarkTextMuted,
    placeholderColor = BossDarkTextMuted,
    cursorColor = BossDarkAccent
)

/**
 * Entry point rendered by EditorTabPluginAPIImpl.LspSettingsPanel.
 */
@Composable
internal fun LspSettingsContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        LspSettingsBody()
    }
}

/**
 * Local copy of the host's SettingsSection helper (host settings-shared
 * components aren't visible to plugins).
 */
@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            color = ai.rever.boss.plugin.ui.BossDarkTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (description != null) {
            Text(
                text = description,
                color = ai.rever.boss.plugin.ui.BossDarkTextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}
