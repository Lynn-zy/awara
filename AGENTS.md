# Awara Agent Guide

## 构建、验证与测试门禁

- **验证完成判据（Completion Criteria）**：任何代码改动完成后，必须执行并通过 `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintVitalRelease`（与 [ci.yml](.github/workflows/ci.yml) 门禁一致）；涉及混淆规则、资源压缩或签名发版配置时，必须额外通过 `./gradlew assembleRelease`。
- **JDK 路径覆盖**：`gradle.properties` 中的 `org.gradle.java.home` 指向本地固定 Windows JDK 路径；若当前环境路径不同，执行 Gradle 时显式附加 `-Dorg.gradle.java.home="$JAVA_HOME"`。
- **回环网络单测（Tight Loop）**：使用 `MockWebServer` 编写断流、重试或 CDN 换源单测时，必须将服务端绑定至 `InetAddress.getByName("127.0.0.1")` 并使用 `127.0.0.1` 构造请求 URL，规避双栈环境下 `localhost` 反向解析耗时或断流后切入未监听 IPv6 导致的偶发失败。
- **测试资产隔离**：`androidTest` 通过 `sourceSets` 直接映射复用 `app/src/test/resources` 下的合成视频素材；测试素材、`Robolectric` 与 `LeakCanary` 仅限测试或 Debug 变体，严禁进入正式 `release` APK。

## 多语言资源同步

- **四语言强制对齐**：新增或修改任何 UI 字符串资源时，必须在同一改动中同步更新 `app/src/main/res/` 下的全部四个资源目录：`values/`（英文默认）、`values-zh-rCN/`（简中）、`values-zh-rTW/`（繁中）、`values-ja/`（日文），确保资源 key 与语义完全一致。

## 核心架构不变量（Invariants & Gotchas）

### 播放器与流媒体链路（`ui/component/player/` & `data/playback/`）

- **高刷新率保护**：所有 `ExoPlayer.Builder` 实例必须显式配置 `.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)`，防止视频内容帧率请求将设备屏幕锁定在 60Hz。
- **首帧渲染与自动播放解耦**：媒体源就绪后统一调用 `prepare()` 以通过视频渲染器呈现真实首帧；`setting.auto_play` 仅决定 `playWhenReady` 意图，禁止采用“先播放再暂停”或用封面图冒充视频首帧。
- **上游数据源串联顺序**：在线播放固定按 `CDN/HTTP 上游 -> PlaybackReadAheadDataSource (1 MiB 环形队列，暂停时 10 KiB/s 低速保活) -> PlayerCache (1 GiB LRU)` 串联；主动限速预读不计入 CDN 吞吐评分与慢源检测。
- **跨 CDN 共享缓存键**：可信 Iwara 播放 URL 的缓存键（`PlaybackResourceKey`）仅提取文件名、路径与画质，剥离 CDN 主机名与临时签名参数，确保多 CDN 换源、单次 403 签名刷新与独立在线取帧器（`NetworkSeekThumbnail`）共享同一磁盘缓存分片且无需重建 `MediaItem`。
- **本地与在线取帧隔离**：拖动进度条缩略图预览对本地 `file`/`content` URI 使用会话级复用的 `MediaMetadataRetriever`（`LocalSeekThumbnail`）；对在线 HTTPS 视频使用独立 `FrameExtractor` 与只读共享缓存（`NetworkSeekThumbnail`，单次目标网络预算 2 MiB），严禁将 HTTP(S) URL 传入平台 `MediaMetadataRetriever` 或通过 seek 主播放器取帧。

### 下载、存储与缓存清理边界（`data/download/` & `util/AppCacheManager.kt`）

- **缓存清理安全边界**：`AppCacheManager` 与 `PlayerCache`（基于读者租约延迟清理）仅清理 `cacheDir/image_cache`、`cacheDir/player_cache` 及 `cacheDir`/`externalCacheDir` 下的临时文件；绝不清理 `filesDir/download_parts`（下载分块与断点日志）、已下载成品文件、Room 数据库（`awara.db`，Schema 导出于 `app/schemas/`）及 MMKV 偏好设置。
- **分块断点与成品命名**：分块下载（`SegmentedDownloader`）以原子刷盘的区间日志（`DownloadRangeStore`）校验恢复进度，绝不以稀疏临时文件长度判定下载完成。下载成品统一命名为 `视频标题_视频页面ID.mp4`；本地目录扫描导入（`DownloadFolderScanner`）依赖该命名后缀解析 `resourceId`（排除 `1080p60` 等分辨率后缀与系统 `(1)` 重名序号），在本地提取第 6 秒封面并后台限流（并发上限 3）补全作者与元数据。

### 本地用户黑名单（`data/user/UserBlocklist.kt`）

- **过滤范围与主页豁免**：本地黑名单跨账号及未登录态共用，不调用服务端拉黑接口。仅在在线列表层（首页视频/图片、订阅、搜索、收藏、播单详情、视频相关推荐）按作者 ID 精确过滤，不改动服务端分页总数、不自动翻页补满；主动访问被拉黑用户主页、历史记录、已下载列表、评论与通知均保持完整展示与正常播放。

### 签名与发版约定

- **包名隔离与发版门禁**：`debug` 变体携带 `.debug` 包名后缀以支持与正式版共存；正式 Release 签名由本地私有 `keystore.properties` 或 CI 环境变量注入（缺失时回退 Debug 签名供开发构建）。推送 `v*` 标签触发 [release.yml](.github/workflows/release.yml) 时强制校验正式证书与 `versionName` 一致性；本地 `apk/` 目录下的安装包不纳入 Git。发版前在根目录 `CHANGELOG.md` 维护对应 `## [X.Y.Z]` 章节（[release.yml](.github/workflows/release.yml) 优先提取该章节作为 Release 与应用内更新弹窗正文，缺失时自动提取 `feat`/`fix` 提交兜底）。

## Agent skills

### Issue tracker

创建/查询/关闭 Issue、子任务与阻塞依赖或运行 `/wayfinder` 探路图时：使用 GitHub Issues（`Lynn-zy/awara`）与 `gh` CLI 操作（外部 PR 默认不进入分类队列）。详见 `docs/agents/issue-tracker.md`。

### Triage labels

执行 `/triage` 或将分类角色映射为追踪器标签时：使用 `needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix` 五个标准状态标签。详见 `docs/agents/triage-labels.md`。

### Domain docs

探索代码库前、命名领域概念或核查架构决策冲突时：单上下文布局，读取根目录 `GLOSSARY.md` 与 `docs/adr/`（文件缺失时静默继续）。详见 `docs/agents/domain.md`。
