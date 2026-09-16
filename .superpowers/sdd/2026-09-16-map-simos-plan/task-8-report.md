# Task 8 报告 —— `GenerationSpec`：参数面，静默夹取改构造期校验

**任务**：L8（参数面）。把 Task 5 的 seed-only 骨架扩成完整参数面，并把 GSimulator 的"静默夹取"
改造成构造期异常。
**分支**：`feat/m2-map-simos`　**起点**：`c1a2607`（工作树干净）
**简报**：`.superpowers/sdd/2026-09-16-map-simos-plan/task-8-brief.md`

---

## 一、交付物

| 文件 | 动作 | 说明 |
|---|---|---|
| `simos-map/src/main/java/io/mosire/simos/map/generate/GenerationSpec.java` | **改写** | 9 组件的 record；构造期校验；**唯一一份** `defaults(long)` |
| `simos-map/src/main/java/io/mosire/simos/map/generate/NoiseBands.java` | **新建** | 16 组件：五个频带的频率 + 各带权重/整形系数（形状参数） |
| `simos-map/src/main/java/io/mosire/simos/map/generate/RidgeParams.java` | **新建** | 33 组件：主/次级脊线形状 + "脊线 → 海拔"的衰减与谷地参数 |
| `simos-map/src/main/java/io/mosire/simos/map/generate/FragmentParams.java` | **新建** | 10 组件 + 预算切分规则 + **那条可为负的差值的守卫** |
| `simos-map/src/test/java/io/mosire/simos/map/generate/GenerationSpecTest.java` | **新建** | 18 条用例，覆盖简报 Step 3 的 11 条 + 自加的 7 条 |
| `simos-map/src/test/java/io/mosire/simos/map/GameMapTest.java` | **改一行**（R-48-p） | 见 §五 |
| `simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java` | **未改动** | Inspect 结论"无需改动"，见 §六 |

组件数（实测，数 record 头）：`GenerationSpec` 9 / `NoiseBands` 16 / `RidgeParams` 33 / `FragmentParams` 10。

---

## 二、Step 1：GSimulator 现读清单（带行号）

**为什么这一节必须"现读"**：简报明文写"~60 个魔法数字的具体清单执行期现读，本计划故意不给 —— 控制器没有实测过它们"。
下列每一行都是本次执行期在 `~/DevMosire/GSimulator` 上读出来的，**行号当场核过**（不是从记忆里写的）。

### 2.1 `gsim-map/src/main/java/com/gsim/map/service/MapGenerator.java`（247 行）

形参面（`:235-241`，公开入口）：

```java
public static MapData generate(String worldId, long seed, int mapRadius, int mainRidges,
        int fragments, double landRatio, double coastRoughness, int contourCacheMax)
```

| 行号 | 字面量 / 表达式 | 所在方法 | 本任务去向 |
|---|---|---|---|
| `:61` | `Math.max(1, Math.min(mainCount, 2))` | `placeRidges` | ★ **静默夹取**，改成构造期抛 → `mainRidges ∈ [1,2]` |
| `:63` | `rng.nextDouble() * 0.5 + 0.3` | `placeRidges` | `mainCompanionAngleSpan/Min` |
| `:64` | `radius * (1.3 + rng.nextDouble() * 0.4)` | 同上 | `mainLengthMin=1.3` / `mainLengthSpan=0.4` |
| `:67` | `radius * (0.20 + rng.nextDouble() * 0.30)` | 同上 | `mainOffsetMin/Span` |
| `:68-69` | `rng.nextGaussian() * radius * 0.03` | 同上 | `mainStartJitter=0.03` |
| `:71` | `rng.nextDouble() * radius * 0.18` | 同上 | `mainCurveSpan=0.18` |
| `:75-76` | `len * 0.48` / `radius * 0.02` | 同上 | `mainTailLength=0.48` / `mainTailJitter=0.02` |
| `:79-80` | `len * 0.52` / `radius * 0.03` | 同上 | `mainHeadLength=0.52` / `mainHeadJitter=0.03` |
| `:81` | `0.75 + rng.nextDouble() * 0.25` | 同上 | `mainWeightMin/Span` |
| `:85` | `Math.max(2, fragmentCount / 2)` | 同上 | `FragmentParams.secondaryCountFloor=2` / `secondaryCountDivisor=2` |
| `:87` | `rng.nextDouble() * 0.4 + 0.12` | 同上 | `secondaryAngleMin/Span` |
| `:88` | `radius * (0.10 + rng.nextDouble() * 0.28)` | 同上 | `secondaryOffsetMin/Span` |
| `:89` | `radius * (0.50 + rng.nextDouble() * 0.45)` | 同上 | `secondaryLengthMin/Span` |
| `:90-92` | `radius * (0.05 + rng.nextDouble() * 0.22)` | 同上 | `secondaryAlongMin/Span` |
| `:96-100` | `len * 0.5` / `radius * 0.02` | 同上 | `secondaryTailLength`/`secondaryHeadLength`/`secondaryJitter` |
| `:101` | `0.25 + rng.nextDouble() * 0.30` | 同上 | `secondaryWeightMin/Span` |
| `:105` | `fragmentCount - secondary` | 同上 | ★ **可为负的差值** → `FragmentParams.requireNonNegativeRemaining` |
| `:108` | `radius * (0.35 + rng.nextDouble() * 0.50)` | 同上 | `distMin/distSpan` |
| `:111` | `radius * (0.04 + rng.nextDouble() * 0.08)` | 同上 | `lenMin/lenSpan` |
| `:112` | `rng.nextGaussian() * 0.5` | 同上 | `angleJitter` |
| `:114-115` | `flen * 0.5`（两端各一次） | 同上 | `tipLength=0.5` |
| `:116` | `0.10 + rng.nextDouble() * 0.15` | 同上 | `weightMin/weightSpan` |
| `:130` | `0.18 + (1.0 - landRatio) * 0.05` | `generate` | → `baseSeaLevel`（**唯一的** `landRatio` 消费者） |
| `:131-135` | `1.8 / radius`、`3.5 / radius`、`8.0 / radius`、`20.0 / radius`、`3.5 / radius` | `generate` | `NoiseBands` 的五个频率（**存分子**） |
| `:147` | `rng.nextLong()` | `generateContour` | ★ **不可复现的根因**：写进 contour 的是派生值，入参 `seed` 反而丢失 |

