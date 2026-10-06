# 计划内未实现功能清单（代码核对版，2026-10-23）

> **这是什么**：一次跨全部开发计划的「计划已定 / 尚未落地」盘点，供恢复工作与派单使用；
> 也替代 2026-10-02 那批老清单里已被后续批次补掉的口径（老文件只作历史留痕，不回头改）。
>
> **基线**：`main` @ `bdf417e6`（2026-10-06 20:51），工作树干净、与 `origin/main` 无分歧、0 未推送。
>
> **方法**：只读回代码核对（`git grep`，显式目录路径）+ 对照最新计划/状态文档。
> **未跑 Maven、未起 Shell/DB/GUI/MCP**（盘点时本机有 `~/DevMosire/prompt-efficiency-lab` 的两个 Maven 在跑，
> AGENTS §一.1 禁止并发）⇒ **本文出现的任何测试计数 / smoke / rc=0 均属「文档自述」，本次未复核**。
>
> **搜索纪律备注（踩到过）**：`git grep … -- 'simos-*/src/main'` 这种 **glob pathspec 在本仓会静默 0 命中**
> （正对照：已知存在的 `class Shell` 在该写法下同样 0 命中）。本文凡「0 命中」一律用**显式目录**或**全仓检索再过滤
> `docs/`、`.superpowers/`** 得出；老清单里据错误写法得出的「不存在」已逐条重核。
>
> **权威**：与代码冲突以代码为准（AGENTS §四 / §四.1「以最新落盘文档为准」）。

---

## 0. 恢复状态（盘点时的现场）

- **HEAD**：`bdf417e6` `docs: D5 决策回合统一结算 + 经济观测状态 handoff`。
- **恢复入口**：`docs/superpowers/HANDOFF-2026-10-22-d5-decision-turn-economy.md`。
- **主计划**：`docs/superpowers/plans/2026-10-22-decision-packet-household-query-llm-mcp-plan.md`
  —— **D0~D5 全部 ✅**（`573cc2e9` D0、`24968ae6` D1、`996cfd41` D2、`570f555a` D3、`a8abc45d` D4、
  `6747ca7c`+`7d944911` D5；§10 复选框原漏勾，已于 2026-10-23 更正）。
- **门禁**（文档自述）：`test-compile` ✅；非真 LLM 全量 ✅；真 LLM 单人 ✅；真 LLM 6 DM 并发 GOV ✅；
  **全仓 `clean verify` ❌（SpotBugs 基线债）**。
- **环境**：本仓构建前先 `pgrep -af "surefirebooter|classworlds.launcher|maven"` 确认没有别的 Maven 在跑。

---

## 1. 完全未做（❌）

### 1.1 经济 / 市场

| 功能 | 差什么 | 证据 / 来源 |
|---|---|---|
| **铸币生产方式 M1**（劳动＋工具 → 货币；政府家户为组织者；规模可调） | 仍只有周期 `seignioragePerCycle` 直接记国库 + `FISCAL_ISSUE` 审计；`Industry` 无货币产出、无 `MintRule`；GM 工具 `simos.economy.gov.policy`／`mintScale` 不存在 | `GovernmentSeigniorage.java`；`moneyOutputPerUnit`/`MintRule`/`mintScale` 仅出现在计划文档；`docs/superpowers/plans/2026-10-08-small-world-gov-mint-plan.md` §4、`plans/2026-10-09-p2-backend-fixes.md` C4 |
| **军俸 FlowRow/ledger 维度与读口** | 军俸走瞬态规则 + 账户扣款，FlowRow 无军俸维度、读不出明细 | P4b §6 / P4c 待办；`FlowRow.java` |
| **军俸与 GOV 行政俸禄共享国库的预算/缺口优先级** | 现为顺序扣：税 → GovDaily → 军俸，无共享池 | P4a/P4b 待办 |
| **决策人受限军俸工具 + 审批链/白名单** | GM 侧 `simos.gm.armyPayPolicy` 已由 D4 补；决策人可 propose 的军俸工具未定/未做 | P4c；`plans/2026-10-22-d2-decision-packet.md` |
| **家户文化/宗教效果**（如穆斯林家户放贷额度 −90%、riba/无息合同） | 家户无 culture/religion/community 字段；借贷仍全局 20‰ | `docs/superpowers/plans/2026-10-04-household-culture-and-community-effects-plan.md`（用户明示「只写规划、不实现」） |
| **非法仿制/伪币** | 无伪币生产、成色/真伪、查获惩罚；只有授权发行审计链 | 小世界计划 §4.5 明留 TODO |
| **`economyHex` 三项不可用指标** | `productionSelfSufficiency`（待 M2 市场读数组件）、`logisticsGap`（M2.4 前无定义）、`paymentInstrumentGap`（属 M1） | AGENTS §8.3 |

