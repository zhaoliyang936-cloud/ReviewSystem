# AI 出题稳定性探针报告

- 模型：`deepseek-ai/DeepSeek-V4.1-Flash`
- 配置：`timeoutMs=60000`, `maxOutputTokens=8192`
- 轮次：10（每轮请求 4 题，源题干长度 318 字）
- 成功率：**10/10 (100.0%)**
- completion_tokens 均值：**1576.3**
- reasoning_tokens 均值：**839.2**
- 失败码分布：`无`

| 轮次 | 结果 | completion | reasoning | 产题数 | 失败码 |
|---|---|---|---|---|---|
| 1 | SUCCESS | 1466 | 734 | 4 | - |
| 2 | SUCCESS | 1766 | 984 | 4 | - |
| 3 | SUCCESS | 1554 | 829 | 4 | - |
| 4 | SUCCESS | 1568 | 884 | 4 | - |
| 5 | SUCCESS | 1594 | 850 | 4 | - |
| 6 | SUCCESS | 1440 | 721 | 4 | - |
| 7 | SUCCESS | 1628 | 913 | 4 | - |
| 8 | SUCCESS | 1521 | 811 | 4 | - |
| 9 | SUCCESS | 1738 | 921 | 4 | - |
| 10 | SUCCESS | 1488 | 745 | 4 | - |
