# M3 Task 5 派单说明（控制器派单前扫描后）

**任务**：`SocialResolver` 的 `social:` 寻址 + R12/R13 的 social 半
**BASE**：`e83a439`（Task 4 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 1248~1560 行**（Task 5 全文）；M3 spec §3.5、§六 R12/R13；`MapResolver`（模板，`simos-map/src/main/.../resolve/MapResolver.java`）+ `MapResolverTest`（夹具形制）。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-5-a（API 已逐条核验）**：`Resolver{namespace,resolve}` / `ResolveContext(state,at)` / `SimulationState(StateMeta,Map,InfoSystem).module(String)` / `StateMeta` / `InMemoryInfoSystem.empty()` / `Entity.of(name)|of(kind,name)|kind()|name()` / `Index.coords()` / `Namespace` / `SubjectId.localId()` / `ResolvedSubject(id,canonicalAddress,typeName)` / `QueryResult.candidates()`——全与计划吻合。★ `Address` 构造期**强制 ≥2 段**（实测 `Address.java:16`）⇒ 计划里裸用 `segments.get(1)` **安全**（与 MapResolver 同款），不加多余守卫。
- **R-5-b（★ m2 需要靶子用例，必补）** 计划 m2 = 把 `single(...)` 的 canonical 换成手写拼接。**现状用例全都用 mapId=`Map1`（不含 `:`）⇒ 手写拼接与 AST 输出逐字相同 ⇒ m2 会存活**（白做）。按计划自己的提示（"若现有用例抓不到，当场补一条含 `:` 的 mapId 用例"）**必补**：
  ```java
  @Test
  void colonMapIdKeepsQuotesInCanonical() {
    // 根形式与 hex 形式各一条断言；social:\"m:1\" 解析回来段 2 是 Entity(∅,"m:1")
    // AST canonical ⇒ social:"m:1" / social:"m:1":hex.0_0；手写拼接 ⇒ 丢引号 ⇒ 红
  }
  ```
  用 `Address.parse("social:\"m:1\":hex.0_0")`（同一 fixtures 即可——social 的 mapId **只回显不校验**）。断言**两条**：`social:"m:1"` 根与 `social:"m:1":hex.0_0`。补进 `SocialResolverTest`。
  ⚠️ m2 的**期望红点**：若它同时打掉根与 hex 两条断言，如实列两条（同根因）。
- **R-5-c（期望数字）**：加实现前编译失败（唯一一次红=编译错）；加实现后 **9/9**（计划 8 + R-5-b 的 1）；三轮改前基线 = util 156 / map 248 / **social 21**；每轮 `COMPILATION ERROR count = 0`。
- **R-5-d（装置）**：复用 `task-4-evidence/{run.sh,mutate.py}` 拷到 `task-5-evidence/`：`ROUNDS_DIR`、`TARGET`（m1/m2/m3）、surefire 明说目标 = `SocialResolverTest`；`-pl simos-social -am`、manifest 范围（util+map+social）**不变**。
- **R-5-e（m1/m3 形态）**：m1 = 删 `resolveHex` 的 `containsKey` 判断 ⇒ `absentHexIsAnEmptyCandidateNotAnError` 红；m3 = `dataOf` 的 `orElseThrow` 改返回空切片 **或** 删 `instanceof` 类型判断（二选一，写明选哪个）⇒ `missingSliceThrows` / `wrongSliceTypeThrows` 相应红。同根因连带可接受、必须全列实测红点。
- **R-5-f（形制纪律）**：`dataOf(ctx)` 在**段形状判定之前**调用（认领的 `social:` 地址一律先过装配故障关，连不服务形状也不例外；非 social 命名空间完全不碰 ctx）——与 MapResolver 同款，已在计划 Javadoc 写明；canonical 只由 AST `canonical()` 产出；非 social 命名空间返回空候选（不抛）。

**执行顺序**：照计划 Step 1~7：先读模板 → 写用例（含 R-5-b）→ 编译失败 → 实现 → 9/9 → `spotless:apply` **先于**实验室 → 三轮变异 → 提交。

**提交**（两组；先 `git diff --cached --stat`，绝不 `git add -A`，不加 Co-Authored-By）：
- A：`git add simos-social/src/main/java/io/mosire/simos/social/resolve/SocialResolver.java simos-social/src/test/java/io/mosire/simos/social/resolve/SocialResolverTest.java`；信息 `feat(social): SocialResolver 的 social: 寻址 + 空候选/抛分工守卫（M3 Task 5）`
- B：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-5-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-5-evidence/`；信息 `docs(sdd): M3 Task 5 报告 + 变异实验室证据入库`

**报告 `task-5-report.md` 要点**：① 交付面；② R-5-a~f 逐条落点；③ 三轮变异自证头 + 实测红点全列 + **m2 的靶子说明**（R-5-b 用例如何给出判别力）；④ 门禁数字；⑤ 关切/未能核实清单。

**MUST NOT**：不重实现 canonical 加引规则；不加 Mockito；不改 Task 4 的 SocialData/SocialChangeSet；不推送；不抹计划原文（取代说明就地追加）。
