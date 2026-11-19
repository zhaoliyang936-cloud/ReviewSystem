package cn.reviewsystem.mastery;

import java.math.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class MasteryCalculation {
 public record Evidence(long id,long questionId,BigDecimal y,BigDecimal weight,OffsetDateTime submittedAt) {}
 public record Result(List<Evidence> selected,int n,BigDecimal weightSum,BigDecimal weightedSum,BigDecimal ruleScore,BigDecimal aiScore,BigDecimal finalScore,boolean clamped,String status,String aiReasonJson,Long aiJobId) {}
 private static final BigDecimal EIGHT=new BigDecimal("8");
 public static Result calculate(List<Evidence> evidence,MasteryJudgePort judge,long userId,long knowledgeId){
  Comparator<Evidence> newest=Comparator.comparing(Evidence::submittedAt).thenComparingLong(Evidence::id).reversed();
  Map<Long,Evidence> byQuestion=new HashMap<>();evidence.stream().sorted(newest).forEach(e->byQuestion.putIfAbsent(e.questionId(),e));
  List<Evidence> selected=byQuestion.values().stream().sorted(newest).limit(20).toList();int n=selected.size();
  BigDecimal ws=BigDecimal.ZERO,ys=BigDecimal.ZERO;for(Evidence e:selected){ws=ws.add(e.weight());ys=ys.add(e.weight().multiply(e.y()));}
  BigDecimal rule=n==0?null:ys.add(BigDecimal.ONE).multiply(new BigDecimal("100")).divide(ws.add(new BigDecimal("2")),12,RoundingMode.HALF_UP);
  BigDecimal ai=null,fin=rule;boolean clamped=false;String reason="{\"aiApplied\":false,\"reason\":\"unavailable\"}";Long job=null;
  if(rule!=null)try{MasteryJudgePort.Judgment result=judge.judge(userId,knowledgeId,n,rule);if(result!=null&&result.score()!=null&&result.score().compareTo(BigDecimal.ZERO)>=0&&result.score().compareTo(new BigDecimal("100"))<=0){ai=result.score();fin=ai.max(rule.subtract(EIGHT)).min(rule.add(EIGHT));clamped=ai.compareTo(fin)!=0;reason=result.reasonJson()==null?"{\"aiApplied\":true}":result.reasonJson();job=result.jobId();}}catch(Exception ignored){reason="{\"aiApplied\":false,\"reason\":\"judge_failed\"}";}
  String status=n==0?"UNASSESSED":n<3?"INSUFFICIENT":fin.compareTo(new BigDecimal("50"))<0?"WEAK":fin.compareTo(new BigDecimal("80"))<0?"CONSOLIDATE":"STABLE";
  return new Result(selected,n,ws,ys,rule,ai,fin,clamped,status,reason,job);
 }
 private MasteryCalculation(){}
}
