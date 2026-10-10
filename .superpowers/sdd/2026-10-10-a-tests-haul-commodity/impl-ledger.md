# A 计划（运输服务商品化）测试收口 —— 实现架构账本

> 责任区：**A 计划的测试收口**（修被改架构的旧测试 + 按判据补新测试 + 跑真门禁 + 变异自证）。
> 权威判据：`docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` **v1.3** §5 I-H1..I-H7 / §6 T-H1..T-H5、N-H1..N-H3。
> 前置：A1 `a15fb3ec`、A2 `4194e90a`、A3 `cb053f1a`（三者都只到"编译过"，测试批是本批）。
> 允许写：任何 `src/test/**`、`simos-app/src/test/js/**`。**未碰任何 `src/main/**`**（变异自证临时改动已逐字节还原，见 §3）。
> 未 `git commit`（交控制方审后提交）。

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 证据 | 结论 | 影响 |
|---|---|---|---|
| S-1 | `grep -rn "haul\|HAUL" simos-*/src/test` = **0 命中**（本批之前） | A1/A2/A3 三批**一条测试都没有**：全部新判据靠静态推演 | 本批 13 条新用例是 A 计划的第一份运行期证据 |
| S-2 | `tools/mvn-lock.sh test-compile` → economy 测试树 10 处编译错，**全部落在 4 个文件** | 失效清单与 A3 交办单**逐条吻合**（无第五条漏网）；app 模块测试树零编译错 | 迁移面可控；`simos-app` 侧不存在"老架构老测试" |
| S-3 | `MarketSettlementFixtures.carrier()` 的劳动参数被我的第一版重构吞掉（`merchantStandingOf` 传 0） | **夹具自己的坑**：`endToEnd…` 期望运力 2500 实得 1000、`CapacityTruncation…` 期望 2905 实得 1000 | 当场修；两处红都不是"实现坏了"，是夹具回归（如实记） |
| S-4 | `MarketSettlementFixtures.industryUnit()` 若用无 hex 键的产业 id ⇒ `participantsFor` 按 `IndustryHexKeys.hexKeyOf(unit.industry())` 归集 ⇒ unit 挂不到任何格 | 产业 id **必须**带 hex 键（`IndustryHexKeys.id(kind,q,r)`） | T-H4 首版红（offered=5000，预留没生效）⇒ 改用 `IndustryHexKeys.id(...)` 后绿 |
| S-5 | `ProductionEnterprise` 构造期不变量：ACTIVE 必须带 unitId、且不得带 statusReason | T-H3 的 `EnterpriseProfitBook.collect(...)` 需要一条**真 enterprise**（带 unit） | 夹具给承运户挂一条 `outputPerUnit={haul:1}` 的跑商 unit（与真实播种同形） |
| S-6 | `MarketSettlement:2149-2152` `sellable = max(0, stock − frozen − necessary − retention)`；`necessaryInputsOf:8612` 读 `Industry.inputPerUnit() × 计划规模` | T-H4 可在**公开读数**（`SellerOutcome.offeredQty()`）上逐值核对 | 不需要反射/私有口，判据是真端到端的 |
| S-7 | `MerchantCapacityPool.assemble:387-389`：成员判据 = `capacity.capacityMilli() > 0`（劳动+工具） | 服务成市格的**运力预算**只看 haul 现货，但**进不进池**仍看劳动+工具 | 见 §6 缺陷 F-1（结构性口径差，未改） |
| S-8 | A3 账本"CARRIER_FEE 铸腿点恰 1 处" —— 实测**代码面** `MarketSettlement` 有 **2** 处（`:6658` 三元 + `:6686` 日志标签 `CARRIER_FEE_PAID`） | 护栏若只数行数会把日志标签判成第二铸腿点（**假红**） | 退役护栏按"身份"判：理由码恰一处且是三元，其余必须是字符串字面量 |

## 2. 本批做了什么

### 2.1 修/迁被改架构的旧测试（4 文件）

