# HANDOFF 2026-10-09：P2 后端重构 / Social 工单 / 实质化改名后的进度

> 本文件保存当前跨会话进度。新会话先读本文件，再按 §5 的文档地图展开。
> 当前状态：**生产代码 clean compile 绿；测试未迁移；军事俸禄链与总验收先放着。**

## 0. 一句话

- 仓库：`/home/cna/SimulatorMosire`，分支 `main`
- HEAD：`4c104e57`（已推送 `origin/main`）
- 工作树：干净
- 已落地：P0/P1、P2-A~E、Social 工单、Unit 家户单一列表、方案 A 直连边界、通用库存扣除、
  R1~R4 实质化改名
- 未做：测试迁移（`test-compile` 当前预期红）、军事俸禄完整链、P2-F 行政/世界/Log 收口、
  `clean verify`、真实小世界冒烟、前端

## 1. 最近提交链（从上到下）

| commit | 内容 |
|---|---|
| `4c104e57` | 改名 R4：Unit/Government 配置层实质化 |
| `d46b171e` | 改名 R3：生产层实质化 |
| `c7103f83` | 改名 R2：经济家户层实质化 |
| `98a3f141` | 改名 R1：账户/库存层实质化 |
| `8ffb581b` | 通用家户库存扣除 + 税/行政俸禄接入 |
| `2351d044` | 方案 A：unit/economy/army 直连 simos-social，social-api 降为低层身份契约 |
| `c8d52215` | Unit.households 成为唯一家户列表；政府家户 UNIT 位置跟随 |
| `e73d5eb2` | Social 工单 `social.SubmitHouseholdWorkOrder` |
| `0aeb4196` | 设计改为 Social 工单模型，撤销普遍多模块聚合 |
| `dbed8808` | 方案 A / Unit 家户单一列表 / 实质化命名设计 |
| `aab4fe7c` | P2-E 生产红点修复（weave 空壳、组织拆资产、新生批次撞 id） |
| `5652eeb1` | P2-E 测试迁移到 P2 新架构，恢复 test-compile（当时） |
| `0540c383` | P2-D GovDaily/辖区日税/UnitBorrow/UnitRepay 恢复 |
| `a6a16b58` | P2-C 中央/地方政府家户入市与生产 |
| `ef0f325a` | P2-B 多生产方式 + 预期单位劳动小时净收益排队 |
| `870006d6` | P2-A 账户家户化、Membership 归 Social、劳动小时制 |
| `8b4ee222` | P2-A 家户身份/账户底座 |
| `f3d492ec` | P2-0 旧 Java 测试迁出 class-first/manpower |
| `6562c215` | AGENTS §一.10 子 Agent 新架构实验 + 回退 tag `agent-experiment-baseline-20261009` |

## 2. 当前架构口径（用户已裁定）

1. **唯一经济路线**：`production-runtime`（政府内置）；`class-first` 删除；旧线格式报废。
2. **账户只归家户**：`HouseholdInventory` 键 = 家户；庄园/作坊是生产方式，不持账户；
   `ActorKind.ESTATE/WORKSHOP` 退役；旧账户直接报废。
3. **Social 是人员/家户唯一域**：
   - 外部模块不直接 `SocialData.with...`；
   - 改人口走 Social 工单：`social.SubmitHouseholdWorkOrder`（reason + source + plan + target）；
   - 单条人口转移是工单的窄封装；Economy/Unit/Army/GOV 只构造工单或组合命令；
   - 只有确实要求同 revision 原子时，用 app `CommandBus.submitBatch`，不普遍搞多模块 ChangeSet。
4. **Unit.households 是唯一“谁在这个 Unit 里”的实质列表**：
   - `GovernmentFormation.households` 已删；
   - GOV 家户在 `Unit.households` 里恰一个 `hh-gov-<unitId>`；
   - `governmentPostsOfHousehold` / `militaryDutiesOfHousehold` 只是角色配置，键必须 ⊆ `Unit.households`；
   - 政府家户位置用 `UNIT(unitId)`，`HouseholdPositionResolver` 取 unit 当刻位置；
   - `unit.PlaceAt` / 行军 / 迁都后有效位置跟随（当前有 1 tick 对齐滞后，具名缺口）。
5. **模块边界方案 A**：
   - `simos-social-api` 保留为低层人口/家户身份契约（破 `social ↔ economy-api` Maven 环）；
   - `unit/economy/gov/army/sd/app` 允许直接依赖 `simos-social`；
   - `social` 仍禁反向依赖 unit/economy/actor/core 实现；
   - unit/economy/army 目前是“已声明依赖、零 import”，后续业务迁移再用。
6. **通用库存扣除**：
   - `HouseholdStockDeduction` + `DeductionReason`（MILITARY_SALARY / JURISDICTION_TAX / ADMIN_UPKEEP / CORVEE）；
   - `actor.DeductHouseholdStock`（GM 裸账目原语）；
   - app `StockDeductionService`：AccountSession 路径共享扣除；
   - 辖区日税、行政俸禄已接；军队俸禄只留 reason 与通用路径，完整决策/sd 调度未做。
