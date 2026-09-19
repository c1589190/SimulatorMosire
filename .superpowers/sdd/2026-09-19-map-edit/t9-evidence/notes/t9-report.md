# M8 T9 —— 区域查看模式 UI：任务报告

证据目录 `.superpowers/sdd/2026-09-19-map-edit/t9-evidence/`（`e2e/` `logs/` `mutants/` `notes/`）。
本次改动：`webui/map.js`、`webui/panels.js`、`src/test/js/{run-gate,gate-contract.test}.cjs`、新增 `src/test/js/region-view.test.cjs`。
**零 Java 改动**（`git status --porcelain | grep -c '\.java$'` 实测 **0**）。

## 1 结论（一行）

T9 的两条判据都在**真浏览器 + 真写路径**上闭合：重叠格点选 ⇒ 高亮**全部**从属区域（`highlighted == regions`，长度 2 > 1），
选区域 ⇒ **其它区域逐值可断言的淡色**（焦点 `0.42/0.52` vs 淡色 `0.13` + 独立复算的混合色）；
两模式共用**一份** `buildRegionHighlightPlan`（差别只有两组参数常量）；左栏**不做求和**——合计是真并集（3 格）且把"求和 4"当红点杀掉；
7 个变异体**全杀**（m1/m3 在浏览器层红，含 T10 抓过的"焦点先入"缺陷）；整树 `./mvnw clean verify` rc=0、977 条、`BugInstance size is 0` ×6、`[ERROR]` 0。

## 2 逐判据实测数字（干净轮：`logs/clean-e2e-final.log`，57/57 STEP PASS，`E2E RESULT: ALL PASS`，`e2e_rc=0`）

**装置自指**：`webui_synced agg_md5=4294de6d441bc28a7230a616bef77d21 map_js_md5=014ffd3aac68dbf282d91e49365d4b0d`；
`harness_md5 e2e_cjs=75ce5aa08c504c093ec6e27d28ce4c16 run_e2e_sh=96288100077e8f58d271d5290b4ffde0`（`clean-e2e-final.log:1-2`）。

### ① "点 hex ⇒ 高亮所有从属区域（多值）" —— 计划 §T9 必须做
| 断言 | 实测 |
|---|---|
| h0 重叠格真有 ≥2 个从属 | `GET /api/map/hex?q=1&r=2` → 200，`regions:["t9_a","t9_b"]`（字典序） |
| h0b 夹具与硬编码期望一致（独立复算） | `unionAB=3 sumAB=4 sharedAB=1 fadeB=#8a8adc fadeC=#8adc8a` |
| h1 世界是**真写**出来的 | region-edit 模式 3 条 `map.CreateRegion` 全 `ok:true`，`head 9 → 12` |
| **h2a 高亮数 == `regions.length`（>1）** | `highlighted=["t9_a","t9_b"]`，`regions=["t9_a","t9_b"]`（长度 2） |
| h2b 高亮**格数** == 并集 | `highlightHexCount=3 union=3` |
| h2f 区域查看模式**只读** | `posts=0` |

### ② "选区域 ⇒ 其它淡色"（**逐值可断言，不是"看起来淡了"**）
| 断言 | 实测 |
|---|---|
| h2c 焦点区在重叠格上**不被淡色顶掉** | `at13={"color":"#0000ff","alpha":0.42}` |
| h2d/h2e 其它区域被淡化 | `fadedRegions=["t9_c"]`，`highlightAlphas=[0.42]` |
| h4b/h4c 单焦点 ⇒ 两档透明度真的渲染出来了 | `faded=["t9_b","t9_c"]`，`highlightAlphas=[0.13,0.42]` |
| h4d 淡色 = **独立复算**的混合值 | `at13={"#8a8adc",0.13}`，期望 `#8a8adc`（harness 内自算 `t=0.68→203`） |
| h4e 焦点区自己的格仍是原色 | `at12={"#ff0000",0.42}` |
| **h4h 焦点先入**（★ T10 抓过的真缺陷的回归位） | 焦 `t9_b`：`at12={"#0000ff",0.42}` **且** `at13={"#0000ff",0.42}` |
| h4f/h4i 选区域**不发写** | `posts=0`（两次） |

