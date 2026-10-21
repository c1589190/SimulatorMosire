# D-032 RA1a 标量 LAND 基础层 · 实现任务书（2026-10-19）

> **状态：可派单。** 这是 v2 迁移的第一个责任区，只做**可独立编译的标量土地值模型/纯账本层**；不接持久化、不改现有主链、不写测试。
> 控制方：本轮不写生产代码。实现方：一个新写代理独占本责任区。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。只动本树，不碰主树。

## 1. 父设计锚点

- 目标运行序列：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1（S1 经济载入、S2 组织重排、S7 债务/市场）、§4.1（LandHolding）、§4.6、§5、§8、§10、§11。
- 复核收口：`docs/superpowers/reports/2026-10-19-d032-standard-agriculture-v2-review-closeout.md` §2.2 E6–E10。
- 用户原话证据：`docs/superpowers/specs/2026-10-19-d032-rural-production-land-labor-revision.md` §一、§二 R-06～R-10、§四、§九（下一轮/太逆天）。
- 纪律：`AGENTS.md`、`docs/agents/00`–`15` 全部读完；加载 `.agents/skills/target-runtime-design/SKILL.md`。

**本责任区嵌入**：

```text
父序列：S1 载入土地持有 → S2 组织/迁移 → S3/S4 生产使用 → S7 市场/债务读写
本单只提供被这些阶段共用的标量土地核心值对象与纯账本；
本单不接线，不改变任何现有阶段行为。
```

## 2. 为什么先做这一层（已核实现场）

当前代码仍把 LAND 当“稳定块”：

- `simos-economy/src/main/java/io/mosire/simos/economy/model/OwnershipStake.java:138-170`：`landBlockIdOf(...)`，身份由 `(industry, LAND, location, sequence)` 生成；
- 同文件 `:212-215`：`idOf(...)` 对 `AssetKind.LAND` 强制走块 id；
- `simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayContext.java:163`：本轮新取得土地是 `LinkedHashSet<AssetShareId>`；
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketLandIntentBook.java:36-56,148-225,382-422`：土地意向、使用中判定、卖盘均按整块 `AssetShareId`；
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketLandOrder.java:28-...`：土地订单/成交是块产权份额变体。

这些与用户最新裁定 `(hex, quantityMilliMu)`、无 block id、任意数量相冲突。RA1a 先建立正确核心类型，RA1b 再把它们接进 `EconomyData`/ChangeSet/Codec/债务/市场/生产；禁止在 RA1a 顺手改现有主链。

## 3. 责任区六要素

### 3.1 问题现场

见 §2。已有代码事实以文件行号为准；实现 Agent 可复核，但不得把“当前块模型”当设计依据。

### 3.2 责任归属

你负责**标量 LAND 公共值契约 + 纯内存账本**这一整层，必须做到：

- 能无歧义表达一个家户在一个 hex 持有任意毫亩数量；
- 能对任意数量做同一 hex 内 owner→owner 转移；
- 能在每次构造/转移后守住“每 hex 持有总量 == 固定可耕量”；
- 能表达市场买卖意向，价格允许为 0，未定价不是本单职责；
- 不出现任何 LAND block id / `AssetShareId` / sequence / 整块限制；
- 编译通过并留下实现架构账本。

### 3.3 最终能力

给一个固定的每 hex 可耕总量和一组初始持有，调用方可以：

1. 以确定性顺序枚举全部持有；
2. 查询某户在某 hex 的数量、某 hex 的全部持有人与总量；
3. 施加一笔或多笔任意数量土地转移；
4. 得到新账本，且总量守恒、无负数、无未知 hex、无重复键、无溢出；
5. 构造 LAND 市场意向，数量为正、单价可为 0、不得为负；
6. 对非法转移/非法订单得到**具名失败原因**，不得静默修正。

### 3.4 不可破坏机制

