# 2026-10-23 全模块日志调用点覆盖：设计与施工计划（L1~L4）

> **需求来源**：
> ① 用户 2026-10-04 裁定（AGENTS §一.9）：「先把经济系统全量 Log 化……其他模块没有加 Log，
>    这次调试完记得加」；§一.9 原文把这件事写成「**待办（不是可选）**」。
> ② 用户 2026-10-23 指示：工程 / 纪律 / 运维先做，**尤其是各个模块的 Log**——
>    「没有 Log，怎么能排查各个模块的实际执行」。
> ③ 用户 2026-10-09 裁定：通用 event 机制收口 `simos-util`（`LogEvent`/`LogLevel`/`EventLog`/`LogChannel`）。
>
> **执行纪律**：AGENTS §一.5 / §一.10 / §三.0 —— 一个批次一个写代码代理，只写生产代码、只过「编译」、
> 不写/不跑测试、不 commit；测试统一留到最后由**单独的测试代理**做（L4）。
>
> **本文是 L1~L4 派单的必读依据**：每个 Agent 的任务书必须引用本文的对应小节，并在报告里回报偏差。

---

## 1. 目标 / 非目标

### 1.1 目标（每一条都能对照检查）

1. **每个模块的「实际执行」可事后从日志读出**：这一轮/这一阶段发生了什么（INFO）、为什么（DEBUG）、
   逐笔是什么（TRACE）。
2. **命令面全覆盖**：每个 `*Handler.java` 至少 1 条成功/结果日志 + 每个具名拒绝路径 1 条 DEBUG 理由。
3. **编排面全覆盖**：命令批、时间推进、检查点、工具调用、审批、决策回合都有 START/END 或结果行。
4. **门面外 `LoggerFactory` 归零**：遗留直接建 logger 的文件迁移到 `XxxLog` 门面（门面是 logger 名唯一拼写点）。
5. **开关齐全**：`log4j2.xml` 的 `-Dsimos.<module>.logLevel` / `-Dsimos.<module>.traceLevel` 覆盖所有有日志的模块。
6. **零行为影响**：不新增/修改任何状态组件、命令、公式、数值；既有测试与探针读数逐值不变。

### 1.2 非目标（本批不做）

- 不做日志采集/聚合/审计基础设施（ELK、文件落盘、日志轮转策略）。
- 不改 `log4j2.xml` 的输出格式与既有 logger 名。
- 不重做经济模块（`simos-economy`）已完成的覆盖，只补实测缺口。
- 不新增领域状态、不写 Codec/ChangeSet/迁移逻辑，不碰旧档兼容。
- 不在本批写/改测试（L4 统一做）。

---

## 2. 现状（2026-10-23 回代码核；计数为 `git grep` 行级、只算 `src/main/java`）

**通用机制（已存在，不改）**：`simos-util` 的 `io.mosire.simos.util.log`
`LogEvent` / `LogLevel` / `EventLog` / `LogChannel`；`EventLog.kv(...)` 是全仓唯一字段拼接实现；
`EventLog.channel(XxxLog.category()).info(LogEvent.of("EVENT", ...))` 是推荐发射形态。

| 模块 | 门面（分类） | 现有发射 INFO/DEBUG/TRACE | 有日志的 main 文件 / 总 | handler 有日志 / 总数 | 门面外 `LoggerFactory` 文件数 |
|---|---|---|---|---|---|
| `simos-util` | **无**（只有通用机制） | 0 / 0 / 0 | 0 / — | — | 0 |
| `simos-map` | `MapLog`（edit/generate/trace） | 12 / 0 / 3 | — / — | **0 / 11** | 0 |
| `simos-social` | `SocialLog`（6 类） | 27 / 12 / 4 | — / — | 3 / 19 | 0 |
| `simos-unit` | `UnitLog`（household/trace） | 1 / 0 / 1 | — / — | **1 / 33** | 0 |
| `simos-sd` | `SdLog`（decision/nation/combat/diplomacy/time/trace） | 30 / 2 / 3 | — / — | 16 / 28 | 0 |
| `simos-actor` | `ActorLog`（account/seed/trace） | 8 / 1 / 6 | — / — | 6 / 7 | 0 |
| `simos-army` | `ArmyLog`（combat/trace） | **3 / 0 / 0** | 3 / 21 | 3 / 3（仅 3 条 INFO） | 0 |
| `simos-gov` | `GovLog`（daily/demand/trace） | 1 / 2 / 3 | 2 / 10 | 该模块无 `*Handler` | 0 |
| `simos-calendar` | `CalendarLog`（clock/season/trace） | **0 / 2 / 0** | 2 / 21 | 该模块无 `*Handler` | 0 |
| `simos-core` | `CoreLog`（command/advance/store/life/trace） | 14 / 14 / 2 | 6 / 28 | — | **4**（`CoreSimos`、`TimeAdvance`、`CommandBus`、`CheckpointStore`） |
| `simos-app` | `AppLog`（shell/tool/time/decision/trace） | 48 / 22 / 8（+旧 logger 的 23 warn / 334 error） | 111 / 286 | — | **12** |
| `simos-economy`（参考） | `EconomyLog`（settlement/market/debt/migration/organization/entry/population/trace） | 33 / 37 / 24 | 23 / 146 | — | 0 |

