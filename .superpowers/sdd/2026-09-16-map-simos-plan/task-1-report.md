# Task 1 实现回报 —— `hex` 包：坐标、方向、网格

**状态**：DONE_WITH_CONCERNS
**提交**：`b0bcf0e1237dbbc826539e2a98f5732b74e7dd0d`（分支 `feat/m1-util-simos`，**未推送**）
**用例**：21 条全绿（HexCoordTest 9 / HexDirectionTest 7 / HexGridTest 5）；`./mvnw clean verify` 全 reactor 绿

---

## 1. 改了什么

| 文件 | 职责 |
|---|---|
| `simos-map/src/main/java/io/mosire/simos/map/hex/HexCoord.java`（70 行） | axial `(q,r)` record，**格的唯一身份**；`s()` 由 q,r 导出不存储；`distanceTo` 一份实现；`neighbor`/`neighbors` 一律走 `HexDirection`（无硬编码偏移）；`toString`/`parse` 是 `"q_r"` 的唯一两份实现，只在 JSON 边界用 |
| `simos-map/src/main/java/io/mosire/simos/map/hex/HexDirection.java`（54 行） | **全模块唯一方向表**，A 序 `E,SE,SW,W,NW,NE`；`opposite`/`next`/`prev` 由枚举序派生；`ALL = List.of(values())` |
| `simos-map/src/main/java/io/mosire/simos/map/hex/HexGrid.java`（79 行） | 纯几何：`of(Set<HexCoord>)`、`minQ/maxQ/minR/maxR`（空网格抛 `IllegalStateException`）、`contains`、`cells`、`withinRadius` 闭球。**无 `gridSize`**、**无 `HexCell`**（Task 5 才落地） |
| `simos-map/src/test/java/io/mosire/simos/map/hex/HexCoordTest.java`（157 行） | 9 条：冻结串往返、非法串、cube 恒等式、距离对称/自反/独立公式对拍/手算种子、邻格往返、6 邻格、全序 |
| `simos-map/src/test/java/io/mosire/simos/map/hex/HexDirectionTest.java`（78 行） | 7 条：反向对合且非自身、next/prev 互逆、ALL 覆盖枚举、**冻结表锚**、偏移互异、邻居距离恒 1 |
| `simos-map/src/test/java/io/mosire/simos/map/hex/HexGridTest.java`（81 行） | 5 条：范围导出 + 空网格抛异常、闭球计数、球内全在范围内、构造期拷贝、返回集合不可变 |

**相对需求书的三处增补**（都已在下面自证，可随时裁掉）：
1. `parseRejectsMalformed` 的输入表加了 `null`——对应控制器裁决 (b) 的显式判空，加这一行才使该守卫可被证伪（见 M5）。
2. 新增 `mutatingTheSourceSetDoesNotAffectTheGrid`（构造期 `Set.copyOf` 的自证，见 M6）。
3. 新增 `returnedSetsAreUnmodifiable`（两处返回点的自证，见 M7）。

**控制器三处裁决的落实**：(a) `HexGrid` 不含 `HexCell`、无占位字段；(b) `parse(null)` 抛 `IllegalArgumentException`；
(c) `"a_b"` 靠 `NumberFormatException`（`IllegalArgumentException` 的子类）满足——**已在 `HexCoord.parse` 的 Javadoc 里
如实写明"这一条没有任何一行显式检查在挡它"**，且报告中单列（见 §7 第 1 条）。

---

## 2. 测试结果

```bash
# 工作树，最终提交的字节（md5 见 §6）
./mvnw -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test
./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test   # exit=0
```

原始输出（节选自非 `-q` 那次）：

