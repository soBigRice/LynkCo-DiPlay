# LynkCoCarPlay 实现入口

整合基线：DiPlay 0.2.12 `2fc876e578eba3905a5b873e3c2dbd74f498433a`；领克旧适配快照 `6d8486a`（test13a）。test14 整合、test15 双入口和 test16 音源交接/诊断和 test17 资源生命周期见适配文档末尾。
核对日期：2026-10-06。test14 已整合上游 0.2.12 并同步 fork/main；test15 已实现首页无线/有线双入口和配对复用；test16 根据 10 月 5 日实车日志处理有线蓝牙媒体交接、统一领克音频焦点，并补齐实际输出/呈现/热点诊断。具体验证边界见适配文档末尾。保留领克配色、设置、环境检测、署名、自动临时热点及 USB/音频适配。2026-10-06 用户确认 test17 有线可以正常使用、流畅度尚可；无线仍待实车验证。模拟器无法证明真实 CarPlay 认证、无线投屏或行车稳定性。

- [领克适配、构建与实车验收](docs/LYNK_OS_N.md)：2023 款领克 03，用户提供的 OS N 2.0 / Android 9，iPhone 14 Pro / iOS 27。
- 当前实现和边界见适配文档末尾「test17」：用户确认后已统一 runtime/stream 所有权、异步关闭屏障、失效音轨恢复、进程热点 pending 和系统 VPN 回调。用户已确认有线使用和流畅度；无线仍待验证，自动验证结果及验收范围以文末记录为准。
- `mobile/build.gradle.kts`：普通 Android 车机 APK；`lynkDebug` 独立包名与资源覆盖。不使用面向 Android Automotive OS 的 `automotive` target。
- `DiPlayActivity.connectionHome/about`：首页和关于页注明基于 DiPlay 修改，保留上游项目链接及许可证声明；后续视觉调整不得移除署名。
- `common/.../DiPlayActivity.kt`：首页、热点配置、配对设备选择、可选车机蓝牙地址补充（留空恢复自动）、诊断导出；`CarPlayHostActivity.kt`：画面、Surface、状态订阅和用户命令；`CarPlaySessionPlan`：不可变连接输入；`CarPlayBackgroundSession`：会话、日志、重连、关闭及前台服务 epoch。
- `common/.../LynkPanelStyle.kt`：领克面板共用的深灰/冰蓝、文字和按钮样式，普通 target 继续原主题；`DiPlayActivity.renderLynkPanel` 提供首页双入口和二级设置；首页宽屏并列、窄屏纵排，设置按 `LynkSettingsGroup` 筛选既有 section，保留原保存回调。
- `CarPlayHostActivity.buildLynkConnectionPanel`：只改变连接提示面板；TextureView、触摸层、状态更新引用和真实画面显隐条件保持，普通版使用 `buildContentView` 内的上游自适应准备面板。
- `mobile/.../LynkPanelNavigationTest.kt`：API28 首页仅无线/有线两张卡片和设置按钮；全部设置分类、检测和日志可达，浏览不改变画质/音频设置，有线入口、类别重建恢复及连接面板窗口缩小时复用原 View 并重新测量。
- `DiPlayActivity.prepareLynkWireless/resumeWirelessPreparation` → `DiPlayBluetooth.preferredPhone`：已保存且仍配对的地址优先，无保存时只自动选择唯一名称含 iPhone 的设备；多部/改名未选择的设备由用户选一次。蓝牙关闭先请求开启，准备页监听真实状态并自动继续；离开/取消清除待连接意图。广播只在 Activity 可见期间注册；同类型后台会话先恢复，不将 2.4GHz 交接后的蓝牙关闭当作解除配对。`LynkRememberedConnectionTest` 覆盖这些分支。
- `common/.../CarPlaySettingsButton.kt`：领克投屏页右上角可拖动设置按钮，点击经 `openSettingsMenu` 打开设置；`DiPlayActivity` 的「返回 CarPlay」复用后台会话。拖动/取消不透传触摸或触发点击，三指入口保留。
- `common/.../CarPlayMediaKeys.kt` → `CarPlayMediaButton.forKeyCode`：领克 PLAY/PAUSE 保持各自语义；BYD 才应用硬件 toggle 规则。控制器记录脱敏命令来源，AirPlay 记录 press/release 写出结果，iAP2 记录播放布尔变化；写出不等于手机执行。
- `common/.../ConnectionGuide.kt`：领克版按 `CarPlayStatus` 显示中文/英文连接阶段和失败后的操作，不再解析翻译后的提示。`config_simple_connection_flow` 只在领克包启用。
- `common/.../ConnectionDiagnosticReport.kt`、`ConnectionEnvironmentSnapshot.kt`：一次汇总 USB/无线各自历史、阶段耗时、环境能力；`DiagnosticExportStore.kt`：API 28 本地报告与限定 FileProvider 分享，API 29+ Downloads。
- `common/.../CarPlayEnvironmentCheck.kt`：只读采集当前系统、ABI 组件、解码器、本地认证、权限、USB 与无线前置条件；`evaluate` 独立输出已满足/需处理/待验证，按应用、有线、无线分组。不调用 `VpnService.prepare`，不申请权限、不开关无线、不 claim USB，未知 OEM 返回值不能通过。
- `common/.../EnvironmentCheckPanel.kt`：检测结果和修复入口；`DiPlayActivity.checkEnvironment/resolveEnvironmentIssue` 负责后台读取、返回页面刷新与用户明确点击后的导航。`ConnectionDiagnosticReport` 在同一份有线/无线导出中附加检测结果。
- `AirPlayPersistence.peekWirelessHotspotMode`：只读解析，与现有 `loadWirelessHotspotMode` 共用模式规则；迁移仍仅由原启动流程负责。`EnvironmentCheckReadOnlyTest`、`CarPlayEnvironmentCheckTest` 和 `LynkAutomaticHotspotTest` 保护 VPN、配置不变、未知结果和蓝牙交接边界。
- `common/.../ConnectionCrashLog.kt`：连接页初始化时安装一次，Java/Kotlin 未捕获异常在交还系统处理前同步保存最后一份脱敏堆栈；跨进程重开后汇入同一报告。无法捕捉 native crash、系统杀进程或安装前的崩溃。
- `shared/.../transport/Iap2HandshakeDeadline.kt`：仅首次协商的预算；真实会话激活后解除，与长时间控制会话分开。
- `common/.../DiPlaySessionService.kt`：连接前台服务；`BootReceiver.kt`：已开启启动选项后的开机入口。
- `shared/.../network/LynkLocalHotspot.kt` / `LocalOnlyHotspotManager.kt`：领克 API28 自动创建临时热点、真实频段/信道校验；`LocalHotspotBluetooth.kt`：2.4 GHz Wi-Fi 交接后关闭蓝牙、结束/取消恢复及跨进程恢复标记。test12 实现和验证边界见适配文档。
- `shared/.../orchestration/CarPlayController.kt`：USB / 蓝牙 iAP2、热点与 AirPlay 调度；`network/ManualHotspotManager.kt` → `ManualHotspotReadiness`：系统拥有的车机热点，合并稳定采样和发布前校验，领克仍使用 `ManualHotspotInterfacePolicy` 排除上联网卡/STA 别名并拒绝歧义。
- `CarPlayBackgroundSession.beginClose` → `CarPlayController.close/whenClosed` → `AndroidMediaSink.whenTerminated`：Controller 控制/隧道 executor 与媒体 native 资源实际结束后才安装下一 plan；提示超时不转移所有权。`CarPlayRuntimeLifecycleTest` / `ControllerCloseCompletionTest` 保护窗口重建、服务代号、迟到资源、取消和释放失败。
- `shared/.../network/CarPlayVpnService.tunnelBuilder`：有线 VPN 只路由 `fe80::/64`，通过 `addAllowedApplication(packageName)` 限于本应用，并用 `allowFamily(AF_INET)` 放行车机正常 IPv4，不给 iPhone 提供 Internet/NAT。`CarPlayVpnRoutingTest` 保护路由契约；报告网络快照明确标为 head-unit。
- `shared/.../network/CarPlayVpnService.kt`：accept 后在 attachment 锁内核验 generation 与监听 socket，防止旧监听连接混入新会话；`CarPlayVpnGenerationTest` 用本地 socket 回放。
- `shared/.../transport/IphoneUsbConfiguration.kt`：领克版在认领接口前读回活动配置；`IphoneUsbHost` 将实际配置传给 NCM，第二连接只核验、不重新配置设备。`IphoneUsbConfigurationTest` 回放 API 28 配置失败与清理边界。
- `shared/.../transport/UsbConfigurationAccess.kt`、`shared/src/main/jni/usb_configuration.c`：借用 Android 已授权 fd 保留 errno；领克版 EBUSY 时由 `UsbDriverRecovery.kt` 核验并临时释放该 iPhone 的音频/HID 占用，USBMUX/NCM 最后关闭后尝试恢复，未知占用立即停止。`UsbDriverRecoveryTest` 覆盖恢复与竞争边界。
- `shared/.../transport/Iap2WirelessControlClient.kt`：认证和订阅后处理 `5702→5703`、`4300→4301`；false/unknown 可用性仅作诊断，不作为启动或用户授权门槛。`Iap2WirelessHandshakeReplayTest` 通过真实 Link/CSM/认证客户端回放这一分支。
- `shared/.../transport/WirelessBluetoothIdentity.kt`：自动系统地址优先，其次可选手填补充，最后稳定配置回退；同一解析结果用于 iAP2/AirPlay。诊断只导出来源和读取结果。
- `shared/.../network/CarPlayBonjour.kt`：热点接口公告/发现与网络邀请；邀请头使用 48 位接收端 MAC 数值的十进制形式，TXT 保持 MAC 原表示。发现/邀请/后台异常经 `connectionDiagnostic` 独立保存，不受菜单影响；启动返回不等于公告送达。`CarPlayBonjourTest` 使用独立参考向量。
- `shared/.../media/AndroidMediaSink.kt`：`AudioRenderer.createTrack` 在 API 28 保留实际创建音轨的属性，API 29+ 才读取 `AudioTrack.getAudioAttributes()`；旧流类型失败回退时同步音频焦点属性。`AndroidAudioStartupTest` 覆盖 API 28/29 的默认、旧流类型和回退。
- `AndroidMediaSink.AudioRenderer.configureCodec` → `MediaCodecStartup` / `media/OpusEncoder.kt`：配置或启动失败时释放已创建的 MediaCodec，避免多次尝试泄漏；`AudioCodecLifecycleTest` 注入两种故障，保护释放次数。
- `shared/.../network/WirelessHostAddress.kt`：领克手动热点优先 IPv4、无可用 IPv4 时保留 scoped IPv6；上游默认 IPv6 优先。接收端、Bonjour 和 iAP2 启动地址使用同一选择结果。
- `shared/.../hud/BydOutputSettings.kt`：比亚迪接口总开关；领克 APK 的资源关闭该接口，保持通用媒体会话、USB、热点与定位链路。
- 测试入口：`mobile/src/testLynkDebug/.../LynkOsNProfileTest.kt`、`shared/src/test/.../ManualHotspotInterfacePolicyTest.kt`，以及上游 `common` / `shared` 回归集。

