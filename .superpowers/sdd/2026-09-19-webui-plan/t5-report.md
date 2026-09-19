# M7 T5 报告 —— 左栏详情 + 单位编制倒树（判据⑥；并把判据③的左栏做完整）

- 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/m7t5`（分支 `m7/t5`，基线 `1502fd1`）
- 实现提交：`263236d`（本报告为补交付物，另一次提交）
- 边界：**零 Java 改动**、零依赖、零构建（无框架 / 无 npm / 无 CDN / 无外部字体）

---

## 一 改了什么 / 为什么

| 文件 | 增删 | 改动 | 为什么 |
|---|---|---|---|
| `simos-app/src/main/resources/webui/unitTree.js` | **+290**（311 行） | T2 空骨架 → **真倒树**：`buildTree`（纯函数森林）、`isBranchPoint`（子数 ≥ 2）、倒置渲染（根在下）、分岔点加粗放大、点分岔点展开/收起分支详情、`focusUnit` 定位 | 判据⑥ / R7。数据只来自 `/api/units` 的 `parent`，纯前端组树、零后端改动、不加第二份真相 |
| `simos-app/src/main/resources/webui/panels.js` | **+45 / -16** | ① hex 详情补 **人口**（`/api/social/population`，404 折成"无序列"）；② 单位详情补全 `equipment/speed/mobilityPerMille/movement`（原只有 id/name/parent/position/member） | 判据③ 要求 hex 出 `{q,r,terrain(含定义),height,region,该处单位,人口}`；DO 4 要求单位详情至少 `id/name/member/equipment/parent/position/movement`（与 `/api/unit/{id}` 同源） |
| `simos-app/src/main/resources/webui/styles.css` | **+126** | 新增 `.tree-forest/.tree-subtree/.tree-children/.tree-node(.branch/.selected)/.tree-branch-detail` 等 | 倒置布局（子行在节点上方）+ 分岔点视觉差（15px/700/黄描边 vs 12px/400） |

**倒置的实现**：DOM 是自然的（子行在前、节点在后），CSS 用 `flex-direction: column` 把子行摆在节点**上方** ⇒ 视觉上根在下、下级向上生长（spec §六 字面）。
**分岔点展开的语义**：点分岔点 ⇒ 展开/收起一个 `.tree-branch-detail` 区，列出**该节点 + 其全部后代**（spec §六"该节点 + 其全部后代"）；树本身始终完整可见（便于结构断言）。
**地图点单位联动**：`map.js` 早已发 `selection={kind:"unit",id}`；`panels.js` 出详情、`unitTree.js` 订阅状态变化把该节点加 `.selected` 并 `scrollIntoView` —— 本任务**未改 `map.js`/`app.js`**。

## 二 逐模块数字（全量 `clean verify`）

命令 `./mvnw clean verify`，日志 `t5-evidence/logs/full-verify.log`：

- **rc=0**，**833 条 = 170 / 255 / 45 / 131 / 154 / 78**（util / map / social / unit / core / app）
- 基线 `1502fd1` 同值 ⇒ **delta 0**（纯前端资源，不动任何测试）
- `BugInstance size is 0` **×6**；`[ERROR]` **0** 行
- 定向 `./mvnw -q -pl simos-app -am -Dtest=WebuiAssetsTest -Dsurefire.failIfNoSpecifiedTests=false test` rc=0（`WebuiAssetsTest` **8/8**），日志 `logs/targeted.log`

## 三 `buildTree` 冻结夹具 + 8 条断言（`node tree-check.cjs`，rc=0）

★ **本项目没有 JS 测试器**（无 npm / 无构建 / 无框架）⇒ 这是**证据级**检查，**不进 Maven 门禁**，与 T3/T4 的前端护栏同形态。**它不是 CI 护栏**——`tree-check.cjs` 只会在我手工跑它的那一刻成立，没有任何东西阻止它日后腐烂。

冻结夹具（覆盖 三层链 / 分岔点 / 单子非分岔 / 多根 / 缺失 parent / 悬空 parent）：

```
A(parent=null) → B(A) → C(B) → F(C)      B 另有子 D；A 另有子 E
G(parent=undefined)   H(parent="ghost")
```

逐条实测（`logs/tree-check.log`）：

| # | 断言 | 结果 | 实测值 |
|---|---|---|---|
| ① | `depth-parent-child-per-node` | **PASS** | `A:0/null/[B,E]`、`B:1/A/[C,D]`、`C:2/B/[F]`、`F:3/C/[]`、`D:2/B/[]`、`E:1/A/[]`、`G:0/null/[]`、`H:0/ghost/[]` |
| ② | `branch-point-recognition` | **PASS** | A(2子)=true、B(2子)=true、C(1子)=false、D/E/F/G/H(0子)=false |
| ③ | `branch-teeth-single-vs-double` | **PASS** | `C(1子)=false B(2子)=true D(0子)=false` |
| ④ | `multi-root-forest` | **PASS** | `roots=["A","G","H"]` |
| ⑤ | `missing-or-null-parent-is-root` | **PASS** | `G.parent=null` |
| ⑥ | `dangling-parent-becomes-root` | **PASS** | `H.parent="ghost" H.depth=0` |
| ⑦ | `descendant-count` | **PASS** | A=5、B=3、C=1，其余 0 |
| ⑧ | `empty-input` | **PASS** | `buildTree([])=[]` |

`TREE-CHECK RESULT: PASS`（rc=0）。

## 四 e2e 每步实测值（Playwright + 真 `ShellMain --demo`）

日志 `logs/e2e-clean.log`，`E2E RESULT: PASS`，`e2e_rc=0`，`server_ready_tries=3 code=200`。
夹具：bootstrap `u-1` + 用**既有 `unit.CreateUnit`**（`POST /api/command`，载荷含 `parent`）造 6 个单位 ⇒ `head 1→7`：
`t5-a`(根) → `t5-b`(子) → `t5-c`(子) → `t5-f`(子)；`t5-b` 另有子 `t5-d`（**两子=分岔点**）；`t5-a` 另有子 `t5-e`（两子=分岔点）。
（比任务单的 A→B→C 多一层 `t5-f` + 单子 `t5-c`：**m2 需要"恰有 1 个子的非分岔点"才有判别力**。）

| 步骤 | 结果 | 实测值 |
|---|---|---|
| `seed` | PASS | `head 1->7` |
| `tree-structure` | PASS | DOM 7 节点，**逐节点 == 手写冻结期望 且 == 页面内 `buildTree` 输出**：`t5-a:0/null/true`、`t5-b:1/t5-a/true`、`t5-c:2/t5-b/false`、`t5-d:2/t5-b/false`、`t5-e:1/t5-a/false`、`t5-f:3/t5-c/false`、`u-1:0/null/false`；`builtCount=7` |
| `branch-recognition` | PASS | `t5-a/b`：dom=true、built=true、expected=true；`t5-c/d/e/f/u-1`：dom=false、built=false、expected=false |
| `branch-visual` | PASS | 分岔点 `weight=700, size=15`；非分岔点 `weight=400, size=12` |
| `branch-expand` | PASS | 点 `t5-b` **前**：`{hidden:true, rows:4, visibleRows:0, rowIds:[t5-b,t5-c,t5-f,t5-d]}`；**后**：`{hidden:false, rows:4, visibleRows:4, rowIds:[t5-b,t5-c,t5-d,t5-f]}` |
| `leaf-detail` | PASS | 左栏文本 `id t5-f / name 己组 / parent t5-c / position q=2, r=5 / member 20 / equipment scout=1 / speed 3 / mobilityPerMille 2000 / movement false`，与 `/api/unit/t5-f` 返回逐字段一致 |
| `map-unit-link` | PASS | `selMap={kind:"unit", id:"u-1"}`，树节点 `u-1` 有 `.selected`（`selectedInTree=true`） |
| `revision-target-tree` | PASS | 游标到 rev1 ⇒ `treeAt1=["u-1"]`；请求 `http://127.0.0.1:5925/api/units?branch=main&revision=1`（出现 2 次） |
| `revision-target-detail` | PASS | 请求 `http://127.0.0.1:5925/api/unit/u-1?branch=main&revision=1`；面板 `id u-1 / name 第一连 / parent — / position q=1, r=1 / member 100 / equipment 步枪=50 / speed 2 / mobilityPerMille 500 / movement false`；`left-status="单位 u-1 · main@1"` |

