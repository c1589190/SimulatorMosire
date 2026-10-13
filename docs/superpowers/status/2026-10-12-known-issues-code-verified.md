# 已知问题总表（回代码核实版，2026-10-12）

> **本文取代**：`docs/archive/status/2026-10-11-economy-line-closeout.md`（本文是其逐条回代码核实；2026-10-13 归档）。
> **这是什么**：对 `2026-10-11-economy-line-closeout.md`（经济线收口）与
> `2026-10-23-planned-not-implemented-inventory.md`（计划内未实现总清单）的**逐条回代码核实**：
> 哪些仍成立、哪些已过时、哪些要加限定。**这是目前最新的口径**（AGENTS §四.1「以最新落盘文档为准」）。
>
> **方法**：4 个只读核查代理（① 经济·钱/政策 ② 经济·市场/迁移 ③ 非经济模块 ④ 工程/日志/测试）
> + 控制方自核；全部结论带 `file:line`。控制方抽查 4 处（`simos-app/pom.xml`、`Industry.java`、
> `Replay.java`、规模算式）复核一致。
> **未跑 Maven、未起服务、未跑世界** ⇒ 本文所有**运行期数字**均为历史实测/文档自述，**未复核**。
> **权威**：与代码冲突以代码为准（AGENTS §四）。
>
> **用户原话（逐字，2026-10-12）**：
> 1. 「额，那要不你看看本地的最新代码，再下定论？？」
> 2. 「直接告诉我目前哪里好了哪里没好，我实在不想碰经济模块了，我看原则上没问题可以通过改测试数据解决就不碰了」
> 3. 「改文档，如实记录问题，提交并推送」

---

## 0. 总账

| 项 | 数 |
|---|---|
| 核过的文档说法 | **54 条** |
| **仍成立** | **39 条** |
| **要加限定 / 改写** | **14 条** |
| **整条作废（其实早已做完 / 不存在）** | **1 条**（铸币生产方式 M1） |
| 顺带抓出的文档措辞 / 计数错误 | **16 处**（§6） |

**一句话结论**：经济模块**原则上没问题** —— 机制齐全、两个世界 0→360 都能跑完、守恒与铁律 5 往返都验过。
"没好"的只剩三类：**① 标定数值不好看（改世界数据即可）② 观测面小口子（改代码但很小）
③ 已裁定推迟的未来机制**；**没有"跑不起来 / 算错 / 机制缺失"这一类的缺陷**。
用户 2026-10-12 裁定：**经济线不再碰逻辑**，只在"拧已生效的旋钮 + 改创世/测试数据"这一层动手。

---

## 1. 经济：已经好了（别碰）

| 能力 | 实测证据（历史读数，本次未复跑） |
|---|---|
| 市场机制（§2.1 自适应定价 + 债务式清算 + 取用权 + 排序） | 144 轮**零成交轮次 0**；Σ成交 3,337 / 1,751 / 5,131 三套世界；96 轮分布指纹守卫在守 |
| **铸币 = 生产方式**（W5） | `Industry.java:144 moneyOutputPerUnit`；`EconomySeeder.java:4066-4074` 有产出/劳动/工具/规模；`MintSettlement` 落 `MINT_PRODUCTION`；`seignioragePerCycle` 已从 `Government` 删除 |
| 货币市场（钱是市场里的一个商品，W5.5） | 轮序 = 挂商品 → 挂钱 → 借钱 → 买货；利率随供需；国库默认不挂钱 |
| 债务语义（W8） | 溢出**不再致命**（世界寿命 690 → 1400+）；户绝**双向销账**；空户不计息 |
| 运力与跑商管道（W7） | 运力池**全局共享**（跨区/跨币）；`haul` 进自适应定价；按需建商号 |
| 转业（W9） | 100% `PROFIT_WEIGHTED`；带库存随迁（守恒、从不带 haul）；按库存定档 |
| GM 政策写口（W10） | 政策写 `{}`→`{…}` 跨 advance 与重启存活；**9 键中 4 键有运行时读取点** |
| 守恒与不变量 | 资本份额总量不变；逐币种 Δ0；B2 三形态 md5 全等（差异叶 0/81,703）；铁律 5 往返 0 |
| 门禁（2026-10-11 23:45） | 16/16 模块；**3,479 条 / 0 失败 / 0 错误 / 5 跳过**；SpotBugs 15/15 = 0；前端 412/412 |

