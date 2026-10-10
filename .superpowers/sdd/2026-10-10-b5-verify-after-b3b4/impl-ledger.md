# 验收账本 —— B5：B3（E14 修）+ B4（落盘集合顺序）之后 **B2 三条判据复验**

> **验证 Agent**（只读：**未改任何代码 / 测试 / 文档**；本文件是唯一新增物）。日期 2026-10-10。
> 判据出处：`docs/superpowers/plans/2026-10-10-all-modules-target-completion-plan.md` **§3.3（P-1/P-2/P-3）/ §3.4（性能红线）**。
> 被验提交：**HEAD = `41afbb79`**（B4）+ `162f9094`（B3）；上一轮基线账本 = `.superpowers/sdd/2026-10-10-b2-verify-path-equality/impl-ledger.md`（jar `2290c0b4…`）。
> **交账结论：P-1 绿 / P-2 绿 / 多区 360 绿（含三段）/ P-3 绿 / §3.4 绿（RevΔ 不变、+3.4%）/ 默认世界回归与上一轮逐字节相同。**

---

## ★ 交账（≤20 行）

1. **① 装置与 jar**：装置 = `/tmp/b2v-run.py`（md5 `44008a9b966dfd2903b44cb6880ed8c8`，**与上一轮同一份、未改**）驱动 `/tmp/b5v-chain.sh`（md5 `e8c704d6d639d2c489c21b43d83172d3`）+ `g3-dump.py`/`b2v-dump2.py`/`b2v-agg.py`/`b2v-stress.py`/`a8-diff.py`。
   按 HEAD（`41afbb79`）构建：`tools/mvn-lock.sh -DskipTests package` rc=0 ⇒ jar md5 **`f69331a604c97e2af81e280a63fb2308`**（27,048,902 B，21:15:32）；`find . -name '*.java' -newer <jar>` = **0**。
   jar 内容自证：`javap` 见 B4 的 `DebtReferenceReconciler.sameEntriesInOrder` 与 B3 的 `EconomyEnterpriseSettlement.rentCommodity`（+ `rentRules(…, Industry, ProductionMode)`）。
   在跑实例 pid 2081 持 `/tmp/simos-shaded-NwvHbq.jar`（独立快照）⇒ **无实例持 target jar**，未触碰 6111/6115/6113 与共享 jar。
2. **② P-1（tick-60 dump，small-world）**：t1 `0→60` 一次 / t2 `0→30→60` / v60 `60×1 天` = **`9d8d88f994ecad194c8eb50202e7efbf` × 3**（`cmp` 逐字节相同、`a8-diff` 差异叶 **0**）⇒ **绿**。
   RevΔ = 1 / 2 / 60（= 段数，未退化）。
3. **② P-2（tick-360 dump）**：s1 一次 / s3 三段 / s12 十二段 / s360 逐 tick = **`ecb9f082d283730cc55dd77b40ddf875` × 4**（逐字节相同、差异叶 0）⇒ **绿**。RevΔ = 1 / 3 / 12 / 360。
   `DEBT_STRESS_BRANCH_FIRED` 四路径均 **12 次**且 day 完全相同（60,90,120,150,180,210,240,243,270,300,330,360）⇒ 计数只由世界史驱动。
4. **③ 多区回归**：three-powers（37 格，**创世新 store**）`0→360` **committed**（rev 1→2，13.25 s，37/37 activated）；`0→120→240→360` 三段亦 **committed**（rev 1→4）；两份 dump md5 均 **`eb4366034c2ab14f1c3c0e95781eabb1`**（逐字节相同）⇒ 上一轮 day-270 E14 **已消**，且多区也路径收敛。
   机理实证（非仅"没报错"）：rev-2 changeset 里那条失败 unit 的规则 **`commodity=cloth → haul`**（受方 `hh-3_0-rural-poor_peasant`、GROSS_OUTPUT、300‰、priority 10 不变）。
