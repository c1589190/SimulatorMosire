# M7 关账报告 —— WebUI 可视化骨架（Simos 工作台）

> **结论**：**M7 8/8 完成**。判据 ①~⑥ **逐条实测闭合**；R1~R8 **每条都有变异自证**（8 条全数被杀）；
> 主树全量门禁绿。代码终态 = 合并 `7307f78`（`feat/adr1-core-scope`）。
> 设计 `docs/superpowers/specs/2026-09-19-webui-design.md`；计划 `docs/superpowers/plans/2026-09-19-webui-plan.md`；
> 逐任务裁定见 `.superpowers/sdd/2026-09-19-webui-plan/progress.md`（裁定 68~73）。

---

## 一 判据逐条实测值（**不是"通过"，是值**）

### ① 开场即地图（T2）

| 项 | 实测 |
|---|---|
| `GET /`（真 `ShellMain --demo` + 真 `StaticHandler`） | **200 / 2545 B**，含 `id="mode-bar"` ×1、**`data-milestone="M8"` ×2** |
| 旧三页仍可访问 | `GET /map` `/unit` `/social` **全 200** |
| 五模式按钮 | 常规查看 / 区域查看 / **地图编辑（disabled+归 M8）** / **区域编辑（disabled+归 M8）** / 单位移动与编辑 |
| 截图核对（控制器目视） | 顶栏「Simos 工作台」+ 五按钮、三栏、底栏时间轴占位；CJK 正常 |

### ② 时间轴（T3；U1 形态）

| 项 | 实测 |
|---|---|
| 节点数 == head | `nodes=3 head=3`（先造 3 个 revision） |
| 节点标签 | `["rev 1 · Bootstrap","rev 2 · RenameUnit","rev 3 · RenameUnit"]` |
| `isAtTip` 纯函数（中间/末端/缺分支/null） | `{mid:false, tip:true, missing:false, empty:false}` |
| **拖到中间节点** | 游标 rev 2；**两按钮 `disabled=true`**；★ **`head 3->3 rows 3->3`（只读预览不写盘）** |
| 拖到末端 | 两按钮可用 |
| 真鼠标拖动 | 只读预览 + 置灰，`head=3 rows=3` |
| **分岔** | `branches=["b2","main"]`、时间轴 `lines=2`、游标在新末端 |
| **409** | 外部推 head(3→4) ⇒ 收 409 ⇒ 提示「**末端已移动，已自动重取最新状态**」、head 自动更新为 4、按钮随之置灰；**只重取、未静默重试写** |
| 写后节点增加（T7 复核） | 移动单位后 `head 1→2`、**时间轴节点 1→2** |

### ③ 点选详情（T4；左栏真读数）

| 项 | 实测 |
|---|---|
| 点 hex ⇒ 左栏 | `terrain`/`height`/`region`/该处单位/人口（真读数，非占位） |
| **取数带 revision** | 拖游标到 rev 2 后点 hex ⇒ 请求 URL = `…/api/map/hex?q=1&r=1&branch=main&**revision=2**`，面板显示 **rev2 的值**（`u-1 甲`；rev3 为 `乙`） |
| 缩放后点选准确 | scale `1→6.748`、锚点漂移 `1.7px`、点选 `{hex,1,3}` |
| 平移后点选准确 | `tx 100.67→190.67`、点选 `{hex,1,2}` |
| 点单位 ⇒ 左栏 | `{unit,u-1}` ⇒ `id/name=乙/position/member=100` |

### ④ 区域查看（T6）

| 项 | 实测 |
|---|---|
| **真实数据**（M6 导入器现场生成，19441 格） | `test_annex_target`(201格,tag=`Nation`,color=`#fc6dce`)、`test_nation`(701格,tag=`Nation`,color=`#d370d6`) |
| 按 tag 分组 | `Nation 2` → 两个区域项（`201 格` / `701 格`） |
| 点区域 | `highlightRegions == ["test_nation"]`，**高亮 hex 数 `701 == 701`** |
| 点标签 | `["test_annex_target","test_nation"]`（集合相等）；★ **`distinct hex == union 701`**（`ΣhexCount=902` 因两区**重叠**，见裁定 72.1） |
| **填充色 == `meta.color`** | `filledPixel [186,151,151]` == `blend(#d370d6, base, 0.42)`；两色都在场 |
| 分组纯函数自检 | **14 断言全 PASS**（null/undefined/空串/纯空白/缺 meta ⇒ "未标注"；桶序；入参不变；乱序等价；色解析 3 条） |

### ⑤ 单位移动与编辑（T7；真写）

