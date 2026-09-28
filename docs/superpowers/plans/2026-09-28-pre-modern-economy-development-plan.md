# 封建—近代前经济演化开发计划（2026-09-28）

> **性质**：可顺序执行的开发计划；**本文件不修改生产代码**。代码事实基线 = `ts/m1` @ `503dd2dc`
> （三份调查报告中 `5b6b2be7` / `a7fbfe46` 的生产代码与本提交逐字节同源）。
>
> **范围**：家户自用生产、佃耕、庄园义务、农村纺织、城市作坊并存；土地/工具/使用权、劳动来源、实物分成与有限区域市场共同决定生计。
> **不做**：现代经济、银行、央行、现代财政、证券、期货、跨币种兑换、现代公司制度、把 `Long` 简化为 `int`、缩小计量单位或时间步长。
>
> **输入报告**：
> - `docs/superpowers/reports/2026-09-28-economy-system-flow-report.md`
> - `docs/superpowers/reports/2026-09-28-economy-investigation.md`
> - `docs/superpowers/reports/2026-09-28-economy-evolution-boundary.md`
>
> **仓库纪律**：以 `AGENTS.md` 为准；一次只跑一个 Maven；写代码代理只写生产代码到编译过，测试统一留到最后；
> 新类型/新组件必须同时进变更集与往返测试（铁律 5）；数字纪律 = 定点整数、唯一写口、可重放、可守恒。
>
> **2026-09-28 晚用户追加裁定（本计划据此修订，优先级高于下文旧口径）**：
> ① **开发与数据测试基线 = 从空 store 重新初始化的 tick0 世界**（`simos.worldgen.initialize`，同一 worldgen 配置，
>    稳定路径、关闭随机化）；旧 tick360 store **只作旧档迁移/兼容夹具**，不再作性能或经济读数的开发基线；
> ② **R0–R4 编码阶段只过模块编译**（`spotless:apply` + `-DskipTests compile`）；不跑测试、不跑模拟读数、不跑性能、
>    不跑 1/4/8 重放、不跑守恒与场景验收；
> ③ 上述数据/模拟/性能/重放/守恒/场景验收全部集中在 **V 最终阶段**；
> ④ 4 个编码子代理 = R0+R1、R2、R3、R4；**独立评审与 V 阶段数据测试是额外的只读/测试代理（方案甲）**，不占 4 个编码配额；
> ⑤ 性能工作（缓存、索引、批处理、并行骨架）仍在 R1–R3 写进生产代码，但**性能数值门槛只在 V 阶段测**。

---

## 0. 阅读核对结论：已知事实、口径与两处必须写清的差异

### 0.1 三份报告交出的硬数字（计划基线）

| 量 | 实测值 | 来源 |
|---|---:|---|
| 地图 hex | 59,223 | 调查 §1.4 |
| 经济 hex / 市场格 | 799 | 同上 |
| 市场区（全局 `regionId` 去重） | 201（7 个区跨国界） | 同上 + 一年期报告口径更正 |
| 产业 | 1,799（farm 799 / weave 799 / craft 201） | 同上 |
| 家户行 | 6,392（799 × 8） | 同上 |
| `GoodsAccount` | 8,191（含 5,078 本非空货币） | 同上 |
| 债务 / 劳动供给 / 配额 / 关系 | 699 / 4,000 / 6,312 / 1,799 | 同上 |
| 在途 | 669 批 / 2,291 allocations | 同上 |
| 0→360 一年推进 | **976–986 s ≈ 16.3–16.4 min** | 调查 §1.1 |
| 360→390 嵌套计时 | step 140.229 s + apply 29.147 s + land 0.346 s = 169.722 s | 边界报告 §5.2 |
| 区域市场轮 | 126.03 s（12 轮，均 10.50 s），占日循环+落账 74.3% | 调查 §1.2 |
| `OwnershipBooks.apply` | 29.15 s（0.97 s/天），占 17.2% | 同上 |
| 关账日 apply | 约 17.5 s/日（fold 21,721 条，平常日 20–80 倍） | 同上 |
| JFR 市场段内：跨区 + 地形索引重建 | 31.95% + **22.66%**（`GameMap.terrainIndex()` 每轮重建 59,223 条） | 同上 |
| 每日末态构造/校验 | 约 9–11% | 同上 |
| 两次独立 JVM 360→390 | 状态 `equals` true；`changeset_json` md5 相同；原始 JSON 仅 `Set.copyOf` / `Map.copyOf` 键序不同 | 调查 §2.3 |

### 0.2 从三份报告回代码核到的断点（本计划必须处理）

| # | 现状事实 | 代码位置（已核） | 对目标的影响 |
|---|---|---|---|
| 1 | 家户买单只认粮/布的 **35 天保留量**；`selfNeedOf` 对其它商品静默 0 | `MarketSettlement.householdLifeReserveOf` / `selfNeedOf` | 新商品需求写进去也不产生订单 |
| 2 | `naturalNeeds` 每日被消费步覆写；`effectiveDemand` 无行为读者 | `EconomySettlement.withDailyNeed`；`ClassRow.effectiveDemand` | 外部需求冲击没有生效日 |
| 3 | `CohortKey(格, 居住类型, 阶层)` 是主键；actor id、账户键、粮债键都从它派生 | `CohortKey` / `HouseholdActors` / `GoodsAccountKey` / `Debt` | 部分人口迁移、阶层变化、地点变化都没有合法路径 |
| 4 | social 的 `PopulationGroup` 没有阶层维；批次→经济行靠劳动配额反推 | `PopulationGroup`；`EconomySettlement.applyPopulationChange` | 迁移无法从社会侧给出“这批人属于哪一家户” |
| 5 | `Industry.capacity` 是技术产能；`Industry` 无 active/closed；`AssetHolding` 整块退役 | `Industry`；`ActorData` | “谁占有/谁使用”与“谁经营”无法分离 |
| 6 | `ProductionRelation` 缺 `occupations` / `useRights` / `laborSource`；`ToCohort` 写死阶层名 | `ProductionRelation`；`RegimeRelations` | 地租/分成仍按名字收，不按占有与劳动关系 |
| 7 | 撮合无卖方成本维；`no_budget` 是全局 `anyBuy` 标签 | `MarketSettlement.collectUnfilled` | 不能把标签当“某经营者竞争失败”的证据 |
| 8 | operator 无 cohort 形状：`HouseholdActors.cohortOf(weave operator)` 抛；`lendDeficits` 只认 `CohortKey` | `HouseholdActors`；`EconomySettlement.lendDeficits` | 经营者负债/停业/退出没有状态可落 |
| 9 | `ProportionalSplit.byDenominator` 的 `total * weights[i]` 无溢出保护 | `ProportionalSplit.java` | 高人口/高库存下会静默溢出 |
| 10 | `economy.Seed` 占用判定只看 entry 的 q/r，不校验 `industries[].id` 里的格 | `EconomySeedHandler.occupiedHexKeys` / `EconomyPayloads.industry` | 可旁路覆盖已占用格；**列为独立校验修复项** |
| 11 | Unit 无 owner/nation/jurisdiction 字段；`sd.Army` 只是 `NationId + UnitId root` 的归属关系 | `Unit`；`Army` | GOV 行政节点不能假设军队 Unit 自带国土/行政语义 |
| 12 | `UnitOperations.attachSubtree` 上方有两块互相矛盾的 javadoc：旧块说“偏移式加入、不要求同格”，下层实际实现是 v2“必须同格、不再清位/反算 offset” | `UnitOperations.java:389-489` | 编码代理不能被旧注释带偏；列入最小文档腐化清理项 |

### 0.3 Unit 实际身份、层级、地域与命令模型（以代码为准，供 GOV 复用）

已核事实：

- **身份**：`UnitId` 是裸值 record（非空文本、无格式约束）；`Unit` 是含 `id / name / parent / position / member /
  equipment / speed / mobilityPerMille / movement / status / attached / offset / rejoinTarget / visionRadius` 的 record。
  **没有 owner、nation、region、office、role 字段。**
- **层级**：`parent` 是 `SegmentedSeries<Optional<UnitId>>`；`attached` 是 `SegmentedSeries<Boolean>`。`UnitState.formationRoot`
  沿 `parent` 上溯，遇到 `attached=false` 或无父即停；`formationMembers` / `formationSpeed` 只把 `attached=true`
  的后代算作“一同移动”的支；`planRoute` 只允许支的顶层。
- **命令链**：`CommandChain(chainId, name, commander, members)` 是扁平星形，允许多属、链内无层级；链引用完整性由
  `UnitState` 构造期判。
- **地域**：每个 Unit 有自己的 `position`（`Optional<HexCoord>`），没有地域字段；`visionRadius` 目前只服务 `ArmyScope`。
- **命令模型**：每个 `unit.*` handler 是 `CommandHandler` + 纯函数；`CreateUnit` 只认 `id/name/position/member/equipment/
  speed/mobilityPerMille/parent`，`kind` 不存在；`attachSubtree` 实际要求整棵子树与父同格；`disband` 拒绝仍有下属或链引用的单位。
- **能复用给 GOV 的**：稳定 `UnitId`、`parent` 森林、`CommandChain` 星形报告关系、`position`、变更集/codec/replay 机制、
  `UnitState` 的引用完整性守卫。
- **不能直接套给官署的**：`member` / `equipment` / `speed` / `mobilityPerMille` / `UnitStatus`（作战三态）/
  `Movement` 路线 / `attached=true` 的随父移动语义 / `visionRadius` 军事侦察语义。

---

## 1. 现状事实 → 目标能力 → 缺口 → 拟修改模块／关键类型

| # | 现状事实（代码） | 目标能力 | 缺口 | 拟修改模块／关键类型 |
|---|---|---|---|---|
| 1 | `CohortKey` = 格 + 居住 + 阶层，直接当主键 | 稳定家户身份；地点/阶层/职业可变；部分人口迁移 | 无稳定 `HouseholdId`；无成员份额表；social 无阶层维 | `simos-economy-api`：新增 `HouseholdId`；`simos-economy`：`ClassRow`/`EconomyData` 键改 `HouseholdId` + `view`；新增 `Membership` |
| 2 | actor id、`GoodsAccountKey`、`Debt.debtor/creditor` 全由 `CohortKey` 派生 | 迁移不改账户/债务身份；经营者、家户、官署、军队都是 actor | 无 actor 键迁移；operator 无 household 形状 | `simos-economy-api`：`HouseholdActors` 改为 `HouseholdId`；`Debt` 两端改 `HouseholdId`；`simos-actor`：兼容读取旧 household actor id |
| 3 | `naturalNeeds` 日覆写；`effectiveDemand` 无读者；订单只认 35 天粮/布 | 权威需求状态 + 命令 + 订单算法 + 生效日 | 无持续消费/一次采购/自然需要/库存缺口/支付能力的分离 | `simos-economy`：新增 `HouseholdDemand`/`DemandBook`；`MarketSettlement.ordersFor` 改读需求；GOV/命令走 app 批量 |
| 4 | 只有 `economy.Seed`；`Industry` 一份固定配方；`RecipeId` 零引用 | 预置候选生产方式；家户试产、失败可回收 | 无候选库、无试产状态、无运行时新格入口 | `simos-economy-api`：`ProductionCandidate`/`CandidateId`；`simos-economy`：`EconomyData.candidates`、`EconomyEntrySettlement` |
| 5 | `Industry.capacity` 只有技术总量；无 active/closed；`AssetHolding` 退役 | 使用权、占有、劳动来源、经营关系可分离 | 无 `UseRight`、无 `LaborSource`、无 operator 状态机 | `simos-economy`/`economy-api`：`UseRight`、`IndustryStatus`、`OperatorCondition`、`ProductionRelation.laborSource` |
| 6 | 撮合无成本维；`no_budget` 全局标签 | 区分“买方有库存/无钱/卖方价格或运输劣势/投入不足/可自用/真无法再生产” | 无逐卖方成交率、无单位成本、无输家候选 | `MarketSettlement`：`SellSlot` 成本键、`SellerOutcome`、逐买方 `UnfilledReason`；`Industry`/market：单位成本读数 |
| 7 | `Debt` 只绑家户，`lendDeficits` 只认 `CohortKey` | 经营者负债、停业、退出；家户转业/迁移 | operator 无 cohort；`defaulted` 无写 true 路径 | `EconomySettlement.lendDeficits` 泛化到 `HouseholdId`/`ActorRef`；`Debt` 两端稳定 ID；`OperatorCondition` |
| 8 | 阶层是主键一段；无使用/劳动关系维 | 阶层由占有和劳动关系形成，按可观察状态计算 | 无分类函数、无迁移入口 | `simos-economy`：`HouseholdClassRule` 纯函数；`SocialClassId` 扩展少量档位；迁移命令 |
| 9 | `LaborSupply.servedLaborMilli` 有字段、恒 0 | 徭役可扣减可用劳动且守恒 | 无 GOV 写者、无每日预留 API | `simos-gov` + `EconomyDayStepper.applyServedLabor`；`LaborSupply` 保持形状 |
| 10 | Unit 无 kind；GOV 无状态、无国库/仓储 | 官署树、实物税、官仓、军队供给闭环 | 无 `UnitKind`、无 `GovState`、无跨模块协调方案 | 新模块 `simos-gov`；`simos-unit`：`UnitKind`；`simos-app`：单参与者写 economy/actor/social/gov |
| 11 | 地形索引每轮重建；apply O(n²)；闭账日大条目；市场全球扫 | 799 格一年 < 4 min；1/4/8 线程确定 | 无缓存、无索引、无批处理、无并行提交协议 | `MarketTopologyBook`、`OwnershipBooks`、`MarketSettlement`、`EconomyDayStepper`、`EconomySettlement` + app participant |
| 12 | 单线程可重放；多线程未验 | 同初态命令下 1/4/8 线程领域状态一致 | 无分区键、无交易意向、无稳定提交序 | `simos-economy/time/*` + app；新增确定性/重放验收装置 |

---

## 2. 修订（2026-09-28 晚）：合并性能与架构为一次半重构

用户判断“性能优化与架构优化应该一起做，相当于半个重构”。复核代码后**同意**，本节取代原先“语义链 + 性能链并行”的执行结构：

- 身份迁移（`HouseholdId` / `Membership` / `LaborAllocation.household`）会改 `EconomyData`、`EconomyChangeSet`、
  codec、resolver、`OwnershipBooks`、`MarketSettlement` 的全部账户签名；
- 性能优化（账户批处理、`EconomySession`、市场索引、并行提交）改的是**同一批文件和同一批会话地图**；
- 如果先做性能，会围绕旧 `CohortKey` 四张会话地图做一遍批处理/并行，S1 再全部改键；
- 如果先做身份，会保留 O(n²) apply、每轮重建地形索引、每日深拷贝，S1 的验收只能在极慢的 year-scale 上跑；
- 两条分开做等于把同一块内核改两遍。**合并成一次重构主线**，性能指标作为每个架构切片的验收条件，而不是独立晚到的轨道。

### 2.1 联合重构主线 R0–R4 + 最终 V 阶段

```
R0 前置修复与基线
  种子格键旁路、ProportionalSplit 溢出、Unit 文档腐化、P1.1 地形索引缓存、tick0 开发基线装置
  → R1 账户/身份内核重构（S1 + P1.4/P1.5；只编译，不测性能）
    → R2 订单/结算执行器重构（S2 + P1.2/P1.3/P1.6 + P2 并行骨架；只编译，不测并行结果）
      → R3 市场/生产关系/衰退重构（S3 + P3 跨区协调；只编译）
        → R4 GOV 最小闭环（S4；复用 R1 账户内核与 R2 执行器；只编译）
          → V 最终数据/模拟/测试阶段（额外代理；测试代码、变异自证、clean verify、tick0 场景、性能、重放、万级）
```

**执行原则（覆盖 §4 旧口径）**：
- 每个 R 切片是“可编译层”，内部不再按功能拆成多个代理任务；一个 R 切片通常一个写代码代理，过大时按编译层拆；
- R0–R4 每片只交：生产代码 + 数值行为变化清单 + 硬编码字面量清单 + `-DskipTests compile` 结果；
- **R0–R4 不跑测试、不跑模拟、不跑性能、不跑重放、不做数据验收**；相关验证全部进 V；
- 性能结构（缓存、索引、批处理、并行分区/稳定提交）仍必须在 R1–R3 写进生产代码，只是**不在编码期宣称达标**；
- 仍不允许并行推进彼此依赖的不同日期；仍不能让多个线程直接抢写同一本账户；唯一写口仍是 `EconomySettlement.applyTransfer`；
- V 阶段由额外测试代理执行，具体见 §2.4。

