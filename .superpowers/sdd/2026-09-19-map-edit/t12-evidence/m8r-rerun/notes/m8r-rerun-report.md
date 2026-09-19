# M8 T12 pre-closure「还债」报告：M8-R 的 m1~m8 在**当前字节**上重跑

> 本报告全部结论都来自**实测**；凡属推导/未核实的，都在句中或第 ④ 节显式标出。
> 引用的日志原文一律带 `文件:行号`。工作树 `/home/dev/SimulatorMosire/.claude/worktrees/m8t5`，**未提交、未推送**。

---

## 0. 一句话结论

**0 被杀 / 8 存活**——8 个变异体在当前**可跑**的检查器（前端门禁 82 例 + t9 演示 e2e 57 例）里全部**实测两侧全绿**；
其中 **m6 是唯一「行为确实被改变、但判据弱于行为」**的一个（详见 ③.6 与 ④）。
这不等于「护栏失效」：护栏代码在当前字节上仍在（第 ② 节逐个给出 `map.js` 行号），而是**守护它们的判据装置在本机跑不了**。

---

## 1. 被测字节、检查器、以及「跑不了的判据」为什么跑不了

**被测字节**：`simos-app/src/main/resources/webui/map.js`
`md5 = 014ffd3aac68dbf282d91e49365d4b0d`（三处实测一致：源 / `simos-app/target/classes/webui/map.js` / 本单快照
`t12-evidence/m8r-rerun/mutants/orig/map.js`，与派单里给的值相同）。

**本机可跑的检查器只有两个**（其余都跑不了，见下）：
| 检查器 | 装置 | 断言数 | 红点命名 |
|---|---|---|---|
| 门禁 gate | `node simos-app/src/test/js/run-gate.cjs` | 82 | `not ok <n> - <用例名>` |
| 演示 e2e | `.superpowers/sdd/2026-09-19-map-edit/t9-evidence/e2e/run-e2e.sh`（`--demo` 空库） | 57 | `STEP <名>: FAIL` |

**轮 0（干净世界基线，跑任何变异体之前）**：`mutants/logs/clean-e2e.log:64` `E2E RESULT: ALL PASS`；
gate `mutants/logs/clean-gate.log`（`# tests 82` / `# fail 0`）；`e2e_rc=0`、`pass=57`、`fail=0`；
e2e 实际加载字节 `agg_md5=4294de6d441bc28a7230a616bef77d21`（`mutants/logs/clean-e2e.log:68`）= 本单**独立复算**的同一值。
★ 与 t9 自己的历史干净日志 `t9-evidence/logs/clean-e2e-final.log` 的聚合 md5 相同 ⇒ 字节没漂。

**跑不了的判据装置（因此不能当判据用）——理由是装置级的，不是被测物的**：
- `r-evidence/e2e/run-e2e.sh:7` `ROOT=/home/cna/SimulatorMosire/...`、`:55` `NODE_PATH=/home/cna/.npm/...` ⇒ **另一台机器**的路径；
- `r-evidence/e2e/run-e2e.sh:33,39` 必须有**真档** `$STORE_SRC/simos.db`（19441 格）——本机实测**不存在**
  （`find / -xdev \( -name 'simos.db' -o -name '*.db' -size +200k \)` 只找到 demo 小库 `/tmp/m8t9-e2e-store-*`、
  `/tmp/m8t11-e2e-store-*`；`find / -xdev -type d -name test_integration` **零命中**；
  `/tmp` 下**没有**旧装置默认要的 `/tmp/m8t1-e2e-store`）；
- `r-evidence/e2e/e2e.cjs:215` `pre-real-archive` 断言 `hexCount=19441 && test_nation && test_annex_target`
  ⇒ 该装置在本机**干净世界也必红**，做判据无意义。`s-evidence/e2e/e2e.cjs:343`、`t10-evidence/e2e/e2e.cjs:113` 同型。
- 故 M8-R/M8-S/T10 的 e2e 一字未改（**不许改判据**），本单只用可跑的两个检查器。

---

## 2. ① 旧锚点 → 新锚点对照

纪律：旧装置的 `r-evidence/mutants/mut-run.sh` **不能原样复用**（`ROOT`/`NODE_PATH` 指另一台机器，且上面还硬绑真档），
只作锚点参考。下表「旧锚点」原文出自 `r-evidence/mutants/mut-run.sh`，「新锚点」原文出自当前 `map.js`；
**为什么必须换**逐条给出。变异体本体见 `t12-evidence/m8r-rerun/mut-run.sh`（每轮 `anchor_line=` 由脚本**现场打印**，不是手抄）。

