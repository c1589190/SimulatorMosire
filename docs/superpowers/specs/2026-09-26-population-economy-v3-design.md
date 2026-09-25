# 第三阶段：人口—劳动—生产关系（v3）

**日期**：2026-09-26
**性质**：用户 2026-09-26 转达的设计方向（"让'人'成为持续存在的人口主体，让'劳动'成为可以分配但不能凭空重复的资源，让'阶层'成为生产关系的结果"）落成的工程口径。
**取代**：`ClassRow` 作为**人口载体**；`Industry` 作为**唯一生产抽象**。
**保留**：V1–V6 已验证的**库存 / 周期结算 / 债务 / 守恒**框架（一年模拟已证其账能平：第 5 周期库存增量 `27,301,475` == `Σ(income − consumed)`，**差 = 0**）。
**依据**：本文件的所有"现状"事实都出自 2026-09-26 的**只读调查**（三轮共 9 个并行 agent），逐条带 `文件:行号`；**未跑 Maven、未改代码**。

---

## 一、为什么（三处**实测**空洞，不是推测）

### 1.1 没有"人口"这个实体——只有**三套各算各的账**

| 账 | 载体 | 会不会变 |
|---|---|---|
| 农村 | `SocialData.populations: Map<HexCoord, PopulationSeries>` | **不变**（`withGrowthSegment`/`withEvent` **零生产调用者**；`social` **不是时间参与者**） |
| 城市 | `SocialCity.population: long` | 仅 `social.UpdateCity` 显式改 |
| 经济 | `ClassRow.population: long` × (产业 × 槽位) | 运行期唯一写点 = `applyFamine`（默认致死率 **0** ⇒ 默认**永不改变**） |

- **经济侧的人口是从生成器 DTO 抄来的快照**：`EconomySeeder` 引的是 `io.mosire.simos.social.gen.{SettlementPlan,PlannedCity}`（**生成期产物**），**不是活的 `SocialData`**；两侧**编译期零交叉引用**（economy 的 enforcer 硬禁 social）。
- **实测**：首都格 `(-39,-71)` 的 `/api/social/population` 报 **15,191**（只农村），而该格 economy 两侧合计 **365,191**（农业 15,191 + 手工业 350,000）。⇒ `/api/social/population` 逐格求和 = **10,507,900**，economy 各行求和 = **11,830,000**，**差 1,322,100 = 全部城市人口（11.2%）**。
- **没有年龄、没有性别**：`simos-social/src/main` 全模块 grep `age|sex|gender|birth|death|mortality|aging|cohort` = **0 命中**。唯一的年龄档在 `EconomySeeder:136,139`（`{350,550,100}‰ × {0,1000,300}‰ ⇒ 每人 580‰`），**创世期一次换算、不落盘、不推进**。

⇒ **一旦开始出生、死亡、迁移、阶层变化，三套账必然漂移**——现在只是"还没来得及漂"。

### 1.2 没有"劳动守恒"——同格可以对同一批人各算一次满额

- 行的身份 = `ClassKey(IndustryId, ClassSlotId)`；**每个产业有自己的一整套 `ClassRow`**，同一格的 farm 与 craft **各带一份人口与劳动，互不知道对方**（`EconomySettlement:320` `classKeysOf` 按 `key.industry()` 过滤）。
- 劳动投入**按产业循环独立累加**（`:321-326`）；收获瓶颈也只是**本产业内**三路取小（`:720`）。唯一的跨产业交互是同格借粮（`:507-601`，只搬粮、不碰劳动）。
- **`Σ laborMilli == 格人口 × 580` 是构造性恒等**（`laborMilli` 对人口线性 + `splitByShares` 无残差），**但全仓零断言守护** ⇒ 改城乡切分或加第三个产业，**不会有任何用例变红**。
- ⇒ 现状**不存在**"同一批人的劳动投入之和 ≤ 其可用劳动"这个概念。**农村纺织（同一批人农闲织布）在当前结构里表达不了。**

### 1.3 阶层是固定槽位、LAND 是死的

