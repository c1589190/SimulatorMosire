# D-032 D1–D4 独立设计复核报告（2026-10-17）

> **性质**：只读独立复核。未修改设计正文、未改代码/测试、未跑 Maven/测试/仿真、未提交/合并/推送。
>
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
>
> **复核基线**：`HEAD 4b49009d`；复核开始时 `git status --porcelain=v1` 为空。
>
> **复核范围**：D1/D2/D3/D4、R2 最小设计、U23 裁决，以及 `docs/agents/02-一-并发.md` §一.8/§一.8.1/§一.10/§一.12；并回核 `EconomyData`、`OwnershipStake`、`ProductionLocation`、`AssetBudgetBook`、`AccountSession`、`MarketTable` 与 D2/D3/D4 新增类型的当前零实现事实。

## 结论

**不通过。**

本批没有发现 P0，但发现多项 P1。它们会让实现代理在关键路径上自行选择互相冲突的规则（组件号、时间/量纲、生存预测、跨地点库存、用户原话收录、文件 owner），当前不适合按“可派单”标准冻结。建议控制方将本报告交回 D1/D2/D3/D4 设计 owner 做最小修订后复评；在 P1 关闭前不派 D4-I/W3/C2 实现单。

## 一、发现清单

| ID | 级别 | 问题 | `file:line` 证据 | 影响 | 建议最小修正 | 归属 |
|---|---|---|---|---|---|---|
| R-01 | P1 | D4 仍使用已退役的 `TOOL`，与 D1 冻结的 `SMALL_TOOL` / `LARGE_TOOL` 词表冲突。 | `docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md:116-120,140`；`docs/superpowers/specs/2026-10-17-d032-asset-occupancy-design.md:249,271,565,713-714` | D4 的 horizon 策略、负向判据和 P10/P11 数值例针对不存在的 `TOOL`；若 D4-I 按 D1 落形，`SMALL_TOOL`/`LARGE_TOOL` 没有 horizon；若保留 `TOOL`，D1 的解析器又要求具名拒绝。D4-I/D4-T 无法在同一套枚举上通过。 | 把 D4 所有 `TOOL` 改为 D1 的两种新值并分别定义 horizon；补 `TOOL` 输入被拒、两种新值各自可往返的判据；C0 同步所有旧引用与 seed。 | D1/D4 |
| R-02 | P1 | D2 的不变量 `D2-I2` 与正文的 `MONEY_WAGE` 例外直接矛盾。 | `docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md:456,661`；同文件 `:49` 也明确 `MONEY_WAGE` 可无 participant | 名册要么必须为工资角色伪造 placeholder（正文明确禁止），要么违反 D2-I2；C2 的校验与测试无法同时通过。 | 把 D2-I2 改成“每个 `PRODUCT_SHARE` 角色至少一个 participant；`MONEY_WAGE` 角色可无 participant，需求由 posting/default 表达”；全库检索并同步该不变量引用。 | D2 |
| R-03 | P1 | D2/D3 的“无第 36 个”绝对句与 D4 的 36/37 组件冲突。 | `docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md:54`；`docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md:133,189,481,837`；`docs/superpowers/specs/2026-10-17-d032-asset-occupancy-design.md:627-630`；状态文件亦有旧“无第 36”记录 `docs/superpowers/status/2026-10-17-d032-production-runtime-migration-result.md:162`，同文件 §12 又写 D4 36/37（`:171`） | 组件号是 ChangeSet/codec/往返测试的硬契约。实现代理可能认为 36 被禁止、把 D4 的两个组件误编号，或让 `EconomyRoundTripTest` 在 32–35 与 37 之间反复漂移。 | 明确范围：D2/D3 只新增 32–35，D4 新增 36/37；删除“无第 36 个”的绝对句；状态文件加一条当前组件号更正。 | D2/D3/D4/控制方 |
| R-04 | P1 | `SurvivalOutlook` 定义了预计货币/实物/产品份额收入，但覆盖计算没有把它们接入；预测收入实际上无法参与“能否换到下一 tick 生存所需”的判断。 | `docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md:405-418`（输入字段）；`:462-472`（覆盖算法，只明确使用 `spendableMoneyMilli` + `localFacts`，没有说如何加入 `expectedMoneyIncomeMilli` / `expectedInKindMilli` / `expectedProductShareMilli`）；验收例 `:1297` 又要求“最好所得 1”被识别 | U23-b 的核心例子“所需 10、工作所得 1”无法被可执行地算出；实现可能只看当前库存而把有工资/产品份额收入的家户误判为 `idleEligible=true`，或者在另一实现中重复计入收入；两种结果都会改变 `IDLE_CHOSEN`。 | 在 §2.5.1 写明逐需求行算法：先把 `expectedInKindMilli`/`expectedProductShareMilli` 作为尚未入库的实物池加入，并做去重；再把 `expectedMoneyIncomeMilli` 按币种加入可用于 `localFacts` 的购买预算，只有可达、正价、有供给时才折算；明确去重和已入库扣减；为 1/10、0/10、组合可达三例增加正负判据。 | D3 |
| R-05 | P1 | `WagePayableInventoryLookup` 的“地点”与账户余额来源没有闭合：同一家户的账户是户级账，查询只减当前地点的 `InventoryReservation`，同一笔余额可在两个地点分别被算成可支付。 | `docs/superpowers/specs/2026-10-17-d032-asset-occupancy-design.md:84`（`AccountSession` 只有户级余额/冻结、没有 location 级库存）、`:246`（写成 `HouseholdInventory`，与 §2 的 `AccountSession` 事实不一致）、`:547-559`（只减 `location == settlementLocation` 的 reserved）、`:583-586`（同 organizer 不同 location 分开预算）；D3 契约 `docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md:1097-1102` | D4 §7.4/B4 要求“不得跨格拿库存”，但算法允许：同账户 `goods=1000`，A/B 各只看到本地点预留，两个 lookup 都返回大部分同一笔粮，W3 可重复支付；这直接违反 U16/U21 的库存不足成债与同一份库存不双付。 | 二选一并写死：① W3 首期若付款人存在 >1 settlement location 的工资义务，在 lookup 前整组拒绝 `wage-payer-location-unsupported`；或② 让 lookup 以地点级物理库存/预留为唯一余额来源，并减去所有地点的 ACTIVE 预留后只能由一个地点消费。同步统一 `AccountSession`/`HouseholdInventory` 的权威来源与 `Optional.empty()` 条件；增加“同账户、两地、一份库存不得双付”判据。 | D4/D3 |
| R-06 | P1 | D1/D2 直接采用 U23 规则，却没有在“用户原话附录”逐字收录 U23（D2 还直接采用 U23 的工资债/生存/折价三项）。 | D1：`:5` 输入写 U13–U21，`:10-13,69` 使用 U23，`:188-206` 只有 U13–U21；D2：`:6` 输入写 U13–U21，`:15-20,36-38` 使用 U23，`:58-79` 只有 U13–U21。U23 原文见 `docs/superpowers/specs/2026-10-17-d032-wage-idle-barter-decision.md:87`，D3 已收录于 `:36-38` | 违反 §一.8.1“派单文档必须原样收录全部相关用户原话”；实现代理可能把 U23 的三项当作控制方建议或被 D1/D2 旧口径覆盖，且控制方无法据此证明设计未失真。 | 在 D1/D2 的独立“用户原话附录”中逐字补 U23（如仍涉及 R2 优先级，也补 U22），并把控制方解释与引文分开；不得用摘要替代。 | D1/D2 |
| R-07 | P1 | D1 的 `standardLaborMilliPerCycle` 注释仍是“千分劳动/周期”，与 D1 顶部更正注和 D2/D3 已统一的毫小时口径冲突；D1 §1 的 `laborPerUnit` 也仍用“千分劳动/区域”。 | `docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md:8`（统一毫小时，旧“千分劳动·日”注释作废）对 `:28,45`；`docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md:247`；`docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md:224` | 同一字段可能被实现成 1000 倍差异；候选工资、劳动目标、经验归一化和资产/劳动规模会同时漂移，且单值测试未必能发现。 | 把 D1 §2.2 的 `standardLaborMilliPerCycle` 改成“毫小时/周期”；审计 D1 §1 D-I8 `laborPerUnit` 与所有 `labor*` 字段的量纲；增加跨文档常量/数值例（1 小时 = 1000 毫小时）。 | D1 |
| R-08 | P1 | `OwnershipStake` 增加必填 `location` 的跨文件 owner 不完整：D4-I 表没有列出仍构造 `OwnershipStake` 的 `OwnershipStakeBook`、`EconomyGovUnitUpserts`，也没有明确 app seed 的写入 owner。 | D4 要求所有写入入口带 location：`docs/superpowers/specs/2026-10-17-d032-asset-occupancy-design.md:143-167,183,662`；D4-I owner 表 `:848`；当前构造点 `simos-economy/src/main/java/io/mosire/simos/economy/time/OwnershipStakeBook.java:387-394,466-473`、`simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyGovUnitUpserts.java:449-457`；app seed 的资产入口 `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java:2571-2572,3460` | D4-I 若严格只改 owner 表内文件，必因其余构造点编译失败；若自行改表外文件，又违反“一文件一 owner”。开发者还会漏掉创世份额 location，导致新世界直接 `asset-location-missing`。 | 在 D4-I 明列所有 `OwnershipStake` 构造/seed 入口（至少 `OwnershipStakeBook`、`EconomyGovUnitUpserts`、`EconomySeeder`/app 只读与 seed 适配），或把 app seed 拆分给另一个明确 owner 并列入批次顺序；补编译与创世判据。 | D4 |
| R-09 | P1 | D1 冻结 `AssetKind` 改词表，但 D2 的 C0 owner 表没有给 `simos-actor-api/.../AssetKind.java` 及旧引用一个 owner。 | `docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md:115-120,154,165`；`docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md:751`（C0 可写只有 economy-api 的 `ProductionRoleId` 与 production contract）；当前枚举仍为 `simos-actor-api/src/main/java/io/mosire/simos/actor/api/asset/AssetKind.java:8-15`（`TOOL` 尚在） | C0 代理没有合法写面完成 D1 的冻结词表；C1/C2/C3 会继续面对 `TOOL` 旧值，或各自临时改 actor-api 形成并行写冲突。 | 在 C0 owner 或单独 C0-A 里明确 `AssetKind.java`、economy/ seed 旧引用与测试 owner 的写面；把 `TOOL` 退役扫描作为 C0 交账项。 | D1/D2 |
| R-10 | P1 | D4 的 `requestedScale` 对“无劳动/投入通道”既允许默认 `1`，又要求候选/新实例无法提供时拒绝“不得默认 1”；两条规则互相矛盾。 | `docs/superpowers/specs/2026-10-17-d032-asset-occupancy-design.md:370-374`（两通道都缺失时取 1）对 `:376`（候选/新实例无法提供目标规模 ⇒ `asset-occupancy-request-missing:<unit>`，不得默认 1/整池） | 候选/新实例在调用方没有劳动或投入预算时，可能被实现为规模 1，也可能被拒绝；这会改变多实例比例分配、实际占用和生产规模，且 D4-I/D4-T 无统一期望。 | 明确默认 `1` 只适用于“已存在单实例且无请求的兼容路径”，候选/新实例必须由选业目标显式给 `requestedScale`；否则一律 `asset-occupancy-request-missing`，并补正负例。 | D4 |
| R-11 | P1 | D3 要求“旧工资欠款不再 20‰”，但没有定义旧工资债与普通贷款的识别/迁移机制；旧路径又明确保留。 | `docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md:62,200-201,591-593,1036-1037,1420`；`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:1981-1995`（旧欠款资本化路径）；D3 仅说工资债须有来源（`WAGE_PAYMENT` / terms / 结算单），未给旧合同字段或迁移名单 | 实现者为落实 0‰ 可能把所有 `legacyDefault` 欠款都改零息，误伤普通贷款；也可能对旧工资债无能为力，继续 20‰，直接违反 U23-a。 | 增加可执行的来源识别（例如已有债务合同字段/专用 `DebtSource`、或 W3 接管的显式旧工资债迁移表）；明确普通贷款继续原 terms；删旧路径或给旧路径一次性迁移规则；补“工资债 0‰、普通贷款仍 20‰”的独立判据。 | D3 |
| R-12 | P1 | 控制方裁决文件仍把 `allowIdle=true` 和“抵物固定 bid”作为待用户拍板的建议，未标 U23 已撤回/取代；这是仍可被实现代理当作有效口径的 `allowIdle` 出现。 | `docs/superpowers/reports/2026-10-17-d032-d3-precheck-adjudication.md:41-51`（U-D3-1 0‰、U-D3-2 `allowIdle` 默认 true、U-D3-3 bid）；U23 原文 `docs/superpowers/specs/2026-10-17-d032-wage-idle-barter-decision.md:87`；D3 已改口 `docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md:36-38,62-64,76,94` | 用户已明确 `allowIdle`/`householdLaborChoices` 只允许历史“已撤回”出现；该文件把两者写成仍待拍板的控制方建议，可能再次触发重复询问或错误实现。 | 在裁决文件头部加“U23 已取代 §5.1”的明确标记，并把 U-D3-2/U-D3-3 行标为历史；有效设计只保留 D3 的 U23 口径。 | 控制方 |
| R-13 | P2 | D1 的 `ProductionRoleId` 正文包名仍是 `production.contract`，顶部更正注和 D2 已改为 `simos-economy-api`。 | `docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md:34` 对同文件 `:8`；`docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md:208-217,751` | C0 实现代理若只读 §2.1 会把 ID 放进错误模块，触发 Maven 环或重复类型。 | 直接改 D1 §2.1 为 economy-api 包；保留顶部历史注作为变更记录，不再保留两个有效包名。 | D1 |
| R-14 | P2 | D1 的 `UNALLOCATED` 正文写“留在可分配池”，D2 写“全部归 organizer residual”，虽然 D1 顶部注已局部指向 D2，但正文仍可被单独引用。 | `docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md:108`；`docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md:536`；D1 顶部注 `:8` | 实现代理可能把未分配余数留在无主池，或反过来把 organizer 当唯一 residual，影响逐户库存与守恒读数。 | 把 D1 §2.6 的 `UNALLOCATED` 说明改成“角色池外未分配；最终按 D2 §8.3 归 organizer residual”；补同一数值例（400/400/100）。 | D1/D2 |
| R-15 | P2 | D4 说 `OwnershipStake.location` 是“实际所在格”，但没有定义规范格式/校验；现有 `ProductionLocation.parse` 只校验非空白，不校验 `<q>_<r>` 或整数。 | `docs/superpowers/specs/2026-10-17-d032-asset-occupancy-design.md:160-161,393`；`simos-production/src/main/java/io/mosire/simos/production/contract/ProductionLocation.java:31-49`（parse 仅非空白；类注称格式守卫属生成侧） | seed/GM 写入 `foo` 或非法格键可能通过构造，随后 `(location, scope)` 匹配和 `AssetShareId.idOf` 生成不同但无效的地点；R2 复现可能被“看起来有地点”掩盖。 | 在 D4 入口明确使用业务格键生成/校验（`<q>_<r>`、q/r 整数范围），把 `asset-location-missing` 扩为非法格式；补非法 `foo` 负向判据；或复用现有 `IndustryUpserts` 格式守卫并给出唯一入口。 | D4 |
| R-16 | P2 | `WagePayableInventoryLookup` 的写 owner 在 D3 W3 与 D4-I 两处重叠。 | `docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md:1400`（W3 可写/新增 `WagePayableInventoryLookup.java`）；`docs/superpowers/specs/2026-10-17-d032-asset-occupancy-design.md:848`（D4-I 新增该文件） | 两批若按文本同时派单会争同一文件；W3 可能重复定义读口或改动 D4 权威实现，违反一文件一 owner。 | W3 改为“只消费 D4-I 已编译的读口”；D4-I 是唯一新增/实现 owner；批次顺序保持 D4-I→D4-T→W3。 | D3/D4 |
| R-17 | P2 | D1 没有像 D2/D3/D4 一样明确写“未实现、未跑 Maven/测试/仿真”，只写“测试由独立 owner 写/跑”。 | `docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md:3,215`；对照 D2 `:765`、D3 `:1415`、D4 `:859-860` | 读者可能把 D1 的“形状冻结”误当成已落形/已验证；交账时缺少与其它分册一致的未验证声明。 | 在 D1 末尾增加一段“本册只写设计；未实现、未编译、未跑测试/仿真/门禁；C0/C1/T 证据另立”。 | D1 |
| R-18 | P2 | 重规划仍写旧 `W1→W2` 顺序，D3 已改成 `W2a→W1→W2b→W3→W4→W5`；状态文件也保留旧的“无第 36”段。 | `docs/superpowers/plans/2026-10-17-d032-production-line-replan.md:78-80` 对 `docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md:132,1391`；`docs/superpowers/status/2026-10-17-d032-production-runtime-migration-result.md:162` 对同文件 `:171` | 控制方派单时若引用重规划旧顺序，会出现 W1 先于 default/posting 状态、或 W2b 先于 W1 的错误依赖；状态文件的旧组件号可能再次污染实现。 | 给 replan/status 加“由 D3/§12 与 D4 局部取代”的头部标记，或直接更新为当前有效顺序/组件号；不要把旧版本当历史依据。 | 控制方 |

