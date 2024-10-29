package me.rhunk.snapenhance.ui.manager.pages.home

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.common.Constants
import me.rhunk.snapenhance.common.action.EnumAction
import me.rhunk.snapenhance.common.bridge.InternalFileHandleType
import me.rhunk.snapenhance.common.ui.rememberAsyncMutableState
import me.rhunk.snapenhance.ui.manager.Routes
import me.rhunk.snapenhance.ui.setup.Requirements
import me.rhunk.snapenhance.ui.util.ActivityLauncherHelper
import me.rhunk.snapenhance.ui.util.AlertDialogs
import me.rhunk.snapenhance.ui.util.saveFile

class HomeSettings : Routes.Route() {
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    private val dialogs by lazy { AlertDialogs(context.translation) }

    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    @Composable
    private fun RowTitle(title: String) {
        Text(text = title, modifier = Modifier.padding(16.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }

    @Composable
    private fun PreferenceToggle(sharedPreferences: SharedPreferences, key: String, text: String) {
        val realKey = "debug_$key"
        var value by remember { mutableStateOf(sharedPreferences.getBoolean(realKey, false)) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 55.dp)
                .clickable {
                    value = !value
                    sharedPreferences
                        .edit()
                        .putBoolean(realKey, value)
                        .apply()
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = text)
            Switch(checked = value, onCheckedChange = {
                value = it
                sharedPreferences.edit().putBoolean(realKey, it).apply()
            }, modifier = Modifier.padding(end = 26.dp))
        }
    }

    @Composable
    private fun RowAction(key: String, requireConfirmation: Boolean = false, action: () -> Unit) {
        var confirmationDialog by remember {
            mutableStateOf(false)
        }

        fun takeAction() {
            if (requireConfirmation) {
                confirmationDialog = true
            } else {
                action()
            }
        }

        if (requireConfirmation && confirmationDialog) {
            Dialog(onDismissRequest = { confirmationDialog = false }) {
                dialogs.ConfirmDialog(title = context.translation["manager.dialogs.action_confirm.title"], onConfirm = {
                    action()
                    confirmationDialog = false
                }, onDismiss = {
                    confirmationDialog = false
                })
            }
        }

        ShiftedRow(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 55.dp)
                .clickable {
                    takeAction()
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(text = context.translation["actions.$key.name"], fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                context.translation.getOrNull("actions.$key.description")?.let { Text(text = it, fontSize = 12.sp, fontWeight = FontWeight.Light, lineHeight = 15.sp) }
            }
            IconButton(onClick = { takeAction() },
                modifier = Modifier.padding(end = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }

    @Composable
    private fun ShiftedRow(
        modifier: Modifier = Modifier,
        horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
        verticalAlignment: Alignment.Vertical = Alignment.Top,
        content: @Composable RowScope.() -> Unit
    ) {
        Row(
            modifier = modifier.padding(start = 26.dp),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment
        ) { content(this) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            RowTitle(title = translation["actions_title"])
            EnumAction.entries.forEach { enumAction ->
                RowAction(key = enumAction.key) {
                    context.launchActionIntent(enumAction)
                }
            }
            RowAction(key = "regen_mappings") {
                context.checkForRequirements(Requirements.MAPPINGS)
            }
            RowAction(key = "change_language") {
                context.checkForRequirements(Requirements.LANGUAGE)
            }
            RowAction(key = "security_features") {
                context.checkForRequirements(Requirements.SIF)
            }
            RowTitle(title = translation["message_logger_title"])
            ShiftedRow {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    var storedMessagesCount by rememberAsyncMutableState(defaultValue = 0) {
                        context.messageLogger.getStoredMessageCount()
                    }
                    var storedStoriesCount by rememberAsyncMutableState(defaultValue = 0) {
                        context.messageLogger.getStoredStoriesCount()
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(5.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                translation.format("message_logger_summary",
                                "messageCount" to storedMessagesCount.toString(),
                                "storyCount" to storedStoriesCount.toString()
                            ), maxLines = 2)
                        }
                        Button(onClick = {
                            runCatching {
                                activityLauncherHelper.saveFile("message_logger.db", "application/octet-stream") { uri ->
                                    context.androidContext.contentResolver.openOutputStream(uri.toUri())?.use { outputStream ->
                                        context.messageLogger.databaseFile.inputStream().use { inputStream ->
                                            inputStream.copyTo(outputStream)
                                        }
                                    }
                                }
                            }.onFailure {
                                context.log.error("Failed to export database", it)
                                context.longToast("Failed to export database! ${it.localizedMessage}")
                            }
                        }) {
                            Text(text = translation["export_button"])
                        }
                        Button(onClick = {
                            runCatching {
                                context.messageLogger.purgeAll()
                                storedMessagesCount = 0
                                storedStoriesCount = 0
                            }.onFailure {
                                context.log.error("Failed to clear messages", it)
                                context.longToast("Failed to clear messages! ${it.localizedMessage}")
                            }.onSuccess {
                                context.shortToast(translation["success_toast"])
                            }
                        }) {
                            Text(text = translation["clear_button"])
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(5.dp),
                        onClick = {
                            routes.loggerHistory.navigate()
                        }
                    ) {
                        Text(translation["view_logger_history_button"])
                    }
                }
            }

            RowTitle(title = translation["debug_title"])
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var selectedFileType by remember { mutableStateOf(InternalFileHandleType.entries.first()) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 26.dp)
                ) {
                    var expanded by remember { mutableStateOf(false) }

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        TextField(
                            value = selectedFileType.fileName,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.menuAnchor()
                        )

                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            InternalFileHandleType.entries.forEach { fileType ->
                                DropdownMenuItem(onClick = {
                                    expanded = false
                                    selectedFileType = fileType
                                }, text = {
                                    Text(text = fileType.fileName)
                                })
                            }
                        }
                    }
                }
                Button(onClick = {
                    runCatching {
                        context.coroutineScope.launch {
                            selectedFileType.resolve(context.androidContext).delete()
                        }
                    }.onFailure {
                        context.log.error("Failed to clear file", it)
                        context.longToast("Failed to clear file! ${it.localizedMessage}")
                    }.onSuccess {
                        context.shortToast(translation["success_toast"])
                    }
                }) {
                    Text(translation["clear_button"])
                }
            }
            ShiftedRow {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PreferenceToggle(context.sharedPreferences, key = "disable_feature_loading", text = "Disable Feature Loading")
                    PreferenceToggle(context.sharedPreferences, key = "disable_mapper", text = "Disable Auto Mapper")
                    PreferenceToggle(context.sharedPreferences, key = "disable_sif", text = "Disable Security Features")
                }
            }
            Spacer(modifier = Modifier.height(50.dp))
        }
    }
}