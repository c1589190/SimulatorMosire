# M10 小服务器部署 — 关账报告

> 三件事：① 可分发产物（shade）② `--bind-address` ③ GUI 访问日志。分支 `m10/deploy`，基线 `05f9c1b`。
> 证据根：`.superpowers/sdd/2026-09-19-map-edit/m10-deploy-evidence/`（日志在 `logs/`，变异装置在 `mutants/`）。

## 〇 一句话结论

三件事**全部落地并真跑通**：shaded jar（21,401,446 B）以 `java -jar` 起在 5851/5852/5853，`curl /` 与 `/api/state` 均 **200**；
`--bind-address` 缺省回环、显式 `0.0.0.0` 时 GUI/MCP 经本机非回环网卡真可达；访问日志**一行一请求**（方法/路径/状态码/耗时/远端）
且**不带查询串**。门禁 `./mvnw clean verify` **rc=0**、7/7 模块 SUCCESS、`BugInstance size is 0` ×6、`[ERROR]` **0 行**、
前端门禁 **88/88**；Java **987 = 170/362/45/131/169/110**（相对派单基线 977 **+10 = 三个新用例类 3+3+4**，见 §五诚实披露）。
3 轮变异**全 KILLED**。

---

## 一 可分发产物（shade）

### 改动

`simos-app/pom.xml`：新增 `maven-shade-plugin:3.6.0`，`phase=package`，
`shadedArtifactAttached=true` + `shadedClassifierName=shaded`（**不动瘦 jar**），
`ManifestResourceTransformer(mainClass=io.mosire.simos.app.ShellMain)` + `ServicesResourceTransformer`（合并
MCP/Jackson/SLF4J 的 `META-INF/services`），excludes 掉签名文件与 `module-info.class`。

产物：**`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`**（21,401,446 B；sha256 `6ac34074…d8bfa`）。
实测内含：`Main-Class: io.mosire.simos.app.ShellMain`、唯一一份 `Log4j2Plugins.dat`、15 条 `META-INF/services`、
50 个 sqlite 原生库、`webui/*`、`log4j2.xml`。

### 启动命令（★ 交付形态）

```bash
# 在仓库里构建（产 target/simos-app-0.1.0-SNAPSHOT-shaded.jar）
./mvnw -pl simos-app -am package

# 起服务（缺省绑 127.0.0.1）
java -jar simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
     --store /var/lib/simos --demo                      # --demo 仅空库首启种演示世界

# 反代场景：显式绑 0.0.0.0（GUI+MCP）
java -jar simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
     --store /var/lib/simos --bind-address 0.0.0.0
```

### 真跑证据（`logs/shaded-run-*.log`）

| 轮 | 端口 | 启动命令要点 | 实测 |
|---|---|---|---|
| default | 5851/5852/5853 | `--store /tmp/m10shade-demo --demo` | `GET /` **200**；`GET /api/state` **200** `{"branches":["main"],"heads":{"main":1},…}`；日志有 `access GET / -> 200` 与 `access GET /api/state -> 200` |
| wildcard | 5851/5852/5853 | 同上 + `--bind-address 0.0.0.0` | `GUI 服务器已启动: http://0.0.0.0:5851/`；经 `192.168.71.21:5851/api/state` **200**（`--noproxy`）；MCP 5852 经该 IP **可达**；审批 5853 经该 IP **Connection refused**（见 §三） |
| final | 5851/5852/5853 | 变异轮后重建的 jar，`--demo` | `GET /` **200**；`/api/state` **200**；`/api/map/overview` **200 / 405 B**；三条 access 行 |

三轮实例**均已停掉**（`.pid` 文件记 PID；`kill -0` 复核为 YES）。**全程未碰 5817/5818**（实测两端口仍由
pid 3419/4872 监听）。

---

## 二 `--bind-address`

### 改动

- `ShellConfig`：新增第 9 个组件 `String bindAddress`（缺省 `DEFAULT_BIND_ADDRESS = "127.0.0.1"`，构造期非空白校验）、
  `withBindAddress(...)`；`defaults()`/`withPorts()` 随之带默认。
- `ShellMain.parse`：新增 `case "--bind-address"`；用法串加 `[--bind-address <host>]`；启动日志打印 `bindAddress=`。
- `Shell`：GUI（`guiServer.start`）与 MCP（`AgentToMcpServer.startHttp`）改用 `config.bindAddress()`（原为硬编码
  `GUI_HOST`/`MCP_HOST` 常量，已删）；`Shell.boundGuiHost()` 新增（回显生效绑定）。

### 判别力（`BindAddressTest`，3 条）