## 二、跨文档矛盾重点

### 2.1 组件号与状态编号

**存在 P1 矛盾。** 当前 `EconomyData` 源码逐组件计数为 31（`simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:310-397`；测试侧当前也断言 31：`simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java:549`）。D2 的 32、D3 的 33/34/35 与 D4 的 36/37 本身设计上相接，但 D2/D3 多处“无第 36 个”的绝对句和状态文件的历史句没有同步，见 R-03/R-18。D4 的 36/37 不能按“无第 36”被删除。

### 2.2 时间尺度（A1）

**未发现 D2/D3 正文矛盾。** D2 §8.2 的候选工资应收/应付乘 `cycleDays`（`cooperative-production-design.md:518-519`），D3 §5.1/§5.2 同样乘（`wage-chain-design.md:884,906`），实际 W3 due 按本次 tick 实际劳动、不乘（`:934-950,1255`）。当前问题是 D1 的旧“千分劳动/周期”注释与统一毫小时冲突（R-07），不是 A1 公式本身。

### 2.3 idle / `allowIdle` / `SURVIVAL_IDLE_POLICY`

D1–D4 设计正文中，`allowIdle`/`householdLaborChoices` 只作为“已撤回/历史”出现；当前源码没有这两个名字（`rg` 零命中）。`idleEligible`（资格）与 `idleChosen`（选择结果）在 D3 §2.5/§4.4 已分离，默认策略 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` 与替代策略 `BEST_EFFORT_WORK` 已标明为控制方工程默认，不是用户裁定。正收入 1/需求 10 的目标行为在 D3 有 P30/P30b/P36 载体。

但控制方裁决文件仍残留 `allowIdle=true` 的“待用户拍板”建议（R-12）；且 `SurvivalOutlook` 的收入接入未闭合（R-04），所以“语义表已写对”不等于“判据可执行”。

### 2.4 D4 / R2 / W3

D4 对 R2 的最小边界表述是清楚的：当前 `AssetBudgetBook` 的共享 scope fail-closed 只阻止整池复制（`asset-occupancy-design.md:461-471`），D4 的退役门五项明确（`:473-488`），原复现判据含实际执行而非只看显示（`:490-502`），并明确 D4-D0 不关闭 R2（`:861`）。这部分未发现“把 D4 写成 R2 已关闭”的问题。

W3 的 `WagePayableInventoryLookup` 形状、`Optional.empty()` 与空 `Map` 区分、W3 不二次扣减、地点使用 `production.contract.ProductionLocation`、同 payer×location 一次快照，在 D3/D4 文字上是对齐的（D3 `:1082-1102`；D4 `:521-568`）。但账户余额来源和跨地点库存预算没有闭合（R-05），且读口文件 owner 有重叠（R-16）。因此 **D4/R2/W3 接口方向可保留，当前不能按“可派单”冻结**。

### 2.5 抵物折价与模块边界

**未发现 D1/D3 的折价规则冲突。** D1 只允许 `LOCAL_BID` / `LOCAL_ASK`，缺价/0 价/折后 0/缺币种 skip 留债，系数可 `<1`，production 只定义规则、economy 执行（`production-definition-v2-freeze.md:73-88`）；D3 §6.4 逐项复述同样语义（`wage-chain-design.md:987-1022`）。设计正文与当前扫描中未发现 `LOCAL_LAST_TRADE`、`referencePriceOf` 或 `SettlementLocationRef`；`MarketTable` 当前确有 `hasPrice`/`bidPriceOf`/`askPriceOf`（`MarketTable.java:181,269,281`）。

### 2.6 模块边界与文件所有权

生产定义/实例归 production、经济分配/结算归 economy、账户落账归 actor、Social 生存需求归 Social、app/Core 只编排的总方向在 D1/D2/D3/D4 一致。但 owner 表存在两个实质缺口：D4 的 `OwnershipStake.location` 构造/seed 入口未完整列 owner（R-08），D1 的 `AssetKind` 词表变更没有落到 C0 owner（R-09）；另有 W3/D4-I 读口文件重叠（R-16）。这些不是“方向错”，而是派单会因写面不完整而失败或产生并行写冲突。

## 三、已核范围与“未发现问题”项

以下项已核，未发现额外问题（“未发现问题”仅表示本轮静态复核未找到，不是功能验收通过）：

1. **当前 31 组件**：`EconomyData.java:310-397` 逐组件计数为 31；`EconomyRoundTripTest.java:549` 当前断言 31。D2 的 32、D3 的 33–35、D4 的 36/37 与当前 31 的衔接方向正确（仅 R-03 的旧绝对句需清理）。
2. **U23 三项的用户状态**：D3 §0.2 与 D4 §1 均把欠薪 0‰、下一 tick 生存条件不从业、可配置折价标为用户已定；D1/D2 的 U23 引用内容本身与 U23 原文一致（问题仅在 D1/D2 原话附录缺失，见 R-06）。
3. **`idleEligible` / `idleChosen` 分离**：D3 §2.5/§4.4 已明确资格不等于选择，默认 idle 策略下正收入 1/需求 10 ⇒ `IDLE_CHOSEN`，`BEST_EFFORT_WORK` 下正收益工作可被选中，idle 不作为普通 `Offer` 经过 `queueable()`，未发现“永远不可能胜出的 idle 候选”文字仍有效。
4. **A1 公式**：D2 §8.2、D3 §5.1/§5.2 的候选周期窗口与 D3 §6.1 的实际 tick due 规则一致，未发现 D2/D3 正文把实际 due 乘 `cycleDays`。
5. **U20 降价边界**：D3 的 `satisfiedStreak > decreaseDelayTicks` 与 §3.4、§3.6、§8.4 P5/P35、§8.6 例 3 一致；`streak == delay` 不降、`delay+1` 首降。
6. **BarterDiscountPolicy**：D1/D3 只出现 `LOCAL_BID` / `LOCAL_ASK`，无 `LOCAL_LAST_TRADE`/`referencePriceOf` 残留；缺价/0 价/折后 0/缺币种 skip 留债、系数可 `<1`、production 定义与 economy 执行边界已写明。
7. **工资债三数守恒和同日排除**：D3 §6.3/§6.5/§6.7 写明 due = 货币 + 抵物价值 + arrears、同日新债排除、`WAGE_PAYMENT` 与唯一账户写口；普通贷款与工资债的区分在文字上已写明（实际旧债识别缺口见 R-11）。
8. **D4 资产唯一数量权威**：`OwnershipStake.quantity` 仍是唯一资产数量，`AssetOccupancyGrant`/`InventoryReservation` 被明确写成引用/预留而非第二本数量账；D4 未新增玩法命令；D4 对 R2 的 fail-closed 和“不宣告 R2 已关闭”表述正确。
9. **W3 lookup 基本契约**：`WagePayableInventoryLookup` 使用 `ActorRef + production.contract.ProductionLocation`，返回已扣 frozen + ACTIVE reservation 的最终商品量，`Optional.empty()` 与空 `Map` 区分，W3 不二次扣减；这些文字与 D3 §6.9/D4 §7.1 对齐（跨地点余额来源问题单列 R-05）。
10. **正向/负向/数值例**：D2、D3、D4 都有正向、负向/变异、数值例；D1 有正向/负向与若干 400/400/200、3:2 例子，但缺少统一的“未实现/未跑”尾注（R-17）。

## 四、当前源码事实核验（只读）

- `EconomyData` 当前 31 组件：`simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:310-397`；`EconomyRoundTripTest.java:549` 断言 31。
- `OwnershipStake` 当前只有 `id/industry/asset/owner/operator/quantity/kind` 七个组件，无 `location`/占用期：`simos-economy/src/main/java/io/mosire/simos/economy/model/OwnershipStake.java:35-42`。
- `ProductionLocation` 已存在，构造/parse 只校验非空白，`UNSPECIFIED` 为读侧防御：`simos-production/src/main/java/io/mosire/simos/production/contract/ProductionLocation.java:31-49,67-71`。
- R2 最小 fail-closed 已实现：`AssetBudgetBook.java:44,106-153`；`SettlementIndex` 构造时只算一次：`SettlementIndex.java:62-68,179-183`。
- `AccountSession` 的账户主体是家户，只有商品/货币余额与冻结，没有 location 级库存：`AccountSession.java:30-38,45-59,147-154`；`AccountPartitionKey` 只有 `household`：`AccountPartitionKey.java:24`。
- `MarketTable` 有 `hasPrice`/`bidPriceOf`/`askPriceOf`，没有最后成交价：`MarketTable.java:181,269,281`。
- 当前 `src/main`/`src/test` 零实现：`ProductionRoleId`、`ProductionRoster`、`AssetOccupancyGrant`、`InventoryReservation`、`AssetOccupancyBook`、`WagePayableInventoryLookup`、`ProductionWageDefault`、`ProductionWagePosting`（按类/record/interface 搜索均零命中）。
- 当前 `allowIdle`/`householdLaborChoices` 在源码零命中；设计正文只作为撤回/历史出现；但裁决文件残留 R-12 所述旧建议。

## 五、复核限制

- **只读**：未修改 D1/D2/D3/D4/R2/decision/status/replan 设计正文；本报告是唯一新增文件。
- **未跑**：未运行 Maven、测试、仿真、门禁；所有“可执行/可验收”判断均基于静态文字与源码事实，不是行为证据。
- **未实现**：未写/改 Java、XML、POM、测试；未派实现单；未提交、合并、推送、stash、checkout，未触碰主树。
- **未验证**：未验证 D4-I/D4-T 的编译、实际分配、W3 支付、SurvivalOutlook 行为、R2 多实例/跨周期世界；未验证任何数值例的运行结果。

## 六、交账：`git status --porcelain=v1`

```text
?? docs/superpowers/reports/2026-10-17-d032-d1-d4-design-review.md
```
