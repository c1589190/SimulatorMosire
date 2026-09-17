# 契约分层与 Core 的依赖收窄（ADR-1）

> **权威层级**：本文件是总纲 `2026-09-16-simos-master-design.md` **§三「模块划分与依赖方向」** 的**修订**。
> 与总纲冲突处，以本文件为准；本文件未触及的部分仍以总纲为准。
>
> **性质**：架构决策记录（ADR）。它记的是**为什么**，不是实现步骤——实现步骤归 M4 的计划。
> **写作纪律**：本文每个数字都有当场跑过的出处；**读文档得知的**（而非跑出来的）单独标注。§十一 列了尚未自证的东西。

---

## 〇 裁决记录

### 〇.1 用户裁定（2026-09-18，三项）

| # | 待决项 | 裁定 |
|---|---|---|
| U12 | 契约层是否拆出独立模块（`simos-spi`） | **不拆**。不新建模块、不搬包 |
| U13 | 契约面的稳定性 | **一旦成型即稳定**；后续要动 = 大版本更新 |
| U14 | 走「乙-3」的实质 | 采纳：**`simos-core` 的 main scope 只依赖共享层**（即铁律 4 结构化） |

### 〇.2 控制器裁定（可在评审时推翻）

| # | 项 | 裁定与理由 |
|---|---|---|
| C9 | 新契约的落点 | M4 新增的 `CommandHandler` / `TimeParticipant` / `ModuleCodec` 放 `io.mosire.simos.util.spi`。**老契约原地不动**——搬 26 个文件是纯 churn，且**买不到任何强制力**（见 §六） |
| C10 | Core 的 enforcer 地位 | `simos-core` 从「集成点，不设限」改为**最需要被限的那个**：main scope 禁领域模块。理由是铁律 4 应当由构建期强制，而非靠自觉 |
| C11 | 信封与载荷的分家 | `AdvanceTime` / `ForkBranch` 归 Core；八条 unit 命令归 `simos-unit`，以**不透明载荷**跨边界（见 §八） |

---

## 一 触发：一个正确的质疑

用户 2026-09-18 原话：

> 「这套会遇到什么问题，三个模块各不相通，那 util 事实上是不是就集成了大量原本应该 map 处理的东西」
>
> 「假设后续有更多功能模块呢？假设要实现 plugin 系统呢？」

这个质疑**一半不成立、另一半成立**，而且成立的那一半比字面说的更严重。

---

## 二 实测：质疑的两半

### 2.1 map → util 没有渗漏（字面部分不成立）

```
util 主源码里出现 "Hex"                                  ：0 次
util 主源码里出现 Region / Terrain / Pathway / City / GameMap：0 次
util 的依赖                                              ：jackson-databind + slf4j-api（+ test scope 的 junit / assertj）
```

`HexCoord` / `HexGrid` / `Region` / `TerrainType` 全在 `simos-map`；三个 Resolver 各在自己模块。
M1 那条「UtilSimos 仅 Jackson + SLF4J、不碰文件系统」**守住了**。

`FieldDelta` 确实是从 `simos-map` 上移到 `simos-util` 的（M3 裁定 C7），但 C7 的论证成立的：
它是通用机制件，留在 map 会让 social/unit 的变更集依赖 map 的「变更机制」（语义错位）。

### 2.2 成立的那一半：Core 的装配契约在 util 堆积

不是 **map → util**，是 **Core 与模块之间的契约 → util**。已发生的实例：

| util 里的类型 | 它其实是 | 动机 |
|---|---|---|
| `state/SimulationState`、`state/StateMeta` | 世界**组合** | 装配 |
| `state/Snapshot`、`state/ChangeSet`、`state/Command` | 模块与 Core 的**接口** | 装配 |
| `facet/*`、`info/*`、`resolve/ResolverRegistry` | 注册与查询**协议** | 装配 |
| `address/*` | 地址系统本身通用，但**存在的理由是**让三个互不认识的模块能被统一寻址 | 装配 |

### 2.3 关键依据：util 里的契约**基本还是空壳**

这是「现在动几乎零成本」的实测依据：

| 类型 | 实现者 / 消费者 |
|---|---|
| `state/Command` | **0 个实现者** |
| `state/ChangeSet` | **0 个实现者**（铁律 5 的守卫工具 `RoundTripAssertions` 唯一消费者是它自己的单元测试） |
| `facet/FacetProvider` | **0 个实现者** |
| `state/SimulationState` | main 代码**从没构造过**；只在 3 个测试文件里 `new` 过 |
| `resolve/Resolver` | 3 个实现 ✅ 活的 |
| `Snapshot` | 三个模块各一份（`MapSnapshot` / `SocialSnapshot` / `UnitSnapshot`）✅ 活的 |

