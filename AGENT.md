# AGENT.md —— 多 agent 共用本工作树时的规矩（写给下一个 agent）

> 本仓**可能同时有多个 agent 在改同一棵工作树**（本会话期间就撞到过）。每条规矩后面都附**为什么**——
> 它们几乎都是**真发生过的损失**，不是洁癖。

## 一、并发：这是最容易造成返工的一类

1. ★ **一次只能跑一个 Maven。** 本机内存不足以并行；两轮 Maven 同抢 `target/` 会让**测试计数失真**
   （实测：某轮 simos-app 只跑了 **9/66** 个测试类、97 条，而正常是 459 条）。
   跑前：`pgrep -af "surefirebooter|classworlds.launcher"`；有命中就等。
2. ★ **一个文件一个 owner。** 动手前先 `git status --porcelain`，再用 `find <模块>/src -newermt "-3 minutes"`
   看有没有人正在写；看到**半成品尾巴**（例如某处 import 了刚被删的类 ⇒ 编译不过）**不要接手**——
   那通常不是坏尾，是**另一个 agent 的中间态**。本会话就发生过：一个 agent 因此停下报我，判断是对的。
3. ★ **派单要写清"谁拥有哪些文件"**；跨 agent 改同一文件 = 互相覆盖。
4. ★ **不要在别人跑 Maven 的窗口里改 Java**：编译/测试的计数会不可信，白跑一轮。

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

## 三、验证：护栏必须自证，且不许把"没跑"写成"通过"

1. ★ 不信"rc=0 就是过了"：本仓 pom 明写前端门禁 **fail-closed、不设 skip/if 守卫**；
   而 `-q` 会吞掉 surefire 汇总 ⇒ **类名写错、测试被跳过，照样 rc=0**。
   要**看 surefire 报告**（`target/surefire-reports/*.txt`）并核对 **mtime 落在本轮**。
2. ★ 新护栏/新判据要有**判别力**：临时把被测逻辑改坏（变异体）⇒ 必须**当场红**，然后还原。
   做不到的（结构性不可表达）如实说"等价存活"，**不许编红点**。
3. ★ 报告里必须有**"我没做/没验证的"**一节。凡是没跑过的，不许写成通过。
4. 本会话反复用到的一条：**门禁沙箱不执行 `initHost`/`initDecision`** ⇒ "搬走函数但别处还留着裸调用点"
   这类漏改**门禁查不出来、只在运行时炸**。搬函数后必须**静态审计裸引用**。

## 四、台账/计划 vs 代码：**机制性描述一律回代码核**

本会话发现**至少 4 处**"计划/台账措辞 ≠ 代码实际"，都足以让人做错方向：

| 台账/计划说 | 代码实际 |
|---|---|
| "写工具用粗断言" | 写侧确实粗，但**有 3 条决策窄写覆写** `writeResources`，且目标**由身份而非载荷派生** |
| T10 "写工具的断言也成对改细" | 只兑现为那 3 条，**"改细"的范围比措辞小** |
| "读口必须走 `RedactingQueryService` 的同一份装配" | 9 条读工具走的是 `ToolSupport` 谓词；`RedactingQueryService` 是 GUI `?as=` 在走 |
| "`at.revision` = 该条目落盘时的 revision" | 实为**写入所依据的基态** revision（可能小于首次可见的 revision） |

⇒ **凡要用到台账里的机制描述，先去代码确认**；发现不符**报出来**，别照着措辞硬做。

## 五、提交与协作

1. **不 `git add -A`**；按**批次**提交（不同关注点分开，便于回退与审计），提交前扫 `git status`。
   ★★ **但反过来也有坑**（2026-09-23 实测踩到）：跑过**全仓工具**（`./mvnw spotless:apply`、
   格式化/重写的 codemod）之后，**按模块路径 `git add` 会漏掉别的模块的同类改动** ——
   那次 `spotless:apply` 改了 21 个文件（`simos-app` 18 个 + `simos-sd` 3 个），
   我按 `git add -A simos-app/src/...` 提交，`simos-sd` 那 3 个**留在工作树里没进提交**，
   而提交信息却写着"21 个文件"（**信息与提交内容不符**）。
   ⇒ **纪律**：全仓工具跑完后，用 `git diff --name-only` **列全**再逐条确认，
   或按"我改过的模块清单"取并集，不要只给一个模块路径。
2. 提交信息**中文**、写清"改了什么 + 为什么 + 验收的实际数字 + 未验的部分"；本仓惯例是**把诚实边界写进提交信息**。
3. 已确认的发现，**若修复比描述还短，当场修**，别 park 成 issue。
4. `.superpowers/sdd/**`、`docs/**`、`CLAUDE.md` 的历史台账行是**留痕**：**不篡改**；要更正就**追加**标注。

## 六、前端（无 npm、无打包器、纯 `<script>`）

1. 拆文件后**加载顺序是语义的一部分**：`hexgeom → hexcolor → regionShape → map.js → map-mapeditor.js →
   map-regioneditor.js → renderer.js`（`renderer.js` **加载期**就要 `core.renderEdgeKindOptions`）。
2. `simos-app/src/test/js/helpers/webui-loader.cjs` 的 `BUNDLE_DEPS`：**新文件依赖它取用的宿主**（例如新文件
   依赖 `map.js`），**不许反过来**——反过来会让新文件先于宿主执行、取不到 `SimosMapCore`。
3. **可变绑定跨文件必须走 getter**（`active`、`regionNamesEnabled`）；取快照会恒为初始值。
4. 两个宿主页（`map.html` 旧页 / `index.html` 工作台）**都要同步**；旧页也加载 `map*.js` 与 `renderer.js`。

## 七、门禁与关账的常用命令

```bash
node simos-app/src/test/js/run-gate.cjs          # 前端门禁（下界见 run-gate.cjs，当前 204；实测 211 条）
./mvnw test -pl simos-app -am                    # 全量测试（含前端门禁那一步）
./mvnw -q -DskipTests package -pl simos-app -am  # 编译+打包（别用管道接 echo $? 取退出码）
./mvnw clean verify                              # ★ 关账：Spotless+Checkstyle+SpotBugs+Surefire+前端门禁
```
★ **`clean verify` 必须前台跑**：台账记过"后台跑会被内存守卫杀"，而被杀**既不是红也不是绿**（不能算过）。
