# B3 实现账本 —— 修 `BAD_REQUEST / E14`：three-powers（原生多区）卡在 day 270

> **写码 Agent**（责任区 = E14 回归的生产代码 + 只到编译门禁）。日期 2026-10-10。起点 HEAD = `b1b112b9`（= B2 `1f7d656c` + SpotBugs 清理）。
> 写入范围：`simos-economy/src/main/**`（本批只用到这一处；`simos-app/src/main/**` 未改）。**未** `git commit`、**未**跑 test、**未**跑 world。
> 现场账本：`.superpowers/sdd/2026-10-10-b2-verify-path-equality/impl-ledger.md` §5.2 / §6-1。

---

## 1. 诊断（全部 file:line + 实跑产物）

### 1.1 E14 在哪、比较哪两个量

| 项 | 落点 | 事实 |
|---|---|---|
| 守卫 | `ProductionSettlement.java:468-488`（`requireProducibleCommodities`），调用点 `:243`（**任何数量计算之前**） | 逐条实物规则判 `facts.outputPerUnit().containsKey(rule.commodity())`；不命中 ⇒ 抛（E14 文案 `:476-486`） |
| 左值 = 规则商品 | `rule.commodity()`，规则来自 `EconomySettlement.java:8353` 的 `relations.get(unit.id())`（**关系表的持久内容**） | 失败那条 = `OUTPUT_SHARE / GROSS_OUTPUT / NONE / 300‰ / cloth / priority 10 / recipient=ToActor[HOUSEHOLD:hh-3_0-rural-poor_peasant]`（原文见 `/tmp/b2v-adv-tp360-0-360.txt`、`/tmp/b2v-adv-tp3seg-240-360.txt`） |
| 右值 = 产业产出表 | `EconomySettlement.java:8376`：`Facts.outputPerUnit ← industry.outputPerUnit()`，`industry = industries.get(unit.industry())` | 该 unit = `unit-trade@3_0-…` ⇒ `trade@3_0` 的产出 = `{haul:1}`（A1 起）⇒ **不命中 cloth** |

⇒ E14 比较的是**「关系规则里写的商品」vs「该 unit 那条产业的产出表」**，与现场判断一致。

### 1.2 那条 cloth 规则是谁造的（唯一生产者）

- 形状唯一匹配 **租金腿**（`RentRule.RentLeg` 的 SHARE 腿）：`EconomyEnterpriseSettlement.java:1091 rentRules(...)` — `SHARE → OUTPUT_SHARE × GROSS_OUTPUT × leg.ratePerMille × leg.commodity`，受方 = `new Payee.ToActor(grant.owner())`（`:673`，故打印成 `ToActor[HOUSEHOLD:hh-…]`）。
- 商品来自**资产规则常量**：`EconomySeeder.java:3875-3876 productionRuntimeRentRuleNode`：`LAND → grain，其余（TOOL/WORKSHOP/SHIP/CATTLE）→ cloth`，率 `:3840 = 300‰`，priority 10 ⇒ 与失败规则的五个字段**逐值吻合**。
- 触发链：`organizeOne` 路由② → `planTenancy`（`:866-937`，出租资产取自 `industry.capacityPerUnit()`）→ trade 的产能资产 = `CATTLE`（`EconomySeeder.java:4340`）⇒ `(mode=merchant × CATTLE)` 的租金模板 = **布**（`:3876`）⇒ 关系里落一条 `cloth` 规则；而该 unit 的产出表 = `{haul}`（`:4344`）⇒ E14。
- **排除法**：全仓 `OUTPUT_SHARE + GROSS_OUTPUT` 的生产者只有三处 —— `RegimeRelations.feudalRules()`（粮）、`EconomySeeder.outputShareRule`（受方=`plan.operator()`，即 `ORGANIZATION:craft@hex`，与现场受方是**家户**不符）、`EconomyEnterpriseSettlement.rentRules`（受方=`grant.owner()`，家户）⇒ **只剩租金腿这一处**。

### 1.3 实测归因：B2 让这条租佃真的发生（而不是 B2 读错某个量）

同一台装置、同一世界（`--world=three-powers`，37 格）、同一 jar 形状，只换代码：

