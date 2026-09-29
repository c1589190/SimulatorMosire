# 经济模块理想架构重构计划（2026-09-29）

> **设计依据**：根目录 `POLITICAL_ECONOMY_DESIGN.md`（2026-09-29 复写版）。
> **性质**：可执行重构计划；本文件只规划，不写生产代码。
> **分支**：`refactor/economy-ideal`（从 `26ad8487` / `ts/m1-r3b2` 开出）。
> **适用纪律**：`AGENTS.md`；一次只跑一个 Maven；写代码代理只写生产代码到编译过；测试统一留到最后测试代理；不 `git commit`/`git push` 由写代码代理执行。

---

## 0. 总原则

1. **先换地基，不先改债务。** 先落“生产方式 → 阶层结构 → 家户/人口成分”，再做生产组织、货币、债务、清算与模式变迁。
2. **每阶段可编译，旧行为默认不变。** 新状态为空时，旧经济路径照常跑；旧档显式迁移，不靠自动默认猜键。
3. **不一次性替换 `EconomyData`。** 现有 `Industry` / `ProductionUnit` / `ProductionRelation` / `AssetShare` / `LaborAllocation` / `GoodsAccount` / `MarketSettlement` 作为底层执行机制复用。
4. **权威唯一、派生可查。** `ClassStructure`/`ClassPosition` 由 `ProductionMode` 生成；`HouseholdClassRule` 降级为迁移/校验/审计；派生读数不得变成第二真相。
5. **稳定 ID、整数定点、完整变更集。** 新组件同时进 `EconomyData`、`EconomyChangeSet`、`EconomyCodec` 与反射往返；跨切片写只在 app 的同一 `WorldTimeProposal` 中完成。
6. **债务是连续生产关系。** 同一 `(debtor, creditor, unit, terms)` 一条连续欠账，跨周期不新开条；不同条款不合并。
7. **粮为硬通货。** 实物粮债优先于货币债；没有有效价格/合同价时不得硬折。
8. **还不起可致阶层下滑与 hex 警告。** 起义不在范围。

---

## 1. 分支与基线

- 开分支：`refactor/economy-ideal`。
- 开分支前工作树已有未提交内容：
  - `POLITICAL_ECONOMY_DESIGN.md` 目标架构设计；
  - `simos-economy` 两处生产小修（`EconomyCodec` 关系键幂等、`EconomySeedHandler` 异常收口）；
  - `simos-economy` 测试夹具迁移、9 个债务用例 `@Disabled`、`EconomyCycle120AcceptanceTest`；
  - `simos-app/src/test/**` 紧凑三国测试世界。
- **B0 基线冻结**：按关注点分批提交到 `refactor/economy-ideal`，之后每个阶段从干净 revision 开始。
- 推荐批次：
  1. `fix(economy): Codec 关系键幂等 + Seed 异常边界收口`
  2. `test(economy): R4 测试夹具迁移 + 0→120 验收（9 债务用例暂 disabled）`
  3. `test(app): 紧凑三国测试世界（test-only）`
  4. `docs(economy): 理想架构设计 + 重构计划`
- **诚实边界**：9 个 disabled 债务用例将在 E4/E5 按新语义重写；B0 提交信息必须写明。

---

## 2. 角色与职能

### 2.1 控制方（本会话/控制器）

- 维护计划、裁决兼容与迁移边界；
- 在每个阶段边界只跑：`spotless:apply`（如需）+ `-DskipTests compile`（全仓或相关模块）；
- 审 diff、审行为变化清单、按批次提交、开/关阶段；
- **不自己写生产代码**；不替写代码代理做实现决策；
- 单个模块评审不超过 3 轮；不建重型评审装置。

### 2.2 阶段代码代理（每阶段一个）

- 只写**生产代码**；不写/改测试；不跑 `test`/`verify`；
- 先读 `AGENTS.md`，遵守模块边界与唯一写口；
- 只做到 `-DskipTests compile` 绿；
- 不 `git commit`/`git push`；
- 报告必须包含：
  1. 改动文件与用途；
  2. **行为变化清单**（旧档/旧路径是否逐值不变）；
  3. **受影响的硬编码字面量清单**（给最终测试代理）；
  4. 未做/未验证项；
  5. 越界检查（是否只动声明模块）。
- 一个阶段只派一个代理；阶段过大按“可独立编译的层”拆，不按任务编号拆。

### 2.3 迁移/兼容专项代理（按需）

- 只在 B0、E1、E4 需要时派出；
- 职责：旧 `DebtId` 合并、旧 `ClassRow.view.stratum` 映射、新组件缺键兼容、旧 revision change set 往返；
- 不拓展语义，只做显式迁移与幂等；
- 输出迁移前后 principal/人口/资产守恒证据。

### 2.4 最终测试代理（全部生产代码落地后）

- 按本计划判据清单写测试，不按代码反推；
- 对四类关键项做变异自证：**守恒式 / 身份不丢失 / 静默付 0 / 断粮或缺口**；
- 跑全仓 `clean verify`、阶段场景、旧档迁移、1/4/8 重放；
- 报告必须有“我没做/没验证的”一节；不允许把未跑写成通过。

