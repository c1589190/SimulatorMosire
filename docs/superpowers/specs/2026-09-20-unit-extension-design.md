# Unit 扩容设计 —— 编制两级 / 回归路径 / 三态 / 战损增量（UnitSimos Extension）

> 状态：**待用户裁决**（未拍项逐条见 §〇.2，编号 `P1~P11`）。作者：控制器（AI 代笔）。日期：2026-09-20。
> 前置依据：`2026-09-20-sd-simos-design.md`（★ 其 **§十「与 Unit 扩容的边界」** 与 **§〇 裁定表**）、`2026-09-20-sd-simos-research.md`（★ 其 **§A.1 是既有 Unit 代码实测，逐条带 `文件:行`**；§B.5/§B.7 是战损与 ORBAT 的外部研究）、`2026-09-20-sd-simos-brainstorm.md`（★ 其 **§3 Unit 的进一步开发** + **§7 五个结构性缺口**）、`CLAUDE.md`（五条铁律 + 模块依赖硬约束 + 纪律）。
> 本文是**设计**，不是计划：**不含 bite-sized 步骤**；未拍项一律标「待裁」，**不替用户决定**。
> 术语沿用 sd 主 spec / research：**编制**分两层——**`command_chain`（谁向谁报告，可多属）** 与 **`Formation`（谁物理跟谁移动：严格树 + attached/detached + 相对偏移）**；战损 = **人员/装备双轨 + delta**（N3）；三态 = **移动 / 休整 / 交战**。

---

## §〇 裁定表

### 〇.1 E 系列（本 spec 已裁定 / 由本任务转述的用户裁定）

| # | 裁定 | 来源 |
|---|---|---|
| **E1** | ★★ **编制采用「两级结构」（= N4）**：**`command_chain`（谁向谁报告，可多属）** 与 **`Formation`（谁物理跟谁移动：严格树 + attached/detached + 相对偏移）** 分开——照 **AFSIM** 的 `command_chain` ⟷ `WsfFormation` 拆分（research §B.7）。 | 用户；sd 主 spec §〇.3 已把 **N4** 记为裁定（2026-09-20「开始吧」）——**与本条一字一致** |
| **E2** | ★ **回归路径**：拆分后**有能力回归则额外创建一条回归路径**；**大编制移动时对应更新**（终点跟着走，**不是**冻结旧格）；★ **只有两者同格才能合体**。 | 用户（brainstorm §3.1） |
| **E3** | ★ **三态**：**移动 / 休整 / 交战**，**三者对应不同移动速度**；★ 合并时，被合的小单位**只能处于「移动」状态**。 | 用户（brainstorm §3.2） |
| **E4** | ★ **战损增量**：唯一入口 `unit.SetStrength` 是**整份替换**（`UnitOperations.java:82-97`）⇒ 新增**增量/损失**语义，**人员 + 装备双轨**，且**可与时间线恢复状态对接**。 | 用户（brainstorm §3.3；N3） |

> ★ **编号说明**：本 spec 用 **`E` 系列**（Extension），**不与 M3 的 U 系列裁定、也不与 sd 的 R/N 系列混用**。`P` 系列 = 本 spec 的未拍项（§〇.2）。

### 〇.2 未拍项（待裁）

| # | 待裁 | 建议 | 理由 |
|---|---|---|---|
| **P1** | `attached`/`offset` 的**存储表示**：(A) 作为 `Unit` 的新时态字段，还是 (B) `UnitState` 的独立 `Formation` 组件？ | ★ 建议 **(A)** | `parent` 本身就是 `Unit` 上的时态序列（`Unit.java:23`）；`attached/offset` 是**同一类「本节点相对父」属性**，放一起可复用既有的「追加段」机制与逐节点构造期校验，且**不引入第二个 key 空间**（备选 B 要额外维护 `formations` 与 `units` 的键一致性）。 |
| **P2** | **`offset` 是否参与 `effectivePosition`**（attached ⇒ 父位 ⊕ 偏移），还是只作形制/显示参数？ | ★ 建议**参与** | 这是 `Formation` 的实质（form-up / station-keeping）；否则「相对偏移」只是摆设。代价：`effectivePosition`（`UnitState.java:55-72`）语义变化 ⇒ 见 §一.4 的**取代/共存**说明。 |
| **P3** | **detach 级联策略**：detach 移动整棵子树，还是只移动该节点？（AFSIM 是**刻意不对称**：attach 级联、detach 不级联） | ★ 建议照 AFSIM：**attach 级联、detach 只节点** | research §B.7 原文；「子树迁移」另有独立命令（`ReparentSubtree`），与 detach 标志是两件事。 |
| **P4** | **孤儿策略**：父被解散 / 被移除后，子怎么办？（级联销毁 / 提升为根 / 标记孤儿） | ★ 建议**提升为根**（`parent=empty`） | AFSIM `RemoveSubCommand` 即此形；★ **绝不留下悬空 `parentId`**（research §B.7 坑 11）。既有 `disband` 已要求先改编下属（`UnitOperations.java:162-176`）⇒ v1 不必新造级联销毁。 |
| **P5** | **三态的速度映射数值**（MOVING/RESTING/ENGAGED 各乘多少‰） | ★ 建议**只落机制、不落数值**（表留空 / 判据只断言「三者不同且 RESTING/ENGAGED < MOVING」） | 用户未给数值；照 sd 的纪律「只借机制形状，不引入数值真值」（sd 主 spec §十二）。 |
| **P6** | **在途时改状态**的语义 | ★ 建议 v1：**在途状态的冻结规则不动**（`Movement.speedAtDeparture` 出发时冻结，`Movement.java:12-13`、`UnitMoves.java:42`）——状态**只影响此后新下达的路线**；要停/要休整就 `CancelRoute`。 | 避免「速度翻倍让昨天已走的路突然变长」的时间反演（`Movement.java:9-10` 明文告诫）。 |
| **P7** | **「有能力回归」的判据** | ★ 建议 v1 = **位置可确定 + A\* 能到达目标当前位置 + 状态允许移动**（三者皆可用代码判） | 用户只说「有能力」未下定义；这是**环境相关**的判据，应每 tick 重算，不能存成一次性的布尔。 |
| **P8** | **回归路径的存法**：(a) 存**目标引用**（`rejoinTarget: UnitId`）每 tick 重规划；还是 (b) 每次大编制移动时**物化**一条新 `Route`？ | ★ 建议 **(a)** | research §B.7 坑 2（CMO #16284：航点不更新移动母体 ⇒ 飞机飞向错误位置并坠毁）说明**冻结 hex 序列必然过期**。存目标引用 = 天然「大编制移动时对应更新」。 |
| **P9** | **合体语义**：attach 回父即可（保留子节点），还是**消化**为一个单位（销毁子节点）？ | ★ 建议 v1 = **attach 回父**（不销毁）；「消化」另设命令 | 用户只说「合体」，未说消失；保留节点使「同格」前置与可回放都更简单。 |
| **P10** | **新增命令的命名与载荷形状**（`unit.AttachUnit` / `ReparentSubtree` / `SplitFormation` / `MergeFormation` / `PlanSparseRoute` / `SetStatus` / `ApplyCasualties` …） | ★ 建议照本文命名；**以实现期 spec 为准** | 设计形状，非用户逐条确认；`ApplyCasualties` 与 sd 主 spec §十 row ③ 的暂名一致。 |
| **P11** | **`command_chain` 是否需要「关系类型」枚举**（`ORGANIC|ASSIGNED|ATTACHED|OPCON|TACON|SUPPORTED|SUPPORTING`，research §B.7 建议），以及链**是否要可寻址**（`unit:<id>:chain.<id>`）？ | ★ 建议 v1：**不要类型枚举**（`attached` 布尔已够），**链不可寻址**；两者都列挂起 | 用户的两级描述里只提到「可多属」；枚举与地址是**额外机制**，加了要连带 codec / resolver / 判据，不在本需求内。 |

