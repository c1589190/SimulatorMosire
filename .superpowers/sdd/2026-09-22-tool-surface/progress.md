# SDD ledger — plan: docs/superpowers/plans/2026-09-22-tool-surface-plan.md

> 宗旨：`docs/superpowers/specs/2026-09-22-tool-surface-creed.md`（**spec，绑定权威**）
> 工作区：worktree `/home/dev/SimulatorMosire/.claude/worktrees/ts+m1`，分支 `ts/m1`，基线 `5c090ce`
> **M1 BASE = `1fca612`**（开工提交：本台账）⇒ 审查包 = `1fca612..M1-HEAD`
> 证据目录：`.superpowers/sdd/2026-09-22-tool-surface/m1-evidence/`
> ★ 本机 `nproc=2` ⇒ **一次只准有一个重活**（Maven 与子代理不并存，已实测会杀掉 agent）

## 〇-pre. ★★ 环境阻断（2026-09-22 控制器只读实测，**开工即红**）

**事实：本阶段的基线在这台机器上编译不过** —— `simos-sd` 编译失败，与任何本阶段改动无关。
M1 实现者的 `m1-evidence/logs/baseline-clean-verify.log` 记 `rc=1`，死点是：

```
simos-sd/.../sd/adjudication/LlmDecisionAdjudicator.java:[55,13] cannot find symbol: method degradable()
simos-sd/.../sd/adjudication/LlmDecisionAdjudicator.java:[58,53] cannot find symbol: method kind()
  location: variable e of type io.mosire.agentlib.llm.LlmException
```

**根因 = 跨机 `~/.m2` 差距，不是代码缺陷**（逐条实测）：

| 项 | 本机实测 | 需要 |
|---|---|---|
| `~/.m2/.../agentlib-mosire-0.1.0-SNAPSHOT.jar` | **126 类**、`LlmException` 只有 2 个构造器 | **134 类**、含 `LlmException.Kind`（`retryable()`/`degradable()`）+ `kind()`/`degradable()` |
| 台账点名的 6 个新类（`Sampling`/`LlmTransport`/`LlmProtocol`/`LlmRouteAssembler`/`ToolDefs`/`LlmException$Kind`） | **全部缺席（0 命中）** | 逐条在场 |
| 旧 jar md5 | `4b85536d8d48c041fac21dfb7fb2de02` | —（**回滚锚点**） |

- ★ **绿轮是真的，但在另一台机器上**：`2026-09-22-llm-integration/logs/clean-verify-final.log` 零编译错误、
  `verify-final-rc.txt` = `RC=0`、8/8 SUCCESS；而**该台账自记「本机 `nproc=8`」**，本机 `nproc=2` ⇒ 不同机器。
  ★ 两处独立佐证：绿轮 `UtilSimos SUCCESS [4.956 s]` vs 本机 `[01:25 min]`；绿轮的
  `ProjectMosire` HEAD 提交自述「提交 AgentLib 编译产物（**126 类**）」而本机装在 `~/.m2` 的正是那份。
- 冻结字节**逐字节相同**（排除"文件被改"）：`LlmDecisionAdjudicator.java` 现 md5 = **`81062fbf…`**
  = 绿轮 `FINAL-GREEN.txt` 记的冻结 md5；两份 agentlib jar（`~/.m2` 与 `ProjectMosire/…/target/`）md5 也相同。
  ⇒ **同一份字节，一绿一红 ⇒ 差异只可能来自机器环境**（这正是"换机器先核一遍"那条纪律的又一实例）。
- **不具备解释力的假设（已排除）**：① 陈旧 jar——用户那轮自己的备注也提示过陈旧，但**本机这份就是它记录的那份**，
  且"绿"发生在用它之后；② 文件被改——md5 相同；③ 可从远端 `-U` 取——`_remote.repositories` 是
  `agentlib-mosire-0.1.0-SNAPSHOT.jar>=`（**空仓库 id = 纯本地 install**）+ `maven-metadata-local.xml`
  的 `<localCopy>true</localCopy>`，simos 侧 `pom.xml` **无 `<repositories>`** ⇒ **没有远程可取**。

