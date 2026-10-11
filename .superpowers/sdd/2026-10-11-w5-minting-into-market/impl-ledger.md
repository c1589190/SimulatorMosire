# W5（钱的来源 / 铸币入市）—— 实现架构账本（2026-10-11）

> 责任区：约束设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.14 §6.1 的 W5 行**（原 R6 + 接管 E1）。
> 写码 Agent 交账：只写生产代码、只到"编译过"；不写/不跑测试；不 commit。
> 状态：**DONE（编译过）** 但**两项运行期行为未验证 + 一项判据 §6.1-④（必须经市场挂单入市）BLOCKED**，逐条见 §5。

---

## ① 关键调查结论（file:line → 结论 → 影响）

| # | 证据（file:line） | 结论 | 影响 |
|---|---|---|---|
| I-1 | `GovernmentSeigniorage.java:89-106`（旧） | 周期铸币把 `seignioragePerCycle` **直接 `treasury.replaceMoney(...)`** 加进国库余额，不产生 Transfer、不碰市场 | 用户点名的"印钱绕过市场"旁路本体 ⇒ 整类删除 |
| I-2 | `GovernmentDebtIssuance.java:66-165`（旧） | 第二条财政轨道：周期开始从家户**借**钱（真实转移 + `DebtContract`），与 I-1 并列 | 双轨的另一半；§一.11（旧设计直接删）⇒ 整类删除 |
| I-3 | `Government.java:41-42`（旧） | 两个旋钮 `seignioragePerCycle` / `debtIssuePerCycle` 是唯一的财政政策面 | 合并成 `mintScalePerCycle`（一个旋钮） |
| I-4 | `EconomySettlement.java:1170-1190`（旧） | 两条轨道都在**周期开始日**（`isCycleStart`）被调用，时点是"任何转移/市场之前" | 铸币新时点 = **产业关账处**（与粮/布同一次 `evaluateHarvest` 的 scale），删掉周期开始那一段 |
| I-5 | `EconomySettlement.java:1884-1935` (`harvest` 收集段) | 关账处求好的 `efficiency.scale()` 是**投入已真实扣过之后**的规模（`drawCycleInputs` 已写 `unit.cycleInputUsedMilli()`），且是投入/劳动/产能三路取小的结果 | ★ 铸币读**同一个 scale** ⇒ "投入真实消耗、产出 = 货币"两半结构上不可能漂开（判据 ①） |
| I-6 | `EconomySettlement.java:8883-8902` (`harvest`) | 商品产出的入账口径 = `gross = scale × outputPerUnit × 1000`（毫），净产经 `creditOutput` 计进账户主体家户的**货物账** | 货币产出必须是**另一条腿**（账户余额，不是货物账），且量纲不再是 ×1000（货币最小单位就是毫） |
| I-7 | `ProductionRecipe.java:51-55`（旧） | `recipe()` 是配方的唯一派生视图（`capacity/input/labor/output` 四路） | 铸币产出率随 `recipe()` 一起派生（`Industry.recipe()` 单点拼写），不另开读口 |
| I-8 | `MarketSettlement.java:1049-1079`（旧 `withDeferredPayments`） | 六个 wither 里**唯一**漏带 `portEnforcement`/`portTax`/`procurementPriority` 的那一个（只带 arbitrage/fx/govMandates） | 按 fail-closed 修（带全 + 运行期守卫），见 §⑦ |
| I-9 | `EconomySettlement.java:9538-9562` (`debitHouseholdMoney`) | **FISCAL_ISSUE 单边发行腿仍活着**：付方 = 发行源（政府国库）且带 `MoneyIssuanceJournal` 时允许透支并记审计 | ★ "除 `simos.gov.issueMoney` 之外没有第二条凭空加钱路径"这条**要按代码事实说清**：还有两条**已具名、已审计**的腿（见 §⑥） |
| I-10 | `MarketSettlement.java:6968/7045/7078/7160` | 市场成交的 `applyTransfer(...)` 调用**不带 journal** | ⇒ 市场路径**结构上不可能**触发 FISCAL_ISSUE（`journal == null` ⇒ 当场抛）⇒ 国库户在市场里花不出"透支的钱" |
| I-11 | `GovernmentMarketMandatePlan.java:26-40,172-174` | **国库家户不生成任何自动订单**（只按 GM 授权下单） | ★ 铸币产出**不会**自动变成市场挂单 ⇒ 判据 §6.1-④"产出货币必须经市场挂单流入"在本批**结构上不成立**（见 §5 BLOCKED-1） |
| I-12 | `MoneyIssuanceRecord.java:32-40` | 审计记录字段 = `(id, governmentId, day, period, currency, amount, kind, reason)`；`period` 就是"属于哪个产业周期" | ★ "上一轮印量"**不必新开状态**：按 `(kind=MINT_PRODUCTION, period=当前周期−1, 政府)` 现算即可（约束设计书 §5.3-4 指的路） |
| I-13 | `MoneyIssuanceKind.java:14-17`（旧） | 只有 `INITIAL_ENDOWMENT` / `FISCAL_ISSUE` / `WITHDRAWAL` | 复用 `FISCAL_ISSUE` 会给"生产出一批钱"贴上"透支差额"的语义 ⇒ 新增具名 `MINT_PRODUCTION`（判据 ⑤ "kind 要具名"） |
| I-14 | `EconomySeeder.java:3690-3760`（旧，`governmentHousehold` 路径） | 只有"带政府家户"的世界（demo/small-world）才播政府；`governmentHouseholdGovernment()` 把两个旋钮写进政府 | ⇒ 铸币生产方式**只在这种世界**播种 ⇒ 别的世界（多国/多省 seed）逐值不变 |
| I-15 | `RegimeOperators.BY_REGIME`（`RegimeOperators.java:66-80`） | 不登记 `merchant` 之外的"显式 operator"制度：未登记 ⇒ `defaultOperator` fail-closed | 铸币产业必须**显式给 operator**（政府家户 actor），不能靠 regime 默认 |
| I-16 | `EconomyPayloads.java:1494-1502`（旧 `industry(JsonNode)`） | 产业载荷解析**只读 12 个字段**（+ 4 个旧档字段），构造走 12 参构造器 | `moneyOutputPerUnit` 必须是**可选键**（缺省 = 空表 = 不铸币）⇒ 旧载荷逐值不变（I-C2） |

---

## ② 我自己的实现架构

### 数据流（一次关账日）

```
EconomySettlement.settleOneDayInto(day)
  ├─（周期开始那一段：GovernmentSeigniorage / GovernmentDebtIssuance 调用 —— ★已整段删除）
  ├─ 关账循环：progressed >= cycleDays
  │     └─ ProductionEfficiencyBook.evaluateHarvest(...) → scale   ← 投入已真实扣过（drawCycleInputs）
  │        if (!industry.moneyOutputPerUnit().isEmpty()) closedUnitScales.put(unitId, scale)
  ├─ harvestPartitioned(...)                                        ← 商品产出照旧
  │  （铸币的劳动在**播种期**已由 seedGovernmentMint 从本格城镇池的剩余预算里发过一条配额；
  │    运行时 laborByUnit 按 unit 取用它 ⇒ 规模公式的"劳动那一路"真的咬合）
  └─ MintSettlement.settleCycleClose(dayView, session, accounts, day, currentCycle, closedUnitScales)
        for each government（基态表序）
          previousMinted = Σ MoneyIssuanceRecord{kind=MINT_PRODUCTION, period=currentCycle-1, gov}
                           （空 ⇒ 首轮启动额 = mintScalePerCycle）          ← I-12
          limit = min(mintScalePerCycle, previousMinted)
          for each 关账 unit whose industry 铸币 and operator == 该政府国库家户
            requested = scale × moneyOutputPerUnit[c]                      ← 判据 ①
            requested > limit ⇒ INFO MINT_LIMIT_REJECTED（limit/requested/rejected/minted） ← 判据 ③
            minted = min(requested, limit)
            credit(): ① 国库账户余额 +minted（货币产出走余额）
                      ② MoneyIssuanceRecord(kind=MINT_PRODUCTION, id=mi-mint-<gov>-<day>-<cur>)
                      ③ DebtContractBook.upsert(债务=政府国库家户, 债权=组织者, unit=Money(c), amount=minted)
            TRACE MINT_CREDIT（逐笔） / INFO MINT_SETTLED（总量）
          旋钮=0 ⇒ DEBUG MINT_POLICY_ZERO 且一毫不动                              ← 判据 ②
```

### 新增/删除的类

| 类 | 处置 |
|---|---|
| `economy.model.MoneyOutput`（新） | `Map<CurrencyId,Long> perUnit`（毫/规模·周期）+ `NONE` + `grossOf(scale)`；保序冻结、逐值 > 0 |
| `Industry` 第 18 个组件 `moneyOutputPerUnit`（新） | 与 `outputPerUnit` **刻意同形**；保留 17 参/12 参两个旧形状委托构造器（旧调用点一字不改） |
| `ProductionRecipe` 第 5 个组件 `moneyOutputPerUnit`（新） | `Industry.recipe()` 单点派生；保留 4 参旧形状委托构造器 |
| `economy.time.MintSettlement`（新） | 铸币唯一结算口：上限闸门 + 三件事实（余额/审计/债务）+ INFO/DEBUG/TRACE 日志 |
| `GovernmentSeigniorage`（删） | 用户点名的旁路本体 |
| `GovernmentDebtIssuance`（删） | 双轨的另一半 |
| `Government.seignioragePerCycle` / `.debtIssuePerCycle`（删） | 合并成 `mintScalePerCycle`；另加结算事实 `mintedLastCyclePerCycle`（逐币种上一轮印量，判据 ③ 的可持久读数） |
| `MoneyIssuanceKind.MINT_PRODUCTION`（新） | 具名类别（不复用 FISCAL_ISSUE） |
| `MoneyIssuanceId.forMintProduction(gov, day, currency)`（新） | 确定性 id `mi-mint-<gov>-<day>-<cur>`；前缀与 `mi-`/`mi-wd-` 互不重叠 |

### 政策旋钮的 GM 写口（判据 ⑥）

**不新开工具** —— 复用既有的 GM 政策写工具族（`GmOnlyCommand` + 决策人桶/令/RegisterEffect 三条路径都排除 + 载荷提示 + 三桶齐）：

- 命令：`economy.RegisterGovernment`（`GmOnlyCommand`），载荷字段 `mintScalePerCycle`（缺省字段在既有政府上**逐值保留**，不给 = 不改）；
- 窄工具：`simos.economy.upsertGovUnit`（`EconomyUpsertGovUnitTool`，只在 GM 桶，`SimosToolSource.java:775`）；
- 载荷提示：`CatalogTool.java` 的 `economy.RegisterGovernment` 行（已把 `seignioragePerCycle/debtIssuePerCycle` 换成 `mintScalePerCycle`）；
- 读口：`ApiViews.economyGovernment`（`mintScalePerCycle` + `mintedLastCyclePerCycle`）；
- 产业侧（产出率与水路）：`simos.economy.upsertIndustry`（`EconomyUpsertIndustryTool`，已文档化 `moneyOutputPerUnit`）。

### 创世落点（让 W5 在真实世界可观测）

`EconomySeeder.seedGovernmentMint(...)`：在"有政府家户"的世界（demo / small-world）给政府家户播一个铸币产业 + 它的 unit + 产能份额 + 投入库存：

```
id = mint@<q>_<r>            regime = government_mint        cycleDays = CYCLE_DAYS
capacityPerUnit   = {WORKSHOP: 1}                    ← "单位规模"的锚（1 座铸炉 = 1 单位规模）
laborPerUnit      = MINT_LABOR_MILLI_PER_FURNACE(8000)     ← 吃劳动
cycleInputPerUnit = {WORKSHOP: {tool: 2000}}               ← 吃工具（真实消耗，走既有周期投入管线）
moneyOutputPerUnit= {<本格计价币>: MINT_MILLI_PER_FURNACE_CYCLE(500)}   ← ★ 产出 = 货币
unit.operator     = HOUSEHOLD:hh-gov-…（政府家户 = 组织者，用户原话）
政府家户库存      += 工具 × MINT_SEED_CYCLES(4)
劳动配额          ← 本格城镇池"作坊配额发完之后剩余的预算"里取 MINT_LABOR_SHARE_PER_MILLE(10‰) 的池日劳动
                    （不足 ⇒ DEBUG SEED_MINT_SKIPPED，产业与 unit 仍播但产出 0；★ 不缩作坊、不造第二份劳动）
```

---

## ③ 关键判断（为什么这样拆 / 推翻过什么）

1. **推翻"照抄 E1 的 `MintSettlement.settleCycleClose`（周期末按规模产出）"**：E1 的时点是**周期开始**（与 `GovernmentSeigniorage` 同一处），
   那样铸币读不到 `evaluateHarvest` 的 scale（那时还没算）。**改为挂在关账处、读同一个 scale** ⇒ 判据 ① 的"投入真实消耗"才有落点。
2. **推翻"货币塞进 `outputPerUnit`"**：三个理由（键是两个身份命名空间 / 入账走余额而非货物账 / 货币最小单位就是毫不再 ×1000）写在 `MoneyOutput` 类注。
3. **推翻"给 `Government` 只留一个 long 旋钮就够了"**：判据 ③ 要"上一轮印量"，而它**不能**从旋钮推（旋钮是请求、印量是事实）。
   两条路：新开状态组件 vs 从 `MoneyIssuanceRecord` 现算。**选后者**（约束设计书 §5.3-4 指的路），另加 `mintedLastCyclePerCycle`
   作为"最近一轮汇总"的**可读/可持久**面（固定大小 ⇒ 不破性能红线）。
   ★ 如实记：目前该字段只是**读数**，闸门仍按 `MoneyIssuanceRecord` 现算（两处口径同源；若将来只留一处，删字段即可）。
4. **推翻"用 `GovernmentMarketMandate` 让铸币自动入市"**：mandate 的量纲是**商品**（`CommodityId` + 限价 + 毫商品单位），
   而且它只能由 GM 写；把"铸币自动生成一条买入授权"塞进去 = 给政府加一条**自动**入市机制（用户口径是"必须市场家户参与市场行为"，
   不是"结算替它下单"）⇒ 不做。见 §5 BLOCKED-1。
5. **保留 `MoneyIssuanceRecord.period` 作为"上一轮"的判据轴**（而不是日窗口）：`period` 是写入时就有的权威字段，
   日窗口要另记"上一轮起止日"= 第二份真相。
6. **首轮启动额（控制方实现口径，不是用户原话）**：第一轮没有上一轮 ⇒ 严格 `≤ 上一轮印量` 会让铸币数学上永远启动不了（0 ≤ 0）。
   故：**上一轮一笔都没印 ⇒ 上限 = 政策旋钮本身**。写在这里当偏离记录。
7. **铸币的投入取"工具"而不是"金属"**：本世界词表没有贵金属商品（`EconomyVocabulary`：粮/纤维/布/铁/工具/运输服务），
   "消耗金属"在当前词表下**表达不出来**。取工具（铸范/模具）满足"真实消耗 + 有投入约束"两条，且形状不变（将来加金属只改那一张表）。
8. **债务合同的两个端**：债务 = **发行政府的国库家户**（印出来的每 1 毫都是它的负债），债权 = **铸币这一路的组织者**
   （新钱以"它产出的东西"的名分交到它手上）。常态世界里两者是同一户 ⇒ 合同是"自持"的记账事实（发行额 = 政府负债）。
   ★ 这是"货币本身是债务"在最小模型下的形状，**不是重复计债**。

---

## ④ 偏离记录

| # | 偏离 | 原因 | 影响 |
|---|---|---|---|
| D-1 | **首轮启动额 = 政策旋钮**（不是严格 `≤ 上一轮印量`） | 第一轮没有上一轮，严格口径下铸币永远启动不了 | 只影响创世后第一个铸币轮；此后每轮严格受上一轮印量约束 |
| D-2 | 铸币的劳动取自**本格城镇池"作坊配额发完之后剩余的预算"**（不是政府家户自己的劳动 —— 它人口为 0） | 政府家户是非生产家户（`population=0`）⇒ 劳动只能来自它落点格的城镇池；且**不许**再造一份劳动（会破"Σ 家户 PRODUCTION 配额 ≤ 时间预算"的构造期不变量） | 剩余不足 ⇒ **具名跳过**（DEBUG `SEED_MINT_SKIPPED`，产业与 unit 仍播、产出 0 毫）；拿的是剩余 ⇒ **作坊已有配额一字不动**；★ 会改变数值行为（见 N-10） |
| D-3 | 铸币**必须显式给 operator**（`RegimeOperators.BY_REGIME` 不登记 `government_mint`） | 不登记的制度按既有 fail-closed 走；显式给 operator 是正确的既有形态（同 `trade`） | 播种路径显式给 `HouseholdActors.of(hh-gov-…)` |
| D-4 | `Government.mintedLastCyclePerCycle` 目前只是**读数**（闸门仍按 `MoneyIssuanceRecord` 现算） | 两处口径同源；保留字段是为了让"上一轮印量"在 API/读口可见（判据 ③ 的可审性） | 若 W6 判定冗余，删字段即可（不改闸门语义） |
| D-5 | `MintSettlement` 对每个政府 × 每个关账 unit 做一次匹配（O(G×U)） | 关账日才跑、world 规模小（1~3 政府 × 几十 unit）；先要正确性 | 若 W6 性能红线报警，改成按 operator 建索引即可 |
| D-6 | 铸币产出的"入市"**只做到"钱进了国库户 + 可被授权挂单支付"**，没有自动挂单 | 见 §5 BLOCKED-1（I-11 的结构性原因） | **判据 §6.1-④ 未满足** |
| D-7 | 未跑任何运行期验证（只到 `compile`） | 任务书 §5.1 只允许 `spotless:apply` + `-DskipTests compile -am` | 判据 ①②③⑤ 的**运行期数字**未取（见 §5） |

---

## ⑤ 未完成 / 未验证项（如实）

- **BLOCKED-1（判据 §6.1-④"产出货币必须经市场挂单流入"）**：
  **结构上不成立，不是没写完**。`GovernmentMarketMandatePlan` 明确"国库家户不生成任何自动订单，能出现的订单全部来自
  `EconomyData.govMarketMandates` 的明确授权"（I-11）；而 `GovernmentMarketMandate` 的量纲是**商品**（`CommodityId` +
  限价 + 毫商品单位），**表达不了"把铸币作为挂单投放"**。要让钱经市场挂单流入，只有两条路：
  ① 给 mandate 加"币种腿"（改持久形状 + 改订单生成 → 超 W5 范围、且要用户裁定"政府买入用什么钱、什么价"）；
  ② 铸币产出进"待投放池"、由既有授权订单花出去（同样需要一个能花新钱的订单来源）。
  ⇒ 本批按 **fail-closed** 实现为：**钱到国库户账户（走生产账 + 审计 + 债务），但没有自动入市机制**；
  **没有**留给它任何绕过市场的加钱路径。★ 请控制方裁定：这是"另开一批做政府挂单入市"还是"W5 降级为部分完成"。
- **未验证-1**：判据 ①（逐值对账）、②（规模 0 ⇒ 0 毫）、③（上限 1000 ⇒ 最多 1000）、⑤（每条发行有审计）的**运行期读数**未取
  —— 只到编译过。`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar` 是**改动前**（08:02）的产物，本会话**不**允许 `package`，
  故未做真跑。
- **未验证-2**：B2 分段等价 / 确定性 I7 / 性能红线（Δ 不随天数增长）未跑。
- **未验证-3**：GM 工具链的端到端行为（`simos.economy.upsertGovUnit` 带 `mintScalePerCycle` 落库）未跑，只核了注册与载荷解析路径。
- **未做**：`MoneyIssuanceKind.MINT_PRODUCTION` 在 FMX GUI 的展示面（若有货币发行枚举的硬编码分组）未逐一核；`ApiViews` 加了读数但前端未核。
- **已知不在本批**：`MoneyStock`（累计发行/回笼读数）会自动把 `MINT_PRODUCTION` 计入发行方向（`issuance() == true`），无需改。

---

## ⑥ "唯一凭空加钱口"的代码证据（grep + 逐条说明）

**口径**：凭空的定义 = "不加任何对端、直接让某个货币余额变大"。逐条列出**全部**让余额变大的路径：

| # | 路径（file:line） | 是否凭空 | 审计 | 性质 |
|---|---|---|---|---|
| 1 | `MintSettlement.credit()` → `treasury.replaceMoney(...)`（`MintSettlement.java:361`） | ★ **是** | `MoneyIssuanceRecord(kind=MINT_PRODUCTION)` + `DebtContract` | **本批新增的合法口**：有生产账（规模 × 产出率，投入已真实扣）、有上限闸门（上一轮印量）、有审计、记政府债务 |
| 2 | `actor.spi.AdjustAccountsHandler`（`simos-actor/.../AdjustAccountsHandler.java:166/180`，`money.put(currency, balance + delta)`） | ★ **是** | 自身**不留**发行痕迹（类注自述"裸账目原语"） | ★ **`simos.gov.issueMoney` 的余额腿**（唯一具名例外：DM 授权 + 审批链 + 同批 `economy.RecordMoneyIssuance` 审计腿）。★ 它是**跨模块**的裸原语，本批**不删**（用户裁定 C-4） |
| 3 | `EconomySettlement.debitHouseholdMoney()` → `journal.recordIssuance(...)`（`EconomySettlement.java:9538-9562`） | ★ **是**（单边发行腿） | `MoneyIssuanceRecord(kind=FISCAL_ISSUE)` | 既有的"发行源透支"腿：付方 = 已登记发行源（政府国库）且**必须带 journal**。★ **市场路径结构上到不了这里**（I-10：市场 4 处 `applyTransfer` 全不带 journal ⇒ `journal == null` 当场抛）⇒ 只有**日结算内部**（收获/规则实付/救济等带 journal 的入口）能走到 |
| 4 | 创世 `INITIAL_ENDOWMENT`（`EconomySeeder` 的 genesis 钱包 + `EconomyRecordMoneyIssuanceHandler`） | ★ **是**（只在创世） | `MoneyIssuanceRecord(kind=INITIAL_ENDOWMENT)` | 创世一次性；运行期不再发生 |
| 5 | `applyTransfer` 的普通货币腿（`EconomySettlement.java:9506-9508 addMoney`） | **否** | 转移本身进 ledger | 收方 +x 恒伴随付方 −x（同一笔转移） |
| 6 | `actor` 侧的其余账户写（`AccountOperations` / `StockDeductionOperations` / `RemitGovTreasuryHandler`） | **否**（除 #2 的 `AdjustAccounts`） | 各自命令面 | 都是"账户间搬运/扣减/建账"，不改 Σ 货币 |

**grep 原始证据**（本仓 main 作用域）：

```
# A) economy 侧：货币余额的写点
$ grep -rn "setMoney(\|addMoney(\|\.replaceMoney(" --include=*.java simos-economy/src/main
MintSettlement.java        -> replaceMoney     ← 本批新增（#1）
AccountSession.java        -> replaceMoney ×2  ← 工作副本的 setter 本身
EconomySettlement.java     -> addMoney/setMoney ← applyTransfer 唯一写口（#5）
# B) 铸币审计的唯一生产者
$ grep -rn "MoneyIssuanceKind.MINT_PRODUCTION" --include=*.java simos-economy simos-app
MintSettlement.java:285/305/372                 ← 唯一（其余命中只在 MoneyIssuanceKind 定义处）
# C) 财政旁路已消失
$ grep -rn "seignioragePerCycle\|debtIssuePerCycle" --include=*.java simos-*/src/main
（仅剩 Government/MintSettlement/EconomyPayloads/EconomyRegisterGovernmentHandler 的注释留痕）
```

⇒ **结论（对任务书判据 ④ 的准确回答）**：运行期让货币余额变大的路径有**两条**：
**(a) `simos.gov.issueMoney`（`actor.AdjustAccounts` + 审计腿，唯一具名例外，保留）**、
**(b) 铸币生产方式（本批新增，有生产账 + 上限 + 审计 + 债务）**；
另有 **(c) 日结算内部的 FISCAL_ISSUE 透支腿**（既有、带审计、市场路径结构上到不了）与 **(d) 创世注资**（一次性）。
**旧的"周期铸币直接加国库"旁路已整类删除**（`GovernmentSeigniorage` 0 命中）。

---

## ⑦ `withDeferredPayments` 小修的落点

- 落点：`simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java:1045-1112`（`MarketRound.withDeferredPayments`）。
- 修法（fail-closed 两件）：
  1. **带全字段**：补 `next.portEnforcement = portEnforcement` / `next.portTax = portTax` /
     `next.procurementPriority = procurementPriority`（与其余五个 wither 同形）；
  2. **运行期守卫**：克隆后逐个 wither 字段做**引用相等**判据（本类 wither 的语义就是"原样带过**同一份**输入"），
     任一漂开 ⇒ `ERROR MARKET_ROUND_CLONE_FIELD_LOST` + `throw IllegalStateException`（§一.9：契约故障 ERROR 且不降级）。
- **不破既有语义**：默认世界这三个字段分别是 `none()/none()/none()` 的同一实例 ⇒ 判别恒真；
  生产注入次序（`withDeferredPayments` 在四个 `withX` 之前）下守卫也恒不触发 ⇒ 零行为变化（只是不再能静默丢字段）。
- 为什么不是"让丢字段在构造期具名抛"：`MarketRound` 的构造器是 22 参的**共享**入口（六条 wither + 两条克隆路径都走它），
  在构造期判"少带了哪个字段"需要给构造器加"克隆上下文"参数（改所有调用点）；wither 尾守卫是同等的 fail-closed 且更局部。

---

## ⑧ 会改变数值行为的清单（逐条具名 —— 给测试 Agent 当输入）

| # | 行为 | 改前 | 改后 | 影响面 |
|---|---|---|---|---|
| N-1 | **周期铸币** | 周期开始日，每政府每 issuable 币种 +`seignioragePerCycle`（demo 世界 = 2,000 毫/周期，直接进国库） | **产出的**：关账日 `scale × moneyOutputPerUnit[币]`，再 min(政策旋钮, 上一轮印量) | ★ 有政府家户的世界（demo/small-world）货币总量曲线**变** |
| N-2 | **周期发债** | 周期开始日按 `debtIssuePerCycle`（demo = 5,000 毫）向家户借钱，写 `DebtContract` | **整条删除** | ★ 政府不再自动借家户的钱 ⇒ 家户货币余额与债务表都变 |
| N-3 | **铸币审计 kind** | `FISCAL_ISSUE` + id `gov-seigniorage-<gov>-<day>-<cur>` | `MINT_PRODUCTION` + id `mi-mint-<gov>-<day>-<cur>` | 按 kind/id 过滤的读数面（`MoneyStock` 累计发行量**不变**，因为它只按 `issuance()` 方向求和） |
| N-4 | **新增债务合同** | 无 | 每条铸币一条 `DebtContract`（债务=政府国库家户，债权=组织者） | ★ `debtContracts` 表多行 ⇒ 债务存量读数、`DebtReferenceReconciler`、清算/还债路径都会看见它 |
| N-5 | **铸币产业进入世界** | 无 `mint@<q>_<r>` 产业 | 有政府家户的世界多 1 个产业 + 1 个 unit + 1 份 `assetShares(WORKSHOP, OWNED)` | ★ 产业表/份额表/关账循环的规模都变；政府家户库存多一份工具（创世） |
| N-6 | **政府家户的货物库存** | 空账 | 创世多 `tool × 4 × 2,000 = 8,000` 毫工具 | 库存读数与 `Σ` 守恒式的初值变（**这是有意的：铸币要吃的投入**） |
| N-7 | **政府政策字段** | `seignioragePerCycle` / `debtIssuePerCycle` | `mintScalePerCycle`（demo = 2,000）+ `mintedLastCyclePerCycle` | 载荷/读口/落盘的键名变（§一.11：旧档直接重建） |
| N-8 | **`MarketRound.withDeferredPayments`** | 丢 3 个字段（生产次序下不可观测） | 带全 + 守卫 | **零行为变化**（注入次序未变）；只在"次序被改"时由静默失效变成当场抛 |
| N-9 | `Industry` / `ProductionRecipe` 形状 | 17 / 4 组件 | 18 / 5 组件（新组件缺省 = `MoneyOutput.NONE`） | 旧载荷/旧调用点逐值不变；**落盘字节变**（多一个 `moneyOutputPerUnit` 键）⇒ 旧档按 §一.11 重建 |
| N-10 | **铸币产业占用本格城镇池的部分剩余劳动预算**（`MINT_LABOR_SHARE_PER_MILLE = 10‰` 的池日劳动，上限 = 作坊发完后的剩余） | 无 | 该格多一条 `LaborAllocation`（activity = `mint@<q>_<r>`，收劳动主体 = 政府家户 actor） | ★ 该 unit 的规模 > 0 ⇒ 铸币真的产出；**作坊/农业/纺织的既有配额一字未改**（拿的是剩余），但"剩余"这一维从此被消耗 ⇒ 同格其它活动的可分配余量变小 |
| N-11 | **`Government.mintScalePerCycle` 出厂值 2,000 生效** | demo 世界政府不铸币（旧路径是 `seignioragePerCycle` 直接加 2,000） | 铸币产业产出 = `min(规模 × 500, 2,000, 上一轮印量)` | ★ 货币总量曲线（与 N-1 同一件事的两面） |

