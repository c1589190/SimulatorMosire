# W6 标定读数 —— three-powers（0→600 天）

## 1. 人口曲线（逐 30 天采样；时点快照）与死因（本期发生额）

| day（时点） | 人口(Social) | 人口(Economy) | 逐户不符 | 本期末30天 出生 | 本期末30天 基准死亡(BASE) | 本期末30天 饥荒死亡(BASE_PLUS_FAMINE) | 满足率<1000‰ 户数 | 满足率=0 户数 | 满足率 min | 中位 |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 6350 | 6350 | 6 | 42 | 8 | 150 | 0 | 0 | None | None |
| 30 | 6231 | 6231 | 0 | 31 | 8 | 141 | 118 | 112 | 0 | 0 |
| 60 | 6111 | 6111 | 0 | 31 | 6 | 137 | 132 | 131 | 0 | 0 |
| 90 | 5998 | 5998 | 0 | 33 | 8 | 133 | 145 | 144 | 0 | 0 |
| 120 | 5890 | 5890 | 0 | 25 | 5 | 149 | 146 | 132 | 0 | 0 |
| 150 | 5761 | 5761 | 0 | 30 | 8 | 146 | 162 | 112 | 0 | 0 |
| 180 | 5642 | 5642 | 0 | 27 | 8 | 97 | 120 | 113 | 0 | 0 |
| 210 | 5559 | 5559 | 0 | 23 | 9 | 121 | 115 | 109 | 0 | 0 |
| 240 | 5453 | 5453 | 0 | 28 | 4 | 113 | 117 | 104 | 0 | 0 |
| 270 | 5366 | 5366 | 0 | 31 | 1 | 116 | 117 | 110 | 0 | 0 |
| 300 | 5284 | 5284 | 0 | 30 | 5 | 125 | 117 | 114 | 0 | 0 |
| 330 | 5179 | 5179 | 0 | 20 | 6 | 124 | 114 | 114 | 0 | 0 |
| 360 | 5073 | 5073 | 0 | 27 | 5 | 112 | 114 | 110 | 0 | 0 |
| 390 | 4977 | 4977 | 0 | 32 | 3 | 107 | 121 | 108 | 0 | 0 |
| 420 | 4900 | 4900 | 0 | 30 | 3 | 125 | 121 | 120 | 0 | 0 |
| 450 | 4802 | 4802 | 0 | 19 | 5 | 155 | 121 | 120 | 0 | 0 |
| 480 | 4665 | 4665 | 0 | 27 | 7 | 122 | 121 | 120 | 0 | 0 |
| 510 | 4559 | 4559 | 0 | 21 | 3 | 108 | 123 | 121 | 0 | 0 |
| 540 | 4473 | 4473 | 0 | 14 | 4 | 88 | 126 | 123 | 0 | 0 |
| 570 | 4393 | 4393 | 0 | 27 | 0 | 118 | 129 | 123 | 0 | 0 |
| 600 | 4302 | 4302 | 0 | 0 | 0 | 3 | 131 | 130 | 0 | 0 |

- 历史累计（全 600 天）：出生 **548**、`cause=BASE` 死亡 **106**、`cause=BASE_PLUS_FAMINE` 死亡 **2490** ⇒ 饥荒类占死亡 **95.9%**
- 饥荒强度分布（历史累计）：{'faminePpm=1000': 2488, 'starvationRatio=1000': 2488, 'faminePpm=939': 1, 'starvationRatio=939': 1, 'faminePpm=842': 1, 'starvationRatio=842': 1}

## 2. 断顿户数 / 满足率（`HOUSEHOLD_GAP_REPORTED`，逐日事件）

