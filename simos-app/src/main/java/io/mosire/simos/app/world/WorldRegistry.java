package io.mosire.simos.app.world;

import io.mosire.simos.util.state.SimulationState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * **世界注册表**（P1.1）：{@code worldId -> 创世生成器} 的唯一登记点。
 *
 * <p>★ **它解决哪件事**：{@link io.mosire.simos.app.ShellMain} 此前把"空库首启种什么世界"硬编码成 {@link RichWorld}；有了本表，
 * 启动参数 {@code --world=<worldId>} 与配置文件 {@code world} 字段才能只交出**一个 id**，由这里解析成真正的生成器。
 *
 * <p>★ **语义边界（用户 2026-10-09 口径）**：{@code worldId} 只是**空库首启时加载哪个世界的生成器**，不是运行时切换； 非空库绝不覆盖（数据安全线在
 * {@code CoreSimos#bootstrapGenesis} 与 {@link io.mosire.simos.app.ShellMain#shouldSeedGenesis}
 * 两处，与本表无关）。
 *
 * <p>★ **生成器契约**：{@link GenesisProvider#state(String)} 必须**确定性、无随机、无时钟**——同一份输入每次都产出逐字段相同的 {@link
 * SimulationState}，否则"创世可复现"这条红线就破了。{@code mapId} 是 {@link io.mosire.simos.app.ShellConfig#mapId()}
 * 的透传（{@code GameMap} 自身没有 id，状态里无处存它）。
 *
 * <p>★ **已登记**（登记顺序即报错信息里的列举顺序）：
 *
 * <ul>
 *   <li>{@value #V17LEVANT} → {@link RichWorld}：当前唯一经济世界的复刻富世界，也是缺省世界；
 *   <li>{@value #CORRIDOR} → {@link CorridorWorld}：三格走廊夹具世界，供后续小世界与确定性用例复用（**不是**缺省）。
 * </ul>
 *
 * <p>★ **未知 id 一律 fail-closed**：{@link #require(String)} 具名抛出并列出已登记 id，绝不"落到某个默认世界"——那会让 拼错的 {@code
 * --world} 静默种出一个不是用户要的世界。
 */
public final class WorldRegistry {

  /** 当前唯一经济世界（{@link RichWorld} 的 {@code v17levant} 复刻），也是内置缺省 worldId。 */
  public static final String V17LEVANT = "v17levant";

  /** 三格走廊夹具世界（{@link CorridorWorld}）的登记 id。 */
  public static final String CORRIDOR = "corridor";

  /** 创世生成器：把 {@code mapId} 解析成一个可被 {@code bootstrapGenesis} 落盘的创世状态。 */
  @FunctionalInterface
  public interface GenesisProvider {

    /**
     * 组装创世状态。
     *
     * @param mapId 本世界的 map 称谓（非空白；生成器各自校验）
     * @return 确定性创世状态
     */
    SimulationState state(String mapId);
  }

  /**
   * 一条登记项。
   *
   * @param id 稳定 worldId（与外部的 {@code --world} / 配置文件字段同字面）
   * @param description 人类可读的一句话（进启动日志与错误信息）
   * @param provider 创世生成器
   */
  public record Entry(String id, String description, GenesisProvider provider) {

    public Entry {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(description, "description");
      Objects.requireNonNull(provider, "provider");
      if (id.isBlank()) {
        throw new IllegalArgumentException("worldId 不得为空白: " + id);
      }
    }

    /** 按 {@code mapId} 组装创世状态（转调 {@link GenesisProvider#state(String)}）。 */
    public SimulationState genesis(String mapId) {
      return provider.state(mapId);
    }
  }

  private static final Map<String, Entry> ENTRIES = buildEntries();

  private WorldRegistry() {}

  /**
   * 按 id 取登记项；**未知 id 具名拒绝**（不落到任何默认世界）。
   *
   * @throws IllegalArgumentException 该 id 未登记（消息里列出已登记 id）
   */
  public static Entry require(String worldId) {
    Objects.requireNonNull(worldId, "worldId");
    Entry entry = ENTRIES.get(worldId);
    if (entry == null) {
      throw new IllegalArgumentException(
          "未知 worldId: \"" + worldId + "\"（已登记: " + String.join(", ", ENTRIES.keySet()) + "）");
    }
    return entry;
  }

  /** 按 id 查登记项（只读；缺失返回 {@link Optional#empty()}，不抛）。 */
  public static Optional<Entry> find(String worldId) {
    Objects.requireNonNull(worldId, "worldId");
    return Optional.ofNullable(ENTRIES.get(worldId));
  }

  /** 全部已登记 id（登记顺序；只读视图）。 */
  public static Set<String> knownIds() {
    return ENTRIES.keySet();
  }

  /**
   * 登记表本体。★ 加新世界只改这一处：{@code new Entry("<id>", "<说明>", SomeWorld::state)}。重复 id 在 {@link
   * #register(Map, Entry)} 里当场抛，避免"后登记静默覆盖先登记"。
   */
  private static Map<String, Entry> buildEntries() {
    Map<String, Entry> entries = new LinkedHashMap<>();
    register(
        entries,
        new Entry(V17LEVANT, "v17levant 复刻富世界（59223 hex / 252 区域 / 240 条河流边）", RichWorld::state));
    register(
        entries,
        new Entry(CORRIDOR, "三格沙漠走廊夹具世界（3 hex / 1 单位 / 1 条人口序列；供小世界与用例复用）", CorridorWorld::state));
    return Collections.unmodifiableMap(entries);
  }

  private static void register(Map<String, Entry> entries, Entry entry) {
    Entry previous = entries.putIfAbsent(entry.id(), entry);
    if (previous != null) {
      throw new IllegalStateException("worldId 重复登记: " + entry.id());
    }
  }
}
