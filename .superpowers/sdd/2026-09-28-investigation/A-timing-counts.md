# A 只读调查：一年推进的耗时构成与规模计数（用户调查单第 1 题）

> **性质**：只读调查。**未改任何 `src/**` / `docs/**` / 既有台账**，未 `git add/commit`；本文件是本次唯一写盘产物（其余装置与中间文件全在 `/tmp`）。
> **代码态**：任务书写 `ts/m1 @ 5b6b2be7`；本树实际 `HEAD=c8520f34`（比任务书多两个提交，均**只动 docs**：`5b6b2be7` 改 review 报告、`c8520f34` 新增 flow 报告；生产代码 = `6fb4493c`）。shaded jar `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`（23,106,852 B，mtime **2026-09-28 01:25:54**，即 `6fb4493c` 之后 7 秒打包；本批 360 天读数用的就是它）。
> **纪律**：§三「不信 rc=0、看证据」；§八.1「判活三件套、收工按 PID」；§九「口径/假阴性」。每个结论下方给出命令/输出/文件:行。**采样 ≠ 墙钟**，二者在文中分开标注。

---

## 0. 结论速览

### 0.1 一年推进的实测墙钟（tick 0→360）

| 段 | 命令/来源 | 墙钟 | 证据 |
|---|---|---|---|
| worldgen（三国初始化，3/3） | `m2sim_full.sh` + `v3curve_setup.py` | **4 s**（01:28:13→01:28:17） | `m2sim-batch.log`；`m2-worldgen.log` |
| **0→180 一次推进** | `v3curve_advance.py 180` | **296.1 s（4:56.20）** | `m2sim-batch.log` 第 8 行「推进完成（296.1s）」+「advance 耗时 4:56.20」 |
| 180→210 | 30 天分块推进 | **52 s** | `journalctl --user -u m2sim-batch`：01:38:18→01:39:10 |
| 210→240 | 同上 | **101 s** | 01:39:10→01:40:51 |
| 240→270 | 同上 | **160 s** | 01:40:51→01:43:31 |
| 270→300 | 同上 | **112 s** | 01:43:31→01:45:23 |
| 300→330 | 同上 | **114 s** | 01:45:23→01:47:17 |
| 330→360 | 同上 | **141 s（journal 时间戳）**；既有一年期报告/台账写 **151 s** | journal 01:47:17→01:49:38；`docs/superpowers/reviews/2026-09-28-m2-one-year-run-report.md` §一表 |
| **一年推进合计（tick 0→360）** | 上述加总 | **976.1 s ≈ 16 min 16 s**（按 141 s）；**986.1 s ≈ 16 min 26 s**（按 151 s） | 96.1+52+101+160+112+114+141 |
| 逐 tick 读数 dump | `h6sim_dump.py` | **m2t180 ≈ 6 s；m2t360 ≈ 10 s** | `m2sim-batch.log` 01:33:13→01:33:19；journal 01:49:38→01:49:48。★ 既有台账写「每 tick dump ~30s」，与时间戳不符 |
| 整批从起服务到 360 读数落盘 | — | **≈ 21 min 39 s**（01:28:09→01:49:48），含 01:37:05 被信号杀后的重启 | `m2sim-batch.log` + journal |

> 注：0→180 只花 296 s（1.64 s/天），而后 180 天分 6 段共 680 s（3.78 s/天）——状态随时间变大（在途/债务/订单），且低粮库存追加轮在后半年更多（见 §2.7）。

### 0.2 30 天窗口的耗时前五项（**360→390**，无 JFR 的同包探针直接墙钟；不是采样）

装置：把 `store-m2` 复制到 `/tmp/store-probe`，只读 replay 出 tick 360 状态（revision 11），用 `EconomyDayStepper` 逐日跑 361..390（与推进器同一条 `settleOneDay` 实现），逐日分别计 `step` / `OwnershipBooks.fold` / `apply` / `land*`。总表 `/tmp/probe30-out.json`；程序 `/tmp/probe/io/mosire/simos/economy/time/AProbe.java`（同包探针，`javac` 对 `simos-*/target/classes`+shaded jar 编译，未跑 mvn）。

| 名次 | 项 | 实测墙钟（30 天） | 占 day-loop+落账 | 备注/证据 |
|---|---|---|---|---|
| **1** | **区域市场轮**（`MarketSettlement.clearOncePerCycle`：订单生成→冻结→区内/跨区撮合→成交/在途） | **126.03 s**（12 轮；单轮 7.61–12.50 s，均值 10.50 s） | **74.3 %** | 开市日 = 363,365,368,370,373,375,378,380,383,385,388,390（6 个例行 + 6 个低粮库存追加轮）；探针 `marketOpened==day` |
| **2** | **`OwnershipBooks.apply`**（每日把 fold 出的 actor 条目落账） | **29.15 s**（30 天，均值 0.97 s/天） | **17.2 %** | 每日 1088–1140 条条目；机制见 §2.6 |
| **3** | **`settleOneDay` 每日末态构造/校验 + 索引辅助**（`EconomyData.<init>`/`requireStratumAllowed`、`householdKeysAt`/`cycleDaysByHousehold` 等） | **JFR 采样推算 ≈ 15–20 s**（JFR 1300+344 样本 = 10.1 %；含在非市场日 step 里，无独立墙钟） | ≈ 9–11 % | 见 §2.4 |
| **4** | **推进前状态载入/重放**（`Replay.replay`：11 条 revision、changeset_json 合计 84,649,735 字符 + 各 codec.apply） | **5.11 s（热）/ 6.06 s（冷）** | 3.0 %（对 169.7 s） | `/tmp` 的 `AReplay` 实测；JFR 435 样本 = 2.67 % |
| **5** | **非市场日的其余 `settleOneDay` 阶段**（消费/投入计提/劳动再分配/借粮/人口回写/到货等） | **约 3–7 s**（非市场日 step 合计 14.20 s 中扣掉每日末态构造后的部分） | ≈ 2–4 % | JFR 各单项 ≤ 0.6 %；生产/收获此窗口为 0（无 120 天关账） |

