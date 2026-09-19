# M7 T2 报告 —— 单页工作台骨架 + 五模式栏 + 资源拆分 + `terrainTypes` 形状切换

> 任务：WebUI 可视化骨架的第二个任务。工作树 `/home/cna/SimulatorMosire/.claude/worktrees/m7t2`，
> 分支 `m7/t2`，基线 `07f3a40`（`docs(m7): T1 关账`）。设计见 `docs/superpowers/specs/2026-09-19-webui-design.md`
> §〇.1 判据① / §〇.4 取代说明 / §四 前端形态 / §七 R6；计划见 `docs/superpowers/plans/2026-09-19-webui-plan.md` T2。

---

## 一 改了什么 / 为什么

| # | 文件 | 改动 | 为什么 |
|---|---|---|---|
| 1 | `simos-app/.../gui/ApiViews.java` | `mapOverview` 的 `terrainTypes` 由 `[key…]` 改为 `[完整定义…]`；新增私有 `terrainTypeDefinitions(map)` 与 `terrainType(TerrainType)` | spec §3.2 / 判据①。**数据来源仍是状态里的 `map.terrainTypes()`**（不查 `TerrainCatalog.defaults()`——那是第二份真相）；`TerrainCatalog.KEYS` **只用来定序**（高度升序），词表外的 key 排末尾并按字典序，保证响应字节可复现 |
| 2 | `simos-app/.../gui/GuiApiTest.java` | T1 留下的 `terrainTypes[0] == "desert"` 断言改为新形状：断言 `terrainTypes` 是对象数组、逐字段等于 `TerrainCatalog.of("desert")` | 与 #1 **同批落地**（计划 §〇 的原子性约定）；否则任务末门禁必红 |
| 3 | `simos-app/.../resources/webui/index.html` | `/` 由链接首页改为**工作台骨架**：顶部模式栏（五按钮）+ 中部三栏（左详情 / 中 Canvas 容器 / 右面板）+ 底部时间轴容器；引用 `panels.js`/`unitTree.js`/`timeline.js` | 判据①（`GET /` 即主应用）。旧 `map.html`/`unit.html`/`social.html` 一字未动，仍可访问 |
| 4 | `simos-app/.../resources/webui/app.js` | 变**状态机**：持有 `{mode, branch, revision, selection}`，暴露 `getState/setMode/setBranch/setRevision/setSelection/onStateChange/applyMode`；模式切换只改 `[data-modes]` 可见性与按钮选中态；`pollState` 把 `/api/state` 的 branch/revision 写回状态机；`boot` 在工作台页接线三个骨架脚本 | spec §四「单页 + 模式状态机」。**保留**既有 `boot/mountNav/mountApprovals/pollState/statusMessage/fieldValue/intField` 原行为（旧三页继续用） |
| 5 | `simos-app/.../resources/webui/panels.js`（新增） | 左栏详情 / 右栏区域面板骨架：挂载占位 + 订阅状态刷新左栏提示 | 计划 T2 步骤 4。功能留 T5/T6 |
| 6 | `simos-app/.../resources/webui/unitTree.js`（新增） | 单位倒树骨架：`buildTree` 空实现 + 挂载点 | 计划 T2 步骤 4。功能留 T5 |
| 7 | `simos-app/.../resources/webui/timeline.js`（新增） | 底部时间轴骨架：挂载点 + 分支/rev 元信息随状态刷新 | 计划 T2 步骤 4。画/拖/分岔留 T3 |
| 8 | `simos-app/.../resources/webui/styles.css` | 追加工作台布局（模式栏 / 三栏 / 底栏），**全部复用既有 `--*` 变量** | spec §四布局图。任务 MUST DO #3：不另起一套配色 |
| 9 | `simos-app/.../resources/webui/map.js` | 图例适配 `terrainTypes` 新形状（对象数组取 `.key`，兼容旧字符串） | 与 #1 同批；否则 `/map` 图例会打成 `[object Object]=0` |
| 10 | `simos-app/src/test/.../gui/WebuiAssetsTest.java` | 扩条：新增 `rootServesTheWorkbenchSkeleton` / `editModesAreVisibleButDisabledAndMarkedM8` / `buttonScannerHasTeeth`；`ALL_ASSETS` 纳入三个新 js；非空自证门槛 7→10 | R6 + 判据①的前端结构护栏；保留既有「无绝对 URL 判定器自证」用例（未破坏） |

**未改**：`simos-core`（**零改动**——T2 不碰 Core）；`GuiServer` 路由集合与其它端点形状；`ToolSupport`（MCP 的
`terrainTypes` 仍是 `[key…]`——那是另一个端点，任务只授权 `/api/map/overview` 的形状变更）；旧三页
`map.html`/`unit.html`/`social.html` 与 `unit.js`/`social.js`；`api.js`。**无新增依赖、无新模块、无构建、无 npm/CDN。**

