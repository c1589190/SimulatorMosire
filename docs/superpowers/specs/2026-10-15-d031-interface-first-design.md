# D-031 接口先行：文化与基层治理 / 生产与科技 / 经济挂点（约束设计书）

> **局部取代者：`docs/superpowers/specs/2026-10-17-d032-production-experience-choice-design.md`（2026-10-17，用户原稿 D-032）。** 文化直接提供生产效率倍率及相关后续 P2 方向已被用户取代；经验、公式、解锁与主副业采用新设计。本文的历史实现/验收与未被取代的领域边界保留；勿据旧文化倍率方向继续派单。

> **当前状态（2026-10-16）**：P0 已实现且主树 `tools/gate.sh full` 通过（3422/0/0/7，前端412/412）。交接见 `docs/superpowers/status/2026-10-16-d031-interface-first-result.md`；定义迁移/真实效果/旧双流动替换仍未完成。以下早期基线、暂停与待实现状态保留作历史，不作为当前进度。

> **Z4 文件范围补全（2026-10-16）**：真实日循环及新世界空快照的登记位置回核后见 `docs/superpowers/reports/2026-10-16-d031-worktree-coordination.md` §6，Z4 除 Shell/bridge 还拥有两个经济推进参与者的最小调用点及四个新世界构造器的初始化登记；其他行为不扩张。

> **实现调查补全（2026-10-16）**：§6.4 的默认技能覆盖方式、科技写入幂等及作用域 kind 口径以 `docs/superpowers/reports/2026-10-16-d031-worktree-coordination.md` §6 为补充：精确目录由 app 从既有坐标/kind 构造映射，不解析 ID、不另存权威表。

> **恢复执行（2026-10-16）**：用户原话「现在你可以继续你手头上的工作了」。暂停解除，继续按每树独立构建规则与 Z5→Z2→Z3→Z1→Z4→Z6 顺序执行；以下暂停记载保留作历史。

> **同日再次更新（2026-10-16，当前执行口径）**：侧线已同步 `03faf0a3`。并发纪律 §一.14 及 `docs/superpowers/reports/2026-10-16-d031-worktree-coordination.md` §5 取代下条更新和 §13 中的共享锁/主树 `-f` 执行方式：现在使用侧线自己的 `tools/mvn-lock.sh`、`.m2-repo` 和树内锁，跨树可并行；主树唯一入账口与暂停状态不变。

> **执行规则更新（2026-10-16）**：§13 的旧锁说明已由 `docs/superpowers/reports/2026-10-16-d031-worktree-coordination.md` §2 局部取代：所有工作树使用共享 `.git` 下的项目锁，主树为唯一提交/完整门禁/推送入口。侧线已对齐 `02b2f96e` 并保留 Z5 改动；开发派单仍暂停。以下原始状态与基线保留为成文时记录。

> 状态：**待实现**（spec 已定稿待评审；未写任何生产代码）。
> 作者：侧线代理（分支 `parallel/side-module`，工作树 `.claude/worktrees/parallel-side-module`；基线 `23a7c070`）。
> 复核依据：`docs/superpowers/reports/2026-10-15-d031-side-line-review-decisions.md`（§2 决定、§3 机械纠正、§6 分节设计复核七条）。
> ★ **本轮修订（2026-10-15，控制方 A–G 复核）**：A 真实效果禁用＝明确执行边界（§7/§8/§11）；B 技能可缺、不挡定义可见性（§6.1/§6.4）；C 技能 lookup 进契约、具体目录只由 production/app 装配（§4/§6.4）；D 生产代理编译口径 `-DskipTests compile` + POM 机械面先行（§13）；E culture 单一责任区 + 编译顺序 Z2→Z3→Z1→Z4→Z6（§13）；F 完整用户原话附录（§1）；G 不加复杂效果/规则引擎/迁移实现/新激活开关，P1/P2 边界写清（§10）。
> ★ **三处"未完成"标记（全文通用，不许在任何阶段省略）**：① 生产**定义尚未迁移**（仍在 `simos-economy`）；② **真实文化效果未启用**（本轮只有中性默认路径）；③ **旧双流动机制未被替换**（`ClassSlideSettlement` / `ModeMigrationPolicy` 原样在跑）。
> ★ 本 spec 不修改任何用户原话；用户设计原稿 `D-022/D-023/D-024/D-031` 与两次补充裁定是最高权威（见 §1 逐字附录）。

---

## §1 用户原话附录（逐字，不改写、不缩写、不意译）

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

## §2 范围与非目标

**本轮（P0）交付**：两个模块 `simos-culture` / `simos-production` 落地；生产对外定义**契约包**与只读口；文化身份 + 加成规则**状态与命令**；家户加成→unit 修正的**合成服务**（可测试纯服务）；组合根接线点；机械面（POM/enforcer/边界表/测试清单）同步；**迁移计划**与三处未完成标记落盘。

**非目标（本轮明确不做；修正 G）**：宗教/信用（riba）等复杂文化效果（需求保留登记、不宣布作废）· 生产定义的**实际搬家**（P1）· 技术传播/升级行为 · 共享流动表的**实现** · 旧双流动机制的替换 · GUI/MCP 新工具面 · **通用规则引擎 / 复杂文化效果 / 真实迁移实现 / 任何新激活位或开关**（A/G）。

