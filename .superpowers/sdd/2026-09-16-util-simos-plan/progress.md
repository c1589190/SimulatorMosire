# SDD ledger — plan: docs/superpowers/plans/2026-09-16-util-simos-plan.md

Spec: docs/superpowers/specs/2026-09-16-util-simos-design.md（已批准，可达）
执行分支：feat/m1-util-simos（base 56836f0）
起始时间：2026-09-16

## Pre-flight 冲突扫描

### 跨任务共享（文件/接口）逐对核对

| 对 | 共享面 | 结论 |
|---|---|---|
| T1 → T2 | 包内 `AddressText`（裸词/引号）+ `Namespace/Entity/Index/Property` 与其 `canonical()`；T2 另追加 `AddressQuoteTest` 用例 | ✓ 一致：T1 的 `Entity.of(kind,name)`、`Index(List<Integer>)` 与 T2 解析器构造式相同 |
| T1 → T2（文件） | `AddressQuoteTest` 由 T1 建、T2 修改 | ✓ 顺序正确（T1 先执行） |
| T2 → T3 | `Address.parse/canonical` 被 `ResolvedSubject` 的 canonical 校验消费 | ✓ `parse("social:Map1.[4,3]:population").canonical()` 确实 ≠ 输入（宽容写法），故该用例会抛 IAE |
| T3 → T8 | `QueryResult/ResolvedSubject/SubjectId` 被 `Resolver` 签名与 T8 测试消费 | ✓ 构造式一致 |
| T4 → T5 | `SimosTimestamp/TimeRange` 被 `InfoEntry/InfoSystem` 消费；`TimeRange.since(...)` | ✓ |
| T4 → T6 | `SimosTimestamp` 被 `StateMeta/Snapshot` 消费 | ✓ |
| T4 → T7 | `SimosTimestamp` 被 `Segment/Event/TemporalSeries` 消费（同包 time） | ✓ |
| T5 → T6 | `InfoSystem/InMemoryInfoSystem.empty()` 被 `SimulationState` 与 T6 测试消费 | ✓ |
| T6 → T8 | `SimulationState/StateMeta/StateRef/BranchId/RevisionId` 被 `ResolveContext` 与测试消费 | ✓ |
| T6 → T9 | T9 测试构造 `SimulationState` + `ResolveContext` | ✓ |
| T6 → T10 | `Snapshot/ChangeSet/RevisionId` 被 `RoundTripAssertions` 消费（`apply` 形状 `(C,S)→S`） | ✓ T10 玩具用**静态** `apply(C,S)` 以匹配方法引用方向 |
| 同包共存 | T4/T5/T7 测试类同包 `time`/`info`；T6 三个测试类同包 `state`，各自内嵌 `ToySnapshot` 不冲突 | ✓ |
| T2 → T11 | T11 核对 spec §十二 测试类名 | ✓ 仅需补 `TimeRangeTest` |

### 每个任务的自洽性

| 任务 | 测试 vs 实现 | 文件创建 vs 后续触碰 | 结论 |
|---|---|---|---|
| T1 | 渲染用例只依赖本任务 5 个类型 | 新建 6 文件 | ✓ |
| T2 | 冻结样例 15 条 + 段判定 + 非法形态 | `Address` 依赖同任务 Step 5 的 `AddressParser`（同一"确认通过"步之前完成） | ✓ |
| T3 | 三个测试类覆盖值语义/校验/防御拷贝 | 新建 3+3 文件 | ✓ |
| T4 | 排序/plus/相等口径 + 左闭右开 | 新建 2+2 文件 | ✓ |
| T5 | 按 key+时刻、左闭右开、put 返回新实例 | 新建 3+1 文件 | ✓ |
| T6 | 反射用例的公开方法名集合 = 记录 7 个（meta/modules/info/module/equals/hashCode/toString） | 新建 8+3 文件 | ✓ |
| T7 | 四条时间语义 + 构造期校验 + 防御拷贝 | 新建 5+1 文件 | ✓ |
| T8 | 分发/重复注册/未注册无兜底 | 新建 3+1 文件 | ✓ |
| T9 | 注册序/唯一名/空列表/结构化值 | 新建 3+1 文件 | ✓ |
| T10 | 正常往返、错版本戳、破裂报文、**漂移自证** | 新建 1+2 文件 | ✓ |
| T11 | 全量 verify + 判据表 + 回填 spec | 改 spec / master plan / CLAUDE.md | ✓ |

### Rulings（pre-flight）