---

## 2. 经济：没好，但**改世界数据 / 标定**就能解决 ⇒ 不碰代码

| 现象（历史读数） | 真正的旋钮 | 现值 / 备注 |
|---|---|---|
| **结构性缺粮**：8–87 户长期 100% 吃不上、占 **85% 死因**；人口 sw −31.0% / tp −22.6% | 世界标定：亩产/土地、初始口粮、城镇分布、家户初始工具与粮 | "谁先拿货"由排序定（区名次 → 家户**净值降序** → hex → actor → 商品，`MarketSettlement.java:3960-3980` / `SettlementOrderBook.java:208-213`）—— 排序没错，是分子分母不对 |
| **借贷成交率 0.02%**（需求中位 3,294,504 毫 vs 放贷池中位 **977 毫**） | 家户货币禀赋；政府持银比例（创世 **97.42%**） | 钱没在能放贷的人手里 |
| **跑商 0 户**（`MERCHANT_TRADE_INDUSTRY_CREATED` = 0） | 真闸 = `market.hasPrice(TOOL) && idleToolInHex>0`（`ModeMigrationSettlement.java:1949-1952`）；资本份额只在**城镇格**播种（`EconomySeeder.java:3590/4751-4802`） | 工具取得走 TENANCY、**不花钱**（`:1062-1108`）⇒ 改创世城镇数与工具投放即可试 |
| **外格粮运不出**（sw 7,525 笔 / 5,888 万毫；tp 92.5 万笔） | 运力池只覆盖 **2 格** + 半径 PORTER 2 / SELF 4 / BOSS 8（`MerchantCapacity.java:281-282`） | 改世界运力与城镇密度 |
| 运费 / 承运成本常数 | "粗估、可由 GM 改"（`MarketSettlement.java:8598-8607`） | 25‰ 一类 |
| **铸币产出率、滑落三常量** | **GM 政策键已生效，直接拧**（`MINT_MILLI_PER_FURNACE_CYCLE`、`SLIDE_SPEED/SLIDE_COST/SLIDE_ROUND`，`ClassSlideSettlement.java:118-120` / `MintSettlement.java:238-240`） | 无需改代码 |

> ⚠️ **一个必须说清的分界**：9 个政策键里**只有 4 个真生效**。另外 5 个（**饥荒上限、铸币劳动份额、
> 借贷基准利率、转业上限、转业斜率**）各自模块读的是**编译期常量**（`FamineMortality.java:48`、
> `EconomySeeder.java:599`、`MarketTable.java:103`、`ModeMigrationPolicy.java:162` / `:134`）
> ⇒ 想改这三个数值**必须先接读取点（碰代码）**。不想碰就按现值接受：
> 饥荒上限 **1000**、铸币劳动份额 **30‰**、借贷基准 **20‰**、转业上限 **100‰**、转业斜率 **100**。
> ★ 其中 `MINT_LABOR_SHARE_PER_MILLE` 键**连创世也没有读取点**（创世读 `EconomySeeder.java:599` 自己的常量）。
>
> ★ 另一个易误读处：`CatalogTool.java:472-475` 会列出 9 键与**缺省值** —— 那是**写口文档，不读当前状态**；
> `economyPolicies` 至今**没有产品读口**（GUI / QueryServer / 读工具都看不到）。

---

## 3. 经济：没好、且真要改代码（小口子 / 结构债 / 待裁定 / 已推迟）

