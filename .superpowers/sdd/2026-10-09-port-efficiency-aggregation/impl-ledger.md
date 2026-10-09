# R2 实现架构账本 —— 法律规定层（口岸维 + 暴露边 + 逐政府管制力 + 市场区总效率 + 走私楔子）

- **责任区**：R2（2026-10-09 口岸设计书 §4.1–§4.4；§7 批次表第 2 行）
- **基线**：`a21adeca`（R1 已落）
- **约束设计书**：`docs/superpowers/specs/2026-10-09-port-policy-and-zone-efficiency-design.md`（+ 上位文档 `…2026-10-08-currency-exchange-arbitrage-and-port-design.md` §5.1–§5.3/§10）
- **任务板**：`team_task_get task-29` 返回 **"agent … is not a member of an active Agent Team"** ⇒ **未能 claim，也未能 complete**（按任务书"不要卡住"执行）。
- **门禁**：`tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` ⇒ **exit 0**（每层各跑一次，末次见 §7）。**未写/未跑任何测试**（不碰 `src/test/**`、不跑 `test`/`verify`/`package`、不 `git commit`）。

---

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | 证据 | 结论 | 影响 |
|---|---|---|---|
| 1 | `simos-gov/src/main/java/io/mosire/simos/gov/GovEfficiency.java:410-416`（改前 10 组件 record）、`:21-27`（两维公式） | 效率是"计划 P_d → 需求劳动 → 满足率 → 最终效率"，总效率 = 治安 × 公文 ÷ 1000 | 口岸维必须**照同一形制**加，且**不能**乘进总效率（否则无口岸编制的 GOV 总效率当场归零 ⇒ I-P8 破） |
| 2 | `GovAdministrationPlan.java:43-51`（8 组件）、`GovPostTier.java:20-21`（两维权重的档位） | 口岸编制的**唯一可用载体**是"档位第三权重"：`StaffRole` 在 `simos-unit`（**不在本批文件所有权内**，禁改） | 口岸岗位 = 挂到 `portWeightPerMille > 0` 档位的既有角色家户；**不新增 StaffRole** |
| 3 | `simos-app/.../household/GovernmentServiceLaborBridge.java:265-283`（两维拆分 + 余数归公文） | 供给桥是两维划分：`治安=⌊L×w_sec÷W⌋`、`公文=L−治安` | 三维化若把余数给口岸，默认档 3（500/500）下 L 为奇数时**公文会少 1 毫小时** ⇒ 旧世界变值。**必须让余数继续归公文**（见 §3.3） |
| 4 | `simos-gov/pom.xml:56-80` enforcer：禁 `simos-economy`；依赖含 `simos-economy-api` | gov 能看见 `CommodityId`/`CurrencyId`（契约），**看不见** `EconomyData`（词表） | 政策载体的键可以用经济 ID；"未知商品/币种"判据**只能落组合根**（→ §3.7 的写前守卫） |
| 5 | `simos-app/.../time/PopulationEconomyTimeParticipant.java:651`（`stepper.step(day)`）与 `:718`（`computeGovEfficiency`） | **市场轮在 gov 效率之前**：口岸效率只能在当日结算后算出 | 注入天然带 **1 tick 滞后**（§3.5）；重排会改动既有数值 ⇒ 不做 |
| 6 | `simos-economy/.../MarketSettlement.java:330-360`（`arbitrage`/`govMandates`/`fx` 三个逐轮瞬态 + 各 `withX`）与 `:715-770`（`copyForWorker` 的"克隆丢字段"三行） | 逐轮瞬态的**唯一**接入形制 = 字段 + `withX` + 在 4 处克隆里逐字段带过；该坑本类踩过三次 | 口岸管制沿用同形制（§3.6） |
| 7 | `CurrencyValuation.java:236-238`（按区估值）与 `MarketSettlement.java:4663`（成交腿调用） | 家户"收不收异币"的**唯一裁定点**有区上下文 | 减项落在 `valuationMicro(numeraire, currency, regionId)`；`HouseholdValuationBook:301` 那条（无区上下文）不动，如实记为边界 |
| 8 | `GovState.java`（改前 4 组件，缺键 ⇒ 空表）、`GovChangeSet.java`（缺键 ⇒ Unchanged） | 既有"旧档缺键 ⇒ 中性默认"兼容惯例现成 | 第五组件 `portPolicies` 沿用同惯例，零额外风险 |
| 9 | `simos-economy/.../model/CommodityFreightBase.java:27-39` | 商品基础运费 **1–3 毫/单位/程**（粮1/纤维1/布2/工具3） | 罚没基准取"最重一档的三程运费"= **9**（§3.4） |
| 10 | `simos-map/.../hex/HexCoord.java:71-74` + `GameMap.hexes()` | `neighbors()` 枚举序返回 6 邻居，**不判地图边界** | 暴露边必须自己判"邻居格存在"（§3.2） |
| 11 | `simos-app/.../world/ThreePowersWorld.java:315`（真世界）+ `Shell.java:906`（`MutationGuard` 注册点） | 组合根有真 3 区/3 GOV 世界与写前守卫 SPI | 探针用真世界；N1 的"未知类"用写前守卫收口（§3.7） |

