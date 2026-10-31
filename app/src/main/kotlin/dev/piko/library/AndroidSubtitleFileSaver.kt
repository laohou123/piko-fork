package dev.piko.library

import android.content.Context
import androidx.core.content.FileProvider
import dev.piko.shared.library.SubtitleFileSaver
import java.io.File

/**
 * 抓到的字幕写进应用的缓存目录 subtitles/ 下，交出 content URI 供上传队列读取。
 * 走 [FileProvider]（authority 与 AndroidManifest 里注册的一致），播放器与其他应用
 * 通过它拿到的是授权 URI，不需要存储权限。
 *
 * 缓存目录里的文件随时可能被系统回收，上传完成即由上传队列的清理策略处理；
 * 重下一枚同一文件名的字幕会直接覆盖，不影响上传中的那一份（内容一致）。
 */
class AndroidSubtitleFileSaver(private val context: Context) : SubtitleFileSaver {
    override suspend fun save(fileName: String, bytes: ByteArray): String? {
        // 文件名由 SubtitleSearchState 用「番号.语言.srt」拼成，仍要防路径穿越
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val directory = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val file = File(directory, safeName)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
    }
}
