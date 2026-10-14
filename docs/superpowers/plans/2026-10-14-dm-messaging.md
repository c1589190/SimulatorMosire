# 2026-10-14 决策人互致消息（`simos.ga.message.*`）—— 约束设计书

> **本文是派实现 Agent 的前置**（`AGENTS.md` §五.3 → `docs/agents/02-一-并发.md` §一.8）。
> **替代**：`simos.sd.report`（`SimosSdReportTool`）+ `simos.sd.reports`（`SimosSdReportsTool`）**整族废弃**（用户原话见 §1.2）。
> **基线**：`main`，工作树含 AgentLib 接入批次（未提交）。

---

## 1. 需求来源

### 1.1 用户原话（逐字，最高权威，2026-10-14）

> 「**把这个工具扔了得了**，如果一个决策人**要隔空对话另一个决策人**，可以，**不给当前决策人完整的决策人id列表**，并且**设权限范围**，只有GM授权这个决策人允许和另一个决策人通讯了，**这个决策人才能使用新的通讯工具**，**每tick给对面发一条交流信息**——重新设计一下这个ga的工具，**老的直接扔掉**」

补充裁定（同日，逐字）：

> 「2，DM理论上**想称呼什么称呼什么**」 —— 收件人用**被授权的那一个 id** 指称；正文里怎么称呼对方是内容，不属机制。
> 「**双向**」 —— 授权一次即双向可通。
> 「**肯定是ga系列啊**」 —— 工具名用 `simos.ga.*`。

四答（同日裁定，用于消除歧义）：

| # | 歧义 | 裁定 |
|---|---|---|
| 1 | 收件人怎么指称 | **只给被授权的那一个 id**（不给完整名册） |
| 2 | 对面怎么读到信 | **对称读工具**（收件人一个 inbox 工具） |
| 3 | 信存在哪 | **复用 `info` 覆盖层 + 专用地址前缀** |
| 4 | 旧件扔到什么程度 | **全扔**（写+读+GUI 面）；**DM→GM 靠现有决策包**（`DecisionTurnFinalizer`），不断层 |

### 1.2 一条**必须一起处理的旧债**

`SimosSdReportTool` 写的那族地址是 `sd:doc.report-<sender>-<tick>-<seq>`。
用户更早的裁定（2026-10-12，见 `docs/agents/01-〇-铁律与模块边界.md:45` 逐字）是：
> 用户裁定「**sd整个弃用！**」＋「**`sd:*` 地址族 → 直接扔**」

而 `GaResolver` **只认 `ga` 命名空间**（`GaResolver.java:62` `NAMESPACE = "ga"`、`:77` 非 `ga` 一律跳过）⇒ 那族 `sd:` 地址**根本解析不了**，只是 `info` 这个 map 的键。
⇒ **本批删掉它，同时给新消息一个 `ga:` 前缀，不再产生 `sd:` 地址。**

★ **注意边界**：`sd` 作为**权限声明面标识符**（`ToolResources.SD_NAMESPACE` / `ResourcePaths#sd` / `ToolSupport.SD_NAMESPACE`）是**另一条已知债**（设计书明文"留给批次 5"），**本批不碰**。

---

## 2. 现状（`file:line`，控制方回代码核过）

| 事实 | 位置 |
|---|---|
| 旧写口：载荷 `{to:{kind:GOV\|NATION\|GM, id?}, subject, body, evidence?, tick?}`；落 `sd:doc.report-…`；`tags` 恒空、`affiliations` 拼 `gov:`/`nation:` 串 | `simos-ga/.../tools/write/SimosSdReportTool.java:40-76` |
| 旧读口：`simos.sd.reports` | `simos-ga/.../tools/read/SimosSdReportsTool.java` |
| `GaState` 组件 **7 个**：`decisionPackets` / `mergedEffectPlans` / `directives` / `verdicts` / **`decisionMakers`** / `info` / `effects` | `simos-ga/.../GaState.java:76-83` |
| `info` 表形状：`Map<String, List<SdInfoEntry>>`（**键=地址字符串，值是列表 ⇒ 同地址可追加**） | `GaState.java:82` |
| `SdInfoEntry` 字段：`(id, tick, **Set<DecisionMakerId> tags**, Set<String> affiliations, key, value, note, at, sourceDirective, adjudicationStatus)` | `simos-ga/.../ga/model/SdInfoEntry.java` |
| 可见性判据：GM 无条件；否则 `owner==actorId` → **`tags 含 actorId`** → `affiliations` 相交 → 兜底不可见（"不可见"与"不存在"同码） | 隔壁 `docs-mosire` 的 `DocVisibility.canSee` |
| ★ `PutInfo` 给了显式 `id` ⇒ **命令期重复即拒**（具名） | `simos-ga/.../spi/PutInfoHandler.java:130-136` |
| ★ GM 配权命令收**完整**命名空间→前缀表：`{decisionMakerId, allowedTools?, accessLimit{命名空间:[前缀…]}?, redactedFields?, adjudicationDisclosure?}` | `simos-ga/.../tools/write/SetDecisionMakerAccessTool.java:44-45` |
| `AccessLimit` = `Map<String, Set<String>> prefixesByNamespace` + `redactedFields` + `adjudicationDisclosure` | `simos-ga/.../ga/model/AccessLimit.java` |
| ★ **按参数取资源的口子**：`protected List<ResourceId> writeResources(ToolContext)` + `ToolResources.requireAll(context, WRITE, …)` | `simos-ga/.../tools/write/AbstractNarrowWriteTool.java:146,200`；实例见 `ActorAdjustAccountsTool:74`、`ArmyRecordCombatTool:58` |
| ★ 窄写基类**硬编码** `gate() = Ask(name, summary, SENSITIVE)` | `simos-app/.../tools/write/AbstractNarrowWriteTool.java:205` |

