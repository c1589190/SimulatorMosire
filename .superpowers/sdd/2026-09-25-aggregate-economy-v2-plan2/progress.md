# SDD ledger — plan: docs/superpowers/plans/2026-09-25-aggregate-economy-v2-plan2.md

Spec: docs/superpowers/specs/2026-09-25-aggregate-economy-v2-design.md（权威，范围 = §九 的 **V3**）
前序：plan 1（V1+V2）已关账、已推送（`3f44845`）。本计划由子代理撰写、控制器审后执行。
执行方式：子代理实现 + 自过编译与基本测试；**统一评审留到整批代码写完**（用户 2026-09-25 指令）。
分支：ts/m1。

## Pre-flight：跨任务共享接口扫描

| 产出方 | 消费方 | 接口 | 结论 |
|---|---|---|---|
| T1 `Industry.cycleInputPerUnit` / `cycleSeedUsedMilli` | T3 播种步、T4 `harvest` | 记录组件（位置参数，9 处构造点） | 一致；★ T1 必须同步改全部位置参数构造点，漏一处即编译错（响亮，非静默） |
| T3 `sowIfCycleStart` 的扣减 | T4 的 `seedCapMu` | `cycleSeedUsedMilli`（毫粮） | 一致（毫粮 ÷ (毫粮/亩) = 亩） |
| T3 扣减入 `FlowRow.consumed` | 端到端守恒用例 | `FlowRow.consumed` | ★ **子代理补的关键一条**：不记则配了种子的世界上守恒式不成立 ⇒ 端到端守恒用例变**假绿** |
| T2 载荷解析缺键 ⇒ 空/0 | T6 "旧载荷缺键不拒" | `EconomyPayloads` | 一致；`EconomySeedHandlerTest.PAYLOAD` 故意不补新键，作活体守卫 |
| plan 1 的 `WageFirst` 落点 | T1 的构造期守卫 | — | ★ 见下方 Ruling 1 |

## Rulings

- **Pre-flight: Ruling: T1 不得给 `Industry` 加"非 Split 即拒"** —
  plan 1 的 Task 5 写的是"构造期拒"，**落地时改在 `EconomyPayloads`（播种期）**（因为构造期拒会让 `WageFirst`
  这个状态形状不可表达，`EconomyCodecTest.workshopIndustry()` 需要它做多态往返）。
  ⇒ 计划 2 的 T1 若照 plan 1 的字面写，会打红 codec 那条往返。以**落地代码**为准。
  代价 if wrong：`WageFirst` 的手改 JSON 存档仍可入库（已知可接受，plan 1 已记）。

- **待裁（阻断"真档可见"的验收，不阻断 T1~T4）**：**「每亩需种」是否现在就定？**
  子代理全仓搜证：该数在代码/配置/文档里**都不存在**，spec §4.1/§4.7 归 V7。
  它按"没依据就置 0"把真档写成 `"cycleInputPerUnit": {}`，并**如实报告后果：V3 的第三路瓶颈在 799 格真实世界里
  完全看不见，验收全落夹具**。同时它发现 **15% 双重计入**：`PRODUCTION_CONSUMPTION_PER_MILLE` 的语义是
  "**种子**/牲畜/工具"，而 spec §3.4 明文要求留种移出 15%（15% 只留饲料+折旧）。
  控制器 2026-09-25 已把该岔口上报用户（推荐"现定"：裁值约 8,000 毫粮/亩 + 拆 15% + 重标定 `MU_PER_HEX` ≈ 3,620），
  **等用户裁定后**再决定是否给本计划加一个标定任务。

## 子代理撰写期发现的、已知约束（不改，记录）

1. **"冬春吃空缸"在标定后是"贫困陷阱型"而非"年度波动型"**：收获在周期末、播种日是其次日，中间**没有消费时点**
   ⇒ 播种日的缸 = 上周期结余 + 上周期收获；每亩净产对种子成本有几百倍回报 ⇒ 只要上周期播过一粒种，下周期必然满种。
   故"扣不到种"只能由"缸从来就是空的"造成。
2. **`seedCapMu ≤ availableMu` 恒成立** ⇒ 第三路只会缩不会放；满种时它恰等于土地约束（只在**扣不满**时可见）。
   这证明 `seedPerMu == 0 ⇒ availableMu`（而非 `0`）是"存在但不约束"的唯一正确写法（否则旧档颗粒无收）。
3. **分配仍按土地占比而非实际播种占比**：干缸行的地荒着，它仍按土地权重拿一份产出。spec §3.1 的 `plantedMu` 是
   **产业级**单一数字，没有"逐行播种归属"概念；引入它属另立设计 ⇒ 留 V5/V6 评审。
4. **读口暂不暴露**这两个字段（V3 判据不含读口；spec §八.8「读数与结算同源」属 V5）。
5. **两份量纲易混**：`cycleInputPerUnit[LAND]` 是**毫粮/亩**，`meansOfProduction[LAND]` 是**千分亩**（差 1000 倍）。

