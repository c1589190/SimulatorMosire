# M4 Task 17 —— 关账报告（**按期收口，M4 未完成**）

> 时限出自用户 2026-09-18 原话：「自己写开发计划然后自己派Agent执行吧，**直接干到M4阶段完成，或者早上8:30**」
> ——取**先到者**。本报告是**按期收口**，不是完成宣告。**M4 的 17 个任务里完成 5 个**（Task 1~5），
> Task 17（本关账）本身也是任务之一。
>
> 主树 `feat/adr1-core-scope` **冻结于 `a2bc3ce`**（2026-09-18 07:58）。

---

## 一、已交付的部分（有提交、有日志、可复核）

| 任务 | 内容 | 提交 |
|---|---|---|
| 1 | 契约收敛（spec §十） | `d1ad67b` |
| 2 | `util.spi` 五类型 + `package-info` | `d1ad67b` |
| 3 | JSON 地基：`SimosObjectMapper` 单点装配 + `FieldDelta` 类型信息 + Map/Social/Unit 三个 `Codec` | `e874542`、`7ca7109`、`0c98c2a`；合并 `acfcb14` |
| 4 | `WorldChangeSet` + `Envelope`（C26 逐字节信封）+ R1 四实现者守卫 | `b12f691`；合并 `068769d` |
| 5 | `SqliteStore` + R2/R13/C23 护栏自证 | `6b95973`；合并 `2ec17ff` |

前置：`91fd1cc`（ADR-1，Core 的 main scope 收窄）、`ce98212`（M4 依赖预置：util 加
jackson-datatype-jdk8，core 加 sqlite-jdbc 与日志实现）。

### 1.1 最终 verify（**本次关账的权威证据**）

命令 `./mvnw clean verify`，工作树 = 主树仓根，HEAD = `a2bc3ce`。
日志已入库：`task-17-evidence/final-freeze-verify.log`。

| 项 | 实测值 |
|---|---|
| 返回码 / 结论 | **rc=0 / `BUILD SUCCESS`** |
| 总耗时 / 结束时刻 | **04:20** / `2026-09-18T07:57:54+08:00` |
| 反应堆 | 6/6 模块 SUCCESS（parent 14.239s、util 01:05、map 01:24、social 31.264s、unit 33.897s、core 29.574s） |
| 用例 | **168 / 254 / 36 / 74 / 36 = 568**，Failures **0** / Errors **0** / Skipped **0** |
| SpotBugs | `BugInstance size is 0` × **5**（五个 jar 模块全清） |
| ERROR / WARNING 行 | **0 / 1**（唯一 WARNING 是父 POM 无 class 可查而跳过 SpotBugs，属既有常态） |
| 是否干净构建 | **是**——跑的是 `clean verify`，不是增量，排除了 `target/` 陈旧产物冒充绿 |

### 1.2 ★ 一条自我更正（必须留在档案里）

本次会话早前，我按**记忆**写过一次「主树 merge 后 verify 绿：BUILD SUCCESS、4:38、568 条用例」。
收口时去查证：`/tmp/m4verify/` 下**唯一**一份日志 `main-1-5.log` 是
**07:44:31 的 `BUILD FAILURE`**（spotless 打回了**控制器自己手工折行**的 javadoc）。
那份「绿色」**没有任何日志支撑**。

⇒ 该数字**作废**，§1.1 是**当场跑出来并落盘**的那组，取代它。
⇒ 这正是 CLAUDE.md 形态 5 的又一次实例：「我验过了」与「我记得是这样」必须分开；
**没当场跑过的期望输出，不许写进交给别人当依据的文档**。

---

## 二、本次会话的独立发现（不是搬砖）

