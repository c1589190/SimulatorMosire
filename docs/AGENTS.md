<!-- 加载时机：本文件**不会**被 harness 自动加载；agent 用 `read`/`write`/`edit` 触达 `docs/` 目录后由 harness 自动注入。 -->
<!-- 定位：**文档系统本身的用法**（放哪一层 / 谁说了算 / 怎么标"被取代" / 日期怎么写）。根 `AGENTS.md` 只做项目简介与文档索引。 -->

# docs/AGENTS.md —— 文档系统本身的用法

## 一、六层归宿表（★ 控制方已从 DSH 实现核实）

| 层 | 路径 | 放什么 | 何时加载 |
|---|---|---|---|
| 常驻 | `AGENTS.md`（根） | 项目简介 + 文档索引 + 五条铁律 + 边界表（精简） | **总是** |
| 常驻·局部 | `simos-*/AGENTS.md` · `docs/AGENTS.md` · `.superpowers/AGENTS.md` | 该处特有纪律 | agent `read`/`write`/`edit` 触达该目录后**自动** |
| 技能 | `.agents/skills/<name>/SKILL.md` | 可复用技术/纪律（**跨运行时**：Codex/Copilot CLI/Gemini CLI 也认） | 目录列出，按需 `skill()` |
| 决策记录 | `.agents/notes/{proposed,implemented,archived}/<分类>/YYYY-MM-DD-<slug>.md` | 为什么这么定、被谁取代 | 按索引读 |
| 长文 | `docs/agents/*.md` | 人读的纪律长文（**本批新建的就是这层**） | 按索引读 |
| 机械强制 | `tools/gate.sh` | 门禁组合 · `-pl` 强制 `-am` · `git add` 白名单 | 直接跑 |

**一句话选层**：改代码前必读的规矩 → 根 `AGENTS.md`；某目录特有的纪律 → 该目录的 `AGENTS.md`；可复用的技术/流程 → `.agents/skills/`；
"为什么这么定、被谁取代" → `.agents/notes/`；人读的纪律长文 → `docs/agents/`；**能机器拦的 → `tools/gate.sh`（别只写文档）**。
★ 长文一律**不要用 `@` 导入**（导入会在启动时把文件展开进上下文，白载 5000+ 行）。

## 二、口径优先级（冲突时谁说了算）

1. **用户原话**（含 `docs/superpowers/specs/2026-10-02-architecture-design-source.md` 的用户设计原稿 D-xxx = 最高权威）。
2. **最新落盘文档**（§四.1：冲突时以最新落盘文档为准；同主题多份时看后一份头部的「取代者」标注）。
3. **状态与未完成清单**：以 `docs/superpowers/status/2026-10-12-known-issues-code-verified.md`（回代码核实版：54 条逐条判定 + 16 处文档更正 + 好/没好分档）
   与 **`docs/superpowers/status/2026-10-13-outstanding-work-survey.md`（最新未完成清单）为准**；
   `docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md` 是"计划内未实现"总清单，**其经济部分已被前者更正**。
4. **根 `AGENTS.md`**（常驻精简口径）：与 `docs/agents/*.md` 长文冲突时**以根为准**（长文是该节的逐字原文，属留痕层）。
5. **旧文档 / 历史留痕**：只作溯源，**不作开工依据**；历史留痕一律不篡改（§五.4）。

★ 向用户提"待裁定"之前，**必须先做口径溯源并落盘**（根 `AGENTS.md` §五.7 → `docs/agents/02-一-并发.md` §一.12）：已有裁定不得重复问。

## 三、文档间的「取代关系」怎么写

- 被取代的文档**不删**（历史留痕不改）。在**文件头部**加一行：
  `> **取代者：`docs/superpowers/specs/YYYY-MM-DD-<主题>.md`（YYYY-MM-DD）** —— 本文仅作历史留痕，**勿据此开工**。`
- 取代者一侧在标题下加一行：`> 本文取代：`<旧路径>`（YYYY-MM-DD）。`
- 只在**头部**写一次，不散落正文；被多次取代时列出全部路径（新的在前）。
- **局部**更正不要整篇标废：写清"哪一节被谁取代"（例：inventory「其经济部分已被 known-issues-code-verified 更正」）。
- `.agents/notes/` 的决策记录同形制：`proposed/`（提议中，无约束力）→ `implemented/`（已落地）→ `archived/`（被取代，头部写 `取代者：`）。

## 四、`file:line` 与"生成命令"要求

- **现状事实必须给 `file:line`**（如 `simos-economy/pom.xml:46-51`），并注明是**回代码核过**还是**转抄**；转抄的标"未回核"。
- **跑过的结论必须给可复制的原命令 + 真实输出**（rc、计数、关键行原文）；**不许只写结论**，更不许把"没跑"写成"通过"（§三）。
- **数字要给生成命令**：测试计数 / 文件数 / 命中数 / 字节数，一律"命令 → 输出"成对出现，便于复核。
- 引用历史时给**取历史版本**的命令：`git show <sha>:<path>`（旧 `CLAUDE.md` 同样可取）。
- 长输出不贴全文，给可复现命令 + 要点；报告/台账里必须留**未验证 / 没做**一节。

## 五、★ 日期口径规则

1. **引用用户原话时，日期取"用户实际说的那一天"** —— 不是文档落盘日、不是提交日。
2. **若与 git 提交日期矛盾，两者都记并标注疑点**：写成「用户口述 2026-10-13 / 提交日期 2026-10-14（★ 日期疑点，两存）」，不许只留一个。
3. **★ 不许用文件名反推日期**：本仓实测有**文件名日期晚于当前日期**的现象 —— 今天是 2026-10-13，
   而 `docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md`、`docs/superpowers/plans/2026-10-23-all-module-logging-rollout.md`、
   `docs/superpowers/HANDOFF-2026-10-22-d5-decision-turn-economy.md` 等一批文件的文件名带 `2026-10-22/23`。
   ⇒ 文件名日期只是**标签**，可能与内容实际日期不一致；要日期就去正文找"用户原话 + 日期"，或看 `git log`，**并标注疑点**。
4. 文档里写"今天 / 本轮"时**带上绝对日期**；跨会话引用一律写绝对日期（`YYYY-MM-DD`）。
