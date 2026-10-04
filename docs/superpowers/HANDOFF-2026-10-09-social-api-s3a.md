# HANDOFF 2026-10-09：Social API / S3a 后的工作进度与待办

> 本文件是跨上下文存档。新会话先读本文件，再按以下路径读细档：
> - `docs/superpowers/specs/2026-10-09-social-api-household-architecture.md`
> - `docs/superpowers/specs/2026-10-09-s3a-unit-household-containment.md`
> - `docs/superpowers/plans/2026-10-09-social-api-household-plan.md`
> - `docs/superpowers/plans/2026-10-09-s3a-unit-household-plan.md`
> - `docs/superpowers/plans/2026-10-08-small-world-gov-mint-plan.md`
> - `docs/superpowers/specs/2026-10-08-gov-household-tools-and-money-production.md`

## 0. 当前状态

- 仓库：`/home/cna/SimulatorMosire`，分支 `main`
- HEAD：`48087abe`（已推送 `origin/main`）
- 工作树：干净
- 项目版本：`0.1.0-SNAPSHOT`
- 运行时版本：economy production-runtime = `seven-hex-v2`；class-first = `aggregate-v1`；actor = `actor-v1`

近期提交：

| commit | 内容 |
|---|---|
| `1511dfa3` | S3a Grand/Unit 家户容纳架构与计划 |
| `2b57b4be` | S3a Unit/Gov 家户容纳实现 + GM 工具 + 读口 + 日志 |
| `48087abe` | S3a 测试迁移与验收 |
| `79cfbddb` | S4 家户/人口测试迁移与日志验收 |
| `37f0a900` | HouseholdLocation 全局 changeset 反序列化修复 |
| `fbb49167` | S2 家户状态、率/人口事件、SocialLog |
| `9b6ee4d2` | S1 social-api 契约模块与 ID 迁移 |

## 1. 已完成（重要）

### 经济/家户
- 新模块 `simos-social-api`：`HouseholdId`、`PeopleLotId`、`Sex`、`HouseholdLocation`（HEX/UNIT）、`HouseholdProfile`、`HouseholdView`、`AgeBracketView`、`HouseholdVitalRate(s)`、`HouseholdPopulationEvent`、`HouseholdLookup`、`PopulationLookup`。
- `simos-social`：`Household`（id/location/profile/memberLots/vitalRates）、`HouseholdBook` 生命周期、`SocialData` 5 组件、`PopulationGroup` 已删 `residence`、`SocialLog`。
- `simos-unit` 第 18 组件 `households: List<HouseholdId>`；`GovFormation` 新组件 `households`。
- Social 命令：`social.CreateHousehold` / `SetHouseholdLocation` / `AddHouseholdMembers` / `RemoveHouseholdMembers` / `TransferHouseholdMembers` / `SetHouseholdVitalRates` / `AdjustHouseholdPopulation`。
- Unit 命令：`unit.SetUnitHouseholds`。
- GM 工具：`simos.social.household.create/move/members/rates`、`simos.unit.assignHousehold/detachHousehold`。
- 读口：Unit 详情/列表 households + population 实时汇总；Gov 读口 households。
- 日志：`SocialLog`（`io.mosire.simos.social`）、`UnitLog`（`io.mosire.simos.unit`）；log4j2 配置键 `simos.social.logLevel`、`simos.social.traceLevel`、`simos.unit.logLevel`、`simos.unit.traceLevel`。

### 自适应价格
- `MarketSettlement.MARKET_ADAPTIVE_PRICING_ENABLED = true`（2026-10-07 用户裁定打开）。
- 修过一个真实写回 bug：`EconomySettlement` 曾把工作副本整体重新绑定，价格更新不落盘；现在用 `clear()+putAll()` 回工作表。
- 当前价格底部 `MARKET_PRICE_FLOOR_MILLI = 1`；用户最新要求**取消最低价**（见 §3）。

