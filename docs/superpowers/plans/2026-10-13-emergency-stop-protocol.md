# 2026-10-13 急停协议（突发事件 → 停下推进）—— 约束设计书

> **本文是派实现 Agent 的前置**（`AGENTS.md` §五.3 → `docs/agents/02-一-并发.md` §一.8）。
> **用户裁定**：「先写，写完再测」⇒ 本批 = **实现 + 真机实测**（真机验收见 §6.4）。
> **来源**：只读调查（四路并行 + 控制方亲自复核）。**未跑 Maven、未起服务**。
> **基线**：`main` @ `9c841e06` + **未提交的 Skill 拆除改动**（见 §8.1，与本批不冲突但派单时要说清）。
> **上游调查**：`docs/superpowers/reports/2026-10-13-emergency-tick-and-monitoring-recon.md`

---

## 1. 需求来源（用户原话，原样照录）

> 「肯定停下啊，具体就是，让util/core之类的模块维护一个**急停协议**，如果正在推进tick，且**任意模块报突发事件**，那么自动停下不得了；
> 目前我能想到的其实是Unit模块，例如Unit报两个单位接触，且之前GM要求在两个单位接触的时候报警，那么就停下，
> **这些条件之类都是可自定义、具体条件交给对应模块具体维护的**」

> 「不是不是，我首先说的是**两支军队相遇**，所以说**监控职能甚至应该包给Army模块**，**触发器本身只管是否被触发，不管怎么触发怎么监控的**，懂我意思吗？」

★ **这两条合起来定义的职责切分**（本设计的骨架，不得改动）：

| 层 | 职责 | 明确**不**负责 |
|---|---|---|
| **模块**（Army / Unit / 经济 / 社会…） | **怎么监控**、**什么算突发事件**（"具体条件交给对应模块具体维护"） | 不负责"停下推进" |
| **util** | **突发事件的类型**（稳定实体；"放 util 声明、覆盖所有包"） | 不认识任何领域判据 |
| **core** | **急停协议**（推进中收到任一模块的突发事件 ⇒ 停在当天） | 不认识"两军相遇"是什么意思 |
| **触发器**（既有 `Trigger`/`Effect`） | **只回答"是否被触发"**，据此执行动作 | **不管怎么触发、怎么监控** |

★ 这条切分**消除了一处"第二个拼写点"风险**：判据只在模块里有一份，触发器只接信号。

---

## 2. 现状事实：三条缺口，全部实测零命中

| # | 缺口 | 核验命令与结果 |
|---|---|---|
| **G1** | `TimeProposal` / `WorldTimeProposal` **没有任何字段能承载"我发现了什么"** | `TimeProposal(namespace, changeSet, reads, writes)`（`simos-util/.../util/spi/TimeProposal.java:22-23`）；`WorldTimeProposal(participantId, moduleChanges, reads, writes)`（同目录 `:28-31`）。且 **`moduleChanges` 不得为空**（空提案当装配错误抛）。`util` 里 `emergen\|urgent\|halt\|Incident` **零命中** |
| **G2** | `UnitTimeParticipant` **没有日循环** —— 在整个 range 上只算终点 | `grep -c "for (long" simos-unit/.../unit/spi/UnitTimeParticipant.java` = **0**；核心调用 `:223 UnitMoves.evaluate(unit, to.get(), map, cost)`。对比：经济有日循环（`PopulationEconomyTimeParticipant.java:620`）、ga 有（`GaTimeParticipant.java:174`） |
| **G3** | **Army 完全不参与时间推进** | `git grep "TimeParticipant\|simulateWorld" simos-army/src/main` ⇒ **零命中** |

### 2.1 支撑本设计的既有事实（好消息）

1. **分段等价是被守住的设计属性**（`PopulationEconomyTimeParticipant.java:639-642` 逐字）：
   > ★ 逐日现算、逐日取走 ⇒ 即使把 advance(0,360) 切成 3 段，每一天看到的仍是那一天自己的缺口
   > （**B2 分段等价的硬前提：不引入跨日/跨 advance 的瞬态记忆**）
   ⇒ **"停在 tick n、从 n 继续"语义安全**，前提是新判据也必须逐日自足。
2. **Unit 位置是时间序列，"任意 tick 在哪"现成可问**：`UnitState.java:102` `unit.position().valueAt(at)` ⇒ **"两军是否同格"是纯读，不用造新状态**。
3. **军的位置从 Unit 派生，Army 不存位置**：`Army(ArmyId id, Optional<UnitId> masterGovUnitId, UnitId rootUnit, String name)`；`ArmyData` 只有 `armies / combats / armyAffiliations` 三表。
4. **Army 的依赖面够用**：`simos-army/pom.xml` 依赖 `util + ga-spi + map + unit + social + gov` ⇒ 读 `UnitState`、写 `ArmyData`、用 util 的事件类型，**都不越界**（`simos-army` 允许依赖表见 `AGENTS.md` §三）。
5. **参与者目前 5 个实现、Shell 只注册 2 个**（`Shell.java:1001-1012`）：`PopulationUnitTimeParticipant`(namespace=`population`)、`GaTimeParticipant`(`ga`)；另 3 个（`EconomyOwnershipTimeParticipant` 等）只在测试夹具里。`TimeAdvance` 按 `namespace()` **字典序**排（`TreeMap`，`:146-154`）。

---

## 3. 目标 / 非目标

**目标**：推进过程中，**任意模块**在**某一天**判定出突发事件 ⇒ **世界停在当天**，该事实可被 GM 读回，且**可从当天继续推进**。

**本批范围**（用户点名的最小闭环）：
- `util` 加**突发事件类型**（稳定实体）；
- `core` 加**急停协议**（按日编排 + 收事件 + 停）；
- **Army** 加时间参与者 + **两军相遇**判据（用户点名的例子）；
- **GM 注册"什么时候要报警"** 的最小通道；
- **真机实测**：19 格世界上两支军队相向而行，须在第 N 天停住。

**非目标**：
- ❌ 不做"监控指标注册表"的完整形态（用户说"监控指标放 APP 管理"是**将来**，本批只做最小通道）；
- ❌ 不改 `CrisisMonitor` 的 5 类灯接入（那是第二批：`docs/superpowers/reports/2026-10-13-emergency-tick-and-monitoring-recon.md` §3.1 已备好事实）；
- ❌ 不动 `Trigger`/`Effect` 的既有五档/两档语义（本批**只加一档 effect 行为**："命中即停车"，见 §4.4）；
- ❌ 不修 `advanceAndDrain` 断裂（L1）的**全部**后果，但**急停必须同时 drain**（§4.5，否则每停一次多一条悬空效果）；
- ❌ 不做"紧急 tick 的分析 → 派单给决策人"那条链（那是下一批，用户工作流的后半段）。

