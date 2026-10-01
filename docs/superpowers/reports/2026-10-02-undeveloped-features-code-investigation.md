# 未开发功能清单 · 代码级复核与实现批次建议（2026-10-02）

> **性质**：只读调查。未跑 Maven/构建/测试，未写世界，未改任何生产代码。
> **基线**：分支 `refactor/class-first-economy` @ `aff48be9`；世界 `/home/cna/simos-testspace/worlds/dashu-v2` 在调查期间 head 126 → 151（tick 仍 120，期间有 `player:gui` 的 `map.SetTerrain` 写入，说明有并行会话在用 GUI 编辑）。
> **四条分线报告（逐条证据全文）**：
> - 军事/单位/战斗：`.superpowers/sdd/2026-10-02-undeveloped-features/A-unit-military.md`
> - 社会/人口/城市/人物：`.superpowers/sdd/2026-10-02-undeveloped-features/B-social-population.md`
> - SD/外交/决策人/事件：`.superpowers/sdd/2026-10-02-undeveloped-features/C-sd-diplomacy.md`
> - 行政/基础设施：`.superpowers/sdd/2026-10-02-undeveloped-features/D-admin-infra.md`

---

## 一、先更正六处"照清单会做错方向"的描述

| # | 清单/日志原话 | 代码事实 | 证据 |
|---|---|---|---|
| 1 | 缺 `simos.command.submitBatch` | **Core 的原子批量提交早已存在**：`CommandBus.submitBatch`（一批命令 = 一条 revision，失败整批不留行），23 个组合工具在用它；运行世界 rev2 的 `core.SubmitBatch` 就是这个落盘标签。**缺的只是 GM/MCP 通用工具**。另注意批路径有两个审计缺口：不写事件（单条路径写）、批行身份只取首条命令 | `simos-core/.../CommandBus.java:265-306`、`:428-441`、`:94`；`CoreSimos.java:207-212` |
| 2 | `RegisterEffect` 效果"不会随 advance 自动执行" | **只对跨模块动作成立**：`simos.advance` 会跑 `SdTimeParticipant`，逐日求值 trigger ⇒ `FIRED`，并内联执行 `PutInfo`/`SetStage`，还按 `CombatStage.exit` 自动翻阶段；`sd.AdjudicateTick` **反而完全不处理 effects**。真正不自动的是 `Action.EnqueueUnitCommand`（`SdCommandDrain` 未接进生产 advance 路径，`Shell.advanceAndDrain` 零调用方）；`Action.RecordCasualties` 被命令层接受但执行层 `default -> {}` **静默无动作** | `SdTimeParticipant.java:94-120`、`:164-211`；`AdvanceTool.java:103-121`；`Shell.java:1121-1132`；`SdCommandDrain.java:46-88` |
| 3 | `simos.state.resolve` 对 `sd:nation/...` 无候选 | **语法用错**：canonical 是点号 `sd:nation.大蜀`（解析器只在 `:` 与容忍点号处分段），`sd:nation/大蜀` 斜杠返回空。解析面本身认识 nation/army。真正缺的是 **Nation/Army 清单+详情读口** | `AddressParser.java:14-35`、`:66-89`；`SdResolver.java:65-88`；`SimosToolSource.java:585-650` |
| 4 | "NationScope（若有）" | **NationScope 已存在且已注册**（`Gov/Nation/Army` 三个内置实现）。当前世界 46 个 DM **全是 Gov 归属**，缺的是 Nation 归属 DM 实例；占领区 tag 是 `Nation` 而非 `nation:大蜀`，Nation DM 仍看不到占领区 | `DecisionScopeFunctions.java:46-53`；`NationScope.java:46`；世界 GET 实测 |
| 5 | DM 出令"可能停在 pending"是缺口 | **审批链已具备、可观测、可裁决**：敏感写 ⇒ `Ask` → `AutoApproveGate → ConfirmGate` ⇒ 登记 `PendingApprovals` 阻塞等人（5 分钟超时）；GUI 审批页 / `POST /api/approvals/{id}` 与 MCP `simos.gm.approve` 可批；`simos.gm.approvals` 可读；`run-decision-makers` 明示不代批。这是设计口径，不是缺失 | `AbstractNarrowWriteTool.java:175-176`；`Shell.java:683-690`、`:212`；`GmApproveTool.java:53-161`；`RunDecisionMakersTool.java:46-67` |
| 6 | `simos.map.overview.cities` 为空 = social 无读口 | 根因是 **map 侧 `GameMap.cities` 没有写入者**（社会城市在 `SocialData.cities`）；`ApiViews.cities` 已把 social 城市视图做好且 GUI 在用（`GET /api/social/cities`）。缺的只是 MCP 读工具 + `ToolSupport.subjectVisible` 的 `social.city` 分支 | `ToolSupport.java:604-616`；`ApiViews.java:382-409`；`GuiServer.java:660-668`；`SimosToolSource.java:585-650` |

