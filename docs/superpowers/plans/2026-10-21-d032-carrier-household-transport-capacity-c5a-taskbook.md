# D-032 C5a 责任区任务书：运输马匹词表与耐久商品资本契约（2026-10-21）

> **状态：可派单的实现任务书；只写生产代码到 `compile` 绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md` §5/§10/§11/§13
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §5.2/§6 C5
> **上游：** C1–C4 已完成且分别 compile 绿。
> **拆分说明：** C5 同时包含“商品/耐久资本契约”和“运输生产方式/配方/Seeder”。本任务只做前一层 C5a；
> C5b 再做运输方式/角色/配方/seed 接线。这样按可独立编译的契约层拆分，避免一个 writer 同时改 production contract 与市场 seed。

---

## 0. 用户原话与冻结口径

```text
商队的生产资料暂时和耕地在市场中的表述持平，可以设置为运输马匹，目前不计算其生产，作为可交易商品，运输马匹的测试世界存量必须要大
```

```text
是的，目前不算运输马匹的耐久消耗，但是以后要，我准备在之后算一个比较简单的生产损耗以模拟……之后改生产方式配置吧
```

```text
运输能力产出全归 operator OWNER，hired_driver 只领工资
```

冻结：

- `transport_horse` 是**普通可交易商品库存**，不是 `AssetKind`，不是第二本 `OwnershipStake`；
- 运输马匹当前**不消耗、不折旧、不死亡、不繁殖**；未来只加生产方式级损耗配置；
- 运输马匹必须计入 ownershipClass：家户库存 `transport_horse > 0` ⇒ OWNER；
- 生产规模需要“耐久商品资本”通道，与消耗性 `inputPerUnit` 分离；
- C5a 不做运输生产方式/配方/seed；C5b 再做；
- 不得把普通商品库存都当资本，只有 `OwnershipCapitalVocabulary` 登记的马匹 id 计入。

---

## 1. 目标运行序列

```text
S1 载入
  ownershipClass 的 durable commodity 集合 = OwnershipCapitalVocabulary
  transport_horse 已登记 ⇒ 家户库存 > 0 时 class=OWNER

S3/S4 生产
  probe/roster/execute 请求可携带：
    durableCommodityOccupancy: Map<CommodityId,Long>
  规模 = min(assetScale, durableCommodityScale, laborScale, inputScale)
  durableCommodityScale 只读该 map，不扣减该商品
  C5a 只建立契约与派生规模；C5b 才把 transport_horse 放进实际运输配方/roster

S7-M2 债偿
  transport_horse 已进商品词表后可按库存介质估值/转移（既有库存腿；不新增非 LAND OwnershipStake 腿）

S5 产出
  C5a 不改产出通道；C6 的 non-inventory service output 另做
```

---

## 2. 责任区六要素

### 2.1 问题现场（C1–C4 后）

- `EconomyVocabulary` 只有 `grain/cloth/fiber/tool/iron/wood/haul`，没有 `transport_horse`；
- `OwnershipCapitalVocabulary.DURABLE_COMMODITY_CAPITAL_IDS` 仍为空集；
- `ProductionRecipe`/`IndustryDefinitionView` 的产能只有 `Map<AssetKind,Long>`；
- probe/roster/execute 只有 `assetOccupancy: Map<AssetKind,Long>`，没有 commodity capital occupancy；
- `ProductionRuntimeEngine.capacityScaleOf` 只读 AssetKind occupancy；
- C5a 必须让“马匹作为货架商品库存”成为可用生产资本输入，但不把马匹变成 `AssetKind`。

### 2.2 责任归属

C5a writer 负责：
- 中心商品词表 `transport_horse`；
- `OwnershipCapitalVocabulary` 接入；
- production contract 的 durable commodity capital 字段/请求/规模计算；
- 不改运输生产方式/配方/seed/市场锚价/批次。

C5b 负责 transport mode/roles/recipe/roster + seeder/价格接线。

### 2.3 最终能力

1. `transport_horse` 有一个中心稳定商品 id；
2. 只登记在 `OwnershipCapitalVocabulary` 的 durable commodity 计 class；
3. `ProductionRecipe`/`IndustryDefinitionView` 可表达 `durableCommodityCapacityPerUnit`；
4. probe/roster/execute 请求可携带 durable commodity occupancy；
5. `ProductionRuntimeEngine` 的规模算式纳入该通道；
6. 该通道不扣商品、不消费、不建 OwnershipStake；
7. 所有旧调用点用空表保持原行为；
8. compile 绿。

### 2.4 不可破坏机制

- `transport_horse` 唯一数量权威 = actor HouseholdInventory.balances；
- 不新增 `AssetKind.HORSE`；
- 不把 durable commodity 放进 `inputPerUnit`（那会按周期消耗）；
- 不把“所有库存 >0”算资本；
- 不改实际 transport 配方/价格锚/批发市场（C5b/C7）；
- 不写/跑测试、不改 pom/tools/world/docs（账本除外）、不碰主树。

### 2.5 可动范围

见 §6。

### 2.6 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c5a/impl-ledger.md`，报告：
改动文件、编译 rc、契约字段、旧调用点兼容、规模公式、测试失效清单、未完成/blocker。