30 天直接合计：`step 140.229 s + fold 0 + apply 29.147 s + land* 0.346 s = 169.72 s`；再加推进前载入 5.1 s、`EconomyChangeSet.between` 0.015 s、`ActorChangeSet.between` 0.008 s、序列化/落盘 <0.3 s ⇒ **无 JFR 的一次 360→390 应约 175 s**。对照：本批无 360→390 的实测段；330→360 段是 141–151 s，而它在 201.9 s JFR 装置下未测。JFR 装置实测 360→390 = **201.9 s**（`/usr/bin/time` 202.57 s），多出的 ~15 % 是 `settings=profile` 的开销（ExecutionSample 10 ms + ObjectAllocationSample 带栈 + NativeMethodSample）。

### 0.3 规模计数（tick 360）

| 量 | 数 | 来源 |
|---|---|---|
| 地图 hex | **59,223** | 探针 `map.hexes().size()`（`/tmp/probe*-out.json` `sizes.mapHexes`） |
| 国家/经济 hex | **799**（德 430 / 奥 138 / 霍 231；799/799 激活） | `m2t360.json` 逐国数组长度；批次日志 |
| 市场格 | **799**；市场区 **201** 个（其中 **7 个**被跨国界的格报告） | 探针 `economyMarkets`；`m2t180/m2t360.json` 的 `marketReadout.regions[].regionId` |
| 产业 | **1,799**（farm 799 + weave 799 + craft 201；598 格 2 个、201 格 3 个） | `m2t360.json` 逐格 `economy.industries` |
| 阶层行 | **6,392** = 799 × 8（rural 3196 + urban 3196；四档各 1598） | `m2t360.json` 逐格 `economy.classes` |
| 账户（actor `GoodsAccount`） | **8,191**：HOUSEHOLD 7,191（6,392 家户 + 799 织户经营者）、ESTATE 799、WORKSHOP 201；其中 5,078 本有非空货币；**冻结非空 0 本** | 探针 `sizes.actorAccounts` / `accountsByKind` / `accountsWithNonEmptyMoney` |
| 订单（**tick 360 状态上干跑 day 365 一轮**） | 买单 **1,822** 条、卖单 **10,002** 条 | 探针 `marketRoundDryRun`（与 `clearOncePerCycle` 共用 `ordersFor` 纯函数） |
| 成交（同一干跑轮） | **2,114 笔**（均为区内即时；跨区 0）；grain 2,057 / fiber 46 / cloth 11 | 同上；数量 grain 1,097,334 / fiber 352,998 / cloth 192（毫单位） |
| 在途批次 | **669 批 / 2,291 条 allocation**；数量 grain 1,135,465,869 + fiber 15,134,255 | 探针 `sizes.economyShipments`；干跑轮新造 0 批（跨区成交 0） |
| 债务 / 流水 / 劳动供给 / 配额 / 生产关系 | debts **699**；flows 6,392；laborSupply 4,000；allocations 6,312；relations 1,799 | 探针 `sizes`；债务唯一 id 从 dump 逐行去重同为 699 |

---

## 1. 方法与证据链

### 1.1 素材

- 已有运行：`.superpowers/sdd/2026-09-27-m2-general-market/`（`m2t180.json` 14,336,453 B、`m2t360.json` 14,989,967 B、`store-m2` 157 MB、`m2sim-batch.log`、`m2sim-server.log`、`dump-m2t{180,360}.log`、`m2sim_full.sh`）。
- 台账：`.superpowers/sdd/2026-09-27-m2-general-market/progress.md`。
- 系统报告：`docs/superpowers/reports/2026-09-28-economy-system-flow-report.md`；读数报告：`docs/superpowers/reviews/2026-09-28-m2-one-year-run-report.md`。
- 服务日志：`journalctl --user -u m2sim-batch --no-pager`（32 行，含分块时间戳）。

### 1.2 已有实测墙钟（直接引用）

见 §0.1。要点：
- `m2sim-batch.log` 原文：「推进完成（296.1s）」「advance 耗时 4:56.20」；
- journal 原文（节选）：
  ```
  Sep 28 01:38:18 ... -- advance -> 210（01:38:18）
  Sep 28 01:39:10 ... -- advance -> 240（01:39:10）
  Sep 28 01:40:51 ... -- advance -> 270（01:40:51）
  Sep 28 01:43:31 ... -- advance -> 300（01:43:31）
  Sep 28 01:45:23 ... -- advance -> 330（01:45:23）
  Sep 28 01:47:17 ... -- advance -> 360（01:47:17）
  Sep 28 01:49:38 ... ===== dump m2t360（01:49:38）=====
  ```
- **口径差异**：`m2sim_full.sh` 的 `chunk 耗时` 行被 `| tail -4` 与 Python 输出缓冲冲掉了（journal 里只剩 no-op 那次的 `chunk 耗时 0:00.08`）；上表用相邻 `-- advance` 行的时间戳差。末段 journal 差 = 141 s，而 review 报告/台账写 151 s，差 10 s——两处都无法再对账（`/usr/bin/time` 行已丢），**本报告两个数并列**，不取平均。

### 1.3 JFR 装置（主证据：360→390，30 天）

命令（全部在 `/tmp`，服务按 PID 收工）：

