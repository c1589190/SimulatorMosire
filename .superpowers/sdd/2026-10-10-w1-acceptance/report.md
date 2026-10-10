# W1 验收报告 —— 市场状态与账（`50e9817e`）

- **角色**：W1 验收 Agent（**只读验收** —— 不改仓库任何源码/测试/文档；装置、脚本、store 全落 `/tmp/w1acc*`）
- **时间**：2026-10-11 01:57 – 02:15（CST）
- **判据来源**：`docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` v3.3 **§6.1 W1 行** + **§6.2 G4/G5**
- **结论**：**G5 绿、G4 绿**（依据见 §6）。附带两个必须看的发现：**§7.1 既存世界缺陷（非 W1 回归）**、**§7.2 W1 新增日志口径缺陷**。

---

## 0. 一句话摘要

两侧各从创世新 store 起 19 格 `small-world`，同一参数跑一次 `POST /api/advance 0→360`：

- **形状投影后，全状态树逐字节相同**（8 个 module namespace 的规范 JSON md5 逐个相同，5,120,813 字节，`458574ca19e2a954d59339615146f291`）；
- 逐项语义量（人口 / 逐格库存 / 逐户货币 / 债务合同 / flows / 逐格价格 / 阶层）**逐值相同**；
- **逐笔成交 6664 笔，两侧整行逐字节相同**（去掉行首时间戳后 0 行差异，量/额逐值相同）；
- HTTP 读口（19 格 × `economy/hex` + `economy/ownership`）**只有 38 处注释文案差异**（`Market`→`MarketTable`），数值 0 差异；
- `advance(0→360)` **Revision Δ = 1**（`revisions` 表 2 行：创世 + 一次推进）；`three-powers 0→3600`（10 年）单次推进 **Δ 仍 = 1**；
- 耗时：改后中位 **7.514 s** vs 改前 **7.341 s** ⇒ **中位劣化 +2.36%、最大劣化 +1.84%**（≤10% 红线内；同批样本波动 ±20% 量级，见 §4）。

---

## 1. 装置

### 1.1 两个 jar（都从 HEAD/基线**新构建**，`-DskipTests package`）

| | 改前（baseline） | 改后（HEAD） |
|---|---|---|
| 代码 | `323b02e3`（worktree `/tmp/w1acc-pre`） | `50e9817e`（主工作树 HEAD） |
| 构建 | `cd /tmp/w1acc-pre && tools/mvn-lock.sh -DskipTests package -pl simos-app -am` | `cd /home/cna/SimulatorMosire && tools/mvn-lock.sh -DskipTests package -pl simos-app -am` |
| 结果 | BUILD SUCCESS，16/16，24.8 s（`evidence/build-pre.log`） | BUILD SUCCESS，3.1 s（增量，`evidence/build-post.log`） |
| shaded jar | `/tmp/w1acc-pre/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar` | `/home/cna/SimulatorMosire/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar` |
| **md5** | **`820c705e382329a65a41c7d84ed83636`** | **`92f92ba05e46a54430d0b976345a87bc`** |

**基线有效性核对（重要）**：`323b02e3` 不是 W1 的直接父提交，但

```
git diff --stat 323b02e3 50e9817e^
  AGENTS.md                                              | 18 ++++++++++++++++++
  ...-10-unified-price-and-ordered-settlement-design.md  | 19 +++++++++++++++---
git diff --name-only 323b02e3 50e9817e^ | grep -E "src/|pom.xml"   # 空
```

⇒ 中间两个提交是**纯文档**，`323b02e3` 与 `50e9817e^` 的**代码逐字节相同**，可作"改前"基线。
（主工作树 `git status --porcelain` 开工与收尾均为空；全程未改任何源码/测试/文档。）

### 1.2 启动命令（两侧同一形态；`tools/run-shaded.sh` 把 jar 复制成进程独占快照）

```bash
bash tools/run-shaded.sh <shaded.jar> \
  --store /tmp/w1acc/store-<tag> \
  --world=small-world \
  --config /tmp/w1acc/shell.json \
  --gui-port <P> --mcp-port <P+1> --approval-port <P+2>
```

配置文件（两侧同一份，`checkpointInterval=1` 是**为了让每次推进都落一份全量 checkpoint 供读数**；默认值是 100）：

```json
{ "world": "small-world", "mapId": "small-world", "checkpointInterval": 1 }
```

推进（`GuiServer`，同步返回，墙钟含结算 + 落盘）：

```bash
curl -s -X POST http://127.0.0.1:<P>/api/advance -H 'Content-Type: application/json' \
     -d '{"branch":"main","expectedRevision":1,"from":0,"to":360}'
# → 200 {"result":"committed","ref":{"branch":"main","revision":2}}
```

### 1.3 dump 装置（可复现脚本，含 md5）

