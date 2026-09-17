# SimulatorMosire 总体设计（总纲）

**日期**：2026-09-16
**状态**：已裁决，待实现
**范围**：五模块的边界、依赖方向、跨模块协议与里程碑。**不含**各模块内部细节设计——那些由各模块自己的 spec 承担（见 §十）。

---

## 〇 背景

本文档是 SimulatorMosire（简称 **simos**）的立项目标与架构基线。simos 是 GSimulator 的重构：把原本单一功能的地图推演工具，解放为**可分模块生长**的模拟引擎。

**参考项目**：
- `~/DevMosire/GSimulator` —— 地图/六边形/区域的既有实现（**只作参考，不作依赖**）
- `~/ProjectMosire/AgentLibMosire` —— Agent 基础设施（CoreSimos 将整包依赖）

### 0.1 用户裁决记录（2026-09-16）

| 议题 | 裁决 |
|---|---|
| 世界状态组织 | **组合式**。`GameMap` 不再充当世界容器 |
| 时间与版本 | **彻底拆开**。`Timestamp`（模拟时间）与 `Revision`（数据版本）正交 |
| 修改路径 | **统一走 `Command → ChangeSet → Revision`** |
| 追加要求 | **完善的 log 记录，方便 debug**（见 §八） |
| 存储底座 | **混合**：日志走 SQLite，快照走 JSON |
| 服务拓扑 | **合并**：5711 主服务 + 5715 MCP（放弃 5712~5714 的独立进程方案） |
| `UtilSimos` 依赖 | **不依赖** `AgentLibMosire` |
| `AgentLibMosire` 获取 | **重新 install + 升固定版本号** |

---

## 一、调查结论：GSimulator 的资产与负债

三支独立调查小组的结论互相印证。以下每条都经源码核实。

### 1.1 可继承的资产

| 资产 | 位置 | 继承什么 |
|---|---|---|
| **插件式地址解析 SPI** | `gsim-agentsmanager/ref/`（`Resolver` / `ResolverRegistry` / `ResolverContext` / `RefResolver`） | 按 `prefix:` 分发 + 空前缀兜底 + 依赖倒置注册的骨架。**方向正确**，需从单层前缀扩展到多层嵌套 + 强类型返回 |
| **附件外挂机制** | `gsim-core/worldinfo/loader/NodeLoader.saveAttachmentFile` | 内存存轻引用（`{"_file":..., "_type":"external"}`）、数据落独立文件、写失败回滚删除防孤儿。**"领域数据不污染主模型"的范式** |
| **边的稀疏存储 + 属性 schema** | `MapData.edges` + `PathwayGroup.properties` | `edgeKey → pathwayId → {prop}` 三层稀疏 map，只存非默认值；属性带 `PropertyDef` schema |
| **连通链追踪算法** | `MapService.traceChains` | 先在度≠2 的节点处切链、再兜底处理纯环。可直接移植 |
| **程序化地形生成** | `MapGenerator` + `ContourQueryEngine` + `SimplexNoise` | 山脊定向造山、域扭曲、多频段叠加、河谷惩罚的合成公式（`height = ridge*0.68 + shelf*0.35 + multi*0.45 - valley`，`pow(h,0.92)`） |

### 1.2 必须抛弃的负债

| # | 缺陷 | 证据 | 后果 |
|---|---|---|---|
| L1 | **子节点无法持久化 `edges`/`terrainTypes`/`pathwayGroups`/`terrainBlocks`** | `MapDiff` 只有 10 字段而 `MapData` 有 12；`MapResolver.applyDiff` 对这四个永远读 base | 非 root 节点写连通性**静默丢失**。`worlds/default` 活跃节点是 n0007、root 是 n0000，**这是默认路径** |
| L2 | **两套 hex 连通性存储并存** | `HexCell.edgeTags`（前端 `pathway.js` 写）vs `MapData.edges`（MCP 工具写） | 两个 store 漂移，数据不一致 |
| L3 | **方向数组语义错位** | `TerrainGeometry.DIRS` = `[E,SE,SW,W,NW,NE]`；`MapService.HEX_DIRS` = `[E,NE,NW,W,SW,SE]` | `edgeTags[i]` 与 `HEX_DIRS[i]` 在 i=1..4 上指向**不同方向** |
| L4 | **"区域"有三个互不相关的概念** | `Province`（hex 列表，无边界）/ `CompressedRegion`（渲染缓存）/ `ContourLayer`（编辑层） | 工具操作的是 `Province`，而它**根本没有闭环边界** |
| L5 | **Province 归属查询 O(区域数 × hex 数)** | `prov.hexes().contains(key)` 对 `List<String>` 线性扫描 | 每次 `gsimap:hex:` 解析都全表扫，无反向索引 |
| L6 | **坐标与地址表述不一致** | 落盘键是 `"q_r"` 字符串；代码中**不存在** `[q,r]` 数组形式 | `(4,3)` 只是展示层打印格式 |
| L7 | **无海拔、无种子落盘** | `MapData` 无这两个字段；`MapService.generate` 不调 `saveContour` | 高度信息生成后完全丢失；MCP 生成的地图**无法复现** |
| L8 | **`new MapData(...)` 12 参数构造复制 12 次** | `MapService` 内 12 处 | 加字段要改全部调用点——这是"6 处重建时 edges 硬编码 `Map.of()`"事故的根因 |
| L9 | **地形词表分裂** | `MapData.defaults()` 8 种（无 lowland）vs `MapGenerator.defaultTerrainTypes()` 9 种 | 实测落盘数据用生成器版，两套并存 |
| L10 | **文档严重滞后** | `CLAUDE.md`/`README.md` 写的 `gsim-agentlib`/`gsim-agent` 模块**磁盘上不存在**；`docs/ARCHITECTURE.md` 更旧 | 文档不可信，只能读源码 |
| L11 | **SubAgent 运行期无权限门禁** | `AbstractAgent.beforeToolExecute()` 直接 `return true`；工具过滤只在 prompt 构造期生效 | **真实提权面**：LLM 只要"记得"工具名就能执行写操作 |
| L12 | **`ToolCategoryRegistry` 静态硬编码表与工具声明长期不同步** | `query_by_tag`/`query_address`/`attachment_read`/`attachment_write` 等均不在表内，未知工具**默认 MUTATING** | 只读工具被要求用户确认"写入操作" |

