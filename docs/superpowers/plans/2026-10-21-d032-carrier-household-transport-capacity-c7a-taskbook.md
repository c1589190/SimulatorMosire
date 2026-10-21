# D-032 C7a 责任区任务书：运输市场区锚价状态层（2026-10-21）

> **状态：可派单的实现任务书；只写生产代码到 `compile` 绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md` §3.2/§7.2/§7.4/§10/§11/§13
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §6 C7
> **上游：** C1–C6 已完成且 compile 绿；C6 已有 `transportCapacityBatches`（第 36 领域组件）、
> `TransportCapacityBatchBook.marketRoundIdOf`、S7-END 当前轮清零。
> **拆分说明：** C7 同时包含“市场区锚价状态/定价”和“需求派生 + lane 分配 + 商品撮合约束”。
> 本任务只做 C7a：**锚价状态与纯定价层**；C7b 再做需求/lane/matcher/旧 haul 退役。

---

## 0. 用户原话与冻结口径

```text
市场上看着欣欣向荣，但是不同hex面对统一价格各有不同的反应。
```

```text
每个市场区只有一个运力锚价；同区所有 hex、所有承运户看到同一个价格；市场轮内价格不变；
市场轮结束后根据本轮结果更新，下一市场轮生效。
```

```text
D > S：缺运力，提高价格；D <= S 且持续满足：延迟后降低价格，最低回 0。
```

```text
D/S 按市场区汇总；不把本地未成交量与本地未售容量先互相抵消。
```

冻结：

- 锚价缺失 = 初始 0，0 是合法价格；
- 锚价按市场区唯一，键 = `MarketZoneId`；
- 货币 = 本区市场表 numeraire；
- 锚价只在市场轮结束更新，下一市场轮生效；
- D/S 由 C7b 的运输轮计划汇总；C7a 只提供状态和纯更新入口；
- C7a **不**做需求派生、lane 分配、matcher 约束、旧 haul 退役。

---

## 1. 目标运行序列

```text
S7-T/CAP/MATCH（C7b 才实现）
  → 得到 D_zone = Σ TransportDemand.workMilli
           S_zone = Σ 本市场轮本地 TransportCapacityBatch.capacityWorkMilli
           committed/unserved/unsold 观察

S7-END（本责任区提供纯入口；C7b 接线）
  TransportPriceAnchorBook.recordMarketRound(...)
    marketRoundId = C6 的当前市场轮 id
    zone = MarketZoneId
    demandWorkMilli = D_zone
    supplyWorkMilli = S_zone
    alpha = 本区 MarketZone.priceAlphaPerMille
    当前 anchor = 缺失 ? 0 : 已存锚价
    D > S  ⇒ 短缺 streak+1，按既有 MarketPricer.adaptiveNextPrice 涨
    D <= S ⇒ 满足 streak+1；连续满足达到 N 轮后才降，最低回 0
    D = S = 0 ⇒ 价不变，只记录观察
  写入 EconomyData.transportPriceAnchors（下一市场轮才读）

下一 S7（C7b）
  只读同一市场区锚价；不同 hex 的本地观察可不同，但锚价相同。
```

### 对父序列的影响表

| 维度 | C7a 影响 |
|---|---|
| 顺序 | 无新阶段、无市场撮合改动；只新增 S7-END 可调用的纯状态写入口。 |
| 预算/冻结 | 不影响商品/土地预算、冻结、订单、成交。 |
| 身份/持久化 | `EconomyData` 新增第 37 个领域组件 `transportPriceAnchors`；ChangeSet/StateBuilder/Transforms/Codec 同步。 |
| 失败/原子性 | 锚 price/streak 非负；key/value 市场区一致；坏输入 fail-closed；不写半状态。 |
| 日志 | `TRANSPORT_ANCHOR_UPDATED` INFO（zone/currency/D/S/old→new/streak）。 |
| 测试 | 只交测试 owner；本任务不运行。 |

---

## 2. 责任区六要素

### 2.1 问题现场（C6 后，file:line 已核）

- `EconomyData` 已有 36 个领域组件（含 `transportCapacityBatches`）；
- `TransportCapacityBatchBook` 已能产出当前 marketRoundId 的供应批次、并在 S7-END 清零；
- 没有运输锚价持久组件；
- 没有 `TransportPriceAnchorBook`；
- C7b 的需求/lane 计划尚未存在，因此本任务不接 matcher。

### 2.2 责任归属

C7a writer 负责运输市场区锚价的持久状态、定价纯函数、状态同步与日志；
不负责需求派生、lane 容量承诺、商品撮合、运费支付、旧 haul 退役。

### 2.3 最终能力

1. `TransportPriceAnchor` 可按市场区持久保存；
2. 缺失锚价按 0 读取；
3. D>S 涨价；D<=S 连续满足后延迟降价；最低 0；
4. 市场轮内锚价只读、轮末更新；
5. 36→37 领域组件在 EconomyData/ChangeSet/StateBuilder/Codec 同步；
6. C7b 可直接读取并调用，不复制定价公式；
7. compile 绿。

### 2.4 不可破坏机制

- 一个市场区只有一个锚价；
- 不新增第二个市场/价格权威；
- 不把 D/S 用本地 hex 互相抵消；
- 不改 C6 批次清零、C7b 尚未实现的 matcher；
- 不写/跑测试、不改 pom/tools/world/docs（账本除外）、不碰主树。

### 2.5 可动范围

见 §6；只改 main 代码到 compile 绿。

### 2.6 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c7a/impl-ledger.md`，报告编译、改动文件、
实际 anchor 形状、定价入口、组件数影响、C7b 调用契约、测试失效面、未完成/blocker。
账本必须先写“目标运行段落 + 对父序列影响表”，再写实现细节。

