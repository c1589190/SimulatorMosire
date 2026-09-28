# F — 家户能否真正改变阶层与生产关系（只读调查，第 2 题）

> **性质**：只读调查报告。不设计、不改 `src/**`、不改 `docs/**`、不写台账、不 `git add/commit`、不跑 Maven/服务、不跑整年。
> **代码态**：`ts/m1` @ `a7fbfe46`。基线报告写的是 `5b6b2be7`；实测 `git diff --stat 5b6b2be7..HEAD -- '*/src/**'` = **0 个文件**（HEAD 上只有 docs 提交），故本文所有生产代码 `文件:行` 与基线报告同源。
> **先读的基线**：`docs/superpowers/reports/2026-09-28-economy-system-flow-report.md`（下称"流程报告"）与 `-economy-investigation.md`（下称"调查 1–8 报告"）。
> **标记约定**：
> - 【代码事实】= 回当前工作树代码核过（给 `文件:行`）；
> - 【推断】= 由代码结构推出来的、未实跑验证的后果；
> - 【文档事实】= 设计/计划/台账原文（不是代码事实）；
> - 【用户目标】= 派单问题本身要回答的目标，不等于代码已有能力。
>
> **一行的总答案**：今天**没有**任何一条命令/协调器/结算路径能把一个家户的**阶层身份**（以及配套的劳动、账户、债务）当作"可迁移的状态"来改；代码只允许**批量人口按批次（PeopleLotId）**增删/缩放，以及**配额在同一格的产业之间**搬。阶层行是人口池在创世时按固定份额投影出来的**视图**，家庭主体（actor/账）的身份键里**直接含着阶层**，所以"换阶层"等于换主键；而换主键的关联表（劳动、账户、债务、流水、关系、在途）都没有配套写入口。`reallocateLabor` 能改的只是"这批人的劳动配额归哪个产业"，不是户的阶层/身份（见 2.2）。

---

## 〇、先给一张"身份 ↔ 状态"总表

| 身份 | 类型/键 | 与家户行的绑定 | 创世写入口 | 运行期写入口 |
|---|---|---|---|---|
| 家户行 | `CohortKey(hex, residence, stratum)`；`EconomyData.classes` 的键。`ClassRow.key()` 必须等于该键 | 行本身 | `economy.Seed`（`EconomyPayloads.toData`） | 只有结算：日需求覆写、饿死缩口、月度生死回写 |
| 家户主体 | `ActorRef(HOUSEHOLD, "<q>_<r>:<residence>:<stratum>")`；由 `HouseholdActors.of/ idOf` 拼 | 与行一一对应（唯一拼写点） | `actor.Seed` | 无独立命令；只由推进时的账本落回改写余额 |
| 家户账 | `GoodsAccountKey(owner=ActorRef, location=hex)`；`ActorData.accounts` | `(HouseholdActors.of(key), key.hex())` | `actor.Seed` | `OwnershipBooks.apply`（按条目）/`landHouseholdGoods`/`landHouseholdMoney`（按绝对值） |
| 人口批次 | `PeopleLotId`；`PopulationGroup`（social） | **键上无阶层**；只能按"格 + 居住类型前缀"映射到该组四行 | `social.SeedGroups` | `PopulationDynamics.monthly`（协调器内）、`applyDailyStress` |
| 劳动供给 | `PeopleLotId`；`LaborSupply` | 无阶层；供给属于批次，四行共用 | `economy.Seed`（`EconomySeeder.appendSupply`） | 饿死时 `scaleSupply`（按批次存活比例缩） |
| 劳动配额 | `LaborAllocationId`；`LaborAllocation(group=PeopleLotId, actor=产业主体)` | 无阶层；行→产业关系由"配额表 + 批次居住前缀"**反推** | `economy.Seed`（`appendAllocation`） | `reallocateLabor`（同格重排）、`scaleLaborOfGroup/Industry`（饿死缩） |
| 债务 | `Debt(debtor, creditor: CohortKey)` | 两端都是家户行键 | `economy.Seed` 只接受空债务 | `lendDeficits`/`repayDebts`/`chargeInterest` |
| 流水 | `FlowRow(key: CohortKey)` | 键 = 家户行 | `economy.Seed`（空） | `settleOneDay` 逐行重建/清零；月度 `withLifecycle` |
| 生产关系 | `ProductionRelation(activity, operator, inputSupplier, rules, residualOwner)`；`Recipient.ToCohort(CohortKey)` | 规则受方含阶层（四档各一条；地租写死 `LANDLORD`） | `economy.Seed`（载荷） | **无**（结算只读，原样带过） |
| 市场参与者 | 无独立表；每轮由 `EconomyData.classes` 的键现算 | 每个 `CohortKey` 一个参与者（`HouseholdActors.of`） | 不需要注册 | 不需要注册（随行集自动出现） |
| 在途批次 | `ShipmentBatch.allocation.buyer/seller: ActorRef` | 家户 actor 引用**写在在途记录里** | 无（创世无在途） | 市场发运/到货；无改绑入口 |

上面每一行的证据与读写路径在下面 F.1–F.6 展开。

---

## 一、身份与状态的全部关联

### F.1 `CohortKey`（格 + 居住类型 + 阶层）的构造/解析与"唯一拼写点"

**结论**
- 【代码事实】家户行的身份键就是 `CohortKey(HexCoord, ResidenceKind, SocialClassId)`；规范串是 `<q>_<r>|<residence>|<stratum>`，`toString()` 与 `parse(String)` 同住一个文件，是**该字符串格式的唯一拼写点**（`CohortKey.java:37, 58-92`）。
- 【代码事实】家户 **actor id** 是另一套形状 `<q>_<r>:<residence>:<stratum>`，由 `HouseholdActors.idOf/ of/ cohortOf` 独占拼写与反解（`HouseholdActors.java:33-34, 38-53, 61-80`）；两套格式**故意不同**：`GoodsAccountKey` 的规范串 `<owner>|<location>` 按第一个 `|` 切，actor id 若含 `|` 会让存盘往返当场抛（`HouseholdActors.java:17-26`；`GoodsAccountKey.java:24-37, 65-85`）。
- 【代码事实】`CohortKey` 的**类型化构造点**有多处（创世/载荷/默认关系/结算派生），但这些不是"格式拼写点"：字符串只由 `CohortKey.toString()` 写、只由 `CohortKey.parse()` 读。
- 【文档事实】`SocialClassId` 是**封闭词表**：只有 `poor_peasant / middle_peasant / rich_peasant / landlord` 四个值，词表外构造即抛（`SocialClassId.java:31-57`）。因此"新阶层"若指**新阶层名**，今天在类型层就不存在；若指**四档中的另一行**，键是存在的（创世每格每组四行都播，含人口 0 的行）。

**关键代码位置**
- 键定义/守卫/规范串/逆：`simos-economy-api/src/main/java/io/mosire/simos/economy/api/cohort/CohortKey.java:37, 45-56, 58-62, 64-92`。
- actor id 唯一拼写点：`.../api/cohort/HouseholdActors.java:33-34, 38-53, 55-80`。
- 居住前缀/词表：`.../api/cohort/ResidenceKind.java:22-43, 50-95`。
- 阶层词表：`.../api/id/SocialClassId.java:22-75`。
- 类型化构造点：
  - 载荷解析：`simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java:694-732`（`classRow`：`residence` 必填、`slot` 解析后 `new CohortKey(hex, residence, slot)`）；
  - 默认生产关系绑定：`simos-economy/src/main/java/io/mosire/simos/economy/model/RegimeRelations.java:493-523`（`laborCohorts` × `SocialClassId.all()`，`toRule` 里 `new CohortKey(hex, residence, spec.stratum())`）；
  - 结算派生行集：`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:2254-2270`（`householdKeysAt`：按 `SocialClassId.all()` × `ResidenceKind.all()` 生成键，只留真实存在的行）、`:2300-2323`（`industriesOfHouseholds`）；
  - 创世播种：`simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java:1508-1522`（`new SocialClassId(CLASS_IDS[i])`）。
- 字符串解析点：`EconomyChangeSet.java:134,136` 的 `CohortKey::parse`（变更集回放重建 classes/flows 的键）；`EconomyResolver.java:116-135`（`class` / `flow` 地址局部名 = `key.toString()`）；`EconomyPayloads.java:434-448`（`recipient.cohort` 解析）。