结论：**util 里"活的"是纯工具，"死的"全是装配契约。** 契约类型还没被任何生产代码用起来。

---

## 三 根因：依赖方向，不是设计失误

```
simos-util  →  simos-map  →  { simos-social, simos-unit }  →  simos-core
```

`simos-core` 在最**下游**，且各模块的 `bannedDependencies` 一律禁 `simos-core`（实测：util/map/social/unit 四个模块的 ban 列表里都有它）。

于是：**Core 与模块之间的契约若定义在 core 里，模块要 implement 它就得 `import io.mosire.simos.core.*` —— 成环，enforcer 当场拦。**

契约**只能往上游放**，一路放到了 util。

> **util 是被这条 DAG 逼成协议层的。** 这是结构性的，不是谁偷懒。

---

## 四 插件假设把「整洁问题」变成「结构问题」

`simos-core` 当前的编译期依赖（实测 `simos-core/pom.xml`）：

```
simos-util、simos-map、simos-social、simos-unit、agentlib-mosire
```

**这不是插件形态。** 插件的定义是「Core 不重新编译也能接纳新模块」；只要 core 的 pom 里还列着 `simos-unit`，加第四个模块就得改 core 的 pom 并重编 core。

三层价值：

| # | 价值 | 是否依赖「拆分模块」 |
|---|---|---|
| ① | **铁律 4 从纪律变成结构**——core 只依赖共享层后，它编译期就看不见 `Unit` 是什么，**想重新实现领域逻辑也无从下手** | 否 |
| ② | **插件就绪**——加模块 = 加一个 jar + app 层一行注册，core 一个字不改 | 否 |
| ③ | util 的整洁 | 否 |

---

## 五 否掉的方案：为什么最后没有分家

一度确定要新建 `simos-spi`（把契约从 util 上移一层）。**最终否掉**，两条理由：

### 5.1 `simos-spi` 没有独立消费者

`spi` 依赖 `util`，而**没有任何模块只依赖 spi 不依赖 util**——依赖 spi 的模块必然同时依赖 util。
**一个没有独立消费者的 Maven 模块，与「没有消费者的枚举」是同一条纪律下的同一个东西**
（M2 的 `contourCacheMax` 教训）。

### 5.2 分包**买不到强制力**

这是决定性的一条：**把契约挪进 `util.spi` 子包，编译器不会因此拦任何东西。**
本项目能用的强制手段是 `maven-enforcer-plugin` 的 `bannedDependencies`，而它是**构件级**的，表达不了包级依赖。

⇒ 搬家 26 个文件（实测并集：17 在 util 内、map/social/unit 各 3；总 java 文件 151），
换来的是**整洁**，不是**护栏**。而按项目纪律「评审的体量不得压过代码本身」，这个交换不划算。

### 5.3 而分包**也没解决**最初的问题

契约往共享层堆积是**结构性**的——任何被 Core 和 N 个模块共用的层都会积累连接它们的契约。
**拆成两个只是把堆积换个房间。**

---

## 六 最终架构

**不新建模块。全部价值来自两件事：**

```
simos-util（module 不变）
  io.mosire.simos.util.{address,time,state,identity,verify,...}   纯工具，原地不动
  io.mosire.simos.util.spi.{...}                                  契约；M4 新增的三个接口落这里（C9）
        ▲
   simos-map → { simos-social, simos-unit } → 第 N 个模块     各模块只认 simos-util
        ▲
   simos-core    main scope 只依赖 simos-util + agentlib-mosire   ← ★ 本次唯一的实质改动
                 map / social / unit 退到 test scope
        ▲
   app（M5：GUI 5711 / MCP 5715）    依赖 core + 具体模块，负责装配
```

| # | 动作 | 买到什么 |
|---|---|---|
| ① | `simos-core` 的 main scope 收窄：留下 `simos-util` + `agentlib-mosire`；map/social/unit 退到 **test scope** | §四 的 ①②③ 三条全部 |
| ② | 给 `simos-core` 加 `bannedDependencies`（它现在**一条都没有**）钉住 ①，配故意违规用例自证 | 护栏不是装饰 |

**零文件搬迁。**

---

## 七 代价：命令变成不透明载荷

main scope 收窄后，Core **看不见任何领域类型**。这直接改写了命令的形态：