---

## 2. 实现架构（落在哪些文件、数据怎么流）

### 2.1 口岸维 = `GovAdministrationPlan` / `GovEfficiency` 的**第三维**（照既有两维形制）

```
GovPostTier(tierId, w_sec, w_pap, w_port)                      ← 4 组件（旧 3 参构造器 ⇒ w_port = 0）
GovAdministrationPlan(sec/pap/port 计划量, postTiers,           ← 11 组件（旧 8 参构造器 ⇒ 口岸计划 0 + 两个修正 1000‰）
                      sec/pap/port 供给静态, sec/pap/port 需求静态, k)
GovEfficiency.of(... 14 参：sec/pap/port 供给 + 三维供给动态 + 三维需求动态 ...)  ← 旧 10 参入口委托（口岸供给 0、动态中性）
Efficiency(10 旧字段 + portCoverage/portEfficiency/portEffectiveLabor/portDemandLabor) ← 旧 4/6/10 参构造器保留
GovEfficiencyModifier(gov, sec/pap/port 供给动态, sec/pap/port 需求动态, source, reason) ← 旧 7 参构造器保留
```

- **总效率 `efficiencyPerMille` 仍然只乘治安 × 公文**；口岸维给独立读数 `portEfficiencyPerMille`（= 实际管制力的乘数）。
- 满足率/需求/有效劳动三维逐字同式；`需求=0 ⇒ 满足率 1000‰`；`供给=0 ⇒ 该维效率 0`（既有口径）。

### 2.2 政策载体 = `GovState.portPolicies`（第五组件，Command → ChangeSet → Revision）

```
GovPortPolicy(commodityRestrictionPerMille: Map<CommodityId,Long>,
              currencyRestrictionPerMille:  Map<CurrencyId,Long>)     ← 缺键 = s = 0 = 不限制
GovState(offices, administrationPlans, budgetPolicies, portPolicies, remittanceStates)  ← 5 组件
GovChangeSet(... 5 个 FieldDelta ...)                                     ← 铁律 5，between/apply 逐字段
gov.SetPortPolicy  →  SetPortPolicyHandler（GM-only，整体替换，与既有政策逐值相同 ⇒ 空变更集）
                  →  GovPortPolicyGuard（组合根写前守卫：未知商品/币种 ⇒ 具名 Rejected，零 revision）
```

### 2.3 暴露边（纯函数、不落盘、只在 Z 侧计）

`simos-app/.../time/PortExposureEdges.java`：`MarketZone.hexes` × `HexCoord.neighbors()` ×
`Unit.jurisdiction()` → `List<Contact>(zoneId, ownerKey, w_k)`；`UNGOVERNED_OWNER_KEY` 收三不管边；
同格两主 ⇒ 具名契约 ERROR + 抛。

### 2.4 折算（组合根唯一落点）

`simos-app/.../time/PortRegimeBridge.java`：
政策类定义域（逐区取各归属政府政策里**显式设过**的类）→ 逐接触面 `(w,s,e)` →
`economy-api.PortContactSurface` → `economy.PortRegimeAggregation.aggregate(...)` → `ZonePortRegime` →
注入 `1000 − E` 到 `PortEnforcementInput` → `EconomyDayStepper.updatePortEnforcement(...)` →
`EconomySettlement.settleOneDayInto(12 参)` → `MarketRound.withPortEnforcement(...)` →
`CurrencyValuation.of(fx, circulation, portEnforcement)` → `valuationMicro(numeraire, currency, regionId)` 的减项。

### 2.5 走私（纯函数，无实体、无随机数）

