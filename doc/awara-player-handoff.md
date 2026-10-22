# Awara 播放器后续工作

## 当前会话目标

更新日期：2026-10-22。

用户已授权继续播放器优化，并确认允许为当前稳定版 Media3 升级必要编译工具链。2026-10-22 用户实测迁移包未发现问题，并自行在更新 JDK 后构建、测试 Debug 包。迁移与本机配置先分别提交，工作区干净后再推进 B 的前四项；缩略图、解码性能及 D 项仍分阶段实现。本轮未授权推送。

## 当前代码状态

- 项目：`D:/private/awara`
- 分支：`main`
- 本地基线：高刷新率修复提交 `df38f72a056fdb67bcdb77b12d387e86015b6791`；之前的发布提交为 `0e5aefc449cc745ae2f9c4bc3beba3c721a48f77`。
- 版本：`1.2.1` / `versionCode 21`
- 高刷新率修复此前已提交、推送。Media3 迁移已获用户实机验收，作为后续交互改造的独立基线；应用版本不变，不创建标签或 GitHub Release。
- 两个播放器 Builder 迁移后仍显式设置 `C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF`。此前独立 ExoPlayer 验证包已由用户在 iQOO Neo 10 / Android 16 上实测确认有效；2026-10-22 用户确认本轮 Media3 迁移测试未发现问题，未采集逐项设备性能数据。
- 数据库版本仍为 11，生成的 schema 与基线一致；网络/本地 URI、缓存目录与 key、清晰度切换位置及暂停意图、循环和释放逻辑保持不变。
- 用户在 2026-10-22 明确授权将本机 JDK 配置及其余变更单独提交，包括此前保留的 `doc/porting-progress.md` 删除；不要恢复该文件。

## 后续播放器工作

### A. Media3 迁移已完成构建验证与用户实机验收

- 独立版 `com.google.android.exoplayer:exoplayer:2.18.6` 已替换为 AndroidX Media3 `1.11.1`，源码和测试中的旧包引用已全部移除。
- 保留原 umbrella 的播放格式能力：核心播放器、common、database、datasource、DASH、HLS、RTSP、SmoothStreaming 和 UI；decoder/extractor 由核心传递引入。不增加会话、广告或第二种播放内核。
- `StyledPlayerView` 按官方映射替换为 `PlayerView`，保留默认 SurfaceView 与原有 Compose 控制层，不重写界面。
- 官方迁移指南：
  `https://developer.android.com/media/media3/exoplayer/migration-guide`
- 实际 AAR 元数据要求 `minCompileSdk=36`，因此采用 AGP `8.10.1`、Gradle `8.11.1`、Kotlin/Compose 编译插件 `2.2.0`、KSP `2.2.0-2.0.2`、JDK 17。`compileSdk` 升至 36，`targetSdk=33`、`minSdk=26` 不变。
- Room `2.5.1` 在 KSP2 下报 `unexpected jvm signature V`，必要升级至 `2.7.2`；统一 Paging `3.3.2`、Compose 基础库 `1.5.1`。分页数量迁移至 `rememberPagerState { ... }`，瀑布流尺寸返回类型适配为 `IntArray`，原计算与页面行为保留。
- 旧 Accompanist 动画导航与 Compose 1.5 的类型签名不兼容，按官方指南替换为 Navigation Compose `2.7.7` 的 `NavHost` / `rememberNavController`，保留路由与转场配置，并同步仪器测试引用。
- Debug LeakCanary 保留；`.kotlin/` 作为本机构建缓存忽略，不提交 APK、缓存或本地配置。

本轮自动化验证：

- `:app:testReleaseUnitTest`：35 个套件、215 项测试，零失败、错误、跳过。其中播放器聚焦回归 14 项，新增视频尺寸映射测试 1 项。
- `:app:assembleRelease`：包含 R8 混淆的 Release 构建成功。
- `:app:compileDebugAndroidTestKotlin`：成功；没有连接设备或 AVD，未执行仪器测试。
- ktfmt 格式化、`git diff --check`、旧播放器/动画导航引用残留检查通过；只读审查未发现高置信度实质缺陷。
- 验证包：`apk/Awara-1.2.1-media3-migration-test.apk`，7,171,357 字节，SHA-256：`ea6708809ba49a861f5855ed3272bf324dbdc949ca4c351af904184d6b147f0c`。
- APK 为 `1.2.1/21`、非 debuggable、四种 ABI，使用原 Android Debug 证书和 v2 签名；ZIP 完整性与构建产物一致性已验证，10 个旧 APK 未被覆盖。

