# P-T1e 币种挂单过滤 —— 实现架构账本（写码 Agent）

> **约束设计书**：`docs/superpowers/specs/2026-10-09-port-policy-and-zone-efficiency-design.md` §14（§14.3 五条 / §14.5 / §14.6）、§12（两侧都要过）、§10–§11（市场选择 / 无"走私"档）、§15（口岸层跨区、市场层区内）、§16.4（撤异币估值减项）；
> `docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §2.2 / §2.6 / §3（I-C2/I-C5/I-C7/I-C10）/ §5（T1/N1/N6/N7）/ §6.2 V-1。
> **前批账本（接缝在哪）**：`.superpowers/sdd/2026-10-10-3c-order-currency/impl-ledger.md` §6/§7（接受集恒真的唯一拼写点）、§5.6（`receiveWith` 语义待 P-T1e 裁定）；`.superpowers/sdd/2026-10-10-p-t5b-pay-currency/impl-ledger.md` §3 判断 9（同上）；`.superpowers/sdd/2026-10-10-p-t1a-port-throttle/impl-ledger.md`（`PortRule`/`PortDirection` 一族风格、区键口径）。
> **责任区**：P-T1e = 把"接受集恒真"的接缝换成**按 (币种, 挂单类型, 方向) 判定**的禁入/禁出 + 政策形状/载荷/守卫/折算/注入的同步。
> **本批不做**：政府采购优先级（P-T1d）；币种手续费的**真收钱**（只落形状 + 读数 + 具名 INFO）；卖家赊购（Q-26）；挂单簿持久状态；FX 轮的闸（见 §4-1）。
> **基线**：工作树 `d518913b`（M-D 之后、本批改动前），未提交任何东西。

---

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | 证据（本批改动前） | 结论 | 影响 |
|---|---|---|---|
| C1 | `MarketSettlement:5264` `acceptsCurrency(sell, buy.currency)` 恒真（3c 留的接缝）；调用点只有 `settlementUnitPrice:5131/5908/2839`（cash / cash / money-credit） | 三条腿共用一个拼写点；接缝只需加参数（`ctx`/`buy`/`kind`） | 判定只改一处，归因/日志/不落账自动生效（3c §7 的承诺成立） |
| C2 | `settlementUnitPrice:5255` 借实物腿（`goods-credit`）**在币种维之前早退** | 该腿不经货币 ⇒ 挂单闸对它无对象 | 不新增门槛；`orderKindOfLeg` 只需认 cash / money-credit 两条 |
| C3 | `creditRound:2710` + `moneyCreditForBuy:2786` `pools.bestCashSeller(regionId, …)`；类注 D-027"单区：只从买方所在区取头寸" | 货币信用腿**只在区内** ⇒ 跨区借贷不存在 | LENDING 类型的闸在本批**不可达**（形状已通、执行面待接，见 §6-1） |
| C4 | `matchAcrossRegions:4310` 是**唯一**跨区商品路径（协调器单线程）；`matchWithinRegions` 走 worker 副本 | 跨区候选的门只在这一条路上；worker 副本上闸恒放行（同区） | 轮级计数只在协调器累加（与 `fills`/`taxItems` 同一条纪律） |
| C5 | `BuySlot.regionId` / `SellSlot.regionId = region.node().nodeId()`（建槽位时）；P-T1a 已证与注入表区键**同一套**（`MarketTopologyBook:236-241`） | 闸的两侧区 id 不必新查表、无第二权威 | `currencyGateBlock` 直接用两个槽位的 `regionId` |
| C6 | `FxSettlement.match:118` 的 `Order.regionId`（`:1250-1284`）= FX 单自己的区；FX 是**另一个结算器** | 币↔币（EXCHANGE）的闸不在本批落点内 | EXCHANGE 类型形状已通、执行面未接（§4-1） |
| C7 | `CurrencyValuation:297` 只读 `portEnforcement.currencyEnforcementPerMille(zoneId, currency)`（标量） | 币种表加"类型"层后，标量口径必须保留 | 保留 2 参标量（= 所有类型 × 两侧取最严）⇒ 改前值逐值不变 |
| C8 | `GovCodec:56-83` 严格读侧 + 值类型零注解；只有三路 `KeyDeserializer`（`UnitId`/`CommodityId`/`CurrencyId`），写侧靠"默认键序列化器调 `toString()`" | 枚举当**键**时会走 `name()`（`LENDING`），与载荷/读数的规范字面量（`lending`）不一致 | 新枚举的键**读写两侧都注册**（GovCodec）⇒ 线格式与载荷同字面（手工往返实测见 §5） |
| C9 | 全仓 `src/test` 里 `GovPortPolicy`/`PortRule`/`PortEnforcementInput`/`PortRegimeBridge`/`currencyRules` **零引用**；只有 `gov.SetPortPolicy` 的**类型字符串**（`McpCoverageTest:181/344/489`、`SimosToolsTest:556`）与合成注册面（`CatalogVisibilityTest:51`） | 改这些形状不会让测试**编译**失败 | §7-3 的失效清单只有"语义/文案"面 |
| C10 | `simos-economy/src/test/.../MarketRegulationTest.java:275` 仍引用已删的 `REGULATION_QUOTA` | economy 测试树**在本批之前就已不编译**（3c 账本 §5.6 已记） | 本批同样无法用"既有测试仍绿"当回归网（§6-4） |

---

## 2. 实现架构（代码怎么把这个世界长出来）

```
写侧（法律）  gov.SetPortPolicy ──GovPayloads.portPolicy──▶ GovPortPolicy{
                  commodityRules: 商品 → PortRule
                  currencyRules:  币种 → 挂单类型(MarketOrderKind) → PortRule }     ← 本批加的一层
              PortRule = 入口限制‰ / 出口限制‰ / 入口税 / 出口税（从量从价各自指定）
              GovPortPolicyGuard：命令**落状态之前**拦未知类键（世界词表）+ 未知挂单类型（受控词表）