★ `:243-245` 是**装饰形参的现场**：

```java
var gen = new MapGenerator(seed, mapRadius, contourCacheMax);
gen.placeRidges(mainRidges, fragments);
return gen.generate(landRatio);          // ← worldId 与 coastRoughness 到此为止
```

`grep -c` 实测：`worldId` 与 `coastRoughness` 在 `MapGenerator.java` 里各出现 **5 次**，全部是
Javadoc `@param`（`:194/:224`、`:200/:230`）、形参声明（`:204/:235`、`:210/:241`）与**重载之间的原样转发**
（`:212`、`:218`）。**没有字段、没有一次真读** —— 最内层那个干活的方法根本不收它们。⇒ 删掉这两个形参有据。

### 2.2 `gsim-map/src/main/java/com/gsim/map/service/ContourQueryEngine.java`（291 行）

| 行号 | 字面量 | 本任务去向 |
|---|---|---|
| `:22` | `static final int MAX_CACHE = 5000` | → `GenerationSpec.contourCacheMax` 的默认 5000 |
| `:98` | `> 2 * contour.getRadius()` | **排除**：地图范围由 `mapRadius` 派生，不是自由参数 |
| `:136`/`:278` | `r * 0.8660254` | **排除**：六边形几何常数（`sqrt(3)/2`） |
| `:139-140` | `px * 0.018`、`* 10` | → `warpFreq=0.018` / `warpAmplitude=10.0` |
| `:147` | `shelf * 0.35 + 0.15` | → `shelfScale=0.35` / `shelfOffset=0.15` |
| `:150-152` | `+100` / `+300` / `+500` | ★ **排除**：给各带去相关的相位平移，见 §四.3 |
| `:153` | `n1*0.40 + n2*0.25 + n3*0.12` | → `lowWeight`/`midWeight`/`highWeight` |
| `:159` | `ridgeH*0.68 + shelf*0.35 + multi*0.45 - valley` | → `heightWeight`/`shelfHeightWeight`/`multiHeightWeight` |
| `:161` | `Math.pow(height, 0.92)` | → `gamma=0.92` |
| `:164` | `+ 77` | ★ **排除**：同 `:150-152` |
| `:165` | `coastNoise * 0.35` | → `coastAmplitude=0.35` |
| `:185` | `k = 5.5 + r.getWeight() * 2.0` | → `decayBase=5.5` / `decayPerWeight=2.0` |
| `:193` | `contour.getRidges().size() < 2` | → `valleyMinRidges=2` |
| `:203-204` | `radius * 0.10`、`* 0.30` | → `valleySigma=0.10` / `valleyWeight=0.30` |
| `:215` | `len2 < 0.001` | **排除**：数值零判定的 epsilon |
| `:229-258` | `classify()`：`0.68 / 0.48 / 0.36 / 0.14 / 0.22 / 0.12`（+ 湿度阈值 `0.10 / 0.0 / 0.50 / 0.28 / 0.42`） | ★★ **一个都没搬过来** —— 这是 U1 禁的那一类（给海拔**分类**），唯一持有者是 `TerrainCatalog` |
| `:237`/`:239` | `2.0 / radius + 900`、`1.5 / radius + 800` | **排除**：`classify()` **内部**的气候/纹理噪声频率，与上面那批阈值纠缠在一起，归属是 Task 9/10 |

### 2.3 默认值的三个分歧副本（spec §6.4 的"唯一一份"）

