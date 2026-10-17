# D-032 D4 资产与占用完整设计（2026-10-17）

> **性质**：D4 的**约束设计书**。只冻结“世界必须长成什么样”，不写生产代码、不写测试、不跑 Maven，不提交、不推送、不触碰主树。实施由后续 D4-I（唯一写代码 owner，只编译、不测试）和 D4-T（独立测试 owner）分两批完成。**历史落盘声明**：初版 D4-D0 未改 D1/D2/D3/R2/status/decision/precheck/adjudication；2026-10-17 复核修正轮按 R-01..R-18 另行局部修改了 D1/D2/D3、D3 裁决文件、重规划与 status（仍仅设计/计划/状态文档，见 §12.1）。
>
> **工作树基线**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`，开工 HEAD `b6f694dd`，开工 `git status --porcelain=v1` 为空。本文只新增本文件。
>
> **权威输入**：`AGENTS.md`；`docs/agents/02-一-并发.md` §一.8、§一.8.1、§一.10、§一.12；`2026-10-17-d032-wage-idle-barter-decision.md`；`2026-10-17-d032-wage-chain-design.md`（D3 §2.11、§6.9、B3/B4）；`2026-10-17-d032-r2-shared-asset-minimal-design.md`；`2026-10-17-d032-final-independent-review.md` R2；`2026-10-17-d032-d3-integration-precheck.md` §5；`2026-10-17-d032-d3-precheck-adjudication.md` B3/B4；以及本文件 §2 逐条回核的当前源码。
>
> **效力与边界**：本文中的“**用户已定**”只逐字来自用户原话；“**工程默认**”均是控制方为落实用户口径而冻结的接口/分配/失败语义，不是新玩法裁定，也不是用户已经选过参数。D4 不重开独立土地账，不把再生产权益变成活动库存，不新增工资、价格、利率、折价或土地制度玩法。
>
> **2026-10-17 D1–D4 独立复核局部修正注（R-01/R-05/R-08/R-10/R-15/R-16，当前有效）**：本册按 `2026-10-17-d032-d1-d4-design-review.md` 修正：`AssetKind` 只用 `SMALL_TOOL`/`LARGE_TOOL`，二者分别定义 horizon，`TOOL` 输入具名拒；W3 在 lookup/支付前按 payer 聚合，跨 settlement location 整组拒绝；`AccountSession` 是账户余额/冻结权威；`OwnershipStake.location` 的构造/seed 入口在 D4-I 全部列明；`requestedScale` 默认 1 只留“已存在单实例且无请求”兼容路径；location 采用规范 `<q>_<r>` 格式；`WagePayableInventoryLookup.java` 唯一新增/实现 owner = D4-I，W3 只消费。旧正文保留为历史 provenance。

## §1 用户原话附录（逐字，2026-10-17）

### 1.1 U8：D4 的生产执行职责（与 D4 归属直接相关）

> D3直接删没毛病
> D4我建议归生产方式模块，生产方式模块干脆提供单个家户选用某个生产方式，预计/实际的产出和家户状态更新，而不是只提供一个模板，经济模块调用生产方式模块之后，负责计算随后的家户间经济关系，

来源：`docs/superpowers/specs/2026-10-02-architecture-design-source.md:612-617`。

### 1.2 U16：劳动价格直接支付、库存不足成债与再生产权益

> 1 这个估算有算法吗？ 2 我不建议搞这么复杂，最好是算出来劳动价格之后直接给——是否选择进行雇农生产是家户自己的事，给钱是地主的事 3 因此也不用算这么复杂，工资库存给不起直接计成债务就行，有库存就给库存，不设置额外的土地生产资料，被用作再生产的生产资料可以视为权益了，不算做可变卖活动库存

来源：`docs/superpowers/specs/2026-10-02-architecture-design-source.md:688-694`；同一原文亦见 `2026-10-17-d032-wage-chain-design.md:24`、`2026-10-17-d032-wage-settlement-simplification-decision.md`。

### 1.3 U21：欠薪不影响择业、禁止预付与现金后按库存最多抵付

> 1 不影响 2 不允许，地主优先给钱，没钱了再按库存最多的东西抵

来源：`docs/superpowers/specs/2026-10-02-architecture-design-source.md:738-744`。

### 1.4 U22：优先修复当前 bug

> 我决定先修bug

来源：`docs/superpowers/specs/2026-10-02-architecture-design-source.md:748-752`；`2026-10-17-d032-d3-integration-precheck.md` §5 也逐字收录同一句。

### 1.5 U23：欠薪零息、按下一 tick 生存条件裁决不从业、抵物折价可配置

> 1 肯定不计 2 任何家户只要条件允许（从事任何可从事的生产方式都没法换取下一tick的生存所需）都可以被裁决为不从业 3 折价可以更低一些，总之是可配置项，注意明确模块边界

来源：`docs/superpowers/specs/2026-10-02-architecture-design-source.md:758-763`；`2026-10-17-d032-wage-idle-barter-decision.md` §7（该文件只逐字收录 U23 原话，不含 U16/U21/U22 全文）。

### 1.6 口径归类：哪些是用户已定，哪些是工程默认

| 事项 | 归类 | 本文落点 |
|---|---|---|
| 工资先按对应货币支付，货币不足再按可支付活动库存的**数量**降序抵付，仍不足成债 | **用户已定（U16/U21）** | W3 消费 D4 lookup；D4 只提供“最终可支付量”，不重新排序、不重新定价 |
| 再生产权益/已投入资料不属于可变卖活动库存；不另设额外土地生产资料账 | **用户已定（U16/U21）** | `OwnershipStake` 仍是唯一实物权益账；D4 的占用授予只引用它，不复制资产数量；W3 不把权益算成商品库存 |
| R2 先修；先最小 D4，再独立修/验 R2，后做 D3/W 设计债 | **用户已定（U22）** | §6 的退役门与 §11 的批次顺序 |
| 欠薪零息、下一 tick 生存条件不从业、抵物折价可配置且可更低、明确模块边界 | **用户已定（U23）** | 只作为边界输入；D4 不重定义这些规则 |
| D4 由 production 提供生产定义/实例/预计与实际生产；economy 计算随后的家户间经济关系 | **用户已定（U8）；工程落形由 U9 授权** | §8 状态归属表：production 定义/实例，economy 经营关系与统一分配 |
| `OwnershipStake` 增加必填 `location` | **工程默认（D4-E1）** | §4.2；不加就无法区分不同地点的同一经营者份额，R2 仍可跨格复用 |
| 农业类资产跨周期锁定、工具类资产按实际生产时段占用 | **工程默认（D4-E2）** | §4.5；按 `AssetKind` 的默认策略可配置，不是用户新裁定 |
| 多个实例争同一份资产时按请求量比例分配、整数最大余数破平 | **工程默认（D4-E3）** | §5.4；不改成“先到先得”或“平均主义”，除非用户另行裁定 |
| 单实例仍拿其作用域内的整份资产 | **工程默认（D4-E4），同时是向后兼容判据** | §5.4；与 R2 最小设计 P1 逐值一致 |
| 唯一可支付库存读口的输出已扣冻结、活动占用/预留和权益排除量 | **工程默认（D4-E5）** | §7；W3 不再二次减法 |
| W3 先按 payer 聚合；同一 payer >1 settlement location 整组拒绝，单地点组一份库存预算 | **工程默认（D4-E6）；B3/B4/R-05 工程裁决** | §7.3/§7.4；整组 `wage-payer-location-unsupported`，不做部分支付 |
| 具体数值、策略载荷、日志级别、UI 呈现、资产跨格搬迁未来是否支持 | **开放/不在 D4 首期** | §12；不阻塞 D4-I 的资产/占用与 lookup；不把未定项伪装成用户已定 |

**不得新增的玩法裁定**：D4 不决定租金、分成、工资、价格、折价系数、利率或阶层规则；这些仍由已落盘的 production/economy 定义与 D3 消费。D4 的比例分配只是“同一资产在多个实例之间的工程预算规则”，它不改变任何资产的所有权、经营者、权利性质或数量。

## §2 现状事实（全部回当前代码核过）

> 基线：`b6f694dd`；工作区干净。以下“当前”均指本工作树当前源码，不是旧文档转述。

| # | 位置 | 当前事实 | 对 D4 的影响 |
|---|---|---|---|
| 1 | `simos-economy/src/main/java/io/mosire/simos/economy/model/OwnershipStake.java:35-42` | record 只有 `id / industry / asset / owner / operator / quantity / kind` 七个组件；**没有 location，也没有占用期**；`quantity ≥ 0`，`kind` 只有权利性质，不表达地点或时段 | 无法从现有份额区分“同一经营者在不同地点的两份实物”，也无法在时域上阻止同一份被两个实例重复占用；D4 必须做最小事实扩展 |
| 2 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionProcess.java:75-83` | 实例有显式 `location`；`location` 构造期非 null，`ProductionLocation.UNSPECIFIED` 读到语义边界必须 fail-closed | 地点的一半已经存在；D4 不用另造地点值类型，但份额侧必须补地点 |
| 3 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionLocation.java:31-49`、`:90`、`:110-113` | 已有 `record ProductionLocation(String value)`；`parse` 只校验非空白；`UNSPECIFIED` 是具名防御占位，任何“地点有语义”的读点必须拒绝 | W3 lookup 的地点形参固定用该类型；D4 不复制临时地点类型 |
| 4 | `simos-economy/src/main/java/io/mosire/simos/economy/time/AssetBudgetBook.java:22-44`、`:106-153` | 已有 R2 最小 fail-closed：同 `(industry, operator)` 有一个 unit 时给整池；多个 unit 时所有分配为空、整池进入 `unallocated`、每个 unit 得 `asset-scope-shared:awaiting-D4`；`quantity` 不写回 | 当前不是完整 D4；它只证明“不再复制整池”，还没有实例/地点/时段的真实授予 |
| 5 | `simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementIndex.java:62-68`、`:179-183`、`:266-285` | `SettlementIndex` 构造时调用 `AssetBudgetBook.allocate(units, assetShares)`，并让 `usableAssetsOf` / `assetRejectionOf` / `assetBudget()` 都读这一份派生结果 | D4 必须保留“一次 revision 只有一份统一预算结果”的架构，换成带地点/时段的 D4 分配器 |
| 6 | `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionProcessBook.java:50-59`、`:67-68`、`:124-127` | 旧的 `usableAssets(unit, assetShares)` 仍会直接扫份额；“索引版”读 `SettlementIndex`；R2 的 `AssetBudget` 重载给空表 | 旧扫描只能留作单 unit 夹具/窄入口；D4 的预计、排队、执行、读口、债务容量不得再用它。否则同一 revision 两套答案 |
| 7 | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:4237-4292` | `driveProductionDay` 按 unit id 稳定序构造执行步；构造前先查 `assetRejectionOf` / `hasMultipleUnitsInScope`，共享 scope 在 progress/经验/实际劳动/消耗/产出之前被业务拒绝；进入 request 的 `assetOccupancy` 来自 `SettlementIndex` | D4 要把这里的“拒绝共享 scope”改成“把同一份 D4 分配结果放入执行请求”，同时保留“先拒绝、零半状态”的顺序 |
| 8 | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:5743-5752` | 抽周期投入前再次检查 `assetRejectionOf` 与同 scope 多 unit；被拒绝的 unit 不扣任何周期投入 | 统一预算必须覆盖“资产授予 + 劳动 + 投入 + 账户”四条腿；D4 占用拒绝必须在这些写之前 |
| 9 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionProbeRequest.java:35-40` | 试算请求已有 `Map<AssetKind, Long> assetOccupancy`；`assetOccupancy` 是“该实例/经营者为此方案实占的生产资料”，0 不进表 | 预计与实际必须读同一份 D4 占用；不能试算用旧整池、执行用 D4 |
| 10 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionProbeResult.java:46-56` | 试算结果有 `assetRequired`，且有 `laborRequiredMilli` / `inputRequired` / `expectedOutput` / `efficiencyScalePerMille` | D4 的 `requestedScale` 不能用 `assetRequired` 反推（否则循环）；请求量必须由非资产预算/显式请求产生 |
| 11 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionExecutionRequest.java:44-51` | 执行请求已有 `assetOccupancy`，语义是“这一格、这个经营者为此实例实占的生产资料”；`grantedScale` 是投入侧上限 | D4 必须给执行请求填同一个 unit 的 D4 分配表，不能填未分配的整池 |
| 12 | `simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java:430-439` | 预计路径已经查 `assetBudget.rejectionOf(existingUnit.id())`；现有 unit 的占用来自 `AssetBudget` | D4 替换底层分配结果后，预计路径只能换源，不能另写第二套资产公式 |
| 13 | `simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueueBook.java:240-244`、`:601-603`、`:765-770` | 共享 scope 的 unit 在 offer 阶段具名不可行、不可吸收劳动、不被当作“估不出价原样保留”；执行段还有 fail-closed | D4 必须让预计/排队/执行同源；共享 scope 不再是永久拒绝，而是按 D4 授予结果进入或不进入 |
| 14 | `simos-economy/src/main/java/io/mosire/simos/economy/time/DebtCapacityBook.java:313-330` | 债务容量入口已有“与日结算共用同一份 AssetBudget”的重载 | D4 的资产预算必须继续供债务容量读；不能因 R2 收口让债务容量另扫份额 |
| 15 | `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketDemandBook.java:235-236` | 需求簿已用 `AssetBudgetBook.allocate(units, shares)` 计算引致投入需求 | D4 改分配器后，需求簿也必须读同一份 D4 预算；否则队列/需求/执行三套口径 |
| 16 | `simos-economy/src/main/java/io/mosire/simos/economy/time/AccountSession.java:45-52`、`:74-88`、`:143-155` | 账户活表只有商品余额、货币余额、商品冻结、货币冻结；账户主体是家户；没有“生产占用/库存预留”表，也没有 location 级库存 | W3 的 `WagePayableInventoryLookup` 不能只看 `goods − frozen` 就冒充“已扣占用/权益”；D4 必须提供权威排除量 |
| 17 | `simos-actor/src/main/java/io/mosire/simos/actor/model/AvailableStock.java:14`、`:60-62` | 全仓唯一可支配库存算法 = 余额 − 冻结；文档明确它**只减冻结**，必要生产投入/生活保留是决策层算式，不能塞进 `frozen` | D4 lookup 通过 `AccountSession` 读同一余额/冻结，再减 D4 的库存预留；不得把资产权益塞进 actor 冻结 |
| 18 | `simos-actor/src/main/java/io/mosire/simos/actor/model/HouseholdInventory.java:61-70`、`:98-107` | `frozen` 只表达“已明确的占用（挂单/承诺交付）”；必要生产投入不是 `frozen`。`HouseholdInventory` 是 actor 侧存储表示；economy 侧权威读面是 `AccountSession` 户级账户 | D4 的 `InventoryReservation` 是独立占用事实，不是第二本余额；D4/W3 通过 `AccountSession` 读余额/冻结，不直接拆 `HouseholdInventory` 建地点账；W3 不得把预留与冻结重复扣 |
| 19 | `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:310-390` | 当前经济状态没有资产占用/库存预留组件；资产份额是第 12 个组件 `assetShares` | D4 必须把这些事实加入 economy 状态、ChangeSet、codec，并守铁律 5 往返 |
| 20 | `simos-economy/src/main/java/io/mosire/simos/economy/change/EconomyChangeSet.java:130-180` | 变更集逐组件对应 `EconomyData`；当前没有占用/预留组件 | 新增状态必须同步新增 `FieldDelta`、`between`、`apply`、`isEmpty` 与 roundtrip 面 |
| 21 | `simos-economy/src/main/java/io/mosire/simos/economy/EconomyReferenceValidation.java:127-135` | 生产实例地点为 `UNSPECIFIED` 时，已具名拒绝；实例地点有语义 | D4 份额、请求、授予、lookup 也必须对地点做同款具名拒绝 |
| 22 | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/ProductionUnitId.java:49-60` | `idOf` 只由 `(industry, operator)` 拼，不含 location；契约允许调用方显式给唯一 id | 多地点实例必须由创建请求给不同稳定 id；若同 id 撞键，构造期拒绝，不能靠工厂偷偷补地点 |
| 23 | `simos-economy/src/test/java/io/mosire/simos/economy/time/R2SharedAssetScopeTest.java:64-69`、`:161` | 当前 R2 独立测试已断言：单 unit=100；同 scope 两 unit 分配空、unallocated=100、Σ规模=0（旧实现 1+1=2）；仍保留 R2 最小边界，不宣称完整 D4 | 当前安全但不完整；D4-T 必须另立真实分配/多地点/跨周期测试，不能只改断言 |
| 24 | `simos-app/src/test/java/io/mosire/simos/app/gui/R2AssetBudgetReadoutTest.java:79-97` | app 读口已能显示 allocations/unallocated/rejections，不把整池复制给每个 unit | D4 读口要扩展为带地点/时段/已占/未分配/释放的视图；旧读口不能悄悄变成第二权威 |
| 25 | 全仓 `grep -R "WagePayableInventoryLookup"`（当前 `src/main` 与 `src/test`） | **零实现、零接口**；D3 §6.9 只有契约文字代码块 | D4-I 必须首次实现该读口；W3 不能把 D3 的伪代码当已可调用 API |
| 26 | `2026-10-17-d032-wage-chain-design.md:1082-1102` | D3 已冻结 `WagePayableInventoryLookup` 的形参 `ActorRef payer + production.contract.ProductionLocation settlementLocation`；`Optional.empty()` = D4 未接/不可用；W3 不二次扣减 | D4 不得自创第二签名或 economy-api 地点类型；实现必须逐字兼容该契约 |

**R2 当前状态必须说清**：`asset-scope-shared:awaiting-D4` 已经在当前源码中实现，仓库里也有对应测试文件；本轮**没有运行**这些测试。这表示“共享资产不再被整池复制给多个实例”，**不表示完整 D4/R2 已关闭**。当前代码没有地点级份额、没有占用期、没有真实多实例分配，也没有 `WagePayableInventoryLookup` 实现。本文是把最小 fail-closed 升级为完整 D4 的设计，不是对已经完成的实现的追认。

## §3 目标、非目标与术语

### 3.1 目标

1. 在同一份 `OwnershipStake` 实物总账上，表达**资产身份、owner/operator、industry、asset kind、quantity、location/作用域、占用期**。
2. 把“实例/地点的实际占用、占用请求/授予/回填、可用/已占/未分配”变成可检查的、确定性的、整数守恒的事实。
3. 用同一个 `AssetOccupancyBudget` 服务试算、预计利润、劳动排队、实际执行、债务容量、需求簿与 app 只读视图，落实既有 I8（同一份资产/劳动/库存不得重复占用，释放量必须回填）。
4. 让 R2 从“遇到共享 scope 全部拒绝”升级为“真实的多实例/多地点统一占用”，并给出可独立验收的 R2 关闭判据。
5. 给 W3 一个唯一、只读、已扣完的 `WagePayableInventoryLookup` 读口；W3 不二次扣减、不回退整账户余额。
6. 明确状态归谁、ChangeSet/codec 往返、命令/读口/失败词表和独立测试边界。

### 3.2 非目标

- 不新增独立土地账、第二份资产数量权威、第二份可写实例/定义/效率权威。
- 不把再生产权益、占用中的生产资料或未来预计份额变成可变卖活动库存。
- 不新增租金、分成、工资、价格、折价、利率、土地或阶层的玩法裁定。
- 不在 D4 首期支持资产跨格搬迁、动态重分配抢断、跨周期提前解约或 W3 支付公式。
- 不修改 D1/D2/D3/status/R2 设计/decision/precheck/adjudication；D4 只在自己正文里写对 D3/R2 的接入契约。
- 不把 `ProductionProcessBook.usableAssets(unit, assetShares)` 旧扫描路径保留为多实例生产路径。

### 3.3 术语

| 术语 | 含义 |
|---|---|
| 资产份额 `OwnershipStake` | 实物资产总账的一行：谁拥有、谁经营、何种资产、多少、何种权利、在哪个地点。**唯一数量权威** |
| 占用请求 `AssetOccupancyRequest` | 某实例/某地点/某时段对某资产种类的需求；revision 内派生，不持久化 |
| 占用授予 `AssetOccupancyGrant` | 一次已经通过统一预算的、引用 `AssetShareId` 的数量+时段占用；不是资产数量账，只是使用权/容量锁 |
| 已占 `committed` | 在目标时段内已存在且仍 ACTIVE 的占用授予 |
| 未分配 `unallocated` | 某作用域在该时段的实有量 − 已授予量；只读可见，不搬走 `OwnershipStake` |
| 回填 `backfill` | 占用到期/取消后，释放量重新进入后续预算；对应既有 I8 的“被缩减释放的资源必须继续参与后续分配” |
| 占用作用域 `AssetOccupancyScope` | `(industry, operator, location, assetKind, fromTick, toTickExclusive)`；不是新的资产身份 |
| `WagePayableInventoryLookup` | W3 唯一可支付商品库存读口；输入 `payer + settlement location`，输出已扣完的逐商品数量 |

## §4 资产/占用模型

### 4.1 原则

1. **一份数量真相**：`OwnershipStake.quantity` 仍是唯一实物数量；`AssetOccupancyGrant` 和 `InventoryReservation` 都只是引用/预留，不能自增资产数量，也不能被当成余额。
2. **地点显式**：资产份额的地点与生产实例的地点各自显式；禁止从 `(industry, operator)` 或市场区反推出唯一地点。
3. **时域显式**：每个占用授予都有半开区间 `[fromTick, toTickExclusive)`；区间外不算已占。
4. **先占后做**：预计、排队、执行、消费看到一个 unit 的 D4 占用，必须来自同一份预算；任何一方不能自己重算。
5. **就地表达**：不新建 `LandLedger` / `AssetOccupancyData` 之类的资产总账；占用事实放在 economy 的占用表里，只引用既有份额。

### 4.2 最小事实扩展：`OwnershipStake.location`

在现有 `OwnershipStake` 上增加一个必填组件：

```java
public record OwnershipStake(
    AssetShareId id,
    IndustryId industry,
    AssetKind asset,
    ActorRef owner,
    ActorRef operator,
    long quantity,
    AssetRightKind kind,
    ProductionLocation location // 新增；不得为 null/UNSPECIFIED
) {}
```

冻结语义：

- `location` 是该实物份额实际所在的格；必须使用已有的 `io.mosire.simos.production.contract.ProductionLocation`，不新建地点值类型。
- **业务格键格式（R-15）**：`value` 必须是规范 `<q>_<r>`，`q`/`r` 为十进制整数，且 `value.equals(IndustryHexKeys.hexKey(q,r))`（不得有前导 0、正号或 `-0`；`foo`、`1`、`1_`、`a_b` 都非法）。该规则与 `IndustryUpserts` 的现有一格式守卫同形；economy 入口可复用同一 helper 或落同一纯校验，但不得放宽到“非空白即可”。非法/缺失/`UNSPECIFIED` ⇒ `asset-location-missing`（契约入口 ERROR，业务入口 INFO 拒绝）。
- 构造期拒绝 `null`、`UNSPECIFIED` 与非法 `<q>_<r>`；读到 `UNSPECIFIED` 的一切资产/占用读点具名 fail-closed。
- `idOf(...)` 的唯一拼写点必须加入 `location`，保证同一 `(industry, asset, owner, operator, kind)` 在两个地点的份额不会得到同一个 id。建议新拼写形如：
  `share-<industry>-<asset>-<owner.kind>-<owner.id>-<operator.kind>-<operator.id>-<kind>-<location.value>-<sequence>`。
- 既有 `AssetShareId` 是 opaque；Alpha 不兼容旧档，旧 id 不重算、不迁移。新世界从创世重新播种。
- `Owner` 与 `operator` 的语义不变；D4 占用只允许 `grant.operator == share.operator`，不改变所有权。
- `quantity` 仍然 ≥0；0 行只可为“已被质押/授予/历史引用”保留，不能作为新的可用资产。
- **不把 `location` 做成第二张 `AssetPlacement` 表**：那会形成“quantity 在一处、location 在另一处”的双写漂移面，违反“唯一数量权威”和铁律 5。

份额 JSON/载荷的最小新形状（示意）：

```json
{
  "industry": "farm",
  "asset": "LAND",
  "owner": {"kind": "HOUSEHOLD", "id": "hh-1"},
  "operator": {"kind": "HOUSEHOLD", "id": "hh-1"},
  "location": "0_0",
  "quantity": 100,
  "kind": "OWNED"
}
```

所有资产份额写入口（seed、GM adjust、`TransferOwnershipStake`、`OwnershipStakeBook` 的 Move/merge/rebuild、`EconomyGovUnitUpserts`、`EconomyEntrySettlement`、`EconomySettlement`、`EconomyPayloads`、clear region）都必须显式携带 `location`；`TransferOwnershipStake` 不改变原份额地点，只改 owner/operator/kind/quantity。**所有权与批次（R-08）**：D4-I 是以下全部构造/seed 入口的唯一 owner，并逐一列出/修改：`OwnershipStakeBook` 的两处 `new OwnershipStake`、`EconomyGovUnitUpserts` 的新建份额、`EconomyPayloads.addOwnershipStake`、`EconomyEntrySettlement`、`EconomySettlement` 的份额构造，以及 `simos-app/.../EconomySeeder.ownershipStakeNode`/其调用点（JSON seed 必须带 `location`）。不拆 D4-S；D4-I 同时持 `simos-app/.../EconomySeeder.java` 的 seed 适配写面，保证“一文件一 owner”。任何 `new OwnershipStake(...)` 漏 `location` ⇒ 编译红；任何 seed 载荷漏 `location` ⇒ 创世 `asset-location-missing` 判据红。D4 首期不支持“把同一份额从 A 格搬到 B 格”的命令；若出现该请求，具名拒 `asset-relocation-unsupported`，不得静默改 location。

| 构造/seed 入口（R-08） | `location` 的唯一来源 |
|---|---|
| `OwnershipStakeBook` Move/split/merge/rebuild 新建目标份额 | 沿用源份额 `source.location()`；Move/Rebuild 不得改地点（搬迁另立 spec） |
| `EconomyGovUnitUpserts` 新建 OWNED 份额 | 该 intent/unit 的已落定 `ProductionLocation`；无法取得 ⇒ `asset-location-missing`，不得从 `industry` 或 `operator` 猜 |
| `EconomyPayloads.addOwnershipStake` | 载荷必填 `location`；按规范 `<q>_<r>` 解析并校验，非法 ⇒ `asset-location-missing` |
| `EconomyEntrySettlement` 新建份额 | 创建请求/方案已落定的 unit 地点；`UNSPECIFIED`/缺失 ⇒ fail-closed |
| `EconomySettlement` 份额迁移/重建构造 | 源份额 `location()` 或同批 unit 地点；不得复制到另一地点 |
| `EconomySeeder.ownershipStakeNode` 及其调用点 | 生成该 seed 条目的 plan/hex 对应格键；每条 `assetShares` 必须写入 `location`，无默认/空值 |
| app 只读视图 | 只读，不构造 `OwnershipStake` |

> **创世/编译判据**：D4-I 编译前用 `rg -n 'new OwnershipStake' simos-economy/src/main simos-app/src/main` 逐点核对 location；创世 world 必须能读回每条 seed 的 location，且任何缺失/非法地点在写入前 `asset-location-missing`。D4-I 不拥有 `AssetKind` 词表（归 C0-A），但必须消费 C0-A 的冻结值。

### 4.3 占用事实：`AssetOccupancyGrant`

占用授予是按份额、按实例、按时段的数量锁，不是资产账。

```java
public record AssetOccupancyGrant(
    AssetOccupancyGrantId id,
    AssetShareId shareId,              // 唯一实物来源
    ProductionUnitId unitId,           // 占用者/消费者
    long quantity,                     // >0；不得超过 share.quantity 的剩余量
    long fromTick,
    long toTickExclusive,              // fromTick < toTickExclusive
    AssetOccupancyStatus status,       // ACTIVE | RELEASED
    long releasedAtTick                // ACTIVE 用 -1；RELEASED 时 >= 0
) {}
```

- 不存 `owner/operator/industry/assetKind/location` 的副本；这些从 `shareId` 指向的 `OwnershipStake` 与 `unitId` 指向的 `ProductionProcess` 派生，避免两处漂移。读口 `AssetOccupancyView` 才把这些字段拼出来。
- `AssetOccupancyGrant.id` 必须确定性生成；建议由 `(unitId, shareId, fromTick)` 生成，不使用随机/时钟/UUID，且不含 `.`。
- 授予量不能超过对应份额的 `quantity`；同一份额在同一时段的全部 ACTIVE 授予之和 `≤ share.quantity`。
- `shareId` 悬空、`unitId` 悬空、grant 的 `operator` 与 share/unit 不一致、grant 的 location 与 unit/share 不一致 ⇒ **契约故障 ERROR**，不是业务拒绝。
- `RELEASED` 行保留为审计/回填依据；它不再计入已占。释放量 = grant.quantity，回填到后续预算的 `unallocated`/可用量，不修改 `OwnershipStake`。
- 释放/取消/周期关账/实例删除都必须走同一释放路径；不允许某个调用点直接删表绕过释放记录。

**`AssetOccupancyView`（只读派生）**：

```java
record AssetOccupancyView(
    AssetOccupancyGrantId grantId,
    AssetShareId shareId,
    IndustryId industry,
    AssetKind assetKind,
    ActorRef owner,
    ActorRef operator,
    ProductionLocation location,
    ProductionUnitId unitId,
    long quantity,
    long fromTick,
    long toTickExclusive,
    AssetOccupancyStatus status)
