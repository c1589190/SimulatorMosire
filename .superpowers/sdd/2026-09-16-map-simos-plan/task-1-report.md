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
   ↑ 以上五行是**原文**（`4335b52`）。第 2 轮 F-新1 指出：R1-d 改写时把最后那行括号**删掉了**，
   而下面 ★ 段当时写的"原文认定判形分支没有判别力"**原文从没说过**。两处一并订正。

   ★ **判决更正（修复轮 1 / R1-d）：这条"未自证"补上了。** R1-c 的 R-M1 实跑"删掉判形分支"⇒ **红**
   （`HexCoordTest.parseRejectsMalformed:45`，理由是**异常类型**）。**判别力来源只有 `""`**：`i = -1` 时
   `substring(0, -1)` 先于任何 `parseInt` 炸成 `StringIndexOutOfBoundsException`，而 **`SIOOBE` 不 `extends` `IAE`**，
   `isInstanceOf(IllegalArgumentException.class)` 当场翻。`"_"`/`"1_"`/`"_2"` **不是**来源——删掉判形后它们由 `NFE`
   挡下，而 **`NFE` 是 `IAE` 的子类**，断言不翻（逐输入探针见「修复轮 1」§3.3）；`"12"` 与 `""` 同源。

   **当初那条自评哪里不对**：原文说这四条**都**"守着"判形——**四条里只有 `""` 真的守得住**，另外三条删掉判形后
   照样抛 `IAE`。它把"同一条 `try` 里的其余字符串"顺手归成了同一类，没有逐个实测**异常类型本身**；这正是本项目
   "红了要问为什么红 / 异常类型不同 ≠ 判别力"那条纪律的镜像（形态 1 的兄弟：**没红也要问为什么没红**）。
   **但它有一处是对的**：它**拒绝**把"已测"说成"已自证"——这个自我评估**成立**，缺的只是一次实跑。
   这段轨迹**保留**，因为抹掉它等于抹掉一次真实的判别力误判记录。

   ⚠️ **第二次更正（第 2 轮 F-新1；控制器当场修，未另开评审轮）**：★ 段的**前一版**（修复轮 1 落的）写着
   "原文……**认定判形分支没有判别力**、只配得上'已测'二字"——**原文从没这么说过**。那是**控制器**在需求书
   R1-d 里的转述，把原文的"**未自证**"记成了"**无判别力**"；实现者照着那条错误指令写，于是审计轨迹里留下了
   一段**替原文认罪的假自白**。原话见上面恢复的五行。
   **教训**：转述一份文件之前先回读它——这是形态 5（"我验过了"与"我记得是这样"必须分开）的又一次踩坑，
   而这次的代价是**把假话写进了留痕**。
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

---

## 修复轮 1（R1-a … R1-f）

> **一句话**：六项全部执行；每条新增/加固的护栏都牵了自己的变异体（R-M1…R-M8）；门禁两条全绿（exit 0）。
> **最要紧的一条**：R1-e 想证的"这条顺序以前没人钉"**实测证到了**——在**修复前的字节**（`b0bcf0e`）上，
> R-M2（邻居顺序反转）与 R-M3（`next`/`prev` 真交换）**都是绿的**（各 21/0，BUILD SUCCESS）；在修复后的字节上**都红**（§3.2）。
> 本轮**没有重写**前面已成立的章节；只按 R1-d 的要求把 §7 第 1 项那处**结论就地改掉**（改动可见 §7 的 ★ 段）。

### 1. 逐项对账 R1-a … R1-f