| 条目 | 为什么躲不掉代码 | 量级 |
|---|---|---|
| 5 个政策键接上读取点 | 见 §2 分界 | 5 处、各 1–2 行 |
| `economyPolicies` 产品读口 | 无任何读面 | 1 个读口 |
| 成交额读口改名义口径 | 仍读现金（`ApiViews.java:2771-2777`；`MarketSettlement.java:2286/2309` 两栏都在） | 1 处 |
| 铸币"上一轮印量"三点口径 | 跨币种求和 / 零印轮不写 / `FISCAL_ISSUE` 不计（`MintSettlement.java:392-408/487-494`） | 1 处 |
| 未成交归因兜底档加子档 | `ALGORITHM_UNCOVERED` **5 处兜底无子档**（`MarketSettlement.java:8982/9068/9086/9436/9446`）；`LOGISTICS_CAPACITY` 已有具名 `logisticsDetail`（`:7996-8001`） | 观测面 |
| 货物守恒加"毛产 / 损耗 / 投入"读数 | `FlowRow` 只有 `income`/`consumed`；`gross()/losses()` 只在当日瞬态账本（`ProductionLedger.java:570-577`） | 观测面 |
| `HexTradeCost` 货币侧 = 0 | `HEX_TRADE_COST_MILLI_PER_UNIT = 0L`，单区内不产生货币运费 | 1 常量 + 接线 |
| 运力只关账日重置；有运力池时区内撮合**退化单线程** | `MarketSettlement.java:1402-1416`（`anyCycleClosed`）、`:4437-4440`（`matchWithinRegionsSerial`） | 中等 |
| 家户结构 5 条 | 出生=0 链（两套生死引擎未统一）、GM 读口缺、创世库存按 `population × rationDays` 折算、`unmetPersonDays` 全局量级、`expectedNeedMilli` 线性外推 | 大（结构债） |
| **户绝者的资产归属** | 债务已双向销账（`EconomySettlement.java:4880`，只销债）；**资产无接管/继承/没收**，`:6811-6816` 明写"留在原主体账上，不没收、不蒸发"；判据已改为"人口 >0→0 且 deaths>0"且**不排除** `hh-gov-*`/`hh-unit:*` | 先裁定 |
| 首日给养时序 | 消费早于俸禄日结（`PopulationEconomyTimeParticipant.java:641` vs `:1004`）；第 1 天无市场轮（依赖"所有产业 `cycleDays=120`"）；代码明文"不新增逐日给养通道" | 先裁定 |
| 规模上限守卫（Σ份额 ≤ 该格上限） | 规模算式有**三份同式副本**（`ProductionProcessBook.java:125-131`、`SettlementIndex.java:456-462`、`ExpectedProfitBook.java:900-902`）；`industry.capacity()` 运行期只用于旧档归一（`EconomyData.java:919/3982`），**没有守卫** | 用户已裁定"以后再做" |
| 未成交归因之外的 INV 群 | 铸币读数口径、成交额口径、政府身份链（`GovernmentHouseholds.java:48-58` 无命名空间校验）、创世 4 户两侧人口不一致、S-11 日志发射点少（`EconomySettlement.java:5985/5870`） | 小 |
| 文化/宗教（riba、无息合同）、伪币/成色/铸熔 | **明确未做的功能**（economy 与 social `main` 内 culture/religion/community/riba 0 命中；`MoneyInstrument.java:18` 等明写"M4+"），不是缺陷 | 立项级 |

---

## 4. 经济之外：好 / 不好

**好了**：门禁全绿；11 模块日志面收口；gov 行政服务模式 + 税制/财政闭环；决策包 D1–D5；外交三件套；
WebUI 412/412；大地图性能；`unit.SetVisionRadius`；P4a/P4b（军俸政策）已落地。

**不好**（20 条核实后 18 条原样成立，全部有 `file:line`）：

