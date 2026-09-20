# T8 报告：战损增量 `unit.ApplyCasualties`（E4 / N3 / P14）

> 时间盒内的交付：**实现 + 9 轮变异（8 个变异体）+ 模块门禁 + 报告**。
> 分支 `ue/t8`，工作树 `.claude/worktrees/uet8`，基线 `851e61b`。
> 本报告只写**实测到的**东西；没做到的一律进 §五 诚实清单。

---

## §〇 落地清单 + 字节

| 类别 | 文件 | 角色 |
| --- | --- | --- |
| 新增 main | `simos-unit/src/main/java/io/mosire/simos/unit/spi/ApplyCasualtiesHandler.java` | `unit.ApplyCasualties` 命令边界 |
| 修改 main | `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java` | `applyCasualties`（+72 行，**0 删除**） |
| 修改 test | `simos-unit/src/test/java/io/mosire/simos/unit/ops/UnitOperationsTest.java` | +5 条（+107 行，**0 删除**） |
| 修改 test | `simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java` | +6 条（+188 行，**0 删除**） |
| 新增 test | `simos-core/src/test/java/io/mosire/simos/core/UnitCasualtyRevisionTest.java` | 真 `CommandBus` + 真 store（3 条） |
| 证据 | `.superpowers/sdd/2026-09-20-unit-extension/t8-evidence/` | 本报告 + `mutants/`（装置、变异体、逐轮日志） |

★ **三份被改的文件的 diff 全是纯增行（`--numstat` = `72/0`、`107/0`、`188/0`）**：既有护栏**一个字节都没动**
⇒ T5 / T7 的旧证据**不作废**，裁定 42 的"新增护栏自带变异轮"由 `t8m5` 满足。

md5（`mutants/baseline-md5.txt` = 变异轮锚定的那份；`spotless:apply` 之后只有 `UnitOperations.java` 的**注释折行**变了）：

| 文件 | 变异轮基线 md5 | 现状 md5 |
| --- | --- | --- |
| `UnitOperations.java` | `eadbe492af290252dbe333178dee0d3d` | `4788d99ffc7e81f8af302c6041ab5288` |
| `ApplyCasualtiesHandler.java` | `1d6b30a0d9d30ce38b5f980fdecc08b9` | 同左（未变） |
| `CommandBus.java`（m7 靶，只借来变异、未改） | `23a07b24ff9ff97dd1ad589e5ff6a67c` | 同左（未变） |

★ 那处 md5 差已用**注释剥离后的代码面比对**证成"零语义改动"：`mutants/spotless-code-identity.txt`（两份文件均 `IDENTICAL(代码面)`）。

---

## §一 逐判据实测值

| 判据 | 实测 | 判它的用例 |
| --- | --- | --- |
| **m1** `100 + (−30) = 70`（不是 30、不是覆写） | `70`；装备 `步枪 50 + (−10) = 40` | `UnitOperationsTest.applyCasualtiesSubtractsFromTheCurrentValueInsteadOfOverwriting`、`UnitCommandHandlersTest.applyCasualtiesSubtractsIncrementallyAndKeepsUnmentionedEquipment` |
| **m2** `Δ=−101`（当前 100）⇒ 拒 | `IllegalArgumentException("人员战损超出当前值: 100 + (-101)")`；命令边界折成 `Rejected`，**`revisions` 行数仍是 1**（三条拒绝全不落 revision） | `UnitOperationsTest.applyCasualtiesRejectsPositiveDeltasAndOutOfRangeAmounts`、`UnitCasualtyRevisionTest.rejectedCasualtyLeavesTheRevisionTableUntouched`；正 Δ 亦拒（人员 / 装备各自） |
| **m3** 装备双轨：只扣提及键 | base `{步枪:50, 炮:4}`，Δ `{步枪:−10}` ⇒ `{步枪:40, 炮:4}` | `UnitOperationsTest.applyCasualtiesLeavesUnmentionedEquipmentKeysUntouched` 等 2 条 |
| **m4** 未知装备键 ⇒ 拒（P14，不视作 0） | `Rejected("未知装备键: 坦克")`；**Δ=0 的未知键一样拒** | `UnitOperationsTest.applyCasualtiesRejectsUnknownEquipmentKeys` 等 2 条 |
| **m5** 时间线恢复：回退前一 revision ⇒ 战前值 | R1 = `member 100 / {步枪 50, 炮 4}`（战前），R2 = `70 / {步枪 40, 炮 4}`（战损后）；R2 的 `parent = R1`、`commandType = unit.ApplyCasualties`；落盘的是**变更集**（不含 `personnel`、不含 `-30`） | `UnitCasualtyRevisionTest.casualtyLandsAsARealRevisionAndRollsBackToThePreBattleValue` |
| **m6** `received` 事件无明文 delta | 载荷 = `{"commandId","expectedRevision","branch","type","payloadDigest":"sha256:<32 hex>"}`；断言**不含** `personnel` / `equipment` / `-30` / `-10` / `步枪` | `UnitCasualtyRevisionTest.receivedEventCarriesOnlyTheDigestNotTheDeltaPlaintext` |
| **T5-L4**（本轮新路径自证） | 链经命令边界的变更集往返**逐值**活下来（`applyCasualties` → `withUnit` → `state.withUnits`） | `UnitOperationsTest.applyCasualtiesKeepsTheCommandChains`、`UnitCommandHandlersTest.applyCasualtiesKeepsCommandChains` |
| 载荷形状（拒绝路径） | 非 JSON / 非对象 / 缺 `id`·`personnel`·`equipment` / 值非整数 / 装备非对象 ⇒ 一律 `Rejected`（不逃逸异常） | `UnitCommandHandlersTest.applyCasualtiesRejectsMalformedPayload` |