### ③ 两模式**共用一份实现**、且各自独立可断言
| 断言 | 实测 |
|---|---|
| 门禁：两模式的宿主入口都走 `reloadRegionHighlight` | `page-层-uses-the-shared-plan-for-both-modes`（含反向：`fadeRegionColor(base)` 全仓**只出现 1 次**、旧的内联排序 `if (region && region.id === focus)` 已不存在） |
| 门禁：差别只有参数 | `REGION_VIEW_HIGHLIGHT.focusAlpha=0.42` vs `REGION_EDIT_HIGHLIGHT.focusAlpha=0.52`；`fadeWhenNoFocus=false/true` |
| e2e 区域查看侧 | h2*/h4*（见上） |
| e2e 区域编辑侧（T10 语义重建后**逐值不变**） | h5a 无焦点⇒全淡 `faded=3 个, alphas=[0.13]`；h5b 焦 `t9_a` ⇒ `faded=["t9_b","t9_c"], alphas=[0.13,0.52]`；h5c `at12={"#ff0000",0.52}`；h5d `at13={"#8a8adc",0.13}`；h5e 不发写 |

### ④ ★★ 裁定 72.1：左栏**禁止把各区域 `hexCount` 求和当并集**
| 断言 | 实测 |
|---|---|
| h3a 每个区域报**它自己的** `hexCount` | `[{"t9_a":"2"},{"t9_b":"2"}]` |
| **h3b 合计 = 真并集** | `data-union-count="3"`，而求和是 **4**（`sumAB=4`）—— 两者不等；文案 `"3 格（并集，逐 hex 去重；2 个区域，重叠 1 格只计一次）"` |
| h3c 重叠格数单列 | `shared="1"`（独立复算 `sharedAB=1`） |
| 门禁：写 DOM 的那个函数里**只有**并集 | `page-层-shows-the-union-and-never-a-summed-total`（`hexCountSum` **不许**出现在 DOM 写入函数里；`总面积` 亦不许） |

### ⑤ 既有能力不退化（判据⑥ 的相关部分）
`z0-no-pageerror: PASS []`（0 个 pageerror）；同一次干净轮里 T8/T11 的 26 条断言（`a*`/`b0`/`c0`/`e*`/`s*`/`x1*`/`r*`/`f0`/`g2`/`g3`）全部 PASS。

## 3 变异轮（**最终扫轮**，全部 7 轮跑在同一份装置上：`mut-run.sh md5=e0267dbd2d5d845a2f979f1547265c2b`，各轮日志第 68 行/第 8 行自指同一 md5）

每轮都通过的门禁：`syntax_rc=0`；`mutant_md5 != orig_md5`（非空）；e2e 轮 `consumed_agg == 独立复算的 agg_expect`；
还原后 `restored_md5 == orig_md5`（**src + target/classes 两处**，`cp` 还原，绝无 `git checkout --`）。
★ 本次**没有 Java 变异轮**——零 Java 改动 ⇒ 无目标类（`mut-run-java.sh` 未用）。

