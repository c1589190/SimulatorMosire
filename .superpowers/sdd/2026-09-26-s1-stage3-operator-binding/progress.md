# SDD ledger — plan: docs/superpowers/plans/2026-09-26-s1-stage3-operator-binding.md

Spec: docs/superpowers/specs/2026-09-26-s1-actor-property-design.md（含 2026-09-26 追加-1..4）
Breakdown: docs/superpowers/plans/2026-09-26-s1-stage-breakdown.md §三 阶段 3
★ 执行方式（用户 2026-09-26 裁定）：**不派独立评审 Agent、不跑修复环**；
验收 = 计划里那套判据（I3.1/I3.2/I3.3），在**阶段边界**由控制方自己核。实现仍派子 Agent。
★ 用户总指令：「一口气干完跑模拟，模拟结果出来你自己先检查，有问题自己改，有问题需要裁决再找我」

## 控制方裁定（派活前已写进计划）

D1 缺省只在载荷边缘 / D2 未知 regime fail-closed / D3 默认 operator 用产业 id /
D4 新档字面量 `tenant` / D5 不加两条守卫 / D6 改名推后阶段 4 / D7 `feudal` 两义就地消除

## 任务清单

- [x] T1 `RegimeOperators` 四档推导表 + 未知档 fail-closed
- [ ] T2 `Industry` 加 `operator` + 25 处构造点 + 载荷边缘缺省（★ 关账点 `clean verify`）
- [ ] T3 透传与「不丢失」：一日结算 / 变更集 / codec 三面
- [ ] T4 `ApiViews` 读口 + `CatalogTool` 提示
- [ ] T5 `S1Stage3TenancyTest`：租佃档 + `AssetOwner ≠ Operator`（I3.2）
- [ ] T6 逐条读数 + 关账

## 执行记录

- **T1: 完成**（`3e54a62` + `8309c72`）—— 四档 `feudal→ESTATE:<industryId>` / `household→HOUSEHOLD:` /
  `handicraft→WORKSHOP:` / `tenant→HOUSEHOLD:`；未知档抛，消息列四档。
  `RegimeOperatorsTest` 3/3（RED **当场捕获** = 编译错）；`-pl simos-economy,simos-app -am verify` 12/12、
  2431 / 0 失败；105 份 surefire 报告**陈旧 0**。两个变异体都红在预期处。
  ★ D7 三处注释已改，`git show -U0` 逐行核过**无代码改动**。
- **Ruling（D8 —— T1 报的 D7 范围不足）**：D7 只点名 3 处，实测另有 5 处同词残留。裁定：
  - ★ **`EconomySeeder.java:662` 要修，并进 T2** —— 它写「制度 = 封建租佃」而 `agriculture()` 用的是 `REGIME_FEUDAL`，
    **与同文件里刚改好的常量注直接矛盾**。这不是扩范围，是**把没做完的修正做完**。
  - ★ **`RegimeId` 的类注要修**（还写着「四档」、还含 `capitalist` —— 而 `RegimeId` **根本没有词表**，
    这是个**假声明**）⇒ 并进 T2，一行。
  - **其余 3 处不动**（`EconomyPayloads:262` / `EconomySettlement:1330` / `EconomySeedHandlerTest:59`
    描述的是 `Split` 的覆盖面，没说错话）。
  - **`AllocationRule` 的标签不动** —— 它只描述**规则形状**、没断言 tenant 档默认规则就是 Split，
    而 spec §六 明文参数值「未做」⇒ **不动才是对的**。
  —— **代价**：低（共两行注释）。

- **T2: 完成**（`24439e1`，18 文件 / +241−30）—— ★ **阶段 3 的关账点过了**。
  全仓 `clean verify` **13/13 SUCCESS**（3m30s）、**2460 条 / 0 失败**、SpotBugs `BugInstance size is 0` ×12、
  Spotless 12 模块 `0 needs changes`、**surefire 280 份陈旧 0**、前端门禁 297/297。
  ★★ **25 处构造点逐条核查、分类零偏差**（派生 19 / 重建透传 4 / 必须显式 1 / 载荷边缘缺省 1）；
  `EconomyTestWorld` 的 8 参签名与 8 个调用点**零改动**（实测三档 regime 全部已登记）。
  ★ 附带实测：**真播种器劳动侧写的 actor 种类与推导表三档逐一相同** ⇒ D3 的"同字面"是**实测**成立、非推演。
  ★ D8 两处注释已改（`EconomySeeder:662` + `RegimeId` 类注）。
