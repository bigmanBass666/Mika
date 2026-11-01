# AGENTS.md — Mika Android 9 兼容 Fork

给在本仓库里干活的 AI/协作者。**读完再动手。**

## 这个仓库是什么

上游 [`wangzan101/Mika`](https://github.com/wangzan101/Mika) 的兼容性 fork，只为一台设备服务：

| 项 | 值 |
|---|---|
| 设备 | vivo Z3（V1813BT / PD1813D） |
| 系统 | Android 9 / SDK 28，FuntouchOS 11 |
| 架构 | arm64-v8a |
| 目标 | 让 minSdk=30 的上游跑在这台机器上，**保留 phone use**（无障碍 + 自建 shell） |

**这不是通用库。** 所有改动围绕这台机器，别为"通用性"引入复杂度。

## 分支

- `main` — 上游最新，不含本 fork 的改动
- `android9-compat` — 我们的适配，**实际在用的分支**
- 适配期间上游推了 39 个提交、正好动了我们改的全部文件，所以 rebase 一直没做。**别随手 rebase**——冲突解决错了会炸掉已真机验证的修复，要合上游就单开一轮并逐项验证。

## 铁律

1. **每处兼容 hack 必须写中文注释**：这个 API 从哪个版本才有、上游为什么那么写、低版本走什么替代。半年后回来看别觉得是有病的决策。
2. **不要 `git add -A`**：`.toolchain/`（1.1G）、`build*.log`、调试脚本会被卷进来。只 add 源码路径。
3. **匹配现有风格**，不要顺手"改进"相邻代码。
4. **构建成功 ≠ 能跑**。改完必须真机验证（流程见下）。

## 构建

工具链是**项目级**的，不动全局 `JAVA_HOME` / `ANDROID_HOME`：

```bash
cd D:/Test/Mika
JAVA_HOME="D:/Test/Mika/.toolchain/jdk-17" ./gradlew :app:assembleDebug --no-daemon
```

- 首次 ~13 min，增量 45 s ~ 2 min（改 `core` 模块比改 `app` 慢一倍）
- **不要挂代理**：`repo.maven.apache.org` 的 TLS 会被本地代理掐断
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（约 100 MB）

## 安装（vivo 会拦 adb）

`adb install` / `pm install` 在这台机器上一律 `Failure [-200]`，必须走 UI：

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/Mika-28-min.apk
adb shell am start -a android.intent.action.VIEW \
  -d "file:///sdcard/Download/Mika-28-min.apk" -t "application/vnd.android.package-archive"
adb shell input tap 540 2155        # 「继续安装」按钮
```

## 装完必须重启 helper

装新包会杀掉 helper，phone use 随即失效：

```bash
adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh
adb shell ps -A | grep mika         # 应看到 shell 用户的 :helper 常驻
```

重启手机后同样要重跑一次 —— 这是 Android 9 没有无线调试的代价。

## 验证流程

```bash
adb logcat -c
adb shell am start -n com.wangzan101.mika/.ui.MainActivity
sleep 8
adb logcat -d | grep -A 25 "FATAL EXCEPTION"
```

有崩溃 → 栈顶的 `NoSuchMethodError` 就是缺失 API 的调用点 → 加版本守卫 → 重编。

## 踩过的坑（别重犯）

- Kotlin 的**属性赋值/访问**会编译成 setter/getter，`grep "setXxx("` 抓不到：`params.fitInsetsTypes = 0` 编译成 `setFitInsetsTypes()`。扫 API 时要连 `.name =` 和 `.name` 一起看。
- **尾随 lambda 也是方法调用**：`viewTreeObserver.registerFrameCommitCallback { }`。
- `Log` / `stderr` 在 `app_process` 环境下**完全不可见**，诊断信息写 `/data/local/tmp/xxx.txt`。
- 反射判断方法是否存在时用**字符串比类名**，别在低版本会执行到的 lambda 里写 `Xxx::class.java` —— 那本身就会 `NoClassDefFoundError`。
- `runCatching`（捕 `Throwable`）能兜住 `NoSuchMethodError`；`catch (e: Exception)` **兜不住**（`Error` 不是 `Exception`）。
- 静态扫描会有大量假阳性：`conversationId`、`isBlank`、`newCall` 这些项目自有符号和 Android API 同名。以**真机崩溃栈**为准。

完整方法论见 [`.hermes/skills/android9-backport/SKILL.md`](.hermes/skills/android9-backport/SKILL.md)。

## 已知永久限制（改代码补不回来）

- 截图 OCR —— `AccessibilityService.takeScreenshot` 是 API 30
- 后台独立焦点 —— API 34
- 无线调试自举 —— API 30，只能 USB 启动
- Linux 沙箱 —— native 库链了 API 29 的 libc 符号

## 行尾

`.gitattributes` 已固化：索引一律 LF，工作区 `*.bat` 为 CRLF。**别去改 `core.autocrlf`**，那会让 `gradlew.bat` 重新开始假 modified。