| 项 | 落在哪（文件:行） | 改了什么 |
|---|---|---|
| **R1-a** | `HexGrid.java:72-74`（守卫）＋`:68-69`（Javadoc）＋`HexGridTest.java:38-40`（断言在 `:39`） | 负半径由"静默返回空集"改为**抛 `IllegalArgumentException`**：`if (radius < 0) { throw new IllegalArgumentException("半径不能为负: " + radius); }`。Javadoc 口径同步改成"**radius 为负时抛 `IllegalArgumentException`**——返回空集是'静默夹取'的近亲"。用例里那条 `-1 → isEmpty()` 换成 `assertThatThrownBy(...).isInstanceOf(IllegalArgumentException.class)`。★ 审查报告写的 `HexGridTest.java:30` **是错的**，改动前那条断言确实在 `:38`（与需求书一致）——已按 `:38` 改。 |
| **R1-b** | `HexCoord.java:29-41`（Javadoc）＋`:42-61`（实现） | 按 spec §3.4 新增 `public static HexCoord round(double q, double r)`：三轴各自 `Math.round`，再把**偏差最大**的那一轴改写成"另两轴之和的相反数"（**不是**逐轴四舍五入——Javadoc 里就举了 `(0.5, 0.5)`：逐轴取整得 `(1, 1)`，距该点整 1 格，真正的最近格是 `(1, 0)`/`(0, 1)`，距离 0.5）；`NaN`/±∞ 抛 IAE；**并列时取哪一侧不定义**、**`int` 越界行为不定义**，两条都写进了 Javadoc。三条用例见下。★ 一处**因 SpotBugs 被迫的结构调整**，登记在 §4 第 3 条。 |
| **R1-c** | 只加变异行（代码零改动）：本轮 §3.1 的 **R-M1** | 删掉 `parse` 的判形分支 ⇒ **红**。红在 `HexCoordTest.parseRejectsMalformed:45`，理由是**异常类型**：`""` 走 `i = -1 → substring(0, -1)`，先于任何 `parseInt` 炸成 `StringIndexOutOfBoundsException`，而 **`SIOOBE` 不是 `IAE` 的子类** ⇒ `isInstanceOf(IllegalArgumentException.class)` 翻。判别力来自 **`""`**；`"_"`/`"1_"`/`"_2"` **不是**来源（它们抛 NFE，**NFE 是 IAE 的子类**，断言不翻）——探针逐输入实测，见 §3.3。 |
| **R1-d** | 本文件 §7 第 1 项（**就地改写**） | 结论改判：判形分支**有**判别力，来源是 `""`（不是 `"_"`/`"1_"`/`"_2"`）。轨迹保留。⚠️ **本行已在第 2 轮 F-新1 更正两处**：① 原文说的是"**没实跑**、只算'已测'不算'已自证'"，**不是**"没有判别力"——那是控制器的误转述；② 原文那五行括号**并未**"仍在"（本次改写把它删掉了，现已在 §7 恢复）。详见 §7 第 1 项的 ⚠️ 段。 |
| **R1-e** | `HexCoordTest.java:204`（`neighborsFollowDirectionOrder`，断言在 `:217`/`:226`/`:247`）＋ `HexDirectionTest.java:47`（`nextAndPrevFollowFrozenCycle`，12 条绝对断言在 `:48-60`） | 冻结表照抄需求书：`(0,0)` 的 A 序邻居 = `(1,0) (0,1) (-1,1) (-1,0) (0,-1) (1,-1)`；`(2,-3)` 的 = `(3,-3) (2,-2) (1,-2) (1,-3) (2,-4) (3,-4)`；再用 6 个起点各走 6 次 `next()` 走回起点、且沿途正是 **A 序环**（回不到起点或环序不对都翻）。`next`/`prev` 那 12 条写成**绝对目标**（`E.next() == SE` …），**没有**写成"互为逆"或"与某表达式一致"。需求书第 3 条用例按裁定**并入** `neighborsFollowDirectionOrder`，**没有新开方法**。 |
| **R1-f** | (1) `HexCoordTest.java:80-88`；(2) `HexCoord.java:10`、`HexGrid.java:12`（另修 `HexGridTest.java:74`）；(3) `HexGrid.java:26-28`；(4) `HexCoordTest.java:46` | (1) 把 `distanceMatchesCubeFormula` 注释里那句越界的"能抓 `s()` 写错"改成它真正钉的东西——**`distanceTo` 的组合规则**（漏 `/2`、只用两轴，都会翻），并写明它对该变异**完全免疫**（对拍两侧都调 `s()`，且 `z = x + y` 恒有 `max(|x|,|y|,|z|) == (|x|+|y|+|z|)/2`），`s()` 由 `sAxisInvariant` 钉——这句"实测"现在由 **R-M8** 背书（§3.1）。(2) 两处接缝空格已消；`spotless:apply` 后按 `grep -rnP '[\p{Han}] [\p{Han}]'` 复查为 **0**，另用汉字标点口径的正则又查出并修掉 `HexGridTest.java:74` 一处 **HEAD 里就有**的接缝（审查报告的正则漏了它，因为空格后跟的是 `，`）。(3) **F4 按裁定撤回**：**不加** `requireNonNull`；只在 `HexGrid.of` 的 Javadoc 补**一句**——入参是程序内部对象，`null` 按 JDK 惯例抛 NPE（与 `Set.copyOf(null)` 一致）、不另设显式守卫；而 JSON 边界上的 `HexCoord.parse(String)` 另有守卫。守卫**没有**落地（`of` 的方法体一行未动）。(4) `parseRejectsMalformed` 增 `"12"`（断言行 `:46`）。 |

