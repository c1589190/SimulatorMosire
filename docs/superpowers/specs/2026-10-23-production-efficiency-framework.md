# 经济生产框架：单 tick 单产业 产出数量公式（约束设计书）

> 状态：用户已裁定并授权派单（2026-10-23）。本文是 Z1/Z2/Z3/Z4 批次的**唯一外部契约来源**；
> 派单文档与实现代理必须以本文为准，与本文冲突 ⇒ 上报，不得自行改设计（AGENTS §一.8.1）。
> 范围：只搭"单 tick 单产业生产框架"；天气/日历/文化/逐 tick 入库/产业模板整体可编辑/工具归属重构**都不在本批**。

---

## 0. 用户原话（逐字，不得改写）

> 我的想法是，现阶段算单tick单产业生产效率，直接按投入的劳动力\*生产资料满足率\*产品产出率常数\*修正参数作为公式，然后再加个余数相加完事了，其中只有最后一个常数可以被GM修改，修正参数能同时作为接口被程序内机制修改，GM反而改不了；天气啥的先别做，先把这么套经济生产框架搭好，其他你想到的记文档里；能实现吗？能实现直接派单了

> 这里的产品产出率，率字可以删了，就是直接的产品产出数量

背景（同一贯要求的前置语境，逐字）：

> 我要求这些东西不但要能被GM改，还要能被用项目的其他模块改，有办法吗？规划调查一下

> 此外还要实现一个容易修改、伴随着tick动态波动、必然被外部模块决定的生产效率变量，这个貌似已经有了，我希望以其来解决后续生产效率受到生产资料、文化、天气气候等影响时等效率变化，这个也一并调查一下吧

**术语冻结**：用户原话里的"产品产出率常数"一律按第二段原话改称 **产品产出数量**（即每单位规模的直接产出量，
对应现有 `Industry.recipe().outputPerUnit()`；本批不新增"率"字命名）。

---

## 1. 公式（冻结）

单 tick、单产业（生产单元 `ProductionUnitId`）、单商品 `j`：

```
产出数量_j = 投入的劳动力 × 生产资料满足率 × 产品产出数量_j × 修正参数
```

四项的来源与量纲：

| 因子 | 来源 | 量纲 | 谁决定 |
|---|---|---|---|
| 投入的劳动力 | 当日该 unit 名下全部劳动配额（现有 `laborByUnit`，逐日累加） | 千分劳动·日 | 结算派生 |
| 生产资料满足率 | 可用生产资料（`capacityScaleOf`，由 OwnershipStake 派生）× 状态缩产（`StressPolicy.plannedScalePerMille`，沿用旧语义）与已扣原料两者对劳动所支持规模的比值，取最紧、封顶 1000‰ | 千分（0..1000） | 结算派生 |
| 产品产出数量_j | 默认 = `Industry.recipe().outputPerUnit()[j]`；可被 **GM 覆盖表**改写 | 商品数量 / 单位规模 | **只有 GM 能改** |
| 修正参数 | 程序内机制逐 tick 注入（默认 1000‰） | 千分（0..2000） | **只有程序内机制能改；GM 无写口** |

"生产资料"按中文政治经济学口径含**劳动资料与劳动对象**，故满足率同时受资产那一路与周期投入（种子/原料）那一路约束；
这与现有 `scaleOf` 的三路取 min 同义（见 §6 不变量）。

### 1.1 执行时点（本批裁决，必须如实记档）

现有模型是**周期末收获**（农业 `cycleDays=120` 等）。本批**不改产出物化时点**，只把公式的四项与余数逐 tick 化：

- **逐 tick**：机制注入修正参数；本框架把当日修正参数记入 unit 的效率状态（周期累计）。
- **周期末**（现有 `harvest` 调用点）：用周期平均修正参数、周期劳动、当日投入对公式求值，产出仍走既有
  损耗/关系分账/入账全流程（一字不改）。
