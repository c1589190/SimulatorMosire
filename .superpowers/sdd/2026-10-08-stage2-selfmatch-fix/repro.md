# 重跑步骤与预期（控制方执行）—— 商品撮合同户自配对修复

> 本修复**不在**现有 jar 里：`simos-app/target/*-shaded.jar` 是 `package` 的产物，而本次实现代理
> **只跑了 `compile`**（任务书明令不跑 `test/verify/package`）。⇒ 真跑必须**先重建 jar**。
> 账本：同目录 `fix-ledger.md`（机制结论 ①②③、其它腿复核 ④、数值影响 ⑦）。

---

## 0. 一句话判据

| | 命令 | 改前（已实测，见 `b-3p-120/finish3p.log`） | 改后（预期） |
|---|---|---|---|
| 推进 90→120 | `POST /api/advance {"from":90,"to":120,…}` | **HTTP 400**，日志 `Transfer 的两端不得相等 …: HOUSEHOLD:hh--1_1-rural-middle_peasant` | **HTTP 200**；`SEG 3 tick 120 …`；`3P DONE` |
| 日志 | 同一份 `service.log` | 无 `MARKET_SELF_MATCH_SKIPPED` | 至少 1 条 `event=MARKET_SELF_MATCH_SKIPPED … commodity=cloth reason=same-owner-buy-and-sell-in-one-round` |

★ **判据的判别力**：改后的日志里**必须出现** `MARKET_SELF_MATCH_SKIPPED`。
若只看到"200 且无这条日志"，说明这次的世界**没走到**那个配对（修复没被用到），应继续推进（120→150→180）
直到它出现，否则等于没验到。

---

## 1. 前置：停服务 + 重建 jar（**不是**本代理做的）

```bash
pgrep -af "surefirebooter|classworlds.launcher"        # 确认本仓无 Maven/服务
pgrep -af "simos-shaded"                               # 确认没有旧实例占端口
cd /home/cna/SimulatorMosire
./mvnw -q -DskipTests package -pl simos-app -am        # 重建 shaded jar（本次修复的字节码在这里面）
md5sum simos-app/target/simos-app-*-shaded.jar | tee /tmp/selfmatch-fix-jar-md5.txt   # 留痕（自指）
```

★ 记住 jar 的 md5：本轮所有结论都只对这份字节有效（AGENTS §三「自建装置要自指」）。

**世界库不要就地跑**（冻结基线要留净）；复制一份：

```bash
SRC=/home/cna/simos-runs/2026-10-08-sw19-arb/b-3p-120/store        # tick 90 / rev 4
RUN=/home/cna/simos-runs/2026-10-08-sw19-arb/b-3p-120-fix          # 新目录
mkdir -p "$RUN" && cp -a "$SRC" "$RUN/store"
```

## 2. 起服务

```bash
cd /home/cna/SimulatorMosire
tools/run-shaded.sh simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
  --store "$RUN/store" --world=three-powers \
  --gui-port 6001 --mcp-port 6015 --approval-port 6013 \
  > "$RUN/service.log" 2>&1 &
```

★ **端口对不上是个坑**：控制方现成的驱动脚本 `b-3p-120/finish3p.py`、`capture3p.py` 里写死
`BASE="http://127.0.0.1:6001"`；任务书示例写的是 `--gui-port 6011`。二选一：**用 6001**（照旧脚本），
或起 6011 并改脚本里的 `B`/`BASE`。
★ 想看**理由 DEBUG**（`MARKET_SELF_MATCH_SKIPPED_WHY`）就加 `-Dsimos.economy.logLevel=DEBUG`；
只看 INFO（默认）也能看到那条跳过记录。

判活（三条一起看，别只看 `/api/*`）：

```bash
curl -s -o /dev/null -w '%{http_code} ' http://127.0.0.1:6001/
curl -s -o /dev/null -w '%{http_code} ' http://127.0.0.1:6001/index.html
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:6001/styles.css    # 期望 200 200 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:6001/api/state
```

## 3. 驱动到 120（复用控制方现成脚本）

```bash
cd /home/cna/simos-runs/2026-10-08-sw19-arb/b-3p-120
python3 finish3p.py 2>&1 | tee "$RUN/finish3p-fixed.log"
```

脚本做的事：`GET /api/state` 落 tick30/tick90 逐格读数 → `POST /api/advance {from:90,to:120,branch:main,expectedRevision:head}` → 落 tick120。
（它把 dumps 写到**原目录** `b-3p-120/dumps/`；想隔离就把 `RUN`/`OUT` 改成新目录再跑。）

**预期输出**（改前这里抛 `HTTPError: HTTP Error 400: Bad Request`）：

