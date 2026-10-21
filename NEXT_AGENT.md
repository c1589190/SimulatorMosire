# 下一个 Agent 的入口

> **2026-10-21 商队家户 + 运输生产方式 + 市场轮运力约束（最新 v2.2；覆盖下方旧运输/阶级口径）**：
> 设计：`docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md`；
> 开发计划：`docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md`。
> 用户已确认：运输生产每 tick 组织；临时运力按 marketRoundId 累积，市场轮统一挂出，上一市场轮不可保留，
> 市场轮末未售整批作废；每市场区单一运力锚价（初始 0）；**单 hex 运力只服务本 hex，禁止跨 hex 调拨**；
> **统一价格下不同 hex 因本地供需各自反应，允许区内总量满足但局部短缺/闲置并存**；
> 阶级只分 OWNER/PROLETARIAN，生产方式不绑定阶级，有产者可受雇；合并 key 去掉 productionMode；
> 向上买地/买马跃迁；混合买入按吸收能力拆分人口；向下 HouseholdFlowBook 只迁人口、净身出户，
> 不搬库存/债/资产/经验；**运输马匹本批不折旧，未来走生产方式损耗配置**；
> **运输能力产出全归 operator OWNER，hired_driver 只领工资**；
> **工资第一次只付货币，后续普通工资债按通用顺序 货币→可清偿库存（含运输马匹）→LAND 清偿；
> 删除“PROLETARIAN 债权人跳过 LAND”旧例外。债权人取得生产资料后就地派生为 OWNER；
> S7-M2 working copy 允许暂态“PROLETARIAN 持生产资料”，最终同一 revision 按资产重派生 class，
> 下一 S2 按 `(homeHex,cultureKey,ownershipClass)` 标准合并；异 hex LAND 不强制迁人。
> 本批不新增非 LAND `OwnershipStake` 的债务转移腿。**
> 旧 haul 库存、CARRIER_FEE、ClassSlide、生产不赚钱的阶级流动退役。
> **用户已授权控制方按此前思路补齐工程裁定；当前没有待用户玩法裁定。**
> **C1–C10 已完成；最终门禁与 360 真实世界行为验收均 PASS（2026-10-22）。**
> 任务书 `...-c1-taskbook.md` … `...-c10-taskbook.md`；账本 `.superpowers/sdd/...`。
> 最终：
> - `tools/mvn-lock.sh -q test -pl simos-app -am` **rc=0**（app 1023/0/0/7）；
> - `verify` **rc=0**（`evidence/verify-final8.*`）；
> - `tools/gate.sh full` **PASS rc=0**：全仓 surefire **3669/0/0**（494 报告），前端 **412/412**，
>   shaded jar **29,145,215 B**（`evidence/gate-full-post-c9labor.*`）；
> - C9 caravan labor 热修后 360 `rural-32hex-360d` **rc=0**：32 个 hex 均有真实运输劳动
>   （actual 8360 毫/日），day360 anchor `supplyWorkMilli=1,344,000`、`demandWorkMilli=164,958`、
>   `committed=49,605`；72 轮每轮 clear 32 个运输批次；72 个市场行中 59 行有非零 `immediateCrossHex`，
>   7 行有 silver 运费。剩余 `LOGISTICS_CAPACITY` 是局部/相邻 lane 容量不足，不是总供给 0。
> - 历史根因与预修复读数见 C9/C10 账本；已确认跨格需求存在（C7b1 `demandWorkMilli=164916`）。
> 用户已授权在当前分支提交并推送（2026-10-22）；其余未做项见 C10 账本 §7。
> 不要碰主树；提交只在本工作树 `parallel/side-module`。
>
> **2026-10-21 土地三档组件树 + Map owner 权威重构（历史状态；覆盖下方 RC8 的土地口径）**：
> 计划：`docs/superpowers/plans/2026-10-21-d032-land-component-refactor-plan.md`。
> 已完成 B1 Map 三档组件树、B2 arable 总量切源、B3 arable supply 瞬态化、B4 Map owner 权威。
> Map 现在是 `HexLand → LandTypeArea → LandComponent(id, quantity, attributes)`；
> `owner` 是 component 上的 STRING 属性，Map 是 LAND 所有权唯一持久权威；
> EconomyData 已删除持久 `landArableSupply` / `landHoldings`，`LandHolding` 只作瞬态聚合视图；
> app 两个时间参与者从 Map 构造 ledger，轮末写回 Map 并在同一 revision 提交 MapChangeSet。
> 测试 owner T4 已迁移测试/夹具：全仓 surefire **3671 / 0 / 0 / 7 skipped**，前端 **412/412**；
> `tools/mvn-lock.sh -q -DskipTests verify -pl simos-app -am` rc=0；32hex 360 **OK=360 / REJECTED=0**，
> `landTotalMilliMu` 恒 **99,200,000**，无 land-writeback/owner-missing/ledger-invalid 日志。
> `tools/gate.sh full` 未完整跑完（工具 300s 上限在 app SpotBugs 阶段中断；其全仓测试、前端、verify 已分段绿，
> 不把分段绿写成 full gate 通过）。变异自证 4 项（merge/缺 owner/seed 总量/Map 写回）已做。
> **仍剩余**：B5 progress/crop/fertility 的组件级生产语义未做；v4 §8 同阶级整组织交易未做；
> 父 v2 的 B_day/B_cycle/单 hex 农业聚合仍未接；工作树未 commit/push，仍有大量前序未提交改动。
>
> **2026-10-21 RC8 修复完成（历史状态；覆盖下方 T1 旧记录）**：
> 计划：`docs/superpowers/plans/2026-10-20-d032-v4-full-repair-plan.md`；
> RC8a transport contract、RC8b actual labor、RC8c DailyDemandBill/actual surplus、RC8d/e S7-B/S7-C、
> RC8e enterprise-roster sync 已完成 compile；T2 测试迁移完成。
> `tools/gate.sh full` **PASS**：全仓 surefire **3647 / 0 / 0**，前端 **412/412**，
> shaded jar **28,993,955 B**，validate rc=0（300s）。
> 最终 32hex 360：**OK=360 / REJECTED=0**；`HAUL_SERVICE_DELIVERY_FAULT=0`、
> `POST_LAND_CLASS_CHECK_FAILED=0`、`MARKET_SELF_MATCH_SKIPPED=0`；
> harvest grain 三期均 **3,065,856,000**；final population **7287**；final debt **1612**；
> csv sha256 `0ce99115…`，md sha256 `60b1243f…`。
> **仍剩余**：v4 §8 同阶级整组织交易未做；父 v2 的 `B_day/B_cycle`/单 hex 聚合/`selfLand/hiredLand`
> 持久视图仍用现有 production runtime 模型（见修复计划 §9）；#7 土地价格/供给侧未直接修；
> 工作树未 commit/push，仍有大量前序未提交改动。
>
> **2026-10-20 v4 设计入口**：二元农村生产关系 v4 =
> `docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md`；
> 可行性/派单顺序 =
> `docs/superpowers/plans/2026-10-20-d032-two-position-rural-class-mobility-dispatch-plan.md`。
> 结论：R1–R6 核心没有新的用户级玩法裁定；v2 S3–S8 日序硬前置已由 **RC0a** 完成
> （`S3→S4→S5→S6→S7-M2→S7-A`，同日工资债 id cutoff，compile rc=0）。完整 v2
> `DailyDemandBill`/实际盈余卖盘仍是独立 v2 收尾项，不阻塞 R1–R6。
> **R1–R6 生产代码主链已完成到 compile 绿**（RC0a、RC1、RC2a/b、RC3、RC4-A/B/C/D、
> RC5、RC6、RC7；均未跑 test/test-compile/verify/gate/360）：
> class nature / culture label / culture-key lookup；32hex 农村 160/40 有地/无地；
> seeder class nature；S3/M6 门控；删除 `MAX_HOUSEHOLD_POPULATION`；HouseholdTransferSettlement；
> 债务 LANDLESS 债权人过滤；同 key 合户（含 S3 前置 App Social 桥/投影刷新）；
> S7-B 无地买地跃迁；S7-C 37th flow table。
>
> **T1 已完成（2026-10-20）**：测试/fixture 迁移、36→37 组件 roundtrip、32hex 1L+1LL fixture、
> R1 merge / S7-B / S7-C / RC5 正负例全部落地；`tools/gate.sh full` **PASS**
> （**3633 tests / 0 failures / 0 errors / 7 skipped**，前端 412/412，SpotBugs 18 模块全 0，guards 7/7，304s）；
> 独立 32hex 360 tick **OK=360 / REJECTED=0 / ADVANCE_EXCEPTION=0**
> （csv sha256 `b0f08698…`，md sha256 `49ed7b98…`，140.4s）。
> 四类关键变异自证 9/9 有判别力，隔离副本执行，原工作树 main 未改、md5 复原一致。
> 证据：`.superpowers/sdd/2026-10-20-d032-t1-test-owner/evidence/{gate-full-4,multitick-360-3,mutations}`（本机留痕）。
>
> **当前剩余**：工作树仍有大量未提交改动；未 commit/push。下一步由用户决定是否提交/
> 同步主树；§8 同阶级整组织交易仍未派单。
> 下方“当前进度（2026-10-19 复核后）”保留为历史；F5 债务分源、信用边界、`production.RebindInstanceOperator`
> 与完整 v2 S3/S4/S7 仍待后续。