```
[INFO] Running io.mosire.simos.map.hex.HexCoordTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.076 s -- in io.mosire.simos.map.hex.HexCoordTest
[INFO] Running io.mosire.simos.map.hex.HexDirectionTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in io.mosire.simos.map.hex.HexDirectionTest
[INFO] Running io.mosire.simos.map.hex.HexGridTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.009 s -- in io.mosire.simos.map.hex.HexGridTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

全量门禁 `./mvnw clean verify`（exit=0）：`UtilSimos 156 / MapSimos 21 / CoreSimos 15` 全绿，
各模块 `BugInstance size is 0`，六个模块 `SUCCESS`。

---

## 3. 自证表（★ 核心交付）

**证据集是用最终测试文件重跑的一整套**（我中途强化过 `returnedSetsAreUnmodifiable`，见 §7 第 4 条；此前那一轮
M1~M6 的产物**已作废、不引用**）。实验室 = `/tmp/hexmut`（工作树的完整副本，**工作树本体全程未被变异触碰**，见 §6）。
每次跑前 `rm -rf simos-map/target`，强制 javac 重编。

**改前基线**（`base-final`，三个源文件 == 参照件）：

```
### tag=base-final  maven_exit=0
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
HexCoord.class=65e11c69cfccf896787e0afc4e465dac  HexDirection.class=605a4bc3c2ef8a43dce62a8a81d1364b  HexGrid.class=3c5c7d1f4050e0af7e8ca7f84ed93c14
```

**★ 这一整套（基线 + 7 条变异）跑了两遍**，两遍的 `Tests run`、失败清单、三个 `.class` 的 md5 **逐字节相同**。
第二遍的全部原始输出（含 maven 输出与 md5 段，非人工誊抄）已落在工作区：
`.superpowers/sdd/2026-09-16-map-simos-plan/task-1-evidence/` ——
`log-{base-final,m1-…,m7-…}.txt`（每份都含 `### maven_exit=` 与 `### 编译产物 md5`）、
`ref/`（原件参照）、`mutants/`（7 个变异产物）、`md5-manifest.txt`、`run_full.sh`（一条命令可复跑整轮）。
**该目录只收最终一轮**：被我作废的那一轮（§7 第 4 条）的日志**未**收录，只在 `/tmp/hexmut/log-base*.txt` 里留有残迹——
要引用请只引工作区那一份，`/tmp` 随时会被清。
**复核入口**：`grep -E "maven_exit|Tests run: 21|\.class" task-1-evidence/log-*.txt`。
（该目录与整个 M2 工作区一样，目前被 `.superpowers/sdd/.gitignore` 的 `*` 规则挡在版本库外、**未跟踪**——
进不进仓库按你的 U4 裁决办，我未 `git add -f` 任何工作区文件。）

| # | 变异 | 改前（原件，上表） | 改后（变异体，原始输出节选） | 结论色 |
|---|---|---|---|---|
| **M1** | `opposite()` 的 `+3` → `+0` | 21/21 绿，exit 0 | `maven_exit=1`；`HexDirectionTest.oppositeIsNotSelf:24 [E 的反向]`：`Expecting actual: E not to be equal to: E`；另连带 `HexCoordTest.neighborIsInvolutive:108 [-3_-3 沿 E 往返] expected: -3_-3 but was: -1_-3`；总计 `Tests run: 21, Failures: 2` | **红 ✅** |
| **M2** | 冻结表 `SE(0,1)` / `SW(-1,1)` 偏移对调 | 同上 | `maven_exit=1`；`HexDirectionTest.offsetsMatchFrozenTable:57 [索引 1（SE）的 dq] expected: 0 but was: -1`；连带 `neighborIsInvolutive:108 [-3_-3 沿 SE 往返] expected: -3_-3 but was: -4_-3`；总计 `Tests run: 21, Failures: 2` | **红 ✅** |
| **M3** | `distanceTo` 的 `/ 2` 删掉 | 同上 | `maven_exit=1`；5 条红：`distanceMatchesCubeFormula:87 [-6_-6 → -6_-5] expected: 1 but was: 2`、`distanceOnKnownPairs:97 expected: 1 but was: 2`、`neighborsAreSixDistinctAtDistance1:121 [3_-3 到中心]`、`HexDirectionTest.everyNeighborIsAtDistanceOne:75 [E 的邻居]`、`HexGridTest.cellsWithinRadiusAllInRange:48 [半径 1 内的 -3_1]`；总计 `Tests run: 21, Failures: 5` | **红 ✅** |
| **M4** | `neighbors()` 改成返回 5 个（`.limit(5)`） | 同上 | `maven_exit=1`；`HexCoordTest.neighborsAreSixDistinctAtDistance1:118`：`Expected size: 6 but was: 5 in: [3_-3, 2_-2, 1_-2, 1_-3, 2_-4]`；总计 `Tests run: 21, Failures: 1`（**只红这一条**） | **红 ✅** |
| **M5** | 删掉 `parse` 的显式判空（控制器裁决 (b) 的自证） | 同上 | `maven_exit=1`；`parseRejectsMalformed:46`：`Expecting actual throwable to be an instance of: java.lang.IllegalArgumentException / but was: java.lang.NullPointerException: Cannot invoke "String.indexOf(int)" because "text" is null`，栈顶 `at io.mosire.simos.map.hex.HexCoord.parse(HexCoord.java:60)` —— **红的正是被删的那一行** | **红 ✅** |
| **M6** | 构造期 `Set.copyOf` → 直接持有入参 | 同上 | `maven_exit=1`；`mutatingTheSourceSetDoesNotAffectTheGrid:64`：`Expecting actual: [9_9] to contain exactly (and in same order): [0_0]`；`returnedSetsAreUnmodifiable:78`：`Expecting actual to be unmodifiable, but invoking "Collection.add(null)" succeeded` | **红 ✅** |
| **M7** | `withinRadius` 的 `return Set.copyOf(ball)` → `return ball` | 同上 | `maven_exit=1`；`returnedSetsAreUnmodifiable:79`：`Expecting actual to be unmodifiable, but invoking "Collection.add(null)" succeeded` | **红 ✅** |

