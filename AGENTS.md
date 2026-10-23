# AGENTS.md —— SimulatorMosire 常驻索引

> **更新时间**：2026-10-23（文档系统重写）
> **本文件只做三件事**：项目简介、不可再拆的常驻红线、文档与目录路由。
> **规则正文不在本文件**：项目设计看 `docs/superpowers/`，项目维护看 `docs/agents/`。
> **禁止用 `@` 导入长文**；按需打开链接，避免一次展开数千行。

## 0. 冲突顺序（从上到下）

1. **用户当次明确指令**（逐字优先）。
2. **项目设计裁定**：代码/数据/架构/契约/公式/工具归属等设计决定。
   - 正本：`docs/superpowers/specs/2026-10-02-architecture-design-source.md`
   - 主题设计：`docs/superpowers/specs/**`（以文件头「状态 / 取代者」为准）
   - 索引：`docs/superpowers/DESIGN_RULINGS.md`
3. **项目维护裁定**：文档系统、Skill、门禁、工作流、环境、协作和提交方式。
   - 正本：`docs/agents/MAINTENANCE_RULINGS.md`
   - 系统规则：`docs/AGENTS.md`
4. **当前有效目标设计 / 契约**（先看是否已被取代，不按文件名日期猜）。
5. **本文件**（常驻路由与红线；与下层冲突时只对“路由”本身有优先权）。
6. **`docs/agents/STATUS.md`**（只表示当前事实和进行中状态，不替代设计或维护裁定）。
7. **旧文档 / 历史留痕**（只作溯源，不作开工依据）。

> **设计裁定 ≠ 维护裁定**：设计裁定属于项目设计，写进 `docs/superpowers/**`；
> 维护裁定属于项目维护，写进 `docs/agents/MAINTENANCE_RULINGS.md`。
> 同一条规则同时影响两边时，**拆开写两处或一处为主、另一处只放指针**，不得复制成两份互相竞争的正本。

## 1. 项目简介

**SimulatorMosire（simos）** 是 GSimulator 的可分模块模拟引擎重构。
- Maven reactor：**18 个 `simos-*` 模块**（含组合根 `simos-app`）。
- 领域模块只拥有自己的数据；`simos-core` 只负责组合与调度；`simos-app` 是唯一组合根。
- 参考项目：`~/DevMosire/GSimulator`（只作参考）与 `~/ProjectMosire/AgentLibMosire`（上游依赖）。
- 技术栈：Java 21、Maven Wrapper、AgentLib `0.2.3-SNAPSHOT`、Jackson、SQLite。
- 工作树规范目录：`worktree/<name>/`（不再使用 `.claude/worktrees/`）；规则见 `docs/agents/ENVIRONMENT.md`。

## 2. 常驻设计红线（五条铁律）

1. **所有查询最终解析为稳定实体。** 地址是定位方式，ID 是身份。
2. **所有修改最终表示为 `Command → ChangeSet → Revision`。** 不存在绕过该路径的写入口。
3. **所有领域模块只拥有自己的数据。** MapSimos 不知道 SocialSimos / UnitSimos 的存在。
4. **Core 只负责组合与调度**，不重新实现领域逻辑。
5. **变更集从完整状态类型派生**，并有往返不变式测试守卫：`apply(changeSet, base)` 逐字段重建 target。

### 工具归属（项目设计红线）

- 领域功能工具的实现**归各自领域模块**；各模块维护自己的工具方法、schema、描述和测试。
- 工具规范统一依赖 `simos-ga-spi`（它向上依赖 AgentLib 的 `AgentTool` 契约）。
- `simos-app` 只负责**统一装配、角色分桶、桥接与跨域编排**，不承载领域工具实现。
- 仅跨切片/宿主级工具可留在 app；必须有明确清单。
- 依据与逐字用户原话：`docs/superpowers/specs/2026-10-12-ga-module-and-tool-descent.md`；
  索引见 `docs/superpowers/DESIGN_RULINGS.md` 的「工具归属」条。

## 3. 文档路由

| 需要找什么 | 入口 |
|---|---|
| 当前状态、进行中目标、最近门禁 | `docs/agents/STATUS.md` |
| 文档系统本身的规则 | `docs/AGENTS.md` |
| 项目维护裁定（文档/Skill/门禁/协作） | `docs/agents/MAINTENANCE_RULINGS.md` |
| 当前维护操作手册 | `docs/agents/WORKFLOW.md`、`docs/agents/VERIFICATION.md`、`docs/agents/ENVIRONMENT.md`、`docs/agents/FRONTEND.md` |
| 工程 Skill 目录与规则 | `docs/agents/SKILLS.md`、`.agents/AGENTS.md` |
| 门禁/检查脚本索引 | `tools/AGENTS.md` |
| 项目设计索引 | `docs/superpowers/AGENTS.md` |
| 设计裁定索引 | `docs/superpowers/DESIGN_RULINGS.md` |
| 用户设计原稿（代码/设计裁定正本） | `docs/superpowers/specs/2026-10-02-architecture-design-source.md` |
| 历史长文与旧纪律 | `docs/agents/AGENTS.md` |
| 本机 SDD 台账/证据（不入库权威） | `.superpowers/AGENTS.md` |