```

### 4.4 库存预留：`InventoryReservation`

D4 还必须让 W3 知道“哪些商品虽然还在账户余额里，但已经被生产占用/承诺，不能拿去抵工资”。它不能把 `OwnershipStake` 当商品，也不能把已实际扣掉的投入再扣一次。

```java
public record InventoryReservation(
    InventoryReservationId id,
    ProductionUnitId unitId,
    ActorRef payer,                    // 账户所有者；必须是已登记家户
    ProductionLocation location,
    CommodityId commodity,
    long quantity,                     // >0
    long fromTick,
    long toTickExclusive,
    InventoryReservationStatus status, // ACTIVE | RELEASED
    long releasedAtTick                // ACTIVE 用 -1；RELEASED 时 >= 0
) {}
```

- 它只是**商品库存的占用声明**，不是账户余额、不是冻结、不是第二本库存账；**账户权威是 `AccountSession` 的户级商品/货币余额与冻结**（`HouseholdInventory` 是 actor 侧存储表示，D4 通过 `AccountSession` 读，不另建地点级余额账）。
- 创建时必须校验：同一 `(payer, location, commodity)` 的 ACTIVE 预留总量 + `AccountSession` 的 `frozenGoods` ≤ 该户账户余额；否则契约故障 `inventory-reservation-overcommitted`，不得静默截断。
- 已实际转出/消耗的投入，应由账户变更集扣减余额，并同时把对应 `InventoryReservation` 置 `RELEASED`；不得同时保留“余额已减 + 预留仍 ACTIVE”的双重扣除。
- 未转出的生产投入/再生产资料才用 ACTIVE 预留表达；`OwnershipStake` 的 LAND/CATTLE/SMALL_TOOL/LARGE_TOOL 等不是 `CommodityId`，不会进入 W3 的返回商品表，也不需要从商品余额里再减一遍。
- 若未来某生产方式把“再生产权益”映射成商品，必须由 D4 明确建 `InventoryReservation`，不得在 W3 写一个按商品名猜测的排除表。

`InventoryReservation` 的生命周期由**生产投入承诺/实际转出**驱动，不由资产占用分配器顺手创建：

1. 当生产承诺使用某商品、但该商品尚未从 payer 账户实际转出时，建立 ACTIVE 预留（数量 ≤ 账户可用量），让 W3 同 revision 的工资结算不能重复使用这笔商品；
2. 当实际转出/消费在同一 revision 落账时，账户余额由 actor 所有者扣减，同时把该预留置 `RELEASED`；二者恰好只扣一次；
3. 生产取消/批次失败 ⇒ 释放预留，余额未动，W3 或后续用途可重新使用；
4. 同一 `(payer, location, commodity)` 的 ACTIVE 预留总和 + `AccountSession.frozenGoods` 不得超过该户账户余额；否则 ERROR `inventory-reservation-overcommitted`，不得静默 `max(0)`。

### 4.5 占用持续期、续占与释放

#### 4.5.1 默认持续期策略（工程默认 D4-E2）

每个 `AssetKind` 有一个经济侧可配置的 `AssetOccupancyHorizonPolicy`，首期默认：

| AssetKind | 默认 horizon | 区间 | 理由 |
|---|---|---|---|
| `LAND` | `CYCLE` | `[cycleStart, cycleStart + plan.cycleDays)` | 农业跨周期，土地上种下的生产不能每天被抢走 |
| `CATTLE` | `CYCLE` | 同上 | 牲畜是跨周期的生产条件；本设计不引入牲畜玩法 |
| `WORKSHOP` | `CYCLE` | 同上 | 工坊能力按周期保留 |
| `MACHINE` | `CYCLE` | 同上 | 机器能力按周期保留 |
| `SMALL_TOOL` | `TICK` | `[today, today + 1)` | 小型农具/手工具按实际生产时段占用；空转日可释放，次日重新请求 |
| `LARGE_TOOL` | `CYCLE` | `[cycleStart, cycleStart + plan.cycleDays)` | 大型农具/设备是跨周期生产条件；首期按周期保留，避免每天被抢走（工程默认，可配置） |
| `SHIP` | `TICK` | `[today, today + 1)` | 船只按实际航次/生产时段占用；不凭空跨格移动 |

规则：

- 策略是 economy 的唯一配置；production 只定义 `capacityPerUnit`/方案/实例，不保存第二份“占多久”的运行时副本。
- 未登记 `AssetKind` 或 horizon 值非法 ⇒ fail-closed `asset-occupancy-horizon-unsupported:<kind>`。
- 首期不允许同一 asset kind 同时出现两种互相重叠的 horizon；若实现需要按方案覆盖，必须由 production 定义面提供**单一来源**并在 D4-I 账本中记录，不能在 economy 里另写一张隐藏表。
- 策略值是可配置工程默认，不是用户已经选择的农业/工具制度；用户可随时推翻，但不得在实现中把默认值冒充成用户裁定。

#### 4.5.2 区间与周期起点

- `CYCLE`：既有实例的 `cycleStart = today - unit.progressDays()`；新实例采用 `cycleStart = today`，`toTickExclusive = cycleStart + plan.cycleDays()`。若 `cycleDays` 或 `progressDays` 使区间非法，契约故障。
- `TICK`：`[today, today + 1)`。若今天不是生产日/没有实际劳动，占用请求可为空，grant 可在日末释放。
- 所有 tick 都是世界日的整数；不使用时钟时间、UUID、随机数。
- 区间比较使用半开区间：两个区间 `[a,b)` 与 `[c,d)` 重叠当且仅当 `a < d && c < b`。

#### 4.5.3 续占

“续占”是同一 unit、同一 share、下一段相邻区间的新请求，不是无限期自动续租：

1. 若上一段 grant 的 `toTickExclusive == 新 fromTick`，请求的 `previousGrantId` 指向上一段 grant，作为连续性证据；没有上一段则为 `Optional.empty()`。
2. 续占仍必须重新通过统一预算；它不自动获得旧资产，不凌驾于其他请求之上；当供给足够时正常授予，不足时按 §5.4 的同一稳定规则分配。
3. 续占成功创建新 grant；旧 grant 到期进入 `RELEASED`。不得用“延长旧 grant 的 endTick”来绕过一次预算重算。
4. 若同一 unit 在重叠区间内已有 ACTIVE grant，新的重叠 grant 必须拒绝 `asset-occupancy-overlap:<share>:<unit>`，而不是合并成两份。

#### 4.5.4 释放与回填

释放发生的合法时机：

- `TICK` grant 到 `toTickExclusive`；周期结束后自动释放；
- `CYCLE` grant 到周期结束/实例关账；
- 实例创建失败/生产批次整体拒绝；
- 明确的业务取消/清区/实例退役。

释放动作：

1. 将 grant 的 `status=RELEASED`，`releasedAtTick=当前 tick`；
2. 生成一条 INFO 释放读数（grant/unit/share/location/kind/quantity/from/to/releasedAt）；
3. 下一份 `AssetOccupancyBudget` 的 `unallocated` 立即增加该数量；后续候选/实例可重新请求；
4. 不修改 `OwnershipStake.quantity`、不改 owner/operator/kind；不得把释放量写成负资产或“凭空新增”；
5. 若释放后同一 `(share, interval)` 又被另一个 unit 授予，必须在前一释放已提交后才可发生；同一 revision 内不允许同一份额同时存在两条重叠 ACTIVE grant。

### 4.6 读取口径：可用 / 已占 / 未分配

- **可用** `available(scope)`：该作用域内全部匹配份额的 `quantity` 之和（只读），不减去 `frozen`（资产份额不是账户冻结）。
- **已占** `committed(scope)`：与目标 interval 重叠的 ACTIVE grant 数量之和。
- **未分配** `unallocated(scope)`：`available − committed − 本次新授予`；恒 ≥0；只读可见。
- **某 unit 的授予** `allocationOf(unit)`：该 unit 在目标 interval 的 ACTIVE + 本次新授予，按 `AssetKind` 汇总。
- **请求** `requestedOf(unit)`：本次派生请求，只在当前 revision/readout 可见，不写入 `EconomyData`。

## §5 统一预算：`AssetOccupancyBook`

### 5.1 唯一输入/输出

输入（全部只读；来自同一 revision）：

```java
record AssetOccupancyRequest(
    ProductionUnitId unitId,
    IndustryId industry,
    ActorRef operator,
    ProductionLocation location,
    long fromTick,
    long toTickExclusive,
    Map<AssetKind, Long> requestedQuantity,
    Optional<AssetOccupancyGrantId> previousGrantId) {}
