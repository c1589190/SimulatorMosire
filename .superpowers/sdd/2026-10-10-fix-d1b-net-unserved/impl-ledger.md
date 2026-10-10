# 实现架构账本 —— 修 D-1b（同一条 lane 的"未服务量"被两个落点重复记账）

责任区：`simos-economy/src/main/**`（唯一写口：`MarketSettlement` + 新增 `LaneUnservedBook`）
纪律：只写生产代码、只到编译过（`spotless:apply` + `-DskipTests compile`）；**不跑** `test`/`verify`；不 commit；
`src/test/**`、`pom.xml`、`docs/**`、`AGENTS.md` 一个字未动（只写本账本）。
判据来源：派单书（控制方裁定 1–4）；上游证据 `.superpowers/sdd/2026-10-10-fix-d1-truncation-cap/impl-ledger.md` §4。

---

## 1. 关键调查结论（先实测，再下结论 —— 与派单书的推导**不一致**）

用 /tmp 手工装置（不落仓，见 §5）把 D-1 场景跑起来，得到**调用序**（不是推演）：

| # | 证据（实测） | 结论 |
|---|---|---|
| C-1 | 区内部分运力（买 2,905 / 卖 4,000 / 运力 1,000）：`markCapacityBlocked` 被叫 **2 次**，两次都是 `truncated=1905`，第 2 次前 `buyTrunc=1905/sellTrunc=1905` ⇒ 记完变 **3,810/3,810** | 重复记账的**真正落点**是 `executeTrade` 的两处"承运分配不足"调用点（现网 `:6417` 全被拦 + `:6431` 部分承运）⇒ `markCapacityBlocked`（`:6779`）**被同一对槽位反复叫**，不是派单书说的 `:5291` |
| C-2 | `[:5291]`（路线窗口用尽）在上述场景**一次都没触发**：`capacityPerWindow = MARKET_ROUTE_CAPACITY_MILLI_PER_WINDOW(100,000,000) × REF(8) ÷ distance` = **8×10⁸**（`:5076-5081`），夹具尺度（10³–10⁴）到不了 | 派单书"`:5291` 已记 1,905 + 本方法再记 min(1,905,3,000)"是**误判落点**；`:5291` 只在单窗口运走 8×10⁸ 毫商品时才可达 |
| C-3 | `blockLaneWithoutCapacity`（调用点 `:4687` 跨区 / `:5501` 区内，判据 `:5500`）在该场景**也没被叫**：`MerchantCapacityPool.hasCapacityAt`（`:575-577`）是**静态**判据（装配时该格有没有运力，本轮吃光不影响）⇒ 有运力的格永远走不到整条拦下 | "两处落点相加"的形态在本场景**不成立**；真正重复的是**同一个落点的多次观察** |
| C-4 | 跨区变体：`markCapacityBlocked` 被叫 **5 次**（1 次部分承运 + 4 个运力窗口各一次），每次都 `truncated=1905` ⇒ 两侧截断 **9,525** | 重复记账的一般形态 = "每一次试配/每一个运力窗口各记一遍"；后果更重：两侧同被剔光 ⇒ **demand=supply=0 ⇒ z=0 ⇒ 价格原地不动**（跨区 10,000/10,000 冻结） |
| C-5 | `adaptPrices:1893/1900` = 截断读数的**唯一价格消费口**（`quantity − min(quantity, trunc)`） | 去重后的价差即判据：区内 (demand,supply) 从 (0,190) 变 (1,000,2,095) |
| C-6 | 多买槽车道（两户各 2,905 / 卖 4,000 / 运力 1,000）：修前逐槽记 **2,000/2,000**、卖侧 **4,000**（两侧都超记）；真正未服务量 = 4,000−1,000 = **3,000** | 只用"车道级水位"会把第二对槽位的量并掉（漏记）；⇒ 逐笔落点必须用**配对键**（见 §2） |
| C-7 | `marketExcluded`/`MatchContext` 生命周期：`MarketContext` 每次 `clearOncePerCycle` 新建（`:1398`） | 记账簿放 `MatchContext` 里 = **逐轮瞬态**，不进 `EconomyData`/变更集/落盘（铁律 2/5 不受影响） |
| C-8 | worker 并行路径只在**空池**时启用（`:3828-3829`），且 worker 各持一个**新的** `MatchContext`（`:3977-3994`） | 有运力（⇒ 能到运力截断）的世界恒走协调器单线程 ⇒ 记账簿无共享写、无竞争 |

## 2. 实现架构

**净额记账 = 一份"已记过多少"的水位 + 三个落点共用**（裁定 1 的"共享同一份已记状态"）：

