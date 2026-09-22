# Task 1（`hex` 包）任务级评审报告 —— 第 1 轮

**评审基线**：`b0bcf0e`（分支 `feat/m2-map-simos`）。六个文件的工作树 md5 与 `git show HEAD:<path>` **逐字节相同**，已逐文件核过。
**判决**：规格符合性 **符合** ／ 代码质量 **良好，但有 1 项 Important 与 3 项 Minor** ⇒ **总判决 Needs fixes**。
**新发现**：报告 §3 声称"7 个变异体全红"——**我全部独立复现、全部属实**；但**另有 2 个我自造的变异体存活**（M10/M11），见 §5-F1。

> **交付提示（给控制器）**：本文件所在的 `.superpowers/sdd/` 受 `sdd/.gitignore` 的 `*` 规则管辖，
> `git check-ignore -v` 实测本文件**被忽略**（`!!`）。要入库须 `git add -f`——
> 同目录的 `review-cee8488..b0bcf0e.diff` 同样处于被忽略状态。**我没有执行任何 `git add`。**

**★ 我没有改工作树的任何文件。** 全部变异在 `/tmp/hexrev`（`git archive HEAD` 出来的干净副本）上做。
收尾时 `git status --porcelain` 显示 `CLAUDE.md` / `progress.md` / `plans/…-map-simos-plan.md` 为 `M`——
其中 `CLAUDE.md` 的 mtime 是 **23:43:25**，晚于我开工、由控制器并发编辑；三者均**非本次评审所改**，hex 六文件 md5 全程未变。

---

## 1. 门禁实跑

### 1.1 本任务用例（brief §6 指定的命令）

```bash
./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test
# → 无输出，EXIT_CODE=0

./mvnw -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test
```

原始输出（节选，退出码 0）：

```
[INFO] Running io.mosire.simos.map.hex.HexCoordTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.080 s -- in io.mosire.simos.map.hex.HexCoordTest
[INFO] Running io.mosire.simos.map.hex.HexDirectionTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in io.mosire.simos.map.hex.HexDirectionTest
[INFO] Running io.mosire.simos.map.hex.HexGridTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in io.mosire.simos.map.hex.HexGridTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
EXIT_CODE=0
```

### 1.2 本模块全门禁（比 brief 要求的多跑一步，覆盖 Spotless/Checkstyle/SpotBugs）

```bash
./mvnw -pl simos-map -am verify
```

```
[INFO] --- checkstyle:3.6.0:check (checkstyle-check) @ simos-map ---
[INFO] --- spotless:3.10.2:check (spotless-check) @ simos-map ---
[INFO] BugInstance size is 0                      ← UtilSimos
[INFO] BugInstance size is 0                      ← MapSimos
[INFO] MapSimos ........................................... SUCCESS [  1.674 s]
[INFO] BUILD SUCCESS
EXIT=0
```

**说明**：本轮派单说"不必跑全量 `verify`，你只需覆盖本任务"。我跑了 `-pl simos-map -am verify`——它覆盖了本任务的全部四个门禁，
但**没有**跑 `clean`（增量编译）；§4 的变异跑法用的是删 `target` 的强制重编，两者互补。

---

## 2. 两个判决

### 2.1 规格符合性：**符合**（逐条，无"差不多"）