**处置（控制器裁决）**：`~/ProjectMosire` 有远端 `git@github.com:c1589190/ProjectMosire.git`，
本地 `main` = `5141cf9`（Sep 20，126 类），远端 `main` = **`212f57e`**，且 fetch 输出为
**`5141cf9..212f57e`（快进、无分叉）**。远端那份的 `LlmException.java` **正是 simos 需要的**：

```
23: public class LlmException extends RuntimeException {
26:   public enum Kind {
99:     public boolean degradable() { return degradable; }
116:  public LlmException(String message, Kind kind) {
126:  public Kind kind() {
136:  public boolean degradable() { return kind.degradable(); }
```

且 `5141cf9..212f57e` 只有两条提交，其一为
**`3267973 feat(llm): 对齐 simos 需求 A1-A5 / B1-B4（…异常分类…）`** —— **该改动本就是为 simos 做的**。

⇒ **裁决：从 `ProjectMosire@212f57e` 重建并 `install` `agentlib-mosire` 到 `~/.m2`。**
依据：CLAUDE.md 明文预授权本条路径（「`~/.m2` 不跨机同步……按测试里的提示在本机重建一次」），
且方向是**该库自己的远端 main、快进、为 simos 而做**，不是回头路。
*代价若判错*：本机 `~/.m2` 被升到 134 类；**已保留旧 jar 与其 md5（见上表）可原样回滚**。
*不影响别人*：执行前实测**无 java 实例在跑**、`nproc=2` 且**独占**（实现者已被叫停）。

**★ 对全阶段的后果**：在环境修复并跑出**本机绿基线**之前，**M1 的一切门禁/变异结论都无效**。
⇒ 修复后的第一件事是**重跑基线 `clean verify` 并现场重算数字**，写进本台账，作为 M1 的真实 BASE 参照。

### 〇-pre.1 环境修复执行记录（2026-09-22 控制器，已执行）

**做法**：`git -C ~/ProjectMosire archive 212f57e | tar -x -C /tmp/pm212`（**只读 git，不动
`ProjectMosire` 自己的检出**、不碰它那个未提交的 `M mvnw.cmd`），再
`mvn -f /tmp/pm212/pom.xml -pl AgentLibMosire -am install -Dmaven.test.skip=true …`。

**结果（逐条实测，非推导）**：

| 项 | 修前 | 修后 |
|---|---|---|
| `~/.m2` 的 jar **类数** | 126 | ★ **134** ✔ |
| jar md5 | `4b85536d8d48c041fac21dfb7fb2de02` | **`4d706ab6ce421e749ef4c2ed1dc913c5`** |
| `LlmException.kind()` / `degradable()` / `retryable()` | 无 | ★ **三者在场**（`javap` 实测） |
| 台账点名的 6 类 | 全 0 命中 | ★ **全部在场**（`Sampling`/`LlmTransport`/`LlmProtocol`/`LlmRouteAssembler`/`LlmResponse`/`ToolDefs`/`LlmException$Kind`） |

- 构建 `BUILD SUCCESS`，33.2 s；`ProjectMosire Parent SUCCESS [12.148 s]` + `AgentLibMosire SUCCESS [19.911 s]`。
- ★ **判据按 CLAUDE.md 的"看类数不看时间戳"** —— 用的是 `jar tf | grep -c`，不是 mtime。
- **回滚锚点物理留存**：旧 jar 整份备份在 `/tmp/agentlib-old/agentlib-mosire-0.1.0-SNAPSHOT.jar`
  （md5 与上表修前值逐字节相同），需要时一条 `cp` 即可回退。
- ★ **本次会话实测再次印证**：本机**没有 `unzip`**，`unzip -l <jar>` **无输出也不报错**（实现者中过一次
  假阴性）⇒ 数类数一律 `jar tf`。这条已在 CLAUDE.md 的换机自检清单里，是它第二次兑现。

