# 2026-10-23 小世界 19 hex × 360 tick 经济实测：对照 P2 问题表 A~E

> 目的：按用户 2026-10-23 指示——「经济循环的问题必须源自经济循环的执行本身」——建 17~20 hex 真实 Simos 世界，
> 初始化经济/政府/军队，跑 360 tick，用日志对照 `2026-10-09-p2-population-economy-bug-investigation.md` 的问题表。

## 0. 一句话结论

- **A（人口对账失效）已闭环**、**B（出生=0）已修复**（360 天出生 312 / 死亡 187，人口 4800→5085）；
- **C（需求/劳动口径）已落地**；**D（Unit 多户 / `MARKET_SUBJECT_COLLECTIVE`）仍复现**（1425 条 WARN）；
- **E（GOV/Army 补建路径）可用**（2 GOV + 2 军队建成、编制/军俸政策落盘）；
- **新发现（全部源自执行本身）**：税 **100% 成行政损耗**（行政效率 0）、GOV 国库全空（upkeep 0 支付）、
  军俸 3 个周期 **全部 `no-payable-leg` 跳过**、**铸币政府（world-silver）与运行期 GOV 之间没有任何财政连接**。

## 1. 运行装置（可复现）

- **世界**：`SmallWorld` 19 hex（1 Region / 首都+镇 / tick0 人口 4800）；空库经 `--world=small-world` 真命令序播种
  （social.SetPopulation → CreateCity×2 → SeedGroups → economy.Seed → actor.Seed）。
- **tick0 建场**（全部真 GM 工具，`head 1→8`）：
  - `gov-central`（CENTRAL，无辖区，staff `{SCRIBE:2}`，DM `dm-central`）；
  - `gov-province`（PROVINCE，辖 `small-world`，上级 `gov-central`，staff `{SCRIBE:2}`，DM `dm-province`，税率 100‰）；
  - `unit-capital-guard`（100 人，角色步兵，masterGov=gov-central，军俸 grain 300 / silver 30 每 120 天）；
  - `unit-town-guard`（60 人，角色戍卒，masterGov=gov-province，军俸 grain 180 / silver 18 每 120 天）。
- **推进**：12 段 × 30 tick（`/api/advance`），逐段落 19 格 `economy/hex` dump + 单位表；
  日志级别：economy/app/gov/social/unit = **DEBUG**（TRACE 未开）。
- **证据**：`/home/cna/simos-runs/2026-10-23-sw19-run2/{service.log, dumps/seg-*.json, setup-tick0.json}`；
  约束设计书：`docs/superpowers/specs/2026-10-23-smallworld-19hex-and-economy-360tick-design.md`。

## 2. 对照 P2 问题表 A~E

| 发现 | P2 原文结论 | 本轮（19 hex / 360 tick / DEBUG 日志） | 状态 |
|---|---|---|---|
| **A** 人口对账 `CLASSROW_POPULATION_PROJECTION_UNRESOLVED` | §11.1 已修（unresolved 135→0） | `grep -c` = **0**；经济行人口随 Social 投影（逐段 dump 一致） | ✅ 保持闭环 |
| **B** 出生=0（首日口粮 / 整数截断 / 两套引擎） | §11.5「下一批第一优先」 | `POPULATION_SETTLE` 逐日 `births` 非零：**累计出生 312 / 死亡 187**；人口 4800→5085；31 条/30 天量级 | ✅ **已修复**（本轮未复现） |
| **C** 需求/劳动口径（成员求和、分档系数） | §11.2 已落地 | `SOCIAL_HOUSEHOLD_NEEDS_EXPANDED` / `LABOR_QUEUE` 事件连续；unmet 集中在第 1 周期（day1-120），day150 起 ≈0 | ✅ 保持 |
| **D** Unit 多户 / `MARKET_SUBJECT_COLLECTIVE` | 仍开放 | **1425 条 WARN**（每次市场轮对两个军队单位各报一次；市场仍在成交） | ❌ **仍复现** |
| **E** small-world GOV/Army 补建路径 | §6 盘点（GOV 现成；Army 需 raiseUnit/手工） | 现在两样都能用真工具建：`SD_ARMY_CREATED=2`、`UNIT_SET_ARMY_FORMATION_APPLIED=2`、`UNIT_SET_ARMY_PAY_POLICY_APPLIED=2` | ✅ 可用 |

## 3. 新发现（F1~F4，全部来自本轮执行）

### F1 税：全额 assess、零 collect（行政效率 0）
- `TAX_DAILY_END` 360 天每天都有：day1 `grainAssessed=34,185,723`、`moneyAssessed=6,920`，
  **`grainCollected=0 / moneyCollected=0`**，`grainAdminShortfall=34,185,723`（= assessed）；day360 `grainAssessed=972,086,657`、仍 0 收。
- 根因（回代码核）：`simos-gov` 的 `GovEfficiency.of` —— `securitySupply = YAMEN 在编数`，`paperworkSupply = SCRIBE+POST`；
  本场 GOV 只有 `SCRIBE:2`、**YAMEN=0** ⇒ 治安覆盖 0 ⇒ `efficiencyPerMille=0` ⇒ `collected = assessed × 0‰`，
  其余按 `admin shortfall` 记（`JurisdictionDailyTax` §54/§63 口径，不走已退役的 `administrationPerMille`）。

