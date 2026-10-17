# D-032 补充工程裁决：生产状态归属与调用边界（2026-10-17）

> **后续偏离复核（2026-10-17）**：`docs/superpowers/specs/2026-10-17-d032-stage1-deviation-review-decision.md` 明确本裁决的方案多地点复用不降级为开放项，并补充默认熟练度效果、经验精度、逐 tick 增长与整周期贡献的实现约束；状态归属、无新增依赖及账户边界继续有效。
> **状态：控制方受用户授权作出的工程裁决，已定、未实现。** 本文供相邻会话继续编写约束设计书；本轮不派实现、不改业务代码。用户原话见 §8，工程裁决不冒称用户逐项说过的原话。
> **承接**：`docs/superpowers/specs/2026-10-17-d032-production-execution-decision.md`（D-032/U8），继续遵守 D1/D2/D3 删除、culture 保留占位及标签面、自动需求—利润选择、通用人口迁移与经验拆合。
> **局部收口**：前述执行裁决 §3 的生产实例/进度归属不再待定；本文 §1 直接冻结。对用户转述的隔壁方案，采纳其按状态划分及参数传入劳动事实的主线；临时只读视图与账户落账的修正见 §4/§5。
> **权威与权限**：用户业务设计优先；这些是落实已定业务设计的工程取舍，由控制方负责，不再以“要不要开始写设计书”或“类放哪里”请求用户批准。

## §1 状态归属：现在定下

| 状态/职责 | 唯一拥有者 | 裁决 |
|---|---|---|
| 生产方案与配方、生产条件、可配置熟练度公式 | production | 统一生产规则权威；不在 economy 再保留一份可覆盖它的定义。 |
| 生产实例 ProductionProcess | production | 从 EconomyData.units 迁出。实例身份、方案引用、经营者引用、地点、生产进度、周期实际劳动与实际投入累计在此维护。方案与实例分开，地点不迫使复制共享方案。 |
| 生产经验、熟练度及解锁状态 | production | 家户×经验条为账目方向；实际劳动贡献作为输入，不通过旧文化专用接口回读 economy。遵守既有衰减、增长、门槛与人口拆合要求，不在本裁决另定参数。 |
| 生产效率累计、精度余数 | production | 随生产计算迁入并按新经验公式重建；删除文化身份倍率、旧 modifier 接收链及其固定上限，保留需要的确定性与跨周期精度机制。 |
| outputQuantityOverrides（GM 生产产出数值覆盖） | production | 随产出定义和计算迁入；GM 写入口、校验、持久状态与读数一起收口。预计/实际都读取同一份有效参数。 |
| relations / ProductionRules | economy | 它规定工资、地租、分成与受益人，属于“怎么分”，不属于“怎么生产”。ProductionSettlement 的此类分配计算留 economy。 |
| operatorConditions | economy | 经营盈亏、市场表现、缩产/退出等经济状态留此；需要影响生产时，转为本次计划规模/预算等明确输入，不把整份经济状态塞进 production。 |
| allocations、劳动承诺与资源分配、assetShares、productionOrganizations | economy | 统一决定谁能使用多少资源、谁为哪个实例出多少劳动；通过稳定实例 ID 关联 production，不再拥有实例完整状态的持久镜像。 |
| 市场、价格、需求、债务、工资、租金、分成与发行政策 | economy | 继续负责选择、估值、交易与经济关系。 |
| 实际钱粮库存、货币账户 | actor | 生产消耗/产出及经济支付通过既有账户路径落账；不迁入 production 或另建生产库存账。 |
| 家户身份与人口 | Social | production 只维护本域经验等状态；人口仍走 Social 工单，迁移时各领域同步拆合。 |

经济侧既有单位引用完整性检查，改为针对同一候选态的 production 只读契约校验；不能因 units 移走就把守卫删除。生产实例在 production 内完成生命周期变更，经济提出创建/停止请求，相关经济关系一并更新，原子提交。

## §2 调用与依赖：不增模块依赖