| 文件:行 | 旧断言 | 分类 | 理由 |
|---|---|---|---|
| `MerchantCapacityAcceptanceTest:39` | `MerchantCapacityPool.TOOL_COMMODITY` | **迁移** | 该常量 A3 起 private（不再是门槛拼写点）⇒ 夹具改从词表取键（单一权威） |
| 同上 `:152` | `CapacityQuoteBook.selfQuoted({small:0, big:500})` | **重写** | 逐户自报价簿整族退役（设计书 §3.4 / A3 K-7）⇒ 限价改**纯派生**（tier 成本 + 25‰）。**判据不变**：价格优先 → 同价回 canonical 序，故拆成两条用例（不同档 ⇒ 限价序；同档 ⇒ 议价权序） |
| 同上 `:175/185/189/198` | 「缺工具 ⇒ 该次跑商不成立」+ `pool.toolBlockedRuns()` | **删除** | 门槛族已退役（设计书 §3.2/§3.4、I-H6）。**反向判据**补在新类：工具 0 的户照样承运（旧架构下必红） |
| 同上 `endToEndFillIsCapped…` 注释 | "开市前读运力（本轮会烧工具）" | 注释更新 | 不再烧工具（工具走产业周期投入）；数值断言未动（labor 1500 + tool 1000 = 2500） |
| `CapacityTruncationPricingAcceptanceTest:61` | `MerchantHaul.TOOL_MILLI_PER_HAUL` | **迁移** | 常量已删 ⇒ 改夹具具名常量 `CARRIER_TOOL_MILLI`。**V-20 判据一字未动**（该世界只给 grain 定价 ⇒ 服务不成市 ⇒ 走原式） |
| `PortPolicyMerchantLaneAcceptanceTest:217` | `toolBlockedRuns()` 逐值相等 | **迁移** | 读口退役 ⇒ 改钉同层 `householdCountAt(H1)`（池成员读数）；J1/J3 判据不变；类注同步 |
| `MarketSettlementFixtures:187` | `MerchantCapacityPool.TOOL_COMMODITY` | **迁移** | 同上；夹具另加 `TOOL`/`HAUL`/`CARRIER_TOOL_MILLI` 常量 + `haulCarrier`/`industryUnit`/`serviceCarrierPool`（新判据的夹具，全部 test scope） |
| `EconomyVocabularyGuardTest` | 6 项词表护栏 | **补护栏** | +`haul` 字面量恰一处、+`CommodityId("haul")` 零命中、+前六项相对序不变且 haul 追加末位（源扫描 `allCommodityIds()` 的 return 语句，先自证读取非空） |

★ **判据与实现冲突的处置**：本批**没有**出现"改判据迁就实现"。唯一一处口径疑问（A2 的报价序）按 v1.3 §3.3 重写成"派生限价 + 同价 canonical 序"，与用户原话「按市场上最低价的运力提供商买运力」一致。

### 2.2 新增测试（13 条，逐条对判据）

| 文件 | 用例 | 判据 |
|---|---|---|
| `simos-util/…/verify/HaulParallelMachineRetirementGuardTest`（新，3 条） | 退役族代码面 0 命中（7 个标识符）；走私族 0 命中；**单腿铸运费**（CARRIER_FEE 恰一处且是三元互斥、EnterpriseProfitBook 只读） | N-H2、I-H5、T-H3 后一半 |
| `simos-app/…/world/HaulServiceCommoditySeedTest`（新，3 条） | 词表（前六项序 + haul 末位）；出厂价表/逐币市场含 haul 牌价；真播种的每个 `trade@hex`：`outputPerUnit` 非空产 haul、`laborPerUnit>0`、分配模板劳动档>0、`inputPerUnit()` **恰一项 tool** 且 = 单套化派生常量 | T-H1 |
| `simos-economy/…/time/HaulServiceCommodityAcceptanceTest`（新，7 条） | ①派生需求量与既有运费算式可核对（含与 `freightUnitMilli` 的三点逐值重合）②跨格货单派生服务需求 → 成交 + 单位运费口径 + 服务货恰减该量 + **唯一 MARKET_TRADE 钱腿 = 运费** + `EnterpriseProfitBook.net == 运费` ③**买不到服务 ⇒ 不成交**（零成交 + 具名 LOGISTICS_CAPACITY + 库房零变动）④工具 0 的跑商家户照样承运 ⑤**预留 = 产业声明投入**（4750，且 ≠ 旧硬编码 1000）⑥无跑商/无跨格需求 ⇒ 既有世界逐值不变 ⑦两跑状态 dump 逐字节相同 | T-H2、T-H3、T-H4、I-H2、I-H5、N-H1、N-H3 |

新增净条数：economy **+8**（迁移 −1/+2 + 新类 7）、util **+6**、app **+3** = **+17**。

## 3. 变异自证（5 例，全部"改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5"）

统一装置：`cp 原件 /tmp/a-tests-mut/` → `md5sum` 记原件 → 变异 → `md5sum` 自证字节不同 → `rm -rf <模块>/target/classes`（防 Maven 增量编译跑旧字节码）→ 跑目标用例 → `cp` 还原 → `md5sum` 与原件逐字节相同 → 重跑目标用例绿。

