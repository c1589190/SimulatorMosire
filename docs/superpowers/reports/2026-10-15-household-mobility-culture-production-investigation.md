# 家户流动、文化与基层治理、生产与科技：现状调查（2026-10-15）

> 状态：代码与历史证据调查；包含明确标注的后续设计建议，不是实现约束设计书。
> 用户本轮原话逐字存于 [设计原稿 D-031](../specs/2026-10-02-architecture-design-source.md#d-031-家户逐-tick-流动表文化与基层治理生产与科技2026-10-15-用户原话调查阶段)。
> 基线：调查开始时 HEAD=`a505521b`；工作树已有另一批工具声明相关改动，本轮未接手。本文代码事实均回当前工作树核过；历史测试读数明确标为转抄，未在本轮复跑。
>
> **2026-10-15 补充口径**：用户已明确滑落、转业与市场同周期执行，生产方式/产业定义归统一前置模块；各领域在共享流动表上计算，复用工单；见文末 §9–§10 及设计原稿 D-031 补充。本文前述旧代码分类不约束新设计。

## 1. 核心结论

家户已有脱离经济模块的稳定身份、位置和成员份额。缺失的是文化、宗教、习俗及它们对生产和流动的规则。
现有经济把“阶层位置变更”“逐户生产方式排序”“家户间人口转移”拆成了不同机制，执行频率、目标集合和“速度”含义不一致。
本轮设想需要重设计流动决策层，同时保留现有的人口权威、财货随迁和跨模块原子落账能力。

生产技术并非一片空白：配方、生产单元、候选试产、效率修正都已存在；但没有独立科技模块、技术解锁/传播/升级链，也没有文化熟练度表。
代码中的 `ProductionMode` 主要是生产关系制度，不能直接等同于工艺技术。

## 2. 当前数据归谁

| 概念 | 当前所有者及实际形状 | 已核证据 |
|---|---|---|
| 家户本体 | Social：`Household(id, location, profile, members, vitalRates)` | `simos-social/src/main/java/io/mosire/simos/social/household/Household.java:56` |
| 人口归属 | `members: Map<PeopleLotId, Long>`；同一批人口可按人数份额属于多个家户；Social 跨表校验份额守恒 | 同文件 `:60`；`simos-social/src/main/java/io/mosire/simos/social/SocialData.java:213` |
| 家户画像 | 名字、描述、自由字符串 metadata；没有带规则语义的文化/宗教字段 | `simos-social-api/src/main/java/io/mosire/simos/social/api/household/HouseholdProfile.java:30` |
| 家户经济投影 | Economy：人口、劳动、参与率、经济位置视图、需求等；人口由 Social 刷回 | `simos-economy/src/main/java/io/mosire/simos/economy/model/HouseholdEconomy.java:81`；`simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java:714` |
| 经济阶层归属 | `HouseholdClassMembership`：原始/当前位置、参与位置、保留份额、债务压力、最近变更日 | `simos-economy/src/main/java/io/mosire/simos/economy/model/HouseholdClassMembership.java:45` |
| 商品与钱 | Actor 的 `HouseholdInventory` 是持久余额；经济日结算持有工作副本 | `simos-actor/src/main/java/io/mosire/simos/actor/model/HouseholdInventory.java:21`；`simos-economy/src/main/java/io/mosire/simos/economy/model/HouseholdEconomy.java:25` |
| 债、生产资料权利、生产关系 | Economy 的 `debtContracts`、`assetShares`、`relations` 等独立表 | `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:312` |
| 家户有效位置 | HEX 直接取格；UNIT 要跟随所属单位当刻位置解析 | `simos-app/src/main/java/io/mosire/simos/app/household/HouseholdPositionResolver.java:69` |

因此可以让新模块以同一 `HouseholdId` 挂文化关系，经济继续使用经济投影，不必另造一份人口与家户表。
但 metadata 仅能存文本，不能算“文化已实现”；现有程序没有消费文化标签的规则。

## 3. 现在的流动究竟在做什么

### 3.1 阶层滑落：只改一个家户的位置

实际入口是 `ClassSlideSettlement.settleAtMarketRoundEnd`：

1. 市场撮合、价格更新、市场统计完成后，逐个有人的经济家户计算债务/资产比。
2. `ratio = debt × 1000 / max(1, assets)`；默认斜率下速度为该比率的 100‰，封顶 1000‰。
3. 将速度换算成所需等待天数，比较 `day - lastTransitionDay`。
4. 到期且有下一级，则把该户 `currentPositionId` 改成下一级；不选另一个家户，不转移一部分成员。

证据：`simos-economy/src/main/java/io/mosire/simos/economy/time/ClassSlideSettlement.java:153`、`:190`、`:237`、`:354`、`:391`。

这里“速度”实际控制的是整户换位置的等待期，不是“每 tick 向某户流多少人口”，也不是持续累加的流动进度。
既不检查债务相对上一 tick 是否增长，也没有库存增长触发向上流动的对称分支。
“资产”目前是本格市场计价币余额与有价商品库存折价；不含完整的土地、作坊权利和债权估值。
证据：`simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementOrderBook.java:271`；`simos-economy/src/main/java/io/mosire/simos/economy/time/PrimaryModeRanking.java:237`。

“下一级”优先读 `downwardPositionId`，否则按同 mode 的生产资料关系、剩余分配角色排序。
旧贫/中/富农位置的角色元组相同，机械排序无法表达富→中→贫。
证据：`simos-economy/src/main/java/io/mosire/simos/economy/time/ClassLadderBook.java:35`、`:96`、`:131`。

### 3.2 家户间转业：确实搬人，但不是完整目标表

`ModeMigrationPolicy` 的实际工作：

- 源户来自经济家户表：有人、有可解析阶层归属，而且折算后有劳动；无劳动户仍可能作为迁入目标。
- 遍历已有格索引中的本格/距离一的邻格，逐 mode 算预期收益；不是从 Social 的全部邻近家户逐户建边。
- 每个 `(hex, mode)` 至多挑一个已有户：剩余人口容量最大者，并列按 ID；也可计划新建目标。
- 本格当前 mode 整组跳过；流民另走兜底分支；产业模板、可生产位置、资产、原料可得性、预计收益等提前混入候选评估。
- 普通转业依赖目标相对当前的预期单位劳动收益差；未按“同等经济水平”限制目标户。
- 先以最快目标速度和人口上限算全户可迁总人数，再按收益差权重分配；每笔携带的速度并不等于该边实际独立积分得到的人数。
- 亏损且无法维持再生产的 A 规则可走整户满速；其他分支有默认每次最多约 10% 的人口闸，小户使用至少一人的取整。

证据：`simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationPolicy.java:450`、`:730`、`:907`、`:932`、`:940`、`:1087`、`:1522`、`:1619`、`:1925`。

已有 `PrimaryModeRanking.Table` 是生产方式收益排序读数，不是源户→所有其他户的流动矩阵；不能因为已有“表”就判本轮需求已满足。
证据：同文件 `:780`。

### 3.3 两套机制频率也不同

| 阶段 | 当前触发 | 实际效果 |
|---|---|---|
| Social 日初投影 | 每 tick（日循环） | 注入成员构成、劳动预算、需求 |
| 阶层滑落 | 有市场轮时，市场轮末 | 换本户阶层位置 |
| 转业规划与执行 | 任一生产周期关账且利润会话存在 | 搬人到已有/新建家户并搬经济关系 |
| Social 人口落账 | 当日经济 step 之后 | 消费转移 outbox，成员份额变更并刷新经济投影 |

证据：`simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java:620`、`:642`、`:700`；
`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:2699`、`:3300`、`:3405`。
市场轮后的借粮、还款、部分计息/关账和政府日结还有后续步骤，所以“市场轮末”不能直接称作“整个 tick 的经济全部完成之后”。

另有旧 `PopulationMigrationPlanner`，按城市吸引力从农村批次规划入城；当前 main 只找到该类定义，未找到生产调用方。
它不是当前家户转业的实际入口，不应用它解释当前运行行为。
证据：`simos-app/src/main/java/io/mosire/simos/app/time/PopulationMigrationPlanner.java:59`；检索命令见 §8。

### 3.4 已有的执行能力值得保留

`ModeMigrationSettlement` 已处理货币逐币种、可用商品库存、债务、可移动资产、生产组织和劳动承诺；
经济只产出 `EconomyPopulationTransfer`，由 app 经 `MigrationSocialBridge` 用 Social 工单选成员份额、建目标户和搬人。
这些工作副本最终在同一次时间推进的 revision 中落账，桥接失败整次拒绝。

证据：`simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java:446`、`:572`、`:604`；
`simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java:700`；
`simos-app/src/main/java/io/mosire/simos/app/household/MigrationSocialBridge.java:114`、`:157`。

新建目标的 profile 目前只给“迁入户”名字与 source 元数据，不继承源户文化；合并已有户保留目标画像。
这不是一个已实现的文化继承政策，只是目前没有文化数据时的建户行为。
源户清空后的空壳保留与 D-022 早期“清零删户”措辞也已有漂移：
`simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java:685`，后续设计应显式规定空户及剩余合同的生命周期。

## 4. 旧日志和裁定说明了什么

以下是历史记录，非本轮实测结果：

- W4 验收报告明确记录 `36.5%` 的滑落评估为 `no-lower-target`，原因正是角色排序不能表达富→中→贫。
  来源：`.superpowers/sdd/2026-10-11-w4-acceptance/report.md:9`；公开问题索引：`docs/superpowers/plans/2026-10-02-open-bugs.md:396`。
- W9 之后的记录说明，收益差速度曾把近零收益放大为大规模迁出，随后加入正收益规模基准与人口上限。
  当前代码已包含这些修正，不能按首版 W9 公式重新设计。
  来源：`ModeMigrationPolicy.java:735`、`:2082`；`.superpowers/sdd/2026-10-11-w9f-test-batch/impl-ledger.md:111`（历史分布及“速度读数与人口闸不同”说明）。
- W9 历史报告还明确“新建户为零”是根据户数未变反推，并非直接计数；本轮不把它升级为当前所有世界不会新建户的结论。
  来源：同账本 `:130`。
- E3 经验积累在旧计划里设计过，但台账记为未做；当前 `EconomyData` 也没有该组件。
  来源：`docs/superpowers/plans/2026-09-29-economy-evolution-r4-plan.md:297`；`.superpowers/sdd/2026-09-29-economy-evolution-r4/progress.md:359`。

### 口径溯源表

| 来源 | 已有口径 | 与本轮的关系 |
|---|---|---|
| 设计原稿 D-022 | 改生产方式通过转入已有/新建家户；不原地改源户 mode | 本轮家户间流动沿用这一方向；“技术升级”是否算换 mode 尚需划清概念 |
| D-023 | 可移动资产、逐币种余额按人口随迁；流民通过转入家户获得生产身份 | 新表执行不能只改人数，必须处理经济随迁 |
| D-024 | 预期利润由配方、价、真实可寻址需求现算；不以已有利润读数作为门 | 可复用估值能力；新文化/技术条件也须进入同一预期口径 |
| `2026-10-10-unified-price-and-ordered-settlement-design.md:404`，W9 实现账本 | 债务驱动同生产方式内滑落，收益差驱动转业 | 本轮统一为同一张方向表，且新增上行与同水平条件；旧拆分不构成新设计上限 |
| `2026-10-04-household-culture-and-community-effects-plan.md:1` | 仅规划文化、宗教、基层组织；举过信用/利息限制例子 | 本轮已明确独立“文化与基层治理”方向；旧候选宿主 A/B/C 不应重新当成已定边界 |
| `2026-10-09-social-api-household-architecture.md:14`、`:19` | 家户统一身份；未来文化宗教挂同一 ID；当批不实现效果 | 有可用身份基础，没有文化实现 |
| `2026-10-23-production-efficiency-framework.md:9`、`:69` | 机制提供动态修正；GM 产品产出数量覆盖与机制修正区分 | 新模块可供给修正；技术是否修订配方/默认产出需明确写权限 |
| 本轮 D-031 | 每户每 tick 全方向表；三项经济速度顺序计算；两个新领域方向 | 是本轮设计来源；尚未定公式、文化迁移规则和技术升级单位 |

日期疑点：生产效率文件名含 `2026-10-23`，但 `git log -1 --format='%h %aI %s' -- docs/superpowers/specs/2026-10-23-production-efficiency-framework.md`
输出 `db4f899d 2026-10-07T04:40:16+08:00 ...`。保留文件名标签与提交日期，不把它解释为本轮之后的裁定。

## 5. 文化与基层治理现状

当前 main 中没有文化/宗教的正式领域状态与效果计算；相关英文命中主要是未来接入注释。
Social 的状态包含家户、人口、饱食度和逃亡等，不能据此称文化、宗教或基层组织已落地。
证据：`simos-social/src/main/java/io/mosire/simos/social/SocialData.java:85`；命令见 §8。

旧文化规划提出过放贷额度、合同利息、行会/教会/村社成员效果，但明确“只登记规划，不实现”。
这些是后续可溯源的需求，不能把它们都算成本轮已经要求立即实现的范围。

最接近本轮需求的现成接口是：

```text
ProductionEfficiencyModifier(ProductionUnitId unit,
                             long modifierPerMille,
                             String source,
                             String reason)
```

目前有效域 `0..2000`，`1000` 为中性；每日替换注入集，同 unit 重复注入拒绝，消费后清空。
逐 tick 累计修正，生产周期末按天平均影响产出，仍不是每日入库。
证据：`simos-economy-api/src/main/java/io/mosire/simos/economy/api/production/ProductionEfficiencyModifier.java:27`；
`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayStepper.java:485`；
`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:1812`、`:1853`、`:3516`。

但当前 main 没有调用 `updateProductionModifiers(...)` 的生产桥，文化等机制未提供输入。
并且它按生产 unit 生效，不按家户或成员生效：多家户参与同一 unit 时必须定义如何按实际劳动组合文化熟练度；
文化、科技、天气等多个来源同时出现时，也必须先合成为 unit 的单个输入，或重设计契约。
`ExpectedProfitBook` 当前也没有文化/效率修正输入；仅在收获结算加成，会使“实际赚钱”与迁移决策的“预期赚钱”脱节。

## 6. 生产与科技现状

| 当前概念 | 实际含义 | 与科技模块的关系 |
|---|---|---|
| `ProductionMode` | ID、名字、version、绑定阶层结构 | 主要表达生产关系；version 本身不等于升级机制 |
| 默认 mode | 实物定租、分成租、货币租、雇农、作坊、自耕、商人、流民等 | 既有词表大量是制度/组织形式，不是具体农业或纺织工艺 |
| `ProductionRecipe` / `Industry` | 单位规模的资产要求、劳动、投入、产出、周期；Industry 还带位置化模板及旧运行字段 | 已有格式化配方；可抽取定义所有权，但不是从零建生产体系 |
| `ProductionProcess` | operator、industry、字符串 modeKey、生产进度、周期劳动和投入 | 是实际运行单元；不能与 mode 的目录身份混为一谈 |
| `ProductionEnterprise` / `ProductionRules` | 谁组织、谁出资产、谁出劳动、谁拿分配 | 属经济组织与分配规则，不宜只因“生产”二字整体搬到科技域 |
| `ProductionCandidate` 与入市 | GM 登记候选，有有效需求及资产/劳动/投入时家户可试产 | 有采用预设能力，没有自动发明、解锁和完整升级生命周期 |

证据：`simos-economy/src/main/java/io/mosire/simos/economy/model/ProductionMode.java:21`、
`DefaultProductionModes.java:89`、`ProductionRecipe.java:54`、`Industry.java:126`、
`ProductionProcess.java:45`、`ProductionEnterprise.java:73`、`ProductionCandidate.java:57`；
`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyEntrySettlement.java:53`；实际调用在 `EconomySettlement.java:1285`。

当前没有独立生产/科技模块，也没有技术树、技术前置、地区/家户掌握状态、传播、研发、升级成本/时间和在产周期切换的成套机制。
E3 实践经验也没有落地，不能把已存在的生产效率余数表解释成经验/熟练度表。

## 7. 后续规范需要补全的接口与规则（建议，未裁定）

### 7.1 建议保留的所有权形状

| 责任 | 建议归属与接口方向 |
|---|---|
| 家户身份、位置、成员份额、生死 | Social 继续唯一拥有；新模块用 `HouseholdId` 关联 |
| 文化身份、宗教、习俗、基层归属、文化对生产的适应加成 | “文化与基层治理”拥有；输出家户×生产方式/工艺的具名修正及规则来源 |
| 生产定义、工艺/技术版本、可采用条件、升级关系与技术掌握状态 | “生产与科技”拥有；给经济提供可解析定义、可用技术与升级结果 |
| 钱、货、债、经济关系、利润、人口流动的经济驱动力 | Actor/Economy 延续各自账本；经济读取新领域输出，不反写它们的私有状态 |
| 跨模块执行与失败原子性 | app 组合协调；领域公式在各自领域，Core 保持组合调度 |

新模块的 Maven 名称、契约包及允许依赖图尚未裁定；当前 `pom.xml:9` 起的模块列表没有这两个领域。
定义抽取将影响 `EconomyData.modes/industries/candidates`、ID 的包归属、codec/changeset、GM 写口、播种、预期利润、在产 unit 引用和清理生命周期。
应先画实际迁移依赖图与唯一写者，再改 enforcer；不能把旧经济实现整包搬走或复制目录两边维护。
Alpha 旧档按既有裁定重建；“回退”应是撤回未发布代码/重建世界，不建设旧存档兼容层。

### 7.2 新流动表至少应能解释这些内容

建议每个源户维护当 tick 的方向读数：目标稳定家户 ID、有效 hex、目标经济位置和生产定义、各阶段贡献、合成速度、预计/实际人数、限制理由。
基础枚举应先保留本格和六邻格所有结构上可达的其他家户；经济/文化导致零速度时保留行及原因，避免又在候选期静默删掉方向。
这是对 D-031 的接口建议，不是已批准字段列表；是否持久保存全表或只保存决定下一 tick 所需余数/审计结果仍需设计。

| 必须补全的问题 | 当前缺口及影响 |
|---|---|
| “经济水平”用什么比较 | 当前阶层是生产关系位置，库存是市价存量；两者不能自动等同。需明确家庭规模归一、生活保留、负债与资产范围及同水平容差 |
| “规模变大”指存量还是增量 | 现有滑落按当前债资比，不看增长；库存上涨还可能仅由物价变化造成。应先确定指标再标定公式 |
| 三项速度顺序如何合成 | 已明确顺序，但还未明确覆盖/累加/剩余人口预算，及同户同时下行和上行时如何分配 |
| 全图同时流动如何结算 | 需要冻结统一时点输入，避免 A→B 后 B 在同 tick 再把新增人口转出；源总出流上限、目标真实承载、取整余数需统一 |
| 文化如何随人流动 | 并入异文化户是否立即同化、保留背景或受到阻力；新建目标继承哪一文化。现有批次份额不带这套文化语义 |
| 文化技能加成作用于什么 | 制度 mode、具体工艺、产业还是技能族；一个 unit 的多文化劳动如何合成；收益预测与实际结算需共用语义 |
| 科技升级的单位和时点 | 升级目录定义、某地可用技术、还是某户在产工艺；触发、成本、时间、周期中投入与余数处理均未定义 |
| 技术升级与 D-022 的关系 | 工艺改进是否保留家户；生产关系改变是否继续转户。先定义“生产方式”的两个语义，不能静默覆盖旧裁定 |
| 单位户/无经济投影户/空户 | D-031 说“每个家户”，现有迁移只扫经济表；需明确普通户与官吏户、军户、国库户等的参与语义，位置用有效 hex |
| 新建目标及流民 | 旧机制可新建户、无可行去处时转流民；新表以已有其他户为基本方向后，这两种出口如何表达需明确 |

建议先定三项语义：**家户代表的同质人群边界、经济位置与技术工艺的区别、流动表各阶段如何产生可执行人数**。
再据此定两个新领域与经济的契约、写权限和 tick 顺序；无需现在补全所有宗教/基层治理玩法。

## 8. 调查命令与验证边界

本轮仅阅读代码/历史记录并写本文与 D-031；没有修改 Java/POM、没有构建 jar、没有启动/推进世界、没有跑测试或变异。
历史报告的通过结论和读数不视为本轮当前工作树的验证。

主要可复核命令：

```bash
git status --porcelain
git log --oneline -20
rg -n '家户|文化|科技|技术|流动|生产方式|阶层|基层' docs/superpowers/specs/2026-10-02-architecture-design-source.md
rg -n -i 'culture|religion|technology|techlevel|文化|宗教|科技|熟练' simos-*/src/main/java --glob '*.java'
rg -n 'updateProductionModifiers' simos-*/src/main/java
rg -n 'PopulationMigrationPlanner' simos-*/src/main/java
rg -n 'ClassSlideSettlement|ModeMigrationPolicy.plan|ModeMigrationSettlement.apply' simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java
sed -n '1,180p' .superpowers/sdd/2026-10-11-w4-acceptance/report.md
sed -n '111,130p' .superpowers/sdd/2026-10-11-w9f-test-batch/impl-ledger.md
git log -1 --format='%h %aI %s' -- docs/superpowers/specs/2026-10-23-production-efficiency-framework.md
```

关键实际检索结果：

- `updateProductionModifiers`：main 有契约/说明及方法自身，未找到业务调用表达式；日循环只调用现有 composition/labor/needs 注入后进入 `step(day)`。
- `PopulationMigrationPlanner`：main 只命中该类自身，没有生产调用方。
- 文化/科技关键词：main 命中未来 SPI/注释，以及 `agriculture` 中的词串误命中；未见文化或科技领域状态/效果实现。此结论同时核对 Social/Economy 的 record 组件与根模块列表，并非仅凭关键词零命中。

尚未验证：新流动公式数值稳定性、全表成本、文化混合后的身份规则、技术升级后的生产守恒及分段推进等价性。这些必须在约束设计获定后，按新验收判据统一验证。

## 9. 用户补充后的裁决边界与追加调查（2026-10-15）

用户原话逐字存于设计原稿 D-031 补充。已定：滑落/转业与市场同周期；家户方向必须列表；生产方式即用户所指产业及产出定义，统一交前置模块。无需重新裁决是否建表、是否拆模块、是否保留旧两套流动时点。

### 9.1 周期管理的追加代码事实

- `MarketSettlement.java:1400` 的真实触发优先级为例行日（每 5 天）→ 任一产业关账日 → 每窗口第 3 天且缺粮的追加轮；市场当前并非单一固定周期。
- `EconomySettlement.java:1718`、`:1845`、`:1904` 将各产业/生产单元的关账汇总为全局 `anyCycleClosed`。
- 该标志又驱动还款（`:2768`）、计息（`:2891`）、家户转业（`:3300`）等跨家户步骤。因此改某个产业的生产周期，会改变全局经济动作的触发日；是否连带改变数值结果需专门验证，不能把“统一入口”当成已经解决。
- `Industry.java:126` 同时包含配方/周期等定义和 operator、进度、周期投入等实例字段；统一前置定义所有权时，应调查并分清定义与运行实例，避免把一个模板改动无意变成全世界在产实例的即时改动。

复核命令：

```bash
sed -n '1397,1425p' simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java
rg -n 'anyCycleClosed|currentCycle =' simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java
sed -n '2745,2788p' simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java
sed -n '2878,2900p' simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java
sed -n '120,148p' simos-economy/src/main/java/io/mosire/simos/economy/model/Industry.java
```

**对 §3.3 的事实更正**：其中“市场轮后的借粮”表述受旧段名/注释误导。当前 `EconomySettlement.java:2745` 起明确退役独立借粮腿；实际后续仍有偿还、计息等，但不应再把独立借粮列为运行阶段。原文保留，追加更正。

### 9.2 尚需裁决的五组语义

| 主题 | 尚未决定 | 调查者建议（不是用户裁定） |
|---|---|---|
| 共用周期 | 统一固定节拍还是跟随全部现有市场触发；追加轮是否追加人口流动；逐 tick 建表与周期执行的关系 | 市场与人口流动共用可配置的结算时点，生产持续时间独立定义；追加轮如保留，应按实际经过时间计算流速，避免多开市就凭空加速迁移 |
| 阶层/经济水平 | 比较总库存、人均库存、净资产还是显式位置；债务/库存条件取规模或增长 | 先明确单户是同质人群集合，比较时处理人数规模；具体指标给候选公式与反例再定 |
| 三步速度 | 顺序修正同一边、相加，还是逐步分配剩余人口；是否继续保留 A 规则整户满速出口 | 同一结算基态计算方向与贡献，一次统一限制总流出并落账；三个业务贡献的合成方式交用户定 |
| 文化连续性 | 跨文化人口进入目标户后采用目标文化、保留来源，还是形成新户/受阻 | 把家户同质性与人口流动后的文化继承规则一起定义，不默认立即同化 |
| 技术升级范围 | 升级全局定义后是否所有实例自动升级，或只是解锁、各地/各户再采用；升级是否保持家户身份 | 前置模块统一拥有定义与升级关系；将定义修订、技术可用和实际采用的状态显式表示，具体作用范围由用户决定 |

### 9.3 不需要推给用户的技术调查

1. 盘点所有周期触发和周期编号消费者：市场、生产、还款计息、利润、迁移、财政承诺；提出统一调度与顺序方案，保留真实时长换算。
2. 追踪配方/产出/制度/候选/GM 覆盖的全部读写点与重复公式，提出前置模块的契约、定义 ID、唯一写者和依赖迁移图；不以旧类名要求用户重命名概念。
3. 核对文化熟练度从家户劳动作用到生产单元的权重，以及预期利润和实际产出如何共用定义与修正。
4. 核对新建户、单位户、空户、流民、目标承载与财货债随迁；定义全表成本及分数人口余数的验证方案。

本次追加仍是静态调查，没有运行世界或修改生产代码。

## 10. 各领域算表、工单落账：用户再次明确后的收束（2026-10-15）

用户本次原话逐字存于设计原稿 D-031 最新补充。本节收束 §9.2：经济指标及文化同化的具体计算归各领域设计，不再逐项作为跨模块方案必须等待用户决定的门槛。

### 10.1 已核代码基础

- 经济已有实际收益簿 `EnterpriseProfitBook.java:209` 的 `netPerLaborScaled`，以及候选预期收益 `ExpectedProfitBook.java:125` 的 `Prospect`（含 `netPerLaborScaled`）。因此可由经济内部读取既有收益、债务、库存等数据算经济流动贡献。代码现有这一指标是“单位劳动净收益”；不能仅凭用户口语“利润率”就声称已有完整收益/资本比率。
- `HouseholdWorkOrder.java:24` 表达目标、原因、来源、有序方案和可选幂等键。
- `HouseholdWorkOrderPlan.java:76` 的封闭步骤集合支持建户、位置、成员增减/转移、人口调整和生死率；没有文化计算阶段或文化状态步骤。
- `HouseholdWorkOrderBook.java:55` 在 Social 私有工作副本顺序执行，整单失败拒绝；`SubmitHouseholdWorkOrderHandler.java:87` 起接收命令并调用它。跨领域同 revision 的既有组合先例仍是 `PopulationEconomyTimeParticipant.java:700` 与 `MigrationSocialBridge`。

因此工单是现成的 Social 受理/执行基础；“多领域依次计算共享流动表”仍要补阶段协议及接线，不能宣称当前 Social 工单已经自带通用跨领域计算流水线。

### 10.2 下一份约束设计应写清的形状（技术建议，尚未实现）

1. 初始化逐家户的全部邻近方向，以稳定家户 ID 为边端点；保留基础候选，领域计算不静默丢方向。
2. 文化模块在该表上计算文化影响、生产熟练度/适应输入及同化方案；生产与科技模块提供统一生产定义。
3. 经济按首轮原话的顺序计算负债、库存、生产收益因素，并在前述领域输入基础上最后计算实际速度/经济方案；不让组合根复制这些领域公式。
4. 最终方案共同明确实际迁移人数；翻译为 Social 工单，同时形成经济财货/债务变更及文化模块自有变更。若文化结果依赖实际人数，由文化模块据最终实际方案完成自己的状态变更，不能按尚未执行的拟议人数先持久改状态。
5. 共享表需明确每阶段读写责任、阶段序和拒绝语义；多个模块对同一条方向的计算有来源可追溯。表可以采用不可变阶段结果，概念上仍是同一份流动方案，无需多个模块任意修改彼此状态。
6. 以统一结算基态约束源户可转人口，统一守住总出流/目标限制、确定性和余数；人口、经济、文化变更纳入同一次 Command → ChangeSet → Revision 路径，失败整次不提交。

调查方向随之明确为：共享表契约放置与依赖、领域阶段接线、Social 工单复用范围、财货及文化变更的原子提交、收益预测与实际生产的共同输入、统一周期与分段推进等价性。不再重新追问用户是否允许领域自己算内部规则。
