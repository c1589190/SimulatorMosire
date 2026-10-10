# A6 真实 world 验收账本 —— A5（服务按周期作废 + 日志对账 + 收益可见性）的三条主张 + 回归

> 角色：**验证 Agent**（起独立世界 / 读日志 / **未改任何代码、测试、文档、pom**；本账本与 `/tmp/a6-*` 是唯一写盘）。
> 日期：2026-10-10。判据来源：任务书三条主张 + A5 账本 `.superpowers/sdd/2026-10-10-a5-haul-cycle-expiry/impl-ledger.md`、
> A4 基线 `.superpowers/sdd/2026-10-10-a4-real-world-verify/impl-ledger.md`（A 计划设计书 v1.4 §3.1/§5/§6 T-H3）。
> HEAD：`5e74dc6c7003c5e1da9f6860b052e57f5e842087`（A5），跑前跑后 `git status --porcelain` = 0 行（仅本账本）。
> ★ 一句话结论：**主张②成立（0 漏行、0 漏报）；主张③成立（逐户逐币精确对上），但覆盖面有洞（见 §6-2）；
> 主张①分两半：「可卖集合按期归零 + 守恒精确」成立，「世界总现货归零」在 B 世界不成立（见 §3/§6-1）。**

## 0. 装置与 jar 身份

| 项 | 值 |
|---|---|
| 跑前实例检查 | `pgrep -af java \| grep -i simos` = 仅 pid **2081**（`/tmp/simos-shaded-NwvHbq.jar` md5 `b3be684d…`，`/proc/2081/fd/4` → 该 /tmp 副本）⇒ **无实例持有 target jar**，未停它、未覆盖共享 jar |
| 被测 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 **`9761e9048e5b33358245d5c4d30f588a`**，27,043,394 B，mtime `2026-10-10 18:39:05` |
| 构建 | `MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package` → **BUILD SUCCESS 16/16**（4.1 s 增量；跑前 `verify15.log` 18:37 已在 HEAD 做过全仓 `verify`） |
| jar↔源码 | `find . -name '*.java' -newer <jar>` = **0**；14 个模块 `src/main/java` 中 `-newer target/classes` = **0/14** ⇒ jar 字节 = HEAD 源码产物 |
| 运行期字节自证 | 两世界 `run-shaded.sh` 独占快照的 md5 **都等于** jar md5：A `/tmp/simos-shaded-qZKvQu.jar`、B `/tmp/simos-shaded-1FqLXl.jar` = `9761e904…` |
| 装置 A | 原生多市场区（`a6-setup1/2` = g3f 版 sed 到 9615→9915）：`SetMarketNumeraire(0,-1)->copper` =1 佐证、`govMarketMandate authorize` 同基线 **REJECTED**、`setup1 → 0→5 → setup2 → 5→10 → 10→360`；committed rev **11 / 20 / 21（与 A4 逐值同）**、head 21、tick 360；store **`/tmp/simos-a6-store-a`**；端口 GUI 9911 / MCP 9915 / 审批 9913；日志 `a6-runA.log` = **160,896,371 B / 454,413 行 / md5 `eae90eac…`** |
| 装置 B | 缺省 `--world=small-world`（`SetMarketNumeraire`=0），**0→360 一次推进**；committed rev **2**、tick 360；store **`/tmp/simos-a6-store-b`**；端口 GUI 9611 / MCP 9615 / 审批 9613；日志 `a6-runB.log` = **139,177,947 B / 378,040 行 / md5 `80f3e1d9…`** |
| 装置 B-ext（本轮加的判决性实验） | 复用 B 的 store（tick 360）重启 → **360→480**（rev 2→3）；日志 `a6-runB-ext.log` = 45,760,597 B / 123,058 行；dump `/tmp/a6-state-b480.json` |
| 日志档位 | 全部世界 `-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE`（与 A4 同档） |
| 状态读数 | `/tmp/a6-state-{a,b,b480}.json`（`g3-dump.py` 19 格 + gov + overview）；对照 A4 `/tmp/a4-state-{a,b}.json` |
| 纪律 | 一次一个 Maven、一次一个实例（B → A → B dump → B 360→480）；未 `git commit`；跑完 `pgrep -af a6-store` = 空，仅余他人 pid 2081 |