### m1 —— 守护：套索建区**重叠允许**（不许自加「相交就拒绝」）
- 旧锚点（`r-evidence/mutants/mut-run.sh:49-51`）：
```
    host.regionEditBusy = true;
    setRegionEditStatus("套索 " + hexes.length + " 格 ⇒ 提交 map.CreateRegion " + id + " …", "muted");
    var result = await app.writeCommand("map.CreateRegion", { regionId: id, name: name, hexes: hexes });
```
- **为什么必须换**：这段文字在当前字节上**已不存在**（M8-S 把提交路径挪进了 `submitCreateRegionNow`，状态文案也改了）。
- 新锚点（`map.js:3292-3295`，实测唯一）：
```
    if (!hexes || !hexes.length) {
      setRegionEditStatus("套索为空或不闭合（至少 3 个格），未创建。", "warn");
      return null;
    }
```
  变异体：紧随其后插入「相交就拒绝」探测（`api.mapHex(hexes[0].q, hexes[0].r, app.target())`，语义与旧本体逐字相同，
  只是锚点挪到空集检查之后，免得动 `regionEditBusy` 状态机）。
- 守护行为仍在的证据：`map.js:3285-3287` 注释「★ M8-R 判据 1 … **重叠不报错**」+ `submitCreateRegionNow`
  成功文案「（… 格，**重叠允许**）」（`map.js:3176`）。

### m2 —— 守护：右键**按模式分派**（region-edit 右键=套索，绝不落 `unit.PlanRoute`）
- 旧锚点 1（`r-evidence/mutants/mut-run.sh:70-74`）与锚点 2（`:77`）：
```
    var contextMode = app.getState().mode;
    if (contextMode === "region-edit" || contextMode === "map-edit") {
      return true;
    }
……
    if (app.getState().mode !== "unit") {
```
- **为什么必须换**：★ 这两处**在结构上没变**——当前 `map.js:3794-3797` 与 `:3803` 与旧锚点**逐字节相同**（实测 `count==1`）。
  仍需重跑的理由不是锚点失效，而是**文件被改过 ⇒ 旧证据对应旧字节**（M8-S 同文件改动过）。所以本单**照旧锚点原样复用**，只把
  「早退删除 + 守卫放宽」两处改动写进新装置。
- 新锚点（`map.js:3794-3797`、`:3803`）：同上原文。变异体：删早退 + 把 `!== "unit"` 放宽为 `!== "unit" && !== "region-edit"`。

### m3 —— 守护：**小点只画 focus 区域**（取消选中 ⇒ 0 点）
- 旧锚点（`r-evidence/mutants/mut-run.sh:88-90`）：
```
    function clearFocusHexes() {
      setFocusHexes([], null);
    }
```
- **为什么必须换**：同上——当前 `map.js:1326-1328` 与旧锚点**逐字节相同**，照旧复用即可（文件被 M8-S 改过 ⇒ 仍需重跑）。
- 新锚点（`map.js:1326-1328`）：同上原文。变异体：函数体改成空实现（只留一行注释）。
- 守护行为仍在的证据：`map.js:855` 注释「焦点区域边界小点（M8-R）：**只对正在编辑的区域画**」+ `:857` 的 `!Object.keys(focusKeys).length` 早退。

### m4 —— 守护：**合并 = 并集**（临时选区 ∪ 焦点区域）
- 旧锚点（`r-evidence/mutants/mut-run.sh:103-106`）：
```
    var base = await fetchRegionCached(id);
    var hexes = unionHexes(base.hexes, regionDraftList());
    if (!hexes.length) {
      setRegionEditStatus("并集为空，未发命令。", "warn");
```
- **为什么必须换**：★ `if (!hexes.length)` 之后多了一行 `return null;`、状态文案仍是那句（当前 `map.js:3378-3382`），
  旧锚点**逐字节不匹配**（少了 `return null;` 就跨不过块尾）⇒ 取前两行做新锚点。
- 新锚点（`map.js:3378-3379`，实测唯一）：
```
    var base = await fetchRegionCached(id);
    var hexes = unionHexes(base.hexes, regionDraftList());
```
  变异体：`unionHexes(base.hexes, regionDraftList())` → `regionDraftList()`（=「只取临时选区」，与旧本体 `:107-108` 逐字相同）。
