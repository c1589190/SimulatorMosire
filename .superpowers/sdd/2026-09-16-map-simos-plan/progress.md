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

### ★ 用户裁定的节奏变更（2026-09-17，Task 2 在跑时下达）

> **用户原话**：「写完 Map 模块的框架就提交一下，提交完开始评审测试，测完修好再提交以下」

**控制器的解读**（若错，用户一句话可改）：**「框架」= 结构性骨架 = Task 1–8**
（Task 7 的标题本身就是「★ 往返**框架** + 反射组件枚举 + 自证」）。
Task 9–14 是生成算法/守卫，Task 15 是关账。

**⇒ 节奏改为两段式，取代逐任务评审：**

1. **框架期（Task 2–8）**：每个任务仍然实现 + **自带变异实验室自证**（那是任务内的"测试"），
   但**不逐任务派评审**。每个任务在台账落 `Task N: complete（框架期，评审延后）`。
2. **框架写完 → 提交**（里程碑提交）。
3. **提交后开评审测试**：对**整个框架**派一轮评审（不是每任务一轮）。
4. **测完修好 → 再提交一次。**

**为什么这么读**：用户此前已两次要求"少审多写"（≤3 轮裁定、"马上把当前评审停掉，开始开发下一阶段代码"），
且本条的动词次序明确是"先提交、**再开始**评审"——是**阶段边界**，不是逐任务的循环。
**代价若错**：评审发现的问题会攒到框架期结束才暴露，返工面比逐任务大；
补偿是任务内的变异实验室仍在逐个任务自证。

### 本机工具坑（本次实际踩到）

**SDD 技能自带的 `scripts/task-brief` / `scripts/review-package` 是 CRLF**（`file` 实测
`with CRLF line terminators`，41 行带 `\r`），直接执行报
`/usr/bin/env: 'bash\r': No such file or directory`。**绕法**：`tr -d '\r' < 脚本 > /tmp/x.sh && bash /tmp/x.sh <参数>`
（已写进 CLAUDE.md 的换机自检清单）。

---

## Task 2（`terrain` 包）: complete（框架期，评审延后）

**实现**：`6d52322`（`TerrainType` + `TerrainCatalog`，281 行）→ **修复**：`20508d8`（控制器当场修）。
报告与变异证据 `abf293e`。门禁 `clean verify`：util 156 / map 39，Failures 0、Errors 0，
`BugInstance size is 0` ×2，BUILD SUCCESS。

**评审方式**：按用户 2026-09-17 红线，**不派评审者、不生成评审包**，控制器自读 diff 即评审；
本轮**不计入**≤3 轮额度（未派独立评审）。

### 裁定（实现者报了 10 条顾虑，全数裁定）

| # | 顾虑 | 裁定 |
|---|---|---|
| 1 | `defaultsIterationOrderIsStable` 在 m2（`Map.copyOf`）下**照样绿** | **删用例**（R-2b）。名字在承诺一件它测不了的事；保序钉子是 `defaultsKeySetEqualsKeys` |
| 2 | 颜色排除用例逐字符比，`"#6cc261"` 能溜过 | **改大小写不敏感**（R-2c）。正则明确允许小写 ⇒ 这是真实漏路，不是理论风险 |
| 3 | 只排了 `#6CC261`，漏了 `#5B8C3E`（**请裁定**） | **补进排除集合**（R-2c）。**已回写 spec §6.1**：两个兜底色来自两个不同词表族，初稿只记了前者 |
| 4 | 哨兵 999 的 M6 换算成本（100 倍差距） | **不动**。哨兵"不参与任何算术"已有 Javadoc 承诺；换算成本归 M6 导入器裁定 |
| 5 | 构造器 5 条守卫只有"高度带"有自证（m9） | **补 `constructorRejectsInvalidFields`**（R-2d），六条逐条给违例 |
| 6 | `description` 无校验 | **有意不设**——只作文档用途，不被解引用，null 不破坏往返。已写进 Javadoc 免得被当疏漏 |
| 7 | `everyTypeIsConstructible` 与 m9 覆盖面重叠 | **不动**。两者口径不同：前者防"校验误伤合法行"，后者证"校验能响" |
| 8 | 旧 key → 新 key 映射有 4 个孤儿 + `plains` 双射 + `hills → low_hills` 偏弱 | **不动，留 M6 裁定**。U1 已作废旧表，此时替 M6 定映射就是编造设计（与派单里"不许替 M6 编映射"一致） |
| 9 | 词表 B 的 `plains` name 是"山区" | **不动**。`plainsIsPlainsNotMountains` 已钉住新表不复活它 |
| 10 | 7 项颜色与旧表无一致对应 | **不构成问题**。U1 的意图正是"不作废改名、而是整表作废" |

### 接受并外溢的遗留

- **跨进程序**的 `defaults()` 迭代序无人把守（单进程用例够不到）；保序的实际保证来自
  `LinkedHashMap` + 不 `copyOf` 的**实现选择**，由 `defaultsKeySetEqualsKeys` 间接钉住。
- spec §6.1 **已补记**第二个兜底色 `#5B8C3E`。
- 旧 key → 新 key 映射表（M6 老存档导入器用）：4 个孤儿 + `plains` 双射 + `hills` 偏弱 → **M6 裁决**。

### 自证（G13）

拆 `TerrainType` 的 `key` 守卫 → `constructorRejectsInvalidFields:171` 红（**是那一行**，非编译错误、
非别的守卫代偿）；`plains` 颜色改 `#6cc261` → `noTypeRevivesAKnownFallbackColor:156` 红，消息指名 plains。
两轮变异体 md5 均 ≠ 原件、恢复后精确回位；事后 `clean verify`（不信 `target/classes` 陈旧产物 —— 第六形态）。

---

## Task 3（`region` 包）派单

**BASE**：`5a0b27f`　**agentId**：`ac717a7f85c2a313b`（sonnet）　**brief**：`task-3-brief.md`
**裁定**：`task-3-rulings.md`（**优先于派单书原文**）　**报告**：`task-3-report.md`

### ★ 派单书自相矛盾 —— 控制器裁定 R-3a~R-3h

派单书写 `RegionBoundary(List<List<HexCoord>> rings)`（环元素是**格**），却又要求
`singleHexRingHasSixVertices`（单格边界 6 个**顶点**）。**两套东西，不可能都对。**

**实测判据**（不是推测）：老仓权威算法 `TerrainGeometry.hexSetToBoundaryWithHoles`
（`:267`）逐格逐边收集暴露边、取该边**两个端点**成段再串环；注释写明「closed polygon」
「Canvas **evenodd** fill」；`CompressionService.java:88` 调它存进 `CompressedRegion.boundaries()`。
⇒ **环是格角顶点，`List<List<HexCoord>>` 是错的**；派单书那三个测试名反而全对。

| 裁定 | 内容 | 代价若错 |
|---|---|---|
| **R-3a** | 环元素改为**新增 `hex.HexVertex(int u,int w)`**（放 `hex` 包，它是格几何原语）。不重用 `HexCoord`（是格）、不引 `Pt`（老仓像素类型） | 新增一个公共类型；跨模块可见性由 enforcer 保证不越界 |
| **R-3b** | `(u,w)` 标签定义（各向异性缩放 ⇒ **是标签不是坐标**，不可算距离/角度）；六项 `U/W` 偏移常量表。`HexDirection` 第 `d` 边的两端点 = 顶点 `d` 与 `(d+1)%6`（**逐项对表已验证**：本仓枚举序 = 老仓 `DIRS`） | 偏移表若错，环装配全错 —— 故 R-3f 强制先用例钉住 |
| **R-3c** | **不沿用**老代码 `size()<3 → 空` 短路（那是渲染期下限，非几何事实）。单格→1 环 6 顶点；相邻两格→1 环 10 顶点 | 与老仓行为有意偏离，已要求写进 Javadoc 免得对表时误判 |
| **R-3d** | 规范化（旋到最小顶点开头、取字典序较小方向、环表按首顶点排序）放进**紧凑构造器**，幂等 ⇒ `RegionBoundary` 成值类型 | 绕向被钉死但**无几何含义**（消费端 evenodd），已禁止在 Javadoc 里说成"顺时针" |
| **R-3e** | ★ 顶点度**恒为 2**（每顶点恰 3 格 3 边，k 格属本区 ⇒ 暴露边 = k(3−k)，k=1/2 皆得 2）⇒ 无岔路口、无需转向规则。**这是控制器的推导**，故**必须落成护栏**：度 ≠ 2 就抛 | 推导若错，护栏会在真实输入上抛 —— 已要求**如实报告而不是绕过去** |
| **R-3f** | 偏移表是推导 ⇒ 落码前先钉两条（相邻两格顶点交集恰 2；单格 6 顶点互不相同） | 同 R-3b |
| **R-3g** | `RegionIndex` 承重断言是"**只触发一次 `get`**"（计数 `Map` 包装）；派单书那条"两个索引结果相同"只是冒烟，不算护栏 | 线性扫描若复活，只有计数断言抓得住 |
| **R-3h** | 不写任何"边界不进存储"的断言（U2 已推翻）；`GameMap` 那条归 Task 5，**只写一处** | — |

★ **老代码的浮点身份坑**：`cornerKey = Math.round(x*1000)+"_"+Math.round(y*1000)`（`TerrainGeometry.java:357`）
—— **拿浮点舍入当身份**，同一顶点由不同格中心算出时可能落在 `.5` 两侧而对不上键、**环就断了**。
整数标签 `(u,w)` 正是为消除它。

### 顺带更正的两处文档

1. **spec §4.3 的类型** `List<List<HexCoord>>` → `List<List<HexVertex>>`（含校正说明）。
2. **spec §4.3 对 GSimulator 的转述有误**：初稿写「闭环边界由前端现算（`render.js:244`），
   于是同一份几何在 Java 与 JS 里各有一份实现」——**实测不成立**。老仓是**两个不同的东西同名混用**：
   `render.js:370 computeBoundaryHexes` 返回**边界格**（无序，只用来画调试圆点 `arc(x,y,5/zoom)`），
   `TerrainGeometry:267` 返回**顶点环**（在 Java 里算、存进存档）。结论（推导应收敛到一处）**仍成立，
   但理由换成了"两个概念共用一个名字"+"浮点当身份"**。

---

## Task 4~8 派单前扫描：控制器裁定（2026-09-17）

派单 Task 4 **之前**做的只读静态扫描（不是代码期评审装置 —— 是**计划期检查**）。
12 条发现，**控制器逐条回读计划原文核实过**（子代理是模型输出，不直接采信）。**无"高"severity**。
全文见 `task-4-8-scan-rulings.md`（**优先于计划原文**）。计划与 spec 已**就地改正**。

**代价依据**：Task 3 那个"类型与断言互斥"是**派单后**才发现的 —— 派单前扫出便宜一个量级。