| 产物 | 证据 |
|---|---|
| B2 失败 | `/tmp/b2v-adv-tp360-0-360.txt` day 270 被拒、rev `1→1` |
| **B2：hex 3_0 有那条租佃** | `/tmp/b2v-state-tp269.json`（day 269）与 `/tmp/b2v-state-tp3seg.json`（day 240）里 `assetShares` 含 **TENANCY CATTLE**：`share-trade@3_0-CATTLE-…-hh-3_0-rural-poor_peasant-…-hh-3_0-urban-rich_peasant-TENANCY-0`（qty 5）—— owner = **失败规则的受方**、operator = **失败 unit 的经营者**，逐字段对上；同格另有 `unit-trade@3_0-HOUSEHOLD-hh-3_0-urban-rich_peasant` 这个 unit（信用/债务读数里出现 4 次） |
| **老码：同一个 hex 没有这条租佃** | `b2v-state-tp360base.json`（基线 `7ce800ac`）与 `b2v-state-tp360b1.json`（B1 `de16fe13`）day 360 的 `assetShares` 里 CATTLE **只有 1 行**（landlord 自持 80）、**零条 TENANCY**；三个城格都只有 `unit-trade@<hex>-…-urban-landlord`（种子那个），**没有 rich_peasant 的 trade unit** |
| 关账日算术 | `HAUL_SERVICE_EXPIRED`：B2 day 240（种子 unit，trig=3）与 **day 270（新建 unit，trig=3）**；基线只在 day 360（trig=3）⇒ 新建的那 3 条 trade unit 的**第一个关账日恰是 day 270** = 拒绝日 |
| 首个状态分叉日 | `DAY_START` 摘要逐日对比：**day 62** 起 `organizations` 分叉（B2 160 / 老码 126）；其因是 **day 60 的 `PRIMARY_MODE_CHANGED` 条数 6 vs 43**（B2 少迁 37 户）⇒ 资产/闲置池不同 ⇒ 到 day 150 那批迁移后，B2 多出上面那条 CATTLE 租佃 |

### 1.4 判定：**A1 遗留的数据不一致被 B2 暴露**（不是 B2 读错来源）

- A1（`a15fb3ec`）把 `trade@hex` 的产出从**空表**改成 `{haul:1}`（`EconomySeeder.java:4344`），但没动"非 LAND 资产 ⇒ 分成布"这条**按资产种类的出厂标定**（`:3876`）。
- A1 之前该产业无产出 ⇒ `harvest` 的 `netByCommodity.isEmpty() ⇒ return`（`EconomySettlement.java:8350-8352`，紧接 `:8353` 的 `relations.get(unit.id())`）**当场返回** ⇒ 这条 cloth 规则**从来没有被结算过** ⇒ E14 不可达。A1 之后同一个 unit 走同一套净产/关系结算，E14 于是可达。
- 因此"哪一天爆"由**何时真发生一笔 CATTLE 租佃**决定，而那由 B2 改写的逐日行为（迁移/择业读当刻状态）决定 ⇒ **B2 是暴露者，不是读错者**（B2 的当日视图语义本身未破；本批一字未改 `EconomyDayView`/`WorkingDayView`）。

---

## 2. 实现架构（改了什么）

**落点**：`EconomyEnterpriseSettlement`（租金腿 → 结算规则的唯一拼写点）。

```
organizeOne 路由②（`:656-674`）
  plans grants → rentGroups(grants)
    → rentRules(rentRule, ToActor(grant.owner()), industry, mode)        ← B3：新收 industry/mode 两口
        → rentCommodity(leg, industry, mode, recipient)                   ← B3：新增（商品的唯一判据）
```

`rentCommodity` 三条分支（**都判在 `applyGrants/units.put/relations.put` 之前**，故不会半建）：

1. 腿上的商品**本产业能产** ⇒ 原样（**既有世界逐值不变**：农田→粮、织机→布、作坊→布）；
2. 不能产 + 是**分成腿** + 该产业**恰有一个产出商品** ⇒ 把分成记在**它自己的产出**上（trade 的 CATTLE 租佃 ⇒ `cloth → haul`），并记 DEBUG `RENT_COMMODITY_FROM_INDUSTRY_OUTPUT`；
3. 其余（固定实物租的商品不能产 / 多产出产业判不出）⇒ 记 INFO `RENT_COMMODITY_NOT_PRODUCED_REFUSED` + 抛 `IllegalArgumentException`：`organize` 的既有 catch（`:411-424`）把它落成**具名 SHORTAGE 组织行、不建 unit、不落非法关系**（`REASON_REFUSED:<原因>`）。

