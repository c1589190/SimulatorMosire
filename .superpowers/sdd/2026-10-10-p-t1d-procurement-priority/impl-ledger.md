# P-T1d 政府采购优先级（挂单置顶 + 行政力池 + 见底硬停）—— 实现架构账本

- 责任区：**P-T1d**（写码 Agent；只写生产代码、只到编译过、不写/不改测试、不跑 `test`/`verify`、不 `git commit`）
- 权威设计书：`docs/superpowers/specs/2026-10-09-port-policy-and-zone-efficiency-design.md` **§16.3 / §17（+§16.2 删除清单、§16.4 M-2）**；
  `docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` **§2.2 / §2.6（第 5 步）/ §3（I-C2/I-C5/I-C7）/ §5（T9/N3）/ §6.2 V-8**、§7 Q-4/Q-7
- 前批账本（注入形制与接缝先例）：`.superpowers/sdd/2026-10-10-p-t1a-port-throttle/impl-ledger.md`、
  `2026-10-10-p-t1b-transit-tax/impl-ledger.md`、`2026-10-10-p-t1c-regulation-purge/impl-ledger.md`、`2026-10-10-p-t1e-currency-gate/impl-ledger.md`
- 基线：工作树 `ede1f349`（P-T1e 之后、本批改动前），未提交任何东西
- **本批不做**：G3 真实 world 复测；测试（另一批）；卖家赊购（Q-26）；挂单簿持久状态；扩编官僚队伍的招人/俸禄（Q-7，只留接口）

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 证据（本批改动前；P-T1e 之后行号） | 结论 | 影响 |
|---|---|---|---|
| C1 | `MarketSettlement:4214 matchGroup` + `:4746 matchRoute` 是**仅有的两条**商品撮合路径；两条都用 `ordered.sort(costOrder(route))` 排卖方、买侧**不排序** | "置顶"的落点只有这两处（信用轮/FX 轮不撮合商品，且国库户不进信用、不生成 FX 单） | 只需在这两处把"顺序"换成"名次"，不改任何算式 |
| C2 | `ProducerCostBook:261 landedCostMilli(unitCost, freight) = unitCost + freight × ESTIMATE_SCALE` | 到货成本对单位成本是**加常数** ⇒ 同一条车道上按到货成本排序 ≡ 按单位成本估计排序 ⇒ **成本序与车道无关** | 卖侧名次可以在**区 × 商品**一张簿上算一次，所有车道/分层共用（这是本批能做成"一次算、处处用"的关键） |
| C3 | `ProportionalSplit:126 distributeByLargestRemainder`（最大余数法、同余数按**下标**升序）；`matchGroup` 的买侧是**唯一一组**按权重比例分配 | 需求侧"排在前面"在**同一组内**几乎看不见（只影响 ±1 毫的余数破平） | 若只做"列表重排"，**买侧的置顶等于没做** ⇒ 必须把买侧按"档"切开（见 §3-2 与 §4-1，**这是本批最需要控制方复核的一条**） |
| C4 | `MarketSettlement:1481 ctx.buys.addAll / :1485 orderIndex = i`；`FillIntent` 用 `orderIndex` 定位回放目标（`:3937` 的 canonical 键自检） | **不能重排 `ctx.buys`/`ctx.sells`**（重排会移动 `orderIndex`、打乱回放） | 置顶 pass 改成"**只写槽位上的名次字段**"，撮合侧按名次取序 ⇒ 列表与回放一个字不动 |
| C5 | `MarketIndexes.build:3514` 建 `buysByRegionCommodity`/`sellsByRegionCommodity`（**区 × 商品**桶，桶内保持插入序） | "挂单簿"的自然口径 = **（市场区 × 商品 × 买/卖侧）**（区内撮合的分组正是它；跨区车道是它的**子集**） | 名次在"区簿"上算一次、跨区车道按名次取子序列 ⇒ 池子**只扣一次**（不会每条车道各扣一遍） |
| C6 | `prepareRegion:3711` 用 `new BuySlot(other)`/`new SellSlot(other)` 建 worker 副本；`copyForWorker:880` 逐字段带过注入值 | 新字段（名次/是否推进）与新注入值都必须进**两条克隆路径** | 两处都改了；否则 worker 与协调器顺序漂开 ⇒ `replayRegionOutcome` 具名抛 |
| C7 | `GovernmentMarketMandatePlan:79-85` 从 `EconomyData.governments()` 取国库户（`Government.treasury()`）；`PortRegimeBridge:527 governmentShareOf` 用 `GovernmentIds.ofUnit(ownerKey)` 反查 | "哪个家户是某政府的国库户"有权威答案，组合根算得出 | 注入表键 = 国库户 `HouseholdId`（**不按 `hh-gov-` 前缀猜**） |
| C8 | `PopulationEconomyTimeParticipant:715-790`：`stepper.step(day)` 在**前**，gov 效率/口岸折算/税注入在**后** | 既有形制 = **一 tick 滞后**（折算读当日结算后的编制，注入给下一次市场轮） | 本批沿用同一时序（写进 §3-6，不另造"当轮生效"） |
| C9 | `GovPayloads:187 portPolicy` + `GovPortPolicy`（`noRules()` 在 `*/src/main` **零消费者**）；`GovChangeSet` 的 `portPolicies` 是**值整体**序列化 | 给 `GovPortPolicy` 加一个布尔分量**不需要**改 Codec/ChangeSet；`noRules()` 无生产消费者 | 触发可以走**既有命令 `gov.SetPortPolicy`**（不新增命令、不新增权限面） |
| C10 | `*/src/test` 对 `GovPortPolicy`/`noRules()`/`portPolicies` **零引用**（复测）；只有 `gov.SetPortPolicy` 的**类型字符串**与"顶层未知字段"载荷（`McpCoverageTest:490`、`SimosToolsTest:556`） | 改这些形状不会让测试**编译**失败 | §7-3 的失效清单只有"语义/文案"面 |

