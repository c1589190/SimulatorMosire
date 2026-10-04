# 7 hex 全链路：商人阶层、家户按真实利润迁移与流民户（架构设计）

> 状态：**已设计、待实现**（2026-10-06）
> 规则依据：`AGENTS.md` §一.8（实现架构的子 Agent 派单前，必须先有本文件这样可阅读、可验证、逻辑完全的架构设计文档）。
> 用户裁定来源：2026-10-06 会话原话（“先接上真生产/市场分配”“才7个hex，模拟必须全链路”“家户自主选择生产方式……加权把当前家户的人口带着债务和生产方式挪到隔壁符合条件的家户/新创建一个家户”“流民也能适配这套体系”“A，让这个阶层的负债继续加”）。
> 关联文档：`docs/superpowers/plans/2026-10-06-probe-to-runtime-migration-plan.md`、`docs/superpowers/specs/2026-10-05-tenancy-wage-production-modes.md`、`docs/superpowers/specs/2026-10-05-city-merchant-urbanization.md`、`docs/superpowers/reports/2026-10-06-probe-golden-readings.md`。

## 0. 一句话目标

在**正式运行时**（`EconomyData` + `EconomyDayStepper` + `EconomySettlement` + `MarketSettlement`）上跑通 7 hex 全链路：真实生产、投入、劳动、市场成交、债务、消费/死亡；家户按**真实结算出来的利润**加权把人口+货币+债务迁到邻格已有 mode 的家户或新建家户；商人作为一个真实运转的阶层；旧档一律作废、GM 重置，不做兼容迁移。

## 1. 目标与非目标

### 1.1 目标

1. **全链路**：7 hex 上真实跑生产结算、市场撮合、跨格运输、债务/利息/欠款、消费/饥饿/死亡，不允许再用 `ProbeEconomy` 的外生 `π` 参数做迁移信号。
2. **真实利润**：每个生产组织/家户的迁移权重来自本周期真实发生额（产出销售、运费、投入实扣、租/工资实付、维护、利息实付；欠款单列）。
3. **加权迁移**：把家户人口的一部分或全部，按“目标 mode/hex 单位劳动净收益高于当前”的权重，迁往：
   - 邻格已有同 mode、有空位的家户 ⇒ 合并；
   - 没有可用目标户、但邻格有该 mode 所需资产/承载 ⇒ 新建家户；
   - 两者都不满足 ⇒ 该目标权重记 0，不许凭空造人/造资产。
4. **人口、货币、债务随迁**：迁走的人口按人口比例带走货币和债务；源户可缩编，人口归零且账清空后消亡；总量守恒。
5. **流民户（`DISPLACED`）是一等 mode**：没有生产组织/雇主/资产的失产失业家户进入该 mode；它可以被任何有劳动缺口的组织雇佣，也可以迁往正利润 mode；没有去处时留下，债务按 A 规则继续增长。
6. **商人阶层真实运转**：merchant principal / porter / self-employed 三种位置；商号有真实运费收入、porter 工资、城区占用/船畜维护成本；盈利/亏损影响运力规模；商号本身也参与利润迁移与 A 规则。
7. **转移速度规则（A 规则）**：**不修改任何现有家户的生产方式**。生产方式属于目标家户；把人口+货币+债务迁到“其他家户/新建家户”才是转变方式的唯一表示。若某户预期利润 < 0 且“现金+可立即变现库存 < 下一周期再生产所需投入”，则把该户的**转移速度提高到上限**，把人口按权重转往单位劳动预期净收益更高的可行目标家户（已存在则合并，不存在则新建）。若该户自身已是最高预期收益候选（即任何转移都不会使它的人均预期收益变好），则不转移、继续生产，让债务/欠款按 A 规则继续增长。只有当前家户连生产也无法维持、且不存在任何可行目标时，才把人口转入 `DISPLACED`（同样是转移到新建/已有流民户，不是改写原户 mode）。
8. **旧档作废**：不做旧数据迁移/兼容；检测到旧档/版本不符/结构不完整 ⇒ 拒绝激活，只能 GM 重置后按新 profile 重播。

