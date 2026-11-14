package me.rerere.awara.ui.component.iwara.param.filter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.awara.data.entity.Tag
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.onError
import me.rerere.awara.data.source.onException
import me.rerere.awara.data.source.runAPICatching
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.di.TagI18NResolver
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.iwara.param.FilterChipCloseIcon
import me.rerere.awara.ui.component.iwara.param.FilterValue
import org.koin.compose.koinInject

/**
 * 标签筛选器：支持已选标签管理及在线标签联想搜索
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagFilter(
    values: List<FilterValue>,
    onValueAdd: (FilterValue) -> Unit,
    onValueRemove: (FilterValue) -> Unit
) {
    val tagI18NResolver = koinInject<TagI18NResolver>()
    val locales = LocalConfiguration.current.locales
    // 关联 values 作为 key，兼容 SnapshotStateList 就地修改与不可变 List 替换
    val currentTags by remember(values) {
        derivedStateOf {
            values.filter { it.key == "tags" }
        }
    }

    // query
    val message = LocalMessageProvider.current
    val mediaRepo = koinInject<MediaRepo>()
    val scope = rememberCoroutineScope()
    var query by remember {
        mutableStateOf("")
    }
    var queryLoading by remember {
        mutableStateOf(false)
    }
    var queryActive by remember {
        mutableStateOf(false)
    }
    val queryResult = remember {
        mutableStateListOf<Tag>()
    }
    var searchJob by remember {
        mutableStateOf<Job?>(null)
    }
    val search = {
        searchJob?.cancel()
        searchJob =
            scope.launch {
                queryLoading = true
                queryResult.clear()
                try {
                    runAPICatching {
                        val result = mediaRepo.getTagsSuggestions(query)
                        queryResult.addAll(result.results)
                    }.onError {
                        message.error {
                            Text(stringResource(error = it))
                        }
                    }.onException {
                        // 协程取消异常继续向外抛出，其余异常向用户提示反馈
                        if (it.exception is CancellationException) throw it.exception
                        message.error {
                            Text(
                                it.exception.localizedMessage
                                    ?: it.exception.javaClass.simpleName
                            )
                        }
                    }
                } finally {
                    // 仅当前协程仍有效时复位加载态，避免被新搜索取消后覆盖最新状态
                    if (isActive) {
                        queryLoading = false
                    }
                }
            }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            currentTags.fastForEach {
                FilterChip(
                    selected = true,
                    onClick = {
                        onValueRemove(it)
                    },
                    label = {
                        Text(tagI18NResolver.tagName(it.value, locales))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    leadingIcon = FilterChipCloseIcon
                )
            }
            DockedSearchBar(
                query = query,
                onQueryChange = { query = it },
                onSearch = {
                    search()
                },
                active = queryActive,
                onActiveChange = {
                    queryActive = it
                },
                modifier = Modifier
                    .fillMaxWidth(),
                trailingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (queryActive) {
                            IconButton(
                                onClick = { queryActive = false }
                            ) {
                                Icon(Icons.Outlined.Close, null)
                            }
                        }

                        if(!queryLoading) {
                            IconButton(
                                onClick = {
                                    search()
                                }
                            ) {
                                Icon(Icons.Outlined.Search, null)
                            }
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(queryResult) {
                        Text(
                            text = tagI18NResolver.tagName(it.id, locales),
                            modifier = Modifier
                                .clickable {
                                    onValueAdd(FilterValue("tags", it.id))
                                    queryActive = false
                                    query = ""
                                }
                                .padding(8.dp)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}