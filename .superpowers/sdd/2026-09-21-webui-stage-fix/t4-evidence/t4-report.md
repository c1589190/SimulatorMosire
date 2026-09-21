# T4 报告 —— MCP 端口拓扑（现有口扩 GM ∪ EXTERNAL + 决策人另开一口）

> 任务：`docs/superpowers/plans/2026-09-21-webui-stage-fix-plan.md` §T4。
> 设计：`docs/superpowers/specs/2026-09-21-webui-stage-fix-design.md` §二 / §六.4 / §七.2。
> 基线 = 当前 HEAD `ffabc83`（T1/T2/T3 已合并）；worktree `.claude/worktrees/wsf-t4`，分支 `wsf/t4`。
> 实现提交 **`061dcbe`**（短 SHA）。

---

## 一 做了什么（逐条对表）

| 判据 | 期望 | 实测 | 证据 |
|---|---|---|---|
| **C6** | 现有口 `tools/list` **同时含** 3 通用写（`simos.command.submit`/`advance`/`fork`）与 3 GM 窄工具（`sd.IssueDirective`/`SubmitVerdict`/`SetViewScope`） | **15 条**（9 读 + 3 通用写 + 3 GM 窄写） | `tool-face-manifest.txt`（运行时逐条）；`McpPortTopologyTest.existingPortExposesExternalUnionGmToolFace`；`McpServerTest.initializeAndToolsListExposeExactlyTheExternalUnionGmTools` |
| **C7** | 决策人口含 2 窄工具、**不含**通用写、**不含** `SetViewScope` | **11 条**（9 读 + `sd.IssueDirective` + `sd.SubmitVerdict`）；无 `simos.command.submit`/`advance`/`fork`/`sd.SetViewScope` | 同上；`McpPortTopologyTest.decisionPortExposesOnlyDecisionAgentToolFace` |
| **C8** | 两 server 同时监听、`boundPort` 不同；`Shell.close()` 后两者都不可连 | 运行时两口端口 `45717` / `46471`（不同）；两个 SDK 客户端各自 `initialize()` 成功；`close()` 后两口均可再绑 | `McpPortTopologyTest.bothServersListenAndCloseReleasesBothPorts`；`ShellLifecycleTest.closeReleasesAllFourPortsAndLeavesNoLingeringNonDaemonThreads` |
| **C9** | 端口是唯一边界：新口调通用写 ⇒ **工具不存在**（不是"存在但被拒"） | 决策人口 `simos.command.submit` ⇒ SDK `McpError`「Unknown tool: invalid_tool_name」，`revisions` 行数不变 | `McpPortTopologyTest.decisionPortHasNoGenericWriteSoSubmitIsUnknownTool` |

### 两端口运行时工具表（实测清单，`tool-face-manifest.txt`）

```
existingPort=45717  serverInfo=simos-shell           count=15
  sd.IssueDirective  sd.SetViewScope  sd.SubmitVerdict
  simos.advance  simos.command.catalog  simos.command.submit  simos.fork
  simos.map.hex  simos.map.overview  simos.social.population
  simos.state.facets  simos.state.resolve  simos.timeline.branches
  simos.unit.get  simos.unit.list

decisionPort=46471  serverInfo=simos-shell-decision  count=11
  sd.IssueDirective  sd.SubmitVerdict
  simos.command.catalog  simos.map.hex  simos.map.overview  simos.social.population
  simos.state.facets  simos.state.resolve  simos.timeline.branches
  simos.unit.get  simos.unit.list
```

> ★ 该清单由**临时证据探针**（`T4ToolFaceProbe`，跑完即删、未入库）在真 socket 上打出：两个 `McpSyncClient`
> 分别连两口、`initialize → tools/list`。它与 `McpPortTopologyTest` 的 `containsExactlyInAnyOrderElementsOf`
> 期望集合**逐条一致**。

---

## 二 实现落点

- **`SimosToolSource`**：新增复合桶 `Role.EXTERNAL_WITH_GM`（读共享；写面 = 外部通用写 ∪ GM 窄写）。写面装配抽成
  `addExternalWrites` / `addGmWrites` / `addDecisionAgentWrites` 三个助手，四个桶的写面各自可读。
- **`ShellConfig`**：加第 10 个组件 `decisionAgentMcpPort`（缺省 `5717` = `DEFAULT_DECISION_AGENT_MCP_PORT`）；
  `withPorts` 由三参改**四参**（测试取随机端口时把决策人口一并置 0）；负值校验纳入。
