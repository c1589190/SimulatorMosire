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
| 1 | map/unit/social 的 handler 大多零日志 | 每个 handler：成功 1 条（事件名 + 具名计数）、每个具名拒绝 1 条 **INFO**（reason 档 + 关键 id；用户 2026-10-23：「被拒绝肯定走 INFO」） |
| 2 | 时间参与者/结算阶段无 START/END | 每个时间参与者/结算阶段：进入 INFO（范围/计数）、结束 INFO（净结果）、关键判据 DEBUG |
| 3 | 逐笔写口不可对账 | 逐笔事实走 TRACE（转移/成交/移动/出生死亡/战斗损失），默认关 |
| 4 | core/app 16 个文件自建 logger | 全部迁移到 `CoreLog`/`AppLog` 门面；门面缺分类时补分类（新分类挂模块根 logger 继承开关）；**被本批触碰的旧日志行按新规范重写**（用户 2026-10-23 裁定） |
| 5 | `simos.util` 无开关 | **默认不建门面**（见 §10 待裁定）；util 机制的可观测性由调用方模块的事件覆盖 |
| 6 | 日志缺统一字段约定 | 固定字段：`event`、`origin`、`originKind`、`reason`、`day/tick`、`from/to`、`count`；键小驼峰、值 `String.valueOf` |
| 7 | 算法日志与交互日志混在一起、无法按触发来源筛 | 每条事件必带 `origin`（模块内 id）+ `originKind`（`tick/interaction/system`）；各模块自维护来源表（§4.4） |

---

## 4. 接口与契约

### 4.1 发射形态（统一走 util 通道；被本批触碰的文件一律用 ①）

```java
// ① 标准形态（被本批触碰的文件一律改成本形态；origin 见 §4.4）
EventLog.channel(SdLog.decision()).info(
    LogEvent.of("DECISION_TURN_START", SdLogSource.DECISION_TURN, "day", day, "dm", dmId));
// ② 既有形态（未触碰的文件可暂时保留；它也经 XxxLog.kv → util，不是旁路）
private static final Logger LOG = CoreLog.command();
LOG.info("event=COMMAND_BATCH_EXECUTED commands={} modules={}", pending.size(), modules.size());
```

**「统一走 util」的准确边界**（用户 2026-10-23 问，编码于此，避免后代误读）：
- ✅ 通用事件机制（`LogEvent`/`LogLevel`/`EventLog`/`LogChannel`）只在 `simos-util`，11 个模块门面的 `kv()` 已全部 `return EventLog.kv(...)`。
- ✅ 本批把「门面外 `LoggerFactory`」清零，并把触碰到的发射点统一为 `EventLog.channel(...)` 形态。
- ❌ **logger 名不收进 util**：`-Dsimos.<module>.logLevel/traceLevel` 按模块名挂载，util 一旦持有模块名/事件名就违反 2026-10-09 用户裁定（util 只放通用机制、零领域知识）。⇒ 门面继续是 logger 名的唯一拼写点。

### 4.2 级别语义（照 AGENTS §一.9，不得自行加档）

- **INFO**：生命周期与「这一轮发生了什么」——进入/结束、写口成功、具名计数，**以及一切「业务拒绝」**
  （用户 2026-10-23：「被拒绝肯定走 INFO 啊，不管啥，被拒绝都是一个必须明显记录的事件」）。
  业务拒绝 = 用户/工具/命令的**请求不合规**（权限不够、参数非法、目标不存在、状态不允许…）。
- **ERROR（不降级）**：**契约违反 / 跨切片一致性故障** —— 例如负人口、Social↔Economy 投影对不上、
  Social 家户键为 null、内部不变量破裂（gov 编制引用不存在的单位）。用户 2026-10-23 裁定：
  **这类保持 ERROR**（"世界账本破裂"不是业务拒绝；判据与出处见 §12 #14）。
- **DEBUG**：关键判据与「为什么」——池子读数、额度、开闭市理由、跳过原因、空结果/不可用这类诊断。
- **TRACE**：逐笔明细——每一笔转移/每个成交或未成交槽/每次移动/逐户出生死亡/每条战斗损失。
- WARN 只用于异常与失败的回退/跳过；**既有 WARN 一律不降级**（用户 2026-10-23：「都不接受降级，继续 WARN」）；
  **不得打印载荷明文**。

