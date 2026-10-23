# D-031 P1 工作树进度（2026-10-16，按用户要求收尾保存）

> **状态：历史快照（文档系统重写于 2026-10-23）** · 当前实时状态以 [`docs/agents/STATUS.md`](../../agents/STATUS.md) 为准；本文只作当时事实留痕，勿据此开工。

> ★★ **2026-10-16 22:12 更新：P1 在侧线已收口（实现 + V1–V7 + 完整 `clean verify` 全过）。当前状态与恢复入口见文末「P1 收口」一节；本文其余章节是过程留痕。仍未提交、未同步主树。**

---

## ★ P1 收口（2026-10-16 22:12 +08:00，控制方复核后落盘）

**结论**：侧线 `parallel/side-module` 上，D-031 P1（定义迁移 + V1–V7 验收）**已满足设计书 §5 的全部判据**，并且完整 `clean verify` **rc=0**。**未提交、未同步主树、未动 `stash@{0}`**。

### 收口数字（全部可复核）

| 项 | 读数 | 证据（本机留痕，`.superpowers/**` 不进 git） |
|---|---|---|
| 完整门禁 | `tools/mvn-lock.sh -pl simos-app -am clean verify` → **rc=0 / BUILD SUCCESS**，06:49，**根 + 18 模块全 SUCCESS、零 FAILURE/零 SKIPPED** | `test/logs/s4-clean-verify-2.log` |
| 测试 | **3473 / 0 / 0 / 7**（app 1014、economy 394、production 31；18 模块全执行） | 同上 + `test/collect-verify-readings.py` |
| SpotBugs | 18 份 `spotbugsXml.xml`，`<BugInstance` **0** 个 | 同上（`grep -ho "<BugInstance" simos-*/target/spotbugsXml.xml \| wc -l` → 0） |
| 前端 | `[frontend-gate] OK tests=412 pass=412 fail=0` | 同上（日志第 340381 行） |
| shade | `[shaded-guard] 结论：OK（8087 个条目；7 条运行期类 + Main-Class 全在）` | 同上 |
| 格式 | `spotless:check` 根+18 模块全过（清缓存口径 1851 文件逐个真验） | `impl/format-fix-1.md` |
| 零残留 | 141 项生产快照 SHA-256 **不符 0**（基准 `evidence/production-manifest-post-dls-fix.json`） | `test/verify-production-manifest.py` |
| 7 条 skipped | 全为 `SIMOS_REAL_LLM` 未设的真 LLM 门控（`RealLlmGovScenarioTest`×3 + `RealLlmUnitDecisionLoopTest`×4）⇒ **未运行、不计通过** | 同上 |

### V1–V7 对照（判据 → 证据）

| 判据 | 结果 | 证据 |
|---|---|---|
| V1 非空状态/往返/回放 | app 层：真实新世界非空、一键批**恰一条 revision**、四组件全非 Unchanged 真 delta 往返+逐字段重建、**重放后推一天逐字段一致** | `test/logs/s1-v1-run-3.log`、`D031P1AppDefinitionRoundTripTest` |
| **V2 P0/P1 黄金对照** | **共同叶子读数 32857 / 单边键 0-0 / 逐值差异 0**（控制方独立重跑通过）；只忽略 4 类：`tree`、`skill`（P0 无对照 45 条）、随机 UUID、P1 多一条 `production.ClearRegionDefinitions`（摘除前先正向断言前提成立） | `test/compare-golden.py` + `simos-app/target/golden-v2/{p1,p0}.json`、`s2-compare-final-v2.log` |
| V3 权威切换 7→11 | 真实结算与预期利润**同读新定义且是具体非零数**（33,000/22,000/55,000，读 7 对照 21,000/14,000/35,000）；被引用再改具名拒；同值重放空变更集；新版本不改旧实例 | `test/logs/s1-v3-run-2.log`、`D031P1AuthoritySwitchTest` |
| V4 独立手算 | 8 项手算落值（毛产 70,000 / 损耗 2,100 / 净产 67,900；实物 500 欠 0 vs 货币 400 欠 300；铸币量纲不 ×1000） | `test/logs/s1-v4-run-2.log`、`D031P1HandCalcSettlementTest` |
| V5 负向收口 | 5 组独立断言（旧表消失/import 边界/fail-closed/跨片具名拒/技能缺可见）+ **`planOnly` 零写**（head、`revisions` 行数、全切片逐值不变）+ **region clear/seed 在真 `CoreSimos.submitBatch` 上整批回滚**（含正对照） | `test/logs/s1-v5*.log`、`s1b-planonly-run-2.log` |
| V6 变异自证 | 四条（读过期定义 / 丢产出字段 / 绕过引用拒绝 / fail-closed 消失）**全部当场业务断言真红、无编译错、还原后 MD5 逐字节一致**；收口 141 项零不符 + 回到绿 | `test/mutation-evidence-s3.md`、`test/logs/s3-*.log` |
| V7 独立长模拟 | 两侧各 **125 天 / 168 次真实关账**、**货币守恒逐日成立**、非负、**无劳动或缺原料不得关账**；对拍 4135 项读数 0 差异 | `test/logs/s2-v7-*.log`、`s2-compare-final-v7.log` |

