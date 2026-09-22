# Task 11 评审：M1 关账（纯文档）

**评审对象**：`review-54ef235..70297a5.diff`（4 个提交：`28c7e77` / `fa59d51` / `ac8a77b` / `70297a5`）
**被评报告**：`task-11-report.md`
**评审者**：T11 评审者（只读；未修改任何文件、未提交、未推送、未派发子代理）
**日期**：2026-09-16
**门禁**：未重跑 `./mvnw clean verify`（控制器已核实 `src/` 0 行改动，且下文以构建产物取证）

---

## 判决一：规格符合性

**总判：已满足**（简报 Step 1~5 逐条满足；两处超出简报范围的改动，一处判为「该做且做对」，一处判为「该做但引入了新缺陷」，见发现 1）。

| 简报要求 | 判定 | 我的核验命令与原始输出摘录 |
|---|---|---|
| Step 1 全量门禁 `clean verify` 绿 | **已满足**（未重跑，以产物取证） | `grep -c 'BUILD SUCCESS' /tmp/t11-verify-final{,2,3,4,5}.log` → 五个日志各 `1`；各日志 `grep -c '^\[ERROR\]'` → `0`、`BugInstance size is 0` → `5`、`Tests run: 156` → `1`。产物时间戳与末次 verify 对齐：`simos-util/target/surefire-reports/*.txt` mtime `22:53:00`、`simos-core/.../AgentLibAvailabilityTest.txt` `22:53:05`、`/tmp/t11-verify-final5.log` `22:53:06`。六个 reactor 全 `SUCCESS`（`grep -E 'SUCCESS \[' final5.log`）。 |
| Step 2 判据：八大件各有单测 | **已满足** | `find simos-util/src/test -name '*Test.java' \| wc -l` → **17**。逐类 surefire：`AddressParse(38) AddressQuote(9) AddressTolerantParse(15) SubjectId(4) ResolvedSubject(5) QueryResult(2) SimosTimestamp(5) TimeRange(4) StateRef(9) SnapshotProtocol(2) SimulationState(8) InMemoryInfoSystem(14) TemporalSeries(16) ResolverRegistry(8) FacetRegistry(10) RoundTripAssertions(6) RoundTripAssertionsDrift(1)`，合计 **156**，全 0 failures。 |
| Step 2 判据：冻结样例逐条往返 15 条 | **已满足** | `grep -o 'frozenSamplesRoundTrip[^"]*' TEST-…AddressParseTest.xml` → `[1]`…`[15]`，`grep -c '<failure\|<error'` → **0**。被测源码 `sed -n '/@ValueSource/,/})/p' AddressParseTest.java` 数得 **15** 个字符串。 |
| Step 2 判据：往返框架有故意漂移的失败用例 | **已满足** | `RoundTripAssertionsDriftTest` = 1 条、绿（surefire）。读了该文件 `:20-42`：断言 `isInstanceOf(AssertionError.class).hasMessageContaining("  actual    = " + expectedActual)`。 |
| Step 3-3 `ChangeSet`/`Command` 的落点表述（**不新建测试类**） | **已满足** | 打开文件核对：`SnapshotProtocolTest.java:8` javadoc 逐字含「ChangeSet/Command 都是单方法接口」；`:27` 起 `changeSetAndCommandExposeTheirStamps()` 对 `baseRevision()` / `expectedRevision()` 各自断言。spec §十二 该行的「覆盖」列已按简报改写，且未新建测试类 ✓。 |
| Step 3-1 §五 `TimeRange.to` 严格晚于 `from` | **已满足** | 打开 `TimeRange.java:13-23`：紧凑构造器内 `end.compareTo(from) <= 0` → `IllegalArgumentException`；`since()` = `Optional.empty()`；`to` 为 `null` 走 `Objects.requireNonNull(to,"to")`。spec 新增条与代码逐字相符。 |
| Step 3-2 §五 `equals`/`compareTo` 口径分叉 | **已满足** | 打开 `SimosTimestamp.java:14`（record 默认 `equals`，含 `calendarLabel`）、`:34-37`（`compareTo` 只 `Long.compare(tick, …)`）。spec 新增条相符。 |
| Step 3-3 §十二 补 `TimeRangeTest` + 「表格不是证据」段 | **已满足** | 表内新增 `TimeRangeTest` 行 ✓；表上方新增「本表是下限」「表格也不是证据…实际用例一直在 `SnapshotProtocolTest` 里」段 ✓（`sed -n '366,372p'` 可见）。 |
| Step 3-4 计划草图加取代说明、**保留草图原貌** | **已满足** | `grep -c '取代说明（2026-09-16 执行期' util-plan.md` → **6** 段；任务地图 Task 2 行为行内「执行期更正」。`git show 28c7e77 -- …util-simos-plan.md \| grep '^-'`（去掉 `---`）→ **仅 1 行**删除，即任务地图那行，且原文「14 条」被完整保留在新行内 ⇒ 草图未被重写。 |
| Step 3-4 取代说明所引计划行号属实 | **已满足**（我逐条在 `54ef235` 上打开） | `:2479` = `assertThat(registry.facetNames()).containsExactly("unit", "social"); // 注册序` ✓；`:2517` = `provider(String namespace, String facetName, FacetEntry... entries)` ✓（第 1 参确是死参）；`:2639` = `putIfAbsent(facetName, provider)` ✓；`:2646-2647` = `facetNames()` 返回 `List.copyOf(providers.keySet())` ✓（确为 **facet 名**，与 `:2479` 的断言互斥，报告所称「自相矛盾」成立）；`:2765-2781` = `ToySnapshot.apply` 内 `new StateRef(base.ref().branch(), changeSet.baseRevision())` ✓。 |
| Step 4 master plan §二 M1 加状态行 + 11 任务产出 + verify 结论 | **已满足** | `:1127` 新增 `\| **状态** \| ✅ **已完成** …`；其下「M1 关账记录」含 11 行产出表 + verify 结论。**不是改既有记号**：`git show 54ef235:…master-plan.md \| grep -n '⬜\|✅\|⏳'` → **exit 1 零命中**（简报 ★ 更正属实）；改后 `grep -n '⬜\|✅\|⏳'` → 仅 `:1127` 一行（只给 M1 加）✓。 |
| Step 4 两处腐坏文本 | **已满足** | `grep -n '待写' …master-plan.md` → **exit 1 零命中** ✓。`:1216` 原句在 54ef235 确为 `\| §4 UtilSimos 八大件 \| M1 路线图（详细计划待写） \|` ✓；§0.2 原句在 54ef235 `:50` 确为「**M1 的详细计划是下一份文档**」✓，改后为过去时 + 指向真实文件，且原段保留。 |
| Step 4 M2 跨里程碑提醒（`addition` 共享实例） | **已满足** | M2 段下新增一段 ✓；依据 spec `:277-281`（54ef235 打开核对：「**含 `ADD` 事件的序列，`addition` 必须是共享实例（模块级 `static final` 常量）**」）✓。 |
| Step 4 `CLAUDE.md` 状态表 M1 ✅ / M2 行 | **已满足** | `CLAUDE.md:116` M1 ✅（11/11）、`:117` M2 ⬜ 待裁 MapSimos 待决项 ✓。 |
| Step 4 `CLAUDE.md` 自检清单第 3 条（ugrep） | **已满足，且当场复现** | `grep --version` → `ugrep 7.8.4 x86_64-pc-linux-gnu` ✓；`grep -rn 'provider("unit", null)' .` → **空、exit 1**（假阴性复现）✓；`grep --hidden --no-ignore-files -rn 'provider("unit", null)' .` → **15 处命中**（该条推荐的替代法确实可用）✓；`.superpowers/sdd/.gitignore` 内容为 `*`，故 `.superpowers/sdd/**` 确被 ignore ✓。 |
| Step 4 `CLAUDE.md` AgentLibMosire 段改写 | **已满足** | `jar tf ~/.m2/…/agentlib-mosire-0.1.0-SNAPSHOT.jar \| grep -c '\.class$'` → **118** ✓，与 `AgentLibAvailabilityTest.MIN_EXPECTED_CLASSES = 118` 一致 ✓。mtime 观测逐字属实：`~/.m2` 内 jar = `2026-09-15 01:02:55.376` / 227896 B，源 `~/ProjectMosire/AgentLibMosire/target/…jar` = `2026-09-15 01:02:55.376677458` / 227896 B，同目录 `maven-metadata-local.xml` 与 `_remote.repositories` = `2026-09-16 22:03:26` ✓。改写后不写死绝对家目录 ✓。 |
| Step 4 `CLAUDE.md` 纪律节按控制器 ★★ 裁定 | **已满足** | 详见下文「专项核验 A」。 |
| Step 5 提交（不推送、逐行扫、尾注） | **已满足** | `git show --name-only` 四提交并集 = `CLAUDE.md` + 3 个 `.md`，`git diff --name-only 54ef235..70297a5 \| grep -c 'src/'` → **0** ✓；`.superpowers/**` 未入库 ✓；四提交 `git log -1 --format=%b` 均含 `Co-Authored-By: Claude Code <noreply@anthropic.com>` ✓；`git rev-list --count '@{u}..HEAD'` = 17 = 14（28c7e77 时）+ 4 提交 − 1 ✓，确未推送。 |
| 超出简报范围 ①：util 计划「14 条冻结样例」→ 15 | **该做，且做对了** | 见下文「专项核验 B」。 |
| 超出简报范围 ②：`CLAUDE.md` 文档索引 + `@` 导入告警同步 | **该做，但引入新缺陷** | 见发现 1。索引补 M1 spec / M1 plan 两行本身必要（M1 的执行权威原先不在表里），`@` 告警「这两份」→「上面这些」的同步也必要；但同步时写进了一个不可复现的精确数。 |

