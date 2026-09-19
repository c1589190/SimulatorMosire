# M7 T5 报告 —— 左栏详情 + 单位编制倒树（判据⑥；并把判据③的左栏做完整）

工作树：`/home/cna/SimulatorMosire/.claude/worktrees/m7t5`（分支 `m7/t5`，基线 `1502fd1`）。
零 Java 改动、零依赖、零构建（无框架/无 npm/无 CDN/无外部字体）。

---

## 一 改了什么 / 为什么

| 文件 | 改动 | 为什么 |
|---|---|---|
| `simos-app/src/main/resources/webui/unitTree.js` | T2 空骨架 → **真倒树**：`buildTree`（纯函数森林）、`isBranchPoint`（子数 ≥ 2）、倒置渲染（根在下）、分岔点加粗放大、点分岔点展开/收起分支详情、`focusUnit` 定位 | 判据⑥ / R7。数据只来自 `/api/units` 的 `parent`，纯前端组树、零后端改动、不加第二份真相 |
| `simos-app/src/main/resources/webui/panels.js` | ① hex 详情补 **人口**（`/api/social/population`，404 折成"无序列"）；② 单位详情补全 `equipment/speed/mobilityPerMille/movement`（原只有 id/name/parent/position/member） | 判据③ 要求 hex 出 `{q,r,terrain(含定义),height,region,该处单位,人口}`；DO 4 要求单位详情至少 `id/name/member/equipment/parent/position/movement`（与 `/api/unit/{id}` 同源） |
| `simos-app/src/main/resources/webui/styles.css` | 新增 `.tree-forest/.tree-subtree/.tree-children/.tree-node(.branch/.selected)/.tree-branch-detail` 等 | 倒置布局（子行在节点上方）+ 分岔点视觉差（15px/700/黄描边 vs 12px/400） |

**倒置的实现**：DOM 是自然的（子行在前、节点在后），CSS 用 `flex-direction: column` 把子行摆在节点**上方** ⇒ 视觉上根在下、下级向上生长（spec §六 字面）。
**分岔点展开的语义**：点分岔点 ⇒ 展开/收起一个 `.tree-branch-detail` 区，列出**该节点 + 其全部后代**（spec §六"该节点 + 其全部后代"），不隐藏树里的节点本身（树始终完整可见，便于结构断言）。
**地图点单位联动**：`map.js` 早已发 `selection={kind:"unit",id}`；`panels.js` 出详情、`unitTree.js` 订阅状态变化把该节点加 `.selected` 并 `scrollIntoView` —— 本任务**未改 `map.js`/`app.js`**。

## 二 门禁（真跑）

| 项 | 命令 | 结果 |
|---|---|---|
| 格式 | `./mvnw -q spotless:apply` | rc=0（未改其他文件） |
| 定向 | `./mvnw -q -pl simos-app -am -Dtest=WebuiAssetsTest -Dsurefire.failIfNoSpecifiedTests=false test` | rc=0（`WebuiAssetsTest` 8/8） |
| 全量 | `./mvnw clean verify` | **rc=0**，**833 条 = 170/255/45/131/154/78**（util/map/social/unit/core/app） |

- **delta 0**：纯前端（资源文件）⇒ 与基线 `833` 逐模块一致（170/255/45/131/154/78）。
- `BugInstance size is 0` **×6**；`[ERROR]` **0** 行。日志 `logs/full-verify.log`。

## 三 `buildTree` 冻结夹具与断言结果（`tree-check.cjs`，rc=0）

**证据级检查，不进 Maven 门禁**：本项目没有 JS 测试器（无 npm/无构建），`node tree-check.cjs` 是**证据级**的，与 T3/T4 的前端护栏同形态——**不是 CI 护栏**。

冻结夹具（覆盖 三层链 / 分岔点 / 单子非分岔 / 多根 / 缺失 parent / 悬空 parent）：

```
A(null) → B(A) → C(B) → F(C)     B 另有子 D；A 另有子 E
G(parent=undefined)  H(parent="ghost")
```

| # | 断言 | 结果 |
|---|---|---|
| ① | `depth-parent-child-per-node`：逐节点 深度/父/子 等于**手写冻结期望**（A0/B1/C2/D2/E1/F3/G0/H0） | PASS |
| ② | `branch-point-recognition`：A、B（2 子）为真；C（1 子）、D/E/F/G/H（0 子）为假 | PASS |
| ③ | `branch-teeth-single-vs-double`：判定器边界自证（1 子=false、2 子=true、0 子=false） | PASS |
| ④ | `multi-root-forest`：多根并列 `["A","G","H"]` | PASS |
| ⑤ | `missing-or-null-parent-is-root`：G 为根、`parent=null` | PASS |
| ⑥ | `dangling-parent-becomes-root`：H 的 `parent="ghost"` 指向不存在 id ⇒ **当根**、depth=0、原 parent 值**原样保留** | PASS |
| ⑦ | `descendant-count`：A=5、B=3、C=1，其余 0 | PASS |
| ⑧ | `empty-input`：`buildTree([])=[]`（不炸） | PASS |