---

## §3 现状事实（回代码核过；"转抄"已标注）

| 事实 | 证据 |
|---|---|
| 世界状态 = `SimulationState(meta, Map<String,Snapshot> modules, info)`，按 `namespace()` 注册；**不提供跨模块访问器** | `simos-util/src/main/java/io/mosire/simos/util/state/SimulationState.java:14` |
| 每个领域模块的四件：`Snapshot`、`ModuleCodec`、命令面、**`TimeParticipant`（按需，不强制）** | `simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java:167-199`（codec 与 participant 各自注册、不要求一一对应） |
| 跨模块只读可见性＝provider api 模块里的 lookup 接口 | `simos-social-api/.../lookup/HouseholdLookup.java:16`；`PopulationLookup.java:13` |
| `IndustryId` = **一整个产业**的稳定身份（不是逐 hex 实例） | `simos-economy-api/.../api/id/IndustryId.java:4-8` |
| `ProductionMode` 有真实 `version` 字段 | `simos-economy/src/main/java/io/mosire/simos/economy/model/ProductionMode.java:21-22` |
| `Industry` 混装定义与运行实例：定义 `id/name/regime/cycleDays/capacityPerUnit/dailyInputPerUnit/dailyLaborPerUnit/laborPerUnit/outputPerUnit/cycleInputPerUnit/slots/allocation/moneyOutputPerUnit`；实例 `operator/progressDays/capacity/cycleLaborMilli/cycleInputUsedMilli` | 同模块 `model/Industry.java:126-144` |
| `ProductionRecipe(capacityPerUnit, inputPerUnit, laborPerUnit, outputPerUnit, moneyOutputPerUnit)` | `model/ProductionRecipe.java:54-59` |
| ★ `ClassSlot`、`AllocationRule`、`MoneyOutput` **住在经济实现模块**（`io.mosire.simos.economy.model`） | `model/ClassSlot.java:26`、`model/AllocationRule.java:32,42,72`、`model/MoneyOutput.java`（`perUnit: Map<CurrencyId,Long>`） |
| ★ `HouseholdLaborCommitment` **在 economy-api**，`activity` = 生产单元 id | `simos-economy-api/.../api/labor/HouseholdLaborCommitment.java:42,51-58` |
| 修正接收口按 unit 收单个值、重复拒、未注入=1000‰ 中性、必须纯函数、GM 不可达 | `docs/superpowers/specs/2026-10-23-production-efficiency-framework.md` §2/§5（★ 该文件名 10-23、实际 `db4f899d` 10-07 提交，**早于 D-031**，仅作接口基座） |
| 该接收口**目前无任何业务生产者**（只有测试在调） | 侧线实测 `grep updateProductionModifiers` |
| ★ `ExpectedProfitBook` **尚无修正消费**（实际生产若吃加成、预期仍按中性配方 ⇒ 口径脱节） | 转抄 `docs/superpowers/reports/2026-10-15-d031-side-line-review-decisions.md` §2/M12（**本设计代理未回代码核**） |
| 工单步骤封闭集：建户/位置/成员增减转移/人口/生死率，**无文化阶段** | `simos-social/src/main/java/io/mosire/simos/social/workorder/HouseholdWorkOrderPlan.java:76-83` |
| 家户成员＝份额 `Map<PeopleLotId,Long>` | `simos-social/.../household/Household.java:56-60` |
| 边界由各模块自带 `bannedDependencies` 强制；禁列 = 全仓 − 自己 − 允许面，零例外；`<searchTransitive>false</searchTransitive>` | `docs/agents/01-〇-铁律与模块边界.md:74-84`；样板 `simos-ga-spi/pom.xml:51-84` |
| 门禁 `GUARDS_TABLE` 需模块级往返守卫 | `tools/gate.sh:94-99` |
| 根 pom 现有 **16** 个 `<module>`（本批加 2 ⇒ 18） | `pom.xml:17-74`（`grep -n "<module>" pom.xml`） |

---

## §4 模块与依赖（含无环证明）

```text
simos-culture     artifact simos-culture    包 io.mosire.simos.culture    namespace "culture"
simos-production  artifact simos-production 包 io.mosire.simos.production namespace "production"
```

**允许面（本轮）**

| 模块 | 允许依赖 | 说明 |
|---|---|---|
| `simos-production` | `simos-util`, `simos-map`, `simos-economy-api` | 复用现有底层 ID（`IndustryId`/`ProductionModeId`/`RecipeId`/`AssetKind`/`CommodityId`/`CurrencyId`/`SocialClassId`/`ClassStructureId`/`ProductionUnitId`） |
| `simos-culture` | `simos-util`, `simos-map`, `simos-social-api`（+ `simos-social` 实现，按需读家户）, `simos-economy-api`, `simos-production`（**只读其 contract 包**） | 家户身份来自 social-api；生产键与定义视图来自 production 契约；修正类型来自 economy-api |
| `simos-economy` | 现有允许面 **+ `simos-production`**（**只读 contract 包**） | 由复核决定放行；**不得**依赖 `simos-culture`；**不得** import `io.mosire.simos.production` 的非 `contract` 包（修正 C） |
| `simos-app` | 现有允许面 + 两个新模块 | 组合根，按原允许面；装配 production 具体目录并**注入** economy 适配器 |

