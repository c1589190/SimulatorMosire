# W8 实现架构账本 —— 债务语义与数值域

- **责任区**：债务语义与数值域（① 溢出不致命 / ② 户绝即销账 / ③ 空户不再计息 + ④ D5 调查）
- **基线**：`f5b4f28d`（HEAD，工作树干净；`docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` 的 1 处改动**开工前就存在、非本 Agent 所为**，本 Agent 全程未碰）
- **权威**：设计书 v3.25/v3.27 §7；调查报告 `.superpowers/sdd/2026-10-11-death-debt-writeoff/report.md` + `results/tables.md`
- **用户原话（逐字，2026-10-11）**：
  > ①「还有利率，这个其实不用管，同样原则，**富人没有利滚利就行，穷人的话，死了账一并消除**，主要调查后者，带利滚利的利率有没有随着家户人口死亡而消失」
  > ②（在第 690 天硬停的选项里选定）「**修：溢出不再致命（世界能继续跑）**」
- **★ 用户裁"利率不用管" ⇒ 本批未动**：无利率上界、未改 α、未动货币市场定价、未改 `chargeInterest` 的**计息算式与费率**（只加了"户已绝则不进入计息"的前置跳过）。

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 证据（`file:line`，均为**回代码核过**的现场） | 结论 | 影响 |
|---|---|---|---|
| C1 | 崩溃算式：`DebtValuation.java:581`（改前）`convert()` 的 `Math.multiplyExact(quantity, numerator)`，`ArithmeticException` ⇒ `:588` 具名抛 | day690 硬停的**唯一**抛出点 | ① 的改动落点 |
| C2 | 崩溃调用链（未过滤日志 `haul-supply-investigation/results/sw-replay2.full.log:2579`，`quantity=11482568556053609 numerator=1000 denominator=1`）：<br>`ModeMigrationPolicy:392` → `SettlementOrderBook.snapshot` → `SettlementOrderBook.valuate:295` → `DebtValuation.commonValueMilli:295` → `convert` | 崩在 **⑧迁移计划**（`FLOWS` 之后、`DAY_END` 之前，日志时间戳 34.987→34.998 逐值对上）；`numerator=1000/denominator=1` 正是"货币按本币单位价 1000 折共尺"那一腿 | 说明它是**估值**溢出（不是计息），故 ① 的改动**不涉利率** |
| C3 | `SettlementOrderBook.java:491-503` 已有**静默**饱和 `saturatedAdd/saturatedMul`，且 `:196-199`/`:297-300` 的求和面**已经**是饱和的 | 崩溃点上游的"求和"早就按"饱和"设计，**只有 `convert` 还在拒绝推进** | 饱和不是本批新发明的语义，而是把已有意图补齐 |
| C4 | `writeOffDebtsForDeaths` 全仓唯一调用点 = `EconomySettlement:4486`（批次入口 `applyPopulationChangeInto`）；活路径 `applyHouseholdPopulationDeltasInto`（五参，`:4575`）**无任何核销**，且其 javadoc 自己写着"**不删债**" | 死亡户绝的债在生产路径上永远不消 | ② 的改动落点（已把 javadoc 改口，避免留假描述） |
| C5 | `chargeInterest`（改前 `:9715`）只跳"当日新借/本金 0"与"自持合同"，**无人口过滤** | 空户无限复利（D2） | ③ 的改动落点 |
| C6 | `DebtIndex.byCreditor`（`DebtIndex.java:42`）**已存在**且是"纯派生、规范序（按合同 id 字典序）" | 债权人侧销账**不需要新状态、不需要新索引** | ② 双向销账零新增组件 |
| C7 | `DebtContractBook.forgive`（`:423-485`）只 `contracts.put`（本金 ↓、减到 0 ⇒ `FORGIVEN`），**不碰账户/库存/流水/发行审计**；`DEBT_FORGIVE` 是该写口内的 DEBUG 事件（`:458-482`） | 复用该写口 ⇒ **DEBT_FORGIVE 自动从 0 变可观测**，且守恒读数不变（调查报告 §2.8 双向证实） | ② 不需要新日志通道（另加一条 INFO 汇总） |
| C9 | ★★ **"正常路径逐值不变"的逐字节证据**：改前 jar 的 `daily/sw600.cp.jsonl`（W7）与本轮 `daily/fresh600.cp.jsonl` 逐日对拍 **checkpoint md5**：**d0–d179（180 天）逐字节完全相同**，首个不同日 = **d180**（= 周期关账日，且是第一批**带债**家户被转移归零的日子 ⇒ ③ 当天第一次真的少算一笔利息；d60 那批被搬空的户 `contracts=0` ⇒ ③ 是 no-op ⇒ 世界仍逐字节相同） | ① 在新播 600 天里 `ARITHMETIC_SATURATED` = **0 条**；②/③ 只在"该动的地方"动 | 三条修法在正常路径上**逐值不变**（不是"我觉得没变"） |
| C8 | **D5 根因（决定性）**：合同 id = `(debtor, creditor, unit, terms)` 的确定性派生（`DebtContractBook` 类注 `/ :124`）；`upsert` 对**已存在**的行**保留** `openedDay`（`:155`），新建行才写 `day`（`:136`）；`ModeMigrationSettlement:663-680` 会在迁移源户归零时**删除**其本金为 0 且无质押的合同（`debts.remove`，**全仓唯一一处**——调查报告"`contracts.remove` 0 命中"是**grep 名字错**：变量叫 `debts`） | D5 = **读侧口径 + 两条既有写路径的叠加**，不是缺陷：<br>· `hh--1_0-rural-middle_peasant`：d210 计息写 `lastInterestDay=210`（`:180`→`:210` 实测）后，整户迁往 `hh--1_0-rural-landlord`（**恰是债权人**⇒`MIGRATION_DEBT_SELF_NETTED`，日志 d210 两行）把本金减到 0；行**未被删** ⇒ d213 同四元组再借（upsert 走"已存在"分支）**openedDay 保持 150**、本金 86,912。<br>· `hh-0_-1-rural-middle_peasant`：d165 开条 opened=165；d180 归零后被**删除**；d188 同四元组重建 ⇒ **opened=188**（若未删则必为 165）。 | **无需修**（取证见 §4④） |