| 轮 | 变异体 → 目标 | orig_md5 | mutant_md5 | 杀红点（原文 + 日志行） |
|---|---|---|---|---|
| m1 | 只高亮第一个从属区域 → `map.js:2380` `setHighlightRegions(regionIds)` | `014ffd3aac68dbf282d91e49365d4b0d` | `38b7cc97b86949fec42fbfa6f76c483b` | `STEP h2a-highlight-count-equals-regions-length: FAIL {"highlighted":["t9_a"],"regions":["t9_a","t9_b"]}`（`m1-e2e.log:39`；另 h2c/h2d/h2e 连带红） |
| m2 | 合计显示**求和** → `panels.js` `appendRegionMembership` | `940bff47d74bc22c29e2187e2f4427e7` | `dc5a7c35aed3c637244cb73f6a3e9816` | 门禁 `not ok 51 - page-层-shows-the-union-and-never-a-summed-total`（`m2-gate.log:303`）+ e2e `STEP h3b-total-is-the-union-not-the-summed-hexcounts: FAIL {"union":"4","sumAB":4,"text":"4 格（并集，逐 hex 去重；2 个区域，重叠 1 格只计一次）"}`（`m2-e2e.log:46`） |
| m3 | 去掉"焦点先入"重排 → `map.js` `buildRegionHighlightPlan` | `014ffd3aac68dbf282d91e49365d4b0d` | `59cb45347376ebcc5a2d6d2710515bce` | 门禁 `not ok 42 - buildRegionHighlightPlan-puts-focus-regions-first`（`m3-gate.log:249`）+ e2e `STEP h4h-focus-wins-both-of-its-overlap-hexes: FAIL at12={"color":"#dc8a8a","alpha":0.13} at13={"color":"#0000ff","alpha":0.42}`（`m3-e2e.log:55`） |
| m4 | 淡色条目也用焦点透明度 → 同上函数 | `014ffd3aac68dbf282d91e49365d4b0d` | `84d1ee3d692cdb4aa7542db45923fab7` | 门禁 `not ok 42/43/45`（`m4-gate.log:249/290/390`，最后一条 `- buildRegionHighlightPlan-region-edit-without-focus-fades-everything`）+ e2e `STEP h4c-two-alpha-levels-are-really-rendered: FAIL [0.42]`（`m4-e2e.log:50`） |
| m5 | 淡色直接用原色（不混合）→ 同上函数 | `014ffd3aac68dbf282d91e49365d4b0d` | `8ad497d42ffaba487029e2249190d4af` | 门禁 `not ok 42/45/47`（`m5-gate.log:249/302/330`）+ e2e `STEP h4d-faded-fill-is-the-independently-computed-mixture: FAIL at13={"color":"#0000ff","alpha":0.13} expected_color=#8a8adc`（`m5-e2e.log:51`） |
| m6 | 未知焦点 id ⇒ 退化成"全都淡色" → 同上函数 | `014ffd3aac68dbf282d91e49365d4b0d` | `7b8df048b29dbf4139e7a575990aa7d9` | 门禁 `not ok 44 - buildRegionHighlightPlan-region-view-without-focus-paints-nothing`（`m6-gate.log:261`） |
| m7 | 取不到 hex 列表 ⇒ 拿求和顶替 → `panels.js` `regionMembershipSummary` | `940bff47d74bc22c29e2187e2f4427e7` | `af175322dcdde69519f2c1427f02b499` | 门禁 `not ok 50 - regionMembershipSummary-refuses-to-guess-without-hex-lists`（`m7-gate.log:297`） |

**红点性质说明（不许含糊）**：T9 的变异体全在 **JS 层**，杀点因此是**前端门禁的 TAP 行**（`not ok N - <用例名>`）与 **e2e 的 `STEP <名>: FAIL` 行**——
`Surefire` 报告里**不可能**有它们（没有 Java 变异体）。另：`failed` 的形态在此为 **`rc=1`**；
`rc=2`（装置崩溃）**不算红**，本轮 5 个 e2e 轮全部实测 `e2e_rc=1`。
★ 门禁失败时它自己也会红并给出行文：`[frontend-gate] 前端测试失败：tests=82 pass=81 fail=1 rc=1`（`m2-gate.log:519`）。

### 3.0 九条装置纪律**逐条**对表（一条都不许静默跳过）

