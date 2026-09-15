# UtilSimos 模块设计（M1 spec）

**日期**：2026-09-16
**状态**：待用户评审
**上游**：`docs/superpowers/specs/2026-09-16-simos-master-design.md`（总纲，已批准）
**范围**：UtilSimos（`simos-util`）的全部公开类型与语义。不含 Map/Social/Unit 的领域实现，不含 Core 的存储与时间引擎。

---

## 〇 已裁决记录（本 spec 的输入）

| # | 议题（总纲 §十三 的待决项） | 裁决 | 出处 |
|---|---|---|---|
| 1 | 八大件的完整方法签名 | 类型形状**已批准**（见 §3）；本 spec 补齐组件定义与解析/渲染规则 | 上一会话"可以" |
| 2 | Address 转义与边界规则 | 名字**允许任意字符**；含分隔符时用双引号包裹；canonical **按需加引**（归一：`region."Nation"."区域A"` → `region.Nation.区域A`）；引号内 `""` 表示字面引号；冻结样例不动 | 本会话裁决 |
| 3 | Resolver 注册与优先级 | **namespace 唯一映射**：无顺序、无兜底、重复注册立即抛异常 | 本会话裁决 |
| 4 | TemporalSeries 的插值/事件语义 | 段边界**左闭右开**；同刻**先切段再施加事件**；同刻多事件按**插入序**；anchor 之前**向前恒定延拓**；`Event<T>(at, T value, EventMode)`，mode ∈ {ADD, SET} | 本会话裁决 |
| 5 | Facet 查询协议 | **保留 Facet 注册表**（总纲 §3.2 原样）：Util 提供 `FacetProvider` + `FacetEntry` + `FacetRegistry`，模块自注册、Core 装配 | 本会话裁决 |
| 附 | `SimulationState` 与 Util 零依赖冲突 | `SimulationState(StateMeta meta, Map<String, Snapshot> modules, InfoSystem info)` 留在 Util；给 `Snapshot` 补 `namespace()` | 本会话裁决 |
| 附 | `ResolveContext` 的构成 | `record ResolveContext(SimulationState state, SimosTimestamp at)`——revision 管数据版本、`at` 管模拟时间，正交；**不加**通用扩展袋 | 本会话裁决 |

**裁决之外的首次定义**集中在 §十（偏离总纲草案清单），评审重点在那。

---

## 一 范围与判据

### 1.1 交付物（总纲 §十一 M1）

八大件（Address / SubjectId / SimosTimestamp / RevisionId·BranchId·StateRef / Snapshot / ChangeSet / Command / SimulationState）
\+ Address 解析与渲染 + `InfoSystem` + `TemporalSeries` + `Resolver` SPI + Facet 协议 + **往返不变式测试框架**。

### 1.2 关账判据

| 判据 | 本 spec 的落点 |
|---|---|
| 八大件各有单测 | §十二 测试清单逐类列出 |
| 往返不变式框架有**故意漂移字段**的失败用例（证明护栏真的会响） | §9.3 |
| `./mvnw verify` 绿（Spotless + Checkstyle + SpotBugs + Surefire） | §十二 |
| 每条新护栏都有故意违规用例自证会响（G13） | M1 的新护栏即往返框架本身，由 §9.3 自证 |

### 1.3 依赖与约束（总纲 §3.1 / §10.2）

- **只依赖** Jackson databind + SLF4J API（外加 test scope 的 JUnit 5 / AssertJ，父 POM 已定 scope）；由 `maven-enforcer-plugin` 构建期强制。
- **不依赖** AgentLibMosire，**不依赖**任何 simos 模块，**不碰文件系统**。
- 本模块的任何类型**不得出现领域词汇**（hex / region / unit / population 只许出现在注释与测试用的假数据里）。
- §9 的往返断言工具位于 **main** 源码（M2~M4 都要用），因此**不能依赖 JUnit**——失败以 `AssertionError` 抛出。

---

## 二 包结构