| 出处 | radius | ridges | fragments | landRatio | coastRoughness | cacheMax |
|---|---|---|---|---|---|---|
| `config/MapConfig.java:36` `DEFAULT = new MapConfig(80, 32, 5000, …)` | **80** | — | — | — | — | **5000** |
| `tools/map/GsimapGenerateTool.java:60-62`（+ `:63-64`） | 80 | **2** | **5** | **0.55** | 0.6 | — |
| `http/MapWebUIHandler.java`（`getOrDefault`） | `mapConfig.defaultMapRadius()` | 2 | 5 | 0.55 | 0.6 | `mapConfig.contourCacheMax()` |

⇒ `defaults(long)` 取 `mapRadius=80` / `contourCacheMax=5000`（`MapConfig.DEFAULT`）、
`mainRidges=2` / `fragments=5`（MCP 工具与 WebUI 的共同默认）、
`baseSeaLevel` 由默认 `landRatio=0.55` 代进 `0.18 + (1-0.55)*0.05` 得到。**全仓只有这一份**。

---

## 三、设计决定（简报没给、由执行期定的）

### 3.1 参数面的**边界**：`GenerationSpec` 装什么

装：GSimulator 的 `MapGenerator` + `ContourQueryEngine.compute` 形状面（上表"去向"列的全部）。
**理由**：Task 10 的 `generate(GenerationSpec spec)` 必须**恰好一个形参**（计划 §Task 10 的
`generateHasExactlyOneParameter`：*"能影响结果的每一个输入都在 spec 里"*），而 spec §6.4 说
"~60 个魔法数字全部提成字段"。装不全 ⇒ Task 10 要么破形参数，要么再开一个旁路。

不装（逐条给理由）：

| 不装的 | 为什么 |
|---|---|
| `:150-152 +100/+300/+500`、`:164 +77` | **去相关用的相位平移**：任意常数，改成任何别的值都只是换一张同样合理的图，不承载语义。做成字段 = 凭空四个"看起来可调、实际调不出东西"的旋钮 |
| `classify()` 的一切（`:229-258`） | 它的高度阈值就是 **U1 禁的那个**；湿度阈值与气候/纹理噪声频率与它纠缠，归属 Task 9/10 |
| `:136/:278 0.8660254` | 六边形几何常数，不是参数 |
| `:215 0.001` | 零判定 epsilon |
| `:98 2 * radius` | 范围由 `mapRadius` 派生 |
| 30 / false 那两个死值 | GSimulator 里就是死的 |

### 3.2 守卫纪律：**只守"错了会静默失效"的**

| 守卫 | 为什么守 | 为什么不守的反面 |
|---|---|---|
| `mapRadius >= 1` | 0 或负会造出空/退化的图 | — |
| `baseSeaLevel ∈ [0,1]` | 越界与 **NaN** 都让 `height < seaLevel` 恒 false ⇒ 整张图**静默变成没有海** | — |
| `mainRidges ∈ [1,2]` | ★ 本任务的核心：GSimulator 的 `Math.max(1, Math.min(mainCount, 2))` 让传 5 **静默变成 2** | — |
| `contourCacheMax >= 1` | 取 0 时缓存**静默失效**（每写一条就逐出） | — |
| 三个子 record 非 null | 它们的字段全是基本类型，null 不在构造期炸，会在生成期以一条**离现场很远**的 NPE 出现 | — |
| `NoiseBands` 六个频率有限正 | 取 0 时该带在整张图上**退化成同一个常数**，这一层尺度**静默消失** | — |
| `fragments` 的负差值 | 见 §3.3 | — |
| — | — | `RidgeParams` 的长度/权重/抖动**不守**：取负只是"另一种分布"，不是静默失效（不替不存在的世界写代码） |
| — | — | `FragmentParams.secondaryCountDivisor` **不守**：取 0 当场 `ArithmeticException`，已经响了 |

写法上的一处刻意：`baseSeaLevel` 写成 `!(a >= 0.0 && a <= 1.0)` 而不是自然形态 `(a < 0 || a > 1)` ——
**前者顺带挡 NaN**（NaN 的任何比较都是 false），后者会把 NaN 放行。这一条有专门的变异轮（m8v-10）证明它不是空话。

★ **一处对简报草图的偏离（有意）**：简报 Step 2 的草图里写着 `if (fragments < 1) throw …`。
本实现**删掉了这一句**，因为 §3.3 的那条守卫**把它整个包住了**：凡 `fragments < 2`，剩余条数必为负
（`secondaryCount` 的下限是 `secondaryCountFloor=2`，与总数无关）。留两句会得到两条同义的抛点，
而它们将来会分叉。**简报的代码草图不是权威**（CLAUDE.md 明写：计划期草图执行期就地校正）。

### 3.3 那条可为负的差值：**规则与守卫都住在 `FragmentParams`**

GSimulator 的 `frags = fragmentCount - secondary`（`:105`）**可以为负**：传 `fragmentCount=1` 时
`secondary = max(2, 0) = 2`，差值 `-1`；那边它只让碎片循环**一次都不执行** —— "我要 1 条碎片"变成
"一条都没有"，**不抛任何异常**。

- 规则（`secondaryCount` / `remainingCount` / `requireNonNegativeRemaining`）住在 `FragmentParams`：
  预算是**碎片这一侧**的账（次级脊线的**形状**在 `RidgeParams`，但"切多少给它"是这里的事）。
