# Task 11 报告：M1 关账

**执行者**：T11 实现者（subagent）
**日期**：2026-09-16
**分支**：`feat/m1-util-simos`
**起始 HEAD**：`54ef235`（动手前 `git rev-parse --short HEAD` 核过 = 简报给定值 ✓）
**本任务性质**：纯文档。`src/` 下一行未改（事后 `git status` 可证）。

---

## Step 1：全量门禁

**命令**：`./mvnw clean verify`

**原始输出摘录**（`54ef235` 上实跑，退出码 0）：

```
[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0
[INFO] BugInstance size is 0        ← 出现 5 次（各模块各一次）
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.core.AgentLibAvailabilityTest
[INFO] SimulatorMosire .................................... SUCCESS [  0.935 s]
[INFO] UtilSimos .......................................... SUCCESS [  4.112 s]
[INFO] MapSimos ........................................... SUCCESS [  0.985 s]
[INFO] SocialSimos ........................................ SUCCESS [  0.888 s]
[INFO] UnitSimos .......................................... SUCCESS [  0.855 s]
[INFO] CoreSimos .......................................... SUCCESS [  1.367 s]
[INFO] BUILD SUCCESS
```

`grep -c '^\[ERROR\]'` → **0**。

**与简报基线的差异（已知且正确）**：简报基线块引的是 `ec2f10d` 的实测值。`simos-util` 用例数
已由 **149 → 155 → 156**（T10 及其两轮评审判修复）。我跑出 **156**，控制器已裁定这是对的，
**没有为对齐简报而去动测试**。除这一项外，`simos-core` 15 条、六模块全 SUCCESS、
`BugInstance size is 0` 三项**逐字成立**。

**文档改完后又跑了一次**（`/tmp/t11-verify-final.log`），结论同上——文档改动不参与构建，
此处复跑只为"门禁结论与提交内容同一时刻成立"。

---

## Step 2：逐条核对关账判据（spec §1.2）

| 判据 | 核对方式（**真的跑了 / 真的看了**） | 结论 |
|---|---|---|
| 八大件各有单测 | `find simos-util/src/test -name '*Test.java'` → **17** 个类（与 Surefire 逐类计数逐个对上）；`Address*`(3) / `SubjectIdTest`+`ResolvedSubjectTest`+`QueryResultTest` / `SimosTimestampTest` / `StateRefTest` / `SnapshotProtocolTest` / `SimulationStateTest` / `RoundTripAssertionsTest` / `RoundTripAssertionsDriftTest` / `InMemoryInfoSystemTest` / `TemporalSeriesTest` / `ResolverRegistryTest` / `FacetRegistryTest` 全绿 | ✅ |
| 往返框架有故意漂移的失败用例 | 读 `RoundTripAssertionsDriftTest`（1 条，绿）：`DriftingSnapshot`/`DriftingChangeSet` 漏 `beta`，断言 `assertThatThrownBy(...).isInstanceOf(AssertionError.class)` | ✅ |
| 冻结样例逐条往返 | 数 `AddressParseTest` 的 `@ValueSource` 字符串 = **15**；Surefire XML 里 `frozenSamplesRoundTrip(String)[1..15]` **15 条**，无 failure | ✅ |
| `./mvnw verify` 绿 | Step 1 | ✅ |

**★ 八大件里的 `ChangeSet` / `Command`（简报 Step 3 第 3 条）**：我按简报**没有**另外新建测试类，
只在 spec §十二 那一行改了表述。核对证据：`git grep -n 'changeSetAndCommandExposeTheirStamps'`
→ `SnapshotProtocolTest.java:27`，该方法对 `ChangeSet.baseRevision()` 与 `Command.expectedRevision()`
各自取值断言；`SnapshotProtocolTest.java:8` 的 javadoc 明写"ChangeSet/Command 都是单方法接口"。

**★ 一处我没能"跑出来"的**：简报说控制器曾"从 spec 表格直接推出缺两个件"，
**那是控制器当时的推理过程，不是可执行命令**——我只能核实其结论面（表格确实没有这两个件的落点行，
而用例确实存在），无法复现"差点的误判"本身。