| Step / 冻结值 | 判定 | 证据 |
|---|---|---|
| **Step 1** `HexCoord` record + `Comparable` | 做到 | `HexCoord.java:13` `public record HexCoord(int q, int r) implements Comparable<HexCoord>` |
| `s()`：`-q - r`，不存储 | 做到 | `HexCoord.java:16-17`；无 `s` 组件 |
| `distanceTo` 公式 `(abs+abs+abs)/2` | 做到 | `HexCoord.java:26` 与 brief 逐字符一致 |
| `neighbor(d)` 走 `HexDirection`，无硬编码偏移 | 做到 | `HexCoord.java:30-31`；`git grep` 无 `q+1` 类硬编码（spec §3.5 要求） |
| `neighbors()`：`ALL.stream().map(this::neighbor).toList()` | 做到 | `HexCoord.java:35-36` 与 brief 逐字符一致 |
| **`compareTo` 先 q 后 r** | 做到 | `HexCoord.java:41-44`；`compareToIsTotalOrder` 与字典序逐点对拍 |
| **`toString` == `"q_r"`** | 做到 | `HexCoord.java:48`；冻结串 `0_0`/`0_-1`/`-3_7` 与 brief 的样例一致 |
| `parse` | 做到**并有一处已批准偏离** | `HexCoord.java:59-69`。偏离 = 增了 `text == null` 显式判空（控制器裁决 (b) 要求），**不是走样** |
| **Step 2** `HexDirection` A 序 `E,SE,SW,W,NW,NE` | 做到 | `HexDirection.java:14-19`，逐行核对 |
| **偏移冻结表** `(1,0)(0,1)(-1,1)(-1,0)(0,-1)(1,-1)` | 做到 | 源码与 `HexDirectionTest.java:51-52` 的冻结副本、与 brief 三方一致 |
| **`opposite()` == `(ordinal()+3)%6`** | 做到 | `HexDirection.java:39` 逐字符一致 |
| `next()` `+1` / `prev()` `+5` | 做到 | `HexDirection.java:44,49` 逐字符一致 |
| `ALL = List.of(values())` | 做到 | `HexDirection.java:52` |
| **Step 3** `HexGrid` 纯几何、**不引入 `HexCell`**、**无 `gridSize`** | 做到 | `HexGrid.java` 全文无 `HexCell`/`gridSize`；`grep` 零命中 |
| `minQ/maxQ/minR/maxR`，空网格抛 `IllegalStateException` | 做到 | `HexGrid.java:24-41`；`boundsDerivedFromCells` 四条断言逐个钉 |
| `withinRadius` 闭球 | 做到（语义待改，见 §6） | `HexGrid.java:64-74` |
| **Step 4** `HexCoordTest` 9 条，用例名逐条一致 | 做到 | 9/9 存在，名字与 brief 完全一致 |
| `HexDirectionTest` 7 条 | 做到 | 7/7 一致 |
| `HexGridTest` 3 条 | 做到 + 自增 2 条 | brief 三条全在；另加 `mutatingTheSourceSetDoesNotAffectTheGrid`/`returnedSetsAreUnmodifiable`（报告 §1 已申报） |
| `distanceOnKnownPairs` 三组手算种子 | 做到 | `HexCoordTest.java:96-99`；我手算复核 `d((0,0),(2,-1))=(2+1+1)/2=2`、`d((0,0),(-3,3))=(3+3+0)/2=3` ✓ |
| **Step 5** G13 自证表的 4 条变异 | 做到 | 我**独立复现了全部 4 条**（M1/M2/M3/M4），见 §4 |
| **Step 6** 门禁 + 提交范围 | 做到 | diff stat = 6 文件 / 519 行，与报告 §6 一致；提交在 `feat/m2-map-simos` 上（U3 分支手术已解决归属） |

**没有任何"做到了但走样"。** 唯一的偏离（`parse` 判空）是控制器裁决要求的，且实现者如实写进了 Javadoc。

### 2.2 代码质量：**良好**

- **不可变性**：`HexCoord` 是 record（`equals`/`hashCode` 由 record 提供，`git grep` 确认**无手写**——符合收窄后的 R-T1-c）；`HexDirection` 是枚举；`HexGrid` 唯一字段 `final` + 构造期 `Set.copyOf`，两处返回点均不可变。
- **边界条件**：空网格抛 `IllegalStateException`（有专门用例）；`parse` 的三种非法来源（判空／判形／`NFE`）在 Javadoc 里**分别写明**，并明说 `"a_b"` 那条**没有任何一行显式检查在挡它**——这是本项目要的"如实口径"，不是掩饰。
- **没有把简单事写复杂**：`withinRadius` 用 cube 轴的双层循环 + `from/to` 夹取，比"遍历外接矩形再过滤"更短也更快；没有引入多余抽象。
- **没有留下没用的东西**：`git grep` 无 `TODO`/`FIXME`/注释掉的代码/未用 import（Checkstyle 的 `UnusedImports` 在门禁里）。
- **越界检查（构建期外我另跑了一遍）**：compile/test import 全部为 JDK + JUnit 5 + AssertJ；`simos-social`/`simos-unit`/`agentlib` **零命中**；`java.io`/`java.nio.file`/`Files`/`Path` **零命中**。
- **注释准确度**：绝大多数准确，**一处过度声称**（F2）与**两处接缝空格**（F3）。
- **不构成 finding 的两处，明确记下来免得被当成漏报**：① `hex` 包无 `package-info.java`——**与本仓惯例一致**（`git ls-files` 显示全仓只有 5 个顶层包的 `package-info`，M1 的 `util.address`/`util.time` 等子包同样没有）；② `HexGrid.cells()` 直接返回字段而非再拷贝一份——**是安全的**（`Set.copyOf` 的返回值按 JDK 规范不可变），且 M6 已证明构造期那道拷贝是承重的，不必再叠一层。

---

## 3. 判别力核验（★ 本项目的核心判据）

### 3.0 变异纪律与自证

**实验室**：`/tmp/hexrev`（`git archive HEAD | tar -x` 出来的干净副本）。参照件 `ref/*.java` 经 md5 核对**与工作树逐字节相同**：

