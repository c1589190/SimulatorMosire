# 未完成项总清单（回代码核实版）· 2026-10-13

> **本文取代**：`docs/archive/status/2026-09-30-class-first-economy-status.md` · `docs/archive/status/2026-10-09-p0-p1-completion.md` · `docs/archive/status/2026-10-12-arch-round-known-reds.md` 的未完成/状态叙述（2026-10-13 归档）。
> **重定向**：本文引用的 `HANDOFF-*.md` 已于 2026-10-13 移入 `docs/archive/handoff/`（文件名不变）。
> **来源**：只读调查（`grep`/`find`/读文件；**未跑 Maven、未起服务、未跑世界**）
> **基线**：`main` @ `30459634`（架构调整轮关账后）
> **口径**：本文**取代**以下文档里的"未完成/待办"叙述（它们比现状旧，见 §6 反向更正）：
> `2026-10-23-planned-not-implemented-inventory.md`（其"未做"判定已被本文逐条回核）·
> `HANDOFF-2026-10-23-gov-fiscal-batch.md` §4.1/§4.2 · `2026-10-12-known-issues-code-verified.md` 的陈旧句
> ★ **本文不取代** `2026-10-12-known-issues-code-verified.md`（除非本文 §6 明确更正它）

## 0. 总览

| 档 | 数 | 含义 |
|---|---|---|
| ① **计划内未实现（未开工）** | **16** | 有设计/有清单，代码里 0 命中 |
| ② **做了但没做完**（半成品 / 缺口 / 观测小口子） | **33** | 主体在，缺一块 |
| ③ **文档互相矛盾 / 必须用户裁** | **8** | 含待裁定 **13 项**（§5 单列） |
| ★ **架构轮红点台账对账** | 台账 **96% 已作废**；**仍成立 4 条**（全是文案/命名债，无一条"红"） | ✅ |
| ★ **反向更正（文档说没做、代码其实做了）** | **10** | §6 |

## 1. ① 未开工（16 条）

| # | 事项 | 出处 | 核的方式 |
|---|---|---|---|
| 1 | 城防/工事/多阶段围攻自动结算 | `status/2026-10-23-...inventory.md:78` | `grep -rli "fortif\|城防\|siege" simos-*/src/main` = 0；`CombatRecord` 8 组件无防御字段 |
| 2 | morale / supply / training 模型 | 同上 `:79` | `grep -rln "morale" simos-*/src/main` = 0；`UnitStatus` 只 MOVING/RESTING/ENGAGED |
| 3 | 自动事件/紧急响应触发器 `DecisionDueRunner` | 同上 `:80` | `grep -rn DecisionDueRunner simos-*/src` = 0（且属待裁定） |
| 4 | v17l 地图整体重建 / 测试世界替换 | 同上 `:71` | 只有 `worlds/v17levant.json` 未动；新地图数据未建 |
| 5 | 单格命名 `simos.map.nameHex` | 同上 `:72` | `grep -rn nameHex simos-*/src/main` = 0 |
| 6 | 免税 / 税率期限实体 | 同上 `:68` | `grep -rn "exempt\|免税" simos-gov/src/main simos-unit/src/main` = 0 |
| 7 | `simos.social.cities` 读工具（+`subjectVisible` 的 `social.city` 分支） | 同上 `:86` | `NAME = "simos.social.cities"` = 0；`ToolSupport:347` 只有 `social`/`social.hex` |
| 8 | `simos.gov.nations` / `simos.army.armies` 读工具 | 同上 `:87`（★ 原文写 `simos.sd.*`，**命名已过期**） | 0 命中；只有单点 resolve |
| 9 | `simos.command.submitBatch` GM 原子批工具（薄工具） | 同上 `:88` | 0 命中；`CommandBus.submitBatch` 已有、app 内 28 处在用 |
| 10 | D4.1 GUI/Catalog 面板（`/gov` 页、决策包审阅/上报） | 同上 `:89` | `StaticHandler` grep `/gov` = 0；webui grep `packet` = 0 文件 |
| 11 | 日历季节结算钩子 + `MapHexTool` date/season | 同上 `:90` | economy/social/unit 无 `SeasonState/seasonAt` 消费者；`MapHexTool` 无 date/season |
| 12 | `RegisterEffect` 多态 schema（trigger/action 子类型进 catalog） | 同上 `:91` | `CatalogTool:813` 只列字段名串，无子类型枚举 |
| 13 | 文化 / 宗教（riba、无息合同） | 同上 `:93` | economy/social main `culture\|religion\|riba` = 0 |
| 14 | 非法仿制 / 伪币 / 成色 / 铸熔 | 同上 `:57` | `InstrumentId:17`/`CurrencyDef:41` 自述属 M4+ |
| 15 | **城市化 / 用地池 / 矿业**（Q1–Q11 待裁 + U0–U6 未派单） | `inventory.md:270-273` + `specs/2026-10-10-urbanization-and-industry-pool-design.md` | `AssetKind` 无矿业档；`EconomyData` 无 `Urbanization/LandPool` |
| 16 | `test-world` 世界内容未初始化 | `inventory.md:104` | `test-world/scripts/` 只有 start/stop，**无 seed 脚本** |

