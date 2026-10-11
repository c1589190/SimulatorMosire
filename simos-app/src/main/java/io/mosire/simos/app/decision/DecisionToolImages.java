package io.mosire.simos.app.decision;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.render.ArtifactStore;
import io.mosire.simos.ga.decision.ToolImages;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * **app 侧交给运行流的图片通路**（{@link ToolImages} 的实现）：把工具结果里的图资产折成一条 {@code user} 图片消息。
 *
 * <p>★★ <b>为什么这段形状住在 app 而不是 ga</b>（2026-10-12 B2a）：{@code assetId} 指的内容寻址工件库与"当前只存 PNG"
 * 这个事实都住在渲染层（{@link ArtifactStore}）——运行流（{@code simos-ga}）不认识工件库、也不该认识媒体类型； 它只决定**发不发**（该 provider
 * 有没有视觉能力 + 工具结果里真有图资产），折消息这一步交回宿主。
 *
 * <p>★ <b>媒体类型的唯一拼写点仍是 {@link ArtifactStore#PNG_MEDIA_TYPE}</b>：这里引用它、不另抄一份字面量
 * （其类注写明"本库当前只存一种；扩展时把类型写进 id/清单"——那一天这里要跟着改成按资产取类型）。
 *
 * <p>★ <b>消息形状逐字保留 P4 的既有形态</b>（一段说明文字 + 每个资产一个 {@code ContentPart.Image}）：迁出前后发出去的字节相同。
 */
public final class DecisionToolImages implements ToolImages {

  /** 唯一实例（无状态）。 */
  public static final DecisionToolImages INSTANCE = new DecisionToolImages();

  private DecisionToolImages() {}

  @Override
  public Optional<LlmMessage> messageFor(ToolResult result) {
    List<String> assetIds = result.assetDocIds();
    if (assetIds.isEmpty()) {
      return Optional.empty();
    }
    List<ContentPart> parts = new ArrayList<>(assetIds.size() + 1);
    parts.add(new ContentPart.Text("（下面是上一次工具结果里的图片）"));
    for (String assetId : assetIds) {
      parts.add(new ContentPart.Image(ArtifactStore.PNG_MEDIA_TYPE, assetId));
    }
    return Optional.of(new LlmMessage(LlmMessage.ROLE_USER, parts));
  }
}
