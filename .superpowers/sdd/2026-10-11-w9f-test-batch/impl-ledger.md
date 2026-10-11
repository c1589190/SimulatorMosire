# W9f 测试批（2026-10-11）：W9 全链（W9 + W9-fix + W9-fix2）测试面收口 + 同一把尺的最终读数

> 批次：**W9f 测试批**。写码批账本：`.superpowers/sdd/2026-10-11-w9-mode-switch/impl-ledger.md`（W9 / W9-fix / W9-fix2 三节）。
> 授权写面：`simos-economy/src/test/**`（**main 一字未改**；未 commit；未跑 `clean verify`）。
> 本轮字节：shaded jar md5 = **`ce0bf3ba8f388492b0f0ab8ae079c22c`**（= W9-fix2 的最终字节；`find simos-*/src/main -newer <jar>` = 空 ⇒ jar 与工作树 main 同源）。
> 统计窗口：真世界读数**逐条标注**（sw 0→360；tp 0→240 与 tp 0→360 分开；A 装置 = 进程内 JUnit）。
> 磁盘：跑前 43 G 可用；跑后清掉 818 MB（全仓 Maven 日志）+ 1.4 GB（tp360.log）等大件，原件读数已落 `evidence/`。

---

## 0. 一句话结论

1. ★★★ **① 真红点清单 = 空**：`tools/mvn-lock.sh test -am` 全仓 16 模块 **3,464 条 0 红**（4:22 min）。W9-fix2 账本 §8 推的 **6 个"可能红"候选类全部实跑绿**（含 `EconomyCycleHealthTest`）⇒ **没有一条需要迁移**。
   ★ 这不是"W9-fix2 没影响"，而是"**它的影响面在既有测试里没有入口**"：全仓只有 `ModeMigrationPolicyD024Test` 一处调 `ModeMigrationPolicy.plan(...)`，而它的目标格走"既有产业"支、**不进按需 trade 支**。本批据实补了入口（§4）。
2. **② 指纹重基线 = 未做（实测无漂移）**：`EconomyCycleHealthTest`（three-powers **0→240**，8 段 × 30 天）实跑绿 69.18 s，其自写摘要与 W9e 基线**逐值相同**（96 轮 / 零成交 0 / Σfills 3337 / 15·35.5·193 / 24 档分布逐值）。**五条牙一字未动、一个期望值也没改**。
3. ★★ **③ 两个世界都跑通 0→360**：sw **HTTP 200 / 10.650 s / Δrev=1 / dump md5 `89bbecfa…`（与 W9e 逐字节相同）**；tp **HTTP 200 / 48.654 s / Δrev=1 / dump md5 `838477e4…`（与 W9-fix2 逐字节相同）**。tp 0→360 的 0→240 子窗与 W7e/W9e **逐值相同**（96/0/3337/15·35.5·193）⇒ **W9-fix2 对 0→240 窗口零影响**，全窗新增量是 W9e 拿不到的那 120 天。
4. ★★★ **运粮口径收口**（§3.3）：唯一可比的一对是"**同事件 + 同世界 + 同窗口 + 同批次谱系**"。本轮实跑为准：**sw 0→360 = 7,525 笔 / 58,888,186 毫**（与 W9e 逐值相同）、**tp 0→240 = 602,011 / 4,607,203,136**（与 W9e 的 `tp240-extract.txt` 逐值相同）、**tp 0→360 = 925,565 / 4,656,075,024**。W7e 证据文件那个 13,551/61,879,715 是 **0→689 三段聚合**，且它自己的"窗口小计"逐窗打印 **0**（字面量解析口径不一致，原文见 §3.3）。
5. **④ 新用例 8 条**（`ModeMigrationWorkCapitalScaleW9fTest` 6 条 + `ModeMigrationPolicyD024Test` 补 2 条），补的正是 W9-fix2 的判据缺口：唯一拼写点逐值、上界 ≥ 执行期向下取整、**计划期预留 == 执行期索取**（含"少一份就该在计划期被拒"的负向）、人口闸边界、U5 与拼写点一的接缝、壳户不产出也不改别人计划。
6. **⑤ `GUI_REQUEST_FAILED` 的栈：拿到真 500 现场了**（不再只有渲染探针）：受控故障注入（删掉运行中实例的 store）⇒ `/api/advance` **HTTP 500** + 响应体 `{"error":"internal error"}` + WARN 事件行**紧跟完整栈**（`Replay.replay:181` ← … ← `GuiServer.handle:616`，17 行 `at`）。
7. **⑥ 变异自证 4/4 当场红、红在被守的那一行**；还原后 md5 逐值相同、重跑绿。★ 顺带实测踩到并记录了 AGENTS §三.5 的"陈旧 `.class`"陷阱（§6 末条）。

---

## 1. ① 全仓测试：真实红点清单（**空**）与"为什么是空"

### 1.1 实跑命令与结果（第一轮，**未改任何测试之前**）

```bash
cd /home/cna/SimulatorMosire
tools/mvn-lock.sh test -am            # 无 -pl ⇒ 全仓 16 模块；2026-10-11 21:28:51 → 21:33:14
# [INFO] BUILD SUCCESS   Total time: 04:22 min
# Reactor：Util/Map/Calendar/SocialApi/ActorApi/EconomyApi/Social/Unit/Core/SD/Actor/Economy/Gov/Army/App 全 SUCCESS
# 逐模块 Tests run：217 / 379 / 33 / 3 / 8 / 50 / 217 / 518 / 231 / 232 / 130 / 335 / 111 / 91 / 909(5 skip) = 3,464 条，Failures 0 / Errors 0
```

* **红点扫描**：`BUILD FAILURE` 行 = **0**；`Failures: [1-9]|Errors: [1-9]` 行 = **0**。
* **新鲜度核对（不信 rc=0，AGENTS §三.1）**：`simos-economy|simos-app/target/surefire-reports/*.txt` 共 194 份，其中 **194 份 mtime 落在本轮窗口**内（economy 21:29:28；app 至 21:32:11）。
* 证据：`evidence/01-fulltest-summary.txt`（反应堆摘要 + 逐模块合计 + 红点扫描 + 6 个候选类的 surefire 原文 + mtime 计数）。
* ★★ **本批测试改动落地之后的最终全仓轮**（同一命令，21:40:35 → 21:44:59）：**BUILD SUCCESS / 4:24 min**，economy 由 335 → **343**（+8 条新用例）、其余模块逐值不变，**合计 3,472 条 0 红**；`EconomyCycleHealthTest` 复跑绿（73.27 s）；两个改动类 8/6 全绿。证据：`evidence/10-fulltest-final-summary.txt`。
* ★ **格式门禁**：`tools/mvn-lock.sh spotless:check` rc=0（`spotless:apply` 只碰了本批的两个测试文件，`git status` 核对只有它们在改动面内）⇒ 控制方门禁不会因本批文件格式而红。证据：`logs/16-spotless-check.log`、`logs/17-spotless-apply.log`、`evidence/11-md5-after-spotless.txt`。

### 1.2 ★★ W9-fix2 §8 的"可能红"清单：**逐条实跑结果（全部绿）**

