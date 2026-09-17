# M3 Task 5 报告：`SocialResolver` + R12/R13 的 social 半

**执行日**：2026-09-18（BASE `e83a439`，分支 `feat/m3-social-unit-simos`）
**权威依据**：派单说明 `task-5-brief.md`（R-5-a~f）→ 计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` L1248~1560 → spec `2026-09-17-social-unit-simos-design.md` §3.5 / §六 R12/R13 → 模板 `MapResolver` + `MapResolverTest`。

## ① 交付面

| 文件 | 内容 |
|---|---|
| `simos-social/src/main/java/io/mosire/simos/social/resolve/SocialResolver.java`（新增） | `implements Resolver`，`namespace()=="social"`。三类地址：`social:<mapId>`（根，`SubjectId("social", mapId)` / typeName `"Social"`）、`social:<mapId>:hex.<q>_<r>`（`SubjectId("social.hex","q_r")` / `"HexPopulation"`）、`social:<mapId>:[q,r]`（Index 恰 2 元 = Human 形式，canonical 一律 `hex.q_r`）。空候选 vs 抛的分工照 MapResolver：不服务（其它 kind / Property 段 / 段数>3 / 缺记录的格 / 非 social 命名空间）一律空候选；抛只有两处——装配故障（无 social 切片 / 切片类型不对）与认领 kind 的名字解析失败（`HexCoord.parse` 原样 IAE）。canonical **只由 Address AST 构造后 `canonical()` 产出**（§3.4 加引规则零重实现）；`dataOf(ctx)` 在段形状判定**之前**调用（R-5-f）；mapId 只回显不校验 |
| `simos-social/src/test/java/io/mosire/simos/social/resolve/SocialResolverTest.java`（新增） | **9 条用例**：计划 8 条（根 / hex / Human 形式 / 缺席格空候选 / 属性段与超长 / 外命名空间空候选 / 缺切片抛 / 冒名切片抛）+ R-5-b 靶子 `colonMapIdKeepsQuotesInCanonical`。夹具 = 真实 `SocialSnapshot` 装进 `SimulationState`，不用 Mockito |

实现与计划 Step 4 草图逐行一致（仅按计划 Javadoc 原文落注释 + 行内注释位置微调）；测试与计划 Step 2 草图一致，另按模板惯例在 `hexWithARecordResolvesAndCanonicalises` 补一条 `id().namespace()=="social.hex"`（"别只断字符串"）。

## ② R-5-a~f 逐条落点

- **R-5-a**：API 逐条核验后才动手——`Address` 构造期 ≥2 段强制（`Address.java:16-18` 实读）、`Entity.of(name)|of(kind,name)`、`Index.coords()`、`SimulationState.module(String): Optional<Snapshot>`、`Snapshot{ref,timestamp,namespace}`、`SocialData.populations()` / `SocialSnapshot.data()`、`Segment(SimosTimestamp, T)`。⇒ 裸用 `segments.get(1)` 无多余守卫，与 MapResolver 同款。
- **R-5-b**：`colonMapIdKeepsQuotesInCanonical` 已在跑 m2 **之前**写进测试。断言两条：根 `social:"m:1"`（`Address.parse("social:\"m:1\"")`，含 localId=="m:1"）与 hex `social:"m:1":hex.0_0`（含 typeName==`"HexPopulation"` 的非纯字符串断言）。**判别力已实测**：m2 轮它是唯一红（见 ③）——没有它 m2 全绿存活（所有现有 mapId=`Map1` 无 `:`，手写拼接与 AST 输出逐字相同）。
- **R-5-c**：实现前红 = 编译失败 `cannot find symbol: class SocialResolver`（唯一一次红，grep 计 13 处报错行全指向它）；实现后 `SocialResolverTest` **9/9**（surefire XML：tests=9, failures=0, errors=0）；三轮改前基线 = **util 156 / map 248 / social 30**（见 ⑤ 与 R-5-c 的数字出入说明）；每轮 `COMPILATION ERROR count = 0`（改前改后均 0）。
- **R-5-d**：装置 = Task 4 的 `run.sh`/`mutate.py` 拷贝改四处：`LAB=/tmp/m3t5lab`、`ROUNDS_DIR=task-5-evidence/rounds`、`TARGET` 三条（m3t5v-1/2/3）、surefire 抽取目标 → `*SocialResolverTest.txt`；`-pl simos-social -am` 与 manifest 范围（util+map+social，124 个 .java）不变。干净世界/字节自证/并集自证/红点抽取机制原样继承。
- **R-5-e**：m1 = **删** `resolveHex` 的 `containsKey` 判断 ⇒ `absentHexIsAnEmptyCandidateNotAnError` 红实测 ✓。m3 **二选一选了"删 `dataOf` 的 `instanceof` 类型判断（改直接强转）"**——没选"orElseThrow 改空切片"，因为 `SocialSnapshot` 紧凑构造器三字段全非空校验，造"空切片"要拖进合法 ref/timestamp 与 `Map` import 问题，变异体易挂 checkstyle/编译，违背"红必须是断言红"；删 instanceof 后 `wrongSliceTypeThrows` 红实测 ✓（ClassCastException ≠ IAE），`missingSliceThrows` 不受影响（orElseThrow 原样）。
- **R-5-f**：实现中 `dataOf(ctx)` 位于 namespace 判定之后、**所有段形状判定之前**（认领的 `social:` 地址一律先过装配故障关）；非 social 命名空间在碰 ctx 之前就返回空候选；canonical 唯一产出点 = `single(...)` 里的 `canonicalAddress.canonical()`（源码内有 ★ 注释钉住"加引规则不许在此手写重实现"）。

## ③ 三轮变异自证（G13）

装置：`task-5-evidence/run.sh` + `mutate.py`（变异在 `/tmp/m3t5lab` 副本上做，工作树零触碰）。每轮：rsync 全新副本（排除 target/.git/.serena/.superpowers）→ md5 清单 124 文件逐一比对 + 文件数一致 + 清单外 .java = 0 → **改前全绿** → 变异 → 字节不同 + 并集自证 → 改后 → `COMPILATION ERROR count = 0` → simos-social 测试类数 = 4（真的跑过）。

**每轮改前基线（同一份清单、同一世界）**：util 156 / map 248 / social 30，BUILD SUCCESS。

| 轮 | 变异（字节自证 md5 原件→变异体） | 实测红点（surefire 全列） | 红的理由是否落在被保护行 |
|---|---|---|---|
| m3t5v-1（m1） | 删 `resolveHex` 的 `containsKey` 判断；`1af68c54…`→`d184d495…` | **1 条**：`SocialResolverTest.absentHexIsAnEmptyCandidateNotAnError:89`。全仓其余 435 条绿 | ✓ 消息即行为本体："Expecting empty but was: [ResolvedSubject[id=social.hex/9_9, canonicalAddress=social:Map1:hex.9_9, …]]"——缺席的格拿到了候选，恰是被拆掉的分工 |
| m3t5v-2（m2） | `single(...)` 的 `canonicalAddress.canonical()` 换成 `naiveCanonical(...)`（逐段裸拼、无按需加引）；`1af68c54…`→`183a7e37…` | **1 条**：`SocialResolverTest.colonMapIdKeepsQuotesInCanonical`（断言行即根形式断言）：`expected: "social:\"m:1\"" but was: "social:m:1"`。其余 435 条绿——含全部 `Map1` 用例（裸词 mapId 逐字不变） | ✓ 红的理由恰是"手写拼接丢引号"，即 R13 的被保护不变量 |
| m3t5v-3（m3） | 删 `dataOf` 的 `instanceof` 类型判断（直接强转）；`1af68c54…`→`5f873ad0…` | **1 条**：`SocialResolverTest.wrongSliceTypeThrows:143`：期望 IAE、实收 ClassCastException（`SocialResolverTest$1 → SocialSnapshot`）。其余 435 条绿，`missingSliceThrows` 不红（orElseThrow 还在，符合预期） | ✓ 冒名切片没被 IAE 拦下，恰是被删守卫的职责 |

**m2 的靶子说明（R-5-b 判别力）**：m2 变异对"mapId 不含 `:`"的地址是**恒等变换**（裸词无需加引，拼接与 AST 逐字相同），计划原有 8 条用例的 mapId 全是 `Map1` ⇒ 若无靶子，m2 改前改后全绿、白做。`colonMapIdKeepsQuotesInCanonical` 用 `Address.parse("social:\"m:1\"")` / `("social:\"m:1\":hex.0_0")` 落在**两种实现必然分叉的输入**上（加引 vs 不加引），实测 m2 下它是唯一红。★ 如实记录：**hex 形式的第二条断言未单独出现在 surefire 报告里**——JUnit 同一测试方法首断言失败即停，根断言先红；hex 断言与根断言同根因（同一 `naiveCanonical` 丢同一对引号），派单 R-5-b 预案的"同时打掉两条"在报告口径下收敛为**1 条红测试、2 处受侵犯断言（1 实测 + 1 同机制必然）**。

## ④ 门禁数字

| 门禁 | 结果 |
|---|---|
| `./mvnw verify`（整仓，收尾跑） | **BUILD SUCCESS**：util **156** / map **248** / **social 30** / core 15，`BugInstance size is 0` ×4 模块，Spotless/Checkstyle 全过 |
| `./mvnw -q spotless:apply`（实验室之前跑） | 零 diff（git status 仅未跟踪新目录） |
| `SocialResolverTest` 单跑 | tests=9, failures=0, errors=0 |
| 社会模块用例总数 | 21 → **30**（+9） |
| 实验室三轮 | `COMPILATION ERROR count = 0` ×6 份日志（改前×3 + 改后×3）；三轮各恰好 1 条红测试，其余全绿 |

## ⑤ 偏离与如实记录

1. **R-5-c 的"三轮改前基线 social 21"实测为 30**：R-5-c 的 21 指 Task 5 之前的社会模块存量；实验室跑在工作树上，新测试文件已就位，故基线 = 21+9 = 30。util 156 / map 248 与期望一致。这不是漂移，是口径差（dispatch CONTEXT 亦写明"After: social 30"）。
2. **m3 变异二选一选了"删 instanceof"**（R-5-e 允许），理由见 ②。
3. **m2 红点为 1 条测试**（根断言），hex 断言同根因未独立实测——JUnit 首败即停所致，见 ③ 的 ★。
4. `hexWithARecordResolvesAndCanonicalises` 比计划草图多一条 `id().namespace()=="social.hex"` 断言（照 MapResolverTest"别只断字符串"的形制），非偏离、是模板惯例的落实。

## ⑥ 关切 / 未能核实清单

- **`social.hex` 的 SubjectId 形态与 MapResolver 同款（`"<ns>.<kind>"` + `hex.toString()`），但 R12/§3.5 只冻结了 typeName 与 canonical**——`SubjectId("social.hex", "q_r")` 是计划草图的形态，本任务照抄并钉住；若 Core 组合层将来要求"格主体全模块同 ID 空间"，需在 spec 层统一裁决（涉及 MapResolver 的 `map.hex` 同题）。
- **变异实验室未覆盖 `HexCoord.parse` 抛 IAE 的路径**（认领 kind 的名字解析失败）：`social:Map1:hex.9_9_9` 会走到 `HexCoord.parse("9_9_9")`——该行为是 util 的被测面（HexCoordTest 已钉），SocialResolver 只是"不包不吞"的转发，三条变异没打它，与计划 Step 6 一致（计划也只列 m1/m2/m3）。
- 除此之外无未核实项：报告内所有 Expected/数字均有当场日志（`task-5-evidence/rounds/*.kept`、surefire XML、verify 输出）。
