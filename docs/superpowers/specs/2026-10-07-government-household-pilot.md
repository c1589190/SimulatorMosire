# 2026-10-07 政府非生产家户（GOV household）试点架构

> 来源：用户 2026-10-07 原话：“能不能加入政府主体作为不生产家户，其每轮投入市场的东西、需求都是可被 GM/决策人规定的……目前主要需要实现的是，如果政府选择周期性为自己添加货币，然后发行定量的债务，自身拥有定量的粮食/工坊生活消费品等需求，会怎么样？GOV 缺钱直接印就行，试试看把 GOV 主体加入 1000tick 循环。”
> 本批是**试点**，不是完整财政系统。旧档不做迁移；新世界走新 profile。

## 1. 需求、目标、非目标

**目标**
1. 在世界经济里加入一个**不生产**的政府主体；它有人口 0、无 labor、无 unit、无资产份额，但**有家户账户**，因此能像家户一样参与市场与债务。
2. 政府的**需求**由 `EconomyData.demands`（已有 GM 命令 `economy.AddDemand`）规定：粮、布等商品按 `HOUSEHOLD` 范围归到政府家户。
3. 政府的**周期性铸币**由 `Government.seignioragePerCycle` 规定：每个产业大周期开始日，向政府国库账户增发该数量的计价货币，并写一条 `FISCAL_ISSUE` 审计记录。
4. 政府“发行债务”有两条路径：① 市场订单现金不足时走现有 `MarketSettlement` 的 D-030/D-031 信用路径，向家户放贷人借货币/实物；② 新增 `Government.debtIssuePerCycle` 周期发债写口，在周期开始日按固定目标向家户借入货币（真实余额转移 + `DebtContract`），形成 `DebtContract(debtor=govHousehold, creditor=家户)`。
5. 1000 tick 真实 12hex 路径跑通，并输出政府收支、铸币、发债、债务、市场成交读数。

**非目标**
- 不改 `DebtContract` 的 `HouseholdId` 端点类型（本批把政府建模为 `SocialClassId.OFFICIAL` 的非生产家户）。
- 不做税收、财政预算、多币种、多政府、每轮 GM 实时编辑工具。
- 不做政府商品“每轮投入市场”的自动注入。政府投入市场的商品由创世/条件注入的库存决定；库存之外的周期性供给留后续批次。
- 不做旧档迁移；只新增 profile `production-runtime-government`。

## 2. 现状 → 目标

| 维度 | 现状 | 目标 |
|---|---|---|
| 生产方式 | `PRODUCTION_RUNTIME` 只种家户+产业+市场 | 新增 `PRODUCTION_RUNTIME_GOVERNMENT`，在现有播种上追加一个 GOV 家户行 |
| GOV 身份 | `SocialClassId.OFFICIAL` 只在旧档默认结构里有位置 | `official` 槽位作为 GOV 家户的阶层；GOV 无 `ClassStanding`、无 unit、无资产 |
| 货币发行 | `MoneyIssuance`/`MoneyIssuanceRecord` 已有，但运行期自动发行腿只在转账余额不足时出现 | 新增周期开始日的 GOV 铸币写口，落 `FISCAL_ISSUE` 记录 |
| 市场参与 | 家户按 `ClassRow` + 家户账户参与；`DemandEntry` 可注入需求 | GOV 家户与普通家户同路径参与 |
| 债务 | `DebtContract` 两端是 `HouseholdId` | GOV 家户 id 作为 `debtor`，家户放贷人作为 `creditor` |
| GM/决策人配置 | `economy.AddDemand` 已存在；`Government` 无财政旋钮 | `Government.seignioragePerCycle` 落持久状态；需求继续走 `AddDemand` |

## 3. 组件与数据模型

### 3.1 `Government` 新字段
`Government(GovernmentId id, String nationRef, ActorRef treasury, Set<CurrencyId> issuable, long seignioragePerCycle)`
- `seignioragePerCycle`：每产业周期向该政府国库增发的货币量，最小币值；`0` = 不自动铸币。
- 旧 4 参构造器保留，委托 `seignioragePerCycle = 0`；`EconomyPayloads` 缺键也读 0。
- `treasury` 允许 `ActorKind.HOUSEHOLD`：本批政府国库就是 GOV 家户的账户 actor（非生产家户模型的直接后果）。`ActorKind.GOVERNMENT` 仍然合法，class-first 路径不变。

