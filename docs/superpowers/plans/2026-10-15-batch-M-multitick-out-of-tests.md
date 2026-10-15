# 批 M 约束设计书 —— 多 tick 全真模拟**搬出测试类**（用户裁定落盘）

> ★★ **本文的第一件事是补落一条从未被记录过的用户裁定**（控制方全仓溯源：`docs/` · `AGENTS.md` ·
> `test-world/README.md` **都搜不到原话**，只有 `test-world/` 这个机制存在）。
> 相关：`docs/agents/09-八-环境与运维.md:140-156`（全真世界 `~/Simos-18Lvt` 与仓内副本 `test-world/`）·
> `tools/gate.sh` · `docs/agents/08-七-门禁与关账命令.md`

---

## 0. 用户原话附录（逐字，2026-10-15）

> 「先停一停，我不是说，测试class里不要塞多tick吗？要跑全真世界模拟自己额外创世界跑，别塞进测试里！」

**口径（原话的机制化）**：
| 用户说的 | 含义 |
|---|---|
| 「测试 class 里不要塞多 tick」 | **JUnit 测试类**里**不许**跑多 tick 的世界推进 |
| 「要跑全真世界模拟**自己额外创世界**跑」 | 全真/多 tick 模拟 ⇒ 跑在**独立实例**上（本仓已有机制：`test-world/`） |
| 「别塞进测试里」 | 它**不属于门禁**（`clean verify`）该承担的东西 |

★ **本仓已有的对应机制**（`docs/agents/09-八-环境与运维.md:150-156` 原文）：
> **仓内代码测试副本（2026-10-02 用户指示，隔离）**：`test-world/`（Simos 仓内，`.gitignore` 排除）=
> 全真世界的**副本**，专用于代码测试：独立 store（`test-world/store/`）、独立端口 **GUI 5867 / MCP 5767 / 审批 5763**、
> 独立 systemd 单元 **`simos-testworld`**、独立日志；jar 用**本仓当前代码**构建。
> 起停 `test-world/scripts/{start.sh,stop.sh}`；夹在两边测试时**不要**把它的 store 指到 `~/Simos-18Lvt/world`、
> 也不要复用端口——那就不是隔离了。

---

## 1. 现状：违规者（控制方用**刚跑完的门禁报告**按类排名，实测数据）

```
411 个测试类 · 3307 用例 · 测试阶段累计 334s（门禁全程 6:47）
★★ 前 12 名占全部测试时间的 75%
   79.4s   1 用例  23.8%   EconomyCycleHealthTest            ← ★★ 违规者
   43.7s   5 用例  13.1%   GovGenesisZ6Test
   30.2s   3 用例   9.1%   GovPayToolTest
   26.1s   5 用例   7.8%   DecisionDocsApiTest
   13.3s   2 用例   4.0%   SegmentEquivalenceAcrossEconomyGovSocialTest  ← ★ 疑似违规
   11.6s  11 用例   3.5%   DmMessagingEndToEndTest
   11.5s  20 用例   3.5%   DecisionMakerApiTest
   10.6s  26 用例   3.5%   GmActAsToolTest
    6.6s   1 用例   2.0%   McpCoverageTest
    6.4s   8 用例   1.9%   GmDmLettersEndToEndTest
    5.8s  65 用例   1.7%   SimosToolsTest
    5.5s   1 用例   1.6%   CrossProcessDeterminismTest
```

### 1.1 ★★ `EconomyCycleHealthTest` —— 确定的违规者
类注**原文自证**（`:51` · `:99`）：
> 「种货币 / **6,350 人**）+ **真推进**（真 `Shell` + 真 `shell.advanceAndDrain(AdvanceTime)`）上跑 …」
> 「段数必须等于 **`240 / 30 = 8`**（`AdvanceTime` 被拒/被吞会让段数或 tick …）」

⇒ ★ **它在一个 JUnit 用例里推进 240 天全真世界（8 段 × 30 天）** ⇒ **吃门禁 23.8%**。

### 1.2 疑似（需逐个核）
- `SegmentEquivalenceAcrossEconomyGovSocialTest`（2 用例 13.3s）—— 名字即"分段等价"，多半也在多段推进
- `GovGenesisZ6Test`（5 用例 43.7s）—— 用的是 `GovZ6WorldFixture.smallWorldGenesis`，逐项钉 tick0/tick1 ⇒
  **可能是"小世界 + 1~2 tick"（可接受）**，**要核**：若它反复重放创世，那是**夹具开销**不是多 tick 模拟

### 1.3 不违规的（对照）
★ `src/test` 里 **52 个类**引用 `advanceAndDrain`/`AdvanceTime` —— 但绝大多数是
**"推进 1 tick 验契约"**（`TimeAdvanceTest` · `ReplayTest` · `OptimisticConcurrencyTest` …）。
⇒ ★ **判据不是"用没用 advance"，而是"是否跑了多 tick 的世界级模拟"**。

