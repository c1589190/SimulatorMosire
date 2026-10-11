## small-world（sw600，0→600 逐日）

### A 户绝清单（population > 0 → 0 的家户）

- 家户行总数 **178**；运行中真实户绝 **21**；d600 仍活着 **86**；从未有过人口的户槽位 **71**（创世即空的城市槽位）
- 成因（权威判据 = `social.populationEvents` 逐户逐日）：{"death": 1, "transfer": 19, "unexplained": 1}

| 户 id | 归零日 | 死前人口 | 成因 | 当日死/移出 | 阶层 | 死前银钱 | 死前粮 | 死前地权 | 银钱分位 | 户绝前一刻未偿 |
|---|---|---|---|---|---|---|---|---|---|---|
| `hh--2_2-rural-rich_peasant` | 60 | 28 | transfer | 0/26 | rich_peasant | {"silver": 3142} | 10,497 | 0 | 97.2% | 5,371 |
| `hh-0_-2-rural-rich_peasant` | 60 | 30 | transfer | 0/30 | rich_peasant | {"silver": 974} | 2,328 | 0 | 95.5% | 1,263 |
| `hh--1_-1-rural-middle_peasant` | 150 | 62 | transfer | 0/62 | middle_peasant | {} | 2,649,702 | 0 | 0.0% | 305,483 |
| `hh--1_-1-rural-rich_peasant` | 150 | 27 | transfer | 0/27 | rich_peasant | {} | 1,205,941 | 0 | 0.0% | 136,108 |
| `hh--1_0-rural-rich_peasant` | 180 | 25 | transfer | 0/25 | rich_peasant | {} | 570,634 | 0 | 0.0% | 67,850 |
| `hh--1_2-rural-middle_peasant` | 180 | 61 | transfer | 0/61 | middle_peasant | {"silver": 16} | 1,482,466 | 0 | 53.4% | 113,361 |
| `hh--1_2-rural-rich_peasant` | 180 | 26 | transfer | 0/26 | rich_peasant | {"silver": 20} | 621,063 | 0 | 56.2% | 47,553 |
| `hh--2_0-rural-middle_peasant` | 180 | 58 | transfer | 0/58 | middle_peasant | {"silver": 20} | 1,065,108 | 0 | 56.2% | 80,029 |
| `hh--2_0-rural-rich_peasant` | 180 | 28 | transfer | 0/28 | rich_peasant | {"silver": 20} | 445,306 | 0 | 56.2% | 33,700 |
| `hh--2_1-rural-middle_peasant` | 180 | 60 | transfer | 0/60 | middle_peasant | {"silver": 20} | 1,677,534 | 0 | 56.2% | 116,995 |
| `hh--2_1-rural-rich_peasant` | 180 | 27 | transfer | 0/27 | rich_peasant | {} | 647,388 | 0 | 0.0% | 48,072 |
| `hh--2_2-rural-middle_peasant` | 180 | 60 | transfer | 0/60 | middle_peasant | {"silver": 16} | 1,724,352 | 0 | 53.4% | 120,167 |
| `hh-0_-1-rural-middle_peasant` | 180 | 60 | transfer | 0/60 | middle_peasant | {"silver": 20} | 177,277 | 0 | 56.2% | 14,656 |
| `hh-0_-2-rural-middle_peasant` | 180 | 59 | transfer | 0/59 | middle_peasant | {"silver": 5} | 1,379,666 | 0 | 49.4% | 92,979 |
| `hh--1_0-rural-middle_peasant` | 210 | 60 | transfer | 0/60 | middle_peasant | {} | 830,554 | 0 | 0.0% | 142,284 |
| `hh-0_-1-rural-rich_peasant` | 210 | 27 | transfer | 0/27 | rich_peasant | {"silver": 16} | 531,672 | 0 | 60.1% | 52,757 |
| `hh--1_1-rural-middle_peasant` | 240 | 57 | transfer | 0/57 | middle_peasant | {"silver": 16} | 541,651 | 0 | 61.2% | 98,728 |
| `hh-0_-1-rural-landlord` | 330 | 1 | transfer | 0/1 | landlord | {"silver": 12} | 73 | 800,760 | 64.0% | 994,475 |
| `hh--1_-1-rural-landlord` | 390 | 1 | unexplained | 0/0 | landlord | {"silver": 11} | 35,082 | 800,760 | 86.5% | 639,144 |
| `hh--2_1-rural-landlord` | 390 | 1 | transfer | 0/1 | landlord | {"silver": 12} | 86,499 | 752,859 | 87.1% | 895,089 |
| `hh-2_-2-rural-landlord` | 550 | 1 | death | 1/0 | landlord | {} | 0 | 752,859 | 0.0% | 11,153,585,921 |

