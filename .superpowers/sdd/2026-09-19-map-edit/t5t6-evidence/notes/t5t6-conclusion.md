# M8 T5 / T6 结论台账

> 范围：`map.SetEdge`（T5）与 `map.RandomizeRegion`（T6）的**领域操作 + SPI 边界 + 端到端**三段护栏，
> 以及它们各自的**变异自证**。
> 本文件记**裁定与结论**；取证装置、逐轮日志、探针源码在 `../mutants/`、`../logs/`、`../probe/`。

---

## 一、判据与结论

| # | 判据 | 结论 | 依据 |
|---|---|---|---|
| T5-1 | `replace`/`merge` **语义显式、无默认值** | ✅ | `SetEdgeHandler` 用 `MapPayloads.requireText(payload, "mode")`；缺字段 ⇒ `Rejected`（m1 杀点） |
| T5-2 | ★ **`merge` 不丢既有 tag**（M2 挂起项） | ✅ | `EdgeOperations` 按 kind `putIfAbsent`；**四处**护栏（见 §三） |
| T5-3 | `replace` 覆盖**整张图**该 kind、别的 kind 一字不动 | ✅ | `EdgeOperationsTest.replaceOverwritesTheKindAcrossTheWholeMapAndKeepsOtherKinds` + e2e |
| T5-4 | 端到端（真 store / 真 checkpoint / 真 replay） | ✅ | `MapSetEdgeEndToEndTest` 4/4 |
| T6-1 | 同 seed **逐字节相同** | ✅ | `sameSeedRebuildsByteIdenticalBlocks` + e2e `sameSeedIsByteIdenticalAcrossTwoRuns` |
| T6-2 | 不同 seed **直方图不同** | ✅ | `differentSeedGivesADifferentHistogram`（单元 + e2e） |
| T6-3 | ★ **选区/载荷的迭代序不进结果** | ✅ | `RegionRandomizer` 入口 `hexes.stream().sorted()`；两条新用例（m4 杀点） |
| T6-4 | 只改地形、**不动高度**、选区外一格不动 | ✅ | `onlyTheSelectionChangesAndHeightsSurvive` + `onlyTheTerrainBlocksComponentChanges` |
| T6-5 | 空选区 / 图外格 fail-closed | ✅ | 单元 + handler + e2e 三层负例（e2e 负例**不留 revision**） |
| T6-6 | **种子表逐值冻结** | ✅ | `seedTableIsFrozenPerValue`（8 seed × 直方图 + 块数）、`seedSevenBlockTableIsFrozenPerValue`（23 个字面 `BlockId`） |

---

## 二、种子表的出处（形态 5：「我验过了」与「我记得」分开）

表里的每一个数**不是手算、不是推导**，是 `../probe/SeedTableProbe.java` 在**当前字节**上跑出来的。
探针与 `RandomizeOperationsTest` 的夹具**完全同形**（半径 10、331 格、初值 `mountains`、选区 326 格）。

```
seed=0 desert=163 mountains=5 plains=163 blocks=24
seed=1 desert=161 mountains=5 plains=165 blocks=25
seed=2 desert=151 mountains=5 plains=175 blocks=24
seed=3 desert=159 mountains=5 plains=167 blocks=21
seed=4 desert=172 mountains=5 plains=154 blocks=27
seed=5 desert=161 mountains=5 plains=165 blocks=28
seed=7 desert=164 mountains=5 plains=162 blocks=23
seed=8 desert=171 mountains=5 plains=155 blocks=28
selectionSize=326 totalHexes=331
```

classpath 见 `../probe/cp-map.txt`（`simos-map/target/classes` + `simos-util/target/classes` + Jackson 家族 + slf4j-api）。

### ★ 为什么表里要同时钉**直方图**与**块数**

**实测**：`seed=1` 与 `seed=5` 的直方图**完全相同**（`{desert=161, mountains=5, plains=165}`），只有块数分得开（25 vs 28）。