```
io.mosire.simos.util.address    Address, AddressSegment, Namespace, Entity, Index, Property
io.mosire.simos.util.identity   SubjectId, ResolvedSubject, QueryResult
io.mosire.simos.util.time       SimosTimestamp, TimeRange, TemporalSeries, Segment, Event,
                                EventMode, SegmentedSeries
io.mosire.simos.util.state      BranchId, RevisionId, StateRef, StateMeta, Snapshot,
                                ChangeSet, Command, SimulationState
io.mosire.simos.util.info       InfoEntry, InfoSystem, InMemoryInfoSystem
io.mosire.simos.util.resolve    Resolver, ResolverRegistry, ResolveContext
io.mosire.simos.util.facet      FacetProvider, FacetEntry, FacetRegistry
io.mosire.simos.util.verify     RoundTripAssertions
```

分包依据是**变化原因**，不是技术分层：地址语法、身份、时间、状态协议、外挂属性、解析分发、跨模块视图、测试守卫各自独立演化。

---

## 三 Address 与四种段

> 本节是 M1 最贵的一节：地址写歪，M2~M6 全歪（总纲 §11.1）。

### 3.1 类型（总纲已批准的形状）

```java
public sealed interface AddressSegment permits Namespace, Entity, Index, Property {
    String canonical();                        // 本段的规范写法（按需加引，§3.4）
}

public record Namespace(String ident) implements AddressSegment {}          // map / social / unit / agent
public record Entity(Optional<String> kind, String name) implements AddressSegment {}  // hex.4_3 / Map1
public record Index(List<Integer> coords) implements AddressSegment {}      // [4,3] / [7]
public record Property(String ident) implements AddressSegment {}           // population / height

public record Address(List<AddressSegment> segments) {
    public Address { /* 防御性拷贝；至少两段；首段必须是 Namespace */ }
    public static Address parse(String text);  // 宽容解析（§3.5）
    public String canonical();                 // 唯一规范写法，机器协议只用这个
    public String namespace();                 // = segments.get(0) 的 ident
}
```

**`Entity.kind` 为什么是 `Optional`**：总纲 §4.2 的 Human 形式 `map:Map1:"Nation"."区域A"` 里**没有类型词**，canonical 才有（`region.Nation.区域A`）。类型词由 Resolver 在解析时补齐，语法层必须允许缺省。`Optional` 也与已批准的 `SimosTimestamp(tick, Optional<String> calendarLabel)` 同一风格。

### 3.2 段类型判定规则（**本 spec 首次定义**）

按段的位置与表面形式判定，**只有这些规则，没有隐式兜底**：

| 位置 | 表面形式 | 判定 |
|---|---|---|
| 第 1 段 | 任意 | **Namespace**（必须是裸词，否则解析失败） |
| 第 2 段 | 裸词 | **Entity**，`kind` 缺省（`Map1`、`U`） |
| 第 2 段 | 含未加引号的 `.` | **Entity**，第一个 `.` 切 kind/name（`hex.4_3`） |
| 第 2 段 | `[...]` | **Index** |
| 第 ≥3 段 | `[...]` | **Index** |
| 第 ≥3 段 | 含未加引号的 `.` | **Entity**，第一个 `.` 切 kind/name |
| 第 ≥3 段 | 裸词 | **Property**（`population` / `height` / `member` / `hexes`） |

**第 2 段永远是这个命名空间的"根主体"**（总纲 §4.4 的整张地图 `map:Map1`、编制树上的小队 `unit:U` 都落在这一位）。机器协议里的具体实体一律用 `kind.name` 形式（`hex.4_3` / `region.Nation.区域A`），裸词主体只出现在根位置。

**kind 与 name 的切分**：段内**第一个不在引号内**的 `.`：
- 左侧**未被引号包裹** ⇒ `kind = 左侧`，`name = 右侧整体`（`region.Nation.区域A` → kind=`region`，name=`Nation.区域A`）
- 左侧**被引号包裹** ⇒ 无 kind，`name = 整段`（去掉引号、组件以 `.` 连接）：`"Nation"."区域A"` → `Entity(∅, "Nation.区域A")`

**kind 必须是裸词**（不含 `: . [ ] "` 与空白，非空）。这条是**有意的收紧**：kind 是结构词（类型判别用），name 才是任意字符的载体。理由：若 kind 也允许含 `.`，`"a.b".name` 与 `"Nation"."区域A"` 两种形态在语法上不可区分。

### 3.3 名字合法性（用户裁决："只要能读就都能加"）

