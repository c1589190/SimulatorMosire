# S1：经济主体与生产关系（第四阶段第一轮）

**日期**：2026-09-26 · **状态**：设计稿（待用户审阅）
**上游**：`.superpowers/sdd/2026-09-26-year-one-simulation/issue-inventory.md`（问题清单 v2）
**范围**：把「生产活动」与「产权主体」解耦，并建立**显式**的生产关系结算规则

---

## 〇、这一轮在整条链上的位置

```
S1 经济主体与生产关系   ← 本文件（谁的产出、谁的资产、产出怎么分）
      ↓ 前置
S2 结算单位与支付能力    （货币 + 信用：买不起的表达）
      ↓ 前置
S3 同 Hex 市场          （订单、撮合、价格）
      ↓ 前置
S4 运输与跨 Hex 贸易     （路线、成本、在途、损耗、区域价差）
```

★ **依赖是真前置**：没有 S1 就不知道"谁有资格卖"，没有 S2 就不知道"谁买得起"，没有 S3 就没有可跨区搬运的东西。

★ **与 B1/B2 两个 bug 正交**：`applyDailyStress` 漏批次、`CrisisMonitor` 相位敏感**不属于本链任何一轮**，
可以在任何时刻先修，不影响本文件的设计。

---

## 一、为什么必须做（问题与根因）

### 1.1 诊断：一个无主的聚合

探针实测（`issue-inventory.md` §一）：

| 产业 | 人口 | 粮库存 | 布库存 |
|---|---|---|---|
| `farm` | 8,419,104 | 289.3B 毫 | 0 |
| **`weave`** | **0** | 0 | **62.1B 毫** |
| `craft` | 897,944 | 0 | 97M 毫 |

**织布的是 `farm` 行的人**（劳动配额跨行投给织机），但**产出落在 `weave` 行的四个阶层行上 —— 而它们的人口是 0**。

⇒ "62M 布是谁的？" 在当前模型里**没有答案**，因为 `weave` 行的 `ClassRow` 同时扮演了
「生产记录」与「产权容器」两个角色，而它**没有对应任何有经济意义的主体**。

### 1.2 更严格的说法

> **`ClassRow` 不应该因为承担某产业活动，就自动成为该活动产出的产权主体。**

它同时被用来回答四个**本该独立**的问题：谁生产？谁劳动？谁拥有？谁消费？
⇒ 四个问题被同一个 `(class, industry)` 键强行绑在一起。

★ **反例**（用户 2026-09-26 给出的制度清单）：自耕农家庭 / 地主庄园 / 雇佣作坊 / 合作社 / 徭役手工业 ——
这五种生产方式的**产权归属互不相同**。若把"劳动天然产生所有权"写成基础不变量，以后做工资劳动、
地租、利润、合作社、国企时都要反过来拆。

---

## 二、领域模型

### 2.1 四个概念的拆分（**不默认相同**）

```
LaborProvider        谁提供劳动          ← PeopleLot（批次）
ProductionOperator   谁组织这次生产      ← ActorRef（新）
AssetOwner           谁拥有土地/织机     ← ActorRef（经 AssetHolding，见 2.3）
InventoryOwner       = ProductionOperator（产出先落 operator）
```

★ **判据**：四者可以大量重合，但模型**不预设**它们必然相同。佃制（AssetOwner ≠ Operator）是第一个实例。

### 2.2 `ProductionActivity`（原 `Industry` 的降级）

`weave` / `farm` / `craft` **不再是 Actor 身份**，降级为**生产活动**：
它描述「用什么配方、多久一个周期、要多少投入、能出多少」，**不拥有任何东西**。

```
Industry { id, name, regime, cycleDays, capacityPerUnit, inputPerUnit,
           laborPerUnit, outputPerUnit, allocation, slots,
           operator: ActorRef }        // ★ 新增：本活动绑定哪个经营主体
```

### 2.3 `AssetHolding`（**独立概念**，不是 Actor 的字段）

