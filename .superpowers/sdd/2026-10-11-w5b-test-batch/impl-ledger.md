# W5b 测试批 —— 实现/测试架构账本（2026-10-11）

> 责任区：把 `src/test` 迁到 **W5（铸币入市）** 的新口径，让测试树编译并尽量实跑绿。
> 权威：设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.14** §2 S-3、§4.1「铸币入口」行、§6.1 W5 行、§6.3 判据清单；W5 写码批账本 `.superpowers/sdd/2026-10-11-w5-minting-into-market/impl-ledger.md`（§8 N-1..N-11、§9 字面量、§10 失效清单）。
> 纪律：一次一个 Maven（全程 `tools/mvn-lock.sh`）；**未** `git commit`；**未** 跑 `clean verify`；**未** `package`；**未**改 `src/main/**`。
> 允许写入面：`simos-economy/src/test/**`、`simos-app/src/test/**`（`simos-social/src/test/**` 本批零改动 —— 实测全绿，见 §4）。

---

## ① 交账总表（逐类处置）

| # | 类 / 判据 | 处置 | 依据 | 证据 |
|---|---|---|---|---|
| A-1 | `simos-app/src/test/java/io/mosire/simos/app/logging/EconomyLoggingTest.java` | **改**（编译失败 + 删一条已失效断言） | 旧 6 参 `new Government(…, 0L, 100L)`（`seignioragePerCycle, debtIssuePerCycle`）在 W5 已不存在（N-7，Government.java:64-115 只剩 4/5/6 参新形状）⇒ 编译失败；它断言的 `GOV_DEBT_ISSUE_SKIPPED` 生产者 `GovernmentDebtIssuance` 已整类删除（N-2）⇒ 断言没有对象 | §4 命令 E-1；`grep -rn "GOV_DEBT_ISSUE" simos-economy/src/main` = 0 命中 |
| A-2 | `EconomyLoggingTest#existingWarnEventIsNotDowngraded` | **删**（逐个给理由） | 它只断言"**周期发债那条轨道**的 WARN 不降级"；轨道本体按 §一.11「旧设计直接删」已删 ⇒ 该用例没有可断言的事实。**代价如实记**：economy 模块"既有 WARN 不降级"这条 claim 在本类里不再有覆盖（另两条 WARN `MARKET_SUBJECT_EMPTY_UNIT` / `ACCOUNT_SUBJECT_UNRESOLVED` 需要市场轮 / 关账夹具，不在该类的低成本装置范围内）—— 已写进该测试类的新类注 | §一.10 / §一.11；W5 账本 N-2 | 类注第 4 条改写见 diff |
| A-3 | `simos-app/.../time/EconomyCycleHealthTest.java` 类注 3 处 `seignioragePerCycle` | **改**（口径迁移，不改数字） | 字段已换名（N-7）；三处旧叙述若留会变成假注释：①"three-powers 不声明周期铸币"→ 新口径 = 它**没有政府家户**（`ThreePowersWorld.java:322-325` `governmentRef = null`）+ GOV 单位路径不声明 `mintScalePerCycle`（缺省 0）⇒ 既无铸币产业也无 GM 发行；②"货币三个合法来源"里的"周期铸币 `seignioragePerCycle`"→ **铸币生产方式 `MINT_PRODUCTION`**；③"红了怎么办"的等式补 `+ Σ MINT_PRODUCTION` | 设计书 §2 S-3；W5 账本 N-1/N-7/N-11 | §4 命令 E-2（真跑，逐值） |
| A-4 | `EconomyCycleHealthTest` 的**分布指纹基线**（W4b 加牙） | **不改**（静态判定无需重新基线化；★ 真跑验证被 BLOCKED-1 挡住，基线一字未改） | three-powers 无政府家户 ⇒ `EconomySeeder.seedGovernmentMint` 不被调用 ⇒ 无铸币产业、无 `MINT_PRODUCTION`、无铸币债务；`GovernmentSeigniorage`/`GovernmentDebtIssuance` 在 three-powers 上本来就恒 0（GOV 单位路径 `seignioragePerCycle` 缺省 0 / 无政府家户）⇒ **行为逐值不变** | §3 守卫基线节（代码链 + 如实记的真跑状态） | E-2（红在 replay）+ §4 的 test diffstat（`EconomyCycleHealthTest` 改动面 = 只有类注 3 处） |
| A-5 | `simos-economy/.../time/MintProductionSettlementTest.java` | **新增**（W5 判据 ①–⑤ 真日循环用例 5 条） | 设计书 §6.1 W5 行 + §6.3 判据 3；既有测试树**没有**铸币夹具（`grep -rn "moneyOutputPerUnit\|mintScalePerCycle" src/test` 在改前 = 0 命中） | §4 命令 E-3 ⇒ `Tests run: 5, Failures: 0, Errors: 0` |
| A-6 | `simos-economy/.../codec/EconomyCodecTest`（12 红） | **不迁就**（保留断言，报 main 缺陷） | 生产 codec 自反失败：`MoneyOutput` 多了 `"empty":true` | §BLOCKED-1 |

