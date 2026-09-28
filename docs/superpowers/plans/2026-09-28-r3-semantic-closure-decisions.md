# R3 语义收口决策单（2026-09-28）

> **用途**：R3 已经能跑通 0→360 的三段真实模拟，但每轮修复都会暴露下一层“旧模型假设 vs 新目标”的冲突。
> 本单只做一件事：把 R3 剩余的四个语义选择一次冻结，之后只派**一轮集中修复**，不再零敲碎打。
>
> **代码态**：`ts/m1` @ `0e0fc566`；R0–R2 + 多线程市场优化 + R3 主体已提交；工作树代码干净。
> **约束**：本文件不改生产代码；测试/完整验收仍留 V 阶段；每个修复批次允许一次 ≤3 分钟的 90 tick 冒烟。

## 0. 用户追加裁定（2026-09-28，覆盖下文旧选项）

- **D2 确定走“动态调整”**：阶层派生结果要写回 `ClassRow.view` 时，先调整该户的
  `participationPerMille` 与 `LaborAllocation`，使目标阶层在槽位上限内合法，然后写 view；
  不再使用静默 `slotCapFallback` 作为长期方案。
- **D3 救济不做**：救济属于政府/宗教模块，最基础的 GOV 模块在后续计划中实现；
  R3 现在不新增 `RELIEF` 转移、不建救济义务。债务、未满足需求、违约状态先保留为可读状态；
  c1 孤儿债作为数据完整性问题留给迁移/V 阶段，不在 R3 用“救济”掩盖。
- **D1 + D4 合并为“资产份额表 + 事件时更新”**：
  - 行 = `(地点/产业, 资产类型, 所有者, 实际经营者, 数量)`；
  - `所有者 == 经营者` = 自有自营；`所有者 != 经营者` = 租佃/委托/占用等；
  - D1 只负责**首次写表**；租佃、转让、退出、迁移、未来的 GOV 占有/征用只在**事件时改表**；
  - 地租/分成仍由 `ProductionRelation` 结算，不塞进资产份额表；
  - 日常生产只读“各经营者可用资产汇总”；份额表变化时重建一次汇总，不每天重算产权；
  - **边界**：若实际经营者与产业当前 operator 不同，实际经营者必须有对应的生产活动与产出账户；
    不允许“地记在甲名下、产出全落到庄园经营者账上”。

> 下文 §1–§5 保留原始选项作为讨论留痕；执行时以本节的用户裁定为准。

---

## 0. 当前事实（回代码核过）

### 0.1 使用权

- `EconomySeeder.useRightsOf`（`simos-app/.../EconomySeeder.java:1695`）把每个产业的**全部 capacity**
  发成 `UseRight(kind=OWNED, holder=operator actor)`；
- 真档 1799 条 `UseRight` 的 holder 全是 `ESTATE:farm@…` / `HOUSEHOLD:weave@…` / `WORKSHOP:craft@…`，
  **没有任何家户直接持有 LAND/TOOL/WORKSHOP**；
- `EconomyData` 构造期守卫（`EconomyData.java:427-516`）要求：
  1. `Σ quantity(activity, asset) ≤ Industry.capacity(activity, asset)`；
  2. **若该产业有使用权，`ProductionRelation.operator` 必须是其中一个 holder**。

### 0.2 阶层分类

- `HouseholdClassRule` 当前已读 `UseRight + ProductionRelation(LaborSource/operator/inputSupplier/residualOwner)
  + LaborAllocation + 租规则 + 债务`；
- 但因为 0.1，living 行中没有家户持有 LAND/TOOL/WORKSHOP；分类只能靠“地租受方/劳动关系”近似；
- 最新 240→360 实测：living 行 `landlord 799 / middle 1053 / poor 2148 / rich 0`；
  **living rich = 0 是数据边界，不是分类器漏读**；
- `EconomyData` 槽位守卫：派生出的新阶层若 `participationPerMille` 超过旧 slots 上限，会被降级（当前降级到合法旧档）。

