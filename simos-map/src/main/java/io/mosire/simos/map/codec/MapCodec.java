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
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;

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
 *
 * <p>★ **同时实现 {@link ModuleDiffer}**（"一批命令 = 一条 revision" 的原子批量提交需要）：把两个切片还原成 {@code GameMap} 后委托
 * {@link MapChangeSet#between(GameMap, GameMap)}——变更集从完整状态派生（铁律 5），本类**不重新实现比较语义**。
 */
public final class MapCodec implements ModuleCodec, ModuleDiffer {

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的键反序列化器（建造期一次性配齐，见 SimosObjectMapper.create 的契约）。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /** 编解码日志门面：只记规模/组件 changed 计数这类元信息，**绝不打印 JSON 原文或载荷明文**。 */
  private static final Logger LOG = MapLog.codec();

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
    MapChangeSet decoded = readJson(json, MapChangeSet.class);
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "MAP_CODEC_CHANGE_SET_DECODED",
                MapLogSource.MAP_CODEC,
                "chars",
                json == null ? "-" : json.length(),
                "changedComponents",
                changedComponentCount(decoded)));
    return decoded;
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    MapChangeSet encoded = (MapChangeSet) changeSet;
    String json = writeJson(encoded);
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "MAP_CODEC_CHANGE_SET_ENCODED",
                MapLogSource.MAP_CODEC,
                "chars",
                json.length(),
                "changedComponents",
                changedComponentCount(encoded)));
    return json;
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    MapSnapshot decoded = readJson(json, MapSnapshot.class);
    logSnapshot("MAP_CODEC_SNAPSHOT_DECODED", decoded, json == null ? "-" : json.length());
    return decoded;
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    MapSnapshot mapSnapshot = asMapSnapshot(snapshot);
    String json = writeJson(mapSnapshot);
    logSnapshot("MAP_CODEC_SNAPSHOT_ENCODED", mapSnapshot, json.length());
    return json;
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
    MapChangeSet mapChangeSet = (MapChangeSet) changeSet;
    GameMap next = MapChangeSet.apply(mapChangeSet, mapBase.map());
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "MAP_CODEC_CHANGE_SET_APPLIED",
                MapLogSource.MAP_CODEC,
                "changedComponents",
                changedComponentCount(mapChangeSet),
                "hexes",
                next.hexes().size(),
                "regions",
                next.regions().size()));
    return new MapSnapshot(newMeta.ref(), newMeta.timestamp(), next);
  }

  /**
   * 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：两层下转型都在本模块地盘，语义委托 {@link MapChangeSet#between(GameMap,
   * GameMap)}。
   *
   * <p>★ {@code spec}/{@code RegionIndex} 等"不进变更集"的部分**不在这里判**——那是 {@code between} 的既有职责（判决点唯一）。
   */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return MapChangeSet.between(asMapSnapshot(base).map(), asMapSnapshot(target).map());
  }

  /** 变更集里 changed 的组件数（元信息；不打印任何值）。 */
  private static int changedComponentCount(MapChangeSet changeSet) {
    int count = 0;
    if (changeSet.hexes().changed()) {
      count++;
    }
    if (changeSet.terrainBlocks().changed()) {
      count++;
    }
    if (changeSet.regions().changed()) {
      count++;
    }
    if (changeSet.cities().changed()) {
      count++;
    }
    if (changeSet.terrainTypes().changed()) {
      count++;
    }
    if (changeSet.pathways().changed()) {
      count++;
    }
    if (changeSet.pathwayGroups().changed()) {
      count++;
    }
    if (changeSet.edges().changed()) {
      count++;
    }
    return count;
  }

  /** 快照编解码的元信息（组件规模 + 文本长度）；不打印 JSON 原文。{@code chars} 传 {@code "-"} 表示输入缺失。 */
  private static void logSnapshot(String event, MapSnapshot snapshot, Object chars) {
    GameMap map = snapshot.map();
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                event,
                MapLogSource.MAP_CODEC,
                "chars",
                chars,
                "hexes",
                map.hexes().size(),
                "blocks",
                map.terrainBlocks().size(),
                "regions",
                map.regions().size(),
                "cities",
                map.cities().size(),
                "pathways",
                map.pathways().size(),
                "groups",
                map.pathwayGroups().size(),
                "edges",
                map.edges().size()));
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
      EventLog.channel(LOG)
          .warn(
              LogEvent.of(
                  "MAP_CODEC_LEGACY_CHANGE_SET_UNMIGRATABLE",
                  MapLogSource.MAP_CODEC,
                  "reason",
                  "旧形状 hexes 带 terrain，块切分依赖 base 全图，无法自动迁移"));
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
    var blocks = TerrainBlocks.split(terrainByHex);
    map.set("terrainBlocks", MAPPER.valueToTree(blocks));
    map.set("hexes", newHexes);
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MAP_CODEC_LEGACY_SNAPSHOT_MIGRATED",
                MapLogSource.MAP_CODEC,
                "hexes",
                terrainByHex.size(),
                "blocks",
                blocks.size()));
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
