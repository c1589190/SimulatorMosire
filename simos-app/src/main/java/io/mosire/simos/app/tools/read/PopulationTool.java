package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.social.population}（spec §7.1 读工具）：某格在 head 时刻的人口取值。
 *
 * <p>★★ **R1.5 起体里有"两笔账"**（见 {@link io.mosire.simos.app.gui.ApiViews#population}）：{@code
 * population} = 该格<b>农村序列</b>在 head 的取值（旧口径，本工具 R1.5 之前只有它），{@code groups} = 该格<b>批次</b>的现算读数
 * （{@code total}/{@code urban}/{@code rural}/{@code ageBrackets}/{@code sex}）—— 于是"某格有多少人、都是谁"与
 * GUI（{@code GET /api/social/population}）**同一份视图**发出去，年龄与性别不再只在状态里、面外看不见。
 *
 * <p>★ **视图只有一份**：体由 {@link ToolSupport#population} 转调 {@code ApiViews.population}（AGENT.md §8.3）——
 * 本类不拼任何字段。
 *
 * <p>★ 该格无人口序列 ⇒ {@code NOT_FOUND}（与"这一格你看不见"**拒因逐字相同**，不给存在性侧信道）。
 */
public final class PopulationTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.social.population";

  private final QueryService query;

  public PopulationTool(QueryService query) {
    this.query = query;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "查某格在 head 时刻的人口：population（农村序列取值）+ groups（批次现算：total/urban/rural、"
        + "年龄档 0-14·15-59·60+、性别 MALE/FEMALE）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("q", ToolSupport.prop("integer", "六角列坐标 q"));
    props.put("r", ToolSupport.prop("integer", "六角行坐标 r"));
    return ToolSupport.schema(props, List.of("q", "r"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.SOCIAL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      HexCoord coord =
          new HexCoord(
              (int) ToolSupport.requiredLong(args, "q"), (int) ToolSupport.requiredLong(args, "r"));
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      SocialData social = ToolSupport.socialData(state);
      // ★ **人口按格判可见性**（T10；spec §3.3 的 social 路径 = `<q>_<r>`，见 ToolSupport#resourceSocial）；
      //   不可见与"该格没有人口序列"同款——拒因逐字相同，不泄露"有数但你看不到"。
      //   ★ R1.5：判据仍是"这一格有没有人口序列"（**没有放宽**）——批次的落点由 R1 的跨组件校验钉在"有序列的格"上，
      //     故本判据恰好也是"这一格有没有批次"；R1.5 加出来的三个派生量（年龄/性别/城乡）都在**同一条**资源
      //     `social:<q>_<r>` 之内，不新开权限面、也不许另造一个更宽的。
      if (!social.populations().containsKey(coord)
          || !ToolSupport.populationVisible(context, coord)) {
        return ToolResult.error("NOT_FOUND", "该格没有人口序列: " + coord.q() + "_" + coord.r());
      }
      return ToolSupport.ok(ToolSupport.population(social, coord, state.meta().timestamp()));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