**R1-b 的三条用例**：`roundIsNearestHex`（`:122`，断言 `:138`）暴力枚举 ±4 内全部候选格，比的是**距离等价**——`distanceFromDoubled`（`:144`）把分数点与被比格**各放大两倍**后借 `distanceTo` 比较（各轴差值全是偶数，`/2` 精确）；因为比的是距离，**并列格天然合法**（返回的格只要是最小距离的之一就通过），这条正是"不许要求坐标相等"的落点。`roundOnFrozenSamples`（`:150`）钉三组**不并列**的冻结输入（`(0.6,0.4)→(1,0)`、`(-0.6,-0.4)→(-1,0)`、`(2.2,-1.1)→(2,-1)`）。`roundRejectsNonFinite`（`:158`，断言 `:161`/`:164`）NaN/±∞ 分别落在 `q` 位与 `r` 位。

### 2. 门禁（原样；索引日志与**全文原始输出**都在 `task-1-evidence/`）

两条都在**本轮最终字节**上跑，运行前工作树 == 日志里的字节（§3 的逐文件 md5 可比对）。

`./mvnw -pl simos-map -am verify` → **exit=0**（`log-R1-gate-module.txt` 索引／`raw-R1-gate-module.txt` 全文）
```
[INFO] --- spotless:3.10.2:check (spotless-check) @ simos-map ---
[INFO] --- checkstyle:3.6.0:check (checkstyle-check) @ simos-map ---
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.map.hex.HexCoordTest
[INFO] Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.map.hex.HexDirectionTest
[INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.map.hex.HexGridTest
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
[INFO] BugInstance size is 0
[INFO] BUILD SUCCESS
[INFO] Total time:  5.544 s
```
`./mvnw verify`（全六模块）→ **exit=0**（`log-R1-gate-all.txt` 索引／`raw-R1-gate-all.txt` 全文）
```
[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0   （UtilSimos）
[INFO] Tests run: 26,  Failures: 0, Errors: 0, Skipped: 0   （MapSimos）
[INFO] Tests run: 15,  Failures: 0, Errors: 0, Skipped: 0   （CoreSimos）
[INFO] BugInstance size is 0                                （util/map/social/unit/core 各一次，共 5 次）
[INFO] UtilSimos/MapSimos/SocialSimos/UnitSimos/CoreSimos .. SUCCESS（5/5）
[INFO] BUILD SUCCESS
[INFO] Total time:  10.622 s
```
两轮日志里 `spotless:check` 与 `checkstyle:check` 各跑了 **6** 个模块（父 POM + 五模块），**无 `[ERROR]` 行**。★ 记一笔口径：`log-R1-gate-*.txt` 的**第一版索引把 spotless/checkstyle 的行滤掉了**（我的过滤正则写窄了）——原始输出一字未动，只是重建索引，明细见 `md5-manifest.txt` 的"门禁日志补全"段。

### 3. 变异（R-M1 … R-M8）

#### 3.0 装置与自证口径

