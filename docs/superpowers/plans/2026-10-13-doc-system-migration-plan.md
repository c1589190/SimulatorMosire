# 文档系统迁移（约束设计书）· 2026-10-13

## §0 用户原话（逐字）
> 「**AGENTS.md只做一个项目简单介绍和文档索引**」
> 「那要不，**整个最新版的文档系统**？先告诉我目前各个地方的文档分别存了什么」
> 「刚刚更新了一下dsh，要不再加一个 **docs/skills系统**？在里面把总结的经验教训和派单规则等说清楚，进一步规划一下」
> 「要不放 **.agents/skills** 吧，.agents目录还有什么可以放的吗？」
> 「**没有任何毛病，之后的所有新文档按照你展示的新规则书写，旧的文档你看着办，还要用到的就迁移，不用的就备份进docs**」

## §1 新规则（已核实，全部来自 DSH 实现）
### 1.1 harness 自动加载（`dsh-agent-instructions` v0.2.0-rc.2，`lib/index.js` + README）
- 基础候选名 `["AGENTS.md","CLAUDE.md"]`；本地叠加 `["AGENTS.local.md","CLAUDE.local.md"]`
- 用户级 `$DSH_HOME/AGENTS.md`（本机 `/home/cna/.dsh/AGENTS.md`，**只认 `AGENTS.md`**）
- ★ **发现范围 = `ancestorChain(项目根, cwd)`**，项目根由 **`.git`** 标记 ⇒ **根到 cwd 的每一级**
- 顺序 **broad → specific**；「更具体的覆盖更宽的」
- 预算（`dsh-base` 挂载 = **65536**）：★ **先整体丢弃更宽的文件，最后才截断最具体的那个**
- ★ **动态**：`read`/`write`/`edit` 触达更深目录 ⇒ **下一个请求带上新文件**
- ★ 已知矛盾（**保守结论**）：`superpowers-dsh/.../dsh-tools.md` 说「no `CLAUDE.md` auto-loading on DSH」，
  而 `dsh-agent-instructions` 候选手册含 `CLAUDE.md` ⇒ **`AGENTS.md` 一定加载；`CLAUDE.md` 存疑**（本仓已删，无影响）
- ★ **当前实际损失**：根 `AGENTS.md` = **123390 字节 > 65536** ⇒ **约 46% 从不加载**，且**恰是尾部**（§一.9 之后 → §八）

### 1.2 技能根（`dsh-skill-filesystem` README 配置表，rank 升序）
| rank | 名 | 路径 |
|---|---|---|
| 100 | `project-dsh` | `<项目根>/.dsh/skills` |
| **200** | **`project-agents`** | **`<项目根>/.agents/skills`** ← ★ **本仓采用** |
| 300 | `custom` | `Config.customSkillDirs` |
| 400 | `user-dsh` | `~/.dsh/skills` |
| 500 | `user-agents` | `<agentsHome>/skills`（`$DSH_AGENTS_HOME` 或 `~/.agents`） |
| 600 | bundled | `$DSH_BUNDLED_SKILL_DIR` |
- 技能形态 = `<name>/SKILL.md` 目录包 **或** 顶层 `<name>.md`；★ **`**/SKILL.md` 刻意不发现**
- frontmatter 必填 `name` + `description`（可选 `whenToUse`/`metadata`/`disable-model-invocation`/`user-invocable`）
- ★ provider **watches** 根 ⇒ 新增/改名/删除**下一轮目录即生效，无需重启**
- ★ **`.agents/` 是跨运行时共享根**：`sync-from-upstream.mjs:197` 原文
  「**Codex, Copilot CLI, and Gemini CLI all also recognize `~/.agents/skills/` as a cross-runtime alias**」

### 1.3 决策记录 `.agents/notes/`（DSH 自己的仓里 **691 处引用**）
```
.agents/notes/{proposed,implemented,archived}/<分类>/YYYY-MM-DD-<slug>.md   （另有 .zh.md 双语）
```
- 实测分类与规模：`implemented/` 528（architecture 284 · feature 183 · bug-fix 33 · simplification 20 · process 6 · testing 2）·
  `archived/` 134（feature 62 · architecture 51 · simplification 11 · bug-fix 10）· `proposed/` 29（architecture 23 · feature 6）
- ★★ **生命周期即权威**：`proposed` = 未定 · `implemented` = 已落地（**代码为准，note 是解释**）· `archived` = 被取代（留痕）
  ⇒ 治本仓调查查出的三病：**同一事实三份文档相反** · **最新 handoff 也骗人** · **计数无权威**

### 1.4 六层归宿（新文档一律按此写）
| 层 | 路径 | 放什么 | 何时加载 |
|---|---|---|---|
| 常驻 | `AGENTS.md`（根） | 项目简介 + **文档索引** + 五条铁律 + 边界表（精简）+ 交叉纪律要点 | 总是 |
| 常驻·局部 | `simos-*/AGENTS.md` · `docs/AGENTS.md` · `.agents/AGENTS.md` · `.superpowers/AGENTS.md` | 该处的特有纪律 | agent 触达该目录后 |
| **技能** | **`.agents/skills/<name>/SKILL.md`** | **可复用的技术/纪律**（跨项目有用） | 目录列出，按需 `skill()` |
| **决策记录** | **`.agents/notes/{proposed,implemented,archived}/<分类>/`** | **决策：为什么这么定、被谁取代** | 人/agent 按索引读 |
| 长文 | `docs/agents/*.md` | 人读的经验教训（含本仓细节，不占技能预算） | 按索引读 |
| ★ 机械强制 | `tools/gate.sh` 等脚本 | 完整门禁组合 · `-pl` 强制 `-am` · `git add` 白名单 | 直接跑 |

