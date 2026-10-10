# A7 实现账本 —— 作废判据从"关账 unit 的主体家户"扩到"任何持有 haul 的家户"

> 角色：**写码 Agent**（责任区 A7）。日期 2026-10-10。
> 判据来源：任务书四条 + A6 真实 world 账本 `.superpowers/sdd/2026-10-10-a6-verify-cycle-expiry/impl-ledger.md` §1.2/§3
> + 约束设计书 `docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` **v1.5** 修订记录第 ① 条。
> ★ 用户原话（逐字，设计书 §1）：「**运力作为特殊商品不可储存转卖，只能计算后在生产环节统一兑现、自动算收益**」。
> 纪律：只改生产代码、只到编译过；未跑 `test`/`verify`/world；未 `git commit`；未碰 `src/test/**`、`pom.xml`、`docs/**`、
> `.superpowers/**`（除本账本）。HEAD：`f93c3df9`（A6 落账）。
> ★ 一句话结论：**作废作用域 = "会话里任何持有 haul 键的家户"，触发时点与落点一字未动**（关账日、收获入账之前、
> `consumeForLoss` + `market-haul-service-expired`）；A6 的 5,249 毫逃逸口在结构上被收掉。

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | 证据（回代码核，§四） | 结论 | 对 A7 的影响 |
|---|---|---|---|
| S-1 | A5 的作用域 = `EconomySettlement:679` 的 `HouseholdRouting.subjectOf(unit, ...).all()`，遍历 `closingHaulUnits` | 作废只覆盖"产 haul 的那个 unit 的账户主体家户" | 扩域点就在这一段；改后**不再需要任何主体解析** |
| S-2 | `AccountSession.householdGoods()`（`:448`）返回 `AccountView`，`entrySet()`（`:541-582`）按 `householdIndex`（`:115` 的 `LinkedHashMap`）迭代；`ActorAccount.goods`（`:49`）是 `LinkedHashMap`，余额归零即去键（`setStock` `:9803-9807`） | **"该户持 haul" ⟺ 内层表里有 haul 键**；外层键集 = 全部已登记家户 | 清扫目标 = `householdGoods` 里 `containsKey(HAUL)` 的户；无需新状态、无需第二处判据 |
| S-3 | 触发点 `EconomySettlement:2031`（`industry.recipe().outputPerUnit().containsKey(HAUL)`，且该 unit 本日关账）→ `:2043-2051` 在 `harvestPartitioned`（`:2052`）**之前**调用 | "关账日 + 收获前"这一时点是 A5 已验证的机制（A6 §1.1 逐日 0 违反） | 时点**一字不动**，只换"扫谁"；触发 unit 退化为**开关 + 日志归因** |
| S-4 | `harvestPartitioned` 是本阶段唯一的产出写入口（`creditOutput` → `:7913` 区）；A7 的清扫在它**之前** | 清扫那一刻账上的 haul **一律**是"本周期收获之前就存在"的存货 | 全局清扫**不会**误伤当期新产（A5 §3-2 "错峰周期会误伤别的格当期存货"的担忧由本时点条件排除） |
| S-5 | `MerchantCapacityPool.assemble`（`:446-455`）服务口径 = `max(0, 现货 − 冻结)`，且在 `select`（承运判定点）**现读活表**；池装配在 `EconomySettlement:2230` 区（清扫之后、市场轮之内） | 清扫后池**天然**只见本周期产出，无需给池加第二处判据 | 与 A5 同一结构性论证，扩域后依旧成立 |
| S-6 | `consumeForLoss`（`:9072-9098`）= 非换手损耗唯一写口：`available < requested ⇒ 一点也不扣`；成立则 `setStock` + `ledger.addLoss` 同址 | 守恒（I-H2）由**写口**保证，不由调用方保证 | 扩域不改落点，守恒自动继承；请求量恒 = `available` ⇒ 结构上不会部分扣 |
| S-7 | `haul` 的**生产**配方只有 `trade`（`EconomySettlement:2031` 的判据就断言了这一点）；`haul` 的**读**只有 `MerchantCapacityPool:449-455`（运力）、`MarketSettlement:2157/7231/7627`（成交/牌价）、本处作废；`OperatorSettlement:257` 读的是 `outputPerUnit`（钱，不读货） | haul 在货物面上**只**被当"运力"读 | "只影响 haul"结构性成立：改一个商品键的余额 ⇒ 只可能改运力与它的日志 |
| S-8 | `OperatorSettlement.selfUsableOf`（`:886` 区）只遍历 `industry.inputPerUnit()`（`trade` = `{tool:100}`） | 作废 haul **不进**任何状态机/自给判据 | 与 A5 S-9 同结论：状态机判据不受影响 |
| S-9 | `git diff -U0` 的 16 个 hunk 全落在 `:626-779` / `:1856-1859` / `:2043-2053` 三段 | 改动面收敛，未意外触及其它区域 | `:8997` 的既有 `Map.copyOf` 不在本次 diff 内（A7 新增代码零 `copyOf`） |

## 2. 自己的实现架构（改动文件 + 落点）