## 2. 实现架构

### 2.1 ① 溢出饱和 —— 唯一拼写点 `SaturatedArithmetic`（**新文件**）

```
simos-economy/src/main/java/io/mosire/simos/economy/time/SaturatedArithmetic.java   （package-private，新）
  add(left, right, logger, origin, site, context...)                  饱和加法
  floorMultiplyDivide(q, n, d, logger, origin, site, context...)      ⌊q·n/d⌋：放得下 ⇒ 原式逐值不变；放不下 ⇒ 128 位
  divide128Saturated(high, low, divisor, ...)                          128÷64：先与 Long.MAX_VALUE×d 比大小，
                                                                       只有**商真的越界**才夹取
  （private）divide128 / log
```

- **精度纪律保留**：全程 `long` + `Math.multiplyHigh`，**零 double、零近似**；`divide128` 是 64 步移位减法。
- **"确实超界"才夹**：`⌊q·n/d⌋` 的乘积放不下 ≠ 商放不下（如 `q·n=2^63, d=2`）⇒ 先比 `(hi,lo)` 与 `Long.MAX_VALUE×d` 的 128 位值，**只有商越界才夹**；否则给精确商。
- **具名可观测**：事件 `ARITHMETIC_SATURATED`，字段 `site / quantity / unitValue / divisor / saturatedTo / productHigh / productLow / productBits`（加法路径为 `left / right / leftBits / rightBits`）+ `why`；**每个 site 的第一次是 INFO**（默认 INFO 档也看得见），其后 DEBUG。闸门 `ConcurrentHashMap<String,Boolean> FIRST_LOGGED` **只影响日志**。
- 接入点（算式唯一）：`DebtValuation.convert`（site `debt-valuation-convert`）、`DebtValuation.maxAssetQuantity`（`debt-valuation-max-asset-quantity`）。
- **改动范围超出原任务书的 `file:line`（如实记）**：续跑实测在 **day 720** 撞到**同一族的溢出**，但那次不是"具名拒绝"而是**静默回绕成负数**后触发的守卫拒：
  `OperatorSettlement.java:356-360`（改前）`due = principal + principal * rate / 1000` 与两处 `+=` 全是**裸 long**；真读数 `OperatorCondition.debtServiceDueMilli 不得为负: -20183298549694382`（`OperatorCondition.java:117-119` 的守卫）。按**同一条用户裁定**（"溢出不再致命"）一并改为饱和，4 个 site：`operator-debt-service-due-interest` / `-service-due` / `-principal-total` / `-service-due-total`。★ 这**不是**改利率：`⌊本金 × 利率‰ ÷ 1000⌋` 的算式与费率一字未动，只把"超界"的失败语义从"静默回绕/硬停"改成"夹取 + 具名"。