| 裁定 | 内容 | 计划/spec 改动 |
|---|---|---|
| **R-48-a** | `mainRidges` 合法区间 **[1,2]**、越界抛（原稿只有下界，与同任务的 `mainRidgesFiveThrows` 互斥；`mainRidgesTwoIsAccepted` 反证上界=2） | Task 8 校验改 `if (mainRidges < 1 \|\| mainRidges > 2)` |
| **R-48-b** | Task 4 Files 漏了 `EdgeTagsTest.java`（Step 4/5 都用它） | 已补 |
| **R-48-c** | `PathwayGroup` 形状**照老仓不编造**（`MapData.java:451`：`id,name,color,description,visible,Map<String,PropertyDef>`；默认 river `#3295D2`/road `#8B7355`）。★ 两处改口径：老仓静默填空改**抛**；`PropertyDef` 是**嵌套 record，不许为省事删掉**（Task 7 反射要穿透它） | Task 4 Step 2 补两份定义 |
| **R-48-d** | Task 5 的 `Modify HexGrid.java（补内容访问）` 是**残留**：`GameMap` 自己持 `Map<HexCoord,HexCell>`，不经 `HexGrid` ⇒ **删该行，`HexGrid` 永远纯几何** | 删 Modify 行；文件表、Task 1 Step 3 说明、自审表三处一并更正 |
| **R-48-e** | ★ **撤回原裁定的"Task 5 可空 → Task 8 收紧"**。否决理由：中间会留**两轮 nullable 世界**（Task 6 的 `apply`、Task 7 的反射枚举都得绕开 `spec`），而"收紧"那步**没人把守**。⇒ Task 5 的 `empty()` 起即 `GenerationSpec.defaults(0L)`，**spec 从不 null**；Task 8 仍加 `specIsNeverNullAfterTask8`，身份是**守卫**不是收紧动作；`MapChangeSet.java` 列进 Task 8 Files 只为**核实前提**（无 null 兜底就如实报"无需改动"） | Task 5、Task 6 `apply`、Task 8 Files+Step 3、自审表 |
| **R-48-f** | ★★ **本批最要紧的一条**：`keyOf = toString()` 要成立，**每个当 key 的类型必须自备"裸值 `toString()` + `static parse(String)` + 冻结字面量往返用例"三件套**。实测只有 `HexCoord` 齐备；`RegionId`/`PathwayId`/`CityId` 是裸 record（默认输出 `PathwayId[value=…]`、无 `parse`）⇒ **往返当场断、`applyRebuildsTargetExactly` 必红**；`EdgeRef` 有手写 `toString` 但无 `parse`、无冻结串（**全局约束：没有冻结用例的手写 toString 算违规**）。⇒ 三件套**各归其创建任务**：`RegionId`→T3、`PathwayId`/`EdgeRef`→T4、`CityId`→T5。**另更正**：原稿"`"q_r"` 唯一允许出现处"与 `EdgeRef` 的 `"a\|b"` 冲突 ⇒ 正确口径是"**地图 key 的规范串**的唯一允许处"，两者并列 | T3 已发消息追加；T4/T5 就地改；`:1379` 补说明 |
| **R-48-g** | Task 7 补**组件数断言**（spec §9.1b 明文要求，原稿漏）。与反方向的逐组件对应**不重复**：反方向挡不住"两边**同时**多一个同名的第 8 个组件" | Task 7 加 `changeSetHasExactlySevenComponents` |
| **R-48-h** | Task 7 的 `V1~V5` 应为 **V1~V6**（表里是六条；V6"往豁免集加 `edges`"同样要证改前是绿的） | 已改 |
| **R-48-i** | Task 6 的 `terrainTypes` 变异**要配对**：原稿三条 `betweenDetects*` **全是 hexes**，删掉 `terrainTypes` 比较**它们照样绿** —— 红的是 Task 7 的反射枚举。**不许把它记成 Step 3 用例的判别力** | 加 `betweenDetectsChangedTerrainType`；变异行注明红的来源 |
| **R-48-j** | `GenerationSpec.defaults(long seed)`（seed 无法有默认值） | spec `:487` 就地改 |

★ **U2 的连锁补记**（写进 Task 7，防后人以为边界被漏掉）：`boundary` 成了 `Region` 的组件
⇒ `MapChangeSet` **不需要**为它新开组件（`regions` 整值比对，`Region.equals` 含 `boundary`）
⇒ 组件数**仍 8 vs 7**、`spec` 仍是唯一豁免项、V6 照旧。

**推送**：本批改动随下一次提交推送（私有仓库，用户 2026-09-17 明示「你爱推就推」）。

---

## Task 3（`region` 包）: complete

**BASE** `5a0b27f` → 代码 `6d0de35`、报告与实验室证据 `d0338a1`、控制器收口 `（本次提交）`。
**评审方式**：**不派评审者** —— 控制器自读 diff（用户红线：代码量小时控制器自己读就是评审）。
**门禁**：`./mvnw -pl simos-map -am clean verify` 全绿（util 156 / map 71，BugInstance 0，Error 0）。

**交付**：`hex/HexVertex`、`region/{RegionId,RegionMeta,Region,RegionBoundary,RegionIndex}` + 4 个测试类。
实现者跑了 **13 轮变异，全红**，每轮 md5 自证字节不同、`COMPILATION ERROR count = 0`。

### 控制器收口：两处确证的缺口，当场修掉（不 park）

| # | 缺口 | 处理 |
|---|---|---|
| 1 | `new RegionBoundary(List.of(List.of()))`（**空环**）无守卫 ⇒ `canonicalRing` 里 `getFirst()` 抛 `NoSuchElementException`，异常类型说不清问题在哪 | 加显式守卫抛 IAE（"环不得为空"）+ 用例 `emptyRingIsRejected`。**理由：本类型直接从存档反序列化，畸形输入是正常到达路径** |
| 2 | 拓扑只覆盖"两簇互不相邻"，**没覆盖带洞的环**（实现者自己提出） | 加 `ringAroundAHoleGivesAnOuterRingAndAnInnerOne`：中心不在集合、6 邻居都在 ⇒ 2 条环、外 18 内 6、顶点 24、两环顶点集不相交。**计数可独立复算，是硬断言** |

**守卫自证（G13，控制器亲手做的一轮）**：删掉空环守卫 → 变异体 md5 `7f6cc4f…` ≠ 原件 `2dd09ea…`（字节自证）；
`COMPILATION ERROR count = 0`；`emptyRingIsRejected` **红**，红的理由是
`NoSuchElementException` 从 `List.getFirst()`（`RegionBoundary.java:132`）经构造器 `:46` 冒出 ——
**正是被删掉的那一行**。恢复后 md5 与原件逐字节相同，再跑 `clean verify` 全绿。
★ 这同时把实现者顾虑 #3 从「推导」升格为「**实测**」。

### 实现者 6 条顾虑的裁定

| # | 顾虑 | 裁定 | 依据 |
|---|---|---|---|
| 1 | 几何**没与老仓对拍**，偏移表靠推导 + 自洽用例 | ★ **控制器补测，顾虑消除** | 老仓 `TerrainGeometry.java:282-284` 实测为 `corner[i] = center + SIZE·(cos(60i−30), sin(60i−30))`、`:296-297` 为 `c1=d, c2=(d+1)%6`、`hexToPixel` 为 `(SIZE(√3q+√3/2·r), SIZE·3/2·r)`。⇒ 表由**实测公式 + 逐项代数**得到（Δu = (2/√3)cos(60i−30)、Δw = 2sin(60i−30)），**六项全吻合**。不再是"自由推导" |
| 2 | `boundaryIsIndependentOfInputSetIterationOrder` 判别力比名字弱（m3v-8 下保持绿）；补的大集合断言**时红时绿**故撤掉 | **接受**，不必改 | 核查：起点规范化真正由**冻结字面量**钉住 —— `singleHexRingHasSixVertices` 逐顶点写死 `(-1,-1),(-1,1),(0,2),(1,1),(1,-1),(0,-2)`，以及 `compactConstructorNormalizesStartDirectionAndRingOrder` 的"环起点不同"断言（`rotated(ringA,2)` 不归一就 ≠ canonical ⇒ **必红**）。那条用例的名字**没有过度承诺**（它证明的确实是"整条流水线与入参迭代序无关"）。★ 实现者**主动报告"我的断言时红时绿所以撤掉"**是甲族该有的行为，记一功 |
| 3 | 空环输入未测（推导为 `NoSuchElementException`） | **已实测并修掉** —— 见上表 #1 | — |
| 4 | 拓扑没覆盖带洞的环 | **已补** —— 见上表 #2 | — |
| 5 | ★ **`Region.hexes` 是 `Set.copyOf`，迭代序跨 JVM 运行会变**（实测 923_0 vs 330_0），任何从迭代序派生的序列化/哈希/变更集 key 都必须排序 | **接受为跨里程碑遗留**（见下） | 本模块**不做存储**，落点在实际写存档的那个任务 |
| 6 | 派单书那行变异不能 1:1 映射（规范化按 R-3d 住在紧凑构造器，`of` 自身不含排序） | **接受** | 实现者拆成 m3v-6（环表序）+ m3v-8（起点），绕向半由 m3v-7 覆盖，已在报告 §4 写明。**拆得对**：派单书那行确实与 R-3d 冲突 |

### 跨里程碑遗留（记裁定，不派工）

- **★ 序列化的迭代序**（顾虑 #5 实测）：`Region.hexes` 是 `Set.copyOf`，**同一内容跨 JVM 运行的迭代序不同**
  （实测首元素 `923_0` vs `330_0`）。`Region.equals` 不受影响（`Set.equals` 是内容判等，`boundary` 已规范化），
  但**任何写存档 / 算哈希 / 造变更集 key 的地方若顺着 `hexes()` 迭代序走，产物会跨运行漂移**。
  **落点**：真正做持久化的那个任务（MapSimos 不做存储，故不在 M2）。**要求：排序或用无序形式。**
  ★ 这与 Task 2 台账里那条残留同源（`TerrainCatalog` 保序的**跨进程**稳定性无人把守），两处合起来是一条：
  **"迭代序/字面量序"这件事在本项目里没有统一的守卫，各模块各自为政。** 留给 M6 或存储里程碑一并裁。

### 顺带更正

- 老仓 `cornerKey` 的注释自陈 "avoid floating-point drift"，但它**本身就是**浮点舍入
  （`Math.round(x*1000)`，且 `Math.cos(90°)` 是 `6.1e-17` 不是 0）—— 整数标签 `(u,w)` 才是那个注释想做而没做到的事。

## Task 4（`pathway` 包）派单