**实际读写路径**
1. 创世：`EconomySeeder.cohortGroup`（`:1508-1522`）把 `<residence, slot>` 写成载荷 → `EconomyPayloads.classRow`（`:694-732`）→ `EconomyData` 构造期判"键 == 行内 key"（见 F.2）。
2. 推进：协调器的读写地址用 `key.toString()`（`PopulationEconomyTimeParticipant.java:117-127`、`EconomyOwnershipTimeParticipant.java:116-126`）；`EconomyResolver` 也按同一串解析（`:116-135`）——两处必须逐字同串（两文件注释都点名了这一点）。
3. 家户 actor：所有把"家户身份 → actor"的路径都只经 `HouseholdActors`：`OwnershipBooks.accountKeyOf`（`:402-408`）、`EconomySettlement.householdActorsOf`（`:3616-3622`）、`MarketSettlement.participantsFor`（`:1663-1667`）、`ProductionSettlement.recipientOf`（`:511-517`）、`HouseholdSeeder`（`:132-141, 195-211`）。
4. 地址/查询：`ActorRef` 的规范串是 `kind:id`、按第一个 `:` 切（`ActorRef.java:29, 40-42, 59-70`），故家户 id 里含 `:` 合法；`GoodsAccountKey` 的规范串按第一个 `|` 切（`:48, 59-85`），故家户 id 里**不得**含 `|`。

**证据**
- `CohortKey.toString/parse` 互逆、按第一/第二个 `|` 切三段（`CohortKey.java:72-92`）。
- `HouseholdActors.cohortOf` 对非 HOUSEHOLD、段数不对、首尾空段一律抛（`HouseholdActors.java:61-80`）；`idOf` 是 `of` 的拼写点（`:38-53`）。
- `CLASS_IDS` 是四个阶层字面量的**保序数组**（`EconomySeeder.java:296`），每个值都经 `new SocialClassId(...)` 校验（`:1513`）；它不是第二份格式，但是**第二份字面量清单**（如果词表增删，需同步；构造期会拒未知值）。

**未验证**
- 未跑 `CohortKey`/`HouseholdActors` 的既有往返测试；未做变异自证。
- 未穷举 `git` 全历史里的旧拼写；本文只对当前工作树负责。

---

### F.2 `ClassRow` ↔ `EconomyData.classes` 的跨表不变式（键 == row.key() 等）

**结论**
- 【代码事实】`EconomyData` 构造期**逐条**判：
  1. `classes` 的键必须 `.equals(entry.value().key())`（`EconomyData.java:183-188`）——**同一身份不许两处拼写**；
  2. 该键的格上必须至少有一个产业，且该产业 `slots` 里必须出现这个 `stratum`，否则抛；`participationPerMille` 还不得超过这些槽位里**最紧**的 `laborParticipationPerMille`（`:190-205` + `requireStratumAllowed` `:422-456`）；
  3. `flows` 的键必须等于 `FlowRow.key()`（`:245-248`），同样要过 `requireStratumAllowed`（`:249`）；
  4. 所有 `Debt.debtor()/creditor()` 必须出现在 `classes` 键集里（`:220-231`）；每条 `ClassRow.debts()` 引用的 `DebtId` 必须存在于 `debts`（`:232-239`）；
  5. `relations` 的键 == `ProductionRelation.activity()`，且该产业存在、`relation.operator() == industry.operator()`（`:356-378`）。
- 【代码事实】`EconomyData` **有意不判**的引用完整性（写进了类注，`:106-108`）：
  - 关系指名的 cohort 行是否存在 → 移到结算期 fail-closed 判（`EconomySettlement.requireCohortRows` `:3639-3671`，要求行在、且 `cohort.hex() == 产业所在格`，**不要求人口 > 0**）；
  - `flows` 键是否在 `classes` 里、`classes` 是否每行有 flow、每行是否都有 laborSupply/allocations、`Debt.id` 是否等于 `debtIdOf(cycle,debtor,creditor,commodity)` 的产物 —— 都没有构造期守卫。
- 【推断】因此"改一行"在类型上不是改一个字段，而是**删旧键 + 增新键**；任何只改 `ClassRow` 内字段而不动键的写，都不能表达"换阶层"。

**关键代码位置**
- `ClassRow` 形状：`simos-economy/src/main/java/io/mosire/simos/economy/model/ClassRow.java:61-70`（9 个组件；**没有 goods / meansOfProduction**，类注 `:13-29`）；构造期守卫 `:72-136`；参与率折算唯一算法 `:139-179`。
- `EconomyData` 十个组件：`simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:110-120`。
- 上述五组不变式：`EconomyData.java:178-207, 209-239, 240-252, 345-378, 422-456`。
- 键与变更集组件的对应：`EconomyChangeSet.java:55-70`（`FieldDelta<ClassRow> classes` 等 10 件）；回放重建用 `CohortKey::parse`（`EconomyChangeSet.java:130-140`）。
- `ClassSlot` 只持制度参数（参与率上限），人口/占比由行派生：`simos-economy/src/main/java/io/mosire/simos/economy/model/ClassSlot.java:3-30`。

**实际读写路径**
- 创世：`economy.Seed` 载荷 → `EconomyPayloads.toData`（`:240-300`）→ `new EconomyData(...)` 构造期守卫。
- 运行期：`EconomySettlement.settleOneDay` 返回新 `EconomyData`（`:1040-1054`）；月度 `EconomySettlement.applyPopulationChange` 返回新 `EconomyData`（`:1207-1217`）；两者都经 `EconomyChangeSet.between` 进 revision。
- 回放/落盘：`EconomyChangeSet.apply` 用 `FieldDelta.rebuild(..., CohortKey::parse)` 还原 classes/flows 的键。

**证据**
- 键相等守卫原文：`EconomyData.java:183-189`。
- 参与率上限守卫原文：`:190-205`；`requireStratumAllowed` 的"格 + 阶层槽位"判断：`:422-456`。
- "键 == 行内 key"不是 `ClassRow` 自己判的（它只判非 null），而是 `EconomyData` 判的（同上一处）。

**未验证**
- 未跑 `EconomyRoundTripTest` / `EconomyInvariantsTest`；未验证旧档缺键路径。
- 没有实跑"手工构造一个 `classes` 键不等的状态"来看报错文本；本文只读到守卫代码。

---

### F.3 social 侧 `PopulationGroup` 批次（有无"阶层"维？）与 `PopulationDynamics` → `PopulationEconomyTimeParticipant` 的映射

**结论**
- 【代码事实】`PopulationGroup` 的字段是 `(id: PeopleLotId, residence: HexCoord, sex, count, ageAtAnchorDays, anchorTick, physiologicalStress)` —— **没有阶层这一维**（`PopulationGroup.java:35-42`）。批次的命名只带城乡/性别/年龄细分：`rural:<q>_<r>:<SEX>:<CHORT>` / `urban:<cityId>:<SEX>:<CHORT>`（`PopulationLots.java:12-14, 51-71`）。
- 【代码事实】"阶层"是**经济侧创世时算出来的投影**：`EconomySeeder.cohortGroup` 把同一格同一居住类型的池人口按固定份额 `CLASS_SHARE_PER_MILLE = {450,350,150,50}` 切成四行（`EconomySeeder.java:283, 1498-1522`）；`LaborSupply` 的毛劳动按**批次**（年龄 × 性别）算，`ClassRow.laborMilli` 则按"行人口 × 池人均劳动"摊（`:1505-1507, 1557-1581`），参与率取固定表 `CLASS_LABOR_PER_MILLE = {950,900,750,100}`（`:318, 1571-1572`）。
- 【代码事实】运行期 social→economy 的映射只有两条：
  1. **逐日压力**：`applyDailyStress` 用 `group.residence()` + `ResidenceKind.ofLot(group.id())` 得到 `HouseholdRef(格, 居住类型)`，然后把该组**四行**的需求/实得求和后算一个满足率，写回**每个批次**的 `physiologicalStress`（`PopulationEconomyTimeParticipant.java:286-313, 318-337`）；这里没有任何"阶层"参与。
  2. **月度生死回写**：`PopulationDynamics.monthly` 产出`SocialData` 新批次表与 `LotChange`；协调器 `stepper.applyPopulationChange(outcome.changeList())`（`:234-252`）→ `EconomySettlement.applyPopulationChange`：把批次映射到"它供给的产业所在格 × 它自己的居住类型 × **四个阶层行**"（`householdKeysOfLot` `:2279-2289`），再按**行人口权重**把 births/deaths 摊到四行上（`:1174-1205`），并把该批次的 `LaborSupply`/全部 `LaborAllocation` 按**批次存活比例**缩（`:1200-1205, 1355-1401`）。