折 算（组合根）PortRegimeBridge.compute：逐区 × 逐（币种 × 类型）× 逐方向
                  surfaces(方向) = 逐归属政府的 PortContactSurface{contactKey, w, s(方向,类型), e}
                  └─ PortRegimeAggregation.aggregate(zone, "币种#类型", direction, surfaces)
                         ⇒ ZonePortRegime{…, E, 逐段读数}（读数键带类型，日志可分辨）
                  ⇒ PortEnforcementInput{
                        currencyByZone:  区 → 币种 → CurrencyEnforcement(类型 → Directional(入口‰, 出口‰)),
                        commodityByZone: 区 → 商品 → Directional }        （存的是 1000 − E）

执 行（经济）  MarketSettlement.settlementUnitPrice（三条腿共用）：
                  if (!acceptsCurrency(ctx, buy, sell, buy.currency, orderKindOfLeg(leg))) ⇒ 不落账
                  acceptsCurrency = currencyGateBlock(...) == null            ← 判据唯一拼写点
                  currencyGateBlock：currencyRegimeActive? → 同区放行 → 源区 EXIT 开放度>0 → 目的区 ENTRY 开放度>0
                  被拒 ⇒ refuseUnacceptedCurrency：买卖两侧 CURRENCY_NOT_ACCEPTED + DEBUG"哪一侧挡的" + 轮级计数
日志（§一.9）  规则设置 INFO（GOV_SET_PORT_POLICY_APPLIED 带 currencyKindRules/currencyFeeRules）
                  折算 INFO（GOV_PORT_CURRENCY_FEE_SHAPED_ONLY：手续费只落形状不收款）/ DEBUG（PORT_REGIME_COMPUTED 带 orderKind）
                  判据 DEBUG（MARKET_CURRENCY_NOT_ACCEPTED 带 orderKind/源区/目的区/被挡侧）/ 轮级 INFO（MARKET_CURRENCY_GATE_BLOCKED）