### 2.2 ② 户绝 ⇒ 双向销账（活路径接通）

```
app 日循环 PopulationEconomyTimeParticipant:760
  → EconomyDayStepper.applyHouseholdPopulationDeltas(deltas, births, deaths, day):589
    → EconomySettlement.applyHouseholdPopulationDeltasInto(session, deltas, births, deaths, day):4575
        ① 全量校验（未动）  ② population += delta（未动）  ③ 生死进流水（未动）
        ④ ★新：遍历 householdEconomies（= 家户行序 ⇒ 确定性 I7）
             populationAfter == 0 且 populationBefore = −delta > 0 ?
               deathsByHousehold[hh] > 0  ⇒ writeOffExtinctHouseholdDebts(…)   INFO HOUSEHOLD_EXTINCT_DEBT_WRITTEN_OFF
               否则（迁移归零）          ⇒ logEmptiedHouseholdDebtRetained(…)  INFO HOUSEHOLD_EMPTIED_DEBT_RETAINED
```

`writeOffExtinctHouseholdDebts`（新，private static）：
- **债务人侧**：**原样复用** `writeOffDebtsForDeaths(debts, byDebtor[hh], hh, populationBefore, deaths, session.debtWriteOffs())`。本分支只在"写后人口 == 0"进入，而 `delta = births − deaths` 已被 ①c 交叉核对 ⇒ `deaths = populationBefore + births ≥ populationBefore` ⇒ `survivors = populationBefore − deaths ≤ 0` ⇒ P5 的 `survivors <= 0 ? 0 : …` 退化为**逐笔全额减免**。**没有第二套删除算式**。
- **债权人侧**：`DebtIndex.byCreditor(debts)` 取 `creditor == hh` 且 `principal > 0` 的合同，逐笔 `DebtContractBook.forgive(…, CREDITOR_EXTINCT_DEBT_WRITE_OFF_REASON)`，并把金额并入 `session.debtWriteOffs()`（**键 = 债务人**，见 §3 判断 J3）。自持合同（`debtor == creditor == hh`）已被债务人侧减到 0 ⇒ 这里 `principal <= 0` 自然跳过，不重复计。
- **先量后删**：减免额用"删前本金"逐值读出（该分支保证全额），**不重写** P5 的 `proportionalPrincipalAfterDeaths`。
- **只动本金与状态**：不碰账户/库存/流水/发行审计 ⇒ 货币守恒读数不变。新增常量 `CREDITOR_EXTINCT_DEBT_WRITE_OFF_REASON = "death:creditorExtinctReceivableWriteOff"`（与 P5 的 `DEATH_DEBT_WRITE_OFF_REASON` **分开命名**，便于用户日后只推翻一侧）。