**调查中新发现的三处隐患**（清单未列）：

- `social.UpdateCity` 对未知键（包括 `at`）**静默忽略**：载荷没有未知键校验，调用方会以为改了。`UpdateCityHandler.java:62-71`、`SocialPayloads.java:36-48`。
- Region 之间**没有互斥不变式**（允许重叠）；两个 GOV 同时辖同一 hex 且税率 > 0 时会**重复征税**（`JurisdictionDailyTax` 无跨 GOV 去重）。`RegionOperations.java:23-27`；`JurisdictionDailyTax.java:132-226`。
- `unit.SetStrength` / `unit.CreateUnit` **未标 `GmOnly`**（可嵌决策令、可经通用提交）且无上界校验 ⇒ 决策人能在自己视野内凭空增兵；`unit.ApplyCasualties` 有逐项上界。`DirectiveWhitelist.java:31-47`；`Shell.java:585-591`；`UnitOperations.java:114-129`。

---

## 二、逐条复核总表

图例：❌ 确认缺失 · ◐ 部分具备 · ✅ 已具备/误判。

### 2.1 军事/单位（A 线）

| 条目 | 复核 | 一句话结论 |
|---|---|---|
| `unit.TransferMembers`/`MergeStrength`/`SplitStrength` | ❌ | `MergeFormation` 只 attach（member/equipment 原样带），`SplitFormation` 只 detach 既有子节点；无转移/并/拆兵力命令 |
| 按人数拆兵（留守备） | ❌ | 无原子命令；workaround = `CreateUnit` + 主军 `SetStrength`/`ApplyCasualties` + `AttachUnit`，多 revision 非原子、无守恒 |
| 休整（RESTING） | ◐ | 只有 500‰ 速度因子 + 阻断 merge/自动回归；无恢复/士气/补给/训练字段；`enqueue_unit_command` 效果生产路径不 drain |
| 攻城/炮击/城防/工事 | ◐ | `sd.CreateCombat` 等五命令是 GM 裁决式抽象表；`CommitCombatOutcome` 手工选结局、`RecordCasualties` 只写 sd 不改 unit；siege/bombard 词界 0 命中；`OutcomeOption.casualties` 与 min/maxDuration 只存不读 |
| 装备目录/语义 | ◐ | `equipment` 是自由 `Map<String,Integer>`；写口 3 个、读口 2 个；无目录/炮械/甲胄语义 |
| `unit.SetVisionRadius` | ❌ | 字段默认 1、`CreateUnit` 写死且忽略载荷里的该键；唯一消费 `ArmyVision → ArmyScope`；无设值命令、`ApiViews.unit` 也不发该键 |
| 军力守恒/原子性 | ◐ | `ApplyCasualties` 有上界；`SetStrength`/`CreateUnit` 无守恒且可嵌令；跨命令只能靠 app 层 `submitBatch` |

