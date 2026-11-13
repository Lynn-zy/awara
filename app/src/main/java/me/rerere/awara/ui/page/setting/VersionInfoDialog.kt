package me.rerere.awara.ui.page.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import me.rerere.awara.R

/** 原作者已删库仓库的展示名称（不直接暴露裸 URL，保持视觉整洁） */
internal const val ORIGINAL_REPO_DISPLAY_NAME = "re-ovo / awara"

/** 当前维护仓库的展示名称（不直接暴露裸 URL，保持视觉整洁） */
internal const val CURRENT_REPO_DISPLAY_NAME = "Lynn-zy / awara"

/** 当前维护仓库的 GitHub 访问地址 */
internal const val CURRENT_REPO_URL = "https://github.com/Lynn-zy/awara"

/** 版本信息卡片测试标签常量，便于仪器测试定位验证 */
internal const val TEST_TAG_VERSION_INFO_CARD = "version_info_card"
internal const val TEST_TAG_ORIGINAL_REPO_ITEM = "version_info_original_repo_item"
internal const val TEST_TAG_CURRENT_REPO_ITEM = "version_info_current_repo_item"

/**
 * 格式化应用版本号展示文本。
 *
 * 统一将版本名与构建号拼接为 `v1.2.9 (29)` 样式；若版本名本身已携带 `v`/`V` 前缀则避免重复添加。
 *
 * @param versionName 应用语义化版本名称（例如 `"1.2.9"`）
 * @param versionCode 应用整数构建版本号（例如 `29`）
 * @return 格式化后的版本展示字符串
 */
internal fun formatAppVersionDisplay(versionName: String, versionCode: Int): String {
    val trimmedName = versionName.trim()
    val normalizedVersion =
        if (trimmedName.startsWith("v", ignoreCase = true)) {
            trimmedName
        } else {
            "v$trimmedName"
        }
    return "$normalizedVersion ($versionCode)"
}

/**
 * 软件版本信息卡片弹窗。
 *
 * 展示软件图标、名称、当前版本号，以及原作者（已删库划掉）与当前维护的源代码仓库入口。
 *
 * @param versionName 应用语义化版本名称
 * @param versionCode 应用整数构建版本号
 * @param onOpenCurrentRepo 点击当前维护仓库条目时的外部跳转回调
 * @param onDismiss 关闭弹窗回调
 */
@Composable
internal fun VersionInfoDialog(
    versionName: String,
    versionCode: Int,
    onOpenCurrentRepo: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        VersionInfoCard(
            versionName = versionName,
            versionCode = versionCode,
            onOpenCurrentRepo = onOpenCurrentRepo,
            onDismiss = onDismiss,
        )
    }
}

/**
 * 软件版本信息卡片主体组件。
 *
 * 独立拆分为无窗口包裹的纯卡片布局，遵循单一职责原则，既供 [VersionInfoDialog] 承载渲染，
 * 也支持在窄屏与大字号仪器测试中直接校验边界与排版。
 *
 * @param versionName 应用语义化版本名称
 * @param versionCode 应用整数构建版本号
 * @param onOpenCurrentRepo 点击当前维护仓库条目时的外部跳转回调
 * @param onDismiss 点击底部关闭按钮时的回调
 * @param modifier 外部修饰符
 */
@Composable
internal fun VersionInfoCard(
    versionName: String,
    versionCode: Int,
    onOpenCurrentRepo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 组合版本展示胶囊文本，例如 v1.2.9 (29)
    val versionBadgeText = formatAppVersionDisplay(versionName = versionName, versionCode = versionCode)

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .testTag(TEST_TAG_VERSION_INFO_CARD),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部品牌标识区：圆角深色底座承载纯白矢量应用徽标，辅以细微描边保证暗色模式下轮廓分明
            AppBrandHeader(versionBadgeText = versionBadgeText)

            // 留出充足上间距后展示源代码仓库分组标题，形成清晰的上下视觉分组
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.setting_about_repositories_label),
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 仓库列表分组容器：上半部为已删库划掉的原作者仓库，下半部为可点击跳转的当前维护仓库
            RepositoryGroupSection(onOpenCurrentRepo = onOpenCurrentRepo)

            // 底部操作区：右对齐关闭按钮
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.close))
                }
            }
        }
    }
}

/**
 * 版本卡片顶部的软件图标、应用名称与版本号胶囊头部区域。
 *
 * @param versionBadgeText 已格式化的版本号文本
 */
@Composable
private fun AppBrandHeader(versionBadgeText: String) {
    // 使用应用原生前景矢量图渲染图标，避免异步位图闪烁并在深浅主题下均保持锐利边缘
    Surface(
        modifier = Modifier.size(68.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF101114),
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f),
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                tint = Color.White,
                // 前景矢量含 Adaptive Icon 安全留白，适度放大使折纸播放图形在 68dp 框内比例饱满
                modifier = Modifier.size(56.dp).scale(1.22f),
            )
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // 软件名称
    Text(
        text = stringResource(R.string.app_name),
        style =
            MaterialTheme.typography.titleLarge.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            ),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

    Spacer(modifier = Modifier.height(6.dp))

    // 版本号胶囊徽章，启用等宽表格数字保证版本号排版齐整
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
    ) {
        Text(
            text = versionBadgeText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style =
                MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 源代码仓库双项分组容器。
 *
 * 第一项为原作者（`re-ovo`）仓库，因已删库以贯穿删除线划掉并置为静态展示；
 * 第二项为当前维护仓库，点击后通过系统浏览器打开对应 GitHub 仓库主页。
 *
 * @param onOpenCurrentRepo 点击当前维护仓库条目时的回调
 */
@Composable
private fun RepositoryGroupSection(onOpenCurrentRepo: () -> Unit) {
    val containerShape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(containerShape),
        shape = containerShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 第一项：原作者仓库（已删库，显示删除横线划掉，静态不可点击）
            RemovedOriginalRepositoryRow()

            Divider(
                modifier = Modifier.padding(horizontal = 14.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )

            // 第二项：当前维护的源代码仓库（支持点击跳转外部浏览器）
            ActiveRepositoryRow(onClick = onOpenCurrentRepo)
        }
    }
}

/**
 * 已删库的原作者仓库展示行。
 *
 * 仓库标识使用删除线（`LineThrough`）清晰划掉，右侧搭配低饱和“已删库”状态签说明原因。
 */
@Composable
private fun RemovedOriginalRepositoryRow() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 58.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag(TEST_TAG_ORIGINAL_REPO_ITEM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 弱化态 GitHub 图标底槽
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(10.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }

        // 中间仓库名称（带贯穿删除横线）与原作者说明
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = ORIGINAL_REPO_DISPLAY_NAME,
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textDecoration = TextDecoration.LineThrough,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.setting_about_original_repo_subtitle),
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }

        // 右侧静态“已删库”状态标签
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        ) {
            Text(
                text = stringResource(R.string.setting_about_original_repo_removed_badge),
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 当前维护的源代码仓库可点击行。
 *
 * 整行具备最小 58dp 触控高度与按钮无障碍角色，点击后调用外部浏览器打开项目仓库。
 *
 * @param onClick 点击整行时的回调
 */
@Composable
private fun ActiveRepositoryRow(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 58.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag(TEST_TAG_CURRENT_REPO_ITEM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 主题强调色容器包裹 GitHub 矢量图标，凸显当前活跃仓库
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        // 当前仓库结构化标识与跳转提示文案
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = CURRENT_REPO_DISPLAY_NAME,
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.setting_about_current_repo_subtitle),
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 外链打开指示图标
        Icon(
            imageVector = Icons.Outlined.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