### 2.2 R 切片与旧章节的对应关系

| R 切片 | 语义内容（§3） | 性能结构内容（§4） | 编码期完成条件（只编译） |
|---|---|---|---|
| **R0** | S0 | P1.1 + tick0 基线装置 | 种子旁路修复、溢出安全乘除、Unit 注释清理、地形缓存、tick0 初始化脚本；`-DskipTests compile` 绿 |
| **R1** | S1 | P1.4 + P1.5（账户统一批处理 + `EconomySession`） | 身份/账户/迁移内核全部进生产代码；`-DskipTests compile` 绿；不跑旧档重放、不跑守恒测试 |
| **R2** | S2 | P1.2 + P1.3 + P1.6 + P2 | 需求/候选/试产 + 订单/市场索引 + 并行执行骨架全部进生产代码；`-DskipTests compile` 绿；不跑 1/4/8 一致性 |
| **R3** | S3 | P3 | 竞争/衰退/迁移/阶层 + 跨区协调全部进生产代码；`-DskipTests compile` 绿 |
| **R4** | S4 | 复用 R1/R2，不新开性能轨 | GOV 最小闭环 + 全仓 `-DskipTests compile` 绿 |
| **V** | 最终验收（§5/§6/§7） | 性能测量、1/4/8 重放、万级扩展 | 测试代码补齐、变异自证、`clean verify`、tick0 场景、性能报告；数据结论只在这里产生 |

### 2.3 为什么不是“两条并行链”

原计划曾把 S1 与 P1 标为可并行（文件不重叠）。复核后该假设不成立：

- `OwnershipBooks` 的账户键与 `EconomyDayStepper` 的四张会话地图是 S1 和 P1.4/P1.5 的共同改动面；
- `MarketSettlement` 的参与者索引与 `HouseholdId`/`LaborAllocation.household` 是 S1/S2 和 P1.2/P1.3 的共同改动面；
- 若强行拆两个写者，会在同一文件上互相覆盖，正是 `AGENTS.md §一` 禁止的形态。

⇒ 改为**同一主线、按可编译层推进**；R0 内保留唯一的小范围并行：P1.1 只改 `MarketTopologyBook`，S0 只改校验/工具类，
两者文件不重叠，可同批提交。

**硬规则（保留）**：不能并行推进彼此依赖的不同日期；不能让多个线程直接抢写同一本账户；R 切片不通过编译不得进入下一片；
性能/重放/数据验收结论只在 V 阶段产生，R1–R4 不得用“未测”冒充“达标”。

### 2.4 开发基线（tick0）与最终 V 阶段

**开发基线**：
- 从**空 store** 启动组合根，调 `simos.worldgen.initialize`，使用与 `config/worldgen/v17levant-nations.json` 同一份参数；
- baseline 保存为只读 tick0 快照，放在重启不丢的稳定路径（如 `$HOME/SimosData/simos-dev/tick0-store`，不得放 `/tmp`）；
- 每次开发/数据测试从同一份 tick0 快照复制/分叉；世界生成只做一次，之后不再改动 baseline；
- 旧 `store-m2`/tick360 store 只作为 `LegacyHouseholdMigration` 等旧档兼容夹具，不参与日常开发或性能基线；
- R0 增加一个不依赖测试框架的初始化/复制脚本（例如 `tools/dev-tick0-world.sh`），但仍不得写/跑 JUnit 测试。

**V 最终阶段（额外测试代理，按方案甲不占 4 个编码配额）**按顺序做：
1. 适配既有测试到新 API，补新测试；`clean verify` 全绿；
2. 守恒/不丢失/静默付 0/断粮四类关键项变异自证；
3. tick0 场景：新需求→试产、家户分化、外地挤占、卖不掉可自用、真实退出/转业、GOV 征粮/供给；
4. 1/4/8 线程重复运行 canonical 比较（§4.5/§6.2）；
5. 性能：R0/R1/R2/R3 各阶段的算法结构在同一 tick0 存档上测量；单线程/4/8 线程、799 格一年、10k 格扩展；
6. 旧 tick360 store 的迁移/重放兼容测试；
7. 如实报告“未做/未验证”。

### 2.5 R1 执行优先级（2026-09-28 用户追加裁定）

**线程安全 > 算法逻辑。** R1 不是“先身份迁移、以后再并行”，而是把它当作**代码设计架构问题**：

1. **第一优先级：多线程安全的会话/账户/提交内核**
   - 先设计与实现：账户分区键、线程本地 `AccountDelta`/交易意向、冻结/占位、跨分区校验、稳定顺序提交；
   - 唯一写口 `applyTransfer` 不得被多个线程直接并发调用；跨线程只允许传递不可变意向；
   - `EconomySession`/`EconomyStateBuilder`/`OwnershipBooks` 的数据结构必须支持“单线程确定提交 + 多线程分区计算”；
   - 并行骨架按 §4.3 的依赖图与账户冲突表落到生产代码，先覆盖可按 hex/市场区并行的阶段；
   - 1/4/8 线程走同一套分区函数与 tie-break，不允许线程数改变结果（R1 只保证结构与编译，结果一致性留 V 验证）。
2. **第二优先级：必要的算法改动随多线程架构一起做**
   - 身份/账户键（`HouseholdId`、`Membership`、`LaborAllocation.household`、`UseRight`）不再单独排后，因为它们决定账户分区键；
   - `OwnershipBooks` 批处理、`MarketTopologyBook` 地形缓存、`EconomySession` 单次构造随内核一起落地；
   - 需求/候选/市场成本排序等 S2/S3 算法可以后补，但不得为了算法方便破坏线程安全分区/确定性；
   - 优先修“会阻碍并行”的设计债（四张会话地图、共享可变 Map、全局标签、按到达顺序撮合），而不是先堆功能。

**R1 收口代理的停止条件**：多线程会话/提交内核达到可编译、可静态审计的状态；身份/账户算法改动能安全挂上这个内核；
若上下文不足以同时完成两者，**不得牺牲线程安全回退到单线程临时实现**，而应报告剩余算法项留给 R2。



---

## 3. 语义切片详细计划

> **阅读说明**：下面 S0–S4 是**工作内容规格**，不再代表独立执行轨道；实际执行顺序以 §2.1 的 R0–R4 为准。

### S0. 校验修复、数值安全与仪器（前置，小）

**目标**：在动身份/需求前，先修掉三个会污染后续判断的已知问题，并把测量装置固定下来。

**状态/命令**：无领域状态变更；只修校验、溢出和文档腐化。

**算法/改动**：

1. **种子格键旁路修复**
   - 在 `EconomyPayloads.industry` 解析时，逐 industry 从 `IndustryId` 用 `IndustryHexKeys.hexKeyOf(id)` 取格，必须与
     `entries[]` 中该产业所在 entry 的 `(q,r)` 一致；不一致 ⇒ `IllegalArgumentException`。
   - `EconomySeedHandler` 的 `occupiedHexKeys` 仍按 entry 格判重，但“entry 格 ↔ industry.id 格”的一致性由载荷层判死，命令层不重复实现第二套。
   - 新老存档：旧档没有旁路问题的正常数据不受影响；若旧档里已存在错位产业，加载时保持可读，由新增的读数/校验报告列出，不静默修数据。
   - 判别力：构造 `entry=(0,0)` + `industry.id=farm@9_9`，命令必须拒绝且理由点名两个格。

2. **`ProportionalSplit` 乘法溢出修复**
   - 保持 `byDenominator(total, weights, denominator)` 的外部语义：`parts[i] = floor(total×weights[i]/denominator)`，残差按最大余数法、
     同余数按下标升序。
   - 计算 `product` 时不得直接用 `total * weights[i]`。实现：
     ```text
     if (weights[i] == 0) product = 0
     else if (total <= Long.MAX_VALUE / weights[i]) product = total * weights[i]
     else product = BigInteger.valueOf(total).multiply(BigInteger.valueOf(weights[i]))
     parts[i] = product / denominator
     residues[i] = product % denominator
     ```
     `BigInteger` 只在会溢出的分支进入；常数级小权重仍走 long 快路。
   - `assigned` 用 `Math.addExact`；若 `assigned + parts[i]` 溢出，改为 `BigInteger` 累加或直接抛具名 `ArithmeticException`（宁抛不静默）。
   - `denominator == 0` 的既有语义（整份记在第一项）保留。
   - 判别力：`total=Long.MAX_VALUE`、`weights={Long.MAX_VALUE, Long.MAX_VALUE}`、`denominator=Long.MAX_VALUE` 等最大输入必须给出
     `Σparts == total`，不得负数/静默回绕。

3. **Unit 文档腐化清理**（与 GOV 相关的最小项）
   - 删除或标注 `UnitOperations.attachSubtree` 上方旧的“偏移式加入、不要求同格”javadoc 块，只保留 v2 实现对应的注释。
   - 不改变任何 unit 行为；不得把 `attached` 改回跟随语义。

**真实文件/类**：
- `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomySeedHandler.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java`
- `simos-util/src/main/java/io/mosire/simos/util/economy/ProportionalSplit.java`
- `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`（仅注释）

**完成标准**：
- `tools/mvn-lock.sh -q spotless:apply && tools/mvn-lock.sh -DskipTests compile` 绿；
- 既有 `EconomySeed*` / `ProportionalSplit*` / `Unit*` 测试不因这次改动变红；
- 新增的判别用例在收尾测试阶段统一补，开发期只保证编译和判据算式写进注释/报告。

---

### S1. 可变身份、使用权与守恒基础（最关键的语义底座）

**目标**：把“家户身份”“当前地点/居住/阶层视图”“谁占有/使用生产资料”“劳动来源”“债务主体”拆成稳定、可迁移、可守恒的状态；
使一个聚合家户行的部分人口能够转入另一生产关系，且账户、债务、劳动批次、社会批次都守恒。

#### S1.1 身份方案比较与选择

**方案 A：稳定家户主体 `HouseholdId` + 可变视图（选用）**

- 新增 `HouseholdId`（opaque 值对象，`toString/parse`），家户的经济主体身份 = `ActorRef(HOUSEHOLD, householdId)`。
- `ClassRow` 的键改为 `HouseholdId`；`ClassRow` 新增 `view: CohortKey`（当前格 + 居住类型 + 阶层）与 `laborSource`
  （或由关系派生，见 S3）。
- `EconomyData.classes` / `flows` 键改 `HouseholdId`；`Debt.debtor` / `Debt.creditor` 改 `HouseholdId`；
  `ProductionRelation.recipient` 增加 `ToHousehold(HouseholdId)`；旧 `ToCohort(CohortKey)` 作为**视图选择器**保留到 S3，
  S1 的迁移把一夫一妻式旧行转成 `ToHousehold`。
- 新增 `Membership` 状态：`(MembershipId, PeopleLotId, HouseholdId, count)`；`Σ count` 逐批等于
  `PopulationGroup.count`。社会批次仍由 social 拥有，economy 只持成员份额。
- `LaborAllocation` 增加 `HouseholdId household`：同一 lot 的人可以分别给不同家户/产业出劳动，这是“部分人口转入另一生产关系”的必要条件。
- 账户键仍为 `(ActorRef, HexCoord)`；家户账户键从旧 actor id 迁移到新 actor id，地点不变。
- 迁移 = 修改 `Membership` + `ClassRow` + `LaborAllocation(household)` + `UseRight` + 债务拆分；**不改** `HouseholdId`
  和 actor 账户键，因此不需要“账户改名”这条危险路径。

**方案 B：保留 `CohortKey` 为身份，迁移即整体改键（不选）**

- 迁移时把旧 `CohortKey` 拆成旧/新两个键；账户、债务、劳动关系全部跟着改键；`DebtId` 由四元组重新生成。
- 优点：不新增 `HouseholdId`，旧档迁移路径短。
- 不选理由：
  1. 与铁律 1“ID 是身份、地址是定位方式”冲突：地点/阶层变化会改身份；
  2. 同一格同居住地同类别的多个家户无法共存（键会撞）；
  3. 每次迁移都要全仓重扫所有引用（关系/债务/账户/劳动），漏一处就静默断链；
  4. S3 的“阶层由占有和劳动关系形成”要求阶层可变而身份稳定，方案 B 会把这条目标写成迁移补丁的无限循环。
- 如实记的方案 B 成本：实施初期改动小，但后期每次新增引用类型都要补迁移器；本仓已有 `GoodsAccountKey` 的“`|` 接缝”教训，
  不应再制造第二种身份拼写。

**选择 A 的代价（如实记）**：
- `classes` / `flows` / `debts` / codec / change set / 地址解析/读口全要改键，改动面大；
- 旧存档必须有一份显式 `LegacyHouseholdMigration`；
- 必须新增 `Membership` 组件并进铁律 5 的变更集/往返测试。
  对冲：迁移只发生一次，且之后所有局部迁移都不改 actor/账户/债务身份。

#### S1.2 S1 状态与契约

新增/修改真实类型：

| 类型 | 模块 | 形状要点 |
|---|---|---|
| `HouseholdId` | `simos-economy-api/id` | 裸值 + `parse`；`ofLegacy(CohortKey)` 只用于旧档迁移，运行期不得再造 |
| `HouseholdActors` | `simos-economy-api/cohort` | `of/idOf` 参数从 `CohortKey` 改 `HouseholdId`；旧 `cohortOf(ActorRef)` 只保留旧档迁移/兼容读，不再作为运行期身份来源 |
| `MembershipId` | `simos-economy-api/id` | `membership-<lot>-<household>`，不含 `.`，由 `Membership.idOf` 唯一拼写 |
| `Membership` | `simos-economy/model` | `(id, PeopleLotId lot, HouseholdId household, long count)`；`count ≥ 0`，`Σ count(lot) == PopulationGroup.count` |
| `ClassRow` | `simos-economy/model` | 键/字段改 `HouseholdId id`；保留 `CohortKey view` 作为当前地点+居住+阶层视图；其余人口/劳动/参与率/债务引用/需求字段保留 |
| `FlowRow` | `simos-economy/model` | 键改 `HouseholdId`；`taxPaid` 字段保留（S4 才有非 0 写者） |
| `Debt` | `simos-economy/model` | `debtor` / `creditor` 改 `HouseholdId`；`principal` 拆债时可拆成多条，`Σ principal` 守恒；`defaulted` 保留。新 `DebtId` 由 `(周期, debtor, creditor, 商品)` 的 `HouseholdId` 规范串确定性生成，不含 `.`；旧 ID 原样保留 |
| `UseRight` | `simos-economy/model` | `(UseRightId, IndustryId activity, ActorRef holder, AssetKind asset, long quantity, RightKind kind)`；`kind ∈ OWNED / TENANCY / COMMUNAL`；数量按资产种类与产业汇总不得超过 `Industry.capacity` |
| `UseRightId` | `simos-economy-api/id` | 稳定值；由 `(activity, asset, holder, kind, 序号)` 拼，序号由状态内确定性计数给出；不含 `.` |
| `LaborAllocation` | `simos-economy-api/labor` | 增加 `HouseholdId household`，id = `alloc-<industry>-<lot>-<household>`；`Σ allocation.laborMilli(lot) ≤ availableLabor(lot)` 不变量不变，但同一 lot 的劳动可分别属于不同家户 |
| `ProductionRelation` | `simos-economy-api/relation` | 增加 `LaborSource laborSource`；`Recipient` 增加 `ToHousehold(HouseholdId)`；`operator` 改 `ActorRef` 后允许 `HOUSEHOLD/ESTATE/WORKSHOP/GOVERNMENT`，但必须与 `UseRight.holder` 一致 |
| `LaborSource` | `simos-economy-api/relation` | `SELF / FAMILY / TENANT / SERF / WAGE`；进入 `ProductionRelation` 后成为生产关系的显式维度 |
| `Recipient.ToHousehold` | `simos-economy-api/relation` | sealed 新变体；旧 `ToCohort` 保留，S1 迁移转换，S3 解释为视图选择器 |