---

## 专项核验 A：`CLAUDE.md` 纪律节 vs 控制器 ★★ 裁定

| 裁定要求 | 判定 | 证据 |
|---|---|---|
| 甲族写量级措辞「十余次」 | ✅ | `CLAUDE.md:91`「M1 期间反复查出**十余次**判别力缺陷」 |
| 姊妹族写「（另有同源的另一族）」 | ✅ | `CLAUDE.md:103`「**（另有同源的另一族）**"我验过了"与"我记得是这样"必须分开**」 |
| 保留「形态清单才是权威，数字只是它的长度」 | ✅ | `CLAUDE.md:92` 末句逐字在 |
| 不写两族精确数 | ✅ | `git show 70297a5:CLAUDE.md \| sed -n '/^## 纪律/,/^## 当前状态/p' \| grep -nE '[0-9]+ ?(处\|次\|个)'` → **零命中** |
| 五条形态清单与总教训句一字未动 | ✅ | `diff <(git show 28c7e77:CLAUDE.md \| sed -n '/^## 纪律/,/^## 当前状态/p') <(git show 70297a5:CLAUDE.md \| …)` → 只有 **2 处**差异：① 引导句（`（M1 关账时补）：` → `：M1 期间反复查出十余次…数字只是它的长度：`）；② 第 5 条括注（`（另一族）` → `（另有同源的另一族）`）。第 1~4 条正文、第 5 条正文、总教训句**逐字未动** |
| 28c7e77 里确实没有出现过精确数 | ✅（报告 §5.3 自陈属实） | `git show 28c7e77:CLAUDE.md \| sed -n '/^## 纪律/,/^## 当前状态/p'` 目视确认写的是「（M1 关账时补）」与「（另一族）」，无计数；`git grep -n -E '[0-9]+ ?处' 28c7e77 -- CLAUDE.md` → **exit 1** |
| 「补五句，控制在十行内」 | ⚠️ 未达成（3 行超出） | 五条现占 `CLAUDE.md:93-105` 共 **13 行**（第 1 条第 4 行、第 2/3/4 条各 2 行、第 5 条 3 行）。判定：该上限写于 ★★ 裁定**之前**，而裁定要求「形态清单原样保留」，两相冲突时以裁定为准——此项**不建议行动**，仅备案 |