## 1. 主张① —— 服务按周期作废（"不可储存转卖"）

### 1.1 逐日对账（判据：0 违反）

**口径**：日志面无逐日现货读数，故用两个独立观测面：
① **运力池装配读数**（`MERCHANT_CAPACITY_HOUSEHOLD`，`priced=true` 行的 `capacityMilli`，服务成市格的 `capacityMilli` = `max(0, haul现货 − 冻结)`）——
每天都有（A/B 各 360 天 × 38 户），装配发生在**当日产出/作废之后、当日交付之前** ⇒ `期末现货(d) = Σcapacity(d) − delivered(d)`；
② **权威状态 dump**（`hex.goods.haul` = 该格 Σ 家户库存）。

- **恒等式 A（日递推）** `capacity(d) == capacity(d−1) − delivered(d−1) + produced(d) − expired(d)`
  - **A：359 天 / 违反 0 条**；**B：359 天 / 违反 1 条**（唯一一条 = day 241，见 §1.4 归因：不是守恒破裂，是观测量缺口）。
- **恒等式 B（累计）** `Σ净产 − Σ交付 − Σ作废 == 期末现货`
  - **A：360 天 / 违反 0 条**，期末 = `362,780 − 134,783 − 126,147` = **101,850** == dump `Σ hex goods.haul` = **101,850** ✓
  - **B：360 天 / 违反 120 条（全部由 day 241 那一处引起）**，但**期末**恒等式 `582,000 − 274,012 − 109,541` = **198,447** == dump = **198,447** ✓
  - **B-ext（cycle 4）**：`198,447 + 97,000 − 51,880 − 44,358` = **199,209** == dump480 = **199,209** ✓
- **三量同源自核**：`HAUL_SERVICE_SETTLED` 的 `producedMilli/expiredMilli` 与 INFO `HAUL_SERVICE_PRODUCED/EXPIRED`、`deliveredMilli` 与 `HAUL_SERVICE_DELIVERED` 逐腿求和 —— **A/B/ext 三份日志逐日不一致条数 = 0**。
- **抽样（边界日）**：

| 世界 | day | Σcap(day−1) | delivered(day−1) | produced(day) | expired(day) | Σcap(day) |
|---|---|---|---|---|---|---|
| A | 120 | 0 | 0 | 127,070 | 0 | **127,070** |
| A | 240 | **25,434** | 0 | 133,860 | **25,434** | **133,860** |
| A | 360 | **100,713** | 0 | 101,850 | **100,713** | **101,850** |
| B | 360 | **109,541** | 0 | 194,000 | **109,541** | **194,000** |
| B-ext | 480 | **44,358** | 0 | 97,000 | **44,358** | **97,000** |

（表中 `delivered(day−1)`：A day240 行 = delivered(239)=0；day-240 当日交付 819、day-120 当日交付 61,312。逐周期交付 A = 61,312 / 41,143 / 32,328（按 day-1//120+1 分组），
但**新周期产出可用的第一天含关账日当天** ⇒ 用 day-240 产出（133,860）减 day-360 作废（100,713）= 该批产出被交付 **33,147 = 819 + 32,328** ✓。）

### 1.2 周期边界之后现货是否归零 —— **可卖集合归零 ✓ / 世界总现货 ✗（仅 B）**

- **判据（可卖集合）**：`Σcap(边界日) == 当日净产`，即边界之后**进池的存货 == 本周期新产、无陈货**：
  A `127,070==127,070 / 133,860==133,860 / 101,850==101,850`；B `194,000==194,000 ×3`；B-ext `97,000==97,000` ⇒ **9/9 成立**。
  且边界前最后一天的 `Σcap` 被**等额**作废（25,434 / 100,713 / 109,541 / 44,358）。