### 〇.3 写作纪律与来源标注

- 本文**每条涉及既有代码的断言都标 `文件:行`**，均来自**控制器 2026-09-20 直读当前字节**（▲）或 research §A.1 的实测（◇/▲）。
- ★ **行号以源码为准**：research §A.1 记 `Shell.java:201-208` 为 8 条 unit handler，**本会话直读实测为 `Shell.java:195-202`**（注册循环 `:203-207`）——行号有漂移，本文用**直读值**。
- **未实测的一律写「未核实」**（§九）。
- 与 sd 主 spec 的**冲突/需协调**处：§六.3 **只指出并给建议，不擅自改主 spec**。

---

## §一 编制：`command_chain` + `Formation`（E1）

### 一.1 现状（实测）

| 事实 | 位置 |
|---|---|
| 编制 = **严格单父树**：`Unit.parent` 是 `SegmentedSeries<Optional<UnitId>>` | `Unit.java:20-29`（`parent` 在 `:23`） |
| 自指在节点内即拒（`parent == 自身 id`） | `Unit.java:40-44` |
| 跨单位**成环**由 `UnitState` **构造期**按「关键时点」逐点查 | `UnitState.java:74-97` |
| ★ **无子表 / 多父 / `Army` 容器**：「下属」只能**全表反查** | `UnitOperations.disband` `:162-176`；`UnitResolver.childrenAt` `:114-125` |
| `effectivePosition`：自身无位置则**沿 `parent` 向父递归取** | `UnitState.java:55-72` |
| `ReparentUnit` **只改单点**（追加一条 `parent` 段） | `UnitOperations.reparent` `:47-64`；`ReparentUnitHandler.java:31-45` |
| 单位可绑决策人（现有 SPI 基础） | `UnitAgentAttachPolicy.java:19` |

> ★ **关键观察**：`Unit.parent` 目前**一身兼二职**——既是「编制（organic 组织）」，又是 `effectivePosition` 的物理取位来源。E1 的两级拆分必须正面回答「拆开之后，谁是谁」。

### 一.2 新增：`command_chain`（谁向谁报告，可多属）

**语义（照 AFSIM `command_chain`，research §B.7）**：一条链是**一个 commander + 一组 members 的星形关系**；**一个单位可属于多条链，但每条链至多一次**；每条链**至少一个 commander**（AFSIM 允许 `SELF`）。

```
record CommandChainId(String value)                       // 裸值 toString + static parse 三件套
record CommandChain(CommandChainId id, String name,
                    UnitId commander, Set<UnitId> members) // commander ∈ members
```

**落点**：`UnitState` 新增一个组件 `Map<CommandChainId, CommandChain> commandChains`（与 `units` 并列）。★ 保序不可变：`LinkedHashMap` + `Collections.unmodifiableMap`，**绝不用 `Map.copyOf`**（同 `UnitState.java:20-21` 的口径）。

**构造期不变量**：
1. `commander ∈ members`；
2. `commander` 与全部 `members` 都**存在于同快照的 `units`**；
3. 链 id 不重复；
4. ★ **多属是允许的**（同一 `UnitId` 出现在多条链里是正常态，这正是「可多属」的意义）；
5. 链**不构成层级**（星形）⇒ **链内无环问题**；唯一需要防环的是 `Formation` 树（`UnitState.java:74-97` 已有）。

★ **为什么不是「第二棵父子树」**：AFSIM 把 `command_chain` 做成**扁平的具名集合**（`一个平台可属多条 chain，但每条至多一次`），报告层级由**多条链叠加**表达，而不是链内嵌套。这避免在已有严格树之外再造一棵需要查环的树（research §B.7 坑 9/10：隐式/歧义根、层级无静态校验）。

### 一.3 新增：`Formation`（严格树 + attached/detached + 相对偏移）

**语义**：**谁物理上跟谁移动**。★ **严格树不新造**——**`Unit.parent` 就是这棵树**（它已经是严格单父 + 构造期查环）。`Formation` 新增的是**每个节点相对其父的两个属性**：

```
record RelativeOffset(int dq, int dr)          // 相对父的轴向偏移（hex 轴向坐标差）
```

**存储（P1，建议 (A)）**：作为 `Unit` 的两个**新字段**——
- `attached`：布尔（建议 `SegmentedSeries<Boolean>`，与 `parent` 同形，可时态化）；
- `offset`：`SegmentedSeries<Optional<RelativeOffset>>`（缺省 `empty` = 无偏移）。

**语义规则**：
- `attached = true`：该节点**跟随** `parent` 移动（成员**保持与 lead 的相对站位**，AFSIM 原话，research §B.7）。
- `attached = false`（detached）：该节点**不跟随** `parent`；它是「被派出去」的（cross-attach / 独立任务）。
- **attach 级联、detach 只节点**（P3，AFSIM 刻意的不对称）：`unit.AttachUnit` 把节点**连其子树**一起 attach；`unit.DetachUnit` **只改该节点**的 `attached`，不动子节点的 `attached`。

★ **与 `effectivePosition` 的关系见 §一.4**（这是本 spec 最需要说清的一条）。

### 一.4 ★ `attached` / `offset` 与 `effectivePosition`：取代 / 共存

**现状**：`effectivePosition` = 自身有位置 ⇒ 它；否则**向父递归取**（`UnitState.java:55-72`）。

**建议语义（P2 = 参与）**：

| 情形 | `effectivePosition` |
|---|---|
| `attached=true`，自身有位置 | 自身位置（**不变**） |
| `attached=true`，自身无位置，`offset` 非空 | **父的有效位置 ⊕ offset**（取代「纯向父取位」） |
| `attached=true`，自身无位置，`offset` 为空 | **父的有效位置**（**与今天逐字相同**） |
| `attached=false`，自身有位置 | 自身位置 |
| `attached=false`，自身无位置 | ★ **空（不回退父）**（取代「向父取位」） |

