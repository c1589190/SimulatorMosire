# 实现账本：跑商"生产资料预留"接回标准挂单保留面（§16.4 ①，最小第一步）

责任区 = `necessary` 的"tool 至少一趟"覆盖**所有跑商家户**（与 trade unit 无关）+ **不按运力上界** + 不改门槛/标定。未跑 test/verify/world、未 commit。

① **改动文件 + 落点**（4 个 main 文件，均 `simos-economy`）：
- `time/MarketSettlement.java`：`:8634-8646`（`necessaryInputsOf` 尾部下夹一趟，仅当声明量更小才抬）+ `:8675-8681`（**唯一拼写点** `merchantHaulToolReserveMilli`，"为什么是一趟"写在 `:8651-8674`）+ `:413`（`MarketRound.merchantHouseholds` 字段，缺省 `Set.of()`）+ `:836/:847/:889`（getter / `withMerchantHouseholds` / 保序冻结，**不新增构造器签名**）+ `:1560-1563` + `:4492`（计划轮克隆丢字段 ⇒ 具名 ERROR `MERCHANT_HOUSEHOLDS_LOST_BY_CLONE` + fail-closed）+ **9 处克隆各一行** `next.merchantHouseholds = ...`（9 个 `new MarketRound(` 站点逐一对应，grep 实测 9↔9）。
- `time/MerchantIdentity.java:108-136`：新增 `merchants(standings, positions)` = `selectsMerchant` 的**范围化**（与既有 `pureMerchants` 同形）⇒ **没有第二份判据**。
- `time/EconomySettlement.java:1933-1941`（结算路径注入）、`time/MarketReadout.java:193-199`（读口同源注入，否则"看到的订单 == 会下的订单"当场破）。
- 判据范围**不用** `MerchantCapacityPool` 成员表：池成员多一道"运力 > 0"过滤，会漏掉"选了跑商但无运力"的户，与"覆盖所有跑商家户"矛盾（用它 = 第二份判据）。

② **两条真实命令与结果**：`tools/mvn-lock.sh -q spotless:apply -pl simos-economy` → rc=0（无输出）；`tools/mvn-lock.sh -DskipTests compile -am` → `BUILD SUCCESS`（16 模块，11.672s，EconomySimos/SimosApp SUCCESS）。未跑 test/verify。

③ **保留口径（一行）**：跑商家户 `necessary[tool] = max(产业声明量(inputPerUnit × 规模), MerchantHaul.TOOL_MILLI_PER_HAUL)`——"至少一趟"= **下夹**（不是 sum：求和的唯一后果是把 trade 那一户从 10,000 抬到 11,000）；**单拼写点** = `MarketSettlement.merchantHaulToolReserveMilli`（量取 `MerchantHaul.TOOL_MILLI_PER_HAUL`，**一字未改**，全仓无第二个字面量）；**为什么是一趟**：一趟 = 跑商的最小可成立单位（`MerchantHaul.blockedReason` 的判据就是"可用量 ≥ 1 趟"），保留只为"别把这一户的工具全额挂出去"；按运力折算会随劳动线性放大、把存量品工具长期冻在账上（= 抽干工具市场）。★ 该保留不经 `supplies` 守卫：它问的是"投入是不是自己供的"，而趟耗**无条件**扣本户 tool 商品账。

④ **会改变数值行为的清单**：(a) 跑商家户的 tool **可卖量**每户至多少 1,000（`:2093 sellable = max(0, 存量 − 冻结 − necessary − 保留)`），进而 `commitFreezes` 少冻 1,000 ⇒ 跑商当刻可用量 ≥ 1,000；(b) `:7851 sellerSelfUsable(tool)` 对其转真 ⇒ 未成交 tool 卖单归因变 `UNSOLD_SELF_USABLE`（读数/归因，不改量）；(c) 读口 tool 供给同源下降；(d) 真实世界里原来被自家冻结拦住的趟会跑起来（烧工具、收运费、lane 被服务）——这正是本批目的。**缺省中性论证**：集合空 ⇒ `merchantHaulToolReserveMilli` 恒 0 ⇒ `if (haulToolReserve > 0)` 整块不执行 ⇒ `necessary` 的键集/插入序/值逐值不变 ⇒ 四个消费点（`:2093` 卖量、`:2492` DEBUG 复算、`:7851` 自用判据、`:8000` 仅经营者分支）全不变；`Math::addExact` 路径、冻结语义、撮合序、`LinkedHashMap` 保序均未触碰（新代码零 `Map.copyOf`/`Set.copyOf`，冻结用 `Collections.unmodifiableSet`）。

⑤ **对非跑商家户零影响的证明（静态，按构造）**：本轮唯一新增的**值** = `merchantHaulToolReserveMilli`，其前置条件 = `participant.household != null ∧ round.merchantHouseholds().contains(household)`；集合只能由 `MerchantIdentity.merchants`（内部逐户 `selectsMerchant`，表为 `null` ⇒ 空集）产生，注入点**恰 2 个**（结算 + 读口），两者都读**同一份** `classMemberships × classPositions`（池用的是同一对表）⇒ 非跑商家户恒不在集合里、恒 0，market 轮的每个产出逐值不变。夹具/旧构造器/空表世界的集合恒 `Set.of()`（字段缺省）⇒ 与改前逐字节同值。9 处克隆逐字段带过 + 计划轮守卫兜底（守卫只可能在"克隆丢字段"时触发，正常路径恒假）。★ 边界（如实记）：**"选了跑商但运力 = 0"（不在池里）的户也会拿到这 1,000 的保留** —— 这是判据口径（"覆盖所有跑商家户"）的直接后果；把范围收窄到池成员需要第二份判据，本批不做。

⑥ **未完成 / 未验证**：① 未跑任何 test/verify/真实 world ⇒ "3,589 趟被拦 ⇒ 跑起来"、"工具市场是否被冻"这两个**数值后果未自证**，也未验证既有测试断言是否受影响（夹具构造器不吃集合 ⇒ 预期不受影响）；② 读口与结算的同源性只做静态审计；③ 守卫分支从未触发（结构性死代码，除非将来克隆再丢字段）；④ 明确未做（后续批，需用户点头）：`trade.inputPerUnit`"100/规模·周期"与趟耗口径对齐、收益接标准关账/删 `MerchantProfitBook`、前瞻算式统一、劳动队列对 trade 的具名排除。