**★ 一处与控制器指令不符的核验前提（如实记账）**：控制器要求「核验形态清单的五条与 diff 之外的原句一致（**原句在 `git show 54ef235:CLAUDE.md`**）」。**该文件里没有形态清单**——54ef235 的「纪律」节只有 5 行，
```
- **护栏必须自证**：任何 enforcer 规则、格式门禁、测试不变量，都要有一个**故意违规**的用例
  证明它真的会响。没有这个的护栏等于装饰
```
五条形态清单是**由 `28c7e77` 引入**的。故我按等价口径核验：**28c7e77（引入时）vs 70297a5（现状）**，并对 `ac8a77b` 单独取证（见上表）。**这一条我未能按指定方式核实**，理由是所引原句在指定位置不存在。

---

## 专项核验 B：两处超范围改动

### B-1「14 条 → 15 条」——该做且做对

| 事实 | 我的核验 |
|---|---|
| 计划本行原写 14 条 | `git show 54ef235:…util-simos-plan.md` 任务地图 Task 2 行 = 「解析器 + **14** 条冻结样例核对」✓ |
| 同一计划的 Task 2 Step 5 自己写 15 条（即改前已自相矛盾） | 54ef235 计划 `:775` = `Expected: PASS（**15** 条冻结样例 + 段判定用例 + 11 条非法形态）` ✓ |
| spec §3.6 表是「14 行、15 条」 | 逐行数该表：`map:Map1`(1) `hex.4_3`(2) `terra.Grass`(3) `terra.Grass:height`(4) `region.Nation.区域A`(5) `…:hexes`(6) `conn.river.r-f82a`(7) `social:…:population`(8) `…population_growth`(9) `unit:U:member`(10) `unit:U:equipment.步枪`(11) `unit:U:hex` / `unit:U:speed`(12，**同占一行两条**) `agent:bind.b-f82a`(13) `agent:map:Map1:region.Nation.区域A`(14) → **14 行、15 条** ✓ |
| 已落地是 15 | `@ValueSource` 15 个字符串 ✓；surefire XML `[1]..[15]` ✓ |
| 改后保留原貌 | 新行把「14 条」完整保留在括号前，更正写在括注里 ✓ |

