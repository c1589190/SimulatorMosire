# Task 10 报告：`MapGenerator` —— 唯一生成入口、seed 随结果落盘、同 seed 可复现

**状态：DONE** ｜ 分支 `feat/m2-map-simos` ｜ 提交 `9427099`（父 `bdcf744`）+ `b7126ed`（fix-1，注释数字校正）
｜ 交付：`MapGenerator`（374 行）+ `SimplexNoise`（59 行）+ `MapGeneratorTest`（246 行）；simos-map 197 用例全绿，`./mvnw verify` 全绿

---

## 一、实现了什么

- **唯一入口** `public static GameMap generate(GenerationSpec spec)`（`MapGenerator.java:93`）。公开静态面只有这一个方法，由 `noSecondPathToGenerate` 反射钉住。
- 实例管线 `new MapGenerator(spec).build()`；构造期把 **`spec.seed()` 直接**喂给脊线布局的 `Random` 与噪声的 `SimplexNoise`（**R-10-a**：不派生——GSimulator 把 `rng.nextLong()` 的派生值写进 contour，入参反而丢失，那正是不可复现的根因）。不读任何环境量。
- `build()`：`placeRidges()` → 逐格 `sampleAt()` → 按**自然序（q 升、r 升）**放进 `LinkedHashMap`（**R-10-h**：`HexGrid.withinRadius` 内部是 `Set.copyOf`，迭代序=散列槽位序，不排序则落盘序跨进程不稳）。区域/城市/线/组/边一律 `Map.of()`，词表用 `TerrainCatalog.defaults()` **本身**。
- `sampleAt()` 的高度管线逐行移植 `ContourQueryEngine.compute`：域扭曲（幅度/频率来自 spec）→ 脊线衰减 → 大陆架 → 三频带 → 谷地惩罚 → 合成 → `Math.max(0, h)` → `Math.pow(h, gamma)` → 海岸噪声定海平面 → **判水在分类前**（R-10-f，水下高度**原样记**，不记 0）→ 分类。频率**先除好再用**（保真：`wpx * (1.8 / radius)` 而非 `wpx * 1.8 / radius`）。
- 陆地格才接湿度：**未扭曲**的 px/py（R-10-b）、**绝对频率 0.02**（不除半径）；`(m + 1) / 2` 后**不夹取**（分类器对域外输入是总函数）；温度通道传占位 0.5。
- `placeRidges()` 的三个循环**逐次 RNG 抽取的次序与次数照抄**（含 `i == 0` 一签不抽的三元、`nextDouble` 先于 `nextBoolean`、每点 x 先于 y）。`mainRidges` 在这里**不再夹取**（spec 构造期已限 [1, 2]，GSimulator 的 `Math.max(1, Math.min(mainCount, 2))` 就是被根除的那族静默夹取）。
- **三层废弃噪声不移植**（`hillsNoise`/`plainsNoise`/`patch`，服务被 U1 作废的 9 项词表）；**U1**：类内零高度带阈值；本类常数除六边形→像素几何映射（`px = q + r*0.5` / `py = r*0.8660254`，照抄 GSimulator `:135-136`）外只剩四类、均不承载可调语义（相位平移 / 退化线段判据 / 温度占位 / 水体 key）。
- `SimplexNoise`（package-private）逐行移植 `com.gsim.map.service.SimplexNoise`：`noise2` 名字叫 Simplex、实为 value noise，无状态。
- **不消费 `contourCacheMax`**（没有 contour 引擎可缓存）——已在 `GenerationSpec` 的 `@param` 逐字注明，留待 M2 关账裁决。

## 二、用例（13 条 = brief 10 条 + 补充文件 §2 的 3 条）

