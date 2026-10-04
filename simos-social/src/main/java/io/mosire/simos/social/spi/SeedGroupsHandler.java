package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code social.SeedGroups} 命令的处理器（R1 的 T3）：**一次落 N 个人口批次**（{@link PopulationGroup}）——它是"人口实体"
 * 唯一的落盘入口；没有它，批次只能活在测试里。
 *
 * <pre>{@code
 * {"entries":[{"id":"rural:0_0:MALE","q":0,"r":0,"sex":"MALE","count":6000,"ageDays":13505,"stress":0},
 *             {"id":"urban:c-0_0:FEMALE","q":0,"r":0,"sex":"FEMALE","count":4000,"ageDays":13505,"stress":0}],
 *  "anchorTick":0?}
 * }</pre>
 *
 * <p>★ **一条命令 = 一条 revision**：全部 entries 由同一个 {@link SocialChangeSet} 承载（{@link
 * SocialChangeSet#between} 逐组件比一次），批内不存在"落了一半"的中间态。
 *
 * <p>★ **整条替换现在可带可选 {@code stress}**（缺省 0；非负由 {@link PopulationGroup} 的构造期守卫判）：重写既有批次时把原批次的
 * 压力原样带过，<b>不会静默清零</b>——组合工具 {@code simos.unit.levyRegion} 抽人力靠它保真。旧载荷不带 {@code stress} ⇒ 取 0，
 * 行为与从前逐值相同。
 *
 * <p>★ **anchorTick 缺省 = 世界当前时刻**（{@code state.meta().timestamp().tick()}，单位：日）——与 {@code
 * social.SetPopulation} 同款；给了就按给的记（创世批量落批次时由调用方一次定死，见 {@code WorldgenInitializeTool}）。
 *
 * <p>★ **覆盖语义**：同一 id 已在状态里 ⇒ **整条替换**（不是累加）。"改一批人的年龄/性别/人数"与"重新播种"因此是同一条路； 累加语义（出生/迁入）属 R4
 * 的人口再生产，不在这里装作能做。
 *
 * <p>★ **坏载荷与域规则违反都折成 {@code Rejected}**（照本模块惯例，见 {@link SocialPayloads}）：空 entries、{@code sex} 不在
 * 词表里、id 空白各抛 {@link IllegalArgumentException}；{@code count}/{@code ageDays}/{@code stress} 为负由
 * {@link PopulationGroup} 拒；**批次落在没有 {@code populations} 序列的格上**由 {@link SocialData} 的跨组件校验拒（设计稿
 * §十.7） —— 最后这条正是"两笔人口账不许各说各话"的命令边界落点。
 *
 * <p>★ **目标资源**（{@link CommandTargets}）：{@code entries[]} 里**每一个**格，路径取 social 命名空间的既有形态 {@link
 * ResourcePaths#social(int, int)}（{@code <q>_<r>}，**不带 mapId**）—— 与 {@code social.SetPopulation}
 * 判的是同一个资源 （"往这一格上落人"），故受限决策人的裁决路径不需要为它新增语法。
 */
public final class SeedGroupsHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = SocialPayloads.parse(payloadJson);
    LinkedHashSet<String> paths = new LinkedHashSet<>();
    for (PopulationGroup group : SocialPayloads.requireGroupEntries(payload, 0L).values()) {
      HexCoord at = group.residence();
      paths.add(ResourcePaths.social(at.q(), at.r()));
    }
    return List.copyOf(paths);
  }

  @Override
  public String type() {
    return "social.SeedGroups";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      long nowTick = state.meta().timestamp().tick();
      Map<PeopleLotId, PopulationGroup> entries =
          SocialPayloads.requireGroupEntries(payload, nowTick);
      Map<PeopleLotId, PopulationGroup> next = new LinkedHashMap<>(base.groups());
      next.putAll(entries); // ★ 同 id 覆盖（见类注的覆盖语义）
      // ★ 跨组件校验（"批次必须落在有 populations 序列的格上"）在 SocialData 的构造期判：这里不重复实现，
      //   违反它就由下面这条 catch 折成 Rejected。
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, base.withGroups(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
