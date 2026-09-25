# 设计要点：R2（劳动底座）

> **给实现者**：本文只给**判断与判据**，代码你写、测试你跑。
> 判据出处：`docs/superpowers/specs/2026-09-26-population-economy-v3-design.md` 的 **§四（劳动）**、§二、§八、§十。
> 前序：R1（`69487db`）、R1.5（开读口）已关账；其要点在 `.superpowers/sdd/2026-09-26-population-r{1,15}/brief.md`（**先读**）。

## 目标（一句话）

**让"劳动"成为可分配但不能凭空重复的资源**：同一批人（`PopulationGroup`）可以**按配额**把劳动供给**多个**产业，且**总和守恒**。

## 现状（为什么这条现在是空的）

- 行的身份 = `ClassKey(IndustryId, ClassSlotId)`；**每个产业有自己一整套 `ClassRow`**，同格 farm 与 craft **各带一份人口与劳动**（`classKeysOf` 按 `key.industry()` 过滤）。
- **劳动投入按产业独立累加**（`EconomySettlement:321-326`）；收获瓶颈也只在本产业内三路取小（`:720`）。
- `Σ laborMilli == 格人口 × 580` 是**构造性恒等、零断言守护**；**不存在**"同一批人的劳动投入之和 ≤ 其可用劳动"这个概念。
- **硬绑定**：创世时**农村批次 → farm 行、城镇批次 → craft 行**（`PopulationSeeder` / `EconomySeeder`），一个批次只喂一个产业。

⇒ **农村纺织（同一批人农闲织布）在当前结构里表达不了。** 本轮先把**劳动这一层**做出来；真正产布要等 R3 的 V7 生产配方。

## 范围

### T0：读口收口（**控制器已裁定，照做**）

R1.5 留了两处，控制器已裁：

1. **`population` 字段语义改为「批次求和（真值源）；若该格无批次则回退旧序列」，并新增 `source` 字段标明用的是哪一个**
   （取值 `batches` / `legacySeries`）。
   ★ 理由：批次是 spec §二 定的真值源；但随包 bootstrap 世界（`worlds/v17levant.json`）**只有旧序列**，
   把它读成 0 会让"世界还没初始化"看起来像"这格没人"。
   ⇒ 要改 `ApiViews.population` 一处 + 两条既有期望值（`GuiApiTest` / `SimosToolsTest`，**按新口径重算，不是放宽**）。
2. **`PopulationFacet` 收口**：`/api/facets`、`simos.map.facets` 上的 `population` facet **仍报农村序列**
   —— 这是"同一资源两个形状"的残留。⇒ **改成与 `ApiViews.population` 同源**（同一份派生量）。
   ★ 若你判断 facet 的语义就该是"农村人口"，那**必须改名**（如 `ruralPopulation`）——**不许两个数共用 `population` 这个名字**。

### T1：`LaborAllocation` 类型 + `availableLabor`

- **落点 `simos-economy-api`**（文档三处明文把 `simos-social` 列为它的消费者；R1 已让 social 依赖它 ⇒ **本模块两侧都看得见**）。
- `availableLabor(group)` = `Σ(count × 年龄×性别劳动系数)` **− 已服役 − 已承诺**（后两项本轮可为 0，但**字段/入口要在**）。
  ★ 年龄×性别系数**复用 R1 已落地的表**（`AGE_LABOR_COEF_BY_SEX`，默认两性同表）——**不要另写一套**。
- `LaborAllocation` 形状照本仓"关系实体"的共性（record + 稳定 id + 两端引用 + 构造期不变量）：
  `(id, PeopleLotId group, <主体引用> actor, String activity, long laborMilli, long period)`。
  ★ **主体引用的类型是本题的岔口**，见下「要你定的一件事」。
- ★ **跨切片引用一律用不透明 `(kind, id)`**（本仓 `ActorRef` 的设计意图原文："跨模块引用任何经济主体**而不依赖它所在的切片**"），**不许**让 economy 编译期认识 `PopulationGroup` 的类型。

### T2：打破"一个批次只喂一个产业"的硬绑定

- 现在：农村批次 → farm、城镇批次 → craft（创世时定死）。
- 本轮：**同一个批次可按配额供给多个产业**。最小形态：
  - 批次上不再隐含"我属于哪个产业"；**归属由 `LaborAllocation` 表达**；
  - 创世仍按"农村→farm / 城镇→craft"**初始化**配额（**默认行为与现在逐值相同**，真档既有数字不动）；
  - ★ **但结构上必须允许** "农村批次 → farm 0.8 + craft 0.2"。