---

## 3. 冻结数据形状

### 3.1 TransportPriceAnchor

建议包/名（账本记录实际）：

```text
TransportPriceAnchor {
  MarketZoneId marketZone,
  CurrencyId currency,
  long pricePerWorkMilli,
  long shortageStreak,
  long satisfiedStreak,
  long lastAdjustedMarketRound
}
```

- `pricePerWorkMilli >= 0`；0 = 初始/明确免费，不是缺价；
- `shortageStreak >= 0`、`satisfiedStreak >= 0`；
- `lastAdjustedMarketRound`: 0 允许（day 0 市场轮），但需要与“缺失”区分；缺失由 map 缺键表达；
- currency 必须等于当刻该 zone 的市场表 numeraire（若表存在；不存在则为契约故障或允许空？由实现选 fail-closed）。

### 3.2 持久组件

`EconomyData.transportPriceAnchors: Map<MarketZoneId, TransportPriceAnchor>`
作为**第 37 个领域组件**追加在 record 末尾：

- 空表 = 从未有锚价，读取价 0；
- key/value.marketZone 必须一致；
- 同步 `EconomyData`、`EconomyChangeSet`、`EconomyStateBuilder`、`EconomyDataTransforms`、`EconomyCoreCodecs`
  的 key 注册（`MarketZoneId` 已有 key 反序列化器；若没有则补）、`EconomyDataInvariants`、`empty()`；
- 其他 main 全参构造调用点补齐；
- Alpha 不写旧档兼容。

### 3.3 定价入口

新增 `TransportPriceAnchorBook`（包 `io.mosire.simos.economy.time` 或同层，账本记录）：

```text
static TransportPriceAnchor anchorOf(EconomyDayView view, MarketZoneId zone)
  // 缺键返回 price=0 / streak=0 / lastAdjusted=-1 的只读默认锚，不写状态

static long pricePerWorkMilli(EconomyDayView view, MarketZoneId zone)
  // = anchorOf(...).pricePerWorkMilli()

static int recordMarketRound(
    EconomyStateBuilder sheet,
    EconomyDayView view,
    MarketZoneId zone,
    long marketRoundId,
    long demandWorkMilli,
    long supplyWorkMilli)
  // 校验、读旧锚、算新锚、写回；返回 1=变化，0=无变化
```

规则：

- `D > S`：`shortageStreak+1`，`satisfiedStreak=0`；用 **既有** `MarketPricer.adaptiveNextPrice(oldPrice,D,S,alpha)` 涨价；
- `D <= S`：`shortageStreak=0`，`satisfiedStreak+1`；
  - `satisfiedStreak < SATISFIED_ROUNDS_BEFORE_DECREASE` ⇒ 价格不动；
  - 达标 ⇒ 用同一 `adaptiveNextPrice(oldPrice,D,S,alpha)` 降价；