---

## 3. 目标 / 非目标

**目标**：让**被 GM 授权的两个决策人之间**能互致消息 —— 一次授权**双向**可通；**每 tick 每方向一条**；**不向任何决策人泄漏名册**；旧 `report` 族彻底消失。

**非目标**：
- ❌ 不做"向 GOV / NATION 上报"（旧能力随旧件一起废弃；DM→GM 走决策包）；
- ❌ 不做群发 / 广播 / 多方会议（只有两两对等）；
- ❌ 不做消息已读回执、撤回、编辑；
- ❌ 不新增 `GaState` 组件（消息住 `info`）；
- ❌ 不碰 `sd` 权限命名空间本体（批次 5 的债）。

---

## 4. 设计

### 4.1 授权：`peer` 资源命名空间（★ **零新机制**，但**一个新命令**）

**资源命名空间**：新增 `peer`（`ToolResources.PEER_NAMESPACE`），目标资源 = **`peer:<决策人id>`**。

**新命令**（★ 这是对"零新命令"的一处**有意偏离**，理由见下）：
```
ga.SetDecisionMakerPeer   载荷 {dmA, dmB, allowed:true|false}
```
一次调用**同时写两条 DM 记录**、**一条 revision**：
- `allowed=true`：`dmA.accessLimit.prefixesByNamespace.peer ∋ dmB` **且** `dmB.…peer ∋ dmA`
- `allowed=false`：两侧各删去对方（关闸）

★ **为什么必须一条命令写两条记录**：用户裁定**双向**。若走既有 `ga.SetDecisionMakerAccess` 逐条设，GM 要点两次，**中间态 = 半开**（A 能写 B、B 回不了）—— 那是**可达且无告警**的错误状态。新命令让"双向"成为**原子事实**，半开**不可表达**。
★ 底层机制**仍是 `accessLimit`**：没有新存储、没有新可见性、没有新切片、没有新限流。

**授权后 DM 看到什么**：它的可达面里出现 `peer:<对方id>`。
⇒ **"不给完整名册"是结构的必然**，不是靠过滤：名册在 `GaState.decisionMakers`，其读口 `DecisionMakersTool` 是 GM-only；DM 只能从自己的 `accessLimit` 里知道**被授权的那一个 id**。

★ **基础可达面必须补一条 `peer:<自己>`**（控制方自审发现的设计缺口）：`peer` 是**新命名空间**，而 DM 的基础可达面由 `DecisionScopeFunctions` 派生、**本来不含它**。若不补：
- `send` 里"只能以自己名义"那条自指断言会**永远判否**；
- `inbox`（§4.3）也会被自己的资源声明拒掉。
⇒ 在 `DecisionCallerFactory` 给每个 DM 的 `peer` 域赋 **`peer:<自己>`**（与既有 `sd:decision-maker/<自己>` 同款自指域）。**只加这一条**，不加别的。

### 4.2 发送：`simos.ga.message.send`（DM 面）

```
载荷  {to:"<决策人id>", subject?, body(必填非空白)}
资源  声明 peer 域；★ 覆写 writeResources(context) 返回 [peer:<to>, peer:<自己>]
      ⇒ requireAll 按调用者可达面判 ⇒ 未授权 = 具名拒（复用既有"够不着"文案）
落点  一条 ga.PutInfo：
      id    = "msg-<from>-<to>-<tick>"      ★ 每 tick 每方向一条，由 id 唯一性保证
      地址   = ga:message.<from>.<to>.<tick>
      key   = "message"
      value = JSON {from, to, tick, subject?, body}
      tags  = {from, to}                    ★ 可见性 = 收发双方 + GM（零新机制）
      affiliations = 空
      tick  = 当前世界 tick（不得记未来）
身份  发件人从 ToolContext.identity() 派生（decision-maker:<自己>），**不由载荷自报**
```

