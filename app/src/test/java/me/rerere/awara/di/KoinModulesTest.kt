package me.rerere.awara.di

import androidx.lifecycle.SavedStateHandle
import me.rerere.awara.data.playback.PlaybackPositionRepository
import me.rerere.awara.data.playback.PlaybackPositionStore
import me.rerere.awara.ui.page.history.HistoryVM
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinInternalApi
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 校验生产环境 Koin 模块依赖图完整性，防止 `viewModelOf` 引用含默认参数的构造器时漏注册依赖。
 */
@OptIn(KoinInternalApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KoinModulesTest {

    @Test
    fun playbackPositionRepositoryResolvesToSharedStoreSingleton() {
        val koinApp = createProductionKoinApplication()
        try {
            val resolved = koinApp.koin.get<PlaybackPositionRepository>()
            assertSame(
                "Koin 必须复用 PlaybackPositionStore.default，确保历史清理与播放器共享同一把锁",
                PlaybackPositionStore.default,
                resolved,
            )
            // 同时验证 HistoryVM 的另一个依赖 AppDatabase 可正常解析
            val database = koinApp.koin.get<AppDatabase>()
            database.close()
        } finally {
            koinApp.close()
        }
    }

    @Test
    fun everyRegisteredViewModelConstructorDependencyExistsInKoinGraph() {
        val koinApp = createProductionKoinApplication()
        try {
            val registeredTypes =
                koinApp.koin.instanceRegistry.instances.values
                    .flatMap { factory ->
                        listOf(factory.beanDefinition.primaryType) +
                            factory.beanDefinition.secondaryTypes
                    }
                    .map { it.java }
                    .toSet()

            val registeredViewModels =
                viewModelModule.mappings.values.map { it.beanDefinition.primaryType.java }
            assertTrue("viewModelModule 应包含 HistoryVM 注册", HistoryVM::class.java in registeredViewModels)

            val missingBindings = mutableListOf<String>()
            for (viewModelClass in registeredViewModels) {
                val primaryConstructor =
                    viewModelClass.constructors.firstOrNull { !it.isSynthetic }
                        ?: error("未找到 ${viewModelClass.name} 的公开构造函数")

                for (paramType in primaryConstructor.parameterTypes) {
                    // SavedStateHandle 由 Navigation / AndroidX ViewModelFactory 在运行时动态注入
                    if (paramType == SavedStateHandle::class.java) continue
                    if (paramType !in registeredTypes) {
                        missingBindings.add("${viewModelClass.simpleName} 缺少 Koin 依赖 ${paramType.name}")
                    }
                }
            }

            assertTrue(
                "存在未在 Koin 模块中注册的 ViewModel 构造依赖:\n${missingBindings.joinToString("\n")}",
                missingBindings.isEmpty(),
            )
        } finally {
            koinApp.close()
        }
    }

    private fun createProductionKoinApplication() =
        koinApplication {
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                networkModule,
                databaseModule,
                repoModule,
                tagI18NModule,
                viewModelModule,
            )
        }
}