**无环证明**：`economy → production`（只读 contract）`→ economy-api → {actor-api, util, map, social-api}`，无回边到 `economy`；`culture → production → economy-api`、`culture → social → economy-api` 均无回边到 `culture`；**`economy-api` 不得反向引用 production 新定义类型**（否则 `production → economy-api → production` 成环）。若实现期发现实环 ⇒ 停下，提"最小契约抽取方案"上报，不得擅自加包。

**禁止项（适用面精确）**：**新增的 `simos-culture` / `simos-production` 不得依赖 `simos-core` 与 `simos-app`**；**任何**模块不得依赖 `simos-app`；`simos-production` 不得依赖 `simos-economy`（实现）。已有组合根对 `core` 的依赖按既有允许面，不受本条影响。

★ **修正 C 的装配纪律**：跨模块只读的技能查询 = production **契约包内的接口** `ProductionSkillLookup`；**具体实现类 `ProductionSkillCatalog` 只住 production 内部（非 contract 包），只由 production/app 装配**。economy 适配器**构造注入**该契约接口，**不得** import 具体目录类，**不得**解析 `IndustryId` 文本或新增"键归一"来猜产业种类。

---

## §5 定义对象 · 状态 · 唯一写者 · 读口 · 实例引用（实际流向，不只画模块名）

| 对象 | 本轮住哪 | 唯一写者 | 读取接口（谁提供/谁实现） | 实例引用流向 |
|---|---|---|---|---|
| 生产**定义**（Industry/Mode/Recipe 的定义部分） | `simos-economy`（**标"尚未迁移"**） | economy | `ProductionDefinitionLookup`（**接口在 production contract 包**，本轮**实现=economy 适配器**） | economy 运行实例（`ProductionProcess` 等）→ 按 id 引用定义 |
| 生产**技能键**（跨地区稳定；**可缺**） | production contract 包（`ProductionSkillKey` 值类型 + `ProductionSkillLookup` 接口）；具体目录 `ProductionSkillCatalog` 在 production 内部 | production（命令面，P1 后） | `ProductionSkillLookup`（接口在 contract，**注入**）；同时经 `IndustryDefinitionView.skill`（`Optional`）暴露 | 文化加成规则按它索引；**缺技能不让产业不可见** |
| 技术/工艺定义 + 掌握状态 | `simos-production`（本轮空表 + 契约） | production 命令面 | `TechnologyLookup` | （将来）生产实例按 `TechnologyId` 引用 |
| 家户文化标签 | `simos-culture` | culture 命令面 | `CultureLookup` | 按 `HouseholdId` 挂接（`POLITICAL_ECONOMY_DESIGN.md:387` 已定 `HouseholdId` 是文化扩展点） |
| 生产熟练度**规则**（可编辑） | `simos-culture` | culture 命令面（增/改/删） | `CultureLookup.rulesFor(CultureId)` | 派生倍率**即时算**，不另存权威表 |
| 人口 | `simos-social`（不变） | Social | `HouseholdLookup`/`PopulationLookup` | culture/economy **不写**人口；跨域变更走 Social 工单 |
| 财货·债·账户 | `simos-actor`/`simos-economy`（不变） | 各自 | 既有读口 | 迁移时随人走（D-023） |
| 家户劳动 → unit 关联 | `simos-economy`（现状） | economy | **新增** `ProductionLaborLookup`（接口在 economy-api，实现=economy） | 供 culture 合成用；**culture 只读** |

★ 契约包所在 artifact ≠ 状态所有者：`ProductionDefinitionLookup` 的**接口**归未来的权威方（production），**实现**本轮仍是 economy —— 这正是"定义尚未迁移"的机读形态。
★ **修正 B**：`IndustryDefinitionView` 的存在性只取决于真实产业定义是否可查，**不取决于文化扩展/技能键是否命中**；缺技能只令 `skill = Optional.empty()`。

---

## §6 契约形状（本轮要立的接口）

### 6.1 `simos-production` 公开契约包 `io.mosire.simos.production.contract`

**值类型（不得引用经济实现类型；字段全部取自 util/map/economy-api）**

```java
public record ProductionSkillKey(String value) { /* 非空白守卫；toString=裸值；static parse —— 铁律 1 三件套 */ }

public record ProductionSlotView(SocialClassId id, String name, int laborParticipationPerMille) {}
public sealed interface AllocationRuleView permits SplitView, WageFirstView {
  record SplitView(int meansWeightPerMille, int laborWeightPerMille) implements AllocationRuleView {}
  record WageFirstView(long wagePerLaborMilli, Map<CommodityId, Long> ownerResidual) implements AllocationRuleView {}
}
public record MoneyOutputView(Map<CurrencyId, Long> perUnit) {}   // 空表 = 不铸币
```
★ 保序不可变纪律照 `MoneyOutput` 类注：`LinkedHashMap` + `Collections.unmodifiableMap`，**绝不用 `Map.copyOf`**（迭代序不是内容的纯函数 ⇒ 快照字节会抖）。