**取代（不兼容的部分）**：`detached` 且无自身位置的节点，**不再**继承父位——它「不知道在哪」。理由：detached 的物理含义就是**不在编队里**，继承父位会产出「父动子瞬移」式的假位置（research §B.7 坑 1「母体追子」、坑 6「不可能地四处跳」）。

**共存（向后兼容）**：既有存档没有 `attached`/`offset` ⇒ 取默认 `attached=true`、`offset=empty` ⇒ **`effectivePosition` 与今天逐字节相同**。这是本设计的硬约束：**默认值必须让旧行为一字不变**。

★ **连带**：`UnitOperations.planRoute` 用 `effectivePosition` 判路线起点（`:119-143`）⇒ 若 P2 采纳，detached 且无自身位置的单位**无法下达路线**（起点不可确定）——这**正是想要的**（它得先有个位置）。★ **offset 是否必须落在地图内**（`GameMap.hexes().containsKey`）**待裁**（本 spec 未定；倾向 v1 不强制，只要求是合法 `HexCoord`）。

### 一.5 新增：子树迁移与拆合

**现状**：`reparent` 一次只改一个节点（`UnitOperations.java:47-64`）；`disband` 要求先改编下属（`:162-176`）⇒ **不能整棵子树迁移**（research §A.1）。

**新增操作（均纯函数，照 `UnitOperations` 形制）**：

| 操作 | 语义 | 拒绝条件 |
|---|---|---|
| `reparentSubtree(state, rootId, newParent, at)` | 给 `rootId` **及其全部后代**在同 `at` 追加 `parent` 段 | `newParent` 落在被迁子树内 ⇒ **成环**（构造期 `UnitState.java:74-97` 会拒；建议在 op 内**先显式拒**以给可读理由） |
| `attachSubtree / detachUnit` | 改 `attached` 标志（P3 的级联策略） | 目标不存在；detach 已是根等 |
| `setOffset(state, id, offset, at)` | 改相对偏移 | 目标不存在；偏移非法 |

**「拆合」的落点**（E2 / brainstorm §3.1）：
- **拆分** = 把一个单位**从大编制的物理跟随里分离出来** ⇒ `detach`（+ 可选改根）；
- **合体** = **同格**前提下重新 `attach`（P9：v1 不销毁节点）；
- ★ **只有同格才能合体**（E2）⇒ **在 `unit.*` handler 里用代码判**（取两者的 `effectivePosition` 比较；不同格或任一不可确定 ⇒ 拒绝）。**绝不交给 AI / 玩家自律**——Command Ops 的「母体追子」bug 与条令的 linkup point 都说明这是引擎该强制的（research §B.7 坑 1）。

### 一.6 不变量（构造期 / 命令期，逐条配故意违规用例）

1. `commandChains` 的 commander/members 必须存在于 `units`（引用完整性）；
2. `commandChains` 的 commander ∈ members；
3. `Formation` 树**无环**（复用 `UnitState.java:74-97`；`reparentSubtree` 不得改变这一点）；
4. `attached`/`offset` 默认值使**旧存档行为不变**（`true` / `empty`）；
5. `detached` 且无自身位置 ⇒ `effectivePosition` 为空（**不得**回退父）；
6. 合体命令的两单位 `effectivePosition` **必须相等且 present**；
7. `LinkedHashMap`/`LinkedHashSet` + `unmodifiable*` 冻在赋值处，**不得 `Map.copyOf`/`Set.copyOf`**（`FieldDelta` 类注释、M2 Task 5 实测）。

### 一.7 判据思路

| 判据 | 变异体（去掉被保护的行 ⇒ 应红） |
|---|---|
| 同一 unit ∈ **2 条** chain，往返后仍在（多属） | 把链做成单属（键改成 `UnitId`） ⇒ 第二条链写不进 ⇒ 红 |
| `reparentSubtree` 把子树整体迁移后，**每个后代**的父都对 | 只改 root、不改后代 ⇒ 后代父不变 ⇒ 红 |
| 成环拒绝：把 `newParent` 设成自己的后代 ⇒ 拒绝、状态不变 | 删环校验 ⇒ 通过 ⇒ 红 |
| `attached` 子无位置 ⇒ `effectivePosition` = 父位 ⊕ offset | 删掉 offset 加项 ⇒ 等于父位 ⇒ 红（offset 非零夹具） |
| `detached` 子无位置 ⇒ 空 | 删 detached 分支 ⇒ 回退父位 ⇒ 红 |
| 不同格合体 ⇒ 拒；同格 ⇒ 过 | 删同格校验 ⇒ 不同格也通过 ⇒ 红 |

---

## §二 回归路径（E2）

### 二.1 现状（实测）

| 事实 | 位置 |
|---|---|
| `Route(waypoints, path)` **类型支持稀疏**：`waypoints` 是 `path` 的**子序列**，`path` 必须逐格相邻、无重复 | `Route.java:8`、`:26-34`、`:35-39`、`:40-42` |
| ★ `PlanRouteHandler` **写死 `new Route(waypoints, waypoints)`** ⇒ 载荷必须**逐格相邻** | `PlanRouteHandler.java:44`（载荷读法 `:43`，`at` 取 `:45`） |
| A\\* **已存在**且决定论（全序平局项） | `PathFinder.findPath` `PathFinder.java:59-107`；平局全序 `:40-44` |
| A\\* **已有生产调用者**（只读端点） | `GuiServer.java:450`（`PathFinder.findPath`）；端点 `:299` / 文档 `:427` |
| `planRoute` 要求路线起点 == `effectivePosition` | `UnitOperations.java:119-143` |
| 成本实现：地形 → 机动性（v1） | `TerrainMovementCost.java:16-35`、`:82-84` |

> ★ **缺口确认**：**稀疏路点载荷通道不存在**——类型支持稀疏，但 handler 把 `path` 钉死为 `waypoints`（research §A.1 缺口④ / brainstorm §7④）。

### 二.2 新增

**（1）稀疏路点载荷通道**（P10：命名待裁，建议 `unit.PlanSparseRoute`）：
- 载荷：`id, waypoints[{q,r}…]`（与既有 `unit.PlanRoute` 同形，但**允许非相邻**，`UnitPayloads.requireWaypoints` `:133-146` 可复用）；
- handler：读 `GameMap`（`state.module("map")`，读法与 `UnitTimeParticipant.mapOf` `:146-154` 同制），对每对相邻 waypoint 调 `PathFinder.findPath(map, from, to, unit, TerrainMovementCost.INSTANCE)`（`PathFinder.java:59`、`TerrainMovementCost.java:18`）**逐段展开**，拼成完整相邻 `path`，再 `new Route(waypoints, expandedPath)`；
- 任一相邻段不可达 ⇒ **命令期拒绝**（建议；「给一条走不通的路」是坏命令而非运行时状况，标待裁）；
- 起点仍须 == `effectivePosition`（`UnitOperations.planRoute` `:128-130` 的既有校验不动）。

