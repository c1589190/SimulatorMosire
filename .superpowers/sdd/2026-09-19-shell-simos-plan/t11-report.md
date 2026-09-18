# M5 T11 报告：判据 ①② 端到端 + R9 生命周期

> **结论**：判据 ①② 端到端闭合、R9 有变异自证；门禁全绿。**test-only**，未动任何 main 源码。
> **分支**：`m5/t11`，基线 `2748bb6`（预检通过，status clean）。
> **交付**：`ShellEndToEndTest`（判据①）/ `McpCoverageTest`（判据②）/ `ShellLifecycleTest`（R9）。

---

## 一 判据①：Agent 与玩家走同一 Command 路径（`ShellEndToEndTest`）

夹具：独立 store 种创世 `(main,1)` + map/unit/social 三切片（state 时间戳 `of(7)`）；三端口全 0；`mcpInitiator = agent:t11-e2e`。

| 写路径 | 行 | commandType | initiator | commandId = correlationId | 事件链（类型序列） |
|---|---|---|---|---|---|
| GUI `POST /api/command` | `(main,2)` | `unit.RenameUnit` | **`player:gui`** | `4718db87-c2ff-4b73-8288-65dbecb24d15` | `received → committed`（2 条） |
| MCP `simos.command.submit`（审批 `APPROVE_ONCE` 后放行） | `(main,3)` | `unit.RenameUnit` | **`agent:t11-e2e`** | `360314be-37ae-4eb2-a2c1-7adfee17ee27` | `received → committed`（2 条） |
| MCP `simos.advance`（审批后放行） | `(main,4)` | `core.AdvanceTime` | `agent:t11-e2e` | `9b6c2028-3f79-4f1b-bb65-2fe85decbf35` | `received → started → module.proposal → finished → committed`（5 条） |

- 三行都经**同一棵独立 store + `Timeline`** 读出（同一张 `revisions` 表），`EventStore.byCorrelation` 按 correlationId 取链——
  "走了同一条 Core 写路径"因此可判：两条 initiator **逐字不同**，且各自链完整。
- 世界逐值：`replay(main,2).unit["u-1"].name == 玩家改的名`；`replay(main,3) == Agent改的名`；其余字段（member 100 / equipment / speed / position）不因改名而变。
- correlationId 也是工具返回值（MCP 侧对账口径）。

## 二 判据②：MCP 可达 catalog 全类型（`McpCoverageTest`）

`simos.command.catalog` 经 MCP 读回 **8 个**已注册类型；逐类经 `simos.command.submit` 提交（每条都过 `APPROVE_ONCE`），head 逐条前进：

| # | type | 结局 | 落点 |
|---|---|---|---|
| 1 | `unit.CreateUnit` | committed | `main@2` |
| 2 | `unit.RenameUnit` | committed | `main@3` |
| 3 | `unit.SetStrength` | committed | `main@4` |
| 4 | `unit.ReparentUnit` | committed | `main@5` |
| 5 | `unit.PlaceAt` | committed | `main@6` |
| 6 | `unit.PlanRoute` | committed | `main@7` |
| 7 | `unit.CancelRoute` | committed | `main@8` |
| 8 | `unit.DisbandUnit` | committed | `main@9` |
| + | `simos.advance` | committed | `main@10` |
| + | `simos.fork` | committed | `mcp-branch@1` |

- 世界确实变了：`main@9` 重放后 `u-1` 已解散、只剩 `CreateUnit` 建的 `u-2`（名字/人数逐值）。
- `fork` 后 `branches()` 含 `main` 与 `mcp-branch`，后者 head = 1。
- **反向**：`unit.RenameUnit` 缺 `name` 的载荷经 MCP ⇒ `[mosire:code=REJECTED]`（`result=rejected`，reason 非空），
  `revisions` 行数**前后相等**、`main` head 不变。

## 三 R9：`close()` 释放三端口 + 无停驻非守护线程（`ShellLifecycleTest`）

- `close()` 后 GUI/MCP/审批三个端口各自可被 `ServerSocket` 重新绑定（`SO_REUSEADDR` 不允许两个监听者同占一地址）。
- `close()` 前后**非守护线程集合的差**为空（以 `threadId:name` 标识，只追究本壳新建且停驻的线程）。
- 判别力由 m1 证明（见下）。

## 四 变异自证（3 轮，全部九道门禁）

| m | 护栏 | 变异（main 源码，临时） | 期望红 | 实测红点 | 结论 |
|---|---|---|---|---|---|
| m1 | R9 | `Shell.close()` 去掉 `mcpServer.close();` | 端口释放断言 | `ShellLifecycleTest:50 → assertEventuallyRebindable:75`：「MCP 端口 … 必须可重新绑定」 | 杀死 |
| m2 | 判据① / R4 | `GuiServer.GUI_INITIATOR "player:gui"→"player:local"` | initiator 逐字断言 | `ShellEndToEndTest:206`：「玩家写经 GUI 端点 ⇒ player:gui」 | 杀死 |
| m3 | 判据② / R5 | `CommandSubmitTool` 对 `unit.DisbandUnit` 返回 `UNSUPPORTED` | 逐类覆盖断言 | `McpCoverageTest:185`：「type=unit.DisbandUnit 必须经 MCP 可提交并生效」 | 杀死 |