| # | 变异体（文件 / 改法） | 期望红 | 实测红（用例:行 + 实得） | 原件 md5 → 变异体 md5 → 还原 md5 |
|---|---|---|---|---|
| M1 | `CapacityDemand.workPerGoodPerMille`：`max(1, 基础费×(1000+费率‰))` 改成忽略费率 | 派生需求口径红 | ✅ `derivedServiceQuantity…:93`（期望 1030 实得 1000） | `3b65fdc4…` → `685d64f9…` → `3b65fdc4…` |
| M2 | `MerchantCapacityPool.assemble`：服务预算下界 `max(0, 现货−冻结)` → `max(运力, 现货−冻结)` | 买不到⇒不成交红 | ✅ `noServiceGoodsMeansNoCrossHexFill…:272`（服务运力期望 0 实得 1000） | `562d3387…` → `1311c1f4…` → `562d3387…` |
| M3 | `EconomySeeder.HAUL_TOOL_PER_MILLE_OF_SERVICE`：100 → 50 | 工具单套化口径红 | ✅ `everySeededTradeIndustry…:134`（期望 100 实得 50） | `23da8bcb…` → `52de8f19…` → `23da8bcb…` |
| M4 | `MarketSettlement.necessaryInputsOf`：`entry.getValue() * scale` → `0L`（不按产业声明投入预留） | 预留自动成立红 | ✅ `ownListingsReserveTheDeclaredIndustryInput…:380`（期望 4750 实得 5000） | `d442b3e7…` → `86c062df…` → `d442b3e7…` |
| M5 | `HaulService`：把已退役事件名 `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` 作为字符串加回代码面 | 退役护栏红 | ✅ `retiredParallelMachineFamiliesHaveNoCodeResidue:79`（`MERCHANT_HAUL_TOOL_` 命中） | `8b566e3f…` → `31348de2…` → `8b566e3f…` |

★ 每例还原后目标用例都重跑绿；`git status --porcelain | grep /src/main/` = **空**（全树 main 零改动）。
★ **M2 的额外发现（如实记）**：把服务预算的下界去掉后，**市场层仍被 `deliverHaulService` 的 fail-closed 挡住**（无服务货 ⇒ ERROR + 本笔不成交）⇒ "买不到 ⇒ 不成交"有**两层独立防线**；M2 被第一层（预算=0）的断言杀掉，第二层在同一变异下仍守住了成交。
★ **M3 的边界（如实记）**：它的红来自"标定值口径"（100‰）那条断言；配方侧断言用的是同一常量 ⇒ 自洽（判的是"只有一个真相"，不是"某个数"）。要杀"配方声明与常量漂开"，需另做变异（未做，见 §5）。

## 4. 门禁真数（确切命令 + 耗时）

```
① rm -rf */target/surefire-reports
② tools/mvn-lock.sh clean verify        # 前台，rc=0，BUILD SUCCESS，real 5m13.180s（Finished 17:39:17）
③ node simos-app/src/test/js/run-gate.cjs   # rc=0
```

| 模块 | 类 | 条 | 失败 | 错误 | 跳过 |
|---|---|---|---|---|---|
| simos-util | 26 | 217 | 0 | 0 | 0 |
| simos-map | 39 | 379 | 0 | 0 | 0 |
| simos-calendar | 6 | 33 | 0 | 0 | 0 |
| simos-social-api | 1 | 3 | 0 | 0 | 0 |
| simos-social | 24 | 215 | 0 | 0 | 0 |
| simos-unit | 29 | 518 | 0 | 0 | 0 |
| simos-core | 33 | 231 | 0 | 0 | 0 |
| simos-sd | 25 | 232 | 0 | 0 | 0 |
| simos-actor-api | 1 | 8 | 0 | 0 | 0 |
| simos-actor | 9 | 130 | 0 | 0 | 0 |
| simos-economy-api | 8 | 50 | 0 | 0 | 0 |
| simos-economy | 53 | 288 | 0 | 0 | 0 |
| simos-gov | 8 | 111 | 0 | 0 | 0 |
| simos-army | 9 | 91 | 0 | 0 | 0 |
| simos-app | 135 | 910 | 0 | 0 | 5 |
| **合计** | **406** | **3416** | **0** | **0** | **5** |

- 5 条跳过 = 既有 `RealLlm*` 环境门控（`RealLlmGovScenarioTest` / `RealLlmUnitDecisionLoopTest`）。
- 报告 mtime 落在本轮（新类 `HaulServiceCommodityAcceptanceTest.xml` 17:35:28、`HaulServiceCommoditySeedTest.xml` 17:38:53，本轮 17:34~17:39）。
- **Spotless**：每模块 `0 needs changes to be clean`；**Checkstyle**：`You have 0 Checkstyle violations.`；**SpotBugs**：`BugInstance size is 0` × **15** 个模块。
- **前端门禁**：`# tests 412 / # pass 412 / # fail 0`（rc=0；verify 里那一步同样绿）。
- 本批触碰的类在本轮的真数：`HaulServiceCommodityAcceptanceTest` 7、`HaulServiceCommoditySeedTest` 3、`HaulParallelMachineRetirementGuardTest` 3、`EconomyVocabularyGuardTest` 11、`MerchantCapacityAcceptanceTest` 8、`CapacityTruncationPricingAcceptanceTest` 2、`PortPolicyMerchantLaneAcceptanceTest` 2。

