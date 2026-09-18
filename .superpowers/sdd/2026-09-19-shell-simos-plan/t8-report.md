# M5 T8 报告 —— GUI 服务器与 `/api`

> **任务**：M5 T8（`docs/superpowers/plans/2026-09-19-shell-simos-plan.md` §二 T8；spec §8.1/§8.2）
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/m5t8`，分支 `m5/t8`，基线 `0708153`（PREFLIGHT 已自证）
> **日期**：2026-09-19
> **范围**：WebUI 出形 1/2 —— 5711 单服务（JDK `HttpServer` + 静态资源 + `/api` 只读/写端点）；前端三页归 T9。

---

## 1 交付物

### 新增源码（`simos-app/src/main/java/io/mosire/simos/app/gui/`）

| 文件 | 内容 |
|---|---|
| `GuiServer.java` | JDK `com.sun.net.httpserver.HttpServer` + **虚拟线程 executor**（`Executors.newVirtualThreadPerTaskExecutor()`）；`start(host, port)`（`port=0` 支持）→ `boundPort()` / `boundHost()`；单 `/` context 内路由（`/api/*` 走 JSON，其余走静态）；`close()` 停监听（释放端口）+ 停执行器，幂等；Jackson 用共享装配点 `SimosObjectMapper.create()`（**无新依赖**） |
| `StaticHandler.java` | classpath `/webui/...` 静态资源；页面别名 `/`→`index.html`、`/map`/`/unit`/`/social`→各自 `.html`；`Cache-Control: no-store`；`..` 目录穿越不服务；内容类型按扩展名 |
| `ApiViews.java` | 领域类型 → JSON 视图树（**只读纯函数**）；位置走 `UnitState.effectivePosition`、人口走 `PopulationSeries.valueAt`，与 facet 同口径 |

### 修改源码

| 文件 | 改动 |
|---|---|
| `Shell.java` | `start()` 末尾建并启动 `GuiServer`（host `127.0.0.1`，审批 base URL 传 `null` = T6 接缝）；新增 `boundGuiPort()`；`close()` 次序 **GUI → CoreSimos**（spec §3.3）；装配日志补 `guiPort` |
| `ShellMain.java` | 启动日志的 `guiPort` 改打**实际绑定端口**（`shell.boundGuiPort()`），不再回显配置值 |

### 新增静态资源

| 文件 | 内容 |
|---|---|
| `simos-app/src/main/resources/webui/index.html` | **最小**首页骨架（标题 + `/map` `/unit` `/social` 链接 + 待批占位）；三页与共享 js/css **不做**（T9） |

### 端点（spec §8.2，全部落地）

- 静态：`GET /` `/map` `/unit` `/social`。
- 只读（全部经 `QueryService`）：`/api/state`（branches + heads + head meta）、`/api/resolve`、`/api/facets`、`/api/map/overview`、`/api/map/hex?q=&r=`（**canonical 地址查 facet**）、`/api/units`、`/api/unit/{id}`（有效位置）、`/api/social/population?q=&r=`。
- 写（全部经 `CoreSimos.submit`；`initiator="player:gui"`、`commandId=correlationId=` 新 UUID）：`POST /api/command`、`POST /api/advance`、`POST /api/fork`。
- 审批接缝：`GET /api/approvals`、`POST /api/approvals/{id}` —— 未配置 base URL ⇒ **503**（T6 未接入）；构造器 `approvalBaseUrl` 为注入点。
- 响应：写端点 `{result:"committed"|"conflict"|"rejected", ref?/current?/reason?}`（200/409/422）；未知路径 404；已知路径错方法 405（带 `Allow`）。

### 新增测试

| 文件 | 条数 |
|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/gui/GuiApiTest.java` | 12 |
| `simos-app/src/test/java/io/mosire/simos/app/AppWritePathGuardTest.java` | 2 |

### 证据与装置

`t8-evidence/`：`logs/{targeted,full-verify,full-verify-final,m1,m2}.log` + `mutants/{mut-round.sh,m1.GuiServer.java,m2.GuiServer.java,orig/GuiServer.java}`。

---

## 2 实测数字

### 定向（`./mvnw -pl simos-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='GuiApiTest,AppWritePathGuardTest' test`）

`rc=0`；`GuiApiTest` **12** / `AppWritePathGuardTest` **2** = **14 条全绿**（`logs/targeted.log`）。

### 全量（`./mvnw clean verify`，`logs/full-verify.log` 与 `logs/full-verify-final.log` 两次一致）

`rc=0`；**776 = 170 / 255 / 45 / 131 / 147 / 28**（util / map / social / unit / core / app）——**7/7 reactor entries SUCCESS**；
`BugInstance size is 0` **×6**；`^[ERROR]` **0 行**；Spotless 跑遍 6 个 jar 模块；走的就是 `verify`（未直调单点 goal）。

**对基线 762 = 170/255/45/131/147/14 的逐模块差**：

| 模块 | 基线 | 现在 | Δ | 来源 |
|---|---|---|---|---|
| util | 170 | 170 | 0 | 未动 |
| map | 255 | 255 | 0 | 未动 |
| social | 45 | 45 | 0 | 未动 |
| unit | 131 | 131 | 0 | 未动 |
| core | 147 | 147 | 0 | 未动 |
| app | 14 | 28 | **+14** | `GuiApiTest` 12 + `AppWritePathGuardTest` 2 |
| **合计** | **762** | **776** | **+14** | |

前五个模块一个不动，app 恰好 +14 ＝ 两个新用例类，**与预期逐条相符**。

---

## 3 变异自证（2 轮，九道门禁）

装置 `mutants/mut-round.sh`（照 M4 task-17 九道门禁骨架，`ROOT` 改本工作树、模块改 `simos-app`；备份是权威原件、工作树先比对；按**目标类名**推送；删陈旧 `.class` 与陈旧 surefire 报告逼本轮重写；三处 md5 追加进日志本身）。

| m | 护栏 | 变异 | 结果（轮 → 红点） |
|---|---|---|---|
| m1 | **R1** 扫描 | 在 `GuiServer` 里加一个**编译得过**的写路径直引（`SqliteStore.open(...).close()` 探测方法） | rc=1、编译错误 0、`Tests run: 2`；红在 `AppWritePathGuardTest.appMainSourcesNeverReferenceTheStorageWritePath:53`（`GuiServer.java 不得出现 SqliteStore`）；工作树逐字节还原（orig=before=restored=`5f3fe11c…`） |
| m2 | **R4** 身份注入 | `GuiServer.GUI_INITIATOR` 由 `player:gui` 硬改成 `player:local` | rc=1、编译错误 0、`Tests run: 12`；红在 `GuiApiTest.writeCommitsThroughCoreAndStampsPlayerGuiInitiator:220`（`GUI 写命令的发起者恰是 player:gui（R4）`）；工作树逐字节还原（orig=before=restored=`5f3fe11c…`） |

九道门禁逐项落位：① 干净世界（m1/m2 的 `orig_md5 == worktree_before == 5f3fe11c…`）；② 字节不同（`mutant_md5` 分别为 `d56e76a4…` / `df533866…`）；③ 按**目标类名** `GuiServer.java` 推送；④ 删陈旧 class + 陈旧报告后重编；⑤ `COMPILATION ERROR`=0 且 `Tests run:`≥1；⑥ surefire 报告 mtime（m1 `1789765459`≥`1789765454`，m2 `1789765467`≥`1789765462`）落在本轮；⑦ 红点落在被保护断言（上表方法/行）；⑧ `cp` 还原并核 md5（**未用** `git checkout --`）；⑨ 本轮 md5 追加进各 `.log` 末的"装置补记"段。

---

## 4 取代说明 / 偏离

1. **R1 扫描护栏扫"代码"、不扫"注释"**（取代说明候选）：spec §十一 R1 的字面是"app 源码不出现 `SqliteStore` / `Timeline` / `CheckpointStore`"，而 `Shell` 的 javadoc **有意**写着"本类不持有 `SqliteStore` / `Timeline.appendRevision` / `CheckpointStore`"（文档，不是写面）。故护栏先 `stripComments` 再判串：注释里的串不触发，真正的引用（类型/类字面量/构造调用/字符串字面量）触发。stripper 的边界由 `stripCommentsKeepsCodeAndDropsComments` 自证（注释去掉、代码与字符串保留），并**由 m1 变异轮反向证明它确实抓得住代码引用**（若 stripper 把一切都抹掉，m1 不会红）。⇒ 不回填 spec；记此取代说明供台账收录。
2. **`ApiViews.java` 是计划外的第三个文件**：计划 T8 Files 写 `gui/{GuiServer,StaticHandler}.java` + 各 handler。把"领域对象 → JSON 视图"抽成纯函数类，避免 `GuiServer` 同时承担路由与视图两件事；仍全在 `simos-app/**` 内。
3. **审批接缝的"已配置"分支返回 501**：任务只要求"未配置 ⇒ 503 + 留注入点"。T6 未接入，无法实测转发，故已配置（`approvalBaseUrl` 非空）时回 `501 Not Implemented` 并留清晰的构造器注入点——**不实现任何审批语义**（照派单）。
4. **`ShellMain` 启动日志改打实际 GUI 端口**：T1 时它无端口可打（未起监听）；T8 起 GUI 后回显配置值会与随机端口（0）不符。
5. **GUI 恒绑 `127.0.0.1`（`Shell.GUI_HOST`）**：spec §〇.4 明确"不做鉴权/TLS（回环基线）"，故不提供改绑 host 的口子（与 AgentLib `ApprovalHttpEndpoint` 同基线）。

---

## 5 我未能核实的

1. **审批代理的转发分支未实现也未测**：`approvalBaseUrl` 被注入时的行为是 `501`（见 §4.3）；真实透传（5713 同路径 `/api/approvals*`）留 T6。
2. **注释剥离器不是完备 Java 词法器**：文本块有专门分支，但 `\uXXXX` 转义与极端嵌套不保证；当前 app main 无文本块（实测 `git grep '"""'` 空），且行为由一条边界用例钉住。是否存在别的会误判的合法 Java 形态，**未穷举**。
3. **读端点的可选查询参数未测**：`branch` / `revision` 参数已实现（缺省 `main` head），但用例只覆盖缺省路径；非 main 分支、显式 revision 的读数未验。
4. **若干 404 分支未测**：`/api/map/hex` 格不存在、`/api/unit/{未知 id}`、`/api/social/population` 无序列——代码路径存在但无用例；已测的 404 只有未知 `/api/nope` 与未知静态 `/nope`。
5. **并发/负载未测**：虚拟线程 executor 的真实并发行为、`CoreSimos.submit` 并发写（M4 裁定 33：`submit` **故意**非线程安全）均未在 GUI 路径下压测。
6. **`/api/approvals` 的 503 体**只断言了状态码，未断言文案。
7. **T9 需要的字段够不够**（`/api/map/overview` 只给了 `hexes/regions/cities/terrainTypes`）未经前端评审。

---

## 6 证据索引

| 证据 | 路径 |
|---|---|
| 定向测试日志（14 绿） | `t8-evidence/logs/targeted.log` |
| 全量门禁日志（776 绿、BugInstance 0×6、ERROR 0、7/7 SUCCESS） | `t8-evidence/logs/full-verify.log` |
| 变异轮后**再跑一次**的全量门禁（确认还原后的源树仍绿） | `t8-evidence/logs/full-verify-final.log` |
| 变异轮 m1（R1 扫描） | `t8-evidence/logs/m1.log` |
| 变异轮 m2（R4 身份） | `t8-evidence/logs/m2.log` |
| 变异装置 | `t8-evidence/mutants/mut-round.sh` |
| 变异体 / 原件备份 | `t8-evidence/mutants/{m1.GuiServer.java,m2.GuiServer.java,orig/GuiServer.java}` |