### 1.2 行政 / 区划 / 世界

| 功能 | 差什么 | 证据 / 来源 |
|---|---|---|
| **`DeleteCity(deletePopulation=true)` 检查/清理 economy 引用**（P2-F4） | `CityOperations.delete` 明说不碰 map/unit/economy；economy 侧仍可能持 `PeopleLotId` 等引用 | P2-F4；`CityOperations.java`、`DeleteCityHandler.java` |
| **`social.UpdateCity` 对 `at` 等未知键的处置**（P2-F5） | 只读 `id/name/props/region`；除 `population` 外不做未知键具名拒，`at` 仍静默忽略 | P2-F5；`UpdateCityHandler.java`、`SocialPayloads.java` |
| **`region.seed` 显式城市落点/逐格明细（capitalHex）** | 只有生成模式，`capital` 无 hex | `RegionSeedTool.java`；老清单批次 6 |
| **`createCityWithPopulation`（建城带目标人口）** | `social.CreateCity` 仍明确拒收 `population`（人口是派生量） | `CreateCityHandler.java`；老清单批次 6 |
| **免税/期限实体**（税率公告、到期恢复原值、自动执行器） | 长期税率命令在（`SetTaxRateHandler`），但无免税/税率历史/到期恢复；且依赖 §2.2 的 drain 接线 | 老清单「免税半年」；`unit.SetTaxRate` |
| **`sd.DeleteNation` 的 Directive 级联清理**（P2-F3） | 严格引用检查（有 Directive 引用即拒）已实现，但没有删/作废 Directive 的入口 | P2-F3；`DeleteNationHandler.java` |
| **Region 互斥/重复征税去重** | Region 允许重叠，两 GOV 同辖一格且税率 >0 会重复征税 | 老清单复核；BUG-2026-10-02-02 |
| **v17l 地图整体重建 / 地形与测试世界替换** | 重建数据源、范围、验收、初始化路径未定 | `open-bugs.md` INV-2026-10-02-01、TODO-01/02；D-013 |
| **单格命名 `simos.map.nameHex`** | 只有 Region 级命名；形态（overlay Region vs 新增 `GameMap.hexLabels`）待裁定 | 老清单批次 0.4 |

### 1.3 军事 / Unit

| 功能 | 差什么 | 证据 / 来源 |
|---|---|---|
| **城防/工事/多阶段围攻自动结算** | `CombatRecord` 无城防字段、`City` 只有自由 props；无防御倍率、无「外围→关垒→巷战」拆分 | INV-2026-10-03-04；AGENTS §十三.6 |
| **morale / supply / training 模型** | 全仓无这些领域字段（RESTING 只有 500‰ 速度因子） | `CombatRecord.java`、`UnitStatus.java` |
| **自动事件 / 紧急响应触发器（`DecisionDueRunner`）** | 全仓无按 due 自动筛选/自动跑轮；所有入口都是显式名单。**必须先重裁 2026-10-01「禁止自动筛选/自动派发 DM」** | `RunDecisionMakersTool.java`；`reports/2026-10-02-undeveloped-features-code-investigation.md` |

### 1.4 MCP / 工具 / UI

| 功能 | 差什么 | 证据 / 来源 |
|---|---|---|
| **`simos.social.cities` 读工具** | 工具名 0 命中；GUI 侧已有 `/api/social/cities`；还需补 `subjectVisible` 的 `social.city` 分支 | 老清单批次 0.1；`GuiServer.java` |
| **`simos.sd.nations` / `simos.sd.armies` 读工具** | 工具名 0 命中；只有单点 resolve（`sd:nation.<id>` 点号语法），无清单/详情 | 老清单批次 0.2 |
| **`simos.command.submitBatch`（GM 原子批工具）** | 工具名 0 命中；Core 的 `CommandBus.submitBatch` 早已有、app 内 28 处在用，只差一条薄工具 | 老清单批次 0.3；`CommandBus.java` |
| **D4.1 GUI/Catalog 面板** | 无 `/gov` 页；webui 中 packet / 上报 0 命中；决策包审阅/上报与 GM 参数面板全缺 | D4/D5 明文留出；`StaticHandler.java` |
| **日历收尾** | 季节结算钩子未接；`simos.map.hex` 未加 date/season（GUI 已加）；不做季节上色 | `plans/2026-10-02-calendar-and-seasons-plan.md` |
| **`sd.RegisterEffect` 多态 schema** | trigger/action subtype 未进 catalog 描述与示例 | TODO-2026-10-02-03 |