```
AssetHolding { id, owner: ActorRef, location: HexCoord,
               assetKey: AssetClassKey, quantity }
```

★★ **为什么独立而不是 `ActorRow.assets`**（用户 2026-09-26 裁定原文）：
> 资产是 Actor **拥有的关系**，不是 Actor **本体的一部分**。以后卖掉 30% 土地，只改 `AssetHolding`，
> 不用打开整个 Actor aggregate。

★ **默认 fungible aggregate，例外才 Entity**（用户原文）：
- Hex 已提供空间边界 ⇒ 同 Hex 内的**同质**生产资料作**可分割余额**处理
- `AssetClassKey` **要能编码影响生产的同质性条件**，而不是只有 `LAND/LOOM/WORKSHOP` 三个粗类型：
  ```
  LAND(arable, quality=B)
  LOOM(handloom, tech=T1)
  WORKSHOP(textile, tech=T2)
  ```
- 只有**真正不可分割、需要独立历史**的（大型矿井、铁路枢纽、港口、特殊工厂）才实体化

★ 佃制因此成为两个**正交事实**：
```
AssetHolding    ESTATE_X owns 10000 LAND @ hex      ← 地主拥有土地
ProductionActivity operator = HOUSEHOLD_GROUP_Y     ← 佃农经营土地
```

### 2.4 `ProductionRelation`（**S1 的核心机制**）

★ **领域名**：`ProductionRelation`（不用"合同/Contract"）—— 它解决的不是民法意义上的任意合同，而是
> **一次生产完成以后，产出如何在 Operator、劳动提供者、资产所有者之间结算。**

```
ProductionRelation {
    id
    operator: ActorRef
    laborCompensationRules: [CompensationRule]     // 劳动的报酬
    assetCompensationRules: [CompensationRule]     // 资产的报酬（地租/机租）
    residualOwner: ActorRef                        // 剩余归谁
}

CompensationRule {
    recipient                          // 收方：ActorRef 或 cohort 引用
    commodity / money                  // 给什么
    basis                              // ★ 乘什么（见下）
    ratePerMille | fixedAmount         // 比例 或 定额
    priority                           // 结算次序
}
```

★★ **`basis` 必须显式**（用户原文）：
> 否则"30% 地租"到底是总收成 30%，还是扣了种子以后 30%，迟早出争议。

| `basis` | 含义 |
|---|---|
| `GROSS_OUTPUT` | 总产出 |
| `NET_AFTER_INPUTS` | 扣掉生产投入之后 |
| `OPERATOR_SURPLUS` | 经营剩余 |
| `LABOR_AMOUNT` | 按劳动量 |
| `ASSET_QUANTITY` | 按资产量 |

★★ **`regime` 只负责初始化，不负责运行时**（用户原文）：

```
regime  ──seed/default──▶  ProductionRelation  ──runtime truth──▶  settlement
```

⇒ 此后同一个 `feudal` 可以有"A 格地租 30% / B 格五五分成 / C 格领主直营"，
**制度可以逐渐变化而不用先改产业类型**。

★ **先别做脚本语言**：几个清晰的**规则类型**已经够用 ——

| 规则类型 | 语义 | 本轮启用 |
|---|---|---|
| `SELF_RETENTION` | 自留（产出全归 operator） | ✔ |
| `OUTPUT_SHARE` | 产出分成 | ✔ |
| `FIXED_IN_KIND_PER_LABOR` | 按劳动量的实物报酬 | ✔ |
| `FIXED_IN_KIND_RENT` | 固定实物租 | ✔ |
| `FIXED_MONEY_WAGE` | 货币工资 | **只定义字段，不结算** |
| `FIXED_MONEY_RENT` | 货币地租 | **只定义字段，不结算** |

★★ **S1 只真正执行「实物型」结算**（用户原文）—— 因为 S2 的钱与支付能力还没落地：
> 雇佣作坊的**货币工资**可以把类型和字段先定义出来，但**不要在没有 ledger 的情况下假装结算成功**。
> 这样 S1 不会顺手把 S2 也做半套。

