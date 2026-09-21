# shaded jar 关闭路径缺 reactor-core？——查清、修复与自证

> 任务：修「shaded jar 关闭路径缺 `reactor-core`」并重新起 `5817`、诊断 `5818` hung。
> 日期：2026-09-21。分支 `fix/shaded-jar-guard`（worktree `.claude/worktrees/shade-fix`，基线 `14e83f5`）。
> 实现提交短 SHA：见本文件同目录 `commit.txt`。

## 〇 结论（先读这一段）

★★ **派单的前提被实测推翻**：shaded jar **一直含 `reactor-core`**，
`reactor.core.publisher.LambdaMonoSubscriber` 就在 jar 里（`javap` 能加载）。
真因是另一件事，而且**同时解释了 5817 与 5818 两个症状**：

> **运行中的 JVM 读到了被「就地重写」的 jar。**
> `mvn package`（shade）每次都会重写 `target/…-shaded.jar`；JVM 打开 jar 时缓存了
> 中央目录里的**条目偏移量**，文件被就地重写后偏移全错位 ⇒ 之后**任何尚未加载的类**
> 都会 `ClassNotFoundException` / `NoClassDefFoundError`。**哪怕新 jar 里那些类都在。**

- **5817**：20:30 起，22:42:55.963 jar 被重写，22:42:56.008 收到停止信号 ⇒
  关闭路径首次懒加载 `reactor.core.publisher.LambdaMonoSubscriber` ⇒ 炸在 `Shell.close`。
- **5818**：15:09 起（旧构建，日志里还是"演示世界/12 工具"），一次重写后，
  GUI 请求路径首次懒加载 `org.apache.logging.log4j.message.ParameterizedMessage` ⇒
  访问日志写不出 ⇒ 请求全 `-1` ⇒ 客户端拿到空响应（`curl` = `000`）＝"hung"。
- 两者**同一个缺陷**，只是"重写后第一个被懒加载的类"不同。

**修**（三件，见 §四）：① Maven `verify` 阶段加 **jar 内容不变量守卫**（构建期拦住
"reactor 真的掉出 shaded jar"这种未来漂移）；② `tools/run-shaded.sh` **快照运行器**
（把 jar 复制成进程独占的一份再起——**这才是那条真因的修复**）；③ `tools/shaded-close-check.sh`
**真起进程 + 真 SIGTERM 的端到端验收**（不在 Maven 门禁里，脚本 + 证据）。

## 一 查清（GOAL 1）

### 1.1 `LambdaMonoSubscriber` 由谁提供、怎么进来

| 问题 | 实测答案 |
|---|---|
| 哪个构件 | **`io.projectreactor:reactor-core`** |
| 版本 | **3.7.0** |
| 直接还是传递 | **传递**——`simos-app` 的 pom **没有**声明 reactor |
| 经谁进来 | **`io.modelcontextprotocol.sdk:mcp-core:2.0.1`**（官方 MCP Java SDK） |

`./mvnw -pl simos-app dependency:tree` 实测：

```
+- io.modelcontextprotocol.sdk:mcp-core:jar:2.0.1:compile
|  \- io.projectreactor:reactor-core:jar:3.7.0:compile
```

★ 附带核实：**`mcp-bom:2.0.1` 并不管理 reactor**（`grep -i reactor mcp-bom-2.0.1.pom` = 无命中），
所以想"显式补依赖"就得自己钉版本——**本次没补**，理由见 §四.0。

### 1.2 为什么"shade 没打进去"是伪命题

`simos-app/pom.xml` 的 shade 配置**没有**任何会丢 reactor 的 excludes，`filters` 只排除：

```xml
<exclude>META-INF/*.SF</exclude>
<exclude>META-INF/*.DSA</exclude>
<exclude>META-INF/*.RSA</exclude>
<exclude>module-info.class</exclude>
```

`ServicesResourceTransformer` 与 `ManifestResourceTransformer(Main-Class=ShellMain)` 都在，
无 `include` 白名单。实测 jar 内容：

