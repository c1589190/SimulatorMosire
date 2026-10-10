# 实现台账 —— A3 残留修复：谁能提供运输服务（F-1 / F-3 / F-2）

- 责任区：让"谁能提供运输服务"的判据与"谁有服务货"一致（收掉 A3 的残留 F-1/F-3），并让 F-2 归因可分辨。
- 允许写：`simos-economy/src/main/**`、`simos-app/src/main/**`。本批实际只动 `simos-economy/src/main`（6 个文件）。
- 约束设计书：`docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` v1.3 §3.2/§3.3/§5/§6（I-H2/I-H3/I-H7、T-H2）。

## 1. 关键调查结论（file:line 实测 → 结论 → 影响）

1. `MerchantCapacityPool.java:387-389`（改前）`if (capacity.capacityMilli() <= 0L) continue;` —— **进池闸**是
   `MerchantCapacity.of(labor, tool)` 的运力（劳动 + 工具）。⇒ 服务成市格里"劳动/工具为 0 但手上有 `haul` 服务货"
   的户结构上不进池 ⇒ 卖不出服务，与 §3.2 不一致。**这就是 F-1**。
2. `MerchantCapacity.java:118-131`（改前）`tier = tierOf(capacity)`、`serviceRadiusHex = serviceRadiusOf(tier)` 全由
   "劳动 + 工具"派生；`MerchantCapacityPool.askPerMilleOf`（`:600-604`）再由 tier 派生限价 ⇒ 池内**限价序**也由
   劳动 + 工具派生。**这就是 F-3**。
3. `MerchantCapacityPool.select` 的返回值 `CarrierAllocation` 只有 `(choices, requested, unallocated, priced)`；
   归因串只出现在 `LOG.isDebugEnabled()` 包住的 `MERCHANT_CAPACITY_LANE_TRUNCATED.reason` 里（`:803-851` 改前）。
   `MarketSettlement.blockLaneWithoutCapacity` 的逐笔 DEBUG 里 reason 恒为字面量
   `"shipping-hex-has-no-merchant-household"`，且 `collectUnfilled` 只把
   `MarketUnfilledReason.LOGISTICS_CAPACITY` 交给读数面（`MarketReadout.matchReadout` 只按 reason 聚合）。
   ⇒ **"没有服务卖"与"运力不足"在公开读数面同为 `LOGISTICS_CAPACITY`，不可分辨**（F-2）。
4. 归因所需的两个事实在同一处已具备：`select` 内知道 `haulServiceHexes.contains(from)` 与
   `totalCapacityByHex.get(from)`；`haulServiceAt(ctx, hex)`（`MarketSettlement:7449-7451`）是"本格服务成市"的
   唯一判据（`HaulService.pricedAt`）。⇒ 可分辨归因不需要任何新状态。
5. `MarketReport.Unfilled` 是**瞬态读数**（不落盘、不进 codec/变更集），构造点只有 `MarketSettlement:7766/7778`
   与两个夹具；给它加一栏不触碰铁律 5。
6. 测试批的 R1/R3 夹具把"有跑商家户但没有服务货"这一事实**钉在池成员数**上
   （`HaulServiceCommodityAcceptanceTest:271` 断言 `pool.householdCountAt(H1) == 1`）。⇒ 若把"预算为 0"的户
   从池里删掉，这条断言会红，且"有提供商 vs 没提供商"在池侧读数面丢失（详见 §3 判断 2）。
7. 两个测试类在 `:229` / `:105` 用 **6 参**构造 `MarketReport.Unfilled`。⇒ 加第 7 栏必须同时保留 6 参重载，
   否则 `src/test/**` 编译不过（而本批**禁止碰测试**）。

## 2. 本批实现架构（改动落点）

