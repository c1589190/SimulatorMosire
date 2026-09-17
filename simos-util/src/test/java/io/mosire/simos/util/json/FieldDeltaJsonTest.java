package io.mosire.simos.util.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.util.state.FieldDelta;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link FieldDelta} 的 JSON 类型信息契约（M4 Task 3，台账裁定 4）。
 *
 * <p>★ 裁定 12 的边界在这里**有意的例外**：本类**只**对 {@code FieldDelta} 一个类型的 JSON 做 {@code contains} 断言——它不含任何
 * {@code Map.copyOf}/{@code Set.copyOf}（两个容器都由构造器冻结成 {@code LinkedHashMap}/{@code
 * LinkedHashSet}），同一次序列化的字节是内容的纯函数，钉得住。快照级 JSON **仍然**只断 {@code equals}（字节漂移源在领域侧）。
 *
 * <p>★ 四条变体各一条往返（{@link FieldDelta} 的 javadoc 记了类型信息决策的全部理由）；{@code Unchanged} 与 {@code Remove}
 * 各一条**单独**成用例——裁定 D 点的名场面：{@code Unchanged} 裸序列化是 {@code {}}，曾与"空集合校验"分不开，类型 id 加上之后两者才区分得开。
 */
class FieldDeltaJsonTest {

  /** 泛型绑定的载体：真实 codec 走 {@code MapChangeSet} 的 record 组件，这里用同形的 record 钉住"读入时 T 不丢"。 */
  record Box(FieldDelta<String> delta) {}

  private final ObjectMapper mapper = SimosObjectMapper.create();

  @Test
  void unchangedRoundTripsWithoutTrippingTheEmptyGuards() throws Exception {
    String json = mapper.writeValueAsString(new Box(new FieldDelta.Unchanged<>()));
    // 字节断言的许可范围见类注释：只钉 type id 的属性名与取值。
    assertThat(json).contains("\"@class\":\"unchanged\"");
    assertThat(mapper.readValue(json, Box.class).delta()).isEqualTo(new FieldDelta.Unchanged<>());
  }

  @Test
  void upsertRoundTrips() throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("k1", "v1");
    entries.put("k2", "v2");
    FieldDelta.Upsert<String> upsert = new FieldDelta.Upsert<>(entries);
    assertThat(mapper.writeValueAsString(new Box(upsert))).contains("\"@class\":\"upsert\"");
    assertThat(mapper.readValue(mapper.writeValueAsString(new Box(upsert)), Box.class).delta())
        .isEqualTo(upsert);
  }

  @Test
  void removeRoundTrips() throws Exception {
    Set<String> keys = new LinkedHashSet<>();
    keys.add("k1");
    keys.add("k2");
    FieldDelta.Remove<String> remove = new FieldDelta.Remove<>(keys);
    assertThat(mapper.writeValueAsString(new Box(remove))).contains("\"@class\":\"remove\"");
    assertThat(mapper.readValue(mapper.writeValueAsString(new Box(remove)), Box.class).delta())
        .isEqualTo(remove);
  }

  @Test
  void patchRoundTripsWithItsNestedVariants() throws Exception {
    Map<String, String> up = new LinkedHashMap<>();
    up.put("c", "3");
    Set<String> rm = new LinkedHashSet<>();
    rm.add("a");
    FieldDelta.Patch<String> patch =
        new FieldDelta.Patch<>(new FieldDelta.Upsert<>(up), new FieldDelta.Remove<>(rm));
    // Patch 嵌套了另两个变体：读入侧两层多态都要各自解析 type id（探针失败清单 ③ 的原点）。
    assertThat(mapper.readValue(mapper.writeValueAsString(new Box(patch)), Box.class).delta())
        .isEqualTo(patch);
  }

  @Test
  void valuesSurviveAsTheirBoundTypeNotAsMaps() throws Exception {
    // T 的绑定在读入侧不能丢成 Object：丢了的形态是 entries 里躺着一堆 Map，equals 必假红不了——所以单独断类型。
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("k1", "v1");
    FieldDelta.Upsert<String> back =
        (FieldDelta.Upsert<String>)
            mapper
                .readValue(
                    mapper.writeValueAsString(new Box(new FieldDelta.Upsert<>(entries))), Box.class)
                .delta();
    assertThat(back.entries().get("k1")).isInstanceOf(String.class).isEqualTo("v1");
  }
}
