# D-032 二元农村生产关系 v4：可行性与派单顺序

> **状态：控制方可行性复核 + 责任区派单顺序；不是实现验收，不是新玩法裁定。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`（分支 `parallel/side-module`）。
> 本文不改主树。当前工作树已有上一段未提交改动；本批先设计，不写生产代码。
>
> **2026-10-20 执行更新（RC0a）**：RC0a 已由实现 Agent 完成，`simos-economy,simos-app -am -DskipTests compile`
> rc=0。市场已移到 S6 工资之后，S7-M2 债务清算已后移，同日新工资债按合同 id cutoff。完整 v2
> `DailyDemandBill`/实际盈余卖盘仍是独立 v2 收尾项，不再作为 v4 R1–R6 的硬阻塞；**RC0b 不再单独派单**。
>
> 父目标运行序列：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` 的 S0–S8。
> 本主题设计：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md`。
> 用户原话证据：见 v4 §1、v2/revision 的原话附录；本文 §8 再次逐字收录本派单直接相关的原话。

## 1. 先看结论

**v4 在模块边界与当前代码上“有条件可行”。**

- **R1–R6 核心机制没有再发现必须让用户新裁的玩法口径**；下面列出的都是可由现有原话推出的不变量，或工程默认。
- **但当前代码的日序不是 v2 的 S0–S8，而是旧的“市场先、生产后”链**：

```text
当前 EconomyDayPipeline：
M2 债务 → M3 预期编排 → M4 市场 → M5 消费 → M6 实际编排 → M7 生产 → M8 工资
```

而 v4 依赖：

```text
S3 位置选择 → S4 生产日结 → S5 收获 → S6 工资 → S7 市场 → S8 事实回传
```

⇒ **RC0（S3–S8 主链对齐）是 v4 的硬前置**，不是可选优化。若不做它，S7-B 的“买到土地后本轮不用、下一生产 tick 才可用”和“市场发生在工资之后/生产之后”在结构上都不成立。

- **v4 §8 的同阶级整组织交易（LAND + unit + roster）本批不派实现。** 它是独立的企业/组织交易问题；当前 v4 没有交易对价、现金腿、债务腿和非 LAND 资产估值规则。先把 R1–R6 核心机制做通，§8 另开约束设计；不把它塞进任何 v4 机制责任区里“顺手做掉”。

## 2. 目标运行序列（v4 嵌入 S0–S8 的唯一位置）

| 阶段 | 触发 | 本主题相关行为 | 读 | 写 | 失败/边界 |
|---|---|---|---|---|---|
| **S0 Social** | 每 tick | 人口/劳动/温饱权威结算 | Social 权威、上一 tick 事实 | Social 切片 | 失败整次 advance 不落 revision |
| **S1 经济载入** | 每 tick | 载入活家户、class position、LAND、文化键的只读投影 | Social、economy、production contract、culture lookup | economy 会话投影 | 缺行/投影不一致 = 契约故障 |
| **S2 进入/迁移/组织** | 每 tick | 先合并同 key 家户；再按 class nature 做位置/组织准备 | 人口、LAND、class position、culture key | economy 会话；production 实例候选态 | 业务拒绝具名；不静默丢户/丢资产 |
| **S3 位置选择** | 每 tick | `LANDED` 只占 landed/owner-operator 位置；`LANDLESS` 只作为 hired labor 响应；未受雇即 idle | 生产定义、class nature、劳动预算、LAND、工资锚 | 瞬态位置计划/雇佣位置 | 禁止 `LANDLESS` 占 owner/operator；禁止 `LANDED` 冒充 wage laborer |
| **S4 生产日结** | 每 tick | 按 v2 的实际播种/日粮/劳动/日效率执行；本批不改公式 | 实际土地、库存、劳动、账单 | 库存、日账单、效率读数 | 生产轮不结算债务 |
| **S5 收获/毛产** | 周期末 | 按 v2 毛产和土地份额分配；本批不改公式 | S4 事实、production 状态 | 毛产、账本 | S5 不二次扣库存 |
| **S6 工资** | 每实际劳动 tick | v2 工资结算，必须发生在 S7 市场之前 | roster/实际劳动、工资锚、雇主现金 | 工资支付/工资债 | 只货币；未付成普通 0‰ 货币债 |
| **S7-A 市场撮合** | 市场日 | 债务最先；商品 + LAND 同一订单簿；LANDLESS 买方可以成交并**临时**持有本轮 LAND；classness 仍 LANDLESS | 账单、LAND、市场价、债务、cash/wealth | 商品/LAND/货币/债务/市场统计 | 本 tick 新工资债不进本 tick S7-M2；临时 LAND 不得持久化 |
| **S7-B PostLandTradeClassCheck** | 紧随 S7-A，同一 revision | 对本轮 LANDLESS 买方逐 hex：把本轮取得的全部 LAND + 按比例人口/钱/货/债转到该 hex 的对应 LANDED 户；LAND 清零 | `landAcquiredQuantityThisRound`、class nature、culture key、LAND 市场成交、Social 单户温饱线 | LAND working copy、ped 家户、钱/货/债、population outbox | 目标不存在 ⇒ 具名 fail-closed；不允许以“临时 LAND”落盘 |
| **S7-C 向下流动** | 每市场轮最多一次，B 之后 | `LANDED` 户按自己的 flow table，在债务扩大时向同 hex 的 LANDLESS 户迁人口 + 按比例钱/货/债；LAND 不随人走 | flow table、债务本金、人口、库存、文化键 | flow table、家户、钱/货/债、population outbox | 不得跨 hex；不得指向 LANDED；保留 `MIN_LANDED_POPULATION` |
| **S8 Social 事实回传** | 每 tick 末 | 本 tick S4 已发生温饱/缺口回传 Social；S7 买入只能成为下一 tick 的未来库存 | S4 事实、S8 槽 | Social 事实槽 | S7 交易不得回写本 tick S4 |

**原子性**：S7-A/B/C 必须在同一 `EconomySession` 内完成；不得在中间 `preview()`/`build()`；任何失败整次 advance 回滚，不留下 `LANDLESS + LAND>0` 或半合户。

## 3. 可行性审计（回代码核过的事实）

| 问题 | 当前事实（file:line） | 结论 / 处置 |
|---|---|---|
| `ClassNature` 放 production contract 是否越界 | production 只依赖 economy-api；economy 显式依赖 production contract；culture 也依赖 production | **可行**。类型放 `simos-production/contract`，不在 app/economy 拼第二套字符串 |
| mode 级单一 class nature 是否够 | `grain_farm` 一个 mode 有 `operator + hired_laborer` 两个位置；`DefaultProductionModes.java` / `IndustrySeeder.java` 均如此 | **不够**。用位置级 `ClassNature`：`operator=LANDED`，`hired_laborer=LANDLESS`；mode 只暴露允许的 `Set<ClassNature>` |
| classness 放哪里 | `HouseholdClassMembership.currentPositionId → ProductionRole.modeId`；当前没有 class nature 字段 | **派生，不新增持久 Household 字段**：由 current class position 的 `ClassNature` 派生；同户多位置若 class nature 冲突 ⇒ 契约故障 |
| economy 不能依赖 culture，但 R1 合并键要 cultureKey | `simos-economy/pom.xml` 禁 culture；`CultureData` 已有 typed tag 但无 label | **加只读端口**：`economy-api` 放不透明 `HouseholdCultureKey` + `HouseholdCultureKeyLookup`；`CultureData` 实现；App 装配进 `EconomySession`。culture 仍拥有标签 |
| `HouseholdCulture` 无自然语言标签 | `HouseholdCulture.java` 只有 `(household, tag)`；`SetHouseholdCultureHandler` 只读 typed tag | **可行**：新增 `label`（非 null；缺省空串），payload 可选 `label`，Codec 由 record 形状自动覆盖，测试后置 |
| LANDLESS 买方临时持地 | `MarketTradeExecutor.executeLandTrade` 直接把 LAND 记买方；`SettlementDayMarketPipeline` 在 S7 后写回 `session.sheet().landHoldings()` 并登记 `landAcquiredThisRound` | **可行**：在 `executeMarketRound` 写回工作副本后、下一次 `build()` 前做 S7-B；全部 LAND 转出后清 marker/持有 |
| 同 key 合户与 organizer rebind | production 已有 `ProductionRuntime.reoperateInstance`；`ProductionRosters.mergeHousehold` 只合并名册；`MigrationPopulationMove` 遇 organizer 直接拒 | **可行但最重**：需 economy 侧合户 rebind（unit/enterprise/relation/asset/labor/occupancy/reservation 等引用）；不能只改名册 |
| `MAX_HOUSEHOLD_POPULATION` | 仅 `ModeMigrationPolicy.java` 与 `ModeMigrationCandidateBook.java` 引用 | **可直接删**：existing target 改为“无人口上限”，new target 的规模仍由资产/劳动可行性限制；不要保留隐藏 200 人夹紧 |
| 债务清算会把 LAND 转给任何债权人 | `DebtFirstSettlement.java:444-451` 无 class filter | **已有用户原话可推出**：向上只能通过市场买地。LANDLESS 债权人不得经债务清算取得 LAND；跳过 LAND 腿、余债保留、LAND 留债务人/市场 |
| 32hex 两户世界 | 当前 `PopulationSeeder` 固定四阶层 + displaced；`Rural32HexWorld` 仍 160 unit/roster | **可改，但跨多个 seeder**；政府/市场区可保持；测试夹具改动留给测试 owner |
| 当前主链 | RC0a 已把 `EconomyDayPipeline` 重排为 `S3 → S4 → S5 → S6 → S7-M2 → S7-A`；同日工资债 id cutoff 已接上 | **RC0a 已完成**；完整 v2 需求账单/实际盈余卖盘另列 v2 收尾，不阻塞 v4 |
| 同阶级整组织交易 | v4 §8 只有 LAND+unit+roster 的包形状；没有对价/支付/债腿 | **本批不派**；独立设计后再开责任区，避免实现方自选交易语义 |

## 4. 本批工程默认（冻结到任务书，用户可随时推翻）

这些不是新的玩法裁定；没有用户反对时，按此实现，避免实现方各写一套。

1. **位置级 classness**
   - `simos-production/contract/ClassNature`：`LANDED` / `LANDLESS`。
   - `ProductionModeDefinitionView` 增 `Set<ClassNature> supportedClassNatures`（非 rural mode 可为空）。
   - `ProductionRole` 增 `classNature`；`grain_farm.operator=LANDED`、`grain_farm.hired_laborer=LANDLESS`。
   - 家户 classness = current position 的 class nature；`participatingPositionIds` 若存在，所有有效位置的 class nature 必须一致，否则契约失败。

2. **合并键与 survivor**
   - `mergeKey = (hex, cultureKey, productionMode, classNature)`。
   - survivor：人口最多 → LAND 最多 → `HouseholdId` 最小。
   - 源户合并后保留 0 人口壳（不丢稳定 ID/Social 落点）；所有资产/债务/名册/经验并到 survivor，不得静默丢。

3. **文化键**
   - `HouseholdCultureKey = (cultureId, religionId, ordered custom tags, label)` 的规范不透明串。
   - `label` 非 null，缺省空串；`label` 缺省不猜内容，日志 `CULTURE_LABEL_MISSING`。
   - 同 key 才允许 R1 合并；culture 模块只读实现 lookup，不把文化状态塞进 economy 第二本账。

4. **S7-B 多 hex 人口/钱/货/债拆分**
   - 每个 hex 按本轮成交 LAND 数量为权重；
   - 人口用稳定序 + 最大余数法；钱/货/债按同一人口比例拆；
   - 不能拆的债务具名留在源户；不得静默丢债；
   - `MIN_SOURCE_POPULATION = 1`，`MIN_LANDED_POPULATION = 1`（初始工程默认）；
   - `migrationRatePerMille` 沿用既有 `100‰/市场轮` 工程常量。

5. **向上迁移量的参数**
   - `netGrainPerMu`、`dailyNeedPerPerson`、`cycleDays`、`outputPerMu`、`seedPerMu` 从 production/Social 定义读，不写死 120/0.8。
   - `landlessWealth = money + goodsAtMarketPrice`（LANDLESS 无 LAND，LAND 项自然为 0）。
   - `entryCostPerPerson = ceil(minLandPerPersonMilliMu × landPricePerMu / 1000)`；地价为 empty 时 `maxByWealth = Long.MAX_VALUE`，0 价时用 `max(1, …)` 防除零；实际仍受 LAND/速度/源人口上限约束。
   - 价格接受者/现金不足成普通债沿用 RF1，不因现金/地价改挂单数量。

6. **向下流动**
   - 每市场轮最多一次；触发 = 该 LANDED 户当前未偿债务本金 > flow table 的 `lastObservedDebtPrincipal`。
   - 目标 = 同 hex、同 cultureKey、同 productionMode、classNature=LANDLESS 的户；多个同 key 先按 R1 合并；没有则按稳定 id 建一个 0 人口 LANDLESS 壳。
   - 流量 = `min(max(1, floor(sourcePopulation × 100 / 1000)), sourcePopulation - MIN_LANDED_POPULATION)`；LAND 不随人走。
   - flow table 必须持久化，且 target 不跨 hex、不指向 LANDED、不指向不存在 id；每轮维护。

7. **测试世界**
   - 32 hex；每个**农村池** 1 个 LANDED + 1 个 LANDLESS 农户；人口默认 160/40。
   - 无 displaced 农户；`displaced` 目录定义可留，但不得播种流民户。
   - 政府记录/国库户/1 个市场区保持既有配置；首都 700 城镇人口与既有城市池本轮不重写（机制只作用于农村位置）。
   - 初始 LAND 全归 LANDED 户；LANDLESS 户 LAND=0。
   - 初始统一文化标签（culture 模块写一条默认标签；不得靠“无标签 = 同文化”的隐含推断）。
   - 这条“首都城市池暂不重写”是工程默认，不是新玩法裁定；若要清空城市池，另开 seed 任务。

8. **合户/单位重绑**
   - 使用 production 既有 `reoperateInstance` 改 unit operator/mode key，不新增第二套 rebind 契约；
   - economy 侧必须同 revision 更新 `ProductionEnterprise.organizer/laborSources/assetSources`、`ProductionRules`、`AssetShare` owner/operator、roster rights/participant、labor commitment actor/id、occupancy/reservation 引用、wage carry/demand/debt 引用；
   - 不能只改名册后留下 organizer 半状态。

## 5. 责任区派单顺序

> 一个责任区 = 一个写代码 Agent；只写生产代码，写到 `-DskipTests compile` 过；不写/不跑测试；不 commit；一个文件一个 owner。
> 每个责任区正式派单前仍须有自己的约束设计书/任务书，本表只确定**顺序、边界与依赖**。

| 顺序 | 责任区 | 交付能力 | 依赖 | 编译门 |
|---|---|---|---|---|
| **RC0a ✅** | **v2 S3–S6 前移** | 生产位置选择、消费/实际编排/劳动/投入/生产、收获、工资前移到市场之前；债务清算移到工资后；同日工资债 id cutoff | 无（v2 父设计已冻结） | 已完成，compile rc=0 |
| ~~RC0b~~ | ~~v2 S7–S8 后移~~ | 已由 RC0a 覆盖日序；完整 v2 `DailyDemandBill`/实际盈余卖盘另列 v2 收尾，不阻塞 R1–R6 | — | 不单独派单 |
| **RC1 ✅** | **共享契约** | production `ClassNature`/mode allowed set/position field；culture `HouseholdCulture.label`；economy-api `HouseholdCultureKeyLookup`；`CultureData` 实现 lookup | RC0a 已 compile 绿 | 已完成，compile rc=0 |
| **RC2a ✅** | **class nature 接线与 S3 门控** | `ProductionPayloadSeeder` 发 mode classNatures + position classNature；`EnterpriseOrganizationBook` 不自动组织 LANDLESS；S3/S2 候选按 class nature 拒绝“LANDLESS 占 owner/ProductShare”和“LANDED 占 MoneyWage”；共享 class nature 派生 helper | RC1 | 已完成，compile rc=0 |
| **RC2b ✅** | **32hex 二元 seeder** | 农村 2 个 `SocialClassId`（landed/landless）；每农村池 160/40、无 displaced；LAND 全归 landed；landless 无自营 unit、作为 hired labor；城市池/政府/市场配置保持 | RC2a | 已完成，compile rc=0 |
| **RC3 ✅** | **人口/钱/货/债迁移原语 + 删人口上限** | `HouseholdTransferSettlement`；`MAX_HOUSEHOLD_POPULATION` 删除及 room/allocate 无上限 | RC1；RC2 给 class key | 已完成，compile rc=0 |
| **RC4-A ✅** | **mergeKey/ survivor 计划 + culture lookup 接线** | 只读 merge key、survivor 规则、同 key 分组；EconomySession/App 接 HouseholdCultureKeyLookup | RC2/RC3 | 已完成，compile rc=0 |
| **RC4-B ✅** | **production 周期贡献 rebind** | `rekeyCycleContributions(unit,from,to)`；同 role labor/weighted 精确相加；不改 operator/progress/input | RC4-A | 已完成，compile rc=0 |
| **RC4-C ✅** | **economy 结构 rebind** | enterprise organizer/laborSources/assetSources/input/output；relation operator/payees/recipients；assetShares 重键；roster rights；不搬 LAND/钱货债/人口 | RC4-B | 已完成，compile rc=0 |
| **RC4-D ✅** | **merge 编排 + S2/App 接线** | LAND LandBook；RC3 钱货债经验；双向 debt rekey/self-net；occupancy/reservation/demand/wageCarry/shipment buyer；source 0 人口壳；S3 前置合并 + App Social 桥/投影刷新；demand 当刻读口统一；旧 organizer guard 保留 | RC4-C | 已完成，compile rc=0 |
| **RC5 ✅** | **债务清算 LANDLESS 债权人过滤** | S7-M2 LAND 腿只允许 LANDED 债权人；LANDLESS 跳过 LAND、余债保留、LAND 回债务人/市场 | RC1/RC2 | 已完成，compile rc=0 |
| **RC6 ✅** | **S7-B 无地买地阶级检查（R2/R3）** | 本轮 LANDLESS 买方逐 hex；同 hex LANDED target；多 hex 按 LAND 权重 + 最大余数；LAND 全转；RC3 钱货债经验；临时 LAND 清零 | RC3/RC4/RC5 | 已完成，compile rc=0 |
| **RC7 ✅** | **S7-C 向下流动表** | 第 37 个 EconomyData 组件 + ChangeSet/Codec/StateBuilder/Invariants；LANDED flow table；同 hex LANDLESS target + 建壳；债务扩大触发；人口/钱货债；LAND 不动 | RC3/RC4/RC5 | 已完成，compile rc=0 |
| **T1 ✅** | **测试 owner** | 迁移/新增 roundtrip、seeder、LAND/classness 不变量、merge、post-land、flow 正负例；32hex 1L+1LL 测试夹具；独立 360 tick；四类变异自证 | RC0–RC7 全部停手 | `gate.sh full` PASS（3633/0/0/7）；360 OK=360；9 个变异全红；证据本机 `.superpowers/sdd/2026-10-20-d032-t1-test-owner/` |
| **FUT** | **§8 同阶级整组织交易** | 本批不做；先开独立约束设计（对价、现金腿、债腿、非 LAND 资产估值、组织/unit 身份） | R1–R6 核心稳定后 | 未派 |

### 5.1 顺序理由

1. **RC0 先做**：v4 的 S7-B/C 依赖市场在生产/工资之后；当前链结构上不满足。
2. **RC1 先冻结契约**：class nature 是 production contract；culture key lookup 是 economy 不依赖 culture 的唯一合法口；后做会反复改构造函数/Codec/映射。
3. **RC2 先定位置与 seed**：没有 LANDED/LANDLESS 位置，RC3–RC7 无法稳定识别迁移两端。
4. **RC3 先做无 organizer 的 partial move**：R2 向上迁移是 LANDLESS→LANDED 的人口/钱货债转移，源无 organizer；可先不动最重的 organizer rebind。
5. **RC4 再做 full merge**：R1 同 key 合户会碰 organizer/unit/enterprise/asset；单独作为最大机械责任区，避免把 R2 的 post-land 逻辑拖死。
6. **RC5 在 RC6 前**：先保证债务清算不会把 LAND 送到 LANDLESS 债权人，否则 S7-B 永远看不到“正常 LANDLESS”，并会绕开向上唯一入口。
7. **RC6 在 RC7 前**：先验证向上（R2/R3）和临时 LAND 清零，再做向下 flow table；否则两类人口迁移会互相掩盖缺陷。
8. **RC7 最后做新持久组件**：flow table 是唯一新增 `EconomyData` 组件，放在迁移原语/合户稳定后，减少 Codec/roundtrip 反复改形状。
9. **T1 最后**：按 AGENTS §三.0，测试与变异自证统一收尾；代码 writer 只编译。

## 6. 每个责任区的中期交账要求

按 `docs/agents/15-十四-目标运行设计与子设计嵌入.md`，每个 writer 的 `impl-ledger.md` 必须写：

- 本责任区锚定的 v2 阶段号（S0–S8）和 v4 §；
- 嵌入位置（前/后/内部）与上下游；
- 实际实现与设计差异、原因；
- 对父序列影响：顺序 / 预算冻结 / 身份持久化 / 失败原子性 / 日志 / 测试；
- 关键 `file:line` 调查；
- 未做/未验证/blocker。

只写“改了哪些文件、编译过了”视为交账不完整。

## 7. 本批非目标

- 不恢复四阶层生产 slot/mode；不恢复 wealth-driven 自动向上；不恢复租佃/rent/Pledge/抵押。
- 不把 LAND 做成 block；不做第二套 LAND 库存；不把临时 `LANDLESS + LAND>0` 落盘。
- 不在本批实现 §8 同阶级整组织交易。
- 不在本批实现文化传播/同化；文化 label 只是稳定标签/key。
- 不写测试、不跑测试/verify/gate、不 commit/push、不碰主树。
- 不用 `MAX_HOUSEHOLD_POPULATION` 作为任何隐藏上限；不保留消费/现金作为需求挂单门。

## 8. 用户原话附录（逐字）

### 8.1 v4 R1–R6

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并，目前的有地农民家户早已脱离地主语义了，事实上是小农和地主的集合体——地主导致的灾难性阶级分化事实上也能表示，就是单个hex的有地农民人口极少土地极多，大部分人口都只能接受雇佣

R2：有地的家户挂单根据库存情况等写好的判断挂单，如果是没地的家户买到了土地，那么就把买到的土地连同那一部分比例的人口全部转移走，如果是有地的家户买到了土地，那就是买到了，走正常的生产支配流程

R3：完全可以

R4：把这个人口上限取消掉！此外，单个家户不应该有一个对应的文化属性标签吗？如果没有，那就紧急加一个只有自然语言描述的标签，让文化模块维护

R5：是的，随着人走

R6：测试世界依旧32hex，每个hex一个有地农民家户一个无地农民家户就行，政府和市场区配置照旧
```