5. **④ 回归（small-world 0→360）**：**committed**；`IllegalStateException/BAD_REQUEST/ ERROR /Exception/at io.mosire` = **0/0/0/0/0**；` WARN ` = **12**（全为既有 `POPULATION_SETTLE_REMAINDERS_CLEANED`）。
   人口Σ **4,925**、粮Σ **3,278,080,223**、货币 copper 100,000/silver 273,200（`privateCirculation==baseMoney` **True**）、债 **105,582,647·1001**（credit==debt **True**）、19/19 activated、产业行 44。
   成交（TRACE）：`MARKET_FILL 6,664` / `MARKET_CREDIT_FILL 5,870` / `TRANSFER 23,370` / `HAUL_SERVICE_DELIVERED 207` / `LIQUIDATION_PLAN 13` / 378,458 行 —— **与上一轮逐值相同**。
6. **④ 性能**：单段 `0→360` 交叉两轮 —— 本批 **7.483 / 7.669 s**（最快 7.483），基线 `c4e436c7…`@7ce800ac **7.468 / 7.237 s**（最快 7.237）⇒ 劣化 **+3.40%**（最快对最快）/ **+3.04%**（均值）≤10% ✅；**Revision `1→2`，Δ=1，两 jar 相同** ✅。
7. **⑤ P-3**：s1 单段 `0→360` 分布 `{0:103,1:13,2:20,3:1,5:16,7:2,9:39,11:16}`，**max 11、≥2 = 94 户**（≥3=74、≥5=73）；样本如 `hh--1_-1-rural-landlord` `cycles=11`、`tenancy-fixed-kind-tenant-operator`、`lastTransitionDay=60` ⇒ **绿**（t1 `0→60`：max 2、57 户）。
8. **⑥ 未验证**：P-4 变异自证、P-5 `clean verify`/前端门禁、v17levant 缺省世界、3600 tick、B3 的"多产出产业拒因"分支（本世界构造不出）、B3 DEBUG 事件（INFO 档不可见）；`--world=small-world` 是 §3.3 口径，**不是** `ShellConfig.DEFAULT_WORLD_ID`（= `v17levant`）。
9. **⑥ 新发现缺陷**：**无阻塞性新缺陷**。两条如实记：⒜ 旧 store（已落盘 cloth 规则）用新 jar 续跑 **仍被 E14 拒**（实测 `0→360` 前的 day-240 旧档 `240→360`：rev 3→3、拒绝）⇒ B3 是"生产者修复"，**多区必须从创世新 store 起**（与 B3 账本 §7-2、用户裁定 §一.11 一致）；⒝ 上一轮账本写的 "EXPIRED 4" 口径 = `HAUL_SERVICE_EXPIRED` **1** + `HAUL_SERVICE_EXPIRED_DETAIL` **3**（两轮同值，非差异）。

---

## 0. 装置与身份（自指）

