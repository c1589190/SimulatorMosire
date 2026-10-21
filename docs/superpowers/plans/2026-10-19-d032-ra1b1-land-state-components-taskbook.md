# D-032 RA1b1 LAND 持久化组件 · 实现任务书（2026-10-19）

> **状态：可派单。** 前置 RA1a 已完成并编译绿；本单只把标量 LAND 的**状态组件**接进 economy 的完整状态/变更集/codec/构造守卫。
> **不做行为迁移**：旧世界仍走 `assetShares` 里的 LAND 行；本单新增两组件默认空，不改变任何现存结算结果。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；只动本树，不碰主树。

## 1. 父设计锚点与本单嵌入

- 父设计：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1（S1/S2/S7）、§4.1、§4.6、§5、§8、§10、§11。
- 复核收口：`docs/superpowers/reports/2026-10-19-d032-standard-agriculture-v2-review-closeout.md` §2.2 E6–E10。
- RA1a：`simos-economy-api/.../api/land/` 与 `simos-economy/.../land/LandBook.java` 已有标量值/纯账本。
- 本单任务书：把两件状态事实接进 `EconomyData`、`EconomyChangeSet`、Codec、StateBuilder、构造不变量与空状态；为 RA1b2 的 Seeder/旧链迁移提供可往返的持久化落点。

```text
父序列：S1 载入土地持有 → S2 组织/迁移 → S3/S4 生产使用 → S7 市场/债务读写
本单：新增 persisted landArableSupply + landHoldings 两组件；
      两组件默认空 = 旧世界未迁移形态；不接 Seeder、不改结算读写。
```

## 2. 冻结目标形状

### 2.1 状态组件

在 `EconomyData` 末尾追加（或紧邻 `assetShares`，但必须全仓一致）两个组件：

```text
LandArableSupply landArableSupply
Map<LandHoldingKey, LandHolding> landHoldings
```

- `LandArableSupply`：每 hex 固定可耕总量；空 map = 本世界尚未使用标量土地组件。
- `landHoldings`：键 = `(hex, owner)`，值 = 标量持有；空 map = 本世界尚未使用标量土地组件。
- **本单不删 `assetShares`**；它是旧链继续使用的通用资产表。`landArableSupply`/`landHoldings` 都为空时，旧世界行为逐值不变。
- 一旦新组件非空，构造不变量要求：
  1. `landArableSupply` 也非空；
  2. `landHoldings` 的每个 key == 值记录的 `key()`；
  3. 用 `LandBook.from(landArableSupply, landHoldings.values())` 验证每 hex 总量、未知 hex、非正数量等；
  4. `assetShares` 中不得还有 `AssetKind.LAND` 行（禁止双权威）。
- 两个新组件必须同批进 `EconomyChangeSet`；`apply(between(base,target),base).equals(target)` 必须能覆盖它们。

### 2.2 持久化 key 串

给 `LandHoldingKey` 增加互逆的规范串（只用于 JSON map key，不是块身份）：

```text
toString() = <hex.toString()> + "|" + <owner.value().length()> + "|" + <owner.value()>
parse(s)   // 严格解析上述三段的长度，owner 段长度必须逐值匹配
```

- 例：`0_0|8|hh-0_0-a`。
- 解析失败/空串/长度不符 → `IllegalArgumentException`；
- 键语义仍是 `(hex, owner)`，不得叫 block id / asset share id。

### 2.3 构造/空状态

- `EconomyData.empty()` / `EconomyDataTransforms.empty()` 必须产生两个新组件的空值，不产生 null；
- 所有 `new EconomyData(...)` 调用点必须同步传参（仅用当前值/空值带过，不改业务）；
- `EconomyStateBuilder` 必须能构造并带过两组件；
- `EconomyDataInvariants` 新增验证并使用 `LandBook.from` 做同源校验；
- `EconomyChangeSet` 新增两个 `FieldDelta` 组件，`between`/`apply`/`isEmpty`/构造默认值全部补齐；
- `EconomyCoreCodecs.keyModule()` 注册 `LandHoldingKey` 的 key deserializer（`LandHoldingKey::parse`）；`LandArableSupply`/`LandHolding` 的值线格式由现有 Jackson 绑定 + 现有 `HouseholdId`/`HexCoord` codec 负责。

