# RC3 任务书：家户部分迁移原语与删除人口上限

> **状态：约束设计书/实现任务书；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：v2 S7/R5；v4 R4/R5。
> 依赖：RC1/RC2a/RC2b 已 compile 绿。
> 本单不做 R1 full merge、S7-B post-land、S7-C flow table、LAND/Culture 变更。

## 1. 目标

1. **删除 `MAX_HOUSEHOLD_POPULATION` 及所有调用点**，让已有目标户可接纳任意人口；新建目标仍只受真实资产/劳动可行性约束。
2. 提供一个可复用的**家户部分迁移原语**：

```text
move population + money + movable goods + debts + experience
with source/target identities, targetHex, targetMode, reason
```

供后续 RC6（landless 买地→landed 户）和 RC7（landed→landless 向下流动）复用；LAND 不在此原语里自动搬。

## 2. 当前事实（file:line）

- `ModeMigrationPolicy.java:164-165`：`MAX_HOUSEHOLD_POPULATION = 200L`。
- `ModeMigrationCandidateBook.java`：
  - `311-315` / `392-406`：existing/new target room 由 200 减去人口；
  - `544-572`：`placeInto` 把新目标 want 再夹到 200；
  - `705-712`：`bestExistingTarget` 按剩余容量选目标。
- `MigrationDebtMove.java:74-130`：goods 按人口比例随迁（可复用）。
- `MigrationDebtMove.java:169-205`：money 按人口比例随迁（可复用）。
- `MigrationDebtMove.java:218-...`：debt 按 `MigrationMove.debtMilli()` 逐合同分摊，支持目标=债权人的自债净额。
- `MigrationPopulationMove.java:720-760`：labor commitment 平移/合并辅助；目前绑定迁移执行器。
- `EconomyPopulationTransfer.java`：population outbox 已存在（瞬态，不进 Codec/ChangeSet）。
- `ProductionRuntime.mergeExperience(...)`：经验/解锁随人口合并已有契约口。

## 3. 冻结形状

### 3.1 部分迁移原语

新增 `simos-economy/time/HouseholdTransferSettlement.java`（名字可改，但一个责任区一个入口）：

```text
record HouseholdTransferRequest(
    HouseholdId source,
    HouseholdId target,
    long population,
    HexCoord targetHex,
    ProductionModeId targetMode,
    String reason,
    boolean targetIsNew)
```

入口至少提供：

```text
HouseholdTransferSettlement.apply(
    EconomySession session,
    AccountSession accounts,
    ProductionLedger.Accumulator ledger,
    long day,
    int ordinal,
    HouseholdTransferRequest request,
    long sourcePopulationBefore,
    long targetPopulationBefore)
```

- 不修改 `HouseholdEconomy.population/laborMilli`（Social 是权威；App 在 step 后按 outbox 回写）；只发 outbox 并改 money/goods/debt/experience/allocations。
- 只允许 `population > 0`、`population <= sourcePopulationBefore`、source != target。
- 不改 LAND；LAND 转移/清零由 RC6/RC7 在调用者侧显式做。
- `targetIsNew` 只影响 outbox 的 `newTarget` 与 Social 侧 CREATE_HOUSEHOLD；经济行/class standing 由调用者预先建立。

### 3.2 钱/货/债/经验

- money：按源户**当刻余额**逐币种 `floor(balance × population / sourcePopulationBefore)`；源户搬空 ⇒ 该币种全部走；余数留源。复用 `MigrationDebtMove.moveAllCurrenciesByPopulation` 或等价纯函数。
- goods：按源户**可用库存**（余额 − 冻结）逐商品同比取整；源户搬空 ⇒ 全部；运输服务 HAUL 不搬；写路径仍 `EconomySettlement.applyTransfer`。复用 `carryGoodsForMove`。
- debt：先算 `debtTake = floor(sourceTotalDebt × population / sourcePopulationBefore)`（源户搬空 ⇒ 全部）；再逐合同按剩余本金分摊，目标=债权人时自债净额。可用 `MigrationDebtMove.moveDebt`，但需把它抽成不依赖完整 `MigrationMove` 的核心（或构造合成 move）；不得静默丢债，分摊余数必须归零。
- experience：调用 `session.productionRuntime().mergeExperience(source, population, target, targetPopulationBefore)`；只使用既有生产契约，不在 economy 重算经验。
- labor commitments：
  - 部分迁移时，源户的 labor commitments 按人口比例缩减；源户搬空时移除其全部 commitments；
  - 本原语**不主动在目标户新建 production commitments**（下一 tick S3/M6 由劳动队列重建；避免把 organizer 劳动错误搬到目标户）；
  - 非 production commitments 若调用方明确要求可搬运，另开参数；首版不搬。
  - 任何情况下 `Σ commitments(household) ≤ projected laborMilli` 的现有构造期不变量不得破。