| 项 | 值 |
|---|---|
| HEAD | `41afbb79`（B4 落盘集合顺序）+ `162f9094`（B3 E14） |
| 本批 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar` md5 **`f69331a604c97e2af81e280a63fb2308`**，27,048,902 B，mtime 2026-10-10 21:15:32 |
| 源码新鲜度 | `find . -name '*.java' -newer <jar> \| wc -l` = **0**（收账时复核） |
| 构建 | `tools/mvn-lock.sh -DskipTests package` → `BUILD_RC=0`（3.3 s，增量：economy class 21:11:30 新于源 21:07:01） |
| jar 内容自证 | `javap`：`DebtReferenceReconciler.sameEntriesInOrder`（B4）、`EconomyEnterpriseSettlement.rentCommodity/rentRules(…,Industry,ProductionMode)`（B3） |
| 在跑实例 | pid **2081** 持 `/tmp/simos-shaded-NwvHbq.jar`（`run-shaded.sh` 的 `mktemp` 快照）⇒ 无实例持 target jar；未触碰 |
| 上一轮 jar | `2290c0b4c1331f85939b296993eb9cac`（B2 账本，同一个 `b2v-run.py` 装置） |
| 性能基线 jar | `/tmp/simos-b2-base/…/simos-app-0.1.0-SNAPSHOT-shaded.jar` md5 **`c4e436c7f08e31e526e7a14e1c1a416b`**（`7ce800ac` worktree） |
| 装置 | `/tmp/b2v-run.py` md5 **`44008a9b966dfd2903b44cb6880ed8c8`**（**与上一轮同一份**）；本批驱动 `/tmp/b5v-chain.sh` md5 **`e8c704d6d639d2c489c21b43d83172d3`**；读数 `g3-dump.py`（19 格）/ `b2v-dump2.py`（37 格）/ `b2v-agg.py` / `b2v-stress.py` / `a8-diff.py` |
| 纪律 | 独立 store `/tmp/simos-b5v-*`、独立端口 10301~10483；**一次一个实例**；未动在跑服务/共享 jar；旧轮产物另存 `/tmp/b5v-old/` 后才写新文件 |

★ **世界口径**：§3.3 的"缺省 small-world"按 **`--world=small-world`** 执行（与 A6/A8/B2 同一夹具）。
**如实记**：`ShellConfig.DEFAULT_WORLD_ID` 代码缺省是 `v17levant`（59223 hex），本轮**未跑**。

---

## 1. ★ P-1：`0→60` 一次 vs `0→30→60` vs `60×1 天`

| tag | 路径 | 段数 | tick-60 dump md5 | RevΔ | 耗时 |
|---|---|---:|---|---:|---:|
| t1 | `0→60` 一次 | 1 | `9d8d88f994ecad194c8eb50202e7efbf` | 1 | 1.96 s |
| t2 | `0→30→60` | 2 | `9d8d88f994ecad194c8eb50202e7efbf` | 2 | 2.20 s |
| v60 | `60×1 天` | 60 | `9d8d88f994ecad194c8eb50202e7efbf` | 60 | 9.32 s |

- **判定：判据绿**。`cmp` 三份两两**逐字节相同**；`a8-diff.py t1 t2` ⇒ **差异叶子数 = 0**。
- ★ 与上一轮对照：新 t1 = **旧 t1**（`9d8d88…`）⇒ B3/B4 **没有改变**单段路径的落盘内容；旧 t2 `e4b23fbc…`、旧 v60 `857ec762…` 已收敛到同一份。

## 2. ★ P-2：`0→360` 一次 / 3 段 / 12 段 / 逐 tick

| tag | 分段 | 段数 | tick-360 dump md5 | RevΔ | 耗时 |
|---|---|---:|---|---:|---:|
| s1 | 1 段 | 1 | `ecb9f082d283730cc55dd77b40ddf875` | 1 | 7.62 s |
| s3 | 3 段（120） | 3 | `ecb9f082d283730cc55dd77b40ddf875` | 3 | 7.88 s |
| s12 | 12 段（30） | 12 | `ecb9f082d283730cc55dd77b40ddf875` | 12 | 9.38 s |
| s360 | 360 段（逐 tick） | 360 | `ecb9f082d283730cc55dd77b40ddf875` | 360 | 88.64 s |

- **判定：判据绿**。四份 `cmp` 互比**逐字节相同**；`a8-diff.py s1 s360` ⇒ **差异叶子数 = 0**；无差异 ⇒ 无需给分叉 tick。
- ★ 与上一轮对照：新 s1 = **旧 s1**（`ecb9f0…`）⇒ 默认世界 0→360 的落盘状态与 B2 轮**逐字节相同**（B3/B4 不改它）；旧 s3 `fe9e305b…`、旧 s12 `a323f2cc…`、旧 s360 `5ca942b7…` 全部收敛到 s1。
- ★ **语义解耦复核**：`DEBT_STRESS_BRANCH_FIRED` 在 s1/s3/s12/s360 上均 **12 次**、day 逐日相同（`60,90,120,150,180,210,240,243,270,300,330,360`）⇒ 只由世界关账日驱动。

## 3. ★ 多区回归（B3 验收）：three-powers 37 格

| tag | 路径 | 结果 | RevΔ | md5 | 耗时 |
|---|---|---|---:|---|---:|
| tp360 | `0→360` 一次（**创世新 store**） | **committed** ✅ | 1 | `eb4366034c2ab14f1c3c0e95781eabb1` | 13.25 s |
| tp3seg | `0→120→240→360` | **committed** ✅（3/3 段） | 3 | `eb4366034c2ab14f1c3c0e95781eabb1` | 13.50 s |

- 上一轮同一装置、同一世界：本批 jar `2290c0b4…` 在 **day 270** 被拒（`BAD_REQUEST / E14`，rev 1→1），基线 `c4e436c7…` committed ⇒ **本轮已转绿**，且两路径 dump **逐字节相同**。
- 日志：`IllegalStateException / BAD_REQUEST / " ERROR " / "Exception" / "at io.mosire"` = **0/0/0/0/0**；` WARN ` = 34（全部 `POPULATION_SETTLE_REMAINDERS_CLEANED`）；`产出表` 命中 0、`REASON_REFUSED` 命中 0。
- ★ **机理实证（不是"这次恰好没发生那笔租佃"）**：day-360 dump 的 `3_0` 格仍有同一条 **TENANCY CATTLE** 份额（`share-trade@3_0-CATTLE-…-urban-rich_peasant-TENANCY-0`，qty 5，owner=`hh-3_0-rural-poor_peasant`）；
  查 rev-2 的 `changeset_json`，失败 unit（`unit-trade@3_0-HOUSEHOLD-hh-3_0-urban-rich_peasant`）的规则现在是
  `{"type":"OUTPUT_SHARE","recipient":…hh-3_0-rural-poor_peasant,"pool":"GROSS_OUTPUT","ratePerMille":300,"commodity":{"value":"haul"},"priority":10}`
  —— 即 B3 的 `rentCommodity` 把 `cloth` 换成了本产业唯一产出 **`haul`**，与 `trade@3_0.outputPerUnit={haul}` 相符，E14 不再可达。
- 回归读数对照（37 格，`b2v-agg.py`）：

| 项 | 本批 tp360 | 旧码基线 `7ce800ac`（B2 轮） | 说明 |
|---|---|---|---|
| 人口Σ | **6,518** | 6,513 | +5 |
| 粮Σ | **6,474,973,171** | 4,616,130,192 | +40.3%（E5b 支路活，与小世界同族） |
| 货币Σ | copper 136,600 / silver 424,600 / gold 129,400 | 同 | `privateCirculation==baseMoney` **True** |
| 债 | **39,868,745 · 1018** | 4,902,190 · 961 | 8.1×（B2 让 E5b 真的开火） |
| activated | 37/37 | 37/37 | 产业行 83 |
| stressMax | **10**（≥2 = 111 户） | 1（≥2 = 0 户） | 与 P-3 同族 |

## 4. ★ §3.4 性能红线

| 项 | 本批 `f69331a6…` | 基线 `c4e436c7…`@`7ce800ac` | 判据 |
|---|---|---|---|
| advance 墙钟（交叉 2 轮） | **7.483 / 7.669 s**（最快 7.483，均值 7.576） | **7.468 / 7.237 s**（最快 7.237，均值 7.353） | 劣化 **+3.40%**（最快对最快）/ **+3.04%**（均值）≤10% ✅ |
| Revision（advance 前后 head） | `1 → 2`，Δ=**1**（两轮相同） | `1 → 2`，Δ=**1** | **必须相同 ⇒ 相同** ✅ |
| 其它分段 RevΔ | t2=2 / v60=60 / s3=3 / s12=12 / s360=360 / tp3seg=3 | — | 每段恰 1 次 ✅ |
| §3.4.3② 静态判据 | `EconomyDayView.java` / `time/WorkingDayView.java` 对 `save\|checkpoint\|persist\|codec\|changeSet\|revision\|copyOf` **仅注释命中、0 调用** | — | ✅ |

- ★ **对照口径提醒（本轮实测）**：机器负载与上一轮差约 20% —— 同一份旧码 s1 dump 上一轮 **9.603 s**、本轮基线 **7.237~7.468 s**。
  ⇒ 只有**同一轮内交叉跑**两侧才可比；本批 +3.4% 与上一轮 +3.8% 落在同一带内（B4 只改 `reconcile` 的 no-op 判据：单趟 O(n) 顺序比对，替代 `Map.equals` 哈希查表）。
- 复现：`bash /tmp/b5v-chain.sh`（内含 perf 交叉两轮）。

## 5. 回归：缺省 small-world 单段 `0→360`（与上一轮逐值对照）

| 项 | **本批（s1）** | 上一轮（B2，s1） | 判定 |
|---|---|---|---|
| committed | `"result":"committed"` | 同 | ✅ |
| 异常计数 | `IllegalStateException/BAD_REQUEST/" ERROR "/"Exception"/"at io.mosire"` = 0/0/0/0/0 | 0/0/0/0 | ✅ |
| ` WARN ` 行 | **12**（全 `POPULATION_SETTLE_REMAINDERS_CLEANED`） | 12 | ✅ 同 |
| 人口Σ / 粮Σ | **4,925 / 3,278,080,223** | 4,925 / 3,278,080,223 | ✅ 逐值同 |
| 货币Σ | copper 100,000 + silver 273,200；`privateCirculation==baseMoney` **True** | 同（True） | ✅ |
| 债 / 信用 | **105,582,647 · 1001**；credit==debt **True** | 同 | ✅ |
| activated / 产业行 | 19/19 · 44 | 19/19 · 44 | ✅ |
| 成交（TRACE，trl） | `MARKET_FILL 6,664` / `MARKET_CREDIT_FILL 5,870` / `TRANSFER 23,370` / `HAUL_SERVICE_DELIVERED 207` / `LIQUIDATION_PLAN 13`；378,458 行；139,887,384 B | 6,664 / 5,870 / 23,370 / 207 / 13；378,458 行；139,887,384 B | ✅ 全部同 |
| 日志档位不改状态 | TRACE 档 dump md5 = INFO 档 dump md5 = `ecb9f082…` | 同（复核） | ✅ |

★ **结论**：B3+B4 **对默认小世界 0→360 的落盘状态与事件面零扰动**（s1/t1/v60 dump 与上一轮逐字节相同）；
数值面的大改（债 18,596 → 105,582,647；stressMax 1 → 11）是 B2 修 E5b 的预期后果，与 B2 账本 §5.1 结论一致。

## 6. ★ P-3：单段长推进里 `consecutiveDebtStressCycles ≥ 2` 的真样本

| 路径 | 分布（cycles:户数） | 最大值 | ≥2 户数 |
|---|---|---:|---:|
| s1 `0→360` 一次 | `{0:103, 1:13, 2:20, 3:1, 5:16, 7:2, 9:39, 11:16}` | **11** | **94**（≥3=74、≥5=73） |
| t1 `0→60` 一次 | `{0:115, 1:1, 2:57}` | 2 | 57 |

- **判定：判据绿**（旧世界单段恒 ≤1）。样本（`b2v-stress.py`，6 条）：
  `hh--1_-1-rural-landlord` / `hh--1_0-…` / `hh--1_1-…` / `hh--1_2-…` / `hh--2_0-…` / `hh--2_1-…`，`cycles=11`、
  `position=tenancy-fixed-kind-tenant-operator`（原 `…-landlord`）、`lastTransitionDay=60`、
  `reason=class-decline:no-downward-target:…`。
- 与上一轮同分布（dump 逐字节相同 ⇒ 必然同）⇒ P-3 在 B3/B4 后仍绿。

## 7. 未验证 / 构造不出（如实）

1. **P-4 变异自证未做**（把"当日视图"改回段首 `base` ⇒ P-1/P-2 当场红）：归测试批；本轮**未**做任何代码改写。
2. **P-5 未跑**：本节只做真世界/状态验收；`clean verify`、SpotBugs、前端门禁**未跑**（不属本任务，且纪律禁改代码）。
3. **v17levant（代码缺省世界，59223 hex）未跑**；`corridor`、3600 tick、其它世界未跑。
4. **B3 的第 3 条分支（多产出产业 / 固定实物租的拒因 `RENT_COMMODITY_NOT_PRODUCED_REFUSED`）本世界构造不出**：
   tp360/tp3seg 日志里该事件命中 **0**（B3 账本 §7-4 自述同）。
5. **B3 的 DEBUG 事件 `RENT_COMMODITY_FROM_INDUSTRY_OUTPUT` 未在日志里直接看到**（缺省 INFO 档不发射 DEBUG）⇒
   本轮改用**落盘 changeset**（`haul`）作为机理实证；未再跑 DEBUG 档多区（INFO 档日志已 182 MB）。
6. **B4 §7 的前提依赖未独立证明**：`EconomyData.classes` 的键序本身是否"内容的纯函数"——本轮只观察到
   四条小世界路径 + 两条多区路径的 `classes[]` 顺序一致（dump md5 相同），**未**做反例构造。
7. **性能对照为共享机器上各 2 轮**（无 CPU 绑定）；±0.2 s 噪声未做统计检验。
8. **`WARN POPULATION_SETTLE_REMAINDERS_CLEANED`（12/34 条）未排查**：两轮同值，属既有现象，本轮只记录。

## 8. 复现命令（可原样重跑）

```bash
# 构建（先确认无实例持 target jar：pgrep -af java | grep -i simos）
tools/mvn-lock.sh -DskipTests package
find . -name '*.java' -newer simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar | wc -l   # ⇒ 0

