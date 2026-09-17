# M3 Task 4 报告：SocialData / SocialSnapshot / SocialChangeSet + 反射往返框架

日期：2026-09-18。BASE `7f19de8`，分支 `feat/m3-social-unit-simos`。
权威资料：派单 brief（R-4-a~f）> 计划 838~1244 行 > M3 spec §3.1/§3.4/§6.1 > CLAUDE.md 纪律节。

## 一、交付面

| 文件 | 内容 |
|---|---|
| `simos-social/src/main/java/io/mosire/simos/social/SocialData.java` | record，单组件 `populations`；构造期**保序冻结拷贝**（`LinkedHashMap` + `Collections.unmodifiableMap`，逐键值查 null）；`empty()` / `withPopulations(Map)` |
| `simos-social/src/main/java/io/mosire/simos/social/SocialSnapshot.java` | record `(StateRef, SimosTimestamp, SocialData) implements Snapshot`；三组件构造期 null 抛；`namespace()` 固定 `"social"` |
| `simos-social/src/main/java/io/mosire/simos/social/change/SocialChangeSet.java` | record `(FieldDelta<PopulationSeries> populations)`；`between`/`apply` 全部委托 util 的 `FieldDelta.diff`/`rebuild`（key 解析 `HexCoord::parse`）；`isEmpty`；不实现 util 的 `ChangeSet` 接口（C8） |
| `…/change/SocialChangeSetTest.java` | 计划 6 用例 + 补的序观察点 `populationOrderFollowsInsertionOrder` = **7** |
| `…/change/SocialRoundTripTest.java` | 反射往返 4 用例（照 M2 `RoundTripComponentsTest` 形制：豁免集 `Set.of()` 被 `theExclusionListIsEmpty` 单独钉死；两个 switch 的 `default` 抛；`changeSetHasExactlyOneComponent` 双向 subset） |

用例数：`SocialChangeSetTest` 7（计划 6 + 序观察点 1）+ `SocialRoundTripTest` 4 = **11**（补序用例前 10）。social 模块合计 21（PopulationSeriesTest 10 + 新 11）。

## 二、TDD 过程（计划 Step 1~4）

1. 先写两个测试文件 → `./mvnw -q -pl simos-social -am … test` **编译失败**（三类型不存在，`cannot find symbol` ×14；唯一一次允许的编译红）。
2. 实现三类型 → 同命令 **10/10 绿**。
3. `spotless:apply`（先于实验室）；全量 `./mvnw clean verify` 绿（门禁数字见 §五）。

## 三、R-4-a~f 逐条

- **R-4-a（API 已核验）**：照单全收，未改任何签名。实测编译一次通过（第 2 步全绿后无 API 修正）。
- **R-4-b（m2 探针决策规则）**：**按规则完整执行，存活轨迹与预判一致**（完整轨迹见 §四）：
  ① 现状夹具跑 m2（`m3t4v-2a`）→ **存活**（0 红点）；② 夹具加到 5 键再跑（`m3t4v-2b`）→ **仍存活**（0 红点）——证实"序不敏感是断言形态问题，加键救不了"；③ 补 `populationOrderFollowsInsertionOrder`（5 键非平凡插入序 `(0,0)(3,1)(1,2)(2,-1)(4,4)`，`containsExactly` 逐位钉键序，放进 `SocialChangeSetTest`，**未新建 SocialDataTest.java**）→ **重跑 m2 终局轮（`m3t4v-2`）**，`.kept` 展示**唯一红点落在新用例**（`SocialChangeSetTest.populationOrderFollowsInsertionOrder:76`，actual 为槽位序 `[1_2, 3_1, 4_4, 0_0, 2_-1]`）——证据对着终局字节。
  **夹具键数当场量**（CLAUDE.md 形态清单第 1 条）：对这组键、这个插入序，独立 20 次 JVM 启动 `Map.copyOf` 的迭代序 **0/20 保插入序**，共观测到 8 种不同槽位序。原始输出与程序存 `task-4-evidence/copyOf-slot-order-20jvm.txt`、`copyOf-slot-order-ProbeOrder.java.txt`。
  探针②的 5 键夹具是**临时测量状态**，量完即还原；终局态 = 计划原用例 + 序用例，未留探针残渣。