`economy/.../time/PortRegimeAggregation.split(E, 总过境能力)`：
`正常=⌊能力×E÷1000⌋`、`走私=能力−正常`（Σ 守恒）、`成本楔子=⌊(1000−E)×9÷1000⌋`（9 = `SEIZURE_BASELINE_MILLI`）。

---

## 3. 关键判断（为什么这样、为什么不那样）

1. **口岸维不进总效率**（§4.2 的"第三维"读法）：① I-P8 是第一判据 —— 乘进去会让任何没有口岸编制的 GOV 总行政效率归零（税/服务随之中断）；② 语义上它是"口岸这一维办公室的办事能力"，不是政府整体行政能力的乘数（上位文档 §5.1 里它甚至是**以行政效率为输入**派生的）；③ 它的消费方（口岸管制折算）与税收/服务的消费方不同。★ 这是对"第三维"的**解释性判断**，记账在 §5。
2. **暴露边只在 Z 侧计、不除 2**：聚合按区问"这个区有多少暴露边、分别归谁管"，扫的只有 Z 的成员格 ⇒ 每条边在 Z 的计数里恰好一次（不存在重复）；**除 2 反而错两处**：把 Z 的暴露边砍半、把"地图边缘只被一个区计到"的边算成半条。上位文档 §10-2 的"除 2"针对"全图总暴露边"那种跨区口径，本批不做那个口径。
3. **邻居格必须存在才计**：地图外的邻居不存在 ⇒ "对虚空设关"没有对象；若计进来，覆盖全图的区会凭空获得暴露边与管制面（幽灵权威）。邻居格存在但**不属于任何区**的边照计（三不管地带是真实来货方向）。
4. **供给拆分：口岸取 ⌊⌋ 份额、余数继续归公文**（`公文 = L − 治安 − 口岸`）：`w_port=0` 时逐值退化为旧两维算式（这就是 I-P8 不在供给拆分上失守的原因）；若余数给口岸，默认档 500/500 下奇数 L 会让公文少 1 毫小时。
5. **注入带 1 tick 滞后**：`stepper.step(day)`（市场轮）在 `computeGovEfficiency` 之前（`:651` vs `:718`），口岸效率只能当日结算后算出 ⇒ 注入值作用于下一次市场轮。不重排的理由：重排会让 gov 效率读到未结算的家户劳动 ⇒ 改动既有数值。与 `GovEfficiencyModifier` 的"逐 tick 注入"同族；在代码与账本两处写明。
6. **逐轮瞬态专用字段 + 4 处克隆逐字段带过**：`MarketRound` 已有 `arbitrage`/`fx`/`govMandates` 三个同形字段与"克隆丢字段"的既有坑（`copyForWorker` 注释明写），口岸沿用同形制（`withArbitrage`/`withFx`/`withGovMandates`/`copyForWorker` 四处各加一行）。
7. **N1 的"未知商品/币种"落在组合根写前守卫**：gov 编译期看不见经济词表（模块边界），若只在折算处炸，坏政策**已经落进状态**了 —— 那不是"具名拒绝"。⇒ 新增 `GovPortPolicyGuard`（`MutationGuard`，与 `GovJurisdictionGuard` 同注册点），命令在 `handler.handle` 之前被拒、**不留 revision**；折算处仍保留具名 ERROR 作为纵深防御（正常路径不可达）。
8. **`openness` 钳到 [0,1000]**：`e` 不封顶（超编开方/静态修正可 >1000‰）⇒ `s×e÷1000` 可超 1000；开放度是比例量，定义域就是 [0,1000]（"比全闭还闭"没有意义）。钳的是开放度，**不是给效率封顶**；`s=0` 时恒 1000，与钳制无关。
9. **`Σw = 0` ⇒ E=1000 且"允许"**：设计书明写"取 1000（全开）并具名记录"；空接触面读作"没有口岸 ⇒ 无从设限 ⇒ 放行"，不是"∃ 在空集上恒假"的字面陷阱。具名标记 `noContactSurface` 进契约（不静默当 1000）。
10. **类的定义域 = 政策里显式设过的类**（逐区）：一条政策都没有的世界 ⇒ 空 `PortEnforcementInput.none()` ⇒ 与"没注入"逐值同义（I-P8 的结构性保证，不靠特判）。
11. **罚没基准 = 9 毫/单位 = 3 × 工具基础运费**：与 1–3 毫/单位/程 的运费表**同量纲同量级**，"被抓一次的期望损失 ≈ 把货合法运三程"；常量直接引用 `CommodityFreightBase.TOOL_MILLI`（运费调档则基准跟着走，单一来源）。
12. **减项折扣上限 1000‰、下界 1 微**：管制力 >1000‰ 时折扣按 100% 截（"比全没收还多"没有意义）；下界与 `valueMicro` 的 `max(1,…)` 同口径。
13. **旧档 JSON 缺口岸字段 ⇒ Jackson 给 0**（不是中性 1000）：`portPlannedLaborMilli=0` 正确；两个静态修正 0 使口岸效率恒 0 ⇒ 无管制力 ⇒ 全部放行 —— 方向 fail-closed 且 I-P8 成立。Java 便捷构造器给中性 1000‰。两条路径数值不同但世界效果相同（口岸维供给本就是 0），探针两种形态都逐值贴过（§7.4）。