### 1.3 从 L1 提炼的核心教训（**本项目的设计原则来源**）

`MapDiff` 是**手工对着 `MapData` 维护**的：`MapData` 加字段时，`MapDiff` 没人提醒要跟着加。四个字段漂移出去，**既无编译期也无测试期护栏**。

> **由此确立本项目第一条派生原则**（§二 铁律 5）：
> 变更集必须**从完整状态的类型定义派生**，不能手工维护。

---

## 二、五条铁律

写进 README 第一行，长期守卫：

1. **所有查询最终解析为稳定实体。** 地址是定位方式，ID 是身份。单位调动、区域改名，历史与 Info 都不断。
2. **所有修改最终表示为 `Command → ChangeSet → Revision`。** 不存在绕过该路径的写入口。
3. **所有领域模块只拥有自己的数据。** `MapSimos` 永远不知道 `SocialSimos` / `UnitSimos` 存在。
4. **Core 只负责组合与调度，不重新实现领域逻辑。**
5. **变更集从完整状态类型派生，且有往返不变式测试守卫。**
   `apply(changeSet, base)` 必须逐字段重建出 target；该断言作为测试不变量长期存在。

---

## 三、模块划分与依赖方向

> ⚠️ **本节经 `2026-09-18-spi-layering-design.md`（ADR-1）修订。** 修订处：§3.1 的最后一条。
> 图的画法与其余条文不变。ADR-1 记的是「为什么不拆 `simos-spi`」与「Core 的依赖为什么要收窄」。

```
                    ┌──────────────────────┐
                    │     UtilSimos        │  零领域依赖
                    │  地址/身份/时间/版本    │  仅 Jackson + SLF4J
                    │  快照/变更集/命令协议   │  不依赖 AgentLibMosire
                    │  Info/时态序列/Facet   │  不碰文件系统
                    │     Resolver SPI     │
                    └──────────┬───────────┘
                               │
                    ┌──────────▼───────────┐
                    │      MapSimos        │  六边形网格/地形/区域
                    │   （不做任何存储）      │  连通性/生成/寻址实现
                    └──────────┬───────────┘
                               │
              ┌────────────────┴────────────────┐
              ▼                                 ▼
   ┌─────────────────────┐          ┌─────────────────────┐
   │    SocialSimos      │          │     UnitSimos       │
   │  hex 人口时态序列     │          │  编制树/装备/人数     │
   │  增长率分段积分       │          │  移动与寻路/位置继承   │
   └──────────┬──────────┘          └──────────┬──────────┘
              └────────────────┬───────────────┘
                               ▼
                    ┌──────────────────────┐
                    │      CoreSimos       │  时间线 DAG / 时间引擎
                    │  + AgentLibMosire    │  Command Bus / 存储
                    │                      │  AgentBinding / GUI / MCP
                    └──────────────────────┘
```

> 图为**直接依赖**的简化画法。`SocialSimos` / `UnitSimos` 同时**直接**依赖 `UtilSimos`（不只经由 `MapSimos` 传递）；权威依赖表见 §10.2。

### 3.1 依赖硬约束

- `MapSimos` 依赖 `UtilSimos`，**永远不 import `SocialSimos` / `UnitSimos`**。
- `SocialSimos` 与 `UnitSimos` **互不依赖**。
- `UtilSimos` 不依赖任何 simos 模块，不依赖 `AgentLibMosire`，不碰文件系统。
- ~~`CoreSimos` 是唯一知晓全部模块的集成点。~~
  **（ADR-1 修订）** `CoreSimos` 的 **main scope 只依赖 `UtilSimos`**（+ `AgentLibMosire`）；
  map / social / unit 退到 **test scope**。⇒ 铁律 4 由**结构**保证，不再靠自觉：
  Core 编译期看不见任何领域类型，**想重新实现领域逻辑也无从下手**。
  具体模块的装配归 **app 层**（M5 的 GUI / MCP），它们依赖 core + 具体模块。
  此约束由 `simos-core` 自己的 `bannedDependencies` 在构建期强制（含故意违规用例自证）。

