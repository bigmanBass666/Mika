---
name: android9-backport
description: Use when 让 App 跑到更低 Android 版本：定位缺失 API 与反射回退.
version: 1.0.0
author: Hermes Agent
license: MIT
platforms: [windows, linux, macos]
metadata:
  hermes:
    tags: [android, backport, minsdk, hidden-api, app_process, 老设备适配]
    related_skills: [android-device-diagnostics, artemis-android-automation]
---

# 把新版 Android App 降级适配到老系统

上游声明 `minSdk 30/31` 的 App，未必**真的**需要那些 API——多数是「只用新写法、没写旧分支」。本技能是把这类 App 拉低到 Android 9（SDK 28）这一档的可复现流程。实战案例：Mika 0.1.31 → vivo Z3（Android 9, arm64），13 处差异全部修通，helper 以 shell 身份常驻。

## When to Use

- 用户想让某个「只支持 Android 11+/12+」的 App 跑在自己的老设备上
- 已确认「装不上」是 minSdk 问题（见 `android-device-diagnostics`），下一步要判断是改源码、刷机还是换机
- 要给一个 fork 补低版本分支，且必须逐条说明改动理由

## 铁律

- **先算账再动手**。三条路的成本量级完全不同：刷机（多数国产 ROM 官方不解锁 bootloader = 死路）> 改源码适配（可控）> 换机（唯一根治）。不要把「支持 Project Treble」当成「能刷机」。
- **minSdk 是作者的声明，不是 API 依赖清单**。先用静态扫描把「无守卫调用」和「已有 `if (SDK_INT >= x)` 的锦上添花」分开，往往只有 2~3 处是硬墙。
- **静态扫描只覆盖方法调用**。Kotlin 的属性赋值 `params.fitInsetsTypes = 0` 编译成 `setFitInsetsTypes()`，`grep "setXxx("` 抓不到，但它照样 `NoSuchMethodError`。必须单独扫 `.apply { }` 块里的属性赋值。
- **`Log` 和 stderr 在 `app_process` 环境里可能完全不可见**（真机上实测：所有 buffer 都空）。诊断日志一律写文件：`/data/local/tmp/xxx.txt`。
- **异常不是错误就吞掉**。上游代码常写 `catch (t: Throwable) { Log.w(...); return Failed }` —— 日志看不见时，你只会看到「静默退出」。先把每个 catch 分支的日志补成文件写入，再谈排查。
- **不要 `git add -A`**。工具链（`.toolchain/`，1GB+）、构建日志、调试脚本会一起进仓库。用 `.git/info/exclude` 本地忽略，只 add 源码路径。

## 流程

### 1. 建立基线（只读）

```bash
adb shell getprop ro.build.version.sdk          # 28 = Android 9
adb shell getprop ro.product.cpu.abi            # arm64-v8a 等
adb shell dumpsys package > pkgs.txt            # 全量已装包的 minSdk/targetSdk 分布
```

结论要能量化：「已装 366 个包里 minSdk 越过 28 的有 0 个，但恰好 = 28 的有 111 个（红线组）」。

### 2. 静态扫描：找缺失 API

SDK 自带的 `platforms/android-XX/data/api-versions.xml` 是权威来源（比记忆可靠）：

```python
# 收集 since >= 目标级别 的方法名（只保留所有重载都越线的方法，减少误报）
for ev, el in ET.iterparse(xml, events=("end",)):
    if el.tag == "method":
        since = int(el.get("since", "1")); by_name[el.get("name")].add(since)
```

然后与源码里的 `\\.(\\w+)\\s*\\(` 求交集，按方法名聚合。

**必须手工过滤误报**：`isBlank`/`repeat`/`takeWhile` 是 Kotlin 标准库，`newCall` 是 OkHttp —— 这些名字在 Android 里也存在，但和你的代码无关。

### 3. 补扫属性赋值（最容易漏的一类）

```bash
# 已知高危 setter（API 29/30/31 新增）
grep -rn "fitInsetsTypes\|layoutInDisplayCutoutMode\|isUserSetImportance" app/src/main
```

