# DiPlay 车机适配记录 — GAC MSM8996（Android 8.1）

记录对象：**GAC MSM8996 车机**上的 [DiPlay](https://github.com/shihabal3amri/DiPlay)（iPhone CarPlay 投屏到安卓车机）。

目标是两件事：让 DiPlay **能装在这台车上**，以及修复**插上 iPhone 点「有线连接」立即闪退**。

---

## 一、车机基本情况

### 1.1 硬件与系统

| 项目 | 值 | 来源 |
| --- | --- | --- |
| 车机标识 | `GAC MSM8996 for arm64` | DiPlay 诊断报告 |
| 主板 / 硬件 | `msm8996` / `qcom` | 诊断报告 |
| Android 版本 | **8.1.0** | `getprop ro.build.version.release` |
| API Level | **27** | `getprop ro.build.version.sdk` |
| 构建号 | `OPM1.171019.026` | 诊断报告 |
| 完整固件串 | `Android/mario/mario:8.1.0/OPM1.171019.026/11621:user/test-keys` | 诊断报告 |
| 安全补丁 | `2019-01-05` | `getprop` |
| 显示 | `0:内置屏幕 1920x720`、`1:HDMI 屏幕 1920x720` | 诊断报告 |
| DiPlay 包名 | `com.shihab.diplay`（官方 release） | `pm list packages` |
| ADB 身份 | 提示符 `root@mario`；但系统**不提供 `su`**，普通 App 拿不到 root | `adb shell` 实测 |

### 1.2 这台车的五个"特殊情况"

这是所有问题的来源，也是后续每一处优化的依据。

**① 系统版本低于官方要求，原版装不上**

官方 DiPlay 原版 `minSdk = 28`（Android 9），本机是 API 27，安装器直接拒绝（`INSTALL_FAILED_OLDER_SDK`）。
→ 这是当初网上流传的 "0.2.0 兼容版" 之所以要改 `minSdk` 的唯一原因；也是后来 0.2.15 / 0.2.16 官方把 `minSdk` 降到 25 后，本机才能装上官方包的原因。

**② VPN 的授权界面是"残缺的"**

- `pm path com.android.vpndialogs` → `/system/priv-app/VpnDialogs/VpnDialogs.apk`：**包在**；
- 但框架要求启动的 **`com.android.vpndialogs.ConfirmDialog` 这个 Activity 不存在**（被这个 ROM 裁剪掉了）。

结果：`VpnService.prepare()` 永远返回一个**指向不存在界面**的 Intent，`launch()` 永远抛
`ActivityNotFoundException`。

**③ 有线 CarPlay 强依赖 VPN，不是可选项**

DiPlay 的有线路径是「**用户态 NCM + 本机 TUN**」：

```kotlin
// CarPlayVpnService.kt
val tunFd = Builder()
    .addAddress(linkLocal, LINK_PREFIX)        // fe80::/64 链路本地
    .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
    .setMtu(TUN_MTU).setBlocking(true)
    .addAllowedApplication(packageName)        // 只接管 DiPlay 自己
    .establish()
```

这个 "VPN" **不是通往服务器的隧道**，只是把本机自己的 IPv6 链路本地包引进 `Ipv6NcmBridge`，再经 USB NCM 帧发给 iPhone。所以 VPN 授权是有线模式无法绕过的前提。

**④ 内核 tun 驱动正常**（好消息）

| 检查 | 结果 | 结论 |
| --- | --- | --- |
| `cat /proc/misc \| grep tun` | `200 tun` | ✅ tun 驱动已注册 |
| `ls /sys/module/tun` | 不存在 | 正常——tun 是**编译进内核**而非模块 |
| `ls /dev/net/tun` | 不存在 | 正常——Android 用的是 `/dev/tun` |

即：**VPN 机制本身可用，坏的只是那个授权界面**。


---

## 二、闪退的根因（崩溃堆栈）

复现有线闪退后，`logcat -d -s AndroidRuntime:E` 抓到：

```
FATAL EXCEPTION: main
Process: com.shihab.diplay, PID: 4682
java.lang.RuntimeException: Unable to start activity
  ComponentInfo{com.shihab.diplay/com.shilapi.xcertplay.CarPlayHostActivity}:
android.content.ActivityNotFoundException: Unable to find explicit activity class
  {com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog};
  have you declared this activity in your AndroidManifest.xml?
    at androidx.activity.result.ActivityResultLauncher.launch(ActivityResultLauncher.java:47)
    at com.shilapi.xcertplay.CarPlayHostActivity.requestVpnConsent(CarPlayHostActivity.kt:732)
    at com.shilapi.xcertplay.CarPlayHostActivity.requestStartupPrerequisites(CarPlayHostActivity.kt:704)
    at com.shilapi.xcertplay.CarPlayHostActivity.onCreate(CarPlayHostActivity.kt:620)
```

App 自己的日志也吻合，最后一行就是断点：

```
20:54:45.910  Host started; MFI target=本地离线; transport=wired
```

**链条**：点有线 → `CarPlayHostActivity.onCreate` → `requestStartupPrerequisites` → `requestVpnConsent` → `vpnConsent.launch(consent)` → 界面不存在 → **未捕获异常 → 进程立刻死亡**（生命周期到不了 `onStart`，屏幕上连提示都没有）。

**还有一个放大器**：`DiPlayActivity` 在启动时会按「连接时自动打开 DiPlay」自动发起上次的连接。上次是有线 → 每次都自动连有线 → 每次都崩 → 表现为「首页一闪而过就闪退，清数据才能恢复」。

---

## 三、针对这台车做了哪些优化

### 关键认知：不需要 root，需要的是 ADB

`appops set` 用的是 **shell 权限（uid 2000）**，不是 root。而
**`ACTIVATE_VPN` 一旦是 `allow`，`VpnService.prepare()` 会直接返回 `null`** —— 根本不会去拼那个不存在的组件，闪退路径被彻底跳过，有线模式随之可用。

```bash
appops set com.shihab.diplay ACTIVATE_VPN allow
appops get com.shihab.diplay ACTIVATE_VPN     # 必须显示 allow
```

这是让有线真正可用的**唯一**前提；其余优化都是围绕它做的"护栏"和"入口"。

### 第 1 轮 — 0.2.14：安卓 8 兼容移植 + 闪退守卫（源码见 `DiPlay` 工作树）

| 改动 | 位置 | 作用 |
| --- | --- | --- |
| `minSdk` 28 → **26** | `mobile` / `common` / `shared` / `automotive` | 让 Android 8 能安装；必须四个模块同改，否则 Manifest 合并直接失败 |
| `APP_PLATFORM` android-28 → **android-26** | `shared/build.gradle` **与** `shared/src/main/jni/Application.mk` | native 库按更低 API 链接，避免加载期 `UnsatisfiedLinkError` |
| `requestVpnConsent()` 加 `catch (Exception)` + `catch (LinkageError)` | `CarPlayHostActivity.kt` | **核心修复**：不再闪退，改为界面提示 |
| 抽出 `openStartupDialog()` 通用守卫 | 同上 | 一并护住定位权限、无线权限两处 `launch()` |
| `UsbPermissionSetup` 新增 `VPN` 权限项 | `UsbPermissionSetup.kt` | 把社区验证有效的 `appops set ... ACTIVATE_VPN allow` 变成 App 内置能力 |
| 新增 `CrashLogger.kt` | `common/` | 未捕获异常逐行落盘到 `diplay.log`，解决"崩了什么都看不到" |
| 7 种语言文案 + 2 个单元测试 | `res/values*`、`test/` | 提示可读、行为有回归保护 |

> 为什么选 26 而不是 24：`UsbRequest.queue(ByteBuffer)`、`requestWait(long)`、`java.time`、`java.util.Base64` 全是 API 26；降到 24 只会额外引入 desugaring 和约 20 处永不执行的降级分支。

### 第 2 轮 — 0.2.15：移植守卫 + 拿到认证资源 + 出包

- 上游 0.2.15 把 `minSdk` 降到 25，但**闪退点完全没改**，于是把第 1 轮的守卫移植过去。
- **发现一个坑**：官方 APK 里有 `assets/offline-mfi/{identity.pk8, certificate.p7b}`（连接 iPhone 必需的认证身份），而**源码里没有**。`docs/BUILD.md` 给了正规做法：把这两个文件放进外部目录，用 `DIPLAY_AUTH_ASSETS_DIR` 构建 `:mobile:assembleStandaloneDebug`。于是从官方 APK 抽取后回填，成功产出可用的独立 APK。
- 交付 `DiPlay-0.2.15-hudtest-VPNfix.apk`。

### 第 3 轮 — 0.2.16：上游自己修了守卫，改为补"缺口"

上游 0.2.16 的发布说明明确写了：

> missing VPN authorization screens no longer crash DiPlay （#517）

核对源码确认，0.2.16 的 `requestVpnConsent()` 已对 `prepare()` 和 `launch()` 同时加了
`ActivityNotFoundException` / `SecurityException` 捕获，还会记录 `operation` / `failureClass` / `component` 到诊断日志并显示 `vpn_authorization_unavailable` 提示。

**所以守卫不再需要移植**（移植反而重复）。但发现一个真实缺口：

> 它提示「请返回 DiPlay 检查连接权限」，可 `UsbPermissionSetup` 里**仍然只有 `ACCESSIBILITY / USAGE / OVERLAY` 三项，没有 VPN** —— 照它的提示去做，那个页面授予不了权限，有线依然连不上。

于是只移植这一块：新增 `VPN` 权限项（判定条件 `CarPlayVpnService.prepare() == null`）、权限页标题（8 种语言）。交付 `DiPlay-0.2.16-hudtest-ACTIVATE_VPN.apk`。

> 附带收获：0.2.16 同时修了 **"Wired CarPlay fixes for NCM receive framing and Android 8 USB reads, with smaller-read retries"（#478 / #495）**，正是这条有线路径。

### 第 4 轮 — 修复"一键授权命令无法执行" + 推送到 fork

**根因**：命令是用 **POSIX 专用**方式拼出来的——包名与服务名先加单引号，再为外层 `adb shell '...'` 做 `'\''` 转义：

```kotlin
// 旧实现
private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
fun manualCommand(pkg: String) = entries.joinToString(" && ") { "adb shell ${quote(command(it, pkg))}" }
```

这在 sh/bash/zsh 下正常，但 **Windows 命令提示符（cmd）把单引号当普通字符**，粘贴过去直接语法错误——而这恰恰是车机用户最常见的环境。

**修复**：

1. **不再给包名和服务名加引号**（包名已被正则校验，服务名由它派生），彻底消掉 `'\''`；
2. `manualCommand()` 增加 `Terminal` 参数，按终端生成两种**可直接执行**的格式：

| 格式 | 写法 | 适用终端 |
| --- | --- | --- |
| `POSIX` | `adb shell '<脚本>'`，语句间用 `;` | sh / bash / zsh、Android 终端（Termux）、**PowerShell**（单引号是字面量；PS 5.1 也没有 `&&`） |
| `WINDOWS` | `adb shell "<脚本>"`，内部 `"` 转义为 `\"`，语句间用 `&&` | **Windows 命令提示符 cmd** |

3. 权限帮助对话框**同时显示两块**，第二块标注「如果上面那条执行报错（Windows 命令提示符不支持单引号），请改用这条」。

**验证**：`UsbPermissionSetupTest` 新增两条断言（POSIX 不含 `'\''`；Windows 完全不含单引号且仍含三项授权）；18 个用例中仅剩 3 个失败，且全部是**通过 `sh -c` 执行脚本的用例在 Windows 上无法通过**（Java `ProcessBuilder` 会重新给参数加引号，MSYS sh 从而丢掉脚本里的双引号——已用独立探针复现，与本次改动无关；CI 走 Linux，正常）。


---

## 四、交付物清单

| 文件 | 说明 | SHA256 |
| --- | --- | --- |
| `DiPlay-0.2.15-hudtest-VPNfix.apk` | 0.2.15 + VPN 守卫（上游当时还没修） | `C05942A2…676C5AFA` |
| `DiPlay-0.2.16-hudtest-ACTIVATE_VPN.apk` | 0.2.16 + App 内 ACTIVATE_VPN 授权 | `4785761C…A72E98AC` |
| `DiPlay-0.2.16-hudtest-ACTIVATE_VPN-cmdfix.apk` | 上面基础上 + 命令格式修复（**最新**） | `7A3F7DF8…C78797D2` |

三个包均为 `com.shihab.diplay.hudtest`（debug 变体），与官方 `com.shihab.diplay` **并存安装、互不影响**（应用名显示为 *DiPlay HUD Test*）。

> 也可以直接用**官方 0.2.16**，只需自己跑一次 `appops set com.shihab.diplay ACTIVATE_VPN allow` —— 上面的定制包只是把这步做进了 App 内。

---

## 五、当前状态与待验证

- [x] 根因定位（有完整崩溃堆栈）
- [x] 安卓 8 可安装、可运行
- [x] VPN 闪退修复（上游 0.2.16 已含，定制包继承）
- [x] App 内可授予 `ACTIVATE_VPN`
- [x] 一键授权命令在 cmd 与 POSIX 终端均可执行
- [ ] **车上实测：授权 `ACTIVATE_VPN` 后，有线连接是否真正建立**（待回传结果）
- [ ] 无线模式已确认正常（车机热点 + iPhone）

**关于 App 内「一键授权」**：它走的是本机 adbd 的 `127.0.0.1:5555` **明文**通道（见 `LocalAdb` 类注释："the TLS variant used by Android 11+ wireless debugging is not supported"）。

- 在 **Android 8.1 车机**上这是原生支持的模式，可用；
- 在 **Android 11+**（例如安卓 16 测试机）上，系统"无线调试"用的是随机端口 + TLS + 配对码，所以 App 内一键授权用不了——需要先 `adb tcpip 5555` 打开传统明文口，或者直接手工跑那条命令。

---

## 六、如何重新编译（本机环境备注）

工具链：**JDK 25** + Android SDK（`platforms;android-37.0`、`build-tools;36.0.0`、`platform-tools`、**NDK 28.2.13676358**），Gradle 用仓库自带 wrapper（9.5.0）。

```bash
# 1) 准备认证资源（源码里没有，必须从官方 APK 提取）
#    assets/offline-mfi/identity.pk8
#    assets/offline-mfi/certificate.p7b

# 2) 构建（缺认证资源会构建失败，这是正常的保护）
DIPLAY_AUTH_ASSETS_DIR=/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

本机踩过的三个环境坑（供以后参考）：

1. **项目路径不能含中文** —— AGP 会直接拒绝（`Your project path contains non-ASCII characters`），Kotlin 编译器也会因路径转义损坏而找不到 `kotlin-stdlib`。所以构建要放在纯 ASCII 路径下（本次用的是 `C:\Users\Public\diplay-build\`）。
2. **`~/.gradle/gradle.properties` 里有失效代理** `127.0.0.1:11371`（端口早已没人监听），会让所有依赖下载报 `Connection refused`。用独立 `GRADLE_USER_HOME`（`-g` 参数）规避，不动全局配置。
3. **github.com / services.gradle.org 直连会超时** —— 分别用 `127.0.0.1:7897` 代理和腾讯镜像解决（推送时用 `git -c http.proxy=...` 临时参数，未改全局 git 配置）。

---

## 七、常用命令速查

```bash
# 查 VPN 授权状态（关键）
adb shell appops get com.shihab.diplay ACTIVATE_VPN
adb shell appops set com.shihab.diplay ACTIVATE_VPN allow

# 查 tun 是否可用
adb shell cat /proc/misc | grep -i tun      # 期望：200 tun
adb shell ls -l /dev/tun

# 抓崩溃日志（复现后立刻执行）
adb logcat -d -s AndroidRuntime:E

# 打开传统 5555 网络 ADB 口（对已 USB 连接的设备）
adb tcpip 5555

# 连接车机 / 安装
adb connect <车机IP>:5555
adb install -r <apk路径>
```

---

## 八、第 5 轮 — 0.2.19 修复版：SELinux fd 策略缺陷与"宽松窗口"方案

### 8.1 新发现的真正拦路虎

`appops` / `settings` / `pm` / `dumpsys` / `content` 这些命令会把 **adb shell 的 stdin / stdout / stderr 文件描述符通过 Binder 交给 system_server**（`IBinder.shellCommand(in, out, err, …)`，或 `dump(fd)`、CursorWindow 的共享内存 fd）。本车 adbd 以 root 运行（`u:r:su:s0`），这些 fd 因此带 **su** 标签，而策略里没有 `allow system_server su:fd use`：

```
avc: denied { use } for pid=5665 comm="cmd" path="socket:[82421]" dev="sockfs"
     scontext=u:r:system_server:s0 tcontext=u:r:su:s0 tclass=fd permissive=0
```

结果：**Enforcing 下所有经 `cmd` 通道的命令必然失败** —— 报 `Failed transaction (2147483646)`、`Could not access the Package Manager`、`DeadObjectException`；而**不传 fd** 的命令（`ps` / `netstat` / `iptables` / `getprop` / `setenforce` / 文件读写）全部正常。这正是"App 内一键授权永远失败"的真正原因，也解释了为什么手工 `appops set` 同样不生效。

判定方法：`dmesg | grep -i -e avc -e denied`，在失败命令的同一时刻能看到上面这条。

### 8.2 解决：极短的宽松窗口（`AdbPermissiveGrant`）

新增 `common/src/main/java/com/shilapi/xcertplay/AdbPermissiveGrant.kt`：执行授权前先 `getenforce`，**仅当为 Enforcing** 时 `setenforce 0` → 执行 → 在 `finally` 里 `setenforce 1` 恢复（恢复是独立命令，中途中断也会执行）。授权写的是 app-op / secure setting，本身就是持久化的，所以窗口只在"缺权限"时才开一次，通常不到一秒。

接在两处：

- `VpnPreConsent.grant()` —— 有线连接启动时自动补 `ACTIVATE_VPN`（对应上游 0.2.18 的 `VPN consent over adb`，但绕开了这里的 fd 缺陷），诊断行 `VPN consent over adb access=READY granted=true`
- `UsbPermissionSetup.Operation.run()` —— App 内「一键授权」（使用情况访问 / 悬浮窗 / 无障碍）

### 8.3 构建与包名

- 基线：上游 **`v0.2.19`**；本分支取代旧的 `feature/wired-vpn-consent-and-adb-command`（其中"补 VPN 权限行""白名单 ADB key"等内容已被上游 0.2.16 / 0.2.18 覆盖）
- debug 变体**去掉 `.hudtest` 后缀**，保留官方包名 `com.shihab.diplay` —— 这样一套命令对官方后续版本同样适用
- 应用名改为 `DiPlay 修复版`（USB 自动确认用的是同一个标签，匹配不受影响）
- 认证资源从官方 APK 提取，与官方**字节一致**（`identity.pk8` / `certificate.p7b` 的 SHA256 已逐字节比对）

### 8.4 当前状态

| 项目 | 状态 |
| --- | --- |
| App 内一键授权（3 项） | ✅ 全部成功 —— 宽松窗口方案生效 |
| USB 设备弹窗自动确认 | ✅ 正常 |
| VPN 授权 | ✅ 自动完成（无需手工 `appops`） |
| 有线连接 | ⚠️ **仍卡在「连接中断，正在重试」** |

有线仍未通的现象与已排除项：

- iPhone 侧**完全没有弹窗**，iOS「设置 → CarPlay」里也**查不到任何车** → iPhone **从未进入有线 CarPlay 模式**，失败点在 USB 模式切换 / iAP2 识别这一段，而不是授权、供电或认证
- 已排除：供电档位（1.5 A / 0.5 A 都试过）、数据线与 USB 口、VPN 授权、认证资源（与官方一致）
- 待查方向：① 车机自带的 USB / 手机互联服务抢占 iPhone 接口；② Android 拒绝切换 USB 配置（内核驱动 detach 后又被重新绑定）；③ iOS 18 拒绝 iAP2 识别
- 需要的证据：诊断报告里的 `wired iPhone discovered vid=… pid=…`、`action=request-transition`、`USB configuration select target=6 ok=… detached=… after=…` 与异常链；或车机 `dmesg` 在插线瞬间的 USB 事件

---

*本文档随适配过程更新；最后更新：2026-10-14*
