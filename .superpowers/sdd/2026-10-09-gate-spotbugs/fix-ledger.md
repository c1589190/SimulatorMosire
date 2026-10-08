# 2026-10-09 门禁债：SpotBugs 6 条 → 绿（fix-ledger）

**责任区**：把 `verify` 的 SpotBugs 门禁从 **6 条**修到 **0 条**，使
`tools/mvn-lock.sh -o verify -pl simos-app -am` 到 **exit=0**。
**边界**：只改生产代码；**不碰 `src/test/**`**；不 `git commit`；不 `package`；只跑 `tools/mvn-lock.sh`。

**结论（一句话）**：6 条全部修掉，`verify` **exit=0**（3:35）；Spotless / Checkstyle / SpotBugs / Surefire /
前端门禁**四项全绿**（真数见 §4）。★ 第 ③ 条与控制方给的"参数命名误导"定位**不符**：硬证据表明它是
`GovSetFxRateTool:201` 的**真类型混淆**，已按"真 bug"修，并做了变异实验证明"只改参数名修不掉它"（§3）。

---

## 1. 逐条修法（file:line 为**修后**行号）

| # | 规则 | 文件:行 | 修法 |
|---|---|---|---|
| ① | `DLS_DEAD_LOCAL_STORE` | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:5727` | `DebtContract contract = DebtContractBook.upsert(...)` → **去掉接收变量**，只留 `DebtContractBook.upsert(...)` 语句（写入副作用原样保留）。补 3 行注释说明"返回值刻意不接：引用表由 `EconomyData` 构造期的 `DebtReferenceReconciler` 整表重建"。 |
| ② | `DLS_DEAD_LOCAL_STORE` | `simos-economy/src/main/java/io/mosire/simos/economy/time/GovernmentDebtIssuance.java:150` | 同上（同一写口 `DebtContractBook.upsert`，同一理由：拆表后会话内不再补引用）。 |
| ③ | `GC_UNRELATED_TYPES` | `simos-app/src/main/java/io/mosire/simos/app/tools/write/GovSetFxRateTool.java:206`（**真 bug**）+ `GovToolSupport.java:145`（命名） | ① 真修：`data.governments().get(target.govId())` → 先 `GovernmentId governmentId = GovernmentIds.ofUnit(target.govId().value())` 再 `get(governmentId)`（与兄弟工具 `GovRenameCurrencyTool:168` / `GovIssueMoneyTool:206` 同一手法）。② 命名：`GovToolSupport.target(…, UnitId govId, …)` 参数改名 **`govUnitId`** 并同步方法体 4 处 + javadoc 一段（写明"这是 GOV **单位**的 `UnitId`，不是 `GovernmentId`；查政府必须先派生"）。**类型与调用点语义未动**。 |
| ④ | `MS_EXPOSE_REP` | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/money/MoneyVocabulary.java:205` | `allCurrencyDefs()` 改 `return List.copyOf(installedDefs);`（**保序**；javadoc 记明"无活视图调用方 / 零分配快路径"）。 |
| ⑤ | `MS_EXPOSE_REP` | 同上 `:219` | `allInstruments()` 改 `return List.copyOf(installedInstruments);`（同上）。 |
| ⑥ | `MS_EXPOSE_REP` | `simos-app/src/main/java/io/mosire/simos/app/world/ThreePowersWorld.java:544` | `hexes()` 改 `return List.copyOf(HEXES);`（**保序不变**；javadoc 记明"格序是语义的一部分 ⇒ 不许重排""不缓存快照，JDK 快路径零分配"）。 |

**为什么 `List.copyOf` 就够、且零行为差异（三条 MS 同一论证）**

- `installedDefs` / `installedInstruments` **只由 `install()` 整表换引用**，`normalizeDefs` / `normalizeInstruments`
  返回的本就是 `List.copyOf(...)` / `List.of(...)`（不可变）；`HEXES` 由 `hexagon()` 以 `List.copyOf(hexes)` 产出。
  ⇒ 三处 `List.copyOf` 都命中 JDK 的"入参已是 `AbstractImmutableList` 则**原样返回**"快路径：**零拷贝、零分配、
  连引用同一性都不变**。
- **"活视图"排查（控制方要求的确认项）**：全仓 `src/main` 对 `allCurrencyDefs()` / `allInstruments()` 的调用只有
  `MoneyVocabulary` 自己（`currencyDefOf` / `requireCurrencyDef` / `currencyIds`）+ `ApiViews` 的注释提及；
  `install()` 是**换引用**而不是就地改列表（无任何 `installedDefs.add/remove/clear`）⇒ **没有任何调用方依赖活视图**，
  故用"每次调用构造副本"形态即可，**不需要**"install 后自动可见"的额外机制。
