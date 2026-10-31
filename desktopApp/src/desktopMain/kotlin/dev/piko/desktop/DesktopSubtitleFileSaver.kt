package dev.piko.desktop

import dev.piko.shared.PikoHome
import dev.piko.shared.library.SubtitleFileSaver
import java.io.File

/**
 * 抓到的字幕写进数据根目录的 cache/subtitles 下，交出本机绝对路径（桌面的 uri 就是路径，
 * 见 DesktopPikoUploadSources）。缓存目录随 PikoHome 一起清理，重下一枚同名文件直接覆盖。
 */
class DesktopSubtitleFileSaver : SubtitleFileSaver {
    override suspend fun save(fileName: String, bytes: ByteArray): String? {
        // 文件名由 SubtitleSearchState 用「番号.语言.srt」拼成，仍要防路径穿越
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val directory = PikoHome.root.resolve("cache/subtitles").toFile().apply { mkdirs() }
        val file = File(directory, safeName)
        file.writeBytes(bytes)
        return file.absolutePath
    }
}