```
reactor/core/publisher/LambdaMonoSubscriber.class   PRESENT（javap 可加载）
reactor/core/publisher/ 下 .class                    782 个
jar 内 reactor 相关条目合计                            968 个
```

★ **顺带发现（非本缺陷，未改）**：shaded jar 的 MANIFEST **没有 `Multi-Release: true`**，
而 reactor-core 是多版本 jar（含 `META-INF/versions/11`、`/21`）。⇒ JDK 21 专属的那些
reactor 版本化类（如 `BoundedElasticSchedulerSupplier`、`VirtualThreadFactory`）在
fat jar 里**不会被 JVM 采用**（回落基础版）。功能不受影响，但记在 §九。

### 1.3 决定性反证：reactor 真的掉了，实例**根本起不来**

把 jar 里的 `reactor/*` 全删（`/tmp/shade-mut/m1.jar`）再起，**启动期**就炸：

```
java.lang.NoClassDefFoundError: reactor/core/publisher/Mono
	at io.modelcontextprotocol.server.McpServerFeatures$AsyncToolSpecification.fromSync(...)
	at io.mosire.agentlib.mcp.AgentToMcpServer.assemble(...)
	at io.mosire.simos.app.Shell.start(Shell.java:443)
```

⇒ **5817 那次实例跑了 2 小时 12 分**（20:30→22:42），它**不可能是**一个缺 reactor 的 jar。
这一条独立地证明了"缺 reactor"不是真因。

## 二 真因：就地重写运行中的 jar（受控复现）

时间戳证据（`/tmp/inst5817.log`）：

```
22:42:56.008 INFO  ShellMain - 收到停止信号，关闭 Shell
Exception in thread "simos-shutdown" java.lang.NoClassDefFoundError: reactor/core/publisher/LambdaMonoSubscriber
	at reactor.core.publisher.Mono.subscribe(Mono.java:4403)
	...
	at io.mosire.simos.app.Shell.close(Shell.java:723)
Caused by: java.lang.ClassNotFoundException: reactor.core.publisher.LambdaMonoSubscriber
```

```
$ stat -c '%y' simos-app/target/…-shaded.jar
2026-09-21 22:42:55.963148868 +0800        ← jar 被重写
（错误发生在 45ms 之后：22:42:56.008）
```

**受控复现**（不碰真 jar，见 `logs/repro-stale-jar.txt`）：

1. 把真 jar 复制成 `/tmp/repro-jar/running.jar`（记 inode `736057`）；
2. 用**同一批类**重新打一份顺序不同的 jar `new-full.jar`（类一个不少，仅布局变）；
3. 起 `java -jar /tmp/repro-jar/running.jar …`，正常；
4. `cat new-full.jar > running.jar`（**同 inode 就地重写**，inode 仍 `736057`）；
5. 再请求 GUI ⇒ **`http=000`**，日志出现
   `ClassNotFoundException: org.apache.logging.log4j.message.ParameterizedMessage`
   （**新 jar 里这个类明明在**）；
6. 对该实例发 SIGTERM ⇒ 复现出**与 5817 逐帧一致**的
   `NoClassDefFoundError: reactor/core/publisher/LambdaMonoSubscriber`。

⇒ 根因坐实：**不是缺类，是偏移量失效**。

## 三 5818 的诊断（只诊断，未 kill）

- pid `64974`，15:09:16 起，命令行 `--store /tmp/sept-gen --demo --gui-port 5818 --mcp-port 5816 --approval-port 5814`。
- 端口 **LISTEN 正常**（5818/5816/5814），但 `curl http://127.0.0.1:5818/` = **`000`**（空响应）。
- `/tmp/inst5818.log`：15:09~20:30:15 一切正常（`GET / -> 200`）；**21:44:49 起 `GET / -> -1`**，
  伴随 **12 处** `NoClassDefFoundError/ClassNotFoundException: org.apache.logging.log4j.message.ParameterizedMessage`
  （`GuiServer.handle` 的访问日志那一行）。
