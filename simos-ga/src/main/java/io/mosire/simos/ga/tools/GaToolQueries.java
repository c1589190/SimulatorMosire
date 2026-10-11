package io.mosire.simos.ga.tools;

import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>决策人读面的窄查询口</b>（2026-10-12 B3 第 7 域）：把 app 的 {@code SdQueryService}（决策人/令清单）与 {@code
 * RedactingQueryService}（**按调用者视角裁剪**的判决 / 决策结果 / 文档 / 上报）收成"给 id + 坐标、回**成品视图**"的一层。
 *
 * <p>★ <b>为什么回成品视图而不是领域对象</b>：那两条服务的返回值里混着 app 的类型（{@code SdQueryService.DecisionMakerInfo} /
 * {@code DirectiveInfo}、{@code ResourceScopeMap}、{@code DecisionScopeView}、{@code
 * GameMap}），而裁剪切面本身要吃 {@code DecisionScopeFunctions} + {@code PlanCatalog} + {@code ApiViews}（全是
 * app 的装配物与视图）⇒ 按 I-G2 只能住 app。<b>接口在 ga、实现在 app</b>：实现逐条转调既有唯一实现（裁剪判据不抄第二份——那是权限面）。
 *
 * <p>★ <b>窗口类型是 ga 自己的镜像</b>（{@link Window}）：app 的 {@code
 * RedactingQueryService.DecisionResultWindow} 进不了本模块；镜像**逐字段同形**（含构造期三条断言：{@code limit} 为正、{@code
 * tick} 与区间互斥、{@code fromTick ≤ toTick}），转换只在 app 适配器一处 ⇒ 边界语义与迁入前逐字相同（拒因文案也相同，调用方折成 {@code
 * BAD_REQUEST}）。
 *
 * <p>★ <b>失败语义不许折平</b>：查无决策人 / 不认识的上报地址这些**由实现原样抛出**（今天就是异常），调用方按既有口径折成工具结果； 本接口不 catch、不兜底。
 */
public interface GaToolQueries {

  /**
   * 决策结果的**可见窗口**（镜像 app 的 {@code RedactingQueryService.DecisionResultWindow}，逐字段同形）。
   *
   * <p>★ 三条构造期断言与 app 侧**逐字相同**（同一处口径，不允许两套边界）。
   */
  record Window(Long tick, Long fromTick, Long toTick, int limit) {

    public Window {
      if (limit <= 0) {
        throw new IllegalArgumentException("limit 必须为正: " + limit);
      }
      if (tick != null && (fromTick != null || toTick != null)) {
        throw new IllegalArgumentException("tick 与 fromTick/toTick 互斥（二选一）");
      }
      if (fromTick != null && toTick != null && fromTick > toTick) {
        throw new IllegalArgumentException("fromTick 不得大于 toTick: " + fromTick + " > " + toTick);
      }
    }
  }

  /**
   * 决策人清单（{@code {id,affiliation,rootUnit,allowedTools,providerId,…}} 的成品视图）。
   *
   * @param affiliationFilterRaw 归属过滤的**原始入参**（{@code null}/空白 = 全部）；解析与拒因住在 app（唯一实现）
   */
  List<Map<String, Object>> decisionMakers(
      String affiliationFilterRaw, BranchId branch, RevisionId revision);

  /**
   * 单个决策人（{@code ApiViews.decisionMaker} + 现算可见范围 {@code ApiViews.decisionScope}）。
   *
   * @return 查无此人 ⇒ 空（调用方据此给 {@code NOT_FOUND}）
   */
  Optional<Map<String, Object>> decisionMaker(
      String decisionMakerIdRaw, BranchId branch, RevisionId revision);

  /**
   * 决策记录（令）清单：{@code decisionMakerIdRaw == null} = 全量；给了则只看该决策人。
   *
   * @return 给了 id 但查无此人 ⇒ 空（调用方据此给具名拒因）
   */
  Optional<List<Map<String, Object>>> directives(
      String decisionMakerIdRaw, BranchId branch, RevisionId revision);

  /**
   * 判决清单（{@code DisclosurePolicy} 三档裁剪）。
   *
   * @param actorIdRaw {@code null} = FULL 全量披露（GM 面）；给了 = 按该决策人视角裁剪
   */
  List<Map<String, Object>> verdicts(String actorIdRaw, BranchId branch, RevisionId revision);

  /** 决策结果（只回**与该 actor 有关**的 INFO 条目，按 tick 降序、同 tick 按 id 升序，先排后截）。 */
  List<Map<String, Object>> decisionResults(
      DecisionMakerId actor, Window window, BranchId branch, RevisionId revision);

  /** 设定文档（判据：{@code tags} 含该 actor **或** {@code affiliations} 含其归属，两轴取并集）。 */
  List<Map<String, Object>> docs(
      DecisionMakerId actor, Window window, BranchId branch, RevisionId revision);

  /**
   * 某个决策人**此刻该不该跑一轮**的读数据（{@code simos.sd.run-decision-makers} 的 preview 面）。
   *
   * @param id 决策人 id
   * @param providerId 绑定的 LLM provider（未绑定 ⇒ null）
   * @param cadence 决策节奏（tick）
   * @param due 是否到期
   * @param lastDirectiveTick 上次出令的 tick（没有 ⇒ null）
   * @param ticksSinceLast 距上次出令的 tick 数（没有 ⇒ null）
   * @param allowedTools 该决策人的显式白名单（**已排序**；空 = 沿用全局白名单）
   */
  record MakerDue(
      String id,
      String providerId,
      long cadence,
      boolean due,
      Long lastDirectiveTick,
      Long ticksSinceLast,
      List<String> allowedTools) {

    public MakerDue {
      // ★ 保序冻结在赋值处（不用 List.copyOf：本仓明文口径——迭代序不是内容的纯函数）。
      allowedTools =
          java.util.Collections.unmodifiableList(new java.util.ArrayList<>(allowedTools));
    }
  }

  /** 全量决策人的到期信号（一次读全量、按 id 索引：同一 base state 上算出的 due 不会因逐次查询而漂移）。 */
  List<MakerDue> makerDueSignals(BranchId branch, RevisionId revision);

  /**
   * 上报（{@code simos.sd.reports}）的决策人可见性 = {@code tags} / 归属 / **发送人 = 自己** 三轴的并集判据。
   *
   * <p>★ 前两轴的唯一实现在 app 的 {@code RedactingQueryService.visible}（**不许在这里重写一份**）；本方法只多出"自己发的" 那一支。
   *
   * @param affiliation 调用者的归属；解不出 ⇒ null（该轴不参与）
   * @param sender 报告 {@code value.from}（解析不出 ⇒ null，该轴不参与）
   */
  boolean reportVisibleToDecisionMaker(
      SdInfoEntry entry, DecisionMakerId actor, Affiliation affiliation, String sender);
}