| 项 | 实测 |
|---|---|
| 点选式移动 | `position {1,1}→{1,2}`、`head 1→2`、时间轴节点 `1→2`、地图层同步、状态「已移动 u-1 → (1,2)」 |
| 路线式移动 | `unit.PlanRoute` 真发 ⇒ `movement false→true`、`head 2→3` |
| 编制：改上级 | `parent null→"u-2"`（`head 4→5`） |
| 编制：改兵力 | `member 100→123`、`equipment {步枪:50}→{步枪:7}` |
| 编制：新建/解散 | `CreateUnit u-2`（无上级）/ `u-3`（**带 parent=u-2**）；`DisbandUnit u-3` ⇒ `GET /api/unit/u-3` **404** |
| **422 可见** | 页面显示 `reason` 原文「**父单位不存在: ghost-parent**」；head 不变 |
| **409** | 「末端已移动…」+ head 8→9 + ★ **位置未变（确实没写）** |

### ⑥ 单位编制倒树（T5）

| 项 | 实测 |
|---|---|
| 树自检（8 断言全 PASS） | **4 层链 A→B→C→F** 逐节点 `depth/parent/children`；`branch-point-recognition`（2 子 true / 1 子 false）；多根 `[A,G,H]`；**`parent="ghost"` ⇒ 当根 `depth 0`**；空输入 |
| e2e 树结构 | DOM 与 `buildTree` **逐节点一致**（层级用既有 `unit.CreateUnit` 真造：`head 1→7`） |
| ★ **"标大"的数值证据** | **`weight 700 vs 400`、`size 15 vs 12`** |
| 点分岔点展开 | `visibleRows 0 → 4`（收起/展开） |
| 点叶子 ⇒ 详情 | 与 `/api/unit/t5-f` **逐字段一致** |
| 地图点单位 ⇒ 树定位 | `selectedInTree=true` |
| 倒置方向 | **根在下**（截图：`第一连 u-1` 在底，向上长出 `甲部→乙部/戊队/丙队/丁队/己组`） |

---

## 二 R1~R8 点验（**每条都有变异自证**）

| # | 护栏 | 载体 | 变异（期望红） | 状态 |
|---|---|---|---|---|
| **R1** | 只读预览不写盘 | T3 e2e（`head 3->3 rows 3->3`） | 拖动里误发一次 `advance` ⇒ `STEP e-readonly: FAIL head 3->4 rows 3->4` | ✅ |
| **R2** | 末端才可写 | T3 e2e（中间节点两按钮 disabled） | 去掉末端判定 ⇒ `STEP e-mid-disabled: FAIL create_disabled=false` | ✅ |
| **R3** | 时间轴节点与库一致 | T1 `TimelineTest` + `GET /api/timeline` | `listRevisions` 去 `WHERE branch=?` ⇒ 分支隔离断言红 | ✅ |
| **R4** | 区域分组（含未标注桶） | T6（14 断言 + e2e） | 丢掉未标注桶 ⇒ `STEP group-null-bucket-fixture: FAIL` | ✅ |
| **R5** | hex 详情完整性（`region`/`terrainType`） | T1 `GuiApiTest` | `regionOfHex` 换常返空 ⇒ region 断言红 | ✅ |
| **R6** | 前端资产纪律（无 CDN/绝对 URL） | T2 `WebuiAssetsTest`（含判定器自证） | 塞 `https://cdn…` ⇒ 判定器红 | ✅ |
| **R7** | 倒树父子/深度 | T5 `tree-check.cjs` 8 断言 | 少挂一个子 ⇒ 深度断言红；"≥2 子"改"≥1"⇒ 分岔识别红 | ✅ |
| **R8** | 写路径 allowlist + 只读模式不写 | T7 e2e（非 GET 清单 + f 步） | 写调用改到 `/api/raw-write` ⇒ `STEP e-write-allowlist: FAIL` | ✅ |

**变异轮统计**：T1(2) T2(2) T3(2) T4(2) T5(2) T6(2) T7(2) = **14 轮 0 存活**；T8 关账轮见 §四。
★ 两条**被装置当场作废**的轮次（不是失败，是纪律生效）：T1 m2 首版（Checkstyle `UnusedImports` 先于测试 ⇒ `Tests run=0` + 陈旧 surefire）、T4 m2 首版（端口占用 ⇒ 服务器没起）。

---

## 三 门禁

`./mvnw clean verify`（主树，合并 T7 后）：**rc=0**、**833 条 = 170/255/45/131/154/78**、7/7 模块、
`BugInstance size is 0` **×6**、`[ERROR]` **0**。日志 `t8-evidence/merged-full-verify.log`。

**逐任务 delta（纯前端任务一律 0）**：T1 core+1/app+4（→830）；T2 app+3（→833）；**T3~T7 均 delta 0**（纯 `webui/**` 资源，不动任何测试）。
⇒ **M7 只给 core 加了 1 条用例、app 加了 7 条**——**整个 WebUI 是"零 Java 改动"建起来的**（M7 只碰了 `Timeline`+`CoreSimos`+`GuiServer`+`ApiViews` 四个 Java 文件，且都是**只读增量**）。