### B 户绝之后这些债怎么变（同一户的未偿本金，逐时点）

| 户 id | 户绝日 | 户绝前一刻 | +120 | +240 | +360 | +480 | d600 | 倍率 d600/户绝 |
|---|---|---|---|---|---|---|---|---|
| `hh--2_2-rural-rich_peasant` | 60 | 5,371 | 0 | — | — | — | **0** | ×0.00 |
| `hh-0_-2-rural-rich_peasant` | 60 | 1,263 | 18,623 | — | — | — | **46,771** | ×37.03 |
| `hh--1_-1-rural-middle_peasant` | 150 | 305,483 | — | 0 | — | 0 | **0** | ×0.00 |
| `hh--1_-1-rural-rich_peasant` | 150 | 136,108 | — | 0 | — | 0 | **0** | ×0.00 |
| `hh--1_0-rural-rich_peasant` | 180 | 67,850 | — | — | — | 65,661 | **65,661** | ×0.97 |
| `hh--1_2-rural-middle_peasant` | 180 | 113,361 | — | — | — | 318,696 | **318,696** | ×2.81 |
| `hh--1_2-rural-rich_peasant` | 180 | 47,553 | — | — | — | 57,690 | **57,690** | ×1.21 |
| `hh--2_0-rural-middle_peasant` | 180 | 80,029 | — | — | — | 2,587 | **2,587** | ×0.03 |
| `hh--2_0-rural-rich_peasant` | 180 | 33,700 | — | — | — | 0 | **0** | ×0.00 |
| `hh--2_1-rural-middle_peasant` | 180 | 116,995 | — | — | — | 1,881 | **1,881** | ×0.02 |
| `hh--2_1-rural-rich_peasant` | 180 | 48,072 | — | — | — | 0 | **0** | ×0.00 |
| `hh--2_2-rural-middle_peasant` | 180 | 120,167 | — | — | — | 247 | **247** | ×0.00 |
| `hh-0_-1-rural-middle_peasant` | 180 | 14,656 | — | — | — | 287,531 | **287,531** | ×19.62 |
| `hh-0_-2-rural-middle_peasant` | 180 | 92,979 | — | — | — | 36,901 | **36,901** | ×0.40 |
| `hh--1_0-rural-middle_peasant` | 210 | 142,284 | 191,742 | — | — | 229,143 | **229,143** | ×1.61 |
| `hh-0_-1-rural-rich_peasant` | 210 | 52,757 | 42,978 | — | — | 51,358 | **51,358** | ×0.97 |
| `hh--1_1-rural-middle_peasant` | 240 | 98,728 | 6,514 | 7,049 | 7,627 | 7,627 | **7,627** | ×0.08 |
| `hh-0_-1-rural-landlord` | 330 | 994,475 | — | — | 0 | 0 | **0** | ×0.00 |
| `hh--1_-1-rural-landlord` | 390 | 639,144 | — | 0 | 0 | 0 | **0** | ×0.00 |
| `hh--2_1-rural-landlord` | 390 | 895,089 | — | 0 | 0 | 0 | **0** | ×0.00 |
| `hh-2_-2-rural-landlord` | 550 | 11,153,585,921 | 1,716,903,243,129 | 1,716,903,243,129 | 1,716,903,243,129 | 1,716,903,243,129 | **1,716,903,243,129** | ×153.93 |

