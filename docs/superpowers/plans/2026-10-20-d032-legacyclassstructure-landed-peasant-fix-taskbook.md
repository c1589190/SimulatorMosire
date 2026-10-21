# 生产缺陷修复任务书：LegacyClassStructure 缺 landed_peasant

> **状态：T1 测试 owner 报告的生产缺陷；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。

## 1. 现象（T1 报告，逐字）

```text
FILE:LINE
simos-economy/src/main/java/io/mosire/simos/economy/migrate/LegacyClassStructure.java:258-275 (roleSpecOf),
reached from buildDefaultPositions() :230-237 via SocialClassId.all() now containing landed_peasant
(simos-economy-api/.../SocialClassId.java:26-42, ALL :78-87).

REPRO:
tools/mvn-lock.sh -q test -pl simos-economy -am
=> 2 errors in W4SurvivalTickIntegrationTest:
java.lang.ExceptionInInitializerError at LegacyClassStructure.<clinit> (line 120)
Caused by: IllegalStateException: 旧档默认阶层结构尚未裁决该社会阶层的角色：landed_peasant
at LegacyClassStructure.roleSpecOf(LegacyClassStructure.java:274)
at LegacyClassStructure.positionOf(:242) -> buildDefaultPositions(:234)

EXPECTED: LegacyClassStructure static init succeeds for every SocialClassId.all() entry
(incl. newly appended landed_peasant) OR SocialClassId.all() excludes it; legacy default
must expose a deterministic position.
ACTUAL: whole class init fails; all tests using ProductionRoleResolver / EnterpriseOrganizationBook
die with ExceptionInInitializerError/NoClassDefFoundError.
```

## 2. 根因

- RC2b 在 `SocialClassId` 词表末尾追加了 `LANDED_PEASANT="landed_peasant"`；
- `SocialClassId.all()` 现在包含 8 个值；
- `LegacyClassStructure.buildDefaultPositions()` 对 `all()` 逐项调用 `roleSpecOf`；
- `roleSpecOf` 的 switch 仍只覆盖旧 7 档，`landed_peasant` 落入 default 抛 `IllegalStateException`。

这是旧档兼容默认结构的**遗漏 case**，不是 RC2b 的 class nature 语义问题。

## 3. 冻结修复

在 `LegacyClassStructure.roleSpecOf` 增加确定性的 `landed_peasant` 分支：

```java
case "landed_peasant" ->
    new RoleSpec(
        "有地农民",
        RelationToMeans.MIXED,
        LaborRole.BOTH,
        SurplusRole.SELF_SUBSISTENCE);
```

理由：

- `landed_peasant` 是 RC2b 的有地农民社会阶层，语义上是“有地、家庭经营、家庭劳动、产出归本户”；
- 旧档默认结构只做兼容占位、不驱动生产，不需要为它设置 mode-level classNature；
- `positionOf` 当前使用六参 `ProductionRole` 便利构造，classNature=empty，满足 `LegacyClassStructure.DEFAULT_MODE.supportedClassNatures = empty`；
- 不改变旧 7 档的位置/行为，只追加第 8 个兼容位置。

同步修改类注释：

- “7 个位置” → “8 个位置”；
- 表格增加 `landed_peasant / legacy-landed_peasant / MIXED / BOTH / SELF_SUBSISTENCE`；
- `buildDefaultPositions` / `zeroDefaultShares` 等注释里的 “7” 改为 “SocialClassId.all() 当前长度”或 “8”。

## 4. 允许修改

```text
simos-economy/src/main/java/io/mosire/simos/economy/migrate/LegacyClassStructure.java
```

**禁止**：tests、EconomyData/ChangeSet/Codec、SocialClassId、class nature/culture 契约、seeder、主树、commit/push。

## 5. 验收锚点（测试 owner 后补）

- `LegacyClassStructure` static init 不再抛；
- `SocialClassId.all()` 8 个值都能解析出 legacy position；
- 旧 7 档位置 id/role 值逐字不变；
- `legacy-landed_peasant` 存在且 role=MIXED/BOTH/SELF_SUBSISTENCE；
- `DEFAULT_MODE.supportedClassNatures` 仍为空，position classNature 仍 empty。

## 6. 编译

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-legacyclassstructure-landed-peasant-fix/impl-ledger.md`。
- 报告列：改动文件、编译 rc、新 case、旧 7 档不变证据、未验证项。
