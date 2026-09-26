# S1：经济主体与生产关系（第四阶段第一轮）

**日期**：2026-09-26 · **状态**：设计稿 v2（按用户 2026-09-26 裁定修订，**主体方向已通过**）
**上游**：`.superpowers/sdd/2026-09-26-year-one-simulation/issue-inventory.md`（问题清单 v2）
**范围**：把「生产活动」从**产权**与**人口身份**两处解耦，并建立**显式**的生产关系结算规则

---

## 〇、这一轮在整条链上的位置

```
【前置】B1 修（压力与劳动配额无关）+ B2 修（实时危机指标同时间基准）
      ↓   ★ 不是逻辑依赖，是为了建立可信的 S1 验收基线（§十）
S1 经济主体与生产关系   ← 本文件
      ↓
S2 结算单位与支付能力（货币 + 信用）
      ↓
S3 同 Hex 市场
      ↓
S4 运输与跨 Hex 贸易
```

★ 依赖是真前置：没有 S1 就不知道"谁有资格卖"，没有 S2 就不知道"谁买得起"，
没有 S3 就没有可跨区搬运的东西。

---

## 一、为什么必须做

### 1.1 诊断：一个无主的聚合

探针实测（`issue-inventory.md` §一）：

| 产业 | 人口 | 粮库存 | 布库存 |
|---|---|---|---|
| `farm` | 8,419,104 | 289.3B 毫 | 0 |
| **`weave`** | **0** | 0 | **62.1B 毫** |
| `craft` | 897,944 | 0 | 97M 毫 |

**织布的是 `farm` 行的人**（劳动配额跨行投给织机），产出却落在 `weave` 行 —— 而它的人口是 0。
⇒ "62M 布是谁的？" 在当前模型里**没有答案**。

### 1.2 ★★ 两个同构的病

这是本文件的核心诊断，也是 S1 的**全部理由**：

```
病之一（产权）：
    ProductionActivity  ══▶  InventoryOwner
    （weave 因为是生产活动，就自动拥有了产出）

病之二（身份）：
    ProductionActivity  ══▶  PopulationIdentity
    （ClassKey = IndustryId + ClassSlot ⇒ 人的身份里含产业）
```

**★ 两者同构**：都是「**活动**」越界承担了「**主体**」的职责。
⇒ **一条裁定同时治两个病**：

> ## ★★ S1 核心裁定
> **`ProductionActivity` 既不定义商品产权，也不定义人口身份。**

### 1.3 为什么"把 `IndustryId` 换成 `HexCoord`"不够

现状 `ClassSlot`（`peasant`/`middle`/`rich`/`landlord`）是**各 `Industry` 自己声明的 slot**，
不是全局社会阶层枚举 ⇒ 现有 `ClassKey` 里的"贫农"**从来不是产业无关的人口身份**。

若只做 `(IndustryId, ClassSlot) → (HexCoord, ClassSlot)` 的机械替换，
**只是删掉了一个维度，没有回答**：

> 两个不同产业里都叫 `peasant` 的 slot，**究竟是不是同一个社会阶层**？

目前四个产业模板恰好长得一样，**不代表这个不变量已经成立**。
⇒ S1 必须**正式建立 `SocialClassId`**（§2.6）。

---

## 二、领域模型

### 2.1 四个概念的拆分（**不默认相同**）

```
LaborProvider        谁提供劳动          ← CohortKey（§2.6）
ProductionOperator   谁组织这次生产      ← ActorRef
AssetOwner           谁拥有土地/织机     ← ActorRef（经 AssetHolding）
InventoryOwner       = ProductionOperator（产出先落 operator）
```

★ **判据**：四者可以大量重合，但模型**不预设**它们必然相同。佃制（AssetOwner ≠ Operator）是第一个实例。

### 2.2 `ProductionActivity`（原 `Industry` 的降级）

`weave` / `farm` / `craft` **不再是 Actor、也不再是人口身份** ——
它只描述「在哪里、用什么配方、多久一个周期、要多少投入、能出多少」：

