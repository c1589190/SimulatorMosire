# Task 11 报告：`RiverBuilder` —— 按海拔生成可寻址、可复现的水系

**状态：DONE** ｜ 分支 `feat/m2-map-simos` ｜ 提交 `213a8f9`（代码，父 `82473ed`）+ `docs(sdd)` 提交（本报告与证据，`git add -f` 越过 gitignore，见 `git log --oneline -- .superpowers/sdd/2026-09-16-map-simos-plan/task-11-report.md`）
｜ 交付：`RiverBuilder`（231 行）+ `RiverBuilderTest`（396 行，11 条）；simos-map 208 用例全绿（197 + 本任务 11），`./mvnw verify` 全绿（Spotless / Checkstyle / SpotBugs BugInstance 0 / Surefire 156+208+15 全 0 失败）

---

## 一、实现了什么

`public static MapChangeSet build(GameMap map, long seed)`（`RiverBuilder.java:91`），算法严格按补充文件 §1（R-11-a~h）：

- **流网络（R-11-a）**：顶点 = `map.hexes()` 全部格；陆地 = terrain 不是 `"ocean"`（private 常量 `OCEAN`，形如 `MapGenerator:51`；**按地形判水、不按高度带**）。每个陆地格至多一条出边，目标 = **严格更低**的**在图纸**邻格（`neighbors()` 不过滤图纸，逐格查 `map.hexes()`，Task 1 的坑）；海洋格永不流出边；无候选 ⇒ 终点（入海/图缘/内陆洼地）。出边必然降高度 ⇒ 无环 ⇒ 森林。
- **随机源（R-11-g）**：`java.util.Random`，`new Random(seed * SEED_SALT + at.q() * Q_SALT + at.r())`（两个大奇数乘子：splitmix64 黄金比乘子 / xxhash 质数）。候选先按 `HexCoord` 自然序排序再 `nextInt` —— 结果不依赖 `neighbors()` 之外的迭代序。**每格的出边只由 (seed, 本格) 决定**，不是每条河一个 RNG。
- **切链（R-11-b）**：把森林边集切成极大简单链，每条链一个 `Pathway`，**边序 = 流向**（上游→下游 ⇒ `start()` 上游端、`end()` 下游端；长度 1 的链按 R-11-h 例外）。链只在"过路格"（恰一入一出）内部延伸；源（零入）、汇（零出）、分支点（≥2 入，度 ≥3）都是端点。
- ★ **度恰为 2 的汇点也切开**（两入零出）——本任务的执行期裁决，见"自审发现 1"：按无向度数它该是链内部格，但两条入边在那里相向汇合，链无法按流向定向，与 R-11-b 自己的"边序 = 流向"矛盾。切成两条各自以它为下游端点的链，每条仍是严格降高度的极大有向路径，边集划分不变。
- **ID（R-11-c）**：`"river-" + seed + "-" + n`，n = 发现序（有边的格按自然序、每格的未用边按对端格自然序，沿链走到另一端后依次编号）⇒ 同种子同图同 ID。
- **组件（R-11-d）**：`name = null`、`groupId = "river"`、`props = Map.of()`。
- **变更集（R-11-e）**：只有 `pathways`（key = PathwayId 裸值）与 `edges`（key = `EdgeRef.toString()`，值 = 只以该 PathwayId 为键的 `EdgeTags`、props 空）是 `Upsert`，均按发现序；其余 5 个 `Unchanged`；**无河时 7 个全 `Unchanged`**（`Upsert` 拒空，经 `upsertOrUnchanged` 走 `Unchanged` 分支）。**不 upsert `PathwayGroup("river")`**（补充文件裁定为挂起项，遵守）。
- **无阈值（R-11-f）**：不做汇流量过滤 ⇒ 每个有出边的陆地格都排入链 ⇒ 生成图上水系密集；"将来稀疏化应作 `GenerationSpec` 参数"已写进类 Javadoc。