- **阶级不是 enum、也不是全局固定四槽**：`ClassSlot` 只有 `(id, name, laborParticipationPerMille)` 三字段；四个槽位由**每个产业各自声明**，字面值硬编码在 `EconomySeeder:111,133,108`。
- **没有任何"人口跨阶层/跨产业转移"的 API**（全仓 `migrat|阶层流动|ClassFlow|transferPopulation` 的实质命中只有一句注释「阶层流动…同样不做」）。
- **`LAND` 的真实语义**：**"这一行有这么多亩地可种"**（千分亩），创世时按该行**人口**从"格土地 = 3100 亩 × 地形系数"里成比例切出（`EconomySeeder:214-222`），**此后永不变动**（运行期零写入，四处 `new ClassRow` 全透传）。它只参与三处计算：播种需求量、收获面积上界、分配权重的"生产资料权重"。
- **代码里没有任何一处把它标注为"经营权"**，也没有所有权对象——`AssetRightId`/`ClaimId`/`TransferId` 在 `simos-economy` 里**零引用**。

### 1.4 农业生产是**硬绑**的（这决定 V7 的形状）

`EconomySettlement.harvest:705-722`（**控制器逐行复核过**）：

```java
rowLand[i] = row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L);   // :705
long availableMu = totalLandMilliMu / 1000L;                             // :711
long ableMu      = avgLaborMilli * LAND_MU_PER_LABOR / 1000L;            // :712
long seedPerMu   = industry.cycleInputPerUnit().getOrDefault(AssetKind.LAND, 0L); // :718
long actualMu    = Math.min(availableMu, Math.min(ableMu, seedCapMu));   // :720  三路全是"亩"
long perMu       = industry.outputPerUnit().getOrDefault(GRAIN, 0L);     // :721  ← 商品硬绑 GRAIN
long gross       = actualMu * perMu * MILLI_PER_GRAIN;                   // :722
```

- **三路瓶颈全是"亩"**、产出键**写死 `GRAIN`**、分配权重**只读土地**（`:736`）。
- **`outputPerUnit` 是 `Map<CommodityId,Long>`**（键已能放多商品），**但"每单位**什么**"这一维是隐式约定（亩）、不是数据** ⇒ "每座工坊产 N 匹布"**表达不出来**。
- **地形在结算里完全不参与**（`simos-economy/src/main` 零 `io.mosire.simos.map` 引用）；地形只在**生成器**里缩放土地面积（`EconomySeeder:214`）。
- **后果已在真档可见**：201 个手工业产业**恒产 0**（`meansOfProduction` 为空 ⇒ `availableMu=0`），城市 132 万人纯消费、只借到缺口约三成 —— 一年模拟里**缺口 66% 在城市**。

---

## 二、三层拆分（本阶段的核心决定）

**现状的病根是：`ClassRow` 同时扮演了三个角色**——它既是"人"（`population`）、又是"经济主体"（`goods`/`money`/`meansOfProduction`/`debts`）、还是"生产关系"（`participationPerMille` + 被固定在某个 `Industry` 下）。**继续给它加字段只会把病根埋得更深。**

⇒ 拆成三层，**各自只有一条真相**：

| 层 | 回答什么问题 | 住哪 | 身份 |
|---|---|---|---|
| **`PopulationGroup`** | 这些人**是谁**、住哪里、年龄性别、身体状态 | **`social`** | `PeopleLotId`（**已存在**，注释原文："本 ID 是该批次的**稳定身份**"） |
| **`EconomicActor`** | **谁**持有资产/库存/货币/债权债务 | `economy` | `ActorRef`（**已存在**，"种类 + 稳定 id 两件，**跨模块引用任何经济主体而不依赖它所在的切片**"） |
| **`Relation` / `LaborAllocation`** | 人与主体之间**是什么关系** | 见 §八 落点 | 各自稳定 id（照 `Debt`/`Claim` 的形状） |