**本机绿基线**：`m1-evidence/logs/baseline-local-clean-verify.log`（前台起跑、600 s 处被 harness
**摘到后台**，**不是** `run_in_background` 起跑 —— 两者不同，见 M8 T12 与 unit-ext T8 的实测）。
★ **数字一律现场重算**，结果记在下一条。

### 〇-pre.2 ★★ 本机绿基线（2026-09-22，**M1 的真实 BASE 参照**）

**环境修复后第一轮全量门禁即绿**（第 **1** 次尝试；前台起跑、600 s 处被摘到后台、**仍在后台跑完**）。

| 项 | 实测值 |
|---|---|
| rc | **0** |
| `BUILD` | **SUCCESS** |
| 反应堆 | **8/8 `SUCCESS [`** —— `SimulatorMosire`(父 POM) + `UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp` |
| **用例总数（现场重算）** | ★ **1406** = **170 / 369 / 45 / 259 / 179 / 141 / 243** |
| `COMPILATION ERROR` | **0** |
| `^[ERROR]` | **0** 行 |
| `BugInstance size is 0` | **×7** |
| 前端门禁 | `[frontend-gate] OK tests=204 pass=204 fail=0` |
| 日志 md5 / 大小 | `da8838f4109ba8fd8cb9c35e91d15200` / 424694 B |

★ **重算法**：只取**模块汇总行**（`^\[INFO\] Tests run: N, Failures: 0, Errors: 0, Skipped: 0$`，无 `-- in`
后缀那 7 行）相加，**不引用任何文档里的现成数字**。逐模块：util 170 / map 369 / social 45 / unit 259 /
core 179 / sd 141 / app 243。

★★ **一处如实登记的跨机差异（未证实成因，不编解释）**：`2026-09-22-llm-integration` 那台机器的绿轮记
**1405 = 170/369/45/259/179/141/242** —— **前六个模块逐字相同，只差 `app`：本机 243 vs 其 242（+1）**。
- **候选解释（是假设，不是结论）**：`BindAddressTest.java:62` 用
  `Assumptions.assumeTrue(lanIp.isPresent(), "本机无非回环 IPv4，跳过跨接口可达性断言")` ——
  **唯一一处**环境条件性用例。本机该轮实测 `Tests run: 3, Skipped: 0`（本机**有**非回环 IPv4），
  但**"另一台机器因假设不成立而少计 1"我没有验过**（另一台机器的 per-class 计数我拿不到）⇒ **未证实**。
- **影响**：**不影响 M1 的 delta 记账** —— M1 的参照就是本表（本机、本树的实测值）。
- ★ 与"按机器/按外壳"那一族同源（`~/.m2`、子代理类型、`-f` 是否必要）：**跨机数字不可照抄，一律现场重算**。

---

## 〇. 开工前裁定（用户 2026-09-22 当场裁定，取代计划 §四 的"倾向"列）

| # | 裁定 | 与计划的差异 |
|---|---|---|
| **D-1** | unit 20 条**也给决策人桶** | ★ **与计划倾向（不给）相反** ⇒ 按用户裁定执行（unit 工具是**窄工具**、非通用写，与 N9「决策人无通用写」不冲突） |
| **D-2** | `sd.SetDecisionMakerProvider` **给 GM** | 与倾向一致 |
| **D-3** | 读口**先列清单再定** | 与倾向一致 ⇒ **M4 的派单前必须先产出清单并回用户** |
| **D-4** | 通用写收窄 = **只评估** | 与倾向一致 |
| **D-5** | 导入器 tag 改 `nation:<名>` **并入本阶段** | 与倾向一致 ⇒ 并入 M3，且**需重导/复验真档** |

## 一. 开工前冲突扫描（每对共享文件的任务一行 + 每任务自洽一行）

### 1.1 任务对（共享文件 / 接口）