## 2. ② 半成品（33 条）

### 军事 / Unit（4）
| # | 事项 | 出处 |
|---|---|---|
| 17 | unit 级兵力拆/并原语 + 装备转移/守恒（`SplitFormation` 只动编制树） | `inventory.md:130` |
| 18 | 装备类型化 / 目录（D-006/R2） | 同上 `:132` |
| 19 | P4c：军俸 FlowRow / ledger 维度与读口（`FlowRow` 13 组件无军俸维） | `plans/2026-10-15-unit-integration-p4b.md:148` |
| 20 | P4c：决策人受限军俸工具 + 审批链/白名单（catalog 归属未定） | `p4b.md:147` + `plans/2026-10-22-d2-decision-packet.md:144` |

### 行政 / 区划（8）
| # | 事项 | 出处 |
|---|---|---|
| 21 | P2-F1 区划下游重算编排（Merge/Split/Reassign 只改 map；jurisdiction/城市/税率/编制重算未编排，仍在 `Shell`） | `plans/2026-10-09-p2-backend-fixes.md:91` |
| 22 | P2-F2 迁都不搬人（`MovePopulationLots` 存在但未被 `MoveCapitalPlan` 调用） | 同上 `:92` |
| 23 | P2-F3 `DeleteNation` 的 Directive 级联缺失（严格拒绝已实现） | `gov/.../DeleteNationHandler.java:116-117` |
| 24 | P2-F4 `DeleteCity` 不清理 economy 引用（铁律 3） | `social/.../CityOperations.java:23` |
| 25 | P2-F5 `UpdateCity` 未知键静默（无具名拒；`population` 除外） | `social/.../UpdateCityHandler.java:27` |
| 26 | `region.seed` 无显式城市落点/hex（`CapitalAnchor` 无 hex） | `app/.../RegionSeedTool.java:147` |
| 27 | Region 互斥 / 跨 GOV 重复征税去重（已有检测工具 `MapOverlapsTool`，但无不变式/去重） | `inventory.md:70` 等 |
| 28 | `SdCommandDrain` 接回生产 advance + `RestUntil`（`Shell.advanceAndDrain` 0 生产调用方） | `inventory.md:131` |

### 前端（3，全属"挂账 1"）
| # | 事项 | 出处 |
|---|---|---|
| 29 | `renderer.js:2902 paintBorders` 悬空引用（只炸微基准；倾向改指 `paintRegionOutlines`） | `plans/2026-10-12-full-dispatch-plan.md:1192` |
| 30 | 陈旧字段读 ⇒ hex 详情渲染成 "—"（`panels.js:767-772` / `renderer.js:630-632`） | 同上 `:1194` |
| 31 | 真浏览器验证 `window.SimosaApi` | 同上 |

