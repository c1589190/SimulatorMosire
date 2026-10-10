# 只读审计账本：本轮 55 个提交「实际做成了什么」

- 范围：`23e11653..f366f999`（本会话 55 提交），工作树干净、HEAD = `f366f999`。
- 纪律：全程只读（`grep` / `sed` / `git show` / 读文件）；未跑 Maven、未起世界、未改任何生产/测试文件。
- 唯一写入：本文件。
- 证据等级：① 代码在且接线 / ② 有单测 / ③ 有真实 world 实测 / ④ 只在计划文档里。

---

## 1. 关键调查结论（file:line 证据 → 结论）

| # | 证据 | 结论 |
|---|---|---|
| S-1 | `grep -rn --include=*.java SmugglingSplit` = **0**；`SEIZURE_BASELINE_MILLI` = **0**；`smuggl/走私` 仅 9 处注释 + 1 个新护栏测试常量（`HaulParallelMachineRetirementGuardTest.java:55-87`） | 走私档真删净 |
| S-2 | `MerchantFirm.java` / `MerchantSettlement.java` 均已不存在（`git diff --diff-filter=D`）；`merchantFirms` 非注释命中 = **1**，且在 `.superpowers/sdd/2026-10-09-debt-ref-table/probes/SelfProbe.java:301`（探针残留，不在 src） | `merchantFirms` 第 30 组件整体退役为真 |
| S-3 | `MarketRegulation.java:45` 现仅 `(anchor, tariffPerUnit)`；基线 `git show 23e11653:...:50-57` 有 `referencePrices/bidPerMille/askPerMille/quotaPerWindow/tariffPerUnit/open/rules` | 大删为真；`referencePrice` 残留 6 命中全是注释/派生读数（`MarketReadout:640`） |
| S-4 | `PortThrottle.java:26-34` 乘积算式；`MarketSettlement.java:4825-4834` `budgetKey = sellerZone→buyerZone#commodity` 共享预算 | 两侧节流 + 区对共享预算 = 真接线 |
| S-5 | `currencyGateBlock` `MarketSettlement.java:5971-5995`：`buy.regionId` EXIT + `sell.regionId` ENTRY，两侧 `<=0` 各自具名拒；`:5946-5947` 明文不读法定区 | 币种挂单过滤三角（币种×类型×方向）+ 两侧 + 不读法定区 = 真 |
| S-6 | `MarketSettlement.java:6655-6682` 逐 `MarketTaxBook.Charge` 铸钱腿 `buy.buyer.actor → charge.treasury()`，币 = `charge.currency()`；`MarketTaxLayer` = `PORT_EXIT/PORT_ENTRY/IN_ZONE_MARKET` | 出口/进口两层 = 真收款 |
| S-7 | `EconomySettlement.java:2225` 生产路径只传 `MarketRegulation.defaultsFor(markets)`（`MarketRegulation.java:65-73` ⇒ `tariffPerUnit` **恒空**）；全仓 `new MarketRegulation(` 仅测试夹具 `PortGateTaxAcceptanceTest.java:345`；spi 面 `grep tariff` = **0** | ★ **IN_ZONE_MARKET 第三层在生产路径结构性恒 0**、且**无写入口/无 GM 命令** |
| S-8 | `WorkingDayView.java:55`（唯一实现）+ `EconomySettlement.java:1261`（唯一构造点）；`grep "new WorkingDayView("` = **1** | B2 当日视图真接线 |
| S-9 | 视图两文件 `grep -E "save|checkpoint|persist|[Cc]odec|changeSet|[Rr]evision"` = 6 命中，**逐条落注释**（`EconomyDayView:38/43/44/45/56`、`WorkingDayView:52`）⇒ 代码面 0 | 性能红线（不逐日存盘）为真 |
| S-10 | `PopulationEconomyTimeParticipant.java:1059` `stepper.finish()` 恰 1 次/advance | 一次 advance 一次落盘 = 真（活路径） |
| S-11 | `liquidationPolicies` 在 `EconomyStateBuilder.java:88/307-311` 有工作副本字段，但全仓**无 `sheet().liquidationPolicies()` 调用**（`:309/427` 是 builder 自身）；写入口 `EconomyGmAdjustments.setLiquidationPolicy`（`:295`）走的是 revision 边界上的 `EconomyData.withLiquidationPolicies`，**不写 session 工作表** ⇒ 段内不可变 | 清算 planner 读 `base.liquidationPolicies()`（`EconomyLiquidationSettlement.java:1100/1111`）**不是路径效应**（与"段首 base 即当刻值"等价）；仅属"门面备而未用"的口径不一致，**不列为缺陷** |
| S-12 | `assetRules` 无 `OrBase` 门面（`EconomyStateBuilder` grep = 0），`classPositions` 亦然；二者是 GM 可改的静态模板（`EconomyGmAdjustments` 有写入口） | 这两处读 base 属可接受的"静态模板"口径（账本已具名） |
| S-13 | `A3 cb053f1a` 删掉 M-C 的**市场轮工具门槛**（`MerchantCapacityPool.java:381/903/1137`）；工具消耗改由 `EconomySeeder.HAUL_TOOL_PER_MILLE_OF_SERVICE = 100`（`:262`）经 `trade` 产业 `cycleInputPerUnit` 现扣 | ★ 宣称"M-C 跑商门槛（工具）"在 **HEAD 已不存在**；M-C 提交 `711a6e94` 后被同一批的 A3 撤回 |
| S-14 | `MerchantCapacityPool.java:41-42/679-688`：自报价簿 `CapacityQuote/CapacityQuoteBook` **已整族退役**；限价 = 派生承运成本 × 档 + 25‰ 具名上门费 | ★ 宣称"M-A2 运力自报价"在 **HEAD 已不存在**（A2 建、A3 撤） |
| S-15 | `grep pureMerchant` 全仓仅 4 个 main 文件（`MerchantIdentity` / `MerchantCapacityPool` / `MarketSettlement` / `EconomySettlement`），**test 树 0 命中**；`MarketSettlementFixtures.java:607` 自陈"本批用例不测免运费" | 纯商号免运费：①，无 ② |
| S-16 | `PrimaryModeRanking` 在 `ModeMigrationPolicy.java:521` `primaryFirst(...)` 真生效；`grep PrimaryMode` **test 树 0 命中** | 主业=排序表第 1 项：①，无 ② |
| S-17 | `HaulService.SERVICE_EXPIRED_ACCOUNT`（`:95`）在 `EconomySettlement.java:705/752/781` 真调用；`grep HAUL_SERVICE_` **test 树 0 命中** | A5 服务作废：①，无 ②（与 §7.14 自陈一致） |
| S-18 | `CARRIER_FEE` 铸腿点唯一：`MarketSettlement.java:6754` 三元二选一（`route.haulService() ? MARKET_TRADE : CARRIER_FEE`）；另 2 处在 `EnterpriseProfitBook.java:512/706` 只读归集 | "CARRIER_FEE 停铸（只读）"为真 |
| S-19 | `EconomyData.java:301-351` 组件数（去注释数）：`merchantFirms` 不在，现 38 个（`commodityFreightBaseMilli` 第 38） | 第 30 组件退役后编号整体下移，与注释一致 |
| S-20 | `EconomyOwnershipTimeParticipant.java:91` 实现 `TimeParticipant`，但全仓**无实例化点**（`grep` 只命中自身 `:91/:103`）；该提交早于本轮（`4edb2817`/`5b2f4ca4`） | ★ 既有孤儿类（非本轮引入），但它是第二个 `stepper.finish()` 的携带者 |

