# S1 阶段划分与开发计划

> **For agentic workers:** 本文件是 **S1 的分解与路线**，不是任务级计划。
> S1 的每个阶段**在执行前另写一份任务级计划**（`docs/superpowers/plans/2026-09-26-s1-stageN-*.md`），
> 格式按 `superpowers:writing-plans`。**理由见 §二**。

**Spec:** `docs/superpowers/specs/2026-09-26-s1-actor-property-design.md`（v2，已通过）
**前置:** `docs/superpowers/plans/2026-09-26-b1b2-preflight-fixes.md`（B1 → B2 → 最小 probe）

---

## 一、为什么必须分阶段

清点结果（2026-09-26）：

| 项 | 数 |
|---|---|
| `ClassKey` 引用 | **259 处 / 36 个文件**（main 18 · test 18） |
| 分布 | `simos-economy` 24 · `simos-app` 11 · `simos-economy-api` 1 |
| `ClassSlot` 的性质 | 类注原文："一个产业内**该制度允许的一个阶层**"，`id`"**同一产业内唯一**" |

⇒ `ClassKey → CohortKey` 是**破坏性重构**，且 `ClassRow` 还要同时**收窄**（移出库存）。
把这两件事和"新建 actor 切片""产出改归属""引入 `ProductionRelation`"塞进一次改动，
**中间态不可测、失败不可定位** —— 违反"每个任务产出可独立测试的交付物"。

★ 另一个硬理由：**生产关系的权威数据来自用户资料**（`POLITICAL_ECONOMY_DESIGN.md` 与 `design-creed.md`），
而 §六 那四档默认规则的**参数值**属"判断结果"（信条十二）⇒ 阶段 5/6 的参数要**按真档观察后再定**，
不能在第一阶段就写死。**先做能确定的，把要看的留到看得见的时候。**

---

## 二、为什么每阶段另写任务级计划（而不是现在一次写全）

1. **阶段 N 的具体代码取决于阶段 N−1 的实际产物** —— 例如阶段 3 的 `operator` 字段形状，
   取决于阶段 2 建出来的 `ActorRef` 落在哪个包、`AssetHolding` 怎么索引。
   现在写死的签名，到阶段 3 大概率要改，**改了就是计划与代码两处漂**。
2. **技能的要求**：`writing-plans` 明写"每个计划应独立产出可工作、可测试的软件" ——
   7 个阶段 = 7 份计划，每份自己闭环。
3. **用户的指令**：本轮"不要直接给具体代码实现方案" ⇒ 现阶段给**结构与判据**，代码留到该阶段开工前。

★ **但每个阶段的"判据"现在就定死** —— 判据是**不变量**，不该随实现漂。

---

## 三、七个阶段

```
阶段 1  身份：SocialClassId + CohortKey          破坏性 · 机械 · 可逐值验证
   ↓
阶段 2  切片：simos-actor（身份与资产）          增量 · 不动 economy · 既有测试须全绿
   ↓
阶段 3  绑定：ProductionActivity.operator        增量 · 从 regime 推导
   ↓
阶段 4  归属：产出落 operator                    核心切换 · 守恒式重画
   ↓
阶段 5  结算：ProductionRelation                 核心机制 · 制度可分辨
   ↓
阶段 6  消费：ConsumptionReceipt                 消费改道 · 拆掉最后一条回退路径
   ↓
阶段 7  收窄：ClassRow 移出 goods/money/debts    收尾 · 结构断言钉死
```

### 阶段 1 —— 身份：`SocialClassId` + `CohortKey`

