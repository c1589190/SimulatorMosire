# G3 诊断遗留三处小修 —— 实现架构账本

> 角色：**写码 Agent**（责任区 = G3 诊断遗留的三处小修）。来源判据 = `.superpowers/sdd/2026-10-10-diagnose-debt-anomaly/impl-ledger.md`
> §Q1/§Q2/§Q4（已只读定案）。允许写：`simos-economy/src/main/**`、`simos-app/src/main/**`；未碰 `src/test/**`、
> 未碰任何 `pom.xml`/`docs/**`/`AGENTS.md`、未 `git commit`、未跑 `test`/`verify`、未起真实 world。
> 日期：2026-10-10。改动文件 3 个（见 §4）。

## 1. 关键调查结论（file:line 证据 → 结论 → 影响）

| # | 证据（HEAD，动手前实测） | 结论 | 影响 |
|---|---|---|---|
| Q4-1 | `MerchantCapacityPool.java:318-356`（旧）的 `MERCHANT_CAPACITY_HOUSEHOLD` 字段表无 `day`；同族 `:1009-1012`（`MERCHANT_CAPACITY_POOL_HEX`）、`:1035`（`MERCHANT_CAPACITY_POOL`）**`day` 打头** | 该事件不能按日对齐 | 只可观测性 |
| Q4-2 | 该事件在 `assemble`（**static**）里发；`assemble`/`MerchantCapacityPool.of` 的入参里**没有 tick**；`day` 只在 `logRoundSummary(long day)`（实例方法，`MarketSettlement:1592` 调用）与 `EconomySettlement` 的 tick 面存在 | 池**结构上**拿不到世界日 ⇒ `day` 必须由 tick 面提供 | 决定 Q4 的修法（见 §3.1） |
| Q4-3 | `assemble` 的**生产唯一调用点** = `EconomySettlement:1860`（6 参重载）；4 参重载的测试调用点 7 处（`MarketSettlementFixtures:387`、`MerchantCapacityAcceptanceTest:75/143/177/194/208/306`） | 改**既有**工厂签名会破坏测试编译；加新重载又需给夹具编造 `day` | 决定不改签名（见 §3.1） |
| Q4-4 | 市场**每 5 天**开一次（`MarketSettlement:1187-1197`：`day%5==0` / 关账日 / 低粮追加轮，否则 `NONE`）；`logRoundSummary` 只在 `MarketSettlement.settle` 内调（`:1592`） | 若把该事件挪进 `logRoundSummary`，**非开市日（约 4/5 的日子）整段读数消失**（改前是天天发） | 否决"挪进轮末汇总"这条捷径 |
| Q4-5 | `EconomySettlement:1867-1868` 既有注释：「带 day —— 它是 tick 面的装配，落在这里而不是池内，是为了让 TICK 来源的事件都带 day」（同处发 `CAPACITY_QUOTE_BOOK`） | 本仓对"装配读数带 day"**已有成文先例** | Q4 照此先例修 |
| Q2-1 | 旧判据 `min(item.remainingToolMilli, toolAvailableMilli)`（`:676-679`）；`remainingToolMilli` 初值 = **装配时点** `max(0, 现货−冻结)`（`:259-267` → Entry `:500`），每放行扣 1,000（`:703`） | 两个操作数**不同时点** | A 世界 5,824 趟被过严拦下（诊断 §2.2） |
| Q2-2 | `MerchantHaul.TOOL_MILLI_PER_HAUL = 1_000`；`affordsRun(x) = x ≥ 1000`；`runsAffordable(x) = x≤0 ? 0 : x/1000` | 判据是**门槛**，不是按量计费 | 判据量换成"当刻可用量 − 已放行×门槛"不改变量纲 |
| Q2-3 | `remainingToolMilli` 全仓用途：`:678`（旧路径判据）、`:703`（扣减）、`:949/:1004`（日志读数）、`:874 remainingToolMilliOf`（**零调用点**，死读口） | 该字段只剩"旧路径 + 读数 + 已放行计数载体"三职 | 不新增计数器，用它反推"已放行"（避免两处各记一遍） |
| Q1-1 | 债务人侧标量 `ApiViews:1313-1314` 累加 `debt.principal()`，**无 unit 拆分**；债权人侧 `:1322-1323` 同病；dashboard 侧 `:2167/:2200/:2217` 明文"本金不跨 unit 合计"、`debtPrincipalStockView:2177-2219` 给 `byUnit` | 读口自相矛盾（诊断 §1.4-2） | 只改读口 |
| Q1-2 | 既有键 `debtPrincipal` 的消费者：`GuiApiTest.java:992`（`assertThat(body.get("debtPrincipal").asLong()).isZero()`）、`panels.js:574`（GUI 面板负债文本）、MCP `simos.economy.hex`（`/tmp/g3-dump.py` 读它） | **删键 = 破坏既有用例 + 静默改契约** | 定案选"保留 + 标注"（见 §3.3） |
| Q1-3 | 全仓既有"按 unit 分列债务本金"的先例与**唯一键源**：`DebtUnit.key()`（`ApiViews:2138/2142/2624`、`HouseholdQueryService:692` 的 `indexDebts` 已经返回 `Map<household, TreeMap<unitKey, principal>>`） | 键域与形状不必新造 | 复用 `debt.unit().key()` |
| Q1-4 | 无任何测试/工具对 hex 视图做**键集合**断言（`grep containsOnlyKeys|containsKeys` 在 gui 测试包 = 0；`EconomyHexTool` 原样透传视图） | 新增键是**纯增量**、不破坏契约 | 可安全加键 |