Kotlin 里 `x.apply { prop = v }` 等价于 `x.setProp(v)`。命中就加版本守卫。

### 4. 真机迭代（核心循环）

```bash
adb logcat -c && adb shell am start -n <pkg>/<activity> && sleep 8
adb logcat -d | grep -A 25 "FATAL EXCEPTION"
```

栈顶 `NoSuchMethodError: No virtual method xxx()V in class Yyy` 直接告诉你要守卫哪个 API。修完重编：改的是 `app` 模块约 50 秒，改 `core` 模块约 2 分钟（增量构建）。

**每轮只改一处**，否则新崩溃你分不清是哪个改动引入的。

### 5. 自建 shell 权限（App 需要执行系统命令时）

这类 App 常靠「无线调试配对」自举（Android 11+），老系统上没有这条路。两条替代：

| 方案 | 前提 | 代价 |
|---|---|---|
| USB 调试 + 电脑跑一次脚本（`adb shell sh .../usb-start.sh`） | App 自带该机制 | 每次重启手机要重跑 |
| `adb tcpip 5555` + App 连 localhost | 需要手动开启 | 重启失效，且要处理 adb RSA 授权 |

脚本自举的三个硬坑（Android 9 实测）：

```sh
# 坑 1：app_process 以 shell 身份运行时改不了 /data/dalvik-cache 所有权 → Aborted
#   Error changing dalvik-cache ownership : Permission denied
#   解法：把 ANDROID_DATA 指到 shell 可写目录，走 imageless 模式
if [ "$(getprop ro.build.version.sdk)" -lt 29 ]; then export ANDROID_DATA=/data/local/tmp; fi
CLASSPATH=<base.apk> setsid app_process /system/bin <MainClass> ...
```

```kotlin
// 坑 2：getContentProviderExternal 在 API 30 才多了 tag 参数
runCatching { am.getMethod(4参).invoke(..., TAG) }
    .recoverCatching { am.getMethod(3参).invoke(...) }

// 坑 3：注意拿到的是 IContentProvider 代理，不是 ContentProvider 实例！
//   IContentProvider.call(callingPkg, method, arg, extras) = 4 参
//   ContentProvider.call(method, arg, extras)              = 3 参（这里用不上）
provider.javaClass.methods.first {
    it.name == "call" && it.parameterTypes.size == 4 &&
        it.parameterTypes[0] == String::class.java
}.invoke(provider, callingPkg, method, null, extras)
```

判断「方法存不存在」时用**字符串比类名**（`it.parameterTypes[0].name == "android.content.AttributionSource"`），不要在低版本会执行到的 lambda 里写 `AttributionSource::class.java` —— 那本身就会 `NoClassDefFoundError`。

### 6. 让 UI 放行

App 通常有 `Prerequisites` / `MIN_SDK` 之类的闸门常量。降级时要一并放开，否则功能装好了但界面拦着不让用：

```bash
grep -rn "MIN_SDK\s*=\|sdkInt < " app/src/main core/src/main | head
```

放开后保留「能力不足」的提示（如「本机无后台独立焦点」），不要假装一切正常。

## 常见 API 差异速查（Android 9 上会崩）

| API | 引入版本 | 低版本替代 |
|---|---|---|
| `AccessibilityService.takeScreenshot` | 30 | 放弃截图，用 `AccessibilityNodeInfo` 读屏 |
| `Service.startForeground(id, n, type)` | 29 | `startForeground(id, n)` |
| `NotificationChannel.hasUserSetImportance()` | 29 | 当作未设置 |
| `WindowManager.LayoutParams.fitInsetsTypes` | 30 | 不设，默认按整屏 |
| `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` | 30 | `SHORT_EDGES`(28) |
| `Bitmap.wrapHardwareBuffer` | 29 | 不涉及则无需处理 |
| `\p{IsHan}` 正则 | Android 9 正则引擎不支持 | `[\u4E00-\u9FFF]` |
| `ActivityManager.getContentProviderExternal`(4参) | 30 | 3 参 |
| `ContentProvider.call`(5参 w/ AttributionSource) | 31 | 见坑 3 的 4 参 IContentProvider |
| `android_get_device_api_level`（native 符号） | 29 | 老设备上该 .so 无法加载，功能降级 |