**定义视图（三个关联视图，各自稳定 ID；不伪造一一映射、不含运行实例字段）**

```java
public record IndustryDefinitionView(
    IndustryId id, String name, RegimeId regime,
    Optional<ProductionSkillKey> skill,            // ★ 修正 B：可缺；缺技能不使产业不可见
    long cycleDays,
    Map<AssetKind, Long> capacityPerUnit,
    Map<AssetKind, Map<CommodityId, Long>> dailyInputPerUnit,
    long dailyLaborPerUnit,                       // ★ 修正①（控制方）：补回
    long laborPerUnit,
    Map<CommodityId, Long> outputPerUnit,
    Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
    List<ProductionSlotView> slots,
    AllocationRuleView allocation,
    MoneyOutputView moneyOutputPerUnit) {}         // ★ 修正①（控制方）：补回，不丢铸币

public record ProductionModeDefinitionView(ProductionModeId id, String name, int version, ClassStructureId classStructureId) {}
public record RecipeDefinitionView(RecipeId id, Map<AssetKind,Long> capacityPerUnit, Map<CommodityId,Long> inputPerUnit,
                                   long laborPerUnit, Map<CommodityId,Long> outputPerUnit, MoneyOutputView moneyOutputPerUnit) {}
```
**明确排除的运行实例字段**（不得进任何视图）：`operator`、`progressDays`、`capacity`、`cycleLaborMilli`、`cycleInputUsedMilli`。

**读口与科技形状**

```java
public interface ProductionDefinitionLookup {          // 本轮实现 = economy 适配器（标"尚未迁移"）
  Optional<IndustryDefinitionView> industry(IndustryId id);   // ★ 修正 B：真实定义存在即返回，skill 可 empty
  Optional<ProductionModeDefinitionView> mode(ProductionModeId id);
  Optional<RecipeDefinitionView> recipe(RecipeId id);
}
public interface ProductionSkillLookup {                 // ★ 修正 C：只读查询放 contract
  Optional<ProductionSkillKey> keyOf(IndustryId id);     // 未命中 ⇒ empty，不猜、不解析 id 文本
}
public record TechnologyId(String value) {}
public record TechnologyDefinition(TechnologyId id, String name, Set<TechnologyId> requires, long cycleDays) {}
public record MasteryScope(String kind, String id) {}   // ★ 修正 M16/R4：粒度作载荷选择（全局/每格/每户）
public record TechnologyMastery(MasteryScope scope, TechnologyId technology, int level) {}
public interface TechnologyLookup { Optional<TechnologyDefinition> technology(TechnologyId id); List<TechnologyMastery> masteries(MasteryScope scope); }
```
★ 本轮只定义 typed 契约，**不因接口先行自动生成传播/升级行为**。
★ `ProductionSkillLookup` 的**具体实现** `ProductionSkillCatalog` 住 production 内部（非 `contract` 包），只由 production/app 装配并**注入** economy 适配器（修正 C）。

**命令面（production）**：`production.RegisterTechnology`（登记定义，幂等键=TechnologyId）、`production.SetMastery`（写掌握状态）。落 `Snapshot`+`Codec`+`ChangeSet`，往返守卫进 `GUARDS_TABLE`。

### 6.2 `simos-culture` 公开契约包 `io.mosire.simos.culture.contract`

```java
public record CultureId(String value) {}
public record ReligionId(String value) {}
public record CustomTag(String value) {}
public record CultureTag(CultureId culture, ReligionId religion, Set<CustomTag> customs) {}   // ★ 修正③（控制方）：显式 CultureId
public record HouseholdCulture(HouseholdId household, CultureTag tag) {}

/** 生产熟练度规则：文化 × 跨地区生产技能键 → 千分修正（1000 = 中性，值域 [0,2000] 同既有接收口） */
public record ProductionAptitudeRule(CultureId culture, ProductionSkillKey skill,
                                     long modifierPerMille, String source, String reason) {}

public interface CultureLookup {
  Optional<HouseholdCulture> cultureOf(HouseholdId household);
  List<ProductionAptitudeRule> rulesFor(CultureId culture);
}
```
★ 生产键 = `ProductionSkillKey`（production contract 的稳定类型），**不得**拿 `IndustryId` 当跨地区技能键。

**命令面（culture，修正④（控制方）：可编辑必须有写口）**
- `culture.SetHouseholdCulture`（设置/覆盖家户文化标签，幂等键 = householdId）
- `culture.UpsertAptitudeRule`（新增或按 `(culture, skill)` 改，幂等键 = 该二元组）
- `culture.RemoveAptitudeRule`（按 `(culture, skill)` 删；不存在 ⇒ 具名拒，INFO）
全部走 `Command → ChangeSet → Revision`；`culture` 状态 = `CultureData(HouseholdCulture 表 + 规则表 + 定义表)`。

### 6.3 `simos-economy-api` 新增只读口（修正⑤（控制方）的关联基础）