- 启动日志写着"**已种入演示世界**…12 个工具"⇒ 它是**旧构建**的陈旧实例。
- `jstack 64974`：`main` 正常 park 在 `CountDownLatch.await`（`ShellMain.run:132`），37 个线程，
  **无死锁**。

**结论**：5818 的"hung"= **同一根因**——jar 被就地重写后，任何"尚未加载的类"都取不出来；
GUI 请求走到访问日志（log4j 懒加载）就炸在写响应之前，于是客户端永远拿不到响应。
**不是死锁，也不是端口没绑上**。★ 未用调试器附加，结论建立在日志 + jstack + 受控复现上（见 §九）。

## 四 修了什么

### 4.0 没有做的两件事（及理由）

- **没有**"补一条显式 `reactor-core` 依赖"：它已经在（传递），且 `mcp-bom` 不管 reactor 版本，
  显式补就得钉死 3.7.0、反而与 `mcp-core` 解耦失败。
- **没有**改 `Shell.close()` 去 try/catch 兜底：派单明令不许绕；而且关闭路径要真的把 MCP
  server 关掉（`AgentToMcpServer.close` 级联 `closeGracefully`），吞异常等于假关闭。

### 4.1 `simos-app/src/verify/shaded-jar-guard.sh`（进 Maven 门禁）

`verify` 阶段对 shaded jar 断言：7 个运行期类（reactor ×2、MCP ×2、log4j、slf4j、sqlite）
**逐条必在** + `Main-Class` 必须是 `ShellMain`。缺任一 ⇒ `rc=1`，**fail-closed**。
它是"reactor 真的掉出 jar"这条**未来漂移**的构建期护栏（不覆盖"就地重写"那条真因——那条由 4.2 修）。

### 4.2 `tools/run-shaded.sh`（真因的修复）

```
把 <shaded.jar> 复制成一份 mktemp 的独占快照，再 exec java -jar <快照>
```

此后无论 `target/` 下的 jar 怎么被重写，运行中的进程都不受影响。**5817 就是用它起的**
（进程实际读的是 `/tmp/simos-shaded-R95jBR.jar`，与构建产物 md5 相同：`4dd81f9e…`）。

### 4.3 `tools/shaded-close-check.sh`（端到端验收，脚本级）

真起进程 → 等就绪（GUI 200 **且**日志出现「WebUI 就绪」）→ SIGTERM → 等退出 →
断言：① 日志含「收到停止信号，关闭 Shell」② 日志**无** `ClassNotFoundException` / `NoClassDefFoundError`。

★ 本脚本在开发中**当场抓到自己的一处竞态**：最初只等 GUI 200 就发信号，但 `--demo` 的
`bootstrapGenesis` 与 shutdown hook 注册都在 GUI 起之后 ⇒ 信号早到、hook 未挂、日志自然没有
关闭行（`close-check.log` 的首轮 FAIL 即此）。已把就绪条件收紧为"GUI 200 **且** 日志就绪"。

## 五 自证与变异

### 5.1 构建期守卫（`logs/guard-mutants.log`）

| 轮 | 变异 | 期望 | 实测 |
|---|---|---|---|
| 控制 | 真 jar | rc=0 | **rc=0**（7 类 + Main-Class 全 OK） |
| m1 | `zip -d` 删光 jar 内 `reactor/*` | rc=1，红在 reactor 两条 | **rc=1**，两条 reactor 均 FAIL |
| m2 | 篡改 `Main-Class` | rc=1，红在 Main-Class | **rc=1**，`实得 'io.mosire.simos.app.WrongMain'` |

### 5.2 端到端关闭检查（`logs/close-check*.log`）

| 轮 | 对象 | 期望 | 实测 |
|---|---|---|---|
| 干净 | worktree 构建的 shaded jar | rc=0 | **rc=0**（四断言全 OK） |
| 变异 | `m1.jar`（reactor 已删） | rc=1 | **rc=1**（启动期 `NoClassDefFoundError: Mono`） |

## 六 门禁（worktree，`./mvnw clean verify`，第 1 次尝试）