```

```java
record AssetOccupancyBudget(
    Map<ProductionUnitId, Map<AssetKind, Long>> allocations,
    Map<ProductionUnitId, Map<AssetKind, Long>> committed,
    Map<AssetOccupancyScope, Map<AssetKind, Long>> available,
    Map<AssetOccupancyScope, Map<AssetKind, Long>> occupied,
    Map<AssetOccupancyScope, Map<AssetKind, Long>> unallocated,
    Map<ProductionUnitId, String> rejections,
    List<AssetOccupancyGrant> grantsToWrite,
    List<AssetOccupancyGrant> grantsToRelease) {}
```

`AssetOccupancyScope`：

```java
record AssetOccupancyScope(
    IndustryId industry,
    ActorRef operator,
    ProductionLocation location,
    AssetKind assetKind,
    long fromTick,
    long toTickExclusive) {}
```

`AssetOccupancyBudget` 是**纯派生值**：不进 `EconomyData`、不进 ChangeSet、不跨 revision 缓存；只有 `grantsToWrite` / `grantsToRelease` 通过 economy ChangeSet 落状态。`InventoryReservation` 不参与资产数量分配（它是商品库存占用声明），由生产投入承诺/实际转出的同一 revision 单独写入/释放；它不是第二个资产分配器，也不改变本预算的资产守恒。

### 5.2 请求量怎么来（工程默认 D4-E4）

请求量**不得**从“资产可用量”反推，否则会出现“有资产 ⇒ 请求有资产 ⇒ 规模由资产决定 ⇒ 请求更多资产”的循环，也无法让预计与实际同源。请求构造规则：

- `requestedScale` 是调用方在进入 D4 分配前给出的、**非资产通道**的计划规模。可由现有非资产约束派生：
  `min(floor(可用劳动预算 / laborPerUnit), floor(可用投入库存 / inputPerUnit))`；
  任一通道明确为 0 ⇒ `requestedScale=0`。
- **默认 1 的唯一适用范围（R-10）**：`requestedScale=1` 只能作为“该 scope 内已存在单实例且调用方没有请求”这一**向后兼容路径内部**、旧调用面必须给一个数字占位时的回退值；它**不得生成请求表、不得把整池授予缩成 `1×capacityPerUnit`**。该路径的实际授予仍走 §5.4 第 2 步整池兼容；若调用方必须显式给请求，则不存在默认。候选/新实例、多实例 scope 一律不得默认 `1`/整池：必须由选业阶段给出显式 `requestedScale`，缺失 ⇒ `asset-occupancy-request-missing:<unit>`；既有实例无法给出请求且有多个 unit 也同样拒绝。
- 逐资产：`requestedQuantity[k] = requestedScale × plan.capacityPerUnit[k]`；使用 `Math.multiplyExact`，溢出是契约故障 `asset-occupancy-quantity-overflow`。
- `requestedQuantity` 只保留正值；0/负值项不进入请求表（负值本身是坏数据）。显式 `requestedScale=0` 表示本 tick 不请求资产，不是“缺请求”。
- 对既有实例，`requestedScale` 还必须受当前周期已实际投入/劳动上限约束；不得用 `probe.assetRequired` 或 `ProductionProcessBook.usableAssets` 反推。
- 请求表按 `(unitId, assetKind, fromTick, toTickExclusive)` 稳定排序，重复 key 合并为一条；同一 unit 对同一 asset kind 不得有两条不同请求版本。
- **单实例兼容例外**：若该 scope 内只有一个**已存在的** unit，为保持单实例逐值不变，允许请求表为空，由 §5.4 第 2 步授予整池；候选/新实例仍必须提供请求量，不得借用该例外。

### 5.3 作用域、匹配与供给

对每个请求/asset kind 形成 scope：

1. 份额必须满足：
   - `share.industry == request.industry`；
   - `share.operator == request.operator`；
   - `share.location == request.location`；
   - `share.asset == request.assetKind`；
   - `share.kind ∈ plan.acceptedRightKinds`；
   - `share.quantity > 0`。
2. `owner` 不参与池化键；同一 operator 可以同时使用自有/租佃/公共份额，所有者差异由既有关系规则计租/分账，不在 D4 重分配。
3. 同一 scope 的 supply = 全部匹配份额的 `quantity` 之和。份额 id 按 `AssetShareId.value` 升序做确定性兜底。
4. 若生产实例/份额 `location` 为 `UNSPECIFIED`、缺失或**不符合规范 `<q>_<r>`**（如 `foo`）⇒ `asset-location-missing`；若请求地点没有任何匹配份额 ⇒ `asset-location-scope-missing:<location>`，不得跨格借用其他地点份额。
5. 若 `plan.acceptedRightKinds` 为空或没有任何匹配权益 ⇒ `asset-right-kind-missing:<industry>`，该 unit 不可产。
6. 多地点、多实例的 `ProductionUnitId` 必须在创建请求中显式稳定且唯一；`ProductionUnitId.idOf(industry, operator)` 只适用于“该经营者在全世界只有一个该 plan 实例”的语境。出现同键两个实例 ⇒ 构造期 `production-unit-id-collision`，D4 不得用“后写覆盖”或“按地点自动补 id”掩盖。

### 5.4 分配算法（工程默认 D4-E3/E4）

对每个 scope 执行：

1. **先认已占**：读取与 `[fromTick,toTickExclusive)` 重叠且 `status=ACTIVE` 的 grant；逐份校验 share/unit/operator/location/kind 一致。已占量计入 `committed`，从 supply 中扣除，不允许被后到请求抢走。
2. **单实例不变式**：若该 scope 内只有一个有效 unit（请求或既有 ACTIVE grant 的并集只有一个 unit），该 unit 得到**整个 supply**；若已有 ACTIVE 少于 supply，则新增差额 grant；若没有请求但该 unit **已存在**，也保留整池（与 R2 最小设计 P1 逐值一致）。**候选/新实例不得进入本步的“无请求保留整池”**：它们必须先在 §5.2 给出显式 `requestedScale`，否则已返回 `asset-occupancy-request-missing:<unit>`。
3. **多实例需求**：若 scope 内 ≥2 个 unit：
   - 每个 unit 的本次需求 `d_i = max(0, requestedQuantity_i − 已属该 unit 且与目标区间重叠的 ACTIVE quantity)`；
   - `S = supply − Σ ACTIVE`（剩余可用）；
   - `D = Σ d_i`；
   - `D == 0` ⇒ 没有新授予；所有 ACTIVE 保持，`unallocated = S`；
   - `S >= D` ⇒ 每个 unit 得 `d_i`，`unallocated = S − D`；
   - `S < D` ⇒ 按请求量比例分配：
     `base_i = floor(S × d_i / D)`（用 `Math.multiplyExact` + `floorDiv`，溢出即契约故障）；
     余数 `r_i = (S × d_i) − base_i × D`；
     剩余 `R = S − Σ base_i`；
     按 `(r_i desc, unitId.value asc)` 给前 `R` 个 unit 各 +1；
     结果必须 `Σ grant_i = S`、每个 `grant_i ≤ d_i`、`unallocated = 0`。
4. **按份额落 grant**：对每个 unit 的新授予，按 `AssetShareId.value` 升序扫描该 scope 的份额；每份份额的 `remaining = share.quantity − 该份额上已 ACTIVE 的数量 − 本批已新授予数量`，在 `remaining` 内创建 grant 行。允许一个 unit 的一条资产种类授予跨多个份额（多条 grant 行），但 Σ 行数量必须等于该 unit 的授予量，且每份份额的 Σ(ACTIVE+新授予) ≤ `share.quantity`。
5. **拒绝**：请求 unit 在 scope 内拿不到任何正授予 ⇒ `rejections[unit] = "asset-short:<assetKind>"`；若 scope 完全没有 supply ⇒ `asset-location-scope-missing:<location>`（若有请求地点）或 `asset-short:<assetKind>`（若地点存在但同一位置无匹配份额）。
6. **不写资产账**：整个算法只创建/释放 grant 行；`OwnershipStake` 的 quantity、owner、operator、kind、location 一字不改。

**稳定序**：所有集合遍历按 scope `(industry.value, operator.kind, operator.id, location.value, assetKind.name, fromTick, toTickExclusive)`、unit `ProductionUnitId.value`、share `AssetShareId.value`、商品 `CommodityId.value` 的升序。不得用 `HashMap` 迭代序、随机数、时钟或 UUID 决定并列。

### 5.5 整数守恒与空值

- 每个 scope、每个 asset kind：`committed + newGrant + unallocated == available`。
- 每个 unit/asset kind：`allocationOf(unit) == committedOf(unit) + newGrantOf(unit)`。
- 每个 share：`Σ ACTIVE grant.quantity + Σ new grant.quantity ≤ share.quantity`。
- 数量全部 `long`、非负；所有加乘用 `Math.addExact` / `Math.multiplyExact`；溢出是 ERROR 契约故障。
- 无份额/无请求 ⇒ 对应表为空；不得用 null、负数或“缺省整池”填充。
- `unallocated` 只能通过 view 读取，不允许在分配函数里把余额转给某个 unit。

### 5.6 预计、排队、执行、读口同源

一次 settlement 的 D4 数据流固定为：

```text
same revision state + same requests
  → AssetOccupancyBook.allocate(...) 一次
  → AssetOccupancyBudget（allocations / committed / unallocated / rejections）
  → ProductionProbeRequest.assetOccupancy / ExpectedProfitBook / LaborQueueBook
  → ProductionExecutionRequest.assetOccupancy
  → 同一 Revision 的 economy + production + actor ChangeSet