### 1.5 工程 / 纪律 / 运维

| 功能 | 差什么 |
|---|---|
| **SpotBugs 基线债** | `clean verify` 未绿（`EI_EXPOSE_REP` 于不可变 record、`UPM_UNCALLED_PRIVATE_METHOD`、`SF_SWITCH_NO_DEFAULT`、`FE_FLOATING_POINT_EQUALITY`） |
| **其他模块 Log 调用点覆盖与测试迁移** | 8 个模块门面已建；sd 约 11 个 handler 未覆盖、gov 无生产调用者、组合工具未逐工具记行 —— AGENTS §一.9「待办（不是可选）」；用户 2026-10-23 指定先做（L1~L4 批次见 `plans/2026-10-23-all-module-logging-rollout.md`） |
| **工具面下沉到模块** | 用户 2026-10-23 设计意图：app 只是前端包装/启动项，**工具归各模块管 + 统一工具协议**；现状 183 个工具文件全在 app、领域模块 0 个工具类（未立项，见 `plans/2026-10-02-undeveloped-features.md` 末尾节） |
| **enforcer 回填** | `util/map/unit` 仍不拦 `economy/economy-api/ledger`；`simos-app` 未显式声明 `util`、`economy-api`（靠传递依赖） |
| **provider 互操作** | 真模型偶产 `tool_calls.arguments` 含 JSON `null`，runner 层不能完全消除 |
| **`RealLlmGovScenarioTest` 的 GM 确定性补执行** | 测试方法学债：模型侧只要求 ≥1 类工具从包路径执行 |
| **真世界 Social+Economy 长期联合校准/联合逐 tick 表** | 目前只有 7hex 探针世界（人口被构造为恒定），不证明出生/死亡下的长期人口平衡 |
| **test-world 世界内容初始化** | units 空 / cities 0，等用户指示；旧 `dashu-v2` 用户已表示可退役 |
| **open-bugs 杂项 / INV** | `SqliteStore.writeMeta` 空白 value、`LatitudeBands` 负溢出、`Map.copyOf` null 待裁；INV-2026-10-02-02 day/tick 混用；INV-2026-10-02-03 seeder 年龄档 anchor tick |

---

## 2. 部分实现（🟡）——差什么写清

### 2.1 经济

| 功能 | 现状 → 还差什么 | 来源 |
|---|---|---|
| **经济数值/验收债（P2-E）** | 新运费常数未标定；自承运无独立读数；`HexTradeCost` 货币侧仍 0；商号运力只关账日重置；商号区内撮合退化为单线程 | P2-E |
| **家户结构修复剩余** | 出生=0 链（首日播种/口粮次序、逐批次整除、两套生死引擎未统一）；GM 社保读口；创世库存仍按 population 折算；`unmetPersonDays` 仍全局量级；`expectedNeedMilli` 线性外推 | `plans/2026-10-09-household-structure-repair-plan.md` 剩余节 |

### 2.2 行政 / 社会

| 功能 | 现状 → 还差什么 | 来源 |
|---|---|---|
| **迁都时人口随迁（P2-F2）** | `MoveCapitalPlan` 可跑（一条 revision、五件事），但**类注明确「不搬人口批次」**；`social.MovePopulationLots` 命令已存在、未被它调用；「城籍」口径未定 | P2-F2；`MoveCapitalPlan.java`、`MovePopulationLotsHandler.java` |
| **区划下游重算编排（P2-F1）** | `map.MergeRegions/SplitRegion/ReassignHexes` 已是 GmOnly，且自带「只改 map，下游由 app 组合根协调」说明；但 Shell 之外 0 调用，**没有**同批一条 revision 的 jurisdiction/城市/税率/编制重算 | P2-F1 |
| **家户查询明细（D1.1）** | `simos.social.households` 的 `include.members/economy/units` 只接受 false；无分页与明细列 | D1 子计划 |

### 2.3 军事 / Unit