**判定**：该改动是**必要的文档腐坏修复**，且证据链完整（四处独立来源互证）。超出简报列举范围但**不是**擅自扩权。

### B-2 `CLAUDE.md` 文档索引 + `@` 告警同步——该做，但**引入了同类新缺陷**

- 必要性成立：改前索引只有总纲 spec 与实现计划，**M1 的执行权威（M1 spec / M1 plan）不在表里**，而这两个文件正是 M2 起「先确认待决项已裁决」要读的；改了索引不改「不要 `@` 导入**这两份**」会让同段内「两份」与表里「四份」打架 ✓。
- 「注意粒度」段删去已裁决的 `TemporalSeries` 插值语义**正确**：`spec :15` 第 4 项「TemporalSeries 的插值/事件语义」状态列 = 「本会话裁决」✓。
- **但**同步时写进了一个不可复现的精确数（发现 1）。

---

## 判决二：任务质量 —— 发现清单

### 发现 1【Important】`CLAUDE.md:64` 的「四份合计 5445 行」不可复现，且**不是** 70297a5 声称已消除的那一类漂移

**文件:行**：`CLAUDE.md:64`（`（2026-09-16 实测四份合计 5445 行）`）

**证据**（同一组四个文件：master spec / master plan / M1 spec / M1 plan）：
```
$ for c in 54ef235 28c7e77 fa59d51 ac8a77b 70297a5; do printf "%s: " $c; for f in <四份>; do printf "%s " "$(git show $c:$f | wc -l)"; done; echo; done
54ef235: 686 1268  398 2993      # 合计 5345
28c7e77: 686 1297  409 3058      # 合计 5450
fa59d51: 686 1308  409 3058      # 合计 5461
ac8a77b: 686 1308  409 3058      # 合计 5461
70297a5: 686 1309  409 3058      # 合计 5462
$ wc -l <四份>                    # 当前工作树
   686  1309  409  3058           # 合计 5462
```
⇒ `5445` **在任何一个提交状态、以及当前工作树上都对不上**。报告 §4.2 第 5 项给出的分解式（`686 + 1296 + 409 + 3054`）同样对不上任何提交：28c7e77 时 master plan 是 **1297**、M1 plan 是 **3058**。我**无法核实**该数是在哪一刻测出的（工作树已无那个快照）。

**为什么不只是「一个过时的数」**：`70297a5` 的提交信息自称「计数改为**漂移安全写法**…不再写任何当前值」，而**同一份 diff 里另一个写死的、会漂的数原样留在了永久上下文里**——即控制器问的「是否真的消除了这类漂移（而不是只改了那一处）」的答案是：**没有，只改了推送状态那一处**。

**建议动作**：把括注删掉，只留量级措辞（「5000+ 行」本身在 5345~5462 全程成立，是稳的）；若想保留可复现性，按 `70297a5` 自己的口径写成「四份合计见 `wc -l` 现跑」（不写数），或锚定到某个提交 SHA。