```

- 预计和实际都读取同一个 `AssetOccupancyBudget`，不得在 `ExpectedProfitBook`、`LaborQueueBook`、`EconomySettlement` 或 app 各自重算。
- 预计/实际允许因“最终劳动/投入不同”产生不同结果；不允许因“取资产来源不同”产生不同占用。
- 如果 `AssetOccupancyBudget` 构造失败/缺失，必须有具名拒绝 `asset-occupancy-unavailable`，不得退回旧的 `ProductionProcessBook.usableAssets(unit, assetShares)` 整池扫描。

### 5.7 I8 全腿（沿用既有 I8）

D4 的“全腿”指：

- **资产腿**：D4 grant 给出的逐单位占用；
- **劳动腿**：既有劳动队列分配的实际贡献；
- **投入腿**：周期投入抽料；
- **账户/产出腿**：actor 账户转移与产品分配；
- **经验/进度腿**：production 的 progress、experience、cycle contribution。

五条腿必须属于同一 revision/同一候选；任何一条失败，该 unit（若只是业务拒绝）或整个候选（若是契约故障）不得留下另一半状态。被缩量/释放的资产、劳动、投入必须按 I8 回填到后续分配，不得“留在空缺”。D4 只扩展资产腿，不改变既有 I8 的劳动/库存部分，也不把 W3 的支付公式塞进 D4。

## §6 与 R2 最小修复的取代关系

### 6.1 当前行为（D4 前）

当前 `AssetBudgetBook` 的行为：

```text
scope 内恰 1 个 unit  ⇒ 该 unit 拿整个 scope 的资产（单实例逐值不变）
scope 内 >1 个 unit   ⇒ 每个 unit 分配为空；整池进入 unallocated；
                        每个 unit 得 asset-scope-shared:awaiting-D4
