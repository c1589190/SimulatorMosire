# W6 标定读数 —— small-world（0→600 天）

## 1. 人口曲线（逐 30 天采样；时点快照）与死因（本期发生额）

| day（时点） | 人口(Social) | 人口(Economy) | 逐户不符 | 本期末30天 出生 | 本期末30天 基准死亡(BASE) | 本期末30天 饥荒死亡(BASE_PLUS_FAMINE) | 满足率<1000‰ 户数 | 满足率=0 户数 | 满足率 min | 中位 |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 4800 | 4800 | 4 | 26 | 6 | 94 | 0 | 0 | None | None |
| 30 | 4720 | 4720 | 0 | 27 | 8 | 107 | 75 | 63 | 0 | 0 |
| 60 | 4634 | 4634 | 0 | 26 | 2 | 108 | 79 | 79 | 0 | 0 |
| 90 | 4549 | 4549 | 0 | 17 | 2 | 124 | 79 | 79 | 0 | 0 |
| 120 | 4444 | 4444 | 0 | 26 | 4 | 99 | 90 | 86 | 0 | 0 |
| 150 | 4364 | 4364 | 0 | 22 | 5 | 78 | 93 | 64 | 0 | 0 |
| 180 | 4304 | 4304 | 0 | 16 | 3 | 68 | 77 | 45 | 0 | 360 |
| 210 | 4246 | 4246 | 0 | 33 | 8 | 82 | 73 | 49 | 0 | 360 |
| 240 | 4196 | 4196 | 0 | 24 | 10 | 80 | 71 | 49 | 0 | 20 |
| 270 | 4129 | 4129 | 0 | 15 | 1 | 86 | 64 | 49 | 0 | 420 |
| 300 | 4054 | 4054 | 0 | 25 | 4 | 92 | 70 | 53 | 0 | 0 |
| 330 | 3982 | 3982 | 0 | 19 | 5 | 128 | 71 | 60 | 0 | 0 |
| 360 | 3870 | 3870 | 0 | 22 | 5 | 81 | 76 | 60 | 0 | 0 |
| 390 | 3807 | 3807 | 0 | 16 | 1 | 88 | 76 | 71 | 0 | 0 |
| 420 | 3733 | 3733 | 0 | 26 | 2 | 78 | 74 | 73 | 0 | 0 |
| 450 | 3680 | 3680 | 0 | 17 | 3 | 89 | 75 | 75 | 0 | 0 |
| 480 | 3604 | 3604 | 0 | 21 | 0 | 87 | 75 | 74 | 0 | 0 |
| 510 | 3533 | 3533 | 0 | 20 | 4 | 83 | 76 | 71 | 0 | 0 |
| 540 | 3471 | 3471 | 0 | 18 | 2 | 94 | 77 | 77 | 0 | 0 |
| 570 | 3390 | 3390 | 0 | 15 | 1 | 97 | 77 | 74 | 0 | 0 |
| 600 | 3312 | 3312 | 0 | 1 | 0 | 1 | 74 | 74 | 0 | 0 |

- 历史累计（全 600 天）：出生 **432**、`cause=BASE` 死亡 **76**、`cause=BASE_PLUS_FAMINE` 死亡 **1844** ⇒ 饥荒类占死亡 **96.0%**
- 饥荒强度分布（历史累计）：{'faminePpm=1000': 1809, 'starvationRatio=1000': 1809, 'faminePpm=999': 13, 'starvationRatio=999': 13, 'faminePpm=705': 2, 'starvationRatio=705': 2, 'faminePpm=779': 2, 'starvationRatio=779': 2}

## 2. 断顿户数 / 满足率（`HOUSEHOLD_GAP_REPORTED`，逐日事件）

