# D3 长跑崩溃 —— 实现架构账本（2026-10-09）

责任区：**D3「长跑崩溃」**。任务书：三区世界（`--world=three-powers`）长跑必须 ≥360 天不崩、零 500、零 `IllegalStateException`；
既有测试全绿；不许把具名抛改成静默跳过；日志必须有（INFO = 发生了什么 / DEBUG = 为什么）。

**结论先行（三条缺陷，全部实测定位并修复；`git status` 只改 6 个生产文件，未碰任何 `src/test/**`、`pom.xml`、`docs/**`）**

| # | 实测崩点 | file:line（旧代码） | 修法 |
|---|---|---|---|
| 1 | 三区世界 day=270（`MIGRATION_PLAN` 之后 2ms） | `ModeMigrationSettlement.moveDebt` 的"目标恰是债权人"分支（`simos-economy/.../time/ModeMigrationSettlement.java:1255-1286` 旧行号）→ 在 `retireSource` 的残留检查（旧 `:614`）抛 | 自债**显式净额**（同 `DebtPartyResolver` 口径）+ INFO + 当天 ledger 具名读数 |
| 2 | 同一次 300 天推进的 day=330 | `PopulationEconomyTimeParticipant.java:557` 的**起点快照** `sessionAccounts` → `OwnershipBooks.apply` 旧 `:148` 抛负余额 | 过滤集改成**活会话**键集（`stepper.accounts().accounts().keySet()`），两处调用点同改 |
| 3 | 同一次推进的 day≈33x | `ModeMigrationSettlement.moveDebt` 只让"最后一笔"吃 floor 余数（旧 `:1231-1242`） | 新 `distributeDebt()`：逐笔 floor + 余数按 canonical 序补给仍有本金余额的合同（总额恒等于计划额） |

---

## 1. 复现装置（控制方那份 210 态 store 是决定性装置）

- **控制方现场**：`/home/cna/simos-runs/2026-10-09-scale-probe/`（服务 pid 2081 仍在跑，我**只读**它）。
  - `service.log` 169MB：两次 `POST /api/advance` 均 `status=500 error=IllegalStateException`，且都紧跟在
    `event=MIGRATION_PLAN origin=economy-migration day=270 moves=43 organizations=163 modeHexProfits=117` 之后
    （`18:14:32.986` → `18:14:32.988`，2ms）。此前两次推进：`210→510 days=300`、`210→270 days=60`。
  - 状态停在 `tick 210 / revision 4`（checkpointInterval=100，未提交的推进不落盘）——**控制方报告属实**。
- **我的复现装置**（不动别人服务、不 `package`）：
  1. `cp -r` 控制方 store 到 `/tmp/d3-repro/store*`（读到 `tick=210 rev=4`，逐字一致）；
  2. `tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` 出 15 个模块的 `target/classes`；
  3. `java -cp "<全部 simos-*/target/classes>:simos-app/target/...-shaded.jar" io.mosire.simos.app.ShellMain --store … --world=three-powers --gui-port 63xx`
     （**fresh classes 在 classpath 前段，遮住 jar 里的旧字节**；故不改 jar、不停别人的服务）；
  4. `POST /api/advance {"from":210,"to":270|510,"expectedRevision":4|5}`。
- **原始输出（复现成功，逐字）**：
  ```
  18:14:32.986 INFO  ... event=MIGRATION_PLAN origin=economy-migration originKind=tick day=270 moves=43 organizations=163 modeHexProfits=117
  18:14:32.988 WARN  io.mosire.simos.app.gui - event=GUI_REQUEST_FAILED ... error=IllegalStateException
  ```
  ⇒ 与日志里能看到的现象**逐字同形**。

## 2. ★★ 控制方的根因定位是**错的**（实测推翻，不是推理）

控制方指出 `simos-economy/.../time/MarketSettlement.java:5225` 的具名抛。**它没有触发**：
- 我在该抛点加了 ERROR 探针（`MARKET_SUBJECT_UNRESOLVED_UNIT`）后重跑同一装置 → **探针一次都没打**；
- 日志把正文截掉的真因是 `GuiServer` 的 500 分支只记 `e.getClass().getSimpleName()`（**正文与栈全丢**）。
  我补上 `message=`（WARN，恒开）与栈（DEBUG）后，第一次重跑就拿到了真身：

