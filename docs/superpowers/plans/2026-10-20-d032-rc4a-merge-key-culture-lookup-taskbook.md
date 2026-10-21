# RC4-A 任务书：同 key 合并计划与 culture-key 接线

> **状态：约束设计书/实现任务书；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：v4 R1、§3 S2、§4 classness、§9 culture。
> 依赖：RC1/R C2a/RC2b/RC3/RC5 已 compile 绿。
> 本单只做**只读 merge 计划**与 culture lookup 接线；不做合户写口、不做 organizer/asset rebind、不做 S2 实际合并。

## 1. 目标

1. 把 RC1 的 `HouseholdCultureKeyLookup` 真正接进 economy 会话/日结算，使 merge/后续 S7-B 能读到 cultureKey，而 economy 仍不 import culture。
2. 提供一个纯函数式 `HouseholdMergeBook`：

```text
mergeKey = (hex, cultureKey, productionMode, classNature)
survivor = 人口最多 → 该 hex LAND 最多 → HouseholdId 最小
plan = 同 key 且成员数 > 1 的稳定分组
```

3. 本单不改任何状态；只返回计划（供 RC4-B/C/D 执行）。

## 2. 当前事实（file:line）

- `HouseholdCultureKeyLookup`/`HouseholdCultureKey` 已在 economy-api（RC1），`CultureData` 已实现。
- `EconomySession` 当前 3/5/6 参构造器没有 culture lookup 字段（`EconomySession.java:161-210`）。
- `EconomyDayStepper` 当前 full 构造器最后是 `RevisionId expectedRevision`，没有 culture lookup（`EconomyDayStepper.java:212-243`）。
- App 构造 stepper 在 `PopulationEconomyTimeParticipant.java:616-628`，当前没有把 culture 切片传入。
- `ProductionRole.classNature`、`ClassNatureBook` 已存在（RC1/RC2a）。
- `HouseholdEconomy.view().hex()`、`HouseholdClassMembership.currentPositionId()`、`EconomyDayView.classPositions()` 可派生 mode。
- `LandHoldingKey(hex, owner)` / `EconomyDayView.landHoldings` 可算每户在本 hex 的 LAND。

## 3. 冻结形状

### 3.1 culture lookup 接线

`EconomySession`：

- 新增 `HouseholdCultureKeyLookup cultureLookup` 字段；全参构造器增加参数；
- 旧 3/5/6 参构造器委托到 `HouseholdCultureKeyLookup.unavailable()`（定义在 economy-api 接口的静态方法或 economy 适配器；unavailable 返回 `Optional.empty()`，不得伪造 culture）；
- 暴露 `Optional<HouseholdCultureKey> cultureKeyOf(HouseholdId household)`，只转发 lookup。

`EconomyDayStepper`：

- full 构造器增加 `HouseholdCultureKeyLookup` 参数；旧构造器委托 unavailable；
- `EconomySession` 用传入 lookup 构造。

App `PopulationEconomyTimeParticipant`：

- 从当前 `SimulationState` 的 `culture` 切片取 `CultureData`（`CultureSnapshot.state()`）；缺失切片时用 `HouseholdCultureKeyLookup.unavailable()`；
- 以只读适配器传入 stepper；不得修改 culture 状态。

### 3.2 merge-key 计划

新增 `simos-economy/time/HouseholdMergeBook.java`：

```text
record MergeKey(
    HexCoord hex,
    HouseholdCultureKey cultureKey,
    ProductionModeId productionMode,
    ClassNature classNature)

record Member(
    HouseholdId household,
    long population,
    long landMilliMuAtHex)

record MergePlan(
    MergeKey key,
    List<Member> members,
    HouseholdId survivor)
```

`plan(EconomyDayView base, HouseholdCultureKeyLookup cultureLookup)` 规则：

- 只考虑 `base.classes()` 中 `population > 0` 的户；
- `hex = household.view().hex()`；
- `productionMode = base.classPositions().get(standing.currentPositionId()).modeId()`；无 standing / 无 position ⇒ skip；
- `classNature = ClassNatureBook.classNatureOf(base, household)`；empty ⇒ skip（本机制只合并有 class nature 的农村户）；
- `cultureKey = cultureLookup.cultureKeyOf(household).orElse(HouseholdCultureKey.missing())`；
  - `HouseholdCultureKey.missing()` = 显式空 key：`("", "", List.of(), "")`；缺失时 INFO `CULTURE_KEY_MISSING`，不得用 SocialClassId/生产方式猜文化；
  - T1/seed 应给真实默认 culture label，逐步消除 missing。
