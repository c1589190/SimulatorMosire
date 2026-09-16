### Task 11: M1 关账

**Files:**
- Modify: `docs/superpowers/specs/2026-09-16-util-simos-design.md`（回填计划期新增细则）
- Modify: `docs/superpowers/plans/2026-09-16-simos-master-plan.md`（§二 M1 状态）
- Modify: `CLAUDE.md`（"当前状态"表）

- [ ] **Step 1: 全量门禁**

Run: `./mvnw clean verify`
Expected: BUILD SUCCESS（Spotless check + Checkstyle validate + SpotBugs verify + Surefire 全绿）。
**`mvn test` 不跑 SpotBugs**——关账必须以 `verify` 为准。

**基线（控制器已在 `ec2f10d` 上实测，可直接对照）**：
```
Tests run: 15, Failures: 0, Errors: 0 -- in io.mosire.simos.core.AgentLibAvailabilityTest
BugInstance size is 0
SimulatorMosire / UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos ... 全 SUCCESS
BUILD SUCCESS        （`grep -c '^\[ERROR\]'` → 0）
```
**⇒ 本命令此刻就是绿的。你跑出来若有任何红，那是新引入的，不是环境陈旧**——请当场停下报控制器，**不要**把红的模块从判据里划出去让它变绿。
（背景：本机 `~/.m2` 的 `agentlib-mosire` 曾是 49 类陈旧构建，导致 `simos-core` **测试编译失败**。控制器已按 `CLAUDE.md` 的既有指示重建为 **118 类**并验证。**若你在别的机器上跑出 `simos-core` 编译失败，先按 `CLAUDE.md` 的"换设备后的自检清单"第 2 条在本机重建**，再重跑。）

- [ ] **Step 2: 逐条核对关账判据（spec §1.2）**

| 判据 | 核对方式 |
|---|---|
| 八大件各有单测 | `Address*` / `SubjectIdTest` + `ResolvedSubjectTest` + `QueryResultTest` / `SimosTimestampTest` / `StateRefTest` / `SnapshotProtocolTest` / `SimulationStateTest` / `RoundTripAssertionsTest` / `InMemoryInfoSystemTest` / `TemporalSeriesTest` / `ResolverRegistryTest` / `FacetRegistryTest` 全绿（`simos-util` 现有 **17** 个测试类；上表未列的 `RoundTripAssertionsDriftTest` 由下一行覆盖、`TimeRangeTest` 由 Step 3 回填进 spec §十二——**多出来的类不是异常**） |
| 往返框架有故意漂移的失败用例 | `RoundTripAssertionsDriftTest` 绿（它断言的是"必须抛 AssertionError"） |
| 冻结样例逐条往返 | `AddressParseTest.frozenSamplesRoundTrip` 的 15 条参数全绿 |
| `./mvnw verify` 绿 | Step 1 |

- [ ] **Step 3: 把计划期新增的细则回填 spec，并处理计划里的腐坏**

1. §五 补：`TimeRange.to` 必须严格晚于 `from`，否则构造期抛 `IllegalArgumentException`。
2. §五 补：`SimosTimestamp` 的 `equals` 含 `calendarLabel` 而 `compareTo` 只看 `tick`——判"同刻"一律用 `compareTo == 0`。
3. §十二 测试清单补 `TimeRangeTest`（spec §十二 是下限不是上限）。
   **★ 另补一处表述（控制器 2026-09-16 实测新增；原简报漏了）**：§十二 的表头自称是"判据『八大件各有单测』的**落点**"，但逐行读下来，八大件里的 **`ChangeSet` 与 `Command` 在两处都找不到落点**——看上去是两个件没有单测。
   **实测结论：覆盖是有的，缺的是表述。** 这两个件由 `SnapshotProtocolTest.changeSetAndCommandExposeTheirStamps()`（`simos-util/src/test/java/io/mosire/simos/util/state/SnapshotProtocolTest.java:27`）覆盖，该类的 javadoc（`:8`）也明写"ChangeSet/Command 都是单方法接口"。**请把 `SnapshotProtocolTest` 那一行的"覆盖"列改成同时点明这两个件**（如"三个协议接口各实现一遍——`Snapshot` 三方法；`ChangeSet.baseRevision()` 与 `Command.expectedRevision()` 各自取值"）。**不要把这两个件删掉或另开测试类**：它们是**单方法接口、无行为可测**，另写用例只会得到一条空转断言（这正是 G13 要治的"装饰性护栏"）。
   **留个记录**：控制器当时是**从 spec 的表格直接推出"缺两个件"**，差点当成覆盖缺口写进关账；实际去 `git grep` 才看到用例就在那里。**表格不是证据**——这条与 T10 那几处同源（把"推导出来的结论"当既成事实）。