- ★ **守卫**：`Σ allocated ≤ available` 必须**在构造期或命令边界可判**（照本仓"模型允许的状态，结算必须能处理"的反面教训——前几轮栽过 `progressDays == cycleDays` 与 `WageFirst` 两处）。

### T3：跨产业劳动守恒（**本轮的判据**）

- 新不变量：`Σ_{a ∈ allocations(group, period)} a.laborMilli ≤ availableLabor(group)`。
- 结算里 `laborToday` 必须**从 `LaborAllocation` 求**，**不再**从"本产业各行 `laborMilli × participation`"独立算。
- ★ **真档可见**：读口要能回答"**这一格的劳动被哪个产业占了多少**"（R1.5 已开读口，本轮加这一维）。

### T4：读口暴露

- 在 R1.5 的读口上加**劳动分配**一维（按格：各产业占用劳动 / 该格可用劳动 / 占用率）。
- ★ 沿用 R1.5 的权限判定，**不许**另造更宽的。

## ★ 要你（实现者）定的一件事，并在报告里说明理由

**`LaborAllocation.actor`（劳动供给的接收方）用什么类型？** 三个选项，各有代价：

- **(a) `ClassKey`**（产业 × 槽位）：改动最小、与现有 `Debt` 同形；**但"主体"仍是阶层行**，与 spec §二"三层拆分"相悖。
- **(b) `ActorRef`**（不透明 kind+id）：符合 §二；**但 `ActorKind` 现只有四档**（`PEOPLE_LOT`/`UNIT`/`GOVERNMENT`/`ORGANIZATION`），要扩"家户/庄园/作坊"⇒ 连带改它的逐值断言测试。
- **(c) 新 `EconomicActorId` + 新 actor 类型**：最贴 §二，但**本轮就要引入 `EconomicActor`**（比 spec §九 的 R2 范围大）。

**推荐 (b)**：它复用本仓既有的跨切片通道、把"扩 actor 种类"变成一次枚举扩充，且**不需要本轮就建 `EconomicActor`**（actor 的**内容**可以晚点填，先有**身份**）。
★ 但这是你的判断——**若你选 (a)/(c)，必须在报告里说明为什么 (b) 不够**。

## 不做（留白，不是遗漏）

1. **不产布**：`CLOTH` / `FIBER` / `TOOL` 的多商品生产属 **R3（V7 通用生产）**。本轮只做**劳动**这一层。
2. **不做 `EconomicActor` 的完整类型**（家户/庄园/作坊的内容），只做本轮需要的最小身份。
3. **不做季节性**（农业劳动需求随时间变）——那要 R3 的生产配方；本轮只保证**结构上允许**配额变化。
4. **不做出生/死亡**（R4）；**不动 `simos-ledger`**（V8 轮）。

## 铁律

- **不许放宽断言**；合法改法只有"按新口径重算期望值"（算式写进注释）与"修夹具"。
- ★★ **夹具必须刻意混合**：本轮做"同一批次供给两个产业"，夹具里**必须真的有**一个批次同时有两条 allocation——否则"只看第一个产业"的实现照样绿（**假绿**）。R1 的实现者在这里栽过。
- **判别力声明必须真的成立**：写完"由 X 承担判别力"，X 必须**存在**且**真的会红**；**逐条自检并把表报给我**。
- **每条护栏配变异自证**：改坏判据 ⇒ 必须**红** ⇒ 用 **Edit 反向重写**还原 ⇒ 复绿。
- ★ **机械改动逐个 Edit，不要用批量脚本**（R1 的实现者用批量脚本误伤 8 个测试文件）。
- ★ **验证工具本身**：报"零命中/全绿"前先证明读取模式对。**禁止 `2>/dev/null` 吞工具错误**。
- **一次只能跑一个 Maven**；判过不过看 `target/surefire-reports/*.txt` 的 **mtime 落在本轮**，不看 rc。
- 改完跑 `./mvnw -q spotless:apply`；若它动了你没改过的文件，**停下报告**。
- **收尾前台跑 `./mvnw clean verify`**，把 **Reactor 逐模块结果**抄进报告。
- **不要 `git commit`**。
- ★ **默认行为必须与现在逐值相同**：真档既有数字（自给率、库存、天数守恒）**一个都不许变**——除非你改的是"结构"而不是"取值"。若真档数字变了，**停下报告**。
