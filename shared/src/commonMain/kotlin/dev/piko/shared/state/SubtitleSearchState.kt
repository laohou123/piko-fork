package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.library.SubtitleCandidate
import dev.piko.shared.library.SubtitleDetail
import dev.piko.shared.library.SubtitleFileSaver
import dev.piko.shared.library.SubtitleHit
import dev.piko.shared.library.SubtitleSource
import dev.piko.shared.log.PikoLog
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.UploadEnqueueResult
import dev.piko.shared.upload.UploadSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import dev.piko.shared.upload.UploadStatus
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/**
 * 播放前给一部片找字幕：SubtitleCat 搜番号 → 看详情页有哪些语言 → 选一个下载，
 * 写成本机文件后排进上传队列，传到视频所在的目录。传完之后播放器里的
 * 「网盘字幕」就能看到它（与手动上传的字幕走同一条路）。
 *
 * 一次搜索一个面板用，界面组合时创建，离开即结束。
 */
class SubtitleSearchState(
    private val source: SubtitleSource,
    private val saver: SubtitleFileSaver,
    private val uploads: PikoUploadCoordinator,
    private val scope: CoroutineScope,
    initialQuery: String = "",
    /** 字幕传到网盘后回调（文件 id、文件名）：播放器据此当场挂上，不用退出重进。 */
    private val onUploaded: (fileId: String, fileName: String) -> Unit = { _, _ -> },
) {
    var query by mutableStateOf(initialQuery)
        private set
    var searching by mutableStateOf(false)
        private set
    var hits by mutableStateOf<List<SubtitleHit>>(emptyList())
        private set

    var detail by mutableStateOf<SubtitleDetail?>(null)
        private set
    var loadingDetail by mutableStateOf(false)
        private set

    /** 正在下载与上传的候选路径；同一时刻只处理一个。 */
    var savingPath by mutableStateOf<String?>(null)
        private set

    /** 错误信息（当前这次操作为什么没成）。 */
    var error by mutableStateOf<String?>(null)
        private set

    /** 一次性结果提示（「已上传到 xxx」）。 */
    var result by mutableStateOf<String?>(null)
        private set

    private var searchJob: Job? = null
    private var detailJob: Job? = null

    fun updateQuery(text: String) {
        query = text
    }

    fun search(text: String = query) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        searchJob = scope.launch {
            searching = true
            error = null
            result = null
            detail = null
            try {
                hits = source.search(trimmed)
                if (hits.isEmpty()) error = "没有找到「$trimmed」的字幕"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "搜字幕失败", e)
                error = e.message ?: "搜索失败"
                hits = emptyList()
            } finally {
                searching = false
            }
        }
    }

    fun openDetail(hit: SubtitleHit) {
        detailJob?.cancel()
        detailJob = scope.launch {
            loadingDetail = true
            error = null
            result = null
            try {
                detail = source.detail(hit.pagePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "取字幕详情失败", e)
                error = e.message ?: "读取详情失败"
                detail = null
            } finally {
                loadingDetail = false
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        detail = null
    }

    /**
     * 下载 [candidate] 并上传到 [targetFolderId]（视频所在目录）。
     * [code] 用来拼建议文件名：`ABC-123.zh-CN.srt`。
     */
    fun download(candidate: SubtitleCandidate, code: String, targetFolderId: String, targetFolderName: String) {
        if (savingPath != null) return
        scope.launch {
            savingPath = candidate.path
            error = null
            result = null
            try {
                val bytes = source.download(candidate.path)
                if (bytes == null || bytes.isEmpty()) {
                    error = "字幕下载失败或内容为空"
                    return@launch
                }
                val fileName = "$code.${candidate.language}.srt"
                val uri = saver.save(fileName, bytes)
                if (uri == null) {
                    error = "字幕写不进本机缓存"
                    return@launch
                }
                when (val outcome = uploads.enqueue(UploadSelection(files = listOf(uri)), targetFolderId, targetFolderName)) {
                    is UploadEnqueueResult.Accepted -> {
                        result = "字幕 $fileName 开始上传，完成后自动挂上"
                        awaitUpload(fileName)
                    }
                    is UploadEnqueueResult.Rejected -> error = outcome.reason
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "下载字幕失败", e)
                error = e.message ?: "下载失败"
            } finally {
                savingPath = null
            }
        }
    }

    /**
     * 站点上还没有这个语言（页面给的是 Translate 按钮），请站点把它翻出来。
     * 翻好之后传回站点存档，用户过几分钟重新搜索就能下载——所以这里只报告结果，不接着下载。
     */
    fun requestTranslation(candidate: SubtitleCandidate) {
        if (!candidate.translatable || savingPath != null) return
        scope.launch {
            savingPath = candidate.path
            error = null
            result = null
            try {
                val failure = source.requestTranslation(candidate)
                if (failure == null) {
                    result = "已把译好的「${candidate.label}」传回站点，过几分钟重新搜索就能下载"
                } else {
                    error = failure
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "请求站点翻译失败", e)
                error = e.message ?: "请求翻译失败"
            } finally {
                savingPath = null
            }
        }
    }

    fun consumeResult() {
        result = null
    }

    /**
     * 等这条字幕传完再回调。
     *
     * 以前只提示「开始上传」就结束了，播放器没收到任何通知，字幕要退出重进才看得到。
     * 这里盯上传任务的状态：完成就交出 fileId 交给播放器当场挂上，失败或超时说清楚。
     */
    private fun awaitUpload(fileName: String) {
        scope.launch {
            val settled = withTimeoutOrNull(UPLOAD_TIMEOUT_MS) {
                uploads.tasks.first { tasks ->
                    tasks.values.any {
                        it.fileName == fileName &&
                            ((it.status == UploadStatus.COMPLETED && it.fileId != null) || it.status == UploadStatus.FAILED)
                    }
                }
            }?.values?.firstOrNull { it.fileName == fileName && it.fileId != null }
            val fileId = settled?.fileId
            when {
                fileId != null -> {
                    result = "字幕 $fileName 已挂上"
                    onUploaded(fileId, fileName)
                }
                settled?.status == UploadStatus.FAILED -> {
                    error = "字幕上传失败：${settled.errorMessage ?: "未知原因"}"
                }
                else -> error = "字幕上传超时，可在「从网盘选择字幕」里手动挂"
            }
        }
    }

    private companion object {
        const val TAG = "SubtitleSearch"

        /** 字幕只有几十 KB，正常几秒就传完；超过两分钟多半是网络卡住了。 */
        const val UPLOAD_TIMEOUT_MS = 120_000L
    }
}
