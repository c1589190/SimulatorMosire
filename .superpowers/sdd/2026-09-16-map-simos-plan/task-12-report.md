# Task 12 报告：`RegionRandomizer` —— 确定性框选随机化

**状态：DONE_WITH_CONCERNS**（关切全在 §八，无一条挡关账）｜ 分支 `feat/m2-map-simos` ｜ 基线 `1212904`
｜ 交付：`RegionRandomizer`（109 行）+ `RegionRandomizerTest`（407 行，**13 条**）
｜ 单跑 `RegionRandomizerTest` 13/13 绿；`./mvnw verify` **rc=0、0 条 `[ERROR]`**（UtilSimos 156 / MapSimos 221 / CoreSimos 15，SpotBugs 五个模块 BugInstance 0）

---

## 〇、诚实披露（本任务跨了两个实现者，先说清楚）

1. **两个源文件是前一个实现者（glm 路由）产出的**：算法、13 条用例、夹具、变异装置（`run.sh`/`mutate.py`）与 5 轮实验都是它做的。它在**门禁处**被卡住（SpotBugs 报 1 个 bug），随后进程因外部原因终止（GLM 额度 429），未提交、未写报告。
2. **控制器复现了失败**：`./mvnw -q verify` rc=1、11 条 `[ERROR]`，`DMI_RANDOM_USED_ONLY_ONCE` @ `RegionRandomizer.java:80`。根因是**过程内检测**——`new Random(...)` 与唯一的 `nextDouble()` 在同一方法里，检测器只数调用点、不看循环 ⇒ 把"一个 RNG、循环里抽 N 次"这个**正确用法**误报。
3. **本轮（第二个实现者）的修复只有一件事**：把随机源的构造抽成 `private static Random rngFor(RegionId region, long seed)`（`RiverBuilder.rngFor` 的同形制），调用点换成 `Random rng = rngFor(region, seed);`。**语义逐字未动**（见 §二 的实测复核）。**没有**用 `@SuppressFBWarnings`、**没有**新增依赖、**没有**改测试迁就。
4. **5 轮变异是在修复后的基线上重跑的**（`mutate.py` 的 m12v-1 锚点随修复移到了 helper 的返回行）。
   ★ **旧的 5 个 `.kept`（修复前基线）已被本次重跑覆盖**——`rounds/` 里现在只有修复后基线的证据，没有旧账混在里面。原 md5 与新 md5 不同（旧原件 `…`（未留存）→ 新原件 `af8a6f03…`），`gate-clean-verify.txt` 也被绿的那次 verify 覆盖（旧的那份记的是 SpotBugs 红）。
5. 报告里每个数字都是**本轮当场跑出来的**：门禁日志见 `task-12-evidence/gate-clean-verify.txt`，夹具探针重跑输出见 §三（探针源码在 `/tmp/m12probe/Probe.java`，工作树外的临时文件，未入库）。

## 一、实现了什么

`public static MapChangeSet randomize(GameMap map, RegionId region, String terrainA, String terrainB, double ratioA, long seed)`（`RegionRandomizer.java:59`），严格按补充文件 R-12-a~i：

- **抽样口径（R-12-a）**：逐格 Bernoulli——`rng.nextDouble() < ratioA` ⇒ 取 A，否则 B。RNG = `new Random(seed * 31L + region.value().hashCode())`，**一个 region 一个 RNG**，抽成 `rngFor(RegionId, long)`（`RegionRandomizer.java:106`）。用 `java.util.Random`（LCG 由规范钉死）+ `String.hashCode()`（同样规范钉死）⇒ **跨 JVM 稳**，不用 `ThreadLocalRandom`。
- **消费顺序（R-12-i）**：目标格 = `map.regions().get(region).hexes()` 里**同时在 `map.hexes()` 里**的格，按 `HexCoord` 自然序逐格消费一次 `nextDouble()`；**不在图纸的格跳过且不消费随机数**（图外的格不影响图内格的结果）。
- **重分配（R-12-c）**：`new HexCell(新地形, 原 height)`——**组件顺序 (terrain, height)，高度一律不动**（L7：`HexCell.height()` 是 `RiverBuilder` 的输入）。
- **校验顺序（R-12-h）**：`Objects.requireNonNull(map, "map")` → `Objects.requireNonNull(region, "region")` → `TerrainCatalog.of(terrainA)` / `TerrainCatalog.of(terrainB)`（未知 key 抛**词表自己的** IAE，不包不吞、不改写消息）→ `ratioA` 范围。**先校验完再算** ⇒ 参数错时一定听得见，与区域空不空无关（三条"空区域上照样抛"的用例钉住）。
- **NaN（R-12-g）**：判据写成 `!(ratioA >= 0.0 && ratioA <= 1.0)`，**不是** `ratioA < 0.0 || ratioA > 1.0`（后者对 NaN 恒 false ⇒ 静默漏过）。
- **变更集（R-12-b）**：7 个组件里**只有 `hexes` 可能非 `Unchanged`**（`Upsert<HexCell>`，key = `HexCoord.toString()` 的 `"q_r"`）；其余 6 个一律 `Unchanged`；无目标格（空区域 / 未知 RegionId / 目标格全在图外）⇒ **7 个全 `Unchanged`**，`isEmpty()` 为 true、`cs` 非 null（`Upsert` 构造期拒空）。**不 upsert region 本身**。插入序 = 处理序（`LinkedHashMap`，不用 `Map.copyOf`）。
- **确定性**：`sameSeedGivesSameResult` 的夹具 ratioA 严格落在 (0,1)（用 0/1 时种子无关 ⇒ 恒真）；`differentSeedGivesDifferentResult` 用 1027 格，"巧合相等"概率 2⁻¹⁰²⁷。

