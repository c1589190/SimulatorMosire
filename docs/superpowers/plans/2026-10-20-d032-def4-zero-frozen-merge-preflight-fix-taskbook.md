# 生产缺陷修复任务书：DEF-4 合户 preflight 零值冻结

> 父序列锚点：v2 S2 同 key 合户前置；RC4-D `HouseholdMergeSettlement.preflight`。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 测试 owner 已停；只写生产代码到 compile，不写/不跑测试。

## 1. 现象（T1 报告）

`HouseholdMergeSettlement.preflightSource` 用 `Map.isEmpty()` 判冻结：

```text
source=hh--3_0-rural-landless_laborer frozenMoney={copper=0}
source=hh--1_-1-rural-landless_laborer frozenMoney={silver=0}
```

零值冻结项等价于无冻结，但 `isEmpty()==false` ⇒ `household-merge-frozen-account` 整次 advance 失败。

## 2. 冻结修复

只改 `HouseholdMergeSettlement.preflightSource`：

- 新增私有 helper `hasPositiveFrozenMoney(AccountSession.ActorAccount)` 与 `hasPositiveFrozenGoods(...)`；
- 只对 `value != null && value > 0` 的项拒绝；
- 零值/负零项视为无冻结（负数本身是坏数据，若出现按正数同样 fail-closed，不静默放行）；
- 错误 detail 仍带原 frozen 表，便于定位；
- 不改冻结释放、账户写口、市场/合户其它逻辑。

## 3. 允许修改

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdMergeSettlement.java
```

禁止 tests、seeder、EconomyData/ChangeSet/Codec、MarketFreezeBook、主树；不 commit/push。

## 4. 验收锚点（测试 owner 后补）

- frozenMoney={copper=0} / frozenGoods={grain=0} ⇒ 合户 preflight 不拒；
- frozenMoney={copper=1} / frozenGoods={grain=1} ⇒ 仍具名拒绝；
- 负数冻结 ⇒ 仍拒绝（坏数据）；
- 真正空 map ⇒ 通过；
- 不改变 `HouseholdTransferSettlement` 的钱/货移动语义。

## 5. 编译

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 账本：`.superpowers/sdd/2026-10-20-d032-def4-zero-frozen-fix/impl-ledger.md`。
- 报告：改动位置、helper 规则、compile rc、未验证项。
