# SDD ledger — plan: docs/superpowers/plans/2026-09-16-map-simos-plan.md

> **本文件是 M2（MapSimos）的台账与恢复地图。**
> M1 的台账在 `.superpowers/sdd/2026-09-16-util-simos-plan/progress.md`，**收尾时会被删除** ——
> 故 M2 的材料**一律放本目录**，不要写回去。
> 本目录另有四份只读取证交付件 `m2-recon-{A,B,C,D}-*.md`（共约 138KB，逐条带 `文件:行`），
> 它们是 M2 spec 全部事实断言的出处。

**上游**：M2 spec `docs/superpowers/specs/2026-09-16-map-simos-design.md`（状态：待用户评审）；
总纲 `docs/superpowers/specs/2026-09-16-simos-master-design.md`；
M1 spec `docs/superpowers/specs/2026-09-16-util-simos-design.md`（已执行，**Task 13 的段位判定要读它 §3.5**）。

**承接的跨里程碑约束**（M1 关账时补记，M2 spec §1.4 已收）：
任何含 `ADD` 事件的 `TemporalSeries`，一律用模块级 `static final` 的 `addition` 构建 ——
各写各的 lambda 会让两个结构相同的序列不相等，使往返断言以"序列不相等"这种费解形态**假红**。

---

## 四路取证完成 —— 汇总与下一步

四份交付件已从 `/tmp` 拷入工作区（`/tmp` 不跨重启）：`m2-recon-{A,B,C,D}-*.md`，共约 138KB。
四路都在派单的三条约束下作业：**只给事实 + `文件:行`**、**不给设计建议**、**宁可报「核不了」也不报没跑过的结论**。
**三路交了「未能核实」清单**（B 4 条 / C 4 条 / D 7 条 / A 7 条），**无一路把推导当结论**。

**控制器抽验（不采信、只看过）**：
- `minQ_minR|maxQ_maxR` 确在 `MapData.java`(2 处) 与 `pathway.js`(1 处) —— B 的"前端重写一份"属实
- `TerraType` 在 GSimulator **零命中**（exit=1）；`TerrainType` 实测 7 字段
  `name/color/food/gold/stone/moveCost/description` —— D 的"总纲字段描述不符"属实

**★ 四处与总纲的出入（M2 spec 必须处理，不得沉默沿用）**：
1. **L3 措辞**：总纲「索引 1-4」→ 实测 **{1,2,4,5}**；且**实际错位面比总纲窄**（唯一错位处是死数据）。
2. **L8 计数**：总纲「`MapService` 内 12 处」→ 实测 **13 处**；主源码 **17**；全仓（含测试）**36**。
   （"12 参数"**对**；"12 次"**不准**。事故文档 `bugs/2026-08-01-logic-edges-wiped-on-rebuild.md:30-34` 记的
   **6 处**是那一次事故的触发面，**不是 `new MapData(` 的全部调用点**——两者不是一回事。）
3. **`TerraType` 字段**：总纲 §5.1 写 `TerraType`（`color`/`height`/`pass`/`name`）；
   实测 GSimulator **无 `TerraType` 这个名字**，等价类 `MapData.TerrainType` 是
   `name/color/food/gold/stone/moveCost/description` —— **`height` 与 `pass` 都不存在**。
   （**性质判读**：这段是总纲对**新设计**的描述，不必然算错——但它与现状的差异是**必须裁决的设计问题**：
   新 `TerraType` 是保留 food/gold/stone/moveCost，还是改成 height/pass？**M2 spec 必须给出答案**。）
4. **L1 的默认路径节点**：总纲写 `n0007`，B 推出 `n0005`（**B 自陈系推导、未运行代码**）⇒ **记为待核，不改文档**。

---

## M2 步骤 ① 完成 —— 五项待决全部裁决

**Ruling: 总纲 §十三 给 MapSimos 列的五项待决，逐条裁决如下；四项总纲出入按纪律第 5 条
（推导 ≠ 实测）分别处置 —— 措辞/计数类当场写进 spec §八，推导类记为待核。**
— 依据：四路只读取证（`m2-recon-{A,B,C,D}-*.md`，逐条带 `文件:行`）+ 控制器抽验。
— 代价若错：设计面重做（spec §三~§七 是 bite-sized 计划的直接输入），但**每一项都指向可复核的证据**，
  推翻时只需重读对应 recon 段落，不需重建取证。