- ★ 台账措辞与旧本体不一致，如实记：`progress.md:92` 把 m4 写作「合并误用交集」，而旧装置本体是「只取临时选区」。
  **本单以旧装置本体为准**（判据是并集，变异是丢弃已选区域）。
- 守护行为仍在的证据：`map.js:3368` 注释「★ M8-R 判据 4：合并 = 临时选区 ∪ 焦点区域」。

### m5 —— 守护：**剔除 = 差集**（焦点区域 − 临时选区）
- 旧锚点（`r-evidence/mutants/mut-run.sh:120-123`）：
```
    var base = await fetchRegionCached(id);
    var hexes = differenceHexes(base.hexes, regionDraftList());
    if (!hexes.length) {
      setRegionEditStatus("差集为空（会清空 " + id + "，服务端拒绝空 hexes），未发命令。", "warn");
```
- **为什么必须换**：同 m4（当前 `map.js:3412-3416`，块尾多 `return null;`）⇒ 取前两行做新锚点。
- 新锚点（`map.js:3412-3413`，实测唯一）：`var base = ...` + `var hexes = differenceHexes(base.hexes, regionDraftList());`
  变异体：`differenceHexes` → `unionHexes`（误用并集，与旧本体 `:125` 逐字相同）。
- 守护行为仍在的证据：`map.js:3402-3416` 注释「★ M8-R 判据 5：剔除 = 焦点区域 − 临时选区」。

### m6 —— 守护：**地形编辑左键 = 平移地图、零写**
- 旧锚点（`r-evidence/mutants/mut-run.sh:137-140`）：
```
      if (event.button !== 0) {
        return;
      }
      // ★ §七：左键在所有编辑模式统一为"平移地图"
```
- **为什么必须换**：★ 注释行**被加长了**（当前 `map.js:1500` 是「…"平移地图"（永不误改）；唯一例外是抓住编辑手柄——」），
  旧锚点的第三行**不是这一行的前缀**（整行匹配失败）⇒ 必须取**整行**。
- 新锚点（`map.js:1497-1500`，实测唯一）：
```
      if (event.button !== 0) {
        return;
      }
      // ★ §七：左键在所有编辑模式统一为"平移地图"（永不误改）；唯一例外是抓住编辑手柄——
```
  变异体：在这三行之后插入 `if (mode === "map-edit") { beginPaint(event); return; }`（与旧本体 `:144-147` 逐字相同）。

### m7 —— 守护：**Shift+右键 = 逐格画/擦**（编辑选区、不发写）
- 旧锚点（`r-evidence/mutants/mut-run.sh:158-161`）：
```
          if (event.shiftKey) {
            beginPaint(event);
            return;
          }
```
- **为什么必须换**：★ 结构没变——当前 `map.js:1471-1474` 与旧锚点**逐字节相同**（实测唯一）。照旧复用，理由同 m2/m3（文件被改过）。
- 新锚点（`map.js:1471-1474`）：同上原文。变异体：整块替换为注释（Shift 分支未实现 ⇒ 落回套索，与旧本体 `:164` 相同）。

### m8 —— 守护：**不再有逐格/块边界描边**（不许把旧边框层画回来）★ 本单**重写**的一个
- 旧锚点（`r-evidence/mutants/mut-run.sh:172-173`，位于 `render()`）：
```
      paintHighlights(ctx);
      paintRegionOutlines(ctx);
```
- 旧变异体本体（`r-evidence/mutants/mut-run.sh:175-189`）用的是 **`blocks.forEach(block => block.boundaries …)` 手写环 + `new Path2D()` + `ctx.stroke(...)`**，
  即**旧树**的「块边界层」写法。
- **为什么必须重写（不是「锚点没了」而是「锚点所在的那套渲染路径换人了」）**：
  旧锚点这两行在当前 `map.js:954-955` **仍然存在**，且作为**两行一组**是唯一的（实测：`paintHighlights(ctx);` 单行出现 2 次——
  `:954` 与 `:1815`，但 `paintRegionOutlines(ctx);` 只出现 1 次，即 `:955`，故旧锚点整体仍唯一）；
  旧变异体**也能跑**（`blocks`/`blocks[].boundaries`/`cellSize`/`view`
  在当前树都在：`map.js:324`、`:730`）。但当前树的**块多边形绘制**已经搬进了 Pass 1 的 `paintTerrain`
  （`map.js:747-764`）：用 `blocks.forEach` + `appendBlockTo(path, block)` 聚合成 Path2D，**只 `fill(..., "evenodd")`、不 stroke**。
  旧本体把「拼环」这件事**又手写了一遍**（旧树没有 `appendBlockTo`），在今天就等于「绕过当前树的真实路径另画一份」——
  那不是「恢复旧边框层」，那是「新加一份别的层」。⇒ 按当前树的真实渲染代码改写。