---

## Step 3：回填 spec + 处理计划腐坏

### 3.1 spec `docs/superpowers/specs/2026-09-16-util-simos-design.md`

| # | 改动 | 位置 | 所依据的当场核实 |
|---|---|---|---|
| 1 | §五 新增 `SimosTimestamp` 的 `equals` / `compareTo` 口径分叉条目 | §五 列表第 2 条 | 读 `SimosTimestamp.java:9-15,34-37`：record 默认 `equals`（含 `calendarLabel`），`compareTo` 只 `Long.compare(tick,…)` |
| 2 | §五 新增 `TimeRange.to` 严格晚于 `from` 条目 | §五 列表第 3 条 | 读 `TimeRange.java:13-23`：紧凑构造器 `end.compareTo(from) <= 0` → `IllegalArgumentException`；`since()` = `Optional.empty()` |
| 3 | §十二 表头加"本表是下限 + 表格不是证据"说明 | §十二 表上方 | 无（说明性文字，其事实依据见下条与 Step 2） |
| 4 | §十二 新增 `TimeRangeTest` 行 | §十二 表内 | 读 `TimeRangeTest.java`（4 条：左闭右开 / `since` 无上界 / 空或倒置区间抛 IAE / null 部分抛 NPE 且钉 `"to"`） |
| 5 | §十二 `SnapshotProtocolTest` 行的"覆盖"列改为**同时点明 `ChangeSet` 与 `Command`** | §十二 表内 | 见 Step 2 的 ★ |

### 3.2 计划 `docs/superpowers/plans/2026-09-16-util-simos-plan.md`（取代说明，**保留草图原貌**）

顶部加了**总纲性取代说明**（"全文代码草图是计划期产物，spec 与已落地 `src` 才是权威"），
然后**逐处**加取代说明。**一处草图都没重写、一行原文都没删。**

| # | 位置 | 分歧 | 我的核实方式 |
|---|---|---|---|
| 1 | 任务地图 Task 2 行 | 原写"**14** 条冻结样例" | 与**本计划 Task 2 Step 5 自己的"15 条冻结样例"**自相矛盾；spec §3.6 表 **14 行 15 条**（`unit:U:hex`/`unit:U:speed` 同占一行）；`@ValueSource` 实数为 15；surefire XML 15 条。**此项超出简报列举的范围，是我在核 Step 2 判据时顺带发现的** |
| 2 | Task 7 Step 1 前 | 草图 9 条 vs 已落地 `TemporalSeriesTest` **16** 条 | Surefire 逐类计数 16；`@Test` 逐行数计划 Task 7 段 = 9 |
| 3 | Task 7 Step 4 | 草图 `public final class SegmentedSeries<T>`（私有构造器、校验全在 `of`）vs **spec §七 强制 record** | 读计划 `:2080-2135` 与已落地 `SegmentedSeries.java`（record + 紧凑构造器校验 + `List.copyOf` + 无双覆盖） |
| 4 | Task 8 Step 1 前 | ① 草图 4 条 vs 已落地 **8** 条；② 注册序草图 `map`→`unit`（**恰好等于字母序**，换 `TreeMap` 照样绿）vs 已落地 `unit`→`map` | 读 `ResolverRegistryTest.java:30-32,71,98-105`；字母序经 **jshell 实测** `List.of("unit","map").stream().sorted()` → `[map, unit]` |
| 5 | Task 9 Step 1 前 | **草图自相矛盾**：Step 1 断言 `facetNames()).containsExactly("unit","social")`，Step 3 实现返回的却是 **facet 名**（`putIfAbsent(facetName,…)` / `keySet()`）；根因是助手 `provider(String namespace, String facetName, …)` 第 1 参**从未被引用**。草图 5 条 vs 已落地 **10** 条 | 读计划 `:2479` / `:2517` / `:2639` / `:2646` 与已落地 `FacetRegistryTest.java:27-36,100-105,141-150` |
| 6 | Task 10 Step 1 前·Step 2 | **草图自相矛盾**：`ToySnapshot.apply`/`DriftingSnapshot.apply` 用 `changeSet.baseRevision()`（= base 的版本）⇒ applied 的 ref 恒等于 base 的 ref、`target` 不可达；Step 5 `Expected: PASS` 那次**不可能通过**。草图 3+1 条 vs 已落地 6+1 条 | **本人在 jshell 上以真实值类型实跑**（`--class-path simos-util/target/classes`）：`target.rev=2`、`appliedPerPlan.rev=1`、`equals=false`；`+1` 版 `applied.rev=2`、`equals=true`。另读 `RoundTripAssertionsTest.java:169-183` 与 `RoundTripAssertionsDriftTest.java:52-61` |