| # | 待决项 | 裁决 | 硬证据 |
|---|---|---|---|
| 1 | 六边形数据结构 | `HexCoord(q,r)` record 作身份；`Map<HexCoord,HexCell>`；**单一 `HexDirection` 枚举（A 序）取代 8 份方向表**；**删 `gridSize`**（恒 30 死值、不参与取格、与真实 `mapRadius=80` 矛盾）；**删 `hexOrientation`**（恒 `false`、无读取分支，而实际公式是 pointy-top ⇒ 值与几何矛盾） | A：`TerrainGeometry.DIRS` vs `MapService.HEX_DIRS` **都是 package-private、都不导出 API** ⇒ 消费方只能复制，无编译期约束（这是 8 份表的**根因**）；`hexOrientation` 三处写死 false |
| 2 | `Region` 统一 | ★ **实测是 4 活 + 1 死，不是 3 个**；只留一个权威 `Region`（`RegionId`+`name`+`Set<HexCoord>`+`RegionMeta`）；**边界降为可重算的 `RegionBoundary`**；**`CompressedRegion` 概念整体取消**（缓存不是状态）；`ContourLayer`→生成参数、`TerrainBlock`→Command；**加 `RegionIndex` 反向索引**解 L5 | B：`Province` **无 name 字段**（名字是 map 键）、**无边界字段**；`service.CompressedRegion` **import 零命中**；4 份 `edgeKey` 里前端那份注释自陈 *"Must stay in sync with MapData.edgeKey() in Java"*；6 处 `hexes().contains(...)` 线性扫描逐字重复 |
| 3 | 连通性稳定 ID | **边不要 ID**（规范序 `EdgeRef` 一个类型取代 4 份字符串实现）；**`PathwayId` 一旦分配即持久化、不由内容派生**（内容派生会让"改一个中间节点"变成"换了一条河"）；**分支点即端点**，分支处断成独立线 | B：★ **线段只有 groupId，所有河流共享 `"river"`**；**链身份 = 返回列表下标**；**"一条有名字的河"在 River/Road 废弃后无承载结构** ⇒ 总纲 §5.1 的"单条线段可寻址"**当前做不到** |
| 4 | 生成参数面 | 参数对象化 `GenerationSpec`（~60 个方法体内魔法数字全提字段、**默认值只此一份**）；**删 `worldId`/`coastRoughness`**（形参在体内从未被引用）；**`ridges` 静默硬夹改构造期抛异常**；**`landRatio` 改名 `baseSeaLevel`**（实测只影响这一个数）；**seed 落盘 + 两条入口同路**（MCP 当前不写 contour ⇒ 不可复现）；**`HexCell` 加 `height`**；**地形词表唯一化** | D：`worldId`/`coastRoughness` 体内零引用；默认值三份拷贝已分歧（radius 120 vs 80、roughness 0.5 vs 0.6）；`frags` 可为负；`classify` **永产不出 forest/desert/tundra**（词表是谎话）；`Map.copyOf` 打乱落盘序 |
| 5 | `MapChangeSet` 字段清单 | 与 `GameMap` 的 record 组件**一一对应**；★ **往返测试用反射枚举 `GameMap` 全部组件逐组件制造差异** ⇒ **新增状态字段若不进变更集，测试自动红**。这是铁律 5 的**机械化落地**，不靠纪律 | C：`MapDiff` 手工维护 ⇒ **6 个字段漂移**（含 C 新查出的 `gridSize`/`hexOrientation`），**零守卫**（最接近的 `MapServiceChildNodeSaveTest` 只断言"父基线被创建 + diff 文件存在"，**不断言 resolve 回的内容**） |

**★ 一处刻意不做的修正**：`CLAUDE.md` 里铁律 5 的由来段写"**四个字段**漂移"
（`terrainBlocks`/`terrainTypes`/`pathwayGroups`/`edges`）。侦察 C 实测**是 6 个**，
但新查出的 `gridSize`/`hexOrientation` **没有任何写路径能改它们**（`MapResolver:256-257` 直接透传 `base.*`），
属**结构性同构而非实际丢失**。**⇒ 不改 CLAUDE.md**：那段是**事故叙事**，四个字段是**真实丢过的那四个**；
把结构性同构混进事故记录，会让叙事变模糊。**新事实记在这里，不覆盖旧叙事。**

