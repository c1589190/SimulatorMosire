# 工作状态存档 —— 2026-10-16 00:2x（GM⇄DM 工具三分类之后的一整夜）

> **状态：历史快照（文档系统重写于 2026-10-23）** · 当前实时状态以 [`docs/agents/STATUS.md`](../../agents/STATUS.md) 为准；本文只作当时事实留痕，勿据此开工。

> **用途**：会话交接 / 下次开工的**唯一入口**。★ 本文只写**已入账的事实**与**待办**；
> 每条都带**可复核的命令或 `file:line`**；★ 拿不准的标"未核实"。
> 上一份存档：`docs/superpowers/status/2026-10-15-gm-dm-tool-classes-progress.md`（同段的前半）。

---

## 0. 一句话状态

**今晚从「批 K」一路做到「批 V」，六个批次全部入账，门禁绿**；
★ 主树干净、已推送；★ 新建了 **ga 优化专用工作树**（`.claude/worktrees/ga-opt`）；
★★ **有一份未落盘的改动在 `stash@{0}` 里**（批 W/X 半成品 + 用户裁定的「本地 LLM 为默认」）——
恢复方法见 §3；★★ **两件事在等用户**（§6）。

---

## 1. 已入账（今晚的提交，按批次）

| 批 | 提交 | 做了什么 | 关键数字 |
|---|---|---|---|
| **K** | `926df76a` `827580c8` `a505521b` `21074edc` `c6265f53` | ★ **让声明成为唯一真相**：`DecisionProposable` 搬到工具身上（`simos-ga-spi`）、名单**从工具面派生**、**装配期 C1/C2 审计**；⑤ 48 站点说明文改成事实 | 类1 140→**131** · 类2 17→**24**（裁定甲给 7 条补 DM 标签） |
| **L** | `0fe28f97` `64df921d` `f082c354` `98e3c9b8` `f99755db` `955b75f9` `c2069e98` `579ac5f6` `fa28daf4` | **旧招牌收尾 `sd → ga`**：9 工具名 · 19 `Sd*` 类名 · 9 路由 `/api/sd/→/api/ga/` + 前端 6 文件 · **包名 `app.sd → app.ga`** · 私有常量 `SD_WRITE/SD_READ → GA_*`；两对外交工具**合并成一条** | 生产 `579ac5f6`（129 文件）· 测试面 `fa28daf4`（204 处字面量 + **L4-c 死角根治守卫**） |
| **M** | `94f21ee9` `d94a8b08` `fbd7712f` `3604f546` | ★★ **多 tick 全真模拟搬出 JUnit**（**用户裁定**：测试 class 里不要塞多 tick） | 3 类 / 4 用例搬走（240/120/60/90 tick）· 门禁 **7:29 → 5:20**（测试阶段 341s → 221s，**−31%~−35%**） |
| **N** | `d77cc7c9` `5053becd` `81e67a8c` | 命名收尾三处残渣：**删两处死 `NAME` 常量** · `isSdSelfReference` 判"**有活路**"一字不动 · 一处"活的"旧名注释；另清 **44 处撒谎注释** | 全程**零行为改动**（token 流自证 + diff 非注释行 0） |
| **O/P/R/S/T/U** | `12f23e68` `14d19515` `10ac9a4f` `45b44d94` `44a1186c` `38f56005` `81ae2209` `23a7c070` | ① GM 薄工具 **`simos.command.submitBatch`**（原子批，一批一条 revision，**实测两口径**）② **`simos-ga-spi` 补测试面**（8 类 / 44 用例 · 6/6 变异全红）③ **修好两个门控真 LLM 用例** ④ ★★ **修 `propose` 写后 NPE**（真机挖出的生产缺陷）⑤ ★★ **真 LLM 收口：缺省 3 轮真跑全绿** | 类1 **132** · GM 桶 **180** · 并集 **193** · 门禁 **3356/0/0/7** · SpotBugs 16 模块 0 · 前端 412/412 |
| **并发文档** | `98e3c9b8` `38f56005` `0169309c` `10a88c69` `41b2e2fe` `3e3fdd09` | 四条新纪律（见 §5）+ **Maven 锁改项目级** + **工作树登记表** | —— |