⇒ 只钉直方图的表，在 `(1,5)` 这一对上**不判别**"忽略 seed"这类变异。
`seedOneAndFiveShareAHistogram` 把这条设计选择**当场自证**（并且它自己也是一个杀点，见 §三）。

---

## 三、变异轮（四轮，逐条自证）

装置 `../mutants/mut-run.sh`（v4），九道门禁：干净世界基线绿 → 变异体字节不同 → 白名单推成目标类名并清陈旧 `.class`
→ `COMPILATION ERROR=0` 且 `Tests run≥1` → surefire mtime 落本轮 → 红点落**被保护断言** → `cp` 逐字节还原（绝不
`git checkout --`）→ **日志自指**（把本轮 md5 追加进日志本身，先断言非空）。

### 最终杀点表（四轮，全部在**改动后**的字节上重跑）

| 轮 | 变异体 → 目标类 | `orig_md5` | `mutant_md5` | hits | 红点（surefire 原文，逐字） |
|---|---|---|---|---|---|
| t5-m1 | `m1.SetEdgeHandler` → `spi/SetEdgeHandler` | `ac8a4eba2962aa8374074842d961860f` | `b74391285a861e4c8d40e4f9a049f2ad` | 2 | `io.mosire.simos.map.spi.SetEdgeHandlerTest.rejectsPayloadWithoutMode`<br>`io.mosire.simos.core.MapSetEdgeEndToEndTest.negativePayloadsAreRejectedAndLeaveNoRevision` |
| t5-m2 | `m2.EdgeOperations` → `ops/EdgeOperations` | `4c717d2d0ac171bf991accfe436a084a` | `0b7c5bca755b51980f4f7fd271ebc218` | **4** | ★ `io.mosire.simos.map.ops.EdgeOperationsTest.mergeAddsTheNewTagAndKeepsEveryExistingTag`<br>★ `io.mosire.simos.core.MapSetEdgeEndToEndTest.mergeSurvivesTheRealCommandPathAndKeepsTheExistingTag`<br>`io.mosire.simos.map.ops.EdgeOperationsTest.mergeLeavesUntargetedEdgesUntouched`<br>`io.mosire.simos.map.ops.EdgeOperationsTest.mergeKeepsExistingPropertiesOfTheSameKind` |
| t6-m1 | `m3.RandomizeOperations` → `ops/RandomizeOperations` | `f1794e68eab49a4126efa0bc5919d1db` | `b1871b26c1d6a9174826299823a71e96` | 4 | `io.mosire.simos.map.ops.RandomizeOperationsTest.seedTableIsFrozenPerValue`<br>`…seedSevenBlockTableIsFrozenPerValue`<br>`…differentSeedGivesADifferentHistogram`<br>`io.mosire.simos.core.MapRandomizeEndToEndTest.differentSeedGivesADifferentHistogram(Path)` |
| t6-m2 | `m4.RegionRandomizer` → `generate/RegionRandomizer` | `023a6f652ba24650c22b5586c2626d65` | `22e7128e0c010a2cbe0ad3e7575f61f9` | 2 | `io.mosire.simos.map.ops.RandomizeOperationsTest.selectionIterationOrderDoesNotLeakIntoTheResult`<br>`io.mosire.simos.map.spi.RandomizeRegionHandlerTest.payloadArrayOrderDoesNotLeakIntoTheChangeSet` |

四轮共同项（逐轮实测，非"同上"）：`clean_rc=0`、`clean_compilation_error=0`、`clean_…Tests run: 8`
（干净基线**绿**）；`mut_rc=0`、`mut_compilation_error=0`、`mut_…Tests run: 8`；`class_removed=<目标类>.class`
（陈旧 `.class` 已清）；`restored_md5 == ORIG_MD5`。

### ★ 这张表**预期在先、实测在后**（不是事后解释）

改夹具之前我先把预测写死：改动前 t5-m2 在 5 个 expect 名字里**只杀中 2 个**；若修法真把两条 ★ 用例挪到分叉点上，
**hits 必须升到 4 且失败原文里必须同时出现那两条方法名**。实测 **hits=4**，两条都在 ⇒ 修法生效。
**若仍为 2 或原文缺这两条，本轮结论就不是"已修"而是"修法没生效"。**