★ 一条执行期观察（不是判据）：`received` 的 `payloadDigest` 形状是 **`sha256:` + 32 位十六进制**（sha256 前 16 字节，裁定 45），不是 64 位裸十六进制——首轮我按 64 位写，被自己的用例当场判红（`/tmp/t8-targeted.log`），按实测形状改正。

---

## §二 变异轮（装置十道门禁，照 T7 同法）

装置：`mutants/mut-round.sh`（十道门禁：基线 md5 锚定 / 变异体字节不同 / 白名单 / 源树无残留 .java / `COMPILATION ERROR=0` /
报告 mtime 落在本轮 + 汇总行非空 / 失败清单原文留全 / **逐字节 cp 还原** + md5 复测 / 日志自指四 md5 / **⑩ 按片段+方向自证**）
+ `mutants/manifest.txt` + `mutants/make-mutants.py`（精确字面量替换，needle 必须恰出现 1 次）。
选择器 = `UnitOperationsTest,UnitCommandHandlersTest,UnitCasualtyRevisionTest`，`-pl simos-unit,simos-core -am`。

| 轮 | 靶 | 变异 | ⑩ 自证 | rc | 红点（原文） | 判定 |
| --- | --- | --- | --- | --- | --- | --- |
| `t8m1` | `UnitOperations` | Δ 当**裸值**（`unit.member() + personnelDelta` → `personnelDelta`） | delete：orig 1 / pushed 0 | 1 | 7 条失败，含 `…SubtractsFromTheCurrentValueInsteadOfOverwriting`（红为异常 `member 必须 ≥ 0: -30`） | **杀** |
| `t8m1b` | 同上 | Δ 当**绝对值**（→ `Math.abs(personnelDelta)`，得**合法值 30**） | delete：orig 1 / pushed 0 | 1 | 7 条，红点落在**值断言**：`[★ 100 + (−30) = 70（不是 30、不是覆写）]` | **杀** |
| `t8m2` | 同上 | 删人员上界守卫 | delete：orig 1 / pushed 0 | 1 | 2 条：`[★ 100 + (−101) 越界 ⇒ 拒…]`（真正红的是**理由文本**，见 §五.2） | **杀** |
| `t8m3` | 同上 | 装备整表 `clear()` | revert：orig 0 / pushed 1 | 1 | 2 条：`[★ 未提及键不变（整表替换会丢它）]` | **杀** |
| `t8m4` | 同上 | 未知键 `continue` 忽略 | revert：orig 0 / pushed 1 | 1 | 2 条：`applyCasualtiesRejectsUnknownEquipmentKeys`（打印里 `member=99`、未知键被静默吞掉） | **杀** |
| `t8m5` | 同上 | **T5-L4**：`withUnit` 改回 `new UnitState(next)` | revert：orig 0 / pushed 1 | 1 | 5 条，含**本轮新护栏**：`applyCasualtiesKeepsTheCommandChains`（op + handler 两侧） | **杀** |
| `t8m6` | `ApplyCasualtiesHandler` | 变更集恒空（`between(base, base)`）⇒ 战损不落 revision | revert：orig 0 / pushed 1 | 1 | 3 条（**unit 模块内**）：`…KeepsCommandChains [前提：战损真的发生了]` 等；★ **反应堆在 simos-unit 先红后收工，simos-core 根本没跑**（见 §五.1） | **杀** |
| `t8m6b` | 同上（**同一个变异体**，只补 `-Dmaven.test.failure.ignore=true`） | 同上 | 同上 | 0※ | 4 条，含 **core 侧被保护断言**：`UnitCasualtyRevisionTest.casualtyLandsAsARealRevisionAndRollsBackToThePreBattleValue:99 [R2：100 + (−30) = 70]` | **杀** |
| `t8m7` | `CommandBus`（m6 判据的实现面） | `received` 载荷加 `"payloadPlain": envelope.payloadJson()` | revert：orig 0 / pushed 1 | 0※ | 1 条：`receivedEventCarriesOnlyTheDigestNotTheDeltaPlaintext:217 [★ m6：delta 的字段名不进事件载荷]` | **杀** |