- 事件行 **600** 天；断顿户数 min/中位/max = **104 / 116.0 / 148**；缺口量 min/中位/max = **195149 / 235863.0 / 352778**（毫）
- 前 10 天样例（day, 断顿户数, 缺口毫, 当日是否开市）：[('1', '148', '352778', 'false'), ('2', '148', '352760', 'false'), ('3', '118', '290653', 'true'), ('4', '118', '290212', 'false'), ('5', '112', '267658', 'true'), ('6', '112', '267287', 'false'), ('7', '112', '266948', 'false'), ('8', '112', '266731', 'true'), ('9', '112', '266522', 'false'), ('10', '112', '266430', 'true')]
| day | 断顿户数 | 缺口毫 | marketRan |
|---|---|---|---|
| 1 | 148 | 352778 | false |
| 51 | 131 | 270456 | false |
| 101 | 146 | 287628 | false |
| 151 | 119 | 276071 | false |
| 201 | 109 | 237435 | false |
| 251 | 110 | 238252 | false |
| 301 | 114 | 238362 | false |
| 351 | 114 | 226492 | false |
| 401 | 120 | 234903 | false |
| 451 | 120 | 228647 | false |
| 501 | 121 | 221483 | false |
| 551 | 122 | 206683 | false |

## 3. 阶层滑落（逐市场轮结算，`CLASS_SLIDE`/`CLASS_SLIDE_ROUND` + 状态序列）

- `CLASS_SLIDE` 事件合计 **25** 条（涉及 **23** 户）；`levels` 字段分布 = {1: 25}（**每轮最多降一级**的实测）
- `CLASS_SLIDE_ROUND` 轮数 **240**；累计 evaluated=46800、slid=25；常量 costPerLevelPerMille=1000 speedPerRatioPerMille=100 roundDays=5

| day（市场轮） | evaluated | slid | skippedNoDebt | skippedCooldown | skippedNoLowerTarget | unpricedDebt |
|---|---|---|---|---|---|---|
| 3 | 203 | 0 | 194 | 9 | 0 | 0 |
| 43 | 203 | 0 | 177 | 26 | 0 | 0 |
| 83 | 203 | 0 | 173 | 29 | 1 | 0 |
| 123 | 203 | 2 | 157 | 14 | 30 | 31 |
| 163 | 202 | 0 | 150 | 6 | 46 | 57 |
| 203 | 202 | 0 | 146 | 4 | 51 | 57 |
| 243 | 198 | 0 | 131 | 4 | 62 | 57 |
| 283 | 191 | 0 | 113 | 5 | 72 | 55 |
| 323 | 190 | 0 | 109 | 1 | 79 | 54 |
| 363 | 189 | 0 | 108 | 2 | 78 | 44 |
| 403 | 189 | 0 | 109 | 1 | 78 | 28 |
| 443 | 189 | 0 | 110 | 0 | 78 | 29 |
| 483 | 189 | 0 | 110 | 0 | 78 | 29 |
| 523 | 189 | 0 | 110 | 0 | 78 | 29 |
| 563 | 189 | 0 | 110 | 0 | 78 | 29 |

- 状态面 `classStandings.currentPositionId`：**23/336** 户在 600 天内换过位置
- 每户出现过的不同位置数分布：{1: 313, 2: 21, 3: 2}
- 序列样例 `hh-0_-3-rural-landlord`：0:tenancy-fixed-kind-landlord → 68:tenancy-fixed-kind-tenant-operator
- 序列样例 `hh--3_3-rural-landlord`：0:tenancy-fixed-kind-landlord → 73:tenancy-fixed-kind-tenant-operator
- 序列样例 `hh-3_0-rural-landlord`：0:tenancy-fixed-kind-landlord → 73:tenancy-fixed-kind-tenant-operator
- 序列样例 `hh--3_3-rural-landless_laborer-displaced`：0:displaced-laborer → 75:displaced-destitute
- 序列样例 `hh-3_0-rural-landless_laborer-displaced`：0:displaced-laborer → 75:displaced-destitute

## 4. 铸币（逐关账日 `MINT_PRODUCTION` 金额 + 国库存量 + 货币总量）

- 本世界 600 天内**没有** `MINT_PRODUCTION` 记录

- 铸币日志事件计数：{}