```
ref/HexCoord.java        0254e25bafbd136edd9e74012fee5cd9   == worktree ✅
ref/HexDirection.java    fd804a33272adcde70d5dbaa1ed00dcb   == worktree ✅
ref/HexGrid.java         1261f98eb7c1af340bc713cbb61118d2   == worktree ✅
```

**每一条都按"先写盘 → 比 md5 → 再跑测试"的顺序做**，且在**装入源码目录后再核一次** md5：

```
  installed_md5=<X>
  mutant_md5   =<X>        ← 两者相等，证明跑的那份就是刚造出来的那份
```

**每次跑前 `rm -rf simos-map/target`**（强制 javac 重编），跑后打印三个 `.class` 的 md5。
**每条跑完都 grep `COMPILATION ERROR` 计数，必须是 0**——见 §3.3 我踩的坑。

**基线**（三文件 == ref）：

```
### tag=base-final  maven_exit=0
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
65e11c69cfccf896787e0afc4e465dac  simos-map/target/classes/io/mosire/simos/map/hex/HexCoord.class
605a4bc3c2ef8a43dce62a8a81d1364b  simos-map/target/classes/io/mosire/simos/map/hex/HexDirection.class
3c5c7d1f4050e0af7e8ca7f84ed93c14  simos-map/target/classes/io/mosire/simos/map/hex/HexGrid.class
```

三个 `.class` md5 与报告 §3 的 `base-final` 行**逐字节相同**。

### 3.1 我实跑的 12 个变异体总表

| # | 变异内容 | md5(变异体) vs md5(ref) | 产出的 `.class` md5（★=变） | exit | Tests | 红的断言（原始） | 判定 |
|---|---|---|---|---|---|---|---|
| M1 | `opposite()` `+3`→`+0` | `b96e8330…` vs `fd804a33…` **异** ✅ | ★`HexDirection=ab78bb4b…` | 1 | 21/2 | `oppositeIsNotSelf:24 [E 的反向]`、`neighborIsInvolutive:108` | **红 ✅** |
| M2 | 冻结表 `SE`/`SW` 偏移对调 | `da7cb8bf…` vs `fd804a33…` **异** ✅ | ★`HexDirection=d11623a6…` | 1 | 21/2 | `offsetsMatchFrozenTable:57 [索引 1（SE）的 dq] expected: 0 but was: -1` | **红 ✅** |
| M3 | `distanceTo` 删 `/2` | `dc0bc0db…` vs `0254e25b…` **异** ✅ | ★`HexCoord=02ad3e73…` | 1 | 21/5 | `distanceMatchesCubeFormula:87`、`distanceOnKnownPairs:97`、`neighborsAreSixDistinctAtDistance1:121`、`everyNeighborIsAtDistanceOne:75`、`cellsWithinRadiusAllInRange:48` | **红 ✅** |
| M4 | `neighbors()` 只返回 5 个（我的写法） | `caf7180d…` vs `0254e25b…` **异** ✅ | ★`HexCoord=ac4396d0…` | 1 | 21/1 | `neighborsAreSixDistinctAtDistance1:118` `Expected size: 6 but was: 5 in: [3_-3, 2_-2, 1_-2, 1_-3, 2_-4]` | **红 ✅** |
| M4′ | 同上，**用报告自己的 mutants/ 文件** | `c1891199…` vs `0254e25b…` **异** ✅ | ★`HexCoord=f42ce1fa…` | 1 | 21/1 | 与上**逐字相同** | **红 ✅**（并交叉印证报告日志） |
| M5 | 删 `parse` 的显式判空 | `3cb4ad50…` vs `0254e25b…` **异** ✅ | ★`HexCoord=75d5fe76…` | 1 | 21/1 | `parseRejectsMalformed:46`：`Expecting actual throwable to be an instance of: java.lang.IllegalArgumentException / but was: java.lang.NullPointerException: Cannot invoke "String.indexOf(int)" because "text" is null`，栈顶 `at …HexCoord.parse(HexCoord.java:60)` | **红 ✅** |
| M6 | 构造期 `Set.copyOf` → 直接持有入参 | `8872b8a3…` vs `1261f98e…` **异** ✅ | ★`HexGrid=0bd7d54e…` | 1 | 21/2 | `mutatingTheSourceSetDoesNotAffectTheGrid:64`（`Expecting actual: [9_9] to contain exactly … [0_0]`）、`returnedSetsAreUnmodifiable:78`（`…"Collection.add(null)" succeeded`） | **红 ✅** |
| M7 | `withinRadius` 返回 `ball` 而非 `Set.copyOf(ball)` | `68073efa…` vs `1261f98e…` **异** ✅ | ★`HexGrid=668be525…` | 1 | 21/1 | `returnedSetsAreUnmodifiable:79` | **红 ✅** |
| **M8** | **删 `parse` 的形状检查**（控制器指定） | `e8cb5d75…` vs `0254e25b…` **异** ✅ | ★`HexCoord=62a9932d…` | 1 | 21/1 | 见 §3.2 | **红 ✅** |
| M9 | **`s()` 写成 `q + r`**（我造） | `ec6baa01…` vs `0254e25b…` **异** ✅ | ★`HexCoord=6a049a08…` | 1 | 21/1 | **只翻** `sAxisInvariant:54`；`distanceMatchesCubeFormula` **不翻** | 红，但暴露 F2 |
| **M10** | **`neighbors()` 反序**（我造） | `793ff283…` vs `0254e25b…` **异** ✅ | ★`HexCoord=4102d048…` | **0** | **21/0** | **无** | **★ 存活** |
| **M11** | **`next()`/`prev()` 真交换**（我造） | `b245d553…` vs `fd804a33…` **异** ✅ | ★`HexDirection=46bcae51…` | **0** | **21/0** | **无** | **★ 存活** |

