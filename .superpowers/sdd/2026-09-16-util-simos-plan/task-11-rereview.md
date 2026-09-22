# T11 复审（限域重审：`70297a5..6eb37e6`）

**范围**：只有一个提交 `6eb37e6`（T11 fix round 1，纯文档，3 文件 8+/7-）。
**对象**：`review-70297a5..6eb37e6.diff`（评审包）。
**上游**：`task-11-review.md` 的 6 条发现（发现 1/2 = Important，发现 3/4/5/6 = Minor）触发了这一轮。
**日期**：2026-09-16。**本轮只读**：未改任何文件、未提交、未推送。工作树对这 3 个文件与 `6eb37e6` **逐字节相同**：

```
$ git status --porcelain CLAUDE.md docs/superpowers/plans/2026-09-16-simos-master-plan.md docs/superpowers/plans/2026-09-16-util-simos-plan.md
(空)
$ git diff --stat 6eb37e6 -- CLAUDE.md docs/.../2026-09-16-simos-master-plan.md docs/.../2026-09-16-util-simos-plan.md
(空)
```

**越界检查（本提交应只动 `.md`，不得出现 `src/`）**：

```
$ git show --name-only --format='' 6eb37e6
CLAUDE.md
docs/superpowers/plans/2026-09-16-simos-master-plan.md
docs/superpowers/plans/2026-09-16-util-simos-plan.md
$ git show --name-only --format='' 6eb37e6 | grep -c '^src/\|/src/'
0
```
⇒ **未越界**，且**未 add 任何 `.superpowers/**`**（发现 ⑤ 的附带要求成立）。

---

## 逐条结论

### 发现 1【Important】`CLAUDE.md` 删「（2026-09-16 实测四份合计 5445 行）」 —— **ADDRESSED**

改动就是**只删括注、一字未加**：

```
$ git diff -U0 70297a5..6eb37e6 -- CLAUDE.md
@@ -64 +64 @@ UtilSimos  →  MapSimos  →  { SocialSimos, UnitSimos }  →  CoreSimos
-只会让每个会话白白载入 **5000+ 行**（2026-09-16 实测四份合计 5445 行）。上面用反引号书写路径
+只会让每个会话白白载入 **5000+ 行**。上面用反引号书写路径
```

**「5000+」量级当场自数成立**（四份 = 686 + master-plan + 409 + 3058）：

```
$ wc -l docs/superpowers/specs/2026-09-16-simos-master-design.md docs/superpowers/plans/2026-09-16-simos-master-plan.md docs/superpowers/specs/2026-09-16-util-simos-design.md docs/superpowers/plans/2026-09-16-util-simos-plan.md
   686 .../2026-09-16-simos-master-design.md
  1310 .../2026-09-16-simos-master-plan.md
   409 .../2026-09-16-util-simos-design.md
  3058 .../2026-09-16-util-simos-plan.md
  5463 total
```
⇒ 现值 **5463**，`5000+` 成立 ✓。

**没有第二处写死的行数**（实现者声称全文扫过，抽查属实）：

```
$ grep -n '[0-9]\+ *行\|[0-9]\+行\|[0-9]\+ *+' CLAUDE.md
64:只会让每个会话白白载入 **5000+ 行**。上面用反引号书写路径
$ git grep -n '1800\|5445\|5000+ 行' -- '*.md'
.superpowers/sdd/2026-09-16-util-simos-plan/progress.md:1711:  ... 「四份合计 **5445** 行」不可复现 ...
CLAUDE.md:64:只会让每个会话白白载入 **5000+ 行**。上面用反引号书写路径
docs/superpowers/plans/2026-09-16-simos-master-plan.md:796:          <timeout>1800000</timeout>
```
⇒ 全仓 tracked `.md` 里，写死的 `5445` 仅剩 `progress.md` 那一行（**台账的历史记录，不在本提交内**）；`1800000` 是 Maven timeout 配置，非行数。**本提交涉及的 3 份文档与 CLAUDE.md 已无漂移的行数** ✓。

**抽查「不漂」的数字（≥3 组，实查 5 组，全部属实）**：