- 【代码事实】运行期**不重算** `laborMilli` 的人口口径，也**不重算**参与率：`withPopulationAndLabor` 只按存活比例缩 `laborMilli`（`:4359-4370`），出生**不加工劳动**（`:1194`），`participationPerMille` 只被透传（`:4321-4389` 的四个 with* 都保留它；类注 `ClassRow.java:153-154` 明说本阶段不运行期变化）。
- 【代码事实】真值源是 social 的 `PopulationGroup.count`；经济侧的 `ClassRow.population/laborMilli` 是投影。`PopulationDynamics` 的出生会**新建批次**（`PopulationLots.born` `:99-113`；新生批次 id 为 `...:<SEX>:b<月>`），但对经济侧只产生"按四行人口摊"的生死；新批次**没有**对应的 `LaborSupply`/配额创建入口。
- 【代码事实】**绝对人口水平没有对账路径**：协调器回写经济侧的输入只有 `LotChange`（本月的 births/deaths），`PopulationDynamics.monthly` 只在两者之一非 0 时产出 `changes`（`:238-249`），`applyPopulationChange` 只把这些 delta 加到行上（`:1184-1198`）；没有任何代码拿社会侧批次的 `count` 与 `ClassRow.population` 做对账。`social.SeedGroups` 改批次 level、`social.SetPopulation` 改旧 `populations` 序列，都不会自动改经济行。

**关键代码位置**
- `PopulationGroup` 形状与"迁移=换 residence，id 不变"的类注：`simos-social/src/main/java/io/mosire/simos/social/population/PopulationGroup.java:18-20, 35-42`。
- 批次命名：`.../population/PopulationLots.java:12-14, 51-71, 99-113, 145-156`；`ResidenceKind.ofLot`：`ResidenceKind.java:68-95`。
- 创世人口批次构造：`simos-app/src/main/java/io/mosire/simos/app/world/PopulationSeeder.java:91-135`（每格每性别 3 档 = 6 批；具体年龄取档中点 `:57-71`）。
- 经济投影：`EconomySeeder.java:1505-1521`（四行人口 = 池人口按 450/350/150/50 切）；`:1571-1572`（行劳动 = 行人口 × 池人均；参与率表）；`:318`（参与率表；`CLASS_WEIGHTED_LABOR_PER_MILLE` `:320-344`）。
- 月度生死与回写：`PopulationDynamics.java:205-255`（`monthly`）、`PopulationGroup.java:84-97`（`withCountAndStress`/`withPhysiologicalStress`）、`PopulationDynamics.java:174-190`（`stressAfter`）；`PopulationEconomyTimeParticipant.java:231-252`；`EconomyDayStepper.java:492-505`；`EconomySettlement.java:1134-1218, 2272-2289, 1355-1401`。
- 月回写后经济副本重新对齐：`PopulationEconomyTimeParticipant.java:238-251`。

**实际读写路径**
- 创世：`PopulationSeeder.payload`（批次）与 `EconomySeeder.plan`（同一份批次列表算 `ClassRow`/`LaborSupply`/`Allocation`）由 `WorldgenInitializeTool` 同批提交（`SET_POPULATION_TYPE`/`SEED_GROUPS_TYPE`/`SEED_ECONOMY_TYPE`/`SEED_ACTOR_TYPE`，`WorldgenInitializeTool.java:125-166`）。
- 推进：`PopulationEconomyTimeParticipant.simulateWorld`：载入家户账副本（`:164-187`）→ 逐日 `stepper.step(day)`（`:209`）→ `applyDailyStress`（`:232`）→ 每 30 天 `PopulationDynamics.monthly` + `stepper.applyPopulationChange`（`:234-252`）→ `finish()` 交变更集（`:254-262`）。
- 出生/死亡两条路径：月度社会结算（本文件 F.3）与饿死路径 `applyFamine`（`EconomySettlement.java:3804-3832`，缩行人口/行劳动）+ `scaleLaborOfIndustry`（`:956-961`）。

**证据**
- `PopulationGroup` 无 stratum 字段：record 组件清单即全部字段（`PopulationGroup.java:35-42`）。
- 映射函数不含阶层：`householdKeysOfLot` 遍历四个 `SocialClassId`（`EconomySettlement.java:2254-2270, 2279-2289`）；`applyPopulationChange` 以行人口为权重摊 births/deaths（`:1178-1198`）。
- 死亡缩配额的注释明说"不改劳动供给表/按存活比例"：`:1301-1345, 1348-1381, 2837-2838`。
- 基线流程报告已把 social↔economy 的关系写成同一个判断：`docs/.../2026-09-28-economy-system-flow-report.md:155`（"经济侧看不见 social 类型；'人'的再生产只在协调器里缝合"）。

**未验证**
- 未实跑"迁一批人到另一阶层行"的实验（代码里也没有入口），因此**未验证**月度摊派在改过 `ClassRow.population` 后逐值如何演化；这是【推断】层。
- 未跑 `PopulationDynamics` 的既有测试；未做变异自证。

---

### F.4 `LaborSupply` / `LaborAllocation` 的键（`PeopleLotId`）与家户行的关系；`reallocateLabor` 到底改了什么

**结论**
- 【代码事实】`LaborSupply` 的键是 `PeopleLotId`（批次），值记 `group, period, gross/served/committedLaborMilli`（`LaborSupply.java:36-41`；`EconomyData.java:116, 254-268`）。
- 【代码事实】`LaborAllocation` 的键是 `LaborAllocationId`，值记 `id, group(PeopleLotId), actor(ActorRef), activity, laborMilli, period`（`LaborAllocation.java:45-51`；`EconomyData.java:117, 269-344`）。`actor` 是**收劳动的产业主体**（真档 = 产业 id 推出来的 `ESTATE/HOUSEHOLD/WORKSHOP`，见 `EconomySeeder.appendAllocation` `:1158-1168` 与 `EconomyData.java:288-313` 的 actor↔产业守卫）；**没有 cohort/阶层字段**。
- 【代码事实】一条配额的总和 ≤ 该批次 `availableLabor()` 是构造期不变量（`EconomyData.java:331-344`）；每条配额必须有**同期**供给记录（`:315-326`）。
- 【代码事实】"家户行 ↔ 配额"不是数据库式外键，而是**反推**：
  - 产业 → 家户行：`settleOneDay` 取该产业的家户行 = 产业 id 的格 × 配额批次的居住前缀 × 四阶层（`householdKeysOf` `:2237-2251`；`householdKeysAt` `:2254-2270`）；
  - 批次 → 家户行：`householdKeysOfLot`（`:2279-2289`）。
  - 也就是说：**一个批次（同格同居住类型）的四行总是同时成为该批次所供给产业的"家户"**；行与批次之间没有多对多选择表。
- 【代码事实】当日生产的实际劳动 = `laborByActor(allocations)`（`:767, 3945-3964`），**不是** `ClassRow.laborMilli`。`ClassRow.laborMilli` 的读者只有：死亡时的同比例缩（`:1194`、`applyFamine` `:3829`）、`laborOfCohort` 经 `ClassRow.participationAdjustedLaborMilli()` 把它当**关系按劳动量分配**的权重/分母（`:3697-3719`；`ClassRow.java:139-179`），以及读口（`ApiViews.java:528, 1162-1169`）。`lendableOf`/`creditLinesOf` 读的是**人口**与已实现所得，不读 `laborMilli`（`:2652-2699`）。故"把某行的 `laborMilli` 挪走"**不会**把生产劳动挪走。
- 【代码事实】`reallocateLabor`（`:2840-2933`）只改 `EconomyData.allocations` 一张表（工作副本）；它用的 `rows` 形参在方法体内**从未被读**（实测 `sed -n '2840,2933p' | grep rows` 只有形参那一行）。

