package dev.piko.ui.screens.poster

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.screens.player.SubtitleSearchPanel

/**
 * 海报页的「搜索字幕」：面板本体与播放器里的「搜索字幕」共用
 * [SubtitleSearchPanel]，这里只负责套一层 [PikoSheet]。
 *
 * 早先这个文件自带一整套搜索界面，输入框直接贴着弹窗边缘；搬进共用面板后
 * 左右留白与播放器其它面板一致（见 SubtitleSearchPanel 的说明）。
 */
@Composable
fun SubtitleSearchSheet(
    code: String,
    targetFolderId: String,
    targetFolderName: String,
    onDismissRequest: () -> Unit,
    /** 字幕传上去了（文件 id、文件名）：调用方据此当场挂载。 */
    onUploaded: (fileId: String, fileName: String) -> Unit = { _, _ -> },
) {
    // 底部 sheet 固定到八成五高：面板内部用 weight 分配列表高度，需要有限高度；
    // 高度随内容时 LazyColumn 会按全部条目的自然高度去量，长列表反而撑爆
    PikoSheet(
        onDismissRequest = onDismissRequest,
        sideSheetTitle = "搜索字幕",
        maxHeightFraction = 0.85f,
    ) {
        SubtitleSearchPanel(
            code = code,
            targetFolderId = targetFolderId,
            targetFolderName = targetFolderName,
            onUploaded = { fileId, fileName ->
                onUploaded(fileId, fileName)
                // 传完就收起面板：播放器里会当场挂上，不用留在搜索页
                onDismissRequest()
            },
            modifier = Modifier.weight(1f),
        )
    }
}