| 面 | 条目 |
|---|---|
| 军事 / Unit | 城防/围攻多阶段**不存在**（`CombatRecord.java:42` 只有 7 组件、`City.java:29` 只有 props、全仓 `fortif/城防` 0 命中）；morale / supply / training **0 字段**（`UnitStatus.java:12` 三态，RESTING 就是 500‰）；**无按 due 自动派发**（`DecisionDueRunner` 0 命中）；**unit 级拆/并原语缺**（`UnitOperations.java:1753/1808` 只动编制树；装备只有整体 set + 增量 adjust，无转移/守恒原语）；**`SdCommandDrain` 未接生产 advance**（`Shell.java:1450` 有 `advanceAndDrain`，但只有 Shell 自身 + 4 个测试调用；`AdvanceTool.java:121` / `GuiServer.java:1718` 直连 `core.submit`；`RestUntil` 0 命中）；装备类型化/目录（D-006/R2） |
| 行政 / 区划 / 世界 | `DeleteCity` 不清理 economy 引用（`CityOperations.java:22-23` + `DeleteCityHandler.java:38-39` 承认悬空）；`UpdateCity` 未知键静默（`:83-102`）；`DeleteNation` 无 Directive 级联入口（`:102-103`）；**免税/期限实体不存在**（唯一税率命令载荷只有 `id/regionId/ratePerMille`）；**Region 可重叠、无跨 GOV 去重**（`RegionOperations.java:30-31` + `JurisdictionDailyTax.java:194`）；`region.seed` 无城市落点；`CreateCity` 拒 `population`；**迁都不搬人**（`MoveCapitalPlan.java:65-66/242-246`）；区划下游重算 Shell 之外 0 调用；单格命名不存在（`nameHex` 0 命中） |
| MCP / 工具 / UI | 四个读工具 0 命中（`social.cities`/`sd.nations`/`sd.armies`/`command.submitBatch`）；`ToolSupport.java:314-340` **无 `social.city` 分支**而 `SocialResolver.java:98` 确实产出该 SubjectId（落 default→false）；无 `/gov` 页、webui 里 packet/上报 0 命中；日历季节钩子未接（`CalendarInfoTool.java:111` 是唯一调用点）、`MapHexTool` 无 date/season；`RegisterEffect` catalog 无多态 schema（底层 7 个 sealed 子类型未进 catalog） |
| 工程 / 运维 | enforcer **半条**：`util/map/unit` 仍不拦 `economy/economy-api/ledger`（成立）；工具面下沉：领域模块 `*Tool.java` **0 个**成立，但计数应为 **207 个 .java / 176 个工具类**；日志剩 4 条（§6-#4/#5/#6）；`RealLlm*` **5 条**（2 个类，`SIMOS_REAL_LLM=1` 门控）；provider 参数里的 JSON `null` 不被剥离（`DecisionAgentRunner.java:965`、`DecisionEffectExecutor.java:350-360`，`ToolContext` 的 `Map.copyOf` 会 NPE）；test-world 无 seed 脚本、rev1 是 `modules:{}` 空创世 + 2 条演示单位；门禁杂项（`writeMeta`、`LatitudeBands`、`ToolContext` null）；day/tick 混用（现象在、**类名行号已漂移**）；seeder 年龄档按 tick0 折算；**城市化/用地池/矿业未派单**（`AssetKind.java:10-16` 无矿业档、`EconomyData.java:312-377` 无池组件、城市化率只有写侧输入）；家户查询明细 D1.1（`HouseholdQueryService.java:274-276` 只接受 false + 无分页） |
| 待裁定 | 原 14 条里**铸币 M1 那条可划掉**（形状已定形）；文化/宗教仍待裁；其余 12 条不变（DM 自动派发、D-026、城防形状、单格命名、批工具审计、DeleteNation 级联、人口城籍、D-006/D-009/D-012、默认世界配置、`unit.SetComposition` 权限、SpawnArmy、Region 互斥） |

---

## 5. 本轮**未核**的边界（如实）

1. **全部运行期数字**（跑商/运粮的笔数与毫数、600 天日志行数、`RealLlm` 实际跳过数、出生是否恰为 0）——只读静态核不了。
2. **`2026-10-10-resume-points-and-backlog.md` §7.4 的 8 条**本轮**没派**：创世种子劳动 580/人 vs 运行期 8000/人（13.8×）、区内税只在现金腿真收、工具补货只覆盖 trade 经营者那一户、纯商号承运"非商号的货"按事实计运费、FX(exchange) 闸执行面未接、冻结时"货已运走运费已铸"、买侧"切档"是解释性判断、运力"需求汇总"是读数非配额表。
3. **口岸"商品维无消费者 / `split` 零调用者"**（同文档 §1.2）——用户已裁定"走私"这一档取消，需按新口径重核，本轮未做。
4. `INV-2026-10-11-12` 的"未来变量索引"是否有落盘设计文档 —— 本轮未逐份检索 `docs`。

