# SocialSimos + UnitSimos 模块设计（M3 spec）

> **权威层级**：总纲 `2026-09-16-simos-master-design.md` > 本 spec > 实现计划。
> 本 spec 里"**冻结**"= 实现、夹具与测试都必须照此；计划的代码草图若与本 spec 冲突，**以本 spec 为准**（M1 以来的既定口径）。
> 本 spec 是 M3 的**唯一**设计文件（用户裁定 U5：一份 spec 覆盖两模块；计划同理一份）。

## 〇 裁决记录

### 〇.1 用户裁定（2026-09-17，六项）

| # | 待决项 | 裁定 |
|---|---|---|
| U1 | 人口积分口径（总纲 §十三） | **线性累加 + 事件即时交织**：段内 `Δp = round(p × rate × Δt)`（单利）、跨段复利；事件在其时刻立刻改变基数，其后的增长按新基数算。种子例 `populationAt(53) = 18036`（§3.6 冻结表） |
| U2 | 单位编制链的地址形态（M1 §10-D7） | **稳定 ID 规范形**：canonical = `unit:<unitId>`（段 2 是根主体，形如 `unit:u-f82a`，与 `map:Map1` 同构）；链式 `unit:"高地人旅指挥部.1营指挥部.1连指挥部"`（M1 已冻结的引号形态）作**定位/人读形式**，两路 Resolver 都收、**canonical 一律回 ID** |
| U3 | 时间基 | **全局刻度 = 小时**：1 tick = 1 小时（`SimosTimestamp` 仍是无量纲整数，这是**读法**不是新字段）；`speed` 单位 = MP/小时；增长率单位 = 每小时的比率；materialize 的最小步长 = 1 tick。种子例的数字保留作**算术验算**，不代表平衡值 |
| U4 | 移动 MP 的精度与舍入（总纲 §十三） | **定点毫 MP**：1 MP = 1000 毫，`long` 存；每步乘法按 `floor(x + 0.5)` 舍入（即 `Math.round` 的语义，正数即四舍五入） |
| U5 | 编制树操作面（总纲 §十三） | **全套 8 项**：创建 / 改编 / 改名 / 人数·装备变更 / 位置设置 / 下达路线 / 取消路线 / 解散（§4.6） |
| U6 | spec 与计划的粒度 | 一份 spec + 一份计划覆盖两模块（控制器核定，用户未反对） |

### 〇.2 控制器裁定（可在评审时推翻）

| # | 项 | 裁定与理由 |
|---|---|---|
| C1 | 人口 cache 策略（总纲 §十三） | **不进状态、不缓存**。每次 `valueAt` 从 anchor 现算，复杂度 O(段数 + 事件数)。理由：M1 的"不储存所有状态"+ M2 的派生件纪律（`RegionIndex` 不进组件）；无实测性能需求（YAGNI）。将来若需要，按 `RegionIndex` 形态做**派生件**，绝不进变更集/存档 |
| C2 | A\* 启发函数（总纲 §十三） | `h(n) = 单位最低单步成本下界 × distanceTo(n, goal)`，下界由**同一个 `MovementCost`** 给出（§4.4）；平局按 `(f, h, q, r)` 全序定序保证决定论；对拍守卫：与 Dijkstra 的结果成本相等 |
| C3 | member / equipment 是否时态 | **否**，普通值（变更走 ChangeSet，历史由 M4 的 revision 日志承载）。总纲只点名 `parent` 与 `position` 是 `TemporalSeries`（军队会改编），本 spec 不扩大 |
| C4 | 同刻语义 | 完整继承 M1 四条（§五）；人口侧的具体落点 = "先切段、再施事件、事件按插入序" |
| C5 | 不可通行判据的来源 | 单一来源 = 新增 `TerrainType.IMPASSABLE_MOVE_COST = 999`（simos-map 内，与 `TerrainCatalog` 的海洋取值同源），并配一条"词表海洋 == 该常量"的一致性守卫。**不在 unit 模块复制第二份哨兵** |
| C6 | 移动成本公式与顺序 | `cost = 地形(to).moveCost × 1000`（MP→毫）→ 按单位 `mobilityPerMille` 缩放 → 道路修正 → 河流修正（后两项 v1 恒 1000，即恒等）。顺序冻结，因为定点舍入对顺序敏感 |
| C7 | `FieldDelta` 的位置 | **从 `simos-map` 上移到 `simos-util`**（`io.mosire.simos.util.state.FieldDelta`）：它是通用机制件，放在 map 里会让 social/unit 的变更集依赖 map 的"变更机制"（语义错位）。附一条"全仓恰一份 FieldDelta"的扫描守卫 |
| C8 | 变更集是否实现 util 的 `ChangeSet`（带 `baseRevision`） | **不实现**——照 M2 的 `MapChangeSet` 先例：变更集是纯 delta，版本戳属 Revision 层。M4 决定是否统一 |

### 〇.3 偏离清单（相对总纲 / M1 spec，逐条给理由）