- 新锚点（`map.js:751-759`，实测唯一）：
```
      blocks.forEach(function (block) {
        var color = terrainColor(block.terrain);
        if (!byColor[color]) {
          byColor[color] = new Path2D();
          colors.push(color);
        }
        appendBlockTo(byColor[color], block);
        paintedRingCount += (block.boundaries || []).length;
      });
```
- 新变异体：在**同一个 `forEach` 里**给每一块再建一条自己的 `new Path2D()`（复用当前树自己的 `appendBlockTo`），
  用旧树那条**深色细线** `#0d1015` / `lineWidth = 1 / view.scale` **逐块 stroke**。
- **为什么新锚点仍表达「恢复逐格/块边界描边」**：
  ① 旧行为的可测签名是 `path2dStrokes > 0`（`progress.md:90` 记的包探针实测：干净 `path2dStrokes=0`；m8 恢复边框层 ⇒ `>0`），
     新变异体正是**新增 Path2D + `stroke()` 调用**，签名同型；
  ② 旧边框层画出的东西是「**地形区之间的黑色间隔**」（`progress.md:90` 原话），新变异体 stroke 的正是**每个地形块的边界环**，
     画出来的东西同型；
  ③ ★ 但**「逐格」那一半在当前树上已无对应绘制点**：据台账 `progress.md:90`，唯一真正的逐格 `stroke` 是**选区预览层**（已删），
     地形侧早就是「块级」绘制。故本变异体只能覆盖「块边界描边」这一半——**这一点在结论里如实标为部分不可观察**（见 ④）。
- 守护行为仍在的证据：`map.js:747` 注释「Pass 1（M9 T14）：画全部块多边形…**不再逐格**」、`:901` 注释「**绝无逐格 stroke、绝无简化**」。

---

## 3. ② 每轮实测：红点原文 + `文件:行号`

> 每一轮的 `STEP .*: FAIL` 与 `not ok <n> - ` **均为 0 条**（除 m6 的"本可以红却没红"另述）。
> 装置把每轮实际读到的红点行**原文抄进了日志自己**（`e2e_fail_lines:` / `gate_red_lines:` 段），下面引用即取自那里。

| 轮 | 结局 | gate（`not ok` 行数） | e2e（`STEP …: FAIL` 行数） | 实测原文（带 `文件:行号`） |
|---|---|---|---|---|
| clean | 绿 | 0 | 0 | `mutants/logs/clean-e2e.log:64` `E2E RESULT: ALL PASS` |
| m1 | 存活（不可观察） | 0 | 0 | `mutants/logs/m1-e2e.log:64` `E2E RESULT: ALL PASS` |
| m2 | 存活（不可观察） | 0 | 0 | `mutants/logs/m2-e2e.log:64` 同上 |
| m3 | 存活（不可观察） | 0 | 0 | `mutants/logs/m3-e2e.log:64` 同上 |
| m4 | 存活（不可观察） | 0 | 0 | `mutants/logs/m4-e2e.log:64` 同上 |
| m5 | 存活（不可观察） | 0 | 0 | `mutants/logs/m5-e2e.log:64` 同上 |
| m6 | **存活（判据弱于行为）** | 0 | 0 | `mutants/logs/m6-e2e.log:64` `E2E RESULT: ALL PASS` |
| m7 | 存活（不可观察） | 0 | 0 | `mutants/logs/m7-e2e.log:64` 同上 |
| m8 | 存活（不可观察） | 0 | 0 | `mutants/logs/m8-e2e.log:64` 同上 |

门禁侧原文（m6 为例）：`mutants/logs/m6-gate.log:495` `# tests 82`、`:498` `# fail 0`。

### ③.6 m6 专段：**行为真的被改了，但可跑的判据看不见**（唯一一处预测失败，如实记）

