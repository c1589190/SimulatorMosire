# RC7 任务书：S7-C 向下流动表（同 hex landed→landless）

> **状态：约束设计书/实现任务书；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：v4 R5、§3 S7-C、§7、§11；v2 S7。
> 依赖：RC4-A/B/C/D、RC6、RC3 已 compile 绿。
> 本单新增一个持久组件（FlowTable），是本主题最后一个生产状态形状变化。

## 1. 目标

在 S7-B 之后、市场轮末 class-slide 之前，执行：

```text
每个 LANDED 户维护一张 flow table
目标 = 本 hex、同 cultureKey/同 mode、classNature=LANDLESS 的户
债务本金比上一市场轮扩大 ⇒ 按比例把人口 + 钱/货/债/经验流向目标
LAND 不随人走
```

- 表必须持久化进 `EconomyData` / ChangeSet / Codec；
- source/target 必须同 hex；
- 目标不存在时可创建 0 人口 LANDLESS 壳（由 `targetIsNew=true` outbox 在 App 侧创建 Social 户）；
- 不改 LAND、不删 source 0 人口壳、不把 LANDED source 改 class；
- 不做 RC6/§8 之外的额外玩法。

## 2. 当前事实（file:line）

- `EconomyData` 当前 36 组件，最后为 `landHoldings`；没有 flow table。
- `EconomyChangeSet` 当前 36 组件；每个持久组件都要同步。
- `EconomyStateBuilder` 有工作副本/惰性物化机制；需要新增 flowTables。
- `ClassNatureBook` 已可派生 LANDED/LANDLESS。
- `HouseholdMergeBook` 已有同 key 分组/survivor；RC4-D 在 S3 前合并，S7 时同 key 应只剩一个活户。
- `HouseholdTransferSettlement` 已可搬人口+钱/货/债/经验/源劳动缩减，且支持 `targetIsNew=true` outbox。
- `PostLandTradeClassCheck` 已在 S7-A 写回后、ClassSlide 之前调用；RC7 紧随其后。
- `MigrationPopulationMove.createNewHousehold(...)` 是既有建目标户形状参考；本单可写专用 `HouseholdFlowBook.createLandlessShell`，不强行复用 tier 逻辑。

## 3. 数据形状

新增 `simos-economy/model`：

```text
enum HouseholdFlowReason { DEBT_DOWNWARD }

record HouseholdFlowTarget(
    HouseholdId targetHousehold,
    HouseholdFlowReason reason)

record HouseholdFlowTable(
    HouseholdId sourceHousehold,
    HexCoord sourceHex,
    List<HouseholdFlowTarget> targets,
    long lastObservedDebtPrincipalMilli,
    long lastFlowDay)
```

- `sourceHousehold` 非 null；sourceHex 非 null；
- targets 非 null、非空（维护时可暂空？若空则表仍可存，但 target 未找到；首版允许 empty table? 建议允许空，表示尚未找到目标；流触发时创建/ fail）；
- `lastObservedDebtPrincipalMilli ≥ 0`；`lastFlowDay ≥ -1`（-1 = 从未流动）；
- key = sourceHousehold；values 冻结、保序。
- `EconomyData` 末尾追加：
  `Map<HouseholdId, HouseholdFlowTable> householdFlowTables`
  - `EconomyChangeSet` 同步追加 `FieldDelta<HouseholdFlowTable> householdFlowTables`；
  - `EconomyStateBuilder` 增加 lazy work copy / `build()` 带出；
  - `EconomyClearRegionHandler` 清除被删户的表；
  - `EconomyDataInvariants` 校验 key/值一致、source/target 存在、target 同 hex，且 target classNature=LANDLESS（若可派生）、source classNature=LANDED；
  - `EconomyBoundaryView` 如需读口可加只读访问器。

## 4. HouseholdFlowBook

新增 `simos-economy/time/HouseholdFlowBook.java`：

### 4.1 maintain + settle

入口：

```text
static FlowRound settle(SettlementDayContext ctx, long day)
```

步骤：