```bash
SRC=.superpowers/sdd/2026-09-27-m2-general-market/store-m2
cp -a "$SRC" /tmp/store-prof            # 只读副本（原 store 不动）
cd /home/cna/SimulatorMosire
JAVA_TOOL_OPTIONS='-XX:StartFlightRecording=filename=/tmp/A.jfr,settings=profile,dumponexit=true' \
  setsid bash -c 'echo $$ > /tmp/prof.pid; exec tools/run-shaded.sh \
    simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
    --store /tmp/store-prof --gui-port 5837 --mcp-port 5735 --approval-port 5733'
# 判活：curl --noproxy '*' http://127.0.0.1:5837/index.html = 200；服务就绪 3 s
# 推进（/tmp 副本已把 simos_mcp.py 的 MCP 端口 5725 改成 5735）：
/usr/bin/time -f 'WALL_SECONDS %e' python3 /tmp/jfrprobe/v3curve_advance.py 390
#   输出：当前 revision=11 tick=360 ⇒ 目标 390；推进完成（201.9s）rev 12
jcmd 3213 JFR.dump filename=/tmp/A-dump.jfr     # 兜底拷贝
kill 3213                                        # ★ 按 PID 收工（ps -eo pid,cmd | grep simos-shaded）
```

- `jfr summary /tmp/A.jfr`：Duration 221 s（含 3 s 启动），ExecutionSample **16,275**、NativeMethodSample 10,373、GarbageCollection 507（暂停合计 **4.838 s**，最大 93 ms）、GCCPUTime CPU 合计 **14.9 s**、ObjectAllocationSample 50,193；JVM 线程 CPU 总账（systemd unit）**232.4 s CPU / 221 s 墙钟 ≈ 1.05 核**。
- 采样线程：`boundedElastic-1`（MCP 工具调用线程）**16,268 / 16,275 = 99.96 %**；其余 main 4、HTTP-Dispatcher 2、ForkJoinPool 1。日循环 + 重放 + 提交都在同一线程，故按线程过滤后仍是全链路。
- 归并脚本：`/tmp/jfr_final2.py`（`jfr print --events jdk.ExecutionSample --stack-depth 64` 流式解析，**取最内层命中的“业务阶段方法”**；先匹配具名阶段，再回退到 helper；方法名只匹配 `方法签名 '(' 之前的类.方法`，避免把参数类型当方法）。输出 `/tmp/A-final2.json`。
- **采样 ≠ 墙钟**：① ExecutionSample 只在 Java 线程 runnable 时按 ~10 ms 采样，阻塞/GC 不进来（GC 单独用 `jdk.GarbageCollection` 计）；② `settings=profile` 的分配采样带栈有开销，JFR 下 201.9 s vs 无 JFR 约 141–175 s，**分配重的阶段（市场）会被高估**。故本报告以**无 JFR 探针墙钟**定“前五项”，JFR 只定“阶段归属与相对占比”。

### 1.4 无 JFR 探针（同包，只读）

- 源码：`/tmp/probe/io/mosire/simos/economy/time/AProbe.java`（package `io.mosire.simos.economy.time`，故能调包内可见的 `MarketSettlement.planOrders` / `clearOncePerCycle` / `MarketRound`）。
- 编译（**不跑 mvn**）：`javac -proc:none -cp <simos-*/target/classes>:simos-app/target/…-shaded.jar -d /tmp/probe-classes …/AProbe.java`。
- 运行：`java -cp /tmp/probe-classes:<同上> …AProbe /tmp/store-probe <targetTick> <roundDay> <lastDay>`。
- 它做的事：`CoreSimos`+6 codec **只读 replay**（`Replay.replay` 不写盘）→ 取 `EconomyData`/`ActorData` → 用 `OwnershipBooks.load*` 载入四份会话副本 + 四张冻结 → ① 在 tick 状态上按 `planOrders` 数订单、`clearOncePerCycle` 干跑一轮（改的只是探针内存副本）；② 干净副本上 `EconomyDayStepper.step` 逐日 361..last，逐项计时 `fold/apply/landHouseholdGoods/landHouseholdMoney/landOperatorGoods/landOperatorMoney`；③ 计时 `EconomyChangeSet.between`、`ActorChangeSet.between`、`EconomyCodec/ActorCodec` 编解码；④ 反射调组合根 `MarketTopologyBook.from` 得到与生产同一份区域拓扑。
- 运行三次：`/tmp/probe-out.json`（5 天，51 s）、`/tmp/probe30-out.json`（360→390，30 天，199.2 s）、`/tmp/probe330-out.json`（330→360，30 天，144.5 s）；另有只读载入计时的 `/tmp/probe/.../AReplay.java`。
- `store-probe` 在探针后仍是 `max(revision)=11, max(tick)=360`（sqlite 只读验证），**探针未写状态**；原 `store-m2` 未改（只被 python sqlite 只读打开过，出现 0 字节 `-wal`/`-shm` 属 WAL 模式读打开的正常副产物）。

---

## 2. 一年推进的耗时分解

### 2.1 全年分段（实测，§0.1 已给）

- 全年 **976.1 s**（journal 口径）≈ 16.3 min；首段 0→180 占 30.3 %。
- 180→360 的 680 s 里含 6 次 `Replay.replay`（每次约 **5.1–6.1 s**，见 §2.5）+ 6 次提交；即**载入/提交固定开销约 6×5.5 ≈ 33 s（占这 180 天的 ~5 %）**。
- 时间≠单调随规模：240→270 的 160 s 是本批最慢的 30 天段（低库存追加轮次数/状态分布所致，未单独 profile）。

### 2.2 窗口 A（360→390）直接墙钟分解（主证据）