### 2.5 `ConsumptionReceipt`（★ 流量，不是库存）

★★ **这是本文件最要紧的一处边界**（用户 2026-09-26 提出并裁定）：

```
WORKSHOP --实物工资 10 布--> farm 贫农 cohort
```
**这 10 布存在哪里？** —— **不能重新塞进 `ClassRow.goods`**，否则刚拆掉的产权/消费混合马上复活。

```
Actor inventory               = 存量 = 能保存、出售、转移 = 有产权
Cohort consumption receipt    = 流量 = 本周期收到多少最终消费品
                                     = 用于满足 need = **不代表持有库存**
```

```
ConsumptionReceipt { cohortKey, commodity, amount, source }

生产关系的实物报酬：  operator.inventory -= 10 cloth
                     cohort.receivedCloth += 10     ← 流量，不是库存
人口消费过程：        received ──▶ consumed / unmetNeed
```

★★ **统一入口**（用户原文："这个接口会非常值钱"）：

```
Contract distribution ─┐
Market purchase ───────┼──▶ ConsumptionReceipt ──▶ consumption
Government relief ─────┘
```

⇒ S3 的市场购买、将来的政府救济，**都走同一个入口**。

### 2.6 `ClassRow` 收窄

| 保留 | 移除 |
|---|---|
| 人口、阶层、年龄 | ~~库存~~（归 Actor） |
| 劳动供给 | ~~钱~~（S2 定） |
| 消费需求、消费满足 | ~~债~~（S2 定） |
| 生理压力、出生/死亡 | |
| 统计：该 cohort 向哪个活动提供了多少劳动 | |

★ 用户的边界要求：**`money`/`debts` 不要在 S1 固化进 Actor** —— 给 S2/ledger 留边界，
否则下一轮刚决定"钱应该属于 ledger"，又要把 Actor 拆一次。

### 2.7 ★ cohort 的身份基（**本 spec 唯一未决项，需裁定**）

**自审时发现的缺口**：§2.5 的 `ConsumptionReceipt.cohortKey` 要写类型，而**它现在没有干净的类型可写**。

**现状**：`ClassKey = (IndustryId, ClassSlot)` —— 例如 `farm@-36_-70 | peasant`。
也就是说，**"人"的身份里含"产业"**。

**但人并不住在产业里，人住在格里**（`PopulationGroup.residence` 是批次的属性）。
现在这层绑定只是**"居住地的代理"**：农村人落在 `farm` 行、城市人落在 `craft` 行 —— 恰好而已。

★ **这与 §1.2 是同一个病**：把「活动」与「主体」塞进同一个键。
`weave` 行的病是"活动持有产权"，`ClassKey` 的病是"身份绑定活动" —— **同构**。

**三个选项**：

| 选项 | cohort 身份 | 代价 |
|---|---|---|
| **A** | 保持 `ClassKey = (industry, slot)` | 零改动；但"人属于哪个产业"这个假事实继续留在身份里，且当 `industry` 降级为 `ProductionActivity` 后，这个键的语义**无处安放** |
| **B** | 改为 `(HexCoord, ClassSlot)` | 人 = "住在哪格、什么阶层" ✓ 语义正确；但 `ClassKey` 是 `ClassRow`/`FlowRow`/`Debt`/`LaborAllocationId` 的**键** ⇒ 波及面大 |
| **C** | 直接用 `PeopleLot`（批次） | 最精确；但主体数 ×8，且与 D1b「消费端用 cohort 聚合」的裁定冲突 |

★ **我倾向 B**，理由是它让 D1b 的裁定真正落地：
"买方是 cohort 聚合"要成立，cohort 必须先是一个**与产业无关的、有经济意义的单位** ——
否则"买方"仍然隐含"这个买方属于某个产业"，而买方本来不该有产业属性。

★ **但 B 是破坏性变更**，且它的波及面**我尚未清点**（§十 已如实记为未做）。
**如果你选 B，它应当作为 S1 的一部分，而不是留给后面**（否则 `ConsumptionReceipt` 要写两次）。