- **R-4-c（m1/m3 形态与允许的连带）**：m1 = `between` 两实参同传 `base.populations()`；m3 = `apply` 里 `cs.populations()` 换成 `new FieldDelta.Unchanged<>()`。两轮均按预判红，**全部实测红点**见 §四表。
- **R-4-d（装置）**：`task-3-evidence/{run.sh,mutate.py}` 拷入 `task-4-evidence/`；改 `LAB=/tmp/m3t4lab`、`ROUNDS_DIR`→本任务、`TARGET`→五条（m1 / 2a / 2b / 2 终局 / m3）、surefire 抽取→`SocialChangeSetTest`+`SocialRoundTripTest`；manifest 范围（util+map+social）与 `-pl simos-social -am` 不变。装置内部一处必要适配：**m2 变异连 `import java.util.Collections;` 一起删**——否则 checkstyle 的 `UnusedImports` 挂在 validate、测试压根不跑，红就不是断言红。
- **R-4-e（期望数字）**：实现前编译失败 ✅；实现后 10/10 ✅（补序用例后 11）✅；每轮 `COMPILATION ERROR count = 0` ✅（5 轮全 0）。**基线数字与 brief 有一处口径差**：brief 写"三轮改前基线 = util 156 / map 248 / social 10"，实测改前为 util 156 / map 248 / **social 20**（S0）与 21（S2 终局）。social 的"10"是**任务前**的模块总数（仅 PopulationSeriesTest），而实验室的改前跑必然带着本任务新用例，故 20/21 是唯一可实测的形态；util/map 两条精确命中。
- **R-4-f（往返框架照 M2 形制）**：豁免集 `Set.of()` + `theExclusionListIsEmpty` 单独钉死 ✅；`mutate`/`changedOf` 两个 switch 的 `default` 抛 `IllegalStateException` ✅；`changeSetHasExactlyOneComponent` 三段（hasSize(1) + 正向 subset + **反向 subset**）✅。

## 四、变异实验室（计划 Step 5 + R-4-b 轨迹，5 轮）

装置：每轮干净世界（rsync 全新副本、排除 target）→ md5 清单逐文件比 + 文件数 + 无清单外 `.java` → 改前全绿 → 变异字节自证 → 并集自证 → 改后断言 `COMPILATION ERROR count = 0`、simos-social 测试类真跑过 → 红点全列。

### m1（`m3t4v-1.kept`）

- 目标：`between` 改传 `diff(base.populations(), base.populations())`（没有真的比较两侧）。
- 自证：`SocialChangeSet.java` md5 `b019b0c9…` → `ef710f94…`（字节不同）；并集自证 OK；改后 COMPILATION ERROR = 0。
- 改后：social 20 跑 4 失败，**红点全列（4 个）**：
  1. `SocialChangeSetTest.aSingleChangedEntryIsNotAnEmptyChangeSet:54`
  2. `SocialChangeSetTest.removalWithoutUpsertIsRemoveAndSurvivesApply:66`
  3. `SocialChangeSetTest.addAndRemoveTogetherIsAPatch:79`
  4. `SocialRoundTripTest.everySocialDataComponentParticipatesInTheChangeSet:44`
- 为什么红：`diff(base, base)` 恒 `Unchanged`，前三红是"期望 Patch/Remove/非空却得 Unchanged"，第 4 红是往返正面钉子 `cs.isEmpty()` 为 true——全部落在"没有真的比较两侧"这个根因上。`unchangedDataGivesAnEmptyChangeSet` 与冻结用例不受影响（正确地绿）。

### m2 轨迹（同一变异的三次观测：`2a` → `2b` → `2`）

变异：`populations = Collections.unmodifiableMap(copy);` → `populations = Map.copyOf(copy);`（并删失效 import）。三次的 md5 自证一致：`SocialData.java` `81e37221…` → `2735f8d1…`；三轮 COMPILATION ERROR 全 0。

| 轮 | 夹具/断言状态 | 结果 |
|---|---|---|
| `m3t4v-2a` 探针① | 计划原用例（夹具 ≤2 键，全部序不敏感断言） | **存活**：0 红点，social 20 全绿，BUILD SUCCESS |
| `m3t4v-2b` 探针② | `aSingleChangedEntry…` 夹具临时加到 5 键（断言仍序不敏感） | **仍存活**：0 红点，social 20 全绿——加键救不了断言形态（R-4-b 的诊断被实测证实） |
| `m3t4v-2` 终局 | 补 `populationOrderFollowsInsertionOrder`（5 键非平凡序 + `containsExactly`）后重跑 | **红，且唯一红点落在新用例**：`populationOrderFollowsInsertionOrder:76`，actual `[1_2, 3_1, 4_4, 0_0, 2_-1]`（槽位序）≠ 插入序；其余 20 条全绿 |

独立 JVM 槽位序测量（对终局用例的这组键与插入序）：**0/20 保插入序、8 种槽位序**——终局轮单次跑红不是运气，是该夹具下的常态（证据文件见 §三 R-4-b）。
终局轮改前基线：util 156 / map 248 / social **21** 全绿。