1. 遍历 `ctx.householdEconomies` 中 `classNature=LANDED`、population>0 的户，按 HouseholdId 稳定序；
2. `sourceHex = row.view().hex()`；
3. 维护 table：
   - table 缺失 ⇒ `new HouseholdFlowTable(source, sourceHex, List.of(), 0, -1)`；
   - 若 sourceHex 变了 ⇒ 重写 sourceHex，target 重找；
   - target 选择：
     - 同 hex、population>0、classNature=LANDLESS、current position mode == source current mode；
     - 同 cultureKey 优先；多个同 key 按 R1 survivor（人口→LAND→id）；
     - 若无同 culture 但有 candidate ⇒ 最小 HouseholdId + INFO mismatch；
     - 找不到正人口 target ⇒ table.targets 空；
   - 更新 `lastObservedDebtPrincipalMilli`；每个市场轮只更新一次。
4. 触发（在 target 选择之后）：
   - `currentDebt = 该 source 户未偿本金合计`；
   - 若 `currentDebt > table.lastObservedDebtPrincipalMilli`
     且 `day > table.lastFlowDay`（每市场轮最多一次）：
     - 若 table.targets 空 ⇒ 创建一个 0 人口 LANDLESS 壳：
       - stable id `HouseholdsIds.ofSeedRole(hex, residence, SocialClassId.LANDLESS_LABORER, "flow-<n>")`；
       - `HouseholdEconomy` 0 人口、0 劳动、source participation? 用 0 或农村 landless 参与率（不复制 LANDED？建议 950‰? 但新户无人口；首版 0/0 合法）；
       - `HouseholdClassMembership` current=landless position（`grain_farm.hired_laborer` LANDLESS 的 mode/position）；
       - `accounts.registerHousehold(target, hex, empty maps)`；
       - 写入 `householdEconomies` + `classMemberships`；`targetIsNew=true`；
       - 表 target 写入该 id。
     - `flowPopulation = min(max(1, floor(sourcePop × 100 / 1000)), sourcePop - 1)`；
     - 若 flowPopulation <=0 ⇒ 只更新 lastObserved/lastFlowDay? 不改 class；
     - 调 `HouseholdTransferSettlement.apply(...)`：
       - reason `HOUSEHOLD_FLOW_DEBT`
       - targetIsNew 按是否新建壳
       - targetMode = target current mode（landless）
       - sourcePopulationBefore = source row population
       - targetPopulationBefore = target row population
       - 不搬 LAND；不创建目标生产承诺；RC3 缩源生产承诺
     - 更新 table.lastFlowDay=day, lastObserved=currentDebt, target list；
5. 不合并 table targets 中的重复；RC4-D 应在 S3 前保证同 key 单活户；若表 target 被合并/不存在 ⇒ 重新找/建。
6. 返回 FlowRound（tablesUpdated, flows, population, createdAtTargets）。

### 4.2 目标创建边界

- 只在**流触发且无 target**时创建壳；纯维护不创建；
- 用 source 的 `ResidenceKind`；stratum = `SocialClassId.LANDLESS_LABORER`；
- 位置 = mode/classNature LANDLESS 的当前 class position（从 `base.classPositions` 找 mode + classNature=LANDLESS，稳定 id 最小）；
- 不得创建 LANDED target、不得跨 hex、不得复制 LANDED LAND；
- `targetIsNew=true` 让 App `MigrationSocialBridge` 建 Social 户；
- 如果 `targetIsNew=false` 且 target row 不存在 ⇒ fail-closed。

## 5. 钩子

在 `SettlementDayMarketPipeline.executeMarketRound`：

```text
market LAND writeback
→ PostLandTradeClassCheck.settle(...)      // RC6 S7-B
→ HouseholdFlowBook.settle(ctx, day)       // RC7 S7-C
→ ClassSlideSettlement.settleAtMarketRoundEnd(...)  // 现有旧路径保留
```

- 只在市场日 `executeMarketRound` 内调用一次；
- 非市场日不更新 table、不流动（table 保留上次观察）；
- 若 RC7 失败 ⇒ 整次 advance 失败，不落 revision。

## 6. 必须达到的行为

