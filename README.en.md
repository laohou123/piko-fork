<p align="right"><a href="README.md">简体中文</a> | <b>English</b></p>

<p align="center"><img src="docs/icon.png" alt="Piko" width="96"></p>

<h1 align="center">Piko · Poster Wall Edition</h1>

<p align="center">
<a href="#install"><img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-306EFF?style=flat-square&logo=android&logoColor=white"></a>
<a href="#install"><img alt="Windows" src="https://img.shields.io/badge/Windows-10%2B-306EFF?style=flat-square"></a>
<a href="#install"><img alt="macOS" src="https://img.shields.io/badge/macOS-12%2B-306EFF?style=flat-square&logo=apple&logoColor=white"></a>
<a href="#install"><img alt="Linux" src="https://img.shields.io/badge/Linux-x64-306EFF?style=flat-square&logo=linux&logoColor=white"></a>
<br>
<a href="../../releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/laohou123/piko-fork?style=flat-square&color=306EFF&label=Release&logo=github&logoColor=white"></a>
<a href="LICENSE"><img alt="License AGPL-3.0-or-later" src="https://img.shields.io/badge/License-AGPL--3.0--or--later-306EFF?style=flat-square"></a>
</p>

<p align="center"><b>Fast, multi-platform PikPak client · with a scrapable poster wall and AI subtitle/title translation</b></p>

---

## What this is

A custom branch of [NihilDigit/piko](https://github.com/NihilDigit/piko). Everything upstream ships stays, plus a **local media library** on top of your drive:

- Scan a drive folder, group videos into works by catalogue number (multi-part files collapse into one work)
- Metadata, covers and screenshots all come through your **self-hosted MetaTube** (the client never hits source sites directly, so anti-bot and hotlink protection never apply)
- **AI translation** for subtitles and titles with any OpenAI-compatible endpoint you configure
- Favourites, actor/tag filters, search, and incremental rescans

## Poster wall

| Feature | Detail |
|---|---|
| Scan & group | Recursive scan, grouped by code; files whose code can't be parsed are still kept and shown by file name |
| Scraping | Via MetaTube: title, cover, backdrop, screenshot wall, actors, tags, studio, series, runtime |
| Filters | All / Favourites / Actor / Tag as four equal-width chips; search matches code, title, actor or tag |
| Favourites | Works (wall, detail, player); actors and tags via long press, pinned to the top |
| Custom tags | The `+` at the end of the tag row adds your own tags; they survive rescrapes and sync with the account |
| Detail page | Muted preview of the actual video (Piko's own playback backend), large cover, swipeable screenshot wall, fixed bottom action bar |
| Incremental rescan | Deleting a video in the drive removes the work after a full rescan; stopping early never deletes anything |

## Settings sync

Everything except the poster-wall index and scraped metadata follows your account: settings are written to the `.piko` folder in your drive and restored on any device after sign-in.

- Appearance, folder handling, drive and transfer, playback, poster-wall preview and AI translation (base URL, model, reasoning effort, prompt, concurrency) are all synced
- **The MetaTube token and translation API key are synced too, but encrypted first**: the key is derived from your account password (PBKDF2 + AES-GCM), so the sync file alone cannot be read
- Merged per item with timestamps, so two devices editing different settings both keep theirs; download folder and proxy stay per-device

A separate `.piko/library-*.json` carries library data: **Chinese titles, favourite works, actors and tags**.
Every entry carries its last-modified time and the later change wins, so two devices can translate or favourite independently;
un-favouriting and clearing a title travel as well, and are not re-applied by the other device.
That file is only rewritten when titles or favourites change (once after a bulk translation), never by a settings edit.

All images go through MetaTube's image proxy endpoints, so Referer checks, hotlink protection and network reachability of the source site are handled server-side.

## AI translation

Any provider that speaks `POST /chat/completions`: DeepSeek, OpenAI, Qwen, proxies, local Ollama / LM Studio.

**Subtitles** — player → subtitle panel → *AI translate*:

- Saved as `<code>.zh.srt` next to the video and attached immediately, reusable on other devices
- Four safeguards: batching that never splits a cue, line-count validation (falls back to per-line retry), concurrency with exponential backoff, and a slow second pass for leftovers
- Lines that fail keep their source text, so the file never has holes

**Titles** — bulk translate from Settings (progress, cancelable, written per batch), or translate / hand-edit a single work from its detail page. The original title is preserved in `title`, the translation in `title_zh`.

**Tunables**: reasoning effort (off by default — subtitles are many small requests; unsupported endpoints fall back automatically) and a fully editable subtitle prompt.

## Install

Download from [Releases](../../releases/latest).

- **Android**: `piko-1.3.8-universal.apk`, Android 8.0+, ~40 MB (three ABIs of libmpv)
- **Desktop**: build from source with the commands below

> This branch uses the package name `dev.piko.fork` (the official build is `dev.piko`), with its own signing key, so the two **can be installed side by side** and run independently. Data is not shared: sign-in, settings and the poster-wall index live in separate app sandboxes, and updates stay within this branch.

## First run

1. **MetaTube** (Settings → MetaTube): self-hosted URL and access token — without it there are no covers or metadata
2. **AI translation** (Settings → AI translation, optional): base URL (up to `/v1`), API key, model name, then *Test connection*
3. **Poster wall** (leftmost bottom tab): *Scan folder* in the top-right, wait for the scan and enrichment to finish

## Build from source

```bash
# JDK 21, Android SDK (compileSdk 37); the Gradle wrapper ships with the repo
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug -Ppiko.enableAbiSplits=false

# Signed release, with your own keystore
export ANDROID_KEYSTORE_PATH=/path/to/release.jks
export ANDROID_KEYSTORE_PASSWORD='<your-store-password>'
export ANDROID_KEY_ALIAS='<your-key-alias>'
export ANDROID_KEY_PASSWORD='<your-key-password>'
export PIKO_VERSION_NAME=1.3.8
export PIKO_VERSION_CODE=10308
./gradlew :app:assembleRelease -Ppiko.enableAbiSplits=false
```

Tests:

```bash
./gradlew :shared:desktopTest :ui:desktopTest
xvfb-run -a ./gradlew :desktopApp:desktopTest   # desktop UI tests need a display
```

## Data and upgrades

- One SQLite index per account: works, files, actors, tags, screenshots, favourites
- Schema migrations run automatically; v4/v5 databases upgrade in place without losing scraped data or translations
- Rescans only rebuild the file table — metadata, covers, actors, tags, screenshots and translations are kept; only works that really disappeared are removed

## Relation to upstream

- Upstream: <https://github.com/NihilDigit/piko>
- Changes are concentrated in the poster wall (`shared/.../library`, `shared/.../state`, `ui/.../screens/poster`) and AI translation (`shared/.../translate`); the rest follows upstream so updates stay easy to merge
- Since 1.1 the whole upstream main as of 2026-10-24 (including v1.3.0's vault, drive and UI work) has been merged in, with the poster wall and translation left intact
- The update checker points at this repository, so upstream releases will not overwrite this build

## License

[AGPL-3.0-or-later](LICENSE), same as upstream.
