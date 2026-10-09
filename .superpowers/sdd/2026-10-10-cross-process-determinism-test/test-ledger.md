# 跨进程确定性护栏（`2026-10-10-cross-process-determinism-test`）测试账本

> 角色：测试代理（本批**只写测试**）。生产代码**一个字符未改**（三次临时变异全部 `git checkout --` 还原，逐字节 md5 相同，见 §3）。
> 落点：`simos-app/src/test/java/io/mosire/simos/app/determinism/`（新）+ `simos-economy/.../codec/EconomyCodecTest.java`（仅注释）。
> 未 `git commit`。未 claim 任务板（`team_task_get task-32` 返回 `agent … is not a member of an active Agent Team`，按要求不卡住、照任务书执行）。

---

## 1. 为什么需要这条护栏（问题现场，不是推测）

`b4bfe988`（economy 的 `Map.of`(≥2 键) per-JVM 盐）与 `8337eac3`（map 的 `Set.copyOf` per-JVM 盐）修掉的两个真缺陷，
**当时所有门禁全绿**——因为既有那条"字节级稳定"护栏
（`simos-economy/.../codec/EconomyCodecTest.java#encodingIsByteLevelStableForEconomyData`）
只在**同一个 JVM 内**做 `encode → decode → encode`：

```java
String snapshotOnce  = CODEC.encodeSnapshot(snapshotOf(data, …));
String snapshotTwice = CODEC.encodeSnapshot(CODEC.decodeSnapshot(snapshotOnce));
assertThat(snapshotTwice).isEqualTo(snapshotOnce);   // ← 同进程两次编码 ⇒ 同一个盐 ⇒ 恒真
```

`Map.of`(≥2 键) = `ImmutableCollections.MapN`、`Set.copyOf` = `SetN`，槽位 = `floorMod(键.hashCode() ^ SALT, 表长)`，
`SALT` 取自 **JVM 启动时的 `nanoTime`** ⇒ **抖动只在跨进程存在**，同进程两次编码用**同一个盐**，这条断言对它是**盲的**。
它旧注却写着"能抓住键序漂移"——**判别力是假的**。本批补上真网。

## 2. 装置：怎么造的，以及为什么这样造

### 2.1 两个文件

| 文件 | 职责 |
|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/determinism/CrossProcessDeterminismChild.java`（187 行） | **子进程主类**（test scope，故意不是 JUnit 测试类 ⇒ surefire 不会把它当用例跑）。自己构造创世世界、自己序列化、落盘、打摘要行 |
| `simos-app/src/test/java/io/mosire/simos/app/determinism/CrossProcessDeterminismTest.java`（393 行） | **父进程 JUnit 用例**：起 N=4 个独立 JVM、读产物、比 digest、逐路径定位 |

**落点为何在 `simos-app`**：被判的字节流横跨 8 个模块（economy 快照/变更集 + map 快照 + 整世界 checkpoint 信封），
只有组合根 `simos-app` 的 test scope 同时看得见 `ThreePowersWorld` 与全部 8 个 codec。

### 2.2 关键设计决定（每条都有理由）

1. **真起子进程，不是同进程多次调用**：装置的全部价值所在（§1）。每个子进程自己调
   `ThreePowersWorld.state("three-powers")` **独立构造**同一份数据，再各自序列化。
2. **classpath 走 `surefire.test.class.path`，回退 `java.class.path`**：surefire 默认用
   `surefirebooter.jar` 的 manifest-only classpath，此时 `java.class.path` **只有那个 booter jar**。
   surefire 为此专门注入 `surefire.test.class.path`（实读 `surefire-booter-3.6.0.jar` 的
   `org/apache/maven/surefire/booter/StartupConfiguration#writeSurefireTestClasspathProperty` 确认）。
   ⇒ **不依赖任何外部脚本、不依赖网络、不依赖 cwd**。
3. **每个子进程写自己的 `@TempDir` 子目录**（`jvm-0/…jvm-3/`），父进程只读文件、不共享内存对象。
4. **比 10 份字节流**（不是 1 份）：`economy`（Map.of 盐的主战场）、`economy.seed-changeset`（同一缺陷的第二条出口，
   journal 的 `changeset_json` 同源）、`map`（Set.copyOf 盐的主战场）、`checkpoint`（整世界信封，8 段载荷都在里面）、
   以及 `social/unit/sd/actor/gov/army` 六段（防"修一处、抖另一处"）。
5. **装置自证（硬断言）**：子进程落 `jvm-identity.txt`（pid + nanoTime），父进程断言 N 份**两两不同**
   ⇒ 证明比较的确实是 N 个不同进程的产物，而不是"同一个 JVM 里调了 N 次"（那正是旧护栏恒真的原因）。