- 实验室在 **`/tmp/hexmut-r1`**（仓根 `rsync` 副本，排除 `.git`/`target`）；**工作树全程未被变异**（本轮跑完再逐文件比对实验室与工作树，六个文件 md5 全同）。
- 原件参照 = `task-1-evidence/ref-r1/*.java`（= 本轮提交的字节）。变异体 = `mutants/R-M*.java`，每个都是"ref-r1 副本 + **恰好一处**定向替换"，生成器 `make_mutants.py` 对每条 needle **断言命中次数 == 1**（`R-M3` 是**两处真交换**，第二处对已改文本再换，且断言只命中 `prev` 块）。
- 装盘**按白名单**推目标类名（`HexCoord`/`HexDirection`/`HexGrid`），**绝不按变异文件名**；每轮先清掉规范名之外的 `.java`。★ 这条本轮**真的响过一次**：我把修复前世界的变异体命名为 `R-M2.b0bcf0e.HexCoord.java`，白名单当场拒收（`目标名不在白名单: b0bcf0e.HexCoord.java —— 本轮作废`），改名成 `R-M2-preR1.HexCoord.java` 才跑。
- 装盘**三方 md5 自证**：装入后的源文件 == 变异体产物，**且 != 原件参照**（否则 javac 可能编的是原件，得到"三路全绿"的假象）。每轮**强制断言 `COMPILATION ERROR` 计数 == 0**（红了必须红在断言上，不是红在编译上），并打印**编译产物 `.class` 的 md5**，证明变异真的进了字节码。
- 基线（修复后字节）：`Tests run: 26, Failures: 0`，`maven_exit=0`，`COMPILATION_ERROR_COUNT=0`；`HexCoord.class=51c86ce11fb26d3fc4a84fd74dcb0448`、`HexDirection.class=605a4bc3c2ef8a43dce62a8a81d1364b`、`HexGrid.class=d54a1173adaf4ade82fccc3f5d96eb3f`。
- **红了的每一个都问过"为什么红"**：红的必须是**被保护的那行**所管的行为（见每条的"为什么红"列）；跑偏一次就作废重做（§3.2 末尾那次）。**没红的也问过"为什么没红"**（§3.4）。

#### 3.1 修复后的字节