注意 `\p{L}` / `\p{N}` **是**支持的（标准 Unicode 类别），只有 `\p{IsXxx}` 这种 script 别名不支持。

## 不可补的硬限制（写进 README 别骗自己）

- **后台独立焦点**：Android 14 才完整。老系统上 App 操作时会占用用户屏幕，改代码补不回来。
- **OCR**：没有截屏就没有像素级识别，只能靠无障碍节点树（看不到图片、自绘界面）。
- **native 库符号依赖**：`.so` 链了新版本 libc 符号时无法加载，只能等上游重编。
- **沙箱类功能**（proot/seccomp）：通常依赖新内核特性，老设备直接不可用。

## Pitfalls

- **同一个 API 往往有多个调用点**：修完 `windowsOn()` 里的 `windowsOnAllDisplays` 就以为完事了，结果 `displayOf()` 里还有一处，第一颗无障碍事件就把主线程崩掉。改完**用 `grep -rn <API名>` 数一遍全量出现点**，逐个确认都在守卫后面。
- **ADB 装包前必须确认屏幕是亮着且已解锁**：息屏或锁屏时 `am start` 能拉起安装器，但它停在后台、`input tap` 落空，表现是「安装没反应」。先 `svc power stayon true` + `settings put system screen_off_timeout 600000`，`dumpsys power` 看 `Display Power: state=ON`，`dumpsys window` 看 `isStatusBarKeyguard=false`。
- **`file://` 的安装包会被系统清掉**：安装成功后 `/sdcard/Download/*.apk` 可能被清理，下次 `am start` 直接提示「安装包不存在」。每次装之前先 `adb push` 一遍，别假设文件还在。
- **`api-versions.xml` 里的方法名带参数和返回签名**（`registerFrameCommitCallback(Ljava/lang/Runnable;)V`）。拿整串去匹配源码永远查不到，先 `.split("(")[0]` 再比对。
- **静态扫描的假阳性能占九成**：项目自有符号（`conversationId`）、Kotlin 标准库（`isBlank`/`repeat`/`takeWhile`）、OkHttp（`newCall`）全都会撞上同名 Android API。**以真机崩溃栈为准**，扫描只用来排优先级和兜底。
- **`catch (e: Exception)` 兜不住 `NoSuchMethodError`** —— 它是 `Error`，不是 `Exception`。要么用 `runCatching`（捕 `Throwable`），要么显式 `catch (Throwable)`。判断某处调用"已安全"之前，先看它到底被什么捕获。
- **vivo 安装器要等 UI 渲染**：`am start -a VIEW` 之后立刻 `input tap` 会点空。等 4~8 秒，再用 `uiautomator dump` 现查「继续安装」的 bounds（会变），别写死坐标。
- **Compose 的 TextField 不吃 `adb input text`**：`input text` 注入的字符会被输入法搅成乱序，而 `uiautomator dump` 又读不出自绘控件里的内容（`text` 恒为空）。验证 UI 交互只能 `screencap` + 看图。
- **装完包 helper 必掉**：任何覆盖安装都会杀掉 `:helper` 进程，phone use 随即失效，必须重跑 `usb-start.sh`。自动化脚本里要把这一步写死。
- **helper 侧 `android.app.TaskInfo` 在 Android 9 不存在**：`ShellService` 用 `Class.forName` 拿它来列任务，抛 `ClassNotFoundException` 后被 `mainWatch` 吞成一条 warning（`cannot list tasks`），不崩但「监视前台应用」功能失效。
- **浅克隆（`--depth=1`）会让 fork 推不上去**：远端有完整历史，本地只有 1 个提交 → `push rejected (fetch first)`。先 `git fetch --unshallow` 再谈 rebase。
- **上游可能在你适配期间改动同一批文件**。本案例 8 小时内上游推了 39 个提交、321 个文件，正好动了我改的全部 11 个文件 —— 这种情况下**不要强行 rebase**，先把适配推到独立分支（`android9-compat`）保住成果，rebase 单独一轮做。
- **`gradlew.bat` 反复假 modified**：项目里没有 `.gitattributes` 时行尾策略只存在于机器级全局配置，换环境就失效。解法见 `cross-agent-skill-portability` 与项目自己的 `.gitattributes`。
- **debug 签名变了，脚本里的 `certPin` 就失效**：每次重装都要让 App 重写启动脚本（脚本会带新的签名指纹）。
- **`android:foregroundServiceType` 在低版本被忽略**，不会崩；别为它加守卫。

