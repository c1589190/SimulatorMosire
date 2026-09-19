# M9 T1 —— 大图性能基线实测（t1-report.md）

> **任务性质：test-only。** 未改任何产品代码（`webui/**`、Java 一律不动），未改 spec/plan/`progress.md`。
> 全部数字来自**真服务 + 真 Chromium（Playwright）**；「实测值」与「推断」分栏见 §5。

## 0. 一句话结论

用户「浏览器直接爆炸、进不去」是**真的**，且基线**可复现**：
真地图 **19441 hex** 下，`render()` 在初始适配比例（scale≈0.097，全图可见）**单次同步耗时 ≈ 5.0 秒**；
首屏可交互（overview+units 取回并完成绘制）**≈ 10.8 秒**；pan/zoom 每次重绘同样卡 **≈ 5.0 秒**，
3 秒拖动窗口内**只出 1~2 帧**。放大到 scale=3（可见格很少）后 `render()` 降到 **0.2ms** ⇒ 成本**随可见 hex 数**走，不是随总 hex 数。

## 1. 环境与装置

| 项 | 值 |
|---|---|
| 服务 | `java -cp <6模块 target/classes + /tmp/t9b-cp.txt> io.mosire.simos.app.ShellMain --store /tmp/m6-import-verify/test_integration --gui-port 45901 --mcp-port 45905 --approval-port 45903` |
| 数据 | `/tmp/m6-import-verify/test_integration`（M6 导入的 19441 hex 真档；**不给 `--demo`**） |
| 浏览器 | `/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome`（headless） |
| Playwright | 1.63.0（`NODE_PATH=/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules`） |
| 视口 | **1280×800**，deviceScaleFactor=1 |
| 端口 | 45901/45905/45903（**避开 5817/5818**，二者全程未被触碰） |
| 测量脚本 | `harness/measure.cjs`（两次跑各一份原始 JSON） |
| 写保护 | `simos.db` md5 全程 `2348b9365e5b107945a305d06fad8fab`（跑前=跑后，未写）；checkpoint `main/1.json` mtime 仍 `07:12`（未重写） |
| 服务端日志 | `logs/server.log`：`[ERROR]` 0、`Exception` 0、`WARN` 0 |

★ **一次只跑一个 Maven**：本任务只跑了**一次** `./mvnw -q -pl simos-app -am -DskipTests compile`（为 worktree 生成 classes），之后测量全程无 Maven 并发。

## 2. 实测值表（两次跑 + 差值）

「可交互」定义（脚本原样打印，见 `baseline.json.interactiveCriterion`）：
**`SimosMap.isReady()===true` 且 `#map-status` 文本含「已载入」**（= overview+units 已取回并完成一次绘制）；取 `firstFramePainted / readyTs / loadedTs` 三者中最晚者。

| 指标 | run1 | run2 | 差值 | 判读 |
|---|---:|---:|---:|---|
| `firstFillCallMs`（首个 `fill()` 调用） | 444.7 | 425.2 | −4.4% | |
| `firstFramePaintedTs`（fill 后首个 rAF） | 461.5 | 436.3 | −5.5% | ★ 见 §4 警告，**不等于可见首帧** |
| `readyTs`（overview 已 setData） | 432.3 | 440.0 | +1.8% | |
| `loadedTs`（+units 已取回并绘制完） | 10806.2 | 10756.5 | −0.5% | |
| **`firstInteractiveMs`** | **10806.2** | **10756.5** | **−0.5%** | ★ 可交互 ≈ **10.8s** |
| **`renderCost` 适配比例 p50** | **4998.3** | **5003.6** | **+0.1%** | ★ 单次 `render()` ≈ **5.0s** |
| `renderCost` 适配比例 max | 5057.7 | 5011.9 | −0.9% | |
| `renderCost` scale=3 p50 | 0.2 | 0.2 | 0.0% | ★ 可见格少 ⇒ **0.2ms** |
| **pan 窗口内帧数**（3s） | **2** | **2** | 0.0% | ★ 3 秒只出 **1~2 帧** |
| `pan.maxGapMs` | 5048.2 | 5012.0 | −0.7% | 帧间隔 ≈ 5s |
| `pan.windowMs` | 5048.2 | 5012.0 | −0.7% | |
| **zoom 窗口内帧数**（2s） | **4** | **4** | 0.0% | |
| `zoom.maxGapMs` | 5084.4 | 5109.1 | +0.5% | |
| `zoom.windowMs` | 5111.0 | 5135.3 | +0.5% | |
| `apiBytes`（全量） | 3,137,534 | 3,137,670 | 0.0% | |
| `staticBytes` | 156,040 | 156,040 | 0.0% | |
| `allBytes` | 3,293,574 | 3,293,710 | 0.0% | |
| **`overview` 总次数** | **3** | **3** | **0** | |
| 启动窗口（<2s）字节总量 | 3,291,638 | 3,291,638 | **逐字节相同** | |