### 2.2 社会/人口/城市/人物（B 线）

| 条目 | 复核 | 一句话结论 |
|---|---|---|
| Person/Character/Noble/Family/Hostage | ❌ | `SocialData` 只有 populations/cities/groups；`DecisionMaker` 无名字/位置；`unit` 只有 `int member`；替代仅 `sd.PutInfo` 叙事 + 纯人员单位占位 |
| `social.MoveCity`/`DeleteCity`、`UpdateCity.at` | ❌ | `UpdateCity` 只认 id/name/props/region，`at` 静默忽略；`SocialCity` 无 `withAt` |
| 人口批次迁移 + 批次 id 读口 | ◐ | `SeedGroups` 同 id 覆盖可换 residence，但读口只给聚合、无 lot id；"城籍"按 id 前缀 `urban:<cityId>:` 判定而非 residence ⇒ 只换格搬不走城籍；无 `MovePopulationLots`；世界已有 5988 条出生批次，id 不可反推 |
| `social.CreateCity` 带人口 | ◐ | `population` 被显式拒收（城镇人口是批次派生量）；现有路径 `CreateCity + SeedGroups`（目标格需已有农村序列），非单命令、不同步 economy/actor |
| `region.seed` 指定城市 hex | ❌ | `capital` 只有 name/targetPopulation；城址=候选分最高格；传 `capitalHex` 会被 schema 静默忽略 |
| MCP 城市读口 | ❌ | 见 §一.6：map 侧空、social 视图已存在、MCP 工具与 `social.city` resolve 分支缺 |
| `MigrateHousehold`/`SetPopulation` | ◐ | 前者只搬 ClassRow.view、账不搬，且 class-first 世界具名拒；后者只覆盖旧序列、有批次时读口完全忽略 |

### 2.3 SD/外交/决策人（C 线）

| 条目 | 复核 | 一句话结论 |
|---|---|---|
| 外交关系领域（宣战/停战/同盟/关系值/最后通牒/屈服） | ❌ | SdState 10 组件无关系表、Nation 只 4 字段、78 命令无外交；替代仅 PutInfo + 军事占领 + GM 裁决式交战 |
| 称臣纳贡/附庸与朝贡 | ◐ | GOV `superiorGov` + `actor.RemitGovTreasury`（粮/布/银原子上缴，非 GmOnly、DM 可嵌令）+ 税/徭役；**零领域改动**可跑通"上缴式朝贡"，缺附庸实体/自治度/义务/期限/违约/宗主权利 |
| `sd.Nation`/`sd.Army` 读口 | ❌ | 清单+详情读工具缺；resolve 用点号语法可用（见更正 3） |
| `sd.DeleteNation` | ❌ | `CreateNation` 重复 id 拒；无删除；SdState 不校验 DM affiliation 目标 ⇒ 删除会留悬空 DM/地图归属，需严格拒绝式 |
| 中央政府 DM 全国视野 | ◐ | GovScope 直辖 jurisdiction（实测 7 格）；`NationScope` 已有，建 Nation DM 可看 `nation:大蜀` 323 格；占领区 tag=`Nation` 进不去；`SetDecisionMakerAccess` 只能收紧；扩 jurisdiction 会改税基 |
| 自动事件/紧急响应触发器 | ◐ | 见更正 2：advance 自动跑 sd 效果/阶段推进；缺口 = drain 接线、`RecordCasualties` 执行、事件/通知实体、DM 自动派发（后者与 2026-10-01 裁定 8 冲突） |
| DM 出令审批链 | ✅ | 完整、可观测、可裁决（见更正 5） |
| 多边谈判/联动裁决 | ◐ | `RunDecisionMakers` 同 revision 批量触发（各 DM 看同一快照）、`AdjudicateTick` 同 tick 共批一条 revision；无条件反射/谈判/事件实体；R4 同 `(dm,tick)` 重写是当前唯一"两轮谈判"近似 |

