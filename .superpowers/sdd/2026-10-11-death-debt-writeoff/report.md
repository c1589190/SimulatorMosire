# 户绝 ⇒ 债务是否消除 —— 调查报告

- **角色**：调查 Agent（**只读**：未改仓库任何源码/测试/文档；`git status --porcelain` 开工/收尾一致，见 §6）
- **被查对象**：`f5b4f28d`（HEAD = W5.5 借贷重做），jar md5 `7d3d662d953a8009248ac1698a92177b`
- **用户原话**（逐字，2026-10-11）：
  > 「还有利率，这个其实不用管，同样原则，**富人没有利滚利就行，穷人的话，死了账一并消除**，主要调查后者，**带利滚利的利率有没有随着家户人口死亡而消失**」
- **时间**：2026-10-11 17:16 – 17:4x（CST）
- **产物落点**：本目录（`.superpowers/sdd/2026-10-11-death-debt-writeoff/`）—— 报告、逐日读数、汇总表、装置、日志全在这里；笨重 store（597 MB）也在 `stores/`（**未放 `/tmp`**，AGENTS §八.1）

---

## 1. 装置

### 1.1 构建与 jar

```bash
# 开工核对：pgrep -af "classworlds.launcher|surefirebooter" → 无本仓 Maven；git status --porcelain → 仅 1 个既存
#           文档改动（他人所致，非本 Agent；见 §6）；HEAD = f5b4f28d
```

| | 值 |
|---|---|
| 被查 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，**md5 `7d3d662d953a8009248ac1698a92177b`**，27,169,137 字节，mtime `2026-10-11 16:51:11` |
| 是否重构建 | **否**。W6 总验收已于 16:46–16:51 对**同一 HEAD** 跑过 `tools/mvn-lock.sh clean verify`（BUILD SUCCESS），jar md5 与本轮逐字节相同 ⇒ 直接复用，未覆盖任何 jar |
| 本 Agent 跑过的唯一 Maven | `tools/mvn-lock.sh -pl simos-economy -am -Dtest=DeathDebtWriteOffTest -Dsurefire.failIfNoSpecifiedTests=false test`（§2.6 的批次路径对照），rc=0，`Tests run: 3, Failures: 0`，surefire 报告 mtime `17:23` 落在本轮 |

### 1.2 世界 / store / 端口 / 命令

```bash
bash tools/run-shaded.sh <jar> --store <dir> --world=<small-world|three-powers> \
  --config scripts/shell-<sw|tp>.json --gui-port P --mcp-port P+1 --approval-port P+2
# 推进：POST /api/advance {"branch":"main","expectedRevision":R,"from":A,"to":B}（逐日，600 次）
```

| tag | 世界 | 推进 | 端口 | Δrev | 用时 | PID | store |
|---|---|---|---|---|---|---|---|
| `smoke` | small-world | 逐日 0→5 | 10501 | 5 | 1.0 s | 374195 | `stores/smoke` |
| `sw600` | small-world | **逐日 0→600** | 10511 | 600 | 116.8 s | 374367 | `stores/sw600` |
| `tp600` | three-powers | **逐日 0→600** | 10521 | 600 | 251.6 s | 375172 | `stores/tp600` |

- **端口未复用** 5711/5817/5857/5867/5715/5757，也未碰 103xx/104xx（另有两个只读调查在跑）。
- **收工**：装置对**该次运行的 Java 子进程 PID** 发 SIGTERM（`proc.send_signal(SIGTERM)`）；**全程未用 `pkill -f`**。
- 配置 md5：`shell-sw.json` `7c1a82f8507e27d74287e8d1f558158b`、`shell-tp.json` `8d877ef31c88e149632692d8fe55f767` —— **与 W4/W5/W6 报告所列逐字节相同**（`checkpointInterval=1`）。

### 1.3 脚本（全在本目录 `scripts/`，md5 为本轮收尾值）

| 脚本 | 作用 | md5 |
|---|---|---|
| `dd_run.py` | 起停服务 + 逐日推进 + 逐日抽读数（逐户行 / 逐笔债务行 / 聚合行 / checkpoint md5）；每次抽完只留最新 checkpoint | `30401ea6e3418c4eba93844d2c62fc0f` |
| `ddlib.py` | checkpoint 全量信封 → 逐户行 / 逐笔债务行（p>0）/ 本金已归零合同 | `598a87b686a72bbd509cd07e029be6f4` |
| `dd_w6lib.py` | **W6 验收读数库的逐字节副本**，用于跨装置对拍（与 `/tmp/w6acc/w6lib.py` md5 相同） | `5253b7f7bb7a61d5059d67be13d702c8` |
| `dd_analyze.py` | A–F 六节分析（户绝清单 / 逐周期债务 / d600 拆解 / 债权人侧 / Top 债务人 / 利率面） | `2bcdb876f53bf5bd8b51dad78bdfe5db` |
| `dd_events.py` | **户绝成因的权威判定**（`social.populationEvents` 逐户逐日） | `185c355691cc0d892f0809cd7f9d7233` |
| `dd_tables.py` | 汇总表 → `results/tables.md` | `93c6e20d7e7b8b833776227c13b3eb26` |
| `driver1.sh` | 两个世界的串行驱动（一次一个世界实例） | `1a789826bf4298194a05030eabefb26c` |
| plan：`plan-sw600.json` / `plan-tp600.json` / `plan-smoke.json` | 推进配方 | `3aaffa02…` / `3aaffa02…` / `f22484cb…` |