**未开日志的 `LoggerFactory` 文件清单（L1 的迁移目标）**
- `simos-core`（4）：`CoreSimos.java`、`advance/TimeAdvance.java`、`command/CommandBus.java`、`store/CheckpointStore.java`
- `simos-app`（12）：`Shell.java`、`ShellMain.java`、`decision/DecisionAgentRunner.java`、`decision/DecisionAgentService.java`、
  `decision/NationOpeningSnapshot.java`、`gui/GuiServer.java`、`household/HouseholdEconomyProjection.java`、
  `household/HouseholdUnitConsistency.java`、`llm/AgentLibLlmConfig.java`、`llm/LlmProviderResolver.java`、
  `llm/SimosApiKeySource.java`、`time/PopulationEconomyTimeParticipant.java`

**开关现状**：`simos-app/src/main/resources/log4j2.xml` 已为 economy/social/unit/map/sd/actor/gov/army/calendar/core/app
11 个模块提供 `logLevel` + `traceLevel`（默认 INFO）；**没有 `simos.util.*`**。

**已有测试形态（L4 照此扩展）**：`simos-app/src/test/.../logging/SocialLoggingTest.java`、`UnitHouseholdLoggingTest.java`、
`simos-core/src/test/.../command/CommandBusLoggingTest.java`（ListAppender 抓行 + 断言事件名/字段/开关）。

**已确认的主要缺口（按模块，详见 §6）**：map 的 11 个 handler 全无日志；unit 33 个 handler 只有 1 个有；
social 19 个 handler 只有 3 个有；army 只有 3 条 INFO、无 DEBUG/TRACE；calendar 只有 2 条 DEBUG；
core/app 的 16 个文件仍在门面外自建 logger；`SettlementGenerator`、`UnitTimeParticipant`、`UnitMoves`、
`MapCodec`、`SocialCodec`、`UnitCodec` 等执行面整条无日志。

---

## 3. 目标状态（逐条可对照）

| # | 现状 | 目标 |
|---|---|---|
| 1 | map/unit/social 的 handler 大多零日志 | 每个 handler：成功 1 条（事件名 + 具名计数）、每个具名拒绝 1 条 DEBUG（reason 档 + 关键 id） |
| 2 | 时间参与者/结算阶段无 START/END | 每个时间参与者/结算阶段：进入 INFO（范围/计数）、结束 INFO（净结果）、关键判据 DEBUG |
| 3 | 逐笔写口不可对账 | 逐笔事实走 TRACE（转移/成交/移动/出生死亡/战斗损失），默认关 |
| 4 | core/app 16 个文件自建 logger | 全部迁移到 `CoreLog`/`AppLog` 门面；门面缺分类时补分类（新分类挂模块根 logger 继承开关）；**被本批触碰的旧日志行按新规范重写**（用户 2026-10-23 裁定） |
| 5 | `simos.util` 无开关 | **默认不建门面**（见 §10 待裁定）；util 机制的可观测性由调用方模块的事件覆盖 |
| 6 | 日志缺统一字段约定 | 固定字段：`event`、`reason`、`day/tick`、`from/to`、`count`；键小驼峰、值 `String.valueOf` |

---

## 4. 接口与契约

### 4.1 发射形态（统一走 util 通道；被本批触碰的文件一律用 ①）

```java
// ① 标准形态（被本批触碰的文件一律改成本形态）
EventLog.channel(SdLog.decision()).info(LogEvent.of("DECISION_TURN_START", "day", day, "dm", dmId));
// ② 既有形态（未触碰的文件可暂时保留；它也经 XxxLog.kv → util，不是旁路）
private static final Logger LOG = CoreLog.command();
LOG.info("event=COMMAND_BATCH_EXECUTED commands={} modules={}", pending.size(), modules.size());
```