★ **为什么另开命令而非改 `unit.PlanRoute`**：既有 `unit.PlanRoute` 的「载荷必须逐格相邻」契约已被 M7b/M7c 的 WebUI 与 e2e 引用（`GuiServer.java:299` 那个只读路径端点与 `unit.PlanRoute` 是两条）；`CommandRegistry` **无可变 `register()`**（`CommandRegistry.java:36-56`）但**新增一个 handler 零成本**，另开命令的回归面为零。备选：给 `unit.PlanRoute` 加**可选** `path` 字段（缺省 = 现行为）。

**（2）回归路径（rejoin track）**：

**语义（E2）**：拆分后**有能力回归**的单位，额外创建一条**回归路径**；★ **大编制移动时对应更新**。

**建议设计（P8 = (a) 存目标引用 + 每 tick 重规划）**：
- 在**被拆出的单位**上记「回归意图」：`rejoinTarget: UnitId`（= 原大编制的根/父）；
- **不**存冻结的 hex 序列——终点 = 目标单位**当前**的 `effectivePosition`，每 tick（或每次推进时）重规划；
- 落点：`unit.*` 里的一条命令（如 `unit.SetRejoinTarget`，P10）设置/清除；物化重规划放 **`UnitTimeParticipant`**（`unit` namespace 唯一的 participant，`UnitTimeParticipant.java:69`、`namespace() :80-82`）——它已能读 map（`:146-154`）且产出 `unit` 变更集，A\\* 是 unit 模块自己的类（`PathFinder.java`），**越界为零**。

**「有能力回归」的判据（P7，待裁，建议 v1）**：位置可确定 + A\\* 到目标当前 `effectivePosition` **可达** + 状态允许移动。⇒ 该判据**每 tick 重算**，**不存成布尔**（环境会变：目标移动、地图改地形）。

### 二.3 不变量

1. 稀疏路点展开后的 `path` 必须满足 `Route` 全部构造期约束（子序列、相邻、无重复，`Route.java:26-42`）；
2. `rejoinTarget` 必须存在于 `units`；
3. ★ **回归路径不得冻结在旧 hex**：任何实现都不得把「终点的绝对 hex」当作持久事实（P8 的直接后果）；
4. 起点规则沿用 `planRoute`（`UnitOperations.java:128-130`）；
5. 同格合体前置（§一.5）**在命令期**判，不依赖回归路径。

### 二.4 判据思路

| 判据 | 变异体 |
|---|---|
| 稀疏路点：`waypoints` 非相邻也能展开成合法 `path`（逐格） | 去掉 A\\* 展开（回到 `new Route(w,w)`） ⇒ `Route` 构造期拒绝 ⇒ 红 |
| ★ **大编制移动后**回归路径终点**随动**（不指向旧格） | 把目标存成冻结 hex ⇒ 父动后仍指旧格 ⇒ 红（**这是 research §B.7 坑 2 的直接守卫**） |
| 「有能力回归」为假时不建回归路径 | 恒建 ⇒ 断言「不可达时无路径」红 |
| 同格才可合体（§一.7 同条） | 删同格校验 ⇒ 红 |

---

## §三 三态：移动 / 休整 / 交战（E3）

### 三.1 现状（实测）

| 事实 | 位置 |
|---|---|
| `Unit` **无 status / mode 字段** | `Unit.java:20-29` |
| `MovementStatus(IN_TRANSIT/ARRIVED/NEED_REPLAN)` **只是路线进度**（走没走完 / 地图变了），由 `UnitMoves.evaluate` **现算、不落库** | `MovementStatus.java:4-10`；`UnitMoves.java:31-74` |
| 速度/机动性在 `Unit` 上；**出发时冻结**进 `Movement` | `Unit.java:27-28`；`Movement.java:12-13` |
| 移动预算 = `speedAtDeparture × 1000 × (at.tick − departedAt.tick)`（毫 MP） | `UnitMoves.java:42` |
| 成本公式 = 地形 `moveCost × 1000` 按 `mobilityPerMille` 缩放 | `TerrainMovementCost.java:57-62`、`:82-84` |
| 单位创建时无 status 概念 | `CreateUnitHandler.java:54-66` |

> ★ **关键区分（本 spec 必须反复强调）**：`MovementStatus`（路线进度，**派生量**）≠ `UnitStatus`（作战状态，**持久状态**）。两者**正交**：一个 `MOVING` 单位带 `Movement` 时，`UnitMoves.evaluate` 仍可能给出 `IN_TRANSIT/ARRIVED/NEED_REPLAN`。★ **不要**把 `UnitStatus` 塞进 `MovementStatus`，也不要复用后者。

### 三.2 新增

```
enum UnitStatus { MOVING, RESTING, ENGAGED }        // 移动 / 休整 / 交战
```

**存储**：`Unit` 新增字段 `UnitStatus status`（**普通字段**，与 `member`/`speed`/`movement` 同族，非时态）——历史由 revision 承载（同 `member` 的处理），无需 `SegmentedSeries`。★ 备选：`SegmentedSeries<UnitStatus>`（可时态查询）；v1 取普通字段，**标待裁**。

**三态 → 速度（E3，只落机制、不落数值，P5）**：
- 概念式：`effectiveSpeed(status) = speed × statusFactor(status) / 1000`（‰ 定点，与项目既有 ‰ 口径一致，`TerrainMovementCost.scale` `:82-84`）；
- 建议 `statusFactor(MOVING) = 1000`（基线），`RESTING`、`ENGAGED` **< 1000**；
- **数值留空**（P5）；判据只断言「三者不同且 RESTING/ENGAGED < MOVING」；
- **施加时点**：在 `planRoute` 时把 `effectiveSpeed(当前 status)` 冻进 `Movement.speedAtDeparture`（沿用 `Movement.java:12-13` 的冻结规则，`UnitOperations.planRoute` `:142` 构造 `Movement`）⇒ ★ **在途状态变化不回溯**（P6）。

**合并前置（E3 后半）**：★ 合并时被合的小单位**只能处于「移动」状态** ⇒ `unit.MergeFormation`（或 attach）在 handler 里判 `status == MOVING`，否则拒绝。与「同格」是**两个独立的拒绝条件**（sd 主 spec §八.2 D5 的 `FormationIntent` 前置即此）。

**创建默认值**：`unit.CreateUnit` 新增 status（建议默认 `MOVING`，或作为可选载荷字段；P10/§五）。

### 三.3 不变量

1. `status` 是持久状态、`MovementStatus` 是派生量，**两者类型不混、字段不合**；
2. `planRoute` 冻结的 `speedAtDeparture` = `effectiveSpeed(status at 出发)`；**在途改状态不改变已冻结值**（`Movement.java:9-10` 的「时间反演」禁令）；
3. 合并前置：`status == MOVING` **且** `effectivePosition` 相等（§一.5）；
4. `statusFactor` 三值互不相等且 `RESTING/ENGAGED < MOVING`（P5，数值待裁）。