### 4.3 硬纪律

1. 日志只读：**不得**写状态、不得改公式、不得改流程分支（`if (log.isDebugEnabled())` 只包日志本身）。
2. **不写状态、密钥、载荷明文、JSON 原文、模型/用户文本**；`Exception.getMessage()` 含载荷时不得整段照抄。
3. 日志失败不得影响结算；热路径（逐笔 TRACE、昂贵 kv 拼接）前用 `isTraceEnabled()/isDebugEnabled()` 守卫。
4. 门面是 logger 名的唯一拼写点：新代码不得 `LoggerFactory.getLogger("...")`。
5. 事件名 `SCREAMING_SNAKE_CASE`，模块内唯一；新增事件名清单必须写进实现 Agent 的报告（供 L4 断言）。
6. 与 core 既有批级行**不重复**：批级事实（`COMMAND_BATCH_SUBMIT/EXECUTED/REJECTED`）留在 `CommandBus`；
   handler 只记「本条命令的业务结果」。
7. **每条事件必带 `origin` 与 `originKind`**（见 §4.4）；缺一个都算没做完。

### 4.4 触发来源：util 定类型架构，各模块自维护来源表（用户 2026-10-23 裁定）

需求：日志要同时能回答「**这是算法算出来的，还是人/LLM 点出来的**」与「**是哪一类机制**」。
按用户裁定：**util 只设类型与字段架构，各模块独立维护自己的来源表（enum + 中文说明）**，不加独立开关、
跨模块粗筛靠 `originKind` 字段。

```java
// ① simos-util：只定义类型与字段架构（零模块知识、零 logger 名、零开关）
public enum LogOriginKind { TICK, INTERACTION, SYSTEM }   // 算法推进 / 人机交互 / 系统
public interface LogOrigin {
  String id();             // 模块内稳定短 id（grep 用）
  String description();    // 中文说明（写进模块来源表）
  LogOriginKind kind();    // 触发粗分类
}

// ② 每个模块自建一张来源表（例：simos-app）
public enum AppLogSource implements LogOrigin {
  SHELL_START  ("shell-start",   "进程启动与装配",              SYSTEM),
  TICK_ADVANCE ("tick-advance",  "时间推进循环/参与者调度",      TICK),
  TOOL_CALL    ("tool-call",     "MCP/GUI 工具调用（含被拒）",   INTERACTION),
  DECISION_TURN("decision-turn", "LLM 决策回合与结算",           INTERACTION),
  APPROVAL     ("approval",      "审批链 pending/approve/deny",  INTERACTION),
  CHECKPOINT   ("checkpoint",    "检查点读写",                   SYSTEM);
}

// ③ 发射与渲染（util 负责拼字段）
EventLog.channel(AppLog.tool()).info(
    LogEvent.of("TOOL_CALL_START", AppLogSource.TOOL_CALL, "tool", name, "caller", callerId));
// → INFO io.mosire.simos.app.tool - event=TOOL_CALL_START origin=tool-call originKind=interaction tool=... caller=...
```

约定：
- 字段名固定 `origin=`（模块内 id）+ `originKind=`（`tick|interaction|system`，小写）；两者**必带**。
- `tick` 类事件必带 `day`；`interaction` 类事件必带身份字段（`caller=` / `tool=` / `dm=` / `approvalId=` 视来源而定）。
- 每个模块的来源表**必须带中文说明**；新增来源 = 改本模块的表 + 发射点，util 不动。
- 决策回合内发生的工具调用：由模块表自行定义（app 用 `DECISION_TURN` + `tool=` 字段表达，或单开一项并在说明里写清）。
- 跨模块粗筛：`grep originKind=tick` 拉算法日志、`grep originKind=interaction` 拉交互日志；细筛再按模块内 id。
- **不加独立开关**（用户 2026-10-23 裁定）：字段过滤就够，开关仍只有各模块的 `logLevel/traceLevel`。

**2026-10-23 用户已裁定的四条（架构复核结论，逐条落进契约）**：

1. **`origin` 描述「工作性质」，不是「谁按的按钮」**：算法/结算/推进计算永远记 `originKind=tick`
   （即使由 GM 手调 `advance` 触发）；入口交互（工具调用、决策回合、审批）在入口那一层另记 `interaction`。
   ⇒ 「关掉交互日志后仍能看算法」成立。
