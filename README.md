# ReviewSystem

基于知识点掌握度的「数据结构」个性化复习系统。

学生侧：做练习 → 看掌握度 → 按推荐复习 → 错题重做与变式 → 向 AI 请教。
教师侧：维护章节与知识点、编辑四类题型、上传讲义、看覆盖度、处理题目复核工单。

当前状态：M01—M12 全部完成。后端 Spring Boot 3.5 / Java 21，83 个类；前端 Vue 3 单页应用，13 条顶层路由（管理端 10 条子路由）、9 个自建基础组件；数据库 16 个 Flyway 迁移（V1—V16）。功能验收台账见 [docs/M11验收清单.md](docs/M11验收清单.md)：**54 项通过 / 0 项不通过 / 3 项经确认不适用**。

---

## 功能

| 模块 | 能力 | 关键约束 |
| --- | --- | --- |
| 身份与权限 | 注册、登录、学生/管理员两种角色、CSRF、登录失败限流、会话 30 分钟 | 注册接口不接受 `role`，越权请求返回 403 |
| 题库 | 单选 / 填空 / 简答 / 算法设计四类题型；题干与选项支持图片 | 版本不可变，编辑生成新版本；乐观锁 `expectedVersion` 防覆盖 |
| 组卷 | 固定比例 7:3（题库 : AI 生成），异步 `PREPARING` | AI 不可用时按槽位降级到题库，结果标记 `degraded`，不算失败 |
| 练习与复习任务 | 草稿保存、提交、评分、结果查看、重试评分 | 同一用户同时只能有一个活动任务；重复提交幂等 |
| 掌握度 | 规则分 `M_rule` + AI 综合分 `M_final`，五档状态 | `M_final` 被限制在 `M_rule ± 8`；证据不足（N<3）为 `INSUFFICIENT` |
| 推荐 | 按优先级公式给出复习项与推荐理由 | 含冷却机制；候选不足时明确说明缺口，不虚报条数 |
| 错题本与变式 | 答错入本、重做、达标记为已掌握、对错题生成变式 | 变式回链原题，答对变式不改变原题掌握状态 |
| AI 辅导 | 生成讲解与自测，支持反馈 | 引用必须能对上资料；输出做脚本与外链图片清洗；结果保留 31 天 |
| 讲义素材 | 上传、编辑、发布、撤回、检索检查 | 草稿与已撤回资料不进入学生检索 |
| 管理端 | 章节/知识点、题库、素材、覆盖度报表、复核工单 | 3 个不同用户举报同一题才开工单；下架题目退出推荐与练习 |
| 数据导入 | manifest 批量导入题库与资料，产出导入报告 | 幂等键 `sourceLabel + sourceQuestionNo + type`，重复导入不重复入库 |

### 两条核心规则

**掌握度**：客观题 `y∈{0,1}`、权重 1；主观题 `y = 建议分/100`、权重 0.6。

```
M_rule  = 100 × (Σ w·y + 1) / (Σ w + 2)
M_final = clamp(M_ai, M_rule − 8, M_rule + 8)
```

同一知识点最多取最近 20 道不同题的最新一次有效作答；同一题重复作答只更新、不增加 N。

**AI 边界**：只接 OpenAI 兼容的 `/v1/chat/completions`，没有 mock 供应方分支。`AI_ENABLED=false` 时系统完整可用，只是出题退回题库、辅导退回规则摘要、综合判断退回 `M_rule`。

---

## 快速开始

### 1 环境

| 依赖 | 版本（本机实测） |
| --- | --- |
| JDK | 21（Zulu 21.0.13） |
| Maven | 3.9 |
| Node.js | 24.19 |
| PostgreSQL | 16.15 |

### 2 建库

```sql
CREATE DATABASE reviewsystem;
```

表结构由 Flyway 在首次启动时迁移到 V16，无需手工执行 SQL。

### 3 配置

```bash
cp .env.example .env
```

`.env` 不进版本库。必须改的是数据库连接；AI 四项（`AI_ENABLED` / `AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL`）不填也能跑，保持 `AI_ENABLED=false` 即可。

### 4 启动后端

bash / Git Bash 注入环境变量：

```bash
set -a; . ./.env; set +a
mvn -f backend/pom.xml -DskipTests package
java -jar backend/target/backend-0.1.0-SNAPSHOT.jar
```

PowerShell：

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim('"'), 'Process')
  }
}
mvn -f backend/pom.xml -DskipTests package
java -jar backend/target/backend-0.1.0-SNAPSHOT.jar
```

健康检查：`GET http://localhost:8080/api/health` → `{"status":"UP"}`

