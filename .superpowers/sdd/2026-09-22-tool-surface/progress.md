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

### Task 1 — M1 map 组（7 条窄写工具，GM 桶）

- **状态：`BLOCKED`（环境级，第 1 位实现者）→ 环境已修（§〇-pre.1）+ 基线已绿（§〇-pre.2）⇒ 已重派（第 2 位实现者）**
- BASE：`1fca612`；★ **审查包 = `1fca612..HEAD`**
- ★ **重派时（2026-09-22）**：HEAD = **`f04a24d`**、工作树**干净**（第 1 位的 BLOCKED 报告 + 环境证据
  已由控制器提交），故第 2 位实现者的 delta 起点干净、**不会撞上他人的未提交产物**。
  ★ 重派**用全新上下文**的理由已记：第 1 位的 134k token 全耗在环境诊断上，且**零代码落盘**可继承；
  简报 `task-1-brief.md` 自足 ⇒ 换人几乎无损失。派单里已显式写明**环境已修、无需再诊断**。
- 第 1 位派单时 HEAD `4d0989d`；★ **实现者零提交**、**生产/测试代码一行未写**（已核：`tools/write/`
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

#### 1.3 实现者报告（第 2 位）与控制器裁决

- 状态：**DONE_WITH_CONCERNS**（第 2 位实现者）⇒ 控制器已生成审查包、已派任务评审。
- 提交（**均未推送**）：`2c8e29c`（7 条工具 + 写闸切片缺陷修复 + 证据）、`7e48404`（关账报告 + token 级复核装置）。
- **门禁**：`./mvnw clean verify` **第 2 次尝试** rc=0、8/8 `SUCCESS [`、**1414** = `170/369/45/259/179/141/`**`251`**、
  `BugInstance size is 0` ×7、`^[ERROR]` 0、前端 `tests=204 pass=204 fail=0`。
  ★ **数字现场重算**（不引用台账/文档）；**delta 干净**：相对 §〇-pre.2 基线**只动 `simos-app` 243→251（+8**
  = `SimosToolsTest` 15→23**）**，前六模块与基线逐值相同。
- **变异 6 体 / 6 杀 / 0 存活**：全部在**最终字节**上重跑，`COMPILATION ERROR` 计数 **0**，每轮开跑前恢复干净世界并逐字节比 md5。
- **审查包**：`m1-review-package.txt`（= `git log` + `git diff --stat` + `git diff -U10` 于 `1fca612..7e48404`，
  **仅代码面** 11 文件 +565/−22；证据面在 `.superpowers/**` 未入包 —— 实现者报告已逐条描述）。

**裁决 E（★ 控制器写错，第 4 次被实现者纠正）**：brief §8 对 m1 的期望「(a)/(b)/(c) 三处都红」
**结构上不成立**，**不是实现缺陷**。根因：`EXTERNAL_WITH_GM` 是 `addExternalWrites` ∪ `addGmWrites`
的**并集**，把一条工具在**这两个桶之间**搬运**不改变并集** ⇒ 断言并集的 `McpServerTest:183` 与
`McpPortTopologyTest:113` **不可能红**。真牙齿在**按桶**断言处（`SimosToolsTest:360`/`:302`），实测 m1 **红在那两处**。
⇒ **m1 是有效的变异体，红的理由也对**；错的是控制器在简报里写的期望。**未改任何判据、未放松任何断言。**
*代价若判错*：无（记录一条控制器措辞错误）。★ 与裁定 65、72.1 同族：**派单文本里的"期望"必须能被实现者反驳**。

**裁决 F**：两条护栏**没有独立可杀的变异体**——`:365`/`:371` 的负向断言与 `:360` 在**同一方法内**，
集合一旦错就先在 `:360` 红；`McpPortTopologyTest:130` 的决策口精确匹配本轮无靶子。
**判定：结构性，不是装饰**（该方法的整体可杀性由 m3 证明：退回切片 ⇒ 红）。**如实登记，不补造变异体。**

**裁决 G（范围声明）**：判据 §6.4 的调用层是 **tool-object 层**（`toolRegistry().find(...).execute(...)`，
`AccessToken.SYSTEM`），**不是真 MCP 传输 + 审批链** ⇒ 7 条新工具"经真 MCP 与审批链"的调用**未验**。
**判定：不补**——理由：① 既有 4 条 GM 窄写工具**同样没有**一条真 `callTool`（全仓 `callTool` 只在
`McpCoverageTest:407`(catalog) 与 `:448`(submit)）；② 7 个**命令类型**经真 MCP 已由 `McpCoverageTest` 覆盖；
③ 本任务**没有降低既有水位**。⇒ 记为**与既有面一致的范围声明**，不冒充"已端到端"。

- 两条 **VOID 轮**（m3 第 1 轮：装置自身回显行匹配到裸 `COMPILATION ERROR` 模式判红；第 2 轮：未加引号的判定串
  让 bash 在 ⑥ 之前中断、变异体留在树里）**留档不删**，已按 md5 逐字节还原。
- 实现者**拒绝了自己的行级注释分类器**（spotless 折行被误判为 14 处非注释改动 ⇒ 假警报），改用
  **token 级** `check-token-identical.py`，并做**双侧自证**（自比必须相等 / 已知变异体必须判不同）⇒
  12 文件 `code=同`、字面量相同 ⇒ **代码面未变、证据不被作废**。

#### 1.4 任务评审（1 轮，无修复轮）与 **Task 1 关账**

**评审者判定**：**规格符合性 = 符合**／**任务质量 = 通过**。逐条核过判据 1~5 与爆炸半径 (a)~(e)；
**未发现被放松的断言**（diff 里被删的行全是 javadoc/`{@code}`/名单常量尾部/`hasSize(16)→23`/
两处 `subList` 循环），改动方向是**增加** `AskKind.SENSITIVE`、`containsAll`、`doesNotContainAnyElementsOf`。
它另做了两件超出我要求的事：7 条 `description()` 与 7 个类的**逐字机械比对**（7/7 全等）；
**树归属现场复核**（7 个 `Map*Tool.java` 只存在于本 worktree，主检出同目录一个都没有 ⇒ 没落错树）。

**E/F/G 复核**：**E 同意**（另补两条佐证：`McpServerTest` 里真 `callTool` 的写只有 `simos.command.submit`；
今天唯一对外口就是 `EXTERNAL_WITH_GM`，端口层本就看不见桶内搬运）／**G 同意**（且把我漏掉的
`ShellEndToEndTest.callWithApproval`(advance) 与 `McpCoverageTest`(fork) 两处真往返点出来了 ⇒
盲区口径从"4 条"修正为 **11 条**）／**F 部分不同意** ⇒ 见裁决 H。

**裁决 H（★ F 的理由我写错了一半，第 5 次被下游纠正）**：
- **`:365` 那半句错**：我写"任何能红的变异必先红在 `:360`"⇒ **不成立**。反例是**拷贝型**变异
  （在 `addDecisionAgentWrites` 里加一行 `built.add(new MapSetTerrainTool(...))` 而 **GM 侧保留**，
  不撞名、不触发注册表 fail-fast）：`:356-360` 绿、`:361-364` 绿，**首红恰是 `:365`**。
  我当时的隐含前提是"变异必须是把工具**搬走**（move）"——**那个前提是我自己加的，评审者指出了它**。
- **`:371` 那半句对**：EXTERNAL 桶只由 `addExternalWrites` 喂，拷进它会让**并集这一个源**里出现两条同名
  ⇒ `ToolRegistry` fail-fast（`工具名重复`）⇒ 夹具即死、`:371` **跑不到** ⇒ 它结构性无法成为首红。
- **处置：只改口径，不补跑 M1 的变异轮。** 理由：① F 的**结论**（"不为 M1 补造变异体"）仍成立——
  **M2 的 m1 正是同一条护栏在同一个桶对上的镜像**（从决策桶删一条而 GM 保留），M2 当场会杀它；
  ② 本机 `nproc=2` 且全量门禁是**概率事件**（T7 实测连杀 3 轮），为一句话的错跑一轮整价门禁
  = 用户明令禁止的过度评审。
- ★ **可证伪的触发条件（写下来，不许含糊）**：**若 M2 的 m1 没能让决策口的精确匹配红**，
  则**立即**补这一轮拷贝型变异——**不许**再把"没红"解释成"结构性不可杀"。
*代价若判错*：一条负向断言的"已被证明"状态被推迟一个任务；触发条件已在案 ⇒ 不会永久沉默。

**裁决 I（F1 顺延 M2，并**升级修法**）**：`Shell.java` 四处注释仍写
「现有口 16 工具 = 3 通用写 + **4 GM 窄写** + 9 读」（`:132`，另 `:164`/`:435`/`:691` 同病）。
M1 后实为 **23 = 3 + 11 + 9**（决策人口那句「11 = 2 窄写 + 9 读」**仍是对的**，M1 只动了 GM 桶）。
- **不在 M1 轮修**：M2 落地后这三个数**又要变**（43 = 3 + 31 + 9 / 决策口 31 = 22 + 9）
  ⇒ 现在修等于**连改两次、连跑两轮门禁**。
