# 增量 2 的 v1 状态形状：人口批次 · 产权 · 账本

**日期**：2026-09-25
**依据**：`POLITICAL_ECONOMY_DESIGN.md` §2 / §2.1 / §4.1 / §5 / §6.2 / §3（绑定权威）；本文件**只定 v1 的状态形状、
组件划分与可在构造期判的不变量**，不定任何公式（产量/价格/税率的算法留在实现时按设计稿 §12 的验收样例校准）。
**范围**：`social` 的 `PeopleLot`（增量 2 第一项）+ 新切片 `property` 与 `ledger` 的 v1。`production`/`market`/`government`
的 v1 形状另文（增量 3/4；本轮只保证它们能被接进来）。

## 〇、共同约定（全部沿用既有纪律）

- **ID 全部来自 `simos-economy-api`**（已落地：`PeopleLotId`/`AssetId`/`AssetRightId`/`AccountId`/`ClaimId`/`TransferId`
  + `ActorRef(ActorKind, id)`）。**不**在 `social`/`property`/`ledger` 里另造同义 ID。
- **五件套照 `social` 模板**：`*Data`（状态）/`*Snapshot`/`*ChangeSet`（逐组件 `FieldDelta`）/`*Codec`/Resolver + 逐组件往返不变式测试。
- **量纲与取整**（设计稿 §3）：人口 `long` 人；劳动**千分日**；土地**千分亩**；商品按**最小计量单位**（整数）；
  货币按**最小币值**（整数）；比率一律**千分数**（`…PerMille`，`[0,1000]`）。**禁 `double` 决定钱/粮/人**。
- **一物一主**：`property` 只写产权，`ledger` 只写库存与钱；生产投入（`invested`）不在这里存（设计稿 §4.1 三件事分离）。

## 一、`social` 的 `PeopleLot`（扩 `SocialData`，不新模块）

```java
record PeopleLot(
    PeopleLotId id,
    long count,                 // 人（整数）
    long ageDays,               // 年龄（天），不用 5 年桶
    Sex sex,                    // MALE / FEMALE
    HexCoord residence,         // 居住格（HexCoord，map 的类型）
    int laborCoefficientMilli,  // 0..1000：年龄/健康/技能的可用劳动系数
    Optional<PeopleLotId> splitFrom,  // 拆分来源（合并时记 sources）
    Optional<AccountId> accountId,    // ledger 账户（未接入账本时为空）
    long servingCount,          // 已服役人数（征兵后 >0；与 unit 的叶级编制对账）
    long committedLaborMilli)   // 当日已承诺劳动（千分日）——由合同写入，日落清零
```

- `SocialData` 增 `Map<PeopleLotId, PeopleLot> people`（+ 需求组件，见下）；`populations`/`cities` 仍是旧档/建城输入
  （设计稿 §2：激活经济后人口查询改从 `people` 求和，旧序列只作迁移来源）。
- **不变量（构造期）**：`count ≥ 0`、`ageDays ≥ 0`、`0 ≤ laborCoefficientMilli ≤ 1000`、
  `0 ≤ servingCount ≤ count`、`committedLaborMilli ≥ 0`；**人口守恒**由命令层保证（拆/合/出生/死亡各自成对），
  `apply(between(base,target),base)==target` 只保证结构往返。
- **自然需求（v1 只记，不影响生死）**：`Map<PeopleLotId, Needs>`，`Needs` 三档：
  生存品 / 社会再生产品 / 改善奢侈品，每档 `Map<CommodityId, Long> required`；
  缺口记录按设计稿 §6.1 四件：`required` / `可满足(自有库存)` / `购后满足` / `最终缺口`。
  **长期缺口对健康/出生/死亡的作用不在 v1**（设计稿 §6.1 明文）。
- 有效劳动：`availableLaborMilli = Σ(count × laborCoefficientMilli) − 服役折算 − committedLaborMilli`，
  **计算在查询层/服务层**，不落成状态字段（避免第二份真相）。

## 二、新切片 `property`（namespace `property`）

```java
record PropertyData(
    Map<AssetId, AssetLot> assets,
    Map<AssetRightId, AssetRight> rights)

enum AssetKind { LAND, CATTLE, TOOL, MACHINE }        // 设计稿 §4.1：土地/耕牛/农具/机器

record AssetLot(
    AssetId id,
    AssetKind kind,
    Optional<HexCoord> at,        // 土地必填；可搬动的牛/农具/机器可为空
    long areaMilliMu,             // 土地面积（千分亩）；非土地 = 0
    long headCount,               // 耕牛头数；其他 = 0
    int conditionPerMille)        // 完好度 0..1000（折旧由 production 提出、property 执行，设计稿 §4.3）

enum RightKind { OWNERSHIP, USE, TENANCY, MORTGAGE }

record AssetRight(
    AssetRightId id,
    AssetId asset,
    ActorRef holder,
    RightKind kind,
    int sharePerMille,            // 份额 0..1000（同 kind 同 asset 的份额之和 ≤ 1000）
    Optional<Long> untilDay,      // 期限（日）；长期 = empty
    Optional<Long> rentMilli)     // 约定租额（最小币值/日 的千分？——见 §五 待裁 ②）
```

- **不变量（构造期）**：`areaMilliMu ≥ 0`、`headCount ≥ 0`、`0 ≤ conditionPerMille ≤ 1000`、
  `0 ≤ sharePerMille ≤ 1000`；`sharedKind` 内部的份额和 ≤ 1000 由**命令层**校验（需要一个跨条目聚合，构造期只判单条）。