## 二、用例（11 条 = brief 9 条 + 补充文件 §2 新增 2 条）

| 用例 | 关键口径 |
|---|---|
| `riverStartsAtHighestHex` | （1）夹具自证：全图扫出的最高格是 `(0,0)` 且其在图纸邻格全部更低；（2）存在 `Pathway` 使 `start() == (0,0)`；（3）该链 ≥ 2 条边（实测 3，`river-7-9`）——R-11-h：长度 1 不定义流向，夹具必须让断言落在有向一侧 |
| `riverNeverGoesUphill` | 游标式沿边序走（**不调 `start()`**）：每条边必须接得上游标、每步高度严格下降；单边链跳过。分叉链（变异靶）在这里也是干净的红（"接不上"断言），不是 NPE |
| `riverEndsAtOceanOrBoundary` | 多边链的 `end()` 是 ocean 或图缘格（六邻至少一个不在图里）；单边链跳过（R-11-h）。另断言多边链条数 = 4（钉夹具，防空转恒绿） |
| `riverIsAddressable` | ID 互不相同 **且** 恰为 `river-7-1 .. river-7-14`（发现序连续、带 seed）——比"互不相同"多钉了派生格式（GSimulator 现状是所有河共享 `"river"`） |
| `branchesAreSeparatePathways` | （1）`size() ≥ 2`（实测 14）；（2）没有任何 Pathway 内部含度 ≥ 3 的格（**用该 Pathway 自己的边集算度数**，不调 `start()`——构造期不拦分叉，红得干净） |
| `sameSeedGivesSameRivers` | 两次 `build` 的 `MapChangeSet` 相等（record 值语义） |
| `producesNoRiversOnFlatMap` | `cs` 非 null、`isEmpty()` true、**7 个组件逐一** `instanceof Unchanged` |
| `edgesAreConsistentWithPathways` | 先 `apply(cs, map)`：(1) `after.pathways()` 与 upsert 集合一致；(2) 每条 Pathway 的每条边的标注**恰以该 PathwayId 为键**（`containsExactly`，连多余的键都不许）；(3) 全体边无重复（链是边集的划分） |
| `doesNotMutateInput` | 夹具用**调用方持有的可变 `LinkedHashMap`** 构造 `GameMap`，调用后断言那份可变 map 与快照相等（真靶子，不是不可变对象自比） |
| ★ `differentSeedChangesRiverShape` | 同夹具 seed 7 vs 8：**比边集不比 ID**（ID 带 seed 恒真）；断言差集 = 13（实测） |
| ★ `worksOnGeneratedMap` | 半径 6 的生成图（127 格）：河数 = 30（实测）、无内部度 ≥ 3、`edgesAreConsistent` 的 (2)(3)、两次调用相等。**不断言** ocean/图缘终点：实测 seed 42 的同类图有 7 个内陆洼地终点，那是 R-11-a 认可的合法终点 |

## 三、夹具实测（都在本机当场跑过，`/tmp/m11probe`，jshell 卡死后换 javac+java 直跑）

**锥形图**（半径 3 = 37 格，高度 `0.9 − 0.2d` 严格随距离递减，全 plains；地形 key 与高度带有意不对应——`HexCell` 不校验，对 RiverBuilder 只有 ocean/非 ocean 有意义）：

- seed 7：**14 条河 / 19 条边**。19 = 37 − 18：图上恰有 18 个图缘格（全部是汇），**每个非图缘格恰有一条出边**——锥上没有内陆洼地，这是 `riverEndsAtOceanOrBoundary` 稳的原因。
- 最高格 `(0,0)` 所在链 = **`river-7-9`，3 条边**（中心→环1→环2→环3），4 条多边链的终点实测为 `-3_2 / 3_-2 / 0_3 / 3_-1`，全是图缘格。
- **两个 seed 的边集确实不同**：seed 7 与 seed 8 各 19 条边，**13 条不同**（重合 6 条）——两套逐格随机只在一部分格上分叉，正是 R-11-g 可证伪的形态。
- 锥上**存在度 2 的汇**（19 条边流入 18 个图缘格 ⇒ 至少一个图缘格收 2 条）——自审发现 1 的裁决不是空谈，每轮都在被行使。