4. **给实现计划的过时代码草图加取代说明（不重写草图）**。`docs/superpowers/plans/2026-09-16-util-simos-plan.md` 的 M1 各任务代码草图是**计划期产物**，执行期已就地校正过——**spec 与已落地代码才是权威**。已知分歧至少五处，必须逐处加一行取代说明：
   - Task 7 Step 4 画的是 `public final class SegmentedSeries<T>`（私有构造器、显式字段、构造期无校验、无防御拷贝），而 **spec §七 强制 record**：往返断言要拿它当判据，`equals` 必须由 record 提供、禁手写（spec §十一）。已落地的是 record + 紧凑构造器校验 + `List.copyOf` 防御拷贝，且**不**手写 `segments()`/`events()` 覆盖。
   - Task 7/8/9/10 的用例数均少于已落地数——控制器在派发前逐条补齐了守卫自证（G13）：T7 加 null 守卫字段级消息、共享 `addition` 相等、两个等价 lambda 不相等；T8 加 null 守卫与 `namespaces()` 快照语义；T9 加提供者返回 null、`facetNames()` 快照语义；T10 加 null 返回守卫。
   - Task 8 的注册序在 brief 里被**故意改成非字母序**（`unit` → `map`），因为原序 `map` → `unit` 恰好等于字母序，"注册序"那条断言换成 `TreeMap` 实现也照样绿。
   - **Task 9 的草图自相矛盾（计划 `:2479`）**：`assertThat(registry.facetNames()).containsExactly("unit", "social")`，而**同一张草图**的实现返回的是 **facet 名**——`:2639` 是 `putIfAbsent(facetName, provider)`、`:2646` 的 `facetNames()` 返回 `keySet()`。断言与实现互斥，逐字照抄**必然编译过但跑不过**。根因是草图助手 `:2517` 的 `provider(String namespace, String facetName, FacetEntry... entries)` 第 1 参**从未被引用**。已落地的是 `provider(String facetName, FacetEntry...)`（死参已删）+ `containsExactly("unitsHere", "population")`；`unit` → `social` 这条注释早于本次修正，其字母序论证也是错的（该处比较的是 facet 名：`"population"` < `"unitsHere"`）。**这条取代说明必须写清楚"计划原文与已落地断言值不同"**，否则 M2 照抄草图会重蹈覆辙。
   - **Task 10 的草图同样自相矛盾（计划 `:2765-2781`）**：`ToySnapshot.apply` 写的是 `new StateRef(base.ref().branch(), changeSet.baseRevision())`，而 `changeSet.baseRevision()` 是 **base 的**版本（`diff` 传的正是 `base.ref().revision()`）。于是 apply 产出的 ref **恒等于 base 的 ref**，而所有用例都是 `base = ref(1)` / `target = ref(2)`——**`target` 永远不可达**，Step 5 明写"Expected: PASS"的那条用例**必然抛 AssertionError**。已用最小等价物在 JVM 上实跑证实（`target.rev=2`、`applied.rev=1`、`equals=false`）。已落地的是产出**下一个**版本（`changeSet.baseRevision().value() + 1`），`DriftingSnapshot.apply` 同改。**该修正还救回了漂移用例的判别力**：照抄计划时 applied 的 ref 比 target 低一版，漂移用例**照样红**，但红的理由里平白多了一个 ref 不匹配——"抓到了 beta 漂移"因此不再被证明（"红了还要问为什么红"）。同时把 `hasMessageContaining("beta")` 改为钉两侧**具体值**（`beta=9` / `beta=2`）：报文拼的是整份 record toString，两侧都带 "beta" 字样，只钉字段名对"差异出在哪个字段"零判别力。
   **理由**：本仓已因文档腐坏吃过一次亏（`fc4cff3` 修换设备后的腐坏）。取代说明**必须**保留草图原貌——计划的写法本身是记录，抹掉它等于抹掉"spec 在执行期被磨尖过"这件事。

