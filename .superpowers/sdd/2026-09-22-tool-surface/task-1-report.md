# Task 1 报告 — M1 地图组（7 条写工具）

> 状态：**BLOCKED**（环境级，非本任务代码问题）
> 落盘：2026-09-22。停止点由控制器当场指令（"立刻停止一切 Maven 调用…写报告记为 BLOCKED…然后停下等我"）。
> 证据目录：`.superpowers/sdd/2026-09-22-tool-surface/m1-evidence/`

---

## 1. 状态

**BLOCKED**。

**一句话**：本机 `~/.m2` 的 `agentlib-mosire` 是 **126 类**的旧版本，而 HEAD 的 `simos-sd` 需要含
`LlmException.degradable()` / `kind()` 的版本 ⇒ `simos-sd` **编译失败** ⇒ `simos-app`（以及它里面的一切
测试、变异、门禁）**在本机一次都跑不起来**。**本阶段基线在这台机器上本来就是红的**，与我的改动无关。

**我没有写一行实现代码**（原因见 §3）：Maven 连 `simos-app` 都没走到，写下去等于盲写、且无法留下任何验证证据。

---

## 2. 提交

**无。我没有提交任何东西**（`git rev-parse HEAD` 仍是派单时的 `4d0989d2f3f3057fd6a32f5c01c5d4ea286dc965`，分支 `ts/m1`）。

工作区状态（原样粘贴）：

```
$ git status --short
 M .superpowers/sdd/2026-09-22-tool-surface/progress.md
?? .superpowers/sdd/2026-09-22-tool-surface/m1-evidence/
?? .superpowers/sdd/2026-09-22-tool-surface/m4-inventory.md

$ git diff --stat
 .../sdd/2026-09-22-tool-surface/progress.md        | 43 +++++++++++++++++++++-
 1 file changed, 42 insertions(+), 1 deletion(-)
```

**这三条没有一条是我改的生产/测试代码**：

- `progress.md` 的那 42 行新增是**控制器写的 Task 4 段**（内容为 `### Task 4 — M4 读口补齐…`，mtime
  `2026-09-22 04:14:15`，早于我的基线日志 `04:14:26`）。**我全程只读它、没写过它**。
- `m1-evidence/` 是**我的证据目录**（内容见 §3.3）。
- `m4-inventory.md` 是**另一个任务**留下的。

`simos-app/src/main/java/io/mosire/simos/app/tools/write/` 里**只有派单时已有的 8 个文件**
（`AbstractNarrowWriteTool`/`AdvanceTool`/`CommandSubmitTool`/`ForkTool`/`IssueDirectiveTool`/
`SetViewScopeTool`/`StartDecisionTool`/`SubmitVerdictTool`），**没有 `MapXxxTool`**。
`SimosToolSource.java`、三个测试文件、`Shell.java`、`simos-map/**`、`simos-sd/**` **全部逐字未动**。

---

## 3. 我做到哪一步

### 3.1 已完成（读码级，无构建）

1. 读完整简报（237 行）、台账、`IssueDirectiveTool`/`StartDecisionTool`/`AbstractNarrowWriteTool`/
   `ToolSupport`、`SimosToolSource`、三个会红的测试文件、`AppWritePathGuardTest`。
2. **逐条核过 §6 判据 4 的 7 个坏载荷落在哪条域层守卫上**（读 `MapPayloads` /
   `TerrainOperations` / `EdgeOperations` / `RegionOperations` / `PathwayGroupOperations` /
   `PathwayGroup`），确认 7 条文案都能被指定的坏载荷触发、且校验次序不会先撞别的守卫。
3. 设计了 §7(a) 的修法：**写闸循环须记录"它实际覆盖过的名字集合"，再断言该集合 ==
   写工具全集（= 联合名单里读工具名字的补集）**——只有这样才能让 m3（退回 `subList(9,16)`）真的被杀，
   否则修复自身是装饰。
4. 设计了 7 条**各自独立**的坏载荷用例 + **一条正向对照**（7 条合法载荷 ⇒ head 前进）——
   没有正向对照时，"永远返回 REJECTED"的变异体不会被任何一条杀掉。

### 3.2 未完成（被环境挡住）

**创建 7 个类、接线 `addGmWrites`、改 3 个测试文件、跑变异、跑门禁 —— 一件都没做。**
理由：`simos-app` 在本机根本编不出来（§4），此时写实现代码既无法编译、也无法验证，
只会把"未验证的代码"混进工作区，干扰控制器修完环境后的续跑。

### 3.3 证据（我落盘的全部东西）