### 3.3 population outbox

成功后追加：

```text
new EconomyPopulationTransfer(
    source, target, population, targetHex, targetMode, targetIsNew, reason)
```

- outbox 是瞬态；不新增 `EconomyData`/ChangeSet/Codec。
- 日志：`HOUSEHOLD_TRANSFER_APPLIED` INFO（source/target/population/targetHex/targetMode/money/goods/debt/experience），逐币/逐商品 TRACE。

## 4. 删除人口上限

在 `ModeMigrationPolicy` 删除 `MAX_HOUSEHOLD_POPULATION` 常量；在 `ModeMigrationCandidateBook`：

- existing target room 不再由 200 计算；改为 `Long.MAX_VALUE` 或显式 `canAccept=true`；
- `bestExistingTarget` 不再按“剩余容量最大”选，改为稳定排序（HouseholdId 升序）选择同 key 目标；
- new target `want = allow - placed`，不再 `min(... , 200)`；
- `buildDisplacedTargets` / `buildTargets` 的 new/existing room 同步改无上限；
- 保留 `MIGRATION_SPEED_CAP_PER_MILLE`（速度上限不是人口上限，R4 不删它）；
- 任何 `MAX_HOUSEHOLD_POPULATION` import/引用清零；日志不得再宣称 200 人/户。

## 5. 必须达到的行为

1. `grep -R MAX_HOUSEHOLD_POPULATION simos-economy/src/main simos-app/src/main` 0 命中；
   docs 历史留痕不要求删除；
2. 已有同 key target 可接纳 >200 人（无上限），但新 target 仍受资产/劳动可行性；
3. 部分迁移的钱/货/债按人口比例守恒，源户搬空时全部走；
4. 债务分摊总额 == `floor(totalDebt × population / sourcePopulationBefore)`，逐合同余数归零；
5. target 是债权人时自债净额，不生成自持合同；
6. 不修改 LAND；不修改 HouseholdEconomy.population/laborMilli；
7. 同一 source 多个 transfer 依次读活账，不重复按初值切分；
8. 日志/outbox 可追溯；outbox 不进持久状态。

## 6. 允许修改的文件

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationPolicy.java
simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationCandidateBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MigrationDebtMove.java（只提取可复用核心）
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdTransferSettlement.java（新增）
simos-economy/src/main/java/io/mosire/simos/economy/time/MigrationPopulationMove.java（只在编译/复用需要时）
simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyPopulationTransfer.java（不改形状，除非只补注释）
```

**禁止**：EconomyData/ChangeSet/Codec 持久组件、LAND 持有转移、production/culture 契约、seeder、`src/test/**`、主树、commit/push。

## 7. 验收锚点（测试 owner 后补）

正例：

1. existing target population=1000，迁入 500 合法，不再出现 200 上限；
2. source pop=100, 迁 40：每币种/商品取 floor，余数留源；债 take=floor(total×40/100)，逐合同 Σ=take；
3. source 迁空：钱/货/债全部走，源无残债；
4. target=债权人：自债净额，不 upsert `debtor==creditor`；
5. 源 labor commitments 按比例缩减/搬空清除，源 commitments ≤ projected labor；
6. outbox 记录正确，且不入 ChangeSet/Codec；
7. 多笔 transfer 顺序迁移，第二笔按第一笔后的活账计算。

负例：

- 任何 200 上限夹紧 ⇒ 红；
- 钱/货/债按错误分母（初值而非活账）导致重复搬 ⇒ 红；
- debt 分摊余数丢失/自持合同 ⇒ 红；
- 原语自动搬 LAND 或改 HouseholdEconomy.population/laborMilli ⇒ 红；
- target 不存在/源人口不足/源=target ⇒ 具名拒绝或契约故障。

## 8. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-rc3-household-transfer-cap-removal/impl-ledger.md`。
- 报告列：改动文件、编译 rc、原语 API、钱货债比例/守恒、MAX 引用清零证据、可能失效测试断言、未验证项、与任务书不一致处。

## 9. 本责任区不做

- 不做 R1 full household merge/organizer rebind（RC4）；
- 不做 S7-B/RC6、S7-C/RC7；
- 不做 LAND 转移；
- 不做需求/预算/工资公式变更；
- 不实现 §8 整组织交易。

## 10. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R4：把这个人口上限取消掉！此外，单个家户不应该有一个对应的文化属性标签吗？如果没有，那就紧急加一个只有自然语言描述的标签，让文化模块维护
R5：是的，随着人走
```
