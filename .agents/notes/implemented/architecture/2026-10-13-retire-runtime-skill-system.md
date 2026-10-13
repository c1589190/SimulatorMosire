# 取缔运行时 Skill 系统（拆除，不留替代）

> **状态**：`implemented`（2026-10-13）
> **本文取代**：`simos-app/src/main/java/io/mosire/simos/app/skill/SkillLibrary.java:31-32` 类注里逐字收录的那条用户原话
> —— 「最好搞成存在外部方便读取、写入、修改，还要兼容其他 外部 Agent 方便进行 GM Agent 的通用修改」（2026-09-23）。
> **依据**：用户原话（逐字，2026-10-13）：
> 「现有项目的Skill等直接取缔，Skills/Docs系统我想了想其实是一个，Skill无非是有更多触发规则的Docs，你得先给我提炼出来隔壁要做Docs系统至少得满足你这边什么需求」
> 「我们暂时不准备做Skill，只维护一个通用文档系统，最多加个自动注入的简单Agents.md」
> **约束设计书**：`docs/superpowers/plans/2026-10-13-retire-skill-system.md`
> **下游需求书**：`docs/superpowers/specs/2026-10-13-agentlib-docs-requirements.md`

## 决定

**把运行时 Skill 这套机制从代码里整体拆除，并且不做替代实现。**

拆除面 = 4 个生产文件（`Skill.java` / `SkillSummary.java` / `SkillLibrary.java` / `tools/read/SkillTool.java`）
+ `config/skills/` 的 4 篇种子 + 7 个生产文件的调用点 + 工具面一条（`simos.skill`）
+ **两处派生**：`DecisionToolNames.skill()` 接口方法、以及 `statelessReads()` 这个由访问器派生的集合（4 项 → 3 项）。

## 为什么「取缔」而不是「改造」

1. **用户裁定两者本是同一件事**：`Skills/Docs 系统其实是一个，Skill 无非是有更多触发规则的 Docs`。
   ⇒ 分成两套机制没有理论依据，改造一套再留另一套只会把"文档身份"变成两个拼写点。
2. **Skill 的两条设计前提都已失效**：
   - 它被设计成**外部文件、不在世界 revision 内**（`SkillLibrary.java:36-38` 逐字「不留下世界版本锚」），
     而用户 2026-10-13 已裁定文档库要**跟着世界走、走文件夹而不走 db** ⇒ 存储前提变了；
   - 它要求"外部 agent 用文本工具直接读写"，而用户同日裁定 GM 通过 MCP 工作 ⇒ 写入前提变了。
3. **它当期没有消费者路径**：`SkillLibrary` 只有 `open`/`list`/`find`（**零写方法**），唯一的读口是 `simos.skill`；
   用户要的"决策人自由更新自己的 Skill"从来没被实现过，也没有 per-agent 技能归属
   （`DecisionMaker` 字段里没有 `skillId`）。⇒ 拆掉的是一套**只读、无归属、无写入口**的空壳。

## 代价（明说，且是**有意**的）

**拆完之后，"决策方法论"在线上是空的。** `DecisionAgentRunner` 开场消息里那句技能指针会消失，
决策人**没有任何**"该怎么做决策"的输入 —— 直到隔壁 AgentLib 的 Docs 系统接上、或补一个自动注入的 Agents.md。

⇒ **这是有意为之，不是缺陷、不许记 bug。**
⇒ ★ **明令禁止**：**不许**用"临时补一段硬编码决策守则"来填这个空 —— 那正是本批要取缔的东西换个马甲。

## 被保留的（不是 Skill，别一起拆）

- `sd.DecisionDocs` / `sd.DecisionResults` / `simos.sd.reports` —— 那是 **Docs**，建在 `InfoSystem` 的 INFO 覆盖层上、
  走 `Command → ChangeSet → Revision`，与 Skill 无关。
- `docs/agents/13-十二-推演Skill文档规则.md` —— 它是**用户裁定的记录**（含 U1 之前的原话），
  **不删**（`docs/AGENTS.md` §三/§五.4：历史留痕一律不篡改）。
- 开发侧技能层 `.agents/skills/` —— 那是**跨运行时的开发者技能**（Codex / Copilot CLI / Gemini CLI 也认），与运行时 Skill 无关。

## 取代者

**隔壁 AgentLib 的通用 Docs 系统**（需求书已落盘：`docs/superpowers/specs/2026-10-13-agentlib-docs-requirements.md`）。
"Skill = 有触发规则的 Docs"是**将来**方向，本期不做（用户：「我们暂时不准备做Skill」）。