### 0.3 债务、救济、违约

- 最新 240→360 实测：debts 412 条 / principal **8,847,700**；newBorrowing **106,238**（旧代码 3.08B）；
- `repaid` 仍低（20,986），其中 8,455,383 是起点 tick120 的 **c1 孤儿债**（债权人侧有、债务人行 `debts` 无引用，
  `repayDebts` 遍历不到）；
- 信用线收紧后，缺口从隐性债务变成显性 `unmet_grain` 4.11B → **9.86B**；
- 没有救济制度；`Debt.defaulted=true` 目前只可能由 `settleOperatorExits` 写，本阶段还没触发过。

### 0.4 退出与使用权/劳动去向

- `OperatorSettlement` 能到 `CONTRACTING / OVERSUPPLIED`，但 0→360 内没有 `INDEBTED/SUSPENDED/EXITED`；
- `UseRight` 只有 `holder` 一栏，**没有“所有权人/授予人”维度**；退出时若把权利从 holder 移走，
  没有合法去处，也没有“地主收回/债权人受偿/继承”的表达；
- `economy.MigrateHousehold` 当前只改 `ClassRow.view.hex`，不搬账户、不搬使用权；
- `LaborAllocation` 可以在产业停业时回池，但“使用权随人/随债/随继承”没有路径。

---

## 1. D1：土地使用权初始归属

### 选项

| 方案 | 内容 | 优点 | 代价 |
|---|---|---|---|
| **A 维持 operator 持有** | 不拆权利；阶层分类继续按生产关系/租佃近似 | 改动最小；与现有 `relation.operator = holder` 守卫相容 | living rich 永远不可达；landlord 只能靠地租受方识别；“谁占有土地”仍答不出 |
| **B 全部分给家户** | 每个产业的 capacity 按家户份额分成 `UseRight`；operator 不再是 holder | 阶层直接由占有关系决定；最符合目标 | 与 `relation.operator ∈ holders` 守卫冲突；必须同时改关系/operator；大规模重标定 |
| **C 混合（推荐）** | operator 保留**直营份额**（OWNED），其余按阶层份额分给家户；总 rights == capacity | 兼容守卫；地主/富农/中农/贫农都能从占有关系产生；operator 仍是 holder | 需要新的初始份额配置 + 旧档迁移 + 参与率/地租联动 |

### C 的默认初始份额（可调，建议先冻结为 preset）

以 `OWNERSHIP_SHARE_PER_MILLE` 配置表表达；建议初值：

| 产业 / 资产 | operator 直营 | landlord | rich | middle | poor | 说明 |
|---|---:|---:|---:|---:|---:|---|
| farm / LAND | 100 | 400 | 250 | 200 | 50 | 地主+富农占多数，贫农只有少量 |
| farm / TOOL | 600 | 100 | 100 | 100 | 100 | 农具主要归庄园/富农 |
| weave / TOOL | 400 | 100 | 200 | 200 | 100 | 家庭纺织工具分散 |
| craft / WORKSHOP | 700 | 0 | 100 | 100 | 100 | 作坊主要在 operator 手里 |

- 每个 `(activity, asset)` 的份额之和必须恰为 1000‰，且 `Σ rights == capacity`；
- `kind` 初值：operator 直营 = `OWNED`；家户份额 = `TENANCY` 或 `OWNED` 由用户选（建议
  farm LAND 家户份额用 `OWNED`，weave/craft 家户份额用 `TENANCY`）；
- 旧档迁移：旧 `UseRight(holder=operator)` ⇒ “operator 直营份额 + 家户份额”按同一 preset 拆分；
- `relation.operator` 仍是 holder（operator 保留直营份额 ⇒ 守卫通过）。

### D1 需要拍板
1. 选 A / B / C？
2. 若选 C：上表初值是否接受？家户份额的 `kind` 是 `OWNED` 还是 `TENANCY`？

---

## 2. D2：阶层分类与槽位上限

