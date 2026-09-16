# Task 9 报告：`TerrainClassifier` —— 7 项全覆盖，且不许自带阈值

**状态：DONE** ｜ 分支 `feat/m2-map-simos` ｜ 交付：`TerrainClassifier`（74 行含 Javadoc + 232 行用例，9 条全绿）

---

## 一、实现了什么

`simos-map/src/main/java/io/mosire/simos/map/generate/TerrainClassifier.java`（新文件，public final，私有构造，全静态）。

**高度判定 = 纯查表，本类零高度字面量**（`grep -nE "[0-9]"` 实测：代码里唯一的数字是湿度常量 `0.35`，其余数字全部落在注释/Javadoc 里）：

```java
String candidate = TerrainCatalog.KEYS.getLast();   // 起始值 = 最高带（升序末项）
for (TerrainType t : TerrainCatalog.defaults().values()) {
  if (height < t.maxHeight()) { candidate = t.key(); break; }   // 第一条命中的带
}
```

**R-9a 的落带约定照办**：升序找第一条满足 `height < maxHeight` 的带；一条都没中（`height >= 1.0`、`NaN`）⇒ 起始值原样保留，即**最高带**。零夹取算术、零终点字面量。域外输入结构性落带：负值 → 最低带（首条带 `height < 0.30` 成立）、非有限值同结构、一律不抛。"总函数"来自这个结构本身，不是某条兜底分支。这条约定写进了类 Javadoc。

**沙漠气候门**：`if (DESERT.equals(candidate) && humidity >= DESERT_MAX_HUMIDITY) return "plains";`
- 常量 `public static final double DESERT_MAX_HUMIDITY = 0.35`，门是 `>=` 阈值即退。
- ★ **0.35 是"本任务新定，非来自 GSimulator"**：原项目没有湿度维度，侦察 D 未实测过任何湿度阈值；Javadoc 里也逐字写了这句。选值依据只有"沙漠只长在 [0,1] 湿度的下三分之一"，同时给中性采样 0.5 留稳定余量。
- 退的是 **写死的常量 `"plains"`**（`private static final DESERT_FALLBACK`），不是"相邻低带"算法 —— 按控制器裁定办。
- ★ **`temperature` 收下但不参与判定**：U1 的 7 项里没有靠温度区分的项，参数为 **M3+（植被带/季节细分）预留**的签名占位。Javadoc（类注释 + `@param`）与本报均写明，避免"看着像忘了用"。

## 二、用例（9 条，brief 的 8 条逐条都在 + 1 条夹具自证）

| 用例 | 关键口径 |
|---|---|
| `everyCatalogKeyIsProducible` | 遍历 `TerrainCatalog.KEYS`，逐项取**自家带中点** + 该带自己的湿度门，断言 7 项**每项都产得出**（L9 的守卫） |
| `classifierFollowsCatalogBands` | ★ 判别力最强：采样点 = `minHeight()` / 带中点 / `Math.nextDown(maxHeight())`，**全部由被检的带算出、不抄一个高度字面量**；desert 带取期望 `plains`（门） |
| `desertBandFallsBackToPlainsWhenHumid` | 同一高度：低湿度 → `desert`、中性湿度 → `plains`；另钉门的**临界点**（`nextDown(0.35)` → desert、`0.35` → plains，两种口径的分叉处） |
| `classifyNeverReturnsUnknownKey` | [0,1]³ 网格 11³ = 1331 点，返回值恒在 `KEYS` 内 |
| `oceanIsLowestBand` | 最低带 → `ocean`，湿度/温度各取 {0, 0.5, 1} 全组合（9 格），钉"与气候无关" |
| `plateauMountainsIsHighestBand` | 最高带三点（含 `nextDown(1.0)`）→ `plateau_mountains` |
| `classifyIsDeterministic` | 6 组输入两次调用同值 |
| `classifyIsTotal` | ① `classify(1.0,…)` 不抛且 = **最高带 key**（R-9a 显式要求）② 域外负值 `-0.5` 不抛且 = **最低带 key**（同上）③ ±∞ / NaN / ±1e9 不抛且返回值在 KEYS 内 |
| `humidityFixturesStraddleTheDesertThreshold` | ★ 夹具自证：低湿度 0.10 < 阈值 0.35 ≤ 中性 0.5。阈值被调走时**先红在这里并指出哪一半失配**，不让 desert 项给出自相矛盾的失败 |