## 5. 没做的 / 没验的（如实）

1. **T-H5（真实 world 360 tick：缺工具事件归零 + 跨格成交对照）未做**。理由：AGENTS §七 2026-10-23 用户裁定"真实世界长跑测试不进程序化门禁"，且本批不许碰 main / 起服务。替代证据只有**结构面**：旧门槛族事件名在 src/main 代码面 0 命中（N-H2 护栏，M5 证明有判别力）。⇒ **T-H5 = 未验**，不是"通过"。
2. **I-H1 货币守恒没有独立的守恒式用例**：本批在正例里断言了钱腿金额/账户增减（买方支出、服务货恰减、承运户收入 = 运费），未写"Σ钱 = 常量"式守恒断言（既有夹具/既有测试覆盖该层）。⇒ 如实记，不冒充"守恒已验"。
3. **N-H3 只做了进程内两跑**（同 JVM、各自新建世界、dump 逐字节相同）。"同 store 落盘 → 重启 → 再跑"未验（economy 夹具不落盘、无 store）。
4. **T-H1 的"读口"未做 GUI 层断言**：`ApiViews:1441` 直接 `put("commodityIds", EconomyVocabulary.allCommodityIds())`（读代码确认），护栏钉住的是那份清单；未起 GUI/前端测试。
5. **N-H1 的对照面是"世界里原有的事实"**（卖方/买方账户 + 逐格价表 + 本轮全部读数），不含"新增跑商家户自己的账目"（它当然会多一行）——判据问的是"跑商的存在有没有扰动别的部分"。
6. **M3 的第二种变异未做**（把 `trade` 的 `cycleInputPerUnit` 写成与单套化常量漂开的数 ⇒ 配方侧断言应红）：只做了常量变异，见 §3 的边界说明。
7. 前端：本批无前端改动，未新增前端用例（门禁 412 条不变）。

## 6. 发现但**未改**的实现缺陷 / 口径差（碰 main ⇒ 只报告）

- **F-1（结构性，建议裁定）**：服务成市格的**运力预算** = 手上 haul 现货（I-H2），但**进池的成员判据**仍是 `MerchantCapacity.capacityMilli() > 0`（劳动 + 工具）—— `MerchantCapacityPool.assemble:387-389`。⇒ 一个"持有服务货、但劳动与工具都为 0"的跑商家户**进不了池**，服务货结构上卖不出去；与设计书 §3.2「运力 = 该产业本周期可产出的服务量」有一处口径差。现实世界不触发（`trade@hex` 产出全归有劳动投入的 operator），但这是服务口径下的新约束。
- **F-2（读数粒度，建议后续）**：`HaulService.NO_SERVICE_SUPPLY_REASON`（"这一格根本没有服务可买"）只进 **DEBUG 日志**；公开读数里它与"运力耗尽/半径外"合并成同一个 `MarketUnfilledReason.LOGISTICS_CAPACITY` ⇒ 判据 §3.3 的"具名归因"在公开面上**区分不出**"没服务卖"与"运力不够"。
- **F-3（口径残留，A3 已知 J-5）**：`MerchantCapacity` 的"劳动 + 工具"算式未动 ⇒ 服务成市格里 `tier`（进而限价序 `askPerMille`）由**劳动+工具**派生，而不是由"实际服务供给量"派生；当服务货量与劳动投入脱钩时，"从最低价起买"的序与真实供给规模无关。

## 7. 落点清单（本批全部改动）

```
改：simos-economy/src/test/…/time/{CapacityTruncationPricingAcceptanceTest,MarketSettlementFixtures,
    MerchantCapacityAcceptanceTest,PortPolicyMerchantLaneAcceptanceTest}.java
改：simos-util/src/test/…/verify/EconomyVocabularyGuardTest.java
新：simos-economy/src/test/…/time/HaulServiceCommodityAcceptanceTest.java（590 行，7 条）
新：simos-util/src/test/…/verify/HaulParallelMachineRetirementGuardTest.java（214 行，3 条）
新：simos-app/src/test/…/world/HaulServiceCommoditySeedTest.java（186 行，3 条）
账本：本文件
```