| 文件 | 落点 | 改了什么 |
|---|---|---|
| `time/MerchantCapacity.java` | `ofService(...)` `:160` | 新装配口：`capacityMilli = 服务货可用量`，`tier`/半径由它派生；`of(...)`（劳动+工具口径）原样保留，仅服务格改走新口 |
| `time/MerchantCapacityPool.java` | `assemble` `:447-476` | F-1：服务格 `servicePriced` 分格，进池不再要"劳动+工具>0"；F-3：服务格的 capacity/tier/半径由服务货派生 |
| 同上 | `LogisticsBlock` 枚举 `:997`、`logisticsBlockOf` `:1050`、`laneBlockedReason` `:969` | F-2：把归因收敛成**单一可分辨档**（6 档，两支业务拒绝） |
| 同上 | `select` `:886`、`CarrierAllocation.logisticsBlock` | 归因随分配结果返回（**同一写点**，与 choices 无耦合）；DEBUG 行另加 `logisticsDetail`/`businessRejection` 两栏 |
| 同上 | `serviceAbsentTally` `:244/283/291/539/1279` | 装配期缺货计数（瞬态、只进日志）：`serviceGoodsAbsentHouseholds` 进逐轮 INFO |
| `time/MarketSettlement.java` | `logisticsBlockDetail` `:6896`、`logLogisticsBusinessRejection` `:6911`、`markCapacityBlocked` `:6966`、`blockLaneWithoutCapacity` `:7046` | 三处"卡在运力"落点都写具名档；业务拒绝发 **INFO** 具名行；`Unfilled` 带上该档 |
| `time/MarketReport.java` | `Unfilled.logisticsDetail()` `:664-700` | 第 7 栏（可空）+ **保留 6 参重载**（夹具不用改） |
| `time/MarketReadout.java` | `CommodityMatchReadout.unfilledLogisticsDetailQuantities` `:414/456/712` | 读数面：按具名档分列未成交量（保序防御性拷贝） |
| `time/EconomySettlement.java` | `MARKET` 轮汇总 `:2219-2250` | 加 `logisticsDetailQuantities` 栏（空表 = 本轮没有卡在运力的未成交） |

## 3. 关键判断（为什么这样，不那样）

1. **F-1 只改"服务成市格"的闸，不改无牌价格**：`servicePriced` 由 `haulServiceHexes.contains(hex)` 判（上游唯一
   拼写点仍是 `HaulService.pricedAt`）。无牌价格 `capacityMilli <= 0 ⇒ continue` **逐字保留** ⇒ 缺省中性是
   **逐表达式**成立，不是"算出来恰好相等"。
2. **"预算为 0 的跑商家户留在池里"（与派单字面不同，主动记录）**：派单写"持有可交付的服务货即可进池"。
   实测 `HaulServiceCommodityAcceptanceTest:271` 要求 `householdCountAt(H1) == 1`（"这一格**有**跑商家户"是它的
   判据），把预算 0 的户删掉会让该断言红、且"缺提供商"与"缺货"在池侧读数面合流。折中口径：
   **进池 = 选了跑商（服务格不另设门槛）；能不能承运 = 预算 > 0**（`select` 只看预算）。于是
   - 劳动/工具为 0 但持有服务货 ⇒ 进池**且**能承运（F-1 修好）；
   - 有提供商但没有服务货 ⇒ 进池、预算 0、分不到量，但**读数面看得见**（`householdCountAt` + 新 INFO 计数 + F-2 具名档）。
   ★ 这一条与派单书字面冲突，已按派单书"口径不够 ⇒ 停手上报"如实上报（见 §6 未完成项 1）。
3. **F-3 的档界不新造**：直接复用 `MerchantCapacity.tierOf` 的 100,000 / 400,000 档（它承载"承运成本档
   25/50/100‰ + 触达 2/4/8 hex"两个**规模的函数**），服务货与之同一量纲（1 商品单位 haul = 1,000 毫服务 =
   1,000 毫商品·程，A1 单位锚）⇒ 服务规模 2,000 毫 ⇒ PORTER，500,000 毫 ⇒ BOSS。
4. **序不另设特权队列**：仍走 `askPerMille` 升序 → 议价权降序 → 家户 id 升序（`:475-480` 未改）；只是
   `askPerMille` 的输入从"劳动+工具派生的 tier"换成"服务货派生的 tier"。⇒ §3.3 的"价格优先、同价按既有
   canonical 序"逐字成立。
5. **归因用枚举而不是裸字符串**：`LogisticsBlock` 是唯一拼写点；`businessRejection()` 一处分级（§一.9：业务拒绝 = INFO），
   调用方不自己判词。`UNSPECIFIED` 兜底不许硬塞假原因。
6. **不给 `BuySlot/SellSlot` 加"第二套判据"**：具名档与 `blocked`/`capacityBlocked` **同写点**落定；它只作读数
   （不进账本、不进账本金额、不落状态）。
7. **`Unfilled` 加栏必须保留 6 参重载**：否则两个夹具编译不过（禁止改测试）。重载 = `logisticsDetail null`（"没有具名归因"）。

## 4. 偏离记录（主动）

- **偏离 1**：见 §3 判断 2（预算 0 的跑商家户留在池里，而非"无服务货即不进池"）。理由：不破坏既有验收断言 +
  保住 F-2 的"缺提供商 vs 缺货"分辨力。