**生成图**（`GenerationSpec.defaults(7L)` 同形制改半径 6）：127 格 / **44 格陆地 / 30 条河 / 44 条边**（每个陆地格恰有一条出边——本 seed 下没有陆地洼地）；多边链终点 11 条入海 + 19 条单边链跳过；两次 `build` 相等。另测 seed 42（未进断言）：69 格陆地 / 45 条河 / 65 条边 / 7 个内陆洼地终点。

## 四、变异证据表（7 轮；装置复用 Task 10 的 `run.sh`/`mutate.py`，改 `LAB=/tmp/m11lab`、TARGET 表与锚点，证据在 `task-11-evidence/`）

**共同自证头**（7 轮全部满足，逐轮从 `.kept` 可查）：

- 干净世界：副本逐文件与工作树 md5 清单一致、**共 106 个 .java、无多余文件**（7 轮同）；
- **改前**：simos-util 156 / 0 + simos-map 208 / 0，BUILD SUCCESS（护栏先绿）；
- 变异体写进**目标类名**的文件（`generate/RiverBuilder.java`），声明文件集合与实际改动集合一致；
- **原件 md5 = `cda2058c…`（7 轮相同**，每轮起点同一份原件）；每轮变异体 md5 与原件不同（见下表）；
- **改后 `COMPILATION ERROR count = 0`（7 轮全 0）**、simos-map 21 个测试类真的跑过（红了必须是断言红）。

| 轮 | 变异（md5 原件 `cda2058c…` → 变异体） | 期望 | 实测红点（测试名:行号；断言消息原文摘录） |
|---|---|---|---|
| m11v-1 | 出边不看高度：判据 `cell.height() < height` 换成坐标序 `nb.compareTo(at) < 0`、取最小。→ `39950a6e…` | 红 | `riverNeverGoesUphill:112`：`[河 river-7-3 第 1 步必须严格下降（3_0 → 2_0）] Expecting actual: 0.5 to be less than: 0.29999999999999993`（坐标序走进了内环=上坡）+ **`riverStartsAtHighestHex:81` 也红**（补充文件点名要当场验证）：`Expecting ArrayList: [-3_0, -3_0, 3_0, …] to contain: [0_0]`——最高格获得入边、不再是叶 ✓。另有 differentSeed:281 / producesNoRiversOnFlatMap:201 / riverEnds:139 / worksOnGeneratedMap:300 红（等高图也产出边、形态全变，皆合法连带） |
| m11v-2 | 只从随机挑的一个源走一条线、不走全网络（补充文件给定的等价形态）。→ `7958b929…` | 红 | `riverStartsAtHighestHex:81`：`Expecting ArrayList: [0_-2] to contain: [0_0]`——随机挑中的链从 `0_-2` 起，不含最高格 ✓。另有 branches:174（1 < 2）/ differentSeed:284 / riverEnds:139 / worksOnGeneratedMap:300 红（单链的合法连带） |
| m11v-3 | 所有河共用 `new PathwayId("river")`。→ `b9c49384…` | 红 | `riverIsAddressable:162`：`[ID 恰为 river-7-1 .. river-7-1（发现序连续）] Expecting actual: ["river"] to contain exactly: ["river-7-1"]`——14 次 put 同 key 被 LinkedHashMap 折叠成 1 条（★ 正是 GSimulator 的现状）。另有 branches:174 / riverEnds:139 / riverStarts:81 / worksOnGeneratedMap:300 红 |
| m11v-4 | 分支不切开：分支点的**其余入边并进同一条线**（线内出现度 3 分叉）。→ `69cdf069…` | 红 | `branchesAreSeparatePathways`：`[河 river-7-5 内部不得有度 ≥ 3 的格（分支点应把链断开）] Expecting actual: 3 to be less than or equal: 2`——分叉在格 `-1_-1` 上，度数断言本身红 ✓。`riverStartsAtHighestHex` 同轮以 `IllegalStateException: 链不合法：格 -1_-1 上挂了 3 条边…` 红（Pathway 自己的 start() 校验，同类问题的另一处响法）；另有 edgesAreConsistent:242（边被两条链重复消费）/ riverEnds / worksOnGeneratedMap 红 |
| m11v-5 | `rngFor` 换成 `new Random(42L)`（不看 seed）。→ `829f71f0…` | 红 | `differentSeedChangesRiverShape:281`：`isNotEqualTo` 失败、消息打出两套完整边集（首条 `-3_2|-2_1` 即不同）——两个 seed 产同网络 ✓。另有 riverEnds:139（4→6，seed 7 自己的网络也变了）/ worksOnGeneratedMap:300（30→36）红 |
| m11v-6 | 空也走 `new Upsert(entries)`。→ `b5dd2174…` | 红（构造期抛） | **仅一条红**：`producesNoRiversOnFlatMap:198 » IllegalArgument Upsert 不得为空`——`FieldDelta` 的构造期守卫当场响，其余 10 条全绿（锥形图非空路径不受影响）✓ |
| m11v-7 | edges 标注的 key 用 `RIVER_GROUP`（`"river"`）而不是 PathwayId。→ `f0cbf31d…` | 红 | `edgesAreConsistentWithPathways:242`：`[(2) 边 -3_1|-2_0 的标注必须以河 river-7-1 的 PathwayId 为键] Expecting actual: ["river"] to contain exactly: ["river-7-1"]`——三层（组/实例/标注）挤回一个词正是 spec §5.4 要拆的病。另有 worksOnGeneratedMap:311 红（同断言） |

