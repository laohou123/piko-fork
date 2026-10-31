plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.sqldelight)
}

// 海报墙索引：番号、文件、演员与标签的本地库。schema 与查询在 src/commonMain/sqldelight
sqldelight {
    databases {
        create("Library") {
            packageName.set("dev.piko.library")
        }
    }
}

// AGP 9 的 KMP 插件把 android 扩展登记了两个名字：androidLibrary（废弃名）与 android（当前名），
// 两者是同一个扩展对象（KotlinMultiplatformAndroidLibraryExtensionImpl）。但在 Kotlin DSL 里
// kotlin { android { } } 会被 Kotlin Gradle Plugin 的废弃成员函数 android() 抢驻——lambda 收到
// KotlinAndroidTarget，没有 namespace / compileSdk；实测加上 sqldelight 插件后必然触发。
// 用 androidLibrary 这个名字避开冲突，是 AGP 9 对此的既定写法。
kotlin {
    androidLibrary {
        namespace = "dev.piko.shared"
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
    }
    jvm("desktop")

    // 两个目标都是 JVM：用得到 java.net 的代码（代理选择）放在 jvmShared，只写一份
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withCompilations {
                    it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm ||
                        it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // 下载任务表以 JSON 存入偏好。SDK 以 implementation 声明它，不会传递过来
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            // PikPakClient 的构造参数里有 HttpClient，SDK 却只把 Ktor 声明为 runtime 依赖
            implementation(libs.ktor.client.core)
            // 海报墙索引：番号、文件、演员、标签
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            // state holder 的公开 API 直接暴露 Compose 的 State，消费方要拿得到这些类型
            api(libs.cmp.runtime)
            api(libs.pikpak.kotlin)
            // 本机回环代理的服务端：只需 GET/HEAD 加 Range，原始 socket 足够
            implementation(libs.ktor.network)
        }
        // 两个目标都是 JVM：爬虫与字幕站的 HTML 解析用 jsoup，只写一份。
        // jvmShared* 源集由层级模板在目标落地后才创建（AGP 的 android 目标晚于脚本执行），
        // 脚本期拿不到，用 matching + all 等它出现再挂依赖
        matching { it.name.startsWith("jvmShared") }.all {
            dependencies {
                implementation(libs.jsoup)
            }
        }
        // SQLDelight 的驱动在各端入口实现（AndroidSqliteDriver / JdbcSqliteDriver），
        // 见 app 与 desktopApp 的 LibraryDriverFactory
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // 冒烟测试用 MockEngine 顶替 PikPak 的 API 与 CDN，SDK 的请求、鉴权与解析仍走真实代码
        val desktopTest by getting {
            dependencies {
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.serialization.json)
                // 只给本机手动跑的 MetaTube 联调（MetaTubeLiveProbe）用，与两端传入的引擎相同
                implementation(libs.ktor.client.okhttp)
            }
        }
    }
}
