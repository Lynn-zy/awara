package me.rerere.awara.ui.page.download

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import me.rerere.awara.R
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.entity.toAvatarUrl

/** Iwara 官方默认头像 URL（供已销号或无头像作者统一使用） */
internal val DEFAULT_IWARA_AVATAR_URL: String = null.toAvatarUrl()

private val PROFILE_URL_PREFIX_REGEX =
    Regex("^https?://(?:www\\.)?iwara\\.tv/(?:profile|user)/([^/?#]+).*$", RegexOption.IGNORE_CASE)

/**
 * 解析并完成准备写入数据库的手动关联作者信息。
 *
 * @property authorId 规范化后的作者唯一标识（不含前导 `@`）
 * @property authorName 展示用作者昵称
 * @property authorAvatar 作者头像地址（已销号或无头像时使用 Iwara 默认头像）
 * @property resolvedOnline 是否成功从在线接口拉取到了存活账号资料
 */
data class ResolvedManualAuthor(
    val authorId: String,
    val authorName: String,
    val authorAvatar: String,
    val resolvedOnline: Boolean,
)

/**
 * 规范化用户输入的作者标识（兼容 `@xxx`、`xxx` 以及粘贴的 Iwara 个人主页链接）。
 *
 * @param rawInput 用户原始输入文本
 * @return 合法的作者用户名 ID（不含 `@`）；若输入为空白或包含非法路径字符则返回 null
 */
fun normalizeAuthorHandle(rawInput: String): String? {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return null
    val extracted =
        PROFILE_URL_PREFIX_REGEX.matchEntire(trimmed)?.groupValues?.getOrNull(1) ?: trimmed
    val cleaned = extracted.trim().trimStart('@').trim()
    if (cleaned.isEmpty()) return null
    if (cleaned.any { it.isWhitespace() || it in setOf('/', '\\', '?', '#', '&') }) return null
    return cleaned
}

/**
 * 根据用户输入的 `@xxx` 解析作者信息：优先联网拉取昵称与头像，若作者已销号则降级采用输入 ID 与 Iwara 默认头像。
 *
 * @param rawHandle 用户输入的作者 ID（如 `@mmd_author`）
 * @param existingAuthors 当前已下载列表中已有的作者分组（同名 ID 可复用本地缓存资料）
 * @param fetchProfile 在线资料查询挂起函数
 */
