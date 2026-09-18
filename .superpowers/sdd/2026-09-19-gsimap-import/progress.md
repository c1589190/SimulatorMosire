# M6 台账 —— GSimap 导入器（py 迁移脚本）

> 阶段：M6。判据（总纲 §十一）：**旧 `*_map.json` 能转成 simos 数据集**。
> 依赖：M2（已完成）。总纲 §5.1 原话：**「GSimap 导入是独立 CLI 脚本，不是公开 API」**。

## 〇 阶段定位（**用户改向**，2026-09-19）

控制器最初按 M5 先例把 M6 当"Java 模块 + spec + bite-sized 计划 + 变异自证"推进（还派了两个取证代理、
起草了 S1~S10 裁定表）。**用户当场改向**，原话：

> 「我更好奇的是你想怎么转换，我原本想的是，**就把数据迁移一下就行，写个 py 脚本，而不是程序化**」

⇒ **M6 不再走 Java 五步流水线**。交付物 = **一个 Python 一次性迁移脚本** + 运行证据 + 判据实测值。
**不动任何 Java/Maven 文件、不新建模块、不写 Java 测试。** 总纲那句"独立 CLI 脚本"按字面兑现。

## 一 用户裁定（M6-U1~U3）

| # | 项 | 裁定 | 备注 |
|---|---|---|---|
| **U1** | 入口形态 | ~~`ShellMain --import` 子命令~~ → **由 U3 取代**：入口就是 py 脚本本身 | U1 原选"ShellMain 加 --import"，是"若走程序化"前提下的选择；U3 改向为脚本后该入口不需要了 |
| **U2** | `height` 缺失 + 未知地形 | **显式映射表 + 未知地形报错**（fail-closed） | 旧 hex 无 `height`、旧 `terrainTypes` 无 `minHeight/maxHeight`，而 simos `HexCell`/`TerrainType` 两者都强制 |
| **U3** | 夹具/实现形态 | **写 py 脚本做一次性数据迁移**，不做程序化 | 见上"阶段定位" |

## 二 关键取证结论（决定脚本形态的硬事实）

1. **落盘格式可 py 复刻**：`Replay` 的读路径是 **Jackson 反序列化 + record 构造器校验**，不是字节比较
   ⇒ py 只需产出 **schema 精确 + 语义合法** 的 JSON。唯一必须逐字复刻的 Jackson 内省产物是一处固定字面量：
   `changeset_json` = `{"@class":"io.mosire.simos.core.state.WorldChangeSet","modules":{}}`。
   ★ 反例（**不许出现**）：`empty` 属性（严格读侧抛 `UnrecognizedPropertyException`）。
2. **最小磁盘集合** = `revisions` 一行 + `checkpoints/main/1.json`；**`events` 表可为空**（`Replay` 不读事件）。
3. **`Region.boundary` 是硬校验**：`Region` 构造器要求 `boundary.equals(RegionBoundary.of(hexes))`。
   但 `RegionBoundary` 的构造器**把每环规范化（旋到最小顶点 + 正反向取较小者）并按首顶点排序环表**，
   注释自陈"**纯函数：同集合必得同结果，与迭代序无关**" ⇒ **JVM 哈希序进不了盘，py 可精确复刻**（`HexVertex.at` 是整数标签表）。
4. **必须补空的 `social`/`unit` 切片**，否则导入后发不了 unit 命令（`applyWorld` 抛"模块不在当前状态里"）。
5. **信封的 `ref` 是扁平的、module payload 的 `ref` 是嵌套的**（`{"branch":{"value":"main"},...}`）；
   `modules` 的值是**字符串**、`info` 是**对象**。`Optional` 走 jdk8 ⇒ `calendarLabel` 是 `null`。
6. **旧数据地形只有 6 个 key 真被用到**（9 个世界实测）：`lowland/plains/water/hills/mountain` 全用 +
   `swamp`（1/15/29/118 格）；`desert/forest/tundra` **声明但从不使用**。
