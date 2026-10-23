# 设计裁定索引（项目设计）

> **本文属于项目设计，不属于项目维护。**
> 这里只做**代码/架构/数据/接口/工具面/公式**等设计裁定的索引，不复制正文。
> 维护裁定（文档/Skill/门禁/协作/环境）在 [`../agents/MAINTENANCE_RULINGS.md`](../agents/MAINTENANCE_RULINGS.md)；
> 当前状态只在 [`../agents/STATUS.md`](../agents/STATUS.md)。

## 一、最高权威：用户设计原稿

- **正本**：[`specs/2026-10-02-architecture-design-source.md`](specs/2026-10-02-architecture-design-source.md)
- **规则**：用户给定的设计 = 最终版本；不得以“实现方便/更优设计”静默改口径。新裁定必须由用户明示，再在原稿追加 D-xxx。
- **查法**：先按 D 编号；同主题看专题 spec 的文件头与取代关系；机制性描述回代码核。

## 二、关键跨切面设计裁定

| 主题 | 当前设计入口 | 说明 |
|---|---|---|
| 工具归属 / app 只组装 | [`specs/2026-10-12-ga-module-and-tool-descent.md`](specs/2026-10-12-ga-module-and-tool-descent.md) | **领域工具实现归领域模块；`ga-spi` 只放工具契约；app 只装配/桥接；仅跨切片工具留 app**。用户原话见下。 |
| 模块边界 | [`specs/2026-10-12-module-boundaries-freeze.md`](specs/2026-10-12-module-boundaries-freeze.md) + 各模块 `AGENTS.md` | 历史冻结文保留；当前直接依赖以各模块 `pom.xml` 与局部 `AGENTS.md` 为准，发现旧文少列/多列时以代码为准并另立更正。 |
| AgentLib / GM DM 决策包 | [`specs/2026-10-17-dm-gm-agentlib-decision-package-design.md`](specs/2026-10-17-dm-gm-agentlib-decision-package-design.md) | 决策包/工具面权威收口到 AgentLib 0.2.3 规范。 |
| D-032 生产运行时 | [`specs/2026-10-17-d032-production-runtime-migration-design.md`](specs/2026-10-17-d032-production-runtime-migration-design.md) | 生产物理状态、预计/实际、同 tick 原子提交、验收判据。 |
| D-032 标准小农业 v2 | [`specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md`](specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md) | 当前目标运行设计入口；旧目标设计只作历史。 |
| 统一 lot/库存/市场 | [`specs/2026-10-22-d032-unified-lot-inventory-market-design.md`](specs/2026-10-22-d032-unified-lot-inventory-market-design.md) | 商品与土地同一订单簿/撮合入口；共享预算与冻结边界。 |
| 家户文化/教区 | [`specs/2026-10-17-d033-parish-household-faith-design.md`](specs/2026-10-17-d033-parish-household-faith-design.md) | 文化身份、地方教区、共享流动表上的信奉选择；未实现部分按正文标注。 |
| 社会/单位基础设计 | [`specs/2026-09-17-social-unit-simos-design.md`](specs/2026-09-17-social-unit-simos-design.md) | 人口/家户/编制树的基础契约；被后续专题局部扩展时看新文。 |
| 聚合经济 v2 | [`specs/2026-09-25-aggregate-economy-v2-design.md`](specs/2026-09-25-aggregate-economy-v2-design.md) | 经济切片机制背景；D-032/新市场设计可能局部取代。 |

### 工具归属的原话（2026-10-12，逐字）

> 「我认为应该把各个工具的实现都下放到各个具体的功能模块，然后决策人包在哪？」

> 「旗下实现标准的决策人流程和MCP工具（给dm）然后把现有的各个工具实现下放到各个功能模块，如果要复写决策人，那直接调我上面说的这个包，app只负责组装」

> 「不是不是，为啥其他模块要写工具不能直接依赖ga这一整个模块啊？还是必须得写个ga-spi才满意」

> 「这些按照正常模块流程留app，其他的放到各个模块分别实现，我要睡了，你自己一步一步做，不需要我确认」

### 工具归属的最新补裁（2026-10-23，逐字）

> 「所有对应模块的功能工具，全从app下放到各自模块里」

> 「工具规范统一依赖agentlib来实现通用」

> 「各自维护方法、统一装配」

**落点解释**：领域模块拥有工具实现、方法、schema 与测试；共同协议通过 `simos-ga-spi` 统一到 AgentLib `AgentTool`；
`simos-app` 作为组合根统一装配各模块的 ToolSource，不实现领域工具。该补裁与 2026-10-12 原话一致，细化的是“方法归属”和“统一装配”两点。

**当前实现事实**：app 等目录仍有历史工具/迁移缺口；那是实现状态，不是设计已改变的证据。
发现工具归属漂移时，按本设计修实现，不得用 `STATUS` 或旧 app 代码反推设计。

## 三、设计原稿 D-xxx 索引

> 下列条目来自 `specs/2026-10-02-architecture-design-source.md` 的顶层标题；局部取代以正文的「局部取代者/补裁」为准。

