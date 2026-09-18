package io.mosire.simos.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CommandRegistry} 的护栏用例：**构造期的两处校验**——R12（同 type 两次）与裁定 37（type 的命名空间形状）。
 *
 * <p>★ 两者都是"少一次校验就少一次响"的形态：静默覆盖会让一个模块的 handler **永远不生效且不报错**； 缺命名空间会让 Core 在建 {@code
 * WorldChangeSet} 时**切不出键**。所以断言钉的是**构造期就抛**，不是"用时才炸"。
 */
class CommandRegistryTest {

  @Test
  void byTypeFindsRegisteredHandler() {
    CommandHandler rename = handler("unit.RenameUnit");
    CommandHandler move = handler("map.MoveUnit");
    CommandRegistry registry = new CommandRegistry(List.of(rename, move));

    assertThat(registry.byType("unit.RenameUnit")).contains(rename);
    assertThat(registry.byType("map.MoveUnit")).contains(move);
  }

  /** 未注册 ⇒ 空，**不抛**：怎么拒绝归调用方（`CommandBus` 折成 `Rejected`），注册表不替它决定。 */
  @Test
  void unknownTypeIsEmptyNotAnError() {
    CommandRegistry registry = new CommandRegistry(List.of(handler("unit.RenameUnit")));

    assertThat(registry.byType("social.Populate")).isEmpty();
  }

  /** R12：同 type 注册两次 ⇒ **构造期**抛，且消息里点名两个肇事类（不然排查得靠猜）。 */
  @Test
  void duplicateTypeFailsAtConstruction() {
    CommandHandler first = handler("unit.RenameUnit");
    CommandHandler second = handler("unit.RenameUnit");

    assertThatThrownBy(() -> new CommandRegistry(List.of(first, second)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同一 type 注册了两次")
        .hasMessageContaining("unit.RenameUnit")
        .hasMessageContaining(first.getClass().getName())
        .hasMessageContaining(second.getClass().getName());
  }

  /** 裁定 37：type 不含 `.` ⇒ 构造期抛（Core 切不出 namespace，装不进 WorldChangeSet）。 */
  @Test
  void typeWithoutNamespaceFailsAtConstruction() {
    assertThatThrownBy(() -> new CommandRegistry(List.of(handler("RenameUnit"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("<namespace>.<Command>");
  }

  /** 裁定 37 的两个边界：`.` 在首/尾都不算命名空间（`".x"` 切出空串、`"x."` 切出的是类名空）。 */
  @Test
  void typeWithLeadingOrTrailingDotFailsAtConstruction() {
    assertThatThrownBy(() -> new CommandRegistry(List.of(handler(".RenameUnit"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("<namespace>.<Command>");

    assertThatThrownBy(() -> new CommandRegistry(List.of(handler("unit."))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("<namespace>.<Command>");
  }

  /** 空表是合法的（Core 只处理自己两条命令时就是这个形态），且 `types()` 返回空集而不是 null。 */
  @Test
  void emptyRegistryIsLegal() {
    CommandRegistry registry = new CommandRegistry(List.of());

    assertThat(registry.types()).isEmpty();
    assertThat(registry.byType("unit.RenameUnit")).isEmpty();
  }

  /** 替身：只借 `type()`，`handle` 永不参与本用例的断言。 */
  private static CommandHandler handler(String type) {
    return new CommandHandler() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public HandlerOutcome handle(SimulationState state, String payloadJson) {
        return new HandlerOutcome.Rejected("替身不执行");
      }
    };
  }
}
