# 1.1.3 移植进度

> 执行规则：每个批次都必须在本文档记录进度；按“记录进度 → 完成移植 → 格式化与构建校验 → 本地提交 → 下一批次”执行，直到全部批次完成。校验失败时先修复，不提交未通过的批次；未经授权不推送。

## 基线

- 初始源码基线：`1.0.8`，`versionCode=9`。
- 目标 APK：`1.1.3`，`versionCode=14`。
- 分析依据：APKTool 资源与 Smali、JADX 反编译结果；不直接复制 JADX 生成的 Kotlin/Java 代码。
- 构建环境：JDK 17、compile/target SDK 33。

## 1.1.3 基线与范围确认

- 已封存基线：`1.1.3`，`versionCode=14`；当时仅整理文档，未新增功能入口。
- 2026 年 10 月 13 日，用户安装原版 1.1.3 APP 实测后确认：前次静态审计列出的相关功能尚未完善，未开放给用户使用。
- 反编译中存在实体、请求或界面代码，不等于原版已提供可用功能；此前将这些差异直接认定为已开放功能的移植遗漏，结论不准确。
- 六个既定移植批次及后续修复构成当前 1.1.3 基线；相关未完善能力改列为下一阶段开发目标，不作为本次提交与推送的阻塞项。

## 批次 1：标签翻译与评论提示文本

状态：已完成

本地提交：`6d79780`（移植标签翻译与评论提示）。

### 已确认

- 目标 APK 使用 `res/raw/tag.json` 保存标签别名及 `zh-Hans-CN` 翻译。
- 标签显示格式为 `原始标签 / 翻译`；未匹配语言或未找到翻译时回退为原始标签。
- 标签筛选结果与标签 Chip 共用同一套翻译逻辑。
- 评论发布成功、失败提示使用资源字符串；异常详情仍然保留。

### 本批次改动

- [x] 添加 `tag.json` 资源。
- [x] 添加标签翻译解析与 Koin 注入。
- [x] 更新标签 Chip 和标签筛选结果显示。
- [x] 将评论成功、失败提示迁移到字符串资源。
- [x] 完成必要的 Kotlin 编译校验。

## 批次总览

| 批次 | 范围                                | 状态   |
| ---- | ----------------------------------- | ------ |
| 1    | 标签翻译与评论提示文本              | 已完成 |
| 2    | 会话列表、消息发送与消息页面        | 已完成 |
| 3    | 通知中心与通知计数                  | 已完成 |
| 4    | 用户资料评论与新建会话              | 已完成 |
| 5    | 下载外部播放、FileProvider 与播放器 | 已完成 |
| 6    | 版本号、依赖与最终构建配置对齐      | 已完成 |

## 批次 2：会话列表与消息

状态：已完成

本地提交：`1d886b1`（移植会话列表与消息发送）。

- 已接入会话分页、before 游标消息查询和发送契约；时间戳使用 InstantSerializer。
- 已完成会话卡片、未读标识、消息刷新、富文本和发送草稿；失败保留草稿并提示，成功清空输入。
- 路由统一为 `conversations/{userId}`、`conversation/{id}/messages`，手机和平板入口绑定登录用户。
- 创建会话的数据契约已接入；用户资料中的创建入口留在批次 4。
- 校验：ktfmt 格式化、`git diff --check`、`:app:compileDebugKotlin` 均通过。
- 消息时间倒序配合反向列表，保证新发送的消息位于底部；取消请求继续抛出取消异常。
- 未运行 APK 或请求生产 API；该限制意味着交互与服务端联调尚未验证。

## 批次 3：通知中心与通知计数

状态：已完成

本地提交：`8aac009`（移植通知中心与通知计数）。

- 计数 DTO 统一升级为 `NotificationCounts`，无旧类型别名；通知实体单独定义。
- 接入 `/user/{userId}/notifications`，完成加载、空状态、错误重试和通知路由。
- `newReply` 导航至视频，`newComment` 导航至资料页；未知类型提示，目标为空时禁止无效导航。
- 手机/平板通知入口绑定登录用户，现有 IndexDrawer 计数刷新路径保留。
- APK 未提供删除或标记已读接口，本批次不添加这些操作。
- 校验：ktfmt 格式化、`git diff --check`、`:app:compileDebugKotlin` 均通过；未运行 APK 或调用生产 API。

## 批次 4：用户资料评论与新建会话

状态：已完成

本地提交：`d0c05f3`（移植用户资料评论与新建会话）。

- 接入契约：
  - `@GET("/profile/{id}/comments") suspend fun getProfileComments(@Path("id") id: String, @QueryMap params: Map<String, String>): Pager<Comment>`
  - `@POST("/profile/{id}/comments") suspend fun postProfileComment(@Path("id") id: String, @Body dto: CommentCreationDto): Comment`
  - `UserRepo.createConversation(title: String, body: String, recipientId: String): ConversationCreatedDto`，路径与请求体的 `user` 均使用收件人 ID，登录身份由现有认证链路携带。