- 修正参数逐 tick 变化**真实影响**本周期产出（按天平均；见 §6.2）。
- **延后**：真正"逐 tick 直接产出/逐 tick 入库"另开批次（要动 harvest、消费时点、市场时点，风险独立）；
  本批的状态设计允许以后在其上加 `cycleGrossMilli` 类累加器，不预埋。

### 1.2 数值口径变化（必须如实测，不得声称逐值不变）

本批把旧的"先取 min 再乘产出数量"显式化为四项乘积，并引入余数累加。与旧口径的差异：

1. 劳动链从"周期累计 ÷ cycleDays ÷ laborPerUnit"（两次向下取整）改为**带余数结转**的同式 ⇒ 长期不再系统性丢精度；
2. 满足率显式千分量化（封顶 1000‰，与 `GovEfficiency` 同尺）⇒ 最紧约束那一路最多差 1 个规模单位；
3. 修正参数缺席 = 1000‰ ⇒ 上述 1/2 之外的旧行为逐值不变（本条可被 Z4 的对照逐值验证）。

Z4 必须产出"同参数、同种子、同 tick 数的旧/新对照报告"：差异逐产业列出，并给出是否在预期范围（≤1 规模单位/周期）的裁定请求。

---

## 2. 写口矩阵（谁能改什么）

| 对象 | GM 命令 | 程序内机制 | 决策令 | 结算 | 其他模块 |
|---|---|---|---|---|---|
| 投入的劳动力 | 无（走现有劳动预算接口） | 现有 `recomputeLaborBudgets`（app 从 Social 注入） | 现有 | 派生 | 经 app |
| 生产资料满足率 | 无 | 现有 StressPolicy 由 `OperatorSettlement` 写状态间接决定 | 现有 | 派生 | 经 app |
| **产品产出数量_j** | **新增两个 kind（§4）** | 无（本批不给模块写口；要写走 GM 命令的 app 桥，后续工具归属批次） | 无（本批不加 `CommandTargets`） | 只读 | 经 app `submitBatch` 提交同一命令（后续） |
| **修正参数** | **无、永不加** | **新增 typed 接口（§5）** | 无 | 只读消费 | 经 app 组合根调用接口 |
| 效率余数/累计 | 无 | 无 | 无 | 派生写（随 revision 落盘） | 只读 |

---

## 3. 新状态组件（2 个，冻结名）

### 3.1 `outputQuantityOverrides`（产品产出数量覆盖表）

- 类型：`Map<IndustryId, Map<CommodityId, Long>>`；键保序不可变（`LinkedHashMap` + `Collections.unmodifiableMap`，
  两段逐字展开，照 `ProductionRecipe` 先例，禁 `Map.copyOf`）。
- 语义：**只在 GM 显式改过时存在**；缺 industry / 缺 commodity = 回落 `recipe.outputPerUnit()` 默认。
  值 = 商品数量 / 单位规模（与 `outputPerUnit` 同量纲，整数）。
- 守卫（命令边界与载入边界都查）：industry 必须存在于 `industries`；commodityId 必须是该产业
  `recipe().outputPerUnit()` 的键；值 ∈ `[0, 1_000_000]`（防溢出；0 = 显式停产式产出，允许但记 INFO）。
- 旧档缺节点 = 空表（中性）。载入时若出现"产业不存在/商品不在配方"的条目 ⇒ **契约 ERROR + fail-closed**。
- `ClearRegion`：按 `IndustryId` 里的格键删除该格全部覆盖（与 `industries` 同生共死）。
- `Seed`：payload 不声明本组件；播种/合并后保持空表（不产生第二份默认值）。

### 3.2 `productionEfficiency`（生产效率累计与余数表）

- 类型：`Map<ProductionUnitId, ProductionEfficiencyState>`。
- `ProductionEfficiencyState`（`simos-economy/.../model/`，与 `OperatorCondition` 同目录）字段（冻结）：