- **调用在 `GenerationSpec` 的紧凑构造器里** —— 守卫必须在**构造期**响，这是本任务的定义性要求。
- `fragments` **本身不是 `FragmentParams` 的组件**，它留在 `GenerationSpec` 顶层（签名见 spec §6.4 与简报），
  于是这个数在全局**只有一份**，不会出现"spec 里写 5、params 里写 3"的漂移。

### 3.4 一个必须如实报告的浮点事实：`0.2025` ≠ `0.18 + (1-0.55)*0.05`

`defaults()` 里 `baseSeaLevel` 用的是**字面量 `0.2025`**，注释记的是它的推导过程。
**实测该推导式的值是 `0.20249999999999999`，与 `0.2025` 差 1 ulp** —— 两者 `==` 为 **false**。
⇒ 我**没有**在注释或代码里声称"两者相等"，只在注释里写"由 `landRatio=0.55` 代进 … 得到"（这是**来历**，不是等式）。
选字面量而不是算式，是为了让"规范默认值"是一个**冻结的、可逐字节复现的**常量（L7 的可复现性关心这个），
而不是每次构造时重算一遍浮点表达式。

---

## 四、★ U1：我如何判定 `NoiseBands` 属于**形状参数**而不是第二份高度带

简报把这条判断权**明确交给我**（"控制器没有实测过 `NoiseBands` 的字段，是把判断权交给你，不是已经替你判好了"）。
我的判据与结论如下。

### 4.1 判据：这个数是**在塑造**海拔值，还是在**给**海拔**分类**？

- **塑造**（允许）：它参与算出 `height` 这个**连续量**本身。改它 ⇒ 得到一张**不同但同样合法**的图。
- **分类**（禁止）：它参与把已有的 `height` 切成**离散的类别名**。改它 ⇒ 地形名与高度带的对应关系变了，
  那就是 L9 的第二份词表。

### 4.2 `NoiseBands` 的 16 个字段逐组过一遍

| 组 | 字段 | 判为"塑造"的理由 |
|---|---|---|
| 五个频率 | `shelfFreq` `lowFreq` `midFreq` `highFreq` `coastFreq` | 决定噪声在**空间**上变化多快（每单位半径几个起伏）。它们进 `noise.noise2(px * freq, py * freq)`，出来的是**采样的自变量**，与"高度"这个量的**量纲都不同**。改它 ⇒ 地形的**颗粒度**变，不是地形名的边界变 |
| 域扭曲 | `warpFreq`(0.018) `warpAmplitude`(10) | 采样**坐标**被扰动得多厉害（`ContourQueryEngine.java:139-140`）。改它 ⇒ 同样的噪声被**揉**成不同形状 |
| 各带权重 | `lowWeight`(0.40) `midWeight`(0.25) `highWeight`(0.12) | `:153` 的线性组合系数：`n1*0.40 + n2*0.25 + n3*0.12`。改它 ⇒ 大尺度/小尺度成分的**比例**变 |
| 合成权重 | `shelfHeightWeight`(0.35) `multiHeightWeight`(0.45) `heightWeight`(0.68) | `:159` 的 `ridgeH*0.68 + shelf*0.35 + multi*0.45 - valley`。这三项是**海拔的加法分解**，不是切分 |
| 大陆架整型 | `shelfScale`(0.35) `shelfOffset`(0.15) | `:147` 的 `Math.max(0, shelf*0.35 + 0.15)`：把一个噪声**线性映射**成大陆架高度 |
| 海岸线 | `coastAmplitude`(0.35) | `:165` 的 `seaLevel = baseSeaLevel + coastNoise*0.35`：它调的是**海平面这条线本身**有多曲折。★ 特别注意：它作用的对象是 `seaLevel`，**不是**用来判断"某个高度算哪种地形" —— 后者是 `classify()` 干的事，那个函数本任务一个字没搬 |
| 幂次整形 | `gamma`(0.92) | `:161` 的 `Math.pow(height, 0.92)`：**整体**单调整形，只改海拔值的分布，不改任何分界 |

**没有一组是"某个高度算哪种地形"**。而且本类型里：

- **一个 `String` 组件都没有**（地形名进不来）；
- 没有任何字段的语义是"上界/下界/阈值"（名字里没有 `Above`/`Below`/`Threshold`/`Max`/`Min` 这一族）；
- 本类型的守卫只守频率（有限正数），**不守**任何 `[0,1]` 区间 —— 因为它的字段本来就**不是** [0,1] 的规范量
  （`warpAmplitude=10` 就是反例）。

### 4.3 `noTerrainHeightThresholds` 怎么写才不是装饰

简报给的写法（"断言不存在 `double`/`float` 字段与 `TerrainCatalog.KEYS` 里任一 key 同处一个类型"）
**只覆盖了一半**，我实现成**两条互不替代的判据**：

1. **形态判据**（不看名字）：树里任何类型都不得**同时**持有浮点组件与 `String` 组件 ——
   即"没有任何类型同时知道**一个高度数**与**一个地形名**"。