**「统一走 util」的准确边界**（用户 2026-10-23 问，编码于此，避免后代误读）：
- ✅ 通用事件机制（`LogEvent`/`LogLevel`/`EventLog`/`LogChannel`）只在 `simos-util`，11 个模块门面的 `kv()` 已全部 `return EventLog.kv(...)`。
- ✅ 本批把「门面外 `LoggerFactory`」清零，并把触碰到的发射点统一为 `EventLog.channel(...)` 形态。
- ❌ **logger 名不收进 util**：`-Dsimos.<module>.logLevel/traceLevel` 按模块名挂载，util 一旦持有模块名/事件名就违反 2026-10-09 用户裁定（util 只放通用机制、零领域知识）。⇒ 门面继续是 logger 名的唯一拼写点。

### 4.2 级别语义（照 AGENTS §一.9，不得自行加档）

- **INFO**：生命周期与「这一轮发生了什么」——进入/结束、写口成功、具名计数（`count`/`rows`/`applied`）。
- **DEBUG**：关键判据与「为什么」——池子读数、额度、开闭市理由、**具名拒绝的 reason 档**、跳过原因。
- **TRACE**：逐笔明细——每一笔转移/每个成交或未成交槽/每次移动/逐户出生死亡/每条战斗损失。
- 不使用 WARN/ERROR 表达业务拒绝（拒绝是正常结果，走 DEBUG）；异常/装配故障可用 WARN/ERROR，但**不得打印载荷明文**。

### 4.3 硬纪律

1. 日志只读：**不得**写状态、不得改公式、不得改流程分支（`if (log.isDebugEnabled())` 只包日志本身）。
2. **不写状态、密钥、载荷明文、JSON 原文、模型/用户文本**；`Exception.getMessage()` 含载荷时不得整段照抄。
3. 日志失败不得影响结算；热路径（逐笔 TRACE、昂贵 kv 拼接）前用 `isTraceEnabled()/isDebugEnabled()` 守卫。
4. 门面是 logger 名的唯一拼写点：新代码不得 `LoggerFactory.getLogger("...")`。
5. 事件名 `SCREAMING_SNAKE_CASE`，模块内唯一；新增事件名清单必须写进实现 Agent 的报告（供 L4 断言）。
6. 与 core 既有批级行**不重复**：批级事实（`COMMAND_BATCH_SUBMIT/EXECUTED/REJECTED`）留在 `CommandBus`；
   handler 只记「本条命令的业务结果」。

---

## 5. 数据流与次序（谁在什么时候记什么）

```
工具/MCP 调用 ── app.tool：记「谁在什么调用者下发起哪个工具」（INFO/DEBUG；拒绝 DEBUG）
        │
        ▼
core.CommandBus ── 批级：SUBMIT(DEBUG) / EXECUTED(DEBUG) / REJECTED(INFO)      ← 已有，不动
        │
        ▼
领域 handler ── 业务结果 INFO（写口成功 + 具名计数）；具名拒绝 DEBUG(reason)     ← 本批新增
        │
        ▼
Codec/ChangeSet.apply ── 施加入口 DEBUG（组件 changed 计数）                      ← 本批新增
        │
        ▼
时间参与者/结算阶段 ── START(INFO) → 阶段判据(DEBUG) → END(INFO) → 逐笔(TRACE)    ← 本批新增
```

---

## 6. 每模块目标面（实现 Agent 的覆盖清单）