| 变异 | 内容 | 源 md5 自证（产物=装入 ≠ 参照） | 编译产物 | 修复后 | 为什么红（= 被保护的那行） |
|---|---|---|---|---|---|
| **R-M1** | 删掉 `parse` 的**判形**分支（只留 `int i = text.indexOf('_');`） | `bf85532ccb6651f8e5f57b04b4935a6f`（参照 `bb0cd038…`） | `HexCoord.class 01ec6d17…` | **26 → Failures 1**：`HexCoordTest.parseRejectsMalformed:45` | `:45` 断言的是"非法串抛 IAE"；删掉判形后 `""` 先炸成 **`StringIndexOutOfBoundsException`**（`⊄ IAE`），翻的正是 `:45` 自己。R1-c 要的那一行。 |
| **R-M2** | `neighbors()` 加 `.reversed()`（`ALL.stream()` → `ALL.reversed().stream()`） | `ee4dca0da4bca942a6167b28ca9c98b4` | `HexCoord.class fd7922e6…` | **26 → Failures 1**：`HexCoordTest.neighborsFollowDirectionOrder:217`（打印了完整 6 元序差异） | `:217` 是 `containsExactly`（**有序**）；反转后序反。R1-e 新增的这条正是唯一的抓手——**修复前它是绿的**（§3.2）。 |
| **R-M3** | `next()` 的 `+1` 与 `prev()` 的 `+5` **真交换** | `b245d55346d720b5f37b5a0d2eb7dd47` | `HexDirection.class 46bcae51…` | **26 → Failures 2**：`HexDirectionTest.nextAndPrevFollowFrozenCycle:48`（`expected: SE but was: NE`）＋ `HexCoordTest.neighborsFollowDirectionOrder:247`（`[从 E 起的 A 序环]`） | `:48` 是**绝对目标** `E.next() == SE`；`:247` 是 A 序环。两条都直接钉方向本身。**修复前两条都没有**，故修复前是绿的（§3.2）。 |
| **R-M4** | 删掉 `withinRadius` 的 `radius < 0` 守卫 | `68efc4165040dd0f26d37e07e6e5f5df`（参照 `f3ec0484…`） | `HexGrid.class 25a47fbe…` | **26 → Failures 1**：`HexGridTest.cellsWithinRadiusIsClosedBall:39`（`Expecting code to raise a throwable`） | `:39` 就是 R1-a 那条"负半径必抛 IAE"。 |
| **R-M5** | `round` 改成**逐轴四舍五入**（`return new HexCoord((int) Math.round(q), (int) Math.round(r));`，finite 守卫保留） | `8b0ce4d1bde17315514813a72c1ec5a5` | `HexCoord.class 47c2d600…` | **26 → Failures 1**：`HexCoordTest.roundIsNearestHex:138 [(-1.5, -1.5) 的取整格是否最近]`（`expected: 0.5 but was: 1.0`） | `:138` 是"取整格到该点的距离 == 最小距离"。朴素取整给出的格距离 1.0，而 0.5 可达 ⇒ 不最近。（`(-1.5,-1.5)` **不是并列**情形，断言失效不是并列造成的。） |
| **R-M6** | 删掉 `round` 的 finite 守卫 | `9c96598b97ec7d50bd32428f3da3e8da` | `HexCoord.class 51072fd3…` | **26 → Failures 1**：`HexCoordTest.roundRejectsNonFinite:161`（`Expecting code to raise a throwable`） | `:161` 就是"`NaN`/±∞ 必抛 IAE"；删掉守卫后 `Math.round(NaN)` 静默给 0。 |
| **R-M7**（我加的） | `distanceTo` 只用两轴：`(|dq|+|dr|+|ds|)/2` → `(|dq|+|dr|)/2` | `1c0f51528fb7ad0cffb51e57eb1fd92d` | `HexCoord.class 81d3f0c4…` | **26 → Failures 4**：`distanceMatchesCubeFormula:96`、`distanceOnKnownPairs:106`、`neighborsAreSixDistinctAtDistance1:189`、`HexDirectionTest.everyNeighborIsAtDistanceOne:100` | 背书 R1-f(1) 注释里"**只用两轴**也会翻"那句——它翻的正是 `:96` 这条对拍。 |
| **R-M8**（我加的） | `s()` 的 `-q - r` 写成 `q + r`（**第三轴整体变号**） | `25f7d36fdba12f7d5c7dd9d0d4b8f06d` | `HexCoord.class a214676f…` | **26 → Failures 1**：`HexCoordTest.sAxisInvariant:59 [-20_-20 的 cube 恒等式]`（`expected: 0 but was: -80`） | 背书 R1-f(1) 注释的另一半：`distanceMatchesCubeFormula` 对它是**免疫**的（**没有**出现在失败名单里，实测），`s()` 只被 `sAxisInvariant` 钉住。 |

八条的 `COMPILATION_ERROR_COUNT` **全为 0**；每条的 `.class` md5 与基线**不同**（且只有被改的那一个类变了，另两个与基线逐字相同）——变异确实进了字节码。

#### 3.2 修复前的字节（`b0bcf0e`）——R1-e"以前没人钉"的实证

装置：`run_old_world.sh`（同纪律：白名单、三方 md5、强制断编译错误为 0），把 `b0bcf0e` 的 **3 个 main + 3 个 test** 原样取出（`ref-b0bcf0e/`），再装变异体。

| 变异（修复前世界） | 源 md5 自证 | 结果 |
|---|---|---|
| **R-M2-preR1**（`neighbors()` 反转，needle 打在 `b0bcf0e` 原文上） | `793ff2830ba220c11298494b4f6b955e` ≠ `0254e25b…`（`b0bcf0e` 原件） | **21 tests, 0 failures，BUILD SUCCESS（绿！）** |
| **R-M3-preR1**（`next`/`prev` 真交换） | `b245d55346d720b5f37b5a0d2eb7dd47` ≠ `fd804a33…`（`b0bcf0e` 原件） | **21 tests, 0 failures，BUILD SUCCESS（绿！）** |