- 事件行 **600** 天；断顿户数 min/中位/max = **44 / 73.0 / 90**；缺口量 min/中位/max = **121754 / 179656.0 / 240966**（毫）
- 前 10 天样例（day, 断顿户数, 缺口毫, 当日是否开市）：[('1', '76', '240966', 'false'), ('2', '76', '240883', 'false'), ('3', '66', '205518', 'true'), ('4', '66', '205565', 'false'), ('5', '63', '193301', 'true'), ('6', '63', '193202', 'false'), ('7', '63', '193004', 'false'), ('8', '63', '192877', 'true'), ('9', '63', '192853', 'false'), ('10', '63', '192770', 'true')]
| day | 断顿户数 | 缺口毫 | marketRan |
|---|---|---|---|
| 1 | 76 | 240966 | false |
| 51 | 77 | 219095 | false |
| 101 | 79 | 213435 | false |
| 151 | 73 | 219572 | false |
| 201 | 49 | 139872 | false |
| 251 | 49 | 124414 | false |
| 301 | 64 | 202423 | false |
| 351 | 74 | 196186 | false |
| 401 | 73 | 181958 | false |
| 451 | 77 | 183992 | false |
| 501 | 75 | 172396 | false |
| 551 | 74 | 164738 | false |

## 3. 阶层滑落（逐市场轮结算，`CLASS_SLIDE`/`CLASS_SLIDE_ROUND` + 状态序列）

- `CLASS_SLIDE` 事件合计 **41** 条（涉及 **40** 户）；`levels` 字段分布 = {1: 41}（**每轮最多降一级**的实测）
- `CLASS_SLIDE_ROUND` 轮数 **240**；累计 evaluated=22456、slid=41；常量 costPerLevelPerMille=1000 speedPerRatioPerMille=100 roundDays=5

| day（市场轮） | evaluated | slid | skippedNoDebt | skippedCooldown | skippedNoLowerTarget | unpricedDebt |
|---|---|---|---|---|---|---|
| 3 | 107 | 0 | 99 | 8 | 0 | 0 |
| 43 | 107 | 0 | 95 | 7 | 5 | 0 |
| 83 | 105 | 0 | 86 | 9 | 10 | 0 |
| 123 | 105 | 0 | 90 | 4 | 11 | 12 |
| 163 | 103 | 2 | 74 | 4 | 23 | 23 |
| 203 | 93 | 1 | 57 | 11 | 24 | 23 |
| 243 | 90 | 0 | 52 | 13 | 25 | 23 |
| 283 | 90 | 0 | 59 | 5 | 26 | 23 |
| 323 | 90 | 1 | 47 | 9 | 33 | 18 |
| 363 | 89 | 0 | 31 | 0 | 58 | 17 |
| 403 | 87 | 0 | 15 | 0 | 72 | 12 |
| 443 | 87 | 0 | 10 | 0 | 77 | 12 |
| 483 | 87 | 0 | 10 | 0 | 77 | 11 |
| 523 | 87 | 0 | 9 | 0 | 78 | 9 |
| 563 | 86 | 0 | 8 | 0 | 78 | 9 |

- 状态面 `classStandings.currentPositionId`：**40/173** 户在 600 天内换过位置
- 每户出现过的不同位置数分布：{1: 133, 2: 39, 3: 1}
- 序列样例 `hh-0_0-rural-landlord`：0:tenancy-fixed-kind-landlord → 68:tenancy-fixed-kind-tenant-operator
- 序列样例 `hh-0_0-rural-landless_laborer-displaced`：0:displaced-laborer → 95:displaced-destitute
- 序列样例 `hh-0_0-urban-landless_laborer-displaced`：0:displaced-laborer → 113:displaced-destitute
- 序列样例 `hh--1_-1-rural-landless_laborer-displaced`：0:displaced-laborer → 120:displaced-destitute
- 序列样例 `hh--1_0-rural-landless_laborer-displaced`：0:displaced-laborer → 150:displaced-destitute

## 4. 铸币（逐关账日 `MINT_PRODUCTION` 金额 + 国库存量 + 货币总量）

| day（本期发生额） | MINT_PRODUCTION | 国库 world-silver（时点） | 货币总量（时点） | Σ |
|---|---|---|---|---|
| 120 | silver=500 | silver=500 | {"copper": 100000, "silver": 267700} | 367700 |
| 240 | silver=500 | silver=1000 | {"copper": 100000, "silver": 268200} | 368200 |
| 360 | silver=500 | silver=1500 | {"copper": 100000, "silver": 268700} | 368700 |
| 480 | silver=500 | silver=2000 | {"copper": 100000, "silver": 269200} | 369200 |
| 600 | silver=500 | silver=2500 | {"copper": 100000, "silver": 269700} | 369700 |