★ **本批没有为迁就实现改任何断言的期望值**（§一.8.1）：`EconomyCodecTest` / `EconomyCycleHealthTest` 的期望值**一个数字都没改**；唯一被改的期望面是 §3 里那条"零成交轮次 = 0"**原样保留**、分布指纹**原样保留**。

---

## ② 被改动语义的断言清单（逐条：类 + 旧 → 新 + 依据）

| # | 类#用例 | 旧期望 | 新期望 | 依据 |
|---|---|---|---|---|
| B-1 | `EconomyLoggingTest#existingWarnEventIsNotDowngraded` | 断言 `WARN GOV_DEBT_ISSUE_SKIPPED`（政府无可发行币种但发债目标 > 0） | **删除**（无对象；同族 claim 由同类的两条 ERROR 面继续覆盖） | W5 账本 N-2（`GovernmentDebtIssuance` 整类删除）+ §一.11 |
| B-2 | `EconomyLoggingTest` 类注第 4 条 | "既有 WARN（`GOV_DEBT_ISSUE_SKIPPED`）不降级" | 改为"W5b 删掉的那一条 + 代价（本类不再覆盖 WARN 不降级）" | 同上 |
| B-3 | `EconomyCycleHealthTest` 类注（3 处） | `seignioragePerCycle` 直充国库 / `GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI` | `mintScalePerCycle`（政策旋钮）+ `MINT_PRODUCTION`（产业产出）；货币来源三件套改写 | 设计书 §2 S-3；W5 账本 N-1/N-3/N-7/N-11 |
| B-4 | 同上，分布指纹 / min / 中位 / max / 轮次数 / "零成交轮次 = 0" | 17×10, 18×1, …, 131×1；min/med/max = 17/35.0/131；96 轮；0 | **一字未改**（预期逐值不变：three-powers 无铸币路径；★ 真跑新真值**未取得** —— E-2 红在 replay，见 §3） | §3（静态判定）+ BLOCKED-1 |

★ **没有**任何用例因为"新口径下算出来不一样"而改期望值。W5 对既有语义的改动（N-1..N-11）落在**有政府家户的世界**（demo / small-world）与**新的铸币产业**上；三个测试模块里唯一跑 small-world 的用例组（`GovGenesisZ6Test` 等 app 用例）被 §BLOCKED-1 挡住，未取得读数 —— 如实记在 §6。

---

## ③ 守卫重新基线化（W4b 加牙的那条）

**结论：静态判定"无需重新基线化"（旧基线与新真值应逐值相同）；★ 但这一点在本轮【没有被真跑验证】—— `EconomyCycleHealthTest` 连创世后的第一次 replay 都到不了（BLOCKED-1，main 的 codec 缺陷），因此本轮**没有**任何新真值，我**没有**改基线里的任何一个数字（凭空填数字 = 假证据；放宽判据 = 违规）。**