★★ **提交计数**（实测）：`git log --oneline --since="2026-10-15 18:00" | wc -l` ⇒ **36**（含文档提交）。

---

## 2. 环境事实（★ 下次开工前先核这几条）

| 项 | 状态 | 怎么核 |
|---|---|---|
| **本地 LLM** | ★ **在线**（用户开了 `gemma4-local` @ `http://127.0.0.1:8080/v1`）；★ 用户**另一条裁定**：门控真 LLM **默认用本地**（那条改动**在 stash 里**，见 §3） | `curl -s -m4 http://127.0.0.1:8080/v1/models` |
| **Maven 锁** | ★★ **项目级**（共享 `.git` 下）⇒ 同项目全部工作树**共用一把锁** | `tools/mvn-lock.sh` 头部注释 + §一.13 |
| **工作树** | 三条：**主树 `main`（唯一入账口）** · `.claude/worktrees/ga-opt`（`ga/opt`，**ga 优化专用，空着等目标**） · `.claude/worktrees/parallel-side-module`（`parallel/side-module`，**另一写者 D-031，控制方不碰**） | `git worktree list` |
| **`/tmp` 遗留** | ★ **已清**：`simos-r`（批 R，内容先存后删）· `simos-realllm`（prunable） | `git worktree list` 应只有上面三条 |
| **D-031 的活** | ★ 另一写者的文件 `docs/superpowers/reports/2026-10-15-d031-side-line-review-decisions.md` 是**未跟踪**、**一直在主树里出现**（24:11 落盘）· ★ **控制方不碰、不暂存** | `git status --porcelain` |
| **推送** | ★ **全部已推送**（`git log origin/main..HEAD` 为空） | 同上 |

---

## 3. ★★ 未落盘的东西：`stash@{0}`（**这是本文最要紧的一节**）

```
stash@{0}  "W+X 在飞改动（被停时未完成；X 的中间态编译不过）—— V 的 provider=gemma4-local 裁决也在其中"
9 files changed, 396 insertions(+), 20 deletions(-)
```
★ **stash 本身是 git 对象 ⇒ 不会丢**；另有**两份副本**（★ 用不会丢的那份）：
```
.superpowers/sdd/2026-10-16-inflight-stash/     ← ★★ 本地留痕（`.gitignore` 排除，重启不丢）
    wx-inflight-0012.patch             44593 字节  ← stash@{0} 的全文
    simos-r-uncommitted.patch          33831 字节  ← /tmp/simos-r 被删前的未提交内容
    ProposeNoIntentNpeProbeTest.java.bak 4800 字节 ← ★ 批 S「免 LLM 确定性证明」的唯一副本
/tmp/wx-inflight-0012.patch            44593 字节  md5 08ac786e3fcf934f11331d321d8e4c18（★ 重启会没）
```

### 里面是什么（分三块，**质量不同**）
| 块 | 文件 | 状态 | 处置建议 |
|---|---|---|---|
| **V** | `RealLlmUnitDecisionLoopTest` / `RealLlmGovScenarioTest` 的 `PROVIDER_ID` | ★★ **用户已裁定**（「开好了，优先用本地的」⇒ 裁定**乙：local 为默认**） | ★ **要捡回来**（`SIMOS_GOV_SCENARIO_PROVIDER=mosire-flash` 可切回远程） |
| **W 前半** | 同上两文件的**夹具自检 A/B**（改成"从选中 provider 派生期望"） | ★ **已验证过**：本地匿名路由上自检**能过**（`[GOV-SCENARIO-CONFIG] provider=gemma4-local valid=true keyConfigured=false`）+ 判据 A 的**负向自证**（`MUTATION-model` ⇒ 当场红 ⇒ 还原 md5 一致） | ★ **值得捡回**（② 在本地才跑得起来） |
| **X** | `CatalogVisibility` · `ProposalCatalog` · `SimosToolSource` · `AppGaProposalHost` · `CatalogTool` · `GaProposalHost` · `ProposeCallTool` | ★★ **中间态、`test-compile` rc=1**（`SimosToolSource.java:1177 method readTools`）；**设计已批**（catalog 标注可提议面 + 报错文案上移成唯一拼写点） | ★ **要重做**（不是捡回）—— 见 §6 待办 |