| 脚本 | 作用 | md5 |
|---|---|---|
| `/tmp/w1acc/runner.py` | 起服务 → 等 GUI 就绪 → POST advance（计时）→ 读 `revisions` 表 + checkpoint 全量信封 → 收进程 | `0456e09961f2bb8a99952d1b61e417a2` |
| `/tmp/w1acc/project.py` | 形状投影 ⇒ 跨版本可比的规范状态（CCS） | `62d420dec1bcd4e66a8e4cb901d738dd` |
| `/tmp/w1acc/diff.py` | 两份 CCS 的逐叶深比对（JSON-pointer 路径 + 前/后值） | `55da8cac7a6f8f6b711a780b8045d007` |
| `/tmp/w1acc/summary.py` | 逐项语义量表（人口/库存/货币/债务/flows/价格/阶层 + 各类 md5） | `fb9f59062377db5f9161cc79170018c5` |
| `/tmp/w1acc/fillcmp.py` | 逐笔成交（`MARKET_FILL` TRACE 行）两侧对拍 | `a86eb007ea44f17b48ccd282bff78080` |
| `/tmp/w1acc/bounded.py` | 逐组件字节/叶数 + 统计簿窗口槽位 | `195de6b71738922df59f6e6af3bfcf3e` |
| `/tmp/w1acc/shell.json` / `shell-three-powers.json` | 世界配置 | `e916f0908d7e9cd76986829869c0edee` / `891ad6827e26fdf28c9d50e337db2bd4` |

**读数来源（不是猜的）**：
① **落盘全量快照** = `<store>/checkpoints/main/<rev>.json`（`CheckpointEncoder` 产出的完整状态信封，`modules` 段是各 namespace 的 JSON 文本）；
② `revisions` 表真数（行数/`tick`/`command_type`/`changeset_json` 字节）；
③ HTTP 读口（`/api/economy/hex`、`/api/economy/ownership`，逐格）；
④ 服务进程 stdout 的具名事件日志（TRACE 档逐笔成交）。
本报告所有脚本副本已随账本落盘：`apparatus/`（md5 与 `/tmp` 原件逐一 `md5sum -c` 校验一致），原始产物在 `evidence/`。

### 1.4 运行清单（store / 端口；一次只跑一个实例）

| tag | 侧 | world | days | store | gui/mcp/approval | jar md5 | RevΔ | 就绪 |
|---|---|---|---|---|---|---|---|---|
| pre-r1..r5 | 改前 | small-world | 360 | `/tmp/w1acc/store-pre-r1..r5` | 10411/10431/10441/10451/10461(+1,+2) | `820c705e…` | 1 | 1.17–1.70 s |
| post-r1..r6 | 改后 | small-world | 360 | `/tmp/w1acc/store-post-r1..r6` | 10311/10331/10341/10351/10361/10401 | `92f92ba0…` | 1 | 1.69–1.71 s |
| pre-c1 / post-c1 | 两侧 | small-world | 360 | `store-pre-c1` / `store-post-c1` | 10471 / 10371 | 各自 | 1 | 1.69 s |
| post-b500（成功）/ post-b510、post-b720、post-b10y、post-b10y2（全 400） | 改后 | small-world | 500 / 510 / 720 / 3600 | `store-post-b500` / `store-post-b510` / `store-post-b720` / `store-post-b10y2` | 10393 / 10396 / 10385 / 10381 / 10391 | `92f92ba0…` | 1 / 400 | 1.69–2.98 s |
| pre-b10y | 改前 | small-world | 3600 | `store-pre-b10y` | 10481 | `820c705e…` | **400** | — |
| post-tp360 / post-tp10y / post-tp3610 | 改后 | three-powers（37 格 / 3 区 / 3 币） | 360 / 3600 / 3610 | `store-post-tp360` / `store-post-tp10y` | 10396 / 10395 / 10397 | `92f92ba0…` | 1 / 1 / 1 | 1.69–1.88 s |

- 一次只有一个世界实例、一个 Maven（`tools/mvn-lock.sh` 串行）；端口全在 103xx/104xx 段，启动前逐个探测占用，占用即拒绝。
- 未覆盖任何运行中服务的 jar（`run-shaded.sh` 复制成 `/tmp/simos-shaded-XXXXXX.jar` 进程独占快照）。

### 1.5 机器

`Intel(R) Core(TM) Ultra 9 275HX` / `nproc=8` / 11 GB / OpenJDK `21.0.12.1`（Ubuntu 24.04）。两侧同机、同时段、交替成块跑；`/proc/loadavg` 在 2.6–3.5 区间漂移（见 §4 的波动说明）。

---

## 2. 逐值对照表（改前 / 改后 / 是否相等）

口径：两侧都取 **tick=360、branch=main、revision=2** 的全量 checkpoint；严格等值 = 规范 JSON（键排序、无空白）逐字节相同。

