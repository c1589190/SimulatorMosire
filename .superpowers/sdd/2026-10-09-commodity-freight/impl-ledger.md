# F 批实现账本：全局商品运费系数表 + GM 命令（2026-10-09）

> 责任区 F（派单书任务 task-23）。唯一权威文档：
> `docs/superpowers/specs/2026-10-09-commodity-freight-and-merchant-ladder-design.md`（§1.3/§1.4 用户原话、§4.1 甲方案、
> §5 I-F1/I-F2/I-F3、§6 T1/T2/T3 + N1/N2/N4、§7 批次）。
> 基态提交：`a558422f`（= HEAD）。★ 本账本只记决策相关事实，不抄工具输出。

---

## 0. 一句话结论

甲方案（商品系数**乘在整条运费费率上**）已落地：新状态组件 `EconomyData.commodityFreightPerMille`（第 38 个组件）、
唯一算式 `TransportTariff.perMille` 第 6 个入参、调用链一路传商品、GM 命令 `economy.SetCommodityFreight` 四处接线齐。
**未设表 ⇒ 与 `a558422f` 逐值相同**（three-powers 5 天 / small-world 5 天：全日志 diff = 0，SHA-256 摘要相同）。

---

## 1. 关键调查结论（`file:line`，2026-10-09 于工作树核实）

| # | 证据 | 结论 / 影响 |
|---|---|---|
| S1 | `TransportTariff.perMille` 原本 5 参（`TransportTariff.java:88` 起） | 全仓**只有一个**真实调用点：`MarketTopology.freightPerMilleBetween` 的 4 参入口（grep `\.perMille(` = 1 命中）。⇒ 第 6 个入参只改这一条链，零散点不存在 |
| S2 | `MarketTopology.freightPerMilleBetween` 两个入口（2 参 / 4 参） | 2 参含"同格/缺图 ⇒ 0"的守卫；4 参是 P6 两个商人调整量的显式入口。两者都被既有夹具/读口用到 ⇒ **必须保留**（本批只**加**重载，不改旧入口语义） |
| S3 | ★ **设计书 §3 F2/F3 已过期**：`MarketSettlement.commodityFreightBaseMilli`（`MarketSettlement.java:4772`）是 A 批（2026-10-09）加的**逐商品基础运费**（grain/fiber 1、cloth 2、tool 3、其余 1），`freightUnitMilli`（`:4821`）= `max(1, ⌈基础费 × (1000+路线费率) × (1000+承运成本) ÷ 1e6⌉)` | ⇒ 运费**本来就已有商品维**；本批的系数是**第二条**商品维（乘在路线费率上）。这决定了 T2 的口径（见 §4 偏离 D1），也说明"缺键 ⇒ 1000 ⇒ 逐值不变"必须严格成立，否则会与既有基础费叠加出意外 |
| S4 | `EconomyData` 是 35 参 record（`EconomyData.java:265-325`），4 个旧组件面便捷构造器 + 35 个 `with*` | 加组件的**真实成本**：4 个便捷构造器的 `this(...)` 尾参 + 35 个 `with*` 的规范构造实参 + 2 处 main 侧全参构造（`EconomySeedHandler:155`、`EconomyClearRegionHandler:394`）。★ 便捷构造器是**链式**委派（28→29→31→规范 / 33→规范），批量加一行会破坏委派目标 ⇒ 本批按"每个构造器各自该收几个实参"逐一核对（见 §6 踩坑） |
| S5 | `EconomyChangeSet` 键一律是 String（`FieldDelta` 的 `Map<String,T>`） | ⇒ 用 `FieldDelta<Long>`（键 = `CommodityId.toString()`）而不是 `FieldDelta<Map<CommodityId,Long>>`：**线格式不引入任何领域键类型** ⇒ 重放第四台 mapper（`Timeline.readChangeSet`，`Timeline.java:115/385`）不需要 CommodityId 键反序列化器 |
| S6 | 快照序列化走 `EconomyCodec`，其 `keyModule()` 早已注册 `CommodityId` 键反序列化器（`EconomyCodec.java:221`） | ⇒ 快照里的 `Map<CommodityId,Long>` 读写两端都通；新组件在快照/变更集两侧的键处理**都不需要新注册** |
| S7 | `EconomyStateBuilder.build`（`:341-402`）把每个组件显式带过；注释里"漏了它 = 任意一次 advance 静默抹掉"反复出现 | ⇒ 新组件必须在此显式带过 `base.commodityFreightPerMille()`（已做，`:402`）——**这条是"推进后仍在"的唯一守卫** |
| S8 | `MarketTopologyBook.from(state)` 是**唯一**拓扑装配点（三处 `TransportTariff.probeDefaults()` 都在它内部）；调用者 `PopulationEconomyTimeParticipant:570` → `EconomyDayStepper` → `EconomySettlement` → `MarketSettlement` | ⇒ "系数从状态读"的落点 = 在 `from(...)` 的返回值上 `.withCommodityFreight(economy.commodityFreightPerMille())`；**每次结算/读数都重建** ⇒ GM 改完下一次 advance 生效 |
| S9 | `CatalogTool.PAYLOAD_HINTS` 有**构造期覆盖断言**（`CatalogTool.java:864-869`：已注册类型缺提示 ⇒ 抛） | ⇒ 漏登记 = `Shell.start` 当场抛（A1 的血教训）。本批已登记（`:402`） |
| S10 | GM-only = `GmOnlyCommand` 标记接口，组合根据此拆 `catalogCommandTypes`（全量）与 `directiveCommandTypes`（剔除 GM-only）（`Shell.java:812-830`） | ⇒ 权限单调性的**唯一**实现形态是"标标记"，不写第二份清单；窄工具另需只加在 `addGmWrites`（GM 桶） |
| S11 | `MarketReportFeed.last(mapId, tick)` 只保留**最后一个** tick 的报告（进程内、不落盘） | ⇒ 探针必须**逐日**取样，否则只读到最后一天（本批踩到：见 §6 坑 3） |