★ **"每 tick 一条"白拿**：`PutInfoHandler:130-136` 对显式 `id` **命令期重复即拒** ⇒ 同 tick 第二条发给同一人的消息**当场具名拒**，无需自建限流器。

★ **为什么 `send` 不走 `AbstractNarrowWriteTool`**：那个基类硬编码 `gate() = Ask(SENSITIVE)`（逐条审批）。
本工具写的是**感知层**、且 GM 的管控点是**事先授权**（§4.1）⇒ 它必须 `gate() = ALLOW`。
⇒ **它不属于"窄写工具"家族**，因此既有的两条审计（`writesAreSensitiveAndAskWithTheToolNameAsClassKey`、`everyNarrowWriteToolClassIsWiredIntoTheGmBucket`）**不该把它扫进去** —— 设计书在此**明文标定这条边界**，实现方不得为省事去 `extends AbstractNarrowWriteTool` 再覆写回 `ALLOW`（那会让"窄写 ⇒ Ask"这条不变量出现例外，审计就失去判别力）。

### 4.3 收件箱：`simos.ga.message.inbox`（DM 面）

```
载荷  {sinceTick?, limit?(默认 50)}
★ 判据  ① **可见性一律走既有通路**（`RedactingQueryService` / 既有读口）—— **不得自己写 `tags().contains(...)`**；
        ② 在**已经可见**的条目里再按 `key=="message"` 且 `to==自己` 过滤
返回  {messages:[{tick, from, subject?, body}], count}
资源  peer:<自己> 自指域（读自己的信不需要 peer 授权）
```
★★ **为什么必须走既有通路（控制方 2026-10-14 实测纠正的设计错误）**：本仓有一条**既有守卫**
`DecisionResultsVisibilityGuardTest.theTagsOwnershipRuleIsImplementedExactlyOnceAndOnlyInRedactingQueryService`
逐字要求：**「★ 归属判据（`tags().contains(`）全仓 `src/main` 恰一份，且只在 `RedactingQueryService`」**。
本文初稿让 `inbox` 自己写 `tags` 含自己 ⇒ **造出第二份** ⇒ 该守卫当场红（实测 `Actual size = 2`）。
⇒ **正确口径**：`inbox` **不重实现归属判据**，只做"在已经看得见的条目里按 `key`/`to` 再筛一层"。
★ 这条**不是"改测试"能解决的** —— 守卫的存在正是为了挡这个。
★ 只返回**寄给自己的**信 —— 不含自己发出去的（发件人要看自己发的，那是另一件事，本批不做）。

### 4.4 三类归类（与本批正在定的口径对齐）

| 工具 | 归类 | 理由 |
|---|---|---|
| `simos.ga.message.send` | **类3 `DM_FREE`** | 写**感知层**（不改领域数据）；闸门 = **事先授权**（`accessLimit.peer`），不是逐条审批 |
| `simos.ga.message.inbox` | **类3 `DM_FREE`** | 读 |
| `ga.SetDecisionMakerPeer` | **类1 `GM_ONLY`** | GM 配权 |

★ 这条**顺带定死了**本批之前留的一个问题：**"写感知层"类工具 ⇒ `DM_FREE` + 事先授权**（不是逐条审批）。

### 4.5 旧件删除清单

| 删什么 | 位置 |
|---|---|
| 旧写口 | `simos-ga/.../tools/write/SimosSdReportTool.java` |
| 旧读口 | `simos-ga/.../tools/read/SimosSdReportsTool.java` |
| 两处注册 | `SimosToolSource` 里的两条 `built.add(...)` |
| 地址族 | 不再产生 `sd:doc.report-*`（**不写迁移**：Alpha 不兼容旧档） |
| GUI 面 | `ApiViews` / `GuiServer` 里 `reports` 相关视图与路由（**先 grep 列全再删**） |
| 测试 | 旧件对应的测试类/用例（**先 grep 列全**） |

★ **不删**：`ga.PutInfo` 本体（本批要用）、`info` 覆盖层、`sd` 权限命名空间（批次 5 的债）。

---

## 5. 不变量（不可破坏）