## 2. 我的实现架构（数据流与落点）

### 2.1 Q4：`MERCHANT_CAPACITY_HOUSEHOLD` 补 `day`（可观测性；零行为）

- 发射点从 `assemble` 内部**上移到 tick 面的装配调用方**（`EconomySettlement` 装配完立刻调），新增池的**只读实例方法**
  `MerchantCapacityPool.logHouseholdAssembly(long day)`（`:1011`，事件体 `:1021`，**`day` 为首字段**，其余字段与顺序逐字保持不变）。
- `assemble` 末尾留注释说明"为何不在这里发"（`:333-338`）；`EconomySettlement:1873` = 调用点（紧跟池装配、在 `CAPACITY_QUOTE_BOOK` 前）。
- 因事件体搬出 `assemble`、而报价表（`quotes`）只是装配入参不入池 ⇒ `quotes.hasPosted(household)` 在**装配点取一次**存进
  `Entry.posted`（新 final 字段，`:440-445`，构造点 `:293-295`）：读数与装配同源，不事后重取。
- **发射时点与改前逐值一致**：仍是"装配完立刻发"（早于市场轮、无中间写盘）⇒ `toolRemainingMilli`/`runsAffordable` 仍是装配时点镜像。

### 2.2 Q2：工具判据时点收窄（唯一改行为的一处）

- 判据量：`max(0, 当刻可用量 − 本轮已放行 × 每趟门槛)`（`select`，`:684-688`）；"本轮已放行"由
  `Entry.releasedToolMilli()`（`:502`）= `capacity.toolMilli() − remainingToolMilli` 推出（**不新增计数器**）。
- **无活视图的 4/5 参旧路径一字未动**（仍 `item.remainingToolMilli`）⇒ 夹具/纯状态读者逐值退回改前。
- 三档方向（可逐值查，写进类注 `:78-82`）：① 轮内工具**不动** ⇒ `max(0, 可用量−已放行) ≡ 装配镜像剩余 =` 旧式 `min(剩余, 可用量)`（**逐值相同**）；
  ② 工具**下降**/冻结上升 ⇒ 新判据 ≤ 旧判据（更严）；③ 只有工具**上升**时才放松（= 本次要修的偏差）。已放行减项若与提交侧已落账实扣重复 ⇒ 只是更严，绝不放松。
