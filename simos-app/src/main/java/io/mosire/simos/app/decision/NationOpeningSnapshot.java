package io.mosire.simos.app.decision;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.simos.app.llm.LlmToolNames;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.render.ArtifactStore;
import io.mosire.simos.app.render.RenderRequest;
import io.mosire.simos.app.render.RenderService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.read.MapRenderTool;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * **国家决策人的开场快照**（P4）：空会话首轮先给决策人发一张"我的国土长什么样"的图。
 *
 * <p>★ **取景规则**：`dm.affiliation()` 是 {@link Affiliation.Nation} ⇒ {@code Nation.homeRegion()} 那个区域
 * ⇒ 该区域的**最北偏西一格**作中心（与 {@code simos.map.render} 按 {@code regionId} 取景**同一条规则**， 复用 {@link
 * MapRenderTool#labelHex}——两处各写一份的话，人与模型看到的会是两个取景中心）。
 *
 * <p>★ **半径取 6**（127 格）：足够看清一个国家的核心区与周边（最大半径 10 会给 331 格，那张图更大、也更贵）， 与工具缺省（4 = 61 格）相比多看一圈。图片大小另有
 * {@link RenderService} 的预算硬顶兜底（超限自动降采样）。
 *
 * <p>★★ **军队决策人本批不给快照**（如实记，不是漏掉）：本类取景要的是"一个区域"，而 {@code Affiliation.Army} 的取景
 * 该按哪一格、多大范围——那是另一套语义（跟着军队走？按视野半径？），没有裁决过就不该在这里编一个。
 *
 * <p>★★ **渲染失败不掀桌子**：这一张图是"额外的开场礼"，不是这一轮决策的必要条件 ⇒ 任何渲染异常都**记一行 warn 后返回空**
 * （决策人仍会正常跑，只是首轮没有图）。反过来做（让整轮因为一张图失败）是把辅助信息的重要性排到了决策本身之前。
 */
public final class NationOpeningSnapshot implements DecisionAgentRunner.OpeningSnapshot {

  private static final Logger LOG = LoggerFactory.getLogger(NationOpeningSnapshot.class);

  /** 开场取景半径（见类注：127 格，够看清核心区）。 */
  public static final int RADIUS = 6;

  private final RenderService render;

  public NationOpeningSnapshot(RenderService render) {
    this.render = Objects.requireNonNull(render, "render");
  }

  @Override
  public Optional<LlmMessage> forDecisionMaker(DecisionMaker dm, SimulationState state) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    if (!(dm.affiliation() instanceof Affiliation.Nation nation)) {
      return Optional.empty();
    }
    Region region = homeRegionOf(nation, state);
    if (region == null) {
      LOG.warn(
          "开场快照跳过：决策人 {} 的国家 {} 找不到首府区域（世界与归属不同源？）", dm.id().value(), nation.nationId().value());
      return Optional.empty();
    }
    HexCoord center = MapRenderTool.labelHex(region);
    try {
      RenderService.Rendered rendered =
          render.renderImage(targetOf(state), RenderRequest.map(center, RADIUS));
      String assetId =
          rendered.assetId().orElseThrow(() -> new IllegalStateException("图片渲染没有产出工件 id"));
      // ★ 图片消息带一句文本：既说清"这是什么"，也给"读不到图"的模型留一条可读的线索。
      return Optional.of(
          new LlmMessage(
              LlmMessage.ROLE_USER,
              List.of(
                  new ContentPart.Text(
                      "【开场快照】你的国土（"
                          + nation.nationId().value()
                          + "）所在区域的当前视图：中心 ("
                          + center.q()
                          + ","
                          + center.r()
                          + ")、半径 "
                          + RADIUS
                          + " 格。图上：地形底色、区域边界、城市与单位标记。"
                          + "要看得更细或换一块地方，用 "
                          + LlmToolNames.wireNameOf(MapRenderTool.NAME)
                          + "（可按 q/r 或 regionId 取景、可加 population 图层）。"),
                  new ContentPart.Image(ArtifactStore.PNG_MEDIA_TYPE, assetId))));
    } catch (RuntimeException e) {
      // ★ 见类注：图是附加信息，不是决策的必要条件 ⇒ 不掀桌子，但要留下痕迹（"开关开了却没图"必须可归因）。
      LOG.warn("开场快照渲染失败，本轮不带图继续决策人 {}（原因：{}）", dm.id().value(), e.getMessage());
      return Optional.empty();
    }
  }

  /** 该国家在**此刻的世界**里的首府区域（查无 ⇒ null，由调用方如实报原因）。 */
  private static Region homeRegionOf(Affiliation.Nation affiliation, SimulationState state) {
    Nation nation = ToolSupport.sdState(state).nations().get(affiliation.nationId());
    if (nation == null) {
      return null;
    }
    RegionId homeRegion = nation.homeRegion();
    GameMap map = ToolSupport.gameMap(state);
    return map.regions().get(homeRegion);
  }

  /** 这一轮看到的那一份世界（不是 head：快照必须画的是"这一轮的世界"，与工具读的是同一份）。 */
  private static QueryTarget targetOf(SimulationState state) {
    StateRef ref = state.meta().ref();
    return QueryTarget.at(ref.branch(), ref.revision());
  }
}