- **name 允许任意字符**，包括 `:` `[` `]` `"` 空格、CJK、emoji。
- 需要引号包裹的情形由 §3.4 的渲染规则决定；解析器一律**宽容接受**带引与不带引两种写法。
- 引号内：`""` 表示一个字面 `"`；`:` `.` `[` `]` 均按字面处理，不参与切分。

### 3.4 按需加引（canonical 唯一性的保证）

canonical 是**机器协议的唯一形态**：同一个实体只能有一种规范写法，否则 `region.Nation.区域A` 与 `region."Nation"."区域A"` 会指向同一实体却是两个字符串——查询缓存、事件日志的目标地址、`correlationId` 追溯全会裂开。

**渲染规则**：token 满足任一条件即加双引号，否则一律不加（冻结样例因此原样输出）：

| # | 条件 | 例 |
|---|---|---|
| 1 | 含 `:`、`[`、`]`、`"` 任一（含以 `[` 开头——不加引就会被当成 Index 段） | `"河口:渡口"`、`"[4,3]"` |
| 2 | 为空串，或首尾含空白 | `" 区域A "` |
| 3 | 作为 `Entity` 的 name，且 kind 缺省，且 name 含 `.` | `"高地人旅指挥部.1营指挥部"` |

条件 3 是 §3.2 切分规则的直接后果：缺 kind 的 name 一旦含 `.`，不引就会被误切成 `kind.name`，往返不变式立刻破。**三条之外一律不加引**——多一处加引，canonical 就少一处唯一。

**归一**：解析时引号只用于消歧，**不进入 AST**——`region."Nation"."区域A"` 与 `region.Nation.区域A` 解析为同一个 AST、渲染为同一个 canonical。这是"按需加引"的另一半。

### 3.5 宽容解析（人类形式）

- 引号可省略（不与结构冲突时）、可冗余（归一后消失）。
- **兼容写法**：段内出现"未加引号的 `.` 紧跟 `[`"时，`.` 处断开为两段——消化总纲遗留的 `social:Map1.[4,3]:population` 写法（`Map1.[4,3]` → `Entity(∅,Map1)` + `Index([4,3])`）。canonical **永不产生**该写法。
- 除上述之外**不猜测**：结构不合法（总段数 < 2、首段非裸词、空段、`[` 无闭合、`[]` 空索引）一律抛 `IllegalArgumentException`，附上出错的段序号与原文。名字要以 `[` 开头就加引（§3.4 条件 1），故不存在"既像 Index 又是名字"的合法歧义。

### 3.6 冻结样例核对表（总纲 §4.4，逐条必须成立）

| canonical 原文 | 解析结果 | `render(parse(s)) == s` |
|---|---|---|
| `map:Map1` | [Ns(map), Entity(∅,Map1)] | ✅ |
| `map:Map1:hex.4_3` | + Entity(hex,4_3) | ✅ |
| `map:Map1:terra.Grass` | + Entity(terra,Grass) | ✅ |
| `map:Map1:terra.Grass:height` | + Property(height) | ✅ |
| `map:Map1:region.Nation.区域A` | + Entity(region, Nation.区域A) | ✅ |
| `map:Map1:region.Nation.区域A:hexes` | + Property(hexes) | ✅ |
| `map:Map1:conn.river.r-f82a` | + Entity(conn, river.r-f82a) | ✅ |
| `social:Map1:hex.4_3:population` | [Ns(social), Entity(∅,Map1), Entity(hex,4_3), Property(population)] | ✅ |
| `social:Map1:hex.4_3:population_growth` | 同上（`_growth`） | ✅ |
| `unit:U:member`（U 见下注） | [Ns(unit), Entity(∅,U), Property(member)] | ✅ |
| `unit:U:equipment.步枪` | + Entity(equipment, 步枪) | ✅ |
| `unit:U:hex` / `unit:U:speed` | + Property(hex) / Property(speed) | ✅ |
| `agent:bind.b-f82a` | [Ns(agent), Entity(bind, b-f82a)] | ✅ |
| `agent:map:Map1:region.Nation.区域A`（Human 便利形式） | 见 §3.7 | 往返恒等 ✅ |