### 1.4 读口与统计窗口（★ AGENTS §九.4：不同窗口不并排比）

| 读口 | 内容 | **窗口** |
|---|---|---|
| `store/checkpoints/main/<rev>.json` **全量信封** | `economy.classes` / `debtContracts` / `assetShares` / `class_positions`、`actor.accounts`、`social.households` | **时点快照**（该日关账后） |
| `social.populationEvents`（同一信封内） | 逐户逐日的 DEATH / BIRTH / TRANSFER_IN / TRANSFER_OUT | **历史累计**（创世起） |
| 进程 stdout 具名事件 | `event=NAME k=v …` | 生命周期 / 逐日 |
| `revisions` 表（sqlite） | 分支 revision Δ | 历史累计 |

⇒ 本报告所有债务/人口数字**默认都是时点快照**；"户绝日 / +120 / …" 是**同一批合同 id 在不同时点的取值**，不是本期发生额或累计发生额，三者未混用。**币种/标的不可相加**，一律按 `money:silver` / `money:gold` / `commodity:fiber` 分列。

### 1.5 ★ 装置自证（决定性）

| 对拍项 | 结果 |
|---|---|
| `sw600` vs W6 `daily/summary-small-world.csv`（逐日 601 天） | `pop_economy` 不符 **0** 天；`money` **0**；`debt_open` **0**；`cp_md5` **0** |
| `tp600` vs W6 `daily/summary-three-powers.csv`（逐日 601 天） | 同上，**全 0** |
| d600 checkpoint md5 | sw `70c8fed60a32b59222a4cf73f72d7f94`、tp `4fb88034ae19fcdaa9410f571a798c63`（**与 W6 报告逐字节相同**） |

⇒ 本轮两个世界与 W6 验收**逐字节同一个世界**，读数可比。

---

## 2. 链路复核 + 逐项真数字

### 2.1 §1 的链路复核结论（★ 活路径到底核销不核销）

**控制方给的三条代码事实，逐条复核结论：全部准确，并可补全调用图。**

| # | 事实 | 复核 |
|---|---|---|
| ① | `writeOffDebtsForDeaths` 声明在 `EconomySettlement.java:4870`，唯一调用点 `:4486` | ✅ 全仓 `grep -rn writeOffDebtsForDeaths` 只有这 2 处 |
| ② | `:4486` 在 `applyPopulationChangeInto`（批次 `LotChange` 入口，`:4402`）的逐行循环里 | ✅ 见 `:4471` 的 `population <= 0L` 跳过与 `:4486` 的调用 |
| ③ | 日常活路径 `applyHouseholdPopulationDeltasInto`（四参，`:4575`）里没有任何核销调用 | ✅ 该方法的 javadoc 自己写着「★ **不做**：不摊批次、不缩劳动、**不删债**、不改 naturalNeeds」；body 只写 `householdEconomies.put(withPopulationAndLabor(...))` 与 `flows.put(withLifecycle(...))` |

**调用图（两个入口各自的上游，`src/main` 全量核过）：**

```
【活路径 —— 生产唯一实际走的】
PopulationEconomyTimeParticipant:760   （app 日循环，用 Social 报来的 vital.*）
  → EconomyDayStepper.applyHouseholdPopulationDeltas(4参):597
    → EconomySettlement.applyHouseholdPopulationDeltasInto(session, deltas, births, deaths, day):4575
      → 只写 population + FlowRow 生死流水；★ 无任何 debt / forgive / writeOff 调用

【批次路径 —— 机制存在，但生产无人调用】
EconomySettlement.applyPopulationChange(EconomyData, List<LotChange>):4385   ← src/main 零调用点
EconomyDayStepper.applyPopulationChange(List<LotChange>):539                 ← src/main 零调用点
  → EconomySettlement.applyPopulationChangeInto(session, changes):4402
    → writeOffDebtsForDeaths(...):4486 → DebtContractBook.forgive(...):4904（survivors==0 ⇒ 全额减免）
```

**★ 待判定的两问，答案是：**

1. **链路准确**（上面给全了 `file:line` 与调用图）。唯一补充：`EconomyDayStepper:550` 确实调 `applyPopulationChangeInto`，但 `EconomyDayStepper.applyPopulationChange`（`:539`）**在 `src/main` 里没有任何调用者** —— 除定义处与注释外，`grep -rn "applyPopulationChange" simos-*/src/main` 只命中 `EconomyDayStepper:539/550`、`EconomySettlement:4385/4392/4402` 与若干 javadoc，**方法引用/反射也没有**（`::`、`getMethod("applyPopulationChange…")` 均 0 命中）。⇒ **W3-fix 台账"批次侧 src/main 零调用点"属实。**
2. **活路径上户绝的债不会被核销** ⇒ 它们继续计息。这正是下面量化的东西。