- 预测（`predictions.txt:48-54`，测量前写的）说它会被 `STEP a5-five-modes-left-drag-zero-write: FAIL` 杀掉。**实测没有**。
- **变异体确实生效**（实测差分）：五模式左键拖动记录
  - clean（`mutants/logs/clean-e2e.log:9`）：`[{"mode":"view","moved":true…},{"mode":"region","moved":true…},{"mode":"map-edit","moved":true…},{"mode":"region-edit","moved":true…},{"mode":"unit","moved":true…}]`
  - m6（`mutants/logs/m6-e2e.log:9`）：`…{"mode":"map-edit","moved":false,"nonGet":[]}…`（其余四模式仍 `true`）
  ⇒ 单变量：只有 map-edit 的**平移没了**。
- **但它一条写都没发**（实测 + 代码）：`nonGet` 为空；机制是 `commitBrush` 在「没选地形」时直接早退——
  `simos-app/src/main/resources/webui/map.js:2517-2521`
```
    if (!host.brushTerrain) {
      setMapEditStatus("先选一种地形再涂抹。", "warn");
      active.setBrushHexes([]);
      return null;
    }
```
  而 `brushTerrain` 初值是 `null`（`map.js:1939`），只有点调色板才被赋值（`map.js:2486`）；t9 e2e 里**第一次**点调色板在
  `t9-evidence/e2e/e2e.cjs:624`，**晚于** a5（`:267`）⇒ a5 段内不可能落写。
- **判据为何看不见**：a5 的断言只要求 `perModeLeft.every((r) => r.nonGet.length === 0)`（`t9-evidence/e2e/e2e.cjs:267-270`），
  **不含 `moved`**——`moved` 在 `:260` 算好、`:265` 打进日志，却没进断言。
  对照 M8-R 当年杀掉 m6 的那条判据 `k1-terrain-left-pans-zero-write`：`r-evidence/e2e/e2e.cjs:1040`
  `check("k1-terrain-left-pans-zero-write", panMoved9 && writes9 === 0, …)`，且它**先把地形选上**
  （`r-evidence/e2e/e2e.cjs:1025-1030` 点 `#terrain-palette button[data-terrain]`）⇒ 两个半边都能红。
  **⇒ 本机可跑的 a5 是 k1 的严格弱化版**（少「平移确实发生」半边、少「已选地形」的铺垫）。
- 排除抖动：重跑一轮（端口 5869），`mutant_md5=bae321df103b5c9ebb6af87c8f9d5b4b`、`consumed_agg=9e04b437f152111b2ccc57677afc9d55`、
  `LEFT_PAN_ALL_MODES` 与 `pass=57 fail=0` **逐值相同**；首轮日志留档 `mutants/logs/m6-e2e.run1.log`（第二轮为 `m6-e2e.log`）。
- **处置**：不改判据、不造红点、不把 `moved:false` 冒充当红 ⇒ 记为**存活（判据弱于行为）**。

---

## 4. ③ md5 链

装置 md5 `f81ed3a3a48d290fd8d8d32d8ecb77f3`（`md5sum mut-run.sh` 实测；**10 轮全部同一个装置**，装置未被改过）；
e2e 检查器 md5：`e2e.cjs=75ce5aa08c504c093ec6e27d28ce4c16`、`run-e2e.sh=96288100077e8f58d271d5290b4ffde0`
（写在每轮日志的自指段里，如 `mutants/logs/m6-e2e.log:70`）。

同一轮自指段原文（`mutants/logs/m6-e2e.log:67-68`）：
```
file=map.js anchor_line=1497 outcome=kill
orig_md5=014ffd3aac68dbf282d91e49365d4b0d mutant_md5=bae321df103b5c9ebb6af87c8f9d5b4b agg_expect=9e04b437f152111b2ccc57677afc9d55 consumed_agg=9e04b437f152111b2ccc57677afc9d55 restored_md5=014ffd3aac68dbf282d91e49365d4b0d restored_classes_md5=014ffd3aac68dbf282d91e49365d4b0d
```

