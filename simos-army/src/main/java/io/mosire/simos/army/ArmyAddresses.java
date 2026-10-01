package io.mosire.simos.army;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import java.util.List;
import java.util.Objects;

/**
 * army 命名空间的**地址拼写单点**（阶段 D4 / 用户设计 D-012，2026-10-02）：{@code army:combat.<id>}。
 *
 * <p>★★ <b>为什么要有它</b>：交战记录是 Unit 状态链接的目标（{@code Unit.stateDescriptions}），链接值必须是 canonical 地址，而
 * canonical 只能由 {@link Address} AST 的 {@link Address#canonical()} 产出（不手拼）。此前这条 AST 只存在于 {@code
 * ArmyResolver}；D4 的 app 工具也要发同一个地址（Unit 侧链接 + 记录 id 的一致性），故提为公共拼写点——resolver 与工具从此不可能拼出两个地址。
 *
 * <p>★ 本类不 import app/core/sd（铁律 3）：它只认识 util 的地址语法与 army 自己的 id。
 */
public final class ArmyAddresses {

  /** 命名空间字面量（与 {@code ArmySnapshot.namespace()} / {@code ArmyCodec.namespace()} 同字面）。 */
  public static final String NAMESPACE = "army";

  /** 交战记录的根主体 kind（地址形态的第二段）。 */
  public static final String COMBAT_KIND = "combat";

  private ArmyAddresses() {}

  /** {@code ArmyAddresses.combat(id)} = canonical {@code army:combat.<id>} 的 AST。 */
  public static Address combat(CombatRecordId id) {
    Objects.requireNonNull(id, "id");
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of(COMBAT_KIND, id.value())));
  }

  /** canonical 地址文本（{@code army:combat.<id>}；id 需要加引时由 {@link Address#canonical()} 负责）。 */
  public static String combatCanonical(CombatRecordId id) {
    return combat(id).canonical();
  }
}