| # | 推断的类 | 推断理由（W9-fix2 §8） | 实跑 | 实测条数 |
|---|---|---|---|---|
| 1 | `MerchantOnDemandTradeW7Test` | 按需 trade 支预留规模 1 → ceil(labor/laborPerUnit)，夹具"携带 ≥120 即过"不再成立 | ✅ **绿** | 11 跑 0 红 |
| 2 | `ModeMigrationPolicyW9Test` | 逐值表 / U5 / 人口闸夹具若落在按需 trade 或 `≡999 (mod laborPerUnit)` 边界 | ✅ **绿** | 12 跑 0 红 |
| 3 | `ModeMigrationPolicyD024Test` | H2 夹具与"预期有候选"类断言可能因预留变严而变空 | ✅ **绿** | 6 跑 0 红（本批扩到 **8** 条，见 §4） |
| 4 | `ModeMigrationInventoryCarryW9Test` | 携带守恒用例若同时断言"该笔被计划" | ✅ **绿** | 3 跑 0 红 |
| 5 | `ModeMigrationGovServiceFailClosedTest` | 迁移 fail-closed 一族若以"按需 trade 能成"为前提 | ✅ **绿** | 6 跑 0 红 |
| 6 | `EconomyCycleHealthTest` | small-world/three-powers 世界级指纹可能漂 | ✅ **绿** | 1 跑 0 红（69.18 s） |

### 1.3 为什么"推断会红"而"实跑全绿"——**回代码核**（不是"运气好"）

W9-fix2 改的那一处（`perMoveNewRoom` 的**按需 trade 支**）在既有测试里**没有任何入口**，逐条具名：

| 事实（file:line / grep） | 后果 |
|---|---|
| `grep -rn "ModeMigrationPolicy.plan(" simos-economy/src/test` = **1 处**（`ModeMigrationPolicyD024Test:354`） | 全仓只有一个类走计划期路径；其余 5 个候选类验的是**执行期**（`ModeMigrationSettlement.apply`，自己 hand-build 一份 `MigrationPlan`）⇒ 根本不经过 `reserveIdleAssetsFor` |
| `ModeMigrationPolicyD024Test` 的目标格 H2 **有** `TARGET_INDUSTRY`（既有产业） | 走 `perMoveNewRoom` 的**既有模板支**（`for (IndustryId industryId : templates)`）⇒ 不碰被改的按需 trade 支 |
| `MerchantOnDemandTradeW7Test` 的夹具走 `ExpectedProfitBook.prospect` + `ModeMigrationSettlement.apply(...)`（`plan(move(...))` 是**手写计划**，见其 `:987` 的 `plan(MigrationMove...)`） | 同理：只碰执行期闸门，不碰计划期预留尺度 |
| `ModeMigrationInventoryCarryW9Test` / `ModeMigrationGovServiceFailClosedTest` 同类（执行期 fixture） | 同上 |
| `EconomyCycleHealthTest` 窗口 = **0→240**（`TOTAL_DAYS = 2 × CYCLE_DAYS = 240`） | W9-fix2 的唯一数值后果发生在 **day=270**（那一笔按需 trade 被计划期拒）⇒ **在窗口之外**；0→240 子窗逐值不变（§3.1 实测） |

⇒ **①的迁移清单为空，且这是可解释的空**（不是"测试没跑到"）。**本批不为迁就实现改任何断言**（派单 §一.11 的反面也成立：没有旧机制断言需要删）。

---

## 2. ② 指纹重基线：**未做**（实测无漂移；显式给出四个数）

| 项 | W7e 基线 | W9e（W9-fix） | **W9f（W9-fix2）实跑** | 判据 |
|---|---|---|---|---|
| 市场轮数 | 96 | 96 | **96** | 未漂 |
| ★ 零成交轮次 | 0 | 0 | **0** ✅ | **未漂**（>0 才禁止重基线） |
| Σfills | 3,651 | 3,337 | **3,337**（== `MARKET_FILL` 3,337 行） | 未漂 |
| min·中位·max | 15·42.0·198 | 15·35.5·193 | **15·35.5·193** | 未漂 |
| 24 档分布 | 24 档（旧） | W9e 新 24 档 | **与 W9e 逐值相同**（`15×1, 21×18, 22×8, 23×8, 24×8, 25×1, 27×1, 35×3, 36×14, 37×3, 38×3, 40×2, 41×5, 42×8, 43×1, 44×1, 45×2, 46×3, 47×1, 48×1, 49×1, 66×1, 167×1, 193×1`） | 未漂 |

* **装置 A**（进程内真 Shell + 真 `advanceAndTime`，8 段）：`Tests run: 1, Failures: 0, Time elapsed: 69.18 s`（本轮全仓跑内），其自写摘要 `simos-app/target/economy-cycle-invariant/summary.txt`（mtime **21:32**）原文落 `evidence/02-fingerprint-deviceA-summary.txt`：
  `★ W4b 加牙（逐市场轮）：轮次=96 零成交轮次=0 min/中位/max=15/35.5/193 Σfills=3337（== MARKET_FILL 3337 行）`。
* **装置 B**（真 HTTP + 独立 store，从创世新播）：本轮 tp 0→360 的 **0→240 子窗**独立抽取 = **96 / 0 / 3337 / 15·35.5·193**，与装置 A、与 W9e 的 `tp240-extract.txt` **三方逐值相同**（`evidence/05b-tp360-0-240-readings.txt`）。
* ⇒ **结论：无漂移 ⇒ 不重基线**。`EconomyCycleHealthTest` 的 `BASELINE_FILLS` / `BASELINE_MARKET_ROUNDS` 与 `assertEveryMarketRoundTraded` 的**五条牙一字未动**（本批对该文件的改动 = **0 行**；它在本批开工前已带 W9e 的重基线，属 W9e 的留痕）。
* ★ 反向确认（为什么"没红也要问为什么没红"）：W9-fix2 的数值后果是"day=270 那笔按需 trade **不进计划**"—— 0→240 窗口内**没有**按需 trade 目标（W9e/W9-fix2/W9f 的 0→240 `MERCHANT_TRADE_INDUSTRY_CREATED` 全为 0），⇒ 该窗口**不可能**被 W9-fix2 扰动。窗口之外的差异见 §3.1。

---

## 3. ③ 最终读数（六类；**同一把尺** /tmp/w6acc/w6run.py；两世界 0→360；`-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE`）

装置与口径：`run-worlds.sh`（本批，端口 10995-10998，store `/tmp/w9f/stores/*`，log-keep 白名单**与 W9e 逐字相同**）；抽取器 `extract9f.py`；**每个数都标窗口**。

| 世界 | 窗口 | HTTP | 墙钟 | Δrev | dump md5 | 规范化 state md5（canon2） |
|---|---|---|---|---|---|---|
| **small-world** | **0→360** | 200 | **10.650 s** | 1 | `89bbecfa44e5e6320507d781dd5577c4`（★ 与 W9e **同值**） | `5ec07665c26d37e4b3f04b9213102d77`（★ 与 W9e **同值**） |
| **three-powers** | **0→360** | 200 | **48.654 s** | 1 | `838477e41aaf5b9db732d12a424c7605`（★ 与 W9-fix2 **同值**） | — |
| small-world | 3 段 | 200 | 5.970+3.488+3.283 s | 3 | `d63c4e68770079e46959bac21724182f` | `5ec07665…`（同上） |
| small-world | 逐 tick | 200 | 43.675 s | 360 | `9cb8f3804e6f4d3067ebae9a6d25978a` | `5ec07665…`（同上） |