---

## 4. 偏离记录（与约束设计书不一致之处，主动申报）

| # | 偏离 | 原因 / 性质 |
|---|---|---|
| D1 | **口岸维不进"总行政效率"**（`Efficiency.efficiencyPerMille` 仍 = 治安 × 公文 ÷ 1000） | 设计书 §4.2 只说"口岸 = 第三维、沿用既有 GovEfficiency 口岸维"，**未规定**它是否进总效率。按 I-P8（第一判据）+ 语义选定"独立读数"。属**解释性判断**，不是静默偏离 |
| D2 | **不实现上位文档 §5.2 的"行政效率对口岸补偿"字段** | 2026-10-09 设计书（最新文档，§4.2 + 任务书）把口岸维冻结为"照既有两维形制"的 `满足率 × 供给静态 × 供给动态`，不含补偿混合项；补偿项是 §5.2 的旧形态（"新增字段"建议、非用户裁定） |
| D3 | **走私的"可正常/走私量"只落成纯函数 + 探针，未接真实跨区流量** | 需要"总过境能力"的实测量口径（跨区到货/发运流量），仓内**没有**这个读数；凭空造一个产能常量会是第二本权威。成本楔子与 E 的消费面（CurrencyValuation 减项）已真实接入。如实列为未完成项（§8） |
| D4 | **商品类管制只产出读数**（未拦截跨区商品流） | 同上：拦截需要交易/发运路径按"类 × 来源区"判定的接入点，超出本批；`PortEnforcementInput` 的商品表已就位（组合根已注入），留给 R3/G3 复测批 |
| D5 | **口岸编制只能经档位权重指派**（不新增 `StaffRole`） | `StaffRole` 在 `simos-unit`，本批文件所有权**禁改**（任务书"禁止碰 simos-unit"）。已写入 `GovPostTier` 类注 |
| D6 | **`GovPortPolicy` 的编辑语义 = 整表替换**（照 `SetAdministrationPlan`），不做 PATCH | 设计书未规定；命令面属开放点 O4（留 GOV 优化）。`SetBudgetPolicy` 的 PATCH 是另一族语义，本批不混 |
| D7 | **政策命令 GM-only** | 照 O4"本批只做算法与机制"；与 `SetAdministrationPlanHandler` 同口径 |

---

## 5. 探针输出（`/tmp`，**不进仓库、不进 `src/test`**；证据文件 `/tmp/r2probe/*.txt`）

装置：真 `ThreePowersWorld.state("three-powers")`（37 格 / 3 区 / 3 GOV / 真 GOV_SERVICE 承诺，创世走真命令 handler）；
真 `EconomyDayStepper` 日循环 24 天（组合根三样只读投影按 app 同名私有 helper 逐字复制）；
真 `GovCodec`/`EconomyCodec`/`SocialCodec` 编码；HEAD = `git worktree add /tmp/r2head HEAD`（`a21adeca`）同探针同跑。

### 5.1 I-P8 逐值不变（HEAD worktree vs 本工作树）

一次**逐字节相同**的对照轮（同一次运行形状）：