| 桶 | 30 天墙钟 | 占比 | 明细 |
|---|---|---|---|
| 开市日 `step` | **126.031 s** | 74.3 % | 12 天：单轮 7.612–12.503 s，均值 10.503 s |
| 非开市日 `step` | **14.198 s** | 8.4 % | 18 天，均值 0.789 s（0.676–1.195 s） |
| `OwnershipBooks.apply` | **29.147 s** | 17.2 % | 30 天，均值 0.972 s；条目 1088–1140/天 |
| `OwnershipBooks.fold` | **0 ms**（毫秒精度下） | ~0 | 每日 1088–1140 条条目 |
| `land*`（HG/HM/OG/OM） | **0.346 s**（101/108/84/53 ms） | 0.2 % | 绝对值落回，30 天几乎可忽略 |
| 合计（day-loop+落账） | **169.722 s** | 100 % | 无 JFR、同一 tick 360 状态、同一实现 |

**开市日识别**：`stepper.lastMarketReport().day()` 在开市当天等于当天，非开市日保留上一轮 ⇒ 12 天 = 6 个 `day%5==0` 例行轮（365,370,375,380,385,390）+ 6 个 `day%5==3` 低粮库存追加轮（363,368,373,378,383,388）。这 12 天与 `MarketTrigger`（`MarketSettlement.java:332-353`）一致。

**单轮干跑**（tick 360 状态、day 365、`PERIODIC`）：`planOrders` 1.71 s + `clearOncePerCycle` 10.52 s，与开市日 step 10–12.5 s 同量级 ⇒ “开市日 step ≈ 一轮市场 + 当日其余”。

### 2.3 JFR 阶段占比与 Top-5 帧（360→390）

**最内层业务帧 Top-5（leaf frame，ExecutionSample）：**

| # | 帧 | 样本 | 占比 |
|---|---|---|---|
| 1 | `MarketSettlement.matchAcrossRegions` | 4,891 | 30.05 % |
| 2 | `java.lang.StringLatin1.lastIndexOf`（几乎全在 `IndustryHexKeys.hexKeyOf` 内） | 1,554 | 9.55 % |
| 3 | `java.util.HashMap.putVal` | 1,424 | 8.75 % |
| 4 | `MarketSettlement.refreshSellFrozen` | 1,242 | 7.63 % |
| 5 | `java.util.HashMap.resize` | 1,017 | 6.25 % |

（另有 `java.lang.invoke.LambdaForm$MH.invoke` 1,116 = 6.86 %、`compareComparables` 954 = 5.86 %、`ActorRef.equals` 953 = 5.86 %，均为集合/JIT 适配层，归到所属业务阶段。）

**首个业务帧 Top-5（业务归属）：**

| # | 业务帧 | 样本 | 占比 |
|---|---|---|---|
| 1 | `MarketSettlement.matchAcrossRegions` | 5,021 | 30.85 % |
| 2 | `GameMap.terrainIndex()` | 3,384 | 20.79 % |
| 3 | `IndustryHexKeys.hexKeyOf` | 1,555 | 9.55 % |
| 4 | `MarketSettlement.refreshSellFrozen` | 1,242 | 7.63 % |
| 5 | `ActorData.<init>` | 1,053 | 6.47 % |

**阶段表（具名阶段优先，helper 回退；`/tmp/jfr_final2.py` 输出 `/tmp/A-final2.json`）：**

| 阶段桶 | 样本 | 占比 | JFR 折算墙钟（×201.9 s） |
|---|---|---|---|
| M2a 市场/跨区撮合 `matchAcrossRegions` | 5,200 | 31.95 % | 64.5 s |
| M2b 市场/跨区撮合-运输代价缓存重建（`terrainIndex`） | 3,688 | 22.66 % | 45.7 s |
| P2 产权落账 `OwnershipBooks.apply` | 2,926 | 17.98 % | 36.3 s |
| X3 `settleOneDay` 末态构造/校验（`EconomyData.<init>`+`requireStratumAllowed`） | 1,300 | 7.99 % | 16.1 s |
| M3a 市场/冻结 `refreshSellFrozen` | 1,245 | 7.65 % | 15.4 s |
| L1 推进前重放/载入旧 revision | 435 | 2.67 % | 5.4 s |
| M9 市场/其他 `MarketSettlement` | 357+12 | 2.27 % | 4.6 s |
| X4 `settleOneDay` 索引辅助 | 344 | 2.11 % | 4.3 s |
| M3b `refreshBuyFrozen` | 165 | 1.01 % | 2.0 s |
| M1 市场/区内撮合 | 101 | 0.62 % | 1.3 s |
| S4 劳动再分配（`reallocateLabor`+`scaleLaborOfGroup`） | 28+64 | 0.57 % | 1.1 s |
| S5 借粮（`lendDeficits`+`creditLinesOf`） | 9+1 | 0.06 % | 0.12 s |
| S9 饿死/人口（`applyPopulationChange`，day 390 月末） | 50 | 0.31 % | 0.6 s |
| M6 市场/未成交汇总 | 49 | 0.30 % | 0.6 s |
| F 推进框架/提案合并 | 48 | 0.29 % | 0.6 s |
| M2c/M2d 跨区撮合其余（`moveCostOf`/`matchRoute`） | 4+43 | 0.29 % | 0.6 s |
| X1b 区域拓扑查询（撮合内） | 40 | 0.25 % | 0.5 s |
| X6 `settleOneDay` 框架 | 23 | 0.14 % | 0.3 s |
| P3 `land*` | 48 | 0.30 % | 0.6 s |
| E 落盘 SQLite/Timeline | 11 | 0.07 % | 0.14 s |
| S3 消费 | 12 | 0.07 % | 0.15 s |
| M5a 成交落账 `executeTrade` | 13 | 0.08 % | 0.16 s |
| S2 投入计提（`surveyInputDemands`，day 361 播种） | 6 | 0.04 % | 0.09 s |
| S1 生产分配（`ProductionSettlement`） | 9 | 0.06 % | 0.12 s |
| **S1 收获 `harvest` / S7 计息 `chargeInterest` / S6 还债 `repayDebts`** | **0** | **0 %** | 该 30 天窗口没有 120 天关账（下一次在 480）；`S8 到货` 也是 0 样本但**确有到货**（669 批在途会陆续到）——只能说该路径轻到采不到，不能反推“没发生” |