**判据（唯一，写在代码注释里）：人口由 > 0 变 0 **且** 社会报来的死亡 > 0。** 迁移归零（二参重载 `applyHouseholdPopulationDeltas(Map)`，两表恒空 ⇒ `deaths == 0`）**绝不销账** —— 这正是"迁移不销账、只有死亡导致的归零才销账"的落点。

### 2.3 ③ 空户不再计息

`chargeInterest`（`EconomySettlement:9715`）新增形参 `Map<HouseholdId, HouseholdEconomy> householdEconomies`（唯一调用点 `:2876` 传入）；在既有两道 `continue`（本金 0 / 自持）**之后**、`interestTiming` 的 fail-closed 校验**之后**、真正算 `charged` **之前**插入：

```
debtorEconomy = householdEconomies.get(debt.debtor())
debtorEconomy != null && debtorEconomy.population() <= 0  ⇒  continue + DEBUG DEBT_INTEREST_EXTINCT_HOUSEHOLD_SKIPPED
```

- **判据取社会侧权威**：`HouseholdEconomy.population` 是 app 每日从 Social 展开后注入的当日物化视图（AGENTS §〇），**不是**"债务表里有没有对应 classes 行"那种间接形态判据。
- **行缺席 ⇒ 照旧计息**（说不出"已绝"就不许当作已绝）：非家户端点（政府/单位户）本来没有经济家户行，行为与改前逐值相同。
- **与 ② 的次序（理由）**：两者**不在同一相位** —— ③ 在日结算的周期末计息（`step` 内），② 在 `step` **之后**的社会生死回写里（W3 两段式：经济先关账、Social 后判死）。日序上 ③ 必然先于 ②，但 **③ 不依赖 ②**：② 把本金删成 0 后 ③ 对那条合同本就无事可做（本金 0 已 continue）。反过来 ③ 也不是 ② 的替代：**③ 只停止增长，② 才消除这笔账**。③ 必须存在，因为"人口 → 0"里绝大多数是**迁移**（调查报告 §2.2：sw 21 例里 19 例），迁移不销账 ⇒ 那些空户要靠 ③ 停息。

## 3. 关键判断

- **J1（饱和 vs 拒绝推进）**：用户裁定"溢出不致命"与"拒绝回退到 double"**不矛盾**——被否掉的是 `double`（丢精度），不是"整数饱和"。故保留全整数、只在**确实超界**时夹取并具名。判据：`Math.multiplyExact` 抛出的那一条分支才走新路，正常路径是**同一句原式**。
- **J2（为什么不一律夹到 Long.MAX_VALUE）**：中间量超界 ≠ 商超界。先用 `Long.MAX_VALUE × divisor` 的 128 位值判定，只有商真的越界才夹；否则（如 `q·n=2^63, d=2`）给精确商 ⇒ "饱和只用在确实超界的路径上"逐字兑现。
- **J3（债权人侧记在谁的键上）**：`EconomySession.debtWriteOffs()` 的既有契约是"**键 = 本金被删掉的债务人**"（`:114-119`）。债权人侧消除的**受益人**是债务人（它的本金掉了）⇒ 记在**债务人**名下；"这一次是债权人侧消除"由 INFO 的 `creditorPrincipalByUnit` 字段与 reason 常量区分，**不改累加器的键契约**。
- **J4（不接受"另写一套"）**：债务人侧**不新写**删除算式，直接调 `writeOffDebtsForDeaths` 并证明它在本分支退化为全额（`survivors ≤ 0`）。债权人侧也不新写写口，直接 `DebtContractBook.forgive`。
- **J5（为什么不给 2 参迁移路径补 day）**：D4 纪律明写"两表恒空 ⇒ 不给假日"，且该重载被 `EconomyLoggingTest:240` / `ModeMigrationPolicyD024Test:581` **两个既有测试类**直接调用 —— 改签名会破坏既有测试（§一.11 又不留兼容重载）。⇒ 保留 day-less，`HOUSEHOLD_EMPTIED_DEBT_RETAINED` 在不知道世界日时**不带 `day` 字段**（与 `HOUSEHOLD_VITAL_WRITEBACK` 的守卫同一口径）。
- **J6（调查中推翻过的）**：调查报告把 D5 记为"机制未定"，并自陈"要先排除读侧因素（`debt_rows` 按 `principal>0` 过滤）"。本批用 `openedDay` 这一**权威字段**把两个案子一次判死（§1 C8）：case 2 的 `opened` 从 165 变 188 **只可能**是"行被删过又重建"，case 1 的 `opened=150` 保住则**反证**行一直在（只是本金 0）。另推翻调查报告一处 grep 结论：`debts.remove` 确实存在（变量名是 `debts` 不是 `contracts`）。