- **★★ Ruling（D9 —— T3 是 I3.1「不丢失」唯一的守门人）**：T2 报——结算夹具全用**派生值** ⇒
  "把 `withCycleState` 改成**重新推导**"这种变异体**在 T2 会存活**（它只用 `null` 变异体证明了那条路径**被走到**）。
  ⇒ **T3 的派活必须含两条**：①**非默认 operator 走完一日结算仍是它** ②★ **补一个"在 `withCycleState` 里重新推导"的变异体**，
  去**证明**①真的接得住 —— 不能只是"我写了条测试"。
  —— **为什么**：这是 **I3.1「不丢失」的唯一守门人**；派生值夹具会让"operator 是标签"以**最隐蔽的形式**复活。
  —— **代价若漏**：阶段 4 产出归属会落到**推导出来的**主体上而不是真实主体 —— 那是"佃农的地租给了庄园主"。
- **Ruling（D10 —— 不加旧归档夹具）**：T2 报"D1『旧归档打不开』这条代价只有注释陈述、无测试钉住"。
  ⇒ **不加**。依据：spec §十.4 **已裁定**「旧档重建也没关系、**不做迁移工具**」——
  给一个**我们故意不支持的行为**写夹具，等于把"不支持"固化成契约。
  该行为的两半**各自已有测试**（载荷缺键 ⇒ 走推导；`Industry` null ⇒ 抛）。
  —— **代价**：旧归档失败的方式（报什么错）不被钉住 —— **已接受的后果**，记为已知。

- **T3: 完成**（`35d53bf`，3 文件 / +176−5，**生产代码零改动**）—— `-pl simos-economy -am verify` 6/6、
  **136 条 / 0 失败**、Spotless 0、SpotBugs 0、12 份 surefire 报告全刷新。
  ★★ **D9-1 落地**：`anExplicitOperatorSurvivesADayOfSettlement` —— 夹具 `feudal` + `HOUSEHOLD:house-7`
  （推导值是 `ESTATE:farm@0_0`，**种类与 id 都不同**），跑满一个周期（覆盖 `withCycleState` **两个**调用点、三种形状）后断言仍是它。
  ★★ **D9-2 实测成立**：`withCycleState` 改成 `defaultOperator(regime, id)` ⇒ **红在断言**
  （`expected HOUSEHOLD:house-7 but was ESTATE:farm@0_0`），且该变异体下**全模块 136 条只有这一条红**
  ⇒ **T2 的派生值夹具确实接不住**。
  ★★★ **本阶段最干净的一条原则演示**：codec 面用**派生值**夹具时，"解码时重推 operator"的变异体
  **整个类 14/14 全绿（存活）**；把夹具换成非默认值后 **5 条一起接住**
  ⇒ **判别力来自夹具，不来自断言。** 值得记进 `AGENT.md` 的失败形态。
- **Ruling（D11 —— T3 唯一一处偏离 brief 字面）**：brief Step 4 的期望 `ESTATE:farm@0_0` 与它指名的 codec 夹具
  （`tenant`、id `farm` ⇒ 推导值 `HOUSEHOLD:farm`）**对不上**（brief 自相矛盾）。
  实现者**没有**把**期望值**改成派生值（那正是 D9 要防的坑），而是把**夹具**改成非默认值使该行**逐字成立**，
  并**当场捕获了 RED**。⇒ **裁定：正确。** 代价：无。
- **Ruling（D12）**：其余大量夹具**仍是派生值、未扩大范围** ⇒ **接受**（守门测试已覆盖该性质；
  那些夹具对**别的**性质——往返保真——仍有判别力）。记为已知边界。
- ★ **坑记录（三条，都值得进 AGENT.md）**：①变异体自己先红在 checkstyle；②M3 红在**夹具构造期**；
  ③M4 第一版 `asText()` 打在 `{"value":…}` 上 ⇒ 造出**"空转变异体"（绿得骗人）**。