> **U 的注**：总纲把 `U` 展开为 `高地人旅指挥部.1营指挥部.1连指挥部.1排指挥部.1班`，并声明该符号"仅用于排版压缩，不进入实现"。展开式**不含在冻结样例内**；它若作为真实地址书写，按 §3.4 条件 3 其 canonical 形态为带引号的 `unit:"高地人旅指挥部.1营指挥部.1连指挥部.1排指挥部.1班"`。UnitSimos（M3）若希望不加引，需要给单位段一个 kind（如 `unit:班.1班`）——**留给 M3 的 spec 裁决**，本 spec 不做假设。

### 3.7 跨命名空间嵌套（`agent:` 形式）

`agent:map:Map1:region.Nation.区域A` 里出现了一个**内嵌的完整地址**。语法层不做特殊处理：段序列原样承载，`resolve` 由 `agent` 命名空间的 Resolver 自己解释（总纲 §5.5 的 `AgentBinding.targetSubject` 用 canonical，该形式只是查询便利形式）。M1 的保证仅限于：**任意合法地址字符串 parse→canonical 恒等**。嵌套语义留给 M5。

---

## 四 身份与解析结果

```java
public record SubjectId(String namespace, String localId) {}
```

- `namespace` 取 `<模块>` 或 `<模块>.<类型>`（如 `map.hex`、`unit.equipment`）——**命名的层级由各模块自定**，Util 不校验。
- `localId` 是该类型内的稳定 ID。**地址是定位方式，ID 是身份**（铁律 1）：改名、调动、改地址，ID 不变。
- 生成规则（前缀、长度、随机源）属于各领域模块，Util 只承载。

```java
public record ResolvedSubject(SubjectId id, String canonicalAddress, String typeName) {}
public record QueryResult(List<ResolvedSubject> candidates) {}
```

- `resolve` 的返回值是**候选列表**：Human 形式可多解，全部列出；调用方选定后回传 canonical，此后一律用 canonical（总纲 §4.2）。
- `typeName` 是展示/路由用的类型名（`Hex` / `Region` / `Unit`），由 Resolver 填。

---

## 五 时间与版本

```java
public record SimosTimestamp(long tick, Optional<String> calendarLabel) implements Comparable<SimosTimestamp> {
    public static SimosTimestamp of(long tick);
    public static SimosTimestamp of(long tick, String calendarLabel);
    public SimosTimestamp plus(long delta);     // 保留 label；负值即回拨
}
public record RevisionId(long value) implements Comparable<RevisionId> {}
public record BranchId(String value) {}
public record StateRef(BranchId branch, RevisionId revision) {}   // 状态的唯一坐标
public record StateMeta(StateRef ref, SimosTimestamp timestamp) {}
```

- **`tick` 是第一序**；比较只看 `tick`（`calendarLabel` 仅作展示，同 tick 视为同一时刻）。
- **时间戳与版本正交**（总纲 §0.1）：`RevisionId` 管数据版本，`SimosTimestamp` 管模拟时间，两把尺子互不换算。
- `RevisionId` **只在分支内有意义**；跨分支的完整坐标是 `StateRef`（总纲 §4.1）。
- **没有 setter**：推进只经 `plus(delta)`（总纲 §4.9）。世界时钟"只能 `advance`/`rewind`、不能赋值"的纪律由 Core（M4）的时钟持有者保证；Util 只保证类型本身不提供复写入口。
- `StateMeta` 是**整个世界状态**的坐标（`Snapshot` 各自的 `ref()` 是模块切片的坐标，两者在正常状态下一致，由 Core 保证）。

---

## 六 三个协议接口与容器

```java
public interface Snapshot {
    StateRef ref();
    SimosTimestamp timestamp();
    String namespace();                 // 本快照所属模块（"map" / "social" / "unit"），见 §10-D1
}
public interface ChangeSet { RevisionId baseRevision(); }
public interface Command   { RevisionId expectedRevision(); }   // 乐观并发（总纲 §7）

public record SimulationState(StateMeta meta, Map<String, Snapshot> modules, InfoSystem info) {
    public Optional<Snapshot> module(String namespace);   // 唯一的取用入口，无跨模块访问器
}
```