```java
public record HouseholdLaborShare(HouseholdId household, long laborMilli) {}
public record UnitProductionFacts(ProductionUnitId unit, IndustryId industry, List<HouseholdLaborShare> laborShares) {}
public interface ProductionLaborLookup { Optional<UnitProductionFacts> factsOf(ProductionUnitId unit); }
```
- 数据源已在（`HouseholdLaborCommitment.activity` = unit id、`household`、`laborMilli`；economy 内部 `SettlementIndex.laborCommitmentsByUnit`）。
- **经济接收面不变**：沿用 `ProductionEfficiencyModifier(unit, modifierPerMille, source, reason)` 与 `EconomyDayStepper.updateProductionModifiers`；**不新建万能修正框架、不预建枚举矩阵**（复核 §2/M2）。

### 6.4 生产技能键的来源（P0 临时归属；随 P1 一并成为 production 权威数据）

- `ProductionSkillKey` 必须是**跨地区稳定**的技能键；`IndustryId` 是"整个产业"的身份但可能带位置语义（修正③（控制方））⇒ **不得**直接充当技能键。
- **查询口**：`ProductionSkillLookup`（在 `simos-production` 的 `contract` 包）。
- **具体目录**：`ProductionSkillCatalog`（具体类，非状态，住 production 内部**非 contract 包**）实现 `ProductionSkillLookup`：
  - `Optional<ProductionSkillKey> keyOf(IndustryId id)`；
  - 数据来源 = **声明式映射表**（P0 内置默认表覆盖现存全部产业种类 + 显式覆盖表）；P1 定义搬家后它成为 production 的权威数据，改由命令面维护；
  - **未命中 ⇒ 返回 empty** ⇒ economy 适配器把 `IndustryDefinitionView.skill` 填成 `Optional.empty()`，**产业视图照常返回完整真实定义**（修正 B），该 unit 的文化贡献按 1000 中性并记 INFO 一次（确定性、**具名原因、不静默**）；
  - **禁止**靠解析 `IndustryId` 文本或新增"键归一"猜产业种类（修正 C）。
- **装配**：具体目录只由 production/app 装配，**注入** economy 适配器；economy 只依赖 contract 接口（修正 C）。
- economy 适配器（Z3）只做装配：`IndustryId` + `ProductionSkillLookup` 结果 → `IndustryDefinitionView`；**适配器不含文化语义、不做任何加权**。

---

## §7 家户加成 → 生产单元修正：由谁算、怎么算（修正⑤（控制方） + 修正 A）

- **计算归属：`simos-culture` 的领域服务** `ProductionAptitudeComposer`（住 `io.mosire.simos.culture.aptitude`），作为**可测试的纯服务**实现。**未来启用后 app 只调用与传参，不实现文化公式**；**P0 本轮 app 连调用都不做（见下"修正 A"）**。
- **合成路径**（每 unit 一个结果，与接收口"每 unit 一个值"对齐）：
  1. `ProductionLaborLookup.factsOf(unit)` → 该 unit 的 `industry` + **按劳动量加权的家户列表**；
  2. `ProductionDefinitionLookup.industry(industry)` → `Optional<ProductionSkillKey>`；
  3. 每个家户 `CultureLookup.cultureOf(household)` → 其 `CultureId` → `rulesFor(culture)` 取该 skill 的规则；
  4. 合成：`blended = Σ(laborMilli_i × perMille_i) / Σ(laborMilli_i)`（千分加权，四舍五入到整数）；无规则的家户按 1000 参与加权；结果 clamp 到 `[0,2000]`；
  5. 输出 `List<ProductionEfficiencyModifier>`（`source` = `"culture.aptitude"`，`reason` = 规则来源摘要）。
- **技能缺失（修正 B）**：`industry` 存在但 `skill` 为空 ⇒ 该 unit 走**中性 1000** 并给**具名原因**（如 `reason="skill-unknown"` + INFO 一次）；**不得**因此把产业判为不存在，**不得**退回 economy 适配器拒绝构造视图。
- **不是"只看经营者文化"**：权重来自**全部出工家户**的劳动承诺（含雇农/佃户），经营者只在其确有劳动承诺时按同一规则参与。
- **确定性**：输入全部取自**已持久化状态**（文化状态、劳动承诺、定义视图），符合 §5.2"纯函数"要求；**派生倍率不另存表**（复核 §2/M13）。
- ★★ **修正 A —— P0 真实效果禁用是明确执行边界，不是"空世界所以中性"**：
  - P0 **真实日循环不消费可编辑规则**：app 接线点**无条件注入空列表**（`updateProductionModifiers(List.of())`，常量硬编码），**不调用** `ProductionAptitudeComposer`；因此即使命令面已 `SetHouseholdCulture` + `UpsertAptitudeRule` 写入非中性规则，P0 真实循环**仍全中性 1000‰**。
  - **这不是 GM 可开的开关，不新增激活位/profile/系统属性**；中性不是"碰巧世界为空"，而是"接收端固定空注入"这一显式执行边界。
  - `ProductionAptitudeComposer` 作为**可测试纯服务**保留（Z6 用夹具直接构造测试），但**未接入** P0 日循环。
  - **未来启用**：必须在**独立批次**里把"预期利润消费"与"实际生产注入"**一起**接线（同一 `IndustryDefinitionView` 与同一修正语义）；不得在本轮或任何单边批次偷跑。
