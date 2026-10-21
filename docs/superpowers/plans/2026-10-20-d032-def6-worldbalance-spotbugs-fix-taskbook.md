# 生产缺陷修复任务书：DEF-6 WorldBalanceSeeder duplicate branches

> 父序列锚点：R6 32hex 创世 seed；`WorldBalanceSeeder.displacedSeedOf` 只是创世初始流民比例读口。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 测试 owner 已停；只写生产代码到 compile，不写/不跑测试。

## 1. 现象（T1 gate）

```text
simos-app/src/main/java/io/mosire/simos/app/world/WorldBalanceSeeder.java:600-603
DB_DUPLICATE_BRANCHES @ line 602, Medium
```

`displacedSeedOf(ResidenceKind)`：

```java
return residence == ResidenceKind.RURAL ? RURAL_DISPLACED_SEED_PER_MILLE : 0;
```

RC2b 后 `RURAL_DISPLACED_SEED_PER_MILLE = 0`，两个分支等价 ⇒ SpotBugs 判重复分支，gate `simos-app` 1 bug。

## 2. 冻结修复

在该类内消除重复分支，保持语义：

- `displacedSeedOf(residence)` 对任何非 null residence 恒返回 `0`（RC2b 起所有池不生成 displaced 户）；
- 保留 `residence` 的 null 具名拒绝（`Objects.requireNonNull`）；
- 删除不再被读的 `RURAL_DISPLACED_SEED_PER_MILLE`，或保留但它不能再次制造重复分支；推荐直接删并用类注释说明“农村侧也是 0”；
- 更新引用它的注释，避免悬空 `{@link}`；
- `DISPLACED_SEED_PER_MILLE = 0` 与 `EconomySeeder` 的公开别名保持不动（外部读口/测试可能引用）；
- 不改 32hex 户数、LAND、class、culture、市场/政府配置。

## 3. 允许修改

```text
simos-app/src/main/java/io/mosire/simos/app/world/WorldBalanceSeeder.java
```

禁止 tests、seeder 其它文件、EconomyData/ChangeSet/Codec、production/culture 契约、主树；不 commit/push。

## 4. 验收锚点（测试 owner 后补）

- `displacedSeedOf(RURAL)` / `displacedSeedOf(URBAN)` 均返回 0；
- `displacedSeedOf(null)` 具名 NPE/IllegalArgumentException（与 `Objects.requireNonNull` 口径一致）；
- `DISPLACED_SEED_PER_MILLE == 0` 不变；
- `spotbugs:check @ simos-app` 不再报 `DB_DUPLICATE_BRANCHES`；
- 32hex fixture 仍 1 landed + 1 landless、无 displaced。

## 5. 编译

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

- 账本：`.superpowers/sdd/2026-10-20-d032-def6-worldbalance-spotbugs/impl-ledger.md`。
- 报告：改动位置、compile rc、语义不变、未验证项。
