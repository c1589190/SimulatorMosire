# 政府专属生产方式 与 行政编制框架（Z0 约束设计书）

> 状态：用户在 2026-10-23 说"开始吧"，授权按本文开工。本文是 Z1a/Z1b/Z2/Z3/Z4/Z5/Z6 的**唯一外部契约来源**；
> 实现代理与派单文档必须以本文为准，与本文或用户原话冲突 ⇒ **上报 BLOCKED**，不得自行改设计（AGENTS §一.8.1）。
> 配套材料：可行性报告 `docs/superpowers/reports/2026-10-23-gov-service-mode-feasibility.md`（复用/缺口/自证的细节）；
> gov 税/国库排查 `docs/superpowers/reports/2026-10-23-gov-tax-treasury-investigation.md`（F1~F4）。

---

## 0. 用户原话（逐字，不得改写）

**效率公式**
> 先给行政区的行政效率加一个修类似经济的公式，也就是行政效率（百分比，决定实际税收等）=治安效率*行政满系数，其中治安效率=治安人员劳动力数/治安人员劳动力需求*静态修正*动态修正，行政效率=行政人员劳动力数/行政人员劳动力需求*静态修正*动态修正，其中两个动态修正是给其他模块的程序每tick更新的，静态修正是方便GM改的，这个能实现吗？

> 说错了，后一个行政效率是公文效率，前一个是总行政效率，总行政效率是公文效率*治安效率

> 我的想法是，如果行政/治安劳动力投入超出需求，超出需求的那部分按开方级下降，算作实际投入到行政/治安等劳动力去，懂我意思吗？

> 都不封顶，要是真的有人民政府，群众把自己库存全给出去还生造了一部分呢？

**劳动力权威**
> 额，单个家户提供的劳动力不是家户维护的吗？家户在这个方面没有通用工具吗

**政府生产方式 / 招募 / 告警**
> 我的想法是，单个政府可以配置一个政府专属的生产方式，政府自己需要的劳动力，既可以从政府自己管的家户里获得，也可以作为需求，挂一个生产方式到市场，允许其他经济家户加入，然后为政府提供劳动，这个能实现吗？

> 政府产出算成和运力一样，不能存到第二tick的商品；命令自然是决策人可直接调用但是gm审批的那种，哦对了，查看工具做了吗？；岗位角色也是政府设置目前可以先设3档普通基层；国库预算优先级也是政府决策人自己决定如何调整；我之前不就让你补创建/修改模版了？继续规划

> 现有gov家户应当分各种职位，例如文员、治安等；gov额外创建生产方式，先把政府自己的家户填进去，不够再由gov决策人选择招多少人、招不招人；最终算实际拿到的行政劳动力，给行政效率判定；此外我意识到，治安、文员的需求同样是动态的，要求gm和其他项目内组建可编辑，我说的可以实现吗？

> 这里的招募工作还分扩充政府家户或是允许外来家户承担行政任务，所以最好是有问题只给gm/决策人一个警报，把主动自定义工具给写全

> 政府这个生产方式呢，是由政府自己预估需要的劳动力规模，然后决定的，所以情况比较复杂，需要自定义的点比较多，最好还是考虑考虑要做出什么

> 开始吧

**同会话已冻结的其他裁定**（出处见 §15）：政府不招人就没有行政效率（维持 `min` 门语义：任一维 0 ⇒ 总效率 0）；
官吏 = **全职**，岗位是对家户劳动的显式承诺、从生产预算扣掉；`hh-gov-<unitId>` 保持 **0 人口纯财政**；
供给劳动取自**家户权威**（`SocialData.householdLaborMilli`），不造"每角色劳动值"；需求定额用**标准劳动系数**；
`k` 进 gov 源状态、GM 可改；动态修正每 GOV 两个 ‰、app 逐 tick 注入、**GM 不可达**；没挂岗位家户 ⇒ 该维供给 0 + 具名 INFO。

---

## 1. 目标与范围（V1 / V2 / V3）

**V1（本文派单范围）**：一个政府一个行政编制闭环——
编制计划（政府预估，可改）→ 自家户先填（全职承诺）→ 缺额由决策人显式招募（两路：扩充政府家户 / 外来家户承担）
→ 实际承诺劳动算两维效率 → 按承诺小时发工资、按决策人定的预算优先级支国库 → 有缺口只发**告警**；
全部配置点做成**状态 + 命令 + 读口**，GM 工具与决策人工具（GM 审批链）并列。
另含 `upsertIndustry`（创建/修改模板）与 gov 专属 `office` 服务生产方式的基础设施。