---

## 2. 实现架构（本批实际长出来的形状）

```
状态层   EconomyData 第 38 组件 commodityFreightPerMille : Map<CommodityId, Long>   （EconomyData.java:325）
         缺键 ⇒ 1000（唯一读口 commodityFreightPerMilleOf，:356；常量在 TransportTariff.DEFAULT_COMMODITY_FREIGHT_PER_MILLE:68）
         值域 > 0（compact 构造器，具名前缀 COMMODITY_FREIGHT_CONTRACT_PREFIX:347）
         旧档缺键 ⇒ 空表（compact 构造器 null 归一）⇒ 每个商品 1000 ⇒ 逐值等于改前
变更集   FieldDelta<Long> commodityFreightPerMille（键 = CommodityId 裸值；EconomyChangeSet.java:168）
         between（:340 区）/ apply（rebuild + CommodityId::parse）/ isEmpty 三处
算式     TransportTariff.perMille(distance, radial, road, cityDiscount, ruralPenalty, commodityFreightPerMille)（:129）
         route = max(0, base + perHex×d + 20×radial − 50×road − cityDiscount + ruralPenalty)
         rate  = route × commodityFreightPerMille ÷ 1000     ★ 截断（floor）
         旧 5 参入口保留（:100）= 第 6 个入参恒 1000（既有用例/对拍逐值不变）
拓扑     MarketTopology 新增字段 commodityFreightPerMille（:118）+ withCommodityFreight（:813）
         + freightPerMilleBetween(from,to,CommodityId)（:716）、(from,to,long)（:737）、(from,to,cd,rp,coef)（:781）
         + commodityFreightPerMilleOf（:757，缺键 1000）、maxCommodityFreightPerMille（:769）
组装     MarketTopologyBook.from(state)（:145）= build(state,…).withCommodityFreight(economy.commodityFreightPerMille())
命令     economy.SetCommodityFreight（spi/EconomySetCommodityFreightHandler.java:60，GmOnlyCommand）
         载荷 {commodityId, perMille, reason?}；成功 INFO / 具名拒 INFO + DEBUG（§一.9）
接线     ① Shell:666 注册 ② CatalogTool:402 载荷提示 ③ SimosToolSource:787 GM 桶窄工具
         simos.economy.setCommodityFreight（write/EconomySetCommodityFreightTool.java）
         ④ GmOnlyCommand 标记 ⇒ 令白名单 / RegisterEffect / 决策人目录三条路径自动排除
日志     新来源 EconomyLogSource.ECONOMY_COMMODITY_FREIGHT（"economy-commodity-freight"）
         + 契约违约 ERROR（EconomyCodec 载入边界，照 Z1 形制）
```

