import io.mosire.simos.map.*;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.*;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.pathway.*;
import io.mosire.simos.map.generate.GenerationSpec;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.util.*;

var H_A = new HexCoord(5, 5);
var H_B = new HexCoord(0, 0);
var H_C = new HexCoord(-3, 2);
var H_D = new HexCoord(2, -4);
var EDGE_AB = new EdgeRef(H_A, H_B);
var EDGE_BC = new EdgeRef(H_B, H_C);

KeyDeserializer kd(java.util.function.Function<String,?> f) {
  return new KeyDeserializer() {
    public Object deserializeKey(String key, com.fasterxml.jackson.databind.DeserializationContext ctxt) { return f.apply(key); }
  };
}
var keys = new SimpleModule();
keys.addKeyDeserializer(HexCoord.class, kd(HexCoord::parse));
keys.addKeyDeserializer(EdgeRef.class, kd(EdgeRef::parse));
keys.addKeyDeserializer(RegionId.class, kd(RegionId::parse));
keys.addKeyDeserializer(CityId.class, kd(CityId::parse));
keys.addKeyDeserializer(PathwayId.class, kd(PathwayId::parse));
var mapper = new ObjectMapper().registerModule(keys);

Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
hexes.put(H_A, new HexCell("plains", 0.37));
hexes.put(H_B, new HexCell("mountains", 0.62));
hexes.put(H_C, new HexCell("ocean", 0.21));
hexes.put(H_D, new HexCell("desert", 0.50));
var original = new GameMap(
    hexes,
    Map.of(new RegionId("r1"), Region.of(new RegionId("r1"), "区域 r1", Set.of(H_C, H_B), RegionMeta.empty())),
    Map.of(new CityId("c1"), new City(new CityId("c1"), "城 c1", H_A, new RegionId("r1"), Map.of("population", 1000))),
    TerrainCatalog.defaults(),
    Map.of(new PathwayId("p1"), new Pathway(new PathwayId("p1"), "线 p1", "road", List.of(EDGE_AB, EDGE_BC), Map.of("width", 2))),
    Map.of("road", new PathwayGroup("road", "组 road", "#8B7355", null, true, Map.of())),
    Map.of(EDGE_AB, new EdgeTags(Map.of("road", Map.of("width", 2)))),
    GenerationSpec.defaults(42L));

byte[] first = mapper.writeValueAsBytes(original);
var back = mapper.readValue(first, GameMap.class);
byte[] second = mapper.writeValueAsBytes(back);
System.out.println("len first=" + first.length + " second=" + second.length);
int i = 0;
while (i < Math.min(first.length, second.length) && first[i] == second[i]) i++;
System.out.println("first diff at " + i);
System.out.println("first  ctx: ..." + new String(first, Math.max(0,i-60), Math.min(160, first.length-Math.max(0,i-60))) );
System.out.println("second ctx: ..." + new String(second, Math.max(0,i-60), Math.min(160, second.length-Math.max(0,i-60))) );
System.out.println("equals: " + java.util.Arrays.equals(first, second));
/exit