- Ruling: 在 `feat/m1-util-simos` 分支上执行，不开 git worktree — 用户在本机以 `~/SimulatorMosire` 为工作目录，worktree 会把路径挪到 `.claude/worktrees/` 干扰其查看；分支已提供必要隔离。代价：收尾需一次合并回 main（由 finishing-a-development-branch 走用户裁决）。
- Ruling: 实现者用 sonnet（计划含完整代码，但仍需 Maven 迭代与小修；haiku 在跨步工具循环上回合数过高）。代价：单任务成本略高。
- Ruling: SpotBugs（effort=More/threshold=Low，无 excludeFilterFile）可能对 T5/T9 的 `Object value` 报 EI_EXPOSE_REP/EI_EXPOSE_REP2 — spotbugs 绑在 verify，`-Dtest=` 迭代看不到，故要求 **T5 实现者额外跑一次 `spotbugs:check`** 提前暴露；若报警，在 pom 的 spotbugs 配置加**精确的 excludeFilterFile**，不动依赖白名单（新依赖会违反 enforcer 白名单）。代价：一次 pom 变更需单独提交与复核。
- Ruling: 本阶段**不推送**（CLAUDE.md 纪律）。用户已授权的推送只覆盖计划那一次。代价：无。
- Ruling: Checkstyle 只有 4 条规则（AvoidStarImport/RedundantImport/UnusedImports/OneStatementPerLine），绑 validate — 故 `-Dtest=` 迭代时即会连带检查；计划代码按此写，若出现未用导入由该任务实现者就地清理。代价：无。

## 进度

Task 1: 实现者报 DONE_WITH_CONCERNS（commit b40b28f，6/6 测试 + `verify` 全绿）。
- 发现 1（转录缺陷，实现者已就地修正）：计划 Step 3 的 `AddressText.isBareWord` 只拒**首尾**空白，与 Step 1 测试（`new Property("a b")`、`Entity.of("a b","x")` 必须抛）及 spec §3.2（裸词"不含空白"）冲突。实现者改为拒任意空白并同步三处文案，测试未削弱。**判定：修得对**（spec 是权威，plan 那段是转录错误）。
- 发现 2（**Ruling**）：收紧后，`Entity.of("region","A B").canonical()` 若不加引会渲染成 `region.A B`，而 T2/T3 的解析器（`componentText` 复用 `isBareWord`）必拒 → `parse(canonical(x))` 破。
  Ruling: spec §3.4 条件 2 由"为空串或**首尾**含空白"改为"为空串或**含任意空白**"（与 §3.2 裸词定义同口径）；spec 已就地更新。
  代价若错：含内部空白的名字 canonical 多一对引号——冻结样例不含此类名字，无回归风险。
  → 修复轮 1 派给 T1 实现者：`quoteIfNeeded` 同步 + `AddressQuoteTest` 补"内部空白"渲染用例；**往返用例由 T2 承载**（属 brief 之外的控制器裁决，随 T2 派发携带，并在 T2 审阅者的约束块中注明）。
- 修复轮 1 完成：commit `0f8d5ff`（先 RED 后 GREEN，`AddressQuoteTest` 6/6；`verify` 全绿、SpotBugs 0）。
- 控制器提交 `4109eb5`：spec §3.4 条件 2 的修正落库（仅该 1 行）。
- 审阅包：`.superpowers/sdd/2026-09-16-util-simos-plan/review-56836f0..4109eb5.diff`（3 commits）→ 已派审阅者（sonnet）。
- **T1 审阅结果**：Spec ✅ 合规（§3.4 条件 2 收紧实现正确，§3.1~§3.5 无其它偏离；14 条冻结样例逐条手工复核 byte-identical）。质量 **Needs fixes**（1 Important + 3 Minor）。
  - Important：`Index` 空坐标护栏**无故意违规用例**（G13，plan-mandated，4 条新护栏里唯一缺的）。
  - Minor 1：`AddressText` 的 `: [ ] "` 字符集在 `isBareWord` 与 `quoteIfNeeded` 两处手工重复——铁律 5 同型失败模式，**裁决：本轮就修**（抽 `hasStructuralChar`；审阅者警告 `!isBareWord` 的偷懒写法会破坏 canonical 唯一性，已在派发中原样转达）。
  - Minor 2/3：`List.copyOf` 与 report:119 措辞——一并修（各一行）。
  - ⚠️（转 T2）：缺 kind 的 `Entity` 出现在第 ≥3 段时 canonical 为 `member`，与 `Property("member")` 撞车 → canonical 唯一性与往返同时破。**Ruling**：T2 的 `Address` 构造器增加位置校验——第 ≥3 段不得是缺 kind 的 Entity（§3.2「裸词主体只出现在根位置」的实施），并带一个故意违规用例（G13）。代价若错：直接构造此类地址会被拒——无合法用途，可忽略。
