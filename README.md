# Helium NTP Hook — LSPosed Module

> **方案二：LSPosed (Xposed) Hook** — 当扩展方案不生效或需要 Java/Native 层底层拦截时使用

## 原理

Helium / Titanium Browser (`io.github.jqssun.helium`) 基于 Chromium 154，导航最终经过
`LoadUrlParams(int transitionType, String url)` 构造函数。Hook 此构造函数，检测 URL 是否为
NTP（`chrome-native://newtab/`），若是则替换为用户配置的自定义 URL。

### 关键事实（反编译 APK 确认）

-   `LoadUrlParams` 类名**未混淆**：`org.chromium.content_public.browser.LoadUrlParams`
-   三个构造函数 `(String)`、`(String, Map)`、`(int, String)` 都最终委托到 `(int, String)`
-   NTP URL = `chrome-native://newtab/`
-   `LoadUrlParams` 在 `classes.dex`（10MB，含 Chromium），运行时由 `PathClassLoader` 加载

## Hook 策略

使用 **libxposed API 102**（现代 LSPosed API），非传统 Xposed API 82。

| 步骤 | 做法 | 说明 |
|------|------|------|
| 1 | `onPackageLoaded` 时 `param.defaultClassLoader.loadClass` | 可能已加载，直接 hook |
| 2 | 失败则 hook `Activity.onCreate`（系统类，安全） | Chromium 类懒加载，此时未就绪 |
| 3 | Activity 创建后从 `activity.javaClass.classLoader` 加载 | 获取真正的 Chromium ClassLoader |
| 4 | Hook `LoadUrlParams(int, String)` 构造函数 | 拦截所有 URL 构造 |
| 5 | `chain.proceed(arrayOf(args[0], customUrl))` 替换 URL | `chain.args` 不可修改，用 `proceed(newArgs)` |

### 为什么不 hook `ClassLoader.loadClass`？

早期版本曾尝试 hook `ClassLoader.loadClass` 基类方法等待 `LoadUrlParams` 被加载，
但这会导致 **Chromium 闪退**——`loadClass` 是类加载核心方法，ART 层面 hook 它会干扰
Chromium 启动时上万个类的加载。改用 hook `Activity.onCreate` 完全安全。

## 构建

详见 [`编译教程.md`](编译教程.md)。

```powershell
cd custom-ntp-lsp
$env:JAVA_HOME = "D:\DevTools\Java\jdk-17"
.\gradlew.bat assembleDebug
# 产物: app\build\outputs\apk\debug\app-debug.apk
```

## 安装与启用

1.  **安装 APK** 到已 root 并装有 LSPosed 的设备
    ```powershell
    adb install -r app\build\outputs\apk\debug\app-debug.apk
    ```
2.  打开 **LSPosed Manager**
3.  在模块列表中找到 **Helium NTP Hook**，启用它
4.  在模块的**作用域 (Scope)** 中勾选 **Helium / Titanium Browser**（`io.github.jqssun.helium`）
    -   模块已在 `scope.list` 中声明默认作用域，通常自动勾选
5.  打开本模块的配置页：
    -   开启 **启用 Hook** 开关
    -   填入 **目标 URL**（如 `chrome-native://bookmarks/folder/10397`）
    -   点击 **保存并启用**
6.  **强制停止** Helium 浏览器，重新打开
7.  新建标签页 → 自动跳转到自定义 URL

## 调试日志

```powershell
adb logcat -s HeliumNtpHook:V
```

正常输出示例：

```
HeliumNtpHook: module loaded
HeliumNtpHook: loaded into io.github.jqssun.helium, custom URL = chrome-native://bookmarks/folder/10397
HeliumNtpHook: hooked Activity.onCreate, waiting for Chromium classloader...
HeliumNtpHook: ✓ LoadUrlParams loaded via org.chromium.chrome.browser.ChromeTabbedActivity (cl=dalvik.system.PathClassLoader)
HeliumNtpHook: ✓ Hooked LoadUrlParams(int, String) constructor
HeliumNtpHook: Intercepted NTP: chrome-native://newtab/ -> chrome-native://bookmarks/folder/10397
```

## 项目结构

```
custom-ntp-lsp/
├── build.gradle                    # AGP 8.5.2 / Kotlin 1.9.24
├── settings.gradle                 # 腾讯云镜像 + google() + mavenCentral() + xposed.info
├── gradle.properties               # JVM 参数、UTF-8、AndroidX
├── local.properties                # ★ sdk.dir，每人自建，不进 git
├── gradlew.bat                     # Gradle 8.9 包装器（已自带）
├── gradle/wrapper/
└── app/
    ├── build.gradle                # libxposed:api:102 compileOnly + AndroidX
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml     # ConfigActivity 声明
        ├── java/com/example/customntp/
        │   ├── Main.kt             # 核心 Hook（XposedModule 子类，API 102）
        │   └── ConfigActivity.kt   # 配置 UI（写 SharedPreferences）
        ├── res/                    # 布局、字符串、图标
        └── resources/META-INF/xposed/   # libxposed 模块声明
            ├── java_init.list      # 入口类：com.example.customntp.Main
            ├── module.prop         # minApiVersion=102
            └── scope.list          # 默认作用域：io.github.jqssun.helium
```