| # | 不变量 |
|---|---|
| **I1** | **每 tick 每方向至多一条**：`id = "msg-<from>-<to>-<tick>"`，重复 ⇒ 命令期具名拒 |
| **I2** | ★ **未授权不可通**：`send` 的 `peer:<to>` 必须在调用者可达面内；不在 ⇒ 具名拒 |
| **I3** | ★ **双向原子**：`ga.SetDecisionMakerPeer{dmA,dmB,true}` 一次落两条记录、一条 revision；**不存在半开态** |
| **I4** | ★ **不泄漏名册**：任何 DM 面的工具、返回、工具描述、错误文案里**不得出现未被授权者的决策人 id** |
| **I5** | **可见性 = {from, to} + GM**：靠 `tags`，不新增可见性机制 |
| **I6** | **发件人不可伪造**：`from` 从身份派生，不采信载荷 |
| **I7** | ★ **`send` 不进"窄写工具"家族**（§4.2 的边界），既有"窄写 ⇒ Ask"审计保持零例外 |
| **I8** | **不新增 `GaState` 组件**（仍 7 个）；消息走 `info` |
| **I9** | **不产生 `sd:` 地址**（新前缀 `ga:message.*`） |
| **I10** | **不写迁移、不读旧档**（Alpha 口径）：旧 `sd:doc.report-*` 条目若存在于旧档，**不解释、不显示** |

---

## 6. 验收判据

### 6.1 正向

| # | 判据 |
|---|---|
| **P1** | GM 授权 `dmA↔dmB` 后：A `send{to:B}` 成功；**B 的 `inbox` 读得到**（正文逐字一致） |
| **P2** | ★ 双向：**B 也能 `send{to:A}`**（一次授权后，无需再授权） |
| **P3** | ★ 未授权：A `send{to:C}` ⇒ **具名拒**（可达面文案），且**世界零变化**（无 revision） |
| **P4** | ★ **每 tick 一条**：A 同 tick 发第二条给 B ⇒ **命令期具名拒** |
| **P5** | ★ 跨 tick 可再发：下一 tick A 再发 ⇒ 成功 |
| **P6** | ★ **不泄漏名册**：A 的工具面与所有返回里**不出现 C 的 id**（把 A 的 `inbox`、`send` 结果、错误文案全部扫一遍） |
| **P7** | 可见性：第三方 D 看不到 A↔B 的信；**GM 看得到** |
| **P7b** | ★ **零授权的 DM 也能用 `inbox`**（空列表，非拒）：证明基础可达面里确实有 `peer:<自己>`（§4.1 自审补的那条）；同时它的 `send` 对任何 `to` 都**具名拒** |
| **P8** | ★ 旧件彻底没了：工具面 **−2**（`simos.sd.report`/`simos.sd.reports` 不在任何桶）；`sd:doc.report` **零写入** |
| **P9** | 关账：`tools/mvn-lock.sh clean verify` **rc=0**，且不低于基线计数 |

### 6.2 负向（★ 每条：改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5）

| # | 变异 | 期望红在哪 |
|---|---|---|
| **N1** | 删掉 `writeResources` 里的 `peer:<to>` | P3 |
| **N2** | 去掉显式 `id`（改用合成 id） | P4 |
| **N3** | `tags` 只放 `from` | P1（B 读不到） |
| **N4** | `ga.SetDecisionMakerPeer` 只写一条记录 | P2 |
| **N5** | `from` 改从载荷取 | 伪造用例（需补一条：A 冒充 B 发信 ⇒ 要么拒、要么记成 A） |

---

## 7. 文件所有权（一个责任区一个 owner）

### 7.1 实现 Agent

✅ 允许改/建：
- **建** `simos-ga/.../ga/spi/SetDecisionMakerPeerHandler.java`（新命令 handler）
- **建** `simos-ga/.../ga/tools/write/SetDecisionMakerPeerTool.java`（GM 面命令工具）
- **建** `simos-ga/.../ga/tools/write/GaMessageSendTool.java`（`simos.ga.message.send`）
- **建** `simos-ga/.../ga/tools/read/GaMessageInboxTool.java`（`simos.ga.message.inbox`）
- **改** `ToolResources`（加 `PEER_NAMESPACE`）、`GaCommandNames`（注册新命令）
  ★★ **2026-10-14 控制方更正（实测）：新 handler 的 `type()` 必须保持**同文件字面量**，`不许`改成 `GaCommandNames.X`** ——
  `SimosToolsTest.handlerTypesFromSources():1202` 的扫描器**只在同一个 `*Handler.java` 文件内**解析常量
  （`Pattern literalConstant` + `while (constant.find())`）；常量搬到别的文件 ⇒ 该 handler 被**静默跳过** ⇒ 审计**静默变瞎**。
  已实测：全仓**没有任何** handler 用 `GaCommandNames.X` 当 `type()`（`GaCommandNames` 仅 1 个常量），现状是干净的。
  ⇒ 本条初稿的"改 `GaCommandNames`（注册新命令）"**作废**。