```
java.lang.IllegalStateException: 迁移后源户人口为 0 但仍有货币/债务残留（拒绝消亡丢账）:
    source=hh--3_3-urban-middle_peasant money=0 debt=323
  at io.mosire.simos.economy.time.ModeMigrationSettlement.retireSource(ModeMigrationSettlement.java:615)
  at io.mosire.simos.economy.time.ModeMigrationSettlement.applySource(ModeMigrationSettlement.java:557)
  at io.mosire.simos.economy.time.ModeMigrationSettlement.apply(ModeMigrationSettlement.java:169)
  at io.mosire.simos.economy.time.EconomySettlement.settleOneDayInto(EconomySettlement.java:2756)
  at io.mosire.simos.economy.time.EconomyDayStepper.step(EconomyDayStepper.java:347)
  at io.mosire.simos.app.time.PopulationEconomyTimeParticipant.simulateWorld(...:645)
  at io.mosire.simos.core.advance.TimeAdvance.run(TimeAdvance.java:246)
```
`MarketSettlement.java:5225` 那条抛仍然**保留原样**（我只加了 ERROR 日志与更丰富的正文）——**没有**把任何具名抛改成静默跳过。

### 2.1 具体身份（任务书要的"哪个 unit、哪个 operator"）

本缺陷**不是 unit/operator 解析问题**（那条路径一次未触发）。实测身份是**家户与债务合同**：

| 项 | 值 |
|---|---|
| 崩点日 | day=270（`from=210,to=270` 与 `from=210,to=510` 两次都停在 270） |
| 迁移源户（debtor） | `hh--3_3-urban-middle_peasant` |
| 迁入目标（= 该笔债的 **creditor**） | `hh--3_3-rural-poor_peasant` |
| 合同 | `debtc-68682d2d…（id 内含 debtor/creditor/unit/terms）`，`commodity:grain`，本金 **323** |
| 计划分摊 | `planDebtMilli=6696`、`principalBefore=6696`、`contracts=5`、`selfCreditorTake=323`、`sourceResidualAfter=323` |

⇒ 计划把 6696 全额分摊（其余 4 笔都搬到目标），**唯独债权人恰是迁入目标的那 323 被 upsert 回源户**；
源户计划人口归零 → `retireSource` 的"拒绝消亡丢账"守卫读到 `debt=323` → 具名抛 → 整次 advance 500、状态回退到 tick 210。

## 3. 链路定位（file:line，旧代码）

1. `ModeMigrationSettlement.applySource`（`:496`）：逐 move 调 `moveDebt`。
2. `ModeMigrationSettlement.moveDebt`（`:1187-1242`）唯一"把债留在源户"的分支：
   ```java
   } else {
     // 目标恰是债权人：这部分留在源户（人口清零时会因残留债具名抛，绝不静默消灭债权）。
     DebtContractBook.upsert(debts, move.source(), contract.creditor(), …);
   }
   ```
   ★ 注释自己写明了"人口清零时会因残留债具名抛" —— 即这是**已知的悬空状态**，只是没人把两条前提同时满足的场景跑出来。
3. `ModeMigrationSettlement.retireSource`（`:608-622`）：`residualDebt>0 ⇒ 抛`。判据本身**正确且必须保留**（坏数据不能静默）。
4. 触发前提：`ModeMigrationPolicy.splitDebt`（`:1278-1314`）在 `emptiesSource` 时把余数随最后一笔全额搬走 ⇒
   计划额恒等于源户剩余本金 ⇒ 残留只可能来自第 2 步。

## 4. 修法选择与理由（三级处置的取舍）

**首选路线＝"让状态不再坏"，而不是放宽判据**：

- **缺陷 1（自债）**：`target == creditor` ⇒ 本笔份额**显式净额、不落任何合同**。
  - 理由：台账里已有唯一口径——`DebtPartyResolver` 类注明文「自债（debtor == creditor）显式净额、不落合同 ——
    同户对自己的债权无经济意义，且还款会铸出'两端相等'的非法转移」。迁移把债务人的**人**并入债权人户，
    这笔份额就成了户内自债 ⇒ 债权与负债在同一个家户里互相抵消，**净值不变**（不是"消灭别人的债权"）。
  - **不静默**：`MIGRATION_DEBT_SELF_NETTED` INFO（day/source/target/contract/principalBefore/netted/reason）
    + 当天 `ProductionLedger.LiquidationAudit(action=migration-netted-self-debt, quantity/debtReductionMilli=netted)`。
- **缺陷 3（余数）**：新增 `distributeDebt()`：逐笔按剩余本金比例 floor，**余数按 canonical 序补给仍有本金余额的合同**；
  分摊总额恒等于计划额（`moved == move.debtMilli()` 例行守卫）。原判据（"没有全部分摊就抛"）**保留**，只是不再会被正常输入触发。