### 3.2 跨模块可见性：Facet 扩展查询

"某个 hex 上有哪些单位"**不能**写成 `MapManager.getUnitsAt(hex)`——那会让 MapSimos 知道 UnitSimos 存在。

**解法**：Util 提供 Facet 协议，各领域模块自己注册提供者。

```java
// UtilSimos 定义
interface FacetProvider {
    String facetName();                                  // "unitsHere" / "population"
    List<FacetEntry> query(Address subject, ResolveContext ctx);
}
```

于是 `inspect map:Map1:hex.4_3` 的返回是：

```
Hex [4,3]
  Map    : terrain=Plain, height=12, pass=1.0
  Social : population=5400
  Unit   : 1班, 迫击炮排
  Info   : description="河口渡口"
```

`MapSimos` 对这些扩展**完全不知情**。

---

## 四、UtilSimos：八大件（本项目成败点）

> 架构审查原话：「这八个一旦写歪，后面所有模块一起歪；这八个一旦写稳，Map/Social/Unit 反而都是相对普通的领域实现。」**已确认采纳。**

### 4.1 八个原语

| 件 | 定义 | 关键决策 |
|---|---|---|
| **Address** | 地址 AST，解析为段序列 | 两层地址（§4.2） |
| **SubjectId** | 稳定身份 `(namespace, localId)` | 与地址**解耦** |
| **Timestamp** | 模拟时间 `(long tick, Optional<String> calendarLabel)` | 与 Revision 正交 |
| **Revision** | `(BranchId, RevisionId)` → `StateRef` | 状态的唯一坐标 |
| **Snapshot** | 某 ref 的完整状态切片（接口协议） | **不是万能父类** |
| **ChangeSet** | 相对 base revision 的增量（接口协议） | 从状态类型派生（铁律 5） |
| **Command** | 意图，含 `expectedRevision` | 乐观并发 |
| **SimulationState** | `{meta, map, social, units, info}` 组合容器 | 无跨模块访问器 |

### 4.2 两层地址（采自架构审查提议 7）

**Human Address** 允许模糊，可多解，全部列出：

```
map:Map1:[4,3]                  ← 人类友好形式
map:Map1:"Nation"."区域A"
```

**Canonical Address** 唯一，机器协议**只用这个**：

```
map:Map1:hex.4_3
map:Map1:region.Nation.区域A
```

解析 Human Address 时返回候选列表：

```java
record QueryResult(List<ResolvedSubject> candidates)
record ResolvedSubject(SubjectId id, String canonicalAddress, String typeName)
```

Agent 输入模糊地址 → Resolver 列出候选 → Agent 选定 → **系统回传 canonical**，此后所有调用都用 canonical。

> **理由**：Simos 后面明确有 MCP / Agent / GUI / 序列化 / 历史 diff。`instanceof` 兜底在本地 Java 调试能用，但**不能作为协议**——MCP 客户端不知道 `instanceof TerraType` 是什么，diff 也不知道该改谁。

### 4.3 地址分段规则

**段间只用** `:` **分隔**；`.` **只在段内使用**（`kind.name` 形式，其中 `name` 自身可再含 `.` 作命名空间内的限定，如 `region.Nation.区域A`）。这条分工正是下面"删除点号属性形式"的直接后果：两种段分隔符会让 AST 与转义规则的复杂度翻倍。

段类型：

| 段类型 | 形式 | 例 |
|---|---|---|
| Namespace | `<ident>` | `map` / `social` / `unit` / `agent` |
| Entity | `<kind>.<name>` | `hex.4_3` / `terra.Grass` / `region.Nation.区域A` / `conn.river.r-f82a` |
| Index | `[q,r]` 或 `[i]` | `[4,3]` |
| Property | `<ident>` | `population` / `height` / `member` |

**已裁决的三处删除**（2026-09-16）：

1. ~~`map:(MapName):(TerraName).height` 点号属性形式~~ → 统一为 `map:Map1:terra.Grass:height`。理由：点号嵌套会让 AST 出现两种分隔符，解析复杂度与歧义翻倍。
2. ~~"强行输入 Map1 和 Map2 完整数据集算 diff"~~ → 只保留"基于已存 revision 输出增量"。理由：与 `ChangeSet.baseRevision` 语义冲突，且 Core 已持有完整状态。
3. ~~连通性无地址~~ → 定为 `map:Map1:conn.river.<稳定ID>`。理由：河道会改道，坐标对会失效。

### 4.4 各命名空间地址样例（冻结）

> 下表中 `U` 代表 `高地人旅指挥部.1营指挥部.1连指挥部.1排指挥部.1班`，**仅用于本文档排版压缩，不进入实现**。

```
map:Map1                                整张地图
map:Map1:hex.4_3                        单个地形块（canonical 用落盘键 "q_r" 形式）
map:Map1:terra.Grass                    地形类型
map:Map1:terra.Grass:height             地形类型的单个属性
map:Map1:region.Nation.区域A             区域
map:Map1:region.Nation.区域A:hexes       区域的覆盖集合
map:Map1:conn.river.r-f82a              连通性线段（稳定 ID，非坐标对）

social:Map1:hex.4_3:population          人口时态序列
social:Map1:hex.4_3:population_growth   人口增长率时态序列

unit:U                                  编制树上的一个小队
unit:U:member                           人数
unit:U:equipment.步枪                    装备数量
unit:U:hex                              位置（无则向父取）
unit:U:speed                            速度

agent:map:Map1:region.Nation.区域A       按主体查决策人 —— Human 形式
agent:bind.b-f82a                       决策绑定记录本体 —— canonical 形式
```