### 首跑唯一阻断点与修复

`clean verify` 首跑 rc=1，唯一原因：`simos-economy/.../spi/EconomyPayloads.java:318` 的 `DLS_DEAD_LOCAL_STORE`（P1 把 modes 权威搬去 production 后留下的死绑定）。修复：`:318` 的绑定改为 `:324` 的 `parseModes(payload);` —— **保留调用**（该键仍由 `EconomySeeder` 的 `economy.Seed` 载荷发出，解析的形状校验经 `EconomySeedHandler` 折成 `Rejected`，删掉会改变拒绝语义），只去掉无人读取的局部；并做了"故意还原缺陷 ⇒ 原文逐字红 ⇒ 还原"自证。修复后 `clean verify` rc=0。

### 本轮新增的机械纪律（都是实测踩出来的，供后续会话直接用）

1. **`-fae` 列不全 Spotless 违规**：全 reactor 依赖 `simos-util`，它一红下游全 SKIP ⇒ 一次列全用 **`-fn`（纯诊断，rc 不当门禁）**，或逐模块 `-pl`。
2. **Spotless 的 ERROR 会截断违规清单**（`Violations also present in N other files`）⇒ **认 `N needs changes to be clean` 计数器，别数列出的文件名**。
3. **`upToDateChecking` 缓存会跳过文件** ⇒ 要可信 rc=0，先删 `target/spotless-index` 跑全量。
4. **`cp -p` 还原会把 mtime 一起还原** ⇒ Maven 增量编译跳过重编、**变异期字节码留在 `target/classes`**。**"源码 MD5 一致"≠"编译产物干净"**：还原后要么 `touch`、要么用 `clean` 收口。
5. **本树里直接目标调用 `-pl <模块> -am <goal>`（如 `spotbugs:check`）会因 reactor 构件不登记而 `Could not find artifact …:simos-util:jar`**（rc=1 但不是门禁红）；绑在生命周期上的门禁走 **`verify`**。不要用 `install` 灌仓库去"修好"它 —— 那会破坏"不带 `-am` 会响亮失败"的安全属性。
6. **`clean verify` 中途失败时，下游模块的 surefire 报告是上一轮残留** ⇒ 必须区分"**本次真跑到**"与"磁盘全量累加"，后者会把 13 模块 2266 冒称成 18 模块 3473。

### 本批外发现（已单独立耐久留痕）

`simos.region.clearData` 对标准小世界的 `small-world` 区**恒不可清**（拒因 `social.ClearRegion`：`家户 hh-unit:gov-central 的成员批次不在 groups 里`）。**已用 P0 基线同场景对拍定性为既有缺陷、非 P1 回归**（P0 拒因逐字相同）。详见 `.agents/notes/proposed/architecture/2026-10-16-social-clearregion-gov-household-batch-gap.md`。