---

## 2. 实现架构（代码怎么把这个世界长出来）

```
写侧（法律）  gov.SetPortPolicy ──GovPayloads.portPolicy──▶ GovPortPolicy{commodityRules, currencyRules, marketControl}
              marketControl = true ⇔ 用户「政府要求管控市场」（缺省 false ⇒ 逐值不变，I-C2）
              ★ 沿用既有命令与 GmOnly 权限面：**不新增命令类型、不新增权限面**

折 算（组合根）ProcurementPriorityBridge.compute（唯一折算点）：
   逐 GOV（政策表键，unitId 升序）：
     ① controlsMarket() = false ⇒ 跳过（缺省语义中性）
     ② 编制劳动力 = supply(unitId).securityLaborMilli + paperworkLaborMilli   ← **行政维**；口岸维不进（§16.4 M-2）
     ③ 一份行政力 = 一个**标准岗位**一 tick 的劳动定额（C8 唯一权威）= 一个家户（§17.4 N-1）
        行政力池 = ⌊② ÷ ③⌋（单位 = 户）
     ④ 国库户 = GovernmentIds.ofUnit(unitId) → economy.governments() → Government.treasury()
        （非 HOUSEHOLD ⇒ 具名 INFO + 跳过；无政府记录/单位 id 非法同理）
   ⇒ ProcurementPriorityInput{国库户 → 池}（逐轮瞬态、不落盘）

注 入（组合根）PopulationEconomyTimeParticipant：procurement.active() ⇒ stepper.updateProcurementPriority(input)
   ⇒ EconomyDayStepper.step → EconomySettlement.settleOneDayInto(+procurementPriority)
   ⇒ marketRound.withProcurementPriority(…)（逐字段带过前五个字段 + 具名 ERROR 契约守卫）

执 行（经济）  MarketSettlement.clearOncePerCycle（§2.6 第 5 步：置顶在撮合之前）
   applyProcurementPriority(ctx, indexes)：
     簿 = MarketIndexes 的（区 × 商品 × 买/卖侧）桶；先买侧全部簿、后卖侧全部簿（池跨簿共享，顺序固定）
     ProcurementPriorityOrder.advance(簿, 这一槽属于哪一户, 写名次, 写"是否推进", 注入值, 剩余池)：
       逐政府（国库户 id 升序）→ 逐该政府的槽位（服务序）→ 从"已放置块之后"逐只向前越，
       **每越一户扣一份**；池 = 0 ⇒ 就地停住（fail-closed）；同政府多单保持 canonical 序
     产出：procurementRank（全序名次）+ procurementOvertook（是否真越过 ≥1 户）
   撮合（matchGroup / matchRoute，两条路径同一套改法）：
     卖侧服务序 = 无管控 ? costOrder(route) : 按名次        ← 逐值不变的判据在 servingOrder()
     买侧服务序 = 无管控 ? 原列表   : 按名次
     "档" = 相邻且**同成本 + 同推进状态**的连续段；买侧按"档"（相邻同推进状态的连续段）逐段配给
     ★ 价格、限价、min(demand, supply, 运力, 口岸闸)、比例切分、逐笔封顶 —— **一个字都没改**

日 志（§一.9）  INFO: PROCUREMENT_PRIORITY_REGIME_INJECTED（组合根：几个政府要求管控、池多大、几个池=0）
                     MARKET_PROCUREMENT_PRIORITY_APPLIED（经济侧：本轮消耗几份、推了几只单、剩多少、几次硬停）
                DEBUG: PROCUREMENT_PRIORITY_POOL_COMPUTED（这一户为什么能超这几户）
                       PROCUREMENT_PRIORITY_GOVERNMENT_SKIPPED / _NO_STANDARD_QUOTA（为什么这个政府进不来）
                       MARKET_PROCUREMENT_PRIORITY_HARD_STOP（见底硬停：付不起哪一户、落在第几位）
```