- **缺陷 2（起点快照）**：`sessionAccounts` 曾是 advance 起点的快照，而迁移会在**中途** `AccountSession.registerHousehold`
  （`ModeMigrationSettlement.createNewHousehold`，`:840`）新建家户 ⇒ 快照漏掉它 ⇒ 它当天的条目被折进 actor 基准
  （基准不是当天权威，终值随后又被 `landAccountSession` 的绝对值覆盖）⇒ 前缀校验假报负余额。
  修法：过滤集改取**活会话键集**（`stepper.accounts().accounts().keySet()`），与 `landAccountSession` 的落回集合逐字同源。
  两处调用点同改（`PopulationEconomyTimeParticipant`、测试夹具在用的 `EconomyOwnershipTimeParticipant`）。
- **没有**走"合法空壳"分支：`MARKET_SUBJECT_EMPTY_UNIT` 那条路径在本现场一次未触发，与本次崩点无关，未改。

## 5. "是否本会话引入" —— **实测结论：不是**

- **装置**：`git worktree add /tmp/d3-before 806e791c`（本会话最后一笔源码提交之前的 HEAD）→ 在 worktree 内
  `tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` → 用 worktree 的 `target/classes` 跑**同一份 store、同一条命令**
  （`210→270` 单次）。
- **结果**：`{"error":"internal error"}`，日志尾三行与控制方**逐字同形**：
  `LIQUIDATION day=270 audits=163` → `MIGRATION_PLAN day=270 moves=43` → `GUI_REQUEST_FAILED error=IllegalStateException`，
  状态停在 tick 210。
- **旁证（字节级）**：控制方在跑的那份 jar（`/tmp/simos-shaded-NwvHbq.jar`）与 worktree 806e791c 编译产物**逐字节相同**：
  `ModeMigrationSettlement.class f06383ed7ed2f80b748f9f0a9930a933`、`MarketSettlement.class 0c3e59bb…`、
  `OwnershipBooks.class bc3e1854…`、`GuiServer.class 7640173f…`（jar 内 vs worktree，四者全 SAME）。
  ⇒ 控制方复现用的二进制就是 806e791c 的编译产物；崩点在本会话之前就存在（**遗留缺陷，非本会话引入**）。
- ★ 另一个反证：`ModeMigrationSettlement` 的旧注释把这条路径写成"人口清零时会因残留债具名抛"——
  作者当时已知它会抛，只是没跑出同时满足两个前提的输入。

## 6. 硬指标证据

### 6.1 三区世界 0→360，30 天一段（**任务书要求的那条**）

| 运行 | 装置 | 结果 |
|---|---|---|
| **A（终稿复验）** | 空 store `fresh3`，`--world=three-powers`，0→30→…→360，每段一次 `/api/advance` | **12 段全 `committed`**（rev 1→13）；`status=500`=**0**、` ERROR `=**0** |
| A′（追加） | 同一 store 再 `360→1010` 单次 | `committed`，tick=1010；`500`=0、`ERROR`=0 |
| B（上一版：与终稿只差"日志留样上界"一处） | 空 store `fresh2`，0→360 + `360→1010` 单次 | 全 committed；`500`=0、`ERROR`=0 |

★ **诚实边界**：A/A′/B 这三条 30 天分段轨迹**一次都没触发缺陷 1 的净额分支**（`MIGRATION_DEBT_SELF_NETTED` 计数=0）。
即：**要求的那条验收跑是真绿，但它本身不是缺陷 1 的判别器**。判别器是下面 C/D 两条。

### 6.2 控制方那份现场 + 控制方那条命令（判别性最强）

| 运行 | 装置 | 修前 | 修后（终稿） |
|---|---|---|---|
| **C** | 控制方 store（tick 210）+ **单次 `210→510`（300 天，控制方原命令）** | day=270 `error=IllegalStateException`，状态停 210 | **`committed`（rev 5）**，`500`=0、`ERROR`=0 |
| C′ | C 的续跑：单次 `510→1010` | —— | `committed`（tick 1010）；净额事件 20 次 / 800 天 |
| D | 控制方 store + 30 天分段 `210→720`（初版代码） | 修前 30 天分段在 270 天**不崩**（见 §8 的轨迹分歧） | 全 committed，`500`=0、`ERROR`=0 |

### 6.3 既有测试（本批允许跑，D3 验收要求）

