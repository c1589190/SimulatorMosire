# Task 3 报告：身份（`identity` 包）

- 分支：`feat/m1-util-simos`（就地工作，未建分支/worktree）
- 提交：`2fca1ab` feat(util): SubjectId/ResolvedSubject/QueryResult 身份三件（M1 Task 3）
- 状态：DONE
- 提交前门禁：`./mvnw -pl simos-util verify` → **BUILD SUCCESS**，71 tests / 0 failures（含 Spotless + Checkstyle + SpotBugs）

---

## 1. 实现了什么

三个 record（spec §四 → 代码），一个文件一个职责：

| 文件 | 内容 |
|---|---|
| `simos-util/src/main/java/io/mosire/simos/util/identity/SubjectId.java` | 稳定身份 `(namespace, localId)`；构造期拒空白。层级形态（`unit` vs `map.hex`）**不校验**（spec §四：由各模块自定） |
| `simos-util/src/main/java/io/mosire/simos/util/identity/ResolvedSubject.java` | 候选 `(id, canonicalAddress, typeName)`；构造期拒 null id、拒空白字段、拒**非 canonical 地址**（`Address.parse(x).canonical().equals(x)`） |
| `simos-util/src/main/java/io/mosire/simos/util/identity/QueryResult.java` | 候选列表 `List.copyOf` 防御拷贝（保序 + 不可变）；空列表 = 无候选，不是错误 |

与 brief 的代码逐字一致（唯一差异是 `spotless:apply` 的两处折行/并行动，见 §6）。**未写** `package-info.java`（address 包也没有，YAGNI）。

与下游的契合已核：Task 8 简报里的 `new QueryResult(List.of(new ResolvedSubject(new SubjectId(ns, id), address.canonical(), "Toy")))` 与本实现签名完全匹配，且 Task 8 传的正是 `address.canonical()`（不会踩 canonical 校验）。

## 2. 测试了什么、结果如何

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `SubjectIdTest` | 3 | 值语义（equals/hashCode/toString）、空白拒收、层级自由 |
| `ResolvedSubjectTest` | 4 | 三字段承载、值语义 + 空白拒收、**null id 拒收**、**非 canonical 地址拒收（消息含 `canonical`）** |
| `QueryResultTest` | 2 | 保序 + 空列表、**防御拷贝（可变源 + 清空后仍 1 条 + 不可 add）** |

结果：9/9 通过；`simos-util` 全量 71/71 通过。

## 3. TDD 证据

### RED（brief Step 2 原命令）

```
./mvnw -q -pl simos-util -Dtest='SubjectIdTest,ResolvedSubjectTest,QueryResultTest' test   # exit=1
[ERROR] COMPILATION ERROR :
[ERROR] /root/SimulatorMosire/simos-util/src/test/java/io/mosire/simos/util/identity/SubjectIdTest.java:[14,20] cannot find symbol
[ERROR]   symbol:   class SubjectId
[ERROR] /root/SimulatorMosire/simos-util/src/test/java/io/mosire/simos/util/identity/QueryResultTest.java:[32,18] cannot find symbol
[ERROR]   symbol:   class ResolvedSubject
[ERROR] Failed to execute goal ...maven-compiler-plugin:3.16.0:testCompile ... Compilation failure
```

为何预期失败：三个被测类型尚不存在，测试源无法编译（brief Step 2 预期即"编译失败 —— cannot find symbol"）。此时生产代码目录 `identity/` 为空。

### GREEN（同一命令）

```
./mvnw -q -pl simos-util -Dtest='SubjectIdTest,ResolvedSubjectTest,QueryResultTest' test   # exit=0，无输出（-q）
确认（surefire）：
  QueryResultTest     Tests run: 2, Failures: 0, Errors: 0
  ResolvedSubjectTest Tests run: 4, Failures: 0, Errors: 0
  SubjectIdTest       Tests run: 3, Failures: 0, Errors: 0
```

## 4. G13 变异实验（全部为真实删改 + 真实运行，非纸面推断）

方法：`/tmp/g13/mutate.py` 精确串删（锚点必须命中且唯一，否则报错退出），随后跑上面那条测试命令，读 surefire 结果；每条实验后从**变异前快照**恢复并 `cmp`。
**所有实验在 `spotless:apply` 之后的最终字节上重跑过一遍**（快照 sha256：`SubjectId.java 87967adc…`、`ResolvedSubject.java c7fe6b86…`、`QueryResult.java 4aa61c21…`）。

