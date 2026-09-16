### Task 1: 地址段类型与 canonical 渲染

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/AddressText.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/AddressSegment.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/Namespace.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/Entity.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/Index.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/Property.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/address/AddressQuoteTest.java`

**Interfaces:**
- Consumes: 无（本任务是 M1 的第一步）
- Produces: `AddressSegment`（sealed，`String canonical()`）；`Namespace(String ident)`、`Entity(Optional<String> kind, String name)` + 工厂 `Entity.of(String name)` / `Entity.of(String kind, String name)`、`Index(List<Integer> coords)`、`Property(String ident)`；包内 `AddressText.isBareWord/quote/quoteIfNeeded`

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.util.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** spec §3.4：三条"按需加引"条件、`""` 转义，与段类型的构造期校验（§3.2）。 */
class AddressQuoteTest {

  @Test
  void bareWordNamesAreNotQuoted() {
    assertThat(Entity.of("hex", "4_3").canonical()).isEqualTo("hex.4_3");
    assertThat(Entity.of("region", "Nation.区域A").canonical()).isEqualTo("region.Nation.区域A");
  }

  @Test
  void namesWithStructuralCharactersAreQuoted() {
    assertThat(Entity.of("region", "河口:渡口").canonical()).isEqualTo("region.\"河口:渡口\"");
    assertThat(Entity.of("region", "[前线]").canonical()).isEqualTo("region.\"[前线]\"");
    assertThat(Entity.of("region", " 区域A ").canonical()).isEqualTo("region.\" 区域A \"");
    assertThat(Entity.of("region", "").canonical()).isEqualTo("region.\"\"");
  }

  @Test
  void quoteCharacterIsDoubled() {
    assertThat(Entity.of("region", "A\"B").canonical()).isEqualTo("region.\"A\"\"B\"");
  }

  @Test
  void kindlessDottedNameIsQuoted() {
    assertThat(Entity.of("高地人旅指挥部.1营指挥部").canonical())
        .isEqualTo("\"高地人旅指挥部.1营指挥部\"");
    assertThat(Entity.of("Map1").canonical()).isEqualTo("Map1");
  }

  @Test
  void kindMustBeBareWord() {
    assertThat(Entity.of("hex", "4_3").kind()).contains("hex");
    assertThat(Entity.of("Map1").kind()).isEmpty();
    assertThatThrownBy(() -> Entity.of("a.b", "x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Entity.of("a b", "x")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void namespaceIndexAndPropertyRenderCanonically() {
    assertThat(new Namespace("map").canonical()).isEqualTo("map");
    assertThat(new Index(List.of(4, 3)).canonical()).isEqualTo("[4,3]");
    assertThat(new Index(List.of(7)).canonical()).isEqualTo("[7]");
    assertThat(new Property("population_growth").canonical()).isEqualTo("population_growth");
    assertThatThrownBy(() -> new Namespace("a:b")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Property("a b")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest=AddressQuoteTest test`
Expected: 编译失败——`cannot find symbol: class AddressSegment`（Java 里新类型的第一个失败就是编译错误，正常）

- [ ] **Step 3: 实现词法工具与四种段**

`AddressText.java`（包内可见，不对外暴露）：

```java
package io.mosire.simos.util.address;

/** 地址词法工具：裸词判定、引号转义与"按需加引"（spec §3.3/§3.4）。包内可见。 */
final class AddressText {

  private AddressText() {}

  /** 裸词：非空、无首尾空白、且不含 `: . [ ] "`。 */
  static boolean isBareWord(String s) {
    if (s == null || s.isEmpty() || !s.equals(s.strip())) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      switch (s.charAt(i)) {
        case ':', '.', '[', ']', '"' -> {
          return false;
        }
        default -> {}
      }
    }
    return true;
  }

  /** 无条件加引：内部 `"` 写成 `""`。 */
  static String quote(String s) {
    return '"' + s.replace("\"", "\"\"") + '"';
  }

  /**
   * 名字的规范写法：命中 spec §3.4 三条加引条件才加引。
   *
   * @param kindAbsent 该名字所属的 Entity 是否缺省了 kind（条件 3：缺 kind 且名字含 `.` 必须加引）
   */
  static String quoteIfNeeded(String name, boolean kindAbsent) {
    boolean needs =
        name.isEmpty()
            || !name.equals(name.strip())
            || name.indexOf(':') >= 0
            || name.indexOf('[') >= 0
            || name.indexOf(']') >= 0
            || name.indexOf('"') >= 0
            || (kindAbsent && name.indexOf('.') >= 0);
    return needs ? quote(name) : name;
  }
}
```

`AddressSegment.java`：

```java
package io.mosire.simos.util.address;