**BASE** `3022a19`。agentId `ae79eb601ed05c94e`（sonnet）。
派单要点：附 `task-4-brief.md`（唯一需求来源，不要去读整份计划）；补齐 brief 不知道的接口事实
（`HexCoord` 的 `toString()`=`"q_r"`/`parse`/先 q 后 r；`HexDirection.ALL` 是 `List` 不是数组；
`HexVertex` 已存在且本任务用不到；`pathway` 与 `region` 无依赖）；
把 **R-48-f（EdgeRef/PathwayId 三件套 + 冻结字面量）**、**R-48-c（`PathwayGroup` 照老仓、静默填空改抛、
`PropertyDef` 不许删）** 再点一遍；★ **指它复用 `task-3-evidence/run.sh`+`mutate.py` 的实验室模板**，
不要长第二套装置（用户红线：装置不得压过代码）。

**框架期口径**：不派评审者；实现者的**变异自证就是测试**；控制器收到报告后自读 diff。

## Task 4（`pathway` 包）: complete

**代码** `18d755c`（9 文件 / +900 行）→ **收口** `bf714bf`；**报告 + 证据** `fe241ff`。
`./mvnw clean verify` 全绿：simos-map **105**（原 102，+3）、simos-util 156、simos-core 15，
Checkstyle 0、SpotBugs `BugInstance size is 0`。6 轮变异全红、`COMPILATION ERROR count = 0`、Errors 恒为 0。

### 控制器自读 diff 的产出（框架期不派评审者，自读即评审）

| # | 发现 | 处置 | 代价 |
|---|---|---|---|
| 1 | ★ **`start()`/`end()` 的校验只盖头尾**：原先只在头看 `(0,1)`、在尾看 `(n-1,n-2)` 两对相邻边，三种畸形输入**静默产出看起来合理的端点** —— 中途分叉 `[(A,B),(B,C),(B,D)]`（B 度 3）⇒ 端点 A 与 D；中段断开 ⇒ 端点 A 与 Z；同一 `[AB, AB]` ⇒ 被当成"长度为 2 的闭环"、两端同取锚 | **先写三条用例、先跑红**（`Errors: 0`，红的理由是 `Expecting code to raise a throwable`，即端点照算不误 —— 不是编译错、不是抛错），再改代码转绿：取端点前整条走一遍（查重复边、查点数度 ≤ 2、逐条查相接）。`isClosed()`/`freeEnd()` 并入 `endpoint()`，**不留够不着的分支** | — |
| 2 | `EdgeTags` 类注释自相矛盾：第 8 行写"pathway **组**"、第 14 行写"**pathwayId 的裸值**" | **裁：外层 key 是 `PathwayId`（线的实例），不是 `groupId`** —— spec §5.4 的三层是 组定义 / 线的实例 / 边标注，本表属第二层。老仓 `Map<Integer, List<String>>`（方向 → 组名）是**第三种形态**。组名还表达不了"同一条边同时有某条河与某条路"。已改注释并写明理由 | — |

### 报告 §6 的 6 条顾虑 + §5 的 6 条"没能验证的事"：逐条裁定

| # | 项 | 裁定 |
|---|---|---|
| §6.1 | `PathwayGroupTest.java` 不在 brief 的 Files 段 | **追认** —— R-48-c 要求静默填空改抛 + G13 要求每条守卫有故意违规用例；不建文件则那些守卫等于装饰 |
| §6.2 | ★ **给 Task 6 的硬提醒**：`EdgeTags.byPathway` / `Pathway.props` 是保序映射，`PathwayId`/`EdgeRef` 的 `toString()` 是变更集 String key，**任何集合/哈希/序列化都不得用 `Map.copyOf`/`HashMap`**；`Pathway.props` 的值是 `Object`，`FieldDelta` 需要"值相等"口径 | **接受，随 Task 6 派单原样带走**（见下） |
| §6.3 | `PathwayGroup.color` 的 `#RRGGBB` 校验是本任务加的（老仓无校验、静默填 `#808080`） | **维持**。与 `TerrainType` 同口径；真出现 `#RGB`/alpha 输入源再裁，**别当噪音改掉** |
| §6.4 | `PropertyDef.type` 只拒空白、不造白名单 | **维持**。老仓只在注释里列过 `int/float/bool/string`，没有一处代码是判据，造了就是**替将来的词表做决定** |
| §6.5 | `Pathway.name`/`description` 有意不校验、`props` 允许 null 值 | **维持**（与 `TerrainType.description` 同口径）。Task 6 若要求"值不得为 null"再说，改起来是一行 |
| §6.6 | ★ `start()/end()` 的语义「空链抛 ISE、闭环取最小 hex 锚、**断链（顶点被 3 条以上边共享，或重复边）在访问端点时抛**」 | **前两条确认；第三条当时是"声称的口径"，代码里没有** —— 报告把**想要**的语义写成了**已有**的行为（乙族）。已由上面发现 #1 补成实测 |
| §5.1 | 未与老仓 GSimulator 逐边对拍（`EdgeRef` 取坐标序、老仓 `edgeKey` 取串序） | **接受为推导 + 迁移遗留**。实测钉子 `(10,0)/(2,0) → "2_0|10_0"` 在 `toStringMatchesFrozenLiteral` 里。**老仓串序 vs 新仓坐标序的分叉写进迁移清单** |
| §5.2 | m4v-4 的 `Map.copyOf` 迭代序按 JVM 加盐，理论上可能"恰好落回插入序 ⇒ 假绿" | **接受**，并入 Task 3 那条**跨里程碑遗留**（迭代序无统一守卫）。真正的护栏是那份**冻结字面量**，不是"永远不同" |
| §5.3 | `start()/end()` 的边界只有正向用例、无删守卫变异 | **已由发现 #1 消解**：三条新用例正是该守卫的删守卫证据（改前红、改后绿，同一条用例两侧都测过）。另两条（空链、闭环锚）的判别力为**推导**：删掉 `isEmpty` 守卫会退化成 `IndexOutOfBoundsException`（不是 ISE ⇒ 用例仍红），删掉锚分支会返回 `(1,-1)`（≠ `(0,0)` ⇒ 红） |
| §5.4 | `PathwayGroupTest` 5 条、`EdgeTagsTest` 其余 3 条无变异轮 | **不补轮**（用户红线：装置不得压过代码）。这些都是"构造器守卫 + `assertThatThrownBy`"的**正向抛**用例，且被守护字段在构造器内**无下游解引用** ⇒ 形态 2（NPE 消息同名遮蔽）够不着。判别力是**推导**，不是实测 —— **按推导记录** |
| §5.5 | `EdgeTags` 内层保序未独立变异 | **同上**。与 m4v-4 同形，那条已实测外层会变序 |
| §5.6 | 未测性能 | **不测**（`verifySimpleChain` 是 O(n)，边数规模远未到） |
| §3 备注 | m4v-3 只红在第一半断言、161 行够不着 ⇒ 补 m4v-6 精确打第二半 | **确认补救成立**：不是删断言、不是改测试迎合，而是**加一轮能打到那半条的变异**。两条合起来覆盖 `idsArePersistedNotDerived` |

### 留给 Task 6 的硬提醒（从 §6.2 原样带走）

1. **保序**：`EdgeTags.byPathway`、`Pathway.props` 是 `LinkedHashMap` 包裹的保序映射；`PathwayId`/`EdgeRef` 的
   `toString()` 是变更集 String key。**任何拿这些 key 做集合/哈希/序列化的地方都不得改成 `Map.copyOf`/`HashMap`**
   —— m4v-4 **实测**顺序会变，变更集内容会跨运行漂移。
2. **值相等口径**：`Pathway.props` 的值是 `Object`（老仓即如此），`FieldDelta` 落到它上面时需要一条"值相等"判据。

## Task 5（`map` 包：`HexCell`/`City`/`GameMap` + `GenerationSpec` 骨架）派单

**BASE** `07a49f0`。agentId `a77857d16ba08eb21`（sonnet）。
派单要点：附 `task-5-brief.md`（唯一需求来源）；补齐 brief 不知道的接口事实
（`HexCoord`/`RegionId` 三件套已齐、`Region.of` 会替你重算边界、`RegionIndex.of` 的签名、
`Region` 的 `boundary` 是组件而非派生）；把 **R-48-d/e/f** 再点一遍；★ **顺序纪律**（Task 4 §6.2
原样带走，含"冻结字面量钉插入序、不许跟源 map 比"的理由）；★ **指它复用 `task-4-evidence/` 的实验室模板**
（拷成 `task-5-evidence/`，只改 TARGET 表与路径）。

**框架期口径**：不派评审者；实现者的**变异自证就是测试**；控制器收到报告后自读 diff。

## Task 6 派单要点（预读 brief 时先记下，免得丢）

1. **R-48-j（新增）**：`GameMap` 的 7 个可变更组件里，`terrainTypes`（`Map<String, TerrainType>`）与
   `pathwayGroups`（`Map<String, PathwayGroup>`）的 key **本来就是 `String`** ⇒ `keyOf` 是**恒等**、
   `apply` 侧**不得**对它做 parse。brief 那句"key 是 `HexCoord`/`RegionId`/`PathwayId`/`CityId`/`EdgeRef`"
   漏了这两个，照抄会多写两个假的 parse 或漏掉两个组件。
2. ★ **因果链**（不是两条独立纪律）：`between` 是**顺着 `GameMap` 的 map 迭代序**读出来造 `Upsert.entries`
   的 ⇒ Task 5 的"保序不可变"纪律是 `between` 确定性的**前提**。`GameMap.hexes` 若成了 `HashMap`，
   `between` 的产物会跨运行漂移。
3. **值的相等就用 `equals`**（记录的内容判等，与 map 迭代序无关）。**不要**为了"顺序也变了"改用序列化串
   比对 —— 顺序变了而内容没变**不是状态变更**，那正是"第二条可写路径"那类病的形态。（真需要感知顺序的地方，
   是 `Pathway.edges` 这种 `List` 组件，`List.equals` 本就有序。）

## Task 5 关账（控制器自读 diff = 评审；框架期不派评审者）

交付：`1614000`（实现，8 文件 / +1057）、`3ef14b1`（报告 + 变异证据）、`f10d33b`（控制器注释修正）。
门禁 `clean verify` 全绿：simos-map **132** / simos-util 156 / simos-core 15，Checkstyle 0，
SpotBugs `BugInstance size is 0`。变异 7 轮（m5v-1..7）。