```

**改动文件（14 个，全在允许写的 `src/main` 内）**

| 文件 | 落点 |
|---|---|
| `simos-economy-api/.../market/MarketOrderKind.java`（**新**） | 挂单类型词表 `exchange/commodity/lending` + `value/all/parse/toString` + 红线与"执行面"注 |
| `simos-economy-api/.../market/{BuyOrder,SellOrder,MarketUnfilledReason}.java` | 文档同步：接受集 = 口岸规则（不是 `receiveWith`）；`CURRENCY_NOT_ACCEPTED` 的键与红线 |
| `simos-gov/.../GovPortPolicy.java` | 币种表加类型层；`ruleOfCurrency(币,型)` / `restrictionOf(方向,币,型)` / `currencyKindsOf` / `currencyKindCount` / `currencyFeeKindCount`；两层冻结与防御性副本 |
| `simos-gov/.../spi/GovPayloads.java` | `currencyRules` 两层解析（未知类型/值非对象/内层空对象口径）具名拒 |
| `simos-gov/.../spi/SetPortPolicyHandler.java` | INFO 计数加 `currencyKindRules`/`currencyFeeRules`；类注与载荷样例 |
| `simos-gov/.../codec/GovCodec.java` | `MarketOrderKind` 的键读写器（线格式 = 规范字面量） |
| `simos-economy/.../time/PortEnforcementInput.java` | 币种表按类型承载（新 `CurrencyEnforcement`）；`currencyOpennessPerMille(区,币,型,方向)`；`currencyRegimeActive()`；2 参标量保留 |
| `simos-economy/.../time/MarketSettlement.java` | `acceptsCurrency` 填实 + `currencyGateBlock` + `orderKindOfLeg` + 具名拒日志 + 轮级 INFO + 计数；文档去"恒真" |
| `simos-app/.../time/PortRegimeBridge.java` | 逐（币 × 类型 × 方向）折算；`surfaces` 收 `RuleLookup`；读数键 `币种#类型`；手续费具名 INFO；`PortRegimeDay.currencyFeeClasses` |
| `simos-app/.../time/PopulationEconomyTimeParticipant.java` | `PORT_REGIME_INJECTED` 加 `currencyFeeClasses` |
| `simos-app/.../world/GovPortPolicyGuard.java` | 多拦一层"未登记的挂单类型"（写状态之前 fail-closed） |
| `simos-app/.../tools/read/CatalogTool.java` | `gov.SetPortPolicy` 的 `PAYLOAD_HINTS` 与载荷同步 |

---

## 3. 关键判断（为什么这样拆；调查中推翻/否掉过什么）

1. **闸作用在"钱的流向"上：目的区 = 卖方所在区（ENTRY），源区 = 买方所在区（EXIT）。**
   钱从买方流到卖方 ⇒ 源区 = 买方区、目的区 = 卖方区；与商品维（货从卖方 → 买方）方向相反，这是刻意的：闸管的是**这一类挂单/这种钱**的流向。两侧都要过（§12），任一侧"全禁"即不给过（用户"有一方不给过就不过"）。
2. **★ 闸只作用在跨区流动上（同区恒放行）——本批最关键的一条口径判断。**
   依据三条：① §10.3 明文"过滤要作用在**跨区候选**上：本区自产的货没跨边界，不受口岸影响（否则等于'区内禁售'，那是市场管制、不是口岸）"；② §15 的两层分工（"口岸层跨区、市场层区内"）；③ 用户模型"本国货币对于本国居民来说只能借贷"要能成立 ⇒ 本区的口岸规则不能掐死本区内的本币借贷。
   ★ **被否掉的另一条路线**：对同区交易也套用"该区 ENTRY + EXIT 两道闸"（字面照读"两侧都要过"）。它会带来一个与用户原话直接冲突的后果：区政府设一条"禁止本币借贷出境"（EXIT=1000）就会把**本区居民之间的本币借贷**一起禁掉 —— 而那正是用户说"只能借贷"的那件事。⇒ 取"边界闸"读法，并把理由写进 `acceptsCurrency` 的类注（留给控制方/测试 Agent 复核）。
3. **"不给过"的判据 = 该侧开放度 = 0（全禁），不是"设了限制就拦"。**
   开放度 = 1000 − 管制力（管制力 = 按暴露边加权平均的 `⌊s×e÷1000⌋`，P-T1a 的唯一算式）⇒ 只有 `s = 1000` 且该侧口岸效率 > 0 才是"一分都不放"。★ 与既有口径一致：`s = 1000` 但 `e = 0`（没有口岸编制）⇒ 管不住 ⇒ 不拦（P-T1a 已裁）。★ 部分强度（`0 < E < 1000`）**不构成禁令** —— 挂单闸是**禁入/禁出**（布尔，用户"不给过就不过"），不是节流；强度仍进读数（`PORT_REGIME_COMPUTED`）、进日志、并继续供 `CurrencyValuation` 的标量减项 ⇒ **不静默**。这是本批唯一"没有完全咬合"的映射（需要控制方一句裁定才能改成"部分强度也算拦"或"按比例配额"）。
