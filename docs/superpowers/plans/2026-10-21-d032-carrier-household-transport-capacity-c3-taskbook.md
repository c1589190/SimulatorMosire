# D-032 C3 责任区任务书：LAND 向上跃迁（2026-10-21）

> **状态：可派单的实现任务书；只写生产代码到 `compile` 绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md`
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §6 C3
> **上游：** C1/C2 已完成且各自 compile 绿；C2 已把 merge key 改为 `(hex,cultureKey,ownershipClass)`，
> 并让 `HouseholdTransferRequest` / `EconomyPopulationTransfer` 的 `targetMode` 可为空。
> **本责任区只做 LAND 的市场买入向上跃迁；运输马匹/混合买入属 C8。**

---

## 0. 用户原话与冻结口径

```text
单个有产者允许购买多种生产资料，包括土地、运输马匹……无产者……在买得生产资料时，自动成为对应阶层的有产者
```

```text
如果是没地的家户买到了土地，那么就把买到的土地连同那一部分比例的人口全部转移走
```

```text
已有的阶级跃迁已经实现了，无地农民买入当前/另一hex的地，自动成为对应hex属性相同的有产者
```

```text
如果是混合情况，那么拍个先后顺序，先成为地主，为对应hex的有地农民家户增加对应土地和人数，不增加同时买入的其他生产资料
```

本责任区冻结：

- 目标 = `(hex, cultureKey 优先, OWNER)`；
- 不要求 target 有 productionMode/currentPosition/classStanding；
- target 不存在 ⇒ 建确定性的 0 人口 OWNER 壳；
- 吸收人口 0 ⇒ LAND 仍完整转 target；
- 市场买入路径与债偿取得路径分离；
- 混合买入的马匹分支留给 C8，C3 只执行 LAND 半边。

---

## 1. 目标运行序列

```text
S7-M2 完成后
  ownershipClassAtMarketTradeStart = 当刻最终资产派生快照（C1 已实现）

S7-A 市场撮合
  PROLETARIAN 买入 LAND
  MarketRound.landAcquiredQuantityThisRound() 只记录市场成交（不含 M2 债偿）

S7-B LAND 向上
  对每个 PROLETARIAN buyer 的 (hex, acquiredLand)：
    1. 读取开盘前快照；只处理 PROLETARIAN
    2. 以当刻活 LAND ledger 确认 buyer 在该 hex 的 land 数量足够
    3. 计算 minLandPerPerson / maxByLand / maxBySpeed / maxByWealth / migrants
    4. 选择 target：
       - 同 hex + OWNER
       - 同 cultureKey 优先
       - 同 culture 无正人口时退回确定性的正人口 OWNER
       - 完全没有 OWNER ⇒ 建立确定性 0 人口 OWNER 壳
       - 不再要求 buyerMode == targetMode
    5. LAND 从 buyer 全量转 target（LandLedgerState/LandBook 唯一写口）
    6. migrants > 0 ⇒ HouseholdTransferSettlement：
       - targetMode = Optional.empty()
       - 人口 + 钱/货/债/经验按现有 split 语义
       - targetIsNew = 本次新建 target
    7. 同一 revision 内完成；失败整次 advance 丢弃
```

---

## 2. 责任区六要素

### 2.1 问题现场（C1/C2 后）

`PostLandTradeClassCheck` 当前仍残留旧 mode 语义：

- `PlannedMove` 持有 `buyerMode`；
- `planBuyers` 调 `currentModeOf(ctx, buyer)`；
- `selectTarget` 用 `candidateMode.equals(buyerMode)` 选 `modeMatched`，无 mode 匹配直接拒绝；
- `HouseholdTransferSettlement` 请求仍传 `Optional.of(move.buyerMode())`；
- 没有“完全没有 OWNER 时建 owner shell”的路径；
- 仍以 `productionMode` 作为 target 硬条件，违反新合并/阶级口径。

C2 已让 `HouseholdTransferRequest.targetMode` 与 `EconomyPopulationTransfer.targetMode` 可为空，
因此 C3 可以真正传无 mode target。

### 2.2 责任归属

C3 writer 负责 S7-B LAND 半边：目标选择、吸收人数、LAND 转移、人口/钱货债/经验迁移、
0 人口 OWNER 壳、原子性与日志。C3 不实现运输马匹、C4 population-only、C5 马匹商品、C8 混合拆分。

### 2.3 最终能力

1. PROLETARIAN 市场买入 LAND 后，LAND 全量进入对应 hex 的 OWNER target；
2. target 不要求 productionMode/currentPosition/classStanding；
3. 同 culture 优先但不硬绑 mode；
4. 没有 OWNER target 时建 0 人口 OWNER 壳；
5. 吸收人口为 0 时 LAND 仍转，不凭空造人；
6. 迁移人口/钱货债按既有吸收能力/比例算法，失败不半提交；
7. C8 可在此 LAND primitive 上扩展马匹分支；
8. compile 绿。

### 2.4 不可破坏机制

- LAND 只走 `LandLedgerState/LandBook` 写口，不写商品库存/assetShares 第二本土地账。
- 人口仍只经 `EconomyPopulationTransfer` outbox，由 Social 做权威写回。
- 目标 shell 不得伪造 productionMode/currentPosition；可以为空。
- 不改 `ownershipClassAtMarketTradeStart` 快照时机。
- 不改 C2 的 merge key/outbox Optional 语义。
- 不写/跑测试、不改 pom/tools/world/docs（账本除外）、不碰主树。

### 2.5 可动范围

见 §6；只改 main 代码到 compile 绿。

### 2.6 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c3/impl-ledger.md`，报告编译、
改动文件、target shell 规则、数值/行为影响、既有测试失效面、C8 复用接口、未完成/blocker。