### 8.2 v4 补充时序原话

来源：同上，§1.1，2026-10-20。

```text
理论上来说，在进行计算的时候，市场轮买卖结束、阶级跃迁没有开始的时候，阶级性为agri/landless的家户账面库存上是由土地的，正因如此下一阶段阶级跃迁要根据土地交易的出价方，以把人口按照土地交易的出价来源家户，按比例转移人口以达到把账面土地全部转移走的作用——如果这个家户的其他属性和出价家户一样，那么对应比例人口就转移到购买的土地所属hex的对应有地农民家户，只不过后面单个家户可能还有文化属性，如果文化属性不同，恐怕涉及文化属性判断，乃至要创建新家户，因此这个合并也是一个需要进行额外判断计算的过程，目前自然是直接合并添加
```

### 8.3 同阶级 classness / B / 向下流动原话

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v3.md` §1，2026-10-20（v3 已由 v4 取代，但这段是 classness/B/flow 的来源）。

```text
肯定是B啊，土地买卖完成之后，可以再塞一轮无地家户是否获得土地的检查，然后按比例算人口的阶级跃迁，很奇怪吗？
这样一来，家户的阶级性也就必须跟着生产方式的定义走了，经济模块和生产方式模块必须根据阶级性的代码定义来维护，农业生产方式的具体维护路径如上
家户向下流动的规则我不是已经说了吗？单个家户维护一个流动表，除去后面可能会做的文化流动因素，目前的作用就是指定在债务扩大的时候，本hex的有地家户只会流向本hex的无地家户，这张表的维护包括找到本hex的对应无地家户这一职责
```

### 8.4 向上只走市场 / 先生产后市场原话

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v2.md` §1；`docs/superpowers/specs/2026-10-19-d032-rural-production-land-labor-revision.md` §一。

