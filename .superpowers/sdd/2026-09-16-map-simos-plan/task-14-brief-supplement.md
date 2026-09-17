# Task 14 补充 —— 派单前扫描的裁定（控制器产出）

> 与 `task-14-brief.md` 一起读，**两者冲突处以本文件为准**。权威口径是 M2 spec §9.1（逐 L 的守卫定义），
> 本文件把它落成可执行的判据形态，并钉死派单前**实测**出来的白名单。

## 0. 形态总述

- 交付 = **9 条守卫 + 9 轮变异自证**（每轮证明"实现被破坏 ⇒ 对应用例红"）。
- **两个测试文件**（计划 Step 1 原文允许"或按缺陷分文件"）：
  - `simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java` —— L1/L2/L3/L4/L6/L7/L8/L9 八条；
  - `simos-map/src/test/java/io/mosire/simos/map/region/RegionIndexGuardTest.java` —— L5 一条（理由见 R-14-a）。
- 每条用例名带 `L1`~`L9` 前缀（如 `L1_edgesSurviveRoundTripOnNonRoot`），**每条一行注释指向它的缺陷来源**。

## 1. 逐条判据（R-14-b，别自由发挥）

- **L1**（子节点写 `edges` 静默丢失）：`MapChangeSet.between(base, target)` → `apply(cs, base)` 往返。
  夹具 = **只有 `edges` 一个组件变**、其余 6 个组件逐字段相同的图对；断言 `apply(...).edges()` 与 target 的 edges **逐项相等**，
  **且** `between` 出来的 cs 第 7 组件**不是 `Unchanged`**（证明 edges 真的进了 diff）。
  ★ 这就是 simos 侧的"非 root"形态：GSimulator 只在**非 root 写**时丢数据；simos 的对应物 = **只有一个（子）组件变的变更集**。
  只写"全组件都变"的往返（Task 7 的 `everyGameMapComponentParticipatesInTheChangeSet` 已有）会被别的组件的重建**掩盖**。
- **L2**（双份连通性存储）：反射 —— `HexCell.class.getRecordComponents()` 的名字集合**恰为** `["terrain","height"]`（冻结字面量）。
- **L3**（方向数组错位）：① `HexDirection` 是 enum 且 `values().length == 6`；② 代数：六项逐个 `opposite(opposite(d)) == d`、
  `next().prev() == d`、`next()` 连跑 6 次回原项；③ **源码扫描**：`simos-map/src/main` + `simos-util/src/main` 里 `int[][]` 命中数 **== 0**
  （实测现在就是 0）。★ 反射证不了"没有第二份"，"全仓只有一份"必须读源码树 —— 这是计划 Step 2 的原话。
- **L4**（三个 region 概念）：源码扫描 —— ① `simos-map/src/main` 里声明的类型名（`class|interface|record|enum`）中，
  含 `Province`/`Territory`/`Zone` 的**为 0**；② `region` 包的顶层类型集合**恰为**冻结字面量
  `{Region, RegionId, RegionIndex, RegionBoundary, RegionMeta}`（控制器 2026-09-17 实测 `ls`）。
- **L5**（Province 归属线性扫描）：见 R-14-a。夹具照 spec §9.1 加大（1000 hex × 100 region 更好），
  断言**一次 `regionOf` 的 Map 访问计数 == 1**。
- **L6**（坐标表述不一致）：源码扫描 —— ① `"_"` 拼接（形如 `q + "_" + r`）在 `simos-map/src/main` **剔除注释行后**
  命中**恰为 1 处**（`hex/HexCoord.java:85` 的 `toString`），白名单**写死**为这一个文件；
  ★ **必须剔注释**：实测 `hex/HexVertex.java:9` 的 Javadoc 里就有一句 GSimulator 的 `cornerKey(...) + "_" + ...`，
  不过滤注释就得把 Javadoc 也列进白名单，判别力被稀释。② `HexCoord` 的 record 组件恰为 `["q","r"]`。
- **L7**（无海拔无种子落盘）：① **JSON 往返** —— 用 `ObjectMapper`（jackson-databind 经 `simos-util` 传递，测试期可用；
  控制器实测 simos-util/pom.xml 无 `<scope>` ⇒ compile）把带**非平凡 height**（≢ 0/1）与 `spec.seed`（≢ 0）的 `GameMap`
  序列化→反序列化 ⇒ `equals` 原对象，并**逐字段**断言某格 `height` 与 `spec().seed()`；② 同 seed 生成两次 ⇒ 结果相等
  （在 L7 里**独立再钉一次**，不许只写注释说"MapGeneratorTest 已有"）。
  ★ 若 record 的 JSON 往返真跑不起来：允许加**最小必要**注解，但**不许**把用例降级成"只断字段存在"；跑不通就按"未核实"进报告的 Concerns。