※ `t8m6b` / `t8m7` 带 `-Dmaven.test.failure.ignore=true`（**只为**让反应堆跑到 simos-core）⇒ `mvn` 返回 rc=0，
**这两轮不以 rc 判杀**，以日志里的失败清单 + 两模块汇总行为准（该标志已逐轮写进日志的 `extra_flags` 行）。t8m7 的 simos-unit 侧全绿、core 侧 1 红。

**汇总：8 个变异体 / 9 轮次，全部被杀，存活项 0**；每轮 43~66 s；每轮 `⑧ restored==orig: yes`（逐字节还原）。

---

## §三 模块门禁与计数

`./mvnw -o -pl simos-unit,simos-core -am verify`（前台）⇒ **rc = 0**，`COMPILATION ERROR = 0`，`spotless:check` 通过。

| 模块 | 用例数 | 变化 |
| --- | --- | --- |
| `unit`（UnitSimos） | **257** | 246 → **257**（**+11**：op 层 5 条 + handler 层 6 条） |
| `core`（CoreSimos） | **177** | 174 → **177**（**+3**：`UnitCasualtyRevisionTest`） |
| 其余（父 pom / util 362 / map 170 / social 45） | 未变 | 只跑不碰 |
| 前端 88/88 | 未碰 | — |

★ 首轮门禁 **rc=1**（不是测试红，是 `spotless:check` 判我新增的 Javadoc 折行不合格式）⇒ 跑 `spotless:apply` 后复跑得 rc=0。
这一步**只动注释**，已用代码面比对上文自证。

---

## §四 纪律落实

- **裁定 42**：新护栏（`applyCasualtiesKeepsTheCommandChains` / `applyCasualtiesKeepsCommandChains`）**自带** `t8m5` 轮；
  既有护栏（`everyWithUnitRoutedOperationKeepsTheChains`、`planSparseRouteKeepsTheCommandChains`、`setRejoinTargetKeepsCommandChains`）
  **一个字节未动**（测试文件 diff 删除行 = 0），故 T5/T7 旧证据有效。
- **T5-L4**：`applyCasualties` 走 `copy` → `withUnit` → `state.withUnits(...)`；变异体 `t8m5` 把该助手改回 `new UnitState(next)` 被杀。
- **T5-L5/T6 教训（逐片段自证）**：⑩ 门禁按 manifest 的 `frag` + 方向判，**只数代码片段**，不按整份文件数词；
  9 轮的 `orig_hits/pushed_hits` 逐轮写在日志里。
- **密钥纪律**：全程无密钥读取；载荷里只有单位 id / 数值，无凭据；日志无 argv/env 泄漏（只记 mvn 参数与计数）。
- **路径纪律**：只在 `.claude/worktrees/uet8` 里改代码；主检出未动（`git -C /home/dev/SimulatorMosire status` 里本轮无我的文件）。
- **不许改 `pom.xml`**：未改（见 §三 的 diff 证据：`git diff --numstat` 只有三个文件）。
- 提交信息不含任何自己的哈希、无 trailer。

---

## §五 诚实清单（写下来的都是真实观察，不是"应该没问题"）

1. **`t8m6` 首轮的反应堆短路**：simos-unit 的测试先红 ⇒ Maven 在到 simos-core 之前停下 ⇒ **core 侧那条被保护断言根本没跑**。
   我**没有**把那轮当成"core 判据被杀"，而是补了 `t8m6b`（同变异体 + `-Dmaven.test.failure.ignore=true`）把 core 侧的红点取到。
   两轮**都留在档**（`t8m6.log` / `t8m6b.log`），没有删任何一轮。
2. **`t8m2` 的红是"理由文本"，不是"值越界"**：`Unit` 构造期**也**把守 `member ≥ 0`（设计如此，"两道不重复实现"），
   所以删掉我的上界守卫**不会**让负数落盘——变异体仍被拒，只是理由变成 `member 必须 ≥ 0: -1`。
   ⇒ 实测结论：**上界判据的判别力落在"可读的领域理由"上**（`人员战损超出当前值`），"值不变"那半由构造期独立保证。
   这条是"判据比行为强"的实例，如实记在这里；它的对照变异体就是 `t8m2` 本身。
3. **`t8m1` 的红是异常、不是值断言**：裸值形态让 `member = −30`，`Unit` 构造期当场抛 ⇒ 用例红为异常。
   值形态的判别力由 `t8m1b`（`Math.abs` ⇒ **合法值 30**）单独证明，红点确实落在 `isEqualTo(70)` 那条上。
