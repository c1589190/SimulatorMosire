# W5 验收报告 —— 钱的来源（铸币生产方式入市）

- **角色**：W5 验收 Agent（**只读验收**：不改仓库任何源码/测试/文档；装置、store、脚本、日志全落 `/tmp/w5acc/**`；报告落本文件）
- **被验对象**：`24af7bf1`（HEAD，工作树 `git status --porcelain` 开工/收尾均**空**）= W5 生产码 + W5-fix / fix2 / fix3 / fix4 / fix5 + W5b / W5c 两个测试批
- **jar**：`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar` 现构，**md5 `5a3d080a7c4c20a62b3eafa9cd71305b`**（27,132,420 字节，12:38:12；快照副本 `/tmp/w5acc/w5.jar` 同 md5）
- **判据来源**：设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md`（**v3.18**）§2 S-3、§4.1 溯源表「铸币入口」行、**§6.1 的 W5 行（判据 ①–⑤）**、§6.2 G1–G7、§7 的 v3.14–v3.18（含两条用户裁定逐字）；实现账本 `.superpowers/sdd/2026-10-11-w5-minting-into-market/impl-ledger.md`（W5 + 5 fix + 2 测试批）；本任务书 §1 的 10 项
- **时间**：2026-10-11 12:37 – 12:55（CST）
- **一句话结论**：**10 项判据本体全部成立**（§4 逐项 绿）—— 铸币确实有生产账（`scale 1 × perUnit 500 = minted 500`，工具与劳动前后值齐全）、旋钮 0 ⇒ 0 毫、旋钮 300 ⇒ 实铸 300 + 具名拒、旋钮 2000 而上轮只印 300 ⇒ 实铸 500（**不被夹**）、钱落国库且**无授权单时一分不出**（600 天国库 = 5×500 = 2,500，零流出）、并**真构造出"决策人授权单 ⇒ 铸币钱流入市场"的样本**（day 123 授权 BUY 粮：国库银 500→498、粮 0→20）；B2 三份 dump **state md5 全等**、Δ=1/3/360、性能同会话 A/B（W1/W4/W5 三 jar 交替）**+5.2%（红线内）**；写档→重启→续跑与"一次跑到底"**逐叶 0/87,033 差**。
  **但要如实标三件事**：① **判据 ②③ 不算"用 GM 工具验过"** —— 铸币旋钮在唯一会铸币的政府（世界级 `world-silver`）上**没有 GM 写口**（`economy.RegisterGovernment` 只写 `gov-unit-<id>` 形态的政府；以 `world-silver` 调用 HTTP 422、`gov.issueMoney` 也拒），②③ 的数字来自 `/tmp` 探针（真世界 + 真结算，按轮改**同一字段**）—— 见 **D1**；② **`MINT_PRODUCTION` 在两个发行读数端点上被当成"回笼"**（`withdrawal`，`netIssuance` 反而少 500），与 `MoneyIssuanceKind.MINT_PRODUCTION.issuance()==true` 矛盾 —— 见 **D2**（W5 引入，只影响两个只读端点）；③ 三条既存/口径类发现：**D3**（"工具耗尽即停产"的账本断言在真世界**不成立**：第 5 个周期无偿同格取材后照印，600 天 5 条记录）、**D4**（创世 200,000 毫银经裸原语注资、**无发行审计**，占创世银总量 74.9%）、**D5/D8**（自持铸币债恒 `DEFAULTED`；GM 窄工具 `actor.AdjustAccounts` 本身可无审计加钱）。

---

## 1. 装置

### 1.1 jar（从头现构；**未覆盖任何运行中服务的 jar**）

```bash
# 开工：pgrep -af java → 无 simos 服务；git status --porcelain → 空；HEAD = 24af7bf1
tools/mvn-lock.sh -DskipTests package -pl simos-app -am     # BUILD SUCCESS，16/16，3.125 s，rc=0（/tmp/w5acc-build.log）
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
cp simos-app/target/…-shaded.jar /tmp/w5acc/w5.jar          # 本次全部跑用它（run-shaded.sh 再复制成进程独占快照）
```

**jar 内容自证**（不只看 md5）：`unzip -l` 命中 `MintSettlement.class` ×2；`MintSettlement.class` 的 `strings` 里 `above-mint-scale-policy` ✓、`MINT_LAST_CYCLE_READOUT` ✓。
★ **构建可复现**：开工时 target 下已有一份 12:36 的 jar（md5 同为 `5a3d080a…`），本次现构**逐字节复现**同一份字节。

| | 值 |
|---|---|
| 被验 jar | `/tmp/w5acc/w5.jar`，md5 `5a3d080a7c4c20a62b3eafa9cd71305b`（= 仓内 target 下同一份） |
| 性能对照 jar | W1 基线 `/tmp/simos-shaded-EZy0IT.jar` md5 `a90e11a9ec533010a8b8b568efdad067`；W4 `/tmp/w4acc/w4.jar` md5 `8d3c37ba44e018960c714b30ab99e97e`（两者与 W4 验收报告 §1.1 逐字相同） |
| 构建日志 | `/tmp/w5acc-build.log`（`BUILD SUCCESS` / `Total time: 3.125 s` / `rc=0`） |

### 1.2 启动命令 / store / 端口 / 日志档

```bash
bash tools/run-shaded.sh <jar> --store <store> --world=<small-world|three-powers> \
  --config /tmp/w5acc/shell-<sw|tp>.json --gui-port P --mcp-port P+1 --approval-port P+2