| 任务对 | 共享点 | 一方的产出 vs 另一方的消费 | 结论 |
|---|---|---|---|
| **M1×M2** | `SimosToolSource.java` 的写桶构造点；`McpCoverageTest`；工具计数断言 | M1 的 7 条与 M2 的 20 条加进**同一个** `List<AgentTool>` 构造点 | **冲突 ⇒ 串行** |
| **M1×M3** | 同上（`addGmWrites`）；`CatalogTool.PAYLOAD_HINTS` | 同 | **冲突 ⇒ 串行** |
| **M2×M3** | 同上 | 同 | **冲突 ⇒ 串行** |
| **M1/M2/M3×M4** | `SimosToolSource.readTools`（9 条读工具**四桶共享**）+ 各桶既有断言 | M4 加读工具会牵动 M1~M3 刚落定的桶断言 | **冲突 ⇒ 串行，且 M4 排最后** |
| **M5×其余** | 无代码改动（只评估） | — | **独立**，最后做 |

★ **扫描结论：M1~M4 全部串行**（计划 §二写的"组内可并行"**不成立**——四组都改同一批文件）。
★ 这也符合本仓通则：**同一文件被两个任务改 ⇒ 串行**。

### 1.2 任务自洽（计划文本 vs 实读代码/裁定）

| 任务 | 计划文本 | 实测/裁定 | 处置 |
|---|---|---|---|
| **M1** | §三.4「连带：`CatalogTool.PAYLOAD_HINTS` 补一条」 | 7 条 map 提示**已在表内**（`CatalogTool.java:57-64`）⇒ **对 M1 是空操作** | 记录，不派无用工作（`McpCoverageTest` 面待 recon 结论） |
| **M1** | §一「map 写 现有工具 **0**」 | 待 recon 核实 | — |
| **M2** | §二 M2「★ 待裁 D-1：倾向不给」 | **用户裁定：也给** | 按裁定执行；本台账为准 |
| **M3** | §二 M3「`SetDecisionMakerProvider` 是否开给 GM？（倾向开）」 | 用户裁定：开 | 按裁定执行 |
| **M3** | §四 D-5 并入 = "M3 的前置消除" | 实际是 **Python 导入器改动**（`tools/`，不入 Maven reactor）+ 真档复验 | 记为 M3 的子项，独立判据 |
| **M4** | §二 M4 要求"逐条列出缺哪些" | 用户裁定 D-3 = 先列清单再定 | **M4 的派单前必须先交付清单并回用户**（阻塞点，非我能自裁） |
| **M5** | 只评估不改 | 同 | 最后做，产出评估报告 |

## 二. 任务台账

（每任务一行：`Task <N>: complete (commits <base7>..<head7>, review clean)` 或 fix round 行）

### Task 4 — M4 读口补齐（**取证完成，实现待用户圈范围**）
- 状态：**取证完成**（`m4-inventory.md`，只读，未跑构建）⇒ **实现阻塞在用户 D-3 裁定**
- ★ **计划 §一 的「GUI 23 端点」对不上**：实测 **17** 个 JSON 读端点。
  17 + 5 静态页 + 1 条审批 GET ≈ 23 是**取证者的对账解释，非出处**；且报告核实
  `/api/approvals/{id}` **要求 POST** ⇒ 那条 GET 可能根本不存在 ⇒ **23 的出处未证实**（报告 §五-4）。
- 现有读工具 **9** 条（`SimosToolSource.java:137-148`），★ **四桶共享**（`SimosToolSource.java:100` 先铺读工具再按 `Role` 追加写工具）。
- **缺口 9 条**：8 完全（`map.path`/`timeline`/`sd/decision-makers`/`sd/decision-makers/{id}`/`sd/verdicts`/`gm/tool-usage`/`llm/providers`/`map/region/{id}`）+ 1 部分（`/api/state` 的 `meta` 面）。

**暴露风险（控制器按报告 §二 归纳）**：