| 用例 | 关键口径 |
|---|---|
| `sameSeedGivesIdenticalMap` | **L7 核心**：同 spec 两次独立调用逐格相等 |
| `differentSeedGivesDifferentMap` | 反证：上条不是"所有图都相等"而恒真 |
| `specIsCarriedOnTheResult` | seed 随**结果**落盘（复现无需外部输入） |
| `generateHasExactlyOneParameter` | 反射：唯一 `generate`，形参恰为 `GenerationSpec` |
| `noSecondPathToGenerate` | 反射：static / 返回 `GameMap` / 形参恰一个，且**类里没有别的 public static 方法**（GSimulator"MCP 路径不写 contour"那种岔路的守卫） |
| `radiusGivesExpectedHexCount` | r=1 ⇒ 7 格、r=2 ⇒ 19 格（`3r(r+1)+1`） |
| `everyHexHasFiniteHeightInRange` | 全部高度 ∈ [0,1] 且有限（真执行者是 `HexCell` 构造期守卫——"生成全图没一格越界"的活着证明） |
| `everyHexTerrainIsInCatalog` | 地形 key 恒在 `TerrainCatalog.KEYS` 内 |
| `terrainTypesComponentIsTheCatalog` | 结果是**词表本身**且**顺序一致**（`Map.copyOf` 会让同一份数据产出不同字节） |
| `generatedMapRoundTripsThroughChangeSet` | 接 Task 7：整张图过变更集往返；base 必须自带**同一个** spec（spec 不进变更集） |
| `measuredSeedProfileMatchesReferencePort` | ★ 黄金钉子：7 地形各一格，地形名精确相等 + 高度差 ≤ 1e-9（见下 delta 表） |
| `measuredSeedsProduceEveryCatalogKey` | ★ 种子 12 与 42 两张默认图的 terrain **并集 = 全部 7 个 key**（词表里躺着产不出的地形 = GSimulator 旧病） |
| `hexesAreInNaturalOrder` | 逐元素按下标检查落盘序 = 自然序（R-10-h） |

夹具：`SEED_42` 共享一张默认图（生成 19441 格不便宜）；反例统一走 `specWithRadius` 一个构造点。

## 三、黄金值 delta 表（实测，`golden-probe.txt`）

期望值来自**控制器对 GSimulator 管线的独立移植**（补充文件 §2.1）；产出值是本实现 `GenerationSpec.defaults(42)` 实测。

| 坐标 | 地形 | 期望（控制器） | 产出（本实现） | delta | ulp |
|---|---|---|---|---|---|
| (-80, 26) | plains | 0.3230012074591211 | 0.32300120745912114 | +5.55e-17 | 1 |
| (-80, 36) | low_hills | 0.5696824967590466 | 0.56968249675904660 | 0 | **逐位相同** |
| (-80, 38) | mountains | 0.6597175038152930 | 0.65971750381529300 | 0 | **逐位相同** |
| (-68, 62) | ocean | 0.32359307124203934 | 0.32359307124203934 | 0 | **逐位相同** |
| (-67, 67) | desert | 0.4629656946763558 | 0.46296569467635584 | +5.55e-17 | 1 |
| (-56, 59) | plateau | 0.7848587387403958 | 0.78485873874039436 | −1.44e-15 | 13 |
| (-46, 34) | plateau_mountains | 0.8679581119465682 | 0.86795811194656824 | 0 | **逐位相同** |

- 4 条逐位相同，最大偏差 1.44e-15（13 ulp），全部 ≪ 1e-9 容差。容差取 1e-9 的理由（写进用例 Javadoc）：把频率的两种等价写法（先除 vs 后除）只差约 1 ulp（实测 |px|、|py| ≤ 240 上最大绝对差 7.1e-15、相对差 2.8e-16，`freq-regrouping-probe.txt`）——**不该红**；而改掉任一系数/项会差 ≫ 1e-9 ⇒ 必红（m-7 轮实测 7 格全红）。
- `(-68, 62)` 那格的水位边界：`h = 0.32359…`、`seaLevel = 0.33441…`（`sea-level-trace.txt` 实测），靠判水成 ocean，删掉那一行就变 plains（m-6 轮实证）。
- 全图最大高度 0.86795811194656820（最大值即来自 (-46,34) 那格）；`hexes = 19441`。