| 轮 | anchor_line | mutant_md5 | consumed_agg（e2e 实际加载） | 独立复算 agg_expect | restored(src) | restored(classes) |
|---|---|---|---|---|---|---|
| clean | n/a | =orig | `4294de6d441bc28a7230a616bef77d21` | 同左 ✔ | `014ffd3aac68dbf282d91e49365d4b0d` | 同左 ✔ |
| m1 | 3292 | `9ba61676eea07bfbc89fcfa6dc62c3ea` | `6803e16292b51e5b796f7ba2049d24d6` | 同左 ✔ | `014ffd…4b0d` | 同左 ✔ |
| m2 | 3794 | `caf8466a923d47c208ee306fe6752a7a` | `3228a64f3b9570e7771afebb555ba5b3` | 同左 ✔ | `014ffd…4b0d` | 同左 ✔ |
| m3 | 1326 | `186ecd16896ae3de2d01eb7ccc112f8e` | `2d8e78f92f5f16197250cd39f546c54e` | 同左 ✔ | `014ffd…4b0d` | 同左 ✔ |
| m4 | 3378 | `2439c14602dc0759fdfee1a047096e0d` | `eca4c675e2498e2ccf9a648ac453daf0` | 同左 ✔ | `014ffd…4b0d` | 同左 ✔ |
| m5 | 3412 | `68cdef79868cabc32a542025004fff9f` | `7573f89434392496b65db8e12ca3f453` | 同左 ✔ | `014ffd…4b0d` | 同左 ✔ |
| m6（首轮/重跑） | 1497 | `bae321df103b5c9ebb6af87c8f9d5b4b` | `9e04b437f152111b2ccc57677afc9d55` | 同左 ✔ | `014ffd…4b0d` | 同左 ✔ |
| m7 | 1471 | `cde9a5c755fc6138334e522c467b3566` | `5bb3ddb464f3565e52743b90c6392b23` | 同左 ✔ | `014ffd…4b0d` | 同左 ✔ |
| m8 | 751 | `660ed8a819776538eb9625d875b199bf` | `9ff90f2ec3589f8720258631473a1ac3` | 同左 ✔ | `014ffd…4b0d` | 同左 ✔ |

（orig 全称 `014ffd3aac68dbf282d91e49365d4b0d`；上表简称列均为实测输出截断，非手写。）
**每轮的 agg 互不相同**且各自 == 独立复算值 ⇒ 「跑的是这一轮的字节」有证据，不是「跑的还是原件」。
每轮 `node --check` rc=0（装置在 rc≠0 时直接判 DEVICE FAIL，本轮 8 个全部 rc=0）。
`map.js` 源与 `target/classes/webui/map.js` 的**当前** md5 实测均为 `014ffd3aac68dbf282d91e49365d4b0d`（工作树已复原）。
每轮的自指段还额外记了「e2e 实际同步/服务端真发出来的字节」：如 `mutants/logs/m6-e2e.log` 里
`webui_synced agg_md5=9e04b437… map_js_md5=bae321df…`（= 本轮变异体）。

---

## 5. ④ 我未能核实的（清单）

1. **M8-R 当年的 8 个「杀」无法在本机复认**：m1~m8 的原始判据分别住在
   `h1-overlap-allowed`(`r-evidence/e2e/e2e.cjs:435`)、`g1/g3`(`:843`/`:925`)、`b1/b2`(`:477`/`:491`)、
   `d1-merge-equals-union`(`:727`)、`e1-exclude-equals-difference`(`:763`)、`k1-terrain-left-pans-zero-write`(`:1040`)、
   `f3/k4`(`:812`/`:1174`)、`l2/l3`(`:1254`/`:1318`)——这些装置在本机**跑不起来**（第 1 节的装置级理由，含**真档缺失**）。
   ⇒ 本单只能证明「**当前可跑检查器**看不见这些变异体」，**不能**证明「原判据在今天仍然会红」。
2. **m1~m5、m7、m8 的变异体是否真的改变了浏览器行为，我没有直接观测**——这 7 轮只有「代码路径存在且锚点唯一」
   （推导）与「两侧实测全绿」（实测）两条，**没有**类似 m6 的差分证据。如实标为「不可观察」，不是「无影响」。
3. **m8 的旧判据量具不可用**：`l2/l3` 靠 `page.addInitScript` 包 `beginPath/lineTo/stroke` 的 `window.__strokeStats`
   （`progress.md:90` 记的 `path2dStrokes`）——该探针只存在于跑不了的装置里。故「新变异体是否会把 `path2dStrokes` 顶到 >0」
   **我没有量过**（只有第 ② 节里标明的同型推导）。
4. **m8「逐格」那一半**：当前树已无逐格地形 stroke 的绘制点（`progress.md:90` 说唯一逐格 stroke 是已删的选区预览层），
   新变异体只覆盖「块边界描边」半边 ⇒ 「逐格描边」这一半在当前字节上**无对应判据、也无对应绘制点**。
