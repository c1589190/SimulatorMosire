# M3 报告 —— sd 域 12 条窄写工具（只进 GM 桶）

## 1. 状态

**DONE_WITH_CONCERNS**（交付物与判据全部落地且门禁绿；三条 concern 属于**证据路径与对账口径**，不影响 12 条工具本身）。

Concern 摘要（逐条证据见 §4 / §5）：

1. **简报 §7 的 m2 原样形态不是"被杀"**：它让 58 条用例全部 error 在 `@BeforeEach`（装配期重复登记），红的不是"名字同源"判据本身 ⇒ 按"红的理由必须是被保护的那行"**不计杀**；用 **m2b**（改成**未注册**类型）在同一判据上取到红（`SimosToolsTest:539`）。
2. **§4.1 对账：现场量出 6 条用例红 / 7 处常量**，与简报的 4 处、控制器的 6 处**都不相等**——多出的三处两张清单都没列（其中一处是**实际最先红**的那条）；简报的"第 1 处必红"被同方法内更靠前的 GM 断言**掩掉**，已用 m5b 单独证实它有独立牙齿。见 §5 表。
3. **原始那次"接线后第一次跑"的红没有留档**（当时没存日志）⇒ 上面的构成是**现场重建**（m5 / m5b：把三份手抄名单与桶大小退回 M3 之前、工具保持接线），**不等于**当时那一次真实运行的复现：重建时文件里已含我新写的 §5.2 / §5.5 用例与 `SD_WRITE_NAMES`（保持 12），当时还没有。

## 2. 提交

- **实现提交 `aba3326`**（`aba33261ded0b38bc10fa37a1a64cbe06b6ad365`）：17 个文件，+819 / −20，**只动 `simos-app`**。
- **证据与报告提交**：本文件所在的那个提交（sha 见 `git log --oneline -3`；报告随第二次提交入库后另有一次只补记 sha 的小提交）。
- 台账 `progress.md`、`pending-rulings.md`、计划 `docs/superpowers/plans/2026-09-22-tool-surface-plan.md` **一个字节都没动、也没 stage**（提交时按路径逐个 `git add`，未用 `-A`、未用 `.`）。

## 3. 一行测试小结

`./mvnw clean verify`（**第 2 次尝试**）rc=0、8/8 模块 `SUCCESS [`、**1449** 条 = 170/369/45/259/179/141/286、Failures 0 / Errors 0、`BugInstance size is 0` ×7、`[ERROR]` **0 行**、`[frontend-gate] OK tests=204 pass=204 fail=0`、`BUILD SUCCESS`（日志 `logs/clean-verify.attempt2.log`，md5 `49278958f7eda715700f376941773d`，rc 文件内容 `0`）。
第 1 次尝试 rc=1：**Spotless 判我新增 Javadoc 的折行不合格式**（`log_prefix` 见 `clean-verify.attempt1.log`）⇒ `./mvnw -q spotless:apply -pl simos-app`，并用**另写的、双侧自证过的** checker 证明"只动了注释"（`strip-compare.py` + `strip-compare-selftest.py` 4/4，原始字节留档 `mutants/pre-spotless/`）。
★ 总数是**现场从原始日志重算**的（`recompute.py` 只取模块汇总行），未引用任何文档里的现成数字。

## 4. 判据 §5 逐条实测值

### §5.1 桶正确

| 桶 | 断言（用例 `roleBucketsNeverCarryGenericWrite` 全绿） | 运行时独立见证（真 MCP 端口列举） |
|---|---|---|
| EXTERNAL | `.hasSize(12)`；`.doesNotContainAnyElementsOf(SD_WRITE_NAMES)` | — |
| GM | `.hasSize(52)`；`.containsAll(SD_WRITE_NAMES)` | `外发工具 31 个`（决策人口）与 `外发工具 55 个`（现有口）各出现 **180** 次 |
| DECISION_AGENT | `.hasSize(31)`；`.doesNotContainAnyElementsOf(SD_WRITE_NAMES)` | 同上（31） |
| EXTERNAL_WITH_GM | `.hasSize(55)`；`.containsAll(SD_WRITE_NAMES)` | 同上（55） |

