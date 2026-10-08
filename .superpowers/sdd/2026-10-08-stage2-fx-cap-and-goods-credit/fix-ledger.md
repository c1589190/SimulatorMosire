# 阶段 2 收尾批（改动 A + B）实现与证据账本

> 主题：**取消政府外汇窗口的储备上限**（A）+ **商品信用受币种一致性约束**（B）
> 日期：2026-10-09 ｜ 责任区：`simos-economy` / `simos-economy-api` 生产代码（不改测试、不跑 test/verify/package、不 commit）
> 用户原话（最高权威，2026-10-08）：
> 「什么叫有仓库上限值，取消掉；什么叫没有发到民间？不能给政府上大规模对民间采购需求，乃至搞粮仓囤粮吗？
> 商品信用通道不受货币种类限制，那就加默认限制啊？怎么着，你海关还能明晃晃同意一个不明人带一大堆本国货币去海外？」
> ★ 本批**只**做「取消上限」+「默认限制」；用户举的"海关查验/身份/罚没"是阶段 3 口岸政策的形状，**不在本批**（范围纪律，未自行扩大）。

---

## 0. 交付物与编译

- 改动文件（5 个，全是 main；**零测试文件**）：
  | 文件 | 行 | 改了什么 |
  |---|---|---|
  | `simos-economy/.../time/GovFxWindow.java` | `:53` `:56` `:66` `:189` `:231` | 删 `DEFAULT_RESERVE_CAP_PER_MILLE=500`、加 `UNBOUNDED_RESERVE_CAP_BASE_MILLI` + 两个判定/标签口；买入容量不再被上限封顶；超容量拒因改 `INSUFFICIENT_FUNDS` |
  | `simos-economy/.../time/FxRoundInput.java` | `:99` `:106` `:158` `:176` `:190` | 装配窗口时 `R_max` 恒填 `UNBOUNDED`；**删除**发行量索引 `issuanceByGovernmentCurrency` 与千分比算式 |
  | `simos-economy/.../time/FxSettlement.java` | `:145` `:161` | 停做日志/明细里的 `cap=` 改用 `GovFxWindow.reserveCapLabel(...)`（不再打印 19 位数字） |
  | `simos-economy/.../time/MarketSettlement.java` | `:2128` | 借实物腿加币种一致性具名拒（复用 `rejectCurrencyMismatch`，`leg=goods-credit`） |
  | `simos-economy-api/.../api/fx/FxRejectReason.java` | `:15-22` | `RESERVE_CAP` 标注**已退役**（保留 wire，新代码不得再发） |
- 编译（唯一跑的 Maven，`tools/mvn-lock.sh` 串行化；跑前 `pgrep -af classworlds.launcher` = 无命中）：
  `tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` → **exit 0**（编译前 `rm -rf simos-economy/target/classes` 防 §七 增量编译假绿；补日志/文案后又跑，仍 exit 0）。
- **探针在最终字节上复跑**（不是中间态）：B 30 tick → `goodsCreditLegRejects=610 / driftCrossDebtDelta=0 / driftSameDebtDelta=4893 /
  creditGoodsSum=28`，**0 项失败**；S 40 tick → 0 窗口 / 0 异币具名拒（不可达性）；A 240 tick → 见 A.2。
  ★ A 的探针在 **60 tick** 上 `windowBuy=0` ⇒ `phase=after` 的那条断言会红：窗口买入**首现于 day≈200**，
  判据 horizon 取 240 tick（60 tick 的红是探针取的窗口太短，不是代码没生效；`A-after-FINAL.log` 留痕）。

---

## A. 取消政府外汇窗口的储备上限

### A.1 `R_max` 取消后的语义（含兼容取舍）——**本批的实现取舍**

1. **旧口径**：`R_max = 该 GOV 对该 base 币的累计发行量 × 500‰`（`GovFxWindow.DEFAULT_RESERVE_CAP_PER_MILLE=500`）⇒
   `buyCapacity = min(max(0, R_max − 储备), 国库可付 quote 能买多少)`。three-powers 实测：`R_max = 100,000×500‰ = 50,000 < 国库实有 100,000` ⇒ 买入侧自第一轮起就 `buyBlocked = RESERVE_CAP`。