**改动文件（11 个：8 改 + 3 新；全在允许写的 `src/main` 内）**

| 文件 | 落点 |
|---|---|
| `simos-gov/.../GovPortPolicy.java` | 第三分量 `marketControl` + `controlsMarket()`；`noRules()` 把开关算进去；类注写明"为什么放这里" |
| `simos-gov/.../spi/GovPayloads.java` | `marketControl` 载荷解析（缺失 = false；非布尔 = 具名拒） |
| `simos-gov/.../spi/SetPortPolicyHandler.java` | INFO 计数加 `marketControl`；载荷样例与类注 |
| `simos-economy/.../time/ProcurementPriorityInput.java`（**新**） | 注入类型：国库户 → 行政力池（保序冻结；`none()`/`isActive()`/`controls`/`unitsOf`） |
| `simos-economy/.../time/ProcurementPriorityOrder.java`（**新**） | 置顶推进的**唯一算法实现**（买/卖两侧共用；返回消耗/推进/硬停读数） |
| `simos-economy/.../time/EconomyDayStepper.java` | 字段 + `updateProcurementPriority`/`procurementPriority`；`step` 传参 |
| `simos-economy/.../time/MarketRound`（在 `MarketSettlement.java` 内） | 第六个注入字段 + `withProcurementPriority` + 访问器 + **五个既有 `withX` 与 `copyForWorker` 全部带过** |
| `simos-economy/.../time/MarketSettlement.java` | `applyProcurementPriority`（逐簿喂进算法）+ 轮级 INFO；槽位两个字段（含两条克隆路径）；`servingOrder`/`procurementOrderedBuys`/`procurementBuyRuns`；`matchGroup`/`matchRoute` 按序 + 按档 |
| `simos-economy/.../time/EconomySettlement.java` | `settleOneDayInto` 新重载（旧签名委托 `none()`）+ 注入 + 具名契约守卫 |
| `simos-app/.../time/ProcurementPriorityBridge.java`（**新**） | 折算（政策 × 编制劳动力 ÷ 标准岗位定额）；具名 INFO/DEBUG |
| `simos-app/.../time/PopulationEconomyTimeParticipant.java` | 在 gov 效率算完之后折算并注入 + INFO |
| `simos-app/.../tools/read/CatalogTool.java` | `gov.SetPortPolicy` 的 `PAYLOAD_HINTS` 加 `marketControl?` |

---

## 3. 关键判断（为什么这样拆；调查中推翻/否掉过什么）

1. **触发不做成新命令、也不做成新持久组件**：`GovPortPolicy` 就是"政府对本市场区的法律规定层"的既有载体（§4.5 G9），
   `gov.SetPortPolicy` 已是 GmOnly 的既有命令面 ⇒ 加一个布尔分量 = **零新权限面、零 Codec/ChangeSet 改动**（C9 实测）。
   ★ 被否掉的另一条路线：新开 `GovState` 第六组件 + 新命令 `gov.SetMarketControl` —— 语义更"干净"，但要动
   `GovState`/`GovCodec`/`GovChangeSet`/往返不变式，而派单冻结口径明写"沿用既有 `gov.*` 政策族与 GM 命令面、不新增权限面"⇒ 取前者。