- 缺省 ⇒ `boundGuiHost()=="127.0.0.1"` 且 `isAnyLocalAddress()==false`；
- 显式 `0.0.0.0` ⇒ `isAnyLocalAddress()==true`（JDK 读回通配形态 `0:0:0:0:0:0:0:0`）；
- 跨接口可达性（本机有 `192.168.71.21`）：绑 `0.0.0.0` ⇒ GUI/MCP 经该 IP 裸 TCP **可达**、审批**不可达**；
  缺省回环 ⇒ GUI/MCP 经该 IP **不可达**。

### ★ 与派单文字的一处偏离（诚实披露，非笔误）

派单写「GUI / MCP / **审批**端口都绑它」。**审批端口做不到**，且**不应做**：

- AgentLib 的 `ApprovalHttpEndpoint.start(int,…)` **没有 host 形参**，其类注明写「本类**不提供任何改绑地址的入口**」，
  源码是 `HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)`（`~/ProjectMosire/…/ApprovalHttpEndpoint.java:97`）。
  改它要动 **AgentLib**（本任务范围是 `simos-app`），故**不在此解决**。
- 安全上也无必要：审批面**对外**是 GUI 的 `GET/POST /api/approvals` **透传代理**（`GuiServer.proxyApproval`），
  反代只暴露 GUI 即可。⇒ 现状满足派单的**意图**（不裸暴露、有 bind 控制），只是实现上「三个可配」应为「GUI+MCP 可配、
  审批恒回环」。
- **落地**：`ShellConfig`/`Shell`/`ShellMain` 的 Javadoc 与 `Shell` 调用点注释都标了这条；`BindAddressTest` 用
  「审批经非回环 IP 不可达」把它**钉成可断言的行为**。
- **若确需审批公网可绑**：那是一次 AgentLib 的接口变更（加 host 形参），列为遗留（§六）。

---

## 三 GUI 访问日志

### 改动

`GuiServer.handle` 记 `startedNanos`，`finally` 里（`exchange.close()` 之前）调 `logAccess(...)`：

```
access <METHOD> <path> -> <status> <ms>ms remote=<ip>
```

- 状态码取 `HttpExchange.getResponseCode()`（响应头发出后才有值）；远端取 `getRemoteAddress().getAddress().getHostAddress()`。
- **密钥纪律**：只取 `URI.getPath()`（**不含查询串**）、**不读请求体**；`logAccess` 自捕获 `RuntimeException`，不得改变响应。

### 判别力（`GuiAccessLogTest`，4 条）

- 前提断言：装置真的在收（空捕获上"不含哨兵"会假绿——沿用 core `CommandBusLoggingTest` 的教训）；
- 两次请求 ⇒ 恰好两行，且 `access GET / -> 200` / `access GET /api/state -> 200` 各自在，`ms remote=` 且远端非 `-`；
- `GET /nope → 404`、`POST /api/state → 405` 各记真实状态码；
- **查询串哨兵**：`GET /api/state?token=<CANARY>&secret=<CANARY>` ⇒ 捕获的访问行**不含 CANARY、不含 `?`**。

真机证据（shaded jar）：`logs/shaded-run-default.log` 里的 `access GET / -> 200 12ms remote=127.0.0.1`（照抄原文）。

---

## 四 门禁（`./mvnw clean verify`，前台，`logs/clean-verify.log` + `verify-rc.txt`）

```
rc=0
[INFO] Reactor Summary for SimulatorMosire 0.1.0-SNAPSHOT:
[INFO] SimulatorMosire .................................... SUCCESS
[INFO] UtilSimos .......................................... SUCCESS
[INFO] MapSimos ........................................... SUCCESS
[INFO] SocialSimos ........................................ SUCCESS
[INFO] UnitSimos .......................................... SUCCESS
[INFO] CoreSimos .......................................... SUCCESS
[INFO] SimosApp ........................................... SUCCESS
[INFO] BUILD SUCCESS
```

| 项 | 值 |
|---|---|
| Java 用例 | **987** = 170 / 362 / 45 / 131 / 169 / **110**（util/map/social/unit/core/app） |
| 前端门禁 | `[frontend-gate] OK tests=88 pass=88 fail=0` |
| SpotBugs | `BugInstance size is 0` ×**6**、`Error size is 0`；`[ERROR]` **0 行** |
| 模块 | 7/7 SUCCESS |

**delta 干净**：前五模块逐值不变；`simos-app` 100 → **110**，恰好 **+10 = 三个新用例类 3+3+4**。
（派单基线写死「977 / app 100」是**改动前**的数；新增护栏用例必然抬高它，按本项目惯例记 delta。★ 若验收要"数字逐字等于 977"，
那与本任务「每件都要有可复核证据」相矛盾——我选了留护栏。）

---

## 五 变异（3 轮，九道门禁；装置 `mutants/mut-round.sh`，日志 `logs/mut-m{1,2,3}.log`）