2. **新口径**：`R_max` 变成**具名"无上限"**：`GovFxWindow.UNBOUNDED_RESERVE_CAP_BASE_MILLI = Long.MAX_VALUE`。
   - 买入容量 = `floor(国库可付 quote × 1000 ÷ bidP)`（**只由钱封顶**）；为 0 ⇒ 具名 `INSUFFICIENT_FUNDS`。
   - **保留的两条底线一字未动**：① 储备为 0 ⇒ `sellCapacity = 0` ⇒ 停卖 `RESERVE_EXHAUSTED`（不许卖空，容量恒 ≤ 储备，I21 后半条）；② 每笔买入必须有对手方 `NO_COUNTERPARTY`（I21 前半条，`FxSettlement.attributeResidue` 的窗口残量归因）。
   - ⇒ 不变量 **I21 不变**（原注释写"三项约束"已改为"两项 + 上限取消"，见类注 `:12-22`）。
3. **不让"500‰ 但被忽略"的死常量存在**：`DEFAULT_RESERVE_CAP_PER_MILLE` **删除**；发行量索引 `issuanceByGovernmentCurrency`（原 `FxRoundInput` 私有方法，唯一读者就是这个算式）**删除**；`moneyIssuances` 形参**保留但不再是任何算式的输入**（签名冻结：旧调用方/旧夹具仍在传它，见下）。
4. **旧载荷/旧构造点的退化语义（写清）**：
   - `FxRoundInput.Window.reserveCapBaseMilli` 与 `GovFxWindow.Quote.reserveCapBaseMilli` **保留为读数位**（旧构造点仍可传它）；生产装配恒传 `UNBOUNDED`。
   - 旧构造点传**有限值** ⇒ **语义退化为"只作留痕，不再封顶买入"**。理由：用户已裁定取消这条政策线，一条自定上限在窗口上不再有强制力；要重新限流请走"吞吐/额度"政策通道（那是另一条政策，不在本批）。
   - `FxRoundInput.of(Map, Map)` / `of(Map, Map, Map)` 两个签名**逐字保留**（`-am` 编译面 + 旧夹具）；`moneyIssuances` 的 Javadoc 已写明"保留形参、不参与容量"。
5. **拒因枚举的取舍**：`FxRejectReason.RESERVE_CAP` **保留**（`wire()="reserve_cap"` 是发布后不改的日志/读数契约，旧档旧日志仍要可解释），但 Javadoc 标注**已退役、新代码不得再发**。⇒ 生产路径里 `RESERVE_CAP` 的**发射点 = 0**（唯一两处：`quote()` 的 `buyBlocked`、`requestWindowBuy` 的超容量分支，前者已删、后者改 `INSUFFICIENT_FUNDS`）。

### A.2 证据（真 Shell.start + 真创世 + 真 GM 命令 + 真推进；**零 `core.register`**）

探针：`/tmp/s2-probe/S2Probe.java`（不进仓库、不进 `src/test`）；世界 = `three-powers`（3 区/3 GOV/3 币/37 格，创世即落持久区表）；
命令 = `economy.SetOfficialRate` ×3（铜区 copper/silver 900/950；金区 gold/silver 1100/1150；**铜区补一条 gold/copper 1000/1050** = 国库无 gold 储备 ⇒ 卖出侧见底 + 买入侧无对手方，两条底线各要一个具名现场）；
推进 = `Shell.advanceAndDrain` 逐日。日志走 **in-process log4j2 appender**（按事件名聚合，不落 150MB 文本）。

**对照读数（改动前 = `git show HEAD:` 源码现编的 5 个类覆盖在 classpath 最前；同一份探针、同一份载荷）**