---

### 发现 2【Important】新增的「（已批准）」与被引文档自身的状态、以及控制器本轮的裁定相矛盾

**文件:行**：`docs/superpowers/plans/2026-09-16-simos-master-plan.md:1129`
（`**M1 关账记录（2026-09-16）**：spec \`…util-simos-design.md\`（已批准）；`）

**证据**：
```
$ git show 54ef235:docs/superpowers/plans/2026-09-16-simos-master-plan.md | grep -n '已批准'
（零命中）                              # ⇒「（已批准）」是本 diff 新写的
$ sed -n '4p' docs/superpowers/specs/2026-09-16-util-simos-design.md
**状态**：待用户评审                     # 被引文档自己说「待用户评审」
$ sed -n '356,360p' docs/…util-simos-design.md
| D6 | … | 提请评审 |
| D7 | … | 提请评审 |                     # 偏离表里两行仍是「提请评审」
```
且控制器本轮明确裁定：spec `:4` 的 `待用户评审`**不动**，理由是「这是只有用户能提供的事实，本会话未收到该批准」。同一份 diff 一边遵守该裁定、一边在另一处断言「已批准」，口径不一致。

**减轻情节（如实记账）**：`docs/…util-simos-plan.md` 的 `Spec:` 行**早就**写着「（M1 spec，已批准）」（54ef235 已如此，非本任务引入）；`spec §〇` 第 1 项也确实记有「类型形状**已批准**…上一会话"可以"」。故这不是本任务发明的口径，只是把既有的宽口径又复制了一处。

**建议动作**：与 `CLAUDE.md:55` 的写法对齐，改为「（已执行）」；若确要保留「已批准」，请只用于 §〇 已记有出处的「类型形状」范围，并避开与 `:4` 的直接冲突。

---

### 发现 3【Minor】推送状态段：「`M1 Task` **打头**」的措辞与实际不符（数字对、措辞错）

**文件:行**：`docs/superpowers/plans/2026-09-16-simos-master-plan.md:1155`

**证据**：
```
$ git log --format='%s' 56836f0..origin/feat/m1-util-simos | grep -c '^M1 Task'
0                                        # 没有任何提交的标题以 "M1 Task" 开头
$ git log --format='%s' 56836f0..origin/feat/m1-util-simos | grep -c 'M1 Task'
17                                       # 17 这个数只在「信息里含该字样」的口径下成立
$ git log --format='%h %s' 56836f0..origin/feat/m1-util-simos | grep -v 'M1 Task'
22aad1f docs(sdd): M1 的 SDD 台账与任务证据入库…
f7ac329 docs(spec): SegmentedSeries 定为 record…（Task 7 前置裁决）
c7d32cf fix(build): 删去 spotbugs 4.10.x 已移除的 fork 参数…
```
同一句还写「（Task 1–5 的实现与修复轮）」——17 个里有 **3 个**是 `docs(spec)` 裁决提交（`b55be33` / `3ebb891` / `4109eb5`，标题形如「…（M1 Task 2 裁决）」），不属「实现与修复轮」。**「另 3 个是 spec 裁决 / 构建修复 / SDD 台账入库」这一句本身正确**（那 3 个确为 `f7ac329`/`c7d32cf`/`22aad1f`）。

**建议动作**：改为「提交信息里含 `M1 Task` 字样的 17 个（含 3 条 spec 裁决）」。

---

### 发现 4【Minor】取代说明总数：「至少六处」与实际插入的七处并列

**文件:行**：`docs/superpowers/plans/2026-09-16-util-simos-plan.md:14`

**证据**：
```
$ grep -c '取代说明（2026-09-16 执行期' docs/…util-simos-plan.md
6                                        # 6 个引用块
+:14 枚举的位置 = 任务地图 Task 2 行、Task 7 Step1 前、Task 7 Step 4、Task 8 Step1 前、Task 9 Step1 前、Task 10 Step1 前、Task 10 Step2
                                        # ⇒ 7 处（Task 2 那处是行内「执行期更正」，非引用块）
```
「至少六处」因「至少」二字并非假话（7 ≥ 6），但紧接着的枚举（用「·」把 Task 7 与 Task 10 各拆成两点）读起来像 5 处，与 6/7 都不等。属可读性问题。