**★ 一处纪律第 5 条的现场执行**：`TerraType` 的 `height`/`pass` 两字段，
总纲 §5.1 写有、GSimulator 实测无。**控制器判读为"总纲在描述新设计"而非笔误** ——
但那是**判读**，不是实测。⇒ spec §八 第 1 条把它列为**明确裁决**（用 `TerrainType`、7 字段、海拔归 `HexCell`），
而不是当成"总纲写错了"悄悄改掉。**判读与实测分开摆。**

**产出**：`docs/superpowers/specs/2026-09-16-map-simos-design.md`（未提交，工作树 `??`）。
状态标 **待用户评审**（与 M1 spec 同形制）。**下一动作 = 步骤 ③ 写 bite-sized 计划。**

---

## M2 步骤 ③ 完成 —— bite-sized 计划落盘 + dispatch 前冲突扫描

**产出**：`docs/superpowers/plans/2026-09-16-map-simos-plan.md`（15 个任务，未提交，工作树 `??`）。

**★ dispatch 前扫描查出四处，两处是真冲突，都在派单前判掉了**（不是执行期才发现）：

| 冲突 | 形态 | 裁决 |
|---|---|---|
| **Task 5 ↔ Task 8：`GenerationSpec` 类型不存在** | Task 5 的 `GameMap.spec` 组件**需要该类型才能编译**，而完整参数面在 Task 8 ⇒ **Task 5 根本编译不过** | Task 5 建**只含 `seed` 的骨架**，Task 8 的 Files 改为 **Modify** 扩写 |
| **Task 6 ↔ Task 7：8 vs 7 的组件数** | Task 7 的 `everyGameMapComponentParticipatesInTheChangeSet` 遍历**全部 8 个** `GameMap` 组件，而 `spec` **有意**不在变更集里 ⇒ 那一轮**必然红** | 引入 `EXCLUDED_FROM_CHANGE_SET = Set.of("spec")`，**且加一条用例把它钉死** + **V6 变异**证明豁免口不是洞 |
| **依赖漏记** | Task 10/11/12 都返回 `MapChangeSet`，任务地图里**都没写依赖 6** | 三条依赖已补 |
| **断言重复** | "`GameMap` 不含 `RegionIndex`"两边都想写，而 Task 3 时 `GameMap` 还不存在 | 判归 **Task 5 独有**，Task 3 里改成注释说明 |

**★ 第二处值得单说**：若给 `spec` 随手写一个 `if (name.equals("spec")) continue`，
**豁免口就成了一个正好等于"下一个被遗忘的字段"大小的洞** ——
以后任何人"加字段忘了进变更集"，都能靠往这个 `if` 里再加一个名字糊过去。
**判法**：把豁免写成一个**被单独用例钉死的集合**（加名字是显式动作，diff 里看得见），
**并配一条变异 V6**（往豁免集里塞 `"edges"` ⇒ 必须红）。**豁免与它的守卫必须同时存在。**

**★ 一处刻意的不写**：`TerrainCatalog` 的 9 行数值、`GenerationSpec` 的 ~60 个阈值、
`TerrainClassifier` 的后 3 项阈值 —— 计划里**故意不给数**，只给**读取程序**（`git grep` 命令 + 出处要求）。
理由：控制器**没有实测过它们**，写进来就是**编造设计**（总纲 §六 的硬门原话）。
计划头部已把"本计划写死的值"与"执行期从 GSimulator 现读的值"**分成两类明写**。

**下一步**：步骤 ④ 执行（SDD 派单），**评审每任务 ≤3 轮**（CLAUDE.md 新规）。

---

## 用户裁决 U1~U4 的落地（2026-09-16，用户原话见下）

> 「1 B 2 全进，方便从其他地方恢复工作状态 地形的话，设立海洋、平原、沙漠、低矮丘陵、山地、
> 平缓高原、高原山地，高度从小到大，具有不同特性；边界还是要写存储的，要不然数据持久化会出问题」

| 编号 | 裁决 | 落地位置 |
|---|---|---|
| **U1** | 地形词表 = **7 项**（海洋/平原/沙漠/低矮丘陵/山地/平缓高原/高原山地），**高度升序**，各有特性。**GSimulator 那 9 项整个作废**（不是改名） | spec §〇.0 U1、§6.1、§6.2、§八 1/1b；计划 Task 2 全节、Task 9 全节、文件结构表、任务地图、头部"三类取值" |
| **U2** | **边界要写存储**：「要不然数据持久化会出问题」 | spec §〇.0 U2、§4.2、§4.3；计划 Task 3 全节、Task 5 Step 3/4/5、头部 Architecture 第 3 条 |
| **U3** | 分支策略 = 选项 (b) | 待 Task 1 交回后执行 |
| **U4** | 工作区产物**全进**（方便从其他地方恢复工作状态） | 待执行 |