6. **同进程对照（硬断言）**：子进程同时打 `SAME_PROCESS economy … once==roundTrip:true`——**它就是旧护栏的形制**。
   父进程断言它**仍然为 true**，把"旧护栏在变异体下照样绿"钉成每次运行都复现的对照，而不是账本里的一句话。
7. **失败诊断是逐路径的**：断言消息给出逐 JVM 的 digest + 盐读数、`#0 vs #i` 的**第一处差异**
   （**键序/键集不同** 与 **值不同** 分列），并给出"忽略键序后是否相等" ⇒ 报告里能直接看出"是键序还是值"。
8. **一次运行查完所有产物再断言**（不是红在第一份就退出）⇒ 一次变异运行就能给出**完整**的抖动面。
9. **`@TempDir(cleanup = ON_SUCCESS)`**：失败轮的证据（各子进程完整日志 + 10 份 JSON）留盘。
10. **N 可调、有下界**：`-Dsimos.xproc.jvms=K`，默认 4；`< 3` 时用例**拒绝运行**（盐只有 2 个样本时判别力不足）。

### 2.3 盐读数：机制的当场证据（每轮绿都会打出来）

子进程落 `salt-probe.txt`，直接量本 JVM 的 `Map.of`/`Set.copyOf` 迭代序。**最终绿轮**
（`02:21` 那次 verify，4 个子 JVM）：

| 进程 | `mapOf("meansWeightPerMille","laborWeightPerMille")` | `Set.copyOf(a..e)` |
|---|---|---|
| #0 | `[laborWeightPerMille, meansWeightPerMille]` | `[d, e, a, b, c]` |
| #1 | `[laborWeightPerMille, meansWeightPerMille]` | `[e, d, c, b, a]` |
| #2 | `[meansWeightPerMille, laborWeightPerMille]` | `[b, a, e, d, c]` |
| #3 | `[meansWeightPerMille, laborWeightPerMille]` | `[b, a, e, d, c]` |

⇒ **同一份内容在这 4 个 JVM 上确实有两种迭代序**（那对键恰好就是 `EconomySeeder.orderedTable` 第一个调用点的键）。
换句话说：**绿轮的 `unique=1/4` 不是"因为大家盐一样才相等"，而是"盐确实不同、而修复让字节仍相等"**——
这是让"绿"有意义（而非恒真）的关键读数。

★ 但它**只上报、不硬断言**：读数本身是随机的，硬断言会带来约 1/500 量级的假红。代价如实记在 §5。

## 3. ★ 判别力自证（本批核心）

三条变异，**每次变异前记源码 md5，还原后再比 md5**（本仓口径：**md5 才是"字节变了"的判据**）。

### 变异 1 — `simos-map/.../region/Region.java` 的保序冻结改回 `Set.copyOf`

- 变异前 md5：`b616e58e3d3e2fbc6a53a1c2411793ac`
- **形态 v1（作废）**：整段替换成一行 `hexes = Set.copyOf(hexes);` ⇒ md5 `ac38340829ea0180ca4359e82fdedc4a`。
  跑出来是 **checkstyle 3 条 `UnusedImports`（ArrayList/Comparator/List）⇒ BUILD FAILURE**。
  **这是构建红，不是护栏红，不算数**（照 §三"按变异文件名拷入 ⇒ 红变成编译错误，不算数"的同族纪律），故重做。
- **形态 v2（采用）**：保留 `sorted` 排序计算，只把冻结换成 `hexes = Set.copyOf(sorted);` ⇒ md5 `89e1e648fdb682c7d677a7eb70d46d38`。
- 跑两次，两次都红：
  - 第 1 次：`map` **4/4** 个不同 digest；
  - 第 2 次（改进报告后重跑）：抖动面 `["map", "checkpoint"]`，`map` **3/4**、`checkpoint` **3/4**。
- **红的原因行**（第 2 次）：
  ```
  #0 vs #1：$.map.regions.province-silver.hexes[0].r 值不同 A=-1 B=1；忽略键序后相等=false
  #0 vs #2：$.map.regions.province-silver.hexes[0].q 值不同 A=2 B=1；忽略键序后相等=false
  ```
  ⇒ 正好红在**被保护的那一行所在的结构**上：`Region.hexes` 是 JSON **数组**，`Set.copyOf` 的槽位序直接把
  **数组元素顺序**改了（所以表现为 `hexes[0]` 的值不同，而不是键序不同）。