- [ ] **Step 4: 更新状态表**

- `docs/superpowers/plans/2026-09-16-simos-master-plan.md` §二：**在 `### M1：UtilSimos 八大件` 段（`:1117-1124`）加一行状态，并列出 11 个任务的产出与 `verify` 结论。**
  **★ 更正（控制器 2026-09-16 实测；本 bullet 原先写"由 ⬜ 改 ✅"，那是错的，别去找那个 ⬜）**：
  该文件**全文一个状态记号都没有**（`grep -n '⬜\|✅\|⏳'` 零命中），`### M1` 段是一张
  四行表（`交付物` / `判据` / `待决` / `依赖`）。所以这里是**新增**一行状态，不是改既有记号。
  M2~M6 段同样没有状态行——**只给 M1 加**即可（它是唯一已完成的），不必顺手给别的段补。
- **同一文件另有两处已被本次执行变成假话的文本，一并改掉**（都属"文档腐坏"，本项目已因它吃过一次亏 `fc4cff3`；控制器实测确认这两处现在都不成立）：
  1. `:1216` 自审表里 `| §4 UtilSimos 八大件 | M1 路线图（详细计划待写） |` ——**详细计划早已写出**，改为指向 `docs/superpowers/plans/2026-09-16-util-simos-plan.md`。
  2. `§0.2`（`:48-50`）"**M1 的详细计划是下一份文档**，前置条件是把 spec §十三 中……裁决掉" ——前置条件**已满足**（五项待决已在 M1 spec 的「〇 已裁决记录」裁决完毕），该计划也已落盘。改为**过去时 + 指向真实文件**，别删掉这段（它记录了当时的拆卷理由，是记录）。
  **判据**：改完后 `grep -n '待写' docs/superpowers/plans/2026-09-16-simos-master-plan.md` 应无命中。
