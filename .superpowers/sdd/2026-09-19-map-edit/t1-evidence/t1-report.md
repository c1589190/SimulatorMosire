# M8 T1 报告 —— 多从属地基（区域从属由单值改为多值）

分支 `m8/t1`，worktree `.claude/worktrees/m8t1`，基线 `f6417e3`。
裁定依据：**M8-U1**（用户原话「hex 只是地形块，应当兼容多种从属」）⇒ 区域从属是**多对多**，不存在"重叠时谁赢"。

## 1. 改了什么（逐文件）

| 文件 | 改动 |
|---|---|
| `simos-map/.../region/RegionIndex.java` | 字段 `Map<HexCoord,RegionId>` → `Map<HexCoord,List<RegionId>>`；`of()` 改为按 `RegionId.value()` 升序处理、逐格**追加**（列表天然字典序），每格列表 `List.copyOf` 冻结；`regionOf(c)` 返回 `List<RegionId>`（无归属 ⇒ **空列表**，不再 `null`）；`hasRegion` 不变 |
| `simos-map/.../resolve/MapResolver.java` | `regionOfHex`：`Optional<RegionId>` → `List<RegionId>`（委托索引，仍 O(1)）；删除不再用的 `java.util.Optional` import |
| `simos-map/.../map/City.java` | 仅 Javadoc：`RegionIndex.regionOf` 无归属"返回 null" → "返回空列表" |
| `simos-app/.../app/gui/ApiViews.java` | `mapHex(...)` 形参 `RegionId region` → `List<RegionId> regions`；JSON 键 `region`(单值/null) → **`regions`**（数组，逐 id value，字典序；空数组 = 无从属） |
| `simos-app/.../app/gui/GuiServer.java` | `mapHexReply`：`MapResolver.regionOfHex(...).orElse(null)` → `List<RegionId> regions`，透传给 `ApiViews.mapHex` |
| `simos-app/.../webui/panels.js` | `renderHex`：单行 `region` → `regions`，多值 `join("、")`，空显示"无区域" |
| `simos-app/.../webui/map.js` | `selectRegionOfHex`：`body.region` → `Array.isArray(body.regions)`，全部高亮 |
| `simos-map/.../region/RegionIndexTest.java` | 多从属断言；`unknownHexReturnsEmptyList`；**3 区域重叠同一格**（覆盖 >2）+ 两种入参顺序逐值相同；计数夹具改 `List<RegionId>` |
| `simos-map/.../region/RegionIndexGuardTest.java` | L5 计数夹具改 `HashMap<HexCoord,List<RegionId>>`；断言 `containsExactly`/`isEmpty` |
| `simos-map/.../resolve/MapResolverTest.java` | 重叠格 ⇒ `containsExactly(r1,r2)`（沿插入序的线性扫描会给 `[r2,r1]` ⇒ 红）；夹具注释更新 |
| `simos-map/.../GameMapTest.java` | `regionOf` → `containsExactly`；**新增** `regionIndexRebuildsFromRegionsWithMultiOwnership`（从 `regions` 重建 == 逐区域暴力扫描，含 3 区域重叠） |
| `simos-app/.../gui/GuiApiTest.java` | 夹具加 `r-3`（与 `r-1` 重叠于 H11）+ `H14`（无从属）；`region` → `regions`；**新增** `mapHexWithoutRegionGivesEmptyArray`、`mapHexRegionsAreByteIdenticalAcrossTwoCalls`；overview 断言 2→3 区域、3→4 hex |

生产字节（被变异的两处）最终 md5：`RegionIndex.java=5e037c984a048361132856da97169564`、`MapResolver.java=c658f5f81c9ec49f561bd2b6644773cc`。

## 2. 命令逐条实测值

```
./mvnw -q spotless:apply                      # rc=0
./mvnw clean verify                           # rc=0、BUILD SUCCESS、7/7 模块、BugInstance size is 0 ×6、[ERROR] 0 行、[WARNING] 1
```

逐模块用例计数（基线 841 = 170/255/45/131/154/86）：