| 模块 | 必补面（按优先级） |
|---|---|
| `simos-core` | ① 4 个门面外文件迁移到 `CoreLog`；② `TimeAdvance` 推进 START/END + 参与者顺序/计数；③ `CheckpointStore` 读写/失败 DEBUG；④ `CoreSimos` 装配 INFO |
| `simos-app` | ① 12 个门面外文件迁移到 `AppLog`；② `Shell` 启动/推进/命令路由 INFO；③ 工具调用面（发起/拒绝/结果）DEBUG；④ 审批链（pending/approved/denied/timeout）INFO/DEBUG；⑤ 决策回合（`DecisionAgentRunner`/`DecisionTurnFinalizer`）START/END INFO + 逐 call DEBUG；⑥ `PopulationEconomyTimeParticipant` 日循环阶段 |
| `simos-map` | ① 11 个 handler 成功/拒绝；② `MapPayloads` 字段级拒绝 DEBUG；③ `RegionOperations`/`EdgeOperations`/`TerrainOperations`/`PathwayGroupOperations` 具名拒绝；④ `MapCodec` 编解码/迁移/施加；⑤ `MapResolver` 空候选；⑥ `MapGenerator` 阶段读数（脊线/水陆/地形分布） |
| `simos-social` | ① 剩余 16 个 handler；② `SettlementGenerator` 6 步 START/END + 中间池子；③ `MovePopulationLots` 整批汇总 + 4 条具名拒；④ `SeedGroups` 覆盖分支；⑤ `SocialCodec.apply`；⑥ `HouseholdBook.settleVitalEventsResult` 逐户余数池 DEBUG |
| `simos-unit` | ① 剩余 32 个 handler；② `UnitTimeParticipant` 每 tick 汇总 + 逐单位判据；③ `UnitMoves` NEED_REPLAN/预算 TRACE；④ `UnitOperations` 生命周期写口（create/disband/编制/战损/路线/军俸）；⑤ `PathFinder`/`PlanRoute*` 结果与拒绝；⑥ `UnitCodec`/`UnitResolver` |
| `simos-sd` | ① 剩余 12 个 handler；② `RunDecision`/`AdjudicateTick` 面；③ 决策包 propose/submit/decide 生命周期；④ 战斗/外交/上报的既有行补齐计数与拒绝理由 |
| `simos-actor` | ① 剩余 1 个 handler；② 账户/库存写口（本模块 8/24 文件有日志，先读现有再补缺口） |
| `simos-army` | ① 战斗记录/结局/损失写口 INFO + 逐条 TRACE（现仅 3 条 INFO） |
| `simos-gov` | ① `GovDaily`/需求结算面（GovLog 分类现成：daily/demand/trace） |
| `simos-calendar` | ① `CalendarClock`/季节换算的配置与边界事件（纯计算模块，事件不必多，但配置漂移要可查） |
| `simos-util` | **本批不动**（见 §10）；由调用方覆盖 |

---

## 7. 批次与文件所有权

> 一次只允许一个**写代码**的办事 Agent 在跑（AGENTS §一.10）。每批只做一批，控制方审查后再派下一批。

| 批次 | 内容 | 文件范围（ownership） | 禁止碰 |
|---|---|---|---|
| **L1** | core + app 编排层：迁移 16 个门面外 `LoggerFactory` + 补 §6 的 core/app 面 | `simos-core/src/main/**`、`simos-app/src/main/**`（含 `log4j2.xml`） | 其他模块 `src/main`、所有 `src/test`、docs、AGENTS.md |
| **L2** | 领域写入面：map + social + unit + sd 的 handler / 时间参与者 / codec | 上述四模块 `src/main/**` | core/app、其他模块、测试、docs |
| **L3** | 其余领域：actor + army + gov + calendar（+ economy 实测缺口） | 对应模块 `src/main/**` | L1/L2 已改文件（除必要的门面分类补充）、测试、docs |
| **L4** | **测试代理**：按 §9 判据补/扩 logging 测试 + 关键项变异自证 + 全仓 `clean verify` | 各模块 `src/test/**`、`simos-app/src/test/js/**`（如需） | 生产代码（发现实现缺日志时报回控制方，不自行改） |

每个写代码批次的固定动作（§一.5/§三.0）：

1. 只写生产代码，写到「编译过」：`tools/mvn-lock.sh -q spotless:apply` +
   `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests compile`（跑前 `pgrep -af "classworlds.launcher|surefirebooter"`，
   AGENTS §一.1：有别的 Maven 在跑就等）。
2. **不写/不改任何测试文件；不跑 `test`/`verify`；不 `git commit`。**
2.5. **可以（也只允许）「只读」既有 logging 测试**：`simos-app/src/test/.../logging/SocialLoggingTest.java`、
   `UnitHouseholdLoggingTest.java`、`simos-core/src/test/.../command/CommandBusLoggingTest.java`。
   重写旧日志行时尽量保留这些测试断言的事件名/字段；**若必须改动会让既有断言失效**，
   逐条登记进报告「会让既有测试断言失效的改动」一节（交 L4 更新断言，写码 Agent 不得改测试）。
3. 报告必须包含：改动文件清单、编译结果、**新增事件名清单**（给 L4）、
   **会让既有测试断言失效的改动清单**、**会改变数值行为的清单**（本批应为「无」）、
   与本文不一致处及原因、**未做/未验证项**。

---

## 8. 版本 / 激活 / 迁移 / 回滚

- **无版本位、无状态组件、无 Codec/ChangeSet 变更** ⇒ 不涉及旧档迁移、不涉及世界重建。
- 激活：`-Dsimos.<module>.logLevel=INFO|DEBUG|TRACE` 与 `-Dsimos.<module>.traceLevel=...`（默认 INFO）；
  新增门面分类必须挂到已有模块根 logger 下，随开关继承，不新增独立开关（util 例外见 §10）。
