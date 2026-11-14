package io.mosire.simos.army.tools;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 军队编制 PNG 渲染口（{@code simos.army.formation} 专用）：把完整树画成真图片，落既有工件库。
 *
 * <p>★★ <b>为什么必须注入</b>：Java2D/PNG/工件库都在 app 渲染层；army 模块不存储、不引 app。组合根注入实现，工具只把 assetId 放进结果（MCP
 * {@code ImageContent}）。
 */
public interface FormationRenderPort {

  /** 渲染所需的单个节点（人数字段与读视图同义）。 */
  record Node(
      String id,
      String name,
      String role,
      String parentId,
      int depth,
      boolean attached,
      long directPersonnel,
      long subtreePersonnel,
      Integer q,
      Integer r) {
    public Node {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
    }
  }

  /** 一次渲染的产物。 */
  record Rendered(String text, Optional<String> assetId, int byteSize, int width, int height) {
    public Rendered {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(assetId, "assetId");
    }
  }

  /**
   * 画整棵树（高度受限、超宽自动缩放；文本摘要给出节点数与直接/子树人数）。
   *
   * @param branch 分支
   * @param revision 版本；{@code null} = head
   * @param armyId 军队 id
   * @param armyName 军队名
   * @param nodes 完整节点列表（不得静默丢节点）
   */
  Rendered render(
      BranchId branch, RevisionId revision, String armyId, String armyName, List<Node> nodes);
}
