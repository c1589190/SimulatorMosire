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

---

## Task 1（hex 包）交回：7 条顾虑的裁定（控制器）

实现者报 **DONE_WITH_CONCERNS**，提交 `b0bcf0e`（6 文件 / 519 行；
`clean verify` 全 reactor 绿；7/7 变异体红且每个都先自证过字节不同）。

### ★ 顾虑 1（判形分支**没自证**）—— **实测后判定：它有判别力，来源是 `""`**

> ⚠️ **本节标题与紧接着那条引述已在第 2 轮 F-新1 就地更正。** 原标题写作"顾虑 1（形状检查**没有判别力**）"，
> 并"引述"实现者说"只有末尾 `null` 那一行有判别力"——**这两句都不来自报告**。
> 报告原文（`4335b52` §7-1）说的是："**判形那条没有专门变异**（它被 `""`/`"_"`/`"1_"`/`"_2"` 四条输入**守着**，
> 但我**没有**实跑'删掉判形分支'的变异，故此条只算'**已测**'、不算'**已自证**'）。"
> —— 它说的是"**我还没验**"，**不是**"它没有判别力"。**是控制器把这句转述错了**；这个错随后经计划 R1-d
> 传进报告，逼出了一段**替原文认罪的假自白**（链条与订正见本文件末尾「第 2 轮」一节）。
> **下面的实测表与裁定 R-T1-a 不受影响**：那是当场实测的，其后又被 R-M1 独立复现。

实现者说：判形分支**没有专门变异**过，被证明的只有判空那条（M5）；
`""`/`"_"`/`"1_"`/`"_2"` 四条输入"守着"它，但**没有实跑**"删掉判形分支"这个变异。

**控制器当场实测**（`/tmp/Probe3.java`，成对跑"有形状检查/删掉形状检查"，
判据是**用例那句 `isInstanceOf(IllegalArgumentException.class)` 会不会翻**）：

| 输入 | 有检查 | 无检查 | 断言会翻？ |
|---|---|---|---|
| `""` | IAE | **非 IAE**（`StringIndexOutOfBoundsException`） | ★ **会翻** |
| `"12"` | IAE | **非 IAE**（同上） | ★ **会翻** |
| `"_"` / `"1_"` / `"_2"` | IAE | IAE（`NumberFormatException`） | 不会翻 |
| `"a_b"` | IAE | IAE（同上） | 不会翻 |

**⇒ 形状检查有判别力，靠的是 `""`**（它已经在用例里、是第一条断言），以及 `"12"`。
理由是 `i = -1` 时 `substring(0, -1)` 先炸成 **SIOOBE，而 SIOOBE 不是 IAE**。
**实现者那条自评哪里不准**：它把四条输入**都**算作"守着"判形，**实际只有 `""`（与同源的 `"12"`）守得住**——
`"_"`/`"1_"`/`"_2"` 删掉判形后照样抛 `IAE`，断言不翻。
（原句写的是"实现者把 `""` 也算进了'被 NFE 挡住'那一类"——**报告从没提过那四条与 NFE 的关系**，同属上一段的转述错误。）

**★ 控制器自己在做这个判定时先写错过一次**：第一版探针把判据写成"两次异常类名是否不同"，
于是 `"_"` 那几行也被标成"有判别力"——**而 NFE 恰恰是 IAE 的子类，断言根本不翻**。
改判据为"`isIAE` 是否变"才得到上表。**这正是"红了还要问为什么红"的同一个坑：异常类型不同 ≠ 判别力。**

**裁定 R-T1-a**：**代码不改**。要改的是两处文本 ——
① 报告 §7-1 的结论按上表更正；② 补一条变异 **M8「删掉形状检查」⇒ 必须红**，
并写明红的理由是**异常类型从 IAE 变成 SIOOBE**（不是消息、不是别的）。

### 其余六条