2. **名字判据**：任何组件名都不得等于 `TerrainCatalog.KEYS` 里的字面量（抓"阈值直接叫 `plains`"这种：
   它是浮点字段、没有 String，第 1 条抓不到）。

★ **为什么不能只写第 2 条**：那会是个装饰 —— 简报指定的变异体给的字段名是 `mountainAbove` /
`terrainKey`，**两个都不是** `KEYS` 里的字面量，只写名字判据一条都拦不住。
反过来**只写第 1 条也不够**：`double mountains`（名字就是 key、没有 String）从形态判据下溜过去。
**两条都实测过**：m8v-4 红在第 1 条，m8v-7 红在第 2 条（见 §七）。

★ **`noTerrainHeightThresholds` 在本任务里没有变红**（红的是变异体，不是源码）。
⇒ 不触发简报那句"若它红了，交回控制器裁定，不要自己放行"。全部 18 条用例在**源码**上是绿的。

### 4.4 我没有把判断权当作"已经判好了"

我**没有**只看字段名就下结论：上面 §4.2 的每一组都是回到 `ContourQueryEngine.java` 的**具体行**
（`:139-140` `:147` `:153` `:159` `:161` `:165`）读出来的用法，逐条确认它参与的是 `height` 的**构造**。
`classify()`（`:229-258`）是唯一的"分类"现场，那批数字**一个都没进 spec**。

---

## 五、我改动的 Task 1–7 文件：**只有 R-48-p 要求的那一处**

`GameMap.java`/`MapChangeSet.java`/`RoundTripComponentsTest.java` 等**一字未动**（`git diff --stat` 可证）。
唯一的例外是派单裁定的 R-48-p：

`simos-map/src/test/java/io/mosire/simos/map/GameMapTest.java:187` 原为

```java
assertThat(GenerationSpec.defaults(7L)).isEqualTo(new GenerationSpec(7L));
```

9 组件后**编译不过**。按裁定：**删掉该行**（**不允许**用 1 参数便利构造器糊过去 —— 那会变成第二条
"静默填 8 个默认值"的路径，与本任务的全部意义相反），保留 186/188 两行，并把它的**真意**
移到新 `GenerationSpecTest#defaultsCarryOnlyTheSeed` 以**更强**的形态钉住：
`defaults(7L)` 与 `defaults(8L)` **逐组件**相等（`seed` 除外），并重写了方法上方的 Javadoc
（原文说"Task 8 才扩参数面"）。

改写后的方法：

```java
@Test
void generationSpecDefaultsCarryTheSeed() {
  assertThat(GenerationSpec.defaults(7L).seed()).isEqualTo(7L);
  assertThat(GenerationSpec.defaults(7L)).isNotEqualTo(GenerationSpec.defaults(8L));
}
```

**"只让 seed 变，其余是规范默认值"这句话现在由 `GenerationSpecTest#defaultsCarryOnlyTheSeed` 以逐组件形态
（看得见全部 9 个组件）钉住** —— 比原来那句 `isEqualTo(new GenerationSpec(7L))` 强：那句在 9 组件世界里
连表达它的能力都没有了。

---

## 六、`MapChangeSet.java` 的 Inspect 结论：**无需改动**

简报把它列进 Files 是"要你去核实前提"，不是"必须先改"（R-48-e 第 4 条），并明令
"不要为了凑一条 diff 去动它"。核实结果：

- `MapChangeSet.java:90` 是 **`base.spec());`** —— `apply` 重建 `GameMap` 的最后一个实参，
  **没有任何 null 兜底**（没有 `cs.spec() != null ? … : base.spec()` 这种形态）。
- 更进一步：该类的 Javadoc **早就把 R-48-e 写在 `:70-72` 了** ——
  *"★ **不得对 `spec` 写任何 null 兜底**（如 `cs.spec() != null ? … : base.spec()`）：`GameMap.spec`
  从 Task 5 起就非 null，兜底是**为不存在的世界写的代码**，且会掩盖真的漏传。"*
- `git diff --stat -- …/MapChangeSet.java` **为空**：本任务对它零改动。

⇒ **结论：无需改动。** `GenerationSpecTest#specIsNeverNullAfterTask8` 仍然加了，但它的身份是
**守卫**（钉住"spec 从不 null"这条不变量），**不是"收紧动作"的证明** —— 本任务无 null 可收紧（前提已被 R-48-e 推翻）。

---

## 七、★ 护栏自证（G13）：12 轮变异实验室

### 7.1 装置

- 位置：`task-8-evidence/run.sh` + `task-8-evidence/mutate.py`（形制继承 Task 4~7，**替本任务的文件清单**重写）。
- **工作树一字不动**：全部变异在 `/tmp/m8lab/repo` 的 rsync 副本上做
  （`--exclude target/.git/.serena/.superpowers`）。工作树状态由每轮结尾的 `git status --short` 留痕。
