# W8 真读数汇总（全部为本轮真跑读数，非推演）

装置：`scripts/w8_run.py`（W6/W7 起停推进装置的改造版）+ `--classes-overlay`。
★ **不跑 `package`、不覆盖任何 jar**：用 `java -cp <15 个模块 target/classes>:<jar 快照>` 起，
classpath 先命中者胜 ⇒ 跑的是新编的类，依赖仍由既有 shaded jar 提供（jar 先 `cp` 成独占快照，免受就地重写影响）。
配置 `scripts/shell-sw.json` md5 `7c1a82f8507e27d74287e8d1f558158b`（与 W6/W7 逐字节相同）。

| 运行 | 世界 | 推进 | 用时 | 结果 JSON |
|---|---|---|---|---|
| `dep690to780b` | small-world（**从 day690 崩溃现场的档续跑**） | tick 689 → 780（Δrev 780） | 18.4 s | `results/dep690to780b.result.json` |
| `dep780to1000` | 同上续跑 | 780 → 1000 | — | `results/dep780to1000.result.json` |
| `dep1000to1400` | 同上续跑 | 1000 → **1400** | — | `results/dep1000to1400.result.json` |
| `fresh600` | small-world **新播** | 0 → 600 | 96.4 s | `results/fresh600.result.json` |
| `fresh600to900` | 同上续跑 | 600 → 900 | — | `results/fresh600to900.result.json` |
| `fresh900to1400` | 同上续跑 | 900 → **1400** | — | `results/fresh900to1400.result.json` |

---

## ① 溢出不致命

### A. 崩溃现场（改前，未过滤日志，逐字）
`../2026-10-11-haul-supply-investigation/results/sw-replay2.full.log:2579`
```
WARN io.mosire.simos.app.gui - event=GUI_REQUEST_FAILED ... path=/api/advance
  error=IllegalStateException message=偿还估值整数溢出的具名失败（拒绝回退到 double）:
  quantity=11482568556053609 numerator=1000 denominator=1
```
日志时序（同文件）：`… FLOWS day=690 rows=178`（34.987）→ 失败（34.998）⇒ 崩在 `FLOWS` 之后、`DAY_END` 之前 = **⑧迁移计划**。

### B. 改后：同一存档续跑越过 day 690（一次进程内跑到 780）
```
[18:02:51] 创世后 head rev=690 tick=689
[18:02:54]   d=700 head=701 (3.0s, ...)
[18:03:04]   d=750 head=751 (12.7s, ...)
[18:03:09]   d=780 head=781 (18.4s, ...)
[18:03:09] advance_daily 689->780 完成 head=781 (18.4s)
{"tick": 780, "revisionDelta": 780, "headRevision": 781}
```
**同一算术、同一 quantity/unitValue/divisor 逐值相同的第一次饱和行**（`results/dep690to780b.log`）：
```
INFO io.mosire.simos.economy.debt - event=ARITHMETIC_SATURATED origin=economy-debt-state originKind=system
  site=debt-valuation-convert quantity=11482568556053609 unitValue=1000 divisor=1
  saturatedTo=9223372036854775807 productHigh=0 productLow=-6964175517655942616 productBits=64
  why=w8:integer-intermediate-exceeds-long:saturate-named-instead-of-refusing-progress
```
⇒ 夹取前的 `quantity`/单价/除数与崩溃日志**逐值相同**（`quantity=11482568556053609`），只是失败语义从"拒绝推进"变成"饱和 + 具名"。

### C. 继续跑到 day 1000 / day 1400（同一存档）
`dep780to1000` → tick 1000；`dep1000to1400` → **tick 1400**（`revisionDelta=1400`）。

### D. 第二处溢出（**任务书 `file:line` 之外**，同一用户裁定）
`dep690to780` 首轮在 **day 720** 撞到 `OperatorCondition.debtServiceDueMilli 不得为负: -20183298549694382`
（`OperatorSettlement.java:356-360` 的裸 `long` 乘/加**静默回绕**）。按同一裁定改饱和后：
```
INFO io.mosire.simos.economy.settlement - event=ARITHMETIC_SATURATED origin=economy-operator-state originKind=system
  site=operator-debt-service-due-total left=7636663117030886270 right=1790283040570604907
  leftBits=63 rightBits=61 saturatedTo=9223372036854775807 debtor=hh--1_1-rural-poor_peasant industry=farm@-1_1
```