- **改** `simos-app/.../tools/SimosToolSource.java`（注册三个新工具 + 删两条旧注册）
- **删** §4.5 清单里的旧件 + 其 GUI 面
- **建** `.superpowers/sdd/2026-10-14-dm-messaging/impl-ledger.md`

❌ 禁止：`src/test/**`（测试 Agent 管）、`docs/**`、`AGENTS.md`、隔壁三仓、`sd` 权限命名空间本体。

### 7.2 测试 Agent（实现方交账后）

✅ 允许改：`simos-ga/src/test/**`、`simos-app/src/test/**`、`.superpowers/sdd/2026-10-14-dm-messaging/test-ledger.md`
❌ 禁止：任何生产文件（发现缺口 ⇒ 上报）

### 7.3 控制方（我）

派单 / 验收 / 真机实测 / 落盘文档 / 提交。**不写代码**。

---

## 8. 现场提醒

1. 一次只跑一个 Maven：`tools/mvn-lock.sh`；跑测试前 `rm -rf */target/surefire-reports`。
2. 工作树含 **AgentLib 接入批次（未提交）** —— 动手前 `git status --porcelain`，**别动别人的改动**。
3. 加工具会改 GM/DM 桶计数 ⇒ 钉死工具面的既有用例会红（`SimosToolsTest` / `McpServerTest` / `McpPortTopologyTest` / `ShellAssemblySmokeTest` / `DecisionCallerFactoryTest`），**归测试 Agent**，实现方只需列出。
4. **不 `git commit`**。

---

## 9. 未核 / 如实记（写本文时的诚实边界）

1. **未跑 Maven**：所有"会不会红"均为静态推导；基线计数以最近一次 `clean verify`（3225/0/0）为准。
2. **未核**：`peer` 作为**资源命名空间**是否需要登记进某个白名单/枚举（`ToolResources` 之外是否还有第二处命名空间表）—— 实现方**先查再定**，若有 ⇒ 一并登记并写进账本。
3. **未核**：`ActorAdjustAccountsTool:74` 那种"按参数取资源"的实际判定是否**真的读到了 `accessLimit`**（本文只核了"口子存在"与"`accessLimit` 收命名空间→前缀表"，**两者相接那一环未实测**）—— ★ 这是**本设计的关键假设**，实现方必须**先做一次最小实测**（设 `accessLimit.peer=["X"]` ⇒ 调 `send{to:X}` 应过、`to:Y` 应拒），**不通就 BLOCKED 上报**。
4. **未核**：`SdInfoEntry.tags` 是 `Set<DecisionMakerId>`，而 `DocVisibility` 比的是 **actorId 字符串**（`decision-maker:<id>`）⇒ **两边是否真能匹配上**（ID 形态转换点在哪）—— 同 3，属关键假设，**必须实测**。
5. **未核**：GUI 面里 `reports` 相关视图/路由的**完整清单**（§4.5 要求"先 grep 列全再删"）。
6. **未定**：`subject` 字段是否保留（用户只说"一条交流信息"）—— 本文按保留处理，若不要可删（不影响其余设计）。

---

## 10. 实现期裁定（控制方，2026-10-14）—— 实现方交账里的 D1~D5 逐条

实现方（docs-wiring）交账时提了 4 条偏离 + 1 处上报，控制方**逐条回代码核实**后裁定如下。