- 关键逻辑与架构：
  - 严格核实实体结构：通过 APK 反编译及实体比对，确认主页评论接口与私信收件人均使用主页用户的真实 `ProfileDto.user.id`，未误用 `username` 或不存在的 `profile.id`。
  - `CommentRepo` 扩充 `getProfileComments` 与 `postProfileComment` 仓库方法，支持多级回复与分页参数映射。
  - `UserVM` 注入 `CommentRepo`，维护 `CommentState` 状态栈，实现 `loadComments/pushComment/popComment/jumpCommentPage/postComment`，并确保仅在 profile 加载成功后触发评论加载；异步异常与错误通过 `events` 暴露，协程取消异常 `CancellationException` 继续抛出。
  - 会话创建逻辑在 `UserVM` 与 `UserPage` 完整闭环：通过现有的 `LocalDialogProvider` 挂载输入弹窗；前置非空校验、提交防重复与 loading 状态保护；成功关闭弹窗并跳转 `conversation/{id}/messages`，失败保留草稿并展示清晰反馈，避免在协程闭包内违规调用 Composable 的 `stringResource`。
  - 主页第三标签将占位 `TodoStatus` 替换为 `CommentList`，`HorizontalPager` 的 `pageCount` 纠正为 3 并与 3 个 Tab 完全对齐；导航栏安全内边距复用 `WindowInsets.navigationBars`。
  - 文本资源：在 `res/values/strings.xml` 中补齐新建会话相关的中文文案。
- 验证限制与暂缓事项：
  - ktfmt 格式化、`git diff --check`、`:app:compileDebugKotlin` 均通过；
  - 留言加载失败可见，切换评论层级取消旧请求；发表评论和创建会话均具有 loading、防重复提交和 finally 清理；
  - 未运行 APK，未调用生产 API。

## 批次 5：下载外部播放、FileProvider 与播放器

状态：已完成

本地提交：`60cb03a`（移植下载外部播放与全屏控制）。

- 范围调整：本批次范围为“下载外部播放、FileProvider 与播放器”，不包含 ACTION_SEND 分享能力。
- 接入契约：
  - FileProvider：`android:authorities="${applicationId}.provider"`，`exported=false`、`grantUriPermissions=true`，`FILE_PROVIDER_PATHS` 指向 `@xml/files_provider`。
  - 路径安全收窄：APK 1.1.3 的 `external-files-path path="."` 覆盖整个外部私有目录，过于宽泛；本批次收窄为 `path="Download/"`，与下载 Worker 实际落盘目录 `getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)`（含 `VIDEO` 子目录）一致，不暴露其他外部私有数据。
  - 设置项：MMKV preference key `setting.download_player_internal`，默认 `internal`，可选 `internal`（内置）/ `external`（第三方）。
- 关键逻辑与架构：
  - 下载列表点击按设置分流：内置播放器维持既有 ExoPlayer 全屏播放；外部播放器通过 `FileProvider.getUriForFile` 生成 content URI，`ACTION_VIEW` + `setDataAndType(uri, "video/*")`，仅授予 `FLAG_GRANT_READ_URI_PERMISSION` 并附带 `clipData` 便于授权，不申请写权限。
  - 外部播放边界错误（`ActivityNotFoundException`、文件缺失、`IllegalArgumentException` 等）通过 `LocalMessageProvider.error` 可见。
  - 下载卡片删除交互改为 Material3 `SwipeToDismiss` 滑动展开删除/恢复操作：滑动本身不删除，显式点击删除按钮才调用 `DownloadVM.delete`，原有删除行为保持不变。
  - TopAppBar 问号按钮显示下载目录路径；卡片文件大小使用 `prettyFileSize`，文件 IO 在 `Dispatchers.IO` 中读取；分页逻辑与列表键（`item.id`）保持一致。
  - 播放器 `Player` 接口新增必填 `fullscreen: Boolean`（不保留旧调用默认值），全屏时控制层应用 `WindowInsets.systemBarsIgnoringVisibility` 内边距；`DownloadPage`、`VideoPage` 全部调用点同步传参。
  - 不添加清晰度 fallback：APK 1.1.3 明确不存在该逻辑；缓存行为与基线一致，本批次不改动。
- 验证限制与暂缓事项：
  - ktfmt 格式化、`git diff --check`、`:app:compileDebugKotlin` 均通过；全屏系统栏 API 已添加局部 ExperimentalLayoutApi 声明；
  - 未运行 APK，未调用生产 API。

## 批次 6：版本配置与最终校验

状态：已完成；全部六个批次的移植与本地构建校验完成