## 二、修复与其语义不变的实测复核

| 项 | 修复前 | 修复后 |
|---|---|---|
| 构造点 | `randomize` 内联 `new Random(seed * 31L + region.value().hashCode())` | `randomize:74` 调 `rngFor(region, seed)`；helper 在 `:106` |
| SpotBugs | `BugInstance size is 1`（DMI_RANDOM_USED_ONLY_ONCE）⇒ BUILD FAILURE | **五个模块全部 `BugInstance size is 0`** ⇒ BUILD SUCCESS |
| 测试 | 13/13 绿 | 13/13 绿，且**逐格数字一字未变** |

**语义不变的证据不是"测试还是绿的"，是"绿的是同一组冻结数字"**：测试里钉死了实测值（`A 占 559/1027`、两 seed 差 **489** 格、孪生区域差 **59** 格、core 的 key 序 `-1_0..1_0`、区域外核对 30 格），修复后全部原样通过。夹具探针（`/tmp/m12probe/Probe.java`）本轮**重新编译、重新运行**（对着修复后的 `target/classes`），输出与前一版实现者记录的类注释数字**逐字相同**：

```
bigMap.hexes.size = 1027 ; seed7 ratio0.5: n=1027 countA=559 observed=0.5443037974683544
seed8 ratio0.5: countA=512 ; 与 seed7 指派不同的格数 = 489
twinMap.hexes.size = 127 ; twin keySets equal = true ; alpha vs beta 同 seed 指派不同 = 59 / 127
twoRegionMap.hexes.size = 37 ; core upsert size = 7 keys = [-1_0, -1_1, 0_-1, 0_0, 0_1, 1_-1, 1_0]
core ratio0: countB=7 / 7 ; core ratio1: countA=7 / 7 ; rim checked=30 changed=0
NaN 抛: ratioA 必须在 [0,1]: NaN ; void+nope 抛: 未知地形类型: nope
```

## 三、用例（13 条 = brief 11 条 + 补充文件 §2 的两条）

