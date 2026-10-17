# D-032 最终交付独立代码审核（2026-10-17）

> **结论：发现需修复的问题，暂不认可“无已知缺陷／整体最终验收通过”。** 既有测试通过记录保留；下面的问题由本轮源码核对和最终 shaded JAR 的独立复现确认。本轮不修改生产/测试代码，不提交、合并、推送或 stash。
> **对象**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module` 的当前最终工作副本；不是对主树的审核，也不把整个未提交差异都当作 D-032 新改动。
> **本机证据**：`.superpowers/sdd/2026-10-17-d032-runtime-migration/independent-review/`（Java 复现源、执行脚本、原始日志、返回码、被审源码与 shaded JAR 的 SHA-256）。目录按仓库规则忽略，不进 git；本报告进文档系统。

## R1 [P1] 经济收益排序仍绕过新生产计算，熟练度优势没有进入选择链

**位置**：`simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java:398`、:404；`simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueueBook.java:252`。

productionProspect 自行取资产/劳动 min，再把 recipe.outputPerUnit×scale 当成预计产出；offer 同样直接按旧配方单产×市价算每劳动利润。它们没有走 runtime.probe，也没有读取家户经验、熟练度倍率或 production 的有效产出覆盖。当前 economy/app main 中生产 probe 的调用只在 EconomyEntrySettlement 的进入路径，不覆盖这两条收益排序链。

**触发与影响**：当家户的生产效率因熟练度或 GM 产出覆盖改变时，真实收获变化，但其自动劳动分配/迁移的收益比较仍按旧基准进行。用户希望熟练生产自然取得利润优势，该机制在选择链中失效；也仍有经济侧自行解释生产数值的竞争计算入口。

**实际复现**：对同一方案、同一劳动/资产条件，把家户原始经验配置为使倍率达到 2。调用实际 productionProspect 核心（反射仅用于进入这个私有计算函数，非完整世界模拟）得到预计粮食 100000 毫；调用生产引擎实际 execute 得到 200000 毫。

```text
REVIEW expected-profit production: planned-grain=100000 actual-grain=200000
```

**修复方向**：由 production 的同一计算契约提供可行性、所需投入与预计产出，经济仅估值/分配；补熟练度不同导致排序不同、GM 覆盖同时改变预计与实际、未解锁候选不入选择链的实际判据。

## R2 [P1] 同方案多个实例重复获得整份资产，统一预算存在缺口

**位置**：`simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementIndex.java:362`、:372；`simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionProcessBook.java:54`；实际输入路径 `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:4277`。

资产按 (industry,operator) 汇总，然后同 scope 的每个 unit 都复制整份数量；没有按实例/地点分配占用。正式日循环又逐实例把这个聚合结果传作 assetOccupancy，并把授予规模设成无额外上限。因此一份共享方案在不同地点、同经营者各建实例后，一份土地/工具会被多个实例分别完整当成“实占”。

**实际复现**：同一共享方案、同一经营者的两个实例分别位于 0_0 与 3_4，只有一条 LAND=100 的 OwnershipStake；ProductionProcessBook 给两个实例各返回 LAND=100，合计 200。该数值随后沿上述正式驱动路径进入生产，没有共享资产占用扣减点。

```text
REVIEW shared asset: owned=100 allocated-to-two-units=200
```

**现有测试为何没挡住**：`simos-economy/src/test/java/io/mosire/simos/economy/time/UnifiedBudgetNoDoubleBookingTest.java:121` 的资产场景只比较**不同方案 ID**；依靠 industry 不等就返回 0，没有覆盖同一方案的多个实例。

**修复方向**：保留资产实物总账，明确实例/地点的实际占用与统一预算；同一份资产的并发占用总和不得超过实有数量。补共享方案、同经营者、多实例/多地点场景，不能只补不同方案的排他检查。

## R3 [P1] 实际执行绕过解锁和可用性条件，试算拒绝后仍能生产

**位置**：`simos-production/src/main/java/io/mosire/simos/production/ProductionRuntimeEngine.java:730`、:786（execute）；条件仅在 :452/:458 的 probe 路径检查。正式日循环 `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:4279` 固定传 ExternalAttributeFacts.EMPTY。

execute 没有检查方案解锁或 availability，也不读取 request.attributes；它找到实例/方案后即累计投入、经验与进度。调用者可以对 probe 明确拒绝的同一方案直接执行成功；既有实例的正常日循环也没有另一道外部属性校验。

**实际复现**：

- 经验不足、门槛 1000 的方案：probe 返回 locked，同样的实例 execute 成功，产粮 100000 毫。
- 要求 literacy≥500、属性事实缺失的方案：probe 返回 external-attribute-missing:literacy，execute 仍产粮 100000 毫。

```text
REVIEW missing external attribute: probe=condition-unsatisfied:external-attribute-missing:literacy execute-grain=100000
```

**修复方向**：在权威实际生产入口校验本次使用资格及必要条件，明确由哪个家户满足哪些条件；不得把无副作用 probe 当成可跳过的唯一守卫，也不能用空属性绕过约束。普通拒绝须零实例/进度/经验/消耗残留；补直接执行与真实日循环的负向判据。

## R4 [P1] 零经验迁入者不参与合户平均，目标经验被高估

**位置**：`simos-production/src/main/java/io/mosire/simos/production/ProductionData.java:438`、:447；正式迁移调用在 `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java:651`。

mergeExperienceInto 只枚举来源家户已有经验条，并跳过全零来源特征。因此目标有、来源没有或为零的经验条完全不更新；虽然迁入人口增加了，目标经验却不被零经验人口稀释。

**实际复现**：目标 10 人、经验 100，来源迁入 10 人、该条经验为 0（缺行）。应为 (10×100+10×0)/20=50，实际仍为 100。显式全零来源特征也会命中跳过分支。

```text
REVIEW merge zero-experience source: expected=50 actual=100
```

**修复方向**：对来源和目标经验条的并集逐条合算，缺项作为 0 参与人口加权；保留完整定点精度与已定永久解锁规则。补目标独有经验条、来源独有条、全零行和多次迁入场景。

## R5 [P2] 原始经验公式未冻结，同 tick 产出依赖实例执行顺序

**位置**：`simos-production/src/main/java/io/mosire/simos/production/ProductionRuntimeEngine.java:1299`；原始经验作为合法公式输入声明在 `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionExperienceUse.java:55`。

开启 tick 后只有 mastery 来自冻结条件，primaryExperience 仍读 current.experienceOf。执行第一个实例会增长 current 中经验，第二个实例的公式若引用 primary_experience，就吃到同一天刚增长的经验，违反同 tick 生效条件统一。

**实际复现**：公式 E=1000+primary_experience，同一户两个实例各出工 100、增长率 500。正序实例产出规模 U1=100/U2=105；倒序 U1=105/U2=100。现有顺序无关测试只引用 primary_mastery，未覆盖合法的原始经验公式输入。生产实例若使用不同商品、分成关系或价格，会影响相应收益分布。

```text
REVIEW raw-experience order: forward U1=100, U2=105; reverse U1=105, U2=100
```

**修复方向**：冻结所有影响当 tick 生产公式的经验输入，或统一从该 tick 生效快照解析；试算也应使用同一口径，不只冻结熟练度一个字段。

## 复核方式、真实输出与局限

工作树根下运行：

```bash
bash .superpowers/sdd/2026-10-17-d032-runtime-migration/independent-review/run-repro.sh
```

执行脚本以**最终 shaded JAR + 已编译的 production 夹具类**为 classpath，仅在证据目录编译独立 ReviewProbe.java；不重编业务、不改 src/main/src/test、不写业务状态。脚本及 Java 源完整保留，不依赖 /tmp 临时路径。执行 rc=0 表示复现程序正常运行，**不表示上述业务预期通过**；原始异常数值在 repro.log，命令/返回码在 repro-exit.json。

报告中的 R1 是实际预期生产计算核心与实际执行的直接对照，R2 是实际资产聚合函数与正式调用路径核对，R3–R5 为引擎/状态 API 直接行为复现。**未将这些写成全世界端到端模拟已跑过。**

另核对现有 surefire XML，结果与终稿声明一致：production 70/0F/0E/0S、economy 387/0F/0E/0S、app 1012/0F/0E/7S。生成命令：

```python
from pathlib import Path
import xml.etree.ElementTree as E
for name in ['simos-production', 'simos-economy', 'simos-app']:
    sums = dict(tests=0, failures=0, errors=0, skipped=0)
    for p in (Path(name) / 'target/surefire-reports').glob('TEST-*.xml'):
        t = E.parse(p).getroot()
        for k in sums:
            sums[k] += int(t.attrib.get(k, 0))
    print(name, sums)
```

本轮未重跑 clean verify、前端门禁、SpotBugs、变异或完整数值模拟；既有绿数不能覆盖本轮反例。GM 写入口等被原终稿列作后续的能力也不在本轮声称已验证的范围。没有提交/同步、没有顺手清理代码；优先修复以上问题，再由独立测试 owner 补有判别力的回归和所需门禁。
