package dev.piko.ui.screens.poster

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.FolderPickerState
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.TooltipIconButton

/**
 * 选海报墙的扫描目录：逐层浏览网盘文件夹，选中当前所在的目录。
 * 用的是通用的 [FolderPickerState]，与别处挑目录同一套行为（自己的路径栈，
 * 不带动主界面的位置）。
 */
@Composable
fun ScanFolderPickerSheet(
    driveRepo: PikoDriveRepository,
    onDismissRequest: () -> Unit,
    onPick: (folderId: String, folderName: String) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = remember(driveRepo) { FolderPickerState(driveRepo, scope) }

    PikoSheet(onDismissRequest = onDismissRequest, sideSheetTitle = "选择扫描目录") {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                TooltipIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    label = "上一级",
                    onClick = { picker.navigateUp() },
                    enabled = picker.path.size > 1,
                )
                Text(
                    text = picker.path.joinToString(" / ") { it.name.ifEmpty { "根目录" } },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
            when {
                picker.isLoading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                picker.loadError != null -> Text(
                    text = picker.loadError!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )

                picker.folders.isEmpty() -> Text(
                    text = "这个目录下没有子文件夹",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(picker.folders, key = { it.id }) { folder ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picker.open(folder) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp).weight(1f),
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                TextButton(onClick = { onDismissRequest() }) { Text("取消") }
                TextButton(
                    onClick = {
                        val current = picker.current
                        onPick(current.id, current.name)
                    },
                ) { Text("扫描这里") }
            }
        }
    }
}