## 5. 货币市场（逐市场轮：demand/supply/lent/利率 + 借款 + 池空）

- 轮数（`MONEY_MARKET_ROUND`）= **240**；`MONEY_MARKET_SETTLED` = 0；`RATE_WRITTEN` = 240；`UNFILLED`（池空/未满足）= 0；`SKIPPED` = 0；逐笔 `LOAN` = 0
- 需求 min/中位/max = 0 / 61933.0 / 14043415；供给 min/中位/max = 67 / 62191.0 / 90542；实际放贷 min/中位/max = 0 / 17.0 / 47442；笔数 min/中位/max = 0 / 2.0 / 45
- **池空轮次（demand>0 且 lent=0）= 0 / 240**；需求未被完全满足的轮次 = 120

| day | demandMilli | supplyMilli | lentMilli | loans | borrowers | lenders | lent/demand‰ | 当轮成交笔数 |
|---|---|---|---|---|---|---|---|---|
| 3 | 220405 | 14301 | 14301 | 7 | 6 | 4 | 64 | 66 |
| 43 | 4149238 | 1098 | 1098 | 7 | 6 | 4 | 0 | 22 |
| 83 | 8168499 | 2485 | 2485 | 10 | 9 | 4 | 0 | 25 |
| 123 | 14043415 | 33672 | 33672 | 25 | 11 | 17 | 2 | 50 |
| 163 | 9651220 | 1763 | 1763 | 15 | 9 | 9 | 0 | 45 |
| 203 | 6335651 | 755 | 755 | 25 | 11 | 17 | 0 | 44 |
| 243 | 5459805 | 52742 | 27615 | 44 | 28 | 19 | 5 | 51 |
| 283 | 1723253 | 29238 | 2254 | 13 | 2 | 13 | 1 | 40 |
| 323 | 0 | 70477 | 0 | 0 | 0 | 0 | 0 | 40 |
| 363 | 0 | 78070 | 0 | 0 | 0 | 0 | 0 | 44 |
| 403 | 0 | 81099 | 0 | 0 | 0 | 0 | 0 | 35 |
| 443 | 0 | 82601 | 0 | 0 | 0 | 0 | 0 | 24 |
| 483 | 0 | 84829 | 0 | 0 | 0 | 0 | 0 | 37 |
| 523 | 0 | 87214 | 0 | 0 | 0 | 0 | 0 | 32 |
| 563 | 0 | 88914 | 0 | 0 | 0 | 0 | 0 | 33 |

- 利率曲线（时点，逐市场轮写回；抽样）：[(0, 20), (50, 40), (100, 93), (150, 231), (200, 597), (250, 1565), (300, 4139), (350, 1490), (400, 542), (450, 199), (500, 77), (550, 35), (600, 15)]
- `MONEY_MARKET_RATE_WRITTEN` 全 240 轮的 rate 序列（抽样）：3:c-tp-copper=20->21,c-tp-gold=20->21,c-tp-silver=20->21 43:c-tp-silver=36->37,c-tp-copper=36->37,c-tp-gold=36->37 83:c-tp-copper=66->69,c-tp-silver=66->69,c-tp-gold=66->69 123:c-tp-copper=133->139,c-tp-gold=133->139,c-tp-silver=133->139 163:c-tp-copper=279->292,c-tp-silver=279->292,c-tp-gold=279->292 203:c-tp-copper=597->626,c-tp-gold=597->626,c-tp-silver=597->626 243:c-tp-copper=1289->1353,c-tp-gold=1289->1353,c-tp-silver=1057->1005 283:c-tp-copper=2804->2944,c-tp-gold=2804->2944,c-tp-silver=470->447 323:c-tp-copper=2750->2613,c-tp-gold=2251->2139,c-tp-silver=212->202 363:c-tp-copper=1216->1156,c-tp-gold=996->947,c-tp-silver=99->95 403:c-tp-copper=542->515,c-tp-gold=444->422,c-tp-silver=50->48 443:c-tp-copper=242->230,c-tp-gold=199->190,c-tp-silver=28->27 483:c-tp-copper=112->107,c-tp-gold=93->89,c-tp-silver=12->11 523:c-tp-copper=55->53,c-tp-gold=47->45 563:c-tp-copper=31->30,c-tp-gold=27->26
- `MONEY_MARKET_RATE_WRITTEN` 首/末样例：`{"event": "MONEY_MARKET_RATE_WRITTEN", "origin": "economy-debt", "originKind": "tick", "day": "3", "markets": "3", "ratesPerMille": "c-tp-copper=20->21,c-tp-gold=20->21,c-tp-silver=20->21", "effectiveFrom": "next-market-round", "reason": "loan-rate-adapts-to-m` / `{"event": "MONEY_MARKET_RATE_WRITTEN", "origin": "economy-debt", "originKind": "tick", "day": "600", "markets": "2", "ratesPerMille": "c-tp-copper=16->15,c-tp-gold=12->11", "effectiveFrom": "next-market-round", "reason": "loan-rate-adapts-to-money-supply-and-d`