### 诚实说明（三处）

1. **m11v-1 / m11v-2 用了预先声明的等价形态**（`mutate.py` 模块注释写在前，非事后辩解）：字面"六邻里 RNG 随便挑"会造出 X→Y 与 Y→X 互指的环（互指只要求互为候选，高度判据删掉后无人可挡），`emitChain` 的游标会无限走下去——红的是**超时**不是断言；等价形态取"候选 = 坐标序更小的在图纸邻格、取最小"，被保护的判据（严格更低）照样删掉、上坡照样出现，而坐标严格降 ⇒ 无环 ⇒ 断言红。m11v-2 按补充文件给定的等价形态（"只从随机挑的源走线、不走全网络"）实现；随机源 `new Random(seed)` 挑中的链起点实测 `0_-2`，判别力成立——**换夹具半径/种子时需复核这一点**（已写进 mutate.py 注释）。
2. **m11v-4 重跑过一次**：首版写成"去掉 break、链沿出边穿过分支继续"——那条变异确实红，但红在 `edgesAreConsistent`（两条链重复消费同一条边），**目标用例 `branchesAreSeparatePathways` 不红**：沿出边走出来的链结构上永远是路径，单条线内度数恒 ≤ 2，"线内分叉"根本造不出来。换成"分支点其余入边并进同一条线"的真分叉形态后重跑该轮，度数断言如预期红。两个版本的来龙去脉都写在 `mutate.py` 的 m11v-4 注释里；`.kept` 是重跑后的那一轮。
3. **红点的行号**取自 surefire 的 `Test.method:line` 摘要。m11v-1 的 `riverStartsAtHighestHex` 完整消息（starts 列表）落在 `run.sh` 的 `head -80` 截断之外，已从同轮 `after.log` 原文补录进 `m11v-1.kept` 末尾（带出处标记，未改动原文）。

## 五、门禁