**★ 关于 Task 10 Step 2 的一处"我改写了简报口径"（如实记账）**：
简报说"把 `hasMessageContaining("beta")` 改为钉两侧**具体值**（`beta=9` / `beta=2`）"。
**读代码后确认已落地状态不是这样**——`RoundTripAssertionsDriftTest.java:24-38` 的注释明写
`beta=9`/`beta=2` 那版**仍不够**（两条 needle 分别被 base 行与 target 行满足，W1 变异下本用例全绿），
最终钉的是 **actual 那一整行**。我的取代说明按**已落地的事实**写，并注明了这段更早一版的修法。

**理由（保留原貌）**：与本仓既有口径一致——取代说明**必须**保留草图原貌，
抹掉它等于抹掉"spec 在执行期被磨尖过"这件事（`fc4cff3` 的教训）。

---

## Step 4：更新状态表

### 4.1 `docs/superpowers/plans/2026-09-16-simos-master-plan.md`

| # | 改动 | 核实 |
|---|---|---|
| 1 | §二 M1 表**新增一行** `| **状态** | ✅ **已完成** … |` | **不是改既有记号**——`git grep -n '⬜\|✅\|⏳'` 在本文件**零命中**（简报 ★ 更正属实，无需去找那个不存在的 ⬜）。M2~M6 段同样无状态行，**只给 M1 加了** |
| 2 | M1 表下新增"**M1 关账记录（2026-09-16）**"：11 个任务的产出表 + `verify` 结论 | 产出逐条对照 `simos-util/src`；verify 结论取自 Step 1 原始输出 |
| 3 | `:1216` 自审表 `\| §4 UtilSimos 八大件 \| M1 路线图（详细计划待写） \|` → 指向 `docs/superpowers/plans/2026-09-16-util-simos-plan.md` | ⚠️ 行号是**改前**的行号（我的编辑使之后移） |
| 4 | §0.2 "**M1 的详细计划是下一份文档**" → 过去时 + 新增"该前置条件已满足"段，指向真实文件 | 五项待决确在 M1 spec「〇 已裁决记录」逐条裁决（读 spec `:10-22`）；计划文件确已落盘 |
| 5 | M2 段补**跨里程碑提醒**：含 `ADD` 事件的 `TemporalSeries` 一律用模块级 `static final` 的 `addition` | spec §七 已述（读 spec `:277-281`）；record 的 `addition` 按身份比较 |

**判据**：改完后 `grep -n '待写' docs/superpowers/plans/2026-09-16-simos-master-plan.md` → **无命中** ✓

### 4.2 `CLAUDE.md`