- ⑥ 该 getter 在主路径**零调用**（实测：全 15 个模块 `src/main`+`src/test` 里 `ThreePowersWorld.hexes` 命中 0）
  ⇒ 也不存在"高频调用要缓存"的问题；即使被调用，快路径也不分配。

**没有用 `@SuppressFBWarnings` 之类的抑制**：6 条全部是真因修复。

---

## 2. 修前基线（6 条原文）

`evidence/before-spotbugs-6-bugs.txt`（转录自控制方那轮 verify 留下的 `spotbugsXml.xml`，逐条
`LongMessage` + `SourceLine` 原样）。要点：**③ 的 `SourceLine` 是 `GovSetFxRateTool 201`、`Method` 是
`GovSetFxRateTool.execute`**，`LongMessage` 讲的是 `UnitId` vs `GovernmentId` —— 与 `GovToolSupport` 无关。

---

## 3. ③ 的定位更正（**控制方诊断与证据不符**；已按"真 bug"处置）

控制方任务书写的是"③ 非真 bug，是 `GovToolSupport.target(...)` 的参数命名误导"。**证据不支持这个定位**：

1. `GC_UNRELATED_TYPES` 来自 SpotBugs 的 `FindUnrelatedTypesInGenericContainer` 检测器，判定依据是
   **容器类型参数 vs 实参的静态类型**（`Map<GovernmentId, Government>.get(...)` 收到 `UnitId`），
   **不看参数名**；报的 `SourceLine` 是 `GovSetFxRateTool:201`，`Method` 是 `GovSetFxRateTool.execute`。
2. 类型链：`GovTarget.govId()` 的声明类型是 **`UnitId`**（record 组件，`GovToolSupport.java:62`），
   而 `EconomyData.governments()` 是 **`Map<GovernmentId, Government>`**。`UnitId` 与 `GovernmentId` 是
   两个互不相等的 record 类型 ⇒ `equals` 恒 false ⇒ **这条 get 改前恒返回 `null`**。
3. 后果（改前的真实缺陷，不夸大）：`existing` 恒 null ⇒ 工具视图的 `previousBuyPerMille` /
   `previousSellPerMille` **永远是 null**（"币对现值"丢读）。**不影响提交的命令**（`payload()` 不含 `existing`），
   也不影响落盘状态。
4. 全仓同类调用点核对：`*/src/main` 里 `governments().get(...)` 共 19 处，**只有 GovSetFxRateTool:201 这 1 处**
   传的是单位 id；其余 18 处（`GovRenameCurrencyTool` / `GovIssueMoneyTool` / `MilitaryPayRuleBridge` /
   `EconomySetOfficialRateHandler` / …）全部传 `GovernmentId`（多为 `GovernmentIds.ofUnit(...)` 派生）。
   ⇒ 是**孤例走样**，不是系统性设计。

**变异实验（判别力自证，证据 `evidence/mutant-govSetFxRate-spotbugs.log`）**

| 轮 | 树的状态 | 命令 | 结果 |
|---|---|---|---|
| 修后 | ③ 的两处都在（重命名 + `:206` 派生） | `verify -pl simos-app -am`（内含 `spotbugs:check`） | simos-app **`BugInstance size is 0`**（全 15 模块皆 0） |
| **变异** | **只回退 `:201`**（改回 `get(target.govId())`）**，保留 GovToolSupport 的参数重命名** | 先 `compile -pl simos-app -am`（exit=0，变异体 md5=`06d66bcc…` ≠ 修复版 `baa78e22…`），再 `spotbugs:check -pl simos-app` | **`BUILD FAILURE`，BugInstance size is 1**：`[ERROR] High: io.mosire.simos.unit.UnitId is incompatible with expected argument type io.mosire.simos.economy.api.id.GovernmentId in …GovSetFxRateTool.execute(ToolContext) … At GovSetFxRateTool.java:[line 207] GC_UNRELATED_TYPES` |
| 还原 | `cp` 回备份（md5 比对一致 `baa78e22…`） | 重跑**全量** `verify` | **exit=0**（§4 的真数即这一轮） |

⇒ 结论：**重命名参数**修不掉这条；**只有把 :201 的键派生出来**才修得掉。变异体红在**被修的那一行**
（`GovSetFxRateTool.java:[line 207]` = 变异文件里的那一行 get），红的理由正确。