1. **`TemporalSeries.segments()` 对人口序列返回单段 anchor**。M1 §七 承诺"人口那种积分型序列由 SocialSimos 自己实现 `TemporalSeries<T>`"——本 spec 履行该承诺，但把 `segments()` 的语义收窄为"本序列唯一的分段常量声明 = anchor"：人口在段内**连续变化**，把增长率段起点采样冒充"分段常量"是说谎（L9 的形态）。增长率的分段货真价实地活在 `growth()` 里（它自己就是一个 `SegmentedSeries<Double>`）。
2. **`TerrainType` 新增 `IMPASSABLE_MOVE_COST`**（M2 已关账模块的小增补）：M2 的"不可通行 = 999 哨兵"此前只活在注释与词表取值里，本 spec 把它提升为具名常量，代价是触碰 M2 的一个文件（无行为变化）。
3. **属性段地址不服务**：`social:…:population`、`unit:…:member` 在 M3 的 Resolver 里**空候选**——与 M2 `MapResolver` 对 `map:…:height` 的口径一致（"属性访问属查询层"）。属性读取与 Info 挂载见挂起项 §8.2。
4. **不做 `PathStrategy` 预留枚举**：总纲 §5.3 说 `LOCK_ROUTE` / `REPLAN_EVERY_STEP` "只预留枚举"。本 spec 判：没有消费者的枚举是死代码（M2 的 `contourCacheMax` 教训），NEED_REPLAN 的语义落在 `MovementStatus.NEED_REPLAN` 上即可；将来在 M4 裁决路线策略时再加枚举。
5. **移动的"写回状态"不在 M3**：总纲 §5.3 的"按时间戳推进 materialize"由 `UnitMoves.evaluate`（纯函数）承载；把结果写回 `position` 序列需要"上次物化到哪 + 两阶段推进"的语义，属 M4 的时间推进（§8.3）。
6. **单位 canonical = 稳定 ID**（用户裁定 U2）：与总纲 §4.4 的 `unit:U` 示例不冲突——示例里的 `U` 就是"ID 的示意写法"；本 spec 把它的形态钉成 `unit:u-f82a` 这类裸值。

### 〇.4 不做清单（照总纲，明确划界）

- 不做 Facet 注册（`unitsHere` / `population` 面没有装配点与消费者；M4 的 Core 才有注册时机）。
- 不做 Split / Merge / 支援关系 / 配属关系（总纲：严格树，未来走独立 `UnitRelation`）。
- 不做时间推进（两阶段推进、Proposal、Revision DAG 全是 M4）。
- 不做存储 / JSON 持久化 schema（M4）。
- 不做 GUI / MCP / Agent 绑定（M5）。
- Social 不做人口之外的任何社会参数（总纲：明确标注"不完善的测试模块"）。

---

## 一 判据与交付物

### 1.1 判据（总纲 §11 / 主计划 §二 M3 行）

| # | 判据 | 落点 |
|---|---|---|
| ① | 人口分段积分与**手算种子数**对得上 | §3.6 的冻结表，逐值断言（`18036` 等） |
| ② | 单位移动按总纲 §5.3 例子**逐值**验算（`40-12.5=27.5`；`27.5-32.5=-5` ⇒ `currentHex=[1,2]`、`nextHex=[1,3]`、`remaining=5 MP`） | §4.5 的冻结夹具与表 |
| ③ | `./mvnw clean verify` 绿（Spotless + Checkstyle + SpotBugs + enforcer + Surefire 全链） | 关账时当场跑，日志留痕 |
| ④ | 每条新护栏有**故意违规用例**自证（G13：变异体必须字节不同、`COMPILATION ERROR` 计数为 0、红点落在被保护的那行） | §六 |

### 1.2 交付物

| 模块 | 交付 |
|---|---|
| simos-util | `FieldDelta` 上移（C7）；无新类型 |
| simos-map | `TerrainType.IMPASSABLE_MOVE_COST`（C5）；`MapChangeSet` 改 import（机械） |
| simos-social | `SocialData`、`SocialSnapshot`、`PopulationSeries`、`SocialChangeSet`、`SocialResolver`、操作（追加增长率段 / 追加人口事件） |
| simos-unit | `UnitId`、`Unit`、`UnitState`、`UnitSnapshot`、`Route`、`Movement`、`MovementState`/`MovementStatus`、`MovementCost` + `TerrainMovementCost`、`PathFinder`（A\*）、`UnitMoves`（evaluate）、`UnitOperations`（8 项）、`UnitChangeSet`、`UnitResolver` |

---

## 二 边界与依赖

```
UtilSimos  →  MapSimos  →  { SocialSimos, UnitSimos }  →  CoreSimos
```

- `simos-social` / `simos-unit` 的 enforcer 已就位（互不依赖、不依赖 core/agentlib）；M3 **不加新依赖**。
- Social **不校验格是否存在于地图**（`social:` 的 hex 只查自己的数据表；装配级一致性归 Core）；Unit 的移动**要用地图**（地形成本），走 `GameMap` 参数显式传入，不依赖任何地图单例。
- 跨模块可见性（"某 hex 上有哪些单位"）本轮**不落地**（§〇.4），留 M4/M5 的 Facet 装配。

---

## 三 SocialSimos

### 3.1 状态形状

```java
// io.mosire.simos.social.population.PopulationSeries —— §3.2
public record SocialData(Map<HexCoord, PopulationSeries> populations) { … }
public record SocialSnapshot(StateRef ref, SimosTimestamp timestamp, SocialData data)
    implements Snapshot { namespace() == "social" }
```

冻结要点：

1. `populations` **保序不可变**——`Collections.unmodifiableMap(new LinkedHashMap<>(…))`，**绝不 `Map.copyOf`**（M2 实测：迭代序不是内容的纯函数，字节级往返因此不成立）；逐键值查 null。
2. `SocialData.empty()` 是往返用例的起点（空表 + 规范的初始时间戳由调用方给）。
3. `SocialSnapshot` 形制照 `MapSnapshot`（三组件、构造期 null 抛、`namespace()` 固定 `"social"`）。
4. SocialData 只有一个组件，`with*` 只有一个：`withPopulations(Map)`（照 M2 的"一个组件一个 with"）。