- **但世界总现货在 B 不归零**：B 期末 198,447 中只有 101,487 在生产者格 0_0（其中 97,000 为当日新产、**5,249 是 day 240 的旧货**），
  另 **96,960** 落在 `-1_0`(50,961) / `-1_1`(45,999)。这 102,209 毫服务**跨过 day 360 与 day 480 两次边界都没被作废**——
  原因是服务货经 **`loan_repayment` 实物还债转移**离开了"关账 unit 主体家户"：
  `day180 hh-0_2-urban-landlord → hh-0_0-urban-rich_peasant haul=88,513`、
  `day240 → hh-0_0-urban-poor_peasant haul=5,249`、`day240 → hh-0_0-urban-landlord haul=91,685`、
  `day360 → hh--1_1/-1_0-rural-landlord haul=45,999/50,961`（B 全世界 **5 条** haul 转移；**A = 0 条**）。
  A 的期末 101,850 全部在生产者手上（0_0=97,000、0_2=4,850）⇒ **A 的世界总现货确实归零到"仅本周期新产"**。
- **这些逃逸存量能不能再卖？——结构上不能**：三个持有户（`hh--1_0/-1_1-rural-landlord`、`hh-0_0-urban-poor_peasant`）
  在 **480 天里 `MERCHANT_CAPACITY_HOUSEHOLD` 行数 = 0**（不是 `selectsMerchant` 的家户 ⇒ 永不进运力池 ⇒ 永不成为承运人）。
  ⇒ "不可储存**转卖**"在**效果上**成立（不能卖），在**字面上**不成立（没被销毁）。

### 1.3 有没有"上一周期的服务在下一周期被卖掉"的痕迹 —— **没有**

- cycle 4（B-ext）全部 70 条 `HAUL_SERVICE_DELIVERED`**只来自一个户**：`hh-0_0-urban-landlord`（Σ51,880）；
  那 102,209 陈旧存量所在户 **480 天内一次都没出现在池里**（`Σcap` 非零户逐日只有 `hh-0_0-urban-landlord`、`hh-0_2-urban-landlord`）。
- 机制侧证明：每个周期内 `Σcap(d)` 单调不增且起点 == 本周期净产（§1.2）⇒ 该周期任何一次交付都只能出自本周期产出。
- A4 的旧口径对照：A4 里陈货**确实存在**（期末 227,997）但**本来也没被卖掉**——A 的 cycle-3 交付 33,147 **远小于**当期新产 133,860
  （故 A4/A6 的交付逐笔相同，见 §3）。⇒ A5 修的是"存量属性"，不是"可观测行为"。

### 1.4 B 唯一那条"违反"的归因（观测量缺口，不是守恒破裂）

day 241 `Σcap` 比递推预期少 **5,249**：逐户差分显示 `hh-0_2-urban-landlord` 当日**整体退出观测集**（capacity 行消失），
其货在 **day 240 经 `loan_repayment` 转给 `hh-0_0-urban-landlord`(91,685) 与 `hh-0_0-urban-poor_peasant`(5,249)**；
dump 期末 198,447 把该 5,249 算在 `0_0/classes[11]` 里 ⇒ **守恒成立，是"池名册"这一观测面天然看不到非商家户的存货**。
⇒ 口径警告（给后续验收）：**"池读数"不是逐日现货的完备见证**，用它做逐日对账会在"持货户退出商家名册"的日子产生**假违反**。

## 2. 主张② —— `HAUL_SERVICE_SETTLED` 每个"服务成市"日都对账（判据：每个服务成市日一条）

| 世界 | 服务成市日（= `MERCHANT_CAPACITY_POOL` 日 = `MARKET_FILL` 日） | SETTLED 行数 / 日集 | 有交付日 | 有交付却无 SETTLED 行 | 三量逐日不一致 |
|---|---|---|---|---|---|
| A | **132** | **132 / 132 同集** | 34 | **0** | **0** |
| B | **81** | **81 / 81 同集** | 43 | **0** | **0** |
| B-ext | **28** | **28 / 28 同集** | 27 | **0** | **0** |

- **含 `trades=0` 的日子**：A **98** 行 `trades=0`（其中 97 行三量全 0）、B **48** 行（38 行三量全 0）、B-ext 1 行（0 行三量全 0）⇒ "什么都没发生的日子也有一条"成立。
- **抽样**：`day=3 producedMilli=0 deliveredMilli=0 expiredMilli=0 trades=0 paidByCurrency={} providers=38`（B，三量全 0 仍刷）。
- **与 A4 的漏报对照（A4 §5-2：B 有交付 43 天、只有 33 行、Σ读数 182,629 vs 实际交付 274,012，**少报 91,383 = 33.4%**）**：
  A6 的 Σ`SETTLED.deliveredMilli` **274,012（B）/ 134,783（A）== Σ `HAUL_SERVICE_DELIVERED` 逐腿 274,012 / 134,783** ⇒ **漏报 = 0**。
  A4-A 是 34/34（本无漏报）；A6-A 变成 132/132（开市日全覆盖，多出的 98 行都是 `trades=0` 的对账行）。