### F2 GOV 国库全空：日常俸禄 0 支付、军俸全跳过
- `GOV_OFFICE_UPKEEP_EVALUATED` 720 条（2 GOV × 360 天）：`grainNeed=166, grainPaid=0, clothNeed=4, clothPaid=0`。
- 军俸桥接正常（`MILITARY_PAY_BRIDGE units=2 policies=2 rules=2 gaps=0`，360 条），但三个到期日
  （day2 / day122 / day242）× 2 支军队 **全部**：`PERIODIC_ADJUSTMENT_RULE status=SKIPPED gap=no-payable-leg
  payer=hh-gov-gov-central|province shortfallGoods={grain=300|180} shortfallMoney={silver=30|18}`。
- 根因：`hh-gov-gov-central` / `hh-gov-gov-province` 的 `treasuryAccounts` 是**空账**（`goods={}, money={}`）。

### F3 铸币政府与运行期 GOV 之间没有财政连接
- 创世政府 `world-silver`（`hh-gov-world-silver`）持有粮/布（`grain=382,960 / cloth=9,003`，另有每周期权铸币：
  `GOV_SEIGNIORAGE` day1/121/241 各 2000 银，累计 `fiscalIssue=6000`，全口径 `netIssuance=73,200` 银），
  但**没有 GOV 单位**（无 `GovernmentFormation`）⇒ `simos.gov.remit` 这类"国库间上缴"工具够不着它；
  运行期新建的两个 GOV 也没有任何注资路径 ⇒ 税（F1）收不上来、国库（F2）空转。

### F4 小世界口径：GovRules 的人均覆盖系数对 19 格世界过重
- `GovRules`：治安 1 YAMEN/500 人、文书 1 SCRIBE/1000 人 + **城市附加 40/60 人**。
- 本世界（17×200 + 900 + 500 人）需求约 **YAMEN 100 名 / SCRIBE+POST 139 名**（GOV 编制的 ~5% 人口）；
  达不到覆盖就按比例降效率（YAMEN=0 ⇒ 0）。⇒ 在 19 hex 小世界"正常覆盖"要么 239 名吏员，要么明确一套小世界标定。

## 4. 逐段读数（run2，12 段 × 30 tick）

| segment | tick | pop | grainStock（milli） | debtPrincipal |
|---|---|---|---|---|
| 0 | 30 | 4970 | 332,114,415 | 241,479 |
| 1 | 60 | 4982 | 322,051,170 | 244,769 |
| 2 | 90 | 4992 | 311,972,841 | 248,187 |
| 3 | 120 | 4995 | 3,711,335,801 | 6,595 |
| 4 | 150 | 5004 | 3,263,455,759 | 25,834 |
| 5 | 180 | 5014 | 3,253,375,132 | 26,398 |
| 6 | 210 | 5024 | 3,243,286,257 | 27,065 |
| 7 | 240 | 5047 | 6,642,613,773 | 4,118 |
| 8 | 270 | 5056 | 6,194,677,751 | 23,386 |
| 9 | 300 | 5072 | 6,184,536,252 | 23,966 |
| 10 | 330 | 5077 | 6,174,382,416 | 24,618 |
| 11 | 360 | 5085 | 9,720,867,052 | 6,063 |

- 周期锯齿：收获在 day120/240/360 抬升库存；债务在周期内累积、周期末集中偿还（241k→6.6k→…→6.1k）。
- 市场：**75/360 天有成交**（每 ~5 天一轮）；每轮 fills 30~70、unfilled ~250；
  第 1 周期 unmet ≈366k/30 天，day150 起 ≈0（与 P2 §12.5 的"day1 全缺→压力"一致，但 B 已不再被压死）。

## 5. run1 vs run2（口径对照）

run1（税率 0‰、只有 economy DEBUG）与 run2（税率 100‰、全 DEBUG）**人口/粮/债逐段逐值相同**——
因为 F1 的行政效率 0 使税率形同未设；两轮的唯一差别是 run2 暴露了税/俸禄/军俸的完整事件链。

## 6. 下一步（run3 建议，待用户裁定口径）

1. **给 GOV 加 YAMEN**（治安供给）+ 足量 SCRIBE：建议先 **YAMEN 20 / SCRIBE 30**（约 20~28% 效率，税能收到一部分）；
   若要 100% 效率需 ~239 名吏员（或改 GovRules 小世界标定——需用户裁定）。
2. **给两个 GOV 国库注资**：从 `hh-gov-world-silver`（GM 权威转移，`actor.AdjustAccounts` 两腿）转
   grain/cloth/silver 各一份（建议 grain 200,000 / cloth 5,000 / silver 5,000 每 GOV）；
   随后可验证 `simos.gov.remit`（GOV 间上缴）在有钱时的真实路径。
3. 重跑 12 段 × 30 tick（同世界、同军队、同军俸），预期：`TAX collected > 0`、`upkeep paid > 0`、
   军俸 `status=APPLIED`；再观察 D 的 1425 条警告是否影响军队参与市场、以及债务/迁移轨迹变化。
4. 产出对照：run2（未注资）vs run3（注资+吏员）→ 判定哪些是"初始化口径"、哪些是"机制缺陷"。

## 7. 未做 / 未验证

- TRACE 未开：逐笔转移/成交明细未抓（本轮结论全部由 INFO/DEBUG 得出；如需逐户对账另开 TRACE 窗口）。
- 真 LLM 决策人未跑（军队/GOV 的 DM 未参与决策，政策均由 GM 设定）。
- 未做多世界重复（确定性单例）；未做参数扫描。
- run1 的完整读数与 run2 相同（见 §5），其 service.log 保留在 `2026-10-23-sw19/`。