### 3.2 PopulationSeries：积分语义（冻结）

```java
public record PopulationSeries(
    Segment<Long> anchor,              // 人口起算点：t < anchor.from() ⇒ 恒为 anchor.value()
    SegmentedSeries<Double> growth,    // 分段恒定的增长率（每 tick 的比率，如 0.02）；非 null
    List<Event<Long>> events)          // 人口上的离散跳变（ADD / SET），按插入序
    implements TemporalSeries<Long> {

  public static final BinaryOperator<Long> ADDITION = Long::sum; // 模块级 static final（M1 纪律）
  …
}
```

构造期校验（全部 `IllegalArgumentException`，宁抛不静默）：

1. `anchor` / `growth` 非 null（`Segment` 自身也查）；`events` 冻结（逐元素查 null）。
2. **`growth.segments().get(0).from()` 不得晚于 `anchor.from()`**——否则 "anchor 到首段之间无速率可依"，只能靠向前延拓瞎猜，那会把"我记得是 t=5 才给的 2%"静默变成"从 t=0 就 2%"。
3. **`growth.events()` 必须为空**（IAE）：速率的变更一律用段表达（与 §4.1 对 `parent` / `position` 的同一条规则同源）。这不是洁癖——`valueAt` 的切分点集合只收集**段边界与人口事件**，段内若还能冒出一个速率事件，"按区间左端点取速率"就会静默算出一段错积（而结果看起来完全正常）。
4. 人口侧的 `ADD` 事件由 `ADDITION` 施加（`static final`，见下）；M1 纪律：凡含 `ADD` 的序列都必须引用**模块级 `static final`** 的加法，各写各的 lambda 会让往返以"序列不相等"假红（record 按身份比较函数）。

`valueAt(SimosTimestamp t)` 算法（**逐字冻结**，实现与手算表都照此）：

1. `t < anchor.from()` ⇒ 返回 `anchor.value()`（**不增长、不施加事件**：anchor 之前是恒定延拓，seed 见 M1 语义 2）。
2. 取切分点集合 = `{anchor.from()} ∪ {g.from : g 是 growth 的段且 anchor.from() < g.from ≤ t} ∪ {e.at : anchor.from() ≤ e.at ≤ t}`，去重、升序 ⇒ 区间序列 `[u₀=anchor.from, u₁), [u₁, u₂), …, [u_{k-1}, t]`。
3. 逐区间：`r = growth.valueAt(uᵢ)`（`uᵢ` 处**新段已生效**——左闭右开，M1 语义 1）；若 `r ≠ 0` 且区间宽度 `w = u_{i+1}.tick − uᵢ.tick > 0`，则
   `p += Math.round(p * r * w)`（**单利增量、每区间一次舍入**，`Math.round` = `floor(x+0.5)`）。
   ⚠️ 舍入落在**切分后的每个区间**上（含查询点所在的半截区间），不是落在整个增长率段上——这正是"一段一段地把增长人口加上"（用户原始定义）的机械化，且保证手算 = 逐步整数运算。
4. 走到某个 `uᵢ` 时，**先切段（第 3 步已用新速率）再施加该时刻的事件**（M1 语义 3）；同刻多事件**按 `events` 列表的插入序**依次施加（M1 语义 4）：`ADD` ⇒ `p += e.value()`；`SET` ⇒ `p = e.value()`。
5. 返回 `p`（long，**不夹取**：负人口是数据的真相，是否合理属命令层校验）。

`segments()` / `events()`（**冻结，见偏离 1**）：

- `events()`：返回冻结的 `events` 列表。
- `segments()`：返回 `List.of(anchor)`——**本序列唯一的分段常量声明就是 anchor**；人口自 anchor 起连续变化，增长率的分段在 `growth().segments()` 里。
- 另提供 `growth()` / `anchor()`（record 访问器）与便捷方法。

### 3.3 增长率与事件的编辑

- `withGrowthSegment(SimosTimestamp at, double rate)`：把 `(at, rate)` 追加为 growth 的新段。**"调整增长率不重算人口"**（用户原始定义）在此落地：不物化、不动 anchor、不动 events。同刻重复 ⇒ 由 `SegmentedSeries` 的严格升序校验抛 IAE（**不实现"同刻替换"**：同刻两条速率是矛盾配置，抛比猜干净）。
- `withEvent(Event<Long> e)`：把事件**追加到列表末尾**——事件列表按插入序，同刻多事件的次序即插入序（M1 语义 4）。**不重排**：`at` 早于现末尾 ⇒ 由 `SegmentedSeries` 的"事件非递减"校验抛（`requireNonDecreasing`）；同刻可以继续追加（非递减允许相等），这正是"同刻多事件"的表达方式。
- 两者都返回新的 `PopulationSeries`（record 值语义），旧值不变。

### 3.4 变更集

```java
public record SocialChangeSet(FieldDelta<PopulationSeries> populations) {
  public static SocialChangeSet between(SocialData base, SocialData target) { … }
  public static SocialData apply(SocialChangeSet cs, SocialData base) { … }
  public boolean isEmpty() { … }
}
```

- **逐字照 `MapChangeSet` 的形制**：`between` 顺 `target` 迭代序 diff、又增又删 ⇒ `Patch`（两侧保留，丢删除正是 GSimulator 的病根）；`apply` 逐组件 rebuild，`Patch` **递归复用** `Remove`/`Upsert` 两路；`Unchanged` ⇒ base 原样（连键序）。
- key 的规范串 = `HexCoord.toString()`（`"q_r"`），apply 侧用 `HexCoord.parse` 还原（三件套已备齐）。
- **不实现 util 的 `ChangeSet` 接口**（C8）。

