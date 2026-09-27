# M2 一般市场 —— 施工台账（2026-09-27 开工）

> **性质**：M2 批的施工台账（控制方写；代码代理只写生产代码、不 commit）。
> **口径来源**：`docs/superpowers/plans/2026-09-27-master-development-plan.md` 第五部分（M2.0–M2.7）
> + §八·补（用户 2026-09-27 三处裁定，甲=30 天安全库存）+ 用户追加裁定「阶段门禁只做编译、所有测试移到最后」。
> **开工 HEAD**：`648da43b`（M1 剩余全部落地：M1.2–M1.8 生产代码 + 台账）。

## M2.0 定案（逐条可执行）

1. **区（`MarketRegion`）**：以**城市节点**为集散点，半径 = 该城 tier 的 `tierRadiiHex`（MajorCity [8,16]；M0.6 实测 R8 覆盖首都缺口 156%）。
   ★ **不按国界**（绝不把"一个王国"当一个区：430 格宽 ⇒ 200+ 天，比周期还长）。
2. **货币**：单币种 `silver`，无换汇、无跨国（用户裁定）；订单/报价的币种 = 该市场的 `numeraire`。
3. **手续**：冻结（`OwnershipBooks.freeze*`，M1.2）→ 验证 → **原子提交**（`EconomySettlement.applyTransfer` 两遍式，M1.4）→ 释放。
4. **交割**：区内**即时**；跨区按 `TradeRoute` 的 ETA/损耗/运力。★ **跨区结算暂设即时**（设计允许，但必须在读数与配置里写明）。
5. **频率**：商品撮合**每 5 天一轮**；粮食库存低于阈值可追加一轮（阈值与追加规则做**具名常量**）。
6. ★ **甲裁定（用户 2026-09-27）**：家户"生活保留" = **到下轮补货/成交前的预测消费 + 30 天安全库存**（**默认值**、参数化具名常量）；
   `可售库存 = max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留)`，各项**互不重复扣除**；
   `MARKET_SELF_RESERVE_PER_MILLE = 1000‰` 的**整周期自留退休**（不许与新口径并列保留）。
7. **参与主体**：家户 + 经营者（`ESTATE`/`WORKSHOP`，账在 actor 侧）+ 运输经营者（`ActorKind.ORGANIZATION`）；
   **统计标签（贫/中/富/地主）不承担资格开关**。
8. **M2 退出条件（MASTER）**：两格粮布/纤维循环成立，**不靠财政兜底**。

## 分层（按"可独立编译的层"拆，不按计划编号；一个代理一层、§一.7 前台派单）

| 层 | 覆盖 | 内容 | owner 边界（该层可动的文件） |
|---|---|---|---|
| **L1 订单与参与者** | M2.1 + M2.2 | 订单类型（`BuyOrder`/`SellOrder`）+ 家户/经营者订单生成（含 30 天安全库存、必要投入、已冻结的逐项扣除）+ 参与者扩容（经营者入市）；**先接进今天的"每格一市"**（拓扑不变），让订单体系当天就活 | `simos-economy-api/.../market/**`、`simos-economy/.../time/MarketSettlement.java`、`simos-economy/.../time/EconomySettlement.java`（仅接线）、`simos-economy/.../model/Market.java`、`simos-app/.../time/**`（会话副本装配）、必要时 `ApiViews`（仅为编译） |
| **L2 区域撮合与运输** | M2.4 + M2.3 + M2.5 | `MarketRegion`（城市节点 + tier 半径）+ 区内优先/跨区候选 + `TradeRoute` + `ShipmentBatch`（★ 在途是**跨 tick 状态** ⇒ 铁律 5：新组件必须回填变更集/codec/payloads）+ 基线合同（买方付货款与运费、买方承担损耗、在途不可消费） | 在 L1 成果上加：`economy-api/market/**`、`economy/time/MarketSettlement.java`、`economy/EconomyData.java`+`EconomyChangeSet`+`EconomyCodec`+`EconomyPayloads`（若加组件）、`app/time/**` |
| **L3 价格与读数** | M2.6 + M2.7 | 每区每商品固定报价（第一版）+ 可选自适应（**默认关**，α ≤ 5%/轮、定点数）；逐区读数（供给/需求/成交量/到货价/运费/损耗/未成交原因/未用运力）+ 丙条的 `cycleNaturalNeedMilli` 累加器与 `tick/lastSettledDay` 口径标注 | 在 L1/L2 成果上加：`Market`（价格）、`MarketSettlement`（价格过滤）、`ApiViews`（读数）、`economy-api`（读数契约） |