```
[3p] 格数=37
落 tick30: {...}
落 tick90: {...}
SEG 3 tick 120 pop <数> grain <数> debt <数> money {...} (<秒>s)
3P DONE
```

## 4. 日志与读数核对（这是"真的没有自转移"的判据）

```bash
L="$RUN/service.log"     # 或 b-3p-120/service.log（取决于重定向）
grep -c "Transfer 的两端不得相等" "$L"          # 期望 0
grep -n "MARKET_SELF_MATCH_SKIPPED" "$L" | head  # 期望 ≥1，且带 household/commodity/buyerHex/sellerHex/skippedQuantityMilli
grep -n "MARKET_CREDIT_SELF_MATCH_SKIPPED" "$L" | head   # 可能 0（只在货币信用那条路上才出现）
grep -n "SELF-TRANSFER-DIAGNOSTIC" "$L"          # 期望 0（临时插桩已撤回，这里只作反例扫描）
grep -n "event=GUI_ACCESS.*path=/api/advance" "$L"  # 期望 status=200（不是 400）
```

再推进一轮，确认"不是把崩溃推后":

```bash
python3 - <<'PY'
import json,urllib.request
B="http://127.0.0.1:6001"
st=json.loads(urllib.request.urlopen(B+"/api/state").read())
print("tick",st["meta"]["timestamp"]["tick"],"rev",st["heads"]["main"])
req=urllib.request.Request(B+"/api/advance",
    data=json.dumps({"from":120,"to":150,"branch":"main","expectedRevision":st["heads"]["main"]}).encode(),
    headers={"Content-Type":"application/json"})
print(json.loads(urllib.request.urlopen(req,timeout=3600).read())["heads"])
PY
```

★ 期望：`120 → 150` 也 200；日志里**没有**任何 `Transfer 的两端不得相等`。

## 5. 数值不回归的对照（本修复的"只动会崩的那一次"性质）

- 改前那次失败的运行已经落过 `b-3p-120/dumps/seg-030.json`、`seg-090.json`（tick 30/90 逐格读数）；
  改后同一份 store 重跑，**tick 30 / tick 90 的这两个 dump 必须逐值相同**（守卫在 day=120 才第一次命中）。
  比较：`diff <(python3 -m json.tool b-3p-120/dumps/seg-090.json) <(python3 -m json.tool <新目录>/dumps/seg-090.json)`
  —— 期望**无差异**（时间戳/秒数字段除外：`seg-*.json` 里没有秒数，秒数只在 `summary.jsonl` 里）。
- 逐日成交/价格：若要看 day≤119 的市场面，用 `-Dsimos.economy.logLevel=DEBUG` 重跑，比较 day=115 那一轮的
  `MarketReport` 读数（改前后应逐值相同；唯一允许的差异是 ⑦-2 那条"同一自配对在旧代码里先撞异币早退"的
  逐槽归因，且它只可能出现在 day=120）。

## 6. 变异自证（可选，但建议做一次）—— 证明"没有这条守卫就必崩"

```bash
# 把 pairUp 里那段 `if (sell.seller.actor.equals(buy.buyer.actor)) { … }` 整块注释掉（或把条件改成 false），
# 重新 package ⇒ 起服务 ⇒ 同一份 store 推到 120 ⇒ 必须重现 HTTP 400 + Transfer 自转移异常；
# 随后 git checkout 还原该文件、重新 package（比 md5）。
```
★ 这一条的"红"已有现成基准：改前 jar 的 `finish3p.log` 就是同一个 400（`b-3p-120/service.log` 22:34:04.176）。
若不想改代码，也可用**旧 md5 的 jar**（若还留着）跑同一条命令复现 400，再用新 jar 跑出 200 —— 这就是 A/B。

## 7. 回退

```bash
cd /home/cna/SimulatorMosire
git diff -- simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java | head -200
git checkout -- simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java   # 只回退本文件
```
★ 该文件**只有本批的改动**（`git diff` 里 `MarketSettlement.java` 一处，+118/-4 量级）；
工作树里 `Shell.java`/`MarketTopologyBook.java`/`EconomySeeder.java`/`WorldRegistry.java` 的改动**不是本批的**，别一起 checkout。

## 8. 已知会看到的"正常噪音"（别误判成故障）

- `MARKET_CURRENCY_MISMATCH_REJECTED` 成片：3 币世界的既有行为（买方币恒为 `SILVER_SPECIE`，
  卖方收款币 = 该格 numeraire）—— 与本修复无关。
- 每轮（day=95/100/…/120）都会有 `ARBITRAGE_ROUND … commodities=cloth,grain`：套利买盘仍在（根因未在订单层处理，见账本 ⑧-5）。
- 一轮里可能出现多条 `MARKET_SELF_MATCH_SKIPPED`（每个命中一次记一条）。