### 恢复方法
```bash
cd /home/cna/SimulatorMosire
git stash show -p stash@{0}                       # 先看
git stash pop                                     # 捡回（★ 会有 X 的编译不过 ⇒ 需要重做 X）
# 或只捡 V+W（按文件挑）：
git checkout stash@{0} -- \
  simos-app/src/test/java/io/mosire/simos/app/decision/RealLlmUnitDecisionLoopTest.java \
  simos-app/src/test/java/io/mosire/simos/app/decision/RealLlmGovScenarioTest.java
```

---

## 4. 门禁基线（最近一次作者门禁，实测）

```
tools/mvn-lock.sh clean verify
rc=0 / BUILD SUCCESS · Total time 05:10
Tests 3356 / Failures 0 / Errors 0 / Skipped 7    （Skipped 7 = 真 LLM 环境门控，非本批）
SpotBugs 16 模块全 BugInstance size 0（非零 0）
前端 [frontend-gate] OK tests=412 pass=412 fail=0
```
★ 注意：**`spotless` 在 SpotBugs 之前** —— spotless 一红该模块 SpotBugs 根本不跑（★ 今晚踩过一次，
`RealLlmGovScenarioTest` 手排格式 ⇒ `spotless:check` 红 ⇒ 复跑才绿）。

---

## 5. 今晚新增的纪律（★ 全部有实测出处）

| # | 纪律 | 出处 |
|---|---|---|
| 1 | ★★ **真机真跑挖出的缺陷最值钱** —— `planOnly` 双写靠测试代理真调、`propose` 写后 NPE 靠**真模型"报错后重试"**（人想不到） | `45b44d94` |
| 2 | ★★ **「写之后抛 ⇒ 报错但已落盘」是通用形状**：`writes.submit*(...)` 之后到 `return` 之间的任何一行都可能抛 ⇒ **写之后的代码必须"不会抛"** | `45b44d94` |
| 3 | ★★ **"静默 0"家族三个变种**（判据为 0 时**退出码与语义方向相反**）：通配 pathspec 匹配不到 · `git grep` 默认不搜未跟踪文件 · **非 ASCII 文件名被引号转义**（`-z \| tr` 才准） | `10a88c69` 等 |
| 4 | ★★ **在飞文件 = 瞬时红**（同日 3 例）：**报，但别当结论**；先看 mtime + 改动形状（`&& false` / 引号未闭合）；★ **绝不代改** | `38f56005` `0169309c` |
| 5 | ★★ **变异自证要么先声明、要么去独立工作树**（共享树 = 公共池子） | `38f56005` |
| 6 | ★★ **跨模块改动：先把端口/接口落定并编译过，再改调用方** —— 否则半成品会以"编译不过"占住整棵树 | `0169309c` |
| 7 | ★★ **工作树纪律**：一项目一把 Maven 锁（共享 `.git`）· **跨树生效必须先提交** · 工作树里**没有** `test-world/` · 服务端口是一份 · **清理先存后删** | `41b2e2fe` `3e3fdd09` |
| 8 | ★★ **"活 vs 留痕"判据升级为三类**：**活标识**（改）· **活规则**（不改）· **历史留痕**（不改） | `81e67a8c` |
| 9 | ★★ **锚点必须真跑读出实测值，不许照抄任何人的清单**（批 O 给的 6 处清单**漏了 3 处**，含两条从未跑过的 MCP 名单） | `23a7c070` |

---

## 6. ★★ 待办与待用户裁决（下次开工从这里接）