7. **`TerrainCatalog` 注释写明 U1（M2 期）已把 GSimulator 那份 9 项表整个作废** ⇒ **不搬旧属性**，只做 key 映射、属性整套取 simos 的。
8. `provinces` **非空**的世界有 3 个（default 2 条 / f3_qa 4 条 / test_integration 2 条）⇒ 第 3 条不是空谈。
9. `_map.json` 这个文件名**同时承载 full map 与 `MapDiff`**（实测 GoatMosire 有 `_map.json` 其实是 diff）
   ⇒ 脚本必须**结构判据**（含 `parentNodeId`/`changed` ⇒ 报错），不能按文件名信任。
10. 真档**不能入库**（`worlds/` 被 gitignore，且最大一份 4.3MB）⇒ 验收在本机跑真文件，仓库里只放脚本。

## 三 映射表（脚本的硬规则）

**旧 key → simos key**（属性整套取 `TerrainCatalog.defaults()`）：
```
water    -> ocean
lowland  -> plains
plains   -> plains
desert   -> desert
hills    -> low_hills
mountain -> mountains
swamp    -> plains        # ★ lossy：simos 词表是纯高度带的，没有湿地 ⇒ 报告里逐条打印 lossy 计数
```
`forest` / `tundra` **故意不进表** ⇒ 一旦某个世界用到就**报错退出**（不编造语义）。

**`height`** = 所映射到的 simos 地形**高度带中点**（确定性；如 plains → 0.375）。

**丢弃（各打印理由）**：`compressedRegions`（纯渲染缓存）/ `rivers`+`roads`（旧仓 @Deprecated，被 edges+pathwayGroups 取代）/
`terrainBlocks`（旧仓 @Deprecated，hex 上的 terrain 才是权威）/ `gridSize`（simos 无对应）。
`hexOrientation` 丢弃但**必须校验 `false`**（simos 方向表假定 flat-top）。

**fail-closed（非空即报错）**：`cities` / 任一 hex 的 `edgeTags` 非空或 `riverMask` 非 0（连通性三份表示，融合优先级未裁决）/ 顶层是 diff。

**直映（绝不丢）**：`edges`（★ 旧仓有"重建时静默清空 edges"的 bug，必须显式透传）/ `pathwayGroups`（`properties` 可有可无）。

## 四 任务

| # | 任务 | 状态 |
|---|---|---|
| T1 | 写 `tools/gsimap_import.py`（标准库 only）+ 跑真文件 + 用现成 Java 读路径验收 | ✅ 601 行；2 个真文件 ALL PASS；7 条 fail-closed 全部按预期拒绝 |
| T2 | M6 关账：判据实测值 + 弃键理由表 + 开口项 + `CLAUDE.md` M6 行 + 推送 | ✅ 见下 |

---

## 六 M6 关账（2026-09-19）

**判据**（总纲 §十一）：**旧 `*_map.json` 能转成 simos 数据集**。**实测闭合**：

### 6.1 判据实测值（**不是"通过"，是值**）

对 `demo/n0000_map.json` 与 `test_integration/n0000_map.json` 各跑一轮，**逐字段对拍 ALL PASS**：

| 对拍项 | test_integration 实测 |
|---|---|
| `revisions` 行数 / 主键 / parent 两列 | `1` / `(main,1)` / `(None,None)` |
| `command_type` / `initiator` | `core.Bootstrap` / `system:bootstrap` |
| `changeset_json` | `{"@class":"io.mosire.simos.core.state.WorldChangeSet","modules":{}}`（与**真样本逐字节相同**） |
| 信封 `ref` / `timestamp` / `modules` 键 / `info` | `{"branch":"main","revision":1}` / `{"tick":0,"calendarLabel":null}` / `[map, social, unit]` / `{"bySubject":{}}` |
| hex 总数 | `19441` |
| terrain 直方图 | `plains 10719 / ocean 4506 / low_hills 3330 / mountains 886`（★ `10719 = 5559+5042+118` ⇒ `swamp→plains` 的 lossy 合并算术**当场对上**） |
| `terrainTypes` 键集 | `[ocean, plains, low_hills, mountains]`（**只输出在用者**，声明未用的 `desert/forest/tundra` 不输出） |
| `provinces` 数 | `2` |
| region `test_annex_target` | hexCount `201` / 环数 `1` / 环长=暴露边数 `100` |
| region `test_nation` | hexCount `701` / 环数 `1` / 环长=暴露边数 `246` |
| `pathwayGroups` 键集 | `[river, road]` |
| `edges` 条数 | `0` |
| `social`/`unit` 空切片 | `{}` / `{}` |

