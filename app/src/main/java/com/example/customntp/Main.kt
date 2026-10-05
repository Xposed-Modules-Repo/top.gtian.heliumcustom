package com.example.customntp

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        private const val E2E_LAYOUT_CLASS = "org.chromium.ui.edge_to_edge.layout.EdgeToEdgeBaseLayout"
        private const val TCC_CLASS = "org.chromium.chrome.browser.toolbar.top.ToolbarControlContainer"
        private val NTP_URLS = setOf(
            "chrome-native://newtab/",
            "chrome://newtab/",
            "chrome://new-tab-page/",
            "about:newtab",
            "chrome://newtab"
        )
    }

    private val alreadyHooked = AtomicBoolean(false)
    private val edgeHooked = AtomicBoolean(false)
    private val onDrawHooked = AtomicBoolean(false)
    private val translationHooked = AtomicBoolean(false)
    private var e2eClass: Class<*>? = null
    private var tccClass: Class<*>? = null
    private var customUrl: String = ConfigActivity.DEFAULT_URL
    private var hookEnabled: Boolean = true
    private var fullscreenEnabled: Boolean = true
    // 诊断计数器
    private val paddingLogCount = AtomicInteger(0)
    private val onDrawLogCount = AtomicInteger(0)
    private var lastTranslationY = Float.MAX_VALUE

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "module loaded")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        if (param.packageName != TARGET_PKG) return

        loadConfig()
        log(Log.INFO, TAG, "loaded into $TARGET_PKG (pid=${android.os.Process.myPid()}), " +
            "custom URL = $customUrl, fullscreen = $fullscreenEnabled")

        // 全屏沉浸模式：与 URL 重定向相互独立，单独由开关控制
        if (fullscreenEnabled) {
            hookImmersiveFullscreen()
            hookSetPaddingForEdgeToEdge()
        }

        // 诊断：观察 Omnibox 滚动隐藏行为
        hookTranslationYForDebug()

        if (!hookEnabled) {
            log(Log.INFO, TAG, "URL hook disabled by config")
            return
        }

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
            fullscreenEnabled = prefs.getBoolean(ConfigActivity.KEY_FULLSCREEN, true)
            customUrl = prefs.getString(ConfigActivity.KEY_TARGET_URL, ConfigActivity.DEFAULT_URL)
                ?: ConfigActivity.DEFAULT_URL
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "config read failed, using defaults: ${e.message}")
        }
    }

    // ── 全屏沉浸模式（隐藏状态栏 / 导航栏）────────────────────

    /**
     * hook Activity.onResume 应用沉浸式全屏。
     * 选 onResume 而非 onCreate：系统栏在每次 resume 后都可能被系统恢复，
     * 沉浸式粘性要求每次回到前台都重新隐藏，因此挂在 onResume 上最稳。
     */
    private fun hookImmersiveFullscreen() {
        try {
            val onResume = android.app.Activity::class.java.getDeclaredMethod("onResume")
            hook(onResume)
                .setId("activity-onresume-immersive")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    chain.proceed()
                    val activity = chain.thisObject as android.app.Activity
                    applyImmersive(activity)
                }
            log(Log.INFO, TAG, "✓ hooked Activity.onResume for immersive fullscreen")
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "hook immersive fullscreen failed: ${e.message}")
        }
    }

    /**
     * 应用沉浸式全屏，参考 Via 浏览器 z8.l3 的做法：
     *  - API 30+  : setDecorFitsSystemWindows(false) + WindowInsetsController 隐藏
     *               statusBars + navigationBars，BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
     *               允许侧滑临时唤出
     *  - API 21~29: legacy SYSTEM_UI_FLAG_IMMERSIVE_STICKY 组合
     *
     * 注意：API 35+ 平台忽略 legacy systemUiVisibility 位，单靠 WindowInsetsController.hide()
     * 只隐藏系统栏图标，Chromium 的 EdgeToEdgeBaseLayout 仍按 inset 留白+画黑条。
     * 真正消除黑条由 hookEdgeToEdgeLayout() 负责。
     */
    private fun applyImmersive(activity: android.app.Activity) {
        try {
            val window = activity.window ?: return
            log(Log.INFO, TAG, "applyImmersive: ${activity.javaClass.name}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.apply {
                    hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "applyImmersive failed: ${e.message}")
        }
    }

    /**
     * hook View.setPadding(int,int,int,int)（系统类，不依赖 Chromium 类加载时机）。
     *
     * 根因：API 35+ Chromium 强制 edge-to-edge，fu4.c() 调
     *   this.x.setPadding(左, 状态栏高, 右, 导航栏高)
     * 把内容下推，EdgeToEdgeBaseLayout.onDraw 再画黑矩形 → 顶部黑/白条。
     *
     * 修复：hook View.setPadding（系统类，onPackageLoaded 时一定可 hook），
     * before 里检查 thisObject 是否 EdgeToEdgeBaseLayout 实例（用类名匹配，
     * 不要求该类已加载到 classloader），是且 top>0 则把 top 改 0、保留 bottom。
     * → setPadding(0, bottom) 内部触发 requestLayout → measure 用正确 padding
     * → 内容从 y=0 铺到 H-bottom，无白条无闪烁，omnibox 自动隐藏正常。
     * 第一次匹配到实例时延迟 hook onDraw 跳过黑矩形。
     */
    private fun hookSetPaddingForEdgeToEdge() {
        if (edgeHooked.getAndSet(true)) return
        try {
            val setPadding = View::class.java.getDeclaredMethod(
                "setPadding",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            hook(setPadding)
                .setId("view-setpadding-e2e-zerotop")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val v = chain.thisObject as View
                    val vCls = v.javaClass
                    val cls = e2eClass
                    val matched = if (cls != null) vCls == cls
                                  else vCls.name == E2E_LAYOUT_CLASS
                    if (matched) {
                        if (cls == null) {
                            e2eClass = vCls
                            log(Log.INFO, TAG, "★ FIRST MATCH ${vCls.name}, hook onDraw")
                            maybeHookOnDraw(vCls)
                        }
                        val l = chain.args[0] as Int
                        val t = chain.args[1] as Int
                        val r = chain.args[2] as Int
                        val b = chain.args[3] as Int
                        if (l != 0 || t != 0 || r != 0 || b != 0) {
                            val cnt = paddingLogCount.incrementAndGet()
                            if (cnt <= 10 || cnt % 100 == 0) {
                                log(Log.INFO, TAG, "padding: [$l,$t,$r,$b] -> [0,0,0,0] (count=$cnt)")
                            }
                            chain.proceed(arrayOf(0, 0, 0, 0))
                        } else {
                            chain.proceed()
                        }
                    } else {
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "✓ hooked View.setPadding (zero top for EdgeToEdgeBaseLayout)")
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "hook setPadding failed: ${e.message}")
        }
    }

    /** 第一次遇到 EdgeToEdgeBaseLayout 实例时 hook onDraw 跳过黑矩形 */
    private fun maybeHookOnDraw(cls: Class<*>) {
        if (onDrawHooked.getAndSet(true)) return
        try {
            val onDraw = cls.getDeclaredMethod("onDraw", android.graphics.Canvas::class.java)
            hook(onDraw)
                .setId("e2e-ondraw-skip")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept {
                    val cnt = onDrawLogCount.incrementAndGet()
                    if (cnt <= 3) {
                        log(Log.INFO, TAG, "onDraw skipped (count=$cnt)")
                    }
                }
            log(Log.INFO, TAG, "✓ hooked onDraw (skip black rects)")
        } catch (e: Throwable) {
            onDrawHooked.set(false)
            log(Log.WARN, TAG, "onDraw hook failed: ${e.message}")
        }
    }

    /**
     * 诊断：hook View.setTranslationY，匹配 ToolbarControlContainer 实例，
     * 记录 translationY 变化 — 直接反映 Omnibox 滚动隐藏行为。
     * 滚动时若日志不出现 → BrowserControls 没在移动 Omnibox → 我们的 hook 破坏了它。
     */
    private fun hookTranslationYForDebug() {
        if (translationHooked.getAndSet(true)) return
        try {
            val setTranslationY = View::class.java.getDeclaredMethod(
                "setTranslationY", Float::class.javaPrimitiveType
            )
            hook(setTranslationY)
                .setId("debug-setTranslationY")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val v = chain.thisObject as View
                    val vCls = v.javaClass
                    val cls = tccClass
                    val matched = if (cls != null) vCls == cls
                                  else vCls.name == TCC_CLASS
                    if (matched) {
                        if (cls == null) {
                            tccClass = vCls
                            log(Log.INFO, TAG, "★ TCC FIRST MATCH ${vCls.name}")
                        }
                        val ty = chain.args[0] as Float
                        if (Math.abs(ty - lastTranslationY) > 0.5f) {
                            lastTranslationY = ty
                            log(Log.INFO, TAG, "TCC translationY=$ty")
                        }
                        chain.proceed()
                    } else {
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "✓ hooked setTranslationY for debug")
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "hook setTranslationY failed: ${e.message}")
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