| # | 语义量 | 改前值 | 改后值 | 相等 |
|---|---|---|---|---|
| ① | **人口**（social 逐户成员求和） | **4925**（215 户） | **4925**（215 户） | ✅ |
| ① | 人口（economy `classes[].population` 求和） | 4925 | 4925 | ✅ |
| ① | 逐格人口（19 格） | `md5 267a5bcce7c8…` | 同 | ✅ |
| ② | **逐格库存**（actor 侧 `HouseholdInventory.balances` 汇总） | 粮 3,278,080,223 / 布 15,838,080 / 纤维 1,042,998,120 / 工具 554,697 / 铁 200,000 | 逐值同 | ✅ |
| ② | 逐格×逐商品（19×6，含 haul） | `md5 1fb668d70539…` | 同 | ✅ |
| ② | 逐户库存（215 户 × 逐商品） | `md5`（见 `evidence/summary-r1.json`） | 同 | ✅ |
| ③ | **逐户货币余额**（actor `money`，逐币种） | silver **273,200** / copper **100,000**；有余额户 **72/215** | 逐值同 | ✅ |
| ③ | 逐户货币映射 md5 | `md5 99c88fc61f9c…` | 同 | ✅ |
| ④ | **债务合同**条数 | **1001**（NORMAL 136 / SETTLED 713 / DEFAULTED 152） | 1001（同分布） | ✅ |
| ④ | 未偿本金（按单位，**不跨单位相加**） | 粮 `105,558,159` / 银 `24,488` / 纤维 0 / 工具 0 | 逐值同 | ✅ |
| ④ | 逐合同明细（id/debtor/creditor/unit/principal/openedDay/status）md5 | `8812bd5c734e…` | 同 | ✅ |
| ④ | `householdDebtRefs` 条数 / md5 | 1001 / `0d869112d059…` | 同 | ✅ |
| ⑤ | **成交（逐笔 笔数/量/额）** — `MARKET_FILL` TRACE（两侧同开 TRACE） | **6664 笔**（grain 3747 / cloth 2561 / tool 356） | **6664 笔**（同分布） | ✅ |
| ⑤ | 成交**量**（毫单位） | grain 89,056,601 / cloth 898,213 / tool 78,066 | 逐值同 | ✅ |
| ⑤ | 成交**货款**（`goodsPaymentMilli`，逐币种） | grain 91,130 / cloth 5,904 / tool 1,023（全 silver） | 逐值同 | ✅ |
| ⑤ | 逐笔整行（去时间戳） | 6664 行 | **0 行不同，全序列逐字节相同** | ✅ |
| ⑤ | 逐户 flows（market 面的持久量：income/consumed/taxPaid/interestDue/newBorrowing/repaid/netSurplus/unmetNeed/deaths/births/repaidMoney/capitalizedArrears） | 世界合计 `{consumed:{cloth:1380736, grain:423177558, fiber:6300000, tool:50000}, unmetNeed:{cloth:16346}, taxPaid:1128095502, interestDue:4741872, newBorrowing:226127, repaid:216778969, netSurplus:-1123570048, income:{grain:432444884, fiber:101704500, cloth:190657}}` | 逐值同（215 户逐户 md5 同） | ✅ |
| ⑥ | **逐格价格**（改前逐格表 vs 改后"该格所属区的表"投影到逐格） | 19 格全部 `{grain:1, cloth:5, fiber:1, tool:9, iron:9, haul:4}`，numeraire=silver | 逐格同 | ✅ |
| ⑥ | 逐格价格 md5 | `de3b1edbf7812e86bf52c6646a21183a` | **同** | ✅ |
| ⑦ | **阶层分布**（economy `classes[].view` 的 stratum） | landlord 52 / rich_peasant 61 / middle_peasant 38 / poor_peasant 38 / landless_laborer 23 / artisan 2 / official 1 | 逐值同 | ✅ |
| ⑦ | `classStandings`（currentPositionId/original/stress） | `md5`（见 `summary-r1.json`） | 同 | ✅ |
| ⑧ | **全状态（形状投影后）** | **5,120,813 字节 / md5 `458574ca19e2a954d59339615146f291`** | **同** | ✅ |
| ⑧ | 逐 namespace md5 | actor `3641289eac0de9c8`、army `f3071f2a16269bef`、economy `eb190c78b8874fca`、gov `549a004125bd824f`、map `7f42aa8fbdb69b20`、sd `182b681a89344209`、social `d3ea3d9c8248218a`、unit `a399ccef1d9cad3b` | **8/8 逐个相同** | ✅ |

补充证据：

