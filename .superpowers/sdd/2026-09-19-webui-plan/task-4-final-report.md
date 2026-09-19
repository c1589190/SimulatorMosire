# M7b 关账报告 —— 用户实测反馈的修复包

> **结论**：**M7b 4/4 完成**。用户报的**四条**全部修好，且**每条都有实测值**；R1/R2/R8 与既有不变量**全部仍成立**；
> 主树全量门禁绿。代码终态 = 合并 `6561362`（`feat/adr1-core-scope`）。
> 台账 `.superpowers/sdd/2026-09-19-webui-plan/progress.md`（「# M7b」一节，裁定 U1~U3 / S1~S6）。

---

## 一 判据：用户四条原话 → 实测值

| # | 用户原话 | 修法 | **实测值** |
|---|---|---|---|
| **①** | 「**没有一个可以拖动选择下一个节点对应时间的拖动按钮**」 | 可见 knob（真元素、`setPointerCapture`、吸附节点） | `a-knob-exists-visible`：`knobHidden=false, display=block`；★ **`a-knob-on-cursor`：`delta=0`**（knob 中心 == 游标节点中心）；★ **`b-drag-knob-offrow`：`offRowY=874`（行中心 804，纵向偏 70px）仍能改 `rev`**（证明 capture 生效）；`b-drag-readonly`：`head 3->3 rows 3->3` |
| **②** | 「创建分岔之后，**分岔的新节点单列了一行且回到了最左边，没有表示出它分岔自哪里**」 | 列坐标布局（`x=(列−1)×列宽`，分支的列由 `parent` 递归决定）＋ 垂直连线 | ★ **`d-fork-aligned`：`child.x=323 parent.x=323 dx=0`**；`d-fork-link-visible`：`.tl-fork-link` 在场；★ **`e-main-first`：`firstLine=main`**（主分支不再被 `b2` 顶下去）；`c-column-spacing`：`COL_WIDTH=110 d1=110 d2=110` |
| **③** | 「**我不知道这个单位移动逻辑是什么**」 | ① 只读 API 把 `movement` 从**布尔**改**对象**（9 字段）；② 左栏把规则**摊开** | `/api/unit/u-1` 的 `movement` = `{route.path 3 点, departedAt.tick 5, speedAtDeparture 2, mobilityPerMilleAtDeparture 500, status IN_TRANSIT, currentHex (1,1), nextHex (1,2), remainingMillis 1500}`；左栏显示 **`本 tick 预算 2000` / `路线每格成本 1500` / `路线总成本 3000` / `remainingMillis 1500` / `预计到达 tick 7` / `出发 tick 5` / `出发速度 2000` / `出发机动‰ 500`** |
| **④** | 「按右键到目标地块，**自动创建表示移动路线的线条**，这个能做出来吗？」 | 服务端 A\*（`PathFinder`）→ 新只读端点 → 前端 `unit.PlanRoute` → T2 折线自绘 | ★ `b-path-3-points`：`PATH_B=(1,1)->(1,2)->(1,3)`（逐格相邻、含首尾）；`c-head-advanced` + `c-timeline-node-plus-1` + **`c-polyline-3-points`**；★ `d-route-replaced-not-appended`（右键 (1,2) ⇒ 路线**替换**为 2 点）；`e-unreachable`：`reachable:false` + **无写**；`g-nonget-list`：`["/api/command","/api/command"]` ⊆ allowlist |

**★ 你问"这个能做出来吗"——能做，而且做出来了。** 关键是我们**早就有 A\***（`PathFinder.findPath`，有单测）却**零接线**；M7b 把它接上了（`PathFinder` 从此有了第一个生产调用者）。

---

## 二 不变量点验（M7b 没把既有护栏弄坏）

| # | 不变量 | 载体 | 状态 |
|---|---|---|---|
| **R1** | 拖动/预览**不写盘** | T1 `b-drag-readonly`/`e-readonly`；T3 图外右键无写 | ✅ |
| **R2** | **仅末端**可写/分岔 | T1 `e-mid-disabled`/`f-tip-enabled`；`g-fork` | ✅ |
| **R3** | 时间轴节点与库一致 | T1 `c-timeline`/`d-nodes`（21 步含回归） | ✅ |
| **R6** | 前端资产纪律（无 CDN/绝对 URL） | T1/T2/T3 门禁（`WebuiAssetsTest` + 判定器自证） | ✅ |
| **R7** | 倒树父子/深度 | T2 回归（未碰 `unitTree.js`） | ✅ |
| **R8** | **写路径 allowlist** | ★ T3 `g-nonget-list` = `["/api/command","/api/command"]`（**打印了完整清单**） | ✅ |
| 既有 e2e | T3 的 13 个 STEP 在 T1/T2 后仍全 PASS（`g-fork` 的 `.timeline-line==2` 未被连线打破） | ✅ |

