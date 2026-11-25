package me.rerere.awara.ui.component.iwara

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.ui.LocalDialogProvider
import me.rerere.awara.ui.LocalMessageProvider

/** 分页栏手动输入页码的本地校验结果。 */
internal sealed interface PageJumpDecision {
    /** 允许直接或通过远端探测跳转至目标页码。 */
    data class Jump(val targetPage: Int) : PageJumpDecision

    /** 页码非法，由界面直接提示无效页码且不发请求。 */
    data object Invalid : PageJumpDecision
}

/**
 * 校验分页栏手动输入的页码字符串。
 *
 * 当服务端仅探测到“下一页”估算总数时，允许在明确存在下一页（`maxPage > currentPage`）
 * 的前提下把超出 `maxPage` 的正整数放行给下游做试探请求；若已处于真实尾页则直接拦截。
 */
internal fun resolvePaginationJumpTarget(
    input: String,
    currentPage: Int,
    maxPage: Int,
    allowProbeBeyondMaxPage: Boolean,
): PageJumpDecision {
    val target = input.trim().toIntOrNull() ?: return PageJumpDecision.Invalid
    if (target < 1) return PageJumpDecision.Invalid
    if (target <= maxPage) return PageJumpDecision.Jump(target)
    if (allowProbeBeyondMaxPage && maxPage > currentPage) {
        return PageJumpDecision.Jump(target)
    }
    return PageJumpDecision.Invalid
}

@Composable
fun PaginationBar(
    modifier: Modifier = Modifier,
    page: Int,
    limit: Int,
    total: Int,
    onPageChange: (Int) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    allowProbeBeyondMaxPage: Boolean = false,
    probing: Boolean = false,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    require(page > 0) { "Page number must be greater than 0" }

    val dialog = LocalDialogProvider.current
    val message = LocalMessageProvider.current
    val maxPage = calculateMaxPage(total = total, limit = limit)

    Surface(
        modifier = modifier,
        tonalElevation = 4.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier.padding(contentPadding)
                    .padding(vertical = 4.dp, horizontal = 8.dp)
                    .fillMaxWidth(),
        ) {
            if (leading != null) {
                leading()
            }

            // Current page
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier.weight(1f).clickable {
                        dialog.input(
                            title = { Text(stringResource(R.string.pagination_jump_to_page)) }
                        ) { input ->
                            when (
                                val decision =
                                    resolvePaginationJumpTarget(
                                        input = input,
                                        currentPage = page,
                                        maxPage = maxPage,
                                        allowProbeBeyondMaxPage = allowProbeBeyondMaxPage,
                                    )
                            ) {
                                is PageJumpDecision.Jump -> onPageChange(decision.targetPage)
                                PageJumpDecision.Invalid -> {
                                    message.error {
                                        Text(stringResource(R.string.pagination_invalid_page, input))
                                    }
                                }
                            }
                        }
                    },
            ) {
                Text(
                    text = stringResource(R.string.pagination_current, page, maxPage),
                    maxLines = 1,
                )
                if (probing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            // Previous page
            FilledTonalIconButton(
                onClick = {
                    if (page > 1) {
                        onPageChange(page - 1)
                    }
                },
            ) {
                Icon(Icons.Outlined.KeyboardArrowLeft, stringResource(R.string.pagination_prev_page))
            }

            // Next page
            FilledTonalIconButton(
                onClick = {
                    if (page in 1 until maxPage) {
                        onPageChange(page + 1)
                    }
                },
            ) {
                Icon(Icons.Outlined.KeyboardArrowRight, stringResource(R.string.pagination_next_page))
            }

            if (trailing != null) {
                trailing()
            }
        }
    }
}

@Preview
@Composable
private fun PaginationPreview() {
    PaginationBar(page = 1, limit = 24, total = 43, onPageChange = {})
}