★ **`PopulationGroup` 绝不能再变成新版 `ClassRow`**：它**不装** `poor_peasant`/`farm`/`serf`/`debt` 这类标签。**"贫农/中农/富农/地主"要从生产关系推导出来，而不是当主键。**
★ 只有这样，"**富裕依附农 / 贫穷自由农**"这种交叉才表达得出来——现在表达不了，因为阶层就是主键。

---

## 三、`PopulationGroup`（人口实体）

```java
// 住 simos-social；id 用 simos-economy-api 的 PeopleLotId
record PopulationGroup(
    PeopleLotId id,
    HexCoord residence,          // 现居格；迁移 = 换 residence，id 不变
    Sex sex,                     // MALE / FEMALE；★ 见待裁 1
    long count,                  // 人
    long ageAtAnchorDays,        // 锚点时刻的年龄（天）
    long anchorTick,             // 锚点（世界日）
    long physiologicalStress,    // 生理压力累积；★ 见 §七
    // needSatisfactionHistory 见待裁 4
) {
  /** 年龄是**派生量**，不每天改字段。 */
  long ageDaysAt(long nowTick) { return ageAtAnchorDays + (nowTick - anchorTick); }
}
```

- **年龄用"逐日精度 + 锚点"**，不做五岁桶——**这是用户旧设计（`POLITICAL_ECONOMY_DESIGN.md:71`）的原口径**，原文明确否决五岁桶："年龄用天数或出生 tick，**不用 5 年桶作运行中的时间单位；0—4、5—9 等只是查询聚合**"。
- **性别做**（`Sex` enum）——用户旧设计里有（`PeopleLot(count, ageDays, sex, …)`），但**从未有一行 Java**（2183 个历史 blob 穷举 0 命中）。
- ★ **D4 的三档不再作运行时模型**：回收为**世界生成期的年龄分布 preset** 与初始劳动参与参数（即 `EconomySeeder:136,139` 那两个常量降级为**创世输入**，不再是结算口径）。
- **年龄段只在查询时聚合**（读口/GM 面板按需算 0-14/15-59/60+）；**任何"档间转移"都不需要**——因为没有档。

★ **落地成本已实测**（见 §八.1）：新增一个组件 ⇒ **57 处 `new SocialData(…)` 编译不过**（main 5 + test 52）、**3 处测试自动红**（`SocialRoundTripTest` 的两处 `default -> throw` + `hasSize(2)`），其余零改动。

---

## 四、劳动（人口与经济之间必须补上的桥）

```
PopulationGroup ──→ LaborSupply ──→ LaborAllocation ──→ ProductionProcess
```

**最重要的新不变量**（本阶段的判据）：

```
Σ allocatedLabor(group)  ≤  availableLabor(group)
```

- `availableLabor(group)` = `Σ(count × ageSexCoefficient)`（年龄×性别→劳动系数，版本化参数）**− 已服役 − 已承诺**。
- `LaborAllocation` 是**一次分配**：`(group, actor, activity, laborMilli, period)`；同一 `group` 在同一 `period` 内**所有** allocation 之和不得超上限。
- ★ **"男耕女织"不是"男 = 农业，女 = 纺织"**——而是**性别与年龄影响各类劳动活动的默认配置权重**，且允许因农忙/战争/劳动力短缺/收益变化**重新配置**。
- ★ **农业有了季节性之后**，"农忙 ⇒ 农业劳动需求升 ⇒ 家庭纺织降 / 农闲反之"就会**自然出现**，比"农村额外生产若干布"强得多。

★ **本阶段的压力测试 = 农村纺织**：验证"**同一批人口能否同时参与多个生产过程，并保持劳动力守恒**"。
　配方形如 `FIBER + LABOR + TOOL → CLOTH`，由农村家庭自己承担。

---

## 五、V7：通用生产（`ProductionRecipe`）

**V7 不该被取消，但它现在成了本阶段的公共底座。** 它要解决的是 §1.4 那处硬绑：

```text
ProductionRecipe
    capacity requirements:  LAND / WORKSHOP / MACHINE ...
    consumed inputs:        GRAIN / IRON / WOOD / FIBER ...
    labor requirements
    outputs:                GRAIN / CLOTH / TOOL ...
```