**⭐ 运行时铁证（不靠读代码）：** 两个世界各 600 天、日志开 `-Dsimos.economy.logLevel=DEBUG` 并保留相关事件：

| 事件 | small-world | three-powers | 含义 |
|---|---|---|---|
| `HOUSEHOLD_VITAL_WRITEBACK` | 586 | 593 | 活路径**确实在跑**（逐日生死回写） |
| `DEBT_FORGIVE`（任何减免，含死亡核销与 GM） | **0** | **0** | **600 天里一次减免都没发生过** |
| `POPULATION_CHANGE_APPLIED`（批次入口的 INFO） | **0** | **0** | **批次路径在生产里一次都没被进过** |
| `MIGRATION_DEBT_SELF_NETTED` | 4 | 35 | 迁移时"债权人==迁移目标"的自债净额 |

---

### 2.2 ★ 户绝清单（§2.1 的真数字）

**★ 一个必须说清的前提（本调查最重要的口径发现）：本世界里"家户人口 → 0"有两种完全不同的成因。**

用 `social.populationEvents`（逐户逐日）做**权威判定**，不是靠形态学猜：

| 世界 | 家户行 | 运行中真实户绝（>0→0） | 其中**转移**（人搬走） | 其中**死亡**（人死光） | 未记事件 | d600 仍活着 | 从未有人口的空户槽位 |
|---|---|---|---|---|---|---|---|
| small-world | 178 | **21** | **19** | **1** | 1 | 86 | 71 |
| three-powers | 342 | **14** | **11** | **0** | 3 | 189 | 139 |

- 判据样本（sw d180）：当天 `TRANSFER_IN = TRANSFER_OUT = 486`、`DEATH = 3`；家户侧合计 损失 489 / 获得 486 / 净 −3。**10 个家户当天人口归零，但只死了 3 个人** ⇒ 那是**转移**，不是死亡。
- ⇒ **若不加分辨，会把"阶层/户籍流动"误读成"户绝"**。本报告此后凡说"户绝"都标成因，并把死亡驱动的单独列出。

**完整清单见 `results/tables.md` §A（两个世界逐户：户 id、归零日、死前人口、成因、当日死/移出、阶层、死前银钱/粮/地权、银钱分位、户绝前一刻未偿）。**

**★ 唯一一例"死亡把整户吃光"的户绝（sw）：**

| 户 id | 归零日 | 死前人口 | 阶层 | 死前银钱 | 死前粮 | 死前地权 | 户绝前一刻未偿 |
|---|---|---|---|---|---|---|---|
| `hh-2_-2-rural-landlord` | **550** | 1 | landlord | `{}`（一分钱没有） | 34,307 fiber | 752,859 | **11,153,585,921** |

（three-powers 侧 0 例纯死亡户绝；14 例中 11 例是转移、3 例当日无任何生死/转移事件记录，见 §4 缺口。）

---

### 2.3 ★★ 户绝之后，它们名下的债怎么变（逐周期）

**（a）那一例死亡户绝 —— 债务不但没消失，还在空户名下继续复利**

`hh-2_-2-rural-landlord`，6 笔合同，债权人全部是 `hh-unit:gov-central`，条款利率 9,475–12,090 ‰/周期，起始日 d345–d358，d600 时全部 `DEFAULTED`：

| 时点 | 人口 | 未偿本金（silver） | 相对上一读数 |
|---|---|---|---|
| d500（户绝前 50 天） | 1 | 73,465,259 | — |
| d540 | 1 | 11,153,585,921 | ×151.8 |
| **d549（最后一天有人）** | 1 | **11,153,585,921** | — |
| **d550（户绝日，最后 1 人死亡）** | **0** | 11,153,585,921 | （本周期尚未到计息日） |
| d570 | 0 | 138,154,449,162 | **×12.39** |
| d600 | **0** | **1,716,903,243,129** | **×12.43（再一个周期）** |

- **户绝后 50 天：11,153,585,921 → 1,716,903,243,129 = ×153.93**（人口全程为 0）。
- **最后一周期（d600 关账）该空户的 `FlowRow` 读数**：`interestDue = 1,716,829,777,870`、`netSurplus = −1,716,829,777,870`；`newBorrowing` 与 `capitalizedArrears` **均为空** ⇒ 这一笔增长**全部是利息并入本金**，不是新借、也不是欠款资本化。
- 计息步长 = 周期（30 天）：d540→d570 ×12.39，d570→d600 ×12.43，中间日不动 —— 与 `chargeInterest`「关账日计息、并入本金」逐值一致。

**（b）全部 21 例（sw）的逐时点序列**

见 `results/tables.md` §B。摘录（"倍率" = d600 ÷ 户绝前一刻）：

