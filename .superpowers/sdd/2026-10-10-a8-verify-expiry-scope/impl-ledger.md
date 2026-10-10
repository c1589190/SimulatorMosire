# A8 真实 world 验收账本 —— A7（作废判据扩到"任何持有 haul 的家户"）：A6 未成立的"世界总现货按期归零（字面）"

> 角色：**验证 Agent**（起独立世界 / 按 store 路径独占快照 / 只读日志 / **未改任何代码、测试、文档、pom**；写盘仅本账本与 `/tmp/a8-*`）。
> 日期：2026-10-10。判据来源：任务书六条 + A6 账本 `.superpowers/sdd/2026-10-10-a6-verify-cycle-expiry/impl-ledger.md`（其 §1.2 逃逸口 = 本轮要关的那条）
> + A7 账本 `.superpowers/sdd/2026-10-10-a7-expiry-scope/impl-ledger.md`（D-A7-1/2/3、C-1..C-6）
> + 约束设计书 `docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` **v1.5**（§3.1 / §5 I-H1~I-H7 / §6 T-H3,N-H3 / §9 修订记录①②）。
> HEAD：`7ce800ac61f52ed5735b555286665a2559180dd5`（A7），跑前跑后 `git status --porcelain` 仅本账本（未 `git commit`）。
> ★ 一句话结论：**A6 唯一没成立的那条（"世界总现货按期归零"字面）现在成立**——四个周期边界（120/240/360/480）每一处
> **陈旧存量 = 0**，且 `expired(T) == 世界现货(T−1)` 逐边界成立（边界前任何人手里的货一律销毁）；逐日对账 **0 违反**（B 479 检 / A 349 检，状态 dump 收口）；
> 同路径对照下 A7 对状态的影响**只有 haul 存量**（A 世界逐字节不变；B 世界 360=2 叶 / 480=6 叶，全是 haul）。

## 0. 装置与 jar 身份

| 项 | 值 |
|---|---|
| 跑前实例检查 | `pgrep -af java \| grep -i simos` = 仅 pid **2081**，`/proc/2081/fd/4` → `/tmp/simos-shaded-NwvHbq.jar`（**非** target）⇒ **无实例持有 target jar**，未停它、未覆盖任何在跑服务/共享 jar |
| 被测 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 **`f5e824871d84131ee62e18cfeb8929fd`**，27,043,397 B，mtime `2026-10-10 18:54:56` |
| 构建 | `MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package` → **BUILD SUCCESS 16/16**（4.018 s）；构建前后 md5 **不变**；跑前 18:53:37 的 `verify16` 在同一 HEAD 产出**同 md5** ⇒ 两次独立构建同字节 |
| jar↔源码 | `find . -name '*.java' -newer <jar>` = **0**（14 模块）；A7 标记 `all-haul-holders` 在 `EconomySettlement.class` 的 strings 里命中 1 ⇒ jar 字节 = HEAD 源码产物 |
| 运行期字节自证 | 每个 run 的独占快照 md5 全 = `f5e82487…`（A `/tmp/simos-shaded-2Lx26s.jar`、B `/tmp/simos-shaded-r8AAhQ.jar`） |
| 装置 A1（逐日） | 原生多市场区（`a8-setup1/2` = a6 版 sed 到 **9815**）：0→5（rev 11）→ setup2 → 5→10（rev 20）→ **逐日 10→360**（rev 21..370，350 次 advance）；store `/tmp/simos-a8-store-a`；端口 GUI 9811 / MCP 9815 / 审批 9813；日志 `a8-runA.log` = 155,861,683 B / 442,653 行；`IllegalStateException/ERROR/Exception/at_io_mosire = 0/0/0/0`、WARN **2,001** |
| 装置 A2（同路径对照） | 同 A1 装置但 **10→360 一次推进**（与 A6-A 完全同路径）→ rev **21**（同 A6）；store `/tmp/simos-a8-store-a2`；端口 9821/9825/9823；日志 160,902,597 B / 454,433 行；WARN **2,022**（= A6-A）；dump `/tmp/a8-state-a2-360.json` |
| 装置 B1（逐日） | 缺省 `--world=small-world`，**逐日 0→480**（480 次 advance，rev 2..481）；store `/tmp/simos-a8-store-b`；端口 9711/9715/9713；日志 200,984,031 B / 534,863 行；`0/0/0/0`、WARN 23；总耗时 551 s（1.15 s/日）；dump 落在 119/120/239/240/359/360/479/480 |
| 装置 B2（同路径对照） | **单段 0→360**（与 A6-B 完全同路径）→ rev **2**（同 A6）；store `/tmp/simos-a8-store-b2`；端口 9721/9725/9723；日志 139,184,726 B / 378,062 行 |
| 装置 B2-ext | 复用 B2 store → **360→480** → rev 3；日志 45,768,045 B / 123,082 行；dump `/tmp/a8-state-b2-480.json`（与 A6 的 `a6-extB.sh` 同路径） |
| 装置 B3/B4（路径效应） | 0→120→240→360 **三段**、中途除 advance 外零读；端口 9731/9735/9743 与 9741/9745/9743；日志各 140,303,445 B / 379,939 行 |
| 日志档位 | 全部世界 `-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE`（与 A4/A6 同档） |
| 纪律 | 一次一个 Maven、一次一个实例（B1 → B2 → B2-ext → A1 → A2 → B3 → B4，全程串行）；跑完 `pgrep -af java` = 仅他人 pid 2081 |