- **rc=0**（`logs/verify-rc.txt`）
- **8/8 `SUCCESS [`**：`SimulatorMosire`(parent) + `UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp`
- `[ERROR]` **0** 行；`BugInstance size is 0` **×7**
- 前端 `[frontend-gate] OK tests=184 pass=184 fail=0`（下界 184）
- 用例总数**现场重算**（`logs/recomputed.txt`）：**1360 = 170/368/45/259/178/129/211**
- **新增的 `shaded-jar-guard` 执行在门禁里跑过**（日志尾部 `[shaded-guard] 结论：OK（6044 个条目…）`）
- 环境：本机 `nproc=8`，整轮约 70s，**无被杀轮**

★ 数字只作**发现分歧**用；口径以 `logs/clean-verify.log` 现场重算为准。

## 七 5817 与 5818 的现状

**5817**（`logs/inst5817-check.log`）：已用 `tools/run-shaded.sh` 起，
`--store /tmp/sept-rich3 --demo --gui-port 5817 --mcp-port 5715 --approval-port 5713`，
pid **418696**，实际读 `/tmp/simos-shaded-R95jBR.jar`（快照）。

- `GET /` → **200**（19014 B）
- `GET /api/map/overview` → **200**（629005 B），`regions` 长度 = **252**
- 启动日志：`已种入富世界（--demo）：v17levant 复刻（59223 hex / 252 区域 / 240 条河流边）`

**5818**：**未 kill**（遵命），仍在 5818/5816/5814 监听、请求仍 `000`；诊断见 §三。

## 八 ★ 最终绿轮 / 最终成功那一次是哪个文件

| 用途 | **最终那一次**的文件 |
|---|---|
| 门禁绿轮 | **`logs/clean-verify.log`**（rc 见 `logs/verify-rc.txt`） |
| 门禁数字重算 | **`logs/recomputed.txt`** |
| 构建期守卫变异 | **`logs/guard-mutants.log`** |
| 端到端关闭成功轮 | **`logs/close-check.log`**（服务器原始日志 `logs/close-check-success.log`） |
| 端到端关闭变异轮 | **`logs/close-check-mutant-m1.log`** |
| 5817 运行证据 | **`logs/inst5817-check.log`** |
| 症状原始证据 | **`logs/symptom-5817-cnfe.txt`** / **`logs/symptom-5818-log4j.txt`** |
| 受控复现 | **`logs/repro-stale-jar.txt`** |
| 被测 jar 指纹 | **`logs/jar-md5s.txt`** |

## 九 我未能核实的

1. **没有抓到 22:42 那次 maven-shade 的实际写文件系统调用**——"就地重写（同 inode）"
   是由①时间戳（错误落后 jar mtime 45ms）②受控同 inode 复现 两条**推断**出来的，
   没给 `ShadeMojo` 挂 strace。若它其实是"写临时文件再 rename"，那 JVM 的 fd 仍指旧 inode，
   机制会不同（但 5817/5818 的实测症状与受控复现都指向偏移失效）。
2. **5818 未用调试器附加**，结论来自日志 + jstack + 复现；也没 kill 它，故它现在仍是坏的。
3. **端到端关闭检查没在"有活跃 MCP SDK 会话"下跑**（只跑过无客户端与一次 SSE GET，
   两者都干净）；MCP 会话中途关闭未测。
4. **`Multi-Release: true` 未处理**——versioned reactor 类在 fat jar 里被忽略，本次只记录不修。
5. **端到端关闭检查不在 Maven 门禁里**（按设计：要起进程/绑端口/发信号，进 surefire 会脆）。
   Maven 里只有**内容守卫**；e2e 靠脚本 + 本目录证据。
6. 守卫只断言**一份固定类清单**，不等于"整个运行期类闭包完整"。
7. 只在**本机 Linux/JDK 21** 验过；跨机、跨 OS 未测。
8. `5818` 的 `/tmp/sept-gen` 库是旧构建产物，与当前代码的兼容性未测。