---

## 4. 设计（接口 / 载荷 / 数据流 / 所有权）

### 4.1 `util`：突发事件的稳定实体（G1）

**落在 `io.mosire.simos.util.spi`**（推进契约的唯一住所；同目录已有 `TimeParticipant` / `TimeProposal` / `WorldTimeProposal` /
`HandlerOutcome` 已示范 sealed 形态）。约束：`simos-util` 主依赖**仅 Jackson + SLF4J**，**不得**反向依赖任何模块。

```
record Incident(String kind, long tick, String namespace, Map<String,Object> payload, List<String> logRefs)
                                                                                        ↑ ★ 见 §4.3.1 更正
+ 4 参兼容构造器（logRefs = List.of()）—— 不变量 I7 同款纪律
```
- `kind` —— **字符串**（不是 util 的枚举）：判据种类由**模块**定义，util **不认识**"两军相遇"。
  ★ 这是本设计的关键取舍：**util 出"容器与时刻"，模块出"种类与载荷"**。
- `tick` —— **发生在哪一天**（急停就停在这天）。★ **必须 == 当天推进日**（机械校验，不符 ⇒ `Rejected`）。
- `namespace` —— 哪个参与者报的（`ga` / `population` / `army` / …）。
- `payload` —— 结构化证据（**不许放 null**；地图/负荷明文不进日志，沿用既有纪律）。
- `logRefs` —— ★ **模块自报的日志锚**（§4.3.1 原因系统要它）；**util/core 不解释**（不变量 I5）。

★ **id 与可重放**：`Incident` **不是**世界事实（不进 revision、不许被 AAR 引用为稳定实体），故**不带 `Id` 类型**、不参与铁律 1 的稳定实体体系。这是**有意**的：它是**过程观测**，与 `DecisionRunRegistry` 的"进程内账"同族。

### 4.2 `util`：提案的上报通道（G1）

在 `TimeProposal` 与 `WorldTimeProposal` 各加一个字段（**默认空 ⇒ 既有实现零改动**）：

```
TimeProposal(namespace, changeSet, reads, writes, List<Incident> incidents)   // 新增，可为空
```
★ **必须用"加字段 + 兼容构造器"**，不许改既有参数顺序（5 个参与者实现 + 多个测试夹具都在构造它）。
★ 不变量：`incidents` 为空 = "我没发现突发事件"，**不是**"我不提案"（"不提案"仍是不变变更集）。

### 4.3 `core`：急停协议（G1 + 核心新增）★ 用户 2026-10-13 已拍口径

**现状**：`TimeAdvance.java:245-251` 每个参与者**只调一次** `simulateWorld(state, cmd.range())`，整个 range 算完 ⇒ `:331` 落**一条** revision。

**★ 口径 1 = (i)（用户已拍）**：把"按日"从参与者内部提到 Core，**逐日落 revision**（"世界真的停在 tick n、可直接续推"）。

**★★ 顺序铁律（用户原话）**：「**急停的时候是先世界里做完，然后再报给 GM 的**」
⇒ 每天的次序**必须是**：① 跑完当天全部参与者 → ② 汇总变更集、**落 revision（世界做完）** → ③ **drain（跨模块效果落地，口径 3=(A)）** → ④ 收齐当天的 `Incident` → ⑤ 若非空 ⇒ **停**并产出 `StopReport`（报给 GM）。
⇒ **不许**"先报再落"、**不许**"发现第一个事件就中断当天的剩余参与者"。

```
for (long day = from + 1; day <= to; day++) {
    proposals = 所有参与者.simulateWorld(state, TimeRange(day-1, day))   // ★ 全部跑完，不许短路
    incidents = 汇总 proposals 的 incidents                              // 聚合，不是取第一个
    commit(day)                                                          // ② 世界先做完
    drain(day)                                                           // ③ 跨模块效果落地（口径 3 = A）
    if (!incidents.isEmpty()) {
        return AdvanceOutcome.Stopped(new StopReport(atTick=day, reasons=incidents, worldRef=新坐标))
    }
    state = 新状态                                                        // ④ 继续下一天
}
```

### 4.3.1 ★ `StopReport`：**原因系统**（用户 2026-10-13 追加要求）

用户原话：「甚至要做一个**原因系统**，明确是**哪些模块**（可能不止一个模块要求停下呢？），最好**附上日志之类的**，方便 **GM 用 MCP 工具查**，也方便你调试」。

```
StopReport {                                                    ★ 落点：io.mosire.simos.util.spi.StopReport
  atTick       停在哪一天（世界已推进到这天，且已 drain）
  worldRef     落盘坐标 {branch, revision}（GM 可据此 replay 到这一刻）
  reasons[]    ★ 聚合：每个要求停的模块各一条，不是只取第一个
    namespace    哪个模块（army / population / ga / …）
    kind         它自报的种类（如 "army.contact"）
    payload      结构化证据（如 {armies:[a,b], hex:"3_0"}）
    logRefs[]    这一天的日志锚（模块自报，便于 GM/调试顺藤摸瓜）
}
```
★ **落点为什么是 `util.spi` 而不是 `core`（实现期更正，2026-10-13）**：§7.2 要求 `StopReport` 的 **MCP 读工具住 `simos-army`**，
而 `simos-army` 的允许依赖**不含 core**（`AGENTS.md` §三：util + map + unit + social + gov）
⇒ StopReport 若住 core，**B 的读工具直接编译不过**。它又无领域语义（只有 atTick/worldRef/reasons），
`core` / `army` / `app` 都看得见 ⇒ `util.spi` 是对的位置。

- **为什么聚合**：只报第一个原因会让 GM 看到**片面**信息（可能两个模块同一天各自都要停）。
  ★ 实现口径：**一个 `Incident` 一条 `Reason`**（不截断、不去重）。
- **为什么带 `worldRef`**：GM 要能 replay 到"停下的那一刻"，看完整世界。
- **为什么带 `logRefs`**：用户原话"方便你调试" —— 从"第 5 天为什么停"直接跳到那天的日志。
- ★ **`Incident.logRefs` 由模块自报**：util 不解释它们（与 I5 同一条纪律：util 不认识领域语义）。

**由此产生的三个必答问题**：