---

## 四 交付物

| 层 | 交付 |
|---|---|
| Core（唯一改动） | `Timeline.listRevisions` + `CoreSimos.revisions`（**纯只读、无 schema/语义改动**） |
| app 只读面 | `GET /api/timeline`；`/api/map/hex` +`region`/`terrainType`；overview 的 `terrainTypes` 改完整定义 + region 项 +`meta`；新增 `GET /api/map/region/{id}` |
| 前端 | 单页工作台（`/`）+ 五模式栏 + 三栏 + 底部时间轴 + Canvas（缩放/平移/区域填充/点选）+ 左栏详情 + 单位倒树 + 区域标签面板 + 单位移动编辑；`webui/` 现 **10 → 13 文件**（新增 `timeline.js`/`panels.js`/`unitTree.js`）；**无框架、无构建、无 CDN** |
| 测试 | `TimelineTest` +1、`GuiApiTest` +4、`WebuiAssetsTest` +3（Java 侧）；**前端护栏为证据级**（node 自检 + Playwright e2e + 截图） |

---

## 五 带裁定的遗留 / 开口项（**M8 必读**）

1. ★★ **前端护栏没有 CI 级保障**（裁定 71.2，**系统性**）：本项目**没有 JS 测试器** ⇒ T3~T7 的前端护栏（时间轴纯函数、几何换算、`buildTree`、分组、写路径 allowlist）**全部是"证据级"**（node 自检脚本 + Playwright e2e + 截图），**不进 Maven 门禁** ⇒ 有**静默腐烂**风险（`tree-check.cjs`/`group-check.cjs` 躺在证据目录里，没人会再跑）。`AppWritePathGuardTest` 只扫 `simos-app/src/main/java`，**不扫 `webui/**` 的 JS**。**建议**：引入 JS 测试器，或把 node 自检接进 Maven（`exec-maven-plugin`）。**在此之前，前端护栏强度低于后端。**
2. ★ **大图性能（裁定 70）**：服务端取 overview（含 `Replay`）**40–85 ms** ⇒ **"拖动时间轴每次重放很贵"被实测推翻**；真正的瓶颈是 **~1MB 响应体在浏览器内的传输（~2.6 s，99.8% 在 `transfer`）** 与 **整图首帧同步渲染（~2.55 s）**。行动项归 M8/后续：overview 按视口分级、或响应压缩（`HttpServer` 目前无 gzip）。
3. ★ **区域重叠的归属语义未核**（T4/T6 两次挂起）：`RegionIndex` 在重叠时"谁赢"**未查证**（`test_nation` 首格经权威解析归 `test_annex_target`）；**M8 允许画重叠区域时会直接撞上 ⇒ M8 spec 必须先裁**。
4. **`PlanRoute` 稀疏路点缺口**（M5 挂起）：载荷只给 `waypoints` 且要求逐格相邻 ⇒ **无法表达"稀疏路点+更细 path"**；T7 按逐格实现、**不伪造稀疏语义**。补载荷字段归后续。
5. **词表外兜底色分支未实测**（地形 `#ff00ff` / 区域 `#00e5ff`）：两个真实世界的地形与区域色都在词表/合法集内。
6. **倒树方向（根在下）** 按原话字面实现；若原意是普通自上而下树，**改一处方向**即可（可推翻）。
7. **旧三页的退役**（spec §九.6）：**裁定 73：M7 保留** `map.html`/`unit.html`/`social.html` 为**调试/回归页**（它们含命令表单与人口序列，是既有回归面）；退役与否留待有明确需求时再定。
8. 其它如实记：`PAGE-ERROR 404`（疑似 favicon，**未抓到 URL**）；CJK 仅截图间接证明；Playwright/Chromium 版本与硬编码路径耦合（换机需重取）；`#shell-state` 显示的是默认分支 head（多分支下未测）；**区域"先者胜"逐格像素未验**。

---

## 六 我未能核实的

- **前端护栏的长期有效性**（见 §五.1）：证据级 ⇒ 无 CI 守护。
- **所有前端 e2e 只在本机、只在本机 Playwright/Chromium 版本下跑过**。
- **窄屏/响应式**未测（e2e 视口固定）。
- **真实大图上的单位移动、倒树、区域多标签**未跑（大图只跑过 overview/缩放/平移/点选/区域填充）。
- **M7 的全部截图都是"我（或其自述）目视"**：T5 明确未过独立视觉评审。
- **只读模式的"无写"是行为级证明**（页面级非 GET 计数不增），**不是**JS 静态可达性证明。