```text
第二层，我的建议之前就提过了，只要没地家户认为干活收益入不敷出，就不提供任何劳动力进雇佣农业生产，单hex只有一个land户是完全可行的，其次这个单hex的有地农民不是能和其他hex交易土地吗？相当于，生产方式端要额外给家户维护一个阶级性，同阶级性家户自由交易生产方式，不同阶级性的家户如果产生交易生产方式的需求，走阶级跃迁流程，注意，家户间因负债而产生的向下阶级流动依旧即存在，只不过向上流动我觉得可以取缔掉了，无地农民只能通过买市场上抛售的土地才能成为本hex或者其他hex的有地农民；这样一来，土地买卖机制反而更好做了，生产规划里因劳动力、种子粮等原因空出来挂到市场上的土地也就有了去处
4 不是说了先生产后开市场吗
如果按照先生产后市场的顺序，tick0确实是没有生产方式的，这很正常，除非你搞预设了
```

### 8.5 需求价格接受者（S7-B 的 LAND BUY 仍沿用）

来源：2026-10-20 当前会话。

```text
需求被允许挂价了吗？家户提的需求，面对市场不管多贵都会直接接受的；其他没啥问题，记录一下你的修复方案，开始吧
```

## 9. 未做/未验证

- 本文只做设计复核与派单顺序，**未改任何生产代码**。
- 未运行 Maven/测试/verify/gate；RC0–RC7 的编译结果要由各自 writer 交账。
- 未验证 32hex 两户 seed、RC4 organizer rebind、RC6 多 hex 迁移、RC7 flow table 的真实运行。
- 未验证 §8 的整组织交易；本批不派。
- 当前工作树已有未提交改动；本文件不碰它们，不 commit。