### C d600 未偿总额拆解（时点快照；按标的分别列，**币种不可相加**）

- 全世界货币量（actor 账合计）：{"copper": 100000, "silver": 269700}
- 活着债务人的 户×标的 组合数 **87**，其中该标的未偿 > 全世界该币种货币量（= 按用户口径**不可偿还**）的 **79** 组

| 桶 | 合同数 | 未偿（按标的） |
|---|---|---|
| ① 债务人已户绝（运行中 >0→0） | 18 | {"money:silver": 1716904349222} |
| ①b 债务人是从未有人口的空户槽位 | 1 | {"money:silver": 2500} |
| ② 债务人活着但未偿 > 全世界货币量（不可偿还） | 506 | {"money:silver": 290958135531232} |
| ③ 正常规模 | 40 | {"commodity:fiber": 127711160, "money:silver": 68878} |
| **扣掉 ①+①b 之后** | — | {"commodity:fiber": 127711160, "money:silver": 290958135602610} |

### D 债权人侧：已户绝家户作为债权人的应收（d600 时点）

- 合同数 **48**，按标的：{"commodity:fiber": 25798354, "money:silver": 2397425359670}

| 合同的债务人 | 本金 | 标的 | 起始日 | 状态 |
|---|---|---|---|---|
| `hh-0_2-urban-landlord` | 1,043,307,982,932 | money:silver | 453 | DEFAULTED |
| `hh-1_-2-rural-poor_peasant` | 303,284,130,530 | money:silver | 423 | DEFAULTED |
| `hh-0_2-rural-poor_peasant` | 263,876,919,510 | money:silver | 393 | DEFAULTED |
| `hh-0_2-urban-landless_laborer-displaced` | 239,287,084,305 | money:silver | 395 | DEFAULTED |
| `hh-1_-2-rural-poor_peasant` | 211,496,208,271 | money:silver | 423 | DEFAULTED |
| `hh-0_-2-rural-landless_laborer-displaced` | 115,048,623,243 | money:silver | 453 | DEFAULTED |
| `hh-1_-1-rural-middle_peasant` | 80,947,071,011 | money:silver | 363 | DEFAULTED |
| `hh--1_-1-rural-landless_laborer-displaced` | 32,167,939,050 | money:silver | 478 | DEFAULTED |

### E d600 未偿额 Top 债务人

| 户 id | 阶层 | 人口 | 活着? | 未偿合计 | 其中标的 | 钱 | 粮 | 地权 |
|---|---|---|---|---|---|---|---|---|
| `hh-0_2-urban-poor_peasant` | poor_peasant | 76 | 是 | 30,096,221,684,231 | {"money:silver": 30096221684231} | {} | 0 | 0 |
| `hh-0_2-urban-landless_laborer-displaced` | landless_laborer | 8 | 是 | 29,641,441,620,882 | {"money:silver": 29641441620882} | {} | 0 | 0 |
| `hh-1_0-rural-poor_peasant` | poor_peasant | 50 | 是 | 27,174,889,209,764 | {"money:silver": 27174889209764} | {} | 0 | 1,788,294 |
| `hh-0_2-rural-poor_peasant` | poor_peasant | 47 | 是 | 20,326,454,600,778 | {"money:silver": 20326454600778} | {} | 0 | 2,169,997 |
| `hh-1_-2-rural-poor_peasant` | poor_peasant | 54 | 是 | 19,120,455,189,728 | {"money:silver": 19120455189728} | {} | 0 | 1,788,294 |
| `hh--2_2-rural-landless_laborer-displaced` | landless_laborer | 7 | 是 | 17,899,928,918,957 | {"money:silver": 17899928918957} | {} | 0 | 0 |
| `hh-2_0-rural-poor_peasant` | poor_peasant | 49 | 是 | 15,466,898,466,677 | {"money:silver": 15466898466677} | {} | 0 | 1,124,836 |
| `hh-2_0-rural-rich_peasant` | rich_peasant | 15 | 是 | 14,518,185,138,121 | {"money:silver": 14518185138121} | {} | 0 | 0 |
| `hh--2_1-rural-landless_laborer-displaced` | landless_laborer | 7 | 是 | 13,242,153,720,503 | {"money:silver": 13242153720503} | {} | 0 | 0 |
| `hh-0_1-rural-poor_peasant` | poor_peasant | 51 | 是 | 12,773,538,751,117 | {"money:silver": 12773538751117} | {} | 0 | 1,877,710 |
| `hh-0_2-rural-middle_peasant` | middle_peasant | 41 | 是 | 8,735,001,315,315 | {"money:silver": 8735001315315} | {} | 0 | 0 |
| `hh--1_-1-rural-landless_laborer-displaced` | landless_laborer | 7 | 是 | 8,379,874,516,867 | {"money:silver": 8379874516867} | {} | 0 | 0 |

