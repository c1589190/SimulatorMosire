# src 修复轮 01：两处无判别力的 `hasMessageContaining`

> **这是你的需求书，其中的精确取值照抄即可。** 本文件之外的任何"背景故事"都不是要求。

## 一、这是什么、为什么做

SimulatorMosire（simos）的 M1（UtilSimos）已实现完毕，正在关账。关账前的最后一次自查里，
`simos-util` 的**测试**中发现两处断言**看着像在钉守卫，实际钉不住**——按本项目的说法，属于
**判别力缺陷**（"护栏必须自证"的反面）。

**已由控制器实测确证**（原话记录在 `.superpowers/sdd/2026-09-16-util-simos-plan/progress.md`）：
把被测的消息正文删空，这两条断言**照样全绿**。也就是说它们**根本没在钉异常消息**，
钉的是**输入数据**（`namespace` / `address.namespace()` 恰好就是 `"map"`）。

你的任务：**把这两处改成有判别力的断言**，并**用变异法当场证明改前改后的差别**。

**为什么是现在做**：这一轮是**独立的一轮**，不是任何任务的修复轮。它必须在终审之前落地，
否则会变成"已确证要修、却挂在某次未必会发生的派单上"——那种修法会被静默丢掉。

## 二、要改的文件

只有一个：
`simos-util/src/test/java/io/mosire/simos/util/resolve/ResolverRegistryTest.java`

被测实现（**只读，不要改**）：
`simos-util/src/main/java/io/mosire/simos/util/resolve/ResolverRegistry.java`

## 三、处 A：`duplicateRegistrationIsRejected`（`:46-53`）

**现状**：
```java
  @Test
  void duplicateRegistrationIsRejected() {
    ResolverRegistry registry = new ResolverRegistry();
    registry.register(resolver("map", "m-1"));
    assertThatThrownBy(() -> registry.register(resolver("map", "m-2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("map");
  }
```

**两个独立缺陷**：
1. **needle 是输入数据**。`"map"` 就是 `registry.register(resolver("map", "m-1"))` 里的命名空间。
   实现侧消息（`ResolverRegistry.java:26`）是
   `"命名空间 " + namespace + " 已有解析器，不允许重复注册（注册表不设优先级）"`，
   其中 `"map"` 来自插值。**把消息正文整个删空，这条断言仍绿。**
2. **后置状态无人看**。实现用的是 `resolvers.putIfAbsent(namespace, resolver)`（`ResolverRegistry.java:25`）。
   把它换成 `put`，**异常照抛**（`put` 返回被顶掉的旧值，非 null），**但注册表已经被换成了 m-2**。
   这条用例对此完全无感。

**改成**：
```java
  @Test
  void duplicateRegistrationIsRejected() {
    ResolverRegistry registry = new ResolverRegistry();
    registry.register(resolver("map", "m-1"));
    assertThatThrownBy(() -> registry.register(resolver("map", "m-2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已有解析器"); // 消息**专有**文本，输入数据里不含（见下方自证 M2'）
    // 失败的注册不得改动注册表：putIfAbsent→put 时此处读到 "m-2"，转红（见下方自证 M3'）。
    assertThat(
            registry.resolve(Address.parse("map:Map1"), context()).candidates().get(0).id().localId())
        .isEqualTo("m-1");
  }
```

**关于后置断言的形态 —— 控制器在此处踩过一次，请照抄，不要"优化"**：
最初拟的是 `assertThat(registry.namespaces()).containsExactly("map")`，**那是错的**。
`putIfAbsent`→`put` 换掉的是**值**不是**键**，`namespaces()` 仍返回 `["map"]`，**照样绿**。
唯一接得住的是**钉住"解析出来的仍是原先那个 resolver"**，即上面复刻 `ResolverRegistryTest:33-43`
既有写法的形态。依据：本测试的辅助方法 `resolver(namespace, localId)`（同文件 `:139-155`）
把 `localId` 写进 `ResolvedSubject` 的 `SubjectId`，故 `localId()` 能读出"注册表里现在装的是哪一个"。
**若你发现 `localId()` 之外还有更直接的读法，可以换，但必须在报告里说明为什么它同样接得住 M3'。**

格式化交给 Spotless：改完跑 `./mvnw -q spotless:apply`，**不要手工调行宽**。

## 四、处 B：`unregisteredNamespaceIsRejectedWithNoFallback`（`:55-62`）

**现状**：
```java
  @Test
  void unregisteredNamespaceIsRejectedWithNoFallback() {
    // 与 GSimulator 的"静默遮蔽"相反：没有注册就是错，不给兜底解析器。
    ResolverRegistry registry = new ResolverRegistry();
    assertThatThrownBy(() -> registry.resolve(Address.parse("map:Map1"), context()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("map");
  }
```

**缺陷**（同上第 1 条）：`"map"` 来自 `Address.parse("map:Map1")` 的命名空间段。实现侧消息
（`ResolverRegistry.java:41-42`）是
`"没有注册命名空间 " + address.namespace() + " 的解析器（已注册：" + resolvers.keySet() + "）"`。
**把消息正文整个删空、只留 `address.namespace()`，这条断言仍绿。**