- 证据：`evidence/mutant-1-map-SetCopyOf.red.txt` / `.xml`
- 还原：`git checkout -- simos-map/src/main/java/io/mosire/simos/map/region/Region.java`
  ⇒ **还原后 md5 `b616e58e3d3e2fbc6a53a1c2411793ac`，与变异前逐字节相同**。

### 变异 2 — `simos-app/.../world/EconomySeeder.java` 的 `orderedTable` 改回 `Map.of`

- 变异前 md5：`3d8a39fca909e14e15ed62bd78f85a73`
- 变异后 md5：`d7d79dcfd3307dbd2ccc4c0d4e9bad5b`
- 变异内容：私有 `orderedTable(k1,v1,k2,v2)` 的方法体换成 `return Map.of(firstKey, firstValue, secondKey, secondValue);`
  ⇒ **7 处调用点同时回退**成 `b4bfe988` 之前的形状（与旧源码同形，不是自造变异）。
- **连跑 4 次，4 次全红**（击杀率 4/4）；`economy` 唯一性依次 `3/4`、`4/4`、`4/4`、`4/4`；
  `economy.seed-changeset` 与 `checkpoint` 同步红；`map` 一直 `1/4`（与本变异无关，说明**不是全局噪声**）。
- **红的原因行**（每次都是同一条路径，纯键序、值逐项相同）：
  ```
  $.data.industries.craft@-3_3.outputPerUnit **键序/键集不同** A=[cloth, tool] B=[tool, cloth]；忽略键序后相等=true
  $.data.industries.craft@-3_3.cycleInputPerUnit.WORKSHOP **键序/键集不同** A=[fiber, tool] B=[tool, fiber]；忽略键序后相等=true
  ```
  ⇒ `忽略键序后相等=true` 正是"**值差异恒 0、只有映射键序变**"的机器判定，与 `b4bfe988` 的定位逐字吻合。
- 证据：`evidence/mutant-2-economy-MapOf.red.txt`
- 还原：`git checkout -- simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`
  ⇒ **还原后 md5 `3d8a39fca909e14e15ed62bd78f85a73`，与变异前逐字节相同**。
- ★★ **额外证明（临时目录外的强证据）**：还原子树后**强制重编**（`rm -rf simos-map/target/classes
  simos-app/target/classes simos-economy/target/classes`）再跑，**10 份产物 digest 与首次绿轮逐字节相同**：
  `economy bd090038…`、`economy.seed-changeset f143e5d7…`、`map 40c2442f…`、`checkpoint 600d2e54…`、
  `social 6f745fa2…`、`unit e5f0a69f…`、`sd 2f7039f1…`、`actor 0edcefed…`、`gov 13ba793f…`、`army 77918c85…`。
  ⇒ 变异是**彻底的**（源码 md5 与行为 digest 都回到原值），不是"看起来还原了"。

### ★ 两跑对照：同一变异在**旧护栏**上是否也红？（**不红**——这就是"旧护栏判别力是假的"的证据）

在**变异 2 的同一棵树上**（一条 Maven 命令同时跑两个类）：

| 护栏 | 结果 | 证据 |
|---|---|---|
| 旧：`EconomyCodecTest#encodingIsByteLevelStableForEconomyData` | **Tests run: 1, Failures: 0 —— 绿** | `evidence/mutant-2-OLD-guardrail.green.txt` |
| 新：`CrossProcessDeterminismTest` | **Failures: 1 —— 红**（economy 3/4） | `evidence/mutant-2-economy-MapOf.red.txt` |

★★ 更强的对照：**同一棵树上、同一份数据**，4 个子 JVM 打出来的同进程往返读数全是
`SAME_PROCESS economy once==twice:true once==roundTrip:true`（4/4）——
即"把旧护栏那条断言**原样搬到真正会抖的创世数据上**，它照样恒真"，而跨进程 digest 已经分裂成 3/4。
⇒ 旧护栏不是"没跑到"，是**天生盲**。

补充事实（用于把两条护栏的覆盖面说清）：旧护栏的夹具 `fullData()` **根本不经过 `EconomySeeder`**（它直接搭
`EconomyData`），所以它对这次缺陷有**两重**失明：① 同进程盐相同；② 夹具里没有会抖的那 3 张两键表。

### 变异 3 — 判"甲/乙"的探针（任务没要求；为"旧护栏到底还有没有判别力"当场量）

甲/乙 的分歧点是"旧护栏是否还有别的判别力"。我不靠推理，直接量：

- 目标：`simos-economy/.../codec/EconomyCodec.java#decodeSnapshot`，临时加"把 `industries` **反转序重建**"
  （= "解码把序丢了"这一类退化的最小复现）。