上述省略路径的源码前缀为 `src/main/java/com/shilapi/xcertplay/`；测试前缀为 `java/com/shilapi/xcertplay/`。

- `shared/.../network/WiredBluetoothMediaHandoff.kt`：仅 Lynk/API28 有线当前 session 的 `disableBluetooth` 命令触发，匹配命令指明且已连接的 A2DP Sink 目标；不根据无线保存地址猜目标、不关闭总开关/HFP、不改配对或优先级。串行工作线程、最终 owner 检查与请求同步、controller 关闭前立即 cancel；结束只尝试恢复本会话断开的设备，已有别的媒体设备则不抢占。测试 `BluetoothMediaLeaseTest` / `WiredBluetoothMediaHandoffTest`。
- `AndroidMediaSink.AudioFocusCoordinator` / `CarPlayMediaKeys.attach(resumeFocus)`：领克仅由 sink 请求焦点，媒体键保留 MediaSession；焦点回调带请求代号，旧 LOSS 不能静音新请求。导航单独播放取临时焦点，和音乐同时播放不重抢；手机再次开始播放可恢复失去的焦点。`LynkAudioFocusTest` 保护普通版及显式禁用设置。
- `AudioOutputDiagnostics` / `PcmSignalStats`：记录音量、静音、BUS 输出、A2DP 状态和 PCM 非零数/峰值，不保留声音内容；通过 `AsyncDiagnosticLog` 有界异步写入。`HotspotDiagnostics` 保存启动前/失败/导出时的 AP 原始来源和网卡拓扑，开关 true 不再等于领克热点可用。
- `VideoStats`：`released` 表示释放解码输出、`presented` 来自 `MediaCodec.OnFrameRenderedListener`，附队列等待/解码到呈现时间。`TouchLatencyProbe` 只统计两秒内下一帧代理值，不代表触摸响应内容；`WindowFrameDiagnostics` 在 Activity start/stop 间记录窗口耗时并释放线程。不能用这些计数宣称用户已验证流畅。

- `CarPlayMediaEngine` / `MediaStreamOwner`：session + SETUP generation 贯穿音视频回调和清理；`Android9MediaOwnershipTest` 保护无关会话和旧流隔离。
- `AndroidMediaSink` / `MediaResourceScope`：包括退役 worker 的关闭屏障、同类输出串行移交、AudioTrack 单次失效恢复；`AudioOutputLifecycleTest` / `VideoOutputLifecycleTest` 注入 native 写入/释放延迟。
- `LocalHotspotRequestBroker`：进程 pending/reservation ticket；`CarHotspotStatus.readTethering` 区分共享与本地热点；`WirelessTunnelOwner` 固定认证上下文和发布代号。上述入口的失败限制见 test17 文档。
- `HeadUnitProfile`：明确 Lynk 平台资源选取音频/蓝牙行为，不再复用 UI 或热点开关。

- `mobile/src/lynkDebug/res/raw/ic_car_home.png`：test18 领克返回车机图标，覆盖 common 默认 BYD；Host → AirPlayConfig → `/info` 的 `oemIcons.imageData`，自定义图标/名称优先级不变。素材来源与验证见适配文档 test18。
