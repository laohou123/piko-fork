plugins {
    id("piko.kmp.ui")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":ui-core"))
            // 字幕翻译面板要用 Ktor 的 HttpClient 类型
            implementation(libs.ktor.client.core)
        }
    }
}