`./mvnw verify`（最终树，未变异）→ **BUILD SUCCESS**（rc=0），日志存 `task-11-evidence/gate-clean-verify.txt`。
实测：Spotless ✓ / Checkstyle ✓ / SpotBugs 各模块 `BugInstance size is 0` ✓ / Surefire：simos-util **156** + simos-map **208**（= Task 10 的 197 + 本任务 11）+ simos-core **15**，Failures 0、Errors 0。
迭代期命令：`./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RiverBuilderTest test`。

## 六、Files changed

**新增（仅此两个源文件，未改任何既有文件）**
- `simos-map/src/main/java/io/mosire/simos/map/generate/RiverBuilder.java`（231 行）
- `simos-map/src/test/java/io/mosire/simos/map/generate/RiverBuilderTest.java`（396 行，11 条）

**证据**（`.superpowers/` 下、gitignored，以 `git add -f` 入库）：
`task-11-evidence/{run.sh, mutate.py, rounds/m11v-1..7.kept, gate-clean-verify.txt}` + 本报告。

**提交**：`213a8f9 feat(map): RiverBuilder——按海拔生成可寻址、可复现的水系`（消息逐字采用）；随后 `docs(sdd): Task 11 的报告与变异实验室证据入库`（本报告 + `task-11-evidence/`）。工作树除本任务文件与控制器自己的 `progress.md` 改动外零触碰。

## 七、Self-review 发现

1. ★ **度 2 汇点的执行期裁决**（唯一一处对补充文件的细化，已写进类 Javadoc）：R-11-b"度数 ≠ 2 的格是两端"与"边序 = 流向"在**两入零出的汇**上直接矛盾——两条入边相向汇合，无论怎么排序都有一段上坡，`riverNeverGoesUphill` 必红。裁定：链只在**过路格**（恰一入一出）内部延伸，这类汇点是两条链共同的下游端点。这与"分支点即端点"同族（分支点是 ≥2 入 + 1 出；度 2 汇是 2 入 + 0 出，无向度数同为 2 才被漏掉），边集划分、极大性、严格降高度全部保持。锥形图每轮都在行使它（19 边入 18 汇）。
2. SpotBugs 首跑报 `WMI_WRONG_MAP_ITERATOR`（`out.keySet()` 里 `out.get`）——改为 `entrySet` 迭代（已修，`RiverBuilder.java:111`）。
3. 我最初把 simos-map 用例数从 surefire 残留报告文件加总成 209——reactor 汇总实为 **208**（= 197 + 11；残留在 `target/` 里的旧报告多算了一条）。报告与台账一律以 reactor 汇总为准。
4. `worksOnGeneratedMap` 的河数断言（30）是夹具钉子而非不变量——换 spec 参数就要重测。这是"当场实测"纪律的代价，Javadoc 里已写明实测条件（半径 6、seed 7）。

## 八、Concerns / 留给下游

1. **在已有边的图上重建河流会整份覆盖 `EdgeTags`**：`edges` 的 `Upsert` 按边 key 换**整份** `EdgeTags`——若 base 的某条边已挂**别的** Pathway（比如一条路）的标注，重建后那份标注被替掉而不是合并。生成图（pathways/edges 为空）无此问题；编辑流需要"按 pathwayId 合并"的 upsert，属上层 Command 的职责。**建议记台账**。
2. **`PathwayGroup("river")` 无人注册**（补充文件 R-11-e 裁定为挂起项，本任务遵守）：组定义由 Command/上层供给，将来消费方别假设生成变更集里有它。
3. **密集水系**（R-11-f 无阈值）：半径 6 的图 44 格陆地 ⇒ 30 条河。稀疏化阈值将来作为 `GenerationSpec` 参数出现（类 Javadoc 已注明路径），届时 `differentSeedChangesRiverShape` 等夹具数字需重测。
4. **m11v-2 的判别力依赖随机挑中的链**（见"诚实说明 1"）：夹具或种子变了要复核"挑中的链不含最高格"。
5. `doesNotMutateInput` 只盯 `hexes`——`RiverBuilder` 唯一读的组件就是它；其余组件本来就无写路径，不为它们写恒真断言。