实机验收后再推进交互：

1. 在线与本地 `content://` / 文件播放，快进/回退，切换清晰度后位置和暂停状态保留。
2. 全屏、旋转、退出与重新进入页面，检查视频显示和播放器释放。
3. 播放与暂停期间观察系统刷新率，确认两个内置播放器均不回归锁 60 Hz。
4. 复查迁移前的缓存可读性，以及首页登录态分页、图集、用户主页和导航返回。

### B. 重做横向拖动进度

当前问题和源码证据：

- `Player.kt` 的 `detectHorizontalDragGestures` 直接把每个事件的 dp 转为 Long 后乘以 `300`，导致灵敏度过高、事件频率依赖和小位移不稳定。
- 横滑期间只更新中央时间文字，不驱动底部进度条目标位置，也没有视频画面预览。
- 当前没有明确的横向/纵向方向锁和顶部系统手势区域避让，用户反馈下拉通知栏可能误触发 seek。
- buffering 时底部 Slider 被替换成不确定的 `LinearProgressIndicator`，短暂 seek 等待会变成明显转圈。

推荐实现顺序：

1. 触摸方向锁：累计位移超过 slop 后再判断主方向；竖向占优立即取消 seek；考虑顶部系统手势区域和非全屏窗口边缘。
2. 以手势开始位置为基准保存 `startPositionMs` 和累计浮点位移，按播放器可用宽度归一化，不在每个事件中取整；对短视频和长视频采用受控灵敏度。
3. 建立统一的 `SeekPreviewState`，由横滑和底部 Slider 共用，包含目标位置、目标时间、是否拖动和预览状态。
4. 拖动时保持进度条可见并跟随目标位置，松手才提交 `seekTo`；短暂等待不要立即显示 loading，超过阈值再显示反馈。
5. 再加入缩略图预览：异步、可取消、按目标时间和媒体 ID 缓存，旧请求不能覆盖新目标；先覆盖本地文件，再评估远端缩略图来源。

### C. 本地 seek 延迟

- 当前 `PlayerState.seekTo` 只调用 `player.seekTo`，没有重建播放器或换源。
- 精确 seek 可能需要从目标前的关键帧重新解码并丢弃中间帧，即使文件在本地也可能短暂等待。
- 先测量“松手到目标首帧”的时间、目标视频容器/关键帧间隔和是否发生重新加载，再决定是否为拖动预览使用较宽松的关键帧 seek。不要未经验证全局改成不精确跳转。

### D. 后续手势控制

在统一触摸仲裁完成后，再实现：

- 左侧竖滑调整应用窗口亮度，退出播放器时恢复原状态。
- 右侧竖滑调整系统媒体音量，不把播放器音量系数误当作系统音量。
- 长按临时倍速，松手、取消、切后台、旋转和离开页面都必须恢复用户原倍速。
- 双击、横滑、左右竖滑、进度条拖动及系统边缘手势之间不能重复消费同一触摸序列。

LoveIwara 的 Flutter `GestureArea` 可作为交互规则参考，但它使用 `media_kit`；手势属于外层交互，不是播放器内核自动提供的能力。

## 建议验证

- 先增加纯 JVM 的位移归一化、方向仲裁和 seek 目标计算测试。
- 再补 Compose UI 测试：竖向拖动不会触发 seek、横滑目标与 Slider 同步、拖动中预览状态可见、取消手势不提交 seek。
- 实机重点测试：下拉通知栏、全屏/小窗、短视频/长视频、本地/网络视频、低关键帧密度视频、快速反向拖动、旋转和切后台。
- 验证高刷新策略不能回归；Media3 迁移后仍需显式保留关闭内容帧率请求的行为，除非用户重新确认匹配内容帧率。