```

这不是“多实例生产已支持”，而是**不做危险的整池复制**。当前 R2 独立测试只证明该最小边界；D4 设计不改变这个事实，也不把最小边界说成完整 R2。

### 6.2 `asset-scope-shared:awaiting-D4` 的退役条件

该字符串退役必须同时满足下列**全部门**，缺一不可：

1. **D4-I 完成**：`OwnershipStake.location`、 `AssetOccupancyGrant`、`InventoryReservation`、`AssetOccupancyBook`、`WagePayableInventoryLookup` 实现完成，且所有资产消费者都改用同一份 `AssetOccupancyBudget`；
2. **D4-T 独立通过**：§10 的全部正向、负向、变异、数值例、I8 全腿、确定性、ChangeSet/codec 往返与 R2 原复现均通过；
3. **旧路径清零**：`src/main` 中不再出现 `asset-scope-shared:awaiting-D4` 常量或分支；旧的“共享 scope 全部拒绝”测试按新架构迁移/删除，不能留下假绿；
4. **新统一入口有守卫**：任何资产消费者绕过 `AssetOccupancyBudget` 直接扫 `assetShares` 的路径，必须被编译面或独立扫描判据挡住；
5. **R2 原复现改判**：`LAND=100`、同方案同经营者两实例不再各得 100；实际占用总和 ≤ 实有 100，实际产出/规模不能因多建实例凭空翻倍（正常熟练度/效率增产另行对照）。

退役动作：

- 删除 `SHARED_SCOPE_REJECTION` 常量及所有运行时使用；
- `AssetBudgetBook` 要么被改写为 D4 的轻量门面，要么删除并由 `AssetOccupancyBook` 取代；不允许保留两个可独立选择的分配器；
- app 读口从 `allocations/unallocated/rejections` 升级为带 `location/fromTick/toTick/committed/released` 的 D4 视图；
- 文档历史可以保留旧字符串作为 provenance，但当前运行路径不得再出现。

### 6.3 R2 原复现的完整修复判据

原复现：同一共享方案、同一经营者的两个实例（当前审核记录为 `0_0` 与 `3_4`），只有一条 `LAND=100` 的 `OwnershipStake`；旧实现每个实例各得 100，合计 200。

D4 完成后的完整判据分两种合法形态：

| 形态 | 预期 |
|---|---|
| 份额 `location=0_0`，两实例分别在 `0_0` 与 `3_4` | `0_0` 实例得到该份额的 100；`3_4` 实例得到 0 并具名 `asset-location-scope-missing:3_4`；两实例实占合计 = 100，不是 200 |
| 两实例都在 `0_0`、共享同一份额 100 | 按各自请求量分配；例如各请求 50 ⇒ 50/50，若各请求 100 ⇒ 50/50；实占合计 = 100；总生产规模/产出按 100 的容量，不得翻倍 |
| 两实例在不同地点，但各自有真实的 100 份额 | 各得 100；合计 200 是合法的，因为实有总量是 200，不得误报“重复占用” |

必须同时验证“实际执行”而不只看显示值：同一份资产进入两个 `ProductionExecutionRequest.assetOccupancy` 时，第二条必须被 D4 预算拒绝/缩减；试算、劳动排队、预计利润、执行、债务容量、需求簿读到的 `assetOccupancy` 必须逐单位一致。

### 6.4 D4 未接 / 部分接时的 fail-closed

| 状态 | 必须行为 |
|---|---|
| D4 完全未接（当前旧路径） | 保留 R2 最小 fail-closed：共享 scope 两个 unit ⇒ 无正分配、整池 `unallocated`、两个 unit 得 `asset-scope-shared:awaiting-D4` |
| D4 已接但 `AssetOccupancyBudget` 缺失/不可用 | `asset-occupancy-unavailable`；该 unit 不进入预计/排队/执行；不得回退整池扫描 |
| 份额缺 location / `UNSPECIFIED` | `asset-location-missing`；该 unit 不可产；契约入口可报 ERROR |
| 请求地点没有匹配份额 | `asset-location-scope-missing:<location>`；不跨格借份额 |
| 请求量/scale 缺失 | `asset-occupancy-request-missing:<unit>`；不默认 1、不默认整池 |
| 同 scope 多实例但只有部分消费者改读 D4 预算 | `asset-occupancy-unavailable`（或实现前的旧 `asset-scope-shared:awaiting-D4`），禁止一半用 D4、一半用旧整池 |
| D4 grant 与 `OwnershipStake`/实例日期/地点不一致 | ERROR 契约故障，拒绝整个相关候选 revision，不静默修正 |
| W3 lookup 未接 | `WagePayableInventoryLookup` 返回 `Optional.empty()`；W3 货币腿照付，库存腿全部跳过，记 `wage-inventory-scope-unavailable`，余款成债 |

**红线**：D4 完成但 D4-T 未通过，不能对外写“R2 已关闭”。当前最小 fail-closed 通过也不等于完整 D4/R2 通过。

## §7 W3 接入：唯一可支付库存读口

### 7.1 契约（逐字沿用 D3 §6.9）

```java
interface WagePayableInventoryLookup {
  Optional<Map<CommodityId, Long>> payableInventory(
      ActorRef payer,
      io.mosire.simos.production.contract.ProductionLocation settlementLocation);
}
```

- **输入**：`payer`（付款人 actor；工资链中为 organizer 家户）与 `settlementLocation`（既有 `production.contract.ProductionLocation`）。
- **输出**：该付款人在该结算地点**最终可支付**的逐商品数量（毫商品单位），按 `CommodityId.value` 升序冻结为不可变表；只保留 `>0` 的项，缺项 = 0。
- **`Optional.empty()`（R-05，写死）**：仅当 ① D4 lookup 尚未接线/明确 disabled；② payer 无法解析为已登记家户账户（`AccountSession` 无该户）；或 ③ 实现明确不可用。**不是“余额为 0”**。商品余额为 0、没有 ACTIVE `InventoryReservation`、没有某商品或该地点当前无货 ⇒ 返回**空 `Map`**。location 为 `null`/`UNSPECIFIED`/非法 `<q>_<r>` ⇒ 不进入 lookup，按 `asset-location-missing` fail-closed。
- **空 `Map`**：读口可用，但该 payer×location 没有任何可支付商品（与 `Optional.empty()` 严格区分）。
- **payer 级地点预检由 W3 负责**：D4 lookup 不负责把同一 payer 的多地点义务合并或拆分；W3 若发现同一 payer >1 个 settlement location，必须在任何 lookup/支付前整组拒绝 `wage-payer-location-unsupported`。
- **W3 不二次扣减（库存腿）**：W3 只能把该返回值当作共享库存预算；不得再用 `AccountSession` 的**商品**余额、`frozenGoods`、`OwnershipStake` 或 `cycleInputUsedMilli` 对库存腿做第二次减法。货币腿仍按 D3 直接用 `AccountSession` 的 `money − frozenMoney`，不受本条影响。
- **唯一实现 owner（R-16）**：`WagePayableInventoryLookup.java` 由 **D4-I 唯一新增/实现**；W3 只消费 D4-I 已编译的读口，不新增/不实现该文件，不复制第二套库存减法。批次 D4-I→D4-T→W3。
- **W3 不重定价**：lookup 只回答数量；零价/缺价/抵物折价由 W3 的 D3 §6.4 处理，D4 不内置价格。

### 7.2 权威实现算法

对每个 `(payer, settlementLocation)`：

```text
// 账户权威：AccountSession 的户级账户快照（不是 HouseholdInventory 直接结构）
account = AccountSession.snapshot().householdAccount(payer)
if lookup disabled / account 不存在 / payer 不是已登记家户:
    return Optional.empty()
if settlementLocation 为 null / UNSPECIFIED / 非法 <q>_<r>:
    fail-closed asset-location-missing   // 不进入余额查询

for commodity c:
    balance = account.goods.getOrDefault(c, 0)
    frozen  = account.frozenGoods.getOrDefault(c, 0)
    reserved = Σ InventoryReservation{
        payer == payer,
        location == settlementLocation,
        commodity == c,
        status == ACTIVE,
        fromTick <= currentTick < toTickExclusive
    }.quantity
    if frozen + reserved > balance:
        throw 契约故障 inventory-reservation-overcommitted（ERROR，不降级）
    payable[c] = balance − frozen − reserved
    只返回 payable[c] > 0 的项；全部为 0/无商品 ⇒ 空 Map（不是 Optional.empty）