---

## 2. ★ 宣称做了但代码里没有 / 没接线

1. **三层税之第三层（区内市场税）在生产路径恒 0**：`EconomySettlement.java:2225` 只传 `MarketRegulation.defaultsFor(markets)`（`tariffPerUnit` 恒空），全仓无任何写 `tariffPerUnit` 的入口（spi/tools grep `tariff` = 0）。判据测试 `PortGateTaxAcceptanceTest.java:345` 用 `new MarketRegulation(...)` 手工构造 ⇒ 测的是"若给税率则收"，**不覆盖生产接线**。（G3 段 §7.6 已自陈"区内市场税无写入口 ⇒ 第三层恒 0"；P-T1b 只做到"有税率就真收"。）
2. **M-C 的两个产物在 HEAD 已不存在**：`711a6e94` 建的市场轮"跑商门槛（工具）"被同批 `cb053f1a`(A3) 删除；`M-A2 ef923d5a` 建的"运力自报价簿"被 A3 整族退役。`docs/.../2026-10-10-resume-points-and-backlog.md` §7.1 仍按"已落地"列这两行（见 §5）。
3. **纯商号自运免运费**：代码在（`MarketSettlement.java:1422/1486/6622/6823`），但**零测试**（test 树 `pureMerchant` 0 命中）。
4. **主业=排序表第 1 项**：代码在（`ModeMigrationPolicy.java:521`），但**零测试**（test 树 `PrimaryMode` 0 命中）。
5. **服务按周期作废（A5/A7）**：代码在（`EconomySettlement.java:705`），但**零测试**（test 树 `HAUL_SERVICE_` 0 命中）。
6. **B2 的 P-1/P-2/P-3 判据**：**只在文档/真实 world 证据里**，测试树无对应类（`grep -E "P-1|P-2|P-3"` test = 0）。证据等级 ③（B5 独立复验，dump md5），非 ②。