---

## 6. 16 处文档更正（现措辞 → 代码事实）

| # | 文档与现措辞 | 代码事实 |
|---|---|---|
| 1 | `AGENTS.md:72`「`simos-app` 用了 `util`/`economy-api` 却**未声明**，靠传递依赖」 | **已关闭**：`simos-app/pom.xml:35-43` 显式声明 `util`、`actor-api`、`economy-api`（pom 注释自述"此处补上显式声明"） |
| 2 | 盘点 `:99`「现状 **183 个**工具文件全在 app」 | 实测 `**/tools/**` **207 个 `.java`**、其中工具类 **176 个**（read 39 + write 137）；领域模块 `*Tool.java` 确实 0 个 |
| 3 | 盘点 §1.1「**铸币生产方式 M1 未做**」 | **已完成**：`Industry.java:144` + `EconomySeeder.java:4066-4074` + `MintSettlement` + GM 工具 `simos.economy.setGovernmentMintScale`；`seignioragePerCycle` 已删；`MintRule` 这个类**从来不存在**、也不是缺口。「发债」亦非待办：旋钮已随 W5 双轨合并删除（`EconomySeeder.java:4259`、`GovernmentDebtIssuance` 整类删除） |
| 4 | 日志计划 `:417`「`RealTwelveHexWorld` 夹具保留（被一个 0.005s 的单测使用）」 | 6 个长跑测试确已删（`b9e4d621`），但该夹具**全树 0 使用者**（只有自引用与历史文档），已是**死夹具** |
| 5 | 日志计划 `:420`「`Replay.applyWorld` 缺模块 ISE…无 ERROR」 | **"缺 codec"那一支已有 ERROR**（`Replay.java:221/241` + `:268 REPLAY_DECODE_FAILED`）；真缺口只剩 `:226-227`「模块不在状态里」这一条 |
| 6 | 日志计划 `:419`「economy **9 条**命令面事件分类」 | 按 **handler 数** 9 个成立；按**成功事件数**是 **8 条**（`UnitRepayHandler` 无独立成功事件名）。另：`EconomyLog.entry()` 这个分类在 `simos-economy/src/main` **0 个调用点** |
| 7 | `INV-2026-10-02-02` 定位到 `ClassFirstPopulationEconomyTimeParticipant.java:263/286/315` | 类已改名移位：现 `PopulationEconomyTimeParticipant.java:619` 起（`day = tick + 1` 与 `day` 当 tick 用的现象仍在） |
| 8 | 收口页/盘点「规模算式**唯一**在 `ProductionProcessBook.java:118-140`」 | 主式在 `:125-131`，另有**两份逐字同式副本**（`SettlementIndex.java:456-462`、`ExpectedProfitBook.java:900-902`） |
| 9 | 收口页 §二.1②「跑商被**家户没钱** + 工具缺货卡死」 | **该闸不存在**：工具取得走 TENANCY、无付款腿、不读钱（`ModeMigrationSettlement.java:1062-1108`）；真闸 `:1949-1952` |
| 10 | 收口页 §二.1「12/12 轮 `rowsBefore==rowsAfter`（新建户 0）」 | 只是**读数**：新建行路径仍在（`ModeMigrationSettlement.java:923-933`），日志只记不等（`EconomySettlement.java:3404-3431`）⇒ **不是不变式** |
| 11 | `open-bugs.md:289`「`SqliteStore.writeMeta` 接受空白 value」 | 分层要写清：**blank（`""`）可写**成立，但 **null 是被拒的**（`SqliteStore.java:330-334`） |
| 12 | 盘点 §1.1「`economyHex` 的 `logisticsGap`（M2.4 前无定义）」 | **已有真实算式**：`ApiViews.java:1973` → `MarketReport.logisticsGapMilli`（`:589`），还带 `logisticsGapWindow`；仍不可用的是 `productionSelfSufficiency`、`paymentInstrumentGap`（`:1970/:1980` 走 `unavailable.put`） |
| 13 | `INV-2026-10-11-05`「未成交归因过粗（含 `LOGISTICS_CAPACITY`）」 | `LOGISTICS_CAPACITY` **已有具名子档** `logisticsDetail`（`MarketSettlement.java:7996-8001`）；只有 `ALGORITHM_UNCOVERED` 一侧仍是 5 处无子档兜底 |
| 14 | `INV-2026-10-11-09`「贫/中/富农角色元组**完全相同** ⇒ 没有阶梯」 | **只对 legacy 表成立**（`LegacyClassStructure.java:46-48`）；fresh 映射是 **rich==middle、poor=雇农**（`EconomySeeder.java:1680-1706`）⇒ 有"中→贫"一步、**仍无"富→中"** |
| 15 | `BUG-2026-10-11-01`「户绝者…**既无核销**、也无继承/没收路径」 | **债务侧已关闭**（`writeOffExtinctHouseholdDebts`，双向、`EconomySettlement.java:4880`）；条目应**收窄为"资产接管/继承/没收未做"**，且判据不再是 `population==0` |
| 16 | 盘点总清单**没有**「城市化 / 用地池 / 矿业」条目 | 应回填：该批只在 `AGENTS.md` 与 `specs/2026-10-10-urbanization-and-industry-pool-design.md`（自述"未实现、未派单"）里 |