> ★★ **2026-09-26 修正（调查发现，已收窄本阶段）**：原文写的"`ClassKey → CohortKey`（去 IndustryId）"
> **不能独立做** —— 去掉 industry 后 `weave@hex|*` 这四个行就不存在了，而它们此刻正是**布的唯一落点**
> （探针实测 62.1B 毫、占全境 99.8%）。⇒ "键去掉 industry"与"产出从 `ClassRow` 挪到 Actor"（阶段 4）
> **是同一件事的两面**，拆开会在中间态**丢掉产出该记在哪**。
> **⇒ 阶段 1 收窄为「只把阶层身份全局化」**（`ClassSlotId → SocialClassId`），**键的形状原样不动**；
> `ClassKey → CohortKey` **并入阶段 4**，与产出归属一起做。
> 详细计划见 `docs/superpowers/plans/2026-09-26-s1-stage1-identity.md`。
>
> ★ 补充事实（调查得到）：四模板当前同构**不是"恰好"**，而是三处都循环**同一对全局数组**
> （`CLASS_IDS` / `CLASS_LABOR_PER_MILLE`）—— 但那仍是**填充习惯**、不是**类型约束**，本阶段正是要把后者补上。

**目标**：人的身份**不含产业**，且社会阶层成为**显式声明**的全局词表。

**关键改动面**：
- 新建 `SocialClassId`（全局词表：`poor_peasant` / `middle_peasant` / `rich_peasant` / `landlord` / …）
- `ClassSlot → SocialClassId` 的**显式映射表**（四个模板目前同构 ⇒ 一对一，但**映射要写出来并加断言**）
- `ClassKey` → `CohortKey(residence, socialClass)`
- `LaborAllocation` 改用 `CohortKey`
- 兼容层**只在 codec / migration / seeder 边缘**；**新领域模型不认识旧 `ClassKey`**

**判据（不变量）**：
- **I1.1** 迁移前后：逐格人口、逐格劳动供给、逐格劳动配额 **逐值相同**
- **I1.2** 同一 cohort 同时参与两个 activity ⇒ **只有一个 cohort 身份**（V9）
- **I1.3** 旧 `ClassKey` 不出现在任何新领域 API 的签名里（结构性断言：源扫描）
- **I1.4** 旧档仍能读（codec 边缘的兼容层生效）

**为什么它排第一**：它是**纯身份重构、零经济语义变化** ⇒ 可以**逐值验证**"什么都没变"。
后面每个阶段都建立在"人的身份是干净的"之上。

### 阶段 2 —— 切片：`simos-actor`（身份与资产）

**目标**：Actor 的存在与产权**有地方住**，但**还不接管任何现有职责**。

**关键改动面**：
- 新建 `simos-actor-api`（`ActorRef` / `ActorKind` / `AssetClassKey` **从 `economy-api` 上移**）
- 新建 `simos-actor`（`Actor` 身份、`AssetHolding`、`GoodsAccount`）
- 装配进 `Shell`；**`economy` 一行不改**
- ★ **不碰 money/debts**（S1 的边界，用户裁定）

**判据**：
- **I2.1** 既有测试**全绿**（本阶段是纯增量）
- **I2.2** `AssetHolding` 按 `(owner, hex, assetClass)` 聚合：佃制能表达成
  "地主持 `LAND` @ hex + 另一个 actor 是 operator"（**两个正交事实**，V-§2.3）
- **I2.3** 上移后 `economy-api` 不再拥有 `ActorRef`（依赖方向单向）

### 阶段 3 —— 绑定：`ProductionActivity.operator`

**目标**：每个生产活动**显式绑定**一个经营主体；`regime` 只做初始化推导。

**关键改动面**：
- `Industry` 加 `operator: ActorRef`
- `regime → 默认 operator` 的推导（§六 四档，含**新加的租佃档**）
- 读口暴露 operator

**判据**：
- **I3.1** **operator 不是标签**（V1）：同 `regime` 配不同 operator ⇒ 结果**必须不同**
  ★ 本阶段只绑不定，故 V1 到阶段 5 才真正可测 —— 这里先钉"**读得出、写得进、不丢失**"
- **I3.2** 租佃档存在且可表达"AssetOwner ≠ Operator"
- **I3.3** 旧档升级：缺 `operator` 的产业按 regime 补默认值（codec 边缘）

### 阶段 4 —— 归属：产出落 operator

**目标**：`ProductionActivity` **不再拥有产出**；产出落 `operator.inventory`。