1. **R15 扫描装置在 git worktree 下**恒绿**（已修，四格实测）。**
   `RepoSourceScan` 按**绝对路径**的每个名字元素判 `startsWith(".")`，而 worktree 的绝对路径含
   `.claude/worktrees/…` ⇒ **全部**文件被滤掉、扫描 0 命中、断言恒真、用例全绿、构建成功，
   **没有任何症状**。四格实测（`-Duser.dir` 驱动真类，刻意不用 `cd`）：

   | 装置 | 主树 | worktree `b1` |
   |---|---|---|
   | 修前 | 44 | **0** ← 缺陷复现 |
   | 修后 | 44 | **45**（= 44 + B1 新增的那个 `util/json` 文件） |

   修后 worktree 转 45 这件事本身就是**修复生效**的证据（它看见了 B1 的工作）。
   修 `50921bc`；教训写入 CLAUDE.md 形态 1 与裁定 23。**★ 本次所有在 worktree 里跑的 R15 结论一律作废。**

2. **裁定 14 撤回。** 该裁定把 `Region.hexes` 的 `Set.copyOf` 记为「M2 疑似缺陷」——**是凭推理写的，
   没读码**。当场读码：`Region.java:14` 的 javadoc **明写这是有意的**（集合语义，迭代序不该被依赖），
   且 `RegionBoundary.of` 有**承重的规范化补偿**（`canonicalRing` + 环表按首点排序）⇒
   内容相等与散列序无关。**不是缺陷，不呈报。** 剩下的只是「JSON 字节跨 JVM 不稳定」这条已知代价，
   已被裁定 12/13 覆盖。★ 原裁定的错误本身，就是它所引用的那条失效形态的实例。

3. **spec 两处按实测回填**（`32c4546`）：〇.3 第 9 条与 §二 都写着 enforcer 白名单「要一并改，
   否则构建失败」——**实测为假**（`simos-util/pom.xml:52` 的 enforcer 只有 `bannedDependencies`
   的 `excludes`、**无 `includes`**）。§6.2 的事务写法在 sqlite-jdbc 3.53.4.0 上**跑不起来**
   （`setAutoCommit(false)` + `BEGIN IMMEDIATE` 抛 `cannot start a transaction within a transaction`），
   已改成「保持 autoCommit 出厂值 + 显式 SQL 边界」并附两探针实测表。

4. **Bash 的 `cd` 会持久改会话 cwd —— 本轮触发两次，两次都是控制器造成的。**
   第一次最危险：命令以 `cd …/worktrees/b1` 开头，而**紧接着的操作就是 `git merge m4/b1`**——
   若没发现，合并会落在分支 `m4/b1` 上，且**输出完全正常**。第二次是 `cd …/specs` 配 grep。
   ⇒ 规则从「命令里出现 `cd .claude/worktrees` 即为违规」**收紧为**：**任何 `cd` 都会改会话 cwd**；
   每次 git 写之前无条件跑 `pwd && git rev-parse --abbrev-ref HEAD && git rev-parse --git-dir`
   （`.git` = 主树；返回**文件路径** = worktree）。记裁定 26。

5. **第三次 503 是控制器自己造成的。** 我看到没有 Maven 进程就断定「CPU 现在是闲的」，
   在**两个 agent 都活着**的时候起了合并后的 verify ⇒ Task 16 的 agent 死于
   `503 system cpu overloaded (current: 99.3%, threshold: 90%)`。
   这直接违反本台账自己测出来的结论（「每次 agent 回合都要它生成 token，而它和 Maven 抢同样的 2 个核」）。
   **未丢东西**（该 agent 当时写盘数为 0），但这是我的操作失误，如实记。

6. **`unzip`/`jar` 不在 PATH 被读成了「jar 里没有」。** `unzip -l … | grep -c "util/spi/"` 打出 `0`，
   我差点读成「该 jar 缺 `util/spi`」。实测 `command -v unzip` **不存在**，且 `jar` 也**不在**
   （要先 `export JAVA_HOME`）。导出后：`util/spi` = **9**、`util/json` = **0**。
   ⇒ 教训：**先 `command -v <工具>`，别把工具的失败读成被测对象的属性**（裁定 27）。
   顺带坐实裁定 6 的 `-am` 是**硬要求**：`~/.m2` 里那个 jar **确实**没有 `util/json`。