**`reallocateLabor` 逐键改动清单**（都在 `allocations` 内）
1. 触发范围：按 `industriesByHexMap` 逐格；只有该格有产业 `progressDays == 0` 才动（`:2844-2856`）。
2. 对每条落在本格产业上的配额：`keep = min(配额, 该产业剩余需求)`；`keep <= 0` ⇒ **删除**该 `LaborAllocationId` 条目；`keep < 原值` ⇒ **替换**为 `withLaborMilli(allocation, keep)`（id/group/actor/activity/period 全不变）；释放出来的量进 `pool[group]`（`:2870-2891`）。
3. 回池按缺口大的产业优先（并列按 `IndustryId.value()` 字典序；`:2895-2924`）；对目标产业**新增**配额 `LaborAllocation.idOf(industryId, group)`，`actor = industries.get(industryId).operator()`，`activity/period` 从同产业既有模板抄（抄不到就 `industryId.value()` / `1`）（`addLabor` `:2992-3016`；`templateAllocationOf` `:3025-3033`）。
4. 回池到最后仍未分出的量：**不生成任何配额**（"失业"只是"没有配额"；组、行、账都不动；`:2925-2931`）。

**`reallocateLabor` 明确没改的**
- `EconomyData.classes`：`population`、`laborMilli`、`participationPerMille`、`naturalNeeds`、`effectiveDemand`、`money`、`debts`、`cycleNaturalNeedMilli` 全不动（形参未使用；方法只收到 allocations 的 `LinkedHashMap`）。
- `EconomyData.laborSupply`：注释明文"不改劳动供给表"（`:2837-2838`），且 `Σ配额 ≤ available` 构造性成立（`:331-344`）。
- `EconomyData.flows` / `debts` / `relations` / `markets` / `shipments`：不动。
- `ActorData.actors` / `ActorData.accounts`：economy 模块看不见 actor 切片；本方法也不返回 actor 状态。
- `PeopleLotId`、批次 `residence`、性别、年龄、生理压力：不动。
- `LaborAllocation.activity`：既有条目原样保留；新条目抄模板，不改"这批人干什么"的词（而且 `activity` 本身没有消费者，`LaborAllocation.java:39-41` 明说本轮无人读）。

**"改劳动配额"到底改变了什么（生产关系层面）**
- 【代码事实·直接】它把同一条批次（同一个 `PeopleLotId`）的**劳动配额**在同一格的不同产业之间重新分配；"这一格的劳动被哪个产业占了多少"的判据就是 `actor.id()`（`:3945-3964`）。
- 【推断】因为 `householdKeysOf` 是从配额表推"哪些行属于这个产业"，一次把某批次的配额从 `farm` 挪到 `craft`，会让**该批次的四行**在结算里成为 `craft` 的"家户"（投入代理、给养、按劳动分配的分母都会跟着变）；这可以读成"这批人换了雇主"，但它**不是**"家户换了阶层/身份"：行键、actor、账户、债务、人口都没换。
- 【代码事实】它不改 `ProductionRelation` 记录：`relations` 表是原样带过的（`:1048` 与 `:1215` 都传 `base.relations()`；全仓唯一写 relations 的地方是载荷/变更集重建，见 F.6）。

**关键代码位置**
- `LaborSupply` / `LaborAllocation` 定义与 `idOf`：`.../api/labor/LaborSupply.java:36-41, 71-78`；`.../api/labor/LaborAllocation.java:45-51, 74-96`。
- 构造期不变式：`EconomyData.java:254-344`。
- 家户行反推：`EconomySettlement.java:2237-2289, 2300-2323`。
- 生产劳动来源：`EconomySettlement.java:763-808, 3945-3964`。
- 重排实现：`EconomySettlement.java:2808-3044`（方法、addLabor、template、withLaborMilli）。
- 饿死缩配额/供给：`:956-961, 1300-1401`。

**实际读写路径**
- 创世：`EconomySeeder.appendSupply` `:1069-1084` + `appendAllocation` `:1117-1170` → `economy.Seed` 载荷 → `EconomyPayloads.laborSupply/allocation`（`:554-580`）→ `EconomyData` 构造期守卫。
- 推进：`settleOneDay` 每次调用 `reallocateLabor`（`:735`/`:758`）；饿死时 `scaleLaborOfIndustry`（`:956-961`）。
- 回放：`EconomyChangeSet.apply` 用 `PeopleLotId::parse` / `LaborAllocationId::parse` 重建两张表。
- 读口：`ApiViews` 从 `allocations`/`laborSupply` 派生（非本次重点）。

**证据**
- `grep` 实测 `rows` 在 `reallocateLabor` 体内 0 次使用：`sed -n '2840,2933p' ... | grep -n rows` 仅命中形参行。
- 方法注释明写"本步只把配额在产业之间搬、或把它删掉，**从不凭空增加**任何批次的配额总和"（`:2837-2838`）。
- `LaborAllocation.activity` 自述"本轮没有消费方读它"（`LaborAllocation.java:39-41`）。

**未验证**
- 未跑 `EconomyCycleBoundaryTest` / `EconomyRoundTripTest`；未实跑一次"迁移后重排"（也没有迁移入口）。
- 未证明存在"通过 `reallocateLabor` 让某个 `ClassRow` 的 `laborMilli` 变化"的路径——从静态代码看不存在，但未做运行时探针。

---

### F.5 `ActorRef`（家户主体 id 怎么拼）、`GoodsAccountKey`、`ActorData.accounts` 与家户行的绑定

**结论**
- 【代码事实】家户 actor = `ActorRef(ActorKind.HOUSEHOLD, "<q>_<r>:<residence>:<stratum>")`，唯一拼写点 `HouseholdActors.of/idOf`，反解 `cohortOf`（`HouseholdActors.java:38-80`）。
- 【代码事实】账本键 = `(actor, location)`：`GoodsAccountKey` 规范串 `<owner>|<location>`，按第一个 `|` 切（`GoodsAccountKey.java:42-85`）。家户账的键固定为 `(HouseholdActors.of(cohort), cohort.hex())`，由 `OwnershipBooks.accountKeyOf` 唯一拼写（`OwnershipBooks.java:402-408`）。
- 【代码事实】`ActorData` 三个组件 `meta / actors / accounts`；`accounts` 的键必须等于 `GoodsAccount.key()`（`ActorData.java:48-51, 81-95`），但**表与表之间没有引用完整性约束**：账户 owner 可以不在 `actors` 里（类注 `:43-46`）。
- 【代码事实】持久真源：家户的**商品与货币**只在 `actor` 切片的 `GoodsAccount` 里；`ClassRow` **没有 goods 字段**（`ClassRow.java:13-29`），`ClassRow.money` 是历史字段、创世写 0、结算只透传（证据见 B6：`EconomySeeder.java:1576`；`EconomySettlement.java:4335, 4351, 4365, 4384`；`ApiViews.java:1178`）。日结算用两份**会话工作副本**（`Map<CohortKey, Map<CommodityId,Long>>` / `Map<CohortKey, Map<CurrencyId,Long>>`），推进结束后按绝对值落回账户（`OwnershipBooks.loadHouseholdGoods/Money` `:215-343`；`landHouseholdGoods/Money` `:264-399`）。
- 【代码事实】`loadHouseholdGoods/Money` 的**驱动集是 `economy.classes().keySet()`**，一行没账户就抛（`:221-237, 325-341`）；而 `requireHouseholdAccounts`/`requireHouseholdMoney` 在结算里只强制"人口 > 0"的行有账（`EconomySettlement.java:4283-4304, 4240-4266`）。⇒ 创世给"每格两组四行"（含人口 0 的空账）都建了 actor+账（`HouseholdSeeder.java:186-223`；`EconomySeeder.cohortGroup` 四行恒建 `:1508-1521`），所以**目标阶层行在真档里通常已经有 actor 和空账**。