### 4.5 不用继承：三个协议接口

**明确不采用**万能父类 / `BaseSimosObject`：

```java
interface Snapshot  { StateRef ref(); SimosTimestamp timestamp(); }
interface ChangeSet { RevisionId baseRevision(); }
interface Command   { RevisionId expectedRevision(); }
```

`MapSnapshot` / `SocialSnapshot` / `UnitSnapshot` 是**各自独立的 record 树**，只实现 `Snapshot`。

> **理由**：这些不是同一种 is-a 关系。`Info` 尤其明显——它能挂到 `map` 命名空间本身、`map:Map1`、`map:Map1:hex.4_3`、甚至 `map:Map1:terra.Grass:height` 上，显然不是继承关系。

### 4.6 Info：外挂时态属性系统

```java
record InfoEntry(String key, Object value, TimeRange valid,
                 SubjectId source, Optional<String> note)
interface InfoSystem {              // 不挂在任何 Java 对象上，是外挂关系
    Optional<InfoEntry> get(Address subject, SimosTimestamp at);
    void put(Address subject, InfoEntry entry);
}
```

**Info ≠ 领域字段**（架构审查 16，已采纳）：

- `TerraType.height` **影响河流生成** ⇒ 是 `MapSimos` 的领域字段
- `info.description` / `info.alias` / `info.ui.icon` / `info.lore` ⇒ 是 Info
- **禁止**有人写 `info.height = 5` 然后期待河流生成器认它

否则 Info 会退化成绕过领域模型的 JSON 垃圾桶。

### 4.7 TemporalSeries：分段常量 + 离散事件

```java
interface TemporalSeries<T> {
    T valueAt(SimosTimestamp t);      // 即时积分，不物化中间点
    List<Segment<T>> segments();      // 分段常量
    List<Event<T>> events();          // 离散跳变
}
```

**不储存所有状态**（架构审查 4，已采纳）。人口：

```
t=0    population = 10000   (anchor)
t=0    growth = 2%          (segment)
t=20   growth = 1%          (segment)
t=70   growth = -3%         (segment)
t=45   战争死亡 -800         (event)
```

`populationAt(53)` 由 anchor + 分段积分 + 事件**即时算出**，不需要存 `Population(1)..Population(53)`。

**信息量佐证**：实测最大地图 JSON 已达 **4.3 MB**（`worlds/f3qa/nodes/n0000_map.json`）。全量快照 × 时间点会立刻爆炸。

### 4.8 Resolver SPI

在 GSimulator 的 `Resolver` 基础上做两处扩展：

```java
interface Resolver {
    String namespace();                                    // "map" / "social" / "unit"
    List<ResolvedSubject> resolve(Address address, ResolveContext ctx);
}
```

| 扩展点 | GSimulator 现状 | Simos |
|---|---|---|
| 分发层级 | 单层前缀 | **多层嵌套**（namespace → entity → property） |
| 返回类型 | `ResolvedRef(source,id,title,content)`，`content` 只有 `String` | **强类型** `ResolvedSubject` + 具体数据集 |
| 歧义处理 | 抛异常 | 返回候选列表，由调用方选定后回传 canonical |

### 4.9 时间戳只能"推进"，不能"设置"

用户原始要求（已采纳）：**更新时间戳不能直接复写变量，只能通过 `advance(delta)` / `rewind(delta)`**。

理由：后续有模块要在时间戳更新上加东西（Core 的时间引擎、Social 的人口积分、Unit 的移动 materialize）。直接赋值会让这些钩子失去挂载点。

---

## 五、各模块职责与"不做"清单

### 5.1 MapSimos

**做**：六边形网格（axial 坐标、**单一方向常量表**、距离）、`TerraType`（`color`/`height`/`pass`/`name`）、`Region`（**闭环 hex 边界** + 元数据）、连通性（河流/道路，统一边系统 + 稳定 ID）、`GameMap`、地形生成（从头生成 + 有限编辑 + 自动河流）、查询/编辑 → `MapChangeSet`、寻址实现。

**不做**：
- **不做任何存储**（用户明确要求）。存储上收到 CoreSimos。
- 不知道 `SocialSimos` / `UnitSimos` 存在
- 不实现 MCP / HTTP / Agent 工具
- GSimap 导入是**独立 CLI 脚本**，不是公开 API

**必须修正的 GSimulator 缺陷**：L1（子节点字段丢失）、L2（双存储）、L3（方向错位）、L4（三个 region 概念统一）、L5（建反向索引）、L6（坐标表述统一）、L7（落盘 height 与 seed）、L8（用 `with*` / builder 消除 12 参数构造）、L9（单一地形词表）。

