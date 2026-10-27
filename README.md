# StreamNest

一个开源的 Android IPTV 播放器：聚合可用的直播源、自动测速排序、播放失败自动切换镜像。

**它只做播放与订阅管理，不提供、不存储任何频道内容。** 详见 [DISCLAIMER.md](DISCLAIMER.md)。

## 为什么它不容易"用一段时间就废掉"

公共 IPTV 源会持续失效，这是这个领域的常态，而不是 bug。本项目的应对方式是**不把任何第三方列表当作固定数据源**：

| 机制 | 位置 | 作用 |
|---|---|---|
| **源健康度打分** | `data/health/HealthScorer.kt` | 每次探测得到一个 0..1 分数，用指数移动平均平滑，避免一次抖动就打乱列表 |
| **后台轮转探测** | `work/RefreshWorker.kt` | 每次只探测一小批（最久未检测的优先），既不拖慢也不轰炸源站 |
| **播放反馈回写** | `ui/PlayerViewModel.kt` | 真实播放失败是最强信号，直接降低该地址的分数 |
| **镜像自动降级** | `ui/PlayerViewModel.kt` | 同一频道有多个地址时，失败自动切到下一个最佳镜像 |
| **本地缓存** | `data/repo/ChannelRepository.kt` | 断网或刷新失败时，冷启动仍立刻显示上次的频道表 |

失效的源会被自动排到后面而不是直接隐藏，所以一旦恢复它就会自己浮上来。

## 功能

- 聚合多源：内置 iptv-org 公开索引（可一键关闭）、自定义 M3U/M3U8 订阅、Xtream Codes、本地 M3U 文件
- 频道表：搜索、按分组筛选、可用性排序
- 播放：AndroidX Media3/ExoPlayer，HLS/DASH/渐进式，失败自动切换镜像
- 可选 EPG：XMLTV 节目单，显示"正在播出/接下来"
- 后台自动更新（WorkManager，可设 6/12/24 小时）
- 数据全部留在本机，不上传订阅信息

## 下载安装

1. 打开本仓库的 **Releases** 页面，下载 `StreamNest-release-apk`（推荐）或 `StreamNest-debug-apk`。
2. 或者在 **Actions** 中打开最新一次成功的构建，从 Artifacts 下载 `.apk`。
3. 手机上安装时需允许「安装未知来源应用」。

首次启动会自动启用内置的公共索引并开始刷新频道，不需要你先去找订阅地址。

> 没有 Releases 时，先在 Actions 里手动跑一次 `Android CI`（workflow_dispatch），或推送一个 `v*` 标签由 CI 自动发布。

## 自行构建

需要 JDK 17 与 Android SDK（compileSdk 35）。

```bash
git clone <your-fork-url>
cd StreamNest
./gradlew assembleDebug          # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # R8 压缩版，产物在 app/build/outputs/apk/release/
./gradlew testDebugUnitTest      # 运行解析器与打分逻辑的单元测试
```

本仓库的 CI（`.github/workflows/android.yml`）在每次推送与 PR 上都会运行测试并构建 debug 与 release 两种 APK，随后在模拟器上安装并启动 **release** 产物——R8 混淆规则写错只会在运行期暴露，这一步把它挡在发布之前。推 `v*` 标签会自动创建 Release 并附上 APK。

### 发布签名（可选）

CI 支持 4 个仓库 Secret：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

对应的 Gradle 读取变量是 `SIGNING_STORE_FILE`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。

**没有配置也能用**：此时 release 构建回退到 debug 签名，产物一样可以直接安装，只是不适合上架应用商店。**密钥永远不会进入仓库**（已加入 `.gitignore`）。

## 架构

```
com.streamnest.tv
├─ core/        通用工具（稳定 id 生成）
├─ data/
│  ├─ model/    领域模型：Channel / Stream / PlaylistSource / HealthRecord
│  ├─ parser/   M3uParser（容错）、XtreamClient、XmltvParser（SAX 流式）
│  ├─ source/   HttpTextFetcher 接口 + OkHttp 实现、内置源清单
│  ├─ repo/     ChannelRepository（聚合+去重+缓存）、EpgRepository
│  ├─ health/   HealthScorer（纯函数）+ StreamProbe + HealthRepository
│  └─ settings/ SettingsStore（DataStore）
├─ player/      PlayerEngine 抽象 + Media3PlayerEngine
├─ ui/          Compose：导航、频道列表、播放页、源管理、设置
└─ work/        RefreshWorker + WorkScheduler
```

设计取舍：

- **播放内核**：v1 只用 Media3/ExoPlayer（Apache-2.0），因此整个项目可以保持 MIT，体积也小。`PlayerEngine` 是抽象接口，将来要加 libVLC 兜底（LGPL/GPL，会改变许可证策略）属于**局部改动**，不影响上层。
- **解析器全部容错**：公开播放列表由成百上千人维护，坏行是常态，解析器遇到坏数据只跳过、不抛异常。
- **不引入 DI 框架与注解处理器**：依赖图很小，构造器注入让构建更快、数据流更明显，也减少了构建不确定性。
- **纯 JVM 单元测试**：解析与打分逻辑不依赖 Android，可直接在 CI 上快速验证。

## 合规与来源

- 内置来源指向 [iptv-org/iptv](https://github.com/iptv-org/iptv)（The Unlicense，公共领域）发布的公开链接索引。
- **列表的许可证只覆盖列表文件，不授予任何频道的转播权。**
- 应用为中立客户端：不预置内容、不绕过限制、不上传用户数据；来源可由用户完全自备。
- 详见 [DISCLAIMER.md](DISCLAIMER.md)。

## 许可证

本项目以 [MIT](LICENSE) 发布。

## 致谢

- [iptv-org](https://github.com/iptv-org)（公共领域频道索引与 EPG）
- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media)（Apache-2.0 播放内核）
- OkHttp、Coil、Jetpack Compose、Room-free DataStore 等 AndroidX 组件
