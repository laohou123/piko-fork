package dev.piko.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr

/**
 * 字幕行的翻译按钮：点一下就把这份字幕（通常在网盘里）翻成中文，译文传回同一目录。
 *
 * 与 [SubtitleDeleteButton] 同为 30dp 点击区、18dp 图标：字幕行只有 48dp 高，
 * 用 IconButton 的 48dp 最小触控区会让两个按钮几乎贴在一起，容易点错——翻译和删除挨着，
 * 点错的代价太大。
 */
@Composable
internal fun SubtitleTranslateButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Translate,
            contentDescription = tr("翻译这份字幕", "Translate this subtitle", "この字幕を翻訳", "이 자막 번역", "翻譯這份字幕"),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 字幕行的删除按钮：只有图标，不带底圈。
 *
 * 早先带 32dp 的浅红圆圈、又用 IconButton 承载——Material3 会给 IconButton 一个 48dp 的
 * 最小触控区，而行高也是 48dp，几行字幕叠在一起时几个圆圈几乎贴到一起。这里改成
 * 固定 30dp 的点击区、18dp 的图标，行与行之间才留得出空隙。
 */
@Composable
internal fun SubtitleDeleteButton(
    onClick: () -> Unit,
    contentDescription: String = "删除字幕",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Delete,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
    }
}