**地形生成的两个新模式**（用户需求）：
1. **框选随机化**：传入 `Region` 边界 + 两种地形类型 + 各自理想占比 → 按占比概率随机重填充
2. **自动河流**：根据地形海拔，**从最高地形到最低地形**自动生成一条河流（复用连通性系统；单条连通性线段可寻址，分支是独立的线）

### 5.2 SocialSimos

**做**：hex 人口属性（`population`）+ 人口增长率（`population_growth`），均为带时间戳的 `TemporalSeries`；时间推进时按分段积分更新人口。**明确标注为"不完善的测试模块"**——社会存在不可能这么简单，先搭架子。

**增长语义**（用户原始定义，已采纳）：
- 用 Manager 调整增长率本身 ⇒ **不重算**，只记录新时间点的新增长率
- 计算人口增加 ⇒ 先把增长率分段，从当前时间戳到目标时间戳各有多少段，**一段一段地**把增长人口加上

**不做**：不直接推进时间（只出 `Proposal`）；不管单位；人口之外的复杂社会参数留空。

### 5.3 UnitSimos

**做**：
- 单个小队（不可拆分单元）：`member`（人数 int）、`equipment.<装备名>`（装备名→数量）
- 编制树：严格**树**（一个 Unit 同一时间只能有一个直属上级）
- **位置继承**：`unit...1班` 无 `hex` 则向父取；自己有则覆盖。实现为 `effectivePosition(SubjectId, Timestamp)`，**`parent` 与 `position` 都是 `TemporalSeries`**（军队会改编），故 `effectivePosition` 是有效值计算而非静态查表
- 移动系统：多个路径点（最低两个）→ 最短路径 → 按时间戳推进 materialize
- 产出 `UnitChangeSet`

**量纲统一为 Movement Points**（架构审查 12，已采纳）：

```
speed = 2 MP / time
Δt = 20  →  movementBudget = 40 MP
[1,1]→[1,2] cost = 12.5 MP    40 - 12.5 = 27.5
[1,2]→[1,3] cost = 32.5 MP    27.5 - 32.5 = -5
结果：currentHex=[1,2], nextHex=[1,3], remainingEdgeCost=5 MP
```

**关键约束**：A* 寻路与实际移动**必须用同一个 `movementCost()` 函数**，否则会出现"最短路径算法说 A 最快、实际执行发现 B 更快"。

`TerraType.pass` 只是**输入之一**，不是最终成本：
```
movementCost = terrain.pass × unit.mobilityModifier × roadModifier × riverModifier
```
第一版只有 `terrain.pass` 也没关系，**接口别锁死**。

**地图变化后的路径策略**（架构审查 14，已采纳）：第一版用「路径保留；下一段无法通过时**暂停并标记 `NEED_REPLAN`**」——可控且好调试。另两种（`LOCK_ROUTE` / `REPLAN_EVERY_STEP`）只预留枚举。

**不做**：不知道社会模块；不管"支援关系"（编制树是严格的树，未来的关系走独立 `UnitRelation`：`SUPPORTS`/`ATTACHED_TO`/`COMMANDS`/`SUPPLIES`）。

### 5.4 CoreSimos

**做**：
1. **时间线系统**：可分岔、可推进、**不储存所有状态**。形态为 `Revision DAG + 周期 Checkpoint + ChangeSet + Temporal Process`
2. **时间推进两阶段**（见 §六）
3. **Command Bus**：唯一写入口
4. **存储**：日志→SQLite，快照→JSON
5. **可观测性**（见 §八）
6. **AgentBinding**：决策人绑定（见 §5.5）
7. **AgentLibMosire 集成**：把 simos 操作包装成 `AgentTool`，桥接 `ToolCallAuthorizer`
8. **GUI**：5711 主服务（`/map` `/social` `/unit` `/api`）
9. **MCP**：5715 独立服务

**不做**：不实现领域逻辑；GUI/MCP/Agent/玩家**全部走同一个 Command 入口**。

### 5.5 AgentBinding（决策人）

用户原始形式 `agent:map:Map1:"Nation"."区域A"` 保留为**查询便利形式**，但底层是独立记录（架构审查 19/20，已采纳）：

```java
record AgentBinding(BindingId id, AgentId agentId, SubjectId target,
                    DecisionScope scope, BindingMode mode, PermissionSet permissions)
enum BindingMode { SUGGEST_ONLY, AUTO_APPLY }
```

- **`targetSubject` 用 canonical address**，不是地址前缀
- **什么类型允许绑定决策人，由各模块自己声明**——Core **不写** `if (object instanceof Region && type.equals("Nation"))`。`MapSimos` 注册「`Region` 且 `type=Nation` 允许绑定」，`UnitSimos` 注册「全部 Unit 允许」，Core 只问 `canAttachAgent(subject)?`
- 决策人做完决策 → 交主 Agent 或玩家看 → **最终仍走 Command 路径**

---

## 六、时间推进：两阶段事务

**这是第二个成败点**——它决定了模拟语义。

```
AdvanceTime(from, to)
    ↓
① Prepare      各模块准备
    ↓
② Propose      各模块产出 TimeProposal（"我认为这段时间应该发生这些变化"）
    ↓          不是直接改 state
③ Resolve      解算跨模块影响
    ↓
④ Validate     校验
    ↓
⑤ Commit       汇总为 WorldChangeSet → 新 Revision
    ↓
⑥ Post-commit  索引刷新
```