### 与派单书的分歧（以源码为准）

1. **`terrainTypes` 的「完整定义」范围**：派单书说「顺序 = `TerrainCatalog.KEYS` 的高度升序」。实测状态里的
   `map.terrainTypes()` 是**子集**（`DemoWorld` 只有 `desert`；`tools/gsimap_import.py` 也只写用到的 key）。
   故实现为「**取状态里的词表**，按 `TerrainCatalog.KEYS` 定序」——若理解成「无条件输出全 7 项」，
   就与 T1 确立的「词表只有一个来源（状态）」冲突，且导入器的真实档只有子集。**这一点派单书未说清，我按 T1 口径裁决。**
2. **`approvals`/`state` 轮询的归属**：派单书说「可迁到 `panels.js`」。我**保留在 `app.js`**（`boot` 里调用），
   因为旧三页也经 `app.boot({approvals:...})` 使用它——迁走会动到三页的回归面。行为未丢（实测顶栏与「待批: 0」在场）。

---

## 二 实测数字

### 定向（`./mvnw -pl simos-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WebuiAssetsTest,GuiApiTest' test`）

- rc=0；`WebuiAssetsTest` **8** 条、`GuiApiTest` **16** 条，全绿。日志 `logs/targeted.log`。
- `WebuiAssetsTest` 5→8（+3 = 本任务三条新用例）；`GuiApiTest` 16 条不变（只改断言内容，不加用例）。

### 全量（`./mvnw clean verify`，冻结源码）