### E. ★★ "正常路径逐值不变"的**逐字节**证据（改前 jar vs 改后类，同一配置）
用 W7 调查报告的 `daily/sw600.cp.jsonl`（改前 jar，md5 `7d3d662d953a8009248ac1698a92177b`）与本轮 `daily/fresh600.cp.jsonl` 逐日对拍 **checkpoint md5**：

| | 值 |
|---|---|
| 共同天数 | 601 |
| **逐日 checkpoint md5 完全相同的前缀** | **180 天（d0–d179）** |
| 首个不同日 | **d180** |
| d0 / d30 / d60 / d90 / d120 / d179 | 全部 `SAME` |

- 首处分歧 d180 的成因**可逐条解释**：d180 是周期关账日（180 = 6×30），也是本世界第一批**带债**的家户被转移归零的日子
  ⇒ ③ 当天第一次真的少算了一笔利息。**d60 那批被搬空的户当时 `contracts=0`（无债）⇒ ③ 是 no-op ⇒ 世界仍逐字节相同。**
- ① 在这个新播 600 天世界里 `ARITHMETIC_SATURATED` = **0 条** ⇒ 饱和分支根本没进 ⇒ 正常路径不可能被它改动。
- ⇒ **三条修法在"该动的地方"之外逐值不变**：①（0 次触发）、②（首次 d550）、③（首次 d180）。

### F. 铁律 5 往返（状态读回自证）
`scripts/w8_check_state.py`（只读）在**新进程**里读回落盘 checkpoint 并逐值核：

| | `fresh600`/d1400 | `d690`/d1400 | `fresh600`/d900 | `d690`/d1000 |
|---|---|---|---|---|
| 合同行数 | 5,054 | 5,054 | 1,380 | 2,634 |
| id 三段 == 行内 (debtor, creditor, unit) | **不一致 0** | **0** | **0** | **0** |
| `FORGIVEN` ⇒ principal == 0 / principal ≥ 0 违约 | **0** | **0** | **0** | **0** |
| 跨表引用缺失（debtor/creditor 不在 classes） | **0** | **0** | **0** | **0** |
| 与逐日读数对拍（本金合计） | — | — | **逐值相同** | **逐值相同** |

★ 本批**未改任何状态形状**（无新组件 / 无 Codec / 无 ChangeSet 改动）⇒ 往返不变式按构造不受影响；上表是"确实读得回来且自洽"的真读数。

---

## ② 户绝 ⇒ 双向销账

### A. 新播世界 0→600：唯一一例死亡户绝（d550），与 W7 调查报告**逐值对上**
```
INFO io.mosire.simos.economy.population - event=HOUSEHOLD_EXTINCT_DEBT_WRITTEN_OFF
  origin=economy-population-write day=550 household=hh-2_-2-rural-landlord
  populationBefore=1 deaths=1 cause=death
  debtorSideContracts=6 debtorPrincipalByUnit={money:silver=11153585921}
  creditorSideContracts=0 creditorPrincipalByUnit={}
```
对照 W7 调查报告（改前）：该户 d549 未偿 **11,153,585,921**、d600 **1,716,903,243,129（×153.93）**、`DEBT_FORGIVE` 全仓 **0** 条。

### B. 逐笔读数（`daily/fresh600.debt.jsonl.gz`，该户作为债务人的 6 笔）
| | 逐笔本金（money:silver） |
|---|---|
| d549 | 820,231,161 / 2,425,805,152 / 2,520,238,473 / 4,316,326,484 / 62,039,853 / 1,008,944,798 |
| **合计** | **11,153,585,921** == 上面日志的 `debtorPrincipalByUnit` **逐值相同** |
| d550 起 | 全部从"本金 > 0"视图消失（本金 0 / 状态 `FORGIVEN`） |