2. **"只改顺序"的兑现方式：名次字段 + 按档配给**。★ **这一条需要控制方复核**：
   - 卖侧：名次直接取代 `costOrder` ⇒ 政府单**先被卖**（"最先卖"是真的）。
   - 买侧：既有需求侧是**唯一一组按权重比例分配**（C3）⇒ 光排序等于没做（只影响 ±1 毫余数）。
     本批把买侧按"相邻同推进状态"切**档**，被推进的政府单自成一段、先配给，剩下才是众家户那一组。
     ★ 这是"顺序的**分组**形态"，不是新算式：价格、限价、`min(...)`、`ProportionalSplit` 一个字没改；缺省（无管控）⇒ 恰好一段 ⇒ **逐值不变**。
     ★ 若控制方认为"切档 = 改了量规则"，请裁定：撤回切档后**买侧的置顶将不可观测**（只剩执行序与余数破平）。
3. **簿的口径 = （市场区 × 商品 × 买/卖侧）**（C5）：区内撮合的分组正是它；跨区车道是它的子集 ⇒ 名次在区簿上算一次、
   所有车道按名次取子序列 ⇒ 池子只扣一次（若按车道各算一遍，"前方多少户"会随车道漂开、池子被重复扣）。
4. **不重排 `ctx.buys`/`ctx.sells`，只写槽位名次**（C4）：`orderIndex` 是 `FillIntent` 回放的定位键，重排会静默打乱回放。
   代价 = 撮合侧要按名次排序（两条路径各一次 `sort`；无管控时走原比较器，不排序）。
5. **成本口径**：① "一户一份"= 越过的 **distinct 家户**（同一户的 3 张单只收 1 份，§17.4 N-1）；
   ② 同一（簿 × 政府）内同一户**只收一次**（政府已经越过它了，同政府后续单再越它不再收费）；
   ③ 付账对象 = **从近到远**的户（从位置 `p-1` 往目标位走）；④ 池 = 0 ⇒ 就地停住（**不是**"先超了再欠"）。
   ★ 被否掉的读法：**按"canonical 序"从簿首开始超**（即先超最前面那几户）——它与"每越过一户扣一份"自相矛盾
   （要越过第 1 户，物理上必须先越过它后面的每一户，那就得为没付账的户让路）。⇒ 取"就近向前推进"。
6. **时序沿用既有形制（一 tick 滞后）**（C8）：折算读"当日结算之后的编制供给"，注入作用于**下一次**市场轮。
   理由：口径与口岸管制力/三层税**同源**（都是当日结算后才有的政府读数）；单方面改成"当轮生效"会让同一批注入值有两个时点。
7. **池 = 编制劳动力 ÷ 标准岗位定额**（"一份行政力 = 一个标准岗位一 tick 的劳动定额 = 一个家户"）：
   编制本来就是按**岗位**计的，而 C8 的 `standardLaborMilliHoursPerTick()` 是"一个岗位一 tick 的劳动"的**唯一权威** ⇒
   用它当换算率，既不新造标定常量、也让"扩编（加岗位）⇒ 池变大"自然成立（Q-7 的接口就在这里：扩编只要抬高编制劳动力，池自动变大）。
8. **`controlsMarket()=true` 但池 = 0 ⇒ 仍进注入表**（值 0）：语义 = "要求了管控，但一份行政力都没有 ⇒ 一户也超不了"
   （fail-closed），且日志看得见（`zeroPoolGovernments`）。若直接不进表，"要求了却没生效"会静默。
9. **缺省中性（I-C2）落在四处、互相独立**：① 政策缺键/`marketControl=false` ⇒ 空表；
   ② 组合根 `active()=false` ⇒ 不注入；③ `EconomyDayStepper`/`MarketRound` 缺省 `none()`；④ 经济侧 `applyProcurementPriority` 第一行
   `!isActive() ⇒ return`（**一行不跑**，槽位名次保持 `PROCUREMENT_RANK_ABSENT`）⇒ 撮合走原比较器。
10. **I7 确定性**：簿遍历序 = 索引建表序（= 槽位插入序）；簿内政府序 = **国库户 id 升序**；同政府槽位序 = 服务序；
    付账对象 = 就近 distinct 家户。新增容器一律 `LinkedHashMap`/`LinkedHashSet` + `Collections.unmodifiableMap`（**无** `Map.copyOf`/`Set.copyOf`、无随机数、无时钟）。