```java
interface TimeParticipant {
    TimeProposal simulate(SimulationState state, TimeRange range);
}
```

`TimeProposal` 是 `ChangeSet` 的**候选形态**：模块自己算出、尚未跨模块解算、尚未校验、尚未提交。**它是提案，不是事实**——这正是两阶段的意义所在。

**为什么必须这样**：如果简单写成

```java
social.advanceTime(20);
unit.advanceTime(20);
```

那么**调用顺序立刻成为游戏规则**——"人口先增长还是战争先死人？""先生产粮食还是先消费粮食？""Unit 先移动还是边界先变化？"这不是技术细节，是模拟语义。

---

## 七、并发：乐观并发控制

Player / 主 Agent / 决策 Agent / MCP / GUI **都能改状态**，必然出现：

```
Agent A 读取 revision 100
玩家    修改，产生 revision 101
Agent A 根据旧世界提交修改    ← 怎么办？
```

**解法**（架构审查 23，已采纳）：

```java
// 每个具体命令都是实现 §4.5 的 Command 接口的 record，一律必须携带 expectedRevision
record MoveUnit(RevisionId expectedRevision, SubjectId unit, List<Hex> waypoints) implements Command
// 提交时：currentRevision == expectedRevision 才应用
// 否则 → CONFLICT { currentRevision: 101 }
// Agent 重新读取
```

**现在设计进去几乎零成本，以后补非常痛苦。**

---

## 八、可观测性（用户的"完善 log"要求）

```
Command ──┬─→ 结构化事件（SQLite，可查询）
          ├─→ 人类可读日志（便于直接 debug，不需查库）
          └─→ ChangeSet → Revision
```

### 8.1 每条命令固定记录

| 字段 | 用途 |
|---|---|
| `commandId` | 命令唯一标识 |
| `correlationId` | **贯穿 `Command → Proposal → ChangeSet → Revision`** |
| 发起者身份 | 玩家 / 主 Agent / 决策 Agent / MCP / 脚本 |
| `expectedRevision` | 与提交时的实际 revision 比对 |
| 目标地址 | Human 与 canonical 都记 |
| 参数摘要 | 复用 `AgentLibMosire` 的 `Digest`（sha256 前 16 字节）——**不记明文** |
| 结果 | 成功 / 拒绝原因 / CONFLICT |
| 耗时 | 性能排查 |
| 产出 ChangeSet 摘要 | 关联到 Revision |

### 8.2 事件类型

```
simos.command.received
simos.command.rejected
simos.command.conflicted
simos.command.committed
simos.time.advance.started / finished
simos.revision.created
simos.module.proposal        （各模块的 TimeProposal 摘要）
```

### 8.3 关键性质

`correlationId` 是**关键路径**：debug 时能一条命令从入口追到落盘，跨模块不丢链。

---

## 九、存储分层

| 内容 | 形式 | 理由 |
|---|---|---|
| **Revision / ChangeSet / 事件日志** | SQLite | 时间线 DAG 分岔、按 revision 查询、`correlationId` 追溯——关系型查询天然。形态参考 `AgentLibMosire` 的 `SqliteEventStore` |
| **完整快照（周期性 checkpoint）** | Jackson JSON | 人类可读、可与 GSimap 格式直接对照、便于导入导出 |
| **地图大对象** | 跟随快照 JSON | 实测最大 4.3 MB，JSON 可承受 |
| **Info 表** | 跟随快照 JSON | 与快照同生命周期，一起打 checkpoint |

**变更集的表示**（铁律 5 落地）：

1. `ChangeSet` 与 `Snapshot` **同源定义**——同一处声明字段，漂移在编译期即失败
2. **往返不变式测试**：`apply(changeSet, base)` 逐字段重建出 target
3. 该断言作为**测试不变量长期守卫**，不是一次性验收

---

## 十、构建形态

### 10.1 Maven 坐标

> 下列命名是本设计的**默认取值**。纯命名约定，如需调整请在 M0 之前提出——M0 骨架落地后再改要走全模块重命名。

| 项 | 值 |
|---|---|
| groupId | `io.mosire` |
| 父 POM | `io.mosire:simos-parent:0.1.0-SNAPSHOT` |
| 模块 | `simos-util` / `simos-map` / `simos-social` / `simos-unit` / `simos-core` |
| 包名 | `io.mosire.simos.util.*` / `.map.*` / `.social.*` / `.unit.*` / `.core.*` |
| Java | **21**（与两个参考项目一致） |
| 构建 | Maven（与参考项目一致；不用 Gradle） |

### 10.2 依赖

| 模块 | 依赖 |
|---|---|
| `simos-util` | Jackson databind、SLF4J API。**仅此** |
| `simos-map` | `simos-util`、Jackson |
| `simos-social` | `simos-util`、`simos-map` |
| `simos-unit` | `simos-util`、`simos-map` |
| `simos-core` | 以上全部 + `io.mosire:agentlib-mosire`、MCP SDK、SQLite JDBC、日志实现 |

### 10.3 AgentLibMosire 接入（**含硬阻塞项**）

