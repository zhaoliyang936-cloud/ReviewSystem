# ReviewSystem — 数据结构课程复习系统

单课程（数据结构）的练习与复习系统。后端 Spring Boot 3 + JdbcTemplate + Flyway + PostgreSQL 16，前端 Vue 3 + Vite。仓库根目录即项目根，后端 `backend/`，前端 `frontend/`。

**先读 `docs/需求分析文档.md` 与 `docs/技术设计文档.md`（v0.5 为当前基线）**，它们定义不可协商的业务规则。本文件只补两件事：代码里看不到的环境事实，以及已经踩过的坑。

## 当前进度

| 里程碑 | 状态 |
|---|---|
| M01 工程基础 | 已完成（第二台机器待验收） |
| M02 身份与权限 | 已完成 |
| M03 单课程/知识点/四类题库 | 已完成 |
| M04 普通练习与判分 | 已完成 |
| M05 证据与掌握度 | 已完成（AI 综合判断待 M10） |
| M06 推荐与解释 | 已完成，远端分支 `m06` = `01cd056` |
| M07 复习任务 | **第一阶段代码在工作区，未提交** |
| M08 资料检索 / M09 错题本 / M10 AI 辅导 | 未开始 |

M07 第一阶段已实现：`MixSlotAllocator` 槽位分配器、M04 `mix_ratio_json` 新结构、V8 两张表、任务生成/详情/取消接口、`MasteryEventConsumer` 按 `event_type` 分派。全量 11 个测试类 83 项通过。**未做**：草稿、提交、结果页、推荐页、任务页。

## 环境事实

### 数据库

- 数据目录 `D:\软件工程职业实践\.m04-audit-syntax-pg`，超级用户 `postgres`，trust 免密，库名 `reviewsystem`。
- **端口 5432**。`postgresql.conf` 里没有 `port` 项，不带参数启动即默认 5432。历史文档里的 55434 是过去用 `-o "-p 55434"` 启动的参数，同一数据目录、同一份数据。
- **只能用 `127.0.0.1`，不要用 `localhost`**——后者解析到 `::1`，连不上，会误判成"数据库没起"。
- 启动：`pg_ctl.exe -D "D:\软件工程职业实践\.m04-audit-syntax-pg" -l "<某处>\pg.log" -w -t 180 start`。若报 `no server running` 但目录里残留 `postmaster.pid`，先确认没有 postgres 进程，再删该文件。
- 执行环境常在**一条命令结束时终止整个进程树**。若发现启动成功、下一条命令就连不上，把「启库 + 查询/测试」写进同一条命令。

### 构建

- JDK：`D:\DevTools\zulu21.54.109-ca-jdk21.0.13.0.3-win_x64`
- Maven：`D:\DevTools\apache-maven-3.9.16`
- 若 `mvn` 报 `ClassNotFoundException: ...Launcher`，改用直调：
  ```
  java -classpath "D:/DevTools/apache-maven-3.9.16/boot/plexus-classworlds-2.11.0.jar" \
       -Dclassworlds.conf="D:/DevTools/apache-maven-3.9.16/bin/m2.conf" \
       -Dmaven.home="D:/DevTools/apache-maven-3.9.16" \
       -Dmaven.multiModuleProjectDirectory="<项目>/backend" \
       org.codehaus.plexus.classworlds.launcher.Launcher <goal>
  ```
- 若 Git Bash 里 `ls`/`rm` 报 command not found，先修复 PATH：`export PATH="/c/Program Files/Git/usr/bin:$PATH"`。

### 测试

- 测试配置读环境变量 `DB_URL` / `DB_USER`（见 `backend/src/test/resources/application.properties`）。
- **集成测试一律跑独立库**：`createdb rs_verify_xxx`，跑完 `dropdb`。**绝不写 `reviewsystem`**。
- 真实库当前状态：Flyway **v7**、31 题、25 知识点、1 用户、11 条证据、3 条掌握度快照。V8 尚未应用到真实库（已在完整副本上验证 v7→v8 升级成功、数据零变化）。升级前备份：`.workbuddy/backup/reviewsystem-pre-v8-2026-11-21.sql`。

## 不可违反的业务规则