- 变异前 md5：`17b2738fe670ae534a81a361292c3f87`
- 结果：旧护栏**当场红**（红点＝`assertThat(snapshotTwice).as("快照的字节级往返").isEqualTo(snapshotOnce)`），
  报错是 `"industries":{"farm":…,"workshop":…}` vs `{"workshop":…,"farm":…}`、**逐值全同**。
- 证据：`evidence/mutant-3-OLD-guardrail-probe.red.txt`
- 还原后 md5：`17b2738fe670ae534a81a361292c3f87`（== 变异前，逐字节）。
- ⇒ 结论：**旧护栏的判别力是真的，它的问题只在"声称的范围"**（详见 §4）。

## 4. 旧护栏的处置：选 **乙**（保留断言与名字，把注释收窄到如实范围 + 点明跨进程归新护栏）

`simos-economy/src/test/java/io/mosire/simos/economy/codec/EconomyCodecTest.java`（**只改 Javadoc，断言一行未动**）：

1. 标题从"字节级往返"改成"**同进程**字节级往返"，如实只声称"同进程往返稳定"（= 甲要的"如实"）；
2. 写上**当场量过的**判别力（变异 3 的红点），不是"看起来能抓"；
3. 写上**它抓不到什么**：per-JVM 盐、同进程恒真，并附**实测**（变异 2 下它 1/1 绿、跨进程已 3/4 分裂）；
4. 点明**跨进程由新护栏 `CrossProcessDeterminismTest` 负责**，两条覆盖面不重叠、都要在。

**为什么不选纯甲（只改名/只删注释）**：变异 3 证明它**确实**能抓"解码往返丢序"，把它的注释降级成"同进程往返稳定"
是对的，但**抹掉它的真实能力**是另一种失真；把它删成"只是往返"会让后来人以为它没用而删掉它。
**为什么不是什么都不做**：旧注的"能抓住键序漂移"是**范围写错**（不是断言错），照 §四"机制性描述一律回代码核"
的口径，写错的机制描述必须就地更正。
**任务的两条硬要求都满足**：不是只把注释删掉（是重写并补上实测数据）；没削弱任何断言（三条断言逐字未动）。

## 5. 我没做 / 没验证的（如实）

**没做的**
- **没有 `git commit`**（本批禁止）；**没有碰任何 `src/main/**`、`pom.xml`、`docs/**`、别人的账本**。
  三次临时变异全部还原并逐字节 md5 相同（§3）。
- **没有在任务板上 claim**：`team_task_get task-32` 报 `agent "04ca6da5-…" is not a member of an active Agent Team`
  ⇒ 按任务书"不要卡住"执行，故本批**没有 team task 的 claim/complete 记录**。
- **没有为"新护栏在别的世界/别的时点也成立"做覆盖**：只覆盖 `three-powers` 的**创世态**。
  `small-world` / `corridor-world` / `rich-world`、以及**真跑 N 天后的末态**（旧批的 `advance` 模式）**都不在**本护栏里
  ——旧批是用 `/tmp` 一次性探针量的，那个装置**没有进仓**。
- **没有把 checkpoint 信封的"8 段逐段 digest"做进断言**：只比整份信封；逐段定位靠失败消息里的
  `$.modules.<ns>` 路径（够用，但不是逐段 digest 清单）。
- **门禁跑的是 `verify`，不是 `clean verify`**：两轮 `tools/mvn-lock.sh verify` 全绿（含 Spotless/Checkstyle/SpotBugs/
  Surefire/前端门禁）。**没有**跑 `clean verify`——`clean` 只强制全量重编、不改变结论，而本机一次只能跑一个 Maven。
- **没有把这条护栏接进任何"必须跑"的清单**：它就是 `simos-app` 的一个普通用例，随 surefire 跑；**没有**单独加门禁钩子
  （本仓口径：测试在 surefire 里跑到就算在门禁里，不另设装置）。

**没验证 / 置信度有限的**
- ★ **装置不硬断言"各 JVM 的盐确实不同"**（只上报 `salt-probe.txt`）。理由：读数本身随机，硬断言约 1/500 假红。
  **代价如实记**：若未来某个 JVM 把 `ImmutableCollections.SALT` 固定下来，本护栏会**静默退化成恒真**，
  届时只有"重跑变异体"能发现。**这是一处已知的、未设防的退化路径。**
- **击杀率只对变异 2 测了 4 次（4/4 红）**；变异 1 只测了 2 次（2/2 红）。**样本小**，
  不能声称"任意一次运行 100% 红"；能声称的是"4/4 与 2/2 观测全红"。
