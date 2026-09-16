### Task 2: `Address.parse` / `canonical` 与冻结样例

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/Address.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/AddressParser.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/address/AddressParseTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/address/AddressTolerantParseTest.java`
- Modify: `simos-util/src/test/java/io/mosire/simos/util/address/AddressQuoteTest.java`（补"冗余引号归一"用例，spec §3.4 后半）

**Interfaces:**
- Consumes: Task 1 的四种段 + `AddressText`
- Produces: `Address(List<AddressSegment> segments)`，`Address.parse(String)`、`Address.canonical()`、`Address.namespace()`

- [ ] **Step 1: 写失败测试（冻结样例 + 段类型判定）**

```java
package io.mosire.simos.util.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** spec §3.6：总纲 §4.4 的冻结样例逐条核对——解析结果与 canonical 往返。 */
class AddressParseTest {

  /** 冻结样例（逐字抄自 spec §3.6 表）。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "map:Map1",
        "map:Map1:hex.4_3",
        "map:Map1:terra.Grass",
        "map:Map1:terra.Grass:height",
        "map:Map1:region.Nation.区域A",
        "map:Map1:region.Nation.区域A:hexes",
        "map:Map1:conn.river.r-f82a",
        "social:Map1:hex.4_3:population",
        "social:Map1:hex.4_3:population_growth",
        "unit:U:member",
        "unit:U:equipment.步枪",
        "unit:U:hex",
        "unit:U:speed",
        "agent:bind.b-f82a",
        "agent:map:Map1:region.Nation.区域A"
      })
  void frozenSamplesRoundTrip(String canonical) {
    assertThat(Address.parse(canonical).canonical()).isEqualTo(canonical);
  }

  @Test
  void secondSegmentIsRootSubjectWithAbsentKind() {
    Address a = Address.parse("map:Map1");
    assertThat(a.namespace()).isEqualTo("map");
    assertThat(a.segments()).hasSize(2);
    assertThat(a.segments().get(1)).isEqualTo(Entity.of("Map1"));
  }

  @Test
  void kindNameSplitHappensOnFirstUnquotedDot() {
    assertThat(Address.parse("map:Map1:region.Nation.区域A").segments().get(2))
        .isEqualTo(Entity.of("region", "Nation.区域A"));
    assertThat(Address.parse("map:Map1:conn.river.r-f82a").segments().get(2))
        .isEqualTo(Entity.of("conn", "river.r-f82a"));
  }

  @Test
  void bareWordAfterPositionTwoIsProperty() {
    assertThat(Address.parse("unit:U:member").segments().get(2)).isEqualTo(new Property("member"));
    assertThat(Address.parse("map:Map1:region.Nation.区域A:hexes").segments().get(3))
        .isEqualTo(new Property("hexes"));
  }

  @Test
  void indexSegmentIsParsed() {
    assertThat(Address.parse("map:Map1:[4,3]").segments().get(2)).isEqualTo(new Index(List.of(4, 3)));
    assertThat(Address.parse("map:Map1:[7]").segments().get(2)).isEqualTo(new Index(List.of(7)));
  }

  @Test
  void quotedKindlessComponentsCollapseIntoOneName() {
    assertThat(Address.parse("map:Map1:\"Nation\".\"区域A\"").segments().get(2))
        .isEqualTo(Entity.of("Nation.区域A"));
  }

  @Test
  void addressRequiresAtLeastTwoSegmentsAndNamespaceFirst() {
    assertThat(Address.parse("map:Map1").segments().get(0)).isInstanceOf(Namespace.class);
    assertThatThrownBy(() -> new Address(List.of(Entity.of("Map1"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Address(List.of(Entity.of("Map1"), new Property("x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

再给 Task 1 建的 `AddressQuoteTest` 追加"归一"用例（spec §3.4 后半：引号只用于消歧，不进 AST）：

```java
  @Test
  void redundantQuotesAreNormalizedAway() {
    Address quoted = Address.parse("map:Map1:region.\"Nation\".\"区域A\"");
    Address plain = Address.parse("map:Map1:region.Nation.区域A");
    assertThat(quoted).isEqualTo(plain);
    assertThat(quoted.canonical()).isEqualTo("map:Map1:region.Nation.区域A");
  }
```

- [ ] **Step 2: 写失败测试（宽容写法与非法形态）**

```java
package io.mosire.simos.util.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** spec §3.5：宽容只限"引号可省/可冗余"与 `.[` 一处兼容写法；其余一律明确报错。 */
class AddressTolerantParseTest {