## 装机与验收流程（vivo + adb，已实战验证）

`adb install` 一律 `Failure [-200]`，必须走 UI。**装前必须确认屏幕亮着且已解锁** —— 息屏/锁屏时安装器会被拉到后台，`input tap` 全部落空，表现就是「安装没反应」。

```bash
# 0. 亮屏 + 保持常亮 + 解锁
adb shell input keyevent KEYCODE_WAKEUP
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 600000
adb shell dumpsys power  | grep "Display Power"        # 要 state=ON
adb shell dumpsys window | grep isStatusBarKeyguard     # 要 false
adb shell input swipe 540 2100 540 900 250              # 无密码锁屏：上滑解锁

# 1. 推包（vivo 默认勾选「安装后删除安装包」，每次都要重推）
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/Mika-28-min.apk

# 2. 清掉旧安装器实例再拉起，等 9 秒（104MB 的包要解析）
adb shell am force-stop com.android.packageinstaller
adb shell am start -a android.intent.action.VIEW \
  -d "file:///sdcard/Download/Mika-28-min.apk" -t "application/vnd.android.package-archive"
sleep 9

# 3. 现查 ok_button 坐标再点（会变，别写死）
adb shell uiautomator dump /sdcard/i.xml    # 找 id/ok_button 的 bounds 中心
adb shell input tap 540 2106

# 4. 装完会弹「打开 / 完成」→ 直接点「打开」唤醒 App，别手动 am start
#    （界面偶尔会自己关掉，那就用 monkey 兜底）

# 5. 覆盖安装必杀 :helper，立刻重跑
adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh
adb shell ps -A | grep mika       # 要看到 shell 用户的 :helper
```

拔 USB 线**不会**杀 helper（`app_process` 起的独立 shell 进程），只有覆盖安装和手机重启会。

### 验收：用预设问题绕开中文输入

`adb input text` 喂不进中文，Compose 的自绘输入框连 `uiautomator dump` 都读不出内容（`text` 恒为空）。**点预设问题卡片**是唯一顺畅的路径：

```bash
adb shell monkey -p com.wangzan101.mika -c android.intent.category.LAUNCHER 1
adb shell input tap 594 1429             # 预设问题卡片（bounds 按文本从 dump 现查）
adb exec-out screencap -p > filled.png   # 确认输入框有字、右侧发送箭头变蓝
adb shell input tap 966 2180             # 点发送（键盘没弹时）
adb logcat -d | grep -A 18 "FATAL EXCEPTION"
```

**判据**：App 进程在 + `:helper` 在 + 无 `FATAL EXCEPTION`。任务报 `gaveup` / `failed` **不算崩溃** —— 那是模型判断做不下去，不是 API 缺失，看诊断 `[Recent runs]` 的 `error` vs `failed` 区分。

## 验收清单

- [ ] `aapt2 dump badging` 的 minSdk 已是目标版本
- [ ] App 冷启动无 `FATAL EXCEPTION`
- [ ] 用预设问题走完一条真实任务，执行期间无崩溃
- [ ] 自建 shell：`ps -A | grep <pkg>:helper` 显示 **shell** 用户 + 常驻
- [ ] helper 能拿到任务列表（logcat 无 `cannot list tasks` / `topTaskId failed`）
- [ ] 无障碍服务已连接（`settings get secure enabled_accessibility_services` 含目标包）
- [ ] README 写明「哪些能力在低版本上永久缺失」
