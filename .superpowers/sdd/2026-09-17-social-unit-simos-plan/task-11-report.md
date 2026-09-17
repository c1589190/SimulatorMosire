# M3 Task 11 关账报告：`UnitOperations` 编制树操作面 8 项 + R11

日期：2026-09-18。分支 `feat/m3-social-unit-simos`（BASE `bbd60a6`）。
权威资料：派单说明 `task-11-brief.md`；计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` 第 3299~3711 行；M3 spec §4.6（8 项操作，U5）。

---

## 〇、交付面

| 文件 | 内容 |
|---|---|
| `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java` | 8 个静态纯函数：`create` / `reparent` / `rename` / `setStrength` / `placeAt` / `planRoute` / `cancelRoute` / `disband`（签名逐字照 spec §4.6）+ 私有助手 `require` / `requireExists` / `withUnit` / `append` / `copy` |
| `simos-unit/src/test/java/io/mosire/simos/unit/ops/UnitOperationsTest.java` | **13 条**测试（计划 12 条 + R-11-b 补条 `placeAtClearsInTransitRoute`） |
| `task-11-evidence/` | `run.sh` + `mutate.py`（Task 10 装置拷贝，按 R-11-f 改造）、`rounds/*.kept`（6 轮）、pre/post/verify 三份门禁日志 |

形制自证（R-11-e，逐条落实）：
- **8 项全纯函数**：每个操作都返回新 `UnitState`，不落任何共享可变状态；`withUnit` 用 `LinkedHashMap` 拷贝后覆盖，**保键位**。
- **无第二写路径**：操作面只产状态，不拼任何变更集（变更集唯一生产路径 = `UnitChangeSet.between`，本类一个 import 都没有）。
- **`create`/`reparent` 只校验父存在**（`requireExists`）；**成环交给 `UnitState` 构造期**——`reparentOntoItselfThrows` 实测走的就是这一族校验，操作面零重复。
- **`disband` 按 `at` 时刻判下属**：`other.parent().valueAt(at)`（m2b 变异证明这条按时刻取值是活的）。
- **`placeAt`/`disband` 顺带清路线**：两处 `Optional.empty()`（m4 变异证明 placeAt 这处是活的；disband 走"删单位"路径天然无在途）。
- 名单外编辑（速度/机动性/自定义字段）不进操作面，`copy` 的形参表里就没有它们的写入口。

---

## 一、R-11-a ~ R-11-f 逐条

### R-11-a（★ 计划用例前提错误——已按取代写法修复）
计划 `planRouteRequiresAStartThatMatchesTheEffectivePosition` 断言"COMPANY 整链无位置 ⇒ 抛"。**实测前提不成立**：COMPANY 的 parent 是 BRIGADE，BRIGADE 在 T0 有位置 H11 ⇒ `effectivePosition(COMPANY, T10)` 沿父链**继承出 H11**（Task 6 的 R7 语义）⇒ `planRoute(COMPANY, [H11,H12], T10)` 起点对得上、应成功。若照计划写，该断言对正确实现**必红**（实现前我本地复核过这条继承链）。
**落地**：① 保留原三条断言（BRIGADE 成功 / 起点对不上抛 / ghost 抛）；② 新增**正例**一行把修正后的语义钉住——`planRoute(COMPANY, route, T10)` 的 `movement()` isPresent（防止后人把"拒绝继承位置"当修复写回来）；③ "无位置 ⇒ 抛"改用**真无位置**的 `u-lost`（`create(twoUnits(), unit("u-lost", Optional.empty(), Optional.empty()))`，整链无位置）⇒ 抛、消息含"位置"。

### ★（报告新增）R-11-a 同族的第二处计划前提错误——改编正例
计划 `reparentAppendsASegmentAndRejectsUnknownParents` 断言 `parent().valueAt(T0)).isEmpty()`——但 COMPANY 在 T0 **就已是 BRIGADE 的下属**，`valueAt(T0)` 恒非空，**该断言对正确实现也红**（派单扫描未覆盖此处，实现在写测试时复核夹具发现）。**取代写法（保原意：证明"追加段的时间作用域"）**：改用 `create` 出的无父新兵 `u-recruit`（T0 parent 为空）——T10 起 BRIGADE、T0 为空，恰好同时钉住"追加"与"按时刻生效"（替换式覆写会红在 T0，跳过追加会红在 T10）。其余断言（旧状态 1 段不变、ghost 父抛、ghost id 抛）照留。

### R-11-b（★ m4 靶子需先补用例——已补）
计划的 `placeAtAppendsAPositionSegmentAndClearsTheRoute` 名不副实（没先 `planRoute`、没断言 `movement()` 清空）。已补第 13 条 **`placeAtClearsInTransitRoute`**：`planRoute(BRIGADE, [H11,H12], T10)` → `placeAt(BRIGADE, H12, T10)` → 断言 `movement()` 为空且位置段已追加。m4 变异（`Optional.empty()` 换回 `unit.movement()`）的红点**恰落在这条新用例**（`placeAtClearsInTransitRoute:154`，"Expecting an empty Optional but was containing Movement[...]"），而计划的旧用例在该变异下仍绿——证明补的正是缺的判别力。

### R-11-c（六轮变异的形态）——全部按简报执行，红点见 §二。
### R-11-d（期望数字）——全部命中：实现前唯一一次红 = 编译错（`COMPILATION ERROR` = 1，全部 `cannot find symbol: UnitOperations`）；实现后 **13/13**；改前基线 = util 156 / map 248 / social 30 / **unit 58**（45+13）；六轮每轮改后 `COMPILATION ERROR count = 0`。
### R-11-e（形制）——见 §〇。
### R-11-f（装置）——`task-10-evidence/{run.sh,mutate.py}` 拷入 `task-11-evidence/` 并改造：`LAB=/tmp/m3t11lab`、`ROUNDS_DIR=task-11-evidence/rounds`、`-pl simos-unit -am`、manifest（util+map+unit，139 个 .java，init 于 13 条测试就位后建）、"surefire 明说"抽取保留；`TARGET`/mutate.py 换 m1/m2a/m2b/m3/m4/m5。

---

## 二、六轮变异实验室（对**最终字节** `UnitOperations.java` md5=`3bf132a6c58f4c69d9b6af22bc2d481e`）

> 过程说明：六轮先跑过一遍（当时原件 md5=`7f41fd20...`）；随后 `./mvnw verify` 抓到 SpotBugs `DLS_DEAD_LOCAL_STORE`（disband 的 `Unit unit = require(...)` 是死存储），修掉这一行后**重建清单、六轮全部重跑**——入库的 `.kept` 全部以最终字节的 md5 为自证头。第一遍 m2a 因 mutate.py 未跟上该行的行内注释而"替换目标 0 命中、本轮作废"（装置按设计自拒），修正后再跑。

每轮自证头（`.kept` 原文）：干净世界 OK（139 个 .java 逐文件与工作树 md5 一致、清单之外 .java = 0）→ 改前 `COMPILATION ERROR count = 0`、BUILD SUCCESS（156/248/58）→ 变异体与原件**字节不同**（md5 并排留痕）→ 实际（修改 ∪ 新增）== 声明集合 → 改后 `COMPILATION ERROR count = 0`、simos-unit 跑过的测试类数 = 9。

| 轮 | 变异 | 实测红点（全列） | 判读 |
|---|---|---|---|
| m3t11v-m1 | 删 `create` 的"同 id ⇒ 抛" | `createRejectsDuplicateId:63`（1 条） | ✅ 红在保护行；红理由 = "Expecting code to raise a throwable"（重复 id 不再被拒） |
| m3t11v-m2a | 删 `disband` 的"有下属 ⇒ 抛"循环 | `disbandRefusesWhileSubordinatesExist:201` + `disbandIsTimeSensitive:219`（2 条） | ✅ 与简报一致：两个 disband 用例红、同根因（两处负向断言都靠这条循环抛） |
| m3t11v-m2b | `valueAt(at)` 改"恒取末段值" | `disbandIsTimeSensitive:219`（**唯一** 1 条） | ✅ 计划点名的时点敏感判别力证据：`disbandRefuses…` 在此变异下仍绿（单段夹具末段==at 值），只有跨段世界红 |
| m3t11v-m3 | 删 `planRoute` 起点相等校验（无位置守卫保留为裸语句） | `planRouteRequiresAStartThatMatchesTheEffectivePosition:179`（1 条） | ✅ 红在"起点对不上"负向断言；u-lost 抛、COMPANY 继承正例、ghost 抛均仍绿 |
| m3t11v-m4 | `placeAt` 的 `Optional.empty()` 换回 `unit.movement()` | `placeAtClearsInTransitRoute:154`（1 条） | ✅ R-11-b 补条正是缺的判别力；计划的 `placeAtAppends…` 如预期仍绿（其世界无在途路线） |
| m3t11v-m5 | `reparent` 不追加段 | `reparentAppendsASegmentAndRejectsUnknownParents:96`（"Expecting Optional to contain u-brigade but was empty"）+ **连带** `disbandIsTimeSensitive:217`（IAE：T20 仍有下属——预期内，同根因）+ **连带** `reparentOntoItselfThrows:114`（3 条） | ⚠️ 靶子红且红理由 = 被保护行本身；两条连带红**同根因**：reparent 的 `append` 是新父值进入对象的唯一载体——append 没了，`disbandIsTimeSensitive` 的前置改编失效、`reparentOntoItselfThrows` 的"指向自身"段根本不再被构造，`Unit`/`UnitState` 的两族校验都无段可查。不是误红，如实记录 |

六轮合计：**零存活变异**（无任何一轮全绿）；零"编译错当断言红"；零"用例没跑"。

---

## 三、门禁数字

| 项 | 数 |
|---|---|
| 实现前 | 编译失败（`COMPILATION ERROR` = 1，`cannot find symbol: UnitOperations`，rc=1）——唯一一次红 |
| 实现后单测 | `UnitOperationsTest` **13/13**（failures 0 / errors 0） |
| 改前基线 | util 156 / map 248 / social 30 / unit 45 |
| 改后（`./mvnw clean verify`，rc=0，BUILD SUCCESS） | util 156 / map 248 / social 30 / **unit 58** / core 15；`BugInstance size is 0` ×5 模块 |
| Spotless | `spotless:apply` 于实验室**之前**跑过、rc=0（两个新文件本就合形，零改动落地） |

证据文件：`task-11-evidence/pre-implementation-compile-failure.log`、`post-implementation-13of13.log`、`gate-clean-verify.log`（最终一次 verify 覆盖写入，rc=0）、`rounds/m3t11v-{m1,m2a,m2b,m3,m4,m5}.kept`。

---

## 四、关切 / 未能核实清单（如实）

1. **计划原文未改**（MUST NOT）：R-11-a 与"改编正例"两处取代说明都只写在测试代码注释与本报告里，计划第 3299~3711 行原样未动，后续读计划的人需靠本报告对账。
2. **比简报多钉了一行**：`planRoute` 用例里加了 `planRoute(COMPANY, ...)` 的 isPresent 正例（简报的取代写法只要求 u-lost 负例）。理由：R-11-a 的结论"COMPANY 应成功"本身就是被修正的事实，不钉住则防不住反向回退；它不影响任何一轮红点归属。
3. **m5 的连带红比简报预告的多一条**（`reparentOntoItselfThrows`）：简报预告了 disbandIsTimeSensitive 连带，没预告这条。机理见 §二表内判读——append 是新父值的唯一载体，删除它连带废掉两族环校验的输入。若控制器认为 m5 应换成"`append` 直接返回原 series"的形态（红点会移到 placeAt 两条 + reparent 一条），可再跑，但当前形态的靶子红与红理由已经干净。
4. **SpotBugs 一轮返工**：首版实现照计划 Step 3 逐字落地，`verify` 抓到 `disband` 的死存储（计划草图在 `Unit unit = require(...)` 上留了未用值）。修法 = 裸语句 `require(state, id);`。由此六轮实验室重跑了一遍——入库证据全部对应最终字节，**没有**"实验室证据与提交字节不一致"的账。
5. **本轮未触及**：`UnitChangeSet`/`UnitResolver`、MoveFixture、plan/spec/CLAUDE.md 均未改动；未推送。