### 2.5 探针/只读调查代理（按需）

- 只读；用于阶段前的代码事实核对或阶段后的定向数值探针；
- 不修改文件、不 commit；
- 报告区分“静态推断”“实际运行”。

---

## 3. 阶段计划

### B0：基线冻结

- **目标**：把现有未提交工作整理成干净基线；建立 `refactor/economy-ideal` 起点。
- **角色**：控制方。
- **要求**：
  - `git status` 干净；
  - 旧生产路径不变（提交前 `-DskipTests compile` 绿）；
  - 9 个 disabled 债务用例如实标注。
- **验收**：干净分支 + 分批提交 + 编译绿。

### E1：生产方式、阶层结构、家户/人口成分地基

- **目标**：建立 `ProductionMode` / `ClassStructure` / `ClassPosition` / `Household`/`ClassStanding` 权威状态；不改旧路径数值行为。
- **主要状态**：
  - `ProductionModeId`/`ClassPositionId`/`ClassStructureId`；
  - `ProductionMode`、`ClassStructure`、`ClassPosition`、`ClassStanding`；
  - `Household` 或扩展现有 `ClassRow` + `currentClassPositionId`。
- **主要改造**：
  - `ClassRow.view.stratum` 降级为兼容投影；
  - `HouseholdClassRule` 改名/定位为 `ClassPositionResolver`，只做创世/迁移/审计；
  - `PopulationGroup` + `Membership` 保留，新增阶层人口成分聚合读法；
  - 旧档：默认 mode + class position 映射；新状态为空时旧路径逐值不变。
- **代码代理要求**：只改 `simos-economy-api` / `simos-economy` 相关生产代码；新组件同步进 ChangeSet/Codec/往返；报告行为变化清单。
- **验收**：编译绿；旧档空表兼容；新状态存在但旧读数逐值不变。
- **完成后玩家可见**：读口出现生产方式/阶层结构/阶层人口成分。

### E2：生产组织 + 生产资料/租佃/租金

- **目标**：阶层结构自动组织生产；土地/作坊/工具“租来的”成为正式规则。
- **主要状态**：
  - `ProductionOrganization`、`AssetRule`、`RentRule`。
- **主要改造**：
  - 新增自动组织生产阶段：mode + class + labor + assets → `ProductionUnit`；
  - 有劳动有资产必须尝试组织；不足落具名 `SHORTAGE`；
  - `AssetShare` 核心生产资料规则、TENANCY 语义；
  - 租金模板：实物/货币/分成/混合，含 `FIXED_MONEY_RENT`；
  - 窄命令 `economy.SetRelation` / `SetRent`。
- **代码代理要求**：只写生产代码；旧 seed 行为不变；新模板只在显式 mode/relation 下生效。
- **验收**：自动生产；缺资产/投入具名；租金实物/货币腿正确；AssetShare 守恒。
- **完成后玩家可见**：有劳动自动组织生产；佃农/工匠租地/租作坊并支付租。

### E3：货币、政府发行、初始发钱

- **目标**：建立货币权威与 GM 初始化发钱；让钱通过真实收支流动。
- **主要状态/模块**：
  - `Government`、`MoneyIssuance`、`MoneyAuthority` 接入；
  - `simos-gov` 或 economy/actor 中的最小政府账户。
- **主要改造**：
  - `MoneyIssuance` 登记 issuer、发行/回笼；
  - 初始化 `INITIAL_ENDOWMENT` 参数化；
  - 普通账户非负；发行腿单边记账；
  - 货币地租/工资/市场/政府采购与救济接入；
  - 货币分布与发行量读口。
- **代码代理要求**：遵守唯一转移写口；不得绕过 `applyTransfer`；报告货币守恒式。
- **验收**：初始发行总量对账；无 issuer 不透支；有 issuer 可单边发行；集中/回流可观察。

### E4：连续债务合同、信用与清偿

- **目标**：实现 `DebtContract` 连续欠账；支持粮/钱/欠租资本化；实物优先偿还。
- **主要状态**：
  - `DebtContract`、`DebtTerms`、`Pledge`（基础形状）。
- **主要改造**：
  - 同一 `(debtor, creditor, unit, terms)` 连续，跨周期不新开；
  - 货币债创建路径；欠租/欠薪资本化；
  - 信用公式 `F` 与 `headroom = max(0, κF + 抵押/产出权益 − D)`；
  - 粮优先偿还；无价格不硬折；
  - 旧 `debt-cN-...` 迁移：同条款合并、不同条款分开，principal 守恒。
- **代码代理要求**：债务唯一 upsert 写口；利率只增债务不增粮/钱；报告旧档迁移约束。
- **验收**：三种债务可建立；利息/F/债务自增可读；无依据拒贷但缺口保留。

### E5：抵押、清算、阶层下滑、hex 警告

- **目标**：还不起 → 处置核心生产资料 → 阶层下滑 → hex 警告。
- **主要状态**：
  - `Pledge`、`LiquidationPolicy`、`ClassStanding`、`HexCrisisSignal`。