## 待办 / 边界（如实）

- **测试、变异自证、全仓 `clean verify`、一年期读数（tick 120/240/360）：全部留到最后**（用户 2026-09-27 裁定）。
- L1 若判断**必须**新增 `EconomyData` 状态组件 ⇒ 先停下报告、由控制方裁（M2.1/M2.2 的订单按计划是**瞬时**的，不应需要新组件）。
- M1.8 的"分不满"后果（预算 clamp）未处理 —— cap-aware 再分配超出 MASTER 原文，留作后续裁定项；M2 若撞上再回头。
- 乙条（首都人口）按 §八·补 的更正执行：**两个数都保留、报表带 tick 标注**（已请用户复核）。
- 本目录台账由控制方写；每层代码代理**不 commit / 不碰 src/test / 只做编译门禁**。

---

## L1 订单与参与者（M2.1+M2.2）—— ✅ 代码落地，提交 `76fa01cc`

**改动**：新增 `economy-api/.../api/market/{Budget,BuyOrder,SellOrder}`；`MarketSettlement` 重写为
「主体各自生成订单 → 限价过滤 → 供≤求按需求比例配给 → 逐笔经唯一 applier」；`EconomyDayStepper` 增加 4 张只读冻结快照
（+`OwnershipBooks` 4 个载入器 + 两个协调器装配）；`applyTransfer` 新增带冻结重载（第一遍加判「扣完不得低于冻结」）。
**甲裁定落地**：`MARKET_RESTOCK_INTERVAL_DAYS=5`、`MARKET_SAFETY_STOCK_DAYS=30`、`MARKET_LIFE_RESERVE_DAYS=35`；
`MARKET_SELF_RESERVE_PER_MILLE`（1000‰ 整周期自留）**已删除**（无并列旧路）。
**零新状态组件**（EconomyData/ChangeSet/Codec/Payloads 未动）；订单是本轮瞬时的；写入仍只走 `applyTransfer`。

**★ 已知断层（交给 L2 / 最后测试代理）**：
1. **订单窗口按 5+30 天，调度仍是"每格每周期一次"** ⇒ 家户只在周期末补到 35 天库存、其余日子可能缺粮。
   **L2 必须把撮合改成"每 5 天一轮"**（M2.0 #5，外加粮库存低于阈值的追加轮）——这是 L1 与 L2 之间的**硬接缝**。
2. **冻结的写者未接**（挂单冻结/释放、在途占用）；4 处旧 `applyTransfer` 调用点仍走空冻结（真档 frozen 恒空 ⇒ 数值无影响）。
3. **工具的"外部买方"未解决**（真档同格作坊自产自用）⇒「作坊卖掉工具」目前只有"生单"定性成立；要靠 L2 跨格。
4. 每商品**顺序清算**、不是同时订单簿；跨商品"卖纤维的钱同轮买种子"未处理。
5. 所有数字是**按常量推算**，未跑夹具/一年期（按裁定留到最后）。

## L2 区域撮合与运输（M2.4+M2.3+M2.5）—— 进行中

派单时给 L2 的**额外硬要求**（在 MASTER 原文之外、但在 M2.0 定案与 L1 接缝之内）：
- **调度**：撮合改为**每 5 天一轮**（`MARKET_RESTOCK_INTERVAL_DAYS` 同一常量），粮库存低于阈值可**追加一轮**；
  必须与 M0.1 的"一次 360 == 三次 120"路径一致（世界日绝对计数 ⇒ 两条路径同日同轮）。
