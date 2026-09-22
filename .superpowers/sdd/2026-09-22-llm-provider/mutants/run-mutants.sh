#!/usr/bin/env bash
# M11 变异轮：每个变异体跑一遍干净 `./mvnw clean verify`（九道门禁），红点必须是**被保护断言**。
# 装置自证：orig md5 / mutant md5 / 还原后 md5 逐字节；每轮强制断言 COMPILATION ERROR 为 0（否则作废）。
# 不留档不删：每轮日志与 summary 都在本目录 logs/。
set -u
ROOT=/home/cna/SimulatorMosire
EV="$ROOT/.superpowers/sdd/2026-09-22-llm-provider/mutants"
LOGS="$EV/logs"
mkdir -p "$LOGS"
: > "$LOGS/summary.txt"

run_mutant() {
  local name="$1" file="$2" mode="$3" old="$4" new="$5" marker="$6"
  local bak="$EV/$name.orig.bak"
  cp "$file" "$bak"
  local orig_md5 mut_md5 now_md5
  orig_md5=$(md5sum "$file" | awk '{print $1}')
  if ! python3 "$EV/mutate.py" "$file" "$mode" "$old" "$new" >/dev/null; then
    echo "$name APPLY-FAIL" | tee -a "$LOGS/summary.txt"
    cp "$bak" "$file"
    return
  fi
  mut_md5=$(md5sum "$file" | awk '{print $1}')
  if [ "$orig_md5" = "$mut_md5" ]; then
    echo "$name VOID-no-byte-change" | tee -a "$LOGS/summary.txt"
    cp "$bak" "$file"
    return
  fi
  ( cd "$ROOT" && ./mvnw clean verify ) > "$LOGS/$name.log" 2>&1
  local rc=$?
  local comp red
  comp=$(grep -c "COMPILATION ERROR" "$LOGS/$name.log")
  red=$(grep -c "$marker" "$LOGS/$name.log")
  cp "$bak" "$file"
  now_md5=$(md5sum "$file" | awk '{print $1}')
  local restore=OK
  [ "$orig_md5" = "$now_md5" ] || restore=BAD
  local verdict=UNKNOWN
  if [ "$comp" != "0" ]; then verdict=VOID-compile-error
  elif [ "$red" != "0" ] && [ "$rc" != "0" ]; then verdict=KILLED
  elif [ "$red" = "0" ]; then verdict=SURVIVED
  fi
  echo "$name verdict=$verdict rc=$rc comp=$comp red_hits=$red orig=$orig_md5 mutant=$mut_md5 restore=$restore" | tee -a "$LOGS/summary.txt"
}

REG="$ROOT/simos-app/src/main/java/io/mosire/simos/app/llm/LlmProviderRegistry.java"
RES="$ROOT/simos-app/src/main/java/io/mosire/simos/app/llm/LlmProviderResolver.java"
HTTP="$ROOT/simos-app/src/main/java/io/mosire/simos/app/llm/HttpLlmClient.java"
SDP="$ROOT/simos-sd/src/main/java/io/mosire/simos/sd/spi/SetDecisionMakerProviderHandler.java"
SVS="$ROOT/simos-sd/src/main/java/io/mosire/simos/sd/spi/SetViewScopeHandler.java"
CAT="$ROOT/simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java"
PAN="$ROOT/simos-app/src/main/resources/webui/panels.js"

# m1：配置落盘时把密钥**值**写进 apiKeyRef 字段（密钥入库）。
run_mutant m1 "$REG" literal \
  'rows.add(ProviderRow.from(provider));' \
  'rows.add(new ProviderRow(provider.id(), provider.baseUrl(), provider.model(), provider.apiKeyRef().kind().name(), resolveSecret(provider.apiKeyRef()).orElse(provider.apiKeyRef().ref()), provider.timeout().toMillis()));' \
  'secretValueNeverLandsInTheConfigFileOrInTheViews'

# m2：查无 provider 时静默回落到第一个（未绑定/悬空不报错）。
run_mutant m2 "$RES" regex \
  '\.orElseThrow\(\(\) -> new IllegalStateException\("绑定的 LLM provider 不存在: " \+ providerId\)\)' \
  '.orElse(registry.list().get(0))' \
  'danglingProviderIdNamesTheIdAndNeverFallsBackToAnExistingProvider'

# m3：处理器把**未改**的 DecisionMaker 放回（绑定不落 revision）。
run_mutant m3 "$SDP" literal \
  'next.put(id, updated);' \
  'next.put(id, existing);' \
  'writesProviderBindingOntoTheDecisionMaker'

# m4：配权重建设有带回 providerId（配权静默丢绑定）。
run_mutant m4 "$SVS" regex \
  'existing\.decisionCadenceTicks\(\),\s*(?://[^\n]*\n\s*)?existing\.providerId\(\)\);' \
  'existing.decisionCadenceTicks());' \
  'settingViewScopeKeepsTheProviderBinding'

# m5：掩码视图回显密钥值。
run_mutant m5 "$REG" literal \
  'view.put("secretResolvable", resolveSecret(provider.apiKeyRef()).isPresent());' \
  'view.put("secretResolvable", resolveSecret(provider.apiKeyRef()).isPresent());\n    view.put("apiKeyValue", resolveSecret(provider.apiKeyRef()).orElse(""));' \
  'secretValueNeverLandsInTheConfigFileOrInTheViews'

# m6：HTTP 非 2xx 异常带回响应体（可能回显敏感内容）。
run_mutant m6 "$HTTP" literal \
  'throw new IllegalStateException("LLM 返回非 2xx 状态码: " + response.statusCode());' \
  'throw new IllegalStateException("LLM 返回非 2xx 状态码: " + response.statusCode() + " body=" + response.body());' \
  'nonTwoHundredThrowsWithoutLeakingTheKeyOrTheBody'

# m7：子页未知值兜成 view（fail-closed 破坏）。
run_mutant m7 "$PAN" regex \
  'return \{ ok: false, id: null, label: null \};' \
  'return { ok: true, id: "view", label: "决策人查看" };' \
  'frontend-gate] 前端测试失败'

# m8：缺 baseUrl 读成默认值（不报错）。
run_mutant m8 "$PAN" literal \
  '      return { ok: false, error: "baseUrl 必填" };' \
  '      baseUrl = "default-base-url";' \
  'frontend-gate] 前端测试失败'

# m9：CatalogTool 缺项不再构造期抛（静默兜底）。
run_mutant m9 "$CAT" literal \
  'if (!missing.isEmpty()) {' \
  'if (false && !missing.isEmpty()) {' \
  'catalogRejectsACommandTypeWithoutAPayloadHint'

echo "=== summary ==="
cat "$LOGS/summary.txt"
