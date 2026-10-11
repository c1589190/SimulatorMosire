# W6 总验收报告 —— 全局判据 G1–G7 + 标定读数

- **角色**：W6 总验收 Agent（**只读验收**：未改仓库任何源码/测试/文档；`git status --porcelain` 开工/收尾均为空，见 §6）
- **被验对象**：`f5b4f28d`（HEAD = W5.5 借贷重做 —— 取缔借粮 + 货币市场；工作树干净）
- **判据来源**：设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md`（**v3.23**）§2 S-1…S-12、§2.1 价格算式、§4.1 溯源表、§6.1 的 W1–W6 行、**§6.2 G1–G7**、§6.3 判据清单；各批验收报告（W2/W3/W4/W5）的口径与装置；`AGENTS.md` §一.11 / §三.1 / §三.4 / §三.5 / §九.4 / §八.1
- **时间**：2026-10-11 16:45 – 17:2x（CST）
- **产物落点**：本目录（`.superpowers/sdd/2026-10-11-w6-acceptance/`）—— 报告、逐日读数、标定汇总、G1 的 Maven 行存档都在这里；笨重中间产物（store / dump / 原始日志）在 `/tmp/w6acc/`（本机重启会清空，故结论与关键读数一律已同步到本目录）。

---

## 1. 装置

### 1.1 构建与 jar（**从头现构；无运行中服务被覆盖**）

```bash
# 开工核对：pgrep -af java → 无 simos 服务；pgrep -af "classworlds.launcher|surefirebooter" → 无 Maven；
#           git status --porcelain → 空；HEAD = f5b4f28d
tools/mvn-lock.sh clean verify        # ★ G1 本体：前台跑，16:46 起，05:11 min，BUILD SUCCESS，rc=0
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
```

| | 值 |
|---|---|
| 被验 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，**md5 `7d3d662d953a8009248ac1698a92177b`**，27,169,137 字节，mtime `2026-10-11 16:51:11` |
| 构建 | `clean verify` **BUILD SUCCESS**，16 模块全 SUCCESS，`Total time: 05:11 min`；Maven 行存档 `g1-maven-lines.log`（本目录，165 KB） |
| 装置自证 jar | W5 验收 jar `/tmp/w5acc/w5.jar` md5 `5a3d080a7c4c20a62b3eafa9cd71305b`（= W5 报告 §1.1 同一份字节） |
| 环境变量 | 每次运行 `JAVA_TOOL_OPTIONS=-Dsimos.economy.logLevel=… [-Dsimos.economy.traceLevel=…] [-Dsimos.social.logLevel=…]` |

### 1.2 世界 / store / 端口 / 命令

```bash
bash tools/run-shaded.sh <jar> --store <store> --world=<small-world|three-powers> \
  --config /tmp/w6acc/shell-<sw|tp>.json --gui-port P --mcp-port P+1 --approval-port P+2