- **独占使用预留**（`invested`）**不在这里**：它是生产周期的事实，住 `production`（设计稿 §4.1 明文）。
  本切片对外只提供"某资产在某日的权利查询"（`owned`/`controlled` 由查询层按 `AssetRight` 聚合）。

## 三、新切片 `ledger`（namespace `ledger`）

```java
record LedgerData(
    EconomyMeta economyMeta,      // ★ 空 Optional = 未激活（设计稿 §2 的"未激活经济"语义）
    Map<AccountId, Account> accounts,
    Map<ClaimId, Claim> claims,
    Map<TransferId, Transfer> transfers)   // 双边转移凭据（append-only 流水）

record EconomyMeta(
    String mapId,
    long activatedDay,            // 激活日（创世 = 0）
    OptionalLong lastClosedDay,   // 最后关账日
    String ruleVersion,           // 规则版本（配方/税则的版本标签）
    Optional<String> migrationSource)      // 迁移来源（旧档坐标），直接创世为空

record Account(
    AccountId id,
    ActorRef owner,
    Map<CommodityId, Long> goods,      // 库存（最小计量单位）
    Map<CommodityId, Long> reserved,   // 已被订单/生产锁定的部分（≤ goods）
    long money,                        // 货币（最小币值）
    long reservedMoney)                // 已被锁定（≤ money）

enum ClaimKind { LOAN, WAGE, TAX, RENT, SEED_DEBT }   // 设计稿 §5/§6.2：贷款/欠薪/欠税/地租/留种欠

record Claim(
    ClaimId id,
    ClaimKind kind,
    ActorRef debtor,
    ActorRef creditor,
    Optional<CommodityId> commodity,   // 实物债；货币债 = empty
    long amount,
    long dueDay,
    OptionalLong settledDay)           // 已清偿日；未清偿 = empty

record Transfer(
    TransferId id,
    long day,
    ActorRef from,
    ActorRef to,
    Map<CommodityId, Long> goods,      // 商品腿（可为空 map）
    long money,                        // 货币腿（可为 0）
    Optional<ClaimId> settles)         // 若这笔转移是在清偿某债权
```

- **不变量（构造期）**：`goods.collectValues ≥ 0`、`reserved ≤ goods`（逐商品）、`money ≥ 0`、`reservedMoney ≤ money`、
  `amount ≥ 0`、`dueDay ≥ 0`；`economyMeta` 用 `Optional<EconomyMeta>` 承载"未激活"。
- **守恒**（设计稿 §6.2 明文，**由命令层/协调器校验，不落成状态**）：每一笔转移满足
  `买方扣款 = 卖方入账 + 政府税入账 + 运输方收入`、`卖方出货 = 买方/在途入货 + 明示损耗`；借款只搬现有的钱/粮
  并创建本金债权，**不凭空造现钞**。
- 国库 = 政府持有的账户（`Account.owner = ActorRef(GOVERNMENT, …)`），**不在 government 切片另存余额**（设计稿 §2）。

## 四、激活与创世（1b-4 的语义，随本 spec 定死）

- **未激活** = `ledger.economyMeta` 为空 `Optional`：日制世界可先沿用简化人口查询（`populations`/`cities`），
  但**日期与参数一律按天解释**。
- **激活** = app 编排的一次原子初始化批次：建人口批次、资产/权利、账户、政府/市场初态，并写 `economyMeta`
  （`activatedDay` = 当前日）；任一步失败 ⇒ 全部不落盘（一条 revision）。
- 新创世（`WorldgenInitializeTool`/`RichWorld`）写**六切片空快照**（`social` 的 `people` 为空 map、
  `property`/`ledger`/`production`/`market`/`government` 各空表）+ `economyMeta` 为空 ⇒ **空切片 ≠ 已激活**。
- **缺切片的日制档**：`TimeAdvance` 的 ④ 已经要求"有变更的模块必须在 base 里有快照"；再加一条 app 级门禁：
  已注册的六个经济 codec 中缺任一切片 ⇒ **拒绝日推进**并给出可读理由（设计稿 §2）。

## 五、待裁（需要你点头的三条，其余我已按设计稿定死）

1. **商品计量**：`CommodityId` 是否自带"最小计量单位"的元数据（例如一张商品表 `CommodityId → {名, 单位, 是否可分割}`）？
   设计稿只说"商品最小计量单位"是整数，没说单位表放哪。**默认**：v1 不建商品表，单位由场景参数在配方/需求里约定。
2. **租额量纲**：`AssetRight.rentMilli` 我暂定为"最小币值/日"，但设计稿 §5 的地租是**收获后从实物里扣**（实物地租）。
   **默认**：v1 只支持**实物地租**（写成 `Map<CommodityId, Long>`），货币租额留到市场那一步。
3. **`ActorKind` 四类够不够**：现在 `{PEOPLE_LOT, UNIT, GOVERNMENT, ORGANIZATION}`。军队要持有账户/资产，
   `UNIT` 够；`ORGANIZATION` 覆盖"单位生产的组织者"（厂主/地主）。**默认**：够，不再加。

## 六、v1 明确不做（免得越写越像设计稿）

- 任何公式：产量、消耗、价格、撮合、税率计算、执行覆盖的乘算。
- 家庭/生育/继承/抚养；健康与营养对劳动与死亡的作用（设计稿 §2.1 / §6.1 明文延后）。
- 跨 Hex 运输与在途账户（`market` 的地盘，增量 4）；银行信用创造（设计稿 §6.2 明文另写）。
- 旧小时档的离线迁移（用户裁定：无档要保）。