| 字段 | 含义 | 有效域 | 何时清零 |
|---|---|---|---|
| `cycleModifierSumPerMille` | 本周期逐 tick 修正参数之和（缺省 tick = 1000） | ≥ 0 | 周期末 |
| `modifierRemainderMilli` | Σ修正 ÷ cycleDays 的余数 | `[0, cycleDays)` | 跨周期保留 |
| `laborDayRemainderMilli` | 周期劳动 ÷ cycleDays 的余数 | `[0, cycleDays)` | 跨周期保留 |
| `laborScaleRemainderMilli` | 日均劳动 ÷ laborPerUnit 的余数 | `[0, laborPerUnit)` | 跨周期保留 |
| `scaleRemainderMilli` | 最紧规模 × 平均修正 ÷ 1000 的余数 | `[0, 1000)` | 跨周期保留 |

  记录构造期守卫：五个字段都 ≥ 0；其余有效域由公式代码在使用时 fail-closed 校验（分母是 per-industry 值，
  不能写进 record 构造期）。
- `ClearRegion`：按 `ProductionUnitId` 里的格键删除该格全部效率状态（与 `units` 同生共死）。
- `Seed`：空表。旧档缺节点 = 空表（缺行 = 全 0 余数、当周期全 1000‰ ⇒ 中性）。
- **不变量**（Z4 必测）：任一 unit 的五个字段在每次 revision 落盘时满足有效域；周期末消费后
  `cycleModifierSumPerMille == 0`。

---

## 4. 命令契约（GM 唯一可改项）

沿用 `economy.GmAdjust`（`EconomyGmAdjustments` / `EconomyGmAdjustHandler`）现有形状，新增 2 个 kind：

| kind 常量 | 值 | payload | 语义 |
|---|---|---|---|
| `SET_OUTPUT_QUANTITY` | `"setOutputQuantity"` | `{industryId, commodityId, quantity}` | upsert 一条覆盖 |
| `CLEAR_OUTPUT_QUANTITY` | `"clearOutputQuantity"` | `{industryId, commodityId}` | 删除覆盖（回默认） |

守卫与拒绝（全部具名、被拒绝一律 INFO；契约/一致性故障 ERROR）：

- `industryId` 不存在 ⇒ 拒 `INDUSTRY_NOT_FOUND`；
- `commodityId` 不在该产业配方产出键里 ⇒ 拒 `COMMODITY_NOT_IN_RECIPE`（不许开新商品）；
- `quantity` 缺失/非整数/<0/>1_000_000 ⇒ 拒 `QUANTITY_OUT_OF_RANGE`；
- `clearOutputQuantity` 没有既有覆盖 ⇒ 拒 `NO_OVERRIDE_TO_CLEAR`（不做静默幂等，防错字）；
- 投影出的 `Projection.Change.component` 固定为 `"outputQuantityOverrides"`，`keyId` = `industryId + "/" + commodityId`。

该命令**本批保持 GmOnly**（不新增 `CommandTargets`、不放入决策令桶）；其他模块的可达路径统一是
"app 组合根 `submitBatch` 同 revision 提交"，本批不实现 typed 桥（工具归属批次另立项）。

---

## 5. 程序内接口契约（修正参数，GM 不可达）

### 5.1 typed 记录（economy-api）

新建 `simos-economy-api` record：

```java
ProductionEfficiencyModifier(ProductionUnitId unit, long modifierPerMille, String source, String reason)
```

- 守卫：`unit` 非空；`modifierPerMille ∈ [0, 2000]`；`source`、`reason` 非空白（审计用，稳定前缀，如 `test-bridge`）。
- `source` 用**自由文本**（本批不建封闭枚举；将来若文化/天气等机制稳定再收紧，是兼容的收紧方向）。

### 5.2 注入方法（`EconomyDayStepper`）

```java
public void updateProductionModifiers(List<ProductionEfficiencyModifier> modifiers)
```

- 时序：app 参与者在**当日 `step(day)` 之前**调用（与 `updateNaturalNeeds`/`recomputeLaborBudgets` 同日序先例）。
- 语义：**替换**本 tick 的注入集；同一 unit 重复 ⇒ 拒；未注入的 unit = 1000‰（中性）；当日结算消费后清空 ⇒
  机制要连续影响就必须逐 tick 注入（"伴随 tick 动态波动"）。