## 4. 偏离记录

| # | 与约束设计书/任务书的不一致 | 原因 | 可否被用户推翻 |
|---|---|---|---|
| **D-1** | **债权人侧一并消除**：用户原话只说了"死了账一并消除"，**没说方向**；任务书裁"双向"并明说这是**控制方口径**可被用户推翻 | 户绝后没有任何主体能主张/被主张这笔账；留着就会出现调查报告 §2.5 的读数（0 人口户仍是 1.04×10¹² silver 的债权人，而活着的债务人还在为它复利） | ✅ **可推翻**：两个方向用**不同 reason 常量**，读口/审计可分开；只推翻债权人侧只需删 `writeOffExtinctHouseholdDebts` 的 ② 段 |
| **D-2** | ① 改了**任务书 `file:line` 之外**的第二处溢出（`OperatorSettlement:356-360`） | 任务书判据要求"推进过 day 690（例如到 700/**720**）"，而 day 720 又撞同一族溢出（**静默回绕成负数**，比具名抛更坏）；用户裁定是"溢出不再致命（世界能继续跑）"，不修则世界仍停 | ⚠️ 可推翻（回退 4 个 site 即恢复原行为；原行为 = day 720 硬停） |
| **D-3** | 新增 `SaturatedArithmetic` 一个类（约束设计书未提） | 同一套 128 位"精确或饱和"算术要在估值与经营者本息两处用；各写一份必然漂成两把尺（本仓"唯一拼写点"纪律） | 纯技术分歧（控制方可裁定） |
| **D-4** | 批次路径 `applyPopulationChangeInto`（`src/main` **零调用点**）**仍只做债务人侧**，未接通债权人侧 | 任务书把修法明确限定在"活路径上接通核销"；批次路径无生产调用者，动它 = 无法验证的行为改动（§一.11 亦不鼓励为死代码扩展） | 纯技术分歧 |
| **D-5** | `HOUSEHOLD_EMPTIED_DEBT_RETAINED` 走 `ECONOMY_POPULATION_WRITE`（SYSTEM），与同方法内的 `HOUSEHOLD_VITAL_WRITEBACK` 同源同制 | 与同写口邻居保持一致；迁移路径无 day 上下文 | 纯技术分歧 |
| **D-6** | 未新增 `EconomyLogSource` 表项 | 复用 `ECONOMY_POPULATION_WRITE`（人口回写原语）与 `ECONOMY_OPERATOR_STATE`（经营者关账，无 day 上下文 ⇒ 按纪律归 system） | 纯技术分歧 |
| **D-7** | `SettlementOrderBook` 那对**静默**饱和 helper（`:491-503`）**未改**为具名 | 先于本批存在、语义是"饱和值求和"，改成具名需给每个调用点补上下文（会刷屏）；本批按"最小改动 + 不改无关语义"处理 | ⚠️ 遗留不一致，如实记 |

## 5. 未做 / 未验证