### 推荐规则（D1 选 C 时）

1. **先看直接 `UseRight`**：
   - LAND > 0：净雇工且自耕规模大 ⇒ rich；自足 ⇒ middle；净卖劳动或债务压力高 ⇒ poor；
   - TOOL/WORKSHOP > 0 且自营 ⇒ artisan；
   - 净收稳定地租且不卖劳动 ⇒ landlord。
2. **再看 `ProductionRelation.LaborSource`**（家户没有直接权利时）：
   - `TENANT`：有部分投入/产出且债务不高 ⇒ middle，否则 poor；
   - `SERF`：poor；
   - `FAMILY/SELF`：middle，债务压力高 ⇒ poor；
   - `WAGE`：landless_laborer。
3. **零人口行、无任何观察证据的行**：保留当前 `view`，不伪造阶档；
4. **新阶层**：`landless_laborer/artisan/official` 只追加，旧四档值/parse 不变；
5. **槽位上限**：
   - 若派生阶层的 `participationPerMille` 超过旧 slots 上限：
     - 优先由**家户状态机**调整参与率/劳动配额，再写回；
     - 若本轮不实现状态机调整，保留“合法退化”作为过渡，但必须在 `reason` 里写 `slotCapFallback(...)`，
       且计入 `unmet/readout`，不许静默改字段。

### D2 需要拍板
1. 是否接受“直接使用权优先、劳动关系 fallback”的规则顺序？
2. 槽位超限时：先做参与率调整（改动大），还是先保留合法退化（改动小）？

---

## 3. D3：救济、违约、孤儿债

### 现状
- 信用线收紧后，城镇缺口户拿不到粮，`unmet_grain` 9.86B；
- 没有救济制度；债务人只能靠自己的粮还债，城镇无粮户永远还不上；
- c1 孤儿债 8.45M 在债务人行侧无引用。

### 推荐方案（组合）

| 优先级 | 规则 | 说明 |
|---|---|---|
| ① 同格救济 | 有可贷余粮的家户（不限阶层）可向同格缺口户铸 `RELIEF` 转移；上限 = 可自用余粮扣除一日口粮；不产生 `Debt` | 把“隐性债务”换成可读的实物救济；救急不救穷 |
| ② 邻格/区域救济（可后置） | 同格无余粮时，允许跨格/跨市场区救济；运输走既有在途语义 | 第一版可只做同格，跨格留 P3 |
| ③ 违约判定 | 连续 N 个关账周期 `debtServiceDue > 可用偿付` 且无救济 ⇒ `Debt.defaulted=true`，本金保留、不删除、不静默减记 | 违约后债权人的损失由谁承担要在读口可见；可影响 landlord 阶层 |
| ④ 孤儿债迁移 | 以债务表为权威，重建 `ClassRow.debts` 的引用；或把孤儿债显式标记为 `orphan` 并进入违约/核销流程 | 不允许“遍历不到 = 不存在” |

### D3 需要拍板
1. 是否先做同格救济（推荐），还是直接做违约？
2. 违约后的本金是保留（推荐）还是核销？损失由债权人承担（推荐）还是社会化？
3. c1 孤儿债：重建引用，还是显式标记/核销？

---

## 4. D4：退出、迁移与使用权/劳动去向

### 现状问题
- `UseRight` 没有“所有权人/授予人”维度 ⇒ 退出时权利无处可去；
- `MigrateHousehold` 只改 view hex，不搬使用权/账户；
- 退出后劳动配额可以回池，但“失地/失具”没有表达。

### 选项

| 方案 | 内容 | 优点 | 代价 |
|---|---|---|---|
| **A 保守** | 退出保留原 holder；只释放劳动；不做权利再分配 | 改动最小 | 死权利、无失地/兼并、地主不能收回土地 |
| **B 增加 owner/source 维度（推荐）** | `UseRight` 增加 `ownerActor`/`grantorActor`（或独立 `RightOrigin`）；退出/违约时 holder 可回到 owner、债权人、继承人或集体；迁移时可显式转移 | 能表达失地、兼并、继承、租佃终止 | 状态形状/迁移/codec 改动；需明确“谁有权处置” |
| **C 使用权市场** | 权利作为可交易资产，进入市场或拍卖 | 最完整 | 远超前现代阶段范围，建议不做 |