| 轮 | 变异体 | 形式 | 杀点（实测） | 结论 |
|---|---|---|---|---|
| **m1** | `GuiServer.handle` 删掉 `logAccess(exchange, startedNanos);` | Java | `GuiAccessLogTest` **Tests=4 Failures=4**，红在「两次请求 ⇒ 两行访问日志」 | **KILLED** |
| **m2** | `ShellConfig.DEFAULT_BIND_ADDRESS = "0.0.0.0"` | Java | `ShellMainParseTest` **Tests=3 Failures=1**，红在「缺省必须回环」 | **KILLED** |
| **m3** | `pom.xml` 去掉 shade 的 `<mainClass>` | 构建产物 | 变异 jar `Main-Class` 计数 **0**；`java -jar` rc=1 `no main manifest attribute` | **KILLED** |

九道门禁逐轮在案（见各 `mut-m*.log` 的 `SELF` 行）：① 干净世界 md5 == 原件；② 变异体 md5 ≠ 原件且非空；
③ 按白名单推成目标名（pushed_md5 == mutant_md5）；④ 清陈旧 `.class`；⑤ `COMPILATION ERROR` 计数 == 0 且 `Tests run ≥ 1`；
⑥ surefire 报告 mtime ≥ 本轮 start；⑦ 红点落在被保护断言/行为上（逐轮 grep 断言原文）；⑧ `cp` 逐字节还原、还原 md5 == 原件
（**全程未用 `git checkout --`**）；⑨ 日志**自指**（orig/pushed/mutant/restored md5 与 start/end 写进日志），核对脚本先断言读到非空再比对。

**m3 的适配（诚实说明）**：其红点不是 JUnit 断言而是"产物起不来"，故 ⑤/⑥ 仍由 `package` 轮的 surefire 报告满足
（`package_rc=0`、`Tests=3`、mtime 本轮内），⑦ 改为断言 `unzip` 出的 `Main-Class` 计数为 0 **且** `java -jar` 的 stderr 含
`no main manifest attribute`——**不是伪造红点**，是换了一个同为真实失败的红点载体。

---

## 六 ★ 我未能核实的 / 遗留

1. **审批端口无法（也不应）随 `--bind-address` 改绑**——AgentLib 硬编码回环且无 host 形参；若产品上确要，需先改 AgentLib
   接口（本任务范围外）。已在代码与测试中显式记录。
2. **本机 `http_proxy=http://0.0.0.0:10808`**：首测 `curl http://192.168.71.21:5851/…` 得 **503**，那是**代理**返回的，非 GUI；
   加 `--noproxy '*'` 后 **200**。★ 报告里的跨接口数字全部是 `--noproxy` 下的。`BindAddressTest` 用**裸 socket**，不受代理影响。
3. **生产路径未测**：shaded jar 只在**本机**（Linux x86_64、JDK 21）跑过；**未**在真正的小服务器/容器/跨平台镜像上跑。
   sqlite 原生库已内联（50 个），但**未**验证目标机 libc/架构匹配。
4. **`--demo` 后的持久化演进未测**：只验了首启种入 + 只读查询；**写命令经 GUI/MCP 后再重启**未跑（属既有链路，非本次改动）。
5. **访问日志**：只在**真 Chromium/curl** 并发**不高**的形态下观测；**高并发下日志量/顺序**未压测。也**未**验证
   `getResponseCode()` 在极端"客户端中途断开"下的取值（此时可能为 `-1`，会如实记 `-1`）。
6. **`--bind-address` 只做了 IPv4/host 字符串透传**：传**非法主机名**时在 `Shell.start` 绑定处才抛（已有既有 `IllegalStateException`
   路径），**未**为它加"启动期友好校验"；传 IPv6 字面量未测。
7. **变异只覆盖 3 个点**：`ServicesResourceTransformer` 是否真被需要、`log4j2.xml` 在 fat jar 里被选中、sqlite 原生库实际加载
   ——都由"fat jar 只读查询 200"**间接**证明，**未**逐条变异。
8. **`mvn install` 未跑**（派单禁），shaded jar 的**下游消费/发布**（推制品库、签名）未做。

---

## 七 改动文件与提交

`simos-app`：`pom.xml`、`Shell.java`、`ShellConfig.java`、`ShellMain.java`、`gui/GuiServer.java`（+5 个既有测试的
`new ShellConfig(...)` 补第 9 个实参、+3 个新测试类）。
证据：`.superpowers/sdd/2026-09-19-map-edit/m10-deploy-evidence/**`。

- 实现提交：**`43c5122`**（「M10 部署：shade 可分发产物 + --bind-address + GUI 访问日志」）
- 本报告与状态行的追记提交见 `git log`（紧随其后的 `M10 收尾` 提交）
