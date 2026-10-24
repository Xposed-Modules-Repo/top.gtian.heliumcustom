package top.gtian.heliumcustom

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 app 的 Application。
 *
 * 作用：注册 [XposedServiceHelper.OnServiceListener]，拿到与 Xposed 框架通信的 binder。
 *
 * 背景（本版本修复的 bug）：Helium 进程读取配置此前只能走模块 app 的 ContentProvider，
 * 依赖模块进程存活。模块被从最近任务划掉后，国产 ROM 常阻止其 ContentProvider 被隐式拉起，
 * 导致配置读取失败、NTP Hook 退回默认值（表现为「未 Hook」），且必须重启浏览器才恢复。
 *
 * 修复：模块 app 一旦与框架建立连接，就把本地已保存的配置同步进框架侧的
 * Remote Preferences（LSPosed 数据库），Helium 进程随后可直接从框架读取，
 * 不再依赖模块进程存活，也不受跨 UID / SELinux 文件读限制。
 */
class HeliumCustomApp : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val TAG = "HeliumNtpHook"

        /** 与框架通信的服务；模块 app 被框架 bind 后可用，死亡后置空。 */
        @Volatile
        var service: XposedService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        try {
            XposedServiceHelper.registerListener(this)
            Log.i(TAG, "XposedServiceHelper listener registered")
        } catch (e: Throwable) {
            Log.w(TAG, "XposedServiceHelper.registerListener failed: ${e.message}")
        }
    }

    override fun onServiceBind(service: XposedService) {
        Companion.service = service
        Log.i(TAG, "Xposed service bound (api=${service.apiVersion})")
        syncLocalToRemote()
    }

    override fun onServiceDied(service: XposedService) {
        if (Companion.service === service) {
            Companion.service = null
        }
        Log.i(TAG, "Xposed service died")
    }

    /**
     * 把本地 SharedPreferences 中的配置迁移 / 同步到框架 Remote Preferences。
     * 幂等：每次模块 app 启动（service bind）都会执行，保证框架侧与本地一致，
     * 用户升级后只需打开一次模块 app 即完成迁移，无需手动点保存。
     */
    private fun syncLocalToRemote() {
        val svc = Companion.service ?: return
        try {
            val local = getSharedPreferences(ConfigActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val remote = svc.getRemotePreferences(ConfigActivity.REMOTE_PREFS_GROUP)
            remote.edit()
                ?.putBoolean(
                    ConfigActivity.KEY_FULLSCREEN,
                    local.getBoolean(ConfigActivity.KEY_FULLSCREEN, false)
                )
                ?.putString(
                    ConfigActivity.KEY_TARGET_URL,
                    local.getString(ConfigActivity.KEY_TARGET_URL, ConfigActivity.DEFAULT_URL)
                )
                ?.apply()
            Log.i(TAG, "synced local config -> remote prefs")
        } catch (e: Throwable) {
            Log.w(TAG, "sync local->remote failed: ${e.message}")
        }
    }
}