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
