# D-032 C2 责任区任务书：合并 key 与家户结构（2026-10-21）

> **状态：可派单的实现任务书；只写生产代码到 `compile` 绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md`
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §6 C2
> **上游：** C1 已完成并 compile 绿，账本 `.superpowers/sdd/2026-10-21-d032-carrier-c1/impl-ledger.md`
> **父运行序列：** 父设计 §3 S2；合并是 S2 内的家户结构归一化，S3 只应看到归一化后的活户。

---

## 0. 用户原话与已冻结口径

### 0.1 阶级/生产方式

```text
注意到，这里事实上直接以资本主义逻辑区分了有产者和无产者，单个有产者允许购买多种生产资料，包括土地、运输马匹，并同时根据利润高低组织多种社会生产，例如有地农业、运力提供，但是无产者只能是无产者，其只能作为被雇佣的一方，在买得生产资料时，自动成为对应阶层的有产者，这套机制毫无疑问是合理的；
```

```text
应当先把有地农民和无地农民规范成有产者和无产者——家户不绑定生产方式，生产方式对家户的区分从此只有有产者和无产者，细化阶层划分以后再做
```

### 0.2 合并

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并……
```

> 本计划的后续口径已把 R1 的 `productionMode` 维度删除：新的合并 key 为
> `(hex, cultureKey, ownershipClass)`；同一 OWNER 可同时拥有多个生产方式/生产组织。

### 0.3 债务清偿（影响本责任区的 target shell）

```text
市场轮还债就是该还土地还土地，该还其他东西还其他东西，有一个通用还债顺序的，也就是A；如果无产者家户在期间拥有土地等生产资料了怎么办？没有关系，后续统一转化/合并为有产者家户，中间态流程处理应该是可行的吧？
```

---

## 1. 目标运行序列

```text
S2 进入/迁移/组织（每个 tick，S3 前）：
  读取当刻活家户、Map LAND、actor 商品库存、economy assetShares
  用同一份 HouseholdOwnershipLookup 派生 ownershipClass
  按 (homeHex, cultureKey, ownershipClass) 分组人口 > 0 的家户
  同 key 且 >1 户 ⇒ 生成确定性 merge plan
  执行：
    survivor = population desc → 本 hex LAND desc → HouseholdId asc
    source → survivor 逐户重绑：
      - LAND 全量转移（唯一 LandBook 写口）
      - 非 LAND assetShares / relations / roster / enterprise / occupancy /
        reservation / demand / wage carry / shipment buyer 重绑
      - 债务、货币、货物、经验、劳动承诺、人口按既有 merge/transfer primitive
      - production operator/contribution/roster 不丢
    source 成为 0 人口壳；不直接写 Social；人口只通过 outbox
  结果必须与输入 Map 迭代序无关，失败整次 advance 丢弃。

S7-M2 债偿/中间态：
  债偿取得资产的 creditor 可以暂时没有 currentPosition/mode；
  最终 class 由资产派生，下一 S2 必须能被本责任区按新 ownershipClass 合并。
```

---

## 2. 责任区六要素

### 2.1 问题现场（已回代码核到 C1 后）

- `HouseholdMergeBook.MergeKey` 当前仍为 `(hex, cultureKey, productionMode, ownershipClass)`
  （C1 只替换了 class 维度）。
- `HouseholdMergeBook.plan` 目前仍要求 `HouseholdClassMembership` + `currentPositionId`，
  并从 current position 取 `productionMode`；无 current position 的新户会被跳过。
- `HouseholdMergeSettlement.preflightPlan` 仍强制 survivor 有 class standing/current position，
  并把 `currentPosition.modeId()` 记成 `targetMode`。
- `HouseholdTransferRequest.targetMode` 与 `EconomyPopulationTransfer.targetMode` 仍是必填。
- `HouseholdMergeRebindBook` 已处理非 LAND assetShares / relations / roster / enterprise；
  需要确保“多生产组织/多 roster”不被按 mode 合并误伤。
- C6 才会新增 `TransportCapacityBatch`；C2 不得伪造该类型，但必须在合户 rebind 处留明确扩展点，
  并在账本写明“C6 必须在本扩展点加批次重键/容量相加”。

### 2.2 责任归属

C2 writer 负责**合并 key 与家户结构**整个责任区：
merge plan/key、survivor 稳定序、source→survivor 重绑、无 mode/currentPosition 的 target/outbox
契约，及本责任区所有 main 编译修整。

运输生产方式/马匹/批次/价格/运输需求不在本责任区；C2 只保证后续 C5–C8 能安全接入 merge。

### 2.3 最终能力

1. merge key = `(homeHex, cultureKey, ownershipClass)`；
2. 同 hex、同 culture、同 OWNER/PROLETARIAN 的 `population>0` 家户可合并；
3. 不再因为 productionMode、class standing、currentPosition、classNature 缺失而跳过候选；
4. survivor = 人口降序 → 本 hex LAND 降序 → HouseholdId 升序；
5. 一个 OWNER 可同时拥有多个 production organization/roster/mode；合并不能把它们错误折叠成单一 mode；
6. merge 的资产/债务/人口/经验/roster/enterprise/occupancy/reservation/wage 引用重绑不丢；
7. 合户 target 无 mode/currentPosition 也能表达；
8. `EconomyPopulationTransfer.targetMode` 对无生产位置的 merge/target shell 可为空；
9. `TransportCapacityBatch` 的 merge 扩展点有唯一落点，C6 可无歧义补上；
10. compile 绿。

