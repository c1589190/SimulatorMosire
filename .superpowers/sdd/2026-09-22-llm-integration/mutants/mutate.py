#!/usr/bin/env python3
"""把一处**故意违规**写进目标源文件（按 label）。只做一次 replace；未命中即失败。"""
import sys

MUTATIONS = {
    # 查无 provider ⇒ 拆掉 fail-closed 守卫（应红：LlmProviderResolverTest 的 E_NOT_FOUND 断言）
    "m1-fail-closed-not-found": (
        "simos-app/src/main/java/io/mosire/simos/app/llm/LlmProviderResolver.java",
        "    if (!config.availableNames().contains(id)) {",
        "    if (false) {",
    ),
    # 未绑定 ⇒ 拆掉 fail-closed 守卫（应红：LlmProviderResolverTest 的 E_UNBOUND 断言）
    "m2-fail-closed-unbound": (
        "simos-app/src/main/java/io/mosire/simos/app/llm/LlmProviderResolver.java",
        "    if (providerId == null || providerId.isBlank()) {",
        "    if (false) {",
    ),
    # 判决请求不再带采样（应红：AdjudicationEndToEndTest 的 max_tokens=4096 断言）
    "m-sampling-max-tokens": (
        "simos-app/src/main/java/io/mosire/simos/app/llm/LlmProviderResolver.java",
        ".withMaxTokens(MAX_OUTPUT_TOKENS));",
        ".withMaxTokens(7));",
    ),
    # CONFIG 类失败也折成 Failed（该炸却降级）（应红：AdjudicationTest 的 nonDegradable 断言）
    "m4-config-degrades": (
        "simos-sd/src/main/java/io/mosire/simos/sd/adjudication/LlmDecisionAdjudicator.java",
        "      if (!e.degradable()) {",
        "      if (false) {",
    ),
    # 提示词不再把输出 schema 交给模型（应红：promptCarriesTheOutputSchema 断言）
    "m-schema-not-sent": (
        "simos-sd/src/main/java/io/mosire/simos/sd/adjudication/LlmDecisionAdjudicator.java",
        "        + request.outputSchemaJson()",
        "        + \"{}\"",
    ),
    # 密钥值写进路由条目（应红：apiKeyGoesToKeysAndNeverIntoTheRouteEntry 断言）
    "m6-key-into-routes": (
        "simos-app/src/main/java/io/mosire/simos/app/llm/AgentLibLlmConfig.java",
        "    configStore.put(\n        SECTION_KEYS,\n        name,",
        "    configStore.put(\n        SECTION_LLM,\n        KEY_ROUTES + \".\" + name,",
    ),
    # 坏条目被隐藏（枚举只列合法的）（应红：brokenRouteIsStillVisibleWithItsErrorCode 断言）
    "m7-broken-hidden": (
        "simos-app/src/main/java/io/mosire/simos/app/llm/AgentLibLlmConfig.java",
        "    return LlmRouteLoader.availableNames(configStore);",
        "    List<String> all = LlmRouteLoader.availableNames(configStore);\n"
        "    List<String> valid = new ArrayList<>();\n"
        "    for (String n : all) {\n"
        "      if (Boolean.TRUE.equals(view(n).get(\"valid\"))) {\n"
        "        valid.add(n);\n"
        "      }\n"
        "    }\n"
        "    return List.copyOf(valid);",
    ),
    # 不再用仓库默认配置兜底（应红：repoDefaultConfigSeedsAnEmptyStore 断言）
    "m-repo-seed-removed": (
        "simos-app/src/main/java/io/mosire/simos/app/llm/AgentLibLlmConfig.java",
        "    if (migrated == 0) {\n      migrated = migrateFrom(repoDefaultConfig);\n    }\n",
        "",
    ),
    # 取密钥成功时把值打进日志（应红：resolvedKeyValueNeverLeaksIntoLogs 断言）
    "m3-key-value-logged": (
        "simos-app/src/main/java/io/mosire/simos/app/llm/SimosApiKeySource.java",
        '"读取密钥引用 kind=CONFIG name={} length={}", safeName(credentialsRef), value.length());',
        '"读取密钥引用 kind=CONFIG name={} length={} value={}",\n'
        "                  safeName(credentialsRef),\n"
        "                  value.length(),\n"
        "                  value);",
    ),
}


def main():
    label = sys.argv[1]
    root = sys.argv[2]
    rel, old, new = MUTATIONS[label]
    path = f"{root}/{rel}"
    with open(path, encoding="utf-8") as f:
        content = f.read()
    if content.count(old) != 1:
        print(f"ANCHOR-MISS count={content.count(old)}", file=sys.stderr)
        sys.exit(2)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content.replace(old, new, 1))
    print("patched", rel)


if __name__ == "__main__":
    main()