- `docs/superpowers/plans/2026-09-16-simos-master-plan.md` **M2 行补一句跨里程碑提醒**：M2 规范须明确"任何含 `ADD` 事件的 `TemporalSeries` 一律用模块级 `static final` 的 `addition` 构建"（spec §七 已述；`addition` 在 record 的 `equals` 里按**身份**比较，各写各的 lambda 会让 M2 起的往返断言假红）
- `CLAUDE.md` "当前状态"表：M1 行改 ✅，M2 行标注"待裁决 MapSimos 待决项（spec §十三）"
- `CLAUDE.md` **"换设备后的自检清单"补第 3 条**（本里程碑新踩的机差陷阱）：本机 `grep` 可能是 **ugrep**（`grep --version` 可辨），它**默认尊重 `.gitignore` 且跳过隐藏目录**——于是 `grep -rn <串> .` 会**静默返回空**，把"没搜到"伪装成"不存在"。仓根下的 `.superpowers/**` 正是被 ignore 的隐藏目录，属重灾区。要搜全仓一律用 `git grep <串>`，或 `grep --hidden --no-ignore-files`。
- **★ `CLAUDE.md` 的"AgentLibMosire 依赖现状"段落已失真，必须一并改**（控制器 2026-09-16 复核时发现；task-11-brief.md 原先**漏了这一处**，现补）。该段现写"**`/home/cna` 这台仍是 49 类的陈旧构建，尚未重建**"——**已经不成立**：本机在 2026-09-16 本会话内已由控制器执行 `cd ~/ProjectMosire && ./mvnw -pl AgentLibMosire -am install` 重建，实测 `jar tf … | grep -c '\.class$'` 由 **49 → 118**，`simos-core` 的 testCompile 由 **`cannot find symbol` 失败**变为 **exit 0**（台账 `:989-995`）。改写时请把该段从"按机分别陈述"改为**只陈述本机现状 + 保留换机器需重建的指引**，不要写死绝对家目录（既有口径）。
  **★ 附一条反直觉的实测观测，务必写进去，否则下一个人会误判"没重建成功"**：重建后 `~/.m2/repository/io/mosire/agentlib-mosire/0.1.0-SNAPSHOT/agentlib-mosire-0.1.0-SNAPSHOT.jar` 的 **mtime 仍是 `2026-09-15 01:02`**，与 `~/ProjectMosire/AgentLibMosire/target/agentlib-mosire-0.1.0-SNAPSHOT.jar` 的 mtime、字节数**完全相同**——`install` 把**源 jar 的时间戳一并带了过去**；2026-09-16 当天被改写的只有同目录的 `maven-metadata-local.xml` 与 `_remote.repositories`。**⇒ 判"本机构件重建成功与否"一律看类数（`jar tf … | grep -c '\.class$'` ≥ 118），不看文件时间戳。** 这与清单里既有的"数 JAR 类数一律用 `jar tf`"是**同一条纪律的延伸**。
  **已被咬过一次**：本轮出现过"全仓 `.md` 都搜不到 `provider("unit", null)`"的结论，实为假阴性——该串就在 `task-9-brief.md:109` 与 `task-11-brief.md:42`（`git grep` 一搜即得）。这与既有的"数 JAR 类数一律用 `jar tf`"同属**工具因机而异、且静默给出错误答案**一类，故必须写进清单。
- `CLAUDE.md` **"纪律"节**：给"护栏必须自证"**补上判定方法**。原文只要求"有一个故意违规的用例"，没说**怎么确认那个用例真的有效**——M1 期间查出**十五处**判别力缺陷（**计数口径**：一个「被保护、却没被任何用例钉住」的文本处算一处；形态归纳只作趋势描述、**不参与计数**——两套数字并存必然打架；含控制器在派单与裁定里写下的两处错误论断），**全部出自控制器自己写的文本**，无一出自实现者或评审者，形态高度一致：断言看着像在钉守卫，但**把被保护的那行删掉，它照样通过**。
  **另有同源的姊妹族十处，一并写进这一段**（不是"断言没判别力"，而是"控制器**未经核实就声称**"）：把**从没测过**的 Expected 写进 brief（T10 Step 3 写 `cannot find symbol: class`，javac 实报 `variable`——我在 worktree 外是把三个文件一起编译的，那一步根本不存在）；把**工具的静默假阴性**当成"不存在"（ugrep 吞掉 `.superpowers/**`）；把**没发生的风险**当理由（"比较两个 `context()` 会因错误的原因转红"——它们是 record，值相等，不会红）；把**未存在的文本**当现状引用（引用一条当时只活在待写 brief 里的 `CLAUDE.md` 纪律；以及 **T11 简报原写"master plan §二：M1 由 ⬜ 改 ✅"——该文件全文零状态记号，那个 ⬜ 不存在**——**这是与上一处同形的第二处**，同形但**主张不同**，故与上一处**各计一处**）；把**没核实的副句**照抄进台账；把**跑了一半的实测写成全称**（T10 brief ① 原写“核心不变量经删式变异证明是承重的”——只跑了通用入口，快照入口那一份当时删了全绿，见 task-10-brief.md ⑥）；把**探针的产物按印象归到另一行**（两例同形：派单要求验收② 附 `but did not`，而该变异下**什么都不抛**，`assertThatThrownBy` 在自身「是否有 throwable」那一关就失败；brief ⑥ 第 4 行写「守卫换人 ⇒ 排除了因错误的原因转红」，实测那一行红的原因**恰恰就是**错误的原因，排除它的是第 3 行）（派单要求验收② 附 `but did not` 那行，而该变异下**什么都不抛**——`assertThatThrownBy` 在自身「是否有 throwable」那一关就失败，根本走不到消息比对；实现者如实回报「跑不出来」并拒绝伪造）；把**没跑过的"护栏边界"**当结论写进 brief（T10 ⑤ 原写"把 `+ applied` 误写成 `+ changeSet` 本用例抓不住"，实测**红 2**，见 task-10-brief.md ⑤ 的更正）。**两族合计二十五处，全部出自控制器自己写的文本。**（**逐条枚举即上面的清单本身**——数字是它的长度，清单与数字不一致时以清单为准。） 姊妹族的教训一句话：**"我验过了"与"我记得是这样"必须分开，写给别人当依据的每个 Expected / 事实 / 出处，都要有当场跑过的痕迹。** 补五句，控制在十行内（前四句为甲族，末句为乙族）：