2. **`origin` 用显式参数传进领域层**：core 批执行时按批次来源把 `LogOrigin` 传给 handler；
   各模块自己的时间参与者直接写本模块表项。**不用线程上下文/MDC**（决策并发 `concurrency=6` 会串味）。
   ★★ **2026-10-23 用户复核后修正（本条前半作废）**：**不改 SPI、不让 origin 跨模块传**——
   各模块用自己的来源表记自己的执行；工具/决策/审批等**入口交互层自记来源**；跨模块关联靠 `commandId`/`revision`。
   理由（用户原话）：见 §11 第 14~15 条（APP 只是前端包装/启动项、工具应归各模块管、日志类型各模块自定）。
3. **来源表 id 发布后不改**：要改 = 新增一项 + 旧项在表里标注废弃；否则历史日志 grep 断裂。
4. **util 追加 `LogEvent.of(String name, LogOrigin origin, Object... keyValues)` 的向后兼容 overload**
   （渲染 `origin=`/`originKind=`）；原有 `LogEvent.of(name, kv...)` 语义不变。

**控制方默认（用户未反对，实施前仍可推翻）**：读口/查询只在空候选与具名拒绝时记 DEBUG（不逐次记）；
来源表常规新增由控制方审、语义改标必须上报用户；决策回合内工具调用用 `DECISION_TURN` + `tool=` 字段表达。

---

## 5. 数据流与次序（谁在什么时候记什么）