11. **不碰守恒**：本批不搬钱、不铸转移、不动 `applyTransfer`；置顶只改"谁先被配到"⇒ 账本/冻结/税/运费一律不动。

---

## 4. 偏离记录（与约束设计书/派单不一致处及原因，主动记）

| # | 差异 | 原因 |
|---|---|---|
| 1 | **买侧"切档"**（§3-2）：被推进的政府买单自成一段、先配给 | 既有需求侧是单组比例分配，"只排序"在买侧不可观测（C3）。★ **请控制方复核**：若判为"改了量规则"，撤回切档即可（改动只有 `procurementBuyRuns` 一处，撤回后买侧只剩执行序） |
| 2 | 计划 §2.2 写"`MarketRegulation` 只剩 `tariffPerUnit` + **采购优先级参数**"，本批**没有**把参数放进 `MarketRegulation` | 派单 §实现形态提示：池与"是否要求管控"由组合根从 gov 切片折算后**注入** economy（照 `PortEnforcementInput`/`PortTaxInput` 先例）。`MarketRegulation` 在生产路径恒为缺省实例（P-T1c C1 实测），放它里面 = 没有注入面 |
| 3 | `MarketRegulation.defined()` 的宽度复审（P-T1c 账本 §7-2 点名留给 P-T1d）**未做** | 优先级参数不进 `MarketRegulation` ⇒ 该复审对象不存在了（`defined()` 仍只判 `tariffPerUnit`） |
| 4 | 新增 `ProcurementPriorityOrder` 独立类（而不是全塞进 `MarketSettlement`） | 算法要能**同包直调做手工验证**（§5）；`MarketSettlement` 自身已 9300 行，再塞一段会挡住"唯一拼写点"的可读性 |
| 5 | 池的换算率用 C8 的 `standardLaborMilliHoursPerTick`，**没有**新造标定常量 | §17.4 N-2 说"规模与俸禄标定由实现方给具名常量"针对的是**扩编**（本批不做）；池的换算率用既有唯一权威更少一处魔数（§3-7） |
| 6 | 组合根折算**不依赖**口岸接触面（`contacts.isEmpty()` 时也照算） | 与 `PortRegimeBridge` 不同：优先级是**逐政府**的量（看政策 × 编制），与"这个政府有没有暴露边"无关 |
| 7 | 未做旧档/旧载荷兼容 | §一.11：旧档缺 `marketControl` ⇒ Jackson 给 `false` = 缺省中性（这不是兼容位，是缺省语义） |

---

## 5. 手工验证（**不是仓内测试**；测试文件一个都没写/没改，装置在 `/tmp/pt1d`，不落仓）

| 装置 | 验的是什么 | 结果 |
|---|---|---|
| `PriorityOrderCheck`（同包 `io.mosire.simos.economy.time`，直调 package-private 的 `ProcurementPriorityOrder.advance`；10 组） | ① 簿里无管控政府 ⇒ 名次=服务序、零消耗；② 池充足 ⇒ 政府单到最前、3 户 3 份；③ 池不足 ⇒ **就地停住**（只用 2 份、h1 仍在它前面、硬停 1 次）；④ 池=0 ⇒ 一户不超；⑤ 已在最前 ⇒ 不动不收费；⑥ 同政府两单 ⇒ canonical 序不反转、第二单停在第一单之后；⑦ 同一户两张单只收一份；⑧ 两政府 ⇒ 国库户 id 升序、各自付费；⑨ `household=null` 的主体不收费不阻断；⑩ 名次是全序（0..n-1 各一次） | **ALL CHECKS PASSED（10/10，40 项断言）** |
| `GovPolicyCodecCheck`（同包 `io.mosire.simos.gov`，走**真** `GovCodec`） | ① 线格式确实出现 `marketControl`，且**没有** getter 污染（`controlsMarket`/`noRules`/`empty` 零命中）；② 快照往返逐字段相同（`policy.equals`、`commodityRules`）；③ **缺该键的旧档** ⇒ 读作 `false`（不抛、不给默认 true）；④ 变更集 `diff → encode → decode → apply` 重建后 `controlsMarket=true` 且 `重建 == target`，且"空规则表 + marketControl" **不是 noop**（铁律 5 面） | **ALL CHECKS PASSED（10/10）** |
| 静态审计（`grep`） | `new GovPortPolicy(` 只剩 2 处（`empty()` + 解析器）；`withProcurementPriority`/`updateProcurementPriority` 各 1 处注入点；六个 `next.procurementPriority = …` 带过点（五个 `withX` + `copyForWorker`）；`MarketProcurementPriority` 旧名零残留；新增行无 `Map.copyOf`/`Set.copyOf`/随机数/时钟 | 一致 |
| 缺省中性论证（纸面 + 代码路径） | 见 §3-9 四处判据；撮合侧 `servingOrder()`/`procurementOrderedBuys()` 在 `!isActive()` 时**返回原对象/原比较器**，`procurementBuyRuns` 在无推进时恰好一段 | 生产缺省路径逐值不变 |