| 读数（INFO 事件聚合） | 改动前（60 tick） | 改动前（**240 tick**） | 改动后（60 tick） | 改动后（**240 tick**） |
|---|---|---|---|---|
| `FX_WINDOW_BUY_BLOCKED reason=reserve_cap` | **78**（3 窗口 × 26 轮） | **312**（3 窗口 × 104 轮） | **0** | **0** |
| 窗口买入成交（`FX_FILL venue=gov_window` 且 `buyer=国库`） | **0** | **0** | 0 | **8** |
| 窗口卖出成交（`seller=国库`） | 12 | **24** | 12 | **34** |
| `reserve_exhausted`（储备见底停卖） | 26 | 104 | 26 | 106 |
| `no_counterparty` 具名拒 | 50 | 498 | 128 | 800 |
| `FX_ROUND` / 成交合计 | 26 / 12 | 104 / 24 | 26 / 12 | 106 / 44 |

- ★ **"双向都能成交"在 240 tick 的同 horizon 对照上成立**：改动后 `windowBuy=8`、`windowSell=34`（买入首现于 day≈200：
  家户持外币而本币花光 ⇒ 折价卖回窗口 —— 正是"外币花不出去"的那条通道，改动前它被上限死封：同 horizon `windowBuy=0`、买入停做 312 次）。
  卖出 24→34 的增量也来自这条闭环：窗口买回的外币重新回到家户手里，才有下一轮可卖。
- ★ **改动前 = 只卖不买（不是"只买不卖"）**：这是一处**与设计书 §11 措辞不符的实测更正** ——
  `doc §11` 写"窗口自第一轮起处于储备触顶 ⇒ 只买不卖（只吸收外币）"；实测 `windowBuyFills=0`、`windowSellFills=12`，
  且国库 copper `100,000 → 92,384`（卖出 7,616 毫）、silver `100,000 → 105,318`（收银）⇒ **是"只卖不买"**。
  根因判断不变（`R_max=50,000 < 储备 100,000` ⇒ 买入侧自第一轮被封），但**方向要按实测读**：封住的是"家户拿外币换本币"那条腿。
  ⇒ 本账本以实测为准（§四"机制性描述一律回代码核"的同族纪律）。
- 两条底线的具名现场（两个 phase 都在）：`FX_WINDOW_SELL_BLOCKED reason=reserve_exhausted`（`reserveBaseMilli=0`，gold/copper 窗口）、
  `FX_REJECTED reason=no_counterparty`（该窗口买入侧有容量可付但无对手方）。⇒ "不许卖空"与"每笔买入必须有对手方"未被本批放松。

---

## B. 商品信用（借实物）受币种一致性约束

### B.1 判据与复用点

- **判据**：`buy.currency`（= 买方所在格市场的 `numeraire`，即法定币；`BuySlot.currency`）**≠** `sell.receiveCurrency`（= 卖方所在格市场的 `numeraire`）⇒ **具名拒该笔商品信用**。
- **复用点**：`MarketSettlement.rejectCurrencyMismatch(ctx, buy, sell, quantity, "goods-credit")` —— A2a/A2b 已落的**唯一拼写点**（现金腿 `executeTrade:leg=cash`、货币信用腿 `moneyCreditForBuy:leg=money-credit`），本批**没有另写第二套比较**。
  该方法的 Javadoc 已从"两条腿"改写为"三条腿 + 调用方处置不由本方法规定"（`:3800-3845`）。
- **不新增静默路径**：不等 ⇒ 买卖两侧各留 `MarketUnfilledReason.CURRENCY_MISMATCH`（`sellerReason` 里优先于市场性归因）+ 一条 INFO `MARKET_CURRENCY_MISMATCH_REJECTED`（§一.9：业务拒绝 = INFO + 具名 reason）；该笔**不扣配额、不减剩余、不铸货腿、不建债务合同**。
- **★ 处置（有意的偏离，写清）**：**跳过该卖方、继续找同币候选**（`skippedForThisBuyer.add(sell); continue;`），**不是** `break`。
  理由：商品信用的候选卖方是"可借量降序"的**一串**，而现金/货币信用腿当轮的卖方是**唯一那个**最优候选；若一个异币候选就把本买方整条实物信用关死，那超出用户要的"**默认限制**"，且实测有实质后果——第一版 `break` 形态下，漂移格买方能否借到货**取决于排序运气**（同刻既有 64 笔跨币具名拒、也有 187,666 毫同币借入），且在混合币种区里会把同币卖方一并放弃。
  三条腿共享的是**判据与日志形态**，不是"之后怎么走"（已写进 `rejectCurrencyMismatch` 的注）。