1. ~~债权人侧未行使~~ **已行使**（`fresh900to1400` 的 **day 1367**，`hh-0_-2-rural-landlord`：`debtorSideContracts=5` + **`creditorSideContracts=5`**，`creditorPrincipalByUnit={money:silver=2917065953340157}`；逐笔 10 条 `DEBT_FORGIVE` 全部 decoded 核对，对手方是 5 户活着/空着的债务人）。★ 但**只此 1 例**：`fresh600(0→600)` / `fresh600to900` / `dep780to1000` / `dep1000to1400` 的其余 15 次户绝 `creditorSideContracts` 全为 **0**（那些户恰好没有应收）⇒ 该分支的样本量 = 1 次户绝 / 5 笔合同。
2. **批次路径（`applyPopulationChange`）未验证**：`src/main` 零调用点，无生产路径可跑，且本批**未给它接债权人侧**（见 D-4）。
3. **`debtWriteOffs` 累加器仍用 `Math.addExact`**（与既有 P5 代码同制，任务书要求"复用…不要另写一套"）：极端世界里"一户多笔巨额减免"求和仍可能溢出。**未改**，如实记（改它会与 P5 既有写法分叉）。
4. **`chargeInterest` 的 `charged = startPrincipal * rate / 1000` 仍是裸运算**（可能静默回绕）：属**利率/计息算式**，用户裁"利率不用管"，D3/D4 已把它归为 W6 标定问题 ⇒ **本批不动**。
5. **未做 A/B 逐值对照**：本批给的是"改前/改后**同一存档**续跑 + **同一配置新播世界逐日 checkpoint md5 对拍（180 天逐字节相同）** + 同规模新世界真读数对照"，不是"同一输入分两条路径跑同一进程做差"。
6. **D5 未回读 d210–d212 的原始 checkpoint**（store 只保留最新 checkpoint，逐日全量档不存在）—— 结论由 `openedDay` 字段**反证**得出（见 §1 C8），未另开重跑取证。
7. **未跑 `-Dsimos.economy.traceLevel=TRACE`**：`DEBT_INTEREST` / `DEBT_REDUCE` 逐笔流未采；"空户不计息"的读数由**逐日时点快照跨 9 个关账周期持平**（§results/readings.md ③B）与 **246 条 `DEBT_INTEREST_EXTINCT_HOUSEHOLD_SKIPPED`** 共同给出。
8. **既有测试只跑了 2 个类**：`DebtBasedClearingAcceptanceTest`（6/6 绿）、`DeathDebtWriteOffTest`（3/3 绿）。**其余类未跑** ⇒ "会让既有测试失效的清单"见 §6，**未逐一实证**。
9. **未验证 jar 打包后行为**：全程用 `target/classes` 覆盖层跑（未跑 `package`）⇒ 与 shaded jar 的差异只有"新类优先命中"这一项，未做 jar 内字节核对。

## 6. 会让既有测试失效的清单（按类名，**只报不改**）

| 类 | 风险 | 判断依据 |
|---|---|---|
| `io.mosire.simos.economy.time.DeathDebtWriteOffTest` | **低（已跑，3/3 绿）** | 它测的是批次路径 `applyPopulationChange`，本批未动 |
| `io.mosire.simos.economy.time.DebtBasedClearingAcceptanceTest` | **低（已跑，6/6 绿）** | 覆盖 `DebtValuation` + 日步进偿还 |
| `io.mosire.simos.economy.model.EconomyInvariantsTest` | **中（未跑）** | 断言里含 `interestDue`；若其夹具里有"人口 0 的债务人家户 + 期待计息"的用例，③ 会让它红 |
| `io.mosire.simos.economy.time.ModeMigrationPolicyD024Test` | **低（未跑）** | `:581` 调二参 `applyHouseholdPopulationDeltasInto(session, deltas)`：本批**未改签名**；若其夹具里该步把某户打到 0 且 `contracts > 0`，会多一条 INFO（不破断言） |
| `io.mosire.simos.app.logging.EconomyLoggingTest` | **低（未跑）** | `:240` 调二参重载断言"未知家户行 ⇒ 抛"：① 的全量校验在写任何一行之前 ⇒ 仍抛；③/④ 都在校验之后 |
| 任何"用零人口家户跑满一个关账周期并断言利息/`FlowRow.interestDue`"的类 | **中（无法穷举）** | ③ 会改变这些读数 |