### GOV 铸币/家户试点（更早批次）
- `Government.seignioragePerCycle` / `debtIssuePerCycle`；`production-runtime-government` profile；GOV 非生产家户；周期铸币/发债写口；1000 tick 跑通。
- 相关文档在 2026-10-07/08 的 spec/plan 中。

## 2. 测试现状与已知基线红

**已绿（本批验证过）**

- `simos-social` 全量：228 通过
- `simos-unit` 全量：472 通过
- `simos-app` 全量：842 通过 / 4 errors / 5 skipped
- 前端门禁：412/412
- S3a 聚焦：`UnitHouseholdContainmentTest` 14、`HouseholdCommandHandlersTest` 8、`UnitHouseholdGmToolTest` 5、`ApiViewsUnitHouseholdTest` 2、`UnitHouseholdLoggingTest` 4（33 通过）
- S2 聚焦：`HouseholdBookTest`、`HouseholdVitalEventsTest`、`SocialHouseholdQueriesTest`、`SocialHouseholdCodecTest`、`SocialApiBoundaryTest`、`SocialLoggingTest` 全绿

**既有经济失败（基线 `1a5e624e` 同样红，不是 S1/S2/S3a 引入）**

1. `AssetShareBook` 质押越界：
   - 失败测试：`RealTwelveHexProductionRuntime3650Test`、`RealTwelveOneTickTraceTest#realTwelveHexLateMarketTrace`、`RealTwelveMarketFreezeDiagnosisTest`
   - 报错示例：份额 `share-farm@2_0-LAND-...-hh-2_0-rural-poor_peasant-...` 质押 22528 > 操作后数量 20015
   - 落点：`simos-economy/.../time/AssetShareBook.java:491`，由 `apply` 触发
2. `SevenHexFullChain3650Test`：商号上一周期运费实收 `expected 0L to be greater than 0L`（`merchantFee`）
3. `SevenHexNatural3650Test`：`ExpectedProfit 的劳动/投入/流动性读数不得为负`
   - 落点：`ModeMigrationPolicy.java:228`（守卫）+ `:977`（构造）

**格式/静态债（非功能，但 `verify` 会红）**

- `spotless:check` 在部分 S2/S3a main 文件报格式违规（unit/social/social-api/app）
- `spotbugs:check` 在 `simos-social-api` 报 4 个 `EI_EXPOSE_REP`：`HouseholdProfile`、`HouseholdView`×2、`HouseholdVitalRates`
- 既有经济失败与这些无关；不要为了让 `verify` 全绿而掩盖真实经济错误

## 3. 用户最新裁定（2026-10-09 追加，必须先记住）

1. **质押按比例跟到新家户的份额**
   - 用户原话大意：“我不是一直说按比例跟到新家户的份额吗”。
   - 经济口径：被质押的资产份额发生转移/拆分/合并时，ACTIVE 质押按比例挂到新份额/新家户上，而不是只留下“数量不够”的守卫错误。
   - 下一步实现点：`AssetShareBook`；需要明确的规则：
     - 拆分：质押按比例拆到新份额；
     - 合并：质押合并到目标份额；
     - 整对象转移：质押跟随到新 owner/operator 的份额；
     - 若新份额已存在同 (industry, asset, owner, operator, kind) 行，按比例累加并保持 `Σ活跃质押 ≤ quantity`。
2. **承运收入必须接到商号账上**
   - 用户的批评：我们派子 Agent 的提示词不够详细，导致承运环节没接到商号。
   - 经济口径：跨格运输的运费必须真实进入商号（MerchantFirm）/承运主体的账户；不能只记 `freightUncollected` 或让 merchant 收入为 0。
   - 下一步排查点：`MarketSettlement` 的承运选择/收费路径、`MerchantSettlement`、`MerchantFirm` 账户、`merchantFee` 读数、7hex 夹具的 carrier pool。
