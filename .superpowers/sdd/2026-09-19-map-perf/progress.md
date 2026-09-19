# M9 台账 —— 大图性能（19441 格真图）

> 裁定：P1 权威块 / P2 先档0 / P3 最好省 height / P4 gzip 降级为可选 / P5 确定性 BlockId / P6 全部建块

## T1 ✅ 基线实测（test-only，`3e6c10e` → 合并 `362ee89`）

**实测（真服务 + 真 Chromium，两次跑，可复现）**：首屏可交互 **≈10.8s**（10806/10757，−0.5%）；`render()` 适配比例单帧 **≈5.0s**（p50 4998/5004），**scale=3 时 0.2ms** ⇒ 成本随**可见格数**走；**pan 3s 仅 1~2 帧**（帧间隔 ≈5s）；longtask 两段各 ≈5s 占满主线程 0.53s→10.8s；**0 pageerror**；全响应**无 gzip**。

**★ 纠正控制器派单**：启动期 `overview` 是 **3 次**（1 次无 target + 2 次 `revision=1`；`timeline` 初始化再次触发 target 键变化 `main#head`→`main#1`），**units 4 次**，state 3 次 ⇒ **≈3MB**。两次跑 3/3 可复现。

**★ 纪律亮点（记）**：实现者**拒绝**把 `firstFramePaintedTs ≈ 460ms` 报成"可见首帧"（它只是首个 `fill()` 后的首个 rAF）——称之为"把推断当实测（形态 5）"，改以 `firstInteractiveMs` 为口径。**自陈未隔离** 5s 的具体机制（`visibleHexes` O(19441) 扫描 vs 两遍 `addHexPath`）。

**门禁**：合并后 **843 = 170/256/45/131/154/88**，rc=0、ERROR 0。

## 下一步
档 0 止血：T2 去重请求 / T4 render 隔离+修复 / T5 updateLegend 记忆化 + P3 核实。