**为什么这是"正确的来源"**：租金腿的语义（`RentRule`/`RentLeg` 类注）= "该 unit **毛产的** ratePerMille‰"，结算侧就是 `OUTPUT_SHARE × GROSS_OUTPUT`；而"毛产"是**这一个产业**的产出 —— 这正是 E14 用的那份权威。资产规则是 `(mode × 资产种类)` 的常量表、**看不见产业**，"非 LAND ⇒ 布"只对"非 LAND 的租佃只发生在产布的手工业"成立。修法把两条权威收敛到**产业产出表**一处（而不是给资产规则再加一份"按产业"的表、也不是放宽 E14）。

**日志**：新增来源 `EconomyLogSource.ECONOMY_ORGANIZATION_RENT`（`economy-organization-rent`，`LogOriginKind.SYSTEM`）—— `rentRules` 方法签名**无 day 上下文**，按本表既有纪律（`ECONOMY_POPULATION_WRITE`/`ECONOMY_DEBT_STATE` 同款）归 system；走 `EconomyLog.enterprise()`（`.organization`）。

**未新增/未改任何持久状态组件**（无新表、无 codec/ChangeSet 变化）；**无新写口**；`rentCommodity` 是**纯函数**（只读 `industry.outputPerUnit()` + leg），确定性 I7 不破；一次 advance 一次落盘不变（改动全在组织阶段，不碰 finish/codec）。

---

## 3. 关键判断（含推翻过的路线）

1. **不改 E14、不限缩 0 付**：E14 是"静默付 0"的对冲，放宽它等于把坏数据变成静默不付 —— 拒。
2. **不在 `simos-app` 改资产规则常量**：`(mode × assetKind)` 表**结构上**看不见产业 ⇒ 在那一层无解（改常量只会把这个产业的错挪到另一个产业）。
3. **不做 load 期就地"修数据"**：已落盘的旧关系不在本批修复范围（用户裁定 §一.11「旧档按重建处理」）；且 read 路径改状态会破坏"读口只读"。⇒ 只修**生产者**（关系首次合成的唯一处）。
4. **"拒绝租佃" vs "换商品"**：分成腿的池是毛产 ⇒ 换成"本产业唯一产出商品"是**语义扩张为零**的读法（trade 的毛产只有 haul，300‰ 的毛产分成只能记在 haul 上）；判不出时（多产出/固定实物租）才拒 —— 比落一条下次关账整段被拒的非法关系好。
5. 排除过的另一条解释（**B2 把 `outputQuantityOverrides`/`industries()` 读成当刻**）：实测该处关系里的商品与 `outputQuantityOverrides` 无关（它只覆盖**数量**、键必须已在配方里，`EconomyCodec.java:752-767` 有 fail-closed 守卫），且失败规则的商品 `cloth` 在 `trade@3_0` 的配方键里**根本不存在** ⇒ 与"当刻/段首"无关。

---

## 4. 偏离记录

- 约束设计书（总计划 §3）未给本次修复的实施细节；本账本的"三条分支"是本人设计，**待控制方/用户裁定**：若认为"分成腿换商品"应改为"一律拒绝租佃"，改的是 `rentCommodity` 的第 2 条分支（一处，行为差异：merchant 家户在有闲置 CATTLE 时能否建 trade unit）。
- 未改 `simos-app`：`EconomySeeder` 的 rent 常量**保持原样**（它是一张按 (mode, asset) 的静态表，本批不动其标定）。

---

## 5. 命令与结果（本批实跑，逐条）

```bash
# ✓ 1) 格式
tools/mvn-lock.sh -q spotless:apply                        # rc=0
# ✓ 2) 编译 + SpotBugs（顺带证 0 债；test 源也编译了 —— testCompile 出现在 8 个上游+economy）
tools/mvn-lock.sh -DskipTests verify -pl simos-economy -am  # BUILD SUCCESS（37s）；economy BugInstance size is 0
# ✓ 3) 复核最终形态（改注释后重跑一次）
tools/mvn-lock.sh -q spotless:apply                        # rc=0
tools/mvn-lock.sh -DskipTests verify -pl simos-economy -am  # BUILD SUCCESS；BugInstance size is 0
```

**改动的文件（2 个，全在 `simos-economy/src/main`）**

| 文件 | 落点 |
|---|---|
| `economy/EconomyLogSource.java` | 新增 `ECONOMY_ORGANIZATION_RENT`（SYSTEM，`economy-organization-rent`） |
| `economy/time/EconomyEnterpriseSettlement.java` | ① `:673` 调用点传 `industry, mode`；② `rentRules(...)`（`:1091`）实物腿商品改由 `rentCommodity` 定；③ 新增 `rentCommodity(...)`（`:1172` 起；判据 + DEBUG `:1185` / INFO `:1204` 两条日志 + fail-closed 抛）；④ 4 个 import |