现场数名单（`count-lists.py` 逐条打印，非引用文档）：`SD_WRITE_NAMES` **12**、`READ_TOOL_NAMES` **9**、`WRITE_TOOL_NAMES` **46**、`MAP_WRITE_NAMES` **7**、`UNIT_WRITE_NAMES` **20**。

### §5.2 名字同源

- `sdNarrowWriteToolsAreNamedAfterTheirFixedCommandType` 绿：GM∖读名单 `.containsExactlyElementsOf(SD_WRITE_NAMES)`（`:539`）、GM / EXTERNAL_WITH_GM 各 `.containsAll(SD_WRITE_NAMES)`（`:542` / `:545`）、catalog `.containsAll(SD_WRITE_NAMES)`（`:546-549`）。
- `catalogListsExactlyTheRegisteredCommandTypes`（`:554`）绿 ⇒ catalog 集合 == 注册表集合（12 个 sd 类型都在 catalog 里）。
- 名字不是独立字段：`NAME` 常量 → `commandType()` → `name()` 同源派生；实测 GM 桶里那 12 条与 `SD_WRITE_NAMES` 逐字相等（`.containsExactlyElementsOf` 而非 `containsAll`）。

### §5.3 敏感写

`writesAreSensitiveAndAskWithTheToolNameAsClassKey`（`:769`）绿，对**写闸覆盖集**（现场 46 条）**逐条**断言：`spec().sensitive()==true`、`noExport()==false`、`gate() instanceof ToolGate.Ask`、`classKey()==name()`、`kind()==AskKind.SENSITIVE`；同用例 `:773` 断言覆盖集 `.containsExactlyInAnyOrderElementsOf(WRITE_TOOL_NAMES)`（46 条，含 12 条 sd）。

### §5.4 前置即错（12 条独立用例）

行号（终态字节）：`:1138, :1156, :1168, :1196, :1203, :1216, :1226, :1238, :1250, :1271, :1285, :1298`；共享助手 `assertDomainRejectedAndHeadUnchanged`（~`:1348`）断言 `success()==false`、`code()=="REJECTED"`、`JSON.readTree(message()).get("reason").asText()` 含关键片段、**head 不变**。**不是循环**，每条各一个 `@Test`，**每条先造出使该前置可达的状态**（先成功建一次 / 先建交战国+阶段+结局表 …）。

门禁日志里 12 条关键片段**各出现 ≥1 次**（现场计数）：

| 片段 | 次数 | 片段 | 次数 |
|---|---|---|---|
| `无国家 tag` | 1 | `结局不在该阶段的 outcomeTable 里` | 1 |
| `nationId 不存在` | 1 | `未知装备键` | 2 |
| `不得含通用写` | 1 | `action 引用的阶段不存在` | 1 |
| `地址至少两段` | 2 | `效果不存在` | 1 |
| `交战已存在` | 1 | `决策人不存在` | 4 |
| `交战不存在` | 1 | `阶段不存在` | 2 |

（`决策人不存在` 的 4 次含 `sd.SetViewScope` / `sd.StartDecision` 等其他用例；`地址至少两段` / `阶段不存在` / `未知装备键` 的重复同理。片段文本取自**当场跑出来的原文**，非猜测。）

### §5.5 同源强判据

`everyNarrowWriteToolClassIsWiredIntoTheGmBucket`（`:709-718`）绿：`:712` 是**非空 + 条数**守卫 `hasSize(43)`（现场扫到 43 个窄写工具类），`:717` `.containsExactlyInAnyOrderElementsOf(implemented)`（GM∖读名单 == 扫到的实现集合）。

## 5. 变异（按终态字节重跑；红点行号为**终态字节的行号**）

