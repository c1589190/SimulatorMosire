# M5 T5 报告 —— 工具集（3 写 + 9 读）

> **任务**：M5 T5（`docs/superpowers/plans/2026-09-19-shell-simos-plan.md` §二 T5；spec §7.1）
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/m5t5`，分支 `m5/t5`，基线 `61f5002`（PREFLIGHT 自证通过）
> **日期**：2026-09-19
> **范围**：12 条 `AgentTool` + `SimosToolSource` + `Shell` 的工具注册表绑定；**不含**审批装配（T6）与 MCP 服务（T7）。

---

## 1 交付物

### 新增源码（`simos-app/src/main/java/io/mosire/simos/app/tools/`）

| 文件 | 内容 |
|---|---|
| `ToolSupport.java` | 共享助手：参数解析、JSON Schema 小件、资源断言（`require`）、领域视图（与 `ApiViews`/facet 同口径）、`CommandResult` 三结局折叠 |
| `SimosToolSource.java` | `implements ToolSource`，id `"simos"`；`listTools()` 返回 12 条；`onChange` = no-op（静态集） |
| `read/CatalogTool.java` | `simos.command.catalog`：已注册命令类型 + 载荷字段提示（**R5 载体**） |
| `read/StateResolveTool.java` | `simos.state.resolve` |
| `read/StateFacetsTool.java` | `simos.state.facets` |
| `read/BranchListTool.java` | `simos.timeline.branches`（只读 `core.branches()/head()`） |
| `read/MapOverviewTool.java` | `simos.map.overview` |
| `read/MapHexTool.java` | `simos.map.hex`（canonical 主体经 Address AST 造） |
| `read/UnitListTool.java` | `simos.unit.list` |
| `read/UnitGetTool.java` | `simos.unit.get` |
| `read/PopulationTool.java` | `simos.social.population` |
| `write/CommandSubmitTool.java` | `simos.command.submit` → `CommandEnvelope` → `CoreSimos.submit`（**R4 载体**） |
| `write/AdvanceTool.java` | `simos.advance` → `AdvanceTime` |
| `write/ForkTool.java` | `simos.fork` → `ForkBranch` |

### 修改源码

| 文件 | 改动 |
|---|---|
| `Shell.java` | handler 注册改为 `List<CommandHandler>` 遍历，同源派生出 `Set<String> commandTypes`（catalog 的输入）；建 `SimosToolSource`（注入 `config.mcpInitiator()` / `config.mapId()` / 8 类型）+ `ToolRegistry` + `McpSourceBridge.bind(...)`；新增 `toolRegistry()`；`close()` 增 `toolBridge.close()`；装配日志补 `tool=` 计数；javadoc 装配清单补一行 |

### 测试

| 文件 | 条数 |
|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java` | 11 |

用例：注册表恰 12 条 / catalog 恰 8 个已注册类型（R5）/ 读工具 Allow+资源声明 / 写工具敏感+Ask(classKey=工具名)+资源声明 / 写工具经 `CoreSimos` 提交且 initiator=配置值（R4，独立 store+`Timeline` 读回）/ `advance` 经 Core 提交 / 拒绝与冲突各留 `ToolResult.error` 且无 revision / 读工具与 `QueryService` 逐值对拍 / `map.hex` canonical facet / 未知单位 `NOT_FOUND`。

---

## 2 实测数字