**关键代码位置**
- `ActorRef`：`simos-actor-api/src/main/java/io/mosire/simos/actor/api/actor/ActorRef.java:23-42, 45-70`；`ActorKind` 词表：`.../ActorKind.java:31-47`。
- `HouseholdActors`：`.../api/cohort/HouseholdActors.java:38-80`。
- `GoodsAccountKey`：`simos-actor/src/main/java/io/mosire/simos/actor/model/GoodsAccountKey.java:42-85`。
- `ActorData`：`simos-actor/src/main/java/io/mosire/simos/actor/ActorData.java:48-51, 81-95, 108-148`。
- `OwnershipBooks`：`:117-147`（fold）、`:149-189`（apply）、`:191-239`（load goods）、`:264-297`（land goods）、`:299-343`（load money）、`:360-399`（land money）、`:402-408`（accountKeyOf）、`:410-464+`（经营者账同形）。
- `HouseholdSeeder`：`:128-159`（载荷）、`:186-223`（`actors` + `accounts` 逐条对齐）。
- 家户行 ↔ actor 的读点：`PopulationEconomyTimeParticipant.java:143-146`（读写地址）；`MarketSettlement.participantsFor` `:1663-1667`；`EconomySettlement.householdActorsOf` `:3616-3622`；`ProductionSettlement.recipientOf` `:511-517`。

**实际读写路径**
- 创世：`WorldgenInitializeTool` 同批提交 `economy.Seed` + `actor.Seed`；`HouseholdSeeder.payload/books` 用 `HouseholdActors.of` 建 actor，用 `new GoodsAccountKey(actor, hex)` 建账（`:132-141, 195-211`）。
- 推进：协调器 `loadHouseholdGoods/Money(economy, actor)`（`:164-187`）→ `EconomyDayStepper` 就地更新副本 → `OwnershipBooks.landHouseholdGoods/Money` 按副本绝对值整本覆盖账户（`:222-230`；月末再对齐 `:238-251`）。
- 条目落账：`OwnershipBooks.fold(ledger)` → `apply`（一腿一条 `ActorEntry`，整本覆盖时把 money/frozen 透传，`:149-189`）。
- 回放/持久化：`ActorChangeSet` 的 `accounts` 是 `FieldDelta<GoodsAccount>`，键用 `GoodsAccountKey::parse` 重建。

**证据**
- `HouseholdActors.of` 返回 HOUSEHOLD 种类；`cohortOf` 对 `HOUSEHOLD:weave@0_0`（家庭纺织经营者，id 里没有 `:`）会抛——所以 `HOUSEHOLD` 种类里**不只有家户**（`OwnershipBooks.java:200-202` 明写）。
- `loadHouseholdGoods` 缺席即抛（`:229-237`）；`ActorSeedHandler` 对已有库存格的格**整份拒绝**（`ActorSeedHandler.java:78-94`）⇒ 不能在已有格用 `actor.Seed` 补一个 actor/账。

**未验证**
- 未跑账户载入/落回/角色绑定的既有测试；未实跑"给目标行造一个新 actor/账"的路径（也没有入口）。
- 未验证 `GoodsAccountKey` 对含 `|` 的 actor id 的运行时行为（代码语义上 fail-closed）。

---

### F.6 债务、流水、生产关系、市场参与者各自怎样引用阶层

**结论**
- **债务**（见 `Debt.java:39-47`）：`debtor` 与 `creditor` **两端都是 `CohortKey`**（格 + 居住类型 + 阶层）。`EconomyData` 要求两端都在 `classes` 键集里（`EconomyData.java:220-231`）；`ClassRow.debts()` 只记**债务人方向**的 `DebtId`（`:232-239`，`DebtIndex.java:9-33` 说债权人方向是纯派生索引）。债务 id 由 `(周期, 债务人, 债权人, 商品)` 确定性拼成（`EconomySettlement.debtIdOf` `:3066-3077`），所以两端键本身也进了 id。运行期只有三种写：新借/聚合（`lendDeficits` `:2507-2627`）、偿还（`repayDebts` `:2752-2806`）、计息并入本金（`chargeInterest` `:3864-3882`）；**没有任何一条路径改 debtor/creditor，也没有把一条本金按人口拆分**。
- **流水**（见 `FlowRow.java:76-87`）：键 = `CohortKey`；`EconomyData` 要求 `flows` 键 == `FlowRow.key()`（`EconomyData.java:245-248`）。`settleOneDay` 对 `classes` 的每一行重建/累加一条 FlowRow（`:972-1026`）；月度回写用 `withLifecycle` 加 births/deaths（`:1196-1198, 1281-1298`）；新周期第一天整行从 0 重记（`:1006-1011, 1014-1025`）。没有独立的 flow 命令。
- **生产关系**（见 `ProductionRelation.java:86-120`）：`CompensationRule.recipient` 是 sealed `Recipient`，其中 `ToCohort(CohortKey)` 直接按阶层指名受方（`Recipient.java:20-70`；`CompensationRule.java:60-69`）。四档默认由 `RegimeRelations.laborCohorts` 对 `SocialClassId.all()` 各生成一条（`:493-508`）；地租/租佃租金写死 `SocialClassId.LANDLORD`（`:399-408, 477-490`），绑定格与居住类型在 `toRule`（`:510-523`）。`ProductionSettlement.recipientOf` 把 `ToCohort` 转成 `HouseholdActors.of(cohort)`（`:511-517`）。运行期 `settleOneDay` 把 `base.relations()` **原样带过**（`:1048`），`applyPopulationChange` 也原样带过（`:1215`）；全仓唯一写 relations 的路径是 `economy.Seed` 载荷和 `EconomyChangeSet.apply` 回放。
- **市场参与者**（`MarketSettlement.participantsFor` `:1655-1680`）：每格取 `EconomyData.classes` 在该格的**全部行键**（`rowsByHex`，`EconomySettlement.java:3972-3989`），每行生成 `Participant(actor=HouseholdActors.of(key), household=key, industries=若该 actor 恰好也是本格某产业的 operator 才有值——真档家户行通常为空)`；经营主体 `actor=industry.operator()` 另算（`:1657-1678`）。所以市场参与者**按阶层行**逐个出现：行有 account 才能读库存/预算（`stockOf` 等读会话副本），`householdLifeReserveOf(row)` 读 `row.population`（`:1687-1738`）。市场没有"参与者表"需要注册，随行集自动变化。
- **在途批次**（额外关联，容易漏）：`ShipmentAllocation.buyer/seller` 是 `ActorRef`（`ShipmentAllocation.java:20-27`）；到货时 `deliverShipments` 用 `householdOfActor`（由 `classes` 键集建）反查买方，找不到且经营者账也没有 ⇒ **当场抛**（`EconomySettlement.java:1094-1102`）。所以家户 actor id 若变，在途票仍指向旧 actor；没有改绑入口。

**关键代码位置 / 证据**
- 债务：`Debt.java:39-47`；`EconomyData.java:220-239`；`EconomySettlement.java:2507-2627, 2752-2806, 3066-3077, 3864-3882`；`withExtraDebt` `:4342-4356`；`withPrincipal` `:3079-3089`；`DebtIndex.java:9-33`。
- 流水：`FlowRow.java:76-87`；`EconomyData.java:240-252`；`EconomySettlement.java:972-1026, 1281-1298`。
- 关系：`ProductionRelation.java:86-120`；`Recipient.java:20-70`；`CompensationRule.java:50-69`；`RegimeRelations.java:384-438, 441-523`；`ProductionSettlement.java:332-383, 511-517`；`EconomySettlement.java:1048, 1215, 3188-3231`；`requireCohortRows` `:3639-3671`。
- 市场：`MarketSettlement.java:277-283, 1655-1680, 1683-1790`；`EconomySettlement.rowsByHex` `:3972-3989`。
- 在途：`ShipmentAllocation.java:20-27`；`EconomySettlement.java:1060-1106`。

**实际读写路径**
- 债务：`settleOneDay` 借粮（`:2507` 区间）→ `Debt` + `ClassRow.debts`；偿还（`:2752`）只改本金；计息（`:3864`）只改本金；revision 经 `EconomyChangeSet.debts`。
- 流水：`settleOneDay` 末尾逐行生成（`:972-1026`）；月回写 `withLifecycle`（`:1281-1298`）。
- 关系：`economy.Seed` 载荷 → `EconomyPayloads.compensationRule/recipient`（`:434-479`）→ `EconomyData` 构造期；运行期只读。
- 市场：每次开市现算参与者和订单（`MarketSettlement.clearOncePerCycle` `:435` 邻域），不落状态。

**未验证**
- 未跑债务/关系/市场的既有测试；未实跑"迁移后旧行继续收租/收还款"的场景（也无迁移入口）。
- `EconomyData` 没有 `Debt.id == debtIdOf(...)` 的构造期守卫，本文只读到"唯一产生点是 `lendDeficits`"与类注口径；未实跑手工构造不一致 id 的状态。

