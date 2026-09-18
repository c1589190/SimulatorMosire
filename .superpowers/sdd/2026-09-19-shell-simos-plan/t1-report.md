# M5 T1 报告 — `simos-app` 骨架与装配门面

> 分支 `m5/t1`，worktree `.claude/worktrees/m5t1`，基线 `71d5789`（Batch A 关账：T4 ‖ T2 合并，主树 731 条全绿）。
> 派单：spec `docs/superpowers/specs/2026-09-19-shell-simos-design.md` §2/§3、计划 `docs/superpowers/plans/2026-09-19-shell-simos-plan.md` T1。
> 结束时**不推送**（按派单）。

---

## 一 交付物与改动文件

| 文件 | 内容 |
|---|---|
| `pom.xml`（修改，仅一行模块） | `<modules>` 加 `simos-app`（reactor 六模块 → **七模块**） |
| `simos-app/pom.xml`（新） | artifactId `simos-app`、父 `io.mosire:simos-parent`；依赖照 spec §2.1；**无** enforcer 的 `bannedDependencies`（组合根）；另有 1 条 provided 的 `spotbugs-annotations`（见偏离 4） |
| `simos-app/src/main/java/io/mosire/simos/app/ShellConfig.java`（新） | spec §3.1 的 record + **`String mapId`**（见偏离 1）；缺省 5711/5715/`/mcp`/5713/`agent:external-mcp`/`Map1`；`port=0` 支持；构造期校验 |
| `simos-app/src/main/java/io/mosire/simos/app/Shell.java`（新） | 唯一装配点：`CoreSimos(CoreConfig(..., SimosObjectMapper.create()))` + **3 codec + 8 handler + 1 participant**；`coreSimos()/config()/registeredModuleCount()/close()`；装配只到 spec §3.2 的 CoreSimos 部分（审批/MCP/GUI 留给 T6/T7/T8） |
| `simos-app/src/main/java/io/mosire/simos/app/ShellMain.java`（新） | 解析 `--store/--gui-port/--mcp-port/--approval-port`；起壳 → 打印生效配置与模块数 → shutdown hook 关壳 → `CountDownLatch` 阻塞到 SIGINT |
| `simos-app/src/main/java/io/mosire/simos/app/log4j2.xml`（新，资源） | 控制台 appender、级别 INFO（没有它 log4j2 默认只出 ERROR，"打印生效配置"看不见）；见偏离 5 |
| `simos-app/src/test/java/io/mosire/simos/app/ShellSmokeTest.java`（新） | 4 条：(a) 改名全链 (b) 推进全链 (c) `branches/head` 经壳可用 (d) `close()` 幂等 |
| `.superpowers/sdd/2026-09-19-shell-simos-plan/t1-evidence/**` | 见证据索引 |
| `.superpowers/sdd/2026-09-19-shell-simos-plan/t1-report.md` | 本文件 |

**未碰**：其它模块源码、spec/计划/既有台账文档、既有测试。唯一动过的既有文件是根 `pom.xml` 的一行模块声明。

---

## 二 实测数字

### 2.1 定向（`targeted.log`）

```
./mvnw -pl simos-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ShellSmokeTest' test
→ BUILD SUCCESS，Tests run: 4, Failures: 0, Errors: 0（7 模块 reactor 全 SUCCESS）
```

### 2.2 全量 `./mvnw clean verify`（**首次七模块**，`full-verify.log`）

| 模块 | Tests run | BugInstance |
|---|---|---|
| UtilSimos | 170 | 0 |
| MapSimos | 255 | 0 |
| SocialSimos | 37 | 0 |
| UnitSimos | 122 | 0 |
| CoreSimos | 147 | 0 |
| **SimosApp** | **4** | **0** |
| 合计 | **735** | **0 ×6** |

- `rc=0`；7/7 reactor 条目 SUCCESS；`[ERROR]` 行数 **0**。
- 与基线 **731 = 170/255/37/122/147** 逐模块对差：前五个模块一个没动，`simos-app` 恰 +4 = 全新 `ShellSmokeTest`。
- `BugInstance size is 0` 出现 **6 次**（父 POM 不产 jar ⇒ 六个 jar 模块各一次；M4 时是 ×5）。