- **生产规模统一按最紧约束决定**（把现在写死在 `harvest` 里的三路"亩"泛化成"每种 capacity 一路"）。
- **`outputPerUnit` 的"每单位什么"必须成为数据**，不能再是隐式约定（亩）。
- **两个投入表的类型必须同时改**：`dailyInputPerUnit` / `cycleInputPerUnit` 现在是 `Map<AssetKind,Long>`，**值侧没有商品维度**，表达不了"消耗 IRON"⇒ 要变成 `Map<AssetKind, Map<CommodityId,Long>>`。
  ★ **它们当前是零读取点的死字段**（`dailyLaborPerUnit` 亦然），**所以改它们的运行期风险为零**，只牵 codec/payloads/测试。
- **生产技术参数从每个 `<kind>@q_r` 实例抽到版本化参数目录**（现在 799 格 × 2 产业 = 1,598 个实例各持一份配方，改配方要批量改实例）。

★ 参数目录的**分型**（`覆盖式` vs `实例式`）与**落地形态**是 spec v2 §四 定的，但**它自己有两处口径打架**（见待裁 5）。

---

## 六、V8：统一资源取得与转移

**最终不该让消费逻辑自己知道"先吃库存、再向地主借、再向富农借"**，而是：

```text
ResourceRequest  →  OwnInventory / Loan / Market / Ration ...  →  EconomicTransfer / Transaction
```

- 商品流、货币流、债权债务**对称记录**（现在**没有**通用转移原语：全仓 main 源码 grep `transfer` **零命中**；借粮是内联在 `settleHexes:558-599` 的专门逻辑；**唯一的双侧移动刻意不对称**——放贷行扣库存但**不进它的 `consumed`**）。
- ★ **`simos-ledger` 的 `Transfer` 形状接近，但被主体类型堵死**：它的 `from`/`to` 是 `ActorRef`，而 `ActorKind` 只有 `{PEOPLE_LOT, UNIT, GOVERNMENT, ORGANIZATION}`——**没有"阶层行"这一档**；而 economy 的 `Debt` 用 `ClassKey`。⇒ 两套债务模型并存、主体类型不兼容。**且 D1 未裁**（见待裁 3）。
- ★ 另外：`Transfer` **一腿一条**（多方交易要拆多条、靠 id 约定回溯，**没有腿集结构**），且**守恒校验/幂等键/原子性三项全无**（明文"不在本切片、归命令层/协调器"）。

---

## 七、人口再生产与社会危机

- **出生：月度结算**。育龄女性按**具体年龄、人数、历史生活资料满足情况**产生出生。**第一版不做复杂家庭婚姻机制，但至少不要用总人口乘一个增长率。**
- **死亡**：`年龄 × 性别 × 基础死亡率 × 生理压力`。
  ★ **缺粮不直接对应死亡人数**，而是累积 `physiologicalStress`：短期缺粮加一些、恢复供给后逐渐消退，**长期严重不足才显著抬高死亡率**。这样"一次五天供应中断"与"连续半年严重营养不足"**不会产生同样的死亡结果**。
- **`CrisisMonitor`（薄）**：按"当前需求满足水平 / 相比历史基线的突变 / 儿童·青壮年·老年人分别受影响程度 / 债务增长 / 劳动负担 / 未来庄园劳役负担"，向 GM 出**红灯**。
  ★ **它输出的是"某地发生生活资料/社会再生产危机"，不是"暴动概率 83%"。**
  ★ **本阶段绝不直接生成起义机制**——起义、逃亡、抢粮、请愿、救济留给以后的政治系统。
- ★ **饥饿与死亡的时间尺度必须能分开表达**（用户原话）：这正是"FOOD 与 CLOTH 两种需求、粮食不足与衣物不足对死亡的时间尺度显然不能一样"的落点。

---

## 八、模块、落点与依赖

### 8.1 落点（附出处，不是我的偏好）