### B.2 证据（真命令造场景 + 真推进；探针同上）

- 场景：`three-powers` 创世后，用**真 GM 命令** `economy.SetMarketNumeraire` 把银区成员格 **`(1,0)`**（9 户，非锚格、有市场）的计价币从 silver 改成 copper
  ⇒ 同区（银区）内出现"**买方币=铜 / 卖方收款币=银**"的一对（持久区表未动，仍走 `MarketPersistentZones` 的成员格）。
- 真推进 30 tick（无任何官方汇率 ⇒ 只有商品面），对照读数：

| 读数 | 改动前（HEAD，同一探针） | 改动后 |
|---|---|---|
| `MARKET_CURRENCY_MISMATCH_REJECTED leg=goods-credit` | **0** | **610** |
| 漂移格债务人新增**跨币**实物债本金（债权人不在漂移格 ⇒ 收款币=银） | **10,919** | **0** |
| 漂移格债务人新增**同币**实物债本金（债权人也在漂移格 ⇒ 收款币=铜） | 374 | **4,893** |
| 全市场 `creditGoods` 求和（MARKET 轮汇总） | 458 | 28 |
| 全市场 `creditMoney` 求和 | 1,494 | 3,180 |
| `leg=cash` / `leg=money-credit` 具名拒（旁证：另两条腿早就堵着） | 114,116 / 388 | 118,016 / 112 |

- 拒的样本（逐字，来自探针捕获的 INFO 行）：
  `day=3 leg=goods-credit reason=currency_mismatch commodity=grain buyerPays=copper sellerReceives=silver buyer=HOUSEHOLD:hh-1_0-rural-landlord seller=HOUSEHOLD:hh-3_0-urban-middle_peasant quantity=16345`
- ★ **负向对照成立**：同币（同格）借实物**没有被关死**——同币实物债增量 374 → **4,893**（同一轮里既有具名拒、又有同币成交）；
  货币信用腿反而上升（1,494 → 3,180，异币卖方被拒后买方转向"借本币"这条合法路径）。
- ★ **如实记的数值行为变化**：全市场 `creditGoods` 458 → 28（跨币借实物被堵后总量下降；被拒的那部分需求转向同币借实物与借货币）。漂移格世界本身脆弱（见 §D）。

---

## C. 旧世界不动（small-world，单币）

- **结论**：本批的两处改动在 single-currency 世界里**结构性不可达**，因此旧世界逐值不受影响。证据（同一份探针、**最终代码**真跑 60 tick）：
  - `FX_WINDOWS_ASSEMBLED` / `FX_WINDOW_*_BLOCKED` / `FX_REJECTED` **全 0**（small-world 没有任何官方汇率 ⇒ `FxRoundInput` 窗口数 = 0 ⇒ `GovFxWindow.quote` 一次都不被调用）；
  - `MARKET_CURRENCY_MISMATCH_REJECTED` **逐腿全 0**（`leg=cash / money-credit / goods-credit` 都是 0）⇒ 新加的守护条件在旧世界里**一次都没触发**（触发才写状态，不触发 = 逐字同源）。
  - 探针断言：`A 创世…`/`S small-world 区表为空`/`S 无任何外汇窗口` 全绿（`S-after-final.log`）。
- ★★ **必须如实记的一条观察（方法论更正）**：**small-world 的逐 revision 变更集摘要跨进程不稳定** ——
  同一份代码、同一条命令 `./run.sh S 40` 连跑四次给出 `b46b217c…`、`f3137346…`、`b46b217c…`、`b46b217c…`（两个稳定值，比例 3:1）。
  ⇒ ① 本批**撤回**"用 digest 相等证明旧世界不动"的做法（它一开始两次相等是运气）；② 这也说明**当前 main 里存在与本批无关的跨进程非确定性**（未定位，见 §D 未完成项）——**F7"同代码跑两遍逐值相同"在当前 main 上需要重新核**。
  本节改用**不可达性**证明（上一条），它不依赖任何随机性。