`EconomyData` 组件从 10 个逐片增加：S1 末为 13 个 = `meta / industries / classes / debts / flows / laborSupply /
allocations / relations / markets / shipments / memberships / useRights / operatorConditions`（`operatorConditions`
若 S3 才启用可先空表，但组件和变更集一次性补齐，避免多次破档）。每加一个组件必须同步：
`EconomyData` record 组件、`EconomyChangeSet` 字段、`EconomyCodec`、`EconomyRoundTripTest` 反射断言。

**同批必须重写的派生路径**（S1 的隐藏工作量，漏掉会静默走旧键）：
`EconomySettlement.householdKeysOf` / `householdKeysAt` / `householdKeysOfLot` / `industriesOfHouseholds` /
`cycleDaysByHousehold` / `householdActorsOf`，以及 `MarketSettlement.participantsFor` / `planFor` 的
`keys` 来源。它们必须改为“`Membership` + `LaborAllocation.household` 派生”，不再从 `CohortKey` 的阶层段反推；
旧档迁移器是唯一仍可读旧 `CohortKey` 段的地方。

#### S1.3 S1 命令面

| 命令 | 载荷要点 | 状态读写 | 生效时间 |
|---|---|---|---|
| `economy.MigrateHousehold` | `fromHouseholdId`、`toHouseholdId`（可新建）、`lotShares[{lotId,count}]`、`targetView`、`assetSelections`、`debtRule` | 读 classes/memberships/accounts/debts/useRights/allocations；写 economy+actor（批量/协调器） | 命令落成一条 revision，从下一结算日起生效；`debtRule` 缺省 `PROPORTIONAL_SPLIT` |
| `economy.RelocateHousehold` | `householdId`、`toHex`、`toResidence` | 改 `ClassRow.view`、账户 location 不变（账户是 owner+格，同一本账不会因家户搬迁自动搬家）；搬迁通过显式货权转移或原地留账处理 | 同上 |
| `economy.ReclassifyHousehold` | `householdId`、`newView`、`reason` | 由 S3 的 `classify()` 自动触发或 GM 显式校验；不改 actor id/债务/账户 | 同上 |
| `economy.TransferUseRight` | `useRightId`、`toHolder`、`quantity`、`kind` | 拆分/转移使用权；检查产业 capacity 与关系一致性 | 同上 |
| `actor.Seed` 扩展 | 允许 seed `HOUSEHOLD` 以外的 `GOVERNMENT/UNIT` actor 与账户（S4 复用） | actor 切片 | 同上 |

**跨模块执行**：`MigrateHousehold` 同时改 economy 与 actor，单个 handler 不能写两个 namespace。
方案：**app 组合根的命令协调器**（`simos-app/.../command/HouseholdMigrationCommand`）用 `CommandBus.submitBatch` 提交
`economy.MigrateHousehold` + 必要的 `actor.*` 命令；一批 = 一条 revision（Core 已有 `submitBatch`）。
若账户余额迁移必须原子，则只允许 batch 路径，不允许 GM 只发单条 economy 命令。

#### S1.4 部分人口迁移算法（伪代码）

```text
migrate(from, to, lotShares, targetView, assetSelections, debtRule):
  # 0. 守恒预检（全部只读，失败则整批拒绝）
  assert Σ_shares.count == Σ lot.count * sharePerMille (按 lot 精确整数配分，残差用最大余数法，同余数按 lotId 升序)
  assert to != from
  assert 每个 lot 在 from 的 Membership.count ≥ shares[lot].count
  assert 所有目标 UseRight/Industry 的剩余容量足够
  assetPlan = allocateAssets(from, to, lotShares, assetSelections) # 商品、货币、使用权
  debtPlan = splitDebts(from, to, lotShares, debtRule)              # 本金拆分、ID 保持或按确定规则新建

  # 1. 身份与成员
  if to 不存在: 创建 ClassRow(to, view=targetView, population=Σ shares, laborMilli=按份额折算, debts=[])
  对每个 lot:
    from.membership.count -= shares[lot].count
    to.membership.count   += shares[lot].count
    # 不复制、不新建人口；新 lotId 只在该 lot 属性确实需要拆分时按 splitLot 规则创建
  重算 from/to 的 ClassRow.population 与 laborMilli（只有劳动随人走）

  # 2. 商品与货币：按“迁出人口 ÷ 原家户人口”的最大余数法拆分
  可移动量[commodity] = max(0, from.account.balance[commodity] - from的必要保留[commodity])
  moved = split(可移动量, shares, from.population)
  from.account -= moved; to.account += moved
  # 未移动部分留在原家户账；不得把负余额或负债伪装成“迁移带走”
  每个转移铸一条 from→to 的 Transfer（reason=HOUSEHOLD_MIGRATION），经唯一写口落账
  # 货币同理，按币种逐个最大余数法拆分

  # 3. 劳动
  对每个 lot：
    released = LaborAllocation 中属于 from 的按份额可迁出部分（从该 lot 的现有配额中切）
    在 to 名下新建/累加对应产业或身份的 LaborAllocation（id = LaborAllocation.idOf(activity, lot, to)，
    household = to）
    Σ allocations(lot) 保持不变；laborSupply.gross/served/committed 不变
  # 迁移不改变人口、劳动总量；只是把劳动承诺从 from 转到 to

  # 4. 债务
  对每条 from 的 Debt：
    if debtRule == LEAVE_WITH_FROM:
      债务全部留在 from；to 不继承。必须同时把“迁移的人不再享受该债务对应的资产/未来收入”写进资产计划；
      否则等于默认逃债。
    if debtRule == PROPORTIONAL_SPLIT:
      principal_moved = floor(principal * 迁出人口 / from人口)，残差归 from；to 新建 Debt（或改 creditor/debtor 侧）
      Σ principal 逐值不变；新 DebtId 由确定性规则给定；旧 Debt 的 debts 引用与 creditor 索引同步更新。
    if debtRule == SETTLE_THEN_MOVE:
      要求迁移前有足够余额；铸 from→creditor 的偿还转移；不足 ⇒ 拒绝整批。
  # defaulted 状态只属于原债务；拆分后的两条分别继承或按裁定配置，默认继承。

  # 5. 使用权与生产资料
  按 assetSelections 从 from 的 UseRight 中拆出数量到 to；若目标产业 capacity 不允许 ⇒ 拒绝；
  UseRight 的 holder 随人走，不改变 Industry.capacity（技术产能不动）；
  kind=TENANCY 的租佃关系在 S1 保留，S3 的 ProductionRelation 改挂到 to。

  # 6. 后置不变量（同一 revision 内、写状态前最后判）
  assert Σ Membership.count(lot) == social PopulationGroup.count(lot)  # 由 app 协调器读 social 侧
  assert Σ ClassRow.population == Σ Membership.count
  assert Σ account balances by commodity/currency 不变
  assert Σ Debt.principal by commodity 不变；每条 Debt 两端存在
  assert Σ allocations per lot ≤ availableLabor；served/committed 不变
  assert Σ UseRight.quantity per (industry, asset) ≤ Industry.capacity
  assert 旧关系/新关系的 operator/inputSupplier/residualOwner 都能解析
```

#### S1.4.1 月度出生/死亡如何摊回成员份额

`LotChange` 仍以 `PeopleLotId` 为单位；app 协调器在月度人口结算后按 `Membership` 的 count 权重，把
`births/deaths` 摊到该 lot 的各家户成员份额上（最大余数法、同余按 `HouseholdId` 字典序）。摊完后：
- `Membership.count`、`ClassRow.population`、`LaborSupply.grossLaborMilli`（按存活比例）、
  `LaborAllocation.laborMilli`（按存活比例）和 `FlowRow.births/deaths` 在同一 revision 内一起更新；
- 不允许“社会侧少了人、经济侧份额没变”；也不允许“孩子出生算进劳动”，新生儿只增加 count，不增加 laborMilli。
- 迁移/拆分/合并 `Membership` 时，同一 lot 的 `Σ count` 必须与 social 的 `PopulationGroup.count` 相等；
  若两者不等，协调器当场抛，不把差额均摊到别的 lot。

#### S1.5 旧存档迁移与兼容规则
- **迁移入口**：在 economy/actor codec 读入旧 revision 时识别旧键形态；或在 app 启动时对 store 做显式一次性迁移。
  计划采用**显式、可重放**的方案：
  - `LegacyHouseholdMigration.of(CohortKey)`：`HouseholdId = "legacy-" + CohortKey.toString()`（确定性、可逆到旧视图）；
  - `HouseholdId.ofLegacy` 只允许在旧档迁移调用，生产代码路径不得使用；
  - `EconomyMeta.migrationSource = old revision id`，`rulesVersion = "pre-modern-v1"`；迁移后再次加载不得二次迁移。
- **economy 侧映射**：
  - `classes` 键：`CohortKey` → `HouseholdId.ofLegacy(key)`；`ClassRow.view = key`；
  - `flows` 键同款映射；行内 key 同步替换；
  - `Debt.debtor/creditor`：旧 `CohortKey` → 新 `HouseholdId`；旧 `DebtId` 原样保留（opaque，不重算，避免旧 ID 语义丢失）；
  - `ProductionRelation.recipient = ToCohort(old)`：若旧世界里该 view 恰有一个家户（旧档必然如此）→ `ToHousehold(newHouseholdId)`；
  - `LaborSupply` / `LaborAllocation`：键不变（`PeopleLotId`）；`LaborAllocation` 新增的 `household` 由旧隐含语义反推：
    对每条旧配额，取它指向产业所在格 + `ResidenceKind.ofLot(lot)` 的既有行，按行人口最大余数法把该条
    `laborMilli` 拆到各行对应的 `HouseholdId`；残差按 `HouseholdId` 字典序，`Σ laborMilli` 逐值不变；
    新增 `Membership` 同样从旧劳动配额与人口行反推：每个旧 lot 先给“它供给的产业所在格 + 居住类型 + 四阶层”中
    人口非 0 的行按人口比例分摊；残差按行键字典序；
  - `UseRight`：旧档没有，上迁移时按旧 `Industry.capacity` + `Industry.operator` 生成一条 `OWNED` 的整额使用权给 operator；
    旧 `AssetHolding` 已退役，不做“假恢复”。
- **actor 侧映射**：
  - 仅当 owner 是 `ActorKind.HOUSEHOLD` 且 id 形如旧家户（三段 `:`：`<q>_<r>:<residence>:<stratum>`，
    可由旧 `HouseholdActors.cohortOf` 反解）才映射到新 `HouseholdId`；
    `HOUSEHOLD:weave@...` 之类产业型 operator 不映射；
  - `GoodsAccountKey.location` 不变；余额/货币/冻结表逐值原样带过；
  - 旧 actor row 的 label 带过，ref 改为新 ActorRef。
- **迁移前后必须成立的守恒式**（写成可执行断言，收尾测试阶段落地）：
  1. 人口：`Σ Membership.count(lot) == PopulationGroup.count(lot)`（逐 lot）。
  2. 行人口：`Σ ClassRow.population == Σ Membership.count`；`Σ ClassRow.population` 迁移前后全球相等。
  3. 劳动：`Σ allocations.laborMilli(lot) ≤ laborSupply.availableLabor(lot)`；`gross/served/committed` 逐值不变。
  4. 商品：按 `CommodityId` 汇总的账户余额 + 在途 + 生产在制量（`cycleInputUsedMilli`）迁移前后逐值相等。
  5. 货币：按 `CurrencyId` 汇总的账户余额迁移前后逐值相等。
  6. 债务：按 `commodity` 汇总 `principal` 迁移前后相等；每条旧债至少映射到一条新债，两端都指向存在的家户。
  7. 使用权：按 `(IndustryId, AssetKind)` 汇总 `UseRight.quantity` 迁移前后相等；不得超过 `Industry.capacity`。
  8. 关系：旧关系全部映射到新关系，`operator/inputSupplier/residualOwner` 的映射保持；规则集合逐值不变。
  9. 身份：不新增人口；新建的 `HouseholdId` 只能来自旧行的拆分，成员份额之和等于旧行人口。

#### S1.6 S1 真实文件/类

| 改动 | 文件/类 |
|---|---|
| 新稳定身份 | `simos-economy-api/.../cohort/HouseholdId.java`、`MembershipId.java`、`HouseholdActors.java`、`Recipient.java`、`LaborAllocation.java`、`Debt.java` |
| 经济状态/变更集/codec | `simos-economy/EconomyData.java`、`change/EconomyChangeSet.java`、`codec/EconomyCodec.java`、`model/ClassRow.java`、`model/FlowRow.java`、`model/Membership.java`、`model/UseRight.java` |
| 日结算派生路径 | `simos-economy/time/EconomySettlement.java`（`householdKeys*` / `applyPopulationChange` / `scaleLaborOf*` / `debtIdOf`）、`time/EconomyDayStepper.java` |
| 地址/解析/读口 | `simos-economy/resolve/EconomyResolver.java`、`simos-app/gui/ApiViews.java`、`simos-app/time/MarketReadoutAssembly.java` 中 class/flow/debt 地址与视图键全改 `HouseholdId` 规范串 |
| 市场参与者 | `simos-economy/time/MarketSettlement.java`（`participantsFor` / `planFor` / `householdLifeReserveOf`） |
| 事务/账户 | `simos-app/time/OwnershipBooks.java`（`loadHouseholdGoods/Money` 的键改 `HouseholdId`；新增任意 actor 账户载入）、`simos-app/time/PopulationEconomyTimeParticipant.java` |
| 命令与迁移 | 新 `simos-economy/spi/MigrateHouseholdHandler.java`、`RelocateHouseholdHandler.java`、`TransferUseRightHandler.java`；app 侧批量协调器 |
| 种子 | `simos-app/world/EconomySeeder.java`、`HouseholdSeeder.java`（生成 `HouseholdId`、Membership、UseRight，不再从 `CohortKey` 派生 actor id） |

#### S1 完成标准

- 旧档可加载；迁移器幂等；上述 9 条守恒式在旧档夹具上逐值成立；
- 对同一旧档跑一次迁移后再保存/读取/回放，领域状态 `equals` 一致；允许 JSON 键序不同（收尾测试统一口径）；
- 可手写一个“部分人口迁移”状态，迁移后：
  - 无新人口，`Σ Membership` 不变；
  - 迁移的人在哪本账户上、欠哪条债、给哪个产业出劳动全部可查；
  - 不得出现“人走了债自动消失”或“人走了原家户劳动没减”；
- `compile` 绿，既有测试不因组件新增变红（新测试收尾统一补）。

---

### S2. 新需求 → 候选生产 → 试产（纵向路径）

**目标**：运行期出现一种持续的新产品需求；有劳动能力、资源、工具、使用权和口粮的底层家户，能从预置候选库选择一种生产方式试产；
生产扩大后，部分家户积累生产资料，部分家户转为依靠别人提供的资料或报酬。

#### S2.1 运行期需求的权威状态与生命周期

**五条分离**（这是用户点名的“不能只写 naturalNeeds”）：

| 概念 | 权威载体 | 生命周期 | 是否进订单 |
|---|---|---|---|
| 自然需要 | `NeedProfile` + 人口（纯函数现算） | 随人口/商品参数变化 | 作为目标库存的一部分 |
| 持续消费 | `DemandBook` 的 `RECURRING` 项 | 按周期续期；显式起止日 | 是 |
| 一次采购 | `DemandBook` 的 `ONE_OFF` 项 | 成交/到期后消耗；可取消 | 是 |
| 库存缺口 | 订单生成时现算：`max(0, targetReserve + demandDue − onHand − incoming)` | 瞬态、不落盘 | 是 |
| 支付能力 | 订单生成时现算：预算与币种余额 | 瞬态、不落盘 | 是（cap） |

新增状态组件 `demands: Map<DemandId, HouseholdDemand>`：

```text
HouseholdDemand(
  DemandId id,
  HouseholdId household,
  CommodityId commodity,
  DemandKind kind,              // RECURRING | ONE_OFF
  long quantityPerCycle,        // 持续消费每周期量；ONE_OFF 为一次性总量
  long createdDay,
  long expiresDay,              // 到期日；RECURRING 可续期
  int priority,                 // 预算紧张时的次序
  Optional<ActorRef> source)    // 需求来源（事件、GOV、市场观察等，便于审计）
```

自然需要不存第二份：`NeedProfile` 只在 `EconomySeeder` / 载荷配置里存在（例如“每人每日粮 X、布 Y、新商品 Z”），
`DailyNeed` 由人口 × 配置现算，避免 `naturalNeeds` 被每日覆写的旧形态。

