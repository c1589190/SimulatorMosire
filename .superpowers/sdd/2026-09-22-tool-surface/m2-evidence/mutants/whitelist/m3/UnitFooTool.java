package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * 孤儿工具（M2 变异体 m3）：实现了窄写工具类，却**没有**接进任何桶。
 *
 * <p>它存在的唯一目的是证明 {@code SimosToolsTest.everyNarrowWriteToolClassIsWiredIntoTheGmBucket}
 * 这条同源判据**不是装饰**——在本变异体出现之前，"写了工具类却忘了接桶"没有任何断言会红。
 */
public final class UnitFooTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.NotARealCommand";

  public UnitFooTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "孤儿工具 branch=" + args.get("branch");
  }

  @Override
  public String description() {
    return "孤儿工具（变异体）：固定 unit.NotARealCommand——**不接任何桶**，只为证明同源判据有牙";
  }
}