| 类型 | 落点 | 依据 |
|---|---|---|
| `PopulationGroupId` | **复用 `simos-economy-api` 的 `PeopleLotId`** | 它**已存在**且注释写着"本模块是该批次的**稳定身份**"；本轮调查还发现它被 spec v2 `:443` 列入"11 个纯死代码"待 D1 裁 —— **本阶段它复活，不再是死代码** |
| `Sex` / `PopulationGroup` | `simos-social`（领域类型） | 人口归 social（spec v1 §二） |
| `LaborAllocation` / `Relation` | **`simos-economy-api`** | ★ **文档三处明文把 social 列为它的消费者**：`economy-api/package-info.java:9-11`「五个经济切片…**与 `simos-social` 可依赖本模块与 util/map**」、根 `pom.xml:25-26`、`economy-api/pom.xml:16-18`。**改 pom 只 1 行**（social 加一条依赖），且 social 的 enforcer 不拦 |
| `EconomicActor` | `economy` 切片 | 持有资产/库存/货币/债权债务 |

★ **`ActorKind` 要扩档**：现有四档**没有家户/庄园/作坊**，需扩枚举 + 同步改 `EconomyIdsTest:116-119` 的逐值断言。
★ **`simos-util` 不是落点**：它的章程是"**只提供原语，不理解任何领域概念**"（`util/package-info.java:4,7`）——放领域类型进去违背自述。

### 8.2 跨切片引用与原子性（**现成的机制够用**）

- **跨切片引用一律走不透明 `ActorRef`**（`(kind, id)` 两件），**不 import 对方模块**——这正是 `ActorRef` 的设计意图（其 jvm 注释原文："**跨模块引用任何经济主体而不依赖它所在的切片**"），已有 `Claim`/`Transfer`/`Account` 三个消费方。
- **跨切片原子性现成支持**：`WorldTimeProposal(participantId, Map<String,ChangeSet> moduleChanges, reads, writes)`（**在 `simos-util/spi`，不在 core**），**契约不限切片数**（唯一约束"非空"——"无事的参与者应交**不变变更集**，不是空"）。原子性由 `TimeAdvance` **逐模块校验、任一不过整日拒**承担。
- **约束**：同一次推进里**两个参与者不得改同一模块**（`TimeProposalResolver:86-98`，模块键相交即 `Blocked`）。⇒ 跨 `social`+`economy` 的协调器**必须住 `simos-app`**（唯一认识所有模块的地方）。

### 8.3 必须一并修的依赖洞（否则"边界可信"是假的）

`AGENT.md:55-58` 已记：`util/map/social/unit` 的 **ban 列表停留在 M0（2026-09-16，当时全仓只有 5 个模块）**，此后新增模块**从未回填**。⇒ 当前 **`social → economy`、`social → economy-api`、`core → economy/economy-api/ledger` 都不会被拦**。
　★ 注意：本阶段**要主动开口**让 `social → economy-api` 合法（文档本就允许），但**要补禁** `social → economy`（那是真漏洞）。
　★ 另有 `simos-app` **用了 `util`（56 个 main 文件）与 `economy-api`（2 个）却不声明**，靠传递依赖——本仓曾把同款情形定性为缺陷并修过（"**依赖传递不是契约**"）。

---

## 九、分四轮（每轮独立可验收）

> 12 步不能塞进一份 spec/plan（`AGENT.md` §五.6：评审体量不得压过代码本身）。四轮各自 spec→plan→实现→关账。