- ★ **修法升级：不钉数字**，改成**不随工具数漂移的表述**（例：「现有口 = 通用写 + GM 窄写 + 读工具，
  条数以工具面为准」）。依据：本仓已有多次"注释里的数字比代码活得久"的实例，而 `Shell.java` 是
  **M2/M3 必碰的装配面** ⇒ 治本不治标。已写进 M2 简报 §6(e)。

**裁决 J（F2 的结论**反转**——`McpPortTopologyTest:135` **不是**装饰）**：评审者判它"永远不可能是唯一红点"，
**这个判断在生产侧变异纪律下是对的**（`:128-130` 的
`containsExactlyInAnyOrderElementsOf(concat(READ_TOOLS, DECISION_AGENT_WRITES))` **严格更强**，
AssertJ 方法内首失败即止 ⇒ `:133/:134/:135` 三条负向断言全被它遮蔽）。**但结论下得太早**：
`:130` 比的是**真实工具面 ⇄ 手抄常量**（证的是"代码 == 常量"），`:135` 比的是**真实工具面 ⇄ 另一份
独立维护的名单**（证的是"代码里没有 map 写"）。**两者只在"常量是对的"这个前提下等价**——
而"工具进了桶 + 手抄常量被顺手同步"**正是本仓在案的复发性失效模式**（两份手抄名单，改一处漏一处）。
⇒ **`:135` 的独立价值 = 捕获那个前提被破坏**：代码加一条 map 窄写**且**常量同步加名时，
`:130` 绿、`:135` 红。
- **处置：保留 `:135`，加注说明它守的是什么**，**并要求 M2 跑一条双侧自证**（m4）证它真的会响：
  变异 = 「`addDecisionAgentWrites` 里加一条 map 窄写」**且**「`DECISION_AGENT_WRITES` 常量同步加该名」
  ⇒ 期望 **`:130` 绿 / `:135` 红**。★ 这是**测试侧自证**，与本仓既有做法同族（M1 的 m3「退回索引切片」
  就是测试侧变异），不是放宽纪律。若 M2 实测**不可达**（例如常量同步后别的断言先红），
  **如实报"不可达"并给出红的理由**，`与`:135` 的保留一并重新裁定。
- **M2 对 `:131-135` 的处置**：**不许原样留着而不加注**（那正是 F2 的问题）。

**⇒ Task 1 关账**：`Task 1: complete (commits 1fca612..7e48404 + 本关账提交, review clean — 1 轮，无修复轮)`
。F1/F2 为**带裁定的顺延**（并入 M2，零额外门禁代价），F3 为**控制器口径更正**。
★ **认账**：E 与 H 是本阶段**控制器口径错误 2 次**（全仓累计第 4、5 次），**两次都在"派单/裁定的措辞"上、
都不在代码上** ⇒ 与裁定 65、72.1、M7b-T2 同族：**派单文本里的"期望"与"结构判断"必须能被实现者/评审者反驳**。

### Task 2 — M2 unit 组（20 条窄写工具，**GM + 决策人双桶**）

- 状态：**实现完成（DONE_WITH_CONCERNS）⇒ 任务评审已派发（2026-09-22）**
- BASE：**`40e19d2`**（派单前 `git rev-parse HEAD` 记录；★ 不是 `HEAD~1`）
- 实现者提交：**`981586e`**（45 文件、+14,424/−52）+ **`8899457`**（纯文档 + 一处自纠取证数字）
  ⇒ **HEAD = `8899457`**；`git log --oneline 40e19d2..HEAD` 实测恰这 2 条。
- 简报 `task-2-brief.md`（258 行、自足）+ 事实附件 `m2-recon.md`；派发 = 后台子代理，agentId **`a4965a6a079e677a9`**
- 审查包：`m2-review-package.txt`（`review-package <PLAN> 40e19d2 8899457`，2 commits / 1,806,767 B）
  ⇒ 派 **1 位任务评审者**（sonnet，只读；**1 轮为限**），给出 A 规格符合性 + B 代码质量**两个**判决，
  并把实现者的 4 处 concern（c1 m2 字面不可达 / c2 §6(a) 名单形态前提 / c3 取证数字自纠 / c4 变异轮在门禁之前）
  交给它**独立判断**（不许照抄实现者结论）。
- **M2 已自报的两处"简报前提与世界不符"**（控制器**照单收下、未推翻**，因为两者都是可复核的实测）：
  - **c1**：简报 §7 的 `m2` 字面形态**不可达**——20 个 unit 类型**全部**已有同名工具 ⇒
    任何"改成另一个已注册 unit 类型"都先撞注册表**重名守卫**（`Shell.start`，49 条 error），
    红点**不落**在名字同源判据上。实现者**没放松断言**，另加**隔离形态 `m2b`**（未注册名 `unit.CancelRouteX`）
    把该判据**单独**钉住（红在 `SimosToolsTest:437`）。
  - **c2**：简报 §6(a) 把 `EXTERNAL_UNION_GM_TOOL_NAMES` 记成**派生式**，基线里它**与 `WRITE_TOOL_NAMES` 都是手抄 `List.of`**
    （§6(b) 自己也把 `McpServerTest` 那份称作"手抄的第二份"）⇒ 实现者**保持原形态**、两份各自扩到 43、
    **未新增第三份名单**、**未改成派生式**（后者会改掉基线形态）。
- 桶目标：GM **23→40**、决策人 **11→31**、`EXTERNAL_WITH_GM` **23→43**、`EXTERNAL` 恒 **12**
  ⇒ 实测达成（实现者报 GM 40 / 决策人 31 / 并集 43 / EXTERNAL 12；§5.1）。
- **本任务承接 M1 的三条带裁定顺延**（零额外门禁代价）：
  - **F1**（裁决 I）：`Shell.java` 四处注释（`:132`/`:164`/`:435`/`:691`，**按串找不按行号**）改「**不钉数字**」表述
  - **F2**（裁决 J）：`McpPortTopologyTest:131-135` 三条负向断言**加 `.as(...)` 注**，并跑 **m4 双侧自证**
  - **m1**（裁决 H 的**可证伪触发条件**）：决策桶删一条 ⇒ 决策口 `:128-130` 精确匹配**必红**；
    ★ **若没红，不许解释成"结构性不可杀"，必须立刻补 M1 的拷贝型变异轮**
- ★ **裁决 H 的可证伪触发条件没有触发（M2 实测）**：m1 让 `McpPortTopologyTest:153` **真的红了**
  ⇒ 该条负向/精确匹配判据**有牙齿**；控制器此前"结构性不可杀"的记法**已被推翻**（M1 评审时就该记对）。
- ★ **M2 变异**：5 变异体 / 6 轮，**5 KILLED / 0 SURVIVED**（m4 首轮**作废**——split key 少一个换行产出**空操作变异体**，
  修正后重跑：正向精确匹配绿 + `C7 反向③` 红 + 运行时自报 `decisionTool=32`）。
- ★ **M2 门禁**：`./mvnw clean verify` **第 1 次尝试**、前台起跑、**未被杀**、**rc=0**、8/8 `SUCCESS [`、
  **1436** = `170/369/45/259/179/141/273`、`[ERROR]` 0、`BugInstance size is 0` ×7、`COMPILATION ERROR` 0、
  前端 **204/204**、574 s。★ **总数由两条独立口径现场重算同值 1436**（模块汇总行求和 / 逐类 `-- in` 行求和），
  见 `m2-evidence/logs/recomputed.txt` —— **不是引用文档里的现成数字**。
- ★ **零领域改动（实测）**：`git diff --stat 40e19d2 -- AbstractNarrowWriteTool.java simos-unit simos-map simos-sd simos-core`
  **输出为空**。`progress.md` **全程未 stage、未提交**。
#### 2.1 任务评审（**1 轮，无修复轮**）与 **Task 2 关账**

- 评审者 = 后台子代理（sonnet，**只读**，`nproc=2` 下**未跑任何测试/构建**）；输入 = 简报 + 报告 + 审查包（`m2-review-package.txt`）。
- **判决：A 规格符合性 通过 / B 代码质量 通过**（两个判决都给全，无"缺判决"）。逐条实测锚点：
  - §5.1 桶正确：接线 `SimosToolSource.java:171-190`（GM 末尾 20 条）、`:202-221`（决策人末尾同 20 条）、
    `:144-149`（`addExternalWrites` 未动）；断言 `SimosToolsTest.java:497-531`；
    ★ **运行期第三来源** `full-verify.log` 的 `tool=43 decisionTool=31`（评审者 `sort | uniq -c` 复算，唯一值）
  - §5.4：20 条**各自独立 `@Test`**（非循环）、断言是**完整消息片段**；15 个文案片段评审者**逐个回 `simos-unit` 源头核过**；
    第 1 条 `unit.RenameUnit` 单独写且确为唯一自解析载荷者（`RenameUnitHandler.java:53`）；第 9 条用**载荷层**实测文案
    `字段 status 不是合法状态`（`UnitPayloads.java:111`）
  - §5.5 同源判据：先 `.hasSize(31)` 再集合相等、右侧取自**真工具面**；扫描器对"继承窄写基类却抽不到 `NAME`"当场断言；
    ★ 路径按**模块目录**相对化 ⇒ **主树与 worktree 都成立**（避开了本仓"绝对路径含 `.claude` ⇒ 扫到 0"那族失效）
  - §6：20 新类 + 两个 main 文件**零命中**三禁词；`Shell.java` 7 处注释去数字，`git diff` 过滤后**非注释行数为 0**；
    三条负向断言**保留未削弱**且各带 `.as("C7 反向①/②/③：…")`
  - 质量面：20 类**各恰 3 个 `@Override`、0 条控制流**、35–39 行、无 `requireNonNull`（真薄转发）；
    **无为了造红放松任何断言**（`SimosToolsTest` 断言调用数 69→84，无 `!`、无注释掉、无 lenient）
- **发现 1 条（低，非代码缺陷）**：`m2-evidence/mutants/logs/m4.log` 的「装置自记」段写着 `verdict=KILLED`，
  而报告 §5 把该轮判 **VOID**，**日志内没有任何作废注记**（`grep -c 'VOID\|作废\|空操作'` = **0**）
  ⇒ 下一个人按本仓纪律"引用自记而不引用散文"，`grep '^verdict='` 会拿到 **12 条 KILLED / 0 VOID**，
  把**没生效的变异体**读成"§7 的 m4 已被杀"——正是"把作废轮当杀"。
- ★ **控制器当场复核（不采信报告口径，自己读数）**：`m4.log` 全程 `decisionTool=31`（= **未变异**值 ⇒ 生产侧变异**没生效**）；
  `m4-rerun.log` 全程 `decisionTool=32`（生效）。⇒ **发现成立**。
  ★ 附带的判据一般化：该轮自记里 `pushed_SimosToolSource.java=74ca45dd…` 与原件 `ca8b0c74…` **字节不同**，
  但**字节不同 ≠ 行为不同**——空操作变异体正是这个形态 ⇒ **判"变异体是否生效"要看运行期自报，不能看 md5 不等**。
- ★ **修复：当场做掉，不 park**（修复比描述短，符合本仓"已确证的发现当场修"的推论）：
  在 `m4.log` **尾部追加**「★ 作废注记（SUPERSEDE）」块——**原「装置自记」块逐字未改、留档不删**（不是篡改留痕），
  块内含两处可重建判据（本文件 `:282` 起 `decisionTool=31` vs `m4-rerun.log:283` 起 `=32`）+
  权威结论指向 `m4-rerun.log` + **明确警告本文件现有两块"自记"、`grep '^verdict='` 会拿到过期那块**。
- ★ **两处"提示"评审者判为非缺陷**（控制器同意）：① 控制器**派单措辞串了简报**——我在 M2 派单里点名"第 6 / 第 12 条警告"，
  那两条其实在 **M3** 简报的 §2 表里（M2 表没有第 6/12 条那种内容），评审者当场点破并按 M2 实表逐字核过 20 行；
  ② `Shell.java:435/:453` 保留了"9 读"这一**子计数**（简报示例写"…+ 读工具"）——读面本任务未动、9 是稳定结构事实，判**形态差异**。
- ★ **评审者的 6 条"我未能核实"**（照录要点）：① 它**没跑过任何构建**，"绿"全来自实现者日志——它**独立重算**了日志数字，
  但"重算日志"≠"复现门禁"；② M1 参照 `…/251` 未在基线上重跑 verify（与报告 §7-1 同）；③ 决策人口**逐工具**的
  `spec()/gate()` 仍只有**构造性论证**（两桶引用同一批 `final` 类、20 类各只有 3 个 `@Override`），**不是实测**；
  ④ `ToolRegistry.putChecked` 的判据来自 `~/.m2` 里 jar 的**字节码**（`javap`），**不是源码**；⑤ `m4` 首轮不复现（只读授权内做不到）；
  ⑥ 覆盖面同报告 §7-8（真档未跑 / 正向审批链逐条未测 / `modes.js` 白名单未复测 / `agentlib-mosire` 是外部依赖盲区）。
- **变异**：5 变异体 / 6 轮，**5 KILLED / 0 SURVIVED**（m4 首轮 VOID，已作废并补注）。**无修复轮**（1 轮即通过）。
- ★ **待办**：文档批次提交 + 推送 ⇒ **再派 M3**（**必须串行**：与 M2 同改 `SimosToolSource.java` /
  `SimosToolsTest.java` / `McpServerTest.java` / `McpPortTopologyTest.java` 四个文件）。
  ★ **文档提交排在评审关账之后**——否则会挤进修复轮的范围（本任务实际无修复轮，但顺序规则照旧）。

**裁决 L（控制器，M2 开工后补发，已 SendMessage 给 `a4965a6a079e677a9`）**：**简报 §6(e) 的"陈旧注释"清单不全**
⇒ 同一条规则（改成**不随工具数漂移**的表述）**扩到三处**，**并入 M2 当前这一轮**、不单独跑门禁：
1. `Shell.java` **另两处**：`决策人口工具注册表（T4）… 的 11 条工具经桥同步`（M2 后 31 条）、
   `仅 DECISION_AGENT 桶（9 读 + 2 窄写）`（M2 后 9 读 + 22 窄写）。
2. **`SimosToolSource.java` 自己的 javadoc**（简报原本**完全没提**）：`EXTERNAL_WITH_GM` 常量 javadoc 里的
   `… + 7 条 map`、`addGmWrites` javadoc 里的 `… + 7 条 map 窄写（M1）`；顺带去掉
   `复合口因此 也含这 7 条` 里**既有的多余空格**。★ **明确不动**：`外部 MCP 桶（3 写 + 9 读）`（那是 `EXTERNAL` 桶，
   **恒 12**）与类 javadoc 的 `9 读 + 各桶自己的写面`（读工具**恒 9**）。
3. ★ `mcpCaller()` javadoc 的 `12 条工具`——**在 M2 开工前就已经陈旧**：`12` 是 `EXTERNAL` 桶的数，
   而运行时两口是 `EXTERNAL_WITH_GM`（M1 前 16、M2 后 43）与 `DECISION_AGENT`。
   **处置 = 留承重事实（"三条通用写工具在 `GUEST` 下全部不可达"）、去掉总数**；并要求 M2 在报告里
   **如实说明第 3 项不是它引入的**。
- *代价若判错*：若用户本意是"注释里的数字要跟代码一起更新"，则我把三处改成了**不带数字**的表述 ⇒
  信息量略降（但**永不陈旧**）；反向判错（不改）则等于**把已知的过期数字留在 M2/M3 必碰的装配面上**。
- ★ **同时回填 M3 简报 §6(e)**：M3 的义务从"无需再动"改成 **"复核 + 不重新钉死"**
  （M3 往 `addGmWrites` 追加 12 条时**不许在新 javadoc 里写条数**）。

### Task 3 — M3 sd 组（12 条窄写工具，**只进 GM 桶**）

- 状态：★ **已派单（2026-09-22，实现者运行中）** —— **BASE = `7ce2cc175c580b174bbd7edfe6c1bb52802f7ad2`**（短 `7ce2cc1`）
  ⇒ 评审包与修复轮 diff 一律以它为下界（**不是** `HEAD~1`）。
- ★ **派单前置**：M2 文档批次已提交（`7ce2cc1`）并**已推送**（`40e19d2..7ce2cc1  HEAD -> ts/m1`；
  推后 `git rev-list --left-right --count origin/ts/m1...HEAD` = **`0 0`**、`origin/ts/m1` = `7ce2cc1`）。
  —— 顺序按 SDD 的「**派单与提交树外文档不能同序**」：先提交完，再派单，避免 M3 的分叉/rebase。
- 简报 `task-3-brief.md` + 事实附件 `m3-recon.md`（523 行只读取证）
- ★ **简报 §2 的 12 个命令类型经控制器逐一核对**（`git grep 'return "sd\.[A-Za-z]+"' -- simos-sd/src/main`）：
  实装 **16** 条 = 简报的 **12** 条待包 **+** 已带工具的 **4** 条（`IssueDirective`/`SubmitVerdict`/
  `SetViewScope`/`StartDecision`）——**16 = 12 + 4，无拼写错、无遗漏**。
- ★ `CreateArmyHandler` 的拒绝文案亦经复核：`:49` 逐字为 `"nationId 不存在: "`（与 §5.4 表一致）、
  `:52` `"rootUnitId 不存在: "`、`:46` `"军队已存在: "`、`:58` 透传 `e.getMessage()`。
- 桶目标：GM **40→52**、`EXTERNAL_WITH_GM` **43→55**、决策人恒 **31**、`EXTERNAL` 恒 **12**
- ★★ **D-5 已从本任务剥离** ⇒ 见 §三 第 1 条（**裁决 K**）
- ★ 硬禁令：**不许给 `SdSetDecisionMakerProviderTool` 加 provider 存在性校验**——
  ★★ **依据已更正（控制器 2026-09-22 逐字复核后发现本行早先写错）**：
  **不是**"会反转 `SdProviderBindingEndToEndTest:84` 的既有判据"——该用例走
  `core.submit(envelope(...))`（`:87-92`）**直接提交信封、根本不经过任何工具**，
  其 javadoc 原文写的是「若有人把存在性校验塞回 **sd**，本用例会红」⇒ **它钉的是域层，不是工具层**。
  ⇒ 真实依据是 §4 的通则本身：**工具层校验可被 `simos.command.submit` 绕过 ⇒ 装饰**。
  ⇒ 简报 §4 与 §7 的 m4 已同步更正（m4 的期望从"`:84` 红"改为"**① 既有判据照绿 + ② 通用写绕过演示**"）。
  *代价若判错*：若照旧口径派单，M3 会去追一个**不可能出现的红点**，并可能在报告里
  把它解释成"结构性不可杀"——正是**裁决 H** 立规要防的那种错误解释。
- ★ 另一条本任务特有的硬禁令：`CreateArmy` 的"nationId/rootUnitId 不存在即拒"**已经实现**
  （`CreateArmyHandler.java:46/49/52`）⇒ 用户点名的"前置即错"**无需新写代码**，只需**证明它到达调用方**

#### 3.0 派单手记（控制器，2026-09-22）：交给 M3 的**实测**事实（不是记忆）

派单前在 BASE `7ce2cc1` 上**当场量过**四条（简报写成于 M2 落地前，这四条它不可能知道）：

1. **§5.5 同源强判据 M2 确实已落地** —— `SimosToolsTest.java:601`
   `everyNarrowWriteToolClassIsWiredIntoTheGmBucket`，其 `:604` 是 `.hasSize(31)`
   ⇒ M3 的义务是**让它长大（31 → 43）**，不是重建。★ 别与 `:472` 的
   `catalogCoversEveryCommandHandlerImplementation` 混（那条数的是 **`*Handler.java` 的 `type()` 个数**）。
2. **常量实测值**：`EXTERNAL_UNION_GM_TOOL_NAMES` = **43 条、两份手抄各自 43**
   （`SimosToolsTest.java:134` + `McpServerTest.java:102` ⇒ 两处同改到 55）；
   `McpPortTopologyTest.java:96` 的 `GM_NARROW_WRITES` = `concat(List.of(4 条 sd 名), MAP_WRITES, UNIT_WRITES)`
   ⇒ 只需把**第一个 `List.of`** 由 4 扩到 16；`:106` 的 `DECISION_AGENT_WRITES` = `concat(List.of(2 条 sd 名), UNIT_WRITES)` ⇒ **不动**；
   `SimosToolsTest.java:198` 注释里的 `WRITE_TOOL_NAMES` **34** ⇒ **46**。
3. ★★ **一个"照单全局替换就会踩"的陷阱（已写进派单）**：`SimosToolsTest.java:476` **也是 `.hasSize(43)`**，
   但它数的是 `*Handler.java` 的 `type()` 个数（`:475` 的 `.as(...)` 原文可证）⇒ M3 **只加工具不加 handler**，
   **那个 43 原样不动**。⇒ 派单明确禁止任何"43 全局替换"，要求**先接线、跑测试、按红点逐处改**。
4. **去数字（裁决 I + 补丁 L）M2 已做完** —— 复核结论：`Shell.java:435` 现为「…（**条数以工具面为准**）」、
   `:453` 现为「（**9 读 + 该桶自有窄写**）」；`SimosToolSource.java:56` 现为「**9 读 + 各桶自己的写面**」、`:86`/`:116` 亦无钉死条数。
   残留的「20 条窄写」（`SimosToolSource.java:170`/`:194`/`:201`）是 **M2 自己那批新增**的注记、**不随 M3 漂移 ⇒ 不改**。
   ⇒ M3 的义务确认是 **"复核 + 不重新钉死"**。
   ★★ **本节关账时更正（2026-09-22，见下方「Task 3 关账」§更正）**：上面"**⇒ 不改**"这个结论**已被推翻**——
   那 3 处「20 条窄写」**最终改了**（去掉数字）。理由不是"数字当时不准"（它当时是准的），
   而是**规则的对象是"工具面注释里不出现钉死条数"本身**：只要数字在，**下一条 unit 命令加进来时它就会静默过期**，
   而当时的措辞已经让人以为"旧计数都清干净了"。⇒ 9 处一并去掉（含这 3 处）。
   ★ **行号提示**：上面给的 `:170`/`:194`/`:201` 是 **M3 追加 12 条之前**的行号；
   改动时它们在 `:185`/`:222`/`:229`（文件 282 行）。**引用行号务必配当前文件核一遍。**

★ **控制器对简报两处不明确处的裁定（已随派单下达）**：
- **(一)** 简报 §6 写「必红 **4** 处」，控制器实测为 **6 处**（多出 §5.5 的 `:604` 与 `McpPortTopologyTest` 常数的来源处）。
  **裁定：以实测为准，但不让实现者按该清单预先改**——照简报 §3 末句「先接线，让断言自己红出来，再改常量」；
  控制器的清单**只用于对账**，并要求实现者：**若其数出的必红点与这 6 处不符，如实写进报告**
  （那意味着存在一个我们还不知道的耦合，**比修好一处断言重要**）。
- **(二)** 简报 §2 第 12 行的 `description()` 里已含「只校验 providerId 非空白、不校验 provider 存在」的 ★★ 警告，
  与 §4 的禁令**一致、不矛盾** ⇒ **保留警告原文、不加任何校验**。

*代价若判错*：若 `:476` 那个同值 43 被一并改掉，`catalogCoversImplementation` 会红，
而它的红**指向"handler 少了 12 个"这个假事实**——实现者会去追一个不存在的缺陷；派单里那句陷阱提示就是为了挡这个。

#### ★★ Task 3 关账（M3，2026-09-22，控制器）

**状态：✅ 已关账。** 实现 → 任务评审 → 三条发现**全部修复** → 门禁在**最终字节**上重跑绿。

| 环节 | 结果 |
|---|---|
| 实现提交 | `aba3326`（17 文件 +819/−20，只动 `simos-app`）+ `fab1fa4`（证据+报告，64 文件）+ `2cffab4` |
| 实现者自评 | `DONE_WITH_CONCERNS`（3 条 concern：m2 系 wrong-reason、对账 6 红/7 常量与两张清单都不等、原始那次红未留档） |
| 任务评审 | **判据 A 通过 / 判据 B 通过**，出 **3 条发现**（无一条指向 12 条工具本身） |
| 12 条工具 | 只进 **GM 桶**；桶规模 **12 / 52 / 31 / 55**（EXTERNAL / GM / DECISION_AGENT / EXTERNAL_WITH_GM）；catalog 集合 == 全仓 30 个实现的 `type()` 集合 |

**三条发现的处置（逐条，全部修在关账前）**：

1. **范围声明缺失** —— brief §6(e)② 明令「**照抄进报告**」，而报告 grep `工作台|GUI|界面|看不到|发不出` = **0 命中**。
   **已修**：报告抬头补上逐字照抄的范围声明，并附控制器只读复核的两条事实
   （`webui/` 对 12 个 type 零命中；`modes.js` 的 `isWriteAllowed` 是 fail-closed 白名单）。
2. **工具面注释仍钉死条数** —— brief §6(e)① 的授权（裁决 I + 补丁 L）。**已修 9 处**（`SimosToolSource` 7 + `Shell` 2），
   见下「更正」一条。★ **两条独立证明各自判定语义中性**：文本级（`strip-compare.py`）+ 字节码级
   （控制器另写的 `bytecode-proof.sh`，**方法与证明①不同**，且每轮**先删 `.class` 再重编**、断言非空）。
   两者在 `SimosToolSource` / `Shell` 上都是 `IDENTICAL`；**行号漂移 0**（282/282、777/777）⇒ 比 T8 先例**更强**。
   全部留痕在 `m3-evidence/comment-drift/`（含 `LINE-DRIFT-NOTE.md`，md5 `fdf26dbecabea258077d29fff25269fc`）。
3. **`CatalogTool` 的改动未在报告出现**（grep `CatalogTool|PAYLOAD_HINTS` = 0）——改动本身是 brief §6(f) 授权的，
   问题是**信息不一致**（后来者会拿错基线）。**已修**：报告补 §2.1，含前后字面量与"键集未变"这一连带事实。

★★ **更正本台账上一节的结论（诚实记账）**：上面 §二 Task 3「复核结论 4」写
「残留的「20 条窄写」…**不随 M3 漂移 ⇒ 不改**」——**该结论已被推翻，那 3 处也去掉了数字。**
- **为什么推翻**：不是因为"当时那个数字是错的"（**它当时是准的**），而是因为**规则的对象是"工具面注释里不留钉死条数"本身**——
  只要数字在，**下一条 unit 命令加进来时它必然静默过期**；且当时文件里那句「M2 已把旧计数去掉」会让人以为**已经清干净了**。
  评审发现 2 的搜索串（`条工具` / `9 读` / `窄写`）**逐字包含 `窄写`** ⇒ 这 3 处落在授权范围内。
- **代价**：若判错，则多改了 3 处**本来还算准确**的注释（值层面无损失，见证明①②）。

★★ **连带发现并当场修掉的装置陷阱（本轮最值得记的一条）**：`mut-round.sh` 的 `restore_world()`
**无条件**把 `pristine/` 覆盖回工作树（对 `baseline.md5` 里每个文件），**然后**才断言 md5 == 基线。
而 `SimosToolSource.java` **正在** `baseline.md5` 里 ⇒ **下次跑任一变异轮会**：
① 用改前字节**静默抹掉**这次的 7 处修复；② 紧接着的断言拿"刚被自己盖回去的旧字节"比"旧基线"⇒ **当然相等**
⇒ **装置会打出 `干净世界 OK`**。★ 与"装置自带陈旧状态却报告正常"是同一族。
- **发现方式**：跑 `check-clean.sh` ⇒ `核对 17 个文件，**1 个**与基线不同`（**恰好**是我改的那个，其余 16 个逐字节一致）。
- **修法（机械、无判断）**：存档原 `baseline.md5` → `baseline.md5.pre-comment-fix`（md5 `bc9b1d0c…`）；
  刷新 `pristine/…/SimosToolSource.java`（→ `7b670b87…`）；改 `baseline.md5` 那一行（仍 17 行，新 md5 `25586d32…`）。
- **修后复核**：`check-clean.sh` ⇒ `0 个与基线不同` / **`干净世界: OK`**。
- ★ **旧基线没丢**：`comment-drift/pre-SimosToolSource.java` 与被换掉的 pristine **逐字节相同**（都是 `8ad2cf5c…`，现场核过）。

**门禁（M3 的第 3 次全量门禁；也是改动后字节上的第 1 次）**：`./mvnw clean verify` **rc=0**、
**1449** = `170/369/45/259/179/141/286`（**现场从模块汇总行 `paste -sd+ | bc` 重算**，未引用任何文档现成数字）、
Failures 0 / Errors 0、**8/8** `SUCCESS [`、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、
前端 `[frontend-gate] OK tests=204 pass=204 fail=0`、`BUILD SUCCESS`。
日志 `m3-evidence/logs/clean-verify.attempt3-post-comment-fix.log`（md5 `0d23a12d327ff1061d81dd6aa3b0a687`，713,871 B）。
★ **旁证**：用例总数与**改动前**那次（attempt2）**逐值相同** ⇒ 注释改动确实行为中性。
★ **环境记录**：本轮在 600 s 处被 harness **摘到后台**、**仍 rc=0 跑完**（与 T8 同形：**"被摘后台"本身不致命**）。

**唯一性核查（关账点验，现场实测）**：`m1` 的两个针脚在**当前**字节上**各仍只命中 1 次**
（`^import …SdCreateNationTool;$` = 1、`built.add(new SdCreateNationTool(…));` = 1）⇒ **m1 的守卫通过、仍可重跑**；
且 `grep -l 'Shell.java' mutants/*.py` = **0 命中** ⇒ 我改的另一个文件**不在任何变异体的射程内**。

**M3 遗留（如实登记、不消，供终审与用户）**：
- 报告 §6「我未能核实的」**10 项** + 三条 concern（m2 系 wrong-reason / 对账 6 红与两张清单都不等 / 原始那次红未留档）
  —— 均**原样保留**，未因本次修复而消解。
- ★ **范围声明本身就是一条遗留**：这 12 条在**工作台（GUI）里看不到也发不出**（`webui/` 零命中 + 白名单 fail-closed）
  ⇒ **"全绿"不等于"界面上能用"**。

### Task 5 — M5 通用写收窄评估（**只评估、零代码改动**，控制器内联执行，2026-09-22）

交付物：**`m5-evaluation.md`**（八节；每条事实带 `文件:行`，末节为"我未能核实的"6 项）。
用户裁定 **D-4 = 只评估** ⇒ **本任务不做任何代码改动、不跑门禁、不跑变异**（评估文件自然不动被测字节）。

**为什么控制器自己写而不派单**：`CLAUDE.md` 的口径「**代码量小时，控制器自己读 diff 就是评审**」
——本任务的产出是**取证结论**，不是代码；派一个只读 agent 去复述我已逐条读过的行号，是"评审的体量压过代码"。

**核心实测（全部现场量的，不抄任何文档）**：

| # | 事实 | 量法 |
|---|---|---|
| ① | 工具面上的"通用写"**只有一条**：`simos.command.submit`（`type` 调用方自选）；`simos.advance`/`simos.fork` **命令类型写死** | 逐条读 `CommandSubmitTool`/`AdvanceTool`/`ForkTool` 的 schema 与方法体 |
| ② ★ | **43 个生产命令类型 ↔ 43 条同名窄写工具**（`comm` 实测**双射**） | 扫 `*Handler.java` 的 `type()` 字面量 44 条 − 1 条测试夹具 `map.RandomizeTest`；扫 `write/` 的 `NAME` 46 条 − 3 条通用写 |
| ③ | 唯一构造点 `SimosToolSource.java:158`，**只进 `EXTERNAL` 桶**；`GM`/`DECISION_AGENT` **无** | 读 `addExternalWrites`/`addGmWrites`/`addDecisionAgentWrites` 三处方法体 |
| ④ ★★ | **`Role.EXTERNAL` 在生产代码里没有构造点** ⇒ "收窄"的实际落点 = **现有那个口少一条工具** | `git grep 'new SimosToolSource('` 命中 3 处全在 `Shell.java`，前两处都带 role 实参 |
| ⑤ ★★ | **`POST /api/command`（`GuiServer.java:498` → `:782`）是第二条通用写**：`type` 由请求体给、**14 行方法体内无任何审批调用** | 全文读 `submitReply` 方法体 |
| ⑥ ★ | 「类型 → 工具」这个方向**没有判据**（现有三族判据只钉 类型↔catalog、工具类↔GM 桶、桶→类型） | 逐个读 `SimosToolsTest` 的 5 处相关方法名与断言对象 |
| ⑦ | 四桶规模 **12/52/31/55**（读 9 + 外部写 3 + GM 43 + 决策 22） | 从源码逐条数，**不是算术**、**更不是抄文档** |

**一处自我更正（形态 5 当场生效）**：草稿写「约 40 处 javadoc 引用 `simos.command.submit`」，
**实测为 12 行 / 9 文件**（`git grep -c` 现场计数）⇒ 落笔前改掉。**没写进任何文件**。

★ **权威挂钩（逐字核过 spec 原文，不是引本仓转述）**：`docs/superpowers/specs/2026-09-20-sd-simos-design.md:478`
把外部 MCP 桶写成「**保留现状或另行收敛，列为挂起**」⇒ **D-4 就是兑现这句「另行收敛」**；
`:479`「★ **GM 绝不握通用写**（N11）」**今天已经满足**。

**裁定（控制器，本任务）**：**不替用户选**。三档摆出（不动 / 只收窄工具面 / 两面一起），
并明写**它们的差别不是"严不严"，而是"管哪一面"**。
*代价若判错*：若我把三档写成了"三档都可行"，用户可能选 2 而以为"任意 type 的写入口没了"
——实际 HTTP 面还在。⇒ 故 ⑤ 在评估与 `pending-rulings.md` §五 里都**加粗前置**，不放在末尾。

★ **本任务未做**（有意，且已在评估 §八 逐条登记）：零 LLM 行为实测（本机无客户端）、
`/api/command` 的调用者范围只核到工作台、`map.RandomizeTest` 未逐行读、spec §八.3 未通读全节。

## 三. ★★ 待用户裁定（控制器**不自裁**；详见 `pending-rulings.md`）

用户 2026-09-22 的指令是「**尽可能把不用判断的做完，提交并推送，然后等我回来判断**」
⇒ 下列**五项**已识别为"需要判断"，**控制器只备料、不落笔**：

1. **D-5 的验证路径**（导入器 tag 改 `nation:`）——四个选项见 `pending-rulings.md` §一。
   *代价若判错*：选错 ⇒ 要么在一张**本机跑不起来**的黄金脚本里写死未实测的期望值
   （违反「不写没实测过的期望输出」），要么把真档复验**永久挂空**。
   ★ 另附**控制器只读实测**的三条连带事实（见**裁决 K 补记**）：导入器**不建 Nation**（悬空）、
   改完后 `sd.CreateNation` 的前置**被打开**、且**写侧从不校验 tag 的 id 与所建国家是否一致**。
2. **D-3 = M4 读口范围**（清单已在 `m4-inventory.md`，9 条缺口；1 条"最高风险"是
   `/api/sd/verdicts` 省略 `as=` 时**连模型输出 payload 一起发**）。
   ★ **「最高」那条经控制器三链逐字复核**：`GuiServer:443-449`（`as=` 可选）→
   `RedactingQueryService:167-169`（无 actor 分支**硬编码 `FULL`**）→ `:203-209`
   （**只有 `FULL` 发 `payload` 与 `meta`**，另两档不发）⇒ 侦察报告的判读**成立**。
   ★★ **控制器新发现（改变整个 D-3 的风险口径，已写入 `pending-rulings.md` §二）**：
   **读工具没有"只给 GM"这个选项**——`SimosToolSource.java:117` 原文「**读工具四桶共享**；写面各自不同」，
   装配式是"四桶公共前缀 + 各自写面"。⇒ 圈中一条读端点 = **凡持有任何口的人都拿到**，
   **含对外的 `EXTERNAL` 口**。⇒ 若用户本意是"只给 GM"，M4 的形态**不是补读工具**，
   而是**给 `Role` 加只挂读工具的子集**——**另一个设计问题、代价大得多**。**控制器未替用户选**，只摆事实。
   ★ 两处既有形状缺陷经控制器逐字复核：`simos.map.hex` 的 MCP 版比 GUI 版**少三个键**
   （`regions[]`/`terrainType`/`edges[]`——**侦察报告写"少两个"，实测是三个**）；
   `simos.map.overview` 的 MCP 版**仍是逐格数组**（`ToolSupport.java:306-314`）⇒ M9 省流只落在 GUI 侧。
3. **creed §五 措辞**：原文写「每加一条**命令/工具** ⇒ 连带 `PAYLOAD_HINTS` + `McpCoverageTest` 双向载荷」，
   逐字读**会误导**（该规矩的对象是**命令类型**；加读工具不产生新命令类型 ⇒ 两张表都不动，M1 实测为**空操作**）。
   **控制器未擅自改 creed**（它是绑定权威、用户文档）。
4. **三处用户可见的域层静默缺口**（M1 recon A/B/C）：通路组 id **大小写变体**、
   `map.SetEdge` **无邻接校验**、`map.UpdateRegion` 的 `meta` 是**整体替换**。
   —— 三者都是**既有**行为、今天经通用写即达，**改它们会牵动作废已关账的变异轮**。
5. **D-4 = 通用写是否收窄**（**评估已出**，见 Task 5 段与 `m5-evaluation.md`）。
   ★ **评估本身就是"非判断工作"**（用户已裁 `D-4 = 只评估`）⇒ 已完成；**要不要动，归用户**。
   ★ 评估里最该被看见的一条：**只收窄工具面 ≠ 收窄写权限**——`POST /api/command`
   是**第二条、且不过审批**的任意 `type` 写入口。**控制器没替用户选**（三档差别是"管哪一面"）。

### 裁决 K（控制器，2026-09-22）：**D-5 从 M3 剥离、升级给用户**

- **裁的是什么**：把 `tools/gsimap_import.py:506` 的 tag 产出从透传 `province.tag` 改成 `nation:<…>`，
  **并连带更新黄金校验与已入库的 `v17levant.json`** —— 这件事**整件**交给用户裁定，
  **不在 M3 动手，也不单独派单**。
- **为什么**（三条，都是**实测**、不是推导）：
  ① 本机**没有导入器输入侧真档**（`find / -xdev` 对 `n0000_map.json` / `*_map_diff.json` /
  `-type d -name nodes` / `*test_integration*` 命中**均为 0**）⇒ `check_v17levant_import.py` 的 `main`
  要求 `len(argv) in (2,3)` 且**源档必填** ⇒ **该脚本在本机连启动都不能**（recon §7.4）。
  ② 因此**无法为新的期望值取证**；而本仓纪律明文禁止「不写没实测过的期望输出」
  （形态 5）⇒ 改了导入器就必然要留下**一个没跑过的期望**。
  ③ 已入库的 `v17levant.json`（4,219,540 B）**本机无法重新生成**（其两级输入同样缺席）。
- ★ 与 spec 的一处**实质分歧**（用户须一并裁）：D-5 文本写 `nation:<名>`，而代码契约是
  `NationTag.tagFor(NationId)` ⇒ **后缀应是 NationId**。本次 252/252 恰好 `name == id` ⇒ 结果同，
  但**契约是 id**（`NationTag.java:6,17-19` + `webui/map.js:2373-2375` 按**字面相等**对应）。
- ★ **连带后果（用户可能没预期）**：改完后富世界 **252 个区域全部**满足 `NationTag.isNationTag`
  ⇒ `RegionDeleteGuard.java:56-57` 让它们**全部变成不可删**；**今天它们是可删的**，
  且**没有任何既有断言会红**（recon §7.6）。
- ★ **K 的补记（同日，控制器只读实测）**——早先标「推断」的两条已升为**实测**，并多出一条**新发现**：
  ① **实测**：`git grep CreateNation -- tools/` **零命中** ⇒ 导入器**不建任何 `Nation` 对象**
  ⇒ 改完后那些 tag 相对 sd 的语义是**悬空的**（`base.nations()` 不含它们）。
  ② **实测**：`CreateNationHandler.java:53-55` 只要求 `homeRegionId` 的区域**带 `nation:` 前缀**
  ⇒ 改完后**建国家的前置被打开**（今天这 252 个区域一个都不带 ⇒ 今天富世界里**建不出任何国家**）。
  ③ ★ **新发现（写侧的对应关系从不校验）**：`NationTag.isNationTag`（`NationTag.java:21-23`）是
  **纯前缀检查**（`tag != null && tag.startsWith("nation:")`），而 `CreateNationHandler` **不校验
  tag 里的 id 是否等于正在建的 `nationId`** ⇒ 拿**任意** nationId 去建，只要区域带前缀就**被接受**。
  「区域属于哪个国家」**只在读侧用**（WebUI 按字面相等高亮）⇒ D-5 之后那个 tag 是**提示、不是绑定**。
  ⇒ 三条都已写入 `pending-rulings.md` §一 供用户裁 D-5 时一并权衡。
- *代价若判错*：若用户本意是"改完就行、复验以后再说"，则拖延了一个**机械改动**的交付；
  若我擅自改了，则会在仓库里留下**一条从未跑过的黄金期望**——那是本仓最贵的教训类型（形态 5），
  且**要等换机器才发现它是错的**。


## 四. 历史遗留**复核**（控制器只读实测，2026-09-22；等待 M3 时的并行工作）

计划 §五 有**两条**历史遗留。第 1 条（`nation:` tag）已升级为 **D-5**（见 §三 与 `pending-rulings.md` §一）。
**第 2 条原文**：「前端 `expectedRevision` 过期 ⇒ **409**（是否已由 `661ac88` 修到"点一次就成"**待复核**）」
—— 本阶段此前**从未碰过**（`git grep --untracked 661ac88` 在本阶段目录下**零命中**），本次复核**收口**。

**结论：✅ 已修，且「点一次就成」这句话现在就有断言钉着。**
- ★ **先看上游自己的说法，不重复劳动**：`661ac88` 的工作区（`.superpowers/sdd/2026-09-22-sd-start-decision-fix/`，
  **另一个计划的工作区，只读**）关账报告 `task-final-report.md` 已把这件事写清，并**主动披露了范围**：
  `:105` 原文——「**「点一次」是 HTTP 层等价物**：§四 用的是**按钮发出的那条 `POST /api/sd/start-decision`**，
  **没跑真浏览器**（本机 Chromium 版本与 Playwright 不匹配，是本仓长期遗留）。前端"409 自动重试一次 +
  成功推进游标"由 `decision-mode.test.cjs` 的 3 条**纯函数/替身**断言覆盖，**未在真浏览器点过**。」
  ⇒ ★ **这是"我验过了 vs 我记得是这样"的正确形态**：上游没把 HTTP 层等价物冒充成"点一次就成"。
- **契约侧实测**（该工作区 `e2e/`）：真发一条过期 `POST` ⇒ `409 {"result":"conflict","current":{…,"revision":11}}`
  ⇒ **服务端在 body 里回 `current.revision`**，前端据此拉游标并重试。
- ★ **今天的字节上复核**（**不引用该报告的数字与文件名，全部当场重取**）：
  - `simos-app/src/test/js/decision-mode.test.cjs:327` **`start-decision-retries-once-after-409-with-the-fresh-head`**；
    `:328` 注释**逐字含「点一次就成」**；`:342` `assert.equal(h.calls.startDecision.length, 2, "409 ⇒ 恰自动重试一次")`；
    `:345` `assert.ok(h.calls.refreshState >= 1, "409 ⇒ 必须重取 head")`；
    另有「重试轮再 409 ⇒ 如实报失败、不得无限循环」一条（`:352` 一带）。
  - 生产侧**四处** 409 分支都在现役前端：`app.js:400`、`timeline.js:604`、`panels.js:1019`、`panels.js:1554`。
- ★ **一处方法学踩坑（自记）**：第一次查时我用 `git grep -n "409" -- '*.js' '*.java' | head -20`，
  输出全被 `.superpowers/**` 的**变异体副本**占满、**现役 `webui/` 的命中被 `head` 截掉** ⇒
  差点得出"前端没有 409 处理"的反向结论。**加固正则的教训又一次成立：命中 0（或命中全是噪声）先怀疑自己的取数方式**。
- ★ **顺带回填计划 §四 M4 行**：原文写「读工具是**三桶**共享」，代码是 **4** 个 `Role` 值
  （`SimosToolSource.java:117` 类内 javadoc 原文「读工具**四桶**共享；写面各自不同」）⇒ 已回填为"四桶"，
  并注明其连带后果（**加读工具 ⇒ 对外的 `EXTERNAL` 口同时拿到**，这才是 M4 的真实代价）。
- **遗留（继承、非本阶段引入）**："真浏览器点过没有 = **没有**"——与本仓 M7/M8/M9 一路记的
  **Chromium revision 1234 ≠ Playwright 1.63/1.64 需要的 1243/1246** 是**同一条**，归该开口项，不重复立账。
- *代价若判错*：若上游报告里的"HTTP 层等价物"其实另有真浏览器证据，则我把"未在真浏览器点过"写重了——
  但**反向写轻**（把没跑过的当跑过了）才是本仓最贵的教训，宁可写重。

---

## 五. ★★ 收尾（控制器内联，2026-09-22）：关账后的文档提交 + 全分支评审

本节记 **M3 关账之后**发生的、不属于任何任务的收尾动作。**全部无生产代码改动。**

### 5.1 提交与推送（`ts/m1`，全程纯快进）

| 提交 | 内容 | 性质 |
|---|---|---|
| `e7e3155` | M3 关账文档（台账 / 待裁定 / 任务报告 / 评审包 / M5 评估 / 计划回填） | 文档（M3 关账提交） |
| `f63e325` | `pending-rulings.md` §六 对账刷新 + **全分支评审包入库** + `CLAUDE.md` 两处更正 | 文档 |
| `84965b9` | 就地更正 `f63e325` 里那句写窄了的 `.superpowers/sdd` 下 `git add` 口径 | 文档 |
| `4aabfb6` | 待裁定清单 §六 推送状态一行改成自持久的写法 | 文档 |

★ **最后一次碰生产代码的提交是 `e7e3155`**——这是本阶段实现面的锚。此后全是文档。
★ 推送**四次**（`7ce2cc1..e7e3155`、`e7e3155..f63e325`、`f63e325..84965b9`、`84965b9..4aabfb6`），
**每次推送前都实测「落后 0 个 + 远端是 HEAD 的祖先」，一次都没 force**。
★（自记：本节初稿把四次写成"三次"，而同一句里就列了四个区间——属本仓反复记账的
**「数字自算错」族**（T8 那次把 1191/1189 抄进三份文档）。**当场改掉，不留。**）
★ **`origin/main` 仍是 `40e19d2`（M1 关账点，也是本分支 merge-base）——没动**；
`main` 要不要跟上**等用户一句话**（先验祖先 ⇒ 也是纯快进）。
★ 全分支评审包 `.superpowers/sdd/2026-09-22-tool-surface/whole-branch-review.txt`（157,569 B）
**已入库**（路径被 `.superpowers/sdd/.gitignore` 命中 ⇒ `git add -f`）。

### 5.2 ★★ `CLAUDE.md` 就地更正两条（都是"原先的记载与实测不符"）

**更正 ①：`.superpowers/sdd/.gitignore` 不是"按机器"的，是"按时刻"的。**
M2/M3 两行原有的那段括注（两处字节相同）写的是"它是某些机器上才有的本地文件，换机器先
`git check-ignore -v` 再决定"。**实测确因**：superpowers 的 `sdd-workspace` 脚本**每次运行都会**
`printf '*\n' > .superpowers/sdd/.gitignore`（该脚本第 39 行；`review-package` 第 28 行调它）
⇒ 本机只要跑过一次 SDD 脚本，这个文件就出现了。
★ **同一会话内实测到前后翻转**：08:32 首次 `git add`（无 `-f`）成功——那会儿文件还没被造出来；
08:33 跑完 `review-package` 后文件出现（mtime 实测 `08:33:33`）；08:36 再 `git add` 即被拒。
★ 该文件**不入库是正常的**（脚本现造，`git log --all` 查无记录）⇒ **"从未入库"不能当作"不存在"的证据**。

**更正 ②：★★ 该目录下 `git add` 的"假失败"。**
`f63e325` 里我把口径写成「已入库的文件照常 `git add`；新增文件先 `git check-ignore -v`，被 ignore 就 `-f`」。
**前半句是推出来的、不是测出来的**——随即被当场打脸（提交 `pending-rulings.md` 时
普通 `git add` 打印 `The following paths are ignored …: .superpowers/sdd/2026-09-22-tool-surface` 且 `rc=1`）。
按纪律「我验过了 vs 我记得是这样」做了**对照实验**（`84965b9` 修）：

1. **已入库的文件 ⇒ 假失败、其实已暂存。**
   `git restore --staged <文件>` ⇒ `git status` 显示 ` M`（未暂存）
   ⇒ 普通 `git add <文件>` ⇒ **`rc=1` + 上面那段告警** ⇒ `git status` 显示 `M `（**已暂存**），
   且 `git show :<路径>` 里确是新内容。★ **`rc=1` 在这里不等于"没暂存"**；
   脚本里 `git add … || exit` **会在已成功的情况下中止**。
2. **新增（未入库）的文件 ⇒ 真被拒**，必须 `-f`（实测即 `whole-branch-review.txt`；
   另测脚本造的 `-plan` 目录里的 `review-*.diff`：dry-run `rc=1`，同样被拒）。
3. **`git check-ignore` 不是 `git add` 行为的预言机**：对**已入库**的文件它返回 `rc=1`（未忽略），
   而 `git add` 照样告警；对**真实 workspace 目录本身**它返回 `rc=1`（未忽略，因内含 162 个入库文件）；
   对**同级无入库内容的 `-plan` 目录**它返回 `rc=0`（忽略）。
4. ★★ **要害：情形 1 与情形 2 打印的是同一段告警、同样 `rc=1`** ⇒ **文案与 rc 都判不出是哪一种**；
   唯一判据是跑完 `git status`，看那行的 `M` 落在**第一列（已暂存）**还是**第二列（未暂存）**。

★ 这一条与本节 5.3 是**同一族**：**把发生了伪装成没发生**（本仓记账最多的族是反向的
「把没搜到伪装成不存在」，这是它的对偶）。

### 5.3 ★★ 新增纪律：SDD 的 workspace 目录名由脚本从**计划文件名**推导，可能**与台账目录不一致**

`sdd-workspace` / `review-package` 取 `basename <plan>.md`（去掉 `.md`）。本阶段计划是
`2026-09-22-tool-surface-plan.md` ⇒ 脚本推导出 `.superpowers/sdd/2026-09-22-tool-surface-plan/`，
**而真正的台账在 `.superpowers/sdd/2026-09-22-tool-surface/`**（无 `-plan` 后缀；那是开工时手工建的）。
**实测**：脚本目录里**只有一个自动生成的 `review-40e19d2..e7e3155.diff`（9.1 MB）**，
`progress.md` 一个字都没有。

★★ **危险在于 SDD 技能自己的明文警告**：找不到台账会被读成"本计划没有台账 ⇒ 从头开始"
⇒ **把已关账的任务全部重派一遍**（技能原文称之为"观察到的最贵的失败"）。
⇒ **接手本阶段前先 `ls .superpowers/sdd/`，台账一律认 `2026-09-22-tool-surface/progress.md`**；
脚本推导出的 `-plan` 目录只是它的副产品、**可以忽略**；
**别据"脚本目录里没有 `progress.md`"判定无台账**。已写进 `CLAUDE.md` 纪律段。

### 5.4 全分支评审（final whole-branch review）

- **范围** `40e19d2..e7e3155`（8 提交）；**基准为何是 `40e19d2`**：实测
  `git merge-base origin/main HEAD` = `40e19d2` = M1 关账点、**已在 `origin/main` 上** ⇒ 天然分叉点
  （M1 本身不在本包范围内）。**不引用任何文档里的现成数字，全部当场量。**
- **评审包** `.superpowers/sdd/2026-09-22-tool-surface/whole-branch-review.txt`
  （**157,569 B**，只含 `simos-app/**` 与 `docs/**`；106 个证据留档文件**已在包内声明为不在评审视野**）。
  ★ 自动生成的 9.1 MB 全量包**不可用**（`review-40e19d2..e7e3155.diff`），故重建了这个聚焦版。
- **模型**：**最强模型（opus）**——SDD 硬要求"终审不在会话默认模型上跑"。
- **派发内容**：评审包路径 + 支撑文档 + **6 条"已知的有意边界，不要当缺陷报"** +
  **5 项独立核查要求**（同源是否真同源 / 桶算术 / 前置即错的真价值 / 测试判别力 / 被"全绿"掩盖的静默失败）
  + 本仓 5 条硬纪律原文（「我验过了」vs「我记得是这样」；「没跑到」≠「没红」；md5 不是 mtime；
  ugrep / `git grep` 的静默假阴性；护栏必须自证）+ 输出契约（每条结论带 `文件:行`，核不过的才报，
  没核实的写"未能核实"）。
- **状态**：已派发、运行中。**结论回来前不追加轮次。**

---

## 六、全分支评审结论裁定（2026-09-22）

**评审对象** `40e19d2..e7e3155`（8 提交），**跑在最强模型（opus）上**。报告 `whole-branch-review-report.md`
（37,142 B）。**结论：可以关账，附 1 条中 / 3 条低**；其 **§二 核查表 5 项全通过**
（同源 / 桶算术 52·31·12·55 / 前置即错 39 处调用点 / 判别力 / 静默失败）。
★ 评审**自己点名了两条它没实测、只是推断**的发现（F1 的"重跑会 VOID"、F4）——**两条都按推断对待，不按结论**。

### 6.1 裁定总表

| # | 级别 | 裁定 |
|---|---|---|
| **F2** | 低 | ✅ **当场修掉**（见 6.5）：Javadoc 写"恰为 30"而同处断言 `hasSize(43)`；实测 handler 数 = **43** ⇒ 注释错、断言对。修法是把**数字从注释里去掉**，不再制造第二个会漂的数 |
| **F1** | 中 | ⚠️ **一半被实测推翻；另一半成立，带裁定挂到关账前**（6.2） |
| **F3** | 低 | ⏸ **挂起，归 `pending-rulings.md` §四 同族**（6.3） |
| **F4** | 低 | ⏸ **挂起，与 F1 的重派生同批**（6.4） |

### 6.2 F1：拆两半——「生成器跑不了」这半**已经被实测推翻**

F1 的判据原话是「M2 的 `regen-mutants.sh` 现在**已经不能跑** / 重跑路径本身是坏的」，
证据是在**工作树**上 `grep -c -F <mark> SimosToolSource.java` 得 0。

**但该生成器不读工作树。** 脚本 `:2` 自己写着"不手改工作树"，`:7-8` 是
`PRI=$EV/mutants/pristine` / `WL=…/whitelist`，**从冻结的 pristine 快照整份派生**，`emit()` 只往 `WL` 写。
当场双侧实测（`git grep --no-index -c -F`，同一串）：

| 文件 | 命中 |
|---|---|
| `m2-evidence/mutants/pristine/SimosToolSource.java` ← **生成器真正读的那份** | **1** |
| `simos-app/src/main/java/…/SimosToolSource.java` ← **评审量的那份** | **0**（M3 去数字 `1e5859e` 把"20 条"删了） |

⇒ 脚本 `:41` 的 `assert src.count(mark) == 1` **通过**。**直接执行**（`WL` 重定向到 `/tmp`，纯 Python）：

```
gen_rc=0   6 个变异体全出（m1/m2/m2b/m3/m4 三条 + m4 两条）
白名单形态：m1:1  m2:1  m2b:1  m3:1  m4:2   文件名 == 目标类名
跑完 git status：只有 F2 那一行 ⇒ 工作树未被碰
```

★ **这是形态 5 的又一实例**：评审把"我想量的文件"当成了"被测物真正读的文件"——
而它**自己已把这条标成没跑过的推断**。**推断被写成结论，就是这条形态的入口。**

**另一半（证据字节陈旧、没重派生）成立。裁定：不现在重跑，挂到"本轮改动全部定稿之后、关账之前"**：

1. **现在跑，当场就会被作废**——本轮还有 **4 项待裁**（D-5 / M4 读口 / D-4 通用写 / creed §五 措辞），
   每项都会再改文件。这与 F1 自己的诉求（证据要落在最终字节上）**直接冲突**。
2. **成本是整轮全价**：本机 `nproc=2`，一轮 = 一次全量 `clean verify`（本树实测 **991 s**、**压在 Bash 600 s 线之上**，
   本阶段已有 3 杀 1 成的先例）⇒ 不是"便宜的尝试"。
3. **旧变异体是旧快照**——生成器从 pristine **整份**派生，整份套用会**回退 M2/M3 之后的改动**
   （T10 先例：`t4m9` 只重放**语义改动**）。⇒ 重派生要**先重造 pristine**，不是直接重跑脚本。

⇒ 记 **F1-L1（带裁定的遗留）**：*触发条件 = 那 4 项裁定全部落地、文件不再变动之后*；
届时按 T10 口径逐体重放语义改动 + 重造 pristine + 重跑 + 逐条记被杀理由。

### 6.3 F3：`AddStageHandler` 非首阶段静默忽略 `combatStateId`/`hex`

`AddStageHandler.java:65-70` 对非首阶段**提前返回 `Applied(...)`**，其后才读那两个字段 ⇒ **被静默忽略**。
散文披露在 3 处（`SdAddCombatStageTool.java:11-12`、`:34`、`CatalogTool.java:75`），**没有任何断言**。

**裁定：挂起，归 `pending-rulings.md` §四「用户可见的域层静默缺口」同族（A/B/C + D~H）**：
- 三个选项里只有 ①（显式拒绝）是**真的修**，而它会是**本阶段第一次改领域层**
  （`git diff --stat 40e19d2..e7e3155 -- simos-sd/` 为空）⇒ 按**裁定 42** 连带作废并重跑 sd 的变异轮；
- ②（加断言钉住当前行为）等于把"静默"**写进护栏**，与"前置即错"的 creed 相抵 ⇒ **不能由控制器单方面选**；
- 它和你手上那批 A/B/C 是**同一个决定**（"域层这些既有的静默行为，改还是不改"）⇒ 一次裁完比裁两次省。

### 6.4 F4：决策人桶只有下界

`SimosToolsTest.java:710-718` 只对 **GM 桶**做磁盘↔工具面双向核对；决策人桶只有**下界**
（`:500` 的 `containsAll(UNIT_WRITE_NAMES)`，常量手抄）⇒ 将来第 N+1 条 unit 窄写**可以静默只进 GM 桶**。
★ 评审自标：**静态推断、无变异轮**。

**裁定：挂起，与 6.2 的重派生同批**——**「护栏必须自证」**：补这条判据必须**自带一个变异轮**（否则是装饰），
而它要改的 `SimosToolsTest.java` 正是 F1 点名要重派生的文件 ⇒ **一批做完，免得做两遍**。

### 6.5 F2 的改动 = 纯注释，按 M8/T8 先例**不作废旧证据**

| 项 | 值（**当场重算**，非引用文档） |
|---|---|
| 改前 = `git show HEAD:<path>` | md5 **`205ec3f71bacb042dc9ddca36e3026db`** |
| 改后 = 工作树 | md5 **`ace275b00c183e6b2fa787cb81fe7088`** |
| 差异 | **1 行**，落在 `/** … */` Javadoc 块内 |

★ **改前 md5 与评审报告 §5.1 记录的值逐字相同** —— 两条独立路径得到同一个数（不是互抄）。

**证明**：`m2-evidence/mutants/strip-compare.py`（**已入库**的状态机式注释剥离器；字符串/字符字面量
**单独提取、各自比对** ⇒ 字面量里的空白改动也躲不过；**输入为空即断言失败**；**两侧原始 md5 相同即拒绝**
——"这个比对证明不了任何事"）。当场跑：

```
A 剥注释后 code   = 35519 字节, md5=440cf30ce3b25164c62e18332d63f7bc
B 剥注释后 code   = 35519 字节, md5=440cf30ce3b25164c62e18332d63f7bc   ← 相等
A 字面量合计      = 11809 字节, md5=3352e40fa1546d7969ea1fcdfaddbc2c
B 字面量合计      = 11809 字节, md5=3352e40fa1546d7969ea1fcdfaddbc2c   ← 相等
VERDICT: IDENTICAL
```
**装置自身有牙**：`strip-compare-selftest.py` 当场 **4/4 条符合期望** ⇒ 不是"比对器恒绿"。

⇒ **LINE-DRIFT-NOTE（M8/T8 先例）**：M1/M2/M3 的变异轮证据**不因本次改动作废**。
★ 改前字节的留档**不靠 `/tmp`**（易失）——`git show HEAD:<path>` 本身就是可复现的留档，上面那个 md5 即锚。

### 6.6 裁定：门禁**不在本次提交上重跑**；本次提交是**存档点**，不是关账轮

**理由与 6.2 同一条**：本树最后一次绿轮在 `e7e3155`，此后**全是文档**
（`f63e325`/`84965b9`/`4aabfb6`/`5252dbd`）**加这一行 Javadoc**；而本轮那 **4 项待裁**每项都会再改文件
⇒ **现在跑，当场就会被作废**（这正是 F1 的诉求本身）。
⇒ **门禁与 F1 的重派生同批**，落在**关账前、最后一次改动之后**。
★ 因此**不宣称"在新字节上绿过"**——`pending-rulings.md` §六 的推送状态行照此写。

★ 本机 `nproc` **当场实测 = 2**（**不是** SDSimos 行里记的 8）⇒ 全量门禁仍是 **991 s 量级、压在 Bash 600 s 线之上**，
"被杀既不是红也不是绿、必须记第几次尝试"那套口径**依然适用**。