| 项 | 裁定 |
|---|---|
| ★ 三处注释引用的数字在存档里查无（`17%`/`5 次`、`0~17%`、`43%`/`13 次`） | 已修 `f10d33b`。★ **报告文件本身是准确的**（§四 写 `10/30（33%）`，并把 `13/30` 括注为第一次探测），对不上的是**注释**与**手工回报**。教训：**hand-back 摘要是最不可靠的一个面，报告文件才权威** |
| ★ m5v-3 日志里 base 与 mutated **同键集却不同序**（同一次 JVM） | **已查明，是实测不是推测**：`Map.copyOf` 的迭代序 = 散列槽位序；两键**撞槽**时走线性探测，**相对次序随插入序**。故同 JVM 内两次 `Map.copyOf`、键集相同而插入序不同 ⇒ 序可不同（record 键集实测 **100/100**；String 键集 0/100 只是该 JVM 的盐下没撞槽）。跨 JVM 的盐再叠一层 = 三种不稳定叠加。`GameMap` 那句「迭代序按哈希表散开，同一份数据因此会产出不同字节」**实测无误，代码未动** |
| 由此否掉的一条注释理由 | `CityTest.propsPreservesInsertionOrder` 原称"再调一次、比两次结果"钉不住 `Map.copyOf`，**因为**同 JVM 内顺序也稳定 —— 该理由为假。真理由是 `props()` 两次返回**同一实例**、那种写法恒绿。已改（`f10d33b`） |
| `City.region` 可为 null | **追认**。读 `RegionIndex.regionOf` 实证：无归属的格返回 null ⇒「不在任何区域内」是合法状态 |
| `CityTest.java` 不在 brief 的 Files 段 | **追认** —— 6 键保序钉子需要这个家 |
| `GenerationSpec` / `CityId` 三件套 / `City` 空值检查无变异轮 | **不补轮**（同 Task 4 §5.4 口径）：都是"构造器守卫 + `assertThatThrownBy`"的**正向抛**用例，被守护字段在构造器内**无下游解引用** ⇒ 形态 2（NPE 消息同名遮蔽）够不着。判别力记**推导**，不记实测 |
| spec §7.3 / §7.4 的豁免集 | **维持** §九 的 `Set.of("spec")`；随 Task 6/7 带走 |
| m5v-4（`empty()` 改用 `Map.copyOf`）**不可观测** | **接受为诚实的甲族报告**：`empty()` 的 7 个 map 全空，只有一种迭代序 ⇒ 该行本就打不响。等价落点由 m5v-3 承担（同类变异在**非空** map 上实测会响） |
| SpotBugs 首次门禁 7 条 `EI_EXPOSE_REP` | 实现者自查出并修：冻结必须写在**赋值处**，藏进私有 helper 时 SpotBugs 判不出。改前/改后原始输出均存档 |
| m5v-7 首轮**假绿** | 实现者自查出，30 次独立 JVM 探针根因定为**键数太少**；夹具加宽到 4 键（`GameMap`）/ 6 键（`City.props`）后重跑转红。**本任务最有价值的一条** |

★ **项目级教训（新增，建议进 CLAUDE.md 形态族）**：
**保序钉子的判别力取决于键数** —— 用冻结字面量钉 `Map.copyOf` 时，键太少会**假绿**
（3 键实测 7%~40% 恰好落回插入序，4~6 键 0/30）。推论：夹具键数**要当场量**，
不能凭"看起来不像巧合"。这把 Task 3 的跨里程碑遗留（迭代序无统一守卫）从"怀疑"变成
"有量化机制的已知项"。

★ **跨里程碑补充（承接 Task 3 那条）**：迭代序不稳有三个独立来源 —— ① 跨 JVM 的盐，
② 撞槽时相对次序随插入序，③ 序列化层（`Region.hexes` / `TerrainCatalog` 的序）尚无守卫。
三条都指向同一结论：**落盘序必须由 `LinkedHashMap` 纪律保证，不能寄望于"哈希恰好稳定"**。

## Task 6 派单前的扫描发现（补充）

★ **R-48-k：`emptyDiffStillEntersApply` 按 brief 的写法打不响 `isEmpty()` 那条变异。**
变异表要求 `isEmpty()` 改成 `hexes.changed()`（只看一个组件）时必须红，且指定红的来自
`emptyDiffStillEntersApply`。但 brief 给该用例的定义是"空 diff 也必须能被 apply 且返回 base" ——
在**全 Unchanged** 时 `hexes.changed()` 恰好也是 `false`，`isEmpty()` 仍然返回 `true`，**结果正确、照样绿**。
⇒ 要让它红，必须补一条断言：**只改 `edges`、`hexes` 未变 ⇒ `isEmpty()` 为 `false`**。
这条正好是 **L1 第四个成因的正面钉子**（老仓 `isEmpty()` 排除 edges ⇒"只改了一条边"产生空 diff、
根本不进 apply）。已写进派单。

★ **R-48-f 已结清（当场核过，不要再去补三件套）**：`git grep` 实测五个 key 类型
（`HexCoord`/`RegionId`/`PathwayId`/`EdgeRef`/`CityId`）**都已有**裸值 `toString()` + `static parse(String)`，
分别由 Task 1/3/4/4/5 交付。

★ **对 brief 正文的修正（控制器裁定）**：`FieldDelta.Remove.keys` 的 `Set.copyOf` **改为保序**
（`Collections.unmodifiableSet(new LinkedHashSet<>(...))`）。理由与 `Map.copyOf` 同一条且已实测：
`Set.copyOf` 同样走 `ImmutableCollections`、序不是内容的纯函数（撞槽时随插入序）⇒ 变更集的字节不稳定。
`Upsert.entries` 同理用 `LinkedHashMap` 包裹。

★ **往返夹具的 `spec` 必须 base 与 target 相同**：`spec` 不进变更集、`apply` 从 base 取（R-48-e），
夹具若让它俩分叉，`applyRebuildsTargetExactly` 会红，而那是设计如此、不是缺陷。

**BASE `11a8de5`**（Task 6 派单前的最后提交）。
★ 原记 `84e38d2` —— 那是**写上面这段扫描之前的**提交。按本台账自订的口径（以实现者的父提交为 BASE，见 Task 5 那段），
实现者首笔 `107e9d9` 的父提交是 `11a8de5`。差这一笔会把**控制器自己的台账提交**卷进评审包。

## Task 6 关账（控制器自读 diff = 评审；框架期不派评审者）

交付：`107e9d9`（实现，3 文件 / +893）、`db6a362`（报告 + 变异证据）、`96e8346`（补 `Patch` 变体）、
`342917b` + `3bf7ee2`（报告与证据更新）。门禁 `clean verify` 全绿：simos-map **152** / simos-util 156 /
simos-core 15，Checkstyle 0，SpotBugs `BugInstance size is 0`。`MapChangeSetTest` **20 例**。
变异 **7 轮**（m6v-1..7），其中 m6v-6/7 是为本任务新增的 `Patch` 面配的自证。

★ **报告的变异表与原始 surefire 输出逐轮相符**：我按另一条路径从 `rounds/m6v-*.kept` 重新数过红的例数
（9/3/4/4/6/3/2），与报告表格逐行对得上；这与 Task 5 那次"注释数字在存档里查无"形成对照——**本任务的报告面可信**。

| 项 | 裁定 |
|---|---|
| ★ brief 的三变体 `FieldDelta` 表达不了"同一组件又增又删"（`diff` 当场抛 `UnsupportedOperationException`） | **当场改设计**：新增第四条变体 `Patch(Upsert, Remove)`（**嵌套**，不新开第三份展开）。理由走铁律 5：`between` 是派生函数，往返必须对**任意** (b,t) 成立，而 Task 7 的反射枚举天生会造出"同组件又增又删"。语义定**先删后增**、重叠时**增胜**、**不为重叠写守卫**（R-48-e）；`rebuild` 的 `Patch` 那一路**递归复用**已有两路（重实现一遍就有了跟它们分叉的可能）。**只加两轮变异**，不铺开 |
| 修复轮删掉 `diff` 的 `component` 参数 | **接受**。读 diff 实证：它只用于拼混合情形的异常消息，混合情形改走 `Patch` 后无消息可拼 ⇒ 死参数。Javadoc 已写明 |
| `Patch` 重叠时"增胜"**无用例** | **接受为有意留白**（R-48-e）：`between` 是唯一生产者、产不出重叠；为不存在的输入写用例正是"为不存在的世界写代码"。已写进 `FieldDelta.Patch` 类注释 |
| `Patch` 的 null 守卫抛 NPE，`Upsert`/`Remove` 抛 IAE | **不动**（纯外观差异）。用例已按形态 2 **精确匹配**消息钉住；两条守卫从唯一生产者走不到 |
| 原"当场抛"用例被删（20 例的账） | **对得上**：删 1 条已废行为，加 3 条 `Patch` 用例 + `Patch` 的不可变/null 断言，18 → 20 |
| ★ **R-48-k 兑现** | m6v-4 红在 `emptyDiffStillEntersApply:**444**`（**不是** 433 行）——正是派单前补的那条"只改 edges ⇒ 变更集不得为空"。预判成立 |
| m6v-3 的两条是 **Errors 非 Failures**（NPE） | **接受**：变异是「全等时 `between` 返回 `null`」，NPE 是其**因果后果**（用例解引用了 null）；同一轮里 `betweenIdenticalIsAllUnchanged:240` 用 `assertThat(cs).isNotNull()` 正面红了。报告自己就如实标了 NPE，没伪装成断言红 |
| `Patch` 的保序钉子键数 | 沿用 Task 5 量出的口径：`deltasPreserveInsertionOrder` 用 **4 键**，不用 3 |

★ **交给 Task 7 的硬提醒**：`FieldDelta` 现在是**四条**变体（不是三条）——反射枚举与往返用例都要知道
`Patch` 在"同组件又增又删"时出现；`spec` 仍是有意不进变更集的那一个（`EXCLUDED_FROM_CHANGE_SET = Set.of("spec")`）。

## Task 7 关账（控制器自读 diff = 评审；框架期不派评审者）

交付：`c83058c`（`RoundTripComponentsTest`，5 用例）、`28f62d6`（报告 + 9 轮证据）、`f0ddce0`（控制器当场修类注释）。
门禁 `clean verify` 全绿：simos-map **157**（152 旧 + 5 新）/ simos-util 156 / simos-core 15，Checkstyle 0，
`BugInstance size is 0`，BUILD SUCCESS。`MapChangeSet.java` **反射未查出缺口 ⇒ 一字未改**（唯一改动是 `f0ddce0` 的注释，控制器做）。
变异 **9 轮**（m7v-1..9）：**m7v-1/m7v-8 撞编译期、如实作废**；m7v-9 是实现者为回答"预测不符"自加的隔离轮。

★ 复核方式：我从 `m7v-*.kept` 独立取每轮的自证头（干净世界 / **md5 原件↔变异体的实际值** / 无旁文件改动 /
`COMPILATION ERROR count`）与红点行，与报告逐条对上。九轮都不是"OK"二字了事。