**复跑一致性判定：PASS。** 除「审批/状态轮询次数」（受网络调度影响的 `/api/approvals`、`/api/state` 每 5s 轮询，两次相差 1~2 次）外，**所有关键指标两次差值 ≤ 0.7%**，`renderCost` 仅 0.1%，启动窗口字节总量两次**逐字节相同**。

### 2.1 首屏字节构成（启动窗口 <2s，两次逐字节相同）

| 类 | 字节 | 说明 |
|---|---:|---|
| API | 3,135,598 | **其中 `/api/map/overview` ×3 = 3,134,910 B（95.3%）** |
| 静态 | 156,040 | HTML+CSS+6 个 JS |
| **合计** | **3,291,638** | |

★ **overview 是 3 次、不是 1 次**：`/api/map/overview?branch=main` ×1 ＋ `?branch=main&revision=1` ×2。
第 1 次来自 `initHost → reloadOverview()`（无 target）；第 2 次来自 `app.pollState()` 首拉 `/api/state` 后 `notify()` → `onStateChange` 检测到 target 变化 → `scheduleTargetReload()`（带 `revision=1`）；
第 3 次来自 **`timeline` 初始化** 再次触发 target 变化（同一 `revision=1` 但键从 `main#head` 变成 `main#1`）。**每次都是完整 1,044,970 B。** 任务书说「各请求 2 次」——实测 overview **3 次**（1 无 target + 2 带 target），units 也相应 **4 次**（1+3）。

### 2.2 请求清单（完整）

| URL | 次数 run1/run2 | 响应体字节 | `Content-Encoding` |
|---|---:|---:|---|
| `/` | 1 / 1 | 6,796 | 无 |
| `/styles.css` | 1 / 1 | 18,345 | 无 |
| `/api.js` | 1 / 1 | 5,992 | 无 |
| `/app.js` | 1 / 1 | 13,645 | 无 |
| `/panels.js` | 1 / 1 | 17,573 | 无 |
| `/unitTree.js` | 1 / 1 | 10,354 | 无 |
| `/timeline.js` | 1 / 1 | 27,312 | 无 |
| `/map.js` | 1 / 1 | 56,023 | 无 |
| `/api/state` | 16 / 17 | 122 | 无 |
| `/api/approvals` | 14 / 15 | 14 | 无 |
| `/api/map/overview?branch=main` | 1 / 1 | **1,044,970** | 无 |
| `/api/map/overview?branch=main&revision=1` | 2 / 2 | **1,044,970** | 无 |
| `/api/units?branch=main` | 1 / 1 | 12 | 无 |
| `/api/units?branch=main&revision=1` | 3 / 3 | 12 | 无 |
| `/api/timeline?branch=main` | 2 / 2 | 136 | 无 |
| `/api/map/hex?q=0&r=0&branch=main&revision=1` | 1 / 1 | 292 | 无 |
| `/api/social/population?q=0&r=0&branch=main&revision=1` | 1 / 1 | 51 | 无 |

（`/api/state`、`/api/approvals` 是 `setInterval(…, 5000)` 轮询，次数随时长浮动；两次采集时长不同故差 1~2 次。）

### 2.3 `Content-Encoding` / `Content-Length`（服务端 curl 直取，绕开浏览器）

见 `logs/server-headers.txt`。**所有响应均无 `Content-Encoding`**（无 gzip/br），`Content-Length` 即实体字节数：