4. **`SellSlot.receiveCurrency` 是声明/读数，不是过滤器（回应 P-T5b §3 判断 9 的待裁定）。**
   P-T5b 之后它带的是"卖方自己的最强持有币"；若把它当接受集，等于给**未设政策**的世界加新限制（违反 I-C2），且"卖家只收自己最强的那种钱"这条语义从未被裁定。⇒ 缺口 = 政策规则；声明照旧只进日志。理由写进 `SellSlot` 与 `acceptsCurrency` 的类注。
5. **挂单类型三类（`exchange` / `commodity` / `lending`），从"腿"来。**
   用户把三件事分开说（借贷 / "一块钱买一块钱" / "用货买钱"），所以"货↔钱"不能并进"兑换"，否则规则表达不出用户的分辨。`leg` → 类型的映射只有一处（`orderKindOfLeg`），未登记的腿**当场抛**（腿只有三条，多一条就是调用点漂开）。
6. **政策/注入的形状：币种表加一层类型键，商品表不动。**
   商品只有"货↔钱"一条类型维 ⇒ 加类型层是空转；币种必须加（否则用户例"禁止本市场区货币被外国借贷"表达不出来，§14.3-4）。⇒ `Map<CurrencyId, Map<MarketOrderKind, PortRule>>`（保序两层 + `Collections.unmodifiableMap`，**不用** `Map.copyOf`/`Set.copyOf`）。
7. **"挂单类型"的载荷/线格式字面量一律小写下划线，枚举键读写器自己注册。**
   Jackson 对**枚举键**默认写 `name()`（`LENDING`），与 `PortTaxMode` 等的规范字面量口径（小写）不一致 ⇒ 在 `GovCodec` 注册键读写器（写 `value()`、读 `parse`），并在枚举上覆盖 `toString()`。★ 顺带实测到一处**既有**不一致：`PortTaxMode` 作为**值**落线格式时是 `NONE`/`AD_VALOREM_PER_MILLE`（大写 name），而载荷里是小写（`GovPayloads` 手工解析）——往返成立、不影响正确性，属 P-T1a 既有形状，本批不动（§4-3）。
8. **缺省语义中性（I-C2）落在三处、互相独立**：① `portEnforcement.currencyRegimeActive() == false`（一条币种规则都没设/未注入）⇒ 闸恒放行；② 表非空但缺（区/币/类型）键 ⇒ 管制力 0 ⇒ 开放度 1000 ⇒ 放行；③ 同区 ⇒ 恒放行。三处都不依赖"政策是否设过其他类"。
9. **I7 确定性**：新增遍历只有两处，都是内容的纯函数 —— `RuleLookup` 的逐接触面循环（序 = 已排序的 `ownerKeys`）与 `sortedKinds`（序 = `MarketOrderKind` 声明序，不用 `Set` 迭代序）；政策表的遍历序 = 政策的规范插入序（与 P-T1a 的商品维同一形制）；新增的 `Map`/`Set` 一律 `LinkedHashMap`/`LinkedHashSet` + 冻结。**没有** `Map.copyOf`/`Set.copyOf`、没有随机数、没有时钟。
10. **守恒不动**：本批**不碰**钱货腿、不搬钱（手续费只落形状）⇒ 既有守恒式与 `applyTransfer` 唯一写口一字未改。
11. **日志（§一.9）**：INFO = 规则设置（handler）/ 折算产物的具名计数（`PORT_REGIME_INJECTED`）/ 手续费只落形状（`GOV_PORT_CURRENCY_FEE_SHAPED_ONLY`）/ 轮级"被挡下多少"（`MARKET_CURRENCY_GATE_BLOCKED`）；DEBUG = 逐条判据（`MARKET_CURRENCY_NOT_ACCEPTED` 带被挡侧与类型、`PORT_REGIME_COMPUTED` 带 `orderKind`）；TRACE = 逐接触面（`PORT_SURFACE_READING`/`PORT_SURFACE_TAX` 带 `orderKind`）。

---

## 4. 偏离记录（与约束设计书/派单不一致处及原因，主动记）