**命令**：
- `economy.AddDemand`：`householdId, commodity, kind, quantityPerCycle, startDay, expiresDay, priority, source`
  - handler 只写 `demands`；
  - 若该 commodity 在该家户所在市场没有价格 ⇒ 拒绝并点名 `economy.SetMarketPrice`；
  - 生效时间：命令落在 revision 的 tick D；订单最早在 **D+1 日**生成；若为 `ONE_OFF` 且 `urgent=true`，允许在 D+1 触发一次
    `URGENT_DEMAND` 市场轮（不改变 5 天例行轮的调度）；
  - 到期/成交：`ONE_OFF` 由市场成交 `reduceDemand` 递减，或过期后由周期关账纯函数移除；`RECURRING` 每周期重置为 `quantityPerCycle`。
- `economy.CancelDemand`：按 `DemandId` 取消；已冻结订单由市场轮释放。

**订单生成算法**（替换 `MarketSettlement.ordersFor` 里粮/布硬编码并保留旧口径）：

```text
desiredQty(household, commodity, day):
  needToday = dailyNeedPerCapita(commodity) * population
  target    = needToday * configuredReserveDays(commodity)
  incoming  = 已成交但未到货量 + 本轮已挂买单剩余
  recurring = demandBook.sumRecurringDue(household, commodity, day)
  oneOff    = demandBook.sumOneOffDue(household, commodity, day)
  gap       = max(0, target + recurring + oneOff - onHand - incoming)
  return gap

affordableQty(household, commodity, price, budgetAllocation):
  budget   = spendableMoney(household, market.numeraire)
  budget   = min(budget, budgetAllocation[priority])  // 多条需求共用一个钱包时按 priority 切
  quantity = floor(budget * PRICE_SCALE / unitLandedPrice)
  return quantity

placeOrder:
  for each commodity in market.prices (升序 commodityId):
    gap = desiredQty(...)
    if gap == 0: continue                    // “库存已足”不是失败
    qty = min(gap, affordableQty(...))
    if qty == 0: continue                    // 记录 NO_BUDGET / 价格不可及，不生成买单
    mint BuyOrder(reason = buildReason(kind, demandId), quantity=qty)
```

**为什么旧粮/布不被打破**：`NeedProfile` 对粮/布保留各自 5+30 天保留量口径；旧订单的默认参数映射到 `DemandBook` 的
`RECURRING` 缺省项；M2 一年期读数必须能逐值复现旧的粮/布订单量（除新需求与波动外，旧基线应同值）。

#### S2.2 预置候选生产库

新增状态组件 `candidates: Map<CandidateId, ProductionCandidate>`：

```text
ProductionCandidate(
  CandidateId id,
  CommodityId output,             // 主产品
  Map<CommodityId, Long> outputPerUnit,
  Map<CommodityId, Long> inputPerUnit,
  Map<AssetKind, Long> requiredAssets, // 土地/工具/作坊容量需求
  long laborPerUnit,
  long buildDays,                 // 建设/试产天数
  RegimeId regime,                // 复用封建/佃耕/家户/手工业模板
  LaborSource laborSource,        // SELF/FAMILY/TENANT/SERF/WAGE
  int trialRiskPerMille,          // 试产失败/损耗参数（固定预设，不允许 double）
  Set<UseRightKind> acceptedRightKinds)
```

- 候选库由 `economy.Seed` 的扩展载荷预置；运行期 `economy.RegisterCandidate` 只允许 GM/事件注册，**不**允许普通家户任意发明配方。
- `Industry` 的运行期创建不再手写配方：`IndustryFactory.fromCandidate(candidate, operator, useRights, hex)` 生成；
  禁止运行期通过 `economy.Seed` 的格/产业 ID 不一致旁路进入（S0 已修）。

#### S2.3 家户进入候选生产的决策算法（自治行为，不是命令）

进入行为发生在周期第一天（或 `progressDays==0` 的产业结算点），由 `EconomyEntrySettlement.planEntries` 纯函数产出意向，
再由日结算执行；GM 不直接指定“谁必须进入”。

```text
planEntries(economy, market, day):
  for each household h with population > 0 and availableLabor > 0:
    # 1. 持续需求信号
    signals = activeDemand(h) + observedShortage(h.commodity) + govProcurement(h.commodity)
    只保留 kind=RECURRING 且剩余周期 ≥ candidate.buildDays + 1 周期的商品
    if signals empty: continue

    # 2. 资源/资产/使用权筛选
    candidate = cheapestFeasibleCandidate(signals, h, market)
      feasible if:
        h 或可租佃/合伙的对象持有 acceptedRightKinds 的 UseRight 数量 ≥ requiredAssets
        本格 Industry.capacity 尚有剩余（不超占）
        可调用劳动 ≥ candidate.laborPerUnit * minTrialScale
        初始投入可在“自有库存 + 家庭可借 + 市场可买”范围内凑齐
        若需新建作坊/工具，candidate.buildDays 内不与其他进入者竞争同一 capacity

    # 3. 生计评价（整数、下限保守）
    expectedOutput = minTrialScale * candidate.outputPerUnit
    expectedRevenue = expectedOutput * min(marketRefPrice, recent成交价)  # 只用可观察价格
    expectedCost = 投入量按市场参考价折算 + 借入利息上限 + 家庭口粮保留
    expectedSubsistence = dailyRation(h.population) * (candidate.buildDays + 1周期)
    score = expectedRevenue - expectedCost - expectedSubsistence
    if score < configuredMinTrialMargin: continue

    # 4. 试错规模限制
    trialScale = min(配置上限, h.availableLabor / laborPerUnit, 可凑投入 / inputPerUnit)
    if trialScale < 1: continue
    每户每周期最多 1 条 trial；每次 trial 最多占用 h 可用劳动的一个配置比例（默认 ≤ 1/3）

    # 5. 产出意向（不直接写状态）
    emit EntryIntent(
      household=h, candidate=candidate, scale=trialScale,
      useRightPlan=..., inputSource=..., laborPlan=..., expectedDay=day + buildDays + 1)
```

**执行与失败处理**：

1. 意向在同一日结算时按 `(householdId, candidateId, hex)` 字典序顺序执行；
2. 执行时重新校验可行性（意向生成到执行之间可能已被其他事件改变）；
3. 创建 `Industry`（`status=TRIALING`，`progressDays=0`，`capacity` 来自 UseRight 计划）、`ProductionRelation`
   （来自 candidate + regime）、必要时创建 operator actor；
4. 投入在周期第一天按现有 `drawCycleInputs` 语义现扣；如果执行时发现投入不足：
   - 不得“扣一半硬开工”；
   - 已扣但未使用的投入按原账户退回，铸一条反向转移（reason=`TRIAL_ABORTED`）；
   - 真正消耗掉的试产成本记进当日 `ProductionLedger.losses` 与 `FlowRow` 的审计读数（具名原因，不静默蒸发）；
   - `Industry.status=ABANDONED`，使用权退回；
5. 若成功开工，`TRIALING -> ACTIVE` 的转换由 `progressDays` 达到 `buildDays` 且第一次产出发生触发；
6. 失败不产生人口死亡；失败的家户仍走自用生产与既有生计机制。

**生效时间**（逐日）：
- 命令写入 `demands/candidates`：revision tick D 即时生效为状态；
- 订单：D+1 日；
- 家户进入决策：下一个周期第一天（`progressDays==0` 的产业结算点），或 `urgent` 需求的 D+1 日；
- 试产产出：`buildDays + cycleDays` 后的第一次收获；在此期间不产生虚假产出。

#### S2.4 S2 真实文件/类

| 改动 | 文件/类 |
|---|---|
| 需求组件与命令 | `simos-economy-api/id/DemandId.java`、`CandidateId.java`、`UseRightId.java`；`simos-economy/model/HouseholdDemand.java`、`ProductionCandidate.java` |
| 订单生成 | `MarketSettlement.ordersFor` / `planOrders` / `householdLifeReserveOf` / `selfNeedOf` |
| 日结算 | `EconomySettlement.settleOneDay`、`EconomyDayStepper`、`EconomyChangeSet`、`EconomyCodec` |
| 进入算法 | 新 `EconomyEntrySettlement.java`（simos-economy/time） |
| 命令 | 新 `simos-economy/spi/AddDemandHandler.java`、`RegisterCandidateHandler.java`、`SetMarketPriceHandler.java` |
| app 读口 | `ApiViews.economyHex`、`MarketReadoutAssembly`（显示 natural/effective/standing/affordable 的口径与窗口） |
| app 装配 | `Shell` 注册新 handler；`EconomySeeder` 预置候选与初始需求 |

#### S2.5 旧存档与兼容

- `demands` / `candidates` 是新增组件：旧档缺键 ⇒ 空表（fail-closed 方向 = 没有显式需求/候选，不猜）；
- 粮/布旧行为由 `NeedProfile` 的默认项恢复：旧档没有 `DemandBook` 时，订单生成走“粮/布 5+30 天保留量”的旧路径，
  但必须与 M2 基线的订单量/成交额逐值一致；
- 若旧档已有 `naturalNeeds`/`effectiveDemand` 值，只作为迁移审计读数保留到首日，不把它们当作订单来源（旧路径本来也不读它们）；
- 新商品没有参考价时，`AddDemand` 拒绝；旧档不因缺价失败。

#### S2 完成标准

- 运行期通过 `economy.AddDemand` 注入新商品需求，D+1 能在开市订单里看到；
- 家户决策能产出一条 `TRIALING` 产业；`buildDays` 前无产出；失败时已消耗投入有具名去向，未用投入退回；
- 旧粮/布基线：相同种子、无新需求时，订单量、成交额、缺口与 M2 基线逐值一致（代表关账日）；
- `Σ人口/商品/货币` 不因进入/失败发生任何非零净变化；
- 验收场景通过（见 §5.1）。

---

### S3. 市场竞争、衰退、迁移与阶层分化

**目标**：市场能区分“买方已有库存 / 买方无钱 / 卖方价格或运输劣势 / 生产投入不足 / 卖不出去但仍可自用 / 真正无法维持再生产”；
经营者有正常→积压→缩产→债务压力→停业→退出的状态转移；劳动家户有自用→转业→迁移→失去生计→人口机制死亡的路径；
阶层由占有和劳动关系形成。

#### S3.1 可观察量与逐原因行为规则

新增读数组件（不落盘，市场轮报告内保留）：

```text
SellerOutcome(
  roundDay, actor, industryId, hex, commodity,
  offeredQty, filledQty, unfilledQty,
  unitPrice, unitCostEstimate, freightPerUnit,
  bestAcceptedLandedPrice, costRank,
  unfilledReason, outcompetedByActorCount, outcompetedQty)

BuyerOutcome(
  roundDay, actor, hex, commodity,
  stockOnHand, stockCoverDays, gapQty, desiredQty,
  spendableMoney, affordableQty, orderedQty, filledQty,
  unfilledReason)
```

**单位成本估计**（唯一拼写点，放在 `MarketSettlement` 或新 `ProducerCostBook`）：

```text
unitCostEstimate(industry, market) =
  Σ inputPerUnit[c] * referencePrice(market, c)   // 缺价按 0 计，但读数标 PRICE_MISSING，不假装便宜
  + laborPerUnit * subsistenceWagePerLaborMilli    // 来自配置；无货币工资制度也照可观察口径算
  + assetRentPerUnit(industry, useRight)           // 地租/工具租按 relation rules 或配置
  // 不把“家庭自用工”折成负成本；不引入现代折旧
```

**原因判定**：

| 可观察量 | 行为规则 |
|---|---|
| 买方已有库存 | `gapQty == 0` ⇒ 不生成订单；`UnfilledReason=NONE_STOCK_SUFFICIENT`；不把“没下单”算成卖方失败 |
| 买方无钱 | 逐买方 `spendableMoney < unitLandedPrice` 或预算分配为 0 ⇒ `NO_BUDGET`；**标签挂在买方 slot 上**，不再全局 |
| 卖方价格/运输劣势 | 同商品、同区或邻接区存在 `landedPrice` 更低且已成交/有剩余供给的卖方 ⇒ `OUTCOMPETED`；记录 `outcompetedByActorCount` 与 `outcompetedQty` |
| 生产投入不足 | `Industry.cycleInputUsedMilli` 不足，或 `capacityScaleOf` 被投入路压低 ⇒ `INPUT_SHORTFALL`；这是生产侧原因，不是市场原因 |
| 产品卖不出去但仍可自用 | 家户/经营者库存可覆盖自身保留量、下一周期投入或家庭消费 ⇒ 不算“无法再生产”；只是 `UNSOLD_SELF_USABLE` |
| 真正无法维持再生产 | 连续周期满足 `ReproductionStress` 的阈值条件（见 S3.3），且所有缓冲（库存、借款、使用权）耗尽 ⇒ `CANNOT_REPRODUCE` |

**撮合规则改动**：
- 区内同商品：先按 `unitCostEstimate + freightPerUnit` 升序排序卖方；同成本按 `(hex, actor)` 稳定升序；
  买方剩余需求优先分配给最低成本卖方；同成本层内仍按可用量与需求比例分配（现有 `ProportionalSplit`，溢出修复已由 S0 提供）。
- 跨区：对每条邻接路线，用 `landedPrice = seller.minPrice + freightPerUnit` 排序候选卖方；路线坐标只作为同价并列时的稳定键，
  不再作为唯一先到优势。
- 成交价仍由参考价与本区/卖方参考价决定（S3 不引入连续竞价）；成本只改变**谁先被选**，不改变成交单价。

#### S3.2 经营者状态机

新增 `IndustryStatus` 与 `OperatorCondition`（可放同一组件，或 `Industry.status` + `OperatorCondition` 组件；计划选独立
`operatorConditions: Map<IndustryId, OperatorCondition>`，避免再次改 `Industry` 构造函数）。

```text
enum IndustryStatus { ACTIVE, TRIALING, CONTRACTING, SUSPENDED, EXITING, EXITED, ABANDONED }

OperatorCondition(
  IndustryId industry,
  IndustryStatus status,
  long consecutiveUnsoldCycles,
  long consecutiveInputShortfallCycles,
  long cashReserveMilli,
  long debtPrincipalMilli,
  long debtServiceDueMilli,
  long lastCycleRevenueMilli,
  long lastCycleCostMilli,
  long lastCycleNetMilli,
  long unsoldStockMilli,
  long selfUsableStockMilli,
  String lastReason)
```

转移规则（全部由可观察量触发；阈值放 `StressPolicy` 配置，不写死在结算里）：

```text
NORMAL -> OVERSUPPLIED:
  连续 U 个市场轮 filledQty == 0 且 unsoldStock > N 个周期可销量
  且至少一轮有 OUTCOMPETED 证据
OVERSUPPLIED -> CONTRACTING:
  下一个周期第一天，plannedScale = min(当前规模, 可销售量 / (cycleDays * outputPerUnit))
  缩产不销毁 capacity/UseRight；只改 production plan / 劳动配额 / 投入计划
CONTRACTING -> INDEBTED:
  cash/goods reserve < nextCycle投入需求且没有自用可垫
  或者 selfUsableStock 已耗尽而家庭口粮靠借
INDEBTED -> SUSPENDED:
  连续 D 个周期 debtServiceDue > 可用偿付能力（余额 + 可自用产出 + 可借）
  或 reproductionStress 连续 S 个周期为真
SUSPENDED -> EXITED:
  operator 主动放弃经营（由状态转移，不是命令）；使用权按 kind 退回 holder；
  剩余库存/货币先偿债，不足部分 debt.defaulted=true（若策略允许），不得静默注销
SUSPENDED -> ACTIVE:
  出现持续需求、投入可筹、债务重组/偿还且剩余口粮安全 ⇒ 有限重开（重开次数有上限）
```

**谁承担损失**（按 `ProductionRelation` 与 `UseRight`）：