**V2**：外来家户规模化加入（劳动队列/`laborSources` 的资格、工资竞争、容量打磨）。

**V3**：真正的岗位发布/应募/竞价原语（`GovJobPosting` 类）、多级政府编制联动、铸币/财政联动。

**永久不做**：行政产出作为可存储商品进库存/商品市场（用户明确"像运力、不能存到第二 tick"）；把 19 格世界平衡写死进全局 `GovRules`。

---

## 2. 三层模型（不要把三者混成一个）

```
① 编制计划（政府预估，可自行决定）
     = 目标劳动/人数（治安、公文两维，可细分到 3 档）+ 需求静态修正 + 需求动态修正（模块注入）
② 岗位供给（实际到账）
     = 政府自家户承诺（先填）+ 外来家户承诺（缺额由决策人显式招募）+ 招募两路
③ 效率判定
     = 实际承诺劳动 vs 计划需求（超编开方、供给/需求修正、两维相乘、不封顶）
```

★ 关键：**① 是政府决策，不是公式常量**。默认给"按辖区人口的建议值"（由 `GovDemand` 算），政府可覆盖成任意目标；
公式永远用"计划需求量"，不用"建议值"。缺口（供给 < 计划）**只告警**，不自动招。

---

## 3. 公式（冻结）

每维 `d ∈ {治安 security, 公文 paperwork}`；`P_d` = 编制计划需求量（劳动单位：毫小时/tick）、`S_d` = 实际承诺劳动。

```
需求劳动_d  = P_d × 需求静态修正_d‰ / 1000 × 需求动态修正_d‰ / 1000
供给劳动_d  = Σ(该维岗位上的承诺小时)            # 全职承诺；自家户 + 外来户同形
超额_d      = max(0, 供给劳动_d − 需求劳动_d)
有效劳动_d  = 供给劳动_d                                    , 供给 ≤ 需求
            = 需求劳动_d + ⌊√(超额_d ÷ 岗位定额_d × k)⌋ × 岗位定额_d , 供给 > 需求
满足率_d    = 有效劳动_d × 1000 ÷ 需求劳动_d               # 需求=0 维 = 1000‰（无需求=全额）
效率_d‰     = 满足率_d × 供给静态修正_d‰ / 1000 × 供给动态修正_d‰ / 1000
总行政效率‰ = 效率_治安‰ × 效率_公文‰ ÷ 1000
```

- **无挂岗位家户** ⇒ `S_d = 0` ⇒ `效率_d = 0`（总效率 0），并记具名 INFO（不是静默 0）。
- **全不封顶**：修正、比值、总效率都不设上限；一切乘法用 `Math.multiplyExact`/`addExact`，溢出 ⇒ 具名 ERROR + fail-closed。
- `k`：开方系数，默认 1，存 gov 源状态、GM 可改（也允许模块注入？——V1 只读 GM 值，动态部分走动态修正）。
- 标准劳动系数（需求人数 → 劳动量）：用 Social 侧标准劳动力默认（ADULT/MALE = 16,000 毫小时/tick）作为**岗位定额**来源；
  两个同名默认（`SocialProvisioning` 与 `HouseholdLaborTimeTable.DEFAULT`）必须收口为一处（C8）。
- 旧口径变化：**删除** `GovEfficiency` 的 `min` 合成与超编加成；`GovOfficeState`/`Efficiency` 的 1000/1100/100 上限全部拆掉（C3）。

---

## 4. 数据组件（冻结名）

### 4.1 gov 侧（新增源状态；与 `GovOfficeState` 派生读数分离）

| 组件 | 键 | 内容 | 写口 |
|---|---|---|---|
| `GovAdministrationPlan` | `UnitId`(GOV) | 两维计划需求量 `P_d`；3 档岗位目录（每档两维权重）；供给静态修正 ×2‰；需求静态修正 ×2‰；`k` | 新 gov 命令（gov 首个 handler）；GM 工具 + 决策人工具（审批链） |
| `GovBudgetPolicy` | `UnitId`(GOV) | 有序支出类别表（含每类 min/cap）；官吏工资规则（每承诺小时粮/银） | 同上 |

