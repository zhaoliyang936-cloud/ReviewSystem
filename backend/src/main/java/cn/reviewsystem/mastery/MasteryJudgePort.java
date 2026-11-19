package cn.reviewsystem.mastery;

import java.math.BigDecimal;

public interface MasteryJudgePort {
 record Judgment(BigDecimal score, String reasonJson, Long jobId) {}
 Judgment judge(long userId, long knowledgeId, int n, BigDecimal ruleScore);
}