**关键改动面**：
- 产出的记账目标从 `ClassRow.goods` 改为 `Actor.inventory`（经 `GoodsAccount`）
- **守恒式重画**（spec §五 两条）
- 这一步**同时要有一个"过渡分配"**把实物送到 cohort（否则 cohort 断粮 —— 见下）

★ **本阶段的风险最高**：产出一旦离开 `ClassRow`，而 `ConsumptionReceipt`（阶段 6）还没做，
cohort 就没有消费来源。**⇒ 阶段 4 必须与阶段 5 的"实物型结算"合并，或阶段 4 内自带最小分配。**

**判据**：
- **I4.1** 生产侧守恒逐值成立：`ΔActorGoods == Output − Input − TransfersOut + TransfersIn`
- **I4.2** 全系统守恒逐值成立：`ΔΣActorGoods + ΣFinalConsumption + ΣLoss == ΣOutput − ΣProductionInputs`
- **I4.3** `weave` 不再作为产权主体持有布（V-§1.2）

### 阶段 5 —— 结算：`ProductionRelation`

**目标**：产出**按显式关系**在 Operator / 劳动者 / 资产所有者之间结算；`basis` 有区别。

**关键改动面**：规则类型（`SELF_RETENTION` / `OUTPUT_SHARE` / `FIXED_IN_KIND_*`）、
`basis` 五种、`priority` 次序；货币规则**只定义不结算**。

**判据**：
- **I5.1** **V2**：同格同产能，佃制与庄园制分配结果**不同**（`same regime + different relation → different result` 进验收测试）
- **I5.2** **V5**：`GROSS_OUTPUT 30%` 与 `NET_AFTER_INPUTS 30%` 实得数**不同**
- **I5.3** **V6**：`FIXED_MONEY_*` 不产生任何转移，且明确报"待 S2"
- **I5.4** `priority` 决定次序（地租先于分成那类）是**数据**不是分支

### 阶段 6 —— 消费：`ConsumptionReceipt`

> ★★★ **取代声明（2026-09-27 追加，本节原文一字不改）**：**阶段 6 作为一个独立阶段已被取消**，
> 与**阶段 7 合并成一次改动**。裁定与理由见
> `docs/superpowers/reviews/2026-09-27-review-response-transfer-layer.md` §十二（裁定 **S2**）与 §十一。
>
> **取代后的形状**：家户 actor（`(格, 居住类型, 阶层)`，`ActorKind` 复用 `HOUSEHOLD`）**直接持有**商品
> ⇒ **消费 = 家户库存减少**，不引入独立 `ConsumptionReceipt` 实体，`receipt = min(应得, 需求)` 退休。
> 于是"goods 离开 `ClassRow`"与"家户 actor 接手"**在同一次改动里发生**，行随即收窄
> ⇒ **I6.1（V4）与 I7.1 一次达成**；★ **合并的判据 = 守恒式的过渡项 `ΔΣRowGoods` 应当消失**
> （它还在 ⇒ 还有一本账没搬完）。
>
> **判据的存废**：**I6.1 保留并升级**（与 I7.1 合批）· **I6.2 作废**（它要的性质在新模型下结构成立）·
> **I6.3 已达成**（600 天实测农村布满足率 96.45%）· **I6.4 保留** · ★ **新增一条城市版目的性判据**
> （裁定 D8-A，**阈值待观察后定**，且必须确认城市/农村**没有并账** —— 否则该判据会**假绿**，见风险 R-N1）。
>
> **仍未决的落点**：本节下方阶段的**任务级计划**从"一次改动（家户主体化 + 一本账）"重写，
> 旧的"阶段 6 计划"不再需要单独编写。

**目标**：cohort 的消费**只从 receipt 来**；实物报酬是**流量不是库存**。

**关键改动面**：`ConsumptionReceipt` + **超额规则**（`receipt = min(应得, 消费需求)`，超出留 operator）；
消费路径改道；S3 的市场购买、将来的政府救济**预留同一入口**。