- 未知 unit（不在 `units` 表）⇒ 具名拒（INFO + `IllegalArgumentException`，与 `updateNaturalNeeds` 同款 fail-closed）。
- 确定性：机制给出的值必须是**已持久化状态的纯函数**；本批不提供持久化修正表（set-and-forget 另开批次）。
- **GM 不可达的负向验收**（Z4）：`EconomyGmAdjustments` 无修正参数 kind；`EconomyAdjustTool` schema 无修正参数字段。

---

## 6. 公式与余数（周期末求值，冻结算法）

设：`L` = 本周期实际劳动合计（`unit.cycleLaborMilli`，现有字段，不改语义）；
`S` = `productionEfficiency` 状态；`D` = `industry.cycleDays()`；`lpu` = `recipe.laborPerUnit()`。

### 6.1 逐 tick（结算日内，现有 `laborByUnit` 计算处）

```
m_t = 注入修正（默认 1000）           // 有效域 [0,2000]
S.cycleModifierSumPerMille += m_t      // 不取整、不丢精度
```

### 6.2 周期末（`harvest` 内，替换现有 `avgLaborMilli + scaleOf` 那一段）

```
① 平均修正：
   n1 = S.cycleModifierSumPerMille + S.modifierRemainderMilli
   avgModifier = n1 / D                 // ‰，D ≥ 1
   S.modifierRemainderMilli = n1 % D

② 劳动（沿用旧除法链 + 余数结转）：
   n2 = L + S.laborDayRemainderMilli
   avgLabor = n2 / D
   S.laborDayRemainderMilli = n2 % D

   若 lpu > 0：
     n3 = avgLabor + S.laborScaleRemainderMilli
     laborScale = n3 / lpu
     S.laborScaleRemainderMilli = n3 % lpu
   若 lpu == 0：
     ⇒ 本公式不适用，走旧口径（capacity×planned 与投入取 min），修正参数不生效，记一条具名 DEBUG；
       laborScale 仅用于日志。

③ 满足率（0..1000，显式算出用于日志与不变量）：
   capPlanned = capacityScaleOf(unit, industry, index) * plannedPerMille / 1000
   inputScale_j = cycleInputUsedMilli_j / inputPerUnit_j     （逐投入，inputPerUnit_j > 0）
   satisfaction = min(1000, capPlanned*1000/laborScale, inputScale_j*1000/laborScale …)   // laborScale==0 ⇒ 0

④ 最紧规模（与旧语义同义，一行不得少）：
   scaleBase = min(laborScale, capPlanned, inputScale_j …)
   // 若 lpu == 0 ⇒ scaleBase = min(capPlanned, inputScale_j …)

⑤ 修正参数与余数（产品产出数量的乘数在这里只作用于规模，数量在 ⑥ 乘）：
   n4 = scaleBase * avgModifier + S.scaleRemainderMilli
   scale = n4 / 1000
   S.scaleRemainderMilli = n4 % 1000

⑥ 产出数量默认/覆盖：
   quantity_j = outputQuantityOverrides[industryId][commodityId] 若存在，否则 recipe.outputPerUnit()[j]
   gross_j = scale * quantity_j * 1000        // 毫单位；后续损耗/关系/入账一字不改

⑦ 周期末清账：
   S.cycleModifierSumPerMille = 0            // 其余四个余数跨周期保留
```

### 6.3 余数不变量（Z4 必测）

1. 中性运行（修正恒 1000、余数全 0）⇒ `scale == scaleBase` 逐值，且 `scaleBase` 与旧 `min` 口径逐值相同；
2. 修正恒 `m` ⇒ 长期 `Σ scale ≈ Σ floor((scaleBase_t*m + carry)/1000)`，无系统性丢精度（构造黄金用例：`scaleBase=3, m=500` ⇒ 两周期共 3）；
3. `avgModifier` 忠实反映逐 tick 注入：`m_t` 全 1000 与"前 D/2 天 500、后 D/2 天 1500"⇒ 同上界差异 ≤1‰ 除以 D 的进位；
4. 五个余数字段始终在有效域；周期末 `cycleModifierSumPerMille == 0`。

