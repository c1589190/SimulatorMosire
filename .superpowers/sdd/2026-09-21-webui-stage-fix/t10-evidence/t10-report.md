# T10 报告 —— 「开始决策」入口与权限（`sd.StartDecision`）

> 分支 `wsf/t10`；worktree `/home/cna/SimulatorMosire/.claude/worktrees/wsf-t10`；基线 = `abd3086`（含 T9 的合并提交，`git log` 实测）。
> 判据 = spec `C18` ①②③ + `C19`（spec §七.3）；依赖 T4/T5/T7（均已在基线里）。
> ★ 本报告含 **§六「我未能核实的」**，不把"分析/推断"写成"实测"。

---

## 一 做了什么（逐项对计划 §T10）

| 计划步骤 | 落点 | 状态 |
|---|---|---|
| 1 新命令 + handler | `simos-sd/.../sd/spi/StartDecisionHandler.java`（`type()="sd.StartDecision"`）；载荷 `{decisionMakerId, note?}` | ✅ |
| 2 `CatalogTool.PAYLOAD_HINTS` 加项 | `simos-app/.../tools/read/CatalogTool.java`：+`Map.entry("sd.StartDecision", "decisionMakerId, note?")` | ✅ |
| 3 `SimosToolSource` GM 桶加工具 | 新 `tools/write/StartDecisionTool.java`；`addGmWrites` +1（GM 桶 3→4）；决策人桶**不加** | ✅ |
| 4 `GuiServer` 加端点（用户路径） | `POST /api/sd/start-decision`（`startDecisionReply`；经 `core.submit`，`initiator="player:gui"`，命令类型**服务端写死**） | ✅ |
| 5 `McpCoverageTest` 喂载荷 | `EXPECTED_COMMAND_TYPES` 41→42；`MINIMAL_PAYLOADS` +`sd.StartDecision`（放最后，不移动既有 revision 号）；head 断言 42→43、advance 43→44、fork 44 | ✅ |
| 6 审批路径测试 | 新 `StartDecisionEndToEndTest`（5 条：C18①②③ + 未知 dm + R4 不占位） | ✅ |
| 7 定向 | 定向 + 两轮全量（见 §三） | ✅ |
| 额外：前端入口 | `index.html` 按钮 `#decision-start`（**默认 disabled**）+ `#decision-start-status`；`panels.js` 纯闸门 + 发起动作；`api.js#startDecision`；`modes.js` 决策模式 `writes=["sd.StartDecision"]` | ✅ |

### 1.1 三处会被"漏一项即红"的静默面（都补了）

- `CatalogTool.PAYLOAD_HINTS`：缺项**构造期抛**（app 起不来）——由 `SimosToolsTest.catalogRejectsACommandTypeWithoutAPayloadHint` 与 T9 的三级谱守卫；本轮 `t10m4` 变异实测把它打红（`IllegalArgumentException: 已注册命令类型未登记载荷提示（PAYLOAD_HINTS）: [sd.StartDecision]`）。
- `McpCoverageTest` 双向载荷：catalog 每 type 备载荷 + head 逐条前进（41→42 同步改）。
- 两个**独立抄本**的工具面清单：`SimosToolsTest.EXTERNAL_UNION_GM_TOOL_NAMES`（15→16）、`McpServerTest`（`15→16`）、`McpPortTopologyTest.GM_NARROW_WRITES`（3→4）——**第一次全量门禁就是被后两者打红的**（见 §三.1）。

---

## 二 ★ 三条关键裁定/设计决定

### 2.1 ★★ `sd.StartDecision` **不写 `Directive`** —— 否则会占掉 R4 名额

`IssueDirectiveHandler` 的 **R4 硬不变量**是「同一 `(decisionMakerId, tick)` 至多一条 `Directive`」（命令期 + `SdState` 状态期两层）。若「开始决策」也写一条 `Directive`（**哪怕 `PLANNED`**），同一 tick 其后的 `sd.IssueDirective`（决策人出令）**必被 R4 拒**——那正是本任务点名要处理的冲突。

