package io.mosire.simos.sd.model;

import java.util.Optional;

/**
 * 一条**有向**外交关系边的内容（D-003 / R6）：以**自然语言**描述为主，模型尽量小——**不做**类型化关系状态机、关系值或期限。
 *
 * <p>字段：
 *
 * <ul>
 *   <li>{@link #kind}——自由文本，**可空**；"称臣纳贡"只是它的一个取值（D-004：贡额/周期/违约一律<b>不</b>写死，所以本类型 不解释 kind
 *       的含义、也不因某个 kind 施加任何规则）；
 *   <li>{@link #text}——自然语言描述；**谈判/联动裁决的状态与情况就记在这里**（D-005），再调 {@code sd.SetDiplomaticRelation}
 *       就是更新它；
 *   <li>{@link #updatedTick}——该边最后一次写入所用的 tick（单位：日；允许补记过去，但命令期拒绝未来，与 {@code sd.PutInfo}/{@code
 *       sd.IssueDirective} 同口径）。
 * </ul>
 *
 * <p>★ 两端（from/to）在 {@link DiplomaticRelationKey} 里，值不重复存一份：端点身份属于边的键，不是边的属性。
 */
public record DiplomaticRelation(Optional<String> kind, String text, long updatedTick) {

  public DiplomaticRelation {
    // ★ 老档/手工载荷的缺省：kind 缺省 = 无 kind（本字段本来就是"可空"）。
    if (kind == null) {
      kind = Optional.empty();
    }
    if (kind.isPresent() && kind.get().isBlank()) {
      kind = Optional.empty(); // 空白与"没写"在本语义下没有区别
    }
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("外交关系的 text 不得为空白（自然语言描述是这条边的本体）");
    }
    if (updatedTick < 0) {
      throw new IllegalArgumentException("updatedTick 必须 ≥ 0: " + updatedTick);
    }
  }
}