1. **FX 轮（币↔币兑换）没有装闸** —— 派单把落点冻结在 `MarketSettlement.acceptsCurrency`（"只改它的方法体"），而 FX 是另一个结算器（`FxSettlement.match`，`Order.regionId`）。⇒ `EXCHANGE` 类型**形状已通、执行面未接**。★ 而且 FX 的闸有一个**必须先裁定的口径问题**：一张 FX 单里两种币**对流**（base 出去、quote 进来），方向维该读哪一种币的进出？本批不自行裁定（写进 §6-1，请控制方裁）。
2. **`LENDING` 类型的闸在本批不可达**（信用撮合是区内的，C3）⇒ "禁止本市场区货币被外国借贷"这条规则**表达得出来、今天执行不到**。同上，跨区借贷是后续批（或需先裁"信用要不要跨区"）。
3. **§16.4「撤掉异币估值减项」本批不做**：派单冻结的落点是挂单闸，撤减项是**另一处数值行为变更**（`CurrencyValuation` 的入参与算式）。⇒ 本批保留该减项，并把标量口径改成"所有类型 × 两侧取最严"（只有一种类型设规则时与改前**逐值相同**）。请控制方在下一批点名处置。
4. **读数键 `币种#类型` 是拼出来的字符串**（`ZonePortRegime.classKey` 是读数键、不是稳定 id）：不改 `ZonePortRegime`（经济侧契约类型），也不新造类型；拼接只有一处（`currencyReadingKey`）。
5. **`GovPortPolicyGuard` 多拦一层"挂单类型"**，与 `GovPayloads` 的解析**判同一个词表**（`MarketOrderKind.all()`）——原类注写"两处不重复实现"，本轮按"写状态之前 fail-closed"改成双保险并在类注里如实写明（不是两套真值）。
6. **`PortEnforcementInput.zoneIds()`/`classCount()` 的计数粒度变了**（币种按（币 × 类型）计）：它们只进日志/读数；`zoneIds()` 是跨切片键口径核对用（语义不变）。
7. **未做旧形状兼容**（`GovPortPolicy` 的旧"币种 → 四元组"载荷/旧档）：照 §一.11 直接重建（用户"旧设计和数据类型直接重建不用留"）。

---

## 5. 手工验证（**不是仓内测试**；测试文件一个都没写/没改，全部在 `/tmp/pt1e` 下，不落仓）

| 装置 | 验的是什么 | 结果 |
|---|---|---|
| `PortPolicyRoundTripCheck`（走**真** `GovCodec`） | 新形状（币种 → 类型 → 四元组）能否编码/解码、字节是否稳定、缺键是否读作不限制 | `ROUNDTRIP_BYTES_EQUAL=true`、`POLICY_EQUAL=true`；线格式里是 `"lending"`/`"commodity"`（小写字面量）；缺类型/缺币种 ⇒ `unrestricted` |
| `PortPolicyPayloadCheck`（`io.mosire.simos.gov.spi` 同包，调 `GovPayloads`） | ① 正向逐字段读对；② 内层空对象 = 无规则；③ **9 条负向必须具名拒**（未登记类型 / 拼错类型 / 币种值非对象 / 类型值非对象 / 规则多键 / 负限制 / `none` 带额 / 键空白 / 表非对象） | `ALL CHECKS PASSED`（19/19） |
| `InjectInputCheck` | 注入表语义：两侧开放度、缺键 = 1000、`none()` 全放行、标量最严、保序、防御性副本与不可变、`MarketOrderKind` 声明序与 `parse` 不归一 | `ALL CHECKS PASSED`（21/21，首轮把"返回表不可写"写成 `clear()` 当场红 ⇒ 反证了 freeze 真的在） |
| 静态审计（`grep`） | `new GovPortPolicy(` 只剩 2 处（empty + 解析器）；`currencyRules()` 消费者只剩 bridge/handler；`acceptsCurrency` 调用点仍只有 3 条腿；test 树零形状引用 | 一致 |

★ **没验的**：闸在**真撮合**里的端到端行为（需要两区世界 + 政策 + 口岸效率 > 0），见 §6-2。

---

## 6. 未完成 / 未验证（如实记）

1. **`EXCHANGE` 未接执行面**（FX 轮，§4-1）、**`LENDING` 不可达**（区内信用，§4-2）——两条都是**可表达、暂不可执行**；需要控制方裁定（FX 的方向语义 / 信用是否跨区）。
2. **没有真实世界跑数**：闸在跨区 lane 上的实际拦截（`MARKET_CURRENCY_GATE_BLOCKED` 的读数）**未观测**；且闸要咬人还要求该侧口岸效率 > 0（`GovEfficiency` 的口岸维），真实世界里是否非 0 **未核**。
3. **没有测**：`requirePortZoneKeysAligned` 在"只有币种表非空"时的触发路径（构造两套键才触发）。
4. **没有跑** `test`/`verify`/`package`（派单纪律：只到编译过）；economy 测试树**本批之前就不编译**（C10）⇒ 开发期**没有行为回归网**（AGENTS §三.0 已接受的对冲）。
5. **未做**：手续费真收钱；部分强度→配额/节流的映射（§3-3）；`MarketNode.receiveWith`（区级 `InstrumentId` 占位）退役（3c §5.3 留的，本批仍未动）。