### 3.2 新增 profile
`FoundationProfile.PRODUCTION_RUNTIME_GOVERNMENT("production-runtime-government")`
- 与 `PRODUCTION_RUNTIME` 共用生产 entries/markets/modes/classStructures 等全部载荷。
- 额外追加一个 GOV 家户：
  - 位置：人口最多的格（并列取 `(q,r)` 字典序最小）。
  - `HouseholdId = HouseholdId.ofSeed(hex, URBAN, SocialClassId.OFFICIAL)`。
  - `ClassRow`：population=0、laborMilli=0、participation=0、money=0、needs/demand 空。
  - actor/账户：`HouseholdActors.of(govHousehold)` 的家户账户，初始空；由 `HouseholdSeeder` 与普通家户一起播。
  - 位置不写 `ClassStanding`：GOV 是非生产家户，没有“生产方式位置”。关账日 `HouseholdClassRule` 在无可观察证据时保留当前 view（`official`），不会把它算成生产户。
- `Government`：`GENESIS_GOVERNMENT_ID` 的 treasury 改指 GOV 家户 actor；`seignioragePerCycle = GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI`。

### 3.3 铸币写口
新增包内类 `GovernmentSeigniorage`（`simos-economy/time`）：
- 触发：`day == 1` 或 `(day - 1) % max(industry.cycleDays) == 0`。本世界所有产业 120 天 ⇒ day 1/121/241/…。
- 对每个 `seignioragePerCycle > 0` 的政府、每个 `issuable` 币种：
  - 国库账户余额 `+= amount`（家户账户或经营者账户按 treasury kind 分派）。
  - 写 `MoneyIssuanceRecord(kind=FISCAL_ISSUE)`，id = `gov-seigniorage-<govId>-<day>-<currency>`。
- 不产生 `Transfer`；商品库存不增不减。铸币只改货币余额 + 发行审计。

### 3.4 周期发债写口
新增包内类 `GovernmentDebtIssuance`（`simos-economy/time`）：
- 触发：与铸币同一个“产业大周期开始日”。
- 对每个 `debtIssuePerCycle > 0` 的政府：取第一个 `issuable` 币种，按 `HouseholdId` 规范序遍历人口 > 0 的家户，
  每家可借额 = `余额 − max(0, 人口 × MarketSettlement.LENDER_MONEY_BUFFER_PER_CAPITA_MILLI)`。
- 从家户账户把货币真实移入政府国库，按 `DebtContractBook.upsert` 写 `DebtContract(debtor=政府家户, creditor=家户,
  unit=Money(currency), terms=DebtTerms.legacyDefault(), dueCycle=currentCycle+1)`；政府行的 `debts` 引用同步补齐。
- 目标借不满时借到实际可借额为止，写 `GOV_DEBT_ISSUE_SHORTFALL`（不静默印钱补足、不跳过整条）。
- 它只搬已有货币、不发行新钱；因此逐币种守恒式仍成立。

## 4. 数据流与调用次序

每日 `EconomySettlement.settleOneDayInto`：
1. `MoneyIssuance.syncAuthorities(base.governments())`（已有）。
2. **新增**：若 `GovernmentSeigniorage.isCycleStart(base, day)` ⇒ `settleCycleStart(...)`：国库账户 +钱、`moneyIssuances` +记录；随后 `GovernmentDebtIssuance.issueCycleStart(...)`：家户货币 → 国库、`debtContracts` +政府债务。
3. 其余阶段不变：消费 → 市场（需求单来自 `EconomyData.demands`）→ 借粮 → 偿还 → 计息 → 关账 → 人口。

GOV 的市场路径与普通家户完全共用：
- `DemandTargets.partsForHex` 把 `HOUSEHOLD` 范围需求分给 GOV 行（population=0 也收 TOTAL 需求）。
- `MarketSettlement.ordersFor` 生成全额买单（D-031：家户借款无额度上限）。
- 现金成交用 GOV 账户余额；不足部分走 `creditRound` → `DebtContractBook.upsert(debtor=GOV, creditor=家户)`。