---

## 三 变异自证（每轮九道门禁）

装置 `t1-evidence/mutants/mut-round.sh`（照 M4 task-17 骨架，`ROOT` 改本 worktree、模块改 `simos-app`）。九道门禁：干净世界 md5 / 变异体字节不同 / 白名单落成目标类名 `Shell.java` / 清陈旧 `Shell*.class` 逼重编 / `COMPILATION ERROR`=0 且 `Tests run:`≥1 / surefire 报告 mtime 落轮内 / 显示变红处 / `cp` 逐字节还原 / 三处 md5 追加进日志本身。

| 轮 | 变异体（源 = `Shell.java`，orig_md5 `c9a7703a6d83b99dcbed82b6e69363f5`） | mutant_md5 | 实测红点（逐字） |
|---|---|---|---|
| m1 | `start()` 的 codec 循环里 `if ("unit".equals(codec.namespace())) continue;` —— 故意不注册 `UnitCodec` | `b3622da25d684aa73f5fe4dbb8e47b78` | `ShellSmokeTest.renameThroughTheShellCommitsAndReplays:98 » IllegalState checkpoint 信封里有未装配 codec 的模块（信封与本实例的 codec 表不同源）: unit`（另 `advance…:123` 同因） |
| m2 | `coreSimos.register(new UnitTimeParticipant(...))` 改为不注册（表达式语句） | `91cf9ed4b30665e4ced0ae80f4c30d26` | `ShellSmokeTest.advanceThroughTheShellMovesTheUnitAndReplays:137 [① 已抵达 ⇒ position 段写入抵达点 [1,3]（预算 4000 付清两段 1500）]`（`Failures: 1, Errors: 0`） |

两轮均：`rc=1`、`COMPILATION ERROR_lines=0`、`Tests run_lines=2`（有测试真的跑了）、报告 mtime > round_start、还原后 md5 == orig_md5。**0 存活**。

★ **m1 的一处实测修正（不照抄派单预测）**：派单预期 m1 的 rename 走"`Rejected` 而非 `Committed`"。实测**不是**——`UnitCodec` 缺席时，`CoreSimos` 在 `stateLoader.load(base)`（重放创世 checkpoint）阶段就抛 `IllegalStateException`，**根本没走到 handler、更没有 `Rejected`**（`Replay.decodeCheckpoint` 对未装配 namespace 的既定契约，`Replay.java`）。红是真的、且直接由被保护的那行（codec 注册）造成；但**红的形态是异常不是拒绝**。如实记，不改 Core。

---

## 四 取代说明 / 偏离

1. **`ShellConfig` 补 `String mapId`（派单已预告，确认为真缺口）**：spec §3.1 的 record 头没有 `mapId`，而 `UnitTimeParticipant(MovementCost, String mapId)` 构造**必须有**它（`GameMap` 无 id，M2/M3 挂起项）。缺省 `"Map1"`。这是 spec 的漏写，不是执行偏差 ⇒ 取代说明候选。
2. **`TerrainMovementCost.INSTANCE` 而非 `new TerrainMovementCost()`**：派单文字写 `new TerrainMovementCost()`，但其构造器**私有**（唯一实例是 `INSTANCE`）。就地校正，语义不变。
3. **新增 `simos-app/src/main/resources/log4j2.xml`**（交付物清单未列）：不加它 log4j2 用默认配置只出 ERROR，`ShellMain` 的"生效配置 + 模块数"日志不可见。属"可执行体的日志实现归 app 层"（spec §2.1）的自然落地。
4. **新增 `com.github.spotbugs:spotbugs-annotations:4.10.4`（provided）**：`Shell.coreSimos()` 按 spec §3.2 必须交出 `CoreSimos` 引用，SpotBugs 首次七模块 `clean verify` 判 `EI_EXPOSE_REP`（`BugInstance size is 1`，见 §5）。用 `@SuppressFBWarnings` 精确豁免在**该方法**上；注解需 provided 依赖。与 AgentLibMosire/BrainMosire 的 `spotbugs-annotations`（provided 4.10.4）同法。**这是 spec §2.1 依赖表之外的唯一新增依赖**，provided、不进产物。
5. **豁免 `EI_EXPOSE_REP` 的边界**：只作用于 `Shell.coreSimos()`。铁律 2 的护栏不因此松——app 源码仍无任何 `SqliteStore`/`Timeline.appendRevision`/`CheckpointStore`（R1 扫描）与行为面（T8 落地）。
6. **`ShellMain` 只解析四个开关**：`--mcp-path`/`--mcp-initiator`/`--map-id`/`--checkpoint-interval` 暂无开关、取缺省；派单只要求四个。

