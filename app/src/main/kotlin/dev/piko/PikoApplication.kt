package dev.piko

import android.app.Application
import android.os.Build
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.piko.data.auth.AndroidPikoSessionStore
import dev.piko.data.auth.SessionManager
import dev.piko.data.repository.DriveRepository
import dev.piko.download.AndroidPikoDownloadStorage
import dev.piko.download.AndroidPikoSegmentDownloader
import dev.piko.download.WorkResultNotifier
import dev.piko.library.AndroidLibraryDriverFactory
import dev.piko.library.AndroidSubtitleFileSaver
import dev.piko.platform.AndroidPikoPlatform
import dev.piko.platform.followSystemAnimatorScale
import dev.piko.shared.data.FilePikoCacheStore
import dev.piko.shared.library.SubtitleCatSource
import dev.piko.shared.library.SubtitleSourceOption
import dev.piko.shared.library.XunleiSubtitleSource
import dev.piko.shared.sync.JvmSyncCipher
import dev.piko.shared.scrape.MetaTubeService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoClientManager
import java.io.File
import okio.Path.Companion.toOkioPath
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.log.LogLevel
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.FileClipCache
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.net.PikoProxySelector
import dev.piko.shared.state.InstantSession
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.ui.PikoServices
import dev.piko.ui.theme.PikoMotionScale
import dev.piko.upload.AndroidPikoUploadSources
import dev.piko.update.AppUpdater
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

