# M5 T9 报告 —— GUI 前端三页（WebUI 2/2）

> **任务**：M5 T9（`docs/superpowers/plans/2026-09-19-shell-simos-plan.md` §二 T9；spec §8.1/§8.3）
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/m5t9`，分支 `m5/t9`，基线 `c80f1bc`（PREFLIGHT 已自证）
> **日期**：2026-09-19
> **范围**：只读/写入型前端三页 + 共享静态资产 + 结构护栏；**不动** `GuiServer`/`ApiViews`/`StaticHandler`/`Shell`。

---

## 1 交付物

### 新增静态资产（`simos-app/src/main/resources/webui/`）

| 文件 | 内容 |
|---|---|
| `index.html` | 首页：入口三链接 + 待批占位（**保留**；`/api/approvals` 未接入 ⇒ 优雅显示"审批未接入"）。链接共享资产相对路径 |
| `map.html` + `map.js` | **只读**六角 Canvas：`/api/map/overview` 画地形色块（顶点朝上轴向坐标）、城市标记；点击格点 → `/api/map/hex?q=&r=` 展示格详情 + facet 表。**无任何写调用** |
| `unit.html` + `unit.js` | 列表（`/api/units`）+ 详情（`/api/unit/{id}`）+ **8 条命令表单**（`unit.CreateUnit`/`ReparentUnit`/`SetStrength`/`PlaceAt`/`PlanRoute`/`CancelRoute`/`DisbandUnit`/`RenameUnit`），POST `/api/command`；显示 `committed`/`conflict`/`rejected`，提交成功/冲突后自动更新 `expectedRevision` |
| `social.html` + `social.js` | 按格查询 `/api/social/population?q=&r=` + 结果详情 + 本页会话查询历史表 |
| `api.js` | 取数层：GET/POST 封装 + 13 个端点的具名函数 + `SimosApi` 导出 |
| `app.js` | 共享导航（`mountNav`）、顶栏状态轮询、待批轮询、DOM 工具（`el`/`clear`/`statusMessage`/`fieldValue`）、`SimosApp` 导出 |
| `styles.css` | 单套样式（深色主题、表格、表单、栅格、状态色）；无外链字体、无 CDN |

### 新增测试

| 文件 | 条数 |
|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/gui/WebuiAssetsTest.java` | **5** |

### 修改

| 文件 | 改动 |
|---|---|
| `simos-app/src/main/resources/webui/index.html` | 接入 `styles.css`/`api.js`/`app.js`，三页链接 + 待批计数占位（原 T8 骨架的"审批面未接入（T6）"占位**保留**并升级为动态轮询） |

### 护栏（`WebuiAssetsTest`，5 条）

1. `allWebuiAssetsExistAndAreNonEmpty`：10 个资产（4 页 + 3 共享 + 3 页内脚本）在册且非空；扫描非空自证（≥7 + 关键文件在册）。
2. `webuiAssetsArePackagedOnTheClasspath`：10 个资产经 `/webui/...` classpath 可读（打包口径）。
3. `noWebuiAssetContainsAnAbsoluteUrl`：逐个资产不得含 `http://` / `https://` / 协议相对 `//host`（spec §8.1 无 CDN）。
4. `eachPageReferencesSharedAssetsByRelativePath`：4 页均以 `"api.js"`/`"app.js"`/`"styles.css"` 相对名引用。
5. `absoluteUrlScannerHasTeeth`：`//` 判定器的正反冻结字面量自证（**判别力**，形态 1/5）。

> ★ **为什么读源码树**：护栏要能对故意违规的**源码变异**响铃（变异 m1）。读 `target/classes` 会读到上一次 `process-resources` 的副本、变异轮是否看到新字节取决于构建时序——本项目吃过"陈旧字节被当本轮结论"的亏（CLAUDE.md 纪律形态 1）。⇒ 判定字节读 `src/main/resources/webui/`（surefire 工作目录＝模块根，主树与 worktree 均成立，与 `AppWritePathGuardTest` 同法）；另立第 2 条断言资源确实进了 classpath。

---

## 2 实测数字

### 定向（`./mvnw -pl simos-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WebuiAssetsTest' test`）

`rc=0`；**5 条全绿**（`surefire-reports/io.mosire.simos.app.gui.WebuiAssetsTest.txt`）。

### 全量（`./mvnw clean verify`，`logs/full-verify.log`）

`rc=0`；**781 = 170 / 255 / 45 / 131 / 147 / 33**（util / map / social / unit / core / app）——**7/7 reactor entries SUCCESS**；
`BugInstance size is 0` **×6**；`^[ERROR]` **0 行**；Spotless 跑遍 6 个 jar 模块（`keeping … files clean` ×6）。

**对基线 776 = 170/255/45/131/147/28 的逐模块差**：

| 模块 | 基线 | 现在 | Δ | 来源 |
|---|---|---|---|---|
| util / map / social / unit / core | 170/255/45/131/147 | 同 | 0 | 未动 |
| app | 28 | 33 | **+5** | `WebuiAssetsTest` 5 |
| **合计** | **776** | **781** | **+5** | |