**数据流**：`Command(economy.SetCommodityFreight)` → handler 读 `EconomyData` → 三条具名拒 → `withCommodityFreightPerMille`
→ `EconomyChangeSet.between(base,target)` → Core 落 revision →（下一次 advance）`MarketTopologyBook.from(state)` 读到新表
→ `MarketTopology.freightPerMilleBetween(..., 商品)` → `TransportTariff.perMille` 第 6 入参 → `MarketSettlement.freightUnitMilli`
→ 成交运费 / 承运收费 / 商号收入估算。

---

## 3. 调用链逐条处置清单（"哪些调用点加了商品维"）

| # | 调用点（原 `:line`） | 处置 | 依据 |
|---|---|---|---|
| C1 | `MarketSettlement.java:3643`（`matchRoute`：跨区/区内路线构建） | **加商品维**：先取 `commodityFreightPerMilleOf(commodity)`，再走 5 参入口（现 `:3644-3652`） | 这是"整条运费"的主入口 |
| C2 | `MarketSettlement.java:3844`（`intraRegionFreightRoute`：区内跨格） | **加商品维**：改走 `(sell.hex, buy.hex, commodity)`（现 `:3853`） | 与跨区同源口径；"区内不用付系数"没有道理 |
| C3 | `MarketSettlement.java:4632`（`carrierChargeSplit` 的逐承运商有效费率） | **不改签名**：它读 `route.freightRatePerMille` / `route.commodity`（已经是商品维之后的数） | 无需第二处解析 |
| C4 | `ExpectedProfitBook.java:1269`（`maxAdjacentLaneRate`：商号收入估算的邻区最大费率） | **改**：取 `topology.maxCommodityFreightPerMille()` 作为**不低估上界**（该处没有具体商品：外部需求是逐商品汇总的） | 空表 ⇒ 恒 1000 ⇒ 逐值不变 |
| C5 | `MerchantSettlement.java:229` | **注释**（javadoc `@param nominalRatePerMille`），无算术 ⇒ 不改 | grep 实证 |
| C6 | `MarketTopologyBook.java:338` | **注释**（说明费率来源），无算术 ⇒ 不改 | grep 实证 |
| C7 | 其余含 `freightPerMilleBetween` 的注释（`MarketSettlement.java:170/3836/4818/6266`） | 只更新/保留说明性文字 | 无算术 |
| C8 | `MarketTopology` 的 2 参 / 4 参旧入口 | **保留不改语义**（等价于系数 1000）；三者都收敛到 5 参实现 | 旧调用方（读口/夹具）逐值不变 |
| C9 | `TransportTariff` 旧 5 参入口 | **保留**（= 系数 1000），委托给 6 参 | 既有 `TransportTariffP4Test` 10 处调用不动 |
| C10 | `EconomyStateBuilder.build`（advance 的唯一构造点） | **显式带过** `base.commodityFreightPerMille()`（`:402`） | 漏 = 每次 advance 抹表（S7） |
| C11 | `EconomySeedHandler:155`、`EconomyClearRegionHandler:394`（main 侧全参构造） | 分别带过 `base.` / `staged.` 的表 | 漏 = 补种/清区域静默抹表 |
| C12 | `EconomyCodec`（快照 + 变更集两条物化路径） | 键反序列化**复用既有注册**（`CommodityId`，`:221`）；新增载入边界契约 ERROR（值域违约） | S6 + §一.9 契约故障不降级 |

---

## 4. 偏离记录（与设计书不一致处，主动报）