```
ProductionActivity { id, location: HexCoord, name, regime, cycleDays,
                     capacityPerUnit, inputPerUnit, laborPerUnit, outputPerUnit,
                     operator: ActorRef }      // ★ 绑定经营主体
```

### 2.3 `AssetHolding`（**独立概念**，不是 Actor 的字段）

```
AssetHolding { id, owner: ActorRef, location: HexCoord,
               assetKey: AssetClassKey, quantity }
```

★★ **为什么独立**（用户原文）：资产是 Actor **拥有的关系**，不是 Actor **本体的一部分** ——
卖掉 30% 土地只改 `AssetHolding`，不用打开整个 Actor aggregate。

★ **默认 fungible aggregate，例外才 Entity**：Hex 已提供空间边界 ⇒ 同 Hex 内**同质**生产资料作可分割余额。
`AssetClassKey` **编码影响生产的同质性条件**（而非只有 `LAND/LOOM/WORKSHOP` 三个粗类型）：
`LAND(arable, quality=B)` / `LOOM(handloom, tech=T1)`。
只有**不可分割、需独立历史**的（大矿井、铁路枢纽、港口、特殊工厂）才实体化。

★ 佃制因此是两个**正交事实**：
```
AssetHolding        ESTATE_X owns 10000 LAND @ hex
ProductionActivity  operator = HOUSEHOLD_GROUP_Y
```

### 2.4 `ProductionRelation`（S1 的核心机制）

★ 领域名**不用"合同/Contract"** —— 它解决的是：
> **一次生产完成以后，产出如何在 Operator、劳动提供者、资产所有者之间结算。**

```
ProductionRelation {
    id
    operator: ActorRef
    laborCompensationRules: [CompensationRule]
    assetCompensationRules: [CompensationRule]
    residualOwner: ActorRef
}

CompensationRule {
    recipient; commodity | money; basis; ratePerMille | fixedAmount; priority
}
```

★★ **`basis` 必须显式**：否则"30% 地租"是总收成的 30% 还是扣种子后的 30%，迟早出争议。

| `basis` | 含义 |
|---|---|
| `GROSS_OUTPUT` / `NET_AFTER_INPUTS` / `OPERATOR_SURPLUS` | 总产出 / 扣投入后 / 经营剩余 |
| `LABOR_AMOUNT` / `ASSET_QUANTITY` | 按劳动量 / 按资产量 |

★★ **`regime` 只负责初始化**：`regime ──seed──▶ ProductionRelation ──runtime truth──▶ settlement`
⇒ 同一个 `feudal` 可以有"A 格地租 30% / B 格五五分成 / C 格领主直营"，
**制度可以渐变而不用先改产业类型**。

★ **不做脚本语言**。规则类型：

| 类型 | 语义 | 本轮 |
|---|---|---|
| `SELF_RETENTION` / `OUTPUT_SHARE` | 自留 / 产出分成 | ✔ |
| `FIXED_IN_KIND_PER_LABOR` / `FIXED_IN_KIND_RENT` | 按劳动的实物 / 固定实物租 | ✔ |
| `FIXED_MONEY_WAGE` / `FIXED_MONEY_RENT` | 货币工资 / 货币地租 | **只定义字段，不结算** |

★★ **S1 只执行实物型结算** —— 不在没有 ledger 的情况下假装货币结算成功，否则 S1 会顺手把 S2 做半套。

### 2.5 `ConsumptionReceipt`（★ 流量，不是库存）

```
Actor inventory            = 存量 = 能保存、出售、转移 = 有产权
Cohort consumption receipt = 流量 = 本结算窗口内**可用于最终消费**的流入
                                    ≠ cohort 拥有库存
```

```
ConsumptionReceipt { cohortKey, commodity, amount, source }
生产关系的实物报酬：  operator.inventory -= X
                     cohort.received += X        ← 流量
人口消费过程：        received ──▶ consumed / unmetNeed
```

★★ **统一入口**（"这个接口会非常值钱"）：
```
Contract distribution ─┐
Market purchase ───────┼──▶ ConsumptionReceipt ──▶ consumption
Government relief ─────┘
```

★★ **超额规则（用户 2026-09-26 裁定，必须写死）**：