| # | 事项 | 裁定 | 依据 |
|---|---|---|---|
| **D1** | 新命令的 `type()` **不**加进 `GaCommandNames`，保持同文件字面量 | ✅ **批准**（且本文 §7.1 原指示**作废**，已就地更正） | `SimosToolsTest.handlerTypesFromSources():1202` 的扫描器**只在同一 Handler 文件内**解析常量；实测全仓无 handler 用 `GaCommandNames.X` 当 `type()`（该文件仅 1 个常量）⇒ 搬常量会让该 handler 被**静默跳过**、审计**静默变瞎** |
| **D2** | GUI 面 grep **零命中** ⇒ 没删任何 GUI 东西 | ✅ **批准** | §4.5 那行是**假设**，不是实测清单（本文 §9-5 已自认未核）；零命中即无可删 |
| **D3** | 另改了 3 个未列在 §7.1 写面清单的文件：`ToolResources`(ga-spi) · `Shell.java`(handler 注册) · `CatalogTool.java`(PAYLOAD_HINTS) | ✅ **追认**（§7.1 写面清单**遗漏**，控制方的错） | `ToolResources` 本就由 §7.1 授权；`Shell` 注册 handler 必需；`CatalogTool` 的 `PAYLOAD_HINTS` 缺项 ⇒ **Shell 起不来**（task-6 已实测踩过这条坑） |
| **D4** | 授权命令**顺带**把两条消息工具并进双方 `allowedTools`（**空 `allowedTools` 一个字不碰**） | ✅ **批准** | 实测：`whitelistFor(dm)` = 全局 ∩ `dm.allowedTools()` ⇒ 非空白名单里没列这两条工具的人，**被授权也只会 `PERMISSION_DENIED`** —— 与用户原话「只有 GM 授权…才能使用新的通讯工具」**相悖**。空白名单 = 沿用全局（更宽）⇒ 填名字反而**收窄**，故不碰。★ **代价如实记**：授权命令现在**写两样东西**（peer 授权 + allowedTools），比初稿多一处副作用 |
| **D5** | ★★ `GaMessageInboxTool` 自己实现了归属判据（`tags().contains`） | ❌ **必须改**（不是改测试） | 既有守卫 `DecisionResultsVisibilityGuardTest.theTagsOwnershipRuleIsImplementedExactlyOnceAndOnlyInRedactingQueryService` 逐字要求该判据**全仓恰一份**；实测 `Actual size = 2`（`RedactingQueryService` + `GaMessageInboxTool`）。§4.3 已就地更正：**inbox 走既有可见性通路，只按 `key`/`to` 再筛一层** |

### 10.1 实测确认的两条关键假设（本文 §9-3/§9-4 的地基 —— **都成立**）
| 假设 | 实测结论 |
|---|---|
| 按参数取资源**真读到** `accessLimit` | ✅ `accessLimit.peer=[dm-b]` ⇒ 该 DM 的 `peer` 声明 `prefixes=[dm-b]` ⇒ `require(WRITE, peer:dm-b)` 过、`peer:dm-c` 拒 |
| `tags`(`Set<DecisionMakerId>`) 与 actorId 字符串**真能匹配** | ✅ 写 `tags=[dm-a,dm-b]` 的 INFO ⇒ 读回元素为 `DecisionMakerId`；用**与 app 逐字同形**的 `decisionMakerIdOf(identity).map(DecisionMakerId::new)` 判 ⇒ a/b 可见、c 不可见 |

★ 但**各有一环是初稿没写的、写错即废**（实现方实测逼出，已写进代码注释）：
1. **manifest 策略必须是 `DENY`**：调用者未表态时 `ResourceAuthorizer` **回落声明策略** —— 若用 `UNRESTRICTED` ⇒ **未授权 DM 能给任何人发信**（I2 整条失效，fail-open）；
2. **自指域必须"并集"且加在 `narrowTo` 之后**：`resourceScopesFor` 末步是**交集** ⇒ `[对方]` 与 `{自己}` 求交 = **空** ⇒ **被授权的人反而发不出信**。

### 10.2 其余交账事实
- 端到端（真 Shell + 真命令 + 真工具）：**I3 只 +1 revision 且双向入表** · P1 A发B收正文逐字 · P2 双向 · P4 同 tick 再发 `REJECTED: INFO 条目 id 已存在` **零 revision** · P3 未授权 `RESOURCE_DENIED WRITE peer:dm-c` **零 revision** · P7 第三方 inbox 空 · **P7b 零授权 inbox 可用 + send 全拒** · P5 跨 tick 可再发 · I7 `gate=Allow` / `{peer=DENY}` · I9 无 `sd:` 地址 · P8 两面旧族零残留。
- **工具面计数与上一批逐字相同（GM 173 / DM 48）** ⇒ `hasSize` 锚点**不用改**；红的是**名单类**断言。
- 控制方独立实跑（`rm -rf */target/surefire-reports && tools/mvn-lock.sh -q -pl simos-app -am test`）：**rc=1 / Tests=3225 / Failures=12 / Errors=2 / Skipped=5**，7 个红类。
  ★ 其中 **`SimosToolsTest` 的 2 个 Error**（`readsAreAllowGatedAndDeclareTheirResources:2157`、`everyReadToolIsAnswerWithItsMinimalShape:1470` 的 `NoSuchElementException`）是**删掉旧读工具**的直接后果 ⇒ 归测试 Agent。
  ★ **D5 那条红必须由生产侧修**，改了才谈得上"唯一归属判据"。