**判据**：
- **I6.1** **V4**：cohort 侧**无 goods 字段**；把 receipt 写回 `ClassRow.goods` ⇒ 结构断言红
- **I6.2** **V8**：应得 10 / 需求 5 ⇒ receipt=5、operator 留 5，**总账无 5 的暗漏**
- **I6.3** **V3**：农村布满足率 **> 0**
- **I6.4** **V10**（目的性判据）：重跑 600 天，`rural` 育龄压力**不再全部 > 阈值**

### 阶段 7 —— 收窄：`ClassRow` 移出库存

> ★★ **追加标注（2026-09-27）**：阶段 7 与阶段 6 **合并成一次改动**（同上）。
> ★ 另有一处**范围漏洞必须补**：I7.1 只列了 `goods` / `money` / `debts`，**没提 `meansOfProduction`**
> —— 按现判据收窄之后，**"谁拥有土地/工具"仍是行口径**（资产留在行里、商品迁走 = 产权只迁一半）。
> ⇒ 合并改动必须**一并表态** `meansOfProduction` 的去向（裁定 S3：资产整块**推迟**，
> `AssetHolding` / `ASSET_QUANTITY` 退役或封存 ⇒ `meansOfProduction` 这一轮**留在行里、作为生产能力**，
> 但**要写清它不等于所有权**）。

**目标**：`ClassRow` 只留人口 / 劳动供给 / 消费需求 / 压力 / 生死。

**判据**：
- **I7.1** `ClassRow` 无 `goods` / `money` / `debts` 字段（结构断言）
- **I7.2** 全部判据 I1–I6 **仍然绿**（收窄没有偷走职责）
- **I7.3** 记账归属：`money`/`debts` 明确挂到 **legacy bridge**（S2 迁走），**未被永久归入 actor**

---

## 四、阶段间的硬依赖

| 依赖 | 说明 |
|---|---|
| 1 → 2 | Actor 的身份键要用 `CohortKey`（`ProductionRelation` 的 recipient 侧） |
| 2 → 3 | `operator: ActorRef` 要落在阶段 2 建出来的包与类型上 |
| 3 → 4 | 没有 operator 就不知道产出该落谁 |
| **4 ↔ 5** | ★ **不能拆开落地**（见阶段 4 的风险）：产出离开 `ClassRow` 的同一个改动里，必须有"实物型结算"把消费送回去 |
| 5 → 6 | receipt 是结算的产物 |
| 6 → 7 | 没有 receipt 就不能安全地移出库存 |

★ **任一段的判据全绿才算关账**；判据是**不变量**，不随实现漂。

---

## 五、与 B1/B2 的关系

B1/B2 **不是 S1 的逻辑依赖**（用户裁定），但它们是**验收基线**：

- **B1 修完**：`rural` 育龄压力不再被"新生儿免疫"稀释 ⇒ 阶段 6 的 I6.4 才有意义
- **B2 修完**：危机读口在任意相位可信 ⇒ S1 各阶段验收时不会遇到"是模型错还是采样错"
- ★ B1 还**钉死了一条 S1 必须继续满足的不变量**：**压力计算与劳动配额无关** ——
  阶段 1 把 `CohortKey` 从 `ProductionActivity` 拔出来之后，那条测试**必须继续绿**，
  否则就证明 S1 把人口生命过程**重新绑回**了劳动关系。

---

## 六、本文件的验证边界

- 本文件**未改任何 Java、未跑 Maven**；§一 的清点出自 `grep`（引用计数是文本级的，**未区分"类型引用"与"注释提及"**）。
- **未做**：阶段 4↔5 的**合并边界**到底切在哪（是"一个阶段里两个任务"还是"一个任务"），
  要等阶段 3 的产物出来才定得准。
- **未做**：`ClassSlot → SocialClassId` 的**映射表内容** —— 四模板目前同构，但**映射本身及其断言**是阶段 1 的第一件事。
- **未做**：阶段 5/6 的参数值（地租 30% / 给养 20% 等）—— 按信条十二属"判断结果"，**要按真档观察后再定**。