| # | 删/改哪一行生产代码 | 转红用例 | 转红证据（原始输出） | 恢复 |
|---|---|---|---|---|
| M1 | `SubjectId` 的 `if (namespace == null \|\| namespace.isBlank()) {...}` 整块 | `SubjectIdTest.blankPartsAreRejected`（:21） | exit=1；`Expecting code to raise a throwable.`（`Tests run: 3, Failures: 1`） | cmp OK |
| M2 | `SubjectId` 的 `if (localId == null \|\| localId.isBlank())` 整块 | `SubjectIdTest.blankPartsAreRejected`（:23） | exit=1；同上（Failures: 1） | cmp OK |
| M3 | `ResolvedSubject` 的 `if (id == null) {...}` 整块 | `ResolvedSubjectTest.nullIdIsRejected`（:31） | exit=1；`Expecting code to raise a throwable.` | cmp OK |
| M4 | `ResolvedSubject` 的 `if (canonicalAddress == null \|\| canonicalAddress.isBlank())` 整块 | `ResolvedSubjectTest.valueSemantics`（:24） | exit=1；`Expecting throwable message: ... to contain: "canonicalAddress" but was: "地址不得为空"` | cmp OK |
| M5 | 中和 canonical 校验：条件改成 `…equals(Address.parse(canonicalAddress).canonical())`（恒假，保留 import 被使用） | `ResolvedSubjectTest.nonCanonicalAddressIsRejected`（:38） | exit=1；`Expecting code to raise a throwable.` | cmp OK |
| M6 | `ResolvedSubject` 的 `if (typeName == null \|\| typeName.isBlank())` 整块 | `ResolvedSubjectTest.valueSemantics`（:24 的第二处） | exit=1；`Expecting code to raise a throwable.` | cmp OK |
| M7 | `QueryResult` 的 `candidates = List.copyOf(candidates);` | `QueryResultTest.candidatesAreDefensivelyCopied`（:27） | exit=1；`Expected size: 1 but was: 0 in:` | cmp OK |

每次变异只让**目标用例**转红，其余用例保持绿（说明失败由该行引起，非连带）。
最终恢复确认：三条生产文件与快照 `cmp` 逐字节一致，sha256 相同；提交后
`git status --short` 为空、`git diff HEAD -- <identity 路径>` 为空、`git show HEAD:<file> | sha256sum` 与工作树一致 → **工作树 == HEAD == 变异前快照**。

### M4 首轮是空转护栏（本任务实际抓到的一个缺陷）

第一次删掉 `canonicalAddress` 空白校验时，**9/9 全绿、exit=0** —— 因为 `Address.parse` 对 `null`/空串自己就抛 `IllegalArgumentException`（`AddressParser.parse` 首行），异常类型无从判别，brief 原用例只断"异常类型"，判据落在**别的代码**上，符合"空转护栏"的定义。

处置（就地修判别性，不动 brief 的生产代码）：该行的**唯一可见差别是消息**——本层抛 `"ResolvedSubject.canonicalAddress 不得为空白"`，删掉后变成 `"地址不得为空"`。故把 `valueSemantics` 里那条断言从 `isInstanceOf(IAE)` 补成 `isInstanceOf(IAE).hasMessageContaining("canonicalAddress")`（house style：brief 自己的 `nonCanonicalAddressIsRejected` 已用 `hasMessageContaining("canonical")`）。改后 M4 稳定转红（证据见上表）。
**给控制器的观察**：这条 guard 对"异常类型"是行为冗余的（parse 已兜住），值域仅剩"字段级错误消息"。若认为不值一行，可裁决删除该 guard——删除后必须同步把该断言的 `hasMessageContaining` 去掉；两种取舍都不影响其他用例。

### 与 G13 提示的对照

`QueryResultTest.candidatesAreDefensivelyCopied` 用的是 `new ArrayList<>()` + `clear()`，**不是** `List.of` —— 若用 `List.of` 做源，`candidates()` 的不可变性与拷贝都来自 JDK 而非被测代码，用例必空转。M7 的 `Expected size: 1 but was: 0` 正是拷贝缺失的实证（清空透了过去），额外那条 `UnsupportedOperationException` 也同时失守。

