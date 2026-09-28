# R3 语义收口决策单（有效执行版，2026-09-28）

> **状态**：本文件是 R3 的**唯一有效执行口径**。旧版方案、旧切片、旧勾选项一律只作废弃附录，编码 Agent 不得执行。
>
> **代码态**：`ts/m1` @ `83007b00`；R0–R2 + 多线程市场优化 + R3 主体已提交；工作树干净。
> **总约束**：本文件不改生产代码；测试/完整验收仍留 V；每个修复批次允许一次 ≤3 分钟的 90 tick 冒烟。

---

## 0. 用户最终裁定：三条互相独立的主线

用户原话要点：**生产方式/生产力，和它带来的阶层划分，必须分离；产权本来就是独立的东西。**

```text
① 产权/资产线：AssetShare        —— 谁拥有、谁使用、多少实物资产
② 生产/生产力线：Industry(模板) + ProductionUnit(实际生产活动) + ProductionRelation
③ 阶层线：ClassRow.view          —— 从①+②+劳动/债务纯派生，只写标签，不改事实
```

### 0.1 禁止的根本性倒置

- **不得**为了写出目标阶层标签，去修改 `participationPerMille`、`LaborAllocation`、资产份额或账户；
- 劳动参与和配额由**生产、人口、劳动关系和事件**改变；阶层分类**只读**这些事实；
- 若旧“槽位上限”不允许写入真实分类，就修改**槽位数据约束 / 守卫的归属**，不是修改劳动量迁就标签；
- `Industry.slots` / `ClassSlot` 是**生产方式里的角色/劳动配置**，不是“家户阶层标签的上限”；
  `ClassRow.view` 不得再被拿来强制 `participationPerMille` 的上限。

### 0.2 旧档迁移 vs 新世界播种必须分开

- **旧档迁移**：逐条一对一保留事实。旧 `UseRight(holder=X)` ⇒ `AssetShare(owner=X, operator=X, quantity 原样)`；
  旧 `Industry` 的 operator/progress/inputUsed ⇒ 一个默认 `ProductionUnit(operator=旧 operator)`；
  **迁移不得凭空制造地主/佃户/新份额结构**。
- **新世界播种**：才按明确的初始规则创建“庄园自营 + 家户佃耕/自有”等多个 `ProductionUnit` 和份额行；
  播种规则是**独立配置**，不混进旧档迁移代码。

### 0.3 c1 孤儿债进 R3，不推迟到 V

起点 tick120 存在 62 条 c1 债：债权人侧有记录、债务人 `ClassRow.debts` 无引用。
它会让债务相关的阶层判断读到不完整状态，**不得留到 V**。
R3B.3 增加一次显式对账/迁移：以**债务表为权威**重建 `ClassRow.debts` 引用（或在无法归属时具名标记并转移/核销），
并对账 principal 守恒。做完后 `Σ(行引用) == 债务表`，不得再有孤儿。

---

## 1. 目标数据模型（方案 B）

### 1.1 AssetShare：独立的实物资产份额表

```text
AssetShare(
  AssetShareId id,        // 确定性 id；不含 '.'，可由 (location, asset, owner, operator, sequence) 拼
  HexCoord location,
  AssetKind asset,        // LAND / TOOL / WORKSHOP / ...
  ActorRef owner,         // 所有权人：庄园、家户、公共/组织
  ActorRef operator,      // 实际使用/经营者；owner==operator = 自有自营
  long quantity,          // 实物数量；LAND 千分亩，其余件
  RightKind kind)         // OWNED / TENANCY / COMMUNAL；只表达权利性质
```

- **这是唯一的实物资产总账**：`Σ quantity(location, asset)` 由所有份额行求和得到，不再由 `Industry.capacity` 充当；
- `Industry` 模板不保存土地/工具/作坊的实物总量；
- 地租、分成、工资仍由 `ProductionRelation` 结算，**不在这里存**；
- `owner != operator` = 租佃/委托/占用；终止租佃时只把该行的 `operator` 改为 owner（或新 operator），
  `owner` 和 `quantity` 不变。

### 1.2 Industry：生产技术/配方模板（不含实物总账）

```text
Industry(
  IndustryId id,          // 当前按 (hex, kind) 实例；R3B 不改成全局 recipe
  String name,
  RegimeId regime,
  long cycleDays,
  Map<AssetKind, Long> capacityPerUnit,   // 每 1 单位规模需要多少资产
  Map<CommodityId, Long> inputPerUnit, ...,
  long laborPerUnit,
  Map<CommodityId, Long> outputPerUnit,
  ...,
  List<ClassSlot> slots)   // 生产方式内部的角色/劳动配置，不是家户标签上限
```

- 移除 `Industry.operator`、`progressDays`、`cycleLaborMilli`、`cycleInputUsedMilli`、实物 `capacity`；
- 模板只回答“怎么做、每单位需要什么、产出什么”；
- `slots` 只用于生产方式内部角色和劳动配置，不再作为 `ClassRow.view` 的上限来源。

### 1.3 ProductionUnit：谁实际在生产

```text
ProductionUnit(
  ProductionUnitId id,     // 确定性：由 (IndustryId, ActorRef operator) 拼；不含 '.'
  IndustryId industry,     // 引用技术模板
  ActorRef operator,       // 实际经营者；产出/投入/关系都归它
  Map<AssetKind, Long> usableAssets,  // 由 AssetShare 汇总：operator 在此产业可用的实物资产
  long progressDays,
  long cycleLaborMilli,
  Map<CommodityId, Long> cycleInputUsedMilli)
```

