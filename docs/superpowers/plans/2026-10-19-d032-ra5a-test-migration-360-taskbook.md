# D-032 RA5a 测试迁移与 360 tick 交付 · 测试 Owner 任务书（2026-10-19）

> **状态：可派单。** 生产代码层 RA1a/RA1b1/RA1b2a/RA1b2b/RA1b2c1/RA1b2c2 已完成主代码编译；现在进入测试 owner。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；只动本树，不碰主树。
> **测试 owner 只改 `simos-*/src/test/**` 与测试夹具；不改生产代码、POM/XML/tools/docs/seeder。发现生产缺陷先报告，不自行修。**

## 1. 前置

- 设计：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1、§4.1、§4.6、§5、§8、§10、§11。
- 复核：`docs/superpowers/reports/2026-10-19-d032-standard-agriculture-v2-review-closeout.md`。
- 本轮前序台账：
  - `.superpowers/sdd/2026-10-19-d032-ra1a-scalar-land/impl-ledger.md`
  - `.superpowers/sdd/2026-10-19-d032-ra1b1-land-state-components/impl-ledger.md`
  - `.superpowers/sdd/2026-10-19-d032-ra1b2a-land-runtime-seam/impl-ledger.md`
  - `.superpowers/sdd/2026-10-19-d032-ra1b2b-scalar-land-source-cutover/impl-ledger.md`
  - `.superpowers/sdd/2026-10-19-d032-ra1b2c1-scalar-land-debt/impl-ledger.md`
  - `.superpowers/sdd/2026-10-19-d032-ra1b2c2-scalar-land-market/impl-ledger.md`
- 当前已知首错：`EconomyRoundTripTest.java:1294` 仍用旧 `EconomyData` 36→? 构造参数；RA1b1 后组件数应为 **36**（meta + 35 领域组件）。
- 旧 LAND `AssetShareId` 测试夹具、整块订单/成交/债务、`GrainFarmUnit.shareNode()`、34/36 组件计数等会批量失效；按“新架构新测试”迁移，禁止为迁就实现放宽断言。

## 2. 任务

### 2.1 测试编译迁移

1. 先运行：
   ```bash
   cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
   tools/mvn-lock.sh -q -DskipTests test-compile -pl simos-app -am
   ```
   逐个修复失败点（只改测试）。
2. 原则：
   - LAND 只从 `EconomyData.landArableSupply()` / `landHoldings()` 读；不再构造 `assetShares` LAND；
   - `EconomyData` / `EconomyChangeSet` 显式构造器加两个 land 参数；
   - 组件数 36；
   - LAND 市场订单/成交按 `(hex, quantityMilliMu)`，不再有 `shareId`/整块；
   - 债务抵地按 `LandHoldingKey`/数量；
   - 本轮取得用 `landAcquiredQuantityThisRound` 数量 map；
   - `LandHolding` 0 行非法；旧 0 share 夹具改成“无持有”；
   - 旧整块/旧命令/旧字段的断言按新架构删除或重写，保留判据的语义强度。
3. 不修改生产代码；若需要生产改动才能编译/行为正确，具名报 BLOCKED/缺陷，附最小复现命令与 `file:line`。

### 2.2 全量测试与修复分类

测试编译通过后：

```bash
tools/mvn-lock.sh -pl simos-app -am test
```

- 逐类处理失败：
  - 旧架构夹具/断言 → 迁移；
  - 测试夹具自身违反新不变量 → 修夹具，不放宽生产守卫；
  - 真实生产缺陷 → 不修生产代码，报告 `主题 + file:line + 复现命令 + 期望/实际 + 最小修复建议`。
- 记录真实的 surefire 数字；不要用 `-q` 吞汇总而读错 rc。测试数量/跳过数必须来自本轮报告。
- 若测试规模大，可分批运行受影响模块，但最终至少完成 `simos-app -am test`。

### 2.3 360 tick 数据表

- 先更新 `simos-app/src/test/java/io/mosire/simos/app/sim/Rural32Hex360DaySim.java` 中 LAND 读法：
  - `landTotalMilliMu` 从 `EconomyData.landHoldings()` 求和；
  - `landDebtTransferredBlocks`/`landTradedMilliMu` 等列按新标量日志（`quantityMilliMu`）重算/改名；
  - `landOwnership` 改按 `(hex, owner, quantity)`，不再遍历 LAND `assetShares`。
- 360 运行：
  ```bash
  SIMOS_MULTITICK_SKIP_BUILD=1 JAVA_OPTS='-Dsimos.economy.logLevel=DEBUG' \
    tools/multitick-sim.sh rural-32hex-360d
  ```
- 交付物：
  - `rural-32hex-360d.csv`（表头 + 360 数据行；固定列序）
  - `rural-32hex-360d.md`（与 CSV 同内容）
  - `SHA256SUMS`
  - 记录 rc、`MULTITICK_SIM_RESULT`、status 计数、D032_TICK_CHAIN/D032_HOUSEHOLD_CHAIN 条数、已知业务拒绝事件。
- 用户要求：控制方/测试 owner **只交表与路径，不自行解释经济数值结论**。
- 若 360 失败：保留失败 tick 与异常原文，不伪造 360 OK；分类是测试读口、生产代码缺陷还是环境/门禁问题。生产缺陷交控制方再派 writer。

## 3. 禁止

- 不改生产代码（`src/main/**`）、POM/XML、tools、docs、Seeder；
- 不 commit/push/merge/stash/checkout；不碰主树；
- 不通过放宽断言、删除失败用例、`@Disabled` 解决失败；
- 不把未跑写成通过；不解释 360 表格业务结论；
- 当前工作树有前序未提交生产改动，发现别人的中间态先报告，不接手。

## 4. 交账

写 `.superpowers/sdd/2026-10-19-d032-ra5a-test-migration-360/impl-ledger.md`，并报告：

1. 改动测试文件；
2. `test-compile`/`test` 真实命令、rc、surefire 数字；
3. 删/改/新增的判据与原因；
4. 生产缺陷/blocker（若存在）的最小复现；
5. 360 tick 交付路径、行数、status 统计、hash；
6. **未做/未验证项**。

## 5. 用户原话附录（逐字）

```text
我原本想加一个自增量的农业进度系统，不过想了想还是算了吧，如果单日对于单位土地劳动效率没有满，那就把单日的效率提供乘到最终这个单hex收获的总效率上
目前种田为了方便测试，可以只需要种子粮、口粮和地
测试也不要写，写完业务代码统一写
继续吧，没有遇到需要裁定的就继续干，如果能顺畅干到最后，就再跑一轮360tick
```