### 三.4 判据思路

| 判据 | 变异体 |
|---|---|
| 三态对应**不同**出发速度：同一单位同一路线，MOVING/RESTING/ENGAGED 三次 `speedAtDeparture` 可区分且有序 | 删 factor ⇒ 三次相同 ⇒ 红（**注意夹具要让三档速度真的走到不同结果**，M1 的形态 3：输入要落在两种实现会分叉处） |
| `MovementStatus` 仍由 `UnitMoves.evaluate` 产出，与 `UnitStatus` 正交 | 把 `UnitStatus` 接到 `MovementStatus` 上 ⇒ 断言红 |
| 在途改状态不回溯：改状态后已走路程不变 | 让状态实时改写 `speedAtDeparture` ⇒ 已走路程变 ⇒ 红 |
| 合并前置：非 MOVING 合体 ⇒ 拒 | 删 status 校验 ⇒ 通过 ⇒ 红 |

---

## §四 战损增量（E4 / N3）

### 四.1 现状（实测）

| 事实 | 位置 |
|---|---|
| 唯一入口 `unit.SetStrength` 是**整份替换**（member + equipment 全量） | `UnitOperations.java:82-97`；`SetStrengthHandler.java:28-43`（载荷 `member` + `equipment` 全表） |
| 全仓**无 combat / casualty** | research §A.1（控制器复核一致） |
| ★ `FieldDelta.Upsert` **只存「key → 新值」**（不存旧值）⇒ revision 里是**战损后的绝对值**，**不是损失量** | `FieldDelta.java:77-78` |
| ★ 事件只存 `payloadDigest`（sha256 摘要），**命令明文载荷不进事件日志** | `CommandBus.java:365-378`（`:377`）；`EventRow` 出参 `:387-389` |
| 变更集**唯一**生产路径 = `UnitChangeSet.between(base, target)` | `UnitChangeSet.java:25`；`UnitOperations` 类注释 `:22-23` |

> ★ **两条对设计有决定性的实测**：(a) revision 记的是**绝对值** ⇒ 「回退到战损前 revision」即恢复战前强度（**时间线恢复成立**）；(b) 事件记的是**摘要不是明文** ⇒ **不得**声称「损失量可从 unit 事件日志读出」。损失量要么取 sd 的 `LossRecord`（sd 主 spec §三.4），要么**对相邻两个 revision 求差**。

### 四.2 新增

**命令**（P10：命名待裁，建议 `unit.ApplyCasualties`；与 sd 主 spec §十 row ③ 暂名一致）：

```
unit.ApplyCasualties: { id, personnel: int(≤0), equipment: {键: int(≤0)} }
```

**操作**（`UnitOperations` 新增纯函数）：
`applyCasualties(state, id, personnelDelta, equipmentDeltas)` —
- **双轨**：`member` + `equipment`（E4）；
- **delta 语义**：只接受 **≤ 0** 的增量；结果 = 当前值 + Δ；
- **上界校验（代码侧，§八.4 的「绝不交给 AI」）**：`|Δ| ≤ 当前值`（member 与每个装备键逐项）；越界 ⇒ 拒绝；
- **未知装备键**：建议**拒绝**（没收一个没有的装备是坏命令），**标待裁**（备选：视作 0 忽略）；
- 产出新 `UnitState`；handler 照既有形制返回 `UnitChangeSet.between(base, next)`（**不另造增量路径**，`UnitOperations.java:22-23` 的纪律）。

**`lossClass`（PERMANENT / RECOVERABLE）的归属**：sd 的 `CasualtyDelta` 带 `lossClass`（sd 主 spec §三.4）；★ **建议 v1 由 sd 的 `LossRecord` 持有，unit 命令只收数值 delta**（unit 不新造损失台账）。★ 若将来要 unit 侧留损（供直接查/AAR），再加一个损失台账组件——**列挂起**（P-未定，见 §七）。

### 四.3 与时间线恢复的对接

```
sd.RecordCasualties（sd 数据：LossRecord，含 delta/lossClass）
   → app 的 SdCommandDrain（sd 主 spec §五.3）
      → unit.ApplyCasualties 信封（数值 delta）
         → UnitOperations.applyCasualties（上界校验 + 绝对值落库）
            → revision 的 UnitChangeSet（FieldDelta.Upsert = 新值，FieldDelta.java:77-78）
```

- ★ **恢复成立**：分叉/回放到**战损前** revision ⇒ member/equipment 是战前值；到**战损后** revision ⇒ 战后值（绝对值在 revision 里）。
- ★ **损失量**：unit 侧读不到明文 delta（`CommandBus.java:377`）⇒ AAR 的「损失多少」以 **sd 的 `LossRecord` 为准**，或**相邻 revision 求差**。
- `RECOVERABLE` 回池速率：**不做**（sd 主 spec §〇.3）。

### 四.4 不变量

1. 双轨：`personnelDelta ≤ 0` 且每个 `equipmentDelta ≤ 0`；
2. 上界：`|Δ| ≤ 当前值`（逐项），违反 ⇒ 命令拒绝、`revisions` 行数不变；
3. 结果非负：`member ≥ 0`、装备值 `≥ 0`（`Unit.java:45-47`、`:87-89` 的构造期规则继续把守）；
4. 绝对值落 revision（`FieldDelta.Upsert` 语义），**不得**在事件里塞明文 delta（`received` 只带摘要，`CommandBus.java:377`）；
5. 变更集仍**只**由 `between` 生产（`UnitChangeSet.java:25`）。

### 四.5 判据思路

| 判据 | 变异体 |
|---|---|
| `base.member=100, Δ=-30 ⇒ new.member=70`（**不是 30、不是覆写**） | 用 `SetStrength` 风格（把 Δ 当绝对值） ⇒ 结果 30 ⇒ 红 |
| 上界：`Δ=-101`（当前 100） ⇒ 拒绝 | 删上界校验 ⇒ 通过（member 变 -1 或翻正） ⇒ 红 |
| 装备双轨：每键独立扣减，未提及的键**不变** | 用整表替换 ⇒ 未提及键丢失 ⇒ 红 |
| 时间线恢复：战损后回退前一 revision ⇒ member/equipment == 战前值 | 让战损覆写历史 / 不走 revision ⇒ 回退不恢复 ⇒ 红 |
| 事件无线格式泄漏：`received` 事件载荷**不含**明文 delta（只 digest） | 把 delta 塞进事件载荷 ⇒ 断言「事件载荷无 delta 明文」红 |

---

## §五 命令与 SPI

### 五.1 handler 放置与装配（现状实测）