### 3.5 地址与 `SocialResolver`

命名空间 `social`。照 `MapResolver` 的形制（空候选 vs 抛的分工、canonical 只由 AST 构造后 `canonical()` 产出）：

| 地址 | 结果 |
|---|---|
| `social:<mapId>` | 该地图的社会切片根主体 ⇒ `SubjectId("social", mapId)`，typeName `"Social"` |
| `social:<mapId>:hex.<q>_<r>` | 有该 hex 的人口记录 ⇒ `SubjectId("social.hex", "q_r")`，typeName `"HexPopulation"`；**无记录 ⇒ 空候选** |
| `social:<mapId>:[q,r]` | 上一条的 Human 形式（`Index` 段恰 2 元）；canonical **一律输出** `hex.<q>_<r>` |
| 其余（段数 > 3、属性段、其它 kind） | **空候选**（属性访问见 §〇.3 偏离 3） |

- **`mapId` 只回显、不校验**（同 M2 的挂起项：`GameMap` 无 id）；Social 不引用地图切片。
- 装配故障（state 里没有 `social` 切片 / 切片类型不对）**抛** `IllegalArgumentException`——照 `MapResolver` 的同款分界。
- 非 `social` 命名空间返回空候选（"认领"由返回值表达）。

### 3.6 判据一的种子（冻结手算表）

**种子例（总纲 §4.7 + 用户裁定 U1）**：`anchor = (t=0, 10000)`；`growth` 段 `(0, 0.02) → (20, 0.01) → (70, -0.03)`；`events = [(t=45, −800, ADD)]`。查询 `valueAt(53)`：

| 区间 | 基数 | 速率 | 增量（舍入后） | 段末人口 |
|---|---|---|---|---|
| `[0, 20)` | 10000 | 2% | `round(10000×0.02×20) = +4000` | 14000 |
| `[20, 45)` | 14000 | 1% | `round(14000×0.01×25) = +3500` | 17500 |
| `t=45` 事件 | — | — | `ADD −800` | 16700 |
| `[45, 53)` | 16700 | 1% | `round(16700×0.01×8) = +1336` | **18036** |

另冻结三条**边界语义**用例（它们才是"段边界算哪段"的判据本体）：

| 用例 | 输入 | 期望 |
|---|---|---|
| 同刻：段边界 + 事件 | `anchor=(0, 1000)`；`growth = (0, 0.1) → (10, 0.2)`；`events = [(10, +100, ADD)]`；查 `valueAt(20)` | `[0,10)` ⇒ 1000+1000 = 2000；t=10 **先切段**（速率变 0.2）**再事件** +100 ⇒ 2100；`[10,20)` ⇒ `2100+round(2100×0.2×10)=2100+4200` ⇒ **6300** |
| 同刻多事件按插入序 | 在上条基础上 `events = [(10, +100, ADD), (10, 5000, SET)]`，查 `valueAt(20)` | t=10：2000 → +100 = 2100 → SET 5000 ⇒ 5000；`[10,20)` ⇒ `5000+round(5000×0.2×10)=5000+10000` ⇒ **15000** |
| anchor 之前 | 种子例查 `valueAt(-5)` | **10000**（恒定延拓、不增长、不施事件） |

---

## 四 UnitSimos

### 4.1 状态形状

```java
public record UnitId(String value) { … }   // 形制照 RegionId/CityId：裸值 toString + static parse + 空白即抛

public record Unit(
    UnitId id,
    String name,
    SegmentedSeries<Optional<UnitId>> parent,    // 严格树：至多一个直属上级
    SegmentedSeries<Optional<HexCoord>> position, // 无则向父取（effectivePosition）
    int member,                                   // 人数（非时态，C3）
    Map<String, Integer> equipment,               // 装备名 → 数量（非时态）
    int speed,                                    // MP / 小时（U3）；≥ 1
    int mobilityPerMille,                         // 机动修正，千分比；1000 = 标准；≥ 1
    Optional<Movement> movement) { … }            // 在途路线（§4.5）

public record UnitState(Map<UnitId, Unit> units) { … }
public record UnitSnapshot(StateRef ref, SimosTimestamp timestamp, UnitState state)
    implements Snapshot { namespace() == "unit" }
```

冻结要点：

1. `units` **保序不可变**（同 §3.1 的 `populations`，一律 `LinkedHashMap` + `unmodifiableMap`）。
2. `id` 非 null；`name` 空白即抛；`member ≥ 0`；`equipment` 保序不可变、键非空白、值 ≥ 0；`speed ≥ 1`（0 速度＝走不动，语义不明）；`mobilityPerMille ≥ 1`。
3. `parent` / `position` 的 **`events()` 必须为空**（抛 IAE）：这两条序列的变化一律用"追加段"表达；`ADD` 对 `Optional` 无定义，`SET` 与段重复。留着事件口子只会引入第二个写法。
4. `parent` 的任一段值/延拓值**不得等于自身 id**（构造期查，便宜）；**跨单位的环**由 `UnitState` 构造期查（见 4.2）。
5. `UnitId` 的**分配器不在 M3**（§8.6）：id 由命令层给，本模块只校验非空白；`u-f82a` 是示例形态，不是格式约束。

### 4.2 编制树不变量与位置继承