---

## 三、切片与依赖

```
simos-actor-api      ActorRef / ActorKind / AssetClassKey / ProductionRelation 的 ID 与契约
      ↑                      ↑
simos-actor           simos-economy
Actor 身份、经营主体、  配方、周期、产能、
资产余额、生产关系的    结算**计算**
运行时真相、goods account
      ↑______________________↑
              simos-app 协调器
     （一次日结算同时写两片 —— 与 R4 的 PopulationEconomyTimeParticipant 同款）
```

- `ActorRef` / `ActorKind` 从 `economy-api` **上移**到 `actor-api`（成为更底层的共用契约）
- ★ **跨切片原子性**：`TimeProposalResolver` 规定"同一次推进里两个参与者不得改同一模块" ⇒
  必须由 **app 里的一个协调器**同时持有 `actor` 与 `economy`（已有先例，不是新机制）
- ★ **Goods balance 的归属**：本轮可由 `simos-actor` 管，但**独立成 `GoodsAccount` 概念**，
  不要把库存塞进 Actor 本体 —— 与 `AssetHolding` 同理

---

## 四、一次生产的结算流程

```
① 周期结算算出产出            economy：Output = f(资产, 劳动, 配方)
② 产出先落 Operator           actor：operator.inventory += Output
③ 按 ProductionRelation 结算  actor：逐条 CompensationRule
      · 实物分成 → Actor→Actor 转移
      · 实物报酬 → Actor→Cohort 的 ConsumptionReceipt
      · 自留     → 不动
④ 剩余归 residualOwner        actor
⑤ cohort 消费                 social/economy：received ──▶ consumed / unmetNeed
```

★ **次序性**：`priority` 决定谁先拿 —— 地租先于分成、工资先于利润，是**数据**不是代码分支。

---

## 五、守恒式（两条，显式承认转移是中间过程）

用户 2026-09-26 裁定：**不要写成单一公式**。

```
生产侧（逐 Actor）：
  ΔActorGoods = Output − Input − TransfersOut + TransfersIn

全系统：
  ΔΣActorGoods + ΣFinalConsumption + ΣLoss = ΣOutput − ΣProductionInputs
```

★ **`Actor→Actor` 与 `Actor→Cohort` 的转移在系统总守恒里都抵消** ——
否则市场、地租、运输一加入，守恒式会越来越难读。
★ **`ΣFinalConsumption` 的来源是 `ConsumptionReceipt`**，不是 cohort 的库存变化。

---

## 六、初始化（`regime` → `ProductionRelation` 的默认推导）

| `regime` | 默认 operator | 默认规则 | 现实对应 |
|---|---|---|---|
| `feudal` | `ESTATE@hex` | 农奴实物给养（`FIXED_IN_KIND_PER_LABOR`）+ 自留 | 领主自营庄园 |
| `household` | `HOUSEHOLD@hex` | `SELF_RETENTION`（全留） | 家庭纺织 |
| `handicraft` | `WORKSHOP@hex` | 实物工资 + 自留 | 雇佣作坊（货币工资待 S2） |
| **租佃**（本轮的**新档**） | `HOUSEHOLD@hex`（佃农家户） | `FIXED_IN_KIND_RENT`（给地主）+ 自留 | 土地出租 |

★ **租佃档是本轮要新加的** —— 因为它正是"AssetOwner ≠ Operator"的载体，
没有它就证明不了 §2.1 那条判据。

---

## 七、可验证现象（落地后必须能读出来）

★ 用户 2026-09-26 要求："描述这些机制落地后应当产生什么可验证的模拟现象"。
每条都配**判据**，且判据要能**当场红**。