### C. `DEBT_FORGIVE` 从 0 变可观测（逐笔，decoded）
6 条，全部 `hh-2_-2-rural-landlord → hh-unit:gov-central, money:silver`，
条款利率 **9475 / 9948 / 10445 / 10967 / 11515 / 12090 ‰** —— 与 W7 报告"6 笔、债权人全部是 gov-central、利率 9,475–12,090‰"逐值一致。

### D. 未偿总额相应下降（聚合读数，`daily/fresh600.agg.jsonl.gz`）
| 日 | 合同数 | `money:silver` 未偿 | Δ |
|---|---|---|---|
| d549 | 792 | 303,399,407,910 | — |
| **d550** | 792 | **292,245,821,989** | **−11,153,585,921** |
| d551–d560 | 792 | 292,245,821,989 | **0** |

★ 下降额 == 减免额 == 6 笔本金之和，**三条读数逐值闭合**。

### E. ★★ 债权人侧在真实世界里被行使（day 1367）
```
INFO … event=HOUSEHOLD_EXTINCT_DEBT_WRITTEN_OFF day=1367 household=hh-0_-2-rural-landlord
  populationBefore=1 deaths=1 cause=death
  debtorSideContracts=5 debtorPrincipalByUnit={commodity:fiber=6299103, money:silver=13026596162025035}
  creditorSideContracts=5 creditorPrincipalByUnit={money:silver=2917065953340157}
```
逐笔（`DEBT_FORGIVE` decoded，共 10 笔）：

| 方向 | 债务人 | 债权人 | 标的 |
|---|---|---|---|
| 债务人侧 | `hh-0_-2-rural-landlord` | `hh-0_-2-rural-middle_peasant` | commodity:fiber |
| 债务人侧 | `hh-0_-2-rural-landlord` | `hh-0_-2-rural-rich_peasant` | commodity:fiber |
| 债务人侧 | `hh-0_-2-rural-landlord` | `hh-0_0-rural-landlord` | money:silver |
| 债务人侧 | `hh-0_-2-rural-landlord` | `hh-unit:gov-central` | money:silver |
| 债务人侧 | `hh-0_-2-rural-landlord` | `hh-unit:gov-province` | money:silver |
| **债权人侧** | `hh-0_-2-rural-landless_laborer-displaced` | `hh-0_-2-rural-landlord` | money:silver |
| **债权人侧** | `hh-0_-2-rural-middle_peasant` | `hh-0_-2-rural-landlord` | money:silver |
| **债权人侧** | `hh-0_-2-rural-poor_peasant` | `hh-0_-2-rural-landlord` | money:silver |
| **债权人侧** | `hh-0_-2-rural-rich_peasant` | `hh-0_-2-rural-landlord` | money:silver |
| **债权人侧** | `hh-2_-1-rural-landless_laborer-displaced` | `hh-0_-2-rural-landlord` | money:silver |

### F. 对账闭合（`fresh900to1400`）
6+4+4+6+5+4+5+**10** = **44** == 本段 `DEBT_FORGIVE` 条数 **44**（无一条来源不明）。

---

## ③ 空户不再计息

### A. 判据分离的日志面（两个方向都在真世界里出现过）
| 事件 | `fresh600`(0→600) | `fresh600→900` | `dep780→1000` | `fresh900→1400` |
|---|---|---|---|---|
| `HOUSEHOLD_EXTINCT_DEBT_WRITTEN_OFF`（**死亡**户绝 ⇒ 销账） | **1** | 1 | 0 | **8** |
| `HOUSEHOLD_EMPTIED_DEBT_RETAINED`（**迁移**归零 ⇒ 不销账） | **20** | 0 | 1 | 3 |
| `DEBT_INTEREST_EXTINCT_HOUSEHOLD_SKIPPED`（③ 具名跳过） | — | **120** | **126** | — |