- **严格树**：任一时刻（a）每个单位至多一个直属上级——由"`parent` 是单值序列"天然成立；（b）父图**无环**——需校验。
- **`UnitState` 构造期校验"关键时点无环"**：关键时点 = 所有单位的 `parent` 段 `from` 的去重升序集合（父图只在段边界变化，故查遍关键时点即覆盖全时间轴，含 anchor 之前的恒定延拓）。每个时点建父图、查环；有环 ⇒ `IllegalArgumentException`（带出环上第一个 id）。代价 O(时点数 × 单位数)，v1 规模下可忽略。
  - 为什么不是"合并所有边查环"：把 `A→B`（t1）与 `B→A`（t2）合并会误报——两者各自合法，改编是允许的。**不得**用合并图。
- `effectivePosition(UnitId, SimosTimestamp)` → `Optional<HexCoord>`：
  1. `unit.position().valueAt(t)` 有值 ⇒ 返回它；
  2. 否则取 `unit.parent().valueAt(t)`，有父 ⇒ 递归；
  3. 无父 ⇒ `Optional.empty()`。
  递归带"已访问 id"集合，**撞环抛 `IllegalStateException`**（数据故障；正常路径下 `UnitState` 已拒环）。
- 无位置不是错误：整条链都没给位置 ⇒ empty（定位问的是"现在在哪"，答案是"不知道"）。

### 4.3 毫 MP：移动成本的定点表示（冻结）

```java
public interface MovementCost {
  /** 从 from 踏入 to 的毫 MP 成本；不可通行（或 to 不在图上）⇒ 空。 */
  OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map);

  /** 本实现给出的**单步成本下界**（毫 MP）；A* 的启发函数用它（§4.4）。无法给出下界时返回 0（退化为 Dijkstra，仍正确）。 */
  long minStepCostMillis(Unit unit, GameMap map);
}
```

v1 实现 `TerrainMovementCost`（无状态，`MovementCost` 接口本身就是"道路/河流修正"的扩展点）：

1. `to` 不在 `map.hexes()` ⇒ 空。
2. 相邻性是**调用方的前提**：`from.equals(to)` 或 `from.distanceTo(to) != 1` ⇒ 抛 IAE（这是调用方的 bug，不是"没有候选"）。
3. `TerrainType type = map.terrainTypes().get(hex.terrain())`；**缺 key ⇒ 抛 IAE**（与 `TerrainCatalog.of` 的"不兜底"同口径）。
4. `type.moveCost() >= TerrainType.IMPASSABLE_MOVE_COST` ⇒ 空。
5. 否则：`cost = scale(type.moveCost() × 1000, unit.mobilityPerMille())`，其中 `scale(v, ‰) = Math.floorDiv(v × ‰ + 500, 1000)`（`floor(x+0.5)`）；道路/河流修正位（v1 恒 1000，即恒等）排在最后。
6. `minStepCostMillis`：扫描 `map.hexes()` 取**可通行**地形的最小 `moveCost`，按第 5 步同样缩放；无任何可通行格 ⇒ 返回 **0**（0 仍是合法下界）。

判据二夹具所需的两条地形不在 `TerrainCatalog` 里：**夹具自建 `TerrainType`**（§4.5），因为 `GameMap.terrainTypes` 本就是任意词表，判据因此与地形词表解耦。

### 4.4 路径规划：A\*（冻结）

```java
public final class PathFinder {
  public static Optional<List<HexCoord>> findPath(
      GameMap map, HexCoord start, HexCoord goal, Unit unit, MovementCost cost);
}
```

- 前置：`start` / `goal` 不在 `map.hexes()` ⇒ 空（合法但不存在，同 `MapResolver` 口径）；`start.equals(goal)` ⇒ `List.of(start)`。
- 邻接：`HexDirection.ALL` 六方向；目标格必须存在于 `map.hexes()`；`cost.costMillis(...)` 为空的边**不可走**。
- `g` = 累计毫 MP；`h(n) = cost.minStepCostMillis(unit, map) × n.distanceTo(goal)`（启发与成本**出自同一实现**——总纲的关键约束在此落地）。
- **可采纳且一致**：任一单步成本 ≥ 下界 ⇒ `h(n) − h(n') ≤ 下界 ≤ c(n,n')`，故首次弹出即最优，`closed` 集合可直接用（不需要 reopen）。
- **平局定序**：优先队列比较器 `(f, h, q, r)` 全部升序 ⇒ 给定输入输出唯一路径（决定论；邻居按 `HexDirection.ALL` 的枚举序展开）。
- 不可达 ⇒ 空。
- **对拍守卫**：一条用例以 `h = 0`（Dijkstra）跑同一图，断言两者的**结果成本**相等（以及本实现路径的逐段成本可复算）。这是"启发函数不得破坏最优性"的护栏（G13 变异自证）。

### 4.5 路线与 Movement 评估（冻结，判据二的落点）

```java
public record Route(List<HexCoord> waypoints, List<HexCoord> path) { … }   // §4.6 的 planRoute 产物
public record Movement(Route route, SimosTimestamp departedAt,
                       int speedAtDeparture, int mobilityAtDeparture) { … }

public enum MovementStatus { IN_TRANSIT, ARRIVED, NEED_REPLAN }

public record MovementState(
    HexCoord currentHex,                        // 最后抵达的格（NEED_REPLAN 时 = 卡住前所在格）
    Optional<HexCoord> nextHex,                 // 正在走的那一段的目标；ARRIVED / NEED_REPLAN ⇒ 空
    OptionalLong remainingEdgeCostMillis,       // 当前段尚未支付的毫 MP（严格 > 0）；ARRIVED / NEED_REPLAN ⇒ 空
    MovementStatus status) { … }

public final class UnitMoves {
  public static MovementState evaluate(Unit unit, SimosTimestamp at, GameMap map, MovementCost cost);
}
```