---

## 7. 接线清单（照 `periodicAdjustments` 先例；文件归属即责任区）

| 文件 | 改动 | 区 |
|---|---|---|
| `simos-economy-api/.../ProductionEfficiencyModifier.java` | 新建 typed 记录（§5.1） | Z1 |
| `simos-economy/.../model/ProductionEfficiencyState.java` | 新建 per-unit 状态记录（§3.2） | Z1 |
| `simos-economy/.../EconomyData.java` | 两个新组件（record 分量 + 归一化/访问器 + `with…`） | Z1 |
| `simos-economy/.../change/EconomyChangeSet.java` | 两个新组件进变更集（反射枚举测试自动把守） | Z1 |
| `simos-economy/.../codec/EconomyCodec.java` | 编码/解码 + 旧档缺节点 = 空表 + 载入守卫 | Z1 |
| `simos-economy/.../time/EconomyStateBuilder.java` | 组件读取/装配 | Z1 |
| `simos-economy/.../spi/EconomySeedHandler.java` | 新组件保持空表（不进 payload） | Z1 |
| `simos-economy/.../spi/EconomyClearRegionHandler.java` | 按格键删除两个新组件 | Z1 |
| `simos-economy/.../spi/EconomyGmAdjustments.java` | 2 个新 kind + 投影守卫（§4） | Z1 |
| `simos-economy/.../spi/EconomyGmAdjustHandler.java` | 新 kind 落盘/拒绝/日志 | Z1 |
| `simos-economy/.../EconomyLogSource.java` | 两条新来源（§8） | Z1 |
| `simos-economy/.../time/EconomyDayStepper.java` | `updateProductionModifiers`（§5.2）+ 会话暂存 | Z1 |
| `simos-economy/.../time/EconomySettlement.java` | 逐 tick 汇总修正；周期末公式（§6）替换 `scaleOf` 段 | Z2 |
| `simos-economy/.../time/ProductionEfficiencyBook.java` | 公式纯函数（§6，便于黄金测试与日志） | Z2 |
| `simos-app/.../tools/write/EconomyAdjustTool.java` | 2 个新 kind 的 schema/描述/PAYLOAD_HINTS | Z3 |
| `simos-app/.../tools/CatalogTool.java`（若涉经济调整目录） | 新 kind 提示 | Z3 |
| `simos-app/.../api/ApiViews.java`（若经济读口在此） | 只读：每 unit 当前修正/周期效率读数（可选，缺失不阻塞） | Z3 |
| `simos-economy/src/test/**`、`simos-app/src/test/**` | 测试统一在 Z4；Z1–Z3 只做**编译必需**的最小修 | Z4 |

Z1 与 Z2 同属 `simos-economy` 模块但文件不重叠：Z1 只动状态/命令/接口/暂存，**不碰 `EconomySettlement`**；
Z2 只动公式与结算路径，不改状态定义（发现定义不足 ⇒ 上报，不自行加字段）。

---

## 8. 日志要求（AGENTS §一.9）

新增两条 `EconomyLogSource`（中文描述 + `originKind`）：

| 常量 | 名 | 描述 | originKind |
|---|---|---|---|
| `ECONOMY_OUTPUT_QUANTITY` | `economy-output-quantity` | 产品产出数量覆盖：GM 设置/清除/回落默认（命令面） | SYSTEM |
| `ECONOMY_PRODUCTION_EFFICIENCY` | `economy-production-efficiency` | 单 tick 单产业生产框架：修正注入、有效规模、产出数量公式（tick 算法，必带 day） | TICK |

事件与级别（事件名稳定、字段可加不可改）：