### 明确未做（不要把上面数字读成已入账）

- **未提交、未同步主树、未跑主树 `tools/gate.sh`**；`stash@{0}`（W/X 在飞改动）未动；`ga-opt` 未动。
- **7 条真 LLM 门控用例未执行**（`SIMOS_REAL_LLM=1` 未设）⇒ 跳过不计通过。
- **P2 未开始**：文化真实效果（预期利润与实际生产同权威同修正语义）、科技升级/传播、共享流动表落账。
- 未做产物分发/换机验证；`test-world/` 未动。
- V2 对拍的是**持久状态投影**；结算期瞬态（投入/产出明细、拒绝条目、停产原因）无持久权威，已记 `unavailableReadings` 并用状态等价读数替代。

### 下一步（需用户授权才动）

1. 主树集成：把侧线 P1 差异同步到主树（写者停手、主树当刻现场核对、保留他人改动），再跑主树 `tools/gate.sh full`；**提交与推送需用户明确授权**。
2. 之后才谈 P2（文化真实效果）与「既有 `social.ClearRegion` × gov 家户批次」那条外批缺陷的归属派单。

---

> **最新：本轮回归 3422/0/0/7、前端 412/412；clean verify 在 util 的 Spotless 格式检查处失败，迁移合理性验收仍未完成。按用户要求收尾保存。** 以下早期章节保留为阶段记录，当前结果及恢复顺序以文末“本轮收尾”一节为准。本批全部在侧线，未向主树同步、未暂存、未提交、未推送，其他批次 stash 未操作。

## 现场与任务