| # | 现象 | 判据 | 反例（改坏即红） |
|---|---|---|---|
| **V1** | **operator 不是标签** | 同一个 `regime` 配**不同 operator** ⇒ 模拟结果**必须不同** | 把 `operator` 字段忽略掉 ⇒ 两版结果逐值相同 ⇒ 红 |
| **V2** | **租佃 ≠ 庄园** | 同格同产能，佃制与庄园制的**分配结果不同**（佃农留 70/地主 30 vs 庄园主留 80/农奴 20） | 让两者共用一套规则 ⇒ 红 |
| **V3** | **织布的人拿到布** | `weave` 的产出按关系分给 `farm` 的 cohort 后，**农村布满足率 > 0** | 产出仍留在无人行 ⇒ 满足率仍 0 ⇒ 红 |
| **V4** | **`ConsumptionReceipt` 不是库存** | 收到布**不等于**持有布：cohort 侧**不出现 goods 字段**；消费后 receipt 归零 | 把 receipt 写回 `ClassRow.goods` ⇒ 结构断言红 |
| **V5** | **`basis` 有区别** | "总产出 30%" 与 "扣投入后 30%" 产生**不同**的实得数 | 两个 basis 走同一分支 ⇒ 红 |
| **V6** | **货币规则不假装结算** | `FIXED_MONEY_WAGE` 在 S1 **不产生任何实物/货币转移**（且明确报"待 S2"） | 让它静默按 0 结算 ⇒ 红 |
| **V7** | **两条守恒式逐值成立** | 生产侧与全系统两条式子，**逐周期**差值 == 0 | 转移只记一侧 ⇒ 红（R2/V3 栽过的"单侧扣减"） |
| **V8** | **M1 缺口收窄** | 落地后重跑 600 天：**农村布满足率显著 > 0**，`rural` 育龄女性压力**不再全部 > 阈值** | 若与现状逐值相同 ⇒ 什么都没发生 |

★ V8 是本轮的**目的性判据** —— 它是把 `issue-inventory.md` 的 M1（当前人口崩溃的主导近因）真正解决掉的证据。

---

## 八、对 S2/S3/S4 的接口预留（本轮**不实现**，只留口）

| 预留 | 位置 | 给谁 |
|---|---|---|
| `CompensationRule.commodity / money` 二选一 | 规则形状 | S2 的货币工资/地租 |
| `Actor.goods` 独立成 `GoodsAccount` | 切片内结构 | S3 的挂牌卖出 |
| `AssetHolding` 可转让（quantity 增减） | 已具备 | S3/S4 的资产交易 |
| `ConsumptionReceipt.source` | 已具备 | S3「市场购买」、将来的「政府救济」 |
| `ProductionRelation` 是**运行时数据** | 已具备 | 制度渐变（不用改 regime） |

---

## 九、不做的（明确边界）

1. **不做货币与信用**（S2）：`FIXED_MONEY_*` 只定义、不结算
2. **不做市场、价格、订单、撮合**（S3）—— 本轮产出**不通过市场**分配
3. **不做跨 Hex 贸易、运输、在途、损耗**（S4）
4. **不做资产市场**：`AssetHolding` 可转让是**结构预留**，本轮没有转让的驱动机制
5. **不做逐亩/逐机的实体化**（用户裁定：默认 fungible aggregate，例外才 Entity）
6. **不做契约 DSL / 脚本语言**（用户裁定：几个清晰的规则类型已经够用）
7. **不动 B1/B2 两个 bug** —— 它们与 S1 正交，另行安排

---

## 十、本文件的验证边界

- 本文件**未修改任何 Java、未跑 Maven**；§一 的现状事实出自 `issue-inventory.md`（其本身由探针世界实测）。
- **未做**：S1 的实现影响面评估（`ClassRow` 收窄会波及多少既有断言/读口/测试，**未清点**）。
- **未做**：§六 那四档默认规则的**参数值**（地租 30% / 农奴给养 20% 等）是**示例**，
  按 `design-creed.md` 信条十二属"判断结果"，应由 GM 可调参数决定，**本文件不预定**。
- **未决策**：`AssetClassKey` 的 `qualities` 具体编码哪些维度（本轮只需 `LAND(arable, quality=B)` 一个实例够用；
  多档 quality 是否影响产能，留待实现时按需要加）。