- **明确不用万能父类**（总纲 §4.5）：`MapSnapshot` / `SocialSnapshot` / `UnitSnapshot` 是各自独立的 record 树，只实现 `Snapshot`。Util 侧**没有** `BaseSimosObject`。
- `SimulationState` **不提供跨模块访问器**（没有 `state.map().units()` 这类入口）；跨模块可见性只走 Facet（§8）。
- `ChangeSet` 的**字段清单由各模块从自己的 Snapshot 类型派生**（铁律 5）；Util 只提供接口与往返断言工具。
- `Command` 保持最小：只有 `expectedRevision()`。`commandId` / `correlationId` / 发起者 / 耗时等属于**命令信封**（总纲 §8.1），由 Core 的 Command Bus（M4）承担——见 §10-D6。

---

## 七 TemporalSeries：分段常量 + 离散事件

```java
public interface TemporalSeries<T> {
    T valueAt(SimosTimestamp t);       // 即时计算，不物化中间点
    List<Segment<T>> segments();       // 分段常量，按 from 升序
    List<Event<T>> events();           // 离散跳变，按插入序
}
public record Segment<T>(SimosTimestamp from, T value) {}
public record Event<T>(SimosTimestamp at, T value, EventMode mode) {}
public enum EventMode { ADD, SET }

public final class SegmentedSeries<T> implements TemporalSeries<T> {
    public static <T> SegmentedSeries<T> of(
            List<Segment<T>> segments, List<Event<T>> events, BinaryOperator<T> addition);
}
```

**不做插值**：段是**阶跃常量**（总纲 §4.7 的"分段常量"）——`valueAt` 在段内恒定，只在段边界或事件处跳变。连续变化（人口那种积分型序列）**由 SocialSimos 自己实现 `TemporalSeries<T>`**，本 spec 只保证四条时间语义被它继承。

**四条时间语义（本会话裁决，`SegmentedSeries` 必须逐条可测）**：

1. **段边界左闭右开**：段 `[from_i, from_{i+1})`；`t` 恰好等于切换点时**新段生效**。最后一段延伸到无穷。**第一段即 anchor**（总纲 §4.7 的 `t=0 population=10000 anchor` 就是 `Segment(at(0), 10000)`）。
2. **anchor 之前**（`t` 早于第一段 `from`）：**向前恒定延拓** = 第一段的值；不外推、不抛异常。
3. **同一时刻先切段、再施加事件**：事件发生在"那一刻的新速率之下"。
4. **同一时刻多个事件按插入序**稳定施加：`valueAt(t)` 先取段值，再把全部 `at ≤ t` 的事件按插入序依次施加——`ADD` ⇒ `v = addition.apply(v, e.value)`，`SET` ⇒ `v = e.value`。

**ADD 的算术来源**：Util 不能用泛型做加法，也不把 `T` 限制成数字——`SET` 不需要算术，`ADD` 需要时由调用方注入 `BinaryOperator<T>`（如 Social 的人口）。带 `ADD` 事件却给不出 `addition` 的序列在**构造期即抛异常**（不给运行期惊喜）。

**不可变**：段/事件列表防御性拷贝并保序；"增长率的调整"在模块侧表现为新建序列或追加分段，不是原地改。

---

## 八 跨模块可见性：Facet

```java
public interface FacetProvider {
    String facetName();                                        // "unitsHere" / "population"
    List<FacetEntry> query(Address subject, ResolveContext ctx);
}
public record FacetEntry(String namespace, String label, String typeName, Object value) {}

public final class FacetRegistry {
    public void register(FacetProvider provider);              // facetName 重复 ⇒ 抛异常
    public List<String> facetNames();                          // 注册序
    public List<FacetEntry> queryAll(Address subject, ResolveContext ctx);
}
```

- **解决什么**：`inspect map:Map1:hex.4_3` 要能列出该 hex 上的单位，而 `MapSimos` 绝不能知道 `UnitSimos` 存在（铁律 3）。模块自注册提供者，`MapSimos` 对这些扩展完全不知情。
- `value` 是**结构化 `Object`**（Jackson 可序列化），不是预先格式化的字符串：GUI / MCP / LLM 三种消费者对同一个值有不同呈现需求，字符串化会把结构在 Core 层丢掉。
- `namespace` 是**贡献者**的模块名（`unit` / `social`），用于分组与显示排序；`typeName` 供 UI 着色/图标。
- **注册语义与 Resolver 注册表一致**：facetName 唯一映射、重复注册立即抛异常、无兜底。
- `queryAll` **按注册顺序**拼接——显示顺序因此是确定性的（由 Core 装配顺序决定），不引入排序规则。
- 提供者返回空列表 = "该主体上我这一面没有内容"，不是错误；主体不存在由各提供者自行决定（通常返回空）。

