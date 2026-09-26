# SDD 读数 —— S1 阶段 1 验收（Task 3）

判据来源：`docs/superpowers/plans/2026-09-26-s1-stage1-identity.md` §Task 3
实验日期：2026-09-26

## 实验方法

| 项 | 基线（换装前） | 现役（换装后） |
|---|---|---|
| 源码 | `f971b66`（worktree `/tmp/s1-base`） | `0f0f74e` |
| jar | `/tmp/s1-base/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`（15:09 构建） | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`（15:08，`clean verify` 产物） |
| 端口 | GUI 5867 · MCP 5765 · 审批 5763 | GUI 5857 · MCP 5755 · 审批 5753 |
| store | `~/.claude/jobs/s1a-stage1/base` | `~/.claude/jobs/s1a-stage1/current` |

**同一脚本**（`s1a_probe.py`）：种三国 → `simos.advance` 到 tick 240 → 逐格 dump 两份读数
（`/api/social/population`（含人口 + 劳动块）+ `/api/economy/hex`）。

`randomization.enabled` 出厂 `false` ⇒ 取 `config/worldgen/v17levant-nations.json` 的文档硬值；
两国 dump 的 seed 逐国一致（14440 / 14441 / 14442），**rev=5 · tick=240 · 799 格** 三项全同。

★ 收工一律按 **PID**（`ps -eo pid,cmd | grep simos-shaded` ⇒ `kill`），未用 `pkill -f`；curl/urllib 全程绕开本机代理。

## 判据 I1.1 —— 逐格人口 / 劳动供给 / 劳动配额**逐值不变**：★ **成立（差 0）**

`python3 s1a_diff.py base.json current.json`：**逐格差异 0 / 799 格**，且两侧格集完全相同（各 0 个独有格）。

**做法**：把基线的**阶层标识符**归一化成新词后，要求两份 dump **逐字节相等**（不是抽样比对，
是整份 JSON 的 `==`）。

归一化命中（只施于基线）：

| 位置 | 规则 | 次数 |
|---|---|---|
| 规范串（`ClassKey` / `DebtId`） | `\|peasant` → `\|poor_peasant` | 1486 |
| 同上 | `\|middle` → `\|middle_peasant` | 1138 |
| 同上 | `\|rich` → `\|rich_peasant` | 1058 |
| JSON 字符串值（槽位 id） | `"peasant"` → `"poor_peasant"` | 3598 |
| 同上 | `"middle"` → `"middle_peasant"` | 3598 |
| 同上 | `"rich"` → `"rich_peasant"` | 3598 |

⇒ 合计 **14,476 处**词表替换，**其余一个字节都没变**。

★★ **这条比判据本身更强**：dump 里不只有人口与劳动，还有库存、出货、流水、债务、危机灯、
出生/死亡、劳动占用率……**全部都逐值相同**。也就是说换装是**纯粹的标识符替换**，
没有碰到任何一条算式。

★ 佐证：799/799 格的劳动块非空，占用率分布 `{859, 860}`‰（两份 dump 同）。

★★ **一处方法论自纠**：`s1a_diff.py` 首版的规范串规则前瞻字符集只写了 `[|>]`，
漏了债权人位置后面跟的 `-`（`…|middle-grain`）⇒ 报出 170 格**假差异**。
补 `-` 后归零。★ 记这一笔是因为：**假差异与真差异在报告里长得一样**，
若不追查就会把"我的工具没归一化干净"误读成"换装改了行为"。

## 判据 I1.2 —— 同一 cohort 兼两个 activity 只有一个身份（V9）：★ **未达成（按计划收窄，属阶段 4）**

计划开头已明写：**`ClassKey → CohortKey` 不能独立做**（去掉 industry 后 `weave@hex|*` 那四行就不存在了，
而它们正是布的唯一落点）⇒ 并入**阶段 4**（与产出归 operator 一起）。故本阶段**注定**不满足 I1.2。

★ 但**量出来了基线**，供阶段 4 前后对比：

| 读数（tick 240 · 799 格） | 值 |
|---|---|
| 产业数合计 | 1799 |
| 阶层行数合计 | 7196 |
| **平均** | **4.00 行 / 产业** |
| 产业 2 个的格 ⇒ 8 行 | 598 格 |
| 产业 3 个的格 ⇒ 12 行 | 201 格 |

⇒ "行数随产业线性增长"**依然成立**，且斜率恰是词表大小 4。阶段 4 的目标就是把这条斜率**打掉**
（同一 cohort 在两个 activity 里只留一个身份）。

## 判据 I1.3 —— `ClassSlotId` 不出现在新领域 API 的签名里：★ **成立**

```
grep -rn "ClassSlotId" simos-*/src/main | grep -vE ':\s*\*'   ⇒  0 处
```

★ **诚实边界**：不加 `grep -v` 过滤时有 **1 处**，在 `SocialClassId` 的**类注**里
（"为什么必须与它取代的 `ClassSlotId` 分开"）—— 那是对**已删除类型**的历史引用，
**不是签名**。★ 保留它的理由：不写这一句，后来人就不知道这个新类型**为什么存在**。

★ 顺带修掉一个真缺陷：该处的 `{@link ClassSlotId}` 在被引类型删除后是**悬空 javadoc 链接**
（`clean verify` 没拦住 —— 本仓没开 javadoc lint）。已改成 `{@code ClassSlotId}`。

★ 另一处残留 `EconomyInvariantsTest.rejectsDuplicateClassSlotIds` 是**测试方法名**（`ClassSlot` 仍在，
"重复的 slot id"这个说法照旧准确），**未改**：纯改名是噪声，收益为零。

## 本读数的边界（诚实清单）

- **tick 240 = 2 个周期**：覆盖了播种 / 收获 / 消费 / 缺粮借粮 / 计息 / 债权序列 / 死亡。**未覆盖**更长的级联
  （人口崩溃后的形态）—— 那要 600+ 天，与本阶段"标识符替换"的命题无关。
- **归一化是我的规则、不是被测物的**：I1.1 的强度**取决于那 6 条规则是否恰好覆盖了全部标识符位置**。
  佐证是"归一化后**逐字节**相等" —— 若还有漏网的旧词位置，它会以差异的形式显出来（首版正是被这样抓到的）。
- **现役 jar 与最终提交差一处 javadoc**（`SocialClassId` 类注，实验后才发现并修的悬空链接）。
  注释不进 class 文件 ⇒ **不影响本次实验的任何字节**。
- **未测**：旧档读入（按 spec §十.4 的裁定不做迁移，旧 revision 的 `ClassKey` 规范串第二段对不上）。

## 证据落点

| 物 | 位置 | 入库 |
|---|---|---|
| 两份逐格 dump（各 ~5.7 MB） | `~/.claude/jobs/s1a-stage1/{base,current}.json` | **否**（太重；sha256 见下） |
| 比对实录（含两份 dump 的 sha256 + `s1a_diff.py` 原样输出 + 退出码） | `diff-run.txt`（同目录） | 是 |
| 探针与比对脚本（可复现整套实验） | `s1a_probe.py` · `s1a_diff.py`（同目录） | 是 |

```
sha256(base.json)    = 2d3254b8ea1cbcaf354da4552f1f3ccd401ebc9c9aa32dc56ae9a042fe457ac0
sha256(current.json) = b3a9ea3bcd22e1c0e7ed398935935421a714a4f7d0d8e899f48e1347185c0caa
```