- 铸币日志事件计数：{'MINT_LAST_CYCLE_READOUT': 1, 'MINT_SETTLED': 5}
- `MINT_LAST_CYCLE_READOUT` 样例：`{"event": "MINT_LAST_CYCLE_READOUT", "origin": "economy-money", "originKind": "system", "day": "240", "period": "8", "government": "world-silver", "previousRoundDay": "120", "previousCycleMinted": "500", "before": "{}", "after": "{silver=500}", "currencies": "1"}`
- `MINT_SETTLED` 样例：`{"event": "MINT_SETTLED", "origin": "economy-money", "originKind": "system", "day": "120", "period": "4", "closedUnits": "1", "currencies": "1", "mintedTotal": "500", "byCurrency": "{silver=500}", "kind": "MINT_PRODUCTION"}`

## 5. 货币市场（逐市场轮：demand/supply/lent/利率 + 借款 + 池空）

- 轮数（`MONEY_MARKET_ROUND`）= **240**；`MONEY_MARKET_SETTLED` = 0；`RATE_WRITTEN` = 240；`UNFILLED`（池空/未满足）= 0；`SKIPPED` = 0；逐笔 `LOAN` = 0
- 需求 min/中位/max = 0 / 3294490.0 / 27838683；供给 min/中位/max = 127 / 201.0 / 9201；实际放贷 min/中位/max = 0 / 189.0 / 9201；笔数 min/中位/max = 0 / 4.0 / 24
- **池空轮次（demand>0 且 lent=0）= 0 / 240**；需求未被完全满足的轮次 = 210

| day | demandMilli | supplyMilli | lentMilli | loans | borrowers | lenders | lent/demand‰ | 当轮成交笔数 |
|---|---|---|---|---|---|---|---|---|
| 3 | 104311 | 9201 | 9201 | 7 | 4 | 4 | 88 | 23 |
| 43 | 2222273 | 671 | 671 | 6 | 4 | 3 | 0 | 6 |
| 83 | 4337831 | 1332 | 1332 | 8 | 6 | 3 | 0 | 7 |
| 123 | 7315632 | 4330 | 4330 | 15 | 9 | 7 | 0 | 20 |
| 163 | 6147878 | 754 | 754 | 7 | 4 | 4 | 0 | 15 |
| 203 | 12663747 | 508 | 508 | 5 | 2 | 4 | 0 | 7 |
| 243 | 17208589 | 3074 | 3074 | 10 | 3 | 8 | 0 | 32 |
| 283 | 16115561 | 189 | 189 | 2 | 1 | 2 | 0 | 9 |
| 323 | 17857450 | 189 | 189 | 4 | 3 | 2 | 0 | 8 |
| 363 | 1935978 | 3936 | 3936 | 9 | 1 | 9 | 2 | 16 |
| 403 | 896570 | 189 | 189 | 2 | 1 | 2 | 0 | 13 |
| 443 | 468718 | 189 | 189 | 4 | 3 | 2 | 0 | 12 |
| 483 | 247462 | 2917 | 2917 | 11 | 2 | 10 | 11 | 13 |
| 523 | 25718 | 189 | 189 | 2 | 1 | 2 | 7 | 10 |
| 563 | 0 | 4000 | 0 | 0 | 0 | 0 | 0 | 11 |

