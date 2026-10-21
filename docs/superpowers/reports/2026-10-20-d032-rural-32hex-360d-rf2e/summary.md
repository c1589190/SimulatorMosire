# D-032 32hex / 360 tick：RF1–RF2e 中间修复结果

> 状态：中间修复跑，不是最终验收。全部 360 tick `status=OK`，无 `ADVANCE_EXCEPTION`。
> 代码态：RF1 需求价格接受者 + RF2 生产输入/劳动/口粮 + RF2b 大信封 + RF2c work-first + RF2d 自规则 + RF2e 周期中途补种。
> 未跑单测/`clean verify`/`tools/gate.sh`；未 commit。

## 1. 运行

- 命令：`SIMOS_MULTITICK_SKIP_BUILD=1 JAVA_OPTS='-Dsimos.economy.logLevel=DEBUG' tools/multitick-sim.sh rural-32hex-360d`
- `MULTITICK_SIM_RESULT=PASS cases=1 failed=0`；360/360 `OK`；167.5s。
- CSV sha256：`d917315f673f8987f940d738304c72d001a6c0531f0868b7179038d53264a386`
- MD sha256：`581fa0ee4b28ac6b1ac932026b076e89d2b2f4abd069ef02e6eaac45e5755b62`
- 完整 DEBUG 日志（gitignored）：
  `.superpowers/sdd/2026-10-20-d032-rf2e-midcycle-sow/evidence/360-after-rf2e.log`

## 2. 关键结果

| 指标 | 修复前 12/48 | RF2e 后 |
|---|---:|---:|
| 360 tick status | 360 OK，但产出 0 | **360 OK，无异常** |
| `productionGrainMilli` | 0 | **2,271,648,000** |
| 有产出的 tick | 无 | **120 / 240 / 360** |
| tick120 grain | 0 | **526,224,000** |
| tick240 grain | 0 | **822,864,000** |
| tick360 grain | 0 | **922,560,000** |
| 人口 | 7,101 → 5,347 | **7,101 → 6,414** |
| 出生 / 死亡 | 398 / 2,151 | **435 / 1,121** |
| 结束粮食库存 | 41,973,998 | **1,477,644,187** |
| 总种子投入 | 837,529,358（指标口径） | **2,224,692,000**（含多个周期/补种） |
| 总市场成交笔数（逐轮相加） | 658 | **6,408** |
| 结束债务本金 | 1,325,756 | **3,845,382** |
| 结束合同数 | 2,299 | **3,625** |
| 土地成交 | 0 | **0** |
| 结束地价 | 0 | **0** |
| `PRODUCTION_REJECTED` | 32/tick 起 | **46/tick 起** |

## 3. 已修复

1. **家户需求价格接受者**：商品/土地 BUY 不再按 ask/现金/预算截断；ask=0 也挂全额需求；现金不足走普通债路径。
2. **M6/M7 劳动/投入时序**：farm 不再因 `drawn=0` 被判 `NO_INPUT`；工作优先（work-first）后 day1 farm 有正 `granted`。
3. **种子/口粮**：整周期计划不再清零户粮；每户扣种保留一 tick 口粮；同 hex 独立家户不再共享 grain 种子池。
4. **周期中途首次入选**：`progressDays>0` 但 `cycleInputUsedMilli` 为空的 unit，在首次实际入选时补扣一次种子，不再永久空转。
5. **自转移关系**：operator 与 payee 同一家户的关系规则跳过，不再触发 `Transfer 两端不得相等`。
6. **大状态信封**：Core 模块载荷字符串读取上限 64MiB，360 tick 合法状态不再被 20MB 默认上限误拒。

## 4. 仍未解决

- **人口仍净减**：1,121 死亡（其中仍有饥荒），人口 6,414；需要继续查土地/劳动/市场分配。
- **土地成交仍 0，地价仍 0**：`ctx.hexToIndustries` M3 未初始化、无地户买盘与 E9 卖地语义未修。
- **`PRODUCTION_REJECTED=46/tick`**：仍有一批 farm 因 LAND scope/组织未重排被拒。
- **债务仍增长**：结束 3,845,382；主要是市场赊购/货币市场贷款，工资债不是主因。
- **市场未满足**仍高：44,062 累计槽位；跨 hex 运力仍为 0。
- **未做**：F3 土地取得后的 unit/roster 组织、F4 实际盈余卖盘/土地买盘/运力、F5 债务分源、测试 owner、`clean verify`、`tools/gate.sh`。

## 5. 结论

RF1–RF2e 已经把“农业全程 0 产出”和“day120 自转移异常”两个直接断点修掉：
**360 tick 全 OK，三个周期都有粮食产出，人口下降从 2,151 降到 1,121 死亡。**
但这不是最终经济验收：土地市场、跨 hex 运力、land→unit 组织、债务和人口仍需要后续责任区。