---

## D. 未完成 / 未验证 / 发现（诚实边界）

1. **未跑** `test` / `verify` / `package`（任务书硬要求）：本批**没有**任何既有回归网的保护，只过了编译门。测试与变异自证留给测试 Agent。
2. **既有测试是否引用被删符号**：已静态核对 `*/src/test/**` —— `DEFAULT_RESERVE_CAP_PER_MILLE` / `reserveCapBaseMilli` / `RESERVE_CAP` / `FxRoundInput.Window` 构造点 **0 命中**（`grep -rn` 实测），故本批不会让测试编译不过；但这只是静态核对，**未跑测试**。
3. **未定位的跨进程非确定性**（§C）：small-world 逐 revision 变更集摘要不稳。未定位到具体来源（候选：`Map.of/Set.of` 的 per-JVM salt、并行分区合并序、或时间派生量）；**不是本批引入**（改动前后的 run.sh 各自都能给出两个值）。建议由控制方另开一项调查任务。
4. **`break` 形态的实测代价已记**（§B.1）——它是本批被我改掉的第一版；若控制方认为应当与现金腿严格同形，请裁定，我把 `continue` 换回 `break` 并重跑探针。
5. **撞到的既有崩溃（非本批引入）**：漂移世界里 `unit=unit-weave@1_0-HOUSEHOLD-weave@1_0 operator=HOUSEHOLD:weave@1_0` ⇒
   `IllegalStateException: 市场参与者无法解析到任何家户`（`MarketSettlement.participantsFor`）。**改动前的 HEAD 同样崩**（240 tick 对照跑复现，同一 unit/同一 operator），
   故判定为**既有隐患**：生产单位（`unit-weave@1_0`）的经营者家户消失（死亡/迁移）后，单位行没被一起拆 ⇒ 下一轮市场解析参与者就抛。
   ⇒ 本批的 B 探针因此把 tick 数压到 30（两侧同一时点可比）；**该隐患不在本批范围**，交控制方另开任务。
6. **未做（范围纪律，按 §一.8 三级处置上报）**：用户举的"海关不许不明人带大量本币出境"= 阶段 3 口岸政策（查验率 / 身份 / 罚没）**本批未做**；本批只兑"默认限制"。
   另：本批**未**采用"买方**可支付币种**"这一维（判据用的是**法定币**，与现金腿/货币信用腿同一口径）；若要把"持可支付币种即可借实物"也放进来，那是**第二套比较**，需要用户/控制方裁定（本账本按现状：同口径优先，"别另写第二套比较"）。
7. **未做**：第三条窗口（gold/copper，国库无 gold 储备）是**探针为验"底线仍具名"而设的真命令载荷**，不是生产默认配置；窗口仍是世界级的（不分区），与 B4 的既有边界一致。
8. 探针与日志：`/tmp/s2-probe/`（`S2Probe.java`；`A-before.log` = 改动前 60 tick 对照；`A-after240.log` = 改动后 240 tick；
   `A-before240.log` = 改动前 240 tick 对照（`reserveCapBlocks=312`、`windowBuyFills=0`、`windowSellFills=24`）；
   `B-before-30.log` / `B-after-30.log` = B 的 30 tick 成对读数；`S-after-final.log` = 旧世界不可达性；
   `run.sh`（当前代码）/ `run-overlay.sh`（改动前 = HEAD 5 个类覆盖）/ `run-before.sh`（shaded jar，仅证 jar==HEAD）；
   覆盖层源/类在 `/tmp/s2-before-src`、`/tmp/s2-before-classes`）。**全部在 /tmp，不进仓库**。
9. **格式与提交**：本批**未跑** `spotless:apply`（任务书限定只跑 compile；已手工把 `git diff` 全部行控制在 ≤100 列）。
   控制方提交前请按 §七 跑 `tools/mvn-lock.sh -q spotless:apply`，**并重跑一次 compile**（格式改动要重新入编译面）。本批**不 commit**。