| 功能 | 现状 → 还差什么 | 来源 |
|---|---|---|
| **unit 级兵力拆/并原语** | 已有：`GovAbsorbUnit`（纯人员单位 → GOV）、`LevyRegion`、`Assign/DetachHousehold`（整户跨单位）。仍缺：从现有单位**按人数/装备拆出新守备**、**合并两单位兵力/装备**的 unit 级原语；装备无转移/守恒原语 | 老清单批次 1；`UnitOperations.java`（`SplitFormation`/`MergeFormation` 只动编制树） |
| **`SdCommandDrain` 接回生产 advance + `unit.RestUntil`** | `Shell.advanceAndDrain` 存在但 **0 生产调用方**；MCP `AdvanceTool` 与 GUI `GuiServer` 都直接 `core.submit` ⇒ SCHEDULED 的 `EnqueueUnitCommand` 只 FIRED、不落 unit 命令；`RestUntil` 0 命中，`RESTING` 只有速度因子 | 2026-09-20 起的开口项 |
| **装备类型化/目录** | 通用化已落地：`Unit.equipment = List<CompositionEntry>`（有序、`type` + `amount`，非 GmOnly 的 `set-composition` 整表复写）。差：类型化/目录/校验、「Army 格式化复写」语义（D-006/R2 待补裁） | D-006/R1/R2 |
| **P4c 军俸残留** | 见 §1.1 三条 | P4b §6 |

---

## 3. 已作废 / 暂缓（⏸——不要当待办，除非用户解禁）

| 条目 | 处置 | 依据 |
|---|---|---|
| 跨市场区重设计 **D-026**（市场区＝行政 Region、聚集节点自然生成、跨区 lane/在途/承运） | **暂缓**：现状仍按 `MarketTopology` 的「城市节点＋半径最近归属」；D-027 明确本批先跑通单个市场区 | D-026/D-027；`MarketTopology.java` |
| `sd.bombard` 攻城组合工具 / 最小装备目录 | 作废：轰城＝特殊交战状态，记在 Unit 通用交战关系 | D-009 |
| Person / Family / Hostage 专门领域 | 作废：人物/家族用通用 Unit | D-007 |
| Nation 归属 DM 看全国 / 扩中央 jurisdiction | 作废：中央政府不设全视野 | D-002 |
| 附庸/朝贡的贡额、周期、违约硬编码 | 不建：只做「给 XXX 政府钱」支付工具（已实现） | D-004 |

---

## 4. 已补齐：老清单里已完成、不要再当待办

> 2026-10-02 那批清单（`2026-10-02-undeveloped-features.md` / `open-bugs.md` / 四线报告）里，
> 下列条目**已被后续批次补掉**；老文件不回头改，本节为追加更正。

- **外交三件套全部已实现**（`bed016b4`，2026-10-02）：
  - D-003 关系边表：`DiplomaticRelation`/`DiplomaticRelationKey` + `SetDiplomaticRelationHandler` + 读工具 + 决策人窄写；
  - D-004 政府支付：`simos.gov.pay`（`GovPayTool`，付款人＝调用者所属 GOV、收款任意 GOV、敏感审批）；
  - D-005 外交事件：`RecordDiplomaticEventHandler`（逐条 append、自带 tick、participants ≥ 2、可按 tick/参与国过滤）。
- **`unit.SetVisionRadius` 已实现**：`SetVisionRadiusHandler` + 命令注册 + catalog + `ArmyScope` 生效链。
- **P1.2 一批后端命令已落地**：`social.MoveCity/DeleteCity/MovePopulationLots`、
  `map.MergeRegions/SplitRegion/ReassignHexes`、`sd.DeleteNation`、`MoveCapitalPlan`、`unit.SetVisionRadius`；
  账户已家户化（`actor.MoveAccount` 随 P2-A **退役**，不要再按「冻结随行」补）。
- **敏感写审批链已具备**（`Ask → AutoApproveGate → ConfirmGate → PendingApprovals`；GUI 审批页 / `simos.gm.approve`）
  ⇒ 老清单「DM 出令审批链」不是缺口；「自动批准」仍是开放问题（见 §5）。
- **`NationScope` 已存在且已注册**；「中央看全国」是 D-002 设计，不是缺口。
- **`sd.RegisterEffect` 的 SCHEDULED 效果会自动求值**（`SdTimeParticipant` → FIRED）；
  真正未接的是跨模块 `Action.EnqueueUnitCommand` 的 drain（见 §2.3）。
- **Core 的 `CommandBus.submitBatch` 早已实现**（一批一条 revision）；缺的只是 GM 的 MCP 薄工具（见 §1.4）。
- **P4b 军俸政策已落地**（`bfb246a3`）：`MilitaryPayPolicy` 第 4 组件 + `unit.SetArmyPayPolicy` +
  `MilitaryPayRuleBridge`；GM 窄工具由 D4 的 `simos.gm.armyPayPolicy` 补上。**AGENTS.md 原「P4b 未做」行已更正。**