**建议动作**：写成「已知分歧七处（6 段取代说明 + 任务地图 1 行）」，或直接把枚举与数对齐。

---

### 发现 5【Minor】报告自身：「`git grep` → 5 处命中」与 `task-11-brief.md:42` 两处引用未当场复跑

**文件:行**：`task-11-report.md:118`（并同源于 `task-11-brief.md:65`）

**证据**：
```
$ git grep -n 'provider("unit", null)' -- '*.md'
…/progress.md:753, :790, :864, :887, :921        # 5 处
…/task-11-brief.md:65                             # 「已被咬过一次」段
…/task-11-brief.md:78                             # 五条之第 1 条
…/task-9-brief.md:109                             # assertThatThrownBy(… register(provider("unit", null)))
                                        # ⇒ 8 处，不是 5 处
$ sed -n '42p' .superpowers/…/task-11-brief.md
   - Task 7 Step 4 画的是 `public final class SegmentedSeries<T>`…   # 不含该串
$ wc -l .superpowers/…/task-11-brief.md
107                                     # 「:42」不在该串上；该串在 :65 与 :78
```
更强的反证——**按提交状态看，该串根本不存在**：
```
$ git grep -n 'provider("unit", null)' 70297a5     # 该提交的树
（零命中）
$ git show 70297a5:.superpowers/…/task-11-brief.md | wc -l
50                                     # 入库的是「加料前」的 50 行版本
$ git show 70297a5:.superpowers/…/task-11-brief.md | sed -n '109p'   # 针对 task-9-brief
（见上，该版本内无此串）
```
（`.superpowers/sdd/**` 确被 `-f` 追加入库，故 `git grep <rev>` 会搜到它们——已用 `git grep -c 'FacetRegistry' 70297a5` 验证其不会静默跳过该目录。）

⇒ `CLAUDE.md` 里那条**是对的**：它只说 ugrep 会静默假阴性、改用 `git grep`（我当场复现：`grep -rn … .` 空 + exit 1；`git grep` 有命中）✓。**错的**是简报/报告引的那两个 `file:line` 与「5 处」这个数——而它们**不在本次交付的四个文件里**，故对交付物不扣分。

**未能核实**：该引用在**写下那一刻**是否成立。`task-11-brief.md` 未入库、且在执行中途被改写过（mtime `22:50:57`，落在 `28c7e77` 22:48:02 与 `ac8a77b` 22:51:56 之间），**没有任何快照**能证明或证伪「当时第 42 行确实含该串」。我能确证的只有两个端点：**现在不对**（:42 无该串、命中 8 处）、**入库状态不对**（70297a5 的树零命中）。

**建议动作**：（若仍需引用）改成不写行号的形式——「`git grep` 一搜即得（命中处含 `task-9-brief.md` 与 `task-11-brief.md`）」，或每次现跑；并把「5 处」改成「现值现跑」。**这正是该段自己要教的东西**，因此值得改。

---

### 发现 6【Minor】关账记录末句「原样复现」会随 M2 首次加测试即失效

**文件:行**：`docs/superpowers/plans/2026-09-16-simos-master-plan.md:1153`

**证据**：原文「…上述数字均可由 `./mvnw clean verify` **原样复现**」。而同句前半已锚定「在 `54ef235` 上实测」✓（`156 / 17 类 / 15 / BugInstance 0` 我已用 surefire 产物与 `/tmp/t11-verify-final5.log` 逐项复核成立）。问题只在末四字：M2 一加用例，`156` 就不再是 `clean verify` 的输出。

**建议动作**：删「原样复现」，或改成「在同一提交 `54ef235` 上可复现」。

---

## 附：我复核但**未发现**问题的重点项（避免下一位评审重复劳动）

- **推送状态段**（master plan `:1157-1166`）：逐条重跑，**全部成立**——
  `git ls-remote --heads origin feat/m1-util-simos` → `22aad1f5bd85d1a635f4c697b1b69a8ad0948502` ✓；
  `git rev-list --count 56836f0..origin/feat/m1-util-simos` → **20** ✓；
  其中含 `M1 Task` 字样 **17** ✓、不含 **3** ✓；
  `git rev-list --count '@{u}..HEAD'` 在 `28c7e77` 上 → **14** ✓（现在 → 17，与「此后每追加一个提交 +1」完全吻合：`fa59d51`=15、`ac8a77b`=16、`70297a5`=17，逐提交实测）；
  未推送段首个提交 = `0876838`（`git rev-list --reverse '@{u}..HEAD' | head -1`）✓；
  `git merge-base --is-ancestor 22aad1f HEAD` → 成立 ✓。
  ⇒ **70297a5 的漂移安全写法对这一句是有效的**：20/17 由两个固定 SHA 夹住、14 锚在 `28c7e77` 上并带「+1」规则，均不会漂。**唯一残留的写死计数在别处**（发现 1）。
