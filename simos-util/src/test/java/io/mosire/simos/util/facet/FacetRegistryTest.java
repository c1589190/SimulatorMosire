package io.mosire.simos.util.facet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** spec §八：facetName 唯一；queryAll 按注册序；空列表不是错误。 */
class FacetRegistryTest {

  private static final Address HEX = Address.parse("map:Map1:hex.4_3");

  @Test
  void queryAllConcatenatesInRegistrationOrder() {
    FacetRegistry registry = new FacetRegistry();
    // 注册序**故意不取字母序**：若按字母序注册，"注册序"这条断言换成 TreeMap 实现也照样全绿，
    // 等于空转护栏。unitsHere → population 之后，LinkedHashMap 绿、任何排序实现红
    // （字母序恰是 population → unitsHere）。
    registry.register(provider("unit", "unitsHere", entry("unit", "unitsHere", List.of("U-1"))));
    registry.register(provider("social", "population", entry("social", "population", 10_000)));
    assertThat(registry.facetNames()).containsExactly("unitsHere", "population"); // 注册序，非排序
    assertThat(registry.queryAll(HEX, context()).stream().map(FacetEntry::label).toList())
        .containsExactly("unitsHere", "population");
  }

  @Test
  void duplicateFacetNameIsRejected() {
    FacetRegistry registry = new FacetRegistry();
    registry.register(provider("unit", "unitsHere", entry("unit", "unitsHere", List.of())));
    assertThatThrownBy(
            () ->
                registry.register(
                    provider("unit", "unitsHere", entry("unit", "unitsHere", List.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unitsHere");
  }

  @Test
  void aProviderWithNothingToSayIsNotAnError() {
    FacetRegistry registry = new FacetRegistry();
    registry.register(provider("unit", "unitsHere")); // 返回空列表
    assertThat(registry.queryAll(HEX, context())).isEmpty();
  }

  @Test
  void valuesStayStructured() {
    // spec §八：value 是结构化 Object，不是预先格式化的字符串。
    FacetEntry entry = entry("social", "population", Map.of("total", 10_000, "growth", 0.03));
    assertThat(entry.value()).isInstanceOf(Map.class);
  }

  @Test
  void blankOrNullPartsAreRejected() {
    // 断言钉到**消息**：三条空白守卫的消息各不相同，只断异常类型的话把它们互换实现也照绿。
    assertThatThrownBy(() -> new FacetEntry(" ", "label", "Type", "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
    assertThatThrownBy(() -> new FacetEntry(null, "label", "Type", "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
    assertThatThrownBy(() -> new FacetEntry("unit", " ", "Type", "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("label");
    // label / typeName 的 **null 半边**同样要自证：缺了这条，把守卫写成 `label != null && label.isBlank()`
    // 也照样全绿，null 会被静默存进 record（namespace 因上面两条而不会漏，这两条补上同样的覆盖）。
    assertThatThrownBy(() -> new FacetEntry("unit", null, "Type", "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("label");
    assertThatThrownBy(() -> new FacetEntry("unit", "label", " ", "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typeName");
    assertThatThrownBy(() -> new FacetEntry("unit", "label", null, "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typeName");
    assertThatThrownBy(() -> new FacetEntry("unit", "label", "Type", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("value"); // 空值走 Objects.requireNonNull，不裹成 IAE
  }

  @Test
  void nullArgumentsAreRejectedWithFieldLevelMessages() {
    // 三条 `requireNonNull` 一律用 **`hasMessage` 精确匹配**：其消息恰是字段名本身，而删掉守卫后
    // 紧接着的解引用（`provider.facetName()`）会抛 JDK 21 的热心 NPE，消息形如
    // `Cannot invoke "..." because "provider" is null`——**同样含该字段名**，
    // 故 `hasMessageContaining("provider")` 对守卫的存废毫无判别力（T7 `"t"` 的同一形态）。
    FacetRegistry registry = new FacetRegistry();
    assertThatThrownBy(() -> registry.register(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("provider");
    assertThatThrownBy(() -> registry.register(provider("unit", " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空白");
    // null 走同一条守卫：只写 isBlank() 的实现会在此抛 NPE，本断言转红。
    assertThatThrownBy(() -> registry.register(provider("unit", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空白");
    assertThatThrownBy(() -> registry.queryAll(null, context()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("subject");
    assertThatThrownBy(() -> registry.queryAll(HEX, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("ctx");
  }

  @Test
  void aProviderReturningNullIsRejected() {
    // 返回 null 与"返回空列表"是两回事：前者是提供者的 bug，后者是"我这一面没内容"。
    // 少了这条守卫，实现会以 NPE 的形态倒下——响亮，但指错了地方。
    FacetRegistry registry = new FacetRegistry();
    registry.register(
        new FacetProvider() {

          @Override
          public String facetName() {
            return "unitsHere";
          }

          @Override
          public List<FacetEntry> query(Address subject, ResolveContext ctx) {
            return null;
          }
        });
    assertThatThrownBy(() -> registry.queryAll(HEX, context()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unitsHere");
  }

  @Test
  void facetNamesIsDefensivelyCopiedAndImmutable() {
    FacetRegistry registry = new FacetRegistry();
    assertThat(registry.facetNames()).isEmpty(); // 空注册表不是错误
    registry.register(provider("unit", "unitsHere"));
    List<String> names = registry.facetNames();
    assertThatThrownBy(() -> names.add("population"))
        .isInstanceOf(UnsupportedOperationException.class);
    // 取到的是**快照**而非视图：后续注册不得改变已取出的列表
    // （返回 Collections.unmodifiableList(providers.keySet()) 这类"不可改但仍是视图"的实现会在此转红）。
    registry.register(provider("social", "population"));
    assertThat(names).containsExactly("unitsHere");
    assertThat(registry.facetNames()).containsExactly("unitsHere", "population");
  }

  private static FacetProvider provider(String namespace, String facetName, FacetEntry... entries) {
    return new FacetProvider() {

      @Override
      public String facetName() {
        return facetName;
      }

      @Override
      public List<FacetEntry> query(Address subject, ResolveContext ctx) {
        return List.of(entries);
      }
    };
  }

  private static FacetEntry entry(String namespace, String label, Object value) {
    return new FacetEntry(namespace, label, "Toy", value);
  }

  private static ResolveContext context() {
    return new ResolveContext(
        new SimulationState(
            new StateMeta(
                new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(1)),
            Map.of(),
            InMemoryInfoSystem.empty()),
        SimosTimestamp.of(1));
  }
}