```
HEAD    PART economy=3abd41d4… len=2519398 | social=7c6677f7… | accounts=75a7fd71… | readings=887de5aa…
CURRENT PART economy=3abd41d4… len=2519398 | social=7c6677f7… | accounts=75a7fd71… | readings=887de5aa…
HEAD    PROBE=IP8 days=24 behavioralDigest=de32d64026781c7fd4379c737f216e7ae2a3fbc9c98f55c08d43c5ad8b6ec6e8
CURRENT PROBE=IP8 days=24 behavioralDigest=de32d64026781c7fd4379c737f216e7ae2a3fbc9c98f55c08d43c5ad8b6ec6e8
HEAD/CURRENT READING（逐 GOV）gov-tp-{copper,gold,silver}: securitySupply=16000 paperworkSupply=16000
   committedSecurity=16000 committedPaperwork=16000 underfed=0 secCov=1000 papCov=1000 secEff=1000
   papEff=1000 total=1000        ← 两棵树逐值相同
HEAD/CURRENT POPULATION_ECONOMY=6369 MARKETS=37 UNITS=271
```

★ 但 **economy 的 JSON 字节在**同一棵树内**就逐轮抖动**（`HashMap` 键序）：HEAD 6 跑得 3 个不同 digest
（`3abd41d4`×3 / `0a5f36f3`×2 / `8d306925`×1），本树 6 跑也是同样那 3 个（说明**这不是本批引入的**）。
⇒ 用**顺序无关的语义比较**补强（对一对字节不同的运行）：

```
head bytes sha256 = 43a97a870db0c6d8…   cur bytes sha256 = 7137b062ac7345f4…
ECONOMY_SEMANTIC_EQUAL = True   diffs = 0        ← 键集/值逐项相同，只有映射键序不同
GOV_STATE_ADDED_TOP_KEYS = ['portPolicies']      ← 值 = {}
GOV_PRE_EXISTING_FIELDS_EQUAL = True   diffs = 0
GOV_NEW_FIELD_VALUES = {portWeightPerMille:0 ×9, portPlannedLaborMilli:0 ×3,
                        portSupplyStaticModifierPerMille:1000 ×3, portDemandStaticModifierPerMille:1000 ×3}
```

⇒ **I-P8 成立**：economy/social/账户/逐 GOV 读数逐值不变；gov 状态只多出新维度字段（值 = 0/中性），
无一既有字段被改。★ 同时**报出一条既有缺陷**：`EconomyData` 的 JSON 字节序不是内容的纯函数（HEAD 就有，
与本批无关，但会让"字节级往返"类断言在跨运行时不稳定）。

### 5.2 T1：暴露边条数（生产函数 vs 探针内独立手算，逐区互核）

```
T1 contacts=3 totalEdges=36
T1 zone=c-tp-copper members=16 edgesByProductionFn=13 edgesByIndependentHandCount=13 agree=true   owner=gov-tp-copper w=13
T1 zone=c-tp-gold   members=12 edgesByProductionFn=12 edgesByIndependentHandCount=12 agree=true   owner=gov-tp-gold w=12
T1 zone=c-tp-silver members=9  edgesByProductionFn=11 edgesByIndependentHandCount=11 agree=true   owner=gov-tp-silver w=11
T1 手算明细（铜区 16 格逐格外部邻居数）: 1,0,2,0,0,2,0,0,0,3,0,0,2,0,2,1 → 合计 13 = 生产函数值
T1b（把铜省 1 格并入银区后）zone=c-tp-silver owners=[gov-tp-copper w=3, gov-tp-silver w=11]
    twoGovernmentsInOneZone=true
```

### 5.3 T2 / T3 / T4（真 `PortRegimeBridge` × 真区表）

```
T2 完全没有政策        → active=false inputActive=false（⇒ 不注入 = 逐值不变）
T2 显式 s=0（grain）   → allowed=true E=1000 enforcement=0（未设限制 ⇒ 可通行 ✓）
T3 同一限制 s=1000、两政府效率不同（逐政府执行，I-P5）:
   eff=1000 owner=gov-tp-copper w=3  s=1000 e=1000 enforcement=1000 openness=0
   eff=1000 owner=gov-tp-silver w=11 s=1000 e=1000 enforcement=1000 openness=0
   eff=500  owner=gov-tp-copper w=3  s=1000 e=500  enforcement=500  openness=500   ← 同限制、效率不同 ⇒ 管制力不同
   eff=500  owner=gov-tp-silver w=11 s=1000 e=1000 enforcement=1000 openness=0
   E(eff1000)=0   E(eff500)=⌊(3×500+11×0)/14⌋=107
T4 规则 OR：一个政府 s=1000（全禁）、另一个未设(0) ⇒ allowed=true，E=⌊(11×1000+3×0)/14⌋=785
```