| 模块 | 基线 | 终态 | delta | 解释 |
|---|---|---|---|---|
| UtilSimos | 170 | 170 | 0 | 未动 |
| MapSimos | 255 | **256** | **+1** | 新增 `GameMapTest.regionIndexRebuildsFromRegionsWithMultiOwnership`（重建逻辑 + 3 区域重叠） |
| SocialSimos | 45 | 45 | 0 | 未动 |
| UnitSimos | 131 | 131 | 0 | 未动 |
| CoreSimos | 154 | 154 | 0 | 未动 |
| SimosApp | 86 | **88** | **+2** | 新增 `GuiApiTest.mapHexWithoutRegionGivesEmptyArray`、`GuiApiTest.mapHexRegionsAreByteIdenticalAcrossTwoCalls` |
| **合计** | **841** | **844** | **+3** | 三个新增用例；既有用例按新语义**原地改造**，数量不变 |

门禁日志：`t1-evidence/logs/full-verify-final.log`（终态）、`logs/full-verify.log`（同字节的首次全量轮，842）。

## 3. e2e（Playwright + 真 `ShellMain`，端口 5931，未碰 5817/5818）

数据：`/tmp/m6-import-verify/test_integration` 的**逐字节副本**（`simos.db` md5 `2348b9365e5b107945a305d06fad8fab`，跑完原件 md5 不变）——用副本是为不污染 M6 证据。
真实重叠：`test_annex_target`(201 格) 与 `test_nation`(701 格) **交集 201 格**；取交集中第一格 `(-18,0)`。

命令⑥原始 JSON 片段（`/api/map/hex?q=-18&r=0`）：

```json
{"q":-18,"r":0,"terrain":"low_hills","height":0.6000000000000001,"regions":["test_annex_target","test_nation"],"terrainType":{"key":"low_hills","name":"低矮丘陵","color":"#A8B36A","minHeight":0.55,"maxHeight":0.65,"food":2,"gold":1,"stone":1,"moveCost":2,"description":"低地与山地的过渡带，产量中等、略难走"},"facets":[]}
```

- 请求 URL：`GET /api/map/hex?q=-18&r=0&branch=main&revision=1`
- 左栏点击后 `#selection-detail` 文本：`q-18r0terrainlow_hills（低矮丘陵）height0.6regionstest_annex_target、test_nation该处单位无人口无序列`（**列出 ≥2 个区域**）
- 逐字节：`c2-two-calls-byte-identical PASS len=299`（先断言 regions 非空 / size=2，再逐字节比对）
- 全步骤：`a-overlap-fixture-present / b-overlap-nonempty(201) / c1-regions-is-array / c1-regions-has-at-least-two / c1-regions-lexicographic / c2-two-calls-byte-identical / c3-hex-on-screen / c3-panel-lists-two-regions / c3-requested-map-hex / c3-no-pageerror` 全 PASS。
- 日志：`logs/e2e-clean.log`；`mut-runs/clean/result.json`。装置：`e2e/run-e2e.sh` + `e2e/e2e.cjs`。

单测另有：多从属解析（`RegionIndexTest` 3 区域重叠、`MapResolverTest` 双区域）、字典序（两种入参顺序逐值相同）、空从属（`unknownHexReturnsEmptyList`、`mapHexWithoutRegionGivesEmptyArray`）、重建（`GameMapTest.regionIndexRebuildsFromRegionsWithMultiOwnership`）。

## 4. 变异表（3 轮，九道门禁全部通过）

装置：`t1-evidence/mut-round.sh`（干净世界基线绿 → 变异体字节不同 → 白名单推成目标类名 + 清陈旧 `.class` → `COMPILATION ERROR`=0 且 `Tests run>=1` → surefire mtime 落轮内 → 红点落被保护断言 → `cp` 逐字节还原 → 日志自指）。**无 `git checkout --`**。

