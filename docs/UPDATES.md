# 官网与应用更新

核对日期：2026-10-06。官网为 <https://soBigRice.github.io/LynkCo-DiPlay/>，仓库为
<https://github.com/soBigRice/LynkCo-DiPlay>。用户决定先公开官网与源码，完整 APK 暂不发布。

## 当前行为与边界

- `scripts/build_site.py` 从 `site/content.json` 生成中文首页、英文 `/en/`，旧语言路径转到
  相应入口。沿用 Python 静态生成和 GitHub Pages，不增加框架或运行服务。
- `.github/workflows/pages.yml` 在 `main` 的网站内容改变或手动触发时构建并部署 `site/`。
  APK、认证素材、本地配置和构建输出不属于 Pages 发布目录，也不进入 Git。
- 官网和 App 共用 `site/updates/latest.json`。当前 `release: null`，网站提供源码 ZIP
  和版本发布页，App 显示“暂无公开 APK”，不将此状态说成“当前已是最新版本”。
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

## 将来发布 APK

完整 APK 含有可提取的实验认证素材。**当前用户未授权公开完整 APK**；只有重新得到明确
授权后才能上传。Android 签名私钥永不公开，保持现有安装签名，否则无法覆盖更新。

1. 对最终领克 APK 核验包名、唯一递增的 `versionCode`、`versionName`、minSdk 和签名；
   记录 SHA-256，完成适用验证。不要拿上游 DiPlay 或普通源码构建当领克完整安装包。
2. 将已授权 APK 上传到本仓库的 GitHub Release，核验公开资产可下载且散列一致。
3. 最后把 `site/updates/latest.json` 的 `release` 设置为实际元数据，再部署 Pages。例如：

```json
{
  "schemaVersion": 1,
  "packageName": "com.shihab.diplay.lynk",
  "release": {
    "versionCode": 38,
    "versionName": "0.2.12-lynk-osn2-test20",
    "minSdk": 28,
    "downloadUrl": "https://github.com/soBigRice/LynkCo-DiPlay/releases/download/lynk-test20/LynkCo-CarPlay-test20.apk",
    "releaseUrl": "https://github.com/soBigRice/LynkCo-DiPlay/releases/tag/lynk-test20",
    "notes": "填写实际变更和验证范围"
  }
}
```

上例仅解释结构，不表示该版本或 APK 已发布。官网显示名与下载、App 检查均来自该元数据。
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

本轮自动验证：仅本次改动与 `e8f4caa` 基线组成的隔离源码中，16 项更新/设置回归通过，
`lintLynkDebug`、`lintDebug`、`assembleLynkDebug` 通过；构建包不含认证素材，仅用于源码验证，
未作为完整 APK 发布。官网中英文、1440px 桌面与 390px 窄屏已实际渲染，未见横向溢出或缺失图片。
车辆网络、浏览器下载与安装尚未实车验证。
