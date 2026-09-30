package io.mosire.simos.app.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.core.store.Envelope;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.info.InfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 富世界（T11）：把签入的 {@code v17levant} 复刻数据集读成创世 {@link SimulationState}，供 {@link
 * io.mosire.simos.app.ShellMain} 在**空库首启时就地初始化**种入（没有"选世界"这一层）。
 *
 * <p>★ **资源就是 simos 的线格式**（{@code simos-app/src/main/resources/worlds/v17levant.json} 是 {@code
 * tools/gsimap_import.py} 产出的 checkpoint 信封，逐字节签入）。因此本类**不另写一套解析**：走 {@link Envelope#decode} +
 * 各模块自己的 {@link ModuleCodec}，与 {@code Replay} 解码 checkpoint 是**同一条真读路径**。这直接兑现 M6 的证法——"导入器写出的档能被
 * simos 真读路径读回"，也守铁律 5（状态由完整状态类型逐字段重建，不手工对齐字段）。
 *
 * <p>★ **补一个空 sd 切片**：{@code v17levant} 档早于 sd 模块（M6 时代只有 map/social/unit）。而推进与查询要求 sd 切片存在 （{@code
 * SdTimeParticipant} 等在状态里 {@code orElseThrow("装配故障")}），故此处补 {@link SdState#empty()}——若资源已带 sd
 * 则不覆盖。
 *
 * <p>★ **确定性**：无随机、无时钟。同一份资源每次调用产出逐字段相同的状态，故其值可被用例写成字面量。
 */
public final class RichWorld {

  /** 复刻数据集的 classpath 落点（由导入器产出、逐字节签入）。 */
  static final String RESOURCE = "/worlds/v17levant.json";

  /** 只服务 {@code info} 段（util 的类型；与 {@code Replay} 同一装配点）。 */
  private static final ObjectMapper INFO_MAPPER = SimosObjectMapper.create();

  private RichWorld() {}

  /**
   * 组装富世界的创世状态：坐标取自资源信封（{@code (main, 1)}）。
   *
   * <p>{@code mapId} 只做**非空白校验**（与 {@link CorridorWorld#state(String)} 同口径：{@code GameMap} 没有 id
   * 字段，状态里无处存它）。
   *
   * @param mapId 本世界的 map 称谓（非空白）
   * @throws NullPointerException {@code mapId} 为 null
   * @throws IllegalArgumentException {@code mapId} 为空白
   * @throws IllegalStateException 资源缺失、信封读不出、或有未装配 codec 的模块
   */
  public static SimulationState state(String mapId) {
    Objects.requireNonNull(mapId, "mapId");
    if (mapId.isBlank()) {
      throw new IllegalArgumentException("mapId 不得为空白: " + mapId);
    }

    Envelope.Decoded decoded = Envelope.decode(readResource());
    Map<String, ModuleCodec> codecs = codecTable();
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : decoded.modules().entrySet()) {
      ModuleCodec codec = codecs.get(entry.getKey());
      if (codec == null) {
        throw new IllegalStateException("富世界资源里有未装配 codec 的模块（资源与 codec 表不同源）: " + entry.getKey());
      }
      modules.put(entry.getKey(), codec.decodeSnapshot(entry.getValue()));
    }
    modules.computeIfAbsent(
        "sd",
        ignored ->
            new SdSnapshot(decoded.meta().ref(), decoded.meta().timestamp(), SdState.empty()));
    // ★ R2a：同 sd 的先例——v17levant 档早于 economy 模块，而"日推进/命令总线要求切片在场"（CommandBus 的
    //   slice() 找不到命名空间会响亮失败）⇒ 这里补一个**未激活**的空 economy 切片（meta 空 = §6.6 的未激活语义）。
    modules.computeIfAbsent(
        "economy",
        ignored ->
            new EconomySnapshot(
                decoded.meta().ref(), decoded.meta().timestamp(), EconomyData.empty()));
    // ★ S1 阶段 2：同 economy 的先例——v17levant 档早于 actor 模块，而"命令总线要求切片在场"（CommandBus 的
    //   slice() 找不到命名空间会响亮失败）⇒ 补一个**未激活**的空 actor 切片（meta 空 = §6.6 的未激活语义）。
    //   ★ 键名写死 "actor"，与 ActorSnapshot.namespace() 同字面（SimulationState 构造期会校验）。
    modules.computeIfAbsent(
        "actor",
        ignored ->
            new ActorSnapshot(decoded.meta().ref(), decoded.meta().timestamp(), ActorData.empty()));
    // ★ 阶段 11b/12（2026-10-01 控制方修生产缺陷）：参与者在单位带 GovFormation 时会写 gov 片，而
    //   TimeAdvance 要求 base 已有该 namespace 快照 ⇒ 升旧档/创世必须补一个空 gov 片（与 sd/economy/actor 同制）。
    modules.computeIfAbsent(
        "gov",
        ignored ->
            new GovSnapshot(decoded.meta().ref(), decoded.meta().timestamp(), GovState.empty()));
    return new SimulationState(decoded.meta(), modules, readInfo(decoded.infoJson()));
  }

  /** 七个模块 codec（与 {@code Shell} 的装配同一套类型）。 */
  private static Map<String, ModuleCodec> codecTable() {
    Map<String, ModuleCodec> codecs = new LinkedHashMap<>();
    for (ModuleCodec codec :
        List.of(
            new MapCodec(),
            new SocialCodec(),
            new UnitCodec(),
            new SdCodec(),
            new EconomyCodec(),
            new ActorCodec(),
            // ★ 阶段 10a：gov codec 与 Shell 同源。★ 阶段 11b 起 gov 片由参与者写 ⇒ 创世/升档必须补空片
            //   （见上面 computeIfAbsent("gov")），否则带 GovFormation 的单位推进会被 TimeAdvance 拒。
            new GovCodec())) {
      codecs.put(codec.namespace(), codec);
    }
    return codecs;
  }

  private static String readResource() {
    try (InputStream in = RichWorld.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("富世界资源不在 classpath: " + RESOURCE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("富世界资源读取失败: " + RESOURCE, e);
    }
  }

  private static InfoSystem readInfo(String infoJson) {
    try {
      return INFO_MAPPER.readValue(infoJson, InMemoryInfoSystem.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "富世界资源的 info 段读不出（info 是 util 的类型，其 JSON 装配在 SimosObjectMapper）", e);
    }
  }
}