- **确定性**：改后 **7/7** 次独立运行（post-r1..r6 + post-c1〔TRACE 档〕）的 checkpoint `2.json` md5 **全为 `c9320a664dcd72d5c2d0e4eb199755db`**；改前 **6/6** 次（pre-r1..r5 + pre-c1〔TRACE 档〕）**全为 `bcab35f0f203a0e8f32493cd4f4e2564`**（两侧不同只因形状）。**投影后 4 份样本（pre/post × INFO/TRACE）md5 全为 `458574ca…`** ⇒ 版本无关 + 日志档位不改状态 + 跨轮确定。
- **HTTP 读口对拍**（`evidence/readouts-face.txt`）：19 格 × 2 视图共 **8,521,718 字节**读口中差异叶 **38** 个，全部是同一条注释文案 `…（Market 只服务商品现货…）` → `…（MarketTable 只服务商品现货…）`；`population`/`goods`/`actorMoneyTotal`/`debtCount`/`debtPrincipalByUnit`/`market.prices`/`classes` 等**逐格逐值相同**。样例（格 `0_0`）：population 1007/1007、goods `{cloth:3299480, fiber:75385603, grain:140247925, haul:119806, iron:189111, tool:139683}` 两侧同、actorMoneyTotal `{copper:100000, silver:113597}` 同、market `{numeraire:silver, prices:{cloth:5,fiber:1,grain:1,haul:4,iron:9,tool:9}}` 同。

---

## 3. 差异归因（逐类；**状态面语义差异 = 无**）

对全仓改动做"形状投影 + 逐叶深比对"后，**语义差异叶 0**。所有非零差异逐类如下（都在任务书白名单 ①②③ 内）：

| 差异 | 类别 | 归因 | 白名单 |
|---|---|---|---|
| `economy.data.markets`：19 键（hex `"q_r"`）→ `economy.data.marketTables`：1 键（`"single-region"`，内含 `hexes[19]` + `prices`） | ① 容器形状 | W1 设计本体（逐格价表退役 / 每区一张） | ✅ |
| 新组件 `economy.data.marketRoundStats`（改前不存在） | ② 新增组件 | W1 判据②（第 40 个组件） | ✅ |
| 新事件 `MARKET_ROUND_STATS_RECORDED` ×81、`MARKET_ROUND_STATS_DETAIL` ×81 | ③ 新增事件 | 新组件的 INFO/DEBUG 日志（每市场轮一条） | ✅ |
| 计数口径字段：`markets=19`→`markets=1`（`DAY_START`/`SETTLEMENT_START_DETAIL`/`ECONOMY_ADVANCE_START`）、`markets=19`→`marketTables=1`（`MARKET_ROUND_START`）、`pricedMarkets=19`→`1`（`HAUL_SERVICE_PRODUCED`×3）、`finalMarkets=19`→`1`（`ECONOMY_ADVANCE_END`） | ③ 日志字段 | 同一世界的"市场计数"单位由**逐格**改为**每区/每表** | ✅ |
| 自由文本类型名 `Market.prices[haul]`→`MarketTable.prices[haul]`（446 行日志）、读口注释 38 叶 | ③ 日志/文案 | 类型改名，非状态 | ✅ |
| 启动行 `store=/port=`、`ADVANCE_END` 的 `commandId/correlationId`（每次请求新 UUID）、`GUI_ACCESS` 计数 15 vs 14 | 装置 | 两次不同进程 / 我的就绪轮询 | ✅（非语义） |

**事件面全量对拍**（`evidence/event-face-diff.txt`，372,654 / 372,815 个不同事件行）：
事件名集合差仅 `{MARKET_ROUND_STATS_DETAIL, MARKET_ROUND_STATS_RECORDED}`；同名事件**计数逐个相同**；把"装置差异 + 市场计数口径 + 类型名文本"归一后，**残余独有行各 1 条**，即 `ADVANCE_END`（只差随机 `commandId`）。

**真数值漂移：无。** 没有需要"归因到漂移"的项。

---

## 4. 性能表（一次 `advance(0→360)`，创世新 store，两侧同参）

墙钟 = `POST /api/advance` 全程（服务端结算 + 一次 revision 落盘 + checkpoint 写盘）。原始数字：

| 轮次 | 改前 (s) | 改后 (s) |
|---|---|---|
| 1 | 8.791（pre-r1） | 7.364（post-r1，`result.json` 因首轮 runner 缺陷未落盘，值取自 runner stdout 实录） |
| 2 | 7.092 | 6.998 |
| 3 | 7.184 | 8.761 |
| 4 | 7.341 | 8.953 |
| 5 | 8.560 | 7.479 |
| 6 | —— | 7.514 |

**主口径（各 5 次落盘样本）**

| 侧 | n | 原始 | 中位 | 最大 | 最小 | 均值 |
|---|---|---|---|---|---|---|
| 改后 post(r2..r6) | 5 | 6.998 / 7.479 / 7.514 / 8.761 / 8.953 | **7.514** | **8.953** | 6.998 | 7.941 |
| 改前 pre(r1..r5) | 5 | 7.092 / 7.184 / 7.341 / 8.560 / 8.791 | **7.341** | **8.791** | 7.092 | 7.794 |