### 推荐
- D4 选 B：新增 `owner`/`grantor` 语义（命名待定），旧档 `owner = holder`；
- 退出时：`UseRight.holder` 先转移给 `owner`（或债权人，按违约规则），劳动配额回池；
- 迁移时：`MigrateHousehold` 允许 `UseRight` 随人显式转移；账户 location 仍不自动搬；
- 死亡只走人口机制，不从使用权直接推死亡；无生计家户先 `DESTITUTE`，长期才触发迁移/违约。

### D4 需要拍板
1. 选 A / B / C？
2. 若选 B：字段命名、旧档默认规则、退出时权利回到 owner 还是债权人？

---

## 5. 推荐的整体组合

| 决策 | 推荐 | 一句话理由 |
|---|---|---|
| D1 | **C 混合** | operator 保留直营份额即可兼容现有守卫；家户份额让占有关系真正决定阶层 |
| D2 | 直接权利优先 + 劳动关系 fallback；槽位超限先合法退化并留痕 | 既有数据下马上可跑；后续再由状态机调整参与率 |
| D3 | 同格救济 → 邻格救济 → 违约；孤儿债以债务表为权威迁移 | 先把“隐性债务”换成可读行为，再谈违约损失 |
| D4 | **B 增加 owner/source 维度** | 不增加这一维，退出/兼并/失地永远无法表达 |

---

## 6. 实施顺序与验收

> 每个切片只写生产代码、只过编译 + **一次 ≤3 分钟 90 tick 冒烟**；测试仍留 V。

| 切片 | 内容 | 冒烟验收 |
|---|---|---|
| **R3.1** | D1 份额配置 + seeder/迁移拆分 UseRight；守卫兼容 | tick0：`Σ rights == capacity`；living 行出现 landlord/rich/middle/poor 的直接权利 |
| **R3.2** | D2 分类规则 + 槽位合法退化/参与率调整 | 120→240：living 分布四档齐全；每个 class transition reason 可解释 |
| **R3.3** | D3 救济 + 违约 + 孤儿债迁移 | 240→360：repaid > 0 或 defaulted 有解释；孤儿债为 0 或显式标记；conservation 不报负余额 |
| **R3.4** | D4 owner/source + 退出/迁移权利处置 | 240→360：至少一个 CONTRACTING 走到 SUSPENDED/EXITED；权利按规则转移、劳动回池 |
| **V** | 1/4/8、守恒、场景、360 tick 全年、10k 格 | 最终验收 |

**R3 完成的建议判据**：
1. tick0 的 `UseRight` 分布能让 living 行产生 landlord/rich/middle/poor 四档；
2. 0→360（分三段，每段 ≤3 min）无负余额、无守恒异常；
3. `newBorrowing` 不回到 3B 量级；至少有一条 `repaid` 或 `defaulted` 路径真实发生；
4. 至少一个经营者走完 `CONTRACTING → INDEBTED/SUSPENDED/EXITED` 并有权利/劳动去向；
5. 所有读数组件可解释：`classifications/classTransitions/operatorConditions/arrears`。

---

## 7. 请您拍板（勾选即可）

- [ ] D1：A / **B / C**（推荐 C；若 C 请确认份额表与 kind）
- [ ] D2：规则顺序是否接受；槽位超限先做“合法退化+留痕”还是“参与率调整”
- [ ] D3：救济优先（推荐）；违约本金保留（推荐）；孤儿债重建引用（推荐）
- [ ] D4：**B 增加 owner/source 维度**（推荐）；字段名与退出规则
- [ ] 切片顺序 R3.1 → R3.2 → R3.3 → R3.4 是否接受
