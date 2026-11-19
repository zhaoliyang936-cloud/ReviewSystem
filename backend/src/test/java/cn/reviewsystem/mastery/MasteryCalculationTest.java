package cn.reviewsystem.mastery;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MasteryCalculationTest {
 private final OffsetDateTime now=OffsetDateTime.now();
 private MasteryCalculation.Evidence e(long id,long q,String y,String w,int seconds){return new MasteryCalculation.Evidence(id,q,new BigDecimal(y),new BigDecimal(w),now.plusSeconds(seconds));}
 private MasteryCalculation.Result calc(List<MasteryCalculation.Evidence> evidence){return MasteryCalculation.calculate(evidence,(u,k,n,s)->null,1,1);}
 @Test void noEvidenceIsUnassessed(){var r=calc(List.of());assertEquals(0,r.n());assertNull(r.ruleScore());assertEquals("UNASSESSED",r.status());}
 @Test void twoCorrectOfEightGivesThirty(){var list=new ArrayList<MasteryCalculation.Evidence>();for(int i=0;i<8;i++)list.add(e(i,i,i<2?"1":"0","1",i));var r=calc(list);assertEquals("30.000",r.ruleScore().setScale(3).toPlainString());assertEquals(r.ruleScore(),r.finalScore());assertEquals("WEAK",r.status());}
 @Test void oneCorrectIsInsufficient(){var r=calc(List.of(e(1,1,"1","1",0)));assertEquals("66.667",r.ruleScore().setScale(3,java.math.RoundingMode.HALF_UP).toPlainString());assertEquals("INSUFFICIENT",r.status());}
 @Test void sameQuestionUsesNewestWithoutIncreasingN(){var r=calc(List.of(e(1,1,"0","1",0),e(2,1,"1","1",1)));assertEquals(1,r.n());assertEquals(2,r.selected().get(0).id());}
 @Test void twentyOneQuestionsUseLatestTwenty(){var list=new ArrayList<MasteryCalculation.Evidence>();for(int i=0;i<21;i++)list.add(e(i,i,i==0?"1":"0","1",i));var r=calc(list);assertEquals(20,r.n());assertEquals(0,r.weightedSum().intValue());}
 private List<MasteryCalculation.Evidence> weighted(){return List.of(e(1,1,"1","1",1),e(2,2,"1","1",2),e(3,3,"0","1",3),e(4,4,"0","1",4),e(5,5,"0.8","0.6",5),e(6,6,"0.4","0.6",6));}
 @Test void weightedSubjectiveEvidenceGivesExpectedSums(){var r=calc(weighted());assertEquals("5.2",r.weightSum().toPlainString());assertEquals("2.72",r.weightedSum().toPlainString());assertEquals("51.667",r.ruleScore().setScale(3,java.math.RoundingMode.HALF_UP).toPlainString());}
 @Test void aiScoreIsClampedToEightPoints(){var r=MasteryCalculation.calculate(weighted(),(u,k,n,s)->new MasteryJudgePort.Judgment(new BigDecimal("20"),"{\"aiApplied\":true}",null),1,1);assertEquals("43.667",r.finalScore().setScale(3,java.math.RoundingMode.HALF_UP).toPlainString());assertTrue(r.clamped());}
 @Test void judgeFailureFallsBackToRule(){var r=MasteryCalculation.calculate(weighted(),(u,k,n,s)->{throw new IllegalStateException("offline");},1,1);assertEquals(r.ruleScore(),r.finalScore());assertFalse(r.clamped());assertEquals("CONSOLIDATE",r.status());}
}