★ 按 §一.8 三级处置的归类：这不是"纯实现困难"，也不是需要用户裁定的设计冲突 —— 是**控制方任务书的定位写错**
（把一处真类型混淆写成了命名问题），修法与证据齐备，故**当场修掉并如实上报**，未改任何契约/权限/边界。

**回退点（若控制方不认这条越出授权）**：修复只占 `GovSetFxRateTool.java` 的 **2 行**（+1 行派生、换 1 行实参）
与 6 行注释，`git diff` 可见；单独回退它**不影响** ①②④⑤⑥，也不需要动任何测试。回退后的后果是
`previousBuy/SellPerMille` 重新恒为 null、且 SpotBugs 门禁**重新变红**（变异实验已证）。

---

## 4. `verify` 真数（**唯一验收判据**：exit=0）

**命令**：`tools/mvn-lock.sh -o verify -pl simos-app -am`（不带任何 skip）
**跑前**：`pgrep -af "surefirebooter|classworlds.launcher"` → 无命中（无并发 Maven）；`rm -rf */target/surefire-reports` 清干净。
**结果**：**exit=0 / BUILD SUCCESS / Total time 03:35 min**（日志 `verify-run2-final.log.gz`，摘要 `verify-run2-gates.txt`）

| 门禁 | 真数 | 覆盖 |
|---|---|---|
| **Spotless** | **0 个文件需改动**（`0 needs changes`） | 15 个模块 |
| **Checkstyle** | **You have 0 Checkstyle violations.** | 16 项（parent + 15 模块） |
| **SpotBugs** | **BugInstance size is 0** × 15（修前 6 条） | 15 个模块（util/map/calendar/social-api/actor-api/economy-api/social/unit/core/sd/actor/economy/gov/army/app） |
| **Surefire** | **3361 条 / 0 失败 / 0 错误 / 5 跳过** | 15 个模块 |
| **前端门禁** | **`[frontend-gate] OK tests=412 pass=412 fail=0`** | simos-app |

逐模块 Surefire：app 905(5 skip)、unit 518、map 379、economy 246、sd 232、core 231、social 215、util 211、
actor 130、gov 109、army 91、economy-api 50、calendar 33、actor-api 8、social-api 3。
5 条跳过 = `RealLlmGovScenarioTest`(2) + `RealLlmUnitDecisionLoopTest`(3)，既有环境门控（与修前一致）。
`spotbugsXml.xml` / `surefire-reports` 的 mtime 落在本轮（02:41~02:44）。

★ 另一轮同样 exit=0 的 verify（修后、变异实验**之前**）跑在 02:36~02:39，真数与上表逐项相同；其原始日志
（8.9MB）已删，逐项真数在本文件与 `verify-run2-gates.txt` 里留有记录。

**改动的 6 个文件（md5，最终字节）**

```
a7eb7f14b9d60b0ac5d4e4b1c61bddc6  simos-economy/.../time/EconomySettlement.java
61e061179a5a568dd12a564d3ff00b3d  simos-economy/.../time/GovernmentDebtIssuance.java
c29505d8c41e507a7a1e7be7a0082711  simos-app/.../tools/write/GovToolSupport.java
baa78e2263c004148a661f4745237e59  simos-app/.../tools/write/GovSetFxRateTool.java
fa200d1eba1372823c95dc66ec512f80  simos-economy-api/.../money/MoneyVocabulary.java
74492d68dc1799485415d940ccb5ac67  simos-app/.../world/ThreePowersWorld.java
```

`git status --porcelain | grep -v src/test/` = **恰好这 6 个文件**（其余 27 个改动全是 `src/test/**`，
是别的责任区留下的既有改动，我**一个字节都没动**：三个模块 `src/test` 的 md5 清单在开工前后**逐文件一致，0 差异**）。

---

## 5. 会改变数值行为的清单（如实列全）