3. **取消粮食最低价，允许价格低到 0**
   - 用户建议：取消最低价，模拟“价格低到 0、没人种粮食、经济崩盘”的局面。
   - 经济口径：自适应价可以一路降到 0；0 表示没有生产者愿意种/卖，市场进入崩溃态。
   - 需要处理的实现细节（待裁定/设计）：
     - `MarketSettlement.MARKET_PRICE_FLOOR_MILLI`：从 1 改为 0（或删除 clamp）；
     - `Market` 构造期当前要求 price > 0；若允许 0，需定义 0 的语义：
       - A. 0 = “无价/不交易”，订单生成/成交跳过；
       - B. 0 = 免费，但这不是用户想要的崩溃语义；
     - `adaptiveNextPrice` 的 `Math.max`、整数四舍五入、价格 1/5 的 5% 步长问题；
     - 下游所有 `priceOf(...) <= 0` 的“缺价”分支要区分“从未定价”和“已跌到 0”。
   - 验收：造一个粮食严重过剩世界，自适应价能跌到 0；随后生产者不再种粮/退出，经济数字体现崩溃，且日志保留跌价过程。

## 4. 其他未完成/下一步

- S3b：Economy/Unit 消费方全面接家户汇总；统一 `ClassRow.population` / `LaborSupply` / `LaborAllocation` 与 Social 家户 ID；`Unit.manpower` 是否切换成家户投影待决定。
- S3c：家户需求、产能、铸币规模的 GM 工具；mint 生产方式（劳动+工具，组织者=政府家户；纸币/信用货币后置；非法仿制 TODO）。
- 世界加载：不做运行时切换；`--world <worldId>`/配置文件字段开机载入；13~17 hex 小世界 + 真实 DB + GUI 的任务按 2026-10-08 计划继续。
- `GovFormation.households` 与 `Unit.households` 目前未自动同步；assign/detach 只写 Unit 列表 + Social location。
- GUI 前端尚未消费新增 households/population 字段。
- 旧世界不迁移；新世界重建。

## 5. 子 Agent 纪律教训（写给下一个控制方）

- 派实现子 Agent 前，必须把**完整架构文档**路径 + **关键形状/命令/失败语义/文件所有权**写进任务书，不能只丢链接。
- 承运 bug 的教训：只写“让运费结算正确”不够，必须钉死“钱从谁扣、进哪个账户、哪张表、哪个读口、哪条日志”。
- 一个阶段一个实现 Agent，只写生产代码、只过 compile；不写/不跑测试；测试单独 Agent。
- 子 Agent 不 commit/push；控制方审后提交。
- 测试验证日志时，必须对照架构文档的事件名/字段/级别，不能按实现反推。

## 6. 快速恢复命令

```bash
cd /home/cna/SimulatorMosire
./mvnw -q -pl simos-app -am -DskipTests compile          # main compile
./mvnw -q -pl simos-app -am -DskipTests test-compile     # 测试编译
./mvnw -pl simos-social,simos-unit -am test              # 社会/单位全量
./mvnw -pl simos-app -Dtest='SocialLoggingTest,UnitHouseholdLoggingTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test            # 日志验收
```

## 7. 文件地图（关键新文件）

- Social API：`simos-social-api/src/main/java/io/mosire/simos/social/api/**`
- Social 域：`simos-social/src/main/java/io/mosire/simos/social/household/**`
- Unit 家户：`simos-unit/src/main/java/io/mosire/simos/unit/**`（`Unit`、`GovFormation`、`UnitState`、`UnitLog`、`spi/SetUnitHouseholdsHandler`）
- App 工具：`simos-app/src/main/java/io/mosire/simos/app/tools/write/SocialHousehold*`、`UnitAssignHouseholdTool`、`UnitDetachHouseholdTool`
- 日志配置：`simos-app/src/main/resources/log4j2.xml`
- 架构/计划：本文件同目录的 `specs/2026-10-09-*`、`plans/2026-10-09-*`