| # | 顾虑 | 裁定 | 理由 / 代价 |
|---|---|---|---|
| **R-T1-b** | `withinRadius(center, 负数)` 返回空集是自定语义 | ★ **改成抛 `IllegalArgumentException`** | 本项目 Global Constraints 与 `GenerationSpec` 都写着"范围校验**在构造期抛异常**，**不静默夹取**"；同模块 `minQ()` 空网格已抛 `IllegalStateException`。**空集是"静默夹取"的近亲**。`radius=0 → {center}` 不变。代价：那条冻结用例改期望值 |
| **R-T1-c** | 手写 `toString` 与全局纪律"`toString` 禁止手写"冲突 | ★ **收窄全局约束**：禁止手写的是 **`equals`/`hashCode`**（它们才是往返断言的判据）；`toString` 在**有渲染契约且被用例钉死**时可以手写 | 该条原文的括注本来就只讲了 `equals`，是措辞过宽。计划 Global Constraints 已改；Task 1 的手写 `toString` 由 `toStringAndParseRoundTrip` 的**冻结串**钉住，**保留** |
| **R-T1-d** | spec §3.4 的 `HexCoord.round(double,double)` **无任务承载** | ★ **归 Task 1**，本轮补实现 | spec 是绑定权威，§3.4（`182-189`「一份距离、一份取整」）明写"一个实现"，与 `distanceTo` 并列；`distanceTo` 已在 Task 1 落地，`round` 是同一条裁决的另一半。**guard**：用暴力最近格作参照（见派单）。★ 本行原写 §3.3，**订正为 §3.4**——§3.3 是"删 `gridSize`/`hexOrientation`" |
| **R-T1-e** | 中途强化了自己的用例、并作废前一轮证据重跑 | **接受，无动作** | 这正是"变异体也要自证"要求的做法；作废掉的那轮不进 evidence 是对的 |
| **R-T1-f** | 提交落在 `feat/m1-util-simos` | **已由 U3 分支手术解决** | 分支手术在派单之后才发生；`b0bcf0e` 完整保留在 `feat/m2-map-simos` 上 |
| **R-T1-g** | 自制装置把变异体按变异文件名拷入，7 个 tag 全是**编译错误** | **记台账，无代码动作** | 与控制器 M1 期间那个坑（"算出变异源码却没写盘、javac 编的是原件、三向全绿"）**是同一族**：**红的理由不是被保护的那行**。★ 这是该族**第三个实例**，强化了"变异体也要自证"该进 CLAUDE.md 的理由 |

**下一步**：派 Task 1 任务级评审（**第 1 轮，上限 3 轮**），把上表作为"已裁定、勿重报"随派单发出，
并要评审者**实跑 M8** 与核对 `round` 的归属。

---

## Task 1 任务级评审 —— 第 1 轮（1/3）

**评审者结论**：**Needs fixes**。规格符合性 **符合**（Step 1~6 逐条，四个语义锚逐个核对无误）；
代码质量 **良好**。门禁实跑 `-pl simos-map -am verify` → exit=0（Spotless+Checkstyle+SpotBugs，
`BugInstance size is 0` ×2）。报告落在 `task-1-review.md`
（★ `git check-ignore` 实测**被忽略**，入库须 `git add -f`）。工作树全程未改。

**评审者独立复核了报告的 7 条声称，全部属实**：M1~M7 逐条复现、失败清单与 `Tests run` 计数吻合、
`mutants/` 里 6 个与它自造变异体 **md5 逐字节相同**、8 份日志**各只变一个 `.class`**、报告 §6 的 6 个 md5 全对。

### 发现与裁定

