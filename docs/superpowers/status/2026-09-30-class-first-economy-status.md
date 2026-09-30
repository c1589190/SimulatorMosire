# 经济模块重构 · 工作状态文档

> 日期：2026-09-30
> 当前分支：`refactor/class-first-economy`
> 当前 HEAD：`0bc92498`
> 旧分支：`refactor/economy-ideal`（E1–E6 生产代码 + 测试基线 + 债务条件等）

---

## 0. 一句话结论

**运行时已经完成“阶层池 classfirst 唯一结算路径”的替换：旧 `EconomySettlement`/`EconomyDayStepper`/旧参与者/旧 seeder profile 已删除，classfirst 是唯一生产结算；三国紧凑世界 360 tick 已跑通双向阶层流动与守恒。**

仍未完成的是**旧档迁移、残余旧模型表清理、读口全量切换、canonical `clean verify` 的既有 SpotBugs 欠账**。这些属于收尾，不是缺少生产路径。

---

## 1. 分支与提交

### 1.1 `refactor/economy-ideal`（旧分支，已完成 E1–E6 等）

主要提交（按阶段）：

| 提交 | 内容 |
|---|---|
| `9c5105a9` | Codec 旧档关系键幂等 + Seed 异常边界收口 |
| `ae67832c` | R4 夹具迁移 + 0→120 验收（9 债务用例曾 disabled） |
| `ac4cc619` | 紧凑三国测试世界 |
| `7a5fff61` | 理想架构设计 + 重构计划 |
| `31c854eb` / `8b19fa67` | E1a/E1b：生产方式、阶层结构、位置、归属、旧档默认映射 |
| `2a411633` | E2：自动组织生产、租佃/租金、AssetRule |
| `de413539` | E3：政府/货币权威、发行审计、初始禀赋、货币读口 |
| `71c044e9` | E4a：连续 `DebtContract`/terms/unit/pledge 地基，25 组件 |
| `c0a05253` | E4b：F/headroom 信用容量、unpaid 处理 |
| `2b52e180` | E4c：欠款资本化、货币债、粮优先偿还、DebtContractBook |
| `a132a95c` | E5a：清算/危机状态地基、AssetShareBook，27 组件 |
| `0b75bc61` | E5b：清算、阶层下滑、CLASS_DECLINE/DEBT_EXPLOSION |
| `27cab35a` | E6a：模式变迁状态 + `economy.SwitchMode` + 日结算执行 |
| `a7c2c510` | E6b：GM `economy.GmAdjust` + 预览/审计工具 |
| `92992507` | E6c：统一 dashboard 读口 + 29 组件注释收口 |
| `f9b20b61` | 修复 `BufferedAccountTables` 本地写读一致性 |
| `4dd1377d` | 测试基线修复（29→30 前一批漂移、actor legacy id、工具面） |
| `2cc323f3` | P4 720 tick 初始债务生命周期 |
| `27569602` / `a11d192c` | A：到期自动 DEFAULTED + 自动质押 + 清算原语 + 压力 720 |
| `d014de34` / `e438d58c` | P3：初始债务/质押/额外测试条件 + 典型条件入口 |
| `eb2b1c15` | 阶层池/双向流动生产替换计划 |
| `37ad997f` / `e53059f2` | 独立 pilot：阶层池聚合资产状态 + 双向流动 |

### 1.2 `refactor/class-first-economy`（当前分支，直接替换）

| 提交 | 内容 |
|---|---|
| `eb2b1c15` | 阶层先行经济生产替换与双向流动计划 |
| `04c5240c` | 阶层池聚合资产状态 pilot 计划 |
| `37ad997f` | 阶层池 pilot：ClassPool、A/x、上下双向、LandForSale 闭环 |
| `20a2b82d` | 直接替换计划：R1–R3 删除清单 |
| `8ec249dd` | R1：`ClassFirstState` 进 `EconomyData`（29→30）+ 正式 `ClassFirstSettlement` |
| `bf03d105` | R2a：CLASS_FIRST 世界播种 + Timeline 真落盘修复 |
| `fdf6ea4c` | R2b：Shell 唯一调用新协调器；旧参与者无生产调用方 |
| `7d4df877` | R2c：三国世界级 4 池合并 + 出生/死亡接回 + classes 投影 |
| `b243e3d9` | R3a：删除旧结算运行时/旧参与者/旧 profile，64 文件、约 -41k 行 |
| `0bc92498` | R3b：全量测试、360 tick、classFirst 读口、死配置清理 |