| 用例（行号） | 关键口径 |
|---|---|
| `ratioIsRespectedStatistically`（:64） | 补充文件收的**写法①**：半径 18（1027 格）跑一次，实测占比落在 0.5 ± 0.05。**实测 A = 559/1027 = 0.5443**。非恒真形式——变异"恒选 A"在此红（实测 1.0） |
| `ratioZeroGivesAllB`（:77） | `ratioA=0` ⇒ 7 格全 B（`nextDouble() ∈ [0,1)` ⇒ 无需特判） |
| `ratioOneGivesAllA`（:89） | `ratioA=1` ⇒ 7 格全 A（同上，恒 `< 1` 为真） |
| `sameSeedGivesSameResult`（:106） | 两次 `randomize` 的 `MapChangeSet` `equals`（record 值语义）。夹具 ratioA=0.5 严格在 (0,1)，1027 格下 2⁻¹⁰²⁷ 巧合概率 |
| `differentSeedGivesDifferentResult`（:117） | seed 7 vs 8 ⇒ 变更集不同；断言**恰为 489 格**指派不同（重合 538）——随机源可被证伪的护栏 |
| `randomizeIsDeterministicAcrossRegions`（:138） | ★ 补充文件推荐条：孪生区域（hex 集**完全相同** ⇒ 键集相同，分叉只能在值上）同 seed 差 **59/127** 格 ⇒ 证明 **region 进了 RNG 派生**。先断言键集相同（夹具自证），再断言变更集不同 |
| `onlyTargetRegionIsTouched`（:166） | (1) upsert 的 key **恰为** core 的 7 格、序 = `HexCoord` 自然序（`containsExactly`：少一格=偷懒、多一格=越界）；(2) `apply` 后区域外 30 格**逐格 equals 原值**，并断言核对过 30 格（防循环空转的恒真） |
| `onlyTwoTerrainTypesAreUsed`（:191） | `apply` 后的图里产出地形**恰为** {A,B}——`containsExactlyInAnyOrder` 比"⊆ {A,B}"多钉了"两者都出现"（只写 ⊆ 时"恒选 A"照样绿） |
| `heightsArePreserved`（:210） | ★ 补充文件 §3 第 5 行要求补的断言：全图 37 格**逐格高度不变**（区域内外都是）。夹具高度随 `(q+r)` 变化 ⇒ 不是全平，"写死 0.5"的变异在这里红 |
| `rejectsRatioOutOfRange`（:232） | `-0.1` / `1.1` / **`Double.NaN`** 三个值 ⇒ IAE；另断言**空区域上同样抛**（校验先于计算） |
| `rejectsUnknownTerrainKey`（:250） | `terrainA="nope"` 与 `terrainB="nope"` 各一次 ⇒ IAE 且消息含"未知地形类型"（词表原文，不包不吞）；空区域上同样抛 |
| `returnsEmptyChangeSetOnEmptyRegion`（:271） | 三种形态（空区域 `void` / 区域存在但 hexes 全在图外 `outside` / 未知 id `ghost`）：非 null、`isEmpty()`、**7 个组件逐一** `instanceof Unchanged` |
| `doesNotMutateInput`（:299） | **真靶子**：夹具用调用方持有的可变 `LinkedHashMap`（hexes 与 regions 都是）构造 `GameMap`，调用后断言那两份 map 内容与尺寸未变 |

**夹具口径（实测）**：初始地形一律 `mountains`（与 `plains`/`desert` 都不同 ⇒ upsert 的值忠实记录每次指派；若初始地形与目标重合，record equals 会把"同指派"折叠成"没变"，`differentSeed` 就丢判别力）；高度随 `(q+r)` 变化且取值数 > 1（实测 `twoRegionMap` 的 7 个 core 格高度 = 0.3333/0.5/0.6667 三种 ⇒ m12v-5 的靶子不是恒真）；`bigMap` 半径 18 = **1027** 格、`twinMap` 半径 6 = **127** 格、`twoRegionMap` 半径 3 = **37** 格（core 7 / rim 30）、`emptyShapeMap` 半径 1 = 7 格。

## 四、变异证据表（6 轮 = 补充文件 5 行 + 1 条补充轮）

**共同自证头**（6 轮全部满足，逐轮从 `.kept` 可查）：

- 干净世界：每轮 `rm -rf` 后从工作树 rsync 全新建副本（排除 `target/`），逐文件与 md5 清单一致、**共 108 个 .java、无多余文件**；
- **改前先绿**：6 轮的改前跑都是 `Tests run: 156 / 221, Failures: 0`、BUILD SUCCESS（护栏在未变异世界里是绿的）；
- 变异体写进**目标类名**的文件（`generate/RegionRandomizer.java`），声明文件集合 = 实际改动集合；
- **原件 md5 = `af8a6f0366a324f4427d67343f81fa86`（6 轮相同**，每轮起点同一份原件；该 md5 与工作树当前文件**逐字节一致**，实测 `md5sum`）；
- **改后 `COMPILATION ERROR count = 0`（6 轮全 0）**、**simos-map 22 个测试类真的跑过**（红了必须是断言红，不是构建挂在用例之前）。