- **`fa59d51` 的更正本身正确**：`2610229` 实测已是 `origin/feat/m1-util-simos` 的祖先（`git merge-base --is-ancestor 2610229 origin/feat/m1-util-simos` → 真），故 `CLAUDE.md` 去掉「未推送」是必要的腐坏修复 ✓。
- **`Co-Authored-By: Claude Code <noreply@anthropic.com>`**：四个提交全部带 ✓。
- **`src/` 零改动、`.superpowers/**` 未入库、四提交范围精确** ✓（`git diff --name-only 54ef235..70297a5` 恰为 4 个文件）。
- **报告所引代码行号抽样全部属实**：`SimosTimestamp.java:14/34-37`、`TimeRange.java:13-23`、`SnapshotProtocolTest.java:8/27`、`FacetRegistry.facetNames()` 返回 `List.copyOf(providers.keySet())`、`FacetRegistryTest` 的 `provider(String facetName, FacetEntry...)`（死参已删）、`RoundTripAssertionsDriftTest` 钉 `"  actual    = " + expectedActual`（报告 §3.2 ★ 所称「不是 `beta=9`/`beta=2`」属实）。
- **五个 verify 日志**：`/tmp/t11-verify-final{,2,3,4,5}.log` 全绿（各 `BUILD SUCCESS` 1、`^\[ERROR\]` 0、`BugInstance size is 0` 5、`Tests run: 156` 1）✓。

---

## 未能核实的项（明确列出，不以推测填空）

1. **「5445」的原始测量时刻**——工作树无该快照；我只能证伪「它与任何提交状态或当前工作树相符」。
2. **`task-11-brief.md:42` 在写下那一刻是否成立**——该文件未入库且中途被改写，无快照。只能确证「现在不对」与「入库树零命中」。
3. **「49 → 118 类」的重建过程**——构件在本会话前已被重建，我只能核实终态（118 类、`simos-core` testCompile 通过）。与报告 §顾虑 2 一致。
4. **`simos-core` testCompile 曾 `cannot find symbol` 失败**——同上，无法复跑，只能核实终态。
5. **完整 `./mvnw clean verify`**——按派单指示未重跑（`src/` 零改动），改以构建产物 + 五个日志取证；「六模块全 SUCCESS / 156 / 15 / BugInstance 0」逐项有产物支撑。
6. **控制器「从 spec 表格直接推出缺两个件」的推理过程**——过程不可执行，只能核实其结论面（表格确无该两件的落点行、用例确在 `SnapshotProtocolTest`）。与报告 §Step 2 ★ 一致。
7. **控制器指定「形态清单原句在 `git show 54ef235:CLAUDE.md`」**——**该处不存在形态清单**（54ef235 的纪律节无五条）。我按等价口径改为对 `28c7e77`（引入时）取证。
8. **`task-11-brief.md` 在 T11 执行中途被改写（mtime 22:50:57）是否影响执行者读到的版本**——只能确认改写落在四个提交之间，无法确认执行者读的是哪一版。

---

## 结论

- **规格符合性：已满足**。简报 Step 1~5 逐项满足，两处超范围改动一处判「该做且做对」（14→15，四处互证）、一处判「该做但引入新缺陷」（发现 1）。纪律节对控制器 ★★ 裁定的遵守**逐条成立**，`ac8a77b` 精确只动了 2 行。
- **任务质量：良，但有 2 处 Important**。文档可读性与既有风格一致；取代说明「保留草图原貌」做到了（util plan 仅 1 行删除，原文完整留存）；主要问题是**漂移安全写法未做彻底**（发现 1）与**新写了一个与被引文档状态冲突的「已批准」**（发现 2）。
- **建议处置**：发现 1、2 各一个词/一句的修改即可闭合，可合并为一个 `docs:` 更正提交；发现 3~6 酌情随同处理。
