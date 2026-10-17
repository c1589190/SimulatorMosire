# D-031 接口先行：口径溯源与待裁定（2026-10-15）

> 状态：**提议中**（proposed）。用途：向用户提"待裁定"之前的**口径溯源表**（根 `AGENTS.md` §五.7 → `docs/agents/02-一-并发.md` §一.12）。
> 作者：DSH 侧线（分支 `parallel/side-module`，工作树 `.claude/worktrees/parallel-side-module`）；隔壁线占 `simos-app`/`simos-ga`/`simos-gov`/`simos-ga-spi`。
> 本轮目标（用户 2026-10-15 原话，逐字）：「现在开始规划文化/生产方式/经济模块的进一步调整，现阶段不需要真的做出来复杂的文化效果，只需要把接口立好就行，根据这个目标，规划怎么写代码、出裁定」

## 一、已裁定 —— **不得重复问**

| # | 口径 | 出处 |
|---|---|---|
| 1 | 逐家户建**阶层流动表**；候选方向覆盖本 hex + 六邻 hex 的所有其他家户；经济速度分三步顺序算（负债→库存→生产收益） | 设计原稿 `D-031`（用户原话逐字） |
| 2 | 新建 **「文化与基层治理」**（家户文化/宗教标签 + 给特定生产方式加加成的表）与 **「生产与科技」**（生产方式＝用户所指产业/产出，统一前置模块 + 技术升级） | 设计原稿 `D-031` |
| 3 | 滑落/转业与**市场同周期**；各领域在**同一张共享流动表**上各算各的；**复用工单**；**人口只由 Social 校验/执行**；app/Core 只组合调度 | 设计原稿 `D-031 补充裁定`（第 2/3 次） |
| 4 | 家户生产方式**不得原地改**：只能"转移到其他/新建家户"表示；源户 mode 逐字不变、人清零后消亡 | 设计原稿 `D-022`（用户原话） |
| 5 | 人口转移带走**可移动资产份额 + 全部币种余额**（逐币种按人口比例、floor、余数留源户）；不可移动资产不得传送；流民**没有工作**（不实现主动招工） | 设计原稿 `D-023` |
| 6 | 预期利润**现算**（配方+价+真实可寻址需求），`OrganizationProfitBook` 退位为事后对账 | 设计原稿 `D-024` |
| 7 | 家户的**文化扩展点 = `HouseholdId`**（原话："稳定身份（账户、债务、culture 扩展点）"） | `POLITICAL_ECONOMY_DESIGN.md:387` |
| 8 | 机制→经济的**唯一程序内修正通道**已存在：`ProductionEfficiencyModifier(unit, perMille∈[0,2000], source, reason)`，逐 tick **替换**注入、未注入=1000‰ 中性、**必须是已持久化状态的纯函数**、**GM 不可达**；`source` 故意是自由文本，"将来若文化/天气等机制稳定再收紧" | `docs/superpowers/specs/2026-10-23-production-efficiency-framework.md` §2 写口矩阵 / §5.1 / §5.2 |
| 9 | **契约上移先例**（新 api 模块只放 id/契约/lookup，**不放**状态/Codec/ChangeSet，且 root pom `modules`+`dependencyManagement`+enforcer 三处同步） | `docs/superpowers/specs/2026-10-09-social-api-household-architecture.md` §3.1/§3.2 |
| 10 | 旧文化规划的**三个宿主候选**（`EconomyData` 加表 / actor 属性 / 组织实体成员）**不构成已定边界**，不是本轮起点 | `docs/superpowers/reports/2026-10-15-household-mobility-culture-production-investigation.md` §口径溯源表 |
| 11 | 旧规划 §4 的六个开放问题里，**第 1 问已被 D-031 部分回答**（文化＝家户标签，不再是"组织成员身份派生"）；其余五问属"复杂文化效果"，**本轮按用户新指示不做** | 同上 + 用户 2026-10-15 指示 |

## 二、现状事实（回代码核过，供设计引用）