**决定**：本命令写的是"发起/授权"这一**事实**，落在 sd 自有 **INFO 覆盖层**（地址 `sd:decision.<dmId>`，key `start`，`value` = 该命令所在 tick 的**标量串**，`note` = 可选说明），**不碰 `directives`**。`value` 取串而非数值：`SdInfoEntry.value` 是裸 `Object`，数值类型跨 JSON 往返会 Long↔Integer 漂移（与 M4 裁定 38 同口径）。

**判据**：
- handler 级 `StartDecisionHandlerTest.recordsStartInTheInfoLayerAndWritesNoDirective`（断言 `directives()` 为空）+ `doesNotConsumeTheR4DirectiveSlot`（该 tick 已有 Directive 时仍成立、且不改动 directives）；
- 端到端 `StartDecisionEndToEndTest.startDecisionDoesNotConsumeTheR4DirectiveSlot`（先 GUI 发起、再同 tick `sd.IssueDirective` ⇒ **200 committed**、head 2→3）；
- ★ 变异 `t10m5`（让 handler 额外写一条 `PLANNED` Directive）**KILLED**——红点正是上面那条端到端断言。

### 2.2 用户路径 = **GUI 窄端点**；GM Agent 路径 = **MCP 窄工具 + 审批门链**（D6）

- **用户**：`POST /api/sd/start-decision`（命令类型服务端写死，前端不传 type）⇒ 直接 `core.submit`，`initiator="player:gui"`，**不经审批**。
- **GM Agent**：现有 MCP 口（`EXTERNAL_WITH_GM`）的 `sd.StartDecision` 窄工具（`ToolSpec.level(DEFAULT, sensitive=true)` + `gate()=Ask(SENSITIVE)`）⇒ **过审批门链**（真 `ToolCallAuthorizer` + `ApprovalCoordinator`）。
- **决策人 Agent**：决策人口（`DECISION_AGENT` 桶）**没有**该工具。
- ★ **D6 的"端口/桶区分"用 T4 的既有拓扑实现**：`Shell` 的两口各持一个 `ToolRegistry`（`EXTERNAL_WITH_GM` / `DECISION_AGENT`），审批由 `startHttp(..., toolAuthorizer)` 注入——本任务**未改审批链**，只把工具加进 GM 桶。

### 2.3 前端写面：决策模式 `writes` 由 `[]` ⇒ `["sd.StartDecision"]`（spec §四.2）

- spec §四.2 的条件句「若 D5 选新命令，则加 `sd.StartDecision`」在 D5 已裁"新命令"后**生效**；`modes.test.cjs` 的 `decision-allows-no-write`（T7）随之**改名为** `decision-allows-exactly-start-decision`（断"恰一条"，未改成恒真）。
- 前端**不**走通用写：按钮经 `api.startDecision`（窄端点）；`panels.js` 内**仍无** `writeCommand(`（T7 的既有断言保留且仍绿）。
- 按钮**只在 `due === true` 时可点**（`panels.js#startDecisionGate` 纯函数；`due` 缺失/`null` ⇒ 不可点，口径同 `pendingStatusText` 的"不造假"）。

---

## 三 门禁（★ 现场重算，只取模块汇总行）

### 3.1 ★ 最终绿轮是哪个文件

- **`logs/clean-verify.after-mutants-GREEN.log`** = **最终绿轮**（变异轮全部还原后复跑；`logs/verify-rc-final.txt` = `RC=0`）。
- `logs/clean-verify.attempt2-GREEN.log` = 修 `attempt1` 两处断言后的绿轮（**变异前**）。
- ★ **`logs/clean-verify.attempt1-FAILURE.log` = 留档的失败轮**（`RC=1`，**不是**最终绿轮；与 T9 那次同形，别被文件名误导）。它抓到两处**真实的漏改**：`WebuiAssetsTest.allSixModesAreEnabledRealControls`（新按钮内文含子串「决策」）与 `McpServerTest`（工具面 15 未改 16）。