| 事实 | 位置 |
|---|---|
| `unit.*` handler 全部住 `simos-unit/.../spi/`（现有 8 个：Rename/Create/Reparent/SetStrength/PlaceAt/PlanRoute/CancelRoute/Disband） | `simos-unit/src/main/java/io/mosire/simos/unit/spi/` |
| 8 条命令注册在 `Shell` 的 handlers 列表 | ★ 直读：`Shell.java:195-202`（列表起点 `:187`） |
| 注册循环同时收 `commandTypes` | `Shell.java:203-207` |
| 唯一的 unit `TimeParticipant` 注册 | `Shell.java:209` |
| resolvers 注册（Map/Social/Unit） | `Shell.java:211-214` |
| `CommandRegistry` **构造期收全量、无可变 `register()`** | `CommandRegistry.java:36-56`（类注释 `:14-17`） |
| `type()` 必须 `<namespace>.<Command>`，**构造期**校验 | `CommandRegistry.java:75-84` |
| 载荷解析助手 `UnitPayloads`（**包私有**） | `UnitPayloads.java:29`；`requireWaypoints` `:133-146`、`requireEquipment` `:113-130`、`requireInt` `:82-88` |
| 时刻取 `state.meta().timestamp()` | `ReparentUnitHandler.java:39`、`PlanRouteHandler.java:45` |
| 命令是**不透明载荷**（ADR-1 §七） | `CommandBus.java:34`、`:214-218` |

### 五.2 新命令清单（P10：命名待裁）

| type（建议） | 载荷要点 | op | 拒绝条件 | 节 |
|---|---|---|---|---|
| `unit.AttachUnit` | `id, parent` | attach（+ 可选同格） | 不存在；已是父；环 | §一.3 |
| `unit.DetachUnit` | `id` | detach（只节点，P3） | 不存在；已是根 | §一.3 |
| `unit.ReparentSubtree` | `rootId, parent?` | reparentSubtree | 新父落在子树内（环）；不存在 | §一.5 |
| `unit.SetFormationOffset` | `id, dq, dr`（或 null 清） | setOffset | 不存在；非法偏移 | §一.3 |
| `unit.CreateCommandChain` | `chainId, name, commander, members[]` | createChain | 重 id；成员不存在；commander∉members | §一.2 |
| `unit.UpdateCommandChain` | `chainId, name?, commander?, members?` | updateChain | 不存在；成员不存在；commander∉members | §一.2 |
| `unit.SplitFormation` | `rootId, subUnitIds[]`（或单 id） | detach 指定 | 目标不在 root 子树；不存在 | §一.5 |
| `unit.MergeFormation` | `childId, parentId` | attach（**同格 + MOVING**） | 不同格 / 状态非 MOVING / 环 | §一.5、§三.2 |
| `unit.PlanSparseRoute` | `id, waypoints[]` | A\\* 展开 + planRoute | 段不可达（建议）；起点不符 | §二.2 |
| `unit.SetRejoinTarget` | `id, target`（或 null 清） | 设/清回归目标 | 目标不存在；自指 | §二.2 |
| `unit.SetStatus` | `id, status` | 改状态 | 未知 status；合并场景前置由合并命令判 | §三 |
| `unit.ApplyCasualties` | `id, personnel, equipment{}` | applyCasualties | **上界**；未知装备键（建议拒） | §四 |

★ **`unit.CreateUnit` 的连带**：新增字段（`status` / `attached` / `offset`）要在创建时给默认段（现构造锚点段在 `CreateUnitHandler.java:54-66`）；`command_chain` 为空表。

### 五.3 连带的 SPI / 快照 / codec

1. **`UnitState` 新增组件 ⇒ `UnitChangeSet` 必须新增同名组件**：`UnitRoundTripTest.everyUnitStateComponentParticipatesInTheChangeSet`（`:52-66`）反射枚举状态组件、`changeSetHasExactlyOneComponent`（`:74-82`）要求两边组件集相同，`mutate`/`changedOf` 的 `switch`（`:94-106`）要登记新组件名。★ **不登记 ⇒ 该测试自动红**（这正是铁律 5 的护栏在工作）。
2. **`ArchitectureGuardsTest`**：`changeSetHasExactlyFourMainSourceImplementors`（`:24-45`）钉「全仓恰 4 个 main `ChangeSet` 实现者」。本次只在 `UnitChangeSet` 里**加组件**（仍是同一个类）⇒ **计数仍是 4，无需改**；★ 若有人另造一个 unit 变更集类，此条会红。
3. **`UnitCodec`**：现在只注册 `UnitId` 一个键反序列化器（`UnitCodec.java:52-55`）。★ 若 `CommandChainId` 作 `Map` 键，**必须照裁定 16 在本模块注册键反序列化器**，否则解码期抛。`UnitChangeSet.isEmpty()` 的 mixin（`:40-50`）已处理，新增组件不改变它。
4. **恰一个 participant**：`unit` namespace 的 tick 行为（回归重规划、状态对物化的影响）**只能进 `UnitTimeParticipant`**（`UnitTimeParticipant.java:69`；`namespace() :80-82`）——`TimeAdvance` 的 `putIfAbsent` 对同 namespace **重复即抛**（research §C③.4）。它读 `state.module("map")`（`:146-154`）、产出 `unit` 变更集、声明 `reads/writes`（`:100-118`）。
5. **resolver**：canonical 仍是 `unit:<id>`（`UnitResolver.java:25`、`:154-159`）。`command_chain` 是否要子实体地址（P11 建议**不要**）若将来要做，须同时改 resolver + `UnitPayloads`/地址 AST——**列挂起**。
6. **AgentAttachPolicy**：`UnitAgentAttachPolicy` 对**存在的 `Unit`** 可绑（`:19`）。新增实体（链/编队覆盖层）**不自动**可绑；若要，另加策略——**列挂起**。

### 五.4 判据思路

| 判据 | 变异体 |
|---|---|
| 每个新 handler 都能在 `CommandRegistry` 里按 type 找到；`catalog` 含全部新 type | 删一个 handler 的注册 ⇒ catalog 少一个 ⇒ 红 |
| `type` 形状 `<namespace>.<Command>`（构造期） | 写 `unitAttach`（无点） ⇒ 构造期抛 ⇒ 红 |
| `unit` namespace 恰一个 participant | 注册第二个 unit participant ⇒ `putIfAbsent` 抛 ⇒ 红 |
| `CommandChainId` 作 Map 键能往返 | 不注册键反序列化器 ⇒ 解码抛 ⇒ 红 |
| 新状态组件全进变更集 | 只在 `UnitState` 加组件、不进 `UnitChangeSet` ⇒ `UnitRoundTripTest` 红 |

---

## §六 与 `sd` 的交界（铁律 3 / 4）

### 六.1 边界（谁拥有什么）