- 版本升级为 `1.1.3`、`versionCode=14`，min/target/compile SDK 保持 26/33/33。
- 依据 APK 内嵌 META-INF 的 version 文件对齐：Compose UI 与图标 1.4.3、Paging Runtime 3.2.0-alpha05、Paging Compose 1.0.0-alpha19、ProfileInstaller 1.3.1。
- Material3 1.1.0-rc01、ExoPlayer 2.18.6、Room 2.5.1、WorkManager 2.8.1 等维持现有版本，不猜测无法明确还原的依赖版本。
- ABI 与 APK 对齐为 arm64-v8a、armeabi-v7a、x86、x86_64；保留本地 Java/Kotlin 17 工具链。
- 最终检查修正三处证据差异：创建会话路径使用收件人 ID；会话头像优先选择对方；通知正文移至内容槽位，图标恢复到 leadingContent。
- 不重写此前批次的提交；最终修正随本批次独立提交。
- 下载列表同步升级至 Paging alpha19 的标准 `items(count, key = itemKey)` 接口，移除旧分页扩展调用。
- 最终复核确认上述三处问题均已修复；“已完成”指既定批次范围，不代表 APK 内部未开放能力已全部实现，也不代替实机及服务端联调。

## 移植后修复与界面调整

- `69fa098`：允许 `Video.user` 为空，修复通知响应中视频作者为 `null` 时的反序列化崩溃，并新增通知反序列化单元测试。
- `4bb4de8`：通知使用紧凑标题与卡片布局，展示关联视频来源，保留无效目标的导航保护。
- `3e4ad0f`：会话列表展示对方昵称、会话标题和最后回复时间，不展示接口中存在滞后的 `lastMessage`，也不增加补查请求；聊天按发送者分组，左右对齐头像和消息，气泡底部显示时间，使用轻量日期分隔线。

## 下一阶段开发目标

这些目标来自 1.1.3 基线整理，不承诺原版已有可用实现。下列前两项已在本轮 1.1.4 开发中完成，其他项目仍待后续安排。

- [x] **作者其他视频推荐**：1.1.4 已接入 `user`、`limit=6`、`exclude=当前视频 ID` 请求与两组标题；支持空作者、加载、空结果、失败重试，并过滤当前视频。
- [x] **下载方式选择**：1.1.4 已提供“APP 内下载”和“打开下载链接”菜单；保留下载权限与重复任务检查，处理无链接及无可用外部应用的错误。
- [ ] **更新服务能力完善**：在接口支持与产品规则确认后，补齐 `VersionMeta.active`、`announcements`、`link`，明确维护或停用提示及后续处理策略、公告展示和服务端更新地址；维护时是否退出应用需在开发前确认，不直接照搬反编译分支。
- [ ] **会话列表下拉刷新**：成功与空列表状态均可主动刷新当前页；复用 `loadConversations()`，保留加载状态、防重复请求和错误重试，不增加用于补查最后消息的额外请求。
- [ ] **多语言资源补齐**：默认资源统一为英文，同步简体中文、繁体中文和日文的会话、通知、评论、新建会话及下载设置文案；新增功能文案同步维护，校验格式占位符一致性。
- [ ] **会话分页栏安全边距**：补齐底部导航栏 inset，并在手势导航与三键导航设备上验证分页控件不被遮挡；此项属于布局完善，不是新增业务功能。

### 保持不变的边界

- 不将恢复原版开发警告、过时提示文案或占位入口本身作为开发目标。
- 保留用户已确认的通知、会话列表与聊天布局，不恢复滞后的最后消息摘要，不增加补查请求。
- 不恢复覆盖整个存储目录的宽泛 FileProvider 授权；1.1.4 的旧系统公共目录共享范围限定为 `Download/Awara/`。
- 1.1.3 基线保持不变；本轮开发版本已确定为 `1.1.4`、`versionCode=15`。

## 1.1.4 开发与打包记录

日期：2026 年 10 月 13 日。状态：实现、单元测试、Release 构建与签名核验已完成；真机交互与线上联调尚未验证。