命令：`rm -rf */target/surefire-reports && tools/mvn-lock.sh -o test -pl simos-app -am`
（跑前 `pgrep -af "surefirebooter|classworlds.launcher"` = 无）

- **[test exit=0]**，`BUILD SUCCESS`，耗时 2:53；前端门禁 **412/412 pass**。
- 逐模块 surefire 真数（报告 mtime 全在 `18:47:05`，即本轮）：

```
simos-util 211 / map 379 / calendar 33 / social-api 3 / actor-api 8 / economy-api 50 / social 215 /
unit 518 / core 231 / sd 232 / actor 130 / economy 246 / gov 109 / army 91 / app 905
TOTAL 15 模块 / 393 测试类 / 3361 条 / 0 失败 / 0 错误 / 5 跳过（5 条 = 既有 RealLlm* 环境门控）
```

- 追加自检（不属 `test` 阶段，但会进控制方的 `clean verify`）：
  `spotless:check -pl simos-economy,simos-app` exit=0；`spotbugs:check -pl simos-economy,simos-app`
  → `BugInstance size is 0` ×2、`BUILD SUCCESS`。
- **未跑**：全仓 `clean verify`（Spotless 全仓 + 全 15 模块 SpotBugs + 其余门禁）——按任务书只跑到 `test -pl simos-app -am`。

## 7. 会改变数值行为的清单（给测试代理当输入）

1. **`MIGRATION_DEBT_SELF_NETTED`（缺陷 1，唯一有经济含义的改动）**
   - 迁移目标恰是某笔债的债权人时，该笔随迁份额**不再落回源户**（旧：落回源户 ⇒ 残留 ⇒ 抛；新：净额）。
   - 影响面：债务合同表（本金/条数）、后续利息与偿还、`debtContracts` 计数、债务压力/退出判定。
   - 数值规模实测（终稿代码，控制方轨迹 210→1010 = 800 天）：共 **20 次**，金额分别为
     1~7（`hh--3_3-rural-rich_peasant → hh--3_3-urban-poor_peasant`，day 420~780）、12/24/26/35（`hh-mig-0_-3-wage_farm-… → hh-0_-3-…`）、
     **323（day=270，即本次崩点）**、**1198（day=570，`hh-mig-0_-3-wage_farm-hh-0_-3-urban-middle_peasant-0 → hh-0_-3-urban-landlord`）**。
     ⇒ 该分支不是一次性边角：长跑里反复出现；若只修 day=270 那一笔，day=570 的 1198 会以同样方式崩。
   - 守恒口径：净额份额的两端（该户债权、该户负债）在同一家户内对冲，**家户净值不变**；金额进当天 ledger 具名读数。
2. **缺陷 3（余数分配）**：`Σ take` 仍恒等于 `planDebtMilli`；变化只是 floor 余数（< 合同数）**按 canonical 序补给仍有本金余额的合同**
   （旧：只由最后一笔吃 ⇒ 吃不下就抛）。旧行为在正常输入下会崩，故无可比旧数值；单笔分账差异 ≤ 合同数−1（实测 5，milli 单位）。
3. **缺陷 2（过滤集现读）**：仅影响"会话中期新建家户"的**落账路径**——它的终值本来就由 `landAccountSession` 绝对值覆盖，
   故**最终状态不变**，只是不再先折一遍（旧行为：折完立刻被覆盖，且折的中间态会假报负余额）。**净数值行为不变**。
4. 日志/正文：`GuiServer` 加 `message=`+DEBUG 栈；`MarketSettlement` 加 `MARKET_SUBJECT_UNRESOLVED_UNIT` ERROR；
   `ModeMigrationSettlement` 加 `MIGRATION_SOURCE_RESIDUAL_REFUSED`(ERROR) / `MIGRATION_DEBT_SELF_NETTED`(INFO) /
   `MIGRATION_DEBT_MOVED`(DEBUG)；`OwnershipBooks` 加 `OWNERSHIP_NEGATIVE_BALANCE_REFUSED`(ERROR)。**不改公式、不改状态形状**。
5. 受影响的硬编码字面量：无（新增的 `8`/`16` 只是日志留样上界 `residualContracts` / `CAUSES_KEPT`，不参与公式）。

## 8. 未完成 / 未验证 / 观察到的其它现象（如实记）