### F 利率面（d600 活跃合同的条款利率，‰/周期）

- n=565，min=20，p50=13328，max=317513；=20‰ 的 59 条，>1000‰ 的 457 条，>100000‰ 的 131 条


## three-powers（tp600，0→600 逐日）

### A 户绝清单（population > 0 → 0 的家户）

- 家户行总数 **342**；运行中真实户绝 **14**；d600 仍活着 **189**；从未有过人口的户槽位 **139**（创世即空的城市槽位）
- 成因（权威判据 = `social.populationEvents` 逐户逐日）：{"transfer": 11, "unexplained": 3}

| 户 id | 归零日 | 死前人口 | 成因 | 当日死/移出 | 阶层 | 死前银钱 | 死前粮 | 死前地权 | 银钱分位 | 户绝前一刻未偿 |
|---|---|---|---|---|---|---|---|---|---|---|
| `hh--1_-1-rural-middle_peasant` | 150 | 46 | transfer | 0/46 | middle_peasant | {} | 3,024,243 | 0 | 0.0% | 214,823 |
| `hh--1_-2-rural-middle_peasant` | 210 | 42 | transfer | 0/42 | middle_peasant | {} | 1,480,322 | 0 | 0.0% | 76,015 |
| `hh-1_0-rural-poor_peasant` | 240 | 98 | transfer | 0/98 | poor_peasant | {"silver": 34} | 12,740,150 | 1,150,718 | 63.2% | 1,519,498 |
| `hh-3_0-rural-poor_peasant` | 240 | 70 | transfer | 0/70 | poor_peasant | {} | 9,614,950 | 885,713 | 0.0% | 223,725 |
| `hh-3_0-urban-landlord` | 240 | 1 | transfer | 0/1 | landlord | {} | 2,822 | 0 | 0.0% | 235 |
| `hh--1_-1-rural-landlord` | 270 | 1 | unexplained | 0/0 | landlord | {"copper": 12} | 2,591,538 | 974,367 | 57.3% | 566,280 |
| `hh--2_3-rural-landlord` | 270 | 1 | transfer | 0/1 | landlord | {"gold": 10} | 21,503,343 | 951,991 | 55.8% | 3,020,130 |
| `hh--3_1-rural-landlord` | 270 | 1 | transfer | 0/1 | landlord | {"gold": 10} | 21,511,724 | 951,991 | 55.8% | 3,020,152 |
| `hh-0_-1-rural-landlord` | 270 | 1 | transfer | 0/1 | landlord | {"copper": 10} | 21,624,534 | 951,991 | 55.8% | 3,043,410 |
| `hh-0_2-rural-landlord` | 270 | 1 | transfer | 0/1 | landlord | {"gold": 12} | 1,855,314 | 951,991 | 57.3% | 0 |
| `hh-3_-3-rural-landlord` | 270 | 1 | transfer | 0/1 | landlord | {"copper": 12} | 1,855,374 | 951,991 | 57.3% | 0 |
| `hh-3_0-urban-middle_peasant` | 270 | 126 | transfer | 0/126 | middle_peasant | {} | 50,700 | 0 | 0.0% | 50,120 |
| `hh--3_3-urban-landlord` | 300 | 104 | unexplained | 0/0 | landlord | {} | 235,143 | 0 | 0.0% | 50,966 |
| `hh--3_3-urban-middle_peasant` | 330 | 108 | unexplained | 0/0 | middle_peasant | {"gold": 611} | 244,800 | 0 | 84.8% | 51,826 |