```

> **同账户、两地、一份库存（R-05）**：`AccountSession` 是**户级**余额/冻结，没有地点级独立库存；因此同一 payer 若同时有两个 settlement location 的工资义务，不能各调一次 lookup 后各自花同一份余额。W3 必须在 lookup 前按 payer 聚合；>1 location ⇒ 整组 `wage-payer-location-unsupported`，不做部分支付。D4 不实现跨地点合并，也不实现“按地点切账户余额”。

- **冻结**由 actor 账户所有者的 `AccountSession.frozenGoods` 提供；D4 不复制冻结表，也不直接读 `HouseholdInventory` 造第二本账。
- **活动占用/预留**由 D4 `InventoryReservation` 提供；D4 不复制余额。
- **权益/再生产资料**：`OwnershipStake` 是资产权益账，不是商品库存；LAND/CATTLE/SMALL_TOOL/LARGE_TOOL 等 `AssetKind` 不会出现在返回 map 中。D4 不需要、也不允许把 `OwnershipStake.quantity` 当成 `CommodityId` 商品；若某商品确实被用于再生产，必须由 `InventoryReservation` 表达并从余额中扣除。**`TOOL` 不是当前 `AssetKind`；任何 payload/命令出现 `TOOL` ⇒ 具名 `asset-kind-retired:TOOL`，不得解析成任一新值。**
- **已投入资料**：`ProductionProcess.cycleInputUsedMilli` / 实际已转出的投入是“已消费”记录，不是账户余额；若账户余额已在同一 revision 扣除，W3 不再减一次。只有仍留在账户、尚未转出的投入才以 ACTIVE `InventoryReservation` 存在。
- **不得**引入不存在的“最后成交价”或其他未落盘市场事实；D4 lookup 不读市场价格、不读 `MarketTable`，估值留给 W3/D3 §6.4。
- 读口是纯读：不写账户、不写占用、不改变 `revision`、不产生 intent。

### 7.3 W3 消费顺序

W3 对本次 tick 的全部工资义务先按 payer 聚合，再做地点预检；只有通过预检的**唯一** `(payer, settlementLocation)` 组才进入以下步骤：

0. **payer 级地点预检（R-05）**：同一 payer >1 个 settlement location ⇒ 整组 `wage-payer-location-unsupported`，零支付/零 lookup/零新债/零 carry 变化，不做部分支付；不得逐地点继续；
1. 在支付前**恰好调用一次** `payableInventory(payer, location)`，把返回表作为该组共享库存预算快照；
2. 先按 U21 支付对应货币：`min(dueRemaining, account.money − account.frozenMoney)`；货币腿仍由 W3/actor 账户所有者落账；
3. 货币不足时，按 D3 §6.3 的**初始可用数量降序、同量 `CommodityId` 升序**处理商品；每个商品的数量只能从 lookup 快照扣一次，W3 不再从账户余额或 `frozenGoods` 另减；
4. 按 D3 §6.4 折价计算本次抵付；缺价/0 价/折后 0/不可交付 ⇒ skip 该商品并保留剩余 due；
5. 余款按 D3 §6.5 成债（工资债零息由 D3/U23 决定）；
6. 记录 `due = paidMoney + Σ paidBarterValue + arrears`，三数守恒。

### 7.4 同 payer × location 预算（B4）

- **首期唯一合法形态（R-05）**：同一 `(payer, settlementLocation)` 的工资义务共享一份库存预算；该组只调用一次 lookup。W3 在支付/lookup 前按 **payer** 聚合全部工资义务；同一 payer 有 >1 个不同 settlement location ⇒ **整组**具名 `wage-payer-location-unsupported`，零支付/零 lookup/零新债/零 carry 变化，不做部分支付。
- **不得跨格合并/抵付**：不得把两个 location 的库存合并，也不得用 A 格库存抵 B 格工资。
- **账户权威**：`AccountSession` 是户级余额/冻结的唯一读面；同一户在不同 location 的义务不得各读一份同一余额后重复付。首期用整组拒绝消除该风险。
- 工资义务的稳定序仍由 D3 负责：`(unit, role, household)` 升序；D4 只提供库存预算。

### 7.5 W3/资产失败词表

| 词 | 级别 | 语义 |
|---|---|---|
| `wage-inventory-scope-unavailable` | INFO（D3 非失败标记） | D4 lookup 未接/不可用；货币腿仍可付，库存腿全部跳过，余款成债；不得回退整账户余额 |
| `wage-payer-location-unsupported` | INFO/业务拒绝 | 同一 payer 本次 tick 的工资义务出现 >1 个 settlement location；W3 在任何 lookup/支付前整组拒绝，零部分支付/零新债/零 carry 变化 |
| `asset-kind-retired:TOOL` | INFO/解析拒绝 | 已退役 `TOOL` 输入；不得静默映射为 `SMALL_TOOL`/`LARGE_TOOL` |
| `wage-barter-price-missing:<commodity>` | INFO skip | W3 缺价/0 价；该商品不抵付，债务不消失 |
| `wage-barter-discount-zero:<commodity>` | INFO skip | 折后单位价值 ≤0；该商品不抵付 |
| `inventory-reservation-overcommitted` | ERROR | 冻结+活动预留 > 账面余额；契约故障，拒绝整个候选 revision |
| `asset-occupancy-unavailable` | INFO/业务拒绝 | 需要 D4 预算但读口不可用；不得退回整池扫描 |
| `asset-occupancy-overlap:<share>:<unit>` | INFO/业务拒绝 | 同一份额、同一时段出现重叠占用请求/授予 |
| `asset-location-missing` | INFO/业务拒绝或 ERROR（契约入口） | 份额/实例/请求地点缺失、为 `UNSPECIFIED`，或不是规范 `<q>_<r>`（如 `foo`）；不得进入 allocation/lookup |
| `asset-location-scope-missing:<location>` | INFO/业务拒绝 | 该地点没有匹配的实物份额；不跨格借用 |
| `asset-short:<kind>` | INFO/业务拒绝 | 该作用域有供给但本次请求拿不到正授予 |
| `asset-occupancy-request-missing:<unit>` | INFO/业务拒绝 | 无法给出非资产请求量；不默认 1/整池 |
| `production-unit-id-collision` | ERROR（构造/命令） | 多地点实例使用同一个 `ProductionUnitId`；不得静默覆盖 |
| `asset-relocation-unsupported` | INFO/业务拒绝 | D4 首期不支持同一份额跨格搬迁 |
| `asset-scope-shared:awaiting-D4` | **仅 D4 前历史/最小 fail-closed** | 当前 R2 最小边界；D4 通过 D4-T 后从运行时删除 |

`WagePayableInventoryLookup` 返回 `Optional.empty()` 本身不是“付清”，不得写 0 库存成功；W3 必须用 `wage-inventory-scope-unavailable` 表示“库存腿不可用”。

## §8 模块边界、状态归属与 ChangeSet

### 8.1 状态归属

| 内容 | 唯一权威 | D4 允许做什么 | D4 禁止做什么 |
|---|---|---|---|
| 方案定义、技术条件、实例、实例地点、周期进度、周期经验/贡献 | **production** | 读 `IndustryDefinitionView.capacityPerUnit/cycleDays`、`ProductionProcess.location/progressDays`、probe/execution contract | 不在 economy 复制定义/实例/效率；不在 D4 写 production 状态 |
| 实物资产份额、owner/operator/kind/quantity/location | **economy**（`OwnershipStake`，当前唯一实物账） | 增加 `location` 最小组件；读/校验；D4 占用表引用 share id | 不新建第二本资产数量账；不由 production/Social 复制；不改变 owner/operator 以修 R2 |
| 统一资产占用请求/授予/释放/未分配、库存预留、工资可支付库存读口 | **economy**（D4 新增状态） | 新增 grant/reservation 表、ChangeSet/codec、分配纯函数、lookup 实现 | 不把 grant 当资产数量；不让其他模块直接改这两张表 |
| 商品/货币余额与冻结、真实转移落账 | **actor 等账户所有者** | 只读 `AccountSession` 快照；生成 intent 交账户所有者提交 | 不直接改账户 Map；不把 `frozen` 当生产占用；不铸币补工资 |
| 生存需求、人口、饱食度、饥饿与劳动能力 | **Social** | 只读既有 Social 事实；D4 不碰 | 不改人口/饱食度/阶层；不让资产占用决定 Social 公式 |
| 组合时序、跨域原子提交、只读编排 | **app/Core** | 把同一 revision 的 economy/production/actor ChangeSet 组合提交；提供只读视图 | 不写资产分配/工资/估值公式；不自己扫份额 |

**结论**：D4 的资产占用状态（`AssetOccupancyGrant` / `InventoryReservation`）归 **economy**；生产定义/实例/技术归 **production**；资产份额的唯一数量权威仍是 economy 的 `OwnershipStake`；账户余额/冻结仍归 actor；Social 不碰资产；app/Core 只编排。U8 的“D4 归生产方式模块”落实为 production 的生产计算/实例职责；U23 的“统一分配/经营关系”落实为 economy 的 D4 占用分配，二者不矛盾。

### 8.2 新增/修改状态组件

D4 首期在 `EconomyData` 增加两个组件。当前编号链以 D2/D3 已冻结的最新形状为准：32 `productionRosters` / 33 `productionWageDefaults` / 34 `productionWagePostings` / 35 `wageCarryRemainders`；D4 接在其后：

1. `Map<AssetOccupancyGrantId, AssetOccupancyGrant> assetOccupancies`（第 36 个）；
2. `Map<InventoryReservationId, InventoryReservation> inventoryReservations`（第 37 个）。

不得沿用旧文里的“第 40 个 marketRoundStats”编号（那是 D2/D3 落盘前的历史组件计数）；实现以当前 `EconomyData` 实际组件序号和 D2/D3 的 32–35 为准。

同时 `OwnershipStake` 加 `location` 组件；这是已有组件的字段扩展，不是新账本。

必须同步：

- `EconomyChangeSet` 两个 `FieldDelta` 组件；
- `between`、`apply`、`isEmpty`、`codec`、`EconomyRoundTripTest` 的反射组件枚举；
- seed / GM adjust / payload 的 `assetShares` 新 `location` 字段；
- `EconomyReferenceValidation` 对 share location、grant/reservation 引用与 scope 的跨表校验；
- `ProductionProcessBook` / `SettlementIndex` / `ExpectedProfitBook` / `LaborQueueBook` / `EconomySettlement` / `DebtCapacityBook` / `MarketDemandBook` / `EconomyEnterpriseSettlement` / `HouseholdClassRule` / app 只读视图的读取源。

新组件的空表语义：`empty` = 没有占用/预留，不能读成“读口不可用”；读口不可用必须由 `AssetOccupancyBudget` 缺失或显式 `Optional.empty()` 表达。

### 8.3 ChangeSet / codec / 往返（铁律 5）

- `OwnershipStake.location` 必须进入 `EconomyChangeSet.assetShares` 的 `OwnershipStake` 值；`apply(base, changeSet)` 必须逐字段重建 target，包括 location。
- `AssetOccupancyGrant`、`InventoryReservation` 的所有持久字段（id、share/unit/payer/location/commodity/quantity/from/to/status/releasedAtTick）都必须进 ChangeSet 与 codec。
- **不持久化** `AssetOccupancyRequest`、`AssetOccupancyBudget`、`AssetOccupancyView`；它们是 revision 内派生值。
- 保序不可变：`LinkedHashMap` + `Collections.unmodifiableMap`，不用 `Map.copyOf`；遍历顺序必须是内容的纯函数。
- 新 id 必须确定性；打开/重放/分支得到同一批 id。禁止随机、时钟、UUID、进程计数器。
- 旧档按 Alpha 纪律作废；不写旧档兼容、键归一或双读。往返只要求**同一版本自洽**：新代码写出去的档，新代码逐字段读回。
- `AssetOccupancyGrant` 与 `InventoryReservation` 的 map 键必须等于行内 id；否则构造期具名抛。
- `grantsToWrite` / `releases` 与最终 ChangeSet 的差量必须可由 `between(base,target)` 重建；不得手写“派生结果”绕过 ChangeSet。

### 8.4 命令、写口与读口

**D4 首期不新增玩家玩法命令**。占用生命周期由既有生产创建/执行/关账/取消与统一分配驱动：

- 内部写口：`AssetOccupancyBook.allocate(...)` 产出 `grantsToWrite`/`grantsToRelease`，经 economy 的 `EconomyChangeSet` 提交；`InventoryReservation` 由生产投入承诺/实际转出的同一 revision 写入或释放（可落在一个 economy 内部 `InventoryReservationBook`，但它不是资产分配器）；
- 资产份额写口：既有 `OwnershipStakeBook` / `EconomyGovUnitUpserts` / `EconomyTransferOwnershipStake` / `EconomyEntrySettlement` / seed / GM adjust，统一要求 `location`；D4 不另开资产数量写口；
- 内部读口：`AssetOccupancyLookup`（unit/scope 的 allocations、committed、unallocated、rejections、releases）；
- W3 读口：`WagePayableInventoryLookup`（§7）；
- app 只读：展示 per-unit allocations、per-scope available/occupied/unallocated、released 和 rejection，不自己扫份额/账户重算。

若后续需要人工释放/修复命令，必须另立 spec；D4 首期不把“GM 可改占用”偷偷塞进实现。

### 8.5 日志

- INFO：grant 创建/释放/回填的 unit/share/location/asset/quantity/from/to/status；W3 lookup 不可用；跨格预算拒绝；
- DEBUG：逐 scope 的 supply/committed/demand/unallocated、比例分配余数、续占结果、rejection 原因；
- TRACE：逐 share 授予拆分行、逐商品 payable 快照；
- 业务拒绝 = INFO 具名；契约/跨切片一致性故障 = ERROR 不降级；日志失败不得影响状态；
- 日志不得写敏感载荷明文，不得用异常文案代替稳定 failure word。

## §9 不变量（D4-I）

1. **D4-I1 唯一数量真相**：`OwnershipStake.quantity` 是资产数量唯一权威；`AssetOccupancyGrant` / `InventoryReservation` 只是引用/预留，任何求和都不得生成新的资产总量。
2. **D4-I2 地点闭合**：任何生产占用必须同时满足 `share.location == unit.location == request.location`；地点必须是规范 `<q>_<r>`；缺地点、非法格式或 `UNSPECIFIED` 一律 fail-closed `asset-location-missing`，禁止跨格借用。
3. **D4-I3 同份额不超占**：同一份额在任何时段的全部 ACTIVE grant 数量之和 ≤ `share.quantity`；同一 scope/时段不得存在重叠的冲突授予。
4. **D4-I4 单实例不变**：某个 scope 只有一个**已存在** unit 且没有请求时，其分配量逐值等于该 scope 的实有量；候选/新实例不得借用该兼容例外，缺显式 `requestedScale` ⇒ `asset-occupancy-request-missing:<unit>`，不得默认 1/整池。
5. **D4-I5 多实例整数守恒**：每个 scope/asset kind 恒有 `committed + newGrant + unallocated == available`；比例分配用整数最大余数，Σ授予 ≤ 实有。
6. **D4-I6 时域锁定**：`CYCLE` 授予在其区间内不被后到实例抢走；`TICK` 授予只在 `[today,today+1)` 生效；到期/取消必须释放并回填。
7. **D4-I7 同源**：试算、预计利润、劳动排队、执行、债务容量、需求簿、app 读口都读同一份 `AssetOccupancyBudget`；不得有第二个资产分配器。
8. **D4-I8 统一预算（沿用既有 I8）**：同一份劳动/资产/库存不得被两个方案或两个实例重复占用；被缩减/释放的资源必须继续参与后续分配。
9. **D4-I9 全腿原子**：同一候选的资产授予、劳动、投入、账户转移、生产进度/经验与产出必须在同一 Revision；任一腿失败不得留下半状态（沿用既有 I11）。
10. **D4-I10 权益不入库存**：产权/使用权/再生产资料不是活动商品库存；W3 的返回表不得出现 `OwnershipStake` 的资产种类，`OwnershipStake` 也不得被扣成负数。
11. **D4-I11 W3 不二次扣减**：库存可支付量只来自 D4-I 实现的 `WagePayableInventoryLookup` 已扣完结果；W3 只消费、不新增/不实现该文件；W3 不得再减冻结/占用/权益，不得回退整账户余额。
12. **D4-I12 payer 级地点闭合**：W3 在任何 lookup/支付前按 payer 聚合；同一 payer >1 个 settlement location 时整组 `wage-payer-location-unsupported`，零部分支付/零 lookup/零新债/零 carry 变化；单地点组一份库存预算；`AccountSession` 户级一份余额不得被两地各读一次。
13. **D4-I13 确定性**：全部遍历按稳定 id/规范序；无随机/时钟/UUID；同输入得到同 allocations/unallocated/rejections/ChangeSet。
14. **D4-I14 往返**：`OwnershipStake.location`、grant、reservation 全部进 ChangeSet/codec；`apply(changeSet, base)` 逐字段重建 target；不兼容旧档。
15. **D4-I15 失败不降级**：业务拒绝 INFO 具名；契约/跨切片故障 ERROR，整候选 revision 拒绝；共享 scope 不得静默回退整池。
16. **D4-I16 日志**：新增资产阶段必须按 AGENTS §一.9 有 INFO/DEBUG/TRACE 载体；缺日志视为未完成。

## §10 验收判据

> 以下判据由 **D4-T 独立测试 owner** 执行；D4-I 只写生产代码到编译。测试必须按本文判据编写，不得按实现反推断言。数值均为示例，单位以现有量纲为准（LAND=千分亩，商品=毫单位）。

### 10.1 正向判据

| # | 判据 | 预期 |
|---|---|---|
| P1 | 单实例、`LAND=100`、`capacityPerUnit[LAND]=10` | unit 得到 100；规模 10；与当前单实例逐值相同；`unallocated=0` |
| P2 | 同 `industry/operator/location` 两实例，`LAND=100`，各请求 50 | A=50、B=50；Σ=100；规模各 5；总规模 10，旧错误 20 不再出现 |
| P3 | 同 scope 两实例，各请求 100，`LAND=100` | 比例分配 50/50；`unallocated=0`；Σ=100；生成两条 grant，均引用同一 share |
| P4 | 同 `industry/operator`，不同 location，`0_0` 只有 100 份额 | `0_0` 实例得 100；`3_4` 实例得 0 且 `asset-location-scope-missing:3_4`；Σ=100 |
| P5 | 同 `industry/operator`，不同 location，各自有 100 份额 | 两地各得 100；Σ=200（真实总量为 200，合法） |
| P6 | 同地点、一个 share 100，A 请求 40、B 请求 60 | A=40、B=60；unallocated=0；Σ=100 |
| P7 | 同地点、一个 share 100，A/B 各请求 80 | 50/50；unallocated=0；Σ=100（整数最大余数） |
| P8 | `CYCLE`（cycleDays=3）A 已占 LAND=100 `[0,3)`，t=1 出现 B | A 仍得 100；B 得 0/`asset-short:LAND`；不得在周期中途抢走 A |
| P9 | t=3，A 周期结束释放 | A 的 grant `RELEASED`；`unallocated` 增加 100；B 在 `[3,6)` 可获 100 |
| P10 | `SMALL_TOOL=2`，A 在 `[0,1)` 占用 1，B 在 `[0,1)` 占用 1 | 两条 grant 各 1；ΣSMALL_TOOL=2；无重叠超占；horizon=TICK |
| P11 | `SMALL_TOOL` A 只在 day0 用、B 只在 day1 用 | day0 A grant 1；day1 A 释放后 B grant 1；互不重叠（TICK） |
| P11b | `LARGE_TOOL` A 在 `[0,cycleDays)` 占用 1，B 在 day1 请求 | A 保持；B 得 0/`asset-short:LARGE_TOOL`；周期结束释放后 B 可在下一周期请求（horizon=CYCLE） |
| P12 | 释放/回填 | 释放 receipt 数量 = grant 数量；下一预算的 `unallocated` 立即包含该数量；不修改 `OwnershipStake.quantity` |
| P13 | `WagePayableInventoryLookup`：余额 1000、冻结 200、活动预留 300 | 返回商品 500；`OwnershipStake.LAND` 不出现；`cycleInputUsedMilli` 不二次扣 |
| P14 | W3：due=10、货币=4、lookup 返回 grain=6、折后单位价值=1 | 付 4 货币 + 6 粮，arrears=0；三数守恒 10=4+6+0 |
| P15 | W3：due=10、货币=4、lookup 可用但 grain 缺价/0 价 | 付 4；grain 跳过并记 `wage-barter-price-missing:grain`；arrears=6 |
| P16 | W3：due=10、货币=4、lookup 返回 `Optional.empty()` | 付 4，库存腿跳过，记 `wage-inventory-scope-unavailable`，arrears=6；不得用账户余额 1000 抵 6 |
| P17 | W3：同一 payer×location 两笔各 due=6、lookup 返回 grain=10 | 第一笔 6、第二笔 4、第二笔 arrears=2；只调用一次 lookup，共享预算不重复 |
| P18 | W3：同一 payer 两 location（R-05） | 在 lookup/支付前整组 `wage-payer-location-unsupported`；零部分支付、零 lookup、零新债、零 carry 变化；不得按 location 各读一份户级余额 |
| P18b | 同账户、两地、一份库存不得双付 | `AccountSession` 只有一份 `grain=1000`，payer 在 A/B 各有 due；预检必须在 lookup 前整组拒绝，**两个地点都不调用 lookup**、实际支付=0、无新债；若实现逐地点 lookup 或把同一 1000 各算一次 ⇒ P18b 红 |
| P19 | 预计/实际同源 | 同一 revision 的 probe 与 execute 的 `assetOccupancy` 逐单位、逐 kind 相等；实际产出差异只能由最终劳动/投入/效率解释 |
| P20 | I8 全腿 | 资产 grant、劳动、投入、账户/产出、进度/经验同一 revision；故意让一条腿失败 ⇒ 该候选零残留，释放的资产立即回填 |
| P21 | 确定性与往返 | 同输入运行两次 allocations/unallocated/rejections/ChangeSet 规范序逐值相同；`apply(between(base,target),base)==target`，codec 写出再读回逐字段相等；`SMALL_TOOL` 与 `LARGE_TOOL` **各自**经 payload→state→codec→apply 往返相等；`TOOL` 输入 ⇒ `asset-kind-retired:TOOL`；任一新值缺 horizon ⇒ `asset-occupancy-horizon-unsupported:<kind>` |
| P22 | 原 R2 复现 | `owned=100` 时两实例实占合计 ≤100；显示值和实际 execution 都不能出现 200；单实例仍 100 |
| P23 | `requestedScale` 默认边界（R-10） | 已存在单实例、无请求 ⇒ §5.4 第 2 步整池兼容；候选/新实例显式 `requestedScale=2`、`capacityPerUnit=10` ⇒ 请求 20；候选/新实例无显式 scale ⇒ `asset-occupancy-request-missing:<unit>`，不得默认 1、不得整池 |
| P24 | `OwnershipStake.location` 全入口（R-08） | `rg -n 'new OwnershipStake' simos-economy/src/main simos-app/src/main` 的每个构造点都带 location；`EconomySeeder` 每条 `assetShares` seed 都带合法 location；编译通过且创世不出现 `asset-location-missing`；漏一入口 ⇒ 红 |
| P25 | location 格式（R-15） | `foo`、`1`、`1_`、`a_b`、`01_2`、`+1_2`、`-0_1` 均 ⇒ `asset-location-missing`；`0_0`/`-3_2` 合法；非法值不得进入 allocation/lookup |

### 10.2 负向 / 变异判据

| 变异 | 必须变红 |
|---|---|
| 把 `(industry,operator)` 整池复制回每个 unit | P2/P3/P22 |
| 忽略 `share.location`、允许不同地点共用同一份额 | P4/P5 |
| 允许同一 share 两个重叠 ACTIVE grant | P3/P8/P10/P11b |
| 把 `SMALL_TOOL`/`LARGE_TOOL` 都套同一 horizon（或漏掉任一 kind 的 horizon） | P10/P11/P11b/§10.4 |
| 接受已退役 `TOOL` 或把 `TOOL` 静默映射成 `SMALL_TOOL`/`LARGE_TOOL` | P21/文档验收 |
| 用平均值 50/50 代替请求量比例（在请求 40/60 时） | P6 |
| 单实例被 D4 清零或只给请求量 | P1 |
| 用 `probe.assetRequired` 反推 `requestedScale` | P19/确定性 |
| 候选/新实例缺 `requestedScale` 时默认 1 或整池 | P23 |
| 非法地点 `foo` 被当合法，或直接进 lookup 才失败 | P25 |
| `OwnershipStake` 任一构造点/seed 入口漏 `location` | P24/编译扫描 |
| 到期不释放 / 释放不回填 | P8/P9/P12 |
| 执行前不检查 grant，progress/经验先写后拒绝 | P20 |
| 共享 scope 静默回退整池扫描 | P19/P22 |
| D4 lookup 再减一次冻结或预留 | P13/P14 |
| W3 再减一次 `frozenGoods` / `OwnershipStake` | P13/P14/P16 |
| W3 用账户原始余额代替 lookup | P16 |
| lookup `Optional.empty()` 被当成“库存 0 但已付清” | P16 |
| 跨格工资预算合并/跨格抵付 | P18 |
| 用 `HashMap` 遍历决定并列/余数 | P21 |
| 漏掉 `OwnershipStake.location` 或 grant interval 的 codec/ChangeSet 字段 | P21 |
| 把 `asset-scope-shared:awaiting-D4` 当 D4 完成后的正常运行词 | §6 文档验收 + 扫描 |
| 把 D4 设计写成“R2 已关闭”而无 D4-T 证据 | 交付文档验收红 |

### 10.3 数值例（至少这些，不得只做算术口头）

**例 A：同地点两个实例，请求 50/50**

```text
OwnershipStake: LAND=100, location=0_0, industry=farm, operator=hh-1
capacityPerUnit[LAND] = 10
A 请求 50，B 请求 50
S=100, D=100 ⇒ A=50, B=50, unallocated=0
A 规模 = floor(50/10)=5，B 规模=5，总规模=10
旧错误：A=100,B=100 ⇒ 总规模=20
```

**例 B：同地点两个实例，请求 40/60**

```text
S=100, D=100 ⇒ A=40, B=60, unallocated=0
A 规模=4，B 规模=6，总规模=10
```

**例 C：不同地点，只有一份份额**

```text
share: LAND=100, location=0_0
A: location=0_0 ⇒ 100
B: location=3_4 ⇒ 0 + asset-location-scope-missing:3_4
Σ=100；不得把 0_0 的 100 复制给 3_4
```

**例 D：跨周期锁定**

```text
cycleDays=3，A 占 LAND=100 [0,3)
t=1 B 请求 100 ⇒ B=0（asset-short:LAND），A 仍=100
t=3 A 释放 ⇒ unallocated 增加 100
t=3 B 请求 [3,6) ⇒ B 得 100
```

**例 E：W3 可支付库存**

```text
账户 goods[grain]=1000，frozenGoods[grain]=200
D4 InventoryReservation{ payer=hh-1, location=0_0, commodity=grain, quantity=300, ACTIVE}
WagePayableInventoryLookup(hh-1, 0_0) = {grain: 500}
OwnershipStake{LAND:100} 不出现在返回表
ProductionProcess.cycleInputUsedMilli[grain]=100 不二次扣（已经转出）
```

**例 F：W3 抵物与缺价**

```text
due=10，货币=4，lookup={grain:6}，grain 折后单位价值=1
paidMoney=4，paidBarterValue=6，arrears=0