# P-1 + P-2 + 多区 + 性能（一次一个实例；独立 store 与端口）
bash /tmp/b5v-chain.sh

# 成交（TRACE 档；B2V_KEEP_JTO=1 保留 JAVA_TOOL_OPTIONS）
JAVA_TOOL_OPTIONS="-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE" B2V_KEEP_JTO=1 \
  B2V_STORE=/tmp/simos-b5v-trl python3 /tmp/b2v-run.py trl 10471 10472 10473 small-world \
  simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar 0:360

# 读数
python3 /tmp/b2v-agg.py    /tmp/b2v-state-s1.json /tmp/b2v-state-tp360.json
python3 /tmp/b2v-stress.py /tmp/b2v-state-s1.json 2 8
python3 /tmp/a8-diff.py    /tmp/b2v-state-t1.json /tmp/b2v-state-t2.json
cmp /tmp/b2v-state-s1.json /tmp/b2v-state-s360.json && echo "逐字节相同"

# B3 旧档边界（负向验证）：B2 轮留下的 day-240 旧 store 用新 jar 续跑 ⇒ 仍被 E14 拒
B2V_KEEP_STORE=1 B2V_STORE=/tmp/simos-b5v-oldstore B2V_DUMP=/tmp/b2v-dump2.py \
  python3 /tmp/b2v-run.py oldstore 10481 10482 10483 three-powers \
  simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar 240:360
```

**产物**：dump/计时 `/tmp/b2v-state-{t1,t2,v60,s1,s3,s12,s360,tp360,tp3seg,perfn1,perfn2,perfb1,perfb2,trl,oldstore}.json`、
`/tmp/b2v-time-*.json`、日志 `/tmp/b2v-run{tag}.log`、每段回执 `/tmp/b2v-adv-*.txt`、store `/tmp/simos-b5v-*`（旧轮产物另存 `/tmp/b5v-old/`）。