### 5.4 T5 / T6 / T5b：加权平均（不是 min/max）

```
T5 1 条放开 + 8 条全禁（w 各 1）⇒ E=111 = ⌊1000/9⌋，allowed=true，W=9
T6 再加一条放开接触面        ⇒ E=200，rose=true（不是 min=0、也不是突变到 1000）
T5 每条半开（s=500,e=1000）  ⇒ E=500
T5b 无接触面（Σw=0）         ⇒ E=1000，namedFlag(noContactSurface)=true，allowed=true
```

### 5.5 T7：走私 = 规模现象（无随机数）

```
seizureBaseline=9（= 3 × 工具基础运费 3）
E=1000 normal=1000000 smuggled=0       share=0‰   wedge=0   Σ=能力 ✓
E=111  normal=111000  smuggled=889000  share=889‰ wedge=8   Σ=能力 ✓
E=0    normal=0       smuggled=1000000 share=1000‰ wedge=9  Σ=能力 ✓
monotone(smuggled: E↓⇒↑)=true   monotone(wedge: E↓⇒↑)=true   twoRunsIdentical=true
```

### 5.6 减项进 `CurrencyValuation`（用户 2026-10-08 原话兑现）

```
TCUR 有官方汇率（银↔铜 1:1）: enforcement0=1000   enforcement500=500   enforcement1000=1
TCUR 当地流通（无报价 ⇒ 面值 1000）: enforcement0=1000   enforcement400=600
```

⇒ `enforcement = 0` 时**逐值不变**（×1000÷1000 = 原值，整数精确）；管制力越高，家户对这种钱估值越低
（卖方更不愿意收它）—— **没有另写"家户不敢用"的逻辑**。

### 5.7 负向 N1（fail-closed 具名拒，不静默忽略）

```
负强度（构造期）     → Rejected reason=口岸限制强度不得为负（非法政策）: commodityRestrictionPerMille[grain] = -1
未知商品（折算处）   → ERROR event=GOV_PORT_POLICY_UNKNOWN_CLASS reason=unknown-class-in-port-policy
                       + IllegalStateException「口岸政策非法：未知商品 unobtanium（政府 gov-tp-silver，day 9）」
未知币种（折算处）   → 同上（kind=currency classKey=drachma）
handler 负强度       → Rejected reason=口岸限制强度不得为负（非法政策）: …[grain] = -5（零 revision）
写前守卫 未知商品    → Optional[口岸政策非法：未知商品 unobtanium（不在世界词表内；fail-closed 拒，不静默忽略）]
写前守卫 未知币种    → Optional[口岸政策非法：未知币种 drachma（…）]
写前守卫 合法政策    → Optional.empty（放行）
写前守卫 别的命令    → Optional.empty（只拦 gov.SetPortPolicy）
```

### 5.8 旧档缺字段兼容（HEAD 落的 gov 快照 JSON ← 新 codec 读回）

```
LEGACY topComponents=5  offices=0 administrationPlans=3 budgetPolicies=3 portPolicies=0 remittanceStates=0
LEGACY plan unit=gov-tp-*: securityPlanned=16000 paperworkPlanned=16000 portPlanned=0
                            portSupplyStatic=0 portDemandStatic=0 tier1PortWeight=0 k=1
LEGACY portPolicyOrDefault(gov-tp-*) empty=true s(grain)=0        ← 旧档 = 不限制（I-P1）
LEGACY reencodeRoundTripEqual=true   reencodeHasPortPolicies=true   reencodeHasPortPlanField=true
LEGACY javaCompatCtor  portPlanned=0 portSupplyStatic=1000 portDemandStatic=1000
LEGACY neutral()       portSupplyStatic=1000 portDemandStatic=1000 portPlanned=0
LEGACY changeSet("{ }") allUnchanged=true portPoliciesUnchanged=true   ← 旧变更集缺键 ⇒ Unchanged，不抛
ROUNDTRIP 新政往返 policyEqual=true s(grain)=1000 s(copper)=250
```

### 5.9 可靠性

- 探针 B 两跑输出 **md5 相同**（`aa892defb705f096c63e05240f358ad1`）⇒ 无随机数、确定性（I-P7）。
- 探针 C 输出 md5 `b5c473bcc01c287cc52e28200cd6a1f1`。
- 全部证据文件：`/tmp/r2probe/{head,cur}.txt`、`probeB.txt`、`probeC.txt`、`head|cur/{economy.json,gov.json,accounts.txt}`。

