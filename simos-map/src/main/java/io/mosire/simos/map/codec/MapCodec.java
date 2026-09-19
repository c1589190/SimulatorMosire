package io.mosire.simos.map.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * map 模块的 {@link ModuleCodec} 实现（spec §八）。
 *
 * <p>★ **快照与变更集的 JSON 形态是 map 自己的事**——Core 只把它当一段文本内嵌（C26）。因此本类的 {@code encode*} / {@code decode*}
 * 返回/接收的是**裸 JSON 文本**，不带任何 Core 侧的信封。
 *
 * <p>★ **键反序列化器在本模块注册**（台账裁定 16）：本模块树里出现的六个自定义键类型 {@code HexCoord}/{@code RegionId}/{@code
 * CityId}/{@code PathwayId}/{@code EdgeRef}/{@link BlockId} 全住在 simos-map 家里，util 的 {@code
 * SimosObjectMapper} 够不着（铁律 3）——所以是本类把 "各家的 {@code toString()}/{@code parse} 三件套"接到 Jackson 的 Map
 * 键上，共享基座只出 feature。{@code terrainTypes}/{@code pathwayGroups} 的键本来就是 {@code
 * String}（R-48-j），**不给它们写凭空的注册**。
 *
 * <p>★ **旧形状回退**（M9 T6 §3.12）：P1 把地形从逐格 {@code HexCell} 提到权威 {@code
 * terrainBlocks}，线格式随之改变。本类在读入时**先探测** 旧形状（{@code map.hexes} 的值带 {@code terrain}、且没有 {@code
 * terrainBlocks}），若是则**就地迁移**成新形状（分离高度、按 {@link TerrainBlocks#split}
 * 切块）——**不静默失败**。旧形状的**变更集**无法迁移（块切分依赖 base 全图，变更集自带信息不足）， 本类**显式抛**并给出重导入指引。
 *
 * <p>★ {@link #apply} 里的 cast 发生在这里（模块自己的地盘），Core 从不 cast、从不反射模块类型（C26）。
 */
public final class MapCodec implements ModuleCodec {

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的键反序列化器（建造期一次性配齐，见 SimosObjectMapper.create 的契约）。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /**
   * ★ **把 {@code MapChangeSet.isEmpty()} 摘出 JSON 形态**（M4 Task 3 实测新发现，探针只往返过快照、没往返过变更集）：Jackson 会把
   * {@code isEmpty()} 当成 {@code "empty"} 属性写进字节，而严格读入（{@code FAIL_ON_UNKNOWN_PROPERTIES}
   * 保持默认）随即炸掉—— **严格恰好在这里立了功**，把它从"静默写脏存档"变成了"当场响"。{@code isEmpty} 是派生判断不是状态，**不进线格式**；mixin 放本类
   * （mapper 与 mixin 同处一地、谁也丢不了），领域类型保持零 Jackson 注解。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(MapChangeSet.class, MapChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class MapChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  /** 六个键类型各接一路 {@code parse}。 */
  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("map-json-keys");
    module.addKeyDeserializer(HexCoord.class, keyDeserializer(HexCoord::parse));
    module.addKeyDeserializer(BlockId.class, keyDeserializer(BlockId::parse));
    module.addKeyDeserializer(RegionId.class, keyDeserializer(RegionId::parse));
    module.addKeyDeserializer(CityId.class, keyDeserializer(CityId::parse));
    module.addKeyDeserializer(PathwayId.class, keyDeserializer(PathwayId::parse));
    module.addKeyDeserializer(EdgeRef.class, keyDeserializer(EdgeRef::parse));
    return module;
  }

  /** Jackson 的 Map 键是 {@code toString()} 串 ⇒ 反序列化方向用同一把尺子（各键类型的 {@code parse}）读回来。 */
  private static <K> KeyDeserializer keyDeserializer(Function<String, K> parse) {
    return new KeyDeserializer() {
      @Override
      public Object deserializeKey(String key, DeserializationContext context) {
        return parse.apply(key);
      }
    };
  }

  /** 编码失败的唯一形态是"内存里的合法快照写不出 JSON"——那是契约故障，不是可恢复的业务分支，故以 {@link IllegalStateException} 出面。 */
  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("map 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  @Override
  public String namespace() {
    return "map";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return readJson(json, MapChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((MapChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, MapSnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asMapSnapshot(snapshot));
  }

  /**
   * 施加变更集，返回**新的**快照（C28）。
   *
   * <p>★ 两层下转型都在**模块自己的地盘**：先经 {@link #asMapSnapshot} 取 {@code GameMap}（**先验后转**，不是裸 cast），再 {@code
   * (MapChangeSet) changeSet}。
   *
   * <p>★ 新快照的 ref/timestamp **来自 {@code newMeta}**，**不是** base 的——用 base 的会得到陈旧坐标，spec §5.4 第 4
   * 项会当场判 {@code Rejected}（C28 的全部由来）。
   */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    MapSnapshot mapBase = asMapSnapshot(base);
    GameMap next = MapChangeSet.apply((MapChangeSet) changeSet, mapBase.map());
    return new MapSnapshot(newMeta.ref(), newMeta.timestamp(), next);
  }

  /**
   * 切片下转型的唯一入口：**先验后转**，验不过当场炸。
   *
   * <p>★ 原先这里是裸 {@code (MapSnapshot) base}。它合法，但那是**未确认的下转型**——{@code Snapshot} 有 map/social/unit
   * 三个实现，转错只在下游落成 {@code ClassCastException}，读不出"这是装配给错了切片"。改成 {@code instanceof} 之后连 cast
   * 都不存在，错误信息指名道姓。
   */
  private static MapSnapshot asMapSnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "map codec 的切片不是 MapSnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return mapSnapshot;
  }

  /** 读 JSON：先解析成树、必要时**回退旧形状**，再按目标类型取。 */
  private static <T> T readJson(String json, Class<T> type) {
    JsonNode tree;
    try {
      tree = MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("map 侧 JSON 解析失败: " + type.getSimpleName(), e);
    }
    // ★ 迁移放在 try 之外：它自己的 IllegalStateException（旧变更集无法迁移）要**原样**面世，不被下面的包装吞掉。
    JsonNode migrated = migrate(tree);
    try {
      return MAPPER.treeToValue(migrated, type);
    } catch (Exception e) {
      throw new IllegalStateException("map 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }

  /**
   * 旧形状回退的入口：探测并就地迁移。
   *
   * <p>两种线格式：
   *
   * <ul>
   *   <li>**快照**：根是 {@code MapSnapshot}，地形图在 {@code map} 段 ⇒ 该段缺 {@code terrainBlocks} 且 hex 值带
   *       {@code terrain} 时迁移；
   *   <li>**变更集**：根就是 {@code MapChangeSet}，hex 值（若带 {@code terrain}）无法迁移 ⇒ **显式抛**（块切分依赖 base）。
   * </ul>
   */
  private static JsonNode migrate(JsonNode root) {
    JsonNode envelope = root.path("map"); // MapSnapshot 的 map 段
    if (envelope.isObject() && envelope.has("hexes")) {
      migrateGameMap(envelope); // 就地迁移 map 段
      return root;
    }
    JsonNode hexDelta = root.get("hexes");
    if (root.has("hexes") && hexDelta != null && containsTerrainField(hexDelta)) {
      throw new IllegalStateException(
          "旧形状 map 变更集（hexes 值带 terrain）无法自动迁移：地形块切分依赖 base 全图，变更集自带信息不足。"
              + "请用 tools/gsimap_import.py 重新导入该数据集。");
    }
    return root;
  }

  /** {@code gameMap} 是 {@code MapSnapshot.map} 段（对象、含 {@code hexes}）。 */
  private static JsonNode migrateGameMap(JsonNode gameMap) {
    if (gameMap.has("terrainBlocks")) {
      return gameMap; // 已是新形状
    }
    JsonNode hexes = gameMap.get("hexes");
    if (hexes == null || !hexes.isObject() || !containsTerrainField(hexes)) {
      return gameMap; // 非旧形状（例如真新形状但缺块）：交回下游报错，不在这里吞
    }
    Map<HexCoord, String> terrainByHex = new LinkedHashMap<>();
    ObjectNode newHexes = MAPPER.createObjectNode();
    Iterator<Map.Entry<String, JsonNode>> fields = hexes.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      JsonNode cell = entry.getValue();
      JsonNode terrain = cell.get("terrain");
      if (terrain == null || !terrain.isTextual()) {
        return gameMap; // 混合形状：不做部分迁移
      }
      terrainByHex.put(HexCoord.parse(entry.getKey()), terrain.asText());
      ObjectNode heightOnly = MAPPER.createObjectNode();
      heightOnly.set("height", cell.get("height"));
      newHexes.set(entry.getKey(), heightOnly);
    }
    ObjectNode map = (ObjectNode) gameMap;
    map.set("terrainBlocks", MAPPER.valueToTree(TerrainBlocks.split(terrainByHex)));
    map.set("hexes", newHexes);
    return map;
  }

  /** 子树里是否出现 {@code terrain} 属性（旧 {@code HexCell} 的签名字段）。 */
  private static boolean containsTerrainField(JsonNode node) {
    if (node == null) {
      return false;
    }
    if (node.isObject()) {
      if (node.has("terrain")) {
        return true;
      }
      for (JsonNode child : node) {
        if (containsTerrainField(child)) {
          return true;
        }
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        if (containsTerrainField(child)) {
          return true;
        }
      }
    }
    return false;
  }
}
