# D-031 P1：生产定义唯一权威迁移与合理性验收（2026-10-16）

> **局部取代者：`docs/superpowers/specs/2026-10-17-d032-production-experience-choice-design.md`（2026-10-17，用户原稿 D-032）。** 本文保留定义迁移批的设计与历史证据；后续经验/科技/可配置公式及主副业选择采用新设计，旧“文化倍率 P2”方向不再作为实施依据。本批历史的“不改算法”范围不能用来否定用户最新架构调整。

> 本文落实 P0 设计 §10 的 P1；局部取代其“定义尚未迁移”的当前执行状态，P0 历史验收原样保留。当前状态：约束已落盘，尚未实现、尚未验收。用户已授权执行；所有实现只在 parallel-side-module 工作树，由无头 dsh 完成；不提交、不推送、不碰其他批次 stash。

## §1 目标、来源与范围

将 Industry / ProductionMode / ProductionRecipe 的定义部分及生产技能归属转入 production，生产命令面成为定义注册与修改的唯一权威；economy 只保留运行实例、劳动/分配、资产/财债和稳定 ID 引用。实际生产与预期利润都必须读同一份 production 定义。依据：用户设计原稿 D-031、P0 设计 §5/§10，及本会话用户认可的合理性测试方案。

本轮不启用文化倍率（P2）、科技升级/传播、共享人口流动表、经济新公式或新激活位。不兼容旧档、不做格式迁移。主树暂不接收 P1 在飞文件。P0 的未提交文件已完整保存于独立只读基线 worktree `.claude/worktrees/d031-p1-baseline`；最终测试阶段才运行基线。

## §2 回代码核过的现场（P0 基线）