- 修复轮 2 完成：commit `424877f`（`AddressQuoteTest` 8/8；`verify` 全绿、SpotBugs 0）。实现者当场发现审阅者给的 `isBareWord` 公式会丢掉 `.` 否决（`hasStructuralChar` 有意不含 `.`），被既有护栏用例 `kindMustBeBareWord` 拦下 → 已写回 `s.indexOf('.') >= 0` 并注 Javadoc。**G13 在实战里响了第一次**。
- 定向复审包：`review-4109eb5..424877f.diff`（1 commit）→ 已派复审（sonnet）。
- **复审结果**：Important(Index 护栏) ADDRESSED；字符集去重 ADDRESSED（逐字符核对等价、未落 `!isBareWord` 陷阱）；report 措辞 ADDRESSED；**防御拷贝用例 NOT ADDRESSED —— 空转护栏**（`List.of(1)` 本就不可变，删掉 `List.copyOf` 照样绿；复审用 jshell 实测 `List.copyOf(List.of(1))` 与 `List.of(1)` 同实例）。判词：**Findings remain open**。→ 修复轮 3：改为可变源 `ArrayList` 的判别性写法。
- **留待最终全支审阅（Out-of-Scope，非阻塞）**：`Namespace.java:8`、`Property.java:8/:17` 三处异常文案以散文形式重复裸词字符集「不含 : . [ ] "」，将来集合变动会静默漂移（文案而非判定逻辑）。
- 修复轮 3 完成：commit `79371d2`（仅测试文件 +6/-3；实测删 `Index.java:10` 的 `List.copyOf` 后用例转红，还原后逐字节一致）。
- 修复轮 3 定向复审：**All findings addressed**，判别性核算到行（唯一满足者是 `Index.java:10`）。新增一条**非阻塞** Minor：换掉旧用例时把「`coords()` 不可变」的断言一并删了——`List.copyOf` → `new ArrayList<>(coords)` 这类回归仍无人守（防御拷贝照旧成立、用例仍绿）。**Ruling**：一行补回，随 T2 的 `AddressQuoteTest` 改动一起做（T2 本就修改该文件，避免单开一轮）。代价若错：无（多一条断言）。

### Task 1: complete

提交链：`b40b28f`（初版）→ `0f8d5ff`（修复轮 1）→ `4109eb5`（spec §3.4 修正，控制器提交）→ `424877f`（修复轮 2）→ `79371d2`（修复轮 3）。T1 净代码提交 4 个 + spec 1 个。
验证：`AddressQuoteTest` 8/8；`./mvnw -q -pl simos-util verify` 全绿（Spotless/Checkstyle/SpotBugs 0）。三处修复轮均经定向复审判 ADDRESSED，无遗留阻塞项。
### Task 2: 进行中

提交：`e1eac8e`（测试，先 RED）→ `4188cc1`（Address/AddressParser）。45 条测试全绿（AddressParseTest 24 + AddressTolerantParseTest 12 + AddressQuoteTest 9），verify 全绿，两次 G13 变异实测转红。实现者报 DONE_WITH_CONCERNS。

- **实现者发现（裁决 ② 字面表述与 spec 冲突）**：`map:Map1:"Nation"."区域A"` 按 §3.2（左侧被引号包裹 ⇒ 无 kind）**必然**产出第 3 段的 `Entity(∅,"Nation.区域A")`；裁决 ② 的字面「第 ≥3 段不得是缺 kind 的 Entity」会让 brief 自带用例转红并违反 spec。实现者按裁决给出的**判别理由**（与 `Property` canonical 撞车）精确化为「缺 kind **且 name 是裸词**」才拒。**判定：采纳**——我的措辞过宽，实现者是对的。
  - 复核：`Property` 在 T1 起就是**裸词专属**且 canonical 从不加引（`Property.java:7,14`），故「name 不是裸词的缺 kind Entity」不会与任何 `Property` 撞车，精确化后唯一性成立。
- **Ruling（副产物收口）**：实现者指出 `unit:U:"member"` 现在会抛（解析器判成 `Entity(∅,"member")` → 守卫拒）。这违反 §3.5「引号可冗余（归一后消失）」。裁决：**第 ≥3 段、无未加引号 `.` 的 token 一律先去引再判**——去引后是裸词 ⇒ `Property`，否则 ⇒ 缺 kind `Entity`。§3.2 与 §3.4 各补一段（控制器提交），T2 修复轮 1 落地。
  代价若错：`"member"` 这类写法从"抛异常"变为"归一为属性名"；两者都不影响冻结样例，改回只需删一个分支。
- 控制器提交 `3ebb891`：上述两条 spec 澄清（§3.2 末段 + §3.4 末段）。
- T2 修复轮 1 完成：commit `687f732`（+20/-1，仅 `AddressParser.toSegment` 的 `dot < 0` 分支、`Address` 守卫 javadoc、新用例）。46 条全绿（25+12+9），verify 全绿。既有用例**无一断言旧行为**。
  - 遗留低危（未擅自改，留给审阅者/最终审阅裁量）：`map:Map1:region.` 的报错文案建议"加引号"而非写成 `""`（简报原样带入，无用例覆盖）；构造器守卫在解析路径上已不可达，是纯"直接构造"防线（§3.2 末段即如此定义，G13 用例走这条）。