- `usableAssets` 是**派生索引**：只读该 operator 在本产业相关资产上的 `AssetShare` 之和；
- 生产规模 = `min(usableAssets[k] / capacityPerUnit[k], 劳动, 实扣投入)`；
- 产出计提、投入扣减、关系分账、货币账户全部归 `unit.operator`；
- 同一 `Industry` 下可以有多个 `ProductionUnit`：庄园自营 60、甲佃耕 40，各自一条。
- **边界**：绝不允许“AssetShare 记甲、产出落庄园”。若甲有份额并实际经营，就必须有甲的 ProductionUnit 和产出账。

### 1.4 ProductionRelation：挂在 ProductionUnit 上

- 键与 `activity` 从 `IndustryId` 改为 `ProductionUnitId`；
- `relation.operator == unit.operator`；
- 一个 industry 有多个 unit ⇒ 多条 relation（各自规则、投入来源、剩余归属）。

### 1.5 阶层：纯派生

`HouseholdClassRule.classify` 只读：

```text
AssetShare(owner/operator) + ProductionUnit(operator) + ProductionRelation
+ LaborAllocation + 租/工资规则 + 债务
```

- 分类结果只写 `ClassRow.view`；
- 不修改 `participationPerMille`、`LaborAllocation`、资产、账户、债务；
- 没有富农证据就报告 0 个富农，不得为了“四档齐全”造标签；
- 同一份资产/劳动/分成/债务状态必须得到同一分类（纯函数，可重放）。

---

## 2. 有效实施切片（R3B.1–R3B.4）

| 切片 | 内容 | 完成判据 |
|---|---|---|
| **R3B.1** | `UseRight` → `AssetShare`（location/asset/owner/operator/quantity/kind）；EconomyData/ChangeSet/Codec/Resolver/ApiViews/Payloads/Seeder/LegacyMigration 同步；旧档 owner=operator=旧 holder 一对一 | 编译绿；旧档可读；tick0 `Σ shares` 对账；分类器改读 owner/operator，仍纯派生 |
| **R3B.2** | 新增 `ProductionUnit` + `Industry` 模板化（移除 operator/progress/inputUsed/实物 capacity）；`ProductionRelation` 键改 `ProductionUnitId`；结算/市场/读口改按 unit 取 operator/capacity/progress/inputs；旧档生成一个默认 unit | 编译绿；单 unit 旧世界行为与现状逐值等价；`Σ AssetShare == 实物总账`；产出归 unit.operator |
| **R3B.3** | 新世界播种规则创建多个 unit（庄园自营 / 家户佃耕 / 家户自有）；事件命令改份额与 operator；c1 孤儿债显式对账（债务表为权威）；不做救济 | 冒烟：同一 hex 至少两个 unit 各有 progress/产出账；无孤儿债；principal 守恒；资产份额变化先于产出归属变化 |
| **R3B.4** | 分类纯派生：移除 `slotCapFallback` 与任何“改劳动迁就标签”的路径；`ClassRow.view` 只写标签；旧槽位约束改为生产方式/劳动配置的内部约束 | 冒烟：同状态重放分类一致；改生产关系后账户先变、阶层后变；活跃富农为 0 时如实为 0；无“为标签改 participationPerMille” |

**顺序约束**：B.1 → B.2 → B.3 → B.4；每片只编译 + 一次 ≤3 min 90 tick 冒烟；不得跳片合并，不得在 B.4 前用旧槽位限制改写分类结果。

---

## 3. 验收（替换旧“四档齐全”式判据）

1. **分类纯函数性**：同一资产/劳动/分成/债务状态，重复分类逐值相同；
2. **先因后果**：改变生产关系/资产/配额后，先看到账户/份额/劳动变化，再看到阶层标签变化；
3. **允许零**：真实账本没有富农，就输出 0 个富农；不得为了分布好看造标签；
4. **不变量**：
   - `Σ AssetShare(location, asset)` = 实物总账（迁移时与旧 capacity 对账）；
   - `Σ AssetShare(operator) = ProductionUnit.usableAssets`；
   - `ProductionUnit.operator == relation.operator`；
   - 产出/投入/债务/账户都归 unit.operator；
5. **旧档迁移**：owner=operator=旧 holder 一对一；不凭空造地主/佃户；
6. **新世界播种**：多 unit/佃耕按显式种子规则创建；
7. **c1 孤儿债为 0**（或全部具名标记并完成显式处置/守恒）；
8. **无禁止性修改**：不存在“分类器改 participationPerMille/LaborAllocation/资产”的代码路径；
9. **运行**：0→360 分三段，每段 ≤3 min；无负余额、无守恒异常。

---

## 附录 A：废弃方案（DEPRECATED，不得执行）

> 以下内容仅作讨论留痕。编码 Agent **不得**按这里执行。

- 旧 D1 的 A/B/C 三选一，以及“按阶层份额先分地、再用地推阶层”；
- 旧 D2 的 `slotCapFallback` / “为写出目标阶层而调整 participationPerMille/LaborAllocation”；
- 旧 D3 的 `RELIEF` 转移、救济制度、违约核销；
- 旧 R3.1–R3.4 切片编号与旧勾选清单（本文件 §2 已用 R3B.1–R3B.4 取代）；
- 旧验收“四档必须齐全”。

**当前唯一有效计划 = 本文 §0–§3。**
