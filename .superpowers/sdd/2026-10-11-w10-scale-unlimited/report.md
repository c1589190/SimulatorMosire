# W10 交付报告（取消产业规模上限 + GM 政策写口 + A1 竞态修复）

jar `1cfc10c1e2b6f462a6c9d1940376b145`；装置 = W6 标定批 `w6run.py` 逐字副本（`--out/--dump-dir/--store/--gui-port` 落本批目录）；
运行清单 `results/*.result.json`；端口 10931-10933（首轮）/10941-10943（成功轮）、tp 10951；**收工 pgrep 无残留 java**。

## 1 改动文件清单
**① 规模无上限（25）**：`ProductionProcessBook`（唯一拼写点重写）、`SettlementIndex`（删死规模索引）、`EconomySettlement`、
`ProductionEfficiencyBook`、`LaborQueueBook`、`WorkingDayView`、`Industry`、`ProductionRecipe`、`MarketSettlement`、
`OperatorSettlement`、`ExpectedProfitBook`、`DebtCapacityBook`、`MarketDemandBook`、`ProducerCostBook`、`EconomyEnterpriseSettlement`、
`ModeMigrationPolicy`、`ModeMigrationSettlement`、`GovernmentServiceDesertionBridge`(app)。
**② 政策写口（9）**：新增 `model/EconomyPolicyKey`、`spi/EconomySetEconomyPolicyHandler`、
`app/tools/write/EconomySetEconomyPolicyTool`；改 `EconomyMeta`、`EconomyData`、`EconomyDayView`、`ClassSlideSettlement`、
`MintSettlement`、`Shell`、`SimosToolSource`、`CatalogTool`。
**③ A1（3）**：`CoreSimos`（创世闩 + `awaitGenesisReady`）、`QueryService`（等就绪 + 具名未就绪）、`GuiServer`（503 + `GUI_WORLD_NOT_READY`）、
新增 `app/query/WorldNotReadyException`。

## 2 真命令与 rc
- `tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am` → **rc=0**（全 reactor 主代码绿）
- `tools/mvn-lock.sh -DskipTests package -pl simos-app -am` → **rc=1**（`testCompile` 红：旧测试引用已删 API）
- `tools/mvn-lock.sh -q -Dmaven.test.skip=true package -pl simos-app -am` → **rc=0**（前置 `spotless:apply` 已跑）
- 世界：`w6run.py` sw ×3 + tp ×1（0→360，`--log-level DEBUG --trace-level TRACE`）

## 3 逐件自证（★ §3 真世界读数）
### ① 规模无上限
- 现状→改法：见账本 §② 。`ProductionProcessBook` 的 `capacityScaleOf` 五重载全删，改为
  `derivedScaleOf = min(劳动路, 投入路)` + `UNBOUNDED_SCALE` 哨兵；门票路（组织租佃天花板 / 迁移预留 / 执行期租佃否决）拆掉或降级为非阻塞。
- **判据 ①（advance 0→360 HTTP 200 + day 360 + 异常 0）**：**sw 三次全部通过**（tick=360，`GUI_ACCESS … status=200`，
  `ERROR` 行 **0/0/0**，`revision=2`，耗时 15.5/14.6/13.3 s）。**tp 未通过**：`HTTP 400`
  `OwnershipStakeBook 的 Σ活跃质押必须 ≤ 份额 quantity：share-farm@0_0-LAND-…-hh-1_0-rural-poor_peasant… 质押合计=6959，终态数量=6235`
  （同一根因族：**还有第三个 mover 会拆走 ACTIVE 质押份额**；sw 撞的是组织租佃/迁移租佃/迁移拆分三条，tp 撞的是另一条路径）。
- **判据 ②（`MERCHANT_TRADE_INDUSTRY_CREATED` 由 0 变 > 0）**：**未达成 = 0**（sw 全程 0 行，`grep -c MERCHANT` = 0）。
  **归因（有据）**：门票拆掉后并非"卡在门票"，而是**劳动配额先崩**：d360 dump 里 `allocations` 全表只剩 **15 条**
  （13 PRODUCTION + 2 GOV_SERVICE，Σlabor=102,377），78 个 farm unit 里绝大多数**承诺劳动 = 0** ⇒ 劳动路规模 = 0 ⇒
  无产出 ⇒ 也无"按需建商号"的需求。**下一道闸 = 劳动吸收上限设计**：本批把 `maxAbsorbable` 改成"投入路 × laborPerUnit"，
  而投入路的取料计划又由**昨天的劳动**派生 ⇒ 一个 unit 一旦失去配额就**永久停在 0**（自锁）。修法候选（下一批）：
  ① `inputScale == 0` 且该 unit 在产时，吸收上限回落到"家户预算"（不做 0 夹）；或 ② 取料计划加"劳动地板"（周期首日按上一周期的
  劳动配额而非当日 0 算）。
