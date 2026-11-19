package cn.reviewsystem.mastery;

import org.springframework.stereotype.Component;

@Component
public class UnavailableMasteryJudge implements MasteryJudgePort {
 public Judgment judge(long userId,long knowledgeId,int n,java.math.BigDecimal ruleScore){return null;}
}