- **真跑状态（如实记）**：E-2 ⇒ `rc=1`、`Errors: 1`，红在 `EconomyCycleHealthTest.java:240 → replay:565 » IllegalState economy 侧 JSON 解码失败: EconomySnapshot`（日志 `event=REPLAY_DECODE_FAILED origin=checkpoint reason=codec-decode-failed`）⇒ 8 个段、96 个市场轮**一轮都没跑到**。守卫的基线（`baselineFillsDistribution()` 的 23 个键、`BASELINE_MIN/MEDIAN/MAX = 17/35.0/131`、`BASELINE_MARKET_ROUNDS = 96`）**逐字原样**留在文件里。
- 为什么**预期**不变（代码链，逐条）：① `WorldRegistry.THREE_POWERS` → `ThreePowersWorld.java:322-325` 明确 `PopulationSeeder.seed(plan, AT.tick())`（**不传 governmentRef**）⇒ `Seeding.governmentHousehold()` 为空（`PopulationSeeder.java:169-186`）；② `EconomySeeder.java:3732-3749` 只在 `governmentHousehold.isPresent()` 时调 `seedGovernmentMint` ⇒ three-powers **没有铸币产业、没有铸币 unit、没有铸币劳动配额、没有工具库存**；③ 三个 GOV 走 GOV 单位路径（`economy.RegisterGovernment`），W5 起其政策字段是 `mintScalePerCycle`（缺省 0）⇒ 即使有铸币产业也印不出钱（判据②）；④ 旧的两条周期轨道（`GovernmentSeigniorage` / `GovernmentDebtIssuance`）在 three-powers 上也是恒 0（旋钮缺省 0）⇒ 删掉它们**不改变**该世界的任何数值。
- **"零成交轮次 = 0"仍在的证据（本轮能给的最强形态）**：① 该断言的**代码一字未改**（`assertEveryMarketRoundTraded()` 的①号断言，`EconomyCycleHealthTest.java:420-426`；连同它同方法内的三条自检：轮次数 == 96、同日不得两轮、相邻间隔 ≤ 5 天、Σfills == `MARKET_FILL` 行数）；② 本批对 `EconomyCycleHealthTest.java` 的改动**只有类注 3 处口径迁移**（`git diff` 可核：无任何断言/常量改动，见 §4 命令 E-4 的 diffstat）；③ ★ **真跑绿要等 BLOCKED-1 修好** —— 修好后**第一件事**就是重跑 E-2，并把该轮的 `summary.txt`（含分布指纹与 Σfills）作为证据补进本账本。
- ★ **若 W6 引入变化**（例如给 three-powers 也播政府家户 / 让 GOV 单位声明铸币政策）：按该方法的注释走"**显式重新基线化**"（改 `baselineFillsDistribution()` 的整份分布 + min/median/max，并在提交信息里具名旧值→新值），**不许**放宽成区间或下界。

---

## ④ 实跑真命令 + rc + 关键输出