### 3.2 实测值

| 项 | 值 |
|---|---|
| `./mvnw clean verify` | **rc=0**（最终绿轮 **第 1 次尝试**；attempt1 为失败轮，见上） |
| 模块 | **8/8 `SUCCESS [`**：`SimulatorMosire`/`UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp` |
| 用例总数 | **1347** = `170/368/45/259/178/129/198`（Util/Map/Social/Unit/Core/SD/App） |
| 基线（本机现场重算） | **1337** = `170/368/45/259/178/124/193`（HEAD=`abd3086`，独立 worktree 跑 `clean verify`，`logs/baseline-verify.log`） |
| delta | **+10 干净**：`sd 124→129`（`StartDecisionHandlerTest` 5 条）+ `app 193→198`（`StartDecisionEndToEndTest` 5 条）；其余五模块逐字不变 |
| SpotBugs | `BugInstance size is 0` **×7** |
| 日志 | `[ERROR]` **0 行** |
| 前端门禁 | `[frontend-gate] OK tests=164 pass=164 fail=0`（159→164，+5；`run-gate.cjs` 与 `gate-contract.test.cjs` **两处下界同改**；未新增 JS 文件 ⇒ `REQUIRED_FILES` 不变） |

重算过程留痕：`logs/recomputed.txt`（含两份日志 md5）；只取 `^\[INFO\] Tests run:` 且**排除 `-- in`** 的模块汇总行。

---

## 四 变异（九道门禁；13 体 13 KILLED / 0 SURVIVED）

装置：`mutants/mut-java.sh` + `mutants/make-mutant.py`（Java 9 体）、`mutants/mut-js.sh` + `mutants/make-js-mutant.py`（前端 4 体）。九道门禁逐条落实：干净世界（pristine 恢复后 md5 == pristine）、变异体字节不同（自记 md5）、**推到规范名目标文件**、清陈旧 `.class` 与 `surefire-reports`（**sd 与 app 两处**）、强制 `COMPILATION ERROR=0` 且 `Tests run>=1`、**surefire 报告 mtime 落本轮内**、红点落**被保护断言**、`cp` 逐字节还原（**绝不 `git checkout`**）、日志自指（md5 写入日志本身；读取处先断言非空）。

| 变异体 | 靶（pristine） | 语义 | 被保护断言（红点） | 判定 |
|---|---|---|---|---|
| `t10m1` | `GuiServer.java` | 用户路径信封 `initiator` 改坏 | `StartDecisionEndToEndTest.guiUserPathCommitsDirectlyWithoutApproval`（`player:gui` 断言） | **KILLED** |
| `t10m9` | `GuiServer.java` | 用户路径**不经 `core.submit`** 直接回 200 | 同上（缺 `ref`/head 不动）——**"不落 revision"** | **KILLED** |
| `t10m2` | `AbstractNarrowWriteTool.java` | `gate()` 由 `Ask` 改 `ALLOW`（**GM 绕过审批**） | `...gmAgentPathIsDeniedWithoutApprovalAndCommitsAfterApproval:199`「C18②：GM 口的写工具必须先进审批」（另 `SimosToolsTest.writesAreSensitiveAndAsk...`） | **KILLED** |
| `t10m3` | `SimosToolSource.java` | **决策人桶错加** `StartDecisionTool` | `...decisionAgentPortHasNoStartDecisionTool`（C18③；另 `McpPortTopologyTest` C7） | **KILLED** |
| `t10m8` | `SimosToolSource.java` | GM 桶**漏加**该工具 | `...gmAgentPathIsDenied...:199`（GM 口工具不存在 ⇒ 不进审批） | **KILLED** |
| `t10m4` | `CatalogTool.java` | `PAYLOAD_HINTS` 漏项 | `新测试 @BeforeEach` ERROR：`IllegalArgumentException: 已注册命令类型未登记载荷提示（PAYLOAD_HINTS）: [sd.StartDecision]`（app 起不来） | **KILLED** |
| `t10m7` | `Shell.java` | handler **未注册** | `...guiUserPathCommitsDirectlyWithoutApproval:160`（422「未注册的命令类型: sd.StartDecision」） | **KILLED** |
| `t10m5` | `StartDecisionHandler.java` | 额外写一条 `PLANNED` Directive（**占 R4 名额**） | `...startDecisionDoesNotConsumeTheR4DirectiveSlot` + handler 级 `recordsStartInTheInfoLayerAndWritesNoDirective` | **KILLED** |
| `t10m6` | `StartDecisionHandler.java` | 不再拒未知决策人 | `StartDecisionHandlerTest.rejectsUnknownDecisionMaker`（另 app `guiUserPathRejectsUnknownDecisionMakerWithoutRevision`） | **KILLED** |
| `t10js-m1` | `panels.js` | 闸门恒 `enabled`（忽略 `due`） | `not ok 19 start-decision-gate-requires-due` | **KILLED** |
| `t10js-m2` | `panels.js` | 发起动作丢掉闸门守卫 | `not ok 23 start-decision-refuses-when-target-is-not-due` | **KILLED** |
| `t10js-m3` | `api.js` | 窄端点路径写错 | `not ok 158 api.js-declares-exactly-the-allowed-write-endpoints` | **KILLED** |
| `t10js-m4` | `panels.js` | 按钮不再绑定发起动作 | `not ok 34 panels-js-and-app-js-delegate-subpage-visibility` | **KILLED** |