7. **worktree 只移树、留分支** ⇒ 下次 `git worktree add -b` 失败，且**诱导「复用旧分支」这个
   陈旧基线陷阱**（`m4/b6` 停在 `2ec17ff`，缺 Task 3 的 json 与 Task 4 的 `WorldChangeSet`，
   但树看起来**完全正常**）。本轮已改为 `git branch -D` 后重建。记裁定 25。

---

## 三、未完成 —— **带裁定的遗留条目**

> 格式：任务号 / 依赖已满足到哪一步 / 下一个该派什么 / 分支（若有半成品）/ 停在哪一步。
> ★ 一律不写成「待办」——**待办**读起来像「没开始」，**遗留条目**要说清**为什么现在派不了**。

### 遗留 1 —— **Task 6 `Timeline`（临界路径的咽喉）**
- **依赖已满足**：`SqliteStore`（Task 5）✅ 已合并 `2ec17ff`。
- **分支 / 工作树**：`m4/b6` @ `.claude/worktrees/b6`，基线 `acfcb14`。
- **停在哪一步（08:13 实测，**取代**早前的观测）**：该 agent **已提交 `ca5b446`**——
  `Timeline.java`（414 行）、`TimelineTest.java`（291 行）、`RevisionRow.java`（47 行），共 **752 行新增**；
  证据在 `.superpowers/sdd/…/task-6-evidence/`（`timeline-test-green-run.log`、`m1-round.log`、
  `m2-round.log`、`mutants/`、`orig/`、`reposourcescan-spotless-churn.diff`、Jackson mixin 探针）
  —— ★ **该目录仍是 untracked，未入库**。
  **基线确为绿**：`task-6-evidence/timeline-test-green-run.log` 实测
  `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`。
  （★ 另有一份 08:11 的 surefire 报 **9 errors**——那是**变异体**那一轮，不是基线；两者别混。）
  变异轮已跑到 m1/m2：m1 在**受保护的那一行**（`hasCheckpointFollowsTheThreeCriteria:203`）准确变红，
  且 `COMPILATION_ERROR_COUNT=0`、有 MD5 自证与干净世界复位。
  **但 `task-6-report.md` 始终没写出来**，且收口时该 agent **仍在跑 m2 轮**。
- ★ **它看起来绿，我仍然没合——理由**：
  (a) 闸门是「**报告文件存在**才算完成」，而报告不存在 = **作者本人没自陈完成**，且**它还在改同一个文件**；
  (b) 合并后**必须重跑一次主树 verify**（刚测过是 4:20 的干净构建），而收口只剩几分钟——
  一旦红了就是**在截止点上把已验证的主树弄坏**，比不合并**糟得多**。
  合并本身只是一次 `--no-ff` + 一次 verify 的事，留给下一轮成本极低。
  **不在压力下临场改自己预先钉死的闸门**（这正是 08:0x 钉时间闸时要防的那件事，见裁定 29）。
- **下一个该派什么**：① 让该 agent 补齐 `task-6-report.md`（自测命令 + 绿/红如实）；
  ② 把 `task-6-evidence/` 一并 `add` 入库（**逐个文件 add，不用 `-A`**）；
  ③ 然后 `--no-ff` 合并 `m4/b6` + 主树跑一次 `./mvnw clean verify`。
- ★ **它卡着 7、8、9 三个任务**，是本阶段最该先拿下的。
- ★ **一处已知的无害冲突点**：`m4/b6` 基线 `acfcb14` **早于** spotless 修复 `9e59f5d`，
  故该分支里跑 `spotless:apply` 会把 `RepoSourceScan.java` 重新折行一次（其 worktree 里
  观测到 `M simos-util/…/RepoSourceScan.java`）。**同一输入的格式化是确定性的**，两侧结果逐字节相同
  ⇒ 合并**不会冲突**。该 agent 已把这段 churn 存证为 `reposourcescan-spotless-churn.diff`。

### 遗留 2 —— **Task 16 的 Step 1/2（两个 SPI 类，**可立即派发**）**
- **依赖已满足**：`TimeParticipant`/`CommandHandler`（Task 2）✅、`UnitCodec`（Task 3）✅、
  M3 的 `UnitMoves.evaluate` / `UnitOperations` ✅。**与 Task 6 无依赖关系**。