---

## ⑨ 受影响的硬编码字面量（新增，全部集中、可审）

| 常量 | 值 | 落点 | 说明 |
|---|---|---|---|
| `EconomySeeder.GOVERNMENT_MINT_SCALE_PER_CYCLE_MILLI` | `2_000` | `EconomySeeder.java` | 铸币政策旋钮的出厂值（毫/周期）；**取代** `GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI(2_000)` 与 `GOVERNMENT_DEBT_ISSUE_PER_CYCLE_MILLI(5_000)`（两者已删） |
| `EconomySeeder.MINT` | `"mint"` | 同上 | 铸币产业的 kind（具名） |
| `EconomySeeder.REGIME_MINT` | `"government_mint"` | 同上 | 制度名（不建户、不参与阶层流动） |
| `EconomySeeder.MINT_FURNACES_PER_CAPITA` | `1` | 同上 | 铸炉数（产能锚 `WORKSHOP`） |
| `EconomySeeder.MINT_LABOR_MILLI_PER_FURNACE` | `8_000` | 同上 | 每座铸炉每周期的劳动（千分劳动） |
| `EconomySeeder.MINT_TOOL_MILLI_PER_FURNACE` | `2_000` | 同上 | 每座铸炉每周期的工具投入（毫） |
| `EconomySeeder.MINT_MILLI_PER_FURNACE_CYCLE` | `500` | 同上 | ★ **铸币产出率**（毫/座·周期）—— 标定项，列 W6 首查 |
| `EconomySeeder.MINT_SEED_CYCLES` | `4` | 同上 | 创世投入够跑几个周期 |
| `EconomySeeder.MINT_LABOR_SHARE_PER_MILLE` | `10` | 同上 | ★ 铸币从城镇池**剩余预算**里取的份额（‰）—— 标定项 |
| `MintSettlement`（无自有常量） | —— | `MintSettlement.java` | 上限闸门与日志字段全部具名，无魔法数 |

★ 与旧常量对照（已删）：`GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI = 2_000L`（保号搬到 `GOVERNMENT_MINT_SCALE_PER_CYCLE_MILLI`，
但**语义变了**：从"直接加进国库的钱"变成"本周期铸币产出的上限"）；`GOVERNMENT_DEBT_ISSUE_PER_CYCLE_MILLI = 5_000L`（**删除**，发债轨道没了）。

---

## ⑩ 会让既有测试断言失效的清单（按类名，只报不改）

| 类 | 原因 |
|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java` | 类注 3 处引用 `seignioragePerCycle`（值域说明）；若注释不改不影响编译，但**断言"货币总量 = 创世 + 周期铸币 + GM 发行"**这一族必失效（N-1/N-2/N-3/N-4） |
| 任何构造 `Government` 7 参（旧顺序含两个旋钮）的夹具 | 组件顺序/含义变了 ⇒ **编译失败**（保留 4/5/6 参旧形状构造器，但**旧的 6 参 `(…, seigniorage, debtIssue)` 形状已不存在**） |
| 任何直接 `new Industry(…17 参…)` / `new ProductionRecipe(…4 参…)` 的夹具 | ★ **两个旧形状都保留了委托构造器** ⇒ 这两个**不会**编译失败（但断言"组件数 = 17/4"或"落盘字节"的会失效） |
| 任何断言铸币路径 `id` 前缀 `gov-seigniorage-` 或 kind `FISCAL_ISSUE` 的测试 | id/kind 都变成具名的 MINT 形态（N-3） |
| 任何"周期开始日国库余额 +2000"的断言 | 改后铸币在**关账日**、且金额 = `min(scale×500, 2000, 上一轮印量)`（N-1） |
| 依赖 `EconomyData.governments` 落盘字节/叶数的黄金档测试 | 新增 `mintedLastCyclePerCycle` 组件 ⇒ 叶数与字节变 |

---

## ⑪ 与约束设计书不一致处及原因

1. **§6.1 W5 判据 ④（"产出货币必须经市场挂单流入"）未满足** —— 见 §5 BLOCKED-1（`GovernmentMarketMandatePlan` 的结构性原因）。
   ★ 这属于"用户原话（第一批第 1 条『铸币更是必须要市场家户参与市场行为才能流入市场』）"的落地缺口 ⇒ 按 §一.8 三级处置
   **上报给控制方**，不自行选边。
2. **§6.1 W5 行的"劳动 + 工具 → 货币"里，劳动这一路当前不紧**（政府家户人口 0 ⇒ 没有劳动配额）—— 见 §4 D-2。
3. **§3.1-5 说"铸币有两条旁路（周期 `GovernmentSeigniorage` 直接加国库 + GM 工具直发）"**：本批删掉第一条，**保留**第二条（用户裁定 C-4 / §4.1 溯源表）。
4. **§5.2 的"`seignioragePerCycle` / `debtIssuePerCycle` 双轨合并"** —— 已按 §一.11 直接删（不是留兼容位）。
5. **旧 E1 账本的形状（`Industry.moneyOutputPerUnit` + `Government.mintScalePerCycle` + `MintSettlement` + `MoneyIssuanceWrite`）**：
   前三个我采纳了（其中 `MintSettlement` 的**时点**从"周期末"改到"关账处读同一个 scale"，见 §3.1）；
   **`MoneyIssuanceWrite` 我没有新建** —— `MoneyIssuanceId.forMintProduction(...)` + 直接写 `session.sheet().moneyIssuances()`
   已经足够（与 `GovernmentSeigniorage` 的旧写法同形），多一层包装类只会多一处拼写点。

---

# W5-fix（BLOCKED-1 序列化往返 + BLOCKED-2 自持债）

> 责任区：修 W5b 测试批挖出的**两个 P0 main 缺陷**（W5b 账本 §BLOCKED-1 / §BLOCKED-2；设计书
> `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.15 §7** 的裁定）。
> 授权写入面：`simos-economy/src/main/**`、`simos-economy-api/src/main/**`、`simos-app/src/main/**`；本批**只动了 `simos-economy/src/main` 两个文件**。
> 纪律：不 commit；不跑 `test` / `verify` / `package` / `clean`；**没跑** `EconomyCodecTest`（测试批的活）；一次一个 Maven（全程 `tools/mvn-lock.sh`）。

## ⓪ 两个 P0 的修法一览

| # | 缺陷 | 落点 | 修法 |
|---|---|---|---|
| BLOCKED-1 | `MoneyOutput.isEmpty()` 被 Jackson 当属性写出 `"empty"` ⇒ 自己写的档自己读不回（铁律 5） | `simos-economy/.../model/MoneyOutput.java`（第 3 行 import、**第 100 行 `@JsonIgnore`**、第 79-99 行长注释） | 给 `isEmpty()` 加 `@JsonIgnore`（照 `MarketTable.java:143-162` / `EconomyMeta.java:79-95` 先例）。**没有**动 `FAIL_ON_UNKNOWN_PROPERTIES`、**没有**加 `@JsonIgnoreProperties(ignoreUnknown=true)` |
| BLOCKED-2 | 播种形状下铸币债 `debtor == creditor` ⇒ 还债轮铸自转移 ⇒ 第一个关账日必抛 | `simos-economy/.../time/EconomySettlement.java`：**`repayDebts` 第 7670-7700 行**（排除 + DEBUG）、**`settleOperatorExits` 第 7210-7232 行**（同族同口径）、第 7657 行计数、第 7866-7867 行 INFO 字段 | 还债轮与退出清算**排除自持合同**（`debtor.equals(creditor)` = "发行额 = 政府负债"的**记账事实**、不是可执行债权），逐条 **DEBUG 具名**（不静默）；本金/状态一概不动 |

★ **只改这 2 个文件**（`git status --porcelain` 共 22 项：本批的 2 项 + 其余 **20** 项 main/test 改动 —— 全部是 W5 + W5b 的既有未提交改动，本批一个字节都没碰）。

## ① BLOCKED-1：改动点 + 系统排查表（全量，不是抽样）

**改动点（前后各一行）**：

```
改前（W5b 交账时的行号）
MoneyOutput.java:78      /** 空表（不铸币）。 */          ← 被替换成 79-99 行的线格式契约长注释
MoneyOutput.java:79      public boolean isEmpty() {

改后
MoneyOutput.java:79-99    （线格式契约长注释）
MoneyOutput.java:100  +  @JsonIgnore
MoneyOutput.java:101     public boolean isEmpty() {
```

**探针（只读，落 `/tmp`，不进 src、不进 Maven 门禁）**：

| 文件 | md5 | 说明 |
|---|---|---|
| `/tmp/w5fix-probe/W5FixProbe.java` | `ff8efc4c104ef27a1d6cc15c3ba3e49b` | 闭包遍历 + 属性集 vs 组件名 + 真 serializer/deserializer 内省；含 MoneyOutput 裸往返 / EconomyCodec 端到端 / 合成 record 规则钉死 |
| `/tmp/w5fix-probe/run-probe.sh` | `df3a813453d5229cb61bfff29406cb60` | 编译器（`javac` + `java`，classpath = 各模块 `target/classes` + jackson 2.22.2 + slf4j 2.0.19） |
| `/tmp/w5fix-probe/before.log` | `42be354807a86897fd1707edf1a90c50` | **修前**：`hazards=1 props≠components=1`；PART C 端到端 `Unrecognized field "empty"` |
| `/tmp/w5fix-probe/after.log` | `4e70159c6c95d74a81aa3d8207b0d0a9` | **修后**：三个 scope 全 `hazards=0` |

**修前（before.log 实录）**：

```
### SUMMARY typesAudited=91 components=463 plainNoArg(不写出)=39 shapedMethods=4 hazards=1 props≠components=1
  MISMATCH [A] MoneyOutput components=[perUnit] props=[empty, perUnit]
=== PART B  JSON = {"perUnit":{"silver":500},"empty":false}   含 "empty" 属性 = true
  读回失败 = UnrecognizedPropertyException: Unrecognized field "empty" (class …MoneyOutput), not marked as ignorable (one known property: "perUnit")
=== PART C  EconomyCodec 编码成功，字节数 = 1572；含 "empty" 属性 = true
  失败 = IllegalStateException: economy 侧 JSON 解码失败: EconomySnapshot
    caused by UnrecognizedPropertyException: Unrecognized field "empty" …
      (through reference chain: EconomyData["industries"]->LinkedHashMap["ind-mint-probe"]->Industry["moneyOutputPerUnit"]->MoneyOutput["empty"])
```

**修后（after.log 实录）**：`hazards=0 props≠components=0`；PART B `{"perUnit":{"silver":500}}`、读回逐值相等 = true、
**旧字节（含 `"empty"`）仍能读回** = true（`@JsonIgnore` 把该属性登记成可忽略）、**其它未知字段 `bogus` 仍 fail-closed** = true、
`MoneyOutput.NONE` 往返 = true；PART C `EconomyCodec` 往返逐值相等 = true、`moneyOutputPerUnit` 逐值相等 = true。

### 系统排查表（"类型 → 有无非组件 bean getter → 处置"）

**判据**：`SimosObjectMapper.create()` 裸 mapper（= Core 第四台 mapper 的同一份装配，模块私有 mixin 一概看不见）实测
"写侧属性集 vs record 组件名"，并对每个 bean 形状无参方法（`isEmpty` / `isXxx` / `getXxx` / 单名）逐条给写/读两侧判定。

| 类型（闭包内） | 非组件 bean getter | 写侧写出？ | 读侧 | 处置 |
|---|---|---|---|---|
| `MoneyOutput` | `isEmpty()` → `empty` | **是（修前）** / 否（修后） | 修前**拒**（UnrecognizedPropertyException） | ★**本批修**：加 `@JsonIgnore` |
| `MarketTable` | `isEmpty()` → `empty` | 否（W1f 已加 `@JsonIgnore`） | — | 无需处理（先例，实测不写出） |
| `EconomyMeta` | `isCurrentRuntimeVersion()` → `currentRuntimeVersion` | 否（已加 `@JsonIgnore`） | — | 无需处理（先例，实测不写出） |
| `EconomyChangeSet` | `isEmpty()` → `empty` | 否（已加 `@JsonIgnore`） | — | 无需处理（实测不写出） |
| 闭包内其余 **87** 个类型（EconomyData / EconomyChangeSet 可达的全部具体类型，含 `Industry` / `Government` / `ProductionProcess` / `DebtContract` / `MarketZone` / `MarketRoundStats` / `MoneyIssuanceRecord` / `AllocationRule.Split` 等） | **无** | — | — | 无需处理 |

- 规模：`typesAudited=91`（组件总数 463；"普通单名无参方法不写出" 39 个）；`SKIPPED` 里只有 enum（按名字序列化）与
  **sealed 接口节点**（实现已按许可子类入队）⇒ **没有"★ 非 sealed 未展开"的盲区**。
- 源码级交叉核（**与探针互为独立证据**）：`grep` 出 economy main + economy-api main 全部 bean 形状无参方法共 **17 处** ⇒
  其中 **4 处在持久闭包内**（上表四条）、**13 处不在闭包内**（`AccountDelta` / `PrimaryModeRanking` / `MerchantCapacityPool` /
  `FxRoundInput` / `ProcurementPriorityInput` / `DebtValuation` / `ModeMigrationPolicy` / `PortEnforcementInput` / `FxRoundResult` /
  `PortTaxInput` / `EconomyIndustryUpserts` / `HouseholdStockDeduction` / `LotChange`）⇒ 逐条核过**都不是 EconomyData/EconomyChangeSet 的组件**
  （`MerchantCapacityPool` 唯一那处命中在 `EconomyData.java:121` 的**注释**里，且明写"不再有任何持久组件承接它"）。
- **旁证（只读，不改）**：`SocialData` + `ActorData` 闭包 `typesAudited=35` ⇒ `shapedMethods=0 hazards=0`；
  其余 7 个快照根（map / unit / social / actor / gov / army / sd）闭包 `typesAudited=163` ⇒ `shapedMethods=0 hazards=0`，
  仅 2 处 `props≠components` = `@JsonProperty` **改名的组件**（`GovernmentFormation.governmentPostsOfHousehold→householdPosts`、
  `ArmyFormation.militaryDutiesOfHousehold→householdDuties`），**读侧实测接受 = true** ⇒ 不是本族缺陷、不动。
- **"只有 `isEmpty`/`isXxx`/`getXxx` 形状会被写出"的规则当场自证**（合成 record）：`{"value":"v","rows":{"k":1},"empty":false,"configured":true,"label":"L"}`
  ⇒ `isEmpty()` / `isConfigured()` / `getLabel()` 写出；`hasRows()` / `currentCycleNumber()` / `describe()` **不写出**（与 W1f 结论一致）。

## ② BLOCKED-2：改动点 + 排除判据 + DEBUG 字段 + 其它路径同款排查

**排除判据（唯一一条）**：`debt.debtor().equals(debt.creditor())` —— 自持合同 = "发行额 = 政府负债"的**记账事实**，
不是可执行的债权（v3.15 §7 控制方裁定）；**不判违约、不减本金、不改状态、不删合同**。

**改动点（file:line + 前后各一行）**：

```
改前（W5b 交账时的行号）
EconomySettlement.java:7636-7641    for (debtId …) { DebtContract debt = debts.get(debtId);
                                      if (debt != null && debt.principal() > 0L) { owed.add(debt); } }
EconomySettlement.java:7194-7261    settleOperatorExits：`owed` 逐条直接进清偿（无自持判据）

改后（本批收口时的行号）
EconomySettlement.java:7664-7701    for (debtId …) { DebtContract debt = debts.get(debtId);
                                      if (debt == null || debt.principal() <= 0L) { continue; }
                                      if (debt.debtor().equals(debt.creditor())) { selfHeldExcluded++; DEBUG 具名; continue; }
                                      owed.add(debt); }
EconomySettlement.java:7657      +  long selfHeldExcluded = 0L;                     ← 轮计数
EconomySettlement.java:7866-7867 +  "selfHeldExcluded", selfHeldExcluded,            ← DEBT_REPAY_ROUND（INFO）新增字段
EconomySettlement.java:7210-7232 +  settleOperatorExits 的债务处置：同一判据 ⇒ 同一处置（排除 + DEBUG），不铸转移、不判违约
```

**DEBUG 具名日志（不许静默跳过）**：

| 事件名 | 级别 / logger | 字段 |
|---|---|---|
| `DEBT_REPAY_SELF_HELD_SKIPPED` | **DEBUG** / `EconomyLog.settlement()`（`…economy.settlement`，`source=ECONOMY_DEBT_STATE`） | `day`、`household`（= 债务人也 = 债权人）、`debtContract`（合同 id）、`unit`、`outstandingPrincipal`、`why=self-held-not-executable` |
| `ENTERPRISE_EXIT_SELF_HELD_DEBT_SKIPPED` | 同上（退出清算那条同族路径） | 同上字段集 |

**运行时证据（只读探针 `/tmp/w5fix-probe/W5FixSelfHeldProbe.java`，md5 `f0b57ca564d3a2d5003a7e5c035b1733`；runner `run-selfheld-probe.sh` md5 `c99b35fe4414a9f75460285f62cae115`）**：
夹具与 W5b 的 `MintProductionSettlementTest.mintWorld` **逐值同形**（一格 / 一铸币产业 / 一 unit / 一户 = 政府家户 = 国库 = 铸币组织者），
**去掉它唯一的一处装置**（国库货币不再冻结）⇒ 真世界形状（"有库存就还"看得到钱）。日志采集借 W5b 已编出的
`simos-economy/target/test-classes` 里的 `TestLogCapture` + `TestSlf4jProvider`（**没有改任何 src/test 文件**）。

- 修后（`selfheld-after.log` md5 `d278352e4db09896292a13c700de0ff9`）：
  `step+finish = 跑完一整天（无异常）`；逐值：工具 8,000→**6,000**、国库银 100→**600**、货币总量增量 = **500** = 铸币发行之和、
  发行记录 1 条、债务合同 1 条（`debtor=hh-gov-mint creditor=hh-gov-mint principal=500 status=NORMAL` ⇒ **未被还债动过**）、
  国库户当日 `repaidMoney={}`；
  实录日志行：`DEBUG|event=DEBT_REPAY_SELF_HELD_SKIPPED … day=1 household=hh-gov-mint debtContract=debtc-… unit=money:silver outstandingPrincipal=500 why=self-held-not-executable`、
  `INFO|event=DEBT_REPAY_ROUND … debtorsWithOutstanding=0 householdsRepaid=0 householdsWithNoStockToRepay=0 selfHeldExcluded=1 …`。
- **变异自证（M1，同一条闸门）**：把判据改成 `if (false && …)`（md5 `528d94f3…` → `ab50e16b…`，**字节不同 ✓**）⇒
  重编译 + 同一探针 ⇒ `selfheld-mutant.log`（md5 `ba5cea588296cccf13eb65f349c36117`）红在
  `IllegalArgumentException: Transfer 的两端不得相等（自转移不是一条发生额，是坏数据）: HOUSEHOLD:hh-gov-mint`
  —— 与 W5b §BLOCKED-2 第 6 条的栈**同一句话**；还原后 `md5sum -c` = `OK`、`grep -c MUTANT-M1` = 0、重编译 + 重跑 ⇒ 又绿。

**其它路径的同款排查（逐条点名，结论都要能回代码核）**：

| 路径 | 是否有"自持合同可执行"假设 | 结论 / 处置 |
|---|---|---|
| `EconomySettlement.settleOperatorExits`（`:7194-7261` 退出清算的债务处置） | **有** —— `ledger.mint(debt.debtor() → debt.creditor())`，自持 ⇒ `Transfer` 抛 | ★ **本批同口径修**（`:7210` 排除 + DEBUG `ENTERPRISE_EXIT_SELF_HELD_DEBT_SKIPPED`） |
| `EconomyLiquidationSettlement.apply`（`:1071-1117`） | **无** —— 它走 `OwnershipStakeBook.Move(sourceShareId, …, toOwner/toOperator=creditor, OWNED)`：**没有 `Transfer`**（源是份额 id，不是 ActorRef）⇒ 结构上不可能自转移 | 不改；如实记：质押份额的"自己转给自己"不抛，且铸币债**不带质押** ⇒ 本 P0 不可达 |
| `DebtReferenceReconciler.reconcile`（`:97-110`） | **无** —— 只校验 `debtor`/`creditor` **两端都在 classes 里**；自持时两端是同一户 ⇒ 只要该户存在就过 | 不改。★ 顺带更正 W5b §BLOCKED-2 的一条**因果**：它报的"无 classes 行 ⇒ `:97` 抛"是**引用完整性**校验（与自持无关）；真世界的 `hh-gov-*` 行由 app 物化 ⇒ 那条不是本 P0 的成因 |
| `capitalizeArrears`（`:7402-7528`） | **无（已有同口径）** —— 自债份额由 `DebtPartyResolver.splitDebtAmounts` 的 `selfNettedAmount` **净额化**，只记审计、**永不落自持合同**（`:7469-7481`） | 不改（它是"自持不执行"这条口径的**先例**） |
| `ModeMigrationSettlement.moveDebt`（`:1254-1300`） | **无（已有同口径）** —— 目标恰是债权人 ⇒ `MIGRATION_DEBT_SELF_NETTED`（**INFO 具名**）、不落合同 | 不改（同为先例） |
| `LotMigrationBook.moveDebts`（`:259-320`） | **半有** —— 当 `target == contract.creditor()` 时会建出 `target→target` 的自持合同（**不抛**，只是落一条记账行） | **不改语义**（v3.15 的裁定保留自持合同这一类**合法形状**本身：铸币那条就是被保留的）；执行侧已被 `repayDebts` / `settleOperatorExits` 两处闸门挡住 ⇒ 该行是**惰性的**。★ 如实标注为"同族创建点，未按 `ModeMigrationSettlement` 那样净额化" |
| `UnitRepayHandler`（GM 命令） | **无** —— 类注明写"本命令不碰账户"（钱的腿是另一条 `actor.TransferAccounts`，自转移由 `Transfer` 自己判死） | 不改 |
| `MarketSettlement` 的 4 处 `ledger.mint` | **无** —— 撮合处 `:5663` 已排除 `sell.seller.actor.equals(buy.buyer.actor)`（自成交） | 不改（顺带核过） |
| `EconomySettlement.emitLegs`（关系拆腿，`:8772+`）与 `requisitionInputs`（`:6452`） | requisition **已有** `!operatorKey.equals(supplierKey)` 守卫；`emitLegs` 是**规则结算**、两端来自不同主体解析 | 不改。★ 口径区别写明：**规则结算**的 payer==payee 是**坏数据**（现状 fail-closed 抛，正确）；**债务合同**的 debtor==creditor 是**合法记账事实**（裁定要求排除而非抛）——两者不可混为一谈 |

## ③ 门禁命令 + rc（真命令、逐条）

| # | 命令 | rc | 关键输出 |
|---|---|---|---|
| F-0 | `tools/mvn-lock.sh -q -DskipTests compile -am`（改前基线） | **0** | `-q` 无输出 |
| F-1 | `tools/mvn-lock.sh -q -DskipTests compile -am`（BLOCKED-1 + BLOCKED-2 落地后） | **0** | 同上 |
| F-2 | `tools/mvn-lock.sh -q -DskipTests compile -am`（变异体 M1 在位） | **0** | 变异体是**运行时**坏法（`&&` 短路），编译照样过 ⇒ 红点必须由探针给（见 §② 的 M1） |
| F-3 | `tools/mvn-lock.sh -q -DskipTests compile -am`（还原后） | **0** | 同上 |
| F-4 | `tools/mvn-lock.sh -q spotless:apply -pl simos-economy` | **0** | ★ **`EconomySettlement.java` 逐字节未变**（与备份 `md5 528d94f3…` 相同）⇒ 本批代码本来就是 google-java-format 干净的；`git status --porcelain` 的文件集与开工时**完全一致**（没有多格式化任何文件） |
| F-5 | `tools/mvn-lock.sh -q -DskipTests compile -am`（spotless 后收口轮） | **0** | 同上 |
| F-6 | `bash /tmp/w5fix-probe/run-probe.sh`（修后） | **0** | 三 scope `hazards=0`；PART B/C 全绿（见 §①） |
| F-7 | `bash /tmp/w5fix-probe/run-selfheld-probe.sh`（修后） | **0** | "跑完一整天（无异常）"+ 逐值读数（见 §②） |

## ④ 会改变数值行为的清单（逐条具名）

★ **共同前提**：只有"**存在自持债务合同**"的世界才会变；三个测试模块里的 `three-powers` 世界**没有政府家户**
（W5b §3 已核）⇒ 结构上不产生自持合同 ⇒ **逐值不变**。

1. **`repayDebts` 不再清偿 `debtor == creditor` 的合同** ⇒ 这些合同的 `principal` **不再下降**、`FlowRow.repaid*` 不再计入它们、
   `DebtStock` 的未偿总额读数相应偏大（**这正是裁定要的**：它们是记账事实，不是债权）。
   受影响的既有读数面：`repaidGrainMilli` / `repaidMoney` / `householdsWithNoStockToRepay` / `householdsRepaid`（当某户**只有**自持债时，
   它不再计入 `debtorsWithOutstanding`）。
2. **`settleOperatorExits` 不再清偿自持合同** ⇒ 退出清算的 `debtPaidMilli` 少掉这一支、合同**不再被标 `DEFAULTED`**（也不会 `SETTLED`）；
   运营者处置报告里的 `debtPaidMilli` / `debtDefaultedMilli` / `debtDefaultedItems` 可能随之变小。
3. **新增读数（不改状态）**：`DEBT_REPAY_ROUND` 多一个 `selfHeldExcluded` 字段；两条新 DEBUG 事件。
4. **BLOCKED-1 的线格式变化**：`MoneyOutput` 写出的字节**少一个 `"empty"` 键**（逐值语义不变，"不铸币"仍是 `{"perUnit":{}}`）。
   ⇒ 依赖"落盘字节/md5/叶数"的黄金档测试若含铸币产业会变（**这是修缺陷本身**，不是行为回归）；读侧**向后可读**（带 `"empty"` 的旧字节仍能读回）。
5. ★ **不再抛**：真世界"第一个关账日"由 `IllegalArgumentException` 变为**正常跑完**（§② 的探针实录）。

## ⑤ 我没做 / 没验证的（如实）

1. **没跑任何 Maven 测试**（纪律）：`EconomyCodecTest` / `MintProductionSettlementTest` / `simos-app` 全量 **都没跑** ⇒
   "12 红转绿"与"app 模块能跑进去"**由测试批真跑确认**，我这里给的是一条端到端 `EconomyCodec` 探针（PART C）+ 一条真日循环探针（§②）。
2. **`simos-app` 侧的播种世界（`EconomySeeder` → 第一次 replay → 第一个关账日）没跑**：需要 boot Shell + 真档，属测试批/控制方。
   本批只从 main 侧修掉两条因果链。
3. **`selfHeldExcluded` 的字段名/事件名**未与任何既有测试对齐（新增字段，测试批可能需要更新断言 —— 但**没有为迁就实现改任何断言**，我一个测试文件都没碰）。
4. **`LotMigrationBook` 的同族创建点未改**（见 §② 表最后第二行）：它仍可能建出 `target→target` 的自持合同；
   我没有为它写探针，也**没有**按 `ModeMigrationSettlement` 的形态净额化（那属口径变更，须控制方裁）。
5. **没跑 SpotBugs / `spotless:check`**（只跑了 `spotless:apply`）；**没跑** `clean verify` / `package`；
   ★ 因此本批**没有** `spotbugsXml.xml` 读写（无备份/还原动作）。
6. **没有验证**"其余快照根闭包"的 `@JsonProperty` 改名两条在**真重放路径**下的行为（探针给的是**读侧内省实测接受 = true**，
   与 W1f 的结论同源；不是端到端重放）。
7. 探针只落在 `/tmp/w5fix-probe/`（按任务书"放 /tmp，不进 src"）⇒ **不随提交入库**；要长期留痕的话需控制方把它拷进
   `.superpowers/sdd/2026-10-11-w5-minting-into-market/w5fix-probe/`（W1f 的形态）。账本这里记了全部 md5 与关键实录行。

---

# W5-fix2（登记 government_mint 制度 + 连带规则）

> 责任区：修 **W5 的第三个 P0（创世被拒）**。任务书自包含；写码 Agent 只写生产代码、不写/不跑 `src/test/**`、不 commit。
> 状态：**P0 已关闭（端到端自证）** + **一条实测的数值标定发现（不属于本任务范围，转控制方）**。
> 改动文件 4 个（全部在允许范围内）：`RegimeRelations` / `RegimeOperators` / `EconomyPayloads`（economy）+ `GovernmentServiceUnitConsistency`（app）。