```java
// ── Core 拥有：时间线是 Core 的事 ──
record AdvanceTime(RevisionId expectedRevision, TimeRange range) implements Command
record ForkBranch (RevisionId expectedRevision, BranchId newBranch) implements Command

// ── simos-unit 拥有：领域命令归领域模块 ──
record RenameUnit(UnitId id, String name)   // 载荷，不实现 Command

// ── 跨边界的是信封（Core），util.state.Command 首次获得真实现者 ──
record CommandEnvelope(String commandId, String correlationId, String initiator,
                       RevisionId expectedRevision, BranchId branch,
                       String type,            // "unit.RenameUnit"
                       String payloadJson) implements Command
```

Core 按 `type` 字符串找模块注册的 handler，**全程不 switch 类型、不 instanceof** —— 正是总纲 §5.5 要的形态。

> ⚠️ **对 M4 命令清单裁定的精确影响**：U9 裁定的是**清单**（unit 8 项 + `AdvanceTime` + `ForkBranch`），**那个不变**。
> 变的是我给出 U9 选项时 preview 里的一句注释「`// simos-core` —— 每条都是 `Command` 接口的 record」——
> `RenameUnit` 的字段含 `UnitId`，住 core 就意味着 core 认识 unit 的类型。

---

## 八 顺带解决：Resolve 的读写集

总纲 §六 要求 Resolve「解算跨模块影响」，但 Core 不认识任何领域类型——它凭什么解算？只剩两条路：

1. 按模块注册顺序合并 ⇒ **调用顺序又成了游戏规则**，正是 §六 要消灭的东西
2. **模块在 `TimeProposal` 里自带读写集**（「我读了这些地址、我写了这些地址」），Core 只做集合相交

**Core 只依赖共享层之后，第 2 条不再是权宜之计——它是 Core 唯一可能做的事。** 因为除集合以外，Core 手里没有任何可用信息。

而这恰好就是**插件间**冲突检测需要的机制：两个互不认识的插件，靠读写集相交就能被发现冲突。
⇒ **分层与 Resolve 是同一件事的两面。**

---

## 九 对既有里程碑的影响

| 里程碑 | 影响 |
|---|---|
| M0（骨架 + 边界 enforcer） | `simos-core` 的 pom 与 enforcer 要改；这是**给 M0 的边界体系补最后一块**——此前 core 是唯一没被限的模块 |
| M1（UtilSimos） | **不动**。契约原地不动（C9） |
| M2 / M3 | **不动**。三个模块的 `bannedDependencies` 已各自禁 core，与本次方向一致 |
| M4 | 命令按下 §七 的信封/载荷形态落地；Core 的装配由 app 层在 M5 接入 |
| M5 | **被牵出一件事**：§5.4 把 GUI 列为 CoreSimos 的职责，但 GUI 要按 `/map` `/social` `/unit` 路由，它天然认识各模块——main scope 收窄后它更像 **app 层**。这是 M5 的账，此处只记录 |

---

## 十 待自证（尚未跑过的东西）

按项目纪律「「我验过了」与「我记得是这样」必须分开」，下列内容**本 ADR 写作时尚未实测**：

1. **enforcer 的 scope 匹配确实有效。** `<exclude>` 的 pattern 是 `groupId[:artifactId][:version][:type][:scope][:classifier]`，scope 是第 5 段——**此结论来自官方文档，非本机实测**。官方推荐「宽 `exclude` + 窄 `include`」做例外。
   ⇒ **必须配一个故意违规用例证明它真的会响**（M0 起的硬要求），否则这条护栏等于装饰。
2. **`searchTransitive` 默认 `true`** 对 scope 例外的具体作用（同样来自文档）。
3. **`provided` scope 是一条绕过路径**：它在编译期可见。宽 exclude 能一并盖住，但需实测确认。
4. **收窄后 `simos-core` 的编译与测试仍绿**（map/social/unit 退到 test scope 后 testCompile 是否还能解析全部类型）。

---

## 十一 不做清单 / 挂起项

**明确不做**（YAGNI；项目纪律「没有消费者的枚举是死代码」）：

- ServiceLoader / JAR 扫描发现
- spi 版本协商
- ClassLoader 隔离（插件崩了不拖垮 Core）
- 插件自带依赖的冲突处理
- 新建 `simos-spi` 模块、搬迁既有契约（U12 / §五）

**以上全部等真有第二个第三方模块时再说。**

**挂起项**：

- M5 的 GUI / MCP 归属（§九）
- 契约面「一旦成型即稳定」（U13）如何落成书面约定——是写进 `util/spi/package-info.java`，还是别的形式