夹具常量：`LOW_HUMIDITY = 0.10`、`NEUTRAL_HUMIDITY = 0.5`、`NEUTRAL_TEMPERATURE = 0.5`（三参数都非退化；温度不承载期望）。

## 三、变异证据表（6 轮；装置 `run.sh` + `mutate.py`，证据在 `task-9-evidence/`）

每轮形态（同 Task 4~8）：干净世界（rsync 全新副本 + md5 清单 + 文件数不得多不得少）→ 改前全绿 → 变异（写进**目标类名**的文件）→ md5 自证"字节不同 + 只改了声明的文件" → 改后 → `COMPILATION ERROR count = 0` → simos-map 用例真跑过（19 类）→ 红点（测试名+行号）。

**共同自证头**：6 轮的**改前**均为 `Tests run: 156 (simos-util) / 184 (simos-map), Failures: 0` + `BUILD SUCCESS`；6 轮的**改后** `COMPILATION ERROR count` 全为 **0**（无一轮撞编译期，无需作废）；原件 `TerrainClassifier.java` md5 = `39211b644299f051d751a4c70fa39240`（每轮相同，证明各轮起点的原件是同一份）。

| 轮 | 变异（md5 原件→变异体） | 期望 | 实测红点（测试名:行号，断言消息） |
|---|---|---|---|
| m9v-1 | 沙漠恒退 plains（`if (DESERT.equals(candidate))`）：desert 产不出来 = **退回 6 项**。CLS `39211b64…`→`7e1d10cb…` | 红（L9 守卫） | `everyCatalogKeyIsProducible:49` expected "desert" but was "plains"；**附带** `desertBandFallsBackToPlainsWhenHumid:93`（沙漠的产出路径没了，"干燥→沙漠"那半自然红——同一因果关系） |
| **m9v-2** | ★ **写死阈值 + 挪词表中间边界**：CLS 改成私有 `double[] hardcodedMax = {0.30, 0.45, …, 1.00}`（`39211b64…`→`a4a450fd…`）**且** `TerrainCatalog` 的共享边界 0.30→0.32（`091e92cc…`→`36655893…`，两处成对改，0.0/1.0 两端未动） | 红，**且只应落在** `classifierFollowsCatalogBands` | ★ **只有一条红**：`classifierFollowsCatalogBands:73`，`高度 0.31999999999999995 落在 ocean 的带 [0.0, 0.32) 内` expected "ocean" but was "plains"。**同轮 `TerrainCatalogTest` 13 条全绿**（端点钉子/连续性/划分三条都没被惊动，完全符合 R-9c 的预期）；无任何其他用例红 |
| m9v-3 | 去掉沙漠低湿度门（落 desert 带一律 desert）。`39211b64…`→`9ad20291…` | 红（门的守卫） | `desertBandFallsBackToPlainsWhenHumid:96` expected "plains" but was "desert"；**附带** `classifierFollowsCatalogBands:73`（该用例的 desert 采样点同样按"门"取期望，red 是因果后果） |
| m9v-4 | **R-9d 的等价形态**："全不中 ⇒ 退末带" 改成 "全不中 ⇒ 返回常量 plains"（只改 `candidate` 的初始值，一字之差）。`39211b64…`→`64c4ff38…` | 红 | ★ **只有一条红**：`classifyIsTotal:215` expected "plateau_mountains" but was "plains"。**`classifyNeverReturnsUnknownKey` 不红** —— 见下"诚实说明" |
| m9v-5 | 让 `classify` 对一段输入抛异常（`height < 0.0` ⇒ IAE）。`39211b64…`→`6b7a6dee…` | 红（总函数） | **只有一条红**：`classifyIsTotal:216` `java.lang.IllegalArgumentException: 高度不得为负: -0.5` |
| m9v-6 | 把 `ocean` 带也加上气候门（同一条门）。`39211b64…`→`3016c07f…` | 红（"与湿度温度无关"半） | `oceanIsLowestBand:159` expected "ocean" but was "plains"；**附带 3 条**（ocean 的产出路径同样被门掐断，是同一因果）：`everyCatalogKeyIsProducible:49`、`classifierFollowsCatalogBands:73`、`classifyIsTotal:218`（负值→最低带→被门改判） |

**红点的行号**都是**断言所在行**（`.isEqualTo(...)` / `classify(...)` 调用行），取自 surefire 的 `Test.method:line` 摘要；每轮 `.kept` 里另有 surefire 的 `expected / but was` 原文。

