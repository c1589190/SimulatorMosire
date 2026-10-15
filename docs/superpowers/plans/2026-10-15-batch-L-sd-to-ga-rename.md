# 批 L 设计书 —— ② 旧招牌收尾：`sd → ga`（+ 命名口径统一）

> 用户裁定（2026-10-14，逐字）：**「sd 整个弃用！」**「**但凡和被取缔的 sd 模块有关的就改，正常语义不用**」
> 批次顺序（用户 2026-10-15）：⑤(含①) → **② 旧招牌** → ④ 零件 → ③ 真 LLM（最后）
> 前序：批 F（`6f157693`）已改**权限域名 `sd → ga`** 与 4 条决策包工具名；本批收尾**剩下的**。

---

## 1. 现状（控制方逐个 grep 核过，2026-10-15）

### 1.1 九个 `simos.sd.*` **工具名**（★ 全部**仍注册在工具面上**，各被 `new` 一次 ⇒ 是活工具，不是死常量）

| # | 现名 | 类 | 类所在 | 形态 | `roles()` |
|---|---|---|---|---|---|
| 1 | `simos.sd.decision-maker` | `DecisionMakerTool` | simos-ga | 读 | ? |
| 2 | `simos.sd.decision-makers` | `DecisionMakersTool` | simos-ga | 读 | ? |
| 3 | `simos.sd.diplomacy` | `SdDiplomacyTool` | simos-ga | 读 | ? |
| 4 | `simos.sd.diplomatic-events` | `SdDiplomaticEventsTool` | simos-ga | 读 | ? |
| 5 | `simos.sd.directives` | `SdDirectivesTool` | simos-ga | 读 | ? |
| 6 | `simos.sd.verdicts` | `SdVerdictsTool` | simos-ga | 读 | ? |
| 7 | `simos.sd.run-decision-makers` | `RunDecisionMakersTool` | simos-ga | 写（编排） | ? |
| 8 | `simos.sd.record-diplomatic-event` | `SdRecordDiplomaticEventTool` | simos-ga | 窄写 | **`{GM}`** |
| 9 | `simos.sd.set-diplomatic-relation` | `SdSetDiplomaticRelationTool` | simos-ga | 窄写 | **`{GM}`** |

### 1.2 ★★ 一对"两面"（**不是重复缺陷**，控制方核过）

| 命令 | GM 口 | DM 口 |
|---|---|---|
| `gov.RecordDiplomaticEvent` | `simos.sd.record-diplomatic-event`（**`{GM}`**） | `gov.RecordDiplomaticEvent`（**`{DM}`** ⇒ 类2） |
| `gov.SetDiplomaticRelation` | `simos.sd.set-diplomatic-relation`（**`{GM}`**） | `gov.SetDiplomaticRelation`（**`{DM}`** ⇒ 类2） |

⇒ 同一条命令的 **GM 面 + DM 面**，是**有意的两面**，不是重复。
★★ 但它把命名问题暴露到极致：**GM 口叫 `simos.sd.*`（旧世界），DM 口叫 `gov.*`（连 `simos.` 前缀都没有）** —— **两套命名都乱**。

### 1.3 十八个 `Sd*` **类名**
- `simos-ga`：**17** 个（`SdCreateArmyTool` · `SdCreateNationTool` · `SdCreateDecisionMakerTool` · `SdCancelEffectTool` ·
  `SdCommandDrain` · `SdPutInfoTool` · `SdQueryService` · `SdRegisterEffectTool` · `SdSetArmyMasterGovTool` ·
  `SdSetDecisionMakerProviderTool` · `SdInfoEntry` · `SdInfoId` · `SdInfoIds` · `SdDiplomacyTool` ·
  `SdDiplomaticEventsTool` · `SdDirectivesTool` · `SdVerdictsTool` · `SdRecordDiplomaticEventTool` ·
  `SdSetDiplomaticRelationTool`）★ 逐个核过是这些，**共 19**（调查清单 #61 记 17，以本次为准）
- `simos-app`：**2** 个

### 1.4 九个 `/api/sd/` **真路由**（不是注释）
```
/api/sd/decision-docs · decision-makers · decision-results · directives
/api/sd/reset-decision-maker-conversation · run-decision
/api/sd/set-decision-maker-provider · start-decision · verdicts
```
★ **前端 6 个文件**引用 `api/sd/`：`api.js` · `modes.js` · `decisionmodel.js` · `index.html` · `map.js` · `panels.js`
★ 另有约 **63 处**是注释/说明里的 `sd` 字样（不是路由）。