| 事实 | 证据 |
|---|---|
| 世界状态 = `SimulationState(meta, Map<String,Snapshot> modules, info)`，模块按 `namespace()` 注册；**不提供跨模块访问器** | `simos-util/.../state/SimulationState.java:14`、`:11-12` |
| 每个领域模块要齐四件：`Snapshot`(+namespace) · `ModuleCodec` · `TimeParticipant` · 命令面；core 校验 namespace 唯一与 apply 后一致性 | `simos-core/.../advance/TimeAdvance.java:184-199`、`:676-681`；`.../command/CommandBus.java:205` |
| 跨模块只读可见性靠**provider 的 api 模块里的 lookup 接口**（例：`social/api/lookup/HouseholdLookup`） | `simos-social-api/.../lookup/HouseholdLookup.java:16` |
| `simos-economy-api` = 经济切片共用契约层（约 120 个 ID/契约），依赖 `actor-api + util + map + social-api`，**不依赖任何 simos 领域实现** | `simos-economy-api/pom.xml`（description 与 deps） |
| 生产**定义**（`ProductionMode`/`Industry`/`ProductionRecipe`）住在 `simos-economy` **实现**模块；`Industry` 一个 record 里混着定义与运行实例字段 | `simos-economy/.../model/Industry.java:126-144` |
| 抽定义的爆炸半径：`simos-economy/src/main` 有 **40 个文件**引用 `ProductionMode|ProductionRecipe` | `grep -rl` 计数（本侧线实测） |
| 门禁里每个模块要有一条**往返守卫**（`GUARDS_TABLE`） | `tools/gate.sh:95-99` |
| app 是唯一注入点先例：`updateProductionModifiers(...)` 在当日 `step(day)` **之前**由 app 参与者调用；目前**没有业务调用方**（只有测试） | `2026-10-23-...framework.md` §5.2；`grep updateProductionModifiers` 实测 |

## 三、本轮仍开放（需裁定或授权技术自决）

| ID | 事项 | 落定（2026-10-15 控制方复核；全文见 `/home/cna/SimulatorMosire/docs/superpowers/reports/2026-10-15-d031-side-line-review-decisions.md`） |
|---|---|---|
| **R1** | 生产方式"定义"本轮搬到哪 | ✅ **最终归前置模块已定**；本轮接口先行：立定义契约、查询/供给边界与**迁移计划**；旧定义暂留 `simos-economy` 但**必须标"尚未迁移"**；后续 production 成为定义与注册的**唯一权威写者**；**迁移定义 ≠ 搬运行实例/劳动分配/账户/债务** |
| **R2** | 模块数与命名/依赖面 | ✅ 两个模块：`simos-culture`（`io.mosire.simos.culture` / namespace `culture`）、`simos-production`（`io.mosire.simos.production` / namespace `production`）；**暂不拆 api+impl**；生产对外定义/lookup 契约放**生产模块的公开契约包**；现有底层 ID 复用 `economy-api`；**经济允许声明生产模块为前置依赖**；建议 `production → util/map/economy-api`、`economy → production + 现有允许面`、文化按需引用 Social/生产契约；**禁止** `production → economy` 实现、任何领域模块 → `app` |
| **R3** | 文化标签形状 | ✅ 按我给的三档（**地域认可 × 宗教 × 习俗**）落契约；挂点 `HouseholdId` |
| **R4** | 科技"掌握状态"粒度 | ✅ 用 `(scope, TechnologyId)` 键表达，**粒度作载荷选择**，不锁死形状 |
| **R5** | 共享流动表本轮落不落地 | ✅ 本轮**只出协议设计**（不实现），且**必须标明尚未替换旧双机制**；执行点＝市场结算周期；"逐 tick 评估 / 周期执行 / 追加市场轮"在 spec **分开描述**；统一周期与追加轮规则由设计给技术方案 |
| **R6** | 空壳户生命周期 | ✅ **保留**零成员 Social 家户与零人口经济壳行；残留财债校验维持，**不得为删户而清账**；D-022 的"删除"出自代理整理条款（逐字原话只要求不得原地改生产方式）；新设计**追加溯源说明，不篡改历史原话** |
| 技术自决项 | 文件布局、包名、Codec/ChangeSet、Log 门面、enforcer 行、守卫命名、接线顺序 | ✅ 我定并写进设计书；**不再打包成用户裁决**（复核 §4：不需追加业务裁决） |