- **定向**（`./mvnw -pl simos-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='SimosToolsTest' test`）：rc=0，`SimosToolsTest` **11 条全绿**（`logs/targeted.log`）。
- **全量**（`./mvnw clean verify`，`logs/full-verify.log`）：**rc=0**、**800** 条 = 170/255/45/131/150/**49**、7/7 模块 `SUCCESS`、`BugInstance size is 0` **×6**、`[ERROR]` **0** 行。
  - 基线 **789** = 170/255/45/131/150/38（7 条目）⇒ delta = **+11**，全在 app（38→49），恰等于 `SimosToolsTest` 的 11 条；其余五个模块**逐模块零变化**。
- **门禁抓到 5 个真项并修掉**（见 §3 偏离 2）：4×`EI_EXPOSE_REP2`（工具构造器存 `CoreSimos`）+ 1×`EI_EXPOSE_REP`（`Shell.toolRegistry()`）。

---

## 3 变异自证（2 轮，九道门禁逐轮在案）

| m | 护栏 | 变异 | 门禁实测（`logs/m<N>.log`）| 红点 |
|---|---|---|---|---|
| m1 | **R5** catalog/注册一致性 | `CatalogTool` 的 `types` 去掉末位一个 | orig_md5 `b2cc…` / mutant_md5 `0211…`；`COMPILATION_ERROR=0`、`Tests run: 11`；surefire mtime > round_start；还原 md5 逐字节相等 | `catalogListsExactlyTheRegisteredCommandTypes:162`（catalog 完备性断言）|
| m2 | **R4** 身份注入 | `CommandSubmitTool` 的 `initiator` 写死 `player:local` | orig_md5 `477f…` / mutant_md5 `52d5…`；`COMPILATION_ERROR=0`、`Tests run: 11`；surefire mtime > round_start；还原逐字节相等 | `writeToolCommitsAndStampsTheConfiguredInitiator:227`（独立 store 读回的 initiator 断言）|

- 装置：`mutants/mut-round.sh`（照 task-17 骨架，`ROOT` 改指本 worktree、模块改 `simos-app`）；变异体按**目标类名**推入（`m1.CatalogTool.java` → `CatalogTool.java`），每轮先删 `simos-app/target/classes/**/<Class>.class` 逼重编。
- 自指：每轮日志尾部追加 `orig_md5 / mutant_md5 / worktree_restored`（装置补记段），核对脚本读的是**本轮**落盘字节。
- **m2 在构造器加 `@SuppressFBWarnings` 修复后重跑**（该文件在首轮 m2 之后被改过 ⇒ 旧证据对象已变，按纪律重跑）；m1 目标文件未变、亦一并重跑，两轮结论一致。

---

## 4 偏离 / 取代说明候选

1. ★ **`SimosToolSource` 构造器多一个 `String mapId`**（派单文字是 4 参 `(core, query, initiator, commandTypes)`）。
   **理由（真缺口非笔误）**：`GameMap` 无 id（M2/M3 挂起项），而 spec §5.2 + T3 裁定 58 要求 `map.hex` 用 Address AST 造
   canonical `map:<mapId>:hex.<q>_<r>` 主体、`map.overview` 要回显 `mapId`。`Shell` 手里有 `config.mapId()`（T1 裁定 57.1 补的
   同一个缺口）。⇒ 实际签名为 `(CoreSimos, QueryService, String initiator, String mapId, Set<String>)`。**同 T1 的 `ShellConfig.mapId` 缺口同型**。
2. **SpotBugs 豁免**：4 个工具构造器 + `Shell.toolRegistry()` 用 `@SuppressFBWarnings` 精确豁免（`EI_EXPOSE_REP2` / `EI_EXPOSE_REP`），
   与 `Shell.coreSimos()`（T1 裁定 57.3）同法。**理由**：`CoreSimos` 是工具的唯一读/写入口（不是内部表示），`ToolRegistry` 是 spec §7.2
   要求交给 T7 的产物本身。★ **如实记**：既有的 `GuiServer` / `QueryService` 也在公共构造器里存 `CoreSimos`，本轮 SpotBugs **未**报它们——
   与本项目形态 6（"分析器判定不是被分析文件的纯函数"）同源，**不据此断定它们永远干净**。
3. **读/写工具在真正访问前调 `context.resources().require(...)`**（AgentTool 的资源 SPI 契约）。T5 的测试以
   `ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources())` 注入判定者后**直接 execute**（不经 `ToolCallAuthorizer`），
   因为写工具的 `Ask` 闸属 T6 装配。宿主级审批/资源前置闸的行为验证归 T6/T7。
4. **`catalog` 列的是 8 个领域命令类型**（`CommandRegistry` 的类型面），**不含** `advance`/`fork`——后两者是 Core 自己的命令、
   由专用工具直达（spec §7.1、任务 §3.2 R5）。判据②"任意合法状态"的覆盖由 catalog 8 + advance/fork 两条专用工具共同构成。
5. **`onChange` 为 no-op**：本源是构造期定死的静态集，无动态增删口子；返回空 `AutoCloseable` 满足契约（并对 `listener` 做非空校验）。

---

## 5 我未能核实的

1. **MCP 面**：`jsonSchema` 经 `McpToolAdapter` 的 `tools/list` 呈现、工具的 `noExport` 过滤、`Ask` 触发审批并放行/拒绝——
   全归 T6/T7；本任务**未**跑任何 MCP 客户端。
2. **审批闸**：写工具的 `gate()` 返回 `Ask(classKey=工具名)` 已在单测里断言，但"未批 ⇒ `APPROVAL_DENIED` 且无 revision"（R3）未验（T6）。
3. **资源前置闸**：调用者够不着声明命名空间时 `ToolCallAuthorizer` 的 `RESOURCE_DENIED` 行为未验（T6/T7）；T5 只证了声明值与工具内 `require` 不炸。
4. **`Shell.close()` 关闭桥**：`toolBridge.close()` 后注册表清空**未单测**（未在任务要求的用例清单里；T7/T11 生命周期覆盖）。
5. **`simos.fork` 工具未端到端执行**（`submit` 与 `advance` 已各有一条真提交；`fork` 只做了注册/闸/资源的结构断言）。
6. **`ToolRegistry.sourceIds()` 为空**：`McpSourceBridge` 用 `registerAll`（不记来源）同步工具，故注册表不记 `"simos"` 来源——
   已实测确认，属 AgentLib 桥的既有行为，非 T5 缺陷；按源卸载因此需走 `McpSourceBridge.close()`（本任务已接）。

---

## 6 证据索引（`.superpowers/sdd/2026-09-19-shell-simos-plan/t5-evidence/`）

| 文件 | 内容 |
|---|---|
| `logs/targeted.log` | 定向 `SimosToolsTest`（11 绿，rc=0） |
| `logs/full-verify.log` | `./mvnw clean verify` 绿（rc=0、800 条、BugInstance 0×6、ERROR 0） |
| `logs/m1.log` | 变异 m1（R5）九道门禁 + 自指 md5 补记 |
| `logs/m2.log` | 变异 m2（R4）九道门禁 + 自指 md5 补记 |
| `mutants/mut-round.sh` | 变异装置（ROOT=worktree、模块=simos-app） |
| `mutants/m1.CatalogTool.java` / `mutants/m2.CommandSubmitTool.java` | 变异体（按目标类名推入） |
| `mutants/orig/CatalogTool.java` / `mutants/orig/CommandSubmitTool.java` | 原件备份（`cp` 还原的字节来源） |