```
工具/MCP 调用 ── app.tool：origin=tool-call / originKind=interaction（发起/拒绝/结果）
        │
        ▼
core.CommandBus ── 批级：SUBMIT(DEBUG) / EXECUTED(DEBUG) / REJECTED(INFO)      ← 已有，不动
        │
        ▼
领域 handler ── 业务结果 INFO（写口成功 + 具名计数）；具名拒绝 DEBUG(reason)     ← 本批新增
        │                        origin=调用方传入的 id / originKind 随来源
        ▼
Codec/ChangeSet.apply ── 施加入口 DEBUG（组件 changed 计数）                      ← 本批新增
        │
        ▼
时间参与者/结算阶段 ── START(INFO) → 阶段判据(DEBUG) → END(INFO) → 逐笔(TRACE)
        │                        origin=tick-advance 类 / originKind=tick        ← 本批新增
        ▼
LLM 决策回合 ── app.decision：origin=decision-turn / originKind=interaction      ← 本批新增
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
| `simos-util` | **本批只新增类型**：`LogOrigin` / `LogOriginKind`（§4.4）；不加 logger 名、不加开关、不改现有机制 |

---

## 7. 批次与文件所有权

> 一次只允许一个**写代码**的办事 Agent 在跑（AGENTS §一.10）。每批只做一批，控制方审查后再派下一批。

| 批次 | 内容 | 文件范围（ownership） | 禁止碰 |
|---|---|---|---|
| **L1** | ① util 新增类型 `LogOrigin`/`LogOriginKind`；② core + app 各自建来源表（`CoreLogSource`/`AppLogSource`）；③ 迁移 16 个门面外 `LoggerFactory` + 补 §6 的 core/app 面并带 `origin/originKind` | `simos-util/src/main/**`（只新增 §4.4 的类型）、`simos-core/src/main/**`、`simos-app/src/main/**`（含 `log4j2.xml`） | 其他模块 `src/main`、所有 `src/test`、docs、AGENTS.md |
| **L2** | 领域写入面：map + social + unit + sd 的 handler / 时间参与者 / codec；各模块自建来源表并录入 | 上述四模块 `src/main/**` | core/app、util、其他模块、测试、docs |
| **L3** | **一次收干净**（用户 2026-10-23 定）：① actor + army + gov + calendar 各建来源表并覆盖执行面；② app/economy 旧形态残扫（实测 32 文件 / 106 行）；③ economy 已有 94 个发射点全量补 `origin`/`originKind`；④ `sd.ChannelAdmission` 两条具名拒绝补 INFO（用户裁定 A） | `simos-actor/src/main/**`、`simos-army/src/main/**`、`simos-gov/src/main/**`、`simos-calendar/src/main/**`；`simos-app/src/main/**`（**仅**旧形态残扫文件）；`simos-economy/src/main/**`；`simos-sd/.../channel/ChannelAdmission.java`（仅新增日志） | L1/L2 已定稿的门面与来源表结构（除必要）、其余模块、测试、docs |
| **L4** | **测试代理**：按 §9 判据补/扩 logging 测试 + 关键项变异自证 + 全仓 `clean verify` | 各模块 `src/test/**`、`simos-app/src/test/js/**`（如需） | 生产代码（发现实现缺日志时报回控制方，不自行改） |

每个写代码批次的固定动作（§一.5/§三.0）：

1. 只写生产代码，写到「编译过」：`tools/mvn-lock.sh -q spotless:apply` +
   `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests compile`。
   **锁的作用域 = 本仓/同工作树**（`.mvn-lock.d`，用户 2026-10-23 更正：隔壁项目的 Maven 不阻塞本仓构建）。
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
     一律为 `EventLog.channel(...)` + `LogEvent.of(...)`，且**每条事件都带 `origin` + `originKind`**。
   - **来源可筛**：`grep 'originKind=tick'` 能拉到各模块算法/结算日志、`grep 'originKind=interaction'`
     能拉到工具/决策/审批日志；两者不互相混入；每个模块的来源表带中文说明且无空 id。
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

1. **`simos-util` 是否新建 `UtilLog` 门面**：**待用户裁定**。本次已按用户裁定在 util 加了
   `LogOrigin`/`LogOriginKind` **类型**（§4.4），这是类型架构、不是 logger；本批仍**不建** `UtilLog` 门面、
   不加 `-Dsimos.util.*` 开关——util 的 `FieldDelta`/`InMemoryInfoSystem`/`ResolverRegistry`/`FacetRegistry`
   由调用方模块的事件覆盖；若要 util 自记，另开裁定。
2. **热路径成本**：`FieldDelta.diff`、`InMemoryInfoSystem.put` 是每 tick 高频口，本批**不逐次记**，
   只在调用方模块的阶段汇总里体现；若后续要开，须先量成本。
3. **重复行风险**：sd（16/28）、actor（6/7）、army（3 条）、app（111 文件）已有部分日志——
   L2/L3 必须先读现有行再补，禁止同一事实两处记。
4. **日志量**：逐笔 TRACE 默认关；不新增独立开关，避免开关爆炸。
5. **重写旧行的连带风险**（用户 2026-10-23 裁定「顺便按新规范重写旧行」）：
   既有 3 个 logging 测试（`SocialLoggingTest` / `UnitHouseholdLoggingTest` / `CommandBusLoggingTest`）会断言
   事件名与字段；写码 Agent 只读测试、不改测试，须把「会让断言失效的改动」逐条登记，由 L4 统一更新断言。
   重写时**级别只允许按 §4.2 规范调整**，不得借机改语义或删日志。
6. **来源表一致性**：跨模块粗筛依赖每个模块的表把 `kind` 标对（例如把 tick 结算误标成 `interaction`）。
   L4 抽检时按 `originKind=tick` 各模块至少各取一行核对语义；发现标错按文档错/实现错分类处理。
7. **文档同步**：L1~L4 完成后，控制方更新 `AGENTS.md` §一.9（把「待办」改为「已完成 + 剩余缺口」）
   并追加 `.superpowers/sdd/` 台账；本文件保持不动，只由新文档追加更正。

---

## 11. 用户原话附录（派单门槛；按 AGENTS §一.8.1 **逐条原样照录，不改写**）

> ★ 实现子 Agent 若认为本文架构设计与下列原话冲突、或原话有歧义 ⇒ **立即停手上报**，不得自行取舍；
> 控制方必须转问用户。下列以外、控制方自己写的实现建议（如批次划分、验收判据的细节）随时可被用户推翻。

**2026-10-04（经济日志裁定，抄自 `AGENTS.md` §一.9）**
> 「讨论个屁啊，先把经济系统全量Log化，把日志记录给我做全了，并且在项目文档里写明其他模块没有加Log这次调试完记得加」

**2026-10-09（通用 event 机制收口，抄自 `AGENTS.md` §一.9）**
> 「为啥非要让日志模块本身知道具体涉及了什么发生了什么，单纯的 event 完全应该放在 util。」

**2026-10-23（本轮，按时间顺序）**
1. > 「我认为应该先把工程 / 纪律 / 运维给做了，尤其是各个模块的Log，没有Log，怎么能排查各个模块的实际执行？计划一下派个子Agent先做这个吧」
2. > 「欸欸欸，臭毛病能不能改改？调查完不和我确认，直接开始改代码，告诉我你准备怎么改！」
3. > 「然后就是，Log系统我记得最近一次修改里统一走Util的日志路径了，这个能实现吗？」
4. 【选项选择，原话即选项文字】L1 范围 =「core + app 一起做」；旧日志处理 =「顺便按新规范重写旧行」；
   批次门禁 =「只编译，测试最后统一做（按 AGENTS §一.5/§三.0）」
5. > 「我首先，为啥要分L1 L2，L2是干什么的」
6. > 「这里的日志包括算法运行日志、Agent工具调用日志等，有些是tick计算产生的，有些是与用户、llm等主动交互产生的，这能被分清吗？」
7. > 「util模块只设类型字段架构；各个模块自己独立维护一套日志来源表（enum+具体情况说明等等），然后按各个模块的实际情况录入日志，这样可行吗？」
8. 【选项选择】是否加独立开关 =「不要，字段过滤就够」
9. 【选项选择】决策回合内工具调用的 origin =「如第一个回答，各个模块各自维护自己的专属日志类型」
10. 【选项选择】是否保留粗分类 =「接受 kind（tick/interaction/system）」；字段名 =「origin= / originKind=」
11. 【选项选择】架构复核四问的裁定：origin 语义 =「按工作性质」；传递方式 =「显式参数」；
    util API =「接受追加 overload」；来源表 id =「id 发布后不改」
12. > 「往AGENTS.md里面加一条，这种用于派单实现开发计划的文档，里面必须包含我的全部相关原话，子Agent若认为架构设计和我的原话冲突，直接上报，然后你来问我，最大限度确保设计不失真——然后你就按这么做，记录我的原话，顺别再看一遍架构有没有问题需要决策」
13. > 「那是隔壁项目，编译门禁只是用于同一个项目文件！」
    （更正编译门禁的作用域：只用看**本仓**有没有 Maven 在跑；隔壁仓库的不阻塞）
14. > 「app 的 CoreSimos.submit(...)……怎么说呢，APP只是一个管前端包装、程序启动项的模块，理论上各个模块的工具归各个模块管，然后统一复写工具协议，为啥要APP管？」
15. > 「日志类型直接让各个模块自己规定不就完了吗，Util只管记；」
16. > 「被拒绝肯定走INFO啊，不管啥，被拒绝都是一个必须明显记录的事件」
17. > 「都不接受降级，继续WARN」
18. > 「调查一下我的所言」
19. 【选项选择】origin 进 handler =「各模块自记，不改 SPI」；工具下沉 =「记入待办，不塞进日志批次」

**相关用户裁定编号**：`D-xxx` 暂无（本轮日志主题尚未形成原稿编号）；`AGENTS.md` §一.9（2026-10-04 / 2026-10-09）。

---

## 12. 架构复核 7 问：处置结果（2026-10-23 已结案）

> 原问句保留在下方（留痕）；结论已并入 §4.4 契约与 §11 原话附录。**未裁定前不动手**这条已解除。

| # | 问题 | 结论 | 性质 |
|---|---|---|---|
| 1 | `origin` 描述工作性质还是谁触发？ | **按工作性质**：推进计算永远 `tick`，入口交互另记 `interaction` | 用户 2026-10-23 裁定 |
| 2 | `origin` 怎么进领域层？ | **不进去**：不改 SPI / core API；各模块用自己的来源表记自己的执行，工具/决策/审批等入口交互层自记来源；跨模块靠 `commandId`/`revision` 关联 | 用户 2026-10-23 复核裁定（取代"显式参数传进 handler"） |
| 3 | 读口/查询日志范围？ | 空结果/不可用记 DEBUG、不逐次记；**被拒绝一律 INFO**（见 #8） | 控制方默认 + 用户裁定 |
| 4 | util `LogEvent.of` 追加带 `LogOrigin` 的 overload？ | **接受**（向后兼容；原 `of(name, kv...)` 不变） | 用户 2026-10-23 裁定 |
| 5 | 来源表 id 稳定性？ | **发布后不改**（要改 = 新增项 + 旧项标废弃） | 用户 2026-10-23 裁定 |
| 6 | 来源表新增/变更要不要用户审？ | 常规新增控制方审；**语义改标必须上报用户** | 控制方默认（用户未反对） |
| 7 | 决策回合内工具调用的表项？ | app 用 `DECISION_TURN` + `tool=` 字段，不单开一项 | 控制方默认（用户未反对） |
| 8 | 具名拒绝的级别？ | **一律 INFO**（用户原话：「不管啥，被拒绝都是一个必须明显记录的事件」） | 用户 2026-10-23 裁定 |
| 9 | 既有 WARN 降级？ | **不接受**：L1 里的 **6 处** WARN→DEBUG 已全部恢复 WARN（复查又发现 2 处：`OPENING_SNAPSHOT_SKIPPED@NationOpeningSnapshot`、`LLM_CONFIG_MIGRATION_ROW_SKIPPED`） | 用户 2026-10-23 裁定 |
| 11 | 批外发现的"拒绝类 DEBUG"？ | `HOUSEHOLD_STOCK_DEDUCTION_REJECTED`（`time/StockDeductionService`）按"被拒绝一律 INFO"应提级 ⇒ 已并入 **L3 的 app 残扫** | 用户规则外推（L3 执行） |
| 12 | `sd.ChannelAdmission` 的具名拒绝？ | **用户 2026-10-23 裁定 A**：在 sd 侧两条 `throw` 前各记一条 **INFO**（`SD_CHANNEL_ADMISSION_REJECTED`，origin=`sd-decision`，字段 actor/commandType/reason）；只加日志、不改判定 | 用户裁定 |
| 13 | economy 已有 94 个发射点？ | **用户 2026-10-23 裁定：全量补 `origin`/`originKind`**（新建 `EconomyLogSource`），并入 L3 | 用户裁定 |
| 14 | 跨切片一致性故障的日志档位？ | **保持 ERROR、不降级**（用户 2026-10-23 听完政治经济学语义后裁定）：`HOUSEHOLD_NATURAL_NEEDS_REJECTED`（投影对不上/坏键/坏需求表）、`HOUSEHOLD_POPULATION_DELTA_REJECTED`（投影对不上/坏 delta/**负人口**）——这是"世界账本破裂"，不是业务拒绝；业务拒绝才是 INFO | 用户裁定 |
| 10 | 工具面归属（app vs 各模块）？ | 用户设计意图＝工具归各模块 + 统一工具协议；**记入待办清单，不塞进日志批次** | 用户 2026-10-23 裁定 |

**原问句（留痕）**

1. `origin` 描述"工作性质"还是"谁按的按钮"？例：GM 用工具调 `simos.advance`——推进计算本身记 `originKind=tick`，
   工具调用那一层另记 `interaction`？建议按工作性质记（这样"关掉交互日志仍能看算法"才成立），入口交互单独成行。
2. `origin` 怎么传进领域层？建议显式参数：core 批执行时按批次来源把 `LogOrigin` 传给 handler；
   各模块自己的时间参与者直接写本模块表项。备选是线程上下文/MDC——与决策并发（`concurrency=6`）不兼容，建议不用。
   涉及若干方法签名的追加参数。
3. 读口/查询日志的范围：每次查询都记（TRACE）？还是只在空候选/具名拒绝时记（DEBUG）？
   建议后者（避免读口放大，且 §4.3 已禁止把高频机制逐次记）。
4. util 的 `LogEvent.of(...)` 需要追加一个带 `LogOrigin` 的 overload（用来渲染 `origin=`/`originKind=`），
   这超出"util 只加类型"的字面范围，属于向后兼容的 API 追加——是否接受？
5. 来源表 id 的稳定性：建议 id 一经发布不再改名（要改 = 新增项 + 旧项标注废弃），否则历史日志的 grep 会断裂。
6. 模块来源表的新增/变更要不要用户审：建议常规新增由控制方审；**一旦涉及语义改标**（例如把 tick 结算从
   `tick` 改成 `interaction`）**必须先上报用户**。
7. 决策回合内工具调用的表项（app）：先用 `DECISION_TURN` + `tool=` 字段表达，还是单开一项 `DM_TOOL_CALL`？
   建议前者（来源少而清晰），由 app 的表说明写清。