每轮九道门禁（原文见各 `logs/mN.log` 的"装置补记"段 + `manifest.md5`）：
① 变异体字节不同于原件（md5 打印并追加进日志本身）；② 干净世界（`orig_md5 == worktree_before`）；
③ 以目标类名路径推入、并清掉陈旧 `.class`；④ `COMPILATION ERROR` = 0；⑤ `Tests run ≥ 1`；
⑥ surefire 报告 mtime ≥ 本轮 round_start；⑦ 红点落在被保护的那行（上表）；⑧ `cp` 逐字节还原并核 md5；
⑨ md5 三处自指进日志。三轮 `rc=1`、还原后 `worktree_restored == orig_md5`。

## 五 门禁数字

| 阶段 | 命令 | 结果 |
|---|---|---|
| 定向 | `-pl simos-app -am -Dtest=ShellEndToEndTest,McpCoverageTest,ShellLifecycleTest test` | rc=0，**3** 条（每类 1 条） |
| 全量 | `./mvnw clean verify` | rc=0，**825** = 170/255/45/131/153/**71**，7 reactor 条目，`BugInstance size is 0` ×6，`[ERROR]` 0 |

delta：app 68 → **71**（+3 = 3 个新用例类各 1 条）；其余五模块与基线 822 逐模块相同（170/255/45/131/153）。

## 六 取代说明候选 / 偏离

1. **推进支"冻结 6 事件"是笔误口径**：spec §1.1/R6 的序列是 `received → started → N×proposal → finished → committed`，
   **N = 已注册 time participant 数**。本壳只注册 1 个（`UnitTimeParticipant`）⇒ 实测冻结链是 **5 条**，用例按 5 条断言。
   **不是代码缺陷**，是派单文字把 N=2 的例子当成了常量；无需改任何源码。
2. **判据②执行序 = 语义合法序，不是 catalog 字典序**：catalog 按字典序把 `CancelRoute` 排在 `DisbandUnit` 前，
   而 `DisbandUnit` 会移除 `u-1`，后续命令即"查无此人"。用例改为先建 `u-2`、最后解散 `u-1`，并另用
   `MINIMAL_PAYLOADS.keySet() == catalogTypes` 钉住"每个 catalog 类型都有载荷"。
3. **R9 单独成类**（`ShellLifecycleTest`）——派单允许"either ... or its own small test"。
4. **无 main 源码改动**：三个判据/护栏都由既有 main 面满足，未发现需要改装配的地方。

## 七 我未能核实的

- **反向用例的绝对行数未打印**：只断言了"被拒前后 `revisions` 行数相等"；绝对值可由 `main` head 10 + `mcp-branch` head 1
  推出为 11，但那是**推导**，不是当场打印的值。
- **R9 的线程名未枚举**：线程差断言通过（本机/JDK 21 实测），但没有列出 AgentLib 服务线程的具体名字；
  真正被变异证明有判别力的是**端口释放**那一半。
- **R9 未在"审批长连在途"时关壳**：`ShellLifecycleTest` 没有连 MCP 客户端，故 spec §11 R9 末句"审批长连先收"
  的分支**未覆盖**（审批 HTTP 长连由 AgentLib `HttpApprovalChannel` 持有，属其契约）。
- **`simos.fork` 不发事件**（M4 已知缺口）⇒ 判据①只断言了 `advance` 的事件链，分岔行的链未断言。
- **审批走的是进程内 `PendingApprovals.decide`**（测试直批），未走 5711 `/api/approvals` 代理面（那是 T6 的 `ShellApprovalTest` 覆盖对象）。
- **判据②的"任意合法状态"限于已注册命令面**（8 条 unit + advance/fork）——这是 spec §〇.1 对"合法"的定义，之外的写面不存在。

## 八 证据索引

- `t11-evidence/logs/targeted-first.log` — 定向绿（3/3，含 `[T11-COVERAGE]` 逐类结局）
- `t11-evidence/logs/full-verify.log` — 全量绿（825，`BugInstance` 0 ×6，`[ERROR]` 0）
- `t11-evidence/logs/m1.log`（R9 端口）/ `m2.log`（initiator）/ `m3.log`（覆盖）— 变异轮，含装置自指补记
- `t11-evidence/mutants/mut-round.sh` — 九道门禁装置（ROOT = 本 worktree）
- `t11-evidence/mutants/orig/*.java` + `*-m*.java` — 原件备份与变异体（逐字节不同）
- `t11-evidence/manifest.md5` — 三个用例类 + 装置产物 + 原件的 md5 清单
