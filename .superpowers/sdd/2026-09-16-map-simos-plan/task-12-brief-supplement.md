# Task 12 补充 —— 派单前扫描的裁定（控制器产出）

> 与 `task-12-brief.md` 一起读，**两者冲突处以本文件为准**（brief 是计划期产物，本文件是执行期扫描后的裁定）。
> 完整扫描表与理由在 `progress.md` 的「Task 12 预扫描」；本文件只列**你要照办的东西**。
> brief 的签名、11 条用例名、4 行变异表都成立，本文件是它们的**落法**与补漏。

## 1. 算法与取值（照办）

**R-12-a 抽样口径。** 逐格 Bernoulli：`rng.nextDouble() < ratioA` ⇒ 该格取 A，否则取 B。
RNG = `new Random(seed * 31L + region.value().hashCode())`（`String.hashCode` 由 Java 规范钉死 ⇒ 跨 JVM 稳），
**一个 region 一个 RNG**（不是每格一个 —— 与 Task 11 的 `rngFor` 形态**不同**，别照抄那边）。
`ratioIsRespectedStatistically` 两种诚实写法都收：① 大区域（~1000 格）跑一次、|实测占比 − p| ≤ 0.05；
② 小区域 × 1000 个不同种子、合并占比落 ±0.05。**不许**写成恒真的形式（如只断言"两种地形都出现过"）。

**R-12-g 范围校验必须挡住 NaN。** `ratioA` 的判据写成 `!(ratioA >= 0.0 && ratioA <= 1.0)` 再抛 IAE ——
**不能**写成 `ratioA < 0.0 || ratioA > 1.0`：NaN 与任何数比较全是 false，会**静默漏过**。
（同 `HexCell` 构造器里那句"必须排在范围校验之前"的坑，`HexCell.java` 有原文。）

**R-12-h 校验顺序。** 先 `Objects.requireNonNull(map, "map")`、`Objects.requireNonNull(region, "region")`，
再 `TerrainCatalog.of(terrainA)` / `TerrainCatalog.of(terrainB)`（未知 key 抛**它自己的** IAE —— **不包不吞**、
不改写消息），最后 ratioA 范围。**先校验完再算** ⇒ 参数错时一定听得见，与区域空不空无关。
**不**为 `terrainA.equals(terrainB)` 加守卫（规格未提，且它是合法输入 —— 记留观）。

**R-12-i 消费顺序。** 目标格 = `map.regions().get(region).hexes()` 里**同时在 `map.hexes()` 里**的格，
按 `HexCoord` 自然序（`compareTo`）排序后逐格处理；**先查在图纸、不在则跳过且不消费随机数** ——
这样图外的格不影响图内格的结果，R-12-e 的跳过语义也一眼可见。

**R-12-c 重分配 = 新 `HexCell(newTerrain, 原 height)`。** ★ 组件顺序是 **(terrain, height)**
（`HexCell` 是 `record(String terrain, double height)`）；**高度与其它一律不动**（L7：高度是落盘的一等公民，
`HexCell.height()` 是河流的输入 —— 你要是顺手"重算高度"，Task 11 的水系就变了）。

**R-12-b 变更集形态。** 7 个组件里**只有 `hexes` 可能非 `Unchanged`**（`Upsert<HexCell>`，key = `HexCoord.toString()` 的 `"q_r"`），
其余 6 个一律 `Unchanged`；**空/未知 region 或全部目标格都在图外** ⇒ **7 个全 `Unchanged`**
（`Upsert` 构造期拒空，空必须走 `Unchanged`，`isEmpty()` 为 `true` 而不是 `null`）。
插入序 = R-12-i 的处理序（保序：`LinkedHashMap`，**别用 `Map.copyOf`**）。

**R-12-f 不碰** `regions`/`cities`/`terrainTypes`/`pathways`/`pathwayGroups`/`edges`/`spec`。
★ 尤其**不 upsert region 本身**（区域内容没变；改了格的地形不等于改了区域）。

## 2. 用例口径（brief §Step 2 的落法）

- `ratioIsRespectedStatistically`：见 R-12-a。★ **夹具判别力当场量**：你要在报告里写下**实际**实测占比与所用格数，
  不能只写"落在 ±0.05 内"。
- `sameSeedGivesSameResult`：两次 `randomize` ⇒ 变更集 `equals`（`MapChangeSet`/`FieldDelta` 都是 record，值语义）。
  ★ **夹具的 ratioA 必须严格落在 (0,1)**（用 0 或 1 时种子无关 ⇒ 这条用例恒真、判别力为零）。
