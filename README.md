<p align="right"><b>简体中文</b> | <a href="README.en.md">English</a></p>

<p align="center"><img src="docs/icon.png" alt="Piko" width="96"></p>

<h1 align="center">Piko · 海报墙版</h1>

<p align="center">
<a href="#下载安装"><img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-306EFF?style=flat-square&logo=android&logoColor=white"></a>
<a href="#下载安装"><img alt="Windows" src="https://img.shields.io/badge/Windows-10%2B-306EFF?style=flat-square"></a>
<a href="#下载安装"><img alt="macOS" src="https://img.shields.io/badge/macOS-12%2B-306EFF?style=flat-square&logo=apple&logoColor=white"></a>
<a href="#下载安装"><img alt="Linux" src="https://img.shields.io/badge/Linux-x64-306EFF?style=flat-square&logo=linux&logoColor=white"></a>
<br>
<a href="../../releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/laohou123/piko-fork?style=flat-square&color=306EFF&label=Release&logo=github&logoColor=white"></a>
<a href="LICENSE"><img alt="License AGPL-3.0-or-later" src="https://img.shields.io/badge/License-AGPL--3.0--or--later-306EFF?style=flat-square"></a>
</p>

<p align="center"><b>高性能多平台 PikPak 客户端 · 带可刮削的海报墙与 AI 字幕／片名翻译</b></p>

---

## 这是什么

