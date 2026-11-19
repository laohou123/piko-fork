package dev.piko.shared.library

import kotlinx.serialization.Serializable

/**
 * 一份完整的刮削结果快照，用于换设备或重装后不重刮。
 *
 * 只装「从刮削源拿到的那部分」：标题原文、封面地址、发行日期、时长、简介、片商、系列、
 * 数据源与影片 ID、预览片地址、截图地址、源站带的演员与标签，以及刮削时刻。
 *
 * **不含**译名、自定义标签、自定义女优、收藏：这四类本来就有自己的增量同步文件
 * （`.piko/library-<时间戳>.json`），再塞进快照会变成两份数据打架。恢复时也一并不碰，
 * 本机已有的译名和收藏不会因为恢复旧备份而丢。
 *
 * 图片只存地址不存图：地址由刮削源代理，换设备后按需重新拉，几百 KB 的门面数据
 * 不值得把几百 MB 图片塞进来。
 */
@Serializable
data class MetadataBackup(
    val version: Int = VERSION,
    /** 备份时刻（毫秒）。同时写进文件名，网盘里能直接看出先后。 */
    val createdAt: Long = 0,
    /** 备份在哪个版本上做的，出问题时便于对照；不影响恢复。 */
    val appVersion: String = "",
    val works: List<MetadataWork> = emptyList(),
) {
    companion object {
        /** 格式版本：只在字段含义变化时加，加完旧版本仍能读。 */
        const val VERSION = 1

        /** `.piko` 里的文件名前缀，末尾的短横线与其它同步文件（settings-、library-）分开。 */
        const val FILE_PREFIX = "meta-backup-"
        const val FILE_SUFFIX = ".json"
    }
}

/** 一部作品的刮削结果。字段与库里的 works/work_images/work_actors/work_tags 一一对应。 */
@Serializable
data class MetadataWork(
    val code: String,
    val title: String? = null,
    val coverUrl: String? = null,
    val releaseDate: String? = null,
    /** 时长（分钟） */
    val duration: Int? = null,
    val summary: String? = null,
    val studio: String? = null,
    val series: String? = null,
    val provider: String? = null,
    val mtProvider: String? = null,
    val mtId: String? = null,
    val previewVideoUrl: String? = null,
    /** 刮削时刻：恢复时拿它和本机比，本机更新就跳过。 */
    val scrapedAt: Long = 0,
    /** scan = 扫到的网盘真实文件；vault = 引用保存的清单条目。 */
    val origin: String = "scan",
    /** 截图墙顺序：0 是封面，其后是片段截图。 */
    val images: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/**
 * 恢复结果。[skipped] 是本机已有且刮削时间不早于备份、因而没覆盖的部数——
 * 两台设备各自刮过时，不会用旧的把新的盖回去。
 */
data class MetadataRestoreResult(
    val restored: Int,
    val skipped: Int,
    val total: Int,
)