- 审阅包 `review-79371d2..687f732.diff`（4 commits）→ 已派 T2 审阅者（sonnet），并在派发中要求做**组合层面的对抗性核查**（枚举四类段 canonical 形态，找"两个 AST 渲染同串"或"某 canonical 回解析不还原"的情形）。
- **T2 审阅结果**：Needs fixes（1 Critical + 1 Important + 4 Minor）。审阅者自建 fuzz（源码只读编译进 /tmp，未动 checkout）后给出结论：**除下列两类外，parse 可达的 AST 上 canonical 是单射**；14 行冻结样例逐条实跑通过。
  - **Critical 1**：`parse("map:Map1:hex.\"1..2\"")` → `Entity[hex,"1..2"]` → canonical `hex.1..2` → **再 parse 抛 IAE**（`nameParts/componentText` 拒空组件 × `quoteIfNeeded` 对带 kind 的含 `.` name 不加引）。**Ruling：走渲染侧**（保 §3.5「不猜测」的严格表面）——spec §3.4 新增**条件 4**：带 kind 的 name 以 `.` 开头/结尾/含连续 `..` 即加引；"三条之外"改"四条之外"。代价若错：canonical 多一处加引（`hex."1..2"`），该名字本就是病态输入，无回归风险。
  - **Important 2**：`Address` 构造器位置校验只守 `i ≥ 2` 的缺 kind Entity，漏了 `Namespace@≥2` 与 `Property@2`（`[Ns(map), Property(x)]` 的 canonical `map:x` 与 `[Ns(map), Entity(∅,x)]` 逐字相同）。**Ruling：对 `i ≥ 1` 全查**（同一类破唯一性，判据与落点相同）。解析路径不可达（`toSegment` 只按位置产出），纯"直接构造防线"补全。
  - **Minor 3**（真漏洞）：`right.isEmpty()` 不可达；可达的 `left.isEmpty()` **无用例**，删掉它 `map:Map1:.[4,3]` 会漏出 `StringIndexOutOfBoundsException`（逃出 IAE 契约）。
  - **Minor 4**：`引号未闭合` 护栏无判别性用例（删掉后 11 条非法用例仍全绿）。
  - **Minor 5**：Index 的 `strip()`/`parseInt` 容忍 `[ 4 , 3 ]`、`[+4]`、`[004]` 超出 §3.5 清单。**Ruling：保留容忍但写进 spec**（§3.5 明文，canonical 仍紧形式），并要求非数字坐标不得漏出 `NumberFormatException`——判据是"歧义才禁，空白无歧义"。代价若错：宽容面比原 spec 宽一档，删除只需去掉 `strip()` 与 spec 一行。
  - **Minor 6**：冻结样式的 §3.6 表第二列（解析结果）无守卫（只断言了 canonical）。
- 控制器提交 `b55be33`：上述两条 spec 更新（§3.4 条件 4 + §3.5 Index 宽容明文）。
- 修复轮 2 完成：commit `a94400b`（+159/-19，5 文件；62 条全绿 = 38+15+9，verify 全绿）。六次真实变异逐条转红并 `cmp` 验证恢复一致；实现者另做公开 API 全构造空间穷举（5624 条被接受地址）声称往返零违规（**待复审独立核实**）。
- 定向复审包 `review-687f732..a94400b.diff`（2 commits，含控制器 spec 提交 `b55be33`）→ 已派复审（sonnet）。
- **复审结果：All six findings ADDRESSED，无新增 Critical/Important**。每条新护栏都做了实测变异自证（删 `hasEmptyComponent` 三个子句分别转红；删 Namespace/Property 分支各转红；短路 `left.isEmpty()` 漏出 SIOOBE；删引号未闭合护栏转红；去 NFE 包装转红）。
  - 复审者独立实现穷举：**accepted=430710 / rejected=11720 / violations=0**，canonical 注入映射零碰撞；随机 fuzz（固定种子）**1.93M 被拒构造 + 74 万被拒串 + 5522 被接受串，violations=0，无异常泄漏**——复现并超过了实现者"5624 条零违规"的结论。
  - 新引入 Minor（文案级，见 Park）：`AddressParser.java:139` 消息「第 3 段 是空 Index」多一个空格。

### Task 4: 实现回报 DONE（`f7785cb`，BASE = `c7d32cf`）→ 评审中

实现者自报：`./mvnw clean verify` 全绿（simos-util 82/0，simos-core 15/0），13 条变异实验逐条转红，工作树与 HEAD 逐字节一致。