| # | 纪律 | 本轮兑现方式（JS 层） |
|---|---|---|
| ① | 干净世界基线先绿 | 每轮开跑前断言 `src == mutants/orig`（`cmp`），不一致即 `DEVICE FAIL(①)` 退出；干净轮 57/57 ALL PASS |
| ② | 变异体必须与原件**字节不同**（先自证） | `mutant_md5 != orig_md5` **且两者非空**，否则 `DEVICE FAIL(②)` |
| ③ | 按白名单推成**目标类名** | **N/A**（JS 无类名间接层；目标就是文件本身）。等价物：`node --check` + 下面 ⑨ 的"消费字节自指" |
| ④ | 每轮清掉规范名之外的 `.class`/`.java` | **N/A**（无 javac）。等价物：`target/classes/webui/<file>` 被同步成变异体、还原时两处都断言 md5 |
| ⑤ | 强断言 `COMPILATION ERROR` 0 且 `Tests run ≥ 1` | **N/A as written**（无 javac/surefire）。等价物：`syntax_rc == 0` 否则该轮作废；检查器必须报出用例数（`# tests 82`） |
| ⑥ | surefire 报告 mtime 落在本轮内 | **N/A**（本轮的检查器是 `node --test` 与浏览器，不读 surefire） |
| ⑦ | 红必须落在**被保护的那条断言**上 | 门禁按 `not ok N - <名>` 点名、e2e 按 `STEP <名>: FAIL` 点名，**两套命名分开钉**（m2 首轮的假阴性就是混用命名造成的） |
| ⑧ | 逐字节还原（`cp`，**绝不** `git checkout --`）并断言复原 | `restored_md5 == orig_md5`，**src 与 `target/classes` 两处**都断言 |
| ⑨ | 日志必须自指 | 每轮把 `file/orig_md5/mutant_md5/agg_expect/consumed_agg/restored_md5/harness_mut_run_md5/各检查器 rc/预期红点名` **追加进日志本身**（`*-e2e.log` 末段 "device self-record"） |

### 3.1 两个**装置/预测**事故（如实记，都是"先怀疑自己"）

1. **m2 首轮判 SURVIVED 是假阴性**（装置 bug）：门禁报的是 JS 用例名、e2e 报的是 STEP 名，装置却拿 STEP 名去核门禁的 `not ok` 行。
   两层其实都真红了。已改成**两套命名分开钉**（`expect_red(gate)` / `expect_red(e2e)`），首轮日志留档 `m2-*-DEVICEBUG.log`。
2. **m3 首轮预测错**（我错，不是装置）：预测 e2e 红在 `h2c`，实测**全绿**。原因是**夹具不可判别**——h2 的焦点是两个从属区域、
   待淡化的 `t9_c` 排色板末位；h4 的焦点 `t9_a` 又是色板首位 ⇒ 不重排也先入（`h4e` 在 m3 下照样 PASS 就是这一点的直接证据）。
   ⇒ 补 **H4b**（焦 `t9_b`：与 `t9_a`、`t9_c` 各叠一格、在色板里居中）+ 重跑干净轮 ⇒ m3 当场在浏览器层红（见上表）。
   这正是"判据写得对、但夹具选得不对"的形态，故连预测修正一起留在 `mutants/predictions.txt`。

## 4 门禁（`./mvnw clean verify`）

| 项 | 实测（`logs/full-verify-postmutants.log` = 7 轮变异**之后**、字节逐字节还原后的那次） |
|---|---|
| 整树 rc | **0**（装置不靠推断：`logs/verify-rc.txt` 内容 `verify_rc=0`，由 `... ; echo verify_rc=$? > …` 当场落盘） |
| 逐模块 `Tests run` | `util 170 / map 362 / social 45 / unit 131 / core 169 / app 100` = **977**（与派单基线 **逐值相同**） |
| 失败/错误 | 每模块 `Failures: 0, Errors: 0, Skipped: 0` |
| `BugInstance size is 0` | **6** 次 |
| `[ERROR]` 行数 | **0** |
| 前端门禁行 | `[frontend-gate] OK tests=82 pass=82 fail=0`（`full-verify-postmutants.log:1650`），下界 `MIN_TESTS=82` / `MIN_ASSERTIONS=82`（由 71 双升） |
| `Total time` | 08:07 min（`full-verify.log` 那次 06:58 min） |
| 模块数 | 7/7 `SUCCESS` |

★ **一处与派单预期的出入**：我原以为 JS 断言会并进 `simos-app` 的 surefire 计数（⇒ 988）。**实测不是**——
前端门禁由 `exec-maven-plugin` 独立执行，行文只进 `[frontend-gate] ... tests=82`，**不进 surefire 合计**；
故 977 与基线逐值相同，而门禁自身的下界确实升到了 82，且**升了之后仍被变异体打红过**（m2~m7 的 gate 轮）。
"下界是真护栏"的证据不是"+11 个数字"，是**它红过**。