---

## 二、必须直接回答的两个问题

### 2.1 能否把**原阶层的部分人口**迁到一个**新阶层行**，并同时迁移其**劳动、需求、账户权益、债务**？

**短答（代码事实）**：**不能。** 今天没有任何一条**命令 / 协调器 / 结算**路径能完成"部分人口的阶层迁移"的原子写；状态形状（`EconomyChangeSet.classes/flows/debts/laborSupply/allocations` + `ActorChangeSet.actors/accounts`）在**类型上**可以表示其中若干片，但没有一个已注册的命令处理器/协调器会产出这一组配套 diff。

#### 逐表清单：迁移需要变什么 / 现有写入口 / 阻断点

| 面 | 要变的东西（键/字段） | 现有写入口（**只有这些**） | 今天为什么走不通 |
|---|---|---|---|
| social 人口真值 | `PopulationGroup.count`, 以及"哪些人属于哪一行" | `social.SeedGroups`（`SeedGroupsHandler.java:66-84`，同 id 覆盖）；月结算 `PopulationDynamics.monthly`（协调器内） | `PopulationGroup` **没有阶层维**；批次是按格+性别+年龄分的（`PopulationSeeder.java:91-135`）；无论加/改哪个批次，经济侧都按"格+居住前缀 → 四行"整组映射（`EconomySettlement.java:2279-2289`），不能指名"这批人属于新阶层行" |
| `EconomyData.classes` | 源行 `population/laborMilli` 减、目标行加；`naturalNeeds/cycleNaturalNeedMilli` 要拆；`participationPerMille` 是目标行固定值；`debts` 引用要搬 | `economy.Seed`（只对新格；`EconomySeedHandler.java:71-101` 对已有产业/阶层的格**整份拒绝**）；结算 `withDailyNeed`/`applyFamine`/`applyPopulationChange`；`EconomyChangeSet.apply` 回放 | 键 == 行内 key 的守卫要求删旧键+加新键；`economy.Seed` 不能在已有格改一行；运行期写者只会改人口/劳动/需求，不会改键、不会改参与率、不会把行"搬"到另一个 stratum |
| `EconomyData.flows` | 源/目标行的本周期 `income/consumed/.../deaths/births` 要拆并 | `settleOneDay`（`:972-1026`）；`withLifecycle`（`:1281-1298`）；回放 | 没有 flow 命令；这些是"本周期累计"（`FlowRow.java:64-74`），月中迁户要拆累计器，无入口 |
| `EconomyData.laborSupply` | 如果要把迁走的人单独计数：需要一个新批次（新 `PeopleLotId`）+ `grossLaborMilli` 按人拆 | `economy.Seed`（`appendSupply` `:1069-1084`）；饿死 `scaleSupply`（`:1384-1401`） | 供给按**批次**；一个批次横跨四行；**没有按阶层的供给**；运行期没有发新供给的命令；新批次也只会被映射回四行 |
| `EconomyData.allocations` | 若认为"劳动"是配额：只能改"批次 ↔ 产业"，改不了"行 ↔ 产业" | `economy.Seed`（`:1117-1170`）；`reallocateLabor`（`:2840-2933`）；饿死缩放（`:1320-1381`） | 配额键里**没有 cohort/阶层**；`reallocateLabor` 只在同格产业间搬，且只改 allocations 一张表（见 2.2） |
| Actor 主体/账 | 源家户 actor/账 → 目标家户 actor/账：`actors`、`accounts.balances/money/frozen*` | `actor.Seed`（只对新格；`ActorSeedHandler.java:78-94` 对已有库存格的格**整份拒绝**）；推进时 `OwnershipBooks.apply/land*`（`:149-189, 264-399`） | 换阶层 = 换 actor id = 换 `(owner, location)` 账键；没有"转账/并账/换主"命令；`land*` 只会按 `economy.classes` 的键把各自账户原样写回；**不动账户 = 权益不迁**（旧行继续持有，即使人口 0） |
| `EconomyData.debts` | `debtor/creditor` 换成新键；`DebtId` 按新键重拼；本金按"部分人口"拆分；两边 `ClassRow.debts` 引用同步 | 只有 `lendDeficits`（新借/聚合）、`repayDebts`（减本金）、`chargeInterest`（加本金） | 没有改 debtor/creditor 或拆本金的写入口；`DebtId` 确定性含两端键（`:3066-3077`）；部分迁移没有"按人拆本金"的口径；旧债权人的应收、旧债务人的应付都留在旧行/旧 actor |
| `EconomyData.relations` | 若目标阶层是**新阶层名**：需要新增规则；若是四档之一：规则已存在，但"权益"不会随行迁走 | `economy.Seed`（载荷）；`EconomyChangeSet.apply` 回放。运行期**没有写者**（`:1048, 1215` 原样带过） | `Recipient.ToCohort` 是创世时写死的四档 × 居住类型（`RegimeRelations.java:493-523`；地租写死 `LANDLORD` `:399-408`）；`requireCohortRows` 要求指名的行**必须存在**（`:3659-3670`），但不要求人口 > 0 ⇒ 租/工资可以继续付给旧行；没有"把规则受方改到新行"的写入口 |
| `EconomyData.shipments` | 在途票的 `buyer/seller` 若指向旧 actor，要改绑 | 市场发运建账、到货销账（`EconomySettlement.java:1060-1106`；`MarketSettlement` 发运） | 在途记录里存的是 `ActorRef`；到货时按 `householdOfActor` 反查，查不到就抛（`:1094-1102`）；没有改绑入口 |
| `EconomyData.markets` | 不需要改 | 派生参与者 | 参与者按 `classes` 行集现算（`MarketSettlement.java:1655-1680`），行在就有参与者；但预算/库存来自该行自己的账户，权益没迁就只是"多了一个空钱包的参与者" |

> 逐表结论：**人口数字**可以在 `classes` 上拆，**日需求**下一天会被 `withDailyNeed` 按新人口重算；但**劳动**（批次级）、**账户**（actor 键变了）、**债务**（键含两端）、**关系/在途**（写死的 cohort/ActorRef）都不会跟着走，且没有任何一条已有写入口能把它们配套改掉。

#### 阻断点逐条（为什么今天走不通）