- `GovState` 由 `Map<UnitId, GovOfficeState> offices` 扩为"派生读数 + 两条源状态"；`GovDaily`/app 写入派生值时**必须原样带过源状态**（照 economy `periodicAdjustments` 的拷贝纪律）。
- 旧档缺源状态 ⇒ 中性默认（计划空/建议值、修正 1000‰、`k=1`、预算空=不自动付）。

### 4.2 economy 侧

| 项 | 形状 | 说明 |
|---|---|---|
| `Industry` 版本 | **新版本 = 新 `IndustryId`**：id 仍是 `<kind>@<q>_<r>`，版本写进 kind（`office`、`office_v2`…） | 不新增 record 字段、不改 Codec 形状；`IndustryHexKeys.hexKeyOf` 解析不受影响；老 unit 引用老 id，零影响 |
| 岗位承诺 | 复用 `HouseholdLaborCommitment`，加 `kind`（`PRODUCTION` / `GOV_SERVICE`），旧档缺省 `PRODUCTION` | 一张承诺表、一个不变量 `Σ commitments ≤ laborMilli`；`GOV_SERVICE` 承诺**不可缩、最高优先级**（C7） |
| 服务流量 | app 进程内 `GovServiceFlow`（逐 tick 产生/消费/过期，不进库存/市场/ledger 存量） | 同形先例：`MarketReport`/`MarketReadout`、`MerchantFirm.capacityPerRound`；`GovOfficeState` 只存**当日读数**供显示，不作次日输入 |
| 政府生产 unit | `ProductionProcess`/`ProductionEnterprise`：operator = `hh-gov-<unitId>`（HOUSEHOLD）；industry = office 版本 id；relations/assetShares 按模板 | 创建/更新走新命令或组合工具（Z1b），不靠 Seed/ClearRegion |

### 4.3 unit / social 侧（一处真相）

- **承诺是权威（C）**：`GovernmentFormation.staff` 与 `governmentPostsOfHousehold` 都降为**派生投影**（由承诺/岗位户现算）。
- 官吏住 `hh-unit:<有单位 id>` 岗位户（不是 `hh-gov-*`）；`Unit.households` ↔ Social 位置由 `HouseholdUnitConsistency` 同步。
- 3 档岗位：档位目录在 `GovAdministrationPlan`；岗位指派（家户→档位/维度）写 `governmentPostsOfHousehold`（扩展字段或按档位表映射）。
- **外部岗位（用户 2026-10-23 裁定，Z3d）**：`GovernmentFormation` 新增 `externalPosts`（键 = HouseholdId，
  **不要求 ∈ `Unit.households`**；与内部 `governmentPostsOfHousehold` 互斥）；外部户**保留** Social 位置与单位归属，
  只承接行政任务；供给桥 / tier 一致性 / `openPostsToMarket` 工具都覆盖它。
- 要求：任何 `withGovernment*` 重建点不得漏带 posts/源状态（既有"最贵教训"纪律）。

---

## 5. 命令与工具（GM 版 + 决策人版；决策人走 GM 审批链）

| 工具 | 管什么 | 谁 | 备注 |
|---|---|---|---|
| `economy.upsertIndustry` / `simos.economy.upsertIndustry` | 创建/修改产业模板（office 等） | GM（V1）；决策人后续可包装 | **Z1a 先做**；新版本=新 id；原地改仅限"无 unit 引用" |
| `simos.gov.setEstablishment` | 编制计划：两维目标量、3 档权重、供给/需求静态修正、k | 决策人（审批链）+ GM | 新 gov 命令 |
| `simos.gov.setBudgetPolicy` | 预算类别顺序 + min/cap；官吏工资规则 | 决策人（审批链）+ GM | 新 gov 命令 |
| `simos.gov.assignPosts` | 自家户→岗位/档位指派、承诺小时 | 决策人 + GM | 写 posts + 承诺 |
| `simos.gov.expandHousehold` | 招募路径 A：新建/扩充政府家户（`hh-unit` 岗位户） | 决策人 + GM | 显式，不自动 |
| `simos.gov.openPostsToMarket` | 招募路径 B：岗位对外开放（名额/资格/工资/开关） | 决策人 + GM | V1 走队列/laborSources；V3 升级应募原语 |
| `simos.gov.dismiss` / `retireStaff` | 解职/退休（现有工具按 C1 改向 `hh-unit` 岗位户） | 决策人 + GM | 释放承诺 |
| `simos.gov.transferTreasury` | 国库注资/政府间转账（具名、可审计） | GM（决策人版走现有 `gov.pay/remit`） | F2 前置 |
| `simos.gov.info` | **只读查看**：计划 vs 实际、两维效率、承诺、岗位/档位、国库、预算、告警 | GM + 该 GOV 决策人（视野收窄） | 复用 `ApiViews.economyGovernment`；MCP 读工具当前缺 |
| 效率**动态**修正（供给/需求 × 两维） | **不是工具**：app 逐 tick 注入接口，GM 不可达 | 其他项目内组件 | 与经济的 `updateProductionModifiers` 同形 |