★ **身份不原地改写（贯穿全文档的硬不变量）**：本批任何自动路径都**不得**把正在生产/存在的家户的 `mode`、`ClassStanding`、`ProductionOrganization.modeId` 原地改成另一种生产方式。转变方式只能表现为“源家户人口/货币/债务转移到目标 mode 的已有家户或新建家户”；源户可缩编、人口归零后消亡。仓库中若已有自动原地改 mode 的路径（例如把 `ModeTransition` 接到自动决策上），本批必须删除或旁路；只有显式 GM 手改可暂时保留，但也要在后续改为转移/新建语义。

### 1.2 非目标

1. 不做商人“买低卖高”的跨市场投机交易；本批商人仍是**承运服务 + 运费利润 + porter 工资**模型。
2. 不做旧 class-first 存档、旧 EconomyData、旧载荷的读入兼容。
3. 不一次实现完整全国/多国经济；7 hex 是正式运行时上的最小全链路验证世界。
4. 不做城市财政/商税与 GOV 的完整接线（可留接口与具名缺口）。
5. 不做货币兑换/FX（用户 2026-10-06 D-023：兑换是市场行为，后续结合国家系统）。
6. 不做“劳动市场主动招募流民”：流民没有工作；他们只能通过自己选择/被吸收转移到其他生产方式的新/已有家户（D-023）。

## 2. 现状与目标

| 维度 | 现状（HEAD `6826779e`） | 目标 |
|---|---|---|
| 生产/市场 | 旧正式运行时 `EconomySettlement`/`MarketSettlement` 已恢复；`EconomyTestWorld` 有 5 hex 真生产夹具 | 7 hex 正式全链路可跑 3650 tick |
| 迁移 | 仅有测试探针 `HomeModeMigrationProbeTest`（外生 π，未进 main） | `ModeMigrationSettlement` 进 main，用真实利润；人口+货币+债务随迁 |
| 流民 | `LABORER` 池/`wage_laborer` 位置，但无通用失产失业户 | `DISPLACED` mode + 两个位置 + 进入/离开规则 |
| 商人 | `merchant` mode/positions 仅目录；`MarketSettlement.carrierOf` 取第一个 ORGANIZATION | `MerchantFirm` + `MerchantSettlement`：选择承运商、收费、发工资、增缩运力 |
| 旧档 | class-first/legacy 兼容仍有若干旁路 | 版本门拒绝旧档；GM 重置唯一出路 |

## 3. 组件与数据模型

### 3.1 现有持久组件（不动组件数与 Codec 形状，除 3.2）

- `ProductionOrganization`：继续作为“mode + 阶层位置 + 劳动来源 + 资产来源 + 产出归属”的桥。商人/流民也用它表达。
- `ProductionUnit`：生产活动实例；商人贸易单元的 `industry = trade@<hex>`、`modeKey = "mode:merchant"`。
- `AssetShare`：实物资产唯一总账；新增 `SHIP`/`CATTLE` 的份额表示商队运力资产。
- `ClassRow` / `ClassStanding` / `Membership`：人口、阶层归属、成员份额。
- `DebtContract` / `DebtContractBook`：债务唯一写口；迁移只搬本金，不消灭债。
- `OperatorCondition`：组织经营状态与 `lastCycleRevenue/Cost/Net` 的真实利润读数。
- `Market` / `MarketNode` / `TradeRoute` / `ShipmentBatch`：市场与在途。
- `Industry`：新增 `trade` 模板（`regime = merchant`，无商品产出，只有劳动与资产容量；具体产出由运费腿表达）。

### 3.2 新增持久组件：`merchantFirms`

`EconomyData` 增加第 31 个组件：

```text
merchantFirms: Map<ProductionOrganizationId, MerchantFirm>
```

`MerchantFirm` 字段（全部不可变，构造期守卫）：

```text
organizationId        商号对应的 ProductionOrganization（键 == 值内 id）
tier                  PORTER / SELF_EMPLOYED / BOSS
homeHex               商号所在城市格
homeIsCity            true 暂定只允许城市商号
capacityPerRound      本周期可用运力（毫单位）
capacityUsedThisRound 本周期已用运力（轮初清零）
serviceRadiusHex      服务半径；PORTER=2 / SELF_EMPLOYED=4 / BOSS=8（具名默认，GM 可调）
ruralTradeCostPenaltyPerMille  农村商号累积成本（0..100，每活跃轮 +2）
lastFeeEarnedMilli    上一周期运费实收
lastUpkeepMilli       上一周期城区/船畜维护支出
lastProfitMilli       上一周期净收益（可负）
```