| # | 问题 | 本设计的选择 |
|---|---|---|
| **Q-A** | **中间态怎么落** | ★ **口径 1 = (i)（已拍）**：Core 逐日落 revision。360 tick = 361 条 revision；`checkpointInterval=1` 下 1.45 GB（既有实测）—— 但分轮推演本来就要分段，**不是新增代价** |
| **Q-B** | **写-写冲突怎么办** | 现在"冲突 ⇒ 拒绝整次推进"。按日之后：**单日冲突 ⇒ 停在冲突前一天 + 报冲突**（不静默跳过）。理由是"冲突"本身就是需要 GM 介入的突发事件 |
| **Q-C** | **返回值怎么变** | `CommandResult` **三种封闭、不许动**（它是铁律 2 的公共契约）。急停结果走**新类型** `AdvanceOutcome`（`Committed` / `Stopped(StopReport)` / `Rejected` / `Conflict`），在 **`Shell`/GUI/MCP 层**暴露；`CommandResult` 保持原样 |

### 4.4 判据与注册（G3 + 用户点名的例子）

**`Army` 新增 `ArmyTimeParticipant`**（namespace 建议 `army`；`TimeAdvance` 按字典序排 ⇒ 实际序 `army` → `ga` → `population`，**要确认 Army 在 population 之后看到的才是当天的单位位置** —— 见下方不变量 I6）。

**"两军相遇"判据（Army 自己维护）**：

```
对每一天 d：
  for each army a:  位置(a, d) = UnitState.positionAt(a.rootUnit, d)      // 纯读，既有 API
  for each 有序对 (a,b):  if 位置(a,d) 与 位置(b,d) 同格或相邻  ⇒ 报 Incident(kind="army.contact", tick=d, payload={a,b,hex})
```

★ **为什么判据住 Army 而不是 Unit**：用户点名的归属（"监控职能甚至应该包给 Army 模块"）；且 Army 依赖 `unit`，读得到位置。
★ **为什么 Army 自己算位置、不消费 Unit 的提案**：两阶段模型里**每个参与者拿到的都是同一份 base**（C25，`TimeAdvance.java` 类注），参与者之间**看不到彼此的提案** ⇒ Army **只能自己算**。这是既有架构约束，不是选型。

★ **为什么这是可行的（控制方已核）**：`UnitMoves.evaluate(Unit, SimosTimestamp at, GameMap, MovementCost)` 是**纯函数、第二参就是任意时刻**（`simos-unit/.../unit/move/UnitMoves.java:49-50`）⇒
Army 每天调一次 `evaluate(a.rootUnit 的 Unit, SimosTimestamp.of(d), map, cost)` 就能知道该军当天在哪，**不需要给 Unit 加日循环、也不需要任何跨参与者通道**。

★ **代价要记账**：Army 逐日算位置 = 对每个军每天调一次 `UnitMoves.evaluate`（**纯函数**）。这是**重复计算**（Unit 那边也会算），换来的是"判据住对模块"。本批接受，**但必须在账本里记明这笔账**，不得悄悄当成免费。

**"GM 要求在两军相遇时报警"（注册通道，★ 待你确认口径 2）**：
- **(a)** 住 **Army**（"具体条件交给对应模块"）：Army 持一份"要监控哪些军对"的配置，GM 用工具设置；
- **(b)** 住 **APP 的一张注册表**（"监控指标放 APP 管理"）：GM 注册"监控什么"，模块只提供可被监控的量。

本设计**先做 (a) 的最小形态**：`Army` 记录里加"是否对该军启用相遇报警"，GM 通过一条**新窄写工具**设置。理由是它最贴用户原话，且 (b) 的完整形态（可注册指标 + DM 提议）是下一批。

### 4.5 急停必须同时 drain（L1，否则每停一次多一条悬空）

`Shell.advanceAndDrain` 在生产路径**零调用**（`/api/advance` 与 `simos.advance` 都直接 `core.submit`）。
`Action.EnqueueUnitCommand` 类效果**永不落地**（`GaTimeParticipant:304-306` 有意忽略，交 drain）。

⇒ **急停的提交路径必须走 drain**（否则停在 tick 5 时，第 5 天 FIRED 的跨模块效果永远悬着）。
⇒ ★ **口径 3 = (A)（用户已拍）**：**只把 drain 接进急停路径**。
⇒ ⚠️ **既有断链不在本批**：`/api/advance`（`GuiServer.java:1702`）与 `simos.advance`（`AdvanceTool.java:123`）**仍直接 `core.submit`、仍不 drain** ——
这是**有意的范围裁剪**，不是遗漏；要另开一批（它会改既有入口的行为，不能顺手带）。**本批必须在账本里写明这条"已知未修"**。
⇒ ★ 缓解事实（**未实测**）：`new Action.EnqueueUnitCommand(...)` 在 `src/main` 零构造点 ⇒ 今天若没人注册过这种效果，损失是 0（见 §10.6）。

### 4.6 模块所有权与依赖方向（不得违反）

| 改动 | 模块 | 允许吗 |
|---|---|---|
| `Incident` 类型 | `simos-util`（仅 Jackson + SLF4J） | ✅ |
| `TimeProposal` 加字段 | `simos-util` | ✅ |
| 急停编排 | `simos-core`（`TimeAdvance`） | ✅ |
| `ArmyTimeParticipant` + 判据 + 注册字段 | `simos-army`（可读 `unit`/`map`/`util`/`ga-spi`/`social`/`gov`） | ✅ |
| 装配参与者 | `simos-app`（`Shell.java:1001-1012`） | ✅ |
| 暴露 `AdvanceOutcome` | `simos-app`（GUI `/api/advance` + MCP `simos.advance`） | ✅ |

★ **禁止**：`simos-util` 反向依赖任何模块；`simos-army` 依赖 `simos-economy`（不在允许面内，enforcer 会拦）。

---

## 5. 不变量（不可破坏）

| # | 不变量 |
|---|---|
| **I1** | `TimeParticipant.simulateWorld` **仍是纯函数**（拿 base、吐提案、不写状态）——急停不得让它写状态 |
| **I2** | **分段等价不破**：`advance(from, from+N)` 的终态 == N 次单日 advance 的终态（既有裁定；`aggregate-economy-redesign.md:236-247`）。⇒ 所有新判据**必须逐日自足**，不得引入跨日瞬态记忆 |
| **I3** | `CommandResult` **三种封闭不动**（Committed / Rejected / Conflict）；急停走新类型 |
| **I4** | 被拒/冲突**不留 revision 行**（既有 R9/R14）；急停**要**留 revision（它是一段真实推进的终点） |
| **I5** | **不引入第二个拼写点**：`Incident.kind` 由模块定义，util/core **不得**出现任何具体 kind 的字面量判断 |
| **I6** | **参与者顺序**：`TimeAdvance` 按 `namespace()` 字典序排 ⇒ Army 若需"当天的单位位置"，**只能自己算**（不能依赖"population 先跑"）——这条要写成断言，不许靠注册顺序 |
| **I7** | `TimeProposal` / `WorldTimeProposal` 的**既有构造点不许破坏**（5 个参与者实现 + 测试夹具）：加字段必须配兼容构造器 |
| **I8** | 急停路径**必须 drain**（§4.5） |