---

## 九 变更集：往返不变式测试框架

### 9.1 为什么是测试而不是编译期（铁律 5 的落地边界）

总纲 §9 写的"漂移在编译期即失败"在 Java 里**只能覆盖一半**：

- **覆盖得了**：状态 record 加字段 ⇒ canonical 构造器 arity 变 ⇒ 全参重建处（`apply`）编译失败，逼你回到实现。
- **覆盖不了**：`ChangeSet` 的字段清单。L1 事故里 `MapData` 加字段根本不会让 `MapDiff` 编译失败——这正是四个字段漂移出去没人发现的原因。

漏掉的那一半由 **record 的 `equals()`** 兜住：`equals()` 自动比较全部组件，所以只要往返断言写成 **`apply(diff(base, target), base).equals(target)`**，任何漏在 ChangeSet/diff/apply 里的字段都会让测试红——不需要反射，也不随字段增长而失效。

### 9.2 工具（main 源码，不依赖 JUnit）

```java
public final class RoundTripAssertions {
    /** 通用：任何状态类型（模块快照或整个 SimulationState）。 */
    public static <S, C extends ChangeSet> void assertRoundTrip(
            S base, S target, BiFunction<S, S, C> diff, BiFunction<C, S, S> apply);

    /** 快照专用：额外要求 cs.baseRevision() 等于 base.ref().revision()。 */
    public static <S extends Snapshot, C extends ChangeSet> void assertSnapshotRoundTrip(
            S base, S target, BiFunction<S, S, C> diff, BiFunction<C, S, S> apply);
}
```

- 失败抛 `AssertionError`，报文含 `base` / `target` / 实际结果三者的 `toString()`（record 自动生成，逐字段可读）与一句定位提示。
- 第二个方法多守一条：**变更集必须相对于它被施加的那个 base**——防止 diff 盖错版本戳。

### 9.3 护栏自证（G13，M1 的硬判据）

`RoundTripAssertionsDriftTest` 里有一个**故意漂移**的用例：玩具快照 `ToySnapshot(ref, timestamp, namespace, alpha, beta)` + `ToyChangeSet` 只携带 `alpha`，base 与 target 在 `beta` 上不同 ⇒ `assertRoundTrip` **必须抛 `AssertionError`**。断言写 `assertThatThrownBy(...)`——**这条路一旦断，护栏就只是装饰**，故它本身也是 M1 的验收项。

---

## 十 偏离总纲草案清单（**评审重点**）

| # | 偏离 | 理由 | 状态 |
|---|---|---|---|
| D1 | `SimulationState` 用 `Map<String, Snapshot> modules` 取代领域类型槽位；`Snapshot` 增补 `namespace()` | Util 不得 import 领域类型，草案的 `MapSnapshot` 等槽位在 Util 里编译不过 | 本会话已裁决 |
| D2 | `Entity` 定义为 `(Optional<String> kind, String name)`；段类型判定规则（第 2 段 = 根主体段） | 草案只给了 permits 列表；总纲的 `└ns └entity └index └property` 注解与 Human 形式（省略类型词）共同确定 | 本 spec 首次定义 |
| D3 | kind 必须裸词；"任意字符"落在 name 上（引号转义） | 否则 `"a.b".name` 与 `"Nation"."区域A"` 在语法上不可区分 | 本 spec 首次定义 |
| D4 | `InfoSystem.get` 增 `key` 参数；`put` **返回新实例**（草案是 `void put`） | `InfoEntry` 自带 `key`，一个主体同时可有多个 key；且状态组件必须不可变，否则 `equals()` 往返断言无从谈起（铁律 5） | 本 spec 修订 |
| D5 | `TimeRange(from, Optional<SimosTimestamp> to)`、`Segment`、`Event`、`EventMode`、`SegmentedSeries` 的定义 | 草案只给了接口轮廓，语义本次裁决后才可写成类型 | 本 spec 首次定义 |
| D6 | `Command` 不含 `commandId` / `correlationId` | 那是**命令信封**（§8.1 的记录字段），属于 Core 的 Command Bus；接口保持最小，M4 再定信封形态 | 提请评审 |
| D7 | `unit:"……"` 单位链的 canonical 需要引号（总纲的 `U` 展开式不引） | §3.4 条件 3 的机械后果；`U` 是排版压缩符号、不属冻结样例。M3 可另选"给单位段加 kind" | 提请评审 |