| 数据 | 归属 | 依据 |
|---|---|---|
| 编制（`command_chain` / `Formation` 树 / attach / offset / 子树迁移） | **`simos-unit`** | 本 spec §一；sd 主 spec §十 row ①⑤ |
| 三态（移动/休整/交战）与速度映射 | **`simos-unit`** | 本 spec §三；sd 主 spec §十 row ② |
| 战损命令 + 单位人数/装备的**绝对值** | **`simos-unit`** | 本 spec §四 |
| 交战（场·阶段·结局）、战损**记录**（`LossRecord` / `lossClass`）、决策、国家 | **`simos-sd`** | sd 主 spec §一.1、§三.4 |
| 国家（= 带 tag 的 `Region`）与军队（unit）的**关联** | **`sd` / app** | unit 与 social 编译期互不可见；unit 也不认识 map 的 Region（research §A.1 编译期边界） |

★ **`sd` 不得直接改 Unit 数据**（铁律 3）：sd 的 participant **只能写 `sd`**（`TimeProposal` 单一 namespace，research §C③.3）⇒ 战损落账、编制改动**一律经 `unit.*` 命令**。

### 六.2 谁发 · 什么时机 · 同一事务

| 动作 | 谁发 | 时机 | 事务 |
|---|---|---|---|
| 战损落账 | `sd.RecordCasualties`（写 sd 的 `LossRecord`） | tick 内（判决后） | sd 自己的 revision |
| 战损**生效**到单位 | app 的 `SdCommandDrain` 发 `unit.ApplyCasualties` 信封 | **`AdvanceTime` 提交成功之后** | **另一个 revision**（跨模块） |
| 编制拆合 | sd 的 D5 `FormationIntent`（意图，sd 数据） | 决策时 | sd revision |
| 编制**执行** | app 的 `SdCommandDrain` 发 `unit.SplitFormation`/`MergeFormation`/`AttachUnit`/`DetachUnit` | 同上 | **另一个 revision** |

★ **同一事务做不到（必须如实记）**：sd 主 spec §五.3 已裁定跨模块效果 = **pending-command 队列 + app drain**，并自陈代价「**提交后再提交、跨多个 revision**；中途失败会有『sd 已记 effect、unit 未改』的中间态」。本 spec 沿用：v1 以**幂等 + 可重放**补偿，**跨 revision 原子性列挂起**（sd 主 spec §十二）。

★ **合法性在 unit 侧用代码判**（sd 主 spec §八.4 的「绝不交给 AI」）：
- 同格才能合体（§一.5）；
- 合并单位状态必须 MOVING（§三.2）；
- 战损上界 `|Δ| ≤ 当前值`（§四.2）；
- 环（子树迁移）、成员存在性（链）。

### 六.3 与主 spec §十 的协调（★ 只指出，不擅自改主 spec）

1. **N4 的状态（已核当前字节）**：sd 主 spec **§〇.3 已把 N4 记为裁定**（2026-09-20「开始吧」采纳建议），§十 表格 row ① 亦写「**N4 = 两层**」。措辞与本 spec 的 **E1 / §一** **一字一致** ⇒ **无冲突，无需协调**。本 spec 的 §一 是该裁定的 **unit 侧落点细化**（加上了对 `effectivePosition` 的取代/共存说明——那是 sd 主 spec 未展开的部分）。
2. **`unit.ApplyCasualties` 命名**：sd 主 spec §十 row ③ 写「暂名 `unit.ApplyCasualties`，以 unit-extension spec 为准」⇒ 本 spec **确认该名**（P10 待裁）。
3. **D5 前置**：sd 主 spec §十 末行「合法性（同位置、状态=移动）由 unit 的命令判」⇒ 本 spec §一.5 / §三.2 落地，**一致**。
4. **战损双轨字段对齐**：sd 的 `CasualtyDelta(UnitId unit, int personnel, Map<String,Integer> equipment, LossClass lossClass)`（sd 主 spec §三.4）与 unit 的 `ApplyCasualties` 载荷（`personnel` + `equipment`）**建议逐字段对齐**（`lossClass` 留 sd）。**待实现期协调**。
5. **`Army.rootUnit`**：sd 主 spec §三.2 的 `Army(..., UnitId rootUnit, ...)` 需要一个「根」的 unit 定义 ⇒ 本 spec 的「根」= `parent` 为空（与 `UnitResolver.resolveChain` 的 `parent().valueAt(at).isEmpty()` 判根一致，`UnitResolver.java:83-89`）。

---

## §七 挂起 / 不做（带裁定）

| 项 | 处置 | 依据 |
|---|---|---|
| **数据链统计与自定义** | ★ 用户原话「**这个不用多说**」⇒ **只列提纲、不实现**：统计维度（per-unit / per-formation / per-nation 的人员·装备·损失累计）、自定义口径（外部注入 / 脚本？）、与 AAR 的接口；**v1 不做** | brainstorm §3.4 |
| **撤销 / 重做** | **不做**；要撤销就**回退到前一节点（分叉）** | 同 M8 Q1 / sd 主 spec §十二 |
| **`RECOVERABLE` 回池速率** | **不做**（v1 只记类别，且在 sd 侧） | sd 主 spec §〇.3 |
| **unit 侧损失台账** | **不做**（损失量以 sd 的 `LossRecord` 为准，或对相邻 revision 求差） | 本 spec §四.3 |
| **LOD / 聚合单位（DIS/HLA 式坍缩-展开）** | **不做** | research §B.7 坑 6 |
| **`offset` 是否参与 `effectivePosition`** | **待裁**（P2，建议参与） | 本 spec §一.4 |
| **detach 级联策略** | **待裁**（P3，建议 attach 级联、detach 只节点） | 本 spec §一.3 |
| **孤儿策略** | **待裁**（P4，建议提升为根；绝不悬空 `parentId`） | 本 spec §一.3 |
| **三态速度数值** | **待裁**（P5，只落机制不落数值） | 本 spec §三.2 |
| **在途改状态语义** | **待裁**（P6，建议 v1 不回溯、要停先 `CancelRoute`） | 本 spec §三.2 |
| **「有能力回归」判据** | **待裁**（P7，建议每 tick 重算） | 本 spec §二.2 |
| **合体是否消化子节点** | **待裁**（P9，建议 v1 只 attach） | 本 spec §一.5 |
| **`command_chain` 关系类型枚举 / 链可寻址** | **待裁**（P11，建议 v1 都不要，列挂起） | 本 spec §一.2 |
| **空 / 海 / 特殊编队、装备词表、链绑决策人** | **不做** | 本 spec §五.3 |
| **跨 revision 的 drain 原子性** | **挂起**（v1 幂等 + 可重放补偿） | sd 主 spec §五.3 / §十二 |

---

## §八 判据总表（可实测 + 变异思路）

> 纪律：**每条护栏都要有一个故意违规用例证明它真的会响**（CLAUDE.md）；变异轮**开跑前先让变异体自证 md5**、**读 surefire 必须跑干净轮并核对报告 mtime**。