| 轮 | 内容 | 验收判据 |
|---|---|---|
| **R1 底座** | 清 enforcer 洞 + 补 app 依赖声明 + `PopulationGroup` 落地（`social` 加组件）+ `SocialCity.population`/`ClassRow.population` 降级为派生 + `social` 成为时间参与者（**只做 aging 的骨架，不做出生死亡**） | 新旧人口账**逐格相等**（`Σ group == 原 rural+urban`）；57 处构造点全绿；旧档仍能读；`social` 出现在推进日志的参与者里 |
| **R2 劳动** | `LaborSupply` / `LaborAllocation` + **跨产业劳动守恒** + **农村纺织**当压力测试 | `Σ allocated ≤ available` 逐组成立；**农忙/农闲两季的纺织投入不同**（季节性是判据，不是装饰）；同格 farm+craft **不再各自满额** |
| **R3 生产** | V7 通用生产（`ProductionRecipe` + 多商品 + 两种投入表换型 + 参数目录）+ 城市作坊 | **非 LAND 生产成立**（`FIBER+LABOR+TOOL→CLOTH`）；城市能靠**自己的产品**换到农村粮食；`outputPerUnit` 的"每单位什么"是数据 |
| **R4 人口再生产与转移** | 月度出生/年龄推进/死亡 + `physiologicalStress` + `CrisisMonitor` + V8 统一转移 + 庄园制度 | 缺粮**五天**与**半年**产生不同死亡结果；红灯**只报危机类别**、不含概率；地租/税/工资/借贷**走同一条转移原语**；`LABOR_RENT/IN_KIND_RENT/MONEY_RENT` 三档各有用例 |

**不做的**：真市场/价格/国家/阶级流动/起义机制（第 12 步）**不进本阶段**。

---

## 十、待裁 → **已全部裁定**（2026-09-26，用户原话见下）

> ★ **追加不删**：下方 1~7 的**原推荐保留作留痕**，本节只追加裁定结论。用户 2026-09-26 原话：
> 「1 性别必须做 2 对 3 对 4 其实重建也没关系，怎么方便怎么来 5 我没意见 6 我没意见 7 我没意见
> **总之我只要看更全面更新的模拟结果**，怎么实现代码我不想多管，来吧！」

| # | 裁定 | 对实现的具体含义 |
|---|---|---|
| 1 | **性别必须做** | `PopulationGroup.sex` 落 `Sex` enum；"男耕女织"的默认劳动配置权重按性别分档 |
| 2 | **D4 降为生成期 preset** | 年龄运行时**只有逐日精度**；`{350,550,100}`/`{0,1000,300}` 降级为创世输入，**不再是结算口径** |
| 3 | **D1：`simos-ledger` 退役** | 删模块；`Transfer` 的**形状**（不是它的 `ActorRef` 主体类型）搬进 `economy-api`，主体统一到 `ClassKey \| ActorRef` |
| 4 | **旧档：重建也没关系，怎么方便怎么来** | ⇒ **不做迁移工具、不为旧档写兼容分支**。**唯一保留的一行**：新组件的 `null ⇒ 空表`（否则随包的 `worlds/v17levant.json` 打不开——那是**创世文件**，不是旧用户档）。**旧世界存档可作废**（含 `/tmp/simos-year1` 那条 600 天基线） |
| 5 | **V7 参数归类：无异议** | 判据 = "**这个数在一国之内是否逐实例不同**"。按此 `outputPerUnit`（全实例同值）= **覆盖式**；`cycleInputPerUnit`（将来"旱地要多种子"则逐实例不同）= **实例式**；`allocation`/`cycleDays`/`ClassSlot.laborParticipationPerMille` = **实例式** |
| 6 | **`EconomicActor` 粒度：无异议** | actor = **格 × 制度**（每格每制度一个聚合主体）；个体差异靠**组内分布**（`PopulationGroup` 按 年龄×性别×居住 分组已足够） |
| 7 | **social 跨组件校验：无异议** | **加**，并立成 **R1 的验收判据**（`group` 必须落在有 `populations` 序列的格上；`Σ group == 该格原 rural+urban`） |

★ **第 4 条的实际收益**：省掉一整类"旧档兼容"工作（每个组件一条缺键用例 + 迁移工具 + 双向兼容讨论）。
★ 但**"兼容是单向的"这条事实仍然要写进 spec**（§十.4 原文）：新代码能读旧档、**旧代码读不了新档**——
这条与"重建也没关系"**不矛盾**：你可以直接重建，但**别指望旧 jar 能读新档**。

---

### 以下为原推荐（留痕，不再是待决项）

