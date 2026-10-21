# D-032 C6 责任区任务书：运输生产非库存产出与市场轮批次（2026-10-21）

> **状态：可派单的实现任务书；只写生产代码到 `compile` 绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md` §5.2/§6/§10/§11/§13
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §5.2–5.3/§6 C6
> **上游：** C1–C5b 已完成且 compile 绿；C5b 已提供唯一 `CaravanTransportTemplate` 配方与服务键；
> C2 已留合户 post-state 扩展点；C1 已有 OwnershipClass。

---

## 0. 用户原话与冻结口径

```text
运输生产应当是每一tick都进行组织——这意味着，实际还是要囤4tick（市场轮间隔）的临时运力在运输家户，囤到有产者商人家户上，然后在市场轮的时候，再统一把这些运力挂到市场上，不同的是，上一市场轮的运力在这一市场轮无法保留，以实现运力分批的效果
```

```text
运输能力产出全归 operator OWNER，hired_driver 只领工资
```

```text
当前不算运输马匹的耐久消耗……以后改成生产方式配置
```

```text
市场轮末未售整批作废；上一市场轮不可保留
```

冻结：

- 运输生产每 tick 组织；本责任区只负责把运输服务产出累积到 `marketRoundId` 批次；
- 服务产出不是普通商品库存，不进 `HouseholdGoods`，不按角色份额分给雇工；
- 所有运输能力归 operator OWNER 户；
- 批次不得跨 marketRoundId；市场轮末清零；
- 马匹不扣、不折旧；
- 本批不实现 C7 的运输需求/lane/锚价，也不实现 C8 马匹向上。

---

## 1. 目标运行序列

```text
S4/S5 生产
  ProductionRunSettlement.harvest 看到 recipe.outputPerUnit 的
  CaravanTransportTemplate.SERVICE_OUTPUT 键：
    - 从普通 output/netByCommodity 中剔除
    - 不进入 RoleDistribution / householdGoods / income
    - 计算 capacityWorkMilli = scale × SERVICE_MILLI_PER_SCALE_UNIT
    - 通过 TransportCapacitySink 交给 economy
  sink → TransportCapacityBatchBook.accumulate(...)
      key = (operator household, homeHex, marketRoundId)
      value.marketZone = homeHex 所属市场区
      capacityWorkMilli 累加

S7-END（市场日）
  清除当前 marketRoundId 的全部 transportCapacityBatches
  未售容量由 C7 读口/日志处理；本责任区只保证清除

S2 合户
  HouseholdMergeRebindBook.rebindHouseholdOwnedPostState(...)
  把 source 的批次重键到 survivor：
    同 marketRoundId + 同 homeHex ⇒ capacityWorkMilli 相加
    不同 homeHex ⇒ 分键保留
```

---

## 2. 责任区六要素

### 2.1 问题现场（C5b 后）

- `CaravanTransportTemplate.SERVICE_OUTPUT` 已是唯一内部服务键；
- `ProductionRunSettlement.harvest` 仍把 `recipe.outputPerUnit()` 全量当普通商品：会 credit 库存/参与角色分配；
- `EconomyData` 仍只有 35 个组件，没有 `transportCapacityBatches`；
- `EconomyChangeSet`/`EconomyStateBuilder`/`EconomyCoreCodecs` 没有该组件；
- C2 的 `HouseholdMergeRebindBook.rebindHouseholdOwnedPostState` 仍是 no-op；
- S7-END 没有按 marketRoundId 清批次。

### 2.2 责任归属

C6 writer 负责：
- production harvest 的 non-inventory service 分流；
- `TransportCapacityBatchKey`/`TransportCapacityBatch` 状态与 sink；
- EconomyData/ChangeSet/StateBuilder/Codec/invariant 的 35→36 组件同步；
- S7-END 清当前轮；
- 实现 C2 批次合户扩展点；
- 本责任区所有 main 编译修整。

C6 **不做**运输需求/分配/价格锚/市场撮合（C7），不做马匹向上（C8），不做 seeder（C9）。

### 2.3 最终能力

1. S5 的运输服务产出只进批次，不进普通库存/收入/角色分配；
2. 批次 key 中 operator household / homeHex / marketRoundId 正确；
3. 每 tick 累加，市场轮内不丢；
4. 市场轮末当前 marketRoundId 批次全部清除；
5. 上一轮批次不可见；
6. 合户时批次不丢，容量按同一 homeHex 相加；
7. 35→36 组件往返/Codec 全部编译通过；
8. compile 绿。

### 2.4 不可破坏机制

- 服务键唯一拼写仍来自 `CaravanTransportTemplate`；
- 服务能力不进入普通 `HouseholdInventory`、`MarketTable.prices`、自然需求；
- 批次写入仍走 `EconomyData`/ChangeSet/StateBuilder；
- LAND/Actors/ownership class 既有权威不变；
- C2 的单个 post-state 扩展点只能用于此批次重键，不新增第二套 merge 逻辑；
- 不写/跑测试、不改 pom/tools/world/docs（账本除外）、不碰主树。

### 2.5 可动范围

见 §6；只改 main 代码到 compile 绿。

### 2.6 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c6/impl-ledger.md`，报告编译、改动文件、
批次 key/value 形状、marketRoundId 算法、sink 接口、合户扩展点、测试失效面、未完成/blocker。