截图（`t5-evidence/`）：`screenshot-tree.png`（倒树 + 加粗分岔点）、`screenshot-branch-expanded.png`（点分岔点后分支详情展开）。**两份我都亲眼看过**：根（第一连 / 甲部）在下，乙部在上、丙队/丁队再上、己组最上；甲部与乙部明显加粗放大（黄描边）；展开后虚线框列出"分支 t5-b · 共 4 个单位"及 4 行。

## 五 两变异轮的红点 + 九道门禁

装置 `t5-evidence/mutants/mut-round-e2e.sh`：目标 `unitTree.js`，**源 + classpath 两份推送**（StaticHandler 从 classpath `/webui` 读字节）。

| 轮 | 目标行 | 变异 | md5 | 期望红 | 实测红点 |
|---|---|---|---|---|---|
| **m1** | L72 `if (parent && parent !== node) {` | 加 `&& parent.children.length === 0`（**少挂子**：每父只留首子，其余成根） | `19ae5980efe96c5abfb3d2b7559a8e04` | `tree-structure` | ✅ `STEP tree-structure: FAIL`：`t5-d` depth **0**（应 2）、`t5-e` depth **0**（应 1）、`t5-a/b` branch **false**（应 true） |
| **m2** | L30 `return !!node && node.children.length >= 2;` | 改成 `>= 1` | `c6f5abdb6089ed65fe5c8f1d83f7e191` | `branch-recognition` | ✅ `STEP branch-recognition: FAIL`：`t5-c`（1 子）被误判 `dom=true,built=true`（应 false）；`branch-visual` 亦红（`t5-c` 也变成 700/15） |