**Ruling（针对 T4 三条顾虑与一条新发现）**：
- ① **identity 的 null 分支保持 IAE，不改为 NPE**——理由：T3 的守卫把 null 与空白合并为「不得为空白」一条 IAE，四臂齐全（实测非缺臂）；改口径要动 T3 既有断言与生产语义，且调用方区分 null/空白的收益为零。代价若错：null 与空白得到同一异常类型，靠消息区分。**要求**：断言必须钉字段级消息（实现者的 `canonicalAddress` 消息判据保留，否则与 `Address.parse(null)` 的同型 IAE 混同）。
- ② **`requireNonNull(to, "to")` 保留**（同 T3 先例：行为冗余但点名字段，用 `.withMessage("to")` 钉住判别性）。代价若错：多一行守卫。
- ③ **G13 报告契约升级（采纳实现者的新发现）**：只看 exit code 会把 Checkstyle/编译失败误记成「用例转红」。**自本任务起，G13 证据一律写「变异 → 转红的用例名 → 失败行号」**，且变异实验须单独跑 `-Dtest=` 目标用例（不跑门禁插件）以免插件失败冒充转红。此条随 T5+ dispatch 下达，并回填 T11 关账记录。
- ④ `TimeRange` 严格 `to > from`：保持 brief 细则 1 原样。代价若错：将来要「瞬时有效」时需改构造器 + 回填 spec；T6/T7 若撞上再裁决。
- ⑤ `plus` 的 long 溢出：spec/brief 未提，保持逐字，仅记录（不改）。

### Task 4: complete（`f7785cb`，BASE = `c7d32cf`）

**T4 审阅结果：规格符合性 ✅ / 质量通过**（无 Critical、无 Important，4 Minor）。
- 审阅者独立跑了**报告自述的 13 条变异 + 3 条自设探针**（16 次），全部为**用例断言失败**（无编译/Checkstyle 假红），每条从独立快照恢复并以 `sha256` + `git diff --stat` 自证干净；另独立复现 RED（临时移出生产文件 → `cannot find symbol`）。
- **裁决 ③ 被实证为真**：审阅者复现了「直接删 `requireNonNull` → exit=1 但 `Failures: 0`，真因是 Checkstyle `UnusedImports` 在 validate 打断」，并确认「中和该行」才是真变异（转红 `nullCalendarLabelIsRejected:37`）。裁决 ②（判据钉消息）由 M7 证实**必要**：删 `requireNonNull(to,"to")` 后邻行兜出同型 NPE，只有 `.withMessage("to")` 能判别。裁决 ① 由 M10 证实**必要**：只钉类型会与 `AddressParser.parse(null)` 的同型 IAE 混同。
- 探针 B 证明「拒空」与「拒倒置」两条半各自独立有判别力；探针 C 证明**手写 `equals`**（违反 spec §十一）会被既有用例抓住。
- Park（非阻塞，留最终全支审阅）：① `SimosTimestamp.of(long, String)` 传 null 抛**无消息 NPE**（走 `Optional.of`，与构造器 `NPE("calendarLabel")` 口径不一；brief 逐字如此）；② 报告 M7 行号记 48、实测 47（断言链归因，结论不受影响）；③ `SimosTimestampTest:24` 的「原实例不变」与 `:30` 的同型重复为**行为冗余断言**（可接受，已点名，非空转缺陷）；④ `task-4-report.md §9.1` 的裁决请求已过期（裁决 ① 已答）——**流程改进**：后续报告模板不再重复发问已决点。

### Task 4: dispatched（BASE = `c7d32cf`）

**Ruling（T4 dispatch 携带）**：
- ① **null 分支断言随 T4 一并补**（时间基础自身的 `requireNonNull` + T3 遗留的 identity 四条）——理由：T3 审阅已确认整块守卫删除会转红、只有 null 臂无用例，属覆盖面而非空转；攒到最终审阅只会再被标一次，成本一样而延迟更久。代价若错：T4 审阅面多出 identity 包 4 行断言（已在 dispatch 中单独标为携带项，不混入 brief 判定）。
- ② **时间类型也补 null 用例**——理由：与 T3 先例一致（实现者自加 `nullIdIsRejected` 被审阅者独立变异确认为必要），同一形态的分支不该只在一处有护栏。代价若错：多两条断言。
- （brief 的代码与断言按 T1~T3 惯例照抄执行；发现的冲突由实现者按「spec 优先」裁决并在报告点名。）

### Task 5: 实现回报 DONE（`0f14a95`，89/0 全绿）→ fix round 1（裁决 ①）

**Ruling（T5 顾虑 1，载重问题）**：`InMemoryInfoSystem` **改为 record**（组件 `Map<Address, List<InfoEntry>> bySubject`，紧凑构造器 `Map.copyOf`，`empty()` 静态工厂），从而由 record 提供**值语义**，**不手写 `equals`**。
- 理由：spec §十一 明文「`equals`/`hashCode`/`toString` 全部由 record 提供——禁止手写 `equals`（`equals` 是往返断言的判据本身）」；§十-D4 亦写明「状态组件必须不可变，否则 `equals()` 往返断言无从谈起（铁律 5）」。现实现只有身份相等 → `SimulationState.info` 一旦参与往返断言即红，属**真缺陷**，不是风格问题。
- 代价若错：record 的公开访问器 `bySubject()` 会暴露内部表示（内容不可变，可接受）；公开 API（`empty`/`put`/`get`）不变。
- 附带：新增相等性用例一组（同内容相等 + 同哈希、异内容不等、`empty()` 自等），并按三件套自证——变异方式为**把 record 临时改回 `final class`（身份相等）**，新用例必须转红。