## 1. ★ 核心判据（A6 未成立的那条）—— **陈旧存量 = 0，成立**

### 1.1 B 逐日路径（**状态 dump 收口**：每天读满 19 格 `simos.economy.hex`，`Σgoods.haul` = 世界现货）

| 边界 T | 现货(T−1) | expired(T) | produced(T) | delivered(T) | 现货(T) | **陈旧 = 现货(T)−(produced−delivered)** |
|---|---|---|---|---|---|---|
| **120** | 0 | 0 | 194,000 | 8,227 | 185,773 | **0** |
| **240** | 1 | 1 | 194,000 | 1,163 | 192,837 | **0** |
| **360** | 109,935 | **109,935** | 194,000 | 1,317 | 192,683 | **0** |
| **480** | 153,591 | **153,591** | 97,000 | 0 | 97,000 | **0** |

- `expired(T) == 现货(T−1)` **四个边界全部成立**（="边界前任何人手里的货一律销毁"，这正是 A6 缺的那半）。
- 边界收盘时的持货户（dump 逐户）：
  - 120：`hh-0_0-urban-landlord` 88,773 + `hh-0_2-urban-landlord` 97,000（**只有产者**）
  - 240：`hh-0_0-urban-landlord` 191,919 + `hh-0_0-urban-rich_peasant` 918
  - 360：`hh--2_0-rural-landlord` 11,999 / `hh--2_1-rural-landlord` 62,499 / `hh-0_0-urban-landless_laborer-displaced` 22,448 / `hh-0_0-urban-landlord` 95,737
  - 480：`hh-0_0-urban-landlord` 97,000（**仅产者，且恰 = 当日净产**）
- 360/480 边界上的非产者持货是**当天新产经 `loan_repayment` 当日转出**（仍是本周期新货），并在**下一个边界被同批销毁**
  （DETAIL：`hh--2_0` 11,999 / `hh--2_1` 62,499 / `hh-0_0-...-displaced` 22,448 / `hh-0_0-urban-landlord` 56,645）；
  ⇒ 任何时刻池里能卖的只可能是本周期产出（A6 的"卖不掉只是巧合"这一层不再需要依赖）。
- 三次作废总量（`market-haul-service-expired`）：**1 + 109,935 + 153,591 = 263,527**（与 §2 的 Σexpired 逐值同源）。

### 1.2 B 单段路径（与 A6 **逐字节可比**，A6 的逃逸口就在这条路径上）

| tick | A6（A5 码） | A7 | Δ | A6 点名的逃逸持货户在 A7 |
|---|---|---|---|---|
| 360 | 世界 **198,447**（0_0 = 101,487 = 97,000 新 + **5,249 陈**） | 世界 **193,198**（0_0 = 96,238） | −5,249 | `hh-0_0-urban-poor_peasant` **0** |
| 480 | 世界 **199,209**（-1_0 = 50,961、-1_1 = 45,999、0_0 = 102,249） | 世界 **97,000**（全在 0_0 产者手上） | −102,209 | `hh--1_0-rural-landlord` **0** / `hh--1_1` **0** / `poor_peasant` **0** |

- 同路径 dump 逐叶差异：**360 = 2 叶**（`0_0.classes[11].goods.haul` 5,249→absent、`0_0.goods.haul` 101,487→96,238）；
  **480 = 6 叶**（-1_0/-1_1/0_0 三个 haul 键 + 三个格级 `goods.haul`）；`__gov` 差异 **0**；其余货币/人口/粮/债/价格/产业**零变化** ⇒ **只落在服务存量**。