- `differentSeedGivesDifferentResult`：同上夹具、换 seed ⇒ **变更集不同**。★ 夹具要大到"同种子巧合相等"不可能
  （~1000 格下巧合概率 2⁻¹⁰⁰⁰；小的也行，但你要**当场说明**为什么这个规模够）。这条是 R-12-a 随机源**可被证伪**的护栏。
- `onlyTargetRegionIsTouched`：夹具 = 两个区域（或一个区域 + 区域外的格）。断言**区域外的每一格**（`cs.hexes()` 的
  upsert key 集合里）**一格都不在**；等价地：`MapChangeSet.apply(cs, map)` 后区域外格的 `HexCell` **逐格 equals 原值**。
  ★ 别只断言"区域内的格变了" —— 那对"改全图"的变异体照样绿。
- `onlyTwoTerrainTypesAreUsed`：产出地形 ⊆ {A, B}（对 `apply` 后的图断言，或对 upsert 的值断言）。
- `ratioZeroGivesAllB` / `ratioOneGivesAllA`：边界值。★ `Random.nextDouble()` 落在 **[0,1)** ⇒
  `ratioA = 1` 时 `< 1` 恒真（全 A）、`ratioA = 0` 时 `< 0` 恒假（全 B），**不需要**为边界特判。
- `rejectsRatioOutOfRange`：`-0.1` / `1.1` ⇒ IAE。★ **加一条 `Double.NaN`**（R-12-g 的靶子）——
  这条不补，R-12-g 就是没人守的散文。
- `rejectsUnknownTerrainKey`：`terrainA = "nope"` ⇒ IAE（经 `TerrainCatalog.of`，消息含"未知地形类型"）。
- `returnsEmptyChangeSetOnEmptyRegion`：空区域（`hexes` 为空集）⇒ `cs.isEmpty()` 为 `true` 且 `cs` 非 null；
  还断言 7 个组件都是 `Unchanged`。★ **另加**：区域存在但 hexes 全在图外 ⇒ 同样全 `Unchanged`（R-12-b 的第二种形态），
  以及 **未知 RegionId** ⇒ 同（不抛）。
- `doesNotMutateInput`：夹具用**调用方持有的可变 `LinkedHashMap`** 构造 `GameMap`（构造期会拷贝冻结），
  调用后断言那份可变 map 内容与尺寸未变（真靶子，不是拿不可变对象自比）。

★ **可选但推荐**：`randomizeIsDeterministicAcrossRegions`（两个不同 region、同 seed ⇒ 结果可不同 ⇒ 证明 region 进了 RNG 派生）。
不收也行 —— 变异表的第 2 行已覆盖"忽略 region"。

## 3. 变异表（brief 4 行 + 补充 1 行）

| 变异 | 期望 | 判别的是 |
|---|---|---|
| 忽略 `seed`，用 `new Random()` | 红 | `sameSeedGivesSameResult` |
| 忽略 `region`，改全图 | 红 | `onlyTargetRegionIsTouched` |
| 忽略 `ratioA`，恒选 A | 红 | `ratioIsRespectedStatistically` 与 `ratioZeroGivesAllB` |
| 删掉 ratio 范围校验 | 红 | `rejectsRatioOutOfRange`（★ 含 NaN 那条） |
| ★ 高度也一起改（如 `new HexCell(t, 0.5)`） | 红 | `doesNotMutateInput` 之外的**高度不变**断言 —— **若你的用例没断言高度，这条就该红而你抓不到** ⇒ 先补上"区域外/区域内格的高度逐格不变"的断言 |

★ 变异必须**按白名单推成目标类名**、每轮**干净世界**（`rsync` 排除 `target/`）+ md5 清单 + 改前先绿 +
`COMPILATION ERROR` 计数为 0；**红了要问为什么红**（必须是被保护的那条断言红）。装置照 Task 11 的
`task-11-evidence/{run.sh,mutate.py}` 改造（`LAB=/tmp/m12lab`）—— **不重建新形态**。

## 4. 门禁与交付

- 证据进 `.superpowers/sdd/2026-09-16-map-simos-plan/task-12-evidence/`（`rounds/*.kept` + `gate-clean-verify.txt`）。
- 本机 `grep` 是 ugrep（尊重 ignore、跳隐藏目录）：**搜全仓用 `git grep`**。
- 中文注释/Javadoc；改完跑 `./mvnw -q spotless:apply` 再 `./mvnw -q verify`（硬门禁，含 SpotBugs）。
- 提交信息照 brief：`feat(map): RegionRandomizer——确定性框选随机化`。**绝不 `git add -A`**，只加你写的文件。
- 报告写到 `task-12-report.md`（形态照 `task-11-report.md`：交付物、逐条用例、变异表、自审发现、关切）。