"★=变"列每次**只有被改文件的 `.class` md5 变化、另两个 == 基线**——这是"变异真进了字节码"的正面证据。

### 3.2 ★ M8 实跑结果（控制器指定，报告里没有这一条）

变异内容（装入 `HexCoord.java`，md5 已自证 `e8cb5d75cf90b5999192928189470fdf` ≠ ref `0254e25b…`）：

```diff
     int i = text.indexOf('_');
-    if (i <= 0 || i == text.length() - 1) {
-      throw new IllegalArgumentException("非法坐标串: " + text);
-    }
     return new HexCoord(
```

**结果：红了。** 原始输出：

```
### tag=M8 maven_exit=1
[ERROR] io.mosire.simos.map.hex.HexCoordTest.parseRejectsMalformed -- Time elapsed: 0.006 s <<< FAILURE!
java.lang.AssertionError:

Expecting actual throwable to be an instance of:
  java.lang.IllegalArgumentException
but was:
  java.lang.StringIndexOutOfBoundsException: Range [0, -1) out of bounds for length 0
	at io.mosire.simos.map.hex.HexCoordTest.parseRejectsMalformed(HexCoordTest.java:41)

[ERROR] Tests run: 21, Failures: 1, Errors: 0, Skipped: 0
```

**红的理由（按要求逐项问过）**：
1. **是断言失败，不是编译错误**（`java.lang.AssertionError`；同一轮 `grep -c "COMPILATION ERROR"` = 0）。
2. **红的是被保护的那行本身**：`HexCoordTest.java:41` 就是 `assertThatThrownBy(() -> HexCoord.parse(""))…`——`""` 那条，正是形状检查挡的输入。
3. **红的机制是"异常类型变了"**：`isInstanceOf(IllegalArgumentException.class)` 比的是**类型**，实际类型 `StringIndexOutOfBoundsException` 不是 `IAE` 的子类，所以断言翻。
   **不是消息匹配**——这一点很关键：本条的断言是 `isInstanceOf`，不是 `hasMessage`，所以**不落入形态 2 的陷阱**（不存在"NPE 消息里也含字段名"那种假判别力）。

**⇒ 控制器的实测表判对了**：`""` 会翻（SIOOBE ⊄ IAE），`"_"`/`"1_"`/`"_2"` 不会翻（`NFE` 是 `IAE` 子类）。
我**另外单独核了 `"12"` 这一行**——它不在用例里，故我没法用变异证明；但 `i = -1` 时 `substring(0, -1)` 先于任何 `parseInt` 抛出，
与 `""` 同一条路径，故控制器把它列进"会翻"是**可推导且与 `""` 同源**的。这一条我**只核到"同源"为止，没有单独实跑**（见 §7）。

### 3.3 ★ 我自己踩的坑（如实报，与报告 §4 / R-T1-g 同族）

第一版驱动脚本用 `sed 's/^m[0-9]*-[a-z-]*\.//'` 从变异文件名推目标名——**`[a-z-]*` 不匹配数字**，
于是 `m1-opposite-plus0.HexDirection.java`（slug 含 `0`）与 `m3-drop-div2.HexCoord.java`（含 `2`）**没被剥掉前缀**，
按变异文件名拷进了源码目录，javac 报：

```
[ERROR] COMPILATION ERROR :
[ERROR] …/hex/m1-opposite-plus0.HexDirection.java:[13,8] enum HexDirection is public,
        should be declared in a file named HexDirection.java
### tag=M1 maven_exit=1        ← 看起来"红了"
```

