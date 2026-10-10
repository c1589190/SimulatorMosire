package io.mosire.simos.economy.spi;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * W1g 只读探针 v3：handler 从**刚编译出来的 spi 目录**发现（不吃 ~/.m2 里的旧 jar），
 * 把夹具 economy 那一组按插入序真跑一遍，再扫一遍 economy 的拒例。
 */
public class W1gChain2 {

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static SimulationState stateOf(EconomyData data) {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    return new SimulationState(
        new StateMeta(ref, T7), Map.of("economy", new EconomySnapshot(ref, T7, data)), InMemoryInfoSystem.empty());
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    Map<String, CommandHandler> handlers = new LinkedHashMap<>();
    File[] classFiles = new File(args[0]).listFiles((d, n) -> n.endsWith(".class") && !n.contains("$"));
    for (File file : classFiles) {
      String simple = file.getName().substring(0, file.getName().length() - ".class".length());
      Class<?> type;
      try {
        type = Class.forName("io.mosire.simos.economy.spi." + simple);
      } catch (Throwable ignored) {
        continue;
      }
      if (!CommandHandler.class.isAssignableFrom(type)
          || type.isInterface()
          || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      try {
        CommandHandler handler = (CommandHandler) type.getDeclaredConstructor().newInstance();
        handlers.put(handler.type(), handler);
      } catch (Throwable ignored) {
        // 无参构造不可用 ⇒ 不是命令 handler
      }
    }
    System.out.println("发现 economy handler " + handlers.size() + " 个");

    Class<?> testClass = Class.forName("io.mosire.simos.app.McpCoverageTest");
    Field minimalField = testClass.getDeclaredField("MINIMAL_PAYLOADS");
    minimalField.setAccessible(true);
    Map<String, String> payloads = (Map<String, String>) minimalField.get(null);
    Field rejectField = testClass.getDeclaredField("PRECONDITION_REJECT_PAYLOADS");
    rejectField.setAccessible(true);
    Map<String, String> rejectPayloads = (Map<String, String>) rejectField.get(null);
    Field typesField = testClass.getDeclaredField("PRECONDITION_REJECT_TYPES");
    typesField.setAccessible(true);
    Set<String> rejectTypes = (Set<String>) typesField.get(null);

    List<String> chain =
        List.of(
            "economy.Seed",
            "economy.SetMarketPrice",
            "economy.SetCommodityFreight",
            "economy.AddDemand",
            "economy.CancelDemand",
            "economy.RegisterCandidate",
            "economy.TransferAssetShare");
    // ★ 与夹具一致：创世 economy 切片是空的，第一条 economy 命令就是 economy.Seed（上面 v1 探针从"已播种态"起跑
    //   把 seed 跑成了重复播种，那是探针的错，不是夹具的错）。
    EconomyData base = EconomyData.empty();
    SimulationState current = stateOf(base);
    int failures = 0;
    for (String type : chain) {
      CommandHandler handler = handlers.get(type);
      HandlerOutcome outcome = handler.handle(current, payloads.get(type));
      System.out.println(
          "[chain] "
              + type
              + " => "
              + outcome.getClass().getSimpleName()
              + (outcome instanceof HandlerOutcome.Rejected r ? " reason=" + r.reason() : ""));
      if (outcome instanceof HandlerOutcome.Applied applied) {
        base = EconomyChangeSet.apply((EconomyChangeSet) applied.changeSet(), base);
        current = stateOf(base);
      } else {
        failures++;
      }
    }
    System.out.println(
        "[chain] 全部提交成功 ? " + (failures == 0) + "（未提交 " + failures + " 条）");

    System.out.println("--- economy 拒例（必须全部具名拒）---");
    Set<String> economyRejects = new LinkedHashSet<>();
    for (String type : rejectTypes) {
      if (type.startsWith("economy.")) {
        economyRejects.add(type);
      }
    }
    int rejected = 0;
    int unexpected = 0;
    int needsOtherSlice = 0;
    for (String type : economyRejects) {
      HandlerOutcome outcome;
      try {
        outcome = handlers.get(type).handle(current, rejectPayloads.get(type));
      } catch (Throwable t) {
        // ★ 探针只装了 economy 切片：需要 social/map/unit 切片的 handler 在这里当场抛 —— 那是**探针**的边界，
        //   不是夹具的问题（真测试里那些切片都在场）。如实分开计。
        needsOtherSlice++;
        System.out.println(
            "[reject] " + type + " => 探针边界（需要别的切片）: " + t.getClass().getSimpleName());
        continue;
      }
      if (outcome instanceof HandlerOutcome.Rejected r) {
        rejected++;
        System.out.println("[reject] " + type + " => rejected: " + r.reason());
      } else {
        unexpected++;
        System.out.println("[reject] " + type + " => ★★ 没被拒：" + outcome.getClass().getSimpleName());
      }
    }
    System.out.println(
        "拒例合计="
            + economyRejects.size()
            + " 具名拒="
            + rejected
            + " 意外="
            + unexpected
            + " 探针边界="
            + needsOtherSlice);
  }
}