| 级 | 端点 | 要害 |
|---|---|---|
| **最高** | `/api/sd/verdicts` | ★ **唯一「支持 `as=` 又允许省略」**的 sd 读端点；省略 ⇒ `RedactingQueryService:167` 写死 `DisclosurePolicy.FULL`，连**模型输出 `payload`** 一起发。同类端点（decision-makers/tool-usage/llm-providers）都是**显式 `rejectAs`** fail-closed。该口径被 `RedactionApiTest:184` 钉为**有意** ⇒ **照抄成读工具不会自己红** |
| **高** | `/api/sd/decision-makers`(+`/{id}`) | 含**别人的** `rootUnit`/`viewScope`/`allowedTools`/`providerId`；**不收 `as=`** ⇒ 无任何按视角裁剪机制 |
| **高** | `/api/gm/tool-usage` | GM 内部观测面；四桶共享 ⇒ **观测者被反向观测**（该端点亦 `rejectAs`） |
| **高** | `/api/map/path` | 地形探测读，且**显式拒绝 `as=`**（今天无视角概念）⇒ 照抄 = 给决策人一条**绕过 `viewScope` 的逐格测地形通道**；同一能力在写面已有 `unit.PlanRoute`（**走审批**），读的那副不受审批 |
| **高** | `/api/llm/providers` | ★ **密钥值不泄露**（逐字段读过：只发 `credentialsRef` 掩码 `:457-461` + `keyConfigured` 布尔 `:311-320`，**无一处分发密钥值**）；但暴露 `baseUrl`/`model`/**key 名**。★ **一处未核实**：`:226` 坏条目把 `ConfigException.getMessage()` 原样发出，该消息是否可能带密钥值**未读到构造端** |
| 中/低 | `map/region/{id}` · `timeline` · `/api/state`(meta) | 可开，但需先定 `as=` 语义 / 只差值形状 |

**★ 既有读工具的两处形状缺陷（不是缺口，是已存在的东西不对）**：
1. `simos.map.hex` 的 MCP 版 **少 `regions[]`/`edges[]`**（GUI 版有，`ApiViews.java:354-362` vs `MapHexTool.java:76-79`）
   ⇒ **决策人经 MCP 看不到「一格同属多区域」**——而那是 **M8-U1 用户裁的地基语义**。
2. `simos.map.overview` 的 MCP 版与 GUI 的 `as=` 版**仍是逐格数组**（`ToolSupport.mapOverview:306-314`）
   ⇒ **M9 的省流只落在 `ApiViews` 一侧**，真档 19441 格下 ≈1MB/次（`ApiViews.java:165` 注释记着改造前 1,043,837 B）。

**代价核算**：会红的只有**名字集合**类断言（`SimosToolsTest` / `McpServerTest` / `McpPortTopologyTest`）；
★ `McpPortTopologyTest.READ_TOOLS` 被**两个端口共用** ⇒ 一处改两处红（= 读工具四桶共享在测试面的可见证据）。
**不会红**：`McpCoverageTest`（驱动量是**命令类型**，非工具）。

**★ 判据缺口（报告 §四）**：**读口没有 `McpCoverageTest` 的等价物**——一条读工具可以有正确的名字、
正确的 `spec()`，但**形状错 / 抛异常 / 压根不可达**，而**全部现有断言照绿**。
（`listTools()` 在全仓只被调过两次，**没有一次 `callTool`**；`readToolsMatchQueryServicePerValue` 只逐值对拍 3 条。）
另：**同源强判据只覆盖命令**（扫 `*Handler.java`），**没有**"扫 `*Tool.java` ⇄ `readTools`"的同源判据
⇒ 加了 `read/XxxTool.java` 却忘接进 `readTools(...)`：**不会红**。

**★ creed 措辞待正（控制器裁决，待用户确认）**：creed 五写「每加一条**命令/工具** ⇒ 连带
`CatalogTool.PAYLOAD_HINTS` + `McpCoverageTest` 双向载荷」——**逐字读会误导**：该规矩的对象是**命令类型**
（`PAYLOAD_HINTS` 是「命令 type → 载荷提示」表，`CatalogTool` 构造期按**注册 type** 强制）。
**加读工具不产生新命令类型 ⇒ 两张表都不动**（M1 亦已实测为**空操作**）。
⇒ 建议把 creed 该处改成「每加一条**命令**」。**控制器未擅自改 creed**（它是绑定权威、用户文档），
**列为待用户确认项**。

- **状态：`BLOCKED`（环境级）→ 环境已修（见 §〇-pre.1），待重派**
- BASE：`1fca612`；派单时 HEAD `4d0989d`；★ **实现者零提交**、**生产/测试代码一行未写**（已核：`tools/write/`
  无 `MapXxxTool`，`SimosToolSource.java` / 三个测试文件 / `Shell.java` / `simos-map/**` / `simos-sd/**` 全未动）
- 实现者报告在 `task-1-report.md`；基线轮证据 `m1-evidence/logs/baseline-clean-verify.log`（rc=1）
- ★ **实现者独立复核了我的根因**（与控制器结论一致，且是从另一侧得到的）：`~/.m2` 的 jar 与
  `~/ProjectMosire/AgentLibMosire/target` 那份**逐字节相同**（同 md5 `4b85536d…`、126 类），
  且 `git log --all -S degradable` **无输出** ⇒ 本机**全机唯一一份** `LlmException.java` 只有两个构造器。
  ⇒ 「要有 134 类版本，只能先把 `ProjectMosire` 建到含该 API 的版本再 install」——**已被 §〇-pre.1 兑现**。
- ★ **它报的一处数字偏差已裁定（不是缺陷）**：`simos-map` **369** / `simos-core` **179**，比 WebUI 阶段的
  368/178 各 **+1**。出处已查明：**`2026-09-22-llm-integration` 阶段的绿轮总数正是
  `1405 = 170/369/45/259/179/141/242`** —— 即 llm-integration 给 map 与 core **各加了一条用例**
  （实现者猜的 `wsf2/v3` 是错的，但**偏差本身报得对**，且它**没有把猜测当结论**，处理正确）。
  ⇒ 口径以实测为准：**本阶段基线 = 170 / 369 / 45 / 259 / 179 / …（sd 与 app 待本机绿轮补）**。
- ★ 实现者还报了一条**同族假阴性**实测：本机无 `unzip`，`unzip -l <jar>` **无输出也不报错**
  ⇒ 已并入 CLAUDE.md 那条纪律的第二次兑现（见 §〇-pre.1 末条）。

#### 1.1 recon 取证结论（只读子代理，未跑构建）

**载荷形态**：7 条命令全注册在 `simos-app/.../app/Shell.java:306-313`，实现在 `simos-map/.../map/spi/`。
`SetEdge.mode` 与 `RandomizeRegion.seed` **无默认值**（各自 javadoc 明文）。

**域层既有守卫**：用户点名的 6 项**全部存在**且文案可读（`未知地形类型: `/`未知连通性类型: `/
`区域已存在: `/`区域不存在: `/`hexes 不得为空…`/`color 必须是 #RRGGBB…`）。**没有一条静默成功。**

**★ 静默缺口 7 处（域层，全部为读码结论、未运行验证）**：

| # | 缺口 | 锚点 |
|---|---|---|
| A | 通路组 id **大小写变体**：注册侧 `containsKey`（敏感），解析侧 `resolveKind` 不敏感**且取首个** ⇒ 已有 `river` 时注册 `River` 成功，此后 `kind:"River"` 落到先注册的 `river`；两条路径都不报错 | `PathwayGroupOperations.java:38-39` vs `EdgeOperations.java:116-124` |
| B | `map.SetEdge` **无邻接校验**（可连相隔半图两格） | `EdgeOperations.java:59-75` + `GameMap.java:72-89` |
| C | `map.UpdateRegion` 的 `meta` 是**整体替换**：只给 `{"tag":"X"}` 静默清掉 color/description/annexedBy；`"meta":{}` 非 null 却**过**「至少给一个」检查并把 4 字段全清空 | `RegionOperations.java:83,93,96` + `MapPayloads.java:136-150` |
| D | `kind`/`mode` 大小写不敏感（`"REPLACE"` 被接受） | `EdgeOperations.java:116-130` |
| E | 未知多余字段**静默忽略**（可选字段拼错 ⇒ 静默取缺省，如 `visble` ⇒ `visible=true`） | `MapPayloads.java:44-47`；★ recon 自陈**未核实**是否有全局 Jackson 严格配置 |
| F | **空操作仍落 revision**（`MapChangeSet.isEmpty()` 存在但 `CommandBus` 从不读） | `MapChangeSet.java:107` vs `CommandBus.java:320-332,347-378` |
| G | `PropertyDef.type` 不做取值白名单（有意，javadoc 明说） | `PathwayGroup.java:73-77` |
| H | `map.CreateRegion` **无 name 唯一性校验** | `RegionOperations.java:57` |

**爆炸半径 4 处**：`SimosToolsTest.java`（名单 16→23 + `.hasSize(16)`→23）、`McpServerTest.java`（名单 16→23）、
`McpPortTopologyTest.java`（`GM_NARROW_WRITES` 4→11）、`AppWritePathGuardTest` 的禁字（`SqliteStore`/`Timeline`/`CheckpointStore`）。
**零改动**：`CatalogTool.PAYLOAD_HINTS`、`McpCoverageTest`（7 类型已在双向断言与最小载荷表内）。

**GUI 侧第二源**：**没有** per-command 端点，7 条全走 `POST /api/command`；第二载荷源是 webui JS
（`map.js` 各构造点已取证）。★ **`map.RegisterPathwayGroup` 在 webui 里没有载荷构造** ⇒ 无第二源可对拍。

#### 1.2 控制器裁决（本任务）

- **裁决 A**：**M1 不加工具层前置校验**。理由：① 域层 7 条全部已有可读拒绝文案，经 `ToolSupport.fold`
  已变成 `ToolResult.error("REJECTED", <原文>)` ⇒「可读理由到达调用方」**已成立**；② 工具层校验
  **可被 `simos.command.submit` 绕过** ⇒ 是装饰（违反本仓「护栏必须自证」）；③ 计划 §三.2 的用意由 fold 兑现。
  ⇒ 本任务的义务是**证明拒绝理由真的到达调用方**（判据 4：7 条各一条坏载荷用例）。
  *代价若判错*：若用户本意是"工具层也要挡一道"，则要在 7 条里补校验并重跑变异轮。
- **裁决 B**：**A~H 七处域层静默缺口不在 M1 修**。理由：① 它们是**既有的**——今天经通用写
  （`simos.command.submit` 已在 EXTERNAL∪GM 桶里）就可达，**不是 M1 新造的**；② 改域层会牵动已关账的
  M2/M8 变异轮（裁定 42：改动被测文件 ⇒ 旧证据作废、须重跑）；③ 代价远超本任务价值。
  ⇒ **记为开口项**，其中 **A / B / C** 三处**用户可见**，随 M1 报告一并回报。
  *代价若判错*：缺口继续存在；用户若要修，另立任务。
- **裁决 C**：★ **`SimosToolsTest` 的 `subList` 索引切片是真缺陷，M1 必须修**。
  读闸 `subList(0,9)` / 写闸 `subList(9,16)`：名单变长后切片仍合法 ⇒ **测试照绿，新增 7 条写工具完全不被
  写闸覆盖**（「把没发生伪装成没发生」族）。修法：改成**按名单选**，且断言语义改为
  **「写闸覆盖集 == 写工具全集」** ⇒ 这样"退回切片"才**杀得掉**（否则修复自身无护栏）。
  *代价若判错*：无——这是净收益。
- **裁决 D**：7 条专归 **GM 桶**（照计划；`EXTERNAL_WITH_GM` 自动含 GM，不动 `addExternalWrites`/
  `addDecisionAgentWrites`）。map 写**不进决策人桶**（D-1 裁定的是 **unit** 20 条）。