class PikoApplication : Application(), SingletonImageLoader.Factory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var sessionManager: SessionManager
        private set

    /** 共享界面用到的进程级对象，经 LocalPikoServices 交给界面。 */
    lateinit var services: PikoServices
        private set

    lateinit var platform: AndroidPikoPlatform
        private set

    // 以下几项是 services 的别名：播放器与下载服务还在 app 模块里，经这里取用
    val clientManager: PikoClientManager get() = services.clientManager
    val driveRepository: DriveRepository get() = services.driveRepository
    val instantMagnetRepository: InstantMagnetRepository get() = services.instantMagnetRepository
    val mediampMediaRepository: PikoMediaRepository get() = services.mediaRepository
    val downloadManager: PikoDownloadCoordinator get() = services.downloadManager
    val uploadManager: PikoUploadCoordinator get() = services.uploadManager
    val instantSession: InstantSession get() = services.instantSession

    /** 首次用到时才建：开屏检查要等界面第一次组合，不必在 onCreate 里就先建一个 HTTP 客户端。 */
    val appUpdater by lazy { AppUpdater(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installLog()

        sessionManager = SessionManager(this)
        installProxy()
        val clientManager = PikoClientManager(AndroidPikoSessionStore(this), appScope)
        val mediaRepository = PikoMediaRepository(
            clientManager,
            sessionManager,
            clipCache = FileClipCache(File(cacheDir, "piko/clip-cache")),
        )
        services = PikoServices(
            platformPreferences = sessionManager,
            clientManager = clientManager,
            mediaRepository = mediaRepository,
            downloadManager = PikoDownloadCoordinator(
                clientProvider = clientManager,
                preferences = sessionManager,
                storage = AndroidPikoDownloadStorage(this, sessionManager, appScope),
                scope = appScope,
                segmentDownloader = AndroidPikoSegmentDownloader(this),
                mediaRepository = mediaRepository,
            ),
            uploadSources = AndroidPikoUploadSources(this),
            cacheStore = FilePikoCacheStore(File(cacheDir, "piko").path),
            syncCipher = JvmSyncCipher(),
            metaTube = MetaTubeService(sessionManager) { HttpClient(OkHttp) },
            // 海报墙与刮削字幕：索引库按账号各一个 SQLite，刮削/字幕源共用一个客户端工厂。
            // 带超时：源站不通时快速失败回 null，补刮计数为「没找到」，而不是无限期挂住
            libraryDriverFactory = AndroidLibraryDriverFactory(this),
            scrapeHttpClientFactory = {
                HttpClient(OkHttp) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 30_000
                        connectTimeoutMillis = 15_000
                        socketTimeoutMillis = 30_000
                    }
                }
            },
            // 字幕源按顺序排：第一个是面板默认选中的那个
            subtitleSourceFactory = { http ->
                listOf(
                    SubtitleSourceOption("subtitlecat", "SubtitleCat", SubtitleCatSource(http)),
                    SubtitleSourceOption("xunlei", "迅雷字幕", XunleiSubtitleSource(http)),
                )
            },
            translationHttpClientFactory = {
                // 翻译一次要几十秒，超时按分钟级给；真正的单批上限在 TranslationClient 里控
                HttpClient(OkHttp) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 120_000
                        connectTimeoutMillis = 15_000
                        socketTimeoutMillis = 120_000
                    }
                    engine {
                        config {
                            // OkHttp 默认对同一域名只放 5 个并发请求：翻译并发调到 6 以上时，
                            // 多出来的请求会在客户端排队，看起来就是"设了高并发还是慢"
                            dispatcher(
                                okhttp3.Dispatcher().apply {
                                    maxRequests = 64
                                    maxRequestsPerHost = 32
                                },
                            )
                        }
                    }
                }
            },
            subtitleSaver = AndroidSubtitleFileSaver(this),
        )
        val motionScale = PikoMotionScale().apply { followSystemAnimatorScale(this@PikoApplication) }
        appScope.launch { sessionManager.reduceMotionFlow.collect { motionScale.appReduced = it } }
        platform = AndroidPikoPlatform(this, { appUpdater }, motionScale)
        WorkResultNotifier(this, services, appScope).start()
    }

    /**
     * 日志放在私有的 files/logs，导出时另拷一份到缓存目录再分享。debug 包同时进 logcat；
     * release 包不进，省掉每条一次的 logcat 写入。
     */
    /**
     * 要赶在 SDK、图片加载与更新检查建出 OkHttpClient 之前：它们建客户端时取走默认的 ProxySelector。
     * 同步读一次设置，手动代理从第一个请求起就生效；之后改设置经 flow 跟上。
     */
    private fun installProxy() {
        PikoProxySelector.install(runBlocking { sessionManager.proxySettingFlow.first() })
        appScope.launch { sessionManager.proxySettingFlow.collect(PikoProxySelector::apply) }
    }

    private fun installLog() {
        val echo: ((LogLevel, String, String, Throwable?) -> Unit)? = if (BuildConfig.DEBUG) {
            { level, tag, message, error ->
                val priority = when (level) {
                    LogLevel.DEBUG -> Log.DEBUG
                    LogLevel.INFO -> Log.INFO
                    LogLevel.WARN -> Log.WARN
                    LogLevel.ERROR -> Log.ERROR
                }
                Log.println(priority, "Piko/$tag", if (error == null) message else "$message\n${Log.getStackTraceString(error)}")
            }
        } else {
            null
        }
        PikoLog.install(File(filesDir, "logs").path, echo)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            PikoLog.e("Crash", "线程 ${thread.name} 未捕获的异常", error)
            // 进程马上就没了，等写入协程把这条落盘；卡住也只等一秒，不耽误系统的崩溃处理
            runBlocking { withTimeoutOrNull(1_000) { PikoLog.flush() } }
            previous?.uncaughtException(thread, error)
        }
        PikoLog.i(
            "App",
            "启动 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}），Android ${Build.VERSION.RELEASE}，${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            // 海报墙的封面与截图走 MetaTube 代理，每张都要服务端现取；落到磁盘后
            // 第二次打开直接读本地，不进网络。放 cacheDir（系统空间紧张时可回收），
            // 上限 200MB——按每部约 10 张 × 40KB 算，够存数千部。
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    companion object {
        lateinit var instance: PikoApplication
            private set
    }
}