同步修改：`EconomyChangeSet`、`EconomyCodec`、`EconomyPayloads`、读口、31 组件反射守卫。旧档不含此键 ⇒ 由版本门直接拒绝，不做缺省回填。

### 3.3 `DISPLACED` mode

`DefaultProductionModes` 增加 `displaced` mode 与两个位置：

```text
displaced_laborer    DIRECT_LABORER / PROVIDER / WAGE_EARNER
displaced_destitute  DIRECT_LABORER / NONE     / DEPENDENT
```

进入：组织退出/无任何可行候选/危机信号/GM。
离开：被任何组织雇佣（写 `LaborAllocation` + `ProductionRelation` 工资腿，standing 改到 `wage_laborer`/`porter`）；或取得资产后按利润规则切回生产 mode。

## 4. 数据流与调用次序

### 4.1 日/周期次序（在现有 `EconomyDayStepper` 内）

```text
每个生产日：
  ① 消费 / C1 留口粮（现有）
  ② 生产：产能、劳动、投入、收获（现有）
  ③ 市场：区内撮合 + 跨区路线 + 在途（现有 MarketSettlement）
  ④ 关系实付：地租、工资、商人运费（现有 + MerchantSettlement）
  ⑤ 债务：利息、偿还、欠款资本化（现有）
  ⑥ 饥饿/死亡率（现有）
周期关账日额外：
  ⑦ OrganizationProfitBook.collect()    ← 汇本周期真实账
  ⑧ ModeMigrationPolicy.plan()          ← 算权重、A 规则、目标选择
  ⑨ ModeMigrationSettlement.apply()     ← 搬人/钱/债，合并/新建/消亡
  ⑩ 清理临时读数，进入下一周期
```

关键顺序约束：
- ⑦ 必须在 ⑥ 之后（利润要读到本期真实发生额）；
- ⑨ 必须在下一周期“投入开扣”之前，否则播种日已按旧规模扣料；
- 商人选择承运商必须在 ③ 的市场撮合内部完成（路线的承运人 = 真实 merchant firm）。

### 4.2 `OrganizationProfitBook`（新增，主包）

输入：本周期逐日 `ProductionLedger`、`MarketReport`、`AccountSession` 的期末/期初 delta、`FlowRow`。
输出：`Map<ProductionOrganizationId, OrganizationProfit>`：

```text
modeId / unitId / householdId / hex
revenueMilli = 产出货款实收 + 运费实收 + 服务收入
costPaidMilli = 投入实扣 + 关系实付租/工资 + 维护/城区占用 + 利息实付
arrearsMilli = 应付未付（单列，不混进成本）
netMilli = revenue − costPaid
laborMilli = 本周期实际投入劳动（分母）
netPerLaborMilli = net / max(1, labor)
```

### 4.3 偿还与计息的介质/估值（D-023）

- 利息仍按合同 `interestRatePerMillePerCycle` 计算，可并入合同未偿额；**不按 DebtUnit/币种拆多套账**。
- 偿还/付款不规定介质：债务人和债权人（含外部放贷主体、政府）都可以“有啥付啥”。
- 估值顺序：
  1. 债务人/债权人的**家户价目表**（若该家户有对应价格）；
  2. 否则用所在**市场区默认价目表**（`Market.prices`）。
- 支付时把可用商品/货币按上述价目表折成合同计价口径，逐笔 floor；支付腿仍走唯一转移/债务写口。
- 本批先实现市场区默认价目表路径，并留出家户价目表读取接口与优先级；无任何价格 ⇒ 该腿不折算、必须具名报告（不许静默付 0）。
- 资产/货币随迁时**按所有币种逐项搬**，不做货币兑换。

## 5. 接口与契约

### 5.1 迁移计划