### 3.1 市场（轮数 / 零成交轮次 / Σfills / min·中位·max）

| 读数 | W7e（**tp 0→240**，旧基线） | **W9f tp 0→240** | **W9f tp 0→360** | **W9f sw 0→360** |
|---|---|---|---|---|
| 轮数 | 96 | **96**（逐值同 W7e） | 144 | 144 |
| ★ 零成交轮次 | 0 | **0** | **0** | **0** |
| Σfills | 3,651 | **3,337**（== `MARKET_FILL` 行） | 5,131（== 行数） | 1,751（== 行数） |
| min·中位·max | 15·42.0·198 | **15·35.5·193**（== W9e） | 15·36.0·193 | 3·9.0·168 |
| 同日两轮 / 最大间隔 | 否 / ≤5 | 否 / 3 天 | 否 / 3 天 | 否 / 3 天 |
| Σfills == `MARKET_FILL` 行数 | ✅ | ✅ | ✅ | ✅ |

★ 口径标注：**W7e 的 96·0·3,651·15·42.0·198 是 tp 0→240**（与 `EconomyCycleHealthTest` 同一装置同一窗口），所以只有 **tp 0→240** 那一列能与它并排；sw 0→360 的 144 轮**不是同一个窗口**，逐值不可比（只作"零成交 = 0 仍成立"的方向性读数）。★ W9 链（W9/W9-fix）把 tp 0→240 的 Σfills 从 3,651 改到 3,337（−8.6%），**W9-fix2 没有再动它**（3,337 → 3,337）。

### 3.2 转业（笔数 / 速度 / 档位 / 落点 / 随迁 / 壳户）

| 读数 | **sw 0→360** | **tp 0→240**（对照 W9e） | **tp 0→360** |
|---|---|---|---|
| `MIGRATION_MOVE` 笔数 | 222 | 233（W9e = 233 ✅） | 389（W9-fix2 = 389 ✅） |
| reason | `PROFIT_WEIGHTED` 222/222 | 233/233 | 389/389 |
| 速度 min/中位/max（‰） | 10 / 45.0 / **101** | 5 / 66 / **100** | 5 / 87 / **126** |
| 速度 = 1000‰ 的笔数 | **0** | **0** | **0** |
| `targetMode` | wage_farm 143 / tenancy 47 / handicraft 16 / **merchant 16** | wage_farm 158 / handicraft 38 / merchant 16 / family_farm 21 | wage_farm 176 / **merchant 120** / handicraft 72 / family_farm 21 |
| `targetTierIndex` 分布 | 0×211, 1×11 | 0×217, 1×9, 2×7（W9e 逐值相同） | 0×349, 1×33, 2×7（W9-fix2 逐值相同） |
| 单笔最大搬迁人口 / Σ人口 | 18 / 507 | 10 / 412 | 10 / 717 |
| **并入户 vs 新建户**（`MIGRATION_APPLIED`） | **12/12 轮 `rowsBefore == rowsAfter == 178`** | **8/8 轮 `342 == 342`** | **12/12 轮 `342 == 342`** |
| ⇒ 新建户行数 | **0** | **0** | **0** |
| 随迁库存 `MIGRATION_INVENTORY_CARRIED` | **222/222**（每笔都带） | 233/233 | 389/389 |
| ★ "从不带 haul" | **带 haul = 0 笔**（D2 排除生效） | 0 笔 | 0 笔 |
| 带 grain（粮随人走） | 156/222 | — | 383/389 |
| 壳户（轮级 Σ） | ΣnoPopulation **876** / ΣnoLabor **943**（12 轮） | — | ΣnoPopulation **1716** / ΣnoLabor **1424**（12 轮） |
| 逐户 `PROSPECT_INFEASIBLE` / 实物腿拒 | **0 行** | 0 行 | 0 行 |

★ 口径标注与如实说三条：① 档位分布的"真世界逐户"读数仍受 W9e §7.4 的同一条限制（日志里没有源户 `laborMilli` 栏 ⇒ "极小户落档 0"在真世界只有**分布旁证**，逐值判据在单测）；② **"新建户 0 个"是从 `rowsBefore == rowsAfter` 反推的**（`MIGRATION_APPLIED` 仍没有 `newHouseholds=` 栏，W9e/W9-fix2 同一条缺口）；③ 速度 max = 126‰ > 100‰ **不是**上限失效 —— 上限是**人口闸**（`可迁人口 ≤ 人口 × 100‰`），`transferSpeedPerMille` 报的是收益差算式输出，两栏在 `MIGRATION_MOVE` 里同时可读（W9-fix §3② 的口径）。

### 3.3 ★★★ 运粮（用户最关心）：笔数 / 金额，四批并排 + **口径收口**

**事件 = `MERCHANT_CAPACITY_LANE_TRUNCATED`（池侧逐 lane 未服务量），字段 `reason` + `unallocatedMilli`：**

| 批次（谱系） | 世界 / 窗口 | `no-carrier-with-deliverable-service` | `out-of-derived-radius` | 备注（口径） |
|---|---|---|---|---|
| W7 批 | sw **0→360（144 轮）** | **11,871 笔 / 61,432,735 毫**（★ 那是**另一个事件** `LOGISTICS_SERVICE_SUPPLY_REJECTED`，见下行"同批另一路"） | — | W7 账本 §"另一路读数" |
| W7 批（同批另一路） | sw 0→360 | **16,141 笔 / 65,142,802 毫** | — | ★ 这才是 W7 的 **LANE_TRUNCATED** 口径 |
| W7e（派单数） | sw 0:360 | **11,777 / 61,441,260** | — | ★ 与 W7 批的 11,871/61,432,735 近似但**不等**；与 W7e 自己的证据文件也不等 |
| W7e（**自己的证据文件**） | sw **(0,360)+(361,600)+(601,689) 三段聚合** | **13,551 / 61,879,715** | 2,672 / 3,319,569 | ★ 文件内同一份表的三行"窗口小计"打印为 **0 / 0 / 0**（见下"为什么对不上"） |
| W9d（W9 首版） | sw 0→360 | 13,230 / 116,786,642 | 6,227（另一口径） | W9 首版（收益差 1000‰ 爆炸的那一版） |
| W9e（W9-fix） | sw 0→360 | **7,525 / 58,888,186** | **4,074 / 5,476,991** | +`out-of-derived-radius+sub-unit-residual` 1,946 / 918,497 |
| **W9f（W9-fix2）本轮实跑** | **sw 0→360** | **7,525 / 58,888,186**（★ 与 W9e **逐值相同**） | **4,074 / 5,476,991**（同上） | 另有 `capacity-exhausted` 42/87,945、`sub-unit-residual` 29/664 |
| **W9f（W9-fix2）本轮实跑** | **tp 0→240** | **602,011 / 4,607,203,136**（★ 与 W9e 的 `tp240-extract.txt` **逐值相同**） | **838,368 / 2,769,023,731** | 同窗口同事件 ⇒ 可与 W9e 直接比 |
| **W9f（W9-fix2）本轮实跑** | **tp 0→360** | **925,565 / 4,656,075,024** | **926,357 / 2,809,340,544** | ★ W9e 拿不到这一段（它在 day=270 崩了） |

