# V3 报告 —— 点击 ⇒ 选中「拥有该 hex 的最顶层区域」（定义序末位）

> 分支 `wsf2/v3`，worktree `.claude/worktrees/wsf2-v3`，基线 **`408b034`**（V1/V2 合并点）。
> 用户原话：「我希望在**区域查看/编辑模式下，点击一个地方，就自动选中拥有这个 hex 的最顶层区域**（其实就是防止有重叠区域）」；
> 「最顶层」定义 = **按「创建/定义顺序」，后定义的在上**（用户答「1」）。
> 设计记账：`docs/superpowers/specs/2026-09-22-webui-fix2-design.md` **§八**（★ 明记**推翻 M8-Q6 / U3-2**）；M8 spec 相应行已加「★ V3 取代」标记。

## 一、改了什么（最小风险：序从 `GameMap.regions` 派生，`RegionIndex` 结构未动）

| 文件 | 改动 |
|---|---|
| `simos-map/.../resolve/MapResolver.java` | `regionOfHex`：成员仍取 `map.regionIndex().regionOf(hex)`（一次 `Map.get`），随后**按 `map.regions().keySet()` 插入序过滤重排** ⇒ 列表**末位 = 最顶层** |
| `simos-map/.../region/RegionIndex.java` | **仅加一行注释**（本类不承载层次）；**结构/行为零改动** |
| `simos-app/.../gui/ApiViews.java` | `mapHex` 的 Javadoc：`regions` 由「字典序」改为「定义序、末位=顶层」 |
| `simos-app/src/main/resources/webui/map.js` | 新增纯函数 `topRegionId(regionIds)`（**只取末位、绝不重排**）+ 导出；`selectRegionOfHex` 改用它、恒 `"single"` |

★ **铁律 5**：`RegionIndex` / `GameMap` / `MapChangeSet` **未改结构** ⇒ 往返不变式不受影响（全量 `clean verify` 里 `GameMapTest`/往返用例全绿）。
★ **铁律 1/2**：无新写入口；`/api/map/hex` 仍只读。
★ **M8-U1 不破**：从属仍多对多、重叠**全部保留**（列表仍列出全部 owner，只是改了顺序）；V3 只加一条排列约定。

## 二、判据（夹具让定义序与字典序**分叉**）

### 2.1 Java
- `MapResolverTest.regionOfHexFollowsDefinitionOrderWithTheTopRegionLast`：两从属 `r2`(先插)、`r1`(后插) ⇒ 定义序 `[r2,r1]`、**末位 `r1`**；字典序末位是 `r2` ⇒ 断言 `getLast().isNotEqualTo(r2)` 是**退回字典序的杀点**。
- `MapResolverTest.regionOfHexTopIsTheLastByDefinitionNotTheLexicographicMax`：三从属 `m,z,a` ⇒ 定义序末位 `a`，字典序末位 `z` ⇒ 分叉。
- `MapRegionEndToEndTest.overlappingOwnersFollowDefinitionOrderNotLexicographicOrder`：**真命令路径**（真 `CoreSimos`+store）先建 `zz` 再建 `aa` ⇒ 定义序 `[seed,zz,aa]` 末位 `aa`；字典序 `[aa,seed,zz]` 末位 `zz` ⇒ 分叉。
- 注：`MapRegionEndToEndTest` 既有的 `seed/t4` 用例**不使两种口径分叉**（seed 先插且字典序更小）⇒ 已在注释里**如实标明**，判别力由上面两条承担。★ L5「索引而非扫描」的判据自 V3 起由 `RegionIndexGuardTest.L5_regionOfIsIndexedNotScanned` 的**计数式**守卫承担（定义序派生必然扫一遍 `regions`）——原 `regionOfHexUsesTheIndex` 改判定义序/顶层，**不是改成恒真**。