### 2.4 行政/基础设施（D 线）

| 条目 | 复核 | 一句话结论 |
|---|---|---|
| GM 通用 `submitBatch` | ◐ | Core 已有、缺 MCP 工具（见更正 1） |
| 迁都 `simos.gov.moveCapital` | ❌ | 无组合工具；workaround 5-6 步非原子（改首都区 hexes、改城市名/归属、GOV 位移、账目逐资源搬、jurisdiction、Info 审计）；账键 `(owner,location)` 不随 GOV 走 |
| 省界合并/拆分/重划 | ❌ | 无 `map.MergeRegions`/`SplitRegion`/`ReassignHexes`；换 hex 后 jurisdiction key / city.region（无存在性校验）/ 税率（悬空静默 gap）/ GovDemand 配编 / superior 全要人工重算；重叠区域可重复征税 |
| 单格命名（铁门坎） | ❌ | `HexCell` 只剩 height，无 hex label 组件；现路径只有 1 格 overlay Region；国家归属由 `nation:` tag 区域集合决定 |
| `actor.MoveAccount` | ❌ | GoodsAccount 四表 + `0 ≤ 冻结 ≤ 余额` 不变式；`RemitGovTreasury` 只三资源、要显式金额、不能搬冻结；无按 owner 整本搬 |
| canonical 地址归一化 | ◐ | `UnitId.parse` 收 `unit:`、`RegionId.parse` 收 `map:<mapId>:region.`；但 `actor.*` owner 仍裸值、`actor.ClearRegion` 用 `new RegionId`、`GovRemitTool` 用 `new UnitId`、`CityId.parse` 不收 `map:` |
| 组合工具模式（clearData/clearStructures/province.apply） | ✅ | 已是"固定批序 + `submitBatch` ⇒ 一批一条 revision"的样例，可直接照抄 |

---

## 三、统一实现路线图（按"解锁当前大蜀国策"排序）

> 粒度遵循 AGENTS §一.5：一个批次 = 一个写代码代理；测试统一留到最后（§三.0）。

### 批次 0 —— 读口 + 逃生口（纯 app 层，零领域状态改动，建议立即做）

| # | 交付 | 依据 | 解锁 |
|---|---|---|---|
| 0.1 | `simos.social.cities` 读工具（复用 `ApiViews.cities` + `hexVisible`）+ `subjectVisible` 补 `social.city` 分支 | B6 | 纯 MCP 流程第一次拿得到 city id/at/region/人口；是迁都/归省/迁人口全部命令的前提 |
| 0.2 | `simos.sd.nations` / `simos.sd.armies` 读工具（复用 SdQueryService + ApiViews）+ resolve 描述补点号示例 | C3 | 建 Nation DM、外交/附庸建模前的可读面 |
| 0.3 | `simos.command.submitBatch` GM 工具（复用 `CoreSimos.submitBatch`，一条 revision；注明批审计缺口） | D1 | 任意"多命令原子组合"的逃生口；兵力转移/拆守备/迁都的半成品风险立刻下降 |
| 0.4 | `simos.map.nameHex`（overlay Region 包装 `map.CreateRegion`） | D4-min | 立刻能给 `(34,-55)` 命名「铁门坎」 |

### 批次 1 —— 国策 1「北谷残兵 + 留守备 + 休整」（unit 域，单命令单 revision）

| # | 交付 | 依据 | 解锁 |
|---|---|---|---|
| 1.1 | `unit.TransferMembers` / `unit.MergeStrength` / `unit.SplitStrength`（守恒校验，零新字段/零 Codec 改动） | A1/A2 | 打散编入、按人数拆守备、并兵力 |
| 1.2 | `unit.SetStrength` / `unit.CreateUnit` 标 `GmOnly`（或加守恒/上界） | A7 | 堵住决策令凭空造兵 |
| 1.3 | `unit.RestUntil` 最小语义 + **把 `SdCommandDrain` 接回生产 advance**（`advanceAndDrain` 零调用方） | A3/C6 | 休整到期、免税到期恢复税率、定时军令真正落盘 |

