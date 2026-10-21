# D-032 RF6a 任务书：无账视图时 LaborQueuePlanBook 的 null 守卫

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md` §3 F5/F6
> **状态：** 可派实现；只写生产代码到编译过，不写/不跑测试。

## 1. 现场

RF5 测试 owner 复现：

```text
GovServiceCommitmentC7Test#laborQueueStillRewritesProductionCommitmentsOnTheSameUnit:134
java.lang.NullPointerException:
Cannot invoke "io.mosire.simos.economy.time.AccountSession.householdGoods()" because "accounts" is null
```

位置：`simos-economy/.../time/LaborQueuePlanBook.java:452`（RF2 新增 `accounts.householdGoods()`）。

契约：`LaborQueueSettlement.apply(...)` 的 5 参兼容重载明确允许 `accounts == null`，
表示“没有账视图，不会凭空吃货，也不会抛”；RF2 `LaborQueueBook.offer` 新重载也支持
`householdOnHand == null ⇒ awaiting-draw`。当前调用点在 null 时直接解引用，违反契约。

## 2. 目标

- `LaborQueuePlanBook` 调用 `LaborQueueBook.offer(..., householdOnHand)` 时：
  ```java
  accounts == null ? null : accounts.householdGoods()
  ```
- `accounts == null` 时走 `awaiting-draw`，不得 NPE、不得伪装成 NO_INPUT/0 投入。
- `accounts != null` 时行为逐值不变。
- 不改变 `LaborQueueSettlement` 的兼容重载/契约；不新增持久状态。
- 其它 RF2/RF3/RF4 逻辑不变。

## 3. 允许文件

- `simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueuePlanBook.java`
- 仅编译必需时同目录最小文件（先报告）

禁止：测试、EconomyData/ChangeSet/Codec、production 定义、主树、GUI/服务。

## 4. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
```

真实 rc=0。报告改动方法、null 行为、数值变化、受影响硬编码/既有断言、未完成；
账本 `.superpowers/sdd/2026-10-20-d032-rf6a-null-account-guard/impl-ledger.md`。

## 5. 验收锚点

- 5 参 `LaborQueueSettlement.apply`（accounts=null）不 NPE；
- `LaborQueuePlanBook` 对可读账视图仍读 on-hand，逐值不变；
- `GovServiceCommitmentC7Test` 的两条旧断言可通过（测试 owner 复跑）；
- 不把 null 账视图解释成“无投入/NO_INPUT”。

## 6. 用户原话

```text
需求被允许挂价了吗？家户提的需求，面对市场不管多贵都会直接接受的；其他没啥问题，记录一下你的修复方案，开始吧
```
