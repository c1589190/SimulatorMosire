package io.mosire.simos.army.tools;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 军队工具面的**选人口**：战斗人员伤亡的"从 {@code Unit.households()} 的家户份额里抽 MALE + ADULT 若干人"。
 *
 * <p>★★ <b>为什么这一层必须注入、不许在 army 里重写</b>：选人层（户籍份额瀑布、候选校验、年龄档现算、不足整条拒的 具名口径）的全仓**唯一实现**是 app 的 {@code
 * HouseholdManpowerAllocator}——它同时服务征兵 / 组军 / 科举 / 消化单位 四条**不属军队**的调用链（那四条仍在 app）。在下放工具里重写一份 =
 * 造出第二份"谁能被抽走"的规则：两处可以各自漂移， 且都不会抛异常（多抽/少抽都是静默的）。
 *
 * <p>★ 本接口只能住在 {@code simos-army}：它的签名里有 {@link SocialData} / {@link HouseholdId} / {@link
 * HexCoord} 等领域类型——契约层 {@code simos-ga-spi} 不许依赖任何领域模块（I-G2）。
 *
 * <p>★ <b>历法时钟不在签名里</b>：年龄档现算要用 {@code CalendarClock}，而 {@code simos-calendar} 是 army 禁列里的模块。
 * 时钟由**实现**持有（app 的组合根按启动期 {@code CalendarService.load} 的同一实例注入），调用点只给世界日 {@code tick}——口径与今天
 * {@code ResolveCombatTool} 传 {@code calendarService.clock()} 完全一致。
 *
 * <p>★ <b>失败语义不许折平</b>：任一家户不存在、或合格份额总量不足 {@code requested} ⇒ 实现**原样抛出** 具名 {@link
 * IllegalArgumentException}（带 requested / available / 缺口），调用点按既有口径补上单位上下文后整条拒、 零
 * revision——不部分抽取、不换年龄档、不静默少抽。
 */
public interface ArmyManpowerPort {

  /**
   * 从**调用方点名的家户集合**里抽恰好 {@code requested} 名 {@code MALE + ADULT} 人（战斗人员伤亡的来源 = {@code
   * Unit.households()}，不适用辖区扫描）。
   *
   * @param social 社会状态（只读；家户位置、成员份额、批次属性都从这里取）
   * @param householdIds 指定来源家户 id 集合（每个元素必须已存在；允许 {@code UNIT} 位置的家户）
   * @param requested 请求人数（&ge; 0；0 = 整段跳过，返回空 shares）
   * @param tick 当前世界日（年龄现算的现在；&ge; 0）
   * @return 恰好选满 {@code requested} 人的不可变 {@link Allocation}
   * @throws IllegalArgumentException 任一入参为 null、{@code requested}/{@code tick} 为负、集合含 null、家户不存在，
   *     或合格份额总量不足（整条拒，不部分抽取）
   * @throws IllegalStateException 候选家户的 lot 不在 {@code social.groups()}，或份额超过批次人数（SocialData 不变量已破坏）
   */
  Allocation allocateMalesOfAdult(
      SocialData social, Set<HouseholdId> householdIds, long requested, long tick);

  /**
   * 一次选人结果（**镜像** {@code HouseholdManpowerAllocator.Allocation} 中调用点用得到的那一部分：份额瀑布）。
   *
   * @param shares 实际抽到的家户份额（瀑布选取顺序；{@code requested == 0} 时为空）
   */
  record Allocation(List<Share> shares) {

    public Allocation {
      Objects.requireNonNull(shares, "shares");
      // ★ 保序冻结在赋值处（不用 List.copyOf：本仓明文口径——迭代序不是内容的纯函数，且 SpotBugs 认不出它的不可变性）。
      shares = Collections.unmodifiableList(new ArrayList<>(shares));
    }
  }

  /**
   * 从某个家户对某个 lot 的份额里抽走的人数（**镜像** {@code HouseholdManpowerAllocator.ManpowerShare}）。
   *
   * @param householdId 来源家户 id
   * @param lotId 来源批次 id（保持原 id，不派生新 id）
   * @param hex 来源格 = {@code HEX} 家户 {@code location()} 的 hex；{@code UNIT} 家户（如 {@code
   *     hh-gov-<unitId>}） 没有格 ⇒ <b>null</b>（合法值：工单只需要 householdId + lotId + taken）
   * @param taken 从该户份额里抽走的人数（&gt; 0）
   */
  record Share(HouseholdId householdId, PeopleLotId lotId, HexCoord hex, long taken) {

    public Share {
      Objects.requireNonNull(householdId, "householdId");
      Objects.requireNonNull(lotId, "lotId");
      // ★ hex 允许 null：只有 HEX 家户的来源才有格；UNIT 家户没有格（不为了非空而伪造位置）。
      if (taken <= 0L) {
        throw new IllegalArgumentException("taken 必须 > 0: " + taken);
      }
    }

    /** 来源格（{@code HEX} 家户才有）；{@code UNIT} 家户没有格 ⇒ {@link Optional#empty()}。 */
    public Optional<HexCoord> hexOptional() {
      return Optional.ofNullable(hex);
    }
  }
}