- 实现工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`，分支 `parallel/side-module`，HEAD `03faf0a3` 加未提交P0/P1差异。
- 冻结P0对照：兄弟工作树 `d031-p1-baseline`（detached同HEAD，加124个P0变动文件）。不改其业务源，只允许最终测试阶段加入隔离临时导出器。
- 主树仍保留已同步的P0，**没有P1代码**；`ga-opt`不碰。主树唯一入账/提交口不变，本轮测试在侧线进行，不冒称主树gate已过。
- 用户授权：继续P1迁移和合理性验收，明确使用无头`dsh`且放worktree。用户追加纠正：production自己维护领域工具，app只装配；原话见设计§7–9.2。
- 约束：`docs/superpowers/specs/2026-10-16-d031-p1-definition-migration-design.md`，执行计划为plans下同主题文件。P2文化真实效果、共享流动表仍不实施。

## 生产代码事实（已回源码核对，尚未行为验收）

- 定义/技能完整状态权威转到production；economy删除产业/模式持久表与旧定义适配器，仅保留本域实例/劳动/财债和只读contract来源。
- 定义命令、GM产业编辑窄工具、定义注册投影归production；production新增ga-spi工具契约依赖（用户纠正授权的精确允许面），app只装配。
- 两日循环按需创建trade/entry产业的候选定义，交production `DefinitionRegistration`投影并随实例原子提交。
- util增加`CandidateStateValidator`，Core仅调度普通命令/批最终态/restore/Advance/创世的落库前校验；economy实现跨片引用判定。批内可前向声明，最终候选态须闭环；批拒因返回调用者，装配故障ERROR。
- P0实际劳动只读能力保留：economy的`EconomyProductionLaborLookup`及app纯装配入口；文化真实循环继续空注入。
- 创世与region/worldgen种子、清空、政府产业、GUI/危机/读数等主代码消费者已迁移；实际是否全部正确仍由测试验收。

真实编译命令（侧线根）：

```bash
tools/mvn-lock.sh -DskipTests compile -pl simos-app -am
```

原始日志本机留痕：`.superpowers/sdd/2026-10-16-d031-p1-definition-migration/impl/compile.log`，结尾`BUILD SUCCESS`、`rc=0`，2026-10-16 19:29:30 +08:00。该目录被忽略，不进git。

生产差异计数生成：对P0基线与侧线两树的`git status --porcelain=v1 -z -uall`路径并集，只取`src/main`与POM，逐文件比较SHA-256；本机`impl/final-production-manifest.json`记录 **141项：新增22、删除17、修改102**。此数字是测试启动时的快照，不包括后续可能的生产修复。

## 测试当前事实

同一个无头dsh测试owner顺序执行；先前尝试的内部再拆子代理已由控制方停止，禁止同树多测试写者。生产owner已停手，测试不改生产代码（受控变异必还原例外）。

已执行：

```bash
tools/mvn-lock.sh -DskipTests=false test-compile -pl simos-production -am
# rc=0
tools/mvn-lock.sh -DskipTests=false test-compile -pl simos-economy -am
# rc=0
tools/mvn-lock.sh -pl simos-economy -am test
# rc=1：economy模块 Tests run: 367, Failures: 8, Errors: 33, Skipped: 0
```

证据在本topic `test/logs/production-test-compile-2.log`、`economy-test-compile-8.log`、`economy-test-run-1.log`。**这是首轮红，不是通过数字。** 多数错误为旧测试给需要定义的入口传裸`EconomyData`，仍须逐条修夹具/判生产缺陷；GOV_SERVICE拒绝等语义红尚未定因，不能预称实现无问题。

当前继续：修经济红→迁culture/app测试→补V1–V7→测试/变异/黄金/独立模拟→侧线全reactor clean verify。使用同一dsh测试session续单，不从头、不降范围、不再派测试子代理。

## 恢复入口与未做

本机topic：`.superpowers/sdd/2026-10-16-d031-p1-definition-migration/`。控制方`progress.md`保存当前shell会话与日志路径；实现/测试各有`impl-ledger.md`、`task-report.md`、`control.md`。最新测试进度在`test/impl-ledger.md`，真实缺陷（若有）在`test/findings.md`。它们仅本机留痕，不是跨设备交付物。

尚未完成：app/culture测试迁移、经济测试全绿、V1–V7最终验收、全reactor clean verify/前端/SpotBugs、变异还原证明、P0/P1黄金及跨周期模拟。未跑主树gate、未提交/推送、未跑真LLM。不能以P0的3422/412等历史数字替代P1结果。

## 进度追加（2026-10-16 19:51 +08:00）

经济测试已收敛：`tools/mvn-lock.sh -pl simos-economy -am test`，`test/logs/economy-test-run-6.log` 实际 `Tests run: 367, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。此前首轮红数字保留作过程记录，**不再是经济当前结果**。

测试owner正在迁app/culture并收拢app测试编译；V1–V7最终验收、全reactor verify、变异与黄金/长模拟仍未完成。本追加不构成全批验收通过。

## 进度追加（2026-10-16 20:01 +08:00）

全reactor首跑未通过：app模块1002项/64失败/134错误/7跳过（`test/logs/reactor-test-run-1.log`）。控制方回读真实堆栈，确认两处真实生产缺陷：`GovWorldBootstrap`与`ThreePowersGovBootstrap`注册production产业后仍传EconomyCodec，导致创世ClassCastException；不是fixture缺片。原owner已串行修复为ProductionCodec，并compile rc=0（`impl/compile-fix-1.log`，20:00:07）。测试owner恢复复跑，**尚未确认行为已修复**。前文“无已确证缺陷”的旧阶段判断不再适用，细证见本机test/findings§4与impl/task-report§10。

## 本轮收尾（2026-10-16，20:28 +08:00 后）

本节取代前文各阶段“当前继续”“尚未确认修复”“app 仍红”等当前状态口径；历史日志和阶段记录保留。用户先说“做到这就停下吧”，随后纠正为“欸别别别，等手头上的东西做完，然后再保存一下工作状态就行”（均为 2026-10-16 原话）。按后一条执行：等待手头 app 回归及已经启动的 clean verify 自然结束，然后仅整理交接，不再开发、补测试或修门禁。

### 已落地与归属