**红的理由都是被保护的那行本身**：7 条全部是**断言失败**（`AssertionFailedError` / `AssertionError`），
**没有一条是编译错误**；失败断言的 `expected/but was` 与被改的那行一一对应。M4 只红一条（与其"只动了
`neighbors()` 的条数"相符），M2 的主红点是冻结表锚（与"这一条是唯一真值锚"的声称相符）。

**变异真的进了编译产物**（"变异没写进磁盘"那个坑的正面证据）：每次改后**只有被改文件的 `.class` md5 变了**，另两个不变——

| 变异 | HexCoord.class | HexDirection.class | HexGrid.class |
|---|---|---|---|
| 改前 base-final | `65e11c69…` | `605a4bc3…` | `3c5c7d1f…` |
| M1 | `65e11c69…`（不变） | **`ab78bb4b…`（变）** | `3c5c7d1f…`（不变） |
| M2 | `65e11c69…`（不变） | **`d11623a6…`（变）** | `3c5c7d1f…`（不变） |
| M3 | **`02ad3e73…`（变）** | `605a4bc3…`（不变） | `3c5c7d1f…`（不变） |
| M4 | **`f42ce1fa…`（变）** | `605a4bc3…`（不变） | `3c5c7d1f…`（不变） |
| M5 | **`75d5fe76…`（变）** | `605a4bc3…`（不变） | `3c5c7d1f…`（不变） |
| M6 | `65e11c69…`（不变） | `605a4bc3…`（不变） | **`0bd7d54e…`（变）** |
| M7 | `65e11c69…`（不变） | `605a4bc3…`（不变） | **`668be525…`（变）** |

★ 另有一条**顺带的自证**：`base` 与 `base-final` 两次独立全量编译得到**完全相同的三个 `.class` md5**，
故"md5 变了"这个判据本身是可靠的（javac 在本机对本项目确定）。

---

## 4. 变异体自证（md5：变异产物 vs 原件参照）

参照件 = `/tmp/hexmut/ref/*.java`，已逐文件核过**与工作树里的原件逐字节相同**（下方 md5 与 §6 的提交 blob md5 一致）。
**每一条都是「先写盘 → 比 md5 → 再跑测试」**：下表 md5 在测试运行**之前**就已算出并打印。

| 变异 | md5(变异产物) | md5(原件参照) | 是否不同 |
|---|---|---|---|
| M1 `m1-opposite-plus0.HexDirection.java` | `b96e8330be1ffd5f4dfb1b4842d93742` | `fd804a33272adcde70d5dbaa1ed00dcb` | **不同 ✅**（`sort -u \| wc -l` = 2） |
| M2 `m2-swap-se-sw.HexDirection.java` | `da7cb8bf47c8caa7266ce7740dab1355` | `fd804a33272adcde70d5dbaa1ed00dcb` | **不同 ✅** |
| M3 `m3-drop-div2.HexCoord.java` | `dc0bc0db58e89c9ba4207b856527907a` | `0254e25bafbd136edd9e74012fee5cd9` | **不同 ✅** |
| M4 `m4-neighbors-five.HexCoord.java` | `c1891199dfaacacc433c750aee93d530` | `0254e25bafbd136edd9e74012fee5cd9` | **不同 ✅** |
| M5 `m5-no-null-guard.HexCoord.java` | `3cb4ad503bc1fd4dfd92c627c3c95e2c` | `0254e25bafbd136edd9e74012fee5cd9` | **不同 ✅** |
| M6 `m6-no-defensive-copy.HexGrid.java` | `8872b8a3810a45bcdb1b99cbfddd13f8` | `1261f98eb7c1af340bc713cbb61118d2` | **不同 ✅** |
| M7 `m7-ball-not-copied.HexGrid.java` | `68073efae8e6f8172c61421e8855e215` | `1261f98eb7c1af340bc713cbb61118d2` | **不同 ✅** |