逐轮证据：`mutants/logs/t10m*.log`（含"装置补记"自指段）、`mutants/logs/mut-java-summary.txt`、`mutants/logs/t10js-m*.tap`、`mutants/logs/mut-js.log`。还原自证：**9 个 Java 靶的 `restored_md5 == orig_md5`**、4 个 JS 靶 `restored_equals_orig=true`；工作树与 `mutants/pristine/` 逐文件 md5 相等（本报告末尾 §五 复核）。

### 4.1 ★ 装置自身的两次迭代（如实记）

1. **首轮 3 个变异体判 SURVIVED，实为 VOID**：`t10m2`/`t10m8`/`t10m7` 在 `test` 生命周期的 **Checkstyle `UnusedImports`** 处失败（删掉唯一使用点后 import 变未用）⇒ **根本没跑到用例**。按纪律「"没跑到" ≠ "没红"」，**不记成存活**。修法：变异体**同时删掉那条 import**，保持编译/风格通过，使其真正到达断言 ⇒ 重跑 **KILLED**。
   ⇒ 可复用形态：**删掉某 API 的唯一使用点时，也要删它的 import**，否则门禁在测试之前拦下，得到的是"VOID"而不是"KILLED"。
2. **`t10m4` 首轮判 SURVIVED 是"判据抽取太窄"**：`CatalogTool` 构造抛发生在 `@BeforeEach`，surefire 记的是 `<<< ERROR!` 行（**只有测试名、无消息**），而装置只按 `<<< (FAILURE|ERROR)` 行匹配 `expect` 正则 ⇒ 消息行里的关键词匹配不到。修法：把 `expect` 改成**报错测试名**（`guiUserPathCommitsDirectlyWithoutApproval`），并把抛出的异常原文一并落进日志 ⇒ **KILLED**，且红点可归因（`已注册命令类型未登记载荷提示`）。

### 4.2 任务点名的"用户点也走审批 ⇒ 应红"为何没有独立变异体

审批（`Ask` → `ApprovalCoordinator`）**只装配在 MCP 工具的 `ToolCallAuthorizer.execute` 路径上**；GUI 端点走 `core.submit`（无审批面）。因此"让用户路径也走审批"没有等价的**单行**变异形态。其**可判读的等价面**由两条变异覆盖：`t10m1`（用户路径身份写坏）与 `t10m9`（用户路径不落 revision）——两条都 KILLED，且 C18① 的 `pendingApprovals().pending()` **为空**断言本身就钉住"用户路径不经审批"。**如实记为"未构造该形态"，不冒充覆盖。**