```
/api/map/overview?branch=main   → 200, Content-length: 1044970
/api/units?branch=main          → 200, Content-length: 12
/api/state                      → 200, Content-length: 122
/api/timeline?branch=main       → 200, Content-length: 136
/                               → 200, Content-length: 6796
/map.js                         → 200, Content-length: 56023
/styles.css                     → 200, Content-length: 18345
```

浏览器侧 `requests[*].contentEncoding` 也**全部为 `null`**，两路互相印证。

## 3. 卡在哪一步 / 是否真的卡死

**没有卡死到跑不完**——10 次阶段（goto→ready→load→pan→zoom→interactive）全部到达，`pageerror` = **0**。
但**每个阶段都被主线程长任务拖到秒级**：

- `pageerror`：**0**（两次）。
- `console error`：**2 条**（两次都是），均为 `Failed to load resource: … 404` —— 对应 `/api/social/population?q=0&r=0…` 的 404。
  ★ **404 分类**：服务端 body 是 `{"q":0,"r":0,"error":"population series not found"}`，即**该格无人口序列**（`/tmp` 导入档只在 `[1,1]` 有 demo 人口，且 `[1,1]` 同样 404），**不是端点缺陷**；`panels.js:250` 对它 `.catch(()=>null)` 折成「无序列」，属预期降级。
- `PerformanceObserver longtask`（>50ms，两次跑各 15 条）：**首屏三个**尤其致命——

  | run | longtask start (ms) | 时长 (ms) | 结束 (ms) |
  |---|---:|---:|---:|
  | run1 | 19.4 | 75 | 94.4 |
  | run1 | 552.7 | **5026** | 5578.7 |
  | run1 | 5578.7 | **5227** | 10805.7 |
  | run2 | 19.0 | 68 | 87.0 |
  | run2 | 529.9 | **5001** | 5530.9 |
  | run2 | 5642.3 | **5112** | 10754.3 |

  ⇒ 导航后约 **0.53s** 开始，主线程被**两段各 ≈5s** 的长任务连续占满到 ≈**10.8s**（正好等于 `firstInteractiveMs`）。
  这与 `renderCost` 的 5.0s 完全对上：**每个 target 触发的 `reloadOverview → active.render()` 各占一段**。
  用户「进不去」的物理原因即此：**页面在 10.8 秒内无法响应任何输入**。

## 4. 「可交互」判据本身 + 一个必须点破的坑

- **判据**：`SimosMap.isReady()===true && #map-status 含「已载入」`，取三个探针最晚者。
- **功能性旁证**：等页面稳定后点画布中心（`hexAtScreen` 命中 `{kind:"hex",q:0,r:0}`），左栏详情从空变为
  `q0r0terrainplains（平原）height0.375regionstest_nation…`、`left-status = q=0, r=0 · main@1` ⇒ **点选真的工作**（两次相同）。
- ★★ **`firstFramePaintedTs ≈ 460ms` 不可读作「可见首帧」**：它只是**首个 `fill()` 调用之后**的首个 rAF 时刻。
  该 `fill()` 在 `render()` 的**早段**（地形层第一个色组），但 `render()` 本身同步跑 ≈5s，且随后第二个 target 又触发一次 ≈5s 重绘——
  真正把首帧交给合成器要到 ≈5.5s。**若只报 460ms，就是把推断当实测**（纪律形态 5）。本报告一律以 `firstInteractiveMs` 为口径。

## 5. 实测 vs 推断