> **★★ 控制器裁定（2026-09-16，T11 执行中追加，优先级高于上文措辞）**：`CLAUDE.md` 那一段
> **不要写两族的精确数**，改用量级措辞——甲族写「**十余次**」、姊妹族写「**同源的另一族**」，
> **形态清单原样保留**（它才是可教的部分），那句总教训也保留。
> **为什么**：这两个数在本里程碑内变动了七轮（甲 12→13→14→15、乙 5→…→11、合计 17→…→26），
> **每一轮都不是新发现，是有人再认真看了一遍**。`CLAUDE.md` 是**每会话载入的常驻上下文**，
> 里面一个已被推翻的精确数**比没有数更坏**——它会让下一个读者以为"这数已经审过了"。
> 本裁定**取代**上文 `十五处`/`姊妹族十处`/`两族合计二十五处` 三处数字；`逐条枚举即清单本身、
> 以清单为准` 那句**保留**（它本来就说明了数字不是权威）。
> 若你已把精确数写进某个 commit，**不必回改历史**——追加一个提交改正即可，并在报告里写明。
  1. **确认方法**：把被保护的那行删掉、跑该用例、看它是否真的红；红不了就说明这条护栏是装饰，得换输入或换断言。**红了还要问"为什么红"**——红的理由必须是被保护的那行本身：M1 里删掉一个参数后，末尾的 `null` 被 varargs 吸收成**整个数组**，一处调用点**没改却照样编译**（`provider("unit", null)` 变成 `facetName="unit"`、`entries=null`）。**javac 对此只给警告、不报错**（`non-varargs call of varargs method with inexact argument type for last parameter`；2026-09-16 以 javac 21.0.12 实测），本仓又无 `-Werror`，故构建照样全绿——**"让编译器去找全部调用点"在这里会失效**：不是编译器没说，是它说的只是一条混在绿构建里的警告。用例确实红了，红的却是"被测行为变了"，不是"护栏响了"——这种红会把人指向错误的方向。**没红也要问"为什么没红"**：探针的**空输出**可能就是根本没跑到——`junit-platform-console --details=none` 在**全部通过时连汇总行都不打**（M1 实测），只留一句 `Thanks for using JUnit!`；M1 里这被脚本读成了 `?`，若当时当成"没问题"就放过去了。**"没输出"与"通过"是两件事，探针本身要先自证跑到了。**
  2. `requireNonNull(x, "x")` 的失败消息**恰是字段名本身**，而删掉守卫后紧接着的那次解引用会抛出 JDK 21 的热心 NPE，其消息形如 `Cannot invoke "..." because "x" is null`——**同样含该字段名**。故这类守卫只有**精确匹配**（`hasMessage`）才有判别力，`hasMessageContaining(字段名)` 是空转的。
  3. 判"同刻/相等"口径的用例，输入必须落在两种实现会**分叉**的地方（例：带 `calendarLabel` 的时间戳——`equals` 分叉而 `compareTo` 不分叉），否则两种实现下断言全等价。
  4. **纯转发型 SPI**（注册表、分发器）要有一条用例证明参数被**原样转交**：把 `return provider.query(subject, ctx)` 写成 `provider.query(null, null)` 而全套仍绿，是 M1 在 `ResolverRegistry` 与 `FacetRegistry` 上**各犯一次**的真实缺口。同理，**返回处的加固**（`List.copyOf(...)`）也要自证：M1 里同一份 `FacetRegistry` 的 `facetNames()` 钉住了、`queryAll()` 漏了。
  5. **（乙族——另一类缺陷，同样出自控制器）把"我验过了"和"我记得是这样"分开。** 写给别人当依据的每个 **Expected / 事实 / 出处**，都要有**当场跑过的痕迹**：不要写从没实测过的期望输出（M1 把 `cannot find symbol: class` 写进 brief，javac 实报 `variable`）；不要把**工具的静默假阴性**当作"不存在"（本机 `grep` 是 ugrep，吞掉 `.superpowers/**`，于是"全仓搜不到"是假的）；不要把**推导出来的风险**当既成事实（"比较两个 `context()` 会因错误的原因转红"——它们是 record，值相等，不会红）；不要引用**当时还只活在待写文件里**的条文当现状；也不要把**推导出来的"护栏边界"**当结论（"把 `+ applied` 误写成 `+ changeSet` 抓不住"——实测红 2）。**这一族与前面四条同源：都是"看起来验过了"。**