## 四、变异证据表（8 轮；装置 `run.sh` + `mutate.py`，证据在 `task-10-evidence/`）

**共同自证头**（8 轮全部满足，逐轮从 `.kept` 可查）：

- 干净世界：副本逐文件与工作树 md5 清单一致、**共 104 个 .java、无多余文件**（8 轮同）；
- **改前**：`simos-map 197 / 0` + `simos-util 156 / 0`，BUILD SUCCESS（护栏先绿）；
- 变异体写进**目标类名**的文件（`generate/MapGenerator.java`），声明文件集合与实际改动集合一致；
- **原件 md5 = `95eb6f4f…`（8 轮相同**，证明每轮起点是同一份原件）；每轮变异体 md5 与原件不同（见下表）；
- **改后 `COMPILATION ERROR count = 0`（8 轮全 0）**、simos-map 20 个测试类真的跑过（红了必须是断言红）。

| 轮 | 变异（md5 原件 `95eb6f4f…` → 变异体） | 期望 | 实测红点（测试名:行号；断言消息原文） |
|---|---|---|---|
| m10v-1 | brief-1：`new Random(spec.seed())` → `new Random()`（噪声那侧仍用入参 seed）。→ `3e183bbf…` | 红（L7） | `measuredSeedProfileMatchesReferencePort:168`（7 格全差：`(-80,26)` plains→ocean、`(-80,36)`→ocean、`(-80,38)`→ocean、`(-68,62)` 高度 0.22951856149876115 vs 0.32359307124203934、`(-67,67)` desert→ocean、`(-56,59)`→ocean、`(-46,34)`→plains）+ `sameSeedGivesIdenticalMap:50`（两次调用产出两张不同的 GameMap） |
| m10v-2 | brief-2 等价形态：多一个 `generate(spec, extra)` 重载。→ `851fcab3…` | 红（单入口守卫） | `generateHasExactlyOneParameter:72->declaredGenerate:243`、`noSecondPathToGenerate:85->declaredGenerate:243`：`[名字叫 generate 的方法只能有一个] Expected size: 1 but was: 2` |
| m10v-3 | brief-3 等价形态：结果带 `GenerationSpec.defaults(0L)`。→ `e68dddb8…` | 红（seed 落盘） | `specIsCarriedOnTheResult:66`（expected: 42L but was: 0L）+ `generatedMapRoundTripsThroughChangeSet:147`（逐格相同、spec 不同 ⇒ 不相等） |
| m10v-4 | brief-4：`terrainTypes` 改 `Map.copyOf`。→ `259babd2…` | 红（词表序） | `terrainTypesComponentIsTheCatalog:134`：keySet 序 `["low_hills","ocean","plateau","plateau_mountains","plains","desert","mountains"]` vs `["ocean","plains","desert","low_hills","mountains","plateau","plateau_mountains"]` |
| m10v-5 | m-5：湿度恒 0.0 ⇒ `(0+1)/2 = 0.5`，沙漠门恒不过。→ `2aa10d23…` | 红（湿度通道） | `measuredSeedProfileMatchesReferencePort:168`（1 格：`(-67,67)` desert→plains）+ `measuredSeedsProduceEveryCatalogKey:197`（produced 集合缺 desert） |
| m10v-6 | m-6：删海平面检查（永不判水）。→ `6ee7df18…` | 红（判水行） | `measuredSeedProfileMatchesReferencePort:168`（1 格：`(-68,62)` ocean→plains）；其余 6 格**不红**（都在海平面之上——判别力恰好落在那一行） |
| m10v-7 | m-7：域扭曲幅度 10 → 0。→ `24d974dd…` | 红（高度管线） | `measuredSeedProfileMatchesReferencePort:168`（7 格全差：如 `(-80,26)` 0.3026989582260413 vs 0.3230012074591211、`(-68,62)` 0.20699231632337714 vs 0.32359307124203934；地形随之漂移 low_hills→plains、mountains→low_hills…） |
| m10v-8 | m-8：格子枚举不排序（直接迭代 `withinRadius` 的 Set）。→ `18b1d83c…` | 红（R-10-h） | `hexesAreInNaturalOrder:209`：`group is not sorted … element 0: 24_11 is not less or equal than element 1: 23_42` |