> **Cohort 只能接受 final-consumption flow；任何需要跨周期保存、出售或再次转移的商品都必须属于 Actor。**

落地为一条明确规则：

```
receipt 生成上限 = min( 该 cohort 本周期应得量 , 该 cohort 本周期该商品的最终消费需求 )
超出部分：留在 operator.inventory（不产生 receipt）
```

★ **S1 阶段，超出部分不产生任何 cohort 侧的索取权**（cohort 没有产权能力）。
⇒ **实物报酬在 S1 是"消费型报酬"，不是"积累型报酬"**。
想让劳动者积累，走 S3 的"分成 + 自卖"或 S2 的货币工资 —— **本文件不假装它能积累**。

★ **为什么这条必须写死**：否则"收到 10、消费 5、剩 5 凭空消失"会在守恒式上留一个暗漏；
而"把 5 记回 `ClassRow.goods`"会让刚拆掉的产权/消费混合**当场复活**。

### 2.6 ★★ `CohortKey`（B′，用户 2026-09-26 裁定）

```
CohortKey {
    residence: ResidenceKey      // 第一版 = HexCoord
    stratum:   SocialClassId     // ★ 全局社会阶层，**产业无关**
}
```

★ **`SocialClassId` 是本轮**新建**的**全局**人口身份**，不再由各 `Industry` 自己声明：
```
SocialClassId ∈ { poor_peasant, middle_peasant, rich_peasant, landlord, ... }
```
★ **迁移**：现有各产业的 `ClassSlot` 按语义**映射**到 `SocialClassId`（目前四个模板同构 ⇒ 一对一）；
**这个不变量从"恰好成立"变成"显式声明"**。

★ **劳动参与另建关系**（人与活动之间是**关系**，不是身份）：
```
LaborAllocation { cohort: CohortKey, activity: ProductionActivityId, laborMilli, ... }
```
⇒ 一个 cohort 可以天然地：
```
poor_peasant @ hex A
    ├── 70% labor → farm
    ├── 25% labor → weave
    └──  5% labor → other
```
**人的身份不再因为今天去织布就变成 `weave|peasant`。**

★ **`PeopleLot`（批次）保持人口动力学内部粒度**（年龄/性别/出生/死亡/迁移），
**不成为经济买方** —— 否则"人口内部生理差异"会被错误升级成"独立经济决策主体"，
除数量膨胀外还会把领域层次搞乱。

★ **不留 A 过渡接口**：兼容层只允许出现在 **migration / seeder / codec 边缘**，
**新领域模型不认识旧 `ClassKey`**。

### 2.7 三个正交坐标系（S1 的最终形状）

```
人是谁：              CohortKey(residence, socialClass)
在哪里生产什么：       ProductionActivity(activityId, location, recipe)
谁组织生产、谁拥有：   ActorRef / ProductionRelation / AssetHolding
```

关系把它们接起来：
```
Cohort ──provides labor──▶ ProductionActivity ──operated by──▶ Actor
Asset  ──owned by────────────────────────────────────────────▶ Actor
```

★ 于是以前那个诡异的
```
farm | poor_peasant      weave | poor_peasant      craft | poor_peasant
```
**不再表示三个"人"**，而变成 `poor_peasant @ hex17` —— **这一群人可以同时参与三个 activity**。

---

## 三、切片与依赖

```
simos-actor-api      ActorRef / ActorKind / AssetClassKey / CohortKey / ProductionRelation 的 ID 与契约
      ↑                      ↑
simos-actor           simos-economy
Actor 身份、经营主体、  配方、周期、产能、结算**计算**
AssetHolding、
ProductionRelation、
GoodsAccount
      ↑______________________↑
              simos-app 协调器（同时写两片，与 R4 的 PopulationEconomyTimeParticipant 同款）
```

★★ **S1 的 `simos-actor` 只承担**（用户裁定）：
```
Actor identity · AssetHolding · Goods ownership/inventory · ProductionRelation
```

★★ **S1 不吞 Money / Debt**（用户裁定）：
- **Debt 不是 Actor 的内部属性** —— 债权天然涉及 `debtor / creditor / principal / terms`，
  它是**跨主体关系**；Money/credit 又正是 S2 的领域