```
.superpowers/sdd/2026-09-22-tool-surface/m1-evidence/logs/baseline-clean-verify.log
    728 行 / 50828 B / md5 3215ff448bfd935f1a74c9656bbec36d
    （构建输出部分 = 前 50237 字节 / 720 行，md5 d23a2021307c03d5c69b512ef6d57f42；
     该 md5 与字节数已按简报 §10 追加进日志**自身的尾部自指块**）
.superpowers/sdd/2026-09-22-tool-surface/m1-evidence/logs/baseline-rc.txt          （5 B，内容 `rc=1`）
.superpowers/sdd/2026-09-22-tool-surface/m1-evidence/logs/baseline-notes.md
    107 行 / 6313 B / md5 f7abba5c8259c969fb20fae254cc50c6
    （该轮的自指说明 + 现场重算的读数 + 我独立测到的环境事实 + 一个 `unzip` 假阴性坑）
```

---

## 4. 观测到的编译错误（原文 + 命令 + rc）

**命令**（前台执行，第 **1** 次尝试，**未被杀**：日志内 `OutOfMemoryError` / `Killed` 命中均为 0）：

```
mkdir -p .superpowers/sdd/2026-09-22-tool-surface/m1-evidence/logs
./mvnw clean verify > …/logs/baseline-clean-verify.log 2>&1
echo "rc=$?" > …/logs/baseline-rc.txt
```

**rc = 1**。

```
[INFO] Building SDSimos 0.1.0-SNAPSHOT                                    [7/8]
[ERROR] COMPILATION ERROR :
[ERROR] /home/dev/…/simos-sd/src/main/java/io/mosire/simos/sd/adjudication/LlmDecisionAdjudicator.java:[55,13] cannot find symbol
[ERROR]   symbol:   method degradable()
[ERROR]   location: variable e of type io.mosire.agentlib.llm.LlmException
[ERROR] /home/dev/…/LlmDecisionAdjudicator.java:[58,53] cannot find symbol
[ERROR]   symbol:   method kind()
[ERROR]   location: variable e of type io.mosire.agentlib.llm.LlmException
[INFO] SDSimos ............................................ FAILURE [  6.359 s]
[INFO] SimosApp ........................................... SKIPPED
[INFO] BUILD FAILURE
[INFO] Total time:  07:04 min
```

反应堆：`SimulatorMosire`/`UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos` **SUCCESS**、
`SDSimos` **FAILURE**、`SimosApp` **SKIPPED** ⇒ `SUCCESS [` **6/8**。

### 4.1 现场重算的读数（只取模块汇总行 `Tests run:` 相加，不引用任何文档）

| 模块 | Tests run | | 门禁项 | 读数 |
|---|---|---|---|---|
| UtilSimos | 170 | | `[ERROR]` 行数 | **20** |
| MapSimos | **369** | | `BugInstance size is 0` | **×5** |
| SocialSimos | 45 | | `[frontend-gate]` | **0 命中**（app 未跑） |
| UnitSimos | 259 | | `SUCCESS [` | **6/8** |
| CoreSimos | **179** | | 被杀？ | **否**（第 1 次尝试） |
| SDSimos / SimosApp | **未产出** | | rc | **1** |
| **合计（仅已跑的 5 个模块）** | **1022** | | | |

★ `170+369+45+259+179 = 1022`。与台账在案的 WebUI 关账分解 `170/368/45/259/178/129/209` 相比
**map 与 core 各 +1**：成因候选是 `39e1ac7..HEAD` 之间 `wsf2/v3` 系列给
`MapResolverTest`（map）与 `MapRegionEndToEndTest`（core）各加了用例；**未逐条核实**，如实记下。

### 4.2 我独立复核过的根因（只读，均非 Maven）

1. `~/.m2/…/agentlib-mosire-0.1.0-SNAPSHOT.jar` = **126 类**，md5 `4b85536d8d48c041fac21dfb7fb2de02`。
2. `~/ProjectMosire/AgentLibMosire/target/…jar` 与它**逐字节相同**（同 md5）⇒ 简报名录里的
   "本机重建 agentlib"**换不到不同的字节**，修不了这个问题。
3. 全机唯一的 `LlmException.java` 只有两个构造器，无 `degradable()`/`kind()`（源文件 mtime `Sep 18 03:16`）。
4. `~/ProjectMosire` 只有 `main`/`origin/main`；`git log --all -S "degradable" -- AgentLibMosire` **无输出**
   ⇒ 本机任何历史版本都没提供过该 API。
5. 本机最后一次成功的 `simos-app` 产物（main 检出 `target/*-shaded.jar`，`Sep 21 07:51`）内嵌的
   `LlmException.class` **同样只有两个构造器**、且**不含** `LlmDecisionAdjudicator.class`
   ⇒ 本机最后一次成功构建**早于** sd 的 D 阶段。

（与控制器给出的诊断一致：缺的那份在 `ProjectMosire` 远端 `main`（`212f57e`），本机停在 `5141cf9`。）

### 4.3 ★ 附带发现的一个坑（值得记进纪律）