### 2.4 不可破坏机制

- 五条铁律；完整 ChangeSet/Codec 往返由测试 owner 后续守。
- 一个事实一个写口：LAND 只走 `LandBook`；账户只走 `applyTransfer`；人口只走 outbox→Social。
- 不得用 mode/position 反推 class；class 只由 C1 的 ownership lookup 派生。
- 不得新增第二份家户、LAND、库存、债务或人口权威。
- 不得改测试/pom/tools/world/docs（账本除外）。
- 已有 290 项未提交改动不得回滚/格式化/批量重写。

### 2.5 可动范围

见 §6；只改 main 代码，只到 `compile` 绿。

### 2.6 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c2/impl-ledger.md`，报告编译命令+真实 rc、
改动文件、行为影响、既有测试失效面、C6 批次扩展点、未完成/blocker。

---

## 3. 冻结数据/接口形状

### 3.1 MergeKey

```text
MergeKey {
  hex: HexCoord
  cultureKey: HouseholdCultureKey
  ownershipClass: OwnershipClass
}
```

- 删除 `productionMode` 字段、构造参数、排序键、日志字段和调用方；
- `ownershipClass` 来自 C1 `HouseholdOwnershipLookup`；
- key 的自然序：hex → cultureKey canonical → ownershipClass；
- 不得从 `HouseholdClassMembership/currentPosition` 补 mode。

### 3.2 Merge candidate

候选只要求：

- `HouseholdEconomy.population() > 0`；
- 有当前 hex/经济行；
- culture lookup 返回 key，缺失用 `HouseholdCultureKey.missing()` 并 INFO；
- ownership lookup 成功返回非 null class。

**不得要求** class standing、currentPosition 或 `classNature`。

### 3.3 Survivor/Member

- `Member(household, population, landMilliMuAtHex)` 保留；
- survivor = population 降序 → 本 hex LAND 降序 → HouseholdId.value 升序；
- members 按同一全序排序；输出 plans 按 MergeKey 全序；
- 不允许 Map 迭代序或随机 tie-break。

### 3.4 Merge target / outbox

冻结：

```text
HouseholdMergeSettlement 不再需要 targetMode；
HouseholdTransferRequest.targetMode: Optional<ProductionModeId>（或等价 optional）
EconomyPopulationTransfer.targetMode: Optional<ProductionModeId>（或等价 optional）
```

- no-mode target 是合法状态；
- Social bridge 当前不消费 targetMode；它只用于记录/诊断和旧 mode migration 路径；
- mode migration 路径仍构造 `Optional.of(mode)`，不能把已有 mode 丢掉；
- 不得用占位字符串/magic mode 冒充“无 mode”；
- 日志中无 mode 写成显式空/none，不写 null。

### 3.5 source→survivor 重绑顺序

执行顺序沿用现有 `HouseholdMergeSettlement` 的 preflight / staged working copy 原则：

```text
preflight（任何写入前）
→ production operator reoperate + cycleContributions rekey
→ HouseholdMergeRebindBook（非 LAND assetShares/relations/rosters/enterprises）
→ 双向债务重键/自债净额
→ LAND 全量 LandBook transfer
→ occupancy / reservation / demand / wageCarry / shipment buyer 重指
→ HouseholdTransferSettlement（钱/货/债/经验/劳动 + population outbox）
→ source 0 人口壳
```

- 失败必须整次 advance 丢弃，不能半写；
- survivor/source 的 LAND、accounts、assetShares、debt、roster、enterprise 均不得丢；
- source 的多个 production units 全部重绑，不得只保留一个 mode/roster；
- `ProductionRosters.mergeHousehold` 的“同户参与项合并”必须保留不同 role/organization 的身份，
  不得按 mode 合并成一条。

### 3.6 批次扩展点（C6）

C2 当前没有 `TransportCapacityBatch` 类型。冻结：

- 在 `HouseholdMergeSettlement`/`HouseholdMergeRebindBook` 的家户级重绑收口处留一个具名、单一的
  “household-owned post-state rebind extension”落点（名称由实现定，账本记录）；
- C6 新增 batch 后必须在该落点实现：
  `same marketRoundId + same homeHex` 的容量相加，不同 homeHex 分键；
- C2 **不得**提前造 batch 类型、字段、Codec 或假占位；
- C2 账本必须把这个后续义务写成明确 TODO，供 C6 任务书引用。

### 3.7 无 mode target shell

- `HouseholdMergeSettlement` no longer reads `sheet.base().classPositions()` or
  `classMemberships` to derive target mode；
- target row 可以没有 class standing/currentPosition；合并/人口 outbox 仍可执行；
- S3 之后再给活户分配当期 position；
- Social 创建家户只看 `targetHex` / target ID / population，不要求 mode。

