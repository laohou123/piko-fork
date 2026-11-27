package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.SubtitleBrowserState
import dev.piko.shared.naming.av.matchAvFile
import dev.piko.shared.state.SubtitleTranslateState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.PikoDialog
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 网盘文件页里对单个字幕文件做 AI 翻译。
 *
 * 播放器里翻字幕是「先挑一份源字幕」，文件页则是「就翻你点的这份」，所以不摆文件列表，
 * 只显示进度。译文仍然传回字幕所在目录，与播放器翻出来的完全一致（`<番号>.zh.srt`），
 * 两边互相认得。
 */
@Composable
internal fun SubtitleTranslateDialog(
    file: FileStat,
    onDismiss: () -> Unit,
    /** 译文上传完成，调用方据此刷新列表。 */
    onUploaded: () -> Unit,
) {
    val services = LocalPikoServices.current
    val scope = rememberCoroutineScope()
    // 拿字幕文件本身查所在目录：locateFolder 对文件返回它所在的文件夹
    val browser = remember(file.id) { SubtitleBrowserState(services.driveRepository, scope, file.id) }
    val gateway = services.translation
    val httpClient = services.translationHttp
    val saver = services.subtitleSaver
    val enabled = gateway != null && httpClient != null && saver != null
    // 译文按番号命名，和播放器翻译、搜索字幕下载的命名保持一致。
    // 字幕名解析不出番号（如「字幕.srt」）时退回文件名主干，总比没有名字好。
    val code = remember(file.id) {
        matchAvFile(file.name)?.code ?: file.name.substringBeforeLast('.').trim()
    }
    val state = remember(file.id, enabled) {
        if (!enabled) {
            null
        } else {
            SubtitleTranslateState(
                browser = browser,
                media = services.mediaRepository,
                http = requireNotNull(httpClient),
                uploads = services.uploadManager,
                saver = saver,
                gateway = requireNotNull(gateway),
                targetFolder = { browser.current?.let { it.id to it.name } },
                code = code,
                scope = scope,
                onUploaded = { _, _ -> onUploaded() },
            )
        }
    }

    // 打开就开始翻：用户点这一项就是冲着「翻这份」来的
    LaunchedEffect(file.id, state) {
        if (state == null) return@LaunchedEffect
        val ready = withTimeoutOrNull(8_000) {
            snapshotFlow { browser.current }.first { it != null }
        }
        if (ready != null) state.translate(file.id)
    }

    val close = {
        state?.cancel()
        onDismiss()
    }

    PikoDialog(
        onDismissRequest = close,
        title = { Text(tr("翻译字幕", "Translate subtitles", "字幕を翻訳", "자막 번역", "翻譯字幕")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val progress = state?.progress
                when {
                    state == null -> Text(
                        text = tr(
                            "还没配置 AI 翻译，请到「设置 → AI 翻译」填好接口、Key 与模型",
                            "AI translation is not configured. Fill in the endpoint, key and model under Settings → AI translation",
                            "AI 翻訳が未設定です。「設定 → AI 翻訳」でエンドポイント・キー・モデルを入力してください",
                            "AI 번역이 설정되지 않았습니다. 설정 → AI 번역에서 엔드포인트, 키, 모델을 입력하세요",
                            "尚未設定 AI 翻譯，請到「設定 → AI 翻譯」填好介面、金鑰與模型",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.stage == SubtitleTranslateState.Stage.TRANSLATING -> {
                        Text(
                            text = tr(
                                "正在翻译 ${progress?.doneBatches ?: 0}/${progress?.totalBatches ?: 0} 段（已译 ${progress?.translatedLines ?: 0} 行）",
                                "Translating ${progress?.doneBatches ?: 0}/${progress?.totalBatches ?: 0} batches (${progress?.translatedLines ?: 0} lines)",
                                "${progress?.doneBatches ?: 0}/${progress?.totalBatches ?: 0} バッチを翻訳中（${progress?.translatedLines ?: 0} 行）",
                                "${progress?.doneBatches ?: 0}/${progress?.totalBatches ?: 0} 배치 번역 중 (${progress?.translatedLines ?: 0}행)",
                                "正在翻譯 ${progress?.doneBatches ?: 0}/${progress?.totalBatches ?: 0} 段（已譯 ${progress?.translatedLines ?: 0} 行）",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LinearProgressIndicator(
                            progress = { progress?.fraction ?: 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                    state.stage == SubtitleTranslateState.Stage.DOWNLOADING -> Text(
                        text = tr("正在读取字幕…", "Reading subtitles…", "字幕を読み込み中…", "자막 읽는 중…", "正在讀取字幕…"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.stage == SubtitleTranslateState.Stage.UPLOADING -> Text(
                        text = tr("正在上传译文…", "Uploading translation…", "翻訳をアップロード中…", "번역 업로드 중…", "正在上傳譯文…"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state?.error?.let {
                    Text(
                        text = tr("翻译失败：", "Translation failed: ", "翻訳に失敗しました：", "번역 실패: ", "翻譯失敗：") + it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state?.result?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        // 出错后给一次重试：字幕有几百行时中途断网很常见，重进菜单太绕
        dismissButton = if (state != null && state.error != null) {
            {
                TextButton(onClick = { state.translate(file.id) }) {
                    Text(tr("重试", "Retry", "再試行", "다시 시도", "重試"))
                }
            }
        } else {
            null
        },
        confirmButton = {
            TextButton(onClick = close) {
                Text(tr("关闭", "Close", "閉じる", "닫기", "關閉"))
            }
        },
    )
}
