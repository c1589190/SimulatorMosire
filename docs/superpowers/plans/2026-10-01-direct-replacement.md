# 直接替换：阶层池经济作为唯一结算

> 分支：`refactor/class-first-economy`
> 原则：不影子、不双轨、不保留第二种生产结构。最终 `src/main` 中不得再有旧经济结算被生产调用。

## 旧路径删除清单（最终状态）

以下入口在 R2/R3 完成后必须从生产路径消失或被新引擎替代，不得继续被 `PopulationEconomyTimeParticipant`、`EnergyDayStepper`、`Shell`、`WorldgenInitializeTool` 调用：

- `EconomySettlement` 的周期生产/收获/分配/市场/借粮/计息/偿还/阶层写回；
- `EconomyDayStepper` 的旧会话结算；
- 旧 `Industry` / `ProductionUnit` / `ProductionRelation` / `RegimeOperators` / `CompensationRule` 驱动的生产分配；
- 旧 `HouseholdClassRule` 分类写回；
- 旧家户一对一 `DebtContract` 作为主债务路径；
- 旧 `repayDebts` / `chargeInterest` / `settleOperatorExits` 作为主结算。

保留的只是：
- actor `GoodsAccount`（商品/货币账本）；
- social 人口批次/城市（人口来源）；
- 新的 `ClassPool` / `HouseholdAccount` / `AssetStateSchema` / `ClassBounds` / `MobilityPolicy` / `TransitionBundle`。

## R1：生产包 + 持久状态

1. 把 `simos-economy/.../pilot/**` 的生产逻辑迁到正式包 `simos-economy/.../classfirst/**`（pilot 测试同步迁）。
2. 给以下状态稳定 ID 与模型，并加进 `EconomyData`：
   - `ClassPool`（键 `(modeId, classPositionId)`）——资产向量、人口、劳动、债务、`A_C`、`x_C`；
   - `HouseholdAccount`（池内子账户，人口/劳动/消费/分配份额）；
   - `AssetStateSchema` / `ClassBounds` / `MobilityPolicy` / `TransitionBundle`；
   - `ClassFlowEvent`（上下迁移审计）。
3. 同步 `EconomyChangeSet` / `EconomyCodec` / `EconomyStateBuilder` / `EconomyPayloads` / `EconomySeedHandler`；旧档缺键为空。
4. 新增 `ClassFirstSettlement` 正式入口，签名对齐现有 `EconomyDayStepper` 所需输入输出（economy 状态、actor 账户、social 人口的读写边界）。
5. R1 不切换调用方；但新引擎必须编译、持久化、能被 360 tick 测试直接驱动。
6. 门禁：`-DskipTests compile -pl simos-economy -am`、`-DskipTests compile -pl simos-app -am`、目标 360 tick 测试。

## R2：唯一调用方切换

1. `PopulationEconomyTimeParticipant` 只调用 `ClassFirstSettlement`；删除对 `EconomyDayStepper` / `EconomySettlement` 的调用。
2. `Shell` / `WorldgenInitializeTool` / `EconomySeeder` 改为只播种阶层池状态；旧产业/阶层行/关系不再生成。
3. actor 账户加载/落账仍由协调器负责；social 人口只保留“人口来源”，出生/死亡改由阶层池结算写回。
4. `EconomyDayStepper` / `EconomySettlement` 不再被任何 `src/main` 调用。
5. 门禁：compile + 一个真实 360 tick 集成测试；旧结算入口引用扫描为空。

## R3：删除旧结构

1. 删除旧生产/分配/债务/分类类与其测试夹具：
   - `EconomySettlement`、`EconomyDayStepper`、`ProductionSettlement` 旧路径、`EconomyOrganizationSettlement` 旧路径、`HouseholdClassRule` 旧分类、`DebtContract` 旧家户主路径；
   - `EconomySeeder` 旧 `entries/classes/industries/relations` 生产部分。
2. 迁移旧档：旧 `ClassRow` / `Industry` / 债务 → 阶层池；旧档读入直接转成新状态，不保留旧运行路径。
3. 全量替换门禁：`clean verify` + 360/720 tick + 守恒/重放/旧档迁移。
4. 完成标准：`src/main` 里搜不到旧结算入口被调用；只有 `classfirst` 一条生产结算路径。

## 验收

- 360 tick 阶层池数据保持：双向流动、稳定区、LandForSale 闭环、GM 调参可复现；
- 人口/商品/货币/土地/债务守恒；
- 非必要品缺口只扣效率；
- `src/main` 无 `EconomySettlement`/`EconomyDayStepper` 调用；
- 旧档读入不再回到旧生产路径。