**权限纪律**：决策人工具照 `GovPayTool` 形态（身份派生 + 决策人桶 + `DecisionCallerFactory.WHITELIST` + 审批链
`AutoApproveGate → ConfirmGate → PendingApprovals`）；GM 侧并列一条同命令工具。权限不得放大。

---

## 6. 招募两路（都显式，由决策人选择）

| 路 | 做法 | 优点/代价 |
|---|---|---|
| **A 扩充政府家户** | 在 GOV 座位/辖区新建或扩充官吏户（`hh-unit` 岗位户），挂档位、承诺 | 政府完全可控；成本=人口/粮食从来源家户转出 |
| **B 外来家户承担行政任务** | 开放 gov 服务 unit 的岗位，其他经济家户以承诺加入、按工资规则领酬 | 不用自己养人；工资/资格/竞争要设计（V1 队列 MVP，V3 应募原语） |

- 决策人可只选 A、只选 B、或混合；**招多少人、招不招**都由决策人定。
- 两路的家户在经济数据上**同形**（都是 `GOV_SERVICE` 承诺），只是来源与生成路径不同。
- 现有 `simos.gov.recruit/selectExaminees/absorb/retire` 按 C1 改向；旧档数据迁移策略在 Z4 定。

---

## 7. 告警（只给 GM/决策人警报，绝不自动解决）

复用/扩展 `GovDaily.SignalDraft → HexCrisisSignal`（现有 `ADMIN_SUPPLY/ADMIN_SECURITY/ADMIN_PAPERWORK`），触发条件：

1. 供给 < 计划（按维、按档）；
2. 岗位空缺无法填（无自家户可扩、外来无人应募）；
3. 国库不足按预算优先级支付（工资/俸禄/军俸）；
4. 编制计划未设或全 0；
5. 服务流量为 0（无承诺劳动）；
6. 契约异常（承诺越界、修正溢出、岗位户位置不符等）⇒ ERROR + 告警。

通道：告警进现有危机信号/读口，GM 与对应 GOV 决策人可见；**不触发任何自动招募、自动注资、自动调计划**。
仓库既有裁定"禁止自动筛选/自动派发 DM"继续有效。

---

## 8. 服务产出（逐 tick 流量，运力同形）

- 产出不是商品：不进 `HouseholdInventory`/库存/商品市场/ledger 存量；当 tick 产生、当 tick 被效率消费、未用完即失效。
- 承载：app 进程内 `GovServiceFlow`（`unavailable` 具名，照 `MarketReport`）；`GovOfficeState` 只持久化**当日读数**（供查看），
  公式与次日结算**不得**把昨日读数当输入。
- office 服务产业：空 `outputPerUnit`；`capacityPerUnit` 用岗位/办公资产锚（具体资产种类在 Z1b 定）；
  `laborPerUnit`/`cycleDays` 作为岗位槽的载体（3 档岗位 = 模板里的 slots/positions，或由 `GovAdministrationPlan` 覆盖）。

---

## 9. 工资与国库预算

- **工资**：新 `ADMIN_SALARY` 扣款/发薪语义（`DeductionReason`/转移 reason 词表新增）+ salary bridge（照 `MilitaryPayRuleBridge`），
  按**承诺小时**从 `hh-gov-*` 国库账户付给官吏户；不足 ⇒ 逐腿具名缺口，不静默 0。
- **预算优先级**：`GovBudgetPolicy` 有序类别 + 每类 min/cap；类别默认建议：
  `行政俸禄 → 军俸 → 行政工资 → 债务 → 其他`（最终由该 GOV 决策人调）。
- **注资**：F2（国库零注资）先解决——G1 具名 `transferTreasury` + bootstrap 初始注资 + `seigniorage`/铸币按后续裁定；
  V1 不做 M1 铸币生产。

