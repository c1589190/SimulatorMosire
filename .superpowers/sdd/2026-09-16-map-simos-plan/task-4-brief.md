### Task 4: `pathway` 包 —— 边与线

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/pathway/EdgeRef.java` `EdgeTags.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/pathway/PathwayId.java` `Pathway.java` `PathwayGroup.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/pathway/EdgeRefTest.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/pathway/EdgeTagsTest.java`（★ 派单前扫描补，R-48-b）
- Test: `simos-map/src/test/java/io/mosire/simos/map/pathway/PathwayTest.java`（`PathwayId` 的三件套用例
  并进这里，**不另开文件** —— 与 Task 3 把 `RegionId` 的用例放进 `RegionTest` 同形制）

**这个任务解的是 L2，并落地待决项 3**（连通性稳定 ID）。

- [ ] **Step 1: 写 `EdgeRef`（★ 规范序在构造期完成）**

```java
package io.mosire.simos.map.pathway;

import io.mosire.simos.map.hex.HexCoord;

/**
 * 无向边。**身份就是那两格**，不需要 ID。
 *
 * <p>★ 构造期完成规范排序 ⇒ {@code (a,b)} 与 {@code (b,a)} 恒为同一对象。
 * GSimulator 有一份 {@code edgeKey} 手写逻辑的**4 份实现**（Java 2 + JS 1 + 1 处内联），
 * 其中前端那份的注释自陈 <i>"Must stay in sync with MapData.edgeKey() in Java"</i> ——
 * 靠注释维系的同步不是同步。此处由类型系统保证。
 */
public record EdgeRef(HexCoord a, HexCoord b) implements Comparable<EdgeRef> {

  public EdgeRef {
    if (a == null || b == null) throw new IllegalArgumentException("边的端点不得为 null");
    if (a.equals(b)) throw new IllegalArgumentException("边不能自环: " + a);
    if (a.compareTo(b) > 0) {
      HexCoord t = a;
      a = b;
      b = t;
    }
  }

  /**
   * 规范字符串形式。★ **两种用途**（R-48-f 更正）：① 它是变更集里 {@code edges} 组件的 **key**
   * （`MapChangeSet.between/apply` 用 `keyOf = toString()` 比对与还原）；② JSON 边界。
   * 原稿只写了 ②，与 Task 6 的变更集口径**对不上**，已改。
   */
  @Override
  public String toString() {
    return a + "|" + b;
  }

  /** ★ **往返的另一半**（R-48-f）：没有它就断链 —— 变更集 key 还原不回来。 */
  public static EdgeRef parse(String s) {
    // 按 '|' 切成两段，各交给 HexCoord.parse；段数不为 2 即抛（宁抛不静默）
  }

  @Override
  public int compareTo(EdgeRef o) {
    int c = a.compareTo(o.a);
    return c != 0 ? c : b.compareTo(o.b);
  }
}
```

- [ ] **Step 2: 写 `EdgeTags` / `PathwayId` / `PathwayGroup`**

```java
/** 边上的标注：属于哪个 pathway 组的实例、带哪些属性。 */
public record EdgeTags(java.util.Map<String, java.util.Map<String, Object>> byPathway) {
  public EdgeTags { byPathway = /* 保序不可变 */; }
}
```

```java
/**
 * 线的稳定身份。
 *
 * <p>★ **一旦分配即持久化，不由内容派生** —— 内容派生会让"改一个中间节点"变成"换了一条河"。
 * 生成期由 {@code (seed, 序号)} 确定性派生（保证同种子可复现）；编辑期由 Command 分配并持久化；
 * 分裂出的新段拿新 ID，缩短不改 ID。
 */
public record PathwayId(String value) {
  public PathwayId {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("PathwayId 不得为空白");
  }

  /** ★ **裸值**，不是 record 默认的 `PathwayId[value=…]`（R-48-f）—— 它是**地址**，是变更集的 key。 */
  @Override
  public String toString() { return value; }

  /** ★ 往返的另一半（R-48-f）。缺了它 `MapChangeSet.apply` 就还原不回来。 */
  public static PathwayId parse(String s) { /* 非空白即收，否则抛 */ }
}
```

```java
/** 线的**组定义**（river / road …）。老仓 `MapData.PathwayGroup`（实测 `:451`），字段照搬。 */
public record PathwayGroup(String id, String name, String color, String description,
                           boolean visible, java.util.Map<String, PropertyDef> properties) {
  public PathwayGroup {
    // ★ 老仓对 id/name 等做 `if (x == null) x = "";` **静默填空** —— 本项目禁。
    //   空白即抛（与 TerrainType / RegionId 同族）。
  }
}

/** 组属性的一条定义。**嵌套 record** —— Task 7 的反射枚举要能穿透它（R-48-c：不许为省事删掉）。 */
public record PropertyDef(String type, Object defaultValue, String description) {}
```

★ **老仓的两组默认值**（`MapData.java:469 defaultPathwayGroups`，实测）：`river` 河流 `#3295D2` / `road` 道路 `#8B7355`。
是否需要一份 `defaults()` 由 Task 6 的 `GenerationSpec` 决定；**本任务只交付类型**。

- [ ] **Step 3: 写 `Pathway`**