验收：当前世界把残兵并入 `大蜀-army`、拆守备队、设休整到期；断言两 revision 前后 `Σmember` 与装备逐键守恒。

### 批次 2 —— 迁都与人口（解锁西陵迁都 + 旧都人口随迁）

| # | 交付 | 依据 | 依赖 |
|---|---|---|---|
| 2.1 | `actor.MoveAccount`（整本四表搬、冻结随行、建议 GmOnly） | D5 | 冻结语义裁定 |
| 2.2 | `social.MoveCity` / `social.DeleteCity` | B2 | — |
| 2.3 | 批次读口（`simos.social.population` 加 `lots[]` 或新工具）+ `social.MovePopulationLots` | B3 | "城籍"口径裁定 |
| 2.4 | `simos.gov.moveCapital` 组合工具（首都区 + GOV + 国库 + 城市归属 + 编制，一条 revision） | D2 | 2.1/2.2/2.3 |

### 批次 3 —— 国策 4「轰城」

| # | 交付 | 依据 |
|---|---|---|
| 3.1 | `simos.sd.bombard` GM 组合工具（combat 五命令 + `unit.ApplyCasualties` 同批一条 revision，GM 给结局与损失） | A4-min |
| 3.2 | 最小装备目录（至少给"炮/攻具"可判定类别，不强制迁移旧档） | A5-min |

### 批次 4 —— 外交（解锁国策 3 +「称臣纳贡」）

| # | 交付 | 依据 | 备注 |
|---|---|---|---|
| 4.1 | 朝贡最小子集：GOV `superiorGov` + `actor.RemitGovTreasury` + `sd.PutInfo` 编排（零领域改动） | C2 | 先跑通"上缴式朝贡" 3 个月再决定是否加组件 |
| 4.2 | 外交领域 `DiplomaticRelation` + 宣战/停战/最后通牒/屈服命令 + DM 外交窄工具（`sd.*` 自指禁令 ⇒ 不能直接嵌令） | C1 | 新状态组件走铁律 5 全套 |
| 4.3 | `sd.DeleteNation`（严格拒绝式） | C4 | 引用未清就拒 |
| 4.4 | 全国视野：建 Nation 归属 DM（NationScope 已有）+ 占领区 tag 处置 | C5 | 与用户确认 tag/obligation 口径 |
| 4.5 | DM 自动触发 `DecisionDueRunner` | C6 | **需用户重裁 2026-10-01 裁定 8**（禁止自动筛选 DM） |

### 批次 5 —— 人物/家族（国策 2「北谷侯一家接到成都」）

| # | 交付 | 依据 |
|---|---|---|
| 5.1 | social `Person`/`Family` 最小模型 + 移动/迁移命令；sd 侧俘虏/人质关系 | B1 |

### 批次 6 —— 长程/完整模型

区划合并/拆分/重划（D3）· canonical 统一归一（D6）· 事件/谈判模型（C8）· `region.seed` capitalHex/explicit（B5）· `createCityWithPopulation`（B4）· `SetVisionRadius`（A6）· 完整城防/工事/自动结算、morale/supply/training、完整装备目录（A3/A4/A5 的完整版）。

---

## 四、需要用户裁定的开放问题（实现前必须先定）