## 5. 改动文件清单（提交前 `git diff --cached` 已逐行审过；`git add` 逐个路径，未用 `-A`）

新增 6 个，179 行，全部在 `identity` 包内：

```
simos-util/src/main/java/io/mosire/simos/util/identity/SubjectId.java
simos-util/src/main/java/io/mosire/simos/util/identity/ResolvedSubject.java
simos-util/src/main/java/io/mosire/simos/util/identity/QueryResult.java
simos-util/src/test/java/io/mosire/simos/util/identity/SubjectIdTest.java
simos-util/src/test/java/io/mosire/simos/util/identity/ResolvedSubjectTest.java
simos-util/src/test/java/io/mosire/simos/util/identity/QueryResultTest.java
```

未推送。仓库根那个 `2026-09-16-012332-gsimulator.txt` 未触碰（已被 `.gitignore:16` 覆盖）。

## 6. 与 brief / spec 的偏差（两处，均为"补判别性"，无 spec 冲突）

1. **新增 `ResolvedSubjectTest.nullIdIsRejected`**（brief 的测试清单里没有）。brief 的生产代码有 `id == null` 校验，但自带测试不覆盖它 —— 不补则 G13 下它是空转护栏（M3 已实测：该用例是让这行转红的**唯一**判据）。补用例比删 brief 的校验行更保守，故补。
2. **`valueSemantics` 的一条断言加 `hasMessageContaining("canonicalAddress")`**（理由见 §4 的 M4）。
3. 非语义差异：`spotless:apply` 把 brief 的两处折行改掉了 —— `SubjectId` 的 Javadoc 按字符宽重排（CLAUDE.md 已明文：中文 Javadoc 折行由 google-java-format 决定），`ResolvedSubject` 的 `throw new IllegalArgumentException("canonicalAddress 必须是 canonical 形式：" + …)` 并回一行（88 字符 < 100）。这是 brief Step 5 要求的动作，不算偏差。

**无 spec 冲突**：spec §四给的是裸 record 形状，brief 在其上加构造期校验（拒空白 / 拒非 canonical），不改变 spec 明文的语义（"Util 不校验命名层级"仍成立——只拒空白，不拒形态；"调用方选定后回传 canonical"正是被强制的方向）。

## 7. 自审发现

- 命名/职责：三个类型各自一文件一职责，未超 brief 意图；无 YAGNI 越界（没加 `SubjectId.parse`、没加 `QueryResult` 便捷查询方法）。
- 交叉核对下游：Task 8 简报的 `Resolver{namespace(), resolve(Address, ResolveContext)}` 与 `ResolverRegistry` 对本包的用法已逐处对上（见 §1）。
- 边界情形复核：`ResolvedSubject` 的校验顺序是 id → 地址空白 → canonical → 类型名。`"map:Map1"` 是合法 canonical（不会被 canonical 校验误伤，M6 因此能单测到 typeName 行）；畸形地址（如 `"不是地址"`）由 `Address.parse` 抛 IAE，与构造器契约一致。
- `QueryResult(null)` 会由 `List.copyOf` 抛 NPE（非 IAE）—— brief 未要求 guard，`Address` 也是同一处置，未加。
- 测试输出干净：无 stdout 噪音、无 `@Disabled`、无用例间共享可变状态。
- 未跑全仓 `./mvnw verify`（仅 `-pl simos-util`）：本任务只碰 `simos-util`，且 util 是被依赖方、不依赖其他模块，控制器给的门禁命令即 `-pl simos-util verify`。

## 8. 顾虑 / 给控制器的裁决点

1. **M4 那条 guard 的取舍**（§4 末）：它只承载字段级消息，不改变异常类型。保留（现状，已自证）+ 消息判据，还是删除该行并回退断言？两种都"绿"，现状更保守。
2. 顺手发现（**非本任务引入，未改**）：`verify` 打印 `Parameter 'fork' is unknown for plugin 'spotbugs-maven-plugin:4.10.4.1:check'`（父 POM 的 `<fork>true</fork>`/`maxHeap`/`timeout` 三项在 4.10.x 的 `check` goal 上不被识别）。它意味着父 POM 注释里"防 60s 超时"的那套配置可能并未生效；本机 `simos-util` 的 SpotBugs 实测能过，故未在本任务处理。若要处理，属父 POM 的门禁配置，建议单开一个修复项。
