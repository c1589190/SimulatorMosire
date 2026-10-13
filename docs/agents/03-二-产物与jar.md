<!-- 本文由 AGENTS.md 于 2026-10-13 整节搬出（逐字未改）；**根 AGENTS.md 是索引，冲突时以根为准**。 -->
<!-- 加载时机：本文件**不会**被 harness 自动加载；由根 AGENTS.md 的「文档索引」按需指向。 -->

## 二、产物：别把正在跑的服务的 jar 覆盖掉

- ★ **重建产物前先停服务**（或把新 jar 打到别的路径）。Java 的 classloader 是**惰性加载**的：
  运行中被替换的 jar，之后要用到某个类时就读不到 ⇒ `ClassNotFoundException`（真发生过一次）。
  ★★ **补充实测（2026-09-23，同一天第二次踩）**：`package` 覆盖在跑的 jar 之后，服务**进程还活着、
  `/api/*` 还 200**，但**静态资源全 500**（`/`、`/index.html`、`/styles.css` 都是
  `{"error":"internal error"}`）——因为 JVM 手里的 jar inode 已被就地重写。判"服务坏了"要看
  **`/` 与 `/styles.css`**，别只看 `/api/*`。
  ★★★ **本 pom 下没有任何命令行开关能跳过 shade**：`maven-shade-plugin:3.6.0` 的 `skip` 参数
  **没有 user property**（`plugin.xml` 里是 `<skip implementation="boolean" default-value="false"/>`，
  不是 `${shade.skip}`）⇒ **`-Dshade.skip=true` 会被静默忽略**（实测：`-q -DskipTests package
  -Dshade.skip=true` 返回 rc=0，而 jar 的 md5 与大小都变了）。
  ⇒ **服务在跑时，验代码用 `./mvnw test`**（`test` 阶段**不经过** `package`/`shade`，**不动 jar**）；
  **只有必须打包/关账时才停服务**。
- 派单里若要"起得来能看"，**写明用别的端口起、别动已有的进程**。