- 删除 `toolBlockReason` 注释里已被实测证伪的"生产路径不可达"断言，改成与实现一致的说明 + 保留更正留痕（`:819-822`）。
- 配套读数（只加日志）：`MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT` 增一栏 `releasedMilli` = **首次被拦那一刻**的减项
  （`toolBlockedReleasedMilli`，`:481`/`recordToolBlock` `:513-534`/日志 `:970-977`）—— 有它那一行才自解释
  `availableMilli − releasedMilli < neededMilli`。`toolBudgetRemainingMilli` 保留（改注为"装配镜像剩余，只作对照"）。

### 2.3 Q1：`debtPrincipal` 读口（零状态改动、纯增量键）

- `economyHex` 顶层新增 `debtPrincipalByUnit` / `creditPrincipalByUnit`（`TreeMap<unitKey, Σprincipal>`，键 = `DebtUnit.key()`，
  与标量**同一趟遍历、同一批合同** ⇒ Σ 逐值等于标量；`:1216/:1221` 声明、`:1330/:1341` 累加、`:1429/:1435` 发出）。
- 顶层标量**保留**，另发 `debtPrincipalNote` / `creditPrincipalNote` = 常量 `DEBT_PRINCIPAL_NOT_COMPARABLE`
  （`:1994`，唯一拼写点），标注"跨 unit 混算 ⇒ 不可比"并把读者指向分列键与 `dashboard.stocks.debtPrincipal.byUnit`。
- `economyHex` 类注追加该批说明（`:1156-1162`）。**不改任何债务状态、不改任何算式、不删任何既有键。**

## 3. 关键判断（为什么这样拆 / 推翻过什么）

1. **Q4 的三条路线**：(a) 给工厂加 `day` 参（新重载或改 6 参签名）—— 夹具没有世界日 ⇒ 要么编造 `day=-1` 进日志、要么事件形状随调用路径变；
   (b) 把事件挪进 `logRoundSummary(day)` —— `day` 现成，但**非开市日整段读数消失**（Q4-4），而本修的目的正是**增强**可观测性；
   (c) **采用**：新增只读 `logHouseholdAssembly(long day)`，由 tick 面调用（Q4-5 的既有先例），既有签名零改动、夹具零影响、发射时点与值不变。
   ⇒ 选 (c)。代价如实记：新增 1 个公开只读方法（纯日志、无状态）；`Entry` 多一个 `posted` 快照字段。
2. **"本轮已放行"用镜像反推而不是新字段**：`releasedToolMilli() = max(0, 装配镜像 − 剩余)` 与既有 `:703` 的扣减是**同一个数的两种写法**
   ⇒ 不引入第二个真相源（本仓最忌"两处各记一遍而漂开"）。`remainingToolMilli` 因此可为负（工具上升时已放行超过装配可用量）——写进字段注释，是真实读数不是错值。
3. **顶层标量：保留 + 标注，不删**（定案留给我判断的那一半）：删除会**静默改契约**并让 `GuiApiTest:992`／GUI 面板／MCP 读工具的既有消费者当场失效
   （Q1-2）；而本仓禁的恰是"看起来在记、其实永远不被读"，保留 + 具名标注 + 给出可用分列键，信息量严格大于删除。
4. **债权人侧一并分列**（超出字面范围的一小步，主动记录）：定案只点名 `debtPrincipal`，但 `:1322-1323` 的 `creditPrincipal` 是**同一条事实的另一半、
   同一个缺陷**（诊断 §1「两侧 Σ 逐值相等」）⇒ 只修一半会把"读口自相矛盾"原样留在隔壁。新增键、零行为、零删除 ⇒ 判定为"已确认发现的当场修复"（AGENTS §五.3）。
   ★ 若控制方判定此步越界，删掉 `creditPrincipalByUnit`/`creditPrincipalNote` 即可（两行 put + 循环里一行 merge），主判据不受影响。
