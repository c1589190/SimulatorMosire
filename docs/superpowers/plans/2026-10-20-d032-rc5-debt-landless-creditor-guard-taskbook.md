# RC5 任务书：S7-M2 不得把 LAND 转给 LANDLESS 债权人

> **状态：约束设计书/实现任务书；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：v2 S7-M2；v4 §4、§5、§11。
> 依赖：RC1/RC2a 已提供 `ClassNature`/`ClassNatureBook`。
> 本单只改债务清算 LAND 腿的 class filter，不改债务排序、金额、市场或 LAND 总量。

## 1. 目标

`DebtFirstSettlement` 的还款介质仍是：

```text
货币 → 商品库存 → 标量 LAND
```

但当债权人 class nature = `LANDLESS` 时：

```text
LAND 腿必须跳过；
余债保留；
LAND 留债务人（之后可进市场/下一轮）；
不得通过债务清算制造 LANDLESS + LAND>0 或绕过“向上只走市场买地”。
```

## 2. 当前事实（file:line）

- `DebtFirstSettlement.java:40-60`：土地腿当前对任何活跃债权人都可转，未按 class nature 过滤。
- `DebtFirstSettlement.java:177-205`：`payWithLand(...)` 从债务人 landHoldings 逐 hex 转给债权人，并 `markLandAcquired`。
- `DebtFirstSettlement.java:374-455`：LAND 逐 holding 的跳过原因已有 `land-price-empty`、`land-price-zero-not-auto-collateral`、`land-value-rounds-to-zero` 等；本单新增 class-nature 原因。
- `ClassNatureBook.java`（RC2a）：`classNatureOf(EconomyDayView, HouseholdId)` 是唯一派生口。
- `EconomyData` 不变量应保证正常状态 `LANDLESS` 无 LAND；S7-A 临时 LAND 只属于市场买方，不属于债务清算。
- 用户原话：“无地农民只能通过买市场上抛售的土地才能成为本hex或者其他hex的有地农民”（v2 §1）。

## 3. 冻结行为

1. 在 `payWithLand` 开始处（或每条 LAND holding 循环前）派生**债权人** class nature：

```text
ClassNature creditorNature = ClassNatureBook.classNatureOf(ctx.dayView, debt.creditor()).orElse(null);
if (creditorNature == LANDLESS) {
    log DEBT_FIRST_M2_LAND_SKIPPED reason=creditor-class-nature-landless
    return PaymentOutcome(0 monetary land payment, 0 transfers, 0 land quantity, same landState);
}
```

- **只跳过 LAND 腿**；货币/商品两条已经发生的支付不受影响。
- 不要按 `SocialClassId` 猜；不要按 `debt.source` 或 `WAGE_ARREARS` 猜。
- 无 class nature 的旧债权人（`Optional.empty()`）保持既有 LAND 行为。
- 债权人不在活家户表/人口 ≤ 0 的既有 FORGIVEN 边界保持不变。
- 不得把 LAND 转给不存在的债权人；不得将 LAND 转给 landless 债权人后再记 debt 已减。
- 日志：INFO/DEBUG 具名含 day/debtor/creditor/contract/remaining/landQuantity（跳过数量），reason 固定。

## 4. 必须达到的行为

1. LANDED 债权人：货币→库存→LAND 行为逐值不变；
2. LANDLESS 债权人：陆地腿 0，债务本金不因 LAND 减少；LAND 仍在债务人账上；
3. 无 class nature / 非农村债权人：旧路径不变；
4. `landAcquiredQuantityThisRound` 不因 landless 债权人增加；
5. 不改变债务排序、纯库存财富快照、LAND 总量守恒、0 价/未定价 skip 语义；
6. 不改变 `DebtFirstSettlement` 对工资债 ID cutoff 的既有逻辑。

## 5. 允许修改的文件

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/DebtFirstSettlement.java
```

必要时只读调用 `ClassNatureBook.java`；不得改 `ClassNatureBook` 语义。
可选只改 `SettlementDayDebtPipeline.java` 的注释/日志；不改持久组件。

**禁止**：EconomyData/ChangeSet/Codec、LandBook/LandLedgerState、市场撮合、production/culture 契约、seeder、`src/test/**`、主树、commit/push。

## 6. 验收锚点（测试 owner 后补）

正例：

1. LANDED 债权人欠款 100，LAND 价>0，LAND 可转移 ⇒ 既有金额/数量；
2. LANDLESS 债权人欠款 100，LAND 价>0 ⇒ landTransfers=0、landTransferred=0、debt principal 不变、LAND 留债务人；
3. LANDLESS 债权人同时有货币/商品可收 ⇒ 货币/商品腿照常，LAND 腿单独跳过；
4. 无 class nature 的旧债权人 ⇒ LAND 腿照常；
5. 0 价/未定价 LAND ⇒ 既有 skip 优先，日志 reason 正确。

负例：

- LANDLESS 债权人收到 LAND ⇒ 红；
- 因 skip LAND 却错误地减少本金 ⇒ 红；
- 按 SocialClassId/债务来源粗筛误伤 LANDED 债权人 ⇒ 红；
- LAND 总量/债务人持有不守恒 ⇒ 红。

## 7. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-rc5-debt-landless-creditor-guard/impl-ledger.md`。
- 报告列：改动文件、编译 rc、skip 位置/日志 reason、守恒点、旧路径兼容、可能失效断言、未验证项。

## 8. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v2.md` §1，2026-10-20。

```text
向上流动我觉得可以取缔掉了，无地农民只能通过买市场上抛售的土地才能成为本hex或者其他hex的有地农民
```

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R2：有地的家户挂单根据库存情况等写好的判断挂单，如果是没地的家户买到了土地，那么就把买到的土地连同那一部分比例的人口全部转移走，如果是有地的家户买到了土地，那就是买到了，走正常的生产支配流程
```