1. 单课程：`rs_subject` 只有 `id=1`（带 CHECK），前端不提供课程选择。
2. 题型固定四类：`CHOICE` / `FILL` / `SHORT_ANSWER` / `ALGORITHM`。**是 `SHORT_ANSWER` 不是 `SHORT`**——V3、V6、V8 四处 CHECK 约束与 `QuestionType` 枚举都这么写。不做判断题、多选题。
3. 题目内容存 `rs_question_version`，**版本一经创建不可修改**；编辑 = 生成新版本。
4. 迁移文件 V1—V8 都已存在并被 Flyway 记录过，**不得修改任何已存在的迁移文件**；新改动只能新增 V9+。注意区分：真实库 `reviewsystem` 目前仍停在 **v7**，V8 只在独立库与真实库完整副本上验证过，尚未应用到真实库。
5. 掌握度：`rule_score = 100*(Σwy+1)/(Σw+2)`，`final = clamp(ai, rule-8, rule+8)`；客观题 `y∈{0,1}`、`w=1.0`，主观题 `y=建议分/100`、`w=0.6`。
6. 推荐优先级：`P = 0.60W + 0.25T + 0.15E`，参数集中在 `VersionedRecommendationConfig` v1，**不得改这些数值**。
7. 固定混合组卷 7:3（题库:AI），学生不选来源，界面不显示比例。
8. 答案只在提交后返回；`/result` 在未完成时返回 409。
9. 同一学生同一时刻只能有一个活动（部分唯一索引 `uq_rs_activity_active`，`kind=PRACTICE/TASK`）。

## 已踩过的坑（会重犯，动手前先看）

1. **集成测试不能依赖库里已有数据。** 早期 `ReviewTaskIntegrationTest` 只建用户就调 `create()`，在干净库上必抛 `No recommended questions available`。所有集成测试必须在 `@BeforeEach` 自建章节/知识点/题目，配 `@Transactional` 回滚。
2. **`rs_evidence` 唯一键是 `(source_type, submission_id, question_id)`。** taskId 与 practiceId 撞号属正常，不要断言"另一类型计数为 0"，应断言"操作前后该类型计数不变"。
3. **`MasteryEventConsumer` 必须按 `event_type` 分派。** 旧实现硬编码查 `rs_practice`，收到 `TASK_SUBMITTED` 会拿 taskId 查同号 practice 并覆盖其证据。未知 `event_type` 必须抛异常让事件进 `FAILED`，不能默认当 PRACTICE。
4. **不要复用 `PracticeService.answered()` 给复习任务组卷。** 它排除练过的题，而复习任务的核心正是练错题。M07 直接用 M06 的 `recommend()` 结果。
5. **AI 端口恒返回 0。** `AiQuestionPort` 只有 `availableCount()`，`NoAiQuestionProvider` 返回 0。AI 槽位必须全部降级为题库题，并在 `degraded` 中逐槽位记录原因，不能只写死 `actual.ai=0` 而不说明。
6. **`Item.position` 是池内序号，不是全局序号。** 落 `rs_task_item.position` 时必须按 items 列表下标 0..n-1 连续重排。
7. **乐观锁**：`rs_chapter` 等表有 `version` 列，PUT 必须带 `expectedVersion`。
8. **Flyway 只能由应用启动执行。** 用 psql 手工跑迁移不写 `flyway_schema_history`，之后 Flyway 会拒绝启动。
9. **`@WebMvcTest` 是切片测试，不加载数据源，Flyway 不会执行。** 要验证迁移必须用 `@SpringBootTest`。

## 已知待修