**实测**：本地仓库 `~/.m2/repository/io/mosire/agentlib-mosire/0.1.0-SNAPSHOT/` 的 JAR 是 **2026-09-10 的旧构建，只有 49 个类**；当前源码有 **118 个类**。旧 JAR **完全缺失**：`approval/*` 全部、`permission/*` 绝大部分（`ResourceScope`/`ResourceScopeMap`/`ResourceManifest`/`ResourcePolicy`/`ResourceAuthorizer`/`ResourceId`/`Operation`/`CommandMode`/`ConfigAuth`/`ToolSpec`/`ResourceDeniedException`）、`tool/{Digest, ToolCallAuthorizer, ToolResultTruncator}`、`llm/{OpenAICompatibleLlmClient, ModelProvider, ...}`、`plugin/{HostServices, PluginListener, PluginToolSource}`、`store/*`、`config/*`、`retrieval/*`。

**处置**（已裁决）：

```bash
cd ~/ProjectMosire && ./mvnw -pl AgentLibMosire -am install
```

同时**升到固定版本号**（如 `0.2.0`），避免 `SNAPSHOT` 的可变语义。当前仓库无 `distributionManagement`、无发布流水线。

**注意**：不要继承 `mosire-parent`，否则会连带继承 Spotless / Checkstyle / SpotBugs / Surefire 全套门禁配置。版本号**显式写死**。

### 10.4 服务拓扑（已裁决）

| 端口 | 内容 |
|---|---|
| **5711** | 主服务：`/map` `/social` `/unit` `/api`（前端同一套构建、同一份会话、无 CORS） |
| **5715** | MCP（协议不同，且 `AgentLibMosire` 已有 `AgentToMcpServer` 可直接挂） |

保留能力：若将来确实需要模块独立启动，5712~5714 可作为 **dev 模式的 launcher**，但生产整合走 5711。

### 10.5 `AgentLibMosire` 可直接复用的能力

| 能力 | 用途 |
|---|---|
| `ToolRegistry` + `ToolSource`（PF4J） | 各 simos 模块作为工具来源注册 |
| `ToolCallAuthorizer` | **唯一调用入口**，五阶段顺序：工具存在 → 硬拒 → 资源前置闸 → 命令闸 → 执行 |
| `ResourceId`/`ResourceScope`/`ResourceScopeMap` | **可复用为游戏内地址级权限**——命名空间泛化（`fs` 只是一个内置常量），段边界前缀包含天然适配层级地址空间 |
| `ApprovalCoordinator` + `AskKind` | 审批流。`SENSITIVE` 永远交人类；`MODE_LIMITED` 可由上游 FULL 模式 agent 判定 |
| `SqliteEventStore` + `EventBus` | 事件持久化（§八） |
| `Digest` | 参数摘要（§8.1） |
| `OpenAICompatibleLlmClient` + `LlmRouteLoader` | LLM 客户端与多 provider 路由 |
| `AgentToMcpServer` | 5715 的 MCP 服务 |

**复用时的四项注意**（调查发现）：

1. `Operation` 只有 `READ`/`WRITE`。游戏内若要 `CONTROL`/`OBSERVE`/`OWN` 需扩展——**改动波及 `ResourceAuthorizer` 判定表与 `PermissionChecker.isSubset`（红线 1 实现点）**，需用例 + 变异自证
2. `ResourcePolicy` 是**默认值不是上限**——调用方显式声明会覆盖工具声明。若需要"某工具绝不允许写某命名空间"的硬保证，**现在没有**，需新增 cap 语义
3. `ResourceScope` 只做**词法段比较**，不理解领域语义。必须在命名空间划分上保证层级正确嵌套
4. `ResourceScope.ofDirs`/`ofDir`/`allowsDir`/`dirList` 是 **fs 专用**——做游戏地址时用 `ResourceScope.of(String...)` 那组

---

## 十一、里程碑

| 阶段 | 内容 | 前置 | 判据 |
|---|---|---|---|
| **M0** | 构建骨架：Maven 多模块、包名、门禁配置、**AgentLibMosire 重新 install + 升固定版本** | — | `mvn verify` 通过；`simos-core` 能 import 到 `ToolCallAuthorizer`/`ResourceAuthorizer`（证明 118 类可用） |
| **M1** | **UtilSimos 八大件** + Address 解析 + Info + TemporalSeries + Resolver SPI + **往返不变式测试框架** | M0 | 八大件各有单测；往返不变式框架有**故意漂移字段**的失败用例（证明护栏真的会响） |
| **M2** | **MapSimos**：网格/地形/区域/连通性/生成/diff → `MapChangeSet` | M1 | L1~L9 逐条有对应用例；框选随机化与自动河流各有验收 |
| **M3** | **SocialSimos**（人口薄架子）+ **UnitSimos**（编制树/装备/移动） | M2 | 人口分段积分与种子数对得上；单位移动按 §5.3 例子逐值验算 |
| **M4** | **CoreSimos 内核**：时间线 DAG、两阶段推进、Command Bus、存储、可观测性 | M3 | 时间线能分岔；`correlationId` 能贯穿全链查出来；CONFLICT 有真实并发用例 |
| **M5** | **CoreSimos 外壳**：AgentBinding、AgentLib 集成、5711 GUI、5715 MCP | M4 | Agent 与玩家改同一状态走同一 Command 路径；MCP 达到任何**合法**状态 |
| **M6** | **GSimap 导入器**（独立 CLI） | M2 | 旧 `*_map.json` 能转成 simos 数据集 |