```
新增 simos-economy/src/main/java/io/mosire/simos/economy/time/LaneUnservedBook.java（逐轮瞬态，~105 行）
  static String laneKey(from, to, commodity)          // 与 ctx.routes 同一个键拼写（:5096 已改用它）
  long claimPair(laneKey, buyIdx, sellIdx, unserved)  // 逐笔落点：配对键水位，另把 delta 累进"该车道配对已记之和"
  long claimLane(laneKey, unserved)                   // 整条车道落点：车道键水位（看 max(车道水位, 配对之和)）
```
- 三个落点全部改为"先认领、再按认领到的增量记两侧"（返回 0 = 已记过 ⇒ 一个字不动）：
  - `MarketSettlement:6793-6800`（`markCapacityBlocked`，承运分配不足）；
  - `MarketSettlement:6840-6843`（`blockLaneWithoutCapacity`，整条拦下）；
  - `MarketSettlement:5286-5307`（`matchRoute` 尾部，路线窗口预算用尽）。
- 状态：`MatchContext:9317` `final LaneUnservedBook laneUnserved = new LaneUnservedBook();`（逐轮瞬态）。
- 标记与日志**不受去重影响**：`buy.blocked` / `sell.capacityBlocked` / `acc.bottleneck` 全部照旧无条件置位；
  DEBUG `MERCHANT_CAPACITY_LANE_BLOCKED` 新增 `recordedMilli`（本次真记进去的量；0 = 已记过），原 `truncatedMilli` 语义不变（车道级观察量）。

## 3. 关键判断

- **两级水位**（配对键 + 车道键），而不是只用派单书说的"车道键"：只用车道键时，同车道**第二对**槽位的量会被并掉（C-6 实测形态）
  ⇒ 那正是裁定 2 禁止的"漏记真正的未服务量"。两级之间**双向取大**：逐笔认领看车道水位（整条拦下记在**所有**还有剩余的槽位上）；
  整条车道认领看"配对已记之和"。⇒ 同一份量在两个方向上都只记一次。
- **为什么不去重"已服务的量"**：认领的量恒等于**本轮的一次真实观察**（本笔真正没被承运的量 / 本车道两侧余量的较小者），
  不用推算量 ⇒ 去重只削重复。实测自证（多买槽）：逐槽各记 1,000 / 2,000 = 两侧各 3,000 = 这条 lane 真正的未服务量，
  修前是 2,000/2,000/4,000（超记）。
- **为什么不"收敛成一处记录"**（裁定 1 的另一选项）：三处的**具名语义不同**（部分承运 / 整条拦下 / 窗口用尽），
  合并会让"为什么走不动"的归因退化；共享水位是更小的改动面。
- **`:5291` 那一处也纳入**（虽然实测不可达）：它的语义（各侧记自己的余量）与 D-1 的裁定口径不一致（余量大的一侧会超剔），
  改成车道级 min + 同一水位后，它一旦在某天真的触发也不会重复记账（§5 用人为调小常数的 /tmp 副本验到了它确实会记且只记一次）。
- **缺省中性**：无运力/无截断 ⇒ 三个落点都到不了（或观察量 ≤ 0）⇒ `claim*` 直接返回 0、一个字节都不写 ⇒ 逐值不变。
- **确定性 I7**：只按键读写、不迭代簿表、不读时钟/随机/哈希序（`LinkedHashMap` 仅保序）。

## 4. 偏离记录

1. **派单书的落点判断与实测不符**（C-1/C-2/C-3）：按 §四"机制性描述一律回代码核"如实记，未照措辞硬做；
   修的是**真实的**重复路径（`markCapacityBlocked` 被反复叫），并把 `:5291` 一并纳入同一水位。
2. **编译命令**：派单书给的 `tools/mvn-lock.sh -DskipTests compile -pl simos-economy` **失败**（`~/.m2` 里没有
   上游 SNAPSHOT；报 `cannot find symbol: MarketZoneId/MarketZone/OfficialRate/PortDirection`，rc=1）⇒ 改用仓内
   `tools/mvn-lock.sh` 自述的推荐形态 **`-pl simos-economy -am`**（列 9 个模块，EconomySimos 编 200 文件）rc=0。
   `spotless:apply -pl simos-economy` 原样可用（rc=0；随后 `spotless:check` 也 rc=0）。
3. **新增了一个类**（`LaneUnservedBook`）而不是把 4 个自由函数塞进 `MarketSettlement`：与仓内
   `CapacityDemandBook`/`MerchantProfitBook` 同款形态，键/水位逻辑只有一处拼写点。

## 5. 手工验证（**不是仓内测试**；装置全在 `/tmp/d1b`，不落仓）

```
# 装置：同包（io.mosire.simos.economy.time）的 /tmp 探针 + 从 surefire XML 里取回的**真**测试 classpath
#   /tmp/d1b/probe/...  D1bProbe（判据场景）/ D1bExistingCasesProbe（既有三案）/ D1bMultiSlotProbe / D1bCrossRegionProbe
#   /tmp/d1b/out = **修前**源码编译；out-final = 最终字节（MarketSettlement md5 0c04b3393722978e60db4744b741df18）
javac -cp "$CP" -d out-final <最终源码+探针> && java -cp "out-final:$CP" io.mosire.simos.economy.time.D1bProbe
```

