package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.SdInfoId;

/**
 * {@link SdInfoEntry} 的 ID 合成（第 3 波第 1 步）：**无显式 id 时** id 形态的唯一拼写点。
 *
 * <p>★ 形制：{@code <canonical 地址>#<该地址下追加序号>}。**注入性由两半共同保证**——canonical 地址在 {@code SdState.info}
 * 的键里唯一（见 {@link io.mosire.simos.sd.state.SdState} 的类注），序号在同一地址的列表内**严格递增** （条目只追加、不删改，见各写路径）⇒
 * 同一状态里不会出现两个相同 id。
 *
 * <p>★ **为什么唯一性在写路径按构造成立、而不在 {@code SdState} 构造期再扫一遍全树**：老档（本字段出现之前落盘的字节）里 没有 {@code id}
 * 键，迁移缺省无法还原"当年的真实 id"；若在构造期强制"全树 id 唯一"，那些老的、内容派生 id 一旦撞车就会 让**整个世界打不开**（{@link
 * io.mosire.simos.sd.codec.SdCodec} 的老档用例钉住"读回来不炸"）。故去重放在**命令期**： {@code PutInfoHandler} 对**载荷显式给的
 * id** 做重复检查（合成 id 本就注入，无需检查）。
 *
 * <p>★ 纯函数：id 只是 (地址, 序号) 的忠实标签，不携带随机/时间成分 ⇒ 可重放。
 */
public final class SdInfoIds {

  private SdInfoIds() {}

  /**
   * 按 (canonical 地址, 该地址下的追加序号) 合成 id。
   *
   * @param canonicalAddress {@code Address#canonical()}（同 {@code SdState.info} 的键）
   * @param ordinal 该地址下**追加前**的条目数（= 新条目的下标）
   */
  public static SdInfoId synthesize(String canonicalAddress, int ordinal) {
    if (canonicalAddress == null || canonicalAddress.isBlank()) {
      throw new IllegalArgumentException("canonicalAddress 不得为空白");
    }
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal 必须 ≥ 0: " + ordinal);
    }
    return SdInfoId.parse(canonicalAddress + "#" + ordinal);
  }
}