| 项 | 裁定 |
|---|---|
| ★ 交付物比 brief 强的一处 | `specIsDeliberatelyExcludedFromTheChangeSet` 第三条断言「变更集组件 ∪ 豁免集 必须恰好覆盖 `GameMap` 全部组件」——**brief 草图没有**。一个断言同时封两向（多一项 = 有组件被"豁免"掉，少一项 = 有组件谁都没管）。保留 |
| V3（加 `foo` 组件）落在**测试期 · Error** | **接受，且正是要的那一层**。变异体把 4 个构造点一并改到编译得过（`GameMap`/`MapChangeSet`/两个测试）——即现实中"加了字段、改了构造、忘了变更集"的形态 ⇒ `mutate` 的 default 抛「未登记的组件: foo」。同轮 `specIsDeliberatelyExcludedFromTheChangeSet:144` 也红并点出缺的是 `foo`；Task 5 的两条组件数钉子同轮也响 |
| ★ V1/V2（"从变更集删组件"）**被 javac 接住**（4 处 `cannot find symbol`）⇒ 该轮作废、不计测试期判别力 | **接受**。★ **项目级事实（新）**：**铁律 5 的漂移在本仓有两道网** —— javac 管"组件的增删"（`apply` 显式构造 `GameMap`、测试显式调 7 个访问器），本测试管"比较漏了"（编译得过、静默丢）。故 brief 表里 V1/V2 的**字面方向到不了断言**；能推到测试期的等价形态是 m7v-2 的"恒定 `Unchanged` 占位访问器" |
| ★ V2/V4 红在**第 ① 条**断言，不是我在派单里预测的第 ② 条 | **我预测错了，实现者如实报了出来 —— 算它对**。单组件夹具下 `isEmpty()` 为假 ⟺ 该组件 `changed()`，故 ① 必然先响。**裁定：不改夹具** —— 把 base/target 改成多组件不同，会把 ① 从"漏了它 ⇒ 变更集整个为空"（GSimulator 病根的正面钉子）稀释成"某个组件变了"；**为迁就我的预测而牺牲钉子，是拿预测当目标**。② 的独立判别力已由 **m7v-9 实测隔离**（把 `changedOf` 的 `edges` 映射复制粘贴成 `pathwayGroups` ⇒ 只有 ② 红、① 绿），**实测强于构造** |
| 豁免口的固有边界（同时改豁免集与其期望值的共谋式改动拦不住） | **接受为已声明的限界**。实现者自己写明"以备后人误读为绝对"——形态 5 的正当用法 |
| `MapChangeSet.java` 类注释把把守者指成 `MapChangeSetTest` | **当场修**（`f0ddce0`，用户裁定「已确证的发现，若修复比它的描述还短，在发现的那一刻修掉」）。同处那句「6 个字段漂移出去」**经查 spec §7.3 属实**（四者 + `gridSize`/`hexOrientation`）⇒ **没改数**，只补半句口径说明 |

★ **我这次差点犯的错（必须记）**：看到"6 个"时我的第一反应是「CLAUDE.md 铁律 5 说四个，这里错了，改掉」——
**那是按记忆去"纠正"一个已经正确的数**，正是乙族的形态。查 spec §7.3 才知 4 与 6 是**两个口径**
（6 = 全部漂移字段；4 = 其中"该进变更集而没进"的那一类）。那半句口径说明已写进注释，免得后人踩同一个坑。

## Task 8 派单前的扫描（控制器预做，Task 7 运行期间；结论先落 `/tmp/task-8-scan.md`）

| 配对 | 一方产出 → 另一方消费 | 发现 |
|---|---|---|
| **Task 5 → Task 8** | `GenerationSpec` 骨架（只 `seed`）；`GameMapTest` 第 187 行 `isEqualTo(new GenerationSpec(7L))` | ★ **真冲突**：扩成 9 组件后该行**编译不过**，而 Task 8 的 Files 段**没列 `GameMapTest.java`** ⇒ **R-48-p**（见下） |
| Task 7 → Task 8 | Task 7 反射枚举 `GameMap` 的 8 个组件 + 豁免集 `Set.of("spec")` | **无冲突**：Task 8 加的是 `GenerationSpec` 的**内部**组件，`GameMap` 的组件数与名**不变** ⇒ 8 vs 7 不变、豁免集不变 |
| Task 6 → Task 8 | `apply` 里的 `base.spec()`（R-48-e 要求核实有无 null 兜底） | **已核实、无需改动**：`MapChangeSet.java:90` 是 `base.spec()`，无兜底 ⇒ Task 8 那份 Inspect 的结论就是"无需改动"，**不许为凑 diff 动它** |
| Task 8 自洽 | Step 2 校验 `mainRidges ∈ [1,2]` vs 用例 `mainRidgesFiveThrows` + `mainRidgesTwoIsAccepted` | **一致**（R-48-a 已修过原稿"只有下界"） |
| Task 8 外部依赖 | Step 1 要读 `~/DevMosire/GSimulator` 的 `MapGenerator.java:105` | **当场核过**：仓库在；真实路径是 `gsim-map/src/main/java/com/gsim/map/service/MapGenerator.java`（brief 只给裸文件名）⇒ 派单带全路径 |
| Task 8 外部依赖 | Step 3 的 `noTerrainHeightThresholds` 要 `TerrainCatalog.KEYS` | **当场核过**：存在（`public static final List<String>`，7 项、U1 定的序） |
| Task 8 Step 4 变异 | 「加回 `worldId` 组件」要让 `noWorldIdNoCoastRoughness` 红 | ★ **与 Task 7 V3 同型的坑**：往 record 加组件先撞**编译期**（构造点全炸）⇒ 要么如实记"编译期接住、该轮作废"，要么把构造点一起改到编译得过。同一条裁定随派单带走 |

**R-48-p（新裁定）**：Task 8 **允许且必须**改 `GameMapTest.java`（brief 的 Files 段漏了它）。
- **不采用**"留一个 1 参便捷构造器"的解法：那是**静默填 8 个默认值**的第二构造路径，与本任务
  "删装饰形参、不静默夹取"的立意正相反。
- **采用**：删掉第 187 行；把它想说的话（"`defaults(seed)` 收种子、只让 seed 变，其余是规范默认值"）
  **移进 Task 8 自己的 `GenerationSpecTest`**，写成**更强**的形态：`defaults(7L)` 与 `defaults(8L)`
  **除 seed 外逐组件相等** —— 这才是原来那句 `isEqualTo(new GenerationSpec(7L))` 的真意
  （骨架只有一个组件时它只能这么写）。第 186/188 行保留；第 184 行那句 Javadoc（"Task 8 才扩参数面"）按现状改写。

## Task 8 关账（控制器自读 diff = 评审；框架期不派评审者）

交付：`d4d2857`（实现）、`d19747d`（报告 + 12 轮变异证据）。
自读范围：4 个源文件 + `GenerationSpecTest`（18 条）逐行读过；R-48-p 的 `GameMapTest` hunk 核过；
m8v-3/4/7 的自证头（干净世界 / 原件↔变异体实际 md5 / `COMPILATION ERROR count = 0`）与门禁日志尾抽验过，
与报告逐条对得上。**关账。**

| 项 | 裁定 |
|---|---|
| ★ 简报草图偏离：Step 2 的 `if (fragments < 1) throw` 被删 | **接受**。它被 `requireNonNegativeRemaining` 整段包住（`fragments < 2` ⇒ 剩余必为负，`secondaryCountFloor=2` 与总数无关）；留两句会得到两条将来会分叉的同义抛点。实现者按 CLAUDE.md"草图不是权威"如实报告，做法正确 |
| ★ 1 ulp：`defaults()` 的 `baseSeaLevel` = 字面量 `0.2025`，GSimulator 算式值 `0.20249999999999999` | **接受为有意**（冻结常量 > 每次构造重算浮点式）。**跨 Task 10 约束**：不许按"与 GSimulator 算式逐位相同"对拍；要复现就用 `defaults()` |
| U1 判定：`NoiseBands` 16 字段全为形状参数 | **接受**。判据（塑造高度值 vs 给高度分类）+ 逐组 `file:line` 依据齐；两条判据独立自证（m8v-4 红形态、m8v-7 红名字）。`classify()` 那批阈值一个没搬 |
| `RidgeParams` 33 组件不设守卫；`secondaryCountDivisor=0` 靠 `ArithmeticException` | **接受**（取负是"另一种分布"；除法当场响）。**跨 Task 10 硬提醒**：若把那条除法改成浮点或包 `Math.max`，"响声"就没了，必须补构造期守卫 |
| `warpFreq/warpAmplitude` 暂居 `NoiseBands`；相位平移 `+100/+300/+500`、`+77` 留在生成器侧 | **暂定接受**。Task 10 若判定域扭曲属"坐标变换"，搬家成本一行；相位平移**不许**顺手提成字段（报告 §3.1 理由已收进 `NoiseBands` 类注释） |
| `MapChangeSet.java` Inspect 结论"无需改动" | **属实**：`:90` 是 `base.spec()`，无 null 兜底；该文件零改动（`git show --stat` 可证）。`specIsNeverNullAfterTask8` 的身份是守卫而非收紧证明 —— 与 R-48-e 一致 |
| `specIsNeverNullAfterTask8` 断言 `GameMap.empty().spec() == defaults(0L)` | **接受**：`defaults(0L)` 是 `empty()` 的规范种子约定（R-48-e）；逐组件相等比单 `isNotNull` 强 |

**下一步**：Task 9（`TerrainClassifier`）派单前扫描 → 派单。

## Task 9 派单前的扫描（控制器预做）

| 配对 | 一方产出 → 另一方消费 | 发现 |
|---|---|---|
| **Task 2 → Task 9** | `TerrainCatalog.KEYS`（7 项、高度升序）/ `defaults()`（升序 `LinkedHashMap`）/ `of(key)`；`TerrainType` 带**左闭右开**、构造期校验 `0 <= min < max <= 1` | ★ **真缺口**：带右开 ⇒ **`h == 1.0` 不属于任何带**（最高带 `plateau_mountains [0.90, 1.00)`），而 `classify` 是总函数 ⇒ **R-9a**（见下）。其余核过：KEYS 序 = 高度升序、`defaults()` 同序、`of(key)` 对未知 key 抛 —— 遍历与判例都成立 |
| Task 8 → Task 9 | 同在 `generate` 包；Task 8 已把高度阈值清出参数面（U1） | **无冲突**：文件不相交；"分类器不许有自己的数"与 Task 8 的 `noTerrainHeightThresholds` 是同一军令的两端，互相加强 |
| Task 9 → Task 10 | `classify(height, humidity, temperature)`（Task 10 的 `MapGenerator` 调它） | **无冲突，两条注记**：(1) 湿度阈值由 Task 9 唯一持有，Task 10 若要沙漠出现需让湿度噪声下探到阈值以下 —— 但 Task 10 的用例不要求沙漠出现，**不构成硬约束**；(2) temperature 本任务收下不用 ⇒ Task 10 的"气候"不得假定它已被消费 |
| Task 2 用例 → Task 9 变异轮 2 | `TerrainCatalogTest` 的钉子：最低带起于 `0.0`、最高带止于 `1.0`、相邻带共享边界 —— **数值全部取自类型自身，无边界字面量** | ★ **变异轮 2 可构造、且不污染词表自己的用例**：挪一条**中间**共享边界并**成对改**（如 ocean/plains 的 `0.30 → 0.32`：改 `ocean.maxHeight` 与 `plains.minHeight` 两处）⇒ 划分/端点/连续性三条仍绿，而"分类器写死旧阈值"只在 `classifierFollowsCatalogBands` 上红（**R-9c**） |
| Task 9 自洽 | Step 2 的 8 条用例 vs Step 1 的语义（沙漠门 / 总函数 / 查表） | **基本一致**，两处需补：`h == 1.0` 无任何用例钉住（→ R-9a 的钉子）；变异行 4"末尾加 default 兜底"在 R-9a 的形态下可能不可达/无判别力（→ **R-9d**）。desert 门与 `desertBandFallsBackToPlainsWhenHumid` 一致；`classifierFollowsCatalogBands` 采样点由被遍历带**现算**（min/中点/`nextDown(max)`），与"无私有数"同构 |
| Task 9 外部依赖 | — | **无**：不读 GSimulator、无外部路径 |