- `Route` 校验：`waypoints.size() ≥ 2`（总纲："多个路径点，最低两个"）；`path.size() ≥ 2`；`path` 首 = `waypoints` 首、尾 = `waypoints` 尾；`waypoints` 按序是 `path` 的子序列；`path` 相邻元素 `distanceTo == 1`；`path` 无重复格（A\* 产物天然是简单路径）。
- `Movement` 构造期校验：`route` / `departedAt` 非 null，`speedAtDeparture ≥ 1`，`mobilityAtDeparture ≥ 1`（与 `Unit` 的同名约束一致）。
- `Movement` 的 **`speedAtDeparture` / `mobilityAtDeparture` 在出发时冻结**：在途行程不得因参数变更而"时间反演"（速度翻倍让昨天已走的路程突然变长）。地图变化仍实时生效——那正是 `NEED_REPLAN` 的来源。
- `evaluate` 前置：`unit.movement()` 为空或 `at` 早于 `departedAt` ⇒ 抛 IAE（调用方 bug）。无状态、纯函数。
- 算法：`budget = speedAtDeparture × 1000 × (at.tick − departedAt.tick)`（毫 MP）；沿 `path` 逐段：
  1. `c = cost.costMillis(path[i], path[i+1], frozen, map)`，其中 `frozen` 是**机动性冻结视图**——`MovementCost` 从 `Unit` 上读 `mobilityPerMille()`，而在途行程必须用 `mobilityAtDeparture`，故 `UnitMoves` 副本一份单位再调成本函数（**冻结口在此，不在 `MovementCost` 的签名里**）：

     ```java
     // u = 入参 unit；m = u.movement().orElseThrow()
     Unit frozen = new Unit(u.id(), u.name(), u.parent(), u.position(), u.member(), u.equipment(),
                            u.speed(), m.mobilityAtDeparture(), u.movement());
     ```

     **空 ⇒ 返回 `(path[i], 空, 空, NEED_REPLAN)`**——路径保留、暂停（总纲 §5.3 第一版策略）。
  2. `budget ≥ c` ⇒ `budget -= c`，续下一段；
  3. 否则 ⇒ 返回 `(path[i], path[i+1], c − budget, IN_TRANSIT)`（严格 > 0）。
- 走完末段 ⇒ `(path[last], 空, 空, ARRIVED)`。
- **判据二（冻结夹具与逐值表）**：`path = [[1,1],[1,2],[1,3]]`（`waypoints = [[1,1],[1,3]]`）；单位 `speed = 2` MP/小时、`mobilityPerMille = 500`；夹具地形 `moveCost = 25`（格 `[1,2]`）与 `65`（格 `[1,3]`）⇒ 成本 `12500` / `32500` 毫；`departedAt = t0`，查 `at = t0 + 20`：

  | 步骤 | 算式（毫 MP） | 结果 |
  |---|---|---|
  | 预算 | `2 × 1000 × 20` | `40000`（= 40 MP） |
  | 第 1 段 | `40000 − 12500` | `27500`（= 27.5 MP，对上总纲的 `40−12.5`） |
  | 第 2 段 | `27500 − 32500 = −5000` | 付不起 ⇒ `currentHex=[1,2]`、`nextHex=[1,3]`、`remaining=5000 毫（= 5 MP）`、`IN_TRANSIT` |

  另两条冻结（同一夹具的另外两个时刻 / 另一份地形）：

  | 用例 | 输入 | 期望 |
  |---|---|---|
  | 差一点走不完 | `at = t0 + 22`（预算 44000，比 45000 少 1000） | `IN_TRANSIT`，`currentHex=[1,2]`、`nextHex=[1,3]`、`remaining = 1000` |
  | 走完 | `at = t0 + 23`（预算 46000 ≥ 45000） | `ARRIVED`，`currentHex=[1,3]`、`nextHex` / `remaining` 空（余量 1000 **不体现**——`ARRIVED` 的定义就是"段都付清了"） |
  | 中途变不可通行 | 把 `[1,3]` 的地形换成 `moveCost = 999`，`at = t0 + 20` | `NEED_REPLAN`，`currentHex=[1,2]`、`nextHex` / `remaining` 空 |

### 4.6 操作面（8 项，U5）

```java
public final class UnitOperations {
  public static UnitState create(UnitState state, Unit unit);
  public static UnitState reparent(UnitState state, UnitId id, Optional<UnitId> newParent, SimosTimestamp at);
  public static UnitState rename(UnitState state, UnitId id, String name);
  public static UnitState setStrength(UnitState state, UnitId id, int member, Map<String, Integer> equipment);
  public static UnitState placeAt(UnitState state, UnitId id, Optional<HexCoord> hex, SimosTimestamp at);
  public static UnitState planRoute(UnitState state, UnitId id, Route route, SimosTimestamp at);
  public static UnitState cancelRoute(UnitState state, UnitId id);
  public static UnitState disband(UnitState state, UnitId id, SimosTimestamp at);
}
```

冻结要点：