  @Test
  void strayDotBeforeIndexIsTolerated() {
    Address a = Address.parse("social:Map1.[4,3]:population");
    assertThat(a.segments())
        .containsExactly(
            new Namespace("social"),
            Entity.of("Map1"),
            new Index(List.of(4, 3)),
            new Property("population"));
    assertThat(a.canonical()).isEqualTo("social:Map1:[4,3]:population");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "map", // 段数 < 2
        "map:", // 空段
        ":Map1", // 空段（首段为空）
        "map::Map1", // 空段
        "map:M[a", // 名字未加引却含 `[`
        "map:a b", // 含空格却不是裸词、也未加引
        "map:[]", // 空 Index
        "map:[4,]", // 坐标不是整数
        "map:[4,x]", // 坐标不是整数
        "map:[4,3", // Index 未闭合
        "map:\"a", // 引号未闭合
      })
  void malformedAddressesAreRejected(String text) {
    assertThatThrownBy(() -> Address.parse(text)).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest='Address*Test' test`
Expected: 编译失败——`cannot find symbol: class Address`

- [ ] **Step 4: 实现 `Address`**

```java
package io.mosire.simos.util.address;

import java.util.List;

/**
 * 地址：段序列。首段是命名空间、第 2 段是该命名空间的根主体（spec §3.2）。
 *
 * <p>解析宽容（人类形式），{@link #canonical()} 唯一（机器协议只用这个，spec §3.4）。
 */
public record Address(List<AddressSegment> segments) {

  public Address {
    segments = List.copyOf(segments);
    if (segments.size() < 2) {
      throw new IllegalArgumentException("地址至少两段（命名空间 + 根主体）：" + segments);
    }
    if (!(segments.get(0) instanceof Namespace)) {
      throw new IllegalArgumentException("地址首段必须是命名空间：" + segments.get(0));
    }
  }

  public static Address parse(String text) {
    return AddressParser.parse(text);
  }

  /** 首段的命名空间标识，例如 `map`。 */
  public String namespace() {
    return ((Namespace) segments.get(0)).ident();
  }

  public String canonical() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segments.size(); i++) {
      if (i > 0) {
        sb.append(':');
      }
      sb.append(segments.get(i).canonical());
    }
    return sb.toString();
  }
}
```

- [ ] **Step 5: 实现 `AddressParser`**

```java
package io.mosire.simos.util.address;

import java.util.ArrayList;
import java.util.List;

/**
 * 地址解析：切段（`:`，引号内的不切）、宽容写法（`.[`）、按位置与表面形式判定段类型（spec §3.2/§3.5）。
 * 包内可见——对外只有 {@link Address#parse(String)}。
 */
final class AddressParser {

  private AddressParser() {}