**★ 口径收口（三条，逐条具名）**：

1. **数字对不上的三个原因**（不是"谁抄错了"）：
   * **窗口不同**：W7e 那两份证据文件的表头写得很清楚 —— `event = MERCHANT_CAPACITY_LANE_TRUNCATED  窗口 = [(0, 360), (361, 600), (601, 689)]` ⇒ **13,551/61,879,715 是 0→689 天的三段聚合**，不是 0→360；而派单/批次表里的数是按 0:360 给的 ⇒ 两者**本来就不该相等**。
   * **事件/计数口径不同**：0→360 那一段，W7 批里 `LOGISTICS_SERVICE_SUPPLY_REJECTED` = 11,871/61,432,735、`MERCHANT_CAPACITY_LANE_TRUNCATED` = 16,141/65,142,802 —— **两个事件两个数**；派单的 11,777/61,441,260 更接近前者（差 94 笔 / 9,475 毫），说明它来自**另一个事件/另一次运行**。
   * **字面量解析口径**：W7e 证据文件里理由串带前缀 **`other|`**（`other|no-carrier-with-deliverable-service`），而该文件的"窗口小计"是按**不带前缀**的字面量匹配的 ⇒ 逐窗小计打印 **0**。这条**只影响聚合脚本的读数，不影响主数据**（主数据行在文件里是真数）。
2. **唯一可直接比的一对（本轮为准）**：`no-carrier-with-deliverable-service`，**同事件 + 同世界 + 同窗口 + 同批次谱系**：
   * sw 0→360：W9e **7,525 / 58,888,186** vs W9f **7,525 / 58,888,186** ⇒ **逐值相同**（W9-fix2 对 sw 零影响，与 dump md5 逐字节相同互为佐证）；
   * tp 0→240：W9e 的 `tp240-extract.txt` **602,011 / 4,607,203,136** vs W9f **602,011 / 4,607,203,136** ⇒ **逐值相同**。
3. **本轮实跑的数作为准**（用户要的那两个数）：
   * **sw 0→360：`no-carrier-with-deliverable-service` = 7,525 笔 / 58,888,186 毫；`out-of-derived-radius` = 4,074 笔 / 5,476,991 毫。**
   * **tp 0→240：602,011 笔 / 4,607,203,136 毫；`out-of-derived-radius` = 838,368 笔 / 2,769,023,731 毫。**
   * **tp 0→360：925,565 笔 / 4,656,075,024 毫；`out-of-derived-radius` = 926,357 笔 / 2,809,340,544 毫。**
   * ★ 另一口径（`CAPACITY_DEMAND_GAP_WHY`，理由名 = `capacity-budget-below-demand-or-out-of-derived-radius`，F2 合并后的唯一拼写点）：sw 297 笔 / 67,084,135 毫；tp 0→360 5,328 笔 / 8,065,671,505 毫。**与 `LANE_TRUNCATED` 不是同一个量**（一个是"缺口需求"、一个是"逐 lane 截断量"）⇒ 不可混比。
   * ★ **方向性结论（如实）**：sw 的笔数相对 W7 批同窗口同事件（16,141）**降了 53%**、金额（65,142,802）**降了 9.6%**；这是 W9 链（速度/落点/上限）改了世界行为的结果，**不是** W9-fix2 的（W9-fix2 对 sw 逐字节零影响）。tp 0→360 是**本轮新拿到的窗口**，没有同口径的前批读数可对照。

### 3.4 跑商（逐户：谁、哪天、哪个格、带了多少）

| 读数 | sw 0→360 | tp 0→240 | tp 0→360 |
|---|---|---|---|
| `MERCHANT_TRADE_INDUSTRY_CREATED`（按需建商号） | **0** | 0 | **0**（W9e 崩前 0→270 = 1，正是肇事那条） |
| `ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE`（缺工具 ⇒ 买/租） | **0** | 0 | **0** |
| `ONDEMAND_TRADE_MIGRATION_SKIPPED`（买不到 ⇒ 具名跳过） | **0** | 0 | **0** |
| `MIGRATION_MOVE targetMode=merchant` | **16**（1 个源户） | 16 | **120**（19 个 源户×格 组合） |

* **sw 逐户明细（全部）**：`hh-0_0-urban-middle_peasant → hex=0,0`，**16 笔** / Σ人口 133 / Σ钱 416 毫；发生在 day = 120,150,180×2,210×2,240×2,270×2,300×2,…（每 30 天一轮，落在**并入既有户**支 —— 该格 trade 产业存在）。
* **tp 逐户明细（0→360，19 行全表在 `evidence/05-tp360-readings.txt`）**，前 6 行为：
  ```
  hh--3_3-urban-middle_peasant → hex=-3,3  15 笔 / Σ人口 43 / Σ钱 1490 毫
  hh-3_0-urban-middle_peasant  → hex=3,0   11 笔 / Σ人口 59 / Σ钱 1988 毫
  hh--3_3-rural-rich_peasant   → hex=-3,3  10 笔 / Σ人口 10 / Σ钱    0 毫
  hh-3_0-rural-rich_peasant    → hex=3,0   10 笔 / Σ人口 10 / Σ钱    0 毫
  hh-0_-3-urban-middle_peasant → hex=0,-3   9 笔 / Σ人口 43 / Σ钱 1357 毫
  hh-0_-3-rural-middle_peasant → hex=0,-3   8 笔 / Σ人口 11 / Σ钱  138 毫
  ```
* ★ **如实说**：跑商"缺工具 ⇒ 买/租"那条新通道（W9 用户裁定②）在**两个世界的真世界里仍然 0 次触发**，且 `MERCHANT_TRADE_INDUSTRY_CREATED = 0` ⇒ 那道闸门**没被进入**（不是"买不到"——买不到会有具名 `ONDEMAND_TRADE_MIGRATION_SKIPPED` 行，也是 0）。这与 W9-fix2 的改动方向一致（那笔按需 trade 的作业资本不够，计划期就把它拒了）。

### 3.5 不回归

