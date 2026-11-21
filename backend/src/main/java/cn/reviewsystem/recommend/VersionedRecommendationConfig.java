package cn.reviewsystem.recommend;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public final class VersionedRecommendationConfig {
 public String ruleVersion(){return "v1";}
 public BigDecimal weaknessWeight(){return new BigDecimal("0.60");}
 public BigDecimal timeWeight(){return new BigDecimal("0.25");}
 public BigDecimal errorWeight(){return new BigDecimal("0.15");}
 public int timeWindowDays(){return 7;}
 public int evidenceWindow(){return 5;}
 public int cooldownHours(){return 24;}
 public int defaultCount(){return 10;}
 public int publicMaxCount(){return 10;}
 public int maxCount(){return 20;}
 public int reviewSlots(){return 8;}
 public int diagnosticSlots(){return 2;}
 public int reviewInitialKnowledge(){return 3;}
 public int newUserKnowledgeLimit(){return 5;}
 public int reviewMinimumN(){return 3;}
}