## 4. 模块局部索引

> 每个模块的 `AGENTS.md` 只写本模块职责、直接依赖、工具归属、局部设计入口和验证命令；
> **不复制根规则**。进入模块目录后，该文件由 harness 按祖先链自动加载。

| 模块 | 一句话职责 | 局部索引 |
|---|---|---|
| `simos-util` | 地址、身份、时间、快照、变更集协议、通用日志机制 | [`simos-util/AGENTS.md`](simos-util/AGENTS.md) |
| `simos-ga-spi` | 工具契约、角色标签、窄读写口（依赖 AgentLib） | [`simos-ga-spi/AGENTS.md`](simos-ga-spi/AGENTS.md) |
| `simos-map` | 地图、六角格、地形、区域与地图工具 | [`simos-map/AGENTS.md`](simos-map/AGENTS.md) |
| `simos-calendar` | 儒略历、节气、季节分带的纯计算 | [`simos-calendar/AGENTS.md`](simos-calendar/AGENTS.md) |
| `simos-social-api` | 人员/家户低层身份契约 | [`simos-social-api/AGENTS.md`](simos-social-api/AGENTS.md) |
| `simos-social` | 人口、家户、需求、劳动与家户工单权威 | [`simos-social/AGENTS.md`](simos-social/AGENTS.md) |
| `simos-unit` | 单位、编制树、移动与单位命令 | [`simos-unit/AGENTS.md`](simos-unit/AGENTS.md) |
| `simos-core` | 组合、调度、Command/ChangeSet/Revision 与持久化内核 | [`simos-core/AGENTS.md`](simos-core/AGENTS.md) |
| `simos-actor-api` | ActorRef / ActorKind / AssetKind 最底层契约 | [`simos-actor-api/AGENTS.md`](simos-actor-api/AGENTS.md) |
| `simos-actor` | Actor 身份、产权、库存与 actor 命令 | [`simos-actor/AGENTS.md`](simos-actor/AGENTS.md) |
| `simos-economy-api` | 经济切片共享稳定契约 | [`simos-economy-api/AGENTS.md`](simos-economy-api/AGENTS.md) |
| `simos-economy` | 产业、市场、债务、货币、工资与财政机制 | [`simos-economy/AGENTS.md`](simos-economy/AGENTS.md) |
| `simos-production` | 生产定义、生产方式、经验/方案与生产域工具 | [`simos-production/AGENTS.md`](simos-production/AGENTS.md) |
| `simos-culture` | 家户文化、宗教/教区与熟练度规则 | [`simos-culture/AGENTS.md`](simos-culture/AGENTS.md) |
| `simos-gov` | 行政、国库预算、区划服务与外交 | [`simos-gov/AGENTS.md`](simos-gov/AGENTS.md) |
| `simos-army` | 交战记录、军队身份与军方编排 | [`simos-army/AGENTS.md`](simos-army/AGENTS.md) |
| `simos-ga` | AgentLib 运行时、决策包、GM/DM 工具面与决策运行 | [`simos-ga/AGENTS.md`](simos-ga/AGENTS.md) |
| `simos-app` | 组合根、GUI/MCP、统一 ToolSource 装配与宿主桥接 | [`simos-app/AGENTS.md`](simos-app/AGENTS.md) |

## 5. 目录级 AGENTS 规则

根到 cwd 的每一级 `AGENTS.md` 都会按祖先链加载；更具体的文件只在**本目录范围**内覆盖更宽的说明。
- 目录级 `AGENTS.md` 必须有：本目录职责、局部边界、关键入口、验证命令、当前文档入口。
- 目录级 `AGENTS.md` 不得复制根铁律、不得另立用户裁定、不得把历史状态写成当前事实。
- 新增/修改目录时，若该目录有独立工作规则，就新增局部 `AGENTS.md`；没有独立规则不硬造空文件。
- 发现局部规则与项目设计冲突：停手上报，按冲突顺序处理；不要用局部文件覆盖用户设计。

## 6. 文档维护的硬约束

- 本文件只写路由，不写当前状态快照；当前事实只写 `docs/agents/STATUS.md`。
- dated 状态文件只作历史，不在本文件当当前状态。
- 项目设计裁定与项目维护裁定分账；不得把代码设计写进维护裁定，也不得把维护流程写进设计原稿。
- 改文档后跑：`python3 tools/check-docs.py`、`python3 tools/skills-lint.py`，或 `tools/gate.sh docs`。