# 推进：POST /api/advance {"branch":"main","expectedRevision":R,"from":A,"to":B}
# 读口：GET /api/economy/gov、GET /api/economy/overview（GUI 端口）
```

| 配置 | md5 | 与历史可比性 |
|---|---|---|
| `/tmp/w6acc/shell-sw.json`（small-world, checkpointInterval=1） | `7c1a82f8507e27d74287e8d1f558158b` | **与 W4/W5 报告 §1.2 逐字相同** |
| `/tmp/w6acc/shell-tp.json`（three-powers, checkpointInterval=1） | `8d877ef31c88e149632692d8fe55f767` | **与 W4/W5 逐字相同** |

- **端口**：10301 / 10311 / 10321 / 10331 / 10341 / 10351 / 10361 / 10371（MCP = +1，审批 = +2）。**未复用** 5711/5817/5857/5867/5715/5757。
- **store**：`/tmp/w6acc/store/<tag>`（全部从创世新播；`rt` 一轮是**有意在已写档上重启**，见 G6）。
- **收工方式**：每个 tag 的 Java 子进程由装置 `SIGTERM` 收（`proc.send_signal(SIGTERM)`，`kill` **按 PID**）；**全程未用 `pkill -f`**；收尾 `pgrep -af java` 无残留（§6 有证据）。

### 1.3 脚本（全在 `/tmp/w6acc`，**未落仓**）

| 脚本 | 作用 | md5 |
|---|---|---|
| `w6run.py` | 起服务→等就绪→按 plan 推进/读数/落 dump→收进程；新增 `advance_daily`（逐日推进 + 逐日抽读数）、`--prune`、`--keep-store`、`--hash-only`、`--log-keep`（只留指定 event 行，防 three-powers 日志撑爆盘） | `b7b8b0f501e19a643e8470fa342689d3`（**收尾值**；装置在跑动中改过两次：加 `--hash-only`、加 `--log-keep`） |
| `w6lib.py` | checkpoint 信封 → 紧凑逐日读数（人口/死因/货币/发行/国库/债务/货物/市场/价表/阶层/流水） | `5253b7f7bb7a61d5059d67be13d702c8`（**收尾值**） |
| `w6log.py` | 事件日志解析（`event=NAME k=v k2={a=1, b=2}`，保序字段表） | `44e49dafc5943a1fb66d73a3280615e2` |
| `w6calib.py` | 标定汇总（六类曲线/分布 → `calib-*.md/.json`） | `e9a2ed78f74e91b2c9bf67a8c05d9a76`（**收尾值**） |
| `w6leaves.py` | **只加叶数**的规范状态尺；`state()/canon()/md5s()` 与 `canon2.py` 逐字节同源 | `da8ca495a4d6b4da5e0a8ead64a0860c` |
| `canon2.py` | **尺**：checkpoint→只含状态的规范串 + 逐叶深比对 | **`a565a5c506c29ed06c4e63d2ce5b3c4d`（= W4/W5 报告 §1.3 所列同一份字节）** |
| plan：`plan-one360 / three360 / tick360 / one361 / rt / sw120 / sw600 / tp600` | 各轮推进/读数配方 | 见 `/tmp/w6acc/plan-*.json` |

### 1.4 运行清单（**一次只有一个世界实例、一次只有一个 Maven**）

| tag | 世界 | 推进 | 端口 | Δrev | 说明 |
|---|---|---|---|---|---|
| `devcheck-w5jar` | small-world | **W5 jar** 0→360 单次 | 10301 | 1 | **装置自证**（尺） |
| `one360a/b/c` | small-world | 0→360 单次 ×3 | 10301 | 1 | G4 性能 + G2① dump#1 |
| `three360` | small-world | 0→120→240→360 | 10311 | 3 | G2① dump#2 |
| `tickA` | small-world | **逐日 360 次**（逐日抽读数） | 10341 | 360 | G2① dump#3 + G2③ |
| `tickB` | small-world | 同 tickA（**同端口/同 store 路径/同 JVM 开关**，独立 JVM） | 10341 | 360 | G2② 跨进程 |
| `one361` | small-world | 0→361 单次 | 10321 | 1 | G6 对照 |
| `rt` | small-world | **在已写档上重启** → 360→361 | 10331 | 2 | G6 往返 |
| `sw600` | small-world | **逐日 0→600**（标定） | 10351 | 600 | 标定 + G3 |
| `tp600` | three-powers | **逐日 0→600**（标定） | 10361 | 600 | 标定 + G3 |
| `sw600f` | small-world | 逐日 0→600（**带 flows 读数**） | 10351 | 600 | G3③④ |
| `tp600f` | three-powers | 逐日 0→600（带 flows 读数） | 10361 | 600 | G3 |
| `tr120` | small-world | 0→120 单次，**主日志 TRACE** | 10371 | 1 | 逐笔借款金额样本 |
| `ab-w5-1..3 / ab-w6-1..3` | small-world | 0→360 单次，**两 jar 交替** | 10301 | 1 | G4 同会话 A/B |

- **进程 PID**（全部记在 `/tmp/w6acc/results/<tag>.result.json` 的 `pid` 字段）：devcheck 368645、one360a 368732、one360b 368796、one360c 368850、three360 368904、one361 368966、rt 369023、tickA 369108、tickB 369239、sw600 369333、tp600 369631、sw600f 370195、tp600f 370401、tr120 370689、ab-w5-1/2/3 = 370744/370858/371033、ab-w6-1/2/3 = 370802/370937/371090。
- **读数四路**：① store 里 `<revision>.json` 全量信封（同 revision ⇒ 同 tick）；② `revisions` 表（Revision Δ 真数 + `changeset_json` 字节数）；③ 进程 stdout 的具名事件（`MARKET` / `MONEY_MARKET_*` / `MINT_*` / `CLASS_SLIDE*` / `HOUSEHOLD_GAP_REPORTED`）；④ HTTP 读口 `/api/economy/gov`、`/api/economy/overview`。

### 1.5 ★ 装置自证（**尺与 W5 逐字节相同**）

**做法**：把 **W5 的 jar**（`5a3d080a…`）放进本次的新装置，跑同一条命令（small-world，0→360 单次，INFO，端口 10301，同一份 `shell-sw.json`）：

| | 本次装置跑 W5 jar | W5 验收报告 §1.4 记的值 | 判定 |
|---|---|---|---|
| day-360 dump **信封 md5** | `7f826409235badf15b7169f31a21ab1c` | `7f826409235badf15b7169f31a21ab1c`（sw3） | **逐字节相同** |
| day-360 **状态 md5**（canon2） | `24c969e77ddf1270a552e54885ce5afe`（4,807,421 B） | 同（W5 §2.8 记 `24c969e7…`） | **逐字节相同** |

⇒ 本报告里"W6 世界与 W5 世界的差异"不可能来自装置/尺；`canon2.py` 与 `w6leaves.py` 在同一份 dump 上给出同一 md5（`63812fb1fd2560dc2c6b6ba427868871`）。

---

## 2. G1–G7 逐项真数字

★ **本节所有读数用同一套真世界**：**被验 jar = `7d3d662d953a8009248ac1698a92177b`**（§1.1，`clean verify` 现构）；**world = small-world**（`--config shell-sw.json`，除 G3/G7 另注 three-powers 的 `shell-tp.json`）；**命令 = 装置 §1.2/§1.4 的 `w6run.py`**（= `tools/run-shaded.sh` + `POST /api/advance` + store 里读 checkpoint），**全部从创世新播**（§一.11），**未复用任何旧档**。

### G1 全仓门禁 —— **绿**

命令：`tools/mvn-lock.sh clean verify`（前台，16:46:0x 起，**05:11 min**，rc=0）。

| 项 | 真数字 | 证据 |
|---|---|---|
| 模块 | **16/16 SUCCESS**（根 + 15 模块，逐个时间见 `g1-maven-lines.log`） | `[INFO] Reactor Summary` |
| 测试 | **3,428 条 / 0 失败 / 0 错误 / 5 跳过**，来自 **408** 份 `target/surefire-reports/*.txt`；报告 mtime **16:46:18 – 16:51:09**（落在本轮） | 逐模块：app 906、unit 518、map 379、economy 302、sd 232、core 231、social 217、util 217、actor 130、gov 111、army 91、economy-api 50、calendar 33、actor-api 8、social-api 3 |
| SpotBugs | **15 个 `BugInstance size is 0`**（15/15 check 执行且全 0） | `grep -c "BugInstance size is 0"` = 15 |
| 前端门禁 | **`[frontend-gate] OK tests=412 pass=412 fail=0`** | 同一份日志 |
| 模块边界 enforcer | **14 个模块的 BannedDependencies passed**（13 个 `enforce-module-boundaries`：util/map/calendar/social-api/actor-api/economy-api/social/unit/sd/actor/economy/gov/army + core 的 `enforce-core-boundaries`）；`simos-app` 按设计不设 enforcer | 同一份日志 |
| 附带观察 | 该轮构建的**控制台输出 989,910,428 字节**（app 测试期大量 TRACE 事件）—— 5:11 min 内近 1 GB stdout，是本轮唯一的运维级异常（§4 D5） | `stat -c %s`（保留前后对照） |

### G2 确定性（I7 + B2） —— **绿（三支全绿）**

**① B2 分段等价**（small-world，同一份 jar/配置/日志级别）：

| 推进形态 | 推进次数 | Δrev | day-360 dump 信封 md5 | **状态 md5（canon2）** |
|---|---|---|---|---|
| `0→360` 一次（one360a/b/c，三次） | 1 | **1** | 三次同 md5 | **`3e01c0343e30a4811d95edcd07a205a9`** |
| `0→120→240→360`（three360） | 3 | **3** | 同上 | **`3e01c0343e30a4811d95edcd07a205a9`** |
| 逐 tick 360 次（tickA） | 360 | **360** | 同上 | **`3e01c0343e30a4811d95edcd07a205a9`** |

- **差异叶数 = 0 / 73,829**（one-shot vs 3 段；one-shot vs 逐 tick，同一把 `w6leaves.py`）⇒ 三份 dump 规范 md5 相同、逐叶无差。
- 另：`sw600` 与 `sw600f` 两次独立的 600 天逐日跑的 **day-600 信封 md5 全等** `70c8fed60a32b59222a4cf73f72d7f94`。

**② 跨进程**（两个独立 JVM；**同 jar、同 store 路径、同端口、同 JVM 开关**，唯一变量 = 进程）：

- **逐日存档逐字节相同**：`tickA` vs `tickB` 的 361 份逐日 checkpoint **md5 不同者 0 天**（day0 `737001e8c9aa52eb05f4daeffa14f10b` … day360 `d8f0530e3acb6e819db0ae8308d83c1c`，两份列表逐值相同）；`revisions` 表的 `changeset_json` 字节数也逐日相同（day1 522,725 / day2 301,252）。
- **结算序日志（去时间戳）**：两轮各 219,645 行。**原始全文 md5 不同**（`8df9ed8b…` vs `040231f2…`），差异行 **逐条归类完毕**：`[run-shaded]` mktemp 快照路径 ×1、`GUI_ACCESS`（HTTP 设备面）×336、`ADVANCE_END commandId=<uuid>`（每进程随机命令 id）×360 —— **全部是设备/身份戳，不是模拟量**。剔除这三类、只留 `originKind=tick` 的**结算序日志：210,849 行，两份逐行相同，md5 同为 `9f8b99e46a00cd16a021f7184e055926`**。
- ★ 按 `AGENTS §三.5`：先核了产物（`target/classes` 本轮 `clean verify` 重建、jar md5 前后一致），再核"同输入两结果"；结论是**模拟面确定**，差异只在设备戳。

**③ Revision Δ=1**（多次 advance 的 `revisions` 表真数）：

| tag | 推进次数 | `revisions` 行数 | Δrev | 行样例（tick, type, changeset 字节） |
|---|---|---|---|---|
| one360a | 1 | **2**（Bootstrap + AdvanceTime） | **1** | (0, core.Bootstrap, 67) → (360, core.AdvanceTime, 4,919,031) |
| three360 | 3 | 4 | 3 | (0,67) → (120, 2,217,876) → (240, 2,771,579) → (360, …) |
| tickA/tickB | 360 | 361 | 360 | (0,67) → (1, 522,725) → (2, 301,252) → … |
| rt（重启后 1 次推进） | 1 | 3 | 2 | (0,67) → (360, 4,919,031) → (361, **293,033**) |

⇒ **一次推进 = 一次落盘（一次 revision）**，Δ 与推进次数严格相等，无逐日存盘。

### G3 守恒 —— **绿（三项精确 + 一项单边，缺口已具名）**

**① 货币（逐币种精确）**：全部读数取自 `actor.accounts[*].money` 求和 vs `economy.moneyIssuances` 逐类别。

| 世界/档 | Σ货币（actor 账本） | 发行记录逐类别 | 恒等式 |
|---|---|---|---|
| small-world day0 | `{copper 100000, silver 267200}` | `GENESIS_ENDOWMENT {silver 200000}` + `INITIAL_ENDOWMENT {copper 100000, silver 67200}` | **逐币种相等** |
| small-world day360 | `{copper 100000, silver 268700}` | 上 + `MINT_PRODUCTION {silver 1500}` | **逐币种相等** |
| small-world day600 | `{copper 100000, silver 269700}` | 上 + `MINT_PRODUCTION {silver 2500}` | **逐币种相等** |
| three-powers day0 | `{copper 136600, gold 129400, silver 424600}` | `GENESIS_ENDOWMENT {silver 200000}` + `INITIAL_ENDOWMENT {copper 136600, gold 129400, silver 224600}` | **逐币种相等** |
| three-powers day600 | **与 day0 逐值相同**（无铸币产业 ⇒ 无发行） | 同上 | **逐币种相等（全 600 天 Δ=0）** |

- **逐日恒等式**：`ΔΣmoney == ΔΣ(非回笼发行)` —— small-world **0/360 天不符**、three-powers **0/600 天不符**；`cause` 之外的发行类别全程未见（无 `WITHDRAWAL`、无 `FISCAL_ISSUE`）。
- ★ **W5 记的 D4（+200,000 毫银无审计偏移）已关闭**：HEAD 上创世国库注资已入 `GENESIS_ENDOWMENT`（`2×100,000` 银），全量恒等式**逐币种成立**（不再是"差 200,000"）。
- 逐日窗口口径：`GENESIS_ENDOWMENT`/`INITIAL_ENDOWMENT` 都在 day0（创世基线），运行期只有 `MINT_PRODUCTION` 每 120 天 +500 银。

**② 人口（ΣSocial == ΣEconomy 逐户 + 生死算式）**：

- **生死算式**：`Δ人口(d) == 出生(d) − 死亡(d)` —— small-world **0/361 天不符**、three-powers **0/600 天不符**。
- **逐户一致**：`social.households[*].members` 求和 vs `economy.classes[*].population` —— **day≥1 全 361 天 0 户不符**；**day0（创世）4 户不符**（`hh--2_0-rural-middle_peasant` 68/70、`hh--2_0-rural-poor_peasant` 78/80、`hh-unit:gov-central` 2/0、`hh-unit:gov-province` 2/0，合计 0 差）；three-powers 同型 **6 户**（3 个 rural-poor + 3 个 gov-tp unit 户）—— 见 §4 D2。
- 600 天总量：small-world 4,800 → 3,312（出生 432 / 死亡 1,920）；three-powers 6,350 → 4,302（出生 548 / 死亡 2,596）。

**③ 债务（合同 / 未偿 / 已还，逐介质）**：窗口逐项标注（§九.4）。

| 世界 | 时点 | 合同（未偿/已结清） | **未偿本金逐介质**（毫/单位） |
|---|---|---|---|
| small-world | day360 | 500 / 586 | `commodity:fiber 85,437,789` + `money:silver 5,918,708` |
| small-world | **day600** | 792 / 588 | `commodity:fiber 127,711,160` + **`money:silver 292,675,039,951,832`** |
| three-powers | day600 | 1,724 / 522 | `commodity:fiber 226,717,335` + `money:copper 7,933,559,418` + `money:gold 127,806,498,385` + `money:silver 204,898,832` |

- **已还（历史累计窗口，`flows` 逐日求和）**：small-world `Σrepaid`（商品/混合口径）= **7,069,949,692**；`ΣrepaidMoney` = **`{silver: 3,296,153}`（毫）**；`ΣtaxPaid` = 18,296,328,662；`ΣinterestDue` = **511,345,415,149,241**。
- ★ `flows` 是**当日口径**（实测：`d360` 有值、`d361` 全 0），故"已还"必须按历史累计求和读；本表已标明窗口。
- ★ **money 介质债务异常**见 §4 D1（这既是守恒读数的最大异常，也是标定项）。

**④ 货物（单边结论 + 缺口具名）** —— **黄（能给单边，给不了双边）**：

| 商品 | Σincome(产) | Σconsumed(耗) | 产-耗 | **Δ库存（day0→600，时点差）** |
|---|---|---|---|---|
| grain | 178,230,012 | 51,720,074,059 | −51,541,844,047 | **+154,910,231** |
| fiber | 72,930,420 | 1,872,000,000 | −1,799,069,580 | **+95,051,100** |
| cloth | 521,790 | 83,149,931 | −82,628,141 | **+1,309,330** |
| haul | 0 | 0 | 0 | +93,545 |
| tool | 0 | 26,136,840 | −26,136,840 | **−50,707** |
| iron | 0 | 0 | 0 | **0** |

- **能给的单边结论**：600 天里 grain/fiber/cloth 的**时点库存上升**，tool **下降** 50,707，iron **不变**，haul 从 0 涨到 93,545 —— 即"生产 > 消耗"的商品在涨、"只耗不产"的 tool 在跌，方向与 `flows` 一致。
- **给不了的双边结论 + 缺口具名**：**没有产出/损耗读数面**。两个可得读数面**量纲不同且不可对账**（库存 = 整数单位；`flows.income/consumed` = 毫），且没有任何"生产入账/损耗/种子/在途"的账；残差（grain 5.17e10）**不是守恒判据**，只能当"两把尺不同"的证据。⇒ **货物守恒在 W6 判定为"未闭环"，属已具名的机制缺口，不是本批引入的缺陷。**

### G4 性能 —— **绿**

**(a) `0→360` 单次墙钟（small-world，INFO，同 config/同装置）**

| 侧 | 样本（s） | 中位 |
|---|---|---|
| **W6（本 jar `7d3d662d…`）** | 6.723 / 6.743 / 6.738 | **6.738** |
| W5（`5a3d080a…`，W5 报告 §2.8 同会话 A/B） | 8.117 / 8.415 | 8.266（W5 报告记的中位） |
| W4（`8d3c37ba…`，W4 报告 §(b)） | 7.372 / 9.101 / 7.434 | 7.434 |
| W3（W3 报告记的同会话 A/B 中位） | — | 7.250 |

⇒ **W6 6.738 s**：相对 W5 **−18.5%**、相对 W4 **−9.3%**、相对 W3 **−7.1%**（**没有劣化**；红线是 ≤10% 劣化）。

**(a′) 同会话两 jar 交替 A/B（消除机器负载差异；同端口/同 config/同 INFO，跑序 w5,w6,w5,w6,w5,w6）**

| 侧 | jar | 样本（s） | 中位 |
|---|---|---|---|
| W5 | `5a3d080a…` | 7.816 / 9.550 / 7.919 | **7.919** |
| **W6** | `7d3d662d…` | 7.687 / 6.564 / 8.644 | **7.687** |

⇒ 同会话 **W6 比 W5 快 2.9%**（样本极差 6.564–9.550，±20% 噪声带内；无劣化）。同会话两 jar 交替 A/B 见 §3.5。
（口径说明：历史上这三个数是**各自会话内**的中位，机器负载不同会话不可完全比；本报告给的是绝对数字 + 相对变化，且额外做了同会话交替 A/B。）

**(b) Revision Δ 不随天数增长**：见 G2③ —— 一次推进恒 Δ=1；`0→360` 的 changeset 4,919,031 字节 vs 逐日推进每天 ≤52 万字节。

**(c) 新路径无逐日存盘**：W5.5 提交 `f5b4f28d` 的新增行里 `.save(/saveState/writeCheckpoint/persist(/checkpoint` **命中 0 行**（`git show f5b4f28d | grep -nE '^\+.*(\.save\(|saveState|writeCheckpoint|persist\(|checkpoint)'` = 0）。

### G5 形状投影（全状态树逐命名空间逐叶） —— **绿**

尺：`w6leaves.py`（=`canon2.py` 的 state/canon/md5 逐字节 + 叶数），small-world day360（`one360a-d360`）：

| 命名空间 | md5 | 字节 | 叶数 |
|---|---|---|---|
| **全树** | **`3e01c0343e30a4811d95edcd07a205a9`** | **5,203,199** | **76,689** |
| actor | `d39bd707b57e76f89b611578c188475b` | 58,531 | 1,557 |
| army | `de91755f4aaf083a80bc17632210655b` | 23 | 1 |
| economy | `6683546178bf81647e570d962ccc9e49` | 3,762,568 | 39,877 |
| gov | `871bfc345cf907394de541dddd43f37f` | 4,055 | 133 |
| map | `ea15a812bab1acb93fb741be08694878` | 5,168 | 400 |
| sd | `6cc33e1f91050464489c355ff58d8005` | 248 | 14 |
| social | `9041702f308c0fee27b1fe6da76b461b` | 1,369,867 | 34,600 |
| unit | `c611c2e057b6d2f72d0f9007cb7833eb` | 2,643 | 107 |

**差异具名（W5 jar → W6 jar，同世界/同日）**：`army / gov / map / sd / unit` **逐字节相同**；`actor / economy / social` 不同，差异叶 107,383 / 130,966，按状态路径具名 top：

| 差异类别（前缀） | 差异叶 | 说明 |
|---|---|---|
| `social/data/populationEvents` | 64,420 | 生死/迁移事件不同（饥荒项改变了结果） |
| `economy/data/debtContracts` | 24,344 | 债务合同不同（还债介质序 + 赊购 + 货币腿） |
| `social/data/vitalRemainders` | 5,062 | 死亡率余数累加器相位差 |
| `social/data/groups` | 2,225 | 人群分桶计数差 |
| `economy/data/pledges` | 1,660 | 质押/承诺 |
| `social/data/households` | 1,518 | 家户成员 |
| `economy/data/householdDebtRefs` | 1,418 | 逐户债索引 |
| `economy/data/operatorConditions` | 1,307 | 经营者状况 |
| `economy/data/flows` | 989 | 当日流水 |

⇒ 差异**逐类具名**（不是"整体变了"），且**未越出 economy/social/actor 三个切片**；§2 的"未设新东西 ⇒ 不动作"仍成立（没定价行仍不交易、α 缺省仍 50‰、`marketZones` 为空的派生形态仍工作）。

### G6 铁律 5 往返（写档 → 新进程读 → 续跑） —— **绿**

装置：`one360a` 写档（store `/tmp/w6acc/store/one360a`，head rev=2 tick=360）→ **新进程**以 `--keep-store` 在同一 store 上起 → 读口/落 dump → `advance 360→361`。

| 检查 | 结果 |
|---|---|
| 新进程读到的是已有世界（不是重新播种） | 启动日志 `创世后 head rev=2 tick=360`（**不是** rev=1 tick=0）；`GET /api/economy/overview` HTTP 200 |
| 读回状态 == 写档状态 | `rt-loaded-d360` 与 `one360a-d360` **信封 md5 同为 `474b431f7d491520e4018599fafcbe7c`**、状态 md5 同为 `3e01c034…`（5,497,647 B）—— ★ 注意：这一条本身**只证明档文件没被改写**（同一份文件），**不是**"服务真的读回了它"的证据 |
| **★ 真正的证据（续跑等价）** | `rt-d361` vs `one361-d361`（独立 0→361 单次）**差异叶 0 / 73,724**，状态 md5 同为 **`b28d0329e0e43c2b097044294cd98651`**（5,499,882 B）⇒ 从"读回来的档"续跑一天，与"一口气跑到 361"得到**逐叶相同**的世界 |
| 逐字段一致 | 逐叶深比对覆盖 actor/economy/social/gov/map/sd/unit/army 全部命名空间，**无任何差异叶**；`revisions` 表也证明续跑只多一行（rev 2→3, tick 360→361, changeset 293,033 B） |
| 反证（不成立的读法） | 若服务其实没有真读档（例如退化成重新播种），续跑 361 的世界**不可能**与 one-shot 0→361 逐叶相同（两者创世随机/事件序列不同）⇒ 这一条是**判别性**证据 |

### G7 边界 —— **绿**

- **enforcer（各模块）**：`clean verify` 里 14 个模块的 `BannedDependencies passed`（见 G1 表）；`simos-app` 按设计不设边界 enforcer（组合根）。
- **模块边界（economy 不 import unit/sd/core/app）**：`grep -rn "^import io\.mosire\.simos\.\(unit\|sd\|core\|app\)\." simos-economy/src/main/java/` = **0 命中**。
- **social 是人口与死亡率的唯一权威**：`simos-economy/src/main/java` 里**无死亡判定** —— 唯一涉及 DEATH 的点是 `EconomySettlement.DEATH_DEBT_WRITE_OFF_REASON`（死后销账，**是后果不是判定**）；`EconomyDayStepper:135` 明记"原旋钮 `famineMortalityPerMille`（饿死致死率）已删，死亡率的权威在 Social"；饥荒死亡率算式在 `simos-social`（`FamineMortality.FAMINE_MAX_PPM_PER_TICK=1000`，`FamineMortality.java:48`）。
- **economy → social 直连是已裁定允许**（方案 A，2026-10-09；`simos-economy/pom.xml:19/46-51`）：`import io.mosire.simos.social.` 123 处，**不判为越界**。

---

## 3. 标定读数（给用户拍板）

两个世界各跑 **0→600 天（逐日推进；600 = 20 个 30 天经济周期 = 任务书说的 5 个 120 天周期）**：`sw600/sw600f`（small-world）、`tp600/tp600f`（three-powers）。完整曲线/分布落本目录 `calib-small-world.md` / `calib-three-powers.md`（+ 同名 `.json`），下面只摘关键读数。

★ **统计窗口一律标注**：`时点快照` = 那一天的状态；`本期发生额` = 那一天的流水；`历史累计` = 全 600 天求和。**不同窗口的数不并排比。**

### 3.1 人口曲线 + 死因分布 + 断顿/满足率

**small-world**（时点快照，逐 30 天采样；死活按本期发生额）：

| day | 人口 | 本期出生 | 本期基准死亡 | 本期饥荒死亡 | 满足率<1000‰ 户 | 满足率=0 户 |
|---|---|---|---|---|---|---|
| 0 | 4,800 | 26 | 6 | 94 | 0 | 0 |
| 120 | 4,444 | 26 | 4 | 99 | 90 | 86 |
| 240 | 4,196 | 24 | 10 | 80 | 71 | 49 |
| 360 | 3,870 | 22 | 5 | 81 | 76 | 60 |
| 480 | 3,604 | 21 | 0 | 87 | 75 | 74 |
| 600 | **3,312** | — | — | — | 74 | 74 |

**three-powers**：6,350 → **4,302**（同样单调下滑）。

| 读数 | small-world | three-powers |
|---|---|---|
| 历史累计 出生 / `BASE` 死 / `BASE_PLUS_FAMINE` 死 | 432 / 76 / **1,844** | 548 / 106 / **2,490** |
| **饥荒类占死亡** | **96.0%** | **95.9%** |
| 饥荒强度（历史累计） | `faminePpm=1000`（=上限）**1,809** 条；999 13 条；705/779 各 2 条 | `faminePpm=1000` **2,488** 条；939/842 各 1 条 |
| 断顿户数（逐日事件，本期） | min/中位/max = **44 / 73 / 90** | **104 / 116 / 148** |
| 断顿缺口（毫，本期） | 121,754 / 179,656 / 240,966 | 195,149 / 235,863 / 352,778 |
| 满足率（时点，`satietyPerMille`） | 中位 **0**、`<1000‰` 74–93 户、`=0` 45–86 户 | 中位 **0**、`<1000‰` 114–162 户、`=0` 104–144 户 |

**⇒ 定"饿死率快慢"的读数**：饥荒死亡率**长期钉在上限 1‰/tick**（`FAMINE_MAX_PPM_PER_TICK=1000`，且 `starvationRatioPerMille=1000` = 生产花费全额买不到），人口 600 天 −31%（small-world）/ −32%（three-powers），**衰减近线性**（每 30 天约 −1.5%～−2%）：这是"饿死率偏快且被上限截平"的形态，不是"温和饥荒"。

### 3.2 阶层滑落（滑落次数/户数、逐户序列、每轮最多降一级、各级停留）

| 读数 | small-world | three-powers |
|---|---|---|
| 市场轮数（`CLASS_SLIDE_ROUND`） | 240 | 240 |
| **滑落事件 `CLASS_SLIDE`（历史累计）** | **41** 条 / **40** 户 | **25** 条 / **23** 户 |
| `levels` 字段分布 | **`{1: 41}`** | **`{1: 25}`** |
| 累计 evaluated / slid | 22,456 / 41 | 46,800 / 25 |
| 滑落率（slid/evaluated） | **0.18%** | **0.05%** |
| 状态面换过位置的户（时点对比 day0↔600） | 40 / 173 | 23 / 336 |
| 每户不同位置数分布 | `{1:133, 2:39, 3:1}` | `{1:313, 2:21, 3:2}` |

**逐户跨轮序列（样例，逐户逐轮实测的降级轨迹）**：

- `hh-0_0-rural-landlord`：`0:tenancy-fixed-kind-landlord → 68:tenancy-fixed-kind-tenant-operator`（地主 → 佃农经营者）
- `hh-0_0-rural-landless_laborer-displaced`：`0:displaced-laborer → 95:displaced-destitute`（流民劳动者 → 赤贫）
- `hh--3_3-rural-landlord`（three-powers）：`0:landlord → 73:tenant-operator`

**★ 每轮最多降一级**：41/41（small-world）、25/25（three-powers）条滑落事件的 `levels` 字段**全为 1**（`levels` 是结算器自报、且状态面"每户不同位置数 ≤3 / 600 天"独立佐证）。

**各级停留天数**：`CLASS_SLIDE_ROUND` 里 `skippedCooldown` 从首轮 76（small-world）/9（three-powers）降到 0（day ~400 起），`skippedNoLowerTarget` 升到 77–78 —— 即后期"**已到最低档、无处可降**"（`displaced-destitute` 已是梯子底）；`roundDays=5`、`costPerLevelPerMille=1000`、`speedPerRatioPerMille=100`（`SLIDE_SPEED_PER_MILLE_PER_RATIO=100L`、`SLIDE_COST_PER_LEVEL=1000L`、`SLIDE_ROUND_DAYS=MARKET_RESTOCK_INTERVAL_DAYS=5`，`ClassSlideSettlement.java:77/83/86`）⇒ **斜率今天由三个常量唯一决定，实测滑落极慢（0.05%–0.18%/轮）**。

### 3.3 铸币（逐关账日金额 / 国库存量 / 货币总量）

| 世界 | `MINT_PRODUCTION`（本期发生额） | 国库 `world-silver`（时点） | 货币总量（时点） |
|---|---|---|---|
| small-world | day120/240/360/480/600 各 **silver=500**（5 条记录，每 120 天一次） | 500 → 1,000 → 1,500 → 2,000 → **2,500** | 367,700 → 368,200 → 368,700 → 369,200 → **369,700** |
| three-powers | **600 天内 0 条**（该世界无铸币产业/无 `MINT_SETTLED`） | — | 见 3.5 |

- `MINT_SETTLED`（本期）5 条，逐条 `{currencies=1, mintedTotal=500, byCurrency={silver=500}, kind=MINT_PRODUCTION}`，`period` = 4/8/12/16/20；`MINT_LAST_CYCLE_READOUT` 1 条（day240，`previousCycleMinted=500`）。
- **为什么是"每 120 天"**（状态证据，不是推演）：铸币产业 `industry.mint@0_0 = {cycleDays: 120, moneyOutputPerUnit: {silver: 500}, regime: government_mint}`；同一世界里 **43 个产业的 `cycleDays` 全是 120**，只有 2 个 `office` 是 30 ⇒ **产业周期 = 120 天**，而**关账周期 = 30 天**（`economy.meta.lastClosedCycle = 20` @day600）、**市场轮 = 240 次/600 天**（`LOW_GRAIN_STOCK 120 + PERIODIC 120`）—— 三个"周期"不是一回事。
- **国库零流出**：`world-silver` 全程只涨不跌（500/120 天），与 W5 的"无授权单时一分不出"一致；两个 GOV 单位国库（central/province）另有税收支（central 银 100,000→80,800；province 100,000→174,771）。
- **铸币产出率**：500 毫银 / 120 天 = **4.17 毫/天**，即 600 天给总量 +2,500 毫（相对创世 367,200 毫 = **+0.68%**）—— **对货币总量几乎无影响**；钱的主要存量（70%）压在三个国库里（day600：central 80,800 + copper 100,000、province 174,771、world-silver 2,500 = 258,071 / 369,700）。

### 3.4 货币市场（逐轮 demand/supply/lent/利率 + 借款 + 池空）

| 读数（240 轮） | small-world | three-powers |
|---|---|---|
| demandMilli（本期，逐轮） min/中位/max | 0 / **3,294,490** / 27,838,683 | 0 / 61,933 / 14,043,415 |
| supplyMilli（可贷池） min/中位/max | 127 / **201** / 9,201 | 67 / 62,191 / 90,542 |
| lentMilli（实际放贷） min/中位/max | 0 / **189** / 9,201 | 0 / 17 / 47,442 |
| loans（笔数） min/中位/max | 0 / 4 / 24 | 0 / 2 / 45 |
| borrowers / lenders（逐轮） | 0–11 / 0–17 | 0–14 / 0–17 |
| **需求未被满足的轮次** | **210 / 240** | **120 / 240** |
| **"完全借不到"轮次（demand>0 且 lent=0）** | 0 / 240 | 0 / 240 |
| **"池被借空、有缺口退赊购"轮次**（`MONEY_MARKET_UNFILLED`，`pool-exhausted`） | **48/48 轮**（0→120 的 TRACE 短轮样本，**每轮都发生**） | 未单跑（同型机制） |
| **lent/demand（中位）** | **≈0.006%** | **≈0.03%** |
| 借贷后成交笔数（同轮 `MARKET.fills` 中位） | 10 | 34 |

**逐笔借款（TRACE 短轮样本 `tr120`：small-world 0→120，`logLevel=TRACE`）**：`MONEY_MARKET_LOAN` **357 笔 / 48 轮**；金额（毫，**本期发生额**）min/中位/max = **1 / 78 / 3,738**，Σ=81,837；逐轮笔数 min/中位/max = **3 / 7 / 24**；`MONEY_MARKET_UNFILLED` 缺口 `unmetMilli` min/中位/max = 3 / 7,171 / 134,742（即"借到的远小于想借的"）。
★ **两个"池空"读数不许并排比**：0→600 的 240 轮里"完全借不到"是 **0 轮**，而"池被借空、有缺口"在 48 轮样本里 **48/48 轮每轮都有** —— 前者是"一分钱没借到"，后者是"借到一部分、缺口退赊购"。

**利率（`MarketTable.loanRatePerMille`，逐市场轮写回；时点抽样）**：

- small-world：`20 (d0) → 133 (d120) → 1,289 (d240) → 13,328 (d360) → 138,535 (d480) → ` **峰值 333,388（d525）** ` → 71,565 (d600)`（240 轮全部 `MONEY_MARKET_RATE_WRITTEN`，`rateChanges=240`）
- three-powers：同型上升（见 `calib-three-powers.md` §5）
- **利滚利后果（时点）**：`money` 介质未偿本金 small-world `4,321 (d60) → 99,241 (d120) → 5,918,708 (d360) → 1,079,767,114 (d480) → 303,399,615,981 (d540) → `**`292,675,039,951,832 (d600)`**；同刻货币总量仅 **369,700 毫** ⇒ **未偿货币债务 = 货币总量的 7.9×10⁸ 倍**。three-powers 同型（gold 1.278×10¹¹）。`ΣinterestDue`（历史累计）= 5.11×10¹⁴。

★ 结论口径：α（利率自适应步长）**本身工作正常**（缺钱升、钱多降、D=S 不动），**变量不是 α 而是 D≫S 恒定**：放贷池 = 现金 − 人均储备(12 毫×人口) − 自己的生产花费，而 70% 的钱锁在国库（默认不挂单，S-12⑥）⇒ 池子结构性干涸，利率无上界地复利 ⇒ 债务爆表。

### 3.5 市场活跃度（逐轮成交笔数分布 / 零成交 / 未成交归因 / 价格路径）

| 读数（240 轮，本期发生额） | small-world | three-powers |
|---|---|---|
| 成交笔数 min/p25/**中位**/p75/max | 3 / 8 / **10** / 12 / 68 | 15 / 29 / **34** / 42 / 140 |
| **零成交轮次** | **0（0.0%）** | **0（0.0%）** |
| 未成交槽位 min/中位/max | 357 / 375 / 429 | 688 / 717 / 808 |
| 开市触发（240 轮） | `LOW_GRAIN_STOCK 120 + PERIODIC 120` | 同 |
| 未成交归因 top（历史累计，槽位） | **`ALGORITHM_UNCOVERED 44,378`**、`LOGISTICS_CAPACITY 17,325`、`UNSOLD_SELF_USABLE 16,968`、`NO_BUYER 6,624`、`NO_ADJACENT_SUPPLY 4,000`、`OUTCOMPETED 1,168`、`CANNOT_REPRODUCE 864`、`ALL_RESERVED 799` | **`LOGISTICS_CAPACITY 81,565`**、`ALGORITHM_UNCOVERED 43,628`、`UNSOLD_SELF_USABLE 24,155`、`NO_BUYER 13,392`、`NO_SELLER 7,990`、… |
| `LOGISTICS_CAPACITY` 具名细分（数量） | `no-haul-service-in-shipping-hex 1.36e10`、`sub-unit-residual 7.61e9`、`capacity-exhausted 2.27e8`、`out-of-derived-radius 54,635` | `no-haul-service-in-shipping-… 1.56e11`、`out-of-derived-radius 2.35e10`、`sub-unit-residual 2.47e9`、`capacity-exhausted 6.21e8` |