| 户 id | 户绝日 | 成因 | 户绝前一刻 | d600 | 倍率 |
|---|---|---|---|---|---|
| `hh-2_-2-rural-landlord` | 550 | **death** | 11,153,585,921 | **1,716,903,243,129** | **×153.93** |
| `hh-0_-2-rural-rich_peasant` | 60 | transfer | 1,263 | 46,771 | ×37.03 |
| `hh-0_-1-rural-middle_peasant` | 180 | transfer | 14,656 | 287,531 | ×19.62 |
| `hh--1_2-rural-middle_peasant` | 180 | transfer | 113,361 | 318,696 | ×2.81 |
| `hh--1_0-rural-middle_peasant` | 210 | transfer | 142,284 | 229,143 | ×1.61 |
| `hh--1_2-rural-rich_peasant` | 180 | transfer | 47,553 | 57,690 | ×1.21 |
| `hh--1_0-rural-rich_peasant` | 180 | transfer | 67,850 | 65,661 | ×0.97 |
| （其余 14 例） | — | — | — | 0 或 ≤ 0.40× | 见 tables.md |

- **口径说明**：`transfer` 那 19 例里，人搬走时债务**按比例跟走**（迁移桥 `ModeMigrationSettlement.moveDebt` / `LotMigrationBook`），若迁移目标恰是债权人则走**自债净额**（`MIGRATION_DEBT_SELF_NETTED`，日志可见）。**留在空户名下的那部分不减记**，且其中一部分随后继续计息 —— 上表 ×37.03 / ×19.62 / ×2.81 / ×1.61 四例就是这个形态。
- **本金归零的形态**：`DebtContractBook` **没有任何删除合同的口径**（`contracts.remove` 全仓 0 命中）⇒ 合同永远留在表里，只可能被 `reduce`（还款/迁移/清算，都伴随等值价值转移）或 `forgive`（**600 天 0 次**）改本金。凡在我的"活跃（本金 > 0）"视图里消失的，都是本金被减到 0。
- **three-powers 侧**：14 例户绝的债在 d600 全部为 0 或远低于户绝日（×0.34、×0.18、×0.00 …）。**已排除"核销"**（`DEBT_FORGIVE` = 0）；读数是"还掉了或被迁移搬走了"，但**我没有把 tp 这些 0 逐一落到具体机制上**（见 §5 未验证项）。

---

### 2.4 ★★ d600 名义总额拆解

**small-world（d600 时点快照）**

| 桶 | 合同数 | 未偿（按标的） |
|---|---|---|
| ① 债务人**已户绝**（运行中 >0→0） | 18 | `money:silver` **1,716,904,349,222** |
| ①b 债务人是**从未有人口的空户槽位** | 1 | `money:silver` 2,500 |
| ② 债务人**活着**但该标的未偿 > **全世界该币种货币量**（= 按用户口径**不可偿还**） | 506 | `money:silver` **290,958,135,531,232** |
| ③ 正常规模 | 40 | `money:silver` 68,878；`commodity:fiber` 127,711,160 |
| **合计** | 565 | `money:silver` **292,675,039,951,832**（≈2.93×10¹⁴，**与 W6 报的 2.92×10¹⁴ 相符**）；`commodity:fiber` 127,711,160 |
| **★ 扣掉 ① 之后** | — | `money:silver` **290,958,135,602,610**（≈2.91×10¹⁴）；`commodity:fiber` 127,711,160 |
| ★ 扣掉 ①+①b 之后（再减 2,500） | — | `money:silver` **290,958,135,600,110** |

- **① 占比 = 1,716,904,349,222 ÷ 292,675,039,951,832 = 0.5866%** —— 而且这 0.587% 里 **99.9999% 就是那一例死亡户绝（`hh-2_-2-rural-landlord`）**。
- ② 的判据数字：全世界货币量（actor 账合计）= silver **269,700**、copper 100,000；活着债务人的"户×标的"组合 **87** 个，其中 **79 个**的未偿 > 全世界该币种货币量 ⇒ **绝大多数活着债务人早已不可偿还**（Top 一户欠 3.0×10¹³ silver，全世界只有 2.7×10⁵ silver，差 **8 个数量级**）。
- ③ "正常规模"只有 40 笔合同、silver 合计 **68,878**。

**three-powers（d600 时点快照；4 个标的，不可相加）**

| 桶 | 合同数 | 未偿 |
|---|---|---|
| ① 债务人已户绝 | 2 | `money:copper` 87,127 |
| ② 活着但不可偿还 | 903 | `money:gold` 127,806,182,652；`money:copper` 7,933,139,448；`money:silver` 202,944,395 |
| ③ 正常规模 | 509 | `money:gold` 315,733；`money:copper` 332,843；`money:silver` 1,954,437；`commodity:fiber` 226,717,335 |
| **扣掉 ① 之后** | — | `money:gold` 127,806,498,385；`money:copper` 7,933,472,291；`money:silver` 204,898,832；`commodity:fiber` 226,717,335 |

- tp 的 ① 只有 0.001% 量级 —— **tp 的户绝户本来就没欠多少**（14 例里只有 2 例带债进 d600）。
- tp 全世界货币量：gold 129,400 / copper 136,600 / silver 424,600；活着债务人 108 个"户×标的"组合里 **43 个**不可偿还。

