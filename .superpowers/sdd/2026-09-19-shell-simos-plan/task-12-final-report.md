# M5 关账报告 —— Simos 外壳（GUI / MCP / AgentBinding）

> **结论**：**M5 12/12 完成**。判据 ①② 逐条实测闭合；R1~R9 点验（**R2 按"M5 无内部工具"存档**，如实记偏离）；
> 主树全量门禁绿。代码终态 = 合并 `559c6f2`（`feat/adr1-core-scope`）。
> 关账提交（本报告 + 台账 + `CLAUDE.md`）见 `git log -1`。

---

## 一 判据逐条实测值（**不是"通过"，是值**）

### 判据① Agent 与玩家改同一状态**走同一 Command 路径**

载体 `ShellEndToEndTest`（T11，`m5/t11` `e130d67` → 合并 `559c6f2`）。夹具：独立 store 种创世 + map/unit/social 三切片（state 时间戳 `of(7)`），三端口全 0，`mcpInitiator = agent:t11-e2e`。

| 写路径 | 行 | commandType | initiator | commandId = correlationId | 事件链（类型序列） |
|---|---|---|---|---|---|
| GUI `POST /api/command` | `(main,2)` | `unit.RenameUnit` | **`player:gui`** | `4718db87-c2ff-4b73-8288-65dbecb24d15` | `received → committed`（2 条） |
| MCP `simos.command.submit`（`APPROVE_ONCE` 后放行） | `(main,3)` | `unit.RenameUnit` | **`agent:t11-e2e`** | `360314be-37ae-4eb2-a2c1-7adfee17ee27` | `received → committed`（2 条） |
| MCP `simos.advance`（审批后放行） | `(main,4)` | `core.AdvanceTime` | `agent:t11-e2e` | `9b6c2028-3f79-4f1b-bb65-2fe85decbf35` | `received → started → module.proposal → finished → committed`（5 条） |

- 三行由**同一棵独立 store + `Timeline`** 读出 ⇒ 同一张 `revisions` 表；`EventStore.byCorrelation` 按 correlationId 取链完整。两条 `initiator` **逐字不同**且 correlationId 互异 ⇒ "同一条 Core 写路径"可判。
- 世界逐值：`replay(main,2).unit["u-1"].name == 玩家改的名`；`replay(main,3) == Agent改的名`；`member/equipment/speed/position` 不因改名而变。
- 推进链之所以是 **5 条**：R6 的冻结序列是 `received → started → **N×proposal** → finished → committed`，`N = 已注册 time participant 数`；本壳只注册 1 个（`UnitTimeParticipant`）⇒ 5 条。**这是正确的 5 条，不是缺一条。**

### 判据② MCP 能达到任何**合法**状态

载体 `McpCoverageTest`（T11）。"合法" = **已注册命令面**可达（spec §〇.1）。经 `simos.command.catalog` 读回 **8** 个类型，逐类经 `simos.command.submit` 提交（每条过 `APPROVE_ONCE`）：

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
- **反向**：`unit.RenameUnit` 缺 `name` 的载荷经 MCP ⇒ `[mosire:code=REJECTED]`（reason 非空），`revisions` 行数**前后相等**、`main` head 不变。

---

## 二 门禁（主树，合并后）