| # | 严重度 | 内容 | 裁定 |
|---|---|---|---|
| **F1** | **Important** | **"序"没有任何用例钉住**：M10（`neighbors()` 反序）与 M11（交换 `next`/`prev` 的 `+1`/`+5`）**双双存活**（exit=0、21/21 全绿，变异确实编译进去了） | ★ **本轮修** → 计划 **R1-e** |
| F2 | Minor | `HexCoordTest:76-78` 注释过度声称"能抓住 `s()` 写成 `q+r`"，**实测抓不住** | ★ 本轮修 → R1-f.1 |
| F3 | Minor | 两处中文接缝空格（Spotless **抓不到**，门禁全绿） | ★ 本轮修 → R1-f.2 |
| F4 | Minor | `HexGrid` 的 `null` 抛 NPE 与 `parse(null)` 抛 IAE 口径不一；评审者**自己声明不主张它是缺陷** | ★ **撤回，不加守卫**，只补一句 Javadoc → R1-f.3 |

**F1 为什么是要害**：`HexDirection` 的 Doc 写着"索引即边序号，全模块唯一"，而**序本身无人钉**。
`offsetsMatchFrozenTable` 比的是 `values()`（**声明序**），**不是 `ALL` 的消费序，也不是 `neighbors()` 的输出序**
—— 形态 4 里 `facetNames()` 钉住而 `queryAll()` 漏掉的翻版。
`next()/prev()` 在本任务里**还没有生产消费者**，要等 Task 3（边界环游走）才上场 ⇒ **不修就是把地雷埋进 Task 3**。
（反方向对照：`opposite()` **反而被钉死了** —— `+3` 是 6 元集上唯一的无不动点对合。）

**F4 撤回的理由**：`parse` 的入参是 **JSON 边界上来的外部数据**（`null` = 数据非法）；
`HexGrid` 的入参是**程序内部对象**，NPE 与 JDK 自身（`Set.copyOf(null)`）一致 —— **不是两套口径，是两种东西**。
且按**形态 2**，`requireNonNull` 只会造出消息恰为字段名的**装饰护栏**。

### 评审者自报的四件事（都印证了本项目纪律）

1. ★ **它自己也踩了变异体拷贝那个坑（该族第 4 个实例）**：`sed 's/^m[0-9]*-[a-z-]*\.//'` 的 `[a-z-]*`
   不匹配数字 ⇒ `m1-opposite-plus0` / `m3-drop-div2` 没剥掉前缀 ⇒ **7 个 tag 全是编译错误**，
   且残留**污染了后续两轮**（"失败清单"是空的，因为压根没跑到断言）。
   **修法已进 CLAUDE.md**：白名单推目标名 + 每轮清理非规范名 `.java` + **强制断言 `grep -c "COMPILATION ERROR"` 为 0**。
2. **M8 实跑：红了，红的理由正是被保护的那行。** `maven_exit=1`、`Tests run: 21, Failures: 1`、
   `HexCoordTest.parseRejectsMalformed:41`、`Expecting ... IllegalArgumentException but was StringIndexOutOfBoundsException`。
   **控制器的实测表判对了。** 评审者另核三点：是 `AssertionError` 非编译错误、`:41` 正是 `parse("")`、
   红在**异常类型**而非消息（不落形态 2 的坑）。
3. **`round` 空隙：控制器判对了**（`git grep` 与 `grep --hidden --no-ignore-files` 双双零命中，exit=1）；
   ★ **但编号是 §3.4 不是 §3.3** —— 计划 R1-b 与上面 R-T1-d 行均已订正。
4. **M11 的旁证**：它第一版 M11 因脚本 bug 变成 `next == prev`，那次**是红的** ——
   该用例能抓"同值"，抓不住"整体反向"。形态 3 的教科书例子。

### 评审者诚实标注的未核实项（6 条，不当作事实）

本轮已处理两条：`parse("12")` 由**推导**改为**用例钉住**（R1-f.4）；
`HexGridTest` 第三处的**行号** —— 评审者写 `:30`，**实为 `:38`**（`:30` 是第一个用例的收尾大括号），
已按**实读行号**派单。其余四条（`"_"`/`"1_"`/`"_2"`/`"a_b"` 逐格实跑、`spotbugs:check` 直调 goal 的失败、
全 reactor `clean verify`、报告 §3"跑了两遍"）**记在此处，留待关账时按需补**。