迁移样例（`cause=relocation-not-death`）：
```
INFO … event=HOUSEHOLD_EMPTIED_DEBT_RETAINED household=hh--2_2-rural-rich_peasant populationBefore=28
  cause=relocation-not-death contracts=0 retainedPrincipalByUnit={}
  why=w8:empty-household-without-deaths-keeps-debt (migration-carries-its-share)
```

③ 跳过的逐笔样例（d630 关账，正是那些"被搬空但仍挂债"的户）：
```
DEBUG io.mosire.simos.economy.debt - event=DEBT_INTEREST_EXTINCT_HOUSEHOLD_SKIPPED day=630
  debtContract=debtc-…-hh--1_0-rural-middle_peasant-hh--1_0-rural-landlord-money:silver-…
（同日另有 hh-0_-2-rural-rich_peasant / hh--1_0-rural-rich_peasant 两条）
```

### B. 逐周期读数：改前"空户继续复利" vs 改后"持平"（同规模新世界、同一户）
| 户（迁移归零） | W7 改前 d600/户绝前 | **本轮**逐周期（`daily/fresh600.debt.jsonl.gz`） |
|---|---|---|
| `hh-0_-1-rural-middle_peasant` | ×**19.62** | d165 14,656 → d330 **220,836** → d360 220,836 → **d600 220,836**（9 个关账周期**持平**） |
| `hh-0_-2-rural-rich_peasant` | ×**37.03** | d180 17,943 → d210 **34,424** → d600 **34,424**（持平） |
| `hh--1_0-rural-middle_peasant` | ×1.61 | d240 4,825 → d300 **178,937** → d600 **178,937**（持平） |
| `hh--1_2-rural-middle_peasant` | ×2.81 | d360 247,996 → d600 248,557（近持平） |
| `hh-2_-2-rural-landlord`（**死亡**户绝） | ×**153.93** | **全期无活跃债务**（d550 已全额销账） |

### C. 桶①对照（d600 时点快照，债务人人口 ≤ 0 的活跃合同）
| | 改前（W7 报告） | 本轮 |
|---|---|---|
| `money:silver` 合计 | **1,716,904,349,222** | **857,839** |
| 其中 `hh-2_-2-rural-landlord` | 1,716,903,243,129 | **0**（已销账） |
| 其余（迁移归零户 + 1 个创世空槽位 2,500） | 1,106,093 | 855,339 |

⇒ 死亡户绝的 1.72×10¹² **消失**；迁移归零户的小额欠账**原样保留**（不销账）。
差额 1,716,903,491,383 = 该户被销的本金 + 其余空户本应继续计的利息 —— 与"③ 停息"逐值方向一致。

---

## ④ D5 根因与处置：**读侧口径，无需修**

| | `hh--1_0-rural-middle_peasant`（案1） | `hh-0_-1-rural-middle_peasant`（案2） |
|---|---|---|
| 合同 id | `debtc-…-hh--1_0-rural-middle_peasant-hh--1_0-rural-landlord-money:silver-<terms>` | `debtc-…-hh-0_-1-rural-middle_peasant-hh-0_-1-rural-landlord-money:silver-<terms>`（**全期只有一个 id**） |
| 消失前 | d200–209 p=142,284 `opened=150` `last=180` `DEFAULTED` | d165–179 p=14,656 `opened=165` `last=None` `NORMAL` |
| 消失期 | d210–212（本金 ≤ 0，被 `principal>0` 视图隐藏） | d180–187 |
| 回来后 | d213 p=86,912 **`opened=150` 不变**、**`last=210`** | d188 p=113,080 **`opened=188`（变了！）** |
| 决定性判据 | `upsert` 对**已存在**行**保留** `openedDay`（`DebtContractBook:155`）⇒ `opened=150` 保住 ⇒ 行**一直在**，只是本金 0 | `opened` 165→188 **只可能**是"行被删过、d188 重建"（若未删必为 165） |
| 机制 | d210 关账计息写 `lastInterestDay=210`（⇒ 日志 `MIGRATION_DEBT_SELF_NETTED day=210 source=hh--1_0-rural-middle_peasant target=hh--1_0-rural-landlord`，目标**恰是债权人** ⇒ 整笔自债净额 ⇒ 本金减到 0）；d213 同四元组再借 ⇒ **同 id**（id 是四元组的确定性派生） | d180 迁走后本金 0 的**空壳被删**（`ModeMigrationSettlement:663-680` 的 `debts.remove`，**全仓唯一一处**；行有质押则保留）；d188 同四元组重建 ⇒ 同 id、新 `openedDay` |