**⑤/⑥ 的裁定（任务要求"你定，定完写进报告"）**：`parent` 指向不存在的 id ⇒ **当根**（降级显示），不报错、不篡改原值。
理由：spec §六 说"`parent` 为空的单位都是根；多根并列"且"前端不做环检测（不造第二份真相）"；悬空父指针在领域层本就被拒，前端把它当根比抛错让整棵左栏空白更符合"只读展示"。
**假设**：输入无环（领域 `UnitState` 的保证）；`count()` 是递归后序，环输入会栈溢出——按 spec §六 明文不做环检测，故未测环夹具。

## 四 e2e 每步实测值（Playwright + 真 `ShellMain --demo`，`E2E RESULT: PASS`，rc=0）

夹具：bootstrap `u-1` + 用**既有 `unit.CreateUnit`**（`POST /api/command`，载荷含 `parent`）造 6 个单位 ⇒ `head 1→7`：
`t5-a`(根) → `t5-b`(子) → `t5-c`(子) → `t5-f`(子)；`t5-b` 另有子 `t5-d`（**两子=分岔点**）；`t5-a` 另有子 `t5-e`（两子=分岔点）。
（比任务单的 A→B→C 多一层 `t5-f` + 单子 `t5-c`：**m2 需要"恰有 1 个子的非分岔点"才有判别力**。）

| 步骤 | 实测值 |
|---|---|
| `seed` | PASS `head 1->7` |
| `tree-structure` | PASS：DOM 7 节点，逐节点 == 冻结期望 **且** == 页面内 `buildTree` 输出。`t5-a:0/null`、`t5-b:1/t5-a`、`t5-c:2/t5-b`、`t5-d:2/t5-b`、`t5-e:1/t5-a`、`t5-f:3/t5-c`、`u-1:0/null` |
| `branch-recognition` | PASS：`t5-a/b`=true，`t5-c/d/e/f/u-1`=false（DOM 与 `buildTree` 双侧一致） |
| `branch-visual` | PASS：分岔点 `font-weight 700 / font-size 15px`，非分岔点 `400 / 12px` |
| `branch-expand` | PASS：点 `t5-b` 前 `hidden=true, visibleRows=0`；点后 `hidden=false, visibleRows=4`，行 = `[t5-b,t5-c,t5-d,t5-f]` |
| `leaf-detail` | PASS：左栏 == `/api/unit/t5-f`（id/name/parent/position/member/equipment/speed/mobilityPerMille/movement 逐字段在场） |
| `map-unit-link` | PASS：点地图 `u-1` 标记 ⇒ `selection={unit,u-1}`、左栏出 u-1、树节点 `.selected` |
| `revision-target-tree` | PASS：游标到 rev1 ⇒ 树只剩 `["u-1"]`，`/api/units` 请求带 `revision=1` |
| `revision-target-detail` | PASS：点 u-1 ⇒ `/api/unit/u-1?branch=main&revision=1`，`left-status="单位 u-1 · main@1"` |

截图：`screenshot-tree.png`（倒树 + 加粗分岔点）、`screenshot-branch-expanded.png`（点分岔点后分支详情展开）。两份我都**亲眼看过**：根（第一连/甲部）在下，乙部在上、丙队/丁队再上、己组最上；甲部与乙部明显加粗放大（黄描边）；展开后虚线框列出"分支 t5-b · 共 4 个单位"及 4 行。

## 五 变异表（九道门禁，2 轮，红点均落在被保护断言）

装置 `mutants/mut-round-e2e.sh`（源 + classpath 两份推送、逐字节还原、日志自指、服务器真起、红点校验）。

| 轮 | 目标行（`unitTree.js`） | 变异 | md5 | 期望红 | 实测红点 |
|---|---|---|---|---|---|
| **m1** | L72 `if (parent && parent !== node) {` | 加 `&& parent.children.length === 0`（**少挂子**：每父只留首子，其余成根） | `19ae5980…` | `tree-structure` | ✅ `STEP tree-structure: FAIL`（`t5-d/t5-e` depth 0 而非 2/1；`t5-a/b` branch=false） |
| **m2** | L30 `return !!node && node.children.length >= 2;` | 改成 `>= 1` | `c6f5abdb…` | `branch-recognition` | ✅ `STEP branch-recognition: FAIL`（`t5-c` 单子被误判为分岔点） |

- **node 侧也红**：`node tree-check.cjs <m1>` rc=1（`depth-parent-child-per-node` 等 5 条 FAIL）；`<m2>` rc=1（`branch-point-recognition`、`branch-teeth-single-vs-double` FAIL）。日志 `logs/tree-check-m{1,2}.log`。
- 两轮门禁全过：干净世界（源==备份==classpath，`65aea1bb…`）、变异体字节不同、两份推送一致、服务器真起（`GUI 服务器已启动`）、e2e 真红、红点是被保护步骤、server 日志 mtime 落轮内、源与 classpath 均逐字节还原（`restored_*=65aea1bb…`）。日志含"装置补记"自指段。