---

### 2.5 债权人侧：死者作为债权人的债权

**它们原样留着，不转移、不清零**（合同永远在表里，且仍在计息）：

| 世界 | 已户绝家户作为债权人的合同数 | 未偿（按标的） |
|---|---|---|
| small-world | **48** | `money:silver` **2,397,425,359,670**；`commodity:fiber` 25,798,354 |
| three-powers | **121** | `money:copper` 1,428,091,450；`money:gold` 158,259,736；`money:silver` 299,863；`commodity:fiber` 520,163 |

**逐笔样例（sw，d600 时点，全部 `DEFAULTED`）：**

| 债务人（活着） | 本金 | 标的 | 起始日 |
|---|---|---|---|
| `hh-0_2-urban-landlord` | 1,043,307,982,932 | money:silver | 453 |
| `hh-1_-2-rural-poor_peasant` | 303,284,130,530 | money:silver | 423 |
| `hh-0_2-rural-poor_peasant` | 263,876,919,510 | money:silver | 393 |
| `hh-0_2-urban-landless_laborer-displaced` | 239,287,084,305 | money:silver | 395 |
| `hh-1_-1-rural-middle_peasant`（已户绝） | 80,947,071,011 | money:silver | 363 |

⇒ **债权不随户消失**：一个 0 人口的户仍然是 1.04×10¹² silver 的债权人，且这笔应收挂在**活着的**债务人身上（那笔债同时也在**继续复利**）。

---

### 2.6 ★ "富人没有利滚利就行" —— Top 债务人到底是穷户还是富户？

**答案是：最大的复利债务人几乎全是穷户。**

**small-world d600 未偿额 Top 12（完整见 `results/tables.md` §E）：**

| 户 id | 阶层 | 人口 | 活着? | 未偿合计（silver） | 钱 | 粮 | 地权 |
|---|---|---|---|---|---|---|---|
| `hh-0_2-urban-poor_peasant` | **poor_peasant** | 76 | 是 | **30,096,221,684,231** | `{}` | 0 | 0 |
| `hh-0_2-urban-landless_laborer-displaced` | **landless_laborer** | 8 | 是 | 29,641,441,620,882 | `{}` | 0 | 0 |
| `hh-1_0-rural-poor_peasant` | **poor_peasant** | 50 | 是 | 27,174,889,209,764 | `{}` | 0 | 1,788,294 |
| `hh-0_2-rural-poor_peasant` | **poor_peasant** | 47 | 是 | 20,326,454,600,778 | `{}` | 0 | 2,169,997 |
| `hh-1_-2-rural-poor_peasant` | **poor_peasant** | 54 | 是 | 19,120,455,189,728 | `{}` | 0 | 1,788,294 |
| `hh--2_2-rural-landless_laborer-displaced` | **landless_laborer** | 7 | 是 | 17,899,928,918,957 | `{}` | 0 | 0 |
| `hh-2_0-rural-poor_peasant` | **poor_peasant** | 49 | 是 | 15,466,898,466,677 | `{}` | 0 | 1,124,836 |
| `hh-2_0-rural-rich_peasant` | rich_peasant | 15 | 是 | 14,518,185,138,121 | `{}` | 0 | 0 |
| `hh--2_1-rural-landless_laborer-displaced` | **landless_laborer** | 7 | 是 | 13,242,153,720,503 | `{}` | 0 | 0 |
| `hh-0_1-rural-poor_peasant` | **poor_peasant** | 51 | 是 | 12,773,538,751,117 | `{}` | 0 | 1,877,710 |
| `hh-0_2-rural-middle_peasant` | middle_peasant | 41 | 是 | 8,735,001,315,315 | `{}` | 0 | 0 |
| `hh--1_-1-rural-landless_laborer-displaced` | **landless_laborer** | 7 | 是 | 8,379,874,516,867 | `{}` | 0 | 0 |

**直接回答用户那句话的三条读数：**

1. **不是"富人利滚利"，是穷户在利滚利**：Top 12 里 9 户是 `poor_peasant` / `landless_laborer`，且**钱=0、粮=0、地权=0 或极少** ⇒ 他们**永远不会还上**，欠账只会在空转中复利。
2. **富户也在复利膨胀**（所以"富人没有利滚利"这个前提在本世界**不成立**）：sw 的 Top 12 里有 `hh-2_0-rural-rich_peasant`（15 人，欠 1.45×10¹³，钱粮地全 0）；已户绝的 `rural-rich_peasant` 一族的债到 d600 也涨到 0.97×–37× 户绝前。**末代地主 `hh-2_-2-rural-landlord` 更是把 ×153.93 打出来了。**
3. **膨胀的燃料是失控的借款利率，不是本金**：d600 活约 565 笔的条款利率 min 20‰、**p50 = 13,328‰**、max **317,513‰**（= 31,751% / 周期）；只有 59 笔是 20‰，**457 笔 > 1,000‰，131 笔 > 100,000‰**。世界借款利率本身从 d0 的 20‰ 一路涨到 **d500 的 204,675‰、d525 的 333,388‰**，再回落到 d600 的 71,565‰（货币总量却几乎不动：367,200 → 369,700 silver）。代码里那条上界注释自己写着「到那一步利率已经是"每周期涨 20 亿倍"，是**标定问题（W6）**」（`MoneyMarketSettlement.java:206-215`，`MAX_TERMS_RATE_PER_MILLE = Integer.MAX_VALUE`）。