---

## 2. 当前运行时结构（classfirst）

### 2.1 唯一调用链

```text
Shell
  └─ ClassFirstPopulationEconomyTimeParticipant
       └─ ClassFirstSettlement.settleOneDay(base, Inputs) -> Result
            ├─ Result.state            → EconomyChangeSet.withClassFirst(...)
            ├─ Result.accountDeltas    → ClassFirstActorWriteback → ActorData
            └─ Result.populationDeltas → ClassFirstPopulationWriteback → SocialData
```

旧路径：

```text
EconomySettlement / EconomyDayStepper / PopulationEconomyTimeParticipant / EconomyOwnershipTimeParticipant
```

已从 `src/main` 删除；`grep` 主代码引用为 0。

### 2.2 权威状态

```text
EconomyData
  ├─ meta
  └─ classFirst : ClassFirstState
       ├─ classPools            Map<ClassPoolId, ClassPool>          // 生产/资产权威
       ├─ householdAccounts     Map<HouseholdProductionAccountId, ..> // 池内人口/劳动/份额
       ├─ accounts              Map<ClassFirstAccountId, ..>          // 双边 DEBT/CLAIM 滚动账户
       ├─ lenders               Map<ExternalLenderId, ..>             // GOV/特殊放贷账户
       ├─ assetStateSchemas     Map<ProductionModeId, ..>
       ├─ classBounds           Map<ClassPoolId, ..>
       ├─ mobilityPolicies      Map<MobilityPolicyId, ..>             // GM 可调政策
       ├─ classFlowEvents       Map<ClassFlowEventId, ..>             // UP/DOWN 审计
       └─ meta                  ClassFirstMeta
```

actor 切片仍持有 `GoodsAccount`（商品/货币账本）；social 仍持有 `PopulationGroup`/城市/人口批次。它们不是旧生产结构，保留。

### 2.3 阶层池核心公式

```text
r_k = asset_k / max(1, req_k)
A_C = 加权几何平均(r) × (min r_k)^{bottleneckWeight}
x_C = clamp((A_C − L_C) / max(1, U_C − L_C), 0, 1)

r_up   = upMin   + (upMax   − upMin)   × x_C^γ
r_down = downMin + (downMax − downMin) × (1 − x_C)^γ

F_up   = P_C × r_up   × O_{C+1}
F_down = P_C × r_down × O_{C−1}
```

- `O` 是目标阶层的吸收系数（可租地、LandForSale、可组织他人劳动容量）；
- 每条 `C→D` 边有 `TransitionBundle`：人口、劳动、流动财产、土地所有权、租约、工具、债权、债务份额；
- 债务跨 tick 累积，按 installment/到期处理；正净额是 claim，负净额是 debt。

### 2.4 GM 可调参数

`MobilityPolicy`：

```text
AssetStateSchema：dimensions / requirements / weights / bottleneckWeight
ClassBounds：L_C / U_C
rates：upMin/upMax/downMin/downMax/γ
caps：UpCap_C / DownCap_C
opportunity：leaseAvailability / landForSale / absorptionPolicy
bundle：TransitionBundle 模板
```

GM/agent 只改源参数，不改人口/阶层/资产/债务派生读数。

---

## 3. 验证证据

### 3.1 测试