  static Address parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("地址不得为空");
    }
    List<String> tokens = new ArrayList<>();
    List<String> raw = splitOnColons(text);
    for (int i = 0; i < raw.size(); i++) {
      String token = raw.get(i);
      if (token.isEmpty()) {
        throw new IllegalArgumentException("第 " + (i + 1) + " 段为空：`" + text + "`");
      }
      tokens.addAll(splitTolerantDot(token, text));
    }
    if (tokens.size() < 2) {
      throw new IllegalArgumentException("地址至少两段（命名空间 + 根主体）：`" + text + "`");
    }
    List<AddressSegment> segments = new ArrayList<>(tokens.size());
    for (int i = 0; i < tokens.size(); i++) {
      segments.add(toSegment(tokens.get(i), i, text));
    }
    return new Address(segments);
  }

  /** 按 `:` 切段；引号内的 `:` 按字面处理（连续两个 `"` 是转义，来回抵消）。 */
  private static List<String> splitOnColons(String text) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (c == ':' && !inQuotes) {
        out.add(cur.toString());
        cur.setLength(0);
        continue;
      }
      cur.append(c);
    }
    if (inQuotes) {
      throw new IllegalArgumentException("引号未闭合：`" + text + "`");
    }
    out.add(cur.toString());
    return out;
  }

  /** 兼容写法：未加引号的 `.` 紧跟 `[` 时在此断开（`Map1.[4,3]` → `Map1` + `[4,3]`）。 */
  private static List<String> splitTolerantDot(String token, String whole) {
    int idx = indexOfUnquotedDotBeforeBracket(token);
    if (idx < 0) {
      return List.of(token);
    }
    String left = token.substring(0, idx);
    String right = token.substring(idx + 1);
    if (left.isEmpty() || right.isEmpty()) {
      throw new IllegalArgumentException("兼容写法 `.` `[` 的两侧不得为空：`" + token + "`（地址：`" + whole + "`）");
    }
    return List.of(left, right);
  }

  private static int indexOfUnquotedDotBeforeBracket(String token) {
    boolean inQuotes = false;
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (!inQuotes && c == '.' && i + 1 < token.length() && token.charAt(i + 1) == '[') {
        return i;
      }
    }
    return -1;
  }

  /** 段类型判定（spec §3.2 表）：位置 + 表面形式，没有隐式兜底。 */
  private static AddressSegment toSegment(String token, int position, String whole) {
    if (position == 0) {
      if (!AddressText.isBareWord(token)) {
        throw new IllegalArgumentException("首段必须是裸词的命名空间：`" + token + "`（地址：`" + whole + "`）");
      }
      return new Namespace(token);
    }
    if (token.charAt(0) == '[') {
      return toIndex(token, whole);
    }
    int dot = indexOfUnquotedDot(token);
    if (dot < 0) {
      if (isQuoted(token)) {
        return Entity.of(unquote(token));
      }
      if (!AddressText.isBareWord(token)) {
        throw new IllegalArgumentException(
            "第 " + (position + 1) + " 段既不是裸词也不是引号包裹的名字：`" + token + "`（地址：`" + whole + "`）");
      }
      return position == 1 ? Entity.of(token) : new Property(token);
    }
    String left = token.substring(0, dot);
    String right = token.substring(dot + 1);
    if (isQuoted(left)) {
      return Entity.of(nameParts(token, whole));
    }
    if (!AddressText.isBareWord(left)) {
      throw new IllegalArgumentException(
          "kind 必须是裸词或整段加引：`" + left + "`（地址：`" + whole + "`）");
    }
    return Entity.of(left, nameParts(right, whole));
  }

  private static Index toIndex(String token, String whole) {
    if (!token.endsWith("]")) {
      throw new IllegalArgumentException("Index 段未闭合：`" + token + "`（地址：`" + whole + "`）");
    }
    String body = token.substring(1, token.length() - 1);
    if (body.isEmpty()) {
      throw new IllegalArgumentException("Index 段不得为空：`" + token + "`（地址：`" + whole + "`）");
    }
    List<Integer> coords = new ArrayList<>();
    for (String part : body.split(",", -1)) {
      try {
        coords.add(Integer.parseInt(part.strip()));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "Index 坐标不是整数：`" + part + "`（地址：`" + whole + "`）", e);
      }
    }
    return new Index(coords);
  }

  /** 第一个不在引号内的 `.`，没有则返回 -1。 */
  private static int indexOfUnquotedDot(String token) {
    return indexOfUnquotedDotFrom(token, 0);
  }

  private static int indexOfUnquotedDotFrom(String token, int from) {
    boolean inQuotes = false;
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (!inQuotes && c == '.' && i >= from) {
        return i;
      }
    }
    return -1;
  }

  /** 名字部分：按未加引号的 `.` 切组件，各组件去引号后以 `.` 连接（spec §3.2）。 */
  private static String nameParts(String part, String whole) {
    StringBuilder sb = new StringBuilder();
    int start = 0;
    while (true) {
      int dot = indexOfUnquotedDotFrom(part, start);
      String component = dot < 0 ? part.substring(start) : part.substring(start, dot);
      sb.append(componentText(component, whole));
      if (dot < 0) {
        return sb.toString();
      }
      sb.append('.');
      start = dot + 1;
    }
  }

  private static String componentText(String component, String whole) {
    if (isQuoted(component)) {
      return unquote(component);
    }
    if (AddressText.isBareWord(component)) {
      return component;
    }
    throw new IllegalArgumentException(
        "名字组件必须加引号（含 : [ ] \" 或空白）：`" + component + "`（地址：`" + whole + "`）");
  }

  /** 整个 token 是否恰好是一个引号包裹的段（`""` 转义计入内容）。 */
  private static boolean isQuoted(String s) {
    if (s.length() < 2 || s.charAt(0) != '"') {
      return false;
    }
    int i = 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"') {
        if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
          i += 2;
          continue;
        }
        return i == s.length() - 1;
      }
      i++;
    }
    return false;
  }

  /** 引号包裹 token 的内容：`""` → `"`。 */
  private static String unquote(String s) {
    return s.substring(1, s.length() - 1).replace("\"\"", "\"");
  }
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest='Address*Test' test`
Expected: PASS（15 条冻结样例 + 段判定用例 + 11 条非法形态）

- [ ] **Step 7: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/address/Address.java simos-util/src/main/java/io/mosire/simos/util/address/AddressParser.java simos-util/src/test/java/io/mosire/simos/util/address/
git diff --cached --stat
git commit -m "feat(util): Address 解析（宽容）与 canonical 渲染，冻结样例逐条核对（M1 Task 2）"
```

---