**BASE `3d3a598`**（Task 9 派单前的最后提交；实现者首笔提交的父提交应是它）。已派单（glm 路由）。

**R-9a（补 brief 的缺口）**：落带算法 = **升序找第一条 `height < maxHeight` 的带；一条都没有（`height >= 1.0`）⇒ 取最高带**。
- 理由：带是 `[0,1]` 的划分但**右开**，`h == 1.0` 是唯一缺口；`classifyIsTotal` 要求不抛。
- **不采用夹取**（`Math.max(0, Math.min(1, height))`）：顶端**仍要补一次退末带**（`1.0 < 1.00` 为假），等于两处约定；且引入 `0.0/1.0` 字面量，与"不许有自己的数"擦边。退末带**零字面量、零算术**。
- 副产物（结构性、不承诺语义）：负值天然落最低带。
- **钉子**：`classifyIsTotal` 显式加 `classify(1.0, …)`（不抛、= 最高带 key）与一个域外负值（不抛、= 最低带 key）。
- 代价若错：一句约定 + 一条断言，一行改回。

**R-9c（变异轮 2 的构造细则）**：挪边界必须**成对改**共享边界、**只挪中间边界**（不与 `TerrainCatalogTest` 的 0.0/1.0 端点相干）；目标红点 = `classifierFollowsCatalogBands`。若同轮别条用例也红，如实列出并标明因果（两套词表分叉），不算污染。

**R-9d（变异行 4 的等价形态）**：R-9a 之后"末尾 default 兜底"可能**不可达**（总函数的正常路径就是退末带）。等价且能响的形态：**把"退末带"改成"全不中 ⇒ 返回常量 `plains`"** —— 这才测出"总函数的来源是结构、不是兜底常量"。红点落在 R-9a 的 `1.0` 钉子（`classifyIsTotal`）或 `plateauMountainsIsHighestBand`，以实测为准、报告写实际红处。

## Task 9 关账（控制器自读 diff = 评审；框架期不派评审者）

交付：`333fd79`（实现，2 文件 / +306）、`a1bb5ef`（报告 + 6 轮变异证据，10 文件）。
自读范围：`TerrainClassifier`（74 行）+ `TerrainClassifierTest`（232 行，9 例）逐行读过；
6 轮 `.kept` 的自证头逐轮核过（干净世界 md5 清单 / 改前全绿 `Tests run: 156 + 184, Failures 0` /
`COMPILATION ERROR count = 0` 改前改后各一行 / 原件 md5 `39211b64…` 六轮恒同，m9v-2 另含 `TerrainCatalog`
原件 `091e92cc…`↔变异体成对声明 / 红点清单逐轮与报告对上）。
★ **工作树落盘 md5 = `39211b64…` = 证据里的"原件"** ⇒ 通过评审的就是每个变异轮的同一份起点。
门禁日志尾：`BUILD SUCCESS`，spotbugs `BugInstance size is 0`，surefire 156 / 184 / 15。**关账。**

| 项 | 裁定 |
|---|---|
| brief 之外的夹具自证用例 `humidityFixturesStraddleTheDesertThreshold` | **接受**（9 例 > brief 的 8 例）。它把"低湿度 < 阈值 ≤ 中性湿度"变成被验的断言——阈值被挪走时先红在这里并指明失配的半边，正是派单时要求的"夹具与阈值相容"的钉子形态 |
| ★ R-9d 实测红在 `classifyIsTotal:215`（brief 字面写的是 `classifyNeverReturnsUnknownKey`） | **实现者如实报出、分析正确**：兜底常量 `plains ∈ KEYS` ⇒ "返回值恒在 KEYS 内"对它结构性无判别力，判别力只能来自"1.0 必须落最高带"（R-9a 的钉子）。**这正是 R-9a 当初被裁定的原因**；m9v-4 实测"只有一条红"佐证"不兜底半"与"1.0 钉子"是两件事 |
| m9v-1/3/6 的附带红点 | **接受**：同一处语义被改、多条断言同时看见（沙漠/ocean 的产出路径、门、带定位是同一事实的几个侧面）；逐条已标因果，非装置杂音 |
| 装置首轮把 Maven `-rf` 续跑提示误收进红点清单 | **已修过滤并重跑全部 6 轮**（不留"事后补记"）；重跑后的 6 轮自证头我逐轮核过 |
| `DESERT_MAX_HUMIDITY = 0.35` 为 **public** | **接受**：测试用它做夹逼自证，Task 10 的气候侧若要与"沙漠出现面"对齐也应引用它而非重抄。0.35 已在 Javadoc 与报告双处声明"本任务新定、非来自 GSimulator" |
| m9v-5 的红是 **Error**（抛 IAE）而非 Failure | **接受**：变异即"对某段输入抛异常"，`classifyIsTotal:216` 的直接调用把异常抛成 Error 是因果后果；报告如实写了异常消息，未伪装成断言红 |
| 每次 `classify` 重建词表（`defaults()` 每次 new） | **接受为 YAGNI**。**跨 Task 10 注记**：若逐格调用实测有压力，缓存加在**调用方或词表侧**并自带护栏，别在分类器里开状态 |
| 类 Javadoc 把 `{@link MapGenerator}` 改 `{@code}` | **接受**：目标类尚不存在，链接会指空气；发现即改，正确 |
| `classifyIsDeterministic` 删去域外采样 `-0.25` | **接受**：让"域外行为"由 `classifyIsTotal` 独占把守，避免"负值抛异常"的变异多红一条噪声——红点归属清晰 > 采样覆盖堆量 |
| 分类器依赖 `defaults()` 的**升序迭代序**（控制器复核项） | **已核实有守卫**：`TerrainCatalogTest:23` 用 `containsExactlyElementsOf(KEYS)` 钉住迭代序、`keysAreInAscendingHeightOrder` 钉住 KEYS 升序 ⇒ 依赖不是静默的，无需改动 |

**交给 Task 10 的硬提醒**：
1. `classify(height, humidity, temperature)` 已交付，签名与计划逐字一致；`humidity >= 0.35` 退 `plains` 是它唯一的气候门。
2. 词表遍历序已被 `TerrainCatalogTest:23` 钉住，分类器依赖它——Task 10 不要再排序或重建词表序。
3. 气候噪声若要保证沙漠可出现，引用 `TerrainClassifier.DESERT_MAX_HUMIDITY`，别抄 0.35。
4. `temperature` 通道已接进签名但**不被消费**——Task 10 生成气候值时别假定它被用。

**下一步**：Task 10（`MapGenerator`）派单前扫描 → 派单。

## Task 10 派单前的扫描（控制器预做）

| 配对 | 一方产出 → 另一方消费 | 发现 |
|---|---|---|
| **Task 8 → Task 10** | `GenerationSpec` 参数面（五个频率是"每单位半径"分子；`RidgeParams` 33 字段；`FragmentParams` 切分规则）→ 生成器要的输入 | ★ **真缺口（1）**：参数面里**没有湿度频率**，而沙漠门需要 [0,1] 湿度 ⇒ 不补则沙漠永不出现（词表谎话在图上重演）⇒ **R-10-c**（`NoiseBands` 加 `moistureFreq = 0.02`，**绝对频率**）。**（2）** `contourCacheMax` 在单程生成器里**无消费者** ⇒ **R-10-j**（保留 + Javadoc 注明 + 记 M2 关账复核）。其余核过：`placeRidges` 每次 RNG 抽取都对得上 `RidgeParams`/`FragmentParams` 的字段；标量齐 |
| **Task 2 → Task 10** | 词表带 vs 生成器实际能到的高度 | ★ **真缺口（L9 的图上版）**：实测 48 张默认图（24 种子 × 2 噪声变体）**无一格** ≥ 0.90（最大 0.8969）⇒ `plateau_mountains` 结构性产不出 ⇒ **R-10-g**（下界 0.90→0.85，控制器已改并提交 `4a0c0e8`；0.85 下 6/24 种子产出 2~12 格）。另：`ocean` 带（h<0.30）与"海平面判水"并存 ⇒ 水的 key 统一取 `"ocean"`（**R-10-f**） |
| **Task 9 → Task 10** | `classify(h, humidity, temperature)` 的输入契约 | **无冲突，三条注记**：(1) 湿度须 [0,1] —— 实测 (m+1)/2 ⊂ [0,1]（**R-10-b**）；(2) `temperature` 不被消费 ⇒ 送命名常量 0.5（**R-10-e**）；(3) 词表遍历序不许重排（Task 9 关账注记 2）—— 生成器只调用、不重排 |
| **Task 10 → Task 11** | 生成器的 heights / terrain（河流要用高度判流向） | 注记：**水体的高度可以 ≥ 0.30**（海平面最高 ~0.45）—— Task 11 判水靠 `terrain == "ocean"`，**别靠高度带** |
| **Task 10 → Task 13/14** | `GameMap.hexes()` 迭代序 = 落盘序；字节级往返 | **R-10-h**：自然序（q 升 r 升）。不排序则 `Set.copyOf` 的散列盐使落盘序跨 JVM 启动不稳（Task 5 的盐教训）。R-10-a + R-10-h 是"同 spec 同图"跨进程成立的前提 |
| **Task 10 自洽** | brief 的 10 条用例 × Step 3 的 4 行变异 vs Files 列表 | ★ 三处补：**(1)** brief 的 Files **漏了 `SimplexNoise`**（53 行，R-10-i 移为包私有类）；**(2)** brief 的 `noSecondPathToGenerate`（git grep）在单测内不可执行 ⇒ 反射形态（**R-10-l**）；**(3)** brief 的 4 行变异对**高度管线保真度零判别力**（改系数/删项/删湿度通道在其下全绿）⇒ 补**黄金钉子**（**R-10-k**，7 行全精度实测值已备）+ m-5~m-8 |
| **Task 10 外部依赖** | GSimulator 源（只读） | `placeRidges`（`MapGenerator.java:56-118`，RNG 次序）、`generateContour`（`:129-157`）、`compute`（`ContourQueryEngine.java:125-175`）、湿度行（`:230`）、`SimplexNoise.java` 全文 —— 控制器**逐行核过**，行号地图写进补充文件 §5 |

细则（实现者照办版）在 `task-10-brief-supplement.md`；下面是裁定本体。