- **冻结生命周期**：挂单/已承诺交付走 M1.2 `freeze*`，交付/撤销走 `release*`；在途货物按合同归买方（不可在到货前消费）。
- **在途状态**：`ShipmentBatch` 跨 tick ⇒ 需要持久化；若加 `EconomyData` 第 10 个组件，**必须同批回填**
  `EconomyChangeSet`/`EconomyCodec`/`EconomyPayloads`（铁律 5），并在报告里点名会红的往返用例（测试留到最后）。
- **地图事实的入口**：`economy` 可 import `simos-map`、**禁 `simos-unit`**；运输代价自写（口径抄 `SettlementGenerator` 的
  `hexDistance × moveCost(目标格)`）；地形/邻接必须由 app 侧以**只读输入**或生成期数据交给 economy（不许给 economy 加对 GameMap 的隐藏全局依赖）。

## L2 区域撮合与运输（M2.3+M2.4+M2.5）—— ✅ 代码落地，提交 `6908c455`

**调度**：`MarketTrigger` = 每 5 天 PERIODIC + 关账日 CYCLE_CLOSE 保底 + 粮覆盖 <10 天时窗口第 3 天 LOW_GRAIN_STOCK
（只看绝对日 + 状态 ⇒ M0.1 同日同轮）。**区域**：`MarketRegion/MarketNode/MarketTopology`（经济侧只读件）+
组合根 `MarketTopologyBook`（social.cities → map.cities → craft@ 三档回退；半径取 tier 上界）。
**运输**：`TradeRoute/ShipmentBatch/ShipmentAllocation/LossBearer`；运费 10‰/hex→ORGANIZATION 承运人（无承运人则**不收**、
只记 `freightUncollectedMilli`）；实际投入 = 距离×目标格 moveCost；损耗 5‰ 买方承担 → `market-transport` 损耗账户；
ETA=1 天/格；运力 = 100,000,000×8÷距离、每轮 4 窗口。**铁律 5**：`EconomyData` 第 10 组件 `shipments` + ChangeSet/Codec/
Payloads/SeedHandler 同批回填。**冻结生命周期**接上（挂单冻结→成交/发运/轮末释放）。

**★ 已如实记的边界**：9 参 `new EconomyData(...)` 的测试将**测试源码编译红**（留到最后）；守恒网需加"在途资产"项；
价格桶未实现；无寻路（直线距离×目标格 moveCost）；一轮内多笔无整轮回滚；实际投入未从账上扣；真档无 ORGANIZATION ⇒ 运费实收 0。

## L3 价格与读数（M2.6+M2.7 + 丙条仪器）—— 进行中

L3 额外硬要求（在 MASTER 之外、由前两层与用户裁定逼出来的）：
- **丙条仪器**：逐行 `cycleNaturalNeedMilli` 累加器（新周期清零；`ClassRow` 加字段要照旧档兼容 = 缺键 0），
  `economyHex` 报 `tick/lastSettledDay` 与该值的人口快照口径；两个"不可比口径"不得再并排当同一分母。
- **M0.3 的 `unavailable` 项复评**：`logisticsGap`（M2.4 后已有定义）必须处理；`paymentInstrumentGap` 的接受规则属 M2；
  `productionSelfSufficiency` 要 ledger 的周期累计（能做才做，做不了保持具名 unavailable，**绝不填 0**）。
- **读数不许进 `EconomyData`**（该类注明写"市场表里没有会过期的读数；读数在当天的 ledger 里"）：
  优先**读时派生** + 进程内 `lastMarketReport`；哪些是"进程内可得、重启即失"必须如实标注。
- **价格模式标注**：固定价（第一版）与自适应（默认关）必须在读数里可区分；跨区结算暂设即时也要标注。