---

## 十一 往返与不变式的其余约定

- **`parse`/`canonical` 双向恒等**：`parse(canonical(a)) == a`，`canonical(parse(s)) == s`（s 为规范写法）。冻结样例逐条覆盖（§3.6）。
- **集合防御性拷贝**：`Address.segments`、`QueryResult.candidates`、`SegmentedSeries` 的段/事件列表一律 `List.copyOf`。
- **`equals`/`hashCode`/`toString`** 全部由 record 提供——**禁止**给这些类型手写 `equals`（`equals` 是往返断言的判据本身）。
- **不可变**：Util 的所有状态类型一律不可变；"修改"由各模块的 `diff`/`apply` 产生新实例。

---

## 十二 测试清单（判据"八大件各有单测"的落点）

| 测试类 | 覆盖 |
|---|---|
| `AddressParseTest` | §3.6 冻结样例逐条：解析结果 + 往返恒等；第 2 段主体段规则 |
| `AddressQuoteTest` | §3.4 三条加引条件各一例；`""` 转义；冗余引号归一（`region."Nation"."区域A"` → `region.Nation.区域A`） |
| `AddressTolerantParseTest` | `Map1.[4,3]` 宽容写法；非法输入（段数 < 2、首段非裸词、`[` 未闭合、空段、`[]`）抛 `IllegalArgumentException` |
| `SubjectIdTest` / `ResolvedSubjectTest` / `QueryResultTest` | 值语义、不可变、候选列表保序 |
| `SimosTimestampTest` | 排序、`plus` 保留 label、时间戳无 setter（编译期即证） |
| `StateRefTest` | 分支 + 版本的坐标语义 |
| `SnapshotProtocolTest` | 玩具快照实现三个接口方法；`namespace()` 与 `modules` 键一致 |
| `SimulationStateTest` | `module(namespace)` 取用；无跨模块访问器（编译期即证） |
| `InMemoryInfoSystemTest` | 按 key + 时刻取值；有效区间（`TimeRange` 左闭右开）；`put` 返回新实例且原实例不变 |
| `TemporalSeriesTest` | 四条时间语义逐条：段边界左闭右开；同刻先切段后事件；ADD 累积 / SET 覆盖；同刻多事件按插入序；anchor 之前恒定延拓；带 ADD 无 `addition` 构造期抛异常 |
| `ResolverRegistryTest` | 唯一映射；**重复注册抛异常**；未注册命名空间抛异常（**无兜底**，与 GSimulator 的静默遮蔽相反） |
| `FacetRegistryTest` | 唯一 facetName；`queryAll` 按注册序；提供者返回空列表不报错 |
| `RoundTripAssertionsTest` | 正常往返通过；baseRevision 不符时抛错 |
| `RoundTripAssertionsDriftTest` | **§9.3 的故意漂移用例：必须抛 `AssertionError`** |

---

## 十三 不做清单（交给后续阶段）

| 项 | 交给 |
|---|---|
| `map:` / `social:` / `unit:` / `agent:` 的具体 Resolver 与地址语义 | M2 / M3 / M5 |
| 快照与变更集的 Jackson 序列化（含 `FacetEntry.value` 的多态处理） | M4（存储） |
| 时间推进两阶段（`TimeParticipant` / `TimeProposal`） | M4 |
| 命令信封（`commandId` / `correlationId` / 发起者 / 摘要） | M4 |
| 世界时钟（"只能 advance/rewind"的执行者） | M4 |
| 单位编制链的地址形态（引号 vs 加 kind） | M3（见 §3.6 注、§10-D7） |
| `agent:` 嵌套主体地址的语义 | M5 |