- **一旦启用真实加成**（后续批次）：预期利润与实际生产必须消费同一 `IndustryDefinitionView` 与同一修正语义；允许累计实际修正与预测输入的时间窗口不同，但**不得复制两套定义/倍率**。

---

## §8 运行期协议

- **本轮不新增 `TimeParticipant`**（两模块没有自己的推进能力；不为满足计数造空推进器）。文化状态的写入全部来自命令面。
- **接线点（app）**：在当日 `step(day)` **之前**（与 `updateNaturalNeeds` / `recomputeLaborBudgets` 同日序先例）由 `CultureAptitudeBridge` **无条件**调用 `updateProductionModifiers(List.of())`；**本轮不调用 `ProductionAptitudeComposer`**，结果恒为空列表。**不新增激活位/开关**（修正 A）。
- **命令路由**：`culture.*` / `production.*` 由 `CommandBus` 按 namespace 路由；组合根注册两套 `ModuleCodec` + `CommandHandler`。
- **失败语义**：命令面拒绝 = INFO 具名理由；契约/跨切片一致性故障 = ERROR 不降级（沿用日志纪律）。

---

## §9 共享流动表协议（**只设计，不实现**；§9 全文带"旧双机制未替换"标记）

**三层时间口径（分开描述，不许混称）**
1. **逐 tick 评估**：候选方向与各域贡献的**纯函数**评估（可每 tick 跑，不落账）。
2. **周期执行**：在**市场结算周期**上落账（用户已定）。
3. **追加市场轮**：按**实际经过时间**折算，避免多开市凭空加速迁移。

**表的形状（概念契约，不落盘为状态）**：行 = 源户 × 目标户（两端稳定 `HouseholdId`）；字段 = 目标有效 hex · 目标经济位置/生产定义 · **各阶段贡献**（阶段名/来源模块/值/量纲/理由）· 合成速度 · 预计与实际人数 · 限制理由。

**阶段序（每阶段只写自己的贡献字段；表由 app 按不可变阶段结果持有）**
1. `init`：枚举本 hex + 六邻**全部结构可达**家户方向（不静默丢方向；不可达也保留行 + 理由）；
2. `culture` 阶段：同化与文化对流动的贡献；
3. `production` 阶段：生产定义/技术可用性贡献；
4. `economy` 阶段**最后算**：负债 → 库存 → 生产收益（按首轮原话顺序），在前三阶段输入之上合成实际速度。

**拒绝语义**：任一阶段拒绝 ⇒ 该行保留 + 具名理由，**不删行**。

**落账与原子性**：人口变更走既有 `HouseholdWorkOrderPlan`（Social 校验执行）；经济财货/债走经济变更；文化状态由 culture 按**实际**人数落账（不得按拟议人数先落）；全部纳入**同一次 `Command → ChangeSet → Revision`**，任一环失败整次不提交。

---

## §10 迁移计划（定义前置化；每阶段完成标准）

| 阶段 | 内容 | 完成标准 |
|---|---|---|
| **P0（本 spec/计划）** | 生产定义契约 + `ProductionDefinitionLookup`（economy 适配器）+ 文化状态与命令 + 合成服务（**可测试但未接入日循环**）+ app 接线（空注入）+ 机械面同步 | §11 的 V0–V5 全过；三处"未完成"标记落盘；**"尚未迁移"字样出现在契约与适配器类注** |
| **P1** | 把 `Industry`/`ProductionRecipe`/`ProductionMode` 的**定义部分**拆出并搬进 `simos-production`；**生产侧命令面在 P1 内即成为定义与注册的唯一权威写者**；economy 只留运行实例 + 按 id 经契约引用；codec/changeset/工具面/守卫同步；**economy 侧适配器与旧可编辑定义表在 P1 内一并删除**（不留第二份可编辑副本、不留双写） | 旧行为**逐值不变**（黄金读数）+ 包边界测试证明**全仓只有一份可编辑定义** + 适配器删除留痕 |
| **P2** | 在 P1 的**单一权威定义**之上，启用**真实**文化加成（预期利润与实际生产消费同一 `IndustryDefinitionView` 与同一修正语义）；移除"中性未启用"假设声明 | 唯一写者仍为 production（回归：**不得出现第二份定义写路径**）+ 预期/实际一致性测试 + 中性假设移除声明 |

★ **迁移定义 ≠ 搬运行实例**：劳动分配、账户、债务、生产进度**不搬**。P1/P2 各自单独出设计书与计划，不在本批偷偷推进。
★ **修正 G —— P1/P2 边界不许含糊**：**不得**承诺"P1 仍有适配器、P2 才切唯一写者而旧定义仍被双写"。唯一写者转移在 **P1** 完成；P2 **只**启用真实效果与移除中性假设，**不新增定义写路径**、不做真实迁移实现、不加通用规则引擎、不加新激活开关。

---

## §11 验收判据（含负向用例）