★ **没验的**：真实撮合里的端到端行为（要有"要求管控的政府 + 有编制供给 + 市场有对手方"的世界）、跨区车道上的名次、
worker 并行路径在有管控时的回放一致性 —— 见 §6。

---

## 6. 未完成 / 未验证（如实记）

1. **没跑** `test`/`verify`/`package`（派单纪律）：compile 绿 ≠ 行为对；且 economy 测试树**在本批之前就不编译**（P-T1e 账本 C10：
   `MarketRegulationTest` 引用已删的 `REGULATION_QUOTA`）⇒ 开发期**没有行为回归网**（AGENTS §三.0 已接受的对冲）。
2. **没有真实世界跑数**：`MARKET_PROCUREMENT_PRIORITY_APPLIED` / `_HARD_STOP` 的实际读数**未观测**；真实世界里
   "政府编制供给 > 0 且要求管控"的组合**未核**（G3 复测是本链后续批次）。
3. **未验**：worker 并行路径在有管控时的 `replayRegionOutcome` 一致性（静态论证：名次与是否推进都在槽位副本里逐值照抄、注入值经
   `copyForWorker` 带过 ⇒ 两侧同序；**未实测**）。
4. **未验**：`MARKET_PROCUREMENT_PRIORITY_CONTRACT` 守卫的触发路径（要构造"with 链丢字段"才触发）。
5. **未做**：扩编官僚队伍（Q-7 只留接口：加岗位 ⇒ 编制劳动力 ↑ ⇒ 池自动变大）；"最先卖/最先买是否各付一次"（Q-4）本批实现为
   **买卖两侧各自付费、共享同一个池**（买侧簿先、卖侧簿后）；卖家赊购（Q-26）；挂单簿持久状态。
6. **未做**：`MarketRegulation.defined()` 宽度复审（见 §4-3，已无对象）。
7. **未做**：SpotBugs/Checkstyle（属 `verify`，纪律禁止）。

---

## 7. 给测试 Agent 的输入

### 7.1 会改变数值行为的清单

| 位置 | 改前 | 改后 | 何时不同 |
|---|---|---|---|
| `matchGroup`/`matchRoute` 的卖侧顺序 | `costOrder(route)` | 有管控时 = 置顶名次 | **只有**：注入表非空（某政府 `marketControl=true` 且算得出池）**且**该簿里有该政府国库户的卖单**且**它被推进（越过 ≥1 户） |
| 同上，买侧顺序与"档" | 列表序、唯一一组比例分配 | 有管控时 = 名次序 + 按"同推进状态"切档 | 同上（被推进的政府买单先配给） |
| 池的消耗 | —— | 每越一户扣一份、见底硬停 | 同上；**池永不为负** |
| 其余（订单、价格、限价、`min(...)`、比例切分、逐笔封顶、税、运费、冻结、回放序） | —— | **一个字未改** | **永不相同** |

**缺省中性论证（I-C2 / N1）**：没有任何 GOV 设 `marketControl=true` ⇒ 组合根返回空表 ⇒ 不注入 ⇒
`applyProcurementPriority` 第一行返回（名次保持 -1）⇒ `servingOrder()` 返回原 `costOrder`、`procurementOrderedBuys()` 返回原列表、
`procurementBuyRuns()` 恰好一段 ⇒ 与改前**逐值相同**。★ 单区/无政府/无编制世界同样落在这一支。

### 7.2 受影响硬编码字面量（夹具要跟着改）