## 6. 市场活跃度（逐市场轮：成交笔数分布 / 零成交轮 / 未成交归因 / 价格路径）

- 轮数 **240**；成交笔数 min/p25/中位/p75/max = 15 / 29 / 34.0 / 42 / 140；**零成交轮次 = 0**（0.0%）
- 未成交槽位 min/中位/max = 688 / 717.0 / 808
- 未成交归因 top 档（历史累计，槽位数）：[('LOGISTICS_CAPACITY', 81565), ('ALGORITHM_UNCOVERED', 43628), ('UNSOLD_SELF_USABLE', 24155), ('NO_BUYER', 13392), ('NO_SELLER', 7990), ('CANNOT_REPRODUCE', 1728), ('OUTCOMPETED', 1599), ('ALL_RESERVED', 1551)]
- `LOGISTICS_CAPACITY` 具名细分（数量）：[('no-haul-service-in-shipping-hex', 155527272084), ('out-of-derived-radius', 23531923243), ('sub-unit-residual', 2468063305), ('capacity-exhausted', 621472246)]
- 开市触发原因分布：{'LOW_GRAIN_STOCK': 120, 'PERIODIC': 120}

| day | trigger | 成交笔数 | 未成交槽 | 归因 top3 |
|---|---|---|---|---|
| 3 | LOW_GRAIN_STOCK | 66 | 774 | LOGISTICS_CAPACITY=366, NO_SELLER=170, NO_BUYER=89 |
| 43 | LOW_GRAIN_STOCK | 22 | 797 | LOGISTICS_CAPACITY=377, NO_SELLER=170, NO_BUYER=89 |
| 83 | LOW_GRAIN_STOCK | 25 | 799 | LOGISTICS_CAPACITY=225, ALGORITHM_UNCOVERED=201, NO_SELLER=170 |
| 123 | LOW_GRAIN_STOCK | 50 | 738 | LOGISTICS_CAPACITY=387, ALGORITHM_UNCOVERED=157, UNSOLD_SELF_USABLE=104 |
| 163 | LOW_GRAIN_STOCK | 45 | 730 | LOGISTICS_CAPACITY=380, ALGORITHM_UNCOVERED=197, UNSOLD_SELF_USABLE=98 |
| 203 | LOW_GRAIN_STOCK | 44 | 732 | LOGISTICS_CAPACITY=370, ALGORITHM_UNCOVERED=185, UNSOLD_SELF_USABLE=122 |
| 243 | LOW_GRAIN_STOCK | 51 | 723 | LOGISTICS_CAPACITY=363, UNSOLD_SELF_USABLE=134, ALGORITHM_UNCOVERED=129 |
| 283 | LOW_GRAIN_STOCK | 40 | 706 | LOGISTICS_CAPACITY=286, ALGORITHM_UNCOVERED=199, UNSOLD_SELF_USABLE=120 |
| 323 | LOW_GRAIN_STOCK | 40 | 706 | LOGISTICS_CAPACITY=389, ALGORITHM_UNCOVERED=156, UNSOLD_SELF_USABLE=102 |
| 363 | LOW_GRAIN_STOCK | 44 | 704 | LOGISTICS_CAPACITY=349, ALGORITHM_UNCOVERED=153, UNSOLD_SELF_USABLE=100 |
| 403 | LOW_GRAIN_STOCK | 35 | 710 | LOGISTICS_CAPACITY=348, ALGORITHM_UNCOVERED=166, UNSOLD_SELF_USABLE=97 |
| 443 | LOW_GRAIN_STOCK | 24 | 720 | LOGISTICS_CAPACITY=348, ALGORITHM_UNCOVERED=216, UNSOLD_SELF_USABLE=98 |
| 483 | LOW_GRAIN_STOCK | 37 | 713 | LOGISTICS_CAPACITY=344, ALGORITHM_UNCOVERED=215, UNSOLD_SELF_USABLE=96 |
| 523 | LOW_GRAIN_STOCK | 32 | 711 | LOGISTICS_CAPACITY=341, ALGORITHM_UNCOVERED=216, UNSOLD_SELF_USABLE=96 |
| 563 | LOW_GRAIN_STOCK | 33 | 710 | LOGISTICS_CAPACITY=341, ALGORITHM_UNCOVERED=214, UNSOLD_SELF_USABLE=97 |