- `OUTPUT_QUANTITY_SET`、`OUTPUT_QUANTITY_CLEARED`：INFO（命令面写口必 INFO；含 industry/commodity/old/new/reason）；
- `OUTPUT_QUANTITY_REJECTED`：INFO（具名 reason）；
- `PRODUCTION_MODIFIER_INJECTED`：INFO，每日一条（day、count、非中性数）；
- `PRODUCTION_MODIFIER_REJECTED`：INFO（未知 unit/越界/重复）；
- `PRODUCTION_EFFICIENCY_TICK`：DEBUG，逐 unit（day、unit、m_t、周期累计）；
- `PRODUCTION_EFFICIENCY_HARVEST`：DEBUG，逐 unit（day、laborScale、satisfaction、scaleBase、avgModifier、scale、四个余数）；
- `PRODUCTION_EFFICIENCY_CONTRACT`：ERROR（余数越域、覆盖表指向不存在产业/商品等契约故障）。

**不接受任何 WARN 降级**；已有 WARN 不因本批改动升降级。

---

## 9. 明确不做（本批边界，用户原话"天气啥的先别做"）

1. 天气/气候/季节曲线接入（`SeasonState.progress` 钩子**不用**；`Shell:800` 注释与 `CalendarClock.julianDefault` 的
   真实缺口只记录、不修）；
2. 文化/宗教/基层组织字段与 SPI 贡献者（方案 3 延后）；
3. 逐 tick 产出物化/逐 tick 入库（§1.1）；
4. 修正参数持久化（set-and-forget）与加成 >2000‰ 的口径；
5. 产业模板整体可编辑（`upsertIndustry`）——本批只开"产出数量覆盖"这一列；
6. 生成参数（`GRAIN_OUTPUT_PER_MU` 等）升格为世界状态；
7. 工具归属重构（领域模块拥有工具）；
8. 债务累积的下一杠杆（亩产/亩数/人口/偿债机制）——run4 实测未达成，待用户另行裁定；
9. 税收行政损耗/国库/军俸/铸币等 P2 新发现（F1–F4）——已有报告，另行排期。

---

## 10. 责任区与派单顺序（AGENTS §一.5/§一.8/§一.10）

| 区 | 目标（可独立验收） | 入口 | 完成定义 |
|---|---|---|---|
| **Z1** | 状态 + GM 命令 + 程序内接口 + 暂存/日志骨架 | 本文 §3/§4/§5/§7/§8 | `spotless:apply` + `-pl simos-economy -am -DskipTests compile` 绿；自写 ledger |
| **Z2** | 公式 + 周期末接线 + 逐 tick 汇总 + 公式日志 | Z1 编译产物 + §6 | 同上（economy 模块）编译绿；自写 ledger |
| **Z3** | GM 工具 schema/目录/只读读数 | Z1 的命令契约 | `-pl simos-app -am -DskipTests compile` 绿；自写 ledger |
| **Z4** | 统一测试与真实行为证据 | Z1–Z3 | 往返/边界/黄金用例/负向权限 + 360 tick 旧新对照 + 全仓 `clean verify` |

纪律：

- 每个区一个写代码代理；只写生产代码到**编译过**（编译是入口门不是完成门）；测试统一 Z4；
- 不与本仓其他 Maven/代理并行跑构建（统一 `tools/mvn-lock.sh`）；
- 子代理在 `.superpowers/sdd/2026-10-23-production-efficiency-framework/<zone>-impl-ledger.md` 自写台账：
  文件清单、契约映射、偏差/不确定、门禁命令与结果；
- 发现架构与本文/用户原话冲突 ⇒ **BLOCKED 上报**，由控制方问用户，不得自行改设计（AGENTS §一.8.1）；
- 不提交、不 `git add -A`；控制方复核后提交。

---

## 11. 验收要点（Z4 最小集）