---

## 3. 本轮引入的孤儿 / 死代码

| 项 | 判据 | 处置 |
|---|---|---|
| `CapacityDemandBook` | 外部 1 个引用（`MarketSettlement.java:9661` 构造）+ `record`（`:6572/6590`）+ `logRoundSummary`（`:1684`） | 活（读数簿） |
| `LaneUnservedObservationBook` | 唯一外部引用 `MerchantCapacityPool.java:272-273/945`；类注自陈"只服务日志读数、不参与判据" | 活但**只写日志**（有意为之，非孤儿） |
| `serviceAbsentTally()` | 唯一读点 = 自己的 `logRoundSummary`（`:1330`） | 活（读数） |
| `EconomyDayView` 15 个方法 | 逐个 grep `.m()` 全 ≥ 12 命中 | **无孤儿方法** |
| `MarketTaxBook` / `MarketPayChoice` / `PortTaxInput` / `PortThrottle` / `ProcurementPriorityOrder` / `PrimaryModeRanking` / `MerchantIdentity` / `HaulService` / `HouseholdPurchasingPower` | 外部引用数 54/11/58/10/5/23/5/54/42 | 全部接线 |
| `EconomyOwnershipTimeParticipant` | 无实例化点 | ★ **既有孤儿**（早于本轮），但持第二个 `finish()` 调用（`：215`），若将来被注册会破"一次 advance 一次落盘" |
| `merchantFirms` 探针残留 | `.superpowers/sdd/2026-10-09-debt-ref-table/probes/SelfProbe.java:301` | 非 src，不编译；只是历史探针 |
| `MarketRegulation` javadoc | `referencePrices`/`quotaPerWindow` 仅存于类注"已删"说明 | 留痕，非死代码 |

**未发现**：新类零调用、只发不用的日志事件、只写不读的持久字段。

---

## 4. 未做的（对照 inventory §1/§2）

§1/§2 共 40+ 条，本轮（2026-10-10 夜）**补掉与口岸/商户/汇率/运输服务直接相关的那几条**，其余**至今未做**：

**已被本轮/近期批次补掉**（不再是待办）：
- §1.1 `economyHex.paymentInstrumentGap` 的**前置**（多币量纲）⇒ P-T4 币种列；
- §2.1"经济数值/验收债"里的**自承运读数**（M-A1 `recordFee`/`freightEarnedByHousehold`）、**`HexTradeCost` 货币侧**仍 0（未补，见下）；
- §1.2"政策命令面"仍待（`SetPortPolicy` 已存在但"谁设限制/审批链"未定）。

**至今仍未做（标题 + 一句话）**：
- §1.1 铸币生产方式 M1 —— 仍只有周期 `seignioragePerCycle`，`Industry` 无货币产出；
- §1.1 军俸 FlowRow/ledger 维度与读口 —— 军俸无 FlowRow 维度；
- §1.1 军俸与 GOV 俸禄共享国库预算优先级 —— 仍是顺序扣；
- §1.1 决策人受限军俸工具 + 审批白名单 —— 未做（P4c）；
- §1.1 家户文化/宗教效果 —— 家户无 culture/religion 字段；
- §1.1 非法仿制/伪币 —— 无成色/真伪/查获；
- §1.1 `economyHex` 三项指标 —— `productionSelfSufficiency`/`logisticsGap`/`paymentInstrumentGap` 仍不可用；
- §1.2 七项区划/城市收尾 —— `DeleteCity` 经济引用清理、`UpdateCity` 未知键、`capitalHex`、`createCityWithPopulation`、免税/期限实体、`DeleteNation` 级联、Region 互斥去重；
- §1.3 城防/工事/多阶段围攻、morale/supply/training、`DecisionDueRunner` —— 全未做；
- §1.4 四个读工具（`social.cities`/`sd.nations`/`sd.armies`/`command.submitBatch`）、D4.1 GUI 面板、日历季节钩子 —— 未做；
- §1.5 工具面下沉到模块、enforcer 回填（`util/map/unit` 仍不拦 economy*）、`RealLlmGovScenarioTest` 确定性、真世界 Social+Economy 长期联合校准 —— 未做；
- §2.1 家户结构修复剩余（出生=0 链、GM 社保读口、创世库存按 population 折算）、`HexTradeCost` 货币侧仍 0；
- §2.2 迁都人口随迁、区划下游重算编排、家户查询明细 —— 未做；
- §2.3 unit 级兵力拆/并原语、`SdCommandDrain` 接回 advance、装备类型化/目录 —— 未做。