### 经济（18）
| # | 事项 | 出处 |
|---|---|---|
| 32 | **5 个政策键无运行时读取点**（饥荒上限/铸币劳动份额/借贷基准利率/转业上限/转业斜率各自读**编译期常量**） | `status/2026-10-12-known-issues-code-verified.md:64-68` |
| 33 | `economyPolicies` **无产品读口**（GUI/QueryServer/读工具都看不到） | 同上 `:72` |
| 34 | 铸币"上一轮印量"三点口径（**跨币种求和**，只作读数） | 同上 `:83` |
| 35 | `ALGORITHM_UNCOVERED` **5 处兜底无子档**（`:8982/9068/9086/9436/9446`） | 同上 `:84` |
| 36 | 货物守恒缺"毛产/损耗/投入"读数（`FlowRow` 只 income/consumed） | 同上 `:85` |
| 37 | `HexTradeCost` 货币侧 = 0（区内不产生货币运费） | 同上 `:86` |
| 38 | 运力只关账日重置 / 区内撮合退化单线程 | 同上 `:87` |
| 39 | 家户结构 5 条结构债（出生=0 链 · GM 读口缺 · 创世库存按 `population × rationDays` 折算 · `unmetPersonDays` 全局量级 · `expectedNeedMilli` 线性外推） | 同上 `:88` |
| 40 | 规模上限守卫缺失（Σ份额 ≤ 格上限）+ **三份同式副本** | 同上 `:91` |
| 41 | INV 群（铸币读数口径 · 成交额口径 · 政府身份链无命名空间校验 · 创世 4 户两侧不一致 · S-11 日志点少） | 同上 `:92` |
| 42 | `economyHex` **仍 2 项不可用**（`productionSelfSufficiency`、`paymentInstrumentGap`） | `inventory.md:58` + `known-issues:138` |
| 43 | 户绝者**资产**归属（债务侧已双向销账） | `known-issues:89` |
| 44 | 首日给养时序（消费早于俸禄日结；第 1 天无市场轮） | 同上 `:90` |
| 45 | **铸币在 19 格世界实际没开产（标定问题，非机制缺失）** | `HANDOFF-2026-10-23-...:116-117` |
| 46 | F4 全覆盖校准 | 同上 `:118` |
| 47 | `MarketReadout` `hh-unit:*` 读口边界未审计 | 同上 |
| 48 | 逃亡目的地回退只在探针层验证 | 同上 |
| 49 | 旧 store 无真实回读测试 | 同上 |

### 工程 / 日志 / 测试 / 运维（8）
| # | 事项 | 出处 |
|---|---|---|
| 50 | 日志 4 条小缺口（economy 9 条命令面事件分类待统一 · `Replay.applyWorld`/`Timeline.readChangeSet`/`Envelope.decode` 三处失败路径无 ERROR 行 · 真 `-Dsimos.*` 属性轮未跑） | `AGENTS.md:344` + `plans/2026-10-23-all-module-logging-rollout.md:427` |
| 51 | 测试侧两份规格并存（`SimosToolsTest` 名单 vs `DecisionFaceGolden`）+ `ToolSupport` 未三分收口 | `inventory.md:262-263` |
| 52 | **`simos-ga-spi` 无 `src/test`**（`simos-ga` 已有） | 同上 `:259` |
| 53 | 真世界 Social+Economy 长期联合校准 / 联合逐 tick 表（现只有 7hex 探针，人口恒定） | 同上 `:103` |
| 54 | `provider` JSON `null` 不被剥离（★ 文档点名的 `ToolContext` **类已不存在**，须按新代码重述） | `known-issues:109` + `inventory.md:101` |
| 55 | 门禁杂项：`SqliteStore.writeMeta` 放行 blank（null 已拒）· day/tick 混用 · seeder 年龄档按 tick0 折算 · `LatitudeBands` 负溢出**未核** | `open-bugs.md:271-286` |
| 56 | 更早两批未复核：`resume-points-and-backlog.md §7.4` 的 **8 条** + G3 账本"其余 **5 处**待处理" | `status/2026-10-10-resume-points-and-backlog.md:226-236` |
| 57 | 死代码：`RealTwelveHexWorld` 夹具（0 使用者）· `EconomyLog.entry()` 分类（0 调用点） | `known-issues:153` |

### 批次 5 挂账（3）
| # | 事项 | 出处 |
|---|---|---|
| 58 | **资源声明面 `"sd"` 正名**（`ToolResources.SD_NAMESPACE`/`ResourcePaths.sd`/`ToolSupport.SD_NAMESPACE`；★ 实测 **106** 处，文档写 126 已漂移）★ **要求逐工具资源键 A/B 证据** | `full-dispatch-plan.md:1195-1197` |
| 59 | `ProposalCatalog:131/149` 把两条 **GM-only** 工具（`LevyRegionTool`/`RaiseUnitTool`）装进**决策人可提议目录** ⇒ DM 可达性未核 | 同上 `:1198` + §26.1 |
| 60 | 三条"**装配故障伪装成身份错误**"：`GovPayTool.resolvePayer` · **`GovToolSupport:169-171`（gov 工具共同路径）** · `GovInfoTool:180-182`（不链 cause） | 同上 `:1199-1202` |

