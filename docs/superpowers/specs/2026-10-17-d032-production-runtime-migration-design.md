# D-032 生产方式运行时迁移：约束设计书（2026-10-17）

> **阶段 2 交接后续复核（2026-10-17）**：`docs/superpowers/specs/2026-10-17-d032-stage2-handoff-review-decision.md` 补充/修正 §13 的批量纯循环与失败语义、同 tick 生效条件、不可变回执、无 runtime 旧构造器及串行化等价判据；A→B→独立测试主线保留。§13 的“唯一有效”说明不覆盖该后续裁决。A 批代码正在施工，本注不表示已完成修正。
> **最新局部取代/偏离复核（2026-10-17）**：`docs/superpowers/specs/2026-10-17-d032-stage1-deviation-review-decision.md` 已收口方案多地点复用、默认熟练度效果、经验余数及逐 tick/整周期贡献；本文 §12.2 第 1/5/6/7 项与 §12.4 对应开放项中冲突部分，以该复核裁决为准。§12 的旧优先说明不覆盖此后续裁决；旧正文和历史编译证据保留，不表示修正已实现。
> **状态：约束设计书，可据此派实现；本文所写尚未实现。** 由控制方编写（用户 2026-10-17 指令："你自己写"）。
> **依据（权威顺序）**：
> 1. **用户原话**：`docs/superpowers/specs/2026-10-02-architecture-design-source.md` 的 **D-032 节 U1–U10**（逐字全量，最高权威；本批直接相关的三条另在 §12 逐字复录）；
> 2. `docs/superpowers/specs/2026-10-17-d032-production-experience-choice-design.md`（设计记录：经验/共用经验/公式/解锁/选择/通用拆合）；
> 3. `docs/superpowers/specs/2026-10-17-d032-production-execution-decision.md`（D3 删 / D4 归 production 并扩为计算职责）；
> 4. `docs/superpowers/specs/2026-10-17-d032-production-runtime-boundary-decision.md`（**工程裁决**：状态归属、不增依赖、试算/分配/实际三段、临时只读视图、到货归属、铸币与账户边界）；
> 5. `docs/superpowers/reports/2026-10-17-d032-production-mode-survey.md`（回代码调查报告）。
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`（分支 `parallel/side-module`）。**所有 file:line 只对本树当刻字节成立**；实现前须按 §9 的命令重算清单，不把本文计数当冻结范围。
> **纪律**：不提交、不同步主树、不动 `stash@{0}`、不碰 `ga-opt` 与 `d031-p1-baseline`；**不新增模块依赖、不改 POM/enforcer/边界表**（边界裁决 §2）。

---

## §1 目标与范围

### 1.1 目标（本批要长成什么样）

1. **生产方式模块成为"生产"的唯一权威与唯一计算者**：方案定义（投入/产出/条件/经验条引用/公式）、生产实例、进度、经验与熟练度、效率累计与精度余数、GM 产出数值覆盖，全部归 `simos-production`。
2. **economic 模块只做选择、分配与经济关系**：按需求—利润排序、统一分配劳动/资产/原料、交易与收益分配（工资/地租/分成）、市场与价格、债务与发行政策；**不再维护第二份可独立修改的投入产出数值**。
3. **两步契约**：economy 调 production **试算**（无副作用）→ 排序分配 → production 按**最终投入**推进实例并给出**回执** → economy 结算经济关系 → 持账方落账 → **同一 Revision 原子提交**。
4. **科技以"生产方式的可选升级方案"体现**（U1）：删除科技点抽象面（D1）；解锁 = 家户达到门槛后获得采用资格，**永久记忆**（U10）。
5. **文化与生产效率解耦**（U5/§2.7）：删除"文化身份 → 生产效率倍率"整条通路（D2）；culture 模块保留为**占位**（标签面不删），文化将来只提供需求差异与外部属性（如识字率）。

### 1.2 非目标（本批不做）

| 不做 | 依据 |
|---|---|
| 文化/宗教实现（D-033：教区、奉献、择优信奉） | 用户 2026-10-17 裁定"文化部分暂时放掉，只留一个占位" |
| 共享阶层流动表落账 | D-031 P0 §9 只设计；D-032 未改其批次归属 |
| 旧档兼容 / 双写 / 键归一 | Alpha 口径（`docs/agents/02-一.11`） |
| 新增模块依赖、新框架/适配层 | 边界裁决 §2、§4 |
| 改变既有玩法数值（币种、发行上限、既有定价/分配/人口公式） | 边界裁决 §5 末、§3.6 |
| 为保住 P1 成果做兼容 | 用户 U10："P1成功是个几把，需要删直接删" |

---

## §2 回代码核过的现状（决定"要删改什么"）

| # | 现场 | 事实 |
|---|---|---|
| S1 | `simos-economy/.../model/ProductionProcess.java:49` | 实例（方案引用 `industry`+`modeKey`、经营者、`progressDays`、`cycleLaborMilli`、`cycleInputUsedMilli`）今天住在 `EconomyData.units`（`EconomyData.java:318`） |
| S2 | `EconomyData.java:334/:335` | `outputQuantityOverrides`、`productionEfficiency` 今天也在 economy |
| S3 | `simos-economy/.../model/ProductionCandidate.java:57`、`EconomyEntrySettlement.java:1014`（调用 `:871`） | **第二份竞争定义面**：候选自带 I/O 数值，`templateFor` 现场合成 `IndustryDefinitionView`（slots 手写四档、`allocation=Split(500,500)`、`MoneyOutput`/`skill` 空） |
| S4 | `simos-production/.../ProductionData.java:28` | 旧注释写"运行实例（unit/劳动/账户/债务/进度）**永不搬入**" —— **与新裁决冲突，实现时改口径** |
| S5 | `simos-economy/.../time/EconomySettlement.java:1600` | **到货**是在途运输到达，**留 economy**，不随生产段搬走 |
| S6 | `simos-economy/.../time/ProductionSettlement.java:34` | 产出**分配**（关系 + 事实 → 转移）纯函数，**留 economy** |
| S7 | `simos-economy/.../time/ProductionEfficiencyBook.java:70` | 旧效率累计接收被删链提供的 modifier（`[0,2000]‰`）⇒ **重建，不照搬** |
| S8 | `simos-economy/.../time/MintSettlement.java:233` | 铸币产出率今天可被**经济政策键** `MINT_MILLI_PER_FURNACE_CYCLE` 运行期覆盖（常量在 `simos-app/.../EconomySeeder.java:4045`）⇒ 权威需收口到 production |
| S9 | `simos-production/.../contract/IndustryDefinitionView.java:52-66` | 真正承载投入/产出的是**按 hex 实例化**的产业定义；`ProductionModeDefinitionView` 只有 4 字段且与 industry 无字段级链接 |
| S10 | `simos-economy/.../time/LaborQueueSettlement.java:49-51`、`EconomySettlement.java:7972-7974` | 今天"被缩减的劳动**留在空缺**，不塞给别的 unit" ⇒ **与 D-032 §2.5 第 5 条冲突，本批必须改** |
| S11 | `simos-app/.../time/PopulationEconomyTimeParticipant.java:1223-1243` | 提案汇合点：`ECONOMY/SOCIAL/ACTOR/PRODUCTION/GOV` 的 ChangeSet 已在**同一个 `moduleChanges`** ⇒ 账户原子性只需**别漏**，不是新建机制 |
| S12 | 两条推进接线：`PopulationEconomyTimeParticipant.java:585`（真实）、`EconomyOwnershipTimeParticipant.java:192`（备用） | 都调 `production` 计算与 `CultureAptitudeBridge`（后者随 D2 删） |
| S13 | `simos-economy-api/pom.xml:92`（exclude 段） | "economy-api 不得反向引用 production"已有**机械面**（enforcer exclude + 注释） |

> 其余现场（字段消费者、`dailyInputPerUnit`/`dailyLaborPerUnit` 零消费者、`AssetKind` 6 值枚举、无"区域"量纲、迁移随人搬什么、经验/解锁/公式零存在）见调查报告 §1–§3，本文不重复。

---

## §3 冻结的状态归属（来源：边界裁决 §1；本文只做落形）

| 状态/职责 | 唯一拥有者 | 本批动作 |
|---|---|---|
| 生产方案与配方、生产条件、可配置熟练度公式 | **production** | 新建（形状见 §4.1） |
| 生产实例（身份、方案引用、经营者引用、地点、进度、周期实际劳动与实际投入累计） | **production** | 从 `EconomyData.units` **迁出** |
| 生产经验、熟练度、解锁 | **production** | 新建；家户 × 经验条为账目方向 |
| 生产效率累计、精度余数 | **production** | 从 economy 迁出并**按新公式重建** |
| `outputQuantityOverrides`（GM 产出数值覆盖） | **production** | 从 economy 迁出（写入口/校验/状态/读数一起收口） |
| `relations`（`ProductionRules`）与 `ProductionSettlement` 分配计算 | **economy** | 不动（只改"实例 ID 从哪读"） |
| `operatorConditions`（经营盈亏、缩产/退出状态） | **economy** | 不动；需要影响生产时转为**本次计划的规模/预算输入** |
| `allocations`、劳动与资源分配、`assetShares`、`productionOrganizations` | **economy** | 通过**稳定实例 ID** 关联 production；**不留实例完整状态的持久镜像** |
| 市场、价格、需求、债务、工资、租金、分成、发行政策 | **economy** | 不动 |
| 实际钱粮库存、货币账户 | **actor** | 不动；生产消耗/产出与经济支付经既有账户路径落账 |
| 家户身份与人口 | **Social** | 不动；迁移时各领域同步拆合 |
| `ProductionCandidate` / `templateFor` 第二定义面 | —— | **删除**（D4）；"按需进入生产/创建产业实例"的能力改由 production 承接（§4.3） |
| 科技点面（`TechnologyDefinition/Id/Mastery/Lookup`、`MasteryScope`、两条命令、`ProductionData` 两组件） | —— | **删除**（D1） |
| 文化→效率倍率链（culture 规则/composer/两条命令/`CultureAptitudeBridge`/`ProductionEfficiencyModifier` 接收链） | —— | **删除**（D2）；culture 模块与标签面保留 |
| P0 劳动只读口（`ProductionLaborLookup`/`UnitProductionFacts`/`HouseholdLaborShare`/`EconomyProductionLaborLookup`） | —— | **删除**（D3）；劳动事实改由**调用入参**提供 |

---

## §4 冻结的形状（接口/载荷/数据流；类名由实现定，语义面必须齐）

### 4.1 方案（Plan）与经验条（ExperienceTrack）

**方案定义**至少能表达：稳定 ID · 名称 · 生产周期（天）· 每规模单位的资产需求（含"每 N 单位"语义所需的规模标度）· 每规模单位投入（可按资产/渠道分路）· 每规模单位劳动 · 每规模单位产出 · 铸币产出（可空）· 适用制度/组织条件 · **引用的经验条与公式**（直接经验条 a + 可共用经验条 b）· 可用性条件（含外部属性门槛）。

**经验条定义**至少能表达：稳定 ID · 名称 · 上限 · 增长换算 · 衰减规则 · 熟练度解释（经验 → 熟练度的映射由**公式**给出，不硬编码 0–1 或千分制）。

**家户经验账**：`(HouseholdId, ExperienceTrackId) → 经验值`；**解锁集合**：`HouseholdId → 已解锁方案集合`（永久记忆，见 I7）。

**规则**：
- ★ 方案的**数值与条件只在 production 存在一份**；economy/其他模块不得持久化可独立修改的副本（边界裁决 §4）。economy 可持有**临时只读视图/本轮索引**（同 revision 内不得陈旧）。
- 选用已有方案 ⇒ **只建实例**（引用方案稳定 ID），**不复制方案定义**；"按需新建定义"与"按需新建实例"是两件事（边界裁决 §3）。
- 方案与实例分离；**地点（hex）不迫使复制共享方案**：方案可跨地点共享，实例携带地点。

### 4.2 计算契约（放在 `production.contract`；实现留在 production 内部，由组合根装配）

两个调用，**都不接受 economy 状态对象，只接受不可变输入值**：

```text
① 试算(方案ID, 家户条件, 外部属性事实) → 预计结果
     输出：可行性(具名 reason) · 可生产规模 · 劳动需求 · 原料/资产需求 · 预计产出（物理量）
     约束：无副作用（不写状态、不扣库存、不涨经验、不推进时间）
② 执行(实例ID 或 新建请求, 最终投入) → 回执
     输入：最终投入（劳动分配、原料、资产占用）+ 本次规模/预算
     输出：实际消耗 · 实毛产（含铸币候选数量）· 进度变化 · **实际出工家户与各自贡献** · 精度余数
     并产出 production 本域 ChangeSet（由 production 从本域完整候选态派生）
```

- ★ **价格/需求不进 ①**：production 不靠市场价格/需求决定物理产量；估值、排序、分配是 economy 的事（边界裁决 §3.2）。
- 输入/输出类型优先落 `production.contract`；复用 economy-api 的稳定 ID 与通用值类型；**economy-api 禁引用 production**（S13 已有机械面）。
- 公开契约**可以承载基于输入计算候选结果的行为**；仍禁止跨域直接写状态（边界裁决 §2）。

### 4.3 同 tick 数据流（边界裁决 §3；含账户与铸币）

```text
共同基态 + 本 tick 外部事实
 → economy 整理可用资源、经济约束与候选
 → production 试算：可行性/规模/劳动与原料要求/预计产出（无副作用）
 → economy 按需求—利润排序；统一分配劳动、土地、工具、原料（不得重复占用）
 → production 按最终实际投入计算生产、进度与经验，产生本域候选态/变更集
 → economy 用回执计算估值、工资/租金/分成等经济变化
 → 持账方（actor）计算实际库存/货币变化；需要迁移时 Social 与经验账同步处理
 → 统一候选态校验（复用 P1 既有 CandidateStateValidator）→ 同一 Revision 原子提交