- `D = S = 0`：价格不动；streak 可按“满足”累计但不强制涨价/降价；
- `alpha = 本区 MarketZone.priceAlphaPerMille`；无 MarketZone 记录 ⇒ fail-closed；
- 新锚 currency = 本区 MarketTable.numeraire；若已有锚 currency 与当前表不一致 ⇒ fail-closed（不静默换锚）；
- 市场轮内不更新；本入口只应在 S7-END 被调用；
- `marketRoundId` 不得倒退（若已有 lastAdjusted > 传入 ⇒ fail-closed）；
- 不复制 `adaptiveNextPrice` 算式；直接调用既有唯一实现；
- `SATISFIED_ROUNDS_BEFORE_DECREASE` 是本责任区工程常量（建议 2），账本记录。

### 3.4 C7b 调用契约

C7b 必须在 S7-END 的运输观察完成之后，对每个 zone 调用：

```text
TransportPriceAnchorBook.recordMarketRound(
  sheet, view, zoneId, marketRoundId, D_zone, S_zone)
```

- 调用顺序：lane 分配/观察 → 锚价更新 → 清 C6 批次；
- C7b 不得自己写 anchor map 或复制 adaptive 公式；
- 本地 hex 未服务/未售量不得先抵消区级 D/S。

---

## 4. 不变量与失败语义

1. 每个 zone 最多一个 anchor；缺键 = 价 0。
2. 0 价合法，不失真。
3. D>S 才涨；D<=S 连续满足达阈值才降，最低 0。
4. 市场轮内不更新；marketRoundId 不倒退。
5. currency 与市场表一致；不一致 fail-closed。
6. 36→37 组件 ChangeSet/StateBuilder/Codec 同步。
7. 不新增第二套价格/市场权威。
8. 不改 C6 批次清零。
9. 日志只读，不改公式/状态。
10. 所有校验确定性。

失败：

- 锚价负/ streak 负 ⇒ 拒绝；
- 缺 Zone/市场表 ⇒ fail-closed；
- currency 漂移 ⇒ fail-closed；
- marketRoundId 倒退 ⇒ fail-closed；
- 组件 36→37 漏 ChangeSet/Codec ⇒ 编译/往返红；
- C7a 派生 demand/lane 或改 matcher ⇒ 越界。

---

## 5. 验收锚点（供 test owner，不在本任务运行）

- A-C7a-01：缺失锚价读 0，不创建状态。
- A-C7a-02：D>S ⇒ price 涨且 shortageStreak+1。
- A-C7a-03：D<=S 连续满足阈值后才降；达到阈值前不动。
- A-C7a-04：最低 0；0 价再供过于求仍 0。
- A-C7a-05：D=S=0 不涨价。
- A-C7a-06：currency 与市场表一致；漂移 fail-closed。
- A-C7a-07：marketRoundId 不倒退。
- A-C7a-08：36→37 组件往返/ChangeSet/StateBuilder。
- A-C7a-09：C7b 只读同一入口，不复制公式。
- A-C7a-10：本地 hex D/S 不互相抵消。

负例：

- 锚价缺失被当缺价不交易/不生产 ⇒ 红；
- 0 价被当缺价 ⇒ 红；
- D<=S 当轮立即降价 ⇒ 红；
- 两个 zone 共用一个锚 ⇒ 红；
- 锚价写进 MarketTable/商品价格 ⇒ 红；
- 组件漏 ChangeSet/Codec ⇒ 红。

---

## 6. 文件所有权

### 允许写

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/**
simos-economy/src/main/java/io/mosire/simos/economy/**
simos-app/src/main/java/io/mosire/simos/app/**
```

### 禁止写

```text
**/src/test/**
**/pom.xml
tools/**
test-world/**
主树 /home/cna/SimulatorMosire/.claude/worktrees 之外
docs/**（除 `.superpowers` 账本）
```

- 约 312 项前序未提交改动；只改本责任区 main 文件，不回滚/格式化。
- C7a 不改 matcher/订单/预算/运费/旧 haul；C7b 才做。
- 不新增测试/不跑测试。

---

## 7. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

只到 compile 绿；不跑 test / test-compile / verify / gate / 360；不 commit/push。

报告：改动文件、真实 rc/输出、anchor 实际类型/字段、定价入口、组件数影响、C7b 调用契约、
既有测试失效清单、未完成/blocker。

---

## 8. 非目标

- 不做 C7b 的 `TransportDemand` 派生、`LaneCapacityCommitment`、商品撮合约束、运费支付。
- 不做旧 haul/CARRIER_FEE/MerchantCapacityPool 退役。
- 不做 C8 马匹向上/混合拆分。
- 不做 C9 seed/读口/32hex。
- 不写测试/不跑测试/不 commit/push/不碰主树。