- **D1~D4 全批已实现**（家户聚合查询、决策包、合并效果集与执行、GM 参数/上报工具）；D5 测试迁移 + 真 LLM E2E 已完成。
- 旧 `unit.TransferMembers` / `MergeStrength` / `SplitStrength` 与 `unit.SetStrength` **没有兼容层、不要按旧名找**
  （`SetStrength` 已 rename 为 `unit.set-composition`；`member:int` 已退役，人口唯一权威是 Social 家户）。

---

## 5. 待用户裁定（不定就不能开工）

1. **DM 自动派发/紧急响应**是否重裁 2026-10-01 裁定 8（禁止自动筛选/自动派发 DM）；连带「出令是否仍需人工点头」。
2. **D-026 跨市场区**是否解除 D-027 暂缓。
3. **城防/工事**数据形状与「外围→关垒→巷战」多阶段口径（是否给 City/关隘加 fortification/防御倍率）。
4. **单格命名**：overlay Region（零状态）vs 新增 `GameMap.hexLabels`（要走 Codec/往返/前端）。
5. **批工具审计缺口**：接受「批不写事件、批行取首条命令身份」，还是同批补齐。
6. **DeleteNation 级联**：补删/作废 Directive 的入口，还是正式裁定「具名拒绝即终态」。
7. **人口「城籍」口径**：按 `urban:<cityId>:` 前缀还是 residence（决定迁都能否「人随城走」）。
8. **D-006/D-009/D-012 补裁**：人力/装备键空间、「Army 格式化复写」语义、交战状态承载/生命周期/概率表结构/记录落点。
9. **铸币 M1 的待决问题**：货币产出挂 `Industry.moneyOutputPerUnit` 还是独立 `MintRule`、系数、规模表示、
   产能硬上限、政府家户人口/劳动/初始库存、GUI 写面。
10. **文化/宗教数据形状与 riba 口径**（含无息合同/利润分成替代）。
11. **默认世界配置**：`config/shell.json` 保持 `small-world` 还是回退 `v17levant`。
12. **`unit.SetComposition`/`CreateUnit` 权限与上界**：是否收紧为 GmOnly/加上界（决策人可在视野内建军/整表改装备）。
13. **SpawnArmy 凭空建军策略**：允许 `ADD_MEMBERS` 造人，还是必须从来源家户转移（当前 plan 级拒）。
14. **Region 互斥/重复征税**：是否加互斥不变式或跨 GOV 去重。

---

## 6. 本次未核到 / 需人工确认

- 未跑 Maven / 未起服务 ⇒ 所有测试计数、smoke、`rc=0` 均未复核（见页首）。
- 未读 live 世界库（`test-world` / SQLite）⇒ 审批链实际放行、可见性实际裁剪、GUI 实际渲染、MCP `tools/list` 实际内容
  均按静态代码判断。
- `DecisionDueRunner` 只按类名与调度 API 检索（全仓 0 命中）；若存在改名后的自动派发器，本次未发现。
- P2-F1/C13 的「编排/引用清理」可能有动态路径：本次只核到 handler 注册、catalog 与组合根（Shell 外 0 调用）；
  GM 仍可经 `simos.command.submit` 手工多步提交。
- 本文与用户后续新裁定冲突时，以用户最新裁定为准（AGENTS §四.1 / §十.1）。

---

## 7. 本次落盘的文档更正（2026-10-23）

| 文档 | 更正 |
|---|---|
| 本文件 | 新增：计划内未实现功能清单（代码核对版） |
| `plans/2026-10-22-decision-packet-household-query-llm-mcp-plan.md` | §10 进度：D0~D4 复选框由 `[ ]` 改正为 `[x]`（补记落地提交） |
| `AGENTS.md` | 模块表 §〇：删去「Unit 军俸政策/分摊（P4b）未做」的过时表述（改为 P4b 已落地 + P4c 残留清单） |
| `plans/2026-10-09-p2-backend-fixes.md` | 头注「P2 尚未开工」追加更正：P2-0/A~E 已落地；F1~F5 逐条现状 |
| `plans/2026-10-22-d2-decision-packet.md` | 补记：D4 实际只做了 GM `simos.gm.armyPayPolicy`；`simos.unit.setArmyPayPolicy` 是否进决策人 catalog 仍属 P4c 未定项 |
| `plans/2026-10-02-undeveloped-features.md` | 追加「2026-10-23 复核更正」节：老条目中被后续批次补齐的清单指向本文件 |
| `HANDOFF-2026-10-22-d5-decision-turn-economy.md` | 追加本次盘点入口指针 |