**"exit=1" 与"断言失败"是两件事。** 更阴的是：残留文件不会被"还原三个规范名"的循环清掉，
于是**污染了后续两轮**——M3、M4 的日志里都同时出现 `m1-opposite-plus0…` 的编译错误。
我是靠 **`grep -c "COMPILATION ERROR"` 不为 0** 才发现的（那时 M1/M3/M4 的"失败清单"是**空的**，
因为压根没跑到断言）。修法：按扩展名/白名单推目标名 + 每轮清理任何非规范文件名的 `.java` + 每轮强制断言"编译错误数 == 0"。
**重跑后 M1/M3/M4 才是真红**（失败清单非空、逐条是断言失败）。
⇒ 这与报告 §4 末尾自报的坑、与控制器的 R-T1-g **是同一个形态的第 4 个实例**，建议一并进 CLAUDE.md。

### 3.4 报告 §3/§4 的 7 条声称：独立复现结果

**我复现了报告的全部 7 个变异体**（M1~M7），结论：**报告的 §3 表与 §4 表均属实**。

- **失败清单**：7/7 与报告 §3 表**逐条吻合**（含 `Expected size: 6 but was: 5 in: [3_-3, 2_-2, 1_-2, 1_-3, 2_-4]` 这种细节）。
- **`Tests run` 计数**：M1=21/2、M2=21/2、M3=21/5、M4=21/1、M5=21/1、M6=21/2、M7=21/1 —— 与报告 §3 完全一致。
- **`.class` md5 交叉核对**：M1/M2/M3/M5/M6/M7 的产出 `.class` md5 与报告日志（`.superpowers/…/task-1-evidence/log-*.txt`）**逐字节相同**；
  **只有 M4 不同**（我的 `ac4396d0…` vs 报告 `f42ce1fa…`）——原因是**变异写法等价但落点不同**（报告 `.stream().limit(5).map(...)`，我 `.stream().map(...).limit(5)`）。
  **我把报告自己的 `mutants/m4-neighbors-five.HexCoord.java` 装进我的实验室重跑**，得到 `f42ce1fa801f96a18661642045d6a900` 与**逐字相同的失败清单** ⇒ 报告的 M4 行**完全属实**。
- **`mutants/` 的 7 个文件 md5**：与报告 §4 表**逐个相同**。**其中 6 个与我独立造的变异体 md5 逐字节相同**（只有 M4 因落点不同而异，见上）。
- **证据集自洽性（我按"污染检查"的判据逐份查过）**：8 份 `log-*.txt` **每一份都只变了一个 `.class` md5**，另两个 == 基线。
  **没有任何一份出现我 §3.3 那种污染**。`ref/` 三文件与工作树原件逐字节相同（报告 §6 的 6 个 md5 我也核过，全中）。

---

## 4. 报告的 §6 md5 声称（"跑过的字节 == 提交的字节"）

我独立算的与报告 §6 表**6/6 相同**：

| 文件 | 我算的 md5 | 报告 §6 | |
|---|---|---|---|
| `HexCoord.java` | `0254e25bafbd136edd9e74012fee5cd9` | 同 | ✅ |
| `HexDirection.java` | `fd804a33272adcde70d5dbaa1ed00dcb` | 同 | ✅ |
| `HexGrid.java` | `1261f98eb7c1af340bc713cbb61118d2` | 同 | ✅ |
| `HexCoordTest.java` | `de9c4e8eceb1f05dd64bd7e35ac0d775` | 同 | ✅ |
| `HexDirectionTest.java` | `37174694446fe8fa76aaac47f0662601` | 同 | ✅ |
| `HexGridTest.java` | `b5caea4373beebfaaf4a4f3b57a6c831` | 同 | ✅ |

且工作树 == `git show HEAD:<path>`，故"跑过的字节 == 提交的字节"这句**成立**。

---

## 5. Finding 清单

> 控制器的已裁定项（§6 那张表）**不计入**本清单。

### F1 —— **Important**：A 序的"序"语义（`next`/`prev` 朝向、`neighbors` 顺序）**没有任何用例钉住，两个变异体存活**

**文件:行**：`simos-map/src/main/java/io/mosire/simos/map/hex/HexCoord.java:34-36`（`neighbors()`）、
`simos-map/src/main/java/io/mosire/simos/map/hex/HexDirection.java:42-49`（`next()`/`prev()`）

**证据（两个实跑，均 exit=0、21/21 全绿）**：