```

- **"按需进入生产/创建产业实例"**：economy 在分配阶段提出**创建请求**（要采用哪个方案、在哪一格、由谁经营），production 在自身状态里创建实例并返回；经济关系（组织、劳动来源、份额）由 economy 同批更新（边界裁决 §1 末、§3）。
- **到货**仍按既有经济顺序（S5），不因紧邻生产段而改变日内次序（边界裁决 §4）。
- **铸币**：production 算候选产出数量；economy/持账方按既有发行规则执行并返回实发行读数（边界裁决 §5）。
- **账户**：实际库存/货币改变必须计入同一次提案（S11 已有汇合点），**不允许**"生产进度成功但消耗落账失败"（边界裁决 §5）。

### 4.4 公式（受限表达式）

冻结为**受限表达式**（控制方工程取舍，用户未反对）：
- 显式声明输入（经验条、常量、方案参数、家户条件）——不允许隐式读状态；
- 白名单运算与函数（至少覆盖用户例式 `1+x*a`、`1+x1*a+x2*b`，并为非线性/分段保留表达空间）；
- **确定性求值**（整数/定点语义明确，禁浮点不确定性）、**版本化**（定义携带版本，改变求值语义即换版本）；
- 求值错误 ⇒ **契约故障 ERROR、拒绝整个候选提交**，**不得静默退化成中性加成**；
- 不嵌入脚本运行时；不引入 eval。

### 4.5 外部属性契约

- 方案可用性可要求"家户具备某外部属性并满足门槛"；**属性由所属领域提供、组合根装配为本次调用的不可变事实**，production 不感知来源模块；
- 属性缺失/未知 ⇒ **fail-closed**（该条件不满足，具名 info 拒绝），不静默放行；
- 键/类型/未知值语义由实现设计，但必须满足上述两条。

---

## §5 不变量（I1–I16）

- **I1** 方案、实例、经验、解锁、效率/精度、GM 产出覆盖的**唯一权威在 production**；economy 不再持有可独立修改的同类数值。
- **I2** 一切修改仍走 `Command → ChangeSet → Revision`；无第四种 Command、无激活开关、无静态可变全局。
- **I3** 状态变更集**逐字段往返**（铁律 5）：production 与 economy 两侧的 codec/change/apply 重建测试必须同步。
- **I4** **不新增模块依赖、不改 POM/enforcer/边界表**；economy 只消费 `production.contract` 公开契约，不 import production 内部状态/算法类；production 不反向依赖 economy 实现；`economy-api` 不引用 production。
- **I5** 试算**无副作用**：不改任何切片、不写 revision、不推进时间、不涨经验。
- **I6** 预计与实际**共用同一方案定义、同一公式、同一覆盖参数、同一时间单位**；实际依**最终投入**计算，允许与预测不同，但**不得**用预测值顶替实际值。
- **I7** **解锁永久**：经验衰减只降熟练度/加成，**不撤销**已解锁资格；可用性另一侧仍受可行性条件约束（用户 U10）。
- **I8** **统一预算**：同一份劳动/资产/库存不得被两个方案或两个实例重复占用；被缩减释放的资源**必须继续参与后续分配**（本条**取代** S10 的"留在空缺"口径）。
- **I9** **只按实际生产贡献累计经验**；协作生产**只记一份总产出**，"出过工"不等于取得全部产品；回执必须保留各家户实际劳动贡献。
- **I10** 经验条随人口**完整携带**（不乘比例）、合户按人口加权平均；财债按比例切分并逐项相加；文化配置本体不迁移（U5）。
- **I11** 实际钱粮/货币变化与生产结果**同一次 Revision 原子落账**（含 actor 的 ChangeSet）；任一环失败 ⇒ 零部分提交。
- **I12** 铸币**技术产出率**归 production（含 GM 覆盖收口），**发行资格/上限/记录/入账**留 economy/持账方；economy 不得再覆盖一次产出率。
- **I13** 临时只读视图允许，但**不得跨 revision 陈旧**；同次 Advance 的后续阶段必须看到本次最新候选态；禁止持久化第二份可写权威。
- **I14** 既有经济规则（定价、分配、市场、债务、人口、财债公式）**一字不改**；`ProductionSettlement` 等分配计算留 economy 且行为逐值不变。
- **I15** 业务拒绝 = INFO 具名理由；契约/跨切片一致性故障 = ERROR 不降级；每模块一个 `XxxLog` 门面（唯一拼写点），新增/修改生产与经济阶段必须同时加日志。
- **I16** Alpha：不写兼容位、不读旧档、不迁移旧格式；"旧档读不回来"不是缺陷。

---

## §6 验收判据（V1–V12；测试与变异自证留到最后由独立测试 owner 统一执行）

| ID | 判据 | 判定要点 |
|---|---|---|
| **V1** 试算无副作用 | 试算前后 `head`/`revisions` 行数/`SimulationState.modules()` 全切片逐值不变 | 三条具体读数（照 P1 的 `planOnly` 断言形制） |
| **V2** 预测/实际同源 | 同方案同输入 ⇒ 试算结果稳定；实际依最终投入；构造"预测 ≠ 实际"的场景并断言两者各自正确 | 具体数值，不许只断"不同" |
| **V3** 统一预算与回填 | 同一劳动/资产/库存不被两方案重复占用；缩减后释放资源被后续候选使用（**这是行为变更**：对旧"留在空缺"口径的取代） | 断言分配总量与新候选的实投 |
| **V4** 协作与经验归属 | 多户协作只算一份总产出；经验按各家户**实际贡献**累加；回执保留贡献明细 | 手算值 |
| **V5** 经验/衰减/门槛/解锁 | 增长、逐 tick 衰减、门槛开放新方案；**衰减跌破门槛后已解锁仍可用**（I7） | 三次读数（达门槛前/后/衰减后） |
| **V6** 迁移拆合 | 人口迁出携带各经验条**完整值**（不乘 p）；合户按人口加权平均；财债按比例切分逐项相加 | 用户给定例式的数值（来源 60 的 10 人 + 原有 30 人经验 20 ⇒ 30） |
| **V7** GM 覆盖与铸币权威 | GM 产出覆盖同时影响**预计与实际**；铸币技术产出率归 production；economy 不再覆盖；发行规则/上限/记录/入账仍按既有玩法 | 覆盖前/后/经济侧不再生效三条 |
| **V8** 账户原子性 | 生产消耗/产出与实际库存/货币变化同一次 Revision；注入中途失败 ⇒ 零部分提交 | 复用既有原子批与候选态校验 |
| **V9** 无陈旧视图/悬空实例/重复推进 | 同 tick 多次试算不推进生产、不涨经验；跨 revision 读取不到陈旧视图；实例引用完整 | 机械断言 + 引用完整性守卫生效 |
| **V10** 完整状态往返 | production 与 economy 两侧**全组件** JSON 往返 + `apply` 逐字段重建；新增组件全部登记 | 铁律 5 守卫进 `GUARDS_TABLE` |
| **V11** 边界机械面 | `simos-economy-api` 不引用 production（enforcer）；economy 不 import production 内部；production 不反向依赖 economy 实现 | 构建期红 + 源码扫描（扫描面须先自证非空） |
| **V12** 完整门禁 | 侧线 `tools/mvn-lock.sh -pl simos-app -am clean verify` rc=0（Spotless/Checkstyle/SpotBugs/前端门禁全过） | 真实 rc + 模块汇总；**Skipped 不算通过** |

**负向用例（并入 V1/V5/V7/V9/V11）**：删掉试算的"无副作用"⇒ 断言当场红；把解锁改成衰减重判 ⇒ V5 红；让 economy 再写一份方案数值 ⇒ V11/扫描红；把 `templateFor` 留着不删 ⇒ 第二权威扫描红；漏掉 actor 的 ChangeSet ⇒ V8 红。每条须"改坏 ⇒ 当场红 ⇒ 还原 ⇒ MD5 一致"。

---

## §7 责任区与文件所有权

| 责任区 | 拥有 | 禁止 |
|---|---|---|
| **D032-IMPL（一个实现 owner，一次写完到编译过）** | `simos-production/src/main`、`simos-economy/src/main`、`simos-app/src/main`（装配与两条推进接线）、`simos-economy-api/src/main`（**只加值类型**）、`simos-culture/src/main`（仅删 D2 通路，保留标签面）、相关**活注释与门禁清单**（不含测试代码） | 写/跑测试；改 POM/enforcer/边界表（I4）；改主树/`ga-opt`/P1 基线；`git add/commit/stash`；新增依赖边或激活开关；把方案数值再存一份到 economy |
| **D032-TEST（最后另一个测试 owner）** | 受影响测试、门禁清单、独立长模拟/黄金脚本 | 改生产代码（受控变异必还原例外） |

**交账形制**：实现 owner 写 `.superpowers/sdd/<topic>/impl/impl-ledger.md` + `task-report.md`（实际改动文件 `路径:行`、真实 `compile` rc 与日志、**偏离本文之处**、未做/未验证一节）；测试 owner 照 P1 的 `test/control-v1v7.md` §8 形制。

---

## §8 日志与可观测

- 每模块一个 `XxxLog` 门面；新增/修改经济阶段必须同时加日志（I15）。
- 需保留/新增的具名事件（名字由实现定，语义必须齐）：试算被拒（原因）、分配缩减与回填、实例创建/推进/关账、经验增长/衰减/解锁、GM 覆盖生效、铸币候选与实发行、跨域校验拒绝。
- **业务拒绝 = INFO**（具名理由）；**契约/跨切片一致性故障 = ERROR 不降级**；逐笔明细 = TRACE。

---

## §9 迁移面清单（★ 设计时快照，**实现前必须用下面的命令重算**）

**命令（在本树根执行；勿用 `.units()` 粗数，它含军事单位）**

```bash
# 实例状态真实读者（economy / app / 共享 ID）
grep -rl "ProductionProcess\|ProductionUnitId" simos-economy/src/main --include=*.java   # 设计时 49
grep -rl "ProductionUnitId" simos-app/src/main --include=*.java                          # 设计时 3
grep -rl "ProductionUnitId" simos-economy-api/src/main --include=*.java                  # 设计时 8
# 要迁走/收口的三个 economy 组件
grep -rl "outputQuantityOverrides" simos-*/src/main --include=*.java                     # 设计时 12
grep -rl "productionEfficiency\|ProductionEfficiencyState" simos-*/src/main --include=*.java  # 设计时 9
grep -rln "ProductionCandidate" simos-*/src/main --include=*.java                        # 设计时 12
#   ★ 反例（不要这么数）：`grep -rl "\.candidates()\|ProductionCandidate"` 会混进无关同名调用
#     （设计时得到 22，比真值多 10）—— 命中数只是线索，实现时必须逐个核文件
# 提案汇合点与两条推进接线
grep -rn "moduleChanges.put" simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java
grep -rn "new EconomyDayStepper" simos-app/src/main/java/io/mosire/simos/app/time/*.java
```

**必须覆盖的迁移面（边界裁决 §6）**

1. production 与 economy 的**完整状态、codec、ChangeSet、往返不变式**（新增/删除组件全部登记 + 守卫表）；
2. 所有**实例引用/构造守卫**（经济侧今天对 units 的引用完整性检查改为针对同一候选态经 `production.contract` 只读校验，**不得因 units 迁走就删守卫**）；
3. **命令/工具/GUI/权限查询**中读实例或方案数值的路径（`CatalogTool`、`ApiViews`、`ToolSupport`、`BindingRegistry`、访问作用域等）；
4. **两条推进接线**（S12）与**账户提交路径**（S11 汇合点）；
5. **预计生产、劳动规模、实际收获、铸币消费的统一计算入口**（迁入 production 的算式与重建的效率/经验面）；
6. `simos-app/.../world/EconomySeeder.java` 等播种路径：方案定义与实例的创建改由 production 承接；GM 覆盖值的正确领域接收。

**明确不搬**：`ProductionSettlement`（分配，S6）、`operatorConditions` 及其经营状态（economy）、到货段（S5）、市场/价格/债务/工资/租金（economy）、账户（actor）。

---

## §10 未做 / 未验证（设计落盘时）

- **本文只是约束设计**：一行代码未改、未编译、未跑测试；所有 `file:line` 与计数是 2026-10-17 本树快照。
- 未生成逐文件的"搬/删/改"终稿（§9 给了命令与类别；终稿由实现 owner 按命令重算并在账本里逐文件列出）。
- 未决定具体类名/方法名/组件名（裁决：由设计方决定，不再上呈用户）。
- 未评估性能（每次 tick 每候选一次试算）；若成为问题另开批次，**不得**顺手引入缓存层掩盖语义。
- 未验证"受限表达式"能覆盖用户全部未来需求（只保证覆盖 U4 例式并保留非线性/分段空间）。

---

## §11 用户原话附录（逐字）

**全量逐字入口**：`docs/superpowers/specs/2026-10-02-architecture-design-source.md` 的 **D-032 节 U1–U10**（U1 科技=可选升级方案；U2 家户经验与解锁；U3 通用迁移与劳动分配中间口径；U4 最新主副业/专门经验/共用经验/公式例；U5 公式可配置与文化解耦；U6 主业副业不需专门机制 + 删改授权；U7 D1/D2 直接删；U8 D3 删/D4 归 production；U9 授权控制方裁决工程分工；U10 解锁永久 + P1 直接删）。**该处为最高权威，本节不复录以免与原文产生二义。**

**本批派单直接相关的三条（逐字复录）**：

> 主业副业不是啥需要在乎的东西，现有的生产方式选择是自动化的，主业作用根据我的最新设计也是自动体现出优势的；
> 重点是，你要狠下心来把现有代码里不符合预期设计的给全删改掉，然后再把我的新设计应用上去

> D3直接删没毛病
> D4我建议归生产方式模块，生产方式模块干脆提供单个家户选用某个生产方式，预计/实际的产出和家户状态更新，而不是只提供一个模板，经济模块调用生产方式模块之后，负责计算随后的家户间经济关系，

> 肯定算数
> P1成功是个几把，需要删直接删

**控制方工程取舍（★ 非用户原话，用户可推翻）**：受限表达式公式（§4.4）；经验数值参数按"可调默认值"先定（§4.1）；属性缺失 fail-closed（§4.5）；由控制方直接写本文（用户 2026-10-17 指令"你自己写"）。

---

## §12 阶段 1 实收与阶段 2 冻结（控制方，2026-10-17）

> 本节是**派单书的正文**：阶段 2 的实现 owner 按本节 + §3–§7 施工；本节与 §1–§11 冲突时以本节为准（它是在阶段 1 真实代码之后的落定）。

### 12.1 阶段 1 实收（真实证据）

- 实现 owner 终稿：`tools/mvn-lock.sh -DskipTests compile -pl simos-app -am` → **rc=0 · 18/18 SUCCESS**，日志 `.superpowers/sdd/2026-10-17-d032-runtime-migration/evidence/s4-compile-final.log`。
- 控制方**独立复跑同一命令**：**rc=0 · 18/18 SUCCESS**（日志 `…/control/control-compile-recheck.log`）；验收全文 `…/control/stage1-acceptance.md`（含删除面"真删"核对、迁移面计数对账、偏离裁定）。
- ★ **交付物是「契约 + 引擎已编译、未装配」**，**不是**"日循环已按两步契约跑通"。任何文档不得写反。
- 阶段 1 未改 POM/enforcer/边界表、未写未跑测试、未提交、未动主树/`ga-opt`/P1 基线——控制方按改动时间窗（60 分钟）核对通过。

### 12.2 阶段 1 偏离裁定（逐条）

| # | 偏离 | 裁定 |
|---|---|---|
| 1 | 方案稳定 ID 仍是带 hex 语义的 `IndustryId`（`IndustryHexKeys.hexKeyOf`）⇒ §4.1 末条"方案可跨地点共享"**未达成** | ★★ **本行已被取代**（`2026-10-17-d032-stage1-deviation-review-decision.md` §1）：方案一次定义、多地复用**必须支持**，不得降级为开放项；工程可拆子批次但属本轮 D-032 范围。**当前有效内容见 §13.4 A 批第 4 条** |
| 2 | 增补两个驱动口 `ProductionRuntimeEngine.advanceDay()`（世界级逐 tick 衰减）与 `grantUnlocks(HouseholdId)`（迁移/合户补授） | **接受能力，方法名不冻结**（复核 §2 第 2 项）：全部经验逐 tick 衰减，**同一世界 tick 只衰减一次**（重复试算/市场轮/调用不得重复衰减）；可用已有时钟与驱动上下文守卫，**不新增独立时间推进者** |
| 3 | 执行入参补 `assetOccupancy` | **接受**（§4.3 原文含"资产占用"）；★ 补（复核 §2 第 3 项）：其数值必须来自**统一分配**，production **不得**回读另一套资产权威 |
| 4 | 白名单增补 `MulDiv`；版本校验 = 等于 `CURRENT_VERSION`；`Piecewise` 谓词"非零即真" | **接受**（复核 §2 第 4 项同）：量纲/取整/除零/溢出/版本语义明确，错误拒绝候选提交、不退化中性；只要求同版本自洽 |
| 5 | 新效率公式代数形状（熟练度加成作用于**劳动**、三级整数除法、余数 5→3、不保留 `cycleModifierSumPerMille` 与 `[0,2000]‰`） | ★★ **本行已被取代**（复核 §3）：**不接受**把加成固定为"只提高劳动有效量"当作默认等价实现；默认必须能表达用户例式「总生产效率 = 总生产效率 ×(1+x×熟练度)」。**当前有效内容见 §13.4 A 批第 1 条** |
| 6 | 经验增长不做余数结转 | ★★ **本行已被取代**（复核 §4）：**不接受**逐次丢弃零头（"长期累计会消化截断"数学上不成立）；须足够精度累计 + 结转余数。**当前有效内容见 §13.4 A 批第 2 条** |
| 7 | 解锁在周期关账按**实际出工家户**授予；门槛 0 的方案不进 `unlocks` | ★★ **本行已被取代为部分接受**（复核 §2 第 7 项 + §5）：门槛 0 可直接可用、不必写入解锁集合，永久解锁不撤销；但经验必须**随实际生产 tick 增长**，达门槛**当 tick** 即可授予，不得一律拖到收获日。**见 §13.4 A 批第 3 条** |
| 8 | `ProductionEfficiencyState` 现存两份（production 新面 + economy 旧面） | **仅限阶段 1 的中间态**；阶段 2 必须消灭 economy 那份 |
| 9 | `ProductionRuntimeEngine` 未被装配 | **不接受为完成**；这是阶段 2 的核心目标 |

### 12.3 阶段 2 范围（冻结；对应账本 §6.1 的 U 编号）

必须全部做到，且**每一步都真实 `compile` rc=0**：

1. **U2 装配与线程**：组合根装配 `ProductionRuntimeEngine`，把 `ProductionRuntime`（只读投影）线程进 economy 会话（`EconomySession`/`EconomyStateBuilder`）与日循环；先通只读通路。
2. **U1 写路径改经两步契约**：economy 的实例创建/推进/关账/日投入改走「`probe` → 分配 → `execute` → 回执」，economy 不再写 `units`/`productionEfficiency`/`outputQuantityOverrides`。
3. **U1 删除面**：删掉 economy 的 `units`、`productionEfficiency`、`outputQuantityOverrides`、`candidates` 四组件，连同 codec / ChangeSet / apply / 便捷构造器 / 守卫的同步改造（守卫**不得**因搬家就删，见 5）。
4. **U3 D4 收口**：删 `ProductionCandidate` 与 `EconomyEntrySettlement.templateFor`（`:1014-1051`），**同时**由 production 承接"按需进入生产/创建实例"（`ProductionCreationRequest`）——用户 U8 明令不得连带删掉该能力。
5. **U7 守卫改造**：economy 侧实例引用/构造守卫改为**针对同一候选态经 `production.contract` 只读投影**校验。
6. **U8 播种路径**：`simos-app/.../world/EconomySeeder.java` 等播种路径的方案定义与实例创建改由 production 承接。
7. **U5 铸币权威收口**：`MintSettlement.java:233` 的政策键 `MINT_MILLI_PER_FURNACE_CYCLE` 运行期覆盖退役，改吃 production 的有效参数（I12）。
8. **U9 app 读路径**：`ApiViews` / `ToolSupport` / `BindingRegistry` / 访问作用域 / `CatalogTool` 改读只读投影。
9. **U6 两条推进接线**：`PopulationEconomyTimeParticipant:585`、`EconomyOwnershipTimeParticipant:192` 按新契约接线；`ProductionRuntime.changeSet()` 汇入 S11 汇合点，与 ACTOR 账户**同一次 Revision**（I11）。
10. **U12 I10**：人口迁移的经验**完整携带**、合户按人口**加权平均**。
11. **U13 I9**：回执的"实际出工家户与贡献"被经济侧消费（估值/工资/分成按实际贡献）。
12. **U4**：economy 旧 `ProductionEfficiencyBook` 退役，新公式成为唯一消费面（承接 12.2 第 5 条）。
13. 顺手清理：空目录 `simos-economy-api/.../api/production/`（阶段 1 删净后遗留）。

**不在阶段 2**（留阶段 3）：U10 新增 GM 写入口（产出覆盖、经验条/可用性条件的命令与工具提示）、U11 效率/经验的 GUI/工具读数。**测试与变异自证**（V1–V12）留独立测试 owner。

### 12.4 开放项（记录，不阻塞阶段 2）

- **D-032-D1 方案跨地点共享**：`IndustryId` 的规范串带 hex 语义（`farm@0_0`），方案定义因此按地点各存一份。"方案可跨地点共享"是**控制方自加条目**（源自边界裁决 §3"定义/实例两件事"），**非用户原话** ⇒ 待用户裁定，阶段 2 不动 ID 形状。
- 效率公式的代数形状与经验数值参数 = 控制方工程取舍（§12.2 第 5/6 条 + §11 末），用户可推翻。

### 12.5 阶段 2 硬约束（违反即派单无效）

- **一个责任区一个写代理**：阶段 2 由阶段 1 的**同一 owner** 续做（它拥有 `simos-production/src/main`、`simos-economy/src/main`、`simos-app/src/main`、`simos-economy-api/src/main`（只加值类型）、`simos-culture/src/main`、相关活注释与门禁清单）。
- **不得留"改一半编译不过"的中间态**：每一步收口在真实 rc=0；做不完就收口在 rc=0 的边界，按 **BLOCKED** 交账（"写进未完成项"不能替代验收判据）。
- **不得**：改 POM/enforcer/边界表（I4）；写或跑测试；`git add/commit/stash`；动主树/`ga-opt`/P1 基线；新增依赖边或激活开关；为让编译过而删守卫、放宽校验、留兼容位、留第二份可写权威（不得已暂留必须写清剩余步骤）。
- I1–I16、§6 判据、§7 交账形制、§8 日志纪律**不变**；新增/修改经济阶段必须同时加日志（业务拒绝 INFO、契约故障 ERROR 不降级）。

---

## §13 阶段 2 实收与阶段 3 派单（★ 当前有效；承 `2026-10-17-d032-stage1-deviation-review-decision.md`）

> 本文头部第 3 行的取代关系已生效：§12.2 第 1/5/6/7 项与 §12.4 的对应开放项按复核裁决更新（已在表内逐行标注）。本节是**当刻唯一有效的派单正文**。

### 13.1 阶段 2 实收（控制方自跑复核）

- 实现 owner 终稿 rc=0（`evidence/s10-compile-final.log`：19 行 `SUCCESS [`、`[ERROR]` 0 行、`BUILD SUCCESS`）；**控制方独立复跑** `tools/mvn-lock.sh -DskipTests compile -pl simos-app -am` → rc=0（`…/control/control-compile-recheck.log`）。
- 真做到 4 项：引擎装配（`PopulationEconomyTimeParticipant:297-302`、`EconomyOwnershipTimeParticipant:190-200`）· PRODUCTION 键恒 put 进同一 `moduleChanges`（`:1243-1263` / `:273-286`，但今天内容是 `between(base, base)`）· ~~铸币政策键运行期覆盖退役 + `MINT_TECH_RATE_POLICY_RETIRED`~~ ★★ **本条经 B1 批回代码核实为【假】**：`MintSettlement:238-247` 至今仍 `policyOverride ? policyMilliPerFurnaceCycle : line.getValue()`，`EconomyPolicyKey.java:54` 该键仍在，`MINT_TECH_RATE_POLICY_RETIRED` 在 main **0 命中**（只存在于本台账/账本的自述里）⇒ **I12 未实现**，更正见 §13.10 更正一 · 空目录清理。
- 未做 8 条半；**BLOCKED 成立**（收口在 rc=0，未留编译不过的中间态）。控制方另核：HEAD `03faf0a3` 未动、`stash@{0}` 未动、窗口内 `pom.xml` 0 个 / `src/test` 0 个、`runtime.execute(...)` 调用点 0 个（机制已就位、内容待接）。
- ★ 控制方查出、必须在 B 批清掉的两处遗留：`simos-app/.../tools/read/CatalogTool.java:481` 与 `simos-economy/.../tools/write/EconomySetEconomyPolicyTool.java:64` 的**提示文本仍在教 GM 写** `MINT_MILLI_PER_FURNACE_CYCLE`（键已失效）；`simos-economy/.../EconomyProductionDefinitions.java:3` 直接 `import io.mosire.simos.production.ProductionData`（非 contract 包）。两条都进 V11 扫描面。

### 13.2 阶段 2 三个提问的裁定（控制方）

1. **允许** production 增补**批量驱动口**；★ 已被 `2026-10-17-d032-stage2-handoff-review-decision.md` §2 修正：**不要求**字面"零准备纯循环"—— 允许批次级准备、共享条件、经验归并和收尾，但 **① 同 tick 的生产效果必须取同一时点的生效条件**：组合根给出**明确世界 tick** → 经验按明确日序衰减一次 → 形成当 tick 的经验/熟练度等生产生效条件 → economy 在这份条件下试算与统一分配 → 本 tick 各实例执行**同口径**；各实例按实际贡献产生经验增量后**按家户×经验条归并并结转精度**（同家户同经验条分布在多个实例时实际劳动**不得重复计**；分组/排列不得额外创造经验或生产增益），达门槛**当 tick** 记入永久解锁，新增经验/资格用于后续 tick、**不回头重算本 tick** 已分配劳动；**"当前候选态"（可含已累计新经验，供迁移/后续读取）与"本 tick 生效条件"必须区分**。**② tick 身份由既有调度/引擎上下文明确提供**；同实例同 tick 重复推进、同 tick 重复衰减**必须被检测**（不能只在注释里写"调用者别重复"）；同一次 Advance 跨多 tick 时逐日依次执行，最终 ChangeSet 仍从 **Advance 起点**到当前完整候选态派生（**不得**每日重置基态后只提交最后一天）；不同实例可共享家户与资产，输入必须已过统一预算与占用检查。**③ 回执可以携带不可变实例快照**（ID/进度等必要事实，或经当前候选态只读查询获取）—— 我此前"不许回传推进后实例"的措辞**作废**；禁止的只是把 runtime 内部**可写** Map/可变对象暴露给 economy，或让 economy 改本地副本后当 production 权威写回；公开 record 的不可变性须覆盖内部集合。**④ 失败语义**（复核 §2.3）：正常业务拒绝可逐项具名返回，但被拒请求**不得残留**新实例、进度、经验、原料消耗或占用变更（其释放资源如何重分配仍归 economy）；**契约故障、公式错误、重复推进、跨域不一致必须终止本次候选计算、拒绝整个相关 Advance 提交** —— 可抛错由组合根废弃候选态、或本域回滚（实现选型自由），**禁止**在部分污染的 runtime 上继续收集回执并最终提交。★ 冻结 tick 语义：**一次 `execute` = 该实例一个生产日**。
2. **是**：§12.3 第 2/3/12 条同一责任区同批（B 批）。**测试 owner 不同批进场** —— 删除面未落地前测试必红，同批只会互相污染信号；B 批交账后立即由独立测试 owner 接（V1–V12 + 复核裁决 §6 与本文 §13.7 的新判据）。实现 owner 必须把"数值来源改变点"逐条写进账本，供测试 owner 定点。
3. **允许**把 `drawCycleInputsPartitioned` 降为**串行**（本批确定实现）。★ 等价判据（复核 §4）：**同一套新规则、相同基态与输入**下，串行与"确定性归并的并行"逐值一致；**不得**要求修正后的新公式复现旧公式的错值 —— 受修正影响的数值须用手算/独立判据解释，不把差异笼统归因于串行。既有到货、周期投入、消费/市场等业务日序不因此改变；未来恢复并行必须保持同 tick 生效条件、全局预算、候选态与归并语义，**不为未来并行预加框架**。

### 13.3 阶段 2 声明的四处偏离的裁定

- `units()` 返回"runtime 投影的**可写副本**"（`EconomyStateBuilder:340/:356`）：**只准作为本轮内的桥**；B 批结束时 economy 三处就地写与 `unitsWorkCopy` 路径必须归零；不得持久化、不得进 ChangeSet（现状未进，正确）；账本与状态文档标注"**不可交付中间态**"。
- 旧构造器保留委托（runtime=null，读实例 fail-closed 抛）：★ 按复核 §4 **只准施工中间态暂留**；**B 批结束后**所有支持生产的有效入口必须显式获得合法 runtime，**没有实际可用职责的旧重载必须删除**（不得以"免得改测试"长期保留"能编译、实际不能履约"的入口；真正只读、不涉及生产的独立用法可有明确**窄接口**，但不得借此让生产写路径静默降级；Alpha 不做无效旧接口兼容）。
- `runtimeDataOf` 读 production 的 `ProductionData`：**B 批必须收口**（引擎由组合根 `new` 已是正确形态）——economy 不得 import `io.mosire.simos.production.ProductionData`/`ProductionSnapshot`，改由组合根注入 `ProductionRuntime`；进 V11 扫描面。
- 未调用 `advanceDay()`：**接受为未做**；B 批必须接线（每 tick 一次，即使经验账为空也走同一路径）。经验条的**数据来源**确定为：播种路径产生默认经验条定义 + GM 写入口（U10，B 批后阶段）。

### 13.4 批次划分

#### A 批（本批派单）：生产侧语义与形状定稿 —— 只动 production（+必要时 `simos-economy-api` 值类型）

1. **效率公式按复核 §3 修正**：默认必须能表达「总生产效率 = 基准 × (1+x×熟练度)」的**总效率倍率**语义；方案显式声明"作用于劳动效率"时才按声明计算；试算/实际/家户利润比较读**同一份配置与同一计算入口**；资源/产出约束由方案明确表达（加成不得凭空多占土地/工具/原料，也不得靠挪动加成位置让已配置效果失效）；常量三分：玩法参数（增益系数、经验增长/衰减、门槛、上限、业务常数）= **配置数据不焊死**；`1000`（千分定点单位）= 版本化数值表示约定可固定；`2000` 若表示"效率上限 200%"属**玩法上限**，不得以"单位常量"之名恢复已退役旧限制。
2. **经验精度按复核 §4 修正**：足够精度累计并**结转余数**；上限/归零边界处理明确（不藏超上限经验、不重发旧零头）；迁移/合户按既有规则处理**完整经验特征（含余数）**；本域完整状态 / ChangeSet / codec / 往返守卫一并覆盖。对账口径：零衰减、未触上限、参数相同时，相同实际劳动量分成多个 tick/实例后，经验主值 + 余数合计与累计换算一致。
3. **周期贡献按复核 §5 修正**：逐 tick 按**实际生产贡献**增长经验（非关账日也增长，不得等到收获日）；一次实际劳动**只计一次**（周期末不得重发）；**整周期逐家户贡献及其当时生效的生产效率**必须能从 production 状态/累计事实正确重建（收获不得只拿最后一天劳动除以整个周期天数，也不得只让最后一天出工者获得整周期影响）；熟练度按实际 tick 生效值累计、不以收获日值反推；门槛在周期中达到即可授予、衰减后资格保留；新增状态仍由 **production 唯一维护**。
4. **方案/实例身份分层**（复核 §1）：方案拥有**不绑地点**的稳定身份；实例拥有自己的稳定身份 + **显式地点**并引用方案；同一方案基础值改变后两地实例均按新有效定义计算；本地事实（土地/库存/自然条件）仍分别传入；经验条/解锁围绕**方案与经验条稳定身份**（换 hex 不重学同一门技术）；实例 ID 生成不得因方案不再带坐标而让"同经营者不同地点"碰撞；关系/资产/市场键须明确指向方案还是实例/地点，**不得只删解析守卫让旧键勉强可用**；**不强制新增特定名称的 ID 类型**（可重塑 `IndustryId` 或另设，由实现设计负责）。
5. production 侧 codec / ChangeSet / 快照 / 只读投影 / `ProductionRuntime` 口同步（新增组件全部登记 + 往返守卫）。
6. economy 与 app 本批**只做机械编译修正**（字段/构造签名搬迁），**不得**改语义、不得动写路径。★ 补（复核 §4）：**不准**通过删守卫、默认空地点、伪造进度或保留第二权威来"机械修编译"；若新形状在该隔离限制下**无法**维持旧消费者编译，由控制方明确把"最小引用收口"并入同一 owner / B 批，**不得**由实现方自行放宽。
7. A 批结束必须真实 rc=0（`tools/mvn-lock.sh -DskipTests compile -pl simos-app -am`），并交账（`路径:行`、rc 与日志、与 §13.4 的偏离、未做/未验证）。

#### B 批（A 批验收后派，同一责任区续做）

§12.3 剩余全部：写路径改经「`probe` → 分配 → `execute` → 回执」· 删 economy 四组件 · 删 `ProductionCandidate` + `templateFor` 并**同时**由 production 承接"按需进入生产/创建实例" · 守卫改经只读投影 · 播种改由 production 承接 · app 读路径收口 · 两条推进接线 + `changeSet()` 汇入（与 ACTOR 同一次 Revision）· I9 回执消费 · I10 迁移（含余数）· 旧 `ProductionEfficiencyBook` 退役 · 13.1 的两处遗留 · 13.3 的四条裁定全部兑现。

### 13.5 硬约束（§12.5 全部继续有效，另加）

- A 批**只动** `simos-production/src/main`（+ 必要时 `simos-economy-api/src/main` 值类型）；economy/app 仅机械编译修正。
- 不得写或跑测试；不得改 POM/enforcer/边界表；不得 `git add/commit/stash`；不得动主树 / `ga-opt` / `d031-p1-baseline` / `stash@{0}`；不得改控制方文档（本设计书、复核裁决、status）。
- 每一步收口在真实 rc=0；做不完按 **BLOCKED** 交账。
- 不得为过编译而删守卫/放宽校验/留兼容位；不得长期保留第二份可写权威。

### 13.6 开放项状态更新

- **D-032-D1 关闭**：只表示"**待用户裁定**"这一状态结束、**决定已做**（复核裁决 §1 定为"必须支持"）—— **不等于能力已实现**；能力状态仍是"已定、实施/验收未完"，交账与状态文档**不得**写成跨地点复用已完成。可拆工程子批次，但属本轮 D-032 整体范围，不得无限挂起后再宣称整体完成。
- §10 其余开放项不变；效率/经验精度相关的旧取舍（§12.2 第 5/6 项）**已作废**，不得再作为实现依据。

### 13.7 阶段 2 交接复核裁决五条（用户裁定，2026-10-17；覆盖 §13 冲突处）

> 源：`docs/superpowers/specs/2026-10-17-d032-stage2-handoff-review-decision.md`（用户授权"出裁决"）。**A 批 owner 施工中无法中途寻址**，故这五条由控制方在 A 批交账时逐条复核兑现，未兑现的进 B 批。

1. **批量执行不能只靠固定遍历顺序** ⇒ §13.2 第 1 条①（同 tick 统一生效条件 + 按家户×经验条归并结转；先后次序不得改变另一实例本 tick 的熟练度）。
2. **契约故障必须停止并撤销整个候选提交** ⇒ §13.2 第 1 条④；"不许短路"**不得**被读成"吞掉错误继续执行"。
3. **允许回执携带不可变实例快照** ⇒ §13.2 第 1 条③（禁止的是暴露可写内部对象与让 economy 另改生产状态，不是禁止返回不可变事实）。
4. **`runtime=null` 就抛的旧构造器只准施工期暂留** ⇒ §13.3 第 2 条（B 批收口）。
5. **允许串行化**，逐值一致比较**同一套新规则下**的串行 vs 确定性归并并行，**不得**要求新公式复现旧错值 ⇒ §13.2 第 3 条。

- ★ **状态措辞铁律**：关闭待裁定项 ≠ 能力已实现。凡"决定已做"的条目，交账一律写"已定、实施/验收未完"，除非有真实运行证据。
- **测试 owner 新增判据**（复核 §5 末）：同户多实例**调整遍历次序无额外熟练度收益**；同 tick 重复推进被拒；单次跨多日与拆段推进一致；批量中途故障**无半提交**；不可变回执无法越权改权威；无效旧构造器 / 越界 import / 旧写入口扫描。**A 批语义同样要最终测试**，不因先编译通过而免检。

### 13.8 A 批两条提问的裁定（控制方，2026-10-17；A 批交账后）

**裁定一（数值口径）：取 (b) —— 熟练度倍率按复核 §3 原句"资源约束后的基准总产出 100 再应用 1.5 倍效率则为 150"落形，A 批现状不算通过。**

- `TOTAL`（**默认**）：三通道**都不乘 E** 求基准规模 `baseScale = min(劳动规模, 资产规模, 原料规模)`；再 `finalScale = ⌊baseScale × E_cycle ÷ 1000⌋`。**投入消耗与占用按 `baseScale`** —— 同样的投入产出更多，这正是"效率"本义，也不凭空多占土地/工具/原料（复核 §3 末句）。
- `E_cycle` = **按实际劳动贡献加权的当 tick 生效值累计**（`CycleContribution` 已有 `laborMilli` / `effectiveLaborMilli` ⇒ `E_cycle = ΣeffectiveLabor ÷ Σlabor`，即既有 `efficiencyPerMille()` 口径）；零劳动 / 除零 / 无经验的退化口径必须写明（与"缺事实 fail-closed"分列）。
- ★ **资产通道不得在 TOTAL 下单独乘 E**：现状"劳动通道乘 E + 资产通道乘 E + 原料通道物理实扣 + min"在**原料主导**时加成归零，正是复核 §3 反例换了一条通道；结构不对称是它的症状，不是问题本身。
- `LABOR`（**必须显式声明**）：保持"只折算劳动通道后再 min"的局部模型（复核 §3 允许"方案若明确配置某个作用作用于劳动效率，可按其声明计算"），其后果（瓶颈在别处时加成可能为 0）写进枚举与方案文档，**不得**当作默认。
- 试算 / 实际 / 家户利润比较**共用同一入口**（`ProductionEfficiency`）。
- **证据要求**：修正后必须给**手算样例**（例：劳动规模 100、资产规模 200、原料规模 120、E=1500 ⇒ TOTAL 下 finalScale=150；同例 LABOR 下 =120）写进账本供测试 owner 定点；**仍不许**把"手算/算术论证"写成"验证通过"。

**裁定二（合规）：`ProductionLocation.UNSPECIFIED` 接受为施工期显式占位，附四条硬条件。**

1. 凡**地点有语义**之处（键解析、实例 ID 生成、跨表引用守卫、播种、读路径）读到 UNSPECIFIED ⇒ **具名拒绝或契约故障 + INFO 日志**，**严禁**静默跳过或当合法地址用。
2. **不得进入任何持久化键**（`isSpecified()==false` 不参与键解析必须由代码保证，不是约定）。
3. **B 批收口**：给出 7 处构点的**逐点处置表**（各自真实地点来源）；B 批结束时 UNSPECIFIED 只允许留在"尚未创建实例的纯定义侧"，否则删除；**不得**成为长期兼容位。
4. 旧世界/夹具若解不出地点 ⇒ 按 Alpha **当场失败**（旧档不兼容不是缺陷），**不许**为了"让它起得来"补默认地点；测试 owner 把"UNSPECIFIED 不得静默运行"列为判据。

**另两条 B 批结转（A 批自报未做，裁定维持为必修）**：

- ★ `beginTick` 取代 `advanceDay` 成为 tick 驱动口（A 批已删 `advanceDay`）：B 批按"每 tick 一次、先衰减再冻结条件"接线，并保留同 tick 重复/回退/未开启即衰减的**具名契约故障**（§13.3 第 4 条原写 `advanceDay()`，按此更名为 `beginTick`）。
- ★ 经验增量**按家户×经验条归并并结转精度**（§13.7 第 1 条 / 复核 §2.1）——A 批自报 `effectiveLabor` 余数仍是"每实例一份"（同一实例多户出工时口径偏松），**A′ 批补做**。
- 顺手清理（控制方复核发现的两处陈旧引用）：`ProductionLogSource.java:23` 仍写 `advanceDay`；`EconomyCodec.java:1456` 的 Javadoc 仍指向已删的 `EconomyData.hasLegacyProductionBits`。

### 13.9 A′ 批两条提问的裁定（控制方，2026-10-17）

**裁定三（偏离：不加逐键余数表）→ 接受。**

- 理由：结转的**唯一权威**就该是家户×经验条经验账上的余数（`ProductionExperienceFeature.remainder`，键 = 家户×经验条，已在状态/ChangeSet/codec/快照/往返面内）；另建同键表 = 第二份口径，硬约束明禁。A′ 改为**删掉逐实例的折算余数**（`PerCycleCarry.tickLaborRemainderMilli` + `ProductionData.effectiveLaborRemainderOf/withEffectiveLaborRemainder`），在精确累计下没有可丢零头 ⇒ **状态面净减**，比净增表更好。
- 保留条件：① `PerCycleCarry` 只剩**实例级周期截断余数**（`laborDayRemainderMilli` / `laborScaleRemainderMilli` / `efficiencyRemainderMilli`），与家户级余数**键不同、语义不同，不得互相复制**；② 状态净减必须在 ChangeSet / codec / 快照 / 往返守卫登记面同步（A′ 已做只读复核，最终由测试 owner 的 V10 验）；③ 残留的两处 `tickLaborRemainderMilli` 字样（`PerCycleCarry.java:20`、`ProductionEfficiencyState.java:20`）控制方核过**只是解释性 Javadoc**，不算漏删。

**裁定四（口径错误）→ 不接受"LABOR 下 `laborRequiredMilli` 可大于可用劳动"。**

- `probe` 的 `*Required` 是**经济侧分配用的口径**：它必须是"达到该规模**实际要投入的量**"。`LABOR` 声明下加成发生在劳动通道**之内**（`baseScale` 已含 E 折算），所以原始劳动需求必须按 E 折回 —— `laborRequiredMilli = ⌈baseScale × laborPerUnit × 1000 ÷ E_cycle⌉`（取整口径写明、向上取整以免欠投）；`TOTAL` 下维持 `baseScale × laborPerUnit`（加成作用于产出，不减少投入）。
- ★ 由此立一条**契约不变式**（进测试判据）：**凡 `probe` 判可行，`laborRequiredMilli ≤ 该家户可用劳动`**。现状在 `LABOR` 下会报出比可用劳动更大的需求 ⇒ 经济侧会误判不可行或超发劳动，必须修。
- `TOTAL` 下 `assetRequired` / `inputRequired` 按 `baseScale`、产出按 `finalScale`、`probe.scale` = 产出规模、`grantedScale` = 基准规模上限 —— **接受**（语义写清即可）。
- 本条修在 **B1 批**（它正是消费这些读数的地方）；不得靠"另给一个有效劳动读数字段"绕过。

### 13.10 B1 批复核、两处更正与 B2/B3 范围（控制方，2026-10-17）

#### 13.10.1 B1 批实收

- 真实 rc=0（实现方做的是**强制重编**：删了 4 个模块 `target/` 后 11 个模块真编译；控制方复跑增量 rc=0）。
- 做到：`beginTick` 接两条推进路径 · 日推进改走 `executeDay`（= 逐步调同一个 `execute`，业务拒绝零残留、契约故障终止整批）· 四处权威就地写归零 · `units()` 改真只读投影 · economy 不再 import production 非 contract 类（扫描 0）· `laborRequiredMilli` LABOR 折回 + 不变式 · `changeSet()` 非空且与 ACTOR 同一条 `moduleChanges`。
- 未做到：`probe` 未进驱动路径 · 四组件未删（只做到"半收口"，且它**已 `git checkout --` 还原**尝试过的整批删除，未留全链红）· `ProductionCandidate`/`templateFor` 未删（BLOCKED）· 守卫未改 · 播种未改 · 串行未做。

#### 13.10.2 更正一（控制方验收错误，必须留痕）：铸币权威**未**收口

- 事实（控制方 2026-10-17 回代码核过）：`MintSettlement.java:238-247` 仍 `boolean policyOverride = base.economyPolicyConfigured(...); long requested = scale × (policyOverride ? policyMilliPerFurnaceCycle : line.getValue())`；`EconomyPolicyKey.java:54` 仍定义 `MINT_MILLI_PER_FURNACE_CYCLE(500L,…)`；`grep -rn MINT_TECH_RATE_POLICY_RETIRED simos-*/src/main` = **0**。
- ⇒ `stage2-acceptance.md` §2 的"铸币收口"行、`A-batch-acceptance.md` §3 的"两处失效键提示"结论、以及本节早先 §13.1 的同一记载 **全部作废**；已在这三处加更正行。
- **控制方失误定性**：把实现方台账里的自述字符串 + 一段"描述旧口径"的注释当成了"行为已退役"，**没有 grep 行为符号**（覆盖分支的读取点）。后续验收一律以**行为读取点**为准，不以自述/注释为准。
- I12 仍是**未实现项**，列入 B2（见 13.10.4 第 6 条）。

#### 13.10.3 裁定五（`ProductionCandidate` + `templateFor` 的承接形状）—— 按"契约口 + 复用既有登记面"删除

- 现成事实：`production.spi.DefinitionRegistration`（P1 起）= 按需产业定义的**唯一登记投影**（合并/冲突规则在 production，app 两个日循环只转接）；production 另有 GM 定义写面 `spi/UpsertIndustryHandler` + `tools/write/EconomyUpsertIndustryTool`；`ProductionRuntime` 已有 `createInstance(...)`。
- ⇒ **删 `ProductionCandidate` / `EconomyEntrySettlement.templateFor` / `EconomyData.candidates` / `EconomyRegisterCandidateHandler` / `EconomyAdjustTool` 的 `upsertCandidate` kind**；`ApiViews` 的候选读口改读 production 定义。用户 U8 的三件事由**两个既有面**承接：选用已有方案 ⇒ `createInstance`；预计/实际产出与家户状态更新 ⇒ `probe`/`execute`。
- **"按需新建定义"**：在 `ProductionRuntime` 增补**一个**契约口（如 `registerPlan(IndustryDefinitionView)`），实现内部**复用 `DefinitionRegistration`** 的合并/冲突规则（不得另写一套合并）；economy 在"选用的方案定义尚不存在"时调它；app 现有直连 `DefinitionRegistration` 的用途（跑商 `trade` 等）保留。
- 判据：删后 `templateFor`/第二定义面扫描 **0 命中**，且"家户选用某生产方式 + 预计产出 + 实际产出与家户状态更新"三件事仍接通（测试 owner 定点）。

#### 13.10.4 B2 批范围（本批派单）

1. **`probe` 进驱动/决策路径**：分配前对候选做**无副作用**试算（家户条件 + 外部属性事实），economy 用试算结果排序/分配；价格与需求**不进** probe（§4.2）。
2. **删 economy 四组件**（B1 只做到半收口）：`units`/`productionEfficiency`/`outputQuantityOverrides`/`candidates` + 旧 `ProductionEfficiencyBook` + 旧 `ProductionEfficiencyState` + ChangeSet/codec/便捷构造器收口；`unitsWorkCopy` 写侧退役。
3. **删 `ProductionCandidate` + `templateFor`**（按 13.10.3 的形状）。
4. **守卫改经只读投影**（一条都不许删）。
5. **播种路径改由 production 承接**（`EconomySeeder` 等）。
6. **铸币权威真收口（I12 / 更正一）**：`MintSettlement` 不再读政策键，改读 production 有效参数；**GM 可调生产率的能力必须被承接**（迁到 production 的定义/覆盖写入口，工具提示与 `CatalogTool`/`EconomySetEconomyPolicyTool` 同步改指向）；若该写入口按计划留后续批次，则明写"能力待后续"，**不得**先删提示/先删键。
7. 目标 **rc=0（强制重编）**；每改一处契约立刻重编到 rc=0。

#### 13.10.5 B3 批范围（B2 之后）

`drawCycleInputsPartitioned` 降串行（若仍并行）· app 读路径语义收口（`ApiViews`/`ToolSupport`/`BindingRegistry`/访问作用域）· I9 回执消费（工资/租金/分成按实际贡献）· I10 迁移/合户经验（含余数）· `UNSPECIFIED` 逐点处置表 · 旧 `runtime=null` 构造器收口 · `ProductionRuntime` 其它实现类扫描 · 两处陈旧 Javadoc（`ProductionLogSource:23`、`EconomyCodec:1456`）。

### 13.11 B2 批复核、三条裁定与 B2b/B3 范围（控制方，2026-10-17）

#### 13.11.1 B2 实收（控制方独立复核）

- 真实 rc=0：实现方**强制重编**（删 4 模块 `target/`，11 模块真编译 **1584 文件**，非 "Nothing to compile"）；控制方复跑增量 rc=0。
- 做到：**`probe` 进决策路径**（唯一调用点 `EconomyEntrySettlement:788`，入参只有方案 ID + 家户条件 + 资产占用 + 余数 + 属性事实，一个价都不读；可行性/规模/劳动与投入需求/预计产出全取 probe）· **守卫改经只读投影且一条未删**（新落点 `EconomyReferenceValidation.validateProductionInstances:79-140`）· **铸币真收口**（控制方核：`MintSettlement` 对 `economyPolicyOf/Configured` **0 命中**，唯一来源 = `industry.moneyOutputPerUnit`；该政策键改具名拒 `mint-rate-moved-to-production-definition` 并指路 `production.UpsertIndustry`）· `templateFor` **0 命中** · `registerPlan` 复用 `DefinitionRegistration.registerCreated`（1 调用点）。
- 未做到：**四组件未删**（B2 尝试整批删净 → 编译暴露 `LegacyHouseholdMigration` 卡点 → **文件内容还原**（非 `git checkout --`）+ 复跑证明回绿，未留全链红）· 候选类型面/handler/`upsertCandidate` kind/`ApiViews` 读口未收口 · 创世实例仍写 economy `units`（`EconomySeeder:4108/:4188/:4429/:4487/:4566`）· `unitsWorkCopy` 写侧未退役。
- ★ 控制方核实的一个**良性结论**：`unitsWorkCopy()/flushUnitWorkCopy()`（`EconomyStateBuilder:368/404`）**不是第二权威** —— 它把净变化经 `createInstance`/`removeInstance`/`reoperateInstance` **推回 production**，进度/台账/效率余数一律不由它写，且推完清零。但按复核 §3「B 批结束必须删除 `unitsWorkCopy` 等经济侧写回路径」⇒ **仍须退役**（见 13.11.4 第 4 条）。
- ★ 实现方**顺手修掉一个 B1 遗留、编译抓不到、只有长跑才会炸**的运行期缺陷：`ModeMigrationSettlement` 在只读投影上 `remove`/`put`（会抛 `UnsupportedOperationException`）。已记功。

#### 13.11.2 裁定六（D-1：四组件删不掉的卡点）→ 按"先去 units 化、再删组件线"执行

- 卡点为真：`LegacyHouseholdMigration` 整个 API 建在 `units` 上（6 签名 + 10 实现），且由 `EconomyData` 的 compact 构造器在**所有守卫之前**自动跑。
- **裁定**：① 先给 `LegacyHouseholdMigration` **去 units 化** —— 它今天只为"旧档补 `assetShares`/`household`"服务，而 Alpha 明令**旧档一律作废**（§一.11）⇒ 该自动迁移段**收窄或整段删除**，不得为了保住它而保留组件线；② `candidates` 与 `units` 同属一条 38 参构造面 ⇒ **同批删净**；③ 接受"`EconomyCodec` 对 `units`/`productionEfficiency` 只有 Javadoc 引用、删组件后 codec 无需改"这一核实。

#### 13.11.3 裁定七（D-2 三处语义选择）

1. **接受**：`registerPlan` 返回新类型 `ProductionPlanOutcome`（不复用 `ProductionInstanceOutcome`，避免向下转换）。
2. **接受契约故障口径，但必须先消除取整伪阳**：`probe` 判可行却 `laborRequiredMilli > 可用劳动` 确实**只能是 production 的 LABOR 折回口径写错**（不是世界里的业务规则）⇒ 属契约故障 ERROR 不降级。但 `laborRequiredMilli` 用**向上取整**，而 `baseScale` 用**向下取整** ⇒ 恰好在可用劳动临界点上可能出现**取整造成的 1 单位伪阳**，那会把一个合法 tick 整批打成 ERROR。**要求**：把"向上取整的余量"**显式量化**为契约文档里的 slack，`required ≤ 可用 + slack` 内视为满足（并把 required 钳到可用），**超出 slack 才抛契约故障**；账本里给一个**临界点手算样例**（可用劳动恰好等于需求）。
3. **接受**：铸币键改**具名拒**（而非"保留键、无人读"——后者正是 §13.10.2 那次静默失效的形态）。同时记录：**"运行中产业原地改产出率"的便捷入口属 U10 后续未做**，拒绝理由已具名，**不得**写成已交付。

#### 13.11.4 B2b 批范围（本批派单）

1. **`LegacyHouseholdMigration` 去 units 化**（Alpha：旧档作废 ⇒ 收窄/删除该自动迁移段），为组件线删除清路。
2. **删净四组件**：`EconomyData.units`/`productionEfficiency`/`outputQuantityOverrides`/`candidates` + 旧 `ProductionEfficiencyBook` + 旧 `model.ProductionEfficiencyState` + ChangeSet/apply/37–38 参便捷构造器 + 全部调用点。
3. **候选面彻底退役**：`ProductionCandidate` 类型、`EconomyRegisterCandidateHandler`、`EconomyAdjustTool` 的 `upsertCandidate` kind、`ApiViews` 候选读口（改读 production 定义）。
4. **退役 `unitsWorkCopy`/`flushUnitWorkCopy`/基线键集**：三类建档写者（进入执行/自动组织/模式变迁/迁移搬迁）改**直接调契约**（`createInstance`/`removeInstance`/`reoperateInstance`），不留经济侧写回路径。
5. **创世实例改由 production 承接**（`EconomySeeder` 五处写点）。
6. **取整 slack**（13.11.3 第 2 条）。
7. **rc=0 强制重编**（删 4 模块 `target/` 后 11 模块真编译）。

#### 13.11.5 性能记录（交测试 owner 长跑盯）

`probe` 现在位于 `planEntries` 的 **O(家户 × 候选)** 双循环内，**性能面完全未测**；实现方**刻意不加缓存**（缓存 = 候选态陈旧视图，与"同源"冲突）。若长跑显示热点，可按"`beginTick` 已冻结当 tick 熟练度"做**记账式**缓存 —— 但必须仍然同源、且不得跨 revision 陈旧。

### 13.12 B2b 批复核、裁定八/九/十与 B2c 范围（控制方，2026-10-17）

#### 13.12.1 B2b 实收（控制方复核）

- 真实 rc=0（强制重编 11 模块 1155 文件）；控制方复跑 rc=0（作业退出码 1 是 `grep -c` 命中 0 行的假信号）。
- **真删**：`LegacyHouseholdMigration` 整类 + 构造期调用段（旧档判据落到守卫 fail-closed）· `economy.model.ProductionEfficiencyState` 与 `time.ProductionEfficiencyBook` 整类 · `EconomyData` 的 `units`/`outputQuantityOverrides`/`productionEfficiency` 三组件（记录 37→33）· 同名 ChangeSet 维与 `with*` · 4 个便捷构造器 · **`unitsWorkCopy`/`flushUnitWorkCopy`/基线键集整体退役**（四类建档写者直调 `createInstance`/`removeInstance`/`reoperateInstance`）· `EconomyGmAdjustments` 的产出覆盖腿。
- **新面**：`production.contract.ProductionStateLookup` · `EconomyProductionDefinitions.stateOf(state)` · `EconomyReferenceValidation.validateUnitCrossReferences`（旧构造期交叉守卫的**新落点，一条未删**）· `production.Seed.instances[]`（创世实例归 production，economy 载荷 `units[]` 降级为 relation 声明并与 production 实例逐值核对）· 取整 slack（`ProductionProbeResult.LABOR_REQUIRED_ROUNDING_SLACK_MILLI=1L` + 钳制）。
- ★ 它又修掉**两处 B1 遗留的"写只读投影"运行期炸点**（`EconomyEnterpriseSettlement`/`EconomyModeTransitionSettlement`）——编译抓不到、只有长跑会炸的那一类。
- ★ **控制方核实"`git show HEAD:` 还原是否吃掉 P1 改动"**：`ProductionCandidate.java` 在 P1 格式化的 before/after md5 **相同**（`2544219102908ec492cd88634940bf57`）且与当刻一致；两文件相对 HEAD 均 clean ⇒ **本次还原是内容无损的**（结论只对这两个文件成立；其他批次仍禁用 `git checkout --`/HEAD 还原）。

#### 13.12.2 裁定八（候选五字段的落点；★ 控制方工程裁定，用户可推翻）

依据用户 D4「D4 归生产方式模块…经济模块调用生产方式模块之后，负责计算随后的家户间经济关系」+ §4.1（方案数值只在 production 一份）：

| 旧字段 | 落点 | 理由 |
|---|---|---|
| `version`（`id@version` 版本键） | **删** | 方案身份 = 稳定 `IndustryId`（A 批已定不绑地点）；"修订" = 经 `registerPlan` 更新定义（`DefinitionRegistration` 已有"同 id 不同值 ⇒ 冲突"规则）。"同 id 多版本并存"的玩法**本批不做** |
| `output`（主产出，且必须出现在产出表里） | **删概念**，改为 economy **按商品逐项处理** | 定义已是 `Map<CommodityId,Long> outputPerUnit` 多商品表；每个产出商品各自进入需求匹配与取价，家户利润 = Σ产出×市价 − Σ投入×市价。★ 退化情形必须逐值不变：**单产出方案的算法结果与旧口径一致**。将来若要"以哪个商品为主"，属 U10 的 GM 配置面 |
| `buildDays`（建设期） | **搬进 production 定义**（`IndustryDefinitionView` 现无该组件 ⇒ 新增，若 payload 已有等价字段则复用，不得造第二个） | 建设期是方案属性 |
| `acceptedRightKinds`（OWNED/TENANCY/COMMUNAL） | **搬进 production 定义**（新增组件；economy 分配时按定义过滤） | 方案声明的资产接受面 |
| `assetSource`（闲置份额来源主体） | **留 economy** | "谁提供闲置份额"是经济关系，进 economy 的资产分配（执行入参 `assetOccupancy` 的来源选择） |
| `outputPerUnit` / `cycleDays` / `slots` / `allocation` / `moneyOutputPerUnit` | 定义已有，无需搬 | —— |

- 新增组件必须走全套登记面（状态 / ChangeSet / codec / 快照 / 往返守卫），铁律 5。
- 判据：`ProductionCandidate` / `EconomyRegisterCandidateHandler` / `upsertCandidate` kind / `ApiViews` 候选读口 **0 命中**；U8 三件事仍接通；**单产出方案的入口算法逐值不变**（测试 owner 定点）。

#### 13.12.3 裁定九（清区）→ 加宽既有命令，同一命令内先退役实例再删定义

- 不新开命令：在既有清区路径内，**同一命令/同一批**里先 `removeInstance` 退役该区实例、再删定义；批序在 `RegionClearPlan` 里保证顺序；退役数量进具名读数。
- 理由：定义删不掉是死路（B2b 现场：有实例时定义删不掉，只有具名 INFO）；"清区"的语义本就包含清掉该区的生产。

#### 13.12.4 裁定十（`upsertGovUnit` 与新 GOV 的 office 实例）→ 具名拒保留，修工具

- 具名拒 `OFFICE_UNIT_NOT_IN_PRODUCTION` **正确**（fail-closed：不许静默建无实例的 office）。
- 修**工具**：`simos.economy.upsertGovUnit` 须在**同一批**内经 production 契约为新 GOV 建 office 实例（`createInstance`），`GovWorldBootstrap` 补实例段。**不得**放宽拒因来"修好"。

#### 13.12.5 其余两条

- `EconomyCodec` 旧档 reshape **保留**：接受（Alpha：旧档 fail-closed，读不回来不是缺陷）。
- `HouseholdIds.pendingLegacy/isPending` 已无生产调用点 ⇒ **清理**。

#### 13.12.6 B2c 批范围（本批派单）

1. **候选面彻底退役**（按裁定八）+ 定义新增 `buildDays`/`acceptedRightKinds` 两组件（全套登记）。
2. **清区加宽**（裁定九）。
3. **`upsertGovUnit` 批内建实例 + `GovWorldBootstrap` 补实例段**（裁定十）。
4. **清理**：`HouseholdIds.pendingLegacy/isPending`。
5. **rc=0 强制重编**（删 4 模块 `target/` 后 11 模块真编译）。

### 13.13 B2c 批复核、裁定十一~十四与 B3/B4 范围（控制方，2026-10-17）

#### 13.13.1 B2c 实收（控制方复核）

- 真实 rc=0（强制重编 11 模块 1156 文件、19×"0 Checkstyle violations"）；控制方复跑 rc=0。
- 候选面**真删**：`ProductionCandidate` / `EconomyRegisterCandidateHandler` / `CandidateId` 三个类型文件已不存在（控制方 `ls` 核过）· `EconomyData.candidates` 组件与校验段删除（残留 3 处为注释）· `EconomyChangeSet.candidates` 维 · `upsertCandidate` 投影改具名拒（白名单 12→9）· `OwnershipStake.RightKind` 上移 · `pendingLegacy/isPending` 清理。
- 裁定八落点核实：`IndustryDefinitionView.buildDays`(`:70`)、`acceptedRightKinds`(`:82`) 已在（类注写明来自候选搬迁）；`EconomyMeta.planAssetSources`(`:51`) 已在；`production.CreateInstance` 命令面在 `ProductionCommandTypes`/`CreateInstanceHandler`，且已在 `Shell:920`、`CatalogTool:951`、`GovWorldBootstrap:566` 三处登记。
- U8 三件事代码位置：`plans():220` → `planDefinitionFor:777` → `registerPlan:1133`/`createInstance:1138` → `probe:562` → `executeDay:4274` → 读口 `ApiViews:4923`。
- ★ 施工事故（如实记录，接受）：一次脚本化删除误删 `EconomyGmAdjustments` 165 行助手块，**用仓库自身 diff 取内容插回**（未用 `git checkout --`/`git show HEAD:`），编译与调用点类型检查通过。

#### 13.13.2 裁定十一（`assetSource` 写面）→ 新开一个**最小 economy 声明命令**

- 取 (a)：在 economy 开一个最小声明命令（设置/清除某方案的闲置份额来源主体），写 `EconomyMeta.planAssetSources`；**不做**版本、不做窗口。
- 理由：它是**经济关系**（谁提供闲置份额），归 economy；而 (b)"并入 `production.UpsertIndustry` 双写"= production 的命令写 economy 状态，违反铁律 3/I1；(c)"认可退役"= 让一项能力因删了旧写面而静默消失，与复核 §5 的口径冲突。
- 空表 ⇒ 只认自有份额（= 旧缺省逐值行为）必须保持。

#### 13.13.3 裁定十二（`laborSource`）→ **逐值不变**，不得顺手接制度派生

- 本批是**迁移**批次，不是改经济规则的批次（I14：定价/分配/市场/债务/人口/财债公式逐值不变）。
- ⇒ 保持旧口径：**SELF** 为缺省；若世界/播种对某方案显式声明了非 SELF，把该声明搬进 economy 的声明面（**与裁定十一同一批**）。
- `RegimeRelations.laborSourceFor(regime)` 的接法**记为"提议的行为变更"**（因为它会改变后续 `LaborSourcePolicy`/`HouseholdClassRule` 的取值），要单独立项 + 数值验收，**不在本批**。

#### 13.13.4 裁定十三（清区运行期缺口）→ 单列 **B4 批**，并记为 **D-031 P1 遗留缺口**

- 事实（控制方核过）：`ProductionConsumerPort` 在 `simos-*/src/main` 只有 production 自己引用（tool/handler/spi），**组合根无实现** ⇒ `production.ClearRegionDefinitions` 与 `economy.UpsertIndustryTool` 运行期抛装配故障；其 `IndustryReferenceCounts.units` 一维在 B2b 后已陈旧。
- 裁定：**单列 B4「P1 遗留缺口修复批」**，在 B3 之后、测试 owner 之前：① 组合根落地 `ProductionConsumerPort` 实现；② 引用计数（尤其 `units` 维）按 **B2b 后的新权威**重算（实例引用读 production 投影）；③ 交账给"清区在有实例的区上跑得通"的**装配位置证据**。
- ★ 性质：**D-031 P1 既有缺口，不是 D-032 回归**；但"GM 命令运行期不可用"不能无限挂起。测试 owner 判据里加"清区在有实例的区上跑通"。

#### 13.13.5 裁定十四（`production.CreateInstance` 命令）→ **保留**

- 理由：GOV office 建档需要一条**经 ChangeSet** 的写入口（铁律 2）；handler 不自己写实例表、复用 `ProductionRuntimeEngine.createInstance` **同一实现**（不产生第二写路径）；命令面与契约面同源。三处登记（Shell/CatalogTool/GovWorldBootstrap）已齐。
- 要求：权限/白名单/目录三面按既有 GM 命令规范保持同步（测试 owner 的 `McpCoverageTest` 一类会盯）。

#### 13.13.6 Spotless 欠账（B2b/B2c 自报）→ B3 **第一步**补齐

- 事实：B2b/B2c 新增 import 后**未跑 Spotless** ⇒ 全文件格式可能与 `spotless:check` 不一致，`clean verify` 会红。
- 裁定：B3 开工**第一步**对触及模块做格式化补齐（`spotless:apply`，**只动格式**），账本给出"改动文件/行数"清单以证明只动格式；`verify` 其余门禁（Checkstyle/SpotBugs/前端/`test`）仍归测试 owner。

#### 13.13.7 B3 批范围（最后一个实现批）

1. **Spotless 格式补齐**（只动格式，13.13.6）。
2. **裁定十一的最小声明命令** + **裁定十二的 laborSource 逐值不变**（含显式声明的搬迁，同批）。
3. `drawCycleInputsPartitioned` 降串行（若仍并行；逐值按**同一套新规则**比较）。
4. **app 读路径语义收口**（`ApiViews`/`ToolSupport`/`BindingRegistry`/访问作用域）。
5. **I9 回执消费**：工资/租金/分成按回执里的**实际出工家户与贡献**计算。
6. **I10 迁移/合户经验**：完整携带（含余数）、合户按人口加权平均；财债按比例切分逐项相加。
7. **`UNSPECIFIED` 逐点处置表**（§13.8 裁定二第 3 条）。
8. **旧 `runtime=null` 构造器收口**（§13.3 第 2 条：B 批结束必须删无实际职责的旧重载）。
9. **`ProductionRuntime` 其它实现类扫描**（A2 遗留）。
10. 两处陈旧 Javadoc（`ProductionLogSource:23`、`EconomyCodec:1456`）。
11. **rc=0 强制重编**。

#### 13.13.8 B4 批范围

`ProductionConsumerPort` 组合根实现 + 引用计数按新权威重算 + 清区/`upsertIndustry` 运行期可用（裁定十三）。完成后即交独立测试 owner（V1–V12 + 全过程新增判据）。

### 13.14 B3 批复核、裁定十五~十七与 B4 范围（控制方，2026-10-17）

#### 13.14.1 B3 实收（控制方复核）

- 实现方 rc=0（强制重编 11 模块 1158 文件、19×"0 Checkstyle violations"）+ 全反应堆 `spotless:check` rc=0；**控制方独立复跑**：`compile` rc=0（19×SUCCESS / 0 ERROR）+ `spotless:check` **rc=0** ⇒ 格式欠账关闭。
- 十一条逐条成立（控制方抽查）：`ofPlanOrUnspecified` main **4 处命中全是"已删除"的注释**（代码 0）· `runtime == null` main **0** · `implements ProductionRuntime` **1**（`ProductionRuntimeEngine`，final）· `mergeExperience` 接线 **1** 个调用点 · `SetPlanSources` 同时写 `planAssetSources` / `planLaborSources` 两个键（不新增持久组件）· 序列化点 `EconomySettlement:3877-3880` 池参数改 `null`（只动并行度）。
- ★ 它自报一处**未做逐字节自证**的格式化：收尾第二次 `spotless:apply`（18 文件，全是本批自己改过的文件）**无前像**，只有"幂等（再跑 0 变更）+ check/compile 全绿"三条旁证 ⇒ **接受并留痕**（控制方的 `spotless:check` rc=0 是独立旁证）。

#### 13.14.2 裁定十五（`EconomyData.industries`）→ 列入 **B4**，它是 **I1 的最后残留**

- 事实（控制方核过）：`EconomyData.industries` 仍在（`:307`），仍被播种/载荷物化，并被**四条构造期腿**读取：`:798`（flows 守卫）· `:804`（actor 撞名）· `:917`（assetShares→industry）· `:1035`（HEX 需求）。
- 裁定：**删**。这是 economy 侧"方案定义副本"的最后一份（I1/I13：不得持久化可独立修改的第二份权威）。步骤：① 停止播种/载荷对它的物化；② 四条构造期腿改走**已存在**的 state-aware 守卫（`EconomyReferenceValidation` 四条等价判据）；③ 交账给"播种仍能建世界 + 守卫仍生效"的代码位置证据。
- 允许的替代（仅在上述步骤确有不可解耦合时）：把四条腿的判据**逐条平移到 state-aware 守卫**并写明"构造期检查被 state-aware 检查取代"，**不得**只是删掉守卫。

#### 13.14.3 裁定十六（`planLaborSources` 声明面）→ **保留**（并入同一命令，已完成）

- 它与 `planAssetSources` 一起由 `economy.SetPlanSources` 写（两个键、同一命令、不做版本/窗口），读点缺键 ⇒ `SELF` ⇒ **逐值等于改动前**。理由同裁定十一：能力不得因删旧写面而静默消失。

#### 13.14.4 裁定十七（其余结转项）

- `MarketSettlement` 生活保留额仍读 `laborOfCohort`：**不改**（不同规则族，不在 I9 范围）。若将来要与 I9 对齐 ⇒ 单独立项 + 数值验收。
- `assetSource` 借用腿**数值未跑** ⇒ 列入测试 owner 定点判据（需要真实场景：有闲置份额来源主体的方案 + 借用发生）。
- I10 的财债按比例切分 = D-023/W9 **既有实现**（本批只核未改、未跑）⇒ 测试 owner 连同 V6 一起验。
- `ClearRegionDefinitionsHandler:126` 的 `instancesSkippedUnknownLocation` 兜底在 B3 后**不可构造** ⇒ B4 清掉。

#### 13.14.5 B4 批范围（最后一个实现批）

1. **`ProductionConsumerPort` 组合根实现**（裁定十三）：引用计数（尤其 `IndustryReferenceCounts.units` 维）按 **B2b 后的新权威**重算（实例引用读 production 投影）；`production.ClearRegionDefinitions` 与 `economy.UpsertIndustryTool` 运行期可用；交账给**装配位置证据**。
2. **删 `EconomyData.industries`**（裁定十五，含四条构造期腿的平移）。
3. 清理 `instancesSkippedUnknownLocation` 死兜底（裁定十七）。
4. **rc=0 强制重编** + **全反应堆 `spotless:check` rc=0**。
5. 交账后 → **独立测试 owner**（V1–V12 + 全批次新增判据 + 变异自证 + `clean verify`）。

### 13.15 B4 批复核、裁定十八~二十与 B5 范围（控制方，2026-10-17）

#### 13.15.1 B4 实收（控制方复核）

- 实现方 rc=0（强制重编 11 模块 1160 文件、19×"0 Checkstyle violations"）+ 全反应堆 `spotless:check` rc=0；**控制方独立复跑两者**：`compile` rc=0、`spotless:check` rc=0。
- 五条成立（控制方抽查）：`EconomyData.industries` 组件与 `withIndustries` **已不存在**（`EconomyData` 里 `Map<IndustryId, IndustryDefinitionView> industries` 0 命中）· `ProductionSnapshots.consumerPort` 已删（只剩一句说明注释）· `AppProductionConsumerPort` 与**7 个装配点**在位（`Shell:659/923/925/926/930`、`SimosToolSource:1745`、`GovWorldBootstrap:503`、`ThreePowersGovBootstrap:640`），handler/工具构造期 `requireNonNull` ⇒ 漏注入在**启动期**炸 · **清区问的是"退役后只读候选态"**（`stagedState`，`:121`/`:159`）。
- ★ 记功：它**自查发现并修掉一处真缺陷** —— 清区原问"退役**前**的 state"取引用计数 ⇒ 实例还在 ⇒ 定义永远删不掉（裁定九要修的死路会回来）。
- ★ 接受并留痕：收尾 `spotless:apply` 改了 17 个文件（全部 ⊆ 本批 30 个触达文件）但**未做逐字节"只动格式"自证**；控制方的 `spotless:check` rc=0 为独立旁证。

#### 13.15.2 裁定十八（`EconomyData.modes`）→ **B5 收口，它是 I1 的最后一个第二权威**

- 事实（控制方核过）：`EconomyData.modes`(`:334`) 仍在，且**仍有写路径** —— `EconomyGmAdjustments:438`/`:529` 调 `base.withModes(modes)`；`EconomyBoundaryView.modes()` 刻意读 economy 这份表（与 `industries()` 读 production 定义**不对称**）。
- 裁定：**删**。步骤：① 先核"economy 的 mode 写腿对应哪条 GM 命令、播种/世界是否用过"；② 若从未被用过 ⇒ 连命令 kind 一起删（GM 改走 production 既有 `UpsertProductionMode`/`DeactivateProductionMode`，两者已在 `Shell:925/:926` 装配）；③ 若被用过 ⇒ 该能力**平移**到 production 命令面（不是退役）；④ 然后删 `EconomyData.modes` 组件 / `withModes` / 读口改 `definitions().modes()`（production 定义表）/ `EconomyBoundaryView` 的刻意不对称消失。

#### 13.15.3 裁定十九（载荷 `industries[]`）→ **随 B5 退役**

- 它已不物化、只作瞬态输入被读；production 侧 `Seed` 已承接定义 ⇒ 该键退役（连同 `EconomySeeder` 侧组装与旧形状路径）。判据：`"industries"` 在 economy 载荷组装/解析处 0 命中（解释性注释除外）。

#### 13.15.4 裁定二十（其余三条）

- `EconomyCodec` 旧档 reshape：**保留**（Alpha：旧档 fail-closed，读不回来不是缺陷）。
- `hexRegistered` 少"产业"一维：**接受**，但要求把该维并入 `EconomyReferenceValidation` 的装配点校验（若已在则写明落点），并在账本记录"codec 单独 decode 不再拦该维"的边界；V10 往返由测试 owner 覆盖。
- `classStructureExists` 与三个 0 调用点读口：**B5 清理**。

#### 13.15.5 B5 批范围（收尾小批，之后即测试 owner）

1. `EconomyData.modes` 收口（裁定十八，含 mode 写腿去核与命令面处置）。
2. 载荷 `industries[]` 退役（裁定十九）。
3. 死代码清理（`classStructureExists` + 三个 0 调用点读口）；`hexRegistered` 维度落点写明（裁定二十）。
4. **rc=0 强制重编** + **全反应堆 `spotless:check` rc=0**。
5. 交账后立即派**独立测试 owner**。

### 13.16 B5 实收 + 实现阶段收口（控制方，2026-10-17）

- 实现方 rc=0（强制重编 11 模块 1160 文件、19×"0 Checkstyle violations"）+ 全反应堆 `spotless:check` rc=0；**控制方独立复跑两者 rc=0**。
- 四条成立（控制方抽查）：`EconomyData.modes` 组件 **0 命中**（`withModes` 仅剩两条"已删"说明注释）· 两个 mode kind 改**具名拒** `production-mode-definition-moved-to-production`（能力由 production 既有命令面承接，未退役；去核证据 = 全仓 0 调用点）· economy 载荷 `"industries"`/`"modes"` 解析 **0 命中**、entry 键已是 `plans` · `classStructureExists` 仅剩一条说明注释 · `hexRegistered` 产业维落点在 `EconomyReferenceValidation.validateDemandHexes`（`:462`）· `EconomyData` 里 `IndustryDefinitionView`/`ProductionModeDefinitionView` 命中**全是注释**（I1 第二权威清零）。
- ★ **实现阶段（A→A′→B1→B2→B2b→B2c→B3→B4→B5）到此收口**：全部批次真实 rc=0（多数为强制重编）+ Spotless/Checkstyle 绿；**本轮没有跑过任何测试/模拟** ⇒ 下一阶段（测试）是唯一的行为验证来源。

#### 13.16.1 B5 三条如实边界的裁定

1. **`trade` 的 unit 仍由 legacy 合成**（seeder 刻意不重发）⇒ **接受为已知残留**（不是"已清理干净"）：测试 owner 必须为 `trade` 路径建判据（"seeder 不重发的前提下，trade 仍能落 unit 且实例归 production"），并写进状态文档的未完成清单。
2. **economy 载荷 `modes[]` 键一并退役**（裁定十八未点名，实现方按同一口径处理）⇒ **接受**：世界播种一直走 `production.Seed`，两边喂的是同一份 `DefaultProductionModes` ⇒ 值不变；测试 owner 用"播种后 production 定义表内容"判据覆盖。
3. **`simos-production/` 与 `app/production/` 是未跟踪目录** ⇒ 那两个文件**无 git 回退手段**（B5 已如实记录）⇒ 记入风险清单；后续任何批次的回退只能靠**文件前像备份 + md5 自证**。

---

## §14 测试阶段派单正文（D032-TEST；控制方，2026-10-17）

> 权威顺序：用户原话 > 本文 §14 > §13 各批次裁定 > §6 的 V1–V12 判据表。测试 owner **只写测试与证据，不改生产代码**（受控变异必须还原并 md5 自证）。

### 14.1 责任区与文件所有权

| 角色 | 拥有 | 禁止 |
|---|---|---|
| **D032-TEST（一个测试 owner）** | 全仓 `src/test/**`、门禁清单里与测试相关的部分、独立的**长跑/黄金对比脚本**、`.superpowers/sdd/2026-10-17-d032-runtime-migration/test/**` 台账与证据 | 改 `src/main/**`（唯一例外：**受控变异**，须"改坏 ⇒ 当场红 ⇒ 还原 ⇒ md5 逐字节一致"）；改 POM/enforcer/边界表；`git add/commit/stash/checkout`；动主树/`ga-opt`/`d031-p1-baseline`/`stash@{0}`；改控制方文档 |

### 14.2 第一原则（写死）

- **不许把"没跑"写成"通过"**；编译通过、源码阅读、算术论证**都不算**行为验证。
- 每条判据的台账形制照 P1 的 `test/control-v1v7.md` §8：**可复制原命令 + 真实输出（rc/计数/关键行原文）+ 未验证声明**。
- 失败就是失败：**先报缺陷、给证据**，不得为了绿而放宽断言、删断言、改判据口径。
- 需要改生产代码才能过的 ⇒ **停手报 BLOCKED**（写清判据、现场 `file:line`、最小修法建议），交回控制方派单。

### 14.3 阶段 T1：让测试面重新编译并跑起来

1. 先跑 `tools/mvn-lock.sh -DskipTests test-compile -pl simos-app -am`（`-pl` 必须带 `-am`）拿到**当刻完整编译红清单**。
2. 按台账各批次的**测试影响面**逐条修：`impl-ledger.md` 的 `A2-§9`、`A3-§9`、`B1-§10`、`B2-§9`、`B2b-§11`、`B2c-§11`、`B3-§12`、`B4-§7`、`B5-§10`（关键词：`ProductionRuntime` 新口/新类型、`IndustryDefinitionView` 两新组件、`ProductionProcess.location`、`EconomyData` 组件数 37→33→31、`EconomyMeta` 第 7/8 组件、`ProductionCandidate`/`CandidateId`/`templateFor`/`upsertCandidate` 删除、`OwnershipStake.RightKind`→`AssetRightKind`、`EconomySession`/`EconomyDayStepper`/`EconomyStateBuilder` 的 `runtime=null` 构造器与 `EconomyBaseDefinitions` 删除、`unitsWorkCopy` 删除、载荷 `industries[]`→`plans`、`MINT_MILLI_PER_FURNACE_CYCLE` 具名拒、`EconomyData.modes` 删除、`classStructureExists` 删除）。
3. **行为变更断言必须改写（不是放宽）**：
   - I8：断言"被缩减的劳动留在空缺"的用例 ⇒ 改为断言"分配总量 + 新候选的实投"；
   - I9：断言"按该格所有家户行算劳动"的用例 ⇒ 改为"按本周期**实际出工家户与各自贡献**"；
   - 效率：`probe.scale` = **产出规模**（finalScale）、`*Required` 按 **baseScale** ⇒ `assetRequired == scale × perUnit` 的旧断言**故意红**；
   - `EconomyData.units()` 里读进度/投入的用例 ⇒ 改读 `productionRuntime().units()` / `EconomySession` 的现扣读数；
   - 铸币：写政策键后铸币量变化的用例 ⇒ 改断**具名拒** + 改由 production 定义 `moneyOutputPerUnit` 生效；
   - 候选面退役 ⇒ 候选相关用例改断 `plans`/生产定义读口。

### 14.4 阶段 T2：验收判据（V1–V12 + 全批次新增判据）

**§6 的 V1–V12 全部要跑**（含 V12 完整门禁 `clean verify`，Skipped 不算通过）。**另加**（按批次累积，逐条要有真实读数）：

| # | 判据（要点） | 期望/口径 |
|---|---|---|
| N1 | **TOTAL 效率**：劳动 100 / 资产 200 / 原料 120、E=1500 | `baseScale=100`、`finalScale=150`，消耗按 100 档 |
| N2 | **LABOR 显式声明**：同例 | `finalScale=120`（瓶颈在别处时加成可为 0，方案声明如此） |
| N3 | **反例**：各 100、E=1500 | 旧式 100（归零）⇒ 今 **150** |
| N4 | **余数跨周期**：E=1505 | 周期1 = 150+余 500、周期2 = 151 ⇒ 两周期 **301**（逐次截断会永远 300） |
| N5 | **经验小额聚合**：g=1、每次 100 毫劳动 | 归并后主值 + 余数合计与累计换算一致（**不得**永远 0） |
| N6 | **同 tick 归并 + 顺序无关**：同户同条两实例各 100 | 一次换算、换遍历序逐值不变；同户同条不得重复计 |
| N7 | **同 tick 统一生效条件**：调整实例处理次序 | 另一实例本 tick 熟练度/产出**不变** |
| N8 | **重复/乱序推进**：同 tick 重复 execute / 重复衰减 / 重复 `beginTick` / tick 回退 / 未开启即衰减 | 五类**具名契约故障**（不得静默） |
| N9 | **批量失败语义**：业务拒绝 | **零残留**（无新实例/进度/经验/消耗/占用） |
| N10 | **契约故障**：注入公式错误/跨域不一致 | **拒绝整个相关 Advance 提交**（零半提交） |
| N11 | **`probe` 无副作用**：试算前后 | 全切片逐值不变（V1 的三条读数） |
| N12 | **`laborRequired` 不变式**：`probe` 判可行（含**可用劳动恰好等于需求**的临界样例） | `required ≤ 可用 + slack`（slack=1 毫劳动），超 slack ⇒ 契约故障 |
| N13 | **单产出方案入口算法逐值不变**（候选退役后） | 与改动前逐值一致（多产出按商品逐项） |
| N14 | **I9 贡献归属**：多户协作 | 只记一份总产出；工资/租金/分成按**实际贡献** |
| N15 | **I10 拆合**：V6 例式（60 人×10 + 20×30 ÷ 40） | **30**；迁移完整携带（含余数、不乘比例）；财债按比例逐项 |
| N16 | **铸币权威**：政策键 | 具名拒；产出率由 production 定义生效；发行资格/上限/记录/入账仍按既有玩法 |
| N17 | **账户原子性**：注入中途失败 | 零部分提交（含 actor 的 ChangeSet 缺失 ⇒ 红） |
| N18 | **无第二权威扫描**：`EconomyData.units/industries/modes`、`unitsWorkCopy`、`ProductionCandidate`、`templateFor`、`ProductionEfficiencyBook`、economy→production 非 contract import | 全 0（解释性注释除外）；扫描面本身要先自证非空 |
| N19 | **清区在有实例的区** | 三段同批跑通（退役实例 → 删定义）；只跑 production 段 ⇒ 保留不删悬空 |
| N20 | **播种**：`production.Seed` 先于 `economy.Seed` | 颠倒 ⇒ 创世具名拒；正常序 ⇒ 世界可建；载荷无 `industries[]`/`modes[]` 依赖 |
| N21 | **`trade` 路径**（B5 遗留） | seeder 不重发的前提下仍能落 unit，且实例归 production |
| N22 | **串行 vs 并行逐值一致** | **同一套新规则**下比较（不得要求复现旧错值） |
| N23 | **`UNSPECIFIED` 不得静默运行** | 四处 fail-closed 生效 |
| N24 | **旧无效入口** | `runtime=null` 构造器等已删 ⇒ 调用即编译红 |
| N25 | **长跑/性能观察** | 记录 `probe` 在 O(家户×候选) 的耗时；**不许**为性能加缓存（除非同源且不跨 revision 陈旧）——先报数 |

### 14.5 阶段 T3：变异自证（负向）

每条负向用例必须"**改坏 ⇒ 当场红 ⇒ 还原 ⇒ md5 逐字节一致**"：删掉试算无副作用 ⇒ V1 红；把解锁改成衰减重判 ⇒ V5 红；让 economy 再存一份方案数值 ⇒ N18 红；把 `templateFor` 留回 ⇒ 扫描红；漏 actor 的 ChangeSet ⇒ N17 红；重复计经验 ⇒ N6 红；把 I8 回填关掉 ⇒ V3 红。做不到的（等价存活）**如实写"等价存活"**，不许含糊成"通过"。

### 14.6 阶段 T4：门禁与黄金对比

- `tools/mvn-lock.sh -pl simos-app -am clean verify` ⇒ rc=0（Spotless/Checkstyle/SpotBugs/前端门禁/`test` 全过；**Skipped 不算通过**，要报模块汇总与计数）。
- 前端门禁按现有口径报计数（P1 基线 412/412；本轮以当刻真实值为准）。
- **黄金对比（P0/旧 vs 今）**：本批**故意**改行为的地方（I8 回填、效率公式、I9 换源、铸币权威、候选退役）**允许有差异**，但**每一处差异必须逐条解释到具体判据**（手算样例或 provenance），**不许**笼统归因。

### 14.7 交账

- 落点：`.superpowers/sdd/2026-10-17-d032-runtime-migration/test/`（`control-v1v12.md` 或同形制多份 + `evidence/`）；`task-report-test.md`。
- 必含：阶段 T1 红的完整清单与逐条处置 · T2 每条判据的**真实命令与输出** · T3 变异证据（含 md5）· T4 门禁 rc 与计数 · **未验证/没做**一节 · 缺陷清单（判据失败 + 现场 `file:line` + 最小修法建议）· 若需改生产代码 ⇒ BLOCKED。
- 终结判据：**V1–V12 全绿 + N1–N25 全绿（或逐条给出"未做/失败"的具名结论）+ T3 变异自证完成 + T4 门禁 rc=0**。达不到就报 BLOCKED，不得写成完成。

### 14.8 测试阶段首轮交账的裁定与 B6 修复批（控制方，2026-10-17）

#### 14.8.1 首轮实收（测试 owner 交账）

- `test-compile -pl simos-app -am` rc=1（**不带 `-fae` 时只报首个失败模块的 1 个文件**）；`-fae` 后：economy 24 文件 · culture 2 文件 · **app SKIPPED（182 个测试文件的红清单仍未知）**。
- 已修绿：production 3 文件（往返夹具 4→8 组件、字段白名单 14→18、handler 构造期注入端口）⇒ 该模块 **30/31 绿**；culture 2 文件 ⇒ 编译 rc=0。
- 未跑：**V1–V12 全未跑 · N1–N25 全未跑 · T3 全未做 · T4 全未做**。

#### 14.8.2 裁定二十一（DEF-1，阻塞级）→ **派 B6 一行修复 + 全树同类扫描**

- 事实（控制方核过）：`ProductionLocation.java:92` 的 `public boolean isSpecified()` 被 Jackson bean 内省当成属性 `specified` **写进 JSON**，而读侧 `FAIL_ON_UNKNOWN_PROPERTIES`（本仓**刻意保持严格**，见 `simos-util/.../SimosObjectMapper.java:46`）拒收 ⇒ **自己写的 JSON 读不回来**（V10 失败，并挡住 V1/V2/V5/V7/V8 的入口）。
- ★ 本仓**已有既成口径与先例**：`SimosObjectMapper.java:89` 专门记录了"bean 内省把 `empty` 写进字节"的坑；main 里已有 **33 处** `@JsonIgnore`。⇒ 按既有做法在**派生读口**上加 `@JsonIgnore`（不是关掉严格性）。
- **不接受的替代**：关 `FAIL_ON_UNKNOWN_PROPERTIES`、给 codec 加全局忽略、把字段改名绕过 —— 那都是把守卫拆掉一半。
- **必做全树同类扫描**（同类 = 会被 codec 写出的类型上的派生布尔/派生读口）：至少覆盖 `MoneyOutput.isEmpty()`、`ProductionDayResult.isEmpty()`、`ProductionTickCondition.isNeutral()/isFor(long)`、`ProductionConsumerPort` 内部 record 的 `isEmpty()`、`economy-api` 的 `LotChange.isEmpty()`、economy 侧 `AccountDelta/MarketTable/EconomyChangeSet/…` 的 `isEmpty()` 等；判据 = **凡被 codec 序列化的类型，其派生读口一律不得成为 JSON 属性**（扫描证据 + 逐类处置表）。

#### 14.8.3 裁定二十二（DEF-2，变异面）→ 变异改为"**新增**第二权威"

- 测试 owner 的判断正确：迁移本身已把第二权威删净 ⇒ "改坏既有第二权威"**没有变异点**。
- N18 的变异改为：**临时新增**一份第二权威（例如在 economy 侧加一个把方案定义写进状态的字段/写路径，或让某读口改读本地副本）⇒ **扫描/判据必须当场红** ⇒ 还原 ⇒ md5 逐字节一致。同样地：把 `templateFor` 留回、把 `unitsWorkCopy` 加回、把 `EconomyData.industries/modes` 加回，都属合法变异面。
- 若某条变异**确实无法构造**（例如类型已不存在且无最小替代）⇒ 写"等价存活 + 为什么无法构造"，**不得**含糊成"通过"。

#### 14.8.4 口径更正（枚举方法）

- **列全量红清单必须用 `-fn`（fail-never）**，不要用 `-fae`（它会把下游模块标成 SKIPPED，app 的 182 个测试文件正是因此一直没被编译到）；逐模块用 `-pl <模块> -am` 复核。此条与 P1 期间记录的同一陷阱一致。

#### 14.8.5 B6 批范围（一行修复 + 全树同类扫描；修完即回测试阶段）

1. 修 `ProductionLocation.isSpecified()` 的 JSON 属性泄漏（`@JsonIgnore`，按 14.8.2 的既有口径）。
2. 全树同类扫描 + 逐类处置（14.8.2 末段；给"哪些类型会被 codec 写出、哪些派生读口已加注解"的表）。
3. **rc=0 强制重编** + **全反应堆 `spotless:check` rc=0**。
4. 交账后：测试 owner 继续 T1（economy 18 文件 → rc=0 → `-fn` 拿 app 全量红清单 → 逐类修）→ T2/T3/T4。

### 14.9 B6 实收与测试阶段续做（控制方，2026-10-17）

#### 14.9.1 B6 实收（控制方复核）

- 实现方：强制重编 rc=0（11 模块 1160 文件）· 全反应堆 `spotless:check` rc=0（首次 rc=1 是它自己改的 javadoc 换行，`spotless:apply` 只动 1 个文件后复绿）· **`test -pl simos-production -am` rc=0 · 31 tests / 0 failures / 0 errors**，其中 `ProductionRoundTripTest` **13/0/0**（修前 13/0/1）。
- 控制方独立复核：注解落点在**派生读口** `ProductionLocation.java:110`（import `:3`、javadoc `:93-109`）· 该文件 md5 = `665f0ad9aab409a91898a85e61260a7d`（与交账一致）· **严格口径未被削弱**（`SimosObjectMapper:46` 原文仍在）· 同口径先例仍在（`MoneyOutput:100`、`MarketTable:211`、`EconomyMeta:307`）· `src/test/**` 未被本批触碰（仍是测试 owner 的 67 项）· 控制方复跑 `spotless:check` rc=0 · 控制方复跑 `test -pl simos-production -am` 结果与交账一致。
- ★ **本轮第一个真实行为验证通过**：JSON 往返（V10 入口）在修后真跑绿了 —— 这是整条迁移第一次有"运行期读数"。
- ★ **控制方派单书的一处不精确（如实记）**：§14.8.2 我点名了 6 个"同类风险"类型，B6 实测**其中 5 个根本不在 codec 可达闭包内**（`ProductionDayResult`、`ProductionTickCondition`（`isFor(long)` 有参 ⇒ 非属性）、`ProductionConsumerPort` 两个内部 record、`LotChange`、`AccountDelta`）。B6 用"24 个 Snapshot/ChangeSet 根 → 传递闭包 373 类 + 真实 `BeanSerializer._props` / `BeanDeserializer` 内省"的两层判据把范围定准：**闭包内 `written − readable` 由 1 类（ProductionLocation）降为 0 类**。⇒ 后续派单里"点名风险类型"一律标为**待核线索**，不得当结论。
- ★ 边界（如实）：B6 跑的是**既有测试**（验证 DEF-1 修好），**未写任何测试**、未动 `src/test/**`；全反应堆 `test`/`verify` 仍未跑。

#### 14.9.2 测试阶段续做（T1 收尾 → T2/T3/T4）

1. **T1 收尾**：`simos-economy` 18 个报错文件 → `test-compile` rc=0；随后**用 `-fn`**（fail-never）拿全反应堆红清单，修到 `simos-app` 的 182 个测试文件也能编译（`-fae` 会把下游标 SKIPPED，禁用）。
2. **T2**：V1–V12 + N1–N25，每条真实读数；手算期望按 §14.4 表。
3. **T3**：变异自证（N18 按裁定二十二 = **新增**第二权威；`templateFor`/`unitsWorkCopy`/`EconomyData.industries|modes` 加回均属合法变异面）；每条"改坏 ⇒ 当场红 ⇒ 还原 ⇒ md5 一致"；不可构造的写"等价存活 + 原因"。
4. **T4**：`clean verify` rc=0（报模块汇总与计数，Skipped 不算通过）+ 前端门禁计数 + 黄金对比（故意变更允许差异，逐处解释）。
5. 台账续写在同一 `test/` 目录；交账与终结判据同 §14.7。

### 14.10 测试阶段第二轮实收、裁定二十三/二十四与 T1 收尾派单（控制方，2026-10-17）

#### 14.10.1 第二轮实收（控制方复核）

- 实现方/测试方声明：economy 测试面 18 文件 → **0**（`test-compile -pl simos-economy -am` rc=0）；app 从 58 条修到 27 条；`src/main` 自 06:34 起 **0 文件被改**（控制方核过）；测试面 70 项 / 60 文件 / +2039−2509。
- **控制方独立复跑**：`test-compile -pl simos-economy -am` **rc=0 · BUILD SUCCESS** ✓；`test-compile -pl simos-app -am` **rc=1**。★★ **口径更正（控制方自纠，2026-10-17）**：此处原写"13 文件 / 46 条"**是错的** —— Maven 把同一张错误表**印了两遍**（23×2=46），我按 `[ERROR] …java:[` 行数计数就中了招；"13 个文件"里另有 2 个只在 `[INFO] …deprecated/unchecked` **告警行**出现、零条 error。**真实红面 = 11 文件 / 23 条**（唯一 `(file:line)` 组合数 = 23，测试 owner 复核一致并已按 11 文件全修完）。
- ★ **新增纪律（与 Checkstyle 截断同一族）**：**数错误一律以 Maven 自己的计数或"唯一 `(file:line)` 组合数"为准**，不得按 `[ERROR]` 行数数；告警行（deprecated/unchecked）不是 error。派单书里凡引用红面计数，必须写清统计口径。
- 错误种类分布：`cannot find symbol` 38 · 方法签名不匹配 4 组（`ApiViews.population` / `GovernmentServiceUnitConsistency.mismatches` / `Map<ProductionUnitId,ProductionProcess>` 与 `ProductionDefinitionLookup` 互转 2 · 推断边界 2）。

#### 14.10.2 裁定二十三（口径更正）→ T2 是"**新建**判据用例"，不是"跑一遍"

- 事实（控制方核过）：`simos-production/src/test` 只有 **3 个测试文件 / 31 条**（`ProductionContractGuardTest` 6 · `ProductionRoundTripTest` 13 · `D031P1DefinitionCommandNegativeTest` 12）；V1–V12 与 N1–N25 在 `src/test` 里**没有现成载体**。
- ⇒ 修正 §14.4 的措辞：T2 = **新建** 37 条判据的用例（V1–V12 + N1–N25）并跑出真实读数。施工图见测试台账 §9.1（`ProductionEfficiency.evaluate` 十参签名 + `Evaluation` 九字段、`ProductionRuntime.probe/executeDay`、四个面的落点）。
- **覆盖缺口 G1–G6**（因能力退役删掉了旧载体、替代载体尚未交付）**不得消失**：每条必须收成"**有载体并跑绿**"或"**具名未做 + 原因**"。优先按测试台账 §9.2 的建议落点。
- 禁止用新用例的"新建"掩盖旧断言被删：删掉的旧断言必须逐条给出"被测能力已按哪条裁定退役"或"已迁移到哪个新载体"。

#### 14.10.3 裁定二十四（工具陷阱，写进派单书）

- **`-Xmaxerrs` 截断**：Maven 默认单模块错误上限 100 ⇒ 红清单"越修越多"（economy 实测）。列全量要显式抬高上限（如 `-Dmaven.compiler.maxErrors=10000`，若该版本不支持则按模块/按包分批跑），并保留每轮原始日志。
- **`-fae` 会把下游标 SKIPPED** ⇒ 列全量用 **`-fn`**（§14.8.4）。
- **Checkstyle 违规列表同样会被截断**（P1 已记录）⇒ 以"`N needs changes` 计数"为准，不以截断后的明细为准。

#### 14.10.4 T1 收尾派单范围（只改 `src/test/**`）

1. 按**控制方权威清单**（13 文件 / 46 条）修到 `tools/mvn-lock.sh -o -DskipTests test-compile -pl simos-app -am` **rc=0**；台账 §8.3 的表**作为线索**使用（已确认对其中至少 2 个文件过期）。
2. 只做 T1 收尾，**不要**顺势开始 T2；修完即交账（含每文件处置表、diffstat、`src/main` 零改动自证）。
3. 修不动或需要改 `src/main` ⇒ 停手报 BLOCKED（判据 + `file:line` + 最小修法）。
4. 收尾后由控制方另派 **T2（新建判据用例）→ T3（变异）→ T4（门禁/黄金）**。

### 14.11 T1 收尾实收、裁定二十五~二十七与 T2 拆分（控制方，2026-10-17）

#### 14.11.1 T1 收尾实收（含控制方自纠）

- **T1 完成**：`tools/mvn-lock.sh -o -DskipTests test-compile -pl simos-app -am` **rc=0 · 19/19 SUCCESS**；**排除增量假绿**：删 `simos-app/target/test-classes` 后重编 **179 个测试源文件**仍 rc=0；全反应堆 `spotless:check` **rc=0**。
- **`src/main` 零改动自证**：`find … -path "*/src/main/*" -newermt "2026-10-17 06:56:25"` ⇒ 0；`spotless:apply` 前后 `src/main` md5 比对改动 0；`git diff --stat -- '*src/test/*'` = 60 files / +2300−2714（累计三任测试 owner）。
- ★ 控制方**自纠**：§14.10.1 的红面计数错误（13/46 → 真实 **11/23**）已在上文更正，并立"以 Maven 计数或唯一 `(file:line)` 为准"的纪律。
- ★ T1 顺手清掉 3 处**编译绿、运行期 fail-closed** 的读口（`CompactThreeNationsWorld` 裸读 `EconomyData.industries()/modes()/units()`）—— javac 看不见的那一类。

#### 14.11.2 裁定二十五（`S1Stage3TenancyTest` 整文件删除）→ **接受，但必须接载体**

- 接受删除：其唯一 `@Test` 的被测能力（经济载荷解析期的 regime→operator 推导 + unit 合成）已按 **B5 裁定十九**（载荷 `industries[]` 退役）+ **B2b 裁定六**（`LegacyHouseholdMigration` 去 units 化）退役；推导表本身仍在 `RegimeOperatorsTest` 里绿着。
- **条件**：显式 operator 的新判据必须落到 T2 的 `ProductionSeedInstancesTest`（覆盖缺口 **G2**）——"能力退役"≠"判据消失"。

#### 14.11.3 裁定二十六（`GoldenV2ExporterTest` 少两个黄金键）→ 接受，T4 必须逐处解释

- `cycleModifierSumPerMille` / `modifierRemainderMilli` 随 **D2**（文化→效率倍率链）退役 ⇒ 接受键集收缩。
- **条件**：T4 的黄金对比必须把这两个键的消失**逐条解释到裁定与代码位置**，不得作为"未解释差异"混过去。

#### 14.11.4 裁定二十七（占位断言风险）→ T2 必须清扫

- 现象：上一任测试 owner 在部分文件里把实例投影写成 `Map.of()` 占位（只有 3 个文件接回了真实例）⇒ **断言可能空转（假绿）**。
- 裁定：T2 必须**逐处清扫**：要么接真实数据，要么把该用例明确改写成"空世界"场景并在名字/注释里写明；清扫清单进台账。**凡"传空表让断言恒真"的用例一律算缺陷**。

#### 14.11.5 T2 拆分（两批，均只写 `src/test/**`）

| 批 | 范围（判据） | 模块 |
|---|---|---|
| **T2a** | 生产侧语义：**V1**（试算无副作用）· **V2**（预测/实际同源）· **V5**（经验/衰减/门槛/解锁，含"衰减跌破门槛仍可用"）· **V9**（无陈旧视图/重复推进被拒）· **V10**（往返；已绿 13 条，按新组件扩展）· **N1–N8**（效率数值 TOTAL/LABOR/反例、余数跨周期、经验小额聚合、同 tick 归并与顺序无关、tick 统一条件、五类具名契约故障）· **N10**（契约故障整批拒绝）· **N12**（`laborRequired ≤ 可用 + slack` 含临界样例） | `simos-production/src/test` |
| **T2b** | 跨域与经济侧：**V3**（I8 回填）· **V4**（I9 贡献归属）· **V6**（I10 拆合，V6 例式=30）· **V7**（铸币权威 + 具名拒）· **V8**（账户原子性）· **V11**（边界机械面）· **V12**（完整门禁）· **N13**（单产出逐值不变）· **N14–N17** · **N19**（清区在有实例的区）· **N20**（播种顺序）· **N21**（trade 路径）· **N22**（串行等价）· **N23**（UNSPECIFIED 四处 fail-closed）· **N24**（旧无效入口）· **N25**（长跑/性能观察）+ 覆盖缺口 **G1–G6** 载体 | `simos-economy/src/test` + `simos-app/src/test` |

- 两批都**只写测试**；需要改 `src/main` ⇒ 停手报 BLOCKED。
- 每条判据必须断言**具体数值**（不许 `assertNotNull` 充数），并把**手算过程写在用例注释里**；证据进 `test/` 台账与 `test/evidence/`。
- T2a/T2b 完成后：**T3**（变异自证，N18 = 新增第二权威型变异）→ **T4**（`clean verify` rc=0 + 前端计数 + 黄金对比逐处解释）。

### 14.12 T2a 实收、裁定二十八/二十九与 B7 修复批（控制方，2026-10-17）

#### 14.12.1 T2a 实收（★ 首次真实判据，抓到"第一天必炸"）

- 新增 **32 条用例 / 6 个文件**（只写 `simos-production/src/test/**`）：`ProductionEfficiencyHandCalcTest`(5) · `ProductionRuntimeProbeTest`(9) · `ProductionRuntimeExperienceTest`(7) · `ProductionRuntimeContractFaultTest`(5) · `ProductionRoundTripNewComponentsTest`(2) · `ProductionLocationFailClosedTest`(4)，夹具 `ProductionTestFixtures`（真数据、无占位空表）。
- `test -pl simos-production -am` = **63 tests / 1 failure / 5 errors**：**57 绿**、**6 红，且 6 红全部归因于 `src/main` 缺陷**（不是判据写不出来）。
- 已通过的判据（节选真实数值）：**N1** baseScale=100 → finalScale=150、消耗 {LAND:100, grain:100}、产出 150000 毫 · **N2** LABOR=120、需求 ⌈120×1000÷1500⌉=80、资产瓶颈时加成归零 · **N3** 各 100 ⇒ TOTAL 150 vs LABOR 100 · **N4** 150+151=**301** · **N5** 5 次 (0,500) → 10 次 (1,0)，对账 1000 · **V1** 三条读数 · **V10** 8 组件 + 余数/解锁/三段余数/覆盖/地点逐字段重建 · **N12** 临界样例（TOTAL 临界 required=100=可用；LABOR E=1505 ⇒ ⌈⌉=100=可用而 ⌊⌋ 会得 99）· `V2`/`V9`/`V5增长与门槛`/`N8⑤`/`N10公式错误腿`/`N23a` 部分 · 附加余数越域契约故障。
- **控制方独立复跑**：`test -pl simos-production -am` 结果与交账一致（63/1/5）。

#### 14.12.2 裁定二十八（三处 `src/main` 缺陷）→ 全部必修，派 B7

1. **DEF-T2a-3（★★★ 阻塞级）**：`ProductionRuntimeEngine.beginTick` 在**登记 tick 身份之前**就调 `advanceTick()`，而 `advanceTick()` 首行守卫要求"已开启过 tick" ⇒ **新引擎的每一次 `beginTick` 都抛 `tick-not-started`**（`lastAdvancedTick` 永远停在 -1）⇒ **tick 协议（衰减 + 同 tick 统一生效条件）在 main 上完全不可用**；两条组合根接线（`EconomyOwnershipTimeParticipant:216`、`PopulationEconomyTimeParticipant:679`）**第一天就会炸**且不捕获。
   - 控制方已读源头确认（`beginTick` :985-1012 与 `advanceTick` :1017-1030 的调用次序如上）。
   - **必修**，且必须保住五类具名契约故障的语义：首次 `beginTick(0)` 必须成功；每 tick **恰好一次**衰减；`tick-already-started`/`tick-regressed`/`tick-already-decayed`/`tick-not-started`（公开 `advanceTick` 被跳过 `beginTick` 直接调用时才抛）/`instance-advanced-twice-in-tick` 全部仍按原语义触发。
2. **DEF-T2a-1**：`skipStep` 末行把"实例引用的方案定义不存在"判成**跳过**（`return … == null`），与它自己的 javadoc 第 ③ 条（"这一条**不**跳过，是契约故障"）**相反** ⇒ `executeDay` 把跨域不一致吞成 `not-advanced-this-day`。**必修**：让该情形抛具名契约故障 `instance-references-unknown-plan`（不降级成业务拒绝，I15）。
3. **DEF-T2a-4（口径小疵）**：`:626` 对"实例不存在"给 `not-advanced-this-day`，与 `:668-669` 注释"同一具名理由"不一致 ⇒ 二者对齐（或给 `instance-unknown` 自己的具名理由，以注释为准）。

#### 14.12.3 裁定二十九（DEF-T2a-2：构点是否 fail-closed）→ **构点必须 fail-closed**

- 现场：`ProductionSeedHandler:161-164`、`CreateInstanceHandler:95-99` 在载荷显式给 `"location":"?"` 时**仍把占位实例落进权威候选态**。
- **裁定**：两处都必须在 parse 之后加 **`!isSpecified() ⇒ 具名拒 location-unknown`**。理由：§13.8 裁定二第 1 条把**播种**明列为"须 fail-closed 的语义点"；只靠读侧防御 = 允许坏数据进权威状态（那正是"静默降级"的形态）。B3-§7 ⑦ 的"保留常量作读侧防御"指的是 **`parse` 仍能构造出它**（防御性读取），**不是**允许它被写进权威状态。
- 同时保留读侧那一道（economy `EconomyReferenceValidation:127`）—— 两道都要。

#### 14.12.4 B7 批范围（修完即回测试阶段原样复跑那 6 条）

1. 修 DEF-T2a-3（`beginTick` 调用次序 / 抽私有 `decayOnce()`，保住五类具名故障语义）。
2. 修 DEF-T2a-1（悬空方案 ⇒ 契约故障，不跳过）。
3. 修 DEF-T2a-4（具名理由与注释对齐）。
4. 修 DEF-T2a-2（两处构点 fail-closed，具名拒 `location-unknown`）。
5. **rc=0 强制重编** + `spotless:check` rc=0。
6. 交账后：测试 owner **原样复跑那 6 条红用例（判据与期望值一字不改）**，随后 **T2b**（跨域经济侧判据）。

#### 14.12.5 ★ 本轮教训（写进纪律）

**编译绿 + Spotless 绿 + 装配点齐全 + 五类具名守卫写在注释里，都不能证明"第一天跑得起来"。** T2a 的第一批真实判据（32 条）就抓出"tick 协议整体不可用"这种阻断级缺陷 —— 它此前穿过了 9 个实现批次、每次强制重编与全反应堆 Spotless。⇒ 后续任何"已接线/已实现"的结论，**没有运行期读数一律只能写成"已编译"**。

### 14.13 B7 实收、裁定三十~三十二与测试阶段续做（控制方，2026-10-17）

#### 14.13.1 B7 实收

- **四处修复全部完成**（只动 **3 个 `src/main` 文件**，`src/test` 零改动）：
  1. **DEF-T2a-3（阻断级）**：`ProductionRuntimeEngine.java:1009` 改调**新增**的私有 `:1052 decayOnce()`；公共 `advanceTick()`（`:1028-1040`）保留两道守卫后 `return decayOnce()` ⇒ 首次 `beginTick(0)` 成功、每 tick 恰好一次衰减，日序（先衰减 → 再冻结条件）不变。
  2. **DEF-T2a-1**：`skipStep` 改 `Optional<String>` 并**删掉**"悬空方案 ⇒ 跳过"分支（`:679-688`）⇒ 由 `:761-767` 抛 `instance-references-unknown-plan`、整批终止。
  3. **DEF-T2a-4**：`:681` 只留"本 tick 已推进过"；`:683-686` 实例不存在 ⇒ 与 `execute:748` 同一条理由 `instance-unknown`。
  4. **DEF-T2a-2（裁定二十九）**：`ProductionSeedHandler:166-179` + `CreateInstanceHandler:84` 两处构点 `!isSpecified() ⇒ 具名拒 location-unknown`；读侧那一道保留（两道都要）。
- **T2a 的 6 条红全部转绿**（判据/期望值一字未改）：V5 衰减 · N6 顺序无关 · N7 · N8①~④ · N10 跨域腿。
- **五类具名故障语义保持自证**：`instance-advanced-twice-in-tick` / `tick-already-decayed` / `tick-already-started` / `tick-regressed` / `tick-not-started`（仅"跳过 `beginTick` 直调公开 `advanceTick`"时抛）+ 首次 `beginTick(0)` 成功 + 每 tick 恰好一次衰减（V5 逐值 100→70→40→10→0）。
- **定稿读数**：强制重编 rc=0 · 全反应堆 `spotless:check` rc=0 · `test -pl simos-production -am` = **63 tests / 2 failures / 1 error**（差的 3 条见 14.13.2）。

#### 14.13.2 裁定三十（3 条"缺陷现状"断言）→ **改判据，不是删**

- 冲突根因（**控制方派单书的问题**）：§14.12.4 我写"6 条原样复跑通过 ⇒ 期望 63/0/0"，而 T2a 在测试里另写了 **3 段"现状读数"块**，断言的正是**修复前的缺陷行为** ⇒ "四处全修"与"这 3 条原样通过"在同一份代码上**不可兼得**。B7 的处理正确：修满四处 + 不动测试一个字节 + 照实报 `63/2/1` + 上报冲突。
- **裁定**：3 段现状块**必须转成修复后的判据**（不许删断言、不许放宽）：
  1. `ProductionRuntimeContractFaultTest:55`：新引擎 `beginTick(5L)` **必须抛** ⇒ 改为"首次 `beginTick(5L)` **成功**"（`：61` 的"直调 `advanceTick` 仍抛 `tick-not-started`"保留）。
  2. `ProductionRuntimeContractFaultTest:162-172`：`executeDay` 返回 `rejections={U2: not-advanced-this-day}` ⇒ 改为"当场抛 `instance-references-unknown-plan`"（`:175-182` 的契约故障判据保留）。
  3. `ProductionLocationFailClosedTest:165-176`：播种构点**接受** `"?"` ⇒ 改为"具名拒 `location-unknown`"（`:179-185` 的读侧清区契约故障判据保留）。
- ★ **纪律**：测试里允许写"现状读数"以钉住缺陷，但**必须**（a）在用例名/注释里写明"缺陷现状、修复后须改判据"，（b）同时把该缺陷登记进缺陷清单；（c）实现批的派单书**不得**把"修复后测试全绿"当成测试方义务，除非测试方确认无现状块。本次冲突由控制方承担。

#### 14.13.3 裁定三十一（B7 报备的第 5 处修复）→ **接受**

- `ProductionRuntimeEngine.java:1286-1287` 改调**新增** `:1311 masteryFromCondition(...)`：`efficiencyScaleFor` 的 `condition` 分支原先直调 `condition.masteryOf(household, track)`，而 `track` 是**可缺槽位**（null，`ProductionTickCondition.masteryOf` 对其 `requireNonNull`）⇒ 凡"只挂 a / 都不挂经验条"的方案在**开启 tick 之后必 NPE**。该分支在 DEF-T2a-3 修好前是**死代码**（`condition` 恒 null），修好后立刻成为活路径（B7 中间稿实测 3 条 `NullPointerException: track`）。
- 接受理由：只加**私有 null-safe 读口**（缺槽位按 0，与另两条分支及 `ProductionExperienceUse` 类注同口径），不改公式、不删守卫、不加字段依赖开关。★ 这类"死代码变活"的连带修复必须在派单书里预留空间（本次由 B7 报备，处理正确）。

#### 14.13.4 裁定三十二（两条判据空白）→ 必须补

1. "只挂 a / 都不挂经验条 + 先 `beginTick` ⇒ **不 NPE** 且按 0 熟练度计算"。
2. "实例不存在 ⇒ `rejections()` 给 `instance-unknown`"（DEF-T2a-4 的口径变化此前 63 条里无载体）。

#### 14.13.5 测试阶段续做（两段，同一测试 owner）

- **Stage A**（先把 production 收绿）：按 14.13.2 改那 3 段判据 + 补 14.13.4 的 2 条判据 ⇒ `test -pl simos-production -am` **rc=0**（63+2 条全绿）。
- **Stage B**（T2b 跨域）：§14.11.5 表里 T2b 的全部判据（V3/V4/V6/V7/V8/V11/V12 · N13–N25 · 覆盖缺口 G1–G6）+ 占位断言清扫（裁定二十七）。
- 之后：**T3**（变异自证，N18 = 新增第二权威型变异）→ **T4**（`clean verify` rc=0 + 前端计数 + 黄金对比逐处解释）。

### 14.14 测试阶段 Stage A 实收（控制方，2026-10-17）

#### 14.14.1 Stage A 完成：生产侧判据**全绿**

- `tools/mvn-lock.sh -o test -pl simos-production -am` ⇒ **rc=0 · `Tests run: 65, Failures: 0, Errors: 0, Skipped: 0`**（基线 63/2/1 已留证）。生产侧 65 条判据 = T2a 新增 32 条（57 绿中随缺陷修复全部转绿）+ Stage A 的 3 条改判据 + 2 条补判据 + 原有 31 条。
- **3 段"缺陷现状"断言按裁定三十改判据（不是删）**：
  1. `ProductionRuntimeContractFaultTest`：首次 `beginTick(5L)` **成功**（断言 tick=5、条件 `mastery=(h-1×farming)=100`、`isNeutral=false`、`advancedUnitsThisTick` 空）；保留"跳过 `beginTick` 直调公开 `advanceTick` 仍抛 `tick-not-started`"；用例名去掉 "CurrentlyBlocked"。
  2. 同文件 N10 跨域腿：`executeDay` **当场抛** `instance-references-unknown-plan`，并附**零半提交读数**（U1 进度 1 / U2 进度 0）；保留第二条独立复现。
  3. `ProductionLocationFailClosedTest`：播种载荷 `"location":"?"` ⇒ **具名拒 `location-unknown`**（reason 含 unit/plan/"?"）；保留读侧清区 `IllegalStateException` 契约故障。
- **两条补判据（裁定三十二）**：① 可缺经验条槽位（只挂 a / 都不挂 + 先 `beginTick`）⇒ **不 NPE** 且按 0 熟练度算（E=1500 ⇒ scale 150 / 毛产 150000；NONE ⇒ scale 100 / 100000；无关的 `milling=700` 不得混入）；② 未知实例 ⇒ `rejections()` 逐值 `{unit-not-in-state: instance-unknown}`、幽灵实例不落状态、同批其它步骤照常推进。
- `spotless:check -pl simos-production -am` rc=0；`src/main` 1397 文件 md5 前后差异 **0**（测试 owner 未越界）。
- **控制方独立复跑**：结果与交账一致（见 `control/` 下本轮日志）。

#### 14.14.2 当刻状态（供后续批次对齐口径）

| 面 | 状态 |
|---|---|
| 生产侧行为判据 | **全绿（65 条，rc=0）** —— 效率数值（150/120/301）、经验聚合与结转、tick 协议与五类具名故障、probe 无副作用、`laborRequired` 临界不变式、往返、地点 fail-closed 都**有运行期读数** |
| economy/app 侧（T2b） | **进行中**（本 owner 已开始只读侦察）：V3/V4/V6/V7/V8/V11 · N13–N25 · G1–G6 · 占位断言清扫 |
| T3 变异 / T4 门禁+黄金 | **未做** |
| 结论口径 | ★ 生产侧"已实现"现在可以写成"**有判据覆盖**"；economy/app 侧仍只能写"**已编译**"，直到 T2b 给出读数 |

### 14.15 economy 预存大红（64 条）与裁定三十三（控制方，2026-10-17）

#### 14.15.1 事实（测试 owner 实测；控制方将在 Stage B 交账时复跑确认）

- `tools/mvn-lock.sh -o test -pl simos-economy -am` ⇒ **rc=1 · `Tests run: 371, Failures: 39, Errors: 25, Skipped: 0`** ⇒ **64 条红 / 13 个类**：
  `EconomySeedHandlerTest` 14/26 · `EconomyCodecTest` 13/22 · **`MintProductionSettlementTest` 7/7** · **`D031P1HandCalcSettlementTest` 5/8** · `GovServiceCommitmentC7Test` 5/8 · **`MerchantOnDemandTradeW7Test` 4/9** · `D031P1AuthoritySwitchTest` 4/8 · `EconomyInvariantsTest` 4/40 · **`DeathDebtWriteOffTest` 3/3** · `D031P1NegativeSurfaceTest` 2 · `EconomyRoundTripTest` 1/7（组件数 37→31）· `EconomyPolicyMigrationTest` 1/1 · `EconomyGmAdjustmentsOutputQuantityTest` 1/3（白名单 9）。
- ★ **口径更正（重要）**：**"`test-compile` rc=0"不等于"测试面健康"**。T1 只证明了 179 个测试源文件**编译**通过；economy 的运行期**从未跑绿**。⇒ 后续里程碑一律以 **`test` rc=0** 计，编译绿只能写成"已编译"。

#### 14.15.2 裁定三十三（优先级）→ **取 A**，但必须三分桶

**A 的定义**：先把 economy 跑绿（改写行为变更断言 + 修测试面欠账），**再**补 T2b 的新判据。否决 B（把 64 条红留成"未做"）与 C（只挑重合类）。

**三分桶（每桶处置规则不同，必须逐条归桶并写进台账）**：

| 桶 | 含义 | 处置 |
|---|---|---|
| **a 测试面欠账** | 断言的是已迁走/已删的面（`units()` 夹具、旧 `industries[]`/operator 形状、组件数 35→31、白名单 12→9、`进度已移到 ProductionProcess`） | **改写**成新面/新计数；不得放宽、不得删"行为仍成立"的用例 |
| **b 行为变更（§14.3 第 3 条点名）** | 效率口径（TOTAL/baseScale）· 铸币政策键具名拒 + 定义 `moneyOutputPerUnit` 生效（V7/N16）· I8 回填 · I9 换源 · units 读数 | **按新口径改写并断言具体数值**（例：`D031P1HandCalcSettlementTest` 的毛产要按 `baseScale×E` 重算；`MintProductionSettlementTest` 要断具名拒与新来源） |
| **c 疑似真 main 缺陷** | `DeathDebtWriteOffTest`（⌊999×99÷100⌋ expected 989 got **999**；102 vs 100）· `D031P1AuthoritySwitchTest`（同值重放被"被引用"守卫拒）· **`MerchantOnDemandTradeW7Test` 的 `plan-unknown`（= N21 trade 路径缺口，B5 已自报边界）** | **停手报 BLOCKED**（判据 + `file:line` + 最小修法）；由控制方派实现批修 **main**。★ **严禁**把断言改成迁就缺陷行为来"跑绿" |

**c 桶的三角定位方法（写死）**：看该失败行为是否依赖 D-032 改动过的行 —— `git diff HEAD -- <file>` 命中 ⇒ **D-032 回归（必修，I14）**；不命中 ⇒ 记为**既有问题**并给证据（不得混进本批结论）。

#### 14.15.3 Stage B 执行顺序（测试 owner）

1. **先做 c 桶三角定位**（可能阻塞一切）：`DeathDebtWriteOffTest` · `D031P1AuthoritySwitchTest` · `MerchantOnDemandTradeW7Test` ⇒ 能定的定、要修的报 BLOCKED。
2. 再做 b 桶改写（铸币 V7/N16、效率口径、I8/I9）。
3. 再做 a 桶欠账（夹具/计数/白名单/已迁字段）。
4. 最后才是 **Stage B2：T2b 新判据 + G1–G6 + 占位断言清扫**。
5. 每完成一桶跑一次 `test -pl simos-economy -am` 记录红数收敛轨迹（原始日志留证）。

### 14.16 Stage B 部分交账、裁定三十四/三十五与 B8 修复批（控制方，2026-10-17）

#### 14.16.1 实收

- **Stage A 通过**（控制方已独立复跑）：`test -pl simos-production -am` **rc=0 · 65/0/0/0**；3 条现状块已改判据、2 条补判据到位；`src/main` md5 零改动。
- **Stage B 已绿的判据**：**V11** 边界机械面 12/0/0（economy 240 文件 15 import 违规 0、production 72 文件越界 0、**新增** economy-api 不引用 production 的扫描 116 文件 + pom，带正向对照；`EconomyData` 组件实测 31，旧 `hasSize(35)` 绊线更正）· **N24** 旧无效入口（`EconomyDayStepper` 只剩 5/7 参、`EconomySession` 只剩 1 个构造器、5 个旧类消失，每条带正向对照）· **N25** probe 成本（**64× 规模 ⇒ 仅 3.6×**；239µs→852µs；单次 39.9µs→2.2µs；**未加缓存**）· `D031P1HandCalcSettlementTest` 5 红 → 1 红（4 条逐值转绿）。
- **economy 64 红的分桶进展**：≥12 条属 §14.3 第 3 条点名的"必须改写"类；**同一根因（实证）**= 老夹具只把实例造在 economy 侧、从未落进 production 权威实例表 ⇒ runtime 实例表空 ⇒ 产出恒 0（bucket a）。
- ★ 记功：测试 owner 用**受控变异**排除假设（改坏 ⇒ 仍红 ⇒ 还原 ⇒ md5 逐字节一致）—— 变异不只当门禁，也当**归因工具**。

#### 14.16.2 裁定三十四（两个真 main 缺陷）→ 派 **B8** 修 main

1. **DEF-T2b-1（★★ 真缺陷 = 判据 N21「trade 路径」）**：`simos-economy/.../ModeMigrationSettlement.java:212-214` 按需建 `trade@hex` 定义只写会话覆盖层、**未调 `runtime.registerPlan`** 就在 `:1319` `createInstance` ⇒ **恒 `plan-unknown`**（`MerchantOnDemandTradeW7Test` 4 红）⇒ **"非城市格按需长出跑商"整条玩法不可用**。最小修法：照 `EconomyEntrySettlement.java:1103-1110` 的正确样板先 `registerPlan` + 判 `ProductionPlanOutcome.Rejected` 再建实例。
2. **DEF-T2b-2（★★ 真缺陷；阻塞一切"声明了周期投入的生产"）**：economy **确实扣到料**（实测 `inputOf=10000`），但 production 权威实例 `progressDays=0`、`cycleInputUsedMilli={}` ⇒ **该生产日根本没推进** ⇒ **毛产恒 0**（`MintProductionSettlementTest` 7 红 `投入 expected 4000 but was 0` + `D031P1HandCalcSettlementTest` 剩 1 红 + `D031P1AuthoritySwitchTest` 部分）。候选现场：`EconomySettlement.java:1651 clearDrawnProductionInputs` / `:1569,:1609 drawCycleInputsPartitioned` / 记录点 `:3903-3912` 与 `:3934-3937` / 消费点 `:4243 drawnProductionInputsOf`；嫌疑 = 协调器记录的那份 `cycleInputUsedMilli`（`:3911` 读分区副本）与累加点（`:6198-6201` 分区本地）**不是同一份**。
3. **DEF-T2b-3（待定位）**：`DeathDebtWriteOffTest` 3 红（`989 vs 999`、`102 vs 100`、删债累加器空）—— **先用 `git diff HEAD -- <file>` 三角定位**：命中 D-032 改动 ⇒ 回归必修；不命中 ⇒ 记为既有问题 + 证据。

#### 14.16.3 裁定三十五（economy 64 红的改写归属）→ **归测试 owner 续做**

- 归入测试 owner 续做范围（否则 T4 的 `clean verify` 永不可能 rc=0）；按 §14.15.2 三分桶处置。
- **顺序**：B8 修完 main ⇒ 测试 owner **原样复跑**（不改编据）⇒ 再按 a/b 桶逐类改写 ⇒ `test -pl simos-economy -am` **rc=0** ⇒ 继续 V3/V4/V6/V7/V8/N13–N17（这些判据在 DEF-T2b-2 修好前**不可能绿**：写了也只是恒 0 的假判据，故此前未写是对的）。
- 之后 **T3**（变异自证）→ **T4**（`clean verify` rc=0 + 前端计数 + 黄金对比逐处解释）。

#### 14.16.4 残余风险（如实记）

- `simos-app` 测试面 **179 文件一条未跑**（等 economy 绿）；占位断言清扫命中 **41 文件 / 44 个 `runtimeOf(...)` 调用点**，本轮只处置 3 处、**38 个文件未逐处复核**（优先清单在测试台账 §13.5）。
- ⇒ **T4 之前不得声称"经济侧接通"**；DEF-T2b-1/2 未修完前，跑商与"声明周期投入的生产"两类玩法在运行期不可用（这是当前 D-032 的**实际交付状态**）。

### 14.17 B8 实收、控制方自纠与测试阶段续做（控制方，2026-10-17）

#### 14.17.1 B8 实收

- 只动 **4 个 `src/main` 文件**、`src/test` 零改动：`time/ModeMigrationSettlement.java` · `time/EconomySettlement.java` · `time/LaborQueueBook.java` · `time/LaborQueueSettlement.java`。
- **DEF-T2b-1 已修**：`ModeMigrationSettlement.java:222-245`（新增）在 `:247 industries.put(...)` 与任何 `createInstance` **之前**逐条 `registerPlan`，`Rejected` ⇒ ERROR 具名 `MERCHANT_TRADE_INDUSTRY_REGISTER_REJECTED` + `IllegalStateException`（不降级），样板照 `EconomyEntrySettlement:1103-1110`。⇒ `MerchantOnDemandTradeW7Test` **11/0/4 → 11/1/0**（4 条 `plan-unknown` 全消）。
- **DEF-T2b-2 已修，但它是三层**（★ 控制方派单书把它写成单点，见 14.17.2）：
  1. `EconomySettlement.java:1574`：清零点从 `:1663`（现扣之后）**前移**到本日现扣之前（`:1568-1574`，`plantingDrawsFirst` 两支共用入口）。
  2. **真根因层**（派单未指到）：`LaborQueueBook.java:495` 新增 `cycleInputUsedMilliOf(EconomySession, ProductionProcess)`（"本生产日实扣量"的**唯一权威视图**：本 tick 现扣过 ⇒ 读会话那份；否则读实例那份，逐值不变），`:197 offer(...)` 改用该视图（`LaborQueueSettlement:300-310` 传会话），`EconomySettlement.reallocateLaborPartitioned` 分区副本（`:4046-4053`）镜像本日实扣量 ⇒ 修好"既有劳动配额被当'一点也用不上'整条删掉 ⇒ `contributions={}` ⇒ 规模 0、毛产 0"。
  3. 同源第三层：`EconomySettlement.java:4233` `driveProductionDay` 建步表改读**当刻权威候选态** `session.sheet().units()`（不再用日结算入口快照），否则"今天刚 `createInstance` 长出来的生产"整天漏掉。
- 判据兑现：`executeDay` 请求带**本日实扣**（会话实测 `{grain=10000}` 与 `ledger.inputOf=10000` 同一份）· `progressDays` 随日推进（第 2 天 `=1`）· 毛产手算逐值回来（`70000/49000/30000`）；**未**用"跳过经济侧扣料"的方式凑数、未放宽校验、未改期望值。
- **DEF-T2b-3 定位结论 = 测试面夹具欠账（不是 main 回归、也不是既有 main 缺陷）**：三条红全停在 `EconomySettlement.java:4511-4525`"该格没有 unit ⇒ `continue`"这一支；三角定位判据 `git diff HEAD -- EconomySettlement.java | grep "writeOffDebtsForDeaths|proportionalPrincipalAfterDeaths|debtWriteOffs|survivors"` ⇒ **唯一命中是上下文行**（`^ ` 开头）⇒ 算式与累加器**逐字节未动**（I14 成立）；正向对照：同型 `D031P1HandCalcSettlementTest` 补 `.withInstance(...)` 后 **8/0/0**。处置面归测试 owner。
- 真实读数：`test -pl simos-production -am` **rc=0 · 65/0/0/0**；`test -pl simos-economy -am` **rc=1 · 374/33/21 = 54 红**（起点 374/33/25 = 58 红 ⇒ **收敛 58→54**）；强制重编 rc=0；`spotless:check` rc=0（首次 2 needs changes ⇒ 限模块 apply 后复绿）。
- ★ 记功（返工处置正确）：第一版给私有 `reallocateLabor` 加参数 ⇒ `GovServiceCommitmentC7Test.java:485` 的**反射签名钉**当场 3 ERROR ⇒ 改用**保签名**的镜像稿（`b8-run2` 10/4/**4** → `b8-final` 10/4/**1**）。这证明"签名是测试面契约"这条纪律有效。

#### 14.17.2 ★ 控制方自纠（派单书对 DEF-T2b-2 的描述不完整）

- 我在 §14.16.2 把 DEF-T2b-2 写成"单点（清空位置）"，并猜"记录值与分区本地累加不是同一份"。B8 实测：**只改那一处不足以修好**（受控对照：仅改第一层时 `D031P1HandCalcSettlementTest` 仍 1 红）；真根因在**读源**（劳动分配/排队读实例旧值而非本日实扣量），且"两份值"经诊断**实际一致**（会话里就是 `{grain=10000}`）——**分裂发生在"谁在读"，不是值本身**。
- 纪律：**控制方派单书里的"根因/嫌疑"一律标为待核线索**；实现方有义务在交账里显式更正（B8 做到了）。

#### 14.17.3 测试阶段续做（Stage B2；同一测试 owner 角色）

1. **原样复跑**（判据不改）：`production` 期望 **65/0/0/0**；`economy` 期望 **374/33/21**；`D031P1HandCalcSettlementTest` 应 **8/0/0**、`MerchantOnDemandTradeW7Test` 应 **11/1/0**、`GovServiceCommitmentC7Test` 回到基线 **10/4/1**。
2. **按 §14.15.2 三分桶收 economy 其余 50 红**（a 桶夹具欠账：`DeathDebtWriteOffTest.defs()` / `MintProductionSettlementTest.mintDefs(...)` / `D031P1AuthoritySwitchTest` 三处补 `.withInstance(...)` 是最小改法；b 桶行为变更断言按新口径改写）⇒ `test -pl simos-economy -am` **rc=0**。
3. **补 B8 点名的判据**（ledger §B8-§10）：① `executeDay` 的 `finalInputsMilli` == 本日 `ledger` 实扣量（这条此前**只有间接覆盖**）；② `MerchantOnDemandTradeW7Test::busyToolShareIsNotLeasableSoTheMoveIsStillSkipped` 的断言改写。
4. 然后继续 **V3/V4/V6/V7/V8/N13–N17/N19/N20/N22/N23** + **G1–G4** + 占位断言清扫剩余 38 文件。
5. 再进 **T3**（变异自证）→ **T4**（`clean verify` rc=0 + 前端计数 + 黄金对比逐处解释）。

### 14.18 DEF-T2b-4（新真回归：`economy.Seed` 关系表被静默丢掉）与裁定三十六（控制方，2026-10-17）

#### 14.18.1 现场（测试 owner 报，控制方回代码 + `git diff HEAD` 双核）

- `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java`：
  - `:326` `Map<IndustryId, IndustryDefinitionView> industries = new LinkedHashMap<>();` —— **只 new、全方法从不 put**（实代码命中只有 `:326` / `:511` / `:514`）；
  - `:511` `if (unit == null || !industries.containsKey(unit.industry())) { continue; }` ⇒ **恒 continue**；`:514` `industries.get(...)` ⇒ 恒 null ⇒ `:509-533`（逐 unit 落 relation：显式 relation 或 regime 默认 relation）**整段死代码**。
- **三角定位命中**：`git diff HEAD -- EconomyPayloads.java` 显示 B5 删掉了 `for (JsonNode node : requireArray(entry, "industries")) … industries.putIfAbsent(...)`（旧 `entry.industries[]` 退役），**却留下读空表的守卫** ⇒ **D-032 回归（I14）**。
- **影响面（回代码核过）**：`simos-app/.../world/EconomySeeder.java:3868` 仍逐格发 `units[]`、`:4917` 显式发 `relation`（trade 那条）；B5 台账明写"载荷 `units[]` 降级为 **relation 声明** + 配额归一" ⇒ 设计意图里 relations 仍由本命令落账。⇒ **真世界创世后 relations 表为空**：补偿规则/地租/分成（regime 默认档与显式档）**静默不生效**，且坏 relation 形状不再被拒。
- 判据读数：`-Dtest=EconomySeedHandlerTest` = `26 run / 7 failures / 1 error`，其中 `aPayloadWithoutRelationDerivesTheDefaultFromItsRegime`（`relations()` 空）、`anExplicitRelationIsSeededValueForValue`、`relationOperatorAndResidualOwnerDefaultToTheUnitsOperator` 三条直指本缺陷；测试 owner **保持这三条红**（不改成迁就缺陷的断言），处置正确。

#### 14.18.2 裁定三十六 → 派 **B9** 修 main（一行 + 同类扫描）

1. **修法取"补写者"而不是"删守卫"**：在 `:429-443`（已用 `definitions.industry(industryId)` 取到 `IndustryDefinitionView industry` 并做过具名存在性核对）之后加 `industries.putIfAbsent(industry.id(), industry);`，**保留 `:511` 的守卫**（它是真实的 unit→方案存在性检查）。
2. ★ **同类扫描（纪律）**：B4/B5 删表时可能留下**其它"读者仍在、写者已删"**的局部集合 ⇒ 扫描 `src/main` 中"只被读、从不被写"的局部集合（`new LinkedHashMap<>()/ArrayList<>()/HashSet<>()` 后只有 `containsKey/get/contains/forEach` 而无 `put/add`），逐条给处置表。
3. 自证：`-Dtest=EconomySeedHandlerTest` 期望 **26/0/0**；`test -pl simos-economy -am` 报红数（应显著下降）；`test -pl simos-production -am` **必须仍 65/0/0**；强制重编 rc=0；`spotless:check` rc=0。
4. 交账后测试 owner 原样复跑那三条 + 继续 a/b 桶。

#### 14.18.3 立纪律（机械可查）

**删除任何表/组件的写入者时，必须同时清理或改写它的读者** —— 否则读者静默变成死代码/恒假守卫（本次即"填表循环被删、守卫留下"）。⇒ 每个"删除面"批次的交账必须附一张**读者-写者对照表**（谁写、谁读、删后读者怎么办）。

### 14.19 B9 落地、economy 报绿与控制方复核口径（控制方，2026-10-17）

#### 14.19.1 B9 的一行修复已在位

- `EconomyPayloads.java:446` 已有 `industries.putIfAbsent(industry.id(), industry);`（`:327` 的注释也写明了读者-写者关系）⇒ DEF-T2b-4 的关系表静默丢失已修；测试 owner 侧那 3 条判据当场转绿（其自报）。B9 仍在跑**同类扫描**（只读/只写局部集合）与自证，其交账另行复核。

#### 14.19.2 测试 owner 自报 economy 全绿 —— 控制方复核**暂不能确认**

- 其自报：`test -pl simos-economy -am` **rc=0 · 374/0/0/0**（起点 374/33/21 = 54 红 ⇒ 54 条全收）；`spotless:check` rc=0；只改 `src/test/**`。
- **控制方独立复跑（08:2x）⇒ rc=1，但失败在 `test-compile` 而非测试**：
  `D031P1HandCalcSettlementTest.java:[413,24] RecordingRuntime is not abstract and does not override abstract method mergeExperience(HouseholdId,long,HouseholdId,long) in ProductionRuntime`
  ⇒ 该文件 mtime = 08:18:51（**在其自报 08:17 读数之后**）⇒ 控制方撞上**在飞状态**（同一棵树并发写）。
- ⇒ **口径**：自报"绿"必须在**代码静止后**复跑才算数；本节暂记"**报绿待复核**"，以测试 owner 交账后的控制方复跑为准。（这也说明：并发写同一棵树时，任何一方的读数都可能只是瞬时态。）
- **第二次复跑（控制方，08:2x+）仍 rc=1，错误换成另一个方法**：`D031P1HandCalcSettlementTest.java:[413,24] RecordingRuntime … does not override abstract method reoperateInstance(ProductionUnitId, ActorRef, String)`；该文件 mtime **08:22:29**（仍在被改）⇒ 测试 owner 正在**逐个补齐**测试替身对 `ProductionRuntime` 的接口实现。
- ★ **这是"接口增长 → 测试替身"的欠账集中偿付**：`ProductionRuntime` 在 B1（`executeDay`/`createInstance`/`removeInstance`/`reoperateInstance`）与 B3（`mergeExperience`）/B6 等批次陆续加口，自实现该接口的替身（如 `RecordingRuntime`）必须同步 —— 各批次的"测试影响面"都列过这条，现在实付。**纪律**：接口加口的批次应在同一批把**main 侧唯一的实现 + 测试替身**一起对齐（替身在测试面，须由测试 owner 同步；派单书须点名"接口新增口 ⇒ 替身编译红"）。

#### 14.19.3 被删测试方法的控制方抽检（防止"靠删测试换绿"）

- 抽检口径：`git diff -- '*src/test/*'` 里 `-@Test` 计数 = **23**（tracked 文件），分布：`EconomyCodecTest` 7 · `ProductionEfficiencyBookTest` 5 · `ProductionEfficiencySettlementTest` 4 · `EconomyPolicyMigrationTest` 2 · `EconomyGmAdjustmentsOutputQuantityTest` 2 · `EconomyRoundTripTest` 1 · `EconomySeedHandlerTest` 1 · `S1Stage3TenancyTest` 1。
- 删除项归类（初判**多为合法**）：旧效率书（旧公式退役，A′/裁定）、旧组件计数与白名单（`changeSetHasExactlyThirtySevenComponents` / `whitelistIsExactlyTwelveSourceStateKinds`）、产出覆盖面（B2c/B5 退役）、旧档形状与旧策略表迁移（Alpha 作废）、`S1Stage3TenancyTest`（裁定二十五）。
- ★ **要求（交账时逐条核）**：每条删除必须在台账里写成"**被测能力按哪条裁定退役**"或"**已迁移到哪个新载体**"；旧效率书那 5 条对应的**黄金/余数不变量**必须确认已由新载体承接（覆盖缺口 **G4** 的关闭证据），不得随文件删除而消失。
- ★ 另有 `+@Test` = 0 的假象：**新增测试文件是 untracked**（不进 `git diff`），故新增量要以 `git status ??` + 文件清单计，不得用 diff 计数下结论。

### 14.20 DEF-T5-1（★★ 阻断级：three-powers 创世被拒）与裁定三十七（控制方，2026-10-17）

#### 14.20.1 现场（测试 owner 报，控制方回代码核过）

- 判据读数：`app.time.EconomyCycleHealthTest.oneSegmentInRealThreePowersWorldKeepsMoneyConservedAndMarketOpen` ⇒ ERROR `IllegalStateException: … OFFICE_UNIT_NOT_IN_PRODUCTION: unit=unit-office@3_0-HOUSEHOLD-hh-gov-gov-tp-silver industry=office@3_0`；同族 `GovAdminSalaryBudgetZ6Test` **11 ERROR**（`:1141`）、`Z7HungerTest`(2)、`Z7FleeTest`(1)、`GovToolsZ6Test`(2) 数值恒 0；`CrossProcessDeterminismTest` 子 JVM 首条 ERROR 同源。app 侧共 **15 类 / 25 F + 12 E = 37 条**红，其中 **~19 条**同此根因。
- 对照（证明是路径漏步、不是守卫坏）：`GovWorldBootstrap` 那条路的同一命令**成功**（日志 `PRODUCTION_INSTANCE_CREATE_APPLIED unit=unit-office@0_0…`）。
- 现场（控制方核）：`ThreePowersGovBootstrap` 在 `:636-643` 只 apply `UpsertIndustryHandler`（把 `office@<hex>` 方案立进权威 ✓），`:695-702` 直接 apply `EconomyUpsertGovUnitHandler`；**全文 0 处 `CreateInstanceHandler`**（控制方复核三处计数：`GovWorldBootstrap` 2 · `Shell` 1 · `ThreePowersGovBootstrap` **0**）。
- 正确样板在隔壁：`GovWorldBootstrap.java:562-570`（B2c 裁定十注释原文"**先**经 production 契约给这个 GOV 立 office 生产实例，**再**发 economy 侧…次序不可换"）。

#### 14.20.2 裁定三十七 → 派 **B10** 修 main

1. **在 `ThreePowersGovBootstrap.bootstrapGov` 的 `EconomyUpsertGovUnitHandler` 之前插一步** `production.spi.CreateInstanceHandler` + `ProductionCodec`，载荷与 `GovWorldBootstrap.createOfficeInstancePayload(spec, officeIndustryId, reason)` **同形**（instance id `unit-office@<hex>-HOUSEHOLD-<govHousehold>`、plan `office@<hex>`、operator `HOUSEHOLD:<govHousehold>`、modeKey 同 office 模板）。**次序不可换**；`OFFICE_UNIT_NOT_IN_PRODUCTION` 一字不改（fail-closed 正确）。
2. **同类扫描**：凡 main 里 apply/调用 `EconomyUpsertGovUnitHandler` / `EconomyGovUnitUpserts` 的路径，必须在前序**同一批**里有实例创建；逐条给"路径 → 是否已建实例 → 处置"表（含 test 世界装配面里的等价路径）。
3. **顺手修 main 侧陈旧工具描述**：`simos-economy/.../tools/write/EconomyAdjustTool.java:102` 仍写"adjustment 白名单（12）"，而 main 实测 `ADJUSTMENTS` **7** 项 + `MOVED_ADJUSTMENTS` **5** 项 ⇒ 描述改为与实现一致（这是 main 的工具描述面，不是测试面）。
4. **自证**：`-Dtest=EconomyCycleHealthTest`（+ `GovAdminSalaryBudgetZ6Test` 若快）应转绿；`test -pl simos-economy -am` 与 `test -pl simos-production -am` **不得因本批变红**；强制重编 rc=0；`spotless:check` rc=0。

#### 14.20.3 并发与读数口径（重申，写给后续批次）

- 本树**同时有多个写者**（B9 的同类扫描仍在跑、测试 owner 在改 `src/test`、邻居 session 只写文档）⇒ **任何一方的读数都带时刻**，跨时刻不可相减；自报"绿"一律**待控制方在静止树上复跑**才算数（economy 的 377/0/0/0 即属此类）。
- app 侧 **a 桶计数锚点**（`ShellAssemblySmokeTest` GM 桶 180→181 · `McpPortTopologyTest`/`McpServerTest` 178 · `McpCoverageTest` handler 148 · `ToolClassAuditAssemblyTest` 193→194 · `SimosToolsTest` 8 处锚点）与夹具欠账（`MerchantTradeScaleAnchorCouplingTest` 缺 production 切片 · `ProductionRuntimeSeedSmokeTest` 旧 `industries` 断言 · `D031P1PlanOnlyAndBatchAtomicityTest`）**归测试 owner**，在 B10 定稿后按实测改。

### 14.21 B9 实收、`birthsToday` 裁定与 T3 变异纪律（控制方，2026-10-17）

#### 14.21.1 B9 实收

- 一行修在位：`EconomyPayloads.java:446` `industries.putIfAbsent(industry.id(), industry);`，`:519` 守卫原样保留；并**改掉两处被 B5 写反的注释**（`:326-328` 声明处、`:566-570` 原文写"载荷里 `industries[]` 仍然读"——正是它掩盖了本回归）。md5 `430f8c78…` → `7e0aea1a…`；写入面自证 = 只此一个 main 文件。
- 真实读数（当刻）：`EconomySeedHandlerTest` **26/0/0**（其期望）· economy **377/0/0/0 · rc=0**（B8 末 374/33/21 ⇒ 54 红全部收口）· production **66/0/0/0 · rc=0**（66 = 65 + 测试 owner 新增 1 条，production 不依赖 economy，与 B9 无因果）· 强制重编 **rc=0**（19/19，1160 文件真编译）。
- `spotless:check` **rc=1**，但违规 **100% 在 `src/test/**`**（测试 owner 当刻在改的两个文件）；`src/main` 零违规已用"禁缓存真跑"自证。★ **未跑 `spotless:apply`** —— 那会改写别人正在改的测试文件，硬约束禁止，处置正确。⇒ main 侧对 `clean verify` 无阻塞；测试面 apply 由测试 owner 做。
- ★ **同类扫描的质量**：扫 **3298** 条局部集合声明 → 29 条"读-无写"候选 → **真死代码仅 1 条**（见 14.21.2）；误报 2 条（写者经别名 `target`）；合法 26 条（逐条给写者 `file:line`：被调方填表 25 + 有意空表 1）。★ **扫描器判别力自证**：对 **B5 旧快照（缺陷态）** 跑 ⇒ 命中 `EconomyPayloads:317 industries … NO-WRITER`；对本树（修后）⇒ **0 命中** —— 这是"护栏自证"的正确形态（既证明能抓，也证明抓不到假阳性）。

#### 14.21.2 裁定三十八（`EconomySettlement:1347 birthsToday` 死读）→ **记为既有死代码，不阻塞，另批清理**

- 事实：读点 `:3186` 恒 0；**写者从来不存在**（`git show HEAD:` 同一形状 ⇒ **非 B4/B5 回归**）；行为等价于同段 `dayDeaths = 0L`；真出生由 `withLifecycle`（`:4571`/`:4840`）另一条路写 `FlowRow.births` ⇒ **无功能回归**。
- 裁定：**不并入当前批次**（避免在测试阶段混入无关 main 改动）；记入清理清单（删表 + 读点显式 0 + 抄 `dayDeaths` 注释），在测试阶段收口后单独一批处理。

#### 14.21.3 ★ T3 受控变异纪律（B9 报的并发风险）

- 事实：测试 owner 于 **08:26:59 / 08:27:15** 对 `src/main` 做过 T3 受控变异（`ProductionRuntimeEngine.java` / `EconomyData.java`，两次都以当场红结束）；同期 B9/B10 在跑 ⇒ B9 明确写"**我没有变异前 md5 快照，不能逐字节证明他人还原**"。
- **裁定**：
  1. **T3 变异必须串行**：同一时刻树里**只允许一个 main 写者**；变异窗口内**不得**有其它实现批在跑（否则彼此的读数都作废）。
  2. **每次变异必须留三件套**：`变异前 md5` → `改坏 ⇒ 当场红的原始输出` → `还原后 md5`（**逐字节一致**）。缺任一件即不得计为"变异自证完成"。
  3. 变异**只许**在测试 owner 自己的窗口内进行，且还原后必须**重跑一次受影响模块的测试**留证（证明还原态可用）。
- ⇒ 后续派单书与测试台账都必须按这三条记录（当前 T3 请补齐 md5 三件套）。

### 14.22 Stage B2 实收、裁定三十九/四十与 B11（控制方，2026-10-17）

#### 14.22.1 实收（明确三分）

- **通过**：`production` **66/0/0/0 rc=0** · `economy` **377/0/0/0 rc=0**（起点 374/33/21 ⇒ 54 红全收，+3 新判据）· `spotless:check`（economy/production）rc=0 · **原样复跑**与 B8 交账逐值一致 · **4 条新判据带真实读数**：① G3/B8-§10① `executeDay` 请求 `finalInputsMilli == 本日 ledger 实扣量`（动态代理观测，手算 10,000 毫粮）② V7/N16 政策键具名拒 + 指路 + 零状态变化（新文件 `EconomySetEconomyPolicyHandlerTest`）③ V6/N15 用户例式 **30**（含余数腿 30/650）④ W7 那条**更正了 B8-§7**（夹具把 busyUnit 塞进"从不落表"的 economy 收件箱 ⇒ 落进 production 后**原断言逐条成立**）。
- **T3 变异 2/7 完成且带三件套**：M1（`probe` 写实例状态）⇒ `ProductionRuntimeProbeTest` **9/2/0 当场红**，还原 md5 `5120d500…` **逐字节一致**；M2（给 `EconomyData` 加回 `withIndustries` = 新增第二权威写口）⇒ **两条独立扫描判据同时红**，还原 md5 `0377fd46…` 一致。⇒ §14.21.3 的三条纪律**已被遵守**（撤销我此前的担心）。
- **失败**：app 面 **15 类 / 37 红**（c 桶 DEF-T5-1 ~19 条 = three-powers 创世，B10 修中）· **T4 `clean verify` rc=1，失败点是 `simos-production` 的 SpotBugs（26 bugs）** ⇒ `SimosaApp` 及下游 SKIPPED ⇒ 前端门禁与黄金测试**没被 verify 走到**。
- **没跑**：前端门禁计数（日志 `frontend-gate` 0 命中 ⇒ **不是"通过"**）· T3 的 M3–M7 · V3/V4/N14/V8/N17/N13/N19/N22/N23-economy/G1/G2/G4 · 占位清扫剩余文件 · 黄金 530 条差异只做类别级归因（脚本只打前 200 条）。

#### 14.22.2 裁定三十九（DEF-T5-4：SpotBugs 26 bugs）→ 派 **B11**

- 现场：`simos-production` **26 bugs** —— `EI_EXPOSE_REP` 19 · `EI_EXPOSE_REP2` 3 · `NP_NULL_PARAM_DEREF_NONVIRTUAL` 1 · `UC_USELESS_CONDITION` 1（`ProductionRuntimeEngine:514-515`）· `UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR` 1（`:1312`）。
- 修法（按本仓既有形制）：contract record 的 compact 构造器**就地冻结集合**（照 `MarketTable.hexes` / `EconomyMeta` 的写法：逐字展开 `LinkedHashMap` + `unmodifiableMap`，**不用 `Map.copyOf`**）；两条引擎侧逐条处置（能改就改）。
- ★ **不许**用 `@SuppressFBWarnings` / 排除清单把问题掩盖过去；若某条确认是分析器误报，必须在账本写明"为什么是误报 + 依据"，并把它作为**孤立例外**列进交账。
- 自证：`tools/mvn-lock.sh -pl simos-production -am verify -DskipTests` ⇒ **rc=0（0 bugs）**（★ 直接用 `-pl X -am spotbugs:check` 会因上游构件未装而失败，必须走 lifecycle）；`test -pl simos-production -am` 仍 **66/0/0**；`test -pl simos-economy -am` 仍 **377/0/0**；强制重编 rc=0；`spotless:check` rc=0。

#### 14.22.3 裁定四十（黄金对比口径）→ **接受为"已解释差异"，但终稿要求补全**

- 现状：`GoldenV2ExporterTest` rc=0（产物 1,721,214 字节）→ 对 P0 基线：共同叶子 **32791** · 仅 P1 **21** · 仅 P0 **66** · 逐值不同 **530** · P0 无对照 **45**。类别：`economy.units.*` **167** + `actor.accounts.*` **27** + `economy.allocations.*.laborMilli` **6** ⇒ 归因到 DEF-T2b-2 修复（本日实扣真到达 production）+ 效率口径（N1/N3）+ I8/I9 + V7/N16；66/21 单侧键 = **结构迁移**（实例权威迁 production，I1），非业务差异；**裁定二十六的两个键两侧都不出现** ⇒ 不构成未解释差异。
- 裁定：**上述归因口径接受**（差异是故意的、且有判据载体）。但 T4 终稿必须：① 把 530 条差异**按类全量落盘**（不许只打印前 200 条）；② 每类标注**判据载体**（哪个用例的手算值）；③ 单侧键逐键给"结构迁移"或"能力退役"的依据。

#### 14.22.4 并行口径澄清

- §14.21.3 的"同一时刻只允许一个 main 写者"**只约束 T3 受控变异**（临时改坏行为）。**文件不相交**的普通修复批可并行，但：① 读数一律标时刻；② 每批交账后由控制方在**静止树**上统一复跑；③ 同一批不得既改 main 又改 test。

#### 14.22.5 剩余清单（交给测试 owner 的下一轮）

app 13 个计数锚点（main 定稿后按实测重列）· `MerchantTradeScaleAnchorCouplingTest`/`ProductionRuntimeSeedSmokeTest`/`D031P1PlanOnlyAndBatchAtomicityTest` 夹具 · V3 · V4/N14 · V8/N17 · N13 · N19 · N22 · N23-economy 两处 · G1/G2/G4 · 占位断言清扫剩余文件 · T3 的 M3–M7 · 前端门禁计数 · T4 `clean verify` rc=0 · 黄金全量落盘。

### 14.23 B10 实收（three-powers 创世修复）与裁定四十一（控制方，2026-10-17）

#### 14.23.1 实收

- 只动 `src/main` **5 个文件**、`src/test` 零改动。核心修复：`ThreePowersGovBootstrap.java:704-713` 在 `EconomyUpsertGovUnitHandler`（`:714-720`）**紧邻之前**插 `CreateInstanceHandler`+`ProductionCodec`；`:963-984` 新增 `createOfficeInstancePayload`（与 `GovWorldBootstrap:784-809` **同形**：plan `office@<hex>` · unit `ProductionUnitId.idOf(...)` · operator `{HOUSEHOLD, hh-gov-<govId>}` · modeKey `OFFICE_REGIME` · location `ofPlan` · reason）。**`OFFICE_UNIT_NOT_IN_PRODUCTION` 一字节未改**（fail-closed 保持）。
- 读数（其本人，带时刻）：强制重编 rc=0（16 模块真编译 1326 文件）· **`EconomyCycleHealthTest` 1/0/0 rc=0**（日志内 **3 条 `PRODUCTION_INSTANCE_CREATE_APPLIED`**，unit 与修前拒因逐字相同）· **`CrossProcessDeterminismTest` 1/0/0 rc=0**（同根因判据，其自加）· economy **377/0/0/0** · production **66/0/0/0** · 全树禁缓存 `spotless:check` rc=0。
- 同类扫描：main 侧**能成功写** `economy.UpsertGovUnit` 的路共 **3 条，如今三条都在前序同批建了实例**（`GovWorldBootstrap:564-577` 样板 · `ThreePowersGovBootstrap:704-720` 本批补 · `EconomyUpsertGovUnitTool:257-262`）；`Shell:935/944` 与 `SimosToolSource:1752` 是**注册**不是调用（不触发守卫）。
- ★ **如实区分**：`GovAdminSalaryBudgetZ6Test` 仍 **12/0/11** —— 根因在**测试面夹具**（`:1141/:1149` 直传 `Map.of()` 空实例表、不经 main 创世）⇒ **不派 main**，归测试 owner 补 `ProductionData.withInstance(...)`。
- 顺修 main 侧陈旧计数描述 **5 文件 9 处**（`EconomyAdjustTool:102/117/133/158` · `CatalogTool:635` · `EconomyGmAdjustments:177/183-185/221` · `EconomyGmAdjustHandler:26-27/32`）按实测 `ADJUSTMENTS=7` / `MOVED_ADJUSTMENTS=5` 对齐（纯描述面）。

#### 14.23.2 裁定四十一（两处移交）

1. **`SimosToolsTest.java:1151` 断言 `contains("白名单（12）")` 已过期**（main 改成 7）⇒ **归测试 owner**（§14.20.3 已把该文件锚点归它）。
2. **`GovWorldBootstrap.java:89-100` 类注的命令序列仍缺 `production.CreateInstance` 一行**（B10 的文件所有权不含它）⇒ 记为**注释级清理项**，随测试阶段收口后的小批处理（与 `birthsToday` 同批）。

#### 14.23.3 观察：T3 变异与 spotless 的相互干扰

- B10 报：中途 `spotless:check` rc=1，违规 4 文件全在 `simos-production/src/main/.../contract/*` + `ProductionRuntimeEngine`，**mtime 08:36:47 → 08:39:56 持续前进** ⇒ 是**他方（测试侧）正在做 T3 受控变异**的瞬态，其落定后全树禁缓存 rc=0。
- ⇒ 印证 §14.21.3/§14.22.4：**变异窗口内其它批的 spotless/测试读数一律作废**；变异方必须留"前 md5 / 当场红 / 后 md5"三件套（测试 owner 已对 M1/M2 补齐，控制方已逐字节复核通过）。

### 14.24 B11 实收、裁定四十二/四十三与 T4 解锁（控制方，2026-10-17）

#### 14.24.1 实收

- `tools/mvn-lock.sh -pl simos-production -am verify -DskipTests`：**26 bugs → 0**（`[INFO] BugInstance size is 0`，两次复跑一致）· `test -pl simos-production -am` **66/0/0/0** · `test -pl simos-economy -am` **377/0/0/0** · 强制重编 rc=0 · `spotless:check -pl simos-production -am` rc=0。
- **处置全部是真修，零 `@SuppressFBWarnings`/零排除清单/零关检查**（grep 自证 0 命中）：
  - `EI_EXPOSE_REP` 19 + `EI_EXPOSE_REP2` 3：compact 构造器**就地冻结**（逐字展开 + `Collections.unmodifiableMap/Set`，嵌套内层各自冻，**不用 `Map.copyOf`**），随内联删 9 个私有 helper（留着会立刻变 UPM）。
  - `UC_USELESS_CONDITION`（engine:514-515）：**真阳性** ⇒ 删恒假死分支；★ 原位留证明：**不得**"修"成 `evaluation.inputScale()==0`，否则**每个不吃原料的方案**都被判 `input-short`（回归）。
  - `NP_NULL_PARAM_DEREF_NONVIRTUAL`（`ProductionCreationRequest:44`）：**真阳性** ⇒ 删 4 参过渡构造器（其注释写"B 批删除"、全仓 0 调用点、传 null location 必抛）。
  - `UPM_UNCALLED_PRIVATE_METHOD`（`RestrictedExpression:283`）：**误报**（调用点在同文件 6 个**嵌套类**里，UPM 只看本类调用图）⇒ 按同文件 `MulDiv` 既有写法**内联 6 处**（IAE 类型与消息逐字节不变）。
  - `UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR`（engine:1312）：**误报**（`condition` 的合法空态="未开启 tick"）⇒ **tick 条件改显式入参**，字段解引用回到守卫内。
- ★ **控制方口径更正（自纠）**：§14.22.2 与我给 B11 的摘要写的是"**5 类 25 条**"，当刻实际 **6 类 26 条**（漏了 `UPM_UNCALLED_PRIVATE_METHOD` ×1）。⇒ 纪律重申：**派单书里的缺陷枚举一律标为待核线索**，实现方须以自己跑出的完整清单为准（B11 做到了）。
- ★ **B11 的格式自证方法记功**：`spotless:apply` 前后对 4 个类做 `javap -p -c` 抹行号比对 ⇒ **逐指令一致**（415/676/426/3201 行）⇒ 证明"只动格式"。此法可复用。
- ★ **跨批更正**：B10 把 08:38:40 那次全树 `spotless:check` rc=1 记成"邻居 T3 变异瞬态"，实为 **B11 自己 apply（08:39:56）之前的中间态** ⇒ **不存在"T3 变异残留在 production"** 这一风险项（M1/M2 的还原亦已由控制方逐字节复核）。

#### 14.24.2 裁定四十二（两处"删代码"是否触碰"不许删守卫"红线）→ **不算，批准**

- `engine:514-515` 的恒假分支：**从未拒绝过任何输入**（前置守卫已保证 `inputScale != 0`，且 raw 与 `evaluation` 的 `inputScale` 是同一函数）⇒ 它不是守卫；
- `ProductionCreationRequest` 的 4 参过渡构造器：**任何输入都必然抛**（null location）⇒ 也不是守卫。
- ⇒ 两者删除**不触碰红线**，且是分析器要求的最小修法。**条件**：此类删除必须附"**前置条件/可达性证明**"（B11 已给：上面两条 + 内联 6 处的 IAE 逐字节不变）。今后同类删除一律按此形制交账。

#### 14.24.3 T4 解锁与静止树复跑

- `clean verify` 的 **production 段已解锁**（SpotBugs 0）。⇒ 下一步 T4 的阻碍只剩 **app 测试面**（夹具 + 计数锚点）。
- 控制方正在**静止树**上复跑三模块（`verify -DskipTests` / production `test` / economy `test`），结果落 `control/`。

### 14.25 最终测试轮实收、裁定四十四~四十六与 B12（控制方，2026-10-17）

#### 14.25.1 实收（测试台账 §15）

- **app 面**：33 红 → **2 红**（仅 `Z7HungerTest`，= DEF-T6-1）；`Tests run: 1006 / 2 F / 0 E / 7 skipped`。15 条逐条处置（12 类夹具 + 锚点按实测重列：GM 桶 **181** · catalog **149** · GM_ONLY **133** · 并集 **194** · 磁盘 `*Tool.java` **182** · 窄写类 **67** · `白名单（12）→（7）`）。
- **T3 五条（M3–M7）全部完成，三件套齐全、零"等价存活"**：M3 解锁衰减重判 ⇒ I7 判据当场红 · M4 `templateFor` 留回 ⇒ 扫描判据红 · M5 漏 actor ChangeSet ⇒ app 1006/**4**/0（国库粮 `was 1000000`、汇款 `expect 0/was 106271844`）· M6 重复计经验 ⇒ 同 tick 归并/顺序无关两条红（`expect 400/was 350`）· M7 关 I8 回填 ⇒ `LaborQueueBackfillAcceptanceTest` 两条红；每条都有**前/后 md5 逐字节一致 + 还原后重跑**。
- **前端门禁**：直跑同一脚本 = **412/412 · rc=0**；但 `clean verify` 里 SimosApp SKIPPED ⇒ verify 日志 0 命中（**不是通过**）。
- **黄金对比**：全量 653 行落盘（不截断）；530 逐值差异 = `units.cycleInputUsedMilli` **314**（比值直方图恰好 2.000×163 / 4.000×163 = 效率 baseScale→finalScale）+ `allocations.laborMilli` **114**（I8+I9）+ `accounts` **27**；每类挂判据载体；单侧键三分（结构迁移/能力迁移/业务差异）；**裁定二十六两键两侧 0/0**。
- **T4 `clean verify` = rc=1**：13 SUCCESS（**含 ProductionSimos ✓**）· **EconomySimos FAILURE（SpotBugs 5 bugs）** · 5 SKIPPED（含 SimosApp）。⇒ 失败点已从 production 挪到 economy，**这就是最后两道关**。