| 变异体 | 判决 | 红点 / 实测输出 |
|---|---|---|
| **m1** 删 `addGmWrites` 里一条 | **KILLED** | 现有口正向精确匹配红：`McpPortTopologyTest.existingPortExposesExternalUnionGmToolFace`（终态 `:154-155`）。日志 `mutants/logs/m1.attempt3.log` |
| **m2** `commandType()` 改成**另一个已注册** sd 类型 | **不算杀（wrong-reason）** | `Tests run: 58, Failures: 0, Errors: 58`，58 条全部 error 在 `@BeforeEach startShell:394`（`java.lang.IllegalArgumentException: 工具名重复: sd.CreateArmy`）⇒ 红的是**装配期重复登记**，不是"名字同源"判据本身。日志 `m2.attempt2.log` |
| **m2b** 改成**未注册**的 sd 类型 | **KILLED** | `SimosToolsTest.sdNarrowWriteToolsAreNamedAfterTheirFixedCommandType:539`（`containsExactlyElementsOf(SD_WRITE_NAMES)`）。日志 `m2b.attempt2.log` |
| **m3** 孤儿工具类 `write/SdFooTool.java` 不接任何桶 | **KILLED** | `everyNarrowWriteToolClassIsWiredIntoTheGmBucket:712`，`Expected size: 43 but was: 44` ⇒ 被 §5.5 的**第一条（非空/条数）守卫**拦下，`:717` 的那条集合相等**这一轮没执行**（"没跑到"）；`:717` 的独立牙齿由 m1（红在现有口精确匹配）与 m2b（红在 `:539`）在**同一行**给出。孤儿文件已删、干净世界已复核。日志 `m3.attempt2.log` |
| **m4** 给 `SdSetDecisionMakerProviderTool` 加 provider 存在性校验 | 预期**两半都实测** | ① `SdProviderBindingEndToEndTest` **4/4 保持绿**（它走 `core.submit(envelope(...))`，**绕过工具层**）——与更正后的预期一致；② 绕过演示实测：通用写 `simos.command.submit` 带**从未注册**的 `providerId` 仍 **Committed**（`sd.SetDecisionMakerProvider … 新坐标=main@5`）⇒ 工具层校验确为装饰。**额外观察**：该变异体把既有 §5.4 用例 `sdSetDecisionMakerProviderToolSurfacesTheDomainRejectionForAnUnknownDecisionMaker:1300` 变成 **Error**（`Unrecognized token 'provider'`：变异体返回裸文本而非折好的 JSON）——比"可绕过"更早暴露问题。日志 `m4.attempt2.log` |
| **m5**（**测量轮，不是杀/存活判定**）工具全接线、三份手抄名单与桶大小退回 M3 之前 | 产出=红点清单 | `Tests run: 66, Failures: 6`；`COMPILATION ERROR` **0**、`Tests run` 行 **66**（两道作废闸都过）⇒ 是"红"不是"没跑"。日志 `m5.attempt3.log` |
| **m5b**（同上，只回退 externalWithGm 一处） | 产出=独立牙齿证明 | `Tests run: 58, Failures: 1`，红点 `roleBucketsNeverCarryGenericWrite:639`，`Expected size: 43 but was: 55`。日志 `m5b.attempt2.log` |

**VOID 轮（留档不删）**：`m1.attempt1`（Checkstyle `UnusedImports` 早于 surefire）、`m5.attempt1`（我的 m5 合并行多补一个引号 ⇒ 未闭合字面量，装置的 `COMPILATION ERROR` 闸当场拦下）、`m5b.attempt1`（我的 m5b Python 元组解包错）。

### §4.1 对账 —— "接线后必须红的地方"（现场量，非回忆）

m5 那一轮（工具全接线、常量未跟上）实测 **6 条用例红 / 7 处常量**：