- 每轮的顺序：**重建副本** → `md5sum -c` 清单（99 个 `.java`）+ **文件数不得多不得少** →
  **改前跑一遍**（护栏必须绿）→ 变异 → **逐文件 md5 自证（原件 vs 变异体，打印实际值）** →
  断言**除声明的文件外无文件被改** → **改后跑一遍** → 断言 `COMPILATION ERROR count = 0` →
  断言 `simos-map 跑过的测试类数 ≠ 0`（红的必须是断言，不是构建挂在用例之前）。
- 变异体**写在目标类名的文件里**；每轮**显式声明**文件集合（有的轮必须跨文件共适应才编得过）。
- 证据形制：`task-8-evidence/rounds/m8v-<N>.kept`（轮次 / 目标 / 逐文件 md5 原件=… 变异体=… / 自证行 /
  `COMPILATION ERROR count` / 红用例清单 / 原始 surefire 输出）。

★ **一个必须留痕的坑**：`m8v-5` 删三个 `requireNonNull` 时**必须连 `import java.util.Objects;` 一起删** ——
checkstyle 的 `UnusedImports` 挂在 `validate`（**先于** surefire），留着会让"红"变成构建失败，
那就不是断言红了（装置形态 ①）。

★ 另一条实测（对下一轮有用）：**spotless 绑在 `verify`，checkstyle 绑在 `validate`** ⇒
`mvn test` 会跑 checkstyle、**不跑** spotless。故变异体只要 import 干净就编得过，无需 google-java-format 干净。

### 7.2 结果总表

**全部 12 轮的 `Errors: 0`** —— 没有一条红是"测试抛异常"，全是断言 Failure（或编译期，见 m8v-11）。
"改前"每一轮都是 `Tests run: 175, Failures: 0` + `COMPILATION ERROR count = 0`（12/12 轮逐轮打印）。

| 轮 | 变异（对**源码**做了什么） | 目标文件 | 期望 | **实得：红在哪条断言** | **哪一层** |
|---|---|---|---|---|---|
| **m8v-1** | `mainRidges` 的构造期校验**换成** GSimulator 的 `Math.max(1, Math.min(mainRidges, 2))` | `GenerationSpec.java` | 红 | `mainRidgesFiveThrows:70` —— *Expecting code to raise a throwable.* | **测试期 · Failure**（断言真响了） |
| **m8v-2** | **删掉**负差值校验的**调用点**（`fragmentParams.requireNonNegativeRemaining(fragments);`） | `GenerationSpec.java` | 红 | `negativeFragmentDifferenceThrows:97` —— 同上消息 | **测试期 · Failure** |
| **m8v-3** | **加回 `worldId` 组件** + **把全部构造点改到编得过**（生产 1 处 + 测试夹具 2 处） | `GenerationSpec.java` + `GenerationSpecTest.java` | 红 | `noWorldIdNoCoastRoughness:197` —— AssertJ 打出 **69 个**组件名的全表（实测 `awk` 计数），`not to contain ["worldId","coastRoughness"] but found ["worldId"]` | **测试期 · Failure** |
| **m8v-4** | 往 `NoiseBands` 加 `double mountainAbove` + `String terrainKey`（简报指定的那一对） | `NoiseBands.java` + `GenerationSpec.java` + `GenerationSpecTest.java` | 红 | `noTerrainHeightThresholds:240` —— **[NoiseBands 同时持有浮点字段与字符串字段…] Expecting value to be false but was true** | **测试期 · Failure**（红在**形态**判据） |
| m8v-5 | 三个 `requireNonNull` 全删（连 import 一起） | `GenerationSpec.java` | 红 | **3 条**：`nullBandsThrows:144`、`nullRidgeParamsThrows:153`（*Expecting code to raise a throwable.*）、`nullFragmentParamsThrows:164`（★ 消息**不匹配**：`Expecting message to be: "fragmentParams" but was: "Cannot invoke "…requireNonNegativeRemaining(int)" because "fragmentParams" is null"`） | **测试期 · Failure ×3** |
| m8v-6 | 掏空频率守卫体（`requirePositiveFrequency` 恒返回入参） | `NoiseBands.java` | 红 | `noiseFrequencyMustBePositiveAndFinite:174` —— *MultipleFailuresError: Multiple Failures (8 failures)*（6 个频率取 0 + NaN + +Inf **逐个**报） | **测试期 · Failure** |
| m8v-7 | 往 `NoiseBands` 加 `double mountains`（名字**就是** `TerrainCatalog.KEYS` 里的 key，且**没有** String 组件） | `NoiseBands.java` + `GenerationSpec.java` + `GenerationSpecTest.java` | 红 | `noTerrainHeightThresholds:244` —— **[组件名不得等于词表 key…] Expecting … not to contain … but found ["mountains"]** | **测试期 · Failure**（红在**名字**判据） |
| m8v-8 | `contourCacheMax` 的校验换成 `Math.max(1, …)` 静默夹取 | `GenerationSpec.java` | 红 | `contourCacheMaxZeroThrows:132` | **测试期 · Failure** |
| m8v-9 | `mapRadius` 的校验换成 `Math.max(1, …)` 静默夹取 | `GenerationSpec.java` | 红 | `mapRadiusZeroThrows:111` | **测试期 · Failure** |
| m8v-10 | `baseSeaLevel` 的 `!(a>=0 && a<=1)` 换成**自然但错**的 `(a<0 \|\| a>1)` | `GenerationSpec.java` | 红 | `baseSeaLevelRangeChecked:122`（**只有 NaN 那一格**红；`-0.1` 与 `1.1` 照旧被挡） | **测试期 · Failure** |
| m8v-11 | **对照轮**：加回 `worldId` 组件，**构造点一个不动** | `GenerationSpec.java` | （编译期） | — | ★ **编译期**（见 §7.3） |
| m8v-12 | 掏空负差值守卫**体**（m8v-2 删的是调用点，本轮删守卫本身） | `FragmentParams.java` | 红 | `negativeFragmentDifferenceThrows:97` | **测试期 · Failure** |