## ⓪ 现场复核：**异常消息与真凶不是同一处**（这是本批最重要的一条）

任务书给的现场是「`RegimeRelations` 的 `BY_REGIME` 表里没有这个制度」。**回代码核之后，这只是三环因果链的第一环**，而且**不是**那 46 条 error 的直接抛出点：

| 环 | 证据（file:line） | 事实 |
|---|---|---|
| ① 期望 | 任务书 / W5 账本 D-3 | 只登记 `RegimeRelations` 就够了 |
| ② **实测抛出点** | `EconomyPayloads.java:1465-1469`（W5 版） | `industrySpec` 对**每一个** industry 节点都**急切**调 `RegimeOperators.defaultOperator(template.regime(), template.id())` —— 即使这个节点是**新形状**（`legacy=false`、operator 由 `units[]` 自带） |
| ③ 消息误导 | `RegimeOperators.java` 的 `BY_REGIME` | 异常消息里的「已登记的档: [feudal, household, handicraft, tenant]」是**经营主体表**的四个档（它**按 W5 裁定 D-3 就该是四档**），不是关系表的档位 ⇒ 照着这条消息去改 `RegimeOperators` 会改错表 |

★ 判别实验（决定性）：`government_mint` **显式带 operator**（`EconomySeeder.java:3975` 的 `unit.put("operator", actorNode(operator))`，operator = `HouseholdActors.of(govHousehold)`）⇒ 若真凶是「缺关系模板」，那么**先补 `EconomyPayloads:1468` 也应当继续红**；实测：只补 `EconomyPayloads` 之后**创世即通过**（见 §② 探针），⇒ 因果链断开在这一行。
★ 结论：**补 `RegimeRelations` 是必需的（第二环），但不充分**；只补它，创世仍会以**同一条消息**红在 `EconomyPayloads:1468`。

## ① 关键调查结论（file:line → 结论 → 影响）

| # | 证据（file:line） | 结论 | 影响 |
|---|---|---|---|
| F-1 | `EconomyPayloads.java:1465-1469`（W5 版，`industrySpec`） | 默认经营主体**在解析期急切推导**；而 `IndustrySpec.legacyOperator` **只在 `legacy==true` 时有消费者**（`EconomyPayloads.java:391-394` 的 `spec.legacy()` 分支） | ★ 真凶。"新形状节点也推一次"= 白白触发一次 fail-closed；对 `government_mint`（刻意不登记 operator）必抛 |
| F-2 | `EconomySeeder.java:3975,3980` | 铸币 unit **显式给 operator**、产业节点**不带 operator**；`regime=government_mint` 且**不发 `relation`** | ⇒ 走 F-1 的急切推导（抛）+ 关系默认表（第二环） |
| F-3 | `RegimeRelations.java:329-333`（第二环；未登记 ⇒ 具名抛） | 缺 `relation` 键 ⇒ `BY_REGIME.get(regime)` = null ⇒ 具名抛 | 补上登记即可（本批已补） |
| F-4 | `GovernmentServiceUnitConsistency.java:98`（第三环） | Z6 不变量的判据是「operator 是政府家户（`hh-gov-` 前缀）」——**过近似**：它管的是 **gov service unit**，却把**任何**由政府家户经营的生产 unit 都卷进来 | 补完前两环后，创世通过、**第一个关账日**撞这条守卫（`GOV_SERVICE_GOV_UNIT_MISSING`，`world-silver` 不是 unit id） |
| F-5 | `GovernmentServiceUnitConsistency.java:116-151` | 守卫要求该家户的 `unitRefOf()` 在 **unit 切片**存在且带 `GovernmentFormation` | 铸币是**生产产业**，没有、也不该有 GOV 编制 ⇒ 三条路只有"收窄判据"可行 |
| F-6 | 探针读数（§②） | 创世后 operator 是政府家户的经济 unit **共 3 个**：`unit-mint@0_0…hh-gov-world-silver`（`modeKey=mint@0_0`）+ `…office@0_0…hh-gov-gov-central` 与 `…office@0_1…hh-gov-gov-province`（两者 `modeKey=gov_service`） | ⇒ **只有 2 个**真正属于该守卫的判据面 ⇒ 收窄判据 = 恢复守卫本意，不是放宽 |
| F-7 | `EconomyGovUnitUpserts.java:97` + `:250-262` | `GOV_SERVICE_MODE_KEY="gov_service"` 是行政服务生产方式的**唯一拼写点**；**唯一**能建 gov service unit 的路径是 `economy.UpsertGovUnit`，且它另由 `requireOfficeIndustry` 判死「产业 base kind 必须是 `office`」 | ⇒ 用 `modeKey` 当判据**不靠猜、也不静默放宽**（那条路径的 unit 恒带这个键） |
| F-8 | `RegimeRelations.java:563`（W5 版 `registered()` 的 `get(0)`） | `entry.getValue().get(0).type()` —— **对空规则表的档硬崩**（`Collections$EmptyList.get`） | ★ 铸币让"空规则表的档"**第一次**真的进了 `BY_REGIME` ⇒ 120 天长跑在 `ExpectedProfitBook.java:818` 处 `IndexOutOfBoundsException: Index: 0` |
| F-9 | `EconomySettlement.java:1896-1901` + `:1971-1974` | 铸币走 **`MintSettlement` 的 `moneyOutputPerUnit` 腿**（账户余额），而 `harvest` 对 `outputPerUnit` 为空的 unit 在**结算规则之前**就 return（`EconomySettlement.java:8971-8973`） | ⇒ 铸币关系的**任何补偿腿都永远不会被执行一次** ⇒ 规则表**必须为空**（写腿 = 死数据；写 cohort 腿还会被 `requireCohortRows` 当场拒） |

## ② 我的实现（4 个文件，逐字段）

### (1) `RegimeRelations`：登记 `government_mint`（唯一关系模板拼写点）

| 字段 | 值 | 依据（照哪一档、为什么） |
|---|---|---|
| `regime` 键 | `RegimeOperators.GOVERNMENT_MINT`（= `"government_mint"`，**新常量**，不在本类里另写一遍字面量） | 照 `merchant` 的形态：制度名在 `RegimeOperators` 有唯一拼写点，关系表引用它 |
| `rules` | **空表**（`MINT_RULES = Collections.emptyList()`） | 照 **裁定 E9 的等价路径**（"空表 ⇒ 全归 `residualOwner`"），★ 不是自创新语义。两条硬依据见 F-9：① 铸币产出物是**货币**，进 `harvest` 的实物分支都进不去（`netByCommodity` 空 ⇒ 提前 return）⇒ 任何腿都是死数据；② 照抄 `handicraft` 的 cohort 腿会在第一个关账日被 `requireCohortRows` 拒（铸币 labor 的受方家户是**零人口政府家户**，它没有 `(hex,urban,传统四阶层)` 行） |
| `inputSupplier` | **不单独登记** ⇒ 走 `defaultInputSupplier` 的同一处默认 = `ToActor(operator)` | 照 H3/C3 的"四档同值"口径：投入由**经营主体自己**出（政府家户自备工具，正是 `EconomySeeder` 第三步给的那份库存） |
| `residualOwner` | `operator`（`defaultRelation` 的既有口径） | 同 E9；产出（钱）本就走 `MintSettlement.credit` 进国库，与"归 operator"一致（常态世界里国库家户 == 组织者） |
| `laborSource` | **`LaborSource.WAGE`**（`laborSourceFor` 新增一支） | 照 **`handicraft`** 那一档：铸币的劳动不是经营者自营，而是 `EconomySeeder.seedGovernmentMint` 从**落点格城镇池的剩余预算**里切的一份配额（被雇来的劳动）★ 本档规则表为空 ⇒ 这一维当前**无数值后果**，它的作用是在登记处把这句话写下来（`LaborSourcePolicy` / `HouseholdCondition` / `HouseholdClassRule` 都读它） |
| 表序 | **追加在末位**（`feudal, household, handicraft, tenant, government_mint`） | 不插队 ⇒ 既有四档的迭代序与规则**逐字不变** |
| 日志 | `defaultRelation` 命中空规则表 ⇒ **DEBUG** `REGIME_RELATION_EMPTY_TEMPLATE`（regime/activity/industry/operator/residualOwner/ruleCount/reason） | §一.9：新登记判据的回退/等价路径具名。发 DEBUG 不发 INFO：小世界里每个 revision 都会解析一次，INFO 会刷屏；"发生了什么"那一面已由 `MintSettlement` 的 `MINT_SETTLED`/`MINT_LIMIT_REJECTED` 给出 |

### (2) `RegimeOperators`：**只加常量，不动 `BY_REGIME`**（如实记：这是**刻意不登记**）

新增 `GOVERNMENT_MINT = "government_mint"`（唯一拼写点），**不**进 `BY_REGIME`。理由（W5 裁定 D-3，本批不推翻）：
`defaultOperator` 会造出 `ActorRef(HOUSEHOLD, "mint@q_r")`（**产业 id 当家户 id**），而铸币真正的经营者是
`HouseholdActors.of(hh-gov-…)`（政府家户的**稳定身份**）—— 两者不是同一个主体 ⇒ 登记它等于让默认值**给出一个错的主体**。
★ 与 `merchant` 同款。⇒ **关系表登记、经营主体表不登记**，两张表回答两个不同问题（这张分工写进了常量的类注）。

### (3) `EconomyPayloads`：把默认经营主体的推导**改为惰性**（F-1 真凶）

```java
// 改前（解析期急切）: ActorRef legacyOperator = operatorNode==null ? RegimeOperators.defaultOperator(...) : actorRef(...)
// 改后（IndustrySpec 的 record 组件 + 惰性访问器）:
private record IndustrySpec(..., JsonNode legacyOperatorNode, ...) {
  ActorRef legacyOperator() {                       // ★ 只在 legacy==true 时被调用
    if (legacyOperatorNode != null) return actorRef(legacyOperatorNode);
    return RegimeOperators.defaultOperator(template.regime(), template.id());   // R1 的 fail-closed 原样保留
  }
}
```
- **旧载荷行为逐值不变**：`legacy==true` 且缺 `operator` 键 ⇒ 仍走 `defaultOperator`，未登记制度**仍具名抛**（R1 不变）。
- **新载荷少做一次无谓推导**：`legacy==false` 时那个值**没有任何消费者**。
- 附带收益：`economy.UpsertIndustry` 的路径（`parseIndustryNode` → `industry`）本来就不经 `industrySpec`，不受影响。

### (4) `GovernmentServiceUnitConsistency`：判据收窄到它**文档化的主体**（F-4/F-6/F-7）

```java
// 只在"这一支真是 gov service unit"时才要求 unit 切片有对应 GOV 编制：
if (!EconomyGovUnitUpserts.GOV_SERVICE_MODE_KEY.equals(process.modeKey())) {
  CONSISTENCY.debug(LogEvent.of("GOV_SERVICE_UNIT_SKIPPED_NOT_SERVICE", ... unit/modeKey/industry/operatorHousehold/reason ...));
  continue;
}
```
- **为什么是收窄而不是"给铸币补编制"**：铸币是**生产产业**（`moneyOutputPerUnit` + 工具投入 + 劳动配额），
  它没有、也不该有 `GovernmentFormation`；给它补一支假编制会把"服务 unit"与"生产 unit"混成一类（改变 Z6 的口径）。
- **为什么不是"改政府家户 id"**：那会动 `GovernmentHouseholdWiring` / `Government.treasury` / 创世身份链（**远超本任务范围**，见 §⑤ 未完成-2）。
- **不放宽**：`modeKey == gov_service` 是**正面证据**（F-7 的两条守卫链），且**被排除的 unit 具名进 DEBUG**。
- 既有 22 条 `GovToolsZ6Test`（含 `serviceUnitConsistencyLogsFaultBeforeFailingClosed` 的 `count=1` 断言）**全绿** ⇒ 该收窄没有削掉守卫的判别力。

## ③ 自证（真命令 + rc + 关键读数）

### 编译 / 格式化（真命令与 rc）

| 命令 | rc | 结果 |
|---|---|---|
| `tools/mvn-lock.sh -q spotless:apply` | **0** | 无输出（干净） |
| `tools/mvn-lock.sh -DskipTests compile -am` | **0** | `BUILD SUCCESS`，16/16 模块 SUCCESS |

### P0 回归自证（任务书允许的那一条）

```
tools/mvn-lock.sh test -pl simos-app -am -Dtest='GovGenesisZ6Test' -Dsurefire.failIfNoSpecifiedTests=false
```
| 轮次 | rc | 结果 |
|---|---|---|
| 只补 `RegimeRelations`（未补 F-1） | 1 | `Tests run: 5, Errors: 5` — 仍是 `government_mint`（**证伪"只补关系表就够"**） |
| 再补 `EconomyPayloads`（F-1） | 1 | `Tests run: 5, Errors: 3` — 创世通过，**3 条红在第一个关账日的 Z6 守卫**（`GOV_SERVICE_GOV_UNIT_MISSING`） |
| 再补 Z6 判据（F-4） | 1 | `Tests run: 5, Errors: 1` — `IndexOutOfBoundsException: Index: 0`（`registered()` 的空表硬崩，F-8） |
| 补 `registered()` 空表守卫（F-8） | **0** | **`Tests run: 5, Failures: 0, Errors: 0` — `BUILD SUCCESS`** |

★ surefire 报告 mtime 落在本轮：`simos-app/target/surefire-reports/io.mosire.simos.app.gov.GovGenesisZ6Test.txt` = `2026-10-11 11:40:25`。

### 全量回归（本仓，非任务书要求；为核"我的改动有没有伤到别人"）

```
tools/mvn-lock.sh test -pl simos-app -am
```
rc=**0**：`Util 217 / Map 379 / Calendar 33 / SocialApi 3 / ActorApi 8 / EconomyApi 50 / Social 217 / Unit 518 /
Core 231 / Sd 232 / Actor 130 / **Economy 293** / Gov 111 / Army 91 / **App 906（skipped 5）**` —— **全绿**。

### 原 P0 报告里点名的 9 个类（逐个点名跑，非抽样）