**改成**：
```java
        .hasMessageContaining("没有注册命名空间");
```
其余行不动（注释保留）。**此处不需要补后置断言**：`resolve` 是只读路径，无状态可改。

## 五、自证要求（**本轮的实质工作在这里，不是上面那两行替换**）

**纪律（本项目反复付过代价的一条）**：
> **变异体本身也要自证。** 读完测试结果之前，**先证明"变异后的产物与原件字节不同"**。
> 否则"全绿"可能只是**变异根本没进去**——控制器本轮亲身踩到过：变异装置里算出了变异源码
> **却从没写到磁盘**，`javac` 编的是原件，三向全绿，据此误判两次。

**做法**（在 `/tmp` 下的副本上做，**绝不改工作树里的任何文件**）：
1. 把原件编一份作**参照**，记 `md5`。
2. 每个变异体：把变异源码**写到真正的临时文件**、编译、**先比 md5 ≠ 参照**，**再**读测试结果。
   相等即断言失败并中止——**不要**把"md5 相同"当成"测试通过"。
3. 跑测试时用 `target/classes` 里那份**被替换过的** class，确认 `javap`/类文件确实是变异体。
   控制器上轮的可用参照：把变异 class 覆盖进一份 `target/classes` 的 `/tmp` 副本，用
   `junit-platform-console` 跑；测试所需的其余 class 从工作树 `target/` 拷来。**这套装置你自己重建，
   不要信任 `/tmp/rrt/` 里残留的旧装置**（它是为另一组变异写的，且它的第一版有过上述"没写盘"的 bug）。

**必须跑的变异体**（三个"改前/改后"成对，外加两个 G13 底）：

| # | 变异 | 改前（现 HEAD 的测试） | 改后 | 这条证明什么 |
|---|---|---|---|---|
| **M0** | 删掉 `ResolverRegistry.java:25-27` 整块 `if (...) throw` | 红 | 红 | G13 底：**这个用例不是装饰**。改前改后**都红**，故它**不是**判别力证据 |
| **M2'** | 处 A 消息正文删空：`throw new IllegalArgumentException(namespace);` | **绿** ← 缺陷 | **红** | 处 A 的新 needle 有判别力（旧 needle 由输入数据满足） |
| **M3'** | `putIfAbsent` → `put` | **绿** ← 缺陷 | **红** | 处 A 的新后置断言有判别力 |
| **M4** | 删掉 `ResolverRegistry.java:40-43` 的 `if (resolver == null) throw` | 红 | 红 | G13 底：处 B 的用例不是装饰（删除后抛的是热心 NPE，不是 `IllegalArgumentException`） |
| **M5'** | 处 B 消息改为 `throw new IllegalArgumentException(address.namespace());` | **绿** ← 缺陷 | **红** | 处 B 的新 needle 有判别力 |

**M2' / M3' / M5' 三条是"改前绿、改后红"的成对证据。**
只报"改后全红"是不够的——那证明不了**修复**有用，只证明用例本身会响。
**报告里这三条必须各带一次改前、一次改后的原始输出。**

**改前那三次**：在工作树**当前 HEAD** 的测试文件上跑（即不要先改文件）。若你先改了再想跑"改前"，
用 `git stash`/`git worktree` 取一份 HEAD 副本到 `/tmp`，**不要在时间上撒谎**。

## 六、报告契约

写进 `.superpowers/sdd/2026-09-16-util-simos-plan/src-fix-01-report.md`：

1. **改了什么**：两处的 diff（`git diff` 原文）。
2. **自证表**：上表五行，逐行给**命令 + 原始输出摘录 + 结论色**。M2'/M3'/M5' 各要**改前与改后两份**输出。
3. **变异体自证**：每个变异体的 `md5(变异产物)` vs `md5(原件参照)`，明写"不同"。
4. **门禁实跑**：`./mvnw clean verify` 的退出码、六模块结果、`simos-util` 的 `Tests run` 计数、
   `BugInstance size`。**注意 `mvn test` 不跑 SpotBugs**，关账判据是 `verify`。
5. **你未能核实的**：明写，并说明卡在哪。**宁可报"这条我核不了"，也不要报一个你没跑过的结论。**

## 七、硬约束

- **绝不 `git add -A`**；`git add` 具体文件后、提交前先扫 `git diff --cached`。
- **不推送**。
- **不得改动 `src/main`**：本轮只动那一个测试文件。变异全部在 `/tmp` 的副本上做。
- **不派发任何子代理**，你自己做完。
- **只跑相关用例做迭代**，关账时跑一次完整 `./mvnw clean verify`。
- 提交时把**完整精确哈希**记进报告（不要写 `HEAD` 这种相对写法）。
- 提交信息末尾加一行：`Co-Authored-By: Claude Code <noreply@anthropic.com>`
- 中文注释与文档，与文件既有风格一致。
- **本机 `grep` 是 `ugrep`**：它尊重 `.gitignore` 且跳隐藏目录，会**静默返回空**。搜 `.superpowers/**`
  用 `git grep` 或 `grep --hidden --no-ignore-files`。