- 本次不改变 POM 依赖图、enforcer 允许面或模块边界表。
- 新生产试算/执行接口及专用输入输出类型，统一优先放在 `production.contract`；实现留 production 内部，由组合根装配。economy 仅消费公开契约，不 import production 内部状态/算法类。
- 现有稳定 ID 和通用经济值类型继续复用 economy-api；不因为名字含 economy 就复制一套生产 ID。economy-api 禁止引用 production 类型。
- 劳动事实、最终资源量、外部属性作为本次调用的不可变输入。经济给实际劳动分配，组合根提供其他领域事实；production 不反向读取 EconomyData，不保留旧 D3 端口或为它造替代回读接口。
- 原有“只读 contract”限制继续禁止跨域直接写状态；公开契约可以承载基于输入计算候选结果的行为。具体类名/方法名由设计方决定，不需要用户选择。
- ChangeSet 的对外传递可使用现有 util.state.ChangeSet 契约；具体 ProductionChangeSet 仍由 production 自己从完整本域候选态派生，economy 不必越过包边界自行构造它。

## §3 试算、分配、实际生产

```text
共同基态及本 tick 的外部事实
  → economy 整理可用资源、经济约束与候选
  → production 试算：可行性、规模、劳动/原料要求、预计产出
  → economy 按需求—利润排序，统一分配劳动、土地、工具、原料
  → production 按最终实际投入计算生产、进度与经验，产生本域候选态/变更集
  → economy 用生产回执计算估值、工资/租金/分成等经济变化
  → 持账方计算实际库存/货币变化；需要迁移时 Social 及经验账同步处理
  → 统一候选态校验，同一 Revision 原子提交
```

1. 预测无写入；预计与实际共用生产规则、公式、覆盖参数及时间单位。相同输入应算出相同生产结果；实际获得的投入变化时，结果可以与原先预测不同。
2. production 无需拿市场价格/需求决定物理产量。价格、需求、工资、租金等由 economy 用于选择、估值与分配；外部生产条件按已声明属性传入。
3. 同一劳动/资产/库存只分配一次；候选按资源缩减后，按 D-032 要求继续考虑能使用剩余资源的选项。不能把每户最大可用量同时当成多个方案的实投。
4. 生产回执保留实例、账期、实际出工家户与贡献、实际投入/毛产、精度与进度变化；经验依据实际贡献计算。协作生产只记一份总产出，“出过工”不等于取得全部产品。
5. 调度仍依据世界 tick/绝对时间，额外市场轮与重复预测不推进生产、不重复涨经验；拆段推进、恢复和回放须守同版本自洽。
6. 不要求把所有经济关系都放到生产之后：租地、雇工和原料购入的可用性约束必须在执行前得到满足。上图表达职责，不授权重排既有业务时间线。

## §4 三处补充纠正

**临时只读视图允许，第二份可写权威禁止。** economy 可以持有由当前 production 快照/候选态生成的只读接口、参数快照与本轮索引。禁止的是另持久化一份可独立修改的 units/进度/生产参数，不是禁止消费者读取或建临时索引。视图不得跨 revision 静默陈旧；同次 Advance 后续阶段须看到本次最新候选态。

**到货留 economy。** EconomySettlement 的 :1600 是在途运输的到达结算，不是生产产出；不能因紧邻生产段就随生产代码搬走。运输在途、交付与市场履约仍属经济；生产模块处理实际生产消耗与产出事实。既有“当天到达可用于当天消费/交易”的顺序保留。

**需要最小接线，不需要空转的适配架构。** 各域变化要经过组合根汇合与持账方执行，不能把“没有循环依赖”说成完全没有集成工作。复用现有提案、账户会话与候选态校验，不为文件搬迁造一套新的框架。

## §5 账户与铸币：生产事实和财务落账分开

- production 的 ChangeSet 只改 production；economy 的 ChangeSet 只改 economy。库存/货币实际改变还需要 actor 的 ChangeSet；涉及人口/政府状态时，同次提案纳入其所属模块变化。不能只提交两份 ChangeSet 后把账户更新遗漏。
- 最终生产消耗、产出与账户候选态必须逐笔对得上；账户不足等普通约束在预算/实投阶段处理，不能先把生产推进再偷偷少扣投入。契约故障或跨域不一致拒绝整个候选提交，不允许生产进度成功但消耗落账失败。
- **铸币生产率与技术产能归 production；发行资格、政府发行上限、发行记录与账户入账留 economy/持账方。** production 算可产出的候选数量，经济层按既有发行规则执行并返回明确实发行读数，不重复生产/入账。
- 现有 `MINT_MILLI_PER_FURNACE_CYCLE` 是每炉产出率覆盖，属于技术数值权威，随有效产出参数归 production；原 GM 可调能力保留并改由正确领域接收，禁止 economy 再覆盖一次。政府 `mintScalePerCycle` 等发行约束继续归原经济/政府状态。
- 本次不改变既有币种、发行上限及发行所附带债务/记录等玩法，也不擅自引入未发行部分的库存、退料或新回收规则。迁移测试要对照既有规则，不能凭“模块重构”改变结果。