> 阶段桶加总 = 100 %（四舍五入）。**JFR 与无 JFR 墙钟的差别**：JFR 折算的市场 ≈ 135 s vs 探针开市日 step 126 s（+7 %，JFR 高估分配重的市场）；P2 折算 36.3 s vs 探针 29.1 s（+25 %）。以探针墙钟为准，JFR 只作相对归属。

### 2.4 `settleOneDay` 各阶段的“分开计时”

| `settleOneDay` 阶段（顺序） | 代码位置 | 窗口 A 样本（占比） | 窗口 A 直接墙钟 | 说明 |
|---|---|---|---|---|
| 0b 到货 `deliverShipments` | `EconomySettlement.java:1060-1106` | 0 | — | 窗口内**有到货**（在途 669 批会陆续到），但采样 0 次 ⇒ 只能判“该路径很轻”，**不能判“没发生”**（§9 假阴性纪律） |
| 1 投入计提 `surveyInputDemands`（day 361 播种经 `drawCycleInputs`） | `:1507`/`:1631`/`:1717`/`:1820` | 6（≈0.04 %） | 落在 `step` 内 | day 361 是周期第一天，但投入路径很轻 |
| 2 消费 `consumeOwnStock` | `:2423` | 12（0.07 %） | — | |
| 3 生产/周期末 `harvest`+`ProductionSettlement` | `:3135`/ProductionSettlement | 9（0.06 %） | — | **窗口内无关账日**，仅 ProductionSettlement 的少量残余 |
| 4 劳动再分配 `reallocateLabor` | `:2840` | 28+64（0.57 %） | — | 含 `scaleLaborOfGroup`（饿死缩编；窗口内人口回写为 0） |
| 4 区域市场 `MarketTrigger`+`clearOncePerCycle` | `MarketSettlement.java:332/435` | 10,896（**66.95 %**） | **126.03 s**（12 个开市日 step 合计） | 本轮最大项；细分见 §2.3 |
| 4b 借粮 `lendDeficits` | `:2507` | 10（0.06 %） | — | |
| 4c 还债 `repayDebts` | `:2752` | 0 | — | 悬挂在关账日 |
| 4d 饿死 `applyFamine` | `:3804` | 0 | — | 致死率默认 0 且窗口内无关账 |
| 5 计息 `chargeInterest` | `:3864` | 0 | — | 关账日才发生 |
| 人口回写 `applyPopulationChange` | `:3672` 邻域 | 50（0.31 %） | — | day 390 月末一次 |
| 每日末态构造/校验（`requireStratumAllowed` 等，不在上面编号里） | `EconomyData.java:190/249/422` | 1,300（7.99 %） | 估算 12–20 s | 每个 `settleOneDay` 返回前 new 一次 `EconomyData` |
| 每日索引辅助（`householdKeysAt`/`cycleDaysByHousehold`…） | `EconomySettlement.java:2255-2360` | 344（2.11 %） | 估算 3–6 s | |
| 产权落账（`step` 之后，协调器循环内） | `OwnershipBooks.java:117/128/149/264/360/495/547` | `fold` 0；`apply` 2,926（17.98 %）；`land*` 48（0.30 %） | **apply 29.15 s；land* 0.35 s** | 见 §2.6 |

> **窗口 B（330→360，含 120 天关账日 360）补充**：`/tmp/probe330-out.json`。30 天 `step` 87.824 s + `apply` 28.868 s + `land*` 0.278 s = **116.97 s**（+载入 ≈5 s vs 该段实测 141–151 s）。其中 **day 360（关账）单日 `step` 13.210 s、`OwnershipBooks.apply` 17.493 s、fold 条目 21,721**（平常日条目 250–1,140、apply 0.18–1.07 s）——**关账日的 apply 条目数暴涨 20–80 倍，落账本身成为单日首项**。该窗口 12 个开市日 step 合计 75.561 s（64.6 %），非开市日 12.263 s（0.681 s/天）。
> ★ 因此**三个关账日（120/240/360）的 `OwnershipBooks.apply` 各约 17.5 s（约 50 s/年）**，是全年不可忽略的一项；而 harvest/还债/计息的内部阶段仍未单独计时。

### 2.5 `EconomyChangeSet.between` / 落账 / 序列化 / codec 分开计时