| 生产关系 | 投入损失 | 产出损失/积压 | 地租/分成 |
|---|---|---|---|
| `SELF` 家户自营 | 家户自己承担 | 家户自己承担 | 无 |
| `FAMILY` 家庭内 | 家户共同承担 | 家户共同承担 | 无 |
| `TENANT` 佃耕 | 佃农家户先承担（投入合同），地主按租约可能减租 | 佃农承担自留部分 | 固定实物租按约；欠租记具名 `RentArrears`，不自动清债 |
| `SERF` 庄园义务 | 庄园与农奴按下述优先级 | 庄园承担经营部分 | 先给养/口粮，再地租，再经营者剩余 |
| `WAGE` 雇佣 | 经营者承担经营风险 | 经营者承担；工资欠款记 `WageArrears`，不得把工资当 0 | 工资在 relation rules 里按 priority 先付，付不出进读数与 arrear |
| 自用生产 | 家户自己承担 | 家户消费/留种/库存 | 无 |

**处置权**：
- `UseRight.kind=OWNED`：持有人可转让/抵押（S3 只支持显式 `economy.TransferUseRight`，不做资产市场）；
- `TENANCY`：地主可收回，佃农在租期内有耕作权；退出/欠租时按合同状态转移，不允许经营者在没有 holder 同意时把土地带走；
- `COMMUNAL`：只能由 GOV/S3 的共同体规则调整，不可个体转让。

#### S3.3 劳动家户状态机与生计

新增 `HouseholdCondition`（可并入 `ClassRow` 的派生读数或独立组件）：

```text
HouseholdCondition(
  HouseholdId household,
  long unmetNeedMilliGrain,     // 本周期累计
  long unmetNeedMilliCloth,
  long debtStress,
  long laborSoldMilli,          // 本期通过 LaborAllocation 供给他人
  long laborSelfMilli,          // 自用生产投入
  long rentPaidMilli,
  long wageArrearsMilli,
  LivelihoodStatus status,      // SELF_PROVISION | TENANT | SERF | WAGE | DESTITUTE | MIGRATING
  long stressCycles)
```

规则：

1. **自用生产优先**：只要家户还持有 `UseRight`、工具和基本口粮，即使市场卖不出去也不自动转业/死亡；
2. **转业**：持续 `WAGE`/`TENANT` 收入低于 `subsistenceNeed`，且自用生产边际收益为 0 ⇒ `SELF_PROVISION -> WAGE/TENANT`，
   写 `LaborAllocation` 与 `ProductionRelation`（不改变人口）；
3. **迁移**：连续 `M` 周期 `CANNOT_REPRODUCE` 或 GOV/领主强制迁徙 ⇒ 走 S1 的 `MigrateHousehold`，人口份额守恒；
4. **失去生计**：没有使用权、没有劳动雇主、没有可迁移目的地 ⇒ `DESTITUTE`；不得直接命令死亡；
5. **死亡**：只有严重 `unmetNeed` 经现有 `PopulationDynamics.stressAfter` 抬升 `physiologicalStress`，再由月度
   `PopulationDynamics.monthly` 产生死亡；`EconomySettlement.applyFamine` 的默认致死率保持 0‰，除非策略显式配置；
   “饿死”不是主体选择，任何 `gov`/`economy` 命令都不得写“kill”/“die”。

#### S3.4 阶层分化算法

**原则**：阶层不是“产量达到 X 就生成地主”，而是由**占有**（UseRight）与**劳动关系**（ProductionRelation / LaborAllocation）
计算；允许聚合近似，但比例必须由可观察状态算出。

```text
classify(household):
  rights = UseRightBook.byHolder(household)
  relations = ProductionRelations.where(household is operator/inputSupplier/residualOwner)
  labor = LaborAllocationBook.byMember(household)

  # 四个可观察量，全部是整数
  ownLand = Σ rights[LAND].quantity
  netLaborSold = Σ labor where household is worker - Σ labor where household is employer
  netRentIncome = Σ rent rules received - Σ rent rules paid
  debtRatio = debtPrincipal / max(1, 经营资产估值)

  if ownLand == 0 and netLaborSold > 0 and netRentIncome == 0:
      return LANDLESS_LABORER
  if netRentIncome > 0 and netLaborSold <= 0:
      return LANDLORD
  if netLaborSold > 0 and ownLand > selfCultivationThreshold:
      return RICH_PEASANT
  if ownLand > 0 and netLaborSold == 0 and netRentIncome == 0:
      return MIDDLE_PEASANT
  if ownLand > 0 and (netLaborSold < 0 or netRentIncome < 0):
      return POOR_PEASANT
  # 佃农/手工业者/官署依附等由 laborSource + regime 决定
  return fallbackByLaborSource(relations)
```

- `selfCultivationThreshold` 是配置的**土地数量阈值**，不是产量阈值；
- 若某聚合行需要按比例表示，比例 = `可观察量的和 ÷ 样本数`（例如 `landlessShare = householdsWithoutUseRight / households`），
  绝不能用“产量达到 X”作为分类依据；
- `SocialClassId` 在现有四档后扩展 `landless_laborer`、`artisan`、`official` 等档位；旧四档值保持不变，
  `parse` 仍 fail-closed 到新词表；旧档只含旧四档仍可读；
- 分类变化写 `HouseholdCondition`/`ClassRow.view`，同时写一条具名 `class_transition` 审计事件（读口可查，不落“凭空生成人口”）。

#### S3.5 S3 真实文件/类

| 改动 | 文件/类 |
|---|---|
| 市场原因与成本排序 | `MarketSettlement.java`（`SellSlot`/`BuySlot`/`collectUnfilled`/`matchGroup`/`matchAcrossRegions`）；`MarketUnfilledReason.java` 扩展 `STOCK_SUFFICIENT`/`OUTCOMPETED`/`INPUT_SHORTFALL`/`UNSOLD_SELF_USABLE` 等档位 |
| 经营者状态机 | 新 `OperatorCondition.java`、`StressPolicy.java`、`EconomySettlement`/`EconomyDayStepper` |
| 家户状态机 | 新 `HouseholdCondition.java`、`HouseholdTransitionSettlement.java` |
| 使用权 | `UseRightBook.java`（派生索引）、`EconomyData` 守卫 |
| 生产关系 | `ProductionRelation`、`RegimeRelations`、`ProductionSettlement`（损失优先级/欠款读数） |
| 阶层 | `HouseholdClassRule.java`、`SocialClassId` 扩展、`ApiViews` 读口 |
| 命令 | `economy.TransferUseRight`、`economy.MigrateHousehold`、`economy.ReclassifyHousehold`（只做显式校验和状态落盘） |

#### S3.6 旧存档与兼容

- `operatorConditions` 缺失 ⇒ 从 `Industry.status` 推导；旧 `Industry` 没有 status ⇒ 全部按 `ACTIVE`/`NORMAL`，不凭空制造积压或债务；
- `UseRight` 缺失 ⇒ S1 迁移已从旧 `Industry.capacity + operator` 生成整额 OWNED 使用权；S3 只消费它，不再造第二份；
- `LaborSource` 缺失 ⇒ 旧关系的默认劳动来源按制度模板推导（feudal/handicraft/household/tenant 的旧语义），
  推导规则唯一写在 `RegimeRelations` 的旧档兼容入口，不散落在结算里；
- `SocialClassId` 扩展只追加新值，旧四档的 `parse` 行为保持不变；旧 actor id/账户键不因 class 扩展变化；
- 旧 `ProductionRelation.ToCohort` 已在 S1 迁移为一对一的 `ToHousehold`；S3 新增的 `ToView` 选择器不追溯改旧状态。

#### S3 完成标准

- 六个原因都有独立可观察量；同一轮里“某卖方零成交”不能再被全局 `no_budget` 掩盖；
- 受控场景（见 §5.3）下，低成本外地生产确实挤占本地市场，本地高成本经营者进入 `CONTRACTING`；库存仍可自用时家户存活；
- 真正无法再生产时，经营者可缩产、负债、停业、退出；退出时使用权、债务、剩余库存都有去向；
- 家户转业/迁移不改变人口总量；死亡只经 `PopulationDynamics` 的生理压力路径产生；
- 阶层分类结果能由 `UseRight` + `LaborAllocation` + 地租规则 + 债务逐值复算。

---

### S4. GOV 最小闭环：实物税／徭役、官署仓储、军队供给

**目标**：复用 Unit 组织结构承载行政节点，形成“征收实物 → 官署入库 → 向军队 Unit 供给”的第一条闭环；
不引入银行、央行、现代财政、货币发行、赤字货币化。

#### S4.1 模块与依赖边界

新增模块 `simos-gov`：

```
simos-util
  + simos-map
  + simos-economy-api
  + simos-actor-api
  + simos-unit        （只用 UnitId / UnitState 读侧；GOV 不拥有编制）
  → simos-gov
  → simos-app         （唯一组合根）
```

- `simos-unit` 不得依赖 `simos-gov`；在 `simos-unit/pom.xml` 的 bannedDependencies 中加入 `io.mosire:simos-gov`。
- `simos-economy` / `simos-actor` / `simos-social` / `simos-sd` 也不得依赖 `simos-gov`；各自 POM 补 ban。
- `simos-core` main scope 继续 domain-blind；若测试需要 gov，只走 test scope 的窄口，且必须说明传递依赖来源。
- `simos-app/pom.xml` 增加 `simos-gov`；父 POM `<modules>` 与 `dependencyManagement` 增加 `simos-gov`。
- `simos-sd` 的 `Nation` / `Army` 仍保留国家与军队归属；GOV 第一版**不依赖 sd**，行政主体按 `GovernmentId` 与
  `UnitId` 建模；军队供给目标直接引用 `UnitId`，由 app 在命令/时间协调时用 `Army.rootUnit` 把军队映射到 Unit。

#### S4.2 第一条实现切片（“征收实物 → 官署入库 → 向军队供给”）

**切片范围**：一个 `Government`、一个 `GovOffice`、一个 `GovLevyRule(GRAIN_PER_LAND)`、一个 `GovSupplyOrder`、一支根 Unit。
**最小链路**：

1. app 批量创建行政节点：`unit.CreateUnit(kind=ADMINISTRATIVE, position=seat)` → `gov.CreateOffice(seat, jurisdiction, granaryHexes)`；
2. `gov.SetLevy` + `gov.SetSupply`；
3. 下一个 `periodDays` 关账日：`GovSettlement.collect` 把税粮从家户账户转到各格 granary actor 账户，并写 `FlowRow.taxPaid`；
4. `GovSettlement.supply` 在 `targetUnit.effectivePosition == granaryHex` 时把粮从 granary 转到 UNIT actor 账户；
5. `GovSettlement.consumeUnitRations` 按 `member × perSoldierPerDay` 扣减 UNIT 账户并写 `GovFlowRow`；
6. 全程经 `EconomySettlement.applyTransfer` 唯一写口；无任何 `Unit.member` 直接改写。

**切片验收条件**：见 §5.6；若军队不在粮仓同格，必须停在 `SUPPLY_WAITING_FOR_UNIT` 或走 `ShipmentBatch` 在途，
不得瞬移、不得少货；`Σ农户粮 + Σ官仓粮 + Σ军队粮 + 军队消费` 逐步对账成立。

#### S4.3 Unit 侧复用与新增

**复用**：
- `UnitId` 作为行政节点稳定身份；
- `Unit.parent` 作为行政报告层级（父—子）；
- `CommandChain` 作为职能链（例如“税吏链”“仓吏链”）；
- `Unit.position` 作为官署驻地/仓储位置；
- `UnitState` 的引用完整性、`UnitChangeSet`/`UnitCodec`/replay。

**新增/收紧**：
- `UnitKind { MILITARY, ADMINISTRATIVE }` 作为 `Unit` 新组件，缺省 `MILITARY`（旧档 Jackson 缺键 ⇒ MILITARY）；
- `unit.CreateUnit` payload 增加可选 `kind`，缺省 `MILITARY`；
- `unit.PlanRoute` 对 `ADMINISTRATIVE` 直接拒绝（理由：官署不移动；要迁官署走 S1 的家户/机构迁移或专门命令）；
- 行政层级：创建时先建独立 `ADMINISTRATIVE` 节点（`parent` 空，position=驻地），再用 `unit.ReparentUnit` 设父；
  **不**使用 `attachSubtree`（`attached=true` 会带来军事随父移动语义），要求行政节点保持 `attached=false`；
- 生产拷贝点（`UnitOperations.copy` / `copyFormation` / `UnitMoves.evaluate` / `UnitTimeParticipant.withPositionAndMovement`）
  必须显式带 `kind`，禁止兼容构造器静默降级为 MILITARY；收尾测试设置 `UnitRoundTripTest` 加组件后自动红。
- 清理 `attachSubtree` 的旧 javadoc 块（S0 已做），避免再次把行政层级与“随父移动”混淆。

#### S4.4 GOV 状态

```text
GovState(
  Map<GovernmentId, Government> governments,
  Map<GovOfficeId, GovOffice> offices,
  Map<GovLevyId, GovLevyRule> levies,
  Map<GovSupplyOrderId, GovSupplyOrder> supplyOrders,
  Map<GovernmentId, GovFlowRow> flows,
  Map<GovConvoyId, GovConvoy> convoys)   // S4 可先空表，接口留住
```

| 类型 | 形状要点 |
|---|---|
| `GovernmentId` | 复用 `simos-economy-api/id/GovernmentId`（稳定契约） |
| `Government` | `(id, name, ActorRef treasury, Set<HexCoord> claimedHexes)`；treasury = `ActorRef(GOVERNMENT, govId)` |
| `GovOfficeId` | `gov-office-<govId>-<nodeUnitId>`，稳定、无 `.` |
| `GovOffice` | `(id, governmentId, UnitId node, Set<HexCoord> jurisdiction, HexCoord seat, Map<HexCoord, ActorRef> granaries)`；每个有粮仓的格一个**独立** `granary-<officeId>-<q_r>` actor，避免“同一 actor 的左右账户做转移”触发 `Transfer.from != to` 守卫 |
| `GovLevyRule` | `(id, governmentId, officeId, CommodityId commodity, LevyBasis basis, long ratePerUnit, long periodDays, long startDay, LevyPriority priority)`；`basis ∈ GRAIN_PER_LAND | GRAIN_PER_PERSON | CORVEE_PER_LABOR` |
| `GovSupplyOrder` | `(id, governmentId, officeId, UnitId targetUnit, CommodityId commodity, long perSoldierPerDay, int priority, boolean active)` |
| `GovFlowRow` | `(governmentId, collectedGoods, spentGoods, issuedToUnits, corveeServedMilli, arrears)`；按周期清零，读口有窗口标注 |
| `GovConvoy` | `(id, from, to, commodity, quantity, dispatchDay, arrivalDay, targetUnitId)`；第一版可以只定义与空表，或直接复用 `ShipmentBatch`（另一种实现选择，见下） |

**军队账户**：`ActorRef(ActorKind.UNIT, unitId.value())`，账户键 `(actor, unit.currentHex)`。app 时间协调器把这类账户
和 GOV 账户一起载入工作副本（`OwnershipBooks.loadActorGoods/loadActorMoney`，由现有 `operatorGoods/operatorMoney`
泛化而来）；`GoodsAccount` 是唯一真源，不给 Unit 加库存字段。

#### S4.5 命令面

| 命令 | 载荷 | 谁的状态 | 生效时间 |
|---|---|---|---|
| `gov.Seed` | governments / offices / levies / supplyOrders | gov | revision tick D |
| `gov.CreateOffice` | `officeId, governmentId, nodeUnitId, jurisdiction[], seat, granaryHexes[]` | gov；前置的 `unit.CreateUnit(kind=ADMINISTRATIVE)` 由 app 批量先发 | 一条 revision（批量） |
| `gov.SetLevy` | `levyId, governmentId, officeId, commodity, basis, rate, periodDays` | gov | 下一征收周期 |
| `gov.SetCorvee` | `levyId, governmentId, officeId, jurisdiction, laborMilliPerDay, startDay, endDay` | gov | 次日 |
| `gov.SetSupply` | `orderId, governmentId, officeId, targetUnit, commodity, perSoldierPerDay, priority` | gov；targetUnit 存在性由 app 用 unit 切片校验 | 下一供给日 |
| `gov.TransferToDepot` | `fromAccountHex, toDepotOffice, commodity, quantity` | gov + actor；经 batch/协调器 | 发运日 |
| `gov.DispatchConvoy` | `convoyId, officeId, targetUnit, commodity, quantity` | gov + economy shipments（二选一，见下） | 发运日 |
| `economy.SetMarketPrice` | `hex, commodity, numeraire, price` | economy | 下一市场轮 |