### 7.3 ★ 派单点名的那三分法：加回 `worldId` 组件的红在哪一层

派单要求**明确说出是哪一层，不许含糊**。答案是：**主形态落在「测试期 · Failure」（第 3 种）**，
并且我另外跑了一个对照轮把第 1 种也钉出来了。

- **m8v-3（主形态）＝ 测试期 · Failure。** 做法与 Task 7 实测过的同形：**把全部构造点改到编得过**
  （`new GenerationSpec(` 全仓只有 3 处：生产 `defaults()` 1 处 + 测试夹具 `scalars()`/`subRecords()`
  各 1 处 —— 这正是把测试构造点集中起来的目的）。`COMPILATION ERROR count = 0`，
  `simos-map 跑过的测试类数 = 18`，17 条绿、**恰好 1 条红**：`noWorldIdNoCoastRoughness:197`，
  断言消息是 **AssertJ 的 `not to contain … but found ["worldId"]`** —— 断言真的响了，
  且**只**响这一条（`defaultsCarryOnlyTheSeed` 因为 `worldId` 在 `defaults()` 里取常数而照旧绿）。
- **m8v-11（对照轮）＝ 编译期。** **只加组件、构造点一个不动**：`COMPILATION ERROR count = 1`，
  本轮**当场作废**（按简报"编译错误 ⇒ void"）。javac 的话是：

  ```
  [ERROR] GenerationSpec.java:[101,12] constructor GenerationSpec in record … cannot be applied to given types;
    required: long,long,int,double,int,int,…NoiseBands,…RidgeParams,…FragmentParams,int
    found:    long,int,double,int,int,…NoiseBands,…RidgeParams,…FragmentParams,int
    reason: actual and formal argument lists differ in length
  ```

  ★ 值得记的一点：它先红在**生产侧**（`GenerationSpec.java:101` 就是 `defaults()` 里那个构造点，
  在 `target/classes` 那一遍编译里），**测试文件压根还没轮到编译**。

- **第 2 种（测试期 · Error）本轮没有出现**：`Errors: 0` 贯穿 12 轮。而 m8v-5 的
  `nullFragmentParamsThrows` 给出了同一族现象的一个近亲（异常被抛了、但**消息**对不上 ⇒ Failure）——
  那正是 CLAUDE.md 形态 2 说的"`requireNonNull(x,"x")` 的消息恰是字段名，删掉守卫后 JDK 的热心 NPE
  消息**同样含那个字段名**，只有精确匹配才有判别力"。这里实测到的正是它：
  变异后抛的是 `NullPointerException: Cannot invoke "…requireNonNegativeRemaining(int)" because
  "fragmentParams" is null` —— 含 `fragmentParams`，但**不是** `fragmentParams`。

**⇒ 结论**：作为简报 Step 4 的那条变异，**它是测试期能被抓住的**（m8v-3），
不是"只能靠 javac"的那种；"只能靠 javac"的形态（m8v-11）是**我不更新任何构造点**时的形态。

### 7.4 "没红也要问为什么没红"

- **m8v-1 没红掉 `mainRidgesTwoIsAccepted`** —— 问过了：夹取区间恰是 `[1,2]`，边界内两值原样通过。
  这正是**故意**的：它让"本轮的 1 条红"干净地归给上界那条断言。
- **m8v-3 没红掉 `defaultsCarryOnlyTheSeed`** —— 问过了：变异把 `defaults()` 里的 `worldId` 取**常数** 0，
  两个种子下逐组件仍全等（`seed` 除外）。⇒ 不是漏网，是 `worldId` 的默认值确实不随种子变。
- **m8v-4 没红掉名字判据、m8v-7 没红掉形态判据** —— 见 §4.3，两条判据各自独立，这两轮互为对照。
- **每轮都断言了 `simos-map 跑过的测试类数 ≠ 0` 与 `COMPILATION ERROR count = 0`**：
  "没红"不可能是"压根没跑到"或"挂在用例之前"。

---

## 八、门禁与测试计数

`./mvnw clean verify` —— **BUILD SUCCESS**（日志全文落盘：`task-8-evidence/gate-clean-verify.txt`）。