**★ 对拍证明不了的那一环，由 Java 真读路径证明**：`ShellMain --store /tmp/m6-import-verify/test_integration`（**不给 `--demo`**）
成功装配（`mapId=Map1`、codec=3、**未覆盖**导入档），`/api/map/overview` 返回 `hexCount=19441` 与真实 hex
（`height=0.375` / `0.6000000000000001`），**日志零异常**。⇒ 这些值是经 `SqliteStore → Replay((main,1)) → decodeSnapshot`
出来的，即 **`Region` 的硬校验 `boundary.equals(RegionBoundary.of(hexes))` 接受了 py 算出的边界** ⇒
**环算法移植成功**（这是本里程碑唯一"不是搬字段"的活）。

### 6.2 fail-closed 实测（7 条，全部按预期拒绝且 `rc=2`）

| 触发 | 拒绝理由（原文） |
|---|---|
| 真 `MapDiff` 文件 | 顶层含 `parentNodeId`/`changed` ⇒ 这是 MapDiff 不是 full map |
| `forest` 在用（合成） | 地形 key 不在显式映射表内：`forest=1 格` |
| `tundra` 在用（合成） | 同上：`tundra=1 格` |
| 未知 key `volcano`（合成） | 同上：`volcano=1 格` |
| `cities` 非空（合成） | `cities 非空（1 座）`——映射规则未裁决，不猜 |
| `hex.edgeTags` 非空（合成） | 连通性三份表示、融合优先级未裁决，不静默丢 |
| `hex.riverMask` 非 0（合成） | 同上 |
| `hexOrientation=true`（合成） | simos 的方向表假定 flat-top |

### 6.3 交付物

- `tools/gsimap_import.py`（**601 行**，Python 标准库 only；**零 Java/Maven 改动、无新模块**）。用法：`gsimap_import.py <旧 map.json> <输出目录>`。
- **门禁**：主树 `./mvnw clean verify` **绿**（rc=0、**825** 条 = 170/255/45/131/153/71、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0；日志 `/tmp/m6-final-verify.log`）。——`tools/` **不入 Maven reactor**，故门禁与 M5 关账**同值**；这是本项目第一次在模块之外新增文件，故仍跑了一次确认。
- `/tmp/m6-import-verify/` 下：两个真目录的产物 + `import.log`/`shell.log`/`fielddiff.log`/`overview.json` + `failclosed-evidence.log` + 6 个合成夹具。

### 6.4 我未能核实的

- **`sun.nio`/平台差异**：只在**本机**跑过（Linux + 本机 Python）；换机未验。
- **`generation` 分片**：`GenerationSpec` 是**合成占位**（旧档没有该概念），未与任何"真" spec 对拍。
- **`forest`/`tundra`**：**0 个真实世界用到**，故它们的 fail-closed 只用**合成夹具**证过。
- **多环（洞）区域**：真实 provinces 都是单环（各 1 环）；**带洞的区域没有真实样本**，环表排序那条路径未被真实数据压到。
- **`edges` 非空**：真实 full map 里只有 `mcp_smoke_test` 有 1 条；脚本能直映，但**未拿它跑过端到端**（那轮跑的是 demo 与 test_integration，两者 `edges` 均为空）。
- **`swamp→plains` 的语义损失**：算术对上了，但"湿地"这个语义在 simos 里**确实丢了**（词表是纯高度带的）。


## 五 开口项（预期，关账时确认）

- `Region.boundary` 算法已在 py 里移植（**若**代理确认可行）；若不可行则记"provinces 不导入"的替代裁定。
- `GenerationSpec` 是**合成占位**（旧档没有）。
- `forest`/`tundra` 未映射 ⇒ 若未来世界用到会 fail-closed（**当前 0 个世界用到**）。
- 连通性三份表示（`riverMask`/`edgeTags`/`edges`）的融合优先级**未裁决** ⇒ 有非空即报错。
- `simos.db` 的 DDL 由脚本内联（与 `SqliteStore` 同源）；**Core 改 DDL 时脚本会失配**（无编译期护栏）⇒ 脚本自检要能看见这条。