| 场景 | 修前 | 修后（最终字节） | 判据 |
|---|---|---|---|
| **D-1b 判据**（区内，运力 1,000） | 截断 3,810/3,810 ⇒ (demand,supply)=(0,190) ⇒ **9,500** | 截断 **1,905/1,905** ⇒ **(1,000, 2,095)** ⇒ **9,823** | ★ 达成（`adaptiveNextPrice(10,000,1,000,2,095)=9823`） |
| 跨区多窗口同 lane | 5 次记账 ⇒ 9,525/9,525 ⇒ 两侧全剔 ⇒ **10,000/10,000 冻结** | 记账 1 次 ⇒ **H1 9,500 / H2 10,500**（区域信号恢复） | 方向正确、非判据值 |
| 多买槽（2 买 1 卖） | 逐槽 2,000/2,000 + 卖 4,000 ⇒ 10,500 | 逐槽 **1,000/2,000** + 卖 **3,000** = 真未服务量 ⇒ 10,238 | 裁定 2（不许漏记/超记） |
| 既有案① `world(-1L)` 空池 | 9,500 / 零成交 | **9,500 / 零成交**（逐值不变） | 既有断言不变 |
| 既有案② 同格+跨格买方 | 9,790 | **9,790** | 既有断言不变 |
| 既有案③ 有运力 10,000 | 9,921 | **9,921** | 既有断言不变 |
| `:5291` 可达性（**只在 /tmp 副本**把 `MARKET_ROUTE_CAPACITY_MILLI_PER_WINDOW` 人为调小） | —— | `[S3 recorded=1905]` 记一次；随后 3 次逐笔认领 `recorded=0` | 第三落点确实共用同一水位 |

**两条真门禁**（一次性、串行，`tools/mvn-lock.sh`）：
`spotless:apply -pl simos-economy` rc=0 + `spotless:check -pl simos-economy` rc=0；
`-DskipTests compile -pl simos-economy -am` rc=0（`BUILD SUCCESS`，EconomySimos 编译 200 文件，`LaneUnservedBook.class`/`MarketSettlement.class` 落在本轮）。

## 6. 会变的读数 / 断言值（给测试 Agent）

- **会变**：`BuySlot.capacityTruncatedMilli` / `SellSlot.capacityTruncatedMilli`（内部读数，被 `adaptPrices` 消费）；
  DEBUG `CapacityDemandBook:268 goodsBlockedByCapacityMilli`（= 各买槽截断之和；部分运力场景下变小，如 D-1b 场景 3,810→1,905、多买槽 4,000→3,000；
  无运力单车道场景不变，如 `world(-1L)` 仍 2,905）；DEBUG `MERCHANT_CAPACITY_LANE_BLOCKED` 新增字段 `recordedMilli`（老字段 `truncatedMilli` 不变）。
- **会变的价格表**（仅在"部分运力 ⇒ 同一份未服务量被观察 ≥2 次"的世界）：D-1 场景 9,500 → **9,823**；跨区 10,000/10,000 → 9,500/10,500；多买槽 10,500 → 10,238。
- **既有断言：实测一处都不变** —— `CapacityTruncationPricingAcceptanceTest` 三值 9,500/9,790/9,921 逐值不变（/tmp 实测）；
  `MerchantCapacityAcceptanceTest.endToEndFillIsCappedByTheHexCapacityPool`（唯一部分运力案）只断言 capacity 2,500 / 成交笔数·量 / 具名 `LOGISTICS_CAPACITY`，
  成交与归因**一字未动**；`PortPolicyMerchantLaneAcceptanceTest`（运力 10,000,000）/ `PortGateTaxAcceptanceTest`（10,000,000+10,000）/
  `MarketSettlementSingleHexLossTest` 运力 ≫ 需求 ⇒ 无截断 ⇒ 不变。全测试树里带 carrier 的只有这 5 个文件（已逐个核）。
- **零改动面**：成交/余额/冻结/债务/运费/税/ChangeSet/落盘 —— 本批只动"读数水位"，不碰任何写口。

## 7. 未做 / 未验证（如实记）

1. **没跑 `test`/`verify`**（派单纪律）⇒ "既有断言不变"是**实测 3 个判据值**（/tmp 装置），不是跑过测试套件。
2. **`:5291` 的语义改动未在真实尺度验证**：夹具尺度不可达（C-2），只在 /tmp 副本人为调小窗口常数时验到"会记且只记一次"；
   其"各侧自己余量 → 车道级 min"的口径变化**没有任何既有断言覆盖**（无可测面）。
3. **S1→S3 方向的级间覆盖未实测**（只验到 S3→S1）：结构上要"同一轮里先有逐笔记账、再有窗口用尽"，而窗口是上限（`matched ≤ capacityLeft`）
   ⇒ 同一轮里两者难以共存；`pairRecordedSum` 那一支是**纸面保证**。
4. **多政府/多商品/多区并行的组合未逐一跑**：worker 路径（空池）不触碰记账簿（C-8，静态论证）。
5. **真实 world 长跑未跑**（AGENTS §七：长跑不进程序化门禁）：价格信号在真实 19 hex 世界里的读数变化**未观测**。
6. **未做**：`git commit`、任何测试/文档改动（`docs/**`、`pom.xml`、`AGENTS.md`）；`.superpowers/**` 只写本文件。