**R-10-a**（种子）：`Random(spec.seed())` + `SimplexNoise(spec.seed())` **直接用入参**，不派生 `rng.nextLong()`。
理由：GSimulator `MapGenerator.java:147` 把派生值写进 contour、入参因此丢失 —— L7 的病灶本体。代价若错：与 GSimulator 同 seed 的图形状不同（预期内的分歧）。

**R-10-b**（湿度）：移植 `noise2(px * 0.02 + 500, py * 0.02 + 500)`（**未扭曲** px/py）⇒ `humidity = (m + 1) / 2`，**不夹取**。
依据：实测 m 全域包络 ±0.71、默认采样域 ±0.60 ⇒ (m+1)/2 ⊂ [0,1]；分类器对域外是总函数 ⇒ 夹取是可省的一行。代价若错：未来参数把 m 推出 ±1 时湿度出界 —— 仍不静默失效（分类器有定义）。

**R-10-c**（`NoiseBands` 加 `moistureFreq = 0.02`，**绝对**频率，带 `requirePositiveFrequency` 守卫）：见补充文件 §1。代价若错：record 组件位移的机械改动（同任务内一并 `defaults` 与三个夹具）。

**R-10-d**（不移植 `hillsNoise`/`plainsNoise`/`patch`）：服务作废的 9 项词表，7 项词表下无消费者 = 装饰（L8 的教训）。
**R-10-e**（温度 = 命名常量 0.5）：Task 9 的签名预留，M2 无温度通道。
**R-10-f**（海平面检查 → `"ocean"`）：不移植则 `baseSeaLevel`/`coastFreq`/`coastAmplitude` 三参数无消费者。
**R-10-g**（最高带下界 0.90→0.85，控制器已提交 `4a0c0e8`）：见扫描表 Task 2 行；实测依据在 `TerrainCatalog` 类注释留了一份。
**R-10-h**（枚举取 `HexGrid.withinRadius` + 自然序排序后入 `LinkedHashMap`）：见扫描表。
**R-10-i**（`SimplexNoise` 逐字移植为**包私有**类）：brief Files 之外的偏差裁定（53 行内联会让生成器不可读）。
**R-10-j**（`contourCacheMax` 保留、不消费）：spec §6.4 的正式组件，擅自删属超范围；记 M2 关账复核项。
**R-10-k**（黄金钉子 `measuredSeedProfileMatchesReferencePort` + `measuredSeedsProduceEveryCatalogKey` + `hexesAreInNaturalOrder`，变异补 m-5~m-8）：brief 的 4 行变异对管线保真度零判别力；实测值（seed 42 的 7 行全精度）见补充文件 §2.1，由控制器独立移植产出 —— **不符时实现者先核 GSimulator 源、控制器数值错则报告，不许静默改期望**。
**R-10-l**（`noSecondPathToGenerate` 用反射：唯一 `generate`、无其它 public static）：brief 字面的 git grep 在单测内不可执行。

**BASE `4a0c0e8`**（代码基线；派单时 HEAD 为 `bdcf744` = 本台账入库提交，评审包用 `bdcf744..HEAD`）。已派单。

## 会话中更正（2026-09-17）

- **★ 路由更正**：Task 10 的派单**实际走的是 deepseek**，不是 glm —— 派单时显式传了 `model:"haiku"`，
  而本机实测（看子 Agent 转写里的上游模型名）：**省略 `model` 参数才是 glm（`route-glm-opus` → `GLM-5.1`）；显式传任何 model 值都落到 `route-deepseek-haiku`（`deepseek-flash`）**。
  用户指出「你压根没上glm」后经探针实测确认；Task 1~10 的派单都踩了这个坑。**Task 11 起一律省略 `model` 参数**。
- **★ 范围裁定（用户 2026-09-17）**：「做到 task13 就停下」⇒ **Task 13（`MapResolver`）关账后停止派单**，
  Task 14（L1~L9 守卫）与 Task 15（M2 关账）不执行；恢复时从 Task 14 起。代价若误判：多派两个任务（可撤销）。

## Task 10 关账（2026-09-17）

- **交付**：`9427099`（`feat(map): MapGenerator——单入口、seed 落盘、可复现`）+ `b7126ed`（fix-1：三处 Javadoc 数字按实测校正）。
  改动文件 6 处 = 补充文件 §4 清单（MapGenerator/SimplexNoise/MapGeneratorTest 新增 + NoiseBands/GenerationSpec/GenerationSpecTest），**零越界**。
- **评审形态**：控制器**自读 diff**（734 行改动），未派评审者 —— 依 CLAUDE.md「代码量小时自读即评审」与用户 2026-09-17 重申。
- **当场核过的事实**（控制器独立跑，不引用实现者日志）：
  - `SimplexNoise` 去注释后与 GSimulator 源**逐行相同**（仅包名不同；diff 实证）—— 保真最硬的一条。
  - 黄金 7 值 vs 补充文件 §2.1 表**逐条比对一致**；实现侧 delta ≤ 1.44e-15（13 ulp）≪ 1e-9 容差。
  - 变异 **8/8 全红**、红点全落预期用例；m10v-6 只红 `(-68,62)` 一格（= 被删的海平面检查本身，判别力落点精准）；
    自证头齐（104 文件 md5 清单、原件/变异体 md5 相异、`COMPILATION ERROR = 0`、20 个测试类真跑）。
  - 判别力**当场量过**：30 次独立 JVM，7 键词表与 19441 格集合的迭代序 30/30 ≠ 插入序/自然序。
  - 控制器自跑 `./mvnw -q verify` → **rc=0、零 ERROR**（Spotless/Checkstyle/SpotBugs/Surefire 全过）。
- **无阻塞发现**。留观两条（不修，记此备查）：
  1. `0.8660254`（√3/2 的七位截断）沿自 GSimulator 且与 WebUI 渲染同款 ⇒ 将来标定几何须**两处同改**（报告 §八.2）。
  2. 跨机字节级复现**只有单机证据**（构成条件已齐：两个随机源只吃 seed、枚举序显式排序、无环境量）；若需要，须在第二台机器跑加强版。
- **留给下游的硬提醒**：判水靠 `terrain == "ocean"` 而**非**高度带（水体高度可 ≥ 0.30，实测海平面最高 0.3344）；
  `hexes` 落盘序 = 自然序（R-10-h）是 Task 13 往返的前提。
- **挂起项**：`contourCacheMax` 的去留原定 M2 关账（Task 15）裁决；Task 15 依用户指示不执行 ⇒ 该裁决**随 Task 15 一并挂起**，恢复时处理。
- **下一步**：Task 11（`RiverBuilder`）派单前扫描 → 派单（★ **省略 `model` 参数**）。

## Task 11 派单前的扫描（2026-09-17）

扫描表（本任务无前序未关账任务，逐条对**已关账**的接口）：

| 对象 | 一侧产出 | 另一侧消费 | 结论 |
|---|---|---|---|
| Task 11 ↔ Task 5 | `Pathway`/`EdgeRef`/`EdgeTags`/`PathwayId` | 河 = 链、边 = 标注 | 一致；`start()` 由 `edges` 顺序定（Task 5 的 `chainHead`）⇒ 边的顺序必须 = 流向 |
| Task 11 ↔ Task 6 | `MapChangeSet`/`FieldDelta` | 产出变更集 | 一致；★ `Upsert`/`Remove` **构造期拒空**（`FieldDelta:67`/`:93`）⇒ 空结果必须走 `Unchanged` |
| Task 11 ↔ Task 10 | `GameMap` 的地形与高度 | 判水/判降 | 一致；水按 `terrain == "ocean"`（Task 10 硬提醒），`MapGenerator` 已有同款 private 常量可照形 |
| Task 11 ↔ Task 1 | `HexCoord.compareTo`/`neighbors()` | 候选排序、自然序 | 一致；`neighbors()` 是在图纸邻格吗？**不是** —— 它不含"是否在地图里"，必须逐格过滤 |
| Task 11 ↔ Task 12 | 同为 `map.generate` 包的变更集生产者 | — | 无共享文件、无接口重叠 |
| Task 11 自洽 | 9 条用例 vs 签名 `build(GameMap, long)` | — | ★ **不自洽**：算法形态（源、局部最低点、河数、组注册、seed 的用法）全缺；`branch` 用例要求多源结构，而 brief 的"走一条路径"读法根本产不出分支 |

- **R-11-a/b（算法形态）**：出边网络（每陆地格至多一条出边、目标严格更低、海洋格无出边）⇒ 森林；
  线 = 森林边集切成的**极大简单链**（度数 ≠ 2 的格是两端，分支点即端点）。**代价若误判**：整条实现重做，
  但形态是 spec §5.2「分支点即端点…分支点把线切成一串」的直接落地，spec 是权威。
- **R-11-c（ID）**：`"river-<seed>-<n>"`，`n` = 链的发现序 ⇒ spec §5.2 的「(generationSeed, 序号)」。
- **R-11-d/e（组件取值与变更集）**：`name=null`/`groupId="river"`/`props={}`；只有 `pathways`/`edges` 是 Upsert；
  ★ **不 upsert `PathwayGroup("river")`** —— `producesNoRiversOnFlatMap` 要求全平图空变更集，**代价若误判**：
  组定义缺一份，将来由 Command/上层补（小改动）。已记为挂起项。
- **R-11-f（无阈值）**：不做汇流量过滤（规格未给阈值，不发明魔数）⇒ 生成图上水系密集。
  **代价若误判**：将来加一个 `GenerationSpec` 参数即可（局部改动）。
- **R-11-g（随机源）**：`java.util.Random`，由 `(seed, 本格)` 派生（**不是每条河一个 RNG 沿途抽**，否则网络不是良定义函数图）；
  候选先按自然序排序再 `nextInt`。**代价若误判**：河形变化，用例夹具需重挑。
- **★ 偏差声明**：brief 的「随机源从 (seed, **起点** HexCoord) 派生」按**逐格**读（起点=当前格）；
  按"每条河一个源"读会让同一格在不同河里选不同出边 ⇒ 分叉/网络无定义，与 spec §5.2 冲突。
- **新增两条用例**（控制器的裁定要求）：`differentSeedChangesRiverShape`（R-11-g 的护栏；★ 比边集不比 ID）、
  `worksOnGeneratedMap`（半径 6 的生成图集成冒烟）。变异表补 3 行。全部落在
  `task-11-brief-supplement.md`（与 brief **冲突以补充为准**）。
- **留观**：未给 `RiverBuilder` 配 Task 10 式的"第二条入口"反射守卫（新类无历史包袱，风险≈0；Task 14 不执行，故不补）。

**BASE `82473ed`**（= Task 10 关账提交，派单时即 HEAD）。按此派单（★ **省略 `model` 参数** ⇒ 走 glm）。

## Task 12 预扫描（2026-09-17，趁 Task 11 在跑时提前做；派单前复核）

Task 12 的计划文本比 Task 11 完整（签名、11 条用例、4 行变异都有）。扫描要点与裁定：