## 3. 主张③ —— 跑商收益在标准读数面可见（判据：不空 + 能对上）

- **不再为空**（对照 A4：两个跑商产业都 `{}`、`lastCycleNetMilli=-104`）：

| 世界 | 产业（operator） | A4 `lastCycleRevenueByCurrency` / net | A6 | A6 `cycleRevenueByCurrency`（周期实收，逐户） |
|---|---|---|---|---|
| A | `trade@0_0`（hh-0_0-urban-landlord） | `{}` / −104 | **`{copper=22, silver=145}` / +41** | cycle3 收款户 = hh-0_0-urban-landlord `{copper=22, silver=145}` ✓ |
| A | `trade@0_2`（hh-0_2-urban-landlord） | `{}` / −104 | `{}` / −104（本周期确实零收） | cycle3 无该户收款 ✓ |
| B | `trade@0_0`（hh-0_0-urban-landlord） | `{}` / −104 | **`{silver=402}` / +298** | cycle3 收款户 = hh-0_0-urban-landlord `{silver=402}` ✓ |
| B | `trade@0_2`（hh-0_2-urban-landlord） | `{}` / −104 | **`{silver=2}` / −102** | cycle3 收款户 = hh-0_2-urban-landlord `{silver=2}` ✓ |

  **逐值对照**：`HAUL_SERVICE_PAID` 按 `toHousehold` 分币求和（cycle3）与 dump `industries[].condition.lastCycleRevenueByCurrency` **四行全等**；
  `lastCycleNetMilli` = 本币收入 − 成本 104（A：145−104=41，铜 22 不计；B：402−104=298、2−104=−102）⇒ 与读数栏口径一致。
- **当天/当周期金额**：cycle1/2/3 的 `paidByCurrency` Σ = A `{silver=236} / {silver=93,copper=139} / {copper=22,silver=145}`（Σ635 毫银，与 A4 同）；
  B `{silver=34} / {silver=482} / `**`{silver=404}`**；除"最后一周期"外，早期周期已被下一周期覆盖（该栏语义 = 上一关账周期）。
- **覆盖面洞（见 §6-2）**：B 的 cycle2 482 毫中有 **444（92%）** 付给 `hh-0_0-urban-rich_peasant` —— 该户**没有产出 haul 的 unit**
  ⇒ 这笔钱在**任何** `industries[].condition` 里都读不到（`HAUL_SERVICE_REVENUE_ATTRIBUTION` 具名 DEBUG **6 条**，reason=`household-has-no-haul-producing-unit`；A = 0 条）。
  全世界服务实收 **920 毫银**，期末读数面可见 = **404**（只有最后一周期那两笔）。⇒ "跑商收益可见"对**拥有跑商产业的家户**成立，
  对**持有服务货的非生产者家户**不成立。

## 4. 回归（A/B vs A4 基线）