7. **实质化命名**：R1~R4 已完成；JSON 键/命令 TYPE/ID/wire/日志名保留不变。
8. **AGENTS**：
   - §一.10 子 Agent 新架构实验（调查/办事两类、compile 推进、最终总验收）；
   - §四.1 文档冲突以最新落盘为准；
   - §四.2 调查取证默认可提出设计修改，旧 pom/模块禁令不是天花板。

## 3. R1~R4 改名对照

- R1：`GoodsAccount → HouseholdInventory`、`GoodsAccountKey → HouseholdAccountKey`
- R2：`ClassRow → HouseholdEconomy`、`ClassStanding → HouseholdClassMembership`、
  `LaborAllocation → HouseholdLaborCommitment`、`LaborTimeTable → HouseholdLaborTimeTable`、
  `DemandEntry → HouseholdDemand`
- R3：`ProductionOrganization → ProductionEnterprise`、`ProductionUnit → ProductionProcess`、
  `ProductionRelation → ProductionRules`、`Recipient → Payee`、`AssetShare → OwnershipStake`、
  `ClassPosition → ProductionRole`
- R4：`GovFormation → GovernmentFormation`、`GovLevel → GovernmentLevel`、
  `GovernmentHouseholdPost → GovernmentPostOfHousehold`、`MilitaryHouseholdDuty → MilitaryDutyOfHousehold`；
  `householdPosts → governmentPostsOfHousehold`（JSON 键用 `@JsonProperty` 钉旧）、
  `householdDuties → militaryDutiesOfHousehold`（同）

## 4. 当前验证状态

- 每批实现后控制方跑：`./mvnw -q -pl simos-app -am -DskipTests clean compile` → 全部 rc=0。
- R1 做了 ActorCodec 快照/变更集字节比对；R2/R3/R4 做了字符串字面量/record 分量静态比对，
  JSON/TYPE/wire/日志名 0 差异（R4 用 `@JsonProperty` 保旧键）。
- **测试全部未跑**；`src/test/**` 仍大量引用旧类型名/旧 GovernmentFormation 形状/Social 工单前模型，
  所以 `test-compile` 当前预期红。
- `clean verify`、前端门禁、真实 Shell/DB/GUI、小世界冒烟均未跑。

## 5. 文档地图

- 本文件：本批进度
- `AGENTS.md` §一.10 / §四.1 / §四.2：Agent 实验、文档时效、调查可改设计
- `docs/superpowers/specs/2026-10-09-social-direct-boundary-and-substantive-naming.md`：
  方案 A、Unit 家户单一列表、实质化命名、Social 工单修正
- `docs/superpowers/plans/2026-10-09-p2-backend-fixes.md`：P2-A~F 清单
- `docs/superpowers/reports/2026-10-09-cross-module-household-conformance.md`：
  跨模块家户写回/Unit 市场参与/通用扣除调查
- `docs/superpowers/status/2026-10-09-p0-p1-completion.md`：P0/P1 完成汇报
- 更早入口：`docs/superpowers/HANDOFF-2026-10-09-social-api-s3a.md`

## 6. 下一步（用户排定顺序）

1. **测试迁移（下一批）**：
   - 旧测试适配 R1~R4 新名；
   - `GovernmentFormation` 5 参 / `Unit.households` 单一列表 / 政府家户 UNIT 位置；
   - Social 工单 `social.SubmitHouseholdWorkOrder`；
   - `HouseholdStockDeduction` / `actor.DeductHouseholdStock`；
   - Plan A 依赖边界；
   - 先恢复 `test-compile`，再做 targeted tests。
2. **暂放**：军事俸禄完整链、P2-F 剩余行政/世界/Log 收口、`clean verify`、真实小世界冒烟、前端。

## 7. 快速恢复命令

```bash
cd /home/cna/SimulatorMosire
git status --short --branch
git log --oneline -20
./mvnw -q -pl simos-app -am -DskipTests clean compile     # 当前应绿
./mvnw -q -pl simos-app -am -DskipTests test-compile       # 当前预期红，待测试迁移
```

## 8. 已知缺口（防误判）

- Social 工单：`dryRun` 暂拒；`source` 是调用方自报；幂等标记寄居 `populationEvents`，无独立工单表。
- Unit/政府：同 tick 移动有 1 tick 位置滞后；UNIT 家户 `HouseholdEconomy.population` 未从 Social 投影；
  部分 GUI 逐格视图仍走 `social.hexOfLot`。
- 账户/家户：旧档全报废；`ActorData.accounts` JSON 键与 `ActorResolver` 的 `GoodsAccount` 线值仍保留旧名。
- 生产：同一 industry 多 mode unit id 仍冲突；预期利润排序仍是首版近似；承运/无配方活动不进队列。
- 税/俸禄：货币税不入 `FlowRow.income`；军队俸禄未做；行政消耗是 sink；`world-silver` 审计政府保留。
- 方案 A：unit/economy/army 暂无 social import；AGENTS §〇 依赖表仍待回填。
- 格式债：spotless/spotbugs 未跑，旧项目本身有格式债。