---

## 3. 冻结契约

### 3.1 中心商品 id

```text
EconomyVocabulary.TRANSPORT_HORSE_COMMODITY_ID = "transport_horse"
```

- 追加到 `EconomyVocabulary.allCommodityIds()` 的末尾（保持既有商品相对序）；
- 不删 `haul`（C7 再退役旧路径）；
- 不新增第二处 `"transport_horse"` 字面量。

### 3.2 Class 接入

`OwnershipCapitalVocabulary.durableCommodityCapitalIds()` 改为只含：

```text
new CommodityId(EconomyVocabulary.TRANSPORT_HORSE_COMMODITY_ID)
```

- 其他普通商品不得进入；
- `OwnershipClassBook` 已有的 durable 商品读取链路自动生效；
- 测试 owner 后续验证“马匹买入后 OWNER”；C5a 只保证派生读口。

### 3.3 ProductionRecipe

新增：

```text
durableCommodityCapacityPerUnit = Map<CommodityId, Long>
```

- 逐值必须 **> 0**（同 AssetKind capacity 的量纲锚）；
- 空表 = 没有这一路；
- 与消耗性 `inputPerUnit` 完全分离；
- `capacityPerUnit` 旧“非空”守卫改为：
  `capacityPerUnit 非空 || durableCommodityCapacityPerUnit 非空`；
- 两个 capacity map 都不为空时，规模取两边最紧；
- 旧 4/5 参构造器给空 durable map，保持旧调用点语义。

### 3.4 IndustryDefinitionView

新增同名字段：

```text
durableCommodityCapacityPerUnit = Map<CommodityId, Long>
```

- 冻结/不可变/无 null；
- 逐值 > 0；
- 至少一个 capacity 通道非空；
- `recipe()` 把该 map 原样传给 ProductionRecipe；
- 旧 canonical/便利构造器加空 map 的 overload，旧调用点逐值不变。

### 3.5 请求契约

为以下请求增加 durable commodity occupancy 输入：

```text
ProductionProbeRequest
ProductionRosterProbeRequest
ProductionExecutionRequest
ProductionDayRequest（若它的 Step/toExecutionRequest 需要透传）
```

冻结：

```text
durableCommodityOccupancy: Map<CommodityId, Long>
```

- 只接受正数量；0 不进表；
- 空表 = 没有该通道；
- 旧构造器默认空表，旧调用点语义不变；
- 不得从各参与户 conditions 聚合马匹；马匹 occupancy 必须由 economy 按 owner/operator 户的**同一份库存**填；
- C5a 只建立透传/计算；C5b/C6 才会真正填 `transport_horse`。

### 3.6 规模公式

`ProductionRuntimeEngine` 唯一规模入口改为：

```text
assetScaleRaw = min(
  min_k ⌊assetOccupancy_k / capacityPerUnit_k⌋,
  min_j ⌊durableCommodityOccupancy_j / durableCommodityCapacityPerUnit_j⌋
)
```

