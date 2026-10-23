# `.agents/notes/` —— 决策解释与生命周期

> **本目录属于项目维护，只解释“为什么这样定、被谁取代”，不冒充设计/维护正本。**
> 当前状态：`../../docs/agents/STATUS.md`；文档系统：`../../docs/AGENTS.md`；
> 维护裁定：`../../docs/agents/MAINTENANCE_RULINGS.md`；项目设计裁定：`../../docs/superpowers/`。

## 一、生命周期

```text
.agents/notes/proposed/    ← 提议中：无约束力，不得据此开工
.agents/notes/implemented/ ← 已落地：代码/设计正本为准，note 解释理由
.agents/notes/archived/    ← 被取代：只留痕；头部写 `取代者：`
```

- `proposed/` 可改、可撤；开工依据是用户原话 / 设计原稿 / 当前设计或维护裁定，不是 proposed note。
- `implemented/` 只解释**已发生**的决策；与代码冲突时先回代码核。
- `archived/` 不删，移动文件保留 git 历史，并在头部写取代者。
- 状态变化用 `git mv`，不复制成两份。

## 二、目录与命名

```text
.agents/notes/<生命周期>/<分类>/YYYY-MM-DD-<slug>.md
```

分类：`architecture` · `feature` · `bug-fix` · `simplification` · `process` · `testing`。
日期取决策当天；不要用文件名反推日期，用户原话日期以正文为准。

## 三、什么该进 notes

| ✅ 收 | ❌ 不收 |
|---|---|
| 决策理由、取舍、被谁取代 | 当前进度、流水账、命令输出 |
| 与设计书/维护规则的偏离及原因 | 完整接口/载荷/不变量正文（那是 spec） |
| 用户原话导致的决策背景（附出处） | 纪律长文、机械检查细节（去 docs/tools） |

**分账**：
- **项目设计裁定**：权威正文在 `docs/superpowers/specs/**`（用户设计原稿 D-xxx），note 只解释；
- **项目维护裁定**：权威正文在 `docs/agents/MAINTENANCE_RULINGS.md`，note 只解释。

## 四、头部形制

```markdown
# <一句话决策名>

> **状态**：`proposed` / `implemented` / `archived`（YYYY-MM-DD）
> **取代者**：`<路径>`（YYYY-MM-DD）      ← 仅 archived
> **本文取代**：`<路径>`（YYYY-MM-DD）    ← 仅取代了旧决策
> **依据**：用户原话（逐字）/ `<spec 或维护裁定路径>`
```

## 五、当前迁移状态

- 已实现 note 只有真实落地决策；旧 `docs/superpowers/plans/` 不批量搬入，等需要时按生命周期迁移。
- 迁移已执行/进行中的计划时，先确认计划状态和引用，不得把历史计划当当前设计。
- 旧 note 中指向 `docs/superpowers/status` 的“当前状态”口径已失效；状态统一看 `docs/agents/STATUS.md`。
- 旧 note 中的 `.claude/worktrees/*` 是 2026-10-23 前的工作树历史地址；当前工作树统一在 `worktree/<name>/`。