| # | 偏离 | 事实与理由 |
|---|---|---|
| **D1** | ★ **T2 的字面读法（面值层面）不成立**：设 `tool=600` 后，"运工具的**运费**低于运粮"只在**费率维**成立（同 lane：grain 52‰ > tool 21‰，实测），**面值维反而不成立** —— 因为 `freightUnitMilli` 里还有 A 批的逐商品基础费（tool 3‰ vs grain 1‰），总运费 tool > grain。实测（three-powers 同 lane、承运成本 100‰）：未设表 grain 2 / tool 4；设 grain1500+tool600 后 grain 2 / tool 4（`ceil(…/1e6)` 粒度）。 | 设计书 §4.1 只冻结"系数乘在整条**费率**上"（甲），没提 A 批已有的基础费；改基础费会改变既有数值口径且超出本批（§2.2 非目标：不加商品细分）。**本批不动它**，把两条商品维的关系如实报出来，请控制方/用户裁定是否要让系数**取代**基础费（那是一次口径变更，需另批）。 |
| **D2** | ★ **T1 的"1.5 倍"在费率维是"⌊base×1500/1000⌋"（截断），面值维在 three-powers 的量级下不变** | 实测：1332 个市场格配对**全部**满足 `grain1500 == base×1500÷1000`；其中 684 个偶基准费率**全部**恰好 1.5 倍（如 base=50 ⇒ 75、base=10 ⇒ 15）。odd 基准如 35 ⇒ 52（52.5 截断，−0.5‰）。面值：`freightUnitMilli = max(1, ⌈基础费×(1000+费率)×(1000+承运成本)÷1e6⌉)`，费率 +1.6%（1035→1052）跨不过 ceil 台阶 ⇒ 逐笔运费不变（实测 day3 粮运 freightPerUnit=2 前后一致）。**取整方向 = 截断**（不给世界凭空加价），这是本批的实现选择，设计书未指定。 |
| **D3** | 面值腿的连通性用**极端系数**（grain=100000）单独证明 | 因 D2：要证明"系数真的进了钱"，必须让乘积跨过 ceil 台阶。实测：同 lane 同商品，`freightPerUnitMilli` 2 → 7、`freightMilli` 39 → 69（真实成交，真结算写的 MarketReport）。 |
| **D4** | `ExpectedProfitBook.maxAdjacentLaneRate` 取**全表最大系数** | 该处没有具体商品。取最大 = 不低估收入（保守方向）；空表 ⇒ 1000 ⇒ 逐值不变。设计书未指定该点，属实现判断。 |
| **D5** | 旧档归一与键域守卫**分层**：状态层只守**值域 > 0**；键域（必须是词表 6 商品之一）只在 GM 命令边界守 | 设计书 §4.1 明确"值域 > 0"，§6.2 N2 把"未知商品"列为**命令**拒因。分层 ⇒ 旧档/夹具能带任意商品键而不炸（"未知键"在状态层无害：没有商品就没有货），同时 GM 写口 fail-closed。 |
| **D6** | 加了**窄工具** `simos.economy.setCommodityFreight`（设计书 §4.1 只写了 GM 命令） | 派单书要求"工具面/GM 桶"接线；形制照 B2 的三条区工具（薄封装 + 只在 GM 桶 + 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS）。 |
| **D7** | 保留 `TransportTariff` 5 参 / `MarketTopology` 2 参与 4 参旧入口 | 设计书说"加第 6 个入参"，没说删旧入口；保留 ⇒ 既有用例与夹具零改动、旧行为逐值不变（I-F1 的直接兑现）。 |

---

## 5. 会改变数值行为的清单（给测试代理当输入）

| # | 何时改变 | 改什么 | 量级（实测） |
|---|---|---|---|
| N-1 | **仅当** GM 设过某商品的系数（表非空） | 该商品的**路线费率**（`Tariff.perMille` 返回值） | grain=1500：base → ⌊base×1.5⌋（35→52、50→75、10→15）；tool=600：35→21 |
| N-2 | 同上 | 由费率派生的**单位运费**（`freightUnitMilli` 的 routeFactor） | 只有在 `ceil(基础费×(1000+费率)×(1000+承运)÷1e6)` 跨台阶时才变；three-powers 的 grain(基础费1) 在 1500 下**不变**（2→2）；grain=100000 时 2→7 |
| N-3 | 同上 | 商号收入估算上界（`ExpectedProfitBook`） | 按全表最大系数放大；空表不变 |
| N-4 | **永远不变**（表为空 / 键缺失） | 所有运费口径 | three-powers 5 天 + small-world 5 天，旧码 vs 新码**全日志 diff = 0**、摘要 SHA-256 相同 |

**受影响硬编码字面量**：无新增标定常量（唯一新常量 `DEFAULT_COMMODITY_FREIGHT_PER_MILLE = 1000` 是**恒等元**）。
既有标定值（`MARKET_FREIGHT_BASE_PER_UNIT_*`、`MARKET_FREIGHT_CARRIER_COST_*`）**未改**。