### 2.2 前端（node 门禁）
- `webui-fix2.test.cjs` 新增 `topRegionId-picks-the-definition-order-last-not-the-lexicographic-max`（`["zz_first","aa_second"]` ⇒ `aa_second`，且 `!= zz_first`；三从属 `[m,z,a]` ⇒ `a`；空/null ⇒ null）。
- 原 `sources-split-...` 改写：断言 `map.js` 含 `var top = topRegionId(regionIds)` 与 `app.setHighlightRegions([top], "single")`，并**反向断言**旧的 `regionIds.length === 1 ? "single" : "group"` 已**不在**（V3 取代 U3-2）。
- `region-view.test.cjs` 的多焦点用例**改名并限定语义**（该路径现归**点 tag**=group，不再是点击语义；断言逐字未改）。

### 2.3 真浏览器 e2e（真富世界，`--demo`：59223 hex / 252 区域）
夹具 **真分叉**：hex `[54,-67]` 的 owners 按 overview 序号（定义序）为 **`[大蜀, 东川]`**（末位 **东川**），而**字典序**为 `[东川, 大蜀]`（末位 大蜀）。
- `a2`：`/api/map/hex` 的 `regions` **就是定义序**（逐值等于按 overview 序号排序）。
- `b1`：点格后 `highlightRegions === ["东川"]`（**恰一个、定义序末位**）；`b2`：**不是**字典序末位 大蜀、也**不是**全部 owner；`b3`：`highlightKind === "single"`。
- `b4`：`regionHighlightAt(54,-67)` = `{color:"#e77a78"(=东川 meta.color), alpha:0.62(=singleFocusAlpha)}`。
- **像素证明（独立复算）**：observed `(171,113,93)` vs 预测 `0.62·东川 + 0.38·(0.45·terrain + 0.55·#0a0d12)` = `(171.99,113.07,93.72)` ⇒ **distTop = 1.22**；对「若选错成大蜀」的预测 **distWrong = 37.40**。判别间距 38.25。
- `d1`：点格 **零非 GET 请求**（只读）。
- 截图：`e2e/v3-click-top-region.png`（md5 `a853fde594c86faae86bf2d102c74a6c`）——★ **人工看图确认**：左栏详情 `q=54,r=-67`、`regions 大蜀、东川`，右栏 **东川** 选中，中心 hex 填充为东川色。**未重蹈"只报 ✅ 不看图"**。

## 三、门禁（★ 现场重算，只取模块汇总行）

| | 值 |
|---|---|
| 基线 `408b034`（主树现场重跑 `clean verify`） | rc=0、**1360** = `170/368/45/259/178/129/211`、8/8、`BugInstance 0 ×7`、`[ERROR] 0`、前端 **187/187** |
| 本树**最终绿轮** | `.superpowers/sdd/2026-09-22-webui-fix2/v3-evidence/logs/clean-verify.final.log`（md5 `05d181a88029a40d147606cceaa2bf2f`，`verify-rc-final.txt`=`rc=0`） |
| 结果 | rc=0、**第 1 次尝试**、**1362** = `170/369/45/259/179/129/211`、**8/8 `SUCCESS [`**（显示名 `UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp` + 父）、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `tests=188 pass=188 fail=0` |
| delta | map **368→369**（+1 = 新增三从属用例）、core **178→179**（+1 = 真命令路径分叉用例）、app Java 不变、前端 **187→188**（+1） |

（`clean-verify.attempt1.log`（23:54 同字节）与 `clean-verify.final.log`（收尾）**同一份源字节**、逐值相同，两份都留档。）

## 四、变异（九道门禁：干净世界 → md5 自证字节不同 → 单次逐字替换 → 断言真跑到 → 红点落被保护断言 → 逐字节还原 → 日志自指）