## 三·补、旧三稿复核（2026-10-15 侧线抽取 + 逐条判定）

| 旧稿 | 规模 / 提交 | 与新裁定的关系 |
|---|---|---|
| `plans/2026-10-04-household-culture-and-community-effects-plan.md` | 98 行 · `fb1c392c` 10-04 | 宿主候选 **A/B/C 已作废**（调查报告 §口径溯源表逐字：`旧候选宿主 A/B/C 不应重新当成已定边界`）；其 riba / 放贷额度 −900‰ 需求见下 |
| `specs/2026-10-23-production-efficiency-framework.md` | 394 行 · `db4f899d` **10-07T04:40** | ★ **日期疑点**（文件名晚 16 天）⇒ 它**早于 D-031**，不可能反映新裁定；但 §5 的 `ProductionEfficiencyModifier` 注入契约**是唯一程序内修正通道且已落地**，仍作本轮接口基座 |
| `specs/2026-10-09-social-api-household-architecture.md` | 160 行 · `37f0a900` 10-05 | ★ 日期疑点（文件名晚 4 天、头部自称"用户 2026-10-08 裁定"，三处互不吻合）；§3.1 的**契约上移先例仍有效**；其 `List<PeopleLotId> memberLots` 形状**已被实现取代**为份额语义 `Map<PeopleLotId,Long> members`（`Household.java:56-61`） |

★ **术语撞车警告（必须写进新设计）**：`2026-10-23-production-efficiency-framework.md:286` 的「**方案 3 延后**」指的是"效率注入 **SPI 贡献者**"，
**不是** 2026-10-04 稿的宿主候选 A/B/C。新文档引用此句**必须改写措辞**，否则会被读成"文化模块方案三被延后"。

★ **riba 需求处置（本侧线裁定，非用户裁定）**：旧稿要的"穆斯林家户放贷额度 −900‰ / 无息形态"在 D-031 三段原话中**一次未出现，也从未被明文废除**，
仍挂在未完成清单上 ⇒ 按「**需求未撤回、本轮不实现、接口不挡死**」处理：文化输出契约做成**具名、可叠、可溯源、带作用域的修正值**（旧稿 §2.1 原话），
本轮**只实现 `productionAptitude`（生产熟练度/适应）一个维度**；信用/利息维度留为**兼容扩展**（新增枚举值即可，不改契约形状）。若用户认为该需求已废，另行记档。

## 四、不变量（任何方案都必须守）

1. 铁律 2：一切写走 `Command → ChangeSet → Revision`；新模块不得开旁路。
2. 铁律 3/4：新模块只拥有自己的数据。★ **2026-10-15 复核修正**：禁止的是「经济依赖**生产的实现**」与「任何领域模块 → `app`」；**经济可以只读依赖生产模块的公开契约包**（"经济永不依赖前置生产"是侧线自加约束，**作废**）。跨模块读写仍只走契约 + app 编排；新允许面须同步 POM/enforcer/边界表/测试清单。
3. 铁律 5：新模块的变更集从完整状态类型派生，**必须有往返不变式守卫**，并进 `tools/gate.sh` 的 `GUARDS_TABLE`。
4. 文化与科技的输出必须是**已持久化状态的纯函数**（同 `§5.2` 的确定性要求），否则回放不收敛。
5. 未接线的机制默认**中性**：无文化数据 ⇒ 1000‰；无技术数据 ⇒ 现有默认生产方式与配方不变（"接口立好"不得改变当前世界的数值行为）。
