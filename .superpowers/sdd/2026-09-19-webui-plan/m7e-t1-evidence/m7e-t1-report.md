# M7e T1 报告 —— 交互表（右键空白取消选中 / 左键取消移动）+ 折线对比度修复

- 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/m7et1`，分支 `m7e/t1`
- 基线：`870ca6c`（M7d 关账）
- 纯前端改动，**零 Java 改动**；全量门禁与 M7b/M7c 同值 **841**（见 §三）。

---

## 一、改了什么

| 文件 | 改动 |
|---|---|
| `simos-app/src/main/resources/webui/map.js` | ① 路线三层描边（对比度修复）；② 右键空白取消选中；③ 左键已选中单位/其所在格发 `unit.CancelRoute`；④ `debug()` 增 `outlineColor`；⑤ 顶部"只读"注释更正为"写路径唯一" |
| `simos-app/src/main/resources/webui/index.html` | 单位编辑器提示文案同步新交互（一行） |

### 1. 交互表（逐条落点）

| 动作 | 目标 | 实现（`map.js`） |
|---|---|---|
| 右键 | 空白 / 图外 / 无格（`pick.inMap === false`） | `handleContextMenu` 首段：`app.setSelection(null)`，返回 `true`（消费，抑制原生菜单）；**不发任何写、不碰路线** |
| 右键 | 有格（`inMap === true`） | 现状保留：仅 `unit` 模式 + 已选中单位 ⇒ `submitPathRoute`（A* → `unit.PlanRoute` 替换）；否则返回 `false` |
| 左键 | 已选中单位标记 | `workbenchSelect` 的 `pick.kind === "unit"` 分支：`selId === pick.id` ⇒ `cancelRouteFor(id)`（`unit.CancelRoute {id}` 经 `app.writeCommand`） |
| 左键 | 已选中单位**当前所在格** | 新增分支：`mode === "unit" && selId && positionOf(selId) === pick` ⇒ `cancelRouteFor(selId)` |
| 左键 | 未选中单位标记 | 选中它（现状）；`unit` 模式顺带 `resetRoute()` |
| 左键 | 其它格 | 现状：选中该格 + 左栏看信息；路线模式下仍 `appendRoutePoint` |

新增函数 `cancelRouteFor(id)`（紧邻 `submitPathRoute`）：`host.editBusy` 守卫 → `app.writeCommand("unit.CancelRoute", { id })` → 状态文案「已取消 <id> 的移动（路线已清）」。

### 2. ★ 冲突默认裁定（控制器给；**可推翻**）

> **「一下选中、两下取消」**：左键点某单位——
> - 该单位**当前未选中** ⇒ 第一下 = **选中**（不发写）；
> - 该单位**已选中** ⇒ 再点它（标记或它所在格）= **取消移动**（发 `unit.CancelRoute`）。
> 不用修饰键；三次点击会「选中 → 取消 → 选中…」交替。

**本实现的两处限定（请复核，均为可推翻决定）：**
1. **取消移动只在 `unit` 模式生效**（`mode === "unit"`）。理由：路线只在单位模式里下，且 `view`/`region` 是只读语义，不应从只读模式发出写请求（R8 精神）。
2. **右键空白取消选中在所有模式生效**（只清 UI 选中态，无写、无副作用）。理由：这是纯前端可见态变更，与模式无关，且用户原话未限定模式。若只想要单位模式，请裁定。

### 3. 折线对比度修复（修前 → 修后）

| 层 | 修前 | 修后 |
|---|---|---|
| 外描边 | （无） | `rgba(12, 8, 2, 0.95)`，宽 **12** |
| 整条底 | `rgba(255, 214, 130, 0.35)`（35% 透明黄），宽 5 | `#f59e0b`（鲜琥珀橙，不透明），宽 **7** |
| 未走完段 | `#ffd27a`，宽 3 | `#ffffff`（纯白），宽 **4** |

`drawRoutes` 依次画：外描边 → 实色底 → 亮段。★ 一次中途强化：首版 `#b45309` + `rgba(26,18,6,.92)` + `#fff7cc`（外描边 9/底 5/亮 3）经视觉复核被判"易被忽略"，遂加粗并提高色相分离度，**对最终字节重跑了全部门禁与全部变异轮**（§三/§四数字均为最终字节）。