**跨模块命令协调**：
- 命令层：app 的 `GovAdminTool` / GM 工具把需要的 `unit.*`、`gov.*`、`economy.SetMarketPrice` 打成 `CommandBus.submitBatch`，
  一次 revision、整体回滚；Core 仍只认 type 字符串与 payload，不做 `instanceof`。
- 时间层：`PopulationEconomyTimeParticipant` 扩展为**单一政治经济参与者**，在同一 `simulateWorld` 里写
  `economy + actor + social + gov` 四个 namespace；任何时刻只有一个写者，避免 `TimeProposalResolver` 的写-写冲突。
  `GovSettlement` 是 `simos-gov` 的纯函数，只接受只读输入并输出 `GovChangeSet` 与 `Transfer`s；app 用现有
  `EconomySettlement.applyTransfer` 作为唯一落账口（需把该方法或专用外部转移入口提升为 public/包外可见，且仍走两遍式校验）。

#### S4.6 征收算法（实物税）

```text
assessLevy(day):
  for each active GovLevyRule where day % periodDays == 0:
    for each office in jurisdiction:
      for each household h in office.jurisdiction:
        base = levyBase(h, rule)                 # GRAIN_PER_LAND: 使用中的土地量; GRAIN_PER_PERSON: 家户人口
        due  = floor(base * ratePerUnit / 1000)  # 全整数、向下取整
        if due == 0: continue
        # 取粮上限：不得把家户吃到“下一天无口粮”或吃掉已承诺的种子/投入
        protected = h.dailyRation * reserveDays + h.seedReserve + h.frozenGoods
        take = min(due, max(0, h.grainBalance - protected))
        if take > 0:
          mint Transfer(from=h.actor, to=office.granaries.get(h.hex), location=h.hex,
                        goods={grain: take}, reason=TAX_IN_KIND)
          h.taxPaid += take
          office.collected += take
        arrears = due - take
        if arrears > 0:
          record GovArrear(h, rule, arrears, day)   # 不清债、不静默；后续周期可尝试征收
```

- **不进“一次没交上就饿死”的口径**：征收上限保护口粮/种子；欠税记 arrears；严重欠税在 S3 的 `debtStress/reproductionStress`
  中间接影响，不由 GOV 直接杀人。
- 仓库：办公室在每个 `granaryHexes` 格有**独立粮仓 actor**（`granary-<officeId>-<q_r>`），税粮入本格官仓，不瞬移到首都；
  同格供给时 `Transfer.from` 是本地粮仓 actor，`Transfer.to` 是军队 actor，二者必不相同。
- 支出：`GovFlowRow.spentGoods` 记录官署自身消耗、转拨、运输损耗；`GovOffice` 的支出规则第一版只允许“向军队供给”和“转运到官仓”，
  不做工资/工程投资。

#### S4.7 徭役算法

```text
serveCorvee(day):
  for each active GovCorvee rule:
    totalDemand = rule.laborMilliPerDay
    # 按管辖格内各 PeopleLot 的 availableLabor 比例分配，最大余数法、同余按 lotId 升序
    shares = split(totalDemand, lots.availableLabor)
    for each lot:
      served = min(shares[lot], lot.availableLabor - lot.alreadyCommitted)
      if served > 0:
        lot.servedLaborMilli += served       # 通过 LaborSupply.served 实现；不生成产业配额
        GovFlowRow.corveeServedMilli += served
      shortfall = shares[lot] - served
      if shortfall > 0: record GovFlowRow.corveeShortfall
```

- 调用点：`EconomyDayStepper.applyServedLabor(Map<PeopleLotId, Long>)` 在日结算**之前**设置本日 `servedLaborMilli`；
  日结算结束后由 GOV 状态在次日重新计算，避免把“每日”写死成一个不可变字段。
- 守恒：`grossLabor = Σ Industry allocations + served（徭役/军役）+ idle`；徭役不会凭空消耗或生成人口。
- 如果 GOV 要征发的人不在任何经济行/批次中，直接拒绝，不造幽灵人口。

#### S4.8 军队供给闭环算法

第一版实现选择：**同格供给 + 官仓到官仓的集中转运**；不直接跨格瞬移粮到军队。

```text
supplyArmy(day):
  for each active GovSupplyOrder:
    unitHex = unit.effectivePosition(targetUnit, day)
    if empty: record SUPPLY_UNIT_UNKNOWN; continue
    # ① 集运：办公室本地粮仓 → 目标格粮仓（目标格没有粮仓则先创建独立 granary actor）
    if office.seat != unitHex and office.granaries 不含 unitHex:
       # 第一版用显式 convoy（GovConvoy）或复用 ShipmentBatch（二选一）
       dispatchConvoy(office.granaries.get(office.seat) -> unitHex, commodity, quantity)
       # 货物在途期间不在任何账户；到货日进入 (目标格 granary actor) 账户
       continue
    # ② 同格发放：本地粮仓 → Unit actor 账户（同格，无瞬移）
    perDay = order.perSoldierPerDay * targetUnit.member
    available = office.granaries.get(unitHex).balance(commodity)
    issue = min(perDay, available)
    mint Transfer(from=office.granaries.get(unitHex), to=ActorRef(UNIT, targetUnit), location=unitHex,
                  goods={commodity: issue}, reason=GOVERNMENT_SUPPLY)
    GovFlowRow.issuedToUnits += issue
    # ③ 军事消费：Unit 账户按日扣减 member × rationPerSoldierPerDay
    consumed = min(unitAccount.balance(commodity, unitHex), perDay)
    debit unitAccount; GovFlowRow.consumedByUnits += consumed
    # 不足额只记 unmetSupply，不直接改 unit.member（unit 的战损/解散仍是 unit 模块职责）
```

- **运输实现二选一**（计划明确选第一种，避免新造平行运输）：
  1. **复用 `ShipmentBatch`**：新增 `TransferReason.GOVERNMENT_SUPPLY`，由 app 在 economy 的 `shipments` 表中创建
     `(TradeRoute(office.seat→unitHex), seller=发货格 granary actor, buyer=目标格 granary actor, lossBearer=BUYER)` 的批次；
     到货复用既有 `deliverShipments`；需要把在途买方从“家户/经营者”泛化到“任意 actor 账户”。
  2. `GovConvoy` 独立状态：不选，除非复用 `ShipmentBatch` 被证明会破坏市场口径。
- **军队消耗的下限**：第一版只扣库存；不足额进 `GovFlowRow` 与读口。是否影响 `Unit.member` 是 Unit 模块的后续裁定，不在 GOV 里偷偷做。
- **闭环验收链**：`Σ农户粮 - 税 = Σ农户粮'`，`Σ官仓粮 = 税入库 - 运输损耗 - 军队发放`，
  `Σ军队账户粮 = 发放 - 军队消费`；三式逐步可核。

#### S4.9 S4 最小针对性核查（不是泛调查）

在写 GOV 代码前只允许做以下最小核查，每项必须在任务报告里给 `文件:行`：

1. `UnitOperations.copy` 与 `copyFormation` 的字段清单，确认新增 `kind` 会被显式带过；
2. `UnitCodec` 对 `Unit` 新字段的 Jackson 缺键行为（旧档缺 `kind` 必须落 `MILITARY` 而不是 null/失败）；
3. `UnitMoves.evaluate` / `UnitTimeParticipant.withPositionAndMovement` 的 `Unit` 重建点，确认不漏 `kind`；
4. `UnitState` 是否允许 `member=0` 的行政节点（已核构造器允许，复核 `CreateUnitHandler` 不额外拒 0）；
5. `OwnershipBooks` 的 `loadOperatorGoods/loadOperatorMoney` 与 `land*` 方法能否泛化到 GOV/UNIT actor 而不改变家户/operator 的既有落账顺序；
6. `EconomySettlement.deliverShipments` 的买方解析能否识别任意 actor 账户（若选择复用 ShipmentBatch 方案）；
7. `EconomyData` 对 `LaborAllocation.actor.kind=GOVERNMENT` 的守卫行为（已核允许非产业型 actor 不解析到产业；复核 `reallocateLabor` 会跳过 GOV 配额不走这条，若走 `servedLaborMilli` 方案则本项只需确认 available 扣减不等式）；
8. `CommandBus.submitBatch` 是否满足“unit.CreateUnit + unit.ReparentUnit + gov.CreateOffice”原子批量（已核存在，复核 namespace 多模块落盘与事件）。

#### S4.10 旧存档与兼容

- 旧档没有 `gov` 切片 ⇒ `GovState.empty()`：不征税、不供给、不因缺键失败；`SimulationState` 装配允许 gov 切片缺席（旧档重放仍只含旧 namespace）；
- `UnitKind` 缺失 ⇒ `MILITARY`（缺省必须在 `Unit` 构造器/Jackson 绑定处唯一实现，不能让 null 流入操作面）；
- 旧 `GoodsAccount` 的 `GOVERNMENT/UNIT` 账户不存在 ⇒ 新命令创建前不报错；但 `gov.SetSupply` 的 targetUnit 必须在 unit 切片存在，否则拒绝；
- `FlowRow.taxPaid` 旧档恒 0，S4 首次写入后按周期清零；读口必须带窗口标注；
- 迁移/重放：旧 revision 不因新模块缺失而被判非法；需要比较总账时把缺失 gov 视为零状态，不把“没有数据”与“零”混同（读口用 `unavailable` 字段）。

#### S4.11 S4 真实文件/类

| 改动 | 文件/类 |
|---|---|
| 新模块 | `simos-gov/pom.xml`、`simos-gov/src/main/java/io/mosire/simos/gov/state/GovState.java`、`model/Government.java`、`model/GovOffice.java`、`model/GovLevyRule.java`、`model/GovSupplyOrder.java`、`model/GovFlowRow.java`、`change/GovChangeSet.java`、`codec/GovCodec.java`、`time/GovSettlement.java`、`spi/*Handler.java` |
| Unit 复用 | `simos-unit/.../Unit.java`（`UnitKind`）、`UnitOperations.java`、`UnitCodec.java`、`UnitChangeSet` 不新增组件；`CreateUnitHandler`、`PlanRouteHandler` |
| 父 POM/边界 | `pom.xml`（module + dependencyManagement）、`simos-gov/pom.xml`、`simos-unit/pom.xml`、`simos-economy/pom.xml`、`simos-actor/pom.xml`、`simos-social/pom.xml`、`simos-sd/pom.xml`、`simos-app/pom.xml` |
| app 协调/读口 | `PopulationEconomyTimeParticipant.java`、`OwnershipBooks.java`、`MarketTopologyBook.java`、`ApiViews.java`、`Shell.java`、GM 工具 |
| 账户/转移契约 | `simos-economy-api/.../TransferReason.java`（新增税/供给/徭役审计档位）、`simos-economy-api/.../money`（不新增发行） |

#### S4 完成标准

- `simos-gov` 模块可编译、无反向依赖；`unit`/`economy`/`actor` 的 POM ban 列表已补；
- 行政节点树可创建、可查询；官署有 jurisdiction、seat、granaries；
- 一条 `gov.SetLevy` 在下一征收期产生实物税转移，`FlowRow.taxPaid` 与官仓余额逐值对账；
- 一条 `gov.SetCorvee` 在次日降低相应 `PeopleLot` 的 `availableLabor`，生产配额不超可用；
- 一条 `gov.SetSupply` 在官仓与军队同格时产生转移；不同格时走 `ShipmentBatch` 在途，到货后才进入目的地官仓；
- 军队账户按日消费有具名读数；不足额不被静默丢掉；
- 旧档（无 gov 模块）可读：`GovState.empty()`，不因缺键失败。

---

## 4. 性能与确定性多线程

### 4.1 实测基线与优化目标

- 一年推进（799 经济格）：**976–986 s ≈ 16.3 min**。
- 360→390（30 天）嵌套：step 140.229 s + apply 29.147 s + land 0.346 s = **169.722 s**。
- 市场轮 126.03 s / 12 轮；其中地形索引重建 22.66%、跨区 31.95%、`refreshSellFrozen` 7.65%。
- `OwnershipBooks.apply` 29.15 s；关账日单日 apply 约 17.5 s。
- 每日末态构造/校验 9–11%；Replay 3%；单日 step 13.21 s（关账日）。

**建议目标**：799 经济格推进一年 **< 4 min（240 s）**。
**判断**：以现有热点构成，R1 的身份/账户内核 + P1.4/P1.5 批处理可先把 step+apply 降到基线的 45%–60%；
R2 的市场索引 + 确定性并行再配合 P3 的跨区协调后，**< 4 min 是有条件可达的**，但必须由 R1/R2 的实测决定是否重定门槛。
阶段门槛见 §4.7；如果 R1 后年推进仍 >10 min，则 4 min 目标在本机本次实现上判定为不可达，改按 §4.7 的降级门槛执行，
并如实报告测量依据（禁止用“理论上可以”替代测量）。

### 4.2 P1 工作项（分布到 R0–R2，不再独立执行）

| 编号 | 问题 | 算法/数据结构 | 真实文件/类 | 预期 |
|---|---|---|---|---|
| P1.1 | `MarketTopologyBook.moveCostAt` 每次调用 `map.terrainIndex()` 重建 59,223 条 | 在 `from(state)` 里一次构建 `Map<HexCoord,Integer> terrainCost`，把 lambda 改为查表；地形索引随 map revision 失效时可重算 | `simos-app/.../time/MarketTopologyBook.java` | 删除市场 JFR 的 22.66% 主项 |
| P1.2 | 市场轮内 O(买卖单 × 商品 × 格) 扫描 | 每轮一次建索引：`buysByHexCommodity`、`sellsByHexCommodity`、`sellsByRegionCommodity`、`adjacentRegions`；`orderedCommodities` 缓存 | `MarketSettlement.java` | 跨区 31.95% 的结构性下降 |
| P1.3 | `refreshSellFrozen` 反复重建卖单冻结 | 每轮维护 `Map<ActorRef, FrozenView>`，只在转让/成交后增量更新 | `MarketSettlement.java` | 去掉 7.65% 的重复扫描 |
| P1.4 | `OwnershipBooks.apply` 每条 entry 重建一次 `ActorData` | 先按 `GoodsAccountKey` 聚合 delta，再按 canonical key 顺序一次 `withAccounts` 批量写；保留条目的入账序 | `simos-app/.../time/OwnershipBooks.java` | 29 s/30 天 → 期望 < 8 s |
| P1.5 | 每日 `EconomyData` 全量构造/深拷贝/校验 | `EconomyStateBuilder` 持有可变工作表，只在 `step` 末尾构造一次 `EconomyData`；未变更组件复用 base map；把校验拆成“每日轻量不变量 + 关账日全量” | `EconomySettlement.java`、`EconomyDayStepper.java`、`EconomyData.java` | 每日末态 9–11% 下降 |
| P1.5a | 校验不可被性能优化绕过 | “轻量”只允许跳过当天语义上不可能变化的跨表校验；凡当日写过的组件仍走完整守卫；revision 边界（每次 `AdvanceTime` 结束）必须至少跑一次全量守恒校验；测试保留“坏数据必红”的判别用例 | 同上 | 性能绿不得以取消守卫为代价 |
| P1.6 | 关账日 21,721 条 fold/apply | 与 P1.4 合并；关账日按账户/动作分桶，禁用逐条 `withAccount` | `OwnershipBooks.java`、`ProductionLedger` 消费端 | 关账日 apply 17.5 s 下降 |
| P1.7 | 读口与结算各算一遍 `planOrders`/拓扑 | `MarketTopologyBook`/`MarketReadoutAssembly` 与日结算共享同一次 `topology` 和订单计划快照（仍不落盘） | `MarketTopologyBook.java`、`MarketReadoutAssembly.java` | 读时不重复市场计算 |

**这些工作项不做什么**：不改变任何经济语义、不改变订单/成交/守恒口径、不引入浮点、不缩单位、不改时间步长。

### 4.3 日结算依赖图与账户冲突表

**当前日序**（`EconomySettlement.settleOneDay`，以代码为准）：

```
0) 到货 deliverShipments
1) 周期投入 drawCycleInputs（周期第一天，可在消费前/后配置）
2) 消费 consumeOwnStock
3) 收获/关系分账 harvest + ProductionSettlement（关账日）
4) 劳动再分配 reallocateLabor（周期第一天）
5) 区域市场 MarketSettlement.clearOncePerCycle（每 5 天 + 低粮追加）
6) 借粮 lendDeficits（市场之后）
7) 偿还 repayDebts（关账日）
8) 饿死判据 applyFamine / scaleLabor
9) 计息 chargeInterest（关账日）
10) 人口回写（月度，由 app 协调器）
11) 流水组装
```