#### 14.25.2 裁定四十四（DEF-T6-1：N22 串行推进 ≠ 一次多日推进）→ **确认 c 桶真 main 缺陷，不改判为"测试口径"**

- 读数（不得改编据）：`Z7HungerTest:101` expect **700**/was **850**（第 2 天不再产生粮缺口）· `:163` `advance(0,2)`=**700** vs `advance(0,1)+advance(1,2)`=**850**。
- 判据 N22 明文要求"单次跨多日与拆段推进一致" ⇒ 现状违反**确定性/等价性不变量**。机理候选：`PopulationEconomyTimeParticipant` 的"上一日累计/上一日 tick"基线只活在**进程内**（`:645` 入口拍一次 · `:784` 日循环尾刷新 · `:2038-2050` 差分 · `:1227` finish），未随 revision 落盘 ⇒ 拆段/重启后基线错位。
- **修法要求**：把"上一日累计/上一日 tick"落进 `EconomyData`（或 meta）随 revision 持久化，日循环只从 state 读；**不得**通过放宽/改写 `Z7HungerTest` 断言来"修复"。

#### 14.25.3 裁定四十五（DEF-T6-4：economy SpotBugs 5）→ 派 B12 一并修

1. `EconomyClearRegionHandler:269` `DLS_DEAD_LOCAL_STORE`（`targetHexKeys`）⇒ 删死赋值，**不改拒绝语义**。
2. `EconomyGmAdjustments:1162-1166` `UPM_UNCALLED_PRIVATE_METHOD`（`optionalVersion`）⇒ 删（同 B11 的 UPM 处置：若确认误报则内联并留证）。
3. **`EconomyDayStepper:317` 与 `EconomySession:153` 的 `EI_EXPOSE_REP`（返回注入的 `ProductionRuntime`）⇒ 裁定为"登记孤立例外"**：返回的是**注入的契约端口接口**（economy 调 production 的唯一通道）—— 防御性拷贝无意义（拷贝掉就不再是同一个权威），且调用面限于本模块域代码。**要求**：在账本列明"为什么不是缺陷 + 调用面 + 依据"，用**具名注解**登记（不得静默 suppression），并在交账里单列。
4. `EconomySettlement:6610-6621` `UPM_UNCALLED_PRIVATE_METHOD`（`unitsOfHouseholds`）⇒ 删，或按 §14.18.3 出**读者-写者对照表**后处置。

