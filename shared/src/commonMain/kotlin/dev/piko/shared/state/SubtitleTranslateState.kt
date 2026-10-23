package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.library.SubtitleFileSaver
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.player.SubtitleBrowserState
import dev.piko.shared.translate.SubtitleProgress
import dev.piko.shared.translate.TranslationGateway
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.UploadEnqueueResult
import dev.piko.shared.upload.UploadSelection
import dev.piko.shared.upload.UploadStatus
import io.github.nihildigit.pikpak.FileStat
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 把网盘里已有的字幕翻成中文，再传回视频所在目录。
 *
 * 为什么不在本地挂着用：网盘里的字幕是跨设备共享的，翻好一份存回去，
 * 手机、平板、桌面都能直接用，也不用每次重翻花钱。
 *
 * 源字幕取视频同目录下用户选中的那一份（通常是日文），译文命名成 `<番号>.zh.srt`，
 * 传完由播放器当场挂上。
 */
class SubtitleTranslateState(
    /** 同目录字幕列表，直接复用播放器的浏览器状态。 */
    val browser: SubtitleBrowserState,
    private val media: PikoMediaRepository,
    private val http: HttpClient,
    private val uploads: PikoUploadCoordinator,
    private val saver: SubtitleFileSaver?,
    private val gateway: TranslationGateway,
    /** 译文传到哪：视频所在目录。进面板时才定位到，所以传取值函数而不是 id。 */
    private val targetFolder: () -> Pair<String, String>?,
    private val code: String,
    private val scope: CoroutineScope,
    /** 译文上传完成后回调（网盘 fileId、文件名），播放器据此当场挂上。 */
    private val onUploaded: (fileId: String, name: String) -> Unit,
) {
    /** 正在翻译哪个字幕文件（fileId）；null 表示空闲。 */
    var translatingId by mutableStateOf<String?>(null)
        private set
    var stage by mutableStateOf(Stage.IDLE)
        private set
    var progress by mutableStateOf<SubtitleProgress?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<String?>(null)
        private set

    enum class Stage { IDLE, DOWNLOADING, TRANSLATING, UPLOADING }

    private var job: Job? = null

    /** 现在能不能用翻译：模型配好了、也不是没空闲。 */
    fun canTranslate(): Boolean = translatingId == null

    /** 翻 [file]。[code] 用来拼译文名，一般是作品的番号。 */
    fun translate(file: FileStat) {
        if (translatingId != null) return
        error = null
        result = null
        progress = null
        job = scope.launch {
            translatingId = file.id
            try {
                val translator = gateway.subtitleTranslator()
                if (translator == null) {
                    error = "先在设置 → AI 翻译里填好接口、Key 与模型"
                    return@launch
                }
                stage = Stage.DOWNLOADING
                val source = readSubtitle(file.id)
                if (source.isNullOrBlank()) {
                    error = "读不到这个字幕文件"
                    return@launch
                }
                if (code.isBlank()) {
                    error = "拿不到番号，没法给译文起名"
                    return@launch
                }

                stage = Stage.TRANSLATING
                val translated = translator.translate(source) { p -> progress = p }
                    .getOrElse { e ->
                        error = e.message ?: "翻译失败"
                        return@launch
                    }

                stage = Stage.UPLOADING
                val fileName = "$code.zh.srt"
                val uri = saver?.save(fileName, translated.encodeToByteArray())
                if (uri == null) {
                    error = "译文写不进本机缓存"
                    return@launch
                }
                val folder = targetFolder()
                if (folder == null) {
                    error = "定位不到视频所在目录"
                    return@launch
                }
                when (val outcome = uploads.enqueue(UploadSelection(files = listOf(uri)), folder.first, folder.second)) {
                    is UploadEnqueueResult.Rejected -> error = outcome.reason
                    is UploadEnqueueResult.Accepted -> {
                        val fileId = awaitUpload(fileName)
                        if (fileId != null) {
                            result = "已翻译并保存为 $fileName"
                            browser.reload()
                            onUploaded(fileId, fileName)
                        } else {
                            error = "译文上传失败或超时，可在「从网盘选择字幕」里看看是否已传上去"
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "翻译字幕失败", e)
                error = e.message ?: "翻译失败"
            } finally {
                stage = Stage.IDLE
                translatingId = null
                job = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        translatingId = null
        stage = Stage.IDLE
    }

    /** 字幕走本机代理读，几十 KB 很快；超时基本就是网盘那边卡住了。 */
    private suspend fun readSubtitle(fileId: String): String? {
        val stream = media.prepareSubtitle(fileId) ?: return null
        return try {
            val bytes = withTimeoutOrNull(READ_TIMEOUT_MS) {
                http.get(stream.url).readRawBytes()
            } ?: return null
            // 字幕基本都是 UTF-8；个别老文件是 Shift-JIS，解不出来也不阻断——模型照样能翻大部分
            bytes.decodeToString().removePrefix("\uFEFF")
        } finally {
            stream.close()
        }
    }

    private suspend fun awaitUpload(fileName: String): String? {
        val settled = withTimeoutOrNull(UPLOAD_TIMEOUT_MS) {
            uploads.tasks.first { tasks ->
                tasks.values.any {
                    it.fileName == fileName &&
                        (it.status == UploadStatus.COMPLETED || it.status == UploadStatus.FAILED)
                }
            }
        } ?: return null
        val task = settled.values.firstOrNull { it.fileName == fileName } ?: return null
        return task.fileId?.takeIf { task.status == UploadStatus.COMPLETED }
    }

    private companion object {
        const val TAG = "SubtitleTranslate"
        const val READ_TIMEOUT_MS = 30_000L

        /** 字幕只有几十 KB，正常几秒；两分钟还没完多半是网络卡住了。 */
        const val UPLOAD_TIMEOUT_MS = 120_000L
    }
}
