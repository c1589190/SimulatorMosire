# M3 Task 7 报告：`UnitChangeSet` + 反射往返框架

日期：2026-09-18。BASE `9aaa2a4`，分支 `feat/m3-social-unit-simos`。
权威资料：派单说明 `task-7-brief.md`（扫描结论 R-7-a~f）＞ 计划第 2242~2368 行；平移母本 = Task 4
最终提交的 `SocialChangeSetTest` / `SocialRoundTripTest`；M3 spec §3.4 / §4.7 / §6.1。

## 〇 交付面

| 文件 | 内容 |
|---|---|
| `simos-unit/src/main/java/io/mosire/simos/unit/change/UnitChangeSet.java` | `record UnitChangeSet(FieldDelta<Unit> units)` + `between` / `apply` / `isEmpty`；key = `UnitId.toString()` / `UnitId::parse`；不实现 util 的 `ChangeSet` 接口（C8）；不校验编制树不变量（R-7-f） |
| `simos-unit/src/test/java/io/mosire/simos/unit/change/UnitChangeSetTest.java` | 7 条用例（计划 6 条 + R-7-a 的 `unitOrderFollowsInsertionOrder`） |
| `simos-unit/src/test/java/io/mosire/simos/unit/change/UnitRoundTripTest.java` | 4 条用例（反射枚举 + 豁免集空钉死 + 组件数钉死 + namespace） |

合计 **11 条新用例**（7 + 4），与 R-7-d 的 11/11 一致。

## 一 R-7-a~f 逐条

### R-7-a（平移母本 = Task 4 最终形态）——已照办，且序夹具经历了一轮实测淘汰

1. `applyOfUnchangedKeepsTheBaseMapIdentical` 用 `containsExactlyEntriesOf`（含迭代序），不用
   `isSameAs`——`UnitState` 构造期总是冻结拷贝，与 Task 4 同款取代说明写进 Javadoc。
2. `unitOrderFollowsInsertionOrder` 已追加（spec §4.1 冻结要点 1 的观测者）。**夹具实测过程**：
   - **5 键候选**（`u-1 / corps-2 / division-33 / brigade-444 / regiment-5555`）：20 次独立 JVM
     启动 **17/20 SHUFFLED、3/20 PRESERVED**——红点会漂（约 15% 的 JVM 盐下保序），**不达标，弃用**
     （`order-fixture-20jvm-5keys-rejected.txt`）。
   - **6 键**（追加 `battalion-66666`）：**20/20 全 SHUFFLED、0 PRESERVED**（10 种槽位序），**达标采用**
     （`order-fixture-20jvm-6keys.txt`）。测试用例即按此夹具逐位 `containsExactly`。
   - **附带发现（双键也不稳）**：`applyOfUnchanged` 起初用双键 base（`u-1`/`u-2`），实测 **13/20
     PRESERVED / 7/20 SHUFFLED**——任何双键对的 `Map.copyOf` 槽位序都随 JVM 盐翻转，不存在跨盐稳定的
     双键夹具。若在这里钉双键序，m3 轮会**漂红**（7/20 概率多出一条假红点）。**已退回母本的单键形态**
     （单键无从重排），多键的序观察职责归 6 键的 `unitOrderFollowsInsertionOrder`。实测存档
     `order-fixture-20jvm-2keys-applyOfUnchanged-rejected.txt`。这印证了 CLAUDE.md 的告诫：
     **String 系 record 键在部分盐下"看起来保序"，夹具必须当场量，"看起来不像巧合"不算数。**

### R-7-b（m3 = 序用例的故意违规）——唯一红，实测兑现

m3 = `UnitState` 的 `Collections.unmodifiableMap(copy)` → `Map.copyOf(copy)`（连同失效的
`Collections` import 一并移除，避免 checkstyle 挡在用例之前）。实测（`rounds/m3t7v-4.kept`）：
**唯一红 = `unitOrderFollowsInsertionOrder:87`**，断言消息当场打出槽位序
`[division-33, regiment-5555, u-1, corps-2, battalion-66666, brigade-444]` ≠ 插入序——与前两轮的红点
集合完全不重叠。其余 22 条全绿，与"只有多键序观察点会分叉"的预判一致（单键 base 无从重排；等值断言
对 `Map.equals` 序不敏感）。

### R-7-c（m2 的形态）——m2-A 与 m2-B 都做了，且都实测