## 5. 接口/契约与失败语义

- `Government` 字段 `seignioragePerCycle < 0` ⇒ 构造期抛。
- `GovernmentSeigniorage` 找不到国库账户 ⇒ 当场抛（不静默跳过；seed 不完整是装配错误）。
- `MoneyIssuanceId` 重复 ⇒ 当场抛（同一政府同一天同一币种只允许一条铸币记录）。
- 需求仍走 `economy.AddDemand` 的既有校验：商品在该格必须有价、HOUSEHOLD 必须存在。
- 旧 payload 缺 `seignioragePerCycle` ⇒ 0，行为不变。

## 6. 版本、激活与重置

- `EconomyMeta.rulesVersion` 仍写 `seven-hex-v2`（本批改新世界初态，不改运行时版本号）。
- 新 profile 只由显式 `economyProfile=production-runtime-government` 激活；默认 profile 不受影响。
- 旧档不迁移；需要 GOV 时按新 profile 重播。

## 7. 验收判据

1. `production-runtime-government` seed 成功，`economy.classes` 比普通 production-runtime 多 1 个 GOV 行（population=0，slot=official），且 actor 侧有对应家户账户。
2. 1000 tick 结束无异常；GOV 的家户账户有货币/商品流水。
3. 日志出现 `event=GOV_SEIGNIORAGE`，`moneyIssuances` 的累计 `FISCAL_ISSUE` 等于 `seignioragePerCycle × 周期数`。
4. `economy.AddDemand` 注入 GOV 粮/布需求后，GOV 出现买单；现金不足时出现 `DebtContract`，`debtor = GOV 家户`。
5. 1000 tick 报表给出 GOV 账户余额、GOV 债务总量、GOV 相关市场成交与未成交原因。

## 9. 2026-10-07 用户追加裁定：打开自适应价格

- `MarketSettlement.MARKET_ADAPTIVE_PRICING_ENABLED` 由 `false` 改 `true`；`PriceMode.ADAPTIVE` 成为默认读侧模式。
- 修复了一个“开关打开但价格不落盘”的写回缺陷：`EconomySettlement` 原来把 `outcome.markets()` 整个重新绑定到局部变量
  `markets`，而 `markets` 只是 `session.sheet().markets()` 的工作副本引用，`EconomyStateBuilder` 仍持旧表。
  现在改为“实例不同才 `clear()+putAll()` 回工作副本”；固定模式下 `outcome.markets() == markets`，不会误清。
- 单家户仍是**价格接受者**：家户不自己定价；它每轮由区参考价现算 `bid = max(1, ⌊p×990‰⌋)`、
  `ask = max(bid+1, ⌈p×1010‰⌉)`，订单按限价过滤、成交按参考价 `p`。自适应只改 `p`，因此改变的是家户下一轮的
  限价窗口、现金可买量与信用需求，而不是家户自己的出价策略。
- 首轮实测（GOV 1000 tick）：自适应确实生效并持久化（iron 10→9、tool 20→9），但 grain 因卖单 26.2 亿毫粮
  vs 买单 34.8 万毫粮、价格已在下限 1 而未动；cloth 在 p=5 时 5% 步长不足 1 毫、整数取整后不变。
  ⇒ 当前世界是“供给过剩 + 低整数价格”，打开自适应价首先表现为**通缩压力/贴地板**，不是通胀。

## 8. 已知缺口与风险

- 本批不做“政府每轮把商品投入市场”的自动写口；仓库中的 GOV 库存只能来自创世/条件注入。
- `addOutputAccrual` 型的商品外部注入不在本批路径；若后续加入必须补齐守恒来源账户。
- `seignioragePerCycle` 当前是 profile 内的具名 GM 默认常量，尚无 GM 实时编辑命令；需求已经可由 GM 命令修改。
- 多政府/多币种下，铸币会向每个 `issuable` 币种各发 `seignioragePerCycle`；本批单政府单币种，语义简单。