- **主要改造**：
  - 清算阶段放在债务结算之后、阶层写回之前；
  - 核心生产资料按比例处置；保护口粮/种粮/最低生产资料；租佃份额不能由佃户卖；
  - 资产转移 + 债务本金扣减 + 阶层变化同一原子流程；
  - `DEBT_EXPLOSION`/`CLASS_DECLINE` hex 警告；不做起义。
- **代码代理要求**：清算顺序稳定；AssetShare 守恒；不凭空造粮/钱。
- **验收**：有债仍生产；利息超 F 债务自增；无偿付依据拒贷；违约清算与阶层下滑；租佃份额边界。

### E6：生产方式变迁 + 读口/GM 工具/收口

- **目标**：模式变迁可迁移、可保留原所属比例；统一读口和 GM 审计工具。
- **主要状态**：
  - `ModeTransition`、`ClassShare`。
- **主要改造**：
  - `economy.SwitchMode` 登记 transition；日结算组织阶段执行；
  - 成员/劳动/资产/债务/租约/抵押按比例迁移；
  - 旧 unit 进 `EXITING`，新组织生效；
  - GM 经济调整工具：预览/原因/前后差异/审计；只改源状态/外部增减，不碰派生读数；
  - 读口：债务/产出、利息/F、基本缺口、投入缺口、阶层/货币分布、hex 警告；标注时点/本期/累计窗口。
- **验收**：模式切换一条 revision 原子；按比例保留；GM 工具不能改派生读数；普通 GOV Agent 不可调用。

---

## 4. 跨阶段硬约束

1. **Command → ChangeSet → Revision**：不得直接改运行对象或另写存储。
2. **唯一写口**：商品/货币转移只走 `applyTransfer`；资产份额转移只走抽出的 `AssetShareBook.transfer`；债务只走 `upsertDebt`。
3. **模块边界**：economy 不依赖 actor/social/unit/sd/gov/app；跨切片只在 app 协调器。
4. **状态组件全套**：新组件同时进 `*Data`、`*ChangeSet`、`*Codec`、反射往返；旧档缺键 => 空/Unchanged。
5. **确定性**：排序用稳定 ID；1/4/8 线程同分区同提交序；无 wall clock/UUID 影响状态。
6. **守恒**：商品、货币（含发行）、人口、资产、债务逐项可对账。
7. **数值行为变化清单**：每阶段代码代理必须列明哪些字面量/路径会变，测试代理据此改判据。
8. **不顺手修无关模块**：发现其他模块问题，记录、报告，不在本阶段私自扩范围。

---

## 5. 最终验收场景

1. **生产方式 → 阶层 → 自动生产**：新 mode 生成阶层结构；人口纳入后有劳动自动组织生产；缺资产/投入具名。
2. **多户同阶层/人口成分**：一个阶层多户，年龄/性别不同；阶层读数为聚合。
3. **初始发钱 → 货币集中/回流**：GM 发钱；租/市场/工资流动；发行量与流通量对账。
4. **借粮/借钱/借地**：三种债务；条款不同不合并；真实转出粮/钱。
5. **粮优先 + 还不起清算**：粮债优先；利息超 F 债务自增；核心资产按比例处置；阶层下滑；hex 警告。
6. **模式变迁按比例**：成员/劳动/资产/债务按比例迁移；可保留原所属；一条 revision 原子。

---

## 6. 风险与处理

| 风险 | 处理 |
|---|---|
| `ClassRow.view.stratum` 与 `ClassStanding` 双真相 | 明确唯一权威；有 `ClassStanding` 时 view 只做投影；禁止双写 |
| 债务端点 HouseholdId 挂不上 ESTATE/WORKSHOP | 先经 resolver 归户；解析不到的按 unresolved 记录；端点升级后续再做 |
| 没有资产价格却要卖地还债 | 允许合同价/制度账面价；无有效价格则挂账，不硬折 |
| 货币发行破坏总量守恒 | 显式 `MoneyIssuance` 累计发行/回笼；发行腿单边记；普通账户非负 |
| 模式变迁比例迁移复杂 | `ClassShare`/`retainOriginalClassPerMille` 显式状态；旧 unit 不原地改 modeKey |
| 旧档迁移风险 | 每阶段空表兼容 + 显式迁移器 + 幂等 + 守恒断言；最后统一旧档回放 |
| 测试基线不可用 | B0 冻结；最终测试代理统一修 `simos-economy-api`/app 测试编译并跑门禁 |

---

## 7. 当前状态

- 分支：`refactor/economy-ideal`（已开）。
- 目标设计：`POLITICAL_ECONOMY_DESIGN.md` 已复写（目标态 761 行）。
- 本计划文件：`docs/superpowers/plans/2026-09-29-economy-ideal-refactor-plan.md`。
- 当前阶段：**B0 基线冻结**。
- 未开工阶段：E1–E6。
- 控制方下一步：审阅并分批提交 B0 基线；然后派 E1 阶段代码代理。
