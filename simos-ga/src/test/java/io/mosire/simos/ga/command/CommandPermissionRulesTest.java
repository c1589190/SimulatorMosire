package io.mosire.simos.ga.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.DmUsableCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>反默认的判别力</b>（2026-10-12 批次 1「权限统一」，用户裁定「默认仅 GM，DM 需显式声明」）。
 *
 * <p>★ <b>被钉的命题</b>：<b>新加一条命令（已注册进 catalog）但既不打 {@link DmUsableCommand}、也不打任何标记 ⇒ 它必须</b>
 *
 * <ol>
 *   <li><b>出现在</b> {@code catalogTypes}（GM 能用 {@code simos.command.submit} 提交它）；
 *   <li><b>不出现在</b> {@code directiveTypes}（不得被嵌进决策令）；
 *   <li><b>不出现在</b> {@code drainableTypes}（不得入效果队列）。
 * </ol>
 *
 * <p>★ <b>为什么这条是本批的核心判据</b>：迁移前命令面是"<b>默认放行</b>、少数打反向标记排除"——新加一条命令不写任何东西就 <b>自动进令白名单</b>；本批把它翻成"默认仅
 * GM、显式声明才给 DM"。本用例就是那条翻转的负向证明：把 {@code derive} 改回"未打标记的也进 DM 面" ⇒ ②③ 两条断言<b>当场红</b>（变异自证见批报告）。
 *
 * <p>★ <b>纯单元测试</b>（合成 type 集合，不起真 {@code Shell}）：真装配面那三个面的具体计数由 {@code
 * AdjudicateTickToolTest}（66）/ {@code SimosToolsTest}（142）/ {@code McpCoverageTest}（142）另钉；
 * 本类只回答"<b>声明与四个面的推导关系对不对</b>"。
 */
class CommandPermissionRulesTest {

  /** 合成注册面：两条打了标记、一条落点（不打标记）、一条"新加进来的"（不打任何标记）。 */
  private static final Set<String> REGISTERED =
      Set.of("unit.RenameUnit", "map.CreateRegion", "ga.IssueDirective", "new.UntaggedCommand");

  /** 只有它们打了 {@link DmUsableCommand}（= 显式声明"可 DM"）。 */
  private static final Set<String> DM_USABLE = Set.of("unit.RenameUnit", "map.CreateRegion");

  /** 决策落点（{@code Shell} 送 {@code ChannelAdmission.LANDING_POINTS}；本轮审计要求它 ⊆ 注册面）。 */
  private static final Set<String> LANDING = Set.of("ga.IssueDirective");

  @Test
  void aNewCommandWithoutAnyMarkerIsInTheCatalogButInNeitherDmFace() {
    CommandFaces faces = CommandPermissionRules.derive(REGISTERED, DM_USABLE, LANDING);

    assertThat(faces.catalogTypes())
        .as("① 注册面 = catalog：新命令**必须**在里面（否则 GM 连提交都提交不了）")
        .contains("new.UntaggedCommand");
    assertThat(faces.directiveTypes())
        .as("② 可嵌令面：不打 DmUsableCommand ⇒ 不得出现（旧行为下这里会**默认放行**）")
        .doesNotContain("new.UntaggedCommand");
    assertThat(faces.drainableTypes())
        .as("③ 可入队面：同一声明 ⇒ 同样不得出现")
        .doesNotContain("new.UntaggedCommand");

    // ★ 正向对照：打了 DmUsableCommand 的那条，②③ 两个面都在（证明②③不是恒空——空集另有 audit 会抛）。
    assertThat(faces.directiveTypes())
        .as("正向对照：打标记者进可嵌令面")
        .contains("unit.RenameUnit", "map.CreateRegion");
    assertThat(faces.drainableTypes())
        .as("正向对照：打标记者进可入队面")
        .contains("unit.RenameUnit", "map.CreateRegion");

    // ★ 落点（sd.IssueDirective）与"不打标记"同待遇：不在 DM 两面里。
    assertThat(faces.directiveTypes()).doesNotContain("ga.IssueDirective");
    assertThat(faces.drainableTypes()).doesNotContain("ga.IssueDirective");

    // ★ 口径钉死：②③ 是**同一份声明**的两种用法（内容逐条相同），本轮不得分家。
    assertThat(faces.directiveTypes()).containsExactlyInAnyOrderElementsOf(faces.drainableTypes());
    assertThat(faces.directiveTypes()).as("66 条声明面在这个合成面上就是这 2 条，不多不少").hasSize(2);
    assertThat(faces.landingPoints()).containsExactly("ga.IssueDirective");
  }

  /**
   * {@code declare(...)} 是**唯一读标记的地方**：只有带标记的 handler 进声明面；{@code catalogTypes(...)} 则与标记无关
   * （catalog = 注册面本身）。
   */
  @Test
  void declareCollectsOnlyTheMarkedHandlersWhileCatalogIsTheWholeRegistrationFace() {
    List<CommandHandler> handlers =
        List.of(new MarkedHandler("unit.RenameUnit"), new PlainHandler("new.UntaggedCommand"));

    assertThat(CommandPermissionRules.declare(handlers).dmUsableTypes())
        .as("只收带 DmUsableCommand 的那些（保序）")
        .containsExactly("unit.RenameUnit");
    assertThat(CommandPermissionRules.catalogTypes(handlers))
        .as("catalogTypes 是注册面本身：打不打标记都在里面（保序）")
        .containsExactly("unit.RenameUnit", "new.UntaggedCommand");
  }

  /** 合成 handler：本用例只读 {@code type()} 与标记，{@code handle} 不会被调用。 */
  private static class PlainHandler implements CommandHandler {

    private final String type;

    PlainHandler(String type) {
      this.type = type;
    }

    @Override
    public String type() {
      return type;
    }

    @Override
    public HandlerOutcome handle(SimulationState state, String payloadJson) {
      throw new UnsupportedOperationException("本用例只读 type()/标记，不执行 handler");
    }
  }

  /** 带 {@link DmUsableCommand} 的合成 handler（"显式声明可 DM"的那一类）。 */
  private static final class MarkedHandler extends PlainHandler implements DmUsableCommand {

    MarkedHandler(String type) {
      super(type);
    }
  }
}
