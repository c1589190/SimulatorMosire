package io.mosire.simos.unit;

import java.util.Optional;

/**
 * 军队编制（阶段 9，2026-09-30 裁定 3/8）：被打上军队模块负责标签的单位挂它——一期只记"认哪个 GOV 当主子 + 职责短名"。
 *
 * <p>★ <b>字段语义</b>：{@code masterGov} = 认领的 GOV 单位（指挥/视野的隶属关系，未认领用 {@code Optional.empty()}）；{@code
 * role} = 兵种/职责短名（自由文本，<b>词表后置</b>）。★ 它<b>不算战力</b>：Army 模块一期只做 编制/隶属/视野派生（裁定 8），"一个 Army 认一个 GOV、视野
 * = 位置 + 半径"是 gov/army 侧的算法，不在 unit 侧。
 *
 * <p>★ <b>不变量</b>：{@code masterGov}（Optional 本身）非 null；{@code role} 非 null、非空白；违反一律当场抛 {@link
 * IllegalArgumentException}。
 *
 * @param masterGov 认领的 GOV 单位（未认领用 {@code Optional.empty()}；Optional 本身非 null）
 * @param role 兵种/职责短名（非 null、非空白；词表后置）
 */
public record ArmyFormation(Optional<UnitId> masterGov, String role) implements UnitModule {

  public ArmyFormation {
    if (masterGov == null) {
      throw new IllegalArgumentException("masterGov 不得为 null（未认领用 Optional.empty()）");
    }
    if (role == null || role.isBlank()) {
      throw new IllegalArgumentException("role 不得为空白");
    }
  }
}