---

## 2. ★ 已裁定（用户 2026-10-15）

| # | 问题 | **裁定** | 落地 |
|---|---|---|---|
| **N1** | 9 条工具名 | ★ **甲：`simos.sd.X` → `simos.ga.X`**（与批 F 的 4 条决策包工具同向） | 除下面两对外，其余 **7 条**按此改 |
| **N2** | ★★ 那对"两面" | ★ **乙 + 合并甲**：**两条一起统一到 `simos.gov.*`**，且**合并成一条**（见 §2.1） | 两条命令各留**一条**工具 |
| **N3** | 无前缀的 3 条 | ★ **甲：补 `simos.` 前缀** | `gov.CreateNation` → `simos.gov.createNation`（另两条由 N2 的合并吸收） |
| **N4** | 9 条 `/api/sd/*` 路由 + 前端 6 文件 | ★ **甲：→ `/api/ga/*`** | 与工具名同向 |
| **N5** | 19 个 `Sd*` 类名 | ★ **乙：按语义删掉 `Sd`**（`SdCreateArmyTool` → `CreateArmyTool` 等）—— 这些类**不属于 ga 的领域**，只是住在 ga 模块 | 逐个按语义命名 |

### 2.1 ★★ 合并甲的确切形态（那两对）

**控制方核过的事实**：两条**不是同一份实现** ——
| | GM 面（`Sd*`，74 行） | DM 面（`gov.*`，103 行） |
|---|---|---|
| 目标怎么定 | **显式点名** id | ★ **从身份派生**（`GaToolIdentity` + `DecisionMakerAffiliationPort`；归属不是 Nation ⇒ 具名拒） |

⇒ **合并成一条**，`roles={DM, GM}` + `gate=Ask(SENSITIVE)` ⇒ **类2**（"DM/GM 混合就是要交给 GM 审核"）；
**目标解析按调用者身份分支**：
- 调用者是 **DM** ⇒ 走身份派生（复用现成那套 `GaToolIdentity` + `DecisionMakerAffiliationPort` + `DiplomaticWriteSignature`）
- 调用者是 **GM** ⇒ 显式点名

★ **这不是新发明**：本仓**已有同一模式** —— `GovToolSupport.resolveDecisionMakerGov`（"给了 `decisionMakerId` 就从身份解，
否则要求显式 `govUnitId`"），正是批 K ⑤-16 刚记录进文档的那一条。⇒ **合并 = 把两侧收敛到既有模式**，
而不是造第三套。

**两条命令的最终名**：`simos.gov.recordDiplomaticEvent` · `simos.gov.setDiplomaticRelation`。

★★ **计数影响**：类1 **133 → 131**（两条 GM-only 消失）· 类2 **24 不变**（两条名字保留位置，只是改名）· 类3 **37**。
⇒ 与批 K 的锚点**必须同步**（`ToolClassAuditAssemblyTest` 等）。
★ 另：`SdRecordDiplomaticEventTool` 的类注还写着「写面只声明 **`sd`** 命名空间（handler 只产 sd 变更集）」
—— **又一处旧名撒谎**，随合并一并改。

---

## 3. 影响面（**大**，所以先定命名再动）

| 面 | 影响 |
|---|---|
| `simos-ga/src/main` | 9 条工具名常量 + 17~19 个类名 + 注释 |
| `simos-app/src/main` | 9 条路由 + 2 个 `Sd*` 类 + 装配面的 `new Sd*` |
| **前端 6 文件** | `api/sd/` 引用 |
| **`src/test/**`** | ★ 大量字面量（`SimosToolsTest` / `McpServerTest` / `McpPortTopologyTest` / `DecisionFaceGolden` / `DecisionCallerFactoryTest` / `ShellAssemblySmokeTest` …） |
| `docs/**` | 设计书/status 里的旧名引用（★ **只改"现行口径"那几处，历史留痕不动**） |

★ **纪律**：本批必须**全仓扫描验证**（`src/main` **与** `src/test` **与**前端），
且**不许留下"改了源码没改测试"**的半成品 —— 那正是"死角"的成因（门控用例 10 处旧名就是这么来的）。