**node 侧也红**（`logs/tree-check-m{1,2}.log`）：
- m1：`TREE-CHECK RESULT: FAIL depth-parent-child-per-node,branch-point-recognition,branch-teeth-single-vs-double,multi-root-forest,descendant-count`（如 `roots=["A","D","E","G","H"]`，`A` 只剩子 `B`）
- m2：`TREE-CHECK RESULT: FAIL branch-point-recognition,branch-teeth-single-vs-double`（`C(1子)` 变 `true`）

**九道门禁逐条**（两轮均过）：

| # | 门禁 | m1 | m2 |
|---|---|---|---|
| 1 | 干净世界（源 == 备份 == classpath） | `65aea1bb…` 三处一致 | `65aea1bb…` 三处一致 |
| 2 | 变异体字节不同 | `19ae5980… ≠ 65aea1bb…` | `c6f5abdb… ≠ 65aea1bb…` |
| 3 | 两份推送一致 | `mutant_md5 == pushed_classes_md5` | 同 |
| 4 | 服务器真起 | `m1.server.log` 含「GUI 服务器已启动」 | 同 |
| 5 | e2e 真跑 | `e2e_rc=1`，`E2E RESULT: FAIL` | 同 |
| 6 | 红点是被保护断言 | `STEP tree-structure: FAIL` | `STEP branch-recognition: FAIL` |
| 7 | server 日志 mtime 落轮内 | `server_log_mtime=1789783096 ≥ round_start=1789783093` | `…104 ≥ …099` |
| 8 | 源 + classpath 逐字节还原 | `restored_src == restored_classes == 65aea1bb…` | 同 |
| 9 | 日志自指 | `m1.log` 尾部"装置补记"含 `mutant_md5/pushed_classes_md5/restored_*` | `m2.log` 同 |