---

## 6. 会让既有测试失效的清单（★ 我没改任何测试；以下是"会红"的既有用例）

| # | 测试 | 为何红 |
|---|---|---|
| T-1 | `simos-economy` `EconomyRoundTripTest.changeSetHasExactlyThirtyFiveComponents` | 硬编码 `hasSize(35)`（EconomyChangeSet record 组件数）⇒ 现在 36 |
| T-2 | `simos-economy` `EconomyRoundTripTest.everyEconomyDataComponentParticipatesInTheChangeSet` | 反射枚举 `EconomyData` 组件后走 `mutate(name)` / `changedOf(name)` 两个 switch，未登记的名字走 `default -> throw new IllegalStateException("未登记的组件: …")` ⇒ 新增组件即抛 |
| T-3 | `simos-app` `SimosToolsTest.registryContainsExactlyTheExternalUnionGmTools` | `GM_TOOL_NAMES` 是**精确**名单（123 条写工具）⇒ 多一条 GM 窄工具即红 |
| T-4 | `simos-app` `SimosToolsTest`（注册面 136 精确对照那条）+ `McpCoverageTest` | `EXPECTED_COMMAND_TYPES` 精确列表 / `hasSize(136)`；且 `McpCoverageTest` 会**逐条提交**每个注册类型（新命令需要一条合法载荷或具名拒路径） |
| T-5 | `simos-app` `SimosToolsTest.catalogCoversEveryCommandHandlerImplementation` | 扫描 `*Handler.java` 的 `type()` 数量 `hasSize(136)` ⇒ 现在 137 |

**仍然绿（本批刻意保住）**：`TransportTariffP4Test`（10 处 5 参调用，旧入口保留）、`MarketTopologySingleRegionTest`
（2 参入口保留）、`EconomyCodecTest` 的各往返/稳定性断言（新键自洽读写）、`CatalogVisibilityTest`（合成注册面）、
`DecisionCallerFactoryTest`（决策人面现算）。

---

## 7. 探针（真装配、零手动 `core.register`）

**装置**：`/tmp/ffprobe/src/io/mosire/simos/app/time/`（**不进仓库、不进 `src/test`**）
- `LegacyDigestProbe.java`（**只用改动前后都存在的老 API** ⇒ 同一份源码对新码/旧码各编译一次）：真 `Shell.start` + 真创世 +
  真推进，逐日打 `MARKET/HOUSEHOLD/MERCHANT/LANE(2 参费率)/FILL(真实成交运费)/REPORT` 规范行 + SHA-256 摘要。
- `FreightProbe.java`（新码专用）：N1/N2/T1/N3/T2 五条命令 + 往返 / 重放 / codec / 旧档 + 全市场格配对费率统计 + 30 天推进。

**跑法**（编译 = 派单允许的那一条 Maven；探针 = javac/java）：
```bash
tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am
CP="$(ls -d simos-*/target/classes | tr '\n' ':')$(pwd)/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar"
javac -nowarn -d /tmp/ffprobe/out -cp "$CP" /tmp/ffprobe/src/io/mosire/simos/app/time/{LegacyDigestProbe,FreightProbe}.java
java -cp "/tmp/ffprobe/out:$CP" io.mosire.simos.app.time.LegacyDigestProbe <world> <store> 5
java -cp "/tmp/ffprobe/out:$CP" io.mosire.simos.app.time.FreightProbe <world> <store> 30 freight
```
**旧码对照**：`git worktree add --detach /tmp/ffprobe/oldwt a558422f` + 同一份 `LegacyDigestProbe` 源码编译运行。

### 7.1 T3 / N4：未设表 ⇒ 逐值等于改动前（旧码 a558422f vs 新码）

| 世界 | 旧码摘要 | 新码摘要 | 全日志 diff |
|---|---|---|---|
| `three-powers` 5 天 | `2c288f4cb77dba9d6b7e8202138a2a7597c0bd297d5382cabd3bb2ad4155c354` | 同 | `diff` = **0 行**（239 笔真实成交运费逐笔相同） |
| `small-world` 5 天 | `fead1d0871f1bb08dccae5d6d62fb60fc6349de4d9238fcc30b153fe5bbc4492` | 同 | `diff` = **0 行** |