每条变异**只替换 1 处**（`needle 命中次数 = 1`，脚本里 `assert` 守住），且装入实验室后**再核一次 md5 == 变异产物 md5**
（例：M1 装入后 `b96e8330…`），确保跑的那份就是刚造出来的那份。

**★ 一次真实的翻车（如实报）**：我第一版实验室驱动脚本把变异文件**按变异体的文件名**拷进源码目录
（`m1-opposite-plus0.HexDirection.java`），javac 报 `enum HexDirection is public, should be declared in a file named
HexDirection.java` —— 7 个 tag **全是编译失败**。这不是"红的理由是被保护的那行"，而是**红得莫名其妙**；
若我当时只看"红了没有"就会得出七个假阳性结论。修掉驱动脚本后才得到 §3 的表。**M1 的上一阶段那个坑的同族**：
"红了"与"红的是那行"是两件事。

---

## 5. 跑了哪些命令（原样）

**工作树（最终提交的那份字节）**

```bash
./mvnw -q -pl simos-map spotless:apply
./mvnw -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test
./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test
./mvnw -pl simos-map -am verify
./mvnw -pl simos-map -am spotbugs:check      # ← 失败：Could not find artifact io.mosire:simos-util:jar:0.1.0-SNAPSHOT
./mvnw clean verify                          # ← 全 reactor 硬门禁，exit=0
```

**变异实验室**（`/tmp/hexmut`，跑测一律经 `/tmp/hexmut/run.sh <tag>`）

```sh
cd /tmp/hexmut || exit 99
rm -rf simos-map/target
./mvnw -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test \
  > "/tmp/hexmut/log-$tag.txt" 2>&1
```

**javac**：我**没有直接调用过 javac**，也没有可报的 `javac` 命令行——编译全部由 Maven 的 compiler 插件触发，
日志原样为 `Compiling 4 source files with javac [debug release 21] to target/classes` 与
`Compiling 3 source files with javac [debug release 21] to target/test-classes`。
**"编译真的发生了"是靠"每次跑前删 target"+"`.class` md5 变了"两点间接证明的，不是靠看 javac 的输出。**

---

## 6. commit 哈希与"跑过的字节 == 提交的字节"

```
b0bcf0e1237dbbc826539e2a98f5732b74e7dd0d
```

分支 `feat/m1-util-simos`；`git add` 只列了两个目录，`git diff --cached --stat` 确认**恰好 6 个文件 / 519 行新增**，
无其它文件混入（同时有两份 `docs/` 下的文件处于 M 状态，是**控制器在会话期间改的**，我未 add、未提交它们）。

`git show HEAD:<path> | md5sum` 与跑测试的工作树逐文件比对，**6/6 相同**：

| 文件 | md5（== 提交 blob == 跑过测试的工作树 == 参照件） |
|---|---|
| `HexCoord.java` | `0254e25bafbd136edd9e74012fee5cd9` |
| `HexDirection.java` | `fd804a33272adcde70d5dbaa1ed00dcb` |
| `HexGrid.java` | `1261f98eb7c1af340bc713cbb61118d2` |
| `HexCoordTest.java` | `de9c4e8eceb1f05dd64bd7e35ac0d775` |
| `HexDirectionTest.java` | `37174694446fe8fa76aaac47f0662601` |
| `HexGridTest.java` | `b5caea4373beebfaaf4a4f3b57a6c831` |

**工作树未被污染**：7 个变异全部只落在 `/tmp/hexmut`；跑完我把实验室的三个源文件还原，并再次逐文件比对工作树与参照件，全同。

