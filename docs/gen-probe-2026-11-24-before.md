# AI 出题稳定性探针报告

- 模型：`deepseek-ai/DeepSeek-V4.1-Flash`
- 配置：`timeoutMs=30000`, `maxOutputTokens=4096`
- 轮次：10（每轮请求 4 题，源题干长度 318 字）
- 成功率：**10/10 (100.0%)**
- completion_tokens 均值：**2308.1**
- reasoning_tokens 均值：**1184.6**
- 失败码分布：`无`

| 轮次 | 结果 | completion | reasoning | 产题数 | 失败码 |
|---|---|---|---|---|---|
| 1 | SUCCESS | 2204 | 1201 | 4 | - |
| 2 | SUCCESS | 2221 | 1121 | 4 | - |
| 3 | SUCCESS | 2154 | 1076 | 4 | - |
| 4 | SUCCESS | 2361 | 1181 | 4 | - |
| 5 | SUCCESS | 2487 | 1244 | 4 | - |
| 6 | SUCCESS | 2271 | 1234 | 4 | - |
| 7 | SUCCESS | 2189 | 1192 | 4 | - |
| 8 | SUCCESS | 2414 | 1206 | 4 | - |
| 9 | SUCCESS | 2621 | 1311 | 4 | - |
| 10 | SUCCESS | 2159 | 1080 | 4 | - |