1. **性别做不做** —— 设计方向说做（`PopulationGroup.sex`）。**推荐：做**（用户旧设计里有，从未实现，且"男耕女织"的默认权重正需要它）。
2. **D4 三档降为生成期 preset** —— **推荐：降**（年龄改逐日精度后，档位不再需要作运行时模型；保留它当**创世输入**，`EconomySeeder:136,139` 两个常量降级）。
3. **D1（`simos-ledger` 退不退）** —— ★ **直接卡住 V8 的形状**。**推荐：退役**，把 `Transfer` 的**形状**（不是它的 `ActorRef` 主体类型）搬进 `economy` 或 `economy-api`，主体改用 `(ClassKey | ActorRef)` 统一口径。理由：它的"逐主体账户"与"库存住阶层行"**直接冲突**（v1 spec §一 明文判过），且它**无 handler/无 codec 注册/无人依赖**。
4. **旧档迁移 vs 重种世界** —— **推荐：兼容（不重种）**。依据：**读侧极便宜**（每个组件一行 null 兜底，先例原文"抛了等于整个世界打不开"）；贵的是写侧那 57 处，**一次性的**。
   ★ 但必须知道：**兼容是单向的**——新代码能读旧档，**旧代码读不了新档**（`SimosObjectMapper:46` 不关 `FAIL_ON_UNKNOWN_PROPERTIES`，且被一条用例单独钉住）。要双向就得引入状态版本号，**本仓当前没有**。
5. **V7 参数归类（spec v2 自身矛盾）** —— `outputPerUnit`/`baseYield` 在 §4.3 归"全局（覆盖式）"、在 §4.7 归"实例式典型"；「每亩需种」**同时出现在两列**。
   **推荐判据：看这个数在一国之内是否逐实例不同**。按此：`outputPerUnit`（全实例同值 67）= **覆盖式**；`cycleInputPerUnit`（若将来"旱地要多种子"则逐实例不同）= **实例式**；`allocation`/`cycleDays`/`ClassSlot.laborParticipationPerMille` = **实例式**。
6. **`EconomicActor` 的粒度** —— ★ **决定"复杂度不随人口线性增长"守不守得住**，且**设计方向里两处诉求相反**（"阶层要成为生产关系推导的结果"vs"要有富裕依附农/贫穷自由农"）。
   **推荐：actor 的粒度 = 格 × 制度（每格每制度一个聚合主体）**，个体差异靠**组内分布**（`PopulationGroup` 按年龄×性别×居住分组已提供足够分辨率）。理由：若"每个人口组一个 actor"，actor 数 ∝ 组数，会炸；而这个粒度下"富裕依附农 vs 贫穷自由农"通过**同一 actor 下的不同 group 的劳动/义务分配**表达。
7. **`social` 侧首例跨组件校验**（`social` 现有两组件间**零跨表校验**，与 economy 相反）—— 要不要加"`group` 必须落在有 `populations` 序列的格上""`Σ group == 城市人口`"？
   **推荐：加**，但要作为**R1 的验收判据**显式立起来（否则"统一人口账"只是口号）。

---

## 十一、本文件的验证边界

- 本文件**未修改任何 Java、未跑 Maven、未跑迁移**。§一 的全部"现状"事实出自 2026-09-26 的只读调查（逐条带 `文件:行号`）；§一.1 的两组人口数字是**实测**（跑着的实例 + MCP/HTTP 读口），§一.4 的公式是**控制器逐行复核**过的。
- **§七 的公式（出生率、基础死亡率、生理压力的衰减率）本文件不给具体值**——它们属"判断结果"，按 `docs/superpowers/design-creed.md` 信条十二应是**GM 可调参数**，其默认值待 `CrisisMonitor` 与月度结算落地时按真档观察后再定（沿用"先做数学基础、不预设阈值"的口径）。
- **`Sex` / `PopulationGroup` / `LaborAllocation` / `EconomicActor` 都还不存在**（全仓 grep 零命中）；§三/§四 给的是**形状**，不是既有代码。