5. **没有动 `panels.js`**：GUI 的 `debtText`（`panels.js:565-575`）仍显示混算标量。"GUI/读口按需跟进"我按"读口先修、GUI 由控制方裁定"执行 ——
   改前端显示会触碰前端门禁（`run-gate.cjs` 下界 402 与 `economy-panel.test.cjs`），且显示口径（是否分列、怎么摆）属用户裁定范畴，不在"三处小修"内。

## 4. 改动文件（3 个；红线核对）

| 文件 | 落点 | 性质 |
|---|---|---|
| `simos-economy/src/main/java/io/mosire/simos/economy/time/MerchantCapacityPool.java` | `:70-82` 类注（新判据 + 三档方向）、`:214-228` 生产装配点注、`:293-295` `posted` 快照、`:333-338` 发射点注释、`:440-462` 字段注释、`:478-499` `posted`/构造、`:502` `releasedToolMilli`、`:481`+`:513-534` `recordToolBlock`、`:630-640` `select` 类注、`:674-697` 判据、`:809-827` 归因三档 + 更正断言、`:968-979` 日志读数、`:998-1060` `logHouseholdAssembly` | Q2 行为 + Q4 可观测性 |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java` | `:1858-1873`（`:1873` = 调用行） | Q4 调用点（1 行 + 注释） |
| `simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java` | `:1156-1162` 类注、`:1212-1221` 声明、`:1326-1343` 累加、`:1424-1437` 发出、`:1985-1999` 常量 | Q1 读口 |

- 红线核对：**无** `src/test/**`、**无** `pom.xml`、**无** `docs/**`、**无** `AGENTS.md`、**无** `.superpowers/**`（除本账本）、未 `git commit`。
- `git status --porcelain` 实测 = 恰上面 3 个 `.java`（`spotless:apply -pl simos-economy,simos-app` 未波及其它模块）。
- 数值标定值：**一个都没改**；五条铁律/模块边界/唯一写口：未触碰（Q2 只改 `select` 的判据算式，不新增写口；Q1 纯读）。
- 确定性 I7：未新增 `Map.copyOf`/`Set.copyOf`；新增两处 `TreeMap`（键序确定）在**读口视图**里（与同文件其余导出表同款）。

## 5. 真实命令与结果（本账本的全部验证）

```bash
cd /home/cna/SimulatorMosire
MVN_LOCK_WAIT=900 tools/mvn-lock.sh -q spotless:apply -pl simos-economy,simos-app   # rc=0（PIPESTATUS 显式取）
MVN_LOCK_WAIT=900 tools/mvn-lock.sh -DskipTests compile                             # BUILD SUCCESS / 16 模块全绿
```
- 第二轮 compile 的实读：`Reactor Summary` 16 个模块全 `SUCCESS`、`BUILD SUCCESS`、`Total time: 8.050 s`；
  `simos-economy` 3.616 s、`simos-app` 3.212 s（**重编了**，不是增量跳过）；`simos-app` 段有 `You have 0 Checkstyle violations.`
- **没跑**：`test` / `verify` / `package` / 任何真实 world / 前端门禁（按纪律只到编译）。

## 6. 会改变数值行为的清单（给测试 Agent 的输入）

1. **唯一改行为处 = 工具门槛判据量**（`select`，`:684-688`）：装配时点镜像**偏低**造成的过严拦截消失 ⇒ **被拦趟数减少**
   （诊断 A 世界基线 = 29 行 / Σ`budgetBlockedRuns` = 5,824，修复后**预期下降但不归零**：真放行若干趟后用尽预算仍应拦、且仍归因
   `tool-budget-exhausted`）；**跨格成交回升**（被拦的承运不再被截断）、连带 `CARRIER_FEE`/工具燃烧/劳动腿随成交回升。
   ★ **不给数字**（诊断要求"给方向不给数字"）。逐笔可核：新判据 `availableMilli − releasedMilli ≥ 1000` 才放行。
2. **逐值不变的边界**（可用于断言）：① 4/5 参旧路径（无活视图）**一字未变**；② 6 参生产路径下"该户轮内工具不动"时
   **与改前逐值相同**（§2.2 的三档①，恒等式 `max(0,A−k·1000) = min(A−k·1000, A)`）；③ 工具下降时只会更严。
3. **读数面**（不是行为）：`MERCHANT_CAPACITY_HOUSEHOLD` 多 `day`（首字段）；`MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT` 多 `releasedMilli`；
   hex 视图多 4 键（`debtPrincipalByUnit`/`creditPrincipalByUnit`/`debtPrincipalNote`/`creditPrincipalNote`）。**不改任何既有键的值**（`debtPrincipal` 仍是原标量）。
4. **受影响的硬编码字面量**：无（`TOOL_MILLI_PER_HAUL = 1_000` 未动；未新增任何数字常量）。

## 7. 会改变既有测试断言的清单（如实：我按静态核，未跑测试）

- 读到的**唯一**与本次改动相邻的既有断言：`GuiApiTest.java:991-992`（`debtCount`/`debtPrincipal` 为 0）—— 两键都**保留且值不变** ⇒ 不失效。
- `MerchantCapacityAcceptanceTest` 的池用例（`:75/143/177/194/208/306` 走 4 参重载；`:189` 断言 `toolBlockedRuns()==1`）—— 4 参路径**一字未变** ⇒ 不失效。
- `economy-panel.test.cjs:79`（`debtCount=0 ⇒ 「无」`）—— 未动 `panels.js` ⇒ 不失效。
- ★ **未验证项**：我没有跑 `test`/前端门禁，以上是**静态核对**（grep + 读断言），不是跑出来的绿。

## 8. 我没做 / 没验证的（如实）

1. **没跑任何测试、没跑真实 world、没跑前端门禁**（纪律：只到编译）。§6 的方向性预期**未实测**（依据 = 诊断账本 §2.2 的样本 + 本次算式的逐档推导）。
2. **未验证** A 世界修复后 `budgetBlockedRuns` 的具体降幅，也**未逐户验证**那 29 行里有多少是"已放行 = 0"（可全解除）与多少是"已放行 > 0"（仍拦）。
   ★ 样本推算：`day=153` 那条 `available=1610 / budgetRemaining=925` 只有在**该户当时尚未放行任何一趟**时才与 `assemblyAvailable=925` 自洽
   ⇒ 该样本预期被解除；但 `releasedMilli` 是**本次新加**的读数，旧日志里没有它，无法事后回算 ⇒ 留给下一轮真实 world 读数核对。
3. **未改 GUI 显示**（§3.5）；**未给 `EconomyHexTool.description` 补新键说明**（它只承诺"旧键形状不变"，仍成立）。
4. **未核** `remainingToolMilli` 变负对其它读数消费者的影响面：已静态确认它只出现在本文件（日志/`remainingToolMilliOf` 零调用点）⇒ 无外部消费者。
5. **未核**"`MERCHANT_CAPACITY_HOUSEHOLD` 搬到 tick 面后，是否有第三方（脚本/文档）按旧行序解析"—— 字段集与顺序**逐字未变**、只多 `day`，且发射点时间序不变（仍早于市场轮）。
6. **未处理**诊断 §5 的其余条目（§3 `LOGISTICS_CAPACITY` 表述、§4 G3 账本更正行、待裁定第 7/8 条）—— 不在本责任区。
7. 未核 `.superpowers/sdd/2026-10-10-diagnose-debt-anomaly/` 里 `Q1` 提到的 `SocialHouseholdsTool:183` 那条 `debtPrincipal` 指标：它走
   `HouseholdQueryService.indexDebts`，**本来就按 unit 分列**（`:686-695`）⇒ 不属于本次缺陷面（未改）。