- **判据 ③（市场零成交轮次 = 0，0→240 窗口）**：**未达成**：sw **16/96 轮** `tradedMilli=0`（改前 W6 读数 = 0/240）。
  与 ② 同源（劳动崩 ⇒ 无货可卖）。
- **判据 ④ 农业规模读数**：解析式 = 改前 `⌊LAND 份额 ÷ 1000⌋`、改后 `min(⌊承诺劳动 ÷ 143⌋, ⌊已投粮 ÷ 8000⌋)`；
  d360 dump 逐值：78 个 farm unit 的 LAND 份额多为 3,100,000 毫亩 ⇒ **改前规模 3100/unit（中位 3100，合计 225,224）**；
  **改后**：`allocations` 里 farm 的承诺劳动读到 0（提取脚本对该表的形状假设见账本，**该读数存疑，标未验证**）⇒
  按公式改后规模 = 0，与"劳动崩"一致。
### ② GM 政策写口
- 形态：`EconomyMeta` 新增第 6 组件 `Map<EconomyPolicyKey, Long> economyPolicies`（缺键 = 具名缺省 = 现值；`withPolicy` 写缺省值即删行），
  9 个键；GM-only 命令 `economy.SetEconomyPolicy` + handler + 窄工具 `simos.economy.setEconomyPolicy` + catalog 载荷提示。
  **铁律 5**：不新增组件 ⇒ 形状零改动；`EconomyChangeSet` 的 `meta` 单键表投影 + Jackson record 绑定**自动**覆盖新字段
  （`apply(between(base,target),base)` 逐字段重建）。★ **往返测试未写**（§5 禁改 `src/test/**`）⇒ 本批**不受铁律 5 的往返守卫保护**，
  风险具名：政策表是 `Map<Enum,Long>` 的新字段，**唯一未自证的就是它的往返**；后续测试批必须补
  `EconomyChangeSetRoundTrip`（含"写缺省 ⇒ 删行 ⇒ 往返为空表"一例）。
- 读取点已切换（4 条）：`ClassSlideSettlement`（斜率/每级成本/轮天数，逐轮现读）、`MintSettlement`（`moneyOutputPerUnit` 的**覆盖**：
  仅在政策键**被显式配置**时生效 ⇒ 没被写过的世界逐值不变）。
- **未切换（写了不生效，如实报）**：`LOAN_BASE_RATE_PER_MILLE`、`MIGRATION_SPEED_CAP_PER_MILLE`、`PROFIT_GAP_SPEED_SLOPE_PER_MILLE`、
  `FAMINE_MAX_PPM_PER_TICK`（social 边界）、`MINT_LABOR_SHARE_PER_MILLE`（只在创世消费）。见账本 §④.2/④.3。
### ③ A1 启动竞态
- 现状：GUI 在创世 checkpoint 落盘前应答 ⇒ `GUI_REQUEST_FAILED` + 500 + 14 行栈（1/3 命中）。
- 改法："等就绪"（创世闩）+ 超时具名 503。
- **自证**：修后 sw **连续 3 次**运行，`grep GUI_REQUEST_FAILED` = **0/0/0**、`GUI_WORLD_NOT_READY` = 0/0/0；
  首帧 `GET /api/economy/overview` **status=200**（millis=347/552）。日志归一化（去掉按构造不同的端口/UUID/请求耗时）后
  **三次逐字节相同**：md5 `11985c623f70d0d6923005cdd678a0e2` ×3。★ 让步：三次用了三个不同端口 ⇒ "完全逐字节相同"是用归一化证明的。