### 命名 / 文案债（3）
| # | 事项 | 出处 |
|---|---|---|
| 61 | `simos-ga` 里 **17 个 `Sd*.java`** 类名保留 | `arch-round-known-reds.md:395-396` |
| 62 | `SdCreateArmyTool`/`SdSetArmyMasterGovTool` 的 `NAME` 仍是 `sd.*` 而 `commandType()` 是 `army.*` | `full-dispatch-plan.md:1068-1071` |
| 63 | 测试 `.as()` 文案与断言值不一致（`.as("142")` vs `hasSize(141)`；`.as("166")` vs `hasSize(161)`）· `RegisterEffectHandler:50-51` 仍写"66 条" · `SimosToolSource:750-751` 假断言（说 `AdjustComposition` 打 `DmUsableCommand`，实为仅 GM） | `SimosToolsTest.java:1002-1004/1043-1047` 等 |

## 3. ③ 文档矛盾（8，需处置）

| # | 矛盾 | 双方 | 处置建议 |
|---|---|---|---|
| 64 | **enforcer 回填 / 工具面下沉**是"已关闭"还是"仍开" | `inventory.md:99-100` ✅ **已关闭** **vs** `HANDOFF-2026-10-23:132` ❌ 仍开 | 回代码：三条 pom 已 `exclude` + `searchTransitive=false`；域模块工具类在位 ⇒ **`inventory` 对，HANDOFF 陈旧**。★ 它是**最新 handoff**，会被当成待办重新开工 ⇒ **必须就地更正** |
| 65 | **铸币 M1**是"已完成"还是"无机制" | `known-issues:42/129` ✅ **已完成** **vs** `HANDOFF:116-117` ❌ "无 `Industry` 货币产出/`MintRule`/`mintScale`" | 回代码：`EconomyUpsertIndustryTool:98/289`、`EconomySeeder:4074`、`MintSettlement:62`、`EconomySetGovernmentMintScaleTool:17` 全在；`seignioragePerCycle` 只剩注释 ⇒ **`known-issues` 对**。真开口是**"世界里没开产"的标定**（见 #45）。★ **唯一需用户裁**：世界里要不要真开铸币产业 |
| 66 | **enforcer 半条**（`known-issues:109` 自身未跟上同日冻结批） | `known-issues:109` vs `specs/2026-10-12-module-boundaries-freeze.md:56-72` | 冻结批在后 ⇒ **冻结文为准**（§四.1「最新落盘为准」失效的一处） |
| 67 | 读工具命名用 `simos.sd.*`（模块已退役） | `inventory.md:87` | 条目**实质仍成立**，但**名字须改 gov/army 命名空间**再派单（否则 grep 出误导性 0 命中） |
| 68 | 测试 `.as()` 文案 vs 断言值 | 见 #63 | 不需用户裁，另开小批 |
| 69 | `economyHex` 三项 vs 两项 | `inventory.md:58` vs `known-issues:138` | `known-issues` 对（`logisticsGap` 已有算式 `ApiViews:1968`） |
| 70 | 工具类计数漂移（183/176/207/**181**） | `known-issues:128` 等 | ★ **无单一权威计数点**（"第二个拼写点"同族）⇒ 见 §7 |
| 71 | **成交额口径**："仍读现金" vs "已按币分列" | `known-issues:82` vs `ApiViews:2748-2759` | "按币分列" ≠ "名义口径" ⇒ **需裁定"名义口径"要不要** |

## 4. ★★ 架构轮红点台账对账（96% 已作废）

| 台账节 | 条目 | 判定 |
|---|---|---|
| 批次 2（hex 9 条） | `HexCellTest`/`RegressionGuardsTest`/`SimosToolsTest`/`McpCoverageTest` | ✅ **全作废（已修）** — 现为 `containsExactly("height","areas")`、141/161/171 |
| 批次 3a/3b-1/3b-2/3c/3d-1/3b-4/乙/3e（sd 侧全族） | 编译断 + 断言红 | ✅ **全作废** —— `ls simos-sd` 不存在；`grep -rn "io.mosire.simos.sd" simos-*/src/test` = **0** |
| 批次 3a E「54 个 app 测试文件手工列 codec 表」 | 预期运行期红 | ✅ 作废：`new SdCodec()` = **0**；`new GaCodec()` = **52** |
| 批次 4（`hasSize(66)`、缺口 10 条、`DM_DECLARED` 5 条） | 断言红 | ✅ 作废：`hasSize(66)` = 0；`CatalogVisibilityTest` 已注"收尾测试第 2 层换到新契约面" |
| 批次 4 C⑤ 残留通道 | 有意保留 | ✅ **已关闭**：`DmUsableCommand` = **17** |
| §33.4 #2 幽灵 `simos.sd.combats` | — | ✅ 已删（`DecisionFaceGolden:53-56` 有注） |
| **仍成立 4 条**（**全是文案/命名债，无一条"红"**） | #58 资源面 `sd` · #59 `ProposalCatalog` 可达性 · #63 `RegisterEffectHandler` "66 条" · #62 `Sd*` 工具 `NAME` 分叉 | ⚠️ |

## 5. 待用户裁定（13 项，`inventory.md:178-192` §5）

> 原文：「**不定就不能开工**」

1. DM 自动派发 2. 跨市场区 D-026 3. 城防数据形状 4. 单格命名 5. 批工具审计 6. `DeleteNation` 级联
7. 人口城籍 8. 文化/宗教 9. 默认世界配置 10. `unit.SetComposition` 权限 11. `SpawnArmy` 造人
12. Region 互斥去重 13. **铸币标定口径**（= §3 #65 的唯一真开口）
★ 第 9 条（铸币 M1 形状）**已消解**（机制已在，见 §3 #65）

## 6. ★★ 反向更正（文档说"没做"、代码其实做了）—— **10 条**

| # | 文档说法 | 代码事实 |
|---|---|---|
| R1 | `inventory:260` G2「外交类型迁 gov **未做**」 | **已做**：`simos-gov/.../gov/nation/{Nation,NationId,NationTag,DiplomaticRelation,DiplomaticRelationKey,DiplomaticEvent,DiplomaticEventId,NationAffiliations}.java` |
| R2 | `inventory:52` + `HANDOFF:116-117`「铸币 M1 **未做**」 | **已做**：`moneyOutputPerUnit`（工具 `:98/289` · seeder `:4074` · `MintSettlement:62` · GM 工具 `:17`） |
| R3 | `HANDOFF:132`「enforcer 回填 / 工具面下沉 ❌ 仍开」 | **两项均已关闭** |
| R4 | `inventory:58`「`logisticsGap` 无定义」 | **已有算式**：`ApiViews:1968` |
| R5 | `arch-round-known-reds:435`「DM 经工单仍能改人口」 | **已关闭**：`DmUsableCommand` = 17 |
| R6 | `p4b.md:146`「GM 窄工具 `simos.unit.armyPayPolicy` 待办」 | **已做（改名）**：`GmArmyPayPolicyTool` |
| R7 | `inventory:54`「军俸与 GOV 共享国库预算/优先级**无共享池**」 | **已关闭**：`GovBudgetPolicy` 存在，79 处引用 |
| R8 | `inventory:84`「`LOGISTICS_CAPACITY` 无子档」 | **已有具名子档**：`MarketReadout:458/499-500` |
| R9 | `known-issues:109`「`ToolContext` 的 `Map.copyOf` 会 NPE」 | **该类已不存在**（`find -name ToolContext.java` = 0）；`DecisionAgentRunner:1061` 改 `LinkedHashMap` |
| R10 | 台账里 sd 侧全族"74 个测试文件编译断" | **全作废**（模块不存在；sd import = 0） |

## 7. 计数漂移（无单一权威计数点 ⇒ §35.4「第二个拼写点」同族）

| 量 | 文档值 | 本次实测 |
|---|---|---|
| 工具类数 | 183 / 176 / 207 | **181**（`*Tool.java`）；`roles()` 实现 = **171** |
| `SD_NAMESPACE` 处数 | 126 | **106** |
| `simos-app` 测试文件 | 54 | **15**（第 1 层实测） |
| `GaRoundTripTest` import 断 | 23 | **22** |

## 8. 本次**未**核的（如实）

1. **未跑 Maven / 未起服务 / 未跑世界** ⇒ 一切测试计数与运行期数字**均属文档自述**。
2. **§33.3 三条"疑似别的缺陷"**：测试文件仍在，但跑不了 Maven ⇒ **无法判定"已修"还是"被改写/放宽"**。
3. 未逐条回代码核：`resume-points §7.4` 8 条 · G3 账本 5 处 · `known-issues` §5 全部 4 条 · 家户结构 5 条中 3 条 · INV 群 4 条。
4. 未核：`LatitudeBands` 负溢出 · `awaitPending` 文案 · 跨命名空间夹具维度 · `worlds/v17levant.json` 与创世数据一致性 · `test-world/store/simos.db` 是否真 0 city/0 unit（未读 live 库）。
5. 未核前端运行时（`paintBorders`/陈旧字段读只做静态核对）。
6. **老计划未逐条判定是否已被消化**（例：`2026-10-10-market-port-fx-merchant-plan.md` §7 的 Q-1~Q-20 多数带"控制方默认"）。