| # | 判据（实测项） | 变异体思路（去掉被保护的行 ⇒ 用例应红） |
|---|---|---|
| 1 | **多属**：同一 unit 在 2 条 `command_chain` ⇒ 两条都在、往返一致 | 链做成单属（键改 `UnitId`） ⇒ 第二条丢失 ⇒ 红 |
| 2 | **Formation 无环**：`ReparentSubtree` 把新父设为自己的后代 ⇒ 拒 | 删环校验（`UnitState` 构造期） ⇒ 通过 ⇒ 红 |
| 3 | **attached 继承 ⊕ offset**：attached 子无位置 ⇒ 父位 + 偏移 | 删 offset 加项（非零偏移夹具） ⇒ 等于父位 ⇒ 红 |
| 4 | **detached 不回退**：detached 子无位置 ⇒ 空 | 删 detached 分支 ⇒ 回退父位 ⇒ 红 |
| 5 | **同格合体**：不同格 ⇒ 拒；同格 ⇒ 过 | 删同格校验 ⇒ 通过 ⇒ 红 |
| 6 | **子树迁移整体性**：迁移后**每个后代**的父都更新 | 只改 root ⇒ 后代父不变 ⇒ 红 |
| 7 | **稀疏路点**：非相邻 waypoints 展开成合法逐格 `path` | 回到 `new Route(w,w)` ⇒ `Route` 构造期拒 ⇒ 红 |
| 8 | ★ **回归路径随动**：大编制移动后终点不指旧格 | 目标存冻结 hex ⇒ 父动后仍指旧格 ⇒ 红 |
| 9 | **三态速度**：MOVING/RESTING/ENGAGED 的出发速度可区分且有序 | 删 `statusFactor` ⇒ 三者相同 ⇒ 红 |
| 10 | **三态正交**：`UnitStatus` 与 `MovementStatus` 不混 | 把 `UnitStatus` 接进 `MovementStatus` ⇒ 断言红 |
| 11 | **在途不回溯**：改状态后已走路程不变 | 让状态实时改写冻结速度 ⇒ 已走路程变 ⇒ 红 |
| 12 | **战损 delta**：`100 + (−30) = 70`（非 30、非覆写） | 把 Δ 当绝对值 ⇒ 结果 30 ⇒ 红 |
| 13 | **战损上界**：`Δ=-101`（当前 100）⇒ 拒 | 删上界 ⇒ 通过/负值 ⇒ 红 |
| 14 | **装备双轨**：只扣提及的键，其余不变 | 整表替换 ⇒ 未提及键丢 ⇒ 红 |
| 15 | **时间线恢复**：回退战损前 revision ⇒ 战前值 | 覆写历史 / 不走 revision ⇒ 不恢复 ⇒ 红 |
| 16 | **事件无明文泄漏**：`received` 载荷只含 digest，不含 delta 明文 | 把 delta 塞进事件载荷 ⇒ 红 |
| 17 | **往返**：新状态组件全进 `UnitChangeSet`；`UnitRoundTripTest` 绿；`ArchitectureGuardsTest` 计数仍 4 | 状态加组件、变更集不加 ⇒ 自动红 |
| 18 | **命令注册与形状**：新 handler 全部在册、`type` 形状合法、`unit` namespace 恰一个 participant | 删注册 / 写无点的 type / 注册第二个 participant ⇒ 各自红 |
| 19 | **codec**：`CommandChainId` 作 Map 键往返 | 不注册键反序列化器 ⇒ 解码抛 ⇒ 红 |
| 20 | **sd 边界**：sd 侧无直接写 unit；经 drain | 删 drain / sd participant 直接写 unit ⇒ 单位不变或越界 ⇒ 红 |

★ **门禁**：`./mvnw clean verify` rc=0（本任务**不跑 Maven**，门禁在实现期跑）；变异轮**全杀**。

---

## §九 本文未核实项与已做的假设（诚实清单）

**未核实**：

- **行号漂移**：research §A.1 记 `Shell.java:201-208`（8 条 unit handler），本会话直读为 **`Shell.java:195-202`**；本文用直读值。research §A.2/§A.3 的多数行号仍来自 explore transcript（◇），**本会话未逐行复核**（如 `TimeAdvance.java:124-127` 的 `putIfAbsent`、`FieldDelta`/`CommandBus` 的若干行属本会话直读，其余以源码为准）。
- **`PathFinder` 在 participant 里的代价**：回归重规划每 tick 对每个在途回归单位跑 A\\*，**在 19441 格真图上的性能未测**（M9 已证 overview/传输是瓶颈，但 A\\* 在推进支的代价**本会话未测**）。
- **新类型的 Jackson 往返**：`CommandChain` / `RelativeOffset` / `UnitStatus` 是**新 record/enum**，假定按既有 `SegmentedSeries`/`Unit` 同制可往返；**未运行 codec 往返实测**（本任务不跑 Maven）。`CommandChainId` 作 Map 键需要键反序列化器（§五.3），**未写、未验**。
- **`SegmentedSeries<Boolean>` 是否可序列化**：`parent`/`position` 是 `SegmentedSeries<Optional<…>>`，布尔序列**未验**；若不可用，退化为普通布尔字段（本 spec 未定）。
- **`effectivePosition` 加 offset 后对既有用例的冲击面**：假定「offset 为空 ⇒ 与今天逐字相同」⇒ 既有测试全绿；**未实证**。
- **`unit.CreateUnit` 加默认段对旧档的影响**：假定默认值使旧档行为不变；**未实证**。
- **sd 主 spec 的 `CasualtyDelta` 与 unit 载荷的字段级对齐**：**未与 sd 实现核对**（sd 尚未实现）。

**我替用户做的假设（凡此均应视为可推翻）**：

1. **`attached`/`offset` 的存储表示取 (A)**（`Unit` 新字段，P1）——§〇.2 建议项；
2. **`offset` 参与 `effectivePosition`**（P2）——本 spec 提出的语义；
3. **detach 级联照 AFSIM（attach 级联、detach 只节点）**（P3）；
4. **孤儿提升为根**（P4）；
5. **三态速度只落机制、数值留空**（P5）；在途改状态不回溯（P6）；
6. **回归路径存「目标引用 + 每 tick 重规划」**（P8 (a)）；
7. **合体 v1 只 attach、不消化子节点**（P9）；
8. **新命令的 type 名与载荷字段名**（P10）——设计形状，实现期可微调；
9. **`command_chain` 用扁平星形、无关系类型枚举、链不可寻址**（P11 建议）；
10. **`unit.CreateUnit` 的 status 默认值**（建议 `MOVING`）。

★ **本 spec 的定位**：它是 `unit.*` 扩容的**设计**；**不含 bite-sized 步骤**；实现顺序与任务分解归实现期计划。**与 sd 主 spec 的协调点已列 §六.3，本 spec 不改主 spec。**