**结论**：D5 = ①读侧 `principal>0` 过滤 × ②`id = (debtor, creditor, unit, terms)` 的确定性派生（所以"再借"看起来是"同一条回来"）× ③两条既有写路径（计息写 `lastInterestDay`、迁移删 0 本金空壳）。**全是文档化语义，不是缺陷 ⇒ 不修。**

**顺带更正 W7 调查报告一处**：其 §2.3 写"`DebtContractBook` 没有任何删除合同的口径（`contracts.remove` 全仓 0 命中）"——
实测**有**，只是**工作表的变量名是 `debts` 不是 `contracts`**：`ModeMigrationSettlement.java:678` `debts.remove(contractId)`。
调查报告据此推出的"凡从活跃视图消失的都是本金被减到 0"**对本条不成立**（案 2 就是被删，不是减到 0）。

---

## 纪律自证
- **未 `git commit`**；未改 `src/test/**`、未改其他模块、未改 `docs/**`、未改 `AGENTS.md`。
- **未跑** `verify` / `clean` / `package`；本批跑过的 Maven：`spotless:apply` ×3、`-pl simos-app -am -DskipTests compile` ×3、
  单类回归 `-Dtest=DebtBasedClearingAcceptanceTest`（6/6 绿）与 `-Dtest=DeathDebtWriteOffTest`（3/3 绿）各一次 —— **一次一个**。
- **未覆盖运行中的 jar**：`simos-app/target/*.jar` md5 全程 `7d3d662d953a8009248ac1698a92177b`（所有运行结果 JSON 里的 `jarMd5` 可核）。
- 每个 Java 世界实例收工走 `SIGTERM → 该次运行的 PID`；**全程未用 `pkill -f`**。
- 端口只用 10821-10823 / 10831-10833（未碰 5711/5817/5857/5867/5715/5757、103xx、104xx、105xx、10711）。
- 全部产物落 `.superpowers/sdd/2026-10-11-w8-debt-semantics/`（gitignore 之下）。

## ★ 磁盘让步（如实记：删了什么、留了什么、为什么）
收工时本机 `/` 只剩 **1.4 G（99%）**，而本轮两个 store 各 **508 M**（其中 `simos.db` 各 **490 M** = 1400 个修订的增量历史）。
为不给同机其他 Agent 的 Maven/世界运行造成写盘失败，**删掉了两个 store 目录**（`stores/d690`、`stores/fresh600`），
但**先保住决定性证据**并把读回自证在新目录上重跑过：

| 保住 | 值 |
|---|---|
| `stores-final/d690-tick1400.json` | md5 `0da69fc443584b94939ace7637fabd26`（5054 合同 / FORGIVEN 46 / 本金合计 2,866,246,580,638,851,255） |
| `stores-final/fresh600-tick1400.json` | md5 `7ae91c2223868a55dc85cb044b76b039`（5054 合同 / FORGIVEN 52 / 本金合计 2,865,978,961,997,290,096） |
| 逐日 checkpoint md5 链 | `daily/*.cp.jsonl`（含被删 checkpoint 的 md5 + 字节数，可复算） |
| 全部日志 / result JSON / 逐日读数 | `results/` `daily/` |

在 `stores-final/` 上重跑 `scripts/w8_check_state.py`：**异常项 0**（id 三段一致、`FORGIVEN`⇒本金 0、跨表引用齐）。
**丢掉的是**：`revisions`/`events` 两张表的增量历史（即"从该 store 再续跑"的能力）。
⇒ **"可读回"这条结论仍可从保住的产物复现**；"再从 d1400 续跑"需要重新播世界。释放 **约 1.0 G**（现 3.3 G 空闲）。