1. ~~`PracticeService` 的 `planned` 按请求题量算~~ **已修**：改为 `MixSlotAllocator.allocate(selected.size(), mix.ai())`，`M04IntegrationTest.scarceSingleKnowledgeUsesEveryQuestionOnceAndReportsRequestedCount` 断言 `planned.bank + planned.ai == 实际题数` 且 `degraded.size() == planned.ai`。
2. ~~`ReviewTaskService` 硬编码 0.7/0.3~~ **已修**：构造注入 `PracticeMixConfig`，`version` / `ratio.bank` / `ratio.ai` 全取配置。
3. ~~题量上限三处不一致~~ **已修**：`VersionedRecommendationConfig` 新增 `publicMaxCount()=10`（对外 HTTP，对齐文档 205 行）与 `maxCount()=20`（内部规划容量）；controller 用前者，service 与 `ReviewTaskService.create` 用后者。
4. ~~`updating` 未含 `TASK_SUBMITTED`~~ **已修**：两处 SQL 改为 `EXISTS(...)` + 两种事件各自按 `rs_practice` / `rs_review_task` 校 user，文档 205 行同步。
5. ~~`ASSESSMENT_UPDATING` 拦截缺失~~ **已补**：`ReviewTaskService.create` 在 `pg_advisory_xact_lock` 之后、读取推荐之前检查该用户是否仍有未 DONE 的 `PRACTICE_SUBMITTED` / `TASK_SUBMITTED` 事件，命中抛 409 `ASSESSMENT_UPDATING`。顺序有意为之：幂等重放先返回原任务，不受拦截影响。仍别和第 4 项的 `updating` 字段混为一谈——那个是响应里的展示字段，这个是拒绝生成。
6. ~~任务里的主观题无法上传作答图片~~ **已在 V9 解决**：`rs_answer_asset` 新增可空 `task_item_id`、`practice_item_id` 放开 NOT NULL、加 CHECK `ck_rs_answer_asset_owner`（二者恰有其一）、`uq_rs_answer_asset_task_position`。「练习还是任务」由 `AnswerAssetService` 的 `Owner`（`PRACTICE` / `TASK`）决定，对外方法是 `upload` / `uploadForTask`、`delete` / `deleteForTask`。任务附件地址前缀 `/api/answer-assets/{id}`，练习仍是 `/api/practice-assets/{id}`。上传同样要求 activity 处于 `IN_PROGRESS`，所以任务必须先 `POST /api/review/tasks/{id}/start`。
7. `Item.position` 是池内序号（已在 M07 落 `rs_task_item` 时按列表下标重排，M06 响应里仍是池内序号，读响应时注意）。
8. ~~`grading_status` 缺 `AI_REVIEW_FAILED`~~ **已由 V10 解决**：`V10__grading_status_ai_review_failed.sql` 用 DO 块按 `pg_get_constraintdef` 搜出两张表上所有涉及 `grading_status` 的 CHECK 并重建为 `ck_rs_practice_item_grading_status` / `ck_rs_task_item_grading_status`，取值域加入 `AI_REVIEW_FAILED`。真实库副本验证 v7→v10 成功，数据零变化。M10 写这个值时不会再违反约束。
9. ~~任务只有 start / cancel，没有草稿、提交、结果~~ **已解决**：`PUT /api/review/tasks/{id}/items/{itemId}/draft`（`draft_version` 乐观锁 + 仅 `IN_PROGRESS`）、`POST /api/review/tasks/{id}/submit`（幂等键 + `submit_hash`、客观题复用 `GradingService`、主观题 `AI_REVIEW_PENDING`、附件转 `SUBMITTED`、双写 `COMPLETED`、投递 `TASK_SUBMITTED` 且 `source_revision = task.source_revision + 1`）、`GET /api/review/tasks/{id}/result`（仅 `COMPLETED`）均已实现并有集成测试。
10. **`POST /api/review/tasks` 的状态码与文档不符**：技术设计第 7 节写「固定混合组卷返回 202 准备，全部降级时 201」，实现是 `@ResponseStatus(CREATED)` 一律 201，且任务直接落 `READY`。AI 出题接入后（M10）必须区分 202 + `PREPARING`，届时改 controller 返回值而不是只改注解。
11. **`rs_review_task.status` 没有 CHECK 约束**：V8 建表时只给了 `VARCHAR(24) DEFAULT 'READY'`，现在实际取值已含 `READY` / `IN_PROGRESS` / `CANCELLED` / `COMPLETED`。它和 `rs_learning_activity.status` 是两份，`start` / `cancel` / `submit` 三处都双写；新增状态时必须三处一起改，漏一处会出现「任务说完成、活动说进行中」。

## 工作方式

- 动手前先读相关文件，不要凭猜测改代码。
- 报告必须附**实际执行的命令与实际输出**。不要用「已完成」「无问题」代替证据。测试结果要给 Surefire XML 的实际计数（测试类数、用例数、失败数、错误数），不能只说「全部通过」，也不能拿上一次的报告冒充本次。
- 做不到的事直接说做不到。不要伪造输出，不要改用 `localhost` 绕过环境问题。
- **测试不能伪造证据**：想验证「配置生效」就必须真的让 Spring 绑定（`@SpringBootTest(properties=...)` 或 `ApplicationContextRunner`），不能用 `MockEnvironment.resolvePlaceholders()` 手工算出值再 `new` 一个配置对象——那只能证明实现用了注入的配置对象，证明不了环境变量能改到它。断言集合时先断非空，`allMatch` 对空集合恒为 true。
- 遇到以下操作先停下说明并等确认：修改 V1—V10 迁移、写入真实库 `reviewsystem`、重写 git 历史、force push。
- 提交规范：分支名 `M<NN>`；仓库身份已配置为 `zero-0623`；**不要提交** `.codex/`、`学习资料/`、`import-report-19A.json`、`*.log`。
- 文档随代码同步：改接口要更新 `docs/技术设计文档.md` 第 7 节接口表与对应里程碑状态行。

## 目录索引

| 路径 | 内容 |
|---|---|
| `docs/需求分析文档.md` | 需求、验收标准 AC-xx、业务规则 |
| `docs/技术设计文档.md` | 架构、表结构、接口表、里程碑定义 |
| `docs/M0x测试记录_*.md` | 各里程碑实际验证记录 |
| `backend/src/main/java/cn/reviewsystem/` | `api`(身份) `course` `question` `practice` `mastery` `recommend` `review` |
| `backend/src/main/resources/db/migration/` | V1—V8 |
| `backend/src/test/java/cn/reviewsystem/` | 各里程碑集成测试 |
| `frontend/src/` | Vue 3 页面，按里程碑分目录 |
