# 实现架构账本：G3-fix-2 —— 让 tool 门槛在真实世界真的生效（重开 G3-fix-1 的缺陷 2）

- **责任区**：G3-fix-2。**输入证据**：`.superpowers/sdd/2026-10-10-g3b-reverify/impl-ledger.md` §2（3,589 逐值未变 / 928 组 SHORT+`toolBurnedMilli=0` / 708 组连运费腿成立）、
  `.superpowers/sdd/2026-10-10-g3-fix-1/impl-ledger.md` §2.2、`.superpowers/sdd/2026-10-10-g3-real-world-verify/impl-ledger.md` §3.6。
- **日期**：2026-10-10。**实现者**：办事子 Agent（只写生产代码、只到编译过；**未写/未跑任何测试**、未跑真实 world、未 commit）。
- **允许写**：`simos-economy/src/main/**`、`simos-app/src/main/**`。**禁止碰**：任何 `src/test/**`、`pom.xml`、`docs/**`、`.superpowers/**`（本账本除外）、`AGENTS.md`。

---

## 1. 关键调查结论（file:line 证据 → 结论 → 影响）

| # | 证据（改动前 file:line） | 结论 | 影响 |
|---|---|---|---|
| 1 | `EconomySettlement.java:1859-1866` 装配池（已传 `householdGoods` / `householdFrozenGoods`）；`MarketSettlement.java:1584` `commitFreezes(ctx)`；`:6396` `ctx.carrierPool.select(...)` | 池装配在**市场轮之前**，本轮卖单冻结在**市场轮内**才落表 | 装配点读到的"冻结"**结构上不可能**含本轮承诺 ⇒ `max(0, 现货 − 冻结)` 的减项恒 0（G3b 实测：池读数 `toolMilli` 全是原始存量） |
| 2 | `MarketSettlement.java:2646-2701`（`commitFreezes`）：`sell.baseFrozenGoods = frozenGoodsOf(...)` → `refreshSellFrozen` 写**绝对值** `base + Σ该轴承诺` | 本轮的**全部**卖单承诺在 `commitFreezes` 返回时已在活表里（整轮一次写清，不是逐格/逐笔） | ⇒ "选择点"是可见本轮冻结的最早时点；门槛若放在它之后就能读到真实可用量 |
| 3 | `MarketSettlement.java:8622-8638`（`frozenGoodsOf`/`setFrozenGoods`）读写 `round.householdFrozenGoods`；`EconomySettlement.java:938-941` 的八个活视图；`:1813-1841` `MarketRound` 构造；`withCredit/withArbitrage/withFx`（`MarketSettlement.java:662/703/1000`）**按引用**带过八张表 | 池装配时拿到的两张表**就是**市场轮写的那两张活表（同一 `AccountSession.AccountView`，`get` 是现读） | ⇒ 不需要把表"再送一次"：缺的只是**读取时点**；把现读挪到选择点即可，零新写口、零新状态 |
| 4 | `MarketSettlement.java:6351-6440`（`executeTrade`：`select` → `allocated<=0 ⇒ return 0`；`:6557-6572` 铸 `CARRIER_FEE`；`:6617-6620` 才 `settleHaulRuns` 烧工具） | 运费腿**先**于烧工具腿；`select` 不放行 ⇒ 整笔不成交（既不铸腿也不走货） | ⇒ 修法必须落在 `select`（成交之前），不能靠事后回补；`select` 一旦拦下，"SHORT+fee"组合在结构上不可能出现 |
| 5 | `MarketSettlement.java:3828-3831`（池非空 ⇒ `matchWithinRegionsSerial`） | 有运力池的世界**整个区内撮合退回协调器单线程**，跨区也是协调器 | ⇒ `select` 只在协调器上跑 ⇒ 现读活表不会触发 `AccountView` 的 owner 守卫（worker 副本拿 `MerchantCapacityPool.empty()`，`:3984`） |

**结论**：G3-fix-1 的修法（读可用量）**方向对**，但读取时点错在"装配点" ⇒ 真实世界行为空操作。本批只改时点，不改判据形状、不改任何写口/常量。

---

## 2. 实现架构（落点与数据流）

**口径（本批冻结，写作时点唯一）**

```
时点   MerchantCapacityPool.select 内、逐条承运条目被判定的那一刻
       （市场轮内、MarketSettlement:1584 commitFreezes 之后；与提交侧读同一张 householdFrozenGoods 活表）
算式   判据量 = min(本轮预算余量, max(0, 当刻现货 − 当刻冻结))
       < MerchantHaul.TOOL_MILLI_PER_HAUL ⇒ 该条跑商**不成立**：不进 choices ⇒ 不成交、不铸运费、不烧工具
归因   tool-short（现货 < 一趟）/ tool-frozen（现货够、可用量不够）/ tool-budget-exhausted（预算镜像先耗尽，生产不可达）
```

