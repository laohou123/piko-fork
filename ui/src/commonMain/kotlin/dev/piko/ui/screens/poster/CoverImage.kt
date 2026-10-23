package dev.piko.ui.screens.poster

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest

/**
 * 海报墙的封面图：番号占位（没刮到 / 加载中 / 加载失败）与封面二态。
 *
 * 封面地址现在全部来自用户自建的 MetaTube（`v1/images/...` 代理），是自家服务器直接返回的
 * JPEG——没有防盗链，不需要 Referer 或浏览器 UA，也不受源站在手机网络里不可达的影响。
 * 之前为 JavBus 直连加的那套请求头逻辑已随直抓链路一起删掉。
 *
 * 加载失败不当空白：Coil 失败时回落到番号占位，与没刮到时一致，网格不会出现空洞。
 */
@Composable
internal fun CoverImage(
    code: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    /** Crop 裁切填满（列表卡）；Fit 完整显示、不满的地方留底色（详情页与需要看全图时）。 */
    contentScale: ContentScale = ContentScale.Crop,
) {
    val url = coverUrl?.trim().orEmpty()
    if (url.isEmpty()) {
        CoverPlaceholder(code, modifier)
        return
    }
    val context = LocalPlatformContext.current
    val request = ImageRequest.Builder(context).data(url).build()
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
        loading = { CoverPlaceholder(code, modifier) },
        error = { CoverPlaceholder(code, modifier) },
    )
}

/** 没有封面可显示时的底色块：居中的番号。 */
@Composable
internal fun CoverPlaceholder(code: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp),
        )
    }
}

/** 海报卡共用的封面宽高比（16:9 横版，封面图按 Crop 填满）。 */
internal const val CoverAspectRatio = 16f / 9f