---

## 3. 冻结契约

### 3.1 Buyer 判定

```text
buyerClass = ctx.ownershipClassAtMarketTradeStart.get(buyer)
```

- snapshot 缺失 ⇒ fail-closed（不默认为 PROLETARIAN）；
- buyerClass != PROLETARIAN ⇒ 跳过（有产者正常买入，不走目标户吸收）；
- 不得在 LAND 已写回后现算 buyer class。

### 3.2 Target 选择

```text
同 hex 的 OWNER候选：
  当刻 OwnershipClassBook.classOf(ctx, candidate) == OWNER
  排除 buyer 自己
  按 population desc → 本 hex LAND desc → HouseholdId.value asc 排序

优先同 cultureKey：
  若同 culture 有正人口候选 ⇒ 取排序首项
  若同 culture 只有 0 人口候选 ⇒ 取排序首项（可作为 0 人口 OWNER 壳）
没有同 culture：
  若任意 OWNER 正人口候选 ⇒ 取排序首项 + INFO culture mismatch
  若只有任意 OWNER 0 人口候选 ⇒ 取排序首项 + INFO
完全没有 OWNER：
  建确定性 0 人口 OWNER 壳
```

- **不再调用 `currentModeOf` 选 target**；
- **不再拒绝 mode mismatch**；
- target 的 class 由 LAND 资产派生，不由 position 派生；
- target shell 的 ID 必须确定性，禁止随机 UUID；
- target shell 默认 0 人口、空 goods/money/account、无 classStanding/currentPosition；
- 若 target shell 被新建且后续需要 Social 家户，`HouseholdTransferSettlement` 的
  `targetIsNew=true` 走既有 outbox CREATE_HOUSEHOLD；无人口迁移时不强制造 Social 行。

### 3.3 LAND 转移

- 只从 `ctx.marketRound.landAcquiredQuantityThisRound()` 取“市场取得”；
- 不得把 `ctx.landAcquiredQuantityThisRound()`（含 M2 债偿）当作市场买入；
- buyer 在该 hex 的 LAND holding 必须 ≥ 取得量；不足 fail-closed；
- 转移后 buyer 在该 hex 的 LAND 必须为 0（LAND 全量转目标）；
- LAND 守恒：转前后全 hex 总量不变；
- transfer 后 `MarketRound.clearMarketLandAcquired(key)`；
- 不复制 LAND 到 target 的 `assetShares`。

### 3.4 吸收人口/资产

沿用现有算式（C3 不重造）：

```text
minLandPerPerson = C1 前既有动态口径（Social naturalNeeds[grain] + production 配方）
maxByLand = acquiredTotal / minLandPerPerson
maxBySpeed = floor(population * 100 / 1000)   // 现有 100‰ 上限
maxByWealth = 现有 maxByWealth 口径
migrants = min(maxByLand, maxBySpeed, maxByWealth, population - 1)
```

- `migrants == 0`：LAND 仍完整转移；
- `migrants > 0`：调 `HouseholdTransferSettlement.apply`，`targetMode = Optional.empty()`；
- 迁移来源人口每笔递减；每笔 `sourcePopulationBefore` 必须是递减后的活值；
- 钱/货/债/经验按现有 `HouseholdTransferSettlement` 比例语义；C3 不改成分摊公式；
- target 是新建 owner shell 时，`targetIsNew=true`；否则 false；
- 目标为 0 人口且 migrants>0 时仍允许，人口会进入该 target。

### 3.5 0 人口 OWNER 壳

必须满足：

```text
HouseholdEconomy pop=0, labor=0, residence=source residence 或确定性规则
账户 = 空账户（registerHousehold）
不建 currentPosition/classStanding（或建空 standing；不得伪造 mode）
LAND 转入后才派生 OWNER
```