- **B1 没有"改一行"的命令，只有"同一格整份拒绝的 seed"与"结算的内部重建"**：Shell 注册的写命令里只有 `social.SetPopulation`、`social.SeedGroups`、`economy.Seed`、`actor.Seed` 触及本问题（`Shell.java:402-458`，社会/经济/actor 三条在 `:432-443`）。`EconomySeedHandler` 已激活时按**格**判重：任一目标格已有产业/阶层行 ⇒ 整份 `Rejected`（`EconomySeedHandler.java:74-81, 104-113`）；`ActorSeedHandler` 同款按**库存格**判重（`ActorSeedHandler.java:78-94, 104-110`）。⇒ 不能往已有格**追加或修改**一行/一个 actor 账。
- **B2 阶层是主键的一部分，不是可变属性**：`ClassRow.key` 就是 `CohortKey`；`EconomyData` 要求键等于行内 key（`EconomyData.java:183-188`）。换阶层 = 换键 = 换家户 actor = 换账本键；三段里没有任何"rename/move"操作。
- **B3 social 侧没有阶层维，且批次→行是"整组比例映射"**：`PopulationGroup` 字段清单没有 stratum（`PopulationGroup.java:35-42`）；`householdKeysOfLot` 对任何批次都摊到四个 `SocialClassId`（`EconomySettlement.java:2254-2270, 2279-2289`）；`applyPopulationChange` 按**行人口权重**摊 births/deaths（`:1178-1198`）。⇒ "这些特定的人改属新行"在人口真值源里**无法表达**；只能经济侧改数字，而社会侧的生死仍会按四行比例分摊。
- **B4 阶层词表封闭、行参与率/规则受方写死**：`SocialClassId` 只有四个值（`SocialClassId.java:31-57`）；创世把四个槽位与参与率写进每个产业（`EconomySeeder.java:1401-1407`），`requireStratumAllowed` 要求 stratum 在产业 slots 里（`EconomyData.java:422-456`）；`RegimeRelations.laborCohorts` 只对 `SocialClassId.all()` 建规则（`:493-508`）。⇒ 新阶层名不存在；迁到四档之一则目标行的参与率/规则是**固定表**，没有"按人保持原参与率"的维度。
- **B5 劳动是批次级，不是行级；`ClassRow.laborMilli` 不是生产劳动**：`LaborSupply/LaborAllocation` 的键是 `PeopleLotId`，分配 actor 是产业；实际生产劳动 `laborByActor(allocations)`（`EconomySettlement.java:763-808`）。`ClassRow.laborMilli` 只影响关系按劳动量的分配与若干读数。⇒ "迁移劳动"没有可搬的、按行归属的实体；把某行 `laborMilli` 挪走不会把生产劳动挪走。
- **B6 账户权益没有"转账"写入口，且行侧 money 是死字段**：真源 `ActorData.accounts` 的键 = `(HouseholdActors.of(key), key.hex())`；运行期写者只有"按结算条目/会话副本绝对值整本覆盖"（`OwnershipBooks.java:149-189, 264-399`），没有 from→to 的余额迁移命令；`ClassRow.money` 创世写 0（`EconomySeeder.java:1576`），之后四个 with*（`EconomySettlement.java:4335, 4351, 4365, 4384`）只透传，读口只有 `ApiViews.java:1178` 的显示一栏，不能当权益载体。
- **B7 债务键含两端、本金是标量、没有拆分/改绑入口**：`Debt.debtor/creditor: CohortKey`；`DebtId` 由四元组确定性拼（`EconomySettlement.java:3066-3077`）；运行期只有借/还/息三种本金写（`:2507-2627, 2752-2806, 3864-3882`）；`ClassRow.debts` 只在借入时追加（`:2612`），偿还后条目不删（`:2744`）。⇒ "迁一部分债务"没有口径与入口；即使整户迁，旧债务人/债权人的债也会继续指向旧键。
- **B8 流水是"本周期累计"、月中拆并需要累计器拆分**：`FlowRow` 各族在新周期第一天整行重记（`:1006-1011`）；没有 flow 命令。月中的部分迁移会留下旧行继续累计、目标行从 0 开始，读完整个周期就不再守恒。
- **B9 关系与在途把"旧身份"写进了状态**：`ToCohort` 的 cohort 键写死在关系里（`RegimeRelations.java:510-523`），关系运行期只读；`requireCohortRows` 只要求行存在，不要求有/无人口（`EconomySettlement.java:3632-3634`）；在途 `buyer/seller` 是 ActorRef（`ShipmentAllocation.java:20-27`），到货按 `householdOfActor` 反查（`:1094-1102`）。⇒ 迁户后旧行仍可能继续收租/收还款，或新 actor 收不到在途粮；删掉旧行还会触发关系/债务/在途的 fail-closed。
- **B10 "铁律 2" 的结构面**：修改只能 Command → ChangeSet → Revision。`EconomyChangeSet` 有 `FieldDelta<ClassRow> classes`、`FieldDelta<Debt> debts`、`FieldDelta<LaborSupply>`、`FieldDelta<LaborAllocation>`、`FieldDelta<ProductionRelation> relations`（`EconomyChangeSet.java:55-70`），`ActorChangeSet` 有 `FieldDelta<Actor>` / `FieldDelta<GoodsAccount>`（`ActorChangeSet.java:38-40`）；⇒ 跨切片 diff 的**形状**存在，但**没有任何已注册命令/参与者产出"阶层迁移"这一组 diff**（`EconomyChangeSet.between` 的调用点只有 seed handler 与两个 time participant，实测见文末"证据检索"）。
- **B11 就算手写一条 revision，也会被后续推进冲击**：【推断】`loadHouseholdGoods/Money` 以 `economy.classes()` 键集为驱动；新行若没有账会当场抛（`OwnershipBooks.java:221-237`），旧行若删而 shipments/debts/relations 还引用旧键会分别在到货/构造期/结算期抛（`EconomyData.java:220-231, 232-239`；`EconomySettlement.java:1094-1102, 3639-3671`）；旧行若保留，租/还款/在途仍落在旧 actor，且 `lendDeficits` 的债权人排序按**阶层 id**（`LENDER_SLOT_PRIORITY` `:384-385, 2541-2548`），新行/旧行的债权资格也随阶层键走。综合起来，现有代码没有任何一个"一致终态"能被某个单一写入口产出。
- **B12 目标行存在不是阻断点，但也不够**：真档创世对"每格 × 两组 × 四行"都建了行与空账（包括人口 0 的行；`EconomySeeder.java:869-881, 1508-1521`；`HouseholdSeeder.java:186-223`），所以迁到**同格同居住类型的另一档**时目标键通常已存在、账户也有；缺的仍是"把人口/劳动/权益/债务配套搬过去"的写入口与口径。若目标是**新阶层名**，见 B4；若目标是**换格/换居住类型**，还要额外处理账本 location（`GoodsAccountKey`）、在途 `deliverTo`、产业格映射与社会的 `populations` 跨组件校验（`SocialData.java:90-98`）。
- **B13 social 侧批次的"水平"改动不会同步到经济行**：`social.SeedGroups` 可以新增/覆盖批次（`SeedGroupsHandler.java:66-84`），`social.SetPopulation` 可以改旧 `populations` 序列（`SetPopulationHandler.java:65-69`）；但协调器回写经济行的输入只有 `LotChange` 的 births/deaths（`PopulationDynamics.java:238-249`；`PopulationEconomyTimeParticipant.java:235-238`；`EconomySettlement.java:1184-1198`），**没有任何地方把批次 `count` 的绝对值对账回 `ClassRow.population`**；而 `PopulationEconomyTimeParticipant` 只读 `social.groups()`（`:132-135`），`populations` 旧序列不在映射里。⇒ 想用 social 命令改"某阶层人口"，经济侧不会跟着变；反过来，经济侧改 `classes` 的数字也不会写回 social。

#### 需要同时变的状态（一张清单）

> 下面只是把"必须同时一致"的面列出来，说明为什么任何**单个**现有写入口都不够；**不是修复建议，也不承诺任何实现路径**。

若把上面各条按"一次一致的迁移"合并，至少涉及：

1. social：`PopulationGroup`（若换格/换批）——今天没有按阶层拆批的表示（B3）；
2. economy：`classes`（源/目标两行）、`flows`（两行本周期累计）、`debts`（两端引用 + `DebtId` + 两行 `debts` 列表）、`laborSupply`（若换批）、`allocations`（若换批/换产业）、`relations`（若新阶层名或要改受方）、`shipments`（若改 actor）；
3. actor：`actors`（若新增家户 actor）、`accounts`（商品、货币、冻结额跨账户迁移）；
4. revision：上述跨切片 diff 必须在同一条 revision 里落地（否则中间态会触发各构造期/结算期守卫）。

现有代码里没有任何一个函数/处理器同时写这三片；`PopulationEconomyTimeParticipant` 是唯一同时持有 economy+social+actor 的地方，但它的日循环只做：结算→压力→月度生死回写→账本绝对值落回（`PopulationEconomyTimeParticipant.java:203-253`），没有任何"阶层/身份迁移"分支。

---

### 2.2 当前代码是否把"改劳动配额"（`LaborAllocation`）误当作"家户转业"？

**短答**：**没有把它当"家户转业"**——代码里根本不存在"家户职业/阶层迁移"这一维；`reallocateLabor` 只改 `EconomyData.allocations`，改的是**同一批人（同一个 `PeopleLotId`）在同一格内把多少劳动给哪个产业**，不改任何家户行、人口、账户、债务或关系记录。它会产生一个**近似"换雇主"的生产关系副作用**（因为"哪些行供给这个产业"是从配额表反推的），但这不是身份/阶层迁移。

#### 它改了哪些键（逐条）

- 改的**唯一表**：`EconomyData.allocations`（工作副本 `LinkedHashMap`）。
- 删除：`keep <= 0` 的既有 `LaborAllocationId`（`:2883-2884`）。
- 缩小：`keep > 0` 且 `released > 0` 的既有条目，只换 `laborMilli`（`withLaborMilli` 原样带过 id/group/actor/activity/period；`:2885-2887, 3035-3044`）。
- 新增：对回池的劳动，按目标产业 + 原批次发新条目；键 = `LaborAllocation.idOf(industryId, group)`；`actor` = 目标产业的 operator；`activity/period` 抄该产业既有模板，缺则 `industryId.value()` / `1`（`:2912-2931, 2992-3016, 3025-3033`）。
- 未分完的部分：不建条目（`:2925-2931`）。