| 项 | 结果 | 证据 |
|---|---|---|
| **B2 分段等价**（一次 / 3 段 / 逐 tick） | ✅ **三形态规范化 state md5 逐值相同** `5ec07665c26d37e4b3f04b9213102d77`；`canon2.py diff` **差异叶 0 / 总叶 81,703**（两次对比都是 0） | `evidence/03-b2-normalized-md5.txt` |
| **Revision Δ** | 一次 `advance(0→360)` = **Δ1**（rev 1→2）；3 段 = **Δ3**；逐 tick = **Δ360** | `logs/*.result.json` |
| **性能（单次墙钟）** | **sw 0→360 = 10.650 s**（W9e 8.226 s，+29%）；sw 3 段 = 5.970+3.488+3.283 s；sw 逐 tick = 43.675 s（≈0.121 s/天，W9e ≈0.097）；**tp 0→360 = 48.654 s（≈135 ms/天；W9e tp 0→240 = 141 ms/天、W9-fix2 = 136 ms/天）** | `evidence/04/05*` 与 `logs/03-worlds.out` |
| **W4 阶层滑落** | sw：`CLASS_SLIDE_ROUND/SETTLED` 144/144、`CLASS_SLIDE` 44、`CLASS_TRANSITIONS` 1；tp：144/144、53、1（`SettlementOrderBook`/`ClassSlideSettlement` 本链未改） | `evidence/04/05*` |
| **W5 铸币** | sw：`MINT_SETTLED` 3、`MINT_LIMIT_WINDOW` 9、`MINT_LAST_CYCLE_READOUT` 1；tp：全 0（three-powers 无铸币产业 ⇒ 货币理应不变） | 同上 + §3.6 |
| **W5.5 货币市场** | sw：`MONEY_MARKET_{ROUND,WHY,UNFILLED,RATE_WRITTEN,GOV_SUPPLY_SUPPRESSED,BUDGET_TOPUP}` = 144 **全齐**；tp：144/432/1175/144/144/106 | 同上 |
| **W7 运力/运费** | sw：`HAUL_PRICE_SIGNAL` 144、`HAUL_SERVICE_DELIVERED` 438；tp：432 / 333；`MERCHANT_CAPACITY_*` 一族齐全（§3.3） | 同上 |
| **W8 债务语义** | sw：`DEBT_REPAY_DECISION` 3786、`DEBT_STRESS_THRESHOLD_FIRED` 872、`MIGRATION_DEBT_MOVED` 118、`MIGRATION_DEBT_SELF_NETTED` 41；tp：12480 / 1694 / 290 / 170 | 同上 |
| **异常 / 500** | 两个世界 `GUI_REQUEST_FAILED` = **0**；名字含 `EXCEPTION/ERROR/FAILED/STACK` 的事件 = **（无）** | 同上 |

### 3.6 守恒（作业资本份额 / 商品库存 / 货币逐币种）

装置：用 store 里的 checkpoint（tick 1 = 创世后、tick 2 = 360 天）经 `w6lib.extract` + `extract9f.py --conservation`；证据 `evidence/06-conservation.txt`。

| 项 | sw 0→360 | tp 0→360 |
|---|---|---|
| **货币逐币种** | copper 100,000 → 100,000（Δ0）；silver 267,200 → **268,700（Δ+1,500）** | copper 136,600 → 136,600；gold 129,400 → 129,400；silver 424,600 → 424,600（**Δ 全 0**） |
| **Δ 的可解释性** | ★ Δsilver **+1,500 == `moneyIssuances` 里窗口内唯一一笔发行** `MINT_PRODUCTION{silver:1500}`（逐币种 Δ == 窗口内非创世发行 ⇒ **True**） | 窗口内**没有任何**非创世发行 ⇒ Δ 必须为 0，实测为 0 ⇒ **True** |
| **作业资本产权份额（assetShares × AssetKind 求和）** | LAND 54,758,400 → 54,758,400；**TOOL 24,192 → 24,192**；WORKSHOP 21 → 21（**逐值不变**；份额条数 162 → 183 = 拆分，不增不减） | LAND 109,523,000 → 109,523,000；**TOOL 36,262 → 36,262**；WORKSHOP 16 → 16（逐值不变；条数 271 → 343） |
| **商品库存总量**（毫） | grain 392,150,000 → 471,553,201；cloth 200,000 → 2,270,950；fiber 329,736,000 → 406,180,620；tool 504,000 → 508,147；haul 0 → 110,729；iron 200,000 → 200,000 | grain 512,337,500 → 1,071,197,503；cloth 300,000 → 2,552,599；fiber 658,080,000 → 908,808,960；tool 920,000 → 895,495；haul 0 → 186,575；iron 160,000 → 160,000 |
| **人口 / 户数** | social 4,800 → 3,876 == economy 4,800 → 3,876；**户数 178 → 178**（⇒ 新建户行 0 个，与 §3.2 反推一致） | social 6,350 → 5,434 == economy 6,350 → 5,434；**户数 342 → 342** |
| ★ 如实说 | 商品**总量**在世界上本来就不守恒（每 tick 生产/消费），这里只作读数；"源减 = 目标增"只能在**转移层**读（`ModeMigrationInventoryCarryW9Test` 逐值钉住，本批复核 3 条全绿） | 同左 |

---

## 4. ④ 补判据测试（含负向 + 关键项变异自证）

### 4.1 新增/改动清单

| 文件 | 改动 | 为什么必须有 |
|---|---|---|
| `simos-economy/src/test/java/io/mosire/simos/economy/time/ModeMigrationWorkCapitalScaleW9fTest.java` | **新建，6 条**（①拼写点一逐值 + 下限；②`laborPerUnit ≤ 0`；③拼写点二逐值；④上界 ≥ 执行期向下取整且 ≤ +1；⑤人口闸边界与退化输入；⑥U5 与拼写点一的接缝） | W9-fix2 的**两个唯一拼写点**在既有测试里**零入口**（§1.3） |
| `ModeMigrationPolicyD024Test.java` | **+2 条**：`thePlanPhaseReservesExactlyTheWorkCapitalThatExecutionWillClaim`、`aShellHouseholdProducesNoMoveAndDoesNotChangeAnyoneElsesPlan`；夹具加第 8 参 `h2ShareQuantity`（默认 100 = HEAD 逐值，既有 6 条的形态与期望**未变**） | 前者的负向 = "预留少一份 ⇒ **计划期就该拒**"（W9-fix2 那条具名不一致的可执行判据）；后者补"壳户跳过" |

### 4.2 逐条判据与出处