---

## 6. 验收判据与负向用例

### 6.1 编译与既有测试（不许红）

```bash
tools/mvn-lock.sh -q -pl simos-app -am -DskipTests compile        # rc=0
tools/mvn-lock.sh test -pl simos-core,simos-unit,simos-army,simos-app -am
```
★ **既有等价性测试必须仍绿**（I2 的护栏）：`advance(from, from+N)` == N 次单日 —— 找到它并单跑，给真实输出。

### 6.2 正向（新能力）

| # | 判据 |
|---|---|
| **P1** | **单日不触发**：两支军队都静止 ⇒ 推进 10 天，**不**停、`AdvanceOutcome.Committed`、落 1 条 revision（若口径 1=(i) 则落 10 条，按裁定改） |
| **P2** | ★ **中途相遇**：A 军从 (0,0) 向 (5,0) 行军、B 军从 (5,0) 向 (0,0) 行军，第 5 天在同格 ⇒ **停在第 5 天**，`Stopped{atTick=5, incidents=[kind=army.contact, payload={a,b,hex}}]` |
| **P3** | **停在世界真的在 tick 5**：停后读 `/api/state` 的 tick == 5；从它 `advance(from=5, to=10)` 能续推 |
| **P4** | **只有被要求监控的军对才报警**：未启用相遇报警的两军相遇 ⇒ **不停** |
| **P5** | **原因系统可查（口径 2b）**：GM 用 **MCP 工具**读回 `StopReport` —— 能拿到 `atTick` / `worldRef` / **`reasons[]`（每个要求停的模块各一条）** / payload / `logRefs` |
| **P5b** | ★ **聚合而非短路**：同一天让**两个**模块各报一个突发事由 ⇒ `reasons[]` 里**两条都在**（只报第一条 ⇒ **红**） |
| **P5c** | ★ **先做完世界再报**：停下后读 `/api/state` ⇒ tick == atTick（**世界已推进到那天**），且该天 FIRED 的跨模块效果**已落地**（不是"报了但没做"） |
| **P6** | **drain 生效**（口径 3=A）：急停路径下，第 5 天 FIRED 的跨模块效果**已落地** |

### 6.3 负向（★ 本批最容易出的假绿）

| # | 负向用例 | 期望 |
|---|---|---|
| **N1** | 故意把 Army 的判据改成"永不报" | P2 **当场红** ⇒ 证明 P2 真的在测判据 |
| **N2** | 故意把 Army 的位置改成 `range.to` 算一次（复刻 Unit 现在的形态） | P2 **红**（两军第 5 天擦肩而过、第 10 天分开 ⇒ 检测不到）⇒ **这条同时证明了 G2 的真实性** |
| **N3** | 故意把急停提交路径的 drain 去掉 | P6 **红** ⇒ 证明 §4.5 的必要性 |
| **N4** | 故意让 `Incident` 在 core 里被按 kind 字面量判断 | **代码评审即红**（I5：第二个拼写点）—— 做不到自动化就**如实说"未自动化"** |
| **N5** | 故意不配兼容构造器（改既有参数顺序） | 编译**断** ⇒ 证明 I7 是真的 |

★ 每条：**改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5**。做不到就如实写"等价存活"，**不许把"没跑"写成"通过"**。

### 6.4 ★ 真机实测（本批关账的硬要求）

用户要的是"写完再测"，故**必须在真机上跑一次**：

```
1. 建空 store → 起 19 格 small-world（独立 store/端口，不踩既有实例）
2. 建两支军队（GM 工具：simos.unit.spawnArmy / simos.gov.recruit…），各给一条相向路线
3. 通过新窄写工具对这两军启用"相遇报警"
4. 推进 10 天 → 期望停在第 5 天
5. 读回：/api/state 的 tick、事件读口、drain 后的世界状态
6. 从第 5 天续推到第 10 天 → 期望正常走完
7. 全部原始输出 + store 的 revisions 表存档
```
★ 真机实测**不许**用程序化测试代替（用户 2026-10-23 裁定：真实世界长跑不进程序化门禁）。

---

## 7. 文件所有权（★ 一个责任区一个 owner）

### 7.1 实现 Agent A：`util` 契约 + `core` 协议

✅ 允许改：`simos-util/.../util/spi/`（新增 `Incident`；`TimeProposal`/`WorldTimeProposal` 加字段与兼容构造器）、
`simos-core/.../core/advance/TimeAdvance.java`、`.superpowers/sdd/2026-10-13-emergency-stop/impl-ledger-core.md`

❌ 禁止：任何 `src/test/**`、`docs/**`、`AGENTS.md`、`pom.xml`、以及 `simos-army`/`simos-app` 的生产代码。

### 7.2 实现 Agent B：`Army` 判据 + 装配 + **原因系统读口**

✅ 允许改：`simos-army/.../army/`（新增 `ArmyTimeParticipant` + 相遇判据 + 注册字段 + 窄写工具 + **`StopReport` 的 MCP 读工具**）、
`simos-app/.../app/Shell.java`（**只加三处**：参与者注册 `:1001-1012`、drain 接线、`StopReport` 的持有与读取）、
`.superpowers/sdd/2026-10-13-emergency-stop/impl-ledger-army.md`

★ ★ **实现期更正（2026-10-13）：drain 接线不在 B 的范围，改为 A 在 core 里开口、B 只写一行。**
原文说"B 在 `Shell` 接线 drain"，**在现有所有权下做不到**：`CoreSimos` 是唯一 `new TimeAdvance` 的地方（`:500`），
且**没有任何**暴露 `AdvanceRoute` / `AdvanceOutcome` 的口 ⇒ `Shell` 拿不到 `TimeAdvance`
⇒ 顺序铁律（②落 revision → ③drain → ⑤报）**只能在 core 内部兑现**。
A 的处置（**已批准**）：core 新增 SPI `StopDrain { void drainAfterStop(StateRef worldRef, long atTick); StopDrain NONE; }`，
`TimeAdvance` 保 5 参构造器 + 新增 6 参（缺省 `NONE`），`CoreSimos` 加 `register(StopDrain)` 与 `submitAdvance(AdvanceTime) → AdvanceOutcome`（**纯加法，既有 `submit(...)` 行为一字不改**）。
⇒ **B 的"drain 接线"就是一行**：`coreSimos.register((ref, tick) -> sdCommandDrain.drainAfterAdvance(ref.branch()))`。