```text
MigrationPlan {
  List<MigrationMove> moves;
}
MigrationMove {
  HouseholdId source;
  HouseholdId target;          // 已有家户（合并）或新家户 id（新建）；不得 == source
  HexCoord targetHex;
  ProductionModeId targetMode; // 目标家户的 mode；源户 mode 不变
  long transferSpeedPerMille;  // 本 move 采用的人均迁移速度（基线上限 10‰；A 规则触发时为 1000‰）
  long population;
  long moneyMilli;
  long debtMilli;
  String reason;               // PROFIT_WEIGHTED / A_RULE_MAX_SPEED / DISPLACED_ABSORBED
}
```

### 5.2 `ModeMigrationPolicy`

- 当前收益：源家户“当前所处 mode/hex”的 `profit.netPerLabor(currentMode, currentHex)`；若该户不在任何组织，按 `DISPLACED`（收益 0）处理。
- 目标收益：目标家户所处 mode/hex 的 `profit.netPerLabor(targetMode, targetHex)`；目标必须是一个**已存在且能合并的家户**或一个**满足资产/承载条件、可新建的家户**，不是“给源户换个 mode”。
- 目标权重：`weight = max(0, target.netPerLabor − current.netPerLabor)`。
- 基线迁移速度：`MIGRATION_PER_MILLE = 10`（1%/周期），具名 GM 可调；A 规则触发时改用 `A_RULE_TRANSFER_SPEED_PER_MILLE = 1000`（当周期把可迁人口按权重全部转出）。
- 目标排序：`weight` 降序 → 距离升序（同格 0，邻格 1）→ `(hex, mode)` 规范串升序。
- 目标可行条件：`Mode.canLiveIn(hex)`、hex 有该 mode 的资产/承载（合并看目标家户剩余容量；新建看 hex 剩余承载）、目标 mode 有匹配位置。
- A 规则触发条件：`expectedNet(current) < 0` 且 `cash + sellableInventory − nextCycleInputNeed < 0`。
  - 触发后：在全部可行目标里，选 `target.netPerLabor` 最高者；
  - 若该最高目标的 `netPerLabor` 严格高于当前 ⇒ 把本户 `transferSpeedPerMille` 设为 `A_RULE_TRANSFER_SPEED_PER_MILLE`，按权重把人口转出；
  - 若当前家户的 `netPerLabor` 已经不低于任何可行目标（转了只会更差或一样）⇒ **不发生转移**，继续生产，欠款/债务走现有资本化；这正是 A 规则下“负债继续加、阶层下落加快”的路径；
  - 若当前家户已经无法维持生产（无组织/资产/劳动）且不存在任何能承载的可行目标 ⇒ 目标选新建/已有 `DISPLACED` 家户，按 1000‰ 转出。
- `expectedNet`：用当前本地 ref 价和当前可行规模现算；现金/库存用账户会话与本地市场价；不得使用未实现的未来价格。
- **不产生“把源户 mode 改成 targetMode”的计划项**：`MigrationMove.targetMode` 只属于目标家户；源户的 mode/standing 在整个执行期间保持不变。

### 5.3 `ModeMigrationSettlement`

- 只做计划中已确定的 move；不重新算利润。
- **绝不原地改写源户 mode**：源户只减人口/货币/债务；目标 `ClassRow`（已有或新建）才带目标 mode 与目标 `ClassStanding`。源户的 `ClassStanding`、`ProductionOrganization.modeId`、`ProductionUnit.modeKey` 在本步一字不改；源户人口归零后整户移除，而不是被改造成目标 mode。
- 合并：目标 `ClassRow` 加人口/货币/债务；源行减同额；若目标 mode 已存在，不新建组织。
- 新建：在目标 hex 创建
  - 新 `HouseholdId` + `ClassRow`（人口/劳动按迁移人数重算）；
  - `ClassStanding{original=current=目标位置, reason=AUTO_MIGRATION...}`；
  - `ProductionOrganization` + `ProductionUnit`（复用目标 mode/industry 模板）；
  - 新 `LaborAllocation`/`LaborSupply` 与 `ProductionRelation`（工资/分成规则按目标 mode）；
  - `AssetShare` 必须来自该 hex 的闲置/租赁份额；不足则此目标不可行，计划里不得出现。
