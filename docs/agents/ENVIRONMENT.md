# 环境维护手册

> **本文件属于项目维护。** 本机具体端口、临时 world、历史 LLM 配置可能变化；
> 详细操作与事故见 `09-八-环境与运维.md`、`12-十一-运行时操作原则.md`。当前命令以本文件为准。

## 一、工具链

- Java **21**；
- 仓库内 Maven Wrapper（`./mvnw`，Maven 3.9.16）；不用系统 `mvn`；
- 根 `pom.xml` 是 `io.mosire:simos-parent:0.1.0-SNAPSHOT`，**不继承** `io.mosire:mosire-parent`；
- Maven 一律通过 `tools/mvn-lock.sh` 使用本树本地仓库。

## 二、工作树与 Maven

### 工作树目录规范

- **未来所有 worktree 统一放**：`<仓库根>/worktree/<name>/`。
- 不再把 `.claude/worktrees/` 当作新工作树落点；`.claude/` 只保留本机工具自己的设置。
- 新建示例：
  `git worktree add worktree/<name> -b <branch> <base>`
- 迁移示例（不要手工 `mv`）：
  `git worktree move <old-path> worktree/<name>`
- `/worktree/` 已加入 `.gitignore`；**忽略 ≠ 删除**，`git worktree list` 仍能看到每棵树。
- 旧文档、旧 status、旧 note 里的 `.claude/worktrees/*` 都是历史地址；当前落点按本轮迁移后的 `worktree/*` 读。

- 每棵工作树有自己的 `<树根>/.m2-repo`，首次由 `tools/mvn-lock.sh` 从 `~/.m2/repository` hardlink 播种；
- 锁是**每棵树一把**：同树串行、跨树并行；
- 想“干净重来”可以删除本树 `.m2-repo`；`.m2-repo/` 必须在 `.gitignore`；
- 跨树生效的脚本、纪律、测试夹具必须先提交，否则另一棵树看不到；
- 本仓 Maven/服务检查只认本仓路径与 cwd；隔壁项目不阻塞本仓（用户 2026-10-23）。

## 三、内存与构建

- 本机内存约 1.6 GB；`clean verify` 必须前台/受管跑；
- 被内存守卫杀掉**既不是红也不是绿**，不能算通过；
- `full` 构建会覆盖 shaded jar；服务在跑时只做 `test`/`compile`，打包前先停服务或打快照；
- `-Dshade.skip=true` 无实际开关，会被静默忽略；不要用它“跳过打包”。

## 四、服务、端口与 world

- 内置/测试 world、端口、服务脚本随时可能调整；启动前先读 `docs/agents/STATUS.md` 与相关运行文档，不要凭旧记录猜端口。
- 全真推演测试 world 在独立仓库 `~/Simos-18Lvt`；仓内 `test-world/` 是隔离副本，不能指向真实 world store。
- store 是世界的本体；空库首次启动会 bootstrap，非空库不得覆盖。
- 临时数据、日志、SQLite 放 `/tmp` 或任务目录；跨重启需要保留的产物不要只放 `/tmp`。

## 五、本地 LLM 与真模型

- 真模型运行前检查 provider/模型配置与 key 只通过配置注入；日志只打印路径/长度/结构脱敏信息。
- 本地 LLM 是否在线、端口和模型名由当前环境决定；不存在跨机器固定值。
- 真模型脚本可能联网/花钱，使用独立 `--data-dir` 与端口，不碰正在运行的世界。

## 六、换设备自检

1. `java -version`、`./mvnw -v`；
2. `git status` 与 `.gitattributes` 的 LF 规则；
3. `tools/gate.sh pre`（本仓服务、锁、工作树）；
4. `tools/gate.sh docs`（文档/Skill 结构）；
5. 先跑聚焦测试，再跑 full；没跑的项按 `VERIFICATION.md` 写 `NOT_RUN`。

## 七、旧长文入口

- 运行/端口/世界：`09-八-环境与运维.md`
- 运行时操作原则：`12-十一-运行时操作原则.md`
- 并发事故：`02-一-并发.md` §一.13/§一.14
