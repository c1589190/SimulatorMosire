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