- **`ShellMain`**：加 `--decision-agent-mcp-port N`（照既有开关风格），用法串与启动日志同步。
- **`Shell`**：建第二个 `SimosToolSource`（`DECISION_AGENT`）+ 第二个 `ToolRegistry` + 第二个 `McpSourceBridge`
  + 第二次 `AgentToMcpServer.startHttp`；决策人口自报名 `simos-shell-decision`（spec §六.4 建议两口区分）。
  `close()` 次序 = **GUI → MCP1 → MCP2 → 工具桥×2 → 审批端点 → 审批通道 → Core**。新增 `boundDecisionAgentMcpPort()`。
  任一口绑定失败的清理路径（MCP 失败 / GUI 失败）都补上了第二个 server 与两个桥的关闭。

### ★ D2 ↔ N9 的显式记账（不许悄悄覆盖）

- **SDSimos 裁定 N9** 原文口径：「专用窄工具，不给决策 Agent 通用 `simos.command.submit`」。
- **D2（用户原话「加」）** 把现有 MCP 口定为 `EXTERNAL ∪ GM` 并**保留通用写** ⇒ **两者在该端口上冲突**。
- **口径**：**D2 以用户裁定为准；N9 就现有 MCP 端口不适用**。代码里已在 `Shell.java`（工具集装配处）与
  `SimosToolSource.Role.EXTERNAL_WITH_GM` 的枚举 Javadoc 两处写明；本报告一并记账。
- **后果（写明）**：现有口（5715）的持有者可直接提交任意命令、绕过 GM 窄工具（`submit`/`advance`/`fork` 全在）。
  **这不是缺陷、是用户裁定的取舍**。
- **N9 仍然有效之处**：**决策人口（5717）**与 **`DecisionMaker.allowedTools` 白名单**（`CreateDecisionMakerHandler`
  拒通用写）**不受影响**——已由 C7 与既有 `SimosToolsTest.roleBucketsNeverCarryGenericWrite` 钉住。

### ★ 权限边界 = 端口级，不是认证级

全仓无多用户认证（`AccessToken` 三值枚举、无 token 表）。两口都用同一 `mcpCaller()`（`DEFAULT` 桶 + `external`
身份），审批 `callerKey` 也同为 `DEFAULT` ⇒ **谁连得上端口就有该口工具面**。`Shell.java` 装配处已留注释
（"权限边界在**端口**、不在身份，spec §二.3"）。**不得**把本阶段读成两个身份安全隔离。

---

## 三 门禁（★ 现场重算，不引用文档现成数字）

**全量 `./mvnw clean verify`：第 1 次尝试 FAILURE（SpotBugs 2 条 `NP_NULL_ON_SOME_PATH` 假阳性，见 §五）→
修后第 2 次尝试 SUCCESS（rc=0）；变异轮后复跑 SUCCESS（rc=0）。**

- 日志：`logs/clean-verify.log`、`logs/clean-verify-after-mutants.log`（`log_md5=6297695badb57e960fe4c3f74191ef54`）、
  `logs/clean-verify-rc.txt`、`logs/clean-verify-after-mutants-rc.txt`、`logs/recomputed.txt`。
- **模块 8/8 `SUCCESS [`**（显示名）：

```
[INFO] SimulatorMosire .................................... SUCCESS
[INFO] UtilSimos .......................................... SUCCESS
[INFO] MapSimos ........................................... SUCCESS
[INFO] SocialSimos ........................................ SUCCESS
[INFO] UnitSimos .......................................... SUCCESS
[INFO] CoreSimos .......................................... SUCCESS
[INFO] SDSimos ............................................ SUCCESS
[INFO] SimosApp ........................................... SUCCESS
```

- **用例总数 1295** = `UtilSimos 170 / MapSimos 368 / SocialSimos 45 / UnitSimos 259 / CoreSimos 178 / SDSimos 124 / SimosApp 151`。
- **增量（对基线 1288 = 170/368/45/259/178/124/144）**：**只有 SimosApp 144→151 = +7**，其余六模块**逐值不变**。
  +7 = `McpPortTopologyTest` **4** + `ShellMainParseTest` **3**（3→6）。
- `BugInstance size is 0` **×7**；`[ERROR]` **0 行**。
- 前端 `[frontend-gate] OK tests=114 pass=114 fail=0`（**未改 JS ⇒ 下界与 `REQUIRED_FILES` 均未动**）。
- 基线现场重算见 `logs/recomputed.txt`（只取模块汇总行）。

### 定向用例