- 五条铁律；本层不绕过 `Command → ChangeSet → Revision`，因为它尚未接线；RA1b 才接持久化。
- LAND 不进普通 `HouseholdGoods`；本层不 import actor 库存或无关经济类型。
- 无 block id、无 `AssetShareId`、无 `landBlockIdOf`、无 sequence；不允许“先把数量拆成固定块再转移”。
- 一 hex 固定可耕总量是独立输入/权威，不得从当前持有人数量反推。
- 整数、确定性、不可变；禁止 HashMap 迭代序、墙钟、随机 UUID、浮点。
- 0 价是合法**市场**价格；不得把 0 当成未定价。未定价语义不在本单，留给 RA1b/市场层。
- 本单不改模块依赖/POM/enforcer。

### 3.5 可动范围

**允许新建且只允许新建：**

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/land/
simos-economy/src/main/java/io/mosire/simos/economy/land/
.superpowers/sdd/2026-10-19-d032-ra1a-scalar-land/
```

**禁止修改任何已存在文件**，尤其：

- `OwnershipStake.java`、`SettlementDayContext.java`、`MarketLand*`、`EconomyData*`、`EconomyChangeSet`、Codec；
- `simos-app/**`、`simos-production/**`、任何 `src/test/**`、任何 POM/XML、`tools/**`、docs；
- 当前已脏文件（M7fix/SURVfix/P6a2/文档）一概不碰。

### 3.6 交账方式

在 `.superpowers/sdd/2026-10-19-d032-ra1a-scalar-land/impl-ledger.md` 写：

1. 本责任区在 S1/S2/S7 的嵌入点；
2. 关键调查结论（`file:line` → 结论 → 影响）；
3. 实际实现架构与数据流；
4. 对父序列影响表：顺序、预算/冻结、身份/持久化、失败/回滚、日志、测试；
5. 偏离记录；
6. 未做/未验证/blocker。

控制方最终接收报告必须含：改动文件、真实编译命令与 rc、会改变数值行为清单（本单应为空）、可能失效旧测试清单（本单应为空，因未接线）、未完成项。

## 4. 冻结的公共形状

以下名称/形状是 RA1b/RA2 的跨责任区契约，必须保留；内部实现类可另命名。

### 4.1 `LandHolding`

```text
record LandHolding(
    HouseholdId owner,
    HexCoord hex,
    long quantityMilliMu
)
```

- 三者非 null；`quantityMilliMu > 0`；
- 不包含 block/sequence/AssetShareId；
- 防御性值语义，不持有可变引用。

### 4.2 `LandHoldingKey`

```text
record LandHoldingKey(HexCoord hex, HouseholdId owner)
```

- 稳定排序：先 hex 自然序，再 `HouseholdId.value()`；
- 仅作 map 键/稳定枚举，不是“块身份”。

### 4.3 `LandTransfer`

```text
record LandTransfer(
    HexCoord hex,
    HouseholdId fromOwner,
    HouseholdId toOwner,
    long quantityMilliMu
)
```

- `fromOwner != toOwner`；`quantityMilliMu > 0`；
- 只允许同一 hex 内转移；不允许跨 hex 直接换地（跨 hex 是两笔独立交易/转移）。

### 4.4 `LandMarketSide` / `LandMarketIntent`

```text
enum LandMarketSide { BUY, SELL }

record LandMarketIntent(
    HouseholdId actor,
    HexCoord hex,
    LandMarketSide side,
    long quantityMilliMu,
    long unitPriceMilliPerMu
)
```

- `quantityMilliMu > 0`；`unitPriceMilliPerMu >= 0`；**0 合法**；
- 本类型不含 `AssetShareId`/block/冻结/预算；只表达意向。

### 4.5 `LandArableSupply`

```text
record LandArableSupply(Map<HexCoord, Long> arableMilliMuByHex)
```

- 防御拷贝为不可变、确定性视图；
- key/value 非 null；value `>= 0`；
- 是每 hex 固定可耕总量的唯一输入，不从持有表反推；
- 不要求覆盖地图上所有 hex；但任何持有/转移涉及的 hex 必须在表中。

### 4.6 纯账本（内部类名可自定）

推荐公共能力：

```text
LandBook.from(LandArableSupply, Collection<LandHolding>)
LandBook.holdings()                  // 稳定排序
LandBook.holdingOf(hex, owner)       // OptionalLong
LandBook.totalOfHex(hex)
LandBook.apply(LandTransfer)         // 返回新账本
LandBook.applyAll(Collection<LandTransfer>)
```

实现必须满足：

- 初始校验每 hex `Σ holding == arable`；
- 账本无 0 数量行（转移后为 0 删除该键）；
- 转移后新账本仍满足全部不变量；
- 单笔/批量转移顺序确定，失败不产生部分新账本；
- 所有加减乘用 `Math.*Exact`，溢出 → 具名失败。

### 4.7 具名失败

至少覆盖以下 reason（可使用异常类型/枚举实现，但报告要列出）：

```text
land-unknown-hex
land-negative-quantity
land-zero-quantity
land-same-owner
land-insufficient-holding
land-arable-mismatch
land-duplicate-holding
land-overflow
land-order-invalid
```

不得抛裸 `NullPointerException` 作为业务拒绝；参数 null 可用 `NullPointerException`/`IllegalArgumentException`，其余必须带 reason。

## 5. 验收判据（留给测试 owner；本单只实现）

1. 同一 hex 三个 owner 持有量合计等于 arable；三笔任意数量转移后总量逐字段守恒。
2. 把持有数量 1/3 转移给另一户；源户减、目标户增；不出现 block/sequence。
3. 跨 hex 直接转移拒绝；未知 hex 拒绝。
4. 超额转出拒绝，且原账本完全不变。
5. 转移量 0/负数拒绝；转给自己拒绝。
6. 初始持有缺失/重复/超过 arable 拒绝并具名。
7. `LandMarketIntent` 数量 0/负拒绝；单价 0 接受；单价负拒绝。
8. 同一输入两次构造/转移逐值相等；持有枚举顺序与 Map 输入序无关。
9. 0 数量行不保留；负数量在任何中间态都不出现。
10. 编译后全仓无新增 `AssetShareId`/`landBlockIdOf` 引用（本单新 API 内为 0）。

## 6. 纪律与命令

- 只写生产代码；**不写/不改/不跑测试**；不跑 `test`/`verify`。
- 编译门槛：
  ```bash
  cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
  tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy-api,simos-economy -am
  ```
  必须看到 rc=0；报告中贴真实 rc 与关键输出。
- 不 commit/push/merge/stash/checkout；不碰主树。
- 看到别人的半成品不接手；只改允许的新建范围。
- 若发现约束设计自相矛盾/与用户原话冲突：立即停手报告，不得自裁。
- 不要求逐文件施工图；实现架构由你决定，但公共形状与不变量不得改。

## 7. 用户原话附录（逐字，相关）

> 来源与日期：`docs/superpowers/specs/2026-10-19-d032-rural-production-land-labor-revision.md`，2026-10-19。

```text
5 是的，目前不做土地池，土地实际上只是一种无法生产、开局固定数量、和单个hex绑定的不动商品
我来说一下土地的数据结构吧，作为商品的土地记录在库存中，实际上写的是，什么hex的土地，例如一个家户有0,0的土地X亩，0,1的土地Y亩，在还债的时候如果排到土地，也是还具体hex的土地，实际可以通过罗列穷举推算出每一个hex固定的耕地量
共享收入这块完全不应该存在，生产关系影响收入在现在就是，在当前hex有土地的人按照土地多少获取对应耕地的粮食，没有土地的雇农只能获得雇佣者支付的钱或者债——这很不工业化思维，也无法直接体现租地，但是事实上租地种的环节已经被拆成两部分，首先是事实上的租地，没有地的家户可以在市场轮用债务换取土地，然后作为“小农”加入当地hex的生产，所以很合理，不需要额外增加环节
土地价为0就不能转移了吗？
你的意思是，买完地之后，下一轮生产规划里这个不作为可用于生产的条件？太逆天了
LAND在市场上不应该是一个标量的标准商品吗，想扣多少扣多少，为啥要引入块
P0更是逆天，地不是在具体的家户手上吗
```

## 8. 明确非目标

- 不接 `EconomyData`/ChangeSet/Codec/StateBuilder/Invariant；
- 不改 Seeder；
- 不改债务清算、市场撮合、生产/劳动/需求/工资；
- 不改 App/GUI/工具；
- 不做旧档兼容；
- 不实现“未定价 vs 0 价”持久语义（RA1b/市场层）；
- 不实现租约、抵押、Pledge、土地池、公共土地；
- 不实现跨 hex 运输/地价表。