### B 户绝之后这些债怎么变（同一户的未偿本金，逐时点）

| 户 id | 户绝日 | 户绝前一刻 | +120 | +240 | +360 | +480 | d600 | 倍率 d600/户绝 |
|---|---|---|---|---|---|---|---|---|
| `hh--1_-1-rural-middle_peasant` | 150 | 214,823 | 59,232 | — | — | 73,641 | **73,641** | ×0.34 |
| `hh--1_-2-rural-middle_peasant` | 210 | 76,015 | 11,288 | — | — | 13,486 | **13,486** | ×0.18 |
| `hh-1_0-rural-poor_peasant` | 240 | 1,519,498 | 0 | 0 | 0 | 0 | **0** | ×0.00 |
| `hh-3_0-rural-poor_peasant` | 240 | 223,725 | 0 | 0 | 0 | 0 | **0** | ×0.00 |
| `hh-3_0-urban-landlord` | 240 | 235 | 0 | 0 | 0 | 0 | **0** | ×0.00 |
| `hh--1_-1-rural-landlord` | 270 | 566,280 | — | — | 0 | 0 | **0** | ×0.00 |
| `hh--2_3-rural-landlord` | 270 | 3,020,130 | — | — | 0 | 0 | **0** | ×0.00 |
| `hh--3_1-rural-landlord` | 270 | 3,020,152 | — | — | 0 | 0 | **0** | ×0.00 |
| `hh-0_-1-rural-landlord` | 270 | 3,043,410 | — | — | 0 | 0 | **0** | ×0.00 |
| `hh-0_2-rural-landlord` | 270 | 0 | — | — | 0 | 0 | **0** | — |
| `hh-3_-3-rural-landlord` | 270 | 0 | — | — | 0 | 0 | **0** | — |
| `hh-3_0-urban-middle_peasant` | 270 | 50,120 | — | — | 0 | 0 | **0** | ×0.00 |
| `hh--3_3-urban-landlord` | 300 | 50,966 | — | — | 0 | 0 | **0** | ×0.00 |
| `hh--3_3-urban-middle_peasant` | 330 | 51,826 | — | — | 0 | 0 | **0** | ×0.00 |

### C d600 未偿总额拆解（时点快照；按标的分别列，**币种不可相加**）

- 全世界货币量（actor 账合计）：{"copper": 136600, "gold": 129400, "silver": 424600}
- 活着债务人的 户×标的 组合数 **108**，其中该标的未偿 > 全世界该币种货币量（= 按用户口径**不可偿还**）的 **43** 组

| 桶 | 合同数 | 未偿（按标的） |
|---|---|---|
| ① 债务人已户绝（运行中 >0→0） | 2 | {"money:copper": 87127} |
| ② 债务人活着但未偿 > 全世界货币量（不可偿还） | 903 | {"money:copper": 7933139448, "money:gold": 127806182652, "money:silver": 202944395} |
| ③ 正常规模 | 509 | {"commodity:fiber": 226717335, "money:copper": 332843, "money:gold": 315733, "money:silver": 1954437} |
| **扣掉 ①+①b 之后** | — | {"commodity:fiber": 226717335, "money:copper": 7933472291, "money:gold": 127806498385, "money:silver": 204898832} |