### 控制器派单前的两处自身更正（已写进计划 R1-b）

复核 R1-b 时发现我先前写的两句是**错的**：
① 朴素舍入的缺陷**不是**"产出 `q+r+s ≠ 0` 的非法格" —— `s()` 在本类型里是**导出**的（`-q-r`），
那条恒等式**永远**成立，`(1,1)` 是合法格；真实缺陷是**它给的不是最近格**
（`(0.5,0.5)` 朴素给 `(1,1)`，cube 距离 **1**；真正最近的是 `(1,0)`/`(0,1)`，距离 **0.5**）。
② guard **不得**断言"与暴力解坐标一致" —— 最近格**存在并列**，按坐标比会在并列处**假红**；
应断言**结果的 cube 距离等于暴力求得的最小距离**。

**下一步**：派 **修复轮 1**（R1-a…R1-f）。★ 原实现者 agent **已不可达**（属压缩前那个上下文窗口），
故**新派**一名实现者。修完派**限域重审** = 本任务**第 2 轮**（上限 3）。

---

## Task 1 修复轮 1 —— 完成（提交 `30a70a2`）

**实现者**：**新派**（原实现者 agent 属压缩前上下文，已不可达）。状态 **DONE_WITH_CONCERNS**。
**提交** `30a70a2`（分支 `feat/m2-map-simos`，**未推送**），一个提交收全部修复 + 48 份证据；
`git add -f` 用于新增的 ignore 文件，**未碰** `CLAUDE.md` / `docs/**` / `.serena/**` / `progress.md`。
**门禁**：`-pl simos-map -am verify` exit 0（MapSimos 26/26）；`./mvnw verify` 全六模块 exit 0。
★ **控制器独立复跑**：`-pl simos-map -am verify` → **BUILD SUCCESS、`BugInstance size is 0`、`Error size is 0`**。
（期间 Eclipse/m2e 报过 `HexCoord cannot be resolved` 与 `spotbugs: failed with 1 bugs`
—— **两条都是 m2e 在改文件途中重建的陈旧假信号**，实测不成立。不采信工具输出，只采信复跑。）

### ★ 本轮最有价值的实证：R-M2 / R-M3「由绿转红」

| 变异 | 修复前字节（`b0bcf0e` 原样取出） | 修复后字节（`30a70a2`） |
|---|---|---|
| **R-M2** `neighbors()` 加 `.reversed()` | **21 tests / 0 failures，BUILD SUCCESS（全绿存活）** | **红** → `neighborsFollowDirectionOrder:217` |
| **R-M3** 交换 `next()` 的 `+1` 与 `prev()` 的 `+5` | **全绿存活** | **红** → `nextAndPrevFollowFrozenCycle:48`（`expected: SE but was: NE`）+ `neighborsFollowDirectionOrder:247` |

⇒ R1-e 的两条护栏**不是装饰**，是补上了**已经存在**的判别力缺口。
★ 这是本项目**第一次**把"变异体由**存活**转为**死亡**"当作护栏有效性的直接证据
（此前都是"删掉它会红"的单向证明）。

**R-M1 / R-M4 / R-M5 / R-M6** 全部红，红的理由都是被保护那行：
R-M1 删 `parse` 判形 → `parseRejectsMalformed:45`（**异常类型** IAE→SIOOBE）；
R-M4 删 `radius < 0` 守卫 → `cellsWithinRadiusIsClosedBall:39`；
R-M5 改逐轴四舍五入 → `roundIsNearestHex:138 [(-1.5,-1.5)]`（`expected: 0.5 but was: 1.0`，**非并列情形**）；
R-M6 删 finite 守卫 → `roundRejectsNonFinite:161`。
另加 **R-M7**（`distanceTo` 只用两轴，翻 4 条）、**R-M8**（`s()` 写成 `q+r`，**只翻 `sAxisInvariant`**）
—— R-M8 就是 F2 那句话的实测证据。全部 `COMPILATION ERROR` 计数为 0，被改类的 `.class` md5 逐个变化。

