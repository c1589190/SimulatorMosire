import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.resolve.MapResolver;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** T4 真档探针（副本）：真 Shell（真 GUI/HTTP） + 真 CoreSimos/CommandBus + 真 map.CreateRegion 重叠正例。 */
public final class T4RealArchiveProbe {

  private static final BranchId MAIN = new BranchId("main");

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  public static void main(String[] args) throws Exception {
    Path store = Path.of(args[0]);
    try (Shell shell = Shell.start(ShellConfig.defaults(store).withPorts(0, 0, 0))) {
      CoreSimos core = shell.coreSimos();
      int guiPort = shell.boundGuiPort();
      GameMap genesis = mapAt(core, 1);
      System.out.println("hexCount=" + genesis.hexes().size());
      System.out.println("regionCount=" + genesis.regions().size());

      Region nation = genesis.regions().get(new RegionId("test_nation"));
      List<HexCoord> nationHexes = new ArrayList<>(nation.hexes());
      nationHexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
      List<HexCoord> pick = nationHexes.subList(0, 3);
      System.out.println("pickedHexes=" + pick);
      for (HexCoord h : pick) {
        System.out.println("  genesisOwners(" + h + ")=" + MapResolver.regionOfHex(genesis, h));
      }

      long rowsBefore = core.revisions(MAIN).size();
      CommandResult created =
          core.submit(
              envelope(
                  1,
                  "map.CreateRegion",
                  "{\"regionId\":\"t4_overlap\",\"name\":\"T4重叠区\",\"hexes\":" + array(pick) + "}"));
      System.out.println("createResult=" + created);
      System.out.println(
          "revisionsBefore=" + rowsBefore + " after=" + core.revisions(MAIN).size());

      GameMap after = mapAt(core, 2);
      System.out.println("regionsAfter=" + after.regions().keySet());
      HexCoord probe = pick.get(0);
      System.out.println("ownersAfter(" + probe + ")=" + MapResolver.regionOfHex(after, probe));

      String url =
          "http://127.0.0.1:"
              + guiPort
              + "/api/map/hex?q="
              + probe.q()
              + "&r="
              + probe.r()
              + "&revision=2";
      String json = httpGet(url);
      System.out.println("API_URL=" + url);
      System.out.println("API_JSON=" + json);
      System.out.println(
          "apiListsBoth=" + (json.contains("test_nation") && json.contains("t4_overlap")));

      GameMap replayA = mapAt(core, 2);
      GameMap replayB = mapAt(core, 2);
      System.out.println(
          "replayRegionsByteIdentical="
              + replayA.regions().toString().equals(replayB.regions().toString()));

      long rows = core.revisions(MAIN).size();
      System.out.println(
          "negDuplicate="
              + core.submit(
                  envelope(
                      2,
                      "map.CreateRegion",
                      "{\"regionId\":\"test_nation\",\"name\":\"x\",\"hexes\":[{\"q\":0,\"r\":0}]}")));
      System.out.println(
          "negUpdateMissing="
              + core.submit(
                  envelope(
                      2, "map.UpdateRegion", "{\"regionId\":\"nope\",\"meta\":{\"tag\":\"T\"}}")));
      System.out.println(
          "negDeleteMissing="
              + core.submit(envelope(2, "map.DeleteRegion", "{\"regionId\":\"nope\"}")));
      System.out.println(
          "negEmpty="
              + core.submit(
                  envelope(
                      2,
                      "map.CreateRegion",
                      "{\"regionId\":\"t4x\",\"name\":\"x\",\"hexes\":[]}")));
      System.out.println(
          "negOutside="
              + core.submit(
                  envelope(
                      2,
                      "map.CreateRegion",
                      "{\"regionId\":\"t4y\",\"name\":\"y\",\"hexes\":[{\"q\":9999,\"r\":9999}]}")));
      System.out.println(
          "rowsUnchanged=" + (core.revisions(MAIN).size() == rows) + " rows=" + core.revisions(MAIN).size());
    }
  }

  private static String array(List<HexCoord> hexes) {
    StringBuilder sb = new StringBuilder("[");
    for (HexCoord hex : hexes) {
      if (sb.length() > 1) {
        sb.append(',');
      }
      sb.append("{\"q\":").append(hex.q()).append(",\"r\":").append(hex.r()).append('}');
    }
    return sb.append(']').toString();
  }

  private static String httpGet(String url) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    return "status=" + response.statusCode() + " " + response.body();
  }

  private static CommandEnvelope envelope(long expectedRevision, String type, String payload) {
    String id = "cmd-" + type;
    return new CommandEnvelope(id, id, "probe:t4", MAIN, new RevisionId(expectedRevision), type, payload);
  }

  private static GameMap mapAt(CoreSimos core, long revision) throws Exception {
    return ((MapSnapshot) core.replay(new StateRef(MAIN, new RevisionId(revision))).module("map").orElseThrow())
        .map();
  }

  private T4RealArchiveProbe() {}
}