- **M10**：`neighbors()` 改成 `HexDirection.ALL.reversed().stream()…` ⇒ `maven_exit=0`、`Tests run: 21, Failures: 0`、`BUILD SUCCESS`，
  而 `HexCoord.class` md5 由 `65e11c69…` 变为 `4102d048…`（**变异确实编译进去了**）。
  这三个测试类**都真的跑了**（不是"没跑到"）：日志里三个 `Running io.mosire…` 与三条 `Tests run` 都在。
- **M11**：`next()` 的 `+1` 与 `prev()` 的 `+5` **真交换**（两处，非误改成同值）⇒ `maven_exit=0`、`Tests run: 21, Failures: 0`、`BUILD SUCCESS`，
  `HexDirection.class` md5 由 `605a4bc3…` 变为 `46bcae51…`。

**为什么现有着不翻**：`HexDirectionTest.nextAndPrevAreInverse:29` 的两条断言（`d.next().prev()==d`、`d.prev().next()==d`）
在"整体交换"下**是对称的**，必然同时成立；`offsetsMatchFrozenTable` 只冻结**名字↔偏移**，不冻结由枚举序派生的**朝向**。
`HexCoordTest.neighborsAreSixDistinctAtDistance1:115` 只查 size/互异/距离，**不查顺序**。
（**旁证**：我第一版 M11 因脚本 bug 变成了 `next()` 与 `prev()` **同值**，那一次**是红的**——`nextAndPrevAreInverse:31 expected: E but was: SW`。
可见该用例能抓"next==prev"，**抓不住"整体反过来"**。这恰好是"红了要问为什么红"的镜像：它红的理由不是我想要的那条。）

**为什么算 Important 而不是 Minor**：本任务存在的**全部理由**就是把 8 份方向表收成**唯一一份**，而"哪条边是第几号、顺时针是哪一边"
正是这份表的语义锚。spec §3.2 与 brief Step 2 都明写"索引序固定为 A 序（顺时针）"、"不要选 B 序"。
今天 `next()`/`prev()` **尚无消费方**，所以它**不是现成的 bug，而是一道已经失效的护栏**——
与本项目 M1 期间"compareTo-而非-equals 未被钉住"那条据同一判据（项目要立的语义之一没被钉住）。

**建议动作**：补两条**冻结序**断言（各约 3 行，只加测试、不动 main）：
1. `HexDirectionTest`：∀d 断言 `d.next() == ALL.get((d.ordinal()+1)%6)` 且 `d.prev() == ALL.get((d.ordinal()+5)%6)`；
   **或**更贴语义地 freeze 一整张 6 行 `(d, next, prev)` 表（照 `offsetsMatchFrozenTable` 的形制）。
2. `HexCoordTest`：断言 `center.neighbors()` **逐个等于** `HexDirection.ALL.stream().map(center::neighbor).toList()`（有序比对，非 `containsExactlyInAnyOrder`）。
修完请各配一条变异自证（即 M10/M11 必须转红）。

### F2 —— **Minor**：`distanceMatchesCubeFormula` 的 Javadoc **过度声称**，举的例子恰恰是它抓不住的

**文件:行**：`simos-map/src/test/java/io/mosire/simos/map/hex/HexCoordTest.java:76-78`（注释）与 `:151-156`（helper）

注释原文：「两种形式代数恒等但写法不同源——**同源错误（比如把 `s()` 写成 `q + r`）因此会被抓住**」。

**证据（M9 实跑）**：把 `s()` 写成 `q + r` ⇒ `Tests run: 21, Failures: 1`，**唯一红的**是

```
[ERROR]   HexCoordTest.sAxisInvariant:54 [-20_-20 的 cube 恒等式]
expected: 0
 but was: -80
```

`distanceMatchesCubeFormula` **一条都没翻**。

**原因（实测 + 推导，两者一致）**：对拍两侧**都调用 `s()`**；且 `s()` 整体变号时，差 `s_a - s_b` 同时变号 ⇒ `|s_a - s_b|` **完全不变**，
故 `distanceTo` 与 helper 在这种变异下的输出**逐点相同**。（更一般地，对 `z = x + y` 恒有 `max(|x|,|y|,|z|) == (|x|+|y|+|z|)/2`，
故该对拍对"用 `q+r` 还是 `-q-r` 当第三轴"**完全免疫**。）

⇒ 这条对拍**确实**抓 `distanceTo` 自身算式的错（M3 删 `/2` 时它红了），但**抓不住注释里举的那个例子**。**注释在替这道护栏作保，而它保不了。**

**建议动作**：把注释里的例子换成它真能抓的（如"丢 `/2`"），并**明写它的边界**："`s()` 的符号翻转由 `sAxisInvariant` 单独钉，本条对它免疫"。
**不要**靠改 helper 去补——我试算过，helper 就算内联展开 `-(q+r)` 也仍然免疫（见上），真正的守卫是 `sAxisInvariant`，而它已经存在。