#### 它没改什么（逐条）

- `EconomyData.classes`：方法有 `rows` 形参但方法体**一次都没读**（实测 grep；且方法只接收 `allocations` 这一个可变整数表）。⇒ 人口、`laborMilli`、`participationPerMille`、`naturalNeeds`、`effectiveDemand`、`money`、`debts`、`cycleNaturalNeedMilli` 全不变。
- `EconomyData.laborSupply`：注释明文不改（`:2837-2838`）；`Σ配额 ≤ available` 不变（`:331-344`）。
- `EconomyData.flows/debts/relations/markets/shipments`：不动。
- `ActorData` / `Account`：economy 模块不可达；不返回、不写入。
- `PeopleLotId` / 批次身份：不动（新条目沿用同一 `group`）。
- `ProductionRelation` 记录：不动；`settleOneDay` 与 `applyPopulationChange` 都原样带过 `base.relations()`（`:1048, 1215`）。

#### "会 / 不会"的逐条理由

**不会把它当作家户转业的理由：**
1. 它只动 allocations 的键/值；家户身份的键 `CohortKey` 没有任何变化（B2）。
2. 它连 `rows` 形参都不读——代码结构上不可能改家户行。
3. 它只改 `laborMilli`（配额量）与目标 `actor`（产业），不改 `group`（人还是同一批），也不改 `activity` 的语义（且 `activity` 本轮无消费者：`LaborAllocation.java:39-41`）。
4. 实际生产劳动按 `actor.id()` 归集（`:3945-3964`），故它改变的是"这一格哪个产业拿到这批劳动"，不是"这批人属于哪个家户/阶层"。
5. `participationPerMille` 是行级固定表，不由它写；`ClassRow.laborMilli` 也不由它写。
6. 它只在**周期第一天**（该格某产业 `progressDays == 0`）触发（`:2831-2832, 2844-2856`），不是日常身份变更。

**"看起来像换雇主"的理由（以及为什么仍不是转业）：**
1. `householdKeysOf`（`:2237-2251`）从配额表的**批次居住前缀 + 产业格 + 四阶层**反推"这个产业的家户行"；因此把批次的配额从 `farm` 挪到 `craft` 后，同一批四行会被结算当作 `craft` 的劳动者/投入方/分配分母。
2. 这是**批次级的生产关系重连**，不是户级身份迁移：行键、actor、账户、债务、人口、消费都没有变；一个农村家户本来就可以同时供给 farm 与 weave（创世就是两条配额），再供给 craft 只是多一条链路。
3. 该副作用也不持久写入 `relations`：规则表原样带过；下次配置/结算仍按同一份规则算，只是配额的 actor 指向变了。
4. 它不能跨格/跨居住类型：`hexToIndustries` 只按产业格分组，`need.containsKey` 只认本格产业（`:2844-2879`）。所以它连"农业人口迁到城市"都表达不了，更不用说阶层迁移。

**结论定性**：
- 【代码事实】它不是"家户转业"，因为它不触碰家户身份/阶层/主体/账；它是**同格产业间的劳动配额再分配**。
- 【推断】若把"转业"理解为"这批人不再给产业 A 干、改给产业 B 干"，它确实产生了这个效果，但只在这个弱意义上、且只在同格；代码里没有把这种效果落成"职业"或"阶层"状态。

---

## 三、代码事实 / 推断 / 文档事实 / 用户目标 的分层

- **代码事实**：F.1–F.6 的每个 `文件:行`、`reallocateLabor` 的改动清单、`EconomyData` 的守卫清单、写入口清单（`Shell.java:402-458` 等）。
- **推断（未实跑）**：
  - 2.1 B11 的"手写一条 revision 会被后续推进冲击"；
  - 2.2 的"批次配额换产业 ⇒ 四行被当作该产业的家户/分配分母"的具体数值后果；
  - "迁户后旧行继续收租/还款、新行拿不到在途粮"的具体行为（代码路径已核，未见运行样本）；
  - 空账目标行在迁移人口后会因无钱/无粮而在下一天产生缺口、并通过同格借贷/市场另行决定的最终路径。
- **文档事实**（不是代码事实，仅作交叉印证）：
  - 流程报告 `:188-191` 已把"劳动来源"缺口写成缺 `LaborSource`；
  - 流程报告 `:208` 写"地租仍是按名字（阶层）收租"，`:209` 写 `ProductionRelation` 缺 `occupations/useRights/laborSource`（架构提案，未实现）；
  - 调查 1–8 报告 `:240` 明写"唯一 economy 写命令是 `economy.Seed`；`progressDays`/`cycleInputUsedMilli`/`capacityScaleOf`/`reallocateLabor` 都没有对外写入口 ⇒ 代码里没有这些命令"；
  - `docs/superpowers/specs/2026-09-26-population-economy-v3-design.md:37` 写"**没有任何'人口跨阶层/跨产业转移'的 API**（全仓 `migrat|阶层流动|ClassFlow|transferPopulation` 的实质命中只有一句注释）"；
  - `.superpowers/sdd/2026-09-25-aggregate-economy/progress.md:18` 把 R6"阶层流动 + 产业转换"记为**未开始**；`Debt.java:37` 的 `defaulted` 自述"供阶层流动判据用；当前恒 false"。
- **用户目标**：派单问的是"家户能否真正改变阶层和生产关系"。按上述代码事实，今天**不能**；能被改的只有：批次人口总量/批次居住（social）、行的人口/劳动折算、批次的劳动配额在格内产业间的分配，以及结算性的人口增减。**阶层**（`CohortKey` 的 stratum）与**生产关系记录**（`ProductionRelation`）在创建后都是只读状态。

---

## 四、我没做 / 没验证的

- 没有跑任何 Maven 命令（`compile` / `test` / `verify` / `package`），没有起服务，没有跑整年或任何推进实验；本文全部是静态读码。
- 没有写、改、删任何生产代码；没有改 `docs/**`、没有写 SDD 台账、没有 `git add/commit`。
- 没有做变异自证；没有跑 `EconomyRoundTripTest` / `EconomyInvariantsTest` / `PopulationR4Test` / `EconomyCycleBoundaryTest`。
- 没有实跑"迁移一行/一批人"的实验——**代码里没有入口**，所以"迁不动"的直接证据是写入口清单 + 构造期守卫，而不是失败用例。
- 没有穷举全仓测试文件；"没有迁移 API"的检索是 `git grep` 主源码 + Shell 注册清单 + `withClasses/withRelations` 调用点，**未逐条排除测试夹具里手搭 `EconomyData` 的可能**（夹具能直接构造状态，不构成生产写入口）。
- 没有查历史 revision / SQLite store 里是否存在人工手写的 classes 迁移 diff；没有验证旧档兼容路径。
- 没有验证 `EconomyData` 对 `Debt.id` 与 `debtIdOf` 不一致时的行为；没有验证跨格/跨居住迁移的具体失败点。
- 没有检查其它 worktree / 其它分支；本文以 `/home/cna/SimulatorMosire` 当前工作树（`ts/m1` @ `a7fbfe46`）为准。

**证据检索（供复核）**：
- 写命令注册：`Shell.java:402-458`（社会 4 条 `:432-437`；economy 1 条 `:438-439`；actor 1 条 `:440-443`）。
- `EconomyChangeSet.between` 调用点：`EconomySeedHandler.java:72, 101`；`PopulationEconomyTimeParticipant.java:154, 258`；`EconomyOwnershipTimeParticipant.java:144, 221`；`EconomyCodec.java:244`（模块差异器）——没有"迁移"调用点。
- `ActorChangeSet.between` 调用点：`ActorSeedHandler.java:76, 94`；`PopulationEconomyTimeParticipant.java:156, 260`；`EconomyOwnershipTimeParticipant.java:145, 222`；`ActorCodec.java:167`（模块差异器）。
- `withClasses` 全仓主源码调用点：**只有声明**（`EconomyData.java:489`），无调用。
- `withRelations` 全仓主源码调用点：**只有声明**（`EconomyData.java:546`），无调用。
- `reallocateLabor` 的 `rows` 形参在体内无使用：`grep -n rows` on `EconomySettlement.java:2840-2933` 只命中形参。