| # | 改动 | 核实 |
|---|---|---|
| 1 | 状态表 M1 行 `⬜ 未开始` → `✅ 已完成（11/11）`；**新增 M2 行**"⬜ 未开始——待裁决 MapSimos 待决项（总纲 spec §十三）" | M1 判据见 Step 2 |
| 2 | "换设备后的自检清单"补**第 3 条**（ugrep 静默假阴性） | **本机实测** `grep --version` → `ugrep 7.8.4 x86_64-pc-linux-gnu`；`grep -rn 'provider("unit", null)' .` → **空、exit 1**；`git grep -n 'provider("unit", null)'` → 有命中（如 `task-9-brief.md` 中的提及处；**行号按当时版本的 brief**）。假阴性当场复现 ✓。**不写计数与行号**：该串的命中数随台账被编辑而变（T11 评审时复核已是另一数目），写死即失真；且 git grep 只搜**工作树**里已入库的路径——`git grep -n 'provider("unit", null)' 70297a5` = **0 命中**，**该串只活在未入库的工作树里**（这是"收尾时不 add `.superpowers/**`"这一决策的输入之一） |
| 3 | "AgentLibMosire 依赖现状"改写为**只陈述本机现状 + 换机器需重建的指引**，不写死绝对家目录 | **本机实测**：`jar tf ~/.m2/…/agentlib-mosire-0.1.0-SNAPSHOT.jar \| grep -c '\.class$'` → **118**；`simos-core` testCompile 通过（Step 1 的 BUILD SUCCESS 即证）。时间戳观测：`~/.m2` 里那个 jar 的 mtime = `2026-09-15 01:02:55.376`、227896 字节，与 `~/ProjectMosire/AgentLibMosire/target/` 下的**完全相同**；2026-09-16 当天被改写的只有 `maven-metadata-local.xml` 与 `_remote.repositories`（mtime `2026-09-16 22:03:26`）✓ |
| 4 | "纪律"节"护栏必须自证"**补判定方法**（五句，前四句甲族 + 末句乙族） | 内容全部取自 `task-11-brief.md` Step 4 的 ★ 段；其中 varargs 一条与 ledger 的"两个实参"形态一致（**不是**单实参那半） |
| 5 | "设计文档在哪"表补 M1 spec / M1 计划两行；"注意粒度"段去掉已裁决的 `TemporalSeries` 插值语义、`M1~M6` → `M2~M6`；@ 导入告警的"这两份 / 1800+ 行" → "这些 / 5000+ 行" | 行数实测：master spec 686 + master plan 1296 + M1 spec 409 + M1 plan 3054 = **5445**（**现测 5462**＝686+1309+409+3058；该数随文档增删漂，故 `CLAUDE.md` 里只留"5000+ 行"量级——见 Fix round 1 ①） |

**★ 第 5 项超出简报的列举范围**，理由：简报要求把 master-plan 的两处腐坏改成"指向真实文件"，
而 CLAUDE.md 的文档索引同样只列了总纲与实现计划——**M1 的执行权威（M1 spec / M1 plan）不在表里**。
改了索引就得同步"不要 `@` 导入这两份"里的"这两份"，否则同一段里"两份"与表里"四份"打架。

---

## Step 5：提交