---

## 3. 冻结数据形状

### 3.1 Key

建议（可微调包名，账本记录实际类型）：

```text
TransportCapacityBatchKey {
  household: HouseholdId
  homeHex: HexCoord
  marketRoundId: long
}
```

- 必须可稳定 `toString/parse` 或注册对应的 key codec；
- 不含市场模式、不依赖 Map 迭代序；
- `marketRoundId` 是绝对 tick 的“本批将结算的市场日”。

### 3.2 Value

```text
TransportCapacityBatch {
  marketZone: MarketZoneId
  capacityWorkMilli: long
}
```

- `marketZone` = 生产时 homeHex 所属市场区；
- `capacityWorkMilli >= 0`；
- 同一 key 一值；不得出现同 key 多值；
- 值不含普通库存/商品/账户。

### 3.3 marketRoundId

使用市场常量 `MarketConstants.MARKET_RESTOCK_INTERVAL_DAYS`（当前 5）计算：

```text
marketRoundId = 下一个 day % interval == 0 的绝对 tick
当前 day 本身是市场日 ⇒ 取当前 day
```

- 不得另写 5 字面量；
- 非市场日产出进入下一市场日；
- 市场日 S4/S5 产出进入当天批次（已冻结）；
- 同一天所有运输收据使用同一个 id。

### 3.4 Sink

建议接口：

```text
TransportCapacitySink {
  void accept(HouseholdId operatorHousehold, HexCoord homeHex,
              long marketRoundId, long capacityWorkMilli)
}
```

- `operatorHousehold` 由 production receipt 的 unit/operator 反查到当前家户；
- `homeHex` 取 operator 户当刻 homeHex（不是 industry id 的字符串、不是买方 hex）；
- `capacityWorkMilli = scale × CaravanTransportTemplate.SERVICE_MILLI_PER_SCALE_UNIT`；
- 不按参与户聚合马匹，不扣马；
- 若 operator 户/市场区找不到 ⇒ fail-closed，不静默丢容量。

### 3.5 Harvest 分流

`ProductionRunSettlement.harvest`：

- `recipe.outputPerUnit()` 中 `CaravanTransportTemplate.isServiceOutput(commodity)` 的项：
  - 不算 `grossByCommodity/netByCommodity`；
  - 不进入 `creditRosterNetPool` / `creditOutput`；
  - 不进入 `ProductionSettlement` 关系结算；
  - 只调用 sink；
- 普通商品输出仍走原逻辑；
- 如果只有服务输出，sink 后可以正常返回；
- service capacity 不参加 `ProductionOutputNetting` 的普通商品损耗；
- 若同一 recipe 同时有普通和非库存服务输出，二者分别处理，不能互相吞掉；
- 失败不半写。

### 3.6 持久组件

新增 `EconomyData.transportCapacityBatches`（建议追加在 record 末尾）：

- 35→36 组件；
- `EconomyChangeSet` 同位置字段 + diff/apply/isEmpty；
- `EconomyStateBuilder` 工作副本/accessor/build；
- `EconomyDataTransforms` 与 `empty()`；
- `EconomyCoreCodecs.keyModule()` 注册 key 反序列化器；
- `EconomyDataInvariants` 校验 key/value、非负、homeHex→marketZone 一致性（可做到的结构校验）；
- 不写旧档兼容/双读；旧档 Alpha 直接重建。