| 被声称不漂的数 | 我的核验命令 | 原始输出 | 判 |
|---|---|---|---|
| `JAR 类数 ≥ 118` | `jar tf ~/.m2/.../agentlib-mosire-0.1.0-SNAPSHOT.jar \| grep -c '\.class$'` + 读测试 | `118`；`MIN_EXPECTED_CLASSES = 118`，断言 `isGreaterThanOrEqualTo` | **真不漂**（`>=` 构造 + 现值恰为 118，新增类不会翻假） |
| `Java 21` | `git grep -n 'maven.compiler.release' -- pom.xml` | `pom.xml:25:<maven.compiler.release>21</maven.compiler.release>` | 真 ✓ |
| `M1 11/11` | `grep -c '^### Task ' .../2026-09-16-util-simos-plan.md` | `11`（Task 1…Task 11 逐个列出） | 真 ✓ |
| `M0 5/5` | `grep -c '^### Task ' .../2026-09-16-simos-master-plan.md` | `5`（Task 1…Task 5） | 真 ✓ |
| `13 个类可加载` | 读 `AgentLibAvailabilityTest` 的 `@ValueSource(strings={...})` | 字符串条目 **13** 条 | 真 ✓ |

---

### 发现 2【Important】`master-plan`「（已批准）」→ 消歧 —— **ADDRESSED**

**(a) 措辞按裁定改了**（与裁定给的**逐字一致**）：

```
$ git diff -U0 70297a5..6eb37e6 -- docs/superpowers/plans/2026-09-16-simos-master-plan.md
@@ -1129 +1129 @@
-**M1 关账记录（2026-09-16）**：spec `.../2026-09-16-util-simos-design.md`（已批准）；
+**M1 关账记录（2026-09-16）**：spec `.../2026-09-16-util-simos-design.md`（**类型形状已于上一会话批准**；**spec 本体仍「待用户评审」**）；
```

**(b) 改后与被引 spec 不冲突**（去读了被引文档本体）：

```
$ sed -n '1,6p' docs/superpowers/specs/2026-09-16-util-simos-design.md
# UtilSimos 模块设计（M1 spec）
**日期**：2026-09-16
**状态**：待用户评审
**上游**：`docs/superpowers/specs/2026-09-16-simos-master-design.md`（总纲，已批准）

$ git grep -n '已批准' -- docs/superpowers/specs/2026-09-16-util-simos-design.md
:4:**状态**：待用户评审
:5:**上游**：...（总纲，已批准）
:14:| 1 | 八大件的完整方法签名 | 类型形状**已批准**（见 §3）... | 上一会话"可以" |
:74:### 3.1 类型（总纲已批准的形状）
```

⇒ 新措辞的两个半句**各自有出处**：`类型形状已于上一会话批准` ← spec `:14`（出处「上一会话"可以"」）；
`spec 本体仍「待用户评审」` ← spec `:4`。**消歧方向正确**——原文「（已批准）」会被读成 spec 整体已批准，
而 spec 自己的状态行是「待用户评审」。**无冲突** ✓。

---

### 发现 3【Minor】`master-plan`「`M1 Task` **打头**」→「**含** 字样」 —— **ADDRESSED**，**17 / 14 / 3 三数当场重跑属实**

```
$ git diff -U0 70297a5..6eb37e6 -- docs/superpowers/plans/2026-09-16-simos-master-plan.md
@@ -1155,2 +1155,3 @@
-`git rev-list --count 56836f0..origin/feat/m1-util-simos` → **20** 个提交，其中 `M1 Task` 打头的是 **17** 个
-（Task 1–5 的实现与修复轮），另 3 个是 spec 裁决 / 构建修复 / SDD 台账入库。
+`git rev-list --count 56836f0..origin/feat/m1-util-simos` → **20** 个提交，其中提交信息里**含** `M1 Task`
+字样的有 **17** 个（14 条 `feat`/`fix`/`test`/`refactor` 代码提交 + 3 条 `docs(spec)` 裁决）——
+**是"含"不是"打头"**：`git log --oneline | grep -c '^M1 Task'` = **0**；另 3 个提交不含该字样。
```

三个数 + 那条 `= 0` 全部当场重跑：

```
$ git rev-list --count 56836f0..origin/feat/m1-util-simos
20
$ git log --format='%s' 56836f0..origin/feat/m1-util-simos | grep -c 'M1 Task'
17
$ git log --format='%s' 56836f0..origin/feat/m1-util-simos | grep 'M1 Task' | grep -cE '^(feat|fix|test|refactor)'
14
$ git log --format='%s' 56836f0..origin/feat/m1-util-simos | grep 'M1 Task' | grep -c '^docs(spec)'
3
$ git log --format='%s' 56836f0..origin/feat/m1-util-simos | grep -v 'M1 Task'
docs(sdd): M1 的 SDD 台账与任务证据入库（-f 越过 .superpowers/sdd/.gitignore）
docs(spec): SegmentedSeries 定为 record——函数组件的相等语义与共享实例纪律（Task 7 前置裁决）
fix(build): 删去 spotbugs 4.10.x 已移除的 fork 参数（消除 verify 警告）
```
⇒ `20 = 17 + 3` ✓；`17 = 14 + 3` ✓；**不含该字样的恰为 3 个** ✓。