### F3 —— **Minor**：两处中文"接缝空格"

**文件:行**：`simos-map/src/main/java/io/mosire/simos/map/hex/HexCoord.java:10`（"唯一两份 实现"）、
`simos-map/src/main/java/io/mosire/simos/map/hex/HexGrid.java:12`（"落地后 另行补入"）

**证据**：`grep -nP '[\p{Han}] [\p{Han}]'` 在两文件各命中 1 处（其余 4 个文件 0 处）。
`spotless:check` 抓不到（它是合法字符，不是格式问题）。

**建议动作**：删掉这两个空格。CLAUDE.md 已明写"写注释不要手工调行宽"，这是那条纪律的残留物。

### F4 —— **Minor**：`HexGrid` 的 null 入参抛 NPE，而同包的 `parse(null)` 按裁决抛 IAE，口径不一致

**文件:行**：`simos-map/src/main/java/io/mosire/simos/map/hex/HexGrid.java:19-22`（`of`）、`:52-54`（`contains`）、`:64`（`withinRadius`）

**证据（实测，用 `/tmp` 实验室里的探针用例跑的，原样输出）**：

```
PROBE of(null)            -> java.lang.NullPointerException: Cannot invoke "java.util.Collection.isEmpty()" because "coll" is null
PROBE contains(null)      -> java.lang.NullPointerException: null
PROBE withinRadius(null,0)-> java.lang.NullPointerException: Cannot invoke "io.mosire.simos.map.hex.HexCoord.q()" because "center" is null
```

（三处的 NPE 分别来自 `Set.copyOf`、`Set.contains`、`center.q()`，**没有一处是显式守卫**。）

**我不主张这是缺陷**——`parse(null)` 的 `IAE` 是控制器裁决 (b) 明确要求的，`HexGrid` 这边没有任何裁决要求对称。
但**同一包内"非法入参"的口径分成了两套**（一处显式 IAE、三处 JDK NPE），而本项目恰有"不静默夹取 / 构造期校验"的纪律。
**建议动作**：由控制器裁——要么认了这个不对称（那 F4 撤回，无需动作），要么按 `parse` 的先例给 `of`/`withinRadius` 补显式 `requireNonNull` 并配用例。

---

## 6. 待修复项（★ 控制器已裁定，**不计入 finding**，仅记落地状态）

| 裁定 | 我的独立核实 | 当前代码状态 |
|---|---|---|
| **R-T1-b**：`withinRadius` 负数半径**改成抛 `IllegalArgumentException`** | 已核：`HexGrid.java:64-74` 当前返回空集；`HexGridTest.java:30` 当前断言 `withinRadius(center, -1)).isEmpty()`；Javadoc `:61` 当前写"**radius 为负时返回空集**"。**三处都与裁定相反，待改** | ⬜ 待改（改 1 行 + 改 1 条断言 + 改 1 行 Javadoc） |
| **R-T1-d**：`round(double,double)` 归 Task 1 本轮补实现 | 已核（见 §6.1）| ⬜ 未实现 |
| **R-T1-a**：代码不改；更正报告 §7-1 + 补 M8 | 报告 §7-1 现文本**仍是更正前的版本**；报告里**没有 M8**。**M8 我已替它实跑，红了**（§3.2），控制器的表判对了 | ⬜ 文本与 M8 待补 |
| **R-T1-c / e / f / g** | 无代码动作；`git grep` 确认实现者**未手写 `equals`/`hashCode`** ✅ | ✅ 无需动作 |

### 6.1 ★ `round(double,double)` 的空隙：独立核实结论 —— **控制器判对了**

**（1）它在 `simos-map/src/` 里确实不存在**（两条命令、都带"搜了哪些文件"的证据）：

```bash
$ git ls-files simos-map/src/            # ← 先证明 git grep 的作用域包含全部 7 个源文件
simos-map/src/main/java/io/mosire/simos/map/hex/HexCoord.java
simos-map/src/main/java/io/mosire/simos/map/hex/HexDirection.java
simos-map/src/main/java/io/mosire/simos/map/hex/HexGrid.java
simos-map/src/main/java/io/mosire/simos/map/package-info.java
simos-map/src/test/java/io/mosire/simos/map/hex/HexCoordTest.java
simos-map/src/test/java/io/mosire/simos/map/hex/HexDirectionTest.java
simos-map/src/test/java/io/mosire/simos/map/hex/HexGridTest.java

$ git grep -n "round" -- simos-map/src/
$ echo $?
1                                        # ← 零命中

$ grep -rn --hidden --no-ignore-files --include=*.java "round" simos-map/src/
$ echo $?
1                                        # ← 换用"含未跟踪/隐藏"的 grep 复核，仍零命中
```

