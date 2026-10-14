package me.rerere.awara.ui.page.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.awara.R
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton

@Composable
fun DownloadHubPage() {
    val router = LocalRouterProvider.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.download)) },
                navigationIcon = { BackButton() },
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.download_manager)) },
                supportingContent = { Text(stringResource(R.string.download_manager_description)) },
                leadingContent = { Icon(Icons.Outlined.Download, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { router.navigate("download/manager") },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.download_completed)) },
                supportingContent = {
                    Text(stringResource(R.string.download_completed_description))
                },
                leadingContent = { Icon(Icons.Outlined.VideoLibrary, contentDescription = null) },
                trailingContent = {
                    Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { router.navigate("download/videos") },
            )
        }
    }
}
