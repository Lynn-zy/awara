# Issue tracker: GitHub

本仓库的 Issue 与 Spec 存储在 GitHub Issues（`Lynn-zy/awara`）中。所有操作均使用 `gh` CLI。

## Conventions

- **创建 Issue（Create an issue）**：`gh issue create --title "..." --body "..."`。多行正文请使用 heredoc。
- **读取 Issue（Read an issue）**：`gh issue view <number> --json number,title,body,labels,comments`。
- **列出 Issue（List issues）**：`gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`，并配合相应的 `--label` 与 `--state` 过滤条件。
- **设置子 Issue（Make an issue a sub-issue of a parent）**：`gh issue create --parent <parent> ...`，或事后执行 `gh issue edit <parent> --add-sub-issue <child>`（需 `gh` 2.94+）。旧版 `gh` 使用：`gh api --method POST repos/<owner>/<repo>/issues/<parent>/sub_issues -F sub_issue_id=<child-db-id>`（数据库 ID，获取方式同下方 **Blocking**）。若未启用 sub-issues，请在子 Issue 正文顶部添加 `Part of #<parent>`。
- **阻塞依赖（Blocking）**：使用 GitHub 原生 **issue dependencies** 作为标准且 UI 可见的依赖表示。通过 `gh issue create --blocked-by <n>,<n> ...` 或 `gh issue edit <child> --add-blocked-by <blocker>`（`gh` 2.94+）添加依赖边。旧版 `gh` 使用：`gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`，其中 `<blocker-db-id>` 为阻塞 Issue 的纯数字 **database id**（通过 `gh api repos/<owner>/<repo>/issues/<n> --jq .id` 获取，而非 `#number` 或 `node_id`）。若原生依赖不可用，回退为在子 Issue 正文顶部添加 `Blocked by: #<n>, #<n>`。当所有阻塞项均已关闭时，该任务单即解除阻塞。
- **统计未关闭的阻塞项（Count open blockers）**：`gh api repos/<owner>/<repo>/issues/<n> --jq .issue_dependencies_summary.blocked_by`（仅统计 open 状态的阻塞项；结果为 0 表示可开始执行）。`gh issue view <n> --json blockedBy` 也会列出已关闭的阻塞项，因此需过滤：`--jq '[.blockedBy.nodes[] | select(.state == "OPEN")] | length'`。
- **评论 Issue（Comment on an issue）**：`gh issue comment <number> --body "..."`
- **添加 / 移除标签（Apply / remove labels）**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **关闭 Issue（Close）**：先通过 `gh issue comment <number> --body "..."` 发布说明，再执行 `gh issue close <number>`。注意对已关闭的 Issue 执行 `gh issue close --comment` 会丢弃评论且仍返回退出码 0。

从 `git remote -v` 推断目标仓库；在克隆目录中运行时 `gh` 会自动解析。

仅向用户自己的仓库（`Lynn-zy/awara`）提交 Issue，切勿提交到 `mattpocock/skills`。在 Fork 仓库中，`gh` 可能会默认指向 upstream 父仓库，因此在每次会话首次执行 `gh issue create` 前，请先通过 `gh repo view --json nameWithOwner` 检查目标仓库，若不是用户本人的仓库需先与用户确认。

## Pull requests as a triage surface

**PRs as a request surface: no.** _（若本仓库将外部 PR 视为功能请求入口，请设为 `yes`；`/triage` 会读取此标志。）_

当设为 `yes` 时，PR 使用与 Issue 相同的标签与状态机，并通过对应的 `gh pr` 命令操作：

- **读取 PR（Read a PR）**：`gh pr view <number>`，随后执行 `gh pr view <number> --comments`（非 TTY 环境下 `--comments` 仅输出评论而不含正文），并用 `gh pr diff <number>` 查看变更对比。
- **列出待分类的外部 PR（List external PRs for triage）**：`gh api --paginate 'repos/{owner}/{repo}/pulls?state=open' --jq '.[] | select(.author_association | IN("OWNER","MEMBER","COLLABORATOR") | not) | {number, title, author: .user.login, author_association, labels: [.labels[].name]}'`。
- **评论 / 标签 / 关闭（Comment / label / close）**：`gh pr comment`、`gh pr edit --add-label`/`--remove-label`、`gh pr close`。

GitHub 的 Issue 与 PR 共享同一编号空间，因此单独的 `#42` 可能是其中任意一种：请先尝试 `gh pr view 42`，失败时回退到 `gh issue view 42`。

## When a skill says "publish to the issue tracker"

创建一个 GitHub Issue。

## When a skill says "fetch the relevant ticket"

按上方 **读取 Issue（Read an issue）** 的方式读取。

## Wayfinding operations

供 `/wayfinder` 技能使用。**map** 是单个主 Issue，其下挂载多个 **child** 子 Issue 作为任务单（tickets）。

- **Map**：带有 `wayfinder:map` 标签的单个 Issue，正文维护 Notes / Decisions-so-far / Fog。使用 `gh issue create --label wayfinder:map` 创建。
- **Child ticket**：作为 GitHub sub-issue 关联到 map 的子 Issue（见上方 **设置子 Issue**）。若未启用 sub-issues，则将子 Issue 加入 map 正文的任务列表中，并在子 Issue 正文顶部注明 `Part of #<map>`。标签格式为 `wayfinder:<type>`（`research`/`prototype`/`grilling`/`task`）。认领后，将该任务单指派（assign）给当前开发者。
- **Blocking**：见 Conventions 下的 **阻塞依赖（Blocking）**。
- **Frontier query**：列出 map 下所有未关闭的子 Issue（`gh issue list --state open`，范围限定为 map 的 sub-issues 或任务列表），排除仍存在未关闭阻塞项（见 **统计未关闭的阻塞项** 或 `Blocked by` 行中的未关闭 Issue）或已有指派人的任务单；按 map 中的排列顺序取第一个。
- **Claim**：`gh issue edit <n> --add-assignee @me`，作为会话的首次写入操作。
- **Resolve**：先执行 `gh issue comment <n> --body "<answer>"`，再执行 `gh issue close <n>`，最后将结论摘要与链接追加到 map 的 Decisions-so-far 部分。
