# Helium NTP Hook — LSPosed Module

> **方案二：LSPosed (Xposed) Hook** — 当扩展方案不生效或需要 Java/Native 层底层拦截时使用

## 原理

Helium / Titanium Browser (`io.github.jqssun.helium`) 基于 Chromium，导航最终经过 `TabImpl.loadUrl(LoadUrlParams)`。Hook 此方法，检测 URL 是否为 NTP（`chrome://newtab` / `chrome-native://newtab`），若是则替换为用户配置的自定义 URL。

## 多级 Hook 策略

由于 `is_official_build = true`，R8 混淆可能改变类名。模块按优先级尝试：

| 策略 | Hook 点 | 说明 |
|------|---------|------|
| 1 | `org.chromium.chrome.browser.tab.TabImpl.loadUrl` | 最中心拦截点，所有 URL 加载必经 |
| 2 | `org.chromium.chrome.browser.tab.Tab.loadUrl` | 接口/基类 fallback |
| 3 | `TabCreator.createNewTab` | Tab 创建层 fallback |
| 4 | 字符串扫描 | 遍历 DEX 寻找 `loadUrl(LoadUrlParams)` 签名方法 |

> **注意**：Chromium 官方构建中，`org.chromium.*` 包名大多通过 JNI/Manifest keep 规则保留，策略 1 通常直接命中。若 Release 包混淆了这些类，策略 4 会自动接管。

## 构建

```bash
cd custom-ntp-lsp
./gradlew :app:assembleRelease
# 输出: app/build/outputs/apk/release/app-release-unsigned.apk
# 签名后安装到设备
```

### 签名（示例）

```bash
keytool -genkey -v -keystore my.keystore -alias mykey -keyalg RSA -keysize 2048 -validity 10000
apksigner sign --ks my.keystore --out app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
adb install app-release.apk
```

## 安装与启用

1. **安装 APK** 到已 root 并装有 LSPosed 的设备
2. 打开 **LSPosed Manager**
3. 在模块列表中找到 **Helium NTP Hook**，启用它
4. 在模块的 **作用域 (Scope)** 中勾选 **Helium / Titanium Browser** (`io.github.jqssun.helium`)
5. 打开本模块的配置页：
   - 开启 **启用 Hook** 开关
   - 填入 **目标 URL**（如 `https://your-custom-nav-site.com`）
   - 点击 **保存并启用**
6. **强制停止** Helium 浏览器，重新打开
7. 新建标签页 → 自动跳转到自定义 URL

## 调试日志

在 LSPosed Manager 中查看日志，或：

```bash
adb logcat -s Xposed:V LSPosed-Bridge:V | grep HeliumNtpHook
```

正常输出示例：
```
HeliumNtpHook: Loaded into io.github.jqssun.helium, custom URL = https://www.bing.com
HeliumNtpHook: ✓ Hooked org.chromium.chrome.browser.tab.TabImpl.loadUrl
HeliumNtpHook: Intercepted NTP: chrome://newtab/ -> https://www.bing.com
```

## 应对混淆

如果日志显示 `TabImpl not found`，说明 R8 混淆了类名。此时：

1. 用 **JADX-GUI** 反编译 `io.github.jqssun.helium` APK
2. 搜索字符串 `chrome-native://newtab/` 或 `chrome://newtab`
3. 找到引用该字符串的类，确认其 `loadUrl` 方法名
4. 模块的策略 4（字符串扫描）会自动尝试定位，通常无需手动修改

如需手动指定混淆后的类名，修改 `MainHook.kt` 中的 `hookLoadUrl()` 调用参数即可。

## 项目结构

```
custom-ntp-lsp/
├── build.gradle                    # 项目级
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/
├── app/
│   ├── build.gradle                # Xposed API compileOnly + AndroidX
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml     # xposedmodule meta-data + scope
│       ├── assets/xposed_init      # 入口类全限定名
│       ├── java/top/gtian/heliumcustom/
│       │   ├── Main.kt         # 核心 Hook 逻辑（4 级策略）
│       │   └── ConfigActivity.kt   # 配置 UI
│       └── res/
│           ├── layout/activity_config.xml
│           ├── values/strings.xml
│           └── xml/xposed_scope.xml
```