### 1.5 `writing-skills` 的两条铁律（约束技能层）
1. ★★ **Iron Law：没有失败测试就不许写技能**（RED：先派子代理在**无技能**下跑压力场景、记录**逐字**违规；
   GREEN：写最小技能只针对那些违规；REFACTOR：堵漏洞重测）。**"总结一下经验教训"不够。**
2. ★★ **Don't create for：project-specific conventions（put in your instructions file）** ·
   **mechanical constraints（能 regex/validation 强制的就自动化）**
   ⇒ **派单规则 → `AGENTS.md`**；**门禁组合等 → 脚本**；**只有"可复用的判断类技术/纪律" → 技能**

## §2 旧文档的迁移映射（控制方判定；用户已授权"你看着办"）

### 2.1 判定原则
1. ★ **决不删**（用户「备份进 docs」）—— 归档 + 文件头加"**已被 X 取代 / 已归档，勿据此开工**"；
2. ★ **【活的】才迁**：仍在约束当前开发的（契约/边界/纪律）⇒ 迁入新规则位置；
3. ★ **【死的】归档**：已完成、被取代、或纯历史叙述 ⇒ **留在 `docs/`** 下并打归档头；
4. ★ **路径不轻易挪动（引用安全）**：`docs/superpowers/**` 被 **AGENTS.md、代码注释、多份文档**大量按路径引用
   ⇒ **默认原地保留 + 打权威级别标注**；**只有明确"被取代"的才移入 `docs/archive/`**。

### 2.2 逐块映射
| 现存 | 数量/体积 | 判定 | 去处 |
|---|---|---|---|
| `docs/superpowers/status/` **9 份**（≥4 份是总表且互有更正） | 240K | ★★ **治"口径重叠"的重灾区** | **合并为一份活权威**：`docs/status/CURRENT.md`（或等价）；其余 8 份 ⇒ **`docs/archive/status/`** + 头部标"已并入 CURRENT / 被谁取代" |
| `docs/superpowers/plans/` **82** | 2.4M | **已执行的 = 决策记录**；进行中的 = 活计划 | **已执行** ⇒ **`.agents/notes/implemented/<分类>/`**（**内容不重写，只按生命周期归位** + 头部补 stage）；**进行中** ⇒ 留 `docs/superpowers/plans/` |
| `docs/superpowers/specs/` **71** | 1.9M | **契约**，多数仍有效 | **原地保留**（引用太多）；★ 在 `docs/AGENTS.md` 写明"哪份被哪份取代"（治矛盾）；已失效的 ⇒ `docs/archive/specs/` |
| `docs/superpowers/reports/` **39** | 1.2M | 调查报告 | **原地保留**；纯历史 ⇒ `docs/archive/reports/` |
| `docs/superpowers/reviews/` **4** | 120K | 评审记录 | 原地保留 |
| **`HANDOFF-*` 8 份** | 100K | ★ **机制上已被 `status/` 与 `.agents/notes/` 取代** | ⇒ **`docs/archive/handoff/`** + 头部标"机制已被 `.agents/notes/` 取代" |
| `design-creed.md` | 16K | 设计信条，仍有效 | **原地保留**（或 `docs/agents/`） |
| `docs/worlds/v17levant/` 7 | 912K | 世界设定，**活** | 原地保留 |
| `docs/shade-evidence/` | 472K | 打包证据 | ⇒ `docs/archive/` |
| `README.md` 4K（2026-09-16 未动） | 4K | ★ **陈旧** | 重写为"项目简介 + 指向 `AGENTS.md`"（阶段 3） |
| `.superpowers/sdd/` **188 主题 / 18G** | 18G | SDD 台账+证据，gitignored | ★ **不迁**；但**最大 5 个占 14.6G**（`w10-scale-unlimited` **10G**）⇒ **另批清磁盘**（用户定） |
| `.claude/worktrees/` **33 个** | ? | 平行工作树，**从未核过** | ★ **另批核**（可能含未合并的活） |

### 2.3 ★ 硬性安全判据（派单时必写）
1. **每一份被移动/归档的文件，必须在文件头加一行状态**：
   `> **状态**：已归档（2026-10-13）· **取代者**：<路径> · **勿据此开工**`（或 `仍有效`）；
2. ★★ **不留死引用**：移动后必须全仓扫引用（`grep -rn "<旧路径>"`）并**逐个更新或留重定向**；
   ★ 报告里给"移动前后引用计数"；
3. ★ **内容零改写**（§五.4 留痕）：只加头部状态行，**不"顺手更新"其中已过期的事实**；
4. ★ **本批只动 `.md` + 新建目录**，不碰代码/测试/pom。

## §3 执行顺序（控制方裁定）
| 阶段 | 内容 | 依赖 | 状态 |
|---|---|---|---|
| **0** | `AGENTS.md` 分层（根瘦身 + `simos-*/AGENTS.md` + `docs/AGENTS.md` + `.superpowers/AGENTS.md`） | — | 🔄 在飞 |
| **1** | ★ **`tools/gate.sh`**（把"能机械强制的"做成脚本） | — | 🔄 在飞（与本表 §2 不冲突） |
| **2** | **本迁移**（§2 的映射）· 建 `.agents/notes/` 生命周期 | 等 **阶段 0 落定**（否则新写的文档索引立刻过期） | ⏳ 排队 |
| **3** | 技能（`.agents/skills/`）· 每个**先跑 RED 基线**，只写基线证明会失败的 | 等 1、2 | ⏳ |
| **4** | `docs/agents/*.md` 长文 + `README.md` 重写 | 等 2、3 | ⏳ |
| **5** | 另批：`.superpowers/sdd` 18G 清盘 · `.claude/worktrees` 33 个核查 | 用户定 | ⏳ |