| # | 红点（终态字节行号） | 涉及常量 | 简报 §6 列了？ | 控制器 §4.1 列了？ |
|---|---|---|---|---|
| 1 | `McpPortTopologyTest.existingPortExposesExternalUnionGmToolFace:154-155` | `GM_NARROW_WRITES` | ✅ 第 3 处 | ✅（"McpPortTopologyTest 常数的来源处"） |
| 2 | `McpServerTest.initializeAndToolsListExposeExactlyTheExternalUnionGmTools:216` | 手抄名单 #2 | ✅ 第 2 处 | ✅ |
| 3 | `SimosToolsTest.roleBucketsNeverCarryGenericWrite:606`（GM 桶 `40→52`） | 内联 `.hasSize(40)` | ❌ | ❌ |
| 4 | `SimosToolsTest.roleBucketsNeverCarryGenericWrite:639`（现有口 `43→55`） | 内联 `.hasSize(43)` | ✅ 第 1 处 | ✅ |
| 5 | `SimosToolsTest.registryContainsExactlyTheExternalUnionGmTools:421` | `EXTERNAL_UNION_GM_TOOL_NAMES` | ❌ | ❌ |
| 6 | `SimosToolsTest.writesAreSensitiveAndAskWithTheToolNameAsClassKey:773` | `WRITE_TOOL_NAMES`（写闸覆盖集相等） | ❌ | ❌ |
| 7 | `SimosToolsTest.everyNarrowWriteToolClassIsWiredIntoTheGmBucket:712` | §5.5 `.hasSize(31)` | ❌ | ✅ |

三条要点（都已在案）：

- **#3 是"实际最先红"的那条**，而它与简报的第 1 处（#4）**同在一个测试方法里**、位置更靠前 ⇒ AssertJ 在方法内第一次失败即抛出 ⇒ **简报的"第 1 处必红"被它掩掉**（m5 那一轮 #4 一次都没执行）。**m5b** 把 GM 留在 52（绿）、只回退 #4 ⇒ 红点恰落在 `:639`（`43 vs 55`）⇒ **#4 有独立牙齿**，与"被掩掉"是两件事、已分开。
- **#5 / #6 是两张清单都没列的耦合**：简报 (a) 确实要求"把 `WRITE_TOOL_NAMES` 34⇒46"（工作没漏），但它把 `writeFaceCoveredByTheWriteGate()` 判成"自动跟上"——**派生的是 helper 的取值，不是断言里的期望集**，期望集仍是手抄常量 ⇒ 不回退常量时 `:773` 会红。
- **简报第 4 处（`catalogCovers…` 那类精确匹配"若存在"）实测不红**：`catalogListsExactlyTheRegisteredCommandTypes` / `catalogCoversEveryCommandHandlerImplementation` 两条在本轮**全绿**，与简报"若存在"的措辞一致（即这种必然红点在本仓不存在）。

## 6. §我未能核实的

