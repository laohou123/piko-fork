package dev.piko.ui.screens.settings

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.VaultSizeFilter
import dev.piko.data.auth.VaultSizeLimit
import dev.piko.ui.components.PikoTextField
import dev.piko.ui.components.toReadableSize

/** 设置行右边那句话：当前按哪种口径过滤。 */
internal fun vaultSizeFilterSummary(filter: VaultSizeFilter, manualGb: Int, remainingBytes: Long?): String =
    when (filter) {
        VaultSizeFilter.OFF -> tr("不限制", "No limit", "制限なし", "제한 없음", "不限制")
        VaultSizeFilter.MANUAL -> tr(
            "不超过 ${manualGb} GB", "Up to ${manualGb} GB", "${manualGb} GB まで", "${manualGb} GB 이하", "不超過 ${manualGb} GB",
        )
        VaultSizeFilter.AUTO -> remainingBytes?.takeIf { it > 0 }?.let { remaining ->
            val limit = VaultSizeLimit.limitBytes(VaultSizeFilter.AUTO, manualGb, remaining) ?: remaining
            tr(
                "跟随剩余空间（约 ${limit.toReadableSize()}）",
                "Match free space (about ${limit.toReadableSize()})",
                "空き容量に合わせる（約 ${limit.toReadableSize()}）",
                "남은 공간에 맞춤 (약 ${limit.toReadableSize()})",
                "跟隨剩餘空間（約 ${limit.toReadableSize()}）",
            )
        } ?: tr("跟随剩余空间", "Match free space", "空き容量に合わせる", "남은 공간에 맞춤", "跟隨剩餘空間")
    }

/**
 * 引用视频体积上限：自动跟随账号剩余空间、指定一个固定值，或干脆不过滤。
 *
 * 只作用于引用保存的条目。真实文件已在网盘里占着地方，播放不需要额外空间，藏它们没有道理。
 */
