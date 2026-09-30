# classfirst 原生 GM 经济工具（阶段 1：GmAdjust 重定向）

> 日期：2026-09-30
> 背景裁定（用户 2026-09-30）：决策人工具的前置 = **GM 工具先在 classfirst 上真正可用**。
> 现状（代码实测）：`simos.economy.adjust` / `economy.GmAdjust` 只认 `forgiveDebt` / `setLiquidationPolicy`，
> 写的还是旧 `debtContracts` / `liquidationPolicies` 表；而 classfirst 世界不读这两张表 ⇒ **classfirst 当前没有任何 GM 写口**。
> 来源与口径：`docs/superpowers/plans/2026-10-01-class-first-production.md` §5、`docs/superpowers/specs/2026-09-30-decision-packet-proposal-design.md` §8、
> `docs/superpowers/status/2026-09-30-class-first-economy-status.md` §4.2。

## 0. 阶段目标

让 `economy.GmAdjust`（及其窄封装 `simos.economy.adjust`）成为 **classfirst 权威源状态**的 GM 写口：
`ClassFirstState` 的 `mobilityPolicies` / `lenders` / `accounts` 是权威面（`ClassFirstPilotEngine.restore` 明确读它们：
`ClassFirstPilotEngine.java:371-376`（政策以 state 为准重建 config）、`:420`（accounts）、`:432`（lenders））。
保留：预览 / 原因 / 前后差异 / 审计；GM-only（`GmOnlyCommand`）；MCP 面无脑过（`GmAutoApproveGate`）。
**不得写派生读数**（totals / conservation / classFlowEvents / pools 的派生量一律不碰）。

## 1. 阶段 1 的新增 adjustment

### 1.1 `setMobilityPolicy`

```json
{"adjustment":"setMobilityPolicy",
 "parameters":{"modeId":"tenancy_agriculture"?,   // 缺省 = classFirst.meta.config.mode.id()
               "gamma":?, "upMinPerMillePerYear":?, "upMaxPerMillePerYear":?,
               "downMinPerMillePerYear":?, "downMaxPerMillePerYear":?,
               "upCapPerMillePerTick":?, "downCapPerMillePerTick":?,
               "leaseAvailabilityPerMille":?, "initialLandForSale":?,
               "ticksPerYear":?, "leasePerCapitaMilli":?, "landPurchasePerCapitaMilli":?,
               "absorptionCapTenantPerMille":?, "absorptionCapMiddlePerMille":?,
               "absorptionCapLandlordPerMille":?, "absorptionCapLaborerPerMille":?,
               "extractionTaxPerMille":?, "absorptionPolicy":"PROPORTIONAL"|"ALL_OR_NOTHING"?},
 "reason":"…"}
```

- 语义：把当前 mode 的 `MobilityPolicy` **按给到的字段** upsert（至少给一个字段；未给字段保持原值）；
  key = `MobilityPolicyId.of(modeId)`；不存在 ⇒ 具名拒绝。
- **本阶段不改**：嵌套 maps（`absorptionCapByEdgePerMille` / `bundleTemplates`）与 `schema` / `bounds`
  （两个 record 组件）——给了就**具名拒绝**（不静默忽略）。

### 1.2 `setClassFirstLender`

```json
{"adjustment":"setClassFirstLender",
 "parameters":{"lenderId":"gov-class-first-lender",
               "interestRatePerMille":?, "nextDueTick":?, "collectionPower":?},
 "reason":"…"}
```

- 至少给一个字段；lender 必须在 `classFirst.lenders` 里存在 ⇒ 否则具名拒绝；只改这三个源参数，不动 money/goods。

### 1.3 `forgiveClassFirstDebt`

```json
{"adjustment":"forgiveClassFirstDebt",
 "parameters":{"ownerId":"LABORER", "counterpartyId":"LANDLORD", "unit":"grain"?, "amount":?},
 "reason":"…"}
```

- `unit` 缺省 `PilotModel.GRAIN`；按 `ownerId→counterpartyId` 找**债务人侧**账户（`cumulativeNet < 0`）；
  找不到/不是负债侧 ⇒ 具名拒绝。
- 减免额 = `min(amount（缺省=全额债务）, -cumulativeNet)`；
  **对称**减少两条镜像账户（`owner→cp` 与 `cp→owner`）的 `cumulativeNet`，保证 `debt==claim` 与 `Σ账户净额=0` 不破；
  净额归零 ⇒ `status=SETTLED`；`interestAccrued` 本阶段不动（如实写进类注）。
- 不改库存/商品/货币；不新增/删除账户。

## 2. 旧 adjustment 的处置（本阶段一并做）

