package me.rerere.awara.ui.component.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import me.rerere.awara.R
import me.rerere.awara.data.SearchCategory

/**
 * 通用搜索输入框组件，基于 [DockedSearchBar] 与 [SelectButton] 封装。
 *
 * @param modifier 外部修饰符
 * @param query 当前搜索关键词
 * @param onQueryChange 搜索词变化回调
 * @param value 当前选择的搜索类别/类型
 * @param options 可选的搜索类别列表
 * @param onValueChange 切换类别时的回调
 * @param onSearch 触发搜索时的回调（例如键盘回车确认）
 * @param active 是否处于激活展开状态，默认为 false
 * @param onActiveChange 激活状态变化回调
 * @param placeholder 输入框占位内容
 * @param trailingIcon 输入框尾部操作图标
 * @param content 展开状态下的下拉内容容器
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SearchInput(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    value: T,
    options: List<SelectOption<T>>,
    onValueChange: (T) -> Unit,
    onSearch: (String) -> Unit = {},
    active: Boolean = false,
    onActiveChange: (Boolean) -> Unit = {},
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    DockedSearchBar(
        modifier = modifier,
        query = query,
        onQueryChange = onQueryChange,
        onSearch = onSearch,
        active = active,
        onActiveChange = onActiveChange,
        content = content,
        placeholder = placeholder,
        leadingIcon = {
            SelectButton(value = value, options = options, onValueChange = onValueChange)
        },
        trailingIcon = trailingIcon,
    )
}

/**
 * 本地搜索输入框组件，专门用于历史记录与已下载等本地数据过滤页面。
 *
 * 预设 [SearchCategory.TITLE] 与 [SearchCategory.AUTHOR] 两种搜索分类， 切换分类时保留当前已输入的 [query]，键盘 IME
 * 搜索动作默认隐藏软键盘， 尾部在输入非空时自动展示清空按钮。
 *
 * @param modifier 外部修饰符
 * @param query 当前搜索关键词
 * @param onQueryChange 搜索词变化回调，输入时即时触发本地过滤
 * @param category 当前搜索类别（标题或作者）
 * @param onCategoryChange 搜索类别变化回调
 * @param titleHint 标题模式下的占位提示文本，默认为历史搜索提示词
 * @param authorHint 作者模式下的占位提示文本，默认为作者搜索提示词
 * @param placeholder 自定义占位内容，若提供则优先使用
 * @param onSearch 键盘搜索动作回调，默认收起软键盘与焦点
 * @param trailingIcon 自定义尾部图标，若未提供且 [query] 非空则默认展示清空按钮
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LocalSearchInput(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    category: SearchCategory,
    onCategoryChange: (SearchCategory) -> Unit,
    titleHint: String = stringResource(R.string.history_search_hint),
    authorHint: String = stringResource(R.string.search_author_hint),
    placeholder: @Composable (() -> Unit)? = null,
    onSearch: ((String) -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val actualPlaceholder: @Composable () -> Unit =
        placeholder
            ?: {
                Text(text = if (category == SearchCategory.AUTHOR) authorHint else titleHint)
            }

    val actualTrailingIcon: @Composable (() -> Unit)? =
        trailingIcon
            ?: if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                }
            } else {
                null
            }

    SearchInput(
        modifier = modifier,
        query = query,
        onQueryChange = onQueryChange,
        value = category,
        options = LocalSearchOptions,
        onValueChange = onCategoryChange,
        onSearch =
            onSearch
                ?: {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
        placeholder = actualPlaceholder,
        trailingIcon = actualTrailingIcon,
    )
}

/** 本地搜索预设类别选项列表（标题与作者），供 [LocalSearchInput] 复用。 */
private val LocalSearchOptions =
    listOf(
        SelectOption(
            value = SearchCategory.TITLE,
            label = { Text(stringResource(R.string.search_category_title)) },
        ),
        SelectOption(
            value = SearchCategory.AUTHOR,
            label = { Text(stringResource(R.string.search_category_author)) },
        ),
    )