- **m2-A（必做，`rounds/m3t7v-2.kept`）**：实验室副本给 `UnitState` 加第二组件
  `String tag`（record 双组件）+ 旧签名二级构造器 `public UnitState(Map<UnitId,Unit> units) { this(units, ""); }`，
  全部旧调用点可编译；**测试一字不改**。实测红 **恰好 2 条**，正是预期的两条机械护栏：
  1. `changeSetHasExactlyOneComponent:81`——反向 subset 失败：`["units","tag"] ⊄ ["units"]`，
     AssertionError 当场列出多出的 `["tag"]`；
  2. `everyUnitStateComponentParticipatesInTheChangeSet:59 → mutate:97`——
     `IllegalStateException: 未登记的组件: tag`。
  **这证明框架抓得住"新增状态组件忘了进变更集"**（铁律 5 的机械落地），两条护栏各自独立响。
- **m2-B（可选，已做，`rounds/m3t7v-3.kept`）**：在 A 基础上把 `changedOf` 的 `default` 改成
  `true` **并**给 `mutate` 注册 `case "tag" -> base.withUnits(oneUnit())`。实测：
  `everyUnitStateComponentParticipatesInTheChangeSet` **由红转绿**（泄漏兑现：tag 从未被真正校验却
  "参与"了——changedOf 的 true 是兜底谎报），`changeSetHasExactlyOneComponent` **仍红**（形态测试
  不受兜底影响）。结论：**温和兜底正好废掉反射循环的判别力，只剩组件集形态测试还活着**——这就是
  `default` 必须**抛**的实测理由。

### R-7-d（期望数字）——全部兑现

- 实现前编译失败：43 处 `cannot find symbol`（`UnitChangeSet` 不存在），唯一一次红 = 编译错
  （`pre-implementation-compile-failure.log`；注：该日志为**复现件**——首跑时的输出已在会话中核对，
  为存档用"暂移走实现文件 → 同命令重跑 → 立即还原"的方式落盘）。
- 实现后 **11/11**（UnitChangeSetTest 7 + UnitRoundTripTest 4）。
- 实验室每轮**改前**基线：util 156 / map 248 / social 30 / **unit 23**（12 旧 + 11 新）全绿。
- 每轮 `COMPILATION ERROR count = 0`（改前改后各一次，四轮共 8 次）。

### R-7-e（装置）——Task 6 装置拷贝改五处

`task-7-evidence/{run.sh,mutate.py}` 拷自 Task 6：`LAB=/tmp/m3t7lab`、
`ROUNDS_DIR=task-7-evidence/rounds`；manifest 范围不变（util+map+unit，127 个 .java）；surefire 抽取
改"实际失败类"形态（FAILURE! 行 + `surefire-reports/*Test.txt` 全量摘要，覆盖红点跨
`UnitChangeSetTest`/`UnitRoundTripTest`/`UnitStateTest` 的本轮）；`TARGET` 换 m1/m2-A/m2-B/m3。
每轮自证链完整：干净世界（rsync 全新副本 + md5 逐文件 + 文件数 + 无清单外 .java）→ 改前全绿 →
变异体与原件**字节不同**（md5 对照）→ 实际（修改 ∪ 新增）== 声明集合 → 改后 `COMPILATION ERROR
count = 0` → simos-unit 测试类数 = 5（用例真的跑过）→ 红点全列 + surefire 明说。

### R-7-f（形制）——已照办

变更集**不校验编制树不变量**（只做逐组件 diff + 重建；夹具全部是合法树——根单位森林，无环）；
`UnitChangeSet` 全委托 `FieldDelta.diff`/`rebuild`；两个 `switch` 的 `default` 均**抛**
（`未登记的组件: …`）；豁免集 `Set.of()` 空且被 `theExclusionListIsEmpty` 单独钉死；key =
`UnitId.toString()` / `UnitId.parse`。全仓无新增 `Map.copyOf`（仅 m3 变异体内出现，已在轮后销毁）。

## 二 三轮变异自证与实测红点全列

每轮 .kept 头部含：目标、干净世界自证、改前基线（全绿）、变异体 md5 对照（字节不同）、并集自证、
`COMPILATION ERROR count = 0`、simos-unit 测试类数 = 5。以下为**实测红点**（非预期推演）：

### m3t7v-1（m1：`between` 两侧对调 → `diff(target, base)`）——红点数 4

| # | 红点 | 行号 | 为什么红（读过日志后的归因） |
|---|---|---|---|
| 1 | `UnitChangeSetTest.aSingleChangedUnitIsNotAnEmptyChangeSet` | :64 | 同键集时，对调后的 diff 把**旧值**装进 Upsert（`u-2→200` 而非 201）——apply 等于把 base 还原成自己，永远到不了 target |
| 2 | `UnitChangeSetTest.removalWithoutUpsertIsRemoveAndSurvivesApply` | :102 | 键集不同时对调更狠：删除侧（读 diff 第一参）循环扫的是原 target、增改侧（第二参）扫的是原 base ⇒ **Unchanged**，`isEmpty` 为 true 且非 `Remove` |
| 3 | `UnitChangeSetTest.addAndRemoveTogetherIsAPatch` | :119 | 同上机制 ⇒ 只剩 `Upsert{u-3}`，非 `Patch`；apply 丢删除侧（**正是 GSimulator 的病根换了个方向复现**） |
| 4 | `UnitRoundTripTest.everyUnitStateComponentParticipatesInTheChangeSet` | :64 | `Remove{u-1}` 取代 `Upsert` ⇒ `apply(empty)` 仍是空 ≠ target |