## 任务台账

（每任务完成后追加：`Task N: complete (commits <base7>..<head7>, tests: <cmd> → <result>)`）

- **Task 1: Ruling: 构造点是 14 处不是计划写的 9 处** —— 计划的 Files 清单本身完整，逐处按语义补参
  （`EconomySettlement.withProgressAndLabor` 走**透传**，否则周期关账会抹掉两个字段）。代价 if wrong：无（清单完整）。
- **Task 2: Ruling: Step 2 的实际红是 2 条不是计划的 1 条** —— `rejectsNegativeCycleInput` 也红
  （字段没被解析 ⇒ 负值静默忽略 ⇒ 走 `Applied` 而非 `Rejected`）。**比计划更红，不是更松**。
- Task 1: complete (tests: `./mvnw -q test -pl simos-economy,simos-app -am` → 92 报告/675 用例/0/0;
  变异轮 T1-a 删 null 守卫 ⇒ 只红 `rejectsNullCycleInputMap`（证明该守卫是唯一拦路者）、T1-b 负值判据放宽 ⇒ 红；
  均按字节还原并复绿)
- Task 2: complete (tests: `./mvnw -q -Dtest=EconomySeedHandlerTest ...` → 13 用例/0/0;
  变异轮 T2-a 模拟"声明了没人解析" ⇒ 2 条红；还原后复绿)
- **全仓回归**：`./mvnw -q test` → **2105 用例 / 0 失败 / 0 错误**（10 模块）。
  ★ 关键活体证据：`EconomySettlementEndToEndTest` 的 8 条**一字未改**（含 `176,545 粮/格` 等 plan-1 字面量）
  ⇒ "未配种子 ⇒ 行为与 V2 逐字一致"由此可证。
- **Final: minor (deferred)**: `ApiViews.economyHex`/GUI/MCP 暂不暴露这两个新字段（计划明列"本计划不做"）。
- **Final: minor (deferred)**: 未跑 `clean verify`（`spotless:check` 绑在 verify 阶段；本次由 `spotless:apply`
  反证格式合规，但未在 verify 阶段实测）。控制器统一跑。

- **Task 3: Ruling: 计划骨架有真 bug（`consumedGrain.put` 覆盖种子那一笔）** ——
  `settleHexes` 写的是 `consumedGrain.put(key, eaten)`，会把先跑的播种步 merge 进去的种子**覆盖**掉
  ⇒ §6.1 守恒式不成立。由计划自己的用例逼出（`sowingDayDrawsTheSeedBeforeTheDayIsEaten` 期望 73,200 实得 33,200）。
  按"修代码不改断言"处理：`put` → `merge(key, eaten, Long::sum)`。代价 if wrong：无（merge 是正确语义）。
- **Task 4: Ruling: 计划里两处累加器断言与设计矛盾（关账清零）** ——
  计划写 `settle(0,2)` 后 `cycleSeedUsedMilli == 20,000 / 40,000`，但 **2 天 = 一整个 2 天周期 ⇒ 关账时已清零**（Task 3 自己的绿断言就钉着它）。
  改法：另跑一次**单日**结算读第 1 天读数，**期望值一字未改**（20,000 / 40,000），未放宽。
- **Task 4: Ruling: 计划的两条用例预测偏多（3 处，无一处偏少）** —— 逐条记录于子代理报告；
  最值得记的是变异轮 M1：计划说 `anIndustryWithoutASeedRateDrawsNothingAtAll` 会红，实际它只结算 1 天、走不到 harvest；
  **但端到端 8 条里红了 3 条**（`176,545,000 → 0`）⇒ 计划的「未配种子的真档颗粒无收」由端到端**逐值验证**。
- **Task 3+4 新增用例**：`aJarThatExactlyCoversTheSeedIsDrawnToZero`（计划 Review Focus 4 的边界，
  计划声称由 Task 3 覆盖但实际没有）——做了**专属变异**（`stock == need ? 0 : min`）⇒ 只红这一条，证明非空转。
- Task 3: complete (tests: `./mvnw -q test -pl simos-economy -am` → 72/0/0；变异 3 轮均 md5 逐字节还原)
- Task 4: complete (tests: `./mvnw -q test -pl simos-economy,simos-app -am` → economy **78**/0/0、app 613/0/0；
  变异 3 轮 + 1 补充轮，均 md5 还原；另跑 `verify -pl simos-economy -am -DskipTests` rc=0 ⇒
  **包内可见静态重载不被 Spotless/Checkstyle/SpotBugs 挑刺**)