`logs/targeted-1.log`（首轮，红：`SimosToolsTest.registryContainsExactlyTheTwelveTools` 未随现有口扩到 15）、
`logs/targeted-2.log`（修后全绿：`McpPortTopologyTest` 4 / `McpServerTest` 4 / `McpCoverageTest` 1 /
`SimosToolsTest` 15 / `ShellLifecycleTest` 1 / `ShellMainParseTest` 6 / `BindAddressTest` 3）。

---

## 四 变异（九道门禁，11 轮 11 KILLED / 0 存活）

装置：`mutants/java-round.sh`（照 T3 同法：干净世界 md5 / 变异体字节不同 / 推成规范类名 / 清陈旧 `.class` 与
surefire / `COMPILATION ERROR`=0 且 `Tests run`≥1 / 报告 mtime 落本轮 / 红点落被保护断言 / `cp` 逐字节还原
（**绝不 `git checkout --`**）/ 日志自指且核对处先断言非空）。日志在 `mutants/logs/`。

| 轮次 | 变异 | 靶文件 | 红点（被保护断言） | 判定 |
|---|---|---|---|---|
| **t4m1** | 现有口退回 `EXTERNAL`（丢 GM 窄工具） | `Shell.java` | `McpPortTopologyTest.existingPortExposesExternalUnionGmToolFace` + `McpServerTest.initializeAndToolsListExposeExactlyTheExternalUnionGmTools` | KILLED |
| **t4m2** | 决策人口错挂 `EXTERNAL`（含通用写） | `Shell.java` | `decisionPortExposesOnlyDecisionAgentToolFace` + `decisionPortHasNoGenericWriteSoSubmitIsUnknownTool` | KILLED |
| **t4m3** | `close()` 漏关第二个 server | `Shell.java` | `bothServersListenAndCloseReleasesBothPorts` + `ShellLifecycleTest.closeReleasesAllFourPorts…` | KILLED |
| **t4m4** | 决策人口错加 `sd.SetViewScope` | `SimosToolSource.java` | `decisionPortExposesOnlyDecisionAgentToolFace` + `SimosToolsTest.roleBucketsNeverCarryGenericWrite` | KILLED |

### 裁定 42 重跑轮（改动的既有护栏文件 ⇒ 从最终字节重派生）

| 轮次 | 来源 | 变异 | 红点 | 判定 |
|---|---|---|---|---|
| **t4r-t7m1** | M5 T7 m1 | 两口 authorizer 换 `ToolCallAuthorizer.standard()`（写不再进审批） | `McpServerTest.writeToolBlocksOnApproval…` + `McpCoverageTest.everyCatalogTypeIsReachable…` | KILLED |
| **t4r-t7m2** | M5 T7 m2 | `mcpCaller` 桶 `DEFAULT→GUEST`（写被硬拒） | 同上两条 | KILLED |
| **t4r-t11m1** | M5 T11 m1 | `close()` 漏关第一个 server | `closeReleasesAllFourPorts…` + `bothServersListenAndCloseReleasesBothPorts` | KILLED |
| **t4r-t9m1** | unit-ext T9 m1 | 删 `new SetStatusHandler(),` + 其 import | `catalogListsExactlyTheRegisteredCommandTypes` + `catalogCoversEveryCommandHandlerImplementation` + `everyCatalogTypeIsReachable…` | KILLED |
| **t4r-t9m2** | unit-ext T9 m2 | 注册移出 `List.of(...)`（注册了却没进 catalog） | 同上三条 | KILLED |
| **t4r-t9m3** | unit-ext T9 m3 | `PlanSparseRouteHandler` 注入不可通行成本 | `everyCatalogTypeIsReachable…` | KILLED |
| **t4r-m10m2** | M10 m2 | `DEFAULT_BIND_ADDRESS 127.0.0.1→0.0.0.0`（`ShellConfig.java`） | `ShellMainParseTest.bindAddressDefaultsToLoopback` + `BindAddressTest.guiBindsLoopbackByDefault` | KILLED |

> ★ 重派生说明：`t4r-t7m1` 的 `toolAuthorizer);` 锚点在新字节上出现 **2 次**（两口各一），故**两处同改**
> （语义仍是"authorizer 不是带审批的那个"）；`t4m3`/`t4r-t11m1` 的 `close()` 锚点改用**多行上下文**定位
> （裸 `    mcpServer.close();` 会同时命中 GUI-finally 里 8 空格缩进的副本）。旧 `t9m*_Shell.java` 快照含 T3 前
> 的 import，**不得整套套用** ⇒ 用 `make-mutants-t9.py` 从当前 `Shell.java` 重新派生。
> 变异轮跑完 `git diff HEAD -- simos-app/src` **为空**（`cp` 逐字节还原），随后复跑全量门禁绿。

---