**红点的行号**都是断言所在行，取自 surefire 的 `Test.method:line` 摘要；每轮 `.kept` 另有 `expected / but was` 原文与 surefire 报告全文。

### 诚实说明（三处）

1. **brief-2 / brief-3 用了等价形态**（`mutate.py` 模块注释预先声明，非事后辩解）：字面改 `generate` 签名会让测试**编译不过**（红了是 COMPILATION ERROR，不算断言红）；"结果不带 spec"写成传 `null` 会被 `GameMap` 构造期守卫抛 IAE（那是**别的**红）。等价形态分别取"多一个重载"与"带空图的规范 spec"——都精确落在被保护的那行上。
2. **m10v-4 / m10v-8 的判别力当场量过**（Task 5 的教训：散列序类夹具"键太少会假绿"）：30 次独立 JVM 启动实测，`Map.copyOf(7 键词表)` 迭代序 30/30 ≠ 插入序、`withinRadius(半径 80)` 的 19441 格 30/30 ≠ 自然序（`orderprobe-30jvms.txt`，各 JVM 哈希盐下首 3 元素各不相同）。⇒ 这两轮的红不是单次 JVM 的巧合。**但这条判别力本质上是概率性的**：单台机器某次凑巧时可能"假绿"——不影响代码本身在跨机器上已错（序不稳定就是 bug）。
3. **重跑记录**：首启一次因 `run.sh` 循环结尾的 `done` 误写成 `}`，bash 解析即死（**未产生任何轮次数据**），修复后跑完一轮完整 lab；随后 fix-1（纯注释数字校正）落地，**整轮重跑**。两轮完整 lab 的红点集合逐条一致；本报告与 `.kept` 用的是**最终树**的那一轮。另：m10v-1 的 `differentSeedGivesDifferentMap` / `specIsCarriedOnTheResult` / `hexesAreInNaturalOrder` **不红**是正确行为（与脊线布局无关：不等式、spec 携带、排序各自独立）。

## 五、门禁

`./mvnw verify`（最终树，未变异）→ **BUILD SUCCESS**（rc=0），日志存 `task-10-evidence/gate-clean-verify.txt`。
逐模块实测：Spotless ✓ / Checkstyle ✓ / SpotBugs（effort=More, threshold=Low）各模块 `BugInstance size is 0` ✓ / Surefire：simos-util **156** + simos-map **197**（其中 MapGeneratorTest 13 条 ≈ 13 s）+ simos-core **15**，Failures 0、Errors 0。
迭代期命令：`./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MapGeneratorTest test`。

## 六、Files changed

**新增**
- `simos-map/src/main/java/io/mosire/simos/map/generate/MapGenerator.java`（374 行）
- `simos-map/src/main/java/io/mosire/simos/map/generate/SimplexNoise.java`（59 行，package-private）
- `simos-map/src/test/java/io/mosire/simos/map/generate/MapGeneratorTest.java`（246 行，13 条）

**修改（仅此三处，与补充文件 §4 一致）**
- `NoiseBands.java`（+14/−4）：加 `moistureFreq` 组件与守卫生成（`requirePositiveFrequency`）、类注释改"六个频带（五个海拔带 + 一个气候带）"、注明 U1 例外（气候带频率是**绝对**频率）
- `GenerationSpec.java`（+4/−2）：`defaults()` 加 `0.02` 与出处注释；`contourCacheMax` 的 `@param` 注明"Task 10 不消费它"
- `GenerationSpecTest.java`（+37/−13）：六个频率 ⇒ 七个（补 `moistureFreq` 的 3 条负例），18 条全绿