**依赖图（同日）**：
```
arrival -> inputDraw -> consumption -> harvest -> market -> lending -> repayment -> famine -> interest -> flows
                    \-> laborReallocation (周期第一天) --/
```
- 不同日期之间**严格串行**：第 d 天的输出是第 d+1 天的输入；不得并行推进两个日期。
- 同一天内部，上图标明的先后是语义依赖：市场必须在消费/收获之后、借粮之前；饥饿判据必须在所有救济之后；
  计息必须在偿还之后且只读当日起始本金。

**账户冲突表**：

| 阶段 | 读 | 写 | 可并行粒度 | 跨分区协调 |
|---|---|---|---|---|
| 到货 | `shipments`、买方账户 | 在途减、买方账户加 | 按目的地 `GoodsAccountKey`（先按 buyer 聚合） | 同一买方多批到货必须并到同一分区，或多个票按稳定序由单线程合并 |
| 周期投入 | 产业、关系、供方账户 | 供方账户减、产业 `cycleInputUsed` | **按 hex**（供方与产业同格由 `requireSupplierHex` 保证） | 同格外无冲突；跨格投入不允许 |
| 消费/借粮 | 家户账户、债务表 | 家户账户、债务表、`FlowRow` | **按 hex**（同格借贷） | 同格内家户之间串行；跨格借贷不在此阶段 |
| 收获/分账 | 产业、关系、家户/operator 账户 | 产出、operator/家户账户、`FlowRow` | **按 hex**；同 hex 内 farm/weave/craft 共享家户账户，必须串行 | 关系可能把产出给同格其他 cohort；不跨格 |
| 劳动再分配 | `laborSupply`、`allocations`、产业 | `allocations` | **按 hex** | 同一批次可供给多个 hex 的产业（罕见）；按 `PeopleLotId` 全局协调，或限制在本 hex 重排 |
| 区内市场 | 同区买卖单、账户、冻结 | 账户、冻结、`shipments`（跨区时） | **按市场区**；一个 actor 只属于一个 hex/区 | 跨区匹配会触碰两个区，必须在区内之后由统一协调器处理 |
| 跨区市场 | 所有邻接区买卖单、账户、冻结、在途 | 账户、冻结、`ShipmentBatch` | 按 **buyerRegion** 或多个候选 seller 路线 | 需求/预算/库存必须冻结并做全局校验；同价分配按全局稳定序 |
| 偿还/计息 | 债务表、账户 | 账户、债务表 | 按 **debtor account** 分组；同一 debtor/creditor 对的多条债必须同分区 | 债权人可能在另一格/另一分区；跨区分账要协调 |
| 饿死/劳动缩放 | 行、配额、供给 | 行人口、配额、供给 | 按 **PeopleLotId** / 产业 hex | 一个批次跨产业时同分区；死亡缩放需全局同序 |
| 人口回写 | social groups、经济行 | social group、economy 行/配额 | 按 `PeopleLotId` | 出生/死亡必须与行人口、配额、流水同序提交 |
| 流水组装 | 当日各阶段累加器 | `flows` | 按家户/hex | 必须等所有改动阶段结束 |

**结论**：
- 消费、同格投入、同格收获/借贷可**按 hex 并行**；
- 区内市场可**按市场区并行**；
- 跨区市场、债务利息/偿还、人口回写是**跨区/跨主体**的，必须由协调阶段处理；
- 账户是冲突的唯一实体：凡可能写同一 `(actor, location)` 的两个任务，必须分到同一分区或走冻结-协调-提交。

### 4.4 并行执行模型（线程本地计算 → 意向 → 冻结/校验 → 稳定提交）

1. **线程本地计算**
   - 每个 worker 拿不可变 base 的只读索引 + 自己的分区（hex/区/账户区间）；
   - 产出 `TransferIntent`、`AccountDelta`、`StateDelta`，**不直接写共享状态**；
   - 意向带确定性 `sequenceKey`：`(stage, partitionIndex, canonicalKey, intraIndex)`；
   - 分区函数只依赖 canonical 字符串（`CohortKey.toString()` / `ActorRef.toString()` / `IndustryId.value()`），
     1/4/8 线程下同一实体落同一分区。

2. **冻结/校验**
   - 市场阶段：先按分区计算**买方预算冻结**与**卖方库存冻结**；跨区协调器再做全局可用量校验；
   - 其它阶段：按账户 `AccountDelta` 校验“余额 − delta ≥ frozen”，不允许两个意向把同一账户扣成负；
   - 校验失败 ⇒ 整个推进拒绝（与现有 `TimeAdvance` 一致），不产生半截 revision。

3. **稳定顺序提交**
   - 单线程提交器按 `sequenceKey` 字典序执行 `applyTransfer` / 账户 delta 合并；
   - 唯一转账口仍是 `EconomySettlement.applyTransfer`（两遍式），并行路径不得出现第二个写口；
   - 同价分配：跨区撮合在协调器里按 `(landedPrice, costRank, canonical seller, canonical buyer, orderId)` 的全局稳定序
     一次性算出 `FillIntent`；禁止按 worker 到达顺序分配；
   - 若必须让多个线程提交，只允许按账户 hash 分片加锁，且同一账户的 delta 合并必须串行、按 canonical 顺序。

4. **跨区在途语义**
   - 跨区成交在发运日：卖方库存减、买方货币减、`ShipmentBatch` 加；买方库存**不加**（在途）；
   - 到货日：在途减、目的地买方账户加（扣损耗）；
   - 上述三件事只由协调器/单线程提交，确保“货不在两个地方同时存在”。

### 4.5 确定性、重放与比较口径

- 1/4/8 线程必须走**同一套分区与稳定序**；线程数只影响执行顺序，不影响任何算术的输入、累积顺序与 tie-break；
- 禁止 `HashMap`/`HashSet` 裸迭代进入算术路径；所有参与求和的容器使用 `LinkedHashMap`/`LinkedHashSet` 或按 canonical key 排序；
- `ProportionalSplit` 的 tie-break 从“数组下标”推广为“canonical 实体键 + 原下标”双重键，确保分区后顺序不变；
- 所有金额/商品量为整数定点（`long`）；求和顺序不影响整数值，但 `Math.addExact` 的溢出点检测必须确定；
- `Transfer`/`Debt`/`Shipment` 的 ID 生成必须是内容或确定性序号的纯函数，不得引入线程 ID、时间戳、UUID；
- 同初态与命令下，1/4/8 线程重复运行比较口径：
  1. **领域状态**：对每个 module snapshot 的 JSON 做 canonical 化（对象键排序、数组按业务身份排序），
     再比较 `Snapshot.equals`/canonical bytes；允许原始 JSON 数组顺序/Map 键序不同；
  2. **变更集**：`changeset_json` 规范化后比较；`command_id`/`correlation_id` 这类随机 UUID 排除；
  3. **账本**：`ActorData.accounts` 按 `GoodsAccountKey.toString()` 排序后逐值比较；
  4. **读口**：`/api/economy/hex` 等读口按 canonical 字段比较；
  5. **验收**：至少 3 次重复 × 3 种线程数，全部满足；若有差异，必须定位到具体字段。

### 4.6 定点、取整与溢出

- **单位不变**：毫商品、千分劳动、毫货币、日 tick、‰ 率；不把 `Long` 改 `int`、不缩单位、不改时间步长。
  理由：真实量级（人口 ~1.15e7、库存 ~1e11–1e12 毫、劳动 ~1e10）已经需要 64 位；改成 `int` 会把溢出从
  `ProportionalSplit` 一处扩散到全仓，且单位缩小会改变所有取整边界和存档语义。
  **若未来必须改单位或时间步长**：语义误差 = 每个取整点的边界与历史余额换算误差，必须新建 `rulesVersion`、
  旧档 fail-closed 拒绝或显式迁移，并用“旧档总量对账 + 新旧逐笔差分 + 一年期基线复现”三项验证；本计划不采用这种变化。
- **取整边界**（保持现有方向，写进 `NumericPolicy` 注释）：
  - 付款、转移、税：`ceil`（至少 1 毫的付款方向）；
  - 可买量、配额切分、给养义务、劳动折算：`floor`；
  - 分成：`floor(amount × rate ÷ 1000)`；残差用最大余数法；
  - 投入路：`drawn < inputPerUnit` ⇒ 0，不半开工；
  - 新产业试产：`trialScale` 和 initial input 必须整数；`scale < 1` ⇒ 不进入；
  - 微量交易：任何 `due > 0` 但 `take == 0` 的情况必须进 `unfilled/shortfall` 读数，不得静默付 0。
- **乘法溢出**：
  - `ProportionalSplit` 按 S0 用 128-bit/`BigInteger` fallback；
  - 其他点位（`ProductionSettlement.shareWithTotal`、`quantity×price`、`labor×participation`、`inputPerUnit×scale`、
    `money×1000`）统一走 `SafeMath.mulDivFloor/Ceil`（新增工具类，唯一拼写点，内部 128-bit 检测）；
  - 溢出是坏数据/极端状态，`SafeMath` 失败时抛具名异常而不是回绕。
- **验证办法**：
  - 对每个乘法点列出理论上界（沿用调查 §6.3 的算式），在测试中构造上界输入；
  - 用 `Math.multiplyHigh` 或 `BigInteger` 对照纯 long 快路的差分测试；
  - 存档层：同一状态迁移/回放前后 `Long` 逐值相等；若单位/步长变化则必须新建 `rulesVersion` 并失败旧档读取，
    **本计划不采用这种变化**。

### 4.7 性能与数据验收（只在 V 阶段；R0–R4 只编译）

> **修订说明**：本节旧版本把性能门槛放进 R1/R2，与用户 2026-09-28 裁定冲突。按新口径：
> **R0–R4 只写性能结构、只过编译；所有性能测量与数据验收在 V 阶段统一做。**

| 切片 | 性能结构工作项 | 编码期要求 | V 阶段验收 |
|---|---|---|---|
| R0 | P1.1 地形索引缓存 + tick0 基线装置 | 只编译；脚本可留待 V 执行 | 同一 tick0 存档 `360→390` 实测，报告真实收益 |
| R1 | P1.4 账户统一批处理 + P1.5 `EconomySession` | 只编译 | 360→390 单线程实测；与 R0 对比；旧档迁移守恒式在 V 验证 |
| R2 | P1.2/P1.3/P1.6 + P2 并行骨架 | 只编译；确定性分区/稳定提交写进代码 | 1/4/8 线程 canonical 一致；4/8 线程耗时；不达标先修稳定序/分区 |
| R3 | P3 跨区协调 + 关账日 + 万级结构支持 | 只编译 | 8 线程一年 ≤4 min 目标；否则按 §7.2 降级并报告依据 |
| R4 | 复用 R1/R2，不新开性能轨 | 全仓 `-DskipTests compile` 绿 | 最终压测、峰值内存、污染检查 |

**V 阶段的测量顺序（在一次 tick0 baseline 上）**：
1. 先跑 R0 后代码态的单线程 `360→390`、一年期；记录绝对秒数；
2. 再跑 R1 后代码态的相同区间；
3. 再跑 R2 后的 1/4/8 线程；
4. 最后跑 R3/R4 后的 1/4/8 线程与 10k 格扩展；
5. 所有对比必须写清代码态（commit/md5）、JVM、机器、store 来源；禁止跨代码态复用旧读数。

**明确保留的硬门（V 阶段）**：4 min 是建议目标；若 R1 后单线程一年 >10 min，则按 §7.2 的降级门槛与实测依据执行。

### 4.8 万级经济格扩展测试

- **夹具**：生成 10,000 个经济格（尽量覆盖多市场区/多地形/多产业），沿用同一 worldgen 参数与 seeding 逻辑；
  单独存到隔离 store（不可复用 799 格 store）。
- **测试**：1/4/8 线程各推进 360 天；测量 wall、峰值堆、每 30 天阶段计时、市场区数量与订单规模；
- **验收口径**：
  - 必须报告**实测**，禁止用 799 格线性外推；
  - 时延随经济格数不得出现超线性爆炸（市场算法应接近 `O(订单数 log 订单数 + 区数 × 邻接边)`）；
  - 若机器内存不足以跑 10k，先在 2.5k/5k 上跑，明确记录“10k 未跑/被内存阻断”，不得写成“通过”；
  - 数据落盘后加 `md5`/canonical 比对，证明 1/4/8 线程领域状态一致。

---

## 5. 代表性验收场景

### 5.1 新商品需求与试产（S2）

**初态**：一个农村家户有劳动、少量土地/工具使用权、粮够 35 天、无该新商品库存；市场有该商品参考价；候选库有该商品配方。

**命令/事件**：
- `economy.AddDemand(kind=RECURRING, quantityPerCycle=Q, startDay=D)`
- 可选 `economy.SetMarketPrice` 保证有价；候选库由 seed 预置。

**期望**：
- D+1 市场轮出现该家户的买单；
- 若买不到且决策分数为正，家户进入 `TRIALING`；
- 试产期间已消耗投入有具名流水，未用投入退回；
- 达到 `buildDays + cycleDays` 后首次产出；产量、投入、劳动与配方逐值一致；
- `Σ人口/商品/货币` 无净变化。

### 5.2 家户分化（S1/S3）

**初态**：同一格两户家户，A 有较多 `OWNED` 土地、B 只有劳动；双方都可交易/借贷。

**过程**：A 通过关系获得地租或分成，B 通过 `WAGE/TENANT` 出卖劳动；A 进入 `LANDLORD/RICH_PEASANT`，B 进入
`POOR_PEASANT/LANDLESS_LABORER`。

**期望**：
- 分类可由 `UseRight` + `LaborAllocation` + 地租规则复算；
- 没有任何新人口按比例生成；A/B 的身份稳定；
- B 的迁移/转业不改变人口总数；A 的资产来源有账；
- 若 A 放债给 B，债务本金、利率、偿还全部可追。

### 5.3 外地生产挤占本地市场（S3）

**初态**：本地高成本织户 + 邻接区低成本织户；同商品存在稳定需求；本地织户有库存且第一轮可自用。

**期望**：
- 成本低的外地卖方优先成交；
- 本地卖方未成交时得到 `OUTCOMPETED`，不是全局 `NO_BUDGET`；
- 本地织户库存先满足自身保留/下一周期投入，显示 `UNSOLD_SELF_USABLE`，不立即破产；
- 若连续多周期被挤出，转 `CONTRACTING -> INDEBTED/SUSPENDED`，最终可退出；
- 退出后使用权退回，库存/货币按偿债优先级有去向；没有货物/货币消失。

### 5.4 卖不出去但仍可靠自用存活（S3）

**初态**：家户自用生产粮/布，市场对该商品无需求或价格低于成本。

**期望**：
- 家户继续自用生产，`consumed` 与库存变化有来源；
- 不因一次没卖出去就触发死亡/破产；
- 只有长期 `reproductionStress` 且缓冲耗尽才进入 `MIGRATING/DESTITUTE`；
- 死亡只经 `PopulationDynamics` 的生理压力路径。

### 5.5 真实退出与转业（S3）

**初态**：一个经营者长期卖不出去、投入来源断、债务压力高；关联家户仍有人口和劳动。

**期望**：
- 经营者按 `NORMAL -> OVERSUPPLIED -> CONTRACTING -> INDEBTED -> SUSPENDED -> EXITED` 转移；
- 退出时操作者不再持有 `UseRight`；关联家户按 `LaborSource` 转入自用/佃耕/ wage/迁移；
- 债务留在原债务人或按显式拆分规则迁移；债权人侧可查；
- 人口、劳动、商品、货币逐值守恒；`defaulted=true` 只在策略允许且无资产可偿时出现。

### 5.6 GOV 征粮和供给军队（S4）

**初态**：一个 GOV 办公室管辖若干农村格；官仓在 A 格；一支军队 Unit 根在 A 格或异地 B 格。

**过程**：`gov.SetLevy` + `gov.SetSupply` / `gov.DispatchConvoy`。