### D 债权人侧：已户绝家户作为债权人的应收（d600 时点）

- 合同数 **121**，按标的：{"commodity:fiber": 520163, "money:copper": 1428091450, "money:gold": 158259736, "money:silver": 299863}

| 合同的债务人 | 本金 | 标的 | 起始日 | 状态 |
|---|---|---|---|---|
| `hh-2_-2-rural-poor_peasant` | 1,389,911,902 | money:copper | 275 | DEFAULTED |
| `hh-0_1-rural-middle_peasant` | 158,128,169 | money:gold | 273 | DEFAULTED |
| `hh-0_0-rural-poor_peasant` | 20,694,990 | money:copper | 245 | DEFAULTED |
| `hh-0_0-rural-poor_peasant` | 14,655,908 | money:copper | 243 | DEFAULTED |
| `hh--1_-1-rural-poor_peasant` | 1,224,038 | money:copper | 120 | DEFAULTED |
| `hh-3_-3-rural-poor_peasant` | 759,458 | money:copper | 213 | DEFAULTED |
| `hh--1_-2-rural-landlord` | 520,163 | commodity:fiber | 120 | DEFAULTED |
| `hh-3_-3-rural-poor_peasant` | 428,553 | money:copper | 215 | DEFAULTED |

### E d600 未偿额 Top 债务人

| 户 id | 阶层 | 人口 | 活着? | 未偿合计 | 其中标的 | 钱 | 粮 | 地权 |
|---|---|---|---|---|---|---|---|---|
| `hh--2_3-rural-poor_peasant` | poor_peasant | 40 | 是 | 66,671,470,707 | {"money:gold": 66671470707} | {} | 0 | 2,685,012 |
| `hh--2_2-rural-poor_peasant` | poor_peasant | 40 | 是 | 50,959,664,255 | {"money:gold": 50959664255} | {} | 0 | 1,917,864 |
| `hh-2_-2-rural-poor_peasant` | poor_peasant | 40 | 是 | 6,422,425,020 | {"money:copper": 6422425020} | {} | 0 | 1,490,181 |
| `hh-0_3-rural-middle_peasant` | middle_peasant | 31 | 是 | 2,503,436,735 | {"money:gold": 2503436735} | {} | 0 | 0 |
| `hh--2_2-rural-middle_peasant` | middle_peasant | 31 | 是 | 1,981,825,350 | {"money:gold": 1981825350} | {} | 0 | 0 |
| `hh--2_1-rural-middle_peasant` | middle_peasant | 30 | 是 | 1,543,618,745 | {"money:gold": 1543618745} | {} | 0 | 0 |
| `hh-0_1-rural-middle_peasant` | middle_peasant | 29 | 是 | 1,459,789,073 | {"money:gold": 1459789073} | {} | 0 | 0 |
| `hh-1_-1-rural-poor_peasant` | poor_peasant | 36 | 是 | 1,331,371,518 | {"money:copper": 1331371518} | {} | 0 | 1,917,864 |
| `hh--1_3-rural-middle_peasant` | middle_peasant | 30 | 是 | 1,135,099,921 | {"money:gold": 1135099921} | {} | 0 | 0 |
| `hh--3_2-rural-middle_peasant` | middle_peasant | 31 | 是 | 960,371,437 | {"money:gold": 960371437} | {} | 0 | 0 |
| `hh--3_1-rural-poor_peasant` | poor_peasant | 36 | 是 | 302,843,805 | {"money:gold": 302843805} | {} | 0 | 2,685,012 |
| `hh-0_0-rural-poor_peasant` | poor_peasant | 40 | 是 | 169,069,798 | {"money:copper": 169069798} | {} | 0 | 1,490,181 |

### F 利率面（d600 活跃合同的条款利率，‰/周期）

- n=1414，min=20，p50=493，max=3755；=20‰ 的 156 条，>1000‰ 的 418 条，>100000‰ 的 0 条