★ **`StopReport` 读工具的形状（口径 2b）**：`roles() = {GM}`（GM 用 MCP 查），返回 `{atTick, worldRef, reasons:[{namespace,kind,payload,logRefs[]}]}`。
★ **`StopReport` 落点 = `io.mosire.simos.util.spi`**（见 §4.3.1 的更正；**住 core 会编译不过**，因为 army 依赖不含 core）。
★ **`StopReport` 住哪（存储）**：它是**过程观测**（与 `DecisionRunRegistry` 同族，**不进 revision**）。
`DecisionRunRegistry` 的类注已给出这个取舍的完整理由（"进 revision 会污染世界状态；另存一张表则要动 Core 的 DDL"），**照它**。
★ **进程重启即失**：这是**已知取舍**（`DecisionRunRegistry` 同款），必须写进账本与读工具的 description（不许让 GM 读成"没发生过"）。

❌ 禁止：`simos-util`、`simos-core`（A 的范围）、任何 `src/test/**`。

★ **依赖序**：**B 依赖 A 的接口**（`Incident` 与提案字段）。⇒ B **必须等 A 交账**（`blocked_by`），
但 A 交账后 B 可独立施工。**为避免 B 空等，A 的接口形状在本文 §4.1/§4.2 已冻结**，B 可先读本文写代码、等 A 落地再编译。

### 7.3 测试 Agent（A、B 都交账后）

✅ 允许改：`src/test/**`、`.superpowers/sdd/2026-10-13-emergency-stop/` 的证据文件。

❌ 禁止：任何生产文件（发现残留 ⇒ 上报）。

### 7.4 控制方（我）

派单 / 验收 / **真机实测（§6.4）** / 落盘文档 / 提交。**不写代码**。

---

## 8. 现场提醒

1. **工作树有未提交的 Skill 拆除改动**（25 文件 +57/−1109）⇒ 派单前先 `git status --porcelain` 看清；
   **本批的改动必须能与它分辨**（不要 `git add -A`）。
2. **本仓一次只跑一个 Maven**：一律 `tools/mvn-lock.sh`。
3. **不要在别人跑 Maven 的窗口里改 Java**。
4. **`Incident` 不进世界状态、不进 revision**：它是过程观测（与 `DecisionRunRegistry` 同族）。
   ★ 若将来要 AAR 引用它，**另开**一份世界事实，不许就地把它塞进 revision。
5. ~~两处待确认口径~~ ⇒ ★ **三条口径已由用户 2026-10-13 拍定**（见 §9），并有实现期更正三条（见 §9.1）。

---

## 9. ★ 口径裁定（用户 2026-10-13 已全部拍定）

| # | 口径 | **裁定** |
|---|---|---|
| **口径 1** | **中间态怎么落** | ★ **(i) Core 逐日落 revision** —— 世界真的停在 tick n、可直接续推（用户：「1 肯定 i 啊」） |
| **口径 2** | **"GM 要求报警"的条件住哪** | ★ **(a) 住 Army**（"具体条件交给对应模块"）（用户：「2 A」） |
| **口径 2b** | **追加：原因系统** | ★ **要做**：停下时明确"哪些模块要求停"（**可能不止一个**）+ 附日志锚 + **GM 能用 MCP 工具查**（用户原话：「甚至要做一个原因系统…最好附上日志之类的，方面GM用MCP工具查，也方便你调试」）⇒ 落到 §4.3.1 `StopReport` |
| **顺序** | **先做完世界再报** | ★ 用户原话：「**急停的时候是先世界里做完，然后再报给GM的**」⇒ §4.3 的五步次序，**不许**先报再落、**不许**短路当天剩余参与者 |
| **口径 3** | **drain 修到什么程度** | ★ **(A) 只把 drain 接进急停路径**（用户：「A啊」）；既有的 `/api/advance` 与 `simos.advance` 断链**不在本批**，另开批 |

⇒ **三条口径已全部拍定，本设计书可派单。**

### 9.1 ★ 实现期更正（2026-10-13，A 施工中发现并上报，控制方已就地更正）

| # | 原文问题 | 更正 | 影响 |
|---|---|---|---|
| **E1** | §4.1 冻结 `Incident(kind,tick,namespace,payload)` **无 `logRefs`**，而 §4.3.1 要求 StopReport 带 `logRefs[]` 且"由模块自报" —— **两节自相矛盾** | 定为 `Incident(..., List<String> logRefs)` + **4 参兼容构造器**（`List.of()`） | 加法，不改既有顺序（I7 同款） |
| **E2** | §4.3.1 **没写 `StopReport` 住哪** | 定为 **`io.mosire.simos.util.spi.StopReport`**。理由：§7.2 要求读工具住 `simos-army`，而 **army 的允许依赖不含 core** ⇒ 住 core **编译不过**；它又无领域语义，`core`/`army`/`app` 都看得见 | 硬约束，不是偏好 |
| **E3** | §7.2 写"B 在 `Shell` 接线 drain" —— **在现有所有权下做不到** | 改为 **A 在 core 开口**：新增 SPI `StopDrain{ void drainAfterStop(StateRef, long); StopDrain NONE; }`；`TimeAdvance` 保 5 参构造器 + 新增 6 参（缺省 `NONE`）；`CoreSimos` 加 `register(StopDrain)` 与 `submitAdvance(AdvanceTime)→AdvanceOutcome`（**纯加法，既有 `submit(...)` 行为一字不改**）。**B 的 drain 接线变成一行** | `CoreSimos` 是唯一 `new TimeAdvance` 的地方（`:500`），且无任何暴露 `AdvanceRoute`/`AdvanceOutcome` 的口 ⇒ 顺序铁律只能在 core 内兑现 |

★ 另冻结（A 报、控制方认）：`WorldTimeProposal.single(TimeProposal)` **必须把 `incidents` 带过去**
（默认 `simulateWorld` 走它；漏了则模块判据**静默失效**）；`Incident.tick` **必须 == 当天推进日**（机械校验，不符 ⇒ `Rejected`）。

---

## 10. 未核 / 如实记