## 4 会改变数值行为的清单
1. 规模来源整体改变（资产路 → 劳动∩投入路）：所有产业的规模/产出/投入需求/劳动需求/租佃分摊/债务容量/需求预测全随之变。
2. `plannedPerMille`（缩产/停业）从"乘在资产路上"改为"乘在两路 min 之后"（ACTIVE 逐值不变；STOPPED 仍为 0）。
3. 组织企业不再受"资产天花板/租佃可行性"限制 ⇒ 新建组织数量与规模改变。
4. 迁移：新建目标不再需要作业资本；**ACTIVE 质押份额不参与租佃/拆分**（留原户 + 具名 residual）。
5. `LaborQueueBook.offer` 的 `NO_CAPACITY` 拒因改名 `NO_ABSORBABLE_SCALE`；吸收上限改由投入路定。
6. GM 政策写入后，滑落三键下一市场轮生效、铸币产出率下一关账日生效（**默认值 = 现值** ⇒ 未写入的世界逐值不变）。
7. A1：创世窗口内的读请求从 500 变为"阻塞至就绪后 200"（超时才 503）。

## 5 硬编码字面量（新增/改动）
`UNBOUNDED_SCALE = Long.MAX_VALUE`；`QueryService.GENESIS_READY_WAIT_MILLIS = 30_000`；
`EconomyPolicyKey` 的 9 组 `defaultValue/min/max`（1000 / 100 / 1000 / 5 / 500 / 30 / 20 / 100 / 100 = 现值）；
`ClassSlideSettlement.SLIDE_MAX_SPEED_PER_MILLE = 1000`（未动）。

## 6 会让既有测试失效的清单（只报不改）
- `simos-economy`：`ProductionEfficiencyBookTest`（`evaluateHarvest` 少一个参数，13 处）、`ExpectedProfitBookEmployerOfTest:237`
  （`capacityScaleOf` 已删）、以及所有引用 `ProductionProcessBook.capacityScaleOf/plannedCapacityScaleOf`、
  `SettlementIndex.capacityScaleOf`、`EconomyEnterpriseSettlement` 租佃天花板、`ProducerCostBook.estimate(…shares…)` 的测试类。
- `simos-app`：引用 `ShellMain.seedGenesisIfEmpty` / GUI 500 语义 / `EconomyMeta` 5 参构造（已保留兼容构造器 ⇒ 这一类**不会**失效）。
- 精确清单需一轮 `testCompile`（本批 `-DskipTests package` 的报错即清单，见 `results/` 与账本 §④.1）。

## 7 与 v3.34 / 用户原话不一致处
1. v3.34 说"本批用劳动口径"⇒ 我实现成 **min(劳动路, 投入路)**（投入是用户原话里的那一路，且不纳入会自锁）——**扩了**半路，理由记在账本 §③.3。
2. 用户"所有产业无上限"⇒ 对农场成立（LAND 锚不再进任何规模算式）；但**没有**把劳动吸收上限放到无穷（改由投入派生）：这是我对
   "不许半吊子"的取舍（资产路确已全删），**若控制方认为必须连劳动吸收也无穷，请裁定**（那会把家户预算变成唯一上限，风险=赢家通吃）。
3. ② 的宿主用 `EconomyMeta` 而非新组件（理由见账本 §③.6）；5 条读取点未切（账本 §④.3）。

## 8 未完成 / 未验证（BLOCKED）
- **BLOCKED-①**：three-powers 0→360 **HTTP 400**（ACTIVE 质押被第三个 mover 拆走）。最小复现：`w6run.py … --world three-powers --plan plan-tp360.json`（结果见 `results/w10-tp-r1.result.json`）。判据①②③在 tp 上**未测**。
- **BLOCKED-②**：判据②（`MERCHANT_TRADE_INDUSTRY_CREATED > 0`）= 0、判据③（零成交轮 = 0）= **16/96**（sw 0→240）⇒ 未达 §3 判据，
  归因与下一道闸见 §3① 。**按 §3 纪律停止重试**。
- **未验证**：铁律 5 往返（无测试）；"日志逐字节相同"用归一化证明（端口/UUID/耗时按构造不同）；
  债/清算路径与 `isIdleShare` 的质押口径未逐处审计（只改了三个 mover）；`MINT_LABOR_SHARE_PER_MILLE` 与 famine 的运行期生效未做；
  性能未单测（sw 0→360 = 13.3–15.5 s，与 W6 基线的 9.0–14.2 s 同带，**未做 A/B**）。