#### 14.25.4 裁定四十六（T4 口径）→ 修完 B12 后由测试 owner 原样复跑

- 复跑期望：`clean verify` **rc=0**，反应堆 18/18 SUCCESS（含 SimosApp），**verify 日志里出现 `frontend-gate` 计数（应 412/412）**，`shaded-jar-guard` 首次执行且过。
- 黄金：**全量 653 行差异表落盘**已达标（不截断）；`530 条逐行←代码行`的 provenance 归因**本轮不做**（需导出器带 provenance，属 U10 面），记入未做。
- **T4 rc=0 之前不得声称 D-032 接通**。

#### 14.25.5 剩余未做（具名，交终稿核对）

G1 · G2 · V4/N14 的"工资/租金/分成按实际贡献"腿 · N13 专用载体 · N23-economy 两处逐行重核 · 占位断言清扫其余文件 · 前端门禁在 verify 流程内的那一次 · `clean verify` 的 simos-app 段（含 `shaded-jar-guard`）· 黄金逐行 provenance · `birthsToday` 死读清理 · `GovWorldBootstrap:89-100` 类注缺行。

### 14.26 ★ 控制方自纠（N22 机理）与测试面覆盖缺口（控制方，2026-10-17）

#### 14.26.1 B12 owner 的插桩证据（推翻 §14.25.2 的候选机理）

