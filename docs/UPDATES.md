# 官网与应用更新

核对日期：2026-10-09。官网为 <https://soBigRice.github.io/LynkCo-DiPlay/>，仓库为
<https://github.com/soBigRice/LynkCo-DiPlay>。用户本轮明确要求公开生产安装包，替代此前
仅公开官网与源码的决定。首个公开包为 R1 / code 43；APK 以非调试 Release 构建提供，
项目仍属实验性质，实车验收范围没有扩大。
中英文官网的“开源与致谢”和页脚均提供 [喵总官网](https://miaozong.cc/) 链接。

## 当前行为与边界

- `scripts/build_site.py` 从 `site/content.json` 生成中文首页、英文 `/en/`，旧语言路径转到
  相应入口。沿用 Python 静态生成和 GitHub Pages，不增加框架或运行服务。
- 官网首页、安装步骤和兼容说明统一声明：有线可用，请用数据线连接车机 USB-A 口；车上
  USB-C 口仅用于充电。无线目前不能正常使用，仍在调试、测试与修复中。此声明来自用户
  2026-10-06 对目标车机的反馈，替代之前“无线仍待实车验证”的表述；不推断其他车型接口能力。
- 官网在下载按钮之前显示高对比度风险摘要，导航、摘要和页脚均可跳转到 `#disclaimer`。
  完整中英文声明来自 `site/content.json`，覆盖驾驶分心、媒体/连接故障、兼容与实验认证、
  安装来源/数据、诊断隐私及第三方权利；已确认的 USB-A/USB-C 和无线状态提示继续保留。
  无担保/责任限制沿用 AGPL §15–17 的适用法律边界，不声称排除依法不得免除的责任，
  也不添加与开源许可冲突的“禁止商用”限制或保证不影响车机保修。
- 2026-10-06 按用户补充，明确“仅为实验项目”“下载安装需谨慎”，并在首页风险框、下载区
  和完整声明显示下载安装/使用表示已阅读、理解并同意声明的提示。作者及贡献者对车机损坏、
  系统异常、数据丢失等损失的免责仅限法律允许范围；依法不得免除的责任继续保留。
  不将网页提示当作免责必然生效的证据，也不改变开源许可证授予的权利。
- `.github/workflows/pages.yml` 在 `main` 的网站内容改变或手动触发时构建并部署 `site/`。
  APK、认证素材、本地配置和构建输出不属于 Pages 发布目录，也不进入 Git。
- 官网和 App 共用 `site/updates/latest.json`。R1 元数据包含 code 43、版本名、最低 SDK、
  实际 GitHub Release/APK 地址、中文/英文说明及 APK SHA-256。官网提供 APK、校验值、
  对应标签源码及发布页；code 42 及此前的领克包可发现更新，code 43 显示当前版本。
- 仅包名 `com.shihab.diplay.lynk` 启用 `LynkAppUpdates`。普通 DiPlay、HUD 测试包和
  Automotive target 不检查这个更新源。
- `DiPlayActivity.onStart` 仅在首页空闲、未运行会话、未准备无线且未启用自动连接时按每天
  一次检查。设置 → 支持及关于页有手动检查、自动检查开关、官网和源码入口。
  更新不修改配对、连接设置、认证、音视频参数或安装包签名。
- `checkAppUpdates` → `LynkUpdateRequest.fetch` → `LynkAppUpdates.parse`：后台读取
  HTTPS 公开 JSON，检查格式、包名、数字 `versionCode`、最低 Android 版本及本仓库 APK URL。
  相同或较旧的 code 不提示更新；要求更高 SDK 时不显示下载按钮。网络失败显示重试信息。
- 检查有连接/读取超时、响应大小限制，无跳转、不附带报告或设备标识。界面停止/销毁时
  取消连接并拒绝迟到结果；成功元数据本地缓存，重开界面可以继续显示已发现的新版。
  自动检查失败后不循环重试，手动检查可随时重试。
- 发现更新后，用户点击“下载新版”打开浏览器；安装由系统与用户处理，不静默安装，不新增
  安装权限。覆盖安装仍需相同包名与签名；若车机没有浏览器，现有回退提示显示链接。

## 发布与维护 APK

完整 APK 含有可提取的实验认证素材。2026-10-09 用户已明确授权公开完整 APK，
官网与 Release 保留实验认证及使用风险说明。Android 签名私钥永不公开；R1 保持已有
test25 签名（最初来自本机 debug keystore），APK 本身关闭调试。改签名会影响覆盖更新，
需单独决策。领克 Release 构建、显式认证/签名输入与 source-only CI 边界见 [BUILD.md](BUILD.md)。

1. 对最终领克 APK 核验包名、唯一递增的 `versionCode`、`versionName`、minSdk 和签名；
   记录 SHA-256，完成适用验证。不要拿上游 DiPlay 或普通源码构建当领克完整安装包。
2. 将已授权 APK 上传到本仓库的 GitHub Release，核验公开资产可下载且散列一致。
3. 最后把 `site/updates/latest.json` 的 `release` 设置为实际元数据，再部署 Pages。完整字段
   以 [公开版本元数据](../site/updates/latest.json) 为准；官网显示名/下载和 App 检查均来自它。
   `notesEn` 与 `sha256` 由网站构建校验和展示，App 忽略这两个新增展示字段；英文网站采用英文说明。

暂时撤下更新可把 `release` 恢复为 null 并重新部署；不删除已有安装或修改用户设置。

## 验证入口

- `common/.../LynkAppUpdatesTest.kt`：无公开包、版本比较、SDK、包名/来源拒绝、缓存、每日检查、
  请求超时配置、HTTP/超限失败与取消。
- `mobile/src/testLynkDebug/.../LynkAppUpdateUiTest.kt`：支持页入口、开关、官网 Intent 和连接设置保持。
- `LynkPanelNavigationTest`：原首页双入口与设置分组回归。
- 网站以浏览器实际检查中英文、桌面/窄屏、安装锚点、源码和发布页链接；模拟器/自动测试
  不代表车机网络可访问 GitHub Pages、浏览器下载或系统覆盖安装已通过。

选择依据：已有静态生成器与平台原生 `HttpURLConnection` 足够完成公开元数据检查，无需
增加第三方更新 SDK 或安装权限。使用 Pages JSON 可以表达“尚未发布”，且避免 GitHub API
限流及混用上游 Release。代价是未来公开 APK 时必须核对并更新这份元数据。

来源（2026-10-06 核对）：[GitHub Pages 工作流](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)、
[Android 网站分发](https://developer.android.com/distribute/marketing-tools/alternative-distribution)。
风险声明依据（2026-10-06）：[AGPL §15–17](https://www.gnu.org/licenses/agpl.en.html)、
[Android 非商店来源安装风险](https://support.google.com/android/answer/9457058?hl=en)，
以及本项目的 [隐私与诊断](PRIVACY.md)、[第三方声明](THIRD_PARTY_NOTICES.md)。
下载声明的提示与责任限制核对：[《民法典》§496–497、§506](https://www.court.gov.cn/zixun/xiangqing/233181.html)
（2026-10-06，最高人民法院公布文本）。

2026-10-06 历史自动验证：仅当时改动与 `e8f4caa` 基线组成的隔离源码中，16 项更新/设置回归通过，
`lintLynkDebug`、`lintDebug`、`assembleLynkDebug` 通过；构建包不含认证素材，仅用于源码验证，
未作为完整 APK 发布。官网中英文、1440px 桌面与 390px 窄屏已实际渲染，未见横向溢出或缺失图片。
车辆网络、浏览器下载与安装尚未实车验证。

## 2026-10-09：R1 公开发布

- 源码标签 `lynk-v0.2.12-r1`，构建源码 `b8308bf432dac71f42e7850bc2217599acadfede`。
  包为 `0.2.12-lynk-osn2-r1` / code 43 / minSdk 28；Release、关闭调试，原包名与签名连续。
- 本地 1,533 项自动回归通过、1 项原有跳过；包含新 Release 和原 lynkDebug 各 41 项。
  Release Lint 0 errors / 12 既有 warnings。已验证缺失签名输入会被 standalone 任务拒绝，
  source-only Release 构建是 unsigned 且不含两份实验认证输入。CI 增加 Release 构建/检查，
  并要求 Release 用例数量大于零，避免 AGP 9 Kotlin 目录配置导致 NO-SOURCE 假通过。
- 最终 APK 两份认证输入、原签名和 CarPlay 返回图标与 test25 相同；13 个 native library
  的打包清单一致，领克资源开关启用、BYD 集成关闭，无 Android keystore 打包。
- [GitHub Release](https://github.com/soBigRice/LynkCo-DiPlay/releases/tag/lynk-v0.2.12-r1)
  提供 `LynkCo-CarPlay-0.2.12-r1.apk` 与 `SHA256SUMS.txt`。APK SHA-256：
  `b62984ed6c2d387c7037ae52b6331204eb7d83197043023cae4c89376741e040`；体积 38705622 bytes。
- 发布后的 APK 实际下载校验在启用共享元数据前执行，Pages 部署后再核对公开页面与 JSON。中文/英文页面
  已检查 1440px 与 390px 视口的下载链接、对应标签源码、校验显示和横向溢出。
  自动验证与下载可用不代表车机网络、浏览器/系统覆盖安装、方向盘、Dock 或无线已获实车验收。

本地最终证据保存在 `.private/verification/lynk-r1/`；认证输入、Android 签名密钥、
测试缓存与安装包不进入公共 Git。必要包保存在 `dist/`，临时浏览器/服务器和本轮生成输出
在收尾清理。签名连续性和测试目录陷阱仅维护于 BUILD.md，不另建重复记录。