---

## 7. 现场与分支（2026-10-12）

| 项 | 事实 |
|---|---|
| 分支 | `main`，工作树干净；本次推送前**领先 `origin/main` 38 个提交**（整个 W1–W10 经济线未推送） |
| 未并回 main 的分支 | 只有 2 个：`llm/provider`（自标"归档…不合并"，已被 `llm/integrate` 取代）、`llm/provider-wip`（自标"半成品，待续做/验证"） |
| worktree | `.claude/worktrees/` **32 个**（多为历史批次工作树）；`/tmp` 下 **8 个** detached 现场，其中 **6 个已 prunable** |
| 死代码 | `RealTwelveHexWorld` 夹具（§6-#4）；`EconomyLog.entry()` 分类（§6-#6） |
| 仓库外证据 | `/home/cna/simos-runs/**`（run4–run7、z7e3-probe）**勿删** |

---

## 8. 结论（给下一批用）

1. **经济模块可以放下**：要动就只在"拧 4 个已生效旋钮 + 改创世/测试数据"这一层，逻辑一行不用改。
2. 真想让数值好看，第一步是**改小世界创世数据**（城镇分布、工具/粮/钱的家户禀赋、运力覆盖），不是碰 `simos-economy`。
3. 若哪天要接上那 5 个"写了不生效"的政策键，是**独立的小批次**（5 处读取点），不牵动机制。
4. 下一批功能方向**不受经济线阻塞**：非经济面 19 条开口原样有效（§4）。

---

## 9. 2026-10-12 大文件事故与清洗（推送阻塞 → 重写未推送历史）

**现象**：`git push origin main` 失败 —— `RPC failed; HTTP 408 curl 22` /
`send-pack: unexpected disconnect while reading sideband packet`。

**根因（不是网络）**：W 线 **40 个未推送提交**里被 `git add -f` 塞进了 **23 个单文件 >100 MB 的运行产物**
（合计 **14.4 GB**；待推送集合共 **1,082 blob / 16.9 GB**），远超 **GitHub 的单文件 100 MB 硬上限**。
`.superpowers/sdd/.gitignore` 的内容就是 `*`（本该挡住它们），这些文件是**强加**进去的。
（本地 `.git` 只有 1.4 GB —— 文本日志压得极狠，但 **GitHub 的上限按未压缩体积算**，照样必拒。）

**用户裁定（2026-10-12，逐字）**：「这些大文件干脆都别提交了，所有大于100MB的单文件都不提交可以吗」

**23 个超限文件（按批次，未压缩体积）**：