---

### 2.7 ★ 对照实验（路径级）：活路径 vs 批次路径

**做到了，而且两侧都有真跑过的证据：**

| 路径 | 装置 | 结果 |
|---|---|---|
| **活路径**（生产实际走的） | 两个真世界 × 600 天逐日（`dd_run.py`），日志开 DEBUG | **0 条 `DEBT_FORGIVE`**；`POPULATION_CHANGE_APPLIED` **0 条**；那一例死亡户绝的债 **×153.93**（§2.3a） |
| **批次路径**（`writeOffDebtsForDeaths` 所在的那条） | `tools/mvn-lock.sh -pl simos-economy -am -Dtest=DeathDebtWriteOffTest -Dsurefire.failIfNoSpecifiedTests=false test` | **`Tests run: 3, Failures: 0, Errors: 0`，BUILD SUCCESS**；其中 `deathFloorsPerContractAndFullDeathWritesOffTheWholePrincipal` 断言：全户死亡 ⇒ 本金 **0**、状态 **FORGIVEN**、`DebtStock.totalPrincipal` **0**；`after.moneyIssuances()` **empty** |

⇒ **同一个 jar 的同一批 class，两条入口给出相反的行为：批次路径会把全户死亡的债清零，活路径一个字都不动，而生产只走活路径。**
（这不是"我构造的两条路"，是"代码里存在的两条路 + 生产实际走哪条"，两侧都由真跑过的进程给出。）

---

### 2.8 守恒 / 口径：核销会改变什么读数

- **`forgive` 只动合同本金与状态**：`DebtContractBook.forgive` 只 `contracts.put(...)` 一个新 `DebtContract`（本金 ↓、减到 0 ⇒ `FORGIVEN`），**不碰账户、库存、流水、发行审计** —— 由代码（`:423-487`）与上面那条测试的 `after.moneyIssuances() … isEmpty()` 双向证实。
- **货币守恒不受影响**：核销不铸钱、不回笼、不转移 ⇒ `money_total` / actor 账合计 / 发行审计**逐值不变**。会变的是：`debtContracts` 本金合计（= W6 报的 `money:silver` 读数）、`DebtStock.totalPrincipal`、会话瞬态 `debtWriteOffs`。
- **哪一侧的"资产估值"会变**：我把 `src/main` 核了一遍 —— **没有任何"债权人应收 → 家户财富/资产"的汇总口径**（`grep` 债权侧的 sum/merge/total 聚合 = 0 命中；`assetShares` 只记实物份额；`DebtStock.totalPrincipal` 的读者只有它自己与 `ModeMigrationSettlement` 里一个**同名局部变量**，后者是迁移按比例分摊用的，不是财富读数）。所以一次核销在读数上只表现为"负债侧掉了"，**不会**同步改债主的资产读数（本项目没有把应收折成财富的口径）。
- **量级算式（sw，d600 时点快照）**：
  - 未偿总额 `money:silver` = 292,675,039,951,832
  - 其中债务人已户绝 = 1,716,904,349,222 ⇒ **占比 0.587%**
  - **若死亡核销本该生效**（把 ① 全部抹掉）⇒ 292,675,039,951,832 − 1,716,904,349,222 = **290,958,135,602,610**，只降 **0.59%**。
  - ⚠️ 这还是**保守口径**：那一例是 d550 死的，若 d550 就核销，它此后两个周期的 ×12.39 / ×12.43 复利（≈1.7×10¹²）本不该发生 —— 也就是说"活路径不核销"这一条缺陷，**当前在名义总额上只放大 0.59%**。
- **但反过来**：这 0.587% 里几乎全是**一个已死的空户**（`hh-2_-2-rural-landlord`，人口 0、钱粮地近零）。它**没有偿还能力、没有任何对手方能收回它**，而这笔 1.72×10¹² 的"资产"正挂在 `hh-unit:gov-central` 的应收账上 —— 这就是"户绝不核销"在读数上留下的东西。

---

## 3. 对用户那句话的直接回答

> 「**带利滚利的利率有没有随着家户人口死亡而消失**」

## **没有消失。一次都没有。**

**一句结论**：在本仓当前代码（`f5b4f28d`）与真世界 0→600 的实测里，**家户人口归零不会核销它任何债务**；带复利的欠账会**留在一个已经没有任何人的家户名下继续计息**，而这条核销机制（死亡按人口比例删债、全户死光 ⇒ 全额减免）**在生产路径上根本不可达**。

**支撑数字（全部为本轮真跑读数）：**