- **R-12-a 抽样口径**：逐格 Bernoulli（`rng.nextDouble() < ratioA` ⇒ 该格取 A，否则取 B），
  RNG = `new Random(seed * 31L + regionId.value().hashCode())`（String.hashCode 由规范钉死 ⇒ 跨 JVM 稳），
  格按 `HexCoord` 自然序消费。`ratioIsRespectedStatistically` 两种诚实写法都收：
  ① 1000 格的区域跑一次，|实测占比 − p| ≤ 0.05；② 小区域 × 1000 个不同种子，合并占比落 ±0.05。
  **不许**写成恒真的形式（如只断言"两种地形都出现过"）。
- **R-12-b 变更集**：只有 `hexes` 是 `Upsert<HexCell>`（key = `HexCoord` 的 `"q_r"`），其余 6 个 `Unchanged`；
  空/未知 region ⇒ 7 个全 `Unchanged`（`Upsert` 拒空）。
- **R-12-c 重分配 = 新 `HexCell(terrain, 原 height)`** ★ 组件顺序是 **(terrain, height)**（`HexCell` 是 record(String, double)）；高度与其它字段一律不动（L7：高度是落盘的一等公民）。
- **R-12-d 校验**：`terrainA`/`terrainB` 交给 `TerrainCatalog.of(key)`（未知 key 抛它自己的 IAE，**不包不吞**）；
  `ratioA ∉ [0,1]` ⇒ IAE。**不**为 `a == b` 加守卫（规格未提，且它是合法输入；记留观）。
- **R-12-e 范围**：只改 `map.regions().get(regionId).hexes()` 里、且**同时在 `map.hexes()` 里**的格
  （区域里落在图外的格跳过，不抛——与 `MapResolver` 的"未知 ⇒ 空，不抛"同口径）；region 之外的格**一格不动**。
- **R-12-f 不碰** `regions`/`cities`/`terrainTypes`/`pathways`/`pathwayGroups`/`edges`/`spec`。
- 跨任务：与 Task 11 同在 `map.generate` 包、同为变更集生产者，**无共享文件**；与 Task 4（Region）的接口在派单前复核
  （`Region.of(id, name, hexes, meta)` / `Region.contains` / `RegionIndex`）。

## Task 11 关账（2026-09-17）

- **交付**：`213a8f9`（`feat(map): RiverBuilder——按海拔生成可寻址、可复现的水系`）+ `e0778b4`（报告与变异证据入库）。
  改动面 = **仅两个新文件**（`RiverBuilder.java` 231 行、`RiverBuilderTest.java` 396 行，11 条用例），**零越界**。
- **评审形态**：控制器**自读 diff**（630 行），未派评审者（CLAUDE.md「代码量小时自读即评审」）。
- **当场核过的事实**（控制器独立跑/独立读，不取实现者日志的表面结论）：
  - 算法逐条对上 R-11-a~h：出边严格更低 + 海洋格不流、候选自然序排序后 `nextInt`、每格 RNG 由 `(seed, 本格)` 派生、
    链按发现序编号、`pathways`/`edges` 是唯一两个 Upsert 且位置正确（第 5、第 7 组件）、空走 `Unchanged`（`upsertOrUnchanged`）。
  - **空链不可能**：`from` 取的是触发边的**尾**（尾必有出边），上溯只路过"入度恰 1"的格 ⇒ 每条链 ≥ 1 边（逐路径推演过）。
  - 发现序不依赖哈希序：外层按 `HexCoord` 自然序、`incident` 按对端自然序、`unused` 判消费 ⇒ 同种子同结果。
  - 变异 **7/7 全红**、红点均含其**声明靶子**（v1 兼红 `riverStartsAtHighestHex` —— 派单前点名的"最高格获得入边即不再是叶"当场成立；
    v3 `riverIsAddressable`；v4 `branchesAreSeparatePathways`；v5 `differentSeedChangesRiverShape`；v6 唯一红 = `producesNoRiversOnFlatMap`
    （`FieldDelta` 构造期守卫当场抛，补充文件已声明该形态为"红（构造期抛）"）；v7 `edgesAreConsistentWithPathways`）。
  - 自证头逐轮齐：106 个 .java 的 md5 干净世界、改前先绿（红点 0）、变异体按**白名单推成目标类名**、原件与各变异体 md5 相异、
    `COMPILATION ERROR` 真实计数 0（我看到的非零匹配是证据文本里的**断言回显**行，不是编译错误）、测试类真跑过。
  - 判别力纪律的两处自纠都在 `.kept` 里：m11v-1/2 用**事先声明**的等价形态（字面"六邻随机"会造 2-环 ⇒ 超时红不是断言红 —— 因果独立复核过）；
    m11v-4 首版"穿过分支"红在错用例（单条线内度数恒 ≤ 2），换成真分叉形态重跑，两版来龙去脉都在 `mutate.py` 注释与报告"诚实说明"里。
- **执行期裁定（控制器追认）**：**度恰为 2 的汇点（两入零出）也切链** —— 补充文件的"度数 ≠ 2 是两端"在它上面与"边序 = 流向"直接矛盾
  （两条入边相向汇合，无论怎么排都有一段上坡）。细化后链只在**过路格（恰一入一出）**内部延伸，边集划分/极大性/严格降高度全部保持。
  **代价若误判**：链数略增，无正确性影响。
- **挂起项 / 留给下游**：
  1. ★ **重建河流会整份覆盖 `EdgeTags`**（`edges` 的 Upsert 按边 key 换整份值）：若 base 的某条边已挂别的 Pathway 的标注，
     重建后被替掉而非合并。生成图（pathways/edges 为空）无此问题；**合并语义属 Command 层**（生成器不做读-改-写——那会让变更集依赖 base，破坏纯函数）。
     未派 Task 14/15 ⇒ 记此备查，**Task 13 之后的编辑流实现者必须处理**。
  2. `PathwayGroup("river")` 无人注册（R-11-e 的裁定），消费方别假设生成变更集里有组定义。
  3. 水系密集（R-11-f 无阈值）：半径 6 图 44 格陆地 ⇒ 30 条河；稀疏化阈值将来作 `GenerationSpec` 参数，届时夹具钉死的数字要重测。
  4. `differentSeedChangesRiverShape` 钉了实测差集 = 13 ⇒ 换 RNG 形态要重测（与 Task 10 黄金钉同族，属**有意**的强断言）。
- **门禁**：控制器自跑 `./mvnw -q verify` ⇒ **rc=0、`[ERROR]` 行 0 条**（独立于实现者；2026-09-17）。
- **下一步**：Task 12（`RegionRandomizer`）—— 预扫描已在本台账（R-12-a~f），brief 已生成 `task-12-brief.md`，直接派单（★ 省略 `model`）。

## Task 13 预扫描（2026-09-17，趁 Task 12 在跑时提前做）

扫描表（与已完成任务逐条对缝，★ = 查出问题并当场裁定）：

| 对上谁 | 缝是什么 | 发现 |
|---|---|---|
| M1 `Resolver` SPI（Task 1/2） | `namespace()` + `resolve(Address, ResolveContext)` | ✓ 签名照抄，无歧义 |
| M1 `Address`（§3.2/§3.6） | brief 的 `map:<mapId>:hex:<q>_<r>` | ★ **计划期笔误**：冒号形式实测解析成两个 **Property** 段（`map:m1:hex:0_0` ⇒ `[Ns(map),Entity(∅,m1),Property(hex),Property(0_0)]`），真形态是 `map:m1:hex.0_0`（`Entity(hex,0_0)`，M1 §3.6 冻结样例）。⇒ R-13-a |
| M1 `SimulationState`/`Snapshot`（Task 8/9） | `resolve` 的图从哪来 | ★ **brief 全无此环**：`SimulationState` 无跨模块访问器（铁律 3/4），`Snapshot` 是接口 ⇒ Task 13 必须顺带建 `MapSnapshot`（总纲 §4.5 已点名该类型）。⇒ R-13-h |
| Task 5 `GameMap` | `map:<mapId>` 的 mapId | ★ `GameMap` **无 id 组件**（8 组件里没有）⇒ mapId 不可校验，只能回显。⇒ R-13-g + 挂起项 |
| Task 3 `RegionIndex` | `regionOfHexUsesTheIndex` 怎么钉 O(1) | ★ `RegionIndex(Map)` 构造器**包私有**（`RegionIndex:24`），`MapResolverTest` 跨包 ⇒ 计数注入这条路**堵死**；改为**重叠区域 + 插入序与字典序相反**（线性扫描给 `r2`、索引给 `r1`）⇒ 仍是结果级红。⇒ R-13-i |
| Task 12（同批） | 包/文件 | ✓ 无共享文件 |
| 自洽行 | brief 的 9 条用例 vs 签名 | ✓ 签名够用；补 4 条（Index 人类形式、mapId 回显、带引 mapId、注册表转发、无 map 模块）⇒ 13 条 |

**裁定摘要**（细则与代价写在 `task-13-brief-supplement.md`，**以它为准**）：
R-13-a 只认 `kind.name` 点号形式（依据 M1 §3.2 第 ≥3 段裸词 = Property + §3.6 冻结样例 + 总纲 §4.3「段间只用 `:`」）；
R-13-b 非 map 命名空间 ⇒ 空候选（抛由注册表负责）；R-13-c 根地址第 2 段必须 `Entity(∅,·)`（M1 §3.2「根主体」）；
R-13-d 三类实体 + **Index 人类形式**（canonical 一律 `hex.<q>_<r>`，总纲 §4.2「Human 进 canonical 出」）；
R-13-e 合法但不服务/形状不符 ⇒ **空候选不抛**（`terra.Grass`、冒号形式、属性段都在此列）；
R-13-f **唯一抛点** = 认领的 kind 名字解析失败（`hex.abc`）；
R-13-g mapId 只回显不校验 + canonical 必须走 `Address` AST（§3.4 加引不许手写）；
R-13-i `regionOfHex(GameMap,HexCoord) -> Optional<RegionId>` 委托 `map.regionIndex()`；
R-13-j 源码级无 IO 断言（注意别用裸词 `File` —— 会误伤 `FieldDelta`）。

**新增挂起项**：MapSimos 缺地图身份（`GameMap` 无 id）⇒ `map:<mapId>` 的 mapId 目前不可校验、只回显；将来 GameMap 有 id 字段时应收紧。

## Task 12 派单（2026-09-17）

- **BASE** = `82f3dbf`（Task 11 关账提交）。派单时工作树干净。
- 需求 = `task-12-brief.md` + `task-12-brief-supplement.md`（补充为准；新增裁定 R-12-g 挡住 NaN 的 ratio、
  R-12-h 校验顺序、R-12-i 图外格跳过且不消费随机数、以及"夹具 ratioA 必须严格落在 (0,1) 否则确定性用例恒真"的判别力要求）。
- 派单：**省略 `model` 参数**（按用户裁定，省略才走 glm 路由）。