| 项 | 实测 | 证据 |
|---|---|---|
| `EconomyChangeSet.between(base, target)`（30 天真实 diff，10 组件 `FieldDelta.diff`） | **15–16 ms** | `/tmp/probe*-out.json` `betweenTiming.economyBetweenMs`；代码 `EconomyChangeSet.java:111-138` |
| `ActorChangeSet.between` | **6–8 ms** | 同上 `actorBetweenMs` |
| `OwnershipBooks.fold` | **<1 ms/天**（毫秒计时 0） | `dayTimings[].foldMs` |
| `OwnershipBooks.apply` | **0.97 s/天**（窗口 A，30 天 29.147 s）；关账日 **17.49 s** | `dayTimings[].applyMs` |
| `landHouseholdGoods/Money`、`landOperatorGoods/Money` | 30 天合计 **0.35 s** | `dayTimings[].land*Ms` |
| 变更集序列化 `Timeline.changeSetJson`（写 `revisions.changeset_json`） | JFR **1 样本 ≈ 20 ms**（实际每次 advance 一次，~10.5 MB JSON） | `/tmp/A-final2.json` 的 `D-变更集序列化`；`Timeline.java:362-370` |
| SQLite 落盘（BEGIN/INSERT/COMMIT） | JFR 11 样本 ≈ **0.14 s** | `E-落盘 SQLite/Timeline` |
| `EconomyCodec.encodeSnapshot`（checkpoint 路径） | **89–113 ms**（12.0–12.4 MB JSON） | `/tmp/probe*-out.json` `codecTiming` |
| `EconomyCodec.decodeSnapshot` | **643–688 ms** | 同上 |
| `ActorCodec` encode/decode | **14–16 ms / 26–30 ms**（2.9 MB JSON） | 同上 |
| **推进前重放**（11 条 revision 的 changeset JSON 反序列化 + 各 codec.apply） | **6.06 s（首次）/ 5.11 s（热）**；changeset_json 合计 **84,649,735 字符** | `AReplay` 输出；`Replay.java:131-200` |

> checkpoint 间隔 = 100（`ShellConfig.DEFAULT_CHECKPOINT_INTERVAL`，启动日志 `checkpointInterval=100`），本批 revision 1→12，**advance 不在 checkpoint 谓词上 ⇒ 上述 `EconomyCodec` 编解码在本次推进中没有发生**，只作为“若命中 checkpoint 需付多少钱”的量级给出。

### 2.6 两条最大项的机制（只读归因，不是设计建议）

**① `GameMap.terrainIndex()` 每调一次重建整张 59,223 格地图**（JFR 首业务帧 20.79 %）。

调用链（JFR 栈 + 代码逐行核对）：

```
MarketSettlement.matchAcrossRegions (:935-999，商品×买方格×卖方格×逐单扫描)
  → MarketSettlement.matchRoute (:1006-…)
    → MarketSettlement.moveCostOf (:1001-1002)
       ctx.moveCostCache.computeIfAbsent(hex, key -> topology.moveCostAt(key))   // 缓存是“每轮一次/每格”
      → MarketTopology.moveCostAt (:210-211)  →  组合根传入的 lambda
        → MarketTopologyBook.moveCostAt (:143-145)
          → GameMap.terrainIndex() (:227-236)   // ★ 每次调用 new LinkedHashMap，遍历全部 terrainBlocks/hexes
```

`GameMap.terrainIndex()` 自己的 javadoc 就写着「不进组件/变更集/存档、**每次重算**」。`ctx.moveCostCache` 是 `MatchContext` 字段（**每轮市场一个**），所以每轮对**每个新出现的格**各触发一次 59,223 条目的重建（上界 = 该轮触及的市场格数，最多 799 格；实际触及格数未单独计数）。JFR 侧与该机制一致：`HashMap.putVal/resize/treeify/compareComparables` 合计约 25 % 样本，`jfr view allocation-by-class`：`LinkedHashMap$Entry` 24.0 %、`HashMap$Node[]` 7.5 %、`HashMap$TreeNode` 8.3 %。

**② `OwnershipBooks.apply` 每条 entry 复制整张 8,191 账户表**（JFR 17.98 %；探针 29.15 s/30 天）。

`OwnershipBooks.java:149-190` 对每条 `ActorEntry` 调 `books.withAccount(new GoodsAccount(...))`，而 `ActorData.withAccount`（`ActorData.java:143-148`）内部 `new LinkedHashMap<>(accounts)`（**整表 8,191 项**）后再 `new ActorData(...)`（构造期还有校验/拷贝）。条目数：平常日 1,088–1,140，关账日 **21,721** ⇒ 关账日 ~21,721×8,191 ≈ 1.78 亿次 map 插入，实测 17.49 s。

**③ `IndustryHexKeys.hexKeyOf` 的字符串 `lastIndexOf`**（首业务帧 9.55 %）：`EconomyData` 构造期 `requireStratumAllowed`（`:422-431`）对 6,392 条阶层行 + 1,799 条 flow 逐个解析产业 id 的格键；`settleOneDay` 每天返回前都 new 一次 `EconomyData`（`:1048` 附近），于是 360 天里这项按天重复。

### 2.7 全年构成的外推（**估算，明确标注**）

可测的全年固定量：0→180 = 296.1 s；180→360 = 680 s；其中 6×载入 ≈ 33 s、6×提交/序列化 <2 s。两个 30 天窗口的直接分解：

| 项 | 窗口 B 330→360（含关账） | 窗口 A 360→390（含月末） |
|---|---|---|
| 开市日 step 合计 | 75.561 s（12 天） | 126.031 s（12 天） |
| 非开市日 step 合计 | 12.263 s（18 天） | 14.198 s（18 天） |
| `apply` 合计 | 28.868 s（其中关账日 17.493 s） | 29.147 s |
| `land*`/fold | 0.278 s / 0 | 0.346 s / 0 |
| 合计（+载入 ~5 s） | **≈ 122 s** | **≈ 175 s** |

以这两个窗口（后半年，低库存追加轮已全部触发）外推全年：

- **区域市场轮：约占全年 45–75 %**。按 6 个 30 天窗口 × (75–126 s) 计 = 450–756 s / 976 s；早期（0→180 只有 296 s）轮次更便宜/更少（tick 180 仅 1 格缺口、低库存轮多半不触发）。
- **`OwnershipBooks.apply`：约 15–20 %**（每 30 天窗口 ~29 s；3 个关账日各 ~17.5 s 已含在内）。
- **`settleOneDay` 每日末态构造/校验+索引：约 8–11 %**（JFR 10.1 %）。
- **推进前载入/重放：约 3–4 %**（6 次分块调用 × 5–6 s；0→180 那次从 tick 0 无 checkpoint 负担）。
- **其余（消费/投入/劳动/借粮/还债/计息/饿死/人口回写/社会压力/提交/序列化/GC）：合计约 5–15 %**。其中 harvest/repay/interest 只在 3 个关账日发生，且**未被单独计时**（见 §5）。