已回源码核实：`simos-production/src/main/java/io/mosire/simos/production/ProductionData.java:47` 的完整状态拥有 industries/modes；`simos-production/src/main/java/io/mosire/simos/production/tools/write/EconomyUpsertIndustryTool.java:63` 由 production 自己维护领域工具，资源作用域是 production（同文件 `:70`），app 只注册装配。外部工具名 `simos.economy.upsertIndustry` 保留；不能据外部名字把实现再迁回 app。文化真实效果、共享流动表仍未实现。

两处生产缺陷已由原实现 owner 修复：`simos-app/src/main/java/io/mosire/simos/app/tools/write/GovWorldBootstrap.java:503` 和 `simos-app/src/main/java/io/mosire/simos/app/world/ThreePowersGovBootstrap.java:640` 配套改用 ProductionCodec；原来的 EconomyCodec 强转触发 ClassCastException。下列最小复现已实跑通过，后续 app 全回归也通过，不再只是编译确认。

### 本轮实际验证

命令均在 `parallel-side-module` 根执行；日志相对本机 topic `.superpowers/sdd/2026-10-16-d031-p1-definition-migration/`，该目录被忽略，**不在版本库里**。

| 命令 | 实际输出 | 证据 |
|---|---|---|
| `tools/mvn-lock.sh -DskipTests compile -pl simos-app -am` | 修复后 rc=0，`BUILD SUCCESS`，20:00:07 +08:00 | `impl/compile-fix-1.log` |
| `tools/mvn-lock.sh -pl simos-economy -am test` | rc=0，economy `Tests run: 367, Failures: 0, Errors: 0, Skipped: 0` | `test/logs/economy-test-run-6.log` |
| `tools/mvn-lock.sh -pl simos-app -am -Dtest=GovToolsZ6Test#assignPostsRejectsUnknownRoleLaborAndNonMemberHousehold -Dsurefire.failIfNoSpecifiedTests=false test` | rc=0，目标用例 1/0/0/0 | `test/logs/govtools-min-1.log` |
| `tools/mvn-lock.sh -pl simos-app -am test` | rc=0，`BUILD SUCCESS`，20:25:12 +08:00；18 个模块汇总 **3422/0/0/7**，其中 app **1002/0/0/7**；`[frontend-gate] OK tests=412 pass=412 fail=0` | `test/logs/app-test-run-5.log` |
| `tools/mvn-lock.sh -pl simos-app -am clean verify` | rc=1，`BUILD FAILURE`，20:28:01 +08:00；util 的 Spotless 拦截，后续模块 `SKIPPED` | `test/logs/reactor-clean-verify-1.log` |

最后一轮 `test` 的测试总数来自该轮日志的模块汇总行，**不是复用 P0 历史数字**；未带 `clean`，不可替代最终 clean verify。计数复核命令：

```bash
python3 - <<'PY'
from pathlib import Path
import re
p = Path('.superpowers/sdd/2026-10-16-d031-p1-definition-migration/test/logs/app-test-run-5.log')
rows = []
with p.open(errors='replace') as f:
    for line in f:
        m = re.fullmatch(r'\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*', line)
        if m:
            rows.append(tuple(map(int, m.groups())))
print(len(rows), [sum(r[i] for r in rows) for i in range(4)])
PY
# 18 [3422, 0, 0, 7]
```

7 项跳过来自 `RealLlmUnitDecisionLoopTest` 的 4 项及 `RealLlmGovScenarioTest` 的 3 项；两类在 `simos-app/src/test/java/io/mosire/simos/app/decision/RealLlmUnitDecisionLoopTest.java:201`、`RealLlmGovScenarioTest.java:147` 显式要求 `SIMOS_REAL_LLM=1`。这是环境开关门控，不是已确认本地 LLM 不可用；**未运行、不计通过**。

最终 verify 的已知拦截文件是 `simos-util/src/main/java/io/mosire/simos/util/spi/CandidateStateValidator.java:13`，Spotless 报 Javadoc 折行格式不符。该轮 util 测试 217/0/0/0 后即失败，后续模块未完成验证；**不能声称整仓 Spotless/SpotBugs/clean verify 通过**。本轮按用户要求未继续修格式或复跑。修掉首个拦截后还可能出现后续检查问题。