★ 三条附带结论：
1. **R1-e 的两条用例不是装饰**：在修复前的字节上，这两个变异**一个都抓不住**（修复前 21 条用例全绿），修复后同一份变异**双双变红**（§3.1）。这就是需求书要"钉住顺序"的全部理由，现在是测出来的、不是推出来的。
2. **R-M3-preR1 与 R-M3 字节完全相同**（`b245d553…`）：`HexDirection.java` 本轮**一行未改**（改的只是它的测试），故"修复前的那个交换"与"修复后的那个交换"本就是同一个文件——这也顺带说明修复前的绿**不是**变异体造得不一样。
3. **R1-a 改的是可观测行为**：顺手跑了一次"**R1 后的 main + R1 前的 test**"（`log-R3-preR1-tests-vs-R1-main.txt`），red 在 `HexGridTest.cellsWithinRadiusIsClosedBall:38 » IllegalArgument 半径不能为负: -1`——老期望（`isEmpty()`）被新守卫直接顶翻，且**再次印证那条断言在 `:38`**（审查报告写的 `:30` 是错的）。
4. **一次"红错了理由"的现场（我抓到的）**：R-M3-preR1 的**第一版**我用裸行 needle 做第二处替换，`    return ALL.get((ordinal() + 5) % 6);` 一次命中**两行**（`next` 刚改成的 `+5` 与 `prev` 原本的 `+5`），结果变异体成了"**`prev := next`**"而**不是真交换**——它当然也红（`nextAndPrevAreInverse:31`，`expected: E but was: SW`），但**红的理由不是"测试能抓交换"**，而是"变异体本身是错的"。改成**块级 needle**（带方法签名）后才是真交换，结果为绿。这正是"**红了还要问为什么红**"的又一例：只看到"有 red"就收工，会得出**与事实相反**的结论。

#### 3.3 探针（`log-probe-R1-parse-exception-types.txt`，源码 `ProbeR1.java`）

逐输入打印 `parse` 抛出的**异常类型**，并直接判"`isInstanceOf(IAE)` 会不会翻"：

| 输入 | 原件 | R-M1（删判形） |
|---|---|---|
| `""` | `IAE`，不翻 | **`StringIndexOutOfBoundsException`，会翻** |
| `"12"` | `IAE`，不翻 | **`StringIndexOutOfBoundsException`，会翻** |
| `"_"` / `"1_"` / `"_2"` | `IAE`，不翻 | `NumberFormatException`，**不翻** |
| `"a_b"` | `NumberFormatException`，不翻 | `NumberFormatException`，**不翻** |

⇒ **判形的判别力来自 `""`/`"12"`**，`"_"`/`"1_"`/`"_2"` **不是**来源（NFE 是 IAE 的子类）。同一份探针还实测了 F4 裁定所依据的三处 `null` 入参：`HexGrid.of(null)` → `NPE: Cannot invoke "java.util.Collection.isEmpty()" because "coll" is null`；`HexGrid.of(Set.of()).contains(null)` → `NPE`；`HexGrid.withinRadius(null, 0)` → `NPE: Cannot invoke "...HexCoord.q()" because "center" is null`。三者都是 JDK 自己抛的**热心 NPE**，这既说明"不加 `requireNonNull`"不会把错误藏起来（与 `Set.copyOf(null)` 的口径一致），也说明**这类守卫若加，只有精确匹配消息才有判别力**（形态 2）。

#### 3.4 "为什么没红 / 为什么只有它红"

- **R-M1 下 `"12"`（`:46`）在套件层面看不到**：`parseRejectsMalformed` 在**第一条**断言（`:45`，`""`）就中止，`:46` 根本没执行。所以 `"12"` 的判别力**只有探针级证据**（§3.3 实测它会翻），这一点已写进 §4 第 1 条，不冒充套件级。`""` 与 `"12"` 同源（都走 `substring(0, -1)`），加 `"12"` 的价值是**输入面**更宽，不是补一条独立通道。
- **R-M8 只翻一条**：26 条里只有 `sAxisInvariant` 翻，`distanceMatchesCubeFormula`／`distanceOnKnownPairs`／`neighborsAreSixDistinctAtDistance1`／`everyNeighborIsAtDistanceOne` **全都没翻**——这就是 F2 注释说"对拍对它完全免疫"的实测依据（距离里 `|s1 - s2|` 对整体变号不变）。
- **R-M7 翻了 4 条**（`:96`/`:106`/`:189`/`HexDirectionTest:100`）：说明 `distanceTo` 的组合规则不只被对拍钉着，另有三条用例**独立**覆盖——注释里只声称 `:96` 会翻，实际更强，故注释不必改（少报不算越界）。