1. **未根因化：调用边界会改变同日状态（真实存在，未修）**。同一份 store、同一天，不同分段方式给出不同读数：
   `day=270` 的 `LIQUIDATION audits` = **163（单次 60/300 天）vs 48~50（30 天分段）**，
   `MIGRATION_PLAN moves` = **43 vs 40**，而同日 `DAY_START`（rows/units/organizations/debtContracts）与 `MARKET` 汇总（fills=181、unfilled=484、reasons 逐字）**完全相同**。
   ⇒ 本次三处崩点里有两处（day=330 负余额、day≈33x 债务余数）**只在单次长推进里出现**；控制方的 `210→510` 属于这一类。
   我**没有**定位"什么状态按 advance 调用为生命周期"（候选：`sessionAccounts` 快照、起点构建的一次性索引/预算表）。
   这正是缺陷 2 的同族病灶（起点快照）——但**除它之外是否还有别处**，我没有证据，不下结论。
2. **未验证**：全仓 `clean verify`（我只跑了 `test -pl simos-app -am` + 两个模块的 spotless/spotbugs 直调）；
   缺陷 1 的净额分支在**空世界 30 天分段**轨迹下一次不触发（覆盖来自控制方 store 轨迹与长单次推进）。
3. **未做**：为三处修复补测试（任务书明确：D3 只写生产代码、测试留最后统一做；`src/test/**` 我一字未碰）。
4. **未动**：`MarketSettlement:5225` 那条具名抛（控制方点名的行）**原样保留**——它在本现场未触发，且它的语义（坏数据具名抛）正确。
   我只在该抛点前置了一条 ERROR（`MARKET_SUBJECT_UNRESOLVED_UNIT`，带 day/unit/operator/hex/assets/份额 owner/relation/labor），
   下一次它真触发时日志能直接给出身份。
5. **未动**：`MARKET_SUBJECT_EMPTY_UNIT` 那条"合法空壳"分支（本次与该路径无关）。

## 9. 复现命令（照抄即得）

```bash
# 0) 前提：不 package、不动别人服务；只用 lock
cd /home/cna/SimulatorMosire
tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am
CP="simos-app/target/classes:$(ls -d simos-*/target/classes | tr '\n' ':')simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar"

# 1) 修前（806e791c，字节等价于控制方 jar）：210→270 单次 ⇒ 500，状态停 tick 210
git worktree add /tmp/d3-before 806e791c
(cd /tmp/d3-before && tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am)
java -cp "$(ls -d /tmp/d3-before/simos-*/target/classes | tr '\n' ':')simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar" \
  io.mosire.simos.app.ShellMain --store /tmp/d3-repro/storeBefore --world=three-powers --gui-port 6301 --mcp-port 6305 --approval-port 6303
curl -s -XPOST localhost:6301/api/advance -d '{"from":210,"to":270,"branch":"main","expectedRevision":4}'

# 2) 修后：控制方原命令 210→510 单次 ⇒ committed
java -cp "$CP" io.mosire.simos.app.ShellMain --store /tmp/d3-repro/storeG --world=three-powers --gui-port 6311 --mcp-port 6315 --approval-port 6313
curl -s -XPOST localhost:6311/api/advance -d '{"from":210,"to":510,"branch":"main","expectedRevision":4}'

# 3) 修后：空世界 0→360（30 天一段）
java -cp "$CP" io.mosire.simos.app.ShellMain --store /tmp/d3-repro/fresh3 --world=three-powers --gui-port 6321 --mcp-port 6325 --approval-port 6323
for t in 30 60 90 120 150 180 210 240 270 300 330 360; do R=$(curl -s localhost:6321/api/state | python3 -c 'import sys,json;print(json.load(sys.stdin)["meta"]["revision"])'); curl -s -XPOST localhost:6321/api/advance -d "{\"from\":$((t-30)),\"to\":$t,\"branch\":\"main\",\"expectedRevision\":$R}"; echo; done
```

## 10. 留痕装置的 md5（本轮结论对应哪份字节）

```
0f46ddf2051ffe216ab0a219e2c327ac  simos-economy/.../time/ModeMigrationSettlement.java
8f4281d051b03d96dc5dd96b2dd13b89  simos-app/.../time/OwnershipBooks.java
```
（改动的 6 个文件：`MarketSettlement.java`、`ModeMigrationSettlement.java`、`GuiServer.java`、`OwnershipBooks.java`、
`PopulationEconomyTimeParticipant.java`、`EconomyOwnershipTimeParticipant.java`；终稿 `git diff --stat` = **6 files, +362/−41**，未碰 `src/test/**`、`pom.xml`、`docs/**`。）