---

## 五 复核（变异轮后）

- 工作树与 `mutants/pristine/` 逐文件 md5 相等：`GuiServer.java 8e7fd8ba…`、`AbstractNarrowWriteTool.java db14c720…`、`SimosToolSource.java 080ce3ce…`、`CatalogTool.java 429d3a3c…`、`StartDecisionHandler.java b599fad7…`、`Shell.java fd1d6588…`、`panels.js 7f850c1a…`、`api.js d2a3604b…`（全 `OK`）。
- **变异轮后复跑全量 `clean verify` rc=0**（`logs/clean-verify.after-mutants-GREEN.log`，即为**最终绿轮**）。

---

## 六 我未能核实的（诚实清单）

1. **真浏览器 e2e 未跑**：`#decision-start` 的"可点/不可点"只在 **node 纯函数 + 渲染夹具**层证过（`decision-mode.test.cjs` 5 条）；**没有**在真 Chromium 里点过按钮、也没有像素/可达性证据。与 T7/T8/T9 的同一系统性开口项。
2. **真档未验**：全部端到端用例跑在**合成夹具**（3 格沙漠走廊 + 1 个决策人 `dm-t10`）；**未**在真 sd 档上验过 `sd.StartDecision`。
3. **`due` 与"开始决策"的耦合只在 UI 层**：服务端**不校验** `due`（命令不因"非待决"被拒）。这是**有意**（due 是"建议"信号，不是领域前置），但"服务端该不该拒非待决的发起"**无上游依据**——记为**未定策略**，未写断言。
4. **重复发起未约束**：同一 `(dm, tick)` 可被发起多次（每次追加一条 INFO）。spec 未要求幂等；**未加**守卫（加了就是发明需求）。
5. **`sd.StartDecision` 的 GUI 路径与 `?as=` / redaction 无交互**：本命令是写，redaction 只覆盖读面；**未**验证任何红action 相关行为（不适用）。
6. **`AgentLib` 是外部依赖**：审批链的字段/语义读的是本机另一份源码与 `~/.m2` 的 `0.1.0-SNAPSHOT`；**两者是否一致**未核（research §E #1 同）。
7. **`value` 用标量串** 的取舍：只在**标量**层面满足往返；若将来要存结构化"发起"信息，`SdInfoEntry.value` 的 `equals` 往返不成立（M4 裁定 38 的老边界），**未**在本任务解决。
8. **变异的"用户点也走审批"形态未构造**（见 §四.2）。

---

## 七 带裁定的遗留（交下游）

- **L1**：`StartDecisionHandler` 的 INFO 落点是**感知层**记录；若将来需要"发起"作为**可计算的领域事实**（参与 `due` 或配额），应迁到 `SdState` 的显式组件（并连带 `SdChangeSet`/`SdCodec`/往返守卫）——**本任务有意不扩状态组件**（最小落地）。
- **L2**：GUI 窄端点与 `/api/command` 并存——后者**本就能**提交 `sd.StartDecision`（任意已注册 type）。二者的差别只是"命令类型写死 + 前端不传 type"；若日后要收窄 GUI 通用写面，需另裁。
- **L3**：`modes.js` 的 `sd.StartDecision` 白名单项**不在运行期被该按钮读取**（按钮走窄端点，不 consul `isWriteAllowed`）；它是 spec §四.2 的声明式要求，且被 `workbench-write-calls-are-all-whitelisted` 的**反向**不变式覆盖（有 `writeCommand("<type>")` 字面量才要求白名单）。
- **L4**：`decision-mode.test.cjs` 的渲染夹具 `app` 替身**无** `refreshState`；`decideStartDecision` 的写后刷新在该层**未被覆盖**（真 `app.js` 有）。`t10js-m2` 只证"闸门守卫"一条。