---

## 五 门禁抓到的事（首次七模块 `clean verify` 的第一跑失败）

首次 `clean verify`（annotation 加入前）**故意不是绿的**：`SimosApp` FAILURE，唯一一条是

```
[ERROR] Medium: io.mosire.simos.app.Shell.coreSimos() may expose internal representation by
        returning Shell.coreSimos [io.mosire.simos.app.Shell] At Shell.java:[line 100] EI_EXPOSE_REP
[INFO] BugInstance size is 1
```

其余六个模块 SUCCESS。这**不是**"门禁装饰"——它真的拦住了。修法是 §四.4/5 的精确豁免；修后 `simos-app` `BugInstance size is 0`。
★ 如实记：那次失败的日志文件被随后的绿跑**覆盖**（同名 `full-verify.log`），上面这行是本会话终端实测输出，**没有独立日志留档**。

---

## 六 我未能核实的

1. **`ShellMain` 从未真正运行过**：`parse` 与 `run`（含 shutdown hook / SIGINT 阻塞）没有用例覆盖，我也没在终端起过它（会永久阻塞 + 需真端口）。它只通过了编译与 SpotBugs/Checkstyle。**"能起、能收到 SIGINT 并关壳"未验**。
2. **`mcp-core` / `mcp-json-jackson2` / `agentlib-mosire` 三个依赖只是"声明即可编译"**：T1 源码尚未 import 它们，未验它们在 M5 后续任务里真能用（连通性实测归 T7）。
3. **`registeredModuleCount()` 的语义边界**：它数的是"codec 注册动作数"，**不**代表 handler/participant 也都注册成功——派单要求"registered module count"，我按 codec 数实现；若下游把它读成"扩展总数"会误解。
4. **封存后行为**：壳一旦 `submit/replay` 封存，`Shell` 并未拦截后续 `register`（由 `CoreSimos` 自身抛 `IllegalStateException`）；壳层无额外护栏，未验。
5. **`close()` 之后仍调 `coreSimos().submit` 的行为**：未验（`SqliteStore` 关闭后的报错形态）。
6. **跨 JVM / 真实文件系统语义**：冒烟测试用 `@TempDir`，未验 storeDir 预置/权限/相对路径形态。

---

## 七 证据索引（`.superpowers/sdd/2026-09-19-shell-simos-plan/t1-evidence/`）

| 文件 | 说明 |
|---|---|
| `logs/targeted.log` | 定向 `ShellSmokeTest`（4/4） |
| `logs/full-verify.log` | 七模块 `clean verify` 绿（rc=0、735、BugInstance ×6=0、ERROR 0） |
| `logs/m1.log` | m1 变异轮（含装置自指的 orig/mutant/restored 三处 md5） |
| `logs/m2.log` | m2 变异轮（同上） |
| `mutants/mut-round.sh` | 九道门禁装置（本 worktree 适配） |
| `mutants/m1.Shell.java` / `m2.Shell.java` | 变异体（与 `Shell.java` 字节不同） |
| `mutants/orig/Shell.java` | 原件快照（md5 `c9a7703a6d83b99dcbed82b6e69363f5`，与工作树逐字节相同） |

---

## 八 提交

单次提交，分支 `m5/t1`，**不推送**。提交信息：
`M5 T1：simos-app 骨架与装配门面 —— ShellConfig/Shell/ShellMain + 冒烟测试 + 变异自证`。