---

## 7. 未能核实的 / 顾虑（请控制器裁）

1. **`"a_b"` 那条是靠继承关系满足的，不是显式判空**（控制器 (c) 要求报告的口径）。判别力不同：
   删掉 `parse` 里的**判形**分支不影响 `"a_b"`（它由 `Integer.parseInt` 抛 `NumberFormatException` 挡下，
   而 `NumberFormatException extends IllegalArgumentException`）；反过来，删掉判空也不影响 `"a_b"`。
   两条路径**互不覆盖**，M5 只证明了判空那条，**判形那条没有专门变异**（它被 `""`/`"_"`/`"1_"`/`"_2"` 四条输入守着，
   但我**没有**实跑"删掉判形分支"的变异，故此条只算"已测"、不算"已自证"）。
2. **`withinRadius` 对负半径的语义是我定的，不是控制器裁的**：实现上 `radius < 0` 自然返回空集，我在 Javadoc 里
   写成"**radius 为负时返回空集**"并在 `cellsWithinRadiusIsClosedBall` 里钉了一条 `radius = -1 → 空`。
   若你要的是"负半径抛异常"，改一行 + 改一条断言即可；但**现在这个语义是需求书没写、我替他固化下来的**。
3. **`toString` 与全局约束的字面冲突**：本轮硬约束写"`equals`/`hashCode`/`toString` 一律由 record 提供，禁止手写"，
   而需求书要求照抄的 `HexCoord` 代码**明确手写了 `toString()`**（spec §3.1：`"q_r"` 是序列化形式，
   `toString`/`parse` 是它的唯一两份实现）。我按"需求书照抄 + spec 为准"做了，**没有手写 `equals`/`hashCode`**
   （已 grep 确认）。若那条约束要按字面执行，需先改 spec §3.1。
4. **我中途强化过一条自己加的用例**（`returnedSetsAreUnmodifiable` 的入参由 `Set.of(...)` 改成可变 `HashSet`）：
   原来的写法下，`cells()` 那半条断言会**继承入参的不可变性而恒成立**，就算把构造期 `Set.copyOf` 删掉也照样绿——
   属"装饰性断言"。改后 M6 能红两条（`…:78` 与 `…:64`），已在 §3 表内。**这是我自行改的，不是需求书里的要求**。
5. **`./mvnw -pl simos-map spotbugs:check` 直接调 goal 会失败**（`Could not find artifact io.mosire:simos-util:jar:0.1.0-SNAPSHOT`，
   因为 `~/.m2` 里没有已安装的 `simos-util`，而直接调 goal 不走 reactor 的 package）。我改用
   `./mvnw -pl simos-map -am verify` 与 `./mvnw clean verify` 覆盖 SpotBugs（两者都报 `BugInstance size is 0`）。
   需求书 Step 6 未要求 spotbugs，此处只作记录。
6. **分支**：本轮提交落在 `feat/m1-util-simos`。我注意到 spec 在**我开工之后**（23:35）加入了用户裁决 U3
   "M1 先并入 `main`，再从 `main` 开 `feat/m2-map-simos`"。我的派单书写于 23:29、未提分支，故我**没有自行
   切分支或造分支**（怕把未合并的 M1 提交带进 M2 分支）。**这个提交放哪由你定**——`git branch feat/m2-map-simos <hash>`
   或等 M1 合并后再处理都很便宜。
7. **未做（明确超出本任务范围、登记在案）**：spec §3.4 还要求 `HexCoord.round(double q, double r)` 的**唯一一份实现**，
   但它在 15 个任务的 bite-sized 计划里**一处也没有出现**（`git grep round` 全 `docs/` 只命中 spec 那一行），
   需求书也没列它。我**没有实现它**——它无归属任务，实现它就是替计划做决定。
8. **本机 `git grep` 的一次假阴性（现场记录）**：我用 `git grep` 查"不得出现 `java.io`/`Files`"时**返回零命中**，
   差点当成"约束满足"；实际原因是**新文件还没 `git add`，`git grep` 只搜已跟踪文件**。改用 `grep -rn` 重查才有效
   （结论不变：确为零命中，且 main/test 的 import 只有 JDK + JUnit + AssertJ）。**"零命中"必须先问"它到底搜了哪些文件"。**