- 事实 1：拆段第二次 advance 入口读到 `previousCumulativeGrainUnmet[hh-unit:gov-central] = 166`（来自 state 的 FlowRow）⇒ **基线确实已随 revision 落盘** ⇒ 我 §14.25.2 写的"基线只活在进程内、未落盘"**是错的**。
- 事实 2：`advance(0,2)` 的 day2 实例 `progressDays=1`（同一会话内累加）；而 `advance(0,1)+advance(1,2)` 的 day2 入口**全部实例仍 `progressDays=0`**。
- 事实 3（真正的机理）：`EconomySettlement` 用 `progressDays==0` 判"新周期第一天" ⇒ 拆段 day2 把 OFFICIAL 的 FlowRow **整行归零** ⇒ `cumulative` 只剩当天 unmet(167)，基线 166 ⇒ `167 < 166` 不成立 ⇒ `daily = 167−166 = 1`（断顿比例 5‰）⇒ 跌幅 0 ⇒ satiety 停在 850；而一次两天推进 day2 不复位 ⇒ `333−166=167` ⇒ 跌 150 ⇒ 700。**这就是 N22 不等价的全部机理。**
- 事实 4（为什么拆段 day2 的 `progressDays` 是 0）：**测试夹具 `GovZ6WorldFixture.applyChangeSet`（`simos-app/src/test/java/io/mosire/simos/app/testing/GovZ6WorldFixture.java:208-250`）的 switch 只有 economy/social/actor/gov/unit，没有 `production` 分支，`default -> null` ⇒ `moduleChanges` 里的 production ChangeSet 被静默丢弃**（`StateRunner:196-203`）。main 侧 `PopulationEconomyTimeParticipant:1315-1320` 的 `ProductionChangeSet.between(...)` 本身是对的。