| 用例 | 判据（自证算式，不写死） | 出处 |
|---|---|---|
| `requiredWorkCapitalScaleIsTheFloorOfLaborOverPerUnitWithAFloorOfOne` | `max(1, ⌊labor ÷ laborPerUnit⌋)` 对 9 个 labor 值逐值；**真世界锚 8857 ⇒ 8 ⇒ need 8×120 = 960 毫工具** | W9-fix2 账本 §3.2/§3.1；three-powers day=270 现场 |
| `requiredWorkCapitalScaleTreatsNonPositiveLaborPerUnitAsASingleUnit` | `laborPerUnit = 0` ⇒ 恒 1（含 `Long.MAX_VALUE`）；**反面对照** `laborPerUnit = 1` ⇒ scale 随 labor 增长 | 同上（`≤ 0 ? 1 : …` 那一支） |
| `laborCarriedByMoveUpperBoundRoundsUpAndFallsBackToTakeWithoutPopulation` | `⌈L×take ÷ P⌉` 11 行（含整除 / 非整除 / L=0 / take=0 / P ≤ 0 兜底 / `L=Long.MAX_VALUE` 不溢出） | W9-fix2 账本 §3.2 第二半 + §9.3 |
| `thePlanSideLaborEstimateIsNeverBelowTheExecutionSideFloorAndNeverAboveItByMoreThanOne` | 210 组 (L,P,take)：`ub ≥ ⌊…⌋` ∧ `ub − ⌊…⌋ ≤ 1`；**判别力自检**：族内必须存在 `ub > floor` 的行 | 同上（"语义有歧义 ⇒ 按较大者预留"） |
| `thePopulationGateIsTheSmallerOfTheSpeedFoldAndTheCapFold` | `min(moveablePopulation(p,速度), moveablePopulation(p,100))` 12 行（含"上限刚好等于速度"、速度 0、人口 0、负人口）；+ 负向"不加闸 200 人全走 vs 加闸 20 人" | W9-fix §3② + 控制方裁定 v3.32 §7②；`ModeMigrationPolicy.java:744-746` |
| `aHouseholdBelowOneLabourUnitLandsTheBottomTierButStillReservesOneWorkCapitalUnit` | `labor < laborPerUnit` × 携带 0/120/7,000/200,000 ⇒ 档位 **恒 0**；同一户 scale **== 1**；**反面对照** `labor = 1 单位规模 + 充足作业资本 ⇒ 档位 > 0` | W9-fix §3④（U5）；`supportedTierIndex` |
| `thePlanPhaseReservesExactlyTheWorkCapitalThatExecutionWillClaim` | ① 富余夹具读出 `take` ⇒ 用**与 main 同形、独立重写**的算式算 `laborEstimate = laborCarriedByMoveUpperBound(...)`、`need = requiredWorkCapitalScale(模板, laborEstimate) × capacityPerUnit[WORKSHOP]`；② **`need − 1` ⇒ 计划必须空**（负向）；③ `need` ⇒ 计划回来且 `take` 逐值相同 | W9-fix2 账本 §3.3（"两侧同式同量"）+ 派单 ④"估计不足时计划期就该拒" |
| `aShellHouseholdProducesNoMoveAndDoesNotChangeAnyoneElsesPlan` | ① 壳户不是任何一笔的源户；② 整份计划（source→target/mode/pop/speed 规范串）与**无壳户**夹具**逐值相同**；③ 计划只读；+ 判别力自检"无壳户的计划必须非空" | `ModeMigrationPolicy.java:457-460` + 用户派单 ⑥；真世界读数面 = `MIGRATION_CANDIDATES.skippedShellNoPopulation` |

★ **随迁守恒（含整户迁空）** 与 **U5 档位逐值** 的既有覆盖**本批复核为绿、未重复新写**（避免造第二套同义判据）：`ModeMigrationInventoryCarryW9Test`（3 条：按人口比例部分迁移逐商品守恒 / **整户迁空带走全部且源户不剩** / 同一源户两笔读活表）与 `ModeMigrationPolicyW9Test`（`theBottomTierNeverRequiresAnyInventory` + `tierBoundariesAreExactOnBothSides`）。
★ **四类关键项（守恒式 / 不丢失 / 静默付 0 / 断粮）**：本批覆盖到的是 **守恒式**（§3.6 + `ModeMigrationInventoryCarryW9Test`）与 **不丢失/不静默**（"缺口 ⇒ 计划期拒 / 执行期具名跳过"，§4.2 第 7 条与 `ONDEMAND_TRADE_MIGRATION_SKIPPED` 一族）；**"静默付 0" 与 "断粮" 两类在本批的改动面上没有可构造的负向**（W9-fix2 不碰支付与口粮口径）—— 如实记为**未覆盖**（§7）。

### 4.3 测试面实跑结果

```
tools/mvn-lock.sh test -pl simos-economy -am \
  -Dtest='ModeMigrationPolicyD024Test,ModeMigrationWorkCapitalScaleW9fTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
# ModeMigrationPolicyD024Test               Tests run: 8, Failures: 0, Errors: 0
# ModeMigrationWorkCapitalScaleW9fTest      Tests run: 6, Failures: 0, Errors: 0
# BUILD SUCCESS
```
★ 第一轮曾红 1 条（`Industry.laborPerUnit 不得为负: -1`）—— 那不是实现缺陷，是**判据写错**：`laborPerUnit < 0` **根本构造不出来**（`Industry` 规范构造器自己 fail-closed）。已按实跑更正为"① 只测 `== 0` 那一支；② 把'负数构造不出来'也钉成断言"。

---

## 5. ⑤ `GUI_REQUEST_FAILED` 的证据强度复核：**渲染探针 → 真 500 现场（已补）**

* W9-fix2 的证据是**渲染探针**（同 jar、同 log4j2、同 logger 名，手工调 `warn(line, throwable)` 证明栈会打出来）+ 12 发负向请求全落 400/404/409/422。**探针 ≠ 真 500** —— 这一点 W9-fix2 自己也如实写了。
* **本批补到真样本**（受控故障注入，**不改 main**）：起服务（small-world、端口 11001、独立 store、`-Dsimos.app.logLevel=INFO`）→ `advance 0→10`（HTTP 200，落 checkpoint 1.json/2.json）→ **`rm -rf <store>`**（运行中实例的存储消失）→ 同实例 `advance 10→20`：
  ```
  HTTP 500      响应体 {"error":"internal error"}      （与契约一致：栈不进响应体）
  21:39:27.338 WARN  io.mosire.simos.app.gui - event=GUI_REQUEST_FAILED origin=gui-request originKind=interaction
      method=POST path=/api/advance caller=127.0.0.1 error=IllegalStateException
      message=重放回退到父链的根仍无可用 checkpoint（…）: main@1
  java.lang.IllegalStateException: 重放回退到父链的根仍无可用 checkpoint（…）: main@1
      at io.mosire.simos.core.store.Replay.replay(Replay.java:181)
      at io.mosire.simos.core.CoreSimos.load(CoreSimos.java:481)
      at io.mosire.simos.core.advance.TimeAdvance.run(TimeAdvance.java:228)
      at io.mosire.simos.core.command.CommandBus.submit(CommandBus.java:226)
      at io.mosire.simos.core.CoreSimos.submit(CoreSimos.java:195)
      at io.mosire.simos.app.gui.GuiServer.advanceReply(GuiServer.java:1697)
      at io.mosire.simos.app.gui.GuiServer.handlePost(GuiServer.java:941)
      at io.mosire.simos.app.gui.GuiServer.handleApi(GuiServer.java:714)
      at io.mosire.simos.app.gui.GuiServer.handle(GuiServer.java:616)
      at jdk.httpserver/com.sun.net.httpserver.Filter$Chain.doFilter(Filter.java:98)
      at jdk.httpserver/sun.net.httpstext.AuthFilter.doFilter(AuthFilter.java:82)
  （日志里 `\tat ` 行共 17 行）
  ```
* **结论**：`GUI_REQUEST_FAILED`（WARN）**确实自带完整异常栈**，且是**真 500 现场**（内层异常 = `core.Replay` 的 `IllegalStateException`，与 W9e 那次 day=270 的**同一异常类**）。★ 纪律不变：栈只进日志、**不进响应体**（响应体仍是 `{"error":"internal error"}`）。
* ★ 装置与收工：`gui500.sh`（脚本落 `.superpowers/sdd/2026-10-11-w9f-test-batch/gui500.sh`）；`kill <PID>` 收工（**未用 `pkill -f`**）；日志 `logs/gui500.log`；证据 `evidence/07-gui500-real-stack.txt`。
* ★ 如实说：这次的真 500 是**存储故障注入**（磁盘消失），**不是** W9e 那条"业务守卫抛"——两者的**异常类相同**（`IllegalStateException`）且**经过的 GUI 捕获路径完全相同**（`GuiServer.handle` 的 `catch (Exception)` ⇒ `GUI_REQUEST_FAILED` + `safeError(500)`），所以对"栈会不会打出来"这个命题是**充分证据**；但"下一次业务守卫抛出时栈也在"仍由同一段代码保证（非本次实测）。