1. **操作产出的都是新 `UnitState`**（纯函数）；变更集**唯一**的生产路径是 `UnitChangeSet.between(base, target)`——**不做**"操作直接拼增量变更集"的第二条路径（两条路径必然分叉，正是本项目最贵的教训形态）。
2. `create`：`units` 已有同 id ⇒ 抛；`parent` 值（若 present）必须在 `units` 里 ⇒ 否则抛；`position` 不做地图校验（定位是地址的事）。
3. `reparent` / `placeAt`：追加新段（`from = at`）；同刻已有段 ⇒ 由 `SegmentedSeries` 的严格升序抛。`reparent` 只校验新父存在；**成环由 `UnitState` 构造期拒绝**（不重复实现）。
4. `planRoute`：`route` 的起点必须等于该单位在 `at` 时刻的 `effectivePosition`（无位置 ⇒ 抛）；`movement` 置为 `Optional.of(new Movement(route, at, unit.speed(), unit.mobilityPerMille()))`。
5. `cancelRoute`：`movement` 置空（`placeAt` 与 `disband` 也顺带清空在途路线——在途单位被改位置/解散后，旧路线没有意义）。
6. `disband`：**在 `at` 时刻有下属 ⇒ 抛**（"先改编子单位、再解散"；判据 = 遍历所有单位在 `at` 的 `parent` 值是否指向它）。
7. 名单外的编辑（速度、机动性、装备之外的自定义字段）**不在操作面**——需要时走 `between`，即"改字段"永远是变更集的语义，不是操作面的语义。

### 4.7 变更集

```java
public record UnitChangeSet(FieldDelta<Unit> units) {
  public static UnitChangeSet between(UnitState base, UnitState target) { … }
  public static UnitState apply(UnitChangeSet cs, UnitState base) { … }
  public boolean isEmpty() { … }
}
```

形制与 §3.4 完全同构（key = `UnitId.toString()` / `UnitId.parse`）。

### 4.8 地址与 `UnitResolver`

命名空间 `unit`。**canonical = `unit:<unitId>`**（U2）：

| 地址 | 结果 |
|---|---|
| `unit:<unitId>` | `SubjectId("unit", <id 值>)`，typeName `"Unit"`；`units` 里没有 ⇒ 空候选 |
| `unit:"<链>"`（引号单段，链以 `.` 分段，自顶向下） | 链式**定位形式**：按 `ctx.at()` 从根（该时刻无父者）逐级按 `name` 匹配 ⇒ 每个命中都是一个候选（canonical 一律回 `unit:<id>`）；多解按 `UnitId` 字典序保序；任一级无命中 ⇒ 空候选 |
| `unit:<unitId>:equipment.<装备名>` | 该单位装备表里有该名 ⇒ `SubjectId("unit.equipment", "<id 值>/<装备名>")`，typeName `"Equipment"`；没有 ⇒ 空候选 |
| 其余（`:member` / `:speed` / `:hex` 等属性段、更长的链式地址、其它 kind） | **空候选** |

- 链式定位对**名字里含 `.`** 的单位无效（切分错位 ⇒ 匹配失败 ⇒ 空候选，不是错误）：这类单位只能用 ID 形式定位。这是记录在案的取舍，不新增转义语法。
- 链式定位只服务**两段地址**；`unit:"链":equipment.X` 这类组合 ⇒ 空候选（避免链多解 × 子实体的组合爆炸）。
- 装配故障（没有 `unit` 切片 / 切片类型不对）⇒ 抛；非 `unit` 命名空间 ⇒ 空候选。canonical 一律由 `Address` AST 构造后 `canonical()` 产出（M1 §3.4 的加引规则不在解析器里重实现）。

---

## 五 时间语义（继承 M1 四条 + 本 spec 的补充）

M1 §七 四条照单全收，本 spec 的落点：

1. **段边界左闭右开**：`t == from` ⇒ 新段生效。人口：区间内的速率取区间左端点的 `growth.valueAt`；单位：`position` / `parent` 在段边界处即取新值。
2. **anchor 之前向前恒定延拓**：人口 ⇒ 恒为 `anchor.value()`（不增长、不施事件）；`parent` / `position` ⇒ 首段值。
3. **同刻先切段再施加事件**：人口侧 = 区间积分结束后、事件之前切换速率（§3.2 第 3/4 步）。
4. **同刻多事件按插入序**：`events` 列表的既有顺序，`ADD` 累加 / `SET` 覆盖。

补充（本 spec 冻结）：

5. **含 `ADD` 事件的序列一律用模块级 `static final` 的加法**（M1 纪律 / 主计划 §二 M2 节补记）：人口事件用 `PopulationSeries.ADDITION = Long::sum`（`static final`，恒非 null）；`growth` 不带事件（§3.2），其 `addition` 传 null——`SegmentedSeries` 只在含 `ADD` 时才要求它非 null。
6. **时间基 = 小时**（U3）：`speed` 的单位、增长率的单位、materialize 的步长都按小时读；`SimosTimestamp` 本身不变（无量纲整数）。

---

## 六 往返框架与护栏（铁律 5 / G13）

### 6.1 往返（铁律 5）

- `SocialRoundTripTest` / `UnitRoundTripTest`：**反射枚举** `SocialData` / `UnitState` 的 record 组件（各 1 个），逐组件造差异（`empty` → 只在该组件不同的 target）⇒ 三条断言（照 M2 `RoundTripComponentsTest` 的循环体）：① `cs.isEmpty()` 为 false（正面钉子）② 该组件在变更集里非 `Unchanged` ③ `apply(between(base, target), base).equals(target)`。
- 反方向两条：变更集组件 ⊆ 状态组件；**组件数钉死**（各恰 1）；**豁免集为空**并被单独钉死（`theExclusionListIsEmpty` 形态：豁免口一旦要加名字，必须在 diff 里现形）。
- 两个 `switch` 的 `default` 一律**抛**（不许 `default -> base` 的温和兜底）。

### 6.2 护栏清单（每条都要 G13 变异自证）