---

## 4. 验收判据（草案）

| # | 判据 |
|---|---|
| **L1** | `git grep -n '"simos\.sd\.'` 在 `simos-*/src/main` = **0** |
| **L2** | `git grep -n '"/api/sd/'` 在 `simos-app/src/main` = **0**；前端 `api/sd/` = **0** |
| **L3** | 9 条新工具名**逐条**真调通（生产装配，`ShellAssemblySmokeTest` 或仓库外夹具） |
| **L4** | ★ **旧名一律不留**（照 `DecisionCallerFactoryTest:302-307` 的 `doesNotContain` 形制，为 9 条各加一条**反向**断言） |
| **L5** | ★ **全仓字面量扫描**：`src/test/**` 里旧名 = 0（**这就是"死角"的根治**） |
| **L6** | 三格计数**不变**（本批**不动任何 `roles()`/`gate()`**） |

---

## 5. ★★ 派单后的三条裁定（2026-10-15，实现方报 BLOCKED 后）

### 5.1 控制方的任务书**自相矛盾**，实现方报得对

任务书同时要求：① 改 19 个 `Sd*` **public 类名** ② **禁碰 `src/test/**`** ③ `-DskipTests test-compile` **rc=0**。
★ **三者不可同时满足**：`-DskipTests` **只跳执行、不跳编译**（根 `pom.xml:218` 的 `testCompile` 照跑），
而 `src/test` 对这批类是**代码级标识符引用**（不是字符串）—— **16/19 个类被测试引用，合计 168 处 / 24 个文件**；
且 §1.2 的**合并会删掉两个类**（`SdRecordDiplomaticEventTool` / `SdSetDiplomaticRelationTool`），
测试里直接 `new` 它们 ⇒ **合并本身也破测试编译**。

► **裁定：甲（授权机械标识符改名）**。理由：
- 那是**同一次改名的另一半**（类名改了、引用没改 = 半成品），**不可分割**；
- 乙（只保证 `compile` 绿）会**把树留在编译不过的状态**，且"编译过"这条验收等于没法验；
- 丙的次序**物理上不可能**（测试侧要先引用一个还不存在的类）。
★ **边界写死**：只改 `SdXxx` → 新名的**标识符 / import / 构造调用**；
★ **不许动**任何**断言 / 期望值 / 字符串字面量**（旧**工具名字面量**仍归测试代理）。
★ 台账里要**列出**旧工具名字面量在 `src/test` 的全部命中（供派测试代理）。

### 5.2 `SdInfoEntry` 改名会与既有 `InfoEntry` **语义冲突** ⇒ 三个一起改成 `DecisionInfo*`

实现方核出：`io.mosire.simos.util.info.InfoEntry` 已存在（`simos-util` 主源码），而 `SdInfoEntry` 的类注开宗明义写
「**自造**、**不复用**全局 `InfoEntry`」⇒ 改名成 `InfoEntry` 会让那句注释**自己打脸**。
► **裁定**：三个一起改成 **`DecisionInfoEntry` / `DecisionInfoId` / `DecisionInfoIds`**
（保持三者的**成组性**；且与全局 `InfoEntry` **不再撞名**，注释那句话仍然成立）。

### 5.3 ★★ 主源码还有 **7 条 `sd.` 前缀的工具名**，§1.1 漏了 ⇒ **改**（映射如下）

实现方核出（**全是活工具名**，被 `SimosToolsTest` / `McpServerTest` / `McpPortTopologyTest` 当工具名断言）：
```
sd.VoidAdjudication · sd.AdjudicateTick · sd.SetArmyMasterGov · sd.CreateArmy
sd.RejectDirective · sd.DecisionResults · sd.DecisionDocs
```
★ 其中 `SdCreateArmyTool` / `SdSetArmyMasterGovTool` **正是 §1.3 要改类名的两个** —— **类名改了、工具名里的 `sd.` 还留着**，本身就是半成品。
► **裁定：改**（用户原话「但凡和被取缔的 sd 模块有关的就改」覆盖它们）。目标 = **`simos.ga.*` + camelCase**（与 `simos.ga.run-decision` / `simos.ga.packet.*` 同族）：
| 现名 | 新名 |
|---|---|
| `sd.VoidAdjudication` | `simos.ga.voidAdjudication` |
| `sd.AdjudicateTick` | `simos.ga.adjudicateTick` |
| `sd.SetArmyMasterGov` | `simos.ga.setArmyMasterGov` |
| `sd.CreateArmy` | `simos.ga.createArmy` |
| `sd.RejectDirective` | `simos.ga.rejectDirective` |
| `sd.DecisionResults` | `simos.ga.decisionResults` |
| `sd.DecisionDocs` | `simos.ga.decisionDocs` |