@Composable
internal fun VaultSizeLimitDialog(
    filter: VaultSizeFilter,
    manualGb: Int,
    remainingBytes: Long?,
    onSave: (VaultSizeFilter, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(filter) }
    var gbText by remember { mutableStateOf(manualGb.toString()) }
    val gb = gbText.toIntOrNull()?.takeIf { it in VaultSizeLimit.MIN_MANUAL_GB..VaultSizeLimit.MAX_MANUAL_GB }
    val autoLimit = VaultSizeLimit.limitBytes(VaultSizeFilter.AUTO, manualGb, remainingBytes)

    SettingsInputDialog(
        icon = Icons.Outlined.FilterAlt,
        title = tr("引用视频体积上限", "Referenced video size limit", "参照動画のサイズ上限", "참조 동영상 크기 한도", "引用影片大小上限"),
        description = tr(
            "引用视频在网盘里只有一份清单，播放时要在云端临时放下整个文件。账号装不下就会播不了，这类条目可以按体积先藏起来。",
            "Referenced videos exist only as a manifest; playing one needs room for the whole file in the cloud. Hide the ones that will not fit.",
            "参照動画はクラウドに一覧だけがあり、再生時はファイル全体をクラウドに一時配置する必要があります。入りきらないものは先に隠せます。",
            "참조 동영상은 클라우드에 목록만 있고, 재생하려면 파일 전체를 클라우드에 임시로 두어야 합니다. 들어가지 않는 항목은 미리 숨길 수 있습니다.",
            "引用影片在雲端只有一份清單，播放時要在雲端暫存整個檔案。帳號放不下就會播不了，這類項目可以依大小先藏起來。",
        ),
        footer = tr(
            "只影响引用保存的视频，网盘里的真实文件不受影响。",
            "Only affects referenced videos; real files in your cloud drive are untouched.",
            "参照動画のみが対象で、クラウド上の実ファイルには影響しません。",
            "참조 동영상에만 적용되며, 클라우드의 실제 파일에는 영향을 주지 않습니다.",
            "只影響引用儲存的影片，雲端裡的實際檔案不受影響。",
        ),
        onSave = { onSave(mode, gb ?: manualGb) },
        saveEnabled = mode != VaultSizeFilter.MANUAL || gb != null,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsChoiceOption(
                title = tr("自动（跟随剩余空间）", "Automatic (match free space)", "自動（空き容量に合わせる）", "자동 (남은 공간에 맞춤)", "自動（跟隨剩餘空間）"),
                supporting = autoLimit?.let {
                    tr(
                        "当前可播约 ${it.toReadableSize()}；升级会员或清理空间后，藏起来的条目会自己回来",
                        "About ${it.toReadableSize()} playable now; items return by themselves after upgrading or freeing space",
                        "現在は約 ${it.toReadableSize()} まで再生可能。アップグレードや容量確保の後、隠れた項目は自動で戻ります",
                        "현재 약 ${it.toReadableSize()}까지 재생 가능. 업그레이드하거나 공간을 확보하면 숨겨진 항목이 자동으로 돌아옵니다",
                        "目前可播約 ${it.toReadableSize()}；升級會員或清理空間後，藏起來的項目會自動回來",
                    )
                } ?: tr(
                    "按账号剩余空间计算，取不到时不过滤",
                    "Based on free space; no filtering while it is unknown",
                    "空き容量から算出します。取得できない間は絞り込みません",
                    "남은 공간으로 계산하며, 알 수 없으면 필터링하지 않습니다",
                    "依帳號剩餘空間計算，取不到時不過濾",
                ),
                selected = mode == VaultSizeFilter.AUTO,
                onClick = { mode = VaultSizeFilter.AUTO },
            )
            SettingsChoiceOption(
                title = tr("指定上限", "Fixed limit", "上限を指定", "한도 지정", "指定上限"),
                supporting = tr("超过这个体积的引用视频不显示", "Hide referenced videos larger than this", "これを超える参照動画を非表示にします", "이 크기를 넘는 참조 동영상을 숨깁니다", "超過此大小的引用影片不顯示"),
                selected = mode == VaultSizeFilter.MANUAL,
                onClick = { mode = VaultSizeFilter.MANUAL },
            )
            SettingsChoiceOption(
                title = tr("不限制", "No limit", "制限なし", "제한 없음", "不限制"),
                supporting = tr("全部显示，播不了时再说明原因", "Show everything and explain only when playback fails", "すべて表示し、再生できない時だけ理由を説明します", "모두 표시하고, 재생할 수 없을 때만 이유를 알려줍니다", "全部顯示，播不了時再說明原因"),
                selected = mode == VaultSizeFilter.OFF,
                onClick = { mode = VaultSizeFilter.OFF },
            )
        }
        if (mode == VaultSizeFilter.MANUAL) {
            PikoTextField(
                value = gbText,
                onValueChange = { text -> gbText = text.filter(Char::isDigit).take(3) },
                label = tr("上限", "Limit", "上限", "한도", "上限"),
                suffix = "GB",
                isError = gbText.isNotEmpty() && gb == null,
                supportingText = tr(
                    "${VaultSizeLimit.MIN_MANUAL_GB}–${VaultSizeLimit.MAX_MANUAL_GB} 之间的整数",
                    "An integer from ${VaultSizeLimit.MIN_MANUAL_GB} to ${VaultSizeLimit.MAX_MANUAL_GB}",
                    "${VaultSizeLimit.MIN_MANUAL_GB}〜${VaultSizeLimit.MAX_MANUAL_GB} の整数",
                    "${VaultSizeLimit.MIN_MANUAL_GB}~${VaultSizeLimit.MAX_MANUAL_GB} 사이의 정수",
                    "${VaultSizeLimit.MIN_MANUAL_GB}–${VaultSizeLimit.MAX_MANUAL_GB} 之間的整數",
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}