suspend fun resolveManualAuthorByInput(
    rawHandle: String,
    existingAuthors: List<DownloadAuthorGroup> = emptyList(),
    fetchProfile: suspend (String) -> ProfileDto,
): ResolvedManualAuthor {
    val handle =
        normalizeAuthorHandle(rawHandle) ?: throw IllegalArgumentException("Invalid author handle")
    return try {
        val profile = fetchProfile(handle)
        val user = profile.user
        if (user != null && user.deletedAt == null) {
            val resolvedId = user.username?.trim()?.takeIf { it.isNotEmpty() } ?: handle
            val resolvedName = user.name?.trim()?.takeIf { it.isNotEmpty() } ?: resolvedId
            ResolvedManualAuthor(
                authorId = resolvedId,
                authorName = resolvedName,
                authorAvatar = (user.avatar).toAvatarUrl(),
                resolvedOnline = true,
            )
        } else {
            buildFallbackManualAuthor(handle, existingAuthors)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        buildFallbackManualAuthor(handle, existingAuthors)
    }
}

private fun buildFallbackManualAuthor(
    handle: String,
    existingAuthors: List<DownloadAuthorGroup>,
): ResolvedManualAuthor {
    val matchedGroup =
        existingAuthors.firstOrNull {
            !it.isUnknownAuthor && it.authorId.equals(handle, ignoreCase = true)
        }
    if (matchedGroup != null) {
        return ResolvedManualAuthor(
            authorId = matchedGroup.authorId,
            authorName = matchedGroup.authorName.ifBlank { matchedGroup.authorId },
            authorAvatar = matchedGroup.avatar.ifBlank { DEFAULT_IWARA_AVATAR_URL },
            resolvedOnline = false,
        )
    }
    return ResolvedManualAuthor(
        authorId = handle,
        authorName = handle,
        authorAvatar = DEFAULT_IWARA_AVATAR_URL,
        resolvedOnline = false,
    )
}

/**
 * 对已有作者分组执行固定 A→Z 名称排序并按名称或作者 ID 筛选。
 *
 * 排序规则优先按展示名（昵称非空时取昵称，否则回退至作者 ID）不区分大小写升序排列，
 * 相同名称再依次按区分大小写展示名与作者 ID 稳定兜底；筛选同时兼容原始输入及带 `@` 前缀的 ID。
 *
 * @param authors 待排序与筛选的作者分组列表
 * @param query 用户输入的筛选关键词
 * @return 过滤未知作者、按 A→Z 排序并匹配关键词的作者分组列表
 */
internal fun sortAndFilterExistingAuthorGroups(
    authors: List<DownloadAuthorGroup>,
    query: String = "",
): List<DownloadAuthorGroup> {
    val trimmedQuery = query.trim()
    val normalizedIdQuery = trimmedQuery.trimStart('@').trim()
    return authors
        .asSequence()
        .filterNot { it.isUnknownAuthor }
        .filter { group ->
            trimmedQuery.isEmpty() ||
                group.authorName.contains(trimmedQuery, ignoreCase = true) ||
                "@${group.authorId}".contains(trimmedQuery, ignoreCase = true) ||
                (normalizedIdQuery.isNotEmpty() &&
                    group.authorName.contains(normalizedIdQuery, ignoreCase = true))
        }
        .sortedWith(
            Comparator { a, b ->
                val nameA = a.authorName.trim().ifEmpty { a.authorId.trim() }
                val nameB = b.authorName.trim().ifEmpty { b.authorId.trim() }
                val insensitiveName = String.CASE_INSENSITIVE_ORDER.compare(nameA, nameB)
                if (insensitiveName != 0) return@Comparator insensitiveName
                val exactName = nameA.compareTo(nameB)
                if (exactName != 0) return@Comparator exactName
                val insensitiveId = String.CASE_INSENSITIVE_ORDER.compare(a.authorId, b.authorId)
                if (insensitiveId != 0) insensitiveId else a.authorId.compareTo(b.authorId)
            }
        )
        .toList()
}

private fun pickDefaultAuthorGroup(
    authors: List<DownloadAuthorGroup>,
    initialAuthorId: String,
): DownloadAuthorGroup? =
    authors.firstOrNull { initialAuthorId.isNotBlank() && it.authorId == initialAuthorId }
        ?: authors.firstOrNull()

/** 关联作者弹窗的两种操作模式 */
private enum class LinkAuthorMode {
    EXISTING_GROUP,
    INPUT_HANDLE,
}

/**
 * 关联或修改视频作者的交互弹窗组件。
 *
 * 支持两种关联方式：
 * 1. 直接选择已下载列表中已有的作者分组（固定按名称 A→Z 排序并支持名称/ID 筛选），自动沿用该分组的作者资料；
 * 2. 输入作者 ID（`@xxx`），优先联网拉取资料，若作者已销号则使用输入的 ID 与 Iwara 默认头像建组关联。
 */
@Composable
internal fun LinkAuthorDialog(
    targetCount: Int,
    isRelink: Boolean,
    existingAuthors: List<DownloadAuthorGroup>,
    linking: Boolean,
    initialAuthorId: String = "",
    onSelectExistingGroup: (DownloadAuthorGroup) -> Unit,
    onSubmitAuthorHandle: (rawHandle: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sortedAuthors =
        remember(existingAuthors) { sortAndFilterExistingAuthorGroups(existingAuthors) }
    val hasExistingGroups = sortedAuthors.isNotEmpty()
    var mode by
        remember(hasExistingGroups) {
            mutableStateOf(
                if (hasExistingGroups) LinkAuthorMode.EXISTING_GROUP else LinkAuthorMode.INPUT_HANDLE
            )
        }
    var groupFilterQuery by remember(sortedAuthors) { mutableStateOf("") }
    val filteredAuthors =
        remember(sortedAuthors, groupFilterQuery) {
            sortAndFilterExistingAuthorGroups(sortedAuthors, groupFilterQuery)
        }
    var selectedGroupId by
        remember(sortedAuthors, initialAuthorId) {
            mutableStateOf(
                pickDefaultAuthorGroup(sortedAuthors, initialAuthorId)?.authorId.orEmpty()
            )
        }
    var inputHandle by
        remember(initialAuthorId) {
            mutableStateOf(if (initialAuthorId.isNotBlank()) "@$initialAuthorId" else "")
        }

    val selectedGroup =
        remember(filteredAuthors, selectedGroupId, initialAuthorId) {
            filteredAuthors.firstOrNull { it.authorId == selectedGroupId }
                ?: pickDefaultAuthorGroup(filteredAuthors, initialAuthorId)
        }
    val canConfirm =
        !linking &&
            when (mode) {
                LinkAuthorMode.EXISTING_GROUP -> selectedGroup != null
                LinkAuthorMode.INPUT_HANDLE -> normalizeAuthorHandle(inputHandle) != null
            }

    AlertDialog(
        onDismissRequest = { if (!linking) onDismiss() },
        title = {
            val baseTitle =
                stringResource(
                    if (isRelink) R.string.download_relink_author else R.string.download_link_author
                )
            Text(
                text =
                    if (targetCount > 1) {
                        stringResource(
                            R.string.download_link_author_batch_title,
                            baseTitle,
                            targetCount,
                        )
                    } else {
                        baseTitle
                    }
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 顶部平滑滑动胶囊分段切换器（禁用波纹遮罩，点击即时顺滑平移高亮）
                LinkAuthorModeSwitcher(
                    mode = mode,
                    hasExistingGroups = hasExistingGroups,
                    enabled = !linking,
                    onModeChange = { mode = it },
                )

                if (linking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                when (mode) {
                    LinkAuthorMode.EXISTING_GROUP -> {
                        OutlinedTextField(
                            value = groupFilterQuery,
                            onValueChange = { groupFilterQuery = it },
                            enabled = !linking,
                            singleLine = true,
                            modifier =
                                Modifier.fillMaxWidth().testTag("link_author_existing_search"),
                            placeholder = {
                                Text(stringResource(R.string.search_author_hint))
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon =
                                if (groupFilterQuery.isNotEmpty()) {
                                    {
                                        IconButton(
                                            onClick = { groupFilterQuery = "" },
                                            enabled = !linking,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription =
                                                    stringResource(
                                                        R.string.download_link_author_search_clear
                                                    ),
                                            )
                                        }
                                    }
                                } else null,
                        )

                        if (filteredAuthors.isEmpty()) {
                            Box(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .heightIn(min = 96.dp)
                                        .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.list_search_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(
                                    items = filteredAuthors,
                                    key = { group -> "pick_author_${group.authorId}" },
                                ) { group ->
                                    val isSelected = group.authorId == selectedGroup?.authorId
                                    Row(
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .selectable(
                                                    selected = isSelected,
                                                    enabled = !linking,
                                                    role = Role.RadioButton,
                                                    onClick = { selectedGroupId = group.authorId },
                                                )
                                                .padding(horizontal = 8.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = isSelected, onClick = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        if (group.avatar.isNotBlank()) {
                                            AsyncImage(
                                                model = group.avatar,
                                                placeholder =
                                                    painterResource(R.drawable.default_background),
                                                error =
                                                    painterResource(R.drawable.default_background),
                                                contentDescription = null,
                                                modifier = Modifier.size(36.dp).clip(CircleShape),
                                                contentScale = ContentScale.Crop,
                                            )
                                        } else {
                                            Box(
                                                modifier =
                                                    Modifier.size(36.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            MaterialTheme.colorScheme.surfaceVariant
                                                        ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Person,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                        Column(
                                            modifier = Modifier.weight(1f).padding(start = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = group.authorName.ifBlank { group.authorId },
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text =
                                                    stringResource(
                                                        R.string.download_author_group,
                                                        "@${group.authorId}",
                                                        group.count,
                                                    ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    LinkAuthorMode.INPUT_HANDLE -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = inputHandle,
                                onValueChange = { inputHandle = it },
                                enabled = !linking,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(stringResource(R.string.download_link_author_id_label))
                                },
                                placeholder = {
                                    Text(stringResource(R.string.download_link_author_id_hint))
                                },
                            )
                            Text(
                                text = stringResource(R.string.download_link_author_input_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!canConfirm) return@TextButton
                    when (mode) {
                        LinkAuthorMode.EXISTING_GROUP -> {
                            selectedGroup?.let(onSelectExistingGroup)
                        }
                        LinkAuthorMode.INPUT_HANDLE -> {
                            onSubmitAuthorHandle(inputHandle)
                        }
                    }
                },
                enabled = canConfirm,
            ) {
                Text(text = stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !linking) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * 双选项平滑胶囊切换组件。
 *
 * 移除默认涟漪叠加遮挡，通过底层高亮滑块平滑位移与文字色彩同步过渡，提供干净跟手的切换反馈。
 */
@Composable
private fun LinkAuthorModeSwitcher(
    mode: LinkAuthorMode,
    hasExistingGroups: Boolean,
    enabled: Boolean,
    onModeChange: (LinkAuthorMode) -> Unit,
) {
    BoxWithConstraints(
        modifier =
            Modifier.fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / 2
        val indicatorOffset by
            animateDpAsState(
                targetValue =
                    if (mode == LinkAuthorMode.EXISTING_GROUP) 0.dp else segmentWidth,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "link_author_mode_offset",
            )

        Box(
            modifier =
                Modifier.offset(x = indicatorOffset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            ModeSegmentTab(
                text = stringResource(R.string.download_link_author_tab_existing),
                selected = mode == LinkAuthorMode.EXISTING_GROUP,
                enabled = enabled && hasExistingGroups,
                onClick = { onModeChange(LinkAuthorMode.EXISTING_GROUP) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ModeSegmentTab(
                text = stringResource(R.string.download_link_author_tab_input),
                selected = mode == LinkAuthorMode.INPUT_HANDLE,
                enabled = enabled,
                onClick = { onModeChange(LinkAuthorMode.INPUT_HANDLE) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ModeSegmentTab(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetTextColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val textColor by
        animateColorAsState(
            targetValue = targetTextColor,
            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
            label = "link_author_tab_text_color",
        )
    Box(
        modifier =
            modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style =
                MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