| 轮 | 变异（md5 原件 `af8a6f03…` → 变异体） | 期望 | 实测红点（测试名:行号 + 断言消息原文） |
|---|---|---|---|
| m12v-1 | 随机源不看 seed：`rngFor` 返回 `new Random()`（★ 锚点已随修复移到 helper 的返回行）→ `0ad71f66…` | 红 | **3 红**：`sameSeedGivesSameResult:112`（两条 1027 项的 `MapChangeSet` 不等，消息即两份完整 Upsert 集的 diff）、`differentSeedGivesDifferentResult:130`：`expected: 489 but was: 519`、`randomizeIsDeterministicAcrossRegions:156`：`expected: 59 …`——**目标用例红** ✓，另两条是同一随机源被替换后的合法连带 |
| m12v-2 | 目标改成全图（`target.hexes()` → `map.hexes()`）→ `8266189c…` | 红 | **2 红**：`onlyTargetRegionIsTouched:174`：[upsert 的 key 恰为 core 的 7 格，序 = 自然序（实测）] `Expecting actual: ["-3_0", "-3_1", "-3_2", …]`（全图 37 格都进了 upsert）——**目标用例红** ✓；连带 `returnsEmptyChangeSetOnEmptyRegion:277`（[void ⇒ isEmpty()]，全图模式让"空区域"也非空了） |
| m12v-3 | 不看 ratioA，恒选 A → `ea6abdbc…` | 红 | **5 红**：`ratioIsRespectedStatistically:72`：[A 的实测占比（实测 559/1027 = 0.5443）] `Expecting actual: 1.0 to be close to: 0.5`；`ratioZeroGivesAllB:84`：[ratioA=0 ⇒ 7 格全 B] 打出全 `plains` 的 7 项——**brief 点名的两条都红** ✓；连带 `onlyTwoTerrainTypesAreUsed:202`、`differentSeedGivesDifferentResult:130`、`randomizeIsDeterministicAcrossRegions:148` |
| m12v-4 | 整条删掉 ratioA 范围校验 → `ead4dd3e…` | 红 | **仅 1 红**：`rejectsRatioOutOfRange:235` `java.lang.AssertionError: Expecting code to raise a throwable.`（循环第一个值 `-0.1` 就抛不出来 ⇒ 立刻红，**走不到 NaN 那一格**）。其余 12 条全绿 |
| m12v-5 | 高度也一起改：`new HexCell(terrain, 0.5)` → `49332098…` | 红 | **仅 1 红**：`heightsArePreserved:219`：[格 -1_0 的高度不变] `expected: 0.3333333333333333 but was: 0.5`——**若无这条用例，本变异全绿**（补充文件 §3 第 5 行点名的靶子）✓ |
| ★ m12v-6（**补充轮，超出 brief 4 行表**） | 范围校验写成"或"形态 `ratioA < 0.0 \|\| ratioA > 1.0`（-0.1/1.1 照样抛，**只有 NaN 漏过**）→ `072f3f19…` | 红 | **仅 1 红**：`rejectsRatioOutOfRange:235`（`Expecting code to raise a throwable.`）。**为什么必须是 NaN 那一格**：见下面的定点测量 |

### 为什么补了 m12v-6（自审发现的判别力缺口）

m12v-4 证明了"删掉校验 ⇒ 用例红"，但它红在**第一个值 `-0.1`** 上——**循环当场中断，NaN 那一格从未被走到**。也就是说：单看 m12v-4，**"NaN 断言"与"-0.1 断言"的判别力无法区分**，R-12-g 仍有"没人守"的可能。补一轮**只有 NaN 能打到**的变异形态（"或"形态）后仍是 1 红；再对**变异体自己的编译产物**逐个喂三个值做定点测量（`/tmp/m12probe/Probe6.java`，classpath 指向 `/tmp/m12lab/repo/simos-map/target/classes`）：

```
### 变异体 m12v-6（或形态）
ratioA=-0.1 ⇒ 抛 IAE: ratioA 必须在 [0,1]: -0.1
ratioA=1.1  ⇒ 抛 IAE: ratioA 必须在 [0,1]: 1.1
ratioA=NaN  ⇒ **未抛**（静默漏过）
### 原件（工作树，非或形态）
ratioA=-0.1 ⇒ 抛 IAE: ratioA 必须在 [0,1]: -0.1
ratioA=1.1  ⇒ 抛 IAE: ratioA 必须在 [0,1]: 1.1
ratioA=NaN  ⇒ 抛 IAE: ratioA 必须在 [0,1]: NaN
```

⇒ 循环序 `(-0.1, 1.1, NaN)` 里前两个在变异体下**照样抛**，**唯一能红的就是 NaN 那一格**；而 NaN 在原件下确实抛。**R-12-g 的守卫与它的用例是彼此对上的**（这才是"NaN 那条不是装饰"的实测证据；m12v-4 单独做不到这一点）。

## 五、门禁

`./mvnw -q spotless:apply`（rc=0）→ `./mvnw verify` ⇒ **BUILD SUCCESS，rc=0，`[ERROR]` 行数 0**（日志：`task-12-evidence/gate-clean-verify.txt`，末尾附 `verify rc=0`）。
实测：Spotless `54 files clean` ✓ ／ Checkstyle ✓ ／ **SpotBugs 五个模块 `BugInstance size is 0`** ✓（修复前 MapSimos 是 `size is 1`）／ Surefire：UtilSimos **156** + MapSimos **221**（= Task 11 的 208 + 本任务 13）+ CoreSimos **15**，Failures 0、Errors 0。
迭代期命令：`./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RegionRandomizerTest test`。