> 外推的不确定来源：① 窗口只有两个、都在后半年；② 前半年库存/货币状态不同，市场轮次数与单轮成本都可能更低；③ 240→270 的 160 s 尖峰未归因；④ 关账日只测了总 `step` 与 `apply`，没拆 harvest/还债/计息。

---

## 3. 规模计数（基准 = tick 360，`m2t360.json` + `store-m2` 的 revision 11）

### 3.1 hex / 产业 / 阶层行（直接数 dump）

命令：`python3 -` 逐国读 `m2t360.json`（`nations[*]` 是逐格记录数组）。

- **hex**：三国数组长度 **430 + 138 + 231 = 799**；`economy.activated=true` 799/799。整张地图 59,223 hex（探针 `map.hexes()`；批次报告同）。dump 只覆盖三国区域，不是全图。
- **产业**：合计 **1,799**：`farm@` 799、`weave@` 799、`craft@` 201；一格的产业数分布：598 格 2 个 + 201 格 3 个。每条含 `cycleDays=120`、`operator`、`capacity`、`inputPerUnit`、`laborPerUnit`、`outputPerUnit`、四档 `slots`。
- **阶层行**：合计 **6,392** = 799×8；`residence` rural 3,196 + urban 3,196；四档各 1,598（`landlord`/`rich_peasant`/`middle_peasant`/`poor_peasant`）。每条含 population/劳动/参与率/goods/actorMoney/naturalNeeds/debts/credits/flow。
- **市场**：799 格每格 1 个 `market`（numeraire 全 `silver`；价表键固定 `{cloth,fiber,grain,iron,tool}`）；按 `regionId` 去重 **201 区**（成员 1–7 格，均值 3.98；**7 个区**被跨国界的格报告）。

### 3.2 账户（actor 切片）

只读 replay tick 360 的 actor 切片（探针 `sizes`）：

- `actors` **8,191**、`accounts` **8,191**（每个主体一本 `GoodsAccount`，键 `(owner, location)`）。
- 按 kind：**HOUSEHOLD 7,191**（= 799 格 × 2 居住类型 × 4 阶层 = 6,392 本“消费家户账” + 799 本 `weave@` 织户经营者账——`weave` 产业 operator 的 kind 也是 HOUSEHOLD）、**ESTATE 799**（`farm@`）、**WORKSHOP 201**（`craft@`）。
- 有非空 money 的账户 **5,078**（HOUSEHOLD 4,799 + ESTATE 268 + WORKSHOP 11）；冻结非空 **0**（tick 360 是轮末/关账后，冻结已释放）。
- 交叉核对：`EconomyOwnershipTool` 的类注口径「真档 799 × 8 = 6392 个」指**家户消费账**；本探针多出的是 1,799 本经营者账，两者不矛盾。

### 3.3 订单与成交（tick 360 状态上的干跑）

dump 里没有“订单条数/成交笔数”（`marketReadout.match` 只有 `tradedMilli`、`unfilled*Counts/Quantities`、运力/运费/损耗，且**每格携带整区读数**）。故用同包探针在 tick 360 状态、day 365（下一个 `PERIODIC`）干跑一轮：

| 量 | 买 | 卖 |
|---|---|---|
| 订单条数 | **1,822** | **10,002** |
| 分商品条数 | cloth 266 / fiber 810 / grain 746 | cloth 4,724 / fiber 799 / grain 3,475 / iron 803 / tool 201 |
| 订单数量（毫） | 4,882,130 | 175,293,920,921 |
| 分商品数量（毫） | cloth 178,213 / fiber 1,976,000 / grain 2,727,917 | cloth 30,864,321,363 / fiber 13,907,036,125 / grain 130,145,726,487 / iron 263,410,000 / tool 113,426,946 |

**成交（fills）：2,114 笔**；其中区内即时 **2,114**、跨区 **0**；分商品 grain 2,057 / fiber 46 / cloth 11；成交量 grain 1,097,334 / fiber 352,998 / cloth 192（毫）。干跑**没有新造在途批次**（跨区成交 0）；`unfilledRows=11,706`、`routeRows=112,698`、运费实收/未收/预排损耗全 0（真档无 ORGANIZATION 承运人，M2.4 的已知边界）。

同一 tick 状态、day 360 的**最后一次真实市场轮**（dump 的 `marketReadout`，按 201 个 `regionId` 全局去重；已核对同区成员格的该行逐值相同）：

| 商品 | `tradedMilli`（去重后） |
|---|---|
| grain | **60,869,214** |
| fiber | **56,335,101** |
| cloth | **196,119** |
| iron / tool | 0 |

未成交原因计数（去重后，卖方）：cloth `no_budget` 4,721、fiber `no_budget` 799、grain `no_budget` 3,474、iron `no_buyer` 803、tool `no_buyer` 201；买方：fiber `algorithm_uncovered` 735、grain 628、cloth 267、fiber `no_budget` 8。

> ★ **与既有读数报告的差异（口径，不是数值错）**：review 报告 §三/§四 写「按 `regionId` 去重后 208 个区；tick180 粮成交 2,393,654,601；tick360 粮成交 63,032,079」。我复算：201 个区是**全局**去重；208 = 201 + 7（**7 个跨国界区被按国各数一次**），其成交也是「先按国去重、再三国相加」⇒ tick180 我算 2,222,004,129、tick360 我算 60,869,214。两套都能复现各自口径，**全球量请用 201/2.22B/60.87M**；引用时须标注是“全球去重”还是“分国去重后相加”。