- `clean verify -Dspotbugs.failOnError=false` → **BUILD SUCCESS**；
- 12 模块 **2348 tests / 0 failure / 0 error / 0 skipped**；
- 前端门禁 **297/297**；
- canonical `clean verify` → **rc=1**，唯一失败为既有 SpotBugs：
  - `simos-economy-api` 8 条 `USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION`（`MoneyIssuance`）；
  - `simos-economy` 45 条（`EconomyData` 28×EI_EXPOSE_REP、`LegacyHouseholdMigration$Result` 12、`ProductionOrganization` 3、`RentRule` 1、`EconomyAddDemandHandler` 1）。

### 3.2 classfirst 360 tick（三国紧凑世界）

- seed：380,000 人、4 池、628 家户账户、土地 199,366、货币 50,160,000；
- tick120：382,806 人，births 6,367 / deaths 3,561，UP 341 / DOWN 109；
- GM A/B（同一 120→240 起点）：
  - GM：upCap 1→10‰/tick、leaseAvailability 300→800‰ → **UP 360 / DOWN 120**；
  - baseline：**UP 303 / DOWN 98**；
- tick360：382,382 人（+13,254 出生 −10,872 死亡），UP 1,061 / DOWN 348；
- 土地市场：`LandForSale 0 + 放地 34,948 − 购地 34,948 = 0`；
- 守恒：土地 199,366、货币 50,160,000、粮/布、`debt == claim == 0`、账户净额 0；
- 旧投影 `flows/industries/units/relations/laborSupply/allocations` 全程为空。

### 3.3 读口

`ApiViews.economyHex` 新增顶层 `classFirst`：

```text
pools[]（population/labor/资产/债务/A_C/x_C/r_up/r_down/bounds）
landMarket（LandForSale/LeaseSupply/守恒）
accounts[]（DEBT/CLAIM/FLAT、net、利息、nextDue、status）
lenders[]
classFlowEvents（count/up/down/byEdge/最近窗口）
mobilityPolicies[]（GM 全参数 + bundleTemplates + schema）
totals（累计生产/消费/租金/利息/红灯/催收等）
conservation（粮/布/钱/土地/债务=债权/账户净额）
```

旧 `marketReadout`、`classTransitions`、旧 `arrears`、清算审计、`entryOutcomes` 等改为具名 unavailable，指向 classFirst 权威读数。

---

## 4. 残余旧模型表清理清单（未完成）

### 4.1 分类

| 类别 | 表 | 现状 | 清理动作 |
|---|---|---|---|
| ① CLASS_FIRST 世界恒空、无人写 | `flows`、`industries`、`laborSupply`、`allocations`、`relations`、`units`、`operatorConditions`、`shipments`、`memberships` | classfirst 入口丢弃/不写；旧读口/迁移偶尔读 | 先改读口，再全表删除 |
| ② 仍有 GM/agent 写口 | `assetShares`、`demands`、`candidates`、`modeTransitions`/`classShares`、`classes`、旧 `debtContracts` | 命令能写，但 classfirst 结算不读 ⇒ 写了不影响生产，可能造成“看起来有、系统不认” | 删除命令或重定向到 classfirst 等价状态 |
| ③ 读口投影 | `ClassRow`、`FlowRow`、`HouseholdCondition`、`ProductionUnitBook` 等 | ApiViews/GUI/MCP 还在读 | 改成 classfirst 读口后删除 |
| ④ 旧档迁移入口 | 旧 `Industry`/`ProductionUnit`/`AssetShare`/旧 `DebtContract` 的 Codec/Legacy 迁移代码 | 为读旧存档保留 | 先写旧档→阶层池迁移器，再删旧读路径 |

### 4.2 建议清理顺序

1. **旧档迁移器**：旧 `ClassRow`/`Industry`/旧债务 → `ClassFirstState`；
2. **读口重写**：ApiViews 只读 `classFirst`，旧投影只留最小人口视图或删除；
3. **命令重定向/禁用**：`TransferAssetShare`、`AddDemand`、`CancelDemand`、`RegisterCandidate`、`SwitchMode`、`MigrateHousehold`、`forgiveDebt`；
4. **删全表**：从 `EconomyData`/ChangeSet/Codec/StateBuilder/Payloads/SeedHandler 删除上述表；
5. **删模型类**：`Industry`/`ProductionUnit`/`ProductionRelation`/`AssetShare`/旧 `DebtContract` 等及其测试；
6. **canonical verify 全绿**：清 53 条 SpotBugs（先 economy-api `MoneyIssuance` 锁对象，再 economy 防御性拷贝/具名 `@SuppressFBWarnings`）。