**`grep -c '^M1 Task'` = 0 —— 逐字跑了文档里写的那条命令（注意它没带 range）**：
```
$ git log --oneline | grep -c '^M1 Task'      # 与文档写的完全一致，无 range ⇒ 覆盖整支 local HEAD
0
$ git log --format='%s' HEAD | grep -c 'M1 Task'    # 对照：含字样的
25
```
⇒ **命令逐字可跑且为 0** ✓。这一点我特意查过：如果本地分支的 Task 6–11 提交里有以 `M1 Task` 打头的，
文档里那条无 range 的命令就会给出非 0，**新写的「= 0」就会变成新的假话**。实测**没有**，故不构成新缺陷。

---

### 发现 4【Minor】`util-plan` 删「至少六处」、枚举原样保留 —— **ADDRESSED**

本提交对该文件**只动了 2 行**，且第 2 处是**行内删除**（`至少六处，` 被抹掉、其余一字未加）：

```
$ git diff -U0 70297a5..6eb37e6 -- docs/superpowers/plans/2026-09-16-util-simos-plan.md
@@ -14 +14 @@
-> 下面的 Java 草图**不是权威**——**spec 与已落地的 `simos-util/src` 才是权威**。已知分歧至少六处，各自在
+> 下面的 Java 草图**不是权威**——**spec 与已落地的 `simos-util/src` 才是权威**。已知分歧各自在
```

**枚举一字未动**（旧/新逐行比对，:15 / :16 两行完全相同）：

```
$ git show 70297a5:<file> | sed -n '15,16p'
> 对应位置有**取代说明**：任务地图的 Task 2 行、以及 Task 7 Step 1 前·Step 4、Task 8 Step 1 前、
> Task 9 Step 1 前、Task 10 Step 1 前·Step 2 各一处。
$ git show 6eb37e6:<file> | sed -n '15,16p'
> 对应位置有**取代说明**：任务地图的 Task 2 行、以及 Task 7 Step 1 前·Step 4、Task 8 Step 1 前、
> Task 9 Step 1 前、Task 10 Step 1 前·Step 2 各一处。
```
（`:17`「取代说明一律保留草图原貌…」亦为 diff 的 context 行，未被触碰。）

**删后通顺** ✓：「已知分歧各自在 / 对应位置有**取代说明**：……」——主语「已知分歧」+ 状语「各自在对应位置」+ 谓语「有取代说明」，语法完整。
另核：枚举条目自数确为 **7**（Task 2 行 / Task 7 Step 1 前 · Step 4 / Task 8 / Task 9 / Task 10 Step 1 前 · Step 2），与提交信息「穷举枚举实为 7 处」一致——**删数正是对的**。

---

### 发现 5【Minor】`task-11-report.md` 的「5 处命中 / `task-11-brief.md:42`」 —— **ADDRESSED**（经工作树直读；该文件不在 diff 内）

报告未入库，**无法从 diff 核**，故按指示**直接读工作树对应段**：

```
$ grep --hidden --no-ignore-files -n 'provider("unit", null)' .superpowers/.../task-11-report.md
118:| 2 | "换设备后的自检清单"补第 3 条（ugrep 静默假阴性） | ... `git grep -n 'provider("unit", null)'` → 有命中（如 `task-9-brief.md` 中的提及处；**行号按当时版本的 brief**）。... **不写计数与行号**：该串的命中数随台账被编辑而变（T11 评审时复核已是另一数目），写死即失真；...
222:| ⑤ | `task-11-report.md`（原 `:118`） | 「...→ **5 处命中**（含 `task-9-brief.md:109`、`task-11-brief.md:42`）」 → 「→ 有命中（如 `task-9-brief.md` 中的提及处；**行号按当时版本的 brief**）」 | ...
```

判「改后是否仍随文件演化而失真」：
- **:118 是改写点本身** —— 新措辞 `→ 有命中（如 task-9-brief.md 中的提及处；行号按当时版本的 brief）` **不含计数、不含行号**，只断言"有命中" ⇒ **不再随文件演化而失真** ✓。
- **新措辞当下为真**（不是"改成另一句迟早变假的话"）：该串现确在 `task-9-brief.md` 里
  ```
  $ git grep -n 'provider("unit", null)'
  .superpowers/.../task-9-brief.md:109:    assertThatThrownBy(() -> registry.register(provider("unit", null)))
  .superpowers/.../task-11-brief.md:65:  ... 该串就在 `task-9-brief.md:109` 与 `task-11-brief.md:42` ...
  .superpowers/.../task-11-brief.md:78:  ... （`provider("unit", null)` 变成 `facetName="unit"`、`entries=null`）...
  .superpowers/.../progress.md:（6 处）
  ```