- **作废轮：无**。两轮的服务器都真起（`server_ready_tries` 正常、端口 5926/5927 未被占用），没有出现 T4 那种"端口占用 ⇒ 作废"。
- `COMPILATION ERROR=0` / `Tests run≥1` 两条形态不适用于资源类目标（不经 javac），故以"e2e 真跑到断言"替代。
- 附加：e2e 装置里曾两次因**拖拽坐标**失败（开发期，非变异轮）——见 §七.3。

## 六 行为约定：`parent` 指向不存在的 id

**约定（本任务裁定，派单书要求写进报告）：`parent` 指向不存在的 id ⇒ 该单位当根（root），原 `parent` 值原样保留，不报错、不篡改。**

- 冻结夹具实测：`H(parent="ghost")` ⇒ 出现在 `roots=["A","G","H"]`，`H.depth=0`，`H.parent` 仍为 `"ghost"`（`logs/tree-check.log` 的 ⑥）。
- 理由：spec §六 说"`parent` 为空的单位都是根；多根并列"且"前端不做环检测（不造第二份真相）"；悬空父指针在领域层本就被 `UnitState` 拒绝，前端把它当根是**降级显示**，比抛错让整棵左栏变空白更符合"只读展示"。
- **前提**：`buildTree` 假设输入无环（领域保证）。`count()` 是递归后序，环输入会栈溢出——按 spec 明文不做环检测，故**未测环夹具**。

## 七 偏离 / 取代说明候选

1. **游标移动用"点节点"而非"拖拽"**（唯一实质偏离）：任务 §4.3 说"拖游标到旧 revision"。实测**拖拽在本页不可靠**——时间轴在文档流底部，左栏树随 revision 变矮会让它上移（拖拽过程中 `line y 791→892`），固定 y 的 `pointermove` 滑出轨道 ⇒ `pointerleave` 终止拖拽，只到 rev6。T3 的 e2e 已单独覆盖拖拽（R1/R2）；T5 改用时间轴的 **click 处理器**（同为 `scrubTo` 移游标），要证的是"游标到旧 revision ⇒ 面板带 revision"，该点已证。**需控制器确认**。
2. **夹具比任务单多两层**（`t5-f`、单子 `t5-c`）：为让 m2（`>=1`）有判别力，必须存在"恰 1 个子的非分岔点"。
3. **e2e 结构断言加了"冻结字面量"一侧**：任务说"DOM 与 `buildTree` 的期望逐节点相等"，但仅此一侧在 m1 下会**自洽假绿**（两侧同源）；手写冻结期望才是 m1 的红点所在。
4. **e2e 里另有两处工程绕行**（非产品行为）：① 视口用 **1920 宽**——时间轴 7 节点在窄视口下横向溢出、末端节点被裁，`pointerdown` 落不到 `.timeline-line`（实测一次）；② 不用 Playwright 的 `locator.boundingBox()`——它在这些 `<button>` 上返回过 **y 偏移 +88.5px** 的错值（`getBoundingClientRect().y=791` vs `boundingBox().y=879.5`，pointerdown 落空），改用页面内 `getBoundingClientRect()`。
5. **任务单路径写法**：`webui/...` 实际是 `simos-app/src/main/resources/webui/...`（简写，非错误）。
6. **`UnitTreeTest`**：spec §〇.1 / 计划 T5 的落点写的是 Java 测试类名 `UnitTreeTest`，但本项目**无 JS 测试器** ⇒ 以证据级 `tree-check.cjs` 交付（任务单本身也如此要求）。**未新增任何 Java 用例**，故 delta 0。
7. 本机 `nproc=8`（CLAUDE.md 写 `nproc=2` 是另一台机器的记录）——仅环境说明。
8. **未发现与本单矛盾的源码事实**；以上均为补充/偏离，非任务单写错。

## 八 我未能核实的