1. **两个世界 × 600 天，`DEBT_FORGIVE` = 0 条**（任何原因的减免都没有发生过）；批次入口 `POPULATION_CHANGE_APPLIED` **= 0 条**（那条核销路径一次都没被进过）。
2. **唯一一例"死光了"的户绝**：`hh-2_-2-rural-landlord`，d550 最后 1 人死亡 ⇒ 债务 **11,153,585,921（d549）→ 1,716,903,243,129（d600）= ×153.93**，人口全程 0；d600 该空户的 `interestDue = 1,716,829,777,870`、`newBorrowing`/`capitalizedArrears` 皆空 ⇒ **纯利息并入本金**。
3. 另外 4 例（sw）空户的债到 d600 比户绝时**更大**：×37.03、×19.62、×2.81、×1.61。
4. **批次路径确实会核销**（`DeathDebtWriteOffTest` 3/3 通过：全户死 ⇒ 本金 0 / `FORGIVEN`）—— 机制是好的，只是**没有生产调用者**。
5. **扣掉债务已户绝的那部分，d600 未偿总额只降 0.59%**（292,675,039,951,832 → 290,958,135,602,610）。⇒ **把"户绝核销"接上，并不能解决 2.93×10¹⁴ 这个量级**；真正把它推上去的是**失控的借款利率**（p50 13,328‰/周期，max 317,513‰；世界利率 d0 20‰ → d525 333,388‰）。**这两件事要分开修。**
6. **顺带纠正用户那句话里的一个前提**：本世界里"利滚利"的**主力是穷户**（Top 12 中 9 户是 `poor_peasant`/`landless_laborer`，钱粮地全 0），但**富户同样在复利膨胀**（`rich_peasant` 欠 1.45×10¹³；末代地主 ×153.93）—— **"富人没有利滚利"在当前世界并不成立**。

---

## 4. 缺陷 / 缺口清单（**只报不修**）

| # | 发现 | 最小复现 / 读数 | 严重度 |
|---|---|---|---|
| **D1** | **活路径不核销、核销路径不可达**：`writeOffDebtsForDeaths` 唯一调用点在 `applyPopulationChangeInto`（批次入口），而 `EconomyDayStepper.applyPopulationChange`（`:539`）在 `src/main` **零调用点**（方法引用/反射亦 0） | 600 天 ×2 世界 `DEBT_FORGIVE=0`、`POPULATION_CHANGE_APPLIED=0`；`hh-2_-2-rural-landlord` 死后 ×153.93 | **高** |
| **D2** | **空户继续无限复利**：人口 0 的家户仍被 `chargeInterest` 扫到（该函数只跳自持合同与本金 0，**无人口/存活过滤**，`EconomySettlement.java:9715`） | d600 空户 `interestDue = 1.7168×10¹²` | **高** |
| **D3** | **借款利率失控、无有意义上界**：世界利率 d0 20‰ → d500 204,675‰ → d525 333,388‰；合同条款上界是 `Integer.MAX_VALUE`（代码注释自认"标定问题（W6）"） | sw d600 活约 p50=13,328‰、max=317,513‰；货币总量同期 367,200→369,700（+0.7%） | **高** |
| **D4** | **名义债务与货币量脱钩 8–9 个数量级**：d600 未偿 silver 2.93×10¹⁴ vs 全世界 silver 2.697×10⁵；活着债务人 87 个"户×标的"组合里 79 个不可偿还 | `results/tables.md` §C | **高** |
| **D5** | **合同本金"消失 3 天后原样回来"（机制未定）**：`hh--1_0-rural-middle_peasant` d209 本金 142,284 → d210–d212 **本金 0（从活跃视图消失）** → d213 **同一合同 id、同一 `openedDay=150` 恢复到 86,912**；同款形态在 d180 的 `hh-0_-1-rural-middle_peasant`（14,656 → 消失 → d210 以 `opened=188` 回来 57,622）也出现 | `daily/sw600.debt.jsonl.gz` 按 `debtor` 过滤逐日可见 | 中（**须先证伪读侧因素**） |
| **D6** | **"户绝"不等于"死亡"**：sw 21 例户绝里 **19 例是转移**（d180 单日 `TRANSFER_IN=TRANSFER_OUT=486`、`DEATH=3`，10 户同日归零）；若按"population→0"直接当死亡统计，会把阶层/户籍流动读成户绝 | `results/dd-events-sw600.json` / `-tp600.json` | 中（**读数口径**） |
| **D7** | **空户槽位带债**：178 个 sw 家户行里 71 个创世即 0 人口（城市槽位），其中 1 个在 d600 仍挂 2,500 silver 欠账 | `results/tables.md` §C 桶 ①b | 低 |
| **D8** | **`unexplained` 户绝**：4 例（sw 1 + tp 3）"人口 → 0"当日 `populationEvents` 里死/生/移入/移出**全是 0**，其中 tp 两例是 104/108 人的大户 | `results/dd-events-*.json` 中 `cause: "unexplained"` | 中（**事件簿有洞**） |

---

## 5. 未做 / 未验证项（**没跑过的一律不写成通过**）