- **:222 是对账表**，其「原措辞」列**按职责**必须引用被改掉的原文（`:109`/`:42`/「5 处命中」）——这是**差异记录**，不是仍在生效的主张，**不计为未改**。

**未因此 add 任何 `.superpowers/**`**：`git show --name-only 6eb37e6` 只有 3 个 `.md`，无 `.superpowers/` 路径 ✓（命令与输出见文首越界检查）。

**残留观察（不阻塞）**：对账表 :222 的「核实」列与 `:264` 的「未能核实」段**新写入了行号** `:65`/`:78`、`:109`/`:42`。
它们分别是"当下的"与"当时版本的"快照引用，措辞上带时间锚（「当前的」「当时版本的」），
故不属"写死会漂的数"；但若台账再被编辑，这些行号同样会漂。**建议下轮不再新增行号引用**，本轮不构成缺陷。

---

### 发现 6【Minor】`master-plan` 复现主张加锚 —— **ADDRESSED**

```
$ git diff -U0 70297a5..6eb37e6 -- .../2026-09-16-simos-master-plan.md
@@ -1151 +1151 @@
-...task-11-report.md`；上述数字均可由 `./mvnw clean verify` 原样复现。
+...task-11-report.md`；上述数字均可在**本段所记的提交**上由 `./mvnw clean verify` 原样复现。
```

**锚点是良定义的**——「本段所记的提交」的同段内即写明：

```
$ sed -n '1146,1152p' docs/superpowers/plans/2026-09-16-simos-master-plan.md
**`verify` 结论**（`./mvnw clean verify`，在 `54ef235` 上实测）：...
`.superpowers/.../task-11-report.md`；上述数字均可在**本段所记的提交**上由 `./mvnw clean verify` 原样复现。
$ git cat-file -t 54ef235 && git merge-base --is-ancestor 54ef235 HEAD && echo YES
commit
YES
```
⇒ 唯一候选 referent 是 `54ef235`（同段两次出现，另一处是括号里的「在 `54ef235` 上实测」）；
该提交存在且为 HEAD 祖先 ✓。**改动方向正确**：把无锚的「可复现」收窄成"在它被实测的那个提交上可复现"，
M2 加用例不会再把它变成假话。

**未核实**：`原样复现`四字**本身我没重跑**（按约束不跑 `./mvnw clean verify`）。
间接证据：`17 个测试类 / 156 条用例`、`15 条`、`BugInstance size is 0` 这些数与 `AgentLibAvailabilityTest`
的静态形态自洽（`2 个 @Test` + `1 个 @ParameterizedTest` × `13 条 @ValueSource` = **15 条** ✓）。
⇒ 判 ADDRESSED，但"复现"部分留给重跑者。

---

### 第 7 处（**超出裁定**，控制器已批准）`util-plan` 的 Spec 行同形消歧 —— **ADDRESSED**，且**与第 2 条同口径**

```
$ git diff -U0 70297a5..6eb37e6 -- .../2026-09-16-util-simos-plan.md
@@ -11 +11 @@
-**Spec:** `.../2026-09-16-util-simos-design.md`（M1 spec，已批准）——...
+**Spec:** `.../2026-09-16-util-simos-design.md`（M1 spec；**类型形状已于上一会话批准**，**spec 本体仍「待用户评审」**）——...
```

**两份文档对同一件事同口径**（逐字比对两个半句）：

| 文档 | 新措辞 |
|---|---|
| `master-plan.md:1129` | `（**类型形状已于上一会话批准**；**spec 本体仍「待用户评审」**）` |
| `util-plan.md:11` | `（M1 spec；**类型形状已于上一会话批准**，**spec 本体仍「待用户评审」**）` |

⇒ 两个半句**逐字一致**（含加粗范围与 `「待用户评审」` 的引号形态），
仅**分句符**不同（`；` vs `，`）——而这是**必要**的：`util-plan` 那行前面挂着 `M1 spec；` 前缀，
用顿号式逗号接续才通顺。**实质同口径** ✓，这处改动存在的理由（消除两份文档对同一件事的两种说法）达成。

两处 `git grep -n '已批准'` 在 `master-plan` / `util-plan` 中**均为 0 命中**（exit 1）——旧措辞已彻底清除，无第三种残留说法。

