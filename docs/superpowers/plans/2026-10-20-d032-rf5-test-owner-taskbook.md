# D-032 RF5 任务书：测试 owner（RF1–RF4b 迁移与验收）

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md` §3 F6
> **状态：** 测试阶段；只改/新增测试文件，不改生产代码、不 commit。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`

## 1. 前置代码态

已完成的实现责任区：

- RF1 家户需求价格接受者 + 同键自动订单替换；
- RF2 生产输入/劳动/口粮；RF2c work-first；RF2e 周期中途补种；
- RF2b 大信封 64MiB；RF2d 自转移关系跳过；
- RF3/RF3b 土地取得者 LAND 占用 + 劳动回退；
- RF4a 土地买盘初始化 + 运力 productive fallback；
- RF4b organizer 迁移临时整源跳过守卫。

主代码 compile 已 rc=0；360 tick 全 OK，但测试未迁移/未跑。

## 2. 测试 owner 任务

### 2.1 第一关：test-compile

先运行：

```bash
tools/mvn-lock.sh -q -DskipTests test-compile -pl simos-app -am
```

迁移由 RF1/RF2/RF3/RF4 引入的 API/语义变化导致的测试编译错误。已知高风险：

- `BuyOrder.maxLandedPrice` `long -> OptionalLong`；
- `MarketIntent`/`LandOrder` 的 BUY 价格/预算字段变化；
- `EconomyPolicyEndToEndTest` 创世 `economyPolicies` 现在含 `SURVIVAL_IDLE_POLICY=0`；
- `HaulServiceCommodityAcceptanceTest` 的 `noServiceGoodsMeansNoCrossHexFill...` 旧断言 `capacity=0`；
- `MarketPayCurrencyAcceptanceTest` / `DebtBasedClearingAcceptanceTest` 订单量/未成交口径；
- `W3WageSettlementTickIntegrationTest` land-based zero-planned organizer fallback；
- `R2SharedAssetScopeTest` 周期中途补种风险。

### 2.2 第二关：核心验收测试

按任务书/父计划的**判据**补/改测试，不得按代码反推改写判据：

1. 需求价格接受者：
   - ask=0 且有 gap ⇒ 仍生成全额 BUY；
   - cash=0/budget=0 ⇒ quantity 不折回；
   - 任意非负价格可成交；现金不足成普通债；无供给 unmet 不记债；
   - M3 同 `(actor, commodity, side)` 只替换同键自动腿。
2. 生产/种子：
   - M6 不因 `drawn=0` 判 NO_INPUT；
   - 扣种保留至少一 tick 口粮；
   - 同 hex 独立家户不共享 grain 种子池；
   - 未入选/无劳动 unit 不扣周期投入；
   - 周期中途首次入选补扣一次，不重复。
3. work-first：
   - 有正收益 work ⇒ grant>0，不 `IDLE_FOREGONE_POSITIVE`；
   - 无正收益 ⇒ idle 可胜出。
4. 自关系：
   - payee==operator ⇒ 不生成 Transfer、不产生自债；不同家户规则不变。
5. 土地：
   - landless/新地主持有 LAND + labor>0 ⇒ D4 allocation >0、M3/M6 有 farm 位置、roster 原 planned=0 不变；
   - RF4a：`hexToIndustries` M3 可用；servicePriced 运力 = max(service, productive)；
   - RF4b：organizer source 迁移整源跳过、无半迁移。
6. 报表字段：
   - `seedInput` 累计/当期口径、`marketUnfilled` 槽位口径、`landDebtTransferred` 数量口径。

### 2.3 第三关：运行顺序

- 先只跑受影响模块/类，确认编译与核心用例；
- 再跑 `tools/mvn-lock.sh -pl simos-economy,simos-app -am test`；
- 不跑 360/`tools/gate.sh`（控制方最后跑）；
- 不启动 GUI/服务。

## 3. 权限边界

- **只允许改/新增测试文件**（`src/test/**`）。
- 不得修改生产代码（`src/main/**`）、POM、配置、主树。
- 若发现生产 bug 导致测试无法按判据通过：**停下报告**，不要改生产代码，不要用放宽断言掩盖。
- 不 commit/push。

## 4. 交账

报告必须包含：

1. `test-compile` 真实 rc 与剩余错误；
2. 改了哪些测试文件、为什么（对应哪条判据）；
3. 实际运行命令、模块/类、测试数、失败/错误/跳过；
4. 哪些旧断言被迁移，哪些新判据未覆盖；
5. **我没做/没验证的**一节；
6. 如发现生产 bug：文件:行 + 失败断言 + 现象，不自行修。

实现账本：`.superpowers/sdd/2026-10-20-d032-rf5-test-owner/impl-ledger.md`。

## 5. 用户原话

```text
需求被允许挂价了吗？家户提的需求，面对市场不管多贵都会直接接受的；其他没啥问题，记录一下你的修复方案，开始吧
```