前五个模块一个不动，app 恰好 +5 ＝ 新用例类，**与预期逐条相符**。

---

## 3 变异自证（m1，九道门禁）

| m | 护栏 | 变异 | 期望红 |
|---|---|---|---|
| m1 | 无绝对 URL / 无 CDN（spec §8.1） | 往 `map.js` 插入一行从 `https://cdn.example.com/x.js` 加载脚本 | `WebuiAssetsTest` 的绝对 URL 断言 |

**装置**：`mutants/mut-round.sh`（照 core T17 九道门禁骨架，适配**静态资源**：无 `.class`/无编译期；判定字节由测试读源码树 ⇒ 只需证明"落盘的是与原件字节不同的那份"）。门禁：干净世界 md5 → 变异体字节不同 → 删陈旧 surefire 报告逼本轮重写 → `Tests run≥1` 且 `rc≠0` → 报告 mtime 落在本轮 → 显示红点 → `cp` 还原并核 md5 → 三处 md5 **追加进日志本身**。

**结果（`logs/m1.log`）**：

```
orig_md5=a09ab32fddb0688b02f95040e58b7d38  mutant_md5=d7a31207f6b32f3c4a9a0ecde2b81dc6  worktree_before=a09ab32fddb0688b02f95040e58b7d38
rc=1  Tests_run_lines=2
surefire_report=simos-app/target/surefire-reports/io.mosire.simos.app.gui.WebuiAssetsTest.txt  mtime=1789766567  round_start=1789766564
[ERROR]   WebuiAssetsTest.noWebuiAssetContainsAnAbsoluteUrl:92 [map.js 不得含 https:// 绝对 URL（spec §8.1 无 CDN）]
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
worktree_restored=a09ab32fddb0688b02f95040e58b7d38
⇒ 杀死（红在上面那组方法/行），工作树已逐字节还原
```

**红的正是被保护的那条断言**（`:92` 的 `https://` 检查在 `map.js` 上响），报告 mtime（1789766567）晚于本轮起点（1789766564），工作树逐字节还原。

### ★ 护栏判别力的一次当场修正（形态 5）

`absoluteUrlScannerHasTeeth`（本护栏的判定器自证）**首跑即红**：`<script src="//cdn.example.com/x.js">` 未被识别为协议相对 URL——根因是判定器把**引号**也算作"标识符字符"（前置字符 `"` 被当成标识符而排除）。`isIdentifierChar` 去掉引号后修复。**这正是"判定器自证"的价值**：没有它，`noWebuiAssetContainsAnAbsoluteUrl` 会在协议相对形态上**假绿**（CDN 引用漏网）。

---

## 4 手工/自动验收证据（`t9-evidence/`）

| 产物 | 内容 |
|---|---|
| `logs/evidence.txt` | 起真 Shell（`guiPort=0`、种创世 map/unit/social 三切片）后：① 4 页 + 6 静态资产的状态码 + `Content-Type`（全部 200，`text/html`/`text/javascript`/`text/css`）；② **契约交叉核对**——页面实际调用的 7 个只读端点逐一记录 JSON 形状（字段名可被后续 rename 对比捕获）；③ 三个写端点空体 ⇒ 400（路由在、不落 revision）；`/api/approvals` ⇒ 503（T6 未接入）；④ `node --check` 5 个 JS **全部 rc=0**；⑤ playwright 截图 |
| `screenshots/*.png` | `index`/`map`/`unit`/`social` + `map-loaded`（3 格已绘）+ `unit-detail`（选中 u-1）+ `social-query`（q=1,r=1 有数据）。**headless chromium 实测存在**（`chromium-1234`），故截图**做了**，非"未做" |
| `t9-screenshot.mjs` | 一次性截图脚本（playwright 走 npx 缓存 + 显式 `executablePath` 指向本机 chromium-1234）；页面 JS 错误/HTTP 失败计数 |
| `mutants/` | `mut-round.sh` + `m1.map.js` + `orig/map.js` |

**浏览器实测关键值**（`evidence.txt` §5）：`UNIT_ROWS 1`、`UNIT_STATUS 1 个单位。`、`SOCIAL_STATUS q=1, r=1 有数据`、`MAP_STATUS 已载入 3 格（mapId=Map1）`。
**页面 JS 错误**：`JS_ERRORS 2` —— ① `503 /api/approvals`（**预期**：T6 未接入，首页优雅显示）；② `favicon.ico` 404（**预期**：浏览器自动请求，仓内无该资产，`StaticHandler` 正确 404）。**均非页面缺陷**。

---

## 5 偏离 / 取代说明候选