/** 地址的一段。段间用 `:` 分隔，段内用 `.` 分隔 kind 与 name（spec §3.2）。 */
public sealed interface AddressSegment permits Namespace, Entity, Index, Property {

  /** 本段的规范写法（canonical，按需加引，spec §3.4）。 */
  String canonical();
}
```

`Namespace.java`：

```java
package io.mosire.simos.util.address;

/**
 * 命名空间段（`map` / `social` / `unit` / `agent`）：地址的第 1 段，必须是裸词（spec §3.2）。
 */
public record Namespace(String ident) implements AddressSegment {

  public Namespace {
    if (!AddressText.isBareWord(ident)) {
      throw new IllegalArgumentException("命名空间必须是裸词（非空、无首尾空白、不含 : . [ ] \"）：" + ident);
    }
  }

  @Override
  public String canonical() {
    return ident;
  }
}
```

`Entity.java`：

```java
package io.mosire.simos.util.address;

import java.util.Objects;
import java.util.Optional;

/**
 * 实体段：`kind.name`（`hex.4_3`）或根主体名（`Map1`，kind 缺省）。
 *
 * <p>kind 必须是裸词——它是结构词（类型判别用），任意字符的载体是 name（spec §3.2/§3.4）。
 */
public record Entity(Optional<String> kind, String name) implements AddressSegment {

  public Entity {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    if (kind.isPresent() && !AddressText.isBareWord(kind.get())) {
      throw new IllegalArgumentException("kind 必须是裸词（非空、无首尾空白、不含 : . [ ] \"）：" + kind.get());
    }
  }

  /** 缺省 kind 的实体（根主体，或 Human 形式里省略类型词的实体）。 */
  public static Entity of(String name) {
    return new Entity(Optional.empty(), name);
  }

  public static Entity of(String kind, String name) {
    return new Entity(Optional.of(kind), name);
  }

  @Override
  public String canonical() {
    return kind.map(k -> k + ".").orElse("") + AddressText.quoteIfNeeded(name, kind.isEmpty());
  }
}
```

`Index.java`：

```java
package io.mosire.simos.util.address;

import java.util.List;
import java.util.stream.Collectors;

/** 索引段：`[q,r]` 或 `[i]`（Human 形式；canonical 的单个 hex 用 `hex.4_3` 形式）。 */
public record Index(List<Integer> coords) implements AddressSegment {

  public Index {
    coords = List.copyOf(coords);
    if (coords.isEmpty()) {
      throw new IllegalArgumentException("Index 段至少一个坐标");
    }
  }

  @Override
  public String canonical() {
    return coords.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
  }
}
```

`Property.java`：

```java
package io.mosire.simos.util.address;

/**
 * 属性段：`population` / `height` / `member`——地址第 3 段起的裸词（spec §3.2）。
 */
public record Property(String ident) implements AddressSegment {

  public Property {
    if (!AddressText.isBareWord(ident)) {
      throw new IllegalArgumentException("属性名必须是裸词（非空、无首尾空白、不含 : . [ ] \"）：" + ident);
    }
  }

  @Override
  public String canonical() {
    return ident;
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest=AddressQuoteTest test`
Expected: PASS（6 个用例）

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/address/ simos-util/src/test/java/io/mosire/simos/util/address/
git diff --cached --stat   # 确认只有本任务的 7 个文件
git commit -m "feat(util): 地址段类型与按需加引的 canonical 渲染（M1 Task 1）"
```

---