1. **倒树的"倒着"方向是否符合用户原意**：spec §六 按"根在下、下级向上生长"字面实现；spec §九.2 自己就把它标为"可推翻项"（若原意是普通自上而下的树，改一处方向即可）。我**没有**向用户确认过原话"倒着的树状图"的语义。
2. **`tree-check.cjs` 不在任何 CI 门禁里**（无 JS 测试器）⇒ 它可能**静默腐烂**；本次的绿只在"当下手工跑过"的意义上成立。
3. **`PAGE-ERROR 404` 的 URL 未取证**：Playwright 只报 "Failed to load resource: 404"，未带 URL。我在 `GuiServer` 与 `webui/` 里**查无 favicon 路由**，判断是浏览器自动请求 `/favicon.ico`，但**没有捕获到确切 URL**，故这是推断不是实测。两条 404 都出现在面板交互之后，未影响任何断言。
4. **CJK 字体只是截图间接证明**：截图里中文正常渲染（本机装有 `~/.local/share/fonts/NotoSansSC*`），但我没有做字体度量/换行断言；**换机器（无 CJK 字体）时未测**。
5. **Playwright / Chromium 版本耦合**：e2e 硬编码 `CHROME=/home/cna/.cache/ms-playwright/chromium-1234/...` 与 `NODE_PATH=/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules`——**换机器/换 Playwright 版本即失效**。另实测到 `locator.boundingBox()` 的坐标错值（§七.4），是**版本相关的行为**，未在不同 Playwright 版本上复现或定位。
6. **真实大图（19441 hex）下的树未跑**：e2e 只用 demo（7 单位）；树是 O(n) 组树 + DOM 渲染，未测大编制下的性能与布局。
7. **多区域 / 多分支下的树未测**：只跑了单分支 `main`；分岔后多分支、以及区域高亮与树同时作用的形态未覆盖。
8. **环输入行为未测**：按 spec 不做环检测，`count()` 递归在环上会栈溢出，未造环夹具（领域拒绝，属无效输入）。
9. **`focusUnit` 的滚动定位只断言 `.selected` class**：`scrollIntoView` 后的实际可视位置未断言；长树下是否真"定位到"未视觉核。
10. **拖拽交互在 T5 未覆盖**（见 §七.1），依赖 T3 的既有证据。
11. **截图的美术判断是我自己看的**，未过独立视觉评审；树在 300px 左栏内偏拥挤、展开详情框有换行，可用性未做用户测试。

## 九 证据索引

```
.superpowers/sdd/2026-09-19-webui-plan/
  t5-report.md                      ← 本报告（派单书 §6 要求的产物）
  t5-evidence/
    tree-check.cjs                  树的纯函数自检（8 断言，rc=0）
    mut-manifest.md5                原件/变异体/脚本的 md5
    logs/targeted.log               定向 WebuiAssetsTest（rc=0，8/8）
    logs/full-verify.log            全量 clean verify（rc=0，833 = 170/255/45/131/154/78，BugInstance 0×6，ERROR 0）
    logs/tree-check.log             干净轮 8 断言 PASS
    logs/tree-check-m1.log          m1 node 侧红（5 条 FAIL）
    logs/tree-check-m2.log          m2 node 侧红（2 条 FAIL）
    logs/e2e-clean.log              e2e 干净轮（E2E RESULT: PASS，9 步）
    logs/demo-server.log            干净轮服务器日志（含「GUI 服务器已启动」）
    logs/m1.log / m1.server.log     m1 变异轮（红点 tree-structure）+ 服务器日志 + 自指补记
    logs/m2.log / m2.server.log     m2 变异轮（红点 branch-recognition）+ 服务器日志 + 自指补记
    e2e/e2e.cjs, e2e/run-e2e.sh     e2e 装置
    mutants/mut-round-e2e.sh        变异轮装置（源+classpath 两份推送）
    mutants/orig/unitTree.js[.classpath]   原件与 classpath 副本（还原基准，65aea1bb…）
    mutants/m1/unitTree.js          变异体（19ae5980…）
    mutants/m2/unitTree.js          变异体（c6f5abdb…）
    e2e-run/                        干净轮输出：tree-dom.json、tree-built.json、请求 JSON
    screenshot-tree.png             倒树（含加粗分岔点）
    screenshot-branch-expanded.png  点分岔点后分支详情展开
    t5-report.md                    同内容副本（首次提交时落在证据目录；本报告为权威版）
```
