package me.rerere.awara.ui.page.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.collectLatest
import me.rerere.awara.R
import me.rerere.awara.data.source.stringResourceOfError
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.Button
import me.rerere.awara.ui.component.common.ButtonType
import me.rerere.awara.util.openUrl
import me.rerere.compose_setting.preference.mmkvPreference
import me.rerere.compose_setting.preference.rememberStringPreference
import org.koin.androidx.compose.koinViewModel

@Composable
fun LoginPage(
    vm: LoginVM = koinViewModel()
) {
    val context = LocalContext.current
    val messageProvider = LocalMessageProvider.current
    val router = LocalRouterProvider.current
    var username by rememberStringPreference(key = "login.username", default = "")
    var password by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        // 清理历史版本落盘保存的明文密码，避免凭据继续残留在本地偏好存储中
        mmkvPreference.remove("login.password")
        vm.events.collectLatest {
            when (it) {
                is LoginEvent.LoginSuccess -> {
                    messageProvider.success {
                        Text(stringResource(R.string.login_success))
                    }
                    mmkvPreference.putString("refresh_token", it.token)
                    router.navigate("index") {
                        popUpTo("login") {
                            inclusive = true
                        }
                    }
                }

                is LoginEvent.LoginFailed -> {
                    messageProvider.error {
                        Text(context.stringResourceOfError(it.error))
                    }
                }

                is LoginEvent.Exception -> {
                    messageProvider.error {
                        Text(it.message.asString())
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.login_title),
                    )
                },
                navigationIcon = { BackButton() }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .width(IntrinsicSize.Min),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = R.mipmap.ic_launcher_round,
                    contentDescription = "logo",
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it.replace("\n", "").trim()
                    },
                    label = {
                        Text(text = stringResource(R.string.login_user))
                    }
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it.replace("\n", "").trim()
                    },
                    label = {
                        Text(text = stringResource(R.string.login_password))
                    },
                    visualTransformation = PasswordVisualTransformation(),
                )

                Button(
                    onClick = {
                        vm.login(username, password)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    type = ButtonType.Default,
                    loading = vm.state.loading
                ) {
                    Text(text = stringResource(R.string.login_button))
                }

                Row {
                    var eulaVisible by remember { mutableStateOf(false) }
                    if(eulaVisible) {
                        ModalBottomSheet(
                            onDismissRequest = {
                                eulaVisible = false
                            }
                        ) {
                            UserEula()
                        }
                    }
                    TextButton(
                        onClick = {
                            eulaVisible = true
                        }
                    ) {
                        Text(stringResource(R.string.login_eula))
                    }

                    TextButton(
                        onClick = {
                            context.openUrl("https://iwara.tv/register")
                        }
                    ) {
                        Text(stringResource(R.string.login_register))
                    }
                }
            }
        }
    }
}

@Composable
private fun UserEula() {
    LazyColumn(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
            .height(500.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.login_eula_intro),
                style = MaterialTheme.typography.titleLarge
            )
        }

        item {
            Text(stringResource(R.string.login_eula_1))
        }

        item {
            Text(stringResource(R.string.login_eula_2))
        }

        item {
            Text(stringResource(R.string.login_eula_3))
        }

        item {
            Text(stringResource(R.string.login_eula_4))
        }

        item {
            Text(stringResource(R.string.login_eula_5))
        }
    }
}