## 3. 责任区六要素

### 3.1 问题现场

- `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:295-363`：34 个组件的完整状态，目前只有通用 `assetShares`，没有标量 LAND 组件；
- `EconomyData.java:467-520`：构造归一/守卫；
- `simos-economy/src/main/java/io/mosire/simos/economy/change/EconomyChangeSet.java:142,230,347,392`：组件、默认值、diff、rebuild；
- `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyStateBuilder.java:105,277,607`：builder 透传；
- `simos-economy/src/main/java/io/mosire/simos/economy/codec/EconomyCoreCodecs.java:131-238`：key module；
- 7 个 `new EconomyData(...)` 主代码调用点，见 `rg -l "new EconomyData\\(" --glob '*/src/main/java/**/*.java'`（RA1b1 时 = 6 文件 7 处）。

### 3.2 责任归属

你负责把**标量 LAND 状态形状**接进 economy 的持久化骨架，做到编译、往返结构完整、构造期 fail-closed、旧行为零改变。你不负责 Seeder 迁移、不负责债务/市场/生产语义切换。

### 3.3 最终能力

- 新世界状态可以携带 `landArableSupply` + `landHoldings` 且经 `EconomyData`/ChangeSet/Codec 往返；
- 两组件非空时，构造期拒绝：
  - 缺 arable 但已有 holdings；
  - 未知 hex、非正数量、重复/错位 key、每 hex 总量不等；
  - 同时保留 `assetShares` 的 LAND 行（双权威）。
- 两组件为空时，现有 34 组件语义逐值不变；
- 所有现有主代码构造器调用更新后仍编译。

### 3.4 不可破坏机制

- 五条铁律；所有状态必须能从完整状态派生 ChangeSet，并 `apply(between(base,target),base).equals(target)`。
- Alpha 不兼容旧档；但本单不删旧 LAND `assetShares`，只允许“新组件为空 ⇒ 旧链”过渡。不得写迁移/兼容位。
- 一个事实一个写口：一旦新组件非空，LAND 的 `assetShares` 行必须为零；在 RA1b1 内不允许双写。
- 不改 POM/enforcer/模块边界；不改测试；不改 App/Production。
- 确定性顺序、不可变视图、无浮点/Map.copyOf。

### 3.5 可动范围

