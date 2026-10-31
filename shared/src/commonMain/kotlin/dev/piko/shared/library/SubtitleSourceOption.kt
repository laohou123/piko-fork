package dev.piko.shared.library

/**
 * 一个字幕源在界面上的名字与实现。
 *
 * 搜索面板的「展开按钮」列的就是这个列表：用户选哪个，就用哪个源搜。
 * id 只用于界面记忆与日志，不参与网络请求。
 */
data class SubtitleSourceOption(
    val id: String,
    val name: String,
    val source: SubtitleSource,
)