### 7.2 T1 / T2：费率逐值（真拓扑，`MarketTopologyBook.from(state)`）

| 标签 | lane | rates（6 商品，‰） | 表 |
|---|---|---|---|
| BASE | c-tp-copper→c-tp-gold | grain=35 cloth=35 fiber=35 tool=35 iron=35 wood=35 | `{}` |
| T1（grain=1500） | 同 | **grain=52** cloth=35 fiber=35 tool=35 … | `{grain=1500}` |
| T2（tool=600） | 同 | grain=52 … **tool=21** … | `{grain=1500, tool=600}` |

**T1 逐值统计（three-powers 全市场格配对 1332 对）**：
`T1_STATS pairs=1332 truncationFormulaMatches=1332 evenBasePairs=684 exact1_5=684`
`T1_SAMPLE from=-3_0 to=-3_1 base=50 grain1500=75 tool600=30 grainExact1_5=true`
（small-world：`pairs=342 … evenBasePairs=180 exact1_5=180`，sample base=10 → 15。）
⇒ **每一条**都等于 `base×1500÷1000`；偶数基准的**全部**恰好 1.5 倍；奇数基准截断（35→52）。

**面值腿（D3）**：`grain=100000` 时同 lane 真实成交 `freightPerUnitMilli` **2 → 7**、`freightMilli` **39 → 69**
（证明系数真的进了钱；1500 时因 D2 的 ceil 粒度面值不变）。

### 7.3 N1/N2/N3：具名拒 + 零 revision + head 不动（真命令总线）

```
REJECT N1  result=Rejected[non-positive-freight: 运费系数必须 > 0（‰）：0/负不是'说不出价'，而是'免费运输'——不是合法系数] headBefore=1 headAfter=1 headUnchanged=true
REJECT N1b result=Rejected[non-positive-freight: …同…] headBefore=1 headAfter=1 headUnchanged=true      （perMille=-5）
REJECT N2  result=Rejected[unknown-commodity: 商品不在词表里（本仓商品恰 6 种：[grain, cloth, fiber, tool, iron, wood]）] headBefore=1 headAfter=1 headUnchanged=true
APPLY  T1  result=Committed@2 headBefore=1 headAfter=2
REJECT N3  result=Rejected[freight-unchanged: 与现值逐字相同（现值 = 1500‰），不做静默幂等（要回退到现状请显式设 1000）] headBefore=2 headAfter=2 headUnchanged=true
APPLY  T2  result=Committed@3 headBefore=2 headAfter=3
CHECKS_END head=3 (期望 = 3)
```

### 7.4 往返 / 重放（第四台 mapper）/ codec / 旧档

```
ROUNDTRIP delta=Upsert[entries={grain=1500}]
ROUNDTRIP apply(diff(base,target),base).equals(target)=true
REPLAY json={…"householdDebtRefs":{"@class":"unchanged"},"commodityFreightPerMille":{"@class":"upsert","entries":{"grain":1500}}}}
REPLAY readChangeSet->apply.equals(target)=true
REPLAY_OLD_ARCHIVE fieldUnchanged=true apply(cs,base).equals(base)=true     ← 去掉组件键的旧变更集：读成 Unchanged、不抛
CODEC decode(encode(x)).equals(x)=true
OLD_ARCHIVE table={} grainCoefficient=1000 empty=true                       ← 去掉组件键的旧快照：空表 + 缺键 1000
```

### 7.5 推进后仍在（★ `EconomyStateBuilder` 带过没漏）+ 接线面

| 运行 | 结果 |
|---|---|
| `small-world` 30 天（真参与者） | `AFTER_ADVANCE revision=33 table={grain=1500, tool=600} grainCoefficient=1500 toolCoefficient=600` |
| `three-powers` 30 天 | 同上（revision=33，表仍在） |
| 工具面 | `TOOLFACE tool=simos.economy.setCommodityFreight gmBucket=true decisionBucket=false gmTools=161 decisionTools=44` |
| `Shell.start` 本身 | 跑通 ⇒ ① handler 已注册 ② `PAYLOAD_HINTS` 覆盖断言通过（缺项构造期抛） |

### 7.6 日志（§一.9）