---

## 6. 会改变数值行为的清单（供测试代理当输入）

1. **`GovEfficiency.Efficiency` 组件数 10 → 14**：新增 `portCoveragePerMille/portEfficiencyPerMille/
   portEffectiveLaborMilli/portDemandLaborMilli`。★ 无口岸编制时 `portCoveragePerMille` = **1000**（需求 0 ⇒ 满足率 1000 的冻结口径），
   而兼容构造器给 **0** ⇒ **按 record 逐字段比较的旧断言会红**（`assertEquals(expected(6参), GovEfficiency.of(10参))`）。
2. **线格式（JSON）新增字段/组件**（gov 快照 3471 → 4014 字节）：
   `GovState.portPolicies`、`GovAdministrationPlan.{portPlannedLaborMilli, portSupplyStaticModifierPerMille,
   portDemandStaticModifierPerMille}`、`GovPostTier.portWeightPerMille`。**逐字段断言 JSON 字节的旧断言会红。**
3. `GovAdministrationPlan` 8 → 11 组件、`GovPostTier` 3 → 4、`GovState` 4 → 5、`GovChangeSet` 4 → 5、
   `GovEfficiencyModifier` 7 → 9、`GovernmentServiceLaborBridge.Supply` 5 → 7 ⇒ `equals`/`hashCode`/`toString` 相关的旧断言形状变了。
4. **供给拆分三维化**：仅当某档位 `portWeightPerMille > 0` 时数值变化（口岸取 ⌊⌋、余数仍归公文）；`w_port = 0` 时**逐值等于旧两维**。
5. **`CurrencyValuation` 新增减项**：仅当注入 `enforcement > 0` 时估值变化；`enforcement = 0`（缺省 `none()`）逐值不变。
6. **`MarketRound` 新增逐轮瞬态 `portEnforcement`**（缺省 `none()`）；`EconomyDayStepper.updatePortEnforcement` 新增（缺省 none）。
   4 处克隆（`withArbitrage/withFx/withGovMandates/copyForWorker`）各多带一行 —— 遗漏即"口岸管制整段不生效且无报错"。
7. **命令面 +1 条**：`gov.SetPortPolicy`（GM-only）注册进 `Shell`；`GovLogSource` +1 表项 `GOV_PORT`；
   组合根 `MutationGuard` +1（`GovPortPolicyGuard`）。按"注册面派生条数"的读数/断言（handler 数、命令目录、日志来源表）会变。
8. **预存量缺陷（非本批引入，如实报）**：`EconomyData` 的 JSON 映射键序在同一棵树内逐轮抖动（HEAD 6 跑 3 个 digest）⇒
   任何"字节级 digest 相等"的既有/新增断言在跨运行时**不稳定**。

---

## 7. 会让既有测试失效的清单（**未跑测试**，按构造点静态审计；本批禁碰 `src/test/**`）

> 前置事实：本批**没有写、没有跑**任何测试（任务书 §一.5）。下列清单是按"哪些既有断言的**形状**变了"静态审计的结果，
> 供测试代理当输入；**其真实性未经执行验证**。

| # | 可能失效的测试族 | 判据 |
|---|---|---|
| 1 | 用 4/6/10 参构造器造"期望的 `Efficiency`"再与 `GovEfficiency.of(...)` 返回值 `assertEquals` | §6-1（口岸满足率 1000 vs 0） |
| 2 | 断言 gov 快照/变更集 **JSON 字节或精确字段集**的用例 | §6-2（新键） |
| 3 | 断言 `GovState`/`GovChangeSet`/`GovAdministrationPlan`/`GovPostTier`/`Supply` **record 组件数或 toString** 的用例 | §6-3 |
| 4 | 用 HEAD 落的**旧档 JSON 夹具**做"新代码读回后与某个 Java 构造的计划相等"的断言 | 旧档 JSON 给口岸静态修正 0，Java 便捷构造器给 1000 ⇒ 二者不等（§5.8 两行） |
| 5 | 断言 `EconomyData` JSON digest/字节序的用例 | §6-8（**预存量**抖动；本批前后都存在） |
| 6 | 断言 handler 数量 / 命令目录条数 / 日志来源表条数的用例 | §6-7 |
| 7 | 断言跨区外汇成交价（`valuationMicro` 命中价）的用例 | 仅当测试注入了口岸管制（本批**不会**，缺省 none）⇒ 预期**不红** |