4. **`spotless` 与变异轮基线之间的 md5 差**：见 §〇。已用注释剥离比对证成零语义改动（`spotless-code-identity.txt`）。
   ★ 装置下一次跑（若要复跑）应把 baseline 换成现状 md5 `4788d99f…`，否则 ① 门禁会（正确地）拒绝。
5. **m5 的"覆写历史"没有在**我这条路径**上被真正构造出来**：handler 是纯函数，**无法**改写历史；
   我用"变更集恒空 ⇒ 战损不落 revision"作为**射程内**的等价形态（`t8m6`/`t8m6b`，core 侧红点命中 `R2 ⇒ 70`）。
   真·覆写历史的变异体会落在 `Timeline`/`CommandBus` 的落盘路径上，本轮**没有**跑（见 §七）。
6. **`t8m5` 的连带面**：T5-L4 变异体改的是**共享**助手 `withUnit`，所以它同时打红了 T5/T7 时代的三条既有护栏
   （`everyWithUnitRoutedOperationKeepsTheChains`、`planSparseRouteKeepsTheCommandChains`、`setRejoinTargetKeepsCommandChains`）。
   这**不影响** T5/T7 的证据（它们的字节没动），但说明该变异体是"面"而非"点"上的——如实记录。
7. **载荷的两个"必填"选择**：`personnel` 与 `equipment` 都**必填**（缺失 ⇒ 拒）。理由：spec §五.2 的形状
   `{ id, personnel: int(≤0), equipment: {键: int(≤0)} }` 与计划 T8 的 `id, personnel, equipment{}` 都没写 `?`；
   对照 T7 的 `target?` 是**显式**写了 `?` 的。若控制器判 `equipment` 应可选（缺省 = 不扣装备），这是一个**语义缺口**，
   见 §六。**我没有自己改 spec/计划**。

---

## §六 待控制器裁的缺口

| # | 缺口 | 本实现的选择 | 影响面 |
| --- | --- | --- | --- |
| G1 | `equipment` 是否可选 | 必填（缺失 ⇒ 拒） | 若应可选：`{"id","personnel"}` 这种"只扣人"的信封现在会被拒；改动点仅在 handler 的两行 |
| G2 | `personnel` 是否可选 | 必填 | 同上 |
| G3 | m5 判据的"覆写历史"形态 | 射程内用"变更集恒空"代理 | 若控制器要"真覆写"的变异轮，需把 `Timeline`/`CommandBus` 纳入白名单，另跑一轮 |
| G4 | 计划里 `169 + K` 与实测 `174 + K` | 报告一律用**实测**（core 174 → 177） | 只是文字口径，不影响行为 |
| G5 | 计划 §T8 第 6 步"正 Δ / 缺失字段 / 非 JSON ⇒ 拒" | 已逐条覆盖；★ 另外**未在 core 侧**复验这三条（域层 + unit 侧已判） | 若要求端到端也判，需再加 1 条 core 用例 |

---

## §七 未验证项 / 未做的事

1. **前端 88/88** 未跑（本轮只碰 `simos-unit` / `simos-core`，按纪律不跑前端；计数按"未变"记）。
2. **全量 `clean verify`** 未跑（不属我的门禁；`simos-app` 未编译、未改动）。
3. **T9（SPI 装配）**：`Shell` 未注册 `ApplyCasualtiesHandler`，`catalog` 未含 `unit.ApplyCasualties`——按计划那是 T9 的活，
   本轮**没有**越界去做（所以端到端跑一个真 `Shell` 的场景在本轮**未验证**）。
4. **m5 的"真覆写历史"变异体**（见 §五.5）未跑。
5. **`-Dmaven.test.failure.ignore=true` 的两轮**（`t8m6b`/`t8m7`）里，simos-unit 侧的全绿只说明"该变异体在 unit 侧不可见"，
   不代表该标志本身无害——门禁（§三）是**不带**该标志跑出来的。

---

## §八 装置与证据文件

```
t8-evidence/
├── t8-report.md                 ← 本文件
└── mutants/
    ├── mut-round.sh             ← 十道门禁装置（EXTRA 参数见 §五.1）
    ├── manifest.txt             ← 9 轮的 label/target/mutant/frag/frag_dir/expect_red
    ├── make-mutants.py          ← 精确字面量变异体生成器（needle 必须恰 1 次）
    ├── baseline-md5.txt         ← 变异轮锚定（格式化前）
    ├── strip-compare.py         ← 注释剥离代码面比对（spotless 的零语义证明）
    ├── spotless-code-identity.txt
    ├── pre-spotless_*.java      ← 变异轮基线两份原件（留档）
    ├── t8m*_*.java              ← 8 个变异体
    └── t8m{1,1b,2,3,4,5,6,6b,7}.log  ← 9 轮日志（含 ⑨ 自指四 md5 与 ⑦ 失败清单原文）
```