## 五 一处就地修正（诚实披露）

首轮全量门禁 **rc=1**：SpotBugs 报 `Shell.java` 两条 `NP_NULL_ON_SOME_PATH`（`mcpServer` / `decisionMcpServer`
在 GUI-finally 与装配日志处的解引用）。根因是新增的"两个 server + `finally` 里按 null 清理"结构让 SpotBugs 的
null 分析合并出**不可达路径**（`!mcpUp` 分支后仍能落到后续代码）——是**假阳性**。修法：在 MCP 块之后加
`Objects.requireNonNull(mcpServer/decisionMcpServer, …)` 显式钉死后置条件（保留 `finally` 的"任何 `Throwable`
都清理"语义）。修后 `BugInstance size is 0 ×7`。

---

## 六 ★ 我未能核实的（含"两 server 并发"的直证情况）

1. **两 server 并存：已有直证（不再是推断）**。`McpPortTopologyTest.bothServersListenAndCloseReleasesBothPorts`
   在**两个 server 同时监听**时，用两个 SDK 客户端各自 `initialize()` 成功并各自读到工具面 ⇒ "同一时刻两个
   `AgentToMcpServer` 实例并存"**有直接用例**（任务书说"没有直接用例、属推断"——本任务补上了）。
   **但仍未测**：两个客户端**同一瞬间**发请求的并发处理（用例是先后 `initialize`）、高并发/长连会话、
   两个 server 共用同一 `mcpPath` 之外的路径形态。
2. **`--decision-agent-mcp-port` 的端到端 CLI 未跑**：`ShellMainParseTest` 覆盖**解析**，`Shell.start` 覆盖**装配**；
   但 `ShellMain.run()`（阻塞到 SIGINT）**未被测试调用**，故"命令行开关 → 运行时监听"这一整链只有解析级 + 装配级两段。
3. **审批层未按口区分**：两口 `callerKey` 同为 `DEFAULT`（A″/D6 的已知限制）——本任务**未**给决策人口换身份桶，
   故审批 `PendingApprovals` 里两条来源不可区分。这是 spec §二.4 记为**已知限制**的那条，**不是本任务遗漏**。
4. **绑定地址跨接口**：`BindAddressTest` 的 `0.0.0.0` 分支断言了决策人口可达（本机有非回环 IPv4，用例
   `Skipped: 0` ⇒ 真跑到），但**生产机 / 跨平台**未测。
5. **`simos-app` 之外**：无改动 ⇒ 未做额外核。
6. **未验 `port=0` 下两口随机端口是否可能相撞**：理论上 OS 不会给两个同地址的监听者同一端口；用例断言了
   `isNotEqualTo`，本机实测不同（45717/46471），但**未做多轮统计**。
7. **`McpCoverageTest` 只跑现有口**：D2="加"意味着通用写仍在，该用例**原样通过**（未放松、未改一行逻辑，仅
   `withPorts` 四参适配）；但**决策人口未做逐类 catalog 覆盖**（C7 只证工具面，不证逐类提交）。
8. **基线未在"改动前"单独重跑**：`logs/baseline-clean-verify.log` 是在本树改动前跑的（1288），但工作树随后被
   改；该基线是"改动前"证据，非"同一字节两次"。

---

## 七 证据清单

```
t4-evidence/
  logs/baseline-clean-verify.log          # 改动前基线（1288）
  logs/baseline-rc.txt
  logs/clean-verify.log                   # 实现后全量门禁（第 2 次尝试）
  logs/clean-verify-rc.txt
  logs/clean-verify-after-mutants.log     # 变异轮后复跑（log_md5=6297695b…）
  logs/clean-verify-after-mutants-rc.txt
  logs/spotbugs-check.log                 # SpotBugs 修复验证（BugInstance 0 ×7）
  logs/targeted-1.log                     # 首轮定向（红：registry 未扩到 15）
  logs/targeted-2.log                     # 修后定向全绿
  logs/tool-face-probe.log                # 运行时两口工具表（探针）
  logs/recomputed.txt                     # 门禁现场重算
  logs/module-summary-lines.txt
  tool-face-manifest.txt                  # ★ 两端口逐条工具表
  mutants/java-round.sh                   # 九道门禁装置
  mutants/make-mutant.py                  # T4 + 裁定42 重放变异体
  mutants/make-mutants-t9.py              # unit-ext T9 变异体（从当前字节重派生）
  mutants/orig/{Shell,SimosToolSource,ShellConfig}.java
  mutants/logs/*.log                      # 11 轮逐轮日志（含自指 md5）
  t4-report.md                            # 本文件
```