| ID | 判据 | 判定方式 |
|---|---|---|
| **V0** | 新模块可编译进 reactor；边界 `validate` rc=0（含新允许面与全仓禁列回改）；**POM/边界机械面先到位**再编译 | `cd /home/cna/SimulatorMosire && tools/mvn-lock.sh -f .claude/worktrees/parallel-side-module/pom.xml -q -DskipTests compile -pl simos-culture,simos-production,simos-economy,simos-economy-api,simos-app -am` rc=0；`validate` rc=0 |
| **V1** | 两模块变更集**往返不变式**（铁律 5） | `CultureRoundTripTest` / `ProductionRoundTripTest`（Z6 写），并登记进 `tools/gate.sh:94-99` 的 `GUARDS_TABLE` |
| **V2** | **中性默认（修正 A 的显式边界）**：① 未接线读数；② 接线后（空注入）读数**逐值不变**；③ **命令面填入非中性规则后仍不启用**（`SetHouseholdCulture` + `UpsertAptitudeRule(modifier=2000)` 后跑日循环，读数与未接线逐值相同，且接收端只收到空列表） | 三条路径读数逐值比；不许声称"效果生效"；③ 是**必须**项，不能只测空世界 |
| **V3** | 负向用例（每条必须有：改坏 ⇒ 当场红 ⇒ 还原） | N1 `production → simos-economy` 依赖 ⇒ enforcer 红 · N2 外部 import `io.mosire.simos.production.<非 contract 包>`（含 economy import 具体 `ProductionSkillCatalog`）⇒ 包边界测试红 · N3 culture 试图写人口 ⇒ 不可达/测试红 · N4 `IndustryDefinitionView` 出现运行实例字段 ⇒ 字段白名单测试红 · N5 `ProductionAptitudeRule` 越界值 ⇒ 构造期拒 · N6 同一 unit 重复注入 ⇒ 既有拒绝语义仍生效 · N7 `economy-api` 反向引用 production 新类型 ⇒ 构建红（环）· N8 **填非中性规则仍不启用**（同 V2③ 的负向实现）· N9 **缺 `ProductionSkillKey` 的现存在产业仍返回完整 `IndustryDefinitionView`**（`skill=empty`），文化贡献中性具名，不判产业不存在 |
| **V4** | **静默面同步**（不补就是静默） | `ArchitectureGuardsTest` MODULES、`EconomyVocabularyGuardTest` MODULES、`SimosToolsTest.commandHandlerSourceRoots()`、`ShellAssemblySmokeTest` 的模块/工具计数、变更集实现者清单 —— 全部按**实际新增能力**同步 |
| **V5** | 全量门禁 | 控制方在**写者停手后**跑 `tools/gate.sh`；本轮不产出"逐小任务测试" |

---

## §12 不变量

1. 铁律 1–5 全守；两新模块的变更集从完整状态类型派生并各有往返守卫。
2. **无环**：`production → economy-api` 单向；`economy-api` 不得反向引用 production 新类型；`economy → production`（只读 contract）不产生回边。
3. **唯一写者**：定义（本轮 economy／P1 后 production）、文化状态与规则（culture）、人口（Social）、财货债（actor/economy）。
4. **中性默认**：无文化数据 ⇒ 现有世界数值逐值不变；**且可编辑规则即使非中性，P0 真实循环仍不启用**（显式空注入，非开关）。
5. **派生倍率不另存权威表**；可编辑规则须有可回放来源（走变更集）。
6. **不为计数造空件**：空 participant、空工具、空状态一律不加。
7. **禁止项适用面精确**：新模块不依赖 core/app；任何模块不依赖 app；economy 只依赖 production 的 contract 包（不 import 具体目录、不解析 id 猜种类）。
8. **"以代码为准"只描述当前结构**，不构成"代码高于用户原话"的通则。
9. **缺技能不改定义可见性**：`ProductionSkillKey` 未命中只令文化贡献中性，产业定义查询照常返回完整真实定义。

---

## §13 文件所有权与责任区（供实现计划）

> ★ **每文件一个 owner**（`docs/agents/02-一-并发.md` §一.1/§一.5）；**不允许 Z2/Z1/Z3/Z4 任何一个改 root pom 或同个领域 pom**。POM/文档机械面由一个独立责任区（Z5）先一次做完，再由契约向消费方按依赖顺序编译（修正 D/E）。