- 载荷：`gov.SetPortPolicy` 新增可选 `marketControl`（布尔；非布尔 ⇒ 具名拒）。
- 新事件名：`PROCUREMENT_PRIORITY_REGIME_INJECTED`、`PROCUREMENT_PRIORITY_POOL_COMPUTED`、
  `PROCUREMENT_PRIORITY_GOVERNMENT_SKIPPED`、`PROCUREMENT_PRIORITY_NO_STANDARD_QUOTA`、
  `MARKET_PROCUREMENT_PRIORITY_APPLIED`、`MARKET_PROCUREMENT_PRIORITY_HARD_STOP`、`MARKET_PROCUREMENT_PRIORITY_CONTRACT`。
- 新类型：`ProcurementPriorityInput`（`none()`/`isActive()`/`controls`/`unitsOf`/`governmentCount`/`totalUnits`/`treasuries`）、
  `ProcurementPriorityBridge.ProcurementPriorityDay`、`MarketSettlement.PROCUREMENT_RANK_ABSENT = -1`。
- ★ **口径常量**：一份行政力 = 一个标准岗位定额（`SocialProvisioning.standardLaborMilliHoursPerTick()`）= 一个家户。

### 7.3 会让既有测试失效的清单

1. **编译期：本批 0 处**（C10：`*/src/test` 对 `GovPortPolicy`/`noRules()`/`portPolicies` 零引用；`settleOneDayInto` 旧签名保留委托；
   `MarketRound` 旧构造器与 `clearOncePerCycle` 旧签名未动）。
2. `McpCoverageTest:490` / `SimosToolsTest:556` / `GovToolsZ6Test:391-392`：只引用类型字符串或"提示键存在" ⇒ **不受影响**。
3. **可能受影响但只在"有管控"的世界才有断言差异**：任何构造"政府挂单 + 稀缺对手方"的市场用例 —— 全仓 `src/test` 里
   `marketControl`/`ProcurementPriorityInput` 零命中 ⇒ 现状不会红。
4. ★ economy 测试树**本批之前就不编译**（P-T1e C10）⇒ 本批形状变更在测试面**无法被编译期发现**。

### 7.4 建议的验收判据（按约束设计书，不按代码反推）

- **T9**：政府要求管控 ⇒ 其**卖单**先成交（同价同质、需求稀缺时）；超越户数 = 消耗的行政力；池空 ⇒ 后续不再置顶
  （用 `ProcurementPriorityInput` 直接构造：池 = k ⇒ 恰好 k 次"越过一户"）。
- **T9'**：买家侧同理（被推进的政府买单先被满足）。
- **N3（fail-closed）**：池不够越过下一户 ⇒ **不越过**、池不为负、后续政府单保持原序（`MarketSettlement.PROCUREMENT_RANK_ABSENT`
  与 `procurementOvertook` 是可直接断言的落点）。
- **N1（缺省中性）**：未设 `marketControl` ⇒ 同一世界两跑逐值相同、且与改前基线逐值相同（md5 级）。
- **I7**：同一 revision 同一 tick 两跑、1/4/8 线程 ⇒ 名次/成交序逐值相同。
- **负向**：`marketControl` 非布尔 ⇒ 具名拒、零 revision；`controlsMarket()=true` 但编制供给 = 0 ⇒ 进表但池 = 0（**不置顶**，且日志有 `zeroPoolGovernments`）。

---

## 8. 门禁证据（真实命令与结果，2026-10-10 07:2x，工作树 `ede1f349` + 本批改动）

```
$ tools/mvn-lock.sh -q spotless:apply                  → [spotless exit=0]
$ rm -rf simos-{economy,gov,app}/target/classes
$ tools/mvn-lock.sh -DskipTests compile                → BUILD SUCCESS，16/16 模块 SUCCESS [compile exit=0]，9.395s
    SimulatorMosire/Util/Map/Calendar/SocialApi/ActorApi/EconomyApi/Social/Unit/Core/SD/Actor/Economy/Gov/Army/App
$ java -cp … io.mosire.simos.economy.time.PriorityOrderCheck   → ALL CHECKS PASSED（10/10，40 项断言）
$ java -cp … io.mosire.simos.gov.GovPolicyCodecCheck           → ALL CHECKS PASSED（10/10）
```

（两个装置都在**最终字节码**上重跑过：`spotless:apply` + `rm -rf target/classes` + 真重编之后。）

**改动规模**：`git diff --stat` = 8 文件 `+565 / −67`（另 3 个新文件未跟踪：`ProcurementPriorityInput`/`ProcurementPriorityOrder`/`ProcurementPriorityBridge`）。