### 10.3 遗留（上报，已处理 / 待裁定）
1. ~~**死代码**：`reportVisibleToDecisionMaker` 现已零调用点，留待独立清理批~~ ⇒ ★ **本条判断已作废**（D5 修复后更正）：
   该端口**不是死代码，恰恰是 `inbox` 该走的既有通路** —— D5 的修法就是**改用它**，故它**重新有了生产调用点**。
   - 端口**已改名**：`reportVisibleToDecisionMaker` ⇒ **`infoVisibleToDecisionMaker`**（`report` 族已删，原名撒谎）；
     覆盖 ga 端口声明 + app 实现 + inbox 调用点三处；★ `src/test/**` **零引用** ⇒ 测试面无跟改。
   - ★ **残余 2 处旧名 + 撒谎 javadoc**（`RedactingQueryService:458` 的 static 定义体、`:444-457` 仍写"上报 / `sd:doc.report-…` / 自己发的报告"）
     不在 D5 那次的写面内 ⇒ **须一并收掉**（否则下一个读代码的人被误导）。**控制方已授权**（见 §10.5）。
2. **新消息在 GUI 上还没有展示**（设计书未要求）⇒ 用户 2026-10-14 已裁定：**GUI 放着，先继续改后端**。

### 10.4 ★ `inbox` 可见性的**有意放宽**（控制方裁定）
D5 修复后，`inbox` 的可见性 = **既有端口的三轴并集**（`tags` 命中 / `affiliations` 命中 / **发送人=自己**），
**而非**上一批那种"只认 `tags` 含自己"的单轴判据。实现方如实上报了这处放宽，控制方裁定：**接受**。

理由三条：
1. **本仓教义是"归属判据只许有一份"** —— 为消息另写一条更窄的判据，**正是那条守卫要挡的形态**（第二份不报错、只会漂移）；
2. **当前写入方下结果集逐条不变**：`GaMessageSendTool` 落的条目 `affiliations` 恒空（§4.2）⇒ 该轴永不命中；
   "发送人=自己"那一支被 `to==自己` 那道筛子**抵消**（§4.3 判据③）⇒ **实际集合与单轴版相同**；
3. 若将来真要更窄的消息可见性，那是**一次设计变更**，应由提出者**明写**，不该靠一处隐式副本。

★ **因此验收判据钉"行为契约"而不是"机制"**（机制可换、契约不许动）：
- 收件人**看得见**寄给自己的信；· **第三方看不见**；
- ★ **发件人在自己的 `inbox` 里看不到自己发出去的信**（这条正是 `to==自己` 那道筛子的靶子，也是本次放宽的**唯一风险面**）；
- GM 看得见全部。

### 10.5 控制方对 D5 那次的追认（2026-10-14）
| 事项 | 裁定 |
|---|---|
| 越出 3 文件清单改了 `SimosToolSource:1572-1575`（+4/−1） | ✅ **追认** —— 端口只能**构造器注入**（`ToolContext` 无服务定位口、`ToolReadPort` 是窄读口读不到 `GaToolQueries`），不改装配点**无法 compile** |
| 端口改名 | ✅ 批准 |
| 授权收掉残余旧名 + `RedactingQueryService:444-457` 撒谎 javadoc | ✅ **已授权**（2 个文件；属"改名一致性"，不是新功能） |
| ★ 实现方指出控制方给的 grep 形态在本机 **rc=2**（`grep` 不做 glob 展开） | ✅ 属实，控制方写法有误；它改用可用形态并把输出贴了回来 —— **这正是"造一个已知会命中的样本"的正确做法** |

---

## 11. ★ 上游 R8 两个"能力自述"工具的接入（2026-10-14，控制方裁定）

### 11.1 现场
测试 Agent 实跑基线时发现：**任何 `Shell.start(...)` 都在装配期炸**，407 个 Error 同一根因：
```
IllegalState 外来工具角色表里没有 doc_guide（AgentLib 侧新增或改名了工具？）
——「这个工具能被谁用」必须逐条声明，不许有缺省角色（设计书 §4.2 / I6）
```
根因：上游 `~/ProjectMosire/{DocsMosire,WorkspaceMosire}` commit **`4a4084d`** 新增两个工具
（`DocsTools.DOC_GUIDE="doc_guide"` / `WorkspaceTools.WORKSPACE_INFO="workspace_info"`），
本地 `~/.m2` 两个 jar **19:07 重建**已含它们；而本仓 `SimosToolSource.EXTERNAL_TOOL_ROLES` 只有 **10 条**、
`rolesOf()` 对未登记项 **fail-closed 抛**。

★ **这是不变量 I6 按设计生效**（"上游加工具 ⇒ 我们这边当场红，不许静默给缺省角色"），**不是回归**。

### 11.2 上游 R8 的由来（逐字，它就是为一个真机问题做的）
> 用户 2026-10-14 裁定：「额外做一个查看信息的工具，查看工作区就是查看工作区是啥，查看doc就是简单了解doc系列工具的用法」
> 隔壁真机事实：工具在面、工作区在盘、审批链在等，但**模型不知道自己的挂载叫什么**，于是拿工具名当挂载名猜
> （`mount=simos_sd_packet_intent` / `mount=simos-command-catalog`）