---

## 5.1 身份（自指：本轮落盘的字节）

| 文件 | md5 |
|---|---|
| `economy/time/EconomyEnterpriseSettlement.java` | `76636003e8dc1f1bef6f5cb4f4691cd9`（69,563 B） |
| `economy/EconomyLogSource.java` | `832a11c56cead28f2d1baaec2966e688`（8,463 B） |
| 本账本自身 | 不写自指 md5（写它就会改变它）—— 收账时由控制方 `md5sum` 计算即可；上两行才是"这一轮跑的是哪份字节"的判据 |

★ 最后一次 `verify` 的产物时间（jar 20:56:48 / class 20:56:46）**晚于**两个源文件的 mtime（20:53:39 / 20:56:21）⇒ 上面那次 BUILD SUCCESS 覆盖的就是这两份字节。

## 6. 会改数值行为的清单（给测试代理当输入）

1. **只在"腿商品 ∉ 产业产出表"时生效**：既有合法组合（农田→粮 / 织机→布 / 作坊→布）**逐值不变**（`outputs.containsKey(declared)` 原样返回）。
2. 生效面 = **trade@hex 的 CATTLE 租佃**：关系商品 `cloth → haul` ⇒ 这些 unit 的 `GROSS_OUTPUT` 分成**以 haul 结算**（受方、率 300‰、priority 10 不变）；`ExpectedProfitBook.obligationsOf` 的租金估算随之从"布价"改"服务价"⇒ 会影响以该估算为输入的**迁移/择业排序**（只在存在这种租佃的世界里）。
3. 新增拒因：`REASON_REFUSED:租金腿的商品不在该产业的产出表里…` 的 SHORTAGE 组织行（原先是"建 unit ⇒ 下次关账整段被拒"）。
4. 新增两条日志事件：`RENT_COMMODITY_FROM_INDUSTRY_OUTPUT`（DEBUG）、`RENT_COMMODITY_NOT_PRODUCED_REFUSED`（INFO）。
5. **不改**：`EconomyDayView`/`WorkingDayView`、E14 本身、任何持久状态组件、任何写口、`simos-app`。

**可能受影响的既有测试断言**（未跑 test，按静态判断列出）：任何断言"租佃关系里 SHARE 腿商品恒等于资产规则商品"的用例（若有，本批后 trade/CATTLE 组合会不同）；`EconomyCycleHealthTest`（真三国 240 天）与 three-powers 相关端到端用例的**数值读数**（如果它们的世界里出现过这种租佃）；日志断言若按"事件全集"匹配，会多出上面两条事件名。

---

## 7. 未完成 / 未验证（如实）

1. ★★ **three-powers `0→360` 能否 committed 未实测**（本批按纪律不跑 world，验收轮归控制方）。**可复现的命令**（沿用验收账本 §8 的装置）：
   `B2V_DUMP=/tmp/b2v-dump2.py python3 /tmp/b2v-run.py tp360 <ports> three-powers <jar> 0:360`，期望 `"result":"committed"`、rev `1→2`；另建议复跑 `0→120→240→360` 三段。
2. ★ **已落盘的旧档（如失败轮的 day-240 store）不在本批修复范围**：修复点在"关系**首次合成**"，已持久化的 cloth 规则不会被就地改写 ⇒ 从旧 store 续跑仍会撞 E14。按用户裁定 §一.11「旧档按重建处理」，重建世界即可；若控制方要求"旧 store 也能跑"，那是另一处改动（load 期归一），本批未做、也不建议混进来（会破坏"读口只读"）。
3. 未做变异自证（P-4 归测试批）；未跑 `test`/全仓 `verify`（本批只到 `-pl simos-economy -am`）。
4. 未验证"多产出产业 + 不能产的分成腿"这一拒因分支的真实样本（当前 seeder 的资产规则只产 SHARE 腿，构造不出该分支）。
5. 未追到"B2 让那 3 户在 day 150 前后真的组起 trade unit"的**逐行**因果（只证到：B2 少迁 37 户 ⇒ 闲置 CATTLE 不同 ⇒ 出现 TENANCY CATTLE 份额与对应 unit；见 §1.3）。上游那条 B2 行为差异本身属 B2 语义范围，本批不动。