---

## 10. C1~C9 冲突处置（可行性报告 §0.4）

| # | 冲突 | 处置（本文冻结） |
|---|---|---|
| C1 | 现有 recruit/retire/absorb 把官吏转入 `hh-gov` | **改向 `hh-unit` 岗位户**（Z4）；旧档迁移策略 Z4 定，不静默双轨 |
| C2 | gov 模块看不见承诺劳动 | 供给由 app 计算后作为 `GovEfficiency` **入参**传入（gov 保持纯函数 + 新增入参），Z2/Z3 |
| C3 | `GovOfficeState`/`Efficiency` 封顶 | 拆掉 1000/1100/100 上限，改 long 无上限 + `Math.*Exact`（Z2） |
| C4 | staff/posts/承诺无一处真相 | **承诺权威（C）**，staff/posts 派生（Z4） |
| C5 | 产出必经库存/市场 | 服务流量走进程内 `GovServiceFlow`（Z3） |
| C6 | 没有劳动力市场 | V1 复用劳动队列/`laborSources`；V3 应募原语；商品市场伪商品永久不做 |
| C7 | 承诺会被队列按比例缩 | `GOV_SERVICE` 承诺不可缩、最高优先级；先扣承诺再排生产（Z1b/Z2/Z3） |
| C8 | 16,000 两个默认拼写点 | 收口为一处"标准劳动系数"权威（Z1b/Z2 定名） |
| C9 | 决策人工具落点 | 照 `GovPayTool` 形态 + 审批链；GM 并列工具（Z3） |

---

## 11. 责任区与依赖（AGENTS §一.5/§一.8/§一.10）

| 区 | 目标（可独立编译验收） | 模块 | 依赖 |
|---|---|---|---|
| **Z1a** | `economy.upsertIndustry`：创建/修改产业模板（新版本=新 id；原地改仅限无引用；守卫/负向）+ GM 工具 + catalog | economy、economy-api、app | Z0 |
| **Z1b** | 承诺基础设施：`HouseholdLaborCommitment.kind`（`PRODUCTION`/`GOV_SERVICE`，旧档缺省 `PRODUCTION`）+ `GOV_SERVICE` 不可缩/最高优先级（C7）+ 标准劳动系数收口（`SocialProvisioning` 为唯一权威，C8） | economy-api、economy、social、app | Z1a |
| **Z1c** | GOV 生产 unit：创建/更新 `office` 产业的 gov unit + assetShares + relations（operator=hh-gov） | economy、economy-api、app | Z1a、Z1b |
| **Z2** | gov 源状态 `GovAdministrationPlan`/`GovBudgetPolicy` + gov 首个 handler + 效率公式（两维、开方、修正、不封顶、溢出 ERROR）+ `GovOfficeState` 去上限 | gov（+app 工具后续 Z3） | Z1b（承诺契约冻结即可开工，接口以本文为准） |
| **Z4** | unit/social 一处真相：3 档岗位目录与指派、官吏户 `hh-unit`、staff 派生、recruit/retire/absorb 改向（C1）、`withGovernment*` 拷贝纪律 | unit、social（如需）、app | Z0；与 Z2 的 role 契约对齐 |
| **Z3** | app 编排：`GovServiceFlow`、承诺→供给桥、`ADMIN_SALARY` + salary bridge、预算优先级执行、告警、决策人/GM 工具 + 审批链、`simos.gov.info` 读工具 | app、economy-api（reason）、economy（若按劳动量工资） | Z1b+Z2+Z4 |
| **Z3d** | 外部岗位表示（`GovernmentFormation.externalPosts`）+ 供给桥/一致性纳入 + `openPostsToMarket` GM/决策人工具（外部户保留归属） | unit、gov、app | Z3c-2（Z3 app 部分） |
| **Z5** | 19 hex bootstrap：GOV/官吏户/office 资产/承诺/注资/计划（复用 G1/G2） | app | Z1b~Z4 |
| **Z6** | 统一测试与真档证据：往返/边界/负向/守恒、全仓 `clean verify`、360 tick 对照 | tests | 全部 |

顺序：**Z0 → Z1a → Z1b → Z2 → Z1c → Z4 → Z3a → Z3b → Z3c-1 → Z3c-2 → Z3d → Z5 → Z6**（一次一个写代码代理；Maven 统一 `tools/mvn-lock.sh`）。

