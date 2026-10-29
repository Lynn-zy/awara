package me.rerere.awara.ui.component.player

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 监听应用程序 Activity 生命周期，用于识别真正的应用 UI 结束并触发播放器缓存清理。
 *
 * 识别规则：
 * 1. 当最后一个活跃 Activity 处于 finishing 状态且非 configuration change（如屏幕旋转）时，判定为应用 UI 结束退出；
 * 2. Activity 切后台（onStop）不判定为退出；
 * 3. 屏幕旋转等配置变更（isChangingConfigurations == true）不判定为退出；
 * 4. 退出清理通过 App 级别的协程 Scope 在 IO 线程执行，不依赖已取消的 Activity 生命周期。
 *
 * @param onExit 应用确认正常退出时的回调函数，默认调用 [PlayerCache.onAppExit]
 */
class PlayerCacheLifecycleCallbacks(
    private val onExit: (Activity) -> Unit = { activity ->
        PlayerCache.onAppExit(activity.applicationContext)
    }
) : Application.ActivityLifecycleCallbacks {
    private var createdActivityCount: Int = 0

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        createdActivityCount++
    }

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        // 切后台仅触发 onPause / onStop，isFinishing 为 false，绝不清理缓存
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        createdActivityCount--
        // 仅当最后一个 Activity 处于 finishing 状态，且非横竖屏旋转等配置变更重建时，判定为应用 UI 结束退出
        if (activity.isFinishing && !activity.isChangingConfigurations) {
            if (createdActivityCount <= 0) {
                createdActivityCount = 0
                onExit(activity)
            }
        }
    }
}
