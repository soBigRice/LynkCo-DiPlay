# 领克 OS N 2.0 测试版

核对日期：2026-10-05；当前整合上游为 0.2.12 `2fc876e578eba3905a5b873e3c2dbd74f498433a`，
本地提交基线 `6b2ef17`，test15～test17 改动按用户要求整理为本地提交。下方 test2～test16 是历史记录，
当前专项审查和 test17 实施情况见文末，不能将旧版实现说明当作当前验收结论。
目标：2023 款领克 03，OS N 2.0 / Android 9（API 28），iPhone 14 Pro / iOS 27。
车型与手机版本来自用户；2026-10-03 实车导出确认 API 28、Qualcomm CS11、固件
`23.01.10.30531.54761`、热点候选 `wlan0` 与 Apple USB 设备，见下方 test4 证据。

## 已确认范围

用户已确认独立包名、隔离比亚迪接口、保留 USB/无线热点和画质；允许使用上游官方 0.2.10 APK
打包的公开实验认证素材生成本地实车测试包。它不是新发放的 MFi 身份，未来 iOS 接受情况未获保证。
素材、上游 APK 和本地签名均不进入 Git；无需账号或远程认证服务。

`mobile:lynkDebug` 包名为 `com.shihab.diplay.lynk`，名称 `DiPlay · LYNK OS N`，可与上游并存。
同一时刻只启动一款投屏软件。之后覆盖安装同包名、同签名版本保留设置；不要卸载清数据。

## 实现和不变项

| 入口/行为 | 领克版 | 普通上游版 |
| --- | --- | --- |
| `mobile/build.gradle.kts` | 新增 `lynkDebug`，使用 debug 签名 | 原任务和包名保持 |
| `config_byd_integration` | false：不初始化/发送 BYD HUD、仪表、ADB 电池/车速/挡位/歌曲接口 | true：原有开关和行为保持 |
| `config_manual_hotspot_strict_interface` | true：排除全部注册上游和已连接 STA 地址，拒绝未知或歧义接口 | false：保持原网卡选择策略 |
| `config_manual_hotspot_prefer_ipv4` | true：已选热点内优先 IPv4；保留 scoped IPv6 回退 | false：原 IPv6 优先 |
| `config_verified_usb_configuration` | true：读取实际配置、必要时切换并读回，NCM 使用同一配置 | false：原配置选择流程 |
| 无线启动 | test7：认证后 `4300→4301`，false/unknown 仅诊断；蓝牙身份自动选择，手填可选 | 同样响应启动，保留原页面 |
| 网络邀请 | test7：receiver Device ID 按 48 位 MAC 转十进制，保留原 Bonjour TXT | 共用该协议纠正 |
| 音轨属性 | test8：API 28 使用实际创建属性，避免调用 API 29 getter；旧流失败回退同步属性 | 共用兼容修复，不改变路由/格式 |
| `config_oem_label` | 新安装默认 `LYNK & CO`；保留用户保存的标签 | 默认 `BYD` |
| `config_simple_connection_flow` | true：无线/有线双入口，复用已有配对、实际阶段与失败操作；test16 还用于音频策略，职责耦合待修正 | false：保持原页面 |
| Android 9 无线 | 默认临时热点（LOCAL_ONLY_HOTSPOT），可选系统车机热点（MANUAL）；不开放 Android 10+ 的 Wi-Fi Direct | 保留上游现有迁移规则 |
| 视频/音频/MFi 素材与算法 | 不降低分辨率、帧率或音质，保留已授权的实验认证素材与算法 | 保持 |

通用 Android 媒体元数据、音乐、电话/Siri、主屏导航、可选标准 GPS 报告保持。
没有领克仪表/HUD/车辆数据接口资料，因此此版本仅适配中控 CarPlay；不把 BYD API 伪装成领克 API。

连接链：`DiPlayActivity.connect` → `CarPlayHostActivity.startCarPlay` → 前台服务 + `CarPlayController.start`。
USB 走上游 USBMUX/NCM 和本地 VPN；无线先通过蓝牙 RFCOMM/iAP2 交换车机热点信息，
再在实际热点接口发现/建立 AirPlay。iPhone 个人热点不是此模式的接收端。

`ManualHotspotManager` 继续使用 `wirelessHostAddress` 支持现有 IPv4 / scoped IPv6。
严格模式先选择唯一 `apN/softapN`，无明确 AP 时仅允许唯一 `wlanN/swlanN`；有歧义或无地址则等待，
到原有超时后失败，不发布到 Ethernet、USB 网卡、蜂窝或已有 P2P 组。诊断只记录接口名/选择结果，
不输出热点密码、STA IP 或协议载荷。未识别的 OEM 接口需用实车证据补充，不能盲猜。

会话生命周期沿用上游：切应用的 `onStop` 保留 controller；断线按分代和退避重建。
通知 Disconnect / 清除最近任务会主动停止。`START_NOT_STICKY`、静态会话无法跨进程被杀恢复；
没有单改 STICKY 或增加无限重连/延时。开机启动是否获 OS N 允许仍需实车核实。

## test2：简化连接与可观察状态

用户反馈：iPhone 已连接车机热点、蓝牙也已连接，但没有反应，无法判断操作是否正确。
这是实车反馈，尚无实车日志，不能把以下代码缺陷直接认定为该次故障的根因。

- 首页直接点「连接 iPhone」。缺热点信息时当场填写，保存后继续；信息和手机选择持久化。
  一个名称包含 iPhone 的配对设备可自动选择，多部时要求选择，避免误连其他设备。
  连接前检查所选手机是否仍配对、公开 BluetoothAdapter 是否可用。
- `ConnectionGuide.state` 直接消费 `CarPlayStatus`：检查热点 → 连接蓝牙通道 → 协商 CarPlay。
  `MfiReady` 仅代表本地认证素材就绪，`RunningWireless` 不代表手机已通过认证；只有实际会话/画面
  回调作为相应连接证据。原 `friendlyStage` 用英文匹配已翻译文案，中文会退化为泛化提示。
- 连接页显示等待秒数，30 秒无画面出现重试入口；这是提示阈值，不冒充协议超时。
  热点、权限、本地认证配置等需人工处理的错误停留显示，提供设置和脱敏失败详情。
  可恢复的蓝牙/传输失败继续使用原退避重连。「取消并返回」释放会话；普通切后台仍保留连接。
- 无线 iAP2 的 `TIMED_OUT` 在既无激活标志、又无已渲染画面时，改为异常进入既有关闭和失败重连
  路径，避免仅报 `ControlEnded` 后停住；已激活/有画面的连接保留。控制循环原有超时参数未缩短。
- `ConnectionRetryScheduler` 统一取消旧的重连任务：手动重试、关闭和连接成功时取消旧任务，
  避免旧 generation 的定时器阻塞新一轮失败后的恢复；`ConnectionRetrySchedulerTest` 验证该时序。
  USB 设备授权拒绝单独提示重试并接受车机 USB 弹窗，不引导到 Android 位置权限设置。

### test2–test11 的自动热点限制（test12 已修正判断）

当时依据 AOSP Android 9 的 2.4 GHz 默认配置及 DiPlay 的 5 GHz 校验，选择系统热点。
这里的 5 GHz 是上游实现策略，不是 CarPlay 协议的绝对限制；不能将它推广为“Android 9
不能自动创建 CarPlay 热点”。2026-10-04 对照 Apple 官方材料后修正：2.4 GHz 需要蓝牙
共存处理，用户已确认这一影响。新的限定实现、可恢复范围和验证见 test12。

接管检查：首次配对 → 保存热点 → 三阶段进度 → 失败提示/重试/取消；重点用实车日志确认
Android BluetoothAdapter 是否暴露车载蓝牙，以及严格网卡选择是否识别到实际 AP。
回归防线为 `ConnectionGuideTest` 的 API 28 中文状态/失败分流，原无线 handoff/proof 测试保护画面保活。

## test3：首次协商定位与一次导出有线/无线记录

用户确认无线卡在「与 iPhone 协商 CarPlay」，并要求有线也能判断状态、一次导出两种连接的记录。
尚未收到实车日志，因此没有把认证兼容性、热点或蓝牙中的任何一个认定为该次故障根因。

- `Iap2HandshakeDeadline` 在领克首次 iAP2 阶段使用总计 60 秒预算，进度事件不重置计时。
  旧无线控制窗口为 5 分钟/启用定位时 24 小时；首次等待不再继承这个长窗口。
  `IDENTIFYING` → `AUTHENTICATING` → 手机确认 AA05 后的 `WAITING_FOR_CARPLAY`，另记录热点
  信息发送、手机报告不可用、启动请求发送。超时保留最后阶段，停止自动重试，可手动重试。
  实际 AirPlay 会话已激活或已证明有画面后解除首次预算，不把它当成行车时长限制。
  普通版默认预算为 0，沿用原时序；不改证书、协议顺序、画质。此项解决等待边界与可观察性，
  **未测得实车握手提速**。有线信任弹窗原有最多 5 分钟窗口保留，明确提示手机解锁/信任。
- USB 分别显示发现设备、车机 USB 授权、切换模式、建立 USB 通道、iPhone 信任、启动服务、
  网络、识别和认证。拒绝 Lockdown 信任不再错误引导到 Android 位置权限。
  `beginReenumeration` 在领克版轮询新的完整 CarPlay 描述符，弥补 attach 广播缺失；
  60 秒仍未出现则失败，取消/旧 generation/迟到回调不能继续旧尝试。
- 首页、连接进度页均有「导出全部连接日志（有线＋无线）」。导出沿用 `DiPlayActivity` 的入口，
  不停止连接；`ConnectionDiagnosticReport.build` 收集两种历史、当前环境、显示协商、启动记录。
  `CarPlayHostActivity` 创建 `logs/connection-wireless` 或 `logs/connection-usb` 日志；每种保留
  当前及 7 段历史、每段约 512 KiB，切模式不会挤掉另一种记录。另收集请求记录和通用日志。
  未尝试/历史不存在时明确显示 0 段，不声称曾成功连接。不是无限保存所有历年记录。
- 每轮记录开始时间、连接类型、阶段总耗时/上一阶段耗时、失败类型、脱敏错误、iAP2 初始
  协议事件码与字节数。快照含型号/API/版本、USB 配置与接口、蓝牙适配器/配对状态、权限、
  位置服务、热点参数是否填写、频段/信道设置、网络接口类型/地址数量，不包含地址值或凭据。
  协议 TRACE、PHONE、密码、证书正文、私钥仍不进入报告；安全元数据单独记录，避免含
  certificate 的整行过滤把 0xAA00/AA01 是否发生也删掉。