### ★ U2 推翻了控制器自己的裁定（必须记）

控制器**原本裁的是"边界纯派生、不进状态"**，理由是可随时重算的东西不该是权威状态。
**用户否掉了它**，理由是持久化。**这条推理链要留着**：
只活在计算里的边界，会逼**每一个读档方**各自重实现一遍推导 —— 那正是 GSimulator 的
`edgeKey` 四份副本那类病的同一个病根（**同一份知识在 N 处各自实现**）。
**落地形态**：`boundary` 是 `Region` 的**组件**（故自然落盘、自然往返，`MapChangeSet` **不需要新组件**），
**规范构造器重算比对**堵死漂移。

### ★ U1/U2 引发的连锁（controller rulings，逐条记）

| # | 裁定 | 理由 | 判错的代价 |
|---|---|---|---|
| R-U1-a | **高度带的唯一持有者是 `TerrainCatalog`**；`GenerationSpec` 与 `TerrainClassifier` 都不得再有按高度的分界 | U1 说"高度从小到大"⇒ 带进词表；若 spec 与分类器各持一套 ⇒ **退回 L9 的病**（词表一份、阈值另一份） | 一份新词表又长出第二套阈值，L9 守卫形同虚设 |
| R-U1-b | 沙漠带内**额外的低湿度门**；过不了门**返回 `plains`**（写死常量，不用"相邻低带"算法） | 否则每张图在该高度长出一圈沙漠环；用常量是因为"相邻低带"将来插一项就变意思 | 要么地图长沙漠环，要么回退逻辑随词表演化而悄悄改语义 |
| R-U1-c | `moveCost` 只钉三条相对关系（`plains` 严格最小、`ocean ≥ plateau_mountains`、`plateau < mountains`），**其余不设断言** | 特性栏只支持这三条；**计划没给依据的不许编成断言** | 编出没有依据的断言 = 乙族（未经核实即声称） |
| R-U2-a | `RegionBoundary.of` 收 **`Set<HexCoord>`** 而非 `Region` | `Region` 的构造需要 `RegionBoundary` ⇒ 收 `Region` 成死循环 | 类型层面写不出来 |
| R-U2-b | `RegionBoundary.of` 必须**规范化**：先排序，环起点旋到字典序最小顶点、绕行方向取规范值 | `hexes` 是 `Set.copyOf`（**不保序**），而 `boundary` 现在**参与 `equals`** ⇒ 非规范化会让内容相同的两个 `Region` 判不等 | 一个只在两次构造迭代序恰好分叉时才现形的 bug |
| R-U2-c | **删掉 `GameMap.boundaryOf(RegionId)`** | U2 之后 `regions().get(id).boundary()` 已是权威路径，再开一条就是同一概念的第二条路 | 两条路各自演化，读者不知道哪条是权威 |
| R-U2-d | `MapChangeSet` **不加新组件**，组件数仍 **8 vs 7**，`spec` 仍是唯一豁免项，V6 变异照旧 | `regions` 的**值**是 `Region`，`equals` 含 `boundary` ⇒ 边界漂移会被值比对抓住 | 误加一层组件，反而绕开值比对 |

**★ 一处性质反转，别让后人看糊涂**：Task 3 原有用例 `boundaryIsDerivedNotStored`
（反射断言 `Region` **不含** boundary 组件）在 U2 之后**反了**，改成 `boundaryIsAStoredComponent`。
**但 `RegionIndex` 不变** —— 索引仍派生、不进存储。**"派生"这个词现在指两件相反的事，看的时候先看主语。**

**★ 已核**：Task 10 的 `terrainTypesComponentIsTheCatalog` 与 Task 2 的 `catalogHasExactlySevenKeys`
**不需改**（前者比的是 `TerrainCatalog.defaults()`，与项数解耦）。**这两条是核对过的，不是想当然。**

### 改完后跑过的两项结构检查（当场跑，非推导）
- `awk` 逐 Task 校验 Step 编号：**15 个任务全部连续、无重复**（编辑期一度在 Task 9 留下过重复的 Step 2/3，已删）。
- `git grep` 全量扫旧措辞（`9 项`/`water`/`forest`/`派生边界`/`boundaryOf`）：**命中处逐条处置完毕**。