- **全仓回归**：`./mvnw -q test` → **2118 用例 / 0 失败 / 0 错误**（10 模块；2105 + 新文件 13 = 2118 ✓）。
  ★ **`EconomySettlementEndToEndTest` 文件 md5 与 plan-1 基线**逐字节一致（`fe489310b5619795caf32f6f6d582774`）
  ⇒ "未配 `cycleInputPerUnit` ⇒ 行为与 V2 逐字一致"由**字节**证明，不只是断言。
- **⚠ 给 Task 5 执行者的预警（子代理留）**：Task 5 的 `eachClassRowDrawsItsOwnSeedSoTheDryRowLeavesItsLandFallow`
  断言 `settle(base,0,2)` 后 `cycleSeedUsedMilli == 80,000`，按现实现**必为 0**（同上关账清零）⇒ 需同样改成读播种日当天。

- **Task 7: Ruling: 控制器算错两处（子代理实测更正）** ——
  ① 控制器在计划里写"端到端字面量再减 `MU_PER_HEX × SEED_MILLI_PER_MU` = 24,800,000"，并给低丘新值 99,447,550。
     **错**：5 格 `EconomyTestWorld` 夹具是 1,000 人/格、储备仅 5,395,000 毫粮，而满种要 24,800,000
     ⇒ 逐行 `need > stock`。子代理做了**诊断轮**（临时给该夹具配种子）实测：第 1 天全格把口粮当种子播掉
     （库存减少 5,395,000）、第 120 天只收获 43,803,260（674 亩）、第一周期饿死 **200/1000**
     ⇒ (g) 的 90 人叙事、(b) 的借粮夹具、(a) 的日耗夹具全崩。
     决定：`EconomyTestWorld` **保持未配种子**（Task 1 明令"必须保持空"），它现在扮演"**未配种子的对照格**"；
     "配了种子"的字面量放到**真档量级**（新增用例 + worldgen 用例）。低丘格因此是 **113,407,550**，不是 99,447,550。
  ② 控制器算真档满种 = 3,100 亩 × 8,000 = 24,800,000。**错**：`亩 = 千分亩 ÷ 1000` **向下取整**，
     逐行 1,395+1,085+464+154 = **3,098 亩** ⇒ 满种 **24,784,000**（差 2 亩、0.06%）；收获面积同为 3,098 亩
     （毛产 207,566,000 而非 207,700,000）。
  代价 if wrong：无（两处都是控制器手算，实测更正后自给率仍 1197~1198‰，落在 [1100,1300] 内）。
- **Task 5**: complete (tests: `-pl simos-economy -am` → 81 用例/0/0；变异 5-a「从全格池子扣」⇒ 只红
  `eachClassRowDrawsItsOwnSeed…`（80,000 vs 100,000）、5-b 删种子入 `consumed` ⇒ 3 条红含守恒用例)
- **Task 6+7**: complete，含 **M7-a**（折旧置 0）economy 3 红 + app 4 红、**M7-b**（不拆、回 150‰）
  economy **11 红** + app 3 红、**M6-a**（播种器写回空）⇒ 播种器用例 + 真档 3 条全红。
  全部用 Edit/Python 反向重写还原 + md5 确认（`EconomySettlement.java` = `4f0e369a…`、
  `EconomySeeder.java` = `a9e06b16…`、`EconomyTestWorld.java` = `6119e76f…` 且 `git diff` 空）。
- **全仓**：`./mvnw -q test` → **2126 用例 / 0 失败 / 0 错误**（10 模块；app 613→**617**、economy 78→**82**）。
  另跑 `verify -DskipTests -pl simos-economy,simos-app -am` → rc=0（spotless:check / checkstyle / spotbugs / 前端门禁 297 / shaded-guard 全过）。
- ★ **真档可见性已证**（Task 7 存在的理由）：`theSeederConfiguresTheDecidedSeedRate`（载荷真带 8,000）
  + `theRealScaleHexSowsEveryMuItHasMoneyForOnTheSowingDay`（播种日扣 24,784,000）
  + `theSownSeedIsTheBottleneckThatDecidesTheHarvestArea`（收获面积由 3,098 亩的**种子**决定而非 3,100 亩**土地**）
  + `anEmptyJarYieldsNothingInTheRealWorldWhileTheUnseededControlStillHarvests`（缸空 ⇒ 颗粒无收；对照格满产 201,469,000）
  + `WorldgenInitializeToolTest.advancingTenDays…`（真 799 格：10 天库存减少 15,165,876,000 = 口粮 5,170,900,000 + 播种扣种 9,994,976,000）。
- **Final: minor (deferred)**: 未逐格断言真档 799 格"全部满种"（只断言 `sown > 0`、`≤ Σ地亩 × 8,000` 与三国 10 天守恒式）。
- **Final: minor (deferred)**: 未跑**带测试的**全仓 `clean verify`（只跑了全仓 test + 两模块 verify -DskipTests）。
- **Final: minor (deferred)**: "每亩需种"未接读口（GUI/MCP）与参数目录——属 V5/V7 范围。