| 文件 | 落点 | 做了什么 |
|---|---|---|
| `simos-economy/.../time/EconomySettlement.java` | `:625-779`（`expireUnservedHaulService` 本体，签名 `:667-671`，清扫循环 `:677-750`） | ★ 清扫目标改为"任何持 haul 的家户"：遍历 `householdGoods.entrySet()` 收 `containsKey(HAUL)` 的户（`:678-686`）→ **按 `HouseholdId::value` 升序排序**（`:688`）→ 逐户 `max(0, 现货 − 冻结)` 作废 |
| 同上 | `:2043-2051` 调用点 | 入参从 7 个降到 4 个（去掉 `householdEconomies` / `enterpriseByProcess` / `settlementIndex` —— 不再解析主体）；注释改述扩域 |
| 同上 | `:1856-1859` 收集表注释 | `haulExpiryUnits` 语义收紧为"触发开关 + 日志归因" |
| 同上 | 日志行（错误 `:721-741`、INFO `:765-791`） | `HAUL_SERVICE_EXPIRED`：`closingUnits` → `triggerUnits`，新增 `holders`（持货户数）与 `scope=all-haul-holders`；`_DETAIL` 去掉 `unit`/`industry`（作废不再与某个 unit 绑定）、加 `scope` |
| `simos-economy/.../time/HaulService.java` | `:46-54` 类注、`:77-88` `SERVICE_EXPIRED_ACCOUNT` 注 | 口径文档同步（A7 扩域 + 为什么） |

**新作废口径（一行）**：**产出运输服务的 unit 关账那天**（`progressed >= cycleDays`）、在**本周期产出入账之前**
（`harvestPartitioned` 之前），对**会话里任何持有 `haul` 键的家户**（= 全部已登记家户 ∩ 有 haul 键；顺序 = 家户 id 规范串升序），
把每户 `max(0, haul 现货 − haul 冻结)` 经既有非换手损耗唯一写口 `consumeForLoss` 记进
`HaulService.SERVICE_EXPIRED_ACCOUNT`（`market-haul-service-expired`；账户减 + `ledger.addLoss` 同址 ⇒ Σ余额 + losses 守恒）。

**为什么"字面也成立"**：A6 的逃逸口是"服务货**离开了**产它的家户"（实物还债 → 已退出商家名册的持货户）。新口径的
清扫集合**不再依赖"谁产的"**，只依赖"谁此刻持货" ⇒ 无论货经转移、继承、还债落到谁手上，只要在关账日之前还在账上，
就与生产者自留的那一份**同一批**被作废。⇒ 任何时刻（含下一次边界）池里能卖的服务货只可能是本周期产出的；
"上一周期的服务不得跨周期留存、不得再卖"因此不再依赖"持货户恰好是商家户"这一巧合。

## 3. 关键判断（为什么这样拆 / 否决了什么）

1. **触发点保留"关账 unit 存在"，只扩清扫集合**（S-3）：这样缺省中性仍是**结构性**的（无跑商 unit ⇒ 收集表为空 ⇒
   首行返回，连一次 `accounts.householdGoods()` 都不取），而"轮末"的语义仍锚在**周期边界**而不是"每一天"。
   否决"每日清扫"（会在周期中途抹掉本周期合法产出的存货 —— 方向反了）与"市场轮清扫"（市场轮看不到"关账"这一事实）。
2. **不新增任何持久状态、不加"产出周期"标号**（铁律 5）：A5 §3-1 已否决过"给运力池加本周期产出过滤"（那需要逐户逐周期的
   产出追踪 = 新组件）。A7 走的仍是"周期性销毁不可储存物"这条**既有**路线。
3. **顺序显式排序**（`Comparator.comparing(HouseholdId::value)`，与 `MerchantCapacityPool:419/567/761` 同一把尺）：
   虽然 `householdGoods.entrySet()` 本身保序（S-2），显式排序让 I7 不依赖"账户登记序恰好确定"这一间接事实。
   **禁** `Map.copyOf`/`Set.copyOf`：本文件里既有的 `Map.copyOf`（`:8997`，`governmentByTreasury`）**不是本次改动**，
   本次新增代码零 `copyOf`。
4. **`available ≤ 0` 的户与"没有 haul 键"的户都不落账**：冻结量不动（它是别处已明确的占用），不制造 0 额条目
   （与 A5 同款，`consumeForLoss` 对 `requested ≤ 0` 直接抛）。
5. **日志字段改名而不是新增解释**：`closingUnits` 在新口径下**是错的**（会让人以为它就是清扫范围）⇒ 改名 `triggerUnits`
   并补 `holders` + `scope`，让"扫了几个户"与"谁触发的"在日志面上分得开（A6 的逐日对账靠这些行）。

## 4. 偏离记录（主动记录，不隐瞒）

