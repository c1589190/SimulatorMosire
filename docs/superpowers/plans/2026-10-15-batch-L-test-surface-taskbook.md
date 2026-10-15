# 批 L ② 的测试面任务书（草案，等 ② 生产面交账后派）

> 前置：`docs/superpowers/plans/2026-10-15-batch-L-sd-to-ga-rename.md`（§1 现状 · §2 裁定 · **§5 三条裁定 + §5.4 判据更正 + §5.7 追认**）
> ★ 生产面（② 第二任）**已在做**「机械标识符改名」（裁定甲授权：只改标识符/import/构造调用，
> **不动断言/期望值/字符串字面量**）⇒ **本批只收它明确留下的那一半**。

---

## 1. 责任区：**测试面的判据与字面量**（`src/test/**`）

生产代码**冻结**（② 已做完）。本批补的是 ② **按裁定甲边界刻意没动**的东西：

### 1.1 ★ 旧**工具名字面量**（基线：**51 处 / 6 文件**）
| 文件 | 处数 |
|---|---|
| `SimosToolsTest` | 22 |
| `McpServerTest` | 9 |
| `McpPortTopologyTest` | 8 |
| `ToolSurfaceGapTest` | 3 |
| `DecisionFaceGolden` | 2 |
| `ShellAssemblySmokeTest` | 2 |

★ **以 ② 交账时给的最终清单为准**（它被要求在台账里列出全部命中）。命中包括：
- 9 条 `simos.sd.*` 旧名（`decision-maker` / `decision-makers` / `diplomacy` / `diplomatic-events` /
  `directives` / `verdicts` / `run-decision-makers` / `record-diplomatic-event` / `set-diplomatic-relation`）
- 7 条 `sd.*` 旧名（`sd.VoidAdjudication` / `sd.AdjudicateTick` / `sd.SetArmyMasterGov` / `sd.CreateArmy` /
  `sd.RejectDirective` / `sd.DecisionResults` / `sd.DecisionDocs`）
- 两条被**合并**掉的工具名（`simos.sd.record-diplomatic-event` / `simos.sd.set-diplomatic-relation` ⇒ 新名见映射表）
- 两条无前缀的旧名（`gov.RecordDiplomaticEvent` / `gov.SetDiplomaticRelation` / `gov.CreateNation`）

### 1.2 ★★ **L4：旧名一律不留**（这是本批的核心判据）
照 `DecisionCallerFactoryTest:302-307` 的 `doesNotContain` 形制，**为每一条旧名写一条反向断言**。
★ 理由（本段最贵的教训）：**改工具名时不扫 `src/test` 的字面量 ⇒ 门控用例悄悄引用旧名，
而它被门控 ⇒ 从不跑 ⇒ 没人被告知**（今晚实测：`RealLlmGovScenarioTest` 10 处旧名，死角第 4 次）。

### 1.3 ★ L5：全仓扫描**必须有判别力**（见 §5.4 的更正）
```bash
# ✅ 正确（通配 pathspec 必须写成 :(glob)…/**，否则静默零命中）
git grep -n '"simos\.sd\.' -- ':(glob)simos-*/src/**'
# ★ 自证：先用同一 pathspec 扫一个必然存在的东西，证明它真能匹配到文件
git grep -c 'class ' -- ':(glob)simos-*/src/**'
```
★ **纪律**：用通配 pathspec 做"零命中"判据时，**必须先用已知存在的样本证明该 pathspec 能匹配到文件**；
**证不出匹配能力 ⇒ 那个 0 不算证据**（控制方今晚自己踩过：`git diff -- 'simos-*/src/main'` 静默空 ⇒ 报了假绿）。

### 1.4 ★ 计数锚点（合并两对 ⇒ 类1 **133→131**；类2 **24 不变**；类3 **37**）
- `ToolClassAuditAssemblyTest`：`GM_ONLY_SIZE` / `DM_APPROVAL_SIZE` / `DM_APPROVAL_NAMES` / `SHARED_SIZE` / `DM_FREE_SIZE`
- `ShellAssemblySmokeTest` 的桶锚点 · `DecisionCallerFactoryTest` + `DecisionFaceGolden` 的金名单
- ★ **两个被合并的 GM 工具名**要从"类1 具名"里消失、两条**合并后**的工具名要出现在**类2 具名**里
- ★ **先真跑读出实测值再写死**（★ 上一批的纪律：**不许照抄控制方的推导值**）

### 1.5 前端路由断点
前端真断点是**段路径 `"/sd/`**（`api.js` 11 处 + `decisionmodel.js` 3 处 = **14 处**）；
★ 判据 = **`"/sd/` = 0**（**不是** `api/sd/` —— 那只在注释里，26 处，抓不到真断点）。

---

## 2. 红线

1. **只能动 `src/test/**`**；生产代码**冻结**。
2. ★ `docs/**` **一字不许碰、不许暂存**（第三个写者正在改 D-031）。
3. ★ **不许为了让断言过而放宽**；**不许**删用例 / 加 `@Disabled`。
4. **变异自证至少 3 条**（改坏 ⇒ 当场红 ⇒ `git checkout --` 还原 ⇒ `md5sum -c`）：
   例：把某个新工具名改回旧名 ⇒ L4 反向断言必红；把某条桶锚点改回 133 ⇒ 必红。
5. **一个 Maven 一次**（`tools/mvn-lock.sh`）；收尾 `clean verify` ⇒ **必须 rc=0**，报全部真实数字。
6. **不要提交**。