这是 [NihilDigit/piko](https://github.com/NihilDigit/piko) 的定制分支，在上游的网盘客户端之上加了一套**本地影视库**：

- 选一个网盘目录扫描，按作品编号自动归组成「作品」
- 元数据、封面、截图全部经**自建 MetaTube** 代理获取（客户端不直连源站，绕开反爬与图片防盗链）
- **AI 翻译**：字幕逐条对齐翻译、片名批量翻译，模型由你自己填（任意 OpenAI 兼容接口）
- 收藏、演员／标签筛选、搜索、多文件合并、增量重扫等完整浏览体验

上游原有的能力（多平台自适应界面、信息流、磁力／分享链接解析、上传下载、离线包、WebDAV 挂载等）全部保留。

## 海报墙

| 能力 | 说明 |
|---|---|
| 扫描归组 | 递归扫目录，按编号把分集／多版本收成一部；认不出编号的视频也保留，直接显示文件名 |
| 刮削 | MetaTube 优先：标题、封面、背景图、截图墙、演员、标签、片商、系列、时长 |
| 筛选与搜索 | 全部／收藏／演员／标签四个等宽按钮，右上角搜索支持编号、标题、演员、标签 |
| 收藏 | 作品收藏（海报墙／详情页／播放页），演员与标签长按收藏并置顶 |
| 自定义标签 | 详情页标签行末尾的「+」可以给自己加标签，重新刮削不会被冲掉，随账号同步 |
| 详情页 | 顶部用 Piko 自带播放后端播正片中段作预览，横向大封面、截图墙（可全屏左右翻）、底部固定操作栏 |
| 增量重扫 | 网盘里删掉视频，完整重扫后自动移除对应作品；中途停止不会误删 |

## 设置同步

除海报墙索引与刮削结果外，其余设置都随账号走：登录后写到网盘根目录的 `.piko` 文件夹，换设备自动恢复。

- 外观与交互、目录处理、网盘与传输、播放参数、海报墙预览、AI 翻译（接口地址、模型、推理强度、提示词、并发）全部同步
- **MetaTube 令牌与翻译 API Key 也同步，但先加密**：密钥由账号密码派生（PBKDF2 + AES-GCM），别人拿到同步文件也解不开
- 按项带时间戳合并，两台设备各改一项两项都保留；下载目录与代理按设备各自保留，不同步

另有一份 `.piko/library-*.json` 专门放库数据：**中文译名、作品收藏、演员收藏、标签收藏**。
每条带最后修改时刻，谁后改谁赢——两台设备各翻各的、各收藏各的，结果都留下；
取消收藏、把译名改回原名也会同步过去，不会被另一台设备又补回来。
这份文件只在翻译或收藏变化时才重传（批量翻译结束后一次），改设置不会带上它。

图片全部走 MetaTube 的图片代理端点，由服务端取图后再返回，因此不受源站 Referer 校验、防盗链与网络可达性影响。

## AI 翻译

模型自己填，不绑定厂商：DeepSeek、OpenAI、通义、任意中转站、本地 Ollama／LM Studio，只要兼容 `POST /chat/completions`。

**字幕搜索**（详情页 → 字幕）

- 没有中文字幕时，列表里照样列出「中文（简体）」这一行，右边是**翻译**而不是下载
- 点一下 App 照站点自己的做法翻一份（跳过序号与时间轴，按 500 字一批）并把译文传回站点存档，过几分钟重新搜索就能下载
- 站点用的是 Google 免费翻译接口：本机连不上时会改挂到站点队列，并说明原因

**字幕翻译**（播放页 → 字幕面板 → AI 翻译字幕）

- 译文命名 `<编号>.zh.srt`，传回视频所在目录并当场挂载，换设备也能直接用
- 管线四层保护：按字数分批且**不切断单条字幕**、**行数对齐校验**（对不上就逐条重翻）、并发 + 指数退避重试、第二轮串行补漏
- 出错时提示分两行：第一行说哪一类问题（Key／地址／限流／模型名／余额／空响应），第二行附服务端原话与接口地址，点一下即可复制
- 部分行失败时保留原文，不会出现空洞

**片名翻译**

- 设置里一键翻全部：显示进度、可随时停止，每批落库，中断不白花钱
- 详情页标题旁可单独翻译当前作品，或手动改名
- 原文保留在 `title`，译名存 `title_zh`，两者互不覆盖

**可调项**

| 项 | 默认 | 说明 |
|---|---|---|
| 推理强度 | 关闭 | 字幕是成百上千次小请求，开推理明显变慢；接口不认 `reasoning_effort` 时自动降级 |
| 字幕提示词 | 内置中日影视字幕规则 | 可整段替换，建议保留末尾「行数一致／不要行号」等格式约束 |

## 下载安装

到 [Releases](../../releases/latest) 下载安装包。

- **Android**：`piko-1.3.9-universal.apk`，Android 8.0+，约 40 MB（含三 ABI 的 libmpv）
- **桌面端**：Windows／macOS／Linux 构建方式见下方「从源码构建」

> 本分支的包名是 `dev.piko.fork`，官方版是 `dev.piko`，两者签名也不同，因此**可以同时安装、各自独立运行**。
> 数据互不共享：两边的登录、设置与海报墙索引各存各的，升级也只在本分支内进行。

## 首次配置

1. **MetaTube**（设置 → MetaTube）：填自建服务地址与访问令牌。没有它就没有封面与元数据
2. **AI 翻译**（设置 → AI 翻译，可选）：接口地址（填到 `/v1` 为止）、API Key、模型名，点「测试连接」验证
3. **海报墙**（底部导航最左）：右上角「扫描目录」选一个网盘目录，等扫描与补刮完成

## 从源码构建

```bash
# 环境：JDK 21、Android SDK（compileSdk 37），Gradle 随仓库自带
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

# 调试包
./gradlew :app:assembleDebug -Ppiko.enableAbiSplits=false

# 正式包（需要自己的 keystore）
export ANDROID_KEYSTORE_PATH=/path/to/release.jks
export ANDROID_KEYSTORE_PASSWORD='<your-store-password>'
export ANDROID_KEY_ALIAS='<your-key-alias>'
export ANDROID_KEY_PASSWORD='<your-key-password>'
export PIKO_VERSION_NAME=1.3.9
export PIKO_VERSION_CODE=10309
./gradlew :app:assembleRelease -Ppiko.enableAbiSplits=false
```

产物在 `app/build/outputs/apk/`。仓库不保存任何签名文件与密钥。

测试：

```bash
./gradlew :shared:desktopTest :ui:desktopTest
xvfb-run -a ./gradlew :desktopApp:desktopTest   # 桌面 UI 测试需要显示环境
```

## 数据与升级

- 索引按账号各一个 SQLite 文件，存作品、文件、演员、标签、截图、收藏
- schema 升级自动迁移，v4／v5 老库直升最新，刮削结果与译名不丢
- 重扫只重建文件表；元数据、封面、演员、标签、截图、译名一律保留，只有这一轮确实消失的作品才会被删

## 与上游的关系

- 上游：<https://github.com/NihilDigit/piko>
- 本分支的改动集中在海报墙（`shared/.../library`、`shared/.../state`、`ui/.../screens/poster`）与 AI 翻译（`shared/.../translate`），其余保持上游结构，便于跟进更新
- 1.1 起已把上游 2026-10-24 的 main（含 v1.3.0 的归档、驱动与界面改进）整套合并进来，海报墙与翻译功能保持不变
- 检查更新指向本仓库，不会被上游版本覆盖

## 许可

[AGPL-3.0-or-later](LICENSE)，与上游一致。二次分发需同样开源。