- **中位劣化 +2.36%；最大劣化 +1.84%；均值劣化 +1.89%** ⇒ **≤10% 红线内**。
- 附口径（改后含 post-r1 共 6 次）：中位 7.497 vs 7.341 ⇒ **+2.12%**。
- ★ **样本内波动 ±20% 量级**（改前 7.092→8.791，改后 6.998→8.953），与 B5 台账记录一致；因此**不能**把 ±3% 当作真实劣化/改善的证据，只能说"未观察到 >10% 的劣化"。报告原始数字，不做平滑。

**Revision Δ（落盘真数，`revisions` 表）**

| 场景 | 推进 | revisions 表 | Δ |
|---|---|---|---|
| small-world 0→360（全部 13 次：改后 7 + 改前 6） | 1 次 `core.AdvanceTime` | `(main,1,tick 0,core.Bootstrap,67B)` + `(main,2,tick 360,core.AdvanceTime,4,789,743B/4,789,388B)` | **1** |
| three-powers 0→3600（**10 年**）单次 | 1 次 | `(main,1,tick 0)` + `(main,2,tick 3600,22,161,321B)` | **1** |
| three-powers 3600→3610（续跑 10 天） | 1 次 | +`(main,3,tick 3610,3,393,352B)` | **1** |

⇒ **Δ 不随天数增长**（10 年也是 Δ=1），checkpoint 每 revision 一份（`checkpointInterval=1` 是我们的读数参数，默认 100）。

**"新路径无逐日存盘/无自建 save"静态证据**：W1 改动共 **63 个 `src/main/*.java`**，对它们 grep `java.nio.file|Files\.|SqliteStore|CheckpointStore|checkpoints\.|\.persist\(|\.save\(|\bsave\(` ⇒ **0 命中**（新统计簿是**同一 revision 的状态写入**，见 `EconomySettlement` 里 `session.sheet().marketRoundStats()` 的就地覆盖写）。

---

## 5. 统计簿读数样例 + 状态有界

### 5.1 真实 world 按区按商品读到的一轮 D/S/成交量/均价/未满足量（原文）

改后 `small-world` day 360、revision 2 的**落盘 checkpoint 原文**（`economy.data.marketRoundStats`；原始文件 `/tmp/w1acc/store-post-r1/checkpoints/main/2.json`，同形文件 `/tmp/w1acc/store-post-c1/checkpoints/main/2.json`）：

```json
{ "single-region": {
    "lastRoundDay": 360,
    "lastRound": {
      "grain":  { "demandMilli": 10586, "supplyMilli": 1187099795, "tradedMilli": 446286, "averagePriceMilli": 1, "unmetMilli": 0 },
      "cloth":  { "demandMilli": 1081,  "supplyMilli": 15230608,   "tradedMilli": 4730,   "averagePriceMilli": 5, "unmetMilli": 0 },
      "fiber":  { "demandMilli": 0,     "supplyMilli": 1041286204, "tradedMilli": 0,      "averagePriceMilli": 0, "unmetMilli": 0 },
      "tool":   { "demandMilli": 0,     "supplyMilli": 474956,     "tradedMilli": 0,      "averagePriceMilli": 0, "unmetMilli": 0 },
      "iron":   { "demandMilli": 0,     "supplyMilli": 200000,     "tradedMilli": 0,      "averagePriceMilli": 0, "unmetMilli": 0 }
    },
    "lastRoundTurnoverMilli": 521,
    "cycleToDate": {}, "cycleTurnoverMilli": 0,
    "previousCycle": {
      "grain":  { "demandMilli": 96049, "supplyMilli": 1977901776, "tradedMilli": 4404009, "averagePriceMilli": 1, "unmetMilli": 0 },
      "cloth":  { "demandMilli": 15803, "supplyMilli": 73185956,   "tradedMilli": 139661,  "averagePriceMilli": 5, "unmetMilli": 0 },
      "tool":   { "demandMilli": 35,    "supplyMilli": 3016188,    "tradedMilli": 11643,   "averagePriceMilli": 9, "unmetMilli": 0 },
      "fiber":  { "demandMilli": 0,     "supplyMilli": 6025248628, "tradedMilli": 0,       "averagePriceMilli": 0, "unmetMilli": 0 },
      "iron":   { "demandMilli": 0,     "supplyMilli": 1400000,    "tradedMilli": 0,       "averagePriceMilli": 0, "unmetMilli": 0 }
    },
    "previousCycleTurnoverMilli": 5601 } }
```

对应的 INFO 原文（同一轮，`post-c1` 日志）：