```
tools/mvn-lock.sh test -pl simos-app -am -Dtest='GovGenesisZ6Test,GovToolsZ6Test,Z7FleeTest,Z7HungerTest,
  Z7RemittanceE2ETest,Z7RemittanceBridgeTest,GovernmentServiceLaborBridgeZ6Test,GovAdminSalaryBudgetZ6Test,
  Z7D1CapitalProvinceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
rc=**0**，`Tests run: 68, Failures: 0, Errors: 0`（GovToolsZ6 22 / GovGenesisZ6 5 / Z7D1 3 / Z7Flee 3 /
RemittanceE2E 2 / Z7Hunger 3 / RemittanceBridge 5 / GovAdminSalaryBudget 12 / GovernmentServiceLaborBridge 13）。

### 只读探针（放 `/tmp/w5fix2-probe/`，**不进 src**）

**探针 A：创世（`SmallWorld.state("W5Fix2Probe")`，不落盘）**
```
PROBE genesis OK head=1
PROBE mint unit       = unit-mint@0_0-HOUSEHOLD-hh-gov-world-silver
PROBE mint industry   = mint@0_0 regime=government_mint moneyOutput={silver=500}
PROBE mint operator   = HOUSEHOLD:hh-gov-world-silver
PROBE mint relation?  = true
PROBE   operator       = HOUSEHOLD:hh-gov-world-silver
PROBE   inputSupplier  = ToActor[actor=HOUSEHOLD:hh-gov-world-silver]
PROBE   residualOwner  = HOUSEHOLD:hh-gov-world-silver
PROBE   laborSource    = WAGE
PROBE   ruleCount      = 0
PROBE gov = world-silver        treasury=HOUSEHOLD:hh-gov-world-silver  mintScale=2000 issuable=[silver]
PROBE gov = gov-unit-gov-central  treasury=HOUSEHOLD:hh-gov-gov-central  mintScale=0 issuable=[copper]
PROBE gov = gov-unit-gov-province treasury=HOUSEHOLD:hh-gov-gov-province mintScale=0 issuable=[]
PROBE mintUnits=1 industries=45 units=162 relations=162 assetShares=162 allocations=564
```
⇒ **创世跑通 + 铸币事实出现 + 铸币关系已登记（空表，operator/inputSupplier/residualOwner 三者同值 = 政府家户）**。

**探针 B：Z6 判据面（`SmallWorld.state` 后列全部"operator 是政府家户"的经济 unit）**
```
PROBE   unit=unit-mint@0_0-HOUSEHOLD-hh-gov-world-silver       modeKey=mint@0_0      regime=government_mint  hhRef=world-silver
PROBE   unit=unit-office@0_0-HOUSEHOLD-hh-gov-gov-central     modeKey=gov_service   regime=gov_service      hhRef=gov-central
PROBE   unit=unit-office@0_1-HOUSEHOLD-hh-gov-gov-province    modeKey=gov_service   regime=gov_service      hhRef=gov-province
```
⇒ 3 个里只有 2 个是服务 unit（`hhRef` 也都是**真 unit id**）⇒ 收窄判据的依据。

**探针 C：第一个关账日（`GovZ6WorldFixture.StateRunner`，tick 1→121；`CYCLE_DAYS=120`）**
```
PROBE MINT_PRODUCTION records=0 totalMilli=0
PROBE mint unit=unit-mint@0_0-… progressDays=0 cycleLaborMilli=0 cycleInputUsed={} perUnit={silver=500}
PROBE treasury money = {}        PROBE treasury goods = {tool=6000}
```
- ★ **无异常**（全程 121 天，`grep -ciE "exception|error"` = **0**）：`cycleInputUsed={}` + 库存 `tool 8000→6000` ⇒
  **第一个关账日真的发生了**（`cycleDays=120` 的 2000 毫工具投入被真实扣掉、`progressDays` 归 0）。
- ★ **0 毫不是缺陷，是规模被劳动下限压到 0**（`scale = min(劳动, 产能, 投入)`）：铸币 unit 拿到的配额是
  `cycleLaborMilli=373,085` 毫·日 = **373 毫劳动**，而 `laborPerUnit = 8,000` 毫/单位规模 ⇒ **劳动可用量 < 1 个单位规模的
  需求量** ⇒ 规模 0 ⇒ 产出 0 毫（`MintSettlement` 对 `requested <= 0` 静默跳过，本批不改）。
- ★ 我方新增的 DEBUG 在真路径上出现（§一.9 的落点自证）：
  `event=REGIME_RELATION_EMPTY_TEMPLATE origin=economy-settlement originKind=tick regime=government_mint activity=unit-mint@0_0-… ruleCount=0 reason=empty-rule-template-all-output-stays-with-operator`

## ④ 会改变数值行为的清单（逐条具名）

| # | 行为 | 改前 | 改后 | 影响面 |
|---|---|---|---|---|
| N-1 | 小世界/真档**创世** | `economy.Seed` 当场抛（`government_mint`） | 正常完成 | ★ 46 条 app 测试从红转绿；**任何**播 `EconomySeeder` 的世界（demo/small-world）首次能创世 |
| N-2 | 铸币 unit 的**生产关系** | 无（创世就炸） | 存在，`rules=[]`、`operator=inputSupplier=residualOwner=` 政府家户、`laborSource=WAGE` | 铸币的产出仍全走 `MintSettlement`（钱进国库），**实物规则一条不加 ⇒ 实物账逐值不变** |
| N-3 | `RegimeRelations.registered()` | 4 个键 | **仍是 4 个键**（`government_mint` 因空表被**跳过**，见 F-8 的修法） | ★ **零变化**（这正是既有 `RegimeRelationsTest` 的 `containsExactly(feudal,household,handicraft,tenant)` 断言仍绿的原因） |
| N-4 | `RegimeRelations.defaultRelation` 对 `government_mint` 的**多居住类型**检查 | 不可达（未登记 ⇒ 先抛） | **不抛**（空表 ⇒ 那段循环体一次都不执行） | 放宽是**正确的**：那条守卫的理由是"非劳动加权的腿会被重复计费"，本档无腿 |
| N-5 | `ModeMigrationPolicy` / `ModeMigrationSettlement` / `ExpectedProfitBook` 对 `government_mint` 的判定 | "未登记 ⇒ 具名回退空规则" | "已登记 ⇒ 空规则模板"（**结果同为 `rules=[]`**，但路径从"回退"变成"正常"） | 迁移面：`industriesForMode` 用 `defaultRegimeForMode` 白名单（铸币不在内）⇒ 铸币**不会**成为迁移目标；`ExpectedProfitBook` 的 `relationFor` 现在返回非 null 的空表关系（原先返回 null 走 `NO_RELATION`）⇒ **铸币的预期利润读数可能从 null 变成 0**（两者对"没有实物腿可折算"的结论一致；见 §⑤ 未验证-3） |
| N-6 | `EconomyPayloads` 对**新形状** industry 节点 | 解析期多做一次 `defaultOperator` 推导（对未登记制度**抛**） | 不做（推迟到 `legacy==true`） | **旧载荷逐值不变**；新载荷少一次无谓推导 + 不再误抛 |
| N-7 | Z6 守卫的判据面 | 全部"operator 是政府家户"的经济 unit | 仅 `modeKey == gov_service` 的那些 | 铸币（生产）不再被当成服务 unit；**服务 unit 的判别力未减**（`GovToolsZ6Test` 的 3 条守卫用例全绿） |
| N-8 | 铸币 labor 的**可供量**（**不是我的改动**） | — | 373 毫劳动 vs 需要 8,000 毫/单位规模 ⇒ 规模恒 0 ⇒ **小世界里铸币永不产出** | ★ 见 §⑤ 未完成-1（标定问题，转控制方） |

★ **底线**：本批**没有**碰 W5 已定的口径（铸币产出放国库、上一轮印量上限、自持债排除、`MINT_*` 常量一个字未改）。

## ⑤ 未完成 / 未验证项（如实）

1. **★ 铸币在小世界里实际产出 0 毫（实测，非推测）**：探针 C 逐值给出 `MINT_PRODUCTION records=0`。
   机制 = `EconomySeeder.MINT_LABOR_SHARE_PER_MILLE(10‰) × 池日劳动` 切出来的配额（373 毫劳动）
   **小于** `MINT_LABOR_MILLI_PER_FURNACE(8,000)` ⇒ 规模下限恒 0。**这是 W5 的标定问题，不是本批引入的**
   （W5 账本 §5 已如实记"判据 ①②③⑤ 的运行期读数未取"）。
   ⇒ **建议控制方裁定**：(a) 提高 `MINT_LABOR_SHARE_PER_MILLE` 到能让 1 座铸炉满负荷的量级（≈ 8000/池日劳动），或
   (b) 把 `MINT_LABOR_MILLI_PER_FURNACE` 降到本世界能给得起的量级，或 (c) 接受"小世界里铸币是有配额才产的生产方式、
   当前标定下不产"并把它写进 W6 首查。★ 我**没有**动这些常量（任务书硬纪律：不顺手改 W5 已定的口径）。
2. **没有**按"给 genesis 政府家户补一支 GOV 编制 / 改政府家户 id"的路线修 F-4：那要动 `GovernmentHouseholdWiring`、
   `Government.treasury`、`GovWorldBootstrap` 与创世身份链（`hh-gov-world-silver` = `GovernmentId` 而非 `UnitId`，
   而 Z6 的 `GovernmentHouseholds.unitRefOf` 只接受 **unit id**）⇒ 超出本任务范围。**如果控制方要的是"身份链对齐"而不是
   "守卫判据收窄"，本批的 F-4 修法要回退重做**（这是本批唯一一处**口径级**判断，已在 §④ N-7 具名）。
3. **未验证**：`ExpectedProfitBook` 对铸币 unit 的读数从 `NO_RELATION` 变为"空表关系"之后，铸币的**预期利润**是否真的恒 0
   （我只核到 `relationFor` 的返回路径，没取逐值读数）。★ 若控制方认为"铸币 unit 的预期利润必须是可读的非 null 数"，
   那是另一批（`ExpectedProfitBook` 的**货币腿**没有折算口径 —— 它整本书按实物商品折钱）。
4. **未跑**：`verify` / `clean` / `package`（任务书禁止，门禁归控制方）；SpotBugs / 前端 node 门禁随 `test` 阶段之外，
   **未单独核**。
5. **未核**：`simos-gov` / `simos-army` / `sd` 模块里是否有别处按 `"government_mint"` 或四档关系表做**硬编码分支**
   （我只跑了全量 `test`：全绿 ⇒ 行为面无回归；但"有没有该一起改的地方"未逐个 grep 到穷尽）。
6. **探针不进库**：`/tmp/w5fix2-probe/*.java`（3 个）与 `/tmp/simos-clean-cp.txt` 只在会话内存在；★ 一处**工具坑**如实记：
   `dependency:build-classpath` 生成的 classpath 会带上 `~/.m2` 里 **2026-10-07 的旧 `simos-economy` jar**，
   它**遮蔽** reactor 的 `target/classes`（表现为探针编译期报 `Industry.moneyOutputPerUnit()` 不存在）⇒
   探针必须用"模块 `target/classes` 前置 + 剔除 `~/.m2/io/mosire/simos-*`"的 classpath。

## ⑥ 与约束设计书 / W5 账本的偏离

| # | 偏离 | 原因 | 影响 |
|---|---|---|---|
| D-1 | W5 账本 D-3 写「铸币**必须显式给 operator**（`RegimeOperators.BY_REGIME` 不登记 `government_mint`）」——**推理对、结论不够** | 那条只考虑到"operator 键"，没考虑到 `EconomyPayloads` 解析期**仍会**为它推一次默认 | ★ **D-3 的结论保留**（本批确实**不**把 `government_mint` 放进 `RegimeOperators.BY_REGIME`），但它的**理由**要补一句：**光"显式给 operator"不足以让创世通过**，还必须在解析期不做无谓推导（F-1） |
| D-2 | 任务书假设「登记 `RegimeRelations.BY_REGIME` 是 P0 的唯一/主要修法」 | 实测三环（F-1/F-3/F-4）+ 一处硬崩（F-8），见 §③ 的 rc 递进表 | 任务书的"根因"描述**不完整**（异常消息误导，见 §⓪）；本批按实测修全 |
| D-3 | 任务书示例里的 `AssetRule`(WORKSHOP 份额) / `DefaultProductionModes` / `ProductionRoleResolver` / `LiquidationPolicy` | 逐条回代码核过：**都不需要新增登记** | 见 §⑦ 自查表 |
| D-4 | F-4 的修法属**口径级**判断（收窄 Z6 守卫 vs 对齐身份链） | 两条路都要么越范围、要么动既有不变量；按 fail-closed 取了"收窄到守卫自己文档化的主体 + 具名 DEBUG"，并在此**显式上报** | 控制方若不认这条，本批要回退该文件（其余 3 个文件与本条独立） |

## ⑦ 连带自查表（任务书 §1.2 要求逐项）

| 项 | 需要什么 | file:line 现状 | 缺了会怎样 | 补了 / 不补的理由 |
|---|---|---|---|---|
| `RegimeRelations.BY_REGIME` | 登记 `government_mint` | `RegimeRelations.java:305-312`（新；`BY_REGIME` 静态块） | 创世抛（第二环） | **补**（空表，见 §②(1)） |
| `RegimeOperators.BY_REGIME` | **不登记**（显式给 operator） | `RegimeOperators.java`（本批只加常量 + 类注） | 登记会给**错的**默认主体（产业 id 当家户 id） | **不补**（W5 裁定 D-3，理由见 §②(2)） |
| `EconomyPayloads.industrySpec` 的急切推导 | 改为惰性 | `EconomyPayloads.java:1445-1500`（新） | 创世抛（第一环、真凶） | **补**（旧载荷逐值不变） |
| `Z6` 服务 unit 不变量 | 判据收窄到 `modeKey==gov_service` | `GovernmentServiceUnitConsistency.java:124-151`（新） | 第一个关账日抛（第三环） | **补**（+ DEBUG 具名；口径级判断已上报） |
| `RegimeRelations.registered()` | 空规则表的档要跳过 `get(0)` | `RegimeRelations.java:560-574`（新） | 120 天长跑 `IndexOutOfBoundsException` | **补**（键集**零变化**，见 N-3） |
| `DefaultProductionModes` | 不需要新增 mode | `DefaultProductionModes.java` 的 `MODE_SPECS` 五项（`tenancy_*`/`wage_farm`/`handicraft_workshop`/`family_farm`/`merchant`/`displaced`） | —— | **不补**：铸币不是**迁移 mode**，它是一支**产业**；`ProductionProcess.modeKey = industry.id` 是既有口径（`EconomySeeder` 的 `unit.put("modeKey", mintId.value())`），且 `RegimeOperators.defaultRegimeForMode` 是 mode→regime 的白名单（铸币不经它） |
| `AssetRule`（WORKSHOP 份额） | 铸币的 WORKSHOP 需要资产规则吗 | `EconomySeeder.java:4143-4152` 给整额 `OWNED` 份额；`productionRuntimeAssetRuleNodes()` 已为**每个 mode × {LAND,TOOL,WORKSHOP,SHIP,CATTLE}** 发规则 | 缺规则只影响**租佃/转移**（`EconomyEnterpriseSettlement` 的 SHORTAGE 具名回退） | **不补**：铸币的产能是自有 `OWNED`，**没有**租佃腿；基准资产规则表已覆盖 WORKSHOP（`EconomySeeder.java` 的 `PRODUCTION_RUNTIME_ASSET_KINDS`） |
| `ProductionRoleResolver` | 需要按 regime 分支吗 | `grep regime simos-economy/.../migrate/ProductionRoleResolver.java` = **0 命中** | —— | **不补**：它按 `ProductionRole`（阶层位置）解析，**与 regime 无关** |
| `ExpectedProfitBook` 的制度分支 | 未登记时返回 null、不猜规则 | `ExpectedProfitBook.java:800-825`（`relationFor`，catch IAE ⇒ null） | 铸币 unit 的预期利润**原先恒走 `NO_RELATION`**；登记后走"空表关系" | **不补**（有意）：两条路的**结论一致**（无实物腿可折算 ⇒ 0）；★ 若要"铸币预期利润"是**货币腿**的读数，那是另一批（见 §⑤ 未验证-3） |
| `LiquidationPolicy` | 铸币需要清算政策吗 | `EconomyPayloads.java:530` 的 `liquidationPolicies(payload)` 读**可选键**；`EconomySeeder` 未发该键 | 缺键 = 该 `AssetRuleId` 无清算政策 ⇒ 既有 fail-closed（不是静默） | **不补**：铸币的资产是 `OWNED`、不涉抵押/清算腿；发一条无消费者的政策 = 死数据 |
| `ProductionEfficiencyBook` / 投入管线 | 铸币的 labor/tool 要能被真实消耗 | `EconomySettlement.java:1896-1901`（scale 收集）+ `:1971-1974`（MintSettlement 调用） | 不接管 ⇒ 钱凭空出现 | **不需要补**：W5 已接通（探针 C 实测工具 8000→6000 ⇒ 真实消耗） |
| 铸币产出的**市场入市** | 判据 §6.1-④ | W5 账本 BLOCKED-1（`GovernmentMarketMandatePlan` 无非自动订单） | 钱到国库但不会自动挂单 | **不补**（W5 已裁 BLOCKED-1；不属本任务） |
| 铸币的**劳动可供量** | 让规模 > 0 | `EconomySeeder.java:4047`（10‰ × 池日劳动，上限 = 剩余）= 373 毫劳动 vs `MINT_LABOR_MILLI_PER_FURNACE = 8000` | **小世界里铸币永不产出**（N-8） | **不补**（改它 = 改 W5 的标定口径 ⇒ 任务书禁止"顺手改 W5 已定的口径"；已转控制方，见 §⑤ 未完成-1） |

## ⑧ 改动文件的 md5（本批落盘字节，供控制方核对）

```
9c86dacc48615cb79a5a99ed8fc74c39  simos-economy/.../model/RegimeRelations.java
7dfadc8e0e75bf4ae916ade285a26751  simos-economy/.../model/RegimeOperators.java
013dcfac76475c7962f3aa82c49c4301  simos-economy/.../spi/EconomyPayloads.java
95284a1992589f47e853c4d6af0d1dce  simos-app/.../household/GovernmentServiceUnitConsistency.java
```
★ `git status` 里其余改动属 **W5 + W5b + W5-fix** 的既有未提交工作（本批**未回退、未重做、未触碰**任何 `src/test/**`）。

---

# W5-fix3（铸币标定：从 0 产出到能产）

> 追加节（不覆盖前文；前文 W5/W5b/W5-fix/W5-fix2 的叙述与结论原样保留）。
> 任务书：**让铸币在小世界真能产出 > 0 毫**，三条约束：① 配额 ≥ 一个单位规模的需求 ② 产出率（毫/劳动）与改前同比例
> ③ 产出率本身列 W6 标定项。**不动** W5 已定口径（产出放国库、上限 = min(政策旋钮, 上一轮印量)、自持债排除）。

## ⓪ 结论一句话

**改一个常量**：`MINT_LABOR_SHARE_PER_MILLE` **10 → 30**（`EconomySeeder.java:589`）。
小世界里铸币的日劳动配额从 **3,147 → 9,440** 毫劳动/日，跨过单位规模需求 **8,000** ⇒ 规模公式的劳动链
`laborScale = ⌊avgLaborMilli ÷ laborPerUnit⌋` 从 **0 → 1** ⇒ 第一个关账日（第 120 tick）铸出
**500 毫**（`MINT_PRODUCTION` 记录 **0 → 1** 条），国库银 **0 → 500 毫**、工具库存 **8,000 → 6,000**（真实消耗）。

## ① 三组数字（改前 / 改后，全部实测；探针读数见 §②）

| # | 量 | 改前（10‰） | 改后（30‰） | 算式 |
|---|---|---|---|---|
| 1 | **配额**（铸币 unit 的日劳动） | **3,147** 毫劳动/日 | **9,440** 毫劳动/日 | 配额 = `min(⌊池日劳动 × share ÷ 1000⌋, 作坊发完后的剩余预算)`；池日劳动 = **349,160** 毫劳动/日（参与率折扣后口径，实测）。改前 `⌊349,160×10÷1000⌋ = 3,491`（请求）→ 落表 3,147；改后 `10,474` → 落表 9,440 |
| 2 | **单位规模需求** | **8,000** 毫劳动/日·单位规模（未改） | **8,000**（未改） | `MINT_LABOR_MILLI_PER_FURNACE`；规模公式比的是**周期平均日劳动**（`avgLaborMilli = 本周期累计劳动 ÷ cycleDays`）⇒ 运算上读作"毫劳动/日·单位规模" |
| 3 | **产出率** | 500 毫/规模·周期（未改），**实际产出 0 毫** | 500 毫/规模·周期，**实际产出 500 毫/周期** | `MINT_MILLI_PER_FURNACE_CYCLE`；实际产出 = `规模 × 本值`，规模 = `min(劳动, 产能, 投入)` = `min(1, 1, 1) = 1`（第 120 tick 的发行记录原文：`scale=1 × perUnit=500 毫/规模，上限=2000`） |
| 附 | 比率（约束 ②） | 劳动 8,000 / 工具 2,000 / 产出 500 ⇒ **0.0625 毫/毫劳动、0.25 工具/毫劳动** | **逐值同改前**（三个出厂率一个字节没动） | —— |
| 附 | 作坊配额（不许抢） | 313,023 毫劳动/日 | **313,023**（逐值不变） | 铸币拿的是 `appendAllocation` 走完之后的剩余预算（同一张预算表多做一次读取） |

**选择理由（为什么改份额，不改单位规模）**：任务书约束 ② 要求"产出率/劳动与改前同比例"。**只提份额**时三条出厂率
（劳动 8,000 / 工具 2,000 / 产出 500）**逐值不变** ⇒ "毫/劳动""工具/毫劳动"两条比率**恒等**（比例 = 1:1），这是约束 ② 的最强形态：
既不让铸币变暴利，也不让工具变免费。反过来"缩单位规模"必须**同时等比**改三个常量，且会把"1 座铸炉"的物理含义改掉。
选 **30‰** 而不是"刚好够"的 ≈26‰：`laborBudget` 不给"时间预算为 0"的批次发配额，而 `appendAllocation` 按性别权重把总量摊到池里
**每个**批次上 ⇒ 摊到零预算批次的那份被预算封顶**静默削掉**（实测削 12 个批次：8 个老龄批次 `…s0-2 … s3-2`（老龄时间预算 = 0）
+ 4 个流民批次 `…d-1 / d-2`（D-023：流民没有工作），合计 1,034/10,474 ≈ **9.9%**）⇒ 留 ~18% 余量。

## ② 改动点（file:line + 前后各一行）

| # | 落点 | 改前 | 改后 | 性质 |
|---|---|---|---|---|
| E-1 | `simos-app/.../world/EconomySeeder.java:589` | `public static final int MINT_LABOR_SHARE_PER_MILLE = 10;` | `public static final int MINT_LABOR_SHARE_PER_MILLE = 30;` | ★ **唯一改数值的一行**（判据路径的份额） |
| E-2 | 同上 `:559-588` | javadoc 断言"10‰ … 给出 1 座铸炉 8,000 毫劳动够用的配额"（**W5-fix2 实测证伪**） | 改为实测算式（池日劳动 349,160 / 请求 3,491→10,474 / 落表 3,147→9,440 / 需求 8,000 / 规模 0→1 / 产出 0→500）+ 选择理由 + 零预算批次削掉的量 | 文档（不改行为） |
| E-3 | 同上 `:4083-4096` | `long poolDaily = industryDailyLabor(urbanPool) * SHARE / 1000;`（**名字误导**：它已是"请求量"不是"池日劳动"，且瓶颈是哪一项无从判读） | 拆成 `poolDailyLabor`（池日劳动）、`requestedLabor`（请求）、`slackAvailable`（剩余预算）+ `mintShare = min(requested, available)` | 重命名 + 具名读数（**逐值等价**：`min(池×share÷1000, 剩余)` 一字未变） |
| E-4 | 同上 `:4143-4172` | 只有"跳过"分支发 DEBUG（`SEED_MINT_SKIPPED`） | **两个分支都发具名 DEBUG**：新 `SEED_MINT_LABOR`（`poolDailyLaborMilli / sharePerMille / requestedLaborMilli / slackAvailableMilli / grantedLaborMilli / perUnitLaborMilli / unitScalesSupported`）；`SEED_MINT_SKIPPED` 补同族四个数 | ★ §一.9（判据路径要具名 DEBUG）；**W5 空转之所以没被早发现，正是因为"配额 > 0 但不够一个单位规模"这条路**没有任何读数** |
| E-5 | 同上 `:3926-3939` | javadoc 写"每 1 座铸炉**每周期**的劳动需求；8,000 = 8 人·周期" | 量纲澄清：规模公式比的是 `avgLaborMilli = 累计 ÷ cycleDays` ⇒ 读作**毫劳动/日·单位规模**；一个 120 天周期共吃 960,000 毫劳动；改本值必须同步等比改工具/产出 | 文档（不改行为） |
| E-6 | 同上 `:4076-4079` | "剩余不足一份铸币配额 ⇒ 具名跳过"（只覆盖配额 = 0 的路） | 补一句：真正的门槛是 `grantedLaborMilli ÷ MINT_LABOR_MILLI_PER_FURNACE ≥ 1`，比值 < 1 时**不会**触发跳过但规模仍 0 | 注释 |

★ **没有碰**：`MINT_LABOR_MILLI_PER_FURNACE` / `MINT_TOOL_MILLI_PER_FURNACE` / `MINT_MILLI_PER_FURNACE_CYCLE` /
`MINT_FURNACES_PER_CAPITA` / `MINT_SEED_CYCLES` / `GOVERNMENT_MINT_SCALE_PER_CYCLE_MILLI` 六个常量；
`MintSettlement` / `EconomySettlement` / `Industry` / `MoneyOutput` 一行未改；W5 的三条口径（产出放国库、上限 = min(旋钮, 上一轮印量)、自持债排除）一行未改。

## ③ 自证（真读数，探针在 `/tmp/w5fix3-probe/`，**不进 src**）

**探针口径（W5-fix2 留的坑已避开）**：classpath = 模块 `target/classes` 前置 + `simos-app/target/test-classes` +
**剔除 `~/.m2/io/mosire/simos-*`**（清单 `/tmp/simos-clean-cp.txt`）。装置 = `SmallWorld.state(mapId)` 创世 →
`GovZ6WorldFixture.StateRunner` 推进（★ 关账日 = 第 **120** 个 tick ⇒ 必须**一次调用** `advance(1,121)`；分两次调用会被读成"还没关账"，
W5-fix3 第一轮探针就踩了这个坑，见 §⑤-4）。

**探针 A：改前（未改 src 的真产物）**
```
PROBE recipe laborPerUnit=8000 inputPerUnit={tool=2000} moneyOutputPerUnit={silver=500} capacityPerUnit={WORKSHOP=1}
PROBE allocations.total=564 mintDailyQuotaMilli=3147
PROBE craftDailyQuotaMilli(0_0)=313023
PROBE mintCapacityScale=1
PROBE treasury0 money={} silver=0 tool=8000
PROBE MINT_PRODUCTION records=0 totalMilli=0
PROBE mintUnitAfter progressDays=0 cycleLaborMilli=0 cycleInputUsed={}
PROBE treasury1 money={} silver=0 tool=6000
```
⇒ 关账真的发生了（`cycleInputUsed` 归零、工具 8000→6000），但 **records = 0**：3,147 < 8,000 ⇒ 规模 0（复现 W5-fix2 的现场）。

**探针 B：改后（改 src + Maven 重编译后的真产物；classpath 里**没有**任何 patched 目录）**
```
PROBE allocations.total=564 mintDailyQuotaMilli=9440
PROBE craftDailyQuotaMilli(0_0)=313023          ← 作坊逐值不变
PROBE mintCapacityScale=1
PROBE treasury0 money={} silver=0 tool=8000
PROBE MINT_ISSUANCE id=mi-mint-world-silver-121-silver day=121 period=4 cur=silver amount=500
      reason=铸币生产方式：scale=1 × perUnit=500 毫/规模，上限=2000（政策旋钮=2000，产出请求=500）
PROBE MINT_PRODUCTION records=1 totalMilli=500
PROBE mintUnitAfter progressDays=0 cycleLaborMilli=0 cycleInputUsed={}
PROBE treasury1 money={silver=500} silver=500 tool=6000     ← 国库 +500 毫；工具 -2,000（真实消耗）
PROBE mintDebtContracts=1
```
⇒ **`scale = 1 > 0`**（发行记录原文）、**记录数 1 ≥ 1**、**国库货币 0 → 500**、**工具 8,000 → 6,000**。四条判据全中。

**探针 C：两轮连跑（240 tick）**：`day=121 period=4 amount=500` + `day=241 period=8 amount=500` ⇒ records = 2、total 1,000、
国库银 1,000、工具 4,000 ⇒ **不是一次性**（每轮都铸）。

**探针 D（负向：工具不是免费）**：600 tick（五个关账日）⇒ **只有 4 条发行记录**（day 121 / 241 / 361 / 481，各 500 毫，
合计 2,000），第 5 个关账日**没有记录**；工具库存 **8,000 → 0**。⇒ 铸币受**真实投入**约束（`MINT_SEED_CYCLES = 4` 吃完即停），
"工具免费/凭空造钱"的反面成立。

**探针 E（§一.9 落点自证，真路径真日志）**：`-Dsimos.app.logLevel=DEBUG` 下创世即出
```
DEBUG io.mosire.simos.app.shell - event=SEED_MINT_LABOR household=hh-gov-world-silver hex=0,0
  poolDailyLaborMilli=349160 sharePerMille=30 requestedLaborMilli=10474 slackAvailableMilli=5018977
  grantedLaborMilli=9440 perUnitLaborMilli=8000 unitScalesSupported=1
```
⇒ 新判据路径有具名读数，且 `unitScalesSupported = 1` 正是"能产"的判据数。

**创世不回归（任务书允许的那一条，真命令与 rc）**
```
$ tools/mvn-lock.sh test -pl simos-app -am -Dtest='GovGenesisZ6Test' -Dsurefire.failIfNoSpecifiedTests=false
[test rc=0]
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 37.55 s -- in io.mosire.simos.app.gov.GovGenesisZ6Test
[INFO] BUILD SUCCESS
surefire 报告真数：simos-app/target/surefire-reports/io.mosire.simos.app.gov.GovGenesisZ6Test.txt
  内容 "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0"，mtime 2026-10-11 12:00:02（本轮；跑前 rm 过旧报告）
```
★ 其中 `oneHundredTwentyDaysAdvanceWithoutContractFailure` 正是**跑满 120 天（走到铸币关账日）**的那条 —— 改后仍绿。

**门禁（真命令与 rc）**
```
$ tools/mvn-lock.sh -q spotless:apply                       → rc=0（无输出）
$ tools/mvn-lock.sh -DskipTests compile -am                → rc=0；[INFO] BUILD SUCCESS；SimosApp SUCCESS [5.542 s]
  （末次：源文件 mtime 12:00:29 → EconomySeeder.class 重编译于 12:00:40 ⇒ 报告的门禁落在**最终字节**上）
  落盘字节：md5 dd120b0582ede8b331f63e7e686f5e06  simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java
```

## ④ 会改变数值行为的清单（逐条具名）

| # | 行为 | 改前 | 改后 | 影响面 |
|---|---|---|---|---|
| **N-1** | **小世界/demo 世界的铸币产出**（有政府家户的世界才播铸币产业） | 每轮 **0 毫**（配额 3,147 < 需求 8,000 ⇒ 规模 0） | 每轮 **500 毫**（配额 9,440 ≥ 8,000 ⇒ 规模 1），持续到工具吃光（4 轮） | `MINT_PRODUCTION` 记录 0→1 条/轮；国库银 0→500/轮；政府自持债合同 0→1 条/轮 |
| **N-2** | 铸币 unit 的**劳动配额**（`LaborAllocation` 行） | 3,147 毫劳动/日（12 行） | 9,440 毫劳动/日（同 12 行，逐行值变大） | 只多一条"活动"的行；**该格其余活动的配额逐值不变**（作坊 313,023 实测不变） |
| **N-3** | **铸币的投入消耗**（工具） | 每轮仍扣 2,000 毫工具（`drawCycleInputs` 按**计划产能**扣，与规模无关） | **不变**（2,000/轮） | ★ 改前那 2,000 是"扣了但没有产出"（工具被浪费）；改后同一笔投入换到 500 毫产出 |
| **N-4** | 政府家户（`hh-gov-world-silver`）的**货币余额轨迹** | 恒 `{}`（0 毫） | 每轮 +500 毫（4 轮后 2,000 毫） | 国库户的**市场下单能力**从"永远没钱"变成"有钱可花"⇒ 后续市场轮里该户**可能开始出价/成交**（W5-fix3 未逐笔核，见 §⑤-2） |
| **N-5** | `EconomyData.moneyIssuances` / `debtContracts` 的**条数与内容** | 无 `MINT_PRODUCTION` | 每关账轮 +1 条发行记录（id = `mi-mint-world-silver-<day>-silver`）+ 1 条自持债合同 | 任何"货币总量恒等于创世基线"的断言在**小世界**上不再成立（W5 已裁：那是设计）；`three-powers` 世界没有政府家户 ⇒ **不受影响**（`EconomyCycleHealthTest` 用它） |
| **N-6** | 新增一条 **DEBUG** 日志（`SEED_MINT_LABOR`；`SEED_MINT_SKIPPED` 的字段增多） | 只有跳过分支有 DEBUG | 两分支都有，字段更多 | 只影响 `-Dsimos.app.logLevel=DEBUG` 时的输出；**不改状态、不改公式**（§一.9 纪律） |
| **N-7** | 局部变量重命名 `poolDaily` → `poolDailyLabor`/`requestedLabor` | —— | —— | **零行为变化**（逐值等价，`min(池×share÷1000, 剩余)` 一字未变） |

★ **硬编码字面量清单（给测试代理当输入）**：`10 → 30`（`MINT_LABOR_SHARE_PER_MILLE`，`EconomySeeder.java:589`）。
**其余铸币字面量一个都没动**：`8_000`（劳动/规模·周期）、`2_000`（工具/规模·周期）、`500`（毫/规模·周期）、`1`（铸炉/首都）、
`4`（种子周期数）、`2_000`（政策旋钮）。★ 小世界的**期望读数**（可作为断言输入）：
配额 **9,440** 毫劳动/日、作坊配额 **313,023** 毫劳动/日、池日劳动 **349,160** 毫劳动/日、剩余预算 **5,018,977** 毫小时、
首个关账日 = **第 120 tick**（发行记录 day=121）、每轮铸 **500 毫**、4 轮共 **2,000 毫**、工具 **8,000 → 6,000 → … → 0**。

## ⑤ 未完成 / 未验证项（如实）

1. **没跑全量测试**（任务书只允许 `GovGenesisZ6Test` + 编译门禁）。**可能受影响的既有测试已逐个静态核过**：
   `GovGenesisZ6Test`（跑满 120 天，**已真跑：5/5 绿**）、`GovToolsZ6Test`（只推 0→1 天）、`Z7D1CapitalProvinceTest`（≤3 天）、
   `Z7RemittanceE2ETest`（30 天）⇒ 后三者**走不到关账日 120**，不受影响；`EconomyCycleHealthTest` 用 `three-powers`
   （**无政府家户 ⇒ 不播铸币产业**，其 javadoc 第 40-70 行自述）⇒ 不受影响；`MintProductionSettlementTest`（economy）
   自带硬编码常量、**不读本份额** ⇒ 不受影响。★ **仍属"静态核过 + 部分真跑"，不是全量回归**。
2. **未逐笔核**"国库有 500 毫之后的市场行为变化"（N-4）：国库户的钱变多，理论上会影响它后续的市场下单/成交。
   我没跑市场轮级读数（不在本任务判据内）。★ 若控制方要"铸币入市"的读数，那是 W5 BLOCKED-1 的那条线（不属本批）。
3. **未验证**"上一轮印量"闸门在本世界是否真的咬合：实测发行记录的 `period` 是 **4 / 8 / 12 / 16**（每轮 +4），
   而 `MintSettlement.previousCycleMinted` 找的是 `currentCycle − 1`（3 / 7 / 11）⇒ **永远找不到上一条** ⇒ 上限回落到
   政策旋钮 2,000。本轮 `requested = 500 < 2,000`，故**结论不受影响**（每轮都铸 500）；但"上限 = 上一轮印量"这条口径
   在 `period` 步进 ≠ 1 的世界里**是空转的**。★ 这是 **W5 的既有口径**（任务书明令不许动），**我只报不改**：请控制方裁定
   `period` 的口径或闸门窗口（W6 首查建议项）。
4. **一处探针坑（如实记，供后续复用）**：`GovZ6WorldFixture.StateRunner.advance(from, to)` 处理的是 `(from, to]`
   —— **一次调用 `advance(1,121)` = 120 tick 才走到关账日**；拆成 `advance(1,119)` + `advance(120,121)` 只有 119 tick，
   读数会显示 `progressDays=119`、`records=0`（**看起来像"改了还是不产"，其实是还没关账**）。W5-fix3 第一轮探针就踩了这个，
   靠"直接 instrument 播种器打印配额/剩余"才把因果断开。
5. **未核**其他世界的量级（`WorldRegistry` 里别的 profile / 更小的城镇）：**份额是"池日劳动的千分比"**，故配额随池子大小线性变化；
   小世界首都（700 城镇人口）已实测够用（9,440 / 8,000 = 1.18×）。★ 若某个世界首都的城镇池 **< 267,000** 毫劳动/日，
   配额会掉到 8,000 以下 ⇒ 那里铸币仍不产（这是合法状态，也是 W6 标定的边界）。
   ⇒ **我报出这个边界，没有为它加保护性代码**（那要动"配额从池子按份额取"的口径）。
6. **未跑** `verify` / `clean` / `package`（任务书禁止）；SpotBugs / 前端 node 门禁随 `test` 阶段之外，**未单独核**。
7. **探针不进库**：`/tmp/w5fix3-probe/*.java`（5 个小程序）与 `/tmp/simos-clean-cp.txt` 只在会话内存在；
   instrument 过的 `EconomySeeder` 副本只在 `/tmp/w5fix3-probe/patched*`，**没有进 `src/`**。
8. **`docs/**` 里一处旧数字未改（任务书禁止改 docs）**：`docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md:307`
   写"`10‰ × 池日劳动 = 373 毫劳动`"—— 实测口径是 **3,147 毫劳动/日**（"373"是**周期累计** 373,085 毫劳动，
   被当成了日配额；结论"< 8,000 ⇒ 不产"仍然对）。★ 建议控制方在 W6 文档里更正这一处措辞。

## ⑥ 与任务书 / 约束设计书的偏离

| # | 偏离 | 原因 | 影响 |
|---|---|---|---|
| D-1 | 任务书 §1 给的三条路线里我选了"改 share"，且**只改了一个**常量（没动单位规模需求） | 约束 ② 的最强形态：份额不变时三条出厂率恒等 ⇒ 比率 1:1；且它**不抢作坊**（拿的是剩余），改动面最小 | 产出率本身（500 毫/周期）留给 W6（任务书 §3 明说它是 W6 项） |
| D-2 | 额外改了 javadoc（E-2/E-5/E-6）与局部变量名（E-3） | E-2 的原文**与实测矛盾**（它声称 10‰ 够用），留着会把下一个标定的人带错方向；E-5 的"8 人·周期"与规模公式的量纲不符 | **零行为变化**（`compile` 后 `.class` 语义同前；四份探针读数可复核） |
| D-3 | 报了"`previousCycleMinted` 窗口在本世界空转"（§⑤-3）但**没改** | 任务书明令"不许动 W5 已定口径（上限 = min(政策旋钮, 上一轮印量)）" ⇒ 按 fail-closed 只报不改 | 本批结论不受影响（500 < 2,000）；列为 W6 首查项 |

## ③-附 探针 F：铸币 unit 的**周期累计劳动**前后值（补 §③ 的"劳动前后值"那一半）

```
PROBE8 genesis  mintUnit progressDays=0   cycleLaborMilli=0
PROBE8 day119   mintUnit progressDays=119 cycleLaborMilli=1,118,579  cycleInputUsed={tool=2000}   ← 关账前
PROBE8 afterClose mintUnit progressDays=0 cycleLaborMilli=0          cycleInputUsed={}            ← 关账后（周期状态清零）
PROBE8 records=1 minted=500 treasurySilver=500 treasuryTool=6000
```
⇒ **劳动真的被计进这个 unit**：119 天累计 1,118,579 毫劳动 ⇒ 日均 **9,399.8** 毫劳动/日 ≥ 8,000（表上配额 9,440；
差 40/日 来自配额表到 `laborByUnit` 的每日折算，不影响"≥ 一个单位规模"）。**工具前后值**：8,000 → 6,000（关账轮扣 2,000）。
★ 关账时 `cycleLaborMilli` / `cycleInputUsed` **同时清零**，故"周期内前后值"只能在关账**前**取（这是探针 F 存在的理由）。

---

# W5-fix4（铸币上限真正生效）

> 追加节（**不覆盖前文**；W5 / W5-fix / W5-fix2 / W5-fix3 的叙述与结论原样保留）。
> 任务书 = 控制方对 W5-fix3 §⑤-3 的**裁定（必修）**（`docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md`
> v3.17 §7："`MintSettlement.previousCycleMinted` 的'上一轮'匹配是错的 ⇒ 上限形同虚设"）：
> ① 用**权威的轮次索引/日窗口**（与统计簿/市场轮同一口径）算上一轮发行额 ② 上轮印 X ⇒ 本轮 ≤ X，超额 INFO `MINT_LIMIT_REJECTED`
> （`limit`/`previousCycleMinted`/`requested`/`rejected` 给真值）③ **首轮无上一轮 ⇒ 上限 = 政策旋钮**（保留 W5 账本 D-1，不许改成"首轮不许铸"）
> ④ 不许动 W5 其它口径（产出放国库、自持债排除、产出率常量、`MINT_LABOR_SHARE_PER_MILLE=30`）⑤ 若查明 `period` 本身写错，改写入或改读口径都可以，但要给判别证据。

## ⓪ 结论一句话

**换掉读窗口**：`period == 当前周期 − 1` → **`day` = 最近一个严格早于本轮的铸币日**（绝对日，与统计簿认"轮"同一口径）。
一行判据式的改动 + 一条具名 DEBUG；**`period` 的写入一个字没动**（它与自己的契约一致，见 ① 的判别结论）。
实测：第 1 轮印 200（`limit=200` = 政策旋钮）⇒ 第 2 轮把旋钮抬到 2,000 **实印仍 200**、`MINT_LIMIT_REJECTED ... limit=200 previousCycleMinted=200`
⇒ **上轮印 X ⇒ 本轮 ≤ X 真正生效**；旋钮 ≤ X 时实印 = 旋钮（不误伤）。

## ① `period` 到底是什么 / 权威轮次是哪个（file:line + 判别结论）

| # | 事实 | 出处（file:line） | 原文 / 读数 |
|---|---|---|---|
| I-1 | `MoneyIssuanceRecord.period` 的**契约** = 经济周期序号 | `simos-economy-api/.../api/money/MoneyIssuanceRecord.java:26`（校验在 `:50-51`） | `@param period 经济周期序号（≥ 1；创世为 1）`；`period <= 0 ⇒ 抛` |
| I-2 | 铸币记录的 `period` **写入值** = `currentCycle` | `simos-economy/time/MintSettlement.java:421-425`（本类唯一的记录构造点） | `new MoneyIssuanceRecord(id, gov, day, currentCycle, currency, minted, MINT_PRODUCTION, …)` |
| I-3 | 命令面同源写法 | `simos-economy/spi/EconomyRecordMoneyIssuanceHandler.java:125` | `long period = base.meta().map(EconomyMeta::currentCycleNumber).orElse(1L);` |
| I-4 | `currentCycle` 的**定义**（派生式，不另存） | `EconomySettlement.java:1164`；`EconomyMeta.java:99-102` | `long currentCycle = meta.lastClosedCycle().orElse(0L) + 1L;`；`currentCycleNumber() = lastClosedCycle.orElse(0)+1`（`@JsonIgnore` 派生判据） |
| I-5 | 什么推进它：**任一产业关账** | `EconomySettlement.java:1863`（`progressed >= industry.cycleDays()` ⇒ `anyCycleClosed`）；`:3333` | 当天结束 `anyCycleClosed ? OptionalLong.of(currentCycle) : meta.lastClosedCycle()` ⇒ **一天最多 +1，且不分产业/unit** |
| I-6 | 小世界两个周期常量 | `simos-app/.../world/EconomySeeder.java:339`；`simos-app/.../tools/write/GovWorldBootstrap.java:156` | `CYCLE_DAYS = 120`（铸币在内的 43 个产业）、`OFFICE_CYCLE_DAYS = 30`（官署 2 个）。★ 探针实测创世 histogram = **`{120=43, 30=2}`** |
| I-7 | 权威的"轮"身份 = **绝对日** | `simos-economy/model/MarketRoundStats.java:19`、`:139`、`:143-150`、`:26` | `lastRoundDay 最近一次写入统计的市场轮的绝对日（-1 = 还没有过任何市场轮）`；`@param day 本轮市场轮的绝对日（≥ 0）`；`withRound(day, …, cycleClosed)`；`previousCycle 上周期累计（关账轮转，不是"从上周期以来一直加"）` |

**判别结论（三条证据合起来才断得开因果）**：

1. **`period` 没写错**：它 = 全局"关账次数 + 1"（I-4/I-5），与自己的契约 I-1 逐字一致（`EconomyRecordMoneyIssuanceHandler:125` 是同一条式子）。
2. **它不是"铸币 unit 的第几轮"**：每 120 天里官署关账 4 次（I-6）⇒ `currentCycle` 在两次铸币之间 **+4**。实测（本批探针，见 ③）：
   铸币记录 `day / period` = **121/4、241/8、361/12、481/16**，而"当前周期 − 1" = **3 / 7 / 11** ⇒ **永远匹配不到**（W5 那条窗口在该世界恒空 ⇒ 上限恒回落旋钮 2,000）。
3. **权威轮次 = 绝对日**（I-7）：统计簿认轮就是 `lastRoundDay`，周期窗口靠**关账轮转**而不是序号相减 ⇒ 铸币记录只在关账日写，"最近一个严格早于本轮的铸币日"就是**上一轮**，不需要第二份轮次定义、不新开状态组件。
   ★ 反证：若按"上一**市场轮**"（近每日一轮）取窗口，绝大多数轮没有铸币记录 ⇒ 上限恒 0 ⇒ 机制永远启动不了（与任务书 ③ 直接冲突）⇒ 只有"铸币轮"这一读法自洽。

## ② 改动点（file:line + 前后各一行）

| # | 落点 | 改前 | 改后 | 性质 |
|---|---|---|---|---|
| C-1 | `simos-economy/.../time/MintSettlement.java:330-369` | `previousCycleMinted(session, gov, currentCycle)`：`record.period() != currentCycle − 1 ⇒ continue`（窗口 = `period == 当前周期−1`）；`currentCycle <= 1 ⇒ 空` | `previousRoundMinted(session, gov, day)`：`record.day() >= day ⇒ continue`；取 `max(day) < 本轮 day` 的那一轮并把落在该日的全部 `MINT_PRODUCTION` **相加**（同政府、跨币种）；返回 `Optional<PreviousRound>(day, minted)` | ★ **唯一改判据的一处** |
| C-2 | 同上 `:129-136` | `long previousMinted = previousCycleMinted(session, government, currentCycle).orElse(government.mintScalePerCycle());` | `Optional<PreviousRound> previousRound = previousRoundMinted(session, government, day);`<br>`long previousMinted = previousRound.map(PreviousRound::minted).orElse(government.mintScalePerCycle());` | 上限式子 `Math.min(knob, previousMinted)` **一字未动**；首轮回退**保留** |
| C-3 | 同上 `:137-161`（新增） | 无（判据路径只有"拒了/结了"两条 INFO，读不出窗口取的是哪一轮） | **DEBUG `MINT_LIMIT_WINDOW`**（§一.9 具名）：`day / period / government / previousRoundDay / previousCycleMinted / mintScalePerCycle / limit / reason`；`reason` 两值具名 = `previous-mint-round-day-window` \| `no-previous-mint-round-policy-scale`（无上一轮时 `previousRoundDay=-1`、`previousCycleMinted=-1`） | 只多日志，不改状态/公式 |
| C-4 | 同上 `:56-81`（类注）+ `:330-369`（方法注） | 类注写 `period == 当前周期 − 1`、`上一轮印量(政府, 币种)` | 日窗口公式 + 一节"为什么窗口是绝对日而不是 `period−1`"（含 ① 的 file:line 与实测数字）+ 量纲澄清 | 文档；**零行为变化** |

**为什么这样算才对**："上一轮"在这条口径里 = **这个铸币 unit 上一关账轮**（用户原话「政府这轮能新投入多少取决于上一轮印了多少」的"轮"）。
铸币记录**只在关账日写**（`settleCycleClose` 的落点见 `EconomySettlement.java:1971-1973`）⇒ "最近一个严格早于本轮的铸币日"与"上一关账轮"是同一件事，
且它用的是记录自带的权威字段 `day`（统计簿同一口径），不是我自己发明的轮次定义。

## ③ 自证（真读数；探针在 `/tmp/w5fix4-probe/`，**不进 src**）

**装置**：`SmallWorld.state("W5Fix4Probe")` 创世 → `GovZ6WorldFixture.StateRunner` 每轮 `advance(from,to]` **120 tick**（关账日 = `to`：121/241/361/481）；
轮间只在**内存**里用 `Government.withMintScalePerCycle` 改政策旋钮（探针不写仓）。
classpath = `/tmp/simos-clean-cp.txt`（模块 `target/classes` 前置 + 剔除 `~/.m2/io/mosire/simos-*`），脚本自证 `stale simos-* jars on classpath = 0`。
★ **新坑（供后续复用）**：该清单里的模块条目是**相对仓根**的路径 ⇒ `javac` / `java` 必须在仓根下跑（W5-fix3 的脚本是绝对路径，照抄会 31 个 `cannot find symbol`）。

**判据三格（真读数；日志原文逐字）**

| 格 | 轮 / 旋钮 | 真读数 | 结论 |
|---|---|---|---|
| **①** | 第 1 轮 `day=121 period=4`，旋钮 **200** | 记录 `amount=200`（`X = 200`）；INFO `MINT_LIMIT_REJECTED day=121 period=4 government=world-silver currency=silver scale=1 perUnit=500 requested=500 limit=200 previousCycleMinted=200 mintScalePerCycle=200 rejected=300`；DEBUG `MINT_LIMIT_WINDOW day=121 period=4 … previousRoundDay=-1 previousCycleMinted=-1 mintScalePerCycle=200 limit=200 reason=no-previous-mint-round-policy-scale`；国库银 0 → 200，工具 8,000 → 6,000 | 首轮无上一轮 ⇒ **`limit` = 政策旋钮 200**（任务书 ③ 保留项，未改成"首轮不许铸"）|
| **②** | 第 2 轮 `day=241 period=8`，旋钮抬到 **2,000 (> X)** | 记录 `amount=200`（**实印 ≤ X**）；INFO `MINT_LIMIT_REJECTED day=241 period=8 … requested=500 limit=200 previousCycleMinted=200 mintScalePerCycle=2000 rejected=300`；DEBUG `MINT_LIMIT_WINDOW day=241 … previousRoundDay=121 previousCycleMinted=200 mintScalePerCycle=2000 limit=200 reason=previous-mint-round-day-window`；国库银 200 → 400 | ★★ **本批核心判据：`limit == X = 200`、实印 200**（改前这里是 500，见下面的变异轮） |
| **③** | 第 3 轮 `day=361 period=12`，旋钮 **100 (≤ X)** | 记录 `amount=100`；INFO `… requested=500 limit=100 previousCycleMinted=200 mintScalePerCycle=100 rejected=400`；国库银 400 → 500 | **实印 = 旋钮 100**（不误伤）|
| 附 | 第 4 轮 `day=481 period=16`，旋钮再抬回 **2,000 (> 上一轮 100)** | 记录 `amount=100`；INFO `… limit=100 previousCycleMinted=100 mintScalePerCycle=2000 rejected=400` | 上限跟的是**上一轮**（100）而不是更早的 200 ⇒ 窗口是"最近一轮"不是"累计/更早轮" |

工具库存 8,000 → 6,000 → 4,000 → 2,000 → 0（投入真实消耗；第 5 个关账日起无记录 —— 与 W5-fix3 探针 D 同）。
`period` 步进 **4/8/12/16** 与 `day` 步进 **120** 都在同一次运行里打印（`PROBE ROUNDn MINT day=… period=…`），是 ①-2 的直接证据。

**默认旋钮的对照读数（N-2 的"逐值不变"是实测，不是推导）**：旋钮全程保持出厂值 2,000（探针第二个参数 `keep-knob`，`probe-default-knob.log`）
⇒ 4 轮各 **500** 毫（合计 **2,000**）、国库银 2,000、工具 0、**`MINT_LIMIT_REJECTED` 0 条**（`requested 500 ≤ limit 500` 恰好卡在边界，不触发拒绝）
—— 与 W5-fix3 探针 C/D 的读数**逐值相同** ⇒ 修这条窗口**没有**改动默认小世界的货币轨迹。

**变异自证（判据有判别力，不是"恰好绿"）**：把窗口那一行改回 `|| record.period() != day - 1L`（= 退回 W5 的 period 窗口）
⇒ 同一探针第 2 轮 **`amount=500`（> X=200）**，且 **day=241 没有 `MINT_LIMIT_REJECTED`**（整轮只有 day=121 那一条）⇒ **当场红**。
变异体 md5 `c825abe3b206c120741f73dcce4a6a74` vs 原件 `2a0cd2f5874ed034dcb06d4064853731`（**字节不同 ✓**，且编译通过 ⇒ 红的是判据不是编译错误）；
还原后 `md5sum -c` = `OK`、`grep -c MUTANT` = 0，重编译后 class md5 回到 **`98509074d00a9e476c3d8229df8e122d`**（与修复轮探针记录的那份字节同一个 md5）。

**两条回归（真命令 + rc + surefire 真数；跑前 `rm` 过旧报告）**

```
$ tools/mvn-lock.sh -q spotless:apply                                        → rc=0（无输出）
$ tools/mvn-lock.sh -DskipTests compile -am                                  → rc=0；BUILD SUCCESS（SimosApp 2.319 s / 末次 2.680 s）
$ tools/mvn-lock.sh test -pl simos-app -am -Dtest='GovGenesisZ6Test' -Dsurefire.failIfNoSpecifiedTests=false
  [rc=0] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0（38.60 s）
  surefire 报告 simos-app/target/surefire-reports/io.mosire.simos.app.gov.GovGenesisZ6Test.txt
    内容 "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0"，mtime 2026-10-11 12:08:39（本轮）
$ tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MintProductionSettlementTest' -Dsurefire.failIfNoSpecifiedTests=false
  [rc=0] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0（0.130 s）
  surefire 报告 …/io.mosire.simos.economy.time.MintProductionSettlementTest.txt，mtime 2026-10-11 12:08:45（本轮）
```
落盘字节：`md5 2a0cd2f5874ed034dcb06d4064853731  simos-economy/src/main/java/io/mosire/simos/economy/time/MintSettlement.java`。

## ④ 会改变数值行为的清单（逐条具名）

| # | 行为 | 改前 | 改后 | 影响面 |
|---|---|---|---|---|
| **N-1** | **"上一轮印量"窗口** | `period == 当前周期 − 1`（小世界 `period` 步进 4 ⇒ **恒空**） | 最近一个严格早于本轮的**铸币日**的 `MINT_PRODUCTION` 之和 | 只有"本轮请求 > 上一轮实印"时才改变读数 |
| **N-2** | **一轮的实铸额** | `min(requested, 旋钮)` | `min(requested, min(旋钮, 上一轮实印))` | ★ **默认小世界逐值不变**（旋钮 2,000、每轮请求 500、上轮实印 500 ⇒ `500 ≤ 500`，4 轮各 500 毫仍成立；**已实测**：`probe-default-knob.log` = 4×500、拒 0 条）；旋钮被调高、规模变大、或某轮少印后恢复时才咬合 |
| **N-3** | 新增 **DEBUG** `MINT_LIMIT_WINDOW` | 无 | 每政府每关账轮一条 | 只影响 `-Dsimos.economy.logLevel=DEBUG`（默认 INFO 不输出）；**不改状态、不改公式** |
| **N-4** | 类注/方法注措辞（含"跨币种求和"） | 注里写 `period == 当前周期−1` / `(政府, 币种)` | 与代码一致 | **零行为变化** |

★ **一个字没动的**：`Math.min(requested, limit)`、`rejected = requested − limit`、`MINT_LIMIT_REJECTED` 的**字段名与 INFO 级别**、
首轮启动额回落、产出放国库、自持债排除、`MINT_*` 与 `MINT_LABOR_SHARE_PER_MILLE=30`、**`period` 的写入**（`MintSettlement.java:425` 仍是 `currentCycle`）、
`EconomySettlement` 的调用点（`:1971-1973` 逐字未改）。

## ⑤ 未完成 / 未验证项（如实）

1. **只跑了任务书允许的两条回归**（`GovGenesisZ6Test` 5/5、`MintProductionSettlementTest` 5/5）；**没跑**全量 `test` / `verify` / `clean` / `package`（禁止项）。
2. ★★ **既有 economy 测试对这条窗口没有判别力**（这是 W5 的洞能活下来的原因，如实记）：`MintProductionSettlementTest` 判据③ 的夹具里
   `period` 恰好**步进 1**（预置 day=1/period=1，本轮 day=2/period=2）⇒ 新旧两种窗口它**都绿**。判别只能靠本批 `/tmp` 探针（含变异轮）。
   建议测试批补一条"`period` 步进 ≠ 1（如 4）的上一轮记录"的夹具 —— 那才是这条口径的判别用例（本批**不写测试**，只报）。
3. **静态核过 ≠ 跑过**：`EconomyCycleHealthTest`（`three-powers`：3 个 GOV 单位旋钮缺省 0、无铸币产业 ⇒ 不受影响）、
   `MarketSettlementSingleHexLossTest` / `HaulParallelMachineRetirementGuardTest`（那里的 "mint" 是运费铸腿，无关）。**未真跑**。
4. **多币种世界的闸门量纲**：`previousRoundMinted` **跨币种求和**、`limit` 对每个币种共用（沿用 W5 的"每政府一个数"；旧实现同样没有按币种过滤）。
   单币种小世界无差别；多币种世界是已知**粗口径**（未改，列 W6 可裁定项）。
5. **"印了 0 毫的那一轮"不可见**：铸币 0 毫不写记录 ⇒ 若上一轮规模 0（劳动/工具不足），窗口会落到"最近一个真印过的轮"。
   ★ 这是 W5 既有语义（"上一轮一笔都没印 ⇒ 空 ⇒ 首轮启动额"）在日窗口下的形态；要严格区分"上一轮印了 0"需要新的状态位
   （W5 明确不新开状态组件）⇒ **未做，只报**（fail-closed 面：它只会让上限**偏松**到"最近一次真实印量"，不会凭空造钱）。
6. **没验**：落盘 / 增量会话（core revision + codec 往返 + replay）下的窗口读数。窗口只读 `moneyIssuances` 的值、与落盘无关，但本批没跑那条链。
7. **没验**：`gov.issueMoney`（`FISCAL_ISSUE`）是否应计入"印量"。窗口只数 `MINT_PRODUCTION`（沿用 W5）⇒ DM 行政发行**不**抬高上限（偏严一侧，fail-closed）。列 W6 可裁定。

## ⑥ 与任务书 / 控制方裁定的偏离

| # | 偏离 | 原因 | 影响 |
|---|---|---|---|
| D-1 | 窗口取"**绝对日**（最近一个铸币日）"，而不是字面的"市场轮序号" | 本仓**没有**"市场轮序号"这种状态：统计簿认轮就是 `lastRoundDay`（绝对日）；而"上一**市场轮**"（近每日一轮）会因绝大多数轮没有铸币记录而恒空 ⇒ **机制永远启动不了**，与任务书 ③ 冲突 | 满足"上轮印 X ⇒ 本轮 ≤ X"，且与统计簿同一口径（用记录自带的 `day`，未发明第二个轮次定义） |
| D-2 | **没改** `MoneyIssuanceRecord.period` 的写入 | 判别证据（① 的 I-1~I-5）显示它与自己的契约一致；改写入会破坏 API 契约并让 `EconomyRecordMoneyIssuanceHandler:125` 的同源写法失配 | 零 API 变化；本批是**读侧**修复 |
| D-3 | 新增一条 DEBUG（C-3） | §一.9：改动路径的判据要有具名 INFO/DEBUG；原有两条只答"拒了/结了"，答不出"窗口取的是哪一轮" | 只多日志 |
| D-4 | 类注 `(政府, 币种)` 改成 `(政府)` | 代码（含改前）从来没按币种过滤 ⇒ 注与实现不符（§四）；本批只把注改成与实现一致，**不动**闸门量纲 | 零行为变化 |

## ⑦ 只报不改（W5 遗留事实）：`Government.mintedLastCyclePerCycle` **恒为空表**

- **契约**：`simos-economy/model/Government.java:57-62` 说它是"上一市场轮逐币种实际印量（毫）……判据 ③ 的**可持久读数**……由 `MintSettlement` 在每轮关账时**覆盖写**"。
- **实际**：全仓 `mintedLastCyclePerCycle` 只有三处 —— `Government` 自身（字段/构造/校验/with）、
  `EconomyRegisterGovernmentHandler.java:361`（登记时**原样保留**旧值）、`ApiViews.java:1676`（GUI 读出来显示）；**没有任何写点**。
  本批探针 4 轮后实测三个政府都是 **`mintedLastCyclePerCycle={}`**（GUI 那一栏因此永远是空的）。
- ⇒ 属 §四 同族的"看起来在记、其实永远不会被写"。**本批不修**：闸门不读它（空表不可能放大铸币，fail-closed），
  而给它接写点 = 新增状态写口 + ChangeSet/快照面，超出本批范围。**建议 W6 二选一**：接上写点（它才算"可持久读数"）或按 W5 账本 D-4 删字段。

## ⑧ 探针落点（不入库）

`/tmp/w5fix4-probe/`：`W5Fix4Probe.java`（4 轮，可 `arg` 限轮数 / `keep-knob` 保持出厂旋钮）、`run-probe.sh`（含 classpath 自证）、
`probe-info.log`（INFO，4 轮，判据 ①②③+附）、`probe-debug.log`（DEBUG，2 轮，`MINT_LIMIT_WINDOW`）、
`probe-mutant.log`（变异轮，第 2 轮 500 > X）、`probe-default-knob.log`（旋钮不改：4×500、拒 0 条）、
`MintSettlement.java.fixed`（还原用的原字节）。

# W5-fix5（上限不夹 + 接通上轮印量读数）

> 追加节（**不覆盖前文**；W5 / W5-fix / W5-fix2 / W5-fix3 / W5-fix4 的叙述与结论原样保留）。
> 任务书 = **用户 2026-10-11 最新原话**「**没事没事，铸币能进政府的家户，按照决策人预期被发行就行**」
> + 设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.18 §7**（"不夹"三条口径）。

## ⓪ 结论一句话

**闸门只剩旋钮**：`limit = 政策旋钮 mintScalePerCycle`（删掉 `Math.min(旋钮, 上一轮印量)` 那道夹）；
**上一轮印量仍是真读数**（W5-fix4 的绝对日窗口保留），并**接通了它的持久写口** `Government.mintedLastCyclePerCycle`
（此前全仓无写点 ⇒ GUI 那一栏永远空）。
实测：第 1 轮旋钮 200 ⇒ 印 200；第 2 轮旋钮抬到 2,000（上一轮只印 200）⇒ **印 500（不再被 200 夹住）**、
`previousCycleMinted=200` 且关账后字段 = `{silver=200}`；第 3 轮旋钮 0 ⇒ **0 毫**。

## ① 上限口径改动（file:line + 前后各一行）

| # | 落点（**改后**行号） | 改前 | 改后 | 性质 |
|---|---|---|---|---|
| C-1 | `simos-economy/.../time/MintSettlement.java:151-154` | `long previousMinted = previousRound.map(PreviousRound::minted).orElse(government.mintScalePerCycle());`<br>`long limit = Math.min(government.mintScalePerCycle(), previousMinted);` | `long previousMinted = previousRound.map(PreviousRound::minted).orElse(-1L); // -1 = 还没有过铸币轮（读数缺席）`<br>`long limit = government.mintScalePerCycle();` | ★ **本批唯一改判据的一行**（"夹"删掉） |
| C-2 | 同上 `:151` 注释 + `:147-150` | 注释写"判据 ③ 的闸门……上一轮一笔都没印（含创世第一轮）⇒ 首轮启动额 = 政策旋钮本身" | 注释写"W5-fix5：闸门 = **政策旋钮本身**；上一轮印量只算一次、只作读数；W5-fix4 的绝对日窗口保留（服务读数与日志）" | 文档 |
| C-3 | 同上 `:99-101`（类注新增一节） | 类注有整节「★ **首轮启动额为什么是必需的**……没有上一轮 ⇒ 上限 = 政策旋钮」 | **该节整段删除**，换成「★★ **首轮特判已删（W5-fix5，§一.11）**：其语义已随"不夹"消失 —— 上限恒 = 旋钮，有没有上一轮都一样；'从未铸过币'只是一个读数事实（空表 / `previousRoundDay=-1` / `previousCycleMinted=-1`）」 | ★ **删首轮特判**（任务书 §2.3） |
| C-4 | 同上 `:269-307`（INFO `MINT_LIMIT_REJECTED`） | `reason=above-previous-round-mint-or-policy-scale` | `reason=above-mint-scale-policy`；`limit` 语义 = 政策旋钮；`previousCycleMinted` 保留为**读数**；注释写清"读数是给人看的，不是闸门" | 事件**保留**（旋钮 < 请求时仍会拒），字段语义随新口径 |
| C-5 | 同上 `:176-187`（DEBUG `MINT_LIMIT_WINDOW`，事件名保留） | 字段：`previousRoundDay/previousCycleMinted/mintScalePerCycle/limit/reason(2 值)` | 新增 `limitSource=mint-scale-policy`、`mintedLastCycleReadout`（写进状态的那份逐币种表）、`mintedLastCycleReadoutChanged`（bool）；`reason` 两值改为 `previous-mint-round-day-window-readout-only` \| `no-previous-mint-round-readout-only` | 只多日志，不改状态/公式 |
| C-6 | 同上 `:382-402`（`previousRoundMinted`） | 返回 `Optional<PreviousRound(day, minted)`（**跨币种求和**那一个数） | 返回 `Optional<PreviousRound(day, Map<CurrencyId,Long> byCurrency)`：**逐币种**合并（同币种相加、不同币种各成一键、更近一轮 clear+重填） | ★ 契约要求的是逐币种（`Government` 那个字段），求和只是日志形态 |
| C-7 | 同上 `:470-486`（`PreviousRound` record） | `record PreviousRound(long day, long minted)` | `record PreviousRound(long day, Map<CurrencyId,Long> byCurrency)` + 派生 `minted()`（= 跨币种求和，进日志）+ 构造期 `Collections.unmodifiableMap(new LinkedHashMap<>(...))`（保序冻结） | 读数的两份形态**同源**（不会漂开） |

★ **一个字没动的**：`Math.min(requested, limit)`、`rejected = requested − limit`、`MINT_LIMIT_REJECTED` 的**事件名/字段名/INFO 级别**、
`MINT_POLICY_ZERO` 支路（旋钮 0 ⇒ 不发 INFO，避免每轮刷屏）、越权 `MINT_CURRENCY_NOT_ISSUABLE` 的 ERROR、
产出放国库、自持债排除、`period` 的写入（仍是 `currentCycle`）、`MINT_LABOR_SHARE_PER_MILLE=30`、
`EconomySettlement` 的调用点（`:1971-1973` 逐字未改）。

## ② `mintedLastCyclePerCycle` 的处置：**接通**（不删）+ 理由 + 铁律 5 落点

**裁定：接通写点**（任务书给了"接通 or 删"二选一，必须给理由）。

| 为什么接通（不删） | 依据 |
|---|---|
| 它是**决策人/GUI 的读数面**：口径改成"决策人预期即权威"之后，"上一轮印了多少"不再有闸门作用，那就**只剩显示价值** —— 恰恰更该让它真的能显示 | 用户原话「按照决策人预期被发行就行」；契约 `Government.java:57-64` |
| 删字段要动**状态形状**（`Government` 组件 + `EconomyData` 第 N 组件 + Codec + ChangeSet + 大量测试夹具），成本**高于**接通；而接通只需一处工作表写入 | 本批"很小"的边界（任务书 §0） |
| 接通**不需要改任何形状**：字段、Codec 键注册（`CurrencyId`）、ChangeSet 的 `governments` 增量**都已存在**，只是从来没人写 | 见下三件套 |
| 它同时给 W5-fix4 的读数补上**持久面**：日志是瞬态的，字段才是"事后可查"的 | 契约原文"可持久读数" |

**写点（唯一一处）**：`MintSettlement.writeLastCycleReadout`（`:422-468`），在**每轮关账**、闸门读数算完之后、铸币之前调用一次（每政府一次）。
值 = `previousRoundMinted(session, gov, day)` 的**逐币种真值**（最近一个**严格早于本轮**的铸币日）；
**没有上一轮 ⇒ 写空表**（不是 0 毫 —— 契约原文："空表 = 还没有过任何铸币轮（合法状态）……逐值必须 > 0（0 不是一条记录，删键即可）"）。
值没变**不写**（工作表一字不动 ⇒ 变更集保持 `Unchanged`），真变了才落 + 发 INFO `MINT_LAST_CYCLE_READOUT`
（字段：`day/period/government/previousRoundDay/previousCycleMinted/before/after/currencies`）。

**铁律 5 三件套（点名核对）**：

| 面 | 落点 | 本批是否改形状 | 核对证据（真跑，见 ③） |
|---|---|---|---|
| ① **Codec** | `simos-economy/.../codec/EconomyCodec.java`（`Government` 走 Jackson record 绑定；嵌套 map 的键 `CurrencyId` 的反序列化器注册在 `:289` 一带 `module.addKeyDeserializer(CurrencyId.class, …)`；写侧靠 `CurrencyId.toString()` 的裸值 + `parse` 逆） | **否**（字段早就在 `Government` 里） | 探针 `IRON5 snapshotRoundTrip dataEqual=true`，且 `fieldBefore==fieldAfter`（**非空** `{silver=500}` 的那一份） |
| ② **ChangeSet** | `simos-economy/.../change/EconomyChangeSet.java:158`（`FieldDelta<Government> governments`）、`:360`（`diff` 从两整份状态派生）、`:411`（`rebuild` 施加）、`:467`（`changed()`） | **否**（组件早就在派生式里） | 探针 `IRON5 changeSetBytes=1850277 mentionsMintedLastCycle=true`；派生+施加后 `rebuiltTarget=true` |
| ③ **往返不变式** | `apply(changeSet, base)` 逐字段重建 target（`EconomyChangeSet.rebuild`） | **否** | 同上：`codec.apply(codec.decodeChangeSet(codec.encodeChangeSet(codec.diff(base,target))), base, meta)` ⇒ 与 target `equals` |

★ **唯一写路径（铁律 2）**：本方法只拿得到 `EconomySession`，写的是 `EconomyStateBuilder.governments()` 这个**工作表**
（与 `moneyIssuances`/`debtContracts` 同一条通道；`TreasuryWithdrawal.java:88` 已是同款读写先例），
`EconomySession.build()` 把它挂成完整 `EconomyData` ⇒ 变更集由 `FieldDelta.diff` 派生 ⇒ 落在 `Command → ChangeSet → Revision` 上，**没有旁路**。
（`base` 在本方法里不可达：`settleCycleClose` 的 `base` 是只读视图 `EconomyDayView`，写口只有 `session.sheet()`。）

**读口一致性**（GUI 那一栏现在真的有了）：`ApiViews.economyGovernment`（`:1676-1681`）读的就是这个字段；
探针 `PROBE after-round2 GUIVIEW gov=world-silver … mintedLastCyclePerCycle={silver=200}`（**从 `ApiViews` 的返回节点读出来的**，不是读状态推的）。

## ③ 自证（真读数：三格 + 字段读数 + 铁律 5）

**装置**：`SmallWorld.state("W5Fix5Probe")` 创世 → `GovZ6WorldFixture.StateRunner` 每轮 `advance(from,to]` **120 tick**
（实测关账日 = **120 / 240 / 360**；`period` 步进 **4 / 8 / 12** —— 与 W5-fix4 的 4/8/12/16 同源，起点差一天是因为本次从 day 0 起推）；
轮间只在**内存**里用 `Government.withMintScalePerCycle` 改政策旋钮（探针不写仓、不改任何仓内文件）。
classpath = `/tmp/simos-clean-cp.txt`（模块 `target/classes` 前置 + 剔除 `~/.m2/io/mosire/simos-*`），脚本自证 `stale simos-* jars on classpath = 0`，
并在**仓根**下编译/运行（W5-fix4 记的坑）。探针日志首行自记本轮字节：`economy classes md5 = aec3a08faa2fa66d57d37458174c160d`。

**三格（真读数；日志原文逐字）**

| 格 | 轮 / 旋钮 | 真读数 | 结论 |
|---|---|---|---|
| **①** | 第 1 轮 `day=120 period=4`，旋钮 **200** | `PROBE ROUND1 mintedDelta=200 newRecords=1 mintedTotalAfter=200`；INFO `MINT_LIMIT_REJECTED day=120 period=4 government=world-silver … requested=500 limit=200 previousCycleMinted=-1 mintScalePerCycle=200 rejected=300 reason=above-mint-scale-policy`；DEBUG `MINT_LIMIT_WINDOW day=120 … previousRoundDay=-1 previousCycleMinted=-1 mintScalePerCycle=200 limit=200 limitSource=mint-scale-policy mintedLastCycleReadout={} mintedLastCycleReadoutChanged=false reason=no-previous-mint-round-readout-only`；国库银 0 → 200、工具 8000 → 4000 | 实印 200 **≤ 旋钮 200**；`limit` == 旋钮；**没有首轮特判**（读数缺席 = `-1` / 空表） |
| **②** | 第 2 轮 `day=240 period=8`，旋钮抬到 **2,000**（**上一轮只印了 200**） | `PROBE ROUND2 mintedDelta=500 newRecords=1 mintedTotalAfter=700`；INFO `MINT_SETTLED day=240 period=8 … mintedTotal=500 byCurrency={silver=500}`；**day=240 无 `MINT_LIMIT_REJECTED`**（整轮 INFO 日志里 `MINT_LIMIT_REJECTED` 计数 = **1**，只有 day=120 那一条）；DEBUG `MINT_LIMIT_WINDOW day=240 … previousRoundDay=120 previousCycleMinted=200 mintScalePerCycle=2000 limit=2000 limitSource=mint-scale-policy mintedLastCycleReadout={silver=200} mintedLastCycleReadoutChanged=true`；国库银 200 → 700 | ★★ **本批核心：实印 500 > 上一轮 200 ⇒ 不再被夹**；读数 `previousCycleMinted=200` 是**真值** |
| **③** | 第 3 轮 `day=360 period=12`，旋钮 **0** | `PROBE ROUND3 mintedDelta=0 newRecords=0 mintedTotalAfter=700`；DEBUG `MINT_LIMIT_WINDOW day=360 … limit=0 limitSource=mint-scale-policy` + `MINT_POLICY_ZERO day=360 … reason=mint-scale-zero minted=0`；国库银仍 700 | 旋钮 0 ⇒ **0 毫**（判据 ② 未回归） |
| 附 | 字段读数（`Government.mintedLastCyclePerCycle`） | `PROBE IRON5 fieldValues genesis=world-silver={} afterRound1=world-silver={} afterRound2=world-silver={silver=200} afterRound3=world-silver={silver=500}` | 第 2 轮关账后 **== 200（真值）**；第 3 轮关账后 == 500（"最近一个印过的轮"，读数**不因本轮旋钮 0 而失真**） |
| 附 | GUI 读口（`ApiViews.economyGovernment`） | `PROBE after-round2 GUIVIEW gov=world-silver mintScalePerCycle=2000 mintedLastCyclePerCycle={silver=200}` | 那一栏**真的能读到了**（此前恒为空表） |
| 附 | 铁律 5 三件套 | `PROBE IRON5 snapshotRoundTrip dataEqual=true fieldBefore=…{silver=500} fieldAfter=…{silver=500}`；`changeSetBytes=1850277 mentionsMintedLastCycle=true`；`changeSetRoundTrip rebuiltTarget=true appliedField=…{silver=500}` | 非空字段的 Codec / ChangeSet / 往返全绿 |

**变异自证（判据有判别力，不是"恰好绿"）**：把 C-1 那两行改回 W5-fix4 的形态
（`previousMinted = …orElse(government.mintScalePerCycle())` + `limit = Math.min(旋钮, previousMinted)`，行内标 `// MUTANT`）
⇒ 同一探针第 2 轮 **`mintedDelta=200`**（被 200 夹住）且 day=240 多出 `MINT_LIMIT_REJECTED … limit=200 previousCycleMinted=200` ⇒ **当场红**。
变异体 md5 `58a42136eec29474df59e28c08aea7f1` vs 原件 `38d6b48f88482ac760c8c462fb6f99b7`（**字节不同 ✓**，且**编译通过** ⇒ 红的是判据不是编译错误）；
还原后 `md5sum -c` = `OK`、`grep -c MUTANT` = 0，重编译后 class md5 回到 **`aec3a08faa2fa66d57d37458174c160d`**（与修复轮探针记录的那份字节同一个 md5）。

**门禁（真命令 + rc + surefire 真数；跑前 `rm` 过旧报告）**

```
$ tools/mvn-lock.sh -q spotless:apply                                        → rc=0（无输出）
$ tools/mvn-lock.sh -DskipTests compile -am                                  → rc=0；BUILD SUCCESS（16/16 模块）
$ tools/mvn-lock.sh test -pl simos-app -am -Dtest='GovGenesisZ6Test' -Dsurefire.failIfNoSpecifiedTests=false
  [rc=0] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0（38.74 s）
  surefire 报告 simos-app/target/surefire-reports/io.mosire.simos.app.gov.GovGenesisZ6Test.txt
    "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0"，mtime 2026-10-11 12:20:32（本轮）
$ tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MintProductionSettlementTest' -Dsurefire.failIfNoSpecifiedTests=false
  [rc=1] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0（0.150 s）
  ★ 唯一那条红 = 判据③ 的**旧口径断言**（见 ⑤-1，测试文件不在本批授权面 ⇒ 只报不改）；其余 4 条（判据①②④⑤ + 越权负向）**全绿**
  surefire 报告 …/io.mosire.simos.economy.time.MintProductionSettlementTest.txt，mtime 2026-10-11 12:19:46（本轮）
```

落盘字节（本批改的 7 个 main 文件里，判据所在的那一个）：
`md5 38d6b48f88482ac760c8c462fb6f99b7  simos-economy/src/main/java/io/mosire/simos/economy/time/MintSettlement.java`。

## ④ 会改变数值行为的清单（逐条具名 —— 给测试 Agent 当输入）

| # | 行为 | 改前 | 改后 | 影响面 |
|---|---|---|---|---|
| **N-1** | **一轮的实铸上限** | `limit = min(政策旋钮, 上一轮实印)` | `limit = 政策旋钮` | ★★ **唯一的数值口径改动**。任何"上一轮印量 < 旋钮"的轮次都会印得更多（上限抬到旋钮，实测 200 → 500 那一格）；**默认小世界逐值不变**（旋钮 2,000、每轮请求 500 ≤ 2,000 ⇒ 每轮仍 500，W5-fix4 的 `probe-default-knob.log` 结论对"请求 ≤ 旋钮"的世界继续成立） |
| **N-2** | 首轮启动额 | 无上一轮 ⇒ `limit = 政策旋钮`（一条**具名分支**） | 无上一轮 ⇒ 同样 `limit = 政策旋钮`，但**不再是分支**（读数缺席 = `-1`/空表） | 逐值**不变**（值相同），变的是"有没有这条特判"与它的读数（`previousCycleMinted` 由"旋钮值"变成 `-1`） |
| **N-3** | `Government.mintedLastCyclePerCycle` | **恒为空表**（无写点） | 每轮关账覆盖写"最近一个严格早于本轮的铸币日的逐币种合计"（无上一轮 ⇒ 空表） | ★ **状态在这个组件上真的会变了**：每轮关账日 `EconomyChangeSet.governments` 会多一条 `Upsert`（值没变时不写）；GUI `/api/economy/gov` 那一栏由空变真值；**不影响任何结算公式**（全仓读它的只有 `ApiViews` 与"重登记原样带过"） |
| **N-4** | 新增 INFO `MINT_LAST_CYCLE_READOUT` | 无 | 值真变时每政府一条 | 只影响日志（默认 INFO 级别 ⇒ **会输出**）；不改状态、不改公式 |
| **N-5** | DEBUG `MINT_LIMIT_WINDOW` 字段 | 8 个字段 | +`limitSource` / +`mintedLastCycleReadout` / +`mintedLastCycleReadoutChanged`，`reason` 两值改名 | 只影响 `-Dsimos.economy.logLevel=DEBUG` |
| **N-6** | INFO `MINT_LIMIT_REJECTED.reason` | `above-previous-round-mint-or-policy-scale` | `above-mint-scale-policy` | ★ **字符串变了**（断言它的测试见 ⑤-1）；`limit`/`previousCycleMinted`/`requested`/`rejected` 四个数值字段的**含义与算法不变**（`limit` 现在恒 == `mintScalePerCycle`） |
| **N-7** | 文档/描述文本 | 类注写"首轮启动额"、"≤ 上一轮印量闸门"；`simos.economy.upsertIndustry` 工具描述写"另受 … 与'上一轮印量'闸门约束" | 改为"上限 = 旋钮本身"；**工具描述字符串已改**（GM/决策人可见面） | 零数值行为；★ 工具描述是 MCP schema 的一部分 ⇒ 列在此处**点名** |

★ **一个字没动的数值行为**：产出率常量（`MINT_MILLI_PER_FURNACE_CYCLE=500`）、`MINT_LABOR_SHARE_PER_MILLE=30`、
投入消耗（`scale × cycleInputPerUnit`）、`period` 写入、自持债排除、`MINT_POLICY_ZERO` 的"不发 INFO"、
越权发行的 ERROR 路径、`MINT_SETTLED` 的字段与算法。

## ⑤ 会让既有测试断言失效的清单（**只报不改**；旧 → 新 + 依据 = 用户原话）

| # | file:line | 旧断言 / 旧措辞 | 按新口径应为 | 依据 |
|---|---|---|---|---|
| 1 | `simos-economy/src/test/java/io/mosire/simos/economy/time/MintProductionSettlementTest.java:269-306`（用例 `mintIsCappedByThePreviousCycleMintedAmountAndRejectsTheExcessByName`） | `@DisplayName("判据③：上一轮印 300 毫 ⇒ 本轮 ≤300（实铸 300、拒 200）…")`；INFO 断言 `limit=300 previousCycleMinted=300 rejected=200 reason=above-previous-round-mint-or-policy-scale`；`minted.amount()==300`；国库 `100+300=400`；`moneyDeltaTotal==300`；债 `principal==300` | **≤ 政策旋钮 2,000 ⇒ 实铸 500**；no `MINT_LIMIT_REJECTED`（`requested 500 ≤ limit 2000`）；`previousCycleMinted=300` 仍是真读数（进 DEBUG `MINT_LIMIT_WINDOW` 与新的 INFO `MINT_LAST_CYCLE_READOUT`）；国库 `100+500=600`；`moneyDeltaTotal==500`；债 `principal==500`；★ 可另立一条"旋钮 300 < 请求 500 ⇒ 实铸 300 + `limit=300 reason=above-mint-scale-policy`"的**新**判据覆盖"真拒"面 | 用户原话「铸币能进政府的家户，**按照决策人预期被发行就行**」；设计书 v3.18 §7-1/§7-2（"任何'≤ 上一轮印量'的判据/断言按新口径改判为'≤ 政策旋钮'"） |
| 2 | 同上 `:55-60`（类注） | "判据出处 = … W5 行（…；**上限 = 上一轮印量**；…）" | "…上限 = 政策旋钮 `mintScalePerCycle`（W5-fix5 / v3.18 §7）；上一轮印量 = 读数" | 同上 |
| 3 | 同上 `:411`（夹具 `@param lastClosedCycle`） | "空 = 本轮是第 1 个周期 ⇒ **上限走"首轮启动额 = 政策旋钮"**" | "空 = 本轮是第 1 个周期 ⇒ **读数缺席**（`previousCycleMinted=-1` / 字段空表）；上限恒 = 政策旋钮" | §一.11（首轮特判直接删）+ 用户原话 |
| 4 | `simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java:67`（类注） | "**上一轮印量上限**"（描述被验世界的口径） | "上限 = 政策旋钮（W5-fix5）"；★ 该测试的**断言面**（3 个 GOV 单位旋钮缺省 0、无铸币产业）逐值不受影响 —— **只改措辞** | v3.18 §7 |
| 5 | `simos-economy/.../MintProductionSettlementTest` 内**未**断言但值得补的读数 | 无 | 新增 `MINT_LAST_CYCLE_READOUT`（值变了才有）与 `mintedLastCyclePerCycle` 的断言（第 1 轮关账后 `{}`、第 2 轮后 `{silver=…}`）—— W5-fix4 §⑤-2 建议的"`period` 步进 ≠ 1 的夹具"仍待补 | 本批新增状态写口 ⇒ 需一条往返/读口判据 |

★ **本批只跑了任务书允许的两条**；**没有**跑全量 `test` / `verify` / `clean` / `package`（禁止项）。
★ 除上表 4 条，另有 2 处**纯注释**提及旧口径但**已由本批改掉**（`Government.java:44-46`、`EconomyRegisterGovernmentHandler.java:359-361`、`ApiViews.java:1638/1673`、
`GovIssueMoneyTool.java:60-62`、`EconomyUpsertIndustryTool.java:100-103`、`EconomySeeder.java:1038`）—— 不在测试面，不列 ⑤。

## ⑥ 未完成 / 未验证项（如实）

1. **只跑了任务书允许的两条回归**（`GovGenesisZ6Test` 5/5 绿；`MintProductionSettlementTest` 4/5，唯一红 = ⑤-1 旧口径断言）；**没有**跑全量 `test` / `verify` / `clean` / `package`。
2. ★ **测试文件不在本批授权面** ⇒ ⑤-1 那条红**未修**（按新口径改写属测试批）。本批的判据证据全部来自 `/tmp` 探针（含变异轮）与**测试自身的日志输出**
   （那条红的 `AssertionError` 原文里恰好打印了运行期真值：`MINT_LAST_CYCLE_READOUT … before={} after={silver=300}`、`MINT_SETTLED … mintedTotal=500` ⇒ 新旧口径的差一目了然）。
3. **没验**：多币种世界下"逐币种表"的形状（`byCurrency` 的合并/保序只有单币种实测样本；多币种路径静态核过：`merge` 按币种分键、`clear+put` 保最近一轮）。
4. **没验**：`gov.issueMoney`（`FISCAL_ISSUE`）与"上一轮印量"的关系 —— 窗口只数 `MINT_PRODUCTION`（沿用 W5，读数是"铸币这一路印了多少"，行政发行不进这个读数）。旧账 W5-fix4 §⑤-7 的同一条仍未变，列 W6 可裁定。
5. **没验**：落盘 → 重启 → 重放（`Replay`/checkpoint）链上的字段值。本批只验了 Codec 往返与 ChangeSet 往返（在同进程内），**没有**跑"写档 → 新进程读回 → 再推进一轮"的整链。
6. **没验**：`ApiViews` 的 HTTP 面（探针直接调 `economyGovernment(state)` 这一层，没有起服务、没有打 `/api/economy/gov`）。
7. **"印了 0 毫的那一轮不可见"**（W5-fix4 §⑤-5 的已知语义）在本批**依旧是**这样：字段/读数取"最近一个**真印过**的轮"。
   ⇒ 旋钮 0 的那一轮**不会**把读数清零（本批探针第 3 格正是这个形态：`afterRound3={silver=500}`）。
   ★ 这是**刻意的**：契约的"空表"只表示"从未铸过"，不表示"上一轮印了 0"；要严格区分需要新状态位（W5 明确不新开），列 W6 可裁定。
8. **没验**：`mintedLastCyclePerCycle` 在 **B2 分段等价**（拆批推进 vs 一次推进）下逐字节一致。它在关账日写、值是"更早的轮"的纯函数 ⇒ 推断一致，但**没实测**。

## ⑦ 偏离记录

| # | 偏离 | 原因 | 影响 |
|---|---|---|---|
| D-1 | INFO `MINT_LAST_CYCLE_READOUT` **只在值真的变了时发**（不是每政府每关账日一条） | §一.9 要求"新状态写口至少 INFO 一条"，但同一个数每轮重复发会刷屏（与 `MINT_POLICY_ZERO` 只发 DEBUG 同制）；"读数取了多少、有没有变"在 **DEBUG `MINT_LIMIT_WINDOW`** 里每政府每关账轮都有（含 `mintedLastCycleReadoutChanged`） | 只影响日志；状态写入不受影响（值没变时**本来就不写**） |
| D-2 | `MINT_LIMIT_REJECTED.reason` 改名（N-6） | 旧串 `above-previous-round-mint-or-policy-scale` 在新口径下**是错的描述**（§四：机制性描述不得与代码漂开） | 需测试批同步（⑤-1 已点名） |
| D-3 | 顺手改了 6 处**旧口径措辞**（`Government` / `EconomyRegisterGovernmentHandler` / `ApiViews` / `GovIssueMoneyTool` / `EconomyUpsertIndustryTool` / `EconomySeeder`） | 同 D-2：这些注释/工具描述正把"≤ 上一轮印量"当现行机制讲；其中 `EconomyUpsertIndustryTool` 的**工具描述字符串**是外部可见面 | 零数值行为；N-7 已点名 |
| D-4 | 首轮"读数缺席"用 **-1** 表示（日志字段），状态字段用**空表** | 契约原文：空表 = 还没有过任何铸币轮（合法）；`-1` 是日志的"缺席"哨兵（沿用 W5-fix4 的读法） | 零数值行为 |
| D-5 | 没有按 §一.11 删字段（选了"接通"） | 见 ② 的四条理由（形状零改动 vs 删字段要动 Codec/ChangeSet/夹具；且它是决策人读数面） | 无 |

## ⑧ 探针落点（不入库）

`/tmp/w5fix5-probe/`：`W5Fix5Probe.java`（3 轮 + 铁律 5 往返 + `ApiViews` 读口）、`run-probe.sh`（含 classpath 自证与 class md5/mtime 自记）、
`probe-info.log`（INFO，3 轮三格 + 字段/GUI 读数）、`probe-debug.log`（DEBUG，`MINT_LIMIT_WINDOW` / `MINT_POLICY_ZERO` / `MINT_LAST_CYCLE_READOUT`）、
`probe-mutant.log`（变异轮：第 2 轮被夹成 200 ⇒ 当场红）、`MintSettlement.java.fixed` + `md5-fixed.txt`（还原对照）。

---

# W5c 测试口径迁移（上限不夹）

> 追加节（**不覆盖前文**；W5 / W5-fix / W5-fix2 / W5-fix3 / W5-fix4 / W5-fix5 的叙述与结论原样保留）。
> 任务书 = **用户 2026-10-11 原话**「**没事没事，铸币能进政府的家户，按照决策人预期被发行就行**」
> + 设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.18 §7**（"不夹"三条口径）
> + **W5-fix5 节 §⑤ 的"只报不改"清单**（旧口径断言逐条迁移；该项原为 W5-fix5 的未完成项）。
> ★ 本批是**测试批**：只动 `simos-economy/src/test/**` 与 `simos-app/src/test/**`；`src/main/**` 一字未改
> （唯一的例外是 §③ 的**变异轮**，它临时改两行并在同一节内逐字节还原、双 md5 自证）。

## ① 结论一句话

W5-fix5 交账时留下的**唯一一条红**（旧口径断言"上一轮印 300 ⇒ 本轮 ≤ 300"）已按 v3.18 §7 迁到"**上限 = 政策旋钮**"：
`MintProductionSettlementTest` 由 **5 条（1 红）** 变为 **7 条全绿**（删 1 条旧口径用例，另立 **真拒面** + **读数持久面** 两条新判据），
`EconomyCycleHealthTest` 类注措辞改准（**断言面代码逐字节未动**，§⑤ 有对照）。全模块 `simos-economy` **295/295 绿**。

## ② 逐条改动（类 + 旧 → 新 + 依据）

| # | 落点 | 旧 | 新 | 依据 |
|---|---|---|---|---|
| T-1 | `MintProductionSettlementTest` 类注（`:58-62`） | 判据出处写"…；**上限 = 上一轮印量**；…" | 改为"…+ ★★ **v3.18 §7** 的最新口径：**实铸上限 = 政策旋钮 `mintScalePerCycle`**（"不夹"）；'上一轮印量'**只是真读数**（DEBUG `MINT_LIMIT_WINDOW` / INFO `MINT_LAST_CYCLE_READOUT` / 状态 `Government.mintedLastCyclePerCycle`）、**不参与任何 clamp**；'首轮启动额'特判已删"，并**逐字收录用户原话** | 用户原话；v3.18 §7-1/§7-2/§7-3 |
| T-2 | 同上 用例 `mintIsCappedByThePreviousCycleMintedAmountAndRejectsTheExcessByName`（旧 `:269-323`） | 上一轮印 300 ⇒ 实铸 **300**、INFO `MINT_LIMIT_REJECTED … limit=300 previousCycleMinted=300 mintScalePerCycle=2000 rejected=200 reason=above-previous-round-mint-or-policy-scale`、国库 400、`moneyDeltaTotal=300`、债 300 | **整条删除**，拆成 T-2a / T-2b / T-2c 三条（§一.11：旧口径断言不留兼容位） | v3.18 §7-1（"任何'≤ 上一轮印量'的判据/断言按新口径改判为'≤ 政策旋钮'"） |
| T-2a | 新用例 `previousCycleMintedIsAReadoutNotAClamp`（**读数面·不夹**） | —— | 上一轮（day=1）印 300 作夹具记录 + 旋钮 **2,000** ⇒ **实铸 500**、**无 `MINT_LIMIT_REJECTED`**、国库 **100+500=600**、`moneyDeltaTotal=500`、债 `principal=500`、投入仍真扣 2,000。★ **读数三条**：① DEBUG `MINT_LIMIT_WINDOW … previousRoundDay=1 previousCycleMinted=300 mintScalePerCycle=2000 limit=2000 limitSource=mint-scale-policy mintedLastCycleReadout={silver=300} mintedLastCycleReadoutChanged=true reason=previous-mint-round-day-window-readout-only`；② INFO `MINT_LAST_CYCLE_READOUT … previousCycleMinted=300 before={} after={silver=300}`；③ 状态 `Government.mintedLastCyclePerCycle == {silver=300}`（★ **≠ 本轮的 500** ⇒ 有判别力） | 任务书 §1-★（旧 → 新逐值）；v3.18 §7-2（读数要真、但只作参考） |
| T-2b | 新用例 `thePolicyKnobIsTheOnlyCapAndTheExcessIsRejectedByName`（**真拒面**） | —— | 旋钮 **300** < 请求 500（无上一轮）⇒ 实铸 **300** + INFO `MINT_LIMIT_REJECTED … requested=500 limit=300 previousCycleMinted=-1 mintScalePerCycle=300 rejected=200 reason=**above-mint-scale-policy**`；国库 400、`moneyDeltaTotal=300`、债 300、投入 2,000、`reason` 文本含"上限=300 / 政策旋钮=300 / 产出请求=500" | 任务书 §1-★"另立一条真拒面"；W5-fix5 §①C-4（`reason` 改名）+ §⑦D-4（无上一轮 ⇒ 读数 `-1` = **首轮特判已删**） |
| T-2c | 新用例 `mintedLastCycleReadoutTracksThePreviousRoundAndSurvivesAZeroKnobRound`（**读数持久面**） | —— | **连推三轮真日循环**（同一条 `AccountSession`；旋钮逐轮 300 → 2,000 → 0）⇒ 第 1 轮关账后字段 **空表**、第 2 轮关账后 **`{silver=300}` == 第 1 轮实印**、第 3 轮（**旋钮 0**）关账后 **`{silver=500}` == 第 2 轮实印**（**不被清零**、也不是 `0` 毫）；前提守卫断言三轮实印 300 / 500 / 0，且第 3 轮无新发行记录 | 任务书 §1-★"再加一条读数面"；W5-fix5 §②（唯一写点 `writeLastCycleReadout`）+ §⑥-7（"旋钮 0 的那一轮不清读数"是**刻意**语义） |
| T-3 | 同上 夹具 `@param lastClosedCycle`（旧 `:411`） | "空 = 本轮是第 1 个周期 ⇒ **上限走"首轮启动额 = 政策旋钮"**" | "空 = 本轮是第 1 个周期 ⇒ ★ **与上限无关**：上限恒 = 政策旋钮；它只决定 `currentCycle` 与**读数是否缺席**（`previousCycleMinted=-1` / 字段空表）" | §一.11（首轮特判直接删）；v3.18 §7-3 |
| T-4 | `EconomyCycleHealthTest` 类注（`:66-68`） | "W5 起改成"产业产出 + **上一轮印量上限**"" | "W5 起改成"产业产出 + **上限 = 政策旋钮 `mintScalePerCycle`**"；★★ W5-fix5 / v3.18 §7 起"上一轮印量"只作真读数、**不参与任何 clamp**（附用户原话）" | 任务书 §1.3；v3.18 §7 |
| T-5 | `MintProductionSettlementTest` 夹具新增私有装置 | —— | `previousMintRecord(amount)`（夹具的"上一轮"记录）、`mintAmount(data, day)`（前提守卫读数）、`runRounds(base, firstDay, knobs…)`（**同一条 `AccountSession`** 连推 N 轮、逐轮设旋钮）；新增 `java.util.ArrayList` import | 判据需要"跨轮状态"+"逐轮不同旋钮"；该装置的边界见 §⑥-5 |

★ **一个字没动的**：`src/main/**` 全链（含 `MintSettlement` 的 `Math.min(requested, limit)`、`MINT_LIMIT_REJECTED` 的字段名/级别、
`MINT_POLICY_ZERO` 支路、越权 ERROR 路径）；其余 4 条既有用例（判据①②④⑤ + 越权负向）的判据与断言；
`EconomyCycleHealthTest` 的**全部分布指纹 / 逐轮零成交断言**（§⑤ 有逐字节对照）。

## ③ 自证（真命令 + rc + 关键输出；一次只有一个 Maven）

```
# ⓪ 开工基线（本批改前）：旧口径断言当场红 —— 先证明"要迁的那条红"真实存在
$ tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MintProductionSettlementTest' -Dsurefire.failIfNoSpecifiedTests=false
  [rc=1]  Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
  唯一红 = mintIsCappedByThePreviousCycleMintedAmountAndRejectsTheExcessByName；报告内运行期真值已打印
  `MINT_LAST_CYCLE_READOUT … before={} after={silver=300}` / `MINT_SETTLED … mintedTotal=500`
  （= 新口径的 500 与旧断言的 300 差一目了然）；报告 mtime 2026-10-11 12:23:34

# ① 迁移后（目标类）
$ tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MintProductionSettlementTest' -Dsurefire.failIfNoSpecifiedTests=false
  [rc=0]  Tests run: 7, Failures: 0, Errors: 0, Skipped: 0（报告 mtime 12:29:59，本轮）

# ② 全模块（最终字节）
$ tools/mvn-lock.sh test -pl simos-economy -am
  [rc=0]  Tests run: 295, Failures: 0, Errors: 0, Skipped: 0（目标类报告 mtime 12:30:35）

# ③ app 侧老守卫（加牙后的分布指纹）
$ tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false
  [rc=0]  Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 78.61 s（报告 mtime 12:29:29）

# ④ 格式化（只对我改的两个文件；★ -DspotlessFiles 是**正则**不是 glob —— glob 写法当场报
#    `Dangling meta character '*' near index 0`）
$ tools/mvn-lock.sh -q spotless:apply -pl simos-economy,simos-app -DspotlessFiles='.*MintProductionSettlementTest\.java,.*EconomyCycleHealthTest\.java'   → rc=0
$ tools/mvn-lock.sh -q spotless:check -pl simos-economy,simos-app -DspotlessFiles='…同上…'                                                                  → rc=0
  作用面核对：对 13 个"最近 1 小时内被动过的源文件"取 md5 前后对照 ⇒ **只有我改的 2 个测试文件变了**，
  其余（含 `MintSettlement.java`、`Government.java` 等 main 文件）md5 逐字节不变；`git status` 集合不变。
```

★ **没跑**（任务书禁止项）：`clean` / `verify` / `package`；也没跑 `simos-app` 全量套件（见 §⑥）。

## ④ 变异自证（判据有判别力：把 `limit` 改回 `min(旋钮, 上一轮)` ⇒ 新判据当场红）

| 步 | 动作 | 真读数 |
|---|---|---|
| 1 | 记原件 | 源 `md5 38d6b48f88482ac760c8c462fb6f99b7`（★ 与 W5-fix5 §③ 记录**逐字节相同** ⇒ main 全链确实未被本批碰过）；class `md5 aec3a08faa2fa66d57d37458174c160d`（同 W5-fix5 探针记录） |
| 2 | 变异（两行，行内标 `// MUTANT`） | `previousMinted = previousRound.map(PreviousRound::minted).orElse(government.mintScalePerCycle())`；`limit = Math.min(government.mintScalePerCycle(), previousMinted)` ⇒ 源 `md5 ca5fa7f9b25956eb3e2f18eca25ee27d`（**≠ 原件 ✓**，且**编译通过** ⇒ 红的是判据不是编译错误） |
| 3 | 跑目标类 | **[rc=1]** `Tests run: 7, Failures: 3` —— 恰是三条按新口径写/改的判据：`previousCycleMintedIsAReadoutNotAClamp`（实铸被夹回 300）、`mintedLastCycleReadoutTracksThePreviousRoundAndSurvivesAZeroKnobRound`（第 2 轮被夹）、`thePolicyKnobIsTheOnlyCapAndTheExcessIsRejectedByName`（日志里冒出 `previousCycleMinted=300` = **被删掉的首轮特判复活**）。其余 4 条（含判据②旋钮 0、越权负向）不受影响、原样绿 |
| 4 | 还原 | `md5sum -c` ⇒ `MintSettlement.java: OK`；`grep -c MUTANT` ⇒ **0**；重编译后 class `md5` 回到 `aec3a08faa2fa66d57d37458174c160d`；同命令重跑 ⇒ **[rc=0] 7/7 绿** |

## ⑤ 断言面保护（app 老守卫）+ 落盘字节

**app 守卫的"一字不许动"自证**：`git show HEAD:<file>` 的工作树版本 vs 工作树当前版本，**去掉注释与空白后逐行 diff = 空**
（两份均为 19,964 字节）⇒ `EconomyCycleHealthTest` 的**断言/夹具代码面逐字节未变**；本批对该文件的写入全在 javadoc（T-4 那一句）。
（该文件在 HEAD 里已是 W4b 加牙后的版本 ⇒ 分布指纹断言本来就在 HEAD 中，本批不碰。）

| 文件 | md5（本批落盘字节） |
|---|---|
| `simos-economy/src/test/java/io/mosire/simos/economy/time/MintProductionSettlementTest.java` | `0c7fbf2c2cb518b5fd2f189faf8cb5df` |
| `simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java` | `42aeb70355f833f50e64d7551c439c48` |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/MintSettlement.java`（**未改**） | `38d6b48f88482ac760c8c462fb6f99b7` |

## ⑥ 未完成 / 未验证项（如实）

1. **没跑** `clean verify` / `package`（任务书禁止项；checkstyle/SpotBugs 门禁归控制方）；只跑了 `spotless:check`（限本批两个文件）。
2. **没跑 `simos-app` 全量套件**（只跑了 `EconomyCycleHealthTest` 一个类）；**没跑 `simos-social`**
   （检索证据：`MINT_PRODUCTION` / `mintScalePerCycle` / `MINT_LIMIT_*` 在该模块测试面 **0 命中**）。
3. **没验**多币种读数形状（`previousCycleMinted` 跨币种求和 / `byCurrency` 逐币种合并）—— 夹具是单币种（继承 W5-fix5 §⑥-3）。
4. **没验**落盘 → 重启 → 重放链上的字段值（继承 §⑥-5）；**没验** HTTP `/api/economy/gov` 那一栏（继承 §⑥-6）。
5. **T-2c 的夹具形状（如实写）**：三连轮复用**同一条 `AccountSession`**、且**没有任何协调器重发劳动配额**（app 日循环那一环不在本夹具里）；
   实测三轮都真的铸出来了（守卫断言 300 / 500 / 0），说明关账日的劳动重排**没有**把那条常设配额修剪掉 ——
   但这是**夹具世界里成立**的事实，不等于"任意真世界逐字节等价"。
6. **变异自证只做了 1 个变异体**（`limit` 那两行）；`reason` 改名、读数写口（`writeLastCycleReadout`）、`MINT_POLICY_ZERO` 支路
   **没做**变异自证（只有正向断言守）。
7. **没跑** `-Dsimos.economy.logLevel=DEBUG` 的**真系统属性轮**：日志断言走 `TestLogCapture`（直连 logger 名），
   没走 `log4j2.xml` 的属性开关那一层。

## ⑦ 偏离记录

| # | 偏离 | 原因 | 影响 |
|---|---|---|---|
| D-1 | 变异轮**临时改过 `src/main`**（`MintSettlement.java` 两行 + `// MUTANT`） | 任务书 §2 明确要求"把 `limit` 改回 `min(旋钮, 上一轮)` ⇒ 新判据当场红"这条变异自证 | 已在同一节内**逐字节还原**（源 md5 + 编译后 class md5 双证）；除此之外 `src/main/**` 一字未改 |
| D-2 | `spotless:apply` 用 `-DspotlessFiles`（**正则**）把作用面收在两个测试文件上 | 全模块 apply 可能误动别的批次正在写的 main 文件（§一.2 一个文件一个 owner） | 只重排 javadoc 折行；代码面零改动；md5 前后对照已证只有本批 2 个文件变 |
| D-3 | 观察到 `git status` 里 `simos-economy/.../EconomySettlement.java` 由"未列出"变为"已修改" | 其 mtime = **11:25:29**，**早于本批开工的 12:22** ⇒ 是 W5 全链的既有未提交改动，被 git 索引刷新后显形（本批只写 2 个测试文件 + 一次已还原的变异） | 只报不改；本批未碰该文件（md5 对照可证） |

---

# W5-fix6（D1 政策写口 / D2 读数分类 / D4 创世审计 / D5 自持债语义）

> 依据：W5 验收报告 `.superpowers/sdd/2026-10-11-w5-acceptance/report.md`（md5 `bf8ba248…`）§3 的 D1/D2/D4/D5 +
> 设计书 v3.20 §7 的四条处置。★ **裁定归属（§一.8.1）**：D1 依据**既有口径**「政策旋钮必须 GM 可调」
> （先例 = W2 的 `economy.SetMarketZonePriceAlpha` + `simos.economy.setMarketZonePriceAlpha`）；
> **D2/D4/D5 是控制方口径**（不是用户原话），**随时可被用户推翻** —— 本账本按此如实标注。

## ⓪ 结论一句话

四条全部落地并**用真世界 + 真命令路径**自证：D1 世界级 `world-silver` 的旋钮可由 GM 2000→300（Δ=1 revision）⇒ 实铸 300 +
`MINT_LIMIT_REJECTED(limit=300, rejected=200)`，调回 2000 ⇒ 实铸 500；D2 两个只读端点按枚举 `issuance()` 分类 ⇒
`withdrawal` 空、`mintProduction={silver:300}`、`netIssuance == circulation`；D4 创世逐国库补 `GENESIS_ENDOWMENT`（2×100,000）
且**货币总量/两国库余额逐值不变**；D5 自持合同本金 == Σ `MINT_PRODUCTION`、`status=NORMAL`、无利息腿，非自持合同读数**逐值不变**。

## ① 改动文件清单（14 个；md5 = 本批落盘字节）

| # | 文件 | md5 | 性质 |
|---|---|---|---|
| 1 | `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomySetGovernmentMintScaleHandler.java` | `3b03b515…` | **新增**（D1 命令 handler） |
| 2 | `simos-app/src/main/java/io/mosire/simos/app/tools/write/EconomySetGovernmentMintScaleTool.java` | `b69ce925…` | **新增**（D1 GM 窄工具） |
| 3 | `simos-app/src/main/java/io/mosire/simos/app/Shell.java` | `a4fb493d…` | D1 命令注册（桶①） |
| 4 | `simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java` | `5165e4d1…` | D1 GM 工具注册（桶②） |
| 5 | `simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java` | `b59a293c…` | D1 载荷提示（桶③；构造期强制覆盖全部注册 type） |
| 6 | `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyCommandPayloads.java` | `8a8c4310…` | D1+D4 政府寻址唯一拼写点 |
| 7 | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/money/MoneyIssuanceKind.java` | `e515711b…` | D4 新类别 `GENESIS_ENDOWMENT` |
| 8 | `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyRecordMoneyIssuanceHandler.java` | `d69b0d0b…` | D4 精确政府 id 寻址 + kind 清单从枚举现取 |
| 9 | `simos-app/src/main/java/io/mosire/simos/app/tools/write/GovWorldBootstrap.java` | `ef84e244…` | D4 两条审计腿（只加审计） |
| 10 | `simos-app/src/main/java/io/mosire/simos/app/tools/write/GovIssueMoneyTool.java` | `7ac0d005…` | D4 连带：kind 清单与拒绝面同口径 |
| 11 | `simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java` | `101c4dd7…` | D2 分类唯一判据点 + 两个读口改写 |
| 12 | `simos-economy/src/main/java/io/mosire/simos/economy/time/DebtContractBook.java` | `ff8dbb32…` | D5 自持合同唯一拼写点 `isSelfHeld` |
| 13 | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java` | `e14b0924…` | D5 计息排除 + 两处既有排除改用拼写点 |
| 14 | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyLiquidationSettlement.java` | `1b1609bd…` | D5 到期即默认/压力判定排除自持 |

## ② 编译真命令 + rc（**一次只有一个 Maven**）

| 轮 | 命令 | rc | 备注 |
|---|---|---|---|
| 格式化 | `tools/mvn-lock.sh -q spotless:apply` | **0** | 全仓；改后 `git diff --name-only` = 本批 12 改 + 2 新（无越界文件） |
| 编译①（本批） | `tools/mvn-lock.sh -DskipTests compile -am` | **0** | `[INFO] BUILD SUCCESS` |
| 编译②（D4 变异轮） | 同上 | **0** | 变异体编译过（红在**读数**上，不是编译错） |
| 编译③（D2+D5 变异轮） | 同上 | **0** | 同上 |
| 编译④（还原轮） | 同上 | **0** | 三个变异文件 md5 与本批落盘字节**逐字节相同**（见 §⑧） |
| 单条回归 | `tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MintProductionSettlementTest' -Dsurefire.failIfNoSpecifiedTests=false` | **0** | `Tests run: 7, Failures: 0, Errors: 0` |

★ **没跑** `verify` / `clean` / `package`（任务书禁止项，归控制方）。

## ③ 四条逐条（现状 file:line → 改法 → 自证真读数 → 判据）

### D1 —— 世界级政府的铸币旋钮 GM 写口（**新增命令 + 工具 + 三桶**）

- **现状**：`Government.mintScalePerCycle` 的运行期唯一写点 = `EconomyRegisterGovernmentHandler:323/358`，只写
  `gov-unit-<id>` 形态；小世界唯一会铸币的政府是世界级 `world-silver`（`EconomySeeder.GENESIS_GOVERNMENT_ID`）
  ⇒ 以它调用 **HTTP 422**；`Government.withMintScalePerCycle` 全仓 main 无调用者。
- **改法**：新增 `economy.SetGovernmentMintScale`（`GmOnlyCommand`，只写 `governments` 一个政府的一个字段，走
  `base.withGovernments → EconomyChangeSet.between`）+ GM 窄工具 `simos.economy.setGovernmentMintScale` + 三桶注册。
  地址两路恰给一个（唯一拼写点 `EconomyCommandPayloads.requireGovernmentAddress`）：
  `governmentId`（精确 id —— 世界级政府只能走这一路）/ `govUnitId`（派生 `gov-unit-<id>`）。
- **自证真读数**（真 Shell 小世界 + 真命令路径 `core().submitBatch`，探针 `/tmp/w5fix6/W5F6Probe.java`：
  `c1f65cbf…`；日志 `after-final.log` `45667d88…`）：
  ```
  SUBMIT D1-knob300   head=1 -> COMMITTED rev=2   headAfter=2      ← Δ=1 revision
  KNOBS after-D1 = world-silver=300,gov-unit-gov-central=123,gov-unit-gov-province=0
  SUBMIT D1-unknown      -> REJECTED government-missing: 该政府未登记…: no-such-gov   headAfter=2（零 revision）
  SUBMIT D1-negative     -> REJECTED bad-payload: mintScalePerCycle 不得为负…: -1
  SUBMIT D1-noninteger   -> REJECTED bad-payload: …字段 mintScalePerCycle 必须是整数: 1.5
  SUBMIT D1-missing-field-> REJECTED bad-payload: …必须是整数: null
  SUBMIT D1-unchanged    -> REJECTED mint-scale-unchanged: 该政府的铸币规模已经是它，没变化   headAfter=2（幂等 ⇒ 零 revision）
  SUBMIT D1-old-unit-form-> COMMITTED rev=3   headAfter=3          ← 旧 govUnitId 形态**不受影响**
  ADVANCE (0,120]  ⇒ READ tick=120 mintProductionSum=300
  INFO MINT_LIMIT_REJECTED  day=120 period=4 government=world-silver currency=silver unit=unit-mint@0_0-… industry=mint@0_0
       scale=1 perUnit=500 requested=500 limit=300 previousCycleMinted=-1 mintScalePerCycle=300 rejected=200
       reason=above-mint-scale-policy
  SUBMIT D1-knob2000  head=5 -> COMMITTED rev=6   headAfter=6
  ADVANCE (120,240] ⇒ READ tick=240 mintProductionSum=800        ← 本轮实铸 500（= 800−300），**不被 300 夹**
  ```
  INFO 写口日志（§一.9）：
  `GOVERNMENT_MINT_SCALE_SET origin=economy-command government=world-silver from=2000 to=300 issuable=[silver] treasury=HOUSEHOLD/hh-gov-world-silver governments=3 effectiveFrom=next-mint-cycle-close`
  （调回那轮同样一条 `from=300 to=2000`；具名拒 `GOVERNMENT_MINT_SCALE_REJECTED` ×5）。
- **判据满足**：✅ 旋钮 2000→300 ⇒ 实铸 300 + `MINT_LIMIT_REJECTED(limit=300, rejected=200)`；✅ 调回 2000 ⇒ 实铸 500；
  ✅ 旧单位级注册形态不受影响（同轮 `govUnitId=gov-central` 提交成功、`gov-unit-gov-central=123`）；✅ 幂等/Δ=1/具名拒齐全。

### D2 —— 发行不能被当成回笼（**两个只读端点按枚举 `issuance()` 分类**）

- **现状**：`ApiViews.governmentIssuanceView:1772-1790` 与 `moneyIssuanceView:4266-4290` 各自 `else → withdrawal`
  ⇒ `MINT_PRODUCTION`（枚举 `issuance()==true`）被记成回笼。
- **改法**：抽出**唯一判据点** `ApiViews.IssuanceTotals`（两个读口共用）：方向先由 `kind.issuance()` 分流、
  再按 kind 落栏（`initialEndowment` / `genesisEndowment` / `mintProduction` / `fiscalIssue` / `withdrawal`）；
  新增两栏 `genesisEndowment` / `mintProduction`（**只增键，不改既有键语义**）。
- **自证真读数**（同一 tick 的两个端点）：
  ```
  D2 tick=120 govView world-silver = {recordCount=4, initialEndowment={silver=67200}, genesisEndowment={silver=200000},
                                     mintProduction={silver=300}, withdrawal={}, netIssuance={silver=267500}}
  D2 tick=120 overviewView = {initialEndowment={copper=100000, silver=67200}, genesisEndowment={silver=200000},
                              mintProduction={silver=300}, fiscalIssue={}, cumulativeIssuance={copper=100000, silver=267500},
                              cumulativeWithdrawal={}, netIssuance={copper=100000, silver=267500},
                              circulation={copper=100000, silver=267500}}
  ```
  ⇒ `withdrawal` **不含** `MINT_PRODUCTION`（空表）；发行栏含 300；`netIssuance` 与 `circulation` **逐值相等**（267,500）——
  即"方向与量一致（+300）"（改前：`circulation` 267,700 而 `netIssuance` 66,700，见 §⑥ 的变异读数）。
- **负向自证**（单独探针 `W5F6D2Negative.java` `dcf1e729…`）：注入一条**回笼类**记录
  `MoneyIssuanceRecord(id=probe-withdrawal-1, gov=world-silver, cur=silver, amount=777, kind=WITHDRAWAL)` ⇒
  ```
  D2NEG after = {… mintProduction={}, genesisEndowment={}, cumulativeIssuance={copper=100000, silver=267200},
                 cumulativeWithdrawal={silver=777}, netIssuance={copper=100000, silver=266423}, circulation={copper=100000, silver=267200}}
  ```
  ⇒ **仍归 withdrawal**（266,423 = 267,200 − 777），发行栏一个数不动。
- **判据满足**：✅ 正向、✅ 负向。

### D4 —— 创世国库注资落审计腿（**只加审计，零数值改动**）

- **现状**：`GovWorldBootstrap`（small-world 创世）用裸原语 `actor.AdjustAccounts` 给两国库各注 100,000 毫银
  （合计 200,000 = 创世银 267,200 的 **74.9%**），**只有那笔铜**有 `MoneyIssuanceRecord`。
- **改法**：同批追加**两条** `economy.RecordMoneyIssuance`（新 kind `GENESIS_ENDOWMENT`；`governmentId` = 世界里
  **银的唯一发行者**，从 state 现取 `requireSilverIssuer`；收款国库写在 `reason`）。**余额腿一个字未改**。
  ★ 为什么发行主体是 `world-silver` 而不是两个 GOV：`MoneyIssuanceRecord.governmentId` 的语义是**发行主体**
  （handler 只在 `Government.issuable` 上判"谁能发"），而两个 GOV 的 `issuable` 不含银（中央只发铜、省不发钞）
  ⇒ 记它们会当场被 `currency-not-issuable` 拒、且那句话本身是假的。
- **自证真读数**（**前后逐值对照**，同一份探针字节）：
  ```
  改前 before.log（9c850ceb…）  D4 genesis moneyTotals={copper=100000, silver=267200}
                                treasuryCentral={silver=100000, copper=100000}  treasuryProvince={silver=100000}
                                issuanceCount=2  issuanceSum={copper:INITIAL_ENDOWMENT=100000, silver:INITIAL_ENDOWMENT=67200}
  改后 after-final.log（45667d88…） moneyTotals={copper=100000, silver=267200}   ← **逐值相同**
                                treasuryCentral={silver=100000, copper=100000}   ← **逐值相同**
                                treasuryProvince={silver=100000}                 ← **逐值相同**
                                issuanceCount=4
                                REC id=gov-issue-world-silver-0-silver-1 kind=GENESIS_ENDOWMENT gov=world-silver cur=silver amount=100000
                                REC id=gov-issue-world-silver-0-silver-2 kind=GENESIS_ENDOWMENT gov=world-silver cur=silver amount=100000
                                issuanceSum={copper:INITIAL_ENDOWMENT=100000, silver:GENESIS_ENDOWMENT=200000, silver:INITIAL_ENDOWMENT=67200}
  ```
  ⇒ **两笔各 100,000（kind 具名、gov=world-silver、cur=silver）**；货币总量与两国库余额**逐值不变**；★ 连带读数变好：
  `Σ发行 == circulation`（267,500 == 267,500）恒等式**闭合**（改前恒差 +200,000）。
  ★ **id 反查**：`gov-issue-<gov>-<day>-<cur>-<seq>`（既有确定性形状，gov 与币种都能从 id 反查）。
- **负向自证（变异体，任务书点名要求）**：把两条审计腿去掉（`for (String govId : List.<String>of())` + `// MUTANT`）
  ⇒ 重编译 rc=0、跑同一探针（`mut1.log` `d212ea52…`）：
  ```
  D4 genesis issuanceCount=2   issuanceSum={copper:INITIAL_ENDOWMENT=100000, silver:INITIAL_ENDOWMENT=67200}
  D2 overviewView netIssuance={copper=100000, silver=67500} vs circulation={copper=100000, silver=267500}
  ```
  ⇒ **当场红**（缺口 +200,000 复现）⇒ 还原（md5 `ef84e244…` 与本批落盘字节相同）。
  ★ **另一条负向（更硬的 fail-closed）**：把 `requireSilverIssuer` 换成不存在的政府 ⇒ `currency-not-issuable`/`government-not-registered`
  具名拒 ⇒ `applier.apply` 当场抛 ⇒ **整次创世失败**（不会静默落一个没有审计腿的世界）。本批**没有**跑这一条（未验证项，见 §⑦）。
- **判据满足**：✅ 两笔；✅ 零数值变化；✅ 负向当场红。

### D5 —— 自持铸币债不计息、不标违约（**同族最后两处漏网**）

- **现状**：自持合同（`debtor == creditor`）被按 2%/周期计息（`EconomySettlement.chargeInterest:10244`）并恒标
  `DEFAULTED`（`EconomyLiquidationSettlement.effectiveStatuses:1342`）；500 → 1,040@d240 → **1,624@d360**（W5 报告同值）。
- **改法**：`DebtContractBook.isSelfHeld` 成**唯一拼写点**；四处用它：还债轮（既有 W5-fix）、退出清算（既有 W5-fix）、
  **计息**（新增 DEBUG `DEBT_INTEREST_SELF_HELD_SKIPPED`）、**到期即默认 + 违约压力**（新增 DEBUG
  `DEBT_STRESS_SELF_HELD_SKIPPED`）。
- **自证真读数**（≥3 个关账轮；真世界）：
  ```
  轮次        Σ MINT_PRODUCTION   自持合同 principal   lastInterestDay    status    （非自持：total/defaulted/withInterestLeg/principalSum）
  改前 d120       500（旋钮不可改）      500              empty          NORMAL      240/91/115/70258480
  改前 d240      1,000                1,040              [240]        DEFAULTED     509/334/411/121021589
  改前 d360      1,500                1,624              [360]        DEFAULTED     528/401/434/145389731
  ─────────────────────────────────────────────────────────────────────────────────
  改后 d120        300                  300              empty          NORMAL      240/91/115/70258480   ← 逐值相同
  改后 d240        800                  800              empty          NORMAL      509/334/411/121021589 ← 逐值相同
  改后 d360      1,300                1,300              empty          NORMAL      528/401/434/145389731 ← 逐值相同
  ```
  ★ **本金 == Σ `MINT_PRODUCTION` 逐值相等、无利息腿（`lastInterestDay` 空）、`status` 从未变 `DEFAULTED`**；
  ★ **非自持对照逐值不变**（同一份读数与改前**完全一致**：`total/defaulted/withInterestLeg/principalSum` 四个数三轮全同）；
  探针另造的非自持合同（真 GM 命令 `economy.UnitBorrow`：5,000 毫银 @20‰）三轮利息腿照旧：
  `5,412@d120 → 5,856@d240 → 6,337@d360`（`lastInterestDay=[120]/[240]/[360]`）—— 改前改后**逐值相同**。
  DEBUG 具名行（`-Dsimos.economy.logLevel=DEBUG`，`after-debug.log` `88fb2d23…`）：
  ```
  DEBT_INTEREST_SELF_HELD_SKIPPED day=150 debtor=hh-gov-world-silver unit=money:silver startPrincipal=300 wouldCharge=6
        outstandingPrincipal=300 why=self-held-is-the-issuance-bookkeeping-fact-not-interest-bearing      （×8）
  DEBT_STRESS_SELF_HELD_SKIPPED   day=120 cycle=4 household=hh-gov-world-silver unit=money:silver
        outstandingPrincipal=300 why=self-held-not-executable                                          （×9）
  DEBT_REPAY_SELF_HELD_SKIPPED    day=120 household=hh-gov-world-silver …（既有 W5-fix 行照旧 ×9）
  ```
- **负向自证（变异体，两处一起，读数面不相交 ⇒ 归因清晰）**：`chargeInterest` 与 `effectiveStatuses` 的排除改成
  `if (false && isSelfHeld(...))`（`// MUTANT`）⇒ 重编译 rc=0、同一探针（`mut2.log` `ecafa6f5…`）：
  ```
  D5 d240 selfHeld={principal=824  lastInterestDay=OptionalLong[240] status=DEFAULTED}   ← ≠ Σ mint 800（利息 24）
  D5 d360 selfHeld={principal=1390 lastInterestDay=OptionalLong[360] status=DEFAULTED}   ← ≠ Σ mint 1300（利息 90）
  ```
  ⇒ **当场红**（利息腿与 DEFAULTED 双双复现）⇒ 还原（md5 `e14b0924…` / `1b1609bd…` 与本批落盘字节相同）。
- **判据满足**：✅ ≥3 关账轮；✅ 本金 == Σ 发行额；✅ `status != DEFAULTED`；✅ 无利息腿；✅ 非自持对照逐值不变。

## ④ 新工具 / 新命令的注册落点（三桶 + 提示 + GmOnly）

| 桶 | 落点 | 证据 |
|---|---|---|
| ① 命令注册 | `Shell.java:695` `new EconomySetGovernmentMintScaleHandler()` | 真提交 `COMMITTED rev=2`（未注册时是"未注册的命令类型"，见 before.log） |
| ② GM 窄工具 | `SimosToolSource.java:803` `built.add(new EconomySetGovernmentMintScaleTool(...))` | 编译期在册；`SimosToolsTest` 的"同源判据"会把它计入（见 §⑤） |
| ③ catalog 载荷提示 | `CatalogTool.PAYLOAD_HINTS` 新增 `economy.SetGovernmentMintScale` 条目 | **构造期强制**：缺项 ⇒ `CatalogTool` 构造抛（app 起不来）⇒ 已补 |
| GmOnly | `EconomySetGovernmentMintScaleHandler implements CommandHandler, GmOnlyCommand` | 不进令白名单 / `RegisterEffect` / 决策人目录（照 W2 先例） |
| 工具名 | `simos.economy.setGovernmentMintScale` | **不是**命令类型 ⇒ 不进 catalog/`PAYLOAD_HINTS`（与 W2 同款） |
| 写面资源 | 只声明 `economy` 命名空间（handler 只产 `EconomyChangeSet`） | `ResourceManifest.of(ToolSupport.ECONOMY_NAMESPACE, UNRESTRICTED)` |

## ⑤ 铁律 5 落点（D1/D4 是否碰形状）

- **D1：零形状改动**。不新增/不删除任何持久组件；只改既有字段 `Government.mintScalePerCycle` 的**值**。
  往返三件套 = 该字段**既有**的三件套（`MintSettlement.writeLastCycleReadout` 与 `economy.RegisterGovernment` 已在写它）：
  `base.withGovernments(...)` → `EconomyChangeSet.between`（`FieldDelta.diff` 按 record 相等判变化）→ Codec（Jackson record 绑定）。
  **往返证据**：真 Shell 世界写盘 → `world.state()`（真 replay，走 Codec 解码）读回 ⇒ 旋钮值 300/123/2000 逐个读回正确；
  探针所有"改后读"的读数都是 replay 出来的（`READ`/`KNOBS` 行）。
- **D4：零形状改动**。`MoneyIssuanceKind` 加一个枚举常量 = 值域扩大（不是"加组件"）；`MoneyIssuanceRecord` 的组件表一字未改；
  `EconomyData.moneyIssuances` 这个组件一字未改。**往返证据**：两条 `GENESIS_ENDOWMENT` 记录在创世档里落盘、重启后由
  `world.state()`（真 replay）逐字段读回（`id/kind/gov/cur/amount/day/period` 全部正确）。
- 结论：**两处都没有新增"派生不完整"的面**，不存在需要新往返不变式测试的新形状。

## ⑥ 会改变数值行为的清单（逐条具名）

| # | 改动 | 会不会改数值 | 具名说明 |
|---|---|---|---|
| 1 | D1 新写口本身 | **零数值变化**（不写就不变） | 只改 `governments` 里一个字段的值；不碰余额/债务/发行/产业/市场 |
| 2 | D1 生效后的铸币量 | **会**（这是修复的目的） | 旋钮被 GM 真改过才会变：本批自证 300（旋钮 300）/ 500（旋钮 2000）。**未改旋钮的世界逐值不变** |
| 3 | D2 两个只读端点 | **零状态变化**（只读） | 只有读数口径变：`mintProduction`/`genesisEndowment` 两栏**新增键**；`withdrawal` 不再含发行类；`netIssuance` 改为含全部发行方向 |
| 4 | D4 两条审计腿 | **零数值变化（只加审计）** | 金额/账户/余额/货币总量逐值不变（§③-D4 前后对照）；变化只有 `moneyIssuances` 多两条记录 + 两个只读端点的栏位 |
| 5 | D4 连带：`RecordMoneyIssuance` 拒绝 `kind=MINT_PRODUCTION` | **零数值变化**（原本可写的伪记录现在具名拒） | 铸币审计唯一生产者是 `MintSettlement`（确定性 id）；`GovIssueMoneyTool` 同口径具名拒 |
| 6 | D5 自持合同不计息 | **会**（这是修复的目的） | 只影响 `debtor == creditor` 的合同；**非自持合同读数逐值不变**（§③-D5 三轮对照四数全同） |
| 7 | D5 自持合同不标 `DEFAULTED`/不计压力 | **会**（读口 + 阶层压力计数） | 同上：只影响自持合同；政府家户不再被自己的记账事实推着累积 `consecutiveDebtStressCycles` |
| 8 | D5 `isSelfHeld` 替掉两处既有 inline 判据 | **零数值变化** | 纯机械替换（`debt.debtor().equals(debt.creditor())` → 同名谓词），语义逐字相同 |
| 9 | `GovWorldBootstrap.requireSilverIssuer` | **零数值变化** | 只读 state；**取不到/多个 ⇒ 创世当场抛**（fail-closed：宁可不创世，也不落一个没有审计腿的世界） |

★ **例外必须点出**：唯一"世界形态变了"的是 **small-world / three-powers 的创世档多两条记录**（D4）—— 按 §一.11，
旧档一律作废（不写兼容位、旧档读不了就读不了）。

## ⑦ 会让既有测试断言失效的清单（按类名，**只报不改**）

| 类 | 断言 | 为什么红 | 修法（留给测试 Agent） |
|---|---|---|---|
| `io.mosire.simos.app.tools.SimosToolsTest` | `catalogListsExactlyTheRegisteredCommandTypes`：`hasSize(EXPECTED_COMMAND_TYPES.size())` + `containsExactlyInAnyOrderElementsOf` | 硬编码清单 `EXPECTED_COMMAND_TYPES`（`:517`）没有新 type | 清单加 `economy.SetGovernmentMintScale` |
| 同上 | `catalogCoversEveryCommandHandlerImplementation`：`hasSize(141)`（`:936`） | handler 实现面 141 → **142** | 141 → 142 |
| 同上 | `roleBucketsNeverCarryGenericWrite`：GM 桶 `hasSize(163)`（`:974`） | GM 桶 163 → **164**（新窄工具） | 163 → 164 |
| 同上 | `everyNarrowWriteToolClassIsWiredIntoTheGmBucket`：`hasSize(67)`（`:1084`）+ `containsExactlyInAnyOrderElementsOf(NARROW_WRITE_NAMES)`（`:484` 的硬编码清单） | 窄写工具类 67 → **68** | 67 → 68 + 清单加 `simos.economy.setGovernmentMintScale` |
| 同上 | `everyToolClassOnDiskIsRegisteredInSomeBucket`：`hasSize(173)`（`:1727`） | 磁盘 `*Tool.java` 的 NAME 173 → **174** | 173 → 174 |
| `io.mosire.simos.app.McpCoverageTest` | `:999-1008`：`catalogTypes containsExactlyInAnyOrderElementsOf(EXPECTED_COMMAND_TYPES)`（`:139` 硬编码清单）+ `MINIMAL_PAYLOADS.keySet()` 覆盖全部可提交 type（`:1005`） | 新 type 既不在清单里、也没有最小载荷 | 清单加新 type + `PRECONDITION_REJECT_TYPES` 加它 + `MINIMAL_PAYLOADS` 备一条"具名拒"载荷（夹具世界没有 `world-silver` 政府 ⇒ 走 `government-missing`） |
| `io.mosire.simos.app.McpServerTest` / `McpPortTopologyTest` | 仅 `contains(...)`/`containsExactlyInAnyOrderElementsOf(EXTERNAL_UNION_GM_TOOL_NAMES)` 之类 | **可能**红——若它们的"外部联合工具名单"是硬编码全量清单 | 需测试 Agent 复核（我只做了静态核对：两处名单里都有 `simos.gov.issueMoney`，未见计数断言） |

★ **没红的那一类（如实记）**：`MintProductionSettlementTest`（7/7 绿）—— 它在**单日夹具**里跑，自持合同既不到计息日也不到 `dueCycle`，
故 D5 的两处排除对它无影响；这不是"覆盖到了"，只是"没被碰到"。

## ⑧ 未完成 / 未验证项（如实）

1. **没跑** `verify` / `clean` / `package`（任务书禁止项）；**没跑** `simos-app` 全量套件（`SimosToolsTest` / `McpCoverageTest` 的
   红**是静态核出来的**，不是跑出来的 —— 见 §⑦）。
2. **`three-powers` 的同族站点未做**（超出本批点名范围）：`ThreePowersGovBootstrap:637-666` 的 `recordIssuances` 只给**该 GOV 自己
   发行的币种**记审计，两个省的 100,000 毫银**仍无发行记录**（该文件类注把这写成"如实的边界"）。设计与 D4 的目标
   （"任何货币凭空出现都能在审计链上查到"）同族 ⇒ **建议同口径补两条**（发行主体 = 该世界声明发行银的那个 GOV）。
   ★ 我没做，理由：本批点名的是 `GovWorldBootstrap`；且这会改另一个世界的创世档（已按 §一.11 作废，但需要控制方确认口径）。
3. **没验**"`requireSilverIssuer` 找不到/多个发行者 ⇒ 创世当场抛"这条 fail-closed 路径（只做了代码面论证 + 变异体去掉整条腿的那一半）。
4. **没验**多币种世界里的 D1（旋钮逐币种共用这条既有粗口径未变）与 `-Dsimos.economy.logLevel=TRACE` 那一档。
5. **D2 只验了 `/api/economy/gov` 与 `/api/economy/overview` 两个 JSON 端点**；GUI 页面（`ApiViews` 背后的前端呈现）没看
   （新增两栏是否要在前端露出，属 W6/前端面）。
6. **D4 的 `reason` 文本**（收款国库名）只进了记录与日志，**没有**做"按 reason 反查"的读口；id 反查只到 gov/币种/日。
7. **没验** `GovIssueMoneyTool` 的 `kind=GENESIS_ENDOWMENT`/`MINT_PRODUCTION` 拒绝面（改的是 app 侧工具的参数校验，
   只做了编译与代码面核对；没跑 `GovToolsZ6Test` 一类）。
8. **探针全部在 `/tmp/w5fix6/`，未落仓**（`W5F6Probe.java` `c1f65cbf…`、`W5F6D2Negative.java` `dcf1e729…`、
   `run-probe.sh` `052de444…`、`digest.sh` `3c983082…`；日志 `before.log` `9c850ceb…`、`after-final.log` `45667d88…`、
   `after-debug.log` `88fb2d23…`、`mut1.log` `d212ea52…`、`mut2.log` `ecafa6f5…`）。

## ⑨ 偏离记录

| # | 偏离 | 原因 | 影响 |
|---|---|---|---|
| D-1 | 变异轮**临时改过 `src/main`**（`GovWorldBootstrap` 1 处 + `ApiViews` 1 处 + `EconomySettlement` 1 处 + `EconomyLiquidationSettlement` 1 处，各带 `// MUTANT`） | 任务书点名要求 D4 负向"把该审计腿改坏/去掉 ⇒ 断言或探针当场红"；D2/D5 照 §三.2 一并做 | **逐字节还原**（4 个文件 md5 与本批落盘字节相同：`ef84e244…` / `101c4dd7…` / `e14b0924…` / `1b1609bd…`），还原后重编译 rc=0、探针读数与变异前**逐字节相同**（`after-final.digest.txt` vs `after.digest.txt` = IDENTICAL），全仓 `MUTANT` 0 命中 |
| D-2 | D1 的地址面**新增** `governmentId`（精确 id），与既有 `govUnitId`（派生）**并存** | 世界级政府不是任何 GOV 单位、没有 `gov-unit-` 派生形态（`GovernmentIds` 类注明写"世界级政府不在此列"）⇒ 没有这一路就**永远够不着** `world-silver` | **不是兼容位**：两路各自服务一种政府形态、判据互斥（同时给 ⇒ 具名拒 `bad-payload`）；既有 `govUnitId` 语义与守卫一字未改 |
| D-3 | 顺带把 `kind` 的**清单来源**从手抄改成 `MoneyIssuanceKind.values()`（`RecordMoneyIssuanceHandler` + `GovIssueMoneyTool`），并让两条写口都具名拒 `MINT_PRODUCTION` | 同一个"哪些类别可手写"的问题出现在两处；手抄清单在加 `GENESIS_ENDOWMENT` 时会**静默说谎**；`MINT_PRODUCTION` 的真生产者是 `MintSettlement`（确定性 id），手写它会造伪审计 | 零数值变化（拒绝面更严）；已记入 §⑥#5 与 §⑦ |
| D-4 | **`GovWorldBootstrap` 的审计发行主体 = `world-silver`**（银的唯一发行者），不是收款的 GOV | 见 §③-D4 的理由（`governmentId` 语义 = 发行主体 + `issuable` 守卫 + 一币一发行人） | 两条记录的 `reason` 里写明收款国库；若控制方要求"记收款方"，需要给 `MoneyIssuanceRecord` 加"收款人"维（**碰形状**，本批不做） |

## ⑩ 补测：三桶注册的**运行期**自证（W5F6ToolsProbe，`/tmp/w5fix6/`，不入库）

```
TOOLS shellStarted=true gmSize=164 dmSize=44
TOOLS gmHasTool=true                     ← simos.economy.setGovernmentMintScale ∈ GM 桶（163 → 164）
TOOLS dmHasTool=false                    ← 不在决策人桶（权限不放大；GmOnlyCommand 三条路径同样排除）
TOOLS gmHasCommandSubmit=true            ← 对照：GM 桶确实有通用写（口径未变）
TOOLS catalogToolConstructed=true        ← ★ 桶③ 的运行期证据：CatalogTool 构造**没抛** ⇒ PAYLOAD_HINTS 覆盖了全部注册 type
TOOLS handlerIsGmOnly=true
TOOLS handlerType=economy.SetGovernmentMintScale
TOOLS typeIsTheRegisteredOne=true
```

★ **`gmSize=164` 是实测值**（不是推断）：它同时钉死 §⑦ 里"`SimosToolsTest` 的 `hasSize(163)` 会红"这一条。
★ 探针文件 `W5F6ToolsProbe.java` md5 = `30d10378…`（当轮落盘）；它只起真 Shell 读工具桶，不改任何状态。

### ⑩-补：§① 文件清单的第 15 个（§① 表已封，此处追加）

| # | 文件 | md5 | 性质 |
|---|---|---|---|
| 15 | `simos-economy/src/main/java/io/mosire/simos/economy/model/Government.java` | `1509fc3f…` | **纯文档**：`@param` 上面的"政策可调 ⇒ GM 写口"那条从"唯一写口 = `economy.RegisterGovernment`"改成"两条写口各服务一种政府形态"（世界级政府那条 = 本批新增的 `economy.SetGovernmentMintScale`） |

★ **它只改 javadoc、零代码**：改后重跑同一探针 ⇒ 关键读数 digest 与改前**逐字节相同**（`e3f2c8d0…`）。
★ 为什么必改（§四「机制性描述一律回代码核」）：不改的话，`Government` 的类注会在本批之后**继续指着一条够不着世界级政府的写口**说"这就是写口"。

---

# W5d 工具面测试迁移

> 依据：W5-fix6 账本 §⑦「会让既有测试断言失效的清单」（**静态核**）+ 本批任务书。★ 本节的每个数字都是**真跑出来的**
> （7 轮 Maven，逐轮 rc 与关键输出见 §③）。
> 纪律：只改 `simos-app/src/test/**`；**`src/main/**` 一字未动**（`git status --porcelain` 里本批只新增 4 个测试文件的
> ` M`，其余 15 个 main 改动全是 W5-fix6 的原有工作树状态，未回退）。

## ⓪ 结论一句话

W5-fix6 新增的命令 `economy.SetGovernmentMintScale` + 窄工具 `simos.economy.setGovernmentMintScale` 让 **4 个**测试类同时红。
★ W5-fix6 账本 §⑦ 静态核出的是「3 个会红 + 1 个『可能红』」；**真跑把那个『可能红』钉成了红**（`McpPortTopologyTest`），
并且 `SimosToolsTest` 实际是 **7 条红**（§⑦ 只列了 5 条 —— 多出的两条同源，见 §② 注）。
逐条按**实测值**更新后：四个类 **81 条全绿 / rc=0**；**全 `simos-app` 套件 906 条全绿**
（子包 819 条 + 根包 87 条，0 failure / 0 error / 5 skipped（真 LLM 用例，与本批无关）/ rc=0）。

## ① 改动文件清单（4 个，全是 `src/test`；md5 = 本批落盘字节）

| # | 文件 | 改前 md5（= `git show HEAD:` 字节） | 改后 md5（本批落盘） | numstat（+ / −） |
|---|---|---|---|---|
| 1 | `simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java` | `4eff92fe…` | `4c3818ba…` | +31 / −21 |
| 2 | `simos-app/src/test/java/io/mosire/simos/app/McpCoverageTest.java` | `0cc600f3…` | `a0f0ff3f…` | +16 / −3 |
| 3 | `simos-app/src/test/java/io/mosire/simos/app/McpServerTest.java` | `18f58ca7…` | `d55e5c1b…` | +6 / −3 |
| 4 | `simos-app/src/test/java/io/mosire/simos/app/McpPortTopologyTest.java` | `16860755…` | `061cecfc…` | +9 / −7 |

（`git diff --numstat -- simos-app/src/test` 合计 = **+62 / −34**；四个改后 md5 与"还原参照副本"逐字节相同 —— 见 §④。）

## ② 逐条改动（旧值 → 新值 + 依据）

| # | 类 : 落点（**改后行号**，逐条已回文件核过） | 旧值 | 新值 | 依据（W5-fix6 新增物 / 实测来源） |
|---|---|---|---|---|
| 1 | `SimosToolsTest` `ECONOMY_ZONE_WRITE_NAMES`（:338） | 5 条 | **6 条**（+`simos.economy.setGovernmentMintScale`） | 新工具继承 `AbstractNarrowWriteTool` ⇒ 窄写扫描器扫得到、只进 GM 桶 |
| 2 | 同上 `ECONOMY_ZONE_WRITE_COMMAND_TYPES`（:354） | 4 条 | **5 条**（+`economy.SetGovernmentMintScale`） | 该表口径 = "工具名 ≠ 命令类型"那几条（新工具正是这一形，同 W2 的 `setMarketZonePriceAlpha`） |
| 3 | 同上 `EXPECTED_COMMAND_TYPES`（:524）+ 其 javadoc | 141 条（javadoc 还写着过期的「136」） | **142 条** | 真跑 `catalogCoversEveryCommandHandlerImplementation` 实测 `Expected size: 141 but was: 142` |
| 4 | 同上 `NARROW_WRITE_NAMES` javadoc（:484） | 「63 条（8 map + **3 economy zone** + 28 unit + 15 sd + 1 army + 1 social + 1 actor + 6 决策共用/其他）」 | 「**66 条**（8 map + **6 economy** + 28 unit + 15 sd + **9 决策共用/其他**——含 1 army + 1 social + 1 actor）」 | 逐表实测计数：8+6+28+15+9 = **66**（原注释的 63 与 3 早已与代码不符，本批按实测改正） |
| 5 | 同上 `WRITE_TOOL_NAMES` javadoc（:503） | 123 条 = 60 非窄写 + 63 窄写 | **127 条 = 61 非窄写 + 66 窄写** | 同上实测（`NON_NARROW_WRITE_NAMES` 实测 61） |
| 6 | 同上 `GM_TOOL_NAMES` javadoc（:507） | 37 读 + 123 写 = 160 | **37 读 + 127 写 = 164** | 新工具是写工具 ⇒ 写侧 +1 |
| 7 | 同上 `catalogCoversEveryCommandHandlerImplementation`（:945） | `.hasSize(141)` | `.hasSize(142)` | 真跑实测 142（见 #3） |
| 8 | 同上 `roleBucketsNeverCarryGenericWrite`（:984） | `.hasSize(163)` | `.hasSize(164)` | 真跑实测 `Expected size: 163 but was: 164`；★ 运行期同证：`SHELL_ASSEMBLED … handler=142 … tool=164`、MCP `外发工具 164 个`、`Registry 共 164 个，未外发 0 个` |
| 9 | 同上 `everyNarrowWriteToolClassIsWiredIntoTheGmBucket`（:1094） | `.hasSize(67)` | `.hasSize(68)` | 磁盘 `tools/write/` 下继承 `AbstractNarrowWriteTool` 的类 +1（真跑实测 68；67 → 68 的两条差额 = 决策人桶那两条外交窄写，口径未变） |
| 10 | 同上 `gmNarrow` 的 `.as(...)`（:1104） | 「65 条：9 + 8 map + **5 economy-zone** + 28 unit + 15 sd」 | 「**66 条**：9 + 8 map + **6 economy** + 28 unit + 15 sd」 | `NARROW_WRITE_NAMES` 实测 65 → 66 |
| 11 | 同上 `everyToolClassOnDiskIsRegisteredInSomeBucket`（:1737） | `.hasSize(173)` | `.hasSize(174)` | 新增一个 `*Tool.java`（`EconomySetGovernmentMintScaleTool`）⇒ 磁盘 NAME 面 173 → 174 |
| 12 | `McpCoverageTest` `EXPECTED_COMMAND_TYPES`（:140）+ javadoc + `:1014` 的 `.as(...)` | 141 条 | **142 条**（+`economy.SetGovernmentMintScale`） | 与 `SimosToolsTest` 同源（该文件自己写明"两处计数必须逐字相同"）；真跑实测红点名缺 `economy.SetGovernmentMintScale` |
| 13 | 同上 `PRECONDITION_REJECT_TYPES`（:305） | 无此条 | **+`economy.SetGovernmentMintScale`** | ★ **夹具世界 `governments` 为空**（没有 `world-silver`，也没有 `gov-unit-*` 政府）⇒ 形状合法的载荷必然走**具名拒**，不能进"提交成功"那一段 |
| 14 | 同上 `PRECONDITION_REJECT_PAYLOADS`（:372） | 无此条 | **+** `{"governmentId": "gov-missing", "mintScalePerCycle": 300, "reason": "coverage"}` | 走 `governmentId`（精确 id）那一路 —— 世界级政府只有这一路；拒因由 handler 的 `government == null` 分支给出 |
| 15 | `McpServerTest` `EXTERNAL_UNION_GM_TOOL_NAMES`（:109）+ javadoc + `:332` 的 `.as(...)` | 163 条 | **164 条**（+`simos.economy.setGovernmentMintScale`） | 该表是**外部联合口（EXTERNAL ∪ GM）的硬编码全量名单**，`containsExactlyInAnyOrderElementsOf` 与真 MCP `tools/list` 逐条比对 ⇒ 必须同源 |
| 16 | `McpPortTopologyTest` `ECONOMY_ZONE_WRITES`（:232）+ javadoc + `GM_NARROW_WRITES` javadoc（:242）+ J1 两处 `.as(...)`（:267/:277） | 6 条 / 「66 窄写 = 163」 | **7 条** / 「**67 窄写 = 164**」 | 同上：J1 与真 MCP 口 `containsExactlyInAnyOrderElementsOf` 逐条比对 |

★ **口径（不许放宽语义）**：16 处里**没有一处**把 `containsExactlyInAnyOrderElementsOf` 改成 `contains`、也没有删任何判据
—— 改的全是**名单内容**（+1 条真实存在的名字）与**计数**（+1）。唯一"新增判据"的是 #13/#14：那是 W5-fix6 的**新命令**
本来就需要的覆盖位（该测试的形制要求"每个 catalog type 要么有成功载荷、要么在具名拒族里在册"）。

★ **§⑦ 漏掉的两条红（本批真跑补出）**：`SimosToolsTest.registryContainsExactlyTheExternalUnionGmTools:709` 与
`writesAreSensitiveAndAskWithTheToolNameAsClassKey:1854` —— 两者都断言 `GM_TOOL_NAMES`（= 读 ∪ 写名单）与**真工具面**逐条相等，
新工具没进 `NARROW_WRITE_NAMES` ⇒ `GM_TOOL_NAMES` 少一条 ⇒ 一起红。**修法是同一处**（#1 加名单），不是两条独立改动。

## ③ 实跑真命令 + rc + 关键输出（**一次只有一个 Maven**；7 轮）

全部用 `tools/mvn-lock.sh test -pl simos-app -am -Dtest='…' -Dsurefire.failIfNoSpecifiedTests=false`。

| 轮 | 目的 | `-Dtest` | rc | 关键输出（原文） |
|---|---|---|---|---|
| 1 | **改前基线** | `SimosToolsTest` | **1** | `Tests run: 71, Failures: 7, Errors: 0`；逐条点名：`catalogCoversEveryCommandHandlerImplementation:936 [Expected size: 141 but was: 142]`、`roleBucketsNeverCarryGenericWrite:974 [Expected size: 163 but was: 164]`、`everyNarrowWriteToolClassIsWiredIntoTheGmBucket:1084 [Expected size: 67 but was: 68]`、`everyToolClassOnDiskIsRegisteredInSomeBucket:1727 [Expected size: 173 but was: 174]`、`catalogListsExactlyTheRegisteredCommandTypes:854`、`registryContainsExactlyTheExternalUnionGmTools:701`、`writesAreSensitiveAndAskWithTheToolNameAsClassKey:1845` |
| 2 | 四个类（修后） | `SimosToolsTest,McpCoverageTest,McpServerTest,McpPortTopologyTest` | **0** | `Tests run: 81, Failures: 0, Errors: 0` + `BUILD SUCCESS`；分项：SimosToolsTest 71/0、McpCoverageTest 1/0、McpServerTest 5/0、McpPortTopologyTest 4/0。★ 同轮日志里的**运行期真值**：`SHELL_ASSEMBLED … handler=142 participant=2 resolver=7 facet=2 tool=164`、`MCP streamable server 已启动 … 外发工具 164 个 … Registry 共 164 个，未外发 0 个` |
| 3 | **全 app 子包**（一处不漏） | `io.mosire.simos.app.**.*Test` | **0** | `Tests run: 819, Failures: 0, Errors: 0, Skipped: 5` + `BUILD SUCCESS`（**112** 个测试类；含 `SimosToolsTest 71/0`、`AdjudicateTickToolTest 16/0`、`CatalogVisibilityTest 3/0`、`DecisionCallerFactoryTest 10/0`）。★ 该模式**不匹配根包**（见轮 4） |
| 4 | **根包**（`**` 模式不匹配根包，补一轮） | `io.mosire.simos.app.*Test` | **0** | `Tests run: 87, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（**22** 个类；含 `McpCoverageTest 1/0`、`McpServerTest 5/0`、`McpPortTopologyTest 4/0`、`ShellEndToEndTest` 等） |
| 5 | **变异轮**（3 个名单各拿掉新名字，带 `// MUTANT`） | 同上四类 | **1** | `Tests run: 81, Failures: 5, Errors: 0` + `BUILD FAILURE`；5 条红逐条见 §④；`McpPortTopologyTest 4/0` 未变异 ⇒ 照旧绿（对照） |
| 6 | **还原后**（字节 = 轮 2 的字节） | 同上四类 | **0** | `Tests run: 81, Failures: 0, Errors: 0` + `BUILD SUCCESS` |
| 7 | **改前字节真红**（把 3 个类的 `git show HEAD:<path>` 字节换回去） | `McpCoverageTest,McpServerTest,McpPortTopologyTest` | **1** | 三类**各 1 条红**：`McpCoverageTest.everyCatalogTypeIsReachableThroughMcpAndTakesEffect:1002`、`McpServerTest.initializeAndToolsListExposeExactlyTheExternalUnionGmTools:330`、`McpPortTopologyTest.theSingleMcpPortExposesTheGmToolFace:276`；每条 AssertJ 的缺项恰是 `["economy.SetGovernmentMintScale"]` / `["simos.economy.setGovernmentMintScale"]` |

★ **`McpCoverageTest` 的新拒例真的走到了拒路径**（轮 2 日志原文）：
`COMMAND_REJECTED … type=economy.SetGovernmentMintScale … caller=agent:t11-coverage reason=government-missing: 该政府未登记（本命令只改已登记的政府；要建政府请走 economy.RegisterGovernment）: gov-missing`
+ `[T11-COVERAGE] type=economy.SetGovernmentMintScale result=rejected` ⇒ 与 W5-fix6 账本 §⑦ 的预判（"夹具没有 `world-silver` ⇒ 走 `government-missing`"）**逐字一致**。

## ④ 变异 / 负向自证（拿掉新名字 ⇒ 当场红 ⇒ 还原比 md5）

- **变异体三处**（各自带 `// MUTANT` 注释，替换掉那一行名字）：
  1. `SimosToolsTest`：`ECONOMY_ZONE_WRITE_NAMES` 拿掉 `"simos.economy.setGovernmentMintScale"`；
  2. `McpCoverageTest`：`EXPECTED_COMMAND_TYPES` 拿掉 `"economy.SetGovernmentMintScale"`；
  3. `McpServerTest`：`EXTERNAL_UNION_GM_TOOL_NAMES` 拿掉 `"simos.economy.setGovernmentMintScale"`。
- **落盘确实变了字节**（变异前 → 变异后）：`4c3818ba` → `ac5d1f5f`、`a0f0ff3f` → `b3bb6767`、`d55e5c1b` → `39d8841b`。
- **编译过**（日志无 `COMPILATION ERROR`）⇒ 红在**断言**上，不是"变异体编不过"那种假红。
- **当场红 5 条**（逐条点名，且缺项就是被拿掉的那个名字）：
  - `SimosToolsTest.everyNarrowWriteToolClassIsWiredIntoTheGmBucket:1104`；
  - `SimosToolsTest.registryContainsExactlyTheExternalUnionGmTools:709`（缺 `["simos.economy.setGovernmentMintScale"]`）；
  - `SimosToolsTest.writesAreSensitiveAndAskWithTheToolNameAsClassKey:1854`；
  - `McpCoverageTest.everyCatalogTypeIsReachableThroughMcpAndTakesEffect:1014`（缺 `["economy.SetGovernmentMintScale"]`）；
  - `McpServerTest.initializeAndToolsListExposeExactlyTheExternalUnionGmTools:332`（缺 `["simos.economy.setGovernmentMintScale"]`）。
- **还原**：三个文件从参照副本恢复；四个文件的 md5 与参照**逐字节相同**
  （`4c3818ba` / `a0f0ff3f` / `d55e5c1b` / `061cecfc`）；`grep -rn MUTANT simos-app/src simos-economy/src simos-social/src` = **0**；
  还原后重跑（轮 6）rc=0。
- **改前真红（更硬的一条）**：不满足于"变异体"，轮 7 用 `git show HEAD:<file>` 的**字面改前字节**换回三个类 ⇒
  各类当场 1 条红、点名同一个缺项 ⇒ 证明这 16 处改动是"把 W5-fix6 **真实存在**的命令+工具补进名单"，
  而不是"把断言放宽到能过"。

## ⑤ 我没做 / 没验证的（如实）

1. **没跑** `simos-economy` / `simos-social` 的**测试**：本批只改 `simos-app` 侧测试文件。`-am` 让这两个模块每轮都**编译**过，
   但它们的测试没被执行。静态核对：全仓 test 里引用 `setMarketZonePriceAlpha`（W2 先例）的只有那 3 个 app 测试文件；
   `MoneyIssuanceKind.values()` 无任何测试枚举其条数（只有逐常量断言），故判定无遗漏。
2. **没跑** `verify` / `clean` / `package`（任务书禁止；归控制方门禁）。
3. **没碰** `src/main/**`：因此"把新工具从 GM 桶真摘掉"的 **main 侧变异没做** —— 负向自证全在**测试名单侧**。
4. `AdjudicateTickToolTest` 的 `hasSize(66)`（白名单）与 `hasSize(96)`（样本表）**没单独测改前**；只做了改后真跑
   （轮 3 里 16 条全绿）+ 代码面论证：新 handler **不实现** `CommandTargets`，且它标 `GmOnlyCommand` ⇒
   白名单与 `targets` 两侧都取不到它 ⇒ 两个数都不变。
5. 轮 3 的 5 条 skipped 是 `RealLlmUnitDecisionLoopTest`(3) + `RealLlmGovScenarioTest`(2)（真 LLM 用例），**与本批无关**；
   我没查它们 skip 的原因、也没动它们。
6. **注释里仍有历史陈旧数字未一一订正**（与本批无关的旧账，例如 `McpCoverageTest` 里"除 5 条需要前置状态"、`96 条样本`
   一类的旁注）：本批只订正了**目标列表自己的** javadoc/`.as` 计数，没做全仓注释考古。
7. **`SimosToolsTest` 没做"字面改前字节"那一轮**（轮 7 只换回了另外三个类）：它的改前 md5 = `4eff92fe…`（`git show HEAD:` 现算），
   改前状态由**轮 1 的真跑**（`71 条 / 7 红`，逐条点名）代替。★ 未测的那一类风险很小：它的 7 条红里 5 条是**计数**断言
   （141/163/67/173 全部由同一批扫描器现算）、2 条是名单同源断言（`containsExactlyInAnyOrderElementsOf`）。

## ⑥ 未完成 / BLOCKED

**无 BLOCKED**。4 个类 + 全 app 套件全绿，变异自证与改前真红都落地，工作树未提交（按纪律：不 `git commit`）。