- ⇒ **禁止**写 `ActorRow { Money money; List<Debt> debts; }`，否则 S2 第一件事就是拆 S1
- 现有 V6 的 `EconomyData.debts` 与 `ClassRow.debts`（引用）**S1 一行不动**，
  明确标注 **legacy bridge，S2 迁走**，不作为 `simos-actor` 的最终领域归属

★ `ActorRef` / `ActorKind` 从 `economy-api` **上移**到 `actor-api`（更底层的共用契约）。
★ **跨切片原子性**：`TimeProposalResolver` 规定"同一次推进里两个参与者不得改同一模块" ⇒
必须由 **app 里的一个协调器**同时持有 `actor` 与 `economy`（已有先例，不是新机制）。
★ **Goods balance 独立成 `GoodsAccount` 概念**，不塞进 Actor 本体（与 `AssetHolding` 同理）。

---

## 四、一次生产的结算流程

```
① 周期结算算出产出            economy：Output = f(资产, 劳动, 配方)
② 产出先落 Operator           actor：operator.inventory += Output
③ 按 ProductionRelation 结算  actor：逐条 CompensationRule（按 priority 次序）
      · 实物分成 → Actor→Actor 转移
      · 实物报酬 → Actor→Cohort 的 ConsumptionReceipt（**受 §2.5 超额规则封顶**）
      · 自留     → 不动
④ 剩余归 residualOwner        actor
⑤ cohort 消费                 received ──▶ consumed / unmetNeed
```

---

## 五、守恒式（两条，显式承认转移是中间过程）

```
生产侧（逐 Actor）：
  ΔActorGoods = Output − Input − TransfersOut + TransfersIn

全系统：
  ΔΣActorGoods + ΣFinalConsumption + ΣLoss = ΣOutput − ΣProductionInputs
```

★ `Actor→Actor` 与 `Actor→Cohort` 的转移在**系统总守恒里都抵消** ——
否则市场、地租、运输一加入，守恒式会越来越难读。
★ `ΣFinalConsumption` 的来源是 `ConsumptionReceipt`，**不是 cohort 的库存变化**。
★ §2.5 的超额规则保证了这里**没有暗漏**：未生成 receipt 的部分仍在 `ΔΣActorGoods` 里。

---

## 六、初始化（`regime` → `ProductionRelation` 的默认推导）

| `regime` | 默认 operator | 默认规则 | 现实对应 |
|---|---|---|---|
| `feudal` | `ESTATE@hex` | 实物给养（`FIXED_IN_KIND_PER_LABOR`）+ 自留 | 领主自营庄园 |
| `household` | `HOUSEHOLD@hex` | `SELF_RETENTION` | 家庭纺织 |
| `handicraft` | `WORKSHOP@hex` | 实物工资 + 自留 | 雇佣作坊（货币工资待 S2） |
| **租佃**（本轮**新档**） | `HOUSEHOLD@hex`（佃农家户） | `FIXED_IN_KIND_RENT`（给地主）+ 自留 | 土地出租 |

★ **租佃档是本轮要新加的** —— 它是"AssetOwner ≠ Operator"的**唯一载体**，没有它就证明不了 §2.1 那条判据。

---

## 七、可验证现象

| # | 现象 | 判据（改坏即红） |
|---|---|---|
| **V1** | **operator 不是标签** | 同 `regime` 配**不同 operator** ⇒ 结果**必须不同** |
| **V2** | **租佃 ≠ 庄园**（`same regime + different relation → different result`，用户要求**进验收测试**） | 佃制与庄园制分配结果不同 |
| **V3** | **织布的人拿到布** | 农村布满足率 **> 0** |
| **V4** | **`ConsumptionReceipt` 不是库存** | cohort 侧**无 goods 字段**；把 receipt 写回 `ClassRow.goods` ⇒ 结构断言红 |
| **V5** | **`basis` 有区别** | "总产出 30%" 与 "扣投入后 30%" 实得数不同 |
| **V6** | **货币规则不假装结算** | `FIXED_MONEY_*` 在 S1 不产生任何转移，且明确报"待 S2" |
| **V7** | **两条守恒式逐值成立** | 逐周期差值 == 0；转移只记一侧 ⇒ 红 |
| **V8** | **不做隐式 cohort 库存**（用户要求**再确认**） | 应得 10 / 需求 5 ⇒ receipt=5、operator 留 5；**总账无 5 的暗漏** |
| **V9** | **身份与活动解耦** | 同一 cohort 同时参与两个 activity ⇒ **只有一个 cohort 身份**；旧 `ClassKey` 不再出现在新模型中 |
| **V10** | **M1 缺口收窄**（目的性判据） | 重跑 600 天：农村布满足率显著 > 0，`rural` 育龄压力不再全部 > 阈值 |