- 导出工作线程先有限等待日志队列排空，文件注明是否排空、丢弃数、各组保留段数和结束标记。
  Android 9 直接保存到 `Android/data/com.shihab.diplay.lynk/files/Documents/DiPlay/`，
  外部目录不可取得时使用内部 reports；受限 FileProvider 仅开放这两个报告目录，分享只授予
  所选文件的读取权，不新增存储权限、不自动上传。保留「选择保存位置」可另存到系统目录/U盘。
  本地副本随卸载删除，应先分享或复制。Android 10+ 保持 Downloads/DiPlay 保存方式。
  选型核对 2026-10-03：[应用专属文件](https://developer.android.com/training/data-storage/app-specific)、
  [FileProvider](https://developer.android.com/training/secure-file-sharing/setup-sharing)。

最小实车采集：覆盖安装 test3 → 无线尝试一次并等到卡点/超时 → 取消返回 → USB 尝试一次 →
点「导出全部连接日志（有线＋无线）」→ 发回同一个 `.txt` 文件。无须分别导出或截多张图。
模拟器没有 iPhone/车机硬件，不能替代 USB/无线会话验收，也不能保证第一次报告必然证明全部根因。

回归入口：`Iap2StartupSilenceTest` 使用真实控制客户端和静默 byte stream 验证两种传输不会
等待长控制窗口，也不会在识别前调用认证；`Iap2HandshakeDeadlineTest` 保护长期会话和阶段预算；
`ConnectionGuideTest` 检查 API 28 中文阶段、信任拒绝与超时操作；
`ConnectionDiagnosticReportTest` 检查双模式历史、连续 USB 不覆盖无线、队列排空、脱敏和
API 28 本地文件/URI 读取及报告目录边界；领克 target 的 `UsbReenumerationDeadlineTest`
检查设备未重现、取消和旧 generation，实际描述符切换仍待实车。

维护经验：诊断不能仅保留当前连接方式；必须按模式保存并在同一报告中汇总，导出前排空异步队列。
状态文案应来自协议回应，MFi 素材加载、手机接受认证、收到画面是三个不同证据。
新增 USB 生命周期用例最初在 common 默认 BYD 配置运行，Robolectric 模拟 BYD service 回调产生 NPE；
已移至实际 `testLynkDebug` target，保留相同断言与完整执行，不修改或绕过产品逻辑。

## test4：按实车日志修复配置与网络路径

证据：用户提供的 `DiPlay-20261003-153449-520.txt`（test3；完整日志不入库）。

- 无线两次在 15:33:04.688 / 15:34:05.752 收到 `0xAA05`，认证阶段约 0.34 / 0.44 秒，
  随后发送热点信息和 `0x4301` 启动请求。未记录 Bonjour 发现、AirPlay 激活或画面。
  这已证明该手机当次接受了实验 MFi 认证，但没有证明完整 CarPlay 会话可用。
  旧导出未包含 TCP accept，不能据此断言手机从未建立 TCP；也没有证据把配置中的
  2.4 GHz 标签当成实测频段或断线根因。两次结束均早于首次 60 秒预算，不能误报预算超时。
- USB 15:34:40.766 起重复在 NCM control interface 3 的 claim 失败，尚未到 USBMUX/信任/MFi。
  描述符同时存在配置 5（NCM 2/3）与配置 6（NCM 3/4）。日志中的
  `configuration ready=true` 仅表示发现描述符，并非实际启用配置。旧实现忽略
  `setConfiguration` 的 false 返回，NCM 又重新按描述符偏好选择配置 6，存在错误认领风险；
  此代码缺陷已确认，实车是否正因此失败尚需新包核实。

修正与边界：

1. `IphoneUsbConfiguration.select` 在 USBMUX claim 前发送标准 GET_CONFIGURATION；
   已活动且具备完整 USBMUX/NCM 端点的配置直接复用。否则只设置一次并读回验证，
   读回短返回/失败或配置不兼容即失败并关闭连接，不再按静态目标配置继续 claim。
   `Iap2UsbSession.configuration` 将实际布局传给 `CarPlayController.openNcm`。
2. NCM 第二个 fd 只核验同一活动配置，不能再 SET_CONFIGURATION；失败时按已有所有权关闭，
   已 claim 的接口逆序释放。MAC 字符串索引按活动配置、接口号、alternate setting 限定，
   避免在其他配置同编号接口中误取。导出新增配置前后值、设置结果、control/data claim 和 alt 结果。
3. `wirelessHostAddress` 在领克已确认热点接口内优先 IPv4，没有有效 IPv4 才选 scoped IPv6。
   实际 JmDNS 3.6.3 字节码核验：绑定 IPv6 时加入 IPv6 mDNS 组，不能假定同时监听 IPv4。
   当前改动以单一地址族验证热点网络路径；AirPlay listener、Bonjour、HTTP probe 与 iAP2
   `0x4301` 地址继续保持一致。没有改证书、画质、频段或新建热点，也没有证据宣称 IPv6 必然不可用。
4. `CarPlayVpnService.acceptLoop` 将脱敏的 TCP 到达/地址族写入统一日志，区分网络不可达和
   已到接收端的协议失败。普通版保持原地址偏好与 USB 选择策略；不更换底层依赖。

核查日期 2026-10-03，依据：[libusb GET_CONFIGURATION](https://github.com/libusb/libusb/blob/master/libusb/core.c#L1727)、
[Linux 4.9 USB 配置与接口认领](https://github.com/torvalds/linux/blob/v4.9/drivers/usb/core/devio.c#L1375)、
[AOSP 9 claimInterface JNI](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/jni/android_hardware_UsbDeviceConnection.cpp#122)、
[JmDNS 绑定接口实现](https://github.com/jmdns/jmdns/blob/main/src/main/java/javax/jmdns/impl/JmDNSImpl.java)。
已有 `force=true` 仅对特定占用错误尝试 detach，不能替代活动配置核验；没有新增 root、内核驱动或盲目重试。

回归：`IphoneUsbConfigurationTest` 通过真实 API 28 host / `NcmUsbBridge.open` 调用链回放配置 5 复用、
配置 4→6、设置 false/读回不一致/短返回、NCM 配置变化、claim 失败清理及跨配置 MAC 索引；
`WirelessHostAddressTest` 保护 IPv4 偏好与 IPv6 scope 回退；`LynkOsNProfileTest` 验证实际构建开关。
test4 已收到实车结果，两种连接仍未成功，后续证据与处理见 test5。

维护经验：USB 描述符存在不等于活动配置已切换；双连接应共享已验证布局，标准控制请求必须
检查实际返回长度。诊断先区分蓝牙/MFi、热点网络、TCP 到达、AirPlay 激活与画面，不能以
泛化的“协商中”推断认证慢，也不能把缺少某项日志当作该事件从未发生。

## test5：手机可用状态、USB 底层错误与蓝牙身份来源

证据：用户提供的 `DiPlay-20261003-162748-217.txt`、`DiPlay-20261003-162932-412.txt`，
均为 test4；第二份包含第一份的历史，不重复计数。用户确认 iPhone 从未弹出允许 CarPlay 提示。

- 两次无线均完成蓝牙和 MFi，手机返回 `0xAA05 authentication-succeeded`；两次认证阶段约
  0.75 秒、0.35 秒。之后报告 `0x4e0d available=false`，旧代码收到 `0x4300` 后仍发送
  `0x4301`，再把界面覆盖为等待会话。未见 AirPlay TCP 到达，约 35 秒、33 秒时退出，
  并非触发 60 秒预算。IPv4 已实际使用但没有解决；不能再把现象归为认证慢或确定的 IPv6 故障。
- 七次独立 USB 尝试均为活动配置 2 → 请求配置 6 返回 false → 读回仍为 2；配置 2 只有
  Audio/HID，没有完整 USBMUX/NCM。test4 的核验阻止了错误认领，但当时 Android API 只返回
  false，无法确认权限、占用或设备拒绝。描述符中存在配置 6 不等于它已经活动。
- 旧日志的蓝牙选择行含 `name=`，整行被脱敏，不能据此判定实际用了真实地址还是配置回退。
  Siri/CarPlay 限制、蓝牙身份只是待验证候选；未弹授权也不足以确定手机设置有误。

本版调用链与边界：

1. `Iap2WirelessControlClient` → `WirelessCarPlayAvailability`：领克开关启用时解析已有
   `Iap2CarPlayMessages.availability`，只有 `wireless.available=true` 才发 `0x4301`。
   false 期间后续 Wi-Fi/启动进度不能覆盖 `PHONE_UNAVAILABLE`；后来 `0x4e0d=true` 会恢复
   `WAITING_FOR_CARPLAY`，但不凭这一条发送启动请求。可用状态和标识存在性经
   `CONNECTION_DIAGNOSTIC` 进入同一份导出，保留隐私过滤，普通版维持原开关默认值。
2. `CarPlayController.accessoryBluetoothMac` → `WirelessBluetoothIdentity.resolve`：优先公开
   adapter 地址，其次系统设置，最后沿用配置回退。拒绝占位/全零/全 FF；分别记录读取被拒、
   不可用、格式错误和最终来源，以及回退是否由配置 deviceId 派生。仅记录分类，不记录 MAC。
   这增加了辨别原因的证据，不能冒充已经取得真实车机蓝牙身份。
3. `IphoneUsbConfiguration.select` → `UsbConfigurationAccess.select` → JNI 单次
   `USBDEVFS_SETCONFIGURATION`：使用 Android 权限已开放的 fd，保留立即返回的 errno，
   并按原流程读回配置；若原生库无法链接，才回退一次 Android API 并标明 errno 不可得。
   失败后通过 `USBDEVFS_GETDRIVER` 只读实际配置的接口驱动，按接口号去重。借用 fd 不关闭，
   不重开、重置、claim 或 detach，不改变已有权限和接口所有权。
4. `ConnectionGuide.failure` 对配置核验失败设置 `pauseRetry=true`，状态回调取消重试定时器，
   保留手动重试/导出。手机不可用提示说明认证已完成，给出 Siri/CarPlay 限制检查和无弹窗时
   导出入口，不要求用户一直等待。

依据（核查 2026-10-03）：[Android 9 JNI 配置切换](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/jni/android_hardware_UsbDeviceConnection.cpp#161)、
[libusbhost ioctl](https://android.googlesource.com/platform/system/core/+/android-9.0.0_r1/libusbhost/usbhost.c#625)、
[Linux 4.9 SETCONFIGURATION](https://github.com/torvalds/linux/blob/v4.9/drivers/usb/core/devio.c#L1299)、
[GETDRIVER UAPI](https://github.com/torvalds/linux/blob/v4.9/include/uapi/linux/usbdevice_fs.h#L66)、
[BluetoothAdapter 地址权限](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getAddress())、
[Apple CarPlay 检查项](https://support.apple.com/en-gb/105109)。Linux 中驱动占用可导致 EBUSY，
但实车内核与 errno 尚未取得；`usbfs` 也不能识别哪个进程持有接口。若后续证明需要释放原生
USB 音频/HID 驱动，可能影响车机原功能，须单独确认；本版没有采用强制抢占。

回归：`WirelessCarPlayAvailabilityTest` 覆盖拒绝、恢复、缺无线组及上游行为；
`WirelessBluetoothIdentityTest` 覆盖来源和隐私；`IphoneUsbConfigurationTest` 覆盖 EBUSY 后不
认领、不重复 SET 和当前配置驱动查询；`ConnectionGuideTest` 覆盖停止重试和手机提示；
`ConnectionDiagnosticReportTest` 保护双模式同文件诊断与秘密排除。

test5 的无线 availability 阻挡与相关状态测试已被 test7 的完整回放替代；false 不代表用户拒绝。

维护经验：安全元数据也必须经过真实导出验证。字段名 `wirelessIdPresent` 内嵌 `ssid`，被现有
大小写不敏感秘密过滤整行删掉；首轮报告测试复现失败，改成 `radioTransportPresent` 并加直达
日志前缀，未放宽脱敏规则。收到手机 false 后不能以“已发请求”替代可用证据；恢复 true 也要
同步界面与超时归因。新增 JNI 只保留原操作错误码，不能用读取驱动名称推断具体进程。

test5 当时的采集步骤（已收到结果，见 test6）：覆盖安装 test5，保持现有热点信息；无线试一次，再切有线试一次，导出一份日志。
本版修复状态显示与重复重试，补齐当前缺失的原因；尚不能保证出画面或稳定性已经改善。

## test6：按真实驱动占用修复 USB，补齐车机蓝牙身份（历史）

蓝牙地址必填方案已由 test7 撤销，USB 修复保留。以下记录描述当时版本，不是当前操作要求。

2026-10-03 核对 test5 实车报告 `DiPlay-20261003-172820-248.txt`，只使用其中
17:27–17:28 的新尝试，不把历史段落重复计数：

- 无线两次 `AA05 authentication-succeeded`（约 0.44 / 0.32 秒），随后手机报告
  `wireless=false`。地址来源均为 `CONFIG_FALLBACK`，适配器是占位值，Secure Settings 不可读；
  尚无 AirPlay TCP 到达。真实蓝牙身份缺失已确认，但是否导致手机不可用仍待实车验证。
- USB 唯一一次尝试为配置 2 → 6，读回仍为 2，`errno=16 / EBUSY`；接口 0/1 被
  `snd-usb-audio` 占用，接口 2 被 `usbhid` 占用。证据在报告 1661–1670 行。
  这是配置切换失败的明确原因；尚未到 USBMUX、手机信任或有线认证阶段。
- 用户已批准：只临时释放该 iPhone 的音频/HID 驱动，退出尝试恢复；可能暂时中断原生
  iPhone USB 音频/控制，恢复失败需重新插线。也批准自动读取不到时一次填写真实车机蓝牙地址。

### 调研依据与选择

核查日期 2026-10-03，参考 Linux 4.9、libusb 1.0.27、AOSP android-9.0.0_r1，
以及 usbmuxd 当前维护者实现：

- [Linux devio.c](https://github.com/torvalds/linux/blob/v4.9/drivers/usb/core/devio.c)：
  SET_CONFIGURATION 遇到接口占用返回 EBUSY；DISCONNECT_CLAIM 支持按预期驱动名原子核验。
- [libusb Linux 后端](https://github.com/libusb/libusb/blob/v1.0.27/libusb/os/linux_usbfs.c) 和
  [usbmuxd usb.c](https://github.com/libimobiledevice/usbmuxd/blob/master/src/usb.c)：
  已有处理内核驱动占用后再切换配置的实现。CONNECT 返回 1 才表示新绑定，0 不代表成功匹配。
- [AOSP BluetoothManagerService](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/services/core/java/com/android/server/BluetoothManagerService.java#1344) 与
  [SettingsProvider](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java#1562)：
  真实地址读取受 LOCAL_MAC_ADDRESS 签名/特权权限限制；增加普通蓝牙/定位授权不能解决。
- [DiPlay Issue 18](https://github.com/shihabal3amri/DiPlay/issues/18) 也记录了占位地址与配置回退，
  但报告者无线可用，问题是通话覆盖/回声。因此该记录只能支持身份读取受限，不能证明本车
  无线失败的根因。上游 main `81a0767ac6eaaeec6c934d3270822bd0874a7493` 未提供手动地址补全；
  本次未升级上游。

沿用已有 JNI 与 Android USB 授权连接，增加受限 ioctl，避免为这几项操作引入整套 libusb。
不使用反射绕权限、root、卸载全局模块或绕过内核的原始 SET_CONFIGURATION 控制请求。
这些源码说明方法可行，不代表 OS N SELinux/内核一定允许，最终结果需看实车 errno。

### 实现与边界

`IphoneUsbConfiguration.select` → 只有 Apple 设备、EBUSY 且活动配置未改变，才进入
`UsbDriverRecovery.switch`。它持有单独管理连接，先核验当前配置所有接口，再只允许音频类
`snd-usb-audio` / HID 类 `usbhid` 的条件式 detach-and-claim；未知驱动或 usbfs 占用立即停止。
音频控制解绑可能同时释放流接口，因此按原始绑定记录所有接口，操作前再次核对状态。
临时 claim 全部释放后才设置目标配置，并 GET_CONFIGURATION 读回。失败不循环抢占。

`Iap2UsbSession` 与 `NcmUsbBridge` 各持有恢复引用，关闭各自数据 fd 后释放；最后引用才
尝试回到原配置并重新绑定原驱动。配对/首次握手异常也立即释放本轮 USBMUX/CSM/NCM，
不会因错误页面保留而一直占用驱动。恢复期间遇到配置被外部改变、新驱动或其他程序占用，
不会再次强制解绑；异常或恢复不完整记录 `USB restore outcome=replug-required`。
恢复驱动不能保证车机播放器自动重开或音频路由恢复，界面提示必要时重新插线。

无线地址优先级：可读的真实适配器地址 → 可读系统地址 → 本机保存的手动地址。
领克版缺少三者时停止并给出填写入口，不再使用公钥派生地址继续等待；普通版保留原回退。
`DiPlayActivity` → `AirPlayPersistence` → `CarPlayRuntimeConfig.headUnitBluetoothAddress` →
`WirelessBluetoothIdentity.resolve`，同一结果进入 iAP2 identification 和 AirPlay `btMac`。
手动输入规范化大小写/分隔符，拒绝无效、全零、广播、Android 占位值与已选 iPhone 地址。
只能校验格式，无法自动证明手填地址确实属于车机；报告只导出来源，不导出地址本身。
USB 无需填写蓝牙地址。新增资源开关只在 `lynkDebug` 开启，不更换认证素材或降低画质。

维护经验：不要把 Android 返回 false 当作泛化权限错误；先读真实 errno 和当前接口 owner。
驱动恢复必须覆盖异常和两个连接的关闭顺序；不能把 fd.close 等同于驱动恢复，也不能把
CONNECT 的非负值一律当成功。无线区分认证通过、身份可用、手机可用与画面四个阶段。
回归入口：`UsbDriverRecoveryTest`、`IphoneUsbConfigurationTest`、
`WirelessBluetoothIdentityTest`、`HeadUnitBluetoothSettingsTest`、`ConnectionDiagnosticReportTest`。

test6 当时的实车步骤（已由 test7 替代，不再要求填写）：覆盖安装 test6，保留热点设置；无线若提示，填写真实车机蓝牙地址一次；
有线插数据线尝试，结束后检查原 USB 音频/控制是否恢复。两种连接各试一次，再导出一份报告。
车机系统信息入口依赖 OEM 是否提供该页面及地址显示，模拟器不能证明 OS N 的菜单可用。

## test12：自动临时热点与蓝牙恢复

2026-10-04 用户确认免填热点信息、自动创建和检测，并确认 2.4 GHz 会话的蓝牙切换影响。
只在领克 profile 且 API 28 开启 `config_lynk_local_hotspot`；普通版及其他 Android 版本
保留原策略。原有有线连接、认证素材、视频分辨率/帧率和音频设置不变。

- `AirPlayPersistence.loadWirelessHotspotMode` 对本 profile 一次性迁移到 LOCAL_ONLY_HOTSPOT，
  不删除系统热点 SSID/密码；后续明确选择 MANUAL 会持久保留，不反复覆盖。
  `DiPlayActivity.simpleConnectionSetup` 提供自动与系统热点备用选择，自动连接跳过输入表单。
- `LocalOnlyHotspotManager.start` 使用 Android 9 原生 reservation 获得随机 SSID/密码，
  Controller 将实际返回值交给原有 iAP2 endpoint。要求位置权限、位置服务、App 前台，
  已有系统共享热点时显示冲突和解决入口。没有 root、ADB、系统签名或新增权限。
- `LocalHotspotRadioPolicy` 允许实际 2.4/5 GHz，接口仍须唯一且具备地址/BSSID；信道取驱动
  测量或系统的明确配置。Android 9 配置为0且驱动不可读时明确失败，禁止套用5GHz信道36。
  热点被系统停止时清理会话和恢复蓝牙；取消/超时的迟到 reservation 仍必须释放。
- `maybeCompleteWirelessHandoff` 仅在当前手机请求 disableBluetooth 且 Wi-Fi iAP2 隧道
  已准备好后，对本次 2.4 GHz 自动热点的 `BluetoothRadioLease` 执行关闭。旧 generation
  的命令不传递到新会话。5 GHz、系统热点、有线、菜单前后台切换不会取得关闭所有权。
- lease 在关闭前同步记录恢复标记，检查初始 STATE_ON，并等待实际 STATE_OFF；结束后等待
  实际 STATE_ON 才清除标记。与取消共用锁，已关闭 lease 拒绝迟到关闭请求。恢复失败保留
  标记并提示系统设置；下次打开 App/开始连接尝试恢复。进程被系统直接杀掉时无法立即
  执行代码，因此不承诺“崩溃后当场自动恢复”；用户也可手动开启车机蓝牙。
- `CarPlayHostActivity.shutdown` 与重连一样等待 `whenClosed` 后才完成退出/放行下一步，
  不再将4秒提示阈值当成资源释放完成。失败、取消、系统停热点、会话结束均纳入恢复路径。

调研依据：
[Android LocalOnlyHotspot](https://developer.android.com/reference/android/net/wifi/WifiManager#startLocalOnlyHotspot(android.net.wifi.WifiManager.LocalOnlyHotspotCallback,%20android.os.Handler))、
[AOSP 9 配置](https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/android-9.0.0_r1/service/java/com/android/server/wifi/WifiApConfigStore.java#269)、
[AOSP 9 启动条件](https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/android-9.0.0_r1/service/java/com/android/server/wifi/WifiServiceImpl.java#1364)、
[Apple WWDC17 717](https://developer.apple.com/videos/play/wwdc2017/717/)（5 GHz 推荐、2.4 GHz
需关闭蓝牙栈）、[BluetoothAdapter](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#disable())。
核查版本为 Android 9/API28，核查日期 2026-10-04。临时热点本身不提供互联网；不能由此
宣称 iPhone 蜂窝网络或音乐加载变快。OSN 实际频段/信道可读性、蓝牙模块响应、iOS 27
无线投屏及通话仍须实车证据。

验证完成：shared 473 项（1 项既有跳过）、common 177 项、mobile 21 项，共 671 项、
670 通过、0 失败、1 跳过。新增覆盖真实 Controller 的交接事件两种顺序、未满足条件不关闭、
关闭后恢复、Host 取消完成屏障、跨 lease 迟到 close、恢复失败保留标记、自动模式一次迁移
及无热点凭据也可进入连接页。只读复核发现并修正旧 lease 重复 close 和旧 session 线程
读取新 lease 的所有权问题：关闭必须幂等，异步恢复必须捕获对应 lease；跨 lease 回归保护。

普通 Debug、领克独立包及应用 lint 构建成功（4m36s）。应用 lint 0 错误、8 警告：
7 项既有，另1项将 library 消费的 profile 资源 `config_lynk_local_hotspot` 误报未使用，
该资源实际路径和 API28 profile 测试已核对；没有关闭规则。首轮构建也成功，复核修复后
以最终完整回归为准。证据在 `.private/verification/test12/`。

API28 模拟器：覆盖安装后默认自动模式，没有热点凭据表单；系统热点备用保存按钮可通过
滚动访问，切回自动成功。本次 crash buffer 为空。另用临时 instrumentation 在目标 App UID
实际调用打包的 LocalOnlyHotspotManager：Android 9 回传 2.4GHz/channel0 reservation，
随后因模拟器无法提供可识别的 AP 接口而明确失败，manager.close 完成。这个结果证明
API28 代码加载、系统回调和失败释放路径，**没有证明可用热点、iPhone 加入或无线 CarPlay**。
临时验证工具最初写错 SDK 目录（android-37 而实际为 android-37.0），修正路径后编译运行；
备用入口检查脚本按实际资源文案修正一次断言，未改产品逻辑或放宽判定。

交付 `dist/DiPlay-Lynk-OSN2-test12.apk`，版本 `0.2.10-lynk-osn2-test12`，code29/minSdk28，
46,955,513 字节，SHA-256：
`e9a828ff4b663245f8245f100f5d10c094705a3eb7e39ddadcffce9ba999d666`。
v2 签名、包名和 test11 一致；两份认证素材及所有 native 库与 test11 字节相同，无 APK
条目删除。覆盖安装保留旧热点等设置，并执行上述一次性自动模式迁移。

下次正常用车、停车时：覆盖安装，关闭原车系统共享热点，首页点无线连接；iPhone 保持
Wi-Fi/蓝牙开启并与车机配对。系统若拒绝临时热点，可在连接设置改用车机热点；一次导出
仍包含有线和无线记录。无需为补日志专门启动车，实车兼容性及现存音乐暂停/网速问题未验收。

已卸载临时验证 APK，恢复本次模拟器显示/字体/定位设置，关闭本次模拟器与 Gradle daemon，
删除构建目录、项目缓存、验证工具编译产物、临时日志/XML及重复截图，保留源码、关键证据、
安装包、SDK/全局依赖、认证素材和 AVD 用户数据；其他项目进程未触碰。未提交/推送。

## test11：领克风格面板

2026-10-04 用户明确要求重做旧面板。本轮是 App 首页、连接设置、分类设置及连接进度页
的视觉与导航调整；CarPlay 内手机画面仍由 iPhone 提供。参考品牌文字与深色座舱方向，
采用深灰/橙色的独立适配设计，不声称逐像素还原 OS N 2.0 官方界面。
核对品牌资料时已区分[当前第三代 03 的 Flyme Auto](https://www.lynkco.com.cn/cars/new03)
与用户 2023 车型的 OS N，未将新车型页面当作旧车系统规格或截图证据。

- `DiPlayActivity.renderLynkPanel` 只在 `config_simple_connection_flow` 打开时使用。
  左侧固定首页/连接/设置导航；小窗口改为顶部导航。首页及热点配置在宽屏使用双栏，
  窄屏纵向滚动，所有状态/长文案可换行。有线、无线、换手机、热点与导出均沿用既有回调。
  首页不将 `hasSession` 显示成成功：仍以真实 `active`/progress/错误决定状态。
- 设置分常用、画面、声音、帮助；`section(group)` 筛选当前类别，并调用原有控件构建和
  保存逻辑，所有原有领克设置保留。类别写入 Activity savedInstanceState，不增加持久化
  配置格式。导航本身不改画质、音频、热点、权限或认证参数，也不触发连接重建。
- `LynkPanelStyle` 集中颜色、字体和按钮风格，普通上游版保留原主题。菜单按钮沿用系统
  原生 View，未换 UI 框架、未增加第三方依赖/权限/后台动画或网络素材。
- Host 分为 `buildLynkConnectionPanel` / `buildStandardConnectionPanel`：Lynk 更新品牌、
  进度卡、帮助/重试/详情/导出/取消样式，所有状态字段继续接原 reporter/tick。
  根 TextureView 和 gestureLayer 均保持全屏，不因面板占位改变视频尺寸；只有实际画面
  stream 出现才隐藏连接面板。设置浮钮仍可拖动，进入设置/返回不关闭已有会话。

验证边界：用户日志确认画布 1920×1080；模拟器按此分辨率、320 dpi 检查布局，dpi 不是
实车测量值。另检查 1280×800 小横屏和 1.3 倍字体下的滚动访问。新增 `LynkPanelNavigationTest` 检查分类可达、
浏览不改变画质/音频设置、有线独立入口与类别重建恢复；首轮夹具的 qualifiers 顺序错误，
改为 Android 规定的宽/高/方向顺序后通过，没有调整产品逻辑或放宽断言。
只读复核发现新连接面板曾按创建时 screenWidthDp 固定宽度，窗口缩小时裁切。新增
`connectionPanelFitsWhenWindowNarrowsWithoutRebuilding` 从 960dp 改为 540dp，在旧实现
明确失败；首次 layout listener 修复只更新请求宽度（492dp），同轮测量仍为 760dp，
未解决裁切。根据该证据改为在 `onMeasure` 按实际容器宽度、最大 760dp 测量同一内容
View，回归通过，不重建视频和控制器。
该经验只适用于自行接管 configChanges 的提示视图；不可用重建 TextureView 修复菜单尺寸。
最终验证：common 174 项、mobile 16 项，共 190 项通过，0 失败/跳过；四项新增面板
回归包含上述窗口缩放场景。应用 lint 0 错误、7 项既有警告；普通 Debug 与领克独立包
构建成功（1m46s）。未重复运行本轮未修改的 shared 回归，也不把历史 library lint
失败写成全部 lint 通过。证据位于 `.private/verification/test11/`。

API 28 模拟器实际点击检查首页、连接配置及设置分类；等待连接时进设置再返回，日志
仍只有一个 controller 启动且未 teardown；导出文件包含有线/无线历史和完整结束标记，
取消可回首页。最终截图包含 `home.png`、`settings-audio.png`、`connection.png`、
`usb.png` 和小窗口滚动图；本次 crash buffer 为空。这些检查没有连接真实 iPhone，
不能据此确认无线成功、音乐暂停消失或手机网速改善。

交付 `dist/DiPlay-Lynk-OSN2-test11.apk`，版本 `0.2.10-lynk-osn2-test11`，code 29，
minSdk 28，47,621,785 字节；SHA-256：
`398cc4243837857240e690b0a9f172079e4e140b5f96b03af63139c540696bef`。
包名及签名保持，支持覆盖安装保留设置；v2 签名已验证。授权的认证素材与输入/上游
官方 APK 一致，所有 native 库与 test10 相同，没有删除 APK 条目。

测试完成后恢复模拟器显示尺寸/默认字体，退出本 App 并关闭本次模拟器和 Gradle
daemon；清理本项目构建缓存、临时 XML/日志和 Python 缓存，保留安装包、核验证据、
SDK/全局依赖、认证素材及 AVD 数据。未触碰其他项目进程。界面及实车效果待用户验收，
未提交/推送。

## test10：音乐按键、车机联网与设置入口

2026-10-04 用户确认 test9 有线能正常连接，但 CarPlay 音乐自行暂停；iPhone 没有连车机
热点时加载也慢，无线仍失败。用户没有导出此次日志。这是新的实车现象，不把 test7 的旧
日志当作本次根因证据，也不要求为补日志专门启动车。下次正常使用后一次导出即可。

本轮有证据的局部修复：

- `CarPlayMediaKeys` 创建 callback 时读取 `BydOutputSettings.integrationAllowed`，传给
  `CarPlayMediaButton.forKeyCode`。此前 PLAY/PAUSE 在所有车型都变为 toggle；领克现在
  分别发送 PLAY/PAUSE，真正的 toggle 键保持 toggle；353 仅 BYD 接受。重复的 PLAY 不再
  变成暂停命令。保留普通版 BYD 硬件规则、显式 media-controller 指令与长按过滤。
  **没有本次日志证明车机确实发送过异常 PLAY，不能认定已解决所有自动暂停。**
- `CarPlayController.sendMediaButton` 记录净化并限长的来源、HID index、closed/session；
  `AirPlaySession.sendMedia` 记录 press/release 写出布尔，iAP2 播放变化记录 playing 布尔。
  这些 `CONNECTION_DIAGNOSTIC` 直接进入模式历史，不受菜单 UI 代次过滤；不导出歌曲、
  设备地址或协议载荷。写出成功不代表手机已经执行。原有音频 rx/underrun/rebuffer 统计
  配合新记录区分手机暂停、外部媒体键与本地缺包；不强制自动播放或吞掉有效暂停命令。
- `CarPlayVpnService.tunnelBuilder` 添加 `allowFamily(AF_INET)`。Android 默认阻断没有
  address/route/DNS 的地址族，原 IPv6-only 隧道会拦截车机正常 IPv4；现在只保留原来
  `fe80::/64` 的 CarPlay 路由，放行 IPv4 回底层网络，没有增加默认路由、DNS、代理或 NAT。
  这是**车机**网络修复，不能由此推断 iPhone 音乐/地图加载变快；手机互联网仍不由本
  App 的 VPN 提供。环境快照增加 head-unit 标识、默认路由数量、Internet/validated 能力，
  只导出计数和布尔，不记录实际地址。依据
  [Android VpnService.Builder.allowFamily](https://developer.android.com/reference/android/net/VpnService.Builder#allowFamily(int))，
  API 21 起支持，核对日期 2026-10-04。
- `CarPlayHostActivity.buildContentView` 在领克连接等待和投屏页加右上角「设置」按钮，
  点击直接进设置，拖动后吸附左右边缘并限制在屏内；保留三指入口。按钮消费自己的手势，
  不传给 iPhone；不需要悬浮窗权限，不改变视频协商尺寸。`DiPlayActivity` 设置页显示
  「返回 CarPlay」，复用已有 Activity/controller；进入设置不停止会话。

无线协议本轮未调整。仅有“仍不行”的反馈不足以区分 Bonjour 发现、网络邀请和会话阶段，
继续使用已有阶段日志，下次正常使用后的一份有线＋无线报告同时核对暂停和无线失败。

回归：`CarPlayMediaCallbackTest` 覆盖 API 28/29 的两种车型规则、重复 PLAY、显式指令和
长按；`CarPlaySettingsButtonTest` 覆盖 tap/drag/cancel；`ConnectionDiagnosticReportTest`
证明新媒体诊断能保留到统一报告。`CarPlayVpnRoutingTest` 保护唯一 IPv6 本地路由、无默认
路由和 IPv4 放行。Robolectric API 28 的框架使用主机 JDK 不存在的 InetAddress/RouteInfo
成员，夹具两轮修正后以 builder shadow 捕获契约，得到缺少 IPv4 放行的预期失败，再验证
修复。最终 APK 还要在真正 API 28 的 Builder 检查实际配置，不能只凭 shadow 断言。
设置按钮首次编译缺少 host.R import，修复一轮后定向测试通过。

最终验证（2026-10-04）：shared 461、common 174、mobile 12，共 647 项，646 通过、
1 个原有跳过，0 失败/错误。应用 lint 0 错误、7 个既有警告；领克 standalone 和普通 debug
构建通过（3m38s）。不将应用 lint 等同于 test9 已记录的独立库 lint 全部通过。

`dist/DiPlay-Lynk-OSN2-test10.apk`：`0.2.10-lynk-osn2-test10`、code 29、minSdk 28，
46,354,966 字节；SHA-256：
`e5651682aa3268880b8bfa7226cf0505317a6f8907641ece1f81ee76ea944e57`。
签名与 test9 一致，认证素材与明确输入和上游官方 APK 一致；全部 native 库与 test9
逐字节相同，没有删除 APK 条目或打入 keystore。test9 保留作回退，直接覆盖安装保留设置。

最终 APK 已在 API 28 覆盖安装。实际查看 USB 等待页右上角按钮和设置页；拖至左侧后
仍在 USB 等待页，点击打开设置，点「返回 CarPlay」恢复原页面与按钮位置、等待计时继续。
期间日志只有一次 controller start，没有 teardown；这是等待会话保持验证，不是假装已有
iPhone 投屏。导出/分享选择器已打开（未发送），报告有 test10、两种模式历史、head-unit
网络能力/路由计数、drained=true、dropped=0 和结束标记；本轮 crash buffer 为空。
独立 `VpnRoutingSmoke.java` 读取最终 APK 在真实 API 28 Builder 上的配置，确认 IPv4
已放行、仅 IPv6 本地 /64 路由、无默认路由，MTU=1500/blocking=true 保持；未建立 VPN、
未连接 USB，也未测量互联网速度。音频渲染代码本轮未变，test9 的音轨启停证据沿用，不
将其重复计作 test10 真机验证。证据、JUnit、包核验统一在 `.private/verification/test10/`。

收尾已关闭本次 Medium_Tablet / emulator-5556 与本项目空闲 Gradle 9.5 daemon（5114），
删除 module build、项目 .gradle、临时日志副本和 smoke 编译产物，按文件分配空间约
296.1 MiB；保留源码、最终/回退包、必要验证证据、AVD 数据和全局 SDK/依赖缓存。
其他任务的 emulator-5560 与 Gradle 9.3.1 未操作；明细见 `cleanup.json`。

本地实现与验证完成；实际音乐暂停改善、手机加载速度和无线连接仍待实车证据。main 未提交、未推送。

## test9：集中检查音频、关闭与重连

2026-10-04 用户要求尽量一次处理完整，减少为排障反复启动车辆。先完成可本地复现的
兼容性、故障注入、连接生命周期及最终 APK 验证，再安排一次集中实车验证；不将模拟器
通过说成车机已验收。下列缺陷由源码与本地复现确认，尚不能认定它们都命中了实车日志。

已实施的修复：

- `AndroidMediaSink.AudioRenderer.configureCodec` 和 `OpusEncoder.createCodec`：之前
  MediaCodec 工厂返回后，configure/start 抛错会丢失实例；现在失败分支先释放候选实例。
  保留原音频格式、路由和失败行为，不降低音质。`AudioCodecLifecycleTest` 的 4 项用例
  在旧代码因未释放失败，修复后通过；麦克风初始化、权限、Siri 回调失败、音频模式恢复的
  `TelephonyMicrophoneTest` 扩到 API 28/29，共 26 项通过。
- `CarPlayController.close`：之前 executor 等待 2 秒后，即使工作线程还持有 USB/MFi
  资源也会发出关闭完成。现在等实际终止后再次清理迟到的 CSM/USBMUX/MFi/无线资源，
  完成 `CompletableFuture`；等待超时只返回 false。迟到服务绑定在同一生命周期锁下检查
  closed/vpnBound。`ControllerCloseCompletionTest` 的阻塞工作线程、迟到 MFi 释放、
  完成回调次数与关闭后服务绑定检查已通过。第二次收尾也对 close 捕获的有线 service
  执行 detach，避免工作线程在首次 detach 后才 attach，且取消时 vpnService 已被清空，
  导致旧 NCM/监听资源残留。`attachmentPublishedAfterFirstDetachIsReleasedBeforeCompletion`
  用真实本地 listener 和持有的 fd 重放该所有权时序；旧代码失败，修复后通过。
- `CarPlayHostActivity.restartCarPlay`：之前忽略 4 秒等待结果继续创建新 controller。
  现在 4 秒仅记录并显示“正在结束上一次连接”，由 `whenClosed` 完成回调释放旧 sink、
  回 UI 检查销毁/取消/generation，再接续原启动函数。原生 USB 调用若始终不返回，
  不能声称资源已释放；本修复不增加 USB reset 或无限连接重试。
- `CarPlayVpnService.acceptLoop`：旧 listener 的 accept 可能在 detach/attach 后迟到，
  原实现借用了新 attachment。现在在创建/登记 session 的同一锁内检查 active、generation
  和 server 对象，不匹配即关 socket。`CarPlayVpnGenerationTest` 在旧实现失败，修复后
  证明旧连接被拒绝、新 listener 仍可接受连接。

API 检查扩大到了 shared/common，不再把应用 lint 结果等同于所有库已检查。
直接运行库 lint 当前仍失败：shared 18 errors、common 127 errors，分类保存在
`.private/verification/test9/library-lint-summary.json`。包含缺失翻译、库单独检查时的权限/
manifest 上下文、非领克 BYD 路径，以及由调用者 API guard 保护的较新 API；没有批量屏蔽。
另按编译后的 SDK API 数据核查自有代码 31 个 API > 28 引用的入口条件，记录在
`newer-api-references.json`；没有确认新的 API 28 缺失方法崩溃，但该检查并非完整形式证明。
平台依据：[CompletableFuture API 24 起提供](https://developer.android.com/reference/java/util/concurrent/CompletableFuture)、
[MediaCodec.release 释放 codec 资源](https://developer.android.com/reference/android/media/MediaCodec#release())。

定向回归：codec/麦克风 30 项、controller 3 项、VPN 1 项分别通过。新增
`mobile/src/testLynkDebug/.../CarPlayRestartBarrierTest` 的 2 项证明：4 秒阈值后仍不
重建，旧 worker 实际退出后只重建一次，以及等待期间取消不能触发重连。这里验证控制器
所有权与 UI 调度，不代表新 controller 已完成 iPhone 认证；测试无需私有认证素材。

测试经验：普通 `.get()` Activity 夹具不会执行 onCreate，不适合直接沿用几何/日志测试
去验证实际重建。此前缺少 `airPlayIdentity` 初始化；移到实际 Lynk profile 避开无关 BYD
绑定，并显式初始化真实启动链需要的身份。首次准备累计两轮修正仍失败后已暂停，用户
2026-10-04 明确要求继续；恢复后完成初始化并通过，不曾删测试或放宽断言。迟到有线
attach 的新回归先在缺失 detach 的实现上失败，修复后通过；本次生产修复无追加失败。

对现有 test8 APK 的 API 28 模拟器音频压力检查：5 个 sink、每个依次 media/navigation/
call/Siri 下行 LPCM，共 20 次启停，写入 400365 frames；每轮无残留 carplay-audio 线程，
进程 fd 数均为 30。证据 `audio-cycles-test8.log`；这不覆盖实际 iPhone 协商、车机可闻音频
或电话/Siri 上行，最终新包必须重跑。该检查不得归为 test9 的 APK 验证。

最终验证（2026-10-04）：shared 460、common 163、mobile 12，共 635 项，634 通过、
1 个原有跳过、0 失败/错误；应用 lint 0 错误、7 个既有警告。领克 standalone、普通 debug
和完整测试构建通过（7m23s），不代表上述单独库 lint 全部通过。证据和最终 JUnit 已存
`.private/verification/test9/`。只读最终复核未发现本次最小修复的新增阻塞项。

最终包 `dist/DiPlay-Lynk-OSN2-test9.apk`，`0.2.10-lynk-osn2-test9`、code 29、minSdk 28，
46,337,530 字节，SHA-256：
`3450f90bf4c43bdf905a877bac018b34651b92e068ce4055038bfc9a099c0ff7`。
签名与 test8 相同；认证素材匹配明确输入与上游官方 APK；所有 native 库与 test8 逐字节一致，
没有删除 APK 条目，也没有 keystore。实车覆盖安装保留设置，旧包保留供回退。

最终 APK 已覆盖安装到 API 28 模拟器。同一音频 harness 的 20 次启停全部通过，累计写入
411725 frames，5 轮均无残留音频线程、fd 数均为 30；帧数受调度影响，不作性能提升指标。
实际 UI 已检查首页版本、USB 等待、重试后新一轮等待、取消返回、再次进入、导出与分享
选择器（未发送）。最终导出含 test9、USB/无线历史、drained=true/dropped=0 和报告结束标记；
日志确认旧控制器 teardown end 先于新 controller start。最终 crash buffer 为空。
首次页面切换的 UIAutomator dump 返回 null root，后续读取和截图成功，不能将工具瞬态
失败当作 App 崩溃。模拟器无 iPhone/车机 USB，以上不证明真实无线连接或 NCM 驱动表现。

收尾已关闭本任务的 Medium_Tablet / emulator-5556、确认只服务本项目且已空闲的 Gradle
9.5 daemon（PID 10772）；无 Gradle 测试 worker 残留。清除 mobile/common/shared 的生成
build 目录、项目 .gradle、重复临时日志与已归档源码对应的 smoke 编译产物，按文件分配
空间计约 310.3 MiB。保留源码、最终/回退 APK、必要测试证据、模拟器业务数据和全局 SDK/
依赖缓存；其他任务的 emulator-5560、Gradle 9.3.1 未处理。明细见 `cleanup.json`。

本地实现与验证完成，待用户实车验收；尚未提交或推送。下次正常用车时集中复验即可，
不为每个补丁单独启动。无线发现/邀请、OEM USB 恢复和实车长连仍未验收，不继续猜测
手机设置或追加握手延时。

## test8：有线画面成功后的 Android 9 音频崩溃

2026-10-04 新实车证据：`DiPlay-20261004-102308-357.txt`，test7，API 28，iPhone iOS 27.0.1。
用户明确反馈曾有线出画面，操作后退出；这是局部成功，不是完整稳定性验收。

- 10:21:19 USB 配置 2→6 的音频/HID 临时交接成功；10:21:28.940 AA05 认证成功；
  10:21:29.224 AirPlay TCP 到达；10:21:31.853 首帧，1920×1080，随后视频与触摸持续工作。
- 10:22:10.778 媒体音频 SETUP（LPCM/48kHz/双声道）；10:22:10.885 最后一条音频统计为
  track 已初始化、0 写入、ended=true，没有 `Audio: ready`，没有正常 controller teardown。
  10:22:18 进程 PID 已改变。原导出没有系统崩溃堆栈，只能据此定位异常窗口。
- 确定源码缺陷：`AudioRenderer.createTrack` 无条件调用 Android 10/API 29 才加入的
  `AudioTrack.getAudioAttributes()`。Android 9 不存在此方法，`NoSuchMethodError` 越过
  `catch(Exception)`，finally 输出统计后进程退出。API 28/29 的真实构建调用回归显示：旧代码
  API 28 的默认/旧流类型两例失败，API 29 两例通过；旧 test7 APK 在 API 28 模拟器内执行
  同一音频 worker 也复现了该异常与行号。这使它成为与实车时间线高度吻合的原因，仍不冒充
  已取得 OS N 的原始崩溃栈。
- 后续两次 USB 均复用配置 6、接口认领/认证/4301 成功，却无新 TCP。读失败发生在 teardown
  之后，不能反向视为首次故障。首次进程崩溃未执行内存中的配置恢复，手机/USB 残留会话是
  候选，尚未证实；未加设备强制重设或任意延时。[上游 #141](https://github.com/shihabal3amri/DiPlay/issues/141)
  有相似重连表现，但平台/触发不同，且无已验证修复，不能直接移植其猜测。
- 无线两次认证与 4301 均完成，未记录 TCP 到达。Bonjour 普通日志在菜单打开时会丢弃，
  因此原报告“没有发现记录”不严格证明没有发现手机；当前不能确定无线根因。

实现：API 28 保留实际构造属性；API 29+ 沿用 getter，旧流失败时记录实际 usage fallback
属性，供音频焦点使用。音频格式、路由、缓冲、画质和 USB 策略均保持。
防线为 `AndroidAudioStartupTest`：API 28/29 各测默认媒体、标准 legacy stream、
ROM 拒绝 legacy 后回退；最后一例通过受控的未初始化音轨模拟，不假定 Robolectric 会拒绝
任意 OEM stream ID（首个测试夹具中的这一假设不成立，已修正夹具，未放宽断言）。

日志链：`CarPlayHostActivity.initializeSessionLog` → `ConnectionCrashLog.install`；
fatal handler 同步 AtomicFile 保存最后一次 Java/Kotlin 堆栈，再调用原系统 handler。
异常消息不保存，只保留时间/PID、异常类、最多 4 层 cause 和每层 12 帧；异常写入失败仍交还
系统。下次进程重开后的 `ConnectionDiagnosticReport` 汇入该记录并标明可能早于当前尝试。
无法覆盖 native crash、系统 kill、安装 handler 前的启动失败，不能把无记录当作无崩溃。

Bonjour 发现/解析/邀请改走 `CONNECTION_DIAGNOSTIC`，独立写入无线和 general 历史；
后台处理异常类型和 JmDNS 不可恢复 IO 通知亦进入同一路径。启动提示只说 requested，
不宣称公告已送达手机。没有改地址族、热点拓扑或邀请时序，也没有采集原始协议、手机名称或密码。

核查来源：[Android AudioTrack API](https://developer.android.com/reference/android/media/AudioTrack#getAudioAttributes())、
[AOSP Android 9 AudioTrack](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/media/java/android/media/AudioTrack.java)、
[JmDNS 3.6.3](https://github.com/jmdns/jmdns/blob/v3.6.3/src/main/java/javax/jmdns/JmDNS.java)。
既有 CatPlay 邀请前再公告以先发现手机为前提，当前证据不支持把它当作无线根因修复。

最终验证（2026-10-04）：shared 442、common 163、mobile 7，共 612 个用例，611 通过、
1 个上游跳过、0 失败/错误；领克 lint 0 错误、7 个既有警告；领克 standalone 与普通 debug
构建通过，最终日志 `.private/verification/test8/final-build.log`（1m52s）。只读复核发现
AtomicFile 不自带并发保护，补充共享读写锁和并发导出测试后对最终代码重新构建通过；
系统原 handler 始终在锁外调用。生产修复没有验证失败，fallback 测试夹具修正一次。

`dist/DiPlay-Lynk-OSN2-test8.apk`：版本 `0.2.10-lynk-osn2-test8`、code 29、minSdk 28，
47,228,767 字节；SHA-256：`37258e237936a02d9877cc82ababbb9577c9ded6a297f273f0d23fd2ac34c278`。
签名与 test7 相同，认证素材逐字节匹配上游 APK/明确输入；三种 ABI 的 USB 配置 native 库
与 test7 相同，无签名 keystore；已跟踪与新增公共源码的凭据检查通过。

最终 APK 在 API 28 模拟器覆盖安装成功。独立 harness 在 app UID 执行包内真实音频 worker：
静音 LPCM/48kHz/双声道进入 PLAYING、写入 48,000 帧，关闭后 worker 正常结束；旧 test7
同一 harness 抛 `NoSuchMethodError`。模拟器关闭声音输出，因此不宣称已验证实车可闻音质。
另一个独立 harness 调用包内 fatal handler，以明确标记 `SyntheticDiagnosticSmoke` 的模拟
异常验证同步落盘/原 handler 委托/消息排除；进程结束后，真实 App 通过 USB 等待页导出，
读回报告确认 test8、模拟前进程堆栈、双模式历史、drained=true/dropped=0 和结束标记齐全，
分享选择器可打开，最终 crash buffer 为空。UI 自动化首次 null-root，实际截图显示 USB 等待，
后续读取成功；不是 App 崩溃。模拟器没有 iPhone，以上不代表无线发现、USB 重连或实车验收。

旧版红灯证据、模拟器崩溃栈、最终报告及独立 harness 在 `.private/verification/test8/`，
均不进入 APK/Git。main 修改未提交、未推送；test7 回退包保留，但含已知 API 28 音频崩溃。
本轮已完成本地兼容修复及诊断补齐，无协议/音质/USB 策略变更。
实车下一步：拔线覆盖 test8 后重新插线，进入画面播放音乐并持续操作，再拔插验证重连；
无线按原热点方式尝试，结束后一份报告包含两种模式及保留的崩溃记录。
无线根因、异常进程退出后的 USB 恢复和实车持续连接仍未验收，不继续无证据调整参数。

## test7：按独立实现纠正无线启动和网络邀请

2026-10-03，用户要求先核对成熟项目的真实流程，再继续修复，并批准实施。
本版保留车机热点拓扑、USB 驱动恢复、认证素材、画质和现有配对；不升级上游或引入新依赖。

**证据与选择：**

- [LIVI bringup.rs](https://github.com/f-io/LIVI/blob/08243b8f3da5a2bb752e2d2995f7ce15e01e15d1/native/livi-helperd/crates/livi-runtime/src/bringup.rs#L473)
  在 AP/地址就绪时响应 `4300`，没有本地新增的 false/unknown 门槛。test5 实车两次命中该门槛，
  后续 `4e0d=true` 也不会补发。删除 `WirelessCarPlayAvailability` 和对应资源开关，恢复收到
  `4300` 就发送 `4301`；可用性字段继续记日志，不解释成手机授权拒绝，不改启动超时预算。
- [LIVI bonjour.rs](https://github.com/f-io/LIVI/blob/08243b8f3da5a2bb752e2d2995f7ce15e01e15d1/native/livi-helperd/crates/livi-runtime/src/bonjour.rs#L54)
  与 [CatPlay cp_ctrl_invite.rs](https://github.com/catplay-labs/catplay/blob/dbb3ebbcde8a0383b7ac1b9bf422d9c7b5a54129/carplay/catplay_carplay/src/ctrl/cp_ctrl_invite.rs)
  都将接收端 MAC 数值转为十进制写入 `AirPlay-Receiver-Device-ID`。修正原来的去冒号十六进制文本，
  严格接收六组冒号分隔或 12 位紧凑 HEX，使用 API 28 可用的 Long（48 位无溢出）。Bonjour TXT 的
  `deviceid` 不变。样例 `AA:BB:CC:DD:EE:FF → 187723572702975`，`02:00:00:00:00:02 → 2199023255554`。
- [PlayPort WirelessBootstrap](https://github.com/youcci/playport/blob/9a0882dd0ffe48e467b59d58b12d81391df55ade/server/src/main/kotlin/com/playport/server/WirelessBootstrap.kt)
  保留自动地址失败回退；它源自 DiPlay，只作同源对照。[DiPlay #18](https://github.com/shihabal3amri/DiPlay/issues/18)
  也有合成地址无线可用的报告。缺真实 MAC 不能直接判为不能启动。因此取消 UI 和 controller 的必填，
  保持 ADAPTER → SETTINGS → 可选 saved → 配置回退；iAP2 与 AirPlay 用同一次结果。
  已存地址不删除；设置页明确“可选”，留空保存只移除这个补充值，保留手机选择和热点。

**当前调用链：**

`DiPlayActivity.connect` 检查热点/已配对手机 → `CarPlayController.runWireless` 选择统一身份、
启动热点接口 AirPlay/Bonjour → 蓝牙 iAP2 识别 → MFi `AA05` → 订阅 →
按手机消息响应 `5702→5703`、`4300→4301` → Bonjour 发现手机控制服务并发送网络邀请 →
等待真实 AirPlay 会话/画面回调。Wi-Fi/蓝牙关联、发送启动、HTTP 200 都不等于画面已连接。

**防止重复错误：**

此前只验证状态类内部逻辑，测试将“false 就阻止”当成正确需求，未用独立实现或实际消息顺序检验。
本版以 `Iap2WirelessHandshakeReplayTest` 替代该状态类测试：真实 Link、CSM、识别及 MFi 客户端，
仅模拟手机和认证签名器；合成脚本包含 false/unknown/wired-only/true `4300`、`5702`、
false→true `4e0d`（无第二次 `4300`）及其他消息转发，检查实际发送的 `4301`。
只读复核还发现未知可选值会令诊断解析抛错，新增 `availability=2` 回放先复现失败，再将
该字段的 `Iap2ProtocolException` 记录为 `decode=unrecognized` 并继续启动；不吞掉链路/认证错误。
`CarPlayBonjourTest` 使用独立数值样例和非法输入，`WirelessAutomaticIdentityFlowTest` 在 API 28
回放已配对且热点就绪、系统 MAC 隐藏时进入投屏页面；空输入恢复自动配置也覆盖手机/热点保留。
旧版已实际出现协议 5 项失败、UI 门槛和空输入失败；修复后再运行完整回归。
合成手机没有验证真实证书或 iOS 27 行为，不能冒充实车成功。

**尚未证实：** test4 已发 `4301` 仍无画面，所以撤销门槛不代表原故障全解；test5 没有控制服务发现/
邀请完成记录，尚不能证明错误请求头曾在本车被发送。CatPlay 的 Wi-Fi 先于蓝牙可能漏公告、邀请前
重新公告的做法是下一层线索；本版不在没有新证据时同时改公告时序、重试次数或热点接口。
现有完整导出继续覆盖两种连接，不再要求用户找 MAC 或猜协议步骤。

## 本地构建

沿用上游 JDK 25、SDK 37、NDK 28.2.13676358 和 Gradle 9.5.0；运行最低版本仍为 Android 9。

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:testLynkDebugUnitTest :mobile:lintLynkDebug --no-configuration-cache
DIPLAY_AUTH_ASSETS_DIR="$PWD/.private/runtime-assets" ./gradlew :mobile:assembleLynkStandaloneDebug --no-configuration-cache
```

输出：`mobile/build/outputs/apk/lynkDebug/mobile-lynkDebug.apk`。
无认证素材的 `assembleLynkDebug` 只用于源码检查，不能作为独立 CarPlay 实车包交付。
只使用明确选择的 `offline-mfi/identity.pk8` 和 `certificate.p7b`；构建时校验，交付时逐文件比对。
上游 APK 下载来自 [v0.2.10 官方发布](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.10)，
已与同一发布的 SHA256SUMS 核对，APK SHA-256：
`8c555ce179f30a30b659914f70355245a594f82957424bf44fc532fb1409441e`。
保留上游 [GPL/第三方声明](THIRD_PARTY_NOTICES.md)。测试包不授权公开发布。

## 实车验收

test9 有线连接已由用户确认。最新测试包为 test12（包含 test10 修复、test11 面板和自动热点），下次正常用车、停车时观察音乐是否
自行暂停，并用边缘「设置」进入/返回；无线如仍失败，使用同一份完整日志定位。无需为
补日志专门启动车辆。以下是完整验收覆盖，长期稳定性可随正常使用记录。

停车操作。开启车机热点并在 App 保存准确 SSID/密码；若车机提供 5 GHz 可选择它，不硬编码频段。
打开 iPhone 蓝牙/Wi-Fi、与车机配对，在 App 连接并接受 CarPlay 提示。
有线使用车机数据 USB 口和支持数据传输的线；接受 USB、信任和本地 VPN 提示。

1. USB 与无线各完成首次连接、画面/触摸、导航/音乐、电话和 Siri。Android 蓝牙公开 API
   是否暴露 OS N 车载蓝牙、麦克风路由是否可用，必须在这一步核实。
2. 连接后切车机首页/原生导航/倒车画面，再返回：前台通知仍在，连接可恢复，音频正常。
3. USB 拔插与热点关闭/重新开启分别验证重连。记录首次画面、断线时间、重连耗时；
   若要声称稳定性改善，在相同条件下与上游 0.2.10 对照。
4. 两种模式各持续使用至少 30 分钟，记录断线次数、音频中断和导航延迟。
5. 开机自动启动与清最近任务单独测试；后者当前应停止连接，不能当作普通后台切换故障。

失败后直接点连接页或首页的「导出全部连接日志（有线＋无线）」。
两种连接各试一次后发回一份报告即可；不要卸载清除记录。

## 验证状态与续接

test9 有线连接已确认，音乐暂停/加载慢/无线仍待定位；test10 修复、test11 面板及最新 test12 自动热点状态见上方对应段落。下面是 test7 及更早的历史证据。

### test7 历史验证

test7 最终代码（2026-10-03）：shared 436、common 159、mobile 7，共 602 个用例，
601 通过、1 个上游跳过，0 失败/错误；领克 lint 0 错误、7 个既有警告。领克 standalone
和普通 debug 均构建成功；最终日志 `/tmp/lynk-diplay-test7-final.log`（2m10s）。
`dist/DiPlay-Lynk-OSN2-test7.apk`：`0.2.10-lynk-osn2-test7`、code 29、minSdk 28，
46,822,783 字节，SHA-256：`9e5bdaeffd236adffa41abb58fa5f9fcbde00655275b9b471ef8f3fb093e54ca`。
v2 签名与 test6 相同；认证素材逐字节匹配明确输入及官方 APK，无 keystore。
三个 ABI 的 `libusb_configuration.so` 与 test6 完全一致，复用该版 native 烟测边界。

API 28 模拟器已覆盖安装最终 APK、启动并导出报告；版本、双模式历史区段、queue drained=true、
dropped=0、结束标记与地址脱敏均通过，分享选择器可打开（未发送），本轮 crash buffer 为空。
实际查看了可选地址弹窗，保存空输入能关闭弹窗并移除原测试补充值；USB 显示等待设备。
界面检查在最后仅改 `4300` 诊断异常捕获前完成，UI 源码未变；最终包再次覆盖安装/启动/导出。
模拟器没有 iPhone，以上不证明认证、无线网络发现、USB 驱动接管或实车稳定性。
证据统一在 `.private/verification/test7/`，包含修复前失败、最终测试摘要、包核验和导出报告。

本轮已完成本地实现/验证，待实车验收。首次测试准备修正了枚举名、跨模块 internal 访问；
这些不是产品连接失败。旧行为回归测试及复核边界用例均先失败，修复后最终全套通过。
当前 main 适配未提交、未推送。回退包保留 `dist/DiPlay-Lynk-OSN2-test6.apk`，其中无线硬门槛
已证实不合适，不建议再试。test7 直接覆盖安装，保留热点设置、无需填写蓝牙 MAC；有线/无线
各试一次，如无画面只需导出一个完整报告。后续优先核对 `4301`、Bonjour 发现/邀请、
AirPlay TCP 与 USB errno/驱动恢复，不让用户猜协议、反复改超时或重新找 MAC。

### 历史构建证据

test6 最终代码（2026-10-03）：shared 436、common 160、mobile 6，共 602 个用例，
601 通过、1 个上游跳过，0 失败/错误；领克 lint 0 错误、7 个既有警告。
领克 standalone 与普通 debug 构建成功。`dist/DiPlay-Lynk-OSN2-test6.apk`，
版本 `0.2.10-lynk-osn2-test6`、code 29、minSdk 28，47,551,995 字节。
SHA-256：`8cf44e5ac4565be2dbad438d36db6ae93fdaaef740f82430bd579694589c6ba8`。
v2 签名与 test5 相同；认证素材与明确输入、上游 APK 逐字节一致，无 keystore；
`libusb_configuration.so` 打包 arm64-v8a、armeabi-v7a、x86_64。公共源码凭据检查通过。

API 28 模拟器安装、启动、地址弹窗实际渲染、空输入拦截、有效测试地址保存/重开读回、
USB 等待、日志导出与分享选择器通过（未发送）；报告含 test6 版本、有线/无线两类历史区段、queue drained=true、
dropped=0 与结束标记，手填地址未导出。没有 iPhone，无线历史为空，不冒充无线连接测试。
启动/交互/导出期间 crash buffer 为空。生产 JNI 在 app UID 下通过借用 fd 烟测：
拒绝非白名单驱动、正确返回 EBADF/ENOTTY/EINVAL，CONNECT 保留负 errno，调用后 fd 仍可 dup。
该烟测没有操作 USB 设备，不能证明 OS N 允许驱动切换或退出恢复。
证据：`.private/verification/android9-test6-*`，独立入口
`.private/verification/native-test6/UsbConfigurationSmoke.java`，均不进入安装包。

只读复核补齐握手失败资源清理、恢复异常误报和前端/控制器占位地址规则一致性，并有相应
针对性测试；最终全套检查在这些修改后通过。本轮无构建/测试失败，未使用失败后的修复预算。
参考方法及本地实现完成；实车有线切换、无线身份与可用性、驱动恢复仍待验收。
当前 main 的适配修改未提交，未推送。test5 回退包在 `.private/releases/DiPlay-Lynk-test5.apk`。
test6 的无线后续方案已由 test7 替代；不再要求用户测试必填 MAC 的版本。USB 仍按新实车 errno/阶段决策。

test5 最终代码：shared 418、common 156、mobile 6，共 580 个用例，579 通过、1 个上游跳过，
0 失败/错误；领克 lint 0 错误、7 个既有警告；领克 standalone 与普通 debug 构建成功。
`dist/DiPlay-Lynk-OSN2-test5.apk`，版本 `0.2.10-lynk-osn2-test5`，版本号 29，minSdk 28。
SHA-256：`6c16b69d5529341d34a77791648208039041ea29fb3fc41529721c964ff93a1c`。
v2 签名与 test4 相同；两份认证素材逐字节匹配明确输入及上游 APK，无签名 keystore；
新增 JNI 已打包 arm64-v8a、armeabi-v7a、x86_64。公共源码凭据检查通过。

API 28 模拟器覆盖安装/启动、USB 等待、保存报告、分享选择器通过；最终报告 UTF-8 读回，
test5 版本、两种模式历史区段、queue drained=true、dropped=0 与结束标记齐全；模拟器没有
iPhone，无线历史为空，不冒充车机记录。最终 App 启动/导出期间 crash buffer 为空。
生产 APK 的 JNI 经 app UID 的独立烟测实际调用：无效 fd 返回 EBADF，`/dev/null` 返回
ENOTTY，无效配置返回 EINVAL，调用后借用 fd 仍可 dup；没有访问实际 USB，不能证明配置可切换。
证据位于 `.private/verification/android9-test5-*`；测试入口为
`.private/verification/native-test5/UsbConfigurationSmoke.java`，不进入安装包。
烟测准备时修正了 SDK 路径 `android-37.0` 和 APK 内 nativeLibrary 搜索路径；此前错误路径的
独立 harness 失败不属于 App 连接验证。首轮产品测试因诊断字段被过滤失败，修复 1 轮后全套通过。
只读复核补齐了手机 false→true 后的 UI/超时归因恢复，最终调用链复核无新增发现。

test5 的待查 USB errno/驱动来源已由最新实车报告确认，处理见 test6。test4 回退包仍保留于
`.private/releases/DiPlay-Lynk-test4.apk`。上述为 test5 历史验证，不代表实车连接成功。

test4 最终代码：shared 407、common 154、mobile 6，共 567 个用例，566 通过、1 个上游跳过，
0 失败/错误；领克 lint 0 错误、7 个既有警告；领克 standalone 与普通 debug 构建成功。
`dist/DiPlay-Lynk-OSN2-test4.apk`，版本 `0.2.10-lynk-osn2-test4`，版本号 29，minSdk 28。
SHA-256：`792162fd381810f32fe752dd7378a7937b9e78c13ea2794d623878adf47ea0f9`。
最终 APK 的 v2 签名与 test3 相同，认证素材逐字节匹配明确输入及上游 APK，无签名 keystore。
API 28 模拟器已覆盖安装并启动；USB 等待、导出和分享选择器已检查；最终 APK 导出文件已读回，
版本、双模式区段、队列排空和结束标记齐全，crash buffer 为空。证据为
`.private/verification/android9-test4-*`；不代表 iPhone 实车连接通过。
只读复核发现 TCP 新日志最初仅进入 general，已改用 `CONNECTION_DIAGNOSTIC` 保证写入双模式
独立历史，且不受菜单打开影响；补充报告断言后对最终代码重新验证通过。本轮无构建/测试失败。
test3 回退包已保留 `.private/releases/DiPlay-Lynk-test3.apk`；安装包、日志证据均不入 Git。
test4 之后的实车失败已记录在 test5；该历史构建结果不代表实车通过。

test3 最终代码：shared 395、common 154、mobile 6，共 555 个用例，554 通过、1 个上游用例跳过，
0 失败/错误；领克 lint 0 错误、7 个既有警告；领克 standalone 和普通 debug 构建成功。
`dist/DiPlay-Lynk-OSN2-test3.apk`，版本 `0.2.10-lynk-osn2-test3`，包名/签名与 test2 相同。
SHA-256：`bf27263301f5fe362b2d0901b9ab69ad3327e8f4b34a6717884736bb4815b60f`。
v2 签名、minSdk 28、认证素材与明确输入/上游 APK 逐字节一致已核实，公共源码凭据检查通过。
API 28 模拟器实际从 USB 等待页面点导出，文件保存成功且可读，分享选择器打开；
最终 APK 再次覆盖安装/启动/导出并读回包含两种模式区段、队列排空标记和文件结束标记的报告，
crash buffer 为空。模拟器未连接 iPhone，无线历史为空如实报告，不冒充实车数据。
证据保留于 `.private/verification/android9-test3-*`；用户尚未实车验收，代码未提交。
USB 生命周期测试的 common/默认 BYD 测试环境问题修复 1 轮后通过。

test2 最终代码：shared 389、common 148、mobile 3，共 540 个用例，539 通过、1 个上游用例跳过，
0 失败/错误；领克 lint 0 错误、7 个既有警告，领克 standalone 与普通 debug 构建成功。
最终 APK 为 `0.2.10-lynk-osn2-test2`，包名/签名与 test1 相同，可直接覆盖安装保留数据。
交付副本：`dist/DiPlay-Lynk-OSN2-test2.apk`，SHA-256：
`4c9fccd39de2d02045a90096768925c7fcbcf1d236c3d9bc54dbd8f4c501fa67`。
已再次验证 v2 签名、minSdk 28、实验认证素材与上游 APK/明确输入逐字节一致。
API 28 模拟器覆盖安装/启动成功；实际检查中文首页、首次连接直接填写热点、USB 等待、
30 秒后重试入口、点击重试重建且计时归零、取消返回未连接首页；crash buffer 为空。
语言为模拟器测试预置，无车机数据；不是 iPhone/领克无线认证和稳定性验收。
本轮首次编译发现新增 AlertDialog 缺 import，修复后完整检查通过；重试竞态与 USB 提示分流
经只读复核修正并加回归。代码保留未提交，等待实车验收。

test1 基线验证：shared 389、common 140、mobile 3，共 532 个用例，531 通过、0 失败/错误、
1 个上游 macOS wildcard-bind 明确跳过。领克 lint 为 0 错误、7 警告（含上游 USB TLS trust-manager
警告、资源使用判断和依赖声明建议）；领克独立包与普通 debug 包构建成功。
已核对 APK 最低 SDK 28、独立包名、两个资源开关、arm64-v8a/armeabi-v7a 原生库、v2 签名，
两份认证素材与明确选择的输入逐字节相同，无 Android 签名 keystore。

Android 9 / API 28 模拟器：覆盖安装成功、启动 `DiPlayActivity` 成功，实际查看首页并进入设置，
认证加载未出现错误，crash buffer 没有该应用崩溃。模拟器不能验证领克蓝牙、USB、麦克风和后台策略。
没有连接的实车，未安装到车机、未验证 iOS 27 握手或稳定性提升。
当前 main 工作区包含本次未提交修改；实车完整验收后按用户规范本地提交。

test1 APK SHA-256：`3e8a66d7f541164418dbba3ff49cecae81924c4994bd9bbc929eb6c8e93b5145`，
保留于 `.private/releases/DiPlay-Lynk-test1.apk`，可覆盖安装回退。
本地 debug 签名证书 SHA-256：`b8bad83ae458e511ef57b83d326b8b8a3f66c16c416affdaeeaee13ee876acdb`；
后续覆盖安装须保留此签名。APK 与模拟器验证文件位于被 Git 忽略的 build / `.private/verification`。
若严格接口模式超时，优先核对接口选择诊断和实际 AP/STA 地址，保留 IPv6，不能改成随便选网卡。

维护经验：manual 系统热点已存在，不能直接复用要求“启动后新增 IPv4”的 LocalOnly 接口策略；
其防线是 `ManualHotspotInterfacePolicyTest` 与现有 `WirelessHostAddressTest`。
AGP 9.3 的自定义 build type 即使 `initWith(debug)` 也不会默认生成 host test 任务；首次验证遇到
任务不存在，已用 [HostTestBuilder.enable](https://developer.android.com/reference/tools/gradle-api/9.3/com/android/build/api/variant/HostTestBuilder)
定向启用 `lynkDebug` unit tests，随后验证通过（此故障修复 1 轮）。构建 SDK 新不代表运行最低版本新，
需同时核对最终 manifest、API 28 测试和实际启动。

依据：[上游兼容范围](COMPATIBILITY.md)、[上游独立包构建](BUILD.md)、
[Android 网络枚举](https://developer.android.com/reference/android/net/ConnectivityManager#getAllNetworks())、
[Android Service 生命周期](https://developer.android.com/reference/android/app/Service)。


## test13：环境检测与领克面板调整（2026-10-05）

用户要求车机配置/CarPlay 条件自检，并继续改善领克风格 UI。入口为首页「环境检测」和
左侧「检测」。采用原生 View，统一深灰表面、冰蓝强调色、较轻边框，保留有线/无线主入口、
设置分类、真实会话返回和原配置值；没有改握手、画质、音质或自动热点交接算法。

调用链：`DiPlayActivity.checkEnvironment` 后台调用 `CarPlayEnvironmentCheck.capture` →
不可变 `EnvironmentFacts` → `evaluate` → `EnvironmentReport` → `EnvironmentCheckPanel`。
检测返回后仅当前首页/检测页刷新，退出 Activity 后不操作旧 View；点击修复按钮才进入相应
系统权限/定位/蓝牙设置、连接页或画面设置，返回检测页重新采集。检测失败明确提示，不触发连接重试。

| 分组 | 静态可读结果 | 必须留待真实连接验证 |
| --- | --- | --- |
| 应用 | Android 版本、当前进程 ABI 对应组件、选定格式的解码器声明、本地认证读取/配对校验、麦克风权限 | 解码实际性能、持续音频、触控、Siri/通话 |
| 有线 | USB host 声明、Apple 设备存在、已有 USB 授权 | 数据线/端口、信任、模式切换、USBMUX/NCM、VPN 建立、iPhone 认证 |
| 无线 | Wi-Fi/蓝牙模块、权限、所选手机仍配对、自动/手动热点前置条件 | RFCOMM/iAP2 可达性、真实 AP 网卡/信道/BSSID、热点交接和 iPhone 接受认证 |

每项仅显示「已满足／需处理／待验证」。分组汇总的优先级为需处理 > 待验证 > 已满足，
三个分组都有实际连接待验证项；静态检查全部通过也不会显示“CarPlay 一定可用”。未插 iPhone
显示待验证，不能误报不支持有线。麦克风未授权只说明 Siri/通话限制。系统不暴露的 API 结果
为待验证，不猜测 pass；不会把系统 Wi-Fi 未开启直接当成自动热点不可用。

- `LocalMfiAuthenticationClient.load` 读取现有素材并做本地密钥/证书配对校验，不调用
  会复制素材/写配置的 `DiPlayBootstrap.ensure`。它不代表 iPhone 已接受认证，也不提供新 MFi 身份。
- `AirPlayPersistence.peekWirelessHotspotMode` 复用模式归一化，仅读取、不执行一次性迁移。
  手动热点复用 `ManualHotspotValidation`，合法开放热点不能报密码缺失，含 NUL 配置不能通过。
- 已有会话时不把蓝牙关闭/热点开启误判为应修复项，避免与 test12 的 2.4GHz 交接冲突。
- 不发网络请求、创建热点、开关蓝牙、打开/认领 USB、申请权限或分配解码器。
  **不能调用 `VpnService.prepare` 做检测**：它在 Android 9 可直接撤销另一 VPN，故固定
  “连接时验证”。正式有线连接原授权流程保留。
- 环境检测结果自动附加到原「导出全部连接日志（有线＋无线）」文件，不新增单独日志导出。
  只输出项目 ID、状态、说明和读取失败类型；不输出热点名/密码、手机名/MAC、证书或密钥。

经验与来源：2026-10-05 核对 [AOSP9 VpnService.prepare](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/core/java/android/net/VpnService.java)
及 [Vpn.prepare/prepareInternal](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/services/core/java/com/android/server/connectivity/Vpn.java)。
名为 prepare 的“查询”可能有副作用，应先看平台实现；检测必须与真实启动的权限申请分开。
无线条件复用项目实际 API28 启动链，并对照 [Android LocalOnlyHotspot](https://developer.android.com/develop/connectivity/wifi/localonlyhotspot)
与 [Apple 无线 CarPlay 说明](https://developer.apple.com/videos/play/wwdc2017/717/)。现代 Android 文档不能直接代替 Android9 分支。
界面以领克车机的分区导航和深色层次为方向，自行实现，不引入品牌网站图片或车辆控制入口。

本轮首次测试编译暴露跨 module 访问 internal 模型的问题：将采集器测试放回所属 common
module，在 lynk target 单独验证模式资源与无写入 peek。没有为测试放宽生产接口可见性。
验证结果与 APK 证据见本节后续记录；真实车辆连接仍未因此获得验收。


验证结果：`common` 190 项、`mobile:lynkDebug` 22 项，全部通过；新增 14 项回归。
普通 shared 协议/媒体源码未改，本轮未重跑 shared 全集。Lynk lint 0 error / 8 warning；
末次标题和状态文案调整后再次通过全部 22 项 Lynk 导航/连接相关测试及 standalone 构建。
API28 模拟器以 1920×1080 和 1280×720 检查首页、三个检测分组、滚动、设置分类、连接页；
初次发现侧边四字标签换行，改为「检测」，完整标题和首页按钮保留「环境检测」。
重复检测前后偏好摘要和 Wi-Fi/蓝牙/定位值完全相同；实际导出的同一 txt 同时含环境检测、
USB 历史、无线历史和结束标记。测试导出的 txt 已删除。模拟器无真实蓝牙/USB 主机，
它显示的需处理项不能归因于实车。crash buffer 无记录。

交付 `dist/DiPlay-Lynk-OSN2-test13.apk`，versionCode 30 / minSdk28，47,828,725 bytes；
SHA-256 `61c5873ab040b0ee28efc73a88be7f4e3e87a849703e0000ef9cbde42e8e57a7`。
v2 签名验证通过，签名与 test12 相同，认证素材及 13 个 native 库逐文件一致。
证据保留于 `.private/verification/test13/`；源码和文档未提交、未推送。实车与视觉效果待用户验收。
本轮测试进程已退出，项目构建缓存已清理；按查看界面的需求保留模拟器与新版 App。


### test13a：应用内上游署名（2026-10-05）

用户要求明确标注基于 DiPlay 修改。首页底部常驻「基于 DiPlay 修改 · 领克适配版」，
「设置 → 帮助 → 关于 DiPlay」补充上游项目、作者、修改范围与独立修改版说明，并提供
原项目 GitHub 和上游开源/许可声明入口；原 xcertplay、DiAuto 和依赖项目声明保留。
文案含中文/英文，普通版不显示领克修改说明。署名属于后续 UI 调整应保留的约束。
本轮只改署名展示和入口，构建及 API28 首页/关于页实际显示已验证，未重复跑协议测试。
`dist/DiPlay-Lynk-OSN2-test13a.apk`，versionCode31；同签名可覆盖安装。
SHA-256：`ff472fdf7e9c2124b8c7170b2b34eecbc8ad4e99a4808f12122e5777ac66e510`。
认证素材/native 库与 test13 逐文件相同。构建进程已退出，本轮缓存和远端 XML 已清理。


## test14：整合上游 0.2.12 并同步 fork/main（2026-10-05）

用户明确选择整合 0.2.12 后更新 `soBigRice/LynkCo-DiPlay` 的 `main`。以 `6d8486a`
保存此前 test13a 全部适配，合入上游 `2fc876e578eba3905a5b873e3c2dbd74f498433a`，
保留双方历史。测试版本为 `0.2.12-lynk-osn2-test14` / versionCode32。

- 音频采用上游 API28 属性 fallback 与 `MediaCodecStartup` 释放逻辑；保留 USB 活动配置核验、
  EBUSY 限定恢复和 Opus 启动失败释放。VPN 同时限制本应用 UID、保持 `fe80::/64` 与 IPv4 放行。
- 无线保留本地完整握手预算和真实关闭屏障，整合上游首次 TCP watchdog、分代监听、稳定热点采样
  与 Existing Wi-Fi 双栈。异步交接、热点停止和旧网络回调必须在最终动作的锁内核验 generation，
  不能先检查再无条件清理当前栈。手动热点继续排除上联网卡、STA 别名和 P2P，并拒绝多个 AP。
- 上游 `runStack` 的手机 `syslog_relay` 采集和 256 字节 TLS 实验在领克资源中明确关闭
  (`config_wired_lab_diagnostics=false`)，维持此前有线发送契约和脱敏 IO 统计。
  普通上游 target 保留上游行为；不以整合上游为由宣称实车更稳定。
- 领克面板、可拖动设置入口、只读环境检测和「基于 DiPlay 修改」署名保留。
  日志统一使用 `ConnectionDiagnosticReport`，包含两种连接历史、环境检测、崩溃、进程退出和
  上游显示诊断；BYD 专属诊断仅在资源允许时采集。
- 导出使用上游 API28 私有文件回退与只读 Provider，每次生成独立 URI、只保留最近八份，
  无需系统文件选择器；优先路径为应用专属 `files/diagnostic-reports`，失败回退内部存储。
  所有认证素材、APK、实车日志、模拟器截图和构建缓存均不进入公共 Git。

经验：本机双栈回放的默认 `Socket` 被电脑代理转走 IPv6 loopback；以同端口/双顺序对照确认
`Proxy.NO_PROXY` 可到正确监听器。仅让本地测试直连，保留车机双栈，不通过加超时掩盖问题。
本地回归的 Android9 VPN shadow 同时覆盖 InetAddress builder overload 与新 UID scope。

验证：shared 753 项（1 项既有跳过）、common 580 项、领克 23 项、home 4 项，合计 1359 通过、1 跳过。
mobile 原版/领克、home、maphost 均构建通过；lint 均 0 errors（warnings 分别 19/9/5/2）。
领克最终入口微调后复跑其 23 项测试和 lint 通过；CI 同步加入领克变体的源码测试/构建。
API28 模拟器覆盖安装 test14，核对首页、设置、环境检测、关于署名；实际导出文件同时含
有线/无线历史、环境检测及结束标记。最后隔离比亚迪专属手势提示，重新构建并在 API28 设置页面核验。
未执行实车或 iPhone 连接，仍不能确认无线、音乐暂停与网速问题已解决。

本地包 `dist/DiPlay-Lynk-OSN2-test14.apk`：48,732,152 bytes，
SHA-256 `c787a899e716cb3eb96d77e97a95451b0becdde997750f02ee6bd154b07be079`。
v2 签名与 test13a 相同，本地认证输入逐文件一致；公共 Git 不含这些素材。
旧 test13a 包保留；证据位于忽略目录 `.private/verification/fork-sync/`。
推送目标为 `fork/main`；`origin` 保留原 DiPlay 项目，便于后续比较上游。


## test15：极简双入口与配对复用（2026-10-05）

用户要求首页只保留无线/有线，点入后自动准备连接，只在首次使用时指导手机配置。
`DiPlayActivity.connectionHome` 使用两张整块可点击卡片，右上角进入设置；常用设置中的
热点设置、环境检测、导出日志保留，帮助分类中的关于 DiPlay 保留，首页继续显示修改署名。
不更改画质、音频、协议认证或 Android 蓝牙配对数据库。

调用链：

- 无线卡片 → `startLynkConnection(true)` → `connect(true)`；已有同类型后台会话直接 `openProjection`。
- 设置的“应用并重连”仍由 `connect` 等待现有会话真实关闭；首页返回会话不能覆盖此语义。
- 没有会话时先完成遗留蓝牙恢复；`prepareLynkWireless` 按保存地址（不区分大小写）匹配
  系统 `bondedDevices`，改名仍复用；没有保存地址才自动认出唯一名称含 iPhone 的设备。
  多部手机或无法辨认的设备需选择一次，不能把唯一耳机当作 iPhone，也不擅自替换失效的已选手机。
- 蓝牙关闭时请求系统开启，保留手机记录；无配对时显示手机教程。返回系统蓝牙设置、
  蓝牙开启完成或配对完成后核对真实状态并继续；取消、返回首页、进入设置或改为有线均取消待启动意图。
- 准备完成 → 既有 `CarPlayHostActivity.requestStartupPrerequisites` 申请必要系统权限 →
  Controller 创建临时热点、发送当前凭据并协商 CarPlay。热点手动备用模式仍留在设置中且保留选择。
- 有线卡片 → `connect(false)` → 既有 USB 发现/授权/信任流程。等待页面显示插线教程。
- 连接等待页查看手机教程不会停止会话；只有明确进入连接设置才关闭会话并释放资源。

记录边界：`DiPlayPreferences` 保存所选手机，`AirPlayPersistence` 保存 AirPlay 身份/配对与
USB Lockdown 信任记录。本轮没有清除、迁移或重建这些数据。
[Android 已配对设备接口](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices)
支持按持久地址复用；[API26+ 临时热点接口](https://developer.android.com/reference/android/net/wifi/WifiManager#startLocalOnlyHotspot(android.net.wifi.WifiManager.LocalOnlyHotspotCallback,%20android.os.Handler))
由系统提供本次凭据，API28 不能用该公开调用指定固定名称和密码；因此继续从当次 reservation
发送凭据，不能把旧热点密码当作下次配置。来源核查日期 2026-10-05。

回归保护：本轮复核曾发现将复用放入通用 `connect` 会让音频等设置只保存、不生效，
已将复用限制在首页 `startLynkConnection`；新增现有会话实际关闭后才能重建的对照测试。

验证：common 580 项、领克 33 项（合计 613 项）通过；其中连接提示调整后复跑相关 11 项，
最后的 Activity 重建/自动启动保护后复跑领克全部 33 项。Lint 0 errors / 9 项既有 warnings，
最终独立测试包构建与 v2 签名校验通过。API28 模拟器覆盖安装 versionCode33，实际检查
首页两入口、二级设置/检测/日志入口、无线硬件不足提示、有线等待及取消返回、窄屏纵排滚动。
等待页重复说明已合并。首次/已配对/多设备/蓝牙恢复/取消与重建由自动测试覆盖；模拟器没有
真实蓝牙和 iPhone，因此不能把这些检查当成实车无线成功、音乐暂停或网速问题的验收。

本地包 `dist/DiPlay-Lynk-OSN2-test15.apk`：`0.2.12-lynk-osn2-test15` / versionCode33，
48,893,198 bytes；SHA-256 `f69ee9c5c64b6f7150941dd259181efd3085caa7015bf4edbebd8868a7499648`。
签名与 test14 相同，可覆盖安装保留配置；两份本地认证输入、13 个 native 库与 test14 逐文件相同。
证据位于忽略目录 `.private/verification/test15/`，旧 test14 包保留作回退。
代码未提交、未推送，等待 UI 与实车验收。

本轮模拟器与 Gradle 测试进程已退出，项目构建缓存、临时 UI XML 和 Python 缓存已清理；
保留最终测试报告、截图、安装包及本地认证输入。最终模拟器 crash buffer 为空。


## test16：有线音源交接、音频焦点与完整诊断（2026-10-05）

证据：用户提供 `DiPlay-20261005-202029-084-6383406262520591076.txt`，确认原车音乐有声，
有线 CarPlay 同时连接车载蓝牙，点击播放立刻暂停；20:20:19 是主动拔线/退出，不作为自动断线故障。
日志中 Oct3/4 历史与 Oct5 分开：Oct5 无线均停在热点准备阶段，晚间自动热点 generic error，
手动模式无就绪 AP 接口。下午音乐状态多次在约 0.5–1.3 秒变为暂停；晚间没有音乐流，仅两段短音频。
20:17:46 有线 AA05 认证成功，20:17:53 手机发出 `disableBluetooth`，旧实现仅无线处理。
蓝牙失焦发送 AVRCP PAUSE 与现象吻合，但没有领克系统事件证明它就是唯一根因。

本轮授权：用户在上述只读结论后要求继续处理。实现边界：只交接目标 iPhone 的蓝牙媒体，
保留无线/有线双入口、配对、HFP、画质和 USB 链路；没有关闭全车蓝牙，没有请求新系统权限。

- `WiredBluetoothMediaHandoff` 使用 Android 9 已有 A2DP Sink profile11；只有命令目标地址
  明确匹配已连接设备才请求断开。未知/不匹配、OEM 隐藏接口拒绝或 proxy 不可用均记录原因，
  不猜测插线手机，不扩大为全局蓝牙关闭。请求受理与实际断开分开记录。
- 取消先失效 owner，实际断开与最终检查共享短同步区；等待与恢复在独立工作线程，controller
  teardown 等待收尾后才允许新控制器。正常退出只尝试恢复 App 断开的目标；已有其它媒体
  设备时不抢占。不修改持久化连接优先级；进程被强杀后的自动重连依赖系统，未承诺恢复成功。
- 领克焦点由 `AudioFocusCoordinator` 唯一管理，`CarPlayMediaKeys` 继续转发媒体键/元数据，
  真实播放转移委托 sink 重获焦点。未保存设置时领克默认开启，显式保存 false 保留；普通版
  仍用原路径。请求代号隔离迟到回调；领克失焦本地静音而非向 iPhone 发暂停，GAIN 恢复。
  单独导航取瞬时可 duck 焦点，导航与音乐同播不重复请求；通话/助手具有更高优先级。
- 统一报告新增焦点请求/变更/所有者、系统音量/静音/mode、BUS 路由、A2DP 状态、PCM 非零
  采样数/峰值、实际内存/CPU核数。仅计数，不导出音频内容或手机地址；音频日志改为有界异步写入，
  避免 UI 焦点回调或音频线程同步写磁盘。导出仍有限等待队列排空。
- 热点日志在准备前、失败与导出时分别记录 OEM AP getter/sticky 原始值、错误数值和接口拓扑。
  领克“开关已开”只标待验证。没有放行 eth0.11 等上联网卡，也没有以全局关闭原车热点来兜底。
  generic error 的系统/驱动根因仍需这套证据，不能声称本版已修复无线连接。
- 视频统计将旧 `shown` 更名 `released`，新增真实呈现回调、队列耗时；触摸下一帧指标超两秒
  丢弃陈旧样本，避免静止画面制造几十秒“响应延迟”。窗口帧耗时与解码独立记录，Activity
  停止后关闭监听/线程。不降低 H.264/HEVC、帧率、分辨率或丢弃额外内容来掩盖卡顿。

资料核对：Android 9 r1 的 [A2DP Sink 焦点与远端暂停](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/android-9.0.0_r1/src/com/android/bluetooth/a2dpsink/A2dpSinkStreamHandler.java)
和 [profile 操作权限](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/android-9.0.0_r1/src/com/android/bluetooth/a2dpsink/A2dpSinkService.java)；
[Android 音频焦点](https://developer.android.com/media/optimize/audio-focus)。只采用项目已有媒体实现
及平台能力，不引入另一套 CarPlay 引擎。领克 OEM 可能隐藏或替换标准蓝牙/Wi-Fi 服务，反射失败不能等同成功。

自动验证、包校验及清理结果见下方交付记录；实车音乐、无线、通话、主观流畅度尚待用户正常用车时验收。

交付验证：shared 767 项（766 通过、1 项原有 socket 用例跳过）、common 581 项、领克 34 项，
共 1,381 项通过、0 失败；补跑后的 shared 全量包含退出期间禁止迟到蓝牙断开的 API28 回归。
Lint 0 errors / 9 warnings，与 test15 相同。首次测试编译遇到隐藏焦点监听器 getter 无 SDK
声明，改为仅测试内反射后通过；没有改变运行代码来规避检查。

独立包 `dist/DiPlay-Lynk-OSN2-test16.apk`：`0.2.12-lynk-osn2-test16` / versionCode34，
47,473,333 bytes；SHA-256 `cba46e9eebb8bd615f96572689a1d498052c8597105f91048600895e44f02b28`。
minSdk28、原领克包名、v2 签名校验通过；签名证书与 test15 相同。两份本地认证输入和
13 个 native 库均与 test15 逐文件一致，旧包保留。认证组件存在不代表当前 iOS 的无线认证通过。

API28 模拟器实际完成 test15→test16 覆盖安装，首次启动前四份配置文件 hash 不变；检查
首页双入口、设置、环境检测、音频焦点开关、有线等待、断开连接和实际导出文件。报告中已
出现音源状态、实际 RAM/CPU、AP 原始来源/网卡拓扑、renderer 焦点所有者和窗口帧耗时。
本轮 crash buffer 为空；报告保留的 10 月 4 日 `SyntheticDiagnosticSmoke` 是旧诊断测试样本，
不能误报为 test16 崩溃。模拟器没有 iPhone、蓝牙和车载 BUS，未验证扬声器声音、真实 A2DP
交接、无线、CarPlay 视频呈现或实际流畅度。

验证证据保存在忽略目录 `.private/verification/test16/`。已关闭本轮模拟器和 App，确认没有
残留 Gradle 测试/daemon 或模拟器进程；清理 common/shared/mobile build、项目 .gradle/.kotlin、
临时 UI XML 和模拟器本轮导出副本，保留安装包、报告、截图及本地认证输入。
源码和文档保持本地未提交，未推送。下一次正常用车时先验证有线播放/暂停/恢复和声音，
再尝试一次无线，结束后通过「设置 → 导出日志」提供一份合并报告即可。


## Android 9 车机专项审查（2026-10-05，修复前基线）

### 用户纠正与后续工程基线

用户明确要求：以 Android 车机接收端的完整适配为目标，逐层审查上游及本地实现；上游可以复用，
不能作为正确性证明。后续先核对会话归属、平台能力、失败恢复和资源生命周期，再决定复用或修改。
测试数量、可安装、能显示一次画面，都不能代替这些契约或实车验收。审查到的缺陷也不能直接当作
10 月 5 日车辆症状的唯一根因。已确认的配对、身份、包名、画质及简洁交互仍是保护项。

目标仍是 OS N 2.0 / API28 的普通 Android 应用。Android Automotive OS 的系统 CarService 权限、
厂商 audio policy / BUS 路由和私有热点服务不能因包内有相关 SDK 就视为可用；没有领克实证的
接口保持“未知/不支持”，不让用户遍历数字音频通道来猜配置。领克型号资料不能替代运行时能力。

此前遗漏：主要验证了成功路径、单会话和局部失败，没有系统覆盖双会话相互隔离、Activity
重建期间的资源移交、系统 pending 请求排他和 native I/O 结束语义。后续本地验证优先覆盖这些
边界；test16 的 1,381 项通过仍是既有范围的有效结果，不能扩张为下面这些契约已通过。

### 有代码证据的问题

审查范围：Host/后台会话/前台服务 → Controller → AirPlay media engine → Android sink；
USB NCM/VPN；临时/手动热点 → Bluetooth/iAP2 tunnel。未全面审计密码学、认证素材可靠性、
所有 AirPlay 扩展、厂商固件和硬件驱动。以下行号用于定位，后续以符号为准。

| 编号 / 证据层级 | 触发与实际行为 | 修复边界及验证方向 |
| --- | --- | --- |
| A1 / 已复现 / P1 | `CarPlayMediaEngine.onSessionClosed`（334–345）对所有 session 执行 `audioMeta.clear()` / `pendingMicrophone.clear()`；关闭没有音频的探测连接也清掉在播连接的时钟记录。它只关闭 sockets，没有调用该会话的 `sink.onAudioStopped` / `onMicrophoneStopped`。 | 资源注册和释放必须按 session + stream + generation；关闭探测会话不影响在播音频，关闭所属会话释放音轨/麦克风/模式。不能只把 clear 换成 filter 而忽略 sink 的归属。 |
| A2 / 代码路径 / P1 | `AudioStreamId` 只有 type/audioType，视频 sink 只按 type；engine 虽按 session 建键，下传时丢失 session。旧流迟到回调或 TEARDOWN 能影响相同类型的新流。 | 协议会话身份贯穿 engine/sink/媒体键/回调；验证同 type 的新旧会话交叠及旧包迟到。涉及跨模块契约，需确认后实施。 |
| A3 / 代码路径 / P1 | `AndroidMediaSink.close`（370–395）、`AudioRenderer.close`（898–901）、`VideoDecoder.close` 只发出退出请求。Host 的 restart 在 `oldSink.close()` 返回后即可创建新会话，未等到 worker finally 释放音轨、焦点、codec。`AudioTrack.WRITE_BLOCKING` 的 native 等待不能靠 Java interrupt 保证解除。 | 关闭完成应覆盖真正的媒体资源释放；显式使 native 写入退出、拒绝旧会话新工作，并异步等待完成，UI 不阻塞。使用卡住写入/延迟释放/重复关闭的故障注入。 |
| A4 / 代码路径 / P1 | `AndroidMediaSink.writePcm`（1361–1366）对负错误只记数后结束当前包；`ERROR_DEAD_OBJECT` 后下一个包仍写旧 track。worker 异常退出后 renderer 也仍可留在 map，后续持续入队而无消费者。 | 按错误类别进入可见失败/有界重建，不无限继续写失效对象；当前 session 才能恢复。验证 DEAD_OBJECT、初始化拒绝、永久失败、取消期间恢复。Android 明确要求失效 track 重建。 |
| A5 / 代码路径 / P1 | Host `restartCarPlay`（4262/4283–4296）保留旧 Activity owner、清空 snapshot；若此时 Activity 被销毁，完成回调因 isDestroyed 不再推进。新 Activity 在 `maybeStartCarPlay`（4181–4183）每 500ms 等待无法取得的 snapshot。另 onDestroy 关闭日志，而复用 sink 的音频回调继续持有旧 closed 日志。 | 长寿命会话/诊断由前台服务管理的 runtime 持有，Activity 只订阅状态和绑定 Surface；测试重连中 recreate、后台恢复、销毁后导出，不以延时重试解决 owner 丢失。 |
| A6 / 已复现 / P2 | `CarPlayVpnService.onBind`（68）对系统 SERVICE_INTERFACE 和应用内部绑定均返回 LocalBinder，替换了 Android VpnService 用于 onRevoke 的 Callback Binder。 | 保留框架系统绑定入口，内部绑定另行分流；撤销权限时停止本次 USB/VPN 资源，恢复要重新核验授权。不能依赖底层读错误偶然完成清理。 |
| W1 / 代码路径 / P1 | Controller 1416 的通用 tunnel handler 被 engine 209/217 捕获，278 延后调用；`startWirelessTunnelControl` 1497–1515 读取全局上下文、无锁发布 channel，最后才读取 generation。旧 SETUP 可跨重连使用新配置并覆盖当前 channel。 | handler 创建时捕获不可变 owner/context；锁内核验并唯一发布。保留旧 handler，重连后释放 latch，必须拒绝它。 |
| W2 / 平台契约 / P1 | `LocalOnlyHotspotManager.close`（315–334）只能释放已取得的 reservation，等待方 394 提前返回；系统未完成的请求仍可能存在，新 manager 再申请。AOSP28 同 PID 只能有一个 outstanding LOHS 请求。已有迟到 reservation 测试没有模拟这个限制。 | 进程内热点请求统一归属，pending 也要有结束语义；不能把 close 返回视作系统取消成功。公开 API28 没有可默认使用的 pending cancel，隐藏接口不作为必然能力。 |
| W3 / 平台契约 / P2 | `CarHotspotStatus.read`（53–57）先信泛 AP getter，再检查 sticky mode。getter=13 + mode=2（LOHS）会返回 true，领克 `LocalOnlyHotspotManager.start`（84）据此误报已有共享热点。AOSP 拒绝冲突依据是 TETHERED，已有 LOHS 可以共享。 | 分开“AP 开启”和“共享热点模式”；多来源矛盾不能提升为确定事实。纯逻辑向量 + 同 PID 请求状态回放，无需实车验证分类代码。 |
| P1 / 设计耦合 / P2 | 本地 test16 将 `config_simple_connection_flow` 用于焦点默认值/协调策略；用 `LynkLocalHotspot.supported` 控制有线 A2DP 交接。界面、热点能力和音频策略并非同一条件。 | 由明确的车机 profile/能力结果选择音频和无线行为，UI 只读展示；不同 API/ROM 组合测试，不再通过界面开关间接改变硬件策略。 |

A1/A2/A3/A4/A6、W1/W2 的相关机制在上游 0.2.12 `2fc876e` 已存在；A5 的 Activity owner
结构来自上游，本地关闭屏障没有补上 Activity 销毁分支。W3 reader 来自上游，领克拒绝分支在
`6d8486a` 已存在。P1 为本地 test16 新增，必须与上游缺陷同样审查，不能只归咎于第三方。

另有 USB 性能风险，尚未测量确认与实车卡顿相关：`NcmUsbBridge.send`（69）仍使用
bulkTransfer，`Ipv6NcmBridge.WRITE_TIMEOUT_MILLIS` 为 2,000ms。AOSP9 JNI 在整个 bulk transfer
期间持有 GetPrimitiveArrayCritical；旧实现只对 bulk IN 避开此路径。后续按双向 I/O 与真实
GC/传输耗时验证是否改为 direct-buffer 异步写，不能先承诺帧率改善或仅把超时调小。

### 官方契约核查

- [AudioTrack.write / ERROR_DEAD_OBJECT / 阻塞写退出](https://developer.android.com/reference/android/media/AudioTrack)：负值有不同含义，失效对象需要重建。
- [AOSP9 AudioTrack.cpp](https://android.googlesource.com/platform/frameworks/av/+/android-9.0.0_r1/media/libaudioclient/AudioTrack.cpp)：write 的 kForever 等待与 pause 的 mProxy interrupt。
- [AOSP9 VpnService.java](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/net/VpnService.java)：SERVICE_INTERFACE → Callback Binder → onRevoke。
- [AOSP9 USB JNI](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/jni/android_hardware_UsbDeviceConnection.cpp)：bulk_request 199–217 的数组 critical 区。
- [API28 WifiServiceImpl](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-28/+/refs/heads/androidx-autofill-release/com/android/server/wifi/WifiServiceImpl.java)：单 PID pending 请求、共享 LOHS 与 tethered 冲突条件。
- [AOSP9 WifiManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/wifi/java/android/net/wifi/WifiManager.java#L1903-L1943)：pending cancel 是隐藏 API。
- [Android Services](https://developer.android.com/develop/background-work/services)：后台会话的组件生命周期与主线程边界。

上述 AOSP 条件是平台参考，不保证领克固件逐项相同。引用核查日期 2026-10-05。

### 审查阶段验证与代码状态

通过外部临时 sourceDir 加载 `Android9AuditProbeTest`，运行实际 API28 Robolectric / 现有 engine，
未改业务源码。4 个用例中正常 audio SETUP 对照通过，3 个契约断言明确失败：

1. 关闭无关 probe 后，在播 session 的 feedback 音频条目由 1 变 0。
2. 关闭音频所属 session 后，sink 收到的停止列表为空，预期包含该音轨。
3. 系统 VPN action 与应用本地 action 得到同一个 LocalBinder，未保留框架 Callback。

这些是刻意揭示当前缺陷的失败，不是修复通过，也不是车辆根因的复现。其余项目为代码与平台
契约审查，未运行硬件故障注入。本轮没有启动模拟器、连接车辆、改认证素材、生成 APK 或提交/推送。
复现输入、XML 与执行日志保存在忽略目录 `.private/verification/android9-audit/`，后续修复时应
转为版本化回归测试。最初临时源码未被 java.srcDir 收录；按 AGP9 的 kotlin.srcDir 修正加载，
并使空 feedback 的断言明确报告 0 条目后，得到上述三项断言失败。没有修改断言目标或运行代码。
收尾校验：common/shared/mobile 下 703 份源码与审查前 hash 一致；仅更新本说明和项目地图。
临时 sourceDir 已删除，保留可重放用例及 XML；本轮 shared/build、项目 .gradle/.kotlin 已清理，
没有残留 Gradle daemon/worker 或模拟器进程。test16 APK 未改变。

### 已确认的实施范围

建议保留已验证的协议编解码和现有 UI，把本次改动集中到三个相连的工程边界：

1. **会话与流归属。** 统一 session/stream generation，所有进入、回调、关闭和诊断都按 owner；
   把存活会话和关闭完成移到前台服务管理的 runtime，Activity 仅发命令、订阅状态、绑定 Surface。
   先补双会话、重连中窗口销毁、迟到回调的失败用例，再移动现有逻辑，避免再造一套并行连接引擎。
2. **Android 媒体输出。** 统一媒体资源生命周期、音源/焦点与失效恢复，关闭完成包含 native
   音轨/解码/录音的释放；新增故障注入覆盖，不通过降低画质或静默丢弃异常掩盖失败。
3. **车机平台适配。** 将 Android9/领克能力从 UI 开关中拆出；热点 pending 请求、无线 tunnel
   和 VPN 系统回调各有明确 owner。能力未知时给出实际失败原因；USB 性能修改须先有定量证据。

保持包名/签名、已有配对与设置格式、实验认证来源、无线/有线双入口、视频/音频质量及 BYD 隔离；
不增加系统权限、收费依赖或车控接口。按上述边界分阶段做可回退改动，最终统一验证后再出一个
实车测试包。test16 包与本地状态保留作为比较基线，不将其标成完整车机适配版本。

代价是需要调整 Host/Service/Controller/MediaEngine/Sink 的契约和测试，工作量高于局部修复。
替代方案是只修三项已复现缺陷，改动较小，但 Activity owner、媒体释放屏障和热点 pending
请求仍有已识别缺口，因此不推荐作为最终适配。按用户规范第 3 节，核心架构/跨模块契约调整
用户已明确回复“确认，按照你的建议来改”，实施结果与验证见下一节。


## test17：会话所有权与 Android 9 资源生命周期（2026-10-05）

### 当前调用链和不变项

- `CarPlayHostActivity` 生成不可变 `CarPlaySessionPlan`，经 `CarPlayBackgroundSession.start/restart/stop`
  发命令。runtime 持有 Controller、AndroidMediaSink、日志、重连预算及前台服务代号；窗口只保留
  weak Binding 与 Surface。重建窗口回放当前状态，不重置日志或重新配对。布局/权限未稳定时延迟
  下一次安装 plan，窗口销毁后仍可按最后有效 plan 完成关闭/重连。
- `CarPlayBackgroundSession.beginClose` 先撤销当前 generation → `Controller.close/whenClosed` →
  `sink.close/whenTerminated` → 安装 pending plan 或完成 stop。4 秒仅提示等待；不靠超时转移资源。
  多次 stop 合并，取消覆盖待重连，旧服务销毁不能停止新服务 epoch。释放失败保存可回放终态，
  结束 stop 回调但隔离本进程后续资源创建，避免“失败即重新打开”。
- `CarPlayMediaEngine` 为 session/stream 每次 SETUP 分配 `MediaStreamOwner`；`AudioStreamId` /
  `VideoStreamId` 传递至 sink。旧包、旧 EOF/TEARDOWN 只处理所属资源；无关 probe 不清空正在播放的
  feedback。sink 的同类型替代输出等待前一输出实际释放。重连中已退役 worker 也包含在关闭屏障。
- `AndroidMediaSink.AudioRenderer` 对 `ERROR_DEAD_OBJECT` 在同一 renderer 内最多重建一次，保持
  采样率/声道/缓存策略；其他负错误或再次失效进入终止并移除 renderer，迟到 RTP 不能复活它。
  关闭先 pause 解除阻塞写；写入返回后再次检查取消，不重新 play。VideoDecoder 在取消后不呈现
  返回的旧 frame，关闭完成包括 native codec、presentation thread 和 keyframe command worker。
  MicrophoneUplink 的完成由采集线程释放录音资源后报告，不以固定 join 时间代表释放完成。
- `HeadUnitProfile` 从独立 `config_lynk_osn_profile` 选取焦点策略、API28 有线蓝牙交接，与简洁 UI
  或热点能力开关解耦。实验认证输入、已有设置/配对格式、包名、签名、画质、双入口和 BYD 隔离不变。

### 无线和系统边界

`LocalHotspotRequestBroker.process` 持有一次 pending/reservation ticket。窗口或 controller 关闭时，
尚未回调的系统请求仍占据该 slot；迟到 reservation 先关闭，才允许后继申请。等待方超时/取消
不冒充系统已取消。单 PID outstanding 拒绝后明确阻止重复请求。API28 的公共 reservation.close
会在框架内吞 binder 异常，不能声称检测到固件已释放；若系统始终不回调，需要系统/进程恢复，
不能在普通应用里承诺强制清除。接口基线在获得 ticket 后采样。

`CarHotspotStatus.readTethering` 仅以明确 TETHERED mode 判断共享热点冲突，LOHS 或泛 AP enabled
不误报；原 `read` 继续服务已有手动热点检测。`CarPlayVpnService.onBind` 区分系统 Callback Binder
和应用 LocalBinder，`onRevoke` 撤销所属 USB/VPN 资源。

Controller 在发布 type130 handler 时捕获不可变 `WirelessTunnelOwner`（代号/身份/endpoint/MFi）；
锁内验证并唯一发布 channel，允许合法 SETUP 先于 RECORD。关闭先在锁内撤销所有权、摘下本轮
资源，再锁外关闭和 join，避免 artwork 回调与关闭线程互等；旧 handler 不能借用新认证上下文。
这些改动解决代码和 AOSP 契约缺陷，不证明领克固件已具备可用 LOHS/无线 CarPlay 能力。

### 回归和接管入口

- `Android9MediaOwnershipTest`：双 session、probe、旧流回调/关闭及 VPN 系统 Binder。
- `CarPlayRuntimeLifecycleTest`：关闭超过提示阈值、窗口移交、重入 stop、取消、服务 epoch、释放失败。
  原 Activity 私有 teardown 测试由这些 runtime 契约测试替代，显示尺寸/权限/设置流程仍独立回归。
- `AudioOutputLifecycleTest` / `VideoOutputLifecycleTest`：失效音轨、阻塞写/延迟 native release、旧帧
  不呈现、后继 codec 等待和 keyframe worker 关闭屏障；`TelephonyMicrophoneTest` 等待真实完成信号。
- `LocalHotspotRequestBrokerTest` / `CarHotspotStatusTest` / `WirelessStartupControllerTest`：单 PID 请求、
  迟到释放、模式分类、旧 tunnel owner 和并发唯一发布。测试的模拟 API 不等同 OEM 实机验证。

本轮纠正：初次移动 runtime 曾提前使用布局未稳定时的 plan，回归发现后恢复布局/权限门禁；
同一释放失败必须先保存 runtime status，再由当前 UI owner 展示，不能用已失效 controller generation
丢弃终态。后续修改此链路先运行上述用例。USB bulk OUT 性能风险仍未实测，未改传输实现，未降低
视频参数；音频 BUS 扬声器是否可闻、iPhone 联网、无线成功和实际流畅度仍须正常用车时验证。

最终自动验证：shared 787 项（786 通过、1 项原有跳过）、common 592 项、领克 32 项；
合计 1,410 项通过、0 失败。最后补充 tunnel 终态状态的 generation 后又运行 8 项 API28/29
Controller 回归通过。Lint 0 errors / 10 warnings：9 项既有，1 项为资源覆盖的 UnusedResources
提示；`HeadUnitProfile.read` 直接引用 shared `R.bool.config_lynk_osn_profile`，运行代码有真实用途，
未关闭规则或删除资源。故障注入基于 Robolectric，并非物理 native/HAL 的实测。

独立 APK `dist/DiPlay-Lynk-OSN2-test17.apk`：versionCode35 / `0.2.12-lynk-osn2-test17`，
47,571,693 bytes；SHA-256 `aa55a1465f8a5dc217fe9fc89947efc4ad7159e9220b73a74c881dac605b080e`。
minSdk28、原领克包名、v2 签名校验通过；证书与 test16 一致，两份认证输入和 13 个 native
库逐文件一致。存在认证组件不等于当前 iOS 接受无线认证。

API28 模拟器重新安装 test16 作为比较基线，再覆盖安装 test17；首次启动前已有的 1 份偏好
文件 hash 一致。未在模拟器放入真实配对资料，配对保持另由持久化回归和源代码边界验证。
实际操作了首页双入口、麦克风/VPN 授权、有线等待和重试、前后台恢复、取消返回、无线缺少
蓝牙能力提示、设置、环境检测和导出；报告同时含 USB 日志和无线入口失败，crash buffer 为空。
前后台切换保留同一前台 ServiceRecord，取消后服务列表为空。试用开发者选项未触发 Activity
销毁，仅观察到 STOPPED，已恢复设置；窗口销毁/重建边界的证据来自 runtime 自动测试，不冒充
模拟器完成了该场景。模拟器无实际 iPhone/A2DP/车载 BUS，未验证声音、无线投屏或真实流畅度。

证据位于 `.private/verification/test17/`；本轮模拟器、构建进程和项目生成缓存清理后保留 APK、
测试 XML、日志、截图及本地认证输入。源码保持本地未提交，未推送。test16 保留为比较基线。
下一次正常用车时覆盖安装即可：先有线播放/暂停/恢复及触摸交互，再尝试一次无线；若有问题，
结束后用「设置 → 导出日志」提供同一份报告。不要求反复启动车辆来试不同参数。


### test17 用户实车反馈（2026-10-06）

用户确认“CarPlay 可以正常有线使用了，流畅度还可以”，并明确要求提交全部代码。
此反馈对应本轮交付的 test17，确认范围为有线正常使用及主观流畅度；无线、通话与更长时间
稳定性未获得新的专项反馈。此前“有线/流畅度尚待验证”的说明是交付当时的历史状态。
提交包括 test15～test17 的连接界面、媒体/平台适配、回归测试和说明；不包含 APK、认证输入、
私人诊断或生成缓存，不推送。保留 test17 包作为后续品牌图标修改的已验收比较基线。
