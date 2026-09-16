### Task 3: 身份（`identity` 包）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/identity/SubjectId.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/identity/ResolvedSubject.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/identity/QueryResult.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/identity/SubjectIdTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/identity/ResolvedSubjectTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/identity/QueryResultTest.java`

**Interfaces:**
- Consumes: `Address`（Task 2）——`ResolvedSubject` 用它守"canonicalAddress 必须是 canonical"
- Produces: `SubjectId(String namespace, String localId)`；`ResolvedSubject(SubjectId id, String canonicalAddress, String typeName)`；`QueryResult(List<ResolvedSubject> candidates)`（`candidates` 保序、不可变）

- [ ] **Step 1: 写失败测试**

`SubjectIdTest.java`：

```java
package io.mosire.simos.util.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** spec §四：稳定身份的值语义；命名层级由各模块自定，Util 不校验。 */
class SubjectIdTest {

  @Test
  void valueSemantics() {
    assertThat(new SubjectId("map.hex", "h-0001")).isEqualTo(new SubjectId("map.hex", "h-0001"));
    assertThat(new SubjectId("map.hex", "h-0001"))
        .hasSameHashCodeAs(new SubjectId("map.hex", "h-0001"));
    assertThat(new SubjectId("map.hex", "h-0001").toString()).contains("map.hex", "h-0001");
  }

  @Test
  void blankPartsAreRejected() {
    assertThatThrownBy(() -> new SubjectId(" ", "h-0001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SubjectId("map.hex", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void namespaceHierarchyIsFreeForm() {
    // §四：`<模块>` 或 `<模块>.<类型>` 都由模块自定，Util 不校验层级
    assertThat(new SubjectId("unit", "U-1").namespace()).isEqualTo("unit");
    assertThat(new SubjectId("map.region", "R-1").namespace()).isEqualTo("map.region");
  }
}
```

`ResolvedSubjectTest.java`：

```java
package io.mosire.simos.util.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** spec §四：候选 = 稳定 ID + canonical 地址 + 展示用类型名。 */
class ResolvedSubjectTest {

  @Test
  void carriesIdentityAddressAndType() {
    ResolvedSubject s = hexSubject("map:Map1:hex.4_3");
    assertThat(s.id()).isEqualTo(new SubjectId("map.hex", "h-0001"));
    assertThat(s.canonicalAddress()).isEqualTo("map:Map1:hex.4_3");
    assertThat(s.typeName()).isEqualTo("Hex");
  }

  @Test
  void valueSemantics() {
    assertThat(hexSubject("map:Map1:hex.4_3")).isEqualTo(hexSubject("map:Map1:hex.4_3"));
    assertThatThrownBy(() -> new ResolvedSubject(new SubjectId("map.hex", "h-0001"), " ", "Hex"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResolvedSubject(new SubjectId("map.hex", "h-0001"), "map:Map1", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonCanonicalAddressIsRejected() {
    // §四：调用方选定后一律回传 canonical。宽容写法不得成为 Resolver 的输出。
    assertThatThrownBy(
            () -> new ResolvedSubject(new SubjectId("social.population", "p-1"), "social:Map1.[4,3]:population", "Population"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical");
  }

  private static ResolvedSubject hexSubject(String address) {
    return new ResolvedSubject(new SubjectId("map.hex", "h-0001"), address, "Hex");
  }
}
```

`QueryResultTest.java`：

```java
package io.mosire.simos.util.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** spec §四：候选列表保序、不可变（Human 形式可多解，全部列出）。 */
class QueryResultTest {

  @Test
  void candidatesKeepInsertionOrder() {
    ResolvedSubject first = subject("h-0001");
    ResolvedSubject second = subject("h-0002");
    assertThat(new QueryResult(List.of(first, second)).candidates()).containsExactly(first, second);
    assertThat(new QueryResult(List.of()).candidates()).isEmpty();
  }

  @Test
  void candidatesAreDefensivelyCopied() {
    List<ResolvedSubject> mutable = new ArrayList<>();
    mutable.add(subject("h-0001"));
    QueryResult result = new QueryResult(mutable);
    mutable.clear();
    assertThat(result.candidates()).hasSize(1);
    assertThatThrownBy(() -> result.candidates().add(subject("h-0002")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static ResolvedSubject subject(String localId) {
    return new ResolvedSubject(new SubjectId("map.hex", localId), "map:Map1:hex.4_3", "Hex");
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest='SubjectIdTest,ResolvedSubjectTest,QueryResultTest' test`
Expected: 编译失败——`cannot find symbol: class SubjectId`

- [ ] **Step 3: 实现三个 record**

`SubjectId.java`：

```java
package io.mosire.simos.util.identity;

/**
 * 稳定身份：`namespace` + `localId`（铁律 1——地址是定位方式，ID 是身份）。
 *
 * <p>`namespace` 取 `<模块>` 或 `<模块>.<类型>`（`map.hex` / `unit.equipment`），层级由各模块自定，Util 不校验；
 * `localId` 的生成规则（前缀、长度、随机源）同样属于各领域模块。
 */
public record SubjectId(String namespace, String localId) {

  public SubjectId {
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("SubjectId.namespace 不得为空白");
    }
    if (localId == null || localId.isBlank()) {
      throw new IllegalArgumentException("SubjectId.localId 不得为空白");
    }
  }
}
```

`ResolvedSubject.java`：

```java
package io.mosire.simos.util.identity;

import io.mosire.simos.util.address.Address;

/**
 * 解析结果的候选项：稳定 ID + canonical 地址 + 展示/路由用类型名（spec §四）。
 *
 * <p>地址必须是 canonical 形式——机器协议只用 canonical，宽容写法在解析入口就被归一掉了。
 */
public record ResolvedSubject(SubjectId id, String canonicalAddress, String typeName) {

  public ResolvedSubject {
    if (id == null) {
      throw new IllegalArgumentException("ResolvedSubject.id 不得为 null");
    }
    if (canonicalAddress == null || canonicalAddress.isBlank()) {
      throw new IllegalArgumentException("ResolvedSubject.canonicalAddress 不得为空白");
    }
    if (!Address.parse(canonicalAddress).canonical().equals(canonicalAddress)) {
      throw new IllegalArgumentException(
          "canonicalAddress 必须是 canonical 形式：" + canonicalAddress);
    }
    if (typeName == null || typeName.isBlank()) {
      throw new IllegalArgumentException("ResolvedSubject.typeName 不得为空白");
    }
  }
}
```

`QueryResult.java`：

```java
package io.mosire.simos.util.identity;

import java.util.List;

/** 解析结果：候选列表，保序（spec §四）。Human 形式可多解，全部列出；空列表 = 没有候选，不是错误。 */
public record QueryResult(List<ResolvedSubject> candidates) {

  public QueryResult {
    candidates = List.copyOf(candidates);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest='SubjectIdTest,ResolvedSubjectTest,QueryResultTest' test`
Expected: PASS

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/identity/ simos-util/src/test/java/io/mosire/simos/util/identity/
git diff --cached --stat
git commit -m "feat(util): SubjectId/ResolvedSubject/QueryResult 身份三件（M1 Task 3）"
```

---