---

## 八、对 S2/S3/S4 的接口预留（本轮**不实现**）

| 预留 | 给谁 |
|---|---|
| `CompensationRule` 的 `commodity \| money` 二选一 | S2 货币工资/地租 |
| `GoodsAccount` 独立 | S3 挂牌卖出 |
| `AssetHolding` 可转让（quantity 增减） | S3/S4 资产交易 |
| `ConsumptionReceipt.source` | S3「市场购买」、将来「政府救济」 |
| `ProductionRelation` 是**运行时数据** | 制度渐变（不改 regime） |
| **`Debt` 保持独立表**（S1 不动） | S2 迁往 ledger |

---

## 九、不做的（明确边界）

1. **不做货币与信用**（S2）；`FIXED_MONEY_*` 只定义不结算
2. **不做市场、价格、订单、撮合**（S3）
3. **不做跨 Hex 贸易、运输、在途、损耗**（S4）
4. **不做资产市场**（可转让是结构预留，本轮无驱动机制）
5. **不做逐亩/逐机实体化**
6. **不做契约 DSL**
7. **不把 Money/Debt 归入 actor 上下文**（用户裁定）
8. **`PeopleLot` 不成为经济买方**
9. **不动 B1/B2** —— 它们是**前置**，另行两个独立小提交（§十）

---

## 十、落地顺序（用户 2026-09-26 裁定）

```
B1 regression + fix      ← "所有存在人口的 cohort 都参与生理压力计算，与其有无 LaborAllocation 无关"
      ↓
B2 regression + fix      ← "实时危机指标使用一致时间基准"
      ↓
最小 population/crisis probe（**不重做巨大 v3 报表**）
      ↓
S1：CohortKey B′ + Actor/property 重构
      ↓
S1 acceptance / 守恒 / counterfactual
      ↓
S2
```

★★ **为什么 B1/B2 前置**（用户原文，理由不是逻辑依赖）：
> 是为了**建立可信的 S1 验收基线**。尤其 B1 与 S1 的 cohort 边界**代码接触面已经不正交**：
> 旧 B1 是"stress 从 `LaborAllocation` 推断有哪些 population rows"，而 S1 B′ 之后
> **population cohort 本来就不再依赖 `ProductionActivity`**。

⇒ **先用 regression test 钉死那条不变量，再开始 S1**。这样 S1 重构后测试继续绿，
就证明**没有把人口生命过程重新绑回劳动关系**。
★ B2 同理，且 S1 验收时要依赖危机读口 —— 不修则又遇到"是模型错还是采样错"。

---

## 十一、本文件的验证边界

- 本文件**未修改任何 Java、未跑 Maven**；§一 的现状事实出自 `issue-inventory.md`（由探针世界实测）。
- **未做**：S1 的实现影响面评估 —— `ClassKey → CohortKey` 是**破坏性变更**，
  波及 `ClassRow` / `FlowRow` / `Debt` / `LaborAllocationId` 等既有键，**波及面未清点**。
- **未做**：`ClassSlot → SocialClassId` 的**映射表**（四个模板目前同构，但映射本身要显式写出并加断言）。
- **未做**：§六 那四档默认规则的**参数值**（地租 30% / 给养 20% 等是示例），
  按 `design-creed.md` 信条十二属"判断结果"，应由 GM 可调参数决定，**本文件不预定**。
- **未决策**：`AssetClassKey.qualities` 具体编码哪些维度（本轮只需 `LAND(arable, quality=B)` 一个实例）。
