# D-032 RF2c 任务书：标准农业世界 work-first idle 政策

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md`
> **父目标：** v2 S3“家户按单位劳动净收益排序选择位置”；用户原话“人一天能不工作？”
> **状态：** 可派实现；只写生产代码到编译过，不写/不跑测试。

## 1. 现场

RF1+RF2 复跑 day1 日志：

```text
LABOR_QUEUE_PLAN ... unit=unit-farm@...-rural-middle_peasant ...
maxAbsorb=62348 granted=0 outcome=IDLE_FOREGONE_POSITIVE
reason=INPUT_ON_HAND
```

`sched` 的 `SURVIVAL_OUTLOOK` 为 `idleEligible=true`，而默认
`EconomyPolicyKey.SURVIVAL_IDLE_POLICY = 1`（`PREFER_IDLE_WHEN_SUBSISTENCE_UNMET`）会把所有可自由分配劳动直接置 idle，
哪怕 farm offer 是正收益。结果是 96 个 farm 在第一日仍没有 grant，劳动链无法恢复。

v2 没有要求“生存不足就一律停工”；相反，用户 2026-10-19 的农业原话强调家户按利润/净收益选择生产位置，且曾明确
“第二次生产方式选择，和家户生产、入库本身确实是每一tick都要干的啊——人一天能不工作？”。本批将标准农业运行世界设为
**work-first**：正收益工作先做，idle 只击败零/负收益且无可计价补偿的工作。`idleEligible` 仍保留为事实读数，不删除。

## 2. 目标

- **标准农业 world 的 economy 初始政策**：`SURVIVAL_IDLE_POLICY = 0`（`BEST_EFFORT_WORK`）。
- 不修改 `EconomyPolicyKey` 的全局缺省（仍为 1，供其他世界/旧规则使用），避免静默改变所有世界语义。
- 该政策通过 `economy.Seed` 载荷可审计地落盘，而不是只存在于测试夹具。
- day1 farm 正收益 offer 必须能得到正 `granted`；`IDLE_FOREGONE_POSITIVE` 不再出现在有正收益 farm 的行上。
- `idleEligible` 仍保留；无正收益工作/零负收益时 idle 仍可胜出；GOV_SERVICE/既有不排队配额不变。

## 3. 约束设计

### 3.1 载荷

- `economy.Seed` 增加**可选顶层** `economyPolicies` 对象：
  ```json
  {"economyPolicies":{"SURVIVAL_IDLE_POLICY":0}}
  ```
- 缺键/null ⇒ 空表 ⇒ `EconomyMeta` 缺省语义（不得改变旧载荷行为）。
- 键必须是 `EconomyPolicyKey.name()`，值必须是整数且在 `EconomyPolicyKey` 的 `[min,max]` 内；
  未知键/越域/非整数 ⇒ `IllegalArgumentException`（fail-closed）。
- 解析后传入 `EconomyMeta` 的 `economyPolicies` 字段；不得新增 `EconomyData` 组件，不得改 Codec/ChangeSet。
- `planAssetSources`/`planLaborSources` 仍按现有缺省空表。

### 3.2 种子写入

- `ProductionPayloadSeeder.jsonOf(...)` 在 `economy.Seed` 顶层写：
  ```json
  "economyPolicies":{"SURVIVAL_IDLE_POLICY":0}
  ```
  （用 `EconomyPolicyKey` 的稳定 name；不要写裸魔法字符串散落在多个文件。）
- 只有生产运行时（标准农业）seed 走这条；如果实现发现该入口也服务其它 profile，必须在账本说明范围；
  不得改 `EconomyPolicyKey` 全局默认值。

### 3.3 非目标

- 不修改 idle 策略算法本身（`LaborQueueBook.plan` 的 `BEST_EFFORT_WORK` 分支已存在）；
- 不修改工资/债务/土地/市场/生产公式；
- 不新增持久组件；
- 不写/改测试，不跑 test/verify；
- 不全局改 `SURVIVAL_IDLE_POLICY` 缺省。

## 4. 允许修改文件

- `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java`
- `simos-app/src/main/java/io/mosire/simos/app/world/ProductionPayloadSeeder.java`
- 仅编译必需时：`simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloadSupport.java`
  （必须先报告）

禁止：测试、`EconomyData`/ChangeSet/Codec、`EconomyPolicyKey` 全局默认、主树、GUI/服务。

## 5. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

真实 rc=0。报告：改动文件、载荷形状、政策值、编译 rc、会改变数值行为、受影响硬编码字面量、可能失效测试断言、
未完成/未验证、实现账本 `.superpowers/sdd/2026-10-20-d032-rf2c-standard-work-first/impl-ledger.md`。

## 6. 验收锚点（测试 owner 后补）

- 32hex 标准世界 seed 后 `EconomyMeta.policyOf(SURVIVAL_IDLE_POLICY)==0`；
- day1 有正收益的 farm `LABOR_QUEUE_PLAN` 出现 `GRANTED`，不再 `IDLE_FOREGONE_POSITIVE`；
- 无正收益/零负收益工作的 idle 仍可 `IDLE_CHOSEN`；
- `idleEligible` 仍可作为事实读出；
- 全局 `EconomyPolicyKey.defaultValue()` 不变；其它 profile/旧载荷省略 `economyPolicies` 时逐值不变。

## 7. 用户原话

```text
需求被允许挂价了吗？家户提的需求，面对市场不管多贵都会直接接受的；其他没啥问题，记录一下你的修复方案，开始吧
```

农业位置选择原话（2026-10-19）：

```text
第二次生产方式选择，和家户生产、入库本身确实是每一tick都要干的啊——人一天能不工作？
```