允许修改/新建：

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/land/LandHoldingKey.java
simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java
simos-economy/src/main/java/io/mosire/simos/economy/EconomyDataInvariants.java
simos-economy/src/main/java/io/mosire/simos/economy/EconomyDataTransforms.java
simos-economy/src/main/java/io/mosire/simos/economy/change/EconomyChangeSet.java
simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyStateBuilder.java
simos-economy/src/main/java/io/mosire/simos/economy/codec/EconomyCoreCodecs.java
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyClearRegionHandler.java
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomySeedHandler.java
simos-economy/src/main/java/io/mosire/simos/economy/land/LandBook.java（仅编译需要的接口/可见性调整）
.superpowers/sdd/2026-10-19-d032-ra1b1-land-state-components/
```

若编译器证明还需要另一个 **simos-economy main** 文件，可在报告中说明并修改；**不得**改 `simos-app`/`simos-production`/测试/POM/tools/docs，除上述文件外不得碰。当前工作树有别人的未提交改动（尤其 `EconomySession.java`、`LaborSurvivalBook.java`、`ProductionInputDrawSettlement.java`、`SettlementTickChainLog.java` 与 app test/tools 文件）：不要接手、不要还原、不要格式化它们。

### 3.6 交账方式

`impl-ledger.md` 必须包含：

1. 本责任区在 S1/S2/S7 的嵌入点；
2. `file:line` 调查结论；
3. 两组件的数据流/构造/ChangeSet/Codec 说明；
4. 父序列影响表：顺序、预算/冻结、身份/持久化、失败/回滚、日志、测试；
5. 偏离记录；
6. 未做/未验证/blocker。

报告必须含：改动文件、真实编译 rc、数值行为清单（应为空）、旧测试可能失效项（组件数 34→36 等）、未完成项。

## 4. 不变量与负向用例（留给测试 owner）

1. 空 land 组件 ⇒ `EconomyData.empty()` 与旧行为等价，组件数 34→36。
2. `landArableSupply` 非空、`landHoldings` 空且某 hex arable >0 ⇒ 构造拒绝（总量不足）。
3. `landArableSupply` 为空、`landHoldings` 非空 ⇒ 构造拒绝。
4. `landHoldings` key 与值记录的 key 不一致 ⇒ 构造拒绝。
5. 每 hex 持有合计 != arable ⇒ 构造拒绝。
6. `landHoldings` 非空且 `assetShares` 含 `AssetKind.LAND` ⇒ 构造拒绝（双权威）。
7. `LandHoldingKey.parse(toString())` 往返；非法串拒绝。
8. ChangeSet roundtrip：base/target 都只有新组件变化时，`apply(between(base,target),base).equals(target)` 逐字段成立。
9. `EconomyData` Codec roundtrip 保留 `LandArableSupply` 与 `LandHolding` 映射（含 0 价无关字段/多 hex/多 owner）。
10. 同一输入构造两次逐值相等；组件顺序稳定。

## 5. 编译与纪律

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不改/不跑测试；不跑 test/verify；不 commit/push/merge/stash/checkout；不碰主树。
- 找不到 compile 所需最小改法 ⇒ BLOCKED，报告最小额外范围；不得擅自扩大范围或恢复旧兼容。
- 发现 v2/本任务书与用户原话/五条铁律冲突 ⇒ 立即停手报告，不得自裁。

## 6. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-19-d032-rural-production-land-labor-revision.md`，2026-10-19。

```text
5 是的，目前不做土地池，土地实际上只是一种无法生产、开局固定数量、和单个hex绑定的不动商品
我来说一下土地的数据结构吧，作为商品的土地记录在库存中，实际上写的是，什么hex的土地，例如一个家户有0,0的土地X亩，0,1的土地Y亩，在还债的时候如果排到土地，也是还具体hex的土地，实际可以通过罗列穷举推算出每一个hex固定的耕地量
共享收入这块完全不应该存在，生产关系影响收入在现在就是，在当前hex有土地的人按照土地多少获取对应耕地的粮食，没有土地的雇农只能获得雇佣者支付的钱或者债——这很不工业化思维，也无法直接体现租地，但是事实上租地种的环节已经被拆成两部分，首先是事实上的租地，没有地的家户可以在市场轮用债务换取土地，然后作为“小农”加入当地hex的生产，所以很合理，不需要额外增加环节
土地价为0就不能转移了吗？
你的意思是，买完地之后，下一轮生产规划里这个不作为可用于生产的条件？太逆天了
LAND在市场上不应该是一个标量的标准商品吗，想扣多少扣多少，为啥要引入块
P0更是逆天，地不是在具体的家户手上吗
```

## 7. 明确非目标

- 不改 Seeder/`LandOwnershipSeeder`/`EconomySeeder`/worldgen；
- 不迁移现有 LAND `assetShares` 到新组件；
- 不改债务清算、市场撮合、生产/劳动/需求/工资；
- 不改 GUI/工具/Social/Production；
- 不实现 `LandMarketIntent` 的撮合/预算/冻结；
- 不做旧档兼容；
- 不修改测试。