**Park（T5 其余顾虑，非阻塞）**：② 「撤销一条 Info」无表达（只能新开有效期覆盖，「从此无值」无写法）→ 留 M4/领域 spec，**待决项**；③ 同 key 重叠条目永久驻留（历史可查 vs 单调增长）→ M4 存储侧；④ `InfoEntry.value` 是裸 `Object`，往返断言依赖其自身 `equals` → M4 序列化须明文限定允许的 value 形态；⑤ 总纲 §4.6 代码块仍是草案签名（`get` 无 key、`void put`）→ **T11 关账回填**（与 T4 `TimeRange` 收紧同批）；⑥ 实现者自加的 `put` 两条 null 用例**保留**（同裁决精神）；⑦ 方法论：「返回新实例」只有复合变异可判别，单点删 `Map.copyOf` 不转红——已并入 G13 契约。

### Task 6: 中止（用户切设备，2026-09-16）→ 待重派

用户指示「先停一下，提交并推送，换设备了」。已按令执行：
- `TaskStop` 终止 T6 实现者（当时自报：15 条用例通过、尚未 spotless、尚未跑变异、**未提交**）。
- 工作树残留：`simos-util/src/main/java/io/mosire/simos/util/state/` 与 `.../test/.../state/` **两个未跟踪目录**（其余跟踪文件干净，无变异残渣）。**控制器不提交它们**——未经 format/verify 的 WIP 会让分支上多一个门禁红的提交。
- **已推送**：`git push -u origin feat/m1-util-simos` 成功（19 commits 领先 main；`origin/feat/m1-util-simos` 已建，upstream 已设）。`main` 与 `origin/main` 均为 `56836f0`，未动。
- **恢复路径（换设备后）**：`git log --oneline main..HEAD` 是代码侧的完整恢复图；另见本台账（**注意：`.superpowers/sdd/` 被 `.gitignore` 的 `*` 忽略，默认不在远程**——若已提交则另见当次提交信息）。
- 重派 T6 时的 BASE 应是届时 HEAD；T6 的 dispatch 补充项见下方「Task 6: dispatched」段（含 `cp -p` 陷阱与反射用例自证要求）。

### Task 6: dispatched（BASE = `6e4c826`，工作树已核验干净）

**Ruling（T6 dispatch 携带）**：① 补 8 条 null/空白守卫用例（brief 只给 `blankBranchIsRejected`，其余守卫全空转），断言一律**钉字段级消息**；② 跨模块访问器反射用例要**变异到 `SimulationState` 自身**（brief 的 `NonCompliantState` 夹具只能证明检查器可用，不能证明它真的会抓本类）；③ `getDeclaredMethods` 名字清单若因合成方法而不稳，要求停下来点名而非硬编；④ **`cp -p` 陷阱**随本任务起写进 dispatch（另加 `touch` 或 `clean test` 纪律）。

### Task 7 前置裁决（控制器，spec 已就地回填并提交）

**发现**：brief/spec §七 的 `SegmentedSeries` 是 `final class`（只有身份相等），而 §十一 要求状态类型的 `equals` 由 record 提供（`TemporalSeries` 是状态类型，往返断言要拿它当判据，铁律 5）——**spec 自相矛盾**；且把它改 record 后，组件 `addition` 是函数，自动 `equals` 对函数只能**身份**比较。

**Ruling**：
- ① `SegmentedSeries` **定为 record**（`(segments, events, addition)` + `of(...)` 委托规范构造器；校验搬进紧凑构造器，公开 API 不变）。
- ② **含 `ADD` 的序列，`addition` 必须是共享实例（模块级 `static final`）**；内联 lambda 会让两个结构相同的序列不相等，并使 M2 起的往返断言以费解形态转红。**不**采用「手写 equals 排除 addition」——那正是本项目最贵教训（MapDiff 手工维护漂移）的形态；也不采用「专用比较器/从 equals 排除」，那些等于让铁律 5 对本类型失效。代价若错：域模块若内联算子，往返断言会红（**响亮的**失败，非静默漂移）。
- ③ 纪律须有**自证用例**：同一实例 ⇒ 相等；两个等价 lambda ⇒ 不相等（有意为之，把陷阱显式化）。
- 已回填 spec §七（含 M4 序列化的待决指路）。**T11 关账时一并记入**。

### Task 5: fix round 1 完成（`baeeb95`）→ fix round 2