- 利率曲线（时点，逐市场轮写回；抽样）：[(0, 20), (50, 40), (100, 93), (150, 231), (200, 597), (250, 1565), (300, 4139), (350, 10967), (400, 29081), (450, 77146), (500, 204675), (550, 199615), (600, 71565)]
- `MONEY_MARKET_RATE_WRITTEN` 全 240 轮的 rate 序列（抽样）：3:single-region=20->21 43:single-region=36->37 83:single-region=66->69 123:single-region=133->139 163:single-region=279->292 203:single-region=597->626 243:single-region=1289->1353 283:single-region=2804->2944 323:single-region=6112->6417 363:single-region=13328->13994 403:single-region=29081->30535 443:single-region=63470->66643 483:single-region=138535->145461 523:single-region=302394->317513 563:single-region=162590->154461
- `MONEY_MARKET_RATE_WRITTEN` 首/末样例：`{"event": "MONEY_MARKET_RATE_WRITTEN", "origin": "economy-debt", "originKind": "tick", "day": "3", "markets": "1", "ratesPerMille": "single-region=20->21", "effectiveFrom": "next-market-round", "reason": "loan-rate-adapts-to-money-supply-and-demand-S-12-5"}` / `{"event": "MONEY_MARKET_RATE_WRITTEN", "origin": "economy-debt", "originKind": "tick", "day": "600", "markets": "1", "ratesPerMille": "single-region=75331->71565", "effectiveFrom": "next-market-round", "reason": "loan-rate-adapts-to-money-supply-and-demand-S-1`

## 6. 市场活跃度（逐市场轮：成交笔数分布 / 零成交轮 / 未成交归因 / 价格路径）

- 轮数 **240**；成交笔数 min/p25/中位/p75/max = 3 / 8 / 10.0 / 12 / 68；**零成交轮次 = 0**（0.0%）
- 未成交槽位 min/中位/max = 357 / 375.0 / 429
- 未成交归因 top 档（历史累计，槽位数）：[('ALGORITHM_UNCOVERED', 44378), ('LOGISTICS_CAPACITY', 17325), ('UNSOLD_SELF_USABLE', 16968), ('NO_BUYER', 6624), ('NO_ADJACENT_SUPPLY', 4000), ('OUTCOMPETED', 1168), ('CANNOT_REPRODUCE', 864), ('ALL_RESERVED', 799)]
- `LOGISTICS_CAPACITY` 具名细分（数量）：[('no-haul-service-in-shipping-hex', 13618712886), ('sub-unit-residual', 7610884848), ('capacity-exhausted', 226719082), ('out-of-derived-radius', 54635)]
- 开市触发原因分布：{'LOW_GRAIN_STOCK': 120, 'PERIODIC': 120}

| day | trigger | 成交笔数 | 未成交槽 | 归因 top3 |
|---|---|---|---|---|
| 3 | LOW_GRAIN_STOCK | 23 | 419 | LOGISTICS_CAPACITY=168, NO_ADJACENT_SUPPLY=90, UNSOLD_SELF_USABLE=77 |
| 43 | LOW_GRAIN_STOCK | 6 | 426 | LOGISTICS_CAPACITY=168, NO_ADJACENT_SUPPLY=90, UNSOLD_SELF_USABLE=76 |
| 83 | LOW_GRAIN_STOCK | 7 | 426 | ALGORITHM_UNCOVERED=133, LOGISTICS_CAPACITY=90, NO_ADJACENT_SUPPLY=80 |
| 123 | LOW_GRAIN_STOCK | 20 | 397 | LOGISTICS_CAPACITY=130, ALGORITHM_UNCOVERED=116, UNSOLD_SELF_USABLE=99 |
| 163 | LOW_GRAIN_STOCK | 15 | 395 | LOGISTICS_CAPACITY=161, ALGORITHM_UNCOVERED=130, UNSOLD_SELF_USABLE=85 |
| 203 | LOW_GRAIN_STOCK | 7 | 383 | LOGISTICS_CAPACITY=146, ALGORITHM_UNCOVERED=137, UNSOLD_SELF_USABLE=71 |
| 243 | LOW_GRAIN_STOCK | 32 | 372 | ALGORITHM_UNCOVERED=121, LOGISTICS_CAPACITY=92, UNSOLD_SELF_USABLE=91 |
| 283 | LOW_GRAIN_STOCK | 9 | 376 | ALGORITHM_UNCOVERED=200, UNSOLD_SELF_USABLE=73, LOGISTICS_CAPACITY=71 |
| 323 | LOW_GRAIN_STOCK | 8 | 375 | ALGORITHM_UNCOVERED=217, UNSOLD_SELF_USABLE=65, LOGISTICS_CAPACITY=61 |
| 363 | LOW_GRAIN_STOCK | 16 | 373 | ALGORITHM_UNCOVERED=213, UNSOLD_SELF_USABLE=67, OUTCOMPETED=38 |
| 403 | LOW_GRAIN_STOCK | 13 | 368 | ALGORITHM_UNCOVERED=254, UNSOLD_SELF_USABLE=65, NO_BUYER=25 |
| 443 | LOW_GRAIN_STOCK | 12 | 368 | ALGORITHM_UNCOVERED=258, UNSOLD_SELF_USABLE=65, NO_BUYER=25 |
| 483 | LOW_GRAIN_STOCK | 13 | 367 | ALGORITHM_UNCOVERED=224, UNSOLD_SELF_USABLE=66, OUTCOMPETED=39 |
| 523 | LOW_GRAIN_STOCK | 10 | 369 | ALGORITHM_UNCOVERED=253, UNSOLD_SELF_USABLE=62, NO_BUYER=25 |
| 563 | LOW_GRAIN_STOCK | 11 | 362 | ALGORITHM_UNCOVERED=255, UNSOLD_SELF_USABLE=62, NO_BUYER=25 |