**变异轮统计**：T1(2) + T2(3) + T3(2) = **7 轮 0 存活**；每轮九道门禁（含 `COMPILATION ERROR=0`、逐字节还原、**日志自指**、红点落被保护断言）。

---

## 三 门禁

`./mvnw clean verify`（主树，合并 T3 后）：**rc=0**、**841 条 = 170/255/45/131/154/86**、7/7 模块、`BugInstance size is 0` **×6**、`[ERROR]` **0**。

**逐任务 delta**：T1 **0**（纯前端）；T2 **+3**（`GuiApiTest` +2 / `SimosToolsTest` +1）；T3 **+5**（`GuiApiTest` 18→23）。⇒ 合计 **833 → 841**。

---

## 四 交付物

| 层 | 交付 |
|---|---|
| **Core** | **零改动**（M7b 一行没碰 `simos-core`） |
| **app 只读面** | 新增 `GET /api/map/path`（服务端 A\*，起点由 `effectivePosition` 权威取）；`movement` 由布尔改对象（**四处同形**：`/api/unit/{id}`、`/api/units`、`simos.unit.get`、`simos.unit.list`） |
| **前端** | 时间轴：可见 knob + 列坐标 + 分岔连线；左栏：移动规则读数（MP/成本/ETA）；Canvas：路线折线（整条淡 + 剩余亮）；**右键移动**（`contextmenu` → A\* → `PlanRoute`） |

---

## 五 带裁定的遗留 / 开口项

1. ★★ **`PlaceAt`（瞬时置位）与右键移动目前共存**——**这是 M7b 关账时尚未收敛的一致性问题**：
   - 你的裁定 **U2** 说「`PlaceAt` 不作为正常编辑手段」（原话「0tick speed 还不超模？」）；
   - 但 **T7 的既有权行为**是"单位模式下**左键**点目标格 ⇒ 发 `unit.PlaceAt`（瞬移）"；
   - T3 的 MUST NOT 明确"不新增命令、不做 M8"，故**未动左键**；
   - ⇒ **当前状态：右键 = 下路线（HoI4）；左键 = 瞬移**。**建议**：把左键改成"只选中/查看"，**移动只由右键发起**，瞬移归控制台（U2）。**待你点头**。
2. **`Route` 至少 2 格**：右键落在**单位自身所在格** ⇒ `path` 长度 1 ⇒ 不可表示为路线 ⇒ 前端**不发写**、提示"已在目标格"（T3 实现期决定，已记）。
3. **S3 的 URL 形状被取代**：S3 写 `?from=&to=&unit=`，T3 实现为 `?unit=&q=&r=`——★ **因为起点必须由服务端取**（前端传 `from` 就等于让前端决定起点 = 第二份真相）。**接受**。
4. **折线视觉对比度**：截图中 3 点折线画在黄色沙漠上、**目视对比度低**（`ROUTE_BASE_COLOR` 是半透明黄）；功能断言可靠，视觉待调（配色遗留）。
5. **前端护栏仍不进 CI**（M7 系统性开口项延续）：M7b 的三轮前端变异只由**证据级**装置证（`AppWritePathGuardTest` 只扫 Java）。
6. 未测：折线**像素级**证明；`ARRIVED`/`NEED_REPLAN` 下的右键替换；`区域查看`模式右键；单位**无有效位置**分支；触摸/笔；**大图 A\* 性能与响应体大小**；嵌套分岔（fork 自 fork）的列递归；横向滚动/resize。

## 六 我未能核实的

- **右click 在真实大图（19441 格）上的表现**未测（demo 只有 3 格）——包括 A\* 规模、往返延迟、折线在 1MB overview 之上是否可接受。
- **折线的颜色/粗细是否"看得清"**：只有截图目视（且 3 点那条对比度低），**无像素采样**。
- **`PlaceAt` 与 `PlanRoute` 并存**是否会在真实使用中造成困惑——**需你上手判断**（见 §五.1）。