---

## 额外两判

### A. 有没有引入新缺陷（新措辞本身变成不成立的主张）—— **未发现阻塞性新缺陷**

逐条主张的来源核验如下（判据：每个数字/事实都要有当场跑过的痕迹）：

| 新主张 | 来源核验 | 判 |
|---|---|---|
| `5000+ 行` | `wc -l` 四份 = **5463** | 成立 |
| `类型形状已于上一会话批准` | spec `:14` 裁决表第 1 行 + 出处列 | 成立 |
| `spec 本体仍「待用户评审」` | spec `:4` `**状态**：待用户评审` | 成立 |
| `含 M1 Task 字样的有 17 个` | `grep -c 'M1 Task'` = **17** | 成立 |
| `14 条 feat/fix/test/refactor` | `grep -cE '^(feat\|fix\|test\|refactor)'` = **14** | 成立 |
| `3 条 docs(spec) 裁决` | `grep -c '^docs(spec)'` = **3** | 成立 |
| `另 3 个提交不含该字样` | `grep -v` 恰列 3 条 | 成立 |
| `grep -c '^M1 Task' = 0` | **逐字跑该命令**（无 range，覆盖整支 HEAD）→ **0** | 成立（这一条我特意查过，见发现 3） |
| `本段所记的提交` = `54ef235` | 同段写明「在 `54ef235` 上实测」；提交存在且为祖先 | 成立 |
| util-plan「已知分歧各自在对应位置有取代说明」 | 枚举 7 条，`util-plan` 内确证 | 成立 |

**⚠️ 一处 Minor 观察（不在 `.md` 内，故不阻塞关账）**：提交信息里写着「（现测 5462，原数不可复现）」，
而 **本提交自身把 master-plan 增加了净 1 行**（`1309 → 1310`，见 U0 diff：2 删 3 增），
故**四份现值已是 5463**，不是提交信息写的 5462：

```
$ git show 70297a5:<master-plan> | wc -l ; git show 6eb37e6:<master-plan> | wc -l
1309
1310
$ (四份 @70297a5) = 686+1309+409+3058 = 5462      # ← 提交信息里的「现测」，是**改前**测得
$ (四份 @6eb37e6) = 686+1310+409+3058 = 5463      # ← 现值
```
⇒ 该数**测于改前、写入提交信息时已+1**——正是本轮 ① 要治的那个病（"写死的数每改一次就变成假话"）的一次重演。
**但**：(1) 它只出现在**提交信息**与**未入库台账**（`progress.md:1711` 的「当前工作树 = 5462」是评审当时的快照，属历史记录）；
(2) **落地的 `CLAUDE.md` 只写 `5000+ 行`，不含任何会漂的数** ⇒ **不构成 ship 出来的假话**。
判：**Minor，不阻塞**；建议下轮写提交信息时以「量级」替代精确数，或改后复测。

### B. 改动是否越界（只动 `.md`）—— **未越界**

`git show --name-only 6eb37e6` → 3 个 `.md`，**无 `src/`**（`grep -c '^src/\|/src/'` = 0），
**无 `.superpowers/**`** ✓。

---

## 我未能核实的项（明确列出，不以推测填空）

1. **`./mvnw clean verify` 对 `54ef235` 的"原样复现"**（发现 6 的实质）——按约束**未重跑**。
   只核了锚点良定义 + `15 条` 与测试静态形态自洽。**"复现"这半句留给重跑者**。
2. **`task-11-brief.md` 在发现 ⑤ 当时那一版的内容**——brief 未入库且执行中途被改写，
   `git grep 'provider("unit", null)' 70297a5` = **0 命中**，故「当时写 `:42` 是否成立」**原理上不可回溯**。
   本轮只判"改后是否不再失真"，**不判"当时是否写错"**（与评审自己的结论一致）。
3. **`17 个测试类 / 156 条用例`** 这两个数的重跑——同样需 `mvnw`，未跑（不在本轮 6 条裁定范围内，仅作发现 6 的旁证）。

---

## 总判

**可以关账。**

7 条（裁定 6 条 + 越界自报的 1 条）**全部 ADDRESSED**，逐条按裁定的值改到位、且改动范围与措辞无一处偏离；
新增主张**逐条有当场跑过的痕迹**（17/14/3/0/5463/13/118/11/5 全部实跑）；未越界、未 add `.superpowers/**`。
唯一瑕疵是**提交信息里那个 5462**（改后就地变 5463），属"改前测得、改后失真"的 Minor，
**未进入任何落地文档**，不影响关账；建议下轮以量级措辞写提交信息。