- 债务：对源户作为债务人的活跃合同，按迁移人口比例 `DebtContractBook.reduce`/`upsert` 到目标家户；逐笔 floor，余数留源户；源户人口归零则余数随最后一笔迁走；禁止删债（死亡删债是另一条既有路径）。
- 货币：搬源户**全部币种**余额，逐币种按人口比例 floor、余数留源户；不做 FX/兑换（D-023）；账户写回由现有 `AccountSession`/协调器完成。
- **资产随迁（D-023，本批必须实现）**：
  - 可移动资产（TOOL/SHIP/CATTLE 等）：按迁移人口比例拆源户份额；同 hex 同产业直接改 owner/operator 到目标户；跨 hex 时要求目标 hex 有同产业模板，在目标产业下按同 `AssetKind` 重建同量份额，源份额同量减少；
  - 不可移动资产（LAND/WORKSHOP）：不得跨 hex 传送；默认走“租赁/变卖/留原户并具名”；变卖按 D-023 估值折货币随人；没有价格源时不得拍脑袋折价，必须具名报告并保留/挂租赁债权；
  - 守恒：可移动资产 Σ数量 守恒；不可移动资产减少量必须对应货币/租赁债权增加，不许凭空消失；
  - 源户组织/目标户组织的 `assetSources` 必须同步到新份额 id，不得悬空。
- 消亡：源户人口为 0 且货币/债务为 0 ⇒ 从 `classes`/`classStandings` 移除；有残留 ⇒ 保留空壳并具名报错。
- 全部写在同一 `EconomySession`/revision 内。

### 5.4 `MerchantSettlement`

- **商号**：`ProductionOrganization{mode=merchant, organizer=merchant principal 家户, laborSources=porter 家户, assetSources=SHIP/CATTLE 份额, unitId=trade@hex}`。
- **承运选择**：`MarketSettlement` 内每条跨格路线，从 `merchantFirms` 中筛 `servesLane`、`capacityUsed < capacityPerRound` 的商号；按 `TransportTariff` 到货费率升序 → `organizationId` 升序选；扣本周期容量。
- **多承运商分摊**：一条 lane 的运量按服务商号队列分配（费率升序 → id 升序），每家吃满自己的 `capacityPerRound` 余量；总运力仍不足的部分才记 `freightUncollectedMilli`（D-023 第 8 项：先做，降低/消除不必要未收）。
- **收付**：买方 `CARRIER_FEE` 分别付给实际承运的 merchant principal 家户；无商号可承运时记 `freightUncollectedMilli`，钱不凭空消失。
- **商号结算**：每周期末
  - 收入 = `lastFeeEarned`；
  - 成本 = porter 工资（`FIXED_MONEY_WAGE`/`FIXED_IN_KIND_PER_LABOR` 实付）+ upkeep（tier districtUse × 100）+ 船畜维护；
  - 付不出 ⇒ 欠薪资本化；
  - `lastProfit = revenue − costPaid`；盈利 `capacityPerRound += 5`（上限 100000），亏损 `−5`（下限 5）；
  - 城市商号折扣、农村累积惩罚用 `MerchantPolicy` 参数；
  - 商人利润/亏损同样进入 `OrganizationProfitBook`，参与 A 规则与迁移。
- **流民适配**：porter 可以从 `DISPLACED` 家户招募；商号亏损到无可行候选 ⇒ principal 户转 `DISPLACED`，商号 `EXITING`，船畜按清算规则处置。

## 6. 版本、激活与重置

1. `EconomyMeta` 增加 `runtimeVersion`；当前新档写 `SEVEN_HEX_V1`（整数值，具名常量）。
2. 载入时任何以下情况 ⇒ 状态 `RESET_REQUIRED`，不激活、不迁移：
   - 无 `runtimeVersion`；
   - `runtimeVersion` 低于当前；
   - `classFirst` 非空且试图走 production runtime；
   - `modes`/`classStructures`/`industries`/`units` 结构不完整；
   - 载荷含已删除的旧键且无法由新 codec 读入。
3. 唯一恢复路径：GM 命令 `economy.Reset` 后按 `PRODUCTION_RUNTIME` 重新播种；不做任何旧档兼容分支。
4. 所有新组件（`merchantFirms`）缺省为空即可；但版本门保证旧档到不了“空表静默”路径。