- 价格路径（逐市场轮，抽样 20 轮；时点）：
  - `cloth`（6→0，Δ=-6）：3:6 33:18 63:30 93:44 123:62 153:39 183:27 213:15 243:3 273:0 303:0 333:0 363:0 393:0 423:0 453:0 483:0 513:0 543:0 573:0
  - `fiber`（0→0，Δ=+0）：3:0 33:0 63:0 93:0 123:0 153:0 183:0 213:0 243:0 273:0 303:0 333:0 363:0 393:0 423:0 453:0 483:0 513:0 543:0 573:0
  - `grain`（2→0，Δ=-2）：3:2 33:14 63:26 93:38 123:53 153:35 183:25 213:15 243:23 273:11 303:0 333:0 363:0 393:0 423:0 453:0 483:0 513:0 543:0 573:0
  - `haul`（4→4，Δ=+0）：3:4 33:4 63:4 93:4 123:4 153:4 183:4 213:4 243:4 273:4 303:4 333:4 363:4 393:4 423:4 453:4 483:4 513:4 543:4 573:4
  - `iron`（9→0，Δ=-9）：3:9 33:0 63:0 93:0 123:0 153:0 183:0 213:0 243:0 273:0 303:0 333:0 363:0 393:0 423:0 453:0 483:0 513:0 543:0 573:0
  - `tool`（19→0，Δ=-19）：3:19 33:7 63:0 93:0 123:0 153:0 183:0 213:0 243:0 273:0 303:0 333:0 363:0 393:0 423:0 453:0 483:0 513:0 543:0 573:0

## 7. 债务与货物（辅助读数）