| 批次目录 | 体积 / 个数 | 内容 |
|---|---|---|
| `sdd/2026-10-11-w10-scale-unlimited` | **9.74 GB / 8** | `w10close-tp240.log` 2,025 MB、`w10fix-tp240.log` 2,025 MB、两份 1,968 MB `.log.stripped`、`w10fix-tppolicy*.log` 505×2 MB + 两份 490 MB stripped |
| `sdd/2026-10-11-w9-test-batch` | 1.52 GB / 1 | `logs/tp360.log.stripped` 1,552 MB |
| `sdd/2026-10-11-wealth-concentration` | 0.67 GB / 3 | `tp600/simos.db` 373 MB、`sw600/simos.db` 203 MB、`daily/hh-tp600.jsonl` 109 MB |
| `sdd/2026-10-11-haul-supply-investigation` | 0.63 GB / 2 | `tp-replay/simos.db` 411 MB、`sw-replay/simos.db` 234 MB |
| `sdd/2026-10-11-w6-calibration` | 0.62 GB / 2 | `tp600/simos.db` 405 MB、`sw600/simos.db` 225 MB |
| `sdd/2026-10-11-death-debt-writeoff` | 0.56 GB / 2 | `tp600/simos.db` 373 MB、`sw600/simos.db` 203 MB |
| `sdd/2026-10-11-w7e-test-batch` | 0.44 GB / 3 | `tp360.log.gz` 154 MB、`sw360.log` 149 MB、`sw360.log.stripped` 143 MB |
| `sdd/2026-10-11-w7-test-batch` | 0.27 GB / 2 | `tp360.log.gz` 158 MB、`sw360.log` 123 MB |

按类型：**日志 11.97 GB**（6 个 `.stripped` 6.46 GB + 6 个 `.log` 5.21 GB + 2 个 `.log.gz` 0.30 GB）、
**世界库 8 个 `simos.db` 2.37 GB**、逐日读数 1 个 `.jsonl` 0.11 GB。

**处置（已完成）**：

1. **备份分支** `backup/pre-largefile-clean` = 重写前 HEAD `4663a970`（远端从未见过这条历史）。
2. `git filter-branch --index-filter`（范围 `origin/main..HEAD`，**只重写这 40 个未推送提交**）
   把上述 23 条路径从全部提交剔除 ⇒ 新 HEAD `985834c7`，提交数仍 40，**内容除这 23 个文件外逐值不变**。
3. **磁盘原件一个没删**：重写收尾的硬重置确实把它们从工作树删掉了，已用
   `git checkout backup/pre-largefile-clean -- <路径>` + `git reset` **全部恢复**（抽查 2,026 MB / 406 MB / 109 MB 三件在位）。
4. **待推送体积：16.9 GB → 2.07 GB**（1,059 blob），**最大单文件 86.1 MB** ✓ 符合新规则。
5. **残留（<100 MB，仍会入库）**：W10 的 `w10fix2-tp240.log(.stripped)`、W7/W7e 的 360 天 `.log.gz`、
   若干 smoke 世界库等 —— 若要"运行产物一律不入库"，另开一批。

**新增纪律**（已写入 `AGENTS.md` §五.1）：单文件 >100 MB 不入库（附提交前自查命令）；
`.superpowers/sdd/**` 运行产物禁 `git add -f`；**排查大文件禁用会解压全仓的命令**
（`rev-list --objects --all | cat-file` / `pack-objects` / `gc` —— 本次就是这样把机器卡死的），
改用 `du -sh` / `find -size +100M`（只 stat）。

**顺带记一条教训**：`pkill -f 'pack-objects'` 会**匹配到自己的命令行**并把自身杀掉 ——
要杀进程用 `pkill -f 'pack[-]objects'` 这种不自匹配的写法。

**推送结果（2026-10-12 收口）**：`git push origin main` → **成功**，
`852d3064..094a7bd0  main -> main`（41 个提交；`094a7bd0` = 本记录所在提交）。
`push_rc=0`。服务端另给了 **16 条 `warning: … larger than GitHub's recommended maximum file size of 50.00 MB`**
（最大的 86.07 MB，全部 **< 100 MB 硬上限**、已被接受）—— 若要连这类 50–100 MB 的运行产物也清掉，
需要**再重写一次历史**（另开一批；`backup/pre-largefile-clean` 仍在本地可作对照）。
本地 `backup/pre-largefile-clean`（= 重写前 `4663a970`）**保留**，未推送。