- 无 durable 通道 ⇒ 只用 assetScale（旧值不变）；
- durable occupancy 缺项按 0（该通道为 0；若配方要求该 durable 商品，则规模为 0）；
- 不扣 durable 商品；
- 不修改 `ProductionEfficiency` 的 input/labor 公式；durable 只是“资产/资本”通道的一部分；
- probe/roster/execute 三处必须走同一个 `capacityScaleOf`，不能各写一份。

### 3.7 兼容边界

- 旧 4/5 参 `ProductionRecipe` 构造器 = 空 durable map；
- 旧 `IndustryDefinitionView` 构造器 = 空 durable map；
- 旧 probe/roster/execute 构造器 = 空 durable occupancy；
- 这些是 Java 调用便利构造器，不是旧档兼容；不得增加 JSON 旧字段/双读。

---

## 4. 不变量与失败语义

1. `transport_horse` 唯一拼写在 `EconomyVocabulary`。
2. 只有 `OwnershipCapitalVocabulary` 中 id 的商品库存计 class。
3. durable capacity 与 input 分离；不消费马匹。
4. capacity 两通道至少一边非空。
5. durable capacity/occupancy 逐值 > 0（0 不进表）。
6. 配方要求 durable 商品但 occupancy 缺项 ⇒ scale 0，不静默当 Long.MAX。
7. 不建 AssetKind.HORSE。
8. 不新增持久组件/Codec/ChangeSet。
9. 旧调用点用空 map，逐值不变。
10. 所有排序/校验确定性。

负例：

- 把 `transport_horse` 放进 `inputPerUnit` 当消耗 ⇒ 红；
- 新增 AssetKind.HORSE 或 OwnershipStake 马匹账 ⇒ 红；
- 所有商品库存都算 class ⇒ 红；
- durable occupancy 缺项被当无约束 ⇒ 红；
- probe/roster/execute 三套规模公式不一致 ⇒ 红；
- 旧构造点被强制改值 ⇒ 红。

---

## 5. 验收锚点（供 test owner，不在本任务运行）

- A-C5a-01：`transport_horse` 在 `allCommodityIds()`，只出现一处字面量。
- A-C5a-02：transport_horse > 0 ⇒ ownershipClass OWNER；普通 grain > 0 不改变 class。
- A-C5a-03：durable capacity 可单独作为单位规模锚；asset capacity 可为空。
- A-C5a-04：asset + durable 两路取最紧。
- A-C5a-05：durable 商品不被扣减，inputPerUnit 仍扣。
- A-C5a-06：probe/roster/execute 同 durable occupancy 给同 scale。
- A-C5a-07：旧构造器/空 map 逐值不变。
- A-C5a-08：无 durable 商品时行为不变。

负例：

- 普通库存计入 class ⇒ 红；
- 马匹扣减 ⇒ 红；
- durable scale 读到 Long.MAX ⇒ 红；
- 配方两 capacity 全空仍构造成功 ⇒ 红；
- 新增 AssetKind.HORSE ⇒ 红。

---

## 6. 文件所有权

### 允许写

```text
simos-util/src/main/java/io/mosire/simos/util/economy/**
simos-economy-api/src/main/java/io/mosire/simos/economy/api/**
simos-production/src/main/java/io/mosire/simos/production/**
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

- 约 297 项前序未提交改动；只改本责任区 main 文件，不回滚/格式化。
- C5a 不改 transport mode/roles/recipe/seed（C5b）；不改 haul 退役（C7）。
- 不改 EconomyVocabulary 既有商品相对序，只末尾追加 transport_horse。

---

## 7. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

只到 compile 绿；不跑 test / test-compile / verify / gate / 360；不 commit/push。

报告：改动文件、真实 rc/输出、契约字段/构造器影响、规模公式位置、既有测试失效清单、未完成/blocker。

---

## 8. 非目标

- 不实现 transport mode/两个角色/配方/roster/seeder（C5b）。
- 不实现 non-inventory service output/批次（C6）。
- 不实现运输需求/价格锚/lane（C7）。
- 不实现马匹买入向上/混合拆分（C8）。
- 不写测试/不跑测试/不 commit/push/不碰主树。