| 轮 | 护栏 | 变异 | 实际红点（为什么红） |
|---|---|---|---|
| m1 | 多值不丢 | `MapResolver.regionOfHex` 只回**第一个** | Java：`MapResolverTest.regionOfHexUsesTheIndex`（`[r1]` vs `[r1,r2]`）。e2e：`c1-regions-has-at-least-two`、`c1-regions-lexicographic`、`c3-panel-lists-two-regions`（`regions=["test_annex_target"]`） |
| m2 | 输出确定 | `RegionIndex.of` **不排序**、按 `HashSet` 迭代序 | Java：`MapResolverTest.regionOfHexUsesTheIndex`（`[r2,r1]` vs `[r1,r2]`）。e2e：**`c1-regions-lexicographic`**（`["test_nation","test_annex_target"]`） |
| m3（附加自证） | 两次逐字节相同 | `regionOf` 每次调用**轮转**列表（引入真·逐调用不确定性） | e2e：`c2-two-calls-byte-identical` FAIL（`md5diff=yes`）。Java：`RegionIndexTest.overlappingRegionsAreAllReportedInIdOrder`、`MapResolverTest.regionOfHexUsesTheIndex` |

轮次日志：`logs/m1.log`、`logs/m2.log`、`logs/m3.log`（含 `orig_md5/mutant_md5/pushed_src_md5/restored_md5` 自指段与 `protected_assertion_hits>=1`）。三次 `restored_md5 == orig_md5`。

**★ 与派单矛盾处（以源码为准，指出写错的地方）**：
- 派单 §5 说 m2 的期望红是「两次逐字节相同」。**源码/实测否定之**：同一进程内 `HashSet` 的迭代序是内容的确定函数，两次调用**逐字节相同**（m2 实测 `c2-two-calls-byte-identical PASS`）。真正能杀"不排序"的是**字典序断言**（`c1-regions-lexicographic`）与 Java 的保序断言。为证明 `c2` 不是装饰，追加 **m3**（逐调用轮转）把 `c2` 真杀（`md5diff=yes`）。
- 派单 §2.4 提到「M5 的 `ApiViews`/`ApiViewsTest`」。**仓里不存在 `ApiViewsTest`**；`/api/map/hex` 的既有断言在 `simos-app/.../gui/GuiApiTest`（已按新语义改）。
- 派单 §2.2「逐个检查所有调用点」：`MapResolver.regionOfHex` 生产调用点只有 `GuiServer.mapHexReply`（其余命中全在 `.superpowers/**` 历史变异体与测试）。MCP 读工具 `simos.map.hex`（`MapHexTool`）**从不输出 region**，不在本次范围，未动。

## 5. 我未能核实的

1. **m1/m2/m3 的变异轮跑在"新增那两个覆盖用例之前"的测试集上**（生产字节三处 md5 与轮内 `orig` 备份逐字节相同，见 §1；杀红点是未改动的 `MapResolverTest` 与 e2e，新增用例不影响结论）。未在终态测试集上重跑三处 Maven 轮。
2. e2e 用的是真档的**逐字节副本**（原件 md5 跑前/runs 后均 `2348b936…`，未变），不是字面上的原路径——为不污染 M6 证据。原路径的 `shell.log` 证明 M6 当时确曾直跑。
3. 真实数据里 701/201 的交集恰好是 2 个区域；**端点侧的 >2 从属只用合成夹具证过**（`RegionIndexTest` 3 区域、`GameMapTest` 3 区域），未在端点/浏览器上跑 >2。
4. 前端 JS 仍**不进 CI**（M8 T2 才接 `node --test`）；本次前端只由 Playwright e2e + 人读验证。
5. 未在并发（同一 revision 多线程）下核 `regions` 输出；索引每请求重算、纯函数，理论确定。

## 6. 证据索引

- `logs/full-verify-final.log`（终态全量，844）、`logs/full-verify.log`（842）
- `logs/e2e-clean.log`、`mut-runs/clean/result.json`
- `e2e/e2e.cjs`、`e2e/run-e2e.sh`、`mut-round.sh`
- `mutants/orig/{MapResolver,RegionIndex}.java`、`mutants/m1/MapResolver.java`、`mutants/m2/RegionIndex.java`、`mutants/m3/RegionIndex.java`
- `logs/m1.log`、`logs/m2.log`、`logs/m3.log`、`logs/m*.server.log`、`mut-runs/m1..3/result.json`