### 5 管理员账号

首次启动时由环境变量注入，仅在库中还没有 ADMIN 时插入，重复启动安全：

```bash
ADMIN_USERNAME=admin ADMIN_PASSWORD='Your#Password1' java -jar backend/target/backend-0.1.0-SNAPSHOT.jar
```

### 6 启动前端

```bash
cd frontend
npm install
npm run dev
```

默认 `http://localhost:5173`，`/api` 代理到 `http://localhost:8080`。后端换端口时设 `VITE_BACKEND_TARGET`。

生产构建：`npm run build`，产物在 `frontend/dist`。

---

## 配置项

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/reviewsystem` | 数据库连接串 |
| `DB_USER` / `DB_PASSWORD` | `reviewsystem` / 空 | 数据库账号 |
| `SERVER_PORT` | `8080` | 后端端口 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | 空 | 管理员初始化，两者都为空则跳过 |
| `AI_ENABLED` | `false` | 关闭时全链路走降级路径 |
| `AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL` | 空 | OpenAI 兼容供应方；`AI_ENABLED=true` 时缺失会启动失败 |
| `AI_TIMEOUT_MS` / `AI_MAX_OUTPUT_TOKENS` | `60000` / `8192` | 单次调用超时与输出上限 |
| `AI_DAILY_CALL_LIMIT` | `200` | 全局限额 |
| `AI_QUOTA_*` | 见 `.env.example` | 出题 / 评分 / 综合判断 / 辅导的每日配额 |
| `PRACTICE_BANK_RATIO` / `PRACTICE_AI_RATIO` | `0.7` / `0.3` | 组卷比例，改动需同时升 `PRACTICE_MIX_VERSION` |
| `QUESTION_ASSET_DIR` / `ANSWER_ASSET_DIR` | `~/.reviewsystem/...` | 题干图片与作答图片目录 |

---

## 测试

后端 21 个测试类，需要真实库：

```bash
export DB_URL=jdbc:postgresql://127.0.0.1:5432/rs_test
export DB_USER=postgres DB_PASSWORD=
mvn -f backend/pom.xml test -Dtest=MasteryCalculationTest
```

全量套件在空库上共用一个上下文时不稳（多个 `@SpringBootTest` 共用库 + 后台调度器并发），**结论以单类隔离跑为准**。

前端：

```bash
cd frontend && npm test && npm run build
```

端到端验收脚本（PowerShell，自己起库、起应用、跑完停服）：

- `scripts/m11/closure.ps1` —— 16 步主链路闭环
- `scripts/m11/security.ps1` —— 26 项权限与安全矩阵

两者都硬校验目标库名，产物写到 `scripts/m11/out/`。

---

## 目录

```
ReviewSystem/
├─ backend/
│  ├─ src/main/java/cn/reviewsystem/    业务代码（api / security / course / question /
│  │                                    practice / mastery / recommend / review / wrong /
│  │                                    ai / material）
│  ├─ src/main/resources/db/migration/  Flyway 迁移 V1—V16
│  ├─ src/main/resources/prompts/       AI 提示词模板
│  ├─ src/test/java/                    21 个测试类
│  └─ scripts/                          题库与资料导入 manifest
├─ frontend/src/                        styles / components / admin / practice / mastery /
│                                       review / wrong / ai
├─ scripts/m11/                         闭环与安全验收脚本及产物
├─ docs/                                需求、技术设计、里程碑测试记录、验收台账
└─ 学习资料/                            课程原始资料（不入库）
```

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [需求分析文档](docs/需求分析文档.md) | 需求 V0.5：功能与非功能需求、验收口径 |
| [技术设计文档](docs/技术设计文档.md) | 数据模型、状态机、接口契约、AI 降级策略 |
| [M11 验收清单](docs/M11验收清单.md) | 54 条验收项与逐条证据 |
| [M01 启动与验证](docs/M01启动与验证.md) | 从零部署的完整步骤 |
| `docs/M0x测试记录_*.md` | 各里程碑的测试记录与缺陷处置 |
| [AI 质量评测](docs/M10b质量评测_2026-11-24.md) | 出题与辅导质量评测（50 例 + 2 组对照） |

---

## 范围与边界

- 只服务「数据结构」一门课（`rs_subject = 1`），多课程不在本期范围。
- 前端不引 UI 库、不用 TypeScript，样式由 `src/styles/tokens.css` 的设计令牌层统一。
- AI 只走真实接口，不提供 mock 供应方；无密钥时按降级路径运行。
- 性能基准（1000 题 / 10000 作答）与多机冷部署经确认不在验收范围。