**Ruling（T5 fix-1 新报的浅拷贝洞）**：`InMemoryInfoSystem` 紧凑构造器**必须逐值深拷贝**（`bySubject.forEach((k,v) -> copy.put(k, List.copyOf(v)))` 再 `Map.copyOf`）。
- 理由：spec §十一「集合防御性拷贝…一律 `List.copyOf`」+ §355「状态类型一律不可变」。浅拷贝让内层 `List` 与调用方共享 → 状态可被外部穿透改动（实现者探针实测：`clear()` 后 `get` 变空、`hashCode` 漂移）。可达路径窄（绕过 `empty()`/`put()` 直接 `new`）**不豁免**——T6 的 `apply` 自行拼 `Map` 构造是正常写法。
- 代价若错：每次构造多一次内层拷贝（可忽略）。**须补**一条能抓住内层别名的新用例（现有 `:163` 只覆盖外层 map）。
- 另：实现者顾虑 2（F1「record→class」对**负向**断言 `instancesWithDifferentContentAreNotEqual` 无判别力，由互补变异 F2「恒真 equals」证明其判别性）**接受**——负向断言的正常形态，报告已如实披露。顾虑 4 的 harness 事故已按「构建中断不得记作转红」处理并整表重跑 + sha256 复核。

### Task 5: 评审完成 → fix round 3（`2630e3f` 之前各轮已记）

**T5 审阅结果：规格符合性 ✅ / 质量通过**（无 Critical/Important，3 Minor）。评审者独立跑了 17 条变异（含 3 条自设探针），确认三条裁决成立、行号逐字吻合、门禁产物旁证 95/0 + SpotBugs 0 + Checkstyle 0。
- **Ruling（Minor 1，G13 强制补）**：`requireNonNull(bySubject, "bySubject")` 被判为**新发现的空转护栏**（删除后 13/13 全绿）→ **保留 + 钉消息用例**（同 T3/T4 先例）。代价若错：多一条断言。→ fix round 3。
- Ruling（Minor 2）：`theExposedMapIsImmutable` 后两条断言只有复合变异能红 → **不改代码，记档**（避免后续评审误当单点护栏）。代价若错：无。
- Ruling（Minor 3）：Javadoc 仍把 `put` 的 `List.copyOf` 称作战线，但 fix-2 后它已不可观测 → **改为如实口径**（唯一可观测防线是构造期深拷贝；`put` 的拷贝保留作纵深防御）。→ fix round 3。

### Task 5: complete（`0f14a95` → `baeeb95` → `2630e3f` → `6e4c826`，BASE = `f7785cb`）

**fix-3 复审：三条全部 ADDRESSED**，无新发现。范围干净（生产改动零代码、全在 Javadoc；测试 11 插入 0 删除）；Minor 1 的新用例**只有它**在删守卫时转红（`nullBySubjectIsRejected:205`）。
- **⚠️ 方法论级陷阱（复审者自曝，随 T6+ 下达）**：变异实验若用 `cp -p` 恢复文件，**保留旧 mtime 会让 Maven 增量编译跳过**，残留的变异体 class 会冒充基线（后续批次结果全不可信）。纪律：变异恢复后必须 `touch` 强制重编译，或直接 `./mvnw -pl simos-util clean test`。
- Park（留最终全支审阅）：`InMemoryInfoSystem` 的 map 值为 `null` 那臂无用例（原评审标"可选"，非阻塞）；`theExposedMapIsImmutable` 后两条断言仅复合变异可抓（已记档）。
- **本任务第 3 次撞到「只钉异常类型 → 守卫空转」**（T4-M7、T5 fix-1、本轮的 `bySubject`）→ 已固化为契约：**守卫断言一律默认钉字段级消息**。

### Task 5: fix round 1 下达（record 化）

### Task 5: dispatched（BASE = `f7785cb`，工作树已核验干净）

**Ruling（T5 dispatch 携带）**：
- ① **`InfoEntry` 的 key 校验与 4 条 `requireNonNull` 补用例**（brief 完全没有对应用例 = 空转）。理由：T3/T4 先例 + G13。代价若错：多 5 条断言。
- ② **判别力变异至少 5 条**，其中「`put` 返回新实例」这条要求**复合变异**（`empty()` 持可变 map + `put` 就地改并 `return this`）——因为内部 `List.copyOf`/`Map.copyOf` 本身不可变，单独删某个 copy 不会转红，不能当自证成功。代价若错：该护栏判别性未被独立证实。
- ③ **G13 证据契约升级版随本任务起执行**：三件套「变异 → 转红用例名 → 失败行号」，且**变异一律单跑 `-Dtest=`**（已实证：直接删 `requireNonNull` 会因 `UnusedImports` 让 Checkstyle 先打断，`exit=1` 但 `Failures: 0`，是假阳性）。

### Task 3: complete