---

## 5. 已知边界与风险

1. **世界级 4 池，无 region 维**：跨区域市场/运输后置；
2. **人口学按 `(格,居住类型)` 聚合摊到家户**：classfirst 无年龄/性别维；出生不加劳动、死亡按比例缩劳动，与旧协调器同口径；
3. **classes 是每日只读投影**：`naturalNeeds/effectiveDemand` 按人口比例缩放（近似），不回写 classfirst；
4. **土地/农具无 actor 维度**：actor 写回记具名 gap，只在 classfirst 池层；
5. **旧档未迁移**：classFirst 缺键 ⇒ 空态，参与者交不变变更集、不回退旧路径；
6. **旧模型表仍有写口**：不是纯只读，见 §4.1 ②；
7. **读口回归**：旧市场/旧当日账/旧清算审计已无生产者，具名 unavailable；
8. **canonical verify 仍红**：53 条既有 SpotBugs；
9. **商业/GOV 税/救济/采购未接**；
10. **GM 速率 A/B 已验 120→240；360 验证过，720 未跑**。

---

## 6. 下一步任务清单

### R4a：canonical verify 全绿
- economy-api：`MoneyIssuance` 私有锁对象替代 public static synchronized；
- economy：`EconomyData`/`LegacyHouseholdMigration`/`ProductionOrganization`/`RentRule`/`EconomyAddDemandHandler` 的 SpotBugs 逐条处理；
- 目标：`tools/mvn-lock.sh clean verify` rc=0。

### R4b：旧档迁移 + 残余表清理
- 写 `LegacyToClassFirstMigration`：旧 ClassRow/Industry/旧债务 → ClassFirstState；
- 按 §4.2 顺序删除旧表/模型/读口/命令；
- `EconomyData` 最终趋向只保 `meta + classFirst`（及必要只读投影）。

### R4c：扩展
- region 维/跨区域市场与运输；
- GOV 税/救济/采购、商业生产方式、货币发行/回笼命令；
- 720 tick 长程验收、4/8 线程、旧档回放。

---

## 7. 关键入口索引

| 内容 | 路径 |
|---|---|
| classfirst 结算 | `simos-economy/src/main/java/io/mosire/simos/economy/classfirst/ClassFirstSettlement.java` |
| 阶层池状态 | `simos-economy/.../classfirst/ClassFirstState.java`、`ClassPool.java` |
| 政策/边界 | `MobilityPolicy.java`、`ClassBounds.java`、`AssetStateSchema.java` |
| 新协调器 | `simos-app/src/main/java/io/mosire/simos/app/time/ClassFirstPopulationEconomyTimeParticipant.java` |
| actor 落账 | `simos-app/.../time/ClassFirstActorWriteback.java` |
| 人口落账 | `simos-app/.../time/ClassFirstPopulationWriteback.java` |
| classes 投影 | `simos-app/.../time/ClassFirstClassProjection.java` |
| 读口 | `simos-app/.../gui/ApiViews.java` 的 `classFirst` 块 |
| 计划 | `docs/superpowers/plans/2026-10-01-direct-replacement.md`、`2026-10-01-class-first-production.md` |
| 本状态文档 | `docs/superpowers/status/2026-09-30-class-first-economy-status.md` |

---

## 8. 备注

- 当前工作树在文档写入前是干净的（除本状态文档）；
- classfirst 分支已经可以独立评审：运行时只有一条结算路径，360 tick 有真实双向阶层流动数据；
- 剩余工作是收尾和扩展，不是缺少生产结算路径。