★ 截至交账**只实证了 2 个类**（见 §5.8），上表其余行是**静态判断**，不是"跑过没红"。

## 7. 会改变数值行为的清单（给测试代理当输入）

| # | 改动 | 何时改变数值 | 量级 / 真读数 |
|---|---|---|---|
| N-1 | ② 活路径户绝核销（债务人侧全额减免 + 债权人侧应收减免） | **仅当**某户人口由 > 0 变 0 且社会报来的死亡 > 0 | d550 一次性 −11,153,585,921 silver；d1367 一次性 −13,026,596,162,025,035 负债 + −2,917,065,953,340,157 应收 |
| N-2 | ③ 空户不再计息 | **仅当**债务人行 `population ≤ 0` 且该户仍有本金 > 0 的合同、且遇到关账日 | 改前该户每周期 ×12.4；改后 0（9 个周期持平；对照 W7 的 ×19.62 / ×37.03 / ×153.93） |
| N-3 | ① 估值/本息合计的**饱和** | **仅当**中间量真的超界（`Math.multiplyExact` 抛 / 128 位商 > `Long.MAX_VALUE` / 裸加回绕） | 新播 0→600 世界 **0 次**；d690 续跑段 5,421（convert）+ 10（operator）次；到 d1400 累计 5.6 万次 |
| N-4 | 日志面新增事件 | 不改数值 | `ARITHMETIC_SATURATED` / `HOUSEHOLD_EXTINCT_DEBT_WRITTEN_OFF` / `HOUSEHOLD_EMPTIED_DEBT_RETAINED` / `DEBT_INTEREST_EXTINCT_HOUSEHOLD_SKIPPED` |
| N-5 | 新增 reason 常量 `death:creditorExtinctReceivableWriteOff` | 不改数值（只进 `forgive` 的 `reason`；`DEBT_FORGIVE` 只记 `reasonLength`） | `reasonLength` 从 26 变 49（债权人侧那几笔） |

**不改数值的**：货币守恒读数（核销不铸钱/不回笼/不转移）、账户/库存/发行审计、`chargeInterest` 的费率与算式、
货币市场定价、α、`DebtContractBook` 的任何既有写口语义、批次路径 `applyPopulationChange`。

## 8. 硬编码字面量（受影响清单）

| 字面量 | 位置 | 变化 |
|---|---|---|
| `1_000L`（利率‰ 的分母） | `OperatorSettlement.java` 债务本息合计 | **值未变**（改前是 `debt.principal() * rate / 1_000L` 的行内字面量，现在作为 `floorMultiplyDivide` 的 `divisor` 传入；算式逐值相同） |
| `Long.MAX_VALUE` | `SaturatedArithmetic`（饱和上界） | 新增（= 原 `DebtValuation`/`SettlementOrderBook` 既有饱和 idiom 的同一个上界） |
| 站点名字符串 5 个 | `debt-valuation-convert` / `debt-valuation-max-asset-quantity` / `operator-debt-service-due-interest` / `-service-due` / `-principal-total` / `-service-due-total` | 新增（只进日志 `site=` 字段，稳定可 grep） |
| reason 常量 2 个 | `death:proportionalWriteOff`（既有，未改） / `death:creditorExtinctReceivableWriteOff`（新增） | 新增 1 个 |
| `1000`（共同价值量纲） | `DebtValuation.commonUnitValue` 用 `EconomyVocabulary.MILLI_PER_COMMODITY_UNIT` | **未改** |
| 阈值 `0L`（人口判据） | ③ 的 `population() <= 0L` | 新增判据常量级字面量（0 = 户绝） |

★ **未新增/未修改任何"标定值"**（利率、税率、运费、产能、死亡率全未动）。
