package io.mosire.simos.gov.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.gov.tools.GovNationViews;
import io.mosire.simos.gov.tools.GovToolReads;
import io.mosire.simos.gov.tools.NationRenderPort;
import io.mosire.simos.map.GameMap;
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
 * {@code simos.gov.nations}（GM-only）：国家并集、颜色、名称、区域与 GOV 关系的统一读视图；可选地图 PNG。
 *
 * <p>★ 与 {@code simos.gov.editNation} 和网页共用 {@link GovNationViews}：国家领土按精确 {@code nation:<id>}
 * 标签合并去重， 名称取 {@code Nation.name}，颜色取区域元数据（优先 homeRegion）。
 *
 * <p>★ 图片经 {@link NationRenderPort} 交给 app 的同一渲染服务/工件链，工具结果把 assetId 放进结果（MCP {@code
 * ImageContent}）。
 */
public final class NationsTool implements SimosTool, GmOnlyRead {

  public static final String NAME = "simos.gov.nations";

  private final ToolReadPort reads;
  private final NationRenderPort render;

  public NationsTool(ToolReadPort reads, NationRenderPort render) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.render = Objects.requireNonNull(render, "render");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 读国家视图：省略 nationId 列出全部国家；给 nationId 看单个国家。返回精确 nation:<id> 标签区域的并集（去重）、"
        + "真实 Nation.name、颜色、homeRegionId、regionIds 与 jurisdiction 覆盖该国的 GOV。"
        + "render=true 时按领土取景出真 PNG（assetId 随结果出站）。参数 {nationId?, render?, branch?, revision?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("nationId", ToolSchema.prop("string", "国家 id（可选；省略=全部）"));
    props.put("render", ToolSchema.prop("boolean", "true=出 PNG；缺省：给了 nationId 为 true，列全部为 false"));
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
      String nationIdText = ToolSchema.optionalText(args, "nationId", null);
      boolean renderPng = ToolSchema.optionalBoolean(args, "render").orElse(nationIdText != null);
      BranchId branch = GovToolReads.branch(args);
      RevisionId revision = GovToolReads.revision(args);
      SimulationState state = reads.stateAt(branch, revision);
      GovState gov = GovToolReads.govState(state);
      GameMap map = GovToolReads.gameMap(state);
      UnitState units = GovToolReads.unitState(state);

      List<GovNationViews.NationView> views;
      if (nationIdText == null) {
        views = GovNationViews.all(state, gov, map, units);
      } else {
        NationId nationId = NationId.parse(nationIdText);
        GovNationViews.NationView view =
            GovNationViews.find(state, gov, map, units, nationId)
                .orElseThrow(() -> new IllegalArgumentException("国家不存在: " + nationId));
        views = List.of(view);
      }
      List<Map<String, Object>> payloads = new ArrayList<>(views.size());
      for (GovNationViews.NationView view : views) {
        payloads.add(GovNationViews.toPayload(view));
      }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("branch", state.meta().ref().branch().value());
      out.put("revision", state.meta().ref().revision().value());
      out.put("tick", state.meta().timestamp().tick());
      out.put("count", payloads.size());
      out.put("nations", List.copyOf(payloads));
      out.put("note", "领土=精确 nation:<id> 标签区域的并集；GOV 关系为 jurisdiction 覆盖该国区域的派生展示，不是持久字段。");

      if (!renderPng) {
        return ToolResults.ok(out);
      }
      if (views.isEmpty()) {
        // 空世界列表是正常状态；没有国家时明确跳过图，不 get(0) 越界、不伪造一张空图。
        out.put("render", Map.of("skipped", "世界上还没有国家，未出图"));
        return ToolResults.ok(out);
      }
      if (nationIdText == null) {
        throw new IllegalArgumentException("render=true 必须同时给 nationId（列表模式不猜测要渲染哪一个国家；空列表会明确跳过图）");
      }
      GovNationViews.NationView first = views.get(0);
      if (first.hexes().isEmpty()) {
        out.put("render", Map.of("skipped", "国家领土为空（不会出假图）"));
        return ToolResults.ok(out);
      }
      NationRenderPort.Rendered rendered = render.render(first);
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
          "TOOL_ERROR", "nations 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
