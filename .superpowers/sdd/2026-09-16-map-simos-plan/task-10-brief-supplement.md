# Task 10 补充 —— 派单前扫描的裁定与实测（控制器产出）

> 与 `task-10-brief.md` 一起读，**两者冲突处以本文件为准**（brief 是计划期产物，本文件是执行期扫描后的裁定）。
> 完整扫描表与理由在 `progress.md` 的「Task 10 派单前的扫描」；本文件只列**你要照办的东西**。

## 0. 控制器已改过并提交的既有代码（勿改回、勿重改）

`4a0c0e8`：`TerrainCatalog` 最高带下界 `0.90 → 0.85`（`plateau.maxHeight` 与 `plateau_mountains.minHeight`
**成对改**，`==` 连续性不变）+ `TerrainClassifier` 的湿度出处 Javadoc 更正 + `TerrainClassifierTest` 一处 Javadoc。
原因见 R-10-g。**除第 4 节列出的三个文件外，不要动其它既有文件。**

## 1. 裁定（照办）

- **R-10-a 种子**：脊线布局用 `new Random(spec.seed())`；噪声用 `new SimplexNoise(spec.seed())` —— **直接用入参 seed，不派生**。
  GSimulator 把 `rng.nextLong()` 派生值写进 contour（`MapGenerator.java:147`），入参因此丢失 —— 那正是 L7 的病灶，不抄。
- **R-10-b 湿度通道（必须移植）**：
  `m = noise.noise2(px * moistureFreq + 500, py * moistureFreq + 500)`，**px/py 是未扭曲的原始坐标**（GSimulator
  `ContourQueryEngine.java:172` 传给 `classify` 的就是 `:135-136` 的 px, py）；`humidity = (m + 1) / 2`；**不夹取**
  （实测 m 全域包络 ±0.71、默认采样域 ±0.60 ⇒ 落 [0,1] 内；且分类器对域外值是总函数 —— 在 Javadoc 注明即可，别写夹取）。
  没有这条，沙漠门恒不过 ⇒ 地图上永远出不了沙漠（词表的谎话在图上重演）。
- **R-10-c `NoiseBands` 加组件 `moistureFreq`**：
  - 位置：`coastFreq` 之后、`shelfScale` 之前；`double`；构造期守卫
    `moistureFreq = requirePositiveFrequency("moistureFreq", moistureFreq);`
    （取 0 ⇒ 气候场退化成常数 ⇒ 沙漠门静默失效，正是该守卫的靶子）。
  - ★ **它是绝对频率**（GSimulator 原式 `px * 0.02`，**不除 mapRadius**）—— 五个海拔频率才是"每单位半径"。
    类 Javadoc「五个频带」→「六个频带（五个海拔带 + 一个气候带）」，U1 段与"为什么每单位半径"段都要点出这条例外。
  - `GenerationSpec.defaults` 里取 **0.02**，行内注释引 `ContourQueryEngine.java:230`。
- **R-10-d 三层废弃噪声不移植**：`hillsNoise` / `plainsNoise` / `patch`（`ContourQueryEngine.java:236-254`）服务的是被 U1
  作废的 9 项词表，7 项词表下无消费者。`ContourQueryEngine.classify` 只抄**湿度那一行**（`:230`）。
- **R-10-e 温度**：调 `classify` 的第三个实参用**命名常量** 0.5（如 `NO_TEMPERATURE_CHANNEL`），Javadoc 说明：M2 没有温度通道
  （GSimulator 也没有），分类器目前不消费它（Task 9 已裁决）。
- **R-10-f 海平面检查**：移植 `height < seaLevel`（`ContourQueryEngine.java:167`）⇒ 水体 terrain 用 **`"ocean"`**
  （GSimulator 的 `"water"` 不在 7 项词表内）。private 常量 `OCEAN = "ocean"`（形如 `TerrainClassifier.DESERT` 的做法）。
  `seaLevel = baseSeaLevel + coastNoise * coastAmplitude`。不移植它 ⇒ `baseSeaLevel`/`coastFreq`/`coastAmplitude` 三参数无消费者。
- **R-10-g 最高带下界已改 0.85**（控制器已提交）：实测 48 张默认图（24 种子 × 2 噪声变体）**没有一格** ≥ 0.90（最大 0.8969）
  ⇒ `plateau_mountains` 结构性产不出。你拿到的词表就是 0.85 版，按它写用例。