提交：`2fca1ab`（6 文件，179 行）。验证：9/9（identity 三测试类）+ `-pl simos-util verify` BUILD SUCCESS（71/71 全模块）。
- 实现者自报 3 条顾虑，其中两条有价值：① 自己发现 M4 变异**首轮未转红**（`canonicalAddress` 空白校验的判据落在 `Address.parse` 上，是空转）→ 补 `hasMessageContaining("canonicalAddress")` 后稳定转红；② brief 的 `id == null` 校验无测试 → 自加 `nullIdIsRejected`（**审阅者独立变异确认：删掉该行只有这条用例转红 → 补得必要**）。
- **T3 审阅结果：Approved**（无 Critical/Important，5 条 Minor）。审阅者独立在 /tmp 编译并做字节级变异，逐条复核判别性（含 `List.copyOf` 删除、canonical 校验中和、typeName 守卫删除），并确认实现者「7 条护栏逐条转红」的说法**在关键几条上为真**；门禁结论由 surefire/spotbugsXml/checkstyle-result 产物旁证。
  - **Ruling（针对实现者顾虑 ① 的提问）**：`ResolvedSubject` 的空白校验**保留**——它与 `Address.parse` 行为冗余，但它是唯一点名出错字段（`canonicalAddress`）的诊断，且现在有消息断言钉住判别性。代价若错：无（多一行守卫）。
  - Park（非阻塞，留最终全支审阅）：② `SubjectId`/`ResolvedSubject` 四个复合守卫的 **null 分支**无用例（整块删除会转红，故非空转，只是覆盖面）→ **随 T4 携带补 4 条断言**；③ `QueryResult(null)` 抛 NPE 而非 IAE（plan-mandated，与兄弟守卫不一致）；④ `valueSemantics` 里混放拒绝断言、无负向相等用例（brief 位置，非实现者选择）。

### 构建配置修复（控制器，非任务）

`c7d32cf` fix(build): 删去 spotbugs 4.10.x 已移除的 `fork` 参数。
- 由来：T3 实现者报告 `verify` 有 `Parameter 'fork' is unknown ... :check` 警告。核实并实证：**`fork` 对 `check` 与 `spotbugs` 两个目标都不存在**（`help:describe` 参数表），而 `maxHeap`/`timeout` 是 `spotbugs` 目标的合法参数、且 `check` 执行前会先调用该目标 → 二者一直生效，只有 `fork` 从未生效。改前 `verify` 有 2 条警告（check + spotbugs），改后 0 条，71/71 全绿。
- 附带发现：`~/ProjectMosire/pom.xml:278` 是同一插件版本、同一段配置，**同样含无效的 `fork` 行**（那边也会有同样警告）——不属本仓，仅记录，已在本会话结束时告知用户。

### Task 2: complete

提交链：`e1eac8e`（测试，先 RED）→ `4188cc1`（实现）→ `687f732`（修复轮 1：≥3 段引号归一）→ `a94400b`（修复轮 2：条件 4 + 位置校验补全 + 契约收口）；控制器 spec 提交 `3ebb891`、`b55be33`。
验证：62 条全绿（38+15+9），`verify` 全绿；公开 API 全构造空间的往返与 canonical 唯一性经两方独立穷举确认零违规。修复轮 1 实现了 §3.2/§3.4 两条新裁决；修复轮 2 关闭了审阅者 fuzz 出的 1 Critical（`hex.1..2` 不可回解析）+ 1 Important（位置校验只守一半）。
**Park（非阻塞，留最终全支审阅裁量）**：
1. `AddressParser.java:139` 消息多一个空格（文案级，无用例断言）。
2. `Integer.parseInt` 接受非 ASCII 数字（`map:Map1:[٤]`、`[４]` → `Index([4])`），超出 §3.5 新明文清单；不破唯一性/往返，建议收紧为 `[+-]?[0-9]+` 或写进 spec。
3. `Character.isWhitespace` 为 false 的空白类字符（NBSP/U+2007）不算空白：canonical 里人眼不可区分，spec §3.2/§3.4 的"空白"未钉字符类。
4. `map:Map1:region.` 的诊断文案建议"加引号"而非写成 `""`（实现者自陈，无用例覆盖）。

经验记档（供后续任务派发参考）：① plan 代码块与 plan 自带测试可能互相矛盾——实现者应以 spec 为准并当场报告；② 我转述审阅者建议时也会夹带错误（`isBareWord` 公式丢 `.` 否决），**既有护栏用例是最后防线**；③ G13 用例必须核算"删掉被测代码后它是否转红"，空转护栏（`List.of` 源）骗过了第一轮，只被复审用 jshell 拆穿。
- **T2 追加携带（控制器裁决，随 T2 派发与审阅者约束块下达）**：
  1. `Address` 构造器新增位置校验——第 ≥3 段不得是缺 kind 的 `Entity`（§3.2「裸词主体只出现在根位置」），否则 `Entity(∅,"member")` 与 `Property("member")` canonical 撞车；带 G13 违规用例。
  2. 往返用例补两条 T1 已证实会产出的 canonical 形态：`map:Map1:region."A B"`（内部空白，§3.4 条件 2 收紧的后果）必须 parse→canonical 恒等；`map:Map1:region.""`（空 name）必须能解析且不被 §3.5「空段」规则误杀；而 `map:Map1::x` 这类**真·空段**仍须抛。