### 3.8 债偿/新 OWNER 合并

- C1 的 `ownershipClassAtMarketTradeStart` / `OwnershipClassBook` 是唯一 class 读口；
- 债偿取得的 creditor 在下一 S2 进入本责任区 merge；
- 若其已成为 OWNER，按 `(homeHex,cultureKey,OWNER)` 合并；
- 异 hex LAND 不强制迁人（C1 已冻结）；
- 不得为了合并另造“临时 class”字段。

---

## 4. 不变量与失败语义

1. Merge key 不含 productionMode/classNature/currentPosition。
2. 同 key 且 population>0 的户必须可形成候选；缺 standing/position 不构成 skip。
3. survivor 稳定序只按人口/LAND/id。
4. 一个 OWNER 多 org/roster/mode 合法；不得因多个 mode 冲突拒绝合并。
5. source 的全部人口、LAND、非 LAND 资产、账户、债、经验、roster/enterprise 引用均不丢。
6. target 无 mode/currentPosition 合法；不得写占位 mode。
7. outbox 的 targetMode optional；ocial bridge 不因 null 崩。
8. 合户任何失败不产生半状态；同一 revision 原子。
9. 批次扩展点只能有一个；C6 必须在其上 sum by `(marketRoundId,homeHex)`。
10. 所有排序确定性。

失败：

- 缺 culture/ownership lookup ⇒ 契约故障或明确 missing，不猜；
- source population 不足/负数 ⇒ 具名拒绝；
- survivor 行/账户缺失 ⇒ 具名拒绝；
- 多 source 债务新 id 碰撞 ⇒ preflight 拒绝；
- LAND 写入绕过 `LandBook` ⇒ 契约故障；
- target 写占位 mode 或伪造 currentPosition ⇒ 契约故障；
- C2 自行创建 transport batch 类型 ⇒ 越界，停手。

---

## 5. 验收锚点（供 test owner，不在本任务运行）

- A-C2-01：同 hex/同 culture/同 class、不同 mode 的户合并为一个 plan。
- A-C2-02：缺 class standing/currentPosition 的户不被 skip。
- A-C2-03：不同 culture 或不同 ownershipClass 不合并。
- A-C2-04：survivor 排序按人口→LAND→id，members/plan 顺序确定。
- A-C2-05：OWNER 多 org/roster 合并后均在，不折叠成单一 mode。
- A-C2-06：LAND、非 LAND assetShares、债、人口、经验、roster/enterprise/occupancy/wage 引用守恒。
- A-C2-07：无 mode target/outbox 可执行，Social bridge 不崩；mode migration 路径仍保留其 mode。
- A-C2-08：合户失败不产生 ChangeSet/半状态。
- A-C2-09：债偿后新 OWNER 下一 S2 按新 key 合并。
- A-C2-10：C6 批次扩展点存在且只有一个；C2 不伪造 batch。

负例：

- merge key 仍含 productionMode ⇒ 红；
- 缺 currentPosition 被 skip ⇒ 红；
- 两个不同 mode 的 OWNER 被拒绝合并 ⇒ 红；
- 多 org/roster 合并后丢一个 ⇒ 红；
- target 填占位 mode/null 崩 ⇒ 红；
- LAND 绕过 LandBook ⇒ 红；
- source 债务/人口/资产丢失 ⇒ 红。

---

## 6. 文件所有权

### 允许写

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/**
simos-production/src/main/java/io/mosire/simos/production/contract/**
simos-production/src/main/java/io/mosire/simos/production/spi/**
simos-economy/src/main/java/io/mosire/simos/economy/**
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

### 文件纪律

- 先 `git status --short`，只改本责任区 main 文件；不回滚/格式化别人的 290 项未提交改动。
- 不新增 `TransportCapacityBatch`/运输配方/价格锚；C6/C7 才做。
- 不写/跑测试、不跑 verify/gate/360、不 commit/push。
- 发现必须改测试/pom/tools 才能 compile：停手报告，不要越界。

---

## 7. 编译与交账

唯一编译命令：

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

报告必须包含：

- 改动文件；
- 编译命令 + 真实 rc + 输出摘要；
- MergeKey/构造器/JSON/outbox 字段影响；
- 既有测试失效清单（静态识别，不运行）；
- C6 批次扩展点的实际文件/方法名；
- 偏离/未完成/blocker。

---

## 8. 非目标

- 不做 C3 市场买入 LAND 的完整 primitive。
- 不做 C4 population-only 执行器；只让 merge target/outbox 支持无 mode。
- 不做 C5 马匹商品/耐久资本接入；ownership lookup 已由 C1 提供。
- 不做 C6 运输批次/价格锚/每 tick 产出。
- 不做 C7 运输需求/lane/市场锚价。
- 不做 C8 混合买入。
- 不写测试、不跑测试、不 commit/push、不碰主树。

---

## 9. 交接

C2 完成后，控制方审账并写 C3 任务书；C6 任务书必须引用本任务书 §3.6 的批次扩展点。