- `landMilliMuAtHex = base.landHoldings().get(new LandHoldingKey(hex, household)).quantityMilliMu()`（无持有 = 0）；
- survivor：`population` 降序 → `landMilliMuAtHex` 降序 → `HouseholdId.value()` 升序；
- 输出按 `MergeKey` 稳定排序（hex 自然序 → cultureKey 字符串 → mode → classNature），只返回 `members.size() > 1` 的组；
- 纯只读，不写任何状态；不 construct/session mutation。

### 3.3 `HouseholdCultureKey.missing()`

在 economy-api 的 `HouseholdCultureKey` 增加静态工厂：

```text
public static HouseholdCultureKey missing() {
  return new HouseholdCultureKey("", "", List.of(), "");
}
```

- 这是“没有 culture 切片/缺标签”的显式占位，不是新文化；
- 所有字段仍非 null；customs 空、label 空；
- 不要求 `cultureId` 非空白（当前构造器只判 null）。

## 4. 必须达到的行为

1. economy 仍 0 个 `io.mosire.simos.culture` import；
2. App 正常装配真实 culture lookup；culture 切片缺失时 unavailable 但 merge plan 仍可产出 missing-key 计划；
3. 同 hex/同 cultureKey/同 mode/同 classNature、人口>0 的多个户按 survivor 规则分组；
4. 不同 hex、不同 mode、不同 classNature、不同 cultureKey 不混组；
5. 0 人口壳不参与 merge 候选/survivor；
6. 无 class nature 的旧户不进入本机制计划；
7. plan 确定性：打乱输入 Map 迭代序，结果成员/ survivor 不变；
8. 本单不修改 `EconomyData`/ChangeSet/Codec/roster/enterprise/LAND/账户。

## 5. 允许修改的文件

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/culture/HouseholdCultureKey.java（只加 missing 工厂）
simos-economy-api/src/main/java/io/mosire/simos/economy/api/culture/HouseholdCultureKeyLookup.java（可加 unavailable 静态工厂）
simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySession.java
simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayStepper.java
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdMergeBook.java（新增）
simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java（只改装配）
```

必要时只做编译适配的 `EconomySettlement`/测试构造调用点？**不得改测试**；测试 owner 后补。

**禁止**：EconomyData/ChangeSet/Codec 持久组件、LAND/资产/roster/enterprise 写操作、S2 合户执行、S7-B/C、seeder、production/culture 契约、`src/test/**`、主树、commit/push。

## 6. 失败/原子性

- culture lookup unavailable ⇒ `missing()`，INFO 记录；不得猜文化。
- class standing/position 缺失 ⇒ skip 该户并 DEBUG；不把无 class nature 户拉进合并。
- merge key 构造/排序必须稳定；null 字段按各 record 构造器 fail-closed。
- `HouseholdMergeBook.plan` 只读，失败不写状态。
- App culture 切片类型不对 ⇒ `IllegalStateException`（装配故障），不得静默当空。
- 本单不改变任何经济数值。

## 7. 验收锚点（测试 owner 后补）

正例：

1. 两个同 key、人口 100/80、LAND 0/100 的户 ⇒ survivor 是人口 100 的；同人口再按 LAND 降序；
2. 不同 cultureKey 不合并；不同 classNature 不合并；不同 hex 不合并；
3. survivor 规则跨输入 Map 顺序稳定；
4. App 装配真实 `CultureData` 后 `cultureKeyOf` 可读；
5. missing culture 使用显式空 key + INFO，不伪造 cultureId。

负例：

- economy 直接 import culture ⇒ enforcer 红；
- 0 人口壳被选 survivor/成员 ⇒ 红；
- 无 class nature 户被合并 ⇒ 红；
- culture lookup 缺失时按 SocialClassId/生产方式猜文化 ⇒ 红；
- plan 写入任何状态 ⇒ 红。

## 8. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy-api,simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-rc4a-merge-key-culture-lookup/impl-ledger.md`。
- 报告列：改动文件、编译 rc、lookup 装配点、merge key/survivor、可能失效测试断言、未验证项。

## 9. 本责任区不做

- 不做合户写口/资产/enterprise/roster/organizer rebind（RC4-B/C/D）；
- 不做 S2 自动合并；
- 不做 S7-B/C、flow table、LAND 市场/债务；
- 不做 §8 整组织交易。

## 10. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并，目前的有地农民家户早已脱离地主语义了，事实上是小农和地主的集合体——地主导致的灾难性阶级分化事实上也能表示，就是单个hex的有地农民人口极少土地极多，大部分人口都只能接受雇佣
R4：把这个人口上限取消掉！此外，单个家户不应该有一个对应的文化属性标签吗？如果没有，那就紧急加一个只有自然语言描述的标签，让文化模块维护
```