## 六 偏离候选 / 与任务单的出入

1. **游标移动用"点节点"而非"拖拽"**（唯一实质偏离）：任务 §4.3 说"拖游标到旧 revision"。实测**拖拽在本页不可靠**——时间轴在文档流底部，左栏树随 revision 变矮会让它上移（拖拽过程中 `line y 791→892`），固定 y 的 `pointermove` 滑出轨道 ⇒ `pointerleave` 终止拖拽，只到 rev6。T3 的 e2e 已单独覆盖拖拽（R1/R2）；T5 改用时间轴的 **click 处理器**（同样是 `scrubTo` 移游标），要证的是"游标到旧 revision ⇒ 面板带 revision"，该点已证。**这是一处需控制器确认的偏离**。
2. **夹具比任务单多两层**（`t5-f`、`t5-e`）：为让 m2（`>=1`）有判别力，必须存在"恰 1 个子的非分岔点"（`t5-c`）。
3. **e2e 结构断言加了"冻结字面量"一侧**：任务说"DOM 与 `buildTree` 的期望逐节点相等"，但仅此一侧在 m1 下会**自洽假绿**（两侧同源）。故另加**手写冻结期望**逐节点比对——这才是 m1 的红点所在。
4. **任务单路径写法**：`webui/...` 实际是 `simos-app/src/main/resources/webui/...`（仅简写，非错误）。
5. **`UnitTreeTest`**：spec §〇.1/计划 T5 的落点写的是 Java 测试类名 `UnitTreeTest`，但本项目**无 JS 测试器** ⇒ 以证据级 `tree-check.cjs` 交付（任务单本身也如此要求）。**未新增任何 Java 用例**，故 delta 0。
6. 本机 `nproc=8`（CLAUDE.md 写 `nproc=2` 是另一台机器的记录）——仅环境说明。
7. **未发现与本单矛盾的源码事实**；上述均为补充/偏离，非任务单写错。

## 七 我未能核实的

1. **`tree-check.cjs` 不在任何 CI 门禁里**（无 JS 测试器）⇒ 它可能**静默腐烂**；本次的绿只在"当下手工跑过"的意义上成立。
2. **页面控制台两条 404 的 URL 未取证**：Playwright 只报 "Failed to load resource: 404"，未带 URL。我在 `GuiServer` 与 `webui/` 里**查无 favicon 路由**，判断是浏览器自动请求 `/favicon.ico`，但**没有捕获到确切 URL**，故这是推断不是实测。
3. **真实大图（19441 hex）下的树未跑**：e2e 只用 demo（7 单位）。树是 O(n) 组树 + DOM 渲染，未测大编制。
4. **环输入行为未测**：按 spec 不做环检测；`count()` 递归在环上会栈溢出，但我没造环夹具（领域拒绝，属无效输入）。
5. **`focusUnit` 的滚动定位只断言了 `.selected` class**，`scrollIntoView` 后的实际可视位置未断言；长树下是否真"定位到"未视觉核。
6. **拖拽交互在 T5 未覆盖**（见偏离 1），依赖 T3 的既有证据。
7. 截图的美术判断是**我自己看的**，未过独立视觉评审。

## 八 证据索引

```
t5-evidence/
  tree-check.cjs                  树的纯函数自检（8 断言，rc=0）
  tree-check.cjs -- <mutant>      变异体下 node 侧红（logs/tree-check-m{1,2}.log）
  mut-manifest.md5                原件/变异体/脚本的 md5
  logs/targeted.log               定向 WebuiAssetsTest（rc=0）
  logs/full-verify.log            全量 clean verify（rc=0，833 = 170/255/45/131/154/78）
  logs/tree-check.log             tree-check 干净轮（8 PASS）
  logs/tree-check-m1.log          m1 node 侧红
  logs/tree-check-m2.log          m2 node 侧红
  logs/e2e-clean.log              e2e 干净轮（E2E RESULT: PASS，9 步）
  logs/demo-server.log            e2e 干净轮服务器日志（含「GUI 服务器已启动」）
  logs/m1.log / m1.server.log     m1 变异轮（红点 tree-structure）+ 服务器日志 + 自指补记
  logs/m2.log / m2.server.log     m2 变异轮（红点 branch-recognition）+ 服务器日志 + 自指补记
  e2e/e2e.cjs, e2e/run-e2e.sh     e2e 装置
  mutants/mut-round-e2e.sh        变异轮装置
  mutants/orig/unitTree.js[.classpath]   原件与 classpath 副本（还原基准）
  mutants/m1/unitTree.js, m2/unitTree.js 变异体
  e2e-run/                        干净轮输出：tree-dom.json、tree-built.json、请求 JSON
  screenshot-tree.png             倒树（含加粗分岔点）
  screenshot-branch-expanded.png  点分岔点后分支详情展开
```
