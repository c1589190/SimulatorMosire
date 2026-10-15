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

## 2. ★ 待裁定（命名是内容决定，控制方不代裁）

| # | 问题 | 选项 |
|---|---|---|
| **N1** | 那 **9 条工具名**：`simos.sd.X` → **`simos.ga.X`**（同名后缀）可以吗？ | ★ 甲：`simos.ga.X`（与批 F 的 4 条决策包工具一致：`sd.packet.* → ga.packet.*`）· 乙：另有方案 |
| **N2** | ★★ 那**一对两面**：GM 口改成 `simos.ga.record-diplomatic-event` 后，与 DM 口 `gov.RecordDiplomaticEvent` **命名仍不一致**（一个带 `simos.`、一个不带） | 甲：**两条一起统一**（例如都叫 `simos.gov.*`，因为命令本来就住 `simos-gov`）· 乙：只改 `sd` 那半（**你在 2026-10-14 说「正常语义不用」⇒ 不动 `gov.*`**）· 丙：其他 |
| **N3** | 无前缀的 **3 条**工具名（`gov.RecordDiplomaticEvent` · `gov.CreateNation` · `gov.SetDiplomaticRelation`） | 甲：顺手补 `simos.` 前缀（与全仓一致）· 乙：**不动**（「正常语义不用」） |
| **N4** | 9 条 `/api/sd/*` 路由 + 前端 6 文件 | 甲：→ `/api/ga/*`（与工具名同向）· 乙：换更准的域名词 |
| **N5** | 17~19 个 `Sd*` **类名** | 甲：机械改成 `Ga*`（`SdCreateArmyTool` → `GaCreateArmyTool`？读起来怪）· 乙：按**语义**改（如 `SdCreateArmyTool` → `CreateArmyTool`/`ArmyCreateTool`）· 丙：只改确实带 `Sd` 语义的 |

★ **控制方的倾向（仅供参考，不代替裁定）**：
- **N1 甲**：与批 F 已做的 4 条同向，机械、可全仓扫描验证。
- **N2 甲**（两条一起统一到 `simos.gov.*`）：理由是**命令本来就住 `simos-gov`**，而"GM 口/DM 口"是**面**不是**域**；
  但这一条**越过了你「正常语义不用」那句**，所以要你点头。
- **N3 甲**：顺手统一，成本极低；同样越过「正常语义不用」。
- **N4 甲**。
- **N5 乙**（按语义）：`Sd*` 里的 `Sd` 是**旧模块名**，删掉后应留下**语义**（`CreateArmy` / `CreateNation` / …），
  而不是把 `Sd` 换成 `Ga`（`GaCreateArmyTool` 无意义 —— 这些类**不属于 ga 的领域**，只是住在 ga 模块）。

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