```
02:03:44.308 INFO  io.mosire.simos.economy.market - event=MARKET_ROUND_STATS_RECORDED origin=economy-market originKind=tick day=360 markets=1 commodities=5 tradedMilli=451016 cycleClosed=true window=last-round+cycle-to-date+previous-cycle
02:03:44.308 DEBUG io.mosire.simos.economy.market - event=MARKET_ROUND_STATS_DETAIL origin=economy-market originKind=tick day=360 marketId=single-region stats=day=360 本轮商品=5 本轮成交额=521 本周期商品=0 本周期成交额=0 上周期商品=0 上周期成交额=0
```

多区样例（three-powers，3 区 3 币，day 3600，`/tmp/w1acc/store-post-tp10y/checkpoints/main/2.json`）：`c-tp-copper` / `c-tp-gold` / `c-tp-silver` 各 5 商品，`lastRoundDay=3600`，`lastRound`/`previousCycle` 各 5 商品 —— 说明统计簿**按键（区）分别成账**，不是单区硬编码。

**判别力自证（统计簿 × 逐笔成交交叉核对，`evidence/stats-crosscheck.txt`）**：不是"打印了一个空表" ——
从**另一条独立通路**（TRACE 逐笔 `MARKET_FILL`）统计 day=360 轮：`{grain: 446286（39 笔）, cloth: 4730（51 笔）}`，
与持久 `lastRound.tradedMilli`（grain 446,286 / cloth 4,730 / 其余 0）**逐商品相等、Σ 相等（451,016）**，
也等于 INFO 行的 `tradedMilli=451016`；且 `averagePriceMilli` 与该区价表 `{grain:1, cloth:5, tool:9}` 逐商品吻合。

### 5.2 状态有界（窗口机制 + 数字）

窗口机制（代码，`model/MarketRoundStats.withRound`）：**最近一轮明细 + 本周期累计 + 上周期累计**，**三段覆盖写**（`cycleClosed` 时把本周期轮转成上周期并清空 `cycleToDate`），每个市场的键恒为 7 个标量/表项 ⇒ **大小只与"商品数 × 周期相位"有关，与推进天数无关**。写入口在 `EconomySettlement`：同一个 advance 的工作副本里就地覆盖，**不产生额外 revision**（与 §4 的 Δ=1 互证）。

数字（`evidence/bounded.txt`）：

| world | tick | 市场数 | marketRoundStats 字节 / 叶 | 每市场键 | 每市场叶 | 备注 |
|---|---|---|---|---|---|---|
| three-powers | 360 | 3 | **4,018 B / 366 叶** | 7 | 121–122 | 关账轮（`cycleToDate` 空） |
| three-powers | **3600（10 年）** | 3 | **4,015 B / 366 叶** | 7 | 121 | 关账轮；**与 360 天逐值同形**（差 3 字节 = 日号位数） |
| three-powers | 3610（10 年 + 10 天） | 3 | 5,764 B / 531 叶 | 7 | **176** | 周期中段；与 small-world 中段同形 |
| small-world | 360 | 1 | 1,352 B / 122 叶 | 7 | 122 | 关账轮 |
| small-world | 500 | 1 | 1,900 B / 177 叶 | 7 | **177** | 周期中段（`lastRound`+`cycleToDate`+`previousCycle` 各 5 商品） |

⇒ 叶数在 **122（关账相位）↔ 177（中段相位）** 之间按周期相位摆动，**10 年推进后仍回到 121–122 叶/市场**；作为对照，同期整个 economy 载荷从 4.96 MB 涨到 11.09 MB（`debtContracts` 2.06→5.91 MB、`householdDebtRefs` 0.82→2.39 MB）——**增长来自别的组件，不来自 W1 的统计簿**。

---

## 6. 结论

| 判据 | 结论 | 依据（一句话 + 数字/文件） |
|---|---|---|
| **G5**（W1 不许改数值行为） | **绿** | 形状投影后**全状态树 8/8 namespace 规范 md5 逐个相同**（`458574ca19e2a954d59339615146f291`，4 份样本：两侧 × INFO/TRACE）；逐项语义量（人口 4925、逐格库存 5 商品、逐户货币 silver 273200/copper 100000、债务 1001 条、flows 世界合计、19 格价格、7 个阶层）逐值相同；**逐笔成交 6664/6664 行逐字节相同**；读口 8.52 MB 视图仅 38 处注释文案差异、数值 0 差异。差异白名单外**无任何差异**。 |
| **G4**（性能红线） | **绿** | `advance(0→360)` **Revision Δ = 1**（revisions 表 2 行；three-powers `0→3600` 10 年也是 Δ=1）；耗时改后中位 **7.514 s** vs 改前 **7.341 s** ⇒ **+2.36%**，最大 **8.953 vs 8.791** ⇒ **+1.84%**（≤10%）；63 个改动的 main 文件里**落盘/save/checkpoint 调用 0 命中**。 |
| 附带：`marketRoundStats` 真有数 | **绿** | §5.1 原文：逐区逐商品 D/S/成交量/均价/未满足量 + 逐区成交额，day 360/3600 均有真值；INFO `MARKET_ROUND_STATS_RECORDED` 每轮一条。 |
| 附带：状态有界 | **绿** | §5.2：three-powers **10 年后每市场仍 121 叶**（与 360 天同形）；中段相位 176–177 叶封顶；窗口 = 最近一轮 + 本周期 + 上周期（覆盖写）。 |

