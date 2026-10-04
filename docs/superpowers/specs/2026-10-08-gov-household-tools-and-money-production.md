# 2026-10-08 GOV/家户侧 GM 工具与货币生产架构草案

> 来源：用户 2026-10-07 原话：
> “可以开始补齐 GOV 侧的工具了，不过值得一提的是，货币事实上也需要生产，目前阶段添加货币、商品等只能由 GM 通过通用工具调，经济上单个家户（尤其是政府家户）需求、产能也应当是 GM 可随便调整，懂我意思吗？”
>
> 本文是**实现前架构**（AGENTS.md §一.8），先钉形状与边界；实现按阶段落地。

## 1. 需求与目标

1. **GOV 工具面**：GM/决策人可调政府家户的经济政策（铸币、发债等）、需求与产能，不再依赖手写 `economy.GmAdjust` 载荷。
2. **任意家户可调**：GM 可对任意单个家户调整：
   - 需求（`DemandEntry`：商品、量、口径、优先级、有效期）；
   - 产能（生产资料份额/参与率/unit 规模等）。
3. **货币/商品要有生产来源**：
   - 货币不能长期靠 `actor.AdjustAccounts` 或 `FISCAL_ISSUE` 凭空增加；
   - 铸币应成为一条显式的经济操作：消耗劳动/工具/金属等投入，产出货币，并同时写发行审计；
   - 商品同理：通用 GM 加账是**世界编辑覆盖**，不是经济生产。
4. **所有调整仍走命令链**：`Command → ChangeSet → Revision`，有 preview/apply、reason、审计、资源围栏与 fail-closed。

**非目标（本批不做）**
- 不做完整央行/银行体系、准备金、汇率、多币种兑换。
- 不做完整财政预算/税收制度；GOV 工具先做“可调”，制度公式后续再补。
- 不把 GM 世界编辑口完全删除；它保留为具名 `world-editor` 覆盖，但与经济生产路径分开标注。

## 2. 现状（改前）

| 能力 | 现状 |
|---|---|
| GOV 铸币/发债政策 | `Government.seignioragePerCycle/debtIssuePerCycle` 已是持久状态，但只能由 seed 写 |
| GOV 国库钱/货 | actor 侧家户账户；`ActorAdjustAccountsTool` 可按有符号净增量调账（通用工具） |
| 家户需求 | `economy.AddDemand` / `CancelDemand` 命令已存在；没有 GM 窄写工具封装 |
| 家户产能 | 产能拆在 `Industry.capacity` / `AssetShare` / `ProductionUnit` / `LaborAllocation`；没有单一 GM 调整口 |
| 货币来源 | `MoneyIssuanceRecord(INITIAL_ENDOWMENT/FISCAL_ISSUE/WITHDRAWAL)` 是审计；铸币结算直接记账户，不消耗投入 |

## 3. 工具与命令设计

### 3.1 Phase G1：`setGovernmentPolicy`（GOV 政策）
- 落在既有 `economy.GmAdjust` 命令上，新增 adjustment：`setGovernmentPolicy`。
- 参数：
  - `governmentId`（必填，必须已存在）；
  - `seignioragePerCycle?`（≥0，毫货币/周期）；
  - `debtIssuePerCycle?`（≥0，毫货币/周期）；
  - 至少给一个；`reason` 必填。
- 语义：只 upsert `EconomyData.governments` 对应政府的两个政策字段，不动国库余额/库存/债务。
- App 工具：`simos.economy.gov.policy`（preview/apply、GM-only、资源 `economy:*`）。
- 后续字段（另开 kind 或扩展）：税率、支出目标、储备目标。

### 3.2 Phase G2：`setHouseholdDemand`（家户需求）
- 基于既有 `economy.AddDemand` / `economy.CancelDemand` 命令，新增 App 工具 `simos.economy.household.demand`。
- 动作：`upsert` / `cancel` / `list`。
- 参数：`householdId`（或 `hex` + 摊分口径）、`commodity`、`quantityPerCycle`、`unit(TOTAL|PER_CAPITA)`、`kind(RECURRING|ONE_OFF)`、`priority`、`createdDay?`、`expiresDay?`。
- 只写 `EconomyData.demands`；GOV 家户与普通家户同一入口。

### 3.3 Phase G3：`setHouseholdCapacity`（家户产能）
产能不是单字段；本工具的**第一批**只做最有经济意义、已有状态模型支撑的三件事：