- 完成作者其他视频推荐与下载方式选择，不实现本轮范围外的公告、维护策略、会话下拉刷新等目标。
- 下载默认保存到公共 `Download/Awara`，不再写入应用的 Android/data 目录。Android 10+ 使用 MediaStore；Android 8–9 仅为默认公共目录申请 `WRITE_EXTERNAL_STORAGE`，权限上限为 API 28。
- 设置页与下载页均可通过系统文件夹选择器修改保存目录，并持久保存 SAF 授权；取消选择不改变配置，可恢复公共默认目录。目录变化仅影响新任务，已排队任务使用入队时的目录快照，不释放旧目录授权，以免其他目录中已完成的新版本下载失去访问权限。
- 用户明确没有旧下载数据，因此不增加旧私有路径兼容或文件搬迁逻辑。数据库升级至 v3，下载记录统一使用 content URI，新增作者 ID、作者名称和实际字节数；保留历史表及既有数据库升级链。
- 下载页默认展示全量列表，可选择按作者 ID 分组，支持时间（最新优先）、名称（升序）、大小（最大优先）排序；偏好会被保存，同名但不同 ID 的作者不合并。
- 内置与第三方播放、文件存在性检查、删除均使用同一 content URI；外部播放只授予临时读取权限。删除失败不丢失数据库记录，取消或失败的任务清理本次创建的半成品文件，已完成并写入数据库的文件不因随后取消而被误删。
- 经用户确认，彻底移除侧边栏随机语录及其专用 UI、API、实体与网络注入，不再请求该第三方服务。
- LeakCanary 2.10 保持 `debugImplementation`：Debug 保留内存泄漏检测，Release 不包含检测组件；此前误删依赖已纠正。Release 网络日志关闭，认证头仅发送给准确的 API 主机。
- 格式检查与编辑器诊断通过；`:app:testReleaseUnitTest :app:assembleRelease` 通过，12 项单元测试全部通过，覆盖文件名安全、受限目录、排序、作者分组及既有通知反序列化。
- Release 已完成 R8 和资源压缩。实际 APK 为 `versionName=1.1.4`、`versionCode=15`，非 debuggable，包含四种既有 ABI；DEX 核验无 LeakCanary。
- 签名：按用户明确选择使用本机 Debug 证书签署 Release，APK Signature Scheme v2 核验通过。这是非正式发布签名，不能覆盖安装签名不同的原版 APP，不包含正式发布密钥。
- 安装包：[Awara-1.1.4-release.apk](../apk/Awara-1.1.4-release.apk)，大小约 6.86 MiB；原版 APK 未覆盖。
- SHA-256：`5870a29c101cdb32364f27f11c1225a9ddedb632343c04e5e992afe4b837efdf`。
- 没有连接的真机或模拟器，本轮未安装运行 APP、未调用生产 API、未提交或推送；目录授权、真实下载、播放与界面仍须安装后实测。
- LeakCanary 恢复后，`:app:testDebugUnitTest :app:assembleDebug` 通过，12 项 Debug 单元测试全部通过；新 Debug APK 为 `1.1.4 / 15`，DEX 确认包含 LeakCanary。此前交付的 Release APK 未改变，DEX 仍不含 LeakCanary；签名证书类型不改变 Debug/Release 依赖隔离。

## 验证限制

- 发布签名证书、私钥及原工程不可还原的发布自动化配置不猜测、不新增。
- FileProvider 已随批次 5 新增，路径安全收窄为 `Download/`。
- 开发与静态审计过程中未调用生产 API 或运行 APK；用户已自行安装原版进行实测，但当前仓库构建产物的交互、服务端联调和第三方播放器兼容性仍需后续实机验证。

## 构建记录

- 首批改动前编译校验：未执行。
- 2026 年 10 月 10 日：`./gradlew :app:compileDebugKotlin` 编译通过。
- 编译仅保留项目已有的 Compose/Accompanist opt-in 警告；本批次未引入新的编译错误。
- 批次 2～5：每批均完成 ktfmt 格式化、`git diff --check`、`:app:compileDebugKotlin`，通过后独立提交。
- 批次 6：`./gradlew :app:assembleDebug :app:assembleRelease --console=plain --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx4096m -Dfile.encoding=UTF-8"` 通过，包含 Release R8、资源压缩与 lintVital 校验。
- 构建环境限制：首次完整构建受 `_JAVA_OPTIONS=-Xmx512M` 覆盖影响，后续复用 2GB 守护进程在 R8 阶段内存不足；仅当前终端移除该变量并使用一次性 4GB 堆、新进程和单工作线程，未修改系统设置或项目堆配置。
- 产物元数据核验：Debug 与 Release 均为 `versionName=1.1.3`、`versionCode=14`，包含 arm64-v8a、armeabi-v7a、x86、x86_64 四种 ABI。
- Debug 产物：[app-debug.apk](../app/build/outputs/apk/debug/app-debug.apk)。
- Release 产物：[app-release-unsigned.apk](../app/build/outputs/apk/release/app-release-unsigned.apk)，未签名，不可直接作为正式发布包。
- 批次 6 校验时未新增或运行单元/仪器测试，未安装应用、运行 APK、调用生产 API 或推送代码；后续通知崩溃修复已新增单元测试。
- 2026 年 10 月 13 日提交前校验：`:app:testDebugUnitTest` 与 `:app:assembleDebug` 通过（44 个任务均为 up-to-date）；测试报告共 3 项测试，失败和错误均为 0，Debug APK 元数据为 `versionName=1.1.3`、`versionCode=14`。本次未构建 Release、安装应用或调用生产 API。