| # | 命题 | 实测？ | 依据 |
|---|---|---|---|
| 1 | overview 响应体 1,044,970 B | **实测** | curl `Content-length` + 浏览器 `response.body()` 两次一致 |
| 2 | 无 gzip（所有响应无 `Content-Encoding`） | **实测** | `logs/server-headers.txt` + 浏览器 headers |
| 3 | 启动期 overview 共 **3** 次（1 无 target + 2 带 target） | **实测** | 两次跑请求清单一致（3/3），时间线见 `baseline.json` |
| 4 | `render()` 适配比例单次 ≈ **5.0s** | **实测** | 连续 6 次直接调用 `SimosMap.render()`，p50 4998/5004ms |
| 5 | `render()` 在 scale=3 时 ≈ **0.2ms** | **实测** | 同上 6 次，p50 0.2ms |
| 6 | 首屏可交互 ≈ **10.8s** | **实测** | `firstInteractiveMs` 10806/10757，且与两段 ≈5s 长任务吻合 |
| 7 | pan 3 秒只出 **1~2 帧** | **实测** | 窗口内 rAF 计数 2/2，最大间隔 ≈5s |
| 8 | 页面响应 input 前需等 ~10.8s | **实测** | longtask 占满 + 点选功能性测试（稳定后才生效） |
| 9 | 「成本随**可见 hex 数**走」 | **实测**（两档：适配=全图 5s vs scale3=0.2ms） | 未在中间比例做曲线 |
| 10 | `render()` 慢的**具体机制**（`visibleHexes()` O(19441) 扫描 vs 逐格 `addHexPath` 两遍路径） | **推断** | 源码：`map.js:415-428` 线性扫全表；`map.js:519-561` 对 `visible` 做两遍 `addHexPath`。两档实测证明与可见数相关，但**未做单因素隔离** |
| 11 | 「每读请求都跑 `Replay` 重读 1.1MB checkpoint」 | **未核实** | 本任务只测浏览器侧；服务端 `overview` 自身耗时（curl 78ms 级）与 Replay 的因果**未隔离** |
| 12 | `firstFramePaintedTs≈460ms` = 可见首帧 | **明确否定**（推断的陷阱） | 见 §4 |

## 6. 我未能核实的

1. **未在 5817/5818 实例上测**——按纪律避开；本基线只对 45901 自起实例成立。
2. **未做 render() 成本的单因素隔离**（`visibleHexes` 扫描 vs 绘制调用次数 vs `terrainColor` 查表），只说"与可见格数相关"。
3. **未测中间缩放档**（只测了适配比例与 scale=3 两档），无曲线。
4. **未测服务端各阶段耗时分解**（overview 的 `Replay` vs JSON 序列化 vs 传输）——本任务范围是浏览器侧。
5. **未在 >2 个分支/有写操作的 revision 上测**（本档只有 `main@1`，无单位、无路线）。
6. **`/api/approvals` 每 5s 轮询** 是否是首屏必要请求未核（服务端 14 B，影响可忽略）。
7. **未测真实有头模式**（headless 与 headed 的合成器行为可能不同）；未测 `deviceScaleFactor>1`（HiDPI）。
8. **`firstInteractiveMs` 依赖 `#map-status` 文案「已载入」**——若将来改文案，探针会静默失准（**已知脆点**，非本次问题）。
9. **两次跑的 `/api/state`、`/api/approvals` 次数不同**（16/17、14/15）——轮询与采集时长耦合，**不构成"不可复现"**，但也**没有**做到逐次相同。

## 7. 证据索引

| 文件 | 内容 |
|---|---|
| `baseline.json` | 机器可读汇总（metrics + 两次请求表 + 时间线 + longtask + 分类） |
| `run1/baseline-run1.json` / `run2/baseline-run2.json` | 两次跑的原始产物 |
| `logs/run1.log` / `logs/run2.log` | 两次跑的完整 stdout |
| `logs/server.log` | 服务端日志（0 ERROR / 0 Exception / 0 WARN） |
| `logs/server-headers.txt` | curl 直取的全部响应头（证明无 `Content-Encoding`） |
| `harness/measure.cjs` | 测量脚本（可复跑） |
| `cp.txt` | classpath 快照 |
| `logs/server.pid` | 自起服务 PID（已收） |

## 8. 对任务书的纠正（以源码/实测为准）

1. **「启动时 overview/units/state 各请求 2 次」**：实测 **overview 3 次**（`/api/map/overview` ×1 无 target + ×2 带 `revision=1`）、**units 4 次**（1+3）、`/api/state` 启动窗口 3 次（之后每 5s 一次）。两次跑都是 3/3，可复现。
2. **「每帧 `visibleHexes` O(19441) 扫描 + 两遍路径」**：源码位置为 `map.js:415-428`（扫描）与 `map.js:519-561`（对 `visible` 先分组 `fill` 再统一 `stroke`）——与描述一致；但本任务**未做单因素隔离**（见 §6.2）。
3. **端口建议 45901**：已采用；5817/5818 全程未被触碰，自起服务已收干净（`kill` 后端口全释放，无残留 java）。