**像素证据（`pixel-sample.json`，canvas 实时 `getImageData`，格 (1,2) 中心扫描行 ±8 device px）**：底色 = 沙漠 `[231,200,110]`（后端 `desert.color = "#E7C86E"`）；路线剖面 = `[231,200,110] → [23,18,8] → [245,158,11] → [255,255,255] → [245,158,11] → [23,18,8] → [231,200,110]`。
- 最大通道差 **208**（外描边 vs 底色）；最暗 49（近黑）、最亮 765（纯白）。
- 对照阈值：断言 `diff > 80` ⇒ PASS。

---

## 二、e2e 实测值（`logs/clean.log`，真 `ShellMain --demo`，全新 store，Playwright/Chromium）

| 步 | 断言 | 实测 |
|---|---|---|
| setup | 选 u-1 + 右键 (1,3) 下路线 | `rev 1→2`，`route (1,1)->(1,2)->(1,3)` |
| **a** | 右键图外点 `(4,4)`（自证 `pick={q:-1,r:0,inMap:false}`） | `selection 单位u-1 → null`；`nonGet 1→1`（**不增**）；`movement` 仍在（`IN_TRANSIT`、route 3 点、`remainingMillis 1500`）；`routeCount 1` |
| **b** | 左键已选中 u-1 | 第一下（重新选中）`nonGet 1→1` 不发写；第二下 ⇒ `movement null`、`rev 2→3`、时间轴节点 `2→3`、`routeCount 0`（折线消失）、仍选中 u-1、文案 `"已取消 u-1 的移动（路线已清）"` |
| **c** | 左键未选中 u-1 | 先左键 (1,2) 选到 hex，再点 (1,1) ⇒ `selection={kind:unit,id:u-1}`，`nonGet 2→2`（**不发写**），`movement` 仍 null |
| **d** | 右键 (1,3) 回归 | `path (1,1)->(1,2)->(1,3)`、`rev 3→4`、`nodes 3→4`、`route 3 点`、折线 3 点 |
| **e** | R8 allowlist | `NON_GET_LIST=["/api/command","/api/command","/api/command"]` ⊆ `{/api/command,/api/advance,/api/fork}`，violations = `[]` |
| **f** | pageerror | `[]` |
| **g** | 像素对照 | 见 §一.3（diff 208） |

- 全程 **31 个 PASS，0 FAIL**（`clean.log`）；变异还原后再跑一轮仍 31 PASS（`clean-after-mutants.log`）。
- 截图：`screenshots/crop-route-contrast.png`（折线在沙漠上清晰：深色包边 + 纯白核心）、`screenshots/crop-leftclick-cancel.png`（同一取景，折线完全消失）；原图 `mut-runs/clean/screenshot-*.png`。

---

## 三、门禁

| 项 | 结果 |
|---|---|
| `./mvnw -q spotless:apply` | rc=0（无改动） |
| `-Dtest=WebuiAssetsTest`（`-pl simos-app -am`） | rc=0，`tests=8 errors=0 failures=0` |
| `./mvnw clean verify`（最终字节） | **rc=0**，`Tests run 841 = 170/255/45/131/154/86`（**delta 0**），`[ERROR]` 0 行，`BugInstance size is 0` ×6 |
| 基线 | 841 = 170/255/45/131/154/86（与 M7b/M7c 一致） |

日志：`logs/full-verify.log`、`logs/targeted.log`、`logs/spotless-apply.log`。纯前端 ⇒ 期望 delta 0，实测 delta 0。

---

## 四、变异（每条 ≥1 轮，九道门禁；装置 `mutants/m7e-mut-round.sh`）

装置形态：资源类（源 + classpath 两份推送、逐字节还原、日志自指、红点落被保护断言、先断言聚合 md5 非空）。