**我把作用域先证明了**（`git ls-files` 列出的 7 个文件含全部 hex 源文件，且它们**已入库**，
故 `git grep` 不会像报告 §7-8 那样因"文件未 add"而假阴性）。

**（2）spec 确实要求它**——`docs/superpowers/specs/2026-09-16-map-simos-design.md:182-189`（**§3.4「一份距离、一份取整」**）：

```
184: GSimulator 现状：距离函数 **4 处**（…），**公式代数恒等，无口径分歧**（侦察 A 实测）；`hexRound` **3 份逐字复制**。
187: ⇒ 裁决：`HexCoord.distanceTo(HexCoord)` 一个实现（cube 曼哈顿 `(|dq|+|dr|+|ds|)/2`）；
188: `HexCoord.round(double q, double r)` 一个实现。**前端不再有第二份**（…）。
```

**⇒ 两件事都成立：spec §3.4 要求 `round`，而 `simos-map/src/` 里没有。空隙确证。**

**★ 一处编号更正（不影响裁定）**：控制器在派单与台账 R-T1-d 里写的是「spec **§3.3** 的 `HexCoord.round`」，
但 §3.3 是「删 `gridSize` 与 `hexOrientation`」；要求 `round` 的是 **§3.4**。报告 §7-7 引的 §3.4 才是对的。
**裁定的实质（归 Task 1 补实现）不受影响**，只是引用编号需订正，免得后人按 §3.3 去翻。

---

## 7. 我未能核实的（宁可报"核不了"，也不报没跑过的结论）

1. **`parse("12")` 在无形状检查下会翻**——控制器实测表里有这一行，但 `"12"` **不在测试用例里**（用例只有 `""`/`"_"`/`"1_"`/`"_2"`/`"a_b"`/`null`），
   所以我**无法用变异证明它**。我只能说明它与 `""` **同源**（`i == -1` ⇒ `substring(0, -1)` 先于 `parseInt` 抛 SIOOBE）。
   **这一格我没有独立实跑，只有同源推导。**
2. **控制器实测表里我未逐格复跑的部分**：我只实跑了 `""` 那一格（M8 红的断言就是它）。
   `"_"`/`"1_"`/`"_2"`/`"a_b"` 四格"不会翻"我**没有单独造变异**去证——依据是 M8 只红了**一条** `parseRejectsMalformed:41`
   （若后四格也翻，`Tests run` 的 Failures 数会 > 1）。**这是"由失败计数反推"，不是逐格实跑。**
3. **报告的 §5 命令行自述**（如 `./mvnw -pl simos-map spotbugs:check` 因 `simos-util` 未安装而失败）：我**没有复现这条失败**。
   我只核了替代路径 `-pl simos-map -am verify` 确能给 `BugInstance size is 0`。
4. **`mvnw` 的 `clean verify` 全 reactor**：我没跑全量（brief 说不必）。我只跑了 `-pl simos-map -am verify`（含 UtilSimos 156 条 + MapSimos 21 条，全绿）。
   `simos-core`/`simos-social`/`simos-unit` 本轮**未触及**。
5. **报告 §3 说的"整套跑了两遍、两遍逐字节相同"**：我只能核到**落盘的那一遍**（evidence 目录里的 8 份日志自洽且与我复现的一致）。
   "跑了两遍"这句本身**不可核**——第一遍没有留下可核的痕迹（报告 §3 明说被作废那一轮只留在 `/tmp`，而 `/tmp` 我看不到）。
   **我没有把"他声称跑了两遍"当成已验证的事实。**
6. **`-pl simos-map -am verify` 的原始完整输出**：我引的是 `grep` 过滤后的行（命令与过滤式都在 §1.2 里给出）。
   未过滤的全文我没有落盘留存。

---

## 8. 轮次与结论

- 本轮为该任务的**第 1 轮任务级评审**（上限 3 轮）。
- **两个判决**：规格符合性 **符合**；代码质量 **良好**。
- **总判决：Needs fixes** —— 1 项 Important（F1：两处存活变异，本任务的核心语义锚没被钉住）+ 3 项 Minor（F2 注释过度声称 / F3 接缝空格 / F4 null 口径待裁），
  外加 §6 的两项控制器已裁定待修复项（R-T1-b 负半径改抛异常、R-T1-d 补 `round`）。
- **报告的可信度结论**：报告 §3/§4/§6 的**全部可核声称均属实**，包括它**主动自曝**的两处（§4 的编译错误坑、§7 的八条顾虑）。
  它没有夸大——唯一的偏差是**它没找到 F1 的那两个存活变异**，而那是"护栏有没有判别力"的问题，不是"它说错了"的问题。