---

## 7. 发现与上报

### 7.1 ★ `small-world` 单次推进在 day≈510 被不变量拒绝（**改前改后完全同一条 ⇒ 非 W1 回归**）

**现象**：`POST /api/advance {from:0,to:N}`，实测 `N = 510 / 720 / 3600` 全部 HTTP 400（`N ≥ 510` 未逐值枚举，`501–509` 未测）：

```json
{"error":"OwnershipStakeBook 的 Σ活跃质押必须 ≤ 份额 quantity：份额=share-farm@0_2-LAND-HOUSEHOLD-hh--2_2-rural-landlord-HOUSEHOLD-hh--2_2-rural-landlord-OWNED-0 质押合计=13，终态数量=12"}
```

**两侧同一条**（改前 `820c705e…` 与改后 `92f92ba0…` 的报文逐字节相同），日志最后处理到的 `day` 都是 **510**（`DEBT_STRESS_BRANCH_FIRED … day=510 cycle=20 … thresholdLiquidations=175`），耗时也同量级（11.0 s vs 10.8 s）。

| 推进 | 改前 | 改后 |
|---|---|---|
| 0→360 | ✅ committed（Rev 1→2） | ✅ committed（Rev 1→2） |
| 0→500 | （未跑） | ✅ committed |
| 0→510 / 0→720 / 0→3600 | ❌ 400（同一条） | ❌ 400（同一条） |
| three-powers 0→3600（10 年） | （未跑） | ✅ committed（Rev 1→2，194.0 s） |

**最小复现**：`§1.2` 的启动命令 + `to≥510` 的一次 advance（store 从创世新起）；失败轮日志 `/tmp/w1acc/results/post-b510.log`（及 `post-b720.log`、`post-b10y2.log`、`pre-b10y.log`）。

**影响与边界**：这是**世界/期权质押账的既存缺陷**（`OwnershipStakeBook` 在 day 510 关账轮的清算把某份额的质押推到 > quantity），**不是 W1 引入**（W1 的 G5 判据反而因此多了一条强证据：两侧连"错在哪一格、错多少"都逐字节相同）。它使**设计书 §6.1 W1 判据⑥"10 年推进"在 `small-world` 上无法完成**；本次用 `three-powers` 10 年 + `small-world` 上限 500 天替代（§5.2）。`501–509` 未逐日二分；修复属 W2/W6 或另开批，本报告**只上报不修**（§2 纪律）。

### 7.2 ★ W1 新增的 `MARKET_ROUND_STATS_DETAIL` 打印的是"本轮入参"，两个周期栏恒 0（**日志口径缺陷，非状态缺陷**）

day 360 的 DETAIL 行写 `本周期商品=0 本周期成交额=0 上周期商品=0 上周期成交额=0`，而**同一 tick 的落盘 stats 原文**里 `previousCycle` 有 5 个商品、`previousCycleTurnoverMilli=5601`（§5.1）。

**归因**（读代码确认，非猜测）：`EconomySettlement` 里该 DEBUG 行打印 `entry.getValue().describe()`，而 `entry.getValue()` 是**本轮入参**（`outcome.roundStats()`，只带 `lastRound`），持久窗口是 `current.withRound(day, round.lastRound(), …, cycleClosed)` 的结果（`model/MarketRoundStats.withRound`）。字段名却叫 `本周期/上周期` ⇒ 读者会以为窗口是空的。**状态本身正确**（§5.1 原文为准），属"日志字段名与所指对象不一致"。建议 W1 修复批或 W6 统一处理（改打印对象或改字段名，**不要**为迁就日志改状态）。

### 7.3 无设计冲突

未发现"设计要求与用户原话/铁律"冲突；未发现真数值漂移（§3 归因表内无"漂移"项）。

---

## 8. 我没做 / 没验证的（如实）