- `base.classFirst()` **非空** ⇒ `forgiveDebt` / `setLiquidationPolicy` 具名拒绝（理由指路：
  class-first 世界没有旧表结算路径，请用 `setMobilityPolicy` / `setClassFirstLender` / `forgiveClassFirstDebt`）。
- `classFirst` 为空（旧档 / 尚未播种）⇒ 保持现有行为**逐字不变**（旧的 classfirst-free 世界仍可调整旧表）。

## 3. 落点（生产代码，不写测试）

| 文件 | 改动 |
|---|---|
| `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyGmAdjustments.java` | 新增三个白名单常量 + `project` 分支 + 各 kind 的纯函数实现；类注与 `derivedRejection` 的允许清单同步；旧两 kind 加 classfirst 门 |
| `.../spi/EconomyGmAdjustHandler.java` | 新 kinds 的参数形状校验（类型/必填/至少一项）；异常消息可读、进 `Rejected` |
| `.../classfirst/ClassFirstState.java` | 新增纯 copy-with：`withMobilityPolicies` / `withLenders` / `withAccounts`（保序不可变、冻在赋值处） |
| `simos-app/.../tools/write/EconomyAdjustTool.java` | `description()` 列新的 adjustment 白名单 |
| `simos-app/.../tools/read/CatalogTool.java` | `economy.GmAdjust` 的 `PAYLOAD_HINTS` 同步新白名单 |

- 引擎/结算**不需要改**：`restore` 已以 state 政策/lenders/accounts 为权威。
- 变更集：一律走 `EconomyChangeSet.between(base, projected)`（`classFirst` 组件已是 `FieldDelta<ClassFirstState>`）。

## 4. 验收（开发期：只编译，AGENTS.md §三.0 / §一.5）

1. `tools/mvn-lock.sh -q spotless:apply`；
2. `tools/mvn-lock.sh -DskipTests compile -pl simos-economy -am` 绿；
3. `tools/mvn-lock.sh -DskipTests compile -pl simos-app -am` 绿；
4. 不写/不改任何测试、不 commit（测试与门禁留最后统一做）。

## 5. 非目标（后续阶段，不在本阶段）

- 税率 / 地方债 / 征兵 / 组军（要新状态，属 R4c）；
- 7 条与 classfirst 脱钩的命令收口（`TransferAssetShare` / `AddDemand` / `CancelDemand` / `RegisterCandidate` /
  `SwitchMode`，见状态文档 §4.2）——阶段 2；
- 嵌套 maps / schema / bounds 的逐项编辑（按需再加）；
- GUI 渲染、720 tick 等运行侧工作。

## 6. 交给测试代理的输入（本阶段完成后由控制方转交）

- 行为变化：`economy.GmAdjust` 允许清单 2 → **5**；classfirst 世界里旧两 kind 从"改旧表（无效果）"变成"具名拒绝"。
- 需要的新判据：三个新 kind 的 `preview=apply` 等价与前后差异；只改源状态（派生读数逐值不变）；
  classfirst 门禁（旧 kind 在非空 classFirst 下必拒、空态保持旧行为）；拒绝消息指路；
  对称免债后 `debt==claim`、`Σ账户净额=0` 仍成立；`MobilityPolicy` 未给字段保持原值。

## 7. 阶段 1 裁定追加（2026-09-30，控制方；写代码代理 BLOCKER 回代码核后裁定）

**背景**（写代码代理实测）：`ClassFirstPilotEngine.restore` 只把 state 的 **mobilityPolicy** 写回 `config`
（`:371-375`）；state 的 lenders 只装进 `engine.lenders`（`:432-436`），`config.lender()` 仍是播种值。
而外部货币借款路径读的是 `config.lender()` 的 `interestRatePerMille` / `nextDueTick`（`:1225-1230`）；
`collectionPower` 在全引擎的唯一出现是 `LenderState.snapshot()`（`:2636`）⇒ **无消费点**。

**裁定**：

1. **授权最小引擎修复**：在 `restore` **构造 `new ClassFirstPilotEngine(config)` 之前**，按 mobility 同款模式，
   取 state 里与 `config.lender().id()` 同 id 的 lender；不等则 `config = config.withLender(stateLender)`。
   这样 `setClassFirstLender` 的 `interestRatePerMille` / `nextDueTick` 对下一 tick 新建外部货币债真正生效。
2. **`collectionPower` 本阶段具名拒绝**：不得接受一个"改了不生效"的参数（本仓禁"看起来在记"）。
   等将来有人把催收逻辑接到它上，再单独开一条 kind/字段；拒绝消息要点名"当前引擎无消费点"。
3. 计划 §1.2 的字段清单据此修正为 **`interestRatePerMille` / `nextDueTick`** 两项（其余不变）；
   §6 的测试判据追加：lender 利率/到期改动后**下一 tick 新建债**确实用新值（否则等于没接线）。