#### 14.26.2 裁定四十七（B12 的 main 修法口径）→ **批准，但必须由测试面修复前提**

- B12 打算把"日缺口 = 跨周期累计差分 + `cumulative < previous` 复位启发式"换成**结算自己逐日产出的事实**：`stepper.drainPendingHouseholdGaps()` → `EconomyHouseholdGap.rationGapMilli()/rationNeedMilli()`（`EconomySettlement.reportHouseholdGaps:9700+` 明写"当日缺口/当日需求"）。
- **批准理由**：① 判据值（−150‰/日、+20‰/日、断顿比例）一字不改；② 非复位日与旧算法**数学恒等**（旧值即当日 unmet）；③ 消除了跨日/跨 advance 的瞬态记忆依赖，正好兑现 `:703` 注释里"分段等价"的硬前提；④ 唯一行为差异就是"复位日且当日 unmet ≥ 上一累计"这条错算。
- **附加要求**：修完必须由测试 owner **原样复跑** `Z7HungerTest`（断言不改）+ 全 app 套件 + economy/production 套件；黄金对比若有变化必须逐处解释。

#### 14.26.3 ★★ 裁定四十八（测试面覆盖缺口：夹具静默丢弃 production ChangeSet）→ **必须修，且它污染了 app 面绿的可信度**