| day | 未偿合同 | 已结清 | 未偿本金（逐介质） | 货物总量（时点） |
|---|---|---|---|---|
| 0 | 0 | 0 | {} | {"cloth": 300000, "fiber": 658080000, "grain": 512337500, "iron": 160000, "tool": 920000} |
| 60 | 217 | 124 | {"money:copper": 1685, "money:gold": 49747, "money:silver": 53953} | {"cloth": 299292, "fiber": 649350000, "grain": 68942397, "iron": 160000, "tool": 858000} |
| 120 | 563 | 286 | {"commodity:fiber": 213393979, "money:copper": 119108, "money:gold": 389430, "money:silver": 396420} | {"cloth": 1840441, "fiber": 797486460, "grain": 901643092, "haul": 2, "iron": 160000, "tool": 935600} |
| 180 | 974 | 389 | {"commodity:fiber": 190305386, "money:copper": 1191543, "money:gold": 1027084, "money:silver": 1101427} | {"cloth": 1532568, "fiber": 793856460, "grain": 715246957, "haul": 2, "iron": 160000, "tool": 872800} |
| 240 | 1454 | 497 | {"commodity:fiber": 215202329, "money:copper": 1217300, "money:gold": 1480040, "money:silver": 1360412} | {"cloth": 2952938, "fiber": 871437060, "grain": 1147129386, "haul": 4202, "iron": 160000, "tool": 950400} |
| 300 | 1744 | 516 | {"commodity:fiber": 165913025, "money:copper": 1995633, "money:gold": 1776532, "money:silver": 1571820} | {"cloth": 2707338, "fiber": 867807060, "grain": 948850288, "haul": 2400, "iron": 160000, "tool": 903900} |
| 360 | 1723 | 521 | {"commodity:fiber": 193501454, "money:copper": 2661045, "money:gold": 3617290, "money:silver": 2320855} | {"cloth": 2543170, "fiber": 893118240, "grain": 1085580801, "haul": 4201, "iron": 160000, "tool": 903900} |
| 420 | 1725 | 521 | {"commodity:fiber": 201318637, "money:copper": 7752536, "money:gold": 28100696, "money:silver": 4913344} | {"cloth": 2272787, "fiber": 890538240, "grain": 996866577, "iron": 160000, "tool": 866837} |
| 480 | 1724 | 522 | {"commodity:fiber": 209451865, "money:copper": 58662569, "money:gold": 415589333, "money:silver": 14623076} | {"cloth": 2100139, "fiber": 890538240, "grain": 991620310, "haul": 18602, "iron": 160000, "tool": 866837} |
| 540 | 1724 | 522 | {"commodity:fiber": 217913674, "money:copper": 638002640, "money:gold": 7092819928, "money:silver": 52589751} | {"cloth": 1890187, "fiber": 888258240, "grain": 919770345, "haul": 1401, "iron": 160000, "tool": 852237} |
| 600 | 1724 | 522 | {"commodity:fiber": 226717335, "money:copper": 7933559418, "money:gold": 127806498385, "money:silver": 204898832} | {"cloth": 1723767, "fiber": 888258240, "grain": 914936849, "haul": 1, "iron": 160000, "tool": 852237} |

## 8. 历史累计流水（`flows` 逐日求和；**历史累计**窗口）

- Σrepaid（商品/混合口径）= **6328795648**；ΣrepaidMoney（逐币种，毫）= {'copper': 5507591, 'gold': 8755281, 'silver': 10308562}；ΣtaxPaid=0；ΣinterestDue=1358119836314；ΣnetSurplus=-1465722364437
- 已还（货币腿）逐币种：{'copper': 5507591, 'gold': 8755281, 'silver': 10308562}
- Σincome（逐商品）= {'cloth': 439284, 'fiber': 174780420, 'grain': 302317560, 'haul': 538200}；Σconsumed = {'cloth': 80904054, 'fiber': 2350800000, 'grain': 107904845683, 'tool': 21381540}

**货物单边核算**（两读数面量纲不同：库存 = 整数单位，flows = 毫 ⇒ 残差不可当守恒判据；本表只作「Δ库存 vs 产-耗」的具名对照）

| 商品 | Σincome(产) | Σconsumed(耗) | 产-耗 | Δ库存(时点差) | 残差 |
|---|---|---|---|---|---|
| cloth | 439284 | 80904054 | -80464770 | 1423767 | 81888537 |
| fiber | 174780420 | 2350800000 | -2176019580 | 230178240 | 2406197820 |
| grain | 302317560 | 107904845683 | -107602528123 | 402599349 | 108005127472 |
| haul | 538200 | 0 | 538200 | 1 | -538199 |
| iron | 0 | 0 | 0 | 0 | 0 |
| tool | 0 | 21381540 | -21381540 | -67763 | 21313777 |
