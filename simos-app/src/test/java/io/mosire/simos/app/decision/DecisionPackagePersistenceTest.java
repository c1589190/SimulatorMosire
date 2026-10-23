package io.mosire.simos.app.decision;

import static io.mosire.simos.app.decision.DecisionPackageTestSupport.applyCommitted;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.item;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.metaFor;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.proposedRecord;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.decision.DecisionPackageTools;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.testing.GmActAsWorldFixture;
import io.mosire.simos.app.tools.write.GovWorldBootstrap;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.util.spi.DecisionMakerId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ 设计 §10.3 P1–P3：重启/replay 后五工具继续读/裁/提交；生产代码没有 {@code DecisionPackages} 内存表；PROPOSED → DECIDING
 * → DECIDED → COMMITTED 的快照跨进程保留 contentVersion/verdicts/receipt。
 */
class DecisionPackagePersistenceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String DM = GmActAsWorldFixture.DM;
  private static final String PACKET = "pkt-" + DM + "-0";
  private static final String GOV_CENTRAL = GovWorldBootstrap.CENTRAL_GOV_ID;

  @TempDir Path tempDir;

  @Test
  void packageCanBeReadDecidedAndCommittedAcrossThreeProcessRestarts() throws Exception {
    Path store = tempDir.resolve("world");
    GmActAsWorldFixture.Fake fake = new GmActAsWorldFixture.Fake();
    GmActAsWorldFixture.World first = GmActAsWorldFixture.start(store, fake);
    CoreSimos core = first.shell().coreSimos();
    List<PackageItem> items =
        List.of(
            item(
                0,
                "simos.gov.issueMoney",
                Map.of(
                    "govUnitId",
                    GOV_CENTRAL,
                    "currency",
                    "copper",
                    "amountMilli",
                    1234L,
                    "reason",
                    "P1 restart commit")),
            item(1, "simos.gov.setBudgetPolicy", Map.of("payloadJson", "{}")));
    DecisionRecord proposed =
        proposedRecord(
            PACKET,
            new DecisionMakerId(DM),
            0L,
            core.head(GmActAsWorldFixture.MAIN).orElseThrow().value() + 1L,
            items,
            "restart intent",
            metaFor(items));
    applyCommitted(core, 0L, proposed);
    long contentVersion =
        DecisionPackageTestSupport.record(core, PACKET).packageState().contentVersion();
    first.close();

    // 重启 #1：PROPOSED 仍可读，第一条逐条表态后进入 DECIDING。
    try (Shell second = reopen(store, fake)) {
      JsonNode get = get(second, PACKET);
      assertThat(get.path("state").asText()).as("P1：重启后 get 仍返回 PROPOSED").isEqualTo("PROPOSED");
      assertThat(get.path("itemCount").asInt()).isEqualTo(2);

      ToolResult approve =
          DecisionPackageTestSupport.executeGm(
              second,
              DecisionPackageTools.DECIDE_ITEM_NAME,
              Map.of("packageId", PACKET, "index", 0, "verdict", "approve", "note", "restart"));
      assertThat(approve.success()).as(approve.message()).isTrue();
      DecisionRecord deciding = record(second, PACKET);
      assertThat(deciding.packageState().pkg().state()).isEqualTo(PackageState.DECIDING);
      assertThat(deciding.packageState().contentVersion()).isEqualTo(contentVersion + 1L);
    }

    // 重启 #2：DECIDING 的 verdict 不丢；第二条表态后 DECIDED。
    try (Shell third = reopen(store, fake)) {
      JsonNode get = get(third, PACKET);
      assertThat(get.path("state").asText()).as("P1/P3：重启后 DECIDING 快照恢复").isEqualTo("DECIDING");
      assertThat(get.path("verdicts")).hasSize(1);
      assertThat(get.path("verdicts").get(0).path("index").asInt()).isZero();

      ToolResult reject =
          DecisionPackageTestSupport.executeGm(
              third,
              DecisionPackageTools.DECIDE_ITEM_NAME,
              Map.of("packageId", PACKET, "index", 1, "verdict", "reject", "note", "restart"));
      assertThat(reject.success()).as(reject.message()).isTrue();
      assertThat(record(third, PACKET).packageState().pkg().state())
          .isEqualTo(PackageState.DECIDED);
    }

    // 重启 #3：DECIDED 仍可继续 commit；receipt 与世界效果都落盘。
    long copperBefore;
    long commitHead;
    String revisionRef;
    try (Shell fourth = reopen(store, fake)) {
      JsonNode get = get(fourth, PACKET);
      assertThat(get.path("state").asText()).as("P1/P3：重启后 DECIDED 快照恢复").isEqualTo("DECIDED");
      assertThat(get.path("undecided")).isEmpty();
      copperBefore = treasuryCopper(fourth);
      commitHead = head(fourth);
      ToolResult commit =
          DecisionPackageTestSupport.executeGm(
              fourth, DecisionPackageTools.COMMIT_NAME, Map.of("packageId", PACKET));
      assertThat(commit.success()).as("P1：重启后仍可 commit：%s", commit.message()).isTrue();
      JsonNode commitView = JSON.readTree(commit.message());
      revisionRef = commitView.path("commit").path("revisionRef").asText();
      assertThat(revisionRef).isNotBlank();
      assertThat(head(fourth)).as("I7：一次 commit = 一条 revision").isEqualTo(commitHead + 1L);
      assertThat(treasuryCopper(fourth)).isEqualTo(copperBefore + 1234L);
      DecisionRecord committed = record(fourth, PACKET);
      assertThat(committed.packageState().pkg().state()).isEqualTo(PackageState.COMMITTED);
      assertThat(committed.packageState().receipt()).isNotNull();
      assertThat(committed.packageState().receipt().revisionRef()).isEqualTo(revisionRef);
    }

    // 重启 #4：COMMITTED + receipt 不丢，重复 commit 仍返回同一 receipt 且不再执行。
    try (Shell fifth = reopen(store, fake)) {
      JsonNode get = get(fifth, PACKET);
      assertThat(get.path("state").asText()).as("P1/P3：重启后 COMMITTED 快照恢复").isEqualTo("COMMITTED");
      ToolResult projection =
          DecisionPackageTestSupport.executeGm(
              fifth, "simos.gm.packet", Map.of("packetId", PACKET));
      assertThat(projection.success()).as(projection.message()).isTrue();
      assertThat(
              JSON.readTree(projection.message())
                  .path("packageState")
                  .path("receipt")
                  .path("revisionRef")
                  .asText())
          .as("P3：receipt 跨重启逐字恢复（领域投影读 packageState）")
          .isEqualTo(revisionRef);
      long headBefore = head(fifth);
      long copperBeforeRepeat = treasuryCopper(fifth);
      ToolResult repeat =
          DecisionPackageTestSupport.executeGm(
              fifth, DecisionPackageTools.COMMIT_NAME, Map.of("packageId", PACKET));
      assertThat(repeat.success()).isTrue();
      assertThat(JSON.readTree(repeat.message()).path("commit").path("revisionRef").asText())
          .as("P3：receipt 跨重启逐字恢复")
          .isEqualTo(revisionRef);
      assertThat(head(fifth)).isEqualTo(headBefore);
      assertThat(treasuryCopper(fifth)).isEqualTo(copperBeforeRepeat);
    }
  }

  /**
   * P2（T 口径，2026-10-17 lead 确认）：允许 import {@code DecisionPackages.RejectionException} 作为 AgentLib
   * 协议异常；禁止的是构造/持有/注入 {@code DecisionPackages} 内存表。
   */
  @Test
  void productionCodeHasNoDecisionPackagesInMemoryTable() throws Exception {
    Path repo = Path.of("..").toAbsolutePath().normalize();
    List<Path> sources;
    try (Stream<Path> modules = Files.list(repo)) {
      sources =
          modules
              .filter(Files::isDirectory)
              .filter(path -> path.getFileName().toString().startsWith("simos-"))
              .flatMap(
                  module -> {
                    Path main = module.resolve("src/main/java");
                    if (!Files.isDirectory(main)) {
                      return Stream.empty();
                    }
                    try {
                      return Files.walk(main).filter(path -> path.toString().endsWith(".java"));
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .toList();
    }
    assertThat(sources).as("★ 扫描面自证：生产源码必须非空").hasSizeGreaterThan(100);

    Pattern construction = Pattern.compile("new\\s+DecisionPackages\\s*\\(");
    Pattern fieldType = Pattern.compile("\\bDecisionPackages\\s+[A-Za-z_]\\w*\\s*[=;,)]");
    List<String> offenders = new ArrayList<>();
    for (Path file : sources) {
      String source = Files.readString(file);
      if (construction.matcher(source).find()) {
        offenders.add(file + " -> new DecisionPackages");
      }
      if (fieldType.matcher(source).find()) {
        offenders.add(file + " -> DecisionPackages field/parameter");
      }
    }
    assertThat(offenders)
        .as("P2：生产代码不得构造/持有 AgentLib 内存表 DecisionPackages；静态协议异常 RejectionException 允许")
        .isEmpty();
    String submissionPort =
        Files.readString(
            repo.resolve(
                "simos-app/src/main/java/io/mosire/simos/app/decision/CoreDecisionSubmissionPort.java"));
    assertThat(submissionPort)
        .as("P2 正口径：仅允许 DecisionPackages.RejectionException 这一协议异常用法")
        .contains("DecisionPackages.RejectionException")
        .doesNotContain("new DecisionPackages(")
        .doesNotContain("DecisionPackages packages");

    String readPort =
        Files.readString(
            repo.resolve(
                "simos-app/src/main/java/io/mosire/simos/app/decision/CoreDecisionPackageReadPort.java"));
    assertThat(readPort)
        .as("P2：五工具读口必须实现宿主端口，不依赖 concrete DecisionPackages")
        .contains("implements DecisionPackageReadPort")
        .doesNotContain("DecisionPackages packages");
    String shell =
        Files.readString(repo.resolve("simos-app/src/main/java/io/mosire/simos/app/Shell.java"));
    assertThat(shell).doesNotContain("new DecisionPackages");
  }

  // ── 小件 ────────────────────────────────────────────────────────────────────────

  private static Shell reopen(Path store, GmActAsWorldFixture.Fake fake) {
    ShellConfig config =
        ShellConfig.defaults(store).withPorts(0, 0).withWorldId(WorldRegistry.SMALL_WORLD);
    return Shell.start(config, fake::providerFor);
  }

  private static JsonNode get(Shell shell, String packetId) throws Exception {
    ToolResult result =
        DecisionPackageTestSupport.executeGm(
            shell, DecisionPackageTools.GET_NAME, Map.of("packageId", packetId));
    assertThat(result.success()).as(result.message()).isTrue();
    return JSON.readTree(result.message());
  }

  private static DecisionRecord record(Shell shell, String packetId) {
    return DecisionPackageTestSupport.record(shell.coreSimos(), packetId);
  }

  private static long head(Shell shell) {
    return shell.coreSimos().head(GmActAsWorldFixture.MAIN).orElseThrow().value();
  }

  private static long treasuryCopper(Shell shell) {
    GaState ga = DecisionPackageTestSupport.ga(shell.coreSimos());
    assertThat(ga).isNotNull();
    var state = DecisionPackageTestSupport.state(shell.coreSimos());
    var actor = ((io.mosire.simos.actor.ActorSnapshot) state.module("actor").orElseThrow()).data();
    var key =
        new io.mosire.simos.actor.model.HouseholdAccountKey(
            io.mosire.simos.social.api.id.HouseholdId.parse(
                io.mosire.simos.social.api.id.GovernmentHouseholds.of(GOV_CENTRAL).value()));
    return actor
        .accounts()
        .get(key)
        .money()
        .getOrDefault(new io.mosire.simos.economy.api.id.CurrencyId("copper"), 0L);
  }
}
