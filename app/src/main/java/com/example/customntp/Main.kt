package com.example.customntp

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helium Browser NTP Hook — libxposed API 102
 *
 * 反编译 APK (154.0.8037.92) 确认：
 *   1. LoadUrlParams 类名未混淆: org.chromium.content_public.browser.LoadUrlParams
 *   2. 构造函数 public LoadUrlParams(int, String) 是所有构造的最终委托点
 *   3. NTP URL = "chrome-native://newtab/"
 *
 * 策略：
 *   1. onPackageLoaded 时 defaultClassLoader 直接加载（可能已加载）
 *   2. hook Activity.onCreate（系统类，安全），Activity 创建后从其
 *      javaClass.classLoader 主动 loadClass 触发 LoadUrlParams 加载
 *
 * 注意：不 hook ClassLoader.loadClass 基类方法——会导致 Chromium 闪退。
 */
class Main : XposedModule() {

    companion object {
        private const val TAG = "HeliumNtpHook"
        private const val TARGET_PKG = "io.github.jqssun.helium"
        private const val MODULE_PKG = "com.example.customntp"
        private const val LOAD_URL_PARAMS = "org.chromium.content_public.browser.LoadUrlParams"
        private val NTP_URLS = setOf(
            "chrome-native://newtab/",
            "chrome://newtab/",
            "chrome://new-tab-page/",
            "about:newtab",
            "chrome://newtab"
        )
    }

    private val alreadyHooked = AtomicBoolean(false)
    private var customUrl: String = ConfigActivity.DEFAULT_URL
    private var hookEnabled: Boolean = true

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "module loaded")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        if (param.packageName != TARGET_PKG) return

        loadConfig()
        if (!hookEnabled) {
            log(Log.INFO, TAG, "disabled by config")
            return
        }
        log(Log.INFO, TAG, "loaded into $TARGET_PKG (pid=${android.os.Process.myPid()}), custom URL = $customUrl")

        // 策略 1: defaultClassLoader 直接加载（可能已加载）
        val cl = param.defaultClassLoader
        val lupClass = runCatching { cl.loadClass(LOAD_URL_PARAMS) }.getOrNull()
        if (lupClass != null) {
            log(Log.INFO, TAG, "✓ LoadUrlParams found in default classloader")
            hookLoadUrlParamsCtor(lupClass)
            return
        }
        log(Log.INFO, TAG, "LoadUrlParams not in default classloader, will hook Activity.onCreate")

        // 策略 2: hook Activity.onCreate，从 Activity 实例的 classLoader 加载
        hookActivityOnCreate()
    }

    // ── 配置读取 ─────────────────────────────────────────────

    private fun loadConfig() {
        try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? Context ?: return
            val mCtx = app.createPackageContext(MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY)
            val prefs = mCtx.getSharedPreferences(ConfigActivity.PREFS_NAME, Context.MODE_PRIVATE)
            hookEnabled = prefs.getBoolean(ConfigActivity.KEY_ENABLED, true)
            customUrl = prefs.getString(ConfigActivity.KEY_TARGET_URL, ConfigActivity.DEFAULT_URL)
                ?: ConfigActivity.DEFAULT_URL
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "config read failed, using defaults: ${e.message}")
        }
    }

    // ── 策略 2: hook Activity.onCreate ────────────────────────

    private fun hookActivityOnCreate() {
        try {
            val bundleClass = Class.forName("android.os.Bundle")
            val onCreate = android.app.Activity::class.java.getDeclaredMethod("onCreate", bundleClass)
            hook(onCreate)
                .setId("activity-oncreate")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    chain.proceed()
                    tryLoadFromActivity(chain.thisObject as android.app.Activity)
                }
            log(Log.INFO, TAG, "hooked Activity.onCreate, waiting for Chromium classloader...")
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "hook Activity.onCreate failed: ${e.message}")
        }
    }

    private fun tryLoadFromActivity(activity: android.app.Activity) {
        if (alreadyHooked.get()) return
        try {
            val cl = activity.javaClass.classLoader ?: return
            val lupClass = cl.loadClass(LOAD_URL_PARAMS)
            if (alreadyHooked.compareAndSet(false, true)) {
                log(Log.INFO, TAG, "✓ LoadUrlParams loaded via ${activity.javaClass.name} (cl=${cl.javaClass.name})")
                hookLoadUrlParamsCtor(lupClass)
            }
        } catch (_: ClassNotFoundException) {
            // 这个 classLoader 没有 LoadUrlParams，等其他 Activity
        } catch (e: Throwable) {
            if (!alreadyHooked.get()) {
                log(Log.WARN, TAG, "tryLoad failed via ${activity.javaClass.name}: ${e.message}")
            }
        }
    }

    // ── hook LoadUrlParams(int, String) 构造函数 ─────────────

    private fun hookLoadUrlParamsCtor(lupClass: Class<*>) {
        try {
            val ctor = lupClass.getDeclaredConstructor(
                Int::class.javaPrimitiveType, String::class.java
            )
            hook(ctor)
                .setId("loadUrlParams-ctor")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val url = chain.args[1] as? String
                    if (url != null && NTP_URLS.contains(url)) {
                        log(Log.INFO, TAG, "Intercepted NTP: $url -> $customUrl")
                        // chain.args 是不可修改的 List，用 proceed(newArgs) 替换参数
                        chain.proceed(arrayOf(chain.args[0], customUrl))
                    } else {
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "✓ Hooked LoadUrlParams(int, String) constructor")
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "hook constructor failed: ${e.message}")
        }
    }
}