★★ **但先核清一件事**：这 7 个**是不是同时又是「命令类型」**（`DirectiveWhitelist:66` 的 `type.startsWith("sd.")` 说明确实存在 `sd.*` **命令类型**）。
⇒ **命令类型不动**（铁律 2 的口径 + 用户「正常语义不用」）；**只改工具名**。核出重叠就**如实报备**，别顺手改命令类型。

### 5.4 ★★ 控制方两条判据**会假绿**（实现方核出，接受更正）

| # | 我写的 | 实际 | 正确写法 |
|---|---|---|---|
| **L1** | `git grep -n '"simos\.sd\.' -- 'simos-*/src/main'` | ★ **rc=1、零命中** —— 该通配 pathspec **匹配不到任何文件** ⇒ **静默假绿** | `git grep -n '"simos\.sd\.' -- ':(glob)simos-*/src/main/**'` ⇒ **9**（当前真实值） |
| **L2** | 「前端 `api/sd/` = 0」 | 前端用 `API_BASE="/api"`（`webui/api.js:11`）+ **段路径** ⇒ 真断点是 **`"/sd/`**（`api.js` 11 处 + `decisionmodel.js` 3 处 = **14 处**）；而 `api/sd/` 在前端**只在注释里**（26 处） | 判据改成 **`"/sd/` 段路径 = 0** |

★★ **这条教训要进纪律**：**「通配 pathspec 匹配不到 ⇒ `git grep`/`git diff` 返 0 而不报错」**
—— 控制方**今晚自己就踩过同一个坑**（`git diff -- 'simos-*/src/main'` 静默空 ⇒ 我据它报了"`gate()` 零改动"的假绿，
后来用无 pathspec 的 `git diff | grep` 才核出真相 7 增 7 删）。
⇒ 纪律：**用通配 pathspec 做"零命中"判据时，必须先用一个已知存在的样本证明该 pathspec 真能匹配到文件**（否则 0 是假的）。

### 5.5 实现方实测的基线（供复核）

```
L1 main "simos.sd.  = 9
L2 java "/api/sd/   = 14（全在 GuiServer.java）
L2 前端真断点 "/sd/ = 14
L5 src/test 9 条旧工具名 = 51 处 / 6 文件
L6 未实测（推导：类1 133→131 · 类2 24 不变 · 类3 37）★ 是推导不是实测
```

### 5.6 ★ 合并的**更贴近先例**（上一任在 BLOCKED 时已把合并设计好，供实施者直接用）

控制方原指向 `GovToolSupport.resolveDecisionMakerGov`；上一任找到**更同形**的仓内先例：
**`IssueDirectiveTool`** —— 它本身就是「`{DM,GM}` + 身份分支」：
- `roles() = {DM, GM}`（`IssueDirectiveTool:110` 同形）
- `resources() = GA_NAMESPACE, READ_ONLY` + `decisionWriteResources(context)` —— **`resources()` 本身就是身份分支**
- `gate` 继承基类 ⇒ `Ask(SENSITIVE)` ⇒ **类2**

**它的合并方案（未落码）**：
- `signatureViolation` 里判 `identity().decisionMakerIdOf(...).isEmpty()`：**空（= GM）⇒ 不判署名**（GM 显式点名）；**非空（= DM）⇒ 走现成的 `DiplomaticWriteSignature`**
- `commandType()` **保持** `gov.RecordDiplomaticEvent` / `gov.SetDiplomaticRelation`（命令类型不动）
- 另覆写 `toolName()` 返回新名 `simos.gov.recordDiplomaticEvent` / `simos.gov.setDiplomaticRelation`
- ★ **不造第三套**

★ 实施者：**先读 `IssueDirectiveTool` 与 `GovToolSupport.resolveDecisionMakerGov` 两处**，再动手。