### 五条顾虑的裁定（**全部不产生新代码**）

| # | 顾虑 | 裁定 |
|---|---|---|
| **C1** | `"12"` 只有探针级证据（断言在 `""` 就中止，`:46` 不执行） | **不加新用例，改声称**。`""` 与 `"12"` 命中**同一行**、同一变异下**同一机制**（`i = -1 → substring(0,-1)`）；再加一条**不增加判别力**，正是本项目反对的"装饰"。计划 R1-f.4 里"加进去就变成用例钉住的事实"**是控制器说过头了**，已就地更正为「`"12"` 只到探针级，套件级由 `""` 承担」。代价若错：无 |
| **C2** | `round` 故意不写末支 `else`，偏离参考实现 | **接受**。★ 计划里**根本没有**那个 `else` 草图（`grep` 实测空手）—— 它偏离的是**教科书算法**，不是需求书。偏离理由正确：该分支要修正的是 **s 轴**，而 `s` 是导出量、不参与返回，写入即 `DLS_DEAD_LOCAL_STORE`。**SpotBugs 抓到它是门禁按设计工作。** 已在计划 R1-b 加「后来者不要把它补回去」 |
| **C3** | 自加：`roundOnFrozenSamples`、`HexGridTest:74` 接缝空格、R-M7/R-M8、修复前世界两跑 | **全部接受**。★ 已核 `roundOnFrozenSamples` **遵守「不钉并列侧」的裁定**（三组样例 `(0.6,0.4)` / `(-0.6,-0.4)` / `(2.2,-1.1)` 均**非 0.5 倍数**、最近格**不并列**，控制器逐个验过）。`roundIsNearestHex` 的「放大两倍再比」也成立（各轴差值全偶 ⇒ `/2` 精确 ⇒ 可用 `isEqualTo` 无需容差） |
| **C4** | 它自曝：R-M3 第一版用裸行 needle、一次命中两行，做成 `prev := next` 而非真交换 —— **虽红但红的理由不对** | **记录**。「红了还要问为什么红」的**第 5 个实例**，且是**首次由执行者在自己的装置上抓住**。修法：块级 needle |
| **C5** | `"12"` / `null` / 越界输入无变异体 | **无动作**。`null` 的判别力**已由上一轮 M5 + 评审者独立复现**证明（删判空 → NPE → 红），且它是清单**最后一条**，前面的断言不会遮住它；`round` 越界输入已裁定「行为不定义」，**不需要**变异体 |

### ★ 一条新形态（记，供 M3 汇总时考虑进 CLAUDE.md）

**F3 那一类缺陷没有机械检测手段。** 审查报告的「接缝空格」正则**漏了「空格后跟汉字标点」这一形态**，
而实现者又在 `HexGridTest.java:74` 上找到一处 **HEAD 就存在**的同类 —— 即上一轮的"两处"其实是**三处**。
⇒ 中文接缝空格是**工具静默假阴性**的又一个落点：Spotless 全绿、正则也过，只能靠眼睛。
与形态 5 同族（不把工具的静默假阴性当"不存在"）。

**下一步**：派**限域重审** = Task 1 **第 2 轮**（上限 3）。窄包 `review-4335b52..30a70a2-scoped.diff`（57KB）。

---

## Task 1 第 2 轮（限域重审）—— 完成：**Accept with 2 Minor**

窄包 `review-4335b52..30a70a2-scoped.diff`（57737 B：提交清单 + stat + hex 源码/测试 diff 221 行 + 报告 diff
+ md5 清单 diff；**2771 行证据日志明确排除**，并在包里写明"那部分请自行重跑验证，不要读日志当证据"）。