- **L8**（12 参数构造复制）：① 反射 —— `GameMap.class.getDeclaredConstructors()` **恰 1 个**且 `getParameterCount() == 8`；
  ② 源码扫描 —— `new GameMap(` 在 `simos-map/src/main` 的位置集合**冻结为白名单**（控制器实测 11 处：
  `GameMap.java` 9、`change/MapChangeSet.java` 1、`generate/MapGenerator.java` 1）；新增调用点即红。
- **L9**（地形词表分裂）：① `TerrainCatalog.KEYS` 恰 7 项且**写死**（U1 词表）；② 阈值结构性断言（并集恰 `[0,1]`、
  两两不重叠、逐项升序 —— `TerrainCatalogTest` 有现成形态可参照，但 L9 这条要在本文件里独立成立）；
  ③ 源码扫描 —— 词表 key 中**只在 `TerrainCatalog.java` 出现的四项** `{low_hills, mountains, plateau, plateau_mountains}`
  （控制器实测）**不得**在 `simos-map/src/main` 的其它文件里出现。`ocean`/`plains`/`desert` 三词有**合法常量**
  （实测 `MapGenerator.java:50`、`TerrainClassifier.java:41/44`）⇒ **不进**这条扫描。

## 2. R-14-a：L5 与其余 8 条分文件、放 `region` 包

靶子变异是"`regionOf` 改线性"——**行为级判据在语义上分不出来**（重叠裁决仍是同一答案），只有**计数注入**能红。
注入点是 `RegionIndex(Map<HexCoord,RegionId>)` 这个**包私有**构造器；Task 3 的 `RegionIndexTest#regionOfIsConstantTime`
已经用它（`CountingMap extends HashMap` 形态在 `RegionIndexTest:92`，可照抄形态、别去改它）。
`RegressionGuardsTest` 在 `io.mosire.simos.map` 包 ⇒ 进不去 ⇒ L5 单独成文件放 `io.mosire.simos.map.region`，
用例名仍是 `L5_regionOfIsIndexedNotScanned`。

## 3. R-14-c：扫描器的统一实现口径（4 条扫描别各写各的）

- **仓库根定位**：从 `Path.of("").toAbsolutePath()`（surefire 下 = 模块根 `simos-map/`）**向上找**含 `pom.xml` 且
  artifactId 为 `simos-parent` 的目录；**找不到就 `fail`** —— 默默跳过 = 恒真的假护栏。
- 只扫 `src/main`；注释行（trim 后以 `*`、`/*`、`*/`、`//` 开头）按需剔除（L6 **必须**剔；L3/L4/L9 剔不剔**当场实测决定**，
  把决定写进注释）。
- 扫描器**自己要有自证**：至少一条"扫到的文件数 > 0"的断言（防路径写错导致零命中恒真）。
- ✍ 本机 `grep` 可能是 ugrep（尊重 ignore、跳隐藏目录），但**用例里读的是文件系统**（`Files.walk` 一类），不受此影响；
  你自己在 shell 里核白名单时一律用 `git grep`。

## 4. R-14-d：9 轮变异（计划 Step 3 那 9 行），装置照 `/tmp/m14lab` 改造

★ 与 Task 12/13 的**关键差别**：本任务的变异体**包含新增文件**（L3 的 `DIRS` 常量可加在新文件里、L4 的第二个 region 类型、
L9 的第二份地形表）。装置的自证头要跟着改：
- "声明文件集合 == 实际改动集合"要按**「修改 + 新增」的并集**比（`md5sum -c` 只覆盖清单里已有的项，**看不到新增**）；
- **每轮开跑前断言上一轮的新增文件真的没了**（干净世界的 rsync 会冲掉 `/tmp` 副本，但**要当场验**，否则第 2 轮起
  扫描类变异互相污染，"红"就不是本轮的）。
- 每轮红点必须落在**对应**的 `L?` 用例上（`L1_…` 红在 `L1_…` 上，别拿"红在别的用例"充数）；
  **红了要问为什么红**（是那条被保护的断言红，不是夹具/编译/装置挂在别处）。

## 5. R-14-e：门禁、证据、提交、报告

- `./mvnw -q spotless:apply` → `./mvnw -q verify`（硬门禁，含 SpotBugs）⇒ rc=0 且 `[ERROR]` 行数 0；日志进
  `task-14-evidence/gate-clean-verify.txt`。
- 证据目录 `task-14-evidence/`（`rounds/*.kept` + 装置脚本），gitignored、以 `git add -f` 入库。
- 提交信息 `test(map): L1~L9 逐条守卫 + 逐条自证`；**绝不 `git add -A`**；提交到本地分支即可，**不推送**；不派子 Agent。
- 报告 `task-14-report.md`：逐条守卫（用例名 + 落在哪个类 + 判据形态）/ 9 轮变异表（**每轮红点原文** + 自证头）/
  门禁实测数字 / Self-review 发现 / **"我未能核实的"清单** / Concerns。
- ★ 报告里每个 Expected 与实测数字都必须是**当场跑出来的**。
