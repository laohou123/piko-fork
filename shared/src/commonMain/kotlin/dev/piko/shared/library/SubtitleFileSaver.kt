package dev.piko.shared.library

/**
 * 把抓到的字幕字节写成本机文件，交出一个上传调度器（[dev.piko.shared.upload.PikoUploadSources]）
 * 认得的 uri：Android 是 FileProvider 的 content: URI，桌面是绝对路径。
 *
 * 与刮削、上传都解耦：状态层只认这个接口，平台在入口处实现。
 */
fun interface SubtitleFileSaver {
    /** [fileName] 是建议的文件名（如 `ABC-123.zh-CN.srt`）。写失败返回 null。 */
    suspend fun save(fileName: String, bytes: ByteArray): String?
}