---

## 12. 验收与测试要点（Z6 最小集）

1. `upsertIndustry`：新版本不改变老 unit 逐值行为；原地改被引用 ⇒ 具名拒；坏载荷/版本倒退/坏 hex ⇒ 拒；往返稳定。
2. 承诺：`GOV_SERVICE` 不可缩、最高优先级；`Σ commitments ≤ laborMilli` 不变量；旧档缺 `kind` ⇒ `PRODUCTION` 读回。
3. 公式：逐值可复算；无挂户 ⇒ 0 + INFO；超编开方（k 可调）；供给/需求静态+动态修正；**全不封顶**；溢出 ⇒ ERROR。
4. 服务流量：同 tick 产生/消费；重启后不冒充有数据（`unavailable` 具名）；不进库存/市场/ledger。
5. 工资：按承诺小时付款、国库不足逐腿具名缺口；预算类别顺序/min/cap 生效。
6. 告警：六类触发条件各一条用例；**无自动招募/自动注资**（负向断言）。
7. 权限：决策人只能操作自己 GOV（越权拒）；审批链必经；GM 工具并列；读工具视野收窄。
8. 旧档/迁移：staff/posts/承诺三表迁移可读回；`hh-gov` 仍 0 人口。
9. 真档：19 hex tick0 后 GOV 有 office unit、官吏户有承诺、国库有初始库存、效率非 0；360 tick 对照报告。

---

## 13. 明确不做（V1 边界）

- 商品市场伪商品；行政产出入库/跨 tick 存储；自动招募/自动注资/自动调计划；M1 铸币生产；多级政府编制联动；
  真正的岗位应募/竞价原语（V3）；GUI `/gov` 写面（读口先做，写面另批）；天气/日历/文化接入。

---

## 14. Z0 阶段开放项（控制方默认，用户审文可改）

1. **"扩充政府家户"**默认 = 新建/扩充 `hh-unit:<govUnitId>` 岗位户（不往 `hh-gov-*` 加人，遵 0 人口裁定）。
2. **3 档默认权重** = 档1 (100,0)、档2 (0,100)、档3 (50,50)；档名/是否改为 YAMEN/SCRIBE/POST 词表由用户定。
3. **"挂到市场"V1** = 复用劳动队列/`laborSources`；V3 再做应募原语。
4. **预算默认顺序** = 行政俸禄 → 军俸 → 行政工资 → 债务 → 其他。
5. **`k` 默认 1**，GM 可改（gov 源状态）。
6. **编制计划默认值** = 按 `GovDemand` 建议，政府可覆盖；公式永远用计划值。
7. **服务产出承载** = 空 `outputPerUnit` + 进程内 `GovServiceFlow`。
8. **承诺落点** = 同表 + `kind`；若 record 改动不可控 ⇒ 报 BLOCKED 由控制方另裁。

---

## 15. 引用

- 可行性报告：`docs/superpowers/reports/2026-10-23-gov-service-mode-feasibility.md`（§0.4 C1~C9、§2 复用、§3 逐问题、§6 11 条裁定、§7 自证）
- gov 税/国库排查：`docs/superpowers/reports/2026-10-23-gov-tax-treasury-investigation.md`（F1~F4、G1~G7）
- 经济可编辑性/效率调查：`docs/superpowers/reports/2026-10-23-editability-and-efficiency-investigation.md`
- 日志纪律：`AGENTS.md` §一.9；责任区委派：§一.5/§一.8/§一.10；用户原话附录要求：§一.8.1

---

## 16. 控制方收尾裁定（Z1a 实际落地，2026-10-23）

1. **幂等重放 = no-op**：载荷与既有模板逐值相同 ⇒ 返回空变更集、不落 revision，**即使该 id 被 unit/份额/关系引用也不拒**
   （照 `upsertProductionMode` 先例）。"被引用 + 任何实际值变化 ⇒ 具名拒"这条危险路径未被放松。接受。
2. **版本口径**（Z1a 新增约定，正式冻结）：同格同 base kind 按**最大版本**比较；倒退、同版本撞号 ⇒ 具名拒；
   允许跳版本；**新载荷**的 `_v0`/`_v01` 等非规范版本拼写严格拒，旧档既有 id 宽容解析（不得把旧档读成载荷错）。
   新版本 = 新 kind 后缀 id（如 `office_v2@0_0`）；`Industry` record/Codec/ChangeSet 形状不变，老 unit 零影响。