- 价格路径（逐市场轮，抽样 20 轮；时点）：
  - `cloth`（6→0，Δ=-6）：3:6 33:18 63:30 93:44 123:60 153:37 183:25 213:13 243:1 273:0 303:0 333:0 363:0 393:0 423:0 453:0 483:0 513:0 543:0 573:0
  - `fiber`（0→0，Δ=+0）：3:0 33:0 63:0 93:0 123:0 153:0 183:0 213:0 243:0 273:0 303:0 333:0 363:0 393:0 423:0 453:0 483:0 513:0 543:0 573:0
  - `grain`（2→0，Δ=-2）：3:2 33:14 63:26 93:38 123:52 153:41 183:53 213:86 243:134 273:122 303:112 333:124 363:125 393:73 423:44 453:29 483:17 513:5 543:0 573:0
  - `haul`（4→4，Δ=+0）：3:4 33:4 63:4 93:4 123:4 153:4 183:4 213:4 243:4 273:4 303:4 333:4 363:4 393:4 423:4 453:4 483:4 513:4 543:4 573:4
  - `iron`（9→0，Δ=-9）：3:9 33:0 63:0 93:0 123:0 153:0 183:0 213:0 243:0 273:0 303:0 333:0 363:0 393:0 423:0 453:0 483:0 513:0 543:0 573:0
  - `tool`（19→0，Δ=-19）：3:19 33:7 63:0 93:0 123:0 153:0 183:0 213:0 243:0 273:0 303:0 333:0 363:0 393:0 423:0 453:0 483:0 513:0 543:0 573:0

## 7. 债务与货物（辅助读数）