1. **未跑 Maven**：一切"现有测试会红/会绿"的判断均为静态推导。
2. **未核** 既有"分段等价性测试"的**确切类名与行号**（只从 `aggregate-economy-redesign.md:236-247` 的裁定原文确认该判据存在；
   §6.1 要求实现 Agent 自己找到并单跑它）。
3. ~~**未核** `UnitMoves.evaluate(unit, day, map, cost)` 对**任意历史/未来 tick** 是否都能算~~
   ⇒ ★ **已核（控制方 2026-10-13）**：签名是
   `public static MovementState evaluate(Unit unit, SimosTimestamp at, GameMap map, MovementCost cost)`
   （`simos-unit/.../unit/move/UnitMoves.java:49-50`）—— **第二参就是"任意时刻"**，纯函数。
   ⇒ **不必给 Unit 加日循环**：Army 每天调一次 `evaluate(unit, SimosTimestamp.of(d), map, cost)` 即可得知该军当天在哪。
   ⇒ 这使 §4.4 的判据**自足**（不依赖任何参与者的中间产物），也顺带回答了一个我原先判错的点：
     **G2（Unit 无日循环）不再是本批的阻断项**——它是"Unit 自己看不中途"的问题，而**判据住 Army，Army 自己算**，绕开了它。
   ★ 代价不变：这是**重复计算**（Unit 那边也会算），账仍要记（§7.2 账本）。
4. **未核** `Army` 逐日算位置的**性能代价**（每个军每天一次 `UnitMoves.evaluate`）——在 19 格世界上应可忽略，**大图上未评估**。
5. **未核** `namespace="army"` 的字典序位置（`army` < `ga` < `population`）是否会与既有断言冲突。
6. **未核** `checkpointInterval=1` 下逐日 revision 的磁盘占用在 19 格世界上的**实测值**（只有 360 tick 的历史读数 1.45 GB）。
7. **未核** GM 建军队并给路线的**完整工具序列**（`simos.unit.spawnArmy` / `PlanRoute` / 设定路线）—— §6.4 的真机实测需要，
   实现 Agent 与控制方要一起把这条命令序列先跑通。

---

## 11. ★★ 关账期发现：既有缺陷「分段不等价」（2026-10-13 控制方实测，**非本批引入**）

### 11.1 实验（在**完全未改动**的基线 `9c841e06` worktree 上做的）

| 实验 | 代码 | 结果 |
|---|---|---|
| A | `advance(0→30)` 一次推（原样） | ✅ Z7 **绿**（2 跑 0 红） |
| B | `advance(0→15)` + `advance(15→30)`（**两段相邻**，改动只在测试里） | ❌ Z7 **红**，且**红在同一条断言**上 |
| C | 本批的按日编排（`advance(0→30)` 内部逐日提交） | ❌ Z7 **红**，红在同一条断言 |

⇒ **B 证明了：把一次推进切成两段相邻推进，引擎的终态就不一样了。** 这个分歧**与本批无关** —— 它在未改动的基线上就能复现。
⇒ C 之所以红，是因为按日编排**等价于**"切成 N 段"（而按 B2 的裁定，它**本该**与一次推等价）。

### 11.2 可观测差异（同一用例、同一天）

| | 一次推 30 天（绿） | 分段/按日（红） |
|---|---|---|
| day 4 `GOV_ADMIN_SALARY_DAY unit=gov-central` | `shortfall=**320**` | `shortfall=**160**` |
| day 5 `GOV_STAFF_HOUSEHOLD_PROJECTION` | `{gov-central:POST=2, gov-province:POST=2}` | **`{gov-province:POST=2}`**（gov-central 消失） |
| day 5 `GOV_BUDGET_ALERT` | `[ADMIN_BUDGET_SHORTFALL]` | `+ ADMIN_SERVICE_FLOW_ZERO + **ADMIN_VACANCY**` |
| day 30 上缴 | `paidGrain=8018539` | **逐值相同** ✅ |
| day 30 中央政府工资 | `paidGrain=320 shortfall=0` | **整行不存在** |

⇒ 机制：**中央政府那个官吏户的岗位在 day 5 前后消失**（`ADMIN_VACANCY`），于是第 30 天上缴到账后**没有工资户可发**。

### 11.3 违反的是哪条裁定（逐字）

`docs/superpowers/specs/2026-09-25-aggregate-economy-redesign.md:236-247`（用户原话「我点推进100天，它不直接结算到100，而是一天一天地推进！改！」）：
> **结算语义不跳日**：各参与者必须在这次推进内部**逐日推进** …
> 判据是**等价性测试**：`advance(from, from+N)` 的**终态** == `N 次单日 advance` 的终态

而 `PopulationEconomyTimeParticipant.java:639-642` 把前提写成了硬要求：
> ★ 逐日现算、逐日取走 ⇒ 即使把 advance(0,360) 切成 3 段，每一天看到的仍是那一天自己的缺口
> （**B2 分段等价的硬前提：不引入跨日/跨 advance 的瞬态记忆**）

### 11.4 已有的护栏为什么没抓到

- 仓里**确实有**那条等价性断言：`UnitTimeParticipantTest:241`
  `multiDayAdvanceEqualsChainedSingleDayAdvancesIncludingTheWholeFormation` —— 且它在我们树上**仍绿**。
- ⇒ **等价性对 Unit 成立、对经济/GOV 那半边不成立。** 缺的是一条**跨模块（经济+gov+social）的分段等价**断言；
  Z7 是第一个撞上的用例，而它撞上纯属**偶然**（它只看 day 30 那一条工资行）。

### 11.5 定位结论（已缩小，未到底）

- `PopulationEconomyTimeParticipant` **只有一个**可变实例字段（`knownGovUnits:186`），且它在每次 `advance` 开头都会重刷（`:456`）⇒ **不是**元凶
  （按日版复用同一实例、两段式也重刷，两者都红，与"实例状态丢失"无关）。
- 日循环**内部不读** `range.from()`（只有日志用）⇒ 不是"起点变了"。
- ⇒ 嫌疑收敛到**每次 `advance` 重建、却在一次调用内跨日累积的瞬态**：`EconomyDayStepper` / `GovDaily` / 各 Bridge 的
  **周期累加器**（`FlowRow` 的周期计数、`OperatorCondition` 的连续周期计数等）。**未定位到具体那一行**。

### 11.7 ★★★ 定位结论（2026-10-13 责任区 `2026-10-13-segment-equivalence` 收口，**已到底**）

> 本节由责任区 `task-8` 落盘。实现账本（本机留痕，**不在版本库**）：
> `.superpowers/sdd/2026-10-13-segment-equivalence/impl-ledger.md`；证据：同目录 `evidence/`。

