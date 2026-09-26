package io.mosire.simos.economy.api.relation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;

/**
 * ★★ <b>一条补偿规则的受方：要么是经济主体，要么是人口 cohort —— **恰其一**</b>（spec §2.4 的 {@code recipient}；裁定 E4 的载体）。
 *
 * <p>★★ <b>为什么是 sealed 两种变体，而不是「actor 字段 + cohort 字段」两个可空槽</b>：spec §2.1 明说四个概念 （{@code
 * LaborProvider} / {@code ProductionOperator} / {@code AssetOwner} / {@code
 * InventoryOwner}）<b>不默认相同</b> ⇒
 * 「受方是谁」必须能被<b>类型</b>回答。两个可空槽会把「恰其一」降级成<b>运行时检查</b>（"两个都填了怎么办"/"两个都空怎么办" 永远会有第三种答案），而 sealed + 两个
 * record 变体让它成为<b>类型事实</b>：{@code switch} 必须写全两支，编译器替你把 "漏了一支"变成编译错。
 *
 * <p>★★ <b>它同时是裁定 E4 的落点</b>：spec §2.4 原本把规则分成 {@code laborCompensationRules} + {@code
 * assetCompensationRules} <b>两张表</b>，而「受方是谁」这一维已经由 {@code recipient} 表达 ⇒ 两张表是<b>第二拼写点</b>
 * （同一批规则能被分成两处、次序也被切成两段，于是「先给养后地租」这种次序<b>无从表达</b>）。⇒ 单列表 + {@code priority} （见 {@link
 * ProductionRelation}），且<b>地租显式写给 {@link ToCohort}（{@code (hex, landlord)}）</b> —— 不显式给， 地主 cohort
 * 的粮源会凭空消失（地主既不是劳动者、也不是 actor）。
 *
 * <p>★ <b>两个变体的字段各自判 null 即抛</b>（不猜）：一条「没有受方」的规则不是状态，是坏数据。
 *
 * <p>★★ <b>线格式：类型信息以注解钉在本接口上</b>（S1 阶段 4+5 Task 2 补；先例 = {@code FieldDelta} 的 M4 裁定 4 与 {@code
 * AllocationRule}）。理由与那两处逐字相同：本接口是 <b>sealed 多态类型</b>，而它<b>进了状态树</b> （{@code EconomyData.relations}
 * → {@code EconomyChangeSet} → 每一条 revision 的 JSON）⇒ 裸往返不可能： 写得出字节，读回时"要造哪个变体"没有依据（{@code no
 * Creators / abstract types}）。**选注解而不是 mixin**： mixin 必须在本模块<b>之外</b>的每一台 mapper 上补注册（{@code
 * EconomyCodec}、{@code Timeline} 那台…）， 忘了就是静默失效；注解跟着类型走，连裸 {@code new ObjectMapper()} 都认得。{@code
 * Id.NAME} 而非 {@code Id.CLASS}：把 <b>短名</b>写进存档，读入侧只接受<b>本接口声明的</b>子类集（封闭）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Recipient.ToActor.class, name = "to_actor"),
  @JsonSubTypes.Type(value = Recipient.ToCohort.class, name = "to_cohort"),
})
public sealed interface Recipient permits Recipient.ToActor, Recipient.ToCohort {

  /** 受方是<b>经济主体</b>（{@code ActorRef} 是身份；actor 的种类/粒度由产出方定，本层不解释）。 */
  record ToActor(ActorRef actor) implements Recipient {

    public ToActor {
      if (actor == null) {
        throw new IllegalArgumentException("Recipient.ToActor.actor 不得为 null");
      }
    }
  }

  /**
   * 受方是<b>人口 cohort</b>（某一格上的某个社会阶层，spec §2.6）。
   *
   * <p>★ 阶段 4+5 里这种受方<b>落到消费行</b>（{@code ClassRow.goods}，裁定 R5 的过渡分配），阶段 6 起换成 {@code
   * ConsumptionReceipt}（§2.5 的流量）—— 本层只认身份，不管落到哪。
   */
  record ToCohort(CohortKey cohort) implements Recipient {

    public ToCohort {
      if (cohort == null) {
        throw new IllegalArgumentException("Recipient.ToCohort.cohort 不得为 null");
      }
    }
  }
}