| 项 | A4-A → A6-A | A4-B → A6-B |
|---|---|---|
| `advance` | rev 11/20/21、head 21、tick 360 → **逐值同** | rev 2、tick 360 → **同** |
| `Exception`/`ERROR`/`IllegalStateException`/`at io.mosire` | 0/0/0/0 → **0/0/0/0** | 0/0/0/0 → **0/0/0/0** |
| WARN | 2,022 → **2,022，构成逐项同**（`MARKET_SUBJECT_COLLECTIVE_UNRESOLVED` 1,994 + `ACCOUNT_SUBJECT_UNRESOLVED` 17 + `POPULATION_SETTLE_REMAINDERS_CLEANED` 11） | 12 → **12，全部 `POPULATION_SETTLE_REMAINDERS_CLEANED`** |
| 人口 Σ / 粮 Σ | 4,927 / 1,090,540,229 → **逐值同** | 4,928 / 3,848,788,464 → **逐值同** |
| 债/信用 principal、count | 4,469,636 / 1,933 → **逐值同**；`credit==debt` 是；`Σ(byUnit)==标量` 38/38 | 18,596 / 1,064 → **逐值同**；同上 |
| 货币守恒 `circulation==baseMoney` | True（copper 100,000/silver 273,200/token 5,000）→ **同值** | True（copper/silver）→ **同值** |
| `MARKET_FILL`（跨格成交代理） | 1,716→1,669 → **1,669（Δ=0）** | 7,055→6,937 → **6,937（Δ=0）** |
| `MARKET_CREDIT_FILL` | 2,878 → **2,878** | 6,032 → **6,032** |
| `MERCHANT_HAUL_RUN` / PAID / DELIVERED | 161 / 145 / 161 → **相同**；Σ服务 134,783 → **134,783** | 141 / 121 / 141 → **相同**；Σ服务 274,012 → **274,012** |
| 状态 dump 逐叶差异（递归全档） | **仅 10 处**：`0_0/0_2 goods.haul`（227,997 → 101,850）+ `trade@0_*` 的 `lastCycleRevenueByCurrency`/`lastCycleNetMilli`（含 `units[0]` 子项） | **仅 10 处**：`0_0 goods.haul` + `trade@0_*` 条件读数（同类） |
| 作废总量 `market-haul-service-expired` | —— | —— |
| 一致性故障 | `HAUL_SERVICE_DELIVERY_FAULT`=0、`HAUL_SERVICE_EXPIRY_FAULT`=0（两世界） | 同 |

- **`haul` 作废总量**：A **126,147**（day240 25,434 + day360 100,713）；B **109,541**（day360）；B-ext 另 **44,358**（day480）。
  作废账户字面量 = `market-haul-service-expired`（与成交消耗 `market-haul-service` 分开）。
- **跨格成交变化方向与幅度**：**零变化**（A 1,669→1,669、B 6,937→6,937）；承运趟/钱腿/货腿/运费 Σ 也全部同 A4。
- **★ 结论**：A5 在**两个真实世界**里对状态的影响**只有 10 个叶子**（服务存量 + 跑商产业收益读数），其余逐值不变；同时**行为面无差异**。

## 5. 为什么"作废"改了存量却不改行为（解释，供后续批次判断判据强度）

A 的 cycle-3 交付 = 33,147，而 day-240 净产 = 133,860 ⇒ 即便 A4 里那 25,434 陈货可卖，也**没被需要**（需求/半径/货单结构决定的）；
B 同理（陈旧存量持有户根本不在承运人池）。⇒ 用"跨格成交是否变化"当 A5 的判据是**无判别力**的；有判别力的是存量式与守恒式。

## 6. 新发现缺陷（只报不改）

1. **周期作废的作用域留了一个"实物还债"逃逸口，B 世界里世界总现货不归零（设计书 §3.1/I-H3 的"不可储存"字面落空）**：
   `loan_repayment` 的 `goods={…, haul=…}` 会把服务货转给**不是关账 unit 主体**的家户；这些货跨边界不被作废。
   实测 B：**102,209 毫服务**（5,249 来自 day 240、96,960 于 day 360 转出）跨过 day 360 + day 480 两次边界，占期末总现货 **51%**。
   缓解事实：三个持有户 480 天内 **0 条池行** ⇒ 结构上不可转卖（效果成立）。
   ★ 建议（不改）：作废判据从"关账 unit 的主体家户"扩到"**任何持有 haul 的家户**"或按商品做全局轮末清扫——否则一旦持有户被选回跑商（本仓确有 `pureMerchant` 状态机），陈货立刻变成可卖运力。
2. **`lastCycleRevenueByCurrency` 的覆盖面不是"全部服务实收"**：只有"名下产出 haul 的 unit"的家户才可见。
   B 的 cycle2 **444/482（92%）**付给了无跑商产业的 `hh-0_0-urban-rich_peasant` ⇒ 读数面**看不到**（6 条具名 DEBUG 是唯一痕迹）；
   全程 920 毫银实收，期末读数面可见 404。⇒ T-H3 的"可见"应写成"**对拥有跑商产业的家户可见**"，或给未归属金额一个读出口。