- **落点 1（核心）**：`MerchantCapacityPool.java:671-696` —— `select` 的循环体里，把原来的
  `MerchantHaul.affordsRun(item.remainingToolMilli)` 换成"现读可用量 + 取较小者"：
  `liveToolStockMilli`（`:779`）/ `liveToolFrozenMilli`（`:787`）读活表，`toolCheckMilli = min(remainingToolMilli, available)`
  （无活视图时 `available = -1` ⇒ 只用预算，旧路径逐值不变）。
- **落点 2**：两张活视图**随池一起带入**：`of(..., goods, frozenGoods, quotes)`（6 参，生产唯一入口，`:221-227`）
  → `assemble(..., liveGoods = goods, liveFrozenGoods = frozenGoods)`（`:238`）；4/5 参重载传 `null`（`:164`/`:198`）
  = "本池没有活视图"（夹具/纯状态读者逐值退回改前）。装配体成为唯一拼写点，三个公共重载各自显式。
- **落点 3（具名归因）**：`toolBlockReason(stock, available)`（`:808`）+ `Entry.recordToolBlock`（`:504-517`）记三支计数与首次样本；
  车道 DEBUG 行 `MERCHANT_CAPACITY_LANE_TRUNCATED` 增 `toolFrozenBlockedRuns`/`toolShortBlockedRuns`/`toolBudgetBlockedRuns`
  且 `reason` 按实际发生的分支拼（`:835-844`）；轮末新增**逐户** DEBUG 行 `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT`（`:908-948`，带 `day`/户/格/三支计数/首次读数）
  与 INFO 汇总的三支拆分（`:1058-1066`）。
- **数据流（不改）**：`EconomySettlement` 装配（`:1859`）→ 池 → `MarketSettlement.clearOncePerCycle`（`:2019`）→ `commitFreezes`（`:1584`）
  → 撮合 → `select`（`:6396`，本批新增现读）→ 成交腿 → `settleHaulRuns`（`:6618`，`consumeForLoss` 判据一字未动）。
- **`MerchantHaul`**：新增第三个 reason 常量 `TOOL_BUDGET_REASON`（`:88-96`），使两个政策名不被"预算镜像"情形污染；判据常量与算式一字未改。
- **`EconomySettlement`**：只改注释（`:1850-1860`）—— 删掉 G3-fix-1 那句**与代码顺序相反**的时序论证（"装配点读到的冻结就是本轮冻结的同一事实"），写明真实时点与落点。

**为什么这次不会重演"输入恒空"（自证：哪个时点的表被读）**

1. 池持有的 `liveGoods`/`liveFrozenGoods` 是 `EconomySettlement.java:938-940` 的 `AccountSession` 活视图；市场轮写冻结走
   `setFrozenGoods(ctx.round, …)`（`MarketSettlement.java:8630-8638`），`ctx.round.householdFrozenGoods` 与池持有的是**同一个对象**
   （`MarketRound` 由 `EconomySettlement.java:1819` 传入同一变量；`withX` 克隆按引用带过）⇒ 池的读**必然**看得见本轮承诺。
2. 读取发生在 `commitFreezes` **之后**（`:1584` → 撮合 → `:6396` `select`）⇒ 本轮全部卖单承诺已在表内。
3. 判据用的是当刻读数，不再缓存：一次 `select` 内不写账户（烧工具在 `settleHaulRuns`），两次 `select` 之间账户已变 ⇒ 每笔按各自判定时刻取量。

---

## 3. 关键判断（为什么这样 / 为什么不另一条路）

1. **现读放在 `select` 内，而不是"把池装配搬进市场轮"**：装配需要 `classMemberships`/`classPositions`（会话表，`MarketSettlement` 不认识），
   搬进去要么给它开新入参、要么新建第二套装配点（本仓反复禁止"两处各写一遍"）；而 `select` 已经持有活表引用，"只改读取时点"是最小且最紧的改动。
2. **判据量取 `min(预算余量, 当刻可用量)` 而不是直接用当刻可用量**：预算余量 = "本轮已放行趟次"的镜像。取较小者**只可能更严、绝不放松**
   ⇒ ① 缺省中性可证（无冻结时两者逐值相等，见 §6）② 夹具/直接调 `select` 的旧用例（静态表、不经账上烧工具）语义逐值不变
   ③ 不会造出比改前更多的承运。代价：若某户在轮内**买到**工具（可用量升过预算），本批仍按预算拦（fail-closed），见 §4-2。