3. **Z1b 拆分**：原 Z1b 拆为 **Z1b（承诺基础设施：kind/不可缩/标准系数）** 与 **Z1c（GOV 生产 unit+资产+关系）**，
   理由＝两类风险不同（承诺 record 改动触及 labor 不变量；unit 创建触及命令/状态/工具）。顺序：
   `Z1a → Z1b → Z2 → Z1c → Z4 → Z3 → Z5 → Z6`（Z1c 与 Z2 无代码依赖，但仍串行跑 Maven）。

---

## 17. 控制方裁定（Z1b 收尾边界，2026-10-23）

1. **旧档兼容只要求 `EconomyCodec` 边界**：`decodeSnapshot`/`decodeChangeSet`（含 patch 变体）缺 `kind` ⇒ `PRODUCTION`、
   新档往返逐字节稳定即可；**不要求** core `Timeline`/`Replay` 的 raw store 重放兼容（core 的 `WorldChangeSet`
   不经过 EconomyCodec，pre-Z1b store 的 `changeset_json` 会当场失败——与 S1 加 household 同一条既有限制）。
   **不**为此外开 core SPI、**不**给 economy-api 加 Jackson 注解（不触铁律 4 与"economy-api 零注解"纪律）。
   Z6 测试范围**不含** raw store 重放；若以后要补，另开小批并先裁定。
2. **C7 死亡缩放豁免记入 Z3 必做项**：Z1b 的 `GOV_SERVICE` 保护只落 `LaborQueueSettlement`；`EconomySettlement`
   的死亡比例缩放（`scaleLaborOfUnit/Group`）与 modes 为空的旧 `reallocateLabor` 对 `GOV_SERVICE` 的豁免，
   在 Z3 接入 gov 写者前必须处理，口径 = **死亡也不缩 `GOV_SERVICE`；超预算 ⇒ 具名 ERROR fail-closed**。
   当前（Z1b 完成时）无写者能在 modes 为空世界产生 `GOV_SERVICE`，故不构成回归。

---

## 18. 控制方裁定（Z1c 模块边界，2026-10-23）

`simos-economy` 的 enforcer 禁依赖 `simos-unit` ⇒ economy handler 编译期看不见 `Unit`/`GovernmentFormation`。
裁定 **A**（不改模块边界、不把 handler 移 app、不新增跨模块 accessor）：

1. **economy handler 的守卫**只用经济侧可见面：`economy.governments` 有 `gov-unit-<id>` 且 treasury =
   `HOUSEHOLD:hh-gov-<id>`（现世界"走过 unit.CreateUnit+SetGovFormation+RegisterGovernment"的经济侧证据）+
   `classes` 有 `hh-gov` 行 + Social 家户表有 `hh-gov` + `industry` base kind=`office`/格已激活/assets
   覆盖 `capacityPerUnit` 1 单位规模；全部 fail-closed、具名拒。
2. **app GM 工具 `simos.economy.upsertGovUnit`** 在 **preview 与 apply 两条路径都**做 unit 切片预检
   （unit 存在 + `module()` 是 `GovernmentFormation`），不满足 ⇒ 工具折 BAD_REQUEST/Rejected，不提交。
3. **已知边界**：GM 裸 `simos.command.submit` 可绕过 app 工具的 unit 切片预检，只过 (1)。接受为边界；
   **Z4 必做**：app 组合根跨切片一致性检查（gov service unit 的 operator GOV 缺 `GovernmentFormation`
   ⇒ 具名拒/告警）；**Z6 必做**：对应负向测试。

---

## 19. 控制方裁定（Z1c 收尾，2026-10-23）

1. Z1c 的 `ProductionRules.laborSource = SELF` 按"最小落地"接受：空规则 + 空产出下它不参与任何分布；
   **Z3 必做**：接入 `GOV_SERVICE` 承诺与 `ADMIN_SALARY` 时复核 `SELF` vs `WAGE`，并核
   `ProductionEnterprise.laborSources`（自家户 / 外来户两类来源）的语义，不得让该字段与"官吏领薪、外来家户受雇"矛盾。
2. §18 的 Z4/Z6 必做项维持：组合根跨切片一致性检查（gov service unit 的 operator GOV 缺
   `GovernmentFormation` ⇒ 具名拒/告警）+ 对应负向测试。
