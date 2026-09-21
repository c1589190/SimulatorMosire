# 直接打真 provider 的原始证据（显式标注：**直连 provider，非经 app 的 LlmClient**）
# 命令：curl -sS --noproxy '*' -H 'Authorization: Bearer <redacted>' -d '{...D6 prompt...}' http://121.40.130.178:3000/v1/chat/completions

## 首帧（id / model / system_fingerprint）
data: {"id":"a793c402-b743-4160-ae48-bd32e444f0d6","object":"chat.completion.chunk","created":1790014527,"model":"deepseek-flash","system_fingerprint":"aeb56401ca74e127821c4f9126dcb669","choices":[{"index":0,"delta":{"role":"assistant","content":null,"reasoning_content":""},"logprobs":null,"finish_reason":null}],"usage":null}

## 末尾 usage
"usage":{"prompt_tokens":82,"completion_tokens":476,"total_tokens":558,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":421}

## 尾部 content（模型最终 JSON 正文，拼接）
{\disposition\暂缓执行\rationaleText\未提供当前交战命令的具体内容、授权依据、目标识别信息和附带损害评估，无法在断点D6确认其合法性、必要性与比例性；在补充完整信息前不应执行。\}