## §6 隔壁现在可以直接做什么

据本文与 D-032/U8 继续写完整约束设计书；**不要再请用户决定上述状态位置、公开类型放置或是否开始写文档。** 设计书负责补齐实际接口/载荷、状态组件、同 tick 候选态传递、生命周期与跨域校验、日志、文件 owner、完整验收，然后按既定工作树/无头 dsh 规则执行已获授权的后续任务。本裁决本身不启动 dsh。

必须覆盖的迁移面：production 与 economy 的完整状态、codec、ChangeSet、往返不变式；所有实例引用/构造守卫、命令/工具/GUI/权限查询；两个现有推进接线及账户提交路径；预计生产、劳动规模、实际收获和铸币消费的统一计算入口。按当前精确引用生成文件清单，不把转述的命中数量当冻结范围，不用 `.units()` 把军事单位误算进来。

验收至少包含：同输入试算/执行一致；预测无副作用；跨方案资源不重复占用且缩减后回填；多户协作产出只算一次；经验按实际贡献累加及迁移加权；GM 覆盖同时影响预计与实际；经济分账保持原规则；资金/库存与生产同步提交；无陈旧视图、悬空实例或重复推进；跨域失败零部分提交；完整状态逐字段往返。测试和变异自证留到实现完成后由独立测试 owner 统一执行。

现有原话已覆盖的工程取舍由控制方继续处理；只有需要改变玩法、用户设计、权限或五条铁律，且溯源后仍无答案时，才带具体业务后果询问用户。本轮没有此类未决问题。

## §7 源码依据与验证限度

2026-10-17 在本 worktree 回代码核过：

| 现场 | 事实 |
|---|---|
| `simos-economy/pom.xml:42`、`simos-production/pom.xml:35`、`simos-economy-api/pom.xml:92` | 既有依赖方向已足够；API 禁止反向依赖 production。 |
| `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:318`、:334、:335 | units、outputQuantityOverrides、productionEfficiency 当前仍在经济状态。 |
| `simos-economy/src/main/java/io/mosire/simos/economy/model/ProductionProcess.java:49` | 实例内有进度与周期劳动/实际投入累计。 |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionEfficiencyBook.java:70` | 旧效率累计接收被删除链提供的 modifier，不能机械照搬为新经验体系。 |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:1600` | 到货是运输在途到达，须保留经济归属及日内业务顺序。 |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/MintSettlement.java:233` | 目前铸币技术产出率可被经济政策覆盖；重构需收口权威，保留可调能力与发行玩法。 |
| `simos-production/src/main/java/io/mosire/simos/production/change/ProductionChangeSet.java:36`、`simos-util/src/main/java/io/mosire/simos/util/state/ChangeSet.java:14` | 本域具体变更集已实现通用 ChangeSet 契约，可沿既有提案通路传递。 |
| `simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java:1222` | 当前提案实际汇合 economy/social/actor/production 等模块变化，账户不能漏掉。 |

工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。本轮只查源码与写裁决；未实施迁移、未派 dsh、未跑构建/测试、未提交/推送。上述验收是未来判据，不是已通过结论。

## §8 用户原话附录（逐字，2026-10-17）

### 已有 D-032/U8：业务方向

> D3直接删没毛病
> D4我建议归生产方式模块，生产方式模块干脆提供单个家户选用某个生产方式，预计/实际的产出和家户状态更新，而不是只提供一个模板，经济模块调用生产方式模块之后，负责计算随后的家户间经济关系，

### 本次授权：工程问题由控制方裁决

> V4.1Flash又说一些我不想听懂的话了，出裁决，真需要决定叫我

### 后续补充材料

用户转述隔壁按状态重切的方案后说：

> 我让V4.1Flash再想了一轮

转述方案作为本次评估材料，不能当成用户已逐项批准其全部细节；本文明确列出采纳部分与纠正项。