- **产物**：`simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java`（`namespace()=="unit"`）
  与 `RenameUnitHandler.java`（`type()=="unit.RenameUnit"`），外加各自用例。
- **分支 / 工作树**：`m4/b16` @ `.claude/worktrees/b16`，基线 `acfcb14`，**零提交**（该 agent 被 503 杀掉，
  **未写盘**，无损失）。**可复用该分支，也可弃之重开。**
- ★ **Step 3（`RealmEffectEndToEndTest`，判据四）不要一起派**：它要 `CoreSimos`（Task 13），
  按**裁定 20** 必须留到 13 之后。

### 遗留 3~11 —— 依赖链上被堵住的任务（**现在派了也只能空转**）

| 任务 | 卡在谁身上 | 说明 |
|---|---|---|
| 7 `CheckpointStore` | **6** | `Consumes: Timeline.hasCheckpoint`（6）、`Envelope`（4） |
| 8 `Replay` | **6、7** | `Consumes: Timeline`（6）、`CheckpointStore`（7）、`Envelope`（4）、`ModuleCodec` 表（3） |
| 9 `CommandRegistry` + `CommandBus` | **6** | `Consumes` 里明列 `Timeline`（6）——★ 见裁定 24 |
| 10 乐观并发两处检查 + R7 | 9 | |
| 11 可观测性 + R6 | 9 | |
| 12 两阶段推进六步 + R9/R10/R14 | 10、11 | |
| 13 `CoreSimos` 装配门面 | 8、12 | **判据一/三/四的端到端都要它** |
| 14 判据一（分岔端到端） | 13 | |
| 15 判据三（真实并发加固） | 13 | |

### 遗留 4 —— **裁定 24：`C1 ‖ C2` 从来没有并行过**
计划把 Task 9 与 Task 12 安排成并行，但 **Task 9 自己的 `Consumes:` 一行里就写着 `Timeline`（6）**，
而 Task 12 在它下游。⇒ 该并行切分**从未成立**。**另有一处 9↔12 的接缝未解**：
`submit(AdvanceTime)` 指向 Task 12，而 Task 12 排在后面。**续派前必须先裁决这条接缝。**

### 遗留 5 —— M4 的四条判据**一条都还不能评**
判据一/三/四要 Task 13~16，判据二要 Task 11。**本报告不对 M4 判据作任何结论**——
没跑到就是没跑到，不写成「部分满足」。

---

## 四、我**未能核实**的（照实列，不含糊）

1. **Task 6 在 08:0x 之后的实际状态**：我只观测到 `timeline/` 目录被创建，**没读它的内容**，
   也没看到它的自测结果。它的分支**是绿是红一律未知**。
2. **Task 6 的 agent 是否在 08:12 前提交**——本报告定稿时该 agent 仍在运行；
   若它在此后提交，**主树不会被改**（已冻结），其分支按「遗留 1」续派。
3. **Task 16 被 503 杀掉的那个 agent 有没有留下任何 `/tmp` 下的探针产物**——我只核实了
   **worktree 零提交、工作树干净**，据此判定**无损失**。
4. **`~/.m2` 里的 `agentlib-mosire` 是否足够新**：我只核实了「06:16 之后没有 install」与
   「`jar tf` 里 `util/json` = 0、`util/spi` = 9」。**本机构件是否与 `~/ProjectMosire` 源一致，未核。**
5. **M4 计划里除已执行的 1~5 之外，其余任务的 bite-sized 步骤是否仍然成立**——特别是
   裁定 24 提到的那条 9↔12 接缝。**未逐条复核。**

---

## 五、续派的推荐顺序（一句话）

> **先 Task 6**（它一个人卡着 7/8/9 三个任务），**同时并行 Task 16 的 Step 1/2**（与 6 无依赖）——
> 但注意本机 **`nproc=2`**，而 127.0.0.1:3000 上的本地推理网关**与 Maven 抢同样的两个核**：
> **并发上限是 2**，且**不要在 agent 活着的时候跑全量 verify**（本会话已因此杀掉过一个 agent）。