1. `EconomyRoundTripTest` 反射枚举自动覆盖两个新组件（不绿即接线漏了）；
2. 旧档（无两节点）载入 ⇒ 空表/中性，逐值可跑；带非法覆盖条目 ⇒ ERROR fail-closed；
3. `ClearRegion` 后两个新组件按格清空，其他格逐值不变；
4. 命令边界负向用例：产业不存在 / 商品不在配方 / 数量越界 / 无覆盖可清 ⇒ 具名拒 + INFO；
5. 余数不变量（§6.3）逐条黄金用例；
6. 修正参数接口：逐 tick 注入生效；未注入 = 中性；未知 unit 拒；`EconomyGmAdjustments`/GM 工具无修正写口（负向）；
7. 同种子、同参数、同 360 tick 的旧/新对照：差异逐产业列出，≤1 规模单位/周期或给出解释；
8. `tools/mvn-lock.sh clean verify` 全绿（含 SpotBugs 15 模块、前端 412）。

---

## 12. 本批之外（调查发现存档）

完整调查（只读静态核查，未跑构建）见
`docs/superpowers/reports/2026-10-23-editability-and-efficiency-investigation.md`，要点：

- 现状**没有任何领域模块能直接提交 economy 命令**；唯一语义落点 `EconomyGmAdjustments.project` 住在实现模块，
  economy-api 无 typed 编辑契约；其他模块改经济的正路是 economy-api typed intent + app 通用执行器（工具归属批次）；
- 产业模板（`Industry` 配方）无编辑命令；生成参数是编译期常量；`Shell.java:800` 注释称生产路径已注入
  `CalendarService`，实际参与者四处硬编码 `CalendarClock.julianDefault()`（真实缺口，记档不改）；
- `SeasonState.progress` 类注明确是给农业/经济的钩子，但 economy 对 calendar/climate/season 0 命中；
- 文化字段不存在（`SocialData` 无 culture/民族/宗教；2026-10-04 规划"只规划不实现"；`HouseholdLookup` SPI 已留）；
- 工具归属：app 91 个 `AgentTool` 实现，9 个领域模块 0 个，按用户 2026-10-23 原话单独立项；
- 债务累积（run4）未达成：债务仍被每个关账日清空（残余 12.5k/4.6k/6.3k 毫粮），自给率 28.6×，
  下一杠杆待用户裁定。

---

## 13. 控制方收尾裁定（Z1 实际落地，2026-10-23）

以下为 Z1 实现与本文的偏差，控制方逐条裁定为**接受**（不改代码；Z4 按此验收）：

1. `PRODUCTION_MODIFIER_REJECTED` **不带 `day`**：拒绝发生在 §5.2 冻结的无 day 方法内。带 day 的每日 INFO 由
   `step(day)` 的 `PRODUCTION_MODIFIER_INJECTED` 承担（day/count/nonNeutral）；拒绝项带 reason/unit/modifierPerMille，
   日号由相邻日志定位。接受。
2. 跨表引用守卫位置：命令边界（具名拒）+ `EconomyCodec` 载入与重放（`PRODUCTION_EFFICIENCY_CONTRACT` ERROR +
   fail-closed）；`EconomyData` 构造期只判结构与值域 `[0,1_000_000]`，不判跨表（避免卡死 `with*` 逐组件中间态）。接受。
3. `economy.Seed` append 语义：合并时**原样带过**既有覆盖表/效率表（不静默清空；对照 `periodicAdjustments` 先例）。
   接受。
4. 新记录的包名 `io.mosire.simos.economy.api.production`。接受。
5. codec 契约 ERROR 复用 `EconomyLog.command()` 通道、来源字段 `ECONOMY_PRODUCTION_EFFICIENCY`。接受。
6. §4 的"数量缺失/非整数"由 `EconomyGmAdjustHandler` 形状守卫拒（具名 payload 错误）；`<0/>1_000_000` 由 `project`
   拒 `QUANTITY_OUT_OF_RANGE`。Z4 负向用例两类都覆盖，不要求同一短语。接受。
7. **GM 覆盖只作用于生产路径**：`ExpectedProfitBook` 等只读预期仍读配方默认值（避免本批扩面）；Z4 记一条已知边界，
   期望/读数一致性另开批次。接受。
