package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>候选生产方式 ID</b>（R4-E2）：一条 {@code ProductionCandidate}（GM 登记的预设）的稳定身份，归 {@code economy} 切片。
 *
 * <p>★★ <b>版本不是身份的一部分</b>：同一个 {@link CandidateId} 可以登记多个 {@code version}（修订 = 新 version）， 但 {@code
 * EconomyData.candidates} 的键仍是本 id（表只保留该 id 的当前版本；旧 unit 的 {@code modeKey} 保持 {@code id@version}
 * 不变）。故本类型只校验非空白，不把 version 编进值里。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；{@code parse} 是 opaque 的，旧档/外部工具写下的
 * id 原样读回。
 */
public record CandidateId(String value) {

  public CandidateId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CandidateId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（格式的权威在命令层/调用方）。 */
  public static CandidateId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CandidateId 不得为空白: " + text);
    }
    return new CandidateId(text);
  }
}