1. **DM 自动触发**：是否重裁 2026-10-01 裁定 8（禁止自动筛选/自动派发 DM）？自动跑 DM 还牵出"出令是否仍需人工点头"。
2. **人口批次"城籍"口径**：城的城镇人口按 id 前缀 `urban:<cityId>:` 还是按 residence 判定？这决定迁都能否"人随城走"。
3. **`MoveAccount` 冻结额语义**：冻结随行（建议）还是拒绝搬动有冻结的账？
4. **`DeleteNation` 级联策略**：严格拒绝式（推荐先做）还是级联清理 DM/国土 tag？
5. **附庸关系的落点**：先只用 GOV `superiorGov` 语义，还是新增 sd `VassalRelation` 组件（自治度/义务/期限）？
6. **DM 出令自动批准**：保持人工点头，还是为自动化模式提供会话级/类别级预授权？（AgentLib 的 `DEFAULT` 桶不可 session 授予，需要额外机制）
7. **单格命名形态**：先 overlay Region 包装（0.4，零状态改动）还是直接加 `GameMap.hexLabels` 新组件（完整但要动 Codec/往返/前端）？
8. **`submitBatch` 工具审计缺口**：接受"批不写事件、批行取首条命令身份"，还是同批补齐（会影响变更集/事件面）？

---

## 五、证据索引

- A/B/C/D 四条分线报告（含每条 `文件:行号` 证据与"未核实项"）：`.superpowers/sdd/2026-10-02-undeveloped-features/`
- 清单本体：`docs/superpowers/plans/2026-10-02-undeveloped-features.md`（已追加本日复核更正）
- 行政缺口：`docs/superpowers/plans/2026-10-02-mcp-admin-gaps.md`
- 世界侧只读调查（head 126 时点）：`/home/cna/simos-testspace/worlds/dashu-v2/logs/dashu_policy_feasibility_20261002.md`
- 命令目录快照：`/home/cna/simos-testspace/worlds/dashu-v2/logs/command_catalog.txt`

---

## 六、诚实边界

- 四条线**都没跑 Maven/编译/测试**；所有"已有测试把守"只是代码级引用，不是本轮验证。
- 四条线**都没写世界**；世界读数（head 126→151、tick 120、DM 条数、region tag）是读取时刻的 GET/日志值，世界还在被并行会话编辑。
- `sd:nation.大蜀` 的 resolve 结论来自 GUI GET + 代码路径；运行中的 MCP jar 是 `7577a399`，与工作树 `aff48be9` 可能不同版本，未交叉验证。
- 真档 raw id 未扫描（D6 兼容性前提未闭环）；未 replay head126；未起前端验证 Region overlay/命名的显示。
- 所有"实现面"是建议，不是已评审的设计；进入实现前按 AGENTS §一.5 立阶段、按 §三.0 统一测试。

---

## 七、2026-10-02 用户设计更正（本报告部分建议已作废，留痕不改写上文）

> 用户同日给定设计已入原稿 `docs/superpowers/specs/2026-10-02-architecture-design-source.md`；
> 以下建议**不得**再按原样实施：

1. **A 线批次 3「`simos.sd.bombard` 攻城组合工具 + 最小装备目录」⇒ 作废**（原稿 `D-009`）：
   轰城是**特殊交战状态**，记在 **Unit 通用交战关系状态**里；**不另开攻城命令**。
2. **B 线批次 2「Person/Character/Family/Hostage 领域模型」⇒ 作废**（原稿 `D-007`）：
   人物与家族用**通用 Unit** 承载，不另做实体。
3. **C 线批次 4.4「Nation 归属 DM 看全国 / 扩中央 jurisdiction」⇒ 作废**（原稿 `D-002`）：
   **中央政府不应有全视野**，中央—地方博弈是设计目的。
4. **附庸/朝贡的贡额/周期/违约硬编码 ⇒ 不建**（原稿 `D-004`）：
   贡额由附庸国决策人意愿决定；只做"给 XXX 政府钱"的支付工具（决策人可用，地方政府同样适用）。
5. **Unit 通用化（任意人力类型 × 多种任意装备类型 + Army 模块格式化复写）⇒ 用户设计，现状未实现**（原稿 `D-006`）：
   本报告 §2.1 的"装备自由 map / 单一 member"是**现状描述**，不是目标形态；目标以原稿 `D-006` 为准（细节待用户补裁）。
