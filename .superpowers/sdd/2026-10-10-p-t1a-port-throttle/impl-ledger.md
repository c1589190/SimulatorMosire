# P-T1a 实现架构账本 —— 口岸政策四元组 + 跨区"两侧节流" + 具名归因

日期：2026-10-10　责任区：P-T1a（写码 Agent）　状态：**生产代码完成 + 编译绿**（未跑 test/verify；未提交）
约束设计书：`docs/superpowers/specs/2026-10-09-port-policy-and-zone-efficiency-design.md` §4.2–§4.4 / §10–§12（§13 税不做）；
`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §2.2/§2.6/§3/§5；
`docs/superpowers/reports/2026-10-10-port-merchant-consistency-check.md` §14–§18。

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | 证据（file:line） | 结论 | 影响 |
|---|---|---|---|
| 1 | `simos-app/.../MarketTopologyBook.java:170-178`（持久区优先）+ `:235-241`（`new MarketNode(zone.zoneId().value(), ...)`） | 有持久市场区时，`MarketRegion.node().nodeId()` **就是** `MarketZone.zoneId().value()` | 与 `PortExposureEdges:104-105` 的注入区键**同一套键** ⇒ 节流查表能命中（本批必核的接缝，结论：**同键**） |
| 2 | `simos-app/.../MarketTopologyBook.java:163-164`（`markets` 空 ⇒ `singleHex`）、`:179-194`（无持久区 ⇒ singleRegion/byCityRadius） | 无持久区 ⇒ `MarketZoneBook.zones()` 为空 ⇒ `PortExposureEdges.contacts()` 为空 ⇒ `PortRegimeBridge` 早退 `PortEnforcementInput.none()` | 不存在"注入表用了另一套键"的合法场景 ⇒ 键不匹配只可能是**契约故障**，可以 fail-closed |
| 3 | `simos-economy/.../MarketSettlement.java` `matchAcrossRegions`（唯一跨区商品路径，`:4125`）；`creditRound`（`:2509`）/`CreditPools`（`:3065`）按 `regionId` 建池、按 `sell.regionId` 消费 | 跨区商品候选**只有这一条**路径；信用轮是**区内**的（不放贷跨区货物） | 闸只装在这一处即可覆盖商品维；信用/FX 不构成绕过 |
| 4 | `MarketSettlement` `BuySlot.regionId` / `SellSlot.regionId`（建槽位时 = `region.node().nodeId()`） | 槽位已有区 id，不必新引入查表 | 节流实现无新增状态、无第二权威 |
| 5 | `MarketSettlement:1818-1820`（`marketRound.withGovMandates(...).withPortEnforcement(portEnforcement)`） | 注入值确实进到市场轮的 `MarketRound.portEnforcement()` | 节流能看到组合根折出的表（不是死代码） |
| 6 | `GovCodec.java:56-83`（严格 `FAIL_ON_UNKNOWN_PROPERTIES` + 三路键反序列化器；值类型**零 Jackson 注解**） | 持久值类型上**任何 getter 形态的方法**都会被写进线格式 | 手工往返实测 `UnrecognizedPropertyException: Unrecognized field "effective"` ⇒ 派生判断必须用非 getter 名（见 §3-3） |
| 7 | `simos-gov/.../GovPortPolicy.java`（旧）`isEmpty()` + `GovState.portPolicies` 持久化 | 旧形状**本来就有**这颗地雷（`"empty"` 会进 JSON） | 本批重建形状时顺手拆掉（旧档不要求兼容，§一.11） |
| 8 | `simos-economy/.../MarketUnfilledReason` 一族 + `LOGISTICS_CAPACITY`（`MarketSettlement:5942` 附近 `sellerReason`） | 具名归因的形制 = 枚举档 + 卖/买槽位标记 + DEBUG/TRACE 日志 | 新增 `PORT_THROTTLED` 档，与物流档**分开**（政策 vs 物流，处置不同） |
| 9 | 全仓 `grep`：`PortEnforcementInput` / `ZonePortRegime` / `GovPortPolicy` / `MarketUnfilledReason` 在 `src/test/**` **零引用** | 改这些形状不会让既有测试**编译**失败 | 唯一提到旧载荷字面量的是 `McpCoverageTest:490`（见报告 §⑤） |

## 2. 实现架构（代码怎么把这个世界长出来）

```
写侧（法律）  gov.SetPortPolicy ──GovPayloads.portPolicy──▶ GovPortPolicy{commodityRules/currencyRules: id→PortRule}
                                                              PortRule = 入口限制‰ / 出口限制‰ / 入口税 / 出口税
                                                              PortTaxRule = {PortTaxMode, amount}（从量/从价各自指定）
折 算（组合根）PortRegimeBridge.compute(...) 逐区 × 逐类 × 逐方向：
                 surfaces(方向) = 逐归属政府的 PortContactSurface{contactKey, w, s(方向), e}
                 └─ PortRegimeAggregation.aggregate(zone, class, direction, surfaces)
                        ⇒ ZonePortRegime{zone, class, direction, allowedByRule, E, Σw, noContactSurface, 逐段读数}
                 ⇒ PortEnforcementInput{zone → class → Directional(入口‰, 出口‰)}   （存的是 1000−E）
执 行（经济）  MarketSettlement.matchAcrossRegions（协调器单线程）：
                 buildPortBudgets：逐（源区 A → 目的区 B × 商品 c，只对"有卖方 + 有活跃买方"的相邻区对）
                        transit = A 区该商品卖方剩余合计（区内撮合之后）
                        E_源 = A 的 EXIT；E_目的 = B 的 ENTRY
                        budget(A→B,c) = PortThrottle.allowedTransitMilli(transit, E_源, E_目的)   ← 唯一拼写点 ⌊transit×E_源×E_目的÷1e6⌋
                 配对循环逐（买方格 → 卖方格）：cap = budget 余量（无条目 ⇒ NO_GATE_MILLI）
                        matchRoute(..., cap)：cap 与运力/配额一起进 Math.min；cap=0 ⇒ 本配对零成交
                        成交后按**真正落账的量**扣共享预算（与 D-027 区级配额同一形制）
                 归因：BuySlot.blocked=PORT_THROTTLED / SellSlot.portBlocked=true ⇒ MarketUnfilledReason.PORT_THROTTLED
日志（§一.9）  逐车道判据 DEBUG（MARKET_PORT_LANE_GATED / _ROUTE_EXHAUSTED）、逐车道读数 TRACE（_LANE_EVALUATED）、
                 轮级汇总 INFO（MARKET_PORT_THROTTLED：gatedLanes + blockedTransitMilli）
                 组合根：PORT_REGIME_COMPUTED(DEBUG)/PORT_SURFACE_READING(TRACE)/PORT_SURFACE_TAX(TRACE)/PORT_REGIME_INJECTED(INFO)
```

## 3. 关键判断（为什么这样拆）

1. **口径：表里存"管制力 = 1000 − E"，方向各一个**。依据 plan §12.4 的原话（"表里存的是 `1000 − E`，取 `E = 1000 − 该值`"）
   ⇒ `PortEnforcementInput.Directional(entryPerMille, exitPerMille)` + `commodityOpennessPerMille(...)` 便利入口，
   减法**只有一个拼写点**（不在经济侧各处重算）。
2. **ZonePortRegime 加 `direction` 字段，而不是把两个方向塞进一个新记录**：§4.3 的 OR 规则与加权平均**逐字不变**，
   只是 s 的来源换成"该方向的限制"；两次调用同一算式 ⇒ 复用既有 `PortRegimeAggregation`（公式零改动，只是多一个入参），
   `PortContactSurface` 契约**不动**。
3. **派生判断一律用非 getter 名**（`PortTaxRule.taxFree()/leviesTax()`、`PortRule.allDefault()/hasRestriction()/hasEffectiveTax()`、
   `GovPortPolicy.noRules()`、`Directional.zero()`）：GovCodec 保持严格读侧、值类型保持零 Jackson 注解（本仓口径），
   从源头消掉 `"empty"/"none"/"effective"` 这类线格式污染。**手工往返实测**（见报告 §② 附）证明改名后 JSON 干净、逐字节稳定。
4. **闸的形态 = "区对级共享预算"，不是"删槽位"、也不是"逐格各乘一遍"**：
   - 预算按 **(源区 → 目的区 × 商品)** 建（`buildPortBudgets`），逐笔按**真正落账**的量扣（与运力/配额同一口径）——
     同一条形制见 D-027 的区级配额 `MatchContext.quotas`；
   - ★ **为什么不能逐格各乘一遍**：闸管的是<b>一条边界上的流量</b>（"一批货要过境，两边都要过"）。若 (买方格, 卖方格) 各自乘一遍，
     同一批货会被同一道闸按"剩余量"重复打折：A 有 100、B 两个买方格各要 100、两侧各 500‰ ⇒ 逐格 50+25=75 过闸，
     而正确是 `100 × 50% = 50`。**首版实现踩了这个坑**（本轮自查发现后改成共享预算），证据写在 §5；
   - 预算 = `⌊源区该商品卖方剩余 × E_源 × E_目的 ÷ 1e6⌋`，`cap=0`（任一侧全关）⇒ 该区对该商品本轮零候选；
   - **不删卖单本身**（§10.3）：卖方槽位原样留着，本区买家仍看得见它、区内成交一个字不改。
5. **`transit` 取"源区该商品的卖方剩余合计"（区内撮合之后）**：§11.2 的 `transit` 是"想入区的量"，
   而 §11.2/§11.5 把节流的对象写成**供给**（"流入量 = 普通供给"、"落点在候选配对处"）⇒ 用"想跨出去的货"当 transit，
   被拦下的那份就是"不进候选集的那份供给"。★ 另一种可能是"用目的区需求当 transit"（那会把 30 的需求在 50% 下压成 15，
   与"被拦下的量不进候选集"的供给口径不同）；本批取供给口径，理由与读法记在这里备裁。
6. **`demand > 0` 才给卖方落 PORT_THROTTLED**：没有"愿意且买得起"的需求时，余货的真因是没人要/限价，
   不该被口岸档掩盖（`sellerReason` 里 PORT_THROTTLED 优先于 capacityBlocked：政策闸比物流更外层，两档混起来会把"该改政策"读成"该加运力"）。
7. **区键口径 fail-closed**（`MarketSettlement.requirePortZoneKeysAligned`，`:4232`）：注入表非空却**一个区键都命中不了**本轮拓扑的区 id
   ⇒ 具名 ERROR `MARKET_PORT_ZONE_KEY_CONTRACT` + `IllegalStateException`。为什么"至少命中一个"就够：注入表非空 ⇒ 至少一个区
   管制力 > 0 ⇒ 那个区来自 `EconomyData.marketZones` ⇒ 必然被 `byPersistentZones` 建成同 id 的区（证据 §1-1/§1-2）
   ⇒ 命中 0 个只可能是两处键分叉或注入值来自另一个 revision。
8. **税：本批只落形状 + 校验 + 日志，不折算、不注入**（见 §4-1）。
9. **`PortThrottle.NO_GATE_MILLI = Long.MAX_VALUE`**：无口岸面时 `Math.min(..., MAX)` 逐值恒等 ⇒ 旧世界一个数都不动（I-P8），
   且省掉逐车道乘法；用 0/负数当"无闸"会与"全拦"的语义撞车。
10. **闸只读 `E`（开放度），不再乘 OR 布尔**：设计书 §4.3 的类注明写"规则判不许而 E = 1000（管不住）……两个读数都如实给出，
    **由调用方决定口径**"，而本批冻结口径给的就是"`可通过比例 = E_源 × E_目的 ÷ 1e6`"（任务书 §2）⇒ 取"E 决定能过多少"这一支：
   `s = 1000`（全禁）但 `e = 0`（没有口岸编制）⇒ `E = 1000` ⇒ **不拦**（"没人管也管不住"）。★ OR 读数照算、照进日志
   （`PORT_REGIME_COMPUTED.allowedByRule`），只是**不参与**节流乘法；§12.3 那句"它决定这一侧是否放行（布尔读数）"
   若将来要读成"OR 不许 ⇒ 该侧直接归零"，那是一次**口径变更**（会与 §4.3"管不住"的拐角冲突），需控制方裁定，本批不自行改。

## 4. 偏离记录（与约束设计书不一致处及原因）

1. **税没有进 `PortEnforcementInput`**（设计书 §13.2 说"两道税按方向"，本批只做形状）：
   多政府共管一个区时"哪一家的税说了算、进哪个国库"**在设计书里没有钉死**（§13.5 只钉了 T-1~T-5，T-3 只写"买方付、进两侧国库"，
   没说同一区多政府怎么分）。若现在折出一个"区级税率"，等于替 P-T1b 定了口径。⇒ 本批：四个字段进政策 + 进校验 + 进日志
   （`PORT_SURFACE_TAX` TRACE、`GOV_SET_PORT_POLICY_APPLIED.info` 的 `taxedClasses`），**一个数都不搬**（I-C2 逐值不变）。
   这是**待控制方/P-T1b 裁定**的开放点，不是实现困难。
2. **币种维的"估值减项"用** `max(入口管制力, 出口管制力)`（`PortEnforcementInput.currencyEnforcementPerMille(zone, currency)`）：
   设计书把"实际管制力"扩成两个方向，但**没有**说 `CurrencyValuation` 那条感知量该读哪一侧。取更严一侧的理由：
   ① 只有一侧设限时与改前**逐值相同**（I-P8 最紧的形态）；② 两侧都没设 ⇒ 0 ⇒ 逐值不变；③ 不会因"另一侧 = 1000"把效应整条丢掉。
   ★ 设计书 §14.6/§14.7 已判该减项可能被"挂单禁入/禁出 + 手续费"（P-T5）取代 ⇒ 不为它做新机制。
3. **`PortRegimeAggregation.split` / `SmugglingSplit` / `SEIZURE_BASELINE_MILLI` 保留未删**（本批明令不删，留给 P-T1c），
   但类注已标明"已判死、P-T1a 不许再引用"（`PortRegimeAggregation` 类注）。
4. **名称沿用 `PortEnforcementInput`（未改名 `*Input` → `*RegimeInput`）**：任务书写的就是这个类型"要能表达方向"，
   改名会平白扩大 diff；语义由类注钉死（表里是 `1000 − E`，两个方向各一个数）。
5. **`withPortEnforcement` 旧重载入口未动**（`EconomySettlement` 那条 `PortEnforcementInput.none()` 的便捷入口）：
   它不涉及本批语义，属既有形制。

## 5. 手工验证（**不是仓内测试**，测试文件一个都没写/没改）

| 装置（/tmp 下，不落仓） | 验的是什么 | 结果 |
|---|---|---|
| `PortPolicyCheck`（Jackson 往返） | 新 `GovPortPolicy` 走 `SimosObjectMapper`（含 `CommodityId`/`CurrencyId` 键反序列化器）能否往返、字节是否稳定 | `ROUNDTRIP_EQUAL=true`、两次编码**逐字节相同**；改名前的版本当场红（`Unrecognized field "effective"`）⇒ 证明改名不是洁癖 |
| `PayloadCheck`（`io.mosire.simos.gov.spi` 同包，调 `GovPayloads`） | ① 合法四元组载荷逐字段读对；② 9 条负向（拼错字段名/负限制/负税/非法 mode/none 带额/规则非对象/表非对象/空键/税内多余键）**必须具名拒**；③ `PortThrottle` 两侧相乘、单侧全关=0、两侧全开=transit、溢出 fail-closed、开放度越界拒 | `ALL CHECKS PASSED`（9/9 负向全部具名拒，拒绝消息含合法键/合法值） |
| 手工推演（纸面，见 §3-4） | "逐格各乘一遍 ⇒ 50+25=75 过闸" 的重复打折反例 | 据此把实现从"逐格上限"改成"区对共享预算"（**首版实现的缺陷，自查后修掉**） |

## 6. 没做 / 没验证（如实）

- **没跑** `test` / `verify`（本批纪律）；既有测试**没有**跑过 ⇒ 行为回归**未被回归网核过**（只有编译 + 上表手工验证）。
- **没有真实世界跑数**：`transit × E_源 × E_目的` 在真实多区世界的行为（含 `MARKET_PORT_*` 日志读数）**未观测**。
- **没有测**：`requirePortZoneKeysAligned` 的触发路径（构造两套键才触发）、并发/多线程路径（跨区撮合是协调器单线程，未额外验证）。
- **没验**：`ZonePortRegime.direction` 的 enum 名字面量（`ENTRY`/`EXIT`）在只读读数面上的外部消费者——当前仓内除 bridge 日志外**无消费者**。
- **待控制方裁定（不是实现困难）**：① 税的多政府归属（§4-1）；② 币种估值减项取哪一侧（§4-2）；③ OR 布尔是否参与节流（§3-10）。