| # | 护栏（用例） | 形态 |
|---|---|---|
| R1 | 全仓**恰一份** `FieldDelta`（扫描 `simos-util` / `simos-map` / `simos-social` / `simos-unit` 的 `src/main`） | 扫描 + 计数 |
| R2 | `TerrainCatalog.of("ocean").moveCost() == TerrainType.IMPASSABLE_MOVE_COST` | 跨件一致性（C5） |
| R3 | 人口种子 + 三条边界语义（§3.6 的两张表） | 逐值断言（判据一） |
| R4 | `growth` 首段晚于 anchor、或 `growth` 带事件 ⇒ 构造抛 | 负向（构造） |
| R5 | `parent` / `position` 的 `events` 非空 ⇒ 构造抛 | 负向（构造） |
| R6 | 单位自环（`parent` 指向自身）⇒ 构造抛；跨单位环 ⇒ `UnitState` 构造抛；**"A→B（t1）、B→A（t2）"的合法改编不得误报** | 负向 + 反向 |
| R7 | `effectivePosition` 的继承链（自身优先 / 向父取 / 全链无位置 ⇒ 空） | 行为 |
| R8 | A\*：与 Dijkstra 对拍成本相等；`start == goal` ⇒ 单元素；不可达 ⇒ 空；**决定论**（同输入两次同结果） | 行为 + 对拍 |
| R9 | 移动判据二的三行表（§4.5）+ `ARRIVED` + `NEED_REPLAN` | 逐值断言（判据二） |
| R10 | `Movement` 的 `speedAtDeparture` 冻结：出发后改单位速度，`evaluate` 结果不变 | 行为（防时间反演） |
| R11 | 8 项操作各自的负向（同 id 创建、改不存在单位、拆有子单位、无位置下路线…） | 负向 |
| R12 | `SocialResolver` / `UnitResolver` 的空候选 vs 抛的分工逐条 | 行为 |
| R13 | canonical 只回 ID：链式输入的解析结果 `canonicalAddress` 是 `unit:<id>` 形态 | 行为（U2） |

**口径**（M1/M2 归纳，形态清单是权威）：变异体必须与原件**字节不同**、按**白名单推成目标类名**、每轮恢复干净世界、断言 `COMPILATION ERROR` 计数为 0、红点必须落在被保护的那一行上；红了要问"为什么红"，没红要问"为什么没红"。

---

## 七 任务分解草案（交给 writing-plans 展开成 bite-sized）

| # | 任务 | 依赖 |
|---|---|---|
| 1 | `FieldDelta` 上移 util + R1 守卫 + M2 侧 import 机械修正 | — |
| 2 | `TerrainType.IMPASSABLE_MOVE_COST` + R2 守卫 | 1 |
| 3 | `PopulationSeries`（构造校验 + 积分 + `segments()`）+ R3 + R4（§3.6 两张冻结表） | — |
| 4 | `SocialData` / `SocialSnapshot` / `SocialChangeSet` + 往返测试（含豁免集钉死） | 1, 3 |
| 5 | `SocialResolver` + R12/R13 的 social 半 | 4 |
| 6 | `UnitId` / `Unit` / `UnitState`（树校验）+ `effectivePosition` + R5/R6/R7 | — |
| 7 | `UnitChangeSet` + 往返测试 | 1, 6 |
| 8 | `MovementCost` + `TerrainMovementCost` + 判据二夹具（毫 MP 逐值） | 2, 6 |
| 9 | `PathFinder`（A\*）+ R8 对拍与决定论 | 8 |
| 10 | `Route` / `Movement` / `UnitMoves.evaluate` + R9/R10（判据二） | 9 |
| 11 | `UnitOperations` 8 项 + R11 | 7, 10 |
| 12 | `UnitResolver` + R12/R13 的 unit 半 | 11 |
| 13 | M3 关账：`./mvnw clean verify` + 四条判据逐条核 + `CLAUDE.md` 状态更新 + 报告 | 全部 |

---

## 八 挂起项（随 M3 记录，不在本轮实现）

1. **`GameMap` 无 id** ⇒ `social:<mapId>` 的 `mapId` 只回显、不可校验（M2 遗留，Task 13 挂起项同源）。
2. **属性段地址不服务**：`social:…:population` / `:population_growth`、`unit:…:member` / `:speed` / `:hex` 的解析（值读取与 Info 挂载）归 M4 的查询层；Info 能挂到属性地址上是总纲 §4.5 的要求，M4 必须处理。
3. **materialize 写回状态**：`UnitMoves.evaluate` 是核心；把抵达点写回 `position` 段、清空完成的 `movement`，需要"上次物化到哪 + 两阶段推进"的语义 ⇒ M4 的时间推进。
4. **`ChangeSet` 接口（`baseRevision`）无人实现**（util 有接口、M2/M3 的变更集都不带版本戳）⇒ M4 决定统一形态。
5. **人口 cache**：本轮不缓存（C1）；将来要加必须是派生件（不进组件/变更集/存档）。
6. **单位 ID 分配器**：`u-f82a` 只是示例形态；分配规则（前缀、长度、随机源）属命令层（M4）。
7. **速度/机动性变更的在途语义**：`Movement` 冻结出发值（R10），故"途中改参数"不影响当前行程；若将来要求"立即生效"，需要显式的"重新出发"操作（M4 的路线策略一并裁决）。
8. **链式定位与名字含 `.`**：含点名字无法用链式定位（§4.8）；若将来要支持，需裁转移义语法。
9. **A\* 的规模**：v1 无权重缓存、无双向搜索；大地图（数十万格）的性能未测——真需要时按 M2 的"实测再定"口径处理。