**本机没有 `unzip`**（`which unzip` ⇒ rc=1）。`unzip -l <jar> | grep <类>` 在缺 `unzip` 时
**无输出也不报错** ⇒ 我一度据此得出"shaded jar 里没有 `LlmException.class`"的**假阴性**，
换 `jar tf` 立刻命中。⇒ 与 `ugrep` / `git grep --untracked` / Spotless 那条正则**同族**：
**工具的静默假阴性把"没搜到"伪装成"不存在"**。控制器若在本机做只读取证，请一律用 `jar tf`。

---

## 5. 判据 §6 —— **逐条：未做**

| # | 判据 | 状态 | 为什么 |
|---|---|---|---|
| 1 | 桶正确（7 条在 GM 与 EXTERNAL_WITH_GM，不在 EXTERNAL / DECISION_AGENT） | **未做** | 无实现、无测试、无法运行任何断言 |
| 2 | 名字同源（`name()` == 固定命令类型；7 类型都在 catalog） | **未做** | 同上 |
| 3 | 敏感写（`spec().sensitive()` 真、`gate()` 是 `AskKind.SENSITIVE`） | **未做** | 同上 |
| 4 | 7 条**各自独立**的坏载荷用例（各断言域层文案片段 + head 不变） | **未做** | 同上（7 个用例一个都没写） |
| 5 | 修掉 `SimosToolsTest` 两处 `subList`（判据语义改为"写闸覆盖集 == 写工具全集"） | **未做** | 同上（修法已设计，见 §3.1-3，但一行未落盘） |

## 6. 变异 §8 —— **逐条：未做**

- **m1 桶错位**：未做。**m2 名字错**：未做。**m3 退回 `subList(9,16)`**：未做。
- 原因同上：变异装置要能编译、要能跑测试，本机两样都做不到。
- **没有把任何一条记成"被杀"**（"被杀既不是红也不是绿"，更何况这一轮连编译都没过）。

## 7. 门禁 §9 —— **未做**（附已跑的一次环境性红）

| 项 | 要求 | 实测 |
|---|---|---|
| `./mvnw clean verify` rc | 0 | **1**（第 1 次尝试，未被杀） |
| `SUCCESS [` | 8/8 | **6/8**（sd 编译失败、app 跳过） |
| `[ERROR]` | 0 行 | **20 行** |
| `BugInstance size is 0` | ×7 | **×5**（app 未跑） |
| `[frontend-gate] OK … fail=0` | 有 | **0 命中**（门禁在 `simos-app` 内，未跑到） |

**这不是我跑出的红、也不是基线的红**：它是**本机 agentlib 版本落后**造成的环境红（§4.2）。

## 8. 我未能核实的

1. **本机修完之后，这个基线到底能不能绿** —— 一次都没验过（本机从未跑过 8/8）。
2. **7 条域层拒绝文案的"运行时"可达性** —— 我只做了**读码级**确认（校验次序、文案字面）。
   `MapPayloads` / 各 `*Operations` 的运行时行为**一次都没跑过**。
3. **三个测试文件的现状断言是否真会红** —— 简报 §7(a)(b)(c) 的爆炸半径是**读码推断**，
   我没有在真跑中看到过任何一条红/绿。
4. **`McpPortTopologyTest` 的决策口在加 7 条后是否真的不红** —— 简报要求"跑一遍确认，不要假定"，
   **这一条我没能执行**。
5. **map 369 / core 179 与台账 368 / 178 各差 1 的确切成因** —— 只有文件级线索（§4.1），未逐条核实。
6. **`MapResolverTest` / `MapRegionEndToEndTest` 的用例数**同样未核对（Maven 冻结中）。
7. **`simos-sd` 这份代码在"有的机器上能编过"这件事本身** —— 我没有在别的机器上看到过；
   我是从"台账记着别处 8/8 绿"**推**出来的，**不是我实测的**。
8. **`AppWritePathGuardTest` 的禁字约束在我新类上会不会触发** —— 未做（类还没写）。
9. **`spotless:apply` 的中文折行结果** —— 未做（无新文件）。
10. **`unzip` 缺失是否会连累门禁自身**（例如某插件/脚本依赖 `unzip`）—— 未核实；
    本轮 `clean verify` 跑到第 7 个模块才停下，**没看到与 `unzip` 有关的报错**，但不等于无。

## 9. 下一步（等控制器）

环境修好后，我按简报原地续跑，顺序为：
① 建 7 个类 → ② 接 `addGmWrites` → ③ 改 3 个测试文件（含 §7(a) 的 `subList` 语义修复）→
④ 7 条坏载荷用例 + 1 条正向对照 → ⑤ 迭代期只跑相关模块 → ⑥ 变异 m1/m2/m3 → ⑦ 全量 `clean verify` → ⑧ 续写本报告。