3. Z1c 的其余口径（payload、守卫、四表最小写入集）按台账冻结，不再改。

---

## 20. 控制方裁定（Z3c-1 收尾，2026-10-23）

四条 V1 偏差**接受**：

1. **预算 min/cap 到腿语义** = 每日预算周期 + 1:1 毫价值 + 固定腿序 grain→cloth→silver + 低优先级 min 预留；
   V1 冻结。若要"每腿独立 cap / 比例分配"，另开批次。
2. **告警 1/2 的 V1 代理判据**：供给<计划按维覆盖率；岗位空缺按 `demandLabor>0 && supply=0`。
   按档需求/招募状态就绪后再细化（Z5/Z6 或后续批次）。
3. **致命契约异常仍 ERROR+ISE、无持久信号**（整次不落 revision）：接受，与既有 fail-closed 口径一致。
4. **中性空预算 = 不自动付 + `ADMIN_PLAN_MISSING`**：接受（§4.1 明文）；旧世界行为变化由 Z5 bootstrap 与 Z6 迁移覆盖。

另：`ADMIN_UPKEEP` sink 与 `ADMIN_SALARY` transfer 并存（类别/reason 分离），接受。

---

## 21. 控制方裁定（外部岗位，2026-10-23，用户已选）

B 路"允许外来家户承担行政任务"采用**新建外部岗位表示、保留外部归属**：

1. `GovernmentFormation` 新增 `externalPosts`（`Map<HouseholdId, GovernmentPostOfHousehold>` 或等价；键**不要求**
   ∈ `Unit.households`，与内部 `governmentPostsOfHousehold` **互斥**；household 必须在 Social/economy 存在）。
2. 新命令只写 `externalPosts`，**不改** `Unit.households` / Social 位置（建议 `unit.AssignExternalGovPost`，或扩展
   `unit.AssignGovPost` 带内外模式；Z3d 冻结）。
3. Z3b 供给桥纳入 `externalPosts`（tierId → `GovAdministrationPlan.postTiers` 同一套权重）与对应 `GOV_SERVICE` 承诺；
   `GovernmentPostTierConsistency` 覆盖 `externalPosts`。
4. `simos.gov.openPostsToMarket` 随 Z3d 一起做：V1 = 显式选户 + 挂外部岗位 + 承诺（GM + 决策人审批链）；
   队列/应募自动化留 V2/V3。
5. 责任区 **Z3d** 插在 Z3c-2 之后、Z5 之前；Z3c-2 不实现 `openPostsToMarket`、不自行造 externalPosts。

---

## 22. 控制方裁定（Z6a 中期发现，2026-10-23）

1. **税收端效率上界必须拆除（设计明文）**：`JurisdictionDailyTax` 仍把 GOV 效率卡在 `[0,1100]`，与 §3/§10 C3
   及用户"都不封顶"直接冲突。授权 Z6a 做最小生产修复：去掉 1100 上界、只保 `≥0`（+溢出保护），并排查全仓
   main 里其它"行政效率/覆盖率/修正"同类上界（属于该管道的按同一裁定拆除并台账列明）。修复记入 Z6a 台账
   "生产改动"节（设计依据 + 复现命令）。
2. **`EconomyCodec` 显式 `"kind": null` = `PRODUCTION`：接受**（与"缺键 ⇒ PRODUCTION"同口径；显式 null
   视为缺省，不 fail-closed）。z1b 台账"显式 null 拒"的措辞更正为"缺键或显式 null ⇒ PRODUCTION"；不改代码。

---

## 23. 控制方裁定（run6 发现，2026-10-23，用户已选）

1. **D1 = 辖区互斥：中央座位不入省辖**（用户当选）。实施方式（独立 central 区域 vs 辖区逐 hex 排除）由 Z7 排查/设计定；
   **不采用**"政府家户一律免税"。
2. **D3 = 税基保持现状**：存量税 + 每日评估（指数抽干式）视为特性，本轮**不做**最低口粮保护/流量税；
   债务累积达标即目标。后续若要改税基另裁。
3. **下一批 = 税制/财政闭环**：D1 辖区互斥、D2 预算 oracle 与实扣同源、D4 军俸部分支付语义、
   中央/省财政闭环（上级拨款/remittance 方案）、run7 360 tick 验证；F3（M1/铸币）与 F4（满覆盖标定）不在本批。