- `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:312`：完整状态仍有 industries、modes 两张定义表，许多 withX 与构造器复制它们；构造期还跨查定义验证实例。
- `simos-economy/src/main/java/io/mosire/simos/economy/model/Industry.java:126`：定义参数与五个旧兼容位混装；真实生产进度已在 ProductionProcess，不能搬入 production。`:397` 的 recipe() 是无 RecipeId 的派生配方，当前无 RecipeId 权威表。
- `simos-economy/src/main/java/io/mosire/simos/economy/model/ProductionMode.java:21`：已有 id/name/version/classStructureId；不能伪造 Industry 和 Mode 的一一映射。
- `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyUpsertIndustryHandler.java:28`：旧业务语义明确“被引用定义不得原地替换；同值重放 no-op；新版本 ID 注册”。这是 P1 必须保留的行为。此前聊天的 7→11 测试建议细化为：无引用时修改，随后新建引用实例；已有引用的修改必须拒绝；新版本不篡改老实例。
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java:106` 与实际结算直接读取 economy 模型；源码检索确认播种、区域清空、组织、借贷、迁移、读视图也消费定义，必须覆盖完整消费者链。
- `simos-production/src/main/java/io/mosire/simos/production/ProductionData.java:44` 目前只保存 technologies/masteries。`contract/IndustryDefinitionView.java:46` 已保留技能、周期、全部投入/产出/劳动、槽位、分配、铸币字段。
- `simos-core/src/main/java/io/mosire/simos/core/command/CommandBus.java:414`：单条 handler 只更新命令 namespace；跨片依赖工作必须用已有 submitBatch 原子提交，不能让 production handler 偷写 economy 或反之。
- `simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java:641` 与备用路径仍只空注入文化倍率。本轮保留。

生成现场检索：`rg -n 'public record EconomyData|Map<IndustryId, Industry>|Map<ProductionModeId, ProductionMode>' simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java`；全仓消费者以 `rg -l '\.industries\(|\.modes\(|withIndustries\(|withModes\(' simos-*/src/main/java --glob '*.java'` 为起点，并补类型 import / 构造器，不把零命中当证明。

## §3 冻结的状态、契约与数据流

### 3.1 单一状态所有权

- production 的完整持久状态增加 `industries: Map<IndustryId, IndustryDefinitionView>` 与 `modes: Map<ProductionModeId, ProductionModeDefinitionView>`，保留现有科技两组件。技能以 IndustryDefinitionView.skill 为权威输入，不另存一张竞争技能表。
- economy 完整持久状态、codec、change 删除 industries/modes 组件、withIndustries/withModes 权威写入口、旧 Industry/ProductionMode/ProductionRecipe 定义模型及旧 EconomyProductionDefinitionLookup 适配器。AllocationRule/ClassSlot/MoneyOutput 如仅为定义的旧镜像，应合并到 production 契约；经济特有计算行为仍归 economy。所有派生参数/视图不得成为另一份可编辑状态。
- unit.operator/progressDays/cycleLaborMilli/cycleInputUsedMilli、OwnershipStake、劳动、账户、债务、收益累计继续归原域。位置相关 IndustryId 原样保留；不改 ID 为地址、不按字符串推断技能。
- 无独立配方目录的既有事实保持：配方作为产业定义的派生只读值由 production 提供，允许补 `recipeOf(IndustryId)` 或 `IndustryDefinitionView.recipe()`；禁止为填接口制造 RecipeId↔IndustryId 一一映射。不需要保留从未提供能力的 empty RecipeId 查口；若保留，必须在报告准确说明边界。

### 3.2 只读来源与执行上下文

- 扩展 production.contract 的只读查询，支持 industry/mode 精确查询及规范保序全表迭代（结算和候选需要枚举）；ProductionSnapshot 可实现 contract 中的只读快照接口，使 economy 不 import production 实现包即可从同一 SimulationState 解析当前定义。
- 经济消费者通过显式传入的只读 contract 来源使用定义；可以组合“EconomyData + 定义来源”为瞬态只读执行视图，由 economy 自己实现内部组合。方法/类拆分由实现者自主设计并记账。
- 持久 EconomyData 不得塞入 lookup、Supplier、快照、副本定义表、静态全局表或 ThreadLocal；禁止序列化两份定义或用全局绑定掩盖参数缺失。瞬态视图不得成为写入口，任何写动作必须归属各自 ChangeSet。
- 一次命令候选态/一次 advance 必须来源一致；后续命令看到本批前面修改的新定义，新的一次 advance 看到最新提交定义。不能复用过期快照。真实结算与 ExpectedProfitBook 均使用上述来源。
- 跨域引用验证由拥有实例的 economy 派生只读使用情况，通过 production.contract 端口/回调提供给 production（由 app 装配，或 economy 快照实现只读 contract）；production 禁依赖 economy 实现。app 只转接与组批，不实现公式或引用判断领域逻辑。

### 3.3 命令与载荷

- 将旧 economy.UpsertIndustry 的完整产业载荷和幂等/版本/被引用拒绝语义迁入 `production.UpsertIndustry`；旧 economy 定义写命令及旧载荷权威不保留。配套 GM 窄工具可保持外部工具名，但必须明确提交 production 命令，catalog提示、资源权限/目录按真实能力同步，保持 GM-only。
- 生产定义批量播种使用 production 命令，载荷包含 `industries:[完整产业定义]`、`modes:[完整模式定义]`（允许定义命令用批量入口复用单条领域校验）；具名校验，保序、同值 no-op；冲突/引用/版本拒绝不能静默覆盖。
- economy.Seed 只创建/追加经济实例与本域状态；生产定义通过 production 命令先入候选态。创世生成器从同一次确定性种子计划拆出 production/economy/actor 的初态与载荷，不临时把定义塞回 economy。四个新世界、worldgen/region seed、政府 office/mint 的创建全部覆盖。
- 区域清空只删区域实例后，再通过 production 清除本区域无人引用的定义，使用已有 submitBatch 原子提交；全局 mode 不能被当作某 Region 独占删掉。planOnly 返回一致的具体计划，绝不能写。
- 技术选型：引用中的 Industry 定义保留旧“同值 no-op、改值拒绝、新版本另 ID”的安全规则；mode 保留既有 `EconomyGmAdjustments.java:322` 的完整规则：新 id 默认version=1，修改已有id显式推进version，同值重放no-op，classStructureId必须存在且双向关联一致；不能自行添加“被引用mode一律禁止版本推进”的新规则。移除不存在定义须具名拒绝（区域清理内部无变动可 no-op）。删除任何被引用定义须拒绝。所有拒绝零状态变化、零 revision。
- 定义引用合法性必须在真实命令/推进边界检查；纯 codec 只验证本片固有约束，不依赖另一片才能解码。空定义+非空引用不能被解释成“未启用所以放过”。新世界/组批的临时构造阶段不能误判为对外可用世界。

## §4 不变量

I1 定义只在 production 持久化，唯一命令写者 production；I2 全部修改走 Command→ChangeSet→Revision；I3 economy/actor/social 的领域所有权不变；I4 非空全状态与 change JSON 往返逐字段重建；I5 字节保序确定，不用 Map.copyOf/Set.copyOf；I6 技能可缺但真实定义仍可见，单位/定义不一致具名故障，不降级中性；I7 实际与预测同一权威定义；I8 旧定价/生产/分配/市场/人口/财债公式和常量不变；I9 五个 P0 GM 命令权限不扩大、新定义命令 GM-only；I10 被引用定义替换/删除及批量失败原子性成立；I11 文化日循环继续空注入；I12 不兼容旧档、不动其他批次；I13 日志业务拒绝 INFO，契约故障 ERROR，细账 TRACE，唯一 XxxLog 门面；I14 不改 Core 领域逻辑、除§9.2用户要求的工具契约依赖外不放宽模块依赖。

## §5 验收判据（最后统一测试，计划数字不是实测结果）

V1 真实新世界及非空 production/economy 状态、两侧 ChangeSet 全组件 JSON 往返、差异 apply 重建，跨模块提交回放/重启后下一次结算一致。
V2 黄金对照：最终测试代理在冻结 P0 基线与 P1 用相同世界、种子和业务操作，比较产出/投入/劳动/分配/铸币/进度/账户/预期收益等业务投影。忽略仅由结构迁移导致的 namespace/JSON形状变化，不能忽略业务差异。长多tick模拟独立脚本运行；JUnit 仅短场景或构造跨周期边界。遇旧bug如实报告，不篡改黄金值迁就。
V3 反事实权威切换：正式 production 命令在无引用定义上把产出7改11，然后创建两个独立实例；原料/劳动/需求充足时，真实生产与预期利润读取新定义。再次修改已被引用定义拒绝；新版本定义不篡改旧实例。必须断言具体非零读数，不只断“不同”。
V4 独立手算：周期未完成、完成、无劳动/缺投入、分配守恒、不同分配规则、实物与铸币、多商品毫单位换算、日劳动与周期劳动量纲；选择小而可逐笔核算的数据，不从被测代码反算期望。
V5 负向：旧 economy 定义写命令/持久表消失；生产模块不得 import economy 实现；经济只能 import production.contract；缺定义、无效mode/阶层结构关联、重复/非法载荷、引用中删除替换、组批中途拒绝、region clear/seed边界、planOnly不写、技能跨格与缺技能可见性、文化非中性规则仍不生效。
V6 变异：消费者临时读过期定义或丢一个产出/铸币字段应导致业务断言红；绕过引用拒绝或双权威边界应当场红；改坏→真实失败→还原→MD5一致，编译错误不冒称业务失败。最后干净构建/有效报告计数，不把Skipped算通过。
V7 独立长模拟合理性：固定种子，输出每次结算的生产、库存、资金变化、约束拒绝与停产原因；对照P0业务投影并检查守恒/非负/无劳动或缺原料不凭空生产。长度由真实世界周期和成本决定，不能只以“无异常退出”验收。不要使用共享test-world/store，不塞入JUnit。
完整门禁仅在写者停手后，主树唯一完整门禁入口。本工作树先最终统一 test 与必要分析；集成主树前必须与主树当刻现场核对，保留他人改动。未经用户进一步要求不擅自提交。

## §6 文件责任与交付

一个跨模块定义迁移责任区 P1-IMPL = 一个无头 dsh 生产实现代理，拥有所有受上述数据流影响的 `simos-production/src/main`、`simos-economy/src/main`、`simos-app/src/main` 和必要其他 simos 主代码、相关POM/活机制文档（除§9.2用户明确要求的production→ga-spi工具契约依赖外不放宽依赖；涉及范围新增先报告）；先完整解决契约到消费者再以本树包装器 compile收口。禁止写/运行测试、禁止改测试/门禁清单、禁止改主树/ga-opt/P0基线、禁止提交与恢复stash。选择一个owner是为让本次跨模块签名迁移保持同一责任，不能另派多个写者抢同文件。

P1-TEST = 最后另一个无头 dsh 测试代理，拥有受影响测试文件、门禁清单、独立模拟/黄金导出脚本；可在P0基线中放隔离的临时导出器但不得改基线业务源码。生产错误交原实现owner修复；受控变异允许临时修改生产文件、必须还原。

控制方只做设计、调查综合、派单、验收与集成，不自己写生产代码。两代理分别在 `.superpowers/sdd/2026-10-16-d031-p1-definition-migration/{impl,test}/` 写 impl-ledger.md/task-report.md，包含实际改动文件/真实命令与退出码/行为变化清单/失效旧断言/偏离/未完成与未验证。没有满足判据不得标DONE。用户原话或五条铁律冲突立即上报；纯技术分歧控制方落盘决定；困难不能靠写风险文档代替完成。

## §7 用户原话附录（2026-10-16 本会话逐字）

> 从当前工作区找一下worktree，找到写文化等的，恢复工作状态，告诉我目前干到哪了

> 有关迁移后的合理性测试，你有想法吗

> 行，那么开始吧，依旧使用无头dsh派单执行，记住要放在worktree里

上述第三句授权执行此前回复中的 P1 迁移及合理性验收建议；关于引用后能否改定义的聊天示例以 §2/§3 明确的既有规则细化，不扩大为P2或改动旧安全规则。

## §8 历史相关用户原话附录（从 P0 §1 原样转录；原日期/转引性质保留）

（逐字，不改写、不缩写、不意译）

> **收录口径（`docs/agents/02-一-并发.md` §一.8.1）**：凡用于派实现子 Agent 的开发计划 / 架构设计文档，必须有独立小节逐条原样照录该主题下用户的**全部相关原话**（含日期）。本节分三层：
> **① 用户逐字原话**（不可改写）· **② 设计原稿的"已明确/记录边界"**（调查者或控制方整理，**非用户逐字**）· **③ 控制方复核修正**（**非用户原话**，标"控制方"）。
> ★ 控制方复核 §1 明注：`§1.1` 的"只需要把接口立好"范围原话**来源为侧线笔记转引**，不伪装为本主会话直接收到的逐字原话；本节按原样保留并标来源。

### 1.1 本轮范围（侧线笔记转引；控制方复核 §1 注其来源性质）

> 现在开始规划文化/生产方式/经济模块的进一步调整，现阶段不需要真的做出来复杂的文化效果，只需要把接口立好就行，根据这个目标，规划怎么写代码、出裁定

（来源：侧线笔记 `.agents/notes/proposed/architecture/2026-10-15-d031-interface-first-scope-trace.md:5` 转引；控制方复核 §1 已注：**未当成本主会话直接收到的逐字原话**。）

### 1.2 D-031 首轮（用户原话逐字，`docs/superpowers/specs/2026-10-02-architecture-design-source.md:497-507`；**完整照录，非摘录**）

> 你自己先了解一下当前目录下的项目，然后我来说一下我希望达成的设计：
> 现有的家户、经济模块管理很混乱，我原本设想的家户，作用是表达一个拥有特定文化、特定习俗的社会阶层，但是目前貌似只实现了经济面上的基层；
> 另一个问题是家户流动，这一点还需要你自己看代码和之前的日志，我不多赘述；
> 重点是我希望实现什么，我希望对于每一个家户对象，在单tick运行的时候，都算一张阶层流动表，以代替之前落后且压根不知道在干什么的单一阶层流动机制；
> 这张阶层流动表首先根据这个家户的所在位置、经济状态初始化，在初始化的时候，其中有这个家户抛掉其他所有人文经济因素，所有可能的人口流动方向——因此需要列入本hex和周围六个hex，不管经济状态、生产方式还是其他目前压根没定义的人文属性等的，所有其他可流动家户；
> 初始化出来这张表之后，最后一步自然是给经济算实际流动速度，当这个家户的负债规模变大的时候（这个条件我甚至不知道靠不靠谱），这个家户其中的成员人口自然要往更低阶层的家户流动；没有反之，同时成立，如果这个家户的库存价值变大，那么会往经济状态更好的家户产生人口流动；然后是生产方式判断，如果这个生产方式相比相邻其他家户选择的生产方式不够赚钱，那么这个家户会产生流向同等经济水平其他家户的速度；以上三者速度计算是顺序进行的，算完这些根据经济决定的实际速度后，根据这张人员流动表，就可以算对其他家户的人员流动了；
> 但是很显然，中间缺了很多环节；
> 这里很显然可以引入一个之前一直在文档中若隐若现但是一直没有实装的模块了，也就是家户的文化模块，我希望命名为“文化与基层治理”——你知道的，这个项目目前服务于中世纪环境，因此宗教、文化等非经济社会存在都要交给这个模块；
> 具体实现，我认为其实就是给家户打上一个文化标签，这个家户是什么地方认可什么宗教/文化的什么人，就是这个意思——这里这个模块首先和经济相互作用的地方很简单，那就是这个家户的生产熟练度，你知道吗？生产技能和适应是和文化绑在一起的，我管你信不信，总之就是要维护一张给特定生产方式加加成的表；
> 是的，这里额外要引入一个单独管理生产方式和科技的模块，最终共同作用于大经济系统，作用自然就是格式化地定义生产方式，然后在恰当的时候让已有的生产方式发生升级，模拟科技进步；
> 总之，我认为这一轮必须要把生产与科技模块、文化与基层模块，和对应的经济模块接口等规范出来，因此你应该先开始进行调查，告诉我目前代码里是什么情况，方便进行进一步规划/裁决/设计

### 1.3 D-031 补充裁定一（用户原话逐字，同文件 `:513`）

> 2 首先，我认为阶层滑落和家户间转业应该和市场同步在同一个周期做，不如说市场结算周期等周期本身就没有很好地管理； 3 所以要列表 4 我还是认为应该把生产方式（其实就是产业，生产方式决定生产产出嘛）包给一个统一的前置模块，方便计算、修改 5 如前者 ——所以单看这个设计，有什么需要额外裁决和调查的吗

### 1.4 D-031 补充裁定二（用户原话逐字，同文件 `:526`）

> 2 不是经济模块自己有个利润率水平吗？根据经济因素决定的家户流动，让经济模块自己算不就得了？遇到文化同化，也让文化模块自己在那个表格上算不得了，目前项目不是有这个工单系统吗？这不就派上用场了

### 1.5 D-031 设计原稿的"已明确 / 责任分工"（**调查者或控制方整理，非用户逐字原话**）

> 以下条目摘自设计原稿 `:491-492`、`:515-520`、`:528-534`；它们是对用户原话的整理与口径收束，**不是用户逐字原话**，此处标记以便分清责任。

- **已明确的方向**（`:491`）：逐家户、逐 tick 建人口流动表；候选方向先覆盖本 hex 与相邻六 hex 的其他家户，再计算经济等因素对速度的影响；引入"文化与基层治理"和独立的生产方式/科技管理模块，文化维护特定生产方式的技能加成。
- **记录边界**（`:492`）：原话中的试探条件、歧义与缺环节仍保留为待设计；不替用户把"负债规模变大"定成债资比、不把"库存价值变大"定成某个人均指标、不替用户选择技术升级是否更换家户。
- **本次已明确**（`:517-520`）：阶层滑落与家户间转业跟市场同步、同周期执行；建流动表要求不变；生产方式按产业/产出定义归统一前置模块管理；固定节拍与追加市场轮、经济水平指标、速度合成、文化流转、技术升级作用范围尚未裁定。
- **本次明确的责任分工**（`:530-534`）：经济因素造成的家户流动由经济模块算；文化同化及文化影响由文化模块在同一张流动表上算；复用工单表达家户变更、人口由 Social 校验执行；app/Core 只组合调度。

### 1.6 D-022 / D-023 / D-024 相关用户原话（逐字；完整裁定见原稿对应条目）

**D-022（2026-10-06，`docs/superpowers/specs/2026-10-02-architecture-design-source.md:353-354`）**

> 7改改，是增加这个家户转移为其他家户的速度，我从来没说过改变当前这个家户的生产方式，如果之前搞了，之后也不应该有，转变生产方式应该直接用创建/转移到新家户表示。

（相关裁定要点：任何家户不得原地改 `ProductionMode`；只用"转移到其他/新建家户"表示；源户人口归零后处理见原稿 `:355-366`。**完整逐字裁定以原稿为准**。）

**D-023（2026-10-06，同文件 `:372-375`）**

> 不行啊，外部放贷主体/政府也能支持任意货款支付，有啥付啥，我规定用什么还了吗？分别核算也不用，单个家户有价目表吗？如果有对应，那么按单家户的价目表优先；如果没有，那么就按照市场区默认价目表核算； 3、5、6、7、8、9先做，4不用急，10可以通过流民选择加入其他生产方式来模拟——流民为什么会有工作？；全部币种余额可以做，但是兑换很显然是一种市场行为，后面可以结合国家系统做，因此现在不用；修复完这些之后再跑一轮根据3来的3650tick

（相关裁定要点：偿还介质不设限、估值顺序、资产随迁、流民没有工作。**完整逐字裁定以原稿 `:376-394` 为准**。）

**D-024（2026-10-06，同文件 `:399`）**

> 单个市场内，单个家户对于单个生产方式没有预期利润吗？能不能算？以及什么叫没有需求，给单个家户、生产方式算一下相关需求啊；这个拍板到底是 什么意思

（相关裁定要点：预期利润现算（配方 + 市场价 + 可寻址需求）、需求必须算、`OrganizationProfitBook` 退位、时间尺度对齐。**完整逐字裁定以原稿 `:400-407` 为准**。）

### 1.7 控制方复核修正（**非用户原话**；来源 `docs/superpowers/reports/2026-10-15-d031-side-line-review-decisions.md` §2/§6）

> 以下为控制方复核决定：**不是用户原话**，不得据此伪称用户裁定；用户随时可推翻。
> 本轮六条修正（控制方转达，2026-10-15）：

1. **生产定义视图补齐身份和既有字段。** 明确 Industry、Mode、Recipe 各自的稳定 ID、关联和已有版本；补上遗漏的 `dailyLaborPerUnit`、`moneyOutputPerUnit`，不能丢掉铸币产出。运行实例字段继续排除。
2. **公开契约不能引用经济实现类。** `ClassSlot`、`AllocationRule`、`MoneyOutput` 目前住在经济实现模块，production 视图若直接引用，会造成依赖环。使用允许依赖内的契约值类型，由经济适配器转换。
3. **文化标签与加成规则必须关联得上。** 家户文化标签补明确的 `CultureId` 关联；加成的"生产键"使用明确的稳定类型。不要直接拿带 hex 含义的产业实例 ID 充当跨地区生产技能键。
4. **补加成规则的编辑命令。** 既然规则可编辑，除 `SetHouseholdCulture` 外，还须有熟练度规则的增改、删除语义，全部走命令链。只靠硬编码填表不算可编辑。
5. **写清家户加成如何转换成生产单元修正。** 现有接收口按 `ProductionUnitId` 收值，每个 unit 只能有一个结果。spec 要说明家户劳动如何关联到 unit、由哪个领域服务计算合成；不能默认只看经营者文化，也不能让 app 实现文化公式。本轮仍可保持中性、不启用实际效果。
6. **修准边界和实施纪律。** "禁止依赖 core/app"限定新增两个领域模块；已有组合根遵循原允许面。production 契约先编译，再由其他责任区消费。Java 测试清单、测试代码和变异统一交 Z6 测试代理，控制方负责调度验收；计划中的验证命令不能变成逐小任务跑测试。

（复核决定 §6 的七条技术修正与本 6 条同源，本文两处都已纳入。复核 §2 的 M2/M4/R1/R2/M12/M13/M5 与 §3 机械纠正同为本条控制方口径。）

---


## §9 控制方补充回核（实现启动早期，2026-10-16）

- mode 的旧更新语义以 `EconomyGmAdjustments.java:322` 为准：允许显式推进version，引用校验仍成立；旧删除守卫`:396`点名六类真实引用，不凭空加候选modeId字段。§3.3已同步纠正过严的技术初稿，不改变用户业务设计。
- `outputQuantityOverrides` 是既有 GM 运行期覆盖，而非待搬迁的 Industry 基础定义；本P1允许它继续归 economy、保持原语义，以免迁移扩大成经济规则修订。唯一基础定义仍归 production，覆盖必须显式分层，禁止拿覆盖表当新定义表。回核 `EconomySettlement.java:1793/8661` 真实结算消费此表，`ExpectedProfitBook` 不直接消费它；黄金与新定义因果测试必须显式给定覆盖条件并区分预测/实际，不能掩盖已有差异，也不把P1等同P2修正语义统一。若测试发现此既有差异妨碍验收，单独报告控制方，禁止篡改黄金或静默改公式。

- dsh 只读调查已完成，报告位于本topic/recon/report.md（由根recon/report.md移动到正确台账目录，内容未改）。采用契约视图作为持久权威类型时，必须继承旧模型全部强不变量：Split权重和恰1000；capacityPerUnit非空、逐值严格正；slots非空、id唯一；MoneyOutput逐值严格正、空表代表无铸币。AllocationRule多态注解及所有派生isEmpty属性的裸mapper/Timeline往返须保留真实类型级支持，不能只依赖codec私有mixin。相同规则以旧真实构造器为准，不从类注片面推导。

### 9.1 按需产业创建（控制方回代码核，2026-10-16）

`ModeMigrationSettlement.java:208` 在转业时创建 trade 产业；`EconomyEntrySettlement.java:868` 在进入生产时按候选模板创建产业。P1必须保留这两项原有能力与数值，不能禁用、预先把所有模板播满、吞掉新增记录或退回economy定义持久表。economy可以产生瞬态请求/使用production契约工厂的只读计划值，production负责定义创建/注册的领域投影；app只转接，最终由同一次Advance派生production与economy的ChangeSet原子落账。瞬态待登记定义仅是本次未提交候选态，不得作为第二份持久权威或可绕过命令的写口。必须有新增定义+实例→回放/重启→下一次结算的测试，验证不是只活在overlay。

若既有单片命令/多片组批的双向引用初始化在现有执行缝确实无法保持完整性，允许对 util/Core 做必要的**通用校验调度**补齐（复用既有MutationGuard/codec机制，不新增领域知识/第四种Command/额外激活位）；此项需要先报告具体缺口与最小SPI形状，由控制方落盘决定再实施，不要求实现方带着假一致性强行过编译。领域引用判断仍归economy/production，Core只组合与调度。

### 9.2 用户纠正：production 自己维护工具（2026-10-16）

用户本会话原话（逐字）：

> 为啥要移到app？照道理来说不应该是production包自己维护吗？

本条为最新用户指示，局部取代任何把production编辑窄工具搬到app的实现建议。生产定义的GM窄工具、schema/description/preview/提交载荷及本域校验由 `simos-production` 自己维护（production.tools等本模块包）；app只实例化/注册工具和装配端口，不持有该领域工具实现。生产定义投影/命令/codec/change仍归production。

当前production未声明 `simos-ga-spi`，其ban还含ga-spi；economy已有该工具契约依赖。这是原先错误绕到app的原因，不是用户要求工具归app。按本条给production增加ga-spi依赖、从ban移除，更新根AGENTS模块允许面与长文、POM说明及包边界守卫，依赖不扩到ga实现/core/app/economy实现。ga-spi只依赖util和agentlib，不回指production，无环。AgentLib工具类型可使用ga-spi传递可见依赖，不为绕守卫添加不必要直接依赖。

现已暂放 `simos-app/.../tools/write/EconomyUpsertIndustryTool.java` 的工具实现迁入production；不要在app留下转发子类/第二份schema或payload处理；只保留注册引用。读取当前生产快照/引用使用的辅助通过production.contract端口，禁止import economy实现。活注释、载荷提示、资源命名空间必须随真实production语义同步。

### 9.3 控制方技术决定：候选态落库前跨片校验（2026-10-16）

依据实现方 `impl/decision-request.md`：现有submitBatch按条执行，写前MutationGuard不足以验证最终两片状态，mode/阶层结构双向引用在分片后存在漏校验。**不接受将其记作非阻塞遗留**；这是§3.3/I10/V5的必要能力，不能交给测试owner补生产代码。批准当前P1-IMPL同一owner补齐util/Core通用调度，仍不引入任何领域依赖。

外部契约冻结：util.spi增加 `CandidateStateValidator`（或语义完全等价地扩展既有MutationGuard，无第二套注册框架），提供 `String name()` 与 `Optional<String> rejection(SimulationState base, SimulationState candidate)`，只读纯函数。CoreSimos可注册，Core在**单条命令、submitBatch最终候选态、restore及Advance最终候选态落库之前**按注册序调用。返回具名拒绝则零revision/零切片变化；先判断再append，禁止“落库后validate(committed)”的顺序。装配/不一致故障ERROR不降级；预期非法操作仍具名拒绝INFO。没有注册validator的既有Core消费者行为不变；不新增领域状态/第四种Command/用户开关。

校验实现归economy（只经production.contract读生产快照），由app注册装配。须覆盖原EconomyData删除的跨域守卫：units/assetShares引用industry存在、进度不超过cycleDays、mode↔classStructure/position及其余mode引用、输出覆盖对应真实产业/产出商品等；具体完整集合从原P0构造器提取，不能只补mode一个口。production自身定义的固有约束继续构造器/命令内验证；引用使用端口继续负责删除/被引用改值的拒绝。

批内每条只校验本域形状和不需等待后续命令的约束；互相声明的生产mode/经济结构可在同批先后创建，最终整批校验闭环。必要时把会错误阻挡合法批次的跨片前置存在性校验移到此处，单条命令仍经最终校验拒绝无效引用。真实preview与提交需要同源语义，不能preview通过而同一完整候选态commit被领域规则拒绝。

同时必须完成impl/control.md中交账前清单：production内维护按需定义注册投影（两日循环不在app抄合并规则）、四新世界/seed路径、全部消费者与真实compile日志。完成后再交P1-TEST，不能以compile通过替代已明确的能力约束。

## §10 未做 / 未验证（设计落盘时）

尚未实现P1、未运行本轮编译/测试/模拟；既有3422等数字仅为P0报告。只读调查由 dsh recon 另行交账；约束中的验收均是计划，不能当成已经实现或跑过。
