package me.rerere.awara.ui.component.iwara

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.awara.data.entity.Tag
import me.rerere.awara.di.TagI18NResolver
import org.koin.compose.koinInject

@Composable
fun TagChip(tag: Tag) {
    val tagI18NResolver = koinInject<TagI18NResolver>()
    val locales = LocalConfiguration.current.locales
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
            Text(
                text = tagI18NResolver.tagName(tag, locales),
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
            )
        }
    }
}

@Composable
fun TagRow(
    modifier: Modifier = Modifier,
    tags: List<Tag>
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(tags) {
            TagChip(tag = it)
        }
    }
}