| 区 | 范围 | 文件（新建/修改） |
|---|---|---|
| **Z5（机械面，先行）** | **全部 POM 与边界表**；单一 owner | 改：root `pom.xml`（`<modules>` + `<dependencyManagement>`）；**新建** `simos-culture/pom.xml`、`simos-production/pom.xml`（含各自 enforcer 与允许面 `<dependency>`）；改：**其余 15 个模块**的 `pom.xml`（`bannedDependencies` 补两条 exclude + ban 文案）；改：**允许面变更的模块显式声明依赖**（`simos-economy/pom.xml` 加 `simos-production`；`simos-app/pom.xml` 加两个新模块）；改：根 `AGENTS.md` 边界表、`docs/agents/01-〇-铁律与模块边界.md` 全表。**不拥有任何 Java 文件、不拥有任何测试文件、不拥有 `tools/gate.sh`** |
| **Z2** | `simos-production` 全部生产代码（**只 Java**） | 新建：`io/mosire/simos/production/**`（`ProductionData`、`ProductionSnapshot`、`codec/ProductionCodec`、`change/ProductionChangeSet`、`contract/**`（含 `ProductionSkillLookup`）、`skill/ProductionSkillCatalog`（非 contract，内部）、`spi/**Handler`、`ProductionLog`/`ProductionLogSource`、`package-info.java`） |
| **Z3** | 经济侧契约与适配器（**只 Java**） | 新建：`simos-economy-api/.../api/production/ProductionLaborLookup.java`、`HouseholdLaborShare.java`、`UnitProductionFacts.java`；新建 economy 侧 `EconomyProductionDefinitionLookup`（**构造注入 `ProductionSkillLookup`**）+ `EconomyProductionLaborLookup`（`simos-economy/.../production/…`，类注标"尚未迁移"） |
| **Z1** | `simos-culture` **完整**生产代码（契约/状态/命令/合成服务，**同一责任区，不拆两次写者**） | 新建：`io/mosire/simos/culture/**`（`CultureData`、`CultureSnapshot`、`codec/CultureCodec`、`change/CultureChangeSet`、`contract/**`、`spi/**Handler`、`aptitude/ProductionAptitudeComposer`（+ 纯函数部件）、`CultureLog`/`CultureLogSource`、`package-info.java`） |
| **Z4** | 组合根接线（**只 Java**） | 改：`Shell.java`（codec/handler 注册；装配 `ProductionSkillCatalog` 并**注入** economy 适配器）；新建 `simos-app/.../household/CultureAptitudeBridge.java`（**无条件空注入** `updateProductionModifiers(List.of())`，不调用 composer）。`simos-app/pom.xml` 由 Z5 统一改 |
| **Z6** | 测试与变异（**统一交 Z6 测试代理**） | 新建/改：两模块 `*RoundTripTest`、包边界测试、视图字段白名单测试、负向用例 N1–N9、`ArchitectureGuardsTest`/`EconomyVocabularyGuardTest`/`SimosToolsTest`/`ShellAssemblySmokeTest` 清单、`tools/gate.sh` GUARDS_TABLE；变异自证与全量门禁由控制方调度 |

**实施顺序（修正 D/E，硬序）**：**Z5（POM/边界一次做完）→ Z2 production 完整生产代码（`-DskipTests compile`）→ Z3 经济契约/适配器（提供 `ProductionLaborLookup`；注入契约）→ Z1 culture 完整代码含 composer → Z4 app → Z6 统一 Java 测试和测试清单（含变异）**。生产代理**只过 `-DskipTests compile`**，不写不跑 Java 测试；每责任区一个写者、一次写完，不按计划小编号逐个派代理。

★ **主锁 + `-f` 执行路径（修正 E；本设计代理未跑）**：`/home/cna/SimulatorMosire/tools/mvn-lock.sh` 内 `ROOT=<主检出>`、锁目录 `<主检出>/.mvn-lock.d`、执行 `<主检出>/mvnw "$@"`；传 `-f .claude/worktrees/parallel-side-module/pom.xml` 时 reactor 根是该工作树 pom，而锁仍是**主仓锁** ⇒ 与主仓其他 Maven 串行。**该路径必须在 Z5 用一条最小命令实测并记 rc**；**锁策略作用域只限本仓/本工作树，不擅自扩张到其他独立项目**。工作树自带的 `tools/mvn-lock.sh` 是另一把锁（锁工作树），**本批不使用**。

★ **协作风险（写进计划，不许当无事）**：Z4 要改 `simos-app/.../Shell.java`，而隔壁线近 4 小时在 `simos-app` 改了 **94 个文件**（`Shell.java` 在其中），Z5 要改 **15 个模块的 pom**（含隔壁线正在动的模块）。⇒ 本侧线在**独立工作树**里做（已隔离），但**合并回 main 时这两处必然有冲突**：Z4/Z5 的改动要小、集中、可机械重放；合并由控制方在隔壁写者停手后进行。

---

## §14 未做 / 未验证（如实）

- 本 spec **未写任何生产代码**、未改 POM、未跑 Maven/测试/变异、未起服务、未动隔壁在飞文件。
- 依赖方向是**待构建验证**的技术方案（V0 会给出 rc）；"无环"目前是静态论证 + enforcer 预期，**尚未实测**。
- **主锁 + `-f` 执行路径**是按脚本文本静态核过（`tools/mvn-lock.sh` 的 `ROOT`/锁目录/执行行），**未实际运行**；V0/Z5 的最小复现命令与 rc **未产出**。
- `ExpectedProfitBook` 无修正消费一句为**转抄**（复核 §2/M12），本设计代理**未回代码核**。
- `MoneyOutput`/`AllocationRule` 的**全部**字段我只核到 `perUnit`/`Split`/`WageFirst` 三处主形状；实现期须逐字核（`WageFirst` 的 `ownerResidual` 键类型已核 = `CommodityId`）。
- 技术掌握（`TechnologyDefinition`）的形状是**接口草案**，不含传播/升级/成本算法。
- 共享流动表只有协议，无实现、无成本与数值稳定性验证（沿用调查报告 §8 的未验证清单）。
- 三条日期疑点沿用先行证据（efficiency 框架文件名晚 16 天、social-api 稿晚 4 天），本 spec 未复跑历史日期。
