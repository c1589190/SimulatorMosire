package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.unit.UnitId;

/**
 * 军队归属（spec §三.2）：把 {@code NationId} 与一个**单位根**（{@code UnitId}）关联起来——"带国家隶属的军队单位"。
 *
 * <p>★ **编制本身仍在 unit**（spec §十，铁律 3）：sd 只存**归属关系**，不拥有编制树。{@code rootUnit} 的存在性由命令期经 {@code
 * state.module("unit")} 读（spec §四）。
 */
public record Army(ArmyId id, NationId nationId, UnitId rootUnit, String name) {

  public Army {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (nationId == null) {
      throw new IllegalArgumentException("nationId 不得为 null");
    }
    if (rootUnit == null) {
      throw new IllegalArgumentException("rootUnit 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
  }
}