- **R-10-h 枚举序**：格子集合取 `HexGrid.withinRadius(new HexCoord(0, 0), spec.mapRadius())`，**按自然序（q 升、r 升）排序后**
  逐格计算，并按该序放进 `LinkedHashMap`。理由：`Set.copyOf` 的迭代序是**散列序**（M2 Task 5 实测过跨 JVM 的哈希盐）
  ⇒ 不排序则落盘序跨进程不稳。`hexesAreInNaturalOrder` 钉住它。
- **R-10-i `SimplexNoise`**：把 GSimulator `SimplexNoise.java`（53 行）**逐字移植**为 `map.generate` 包下的**包私有**类
  （brief 的 Files 只列了 MapGenerator —— 这是控制器的偏差裁定；53 行内联进生成器会让它不可读）。
  Javadoc 注明出处与"名字叫 Simplex、实为 value noise"。
- **R-10-j `contourCacheMax`**：**保留、本任务不消费**（没有 contour 引擎）。给 `GenerationSpec` 该组件的 Javadoc 补一句
  "Task 10 的生成器不消费它（无 contour 引擎）；留待 M2 关账裁决"。**不要**实现任何 LRU。
- **R-10-l `noSecondPathToGenerate` 用反射**：断言（1）名字为 `generate` 的方法声明的恰有一个；（2）它是 `static`、返回 `GameMap`、
  形参表恰为 `(GenerationSpec)`；（3）类里**没有其它 public static 方法**。不做 git grep（单测进程内无法可靠知道仓库根 / VCS 状态）。

## 2. 黄金钉子（写进 `MapGeneratorTest` 的三条新用例）

### 2.1 `measuredSeedProfileMatchesReferencePort` —— 高度管线的保真钉子（seed 42）

对 `MapGenerator.generate(GenerationSpec.defaults(42L))` 的地图，逐行断言
`height` 用 `isCloseTo(期望, within(1e-9))`、`terrain` 精确相等：

| (q, r) | 期望 terrain | 期望 height |
|---|---|---|
| (-80, 26) | `plains` | 0.3230012074591211 |
| (-80, 36) | `low_hills` | 0.5696824967590466 |
| (-80, 38) | `mountains` | 0.6597175038152930 |
| (-68, 62) | `ocean` | 0.32359307124203934 |
| (-67, 67) | `desert` | 0.4629656946763558 |
| (-56, 59) | `plateau` | 0.7848587387403958 |
| (-46, 34) | `plateau_mountains` | 0.8679581119465682 |

- 来源：**控制器对 GSimulator 管线的独立移植**（`placeRidges` + `compute` + `SimplexNoise`，input 变体 = R-10-a），
  坐标为"逐地形 q 升 r 升首个、且离带边界 ≥ 0.004 的格"。
- `(-68, 62)` 那行是**水**（h = 0.3236 ≥ 0.30 而 seaLevel = 0.3344）—— 它同时钉住"海平面检查真的在判水"。
- 全精度字面量：表里给的就是 `%.17g` 实测值（17 位有效数字，二进制往返安全）。**照抄，别四舍五入。**
- ★ **若你的实现与表中值不符**：先按第 5 节的行号对着 GSimulator 源核你的移植（尤其 **RNG 抽取次序** 与
  `Math.max(0, h)` **在** `Math.pow(h, 0.92)` **之前**）；核完仍认为控制器数值有误 ⇒ **报告，不要静默改期望**。
- 容差取 1e-9 是刻意的：把 `wpx * (1.8 / radius)` 写成 `wpx * 1.8 / radius` 只差约 1 ulp（~1e-17），**不该红**；
  而改动任一系数/项（域扭曲、谷地、大陆架…）会差 ≫ 1e-9。

### 2.2 `measuredSeedsProduceEveryCatalogKey` —— 词表可产出性的**图上**守卫

对种子 12 与 42 两张默认图取 terrain 的并集，`containsExactlyInAnyOrderElementsOf(TerrainCatalog.KEYS)`。
Javadoc 记实测依据：48 图扫描里 0.85 下 6/24 种子产出 `plateau_mountains`；这两个种子实测**各自** 7 项俱全
（种子 42 的见证坐标 `(-46, 34)`，种子 12 的 `(62, -11)`）。
没有它：湿度恒 0.5（沙漠消失）或最高带被挪回 0.90（高原山地消失）都能全绿。

### 2.3 `hexesAreInNaturalOrder`