---

## 6. ⑥ 变异自证（4 处测试侧变异 ⇒ 全部当场红 ⇒ 还原 ⇒ md5 逐值相同）

| # | 变异（测试侧） | 期望红点 | 实测（原文摘要） |
|---|---|---|---|
| M1 | `...W9fTest` 真世界锚 `8857 ⇒ 8` 改成 `9` | 拼写点一逐值表 | ✅ `requiredWorkCapitalScaleIsTheFloorOfLaborOverPerUnitWithAFloorOfOne:101` —— 消息里 `⌊8857 ÷ 1000⌋ = 8`（**红在被守的那一行**） |
| M2 | `...W9fTest` 人口闸表 `(200, 1000‰) ⇒ 20` 改成 `200` | 人口闸边界 | ✅ `thePopulationGateIsTheSmallerOfTheSpeedFoldAndTheCapFold:267` —— 消息里 `min(速度折算 200, 上限折算 20) = 200` |
| M3 | `...W9fTest` 上界表 `(10, 4, take 1) ⇒ 3` 改成 `2`（= 向下取整的读法） | 向上取整逐值 | ✅ `laborCarriedByMoveUpperBoundRoundsUpAndFallsBackToTakeWithoutPopulation:182` —— `⌈10÷4⌉ = ⌈2.5⌉ = 3` |
| M4 | `D024Test` 计划级负向夹具 `need − 1` 改成 `need`（"少一份"变成"恰好够"） | 计划期预留负向 | ✅ `thePlanPhaseReservesExactlyTheWorkCapitalThatExecutionWillClaim:573` —— 消息里 `只给 need − 1 = 0 单位作业资本 ⇒ 计划必须为空`（★ 顺带暴露该夹具里 **need = 1**：源户 100 人 / 劳动 1000 ⇒ 计划期劳动估计 < 1 个 `laborPerUnit`，尺度取下限 1 ⇒ 边界落在 1 vs 0） |
| — | 还原 | — | `evidence/08-md5-mutation-baseline.txt` 与 `09-md5-after-restore.txt` **两对两 md5 逐值相同**（`4ea050678e36514bba294df9456edb07` / `f978a4eddb046ce1a13fc97e53ce5a04`）；还原后 13 条全绿 |

★ **注意一处字节变更的留痕（透明记）**：上表 md5 是**spotless 之前**的字节。之后 `spotless:apply` 只重排了 javadoc 折行 ⇒ 两个文件的新 md5 = `9c91c927b8dc346cd8f2b5004a2a9975`（新类）、`0b4643c1f2e3b247c4faecf983387459`（D024Test），`spotless:check` rc=0，两个类复跑 8/6 全绿（`logs/18-after-spotless-test.log`）。**语义未变，只有换行位置变**。

★★ **顺带实测踩到并记录 AGENTS §三.5 的"陈旧 `.class`"陷阱**（本批真发生过，代价一轮）：
第一轮变异跑完 → `cp -p` 还原（**保住了原 mtime**）→ 重跑**仍然全红**（4 个 Failure 与变异轮逐字相同）。判别实验（决定性、且便宜）：`ls -la` 显示**还原后的源文件 mtime(21:39:34) 比变异轮编出来的 `.class`(21:39:41) 旧** ⇒ `maven-compiler-plugin` 的增量判据（源 mtime vs 类 mtime）**跳过重编译** ⇒ 跑的还是变异体。`touch` 两个源文件后重跑 ⇒ 全绿。**教训**：还原变异体时 `cp -p` 会骗过增量编译器；要么 `touch`，要么 `rm -rf <模块>/target/test-classes`。
★ **做不到的（如实说）**：**没有**对 `src/main` 做变异（派单明令 main 禁改）。⇒ "把 `requiredWorkCapitalScale` 改回旧的读取口径（落盘行 labor=0）⇒ 新用例必须红"这条**最有价值的变异体本批没做**；它的判别力由 ①**真世界的自然对照**替代：同一笔按需 trade 在 W9-fix（旧口径）下于 day=270 **撞守卫抛 HTTP 500**，在 W9-fix2（新口径）下**被计划期拒**（`MERCHANT_TRADE_INDUSTRY_CREATED` 1 → 0，`targetHex=2,0 ∧ targetMode=merchant` 笔数 1 → 0）。

---

## 7. ⑦ 我没做 / 没验证的

1. **没有跑 `clean verify`**（派单明令，归控制方门禁）⇒ spotbugs/checkstyle/前端门禁**未复核**；`test` 阶段不含它们。★ 唯一补做的是 **`spotless:check`（rc=0）**，见 §1.1 末条 —— 那是为了不把自己的格式问题留给门禁。
2. **没有对 `src/main` 做任何变异**（派单明令）⇒ §6 末条那条最有价值的变异体缺席，只有"真世界自然对照"替代。
3. **"静默付 0"与"断粮"两类关键项：本批没有构造负向**（W9-fix2 不碰支付/口粮口径）⇒ 未覆盖，见 §4.2 末条。
4. **"新建户数" 仍未进轮级日志**：`MIGRATION_APPLIED` 里没有 `newHouseholds=` 栏 ⇒ "0 个新建户"是靠 `rowsBefore == rowsAfter` **反推**（与 W9e §7.10 / W9-fix2 §10.7 同一条缺口；本批**只报不改**）。
5. **U5 的"真世界逐户"样本仍没有**：日志面没有源户 `laborMilli` 栏 ⇒ 真世界只有档位分布旁证（sw 0×211/1×11；tp 0×349/1×33/2×7），逐值判据在单测（§4.2 第 6 条）。
6. **`ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE` 真样本仍没有**（两世界该通道 0 次触发，按需商号 0 个）⇒ "缺工具可买"这条通道**只在单测层面被验**（`MerchantOnDemandTradeW7Test` 的 `migrationWithoutCarriedToolIsAllowedWhenToolIsTradedAndIdleInHex`），真世界是"没被进入"，不是"买不到"。
7. **600 天窗口 / 多进程逐字节确定性：只做了 0→360**（三形态 B2 + tp 与 W9-fix2 逐字节相同、sw 与 W9e 逐字节相同 = 跨批次确定性成立；但没有 600 天读数）。
8. **W7e 的 11,777 / 61,441,260 没有追到源头**：本批把"为什么对不上"讲清了（窗口/事件/字面量三条，§3.3），但**没有**去 W7e 的运行产物里逐行重算那个数（W7e 的原始 tp/sw 大日志已按磁盘纪律删除）⇒ 该数仍只作"不可直接比"的并列项。
9. **sw/3seg/tick 三个 store 的 checkpoint 只用于 B2 与 sw 守恒**；`swtick` 的 1.5 GB store 与本批的 1.4 GB tp 日志**已删**（读数落 `evidence/`）。
10. **`GUI_REQUEST_FAILED` 的真 500 是"存储故障注入"而非"业务守卫抛出"**（§5 末条已如实标注）。