### 诚实说明（两处与 brief 字面预期的出入）

1. **R-9d（第 4 行）**：控制器裁定的等价变异**实测红在 `classifyIsTotal:215`**，而 brief 表里写的是"`classifyNeverReturnsUnknownKey` 的不兜底半"。原因是**结构性的**：常量为 `"plains"`，而 `plains ∈ KEYS`，所以"返回值恒在 KEYS 内"这条断言**对它天然无判别力** —— 判别力只能来自"1.0 必须落到**最高带**"这条 R-9a 约定（`classifyIsTotal`）。R-9a 把 `classify(1.0, …)` 显式钉进 `classifyIsTotal` 正是为了这一刻。原始字面写法（加一条 `default -> "plains"` 分支）在 R-9a 的查表结构里**不可达**，故按裁定改用等价形态。
2. **m9v-1 / m9v-3 / m9v-6** 的附带红点：都属"同一处语义被改 ⇒ 多条断言同时看见"的因果后果（沙漠/ocean 的产出路径、门、带定位是同一事实的几个侧面），不是装置里的杂音。逐条已在表中标明。
   （另：`mvn <args> -rf :simos-map` 是 Maven 的 reactor 续跑提示，首轮曾被 `[ERROR]   ` 前缀的 grep 误收进"红点"清单；已修 `red_points()` 过滤并**重跑全部 6 轮**，现 `.kept` 里的红点清单无杂音。）

## 四、门禁

`./mvnw clean verify`（工作树，未变异）→ **BUILD SUCCESS**，日志存 `task-9-evidence/gate-clean-verify.txt`。
逐模块实测：checkstyle ✓ / spotless ✓ / spotbugs（effort=More, threshold=Low）✓ / surefire：simos-util 156 + simos-map 184 + simos-core 15，Failures 0，Errors 0。
迭代期用 `-pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TerrainClassifierTest` ⇒ 9/9 绿、输出干净。

## 五、Files changed

- **新增** `simos-map/src/main/java/io/mosire/simos/map/generate/TerrainClassifier.java`
- **新增** `simos-map/src/test/java/io/mosire/simos/map/generate/TerrainClassifierTest.java`
- 未改任何既有文件（`TerrainCatalog`/`TerrainType` 只读、零改动）。
- 证据（gitignore 下，用 `git add -f` 单独提交）：`task-9-evidence/{run.sh, mutate.py, rounds/m9v-1..6.kept, gate-clean-verify.txt}` + 本报。

## 六、Self-review 发现（已当场处理）

1. `classifyIsDeterministic` 原来带一个域外采样 `-0.25` —— 它会让"负值抛异常"那条变异**多红一条**（属噪声，掩盖"谁在把守总函数"）。改为在域内 + 两端采样（0.0 / 1.0），域外行为由 `classifyIsTotal` 独占。**发现即改**，不是 park。
2. 类 Javadoc 原写 `{@link MapGenerator}` —— 那个类属于 Task 10、**当前不存在**，链接会指向空气。改为 `{@code MapGenerator}`。
3. 装置首轮把 Maven 的 reactor 提示误收进红点清单（见上）—— 修过滤并**重跑全轮**，不留"事后补记"。
4. 复核 brief 8 条用例逐条在位；`classify` 签名与 brief 逐字一致；提交信息逐字采用。

## 七、Concerns / 留给下游

1. **每次 `classify` 调用都会重建词表**（`TerrainCatalog.defaults()` 每次 new 7 个 `TerrainType` + 一个 `LinkedHashMap`；该函数**有意不做静态缓存**，理由见其 Javadoc 的 `ExceptionInInitializerError`）。Task 10 的 `MapGenerator` 逐格调用时是"N 格 × 7 次分配"。20k 格量级下是毫秒级、不影响正确性，**故本任务不加缓存（YAGNI）**；若 Task 10 实测有压力，缓存应加在**调用方或词表侧**并自带其护栏，别在分类器里再开一份状态。
2. **跨进程的落带/词表序稳定性**仍无人把守（`TerrainCatalogTest` 的既有残留风险，非本任务引入）。
3. 门只长在 desert **一处**这一点，目前由 `oceanIsLowestBand`（最低带）+ 全网格 + 7 项可产出共同覆盖；若将来加第二道气候门（如 tundra 的温度门），`humidityFixturesStraddleTheDesertThreshold` 这类"夹具↔常量夹逼"自证要按新门复制一份。