---

## 8. 未完成 / 未验证 / BLOCKED

**未完成（不是"已做"，如实列）**
1. **走私量的产能接入（D3）**：`split(E, 总过境能力)` 是纯函数 + 探针；生产日循环里**没有**"总过境能力"的读数口径 ⇒ 未接入。
2. **商品类管制的实际拦截（D4）**：`PortEnforcementInput` 的商品表已注入（减项只作用于币种），跨区商品流的放行/拦截未接。
3. **政策命令面**：`gov.SetPortPolicy` 只挂 GM（O4 留 GOV 优化）；无决策人工具/审批链/白名单。
4. **口岸编制的"建议量"**：`GovDemand` 的建议需求里**没有**口岸维（它需要暴露边 ⇒ 归组合根）；本批只有 `P_port` 由政策/计划给，
   没有"按暴露边算建议编制"的告警。设计书上位文档 §10-2 的"由暴露边条数算管理所需劳动力"落在 `PortExposureEdges` 的 `w_k` 上，未做成建议值。
5. **`HouseholdValuationBook` 那条估值路径（`:301`）没有区上下文** ⇒ 减项只落在成交腿（`MarketSettlement:4663`）；家户价目表侧不减项。
6. **日志覆盖**：新增事件 6 条（`PORT_EXPOSURE_EDGES_COMPUTED`(DEBUG)/`PORT_REGIME_COMPUTED`(DEBUG)/
   `PORT_SURFACE_READING`(TRACE)/`PORT_REGIME_INJECTED`(INFO)/`GOV_PORT_POLICY_UNKNOWN_CLASS`(ERROR)/
   `GOV_SET_PORT_POLICY_*`），但**真 `-Dsimos.*` 系统属性轮未跑**（同 2026-10-23 日志收口批的遗留口径）。

**未验证**
- 未跑 `test`/`verify`/`package`/前端门禁（任务书禁）⇒ §7 的测试影响清单**未经执行验证**。
- 未在真 `Shell`/sqlite 存档路径上跑（探针走真世界 + 真日结算 + 真 codec，但不经 GUI/sqlite）。
- 未跑 360 tick / 长跑读数（G3 复测批的活）。

**BLOCKED**：无硬阻塞（不因纯实现困难停手）；上述 1/2/4 需要"跨区流量读数口径"或新的文件所有权，属**后续批次**。

---

## 9. 新增/改动文件（24 个，全部生产代码；以 `git status --porcelain` 为准）

**新增（9）**
- `simos-gov/src/main/java/io/mosire/simos/gov/GovPortPolicy.java`
- `simos-gov/src/main/java/io/mosire/simos/gov/spi/SetPortPolicyHandler.java`
- `simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/PortContactSurface.java`
- `simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/ZonePortRegime.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/PortRegimeAggregation.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/PortEnforcementInput.java`
- `simos-app/src/main/java/io/mosire/simos/app/time/PortExposureEdges.java`
- `simos-app/src/main/java/io/mosire/simos/app/time/PortRegimeBridge.java`
- `simos-app/src/main/java/io/mosire/simos/app/world/GovPortPolicyGuard.java`

**改动（15）**
- gov：`GovPostTier`、`GovAdministrationPlan`、`GovEfficiency`、`GovEfficiencyModifier`、`GovState`、`GovLogSource`、
  `change/GovChangeSet`、`spi/GovPayloads`
- economy：`time/CurrencyValuation`、`time/MarketSettlement`、`time/EconomyDayStepper`、`time/EconomySettlement`
- app：`household/GovernmentServiceLaborBridge`、`time/PopulationEconomyTimeParticipant`、`Shell`

**未碰**：任何 `src/test/**`（`git status | grep -c src/test` = 0）、任何 `pom.xml`、`docs/**`、`simos-social/**`、`simos-map/**`、
`simos-unit/**`、其余 `.superpowers/**`。

**留在机器上的装置（控制方自便清理）**：`/tmp/r2head`（`git worktree add /tmp/r2head HEAD`，I-P8 对照用；
清理 = `git worktree remove /tmp/r2head`）、`/tmp/r2probe/**`（探针源码 + 证据）。两者都**不在仓库内**、不进提交。