## 7. 验收判据

### 7.1 7 hex 全链路（120 tick 冒烟 + 3650 tick 长程）

- 真实生产：7 格产业都关账，至少产出 GRAIN/CLOTH 两类。
- 真实市场：至少一笔跨格成交（`ShipmentBatch` 或 `MarketReport` 路线读数非空），`MarketTopology.regional() == true`。
- 真实利润：`OrganizationProfitBook` 至少一个 mode/hex 有非零 `net`，且 `revenue/cost/arrears` 来自真实 ledger。
- 迁移：发生合并、新建、消亡各至少一次；人口、货币守恒；债务 = 初始 + 流民新增 + 利息等既有路径，迁移本身不改变总量。
- 模式变化：高真实利润 mode 的人口份额从初始到 3650 tick 显著上升。
- **自然世界验收（D-023 第 3 项）**：另跑一个不额外种“事件户”的 7hex 世界，仅靠正常家户+真实利润读数，在 3650 tick 内自然出现迁移/合并/新建/消亡/流民吸收；参数标定（D-023 第 9 项）以该世界为准。
- **资产/币种随迁验收**：迁移前后可移动资产 `Σ数量` 守恒；不可移动资产减少量对应货币/租赁债权增加；源户/目标户组织的 `assetSources` 不悬空；所有币种余额按比例迁移且无 FX。
- 流民：进入过 `DISPLACED`，并被雇佣/迁出；`DISPLACED` 初期存在、终局不增长失控。
- A 规则：构造一个负利润且流动性耗尽、且存在更高收益目标家户的户，断言它以 1000‰ 速度把人口转出到目标 mode 家户（已有则合并、没有则新建），**源户 mode 逐字不变**、只缩编/消亡；若当前户自身已是最高收益，断言不发生转移且下一周期债务增加；源户人口清零前，`ClassStanding`/organization mode 不得出现目标 mode 值。
- 确定性：同输入两次运行终态逐字段相同。
- 无负人口/负货币/负债务/负资产。

### 7.2 商人专用

- 无商号/商号不足时：运费落 `freightUncollectedMilli`，不凭空消失。
- 有商号时：运费进 principal 家户、porter 拿到工资、upkeep 进成本；盈利/亏损正确改变 `capacityPerRound`。
- 城市/农村商人折扣与累积惩罚从 `MerchantPolicy` 参数生效。
- 商号 `capacityPerRound` 不得超过上限、不得为负。

### 7.3 旧档

- 任意旧档/缺版本档载入 ⇒ `RESET_REQUIRED`，不修改状态。
- GM `economy.Reset` 后新档可重新播种并跑通 120 tick。

## 8. 已知缺口与风险（实现前如实记录）

1. 7 hex 真实市场跨格路线需要 `MarketTopology` 显式节点与道路；当前 `MarketTopologyBook` 会从 map/social 取城市，7 hex 测试夹具要显式构造拓扑。
2. 旧 `EconomyTestWorld` 是 5 hex，需要扩 7 hex；资产/劳动/关系/account 的构造要沿用 `EconomyFixtures` 兼容位归一化，不能手写第二份。
3. 迁移目标“新建家户”需要可分配资产；本批只允许同 hex 的闲置/租赁份额，不做跨格资产调运。
4. 迁移率、权重归一化、合并/新建承载上限是标定参数，默认 10‰/周期；3650 tick 可能需要调，调参只改具名常量并留证据。
5. 商人层级门槛、折扣、upkeep、容量增减沿用 P6 `MerchantPolicy` 默认值；如需与探针 fixture 对齐，以 P9 对拍为准。
6. 死亡/出生/阶级下滑阈值仍走现有 `PopulationDynamics`/`EconomyLiquidationSettlement`；本批不重写人口学。
7. 流民户的“救济/军役/迁徙”GM 工具不在本批，只保留模型与读口。

## 9. 文件所有权（下一实现批）