- [ ] **Step 5: 提交（不推送）**

```bash
git add docs/ CLAUDE.md
git diff --cached --stat          # 必须逐行扫过再提交（纪律：绝不 git add -A）
git commit -m "docs: M1 UtilSimos 关账——判据核对、计划期细则回填 spec、状态表同步" \
           -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

**★ 第二段 trailer 是必须的**（控制器 2026-09-16 加入本简报）：本仓提交信息以
`Co-Authored-By: Claude Code <noreply@anthropic.com>` 结尾是**当前生效的约定**。
实测本分支 `56836f0..HEAD` 的 32 条提交里 **26 条有、6 条没有**（早期遗留，控制器已裁定
**不追溯改写**——改写会作废已进台账的 SHA）。**本任务起的提交一律要带上**，
别再制造新的缺失条目。同理，**`git add` 只加你确实改过的路径**，不要顺手加别的。

---

## 自审记录（writing-plans 的三项自查）

**1. spec 覆盖**：spec §二 包结构的 8 个包 ↔ Task 1–10 逐个落地；§三 Address ↔ Task 1/2；§四 身份 ↔ Task 3；§五 时间与版本 ↔ Task 4/6；§六 三协议与容器 ↔ Task 6；§七 TemporalSeries ↔ Task 7；§八 Facet ↔ Task 9；§九 往返框架 ↔ Task 10；§十二 测试清单逐类落到任务里（`AddressQuoteTest` 由 Task 1 建、Task 2 补归一用例；额外的 `TimeRangeTest` 由 Task 11 Step 3 回填进清单）；§十 偏离 D1–D7 在 Task 5/6 落地并在接口处注明。**§十三 不做清单**（各领域 Resolver、Jackson 序列化、两阶段时间推进、命令信封、世界时钟、单位编制链地址、`agent:` 嵌套语义）在计划中**无对应步骤**——这是有意的。

**2. 占位符扫描**：每个代码步骤都给了可编译的完整代码与确切路径；无 TBD/TODO/"类似 Task N"。唯一引用他处代码的措辞是 Task 1 的 `AddressText`（同任务 Step 3 内给出）与 Task 2 复用 Task 1 的段类型（同文件、已给全）。

**3. 类型一致性**：逐项核对过——`Entity.of(String)` / `Entity.of(String, String)`、`Address.canonical()`、`Index(List<Integer>)`、`SegmentedSeries.of(segments, events, addition)`、`TimeRange.since(...)`、`InfoSystem.put(...)`、`SimulationState.module(String)`、`RoundTripAssertions.assertRoundTrip/assertSnapshotRoundTrip` 的参数顺序（`diff` 为 `(S,S)→C`、`apply` 为 `(C,S)→S`）在各任务间一致；Task 10 的玩具类型用**静态** `apply(C, S)` 方法以匹配该签名（写成实例方法会让方法引用顺序颠倒、编译不过）。