- 回滚：按批次 `git revert`（或重跑上一提交）；日志不影响状态，回滚零数据成本。
- 部署：只影响 `simos-app` 的 shaded jar 重建；重建前先停服务（AGENTS §二）。

---

## 9. 验收判据与证据（L4 执行；控制方终审）

1. **覆盖率（可脚本核）**：
   - `每个 *Handler.java ≥ 1 处日志调用`（`git grep -lE '\.(info|debug|trace|warn|error)\(' -- <file>`）；
   - 每个模块的 handler 覆盖率 = 100%（`simos-gov` 等无 handler 的模块按各自执行面等价判据）；
   - 门面外 `LoggerFactory`：全仓 `src/main` = 0（门面自身除外；`.superpowers/**` 里的历史备份不算）。
   - **形态统一**：本批触碰过的文件里旧式 `LOG.info("event=…` / `LOG.debug("event=…` 计数 = 0，
     一律为 `EventLog.channel(...)` + `LogEvent.of(...)`。
2. **三档存在性**：每个模块 ≥ 1 条 INFO（生命周期）、≥ 1 条 DEBUG（判据/拒绝）；TRACE 至少覆盖一个逐笔写口
   （map/social/unit/sd/actor/army/economy 各点名一个）。
3. **开关有效性**：以 `-Dsimos.<module>.traceLevel=TRACE` 重跑，逐笔行出现；恢复 INFO 后消失（照 `SocialLoggingTest` 形态）。
4. **零行为影响**：非真 LLM 全量测试 + 既有 logging 测试全绿；经济探针（`SevenHexFullChain3650Test` 等）
   读数与改前逐值一致（md5 或逐值对拍）。
5. **可诊断性抽检（每个模块 1 个真实场景）**：用日志回答一个「为什么没发生」：
   - unit：某 tick 某单位为什么没到（预算/边费/重规划）；
   - map：某条命令为什么被拒（字段/域规则）；
   - social：某户为什么没出生（余数池/率）；
   - sd：某个决策包为什么没被批准（状态/越权）；
   - app：某个工具调用为什么被拒（审批/权限/scope）；
   - core：某个批为什么整批拒绝。
6. **负判据**：全仓日志语句不得打印密钥/载荷明文（扫 `apiKey|password|secret|payload` 出现处逐一判定）；
   日志不得出现在 `ChangeSet`/`apply` 之前产生副作用的路径上（静态审计调用点）。
7. **证据形态**：surefire 报告（mtime 落在本轮）+ 抽取的真实日志行样例（每模块 ≥ 3 行，含 INFO/DEBUG/TRACE）
   + 覆盖率统计命令与输出。

---

## 10. 已知缺口 / 风险 / 待裁定

1. **`simos-util` 是否新建 `UtilLog` 门面**：**待用户裁定**。本文默认**不建**——
   util 是机制层（AGENTS §一.9 的门面清单不含 util），其 `FieldDelta`/`InMemoryInfoSystem`/`ResolverRegistry`/
   `FacetRegistry` 由调用方模块的事件覆盖；若要 util 自记，需要新增 `-Dsimos.util.*` 开关，另开裁定。
2. **热路径成本**：`FieldDelta.diff`、`InMemoryInfoSystem.put` 是每 tick 高频口，本批**不逐次记**，
   只在调用方模块的阶段汇总里体现；若后续要开，须先量成本。
3. **重复行风险**：sd（16/28）、actor（6/7）、army（3 条）、app（111 文件）已有部分日志——
   L2/L3 必须先读现有行再补，禁止同一事实两处记。
4. **日志量**：逐笔 TRACE 默认关；不新增独立开关，避免开关爆炸。
5. **重写旧行的连带风险**（用户 2026-10-23 裁定「顺便按新规范重写旧行」）：
   既有 3 个 logging 测试（`SocialLoggingTest` / `UnitHouseholdLoggingTest` / `CommandBusLoggingTest`）会断言
   事件名与字段；写码 Agent 只读测试、不改测试，须把「会让断言失效的改动」逐条登记，由 L4 统一更新断言。
   重写时**级别只允许按 §4.2 规范调整**，不得借机改语义或删日志。
5. **文档同步**：L1~L4 完成后，控制方更新 `AGENTS.md` §一.9（把「待办」改为「已完成 + 剩余缺口」）
   并追加 `.superpowers/sdd/` 台账；本文件保持不动，只由新文档追加更正。