#### 11.7.1 §11.5 的两条嫌疑逐条否掉

| 嫌疑 | 判定 | 证据（回代码核过） |
|---|---|---|
| `PopulationEconomyTimeParticipant:315-333` 的 `range.from().tick()` | **不是元凶** | 那一段是 advance 开头的一次性**只读投影读数**（`GOV_STAFF_HOUSEHOLD_PROJECTION` / `GOV_STAFF_PROJECTION_MISMATCH`），不进任何判定；`tick()` 只作 `day` 进日志。★ 但它解释了一条**假象**：该日志每次 `advance` 只打一条 ⇒ 一次 30 天只在 day0 打、分段版在 day0 与 day15 各打一条，两张表里"day5 投影"实为**不同时刻**的日志 |
| `committedLaborByHousehold(economy, govUnitId, day)` 的 `day` | **不是元凶** | `GovernmentServiceLaborBridge.java:178` 类注逐字：「`day` 世界日（只进日志上下文；TICK origin 必带）」 |

#### 11.7.2 真根因：**写入侧在 stepper、读取侧在基态切片**

基线 `9c841e06` 的 `PopulationEconomyTimeParticipant.simulateWorld(state, range)` 里，`economy` 是**本轮基态**
（从 state 读入后**整个方法内不再更新**），而日循环每天：

```
GovSalaryRuleBridge.deriveReport(units, economy, day)      # 读**基态** economy
GovernmentUpkeepOracle(…, economy)                          # 读**基态** economy
GovernmentServiceDesertionBridge.execute(…)
  → reduceGovServiceCommitments(live = stepper.data(), …)   # 缩减只写进 **stepper 工作副本**
  → stepper.applyGovServiceCommitmentReductions(…)          # 不进基态 economy
```

⇒ 一次 `advance(0→30)` 内，day N 的缩减对 day N+1 的工资/俸禄**永不可见**（两侧在一次调用内不同步）；
而 `advance(0→15)` + `advance(15→30)` 的第二次调用从**新 revision** 读基态 ⇒ 缩减可见 ⇒ 结果分叉。
**这就是"引擎结果依赖推进被切成几段"的根因。**

#### 11.7.3 ★ 本批的按日编排已让 11.7.2 结构上不可能复现（实测）

本批工作树已把 `TimeAdvance` 改成"按日编排、逐日落 revision"（`simos-core/.../TimeAdvance.java` 类注），
参与者**每天被调一次** ⇒ 不存在"一次调用内跨日复用同一份基态"。
实测（同版本、同夹具，真 `Shell` + `SmallWorld` + 真 `bootstrapGenesis`）
`one-shot(0→30)` / `split(0→15)+(15→30)` / `daily(30×1)` 三路的**终态与 INFO 日志逐字节相同**：

```
md5sum /tmp/seg-equiv-probe/*.txt
a256bd21… one-shot-at30.txt   == split-at30.txt == daily-at30.txt
cf4ac5f3… one-shot-log.txt    == split-log.txt  == daily-log.txt
```

★ **注意**：§11.1 的 B 实验（基线一次推 vs 基线分段）在**基线**上确实分叉（已复核，见 11.7.4）；
§11.2 表里"一次推 30 天 ⇒ day5 官吏户还在"是**基线**现象，**当前树不复现**。

#### 11.7.4 ★★ Z7 现在红的**真因**与分段无关：**「户空即删承诺行 ⇒ 断供」**

用户 2026-10-13 裁定（逐字，最高权威）：

> 「我的设计要求是，**就算是政府家户，0 人也发钱** —— 我甚至**从来没设计过没人就不发钱了**」

违反它的**那一处**（改前 `GovernmentServiceDesertionBridge.java:641-642`，方法 `reduceGovServiceCommitments`）：

```java
if (remaining <= 0L) { next = 0L; }        // 户空 ⇒ 写 0
…
reductions.put(row.id(), next);
```

⇒ `EconomyDayStepper.java:726-727` `if (next == 0L) { commitments.remove(id); }` ⇒ **承诺行被删** ⇒
`GovSalaryRuleBridge.java:128-129,156-166` 的**唯一遍历源**（`GovernmentServiceLaborBridge.committedLaborByHousehold`）
里没有该户 ⇒ day5 起 `unit=gov-central` 的 `GOV_ADMIN_SALARY_DAY` **整行消失**（连"欠俸"读数都没有）⇒
Z7 的 day30 断言（`paidGrain=320 shortfallGrain=0`）必红。
同一条链还让俸禄失去收款方（`GOV_UPKEEP_RECIPIENT_MISSING` ERROR，`reason=no-official-household`）、
`ADMIN_VACANCY`、`GOV_EFFICIENCY_ZERO_SUPPLY reason=no-posted-household-commitment`。

**"day5 户又回来"的结论：没有复活。** 本仓确实**没有**自动补员（补员只有 GM 手动工具
`simos.gov.applyStaffing` / `GovRecruitTool` / `GovAbsorbUnitTool`）；基线"复活"是 11.7.2 那条**读取不可见**造成的
假象 —— 基线逐日版 day30 终态里该户的承诺行**不存在**、`internalPosts=[]`，`ADMIN_VACANCY` 照常。

#### 11.7.5 修复（唯一改动文件）

`simos-app/src/main/java/io/mosire/simos/app/time/GovernmentServiceDesertionBridge.java`（+42 / −85）：

1. **不缩、不释放 `GOV_SERVICE` 承诺**（含部分走人与整户走光）：承诺行 = **职位（post）**，是发薪唯一遍历源；
   人口走光只让**有效劳动**归 0（`GovernmentServiceLaborBridge.supply` 的 `min(承诺, 该户实际劳动)`，Z7d-1 既有口径）。
   ⇒ 删掉 `reduceGovServiceCommitments` 整段。
2. **不摘岗位、不摘 `Unit.households`、不迁壳户**：0 人壳户留在原 unit（位置保持 `UNIT(<gov>)`）。
   ⇒ 删掉 `disbandEmptyHousehold`；不再产生 `Eviction`。
   理由：俸禄目标 = `Unit.households()` − 国库户（`GovernmentUpkeepOracle:296-322`），摘出去就没有收款方；
   且留在 unit/位置不变 ⇒ `HouseholdUnitConsistency` 的「`Unit.households` ⇔ `location == UNIT(unitId)`」不变量逐条成立。

★ **不是自动补员**（不招人/不注资/不补俸）；★ **没有新增任何状态字段**。
实测：`Z7RemittanceE2ETest` **一字未改、两跑全绿**（`Tests run: 2, Failures: 0`）。