---

## 2. 待控制方定的判据（写下来免得后人各说各话）

| # | 判据 | 处置 |
|---|---|---|
| **M1** | 一个 `@Test` 里**推进 ≥ N 段/≥ M tick**（建议 **N≥2 段 或 M≥60 tick**） | ★ **违规** ⇒ 搬出 |
| **M2** | 测试里**重放创世 + 多 tick**（真 `Shell` + 真 `advanceAndDrain`） | ★ **违规** ⇒ 搬出 |
| **M3** | **单 tick** 推进验契约（含"推进 1 tick 看 revision 递增"） | ✅ **合规**，留在门禁 |
| **M4** | 纯函数/夹具级（不推进世界） | ✅ 合规 |
| **M5** | 门控真 LLM（`SIMOS_REAL_LLM=1`） | ✅ 合规（默认不跑，不占门禁） |

### 2.1 ★★ 用户裁定（2026-10-15）：**≥2 段 或 ≥60 tick = 违规**

| 判据 | 阈值 | 处置 |
|---|---|---|
| **M1** | 一个 `@Test` 里推进 **≥2 段** 或 **≥60 tick** | ★ **违规** ⇒ 搬出 JUnit |
| **M3** | 单 tick / 推进少量 tick 验契约（revision 递增、并发冲突、回放） | ✅ 合规，**留门禁** |
| **M4** | 纯函数/夹具级（不推进世界） | ✅ 合规 |
| **M5** | 门控真 LLM（`SIMOS_REAL_LLM=1`，默认不跑） | ✅ 合规 |

★ 「段」= `AdvanceTime` 的一次推进单位（`EconomyCycleHealthTest` 是 **30 天/段 × 8 段 = 240 天**）。
★ **判据不是"用没用 `advanceAndDrain`"** —— `src/test` 里 52 个类用它，绝大多数是 1 tick 契约验。

---

## 3. 处置方案（草案，待裁定后派）

1. ★ **把多 tick 模拟搬成"独立跑法"**：落在 `test-world/` 那一侧（独立 store / 端口 / 单元），
   形态 = **脚本 + 独立 runner**（不是 JUnit 类）；断言保留（该红还是红，只是不在门禁里红）。
2. ★ 测试类里**只留小规模 smoke**（建议 1~2 段 / 小世界）—— **保留判别力**，但把 8 段 240 天的重跑搬走。
3. ★ **门禁时间**要给出前后对比（当前测试阶段 **334s**；`EconomyCycleHealthTest` 一个就 79.4s）。
4. ★ **不许**用 `@Disabled` / `@Ignore` 把违规者"藏起来" —— 那就变成**假绿**（本段已治过三次同类病）。

---

## 4. 验收判据

| # | 判据 |
|---|---|
| **V1** | 门禁里**不再有**推进 ≥N 段/≥M tick 的用例（源码扫描 + 逐条核，**扫描器要自证**） |
| **V2** | 被搬走的那些断言**在新跑法里仍会红**（改坏 ⇒ 红，至少 1 条变异自证）—— 判别力**不许丢** |
| **V3** | 门禁**测试阶段耗时**给出前后实测对比 |
| **V4** | ★ 新跑法**有文档**（怎么跑、跑多久、看什么），落在 `test-world/README.md` 或 `docs/agents/08-七-…` |
| **V5** | 三格计数 / 桶锚点 / 其余 3307 用例**不受影响**（`clean verify` 仍 rc=0） |

### 2.2 ★ 边界个案的裁定（用户 2026-10-15）

`AdvanceStopProtocolTest#stoppedWorldIsReallyAtThatTickAndCanBeAdvancedFurther`（simos-core）
按字面踩「≥2 段」（4→10 = 6 天 + 6→10 = 4 天 = **10 tick**），但装置全是**玩具**
（`ToyCodec`/`ReportingParticipant`，**没有真世界**），两次推进是该"急停/续推"判据的**必要组成**。

► **用户原话（逐字）：「10tick 留着呗，不耗费多少时间」** ⇒ ★ **裁定：合规、不搬**。
- ★ **判据没有被改宽**：该用例**照字面报 `VIOLATION(ATTESTED)`**，理由具名登记在
  `tools/multitick-scan-attestations.txt`（含**过期即 rc=3**），并且**曾上交用户复核后才维持**。
- ★ **重审条件**：若它日后被改成**真世界装置**、或 tick 数上升 ⇒ 该豁免**须重审**（表头规则）。
- 另一条豁免（`TimeAdvanceTest#absurdlyLongAdvanceIsRejectedWithTheCapInTheReason`）是**扫描器假阳性**：
  36501 天超引擎上限（纯语法层拒）、36500 天那次装库探针当场抛 ⇒ 实际推进 **0 tick / 0 revision**。