| 阶段 | 命令 | 结果 |
|---|---|---|
| **合并 T7 后** | `./mvnw clean verify` | rc=0，**822** = 170/255/45/131/153/68，7/7 模块，`BugInstance size is 0` ×6，`[ERROR]` 0 |
| **合并 T11 后（终态）** | `./mvnw clean verify` | rc=0，**825** = 170/255/**45**/131/153/**71**，7/7 模块，`BugInstance size is 0` ×6，`[ERROR]` 0 |

delta 逐模块对差：T7 只动 core(+3)/app(+4)；T11 只动 app 68→71（+3 = 3 个新用例类各 1 条）；其余四模块 170/255/45/131 一个没动。
日志：`t12-evidence/merged-t7-verify.log`、`t12-evidence/merged-full-verify.log`。

---

## 三 R1~R9 点验（规格 §十一）

| # | 护栏 | 载体 / 变异 | 状态 |
|---|---|---|---|
| R1 | 同一 Command 路径：app 源码零 store/timeline 写面 | T8 `GuiServer` 扫描用例（先 `stripComments` 再判串，stripper 有专项自证）+ **m1**（往 app 加真写路径直引 ⇒ 扫描断言红，证明 stripper 没抹掉一切） | ✅ |
| R2 | 暴露白名单 `noExport` 不出现在 `tools/list` | **无内部工具可暴露**（12 条 `noExport()` 全 `false`）；`McpServerTest` 用 `tools/list` **exact-set == 12** 作其 M5 形态 | ⚠️ **存档**（见遗留 1） |
| R3 | 审批闸承重：未决/拒绝的写调用 ⇒ `APPROVAL_DENIED` 且**无 revision** | T6 **m1**（写工具 `Ask→Allow` ⇒ 红在"写工具必须先进审批"）+ T7 **m1**（authorizer 换 `standard()` ⇒ 绕过审批，红） | ✅ ×2 |
| R4 | 身份注入：MCP `agent:…`、GUI `player:gui`，correlationId 可追链 | **四处**：T5 m2 / T8 m2 / T7 m2（`GUEST` 桶 ⇒ 硬拒先于审批，红）/ T11 **m2**（`player:gui→player:local` ⇒ 红在 `ShellEndToEndTest:206`） | ✅ ×4 |
| R5 | 命令覆盖：catalog 与工具可达性一致 | T5 **m1**（catalog 少列一 type ⇒ 红）+ T11 **m3**（`DisbandUnit` 返 `UNSUPPORTED` ⇒ 红在 `McpCoverageTest:185`） | ✅ ×2 |
| R6 | 查询参数原样转交（形态 4） | T3 **m1**（转发时改时间戳 ⇒ 红在转发断言） | ✅ |
| R7 | Facet 装配完整性 | T3 **m2**（注册表少注册一面 ⇒ 红在 `facetNames` 完整性） | ✅（落地修正：util `FacetRegistry` 无按名查询 API ⇒ 未注册名的"明确失败"落在 namespace 直证） |
| R8 | AgentBinding 策略门：无 policy / policy 拒绝 ⇒ 拒；canonical 化后再问 | T10 **m1**（删整段策略咨询 ⇒ **4 条同时红**，含"拿 canonical 地址问策略、不是 Human 形"） | ✅ |
| R9 | 生命周期：`close()` 后三端口全释放、无停驻非守护线程 | T11 **m1**（`Shell.close()` 去掉 `mcpServer.close()` ⇒ 红在 `ShellLifecycleTest:50 → assertEventuallyRebindable:75`"MCP 端口必须可重新绑定"） | ✅ |

**变异轮统计**：T1(2) T2(1) T3(2) T4(2) T5(2) T6(1) T7(2) T8(2) T9(1) T9b(2) T10(1) T11(3) = **21 轮变异，0 存活**；每轮九道门禁（字节不同 / 干净世界 / 白名单推成目标类名 / 清陈旧 `.class` / `COMPILATION ERROR`=0 且 `Tests run≥1` / surefire mtime 落轮内 / 红落被保护断言 / `cp` 逐字节还原 / md5 自指进日志）。

---

## 四 遗留条目（**带裁定**，不静默）

1. **R2 存档**（裁定 64）：M5 无内部工具 ⇒ `noExport` 机制在本里程碑**无载体**，规格 §十一"每条都要变异自证"对 R2 **不成立**；如实记为**偏离**，机制本身判别力归 AgentLib 自己的用例。
2. **`ShellMain` 打印 `config.approvalPort()`**（裁定 63 域）：`port=0`（测试态）时与实际绑定端口不符；缺省 5713 时一致 ⇒ **无害**，不在派单文件集内，未改。
3. **`BindingRegistry` 纯内存不持久化**（T10）：规格 §6 只要求"记录 + 可绑性 + 查询"；"绑定是否跨进程存活"**未裁决**，M5 不做。
4. **`unit.PlanRoute` 无法表达"稀疏 waypoints + 更细 path"**（T4，真设计缺口）：载荷只给 `waypoints`，采唯一可重建口径 `Route(waypoints, waypoints)` ⇒ 要求点列**逐格相邻**；要稀疏须先补载荷（`path` 或自动 A*），归 GUI 路线表单的后续。
5. **审批超时/会话键未验**（T6）：等待上限 5 分钟（规格未定值，到点 fail-closed 归 AgentLib）——"超时真到点"与"`APPROVE_SESSION` 真落会话键"**未验**。
6. **`ForkBranch` 不发事件**（M4 已知缺口）：判据①只断言了 `advance` 的链，**分岔行的链未断言**。
7. **R9 未在"审批长连在途"时关壳**：`ShellLifecycleTest` 未连 MCP 客户端 ⇒ 规格 §十一 R9 末句"审批长连先收"分支**未覆盖**（审批 HTTP 长连由 AgentLib `HttpApprovalChannel` 持有，属其契约）。
8. **判据②反向用例的绝对行数未打印**：只断言"被拒前后行数相等"；绝对值由 `main@10` + `mcp-branch@1` **推导**为 11，是推导不是当场打印。
9. **规格 §十三.4（每查询重放代价）**：小规模可接受，**代价未测**，缓存不做。
10. **规格 §十三.5（运行形态评测，关账必评）**：无 fat jar；靠 `-cp` 六模块 `target/classes` + `mvn dependency:build-classpath` 生成的清单（本机实测 2296 字节）。**demo 实例已在 5817 实跑 ⇒ "够用"**（`--demo` 空库首启 + 三页可点 + 地图单位标记）；但**不便**（换机需重生成 classpath）⇒ 若要发布须 shade，**开口项**（与规格 §〇.4"不做发布流水线"一致）。
11. **规格 §十三 1~3 已消**：① MCP 官方 SDK 客户端 ↔ `startHttp` 连通性 —— T7 实测通过；② Canvas 移植规模 —— T9 估定（`map.js` 312 行 + 共享 `api.js/app.js/styles.css`）；③ unit 载荷细节 —— T4 逐字对齐并记下第 4 条的 `PlanRoute` 缺口。

---

## 五 我未能核实的

- **GUI 视觉保真**：headless 环境**无 CJK 字体**，截图里中文是方框——**是环境而非缺陷**；换有字体的机器需重截。
- **跨机 / 跨网络 MCP 未测**（本机回环 + 官方 SDK）。
- **MCP 官方客户端首连 405/降级行为**未细察（T7 报告 §）。
- **`noExport` 机制**（R2）本身未自证（见遗留 1）。
- **`AgentAttachPolicy` 接口**无独立测试类（T10 明记，派单允许）：两条真策略由 `BindingRegistryTest` 覆盖。
- **`BindingRegistry` 的 `ResolveContext` 生产来源**未验（未接线，见遗留 3）。
- **`Shell` 里 `BindingRegistry` 未装配**（T10 明令不接线）：M5 只交付模型层 + 策略门，**"生产上注册哪几条策略、bind 从哪取 ctx"留待 M6**。

---

## 六 证据索引

- `t12-evidence/merged-full-verify.log` — 主树终态全量绿（825，`BugInstance` 0 ×6，`[ERROR]` 0）
- `t12-evidence/merged-t7-verify.log` — 合并 T7 后全量绿（822）
- `t11-evidence/` — 判据①/② 载体（3 用例类 + 3 轮变异日志 + `mutants/` + `manifest.md5`）
- 各任务证据：`t1..t10-evidence/`、`t9b-evidence/`（含 7 张截图 + `map-unit-selected.png`）
- 逐任务裁定与跨任务约束：`progress.md`
- 各任务报告：`t1..t11-report.md`