#### 11.7.6 新增护栏（本仓原先缺的那条）

`simos-app/src/test/java/io/mosire/simos/app/time/SegmentEquivalenceAcrossEconomyGovSocialTest.java`：
把 `UnitTimeParticipantTest:241` 那条等价性判据推广到 **经济 + GOV + Social**（+ 反向用例：户空仍足额发薪）。

**变异自证 = 把基线代码放回来**（同一份测试源码、同一台机器、只换引擎）：

| 树 | 命令 | rc | 结果 |
|---|---|---|---|
| 基线 `/tmp/baseline-wt`（`9c841e06`，未改动） | `tools/mvn-lock.sh test -pl simos-app -am -Dtest=SegmentEquivalenceAcrossEconomyGovSocialTest …` | **1** | `Tests run: 2, Failures: 2` |
| 当前树（含本次修复） | 同上 | **0** | `Tests run: 2, Failures: 0` |

基线红在哪（逐字）：一次 30 天 `ADMIN_SUPPLY evidence={assessed=170, paid=166, shortfall=4}`、无 `ADMIN_VACANCY`；
逐日 30 次 `paid=0, shortfall=170`、有 `ADMIN_VACANCY`；且**逐日版 day30 完全没有中央工资行**。

#### 11.7.7 `Z7FleeTest` 6 处断言钉住的是**旧缺陷行为** ⇒ 已上报 ⇒ **用户已裁定改向，已改完（2026-10-14）**

`Z7FleeTest.emptyingHouseholdEvictsPostsReleasesCommitmentsAndSignalsWithoutAutoRefill` 的
`:255-257`（壳户位置 = `Hex`）、`:260-263`（`households` 不含该户）、`:265`（岗位摘除）、
`:266-272`（承诺行释放）、`:273-277`（`projectedStaff`=0）—— **断言的正是用户裁定为缺陷的旧行为**。
按责任区纪律「断言本身写错了 ⇒ 上报，不许自行改断言方向」，**先保持原样（红）并上报**；控制方转呈用户后
**用户口径已明确：批准改断言方向**（澄清：task-8 硬约束禁的是"放宽/删除断言来变绿"，**改向新口径是纠正错误方向，不是放宽**）。

**改向结果**（只改 `Z7FleeTest.java` 一个文件，+45 / −18；生产代码零改动）：

| 位置（改后） | 旧断言 | 新断言（★ 值全部实测，没猜） |
|---|---|---|
| `:271-273` | `location()` `isInstanceOf(Hex.class)` | **`isEqualTo(new HouseholdLocation.Unit("gov-central"))`**（更紧：还钉死 unitId） |
| `:276-279` | `central.households()` `doesNotContain(OFFICIAL)` | **`contains(OFFICIAL).contains(CENTRAL_TREASURY)`** |
| `:280-285` | 岗位 `doesNotContainKey(OFFICIAL)` | **`containsKey` + `role()==StaffRole.POST`**（更紧） |
| `:286-294` | 承诺 `count()` `isZero()` | **`hasSize(1)`** |
| `:295-297` | （旧断言无此维度） | **新增 `laborMilli == 32_000L`**（★ 只断"行还在"会漏掉"行还在但被缩半"这一半缺陷） |
| `:298-302` | `projectedStaff` `isZero()` | **`isEqualTo(2L)`**（32000 ÷ 标准定额 16000） |
| `:242` | 方法名 `…EvictsPostsReleasesCommitments…` | **`emptyingHouseholdKeepsPostsAndCommitmentsAndSignalsWithoutAutoRefill`** |

★ 7 处里 **3 处收紧**（新增维度）、3 处纠正方向、1 处文字；**没有一条是放宽**。
★ day2 效率断言**值仍是 0**，但**原因变了**：不再是 `no-posted-household-commitment`（无岗位），
而是 `committed-supply-underfed-to-zero`（在编但无人到岗）⇒ `.as(...)` 与注释已同步修正。

**护栏有效性自证（两个独立牙齿）**：

| 变异 | 做法 | 结果 |
|---|---|---|
| **A** 整个修复改回旧行为 | `git show HEAD:<生产文件>` 直接覆盖（即加 task-8 修复之前的原版） | **rc=1**，红在 `:273` |
| **B** 只把"户空保留承诺"改回（不动岗位/households/位置） | 修复版上仅加：户空时 `commitmentReductions.put(id, 0L)` | **rc=1**，红在 `:294`（★ 发薪依据不释放 = 用户要求本身） |

还原后 **md5 同值**（`2b41d907…`），变异残留 `grep -c` = 0。
★ 做不到的如实记：变异 C（只摘岗位、保留承诺）未跑；JUnit 遇首个失败即停，故 `:277`/`:282` 两条
**没有各自独立的**变异证据，只有"随 A 一起红"。

**验收**：`rm -rf */target/surefire-reports && tools/mvn-lock.sh -q -pl simos-app -am test` ⇒ **rc=0、BUILD SUCCESS、全树零红**。
计数口径：`907` 是 simos-app 模块自己的汇总；15 行模块汇总相加 = **3218**（与控制方报数一致）。

#### 11.7.8 §11.6 状态（取代）

★ **已修 + 断言已改向，全量零红**（责任区 `2026-10-13-segment-equivalence`，task-8 + task-9）。
分段不等价在本批按日编排下**已不可复现**（11.7.3，三路逐字节相同），Z7 的 day30 断言**一字未改、已绿**（11.7.5），
`Z7FleeTest` 按用户裁定改向新口径并全绿（11.7.7）。
**未做**：全仓 `clean verify` / `tools/gate.sh` 门禁；其它模块未单独跑测试。详见实现账本 §7「未验证项」。

### 11.9 ★ 关账：控制方最终验收（2026-10-14 00:27，**亲自跑，非转抄**）

```
rm -rf */target/surefire-reports
tools/mvn-lock.sh clean verify
→ rc=0，[INFO] BUILD SUCCESS
→ 合计 Tests=3218  Failures=0  Errors=0  Skipped=5
→ 前端门禁 412/412 全绿
→ SpotBugs：16 个模块 "BugInstance size is 0"
```
期间修掉的一处门禁红：修复新增的 Javadoc（引用用户原话那段 `<blockquote>`）折行不合规 ⇒ `spotless` 红；
`spotless:apply` 后 `spotless:check` rc=0，重跑 `clean verify` 绿。

★ **本批（急停协议）到此关账**，且**顺带闭合了一条既有缺陷**（§11 全程）。