### 4.1 两次整树运行
`logs/full-verify.log`（变异前）与 `logs/full-verify-postmutants.log`（7 轮变异之后、字节已逐字节还原）——
两次的对象是同一份字节（`logs/verify-inputs.txt` 记了 `git status` 计数与 5 个文件的 md5：`map.js 014ffd3a…`、`panels.js 940bff47…`、
`run-gate.cjs 0536a019…`、`gate-contract.test.cjs b202c25b…`、`region-view.test.cjs 184e4020…`、`HEAD 27e0887`、`java_changed=0`）。
两次日志**逐行相减只差时间戳、临时目录名与耗时**（`diff` 过滤 `elapsed` 后余下的全是 `2026-09-19T…Z` 时间戳与 `/tmp/junit-…` 路径），
**没有一处结论性差异**；rc 由第二次当场落盘。

## 5 ★★ 我未能核实的

1. **真档问题（派单 §1.3）未被消除，只是被绕开**：本机**没有**任何真档（派单点名的 `/tmp/m6-import-verify/test_integration`、`*_map.json`、`simos.db` 都不存在）。
   全部证据跑在 `--demo` 的 **3 格**世界上，"1 hex ≥2 区域"的夹具是用**真写路径**（3 条 `map.CreateRegion`）在 demo 世界上**造**出来的
   ——它是真 ChangeSet/真服务端/真浏览器，但**不是** 19441 格的导入档。**多从属在真档上未被浏览器验证。**
2. **一个 hex 属于 ≥3 个区域**：只有合成夹具（`regionMembershipSummary-keeps-three-owners-as-one-hex`）；端点与浏览器上最多 **2** 个从属。
   （与 M8 T1 台账同款缺口，本次未消除。）
3. **左栏是 N+1 次请求**：`renderRegionMembership` 为**每个**从属区域发一次 `/api/map/region/<id>`；从属很多时（十几二十个）的请求数与耗时**未测**。
4. **m6/m7 只有纯函数层的红**：`e2e` **没有**对应断言可杀——"焦点 id 不在本世界色板里"与"某个区域的 hex 列表取不到"这两种状态，现装置造不出来。
   ⇒ 这两条护栏在浏览器层**未经变异证伪**，我不把门禁层的红冒充成"浏览器层也验过"。
5. **淡色只有"入参级"证明，没有像素证明**：断言的是 `setHighlightHexes` 的 entries（经新增的只读投影 `regionHighlightAt`）+ 独立复算的混合值，
   **不是** canvas 取色。M7 判据④那种像素级证明本次没做（截图在 `e2e/t9-*.png`，只是旁证）。
6. **`regionHighlightAt` 的一条分支未覆盖**：entry 的 `alpha` 为 `null` 时回落到 `HIGHLIGHT_ALPHA`——该分支无用例。
7. **左栏不显示区域 `name`**（只显示 id 与 `hexCount`）：spec/计划未要求，但若 T10 的左栏要显示名字，这里要补。
8. **m4/m5 的两条 e2e 断言是共用夹具**（同一份"焦点/淡色五元组"），杀 m4 时红 42/43/45、杀 m5 时红 42/45/47——
   "一条断言只钉一件事"没做到；拆分夹具未做。

## 6 有裁定的遗留（需裁决）

1. **"合计"这一行本身要不要保留**：裁定 72.1 只禁"求和当并集"，没说要显示并集。当前实现给了并集 + 口径文案；
   若产品上根本不需要"合计"，**删掉比修文案更省**。建议 T10 一并裁。
2. **双下界同值双写**（`run-gate.cjs:17` 与 `gate-contract.test.cjs:25` 都是 82）：已加注释互相提醒，但仍是两处；
   合成一处常量需要动 exec 插件的参数传递，本次**未做**。
3. **`h2b` 的判据含义**：它判"高亮**格数** == 并集"，实测在 m1 下**不红**（只高亮 t9_a 时，t9_b/t9_c 仍以淡色贡献条目，
   去重后的格数仍是 3）。⇒ "高亮了谁"只能靠 **id 列表**（h2a）与**颜色/透明度**（h2c/h2d/h2e）判，格数天生不可判别。
   这条已写进 `mutants/predictions.txt`，供后续任务选判据时参考。