### 3.4 在途批次（tick 360）

- **669 批 / 2,291 条 `ShipmentAllocation`**；数量 grain 1,135,465,869 + fiber 15,134,255（毫单位）。
- 交叉：tick 330 时是 **1,266 批**；330→360 期间跨区贸易降温、旧批到货，故 360 反而更少。干跑 day 365 轮新造 0 批。
- `ShipmentBatch` 是 `EconomyData` 第 10 组件（跨 tick 状态），**在 store 的 changeset/payload 里，dump 不含**——只能用 replay 探针/只读接口数。

### 3.5 其它规模量（探针 `sizes`）

`debts` 699（与 dump 逐行唯一 id 去重一致）、`flows` 6,392、`laborSupply` 4,000、`allocations` 6,312、`relations` 1,799、`markets` 799、`shipments` 669、`mapHexes` 59,223。

---

## 4. 口径、不确定性与证伪点

1. **JFR 采样 ≠ 墙钟**（§1.3）：JFR 下 360→390 = 201.9 s；无 JFR 探针同窗口 day-loop+落账 = 169.7 s；本批无 JFR 的 330→360 = 141–151 s。JFR 折算的秒数只能当“阶段相对占比”，不能当逐项墙钟。
2. **窗口代表性**：两个 30 天窗口都在 330–390（后半年）。**360→390 不含任何 120 天关账**（下一次 480），故 harvest/还债/计息/饿死在窗口 A 是 0 样本；330→360 含 day 360 关账，但只测了总 `step` 与 `apply` 总时。全年外推因此对“关账日内部阶段”没有直接证据。
3. **探针不含 social 侧**：`PopulationEconomyTimeParticipant` 每日还调 `applyDailyStress`、每 30 天调 `PopulationDynamics.monthly`；探针只跑 economy+actor 片段。JFR 里这两项合计 63 样本（0.39 %），量级小但不为零。
4. **探针的 JVM 状态**：单次运行内逐日计时，首日含 JIT 冷启动；市场日成本占绝对多数，故聚合比例稳定。不同窗口的 `step` 差异（如单轮 5.3 s→12.5 s）主要是状态（在途/订单/货币）差异，不是装置噪声。
5. **末段 141 s vs 151 s**：journal 时间戳与 review 报告/台账冲突，未对账（§1.2）。全年两个口径都给了。
6. **dump 的 round 是 day 360**（`marketReadout` 是进程内 `lastMarketReport`，重启即失、旧 revision 回读没有）；探针干跑是 day 365。两者的订单/成交不可直接横比（状态与日号都不同），报告中没有把它们并排当同一读数。
7. **订单条数/成交笔数**在 dump 里不可得（§3.3）；探针给出的 1,822/10,002/2,114 是**在 tick 360 状态上按生产同一 `ordersFor`/`clearOncePerCycle` 干跑一轮**的值，不是一年累计，也不是 day 360 的复盘。
8. **区域计数**必须写明“全球去重 201”还是“分国去重相加 208”（§3.3）；跨国界 7 区已用逐格 `regionId → 国家集合` 复算。

---

## 5. 我没做 / 没验证的

- **没改任何 `src/**`、`docs/**`、既有台账**；没 `git add/commit`；没跑 Maven（探针只用 `javac` 对已有 `target/classes`+shaded jar 编译）。
- **没做关账日内部阶段的计时**：`harvest` / `repayDebts` / `chargeInterest` / `applyFamine` 在窗口 A 是 0 样本；窗口 B 只给出 day 360 的 `step=13.21 s`、`apply=17.49 s`、fold 条目 21,721，**没有拆出各阶段**。
- **没测 `EconomyOwnershipTimeParticipant` 那条参与者**（本批生产参与者是 `PopulationEconomyTimeParticipant`）；两者用同一套 `OwnershipBooks`，但没逐条对照。
- **没测其它模块的 `between`**（`SocialChangeSet`/`MapChangeSet`/`UnitChangeSet`/`SdChangeSet`）；只测了 `EconomyChangeSet`（15–16 ms）与 `ActorChangeSet`（6–8 ms）。
- **没把 JFR 的 `ObjectAllocationSample` / `NativeMethodSample` 全量归因**；NativeMethodSample 几乎全在 HTTP/selector 线程（`HTTP-Dispatcher` 7,776 + `HttpClient-1-SelectorManager` 2,589），与推进线程无关。GC 只计了暂停合计（4.84 s）与 CPU（14.9 s），未做分配点逐条归因。
- **没做全年 12 个 30 天窗口的逐段 profile**；§2.7 的外推基于两个窗口 + 6 段墙钟。
- **没解释 240→270 的 160 s 尖峰**（低库存追加轮次数/状态分布都没查）。
- **没有验证 151 s 这个数的来源**（`/usr/bin/time` 行已在 journal 中丢失）。
- **没有对最大项 `GameMap.terrainIndex()`/`OwnershipBooks.apply` 提出或实现任何修复**（只读调查；机制归因见 §2.6）。
- **所有临时物都在 `/tmp`**（重启即失）：`/tmp/A.jfr`、`/tmp/A-final2.json`、`/tmp/probe*-out.json`、`/tmp/probe/...`、`/tmp/store-prof`、`/tmp/store-probe`。JFR 主文件未持久化到仓内。
- **服务收工**：JFR 服务 PID 3213 是 `kill 3213` 关闭的（日志 `收到停止信号，关闭 Shell`；`ps` 已无该 PID）；`systemctl --user` 无遗留 simos 单元；原 `store-m2` 只被只读打开，仓内 `git status --porcelain` 为空（写本文件前）。