- **`checkpoint` 那条红点的"忽略键序后相等"读数是 false，但那不是"值变了"**：checkpoint 把各模块载荷编成
  **字符串**嵌在信封里，我那套 JSON 级按键名排序的规范化**穿不透字符串** ⇒ 读数偏保守。
  逐值是否真的相同，本批**没有**为 checkpoint 单独证（economy/map 两处已由 JSON 级 `忽略键序后相等=true` 证过）。
- **N 只用了默认 4**：`-Dsimos.xproc.jvms=3` 与更大 N 都没实测。
- **classpath 的回退分支没实测**：`surefire.test.class.path` 存在时走它（已实测）；`java.class.path` 那条回退分支
  **没有**在真实场景下跑过（只在 IDE/非 surefire 场景才会用到）。
- **只在本机 JDK 21（`/usr/lib/jvm/java-21-openjdk-amd64`）与 Linux 上跑过**。
- **没有为"子进程超时"路径做变异**（`CHILD_TIMEOUT_SECONDS` 默认 180s，实测每个子 JVM ≈1.6s，余量 100×）。

## 6. 耗时读数（实测，非估计）

| 项 | 读数 |
|---|---|
| 新护栏自身（surefire）| **6.528 s**（最终 verify 轮）；6.55–7.25 s 各轮；用例自打印"4 个 JVM 串行 6599 ms" |
| 单个子 JVM | 1615 / 1630 / 1651 / 1695 ms（≈1.6 s；一次创世构造 + 10 份序列化） |
| 单跑（`-pl simos-app -am -Dtest=CrossProcessDeterminismTest`）| 墙钟 ≈26 s（含 15 模块增量编译 + 前端门禁 412 条） |
| 全仓 `test` | **3:34** |
| 全仓 `verify`（关账门禁）| **4:34**（02:17–02:22）与 **4:22**（02:22–02:27，最终字节），两次 BUILD SUCCESS |

## 7. 最终门禁真数（`tools/mvn-lock.sh verify`，**最终字节**，02:22–02:27）

- **15 模块 / 394 测试类 / 3362 条 / 0 失败 / 0 错误 / 5 跳过**（5 条为既有 `RealLlm*` 环境门控）；
  surefire 报告 mtime **02:23:00 – 02:27:01**（本轮）。BUILD SUCCESS / 4:22。
- 基线（`8337eac3`）为 **393 类 / 3361 条** ⇒ 本批 **+1 类 / +1 条**，正是新护栏。
- 新护栏报告：`simos-app/target/surefire-reports/io.mosire.simos.app.determinism.CrossProcessDeterminismTest.txt`，
  mtime **2026-10-10 02:26:49**，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，`Time elapsed: 6.528 s`。
- ★ **正向绿的稳定性**：新护栏在本批共跑 **4 次全绿**（02:08 单跑 6.827 s、02:16 全仓 `test`、02:21 全仓 `verify`、
  02:26 全仓 `verify`），**0 次假红**——4 个子 JVM 的启动时序不影响结论。
  （02:21 与 02:26 两轮 `verify` 之间的唯一差异是 `CrossProcessDeterminismTest` 里一句**注释**改了两个字，
  故重跑了整轮 verify，使**最终字节与最终门禁逐字节对齐**。）
- 旧护栏（改注释后）：`simos-economy/target/surefire-reports/io.mosire.simos.economy.codec.EconomyCodecTest.txt`，
  mtime **02:23:59**，**28 条 / 0 失败**。
- Spotless：**473 个文件 clean，0 needs changes**；SpotBugs：**BugInstance size is 0**；前端门禁 **412/412**。
- 证据：`evidence/green-final.txt` / `.xml`（新护栏）、`evidence/green-final-OLD-guardrail.txt`。

## 8. 本批新增/改动的文件（md5）

| 文件 | md5（收工那一刻） |
|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/determinism/CrossProcessDeterminismChild.java`（新，187 行）| `cf3f01fb2d0bf61dbde5815a4e9d8223` |
| `simos-app/src/test/java/io/mosire/simos/app/determinism/CrossProcessDeterminismTest.java`（新，393 行）| `c2d1f51200d6a03eae6c194d64c969a5` |
| `simos-economy/src/test/java/io/mosire/simos/economy/codec/EconomyCodecTest.java`（改 Javadoc，断言零改动）| `3b8f4281d4c166140027c84c5084cb23` |

`git status --porcelain` 收工读数（**`src/main/**` 零改动**）：

```
 M simos-economy/src/test/java/io/mosire/simos/economy/codec/EconomyCodecTest.java
?? simos-app/src/test/java/io/mosire/simos/app/determinism/
```