- 480 边界作废日志：A6 `households=1 expiredMilli=44,358` → A7 `households=3 triggerUnits=2 holders=3 scope=all-haul-holders expiredMilli=141,318`（= 44,358 + 96,960）。

### 1.3 A 世界（原生多区）逐日路径

| 边界 T | 现货(T−1) | expired(T) | produced(T) | delivered(T) | 现货(T) | 陈旧 |
|---|---|---|---|---|---|---|
| 120 | 0 | 0 | 127,070 | 45,075 | 81,995 | **0** |
| 240 | 65,082 | 65,082 | 131,920 | 0 | 131,920 | **0** |
| 360 | 103,687 | 103,687 | 102,820 | 47 | 102,773 | **0** |

持货户恒为两个产者（`hh-0_0-urban-landlord` / `hh-0_2-urban-landlord`），480 天内 haul 实物转移腿 **0** 条（与 A6-A 实测同）⇒ A7 在 A 世界是**结构性 no-op**。

## 2. 逐日对账（Σ净产 − Σ交付 − Σ作废 == 现货；**状态 dump 收口**，判据 0）

恒等式 `现货(d) == 现货(d−1) + produced(d) − delivered(d) − expired(d)`：

| 世界 | 检查天数 | **违反** | 区间累计式 | 覆盖自证 |
|---|---|---|---|---|
| **B（逐日 0→480）** | **479** | **0** | 1→120 / 120→240 / 240→360 / 360→480 **4/4 OK** | 20 个"全读对照日"候选集 Σ == 全读 Σ（差 0） |
| **A（逐日 10→360）** | **349** | **0** | 11→120 / 120→240 / 240→360 **3/3 OK** | 15 个全读对照日 差 0 |

- 三量同源自核（B/A 均）：`SETTLED.produced/delivered/expired` 与 `HAUL_SERVICE_PRODUCED`、`HAUL_SERVICE_DELIVERED` 逐腿、`HAUL_SERVICE_EXPIRED` **不一致日 = 0**；
  Σ `SETTLED.delivered` == Σ 逐腿 = **318,473**（B 逐日）/ **90,268**（A 逐日）⇒ 漏报 0。
- **对照（A6 用的池名册口径）**：B 逐日路径仍有 **2 条假违反**（day 361 `Σcap=95,737` vs 现货 192,683；day 480 `Σcap=97,000` vs 54）
  ⇒ 坐实 A6 §1.4 / spec v1.5 修订②的警告：**池读数不是逐日现货的完备见证**；收口必须用状态 dump。
- 全额：B 逐日 Σproduced **679,000**、Σdelivered **318,473**、Σexpired **263,527**，480 日末现货 **97,000**（= 679,000 − 318,473 − 263,527 ✓）。

## 3. 日志（`HAUL_SERVICE_SETTLED` 每个服务成市日一条 + 新字段自解释性）

| 项 | B2（单段 0→360） | B2-ext | A2（同路径） | B1（逐日 0→480） | A1（逐日） |
|---|---|---|---|---|---|
| 服务成市日 = 运力池日 = SETTLED 日集 | **81 / 81 / 81，差集空** | 28 / 28 / 28 | 132 / 132 / 132 | 109 / 109 / 109 | 131 / 131 / 131 |
| 与 A6 同日集 | **是**（逐日差集 ∅） | **是** | **是** | ——（不同路径） | —— |
| 含 `trades=0` 的行 | （计数见下） | | | 44 行（其中三量全 0 = 33） | 104 行（103 行三量全 0） |

- **`holders` / `scope` 自解释性（判据问的那条）**：
  - `scope=all-haul-holders` **自解释**（直接点名清扫集合），`triggerUnits` 取代 `closingUnits` 后与 scope 并读不再误导
    （A6 日志 `closingUnits=2`，A7 日志 `triggerUnits=2` ⇒ D-A7-1 的"旧解析脚本取不到值"**已实测为真**）。
  - ★ **`holders`（持货户数）与 `households`（真被扣减的户数）同时出现，但对不读代码的人不自解释**；
    本轮 5 次作废里两者**恒等**（240: 1/1、360: 1/1 或 2/2、480: 4/4 或 3/3）——差异只在"某持货户 available≤0（全冻结）"时出现，
    而 `frozenMilli` 全轮**恒 0** ⇒ 该分支**无样本**（同 A6 §7-5）。建议（不改）：把两计数改成可区分的名字或用一条 note 说明。
  - `HAUL_SERVICE_EXPIRED_DETAIL` 去掉 `unit`/`industry`、加 `scope` 后与新口径一致（逐户 `expiredMilli`/`stockBeforeMilli`/`frozenMilli` 齐备）。