1. **原始那轮"接线后第一次跑"的红没有留档**（见 §1 concern 3）：§5 的对账表是**重建**，重建形态 ≠ 当时那次运行（当时测试文件里还没有我新写的 §5.2 / §5.5 用例）。
2. **变异轮跑在 `./mvnw -pl simos-app -am … test` 下**：该生命周期**不跑 SpotBugs、不跑 `spotless:check`**（`spotless:check` 绑在 `verify`）⇒ 变异体字节**未经 SpotBugs 分析**；只有**干净世界**过的是全量 `clean verify`。
3. **§5.4 的 12 条坏载荷路径只在工具层（in-process）核过**：未走**真 MCP 传输**逐条复验（`McpCoverageTest` 走 MCP 提交的是**合法**载荷、且是全量 verify 里跑的，未按 12 条工具逐条对拍拒绝文案）。
4. **§5.3 只证到工具层 `gate()` 的返回值**（`sensitive` / `Ask` / `classKey` / `SENSITIVE`）：**审批链真放行与真拒绝**在 M3 未重跑（M5 T6 核过，本轮未复验）。
5. **12 条 `description()` 只与简报文本逐字一致**：未逐条与 recon §3.2 的 S1~S9 原句对拍（简报是本任务唯一需求来源，故按简报逐字抄）。
6. **`EXTERNAL_WITH_GM` 的 55** 由 `toolsFor` 断言 + 运行时端口列举两路证明；**同一桶在两个 MCP 端口上的并发访问**未测（T4 已测"两口并存"，本轮未重跑）。
7. **`simos.command.submit` 绕过工具层**只用 m4 的 provider 一条做了演示；另 11 条工具**未经通用写绕过**逐条验证。
8. **装置回显有一处 cosmetic 错标**：`mut-round.sh` 的还原段落打印"干净世界 OK：16 个基线文件"，实际核对 **17** 个（`check-clean.sh` 输出"核对 17 个文件，0 个与基线不同"）。**故意不改**：改它会让所有日志里记的 `harness_md5` 与磁盘不符。
9. `m5.py` 在 attempt2 与 attempt3 之间被改过（补 `McpServerTest` 分支）⇒ 两轮自记的 `spec_md5` 不同（`4c011b28…` / `bd82a613…`），**各自有效且互不覆盖**；引用时**必须取对应那一轮的自记块**。
10. `m5` / `m5b` 是**测量轮**：它们的产物是"红点清单"，**不得**记成"KILLED"。

## 附：证据清单（自指 md5；每份日志自带"这一轮跑的是哪份字节"）

门禁：`logs/clean-verify.attempt2.log` `49278958f7eda715700f376941773d`、`…attempt2.rc` 内容 `0`、`attempt1.log` `15583daf45d0e356fd2f37e0e3a14d02`（Spotless rc=1）、`logs/spotless-comment-only.attempt1.log` `3dff5d259e6eb3361d072279b24db444`。
变异日志：`m1.attempt3.log` `7102ee1ab0ead874ede9388658264b23`、`m2.attempt2.log` `c1e057b21480245197812e7f64b0ef35`、`m2b.attempt2.log` `4d6ce28a6c8c90dc34f3c149f1b0f562`、`m3.attempt2.log` `48253d4c3c8b740946ab98624e438619`、`m4.attempt2.log` `6f39b3facaecac5d440022cebd666e58`、`m5.attempt1/2/3.log` `b7b4221089f62a5991b10c22df088a4f` / `cfb1219f8a0813264cb5ab3a74232e35` / `a17f5f62ac23520ee9f4f13a6d840a12`、`m5b.attempt1/2.log` `641e78489280ea934ed89c3966519b67` / `fdb6ecddbc926d09bde9c8666708019c`。
装置与脚本：`mut-round.sh` `bbebdd6e4dc495a4c1f9151179f0fdf8`、`baseline.md5` `bc9b1d0c7ccec4fa120d01fc8dba4838`、`m1.py` `f451d0748dbef3a6da7b3a2ae99c9abb`、`m2.py` `853e74df5c979b85c6bef4ae2210a513`、`m2b.py` `8ab7d5779078c8e7290b610ba7406292`、`m3.py` `9e81fc5d196d9e422d923289163b449d`、`m4.py` `33055001c97114b9db57175e02fc2b11`、`m5.py` `bd82a6137f7de8c1f1759c34d46545ed`、`m5b.py` `95efa8b3c0e6c272d570f868b2039d5d`、`linemap.py` `24f199f811c17688b278e3645d31a101`、`count-lists.py` `582645ca58071a067467ef5fee1f30f6`、`recompute.py` `5083049de13ee7cb1af7883843a20f37`、`check-clean.sh` `c4f2167da12186e0f15f3c413856ca1a`、`run-gate.sh` `038b3505c6da6b2bd2fbf7cca402d302`、`strip-compare.py` `b70a8a5d0a36b3e934cab03330245088`、`strip-compare-selftest.py` `529b05e3ce78e97ffa078644a905cf5a`、`write-whitelist.txt` `87f3ec63fae21dcd2cf3b58744b049e2`。