> ⚠️ **轮次记账**：这一轮被**用户中途 kill 过一次，随后由用户自己恢复**并跑完。计为**第 2 轮**（不是两轮）。

**结论**：**Accept with 2 Minor，均为文档层，代码行为无缺陷。**

### 它独立复现的（不是照抄我的台账）

| 复现项 | 结果 |
|---|---|
| **由绿转红** | R-M2：**21/0 绿 → 26/1 红**；R-M3：**21/0 绿 → 26/2 红** —— 在**修复前的字节**上绿、修复后红，独立重跑 |
| `round` 的"并列侧不定义"是否被遵守 | ★ **用精确有理数（`Fraction`）算过**，不是浮点估算：三组冻结样例各轴偏差 0.4 / 0.4 / 0.2，**距 0.5 的余量 0.1 / 0.1 / 0.3** ⇒ 最近格唯一、裁定被遵守 |
| `round` 故意不写末支 `else` 是否真无影响 | 它把证明**加强**了：不是"这些输入上不分叉"，而是**对任何输入都不分叉** |

### 两条 Minor —— 控制器**逐条自核后**裁定

| # | 落点 | 裁定 |
|---|---|---|
| **F-新2** | `HexCoord.java:33-34`：Javadoc 称"（修正后）**各轴偏差都不超过 0.5**" | ★ **成立，伪陈述**。控制器当场实测：`round(0.4, 0.4) = (0, 1)`，`\|r - 0.4\| = **0.6**`；0.05 步长 `[-3,3]²` 网格（14641 点）中 **2592 点（17.70%）** 至少一轴偏差 > 0.5，**最大 0.65**（在 `(-2.65, -2.65)`）。**只删不加**：不采纳评审者推导的 2/3——**我的网格上取不到它**，没实测过的不写进文档 |
| **F-新1** | 报告 `:201-202`/`:251` 的 ★ 段**替原文认罪** | ★ **成立，且根因在控制器**。回读 `4335b52` 原文（`git show` 后 grep）：报告**只说"我没有实跑 ⇒ 未自证"**，**从没说过"没有判别力"**；`grep -n "判别力\|M5"` 全篇只有 `:196`/`:199` 两处。**错误链条**：台账本节标题 `:155`（我写错）→ 计划 R1-d（照抄我的错）→ 报告的假自白（实现者照指令写）。三处**全部就地订正**，并在报告里恢复被删掉的原文五行 |

### 第 6 个实例（新形态，已进 CLAUDE.md 形态 1）

**装置的产物自己会带状态**：重审者先用一手 `round` 探针"复核"，读到的却是**上一轮 R-M5 留在
`target/classes` 里的陈旧 `HexCoord.class`**，于是打出一份**完全虚假**的发现（"`round(0.5,0.5)=(1,1)`、
超界 23.7%"）——若照单全收，就会去"修"一个不存在的缺陷。**每一轮开跑前必须恢复干净世界**；
"基线修正记录"这类事后补记不能代替它。

### 裁定：**不烧第 3 轮**

两条都是**文档层**、修复比描述还短，按 CLAUDE.md 的推论（「已确证的发现，若修复比它的描述还短，
在发现的那一刻修掉，不 park」）**由控制器当场修**，随后 `./mvnw -pl simos-map -am verify` 全绿
（util 156/0、map 26/0、`BugInstance size is 0`、`Error size is 0`、BUILD SUCCESS）。
⇒ **Task 1 用轮 2/3，关账。**

**代价若错**：F-新2 我删掉了"各轴偏差 ≤ 0.5"却没给替代上界——若将来有人**依赖**某条偏差上界做优化，
得重新自己测；文档现在明说"验收口径是返回最近格，不是逐轴偏差"，把这条依赖显式化在反面。