允许实现 Agent 写：
- `simos-economy/src/main/java/io/mosire/simos/economy/model/MerchantFirm.java`（新增）
- `simos-economy/src/main/java/io/mosire/simos/economy/time/OrganizationProfitBook.java`（新增）
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationPolicy.java`（新增）
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java`（新增）
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MerchantSettlement.java`（新增）
- `simos-economy/src/main/java/io/mosire/simos/economy/model/DefaultProductionModes.java`（加 displaced）
- `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java`、`change/EconomyChangeSet.java`、`codec/EconomyCodec.java`（加第 31 组件）
- `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java`（merchantFirms 可选键）
- `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayStepper.java`、`EconomySettlement.java`（周期关账钩子）
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java`（真实承运商选择）
- `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`（旧档版本位/商人户/流民初始）
- GM 读口/工具（P7 扩展）另列。

禁止碰：`src/test/**`（测试 Agent 最后统一写）、既有探针与 fixture、无授权模块。

## 10. 默认参数表（实现直接使用，改则具名）

| 参数 | 默认值 | 位置 |
|---|---|---|
| 每周期最大迁出 | 10‰ | `ModeMigrationPolicy.MIGRATION_PER_MILLE` |
| 新家户承载 | 200 人/户 | `ModeMigrationSettlement.MAX_NEW_HOUSEHOLD_POPULATION` |
| 合并/新建优先级 | 现有同 mode 户合并优先 | 计划排序规则 |
| 商人 tier 门槛 | 由 `merchantFirms` 资产/容量派生 | `MerchantSettlement` |
| `serviceRadiusHex` | PORTER 2 / SELF_EMPLOYED 4 / BOSS 8 | `MerchantFirm` 构造默认 |
| 运力增减 | +5 / −5，上限 100000，下限 5 | `MerchantSettlement` |
| 农村累积惩罚 | +2/活跃轮，封顶 100 | `MerchantSettlement`/`MerchantPolicy` |
| 城市商人折扣 | `P6 MerchantPolicy.cityDiscountPerMille` | 复用 |
| A 规则触发 | 预期利润 < 0 且现金+可卖库存 < 下一周期投入 | `ModeMigrationPolicy` |
| A 规则转移速度 | 触发且存在更高收益目标 ⇒ 1000‰；否则 0（不转移，继续负债生产） | `ModeMigrationPolicy` |
| 迁移目标排序 | weight 降序 → 距离升序 → id 升序 | `ModeMigrationPolicy` |
| 偿还估值 | 家户价目表优先；缺省用市场区默认价目表 | `DebtValuation`（本批新增）/`Market.prices` |
| 币种迁移 | 所有币种逐项按比例，不做 FX | `ModeMigrationSettlement` |
| 多承运商 | 费率升序→id 升序，按容量分摊；余量才未收 | `MerchantSettlement` |

## 11. 版本历史

- v1（2026-10-06）：首版。用户已裁定旧档作废、A 规则、人口+债务随迁、流民适配；6 个 mode + displaced；商人承运/工资/运力模型。
- v2（2026-10-06）：用户澄清——**从不改变当前家户的生产方式**；转变方式只能用“创建/转移到新家户/其他家户”表示。原第 7 条“原地切模式”改为“提高转移速度”；A 规则只加快转出，源户 mode/standing 不变；仓库已有原地自动改 mode 路径必须删除或旁路。
- v3（2026-10-06）：按 D-023 修订——偿还“有啥付啥”不规定介质；估值家户价目表优先、否则市场区默认；不按 DebtUnit/币种分别核算；资产随人口迁移（可移动随迁、不可移动租赁/变卖）；全部币种余额随迁但不做 FX；流民没有工作、不得主动招募；优先做 3/5/6/7/8/9，4 不急。
- v4（2026-10-06）：按 D-024 修订——候选生产方式不再以“已有真实利润读数”为门槛；每户 × 每 mode × 每市场用配方 + 市场价 + **真实需求**算预期净收益/劳动；`OrganizationProfitBook` 退位为对账；城镇 seeder 按 `(residence, slot)` 产出 `handicraft_workshop`/`merchant`/`displaced`；播种 `trade` 产业、`merchantFirms`、`SHIP/CATTLE` 运力；运行时版本升 `seven-hex-v2`。完整设计见 `docs/superpowers/specs/2026-10-06-expected-profit-demand-and-mode-entry.md`。
