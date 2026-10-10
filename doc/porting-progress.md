# 1.1.3 移植进度

> 执行规则：每个批次都必须在本文档记录进度；批次完成后先提交代码，再开始下一批次，直到全部批次完成。

## 基线

- 当前源码基线：`1.0.8`，`versionCode=9`。
- 目标 APK：`1.1.3`，`versionCode=14`。
- 分析依据：APKTool 资源与 Smali、JADX 反编译结果；不直接复制 JADX 生成的 Kotlin/Java 代码。
- 构建环境：JDK 17、compile/target SDK 33。

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

## 后续批次

| 批次 | 范围                                    | 状态             |
| ---- | --------------------------------------- | ---------------- |
| 2    | 会话列表、消息发送与消息页面            | 已完成           |
| 3    | 通知中心与通知计数                      | 已完成           |
| 4    | 用户资料评论与新建会话                  | 已完成           |
| 5    | 下载分享、FileProvider 与播放器相关能力 | 未开始           |
| 6    | 版本元数据、依赖与最终发布配置对齐      | 未开始           |

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

- 接入契约：
  - `@GET("/profile/{id}/comments") suspend fun getProfileComments(@Path("id") id: String, @QueryMap params: Map<String, String>): Pager<Comment>`
  - `@POST("/profile/{id}/comments") suspend fun postProfileComment(@Path("id") id: String, @Body dto: CommentCreationDto): Comment`
  - `UserRepo.createConversation(userId: String, title: String, body: String, recipientId: String): ConversationCreatedDto`
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

## 验证限制与暂缓事项

- 暂不修改 `versionName`、`versionCode`。
- 暂不新增 FileProvider。
- 暂不调用生产 API 或运行 APK。

## 构建记录

- 首批改动前编译校验：未执行。
- 2026 年 10 月 10 日：`./gradlew :app:compileDebugKotlin` 编译通过。
- 编译仅保留项目已有的 Compose/Accompanist opt-in 警告；本批次未引入新的编译错误。
