package cn.reviewsystem.practice;

import org.springframework.stereotype.Component;

@Component
public class NoAiQuestionProvider implements AiQuestionPort {
 public int availableCount(long knowledgePointId,int requested){return 0;}
}