---

## 7. 给测试 Agent 的输入

### 7.1 会改变数值行为的清单（只有一处，且只在"设了币种规则 + 跨区 + 该侧全禁"时）

| 位置 | 改前 | 改后 | 何时不同 |
|---|---|---|---|
| `settlementUnitPrice` 的候选裁决 | `acceptsCurrency` 恒真 | 跨区且有币种规则时按两侧开放度判 | **只有**：① 注入表含币种条目 ② 两槽位在**不同**区 ③ 该侧（源区 EXIT / 目的区 ENTRY）对（币, 类型）开放度 = 0。三者缺一 ⇒ 与改前逐值相同 |
| 其余（订单币、预算、冻结、单价折算、钱货腿、三层税、报告） | — | — | **一个字未改** |

**缺省中性论证（I-C2 / N1）**：未注入 / 表空 ⇒ `currencyRegimeActive() == false` ⇒ 闸的第一行就返回"放行"（不进任何表查询）；表非空但缺键 ⇒ 管制力 0 ⇒ 开放度 1000 ⇒ 放行；同区 ⇒ 放行。⇒ **一条币种规则都没设的世界逐值不变**（含单区世界：`matchAcrossRegions` 在 `!topology.regional()` 时整段不跑）。

### 7.2 受影响硬编码字面量（测试夹具要跟着改）

- 载荷：`currencyRules` 的值从"规则对象"变成"`{exchange|commodity|lending} → 规则对象}`"（`McpCoverageTest:490` 用的是**更旧**的 `currencyRestrictionPerMille` 空对象 ⇒ 顶层未知字段被忽略 ⇒ 仍走"未知 GOV 单位"具名拒，**用例语义不变**）。
- 新字面量：`exchange` / `commodity` / `lending`；日志事件 `GOV_PORT_CURRENCY_FEE_SHAPED_ONLY`、`MARKET_CURRENCY_GATE_BLOCKED`；读数键 `币种#类型`；拒因 `source-zone-exit-closed` / `destination-zone-entry-closed`。
- `PortRegimeDay` 多一个组件 `currencyFeeClasses`（构造点只在 `PortRegimeBridge` 内）。

### 7.3 会让既有测试失效的清单

1. **`simos-economy/src/test/.../MarketRegulationTest.java:275`** 引用已删的 `REGULATION_QUOTA` ⇒ **整个 economy 测试树不编译**（**本批之前就如此**，P-T1c 删档留下；3c 账本 §5.6 已记）。⇒ 本批形状变更在测试面**无法被编译期发现**。
2. `McpCoverageTest:490` / `SimosToolsTest:556` / `CatalogVisibilityTest:51`：只引用**类型字符串**或合成注册面 ⇒ 不受影响（前者载荷虽旧，仍按"缺前置"具名拒）。
3. **可能过时但不会红**：任何断言 `GovPortPolicy.currencyRules()` 是"币种 → PortRule"的测试 —— 全仓 **0 处**（C9 实测）。

### 7.4 建议的验收判据（按约束设计书，不按代码反推）

- **T1'**：两侧都不设 ⇒ 跨区候选/结算**逐值不变**；只设一侧且全禁 ⇒ 该 lane 零候选 + `CURRENCY_NOT_ACCEPTED`；
- **T2'**：同一份规则，`e = 0`（无口岸编制）⇒ **不拦**（"没人管也管不住"）；`e > 0` ⇒ 拦；
- **T3'**：类型维可分 —— 只禁 `lending` 不影响 `commodity` 的候选（在可达路径上则用注入表直接构造）；
- **T4'**：同区交易**不受**任何币种规则影响（红线/§10.3 的负向用例）；
- **T5'**：红线负向 —— **一个区的规则管不到与它无关的两个区之间的交易**（本币境外使用不被禁）、且规则**不限制持有**；
- **T6'**：**N1 负向** —— 未登记的挂单类型 ⇒ 具名拒、零 revision（写前守卫 + 载荷解析两道都可测）；
- **T7'**：字节级往返（`GovCodec`）与缺键 = 不限制；**I7**：同一 revision 两跑逐值相同。