### 4. 我没做 / 做不到的（请控制器裁）

1. **`"12"` 只有探针级证据**（理由见 §3.4）。若你要**套件级**的，最便宜的做法是把 `"12"` 抽成独立一条用例（这样它不会被执行顺序掩盖）；那会**新开一条计划外的用例**，我没擅自加——加与不加都请裁。同理，`parse` 的 `null` 入参（`:51`）也没进过任何变异体。
2. **F2 注释里那句"实测"原本是没实测过的**：我核了 R-M1…R-M7 没有 `s()` 变异，也就是说那句在写下的当时属于"我记得是这样"。本轮补跑 **R-M8** 才让它是真的（结果恰好与断言一致）。按纪律我把它单列出来——**"我验过了"与"我记得是这样"必须分开**；本轮它侥幸对了，但当时的状态是后者。
3. **`round` 里一处偏离需求书草图的改动（SpotBugs 逼的）**：需求书的做法若把"s 轴偏差最大"那支写成 `else { rs = -rq - rr; }`，`verify` 会红在 `[ERROR] Medium: Dead store to rs in …HexCoord.round(double, double) … DLS_DEAD_LOCAL_STORE`（`rs` 只参与偏差比较、**不参与返回**）。我改成"**故意不写 else**"并在代码里写明理由（改 `q`/`r` 反而错；写 `rs` 是死存储）。**对所有入参行为等价**（`rs` 本来就不影响返回值），最坏情况下少修正一次"并列"——而**并列取哪一侧已按 spec 裁定为不定义**。它确实偏离了草图，登记在案。
4. **我自己加的、超出派单的东西**（不算需求书要求，供审查取舍）：用例 `roundOnFrozenSamples`（`:150`，派单只点名了暴力对拍与 finite 两条）；`HexGridTest.java:74` 那处 **HEAD 里就存在**的接缝空格（审查报告的正则没覆盖"空格后跟汉字标点"）；变异体 **R-M7**、**R-M8**；修复前世界的两次跑（§3.2）与那次"R1 main + R1 前 test"的混合跑；`run_old_world.sh`。
5. **本轮提交的 SHA 无法写进本文件**：本文件就在那个提交里，写进去必然自指。SHA 在回给控制器的正文里；§6 的老条目讲的是上一个提交（`b0bcf0e`），未改动。

### 5. 本轮最终字节（工作树 == 提交内容，逐文件 md5）

| 文件 | md5 |
|---|---|
| `.../main/java/io/mosire/simos/map/hex/HexCoord.java` | `bb0cd0389dc5084a0295a3a7e27dea5f` |
| `.../main/java/io/mosire/simos/map/hex/HexDirection.java` | `fd804a33272adcde70d5dbaa1ed00dcb`（**本轮未改**，= `b0bcf0e` 的字节） |
| `.../main/java/io/mosire/simos/map/hex/HexGrid.java` | `f3ec0484326452a2d9c977ea27e2f82a` |
| `.../test/java/io/mosire/simos/map/hex/HexCoordTest.java` | `ac39f06edea33cd5525f83ac6377fc7d` |
| `.../test/java/io/mosire/simos/map/hex/HexDirectionTest.java` | `64de2708d2a80f81eef0349f2cb954cd` |
| `.../test/java/io/mosire/simos/map/hex/HexGridTest.java` | `61b19c66fad549af2179d4fa5cf6c4cd` |

约束复核（本轮出口处再跑一遍）：`java.io` / `java.nio` / `Files` / `Path` 在 `simos-map/src` 下**零命中**；hex 包 `import` 全表 = `java.util.{ArrayList,Collections,Comparator,HashSet,List,Set}` + `org.junit.jupiter.api.Test` + AssertJ 两个静态导入（**无新增依赖、无 pom 改动**）；`equals`/`hashCode` 仍全部来自 record（`toString` 按 spec §3.1 保留手写，见本文件 §7 第 3 条的老登记）；`git status` 里 `simos-map/src` 只有上述 **5 个文件**被改（`HexDirection.java` 不在其中）。