| m | 护栏 | 变异 | 预期红步 | 实测红步（`logs/mN.log`） | 结果 |
|---|---|---|---|---|---|
| **m1** | 右键空白**不清路线** | 空白分支顺带 `cancelRouteFor` | `a-movement-kept` | `a-no-write`,`a-movement-kept`,`a-polyline-kept` | ✅ 杀死 |
| **m2** | 左键已选中单位**发 CancelRoute** | 删掉已选中分支的取消调用（只重选） | `b-movement-cleared` | `b-movement-cleared`,`b-head-advanced`,`b-node-plus-1`,`b-polyline-gone`,`b-status-refreshed`,`c-movement-still-null` | ✅ 杀死 |
| **m3** | 左键未选中单位**只选中不写** | 未选中也发 `CancelRoute` | `c-no-write` | `setup-route-planned`,`b-first-click-no-write`,`c-no-write` | ✅ 杀死 |

- 三轮 `DEVICE_RC=0`、rc=1（e2e 真红），装置补记已写入各自日志（含 `orig_md5/pushed_src_md5/pushed_classes_md5/restored_*`）；最终源与 classpath 资源均还原为 `57ffb8d1…`（装置末条断言 + 独立复核）。
- m3 因"未选中即发写"波及 setup（首次选中即写），属预期的连锁失败；`c-no-write` 仍被精确命中。

---

## 五、我未能核实的

1. **"左键点已选中单位所在格"这条路径未被 e2e 直接触发**：demo 里 u-1 恰在格中心，`pickAt` 的命中半径使 `pick.kind` 恒为 `"unit"`，走不到 `hex` 分支。代码已实现（`positionOf(selId) === pick`），但**只在直读源码层面成立，未有真跑证据**。
2. **同格多单位**：若两单位叠在同一格，`positionOf(selId)` 只匹配"被选中那个"；点其它单位标记走的是另一分支。无样本，未测。
3. **非 `unit` 模式下右键空白清选中**是本实现的裁定（§一.2）；用户只给了"右键空白取消选中"，未明确模式范围 —— **若用户本意是仅单位模式，此为偏离**。
4. **右键空白会 `preventDefault` 抑制原生浏览器菜单**：用户未表态；本实现选择了消费（否则"取消选中"与弹菜单同时发生）。
5. **`host.routePath`（手动路线模式的暂存点）不被右键空白清除**：只清 `selection`；这是"不清路线"的合理延伸，但用户原话未覆盖手动暂存点。
6. 前端护栏仍**不进 Maven 门禁**（本项目无 JS 测试器的系统性开口项）；本轮变异靠 e2e 装置观察，非 CI 常驻。

---

## 六、证据索引

```
m7e-t1-evidence/
├── m7e-t1-report.md                     ← 本文件
├── e2e/run-e2e.sh, e2e/e2e.cjs          ← 装置（真 ShellMain --demo + Playwright）
├── logs/
│   ├── full-verify.log                  ← 最终字节全量门禁（841）
│   ├── targeted.log, spotless-apply.log
│   ├── clean.log, clean-after-mutants.log
│   ├── m1.log, m2.log, m3.log           ← 各含装置自指 md5 段
│   └── *.server.log
├── mut-runs/
│   ├── clean/                           ← e2e JSON（a~g）+ 截图
│   ├── clean-after-mutants/
│   └── m1/ m2/ m3/
├── mutants/
│   ├── m7e-mut-round.sh                 ← 九道门禁装置
│   ├── orig/map.js + orig/classes-resource/map.js
│   └── m1/map.js, m2/map.js, m3/map.js  ← de1253b2… / 5c90a923… / a85fb089…
└── screenshots/crop-route-contrast.png, crop-leftclick-cancel.png
```

- 最终源/资源 md5：`57ffb8d104514f0883acf3af84720dad`（源 == classpath 副本）。
- 变异体 md5：m1 `de1253b2…`、m2 `5c90a923…`、m3 `a85fb089…`（均 ≠ 原件，装置已断言）。

---

## 七、与本单/源码的出入（按"以源码为准"）

- 本单 §1 表把"左键点单位当前所在格"列为取消移动；源码中 `pickAt` 是"单位优先于格"的命中，故**点单位格中心必得 `kind:"unit"`**，`hex` 分支在 demo 里不可达（见 §五.1）。两条路径都已实现，但真跑只覆盖了 `unit` 分支。
- 本单 §2 的默认裁定与实现一致（"一下选中、两下取消"）。
- 本单把折线 `baseColor` 记为 `rgba(255,214,130,0.35)` —— 与改前源码一致，已改。