- **偏离 2**：派单书把 F-2 的目标描述为"公开读数面可分辨"。本批落在**两处公开读数**：
  ① 逐轮 `MARKET` 汇总（TRACE 档 logger，但**始终发射**，与档位无关）的 `logisticsDetailQuantities`；
  ② 业务拒绝的 **INFO** 行 `LOGISTICS_SERVICE_SUPPLY_REJECTED`（§一.9 的"业务拒绝 = INFO"）。
  另加 `MERCHANT_CAPACITY_POOL`（INFO）的 `serviceGoodsAbsentHouseholds`。★ **未**改 `MarketUnfilledReason`
  词表（它是 `simos-economy-api` 的稳定契约，且 6 个按档聚合的断言依赖 `LOGISTICS_CAPACITY` 这一档仍在）——
  所以 `reason` 仍是 `LOGISTICS_CAPACITY`，**可分辨的那一半在新增的 `logisticsDetail` 栏**。
- **偏离 3**：`NO_SERVICE_GOODS_REASON` 的字面量我取 `merchant-household-holds-no-service-goods`（新常量，
  与既有 `HaulService.NO_SERVICE_SUPPLY_REASON = no-haul-service-in-shipping-hex` **分开**）：前者"有提供商、没有货"，
  后者"这一格的服务货口径下没有服务卖"。两者都进同一 `LogisticsBlock` 词表，读数面按名分档。

## 5. 验证证据（真实命令 + 结果）

| 命令 | 结果 |
|---|---|
| `tools/mvn-lock.sh -q spotless:apply` | rc=0 |
| `tools/mvn-lock.sh -DskipTests compile -am` | `BUILD SUCCESS`（`rm -rf simos-economy/target/classes` 后重编 198 + 328 个源文件） |
| `tools/mvn-lock.sh -q -pl simos-economy,simos-util,simos-app -am test` | rc=0；**economy 288 / util 217 / app 910 条，0 失败 0 错误**（app 5 条跳过 = 既有 `RealLlm*` 环境门控）；前端门禁 **412/412** |
| 基线（改动前同一条命令） | economy 288 / util 26 个测试类 全绿 —— 对照用 |

**只读验证装置**（临时文件 `/tmp/f1check/**`，**不在仓内**、不碰 `src/test/**`；与夹具同包以复用
`MarketSettlementFixtures`）：

```
[F-1] labor=0&tool=0&haul=2000 ⇒ 进池户数=1 本格运力=2000 select(1500)⇒分配=1500 承运户=hh-f1-idle-but-has-service-goods
      （改前：劳动+工具=0 ⇒ 不进池 ⇒ 0 户 / 0 分配）
[F-3] 池内序（价格优先）首条=hh-f1-idle-but-has-service-goods tier=PORTER askPerMille=50
      （小户劳动 900,000 但服务货 2,000 ⇒ 新口径 PORTER；大户 labor=0、服务货 500,000 ⇒ 能进池）
[F-2] 有商家户但零服务货 ⇒ 归因=no-haul-service-in-shipping-hex 业务拒绝=true
      池侧分档计数={merchant-household-holds-no-service-goods=1}
[F-2] 无任何商家户 ⇒ 归因=no-merchant-household-in-shipping-hex
```

## 6. 未完成 / 未验证项（如实）

1. **口径与派单字面的偏离待裁定**：派单写"持有可交付的 `haul` 服务货 ⇒ 即可进池"，实现是"选了跑商即进池、
   运力 = 服务货"（预算 0 的户留池、分不到量）。二者在**承运结果**上等价（预算 0 ⇒ 分不到），差别只在
   `householdCountAt` / 池成员读数。若要严格照字面，需同时改 `HaulServiceCommodityAcceptanceTest:271`
   （本批禁止改测试）⇒ 停手上报。
2. **未跑 `verify` / world / `test` 以外的门禁**（按派单书只到编译过；测试运行是为核对既有断言不受影响，
   已如实记录）。SpotBugs/Checkstyle 未跑。
3. **未做变异自证**（测试阶段归属：按 §三.0 与派单书，变异自证留到最后统一做）。
4. **真实 world 的读数变化未验**：本批没有跑 `run-small-world.sh` / `test-world`，所以"服务成市格里
   `blockedByReason` / `logisticsDetailQuantities` 在一年期真档里长什么样"没有实测样本。
5. **`ExpectedProfitBook` 的跑商模式可行性仍用"劳动"派生 tier**（`:654-667`，纯状态路径、无商品账）——
   本批未动它（派单书只要求池的提供者判据/序）；若它也要按服务货口径，属另一责任区。
6. **`MarketReport.Unfilled` 6 参重载**是为不动夹具加的；它是否该在某批退役，未定。