1. `participationPerMille`：改 `ClassRow.participationPerMille`（劳动参与率）。
2. `assetShares`：按 `(industry, asset, rightKind)` upsert 该家户名下的 `AssetShare.quantity`（可新增 OWNED/TENANCY/COMMUNAL 份额）。
3. `unitScale`：对 `ProductionUnit` 的 `operator == 该家户` 的 unit，调整其占用的资产份额/规模（经 AssetShare，不直接写虚构字段）。

- 落点：新增 `economy.GmAdjust` adjustment `setHouseholdCapacity`，参数：
  ```
  householdId, participationPerMille?, assetShares?[{industry, asset, quantity, rightKind?}], reason
  ```
- 守卫：家户存在；资产种类/产业存在；数量 ≥0；`Σ份额` 与 `Industry.capacity` 的关系先按现有模型**只告警不截断**（是否设硬上限待用户裁定）。
- 非目标：不在本工具里新建/删除 unit、不改生产模式/阶层归属（那走 E2/P7 既有 kind）。

### 3.4 Phase M1：货币生产（铸币操作）
把“印钱”从结算直接加余额，改成一条**显式铸币**：
- 新增持久规则（暂名 `MintPolicy`，或扩展 `Government`）：
  - `mintInputsPerUnit`：每发行 1 个货币单位需要的投入（劳动、工具磨损、金属/商品）；
  - `mintOutputPerCycle`：本周期可铸上限；
  - `mintActor`：铸币经营者（GOV 家户/官署）。
- 执行：在日/周期结算里作为一条特殊生产：
  1. 从铸币主体账户扣投入（商品 + 劳动配额/资产占用）；
  2. 记 `ProductionLedger` 的投入/损耗；
  3. 写 `MoneyIssuanceRecord(FISCAL_ISSUE)`；
  4. 货币记入目标账户。
- `GovernmentSeigniorage` 保留为“政策开口”，但政策量必须受 `mintInputsPerUnit` 与投入库存约束；投入不足 ⇒ 铸不出（记录 shortfall），不是自由加钱。
- 通用 `actor.AdjustAccounts` 继续存在，但只作 GM 世界编辑；建议在工具结果/审计里加 `world-editor` 标记，与生产路径区分。

### 3.5 Phase M2：商品生产来源
- 商品数量变化只能来自：生产配方、收获、合法转移、损耗、显式 GM 世界编辑。
- GM 增加商品应走“世界编辑”工具，并可选落一条具名审计；经济上建议逐步由生产方式/贸易替代。

## 4. 数据流与调用次序（M1 落地后）

```
周期开始
  → 读 Government/MintPolicy
  → 有铸币政策：检查投入（劳动/工具/金属/商品）
  → 够：扣投入 + 记 FISCAL_ISSUE + 记目标账户
  → 不够：写 shortfall，不铸
日结算其余阶段不变
```

## 5. 失败语义

- 政策/产能/需求参数缺失、负值、引用不存在 ⇒ 具名拒绝（JSON 层 `BAD_REQUEST` / 命令层 `Rejected`）。
- 铸币投入不足 ⇒ 不铸 + `shortfall` 读数；不得透支投入账户。
- `governmentId` / `householdId` 不存在 ⇒ 拒绝，不静默跳过。
- 通用 GM 加账与生产铸币在审计上必须可区分。

## 6. 版本与重置

- 新字段进 `EconomyData` 时同步 ChangeSet/Codec/往返；旧档不做迁移，按用户既定口径 GM 重置。
- 新工具只走 GM 桶；决策人工具面后续按权限单开。

## 7. 验收判据

1. GM 工具可设置 GOV 家户的 `seignioragePerCycle` / `debtIssuePerCycle`，下一周期按新值执行，并有前后差异/审计。
2. GM 工具可给任意家户 upsert/cancel 需求，市场订单路径下一轮生效。
3. GM 工具可调家户参与率与资产份额；结算读到的规模/产出随之变化。
4. 铸币操作在投入不足时铸不出；投入充足时货币总量增加 = `FISCAL_ISSUE` 总量，且投入确实被扣。
5. 所有调整走命令链，preview 不写状态，apply 落 revision。

## 8. 待用户裁定

- 家户产能是否允许 `ΣAssetShare > Industry.capacity`（GM 世界编辑优先，还是硬上限）。
- 铸币投入的计量口径（劳动/工具/金属/粮食；是否允许纯信用铸币）。
- `MintPolicy` 放在 `Government` 字段还是新组件。
- 通用 GM 加账是否要加 `world-editor` 审计标记，以及是否需要“只允许 GM 桶”的硬围栏。