- rc=0；**833** 条 = **170 / 255 / 45 / 131 / 154 / 78**（util/map/social/unit/core/app）。
- 基线 **830** = 170/255/45/131/154/**75**（派单书 §4.3 给的值，与 T1 关账记录一致）。**逐模块对差**：
  前五个模块一个都没动，**app 75→78 恰 +3** ＝ `WebuiAssetsTest` 的三条新用例。**无其它 delta。**
- `BugInstance size is 0` ×6、`[ERROR]` 0 行。日志 `logs/full-verify.log`。

### 端到端（真 `ShellMain` + 真 `StaticHandler`，`--store /tmp/m7t2-e2e-store --demo --gui-port 5821`）

| 探针 | 实测 |
|---|---|
| `GET /` | 200、2545 字节、含 `id="mode-bar"` ×1、`data-milestone="M8"` ×2 |
| `GET /map` `/unit` `/social` | 全部 **200**（旧三页仍可访问，MUST DO #1） |
| `GET /api/map/overview` | `terrainTypes` 已是**对象数组**，唯一项 `key=desert / name=沙漠 / color=#E7C86E / minHeight=0.45 / maxHeight=0.55 / food=0 / gold=1 / stone=1 / moveCost=3 / description=…`（十字段全在场） |

- 原始产物：`curl-root.html` / `curl-overview.json` / `e2e-curl.txt` / `logs/e2e-shell.log`。
- 核验后已关闭该进程（`pgrep` 实测无 `m7t2-e2e-store` 进程；另有两个**不属于本任务**的旧 ShellMain
  进程（`/tmp/simos-demo-store:5817`、`/tmp/m6-import-verify:5818`）**未动**）。

### 截图（headless chromium，Playwright 驱动点击）

- `screenshot-view.png`：`常规查看` 激活（蓝底高亮），两个编辑按钮置灰带「归 M8」，右栏无「区域」区段。
- `screenshot-region.png`：`区域查看` 激活，`常规查看` 退出激活，右栏多出「区域」区段（T6 占位）。
- 底栏均显示 `分支 main · rev 1`（状态机从 `/api/state` 回填，非占位 `—`）；顶栏 `分支 main · rev 1 · tick 5`。
- CJK 全部正常（无豆腐块），三栏/底栏无错位。md5：view `f4604d48bd5faf3af8cfb684da988f62`，
  region `bda700f4c439848168b0108b7c914b0a`。

---

## 三 变异表（九道门禁，2 轮，0 存活）

装置 `.superpowers/sdd/2026-09-19-webui-plan/t2-evidence/mutants/mut-round.sh`（照 T1 骨架，针对**静态资源**目标
去掉「推成类名 / 清 `.class`」两步——测试直接读 `src/main/resources/webui/` 的字节；其余七道一条不少）。
每轮日志末尾有「装置补记」段，把本轮推送的 `orig_md5` / `mutant_md5` / 还原后 md5 写进日志本身（形态 6）。

| m | 护栏 | 变异体 | 期望红 | 实测红点（日志） |
|---|---|---|---|---|
| **m1** | **R6** 资产纪律 | `panels.js` 追加 `var CDN = "https://cdn.example.com/x.js";` | 判定器红 | `WebuiAssetsTest.noWebuiAssetContainsAnAbsoluteUrl:106 [panels.js 不得含 https:// 绝对 URL（spec §8.1 无 CDN）]`；`Tests run: 8, Failures: 1` |
| **m2** | **判据①** 模式栏 | `index.html` 去掉「地图编辑」按钮的 `disabled` | 资产断言红 | `WebuiAssetsTest.editModesAreVisibleButDisabledAndMarkedM8:152 [「地图编辑」必须 disabled]`；`Tests run: 8, Failures: 1` |

- 两轮均：`rc=1`、`COMPILATION ERROR`=0、`Tests run≥1`（8）、surefire 报告 mtime 落轮内、红点落在**被保护断言本身**、
  `cp` 逐字节还原（`worktree_restored` == `orig_md5`）。`mut-manifest.md5` 记录四份字节的 md5。
- **形态 5 的坑已避**：本轮没有出现「Checkstyle 先于测试导致 `Tests run=0`」的作废轮——变异目标是资源，
  不产生未用 import。

---

## 四 偏离 / 取代说明候选（供控制器裁决）

1. **`terrainTypes` 子集语义**（见 §一 分歧 1）：实现取「状态词表 ∩ 按 KEYS 定序」。若控制器要求「无条件全 7 项」，
   需把数据源改为 `TerrainCatalog.defaults()`——但那与 T1 的「词表只有一个来源」相抵。**建议维持本实现**，
   并在 spec §3.2 补一句「取状态里的词表」。
2. **`?mode=` 深链未加**：为满足「区域查看」截图，我用 Playwright 真点按钮，**未**给前端加 query 参数入口（避免范围外功能）。
   若后续希望可分享 URL，归 T3+。
3. **旧三页退役**：spec §九-6 留到关账决定；本任务**原样保留**（含 `unit.js` 的命令表单回归面）。
4. **`/api/map/overview` 的 `terrainTypes` 顺序在多词表下未被测**：夹具/演示世界只有 `desert` 一项
   （见「我未能核实的」#2）。

---

## 五 我未能核实的

1. **`terrainTypes` 多词表排序未实测**：`GuiApiTest` 夹具与 `--demo` 世界都只有 `desert` 一项，
   「按 `TerrainCatalog.KEYS` 高度升序」这条**没有 >1 项的实测证据**。排序逻辑读起来正确，
   但「读起来对」不等于「跑过」（形态 5）。真实导入档（多地形）未在本任务跑。
2. **模式切换的「可用性」只证到按钮级**：三个可用模式的**选中态**由截图证明；但「切换后各面板的可见/可用」
   只实现了右栏「区域」区段的显隐（`[data-modes]`），其余面板在 T2 阶段对所有模式都可见——这是骨架的有意取舍，
   真正的模式语义归 T3~T7。截图只覆盖了 `常规查看`/`区域查看` 两态。
3. **`disabled` 按钮的「点击无响应」**：截图只能证明视觉置灰；`mountModeBar` 对 `disabled` 按钮加了早退守卫，
   但**没有浏览器级实测**「点击被禁按钮不改状态」（无 JS 单测装置）。
4. **Playwright 版本耦合**：截图用 `~/.npm/_npx/e41f203b7505f1fb` 下的 Playwright 1.63.0 + `chromium-1234`。
   换机器若版本不同，截图命令需重取路径（这属运行环境，不属代码）。
5. **旧三页的 JS 未被本任务的测试覆盖**：`map.js` 的图例改动只做了语法级核对（新形状取 `.key`），
   未在 `/map` 页做真渲染断言——`/map` 页 200 已证，但图例文本内容未截图/断言。

---

## 六 证据索引

```
.superpowers/sdd/2026-09-19-webui-plan/t2-evidence/
├── logs/
│   ├── targeted.log        # 定向：WebuiAssetsTest 8 + GuiApiTest 16，rc=0
│   ├── full-verify.log     # 全量 clean verify：833 = 170/255/45/131/154/78，BugInstance 0 ×6，ERROR 0
│   ├── m1.log              # 变异 m1（含装置补记：orig/mutant/restored md5）
│   ├── m2.log              # 变异 m2（含装置补记）
│   └── e2e-shell.log       # 真 ShellMain 启动日志
├── mutants/
│   ├── mut-round.sh        # T2 变异装置（资源目标版九道门禁）
│   ├── orig/{panels.js,index.html}
│   ├── m1/panels.js        # 注入 https://cdn…
│   └── m2/index.html       # 去掉 地图编辑 的 disabled
├── mut-manifest.md5        # 四份字节的 md5
├── curl-root.html          # GET / 的真响应（含 id="mode-bar"、2×data-milestone="M8"）
├── curl-overview.json      # GET /api/map/overview 的真响应（terrainTypes 新形状）
├── e2e-curl.txt            # 端点探针汇总（/、/map、/unit、/social、terrainTypes）
├── screenshot-view.png     # 常规查看
└── screenshot-region.png   # 区域查看
```