### 11.1 两个成败点

- **M1**：八大件写歪，后面全歪
- **M4**：时间推进两阶段决定模拟语义

### 11.2 依赖关系

M6 只依赖 M2，可与 M3/M4 并行。其余严格串行。

---

## 十二、本轮不做 / 开口项

| 项 | 处置 |
|---|---|
| Social 的复杂社会参数（经济、政治、外交） | **登记在案**。Social 本轮只做人口，明确标注为"不完善的测试模块" |
| Unit 的支援/配属关系 | 本轮只做严格编制树。未来的 `UnitRelation` 只预留概念 |
| Unit 移动的 `LOCK_ROUTE` / `REPLAN_EVERY_STEP` | 只预留枚举，第一版用 `NEED_REPLAN` |
| `Operation` 枚举扩展（`CONTROL`/`OBSERVE`/`OWN`） | 待 M5 权限设计时定 |
| 工具级硬上限（cap 语义） | 待 M5 权限设计时定 |
| 审批 UI | `AgentLibMosire` **没有** HTTP 渠道实现，CoreSimos 需自写 `ApprovalChannel` |
| 流式 LLM 输出 | `LlmClient.chat` 是同步一请求一响应（契约级约束）。打字机效果需要改造 |
| Embedding / RAG | `AgentLibMosire` 只有 `NoopEmbeddingProvider`，无生产实现 |
| 发布流水线 | 当前无 `distributionManagement`，长期方案是发布固定版本到私服/local |

---

## 十三、各模块 spec 需自行决定的事项

总纲**不**替各模块决定以下内容，它们由各自的 spec 承担：

| 模块 | 待其 spec 决定 |
|---|---|
| **UtilSimos** | 八大件的完整方法签名；Address 转义与边界规则；Resolver 注册与优先级；TemporalSeries 的插值/事件语义；Facet 查询的具体协议 |
| **MapSimos** | 六边形数据结构的最终形态；`Region` 如何统一三个旧概念；连通性稳定 ID 的生成规则；生成算法的参数面；`MapChangeSet` 的字段清单 |
| **SocialSimos** | 增长率分段的边界语义（时间戳落在段边界时算哪段）；人口的 cache 策略 |
| **UnitSimos** | 编制树的修改操作面；移动 materialize 的精度与舍入；A* 的启发函数 |
| **CoreSimos** | 时间线 DAG 的存储 schema；Checkpoint 周期；Command 类型清单；GUI 具体形态；MCP 工具清单 |

---

## 附录 A：GSimulator 关键文件索引

| 关注点 | 路径 |
|---|---|
| 地址解析 SPI | `gsim-agentsmanager/src/main/java/com/gsim/agentsmanager/ref/` |
| 地图数据模型 | `gsim-map/src/main/java/com/gsim/map/map/MapData.java` |
| diff 与重放 | `.../map/MapDiff.java`、`.../map/MapResolver.java`（**L1 缺陷所在**） |
| 持久化 | `.../map/MapStore.java`、`gsim-core/.../worldinfo/loader/NodeLoader.java` |
| 地形生成 | `.../service/MapGenerator.java`、`ContourQueryEngine.java`、`SimplexNoise.java` |
| 连通性 | `.../map/MapData.java`（`edges` 部分）、`.../service/MapService.java:759-829`（`traceChains`） |
| 地图 HTTP | `.../http/GsimapHttpServer.java`、`MapWebUIHandler.java` |
| Agent 循环 | `gsim-agentsmanager/.../core/AbstractAgent.java:218-428` |
| **提权面（L11）** | `.../core/AbstractAgent.java` 的 `beforeToolExecute()` |
| 地图前端 | `gsim-map/src/main/resources/web/`（`js/` 下 11 个模块 + `map-api.js`，共 12 个原生 JS 文件） |

## 附录 B：AgentLibMosire 关键 API 索引

| 用途 | 类型 |
|---|---|
| 定义工具 | `io.mosire.agentlib.tool.AgentTool` |
| 注册工具 | `io.mosire.agentlib.tool.ToolRegistry` |
| **唯一调用入口** | `io.mosire.agentlib.tool.ToolCallAuthorizer` |
| 插件式工具来源 | `io.mosire.agentlib.plugin.ToolSource`（PF4J） |
| 地址级权限 | `io.mosire.agentlib.permission.{ResourceId, ResourceScope, ResourceScopeMap, ResourceAuthorizer}` |
| 审批 | `io.mosire.agentlib.approval.{ApprovalCoordinator, ToolGate, AskKind, SuperiorJudgeGate}` |
| 事件持久化 | `io.mosire.agentlib.event.SqliteEventStore` |
| 参数摘要 | `io.mosire.agentlib.tool.Digest` |
| LLM | `io.mosire.agentlib.llm.{LlmClient, OpenAICompatibleLlmClient, LlmRouteLoader}` |
| MCP 服务 | `io.mosire.agentlib.mcp.AgentToMcpServer` |