**提交**：`9427099 feat(map): MapGenerator——单入口、seed 落盘、可复现`（父 `bdcf744`，消息逐字采用）；`b7126ed docs(map): 按实测校正 Task 10 的三处 Javadoc 数字（M2 Task 10 fix-1）`。工作树干净、除上述文件外零改动。

**证据（`.superpowers/` 下、gitignored，按派单要求不提交，控制器归档）**：
`task-10-evidence/{run.sh, mutate.py, rounds/m10v-1..8.kept, gate-clean-verify.txt, orderprobe.java, orderprobe-30jvms.txt, golden-probe.txt, moisture-envelope-probe.txt, freq-regrouping-probe.txt, sea-level-trace.txt, lab-reinit.txt}`
（m10v-1 / m10v-3 两个 `.kept` 各 2.3 MB：`sameSeedGivesIdenticalMap` 与 `generatedMapRoundTripsThroughChangeSet` 的 AssertJ 失败消息把**整张图**逐格打进 `expected / but was`，属 surefire 原文，未删减。）

## 七、Self-review 发现（已当场处理，即 fix-1）

1. `MapGeneratorTest` Javadoc 原写"最大偏差 1.4e-15 ≈ 8 ulp"——**复核实测为 13 ulp**（bit-pattern 差 13）。改了数。
2. 容差理由里"只差约 1 ulp（~1e-17）"无实测支撑——补测（`freq-regrouping-probe.txt`）后改写为"实测 |px|、|py| ≤ 240 上最大绝对差 7.1e-15、相对差 2.8e-16"。
3. 类注释原写"本类里剩下的常数只有四类"——漏了 `px = q + r*0.5` / `py = r*0.8660254` 两个几何映射字面量（GSimulator `:135-136` 同款），补 carve-out。
4. 湿度包络"±0.60 / ±0.71"复测（4 个种子，`moisture-envelope-probe.txt`）：默认域 |m| ≤ 0.59、细网格 |m| ≤ 0.71 ⇒ 成立；把测量域与种子一并写进 Javadoc（原表述"全域"无定义）。
5. `seaLevel = 0.3344` 注释数字：trace 复核 = 0.33441147706592633 ✓（`sea-level-trace.txt`）。
6. 变异装置 `run.sh` 首启语法错（未产生数据）——修复并重跑，不留"事后补记"。

## 八、Concerns / 留给下游

1. **`contourCacheMax` 无人消费**：生成器没有 contour 引擎可缓存（已在 `GenerationSpec` 的 `@param` 写明）。是删是留，请 M2 关账裁决。
2. **`0.8660254` 是截断常数**（√3/2 只取 7 位有效数字）：GSimulator 原文如此（`ContourQueryEngine.java:136`），且 WebUI 渲染（`MapWebUIHandler.java:766`）用的是同一个截断值。本次逐字移植不引入新偏差；将来若"标定几何"，两处须**同时**改。
3. **黄金钉子依赖控制器的独立移植**：它既是保真网也是回归网——若将来 GSimulator 侧管线被修正，这张表（及测试期望值）要按新裁决整表重算，不能单点微调。
4. **跨机器"字节级"复现未经第二台机器实测**（本机只有一台）：构成条件已全部落地（两个随机源只吃 seed、枚举序显式排序、无环境量、`LinkedHashMap` 保序）；本机跨 JVM 反复生成一致。若 M2 关账要求跨机证据，需在另一台机器跑 `sameSeedGivesIdenticalMap` 的加强版（逐字节比较落盘序）。
5. **MapGeneratorTest 的运行成本 ≈ 13 s**（两张 19441 格全图）；已共享 `SEED_42` 夹具。M3+ 若加更多"整图"用例，考虑把默认图夹具做成测试期共享常量集中管理，而不是每类各生成一张。
