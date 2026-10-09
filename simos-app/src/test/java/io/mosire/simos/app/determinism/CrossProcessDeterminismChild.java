package io.mosire.simos.app.determinism;

import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.app.world.ThreePowersWorld;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ <b>跨进程确定性护栏的“子进程主类”</b>（test scope；故意<b>不是</b> JUnit 测试类——surefire 不会把它当用例跑， 它只由 {@link
 * CrossProcessDeterminismTest} 用 {@code ProcessBuilder} 起成<b>独立 JVM</b>）。
 *
 * <p>★★ <b>它解决的问题</b>：既有那条“字节级稳定”护栏（{@code
 * EconomyCodecTest#encodingIsByteLevelStableForEconomyData}） 在<b>同一个进程内</b>做 {@code encode → decode
 * → encode} 两次相等。而本仓 2026-10-10 抓到的真缺陷是 <b>per-JVM 盐</b>：{@code Map.of}（≥2 键）= {@code
 * ImmutableCollections.MapN}、{@code Set.copyOf} = {@code SetN}，其槽位 = {@code floorMod(键.hashCode() ^
 * SALT, 表长)} 而 {@code SALT} 取自 <b>JVM 启动时的 {@code nanoTime}</b> ⇒
 * <b>同进程内两次编码用同一个盐，那条护栏恒真、天生抓不到</b>。只有“各起一个 JVM、各自构造同一份数据、 比字节 digest”才有判别力。
 *
 * <p>★ <b>产出</b>（全部写到 {@code args[0]} 这个目录；每个子进程一个<b>自己的</b>目录）：
 *
 * <ul>
 *   <li>{@code <ns>.json}：8 个模块的创世快照字节（{@code ModuleCodec#encodeSnapshot}）；
 *   <li>{@code checkpoint.json}：整世界 checkpoint 信封（{@code CheckpointEncoder}，逐模块载荷都在里面）；
 *   <li>{@code economy.seed-changeset.json}：{@code empty → 创世态} 的变更集字节（journal 的 {@code
 *       changeset_json} 同源）；
 *   <li>{@code jvm-identity.txt}：本进程的 pid / nanoTime（父进程据此断言“确实起了 N 个不同进程”）；
 *   <li>{@code salt-probe.txt}：{@code Map.of} / {@code Set.copyOf} 在本 JVM 的迭代序读数——<b>机制读数</b>，
 *       用来把“盐”这件事量出来（父进程只上报、不断言：它的取值本身就是随机的）。
 * </ul>
 *
 * <p>★ stdout 只打<b>摘要行</b>（{@code ARTIFACT <名> sha256=<64 hex> len=<n>} / {@code SAME_PROCESS …} /
 * {@code CHILD_OK …}），不打 JSON 本体——父进程按行解析，失败时把整个日志贴进断言消息。
 *
 * <p>★ 退出码：全成功 0，任何异常 1（父进程断言 {@code exitValue()==0}，并把 stderr 一起贴出来）。
 */
public final class CrossProcessDeterminismChild {

  /** 参与比较的模块 namespace，与 {@link CheckpointEncoder} 的逐段载荷一一对应。 */
  static final List<String> MODULES =
      List.of("map", "social", "unit", "sd", "economy", "actor", "gov", "army");

  /** 8 个模块的 codec（namespace → codec），与生产装配同序。 */
  static Map<String, ModuleCodec> codecs() {
    Map<String, ModuleCodec> codecs = new LinkedHashMap<>();
    for (ModuleCodec codec :
        List.of(
            new MapCodec(),
            new SocialCodec(),
            new UnitCodec(),
            new SdCodec(),
            new ActorCodec(),
            new EconomyCodec(),
            new GovCodec(),
            new ArmyCodec())) {
      codecs.put(codec.namespace(), codec);
    }
    return codecs;
  }

  public static void main(String[] args) throws Exception {
    PrintStream out = System.out;
    Path outDir = Path.of(args[0]);
    String mapId = args.length > 1 ? args[1] : ThreePowersWorld.MAP_ID;
    Files.createDirectories(outDir);

    long pid = ProcessHandle.current().pid();
    out.println("CHILD pid=" + pid + " mapId=" + mapId + " cwd=" + Path.of("").toAbsolutePath());

    // ★ 装置自指：这两个值每个进程都不同 ⇒ 父进程据此断言“比较的确实是 N 个不同进程的产物”，
    //   而不是同一个 JVM 里跑了 N 次（那正是旧护栏恒真的原因）。
    Files.writeString(
        outDir.resolve("jvm-identity.txt"),
        "pid=" + pid + "\nnanoTime=" + System.nanoTime() + "\n",
        StandardCharsets.UTF_8);

    // ★ 机制读数：直接量本 JVM 的 Map.of / Set.copyOf 迭代序。只上报、不断言。
    Files.writeString(
        outDir.resolve("salt-probe.txt"),
        "pid="
            + pid
            + "\n"
            + "mapOf2=a,b -> "
            + new ArrayList<>(Map.of("a", 1, "b", 2).keySet())
            + "\n"
            + "mapOf2b=fiber,grain -> "
            + new ArrayList<>(Map.of("fiber", 1, "grain", 2).keySet())
            + "\n"
            + "mapOf2c=meansWeightPerMille,laborWeightPerMille -> "
            + new ArrayList<>(Map.of("meansWeightPerMille", 1, "laborWeightPerMille", 2).keySet())
            + "\n"
            + "setCopyOf5=a..e -> "
            + new ArrayList<>(Set.copyOf(List.of("a", "b", "c", "d", "e")))
            + "\n",
        StandardCharsets.UTF_8);

    SimulationState state = ThreePowersWorld.state(mapId);
    Map<String, ModuleCodec> codecs = codecs();

    // ① 逐模块创世快照字节
    Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    for (String ns : MODULES) {
      Snapshot snapshot =
          state.module(ns).orElseThrow(() -> new IllegalStateException("缺模块: " + ns));
      snapshots.put(ns, snapshot);
      writeArtifact(out, outDir, ns, codecs.get(ns).encodeSnapshot(snapshot));
    }

    // ② 整世界 checkpoint 信封（8 段载荷都在里面 ⇒ 任一段抖动都会改这份字节）
    writeArtifact(out, outDir, "checkpoint", CheckpointEncoder.encode(state, codecs.values()));

    // ③ 创世变更集（journal 的 changeset_json 同源）：empty → 创世态
    EconomyCodec economyCodec = new EconomyCodec();
    EconomySnapshot economySnapshot = (EconomySnapshot) state.module("economy").orElseThrow();
    writeArtifact(
        out,
        outDir,
        "economy.seed-changeset",
        economyCodec.encodeChangeSet(
            EconomyChangeSet.between(EconomyData.empty(), economySnapshot.data())));

    // ④ 同进程对照：这正是旧护栏的形制（同进程 encode→decode→encode）。**它必须印出来**——
    //    变异体下它会继续为 true，而跨进程 digest 已经分裂，两者的对照就是“旧护栏判别力是假的”的证据。
    for (String ns : MODULES) {
      ModuleCodec codec = codecs.get(ns);
      String once = codec.encodeSnapshot(snapshots.get(ns));
      String twice = codec.encodeSnapshot(snapshots.get(ns));
      String roundTrip = codec.encodeSnapshot(codec.decodeSnapshot(once));
      out.println(
          "SAME_PROCESS "
              + ns
              + " once==twice:"
              + once.equals(twice)
              + " once==roundTrip:"
              + once.equals(roundTrip));
    }
    String seedOnce =
        economyCodec.encodeChangeSet(
            EconomyChangeSet.between(EconomyData.empty(), economySnapshot.data()));
    String seedRoundTrip = economyCodec.encodeChangeSet(economyCodec.decodeChangeSet(seedOnce));
    out.println(
        "SAME_PROCESS economy.seed-changeset once==twice:true once==roundTrip:"
            + seedOnce.equals(seedRoundTrip));

    out.println("CHILD_OK pid=" + pid);
    out.flush();
  }

  /** 落盘一份产物并打摘要行（父进程按行解析；不打 JSON 本体）。 */
  private static void writeArtifact(PrintStream out, Path outDir, String name, String text)
      throws Exception {
    Files.writeString(outDir.resolve(name + ".json"), text, StandardCharsets.UTF_8);
    out.println("ARTIFACT " + name + " sha256=" + sha256Hex(text) + " len=" + text.length());
  }

  static String sha256Hex(String text) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    StringBuilder hex = new StringBuilder();
    for (byte b : digest.digest(text.getBytes(StandardCharsets.UTF_8))) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }

  private CrossProcessDeterminismChild() {}
}