⇒ 这与控制方 2026-10-14 真机实测**同一个问题**（见 `docs/superpowers/plans/2026-10-14-agentlib-docs-wiring.md` §12.2）。

### 11.3 裁定：**(A) 采纳上游 R8，不锁回旧 jar**
锁回旧 jar 等于把用户刚裁定要的工具挡在门外 ⇒ **不采纳 (B)**。

**两个工具的角色（控制方反编译核实其规格）**：两者都是 `ResourceManifest.NONE` + `ToolSpec.level(DEFAULT, false, false)`
⇒ **纯读**（可导出、不敏感、不破坏）⇒ 按**用户原则**（逐字）：
> 「只要涉及到真实世界变动（一个工具导致世界中某项数据变化），那GM必须审核，**如果只是查看**，查看范围应当是对决策人的权限范围的限制，
> **GM理论上事先已经做好了**，所以**不需要也不应该让 GM 审批，走 DM_FREE**」

⇒ **两个都给 `{GM, DM}` + `gate = ALLOW`（`DM_FREE`）**，**不进审批那一路**：

| 工具 | 角色 | 归类 |
|---|---|---|
| `DocsTools.DOC_GUIDE` | **`{GM, DM}`** | 类3 `DM_FREE` |
| `WorkspaceTools.WORKSPACE_INFO` | **`{GM, DM}`** | 类3 `DM_FREE` |

**计数随之变化**：GM 面 **173 → 175**、决策人面 **48 → 50**。

### 11.4 分工
- **生产侧**（`subagent 3cb7e605`）：`EXTERNAL_TOOL_ROLES` 加 2 条 + 进**读工具面**名单 + `compile`/`test-compile` rc=0；
- **测试侧**（测试 Agent）：名单类旧名替换、按**实测**改齐计数、补 P/N 判据与变异自证、最终 `clean verify` rc=0。
- ★ **`src/test/**` 的计数断言一律归测试侧** —— 生产侧只做生产。

---

## 12. 控制方对测试侧两处提问的裁定（2026-10-14）

★ **背景**：一次性 `subagent` **中途不可 `send_message`**（实测：寻址不到），故中途裁定的传递方式是
**落盘（本节）+ 另派单**。测试 Agent 的提问因此在这里答复。

### 12.1 `SimosToolsTest` 两处 Error 的修法 ⇒ **采纳测试 Agent 的分析，`impl-ledger §7` 的写法作废**
测试 Agent 指出：`impl-ledger §7` 写"226 行换成 `ga.SetDecisionMakerPeer`"，但
**226 行是「读名单」，而新命令 `ga.SetDecisionMakerPeer` 是窄写工具**（真继承 `AbstractNarrowWriteTool`）。
测试 Agent 的改法：**读名单删 `simos.sd.reports`、`NARROW_WRITE_NAMES` 加 `ga.SetDecisionMakerPeer`**。

**裁定：采纳测试 Agent 的改法。** 理由：`send`（写感知层、`ALLOW`、**故意不继承窄写基类**，不变量 I7）与
`ga.SetDecisionMakerPeer`（真窄写、`Ask`）**必须分处两张表** —— 否则 `writesAreSensitiveAndAskWithTheToolNameAsClassKey`
要么假红、要么把 `send` 扫进去，§4.2 的边界就废了。

### 12.2 测试 Agent 顺手改了**另一批**的测试文件 `AgentLibDocsWiringTest` ⇒ **追认**
该文件属 **AgentLib 接入批**（`docs-wiring` 那批，**未跟踪、未提交**）。它把 `hasSize(10)` /
`externalToolNames()` / doc 工具 6/7 条三处改了，理由是**同一根因**（上游 R8 新增 `doc_guide`/`workspace_info` ⇒ 外来工具面 10 → 12）。

**裁定：追认。** 依据三条：
1. **同一根因、同一修法**（R8 接入，§11），不是两处各自漂移；
2. **该文件的 owner 已停写**（`docs-wiring` 那批的测试 Agent 已交账、无在跑 Maven、文件 mtime 02:45）；
3. 本批 §11.4 已定分工：**`src/test/**` 的计数断言一律归测试侧** —— 但它**越了批**，故**必须在 `test-ledger.md` 里
   单独列出这次越批改动**（改了什么、为什么、与 R8 的因果），供最终评审一眼看到。

★ **"一个文件一个 owner"没有被破**：owner 已停写且该改动是**该批自身的必要修复**；但纪律要求**报备**（它做了）。