| day | 未偿合同 | 已结清 | 未偿本金（逐介质） | 货物总量（时点） |
|---|---|---|---|---|
| 0 | 0 | 0 | {} | {"cloth": 200000, "fiber": 329736000, "grain": 392150000, "iron": 200000, "tool": 504000} |
| 60 | 96 | 80 | {"money:silver": 4321} | {"cloth": 199528, "fiber": 322836000, "grain": 84041470, "iron": 200000, "tool": 442000} |
| 120 | 159 | 301 | {"commodity:fiber": 61592553, "money:silver": 99241} | {"cloth": 1566464, "fiber": 370170060, "grain": 347250573, "haul": 45697, "iron": 200000, "tool": 534150} |
| 180 | 264 | 498 | {"commodity:fiber": 58341123, "money:silver": 2330561} | {"cloth": 1323371, "fiber": 367050060, "grain": 298318944, "haul": 37198, "iron": 200000, "tool": 472150} |
| 240 | 354 | 564 | {"commodity:fiber": 71316684, "money:silver": 3074481} | {"cloth": 2498326, "fiber": 387810000, "grain": 408480342, "haul": 62080, "iron": 200000, "tool": 554600} |
| 300 | 421 | 587 | {"commodity:fiber": 67028415, "money:silver": 5113834} | {"cloth": 2306982, "fiber": 385170000, "grain": 372176888, "haul": 49495, "iron": 200000, "tool": 502600} |
| 360 | 500 | 586 | {"commodity:fiber": 85437789, "money:silver": 5918708} | {"cloth": 2143709, "fiber": 403468080, "grain": 471128710, "iron": 200000, "tool": 502600} |
| 420 | 560 | 571 | {"commodity:fiber": 87284109, "money:silver": 19183631} | {"cloth": 1985808, "fiber": 401698080, "grain": 442320375, "iron": 200000, "tool": 473293} |
| 480 | 700 | 579 | {"commodity:fiber": 110984845, "money:silver": 1079767114} | {"cloth": 1828777, "fiber": 419734260, "grain": 540828660, "haul": 75296, "iron": 200000, "tool": 473293} |
| 540 | 792 | 586 | {"commodity:fiber": 115468605, "money:silver": 303399615981} | {"cloth": 1670225, "fiber": 418024260, "grain": 512433051, "iron": 200000, "tool": 453293} |
| 600 | 792 | 588 | {"commodity:fiber": 127711160, "money:silver": 292675039951832} | {"cloth": 1509330, "fiber": 424787100, "grain": 547060231, "haul": 93545, "iron": 200000, "tool": 453293} |

## 8. 历史累计流水（`flows` 逐日求和；**历史累计**窗口）

- Σrepaid（商品/混合口径）= **7069949692**；ΣrepaidMoney（逐币种，毫）= {'silver': 3296153}；ΣtaxPaid=18296328662；ΣinterestDue=511345415149241；ΣnetSurplus=-511415253321950
- 已还（货币腿）逐币种：{'silver': 3296153}
- Σincome（逐商品）= {'cloth': 521790, 'fiber': 72930420, 'grain': 178230012}；Σconsumed = {'cloth': 83149931, 'fiber': 1872000000, 'grain': 51720074059, 'tool': 26136840}

**货物单边核算**（两读数面量纲不同：库存 = 整数单位，flows = 毫 ⇒ 残差不可当守恒判据；本表只作「Δ库存 vs 产-耗」的具名对照）

| 商品 | Σincome(产) | Σconsumed(耗) | 产-耗 | Δ库存(时点差) | 残差 |
|---|---|---|---|---|---|
| cloth | 521790 | 83149931 | -82628141 | 1309330 | 83937471 |
| fiber | 72930420 | 1872000000 | -1799069580 | 95051100 | 1894120680 |
| grain | 178230012 | 51720074059 | -51541844047 | 154910231 | 51696754278 |
| haul | 0 | 0 | 0 | 93545 | 93545 |
| iron | 0 | 0 | 0 | 0 | 0 |
| tool | 0 | 26136840 | -26136840 | -50707 | 26086133 |

## 5b. 逐笔借款 / 池空（TRACE 短轮样本：small-world 0→120，`logLevel=TRACE`）

- 逐笔 `MONEY_MARKET_LOAN`：**357 笔**（48 个市场轮）；金额（毫，本期发生额）min/中位/max = **1 / 78 / 3738**，Σ=**81837**
- 逐轮笔数 min/中位/max = **3 / 7.0 / 24**（day120 = 24 笔）
- `MONEY_MARKET_UNFILLED`：**48 行 = 48/48 轮每轮都有**（`reason=pool-exhausted`）；缺口 `unmetMilli` min/中位/max = 3 / 7171.0 / 134742；已借到 `borrowedMilli` min/中位/max = 13 / 286.0 / 6124
- 利率（写入后，抽样）：[21, 25, 29, 33, 37, 42, 50, 58, 69, 81, 97, 116]
- ★ 口径区分：**"完全借不到"（`demand>0` 且 `lent=0`）在 0→600 的 240 轮里是 0 轮**；**"池被借空、有缺口退赊购"是每轮都发生**（本 48 轮样本 48/48）——两个数不是一回事，不许并排比。
