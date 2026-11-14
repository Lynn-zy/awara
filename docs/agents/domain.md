# Domain Docs

工程技能在探索代码库时读取与遵循领域文档（Domain Documentation）的规范。

## Before exploring, read these

- 仓库根目录下的 **`GLOSSARY.md`**，或
- 仓库根目录下的 **`GLOSSARY-MAP.md`**（若存在）：它会指向各个上下文对应的 `GLOSSARY.md`，请读取与当前主题相关的词汇表。
- **`docs/adr/`**：读取与即将修改的模块相关的架构决策记录（ADR）。在多上下文（multi-context）仓库中，还需检查 `src/<context>/docs/adr/` 下的上下文级决策。

若上述任何文件尚不存在，请**静默继续（proceed silently）**。不要将其缺失作为问题报告，也不要主动建议提前创建。`/domain-modeling` 技能（可通过 `/grill-with-docs` 与 `/improve-codebase-architecture` 触发）会在术语或架构决策真正敲定时按需惰性创建它们。

## File structure

单上下文仓库（Single-context repo，当前仓库采用此布局）：

```
/
├── GLOSSARY.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── app/
```

多上下文仓库（Multi-context repo，根目录存在 `GLOSSARY-MAP.md` 时）：

```
/
├── GLOSSARY-MAP.md
├── docs/adr/                          ← 系统级全局决策
└── src/
    ├── ordering/
    │   ├── GLOSSARY.md
    │   └── docs/adr/                  ← 上下文专属决策
    └── billing/
        ├── GLOSSARY.md
        └── docs/adr/
```

## Use the glossary's vocabulary

当你的输出涉及领域概念命名时（无论是 Issue 标题、重构提案、问题假设还是测试名称），必须严格使用 `GLOSSARY.md` 中定义的标准术语，切勿偏移使用词汇表中明确规避的同义词。

如果你需要的领域概念尚未收录在词汇表中，这通常意味着两种情况之一：要么你正在自造项目并不使用的概念词汇（请重新审视），要么领域模型存在真实空白（请记录下来以便交由 `/domain-modeling` 处理）。

## Flag ADR conflicts

如果你的输出与现有 ADR 存在冲突，必须显式指出，而不能静默覆盖：

> _与 ADR-0007（event-sourced orders）冲突，但值得重新讨论，因为……_