---

## 5. 口径可信度：文档与代码不符处（点名）

| 文档 | 位置 | 文档说 | 代码实际 |
|---|---|---|---|
| `status/2026-10-10-resume-points-and-backlog.md` | §4.1 表 `:114` | 口岸 P-T1「两侧节流 + 过境税 + 删死代码」= **待做** | `8b728a82`/`4b753495`/`28a04d49` 已落地，同文件 §7.1 自己列了这三条 |
| 同上 | §4.1 表 `:117` | P-T4 读口币种列 = **待做** | `4d116367` 已落地；§7.1 #1 自己也列了 |
| 同上 | §4.1 表 `:119` | P-T5 民间 FX 簿 = **待做** | `1827dbce`/`78bcb208` 已落地 |
| 同上 | §4.1 表 `:118` | 3c 订单可选币 = **待用户裁定是否立项** | `07edabfe` 已落地 |
| 同上 | §4.1 表 `:120-124` | 商户 M1–M5 全 **待做** | M-A1/M-A2/M-C/M-D 四条已落地（A3 后又撤了两条） |
| 同上 | §7.1 表 `#9` | "运力自报价" = 已落地 | A3 已整族退役 `CapacityQuote/CapacityQuoteBook` |
| 同上 | §7.1 表 `#10` | "跑商门槛（tool 消耗）" = 已落地 | A3 已删市场轮工具门槛，改走产业周期投入 |
| 同上 | §7.2 | T 批门禁 **3385** 条 | 同文件 §7.11/§7.14 记 **3400 / 3416**（T 批自身提交信息亦记 3416）⇒ 3385 应是 T-fix 前的重跑，**行文未标时点** |
| `plans/2026-10-10-all-modules-target-completion-plan.md` | §5.5 `:151` | 性能红线达标 | **为真**（见 S-9/S-10），但 `§5.5` 未记录 `EconomyOwnershipTimeParticipant` 这第二个 `finish()` 载体（它未被注册，故不破） |

★ 两条文档里"计划/台账措辞 ≠ 代码"的老毛病（AGENTS §四）在本轮**再次出现**，且都落在 `.md` 未随代码更新上。

---

## 6. 我没核到的（如实）

1. **未跑任何 Maven / 未跑测试**：`3385 / 3400 / 3416` 三个门禁数字与 `SpotBugs 0 / 前端 412` **全部是读文档**，我一条 surefire 报告都没打开（无 `target/` 可读，且纪律禁止跑 Maven）。
2. **未起世界**：B2 的 P-1/P-2/P-3 dump md5、three-powers 多区回归消除、默认世界"零扰动"数字，均**只读提交信息/计划 §5.5**，未复核 jar 与 store。
3. **未核 `PortRegimeBridge` / `ProcurementPriorityBridge` 的数值口径**：只确认"有装配点、入参形状齐全、接到了参与者"，未逐算式复算。
4. **未核 `HaulService.serviceDemandMilli` / `unitFreightMilli` 与既有 `freightUnitMilli` 的逐值重合**（文档称在 `服务牌价 = 1.025 毫` 处重合）—— 只读算式注释，未代入数验证。
5. **未核 `EconomySettlement` 里 19 处逐日读点是否"每一处都真的换了 dayView"**：只做了聚合 grep（`EconomyData base` 在 time 包内 26 处）与抽样（`Liquidation` 一处漏迁）。**完整清单未逐条走**（`b2-per-day-view/impl-ledger.md` 未逐条回核）。
6. **未核 `liquidationPolicies` 工作副本是否真被写入**：`EconomyStateBuilder.liquidationPolicies()` 存在，但谁调用它、GM 命令是否在 advance 中生效，未追。
7. **未核 A6/A7 真实 world 的"逃逸货"结论（`hh-0_0-urban-rich_peasant` 480/480）**：读文档。
8. **未核 `MarketRegulationTest.java` 被删（`5758962d`，-368 行）后铁律 5 的往返覆盖是否仍够**：只确认 `EconomyRoundTripTest` 被改动 +88/-。
9. **未核 economy 模块 53 个测试类里是否有因删类而未跟进的文件**（我只确认 `simos-economy/src/test` 存在、关键 6 类齐全）。
10. **未核 `git commit 作者/提交日期`**：`f366f999` 提交日期 2026-10-10，而仓库内 inventory 文档名为 2026-10-23 ⇒ 元数据口径不自洽，我按"代码为准"处理，未追因。