- 性质：`GovZ6WorldFixture.applyChangeSet` 没有 `production` 分支 ⇒ **app 级测试在跨 revision 时看不到 production 状态推进** ⇒ 一切"production 跨 revision 等价性"的 app 级判据（含 N22、I8/I9/I10 的 app 腿）**都是在坏夹具上测的**。
- 裁定：**测试 owner 必须补 `production` 分支**（用 `ProductionSnapshot`/`ProductionChangeSet.apply`，与 `StateRunner` 对其它模块的形制一致），并**重跑全 app 套件**；补完后 `Z7HungerTest` 两条断言应在**修好的 main**上原样通过。
- ★ 纪律（新）：**任何"世界夹具/测试 harness"对模块变更集的 switch，都必须覆盖全部 17 个模块**；少一个分支 = 静默丢弃变更集 = 该模块的一切跨 revision 判据失效。此条加入测试 owner 的收尾检查清单。

#### 14.26.4 其余（DEF-T6-4）进展与口径

- B12 按 §14.25.3 处置中：#1/#2/#5 为真死代码/死赋值（删）；#3/#4 用**具名 `@SuppressFBWarnings` 登记孤立例外**（返回注入的契约端口，防御性拷贝无意义）—— **口径确认**：具名例外允许，但账本必须写明"为什么不是缺陷 + 调用面 + 依据"，交账单列。

### 14.27 B12 实收、裁定四十九与 T7（终局轮）范围（控制方，2026-10-17）

#### 14.27.1 实收

- **DEF-T6-4（economy SpotBugs 5→0）**：`verify -DskipTests -pl simos-economy -am` **rc=0**（`BugInstance size is 0`，XML `total_bugs='0'`）—— #1 删死局部 `targetHexKeys`（拒绝语义不动）· #2 删零调用私有方法 `optionalVersion` + 哨兵 `VERSION_ABSENT` · #5 删零读者 `unitsOfHouseholds` 并原位留**读者-写者对照表**（读者 0/写者 0/替代 `SettlementIndex#unitsOfHousehold`）· #3/#4 具名 `@SuppressFBWarnings` + justification（契约端口，接口无拷贝契约，防御副本会让 `beginTick()/changeSet()` 不再是同一份权威；调用面 Session 10 处 / Stepper 0 处）。无排除清单、无关检查、无 POM。
- **DEF-T6-1（N22）**：取消跨日基线，日缺口改取**结算当天交出的 outbox**（`rationGapMilli` = 当日 `unmetToday[grain]`）；判据值（−150‰/日、+20‰/日、断顿比例）**一字未改**；**不新增任何状态字段**（不改 `EconomyData/meta/ChangeSet/Codec/往返用例`）。读数：economy **381/0/0** · production **66/0/0** · **`Z7HungerTest` 3/0/0（两条断言原样）** · 邻接 `Z7FleeTest`+`GovToolsZ6Test` **25/0/0** · 强制重编 rc=0（18 模块真编译）· 全树 `spotless:check` rc=0（apply 只动 3 个 main）。
- ★ **B12 的探针再次推翻我的 §14.25.2 候选**（与 §14.26.1 一致）：基线本来就落盘；真机理是**测试夹具静默丢弃 production ChangeSet** ⇒ 拆段 day2 实例 `progressDays=0` ⇒ 结算判"新周期第一天" ⇒ FlowRow 归零 ⇒ 差值错算。

#### 14.27.2 裁定四十九（B12 的偏离）→ **批准并作废我 §14.25.2 的"落盘基线"要求**

- B12 判定"落盘基线"方案会给同一个量造**第二权威**、且仍要猜复位 ⇒ 改为**取消基线、用当天事实**。
- **批准**：这比我的要求**更强**（无第二权威、无跨 advance 瞬态记忆，正好兑现 `:687-688` 的 B2 硬前提）；我的"落进 EconomyData/meta"要求**作废**（它建立在错误的机理假设上）。
- ★ 自我纠正入账：**控制方的机理假设连续两次被实现方的插桩推翻**（§14.25.2 → §14.26.1 → 本节）⇒ 纪律再次确认：**控制方派单书里的"机理/根因/修法方向"一律是待核假设**，实现方有义务用插桩/读数校正并在交账里显式更正（B12/B11 都做到了，这是好实践）。

#### 14.27.3 裁定五十（`EconomyDayStepper.productionRuntime()` 0 调用）→ 暂留具名例外，列清理项

- 该访问器当前 **0 外部调用**；但具名例外已登记且论证充分 ⇒ **本批不推翻**（避免 T4 前再动 main）。
- 记入清理清单：**若最终确认无用则删**（与 `birthsToday` 死读、`GovWorldBootstrap:89-100` 类注缺行同批处理）。

#### 14.27.4 T7（终局轮）范围

1. **修测试面覆盖缺口（裁定四十八）**：`GovZ6WorldFixture.applyChangeSet`（`simos-app/src/test/java/io/mosire/simos/app/testing/GovZ6WorldFixture.java:209-251`）补 `production` 分支（`ProductionSnapshot`/`ProductionChangeSet.apply`），**并审计该 switch 是否覆盖全部可能出现在 `moduleChanges` 里的模块**（少一个分支=静默丢弃）。
2. **重跑全 app 套件**（1006 条）并三分桶——★ 补夹具后**可能出现此前被掩盖的新失败**，照实报、按 a/b/c 处置（c ⇒ BLOCKED），**不许**为绿放宽断言。
3. 重跑 economy **381** / production **66**（不得因夹具改动变红）。
4. **T4 终局**：`tools/mvn-lock.sh -pl simos-app -am clean verify` ⇒ **rc=0**，反应堆 **18/18 SUCCESS**；★ **verify 日志里必须真出现 `frontend-gate` 计数（应 412/412）与 `shaded-jar-guard`**（早退不算）。
5. **黄金重导重比**（B12 的 hunger 修法可能影响 satiety 值）：全量落盘 + 逐类归因（沿用 T6 的 653 行表 + 新差异逐条解释）。
6. 交账明确三分；终结判据同 §14.7。

### 14.28 ★ 控制方终局验收（2026-10-17，T4 达成）

#### 14.28.1 控制方独立跑的终局门禁

```bash
tools/mvn-lock.sh -pl simos-app -am clean verify
⇒ FINAL_VERIFY_RC=0 · BUILD SUCCESS
⇒ 反应堆 18/18 项目模块 SUCCESS（SKIPPED 0 / FAILURE 0）
⇒ app Tests run: 1008, Failures: 0, Errors: 0, Skipped: 7
⇒ [frontend-gate] OK tests=412 pass=412 fail=0
⇒ [shaded-guard] 结论：OK（8121 个条目；7 条运行期类 + Main-Class 全在）
```

- 边界同时核过：HEAD `03faf0a3` · `stash@{0}` 未动 · **09:56:51 后 `src/main` 零改动** · 夹具 `case "production"`（`GovZ6WorldFixture:256`）与守卫测试在位。
- 各模块测试：production **66/0/0** · economy **381/0/0** · app **1008/0/0/7**；SpotBugs 全 0（production/economy/app 均过）。

#### 14.28.2 判据终局状态

| 判据组 | 状态 |
|---|---|
| **V1–V12** | **11 通过 + V4 部分**（V4 的"按实际贡献分成"腿具名未做） |
| **N1–N25** | **22 通过 + 3 条具名**（N9 无独立载体 / N14 部分 = V4 同腿 / N20 部分 = 三命令端到端序） |
| **T3 变异自证** | **完成**（M1–M7 七条 + 夹具变异一条，全部"改坏⇒红⇒逐字节还原⇒重跑绿"，零等价存活） |
| **T4 完整门禁** | **达成**（控制方复跑 rc=0） |
| 黄金对比 | 653 行全量落盘；T6→T7 新差异 **0**；裁定二十六两键两侧 0/0 |

#### 14.28.3 结论与剩余（终局口径）

- **D-032「生产方式运行时」主链已验证接通**：方案/实例/经验/效率/解锁/GM 覆盖的唯一权威在 production；两步契约（`probe`→分配→`execute`/`executeDay`→回执）真实驱动经济日循环；`changeSet()` 与 ECONOMY/SOCIAL/ACTOR/GOV 同一次 Revision；I1/I4/I5/I8/I9/I10/I11/I12/I13 均有运行期判据；economy 侧第二权威清零。
- ★ **验证阶段抓出并修掉的真运行期缺陷**（全部是"编译绿 + Spotless 绿 + 装配齐全"盖住的断路）：tick 协议首次即炸 · trade 路径 `plan-unknown` · 生产日不推进（三层：清零位置/读源/步表快照）· `economy.Seed` 关系表静默丢失 · three-powers 创世被拒 · SpotBugs 26+5 · N22 串行推进不等价 · **测试夹具静默丢弃 production ChangeSet**（覆盖缺口）。
- **剩余具名项（不阻塞主链验收，需覆盖收尾批）**：V4/N14 的"按实际贡献分成"腿 · N9 独立载体 · N20 三命令端到端序 · N13 专用对照载体 · N23-economy 两处逐行重核 · **`EconomyOwnershipFixture` 的 production 片**（与裁定四十八同类的覆盖缺口）· 占位断言清扫剩余文件 · 黄金逐行 provenance（U10 面）· G1/G2 · `birthsToday` 死读 · `GovWorldBootstrap:89-100` 类注缺行 · `EconomyDayStepper.productionRuntime()` 0 调用去留。
- **提交/同步主树**：仍**未提交**（HEAD `03faf0a3`，193 M / 20 D / 44 ?? 全在本侧线工作树）⇒ **需要用户明确授权**才可 commit/同步（U10 起的一贯口径）。

#### 14.28.4 T8 覆盖收尾与**控制方终局复跑二**（2026-10-17）

- T8（纯测试面，1 夹具 + 9 新测试文件、14 条新判据）把 §14.28.3 的具名缺口**全部收口**：
  ① **`EconomyOwnershipFixture` production 片**（与裁定四十八同类）：三片同进同出 + `default` 抛错 + namespace 审计表；`EconomyOwnershipFixtureProductionSliceTest` **2/0/0**（160 实例进度 0 → advance 后 162：旧 160 全 +1 + 2 个 trade unit 当日建档；未知 namespace `ga` 具名 ISE）。
  ② **V4/N14**：`ProductionSettlementContributionHandCalcTest` **1/0/0**——两户 600,000/400,000 劳动 ⇒ 工资 1,200/800 · 分成 720/480 · 租 500 · 经营者剩余 300；守恒 1,000+3,700+300=5,000（毛产只一份）。
  ③ **N9**：`UnifiedBudgetNoDoubleBookingTest` **3/0/0**（劳动 700+300=1,000 · 库存 800 成功/800 具名拒 · 同一 1,000 LAND 份额只支撑 farm=1、weave=0）+ `N9BatchZeroPartialCommitTest` **1/0/0**（真 submitBatch：第 2 条缺 unit ⇒ 整批拒、第 1 条不落、head 与全切片零变化；正对照 1 revision）。
  ④ **N20**：`N20ThreeCommandSeedOrderEndToEndTest` **1/0/0**（正常序 1 revision 三片非空；颠倒序具名拒 + 零 revision）。
  ⑤ **N13**：`SingleOutputEntryHandCalcTest` **2/0/0**（baseScale 100 / finalScale 150 / 需求 100,000·100,000·300 / 毛产 10,050,000 / 实扣 300；多产出逐商品 10,050,000 + 450,000）。
  ⑥ **N23-economy**：`N23EconomyUnspecifiedFailClosedLineAuditTest` **2/0/0**（行号重核 `ModeMigrationSettlement:1144` / `EconomyEnterpriseSettlement:726`，与 T6 逐值相同未漂移；Enterprise 处正常路径不可达，只钉源码行号/原文，如实写明）。
  ⑦ **占位清扫**：§13.5 清单 **42 文件 / 66 命中**逐文件 A–F 处置表入台账 §17.9；结论 = 无"空 runtime + 产出断言"的活占位。
  ⑧ **G1/G2**：`ProductionChangeSetOperatorTest` **1/0/0** + `ProductionSeedInstancesTest` **1/0/0**（instances[] 六字段逐值、operator≠tenant 推导值）。
- **控制方终局复跑二（在 T8 之后的最终树上）**：`tools/mvn-lock.sh -pl simos-app -am clean verify` ⇒ **rc=0 · BUILD SUCCESS · 18/18 模块 SUCCESS**；app **1012/0/0/7** · economy **387/0/0** · production **70/0/0**；`[frontend-gate] OK tests=412 pass=412 fail=0`；`[shaded-guard] 结论：OK（8121 条目）`。边界：HEAD `03faf0a3` 未动 · `stash@{0}` 未动 · 10:29 后 `src/main` 零改动。
- **判据终局（更新）**：**V1–V12 全部有载体（V4 已由 T8 补齐）· N1–N25 全部有载体（N9/N14/N20 已补齐）**；T3 完成；T4 达成。
- **剩余（不阻塞验收，均为后续小批/U10）**：黄金 653 行逐行 provenance（U10 面）· main 清理三项（`birthsToday` 死读 / `GovWorldBootstrap:89-100` 类注缺行 / `EconomyDayStepper.productionRuntime()` 0 调用去留）· `EconomyTestWorld` 死夹具（0 引用）删除 · Enterprise 处不可达分支仅源码钉死。

### 15. 最终独立审核 R1–R6：缺陷裁定与修复批次（控制方，2026-10-17）

> **本文取代** §14.28.3 的"剩余具名项（不阻塞主链验收）"中隐含的"已知缺陷已清"口径：**该口径作废**。独立审核 `docs/superpowers/reports/2026-10-17-d032-final-independent-review.md` 的 R1–R5 **全部成立**（控制方已用同一复现脚本独立复跑，读数逐字相同）；控制方在复核 R3/R1 的修复面时**新发现 R6**（入口 `probe` 入参组合使一切候选必被拒）。根因不是测试跑假，而是**判据覆盖缺口**：这些入口/数值组合当时没有任何带判别力的用例（与 T8 已记录的 `GovZ6WorldFixture` 静默丢片同类）。
> **状态**：R3/R4/R5 → **P 批**（production，可立即派单）；R1/R6 → **E1 批**（economy 估算/入口接线）；R2 的分配规则**待用户裁定**（见 §15.7 选项），裁定后派 **E2 批**。修复完成后由**独立测试 owner**（T9 批）补带判别力的回归与变异自证，再由控制方终局复跑。

#### 15.1 R1（P1）经济收益排序绕过新生产计算，熟练度/GM 覆盖不进选择链