### 3.7 S7-END 清零

在市场轮结算收尾调用：

```text
clearTransportCapacityBatches(ctx, currentMarketRoundId)
```

- 只清除 key.marketRoundId == currentMarketRoundId 的条目；
- 不清别的市场轮/别的区；
- 清除必须在 S7-B/S7-C 之后、市场轮最终收尾时；
- C6 不读取/消费未售容量，只清；C7 后续增加读口/过期日志。

### 3.8 合户扩展点

实现 C2 的：

```text
HouseholdMergeRebindBook.rebindHouseholdOwnedPostState(sheet, source, survivor, survivorHomeHex)
```

- 找 source 的所有 transportCapacityBatches（所有 marketRoundId）；
- 同 `(marketRoundId, homeHex)` 重键到 survivor，`capacityWorkMilli` 相加；
- 不同 homeHex 保留不同 key；
- 不能丢 source 批次；
- 只在此方法实现，不新增第二套合户批次逻辑；
- 合户失败由上游整次 advance 丢弃。

---

## 4. 不变量与失败语义

1. 服务 key 不进入普通库存/收入/角色分配。
2. 所有服务能力归 operator OWNER 户。
3. 批次 key 是 `(household, homeHex, marketRoundId)`；值只含 marketZone/capacity。
4. 每 tick 累加；marketRoundId 不跨轮。
5. S7-END 只清当前轮。
6. 合户批次不丢，同 homeHex 相加。
7. 35→36 组件 ChangeSet/Codec/StateBuilder/往返一致。
8. 不扣 transport_horse。
9. 不写第二套 market/matcher/价格。
10. 所有排序/运算确定性。

失败：

- service 产出进 HouseholdGoods/RoleDistribution ⇒ 红；
- 服务键写成第二个字面量 ⇒ 红；
- marketRoundId 跨轮/读旧轮 ⇒ 红；
- 批次 key 与值不一致/容量为负 ⇒ 红；
- 合户丢批次或按错误 homeHex 合并 ⇒ 红；
- S7-END 清掉别轮批次 ⇒ 红；
- 组件 35→36 漏 ChangeSet/Codec ⇒ 编译/往返红。

---

## 5. 验收锚点（供 test owner，不在本任务运行）

- A-C6-01：运输服务产出只进批次，普通库存/收入不变。
- A-C6-02：hired_driver 不取得服务能力。
- A-C6-03：非市场 tick 产出进下一市场日；市场日产出进当天。
- A-C6-04：每 tick 累加；同 key 一个批次。
- A-C6-05：S7-END 清当前轮；上一轮不可见。
- A-C6-06：合户同 homeHex 容量相加，不同 homeHex 分键。
- A-C6-07：35→36 组件、Codec、StateBuilder、ChangeSet 往返。
- A-C6-08：无市场区/homeHex 缺失 fail-closed。
- A-C6-09：服务输出不参与普通商品净产损耗/关系分配。
- A-C6-10：批次 key 用 operator homeHex，不取买方/industry 字符串。

负例：

- 服务进入 HouseholdGoods ⇒ 红；
- 雇工分到服务份额 ⇒ 红；
- 批次跨轮保留 ⇒ 红；
- S7-END 清错轮 ⇒ 红；
- 合户丢批次/容量不守恒 ⇒ 红；
- 组件数量/KeyCodec 漏改 ⇒ 红。

---

## 6. 文件所有权

### 允许写

```text
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

- 约 307 项前序未提交改动；只改本责任区 main 文件，不回滚/格式化。
- 不改 C7 需求/锚价/lane；不改 C8 马匹向上；不改 C9 seed。
- 不新增测试/不跑测试。

---

## 7. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

只到 compile 绿；不跑 test / test-compile / verify / gate / 360；不 commit/push。

报告：改动文件、真实 rc/输出、批次 key/value 实际类型、marketRoundId 算法、sink 接口、
合户扩展点、测试失效清单、未完成/blocker。

---

## 8. 非目标

- 不实现 C7 运输需求/锚价/lane/市场撮合。
- 不实现 C8 马匹向上/混合拆分。
- 不实现 C9 seeder/读口/32hex。
- 不做运输马匹折旧/损耗。
- 不写测试/不跑测试/不 commit/push/不碰主树。