### m3（`m3t4v-3.kept`）

- 目标：`apply` 的 `rebuild` 第二实参换成 `new FieldDelta.Unchanged<>()`（apply 不吃 delta）。
- 自证：`SocialChangeSet.java` md5 `b019b0c9…` → `8ecf04d3…`；并集自证 OK；COMPILATION ERROR = 0。
- 改后：social 20 跑 4 失败，**红点全列（4 个）**：
  1. `SocialChangeSetTest.aSingleChangedEntryIsNotAnEmptyChangeSet:55`
  2. `SocialChangeSetTest.removalWithoutUpsertIsRemoveAndSurvivesApply:67`
  3. `SocialChangeSetTest.addAndRemoveTogetherIsAPatch:80`（断言消息"Patch 两侧都不许丢"）
  4. `SocialRoundTripTest.everySocialDataComponentParticipatesInTheChangeSet:46`（消息"组件 populations 的往返"）
- 为什么红：rebuild 拿到强行 `Unchanged` 直接原样返回 base，一切"变了"的断言全红；两处空变更集用例（`unchanged…`、`applyOfUnchanged…`）与冻结用例正确地绿。

### 证据对终局字节的口径说明

`m3t4v-2`（m2 终局）的 `.kept` 对着**终局字节**（11 条新用例，social 21）。`m3t4v-1`/`m3t4v-3` 的 `.kept` 摄于 S0（10 条新用例，social 20）：S0 → 终局的唯一测试差异是**追加**的序用例（其余用例字节相同；探针②的 5 键夹具已还原），而序用例不调用 `between`/`apply`，m1/m3 的红点集合在终局字节下不变——故未重跑这两轮，如实记录于此。

## 五、门禁数字（终局字节，`./mvnw clean verify`）

| 模块 | 用例 | SpotBugs |
|---|---|---|
| simos-util | 156，0 失败 | BugInstance size is 0 |
| simos-map | 248，0 失败 | BugInstance size is 0 |
| simos-social | **21**，0 失败 | BugInstance size is 0 |
| simos-core | 15，0 失败 | BugInstance size is 0 |

BUILD SUCCESS；Spotless / Checkstyle / enforcer 全过（clean verify 含）。

## 六、偏离与关切（诚实清单）

1. **取代说明：`applyOfUnchangedKeepsTheBaseMapIdentical` 的 `isSameAs` → `containsExactlyEntriesOf`**（计划第 945 行 vs 计划自己的实现）。计划的 `SocialData` 构造期**总是**冻结拷贝（保序 + 逐键查 null，与 M2 `GameMap` 同形制），`apply` 经 `new SocialData(…)` 落地必是新实例——`isSameAs` 在计划自带实现下**不可满足**（实测首轮即红：`Expecting same instance`），M2 也因此没有这条断言。spec §3.4 冻结的语义是"Unchanged ⇒ base 原样（**连键序**）"，由 `containsExactlyEntriesOf`（含迭代序）钉住。取代说明写在测试 Javadoc 里；未改计划文件。**未选择**给 `apply` 加 `Unchanged` 快路来保 `isSameAs`：那会给计划外的分支，且 M2 无此先例。
2. **序观察点是计划意图的机制修正**（R-4-b 授权）：计划 m2 的"夹具加到 4~6 键"被实测证明救不了（`2a`/`2b` 两轮 0 红点），改为补 `containsExactly` 序用例；取代说明写在用例 Javadoc。放 `SocialChangeSetTest` 内（brief 的首选，避免新文件，故无 `SocialDataTest.java` 进提交 A）。
3. **m2 变异必须连 `import java.util.Collections;` 一起删**，否则 UnusedImports 让"红"变成构建红——装置内处理，属 R-4-d"surefire 明说"同级的装置适配，不影响被保护那行的红点归属。
4. **R-4-e 的 social 基线"10"**：见 §三 R-4-e 条——"10"只能理解为任务前模块计数；实验室可实测的改前数字是 20（S0）/ 21（终局）。其余数字全部命中。
5. **m1/m3 `.kept` 摄于 S0**：见 §四末。不重跑的理由与差异范围已写明。
6. **未能核实**：`Map.copyOf` 槽位序的 0/20 只代表**本机本 JVM 版本**对这组键的实测（`ImmutableCollections` 的 SALT 逐 JVM 变化）；换机器/换 JDK 不保证同分布，但"大概率红"的判别方向不变（M2 的 4~6 键 0/30 同向）。此外核心 15 条用例属 M0 的 AgentLib 可用性测试，本任务未触碰 core 代码。

## 七、遗留

无。计划 Task 4 的 Step 1~6 全部执行；brief 的 R-4-a~f 全部落地的实测记录如上。