- 若现有 `EconomyData` 构造约束要求某些空组件，按既有空值语义填；
- 不允许为了过构造器写 magic mode/占位 position；
- 若 Social 侧必须有家户，由后续 outbox 在有人口迁移时创建；无迁移的 0 人口 ECONOMY 壳不强行伪造 Social 家户。

### 3.6 C8 复用接口

C3 必须把“LAND 吸收人数/目标选择/转移”收成 C8 可复用的 primitive，
使 C8 只增加马匹分支、最大余数混拆和两目标顺序，不复制 LAND 算式。

---

## 4. 不变量与失败语义

1. LAND 写入只走 LandBook；LAND 总量守恒。
2. PROLETARIAN 判定使用 S7-M2 后快照，不用 LAND 写回后的现算结果。
3. target 不要求 mode/currentPosition/classStanding；不得用 mode 选目标。
4. 同 culture 优先；没有 OWNER 才建壳；壳 ID 确定性。
5. migrants=0 时 LAND 仍完整转 target，不凭空造人。
6. 每笔迁移 source 人口递减、目标按实际 target 计；不得重复使用初值。
7. 目标 shell 不伪造 mode/position；无 mode 的 outbox 可执行。
8. 任何失败整次 advance 丢弃，不允许半提交。
9. 所有排序确定性。
10. C8 可复用的 primitive 只有一个入口。

失败：

- snapshot 缺失/null ⇒ 契约故障；
- buyer 当前 LAND 不足/残留 ⇒ 具名拒绝；
- LAND 总量不守恒 ⇒ 契约故障；
- target 选择仍依赖 mode ⇒ 红；
- 无 OWNER 却不建壳而丢 LAND ⇒ 红；
- 0 人迁移仍要求 target 正人口 ⇒ 红；
- 迁移金额/人口不守恒 ⇒ 整次回滚。

---

## 5. 验收锚点（供 test owner，不在本任务运行）

- A-C3-01：PROLETARIAN 在无 OWNER 的 hex 买 LAND ⇒ 建 0 人口 OWNER 壳，LAND 全转。
- A-C3-02：同 culture OWNER 优先于不同 culture；同 culture 无时确定性 fallback。
- A-C3-03：target 无 productionMode/currentPosition 仍可接收 LAND/人口。
- A-C3-04：migrants=0 时 LAND 全转、人口不变。
- A-C3-05：migrants>0 时 LAND、人口、钱/货/债/经验守恒，source 该 hex LAND=0。
- A-C3-06：有产者 buyer 不走 PROLETARIAN 吸收路径。
- A-C3-07：snapshot 在 S7-M2 后，LAND 写回不改变本 tick buyer 判定。
- A-C3-08：债偿 LAND（ctx.landAcquiredQuantityThisRound）不被误当市场买入。
- A-C3-09：C8 复用 LAND primitive 不复制公式。
- A-C3-10：失败无半状态。

负例：

- 仍用 buyerMode==targetMode 选 target ⇒ 红；
- 无 OWNER 但失败/丢 LAND ⇒ 红；
- target shell 写占位 mode/position ⇒ 红；
- LAND 写 commodity/assetShares ⇒ 红；
- 0 迁移要求正人口 target ⇒ 红；
- 迁移 sourcePopulationBefore 未递减 ⇒ 红；
- LAND 总量变化 ⇒ 红。

---

## 6. 文件所有权

### 允许写

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/**
simos-economy/src/main/java/io/mosire/simos/economy/**
simos-production/src/main/java/io/mosire/simos/production/contract/**
simos-production/src/main/java/io/mosire/simos/production/spi/**
simos-app/src/main/java/io/mosire/simos/app/**
```

### 禁止写

```text
**/src/test/**
**/pom.xml
tools/**
test-world/**
主树 /home/cna/SimulatorMosire
docs/**（除 `.superpowers` 账本）
```

- 前序约 293 项未提交改动；只改本责任区 main 文件，不回滚/格式化/批量重写。
- 不改 C1/C2 的持久形状/merge key/outbox 已冻结语义。
- 不改 C5–C8 的马匹/批次/价格锚。

---

## 7. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

只跑 compile；不跑 test / test-compile / verify / gate / 360；不 commit/push。

报告：改动文件、真实 rc/输出、target shell 规则、数值影响、既有测试失效清单、
C8 复用入口、未完成/blocker。

---

## 8. 非目标

- 不实现运输马匹买入/混合拆分（C8）。
- 不实现 C4 population-only/向下 flow。
- 不实现 C5 马匹商品/耐久配方。
- 不实现 C6/C7 批次/价格锚/运输需求。
- 不写测试/不跑测试/不 commit/push/不碰主树。