5. **m2 的下游后果未验**：变异体把守卫放宽后，region-edit 右键**是否真会走到 `unit.PlanRoute`**（需要一次 region-edit 右键 + 一个已选单位），
   本机可跑的 e2e 里**没有**这种用例（t9 全文无 `contextmenu`/`shiftKey`/`button:"right"` 于 region-edit 语境）。
6. **m1 的探测载荷形状**（`api.mapHex(...).regions`）是从旧装置逐字抄来的，我**没有**在真档上验过该端点在这条路径上的返回；
   本机 demo 库上该分支从未被触发（无人套索）。
7. **未跑 Maven**（按派单要求）：门禁平时是经 `exec-maven-plugin` 挂进 `test` 阶段的（`progress.md:105-110`），
   本单**直接跑** `node src/test/js/run-gate.cjs`，未复现 Maven 那一层；也未跑 SpotBugs / 927 项 Java 门禁（与纯前端 delta 无关）。
8. 未测：dpr>1 / HiDPI 像素采样、触摸与触控笔（沿用台账 `progress.md:95,84` 的老口径）。
9. 台账措辞与旧本体的一处不一致（m4，见 ②-m4）已记，但**未去追**其他轮的台账措辞是否也有出入。
10. 并行的只读 agent（派单提到）与本单**没有任何交互**；本单未跑任何编译。

---

## 6. ⑤ 结论

| 变异体 | 守护的行为（当前字节上仍在） | 结局 |
|---|---|---|
| m1 | 套索建区**重叠允许**（`map.js:3285-3287`、`:3176`） | **存活 / 不可观察** |
| m2 | 右键**按模式分派**（`map.js:3791-3807`） | **存活 / 不可观察** |
| m3 | 小点**只画 focus**（`map.js:855-859`、`:1326-1328`） | **存活 / 不可观察** |
| m4 | 合并 = **并集**（`map.js:3368-3382`） | **存活 / 不可观察** |
| m5 | 剔除 = **差集**（`map.js:3402-3416`） | **存活 / 不可观察** |
| m6 | 地形编辑左键 = **平移、零写**（`map.js:1500-1501`、`:1497-1499`） | **存活 / 判据弱于行为**（唯一有差分证据的） |
| m7 | Shift+右键 = **逐格画/擦**（`map.js:1466-1474`） | **存活 / 不可观察** |
| m8 | **不再有逐格/块边界描边**（`map.js:747-764`、`:901`） | **存活 / 不可观察**（且「逐格」半边无绘制点） |

**0 被杀 / 8 存活**。★ 如实归结：**这是判据覆盖问题，不是护栏失效问题**——8 条护栏在当前字节上都还在（上表逐条给了行号），
但守护它们的判据装置（r-evidence / s-evidence / t10 的 e2e）在本机**因缺真档 + 装置路径指向另一台机器而不可运行**；
本机可跑的门禁（纯函数级）与 t9 e2e（区域**查看**模式为主）**本就不覆盖**这 8 条行为。
其中 m6 更进一步：可跑的 a5 断言是原判据 k1 的**严格弱化版**，因此「平移被抢走」这种变异在 a5 下**结构性地**测不出来。

**留给 T12/控制器的两条建议（本单不做，因为都属「改判据/改装置」，越权）**：
1. 把 `a5-five-modes-left-drag-zero-write`（`t9-evidence/e2e/e2e.cjs:267-270`）补上它已经算好的 `moved` 半边，即可覆盖 m6 这类「平移被抢、但不发写」的变异；
2. 把 r-evidence 的判据装置**本机化 + 解决真档来源**（或把 19441 格真档换成一个可复现的合成大图夹具），
   否则 m1~m5、m7、m8 这 7 条行为在关账后仍将是**无判据**状态。

**本单产出**（全部留在工作树，未提交、未推送）：
`t12-evidence/m8r-rerun/mut-run.sh`（新装置）、`…/predictions.txt`（含「预测 vs 实测：修正记录」）、
`…/mutants/orig/map.js`（当前字节快照）、`…/mutants/logs/**`（10 轮日志 + `runs.log` + m6 首轮留档）、
`…/notes/m8r-rerun-report.md`（本文件）。
★ 未写进仓根、未覆盖 `r-evidence/` 里任何旧文件；`m9`（RDP 简化）因 M8-S 删除整段而**作废**，未重跑。