```java
/**
 * 一条**极大简单链**：两端是端点或分支点。
 *
 * <p>★ **分支点即端点** —— 度数 >= 3 的格是分支点，线在分支处断开 ⇒ "分支是独立的线"。
 * <p>★ GSimulator 的线段**只有 groupId**（所有河流共享 {@code "river"}），链的身份是**返回列表的下标**
 * ⇒ 总纲 §5.1 要的"单条连通性线段可寻址"当前做不到。本类型就是那个承载结构。
 */
public record Pathway(PathwayId id, String name, String groupId,
                      java.util.List<EdgeRef> edges,
                      java.util.Map<String, Object> props) {

  public Pathway {
    if (id == null) throw new IllegalArgumentException("id 不得为 null");
    if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("groupId 不得为空白");
    edges = java.util.List.copyOf(edges);
    props = /* 保序不可变 */;
  }

  /** 两端端点（链的两头）。闭环的处置见 spec §5.2：以规范序最小的格作锚。 */
  public HexCoord start() { /* … */ }

  public HexCoord end() { /* … */ }

  public int length() { return edges.size(); }
}
```

- [ ] **Step 4: 写用例**

```
EdgeRefTest
  - orderIsCanonical                      : new EdgeRef(a,b).equals(new EdgeRef(b,a))
  - canonicalFormTouchesASortedFields     : 反射断言 a.compareTo(b) <= 0 恒成立
  - selfLoopIsRejected                    : new EdgeRef(a,a) → IllegalArgumentException
  - nullEndpointIsRejected
  - toStringIsStable                      : 同一无向边的两个构造方向 toString **相同**
  - toStringMatchesFrozenLiteral          : ★ **冻结串**（R-48-f）：`new EdgeRef(c(3,4), c(-1,0)).toString()`
                                            恰为字面量 `"-1_0|3_4"`（**规范序在前**，不是入参序：
                                            `HexCoord.compareTo` 先 q 后 r，−1 < 3 故 `(-1,0)` 在前）。
                                            全局约束「没有冻结用例的手写 toString 算违规」—— `toStringIsStable`
                                            只比两个方向，**不算冻结**，光有它不达标。
  - parseRoundTripsFrozenLiteral           : ★ `EdgeRef.parse("-1_0|3_4")` equals 上式结果；
                                            段数不为 2 的串 → IllegalArgumentException
  - compareToIsTotalOrder

PathwayTest
  - constructorRejectsBlankGroupIdAndNullId
  - edgesIsImmutable
  - lengthIsEdgeCount
  - startAndEndAreTheChainEnds            : 三格链 → start/end 是两头，不是中间
  - ★ idsArePersistedNotDerived           : 见 Step 5
  - equalityIsComponentwise

  # ★ R-48-f：`PathwayId` 的三件套（缺一，Task 6 的往返就断）
  - pathwayIdToStringIsBareValue          : new PathwayId("p1").toString() 恰为 "p1"
                                            （**不是** `PathwayId[value=p1]` —— 默认实现会让变更集 key
                                             变成 `PathwayId[value=p1]`，apply 侧认不出来）
  - pathwayIdParseRoundTripsFrozenLiteral : PathwayId.parse("p1").equals(new PathwayId("p1"))
  - pathwayIdParseRejectsBlank            : parse("") / parse("  ") → IllegalArgumentException

EdgeTagsTest
  - preservesInsertionOrder               : ★ 保序（前端曾因 props 被抹平而丢数据）
  - isImmutable

PathwayIdTest                             # ★ R-48-f：ID 三件套，缺一往返就断
  - toStringIsBareValue                   : new PathwayId("p1").toString() 恰为 "p1"
                                            （**不是** `PathwayId[value=p1]` —— 那条默认实现会让
                                             变更集 key 变成 `PathwayId[value=p1]`，apply 侧认不出来）
  - parseRoundTripsFrozenLiteral          : PathwayId.parse("p1").equals(new PathwayId("p1"))
  - parseRejectsBlank                     : parse("") / parse("  ") → IllegalArgumentException
  - rejectsBlankValue                     : 构造期校验不是装饰
```

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| 删掉 `EdgeRef` 构造期的 `a.compareTo(b) > 0` 交换 | **红** | `orderIsCanonical` 真的钉住了规范序 |
| 删掉自环校验 | **红** | `selfLoopIsRejected` 不是装饰 |
| `Pathway` 加一个派生 id 的方法并让构造器**忽略传入 id** | **红** | ★ `idsArePersistedNotDerived` 有判别力 |
| `EdgeTags` 改用 `Map.copyOf` | **红** | `preservesInsertionOrder` 真的钉住了保序 |
| `PathwayId` 里**删掉手写 `toString()`**（退回 record 默认） | **红** | ★ R-48-f 的钉子：`pathwayIdToStringIsBareValue` 直接红。**这条是"往返会不会断"的早期报警** —— 它在 Task 4 红，比拖到 Task 6 的 `applyRebuildsTargetExactly` 才红便宜得多 |

★ **`idsArePersistedNotDerived` 怎么写**：构造两条 `edges` 相同的 `Pathway`，
**只让 `id` 不同** ⇒ 断言**不相等**。
再把其中一条的一个中间 `EdgeRef` 换掉、**保持 id 不变** ⇒ 断言 `id()` **仍然相同**。
两条合起来才钉住"id 是持久身份、不随内容漂移"。

- [ ] **Step 6: 跑门禁并提交**

提交信息 `feat(map): pathway 包——规范序无向边与稳定 ID 的线`。

---