`map.hexes()` 的迭代序按下标逐元素按 `HexCoord` 自然序排好（`assertThat(new ArrayList<>(keySet())).isSortedAccordingTo(...)`）。
Javadoc 写明理由 = R-10-h（散列盐使 `Set.copyOf` 的序跨 JVM 不稳，落盘序必须确定）。

## 3. 变异实验室（brief 的 4 行 + 本文件补 4 行，共 8 行）

| 编号 | 变异 | 期望红点 |
|---|---|---|
| brief-1 | `new Random()` 替代从 `spec.seed()` 派生 | `sameSeedGivesIdenticalMap` |
| brief-2 | `generate` 加一个形参 | `generateHasExactlyOneParameter` |
| brief-3 | 结果不带 `spec` | `specIsCarriedOnTheResult` |
| brief-4 | `terrainTypes` 改 `Map.copyOf` | `terrainTypesComponentIsTheCatalog` 的顺序半 |
| **m-5** | 湿度恒 0.5（不接 `moistureFreq` 噪声） | `measuredSeedsProduceEveryCatalogKey`（desert 消失）+ §2.1 的 desert 行 |
| **m-6** | 删海平面检查（永不判水 / 水判成带地形） | §2.1 的 ocean 行（h = 0.3236 落回 plains） |
| **m-7** | 高度管线改一处（如域扭曲幅度 10→0，或删谷地项） | §2.1（height 差 ≫ 1e-9） |
| **m-8** | 枚举不排序（直接迭代 `withinRadius` 的 Set） | `hexesAreInNaturalOrder` |

装置沿用 Task 9 的 `task-9-evidence/{run.sh,mutate.py}` 形态（证据放 `task-10-evidence/`）：干净世界（rsync 副本 + md5 清单 +
文件数不得多不得少）→ 改前全绿 → 变异（写成**目标类名**、先核对 md5 与原件字节不同）→ `COMPILATION ERROR count = 0`
→ 红点（测试名 + 行号 + `expected/but was` 原文）。**红了问为什么红、没红问为什么没红**，逐行如实入表。

## 4. 你要动的既有文件（Task 8 的 record 加组件）

1. `NoiseBands.java` —— 加组件 + 守卫 + Javadoc（R-10-c）。
2. `GenerationSpec.java` —— `defaults()` 里 `NoiseBands` 构造点加 `0.02`（`coastFreq` 之后）+ 行内注释。
3. `GenerationSpecTest.java` —— 三个夹具跟着加一个位置参数：`noiseWithGamma`（透传 `b.moistureFreq()`）、
   `bandsWith`（签名 + 实现）、`frequencyRejected`（签名 + 转调）；**并给 `noiseFrequencyMustBePositiveAndFinite`
   添 moistureFreq 的拒收行**（0 / NaN / +∞ 各一行，形态照抄既有行）。`scalars` / `subRecords` 不受影响。
4. **不要动** `TerrainCatalog` / `TerrainClassifier` / `TerrainClassifierTest`（已被控制器改好、已提交 `4a0c0e8`）。

## 5. 移植时的行号地图（**只读** `~/DevMosire/GSimulator`）

- `gsim-map/.../MapGenerator.java:56-118` `placeRidges` —— ★ **逐次 RNG 抽取的次序与次数照抄**（含
  `i == 0 ? 0 : …` 不抽取、`nextBoolean` 的先后），次序变一处就换一张图。
- `MapGenerator.java:129-157` `generateContour` —— `baseSeaLevel = 0.18 + (1 - landRatio) * 0.05`（landRatio 0.55 ⇒
  0.2025 = spec 默认值的来源）；五个频率 = 分子 / radius（**先除好再用**，见 §2.1 容差说明）。
- `ContourQueryEngine.java:125-175` `compute` —— 域扭曲 → 脊线 → 大陆架 → 多频带 → 谷地 → 合成
  （`ridgeH*0.68 + shelf*0.35 + multi*0.45 - valley`）→ `Math.max(0, …)` → `Math.pow(…, 0.92)` → 海岸噪声 → 海平面 → 判水 → 分类。
- `ContourQueryEngine.java:181-205` `computeRidgeHeight` / `computeValleyPenalty`；`:207-227` `distToRidge`。
- `ContourQueryEngine.java:229-258` `classify` —— **只抄 `:230` 的湿度行**（R-10-d）。
- `SimplexNoise.java` 全文（53 行）。