```
INFO  event=ECONOMY_SET_COMMODITY_FREIGHT_APPLIED origin=economy-commodity-freight originKind=system commodity=grain perMille=1500 previousPerMille=1000 reason=probe-T1 configuredCommodities=1
INFO  event=ECONOMY_SET_COMMODITY_FREIGHT_REJECTED … commodity=banana perMille=1500 reason=unknown-commodity detail=…
INFO  event=ECONOMY_SET_COMMODITY_FREIGHT_REJECTED … commodity=grain perMille=1500 reason=freight-unchanged detail=…
DEBUG event=ECONOMY_SET_COMMODITY_FREIGHT_REJECT_CRITERIA …（-Dsimos.economy.logLevel=DEBUG 下 4 条拒各一条）
```

### 7.7 探针途中踩到的坑（如实记，供后续复用）

1. **便捷构造器的链式委派**：批量给 4 个旧组件面构造器的 `this(...)` 各加一个尾参 ⇒ 两个构造器（28 参 / 29 参）的委派目标
   从"下一个构造器"变成"没有匹配" ⇒ 编译期报 `Map<Object,Object>` 的不适用。正确做法：逐个核对**该构造器该收几个实参**
   （28→29、29→31、31→规范、33→规范）。编译当场红，未进运行期。
2. **`TreeMap<>(Map<CommodityId,Long>)` 会抛**（`CommodityId` 非 `Comparable`）——探针读数排序必须先转字符串。
3. **`MarketReportFeed` 只保留最后一个 tick 的报告** ⇒ 探针第一版把 `printFills` 放在推进循环外，只读到 day=5（运费全 0 的同格成交），
   漏掉 day=3 那份有运费的报告；改成**逐日取样**后才有逐笔运费证据。

---

## 8. 未完成 / 未验证 / BLOCKED

| # | 项 | 状态 |
|---|---|---|
| U1 | **编译**：`tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` | ✅ exit=0（本批唯一跑过的 Maven） |
| U2 | `test` / `verify` / `package` | ❌ **未跑**（派单书禁止）；T-1~T-5 的既有用例**必然红**，留给测试代理按判据迁移/补新用例 |
| U3 | 前端门禁 / Spotless / Checkstyle / SpotBugs | ❌ 未跑（未获授权跑 Maven 门禁）。已手工控制行宽 ≤ 100 与既有 javadoc 风格；**Spotless 是否逐字满意未验证** |
| U4 | `small-world` / `corridor` 之外的世界（`v17levant` 大世界）未跑 | 未验证（时间与体量）；**不构成结论缺口**：逐值不变由 three-powers + small-world 两条链路证 |
| U5 | T2 的**面值**读法不成立（D1） | ⚠️ 已报，**待控制方/用户裁定**（是否要让系数取代 A 批的商品基础费）——本批按设计书字面（乘在费率上）实现 |
| U6 | `EconomyData.commodityFreightPerMille` 的**键域**在状态层不设防 | 有意的分层（D5）；载入路径只对**值域** fail-closed |
| U7 | 决策人的**间接**路径（IssueDirective 载荷里嵌该命令）未用真命令端到端打 | 由 `GmOnlyCommand` 标记 + 组合根 `directiveCommandTypes` 过滤保证（`Shell.java:812-830`）；探针只证到"GM 桶有 / 决策人桶无" |
| U8 | 无 BLOCKED | — |

---

## 9. 改动文件（17 个：15 改 + 2 新，全在允许面内）

**simos-economy（13）**：`EconomyData.java`、`EconomyLogSource.java`、`change/EconomyChangeSet.java`、
`codec/EconomyCodec.java`、`model/TransportTariff.java`、`spi/EconomySetCommodityFreightHandler.java`（新）、
`spi/EconomySeedHandler.java`、`spi/EconomyClearRegionHandler.java`、`time/EconomyStateBuilder.java`、
`time/MarketTopology.java`、`time/MarketSettlement.java`、`time/ExpectedProfitBook.java`
**simos-app（4）**：`Shell.java`、`time/MarketTopologyBook.java`、`tools/read/CatalogTool.java`、
`tools/SimosToolSource.java`、`tools/write/EconomySetCommodityFreightTool.java`（新）

**未碰**：`src/test/**`、任何 `pom.xml`、`docs/**`、`.superpowers/**`（除本目录）、`simos-util/**`、
`SocialProvisioning`/需求侧、口岸/关税/摩擦、`MarketZone`/`Government` 状态形状。