| id | 靶子 / 变异 | 结果 | 红点 |
|---|---|---|---|
| `js-m1` | `topRegionId` 改取**首位** | **KILLED** | `topRegionId-...`（`expected 'aa_second'`） |
| `js-m2` | `topRegionId` **退回字典序**（`.sort()` 后取末位） | **KILLED** | `topRegionId-...` |
| `js-m3` | `selectRegionOfHex` **退回 group**（多从属传全部） | **KILLED** | `sources-split-...` |
| `j-m1` | `MapResolver` 定义序 **退回字典序** | **KILLED** | `MapResolverTest.regionOfHexFollows...` + `...TopIsTheLast...`（各 1 条） |
| `j-m2` | `MapResolver` **顺序反转**（`.reversed()`） | **KILLED** | 同上两条 |
| `j-m1b` | `j-m1` 的补充轮（`-Dmaven.test.failure.ignore=true`，绕开 reactor 短路） | **KILLED** | `MapRegionEndToEndTest.overlappingOwnersFollowDefinitionOrderNotLexicographicOrder` |
| **e2e-m1**（浏览器层） | jar 副本里 `topRegionId` 改取**首位** | **KILLED** | `b1`/`b2`/`b4`/`c1`（选中 `["大蜀"]`、像素 distWrong=1.38 vs distTop=38.7） |
| **e2e-m3**（浏览器层） | jar 副本里 **退回 group** | **KILLED** | `b1`/`b2`/`b3`/`b4`/`c1`（选中 `["大蜀","东川"]`、kind `group`、alpha 0.42） |

**★ 裁定 42（改既有文件 ⇒ 在最终字节上重派生相关既有变异轮）**：V3 改了 `MapResolver.java` 与 `map.js`（改的是 `regionOfHex` 与 `regionOfHex` 调用点）。⇒
① **行为被 V3 取代的旧轮**（M8 T1 的 `MapResolver` 排序轮、U3 的 `selectRegionOfHex` 轮）已在最终字节上**重派生**为 `j-m1`/`j-m2`/`js-m3`/`e2e-m1`/`e2e-m3`（红点落在**新**判据上）；
② **改文件但未改其目标字节的旧轮**（V2 `map.js` 区域名门控）也在最终字节上**逐字重放**：`r-v2m1`/`r-v2m2`/`r-v2m3`/`r-u2a` **全部 KILLED**（日志 `mutants/logs/r-*.log`）。
★ **范围声明**：V1/U1/U5 的变异体靶在 `styles.css`/`panels.js`（V3 未改）与其它 worktree，**未重放**——这是范围声明，**不是"已重跑"**。

**存活：0（本任务共 11 轮：7 新 + 4 重派生）。** 每轮还原后 `md5` 与原件逐字节相同（`map.js`=`3a5e981d…`、`MapResolver.java`=`7fc1e6a7…`）；浏览器层两轮用 **jar 副本**（源文件一字未动），故不影响最终绿轮字节。日志在 `v3-evidence/mutants/logs/` 与 `v3-evidence/e2e/mutant/`。

## 五、我未能核实的 / 如实披露

1. **region-edit 模式下没有"点击选区域"**：左键在 region-edit 是**平移地图**、右键是套索（M8-R §七 的按键模型）⇒ V3 的"点击取顶层"只作用于**区域查看**模式（唯一有"点 hex ⇒ 高亮区域"的模式）。用户原话含"编辑模式"，但该模式无此交互——**未擅自改动编辑模式按键模型**。
2. **`/api/map/hex` 之外的读路径**：`selectNationOfHex`（决策模式）仍遍历**全部** owner 收集国家 tag（语义是 nation 集合，不受顺序影响）——未改。
3. **性能**：`regionOfHex` 每次 `map.regionIndex()` 会重建整表（O(总 hex)），这是**既有**行为、非本次引入；在 59223 格真档上点击正常（e2e 实测）。
4. **多从属 >2 的浏览器实测**：真富世界里找到的分叉夹具是 **2 从属**；三从属仅 Java/JS 单测覆盖（真档多从属样例有 3 从属，但本轮未在浏览器上验 3 从属的"取末位"）。
5. **真档只这一份**（`--demo` 的 v17levant 复刻）；未换别的档。
6. **e2e 的"高亮要等 252 个区域 hex 拉齐"**：`reloadRegionHighlight` 的 N+1 代价导致点击后约数秒才画完（e2e 用 `waitForFunction` 等到位）。这是既有实现，未优化。
7. **`Region` 的 `meta.color` 缺失时**用兜底色 `#00e5ff`（`resolveRegionColor`）——本轮两个区域都有色，兜底分支**未在 e2e 触发**。

## 六、下一步（建议）

- U4（富世界改用 n0008 最全区域）按用户"等前两项修完"的顺序，可开工（本 V3 与 U4 无耦合）。