### 两处口径，如实标注

1. **`hits` 不是"红了几个方法"**。它数的是「命中白名单 `expect` 正则 ∧ 带 `<<< FAILURE`」的**行数**。
   实测 t6-m1 的红点是 **5 个方法**（比 hits 多一个 `RandomizeOperationsTest.seedOneAndFiveShareAHistogram`），
   多的那个**被杀但不在当轮白名单里**，故不计入 —— 表里的 4 是**下界**，不是"只有 4 个被杀"。
2. **`self_md5` 不是日志自身的摘要**。装置第 124 行是 `grep -oE 'mutant_md5=…' "$LOG" | tail -1`，
   即**回读日志里本轮那个变异体摘要** —— 它是"日志自指"的兑现方式，故它**必然等于** `mutant_md5`（不是巧合）。
   ★ 但它只断言**非空**，**不**断言等于本轮 `MUT_MD5`；那条等价实际来自 `$LOG` 每轮以 `>` 截断，
   **来自截断、不来自断言**。⇒ 记成**装置已知的弱断言**（未改：四轮跑在同一个活进程上，
   改动脚本会让四轮跑在两个版本的装置上 —— 违背"同一文件被改动 ⇒ 旧证据对应旧字节"）。

---

## 四、装置 v1→v4 的四个盲区（都是**实测**撞出来的，不是设想）

| 版本 | 盲区 | 后果 | 修法 |
|---|---|---|---|
| v1 | 变异轮不带 `testFailureIgnore` | **前一个模块一红，reactor 当场中止**，`simos-core` 的 e2e 根本没跑 ⇒ 只知"最近的护栏响了"，误判为"唯一杀点"（`hits` 从 1 变 2 才暴露） | **只给变异轮**加 `-Dmaven.test.failure.ignore=true`；**绝不加到干净基线**（加了真失败会被吞、基线假绿） |
| v2 | 两处 `-q` | 绿轮的 `Tests run:` 汇总行被吞 ⇒ 读到**空串**。真后果不是"少一行日志"：一个**存活**的变异体会因"没跑到用例"被判**作废本轮**，而它该报的是"**存活**" —— 装置把最该看见的结论藏起来 | 去掉 `-q`；并给干净基线补 `CLEAN_TESTS_N>=1` 断言（测试名写错时 `failIfNoSpecifiedTests=false` 会静默跑 0 条、rc 仍为 0 ⇒ **完美的假绿**） |
| v3 | "失败方法"小节用旧格式正则归一化 | surefire **3.x** 的纯文本是 `完全限定类名.方法名 -- Time elapsed: … <<< FAILURE!`（**没有括号**），旧版才是 `方法(类)` ⇒ 正则一条都匹配不上 ⇒ 该小节**永远为空**。门禁没塌（HIT 走另一条路径），但**人读的那份 corroboration 是空的** | 打**原文**，并先断言 `FAIL_LINES` 非空 |
| v4 | （当前） | — | 另：每条结论都由 `find -newermt "@$ROUND_START"` 限定在本轮内，**不拿上一轮留下的绿/红当本轮结论** |

四版日志留痕：`../logs/t5-m1.device-v1.log` / `.device-v2.log` / `.device-v3.log`，v4 为最终 `../logs/t5-m1.log` 等。

---

## 五、★★ 本轮抓到并当场修掉的**真缺陷**：两条 ★ 用例不在分叉点上

### 现象

m2 变异体（`EdgeOperations` 的 `if (REPLACE.equals(operation))` ⇒ `|| MERGE.equals(operation)`，即"merge 当 replace 使"）
被杀时，**红点只有两条**：

```
EdgeOperationsTest.mergeLeavesUntargetedEdgesUntouched
EdgeOperationsTest.mergeKeepsExistingPropertiesOfTheSameKind
```

而**两条名字里就写着"merge 不丢既有 tag"的 ★ 用例全绿**：
`MapSetEdgeEndToEndTest.mergeSurvivesTheRealCommandPathAndKeepsTheExistingTag`、
`EdgeOperationsTest.mergeAddsTheNewTagAndKeepsEveryExistingTag`。