| # | 偏离 | 原因 |
|---|---|---|
| D-A7-1 | `HAUL_SERVICE_EXPIRED` 的字段 `closingUnits` → `triggerUnits`，并新增 `holders`/`scope` | 旧名在新口径下是**误导**（它不是清扫范围）。★ 这会让 A6 式日志解析脚本里读 `closingUnits` 的那一处**读不到值**（未验证项，见 §6） |
| D-A7-2 | `HAUL_SERVICE_EXPIRED_DETAIL` 去掉 `unit`/`industry` 两个字段 | 作废不再绑定某个 unit（A7 的核心）⇒ 留着会把"触发 unit"写成"被作废物的 owner" |
| D-A7-3 | 方法签名去掉 `householdEconomies`/`enterpriseByProcess`/`settlementIndex` 三个入参 | 扩域后不再需要主体解析；留着会成为"看起来还要解析主体"的沉默误读源 |

## 5. 会改变数值行为的清单（含缺省中性论证）

| # | 改动 | 是否改数值 | 说明 |
|---|---|---|---|
| C-1 | ★ 作废作用域扩到"任何持 haul 的家户" | **改**（**只**在"关账日 + 存在持 haul 的非主体家户"时） | 与 A5 的差集 = **非"关账 unit 主体"的持货户**的陈货。真实世界两例：B 世界 `hh-0_0-urban-poor_peasant` 的 5,249、`hh--1_0/-1_1-rural-landlord` 的合计 96,960（A6 §1.2 实测）。⇒ 这些户的 haul 现货归零、`market-haul-service-expired` 损耗账增加等额、`Σcap` 相应变化（若该户此后进池）。★ **A 世界 0 变化**（A6 实测 A 的 5 条 haul 转移 = 0，期末存货全在生产者手上）。 |
| C-2 | 缺省中性 | **不改** | ① 无跑商 unit（无 `trade@hex` / 产出表不含 haul）⇒ `haulExpiryUnits` 空 ⇒ 首行返回，**一次表查询都不做**（与 A5 逐字相同）；② 有跑商 unit 但**全世界没有一户持 haul** ⇒ `holders` 空 ⇒ 不排序、不落账、不改一个字节（新增的第二道中性）。 |
| C-3 | 只影响 `haul` | **不改别的商品** | 读写的商品键恒为 `HaulService.HAUL_COMMODITY`；写入只经 `consumeForLoss(...HAUL_COMMODITY...)` ⇒ 每户内层表只有 haul 那一个键被换掉。无跨商品分支。 |
| C-4 | 触发时点/落点 | **不改** | 仍是"关账日、收获入账之前、`consumeForLoss` + `market-haul-service-expired`"。⇒ A6 §1.1 的逐日恒等式与 `HAUL_SERVICE_SETTLED.expiredMilli` 同源同值不变。 |
| C-5 | 日志字段改名/增删（D-A7-1/2） | **不改数值**（日志面） | 事件名不变；日期与三个量不变。 |
| C-6 | 无跑商家户/模式未选中 | **不改** | 同 C-2①（I-H3 的结构性开关仍是"有没有 `trade@hex` 关账"）。 |

## 6. 未完成 / 未验证项（如实记）

1. **未跑任何测试/world/verify**（任务书只要求编译过）⇒ C-1 的数值后果（5,249 / 96,960 归零、损耗账等额增加）**没有实测证据**；
   留给 A8/验证批。
2. **会改变既有验收读数的两处**（★ 供测试/验证 Agent 判定，**未改任何测试**）：
   - (a) A6 §1.2 的"边际等式 `Σcap(边界日) == 当日净产`"仍然成立，但**推导路线变了**：清扫不再只抹"池里看得见的量"，
     而是抹"账上全部的陈货" ⇒ 用 `Σcap(d)` 做逐日递推的对账面在"持货户退出/进入商家名册"的日子会**少一项**
     （此前 A6 §1.4 的假违反会消失，但恒等式 B 的逐日形式在边界日可能表现为"expired 读数 > Σcap(d−1)"）。
     验证批应改用**状态 dump** 收口（spec v1.5 修订记录第 ② 条已有同款口径警告）。
   - (b) 若存在断言"非商家持货户的服务货跨周期留存"的既有测试 ⇒ 会红（这类断言**必须改**，但按纪律**未改**，在此上报）。
     ★ 已 grep 全仓 `src/test/**`：`HAUL_SERVICE_EXPIRED` / `expireUnservedHaulService` / `SERVICE_EXPIRED_ACCOUNT`
     在 `src/test/**` **零命中**、`closingUnits` 在测试里零命中 ⇒ 未发现会被本改动直接判红的断言（但**未编译/未运行测试**，
     这是静态判断，不是实测）。
3. **未验证**：多跑确定性（N-H3）—— 排序是内容的纯函数，但需要两跑 dump 才能实测。
4. **未处理的边界（如实记，与 A5 §6 同族）**：若同一世界存在**错峰周期**的多个 `trade` unit，先关账的那个会在它的边界日
   抹掉全世界（含尚未关账的 trade unit 家户）的陈货。真实世界两格 `trade@0_0/0_2` 同为 120 天相位（A6 实测），故不可达；
   本批**不为此新增机制**（要处理就得给商品打"产出周期"标号 = 新持久状态 ⇒ 铁律 5）。
5. **未验**：`holders` 排序对大型世界（38 户）之外的家户数无影响论证，未做大世界压力读数。