1. **未做"同一世界的路径级 A/B"**：我没有构造"同一份状态分别沿活路径与批次路径各推一段"的实验。给的是**两侧都有真跑证据的替代对照**：活路径 = 两个真世界 600 天（0 核销）；批次路径 = `DeathDebtWriteOffTest` 真跑 3/3 通过（全户死 ⇒ 清零）。**"两条路径跑同一个世界做差"这件事没做。**
2. **未逐笔落实 three-powers 那些"债 → 0"的机制**：已排除核销（`DEBT_FORGIVE=0`），但"还款 vs 迁移搬走 vs 清算处置"三者我**没有逐笔归因**。
3. **D5 的机制未定**：本金"消失 3 天又原样回来"我只给了读数，**没有**证明根因（读侧过滤？迁移桥的中间态？核销后又重建？）。**要先排除我自己的读侧因素**（`debt_rows` 按 `principal>0` 过滤）再定性。
4. **未做 `-Dsimos.economy.traceLevel=TRACE`**：全程只开 `economy.logLevel=DEBUG`（够用来抓 `DEBT_FORGIVE`）。⇒ `DEBT_INTEREST` / `DEBT_REDUCE` 的**逐笔明细没有采**，"本金每一步怎么变"是从逐日时点快照做差得到的，不是逐笔事件流。
5. **未验证 `moveDebt` 的比例守恒**：我观察到"人搬走时债务按比例跟走、目标是债权人时净额"的形态与 4 条日志样本，**没有**逐值核对 `Σ源减少 == Σ目标增加 + 净额`。
6. **未跑的窗口**：只跑到 d600；**没有**验证 d600 之后（例如 600→1200）那条 ×12.4/周期的复利会把读数带到哪里。
7. **未核 `hh-unit:*` / `hh-gov-*` 这些非普通家户端点**：本报告的"家户"口径 = `economy.classes` 里的行；政府/单位户只作为债权人出现，未单独展开。
8. **未做任何"应该怎么修"的设计**：本报告不含修复方案（调查 Agent 只报不修）。
9. **`results/tables.md` 的"银钱分位"只管货币持有量**：本世界 d600 价表**全为 0**（`marketTables.prices` 除 haul 外皆 0）⇒ 货物无法折价，财富分位**没有**把粮/货并进来。这是口径限制，不是遗漏。

---

## 6. 纪律自证

- **未改仓库任何源码/测试/文档。** 开工与收尾 `git status --porcelain` 一致：只有一条**开工前就存在**的他人改动
  `M docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md`（非本 Agent 所为，本 Agent 全程未碰）。
- 本报告与全部产物都在 `.superpowers/sdd/2026-10-11-death-debt-writeoff/`（gitignore 之下，控制方 `git add -f`）。
- **一次只有一个 Maven**（用 `tools/mvn-lock.sh`，`MVN_LOCK_WAIT=1800`）；**一次只有一个世界实例**；**未用 `pkill -f`**。
- 运行中的 jar **未被覆盖**：本轮只跑了 `test` 阶段（不过 `package`/`shade`），jar md5 开工/收尾均为 `7d3d662d953a8009248ac1698a92177b`。
- **发现缺陷只报不修**（§4 全是读数 + `file:line`，无一处改动）。
- 收尾进程核查：见下。

---

## 7. 产物清单

| 路径 | 内容 |
|---|---|
| `report.md` | 本报告 |
| `results/tables.md` | A–F 六节全量汇总表（两个世界逐户户绝清单 / 逐时点债务 / d600 拆解 / 债权人 / Top 债务人 / 利率） |
| `results/dd-analyze.json` | A–F 的机器可读全量结果 |
| `results/dd-events-sw600.json` / `-tp600.json` | 户绝成因的权威判定（`populationEvents` 逐户逐日） |
| `results/sw600.result.json` / `tp600.result.json` / `smoke.result.json` | 每次运行的 jar md5 / PID / 端口 / Δrev / 日志 md5 / 行数 |
| `results/sw600.log(.stripped)(.md5)` / `tp600.*` | 只保留相关事件的日志（`DEBT_FORGIVE` / `HOUSEHOLD_VITAL_WRITEBACK` / `POPULATION_CHANGE_APPLIED` / `MIGRATION_DEBT_SELF_NETTED` / 自持跳过） |
| `daily/sw600.{agg,hh,debt}.jsonl.gz` / `tp600.*` | **逐日读数**：聚合 601 行 / 逐户 106,978+205,542 行 / 逐笔活跃债务 184,086+660,642 行 |
| `daily/*.cp.jsonl` | 逐日 checkpoint 的 md5 + 字节数（被删 checkpoint 的可复算留痕） |
| `stores/sw600` / `stores/tp600` | 两个世界的 store（含 d600 全量信封 `checkpoints/main/601.json`） |
| `scripts/` | 装置全部脚本（md5 见 §1.3）+ 配置 + plan |
| `logs/mvn-deathdebt.log` | 批次路径对照那一次 Maven 的完整输出 |