| # | 命令 | rc | 关键输出 |
|---|---|---|---|
| E-0 | `tools/mvn-lock.sh -q -DskipTests test-compile -pl simos-economy,simos-social,simos-app -am`（本轮首次，改前） | **1** | 唯一编译错：`simos-app/.../logging/EconomyLoggingTest.java:[285,13] incompatible types: long cannot be converted to Map<CurrencyId,Long>`（= 旧 6 参 `Government` 的第 6 个实参 `100L` 落进了新形状的 `Map` 形参）⇒ 处置 A-1 |
| E-1 | `tools/mvn-lock.sh test -pl simos-economy -am`（改前，全模块回归） | **1** | `simos-economy: Tests run: 288, Failures: 1, Errors: 11`，**12 条红全在 `EconomyCodecTest`**，13 处 Caused by 同一根因 `Unrecognized field "empty" (class MoneyOutput)`；依赖模块（Util/Map/Calendar/SocialApi/ActorApi/EconomyApi/**Social**）全 `SUCCESS` ⇒ **simos-social 全绿**，`simos-economy` 除 codec 外全绿 |
| E-2 | `tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false` | **1（BLOCKED-1）** | `Errors: 1`：`EconomyCycleHealthTest…:240->replay:565 » IllegalState economy 侧 JSON 解码失败: EconomySnapshot`；日志 `ERROR … event=REPLAY_DECODE_FAILED origin=checkpoint reason=codec-decode-failed` ⇒ **创世后第一次 replay 就炸**，与 W5 语义无关 ⇒ 守卫基线本轮**取不到真值**（见 §BLOCKED-1） |
| E-3 | `tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MintProductionSettlementTest' -Dsurefire.failIfNoSpecifiedTests=false` | **0** | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`（0.144 s）—— 判据①②③④⑤ 全绿（含两条负向 + 一条越权负向） |
| E-4 | `tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyLoggingTest' -Dsurefire.failIfNoSpecifiedTests=false` | **0** | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`（0.309 s）—— 处置 A-1/A-2 的落地；**同时证明 app 测试树整体 test-compile 通过**（surefire 前会编译该模块全部测试源，含本批改过的 `EconomyCycleHealthTest` / 新测试类） |
| E-5 | `tools/mvn-lock.sh test -pl simos-economy -am`（本批收口轮） | **1** | `Tests run: 293, Failures: 1, Errors: 11`（12 红全在 `EconomyCodecTest`）；依赖模块全 SUCCESS（见 §4.1） |
| E-6 | `tools/mvn-lock.sh -q spotless:apply -pl simos-economy,simos-app` | **0** | **`src/main/**` 零改动**（W5 的 17 个 main 文件 mtime 仍是 08:08–08:14，早于本轮 08:30）；本批被格式化的只有 `EconomyLoggingTest` / `EconomyCycleHealthTest` 两个文件 |
| E-7 | `tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MintProductionSettlementTest' -Dsurefire.failIfNoSpecifiedTests=false`（**变异体 M-1** 在位时） | **1** | `Tests run: 5, Failures: 3`；红在 `[silver=1100L (expected: 600L)]` 等三条余额/守恒读数（见 §7） |
| E-8 | 同上（**变异体 M-2** 在位时） | **1** | `Tests run: 5, Failures: 1`；红在 `[★ 实铸 = min(请求 500, 上限 300) = 300 毫，不是 500] expected: 300L but was: 500L` |

### ④.1 收口轮（E-5）结果

```
tools/mvn-lock.sh test -pl simos-economy -am        ⇒ rc=1
simos-economy: Tests run: 293, Failures: 1, Errors: 11   （288 + 本批新增 5）
  └─ 12 条红**全在** io.mosire.simos.economy.codec.EconomyCodecTest（BLOCKED-1 同一根因）
Reactor：Util / Map / Calendar / SocialApi / ActorApi / EconomyApi / **Social** 全 SUCCESS
```

⇒ 即：`simos-social` **全绿**；`simos-economy` 除 `EconomyCodecTest`（main 缺陷）外**全绿**（含本批新增的 5 条铸币判据）。

### ④.2 预测失效面（静态判定；实跑验证被 §BLOCKED-1 挡住，如实标"未跑"）

| 面 | 静态判定 | 处置 |
|---|---|---|
| `GovGenesisZ6Test.java:133-137`（`serviceUnits` 只按 `operator == HouseholdActors.of(govHousehold)` 过滤 + `hasSize(1)`） | **不失效**：W5 播的铸币 unit 的 operator 是**世界级政府家户** `hh-gov-world-silver`（`SmallWorld.java:266` 传 `EconomySeeder.GENESIS_GOVERNMENT_ID = "world-silver"`，`EconomySeeder.java:3964`），而该循环遍历的是 **GOV 单位**（`gov-central` / `gov-province`，其家户是 `hh-gov-gov-central` / `hh-gov-gov-province`）⇒ 两者不同户，`hasSize(1)` 仍成立 | 不改（未跑，仅静态） |
| `GovToolsZ6Test.java:1712 serviceUnitOf`（`findFirst()` by operator） | **不失效**（同上：它查的是 GOV 单位家户） | 一度改成"再加 `office@` 前缀过滤"，静态核完发现是不必要的改动 ⇒ **已还原**（`git diff` 该文件 = 空），避免留一处未跑过的改动 |
| `GovGenesisZ6Test.java:191-196`（国库 GRAIN/CLOTH/SILVER 逐值） | **不失效**：N-6 加的是 **tool** 商品库存，这三条读的是粮/布/银（`getOrDefault` 逐商品） | 不改 |
| `GovGenesisZ6Test.java:95`（`economy.governments()` 恰 3） | **不失效**：铸币不新增政府 | 不改 |
| 小世界劳动配额总量（N-10：铸币从**剩余预算**取 10‰） | **未判定**：它改的是"剩余"这一维，理论上不影响已发出的作坊/农业/纺织配额，但**没有跑过**（app 被挡） | 留给修好 BLOCKED-1 后的 app 全量轮 |


---

## ⑤ 新增用例（W5 判据 ①–⑤）—— 逐条对应

| 判据（设计书 §6.1 W5 行） | 用例 | 逐值判据 |
|---|---|---|
| ① 铸币有生产账（投入真实消耗、产出 = 货币） | `mintIsAProductionProcessWithAuditAndNoMoneyFromNowhere` + `mintOutputScalesWithTheProductionScale` | 规模 1：`ledger.inputOf(mint,tool) == 2,000`、工具库存 8,000→6,000、发行额 `1×500 = 500`；规模 2：投入 4,000、产出 1,000（逐值跟随规模） |
| ② 负向：规模 = 0 ⇒ 新钱 0 毫 | `zeroMintScaleMeansExactlyZeroNewMoney` | 无发行记录、无货币债务、国库余额 100 不变、货币总量增量 0；并发 DEBUG `MINT_POLICY_ZERO`（且不刷 INFO） |
| ③ 上限：上轮印 X ⇒ 本轮 ≤ X，超额具名拒 | `mintIsCappedByThePreviousCycleMintedAmountAndRejectsTheExcessByName` | 预置上轮（period=1）300 ⇒ 上限 = min(2,000,300) = 300 ⇒ 实铸 300、拒 200；INFO `MINT_LIMIT_REJECTED` 逐字段（requested/limit/previousCycleMinted/mintScalePerCycle/rejected/reason）；`period=2` 供下一轮现算 |
| ④ 每条发行有 `MoneyIssuanceRecord(kind=MINT_PRODUCTION)` | 同①（断言 id `mi-mint-gov-mint-1-silver`、kind、政府、日、period、金额、reason 自带算式） | 逐值 |
| ⑤ 除 `simos.gov.issueMoney` 外无第二条凭空加钱路径 | 同①（**运行读数**）：`Σ 全部账户货币增量 == Σ MINT_PRODUCTION`（500/300/1,000 三处）且非铸币类发行 = 0 | 静态 grep 证据在 W5 写码批账本 §⑥（`MoneyIssuanceJournal` 单点、市场路径 `journal == null` 当场抛） |
| （额外，W5 账本 §2 的越权守卫） | `mintingACurrencyTheGovernmentCannotIssueFailsClosed` | 配方铸 `copper` 而政府 `issuable={silver}` ⇒ `IllegalStateException` + ERROR `MINT_CURRENCY_NOT_ISSUABLE`（权限不放大） |

★ **夹具装置（必须写明，否则这些绿会被误读）**：W5 的铸币路径在本 main 版本里**只能在"组织者 == 国库户"这一形状下触发**（`MintSettlement.java:130-137` 要求 `organizer.equals(treasuryHousehold)`，否则整条 `continue`）；而那个形状恰好触发 §BLOCKED-2（自持债务 + 还债轮 + `Transfer` 禁自转移）。本类因此把国库户的**货币冻结**（`frozenMoney` ≫ 任何铸出额）⇒ 还债轮的"可动货币"恒 0 ⇒ 铸币事实可观测。**这不是设计选择**：真世界里国库的钱是活的 ⇒ 第一个关账日必抛。详细见 §BLOCKED-2 与类注。

---

## ⑥ 我没做 / 没验证的

1. **`simos-app` 全量测试没跑成**（BLOCKED-1）：编不过那一步过了，但任何 boot Shell + replay 的用例都被 codec 缺陷挡住 —— 我**没有**得到 app 模块的"W5 真失败清单"，也没有任何 app 用例的绿/红读数（除 E-2 那条 ERROR）。
2. **守卫没取到新真值**（同上）：`EconomyCycleHealthTest` 旧基线我**一个数字都没改**，因为改数字必须有真跑真值（凭空填 = 假证据）；等 main 修好后按 §3 复核即可（预期逐值不变）。
3. **small-world / demo 世界（有政府家户）的端到端铸币读数没跑**：那些用例（`GovGenesisZ6Test` / `GovToolsZ6Test` / `Z7RemittanceE2ETest` 等）都在 app 模块且要 replay ⇒ 同样被 BLOCKED-1 挡住；**W5 最该被验的那个世界（真的播种出铸币产业）本轮零读数**。
4. **变异自证只做了「守恒式」一类**（M-1/M-2 两个变异体，见 §7）：另三类（不丢失 / 静默付 0 / 断粮）本批**未触及** ⇒ 不适用；另两个候选变异体（不写审计 / 跳过 `credit`）**没跑**。
5. **没跑** `clean verify` / `package`（本批纪律）；**没跑** app 前端的 412 条（同属门禁，归控制方）。
6. **没验证** `spotless:check`（只跑了 `spotless:apply`）；没验证 SpotBugs（`verify` 才跑）。
7. `simos-unit` / `simos-sd` / `simos-core` 的测试**没跑**（本批未触及，且 economy 的依赖闭包里不含它们）。

---

## ⑦ 未完成 / BLOCKED

### BLOCKED-1（P0，main）：`MoneyOutput` 落盘写出去、自己读不回来（铁律 5）

- **现象**：生产 codec 自反失败 `CODEC.decodeChangeSet(CODEC.encodeChangeSet(x))`；异常 `Unrecognized field "empty" (class io.mosire.simos.economy.model.MoneyOutput), not marked as ignorable (one known property: "perUnit")`，reference chain `Industry["moneyOutputPerUnit"]->MoneyOutput["empty"]`。
- **根因（file:line）**：`simos-economy/src/main/java/io/mosire/simos/economy/model/MoneyOutput.java:79` 的 `public boolean isEmpty()` **缺 `@JsonIgnore`** ⇒ Jackson bean 内省把它写成字节里的 `"empty":true`（实得 `"moneyOutputPerUnit":{"perUnit":{},"empty":true}`），而读侧 `FAIL_ON_UNKNOWN_PROPERTIES` fail-closed 拒。
- **先例（同一缺陷已有修法）**：`simos-economy/.../model/MarketTable.java:143-162`（W1f 给 `isEmpty()` 加 `@JsonIgnore` + 注释"派生判断不是状态组件"）；`EconomyMeta.java:79-95` 同款。
- **最小复现**：`tools/mvn-lock.sh test -pl simos-economy -am -Dtest='EconomyCodecTest' -Dsurefire.failIfNoSpecifiedTests=false` ⇒ rc=1，`Tests run: 28, Failures: 1, Errors: 11`。
- **影响面**：不只是 12 条 codec 用例 —— **任何写/读 economy 档的路径都解不回来** ⇒ `simos-app` 模块凡是 boot Shell + replay 的用例全红（E-2 是实例：创世后第一次 replay 就炸）。
- **最小修法（归 main owner）**：给 `MoneyOutput.isEmpty()` 加 `@JsonIgnore`（+ import `com.fasterxml.jackson.annotation.JsonIgnore`）。
- **测试侧立场**：**没有**为迁就实现改 `EconomyCodecTest` 的任何断言（§一.8.1）。

### BLOCKED-2（P0，main）：播种形状下铸币债**自持**，同一日的还债轮必然抛自转移

- **证据链（逐条 file:line）**：
  1. `simos-app/.../world/EconomySeeder.java:3964` `ActorRef operator = HouseholdActors.of(govHousehold)`；`:4134` `HouseholdActors.of(governmentHousehold)` 作为 treasury ⇒ **组织者 == 国库户**（播种形状）。
  2. `simos-economy/.../time/MintSettlement.java:130-137` 只在 `organizer.equals(treasuryHousehold)` 时才铸（⇒ 非自持形状下**整条铸币路径被跳过**，我用"组织者 ≠ 国库户"的夹具实测过：`HARVEST inputs=2000` 但没有发行记录）。
  3. `MintSettlement.java:398-406` 为该形状写一条 `debtor == creditor` 的债务合同。
  4. 同一日的日循环里还有还债轮：`EconomySettlement.java:2861` → `repayDebts`（`:7610+`）；债务人域 = **`householdEconomies.keySet()`**（`:7634`），`owed` = 该户全部 `principal > 0` 的合同（`:7640-7646`），**无"跳过自持合同"判据**。
  5. `householdMoney` 是 `accounts.householdMoney()` 的**活视图**（`EconomySettlement.java:1160`）⇒ 铸币刚记进国库的钱立刻可见 ⇒ 「有库存就还」（`:7650-7656`，W2 v3.6 无保留窗口）为这条自持债铸 `debtor → creditor` 转移。
  6. `simos-economy-api/.../transfer/Transfer.java:114` 禁自转移 ⇒ **抛**：
     `IllegalArgumentException: Transfer 的两端不得相等（自转移不是一条发生额，是坏数据）: HOUSEHOLD:hh-gov-mint`，栈：`Transfer.<init>` ← `ProductionLedger$Accumulator.mint(ProductionLedger.java:735)` ← `EconomySettlement.repayDebts(EconomySettlement.java:7707)` ← `settleOneDayInto(:2861)` ← `EconomyDayStepper.step(:396)`。
- **触发条件（判定为必现）**：一格 + 一个铸币产业 + unit（operator = 政府家户）+ 政府（`mintScalePerCycle > 0`）+ **政府家户有 `classes` 行**（app 对每个 Social 家户都物化；`GovGenesisZ6Test` 也断言 `hh-gov-*` 行存在、人口 0）+ 国库账上有 ≥1 毫可动货币（small-world 创世即给国库银）⇒ **第一个关账日**。
  ★ 实测过两条替身路径都不通：**去掉 `classes` 行** ⇒ 铸币跑得完，但 `stepper.finish()` → `EconomyData` 构造期 `DebtReferenceReconciler.reconcile(DebtReferenceReconciler.java:97)` 以"债务人 hh-gov-mint 在 classes 里不存在"**fail-closed 抛**；**换成"组织者 ≠ 国库户"** ⇒ `MintSettlement` 整条 `continue`（不铸）。⇒ 本 main 版本里**没有任何夹具形状能既走完一天、又留下铸币事实**。
- **修法候选（供控制方裁，我不动 main）**：① `repayDebts` 在 `owed` 处排除 `debtor.equals(creditor)` 的合同（自持债不是可执行债权，只作"发行额 = 政府负债"的记账事实）——我倾向这条；② `MintSettlement` 在 `debtor == creditor` 时不写合同（代价：丢了 §6.1-W5「货币 = 发行政府的债务」的可读事实）；③ 播种时让组织者 ≠ 国库户（代价：新钱会在**同日**被还债轮付给组织者 ⇒ 国库留不住新钱，且那是口径变更，要用户/控制方裁）。
- **测试侧现状**：判据①–⑤ 的 5 条用例**全绿**，但夹具用了"国库货币冻结"这一装置绕开还债轮（见 §5 末尾与类注）；真世界形状的崩溃**没有被测试掩盖** —— 它以 BLOCKED 条目、最小复现与调用栈落在本账本里。

### 变异自证（四类关键项里的「守恒式」，2 个变异体，逐条真跑）

| # | 变异（main，临时） | 字节证据 | 期望红点 | 实测 | 还原 |
|---|---|---|---|---|---|
| M-1 | `MintSettlement.java:359` `Math.addExact(before, minted)` ⇒ `Math.addExact(before, Math.multiplyExact(minted, 2L))`（**凭空多印一倍** = "凭空造币"坏法） | 变异前 `438b347df2585c2dcf6687e952b2cfab` → 变异后 `8123083090be3b2810f091aa17b25e82`（**字节不同 ✓**） | 判据①⑤ 的"货币增量恰好等于发行记录"三处读数 | `rc=1`，`Tests run: 5, Failures: 3`；三条红分别是 `[silver=2100L (expected: 1100L)]`（规模 2 用例）、`[silver=700L (expected: 400L)]`（判据③ 上限用例）、`[silver=1100L (expected: 600L)]`（判据①）—— **红的正是守恒/余额那三行** | `md5sum -c` ⇒ `MintSettlement.java: OK`；`grep -c MUTANT` = 0 |
| M-2 | `MintSettlement.java:238` `Math.min(requested, limit)` ⇒ `requested`（**拆掉"上一轮印量"上限闸门**） | 变异后 `6acd4ab76240b7345a988947a8b7d47c` vs 基线 `438b347df2585c2dcf6687e952b2cfab`（**字节不同 ✓**；变异轮实跑时文件已被还原，故这枚 md5 用 `/tmp` 中的**同规则副本**复算，副本保留在 `/tmp/w5b-M2-probe.java`） | 判据③"实铸 = min(请求 500, 上限 300) = 300" | `rc=1`，`Tests run: 5, Failures: 1`，红在 `mintIsCappedByThePreviousCycleMintedAmountAndRejectsTheExcessByName:311 [★ 实铸 = min(请求 500, 上限 300) = 300 毫，不是 500] expected: 300L but was: 500L` | `md5sum -c` ⇒ `OK`；`grep -c MUTANT` = 0 |

**改动面与纪律**：两个变异体都只落在 **W5 写码批自己的文件** `MintSettlement.java`（本批唯一被临时改动的 main 文件），
每个变异体"改坏 ⇒ 当场红 ⇒ 立即还原 ⇒ `md5sum -c` 逐字节相同"；**本批交账时 `src/main/**` 相对 W5 写码批零改动**（全仓 17 个 main 改动文件的 mtime 全部 ≤ 08:14:52，早于本批开工 08:15:56；`MintSettlement.java` 已回到 `438b347d…`）。

**未自证的部分（如实记）**：另两个候选变异体（M-3 不写审计、M-4 整段跳过 `credit`）**没跑**（时间用在把 app/守卫那条被 BLOCKED 的路查清上）；
四类关键项里本批只触到「守恒式」一类（另三类：不丢失 / 静默付 0 / 断粮 本批未触及 ⇒ 不适用）。


---

## ⑧ 与设计书/写码账本不一致处（主动记录）

1. **W5 写码账本 §10 预告"`EconomyCycleHealthTest` 的货币守恒断言必失效"** —— 实测口径**不成立**（至少不是"因为铸币改了货币供给"）：three-powers 没有政府家户 ⇒ W5 的铸币路径在该世界**结构性不参与**；该用例本轮的红是 codec 缺陷（BLOCKED-1），与 N-1..N-11 无关。⇒ 以代码与实测为准（§四）。
2. **W5 写码账本 §2 写"`MintSettlement` 在关账处读同一个 scale"** —— 核对无误；但**它只在"组织者 == 国库户"时生效**这一点账本没写（`:130-137`），而它恰是 BLOCKED-2 的成因。
3. **W5 写码账本 §6.1-④ BLOCKED-1（"产出货币必须经市场挂单流入"未满足）** 本批**未触及**（不在测试迁移面内），原样留痕。