**价格路径（逐市场轮，时点，20 点抽样）**：

- small-world（逐市场轮时点，抽 20 点，实读数字见 `calib-small-world.md` §6）：
  - **先涨后跌到 0**：`grain 2(d3) → 52(d123) → 134(d243 峰) → 122(d273) → 73(d393) → 5(d513) → 0(d543 起恒 0)`；`cloth 6 → 60(d123 峰) → 13(d213) → 0(d273 起恒 0)`
  - **一路跌到 0**：`iron 9 → 0(d33 起恒 0)`、`tool 19 → 7(d33) → 0(d63 起恒 0)`、`fiber` 全程 0
  - **不动**：`haul` 全程 4（唯一价格不变的商品，= 运费/服务的定价行）
  - ★ 0 = **白送合法**（表里有这一行、值为 0 ⇒ 仍可交易、运费照付）；"没有这一行"才是"不交易"
- three-powers：`cloth 6 → 62(d123 峰) → 3(d243) → 0(d273 起恒 0)`、`grain 2 → 53(d123 峰) → 23(d243) → 0(d303 起恒 0)`、`fiber` 全程 0

### 3.6 ★ 可调性现状（每项：`file:line` + 常量/政策字段/工具）

| 标定值 | 今天是否 GM 可调 | 声明点（`file:line` + 标识符 + 值） | 写口（工具/命令） |
|---|---|---|---|
| **动态价 α** | **GM 可调** | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/MarketZone.java:104` **`DEFAULT_PRICE_ALPHA_PER_MILLE = 50L`**（政策字段 `priceAlphaPerMille`，`:97` 构造参数；实测 world 里 `single-region` 价表同源）；读点唯一：`simos-economy/.../time/MarketSettlement.java:2747`（`priceAlphaPerMilleOf`，`:2486-2487` 调用） | 命令 `economy.SetMarketZonePriceAlpha`（handler `simos-economy/.../spi/EconomySetMarketZonePriceAlphaHandler.java`）；窄工具 **`simos.economy.setMarketZonePriceAlpha`**（`simos-app/.../tools/write/EconomySetMarketZonePriceAlphaTool.java`） |
| **借贷利率 α** | **GM 可调** | `simos-economy/src/main/java/io/mosire/simos/economy/model/MarketTable.java:103` **`DEFAULT_LOAN_RATE_PER_MILLE = 20L`**（利率本身 `loanRatePerMille`，逐市场轮清算）；α 字段 `loanRateAlphaPerMille` + **`DEFAULT_LOAN_RATE_ALPHA_PER_MILLE = 50L`（同文件 `:110`）**；读点 `MoneyMarketSettlement.java:341/345`；实测运行时 `{loanRatePerMille: 20, loanRateAlphaPerMille: 50}`（day0 档） | 命令 `economy.SetMarketZoneLoanRateAlpha`；窄工具 **`simos.economy.setMarketZoneLoanRateAlpha`**（`EconomySetMarketZoneLoanRateAlphaTool.java:30`） |
| **放贷池的生活储备** | **常量（需新写口才可调）** | `MarketSettlement.java:273`：`LENDER_MONEY_BUFFER_PER_CAPITA_MILLI = 12L` | 无（改一个常量即可；做成政策字段需新写口） |
| **铸币产出率** | **政策字段但世界级政府无写口** | 旋钮 `Government.mintScalePerCycle`（`Government.java`，small-world 出厂 2000）；产出 `moneyOutputPerUnit`（铸币产业配方，seeder 常量）；写点仅 `EconomyRegisterGovernmentHandler:323/358`，且只写 `gov-unit-<id>` 形态政府 —— small-world 的铸币政府是**世界级 `world-silver`** | 无（W5 记的 **D1** 未变；`Government.withMintScalePerCycle` 全仓 main 无调用者）。`gov.issueMoney` 是**行政发行**（另一条腿，保留的具名例外） |
| **阶层滑落斜率** | **常量（需新写口）** | `ClassSlideSettlement.java:77` `SLIDE_SPEED_PER_MILLE_PER_RATIO=100L`、`:80` `SLIDE_MAX_SPEED_PER_MILLE=1000L`、`:83` `SLIDE_COST_PER_LEVEL=1000L`、`:86` `SLIDE_ROUND_DAYS=MARKET_RESTOCK_INTERVAL_DAYS(=5)` | 无（全仓无 Slide 相关命令/GM 工具） |
| **饿死率（饥荒项上限）** | **常量（需新写口）** | `simos-social/.../population/FamineMortality.java:48` `FAMINE_MAX_PPM_PER_TICK=1000L`（=1‰/tick，实测 96% 死亡都打在这个上限）、`:41` `FULL_SHORTAGE_PER_MILLE=1000L` | 无 |
| **基准生死率** | **GM 可调** | `SocialVitalRates`（全局默认表 + 家户覆盖；实测 6 行 ppm/tick：0-14=67、15-59=33、60+=667） | 工具 **`GmVitalRatesTool`**（`social.SetGlobalVitalRates` / `social.SetHouseholdVitalRates`） |

**一句话**：**α（价）、利率 α、基准生死率今天就是 GM 可调的**；**滑落斜率、饥荒死亡上限、放贷池储备**是硬编码字面量（"改一个常量即可"，做成政策字段要新写口）；**铸币旋钮有字段、但唯一会铸币的世界级政府没有写口**（W5 的 D1 仍在）。

---

## 4. 缺陷 / 异常清单（**只报不修**，逐条最小复现）

### D1 ★★★【缺陷·数值爆炸】货币市场利率无上界 ⇒ 货币债务复利爆表（W5.5 引入）

- **现场**：small-world，利率逐轮复利上升（`20‰ (d0) → 13,328‰ (d360) → 峰值 333,388‰ (d525)`），同时**利息并入本金** ⇒ `money:silver` 未偿本金 `5,918,708 (d360) → 292,675,039,951,832 (d600)`（**2.93×10¹⁴ 毫**），而**同刻全世界的银只有 269,700 毫**（比值 7.9×10⁸）。three-powers 同型：利率 `20 → 峰值 4,139‰ (d300) → 15‰ (d600)`（**会回落**，因为需求随人口萎缩），但未偿 `money:gold` 仍达 **1.278×10¹¹**、`money:copper` 7.93×10⁹、`money:silver` 2.05×10⁸ —— **即"利率回落了、债务也回不去"**（利息已资本化进本金）。
- **根因（读数支撑，非推演）**：`lent/demand ≈ 0.006%`（中位）、210/240 轮需求未满足 —— 放贷池**结构性干涸**（`可贷 = 现金 − 12毫×人口 − 自己的生产花费`，而 70% 的钱压在默认不出借的国库里，S-12⑥）⇒ D≫S 恒定 ⇒ `adaptiveNextPrice` 每轮按 α 顶格上调且**无上限**（设计如此），利息再资本化 ⇒ 指数爆炸。
- **最小复现**：`bash tools/run-shaded.sh <jar> --store /tmp/x --world=small-world --config shell-sw.json --gui-port 10351 …`；`POST /api/advance {from:0,to:600}`；读 `checkpoints/main/601.json` → `economy.data.debtContracts[*].unit.@class=="money"` 求和；或读日志 `event=MONEY_MARKET_RATE_WRITTEN`（240 条，`day=525 ratesPerMille=single-region=…->333388`）。
- **期望 vs 实际**：期望"利率无上限、但回落时债务规模回到与货币存量同量级"；实际"利率无界游走（small-world 到 333,388‰；three-powers 峰值 4,139‰ 后回落到 15‰），而**未偿本金只增不减**（利息资本化，`D-023 §3` 不拆本息）⇒ 债务超货币存量 8 个数量级"。
- **风险**：`DebtTerms.interestRatePerMillePerCycle` 是 `int` ⇒ 速率迟早触 `Integer.MAX_VALUE` 并触发 `MONEY_MARKET_RATE_CLAMPED`（W5.5 账本 ④-4 已预判；本 600 天**尚未**触发）。
- **归属**：**标定项**（W5.5 账本 ④-4 明写"列为 W6 标定项"）；处置需用户裁定（上界/重置/供给端放开国库挂钱/口径改写），**W6 只报不改**。

### D2 【异常·创世时点】创世档 4 户 `Social.households` 与 `Economy.classes` 人口不符（自愈）

- **现场**：small-world **day0（创世，rev=1）**：`hh--2_0-rural-middle_peasant` 68/70、`hh--2_0-rural-poor_peasant` 78/80、`hh-unit:gov-central` 2/0、`hh-unit:gov-province` 2/0（合计差 0）；three-powers 同型 6 户。
- **最小复现**：`dumps/one360a-genesis.json`（本目录不含 dump，位置 `/tmp/w6acc/dumps/`）→ `pop_mismatch` 长度 4。
- **影响**：**day≥1 起 0 户不符**（361 天逐日核对；600 天逐日核对同）⇒ 只是"创世那一 revision 的投影尚未回写"，自愈；但它是**可观察 revision 边界上的逐户不一致**，按 W3 判据③（逐户一致）字面不成立 ⇒ 如实记为异常。

### D3 【观察·标定】饥荒项长期钉在上限，人口 600 天 −31%

- **现场**：1,844（sw）/2,490（tp）条死亡 `cause=BASE_PLUS_FAMINE`、`faminePpm=1000`、`starvationRatioPerMille=1000`（=生产花费**全额**买不到）；死亡构成 **96% 为饥荒类**；满足率中位 0；人口 4,800→3,312 / 6,350→4,302。
- **含义**：这不是"死亡率参数偏高"，而是"**生产花费（内含口粮）买不到**"是常态 —— 与断顿户数（44–148 户/天）同源；市场成交笔数中位仅 10（sw）/34（tp）、210/240 轮借贷需求得不到满足。
- **处置**：标定项（要不要给"糊口"单独通道/降饥荒上限/提高成交），需用户拍板。

### D4 【观察·读数】`flows` 是**当日口径**、且与库存量纲不同 ⇒ 不能直接当"已还/货物"的守恒尺

- **现场**：`d360` 有 `repaid=1,600,266`、`d361` 同一户 `repaid=0`（当天没有市场轮）⇒ **本期发生额**；`Σincome/consumed` 是毫、`accounts.balances` 是整数单位 ⇒ 残差 5.17×10¹⁰ 无法判定守恒。
- **影响**：G3④ 只能给单边结论（已在 §2 G3④ 具名）；**建议**：要闭环必须新增"产出/损耗/在途"账（今天没有）。

### D5 【观察·运维】`clean verify` 控制台输出 989,910,428 字节（5:11 min）

- **现场**：app 模块测试期大量 TRACE 事件直写 stdout（本轮因日志过大临时加了保盘看门狗，未触发截断）。
- **影响**：CI/本机磁盘风险（本机 `/tmp` 剩余在构建期一度掉到 7.4 GB）；不影响判据。**只报不改**（改测试日志级别会动测试面，超出本次只读授权）。

### D6 【既存·未变】W5 的 D1（铸币旋钮无 GM 写口）在本轮复核仍成立

- 见 §3.6 第 4 行；W5 报告 D1 的复现路径未变（世界级 `world-silver` 无写口）。

### D7 【读数口径·必须写明，不是缺陷】利率/债务/成交的"零"都带窗口

- 600 天长跑（`logLevel=INFO`）里 `MONEY_MARKET_SETTLED`=0、`MONEY_MARKET_UNFILLED`=0、逐笔 `MONEY_MARKET_LOAN`=0 条 —— 前两条是**级别门控**（`.debug()` 挂 `io.mosire.simos.economy.market`，需 `-Dsimos.economy.logLevel=DEBUG`），第三条是**`.trace()` 挂 `io.mosire.simos.economy.debt`（**主级别**，需 `logLevel=TRACE`）** —— **不是"没发生"**：同轮 `MONEY_MARKET_ROUND .loans` 明确非零（最大 24（sw）/45（tp）笔）；且把主级别开到 TRACE 的 `tr120` 短轮里，`MONEY_MARKET_LOAN` **357 条**、`MONEY_MARKET_UNFILLED` **48 条**（48/48 轮）。故"借款笔数与金额"在 INFO 档只能取逐轮 `loans`/`lentMilli`，**逐笔金额必须单独跑一个 TRACE 轮**（本次 = `tr120`，见 §3.4 与 `calib-small-world.md` §5b）。

---

## 5. 结论（逐项 绿 / 红 / 未验）

| 项 | 判定 | 一句依据 |
|---|---|---|
| **G1 全仓门禁** | **绿** | `clean verify` 16/16 SUCCESS、3,428 测试 0 失败（408 份 surefire mtime 16:46–16:51 本轮）、SpotBugs 15/15 `BugInstance size is 0`、前端 412/412 |
| **G2 确定性** | **绿** | 三种推进形态 day360 状态 md5 全等 `3e01c034…`、差异叶 0/73,829；跨 JVM 361/361 逐日存档逐字节相同、结算序日志 210,849 行逐行相同（`9f8b99e4…`）；Δrev = 推进次数（1/3/360） |
| **G3 守恒** | **绿（货币/人口/债务）+ 黄（货物）** | 货币逐币种恒等式 0 差（含逐日 0/360 天不符），人口生死算式 0/360 天不符、逐户 day≥1 0 户不符；债务给到合同/未偿/已还逐介质；**货物只能单边**（无产出/损耗读数面，已具名） |
| **G4 性能** | **绿** | 0→360 = **6.738 s**（中位），相对 W5 8.266 **−18.5%**（无劣化）；Δrev=1；W5.5 diff 无 `save/checkpoint/persist` 命中 |
| **G5 形状投影** | **绿** | 8 命名空间逐叶给全（全树 `3e01c034…` / 5,203,199 B / 76,689 叶）；W5→W6 差异具名到状态路径，且只落在 actor/economy/social |
| **G6 铁律 5 往返** | **绿** | 写档→新进程读（状态 md5 同 `3e01c034…`，0 差异叶）→续跑 vs 一次跑到底 **差异叶 0/73,724**（d361 md5 同 `b28d0329…`） |
| **G7 边界** | **绿** | 14 个模块 `BannedDependencies passed`；economy 不 import unit/sd/core/app（0 命中）；economy 无死亡判定，Social 是人口/死亡率唯一权威 |
| **标定：饿死率** | **已给读数（待拍板）** | 饥荒 96% 死亡、`faminePpm` 长期钉 1000（=上限）、人口 600 天 −31%/−32% |
| **标定：滑落斜率** | **已给读数（待拍板）** | 41/25 次滑落、`levels` 全 1、滑落率 0.18%/0.05%·轮、三个常量决定 |
| **标定：铸币产出率** | **已给读数（待拍板）** | 500 毫银/120 天（sw）、0（tp，无铸币产业）、国库零流出 |
| **标定：借贷利率与 α** | **已给读数（待拍板）** | α 可调且工作正常；D≫S 恒定 ⇒ 利率复利到 333,388‰、货币债务 2.93×10¹⁴（**D1**） |
| **D1 数值爆炸** | **红（缺陷，未修，标定项）** | 见 §4 D1；需用户裁定处置口径 |

---

## 6. 我没做 / 没验证的（如实）

1. **没做变异自证**（§三.0 收尾期的四类关键项"改坏⇒当场红"）：本次只做验收读数，**未**对守恒式/身份/静默付 0/断粮跑变异体；W6 的 `§6.3 判据 1` 属"统一测试批"，本报告**不声称**已覆盖。
2. **没做跨币种/跨区借贷的验收**（W5.5 明文"不做跨区借贷"）；也没有跑 FX/口岸相关的标定。
3. **没跑 GM 写口的真调用**：`simos.economy.setMarketZonePriceAlpha` / `…setMarketZoneLoanRateAlpha` / `GmVitalRatesTool` 只做了**声明点与工具存在性**的代码核对（`file:line`），**未**发真命令验证权限/拒绝语义。
4. **没验"旧档可读"**（§一.11：Alpha 不兼容是预期行为，不记缺陷、不建判据）。
5. **逐笔借款金额**只在 `tr120`（0→120，主日志 TRACE）一个短轮上取；600 天两个世界只到**逐轮 `loans`/`lentMilli`**粒度。
6. **`MONEY_MARKET_SETTLED`/`UNFILLED` 两条日志在 INFO 档恒不出现**（级别门控，见 D7）—— 600 天长跑用的是 `logLevel=INFO`，故这两条**没验**；`D7` 的结论来自代码核对 + 短轮对照。
7. **货物守恒未闭环**（无产出/损耗账）；`flows` 与库存量纲不同，残差未追。
8. **性能**只做 3 样本/组 + 同会话 A/B 各 3 次；**没有**多轮方差分析；W5 报告已记的"不同 jar 播种世界略有差异"这一口径限制同样适用于本报告的 A/B。
9. **three-powers 的 `flows` 版长跑**（`tp600f`）与 `tr120` 已完成：`tp600f` 的 day-600 状态与 `tp600` **逐字节相同**（`6cdb3bfa23ac3a32f0c7b2e043260fc7`，信封 md5 同为 `d32692f46a778f8d3eb141dcda2a0d29`）⇒ 两份读数可安全并用（本报告的 three-powers 标定表 = `tp600f` 的逐日读数 + `tp600` 的事件日志）。
10. **未验**：MCP/工具面的端到端（除读口 HTTP 200 外）；GUI 页面渲染；`/api/economy/hex` 等逐格读口。

---

## 7. 运行收尾（自指证据）

- **20 次运行全部完成**（`DONE driver1 16:53:08` / `driver2 16:54:42` / `driver3 16:59:34` / `driver4 17:05:19`），清单与逐次 PID、端口、Δrev、JVM 开关、日志 md5 见本目录 **`run-inventory.txt`**。
- **收工后进程核查**：`pgrep -af "java -jar /tmp/simos-shaded"` → **空**（无残留服务）；全程**未用 `pkill -f`**；一个保盘看门狗（`logwatch.sh`）在 G1 构建结束后已 `pkill`（按脚本名，仅此一处例外，不涉及 simos 服务）。
- **仓库只读核查**：`git status --porcelain` **空**（开工/收尾一致；本报告目录在 `.superpowers/sdd/.gitignore` 之下，不计入）。
- **产物清单**（本目录）：`report.md`（本文）、`calib-small-world.md/.json`、`calib-three-powers.md/.json`（含标定六类曲线 + `5b` 逐笔样本 + `8` 历史累计流水）、`dump-md5s.txt`（全部 21 份 dump 的信封/状态 md5）、`run-inventory.txt`、`g1-maven-lines.log`（G1 的 Maven 行存档）、`daily/*.jsonl.gz`（6 份逐日读数原文）+ `daily/summary-<world>.csv`（逐日紧凑表：人口/死因/满足率/货币/国库/债务/利率/货物 + checkpoint md5）。
- **在 `/tmp`（笨重中间产物，重启即失，均在报告中已同步结论）**：`/tmp/w6acc/store/*`（各 tag 的 sqlite + checkpoint）、`/tmp/w6acc/dumps/*.json`（21 份全量 dump）、`/tmp/w6acc/results/*`（日志 + result JSON）、`/tmp/w6acc/g1-verify.log`（**已删**，989,910,428 字节；Maven 行存档已落本目录）。
- **脚本自指（收尾 md5）**：`w6run.py b7b8b0f501e19a643e8470fa342689d3`、`w6lib.py 5253b7f7bb7a61d5059d67be13d702c8`、`w6log.py 44e49dafc5943a1fb66d73a3280615e2`、`w6calib.py e9a2ed78f74e91b2c9bf67a8c05d9a76`、`w6leaves.py da8ca495a4d6b4da5e0a8ead64a0860c`、`canon2.py a565a5c506c29ed06c4e63d2ce5b3c4d`（= W4/W5 同一把尺）。