1. **页内脚本单列文件**（`map.js`/`unit.js`/`social.js`）：spec §8.3 只点名三个**共享**资产（`app.js`/`api.js`/`styles.css`）。三页各自的页内逻辑另起一个相对引用的 `.js`，而非内联 `<script>`——保持"无构建、可单独做语法检查（`node --check`）、可被结构护栏扫描绝对 URL"三条一致。此为实现细节，**不改 spec 语义**。
2. **`/map` 的 region 描边未做**：spec §8.3 原文"地形色块、路径、区域描边**可后置**"。本期种创世地图 `regions`/`cities` 为空，画region描边无从验证；`map.js` 已读 `overview.regions`/`cities` 并在有城市时画标记，区域描边按 spec 允许**后置**。计划 T9 Step 1 的"超预算则降级为网格色块 + 单位标记"的规模估计：旧 `render.js` 379 行面向 `MapData`，本实现未移植其区域/路径层，规模在预算内但**有意只做只读最小集**。
3. **单位标记画在 Canvas 上未做**：任务书 Deliverables 1 写"unit markers"，但 `/api/map/overview` **不返回单位位置**（单位位置在 `/api/units` 与 `/api/map/hex` 的 facet 里）。`map.js` 的只读图源是 `overview`；单位在哪一格由**点击后** `/api/map/hex` 的 `unitsHere` facet 显示（spec §8.3 的"点击查格详情走 `/api/facets`"口径）。**在 overview 不提供单位位置的前提下，Canvas 上画全局单位标记需要前端额外拉 `/api/units` 并按 position 归并**——本期未做，记为**取代说明**：单位信息经格详情 facet 呈现，不做全局标记层。
4. **`index.html` 待批计数**：spec §8.4 描述"顶部含待批计数（轮询）"。本期首页保留占位并按 `app.js` 轮询 `/api/approvals`，503 时优雅降级为"审批未接入"（**不**因 503 抛错、不红屏）。完整审批面板（列表 + 批准/拒绝）归 T6。
5. **CJK 字体**：截图是 headless 容器，无 CJK 字体（`fc-list` 无 CJK 项），中文渲染为方块——**环境产物，非页面缺陷**（布局/宽度/各行高均正确）。真机浏览器有系统 CJK 字体。

---

## 6 我未能核实的

1. **真机浏览器目验未做**：仓库无 npm/构建；本机 headless chromium 已被 playwright 驱动跑通三页并截图（§4），但**没有框选/输入等真交互的逐帧目验**，也**没有在装了 CJK 字体的真机上目验**中文显示。⇒ 建议用户开 `ShellMain`（`--store <dir>` 指向已种创世的世界）后浏览器走查三页，确认中文字体与交互手感。
2. **单位页 8 条命令的表单"真提交成功"未逐条走**：`evidence.txt` §3 只验了三个写端点**路由在**（空体 ⇒ 400），没在浏览器里逐条填表提交成功（那需要能种出合法 payload 的世界 + 逐条命令 fixture）。命令 handler 本身的正确性由 M5 上游任务（T4 等）的测试覆盖，**T9 只到"表单能把字段装配成信封并发出去"**。
3. **`noWebuiAssetContainsAnAbsoluteUrl` 的 `//` 判定是启发式**：它认"`//` 后跟字母/数字/斜杠、且前置字符不是标识符/点/冒号/斜杠"的形态。**已知未覆盖**：数学上可能出现的中缀 `a/b//c/d`（前置是标识符 ⇒ 不判）等边角；本护栏的判据是"有没有绝对 URL"，对**常见 CDN 引用形态**有判别力（m1 已证），但不是形式化的 URL 解析器。`absoluteUrlScannerHasTeeth` 只钉住它声明覆盖的那几种形态。
4. **`map.js` 的 Canvas 布局在超大/非连通地图上的表现未测**：本期只测了 3 格走廊；多区域、极限坐标、高格数的渲染性能与自动缩放未验。
5. **契约交叉核对是"记录"不是"断言"**：`evidence.txt` 记下当前 JSON 形状供将来对差，但**没有**一个测试把"字段名变了就红"固化成断言（那会与 T8 的 `GuiApiTest` 逐值断言重复，且 T9 范围外）。字段 rename 的实时护栏仍靠 `GuiApiTest`。

---

## 7 证据索引

| 路径 | 说明 |
|---|---|
| `t9-evidence/logs/full-verify.log` | `./mvnw clean verify` 全量（rc=0；781 条；BugInstance 0 ×6；ERROR 0） |
| `t9-evidence/logs/m1.log` | 变异 m1（九道门禁逐项 + 装置补记的自指 md5） |
| `t9-evidence/logs/evidence.txt` | 起壳 + 页面/资产抓取 + 契约交叉核对 + `node --check` + playwright 截图日志 |
| `t9-evidence/screenshots/` | `index`/`map`/`social`/`unit` + `map-loaded`/`unit-detail`/`social-query` |
| `t9-evidence/mutants/` | `mut-round.sh`、`m1.map.js`、`orig/map.js` |
| `t9-evidence/t9-screenshot.mjs` | 一次性截图脚本（非交付，留作复现） |