### 下次恢复顺序与没做

1. 从本文件恢复，先核 `git status --porcelain`、现有进程及各责任区 owner；不要恢复 stash 或覆盖主树。侧线分支 `parallel/side-module`、HEAD `03faf0a36278362d93737b719da0bfeccc8f139f`，P0/P1 都是未提交改动。
2. 由原实现 owner 处理生产源码格式，记录实际变动；再由统一测试 owner 做最终 clean 验证。不能由控制方直接改代码，不能把本轮增量 test 作为 clean 结果。
3. 阅读测试 owner 的 `test/task-report.md`、`test/impl-ledger.md`、`test/findings.md`、`test/migration-map.md`；复核迁移后断言是否保持原判别力，覆盖缺口不能靠绿数字关闭。
4. 继续设计与计划中尚未完成的 V1–V7：非空状态及真实重启回放、P0/P1 相同业务输入黄金对照、7→11 权威切换、独立手算、跨片非法提交原子拒绝、变异及还原哈希、独立长模拟。现有 P0 往返/中性接线测试和旧业务回归通过，**不等于这些 P1 专项验收已经完成**。
5. 收敛后完成侧线完整 clean verify、前端、全部模块 SpotBugs 与变异证明。主树 gate、同步、提交和推送均未做；提交仍需用户明确授权。

### 工作树、证据与会话

- 唯一 P1 修改树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
- 冻结 P0：`/home/cna/SimulatorMosire/.claude/worktrees/d031-p1-baseline`，detached 同 HEAD；保留，不删除、不更改业务源。主树仍只有原有 P0，`ga-opt` 未操作。
- P0 清单是 `p0-baseline-manifest.json` 的 124 项；生产差异修复后快照为 `impl/final-production-manifest-fix1.json` 的 141 项。控制方曾于收尾等待期间逐项 SHA-256 核对，主树/P0 124 项及侧线生产 141 项均零差异；最终保存观察另留 `evidence/`。
- 原实现 dsh session：`session-e0bb7199-f1f6-4868-b077-3e97cf78b959`。测试 dsh session：`session-5ed9c6ce-254a-4176-883c-b8c7b392ddde`。续用原 owner，不另起并行写者。
- 测试续段日志 `test/run-continue-3.err`；最终仅交账的指令 `test/save-state-only.md`、日志 `test/run-save-state.{out,err}`。控制方在 verify 自然结束且 dsh 无子命令时暂停模型，以同一 session 仅整理账本；没有中断 Maven。
- 所有既有源码、测试、设计、计划及日志就地保存。未 `git add`、未 `commit`、未 `push`、未将 P1 同步主树、未操作其他批次 stash。

### 保存完成（2026-10-16 20:31:49 +08:00）

同一 TEST owner 仅交账续段已自然退出，rc=0，已保存 `test/task-report.md`、`test/impl-ledger.md`、`test/findings.md`、`test/migration-map.md`。本轮 dsh/Maven 均已结束，无待运行派单；按用户要求到此暂停。

控制方最终只读快照的生成命令：

```bash
python3 .superpowers/sdd/2026-10-16-d031-p1-definition-migration/evidence/save-state-snapshot.py
```

20:31:26 +08:00 实际输出：主树与 P0 基线各核 124 项、零 SHA-256 差异；侧线生产快照核 141 项、零差异（无未还原生产变异）。`git status --porcelain=v1 -z -uall`：主树/基线各 38 修改、86 未跟踪；侧线 170 修改、9 删除、104 未跟踪。数字包含原有 P0，未跟踪按展开后的文件计数。明细和源码/测试/POM 指纹保存在本机 `evidence/saved-work-state.json` 与 `evidence/saved-status-*.txt`。这些是工作状态证据，不替代未完成的变异测试或验收。