| 项 | 是否改变数值行为 | 说明 |
|---|---|---|
| ① ② DLS | **否** | 返回值改前就没人用（SpotBugs 判 DLS 即此），写入副作用与调用次数逐字未动。 |
| ④ ⑤ ⑥ 防御副本 | **否** | 三处的内部表本来已是不可变 `List.copyOf`/`List.of` 产物 ⇒ `List.copyOf` 走 JDK 快路径返回**同一实例**；保序、元素、引用同一性全不变。**不是"新副本改了可见性"**。 |
| ③ `:201` 真修 | **是（仅限工具视图的两栏，不涉状态）** | **改前**：`previousBuyPerMille` / `previousSellPerMille` 恒为 `null`。**改后**：政府已登记且该币对已有官方汇率时，两栏给出**真实现值**；政府未登记时仍为 `null`（未新增任何拒绝路径）。**提交的命令载荷**（`govUnitId/base/quote/buy/sell/reason`，见 `payload()`）与**落盘状态**逐值不变 —— 已核对 `GovToolSupport.submitOne(...)`：`view` 只用于组装返回值，命令体是 `payloadJson`。全仓 `src/test` 无任何断言涉及这两个字段（实测 `grep -rn "previousBuyPerMille\|previousSellPerMille" */src` 只命中工具自身 2 行）⇒ 3361 条全绿与之一致。 |
| ③ 顺带（窄、fail-closed） | **理论上是，实际不可达** | `GovernmentIds.ofUnit` 对 gov 单位 id 有格式校验（不得含 `.`、不得含 `\|`、不得带首尾空白、不得已带 `gov-unit-` 前缀）。若某个 GOV 单位 id 真含这些字符，本工具改前会"静默 `existing=null` 继续"，改后抛 `IllegalArgumentException` → `BAD_REQUEST`。实测仓内 GOV 单位 id 形态为 `gov-1` / `g-central` / `g-province` 等，不含这些字符；且**兄弟工具 `GovRenameCurrencyTool`/`GovIssueMoneyTool` 早已对同一 target 无条件调 `GovernmentIds.ofUnit`** ⇒ 口径一致，不引入新的可达行为差异。 |

**没有第二处需要报告的数值行为变化**；6 个文件的 diff 合计 63 insertions / 33 deletions（含注释），除上表外无逻辑改动。

---

## 6. 我没做 / 没验证的

- **没写/没改任何测试**（`src/test/**` 零改动，md5 清单自证）；按责任区纪律，新测试由后续测试 Agent 补。
- **没跑 `package` / 没碰在跑的 jar**（本机无服务在跑，但我按规定只跑 verify）。
- **没跑 `clean verify`**：控制方给的验收命令是 `verify -pl simos-app -am`，我原样执行；`clean` 会全量重编，
  与本条"唯一验收判据"无关，故未做。**未验证**：从 clean 树冷启动的这一轮是否同样绿（预期绿，但没跑）。
- **没在真实 world 上跑 `simos.gov.setFxRate`**：③ 的行为变化是"两栏现值不再恒 null"，我用静态核对
  （调用点/断言/`submitOne` 数据流）+ 全量测试绿来支撑，**没有**用 `run-small-world.sh` / 真档点一次这个工具
  去肉眼看那两栏。⇒ 若控制方要"真实行为证据"，这是待补的一项（成本：起一个 world + preview 调用一次）。
- **没做 ①②④⑤⑥ 的变异自证**：它们要么语义等价（DLS 去接收变量、副本走快路径），要么是 SpotBugs 直接判据；
  做了 ③ 的变异（那一条是唯一有争议、且唯一有行为差异的）。**如实记：其余 5 条是"修后门禁转绿 + 静态等价论证"，不是变异自证。**
- **没提交 git**（控制方审后提交）。
- **没验证 SpotBugs 的 `spotless-index` 缓存是否可能掩盖格式问题**：本轮 Spotless 15 个模块全部报
  "0 needs changes / 全部 skipped because caching"；但我在 02:35 已经用 `spotless:apply` 把 3 个模块**真跑过一遍**
  （它当时确实改了 `MoneyVocabulary` / `GovToolSupport` / `ThreePowersWorld` 三个文件并写回），此后源文件未再变
  ⇒ 缓存与磁盘一致。**缓存口径本身我没额外验证**。

---

## 7. 证据文件

| 文件 | 内容 |
|---|---|
| `verify-run2-gates.txt` | 最终轮（exit=0）四项门禁逐模块摘要（Spotless/Checkstyle/SpotBugs/Surefire/前端） |
| `verify-run2-final.log.gz` | 最终轮全量日志（8.9MB → 537KB） |
| `evidence/before-spotbugs-6-bugs.txt` | 修前 6 条 `BugInstance` 原文转录（含 ③ 的 `SourceLine=GovSetFxRateTool 201`） |
| `evidence/mutant-govSetFxRate-spotbugs.log` | 变异轮（只回退 `:201`、保留重命名）`spotbugs:check -pl simos-app` 全量日志：`BUILD FAILURE` + 1 条 `GC_UNRELATED_TYPES` |
| `evidence/mutant-govSetFxRate-excerpt.txt` | 上面那轮的失败摘要（含 `At GovSetFxRateTool.java:[line 207] GC_UNRELATED_TYPES`） |
| `evidence/mutant-compile.log` | 变异体的编译日志（exit=0；证明跑的是新字节码，不是旧 class） |