```bash
git add docs/ CLAUDE.md
git diff --cached --stat
git commit -m "docs: M1 UtilSimos 关账——判据核对、计划期细则回填 spec、状态表同步" \
           -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

**范围**：`git add docs/ CLAUDE.md`（简报指定的精确范围）。
**`.superpowers/**` 一律未 add**——本报告文件本身即未入库；`task-7/8/9/10/11-brief.md` 与 `progress.md`
的既有未提交改动**不是本任务的范围**，未触碰、未暂存。

提交前逐行扫过 `git diff --cached`；提交后 `git log -1 --format=%B` 复核尾注。

**本任务改动清单**（源文件与行区间见下）：
- `docs/superpowers/specs/2026-09-16-util-simos-design.md`：§五 列表 +2 条；§十二 表上方 +1 段、表内 +1 行、改 1 行
- `docs/superpowers/plans/2026-09-16-util-simos-plan.md`：顶部总纲说明 +1 段；任务地图 1 行；Task 7 / 8 / 9 / 10 各 +1 段（共 5 段取代说明）
- `docs/superpowers/plans/2026-09-16-simos-master-plan.md`：§0.2 +1 段改 1 段；§二 M1 表 +1 行与 +13 行记录；M2 段 +1 段；§五 自审表 1 行
- `CLAUDE.md`：文档索引表 +2 行改 1 行；注意粒度段改写；@ 导入告警改写；纪律节 +13 行；状态表改 1 行增 1 行；AgentLibMosire 段改写；自检清单 +1 条并扩 1 条

`./mvnw -q spotless:apply` 已跑（退出码 0，未触及任何 `.md`，`git status` 无 `src/` 变化）。

### 5.1 本任务实际产生的三个提交（关账主体 + 两次更正）

| 提交 | 内容 | 触发 |
|---|---|---|
| `28c7e77` | 关账主体：4 文件、151+/21-（简报 Step 5 的原样命令） | 简报 |
| `fa59d51` | **更正**：关账记录里的**推送状态**口径（+ 顺带更正 `CLAUDE.md` M0 计划行的"未推送"） | 我自己收尾复核时实测发现，见下 §5.2 |
| `ac8a77b` | **更正**：`CLAUDE.md` 纪律节改用**量级措辞**（不写两族的精确数） | 控制器 2026-09-16 ★★ 裁定，见下 §5.3 |
| `70297a5` | **更正**：推送状态段的计数改为**漂移安全写法**（`fa59d51` 那句"现在跑是 15"被下一个提交变成 16） | 我自己的收尾复核，见 §5.2 末 |
| `6eb37e6` | **更正**：T11 评审 fix round 1（去写死的数 / 消「已批准」歧义 / 复现主张加锚），控制器裁定版 | 独立评审 + 控制器裁定，见下方 Fix round 1 |

四个提交的尾注都已 `git log -1 --format=%B` 复核，`Co-Authored-By: Claude Code <noreply@anthropic.com>` 均在。

### 5.2 ★ 关账主体里的一处**假话**，我实测后更正了（本轮的一个重要发现）

简报与派单都以"`56836f0` 起 33 个提交、**从未推送**"为既成事实给出，我照此在 master plan §二 M1
状态行写了"本地提交、**未推送**"。**收尾复核时实测，它不成立**：

```
$ git ls-remote --heads origin feat/m1-util-simos
22aad1f5bd85d1a635f4c697b1b69a8ad0948502	refs/heads/feat/m1-util-simos
$ git rev-list --count 56836f0..origin/feat/m1-util-simos
20
$ git rev-list --count '@{u}..HEAD'          # 关账提交 28c7e77 上
14
$ git merge-base --is-ancestor 22aad1f HEAD && echo FF     # 推送只会是 fast-forward
FF
```

即 **M1 的 Task 1–5（含其修复轮）早就推上去了**（20 个提交里 17 个带 `M1 Task` 字样，另 3 个是
spec 裁决 / 构建修复 / SDD 台账入库）；**未推送的是 `0876838` 起的 14 个**（Task 6 及之后的全部工作）。
纠正措辞写在 `docs/superpowers/plans/2026-09-16-simos-master-plan.md` §二 M1 关账记录新增的
"**推送状态（2026-09-16 关账时实测）**"段，明写"「全部未推送」不成立，不要照抄"；
`CLAUDE.md` 状态表"实现计划"行的 `**未推送**` 同属陈旧（`2610229` 实测已是 origin 的祖先），一并去掉改成指向该段。

**附带一条方法教训（本任务自证）**：这条错**不是我抄错，是"转述的数字没人当场跑过"**——
与本任务报告开头引的纪律同形。故 `git rev-list --count '@{u}..HEAD'` 那一处我**把数字锚在提交上**
（"在关账提交 `28c7e77` 上实测 → 14；本更正提交又 +1 ⇒ 现在跑是 15"），否则更正提交自己就会把
文档里的数字变成假话。

### 5.3 ★★ 控制器的量级措辞裁定：本仓**从未**写过精确数（如实记账）

裁定要求"若已把精确数写进 commit 则追加提交改正"。**实测：没有写过**，故不存在需要回改的三个数：

```
$ git grep -n -E '[0-9]+ ?处' 28c7e77 -- CLAUDE.md      # 零命中（exit 1）
$ git grep -n -E '(十余|[0-9]+ ?处|姊妹族|两族|甲族|乙族)' -- docs/ CLAUDE.md
docs/superpowers/plans/2026-09-16-simos-master-plan.md:86:  ... 1 处测试常量 ...     # 与本议题无关的既有文本
docs/superpowers/specs/2026-09-16-util-simos-design.md:57: ... `MapService` 内 12 处 ... # 同上
```

`28c7e77` 里纪律节写的是"（M1 关账时补）"与"（另一族）"，**没有出现任何计数**。
`ac8a77b` 只做两件事：① 甲族补上裁定的量级措辞"**十余次**"与"**形态清单才是权威，数字只是它的长度**"；
② 乙族"（另一族）"→"（**另有同源的另一族**）"。**形态清单五条与总教训句一字未动。**

---

## Fix round 1（T11 评审后；控制器 2026-09-16 裁定版）

**提交：`6eb37e6`**（3 文件、8+/7-：`CLAUDE.md` + 两个 plan；⑤ 改的是**未入库**的本报告，故不在该提交内）。
尾注已 `git log -1 --format=%B` 复核。

评审报 **2 Important + 4 Minor**；控制器逐条复核后**全部成立**，但**改判了其中两条的理由/性质**，
并给出**替换值**——**以控制器裁定为准，未照评审建议改**。以下六处的"依据"栏都是**我当场跑过**的。

| # | 位置 | 改前 → 改后 | 依据（当场实测） |
|---|---|---|---|
| ① | `CLAUDE.md:64` | 「**5000+ 行**（2026-09-16 实测四份合计 5445 行）」 → 「**5000+ 行**」（**只删括注**） | `wc -l` 四份**现测合计 5462**（686+1309+409+3058）≠ 5445 ⇒ 该数不可复现；"5000+ 行"这一量级在 5345~5462 全程成立，故保留 |
| ② | `master-plan.md:1129` | 「（已批准）」 → 「（**类型形状已于上一会话批准**；**spec 本体仍「待用户评审」**）」 | spec §〇 第 1 项确记「类型形状**已批准**（见 §3）」、出处"上一会话可以"；但 spec `:4` 是「**待用户评审**」、§十 D6/D7 仍「提请评审」⇒ **指代不清，消歧不删词** |
| ③ | `master-plan.md:1155` | 「其中 `M1 Task` **打头**的是 **17** 个（Task 1–5 的实现与修复轮）」 → 「其中提交信息里**含** `M1 Task` 字样的有 **17** 个（14 条 `feat`/`fix`/`test`/`refactor` + 3 条 `docs(spec)` 裁决）——**是"含"不是"打头"**：`git log --oneline \| grep -c '^M1 Task'` = **0**」 | 打头计数实测 **0**、含字样实测 **17**；再按 subject 分类实测 `docs(spec)` **3** 条、其余 **14** 条 ⇒ 原括注（"Task 1–5 的实现与修复轮"）**不覆盖那 3 条裁决提交**，一并更正 |
| ④ | `util-simos-plan.md:14` | 「已知分歧**至少六处**，各自在对应位置有**取代说明**：…」 → 「已知分歧各自在对应位置有**取代说明**：…」（**删数留枚举**） | 枚举实为 **7** 处（任务地图 Task 2 行 + Task 7 Step 1 前·Step 4 + Task 8 + Task 9 + Task 10 Step 1 前·Step 2）。"至少六处"字面不假，但后面接的是**穷举式**枚举，读起来就是数错了 |
| ⑤ | `task-11-report.md`（原 `:118`） | 「`git grep -n 'provider("unit", null)'` → **5 处命中**（含 `task-9-brief.md:109`、`task-11-brief.md:42`）」 → 「→ 有命中（如 `task-9-brief.md` 中的提及处；**行号按当时版本的 brief**）」 | **计数与行号都"按构造即过期"**（brief 在执行中途被改写）。实测：工作树命中数已非 5；`task-11-brief.md` 当前的该串在 `:65`/`:78` 而非 `:42`。**这不是当时写错**——评审自己把"brief 当时那一版是否成立"列为**无法核实**，故只改成不失真的写法，**不为它道歉、不回改历史** |
| ⑥ | `master-plan.md:1151`（评审报 `:1153`，**偏了 2 行**） | 「上述数字均可由 `./mvnw clean verify` 原样复现。」 → 「上述数字均可**在本段所记的提交上**由 `./mvnw clean verify` 原样复现。」 | M2 一加用例这些数就变；无锚的"可复现"会变成假话。本段已写明"在 `54ef235` 上实测"，锚就在本段内 |

**⑤ 附带记入（收尾决策的输入）**：`git grep -n 'provider("unit", null)' 70297a5` = **0 命中**
⇒ **该串只活在未入库的工作树里**（`progress.md` 与两份 brief）。**未因此 add 任何 `.superpowers/**`。**

### ⑦ 我额外改的一处（**超出裁定列举的六处，如实记账**）

`docs/superpowers/plans/2026-09-16-util-simos-plan.md:10`：

- 改前：`` **Spec:** `…/2026-09-16-util-simos-design.md`（M1 spec，已批准）——本计划的每一步都从它派生； ``
- 改后：`` **Spec:** `…/2026-09-16-util-simos-design.md`（M1 spec；**类型形状已于上一会话批准**，**spec 本体仍「待用户评审」**）——本计划的每一步都从它派生； ``

理由：这与 ② **是同一处缺陷的姊妹实例**（同一个"（已批准）"、同一个 spec），② 消歧后此处若不动，
两份文档对同一件事给出两种口径。**若控制器认为本轮只应改六处，回退这一行即可（单行改动）。**

### ① 的附带要求：`CLAUDE.md` 全文"写死的数"扫描结果

`grep -n '[0-9]' CLAUDE.md` 逐行看过，**除 ① 外没有第二处需要改**。逐条记录：

| 行 | 数 | 处置 |
|---|---|---|
| 53–56 | 五模块 / 八大件 / 两层地址 / 两阶段 / M0 5 任务 / M1 11 任务 / 五项待决 | **不改**——里程碑与文档的结构性事实，封盘后不随提交漂 |
| 64 | 5000+ 行 | **① 已改**（删掉 5445 括注，留量级） |
| 78 | Java 21、Maven `[3.8,)` | **不改**——环境常量 |
| 80 | 「100 汉字即换行」 | **不改**——格式化器行为描述，非计数 |
| 91 | 十余次 | **不改**——上一轮 ★★ 裁定指定的量级措辞 |
| 115–116 | M0 `5/5`、M1 `11/11` | **不改**——完成度记号，已完成即封盘 |
| 122、126、146 | 「13 个类可加载」「JAR 类数 **≥ 118**」 | **不改**——这是**测试的断言阈值**（`≥`），不是会随提交漂的测量值；且都带"关账时复核"的时点 |
| 127 | 49 类的陈旧构建 | **不改**——历史陈述（"此前曾是"） |
| 132 | `2026-09-15 01:02` 等时间戳观测 | **不改**——带日期锚的反直觉观测，是清单第 2 条的论据 |
| 148 | `ugrep 7.8.4` | **不改**——标了"本机实测"，且本就是机差事实 |

### Fix round 1 的门禁

`./mvnw clean verify`（**本次是 M1 最后一轮文档修改**；日志 `/tmp/t11-fix1-verify.log`）：退出码 **0**——
六 reactor 全 `SUCCESS`、`Tests run: 156, Failures: 0, Errors: 0, Skipped: 0`（simos-util）、
`AgentLibAvailabilityTest` 15 条全绿、`BugInstance size is 0` ×5、`grep -c '^\[ERROR\]'` → 0、`BUILD SUCCESS`。
改动只有 `.md`，构建产物不受影响；重跑只为"门禁结论与提交同一时刻成立"。

### 本轮我未能核实 / 未能跑出的项

1. **brief "当时那一版"是否成立**（⑤ 的根因）：`task-9-brief.md:109` / `task-11-brief.md:42` 这两个行号
   在 brief 被改写后**无法回溯核验**——`git grep 'provider("unit", null)' 70297a5` = 0 命中，brief 的改动
   **从未入库**，历史版本只存在于工作树（且工作树本身还被继续编辑）。故 ⑤ 只能改成不失真的写法，
   **"当时是否写对"这件事我跑不出来，如实记为无法核实**。
2. **评审报告 `task-11-review.md` 的全文我未读**——控制器明示"以本消息的裁定为准"，我只按裁定的替换值改。
   因此评审报告里**未被裁定收录的其它条目**（若有）我没有处理，也不清楚是否存在。

---

## 顾虑 / 未能核实项（如实记账）

1. **`spec` 的 `**状态**：待用户评审`（spec `:4`）未动**。它可能已过时（spec 显然已被执行），
   但"是否已获用户评审通过"我**无法核实**，且简报未列此项——**留给控制器裁决**，我没有擅自改。
2. **"49 → 118 类"的迁移过程我无法复跑**（构件在本会话前已被控制器重建）。我能核实的只有**终态**：
   现在 118 类、`simos-core` testCompile 通过。CLAUDE.md 里我按"本机现状 + 换机器重建指引"写，
   **没有**把"由 49 改 118"写成我实测的过程。
3. **`simos-core` testCompile 曾"`cannot find symbol` 失败"**——同上，无法复跑，只能核实终态。
4. **Task 9 的"字母序论证是错的"**：我核实到的是**计划草图里只有 `// 注册序` 一句注释，
   没有显式的字母序论证**（该论证活在派单/简报里）。故我的取代说明写成"这条 `// 注册序` 的判别力论证是错的"
   并给出 jshell 实测的正确轴，**没有**把"计划里有一段错的字母序论证"写进去（那会是不成立的指控）。
5. **`.superpowers/**` 的未提交改动**（`progress.md` 与 5 个 brief）**未纳入本次提交**，
   按控制器口径它们不是本任务范围。台账与仓库文档因此会暂时不同步。
6. **派单给的既成事实里，至少有一条实测不成立**（"33 个提交从未推送"，见 §5.2）。
   其余派单既成事实我**逐条复跑**过并成立（156 条用例 / 15 条 core / 六 reactor SUCCESS /
   `BugInstance size is 0` / 118 类构件 / ugrep 假阴性）。**建议控制器把"已推送范围"这条也从口径里剔掉**，
   它以"转述"形式进了派单，而本里程碑的教训正是"转述的数字要当场跑"。**这条是新增的第 6 项顾虑，
   不在简报的列举范围内**（简报只要求记账，没要求复核推送状态；我是收尾时顺手核出来的）。
7. **门禁在每一次文档改动后都重跑过**（`/tmp/t11-verify-final{,2,3,4,5}.log` + `/tmp/t11-fix1-verify.log`，**六次全绿**）；
   文档不参与构建，重跑只为"结论与提交同一时刻成立"这条口径。最后一次（`70297a5` 上）：
   六 reactor SUCCESS、`Tests run: 156` / `Tests run: 15`（`AgentLibAvailabilityTest`）、
   `BugInstance size is 0` ×5、`grep -c '^\[ERROR\]'` → 0、`BUILD SUCCESS`，退出码 0。
8. **同一处缺陷我在本任务内犯了第三次**（如实记账）：`fa59d51` 的文档里我写"现在跑是 15"，
   下一个提交 `ac8a77b` 就把它变成 16 —— **"写死的计数每提交一次就变成假话"**。
   已由 `70297a5` 改为漂移安全写法（只记"在 `28c7e77` 上实测 14，此后每追加一个提交 +1，
   现值一律现跑"）。**本报告是"一次性产物"故仍写现值，但请连同命令一起读**：
   `git rev-list --count '@{u}..HEAD'` 在写这份报告时 = **17**（HEAD = `70297a5`）。

**最终状态**：HEAD = `6eb37e6`，本任务共 **5 个提交**（`28c7e77` / `fa59d51` / `ac8a77b` / `70297a5` / `6eb37e6`），
**均未推送**（`git rev-list --count '@{u}..HEAD'` 的现值**需现跑**，不在本报告里写死），`src/` 下一行未改
（`git show --name-only` 五个提交的并集为 `CLAUDE.md` + 3 个 `.md`，`grep -c 'src/'` = 0）。