## 4. 回归（全部**同路径**对照，避免 §5-1 的路径效应污染）

| 项 | A：A6-A → A7-A2（同路径） | B：A6-B → A7-B2（同路径） |
|---|---|---|
| 状态 dump | **md5 逐字节相同** `3f71ca7f83c416d86275fef126f5d3b0` | 差 2 叶（全是 haul 存量，见 §1.2） |
| 事件计数 | **207 种 Δ 全 0**（含 HAUL_* 132/3/2/3/145/161、MARKET_FILL 1,669、MARKET_CREDIT_FILL 2,878） | **165 种 Δ 全 0**（唯一多 `GUI_ACCESS` = 我的 dump 的 HTTP 调用）；HAUL_DELIVERED 141 / PAID 121 / SETTLED 81 同 |
| rev / committed | 11 / 20 / **21** 同；全部 `committed` | rev **2** 同；ext rev 3；全部 `committed` |
| 异常 | `IllegalStateException/ERROR/Exception/at io.mosire` = **0/0/0/0**；WARN **2,022**（= A6-A，构成同） | 0/0/0/0；WARN 23 |
| 人口Σ / 粮Σ | 4,927 / 1,090,540,229（= A4/A6 逐值） | 4,928 / 3,848,788,464（= A4/A6 逐值） |
| 债 / 信用 | 4,469,636 · 1,933；`credit==debt`；`Σ(byUnit)==标量` 38/38 | 18,596 · 1,064；同上 |
| 货币守恒 | `circulation==baseMoney` True（copper 100,000 / silver 273,200 / token 5,000） | True（copper / silver） |
| 服务实测 | Σ服务交付 134,783（同 A6）；作废 25,434 + 100,713 = **126,147**（同 A6） | Σ服务交付 274,012（同 A6）；作废 **256,108**（114,790 + 141,318；A6 = 153,899） |

★ **A7 的影响面 = 只有服务存量 + 损耗账户**：A 世界 dump **0 叶**；B 世界 dump 360 = 2 叶、480 = 6 叶且全是 `haul` 键；
`__gov` 0 叶；损耗账户 `market-haul-service-expired` 的等额增量只出现在日志面（dump 不含 loss 账）。

## 5. 新发现（只报不改）

1. ★★ **`simos.advance` 分段推进不路径无关**（同码=A7、同世界=small-world、fresh store 对照）：
   - 1 段（0→360）vs 3 段（0→120→240→360）：tick-360 dump 差 **18,538 叶**；1 段 vs 360 段（B1）差 **24,456 叶**；3 段 vs 360 段差 25,538 叶。
   - 日志行 378,040（1 段）vs 379,917（3 段）；`CALENDAR_CLOCK_BOUND` 2,839 → 2,970、`DEBT_REPAY_DECISION` 3,012 → 3,161、
     `TRANSFER` 23,955 → 24,225、`MARKET_FILL` 6,937 → 6,974、`PRODUCTION_EFFICIENCY_TICK` 57,960 → 58,527。
   - **不是随机性**：3 段跑两遍（B3/B4，各自 fresh store）dump **byte 相同**（md5 `845fd183b2d266d95dd6546f87e9dcbf`）、日志同为 140,303,445 B / 379,939 行。
   - 影响：A4/A6 的"B 世界单段 0→360"是一个**与逐日推进不同的世界**（债务 `openedDay` 指纹 241/255/… vs 5/15/40）。
     ⇒ 本轮所有 A6↔A7 差值都在**同路径**上测，结论不受影响；但文档里"路径无关（M0.1/A4）"的说法需要重验。
   - 未做 bisect ⇒ **不能判定**它是 HEAD 既有还是新近引入（两跑都用 A7 码，故可判定**与 A7 无关**）。
2. `holders`/`households` 双计数**无差异样本**（见 §3），冻结分支仍未被任何真实世界触发。
3. A6 §6-1 的缓解事实（"陈货落在不可转卖的户手里"）**不普适**：A6 自己 day-180 的 `loan_repayment` 把 88,513 转给了
   `hh-0_0-urban-rich_peasant`，该户在 **480/480 天**都有 `MERCHANT_CAPACITY_HOUSEHOLD` 行（是商家候选）⇒ 落到池内户的陈货**本来就能再卖**；
   期末那 102,209 恰好落在 0 池行的三户只是**这一次的巧合**（B1 实测：期末三个非产者持货户 0 池行、`rich_peasant` 480/480 行）。