# JAVA_TOOL_OPTIONS = -Dsimos.economy.logLevel=INFO|DEBUG|TRACE  [-Dsimos.app.logLevel=DEBUG]
# 推进：POST /api/advance  {"branch":"main","expectedRevision":R,"from":A,"to":B}
# GM ：POST /api/command  {"type":"…","payloadJson":"{…}","branch":"main","expectedRevision":R}
# 读口：GET /api/economy/gov、GET /api/economy/overview
# MCP：POST http://127.0.0.1:<mcp>/mcp（streamable HTTP；GM 面自动放行，见 §2.6）
```

| 配置 | 内容 | md5 |
|---|---|---|
| `/tmp/w5acc/shell-sw.json` | small-world / checkpointInterval=1 | `7c1a82f8507e27d74287e8d1f558158b`（与 W4 同） |
| `/tmp/w5acc/shell-tp.json` | three-powers / checkpointInterval=1 | `8d877ef31c88e149632692d8fe55f767`（与 W4 同） |

### 1.3 脚本（全在 `/tmp/w5acc`，**未落仓**）

| 脚本 / plan | 作用 | md5 |
|---|---|---|
| `runner.py` | 起服务→等就绪→按 plan 推进/发命令/落 dump/HTTP 只读探针→收进程（W4 同名脚本 + 改 cwd/输出目录） | `2385775c558cbb9721fae2b037be240f` |
| `canon2.py` | checkpoint→只含状态的规范串 + 逐叶深比对（**与 W3/W4 同一把尺**，字节未改） | `a565a5c506c29ed06c4e63d2ce5b3c4d`（= W4 报告 §1.3 所列） |
| `conserve_w5.py` | 守恒：Σ货币/发行类别逐值恒等式/人口/债务 | `83404770edd84208e352c6af3993dc49` |
| `inspect_mint.py` | 铸币读数：产业/unit/国库/发行表/旋钮 | `2937625c267f3b6b878dd2e8e4f652d5` |
| `issue_money.py` | 真服务 + **MCP `tools/call`** `simos.gov.issueMoney` + 两端点读数 + 落盘发行表 | `6e2b374152fa97a4d57e1179c600b7b2` |
| `issue_money_ws.py` | 同上，但 `govUnitId="world-silver"`（世界级政府）——取工具层拒因原文 | `9eea87d7fdd7198c8e62afc9a308d844` |
| `W5AccKnobProbe.java` | **真世界 SmallWorld + 真结算**，按轮改"政策旋钮"这一格（五格：300 / 2000 / 0 / 双币种×2） | `fc3743ff5fcdd784b5f7f5e581715126` |
| `run-probe.sh` | 探针 classpath 自证（仓内 `target/classes` 前置、`~/.m2/io/mosire/simos-*` 必须 0 命中）+ 编译运行 | `e9592920b170d50ee0f278849527bba4` |
| plan：`sw1 sw2 sw3 sw4 sw5 sw6 sw600 sw601 sw6cycle sw6cycle2 tp1 repA` | 各轮推进/读数配方 | 见 §1.4 表（逐个 md5 在 `/tmp/w5acc/*.json`） |

### 1.4 运行清单（**一次只有一个世界实例、一次只有一个 Maven**；端口 103xx/104xx）

| tag | 世界 | 推进 | 端口 | 日志档 | Δrev | 墙钟(s) | HTTP | dump md5（信封） |
|---|---|---|---|---|---|---|---|---|
| `sw1` | small-world | 0→120 单次 | 10301 | economy DEBUG | 1 | 3.372 | 200 | d120 `f20341b228bda1158b4eb75ff225cfa5` |
| `sw2` | small-world | 0→119→120→121→125→130（5 段） | 10311 | economy **TRACE** | 7 | 3.622+0.324+0.099+0.190+0.192 | 全 200 | d119 `4c8b39fc…` d120 `78ee4cd2…` d121 `1c06c62d…` d125 `7f81800a…` d130 `11420f7e…` |
| `sw3` | small-world | **0→360 单次** | 10321 | DEBUG | **1** | 8.611 | 200 | d360 `7f826409235badf15b7169f31a21ab1c` |
| `sw4` | small-world | **0→120→240→360（3 段）** | 10331 | DEBUG | **3** | 3.650+3.246+2.428 | 全 200 | d360 `447675ea652d53d0b65f8d4a387202f6` |
| `sw5` | small-world | **逐 tick 360 次**（`--prune`） | 10341 | INFO | **360** | Σ33.394（中位 0.089，max 1.065） | 全 200 | d360 `779baf9332c7082a1c2e2997fc449b3a` |
| `repA` | small-world | **重启在已写档上**：dump→get→360→361 | 10351 | DEBUG | 2 | 0.510（单步） | 200 | loaded-d360 `7f826409…`（= sw3 同 md5） d361 `3e8ef20c…` |
| `sw6` | small-world | 0→361 单次（重启对照） | 10361 | INFO | 1 | 8.096 | 200 | d361 `9a70935120e85e7c38e60b179cd3df7c` |
| `sw600` | small-world | **0→600 单次** | 10361 | INFO | 1 | 11.339 | 200 | d600 `639638c1d03f6c8331e1f55056343ff5` |
| `sw601` | small-world | 0→480→600 | 10371 | DEBUG | 2 | 11.084+2.345 | 200 | d480 `278ef2bc…` d600 `875b9d87…` |
| `sw6cycle` | small-world | 0→481→500→600 | 10381 | DEBUG | 3 | 11.862+0.470+1.843 | 200 | d481 `f0fee653…` d500 `8e7fcce3…` d600 `a03fc099…` |
| `sw6b` | small-world | 0→480→481（**同一进程内** 480/481 差） | 10391 | DEBUG | 2 | 10.498+0.191 | 200 | d480 `278ef2bc…`（与 sw601 同日同 md5） d481 `9a17fba1…` |
| `im1` | small-world | 0→120 + **MCP `gov.issueMoney`** | 10401/10402/10403 | DEBUG | 2 | 3.7 + 0.3 | 200 | 见 §2.6 |
| `im2` | small-world | 0→120 + **MCP `gov.issueMoney(govUnitId=world-silver)`**（取拒因） | 10411/10412/10413 | DEBUG | 1 | 3.7 | 200 / 工具 `isError` | `[mosire:code=BAD_REQUEST] GOV 单位不存在: world-silver` |
| `tp1` | **three-powers（无政府家户 ⇒ 无铸币产业）** | 0→240 单次 | 10301 | DEBUG | 1 | 29.262 | 200 | d240 `340ef516dfe676e1a9c4cbba0ce5b0f2` |
| `perfw5-1..3` | small-world | 0→361 单次 ×3 | 10411–13 | INFO | 1 | 7.496 / 8.262 / 8.406 | 200 | 三份 d361 **同 md5** `9a709351…` |
| `ab-{w1,w4,w5}-1..2` | small-world | 0→360 单次，**三 jar 交替** | 105xx | INFO | 1 | 见 §2.8 | 200 | w1 `c9320a66…` w4 `7f0704b2e87b231c939a2e44dd9a0af1`（与 W4 报告同） w5 `7f826409…` |

**读数四路**：① store 里 `<rev>.json` 全量信封；② `revisions` 表（Revision Δ 真数）；③ 进程 stdout 的具名事件（`economy.settlement` / `economy.market` / `economy.command`）；④ HTTP 读口 `/api/economy/gov`、`/api/economy/overview`。

**★ 装置自证**：`canon2.py` 字节与 W3/W4 两次验收所用完全相同；W4 的 jar（`8d3c37ba…`）在本装置下重跑 0→360 得到 day-360 dump md5 `7f0704b2e87b231c939a2e44dd9a0af1`，**与 W4 验收报告 §1.4 的 `b2one` 逐字相同** ⇒ 本装置的"尺"与 W4 可比。

### 1.5 探针自证（D1 的直接后果：旋钮无 GM 写口，只能探针改）

```
[probe] stale simos-* jars on classpath = 0 (must be 0)
[probe] jar/classes 自证: MintSettlement.class md5 = aec3a08faa2fa66d57d37458174c160d
[probe] MintSettlement.java md5 = 38d6b48f88482ac760c8c462fb6f99b7   （= W5-fix5 账本 §③ 记录的同一份字节）
[probe] probe java md5 = fc3743ff5fcdd784b5f7f5e581715126
```
探针在**仓根**下编译运行、classpath 用仓内 `target/classes`（不用 `~/.m2` 里的 simos-* 构件）；它只改内存里 `Government.mintScalePerCycle` 这一格（`Government.withMintScalePerCycle`，**全仓 main 作用域内没有任何调用者**，见 D1），其余全是真世界的播种与结算。

---

## 2. 逐项真数字

### 2.1 项 1 —— 铸币有生产账（判据 ①）：**绿**

**装置**：`sw2`（small-world，TRACE）+ `sw6cycle`/`sw6b`（DEBUG）。放款/口径来自创世档与逐日档。

**创世形状**（`sw2-genesis.json`）：

```
[铸币产业] mint@0_0 regime=government_mint cycleDays=120 capacity={WORKSHOP=1} laborPerUnit=8000
           cycleInputPerUnit={WORKSHOP={tool=2000}} moneyOutputPerUnit={silver=500} outputPerUnit={}
[铸币 unit] unit-mint@0_0-HOUSEHOLD-hh-gov-world-silver operator=HOUSEHOLD:hh-gov-world-silver
[政府] world-silver issuable=[silver] mintScalePerCycle=2000 treasury=HOUSEHOLD/hh-gov-world-silver
[国库账户] money={} balances={tool=8000}
SEED_MINT_LABOR household=hh-gov-world-silver hex=0,0 poolDailyLaborMilli=349160 sharePerMille=30
                requestedLaborMilli=10474 slackAvailableMilli=5018977 grantedLaborMilli=9440
                perUnitLaborMilli=8000 unitScalesSupported=1
```

**投入"前后值"（同一世界、同一进程，day 119 → day 120）**：

| 读数 | day 119（周期末前一日） | day 120（关账日） |
|---|---|---|
| unit `progressDays` | 119 | **0**（关账清零） |
| unit `cycleLaborMilli`（本周期累计劳动，千分劳动·日） | **1,117,767** | **0**（关账清零） |
| unit `cycleInputUsedMilli` | `{tool=2000}` | `{}`（关账清零） |
| 国库 `hh-gov-world-silver` 货物 | `{tool=6000}` | `{tool=6000}` |
| 国库货币 | `{}` | **`{silver=500}`** |

**关账处的 scale 求值**（原文，`sw2.log`）：

```
event=PRODUCTION_EFFICIENCY_HARVEST day=120 unit=unit-mint@0_0-HOUSEHOLD-hh-gov-world-silver
  laborScale=1 satisfaction=1000 scaleBase=1 avgModifier=1000 scale=1 modifierRemainderMilli=0
  laborDayRemainderMilli=30 laborScaleRemainderMilli=1392 scaleRemainderMilli=0
  modifierEffective=true laborPerUnitZero=false
```

**逐值对账**（`MINT_CREDIT` TRACE 原文）：

```
event=MINT_CREDIT day=120 period=4 government=world-silver currency=silver minted=500 requested=500
  limit=2000 scale=1 perUnit=500 treasuryBefore=0 treasuryAfter=500
  issuance=mi-mint-world-silver-120-silver debtor=hh-gov-world-silver
event=MINT_SETTLED day=120 period=4 closedUnits=1 currencies=1 mintedTotal=500 byCurrency={silver=500} kind=MINT_PRODUCTION
```

⇒ **`scale(1) × perUnit(500) = requested(500) = minted(min(500, limit 2000)) = 500`**，逐值相等。
⇒ **投入真实消耗**：工具 8,000 → 6,000（本周期 `cycleInputUsedMilli={tool=2000}` = `scale 1 × 2000`）、劳动本周期实际 1,117,767 千分劳动·日（≥ `laborPerUnit 8000` ⇒ `laborScale=1`；日配额 9,440，实测首日 9,250/日）。
⇒ **产出 = 货币**：`{}` → `{silver=500}`，货币总量 267,200 → **267,700**（+500，别的一毫未动）。
★ **限制（见 D3）**：这笔 2,000 工具**不是国库自己出的**——国库工具库存从头到尾由创世 8,000 供给，第 5 个周期（day 481）落在 **0**，而铸币照产（§2.2/§3-D3）。

### 2.2 项 2 —— 负向：旋钮 = 0 ⇒ 新钱 0 毫（判据 ②）：**绿（探针取数；真世界旋钮不可写 ⇒ D1）**

真世界的铸币政府 `world-silver` 的旋钮**改不动**（§3-D1，HTTP 422），故这一格用探针（真世界 SmallWorld + 真结算路径，只按轮改旋钮字段）：

| 格 | 旋钮 | 货币总量（国库/全局） | 新钱 | 具名日志原文 | 记录 | 工具 |
|---|---|---|---|---|---|---|
| **格③ R3**（day 240→360） | **0** | 800 → **800** | **0** | `DEBUG event=MINT_POLICY_ZERO day=360 period=12 government=world-silver unit=unit-mint@0_0-… industry=mint@0_0 reason=mint-scale-zero minted=0` | 无新记录（仍 2 条） | 4,000 → **2,000**（投入照样消耗） |
| **格① R1**（day 0→120） | 300 | 0 → 300 | +300 | 见 §2.3 | `mi-mint-world-silver-120-silver` | 8,000 → 6,000 |
| **格② R2**（day 120→240） | 2,000 | 300 → 800 | +500 | 见 §2.3 | `mi-mint-world-silver-240-silver` | 6,000 → 4,000 |

`PROBE R3 moneyDelta=0`（探针原文）。**另有一个真服务侧的零新钱读数**：`tp1`（three-powers，无政府家户 ⇒ 无铸币产业）0→240 货币总量 `{copper 136,600, gold 129,400, silver 424,600}` **逐值不变**、`MINT_*` 日志 **0 行**（`grep -c MINT results/tp1.log` = **0**）。

★ 口径注：`MINT_POLICY_ZERO` 是 **DEBUG** 级（设计如此："关掉的政策"不是一次拒绝，避免每关账日刷屏）⇒ 默认 INFO 档下判据②**没有 INFO 行**，要 `-Dsimos.economy.logLevel=DEBUG` 才看得到。

### 2.3 项 3 —— 上限 = 政策旋钮（判据 ③，**v3.18 新口径**）：**绿（探针取数；真世界旋钮不可写 ⇒ D1）**

**③a 旋钮 300 < 请求 500 ⇒ 实铸 300 + `MINT_LIMIT_REJECTED`**（`knob-probe.log` 原文）：

```
INFO  event=MINT_LIMIT_REJECTED day=120 period=4 government=world-silver currency=silver
      unit=unit-mint@0_0-HOUSEHOLD-hh-gov-world-silver industry=mint@0_0 scale=1 perUnit=500
      requested=500 limit=300 previousCycleMinted=-1 mintScalePerCycle=300 rejected=200
      reason=above-mint-scale-policy
INFO  event=MINT_SETTLED day=120 period=4 closedUnits=1 currencies=1 mintedTotal=300 byCurrency={silver=300} kind=MINT_PRODUCTION
PROBE R1 MINT_RECORD id=mi-mint-world-silver-120-silver … amount=300 kind=MINT_PRODUCTION
PROBE R1 TREASURY household=hh-gov-world-silver money={silver=300} goods={tool=6000}
PROBE R1 moneyDelta=300
```
⇒ `rejected = requested − limit = 500 − 300 = 200` ✓；`limit=300` = 旋钮 ✓；`reason=above-mint-scale-policy` ✓（`previousCycleMinted=-1` = 首轮读数缺席，按 v3.18 不再是特判）。

**③b 旋钮 2,000 而上轮只印 300 ⇒ 本轮能印到 500（不被夹）**：

```
INFO  event=MINT_LAST_CYCLE_READOUT day=240 period=8 government=world-silver previousRoundDay=120
      previousCycleMinted=300 before={} after={silver=300} currencies=1
INFO  event=MINT_SETTLED day=240 period=8 mintedTotal=500 byCurrency={silver=500}
PROBE R2 MINT_RECORD id=mi-mint-world-silver-240-silver … amount=500
PROBE R2 TREASURY … money={silver=800} goods={tool=4000}
PROBE R2 moneyDelta=500
PROBE final GUIVIEW gov=world-silver knob=300 mintedLastCyclePerCycle={silver=500, copper=500}   （双币种格之后的终值）
```
⇒ **实铸 500 > 上一轮 300 ⇒ 不被夹** ✓（若按旧口径 `min(旋钮, 上一轮)` 这里只能是 300）。

**"上一轮印量"读数是真值**：
- 探针：`MINT_LAST_CYCLE_READOUT … previousCycleMinted=300 before={} after={silver=300}`（写入 `Government.mintedLastCyclePerCycle` 的值 = 300，与上一轮实铸逐值一致）。
- **真服务侧（无探针）**：`sw3`（纯 HTTP，0→360 单次）day 240 原文
  `DEBUG event=MINT_LIMIT_WINDOW day=240 period=8 government=world-silver previousRoundDay=120 previousCycleMinted=500 mintScalePerCycle=2000 limit=2000 limitSource=mint-scale-policy mintedLastCycleReadout={silver=500} mintedLastCycleReadoutChanged=true reason=previous-mint-round-day-window-readout-only`
  + `INFO event=MINT_LAST_CYCLE_READOUT day=240 … previousCycleMinted=500 before={} after={silver=500}`；
  读口逐日：d240 `mintedLastCyclePerCycle={silver=500}`、d360 `{silver=500}`、d600 `{silver=500}`（`sw4`/`sw3`/`sw600` 档）。
  ⇒ 读数**不是恒 0**，也不是"旋钮值"；它 = 最近一个严格早于本轮的铸币日逐币种合计（**跨币种求和**进日志，逐币种表进状态）。

### 2.4 项 4 —— 入市只能由决策人触发（判据 ④，用户裁定 v3.15）：**绿（运行期口径）+ 一处创世例外（D4）**

**① 铸币产出落国库家户账户**：`sw3` 逐档 —— 国库 `hh-gov-world-silver` 货币 `{}`→d120 `{silver=500}`→d240 `{silver=1000}`→d360 `{silver=1500}`；`sw600` d600 `{silver=2500}`；货物 `tool 8000→6000→4000→2000→0`。`MINT_CREDIT` 带 `treasuryBefore/treasuryAfter`（0→500）。**没有任何一笔钱进别的主体**（同一 minted 只出现在国库）。

**② 无任何自动投放（无授权单时钱躺在国库）**：

| 读数 | 值 | 出处 |
|---|---|---|
| 国库货币（0→600 天，**无任何授权单**） | 0 → 500 → 1000 → 1500 → 2000 → **2500** = 5 × 500 | `sw4-d120/d240/d360`、`sw601-d480`、`sw600-d600` |
| 国库市场收入 | `flows.income = {}` —— **24 份非创世档（d119…d600、含重启前后）全部为空表**；`interestDue` 0→10→22→34→47（自持铸币债的利息，见 D5） | `inspect_mint.py` 读 `flows['hh-gov-world-silver']` |
| 自动订单被抑制（审计行） | `GOV_MARKET_AUTO_ORDERS_SUPPRESSED … household=hh-gov-world-silver commodity=tool hex=0_0 authorizationOnly=true autoBuyOrders=0 autoSellOrders=0 mandateBuyOrders=0 mandateSellOrders=0 wouldBeBuyMilli=0 wouldBeSellMilli=4000`（每开市日每商品一条；`sw2` **780** 行、`sw3` **2,160** 行） | `results/sw2.log:2098`、`sw3.log` |
| "如果想自动卖、它会卖多少" | `wouldBeSellMilli=4000`（工具）——**被 `authorizationOnly=true` 挡住** | 同上 |
| 有授权单时才有动作 | `sw2` d120→d121 国库 500→500（无单，不变）；授权后（day 123 开市）500→**498**、粮 0→**20** | `sw2-d120/d121/d125` |

**③ 除 `simos.gov.issueMoney` 外没有第二条凭空加钱路径（grep 证据 + 运行读数）**：

grep（本仓 main 作用域，现跑）：

```
A) economy 侧货币余额写点：
   MintSettlement.java:533  treasury.replaceMoney(money)        ← 铸币产出（本批新增，带生产账+审计+债务）
   AccountSession.java:208/466                                    ← 账户工作副本 setter 自身
   EconomySettlement.java:9629/10735/10758 setMoney/addMoney      ← applyTransfer 唯一写口（收 +x 必伴付 −x）
B) MINT_PRODUCTION 的唯一生产者：MintSettlement.java:356/387/534/544（其余命中只在枚举定义与注释）
C) 旧财政旁路：`seignioragePerCycle|debtIssuePerCycle|GovernmentSeigniorage` 在 main 里 **9 处命中，逐条都是注释/javadoc**
   （MintSettlement 类注 3、EconomySettlement:1170 注释、Government 类注 3、RegisterGovernment:320、EconomyPayloads:2171）；
   `find . -name 'GovernmentSeigniorage*' -o -name 'GovernmentDebtIssuance*'` = **0 命中**（整类已删）
D) actor 侧裸原语：`actor.AdjustAccounts` → applyMoneyDelta（纯增量）——本文具名的 `simos.gov.issueMoney` 余额腿；
   其余调用者（Levy/Recruit/Dismiss/Retire/RaiseUnit…）**本批未逐条审**（属既有路径，不在 W5 改动面）；
   ★ **但创世那条 `GovWorldBootstrap` 注资经同一原语凭空加钱且无审计 —— 见 D4**（它是本判据的具名例外）
E) ★ **同一个裸原语还被单独暴露成 GM 工具**：`actor.AdjustAccounts`
   （`ActorAdjustAccountsTool.java:35`，注册在 `SimosToolSource.java:663` 的 GM 写集；描述原文："调整 actor 账目：固定 actor.AdjustAccounts … money:{币种:有符号净增量}"）
   ⇒ **GM 手里有一条"给任意家户凭空加钱、不留 `MoneyIssuanceRecord`"的窄工具**。这是**既存**路径（用户裁定 C-4 保留余额腿），
   但它使判据 ④③ 的字面读法"除 `simos.gov.issueMoney` 外没有第二条凭空加钱路径"在**工具面**上不成立（详见 D8）。
```

**运行读数（逐窗口恒等式，全部精确）**：
- `sw600` 0→600：Σ货币 367,200 → **369,700**，Δ = **+2,500** = Σ`MINT_PRODUCTION`（5×500）；`withdrawal` 0 条；本金 0。
- `sw3` 0→360：Δ = +1,500 = 3×500。
- `tp1` 0→240：Δ = **0**（无铸币产业）。
- `im1`（真 MCP 发行）：+500（铸币）+**777**（`FISCAL_ISSUE`）= 368,477，逐值对得上（§2.6）。
- 国库余额变化的**唯一**两个来源在样本里都被点名：铸币产出与 `gov.issueMoney`；市场侧只有**花出去**（§④★ 的授权单样本：−2 银），且 **Σ货币不变**（267,700 → 267,700）。

**★ 构造出的"决策人下单 ⇒ 钱流入市场"真样本（用户裁定 v3.15 的正面证据）**：`sw2` 在 day 121 用 GM 命令授权国库买粮（命令原文与回执见 §3-D1 表同款通道）：

```
POST /api/command {"type":"economy.AuthorizeGovernmentMarketOrder","payloadJson":
  "{\"id\":\"w5acc-mandate-1\",\"government\":\"world-silver\",\"commodity\":\"grain\",\"side\":\"BUY\",
    \"quantityMilli\":20,\"limitPriceMilli\":60,\"expiresOnDay\":200,\"source\":\"w5acc-acceptance\"}",
  "branch":"main","expectedRevision":5}                                   → HTTP 200 {"result":"committed","ref":{"revision":6}}

TRACE event=GOV_MARKET_MANDATE_BUY_ORDER day=123 household=hh-gov-world-silver mandate=w5acc-mandate-1
      government=world-silver commodity=grain quantityMilli=20 limitPriceMilli=55 mandateLimitPriceMilli=60
      marketAskMilli=55 remainingMilli=20 spendableMilli=500 hex=0_0
TRACE event=GOV_MARKET_MANDATE_FILLED day=123 mandate=w5acc-mandate-1 government=world-silver side=BUY
      commodity=grain filledDelta=20 filledMilli=20 quantityMilli=20 remainingMilli=0
INFO  event=GOV_MARKET_MANDATE_CLEARED day=123 … quantityMilli=20 filledMilli=20 expiresOnDay=200 reason=exhausted
```

逐笔读数（`sw2-d121` → `sw2-d125`）：国库 `silver 500 → 498`（**付出 2 毫银**）、`grain 0 → 20`（收到 20 毫粮）；同窗口 Σ货币 **267,700 → 267,700 不变**（是转移不是创造）。⇒ **铸币出来的钱经"决策人授权 + 市场撮合"流入市场**，系统自身一笔都没投放。★ 如实记：撮合是**按池配对**的，日志不点名单一对手方（`MARKET_PAIRING_*` 无买卖双方明细），故"流到谁手里"只能给到"国库付出、某卖方收到、总量不变"这一层。

### 2.5 项 5 —— 每条发行有 `MoneyIssuanceRecord`（判据 ⑤）：**绿**

`sw600-d600.json` 的 `moneyIssuances` 全表（逐条原文）：

```
endowment-small-world--2_0-silver     gov=world-silver           day=0   period=1  cur=silver amount=67200   kind=INITIAL_ENDOWMENT
gov-issue-gov-unit-gov-central-0-copper-1 gov=gov-unit-gov-central day=0  period=1  cur=copper amount=100000  kind=INITIAL_ENDOWMENT
mi-mint-world-silver-120-silver       gov=world-silver           day=120 period=4  cur=silver amount=500     kind=MINT_PRODUCTION
mi-mint-world-silver-240-silver       gov=world-silver           day=240 period=8  cur=silver amount=500     kind=MINT_PRODUCTION
mi-mint-world-silver-360-silver       gov=world-silver           day=360 period=12 cur=silver amount=500     kind=MINT_PRODUCTION
mi-mint-world-silver-480-silver       gov=world-silver           day=480 period=16 cur=silver amount=500     kind=MINT_PRODUCTION
mi-mint-world-silver-600-silver       gov=world-silver           day=600 period=20 cur=silver amount=500     kind=MINT_PRODUCTION
```
- **id 形状**：`mi-mint-<gov>-<day>-<cur>` ✓（`MoneyIssuanceId.forMintProduction`，`simos-economy-api/.../MoneyIssuanceId.java:99`；`governmentId` 里的 `|`→`:`）。
- **逐值对账**：每条 `amount` == 该轮 `MINT_SETTLED.byCurrency` == 该轮 `MINT_CREDIT.minted` == `scale × perUnit`。
- 另一面（货币 = 政府债务）：每轮一条 `DebtContract`（见 D5）。

### 2.6 项 6 —— `gov.issueMoney` 例外仍可用：**绿（真 MCP 调用）**

装置 `im1`：small-world 0→120（跑出一次铸币）后，用 **MCP streamable HTTP** 真调 `simos.gov.issueMoney`（GM 面 `GmAutoApproveGate` 无脑过）：

```
POST /mcp  initialize              → HTTP 200（server=simos-shell，protocol=2024-11-05，session-id 回传）
POST /mcp  notifications/initialized → HTTP 202
POST /mcp  tools/call {"name":"simos.gov.issueMoney","arguments":
   {"govUnitId":"gov-central","currency":"copper","amountMilli":777,"preview":false,
    "expectedRevision":2,"reason":"w5acc-acceptance"}}
→ HTTP 200 result.content[0].text =
  {"preview":false,"submitted":true,"govUnitId":"gov-central","govDerivedFromIdentity":false,
   "treasuryHousehold":"hh-gov-gov-central","currency":"copper","amountMilli":777,"kind":"FISCAL_ISSUE",
   "reason":"w5acc-acceptance","issuanceRecordsBefore":3,"at":{"q":0,"r":0},
   "commandsPreview":[{"type":"actor.AdjustAccounts","payloadJson":"{\"entries\":[{\"household\":\"hh-gov-gov-central\",\"money\":{\"copper\":777}}]}"},
                      {"type":"economy.RecordMoneyIssuance","payloadJson":"{\"govUnitId\":\"gov-central\",\"currency\":\"copper\",\"amountMilli\":777,\"kind\":\"FISCAL_ISSUE\",\"reason\":\"w5acc-acceptance\"}"}],
   "submission":{"result":"committed","ref":{"branch":"main","revision":3},"commandId":"aaf595ea-…"}}
```

| 读数 | 前 | 后 |
|---|---|---|
| 国库 `hh-gov-gov-central` 铜 | 100,000 | **100,777**（+777 逐值） |
| `/api/economy/gov` 该政府 `issuance` | `recordCount=1, initialEndowment={copper:100000}, fiscalIssue={}, netIssuance={copper:100000}` | `recordCount=2, fiscalIssue={copper:777}, netIssuance={copper:100777}` |
| 新 `MoneyIssuanceRecord` | — | id `gov-issue-gov-unit-gov-central-120-copper-1`，**kind `FISCAL_ISSUE`**，day 120，amount 777 |
| Revision | 2 | **3**（一批两条命令 = 一条 revision，原子） |

★ **真跑过的反面**：同一工具以 `govUnitId="world-silver"`（世界级铸币政府）调用 ⇒ 工具层直接拒（原文）：
```
tools/call {"name":"simos.gov.issueMoney","arguments":{"govUnitId":"world-silver","currency":"silver","amountMilli":111,…}}
→ result.isError=true, text = "[mosire:code=BAD_REQUEST]\nGOV 单位不存在: world-silver"
```
⇒ **世界级政府既不能用 `economy.RegisterGovernment` 改旋钮（D1 的 422），也不能用 `gov.issueMoney` 发行** —— 两条具名口都到不了它。

### 2.7 项 7 —— 铁律 5 往返：真档写 → 读 → 续跑：**绿**

**装置**：`sw3`（进程 A）0→360 写档 → 复制 store → `repA`（**新进程 B**）在同一 store 上启动 → 读 → 续跑；对照 `sw6`（一次 0→361 跑到底）。

| 检查 | 结果 |
|---|---|
| B 进程能否起（Replay 解码） | 能：GUI 1.46 s 就绪、`创世后 head rev=2 tick=360`、`/api/economy/gov` 200 |
| 写档（A）vs 读档（B）**落盘字节** | `sw3-d360.json` 与 `repA-loaded-d360.json` **md5 同为 `7f826409235badf15b7169f31a21ab1c`**；状态规范串 `canon2.py` md5 同为 **`24c969e77ddf1270a552e54885ce5afe`** |
| 写档（A）vs 读档（B）**读口逐字段** | `/api/economy/gov` 三政府的 `mintScalePerCycle / mintedLastCyclePerCycle / issuable / treasury / treasuryAccounts / issuance` **逐字段相同**（脚本判定 `写档前(sw3 进程) 与 重启后(repA 新进程) 读口逐字段相同: True`）；world-silver：`knob=2000, mintedLastCyclePerCycle={silver=500}, treasury money={silver=1500} goods={tool=2000}` |
| **续跑** | B 推进 360→361 HTTP 200，`Δrev=1`（rev 2→3），新档 `repA-d361.json` |
| 续跑 vs 一次跑到底 | `repA-d361`（重启后）与 `sw6-d361`（0→361 一次）**状态 md5 同为 `f60ca6c0be2e657c4126741554add14b`，逐叶差异 0 / 87,033**（`canon2.py diff`） |
| 确定性 | `perfw5-1..3` 三次 0→361 的 d361 信封 md5 三次全等 `9a709351…` |

⇒ 不是"旧档能读"，是**新代码写出去的档被新代码逐字段读回、并接着跑出同一世界**。

### 2.8 项 8 —— B2 分段等价 + Revision Δ + 性能：**绿**

**B2（G3）**：三份 dump 的状态规范串（`canon2.py`，剔各模块 ref/timestamp）

| 推进方式 | tag | dump | 状态 md5 | 逐模块 md5 |
|---|---|---|---|---|
| `0→360` 一次 | `sw3` | `sw3-d360.json` | **`24c969e77ddf1270a552e54885ce5afe`** | economy `06e709f9…` social `e123e923…` actor `7bcf3e4f…` … |
| `0→120→240→360` 三段 | `sw4` | `sw4-d360.json` | **同一 md5** | 全等 |
| 逐 tick ×360 | `sw5` | `sw5-d360.json` | **同一 md5** | 全等 |

三份规范串均 4,807,421 字节；信封 md5 不同（`7f826409…` / `447675ea…` / `779baf93…`）只因为 `ref.revision` 不同（1 / 3 / 360）。

**Revision Δ（G4）**：一次推进**一次落盘** —— `sw3` Δ=**1**（rev 1→2）、`sw4` Δ=**3**、`sw5` Δ=**360**；`sw600` 0→600 Δ=**1**、`sw601`/`sw6cycle`/`sw6b`/`repA` 每段 Δ=**1**。

**性能（G4，同会话三 jar 交替 A/B，small-world 0→360 单次，INFO）**：

| jar | run1 | run2 | 中位 | vs W4 |
|---|---|---|---|---|
| W1 基线 `a90e11a9…` | 7.823 s | 7.862 s | **7.843 s** | −0.2% |
| W4 `8d3c37ba…` | 7.880 s | 7.830 s | **7.855 s** | 基准 |
| **W5 `5a3d080a…`** | 8.117 s | 8.415 s | **8.266 s** | **+5.2%**（红线 ≤10% ⇒ 过） |

（另测：0→361 ×3 = 7.496 / 8.262 / 8.406 s，三份 dump 同 md5；0→600 单次 11.339 s；three-powers 0→240 29.262 s。）

### 2.9 项 9 —— 守恒（货币 / 人口 / 债务）：**绿（货币恒等式带一处具名创世偏移，见 D4）**

`conserve_w5.py` 逐档读数：

| 档 | Σ货币（actor 账本） | 发行记录逐类别 | 人口 economy.classes / social.groups | 债务（未偿合同 / 未偿本金 Σ） |
|---|---|---|---|---|
| `sw3-genesis` | `{silver 267200, copper 100000}` Σ=367,200 | INITIAL_ENDOWMENT `{silver 67200, copper 100000}` | 4,800 / **4,800** | 0 / 0 |
| `sw3-d360` | `{silver 268700, copper 100000}` Σ=368,700 | +MINT `{silver 1500}` | 4,339 / **4,339** | 258 / 145,385,018（grain 38,768,699 / fiber 91,793,963 / money 14,822,356） |
| `sw600-d600` | `{silver 269700, copper 100000}` Σ=369,700 | +MINT `{silver 2500}` | 3,724 / **3,724** | 250 / 171,749,703 |
| `tp1-genesis` | `{copper 136600, gold 129400, silver 424600}` Σ=690,600 | INITIAL_ENDOWMENT `{copper 136600, gold 129400, silver 224600}` | 6,350 / **6,350** | 0 / 0 |
| `tp1-d240` | **逐值不变** Σ=690,600 | 同创世（无新发行） | 5,930 / **5,930** | 483 / 295,700,192 |

- **窗口恒等式（精确）**：ΔΣ货币 == ΣMINT_PRODUCTION + ΣFISCAL_ISSUE − ΣWITHDRAWAL，逐窗口 0 差（+500/轮、+777 那笔、three-powers 0）。
- **全量恒等式**：`Σ货币 == ΣINITIAL_ENDOWMENT + ΣMINT + ΣFISCAL − ΣWITHDRAWAL` **不成立**，差额恒为 **silver +200,000**（small-world 与 three-powers 都是这个常数）—— 见 **D4**（创世国库注资未入审计表）。
- **人口**：`economy.classes` 与 `social.groups` **逐档完全相等**（4800/4339/3724/6350/5930）。
- **债务**：给出未偿合同数与逐介质本金（含 W5 新增的自持铸币债，见 D5）；`status=SETTLED` 与未偿分开计数。

### 2.10 项 10 —— 多币种：**绿（现状读数 + 已知粗口径，只记不改）**

- **世界真有多币种**：small-world = `{silver, copper}`（`silver` 由 `world-silver` 发行、`copper` 由 `gov-unit-gov-central` 发行；`/api/economy/overview.moneyIssuance.circulation = {silver 267,700, copper 100,000}`）；three-powers = `{silver, copper, gold}`（3 个 GOV 单位政府，旋钮全 0）。
- **铸币产出只有一种币**：`mint@0_0.moneyOutputPerUnit = {silver=500}` ⇒ 真世界的铸币窗口**只有单币种样本**（`previousCycleMinted=500`、`mintedLastCyclePerCycle={silver=500}`）。
- **"跨币种求和、limit 逐币种共用"的现状（探针构造双币种产出 `{silver:500, copper:500}`，其余同真世界）**：

| 格 | 旋钮 | `MINT_SETTLED` | 记录 | 结论 |
|---|---|---|---|---|
| R4（day 360→480） | 2,000 | `currencies=2 mintedTotal=1000 byCurrency={silver=500, copper=500}` | `mi-mint-world-silver-480-silver` + `…-480-copper` | 每币各自吃满请求（500+500），**闸门没有跨币种汇总** |
| R5（day 480→600） | **300** | `currencies=2 mintedTotal=600 byCurrency={silver=300, copper=300}` + **两条** `MINT_LIMIT_REJECTED`（各 `limit=300 rejected=200`） | `…-600-silver` + `…-600-copper` | **合计 600 > 旋钮 300** ⇒ `limit` 是**逐币种各自套用**，不是跨币种总额 |
| R5 读数字段 | — | `MINT_LAST_CYCLE_READOUT … previousCycleMinted=1000 before={silver=500} after={silver=500, copper=500}` | — | 日志字段 = **跨币种求和**（1000）；状态字段 = **逐币种表**（`{silver=500, copper=500}`）——两份形态同源但量纲不同 |

★ 这是**已知粗口径**（`Government.mintScalePerCycle` 本身不按币种细分；`MintSettlement.java:154 long limit = government.mintScalePerCycle();` 在**逐币种循环内**取值）——**只记不改**。真世界里因为铸币产业只产一种币，这个粗口径**暂不可观测**。

---

## 3. 缺陷 / 异常清单（**只报不修**，逐条最小复现）

### D1 ★【缺陷·可用性】铸币政策旋钮在**唯一会铸币的政府**上没有 GM 写口

- **现场**：`Government.mintScalePerCycle` 的运行期写点全仓只有一处 —— `EconomyRegisterGovernmentHandler:323/358`，而它只写 **`gov-unit-<govUnitId>`** 形态的政府（`GovernmentIds.ofUnit`）。small-world 的铸币政府是**世界级** `world-silver`（`EconomySeeder.GENESIS_GOVERNMENT_ID`，`simos-app/.../EconomySeeder.java:1027`），不是单位政府。`Government.withMintScalePerCycle`（`Government.java:210`）在全仓 main 作用域**没有任何调用者**。
- **最小复现**（真服务，`sw2`，day 120）：
  ```
  POST /api/command {"type":"economy.RegisterGovernment","payloadJson":
    "{\"govUnitId\":\"world-silver\",\"nationRef\":\"world\",\"q\":0,\"r\":0,\"mintScalePerCycle\":300}",
    "branch":"main","expectedRevision":3}
  → HTTP 422 {"result":"rejected","reason":"政府家户 hh-gov-world-silver 已经是政府 world-silver 的国库；同一家户不能同时是两届政府的国库"}
  ```
  同一轮对**单位政府**写旋钮**成功**（`govUnitId=gov-central` → HTTP 200 committed，rev 4；但该政府没有铸币 unit ⇒ 对铸币零影响：day 240 的 `MINT_LIMIT_WINDOW` 里 `gov-unit-gov-central mintScalePerCycle=300`、`world-silver mintScalePerCycle=2000` 照旧）。
- **期望 vs 实际**：期望"决策人预期即权威"（v3.18）= 旋钮可调；实际**唯一会铸币的政府的旋钮不可调**（`simos.command.submit` 走同一 handler，同一条守卫 ⇒ 无第二条路）。
- **后果**：判据 ②③ 的"旋钮 ⇒ 实铸"在真世界里**无法用 GM 工具驱动**；本报告的 ②③ 数字来自 `/tmp/w5acc/W5AccKnobProbe.java`（真世界 + 真结算，按轮改**同一个字段**，见 §1.5）。**这不算"通过 GM 工具验过"**。
- **建议（不在本批）**：给世界级政府一条具名旋钮写口（例如 `economy.RegisterGovernment` 接受 `governmentId` 非 `gov-unit-` 形态时的 upsert，或新增 `economy.SetMintPolicy`）；并给 `simos.economy.upsertIndustry` 之类的政策工具补旋钮字段。

### D2 ★【缺陷·读数·W5 引入】`MINT_PRODUCTION` 在发行读数里被当成"回笼"（withdrawal）

- **现场**：`ApiViews.governmentIssuanceView`（`simos-app/.../gui/ApiViews.java:1772-1790`）与 `ApiViews.moneyIssuanceView`（`:4266-4290`）按 kind 分类：`INITIAL_ENDOWMENT`→initial、`FISCAL_ISSUE`→fiscal、**其余一律→withdrawal 并从 netIssuance 里减去**。W5 新增的 `MINT_PRODUCTION` 因此落进"回笼"。而枚举自己写着 `MoneyIssuanceKind.MINT_PRODUCTION.issuance() == true`（`MoneyIssuanceKind.java:25-27`，"该类别是否让流通量增加"）。
- **最小复现**（真服务 `im1`，0→120 跑出一次铸币 500）：
  ```
  GET /api/economy/gov → world-silver.issuance =
    {"recordCount":2,"initialEndowment":{"silver":67200},"fiscalIssue":{},
     "withdrawal":{"silver":500},"netIssuance":{"silver":66700}}
  GET /api/economy/overview → moneyIssuance =
    {"circulation":{"copper":100000,"silver":267700},"cumulativeIssuance":{"copper":100000,"silver":67200},
     "cumulativeWithdrawal":{"silver":500},"netIssuance":{"copper":100000,"silver":66700},"recordCount":3}
  ```
- **期望 vs 实际**：铸币 500 毫让**流通量 +500**（`circulation` 267,200→267,700 是实测），但读数把它记成**回笼 500**，于是 `netIssuance` 反而**少了 500**（66,700 而不是 67,700），`cumulativeIssuance` 也不含它。⇒ 同一份状态上有两个互相矛盾的读数（`circulation` ↑ vs `netIssuance` ↓）。
- **影响面**：**只影响两个只读端点**（`/api/economy/gov`、`/api/economy/overview`），不写状态、不改公式；`MoneyStock`（按 `issuance()` 方向求和）不受影响（与 W5 账本 §⑤ 的注一致）。但它是"看起来在记、其实记反了"那一族，W6 应修（两处 `else` 分支改成按 `record.kind().issuance()` 分类）。

### D3 ★【账本断言与真世界不符 / 既存机制被 W5 变成承重墙】"工具耗尽后第 5 轮停产"在真世界**不成立**

- **账本原文**（`impl-ledger.md` v3.17 节）："600 tick ⇒ **只有 4 条**（工具耗尽后第 5 轮停产）⇒ **投入约束真实**"。
- **真世界实测**（`sw600`、`sw601`、`sw6cycle` 三次独立复现）：0→600 **5 条** `MINT_PRODUCTION`（day 120/240/360/480/600 各 500），货币总量 367,200 → **369,700**；国库工具库存 d480 起就是 **0**（`balances={}`），day 600 的关账求值却是 `satisfaction=1000 scaleBase=1 scale=1`。
- **根因（有判别实验）**：既有的 **H6-lite「同格取材」**（`EconomySettlement.drawCycleInputs` → `recordInputDraw`，`TransferReason.INPUT_REQUISITION`）。同一进程内 480→481 的逐户差：
  ```
  Σtool 497,244 → 477,644（−19,600）
  仅两户变动：hh-0_0-urban-landlord      tool 10,360 → 0      money {silver 771} → {silver 771}
             hh-0_0-urban-middle_peasant tool 15,366 → 6,126  money {} → {}
  ```
  这两户的**货币一分未动** ⇒ 工具是**无偿划走**的（`ledger.mint(供方, operator, hex, Map.of(commodity,drawn), INPUT_REQUISITION)` 只带货物腿）。其中 2,000 进铸币 unit 的 `cycleInputUsedMilli`（`sw6cycle-d481`：`cycleInputUsedMilli={tool=2000}`，而国库 `balances={}`）⇒ 关账 `satisfaction=1000` ⇒ 照印。
- **期望 vs 实际**：期望"投入约束真实（国库没料就停产）"；实际只要**首都格里还有别人有工具**，铸币就永不缺料、也不付钱。⇒ 判据 ① 只能说"**货物真被消耗**"（Σtool 真减少、产出对账精确），**不能说**"这笔投入由国库承担/由铸币的收益支付"。
- **在哪一天/哪一户/哪个量**：day 481（第 5 周期播种日）、`hh-0_0-urban-landlord` 与 `hh-0_0-urban-middle_peasant`、工具 19,600 毫（其中铸币 2,000）。
- **性质**：H6-lite 是**既存**机制（`EconomySettlement` 类注自述"它会跨主体抢料，实测把城里作坊的纤维取走"），W5 **没有引入**它；但 W5 把铸币建成依赖它的生产 unit ⇒ 这条既存口径第一次变成"钱的来源"的承重墙。**账本那句断言必须改**（不改会让人以为已验过投入约束）。

### D4 ★【既存·创世加钱口未入审计】创世 200,000 毫银凭空进入两国库，无 `MoneyIssuanceRecord`

- **现场**：`GovWorldBootstrap`（small-world 创世，`simos-app/.../tools/write/GovWorldBootstrap.java:99-108/776-803`）用 `actor.AdjustAccounts`（裸账原语、纯正增量）给两个 GOV 国库各注 `TREASURY_SILVER_MILLI_PER_GOV = 100_000` 毫银 = **200,000**；**只有那笔铜**（`TREASURY_COPPER_MILLI_PER_GOV`）由同批 `economy.RecordMoneyIssuance` 补 `INITIAL_ENDOWMENT` 审计。类注原文："一次给两国库注入初始 粮/布/银 + 给中央国库注入铜 → economy.RecordMoneyIssuance（**那笔铜**的 INITIAL_ENDOWMENT 审计）"。
- **最小复现**（创世档 + 读口，`sw3-genesis` / `im1`）：
  ```
  Σactor.money = {silver 267200, copper 100000} = 367,200
  Σ发行记录     = {silver  67200, copper 100000} = 167,200      ← 差 silver +200,000
  GET /api/economy/gov → gov-unit-gov-province: treasuryAccounts.money={silver:100000},
                         issuance={"recordCount":0,"initialEndowment":{},"netIssuance":{}}
  ```
  three-powers 同样差 **+200,000 silver**（424,600 vs 224,600）。
- **期望 vs 实际**：判据 ④③ 的严格读法"除 `simos.gov.issueMoney` 外没有别的凭空加钱路径"——**运行期成立**（§2.4③ 的全部读数都精确），**创世期不成立**：这条路径既不是 `gov.issueMoney`、也不是铸币生产、也**没有审计记录**；它占 small-world 创世银总量的 **74.9%**（200,000/267,200）。
- **注**：W5 账本 §⑥ 的"凭空加钱口"排查表把"创世 `INITIAL_ENDOWMENT`（`EconomySeeder`）"列为 #4，但**漏了 `GovWorldBootstrap` 这条注资**（它不是 `INITIAL_ENDOWMENT`，是裸原语）⇒ 表本身也要补一行。**不是 W5 引入**（A1 阶段就在）。

### D5 【观察·语义】自持铸币债只增不减、被计息、并被标 `DEFAULTED`

`sw600-d600.json`（唯一一条 debtor==creditor==`hh-gov-world-silver` 的合同）：
```
unit={@class:money, currency:{silver}}  terms={interestRatePerMillePerCycle:20, interestTiming:AFTER_REPAYMENT_ON_CLOSE,
                                              repaymentRule:AVAILABLE_SURPLUS_SHARE, defaultRemedy:MARK_DEFAULTED}
principal=2940  openedDay=120  lastInterestDay=600  dueCycle=21  status=DEFAULTED        （d360 时 principal=1624）
```
- 铸币的"货币 = 政府债务"是**自持合同**（W5-fix 裁定：还债轮排除自持、不当可执行债权）⇒ 它**永远不被偿还**，按 2%/周期计息（500 → 1,624@d360 → 2,940@d600），并被 `defaultRemedy` 标成 **`DEFAULTED`**。
- 期望 vs 实际：期望自持合同是"发行额 = 政府负债"的记账事实；实际它在债务统计里表现为**一笔违约中的政府债**，且本金随利息增长（每 2%/周期）。**无执行影响**（还债轮结构上排除它），但读债务读数的人会误读；W6 建议：自持合同要么不标 `DEFAULTED`、要么单列一栏。

### D6 【已知粗口径·只记不改】多币种下 `limit` 逐币种共用（见 §2.10 的 R4/R5 真读数：旋钮 300 ⇒ 合计 600）

`MintSettlement.java:154` 的 `long limit = government.mintScalePerCycle();` 在逐币种循环内取值 ⇒ 旋钮是"每币种上限"而非"跨币种总额上限"；日志字段 `previousCycleMinted` 是跨币种求和、状态字段是逐币种表。**现状记录，不改**（任务书 §1.10 明说"只记不改"）。

### D7 【观察·日志级别】判据 ② 的具名行只在 DEBUG

`MINT_POLICY_ZERO`（旋钮 0 ⇒ 0 毫）是 **DEBUG** 级（`MintSettlement.java:208-230`，设计理由：关掉的政策不是拒绝、避免每关账日刷屏）；默认 INFO 档下"旋钮 0 ⇒ 0 毫"**没有 INFO 行**（判据①③的 `MINT_LIMIT_REJECTED` 是 INFO）。⇒ 验收/排查判据②时要显式开 DEBUG。**非缺陷，记录口径**。

### D8 【既存·已裁定保留，但判据字面不成立】GM 工具 `actor.AdjustAccounts` 本身就是一条无审计的凭空加钱口

- **现场**：`ActorAdjustAccountsTool`（`NAME = "actor.AdjustAccounts"`，`ActorAdjustAccountsTool.java:35`）注册在 **GM 写集**（`SimosToolSource.java:663`）；描述原文："调整 actor 账目：固定 actor.AdjustAccounts，载荷 `{entries:[{household, goods:{…}?, money:{币种:有符号净增量}?}]}` … 缺账只允许纯正增量并新建"。它的写路径就是 `AdjustAccountsHandler.applyMoneyDelta`（**纯正增量、不留 `MoneyIssuanceRecord`**）—— 正是 `simos.gov.issueMoney` 的**余额腿原型**。
- **期望 vs 实际**：判据 ④③ 的字面读法"除 `simos.gov.issueMoney` 之外没有别的凭空加钱路径"——**运行期我观测到的两条路径确实是铸币与 `gov.issueMoney`**，但**工具面**上这条窄工具仍可被 GM 直接调用来给任意家户加钱（且 GM 面 `GmAutoApproveGate` 无脑过、无审批摩擦）。
- **处置建议**：它是用户裁定 C-4 明确保留的余额腿（W5 账本 §⑥ 表 #2 已如实记），**本批不该也不许改**；但 W6 若要把判据 ④③ 写成"可测的断言"，应把范围限定为"**运行期结算路径**"，或把这条工具标成"仅组合工具内部使用"（`GmOnlyCommand` + 隐藏出 MCP 工具面）。**我没有真调用它以制造新钱**（只核了注册面与描述），故"它能真的加钱"这一条是**代码面结论**，不是运行读数。

---

## 4. 结论（逐项 绿 / 红 / 未验）

| # | 项 | 结论 | 一句依据 |
|---|---|---|---|
| 1 | 铸币有生产账（判据①） | **绿**（带 D3 限制） | `PRODUCTION_EFFICIENCY_HARVEST scale=1` + `MINT_CREDIT minted=500 requested=500 scale=1 perUnit=500 treasuryBefore=0 treasuryAfter=500`；工具 8,000→6,000、劳动 1,117,767（d119）；`scale×perUnit == minted` 逐值 ✓ |
| 2 | 旋钮 0 ⇒ 0 毫（判据②） | **绿**（探针取数） | `PROBE R3 moneyDelta=0` + `MINT_POLICY_ZERO reason=mint-scale-zero minted=0`；工具仍 4,000→2,000、无新记录；three-powers 240 天 Δ=0、`MINT_*` 0 行 |
| 3 | 上限 = 政策旋钮（判据③，v3.18） | **绿**（探针取数） | 旋钮 300 ⇒ `limit=300 rejected=200 reason=above-mint-scale-policy`、实铸 300；旋钮 2,000（上轮 300）⇒ 实铸 **500 不被夹**；`previousCycleMinted`/`mintedLastCyclePerCycle` 真服务侧 day240 得 `{silver=500}` |
| 3′ | "旋钮可被 GM 改" | **红** | D1：唯一会铸币的政府 `world-silver` 无 GM 写口（HTTP 422 原文）⇒ ②③ 无法用 GM 工具驱动 |
| 4 | 入市只能由决策人触发（判据④） | **绿**（运行期结算路径）+ 两处具名例外（D4 创世 / D8 工具面） | ① 国库 0→500（`MINT_CREDIT` before/after）② 无授权单 600 天国库 = 5×500=2,500、`GOV_MARKET_AUTO_ORDERS_SUPPRESSED authorizationOnly=true wouldBeSellMilli=4000` ×2,160 行 ③ grep：运行期只有铸币 + `gov.issueMoney` 两条、窗口恒等式精确；★ **授权单真样本**：day123 `GOV_MARKET_MANDATE_BUY_ORDER/FILLED/CLEARED`、国库银 500→498 粮 0→20、Σ货币不变。例外：D4 创世 +200,000 银无审计、D8 GM 窄工具 `actor.AdjustAccounts` 可无审计加钱 |
| 5 | 每条发行有 `MoneyIssuanceRecord`（判据⑤） | **绿** | 5 条 `mi-mint-world-silver-<day>-silver`（120/240/360/480/600）×500，`kind=MINT_PRODUCTION`，id 形状 `mi-mint-<gov>-<day>-<cur>` ✓，逐值与 `MINT_SETTLED`/`MINT_CREDIT` 相等 |
| 6 | `gov.issueMoney` 例外仍可用 | **绿** | 真 MCP `tools/call` 回执（`kind=FISCAL_ISSUE`、一批两命令、rev 2→3）；国库铜 100,000→**100,777**；新记录 `gov-issue-gov-unit-gov-central-120-copper-1` |
| 7 | 铁律 5 往返（写→读→续跑） | **绿** | 重启后读口与写档前**逐字段相同**；360→361 续跑 vs 一次 0→361 **逐叶 0/87,033 差**（state md5 同为 `f60ca6c0…`） |
| 8 | B2 分段等价 + Δ=1 + 性能 | **绿** | 三份 dump 状态 md5 全等 `24c969e7…`；Δ=1/3/360；同会话 A/B：W5 中位 **8.266 s** vs W4 7.855 s（**+5.2%**，≤10%） |
| 9 | 守恒（货币/人口/债务） | **绿**（恒等式带 D4 偏移） | 窗口恒等式逐值精确（+2,500 = 5×500；+777）；人口 `economy.classes == social.groups` 逐档相等（4,800/4,339/3,724/6,350/5,930）；债务逐介质给出；全量恒等式差 **+200,000 银** = D4 |
| 10 | 多币种现状读数 | **绿**（只记不改） | small-world `{silver,copper}`、three-powers `{silver,copper,gold}`；双币种探针：旋钮 300 ⇒ 合计 **600 > 300**（limit 逐币种各自套用）、`previousCycleMinted` 跨币种求和 1,000 vs 状态逐币种表 |

**总评**：W5 的机制本体（生产方式 + 生产账 + 国库落点 + 旋钮闸门 + 审计 + 债务 + 无自动投放 + 可经授权单入市）**在真世界里成立且可复现**；两条要修的是 **D1（旋钮无写口 ⇒ 政策面缺口）** 与 **D2（发行读数把铸币记成回笼）**；要改文档/口径的是 **D3（"工具耗尽即停产"的账本断言与真世界不符）**、**D4（创世注资未入审计、账本排查表漏项）** 与 **D8（判据④③的字面读法要限定到运行期结算路径）**。**未发现**铸币路径上的守恒破口、第二条**运行期**加钱口或铁律 5 缺陷。

---

## 5. 我没做 / 没验证的（如实）

1. **判据 ②③ 没有用 GM 工具验过** —— 唯一会铸币的政府没有旋钮写口（D1），我用 `/tmp` 探针按轮改**同一字段**取证。**"GM 工具能改旋钮"这件事我没验过（且证据指向不能）**。`simos.economy.upsertGovUnit` 带旋钮的端到端落库（W5 账本 §⑤-3 的未验项）**仍未验**，而且我查实该工具**根本没有**旋钮字段（旋钮在 `economy.RegisterGovernment` 的载荷里）。
2. **多币种的真实世界样本没构造出来** —— 真世界的铸币产业只产 `silver`；§2.10 的双币种数字来自**探针改配方**（`moneyOutputPerUnit={silver:500, copper:500}` + 清空 `gov-central` 的 copper 发行权以避免"同一币种只能有一个发行主体"守卫 + 补工具库存）。**播种世界里的多币种铸币窗口未验**。
3. **铸币的"劳动工资"没验** —— 铸币劳动来自首都城镇池的配额（`SEED_MINT_LABOR grantedLaborMilli=9440`/日、实测消耗 9,250/日），但**这份劳动有没有得到报酬、报酬从哪个账户出**，我没查（`flows` 里国库没有 wage 支出栏）。判据①的"劳动真实消耗"只验到"配额被消耗 + laborScale=1"。
4. **没验**：`ALGORITHM_UNCOVERED`、`blockedByPortMilli`、0 价跨格运费等 W2/W3/W4 遗留项（不属本批）；B2 只跑 small-world（three-powers 的 B2 未跑）。
5. **没验**：`MINT_LIMIT_REJECTED` 的多币种字段语义（R5 里两条拒各带 `rejected=200`，**跨币种合计被拒 400** 而请求合计 1,000、旋钮 300 —— "rejected" 是逐币种量，这是我从数字推的，没有文档口径）。
6. **没验**：`mintedLastCyclePerCycle` 在 B2 分段等价下的逐字节一致（W5-fix5 §⑥-8 的同一条未验项）——我只验了同一推进方式下三份 dump 的状态 md5 全等（含该字段），**没有**单独对该字段做分段对照。
7. **没跑**：`clean verify` 全量门禁（G1：SpotBugs 15/15、前端 412/412、测试数不回退）—— 本任务是**只读验收 + 现构 jar**，我跑了 `-DskipTests package -pl simos-app -am`（rc=0）。**G1 的测试面证据我没有自己取**（W5 的两条测试批账本在 `impl-ledger.md` §W5c / `2026-10-11-w5b-test-batch`，我未复跑）。
8. **没验**：`/api/economy/gov` 之外的前端/GUI 呈现（D2 只验了两个 JSON 端点；`ApiViews` 背后的 GUI 页面如何显示 `withdrawal` 我没看）。
9. **性能**：同会话 A/B 三次/组、幅度小（+5.2%），**没有**做多轮方差分析；W1/W4/W5 三个 jar 各自播种的世界内容略有差异（W5 多一个铸币 unit），与 W4 报告同样的口径限制。
10. **D3 的"既存 vs W5 引入"判定**基于代码注释与 `guardCycleInputs` 的调用链（`drawCycleInputs` 早于 W5），**没有**用旧 jar（W4/W1）复跑"第 5 轮是否也产"来对照 —— 若要钉死"W5 没有改变这条行为"，应拿 `w4.jar` 跑同一个 mint 产业（但 W4 世界根本没有铸币产业，做不了对照）。
11. **没有**真调用 `actor.AdjustAccounts`（GM 窄工具）去制造新钱（D8 的"它能凭空加钱"是代码面结论：注册面 + 描述 + handler 的 `applyMoneyDelta` 纯增量）；也**没有**验它的权限面（`GmOnly`/资源声明）在决策人侧是否真的挡住。
12. **没有**验 `GovWorldBootstrap` 的注资（D4）在**创世批**之外是否还有别的调用点（`ThreePowersGovBootstrap` 同款；small-world 的 200,000 与 three-powers 的 +200,000 我都量到了差额，但**没有**逐条把每个国库的余额与记录对完）。

---

## 附：原始产物位置（全部在 `/tmp`，**未落仓**）

| 类别 | 路径 |
|---|---|
| 构建日志 / jar | `/tmp/w5acc-build.log`；`/tmp/w5acc/w5.jar`（md5 `5a3d080a…`） |
| 装置脚本 | `/tmp/w5acc/{runner.py,canon2.py,conserve_w5.py,inspect_mint.py,issue_money.py,run-probe.sh,W5AccKnobProbe.java}` |
| plan | `/tmp/w5acc/plan-*.json` |
| 服务日志 | `/tmp/w5acc/results/{sw1,sw2,sw3,sw4,sw5,sw6,sw600,sw601,sw6cycle,sw6b,repA,tp1,im1,ab-*,perfw5-*}.log` + `issueMoney.log` |
| 结果 JSON | `/tmp/w5acc/results/*.result.json`、`*-get*.json`（HTTP 读口原文）、`issueMoney.result.json` |
| dump（全量信封） | `/tmp/w5acc/dumps/*.json` |
| store | `/tmp/w5acc/store/{sw1,sw2,sw3,sw4,sw5,sw6,sw600,sw601,sw6cycle,sw6b,repA,im1,tp1,perfw5-*,ab-*}` |
| 探针日志 | `/tmp/w5acc/evidence/knob-probe.log`（含 `MINT_*` 事件原文与 `PROBE` 读数） |

**收尾自检**：`git status --porcelain` = **空**（与开工一致）；本报告在 `.superpowers/sdd/.gitignore`（内容 `*`）之下，不入版本库。