## 六、Files changed

**新增（仅此两个源文件，未改任何既有文件）**
- `simos-map/src/main/java/io/mosire/simos/map/generate/RegionRandomizer.java`（109 行）
- `simos-map/src/test/java/io/mosire/simos/map/generate/RegionRandomizerTest.java`（407 行，13 条；**测试文件本轮一字未改**）

**证据**（`.superpowers/` 下、gitignored，以 `git add -f` 入库）：`task-12-evidence/{run.sh, mutate.py, rounds/m12v-1..6.kept, gate-clean-verify.txt}` + 本报告。
`run.sh`/`mutate.py` 相对前一版只有两处改动：m12v-1 的锚点移到 `rngFor` 的返回行（修复所致），新增 m12v-6 一条与 `TARGET` 表对应项。

## 七、Self-review 发现

1. **m12v-4 打不到 NaN**（§四末尾）：这是本轮自审最主要的发现。补了 m12v-6 + 定点测量，缺口已闭合；但**装置本身**（`mutate.py` 的注释）也写明了原因，避免下一个人把 m12v-4 当"NaN 已被守"的证据。
2. **`rejectsRatioOutOfRange` 的失败消息只有一句 `Expecting code to raise a throwable.`**：AssertJ 在"没抛"的失败形态下**不回显 `.as("ratioA=%s", bad)` 的描述**（实测：m12v-4/m12v-6 的 surefire 原文都没有 `ratioA=`），栈帧只给到行号 235。⇒ **只看 `.kept` 无法判断是哪个值红的**，这正是 §四 要做定点测量的原因；这一点值得写进装置坑清单（"红点的可读性不足以定案时，要另外量"）。
3. 前一版实现者把语义复核的数字（559/489/59）写进了**测试类注释**——本轮重跑探针逐字复现了它们，故这些注释可以照原样留下，不算"没实测过的期望输出"。
4. `heightsArePreserved` 夹具的 37 格里高度取值数为 3 种（0.3333/0.5/0.6667，实测）——`0.5` 这个值本身在夹具里存在，m12v-5 写死 0.5 时**只有那部分格**会红（实测红在 `-1_0`，height 0.3333）⇒ 断言是逐格比对，不受"巧合相等"影响。

## 八、Concerns / 留给下游

1. **`@` 一个 region 一个 RNG 的实现靠 helper 的"结构"满足 SpotBugs**：把 `rngFor` 内联回 `randomize` 会**立刻**重新触发 `DMI_RANDOM_USED_ONLY_ONCE`（检测器是过程内的）。已在 helper 的 Javadoc 里写明"别为了省一个方法内联回去"，但**编译期没有护栏**——只有 `verify` 会响。**建议记台账**：若将来 `RegionRandomizer` 被重构成每格一个 RNG（那才是检测器本意的适用形态），这条注释与 SpotBugs 的关系要重读。
2. **`terrainA.equals(terrainB)` 不设守卫**（补充文件 R-12-h 裁定为合法输入，记留观）：两种地形相同时占比退化、变更集仍会 upsert（值等于原值的 upsert 不是 no-op）。下游消费方别假设"upsert 的格一定变了地形"。
3. **`rejectsRatioOutOfRange` 的 NaN 断言不可从失败日志反推**（§七-2）：本轮靠 m12v-6 + 定点测量补上，但**这条测量没进装置**（跑的是 `/tmp` 探针，不在 `run.sh` 的轮次里）。将来的限域重审若要复现，需重跑 `Probe6`（或把该形态固化为 m12v-7）。
4. **变异装置只跑 `test`、不跑 verify**（沿用 Task 4~11 的形态）：所以 m12v-1 的"helper 里 `new Random()`"形态**不会被 SpotBugs 检查**——这是有意的（装置判的是测试判别力，不是门禁），但意味着**装置绿 ≠ 门禁绿**，两者本轮分别跑过。
5. 测试文件 407 行对 109 行的实现（约 3.7:1）——比 M1 的教训好得多，但 §四 的 6 轮实验日志总量（`.kept` 合计 ~156 KB）仍然大于代码本身，其中 m12v-1.kept 单轮 120 KB（两条 1027 项变更集的完整 diff 被 AssertJ 打出来）。**若要压缩**：`run.sh` 的 `head -80` 可对 `sameSeedGivesSameResult` 这类"整集 dump"型失败改成只留首尾若干行。本轮**未改**（不动前一版实现者已验证的装置形态）。