| 编号 | 主题 | 正本 |
|---|---|---|
| `D-001` | 设计权威：用户给定 = 最终版本 | `specs/2026-10-02-architecture-design-source.md` |
| `D-002` | 中央政府不设全视野（中央—地方博弈是设计目的） | `specs/2026-10-02-architecture-design-source.md` |
| `D-003` | 外交关系 = 自然语言边表（尽量小，先做语义） | `specs/2026-10-02-architecture-design-source.md` |
| `D-004` | 称臣纳贡 = 特殊外交关系；不写死贡额；只做"给 XXX 政府钱"的工具 | `specs/2026-10-02-architecture-design-source.md` |
| `D-005` | 谈判 / 联动裁决的记录位置 | `specs/2026-10-02-architecture-design-source.md` |
| `D-006` | 通用 Unit 接口：任意人力类型 × 多种任意装备类型；Army 模块做格式化复写（★ 现状：未实现）（含补裁） | `specs/2026-10-02-architecture-design-source.md` |
| `D-007` | 人物 / 家族 = 通用 Unit（不单独做家族实体） | `specs/2026-10-02-architecture-design-source.md` |
| `D-008` | 地图 / 地形问题后置 | `specs/2026-10-02-architecture-design-source.md` |
| `D-009` | 轰炸城池 = 特殊交战状态（记在 Unit 通用交战关系状态里；不另开攻城命令）（含补裁） | `specs/2026-10-02-architecture-design-source.md` |
| `D-010` | Unit 与 Army 的职责边界（2026-10-02 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-011` | 不兼容与测试世界替换（2026-10-02 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-012` | 交战记录归 Army；Unit 只记"状态 ↔ 状态描述地址"（2026-10-02 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-013` | v17l 地图必须整体重建（含其中所有区域）—— 用户 2026-10-02 口述 | `specs/2026-10-02-architecture-design-source.md` |
| `D-014` | 区域编辑点选 + GM 区域重合检查读口（用户 2026-10-02 口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-015` | 编制浮层正方形 + 树视图缩放（用户 2026-10-02 口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-016` | 区域点选按"当前选中 tag"的最后覆盖区域（用户 2026-10-02 口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-017` | 当前 tick 战斗事件浮层（用户 2026-10-02 口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-018` | 年份系统：儒略历 + `simos-calendar` + 季节系统（2026-10-02 用户口述/选项裁决） | `specs/2026-10-02-architecture-design-source.md` |
| `D-019` | `simos-calendar` 模块依赖 = `simos-util` + `simos-map`（2026-10-02 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-020` | GUI：时间线显示 tick + 具体日期；点 hex 显示该格季节（2026-10-02 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-021` | 事件浮层：战况可滚动 + 一键复制战况文本（2026-10-02 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-022` | 家户生产方式转变 = 转移到其他/新建家户，不原地改当前家户（2026-10-06 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-023` | 偿还介质/估值、资产随迁与流民语义（2026-10-06 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-024` | 候选生产方式的预期利润 = 配方 + 市场价 + 真实需求；不得用"有没有既存读数"当门（2026-10-06 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-025` | 饥荒/城市衰亡合法：城市化率是派生量，不作为维持性判据（2026-10-06 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-026` | 市场区 = map 的 Region（GM 可编辑）；节点由经济聚集自然生成，不由半径划区（2026-10-06 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-027` | 先跑通单个市场区：同币即同区；分单 hex 贸易成本与市场总调控；跨市场区暂缓（2026-10-06 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-030` | 市场信用：挂单即可借、钱优先借、借啥欠啥；还款货币第一、商品按数量降序（2026-10-06 用户口述） | `specs/2026-10-02-architecture-design-source.md` |
| `D-031` | 家户逐 tick 流动表、文化与基层治理、生产与科技（2026-10-15 用户原话；调查阶段） | `specs/2026-10-02-architecture-design-source.md` |
| `D-032` | 生产方案、家户经验、可配置公式与市场选择（2026-10-17） | `specs/2026-10-02-architecture-design-source.md` |
| `D-033` | 家户文化、地方教区与择优信奉（2026-10-17） | `specs/2026-10-02-architecture-design-source.md` |

## 四、专题设计与计划的边界

- `specs/**`：设计与契约，能作为实现依据。
- `plans/**`：按设计拆出的执行计划；不是设计本身，计划里的数字是预测。
- `reports/**`：调查/证据，不等于设计裁定。
- `status/**`：历史快照，不构成设计权威。
- 维护流程（如何派单、如何验证、文档怎么写）去 [`../agents/`](../agents/AGENTS.md)，不要写进设计原稿。

## 五、维护方式

1. 新设计先判断是否已有 D-xxx/专题；已有则更新对应正本，不重复问用户。
2. 小改原地更新目标运行段并补「修订记录」；大改新建文档 + 双向取代头。
3. 新 D-xxx 在本索引登记主题，正文仍只在设计原稿；索引不复制全文。