**期望**：
- 征收日：农户粮 → 官仓（本格），`FlowRow.taxPaid` 与官仓余额对账；欠税进 arrears；
- 同格供给：官仓 → Unit 账户 → 军队消费，三段可核；
- 异地供给：官仓 → 在途（`ShipmentBatch`/convoy）→ 目标格官仓 → Unit 账户；不存在瞬移，也不存在货凭空消失；
- 军队补贴不足只进 `unmetSupply`，不改 Unit.member，不由 GOV 直接处死。

---

## 6. 守恒、重放与确定性验收

### 6.1 守恒式清单（每个阶段边界必须跑）

1. **人口**：`Σ Membership.count(lot) == PopulationGroup.count(lot)`；`Σ ClassRow.population == Σ Membership.count`。
2. **劳动**：`Σ allocations.laborMilli(lot) + servedLaborMilli(lot) + committedLaborMilli(lot) ≤ grossLaborMilli(lot)`；
   S4 徭役后还要满足 `served` 增量与 `GovFlowRow.corveeServed` 逐值一致。
3. **商品**：`Σ 账户余额 + Σ 在途 + Σ 生产在制量 = 初始存量 + 本期产出 − 本期消费 − 本期损耗 ± 显式转移`；
   每个 `Transfer` 的商品腿有对端；`TransferReason` 必填。
4. **货币**：`Σ 账户货币余额 = 初始货币 + 累计发行 − 累计回笼`；本阶段无发行/回笼 ⇒ 逐币种常数；
   `frozen` 是余额的子集，不计入总量。
5. **债务**：`Σ Debt.principal by commodity` 在借入/偿还/计息/拆分前后的变化必须由具名事件解释；
   禁止“人走了债消失”。
6. **使用权**：`Σ UseRight.quantity(industry, asset) ≤ Industry.capacity(industry, asset)`；
   使用权转移只改 holder，不改技术 capacity。
7. **关系**：每条 `ProductionRelation` 的 `operator/inputSupplier/residualOwner` 都可解析；
   `ToHousehold` 指向存在的家户；`ToCohort` 在 S3 语义下必须展开到非空视图集合或进 `unresolved` 读数。
8. **GOV**：`官仓存量 = 税入库 − 官署支出 − 运输损耗 − 军队发放存量`；军队账户变化只来自发放与消费。

### 6.2 重放/确定性验收

- 同一初态、同一命令序列、同一 JVM/机器；
- 1/4/8 线程各重复 3 次，推进 360 天；
- 比较口径见 §4.5；允许 JSON 键序不同，不允许业务数组顺序不同；
- 若出现差异，必须定位到具体 module/字段，不允许“整体看起来一样”。

---

## 7. 性能测量协议与最终门槛

### 7.1 固定条件（每次报告必须写全）

- 机器：CPU 型号/核心数、内存、是否 shared CI；`uname -a`、`lscpu` 摘要；
- JVM：`java -version`；`-Xms/-Xmx`、GC 配置；测试进程独占；
- 存档：store 路径、创建方式、revision、worldgen 参数；
- 代码态：git commit、jar md5；
- 命令：完全相同的 `advance` 区间、dump 次数、warmup；
- 测量：每档 3 次取中位数，报告 wall、peak heap、G1GC 次数/停顿；
- 对比：优化前后单线程、4 线程、8 线程；报告 **绝对秒数与加速比**。

### 7.2 门槛（全部在 V 阶段测；R0–R4 编码期只编译）

| 项 | 目标 | 若不可达 |
|---|---|---|
| R1 后单线程 360→390 | ≤ 110 s | 在 V 阶段回 profile，修完热点再继续 R2 验收；不得在编码期用“未测”当达标 |
| R1 后单线程一年 | ≤ 10 min | 由 V 阶段报告实测热点与下一轮算法项 |
| R2 后 8 线程一年 | ≤ 4 min | 报告实测；阶段性门槛改为 **≤ 8 min 单线程 / ≤ 5.5 min 4 线程 / ≤ 4.5 min 8 线程**，由用户复核 |
| 万级 10k 一年 | 实测报告 + 无超线性爆炸 | 内存不足则跑 2.5k/5k 并如实写“10k 未跑” |
| 峰值内存 | 不 OOM；报告峰值堆与系统余量 | 若逼近机器上限，先做数据结构内存优化，不用“加内存”替代 |

---

## 8. 风险、待裁点与最小核查清单

### 8.1 风险

| 风险 | 影响 | 对冲 |
|---|---|---|
| S1 身份迁移面过大 | 全仓 key/change set/codec 红 | 先 S0；一次性补齐组件；旧档迁移器优先做 |
| S2 需求/候选库改动影响旧粮布基线 | 一年期读数漂移 | 旧需求映射到默认 `DemandBook`，先跑旧基线复现 |
| S3 成本排序改变成交身份 | 重放/读口不稳定 | 稳定 tie-break 到 canonical key；新旧成本同价时行为不变 |
| S4 复用 ShipmentBatch 破坏市场口径 | 在途/损耗/读口漂移 | 先做最小同格供给；异地供给二选一，走已有 `deliverShipments` 但 buyer 泛化必须单独测试 |
| 并行提交引入非确定性 | 重放不一致 | 线程本地意向 + 稳定有序提交；1/4/8 对比；禁止按到达顺序分配 |
| 4 min 目标不可达 | 阶段验收失败 | P1 后重估；按 §7.2 报告测量与降级门槛 |

### 8.2 已裁定的设计选择

1. **身份选方案 A**：稳定 `HouseholdId`，`CohortKey` 变为视图。
2. **需求权威 = `DemandBook` + 纯函数自然需要**；`effectiveDemand` 只为读时/计算量，不落第二份“权威”。
3. **候选库预置、运行期不可发明配方**；进入决策自治，GM 只能注入需求/价格/候选。
4. **阶层由 UseRight + LaborAllocation + 地租规则派生**，不用产量阈值。
5. **性能不换单位、不换时间步长、不缩 `Long` 为 `int`**；只做缓存/索引/批处理/128-bit 安全乘除/确定性并行。
6. **GOV 第一版复用 UnitId/parent/CommandChain/position**；新增 `UnitKind`，行政节点不移动、不用 `attach` 随父移动语义。
7. **跨模块命令由 app 用 `CommandBus.submitBatch` 协调；时间推进由单一政治经济参与者写四模块**；Core 仍不认领域类型。

### 8.3 最小针对性核查（写代码前，逐项必须给 `文件:行`）

- `UnitOperations.copy` / `copyFormation` 字段清单；
- `UnitCodec` 对新增 `kind` 的旧档缺键行为；
- `UnitMoves.evaluate` / `UnitTimeParticipant.withPositionAndMovement` 的重建点；
- `OwnershipBooks` 的 `loadOperatorGoods/loadOperatorMoney` 泛化到任意 actor 的落账顺序；
- `MarketSettlement.deliverShipments` 的 buyer 解析扩展（若复用 ShipmentBatch）；
- `EconomyData` 对 GOV actor 的 `LaborAllocation` 守卫与 `reallocateLabor` 的跳过行为；
- `CommandBus.submitBatch` 多 namespace 命令的 revision/事件形状；
- `EconomySettlement.applyTransfer` 提升为跨模块唯一写口时的可见性与两遍式校验不变量。

---

## 9. 编码 Agent 执行顺序与文件所有权

### 9.1 顺序（联合重构，一个 R 切片一个写代码代理）

> **当前工作树状态（2026-09-28 晚）**：R0+R1 已由一个前台子代理产出 candidate（未提交、未测试、未独立评审）。
> 下一步不是从零重写 R1，而是派 **R1 收口代理**在 candidate 上按修订后的计划补齐/修正，保证 `-DskipTests compile`
> 绿，并把所有“未验证”明确留到 V 阶段；随后派 R2。R1 收口代理不得用“删守卫/放宽守卫”换编译。

1. **R0 前置修复与基线**（已完成 candidate）：S0 的种子旁路 + `ProportionalSplit` + Unit 注释清理；P1.1 地形缓存；
   tick0 基线装置脚本。
2. **R1 账户/身份内核收口**（下一个前台代理，大；过大时按“线程安全会话/提交层 / 身份迁移层”两层拆，不按 S1 子条拆）：
   **第一优先级**：线程安全会话/提交内核（账户分区、线程本地意向、冻结/校验、稳定提交、可按 hex/区并行骨架）；
   **第二优先级**：在既有 candidate 上补齐 `AccountSession`、`HouseholdId`/`Membership`/`LaborAllocation.household`/`UseRight`、
   `EconomyData`/变更集/codec/旧档迁移、`OwnershipBooks` 批处理、`EconomySession` 单次构造；清理已知遗漏
   （`operatorConditions` 组件、Membership 逐 lot 一致性、旧档 pending 路径）；只编译，不跑测试。
   若上下文不足，优先保线程安全内核，剩余算法项明确留给 R2，不得退化成单线程临时实现。
3. **R2 订单/结算执行器**（R1 收口编译通过后派）：
   `OrderPlanner`、`DemandBook`、候选/进入、市场索引与冻结视图、并行日结算骨架、稳定提交。文件面：
   `MarketSettlement` 的订单生成段、`EconomySettlement` 日结算尾部、`EconomyDayStepper`、`OwnershipBooks` 提交层、新并行协调类。
4. **R3 市场/生产关系/衰退**（R2 编译通过后派）：成本排序、逐买方原因、经营者/家户状态机、迁移、阶层分类、跨区协调。
5. **R4 GOV 最小闭环**（R3 编译通过后派）：`simos-gov`、`UnitKind`、app 协调/读口；复用 R1/R2 的账户与执行器，不另起结算路径。
6. **V 最终测试/数据阶段**（额外测试代理，方案甲，不计入 4 个编码代理）：统一补测试、变异自证、`clean verify`、
   tick0 场景、1/4/8 重放、性能、万级读数、旧档迁移兼容。

**并行纪律（修订后）**：R0 内部可以同批做“S0 校验”和“P1.1 地形缓存”（文件不重叠）；R1 起不再拆分并行轨道，
因为身份键、账户会话、批处理、codec 改的是同一批文件。R1 与 R2 之间的并行只允许“R2 的新类先写接口/纯函数”，
不得提前改 R1 尚未冻结的 `EconomyData`/`EconomyChangeSet`。

### 9.2 联合重构的文件所有权

- **R1 owner**：`EconomyData.java`、`EconomyChangeSet.java`、`EconomyCodec.java`、`ClassRow.java`、`FlowRow.java`、`Debt.java`、`HouseholdActors.java`、`LaborAllocation.java`、`UseRight.java`、`Membership.java`、`EconomySettlement.java`（身份派生路径）、`EconomyDayStepper.java`、`OwnershipBooks.java`、`MarketSettlement.java`（参与者/键面）、`EconomySeeder.java` / `HouseholdSeeder.java`。
- **R2 owner（R1 完成后）**：`MarketSettlement.java`（订单/索引/撮合执行段）、新 `OrderPlanner` / `EntrySettlement` / 并行协调类、`EconomyDayStepper` 的会话提交层。R2 不改 `EconomyData` 组件形状，除非 R1 发现漏项并回写。
- **R3 owner**：`MarketSettlement.java` 的原因/成本段、`ProductionSettlement` / `ProductionRelation` / `RegimeRelations`、新状态机/分类类；与 R2 的 `MarketSettlement` 串行交接。
- **R4 owner**：`simos-gov/**`、`Unit.java`/`UnitOperations.java`/`UnitCodec.java`、app 的 `Shell`/读口/工具；不碰 economy 结算核心，除非 R1/R2 已定的接口需要扩展。
- **控制方**：只做派单、审、验收、提交；不把生产代码写进计划，不在同一文件上同时放两个写代码代理。

---

## 10. 完成定义（DoD）

**R0–R4 编码期（每个 R 切片）完成 = 只过以下项：**

1. **编译绿**：`tools/mvn-lock.sh -q spotless:apply && tools/mvn-lock.sh -DskipTests compile`；
2. **生产代码落地**：该 R 切片对应的 §3/§4 生产代码全部进工作树；
3. **数值行为清单**：会改变数值/身份/读数/顺序的改动逐条列出，硬编码字面量清单附上；
4. **不得撒谎**：没跑的测试/模拟/性能一律写“未跑”，不得写成通过；不得为编译方便删除/放宽守卫；
5. **不 commit**：由控制方审核后再提交；不得在别的代理正在编译时改 Java。

**V 最终阶段完成 = 全部生产代码之后：**

1. 既有测试适配 + 新测试补齐；`clean verify` 全绿；
2. 守恒/不丢失/静默付 0/断粮四类关键项变异自证；
3. tick0 世界六类场景验收（§5）；
4. 1/4/8 线程 canonical 一致（§4.5/§6.2）；
5. 性能报告：R0/R1/R2/R3 各结构在同一 tick0 存档上的单线程/4/8 线程读数，799 格一年与 10k 格扩展；
6. 旧 tick360 store 迁移/重放兼容；
7. 如实报告“未做/未验证”。

**最终验收以 V 阶段的实测数据为准**；R0–R4 的“只编译通过”不得被表述为“功能/性能/守恒已验证”。


---

## 附录 A：关键真实文件索引

| 概念 | 文件 |
|---|---|
| 经济状态（10 组件） | `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java` |
| 经济变更集 | `simos-economy/.../change/EconomyChangeSet.java` |
| 日结算/写口 | `simos-economy/.../time/EconomySettlement.java` |
| 日推进器（会话副本） | `simos-economy/.../time/EconomyDayStepper.java` |
| 市场撮合 | `simos-economy/.../time/MarketSettlement.java` |
| 生产关系 | `simos-economy-api/.../relation/ProductionRelation.java` |
| 阶层行 | `simos-economy/.../model/ClassRow.java` |
| 家户身份拼写 | `simos-economy-api/.../cohort/HouseholdActors.java` |
| 账户 | `simos-actor/.../model/GoodsAccount.java` |
| 账务落回 | `simos-app/.../time/OwnershipBooks.java` |
| 人口—经济协调器 | `simos-app/.../time/PopulationEconomyTimeParticipant.java` |
| 区域拓扑 | `simos-app/.../time/MarketTopologyBook.java` |
| 读口 | `simos-app/.../gui/ApiViews.java` |
| Unit 身份/编制 | `simos-unit/.../Unit.java`、`UnitState.java`、`UnitOperations.java` |
| Unit 命令 | `simos-unit/.../spi/CreateUnitHandler.java` 等 |
| GOV（计划新增） | `simos-gov/src/main/java/io/mosire/simos/gov/...` |
| 进程控制/推进 | `simos-app/.../tools/write/AdvanceTool.java`、`simos-core/.../advance/TimeAdvance.java` |

## 附录 B：命令清单（计划新增）

| 命令 | 阶段 | 写入模块 |
|---|---|---|
| `economy.AddDemand` / `economy.CancelDemand` | S2 | economy |
| `economy.RegisterCandidate` / `economy.SetMarketPrice` | S2 | economy |
| `economy.EnterProduction`（仅 GM 调试；自治路径不依赖它） | S2 | economy |
| `economy.TransferUseRight` | S1/S3 | economy |
| `economy.MigrateHousehold` / `economy.RelocateHousehold` / `economy.ReclassifyHousehold` | S1/S3 | economy + actor（batch） |
| `unit.CreateUnit`（扩展 `kind`） | S4 | unit |
| `gov.Seed` / `gov.CreateOffice` | S4 | gov |
| `gov.SetLevy` / `gov.SetCorvee` / `gov.SetSupply` | S4 | gov |
| `gov.TransferToDepot` / `gov.DispatchConvoy` | S4 | gov + economy/actor（batch） |

## 附录 C：不许做的事（负面清单）

- 不得把“一次没卖出去”直接判破产；
- 不得把“饿死”写成主体主动选择；死亡只走人口机制；
- 不得按比例凭空生成新人口、新货币、新商品、新土地；
- 不得硬编码“产量达到 X ⇒ 产生地主”；
- 不得用全局 `no_budget` 证明某经营者竞争失败；
- 不得用 `economy.Seed` 的格/产业 ID 错位旁路实现运行期进入；
- 不得在并行路径里绕过 `applyTransfer` 写账户；
- 不得用 `double` 决定钱/粮，不得为性能把 `Long` 改成 `int` 或缩小单位/时间步长；
- 不得让多个线程直接抢写同一本 `GoodsAccount`；
- 不得跨日期并行推进。
