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

## §4 阶段 1 收工（`tools/gate.sh`，提交 `9f246895`）

**它是什么**：把 5 条"**写在文档里但没人拦**"的规则做成**不可绕过**的门禁（`writing-skills` 明文：
mechanical constraints → automate it）。55.7K，mode 755，零新依赖，Maven 一律经 `tools/mvn-lock.sh`。
**控制方自跑复核**：`selftest` **pass=57 fail=0 rc=0** · `guards` **rc=0**（5 护栏 2/11/10/12/1 全 `0/0` + 前端门禁 412/412）·
R1/R3/R4 三条负向演示**全部当场拦住** · 索引干净（只 1 个文件）。

| 规则 | 它拦什么 | 对应的真实事故 |
|---|---|---|
| **R1** | skip 开关（`full` **零参数**；任何 `-D*skip*` / `-q` / `MAVEN_OPTS` / `.mvn/maven.config` 藏开关一律拒）★ 并做**正向证据验证**（前端 marker + 插件 banner + shaded jar + 全仓 surefire 合计 `tests>0 且 0 红`）⇒ **假绿无路可走** | `-Dexec.skip=true` 停掉整道前端门禁 ⇒ `api.js:535` 悬空 ⇒ **GUI 全坏，活 5 个提交** |
| **R2** | `guards` 跑 5 条护栏（**含真 `Shell.start`**）；★ 逐类断言 surefire XML `tests>0 且 0 红`；★ **护栏源文件必须还在**（改名/删除 ⇒ 门禁自身腐烂，拒跑） | 架构轮从没跑过 `clean verify` ⇒ 没有机制构造真 `Shell` ⇒ `roles()` 写错 ⇒ **`Shell` 构造即抛，GUI 5 个提交起不来** |
| **R3** | `focused` **默认加 `-am`**；去掉须显式 `--i-know-upstream-may-be-stale`；禁透传 `-pl` | `-pl <module>` 验的是 **`~/.m2` 旧 jar**（§34.3） |
| **R4** | `git add` 白名单（拒 `-A`/`--all`/`.`/通配符/pathspec magic/仓外/仓根）；目录参数**列出全部入列条目**；回显新入列清单 + **2 分钟内被改过 = 可能是在飞中间态**警告 | `git add -A` **三次**扫进在飞 Agent 的文件 |
| **R5** | 跑前查服务：**只看落在本仓的进程**；硬风险（吃本仓 `simos-app/target/`）判红 vs 快照/其他 checkout 仅报告 | AGENTS §二：运行中 jar 被就地改写 ⇒ `/` 与 `/styles.css` 全 500 |

★ **它顺带揪出一个真陷阱**：`-Dtest + -am` 会让上游模块以 `No tests matching pattern …` 判红
⇒ 已在 `focused` 内部自动补 `-Dsurefire.failIfNoSpecifiedTests=false`，**并用"本次新报告数 > 0"兜底**
（**不是放松** —— 拼错表达式照样红）。
★ **残余绕过面它如实报了**：脚本可被改 · 裸 `git add -A` / 裸 `mvnw` 仍可绕 ·
`~/.m2/settings.xml` 的 profile 属性注入 skip **未拦未验**。⇒ 牙齿是"**走这条路绕不过去**"，不是物理阻止。

### §4.1 ★ 控制方裁定：日期口径异常（它提的疑点）
**事实**：本机 **今天是 2026-10-13**，而 `AGENTS.md` §一.1 把一条用户更正记作「用户 **2026-10-23**」；
且仓内有一批 **`2026-10-23` / `2026-10-22` / `2026-10-15` / `2026-10-14`** 的文件
（含 `HANDOFF-2026-10-23-…`），其中 `status/2026-10-23-planned-not-implemented-inventory.md`
的 **git 提交日期是 2026-10-12** ⇒ **文件名日期 ≠ git 日期，且部分晚于当前日期**。

**裁定**：
1. ★ **不就地改**（§五.4 不篡改留痕；且 `AGENTS.md` 正被另一个 Agent 重写）——
   `tools/gate.sh` 照抄未改是**正确**的（它自己也说明了"不篡改 AGENTS 口径"）；
2. ★★ **这本身是文档系统的病灶，写进新规则**：
   **引用用户原话时，日期取"用户实际说的那一天"；若它与 git 提交日期矛盾，两者都记并标注疑点**
   —— 不许用文件名的日期反推，也不许二选一后当无事发生；
   ★ 这条与 §一.12「口径溯源」配套：**溯源要能同时给出"原话日期"与"落盘提交日期"**；
3. ★ **统一更正**排进**阶段 4**（`docs/agents/*.md` + `README.md` 那一批）—— 届时一次性扫全仓
   `2026-10-1[4-9]|2026-10-2[0-9]` 的日期引用，逐条判定"笔误 / 目标日期 / 真未来"并统一口径；
4. ★ **`AGENTS.md` 与 `tools/gate.sh` 两处的措辞留到阶段 4 一起改**（避免本批改一半、另一 Agent 正在写同一文件）。