| 模块 | Tests run | Failures | Errors | Skipped | SpotBugs |
|---|---|---|---|---|---|
| UtilSimos | 156 | 0 | 0 | 0 | `BugInstance size is 0` / `Error size is 0` |
| MapSimos | **175** | 0 | 0 | 0 | `BugInstance size is 0` / `Error size is 0` |
| SocialSimos | 0（无用例） | 0 | 0 | 0 | `BugInstance size is 0` / `Error size is 0` |
| UnitSimos | 0（无用例） | 0 | 0 | 0 | `BugInstance size is 0` / `Error size is 0` |
| CoreSimos | 15 | 0 | 0 | 0 | `BugInstance size is 0` / `Error size is 0` |
| **合计** | **346** | **0** | **0** | **0** | 5 个模块全部 0 bug |

- 本任务新增：`GenerationSpecTest` **18 条**（MapSimos 从 157 → 175）。
- `GameMapTest` 16 条（R-48-p 删了 1 条断言、方法保留）。
- ★ **Task 7 的 `RoundTripComponentsTest` 5 条全绿**：它反射的是 `GameMap` 的 8 个组件，
  而本任务只动 `GenerationSpec` 的**内部**、不碰 `GameMap` ⇒ 按派单预期保持绿。**没有改动它。**
- `MapChangeSetTest` 20 条全绿。
- Spotless：`clean verify` 内 `spotless:check` 通过（写完全部源码后跑过一次 `spotless:apply`，
  中文 Javadoc 的折行由它决定）。
- Checkstyle：`validate` 期通过（无 star import / 无冗余或未用 import / 一行一句）。

---

## 九、遗留与关切

### 9.1 遗留（有明确归属，不是本任务欠账）

1. **`classify()`（`ContourQueryEngine.java:229-258`）与它的气候/纹理噪声频率没进参数面** ——
   归属 **Task 9/10**。本任务的边界是"造海拔的形状参数"；"把海拔切成地形"是 Task 9 的事，
   而它的判据必须来自 `TerrainCatalog`（U1）。
2. **`warpFreq`/`warpAmplitude` 的载体**：它们目前住在 `NoiseBands`（域扭曲是"噪声怎么被采样"，
   与五个频率同族）。若 Task 10 认为域扭曲属于"坐标变换"而非"噪声场"，搬家成本是一行。
3. **去相关相位平移（`:150-152` `:164`）**：有意留在 `ContourQueryEngine` 侧。
   Task 10 实现 `generate(GenerationSpec)` 时它们应当**仍是常量**，不要顺手提成字段（§3.1 已给理由）。
4. **`RidgeParams` 的 33 个组件没有守卫**（有意，§3.2）。若将来发现某个跨度取负会**静默**产生
   坏图（而不是"另一种分布"），那一条要单独加守卫 + 单独一轮变异自证。
5. **`FragmentParams.secondaryCountDivisor = 0` 不设守卫**：靠 `ArithmeticException`。
   若 Task 10 把这条除法改成浮点或改成 `Math.max` 包裹，**那条"响声"就没了** —— 那时必须补守卫。

### 9.2 关切

1. ★ **`defaults()` 里 `baseSeaLevel` 用的是字面量 `0.2025`，而 GSimulator 的算式值是
   `0.20249999999999999`（差 1 ulp）**。我选了字面量并在注释里记**来历**而不是**等式**（§3.4）。
   如果 Task 10 或别的任务拿 GSimulator 的算式去对拍"逐位相同"，这里会差 1 ulp —— **这是已知且有意**的。
2. **`NoiseBands` 里 16 个字段的名字是我起的**（GSimulator 那些是匿名字面量，没有名字可继承）。
   名字与 `ContourQueryEngine` 行号的对应关系逐条写在 `@param` 里（含 `file:line`），
   Task 10 接线时应以那条对应关系为准。
3. **`GenerationSpec` 的组件顺序**（`seed, mapRadius, baseSeaLevel, mainRidges, fragments, bands, ridges,
   fragmentParams, contourCacheMax`）沿用简报 Step 2 的签名。**未**沿用 GSimulator 的形参顺序
   （那边是 `worldId` 打头）—— 因为 `worldId` 已按 spec §6.4 删除，而 `coastRoughness` 同样删除。
4. **我不曾为了通过门禁而放松任何规则**：`pom.xml`、`config/checkstyle.xml`、SpotBugs 的
   excludeFilter **一字未动**；没有新增 `@SuppressWarnings`；Task 1–7 的文件除 R-48-p 那一处外**零改动**。

---

## 十、附：证据与脚本索引

| 路径 | 内容 |
|---|---|
| `task-8-evidence/gate-clean-verify.txt` | `./mvnw clean verify` 全量日志（BUILD SUCCESS） |
| `task-8-evidence/run.sh` | 变异实验室装置（12 轮） |
| `task-8-evidence/mutate.py` | 逐轮变异定义 + 自证用的文件声明 |
| `task-8-evidence/rounds/m8v-1..12.kept` | 逐轮证据（含原始 surefire 输出） |