**事实（控制方回代码核过）**：
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java:398-413`：`productionProspect` 自算 `scale = min(资产规模, 劳动规模)`，`plannedOutput = recipe.outputPerUnit × scale × 1000`；不调 `probe`，不读家户熟练度/经验、不读 `production.outputQuantityOverrides()`。
- `simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueueBook.java:248-302`：`offer` 的 `outputValue/net` 同样直接按旧配方单产×市价算。
- 复现（控制方独立跑审核脚本）：同一方案同一投入，熟练度倍率 2 时 `expected-profit planned-grain=100000`，实际 execute `grain=200000`。

**违反条款**：§4.2「价格/需求不进 probe；物理产量由 production 说」· §4.3「分配前对候选做无副作用试算，economy 用试算结果排序/分配」· §13.3「试算/实际/**家户利润比较**读同一份配置与同一计算入口」· I6。**"probe 进决策路径"此前只覆盖入口 `EconomyEntrySettlement`，未覆盖排序/分配链**。

**修复后世界必须长成**：`ExpectedProfitBook.productionProspect` 与 `LaborQueueBook.offer` 的"物理产量/所需劳动/所需投入"一律取自 `ProductionRuntime.probe`；economy 只保留 valuation（价格×产出、自给折算、需求可寻址上限、成本）、排序与分配。熟练度不同 ⇒ 两个候选的排序可不同；GM 产出覆盖同时改变预计与实际；未解锁候选不进入选择链（probe 具名 `locked`）。probe 入参按家户装配：劳动=该户本周期可用劳动（`participationAdjustedLaborMilli × cycleDays` 口径）、资产=该实例/候选实占、投入=可用库存、熟练度/经验=**本 tick 冻结条件**、余数=实例 `efficiencyOf`。预计与实际仍允许不同（实际依最终投入重算），但**算式只有一份**。

**负向判据（T9）**：① 熟练度 2 的候选排序高于熟练度 1；② GM 覆盖 ×2 ⇒ 预计与实际都 ×2；③ `locked` 候选不进选择链；④ 试算前后全切片逐值不变（I5 不回退）。

#### 15.2 R2（P1）同方案多实例重复获得整份资产（统一预算缺口）

**事实**：`SettlementIndex.java:353-382` 按 `(industry, operator)` 聚合后把整份数量复制给同 scope 的**每个** unit；`EconomySettlement.java:4277` 逐实例把整份聚合当 `assetOccupancy` 传给 `executeDay`，`grantedScale=Long.MAX_VALUE`（"不额外夹"）。复现：同一 LAND=100 份额、同经营者两个实例 ⇒ 每 unit 各得 100，合计 200。`UnifiedBudgetNoDoubleBookingTest:121` 只测了**不同方案 id**（靠 industry 不等返回 0），未覆盖同方案多实例。**违反 I8**（"同一份劳动/资产/库存不得被两个方案**或两个实例**重复占用"）。

**修复方向（分配规则待裁定，见 §15.7）**：资产实物总账保留一份；**每 tick 逐实例的 `assetOccupancy` 必须来自统一分配**（§4.3 第 3 条已定：数值必须来自统一分配，production 不得回读另一套资产权威），同 scope 并发占用之和 ≤ 实有数量；分配结果同时供 labor offer 的产能上限与 `executeDay` 的 Step 使用（两处不得各算一份）。负向判据：共享方案+同经营者+多实例/多地点场景，`Σ占用 ≤ 实有`；单实例世界逐值不变。

#### 15.3 R3（P1）实际执行绕过解锁与可用性条件

**事实**：`ProductionRuntimeEngine.java:730-790`（`execute`）不做 `unsatisfiedCondition` 与 `isUnlockedBy` 检查；条件只在 `probe` 的 `:452/:458` 检查。`ProductionDayRequest.Step` 携带 `attributes` 但正式日循环 `EconomySettlement.java:4279` 固定传 `ExternalAttributeFacts.EMPTY`。复现：`locked` 方案 probe=locked、execute 产粮 100000；缺 literacy 事实 probe=condition-unsatisfied、execute 仍产粮 100000。

**修复后世界必须长成**：`execute`/`executeDay` 是**权威实际入口**，必须与 probe 用**同一条件求值**判"本次使用资格"，被拒 = 具名业务拒绝（INFO）且**零残留**（无实例/进度/经验/消耗/占用变更）。
- **条件主体（控制方工程裁定，用户可推翻）**：使用资格的主体 = **实例经营者对应的家户**（`instance.operator()` 为 `ActorKind.HOUSEHOLD` ⇒ `HouseholdActors.householdOf`）。组织经营者不构成家户主体 ⇒ 声明了 `MinimumExperience` 条件、或设了解锁门槛的方案对组织实例 **fail-closed**（具名 `condition-subject-unknown`）；无经验类条件的方案不受影响。解锁资格同样按该主体判（`unlocksOf(subject)`）。
- `ExternalAttribute` 条件用 `request.attributes()` 判（缺失 ⇒ fail-closed，理由与 probe 逐字相同 `condition-unsatisfied:external-attribute-missing:<key>`）；组织主体也可判（属性事实是平表，不绑定家户）。
- **条件求值只有一处拼写点**：probe 与 execute 共用同一 helper（经验读取由调用方给函数：probe 读 `HouseholdProductionConditions.experienceOf`，execute 读本域账本）；未知经验条仍是契约故障。
- 正式日循环的 `EMPTY` 属性事实保持（属性通路本就未接）⇒ 声明外部属性条件的方案在真实日循环里一律具名拒（fail-closed 是设计口径 §4.5，不是缺陷）。
- 负向判据（T9）：直接 execute 被 probe 拒的 locked/条件方案 ⇒ 具名拒 + 零残留；真实日循环同样拒（`Rejected` 计数 + 状态逐值不变）；无条件方案逐值不变。

#### 15.4 R4（P1）零经验迁入者不参与合户平均

**事实**：`ProductionData.java:438-474`（`mergeExperienceInto`）只枚举**来源户已有**经验条并 `allZero` 跳过 ⇒ 目标独有条不稀释。复现：目标 10 人经验 100 + 来源迁入 10 人经验 0（缺行）⇒ 应 50，实际 100。**违反 I10**（"合户按人口加权平均"——缺行 = 0 是真实世界状态，见 `experienceFeatureOf` 类注，必须参与平均）。

**修复后世界必须长成**：对**来源 ∪ 目标**的经验条并集逐条合算，缺项按 0 参与人口加权（`⌊(exact_from×moved + exact_to×target) ÷ (moved+target)⌋`，余数一起参与）；全零结果删行；解锁并集与源户不减不变。负向判据（T9）：目标独有条 / 来源独有条 / 显式全零行 / 多次迁入累计四种场景的具体数值。

#### 15.5 R5（P2）原始经验公式同 tick 顺序依赖

**事实**：`ProductionRuntimeEngine.java:1297-1299`：`condition != null` 时熟练度取冻结条件，但 `primaryExperience = current.experienceOf(...)` 仍是当刻候选态 ⇒ 同 tick 先执行的实例涨经验，后执行的实例公式吃到新经验。复现：E=1000+primary_experience，同户两实例正序规模 100/105、倒序 105/100。`ProductionExperienceUse.java:55` 明确 `primary_experience` 是合法公式输入。**违反 §13.2 第 1 条①**（同 tick 取同一时点生效条件，"不能因实例处理先后改变另一实例本 tick 的熟练度"——原始经验同属公式输入）。

**修复后世界必须长成**：`ProductionTickCondition` 冻结**全部**影响当 tick 生产公式的经验输入（熟练度 a/b + 主经验原始值；逐 (家户 × 经验条) 一行，缺键 = 0）；`beginTick` 在衰减之后一次快照；`execute` 的 `efficiencyScaleFor` 在 `condition != null` 时读冻结值，不得读 `current`；`condition == null`（手搭夹具直调）逐值不变。试算若在 tick 内发生，调用方须传冻结值（E1 批把入口 probe 改为优先读 `currentCondition()`）。负向判据（T9）：引用 `primary_experience` 的公式、同户两实例正反序 ⇒ 规模逐值相同；tick 边界（tick N 冻结值不因 N 内执行改变）。

#### 15.6 R6（控制方新发现，P1）入口 probe 入参组合使一切候选必被拒

**事实（控制方独立复现，rc=0）**：
```
A cycle=1 emptyOccupancy labor=100      : feasible=true scale=0 assetRequired={} expectedOutput={}
B cycle=1 occupancy=100 labor=100       : feasible=true scale=100 laborRequired=100 assetRequired={LAND=100} expectedOutput={grain=100000}
C cycle=120 occupancy=100 labor=12000   : feasible=true scale=100 laborRequired=100 expectedOutput={grain=100000}
D cycle=120 occupancy=100 labor=100     : feasible=false reason=labor-short
```
`EconomyEntrySettlement.java:833-880` 的 `probeEntry` 固定传 `assetOccupancy = Map.of()` ⇒ `probe.capacityScaleOf` 显式"空占用 ⇒ 0"（`ProductionRuntimeEngine.java:1226-1233`）⇒ `scale=0`、`assetRequired` 空 ⇒ 入口在 `NO_ASSET_REQUIREMENT`/`PROBE_SCALE_ZERO` 处**必拒**；且它传的 `householdEconomy.laborMilli()` 是**每日**可用劳动，而 `probe` 的 `ProductionEfficiency.evaluate` 按**周期累计**劳动除以 `cycleDays`（C/D 对照：周期 120 天必须传 12,000，传 100 得 `labor-short`）⇒ 即便补上占用，规模仍被低估 `cycleDays` 倍。⇒ **"probe 进决策路径"在真实入口上是一条死路**；此前测试无入口端到端载荷（`base.demands()` 为空即不触发），与 R1–R5 同属覆盖缺口。
**修复后世界必须长成**：入口 probe 的入参 = 候选**将要实占**的资产（自有 + 允许来源，按 probe 的 `assetRequired` 语义）+ **本周期累计可用劳动**（每日可用 × `cycleDays`）；probe 判可行且 `scale>0` 的候选能真正 `createInstance` 并得到与 `execute` 同源的预计产出；被拒候选零残留。负向判据（T9）：有需求的真实世界入口能产出实例；预计产出 == 按同投入 execute 的毛产（I6）；劳动/资产不足时具名拒。

#### 15.7 R2 待用户裁定：同经营者共享资产池的分配规则

同一 `(方案, 经营者)` 的资产池被多个实例共享时，谁拿多少？三个候选规则（控制方推荐 A）：
- **A（推荐）按计划产能比例分摊**：每个实例的占用 = 池 × (该实例计划产能 / 同 scope 全部实例计划产能和)，最大余数法保整数与确定性；`Σ ≤ 实有`，单实例世界逐值不变。与"同 unit 内多家户按想要量比例缩"的既有形制一致，无排序偏置。
- **B 按收益排序优先领取**：与劳动队列"最划算先做"一致，但需把资产分配并入排序阶段，且同一天里低收益实例可能拿 0。
- **C 均分**：最简单，但忽略各实例计划差异。
**裁定前 E2 批不派单**；裁定后按该规则实现 §15.2 的分配。

#### 15.8 批次、文件所有权与验收纪律

| 批 | 内容 | 所有权（写） | 验收 |
|---|---|---|---|
| **P** | R3/R4/R5（production） | `simos-production/src/main/**` | 实现方只到 main 编译 rc=0；记录测试预期破面 |
| **E1** | R1/R6（economy 估算与入口接线） | `simos-economy/src/main/**`（+ app 桥接线若需） | 同上 |
| **E2** | R2（economy 统一资产分配，待裁定） | `simos-economy/src/main/**` | 同上 |
| **T9** | R1–R6 带判别力的回归 + 变异自证；既有测试按新语义改写 | 各模块 `src/test/**` | 测试 owner 只写测试；不得改 main 掩盖 |
| **控制方** | 终局复跑 `clean verify` + 边界核对 + 本轮记录 | docs/.superpowers | rc=0、计数、零未解释差异 |

**纪律**：① 每批先写实现账本（`.superpowers/sdd/2026-10-17-d032-runtime-migration/impl-fix/impl-ledger.md`）；② 接口/契约变更必须同步 main 侧唯一实现，测试替身由 T9 批同步；③ 不得 `git add/commit/stash/checkout --`，不得改主树/其他工作树；④ 业务拒绝 = INFO、契约故障 = ERROR 不降级；⑤ 修复不得靠删判据/放宽校验；⑥ T9 的每条负向须"改坏 ⇒ 当场红 ⇒ 还原 ⇒ md5 逐字节一致"，做不到如实写"等价存活"。

#### 15.9 用户原话附录（逐字，2026-10-17）

> 「发现 5 类问题，建议先修复，暂缓提交同步。
> 1. [P1] 利润排序没有使用熟练度。 经济侧仍自行按基础配方估算，复现得到"预计产出 100、实际产出 200"，熟练生产的利润优势没有进入选择链。位置 (.claude/worktrees/parallel-side-module/simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java:404)
> 2. [P1] 共享方案的多个实例重复计入资产。 只有 100 份土地，两个实例各自得到可用量 100，合计算成 200。位置 (.claude/worktrees/parallel-side-module/simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementIndex.java:372)
> 3. [P1] 实际执行绕过解锁与属性条件。 试算明确拒绝未解锁、缺识字率属性的方案，直接执行却仍成功生产。位置 (.claude/worktrees/parallel-side-module/simos-production/src/main/java/io/mosire/simos/production/ProductionRuntimeEngine.java:786)
> 4. [P1] 零经验人口合户时没有摊薄经验。 10 人经验 100，加入 10 人经验 0，应平均成 50，实际仍为 100。位置 (.claude/worktrees/parallel-side-module/simos-production/src/main/java/io/mosire/simos/production/ProductionData.java:438)
> 5. [P2] 原始经验公式存在同 tick 顺序依赖。 同户两个实例正序产出规模为 100/105，倒序变成 105/100；熟练度被冻结，原始经验没有。位置 (.claude/worktrees/parallel-side-module/simos-production/src/main/java/io/mosire/simos/production/ProductionRuntimeEngine.java:1299)
> 已保存独立审核报告及复现入口 (.claude/worktrees/parallel-side-module/docs/superpowers/reports/2026-10-17-d032-final-independent-review.md)，可直接给隔壁 —— 来看一下」

### 15.10 P 批实收（R3/R4/R5，控制方复核，2026-10-17）

- **编译**：实现方 `tools/mvn-lock.sh -pl simos-production -am compile` ⇒ rc=0（`Compiling 72 source files`、0 Checkstyle 违规；证据 `impl-fix/evidence/p-compile.log`）。控制方回代码核过：`qualificationFailure` 是 probe/execute 唯一求值点（probe 传 `household::experienceOf`，execute 传 `current.experienceOf(subject,·)`），execute 资格判定在任何 `current` 变更之前、creation 的 `withInstance` 移到资格通过后（零残留）；`freezeCondition` 同快照原始经验；`efficiencyScaleFor` 冻结分支只读 `experienceFromCondition`，`condition == null` 分支未动；`mergeExperienceInto` 为来源∪目标并集、缺行=ZERO、删掉全零跳过。**接受**（测试面由 T9 批验证，未跑测试）。
- **两处偏离接受**：① 求值次序按 probe 现状（条件→解锁）而非派单编号（解锁→条件）——理由正确（否则 probe 输出会变），以 probe 逐值不变为准；② 新增契约故障 `condition-subject-unparsable`（HOUSEHOLD 经营者但 actor id 反查失败 ⇒ ERROR 不静默），与本轮"宁抛不静默"一致。
- **留待 T9**：`ProductionExperienceMoveOutcome.tracksMerged` 是否扩到并集口径（当前只数来源条；数值口径已在 `mergeExperienceInto` 落地）。

### 15.11 E1-A 实收（R6 主接线）与 E1-A2 收尾批（控制方，2026-10-17）

**E1-A 实收（接受主接线）**：`EconomyEntrySettlement.probeEntry` 现在传 `laborMilli = 日可用 × plan.cycleDays()`（probe 内部 ÷cycleDays 折回日均）与 `assetOccupancy = own ∪ source`（正值合并）；编译 rc=0（证据 `impl-fix/evidence/e1a-compile.log`）。控制方回代码核过两处改动。

**E1-A2 收尾（R6 的两个残留，均经控制方回代码确认）**：

1. **plan-unknown 前置缺口（P1 级，R6 修复不完整）**：`probe` 按 `request.plan()` 查 `current.industries()`；入口传**本格物化 id** `<plan>@q_r`，而 `planDefinitionFor` 未物化时**只合成不登记**（`registerPlan` 在 `applyIntent`、在 assess 之后；planEntries 必须只读）⇒ 某格**首次**采用某方案仍被 `PROBE_INFEASIBLE:plan-unknown` 拒。**修法**：`assess` 里已有 `registered = definitions().industry(industryId)`；probe 的 `plan` 入参改为 `registered.isPresent() ? industryId : plan.id()`（未物化时用方案池里的 unbound id —— 物化是纯复制、两定义逐值相同；且 unbound 方案必然在 `current.industries()` 里，否则它进不了 `plans()` 池）。`planDefinitionFor` 仍用于 capacityPerUnit/经验面等读数值；**不得**在 planEntries 里调 `registerPlan`（写副作用）。
2. **二次夹删除（P1 级）**：`assess` 的 `assetCap = min_k(available_k/required_k)` 量纲错误（`required` 是 baseScale 处绝对量，`available/required = assetScale/baseScale`），资产通道最紧（真实世界常见）时退化为 1 ⇒ `trialScale` 恒为 1、`MAX_TRIAL_SCALE_PER_ENTRY` 失效。**修法**：删掉 `assetCap` 整段与 `ASSET_SHORT` 拒绝（probe 已按同一占用夹过、资产不足已由 `PROBE_INFEASIBLE:asset-short` 具名拒），`cap = min(MAX_TRIAL_SCALE_PER_ENTRY, probeScale)`；`required`/`anyPositiveAsset` 按最小清理处置并写清。
3. **契约文档补口径**：`HouseholdProductionConditions.laborMilli` 的 javadoc 补"本**周期累计**可用劳动（= 每日可用 × cycleDays；probe 内部 ÷cycleDays）"，与 `HouseholdEconomy.laborMilli()` 的每日口径区分开（只改 javadoc，不改行为）。

**未在本批**：E≠1000 时入口 `scaleLinearly(probe.expectedOutput, trialScale, probeScale)` 的 1000/E 偏差残留——由 T9 的"熟练度≠1000 入口夹具"判定，若红另开修复批；`applyIntent` 同批资产索引不更新属既有遗留，归 R2/E2 域。

### 15.12 E1-B 派单：R1（probe 进两条估算/排序链）实现约束（控制方，2026-10-17）

**目标**：`ExpectedProfitBook.productionProspect` 与 `LaborQueueBook.offer` 的"物理产量/所需劳动/所需投入"一律取自 `ProductionRuntime.probe`；economy 只保留估价（价×量、自给、需求上限）、排序与分配。**熟练度/GM 覆盖/解锁条件必须进入选择链**（R1 的复现：预计 100 vs 实际 200）。

**A. `LaborQueueBook.offer`（劳动分配排序链）**
- 保留 `Offer` record 形状与字段语义（字段仍为"每 1 基准规模的毫钱/毫劳动"量纲），避免下游漂移；`offer(...)` 增入参 `HouseholdId household`（家户决定熟练度/经验）。
- 用**参考基准规模 `REF = 1000`** 调一次 probe（把 floor 失真压到 ≤1/1000 规模；E=1000 时逐值中性）：
  `occupancy_k = capacityPerUnit_k × REF`；`inputs_c = inputPerUnit_c × REF`；`conditions.laborMilli = laborPerUnit × REF × cycleDays`（cycle 累计；供 production 内部 ÷cycleDays 折回）；熟练度/经验优先读 `runtime.currentCondition()`（tick 冻结口径，R5 一致），无 tick 时读 `runtime.masteryOf/experienceOf`；`carry = runtime.efficiencyOf(unit.id())`；`attributes = EMPTY`。
- 从 probe 结果换回"每 1 基准规模"：`perScaleLabor = probe.laborRequiredMilli()/REF`；`perScaleOutput_c = probe.expectedOutput_c/REF`（毫商品/规模）；`perScaleInput_c = probe.inputRequired_c/REF`。`outputValue = Σ perScaleOutput×bidPrice/1000`；`inputCost = Σ perScaleInput×askPrice/1000`；`net = outputValue − inputCost`；`key = ⌊net×1e6/max(1, perScaleLabor)⌋`（公式形状不变，只换物理读源）。
- `maxAbsorbableLaborMilli = usableScale × perScaleLabor`（`usableScale = min(plannedCapacityScale, inputScale)` 的原判据不变）。
- 不可行/退化：`probe.feasible()==false` ⇒ `maxAbsorbable=0` + reason `"PROBE_REJECTED:"+probe.reason()`（`locked`/`condition-unsatisfied:*` 因此不进队列 = R1 的"未解锁候选不入选择链"）；`probe.producible()==false`（scale 0）⇒ `maxAbsorbable=0` + `"PROBE_SCALE_ZERO"`。既有 `LABOR_FREE`/`NO_RECIPE_OUTPUT`/`NO_CAPACITY`/`NO_INPUT*` 具名分支序保留。
- **中性判据**：E=1000、TOTAL、无 GM 覆盖、无 availability 条件、库存够时，`Offer` **逐值等于**改前（`expectedLabor==laborPerUnit`、outputValue/inputCost/net/maxAbsorbable/理由全同）⇒ 既有 economy 测试应仍绿。
- 性能：每 (家户 × unit × 日) 一次 probe；**不加缓存**（N25 口径），在账本记耗时与调用量。

**B. `ExpectedProfitBook.productionProspect`（迁移/候选排序链）**
- 两个公开 `prospect(...)` 重载与 `productionProspect` 增入参 `ProductionRuntime runtime`（requireNonNull）；`ModeMigrationPolicy.plan(...)` 增同参并由 `EconomySettlement` 传 `session.productionRuntime()`；app `GovernmentServiceDesertionBridge.execute(...)` 增同参，由 `PopulationEconomyTimeParticipant` 传 `stepper.productionRuntime()`（该 accessor 已存在、此前 0 调用，本批成为真实消费者）；`expectedProfitPerLabor` 透传。
- probe 入参：劳动 = `householdEconomy.participationAdjustedLaborMilli() × horizonDays`（cycle 累计；horizonDays = `industry.cycleDays()`）；占用 = 既有 unit 用 `ProductionProcessBook.usableAssets(existingUnit, shares)`（**R2/E2 替换点**，注释标清），候选用与现有 `assetScaleOf` **同一判据**的可用资产表（自有+同格可租闲置、claimed 排除；把 `assetScaleOf` 的内部现改成"返回资产表"的唯一拼写点再取 min，不得两份算术）；投入 = `accounts` 里该户商品余额（缺账户 ⇒ 空表）；熟练度/经验同 A（tick 冻结优先）；`carry = runtime.efficiencyOf(unitId)`（候选 empty）；`attributes = EMPTY`。
- 结果消费：`plannedOutput = probe.expectedOutput`（毫商品/周期，含 GM 覆盖与 E）；`scale = probe.scale()`（产出规模）；`inputCost` 用 `probe.inputRequired`（键集同 recipe）；`laborNeed = probe.laborRequiredMilli() × horizonDays`（每日→周期）；自给/可售/需求上限/收入/分账（obligations）/净额算法形状不变。不可行 reason 映射：`asset-short→"NO_ASSET:"+industry.id()`、`labor-short→"NO_LABOR:"+household.value()`、`plan-unknown→"NO_INDUSTRY"`、其余（含 `locked`/`condition-unsatisfied:*`）⇒ `"PROBE:"+reason`；feasible 但 scale==0 ⇒ `"SCALE_ZERO"`（旧词表保留）。`Prospect.scale` 的消费者（`profitScaleBasisScaled`/A 规则/排序表等）逐处核对新语义并记录。
- **中性判据**：E=1000、TOTAL、无覆盖、无 availability、库存够、同一份占用时，`Prospect` 的 plannedOutput/selfConsumed/sellable/revenue/inputCost/net/laborNeed/scaledNetPerLabor/infeasible reason **逐值等于改前**。
- `wageProspect` 的雇主规模读数**不在本批**（它是物理产能读数、不是预计产出），在账本记明决定。

**纪律**：只写 `simos-economy/src/main/**` 与 `simos-app/src/main/**`（app 仅限桥接传参）；不改 production/测试/docs；不跑测试/verify；不 git 操作；唯一验收 = `tools/mvn-lock.sh -pl simos-app -am compile` rc=0（app 会把 economy/其余模块一起编）+ 0 Checkstyle；账本落 `impl-fix/e1b-batch-impl-ledger.md`，证据 `impl-fix/evidence/`。实现方**只到编译**；测试面破面（既有测试直接调 `prospect`/`offer` 的签名变化、非中性数值变化）由 T9 批统一收。

### 15.13 新裁决吸收与重规划（2026-10-17，晚）

- **输入**：用户 2026-10-17 追加裁定 D-032/U13–U21、D-033（`docs/superpowers/specs/2026-10-02-architecture-design-source.md`）与合作生产/工资链专题文档。**重规划落盘**：`docs/superpowers/plans/2026-10-17-d032-production-line-replan.md`（Phase F/D/C/W/A/G/M/T）。
- **§15.7 的 R2 A/B/C 选项作废**（那是"同一作用域池怎么分"的三选一）：新方向是**资产有真实地点/身份 + 经济对合作实例统一分配占用 + 占用期/跨周期锁定**（合作提案 §6；与工资裁决 §4"不新增独立土地登记"需收口为"扩展现有 `OwnershipStake` 账本加地点/占用属性"）。R2 仍**未修**，关闭在 Phase A；此前不得以任何形式"顺手修"。
- **R3 主体模型将被 C 批取代**：新方向"资格按参与位置、组织经营者从实际参与名册解析"（合作提案 §5）。P 批的"条件主体=经营者家户、组织对经验/解锁条件 fail-closed"是**临时工程取舍**，C 批替换；临时是否先扩成"实际出工家户集合"见重规划 §5-Q6（控制方推荐 (b)）。
- **F1/F2 已派**（R6 两残留、R1 probe 进两条估算链），不受新业务裁定影响；T9 在其后。**提交/同步继续冻结**（需用户显式授权）。

#### 15.13.1 重规划 v1 被独立审核否冻结、v2 修订（2026-10-17）

- `2026-10-17-d032-production-line-replan-review.md`（独立审核，14:34）判定重规划 v1 **不通过冻结**：Q2 复活已被 U15 否定的"劳动/标准劳动份数×系数"分配解释；Q5 复活已撤回的 ΔB×k 工资定价；资产旧建议被提升为待确认玩法；C1 与 G1/G3 同一生产定义写面冲突；C4 提前验收 R2 造成 C↔A 验收依赖环；F3 笼统写"R1–R6"含未修的 R2；现状状态过期。
- **重规划 v2（14:37 落盘）已逐条修订**（见 `docs/superpowers/plans/2026-10-17-d032-production-line-replan.md`）：Q2/Q5 撤回；Q1/Q4/Q6/Q7 不再问用户；只剩"经验增长的规模效应"一条可观察玩法（默认可配置表达式按参与强度归一化，不冻结唯一口径）；先冻结共享生产定义形状（D1）→ 同一 owner 完成 C0（共享契约/codec/命令）→ 再 C1/C2；"共享土地不重复生产"判据归 A/T，C 交账写明受 R2 限制；F3 只覆盖 R1/R3/R4/R5/R6，R2 保留具名"未修复"证据；不得写"全无已知缺陷"。
- **F 修复继续**：F1（R6 两残留）已完、编译 rc=0、控制方抽核通过（含拆分腿量纲必要伴随修正）；F2（R1）在飞；T9 其后。R2 仍**未修**，Phase A 关闭。提交/同步继续冻结。

#### 15.13.2 v2 复核通过、基线冻结与 D1 启动（2026-10-17）

- `2026-10-17-d032-production-line-replan-v2-review.md`（14:40）**复核通过**：可把重规划 v2 作为基线冻结、继续 D1→D2/D3/D4 详细设计；上轮两条 P1 与排期冲突已消除，无新阻断。
- **冻结边界（写入纪律）**：① 经验归一化 = **可配置工程默认**（不得写成用户已裁定的唯一公式；D2 必须写清归一化分母/周期/角色系数）；② 仅冻结规划与 **D1 共享生产定义形状**，**C/W/A 实现仍须各自约束设计书完成后才可派单**；③ 测试责任仍独立（C0 的往返能力由独立测试 owner 验）；④ R2 继续具名未修（F 限定范围通过 ≠ R2 通过）；⑤ 工资按 U19–U21 最新规则保留（当期报价入候选费用、下一 tick U20 非对称调价、可闲置、U21 支付顺序）。
- 执行状态：**F1 ✅（R6 两残留，编译 rc=0）· F2 ✅（R1，编译 rc=0，18/18 模块）· T9 已派（进行中）**；**D1 共享定义形状已冻结落盘**：`docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md`。下一步 D2/D3/D4 设计书。

#### 15.14 T9 中期：抓到 F 批之外的真缺陷（B2 遗留）与 F5 修复批（2026-10-17）

- T9 中期：A 段（全仓测试编译）绿；B 段 `3488/0/0/7`（8 条旧口径红按新语义校准：R1 库存通道进 B）；C 段新增 R1 6 / R3 6 / R4 5 / R5 3 / R2 具名未修 1 条全绿；D 段 5 条变异自证完成（红→还原→md5 逐字节一致→绿）。
- **BLOCKED 真缺陷**：`EconomyEntrySettlement.planEntries` 名义"只读意向段"，但 B2 起其 `applyIntent` 调真 production 写口（`registerPlan`+`createInstance`+实例守卫）⇒ ① planEntries 即改 runtime（物化定义+progress 0 实例、`runtime.changeSet()` 非空）；② 随后 `execute` 重新评估同意向时 `createInstance` 撞 `unit-conflict` ⇒ `Execution.changed()=false`、`enteredUnitIds=[]`、`INSTANCE_CREATE_REJECTED:unit-conflict`。**首次采用生产入口仍坏**（R6 端到端一腿）。测试载体：`EconomyEntryProbeR6Test#planEntriesMustBeReadOnlyAndExecuteMustActuallyEnter`（T9 已钉，红证据 `test/evidence/t9-c-economy-new-05.log`）。
- **处置**：派 **F5 批**（只改 `EconomyEntrySettlement.java`）：给 `applyIntent` 显式分叉——planEntries=只读模拟（不调 runtime 写口，只写 `context.tables.units` 副本 + 既有 shadow 写）；execute=真写不变。F5 编译过 → T9 续跑全量 + 该测试转绿 + 变异④（probe plan id）按新文件重做 → 控制方终局复跑。
- 教训（再次同形）：B2 的"实例/定义归 production"改对了权威，却把**只读意向段**也接到了写口；编译/检查点看不出来，只有端到端判据能抓。

#### 15.15 F5 复修与 T9 收口（控制方记录，2026-10-17 下午）

- **F5（planEntries 只读化）已完**：`applyIntent` 分叉为 `WriteMode`——planEntries=只读模拟（不调 runtime 写口，只写 `context.tables.units` 副本 + 既有 shadow 写），execute=真写逐值不变；编译 rc=0（`Compiling 241 source files`、11/11 模块 0 Checkstyle；证据 `impl-fix/evidence/f5-compile.log`，★ 该文件曾被 T9 并发锁日志覆盖为一行，编译真实性由 T9 的 F5 后全量测试通过间接证明，F4 复跑为准）。
- **T9 收口（关键读数，命令 `tools/mvn-lock.sh -pl simos-app -am test`，日志 `test/evidence/t9-full-test-04-post-f5.log`）**：全仓 **3513 / 0F / 0E / 7 skipped**；production **84**（70+14 新增）· economy **398**（387+11 新增）· app **1012/0/0/7**。新增判据 R1 6 / R3 6 / R4 5 / R5 3 / R6 4 / **R2 具名未修 1**；变异自证 5 条全部"改坏⇒红⇒还原⇒md5 逐字节一致⇒绿"，零等价存活（含 F5 后重做变异④）。
- **R2 仍具名未修**：`EconomyEntryProbeR6Test` 里保留"同方案两实例重复占用"复现判据（断言当前仍为缺陷），**不得**为绿灯改掉；R2 关闭在 Phase A。
- **F4 终局复跑**：控制方在 F5/T9 之后独立跑 `clean verify`（日志 `impl-fix/evidence/f4-final-verify.log`）。
- ★ **口径更正（重要）**：此前 §14.28 与状态文档"无已知缺陷/整体最终验收通过"的表述**作废**——独立审核 R1–R5 + 控制方 R6 + T9 的 F5 缺陷证明：**编译绿/检查点/载体绿灯不等于能力实现**；终局只以行为读取点、独立复跑与具名未修项为准。

#### 15.16 T9 终稿、F5 终版 md5 纠正与"并发变异×门禁"事故留痕（控制方，2026-10-17）

- **T9 终稿（独立测试 owner）**：只写测试面。在 **F5 终版 main** 上跑 `tools/mvn-lock.sh -pl simos-app -am test` ⇒ **3513 / 0F / 0E / 7 skipped**（3:59 min）；production **84**（70+14 新）· economy **398**（387+11 新）· app **1012/0/0/7**。校准链：首轮 8 红（全为 F2 §6-B-1"库存通道进 B"旧口径，economy 8 红、app SKIPPED）→ 夹具按新语义备料（零放宽）→ 3488/0/0/7 → +25 条新判据 → 3513/0/0/7。
- **新增判据**：production `ProductionRuntimeQualificationR3Test` 6 · `ProductionRuntimeMergeUnionR4Test` 5 · `ProductionRuntimeTickFreezeR5Test` 3；economy `ProductionProbeSelectionR1Test` 6 · `EconomyEntryProbeR6Test` 4；R2 具名未修 1（`UnifiedBudgetNoDoubleBookingTest#r2SamePlanTwoInstancesStillDoubleBookTheSameAssetStake`，断言"当前仍是缺陷"，A 阶段修好会当场红）。
- **变异自证 5/5**：①R3 资格判定→false（4/6 红，还原 `3f4db47a…`）· ②R4 恢复 allZero continue（4/5 红，还原 `e5821de9…`）· ③R5 原始经验改回 current（1/3 红，还原 `3f4db47a…`）· ④R6 probePlanId 回物化 id（4/4 红，F5 终版字节上还原 `5c2868b7…`）· ⑤offer 物理量回旧配方（1/6 红，还原 `6a1c3bb2…`）。全部"红⇒还原⇒md5 逐字节一致⇒绿"，零等价存活。
- **F5 终版 md5 纠正**：F5 自己账本写 `bba2f785…` = **最终格式调整前的中间态**；F5 真正终版 = `5c2868b75dcf99dd23cd906d95268c44`（1559 行）。控制方 15:10 复核：当前 main = `5c2868b7…`、`probePlanId = registered.isPresent() ? industryId : plan.id()`、无 `T9 变异` 残留注释；其余被变异文件 md5 与 T9 基线逐字节一致。留痕 `impl-fix/evidence/f5-md5-correction.txt`。
- ★★ **事故留痕（控制方自记）**：T9 做变异自证期间会**临时改坏 main**；F5 的迟到编译（等锁到 15:09）与控制方 15:08 启动的 F4 `clean verify` 都落在**变异树**上 ⇒ 读数无效。F4 旧 log 已改名 `…VOID-collided-with-t9-mutation.log` 作废；控制方 kill F4，等 F5/T9 全退、main md5 基线核对后重跑 `f4-final-verify-2.log`。**纪律**：① 测试 owner 的变异自证放单独窗口，与 main 修复批/控制方门禁不重叠；② 门禁前置检查 = 所有 owner 已退出 + `ps` 无 mvn + main md5 基线核对；③ 任何"rc=0/绿灯"只在**干净树**上算数。
- **未做/未验证（T9 如实列）**：R2 未修；E≠1000 入口线性换算偏差未单列用例；R1 `isPreservedByQueue` 未改；R1 性能未测；R4 `tracksMerged` 未扩并集；clean verify/Spotless/SpotBugs/黄金归控制方终局门禁。

#### 15.17 F4 终局门禁（干净树，控制方，2026-10-17）

- `tools/mvn-lock.sh spotless:apply` 先收格式债（P/F1/F2/F5/T9 新增代码；apply 后 Spotless check 绿）。
- `tools/mvn-lock.sh -pl simos-app -am clean verify` ⇒ **rc=0 · BUILD SUCCESS · 19/19 SUCCESS · 05:55 min**；production **84/0/0** · economy **398/0/0** · app **1012/0/0/7**；`[frontend-gate] 412/412`；`[shaded-guard] OK（8123 条目）`；日志 `impl-fix/evidence/f4-final-verify-3.log`。
- 只有**干净树**读数算数：先前在 T9 变异体上跑的 F4 已作废（`…VOID-collided…`）。`spotless:apply` 使 F5 文件 md5 由 `5c2868b7…` 变为格式化后的 `8a736dfc…`（语义不变，全量测试绿）。
- 覆盖声明：F 轮 = R1/R3/R4/R5/R6 + F5；**R2 仍具名未修**（Phase A）。
