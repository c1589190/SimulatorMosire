package io.mosire.simos.army.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.tools.ArmyFormationViews;
import io.mosire.simos.army.tools.ArmyToolReads;
import io.mosire.simos.army.tools.FormationRenderPort;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code simos.army.formation}（GM-only）：无 armyId 列出全部军队；有 armyId 返回完整组织树、直接/子树人数、装备、位置与 PNG。
 *
 * <p>★ 树来自 unit 的 parent 森林，人数来自 Social；本工具不存第二棵树、不算第二本人数。PNG 经 {@link FormationRenderPort} 出站为
 * assetId（MCP {@code ImageContent}）。
 */
public final class FormationTool implements SimosTool, GmOnlyRead {

  public static final String NAME = "simos.army.formation";

  private final ToolReadPort reads;
  private final FormationRenderPort render;

  public FormationTool(ToolReadPort reads, FormationRenderPort render) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.render = Objects.requireNonNull(render, "render");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 读军队完整编制：省略 armyId 列出全部军队（节点数/人数/根位置/GOV）；给 armyId 返回完整树，"
        + "每节点含 stable id、名称、role、masterGov、parentId、depth、attached（true=随父移动，false=独立机动线）、"
        + "position、本级直接人数 directPersonnel、子树合计 subtreePersonnel、装备、速度与 childIds。"
        + "render=true（给 armyId 时缺省 true）出整棵树的 PNG（大图高度受限、不静默漏节点）。"
        + "人数直接读 Social 家户，不读任何 Unit 人力字段。参数 {armyId?, render?, branch?, revision?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("armyId", ToolSchema.prop("string", "军队 id（可选；省略=列表）"));
    props.put("render", ToolSchema.prop("boolean", "true=出编制 PNG；缺省：给了 armyId 为 true，列表为 false"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String armyIdText = ToolSchema.optionalText(args, "armyId", null);
      boolean renderPng = ToolSchema.optionalBoolean(args, "render").orElse(armyIdText != null);
      if (renderPng && armyIdText == null) {
        throw new IllegalArgumentException("render=true 必须同时给 armyId（不渲染含糊的列表）");
      }
      BranchId branch = ArmyToolReads.branch(args);
      RevisionId revision = ArmyToolReads.revision(args);
      SimulationState state = reads.stateAt(branch, revision);
      ArmyData armies = ArmyToolReads.armyData(state);
      UnitState units = ArmyToolReads.unitState(state);
      SocialData social = ArmyToolReads.socialData(state);

      List<ArmyFormationViews.FormationView> views;
      if (armyIdText == null) {
        views = ArmyFormationViews.all(state, armies, units, social);
      } else {
        ArmyId armyId = ArmyId.parse(armyIdText);
        views =
            List.of(
                ArmyFormationViews.find(state, armies, units, social, armyId)
                    .orElseThrow(() -> new IllegalArgumentException("军队不存在: " + armyId)));
      }
      List<Map<String, Object>> payloads = new ArrayList<>(views.size());
      for (ArmyFormationViews.FormationView view : views) {
        payloads.add(ArmyFormationViews.toPayload(view));
      }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("branch", state.meta().ref().branch().value());
      out.put("revision", state.meta().ref().revision().value());
      out.put("tick", state.meta().timestamp().tick());
      out.put("count", payloads.size());
      out.put("armies", List.copyOf(payloads));

      if (!renderPng) {
        return ToolResults.ok(out);
      }
      ArmyFormationViews.FormationView view = views.get(0);
      List<FormationRenderPort.Node> nodes = new ArrayList<>(view.nodes().size());
      for (ArmyFormationViews.NodeView node : view.nodes()) {
        nodes.add(
            new FormationRenderPort.Node(
                node.id(),
                node.name(),
                node.role(),
                node.parentId(),
                node.depth(),
                node.attached(),
                node.directPersonnel(),
                node.subtreePersonnel(),
                node.q(),
                node.r()));
      }
      FormationRenderPort.Rendered rendered =
          render.render(branch, revision, view.armyId(), view.name(), List.copyOf(nodes));
      Map<String, Object> renderView = new LinkedHashMap<>();
      renderView.put("summary", rendered.text());
      rendered.assetId().ifPresent(assetId -> renderView.put("assetId", assetId));
      renderView.put("byteSize", rendered.byteSize());
      renderView.put("width", rendered.width());
      renderView.put("height", rendered.height());
      out.put("render", renderView);
      if (rendered.assetId().isPresent()) {
        return ToolResult.ok(ToolResults.json(out), List.of(rendered.assetId().orElseThrow()));
      }
      return ToolResults.ok(out);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "formation 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