判读：计划预期"单组件变化那条与往返用例同时红"**命中**（#1、#4），另两条（#2、#3）是键集不同时的
必然连带——四条全是**方向观察点**，红点集合与 m2/m3 不重叠。

### m3t7v-2（m2-A：UnitState 加 `tag` 组件 + 二级构造器，测试不改）——红点数 2

| # | 红点 | 行号 | 为什么红 |
|---|---|---|---|
| 1 | `UnitRoundTripTest.changeSetHasExactlyOneComponent` | :81 | 反向 subset 失败：`["units","tag"] ⊄ ["units"]`（正向 subset 仍过——变更集组件在状态里都有同名） |
| 2 | `UnitRoundTripTest.everyUnitStateComponentParticipatesInTheChangeSet` | :59→mutate:97 | 反射枚举到 `tag`，`mutate` 的 `default` **抛** `IllegalStateException: 未登记的组件: tag` |

### m3t7v-3（m2-B：在 A 之上 `changedOf` 的 default→true 并注册 tag）——红点数 1（**泄漏轮**）

| # | 红点 | 行号 | 为什么红 |
|---|---|---|---|
| 1 | `UnitRoundTripTest.changeSetHasExactlyOneComponent` | :81 | 形态测试不受兜底影响，仍抓得住双组件 |

**关键观测**：`everyUnitStateComponentParticipatesInTheChangeSet` **由红转绿**——tag 从未进变更集、
从未被 diff/apply 真正校验，却因 `changedOf` 的温和兜底"参与"了。**泄漏被证明**：`default -> throw`
是反射循环唯一的行为防线，换成温和兜底后框架对新组件**失明**。m2-B 已验证（非"未验证"）。

### m3t7v-4（m3：`unmodifiableMap` → `Map.copyOf`）——红点数 1

| # | 红点 | 行号 | 为什么红 |
|---|---|---|---|
| 1 | `UnitChangeSetTest.unitOrderFollowsInsertionOrder` | :87 | 槽位序 `[division-33, regiment-5555, u-1, corps-2, battalion-66666, brigade-444]` ≠ 插入序——spec §4.1 冻结要点 1 的观测者如约响 |

判读：**唯一红**，与前两轮红点集合零重叠（R-7-b 达成）。`applyOfUnchanged`（单键 base）与其余等值
断言全绿——"为什么没红"的答案：它们要么单键无从重排，要么走 `Map.equals`（序不敏感），**本就该绿**。

## 三 门禁数字

- `./mvnw verify`：**rc=0，BUILD SUCCESS**。Surefire：util **156** / map **248** / social **30** /
  **unit 23**（12+11）/ core **15**，全部 `Failures: 0, Errors: 0`；SpotBugs `BugInstance size is 0` ×5；
  Spotless/Checkstyle 随 verify 通过。
- 工作树：实现与测试仅落在 `simos-unit`，未触碰计划/spec/CLAUDE.md/其他模块。

## 四 关切 / 未能核实 / 取代说明清单

1. **序夹具的判别力是"本机实测"**：6 键 20/20 SHUFFLED 是本机 20 次独立 JVM 的实测，不是全 salt 空间的
   证明（数学上不可证）。换机器后若 m3 轮 `unitOrderFollowsInsertionOrder` 没红，先怀疑盐，重跑探针再下
   结论（探针已存档，可复跑）。
2. **m1 的预期红点从 2 条修正为 4 条**：TARGET 注释里我事先只预测了 2 条（键集不同才分叉），实测 4 条——
   同键集的 #1、#4 分叉原因是"对调后的 diff 把旧值装进 Upsert"，这是我预测时漏掉的一半机制（diff 不只
   决定哪些键不同，还携带**写入哪个值**）。已按"实际红点以日志为准"的纪律归因并记入上表。
3. **`applyOfUnchanged` 退回单键 base 是执行期决定**（计划/派单未提）：依据是双键 base 的 20-JVM 实测
   （13/7 随盐漂），取代说明已写进用例 Javadoc；双键的序观察职责由 6 键序用例承担，不丢覆盖。
4. **`pre-implementation-compile-failure.log` 是复现件**（首跑未落盘，事后"暂移实现文件重跑"落盘），
   命令与首跑完全一致；正文其余数字全部为当场实跑。
5. 无其他偏离。计划 Step 5 的"2 轮变异"按派单 R-7-b/R-7-c 扩为 4 轮（m1 / m2-A / m2-B / m3）。
