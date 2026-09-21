# M1 证据 · 基线轮说明（自指记录）

> 实现者落盘于 2026-09-22。**本文件只记"我跑过的那一轮"的身份与读数**，不含任何来自文档的现成数字。

## 1. 这一轮是什么

| 项 | 值 |
|---|---|
| 命令 | `./mvnw clean verify` |
| 工作目录 | `/home/dev/SimulatorMosire/.claude/worktrees/ts+m1` |
| 形态 | **前台**执行；**第 1 次尝试**；**未被杀**（日志内 `OutOfMemoryError` / `Killed` / `BUILD FAILURE` 三个串：前两个命中 **0**，`BUILD FAILURE` 命中 **1**） |
| rc | **1**（见同目录 `baseline-rc.txt`，内容 `rc=1`） |
| 树 | 分支 `ts/m1`，HEAD `4d0989d2f3f3057fd6a32f5c01c5d4ea286dc965` |
| 耗时 | `Total time: 07:04 min`（424 s） |
| 日志 | `baseline-clean-verify.log` |

## 2. 日志的自我指认（自指链）

- 构建输出部分（前 **50237** 字节 / **720** 行）的 md5 = **`d23a2021307c03d5c69b512ef6d57f42`**
  ⇒ 可用 `head -c 50237 baseline-clean-verify.log | md5sum` 复核。
- 该 md5 与字节数**已写入日志自身的尾部自指块**（8 行补记），故日志不再悬空：
  读日志即可知道"我是哪份字节"。
- 追加自指块**之后**的整份日志：**50828** 字节 / **728** 行，md5 = **`3215ff448bfd935f1a74c9656bbec36d`**。
- 自指块**只追加、未改动**构建输出的任何一个字节（`head -c 50237` 的 md5 即证）。

## 3. 现场重算的读数（不引用任何文档）

### 3.1 反应堆（8 个 project）

```
[INFO] SimulatorMosire .................................... SUCCESS [ 16.671 s]   [1/8]
[INFO] UtilSimos .......................................... SUCCESS [01:25 min]   [2/8]
[INFO] MapSimos ........................................... SUCCESS [02:02 min]   [3/8]
[INFO] SocialSimos ........................................ SUCCESS [ 38.548 s]   [4/8]
[INFO] UnitSimos .......................................... SUCCESS [01:06 min]   [5/8]
[INFO] CoreSimos .......................................... SUCCESS [01:26 min]   [6/8]
[INFO] SDSimos ............................................ FAILURE [  6.359 s]   [7/8]
[INFO] SimosApp ........................................... SKIPPED
```

`SUCCESS [` 命中 **6/8**（台账要求 8/8）。

### 3.2 用例数（只取模块汇总行 `Tests run:`，即**不含** `-- in <类名>` 的那些行）

| 模块 | 日志行 | Tests run |
|---|---|---|
| UtilSimos | 124 | 170 |
| MapSimos | 268 | **369** |
| SocialSimos | 344 | 45 |
| UnitSimos | 450 | 259 |
| CoreSimos | 617 | **179** |
| SDSimos | — | **未产出**（编译失败，surefire 从未启动） |
| SimosApp | — | **未产出**（SKIPPED） |
| **合计（仅已跑的 5 个模块）** | | **1022** |

`170 + 369 + 45 + 259 + 179 = 1022`（`paste -sd+ | bc` 现场算得）。

★ 与台账在案的 WebUI 关账分解（`170/368/45/259/178/129/209`）相比，**map 与 core 各 +1**。
**成因候选（未逐条核实）**：`39e1ac7..HEAD` 之间 `wsf2/v3` 系列对
`simos-map/src/test/.../MapResolverTest.java` 与 `simos-core/src/test/.../MapRegionEndToEndTest.java`
各加过用例。**属"数字对不上就记下来"的项，不是本轮 BUILD FAILURE 的成因。**

### 3.3 门禁项

| 项 | 读数 |
|---|---|
| `[ERROR]` 行数 | **20** |
| `BugInstance size is 0` | **×5**（parent 无 spotbugs；sd/app 未跑到） |
| `[frontend-gate]` | **0 命中**（该门禁在 `simos-app` 内，本轮 SKIPPED） |

## 4. 失败原文（逐字，来自日志 `:677-711`）

```
[INFO] Building SDSimos 0.1.0-SNAPSHOT                                    [7/8]
[ERROR] COMPILATION ERROR :
[ERROR] .../simos-sd/src/main/java/io/mosire/simos/sd/adjudication/LlmDecisionAdjudicator.java:[55,13] cannot find symbol
[ERROR]   symbol:   method degradable()
[ERROR]   location: variable e of type io.mosire.agentlib.llm.LlmException
[ERROR] .../LlmDecisionAdjudicator.java:[58,53] cannot find symbol
[ERROR]   symbol:   method kind()
[ERROR]   location: variable e of type io.mosire.agentlib.llm.LlmException
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.16.0:compile (default-compile) on project simos-sd: Compilation failure
```

## 5. 我（实现者）当场独立测到的环境事实（只读，均非 Maven）

| # | 事实 | 证据 |
|---|---|---|
| 1 | `~/.m2` 的 agentlib jar = **126 类**，mtime `2026-09-20 03:21:41`，md5 `4b85536d8d48c041fac21dfb7fb2de02` | `jar tf … \| grep -c '\.class$'` = 126；`md5sum` |
| 2 | `~/ProjectMosire/AgentLibMosire/target/…jar` 与该 `~/.m2` jar **逐字节相同**（同一 md5、同一 mtime） ⇒ **本机重建也得不到别的字节** | 同上 |
| 3 | 全机唯一一份 `LlmException.java`（`~/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/llm/`）**只有两个构造器**，无 `degradable()` / `kind()`；源文件 mtime `Sep 18 03:16`（自那之后没被动过） | `cat -n` 全文 13 行 |
| 4 | `~/ProjectMosire` 只有 `main` / `origin/main` 两条 ref；`git log --all -S "degradable" -- AgentLibMosire` **无输出** ⇒ 本机任何历史版本都没有过该 API | `git branch -a`、`git log --all -S` |
| 5 | 反应堆里最后一次成功的 `simos-app` 产物（main 检出 `target/*-shaded.jar`，`Sep 21 07:51`）内嵌的 `io/mosire/agentlib/llm/LlmException.class` **同样只有两个构造器**，且**不含** `LlmDecisionAdjudicator.class` ⇒ 本机最后一次成功构建**早于** sd 的 D 阶段 | `jar xf` + `javap -p`；`jar tf \| grep` |
| 6 | **本机没有 `unzip`**（`which unzip` rc=1） | 见下"坑" |

### ★ 坑（本轮实测，值得记进纪律）

本机 `unzip` **不存在**（CLAUDE.md 已写过"`unzip` 并非每台都有"，本轮第一次真被它咬）：
`unzip -l <jar> | grep <类>` 在缺 `unzip` 时**无任何输出、也不报错** ⇒ 我一度据此得出
"shaded jar 里没有 `LlmException.class`"的**假阴性**；换 `jar tf` 立刻命中。
⇒ 与 `ugrep` / `git grep --untracked` / Spotless 那条正则**同族**：**工具的静默假阴性把"没搜到"伪装成"不存在"**。

## 6. 本轮**未**产生的东西

- 用例数：**SDSimos / SimosApp 一个都没有**（前者编译失败、后者跳过）。
- 前端门禁：**未跑**。
- 判据 §6、变异 §8：**未做**（见 `task-1-report.md` §4/§5）。