同例 grain 缺价/0价：
paidMoney=4，grain skip（wage-barter-price-missing:grain），arrears=6
```

**例 G：W3 lookup 不可用**

```text
due=10，货币=4，lookup=Optional.empty()
paidMoney=4，库存腿跳过（wage-inventory-scope-unavailable），arrears=6
不得回退用账户 goods[grain]=1000 抵付
```

**例 H：同 payer×location 共享预算**

```text
lookup(hh-1,0_0) = {grain:10}，只调用一次
义务1 due=6 ⇒ grain 6，剩余库存预算 4
义务2 due=6 ⇒ grain 4，arrears=2
Σgrain=10，不重复扣
```

**例 I：释放回填与 I8**

```text
A 占 LAND=100 [0,3)
t=1 取消 A ⇒ release 100，unallocated=100
B 请求 [1,4) ⇒ 得 100
A 的 progress/经验/投入/账户与 B 的 grant 同 revision；若 B 任一条腿失败，A 释放与 B 授予都不得半提交
```

### 10.4 零/缺价/不可用与 I8 全腿专项

- **零价**：lookup 返回商品数量；W3 不因价格 0 把数量从 lookup 中删掉；W3 记 `wage-barter-price-missing` 或 `wage-barter-discount-zero` 并保留债务。
- **缺价**：lookup 不读市场、不因缺价返回 empty；W3 跳过该商品，余款成债。
- **不可用**：lookup 返回 `Optional.empty()`，W3 不调用账户余额兜底；货币腿仍可付，余款成债。
- **I8 全腿**：资产 grant、劳动分配、原料抽取、账户转移、生产进度/经验/产出必须同 revision；负向注入任一腿失败，所有腿零残留；缩量释放量必须被后续候选重新使用，不能“留在空缺”。
- **`AssetKind`/location 交付扫描（R-01/R-08/R-15）**：D4-I 编译前必须交 `rg -n 'AssetKind\.TOOL|"TOOL"' simos-*/src/main`（应仅文档/拒绝词，不含词表常量）与 `rg -n 'new OwnershipStake' simos-economy/src/main simos-app/src/main`（每点带 location）清单；`EconomySeeder` 的 `assetShares` 每条 seed 都带规范 location；`SMALL_TOOL`/`LARGE_TOOL` 两个 horizon 都有配置且各自往返。缺一项 = D4-I 未完成。

## §11 文件所有权与 D4 批次

> 一个文件同一时刻只能有一个 owner；D4 不并行改共享定义/D3/W3 文件。下面是**方向与禁止面**，具体类/方法拆分由 D4-I owner 在实现架构账本中定。

| 批次 | owner 可写 | 禁止/边界 |
|---|---|---|
| **D4-D0** | 本文（控制方） | 只写设计；不改 D1/D2/D3/status/R2 设计/decision/precheck/adjudication；不写代码/测试；不跑 Maven |
| **D4-I（economy/资产占用，唯一写代码 owner）** | `simos-economy/src/main/java/io/mosire/simos/economy/model/OwnershipStake.java`；`time/OwnershipStakeBook.java`（两处 `new OwnershipStake`）；`spi/EconomyGovUnitUpserts.java`；`spi/EconomyPayloads.java`（`addOwnershipStake` 的 location 载荷/解析）；`time/EconomyEntrySettlement.java`；`time/EconomySettlement.java`（份额构造点）；`simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`（`ownershipStakeNode` 及其全部调用点，seed 必须带 location）；新增 economy 的 `AssetOccupancyGrant/Id/Status`、`InventoryReservation/Id/Status`、`AssetOccupancyBook`、**`WagePayableInventoryLookup`（唯一新增/实现 owner）**；`EconomyData.java`、`change/EconomyChangeSet.java`、`codec/EconomyCodec.java`、`EconomySeedHandler.java`、`EconomyReferenceValidation.java`；`time/SettlementIndex.java`、`ProductionProcessBook.java`、`ExpectedProfitBook.java`、`LaborQueueBook.java`、`DebtCapacityBook.java`、`MarketDemandBook.java`、`EconomyEnterpriseSettlement.java`、`HouseholdClassRule.java`；必要时 app 只读视图适配 | **只编译，不写/不跑测试**；不修 D3/W3 支付公式；不改 POM/XML/enforcer/模块边界；不改 `ProductionProcess`/`ProductionLocation` 等 production 共享定义（如确需改，先停手报控制方）；**不改 `AssetKind`（词表归 C0-A）**；不直接改 actor Map；不新增独立土地账；不把 grant 当资产数量；不提交/同步；必须逐点改完 `OwnershipStake` 构造/seed 入口，漏一入口即编译/创世红 |
| **D4-T（独立测试 owner）** | `simos-economy/src/test/**`、`simos-app/src/test/**`（如 D4-I 未改 production 契约，则无需 production 测试）；D4 证据/台账 | 按 §10 判据写测试，不按实现反推；不改 `src/main/**`（变异自证必须先声明/还原并 md5 对照）；不改 D1-D4 设计正文；不由实现 owner 写/跑 |
| **W3（后续）** | **只消费 D4-I 已编译的 `WagePayableInventoryLookup` 读口**与既有 D3 契约；在 D4-I/D4-T 后串行开始 | **不新增/不实现 `WagePayableInventoryLookup.java`**；不实现第二套库存减法；W3 在 lookup/支付前按 payer 聚合，同一 payer >1 settlement location ⇒ 整组 `wage-payer-location-unsupported`，不做部分支付；不改 D4 占用/grant 文件；W3 的支付/抵物/债务公式仍按 D3/U23 |
| **R2 关闭** | D4-T 结果 + 控制方复核 | 只有在 §6.2 全部门通过后，才可删除 `asset-scope-shared:awaiting-D4` 并声明 R2 完整修复；D4-D0 本身不关闭 R2 |

**批次顺序**：D4-D0（本文）→ D4-I（economy 编译通过）→ D4-T（独立测试，含原 R2 复现）→ 控制方复核 → 才允许 W3 接入与 R2 关闭。D4-I 不并行碰 D3/W3 文件；W3 不并行碰 D4 状态文件。

> **`EconomySeeder.java` 的串行边界**：该文件会出现在 C0-A（仅 `AssetKind` seed 字面量）、C3（名册/装配）与 D4-I（`OwnershipStake` seed 的 `location`）三个不同批次，但**绝不允许并行**；每次只允许当前批次 owner 按列明字段改，前一 owner 交账并停手后下一 owner 才可开工。C0-A 不写 location，D4-I 不重写名册/装配语义。

## §12 未做 / 未验证 / 开放项

### 12.1 本轮明确未做

- 本文件只是设计：**没有写/改生产代码，没有写/改测试**。
- **没有跑 Maven、没有跑测试、没有跑仿真、没有跑门禁**。
- 没有修 R2 完整功能；当前 R2 仍只是“共享 scope fail-closed”的最小实现，不能写“R2 已关闭”。
- 没有实现 `WagePayableInventoryLookup`；D3 §6.9 仍只是契约，不是可调用实现。
- D4-D0 最初落盘时没有修改 D1/D2/D3/status/R2 设计/decision/precheck/adjudication；**2026-10-17 D1–D4 复核修正轮另行局部修改了 D1/D2/D3、D3 裁决文件、重规划与 status（仅设计/计划/状态文档），仍没有改 R2 最小设计/decision/precheck 历史事实**；没有提交、推送、stash、checkout、merge、同步，也没有触碰主树。
- 没有新增任何用户玩法裁定；本文所有比例/时限/接口/failure word 均为工程默认或工程契约。

### 12.2 开放但不阻塞 D4-I 的工程项

1. 占用 horizon 的默认表（D4-E2）是可配置工程默认；用户若给出新的农业/工具制度，应另立裁定并更新配置，不在实现里硬编码第二套。
2. 多实例比例分配在“请求量相等”时天然接近平均；这不是用户选定的公平制度，只是当前的确定性工程预算规则。若用户以后要求其他分配公平性，另立 spec。
3. 资产跨格搬迁、动态抢占、跨周期提前解约、GM 人工改占用、Wage payable 的定价/折价参数仍不在 D4 首期。
4. D4-I 若发现必须新增 production 共享定义字段（例如 per-asset horizon 由方案定义提供），必须停止并上报，不得为了编译在 D4-I 并行改 D1/production contract；当前设计已用 economy 策略避免该依赖。
5. `WagePayableInventoryLookup` 的 UI 呈现、日志字段细节、性能缓存不在本文冻结；但“只读、不写、不跨 revision 陈旧”的边界不可变。

### 12.3 交付声明

本文件的完成标准是“设计可读、可验收、可派单”，不是“功能已实现”。任何声称 D4/R2/W3 已完成、已通过、已关闭的说法，都必须有 D4-I 编译记录 + D4-T 独立测试证据 + 控制方复核；当前没有这些证据。