3. **逐日对账的唯一日志见证（运力池名册）不完备**：服务成市格的 `capacityMilli` 只覆盖 `selectsMerchant` 家户，
   持货户一旦退出名册，`Σcap` 会"无交付地下跌"（B day 241 的 5,249 假违反）。本轮的恒等式判据因此要按 dump 收口；
   若要逐日审计，需要一个不依赖名册的逐日服务库存读数（当前没有）。
4. **`HAUL_SERVICE_SETTLED` 的开市日集合 = `MERCHANT_CAPACITY_POOL` 日集合**这条依赖没有断言守卫：若将来市场轮改成"非服务格也开市"或
   `haulServiceMarketHexes` 判据变化，行数会随之变而无人报警（本轮 A/B/ext 三份日志恰好同集，属**实测**而非结构性保证）。
5. **B 世界 day-360 转出的 96,960 是"当周期新产经实物还债即刻离场"**：它不是"陈货"，但它**在下一周期开始前就已不在产者手上**
   —— 说明"谁持有服务货"与"谁产服务"在真实世界里会分叉，任何按产者归集的机制（作废、收益归集）都会漏掉这条路径。

## 7. 未验证 / 构造不出（如实）

1. **未跑 `test`/`verify`/SpotBugs/前端门禁**（本轮只做真实 world 验证；跑前 18:37 已有人在 HEAD 跑过全仓 `verify`，但**那不是本轮的证据**，未引用其数字）。
2. **未做变异自证**（无代码改动；判据强度靠"0→360 两世界 + A4 基线逐叶对照"提供）。
3. **未跑其他世界**：`three-powers` / `v17levant` / `corridor`；未跑 3600 tick；未跑 FX/GM 家户补丁世界。
4. **未构造"持货户又被选回跑商 ⇒ 陈货重新可见"**：480 天里那三户 0 池行（`pureMerchant` 状态机未在该窗口内选中它们）。
5. **未构造"冻结量 > 0"**：两世界服务冻结恒 0（A4 已论证服务不进订单簿），故"仅剩冻结量"这一半判据无样本。
6. **未做两跑确定性（N-H3）**：staged（A 0→5→10→360）与单段推进的等价性沿用 A4/M0.1 的"路径无关"结论，本轮未重复自证；
   B-ext 是"复用落盘 store 再推进"，与"一次 0→480"是否等价未单独对照（只对照了守恒式）。
7. **未解释 A 世界为何 0 条 haul 实物还债**（B 有 5 条）：只如实记现象，未追债结构差异。
8. **未验证**：`HAUL_SERVICE_EXPIRED` 只在"真的发生作废"时打（total>0）这一条与 `HAUL_SERVICE_SETTLED` 的 `expiredMilli` 同源同值已核，
   但"两行别相加"的纪律本轮未在测试面固化。

## 8. 复现命令

```bash
cd /home/cna/SimulatorMosire
pgrep -af java | grep -i simos                       # 只应有他人 pid 2081（jar 在 /tmp，非 target）
MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar     # 9761e9048e5b33358245d5c4d30f588a
find . -name '*.java' -newer simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar | wc -l   # 0
bash /tmp/a6-runB.sh   ; bash /tmp/a6-kill.sh        # B 0→360（9611/9615/9613, /tmp/simos-a6-store-b）
bash /tmp/a6-runA.sh   ; bash /tmp/a6-kill.sh        # A 0→5→10→360（9911/9915/9913, /tmp/simos-a6-store-a）
bash /tmp/a6-dumpB.sh  ; bash /tmp/a6-kill.sh        # B tick360 只读 dump
bash /tmp/a6-extB.sh   ; bash /tmp/a6-kill.sh        # B 360→480（判定"陈货是否被卖"）
python3 /tmp/a6-daily.py /tmp/a6-runA.log            # 逐日两恒等式（A：0/0 违反）
python3 /tmp/g3d-health.py /tmp/a4-state-a.json /tmp/a6-state-a.json
```

**产物**：日志 `/tmp/a6-run{A,B}.log`、`/tmp/a6-runB-ext.log`、`/tmp/a6-runB2.log`；读数 `/tmp/a6-state-{a,b,b480}.json`；
store `/tmp/simos-a6-store-{a,b}`；推进 `/tmp/a6-adv*`；脚本 `/tmp/a6-*.sh`、`/tmp/a6-daily.py`。