1. `EconomyData/ChangeSet/Codec/StateBuilder` 都包含 37th 组件，往返形状完整；
2. 每市场轮每个 LANDED 户更新 lastObservedDebtPrincipal；不因无 debt 删除表；
3. debt > lastObserved 且 target 存在/可创建 ⇒ 人口按 100‰ 速度上限、保留 source ≥1 人；钱/货/债/经验随人走；
4. LAND 不变；source LANDED 壳保留；target LANDLESS；
5. target 不存在时创建 0 人口 LANDLESS 壳并同批 outbox newTarget；
6. 同 hex/culture/mode 优先；不同 culture 才稳定选最小 id + mismatch；
7. 每市场轮最多一次 flow；lastFlowDay/上次债务正确；
8. 不把 §8 整组织交易、不把 LAND 市场/债务公式带进来。

## 7. 允许修改的文件

```text
simos-economy/src/main/java/io/mosire/simos/economy/model/HouseholdFlowTable.java（新增）
simos-economy/src/main/java/io/mosire/simos/economy/model/HouseholdFlowTarget.java（新增）
simos-economy/src/main/java/io/mosire/simos/economy/model/HouseholdFlowReason.java（新增）
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdFlowBook.java（新增）
simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java
simos-economy/src/main/java/io/mosire/simos/economy/change/EconomyChangeSet.java
simos-economy/src/main/java/io/mosire/simos/economy/codec/EconomyCoreCodecs.java（仅若需）
simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyStateBuilder.java
simos-economy/src/main/java/io/mosire/simos/economy/EconomyDataInvariants.java
simos-economy/src/main/java/io/mosire/simos/economy/EconomyBoundaryView.java（只读口，若需要）
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyClearRegionHandler.java
simos-economy/src/main/java/io/mosire/simos/economy/EconomyDataTransforms.java（仅构造参数适配）
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayMarketPipeline.java（S7-C 钩子）
```

必要时只做编译适配；**禁止**改 LAND 市场/债务/工资/需求公式、seeder、tests、主树；不得把 §8 做进来。

## 8. 失败/原子性

- 表 key/source/target/hex/class 不一致 ⇒ 构造/候选态 fail-closed；
- 创建 target 壳 id 冲突 ⇒ fail-closed；
- target 同 hex 不满足 ⇒ 不写入；
- 本 tick flow 之后 source 人口不得为 0（MIN=1）；target 不得被删；
- 同一次 advance CHANGESET 必须包含新组件 delta；不得漏 Codec/StateBuilder；
- 失败整次 advance 失败，不落 revision。

## 9. 验收锚点（测试 owner 后补）

正例：

1. 表初建：landed 户 / 同 hex landless target / lastObserved=当前债务；
2. 下一市场轮债务增加：人口按 100‰（最多 P-1）迁到 target，钱/货/债/经验按 RC3 规则；
3. LAND 不动、source 保持 LANDED、target LANDLESS；
4. target 不存在时创建 0 人口壳，outbox newTarget，App 建 Social 户；
5. 每轮最多一次；lastFlowDay 正确；
6. 37 组件 roundtrip 完整。

负例：

- target 跨 hex/landed ⇒ 红；
- LAND 随人口走 ⇒ 红；
- source 被搬空/改 class ⇒ 红；
- 同 tick 多次 flow ⇒ 红；
- flow table 未进 ChangeSet/Codec ⇒ 红；
- 0 人口 target 被当作正人口目标/源户空壳被再次 flow ⇒ 红。

## 10. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-rc7-downward-flow-table/impl-ledger.md`。
- 报告列：改动文件、编译 rc、37 组件接入点、flow 触发/目标创建、LAND 不动证明、可能失效测试断言、未验证项。

## 11. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R5：是的，随着人走
```
来源：`...-v3.md` §1，2026-10-20。

```text
家户向下流动的规则我不是已经说了吗？单个家户维护一个流动表，除去后面可能会做的文化流动因素，目前的作用就是指定在债务扩大的时候，本hex的有地家户只会流向本hex的无地家户，这张表的维护包括找到本hex的对应无地家户这一职责
```