| # | 事项 | 状态 | 需要谁 |
|---|---|---|---|
| **A** | ★★ **「进一步优化 ga 模块」的具体目标** | ★ **用户说"我有别的具体目标"但尚未说明** ⇒ **`ga-opt` 工作树建好空着就等这一句** | **用户** |
| **B** | ★★ **AgentLib 边界**：需求书已落盘 `48a7ef97`（`docs/superpowers/plans/2026-10-16-agentlib-boundary-needs.md`，7 节 · N1–N8 能力需求 · 给隔壁的 8 个问题） | ★ **等用户去隔壁核实后给答复** ⇒ ★ **在此之前：不搬、不派**（用户裁定） | **用户** |
| **C** | **批 X 的活**（`simos.command.catalog` 标注**可提议面** + 报错文案上移成唯一拼写点） | ★ **设计已批、被停在中间态**（在 `stash@{0}`，编译不过）⇒ **要重做**；★ 顺带它核实的事实：catalog 现只给 `types` + `payloadHints`、**没有"能不能 propose"标记**（`CatalogTool.java:1035-1051`） | 可派 |
| **D** | **批 Q：P4c 军俸工具**（★ 用户裁定 **甲：类2 + 进 `ProposalCatalog`**，预期类2 **24→25**） | 押后中（原等 X 交账；★ 与 C 同碰可提议面 ⇒ **建议与 C 一起做或排在其后**） | 可派 |
| **E** | **「旧名 ⇒ 静默 null」专项扫描**（今晚见两处：`stateIntent()` 读 `sd:directive.` 静默空串） | ★ 用户说"先记着" | 待定 |
| **F** | ★ **门控真 LLM 的稳定性** | ① 已达成（T 绿）；★ 但**本机模型偶发**（1 轮红/3 轮绿 · 出现过 300s 流式停摆 · 一次工具路由滑档）⇒ ★ **`local 默认` 是用户裁定，但"绿"的可信度依赖 provider** | 观察 |
| **G** | **`GA_NAMESPACE` 等守卫** | 已入账（批 P 44 用例） | —— |

---

## 7. ★ 未验证 / 没做（纪律要求，如实列）

- ★ **AgentLib 的实现语义**：**没有**系统读过 ⇒ 需求书里那条"请隔壁核实"是**真需要核实**，不是客套。
  ★ 已**证实**的只是"今晚四笔出血**都不是** AgentLib 的缺陷"。
- ★ **`~/.m2` 的跨项目并发**：锁改成了**项目级** ⇒ 本仓工作树之间安全了；
  ★ **但本仓与隔壁仓库（另一个 `.git`）仍会争 `~/.m2`** —— 用户既有裁定是"隔壁不参与本锁" ⇒
  ★ **控制方未擅自扩大锁范围**，这条**留待用户裁定**。
- ★ **`test-world/` 在别的工作树里不存在** ⇒ 多 tick 模拟**只能在主树跑**（或 symlink，但 store 是共享可变状态）。
- ★ **X 的中间态**：未验证、未评审 ⇒ 只能当"设计意图"参考，代码**必须重做**。
- ★ **`stash` 是易失的**（`git stash pop` 一次就没了；`git stash clear` 会毁掉）：★ **建议尽快按 §3 处置**；
  ★ 副本已存两份，其中 `.superpowers/sdd/2026-10-16-inflight-stash/` 是**本地留痕、重启不丢**，
  `/tmp/...` 那份**重启会没**。

---

## 8. 复核命令（照抄可用）

```bash
cd /home/cna/SimulatorMosire
git log --oneline --since="2026-10-15 18:00"     # 今晚的 28 个提交
git status --porcelain                           # 应只有 D-031 那个未跟踪文件
git stash list                                   # 应有一条 stash@{0}
git worktree list                                # 应三条
tools/mvn-lock.sh clean verify                   # 门禁（~5:20）
curl -s -m4 http://127.0.0.1:8080/v1/models      # 本地 LLM
```