3. **不动 `consumeForLoss`、不动 `MerchantHaul` 常量与算式**：提交侧的 fail-closed（一点也不烧）是"绝不部分烧"的唯一守卫，必须原样保留；
   本批只让**上游**看得见同一张表。→ 两者从此同口径、同活表、同时点附近（相隔同一笔成交内的几条腿）。
4. **不加"事后回补/回退运费腿"**：运费腿先铸、工具后烧是既有正向链；在成交之前判死是该结构下能做到的最强 fail-closed，也符合既有
   "运力不足 ⇒ 那一份不成交、不成债、不计价"（K-4/Q-27）的形状。
5. **第三个 reason 名而不是把"预算镜像"塞进 `tool-short`**：`MerchantHaul.blockedReason` 的契约是"可用量不足一趟时二选一"，
   预算镜像情形下**可用量是够的** ⇒ 塞进去会让日志自相矛盾（`frozenMilli=0` 却写 `tool-frozen`）。该支生产路径不可达（每次放行都在同一步烧掉工具）。

---

## 4. 偏离记录

1. **判据量是 `min(预算, 当刻可用量)`，不是纯"当刻可用量"**（题面的字面算式是后者）。原因见 §3-2：取较小者绝不放松且可证缺省中性；
   差别只在"轮内买到工具让可用量升过装配时预算"这一种情形，方向是 fail-closed（少运一趟，不会"运了却不烧"）。**如实记**。
2. **新增第三个归因名 `tool-budget-exhausted`**：题面只要求 `tool-frozen` / `tool-short` 分清；第三名是为不污染那两个政策名而加，不参与任何判据。
3. **`EconomySettlement` 只改注释**：G3-fix-1 那句时序论证是**错的**（与 `commitFreezes` 的实际位置相反），留着会继续误导后续修复 ⇒ 就地更正（不改任何代码语义）。
4. **未改 `hasCapacityAt` / 路线候选生成**：`hasCapacityAt`（`MarketSettlement.java:4686/5492`）只判"该格有没有跑商家户"（成员判据），
   本批不动它 ⇒ 路线照建、`select` 拦下 ⇒ 记 `LOGISTICS_CAPACITY` + 不成交（既有形状），避免把行为改动扩散到候选生成。

---

## 5. 真实命令与结果

```text
$ tools/mvn-lock.sh -q spotless:apply
[spotless exit=0]        # 跑完 git status --porcelain 只有本批 3 个 M 文件 ⇒ 未误改他文件

$ tools/mvn-lock.sh -DskipTests compile
[INFO] Reactor Summary for SimulatorMosire 0.1.0-SNAPSHOT:
[INFO] EconomySimos ....................................... SUCCESS [  3.156 s]
[INFO] SimosApp ........................................... SUCCESS [  2.525 s]
[INFO] BUILD SUCCESS     （16/16 模块 SUCCESS，Total time 10.237 s）
[compile exit=0]

# 字节新鲜度自检（防"编译到旧字节码"）：源码 09:09:44 < class 09:09:53/09:09:54
$ strings simos-economy/target/classes/.../MerchantHaul.class | grep -c tool-budget-exhausted          # 1
$ strings simos-economy/target/classes/.../MerchantCapacityPool.class | grep -c MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT  # 1
```

**未跑**：`test` / `verify` / `package` / 真实 world（按派单书由控制方下一轮统一验收）。

### 改动文件清单（3 个，全在 `simos-economy/src/main/**`）

| 文件 | 改动（行号 = 改后） |
|---|---|
| `.../time/MerchantCapacityPool.java` | 类注新增 G3-fix-2 口径（`:56-72`）；活视图字段（`:116-123`）；`EMPTY` 传 `null`（`:91`）；4/5 参重载改走 `assemble(..., null, null)`（`:159/192`）；6 参 = 生产入口带活视图（`:217-226`）；新私有 `assemble`（`:232`）；`select` 判据现读（`:671-696`）；`liveToolStockMilli/FrozenMilli`（`:779/787`）；`toolBlockReason`（`:808`）；`Entry` 三支计数 + `recordToolBlock`（`:477-490/509`）；车道 DEBUG 三支计数与 reason（`:751-762/822-845`）；`toolBlockedByReason`/`logToolBlocks`（`:893/911-948`）；INFO 汇总三支拆分（`:1058-1066`） |
| `.../time/MerchantHaul.java` | `:88-95` 新增 `TOOL_BUDGET_REASON = "tool-budget-exhausted"`（判据常量与算式一字未改） |
| `.../time/EconomySettlement.java` | `:1850-1860` 注释更正（删掉与代码顺序相反的错误时序论证；写明真实时点与落点）—— **无代码语义改动** |