---

## Task 1: complete

**`hex` 包已关账。** 提交链：实现 `b0bcf0e` → 修复轮 1 `30a70a2` → 第 2 轮收尾 `fbafbdd`
（`cdc814d` 是无关的 `.serena` 配置）。评审用 **2/3 轮**；门禁 `./mvnw -pl simos-map -am verify`
在收尾提交上全绿（util 156/0、map 26/0、SpotBugs 0）。

**下一个：Task 2（`terrain` 包）**。

---

## Task 2（`terrain` 包）派单

- **brief**：`task-2-brief.md`（脚本 `scripts/task-brief` 抽出，200 行）
- **BASE**：`9744c56`（台账补 `Task 1: complete` 的提交；它之前还有 `fbafbdd`/`cdc814d` 两笔收尾）
  ★ **生成评审包时以此为准，不要用 `HEAD~1`**（Task 2 若多提交，`HEAD~1` 会静默截断）。
  若实现者的提交落在别的提交之后（我随后又提交了文档），以**实现者的父提交**为 BASE——等价的"开工前那一刻"。
- **实现者**：模型 `sonnet`，agentId `ac63add44c841ae1f`（修复轮 1–3 复用它）
- **报告**：`task-2-report.md`

### 派单时我做的裁定（brief 里有歧义的四处，都写进派单了）

| # | 歧义 | 裁定 | 若错代价 |
|---|---|---|---|
| **a** | brief 的用例清单列了 `ofNeverFallsBack`，但它的注解说"用变异证明" | **不写成 `@Test`**——一句断不了言的 `@Test` 在评审里就是装饰。它的判别力**已由 `ofThrowsOnUnknownKey` 承担**（兜底变异会让 `of("nope")` 不再抛）。它只作为 Step 5 变异表第 1 行存在 | 少一条"名字存在但没有断言"的用例；判别力不损失 |
| **b** | 平原的颜色最顺手的绿**很可能就是 `#6CC261`**（旧词表 A 的平原绿 = `ContourQueryEngine.terrainColor` 的兜底色） | 要求实现者**选中就换掉并记录**。该用例钉的是"这个已知污染值不许复活"，不是"plains 长什么样" | 若没换，`plainsGreenIsNotTheOldFallback` 会红——**这是设计如此**，不是误伤 |
| **c** | 表里"不可通行"没有字段承载（brief 数过是 10 字段） | **不加第 11 个字段**，用 `moveCost` 哨兵值表达，并在 Javadoc 与报告里写明哨兵 | 将来若要布尔 `passable`，改 record 组件列表是跨模块破坏性变更（铁律 5 下须刻意为之） |
| **d** | `defaults()` 是否缓存成 `static final` | **按草图（方法内新建）**。类加载期静态初始化抛异常会变成 `ExceptionInInitializerError`，比 IAE 难查得多——而 `everyTypeIsConstructible` 正要看 IAE | 每次 `of()` 重建 7 项表，性能可忽略；换来的失败模式更可读 |

**另**：Step 3 的 GSimulator 旧词表调查**只为留记录**（M6 的老存档导入器要那张映射表），**明写它是 M6 的输入**；U1 已把那份 9 项表整个作废，**不得从此抄任何数值**。`forest`/`swamp`/`lowland`/`tundra` 无显然对应物 ⇒ **如实写"待 M6 裁决"，不许替 M6 编映射**。

### 本机工具坑（本次实际踩到）

**SDD 技能自带的 `scripts/task-brief` / `scripts/review-package` 是 CRLF**（`file` 实测
`with CRLF line terminators`，41 行带 `\r`），直接执行报
`/usr/bin/env: 'bash\r': No such file or directory`。**绕法**：`tr -d '\r' < 脚本 > /tmp/x.sh && bash /tmp/x.sh <参数>`
（已写进 CLAUDE.md 的换机自检清单）。