### 根因（读实现即定，非猜测）

`EdgeOperations` 的 `replace` 分支是**逐 kind** 摘标注的：`withoutTag(next.get(edge), tagKey)` 只摘 `tagKey` 那一 kind。
而这两条用例的夹具里，**既有 tag 都在别的 kind 上**（既有 `river`，命令是 `road`）⇒ 变异体的 replace 分支
**摘不到任何东西** ⇒ 两种语义在这份输入上**结果相同** ⇒ 用例恒真，是装饰。

（同族形态：形态 3「输入必须落在两种实现会分叉的地方」的夹具版。）

### 修法（当场修，不 park —— 修复比它的描述还短）

- `EdgeOperationsTest.mergeAddsTheNewTagAndKeepsEveryExistingTag`：既有边改成**带同 kind 的 props**
  （`road{width=3}`）**加**别的 kind（`river{width=2}`），断言既有边**逐值**不变。
  变异体下 `road` 被摘 ⇒ `E_LEFT` 只剩 `river` ⇒ **红**。
- `MapSetEdgeEndToEndTest.mergeSurvivesTheRealCommandPathAndKeepsTheExistingTag`：genesis 的 `E_LEFT` 同时带
  `river{width=2}` 与 `road{width=3}`，命令是 **road** merge 到 `E_UP` ⇒ 变异体下 `E_LEFT` 的 `road` 被摘 ⇒ **红**。
- 连带：`replaceOverwritesTheKindThroughTheRealCommandPath` 的断言随之**变强**（`E_LEFT` 不再"消失"，而是"只剩 road"
  —— 比原来多钉了一条"别的 kind 一字不动"）。

### 未受影响、且**诚实**的一条

`SetEdgeHandlerTest.appliesAMergePayload` 同样在这类输入上分不开两种语义，但它的 javadoc 明写验的是
"merge 载荷 ⇒ Applied，且变更集作用在 base 上真的给该边加了 river"（**管道**），**不声称**保护 merge 语义 ⇒ 不是缺陷。

### ★ 纪律实例

**"两种实现会分叉"必须在被测的那个维度上成立，而不只是在规模上成立。**

这与 CLAUDE.md 形态 1「夹具规模决定判别力」同族，但载体不同：那次是**键数**（`Map.copyOf` 保序在 3 键时
7%~40% 恰好落回插入序），这次是**既有 tag 的 kind 取值**。两次都是"夹具看起来够真实"，但都没落在分叉点上。

（不写"这是第 N 次"——本台账核不到 M8 全部轮次的原始记录，**只列当场核得到的**：T6-m2 的迭代序替身
（原 `selectionOf` 装升序 ⇒ 迭代序 == 自然序 ⇒ 变异必存活，已加两个自证过非自然序的替身）与本条。）

---

## 六、如实记账：夹具的来源

T5 的端到端夹具**用的是合成夹具**，不是真档。

- 判据原文允许："**若无则合成**"。
- 本机实测**没有**真档：无 `/tmp/m6-import-verify/test_integration`、无 `*_map.json`、无 `simos.db`。
- ⇒ 依计划兜底条款用合成夹具（三格链 `H00—H10—H01`，`E_LEFT` 带 `river`+`road`，`E_UP` 空）。
- **因此本台账不对"真档上的 `edges` 往返"作任何断言**；M6 的开口项（`edges` 非空无真实样本）**依然成立**。

---

## 七、我未能核实的

1. **真档上的 `map.SetEdge`**：没有真档，只有合成夹具（见 §六）。
2. **`RegionRandomizer` 的大图重切性能**：未测（M9 台账已记同条）。
3. **`mode` / `kind` 的大小写混合**只在单元层验过（`kindIsCaseInsensitiveAndNormalizedToLowercase`），
   未在 e2e 或浏览器上跑过。
4. **块表 `TreeMap` 全序在 >1000 块时**未测（本夹具最大 28 块）。