---

## 6. 会改变数值行为的清单 + 缺省中性论证

1. **唯一改数值的改动**：承运池 `select` 的工具门槛判据量从 `本轮预算余量` 改为 `min(本轮预算余量, max(0, 当刻现货 − 当刻冻结))`
   （`MerchantCapacityPool.java:676-679`）。方向**只减不增**（取较小者）⇒ 只会**减少**承运/运费，不会造出新运力。
   - 影响面：`CarrierAllocation.choices`（跨格/跨区承运是否成立）→ `MARKET_FILL` / `CARRIER_FEE_PAID` / `MERCHANT_HAUL_RUN` /
     `MERCHANT_CAPACITY_POOL*` 读数；`MARKET_UNFILLED reason=LOGISTICS_CAPACITY` 方向**变多**（原本靠"被冻结的 tool"虚构出来的运力没了）。
   - 具名读数新增：`MERCHANT_CAPACITY_LANE_TRUNCATED` 的三支计数、`MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT`（逐户/日）、
     `MERCHANT_CAPACITY_POOL` 的三支计数 —— **纯日志**，不改判据。
2. **缺省中性（逐值不变）论证**：
   - **无跑商家户** ⇒ 池空 ⇒ `select` 根本不被调用（`hasCapacityAt` 恒 false ⇒ 跨格路线不建）⇒ 一字不改。
   - **无冻结**（冻结表无该户 tool 行）⇒ 装配时 `budget = 现货`；`commitFreezes` 不改 tool 冻结 ⇒ 选择点读数 = `现货 = 预算` ⇒
     `min` = 预算 ⇒ 与改前逐值相同。此后每笔放行都在同一步烧掉 1000 ⇒ 预算与账面可用量**同步下降**，恒等关系保持。
   - **无跨格承运**（route == null 的同格成交）⇒ `select` 不被调用 ⇒ 一字不改。
   - **4/5 参重载（夹具/纯状态读者）** ⇒ 无活视图 ⇒ 判据量 = 本轮预算余量 ⇒ 逐值退回改前（连 `laneBlockedReason` 的 `tool-short` 字面量都保持原样）。
3. **不改**：五条铁律；模块边界；唯一写口 `applyTransfer` / 唯一损耗落点 `consumeForLoss`（判据零改动）；`MerchantHaul` 常量与算式；
   任何 Codec/ChangeSet/载荷/命令/持久组件；`Map.copyOf`/`Set.copyOf` 零新增（I7：新日志按 `byHex`（`LinkedHashMap`）+ 已排序 `List<Entry>` 迭代）。
4. **受影响的硬编码字面量**：**无**（`TOOL_MILLI_PER_HAUL = 1_000` 一字未改；新增的只有 reason 字符串与事件字段名，不参与判据）。

---

## 7. 未完成 / 未验证（如实）

1. **未跑任何测试 / 未跑真实 world**（派单书明令）：本账本给出的只是**判据链** ——
   ① 现读的是市场轮写的那张活表、时点在 `commitFreezes` 之后（§2 自证）；② `select` 拦下 ⇒ `allocated<=0` ⇒ `return 0`（货款/运费/货腿都不走）。
   ★ 我**没有**实测 `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` 是否归零、"SHORT+fee"组合是否消失 —— 由控制方下一轮复测判定。
2. **"通过 select ⇒ 提交时必烧得成"这条不变量是静态论证**（§3-3）：同一户在该笔的 `select` 与 `settleHaulRuns` 之间只有
   ① 它自己的卖单成交（现货与冻结**等量**下降 ⇒ 可用量不变）② 它自己在同一笔的烧工具；买方侧损耗只动买方，而承运户住在发货格
   （≠ 买方格，`route != null` 时）⇒ 承运户 ≠ 买方。**未用真实世界逐笔核对**。
3. **轮内"买到工具"的户在本轮仍可能被拦**（§4-1 的已知偏离，方向 fail-closed）：未实测其发生频次。
4. **未验证日志量**：新增逐户 DEBUG 行的条数上界 = 池内被拦户 × 市场日（G3b 场景约 35 户 × 77 日量级），未真实跑过。
5. **未做**：多市场区世界、`--world=three-powers`、3600/3650 tick、`test`/`verify`/`package`；未 `git commit`。