4. `HAUL_SERVICE_SETTLED` 日集 == 服务成市日集仍**无断言守卫**（同 A6 §6-4；本轮三份同路径日志恰好同集，是实测不是结构保证）。

## 6. 未验证 / 构造不出（如实）

1. **本轮未跑 `test`/`verify`/SpotBugs/前端门禁**（只做真实 world）。跑前 18:53:56 有人在 HEAD 跑过全仓 `verify`
   （`/tmp/verify16.log`：BUILD SUCCESS 5:09、app 910 条 / 0 失败 / 5 跳过）——**那不是本轮证据，未引用其数字**，只用作 jar 身份的旁证。
2. **未做变异自证**（本轮零代码改动）；判据强度靠"两条路径 + 同路径逐字节对照 + B3/B4 两跑同字节"提供。
3. **未构造 `frozen > 0`**：四个世界的服务冻结量恒 0 ⇒ "仅剩冻结量"与 `holders≠households` 两个分支无样本。
4. 未跑 `three-powers` / `corridor` / `v17levant` / 3600 tick / GM-FX 补丁世界。
5. **未构造"持货户被选回跑商 ⇒ 陈货被卖掉"**：B1 的 480 天里期末三个非产者持货户 0 池行；§5-3 给出了该场景**可达**的反例（池内户确实能拿到服务货）。
6. **未 bisect §5-1 路径效应的引入点**（是 HEAD 既有还是新近引入，判定不了；只判定与 A7 无关）。
7. 未做完整 N-H3（同 store 同参数两跑）：只做了"3 段路径两跑逐字节相同"；单段路径用 A6 日志逐事件计数对照，非两跑。

## 7. 复现命令

```bash
cd /home/cna/SimulatorMosire
pgrep -af java | grep -i simos            # 只应有他人 pid 2081（jar 在 /tmp，非 target）
MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar     # f5e824871d84131ee62e18cfeb8929fd
find . -name '*.java' -newer simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar | wc -l   # 0
bash /tmp/a8-runB.sh    ; bash /tmp/a8-kill.sh    # B 逐日 0→480（9711/9715/9713, /tmp/simos-a8-store-b）→ /tmp/a8-b-daily.jsonl + 边界 dump
bash /tmp/a8-runA.sh    ; bash /tmp/a8-kill.sh    # A 逐日 10→360（9811/9815/9813, /tmp/simos-a8-store-a）
bash /tmp/a8-runA2.sh   ; bash /tmp/a8-kill.sh    # A 同 A6 路径（10→360 一次推进）→ /tmp/a8-state-a2-360.json
bash /tmp/a8-runB2.sh   ; bash /tmp/a8-runB2ext.sh ; bash /tmp/a8-kill.sh   # B 单段 0→360 + 360→480（与 A6 逐字节可比）
bash /tmp/a8-runB3.sh   ; bash /tmp/a8-runB4.sh   # 3 段跑两跑（路径效应/确定性）
python3 /tmp/a8-recon.py /tmp/a8-b-daily.jsonl /tmp/a8-runB.log 120,240,360,480 "B"
python3 /tmp/a8-recon.py /tmp/a8-a-daily.jsonl /tmp/a8-runA.log 120,240,360 "A"
python3 /tmp/a8-diff.py /tmp/a6-state-a.json /tmp/a8-state-a2-360.json       # A 同路径：0 叶
python3 /tmp/a8-diff.py /tmp/a6-state-b480.json /tmp/a8-state-b2-480.json    # B 同路径 480：6 叶（全 haul）
python3 /tmp/a8-events.py /tmp/a6-runA.log /tmp/a8-runA2.log                 # 207 种事件 Δ 全 0
```

**产物**：日志 `/tmp/a8-run{A,A2,B,B2,B2-ext,B3,B4}.log`；读数 `/tmp/a8-{a,b}-daily.jsonl`、`/tmp/a8-{b,a}-dump-*.json`、
`/tmp/a8-state-{a2-360,b2-360,b2-480,b3-360,b4-360}.json`；store `/tmp/simos-a8-store-{a,a2,b,b2,b3,b4}`；
脚本 `/tmp/a8-*.sh`、`/tmp/a8-orch.py`、`/tmp/a8-recon.py`、`/tmp/a8-diff.py`、`/tmp/a8-events.py`。
