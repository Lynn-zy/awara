package me.rerere.awara.ui.component.iwara.param.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.awara.R
import me.rerere.awara.ui.component.iwara.param.FilterValue

private val ratingOptions = listOf(
    "all" to R.string.rating_all,
    "general" to R.string.rating_general,
    "ecchi" to R.string.rating_ecchi,
)

/**
 * 分级筛选器：统一渲染全部/常规/成人分级筛选选项
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatingFilter(
    values: List<FilterValue>,
    onValueAdd: (FilterValue) -> Unit,
    onValueRemove: (FilterValue) -> Unit
) {
    // 关联 values 作为 key，兼容外部不可变 List 替换与 SnapshotStateList 就地修改
    val currentValue: FilterValue? by remember(values) {
        derivedStateOf { values.firstOrNull { it.key == "rating" } }
    }
    FlowRow(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ratingOptions.forEach { (ratingKey, labelRes) ->
            val selected = currentValue?.value == ratingKey
            FilterChip(
                selected = selected,
                label = { Text(stringResource(id = labelRes)) },
                onClick = {
                    val activeValue = currentValue
                    if (activeValue?.value == ratingKey) {
                        onValueRemove(activeValue)
                    } else {
                        activeValue?.let(onValueRemove)
                        onValueAdd(FilterValue("rating", ratingKey))
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}