1. **`small-world` 10 年推进未做到** —— 被 §7.1 的既存不变量拒绝挡住（两侧同一条，非 W1 问题）；替代证据是 three-powers 10 年（§5.2）。`501–509` 的精确边界未二分。
2. **多区持久拓扑（`marketZones` 非空）未做 pre/post 逐值对拍** —— `small-world` 是派生拓扑（`marketZones` 空，正是判据①要覆盖的形态），three-powers 只用来验"多区统计簿 + 有界"，**没做**它的 G5 逐值对拍（设计书判据②只要求默认世界 0→360）。
3. **`v17levant`（59k hex）未跑** —— 只跑了 19 格 small-world + 37 格 three-powers。
4. **统计簿读口未走 HTTP** —— W1 自述"统计簿未接 GUI/API 面板"；本次读的是 checkpoint 落盘原文与 INFO/DEBUG 日志。`MarketRoundStatsBook` / `MarketSupplyDemandView` **未通过任何对外接口验证**。
5. **`checkpointInterval=1`（为取全量 dump 而设）** —— 两侧同参、同开销；**默认 100 的性能未另跑**。Δ 判据不受影响（checkpoint 是文件，不是 revision）。
6. **性能样本：改后 6 次（5 次落盘 + 1 次 stdout 实录）、改前 5 次**，同机同时段交替跑；样本内波动 ±20% ⇒ 只能断言"**未观察到 >10% 劣化**"，不能断言"没有 1–5% 的真实劣化"。
7. **不属于本次 W1 验收范围、因此未跑**：铁律 5 往返（G2）、分段等价（G3）、`clean verify` 全仓门禁与测试数（G1；W1 提交信息已给 3408 条/0 失败）、GM 载荷 `{marketId}` 的工具面负向用例、`grep 0 残留` 的旧 API 判据。
8. **未做变异自证**（本次是验收不是测试批）：报告里没有"改坏⇒红"的证据；本报告全部结论来自**两侧真跑的真实读数对比**。
9. 全仓**只新增** `.superpowers/sdd/2026-10-10-w1-acceptance/`（报告 + `apparatus/` 脚本副本 + `evidence/` 原始产物），未改任何源码/测试/文档，未 `git commit`，未推送；worktree `/tmp/w1acc-pre` 保留（可用 `git worktree remove /tmp/w1acc-pre` 清理）。
   ★ 注意：`.superpowers/sdd/.gitignore` 是 `*`（该目录默认被忽略）⇒ 本目录**不在 `git status` 里**（这也让"收尾 git status 与开工一致"成立）；若要把报告入库，需与既有台账同样的 force-add：`git add -f .superpowers/sdd/2026-10-10-w1-acceptance/`（本 Agent 不提交，交由控制方）。

---

## 附录 A：产物位置

| 内容 | 路径 |
|---|---|
| 本报告 | `.superpowers/sdd/2026-10-10-w1-acceptance/report.md` |
| 装置脚本（副本，md5 与 `/tmp` 原件一致） | `.superpowers/sdd/2026-10-10-w1-acceptance/apparatus/` |
| 逐轮 result / 逐项 summary / 各类对拍输出 / 构建日志 | `.superpowers/sdd/2026-10-10-w1-acceptance/evidence/` |
| 原始 store（含 checkpoint 全量信封） | `/tmp/w1acc/store-*`（两侧各 5+ 轮） |
| 原始日志（含 TRACE 逐笔成交，pre-c1/post-c1 各 ~140 MB；three-powers 两份已 gzip） | `/tmp/w1acc/results/*.log`（`post-tp10y.log.gz` / `post-tp360.log.gz`，`zcat` 可还原） |
| 全量投影（6 MB/份，可按需重生成） | `/tmp/w1acc/proj-{pre,post}-{r1,c1}.json` |

## 附录 B：复现（最短路径）

```bash
# 1) 基线 worktree + 两个 jar
git worktree add /tmp/w1acc-pre 323b02e3
cd /tmp/w1acc-pre && tools/mvn-lock.sh -DskipTests package -pl simos-app -am
cd /home/cna/SimulatorMosire && tools/mvn-lock.sh -DskipTests package -pl simos-app -am

# 2) 两侧各跑一次（各 360 天，独立 store/端口，一次一个实例）
python3 /tmp/w1acc/runner.py --jar /tmp/w1acc-pre/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
  --tag pre-r1 --store /tmp/w1acc/store-pre-r1 --gui-port 10411 --days 360 --segments 1
python3 /tmp/w1acc/runner.py --jar /home/cna/SimulatorMosire/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
  --tag post-r1 --store /tmp/w1acc/store-post-r1 --gui-port 10311 --days 360 --segments 1

# 3) 投影 + 逐叶比对 + 逐项表
python3 /tmp/w1acc/project.py /tmp/w1acc/store-pre-r1/checkpoints/main/2.json  /tmp/w1acc/proj-pre-r1.json
python3 /tmp/w1acc/project.py /tmp/w1acc/store-post-r1/checkpoints/main/2.json /tmp/w1acc/proj-post-r1.json
python3 /tmp/w1acc/diff.py    /tmp/w1acc/proj-pre-r1.json /tmp/w1acc/proj-post-r1.json   # 只应剩 shape/removedNewComponents 两处元数据
python3 /tmp/w1acc/summary.py /tmp/w1acc/proj-pre-r1.json /tmp/w1acc/proj-post-r1.json   # exit 0 = 逐项全同
```