---

## 8. ⑧ 未完成 / BLOCKED

| 项 | 状态 | 说明 |
|---|---|---|
| ① 真红点清单 + 迁移 | ✅ **完成（红点 = 空）** | 全仓 3,464 条 0 红；6 个推断候选全绿且给出"为什么"（§1.3）；**没有为迁就实现改任何断言** |
| ② 指纹重基线 | ✅ **完成（判定 = 不重基线）** | 实测无漂移（四个数与 24 档分布逐值同 W9e）；五条牙一字未动 |
| ③ 六类最终读数 | ✅ **完成** | 两世界 0→360 + B2 三形态 + 守恒；运粮口径收口（§3.3）给出"本轮实跑为准"的三个数 |
| ④ 补判据测试 | ✅ **完成（+8 条）** | 两个唯一拼写点逐值 + 计划↔执行一致性的负向 + 人口闸边界 + U5 接缝 + 壳户；守恒/整户迁空/U5 逐值复核既有覆盖为绿 |
| ⑤ 真 500 栈 | ✅ **完成** | 受控故障注入拿到 HTTP 500 + 完整栈（§5） |
| ⑥ 变异自证 | ✅ **4/4 红 + 还原 md5 相同** | main 侧变异按纪律不做（如实记） |
| `clean verify` / spotbugs / checkstyle | ❌ 未做 | 派单明令（归控制方门禁）；**`spotless:check` 已单独跑过且 rc=0** |
| `git commit` | ❌ 未做 | 派单明令 |
| 磁盘 / 进程 | ✅ 已清 | 818 MB + 1.4 GB 大日志已删；服务进程逐个 `kill <PID>` 收工（未用 `pkill -f`）；`pgrep -af "shaded.jar"` 空 |

**BLOCKED 判定：无。**

---

## 9. 上报（只报不改）

| # | 事项 | 证据 / 最小复现 | 影响 |
|---|---|---|---|
| **F1** | ★★ **W9-fix2 的改动面在既有测试里零入口**：全仓只有 `ModeMigrationPolicyD024Test` 一处调 `ModeMigrationPolicy.plan(...)`，且其目标格走"既有产业"支 ⇒ `perMoveNewRoom` 的**按需 trade 支**（W9-fix2 唯一改的那条路径）**没有测试覆盖** | `grep -rn "ModeMigrationPolicy.plan(" simos-economy/src/test` = 1 处；§1.3 的表 | 这解释了"W9-fix2 一条测试没跑却全绿"；本批已补入口（§4），但**真世界那条按需 trade 路径仍无真样本**（作业资本不够 ⇒ 计划期拒） |
| **F2** | **计划期 `carriedCarrierAssetsForMove` 的估计 ⊇ 执行期 `migrateAssetsForMove` 的实际覆盖**（W9-fix2 §5.4 点名的第二处对偶）**未修**：`reserveIdleAssetsFor` 的 `available = carried + idle` 会把"跨 hex 不可移动 / 无宿主产业 / 已被外组织使用"那三部分也算成可用 ⇒ 极端情形仍可能**少预留** ⇒ 同一守卫（`ModeMigrationSettlement.java:1201`）仍可能被撞 | W9-fix2 账本 §5.4（逐条列出三道排除） | 同一类缺陷（"计划期假设 vs 执行期读法"）**仍在**；本批**只报不改** |
| **F3** | **`MIGRATION_APPLIED` 缺"新建户数"栏**，导致 U-D（新建户支是否可达/是否被过修）只能靠 `rowsBefore == rowsAfter` 反推 | §3.2 与 W9e §7.10、W9-fix2 §10.7 同一条 | 读数面缺口；建议下一批给该事件加 `newHouseholds=` |
| **F4** | ★ **W7e 运力证据文件自身的"窗口小计"是坏的**（理由串带 `other\|` 前缀，逐窗小计打印 0/0/0），而主数据行是真数 | `.superpowers/sdd/2026-10-11-w7e-test-batch/evidence/sw360-lane-truncated-by-reason.txt` 原文（§3.3 引用） | 任何"按窗口聚合"的下游脚本都会读到 0；历史留痕不改（§五.4），但**引用该文件时必须只用主数据行** |
| **F5** | **`Industry.laborPerUnit` 不接受负值**（构造期 fail-closed）⇒ `requiredWorkCapitalScale` 里 `laborPerUnit ≤ 0` 的"负"半边**结构性不可达**（唯一可达入口 = `0`） | 本批第一轮测试红原文：`IllegalArgumentException: Industry.laborPerUnit 不得为负: -1`（`Industry.java:280`） | 不是缺陷（防御分支）；但**别把它当成"被覆盖的分支"**——判据只能覆盖 `== 0`（§4.3 已按实跑更正） |

---

## 10. 落点速查

```
.superpowers/sdd/2026-10-11-w9f-test-batch/
├── impl-ledger.md                  ← 本文件
├── run-worlds.sh                   ← 真世界装置（sw360/tp360/sw3seg/swtick；端口 10995-10998）
├── extract9f.py                    ← 六类读数抽取器（含 --conservation 守恒模式）
├── gui500.sh                       ← ⑤ 受控真 500 装置（109xx? 端口 11001；独立 store）
├── plans/plan-{sw-360,sw-3seg,sw-tick,tp-360,tp-240}.json
├── logs/ 03-worlds.out（装置总输出）sw360/tp360/sw3seg/swtick .result.json
│         10-14-*.log（新用例与变异轮的 Maven 摘要，小）
│         15-fulltest-final.log（最终全仓轮）
│         gui500.log（⑤ 的服务日志）
│         （818 MB 全仓日志、1.4 GB tp360.log、1.5 GB swtick store 已按磁盘纪律删除）
├── dumps/ sw360-d360-oneshot.json sw3seg-d360-3seg.json swtick-d360-tick.json tp360-d360-oneshot.json
└── evidence/
    ├── 01-fulltest-summary.txt              ← ① 全仓 rc + 逐模块 + 红点扫描 + 6 候选类 + mtime
    ├── 02-fingerprint-deviceA-summary.txt   ← ② 装置 A 自写摘要（96/0/3337/15·35.5·193）
    ├── 03-b2-normalized-md5.txt             ← 三形态规范化 md5 + 差异叶 0
    ├── 04-sw360-readings.txt                ← ③ sw 0→360 六类
    ├── 05-tp360-readings.txt                ← ③ tp 0→360 六类
    ├── 05b-tp360-0-240-readings.txt         ← ③ tp 0→240（与 W7e/W9e 同窗口）
    ├── 06-conservation.txt                  ← ⑥ 守恒（货币/份额/商品/人口）
    ├── 07-gui500-real-stack.txt             ← ⑤ 真 500 + 完整栈
    ├── 08-md5-mutation-baseline.txt / 09-md5-after-restore.txt
    └── （Maven 日志同目录 logs/）
源码改动（测试面，未 commit）：
  simos-economy/src/test/java/io/mosire/simos/economy/time/ModeMigrationWorkCapitalScaleW9fTest.java（新）
  simos-economy/src/test/java/io/mosire/simos/economy/time/ModeMigrationPolicyD024Test.java（+2 用例，夹具 +1 参）
```
