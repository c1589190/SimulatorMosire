#!/usr/bin/env python3
"""m3（brief §7）：造一个**孤立的窄写工具类**（`write/SdFooTool.java`），不接任何桶 ⇒ 期望 §5.5 判据红。

★ 事后由 `mut-round.sh` 的白名单段把该文件清掉（它不在 `write-whitelist.txt` 里），恢复干净世界。
"""
import os
import sys

WT = sys.argv[1]
WRITE_DIR = "simos-app/src/main/java/io/mosire/simos/app/tools/write"
BODY = '''package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/** 变异体 m3：一个**不接任何桶**的孤立窄写工具类（§5.5 判据的靶子）。 */
public final class SdFooTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.Foo";

  public SdFooTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "foo";
  }

  @Override
  public String description() {
    return "m3 变异体：孤立的窄写工具，不接任何桶";
  }
}
'''

path = os.path.join(WT, WRITE_DIR, "SdFooTool.java")
if os.path.exists(path):
    raise SystemExit("VOID: SdFooTool.java 已存在 —— 世界不干净")
with open(path, "w", encoding="utf-8") as handle:
    handle.write(BODY)
print("  created %s/SdFooTool.java（NAME=sd.Foo，不接任何桶）" % WRITE_DIR)