先在这个 worktree 工作：

```text
/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
```

不要碰主树 `/home/cna/SimulatorMosire`。

必读顺序：

1. `AGENTS.md` 与 `docs/agents/00`–`15` **全部读完**（不要只看目录/摘要）
2. **最新标准小农业架构设计（修复 Agent 唯一入口，内含 S0–S8 目标运行序列）**：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md`；并加载 `.agents/skills/target-runtime-design/SKILL.md`
2b. **用户原话/裁定证据**：`docs/superpowers/specs/2026-10-19-d032-rural-production-land-labor-revision.md`
2c. **2026-10-19 复核收口与工程默认 E1–E15**：`docs/superpowers/reports/2026-10-19-d032-standard-agriculture-v2-review-closeout.md`
3. **当前责任区任务书**：按 `docs/superpowers/plans/2026-10-19-d032-ra1*-*.md` 选择；RA1a/RA1b1 已完成，下一步是 RA1b2 的约束设计书
4. 旧目标设计/旧 tick contract/master dispatch/P2–P4 约束：只在**不与 v2 冲突**时作为历史工程细节；禁止照抄 block 土地、两遍锁、市场先消费、工资同 tick 还款等旧口径
5. `docs/superpowers/status/2026-10-18-d032-rural-land-labor-debt-redesign-handoff.md`（旧状态，需回代码核）
6. `docs/superpowers/specs/2026-10-18-d032-economy-tick-redesign.md`（以 §14–§45 的后续修正为准，冲突处以 v2 为准）
7. `docs/superpowers/reports/2026-10-18-d032-original-intent-audit.md`
8. `docs/superpowers/specs/2026-10-02-architecture-design-source.md` 的 **D-032 U1–U23 全文**
9. `docs/superpowers/plans/2026-10-18-d032-rural-land-labor-debt-p0-p6-plan.md` §1.1 的“未读清单”——涉及旧 P2/P3/P4 背景时按表补读

## 当前进度（2026-10-19 复核后）

- 设计入口已换为 v2；复核报告已把 S4–S8 的工资时点、5 tick 账单、同 tick 工资债 cutoff、S7 购买不回溯本 tick 温饱、实际播种土地进入 `u/a` 等收口为 E1–E15。
- **RA1a 已完成**：`simos-economy-api/.../api/land/` 与 `simos-economy/.../land/LandBook.java`，标量 `(owner, hex, quantityMilliMu)`、无 block id、任意数量转移、纯账本守恒；编译 `rc=0`。任务书：`docs/superpowers/plans/2026-10-19-d032-ra1a-scalar-land-foundation-taskbook.md`。
- **RA1b1 已完成**：`EconomyData`/ChangeSet/StateBuilder/Codec 已接 `landArableSupply` + `landHoldings` 两持久组件；组件数 34→36；默认空，旧 `assetShares` LAND 路径未改；编译 `rc=0`、Spotless `rc=0`。任务书：`docs/superpowers/plans/2026-10-19-d032-ra1b1-land-state-components-taskbook.md`。
- **下一步 = RA1b2**：为旧 `assetShares` LAND → 标量 `landHoldings` 的数据迁移/读写面写约束设计书（Seeder、清区、债务、市场、生产、读口、旧 LAND 行删除），然后才能派实现。RA1b1 尚未运行测试/verify，组件数变化和构造参数变化留给统一测试 owner。
- 当前工作树仍有上一段 M7fix/SURVfix/P6a2 与本轮 docs/land 的**未提交改动**；不要覆盖、不要接手别人的未提交文件，不擅自 commit/push。
- 全仓门禁与 360 tick 重跑均未在本轮执行；360 tick 旧表是现象数据，不要自行解释。

特别注意：设计明确**没有抵押、担保、特定资产优先受偿**；不要恢复 `Pledge` 作为农村债务基础。土地在债务清算中按标量数量、当前市场区价格处理；0 价市场成交合法但债务清算不自动冲本金。工资第一次只付货币，未付成 0‰ 普通家户货币债；工资每 tick 按实际劳动结算，本 tick 新工资债不进入本 tick S7-M2；还债阶段允许库存。
