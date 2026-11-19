package cn.reviewsystem.question;

import cn.reviewsystem.api.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class QuestionValidator {
 public Validated validate(QuestionWriteRequest q){
  if(q==null||q.content()==null||!q.content().isObject()||q.answer()==null||!q.answer().isObject())fail("content and answer must be JSON objects");
  QuestionType type;try{type=QuestionType.valueOf(q.type());}catch(Exception e){fail("Unsupported question type");return null;}
  String stem=text(q.content(),"stem");if(stem.isBlank()||stem.length()>10000)fail("stem is required and must not exceed 10000 characters");
  double max=q.maxScore()==null?0:q.maxScore();if(!Double.isFinite(max)||max<=0||max>1000)fail("maxScore must be between 0 and 1000");
  String explanation=q.explanation()==null?"":q.explanation().trim();if(explanation.length()>10000)fail("explanation is too long");
  switch(type){
   case CHOICE -> choice(q);
   case FILL -> fill(q);
   case SHORT_ANSWER,ALGORITHM -> subjective(q,max);
  }
  return new Validated(type,max,explanation,q.sourceRef()==null?null:q.sourceRef().trim());
 }
 private void choice(QuestionWriteRequest q){
  JsonNode options=q.content().get("options");if(options==null||!options.isArray()||options.size()<3)fail("CHOICE requires at least 3 options");
  var keys=new HashSet<String>();for(JsonNode option:options){String key=text(option,"key"),value=text(option,"text");if(key.isBlank()||value.isBlank()||!keys.add(key))fail("Each choice option needs a unique key and non-empty text");}
  String correct=text(q.answer(),"correctOption");if(!keys.contains(correct))fail("CHOICE requires exactly one correct option key");
  if(q.rubric()!=null&&!q.rubric().isNull())fail("CHOICE must not contain a rubric");
 }
 private void fill(QuestionWriteRequest q){
  int placeholders=countPlaceholders(text(q.content(),"stem"));JsonNode blanks=q.answer().get("blanks");
  if(placeholders<1||blanks==null||!blanks.isArray()||blanks.size()!=placeholders)fail("FILL placeholder count must equal answer blank count");
  for(JsonNode blank:blanks){JsonNode accepted=blank.get("accepted");if(accepted==null||!accepted.isArray()||accepted.isEmpty())fail("Each blank needs an accepted answer set");var values=new HashSet<String>();for(JsonNode value:accepted)if(!value.isTextual()||value.asText().trim().isEmpty()||!values.add(value.asText().trim()))fail("Equivalent answers must be non-empty and unique");}
  if(q.rubric()!=null&&!q.rubric().isNull())fail("FILL must not contain a rubric");
 }
 private void subjective(QuestionWriteRequest q,double max){
  if(text(q.answer(),"referenceAnswer").isBlank())fail("Subjective questions require referenceAnswer");
  if(q.rubric()==null||!q.rubric().isObject()||q.rubric().get("items")==null||!q.rubric().get("items").isArray()||q.rubric().get("items").isEmpty())fail("Subjective questions require rubric items");
  BigDecimal sum=BigDecimal.ZERO;for(JsonNode item:q.rubric().get("items")){if(text(item,"criterion").isBlank()||item.get("score")==null||!item.get("score").isNumber()||item.get("score").decimalValue().signum()<=0)fail("Each rubric item needs criterion and positive score");sum=sum.add(item.get("score").decimalValue());}
  if(sum.compareTo(BigDecimal.valueOf(max))!=0)fail("Rubric scores must add up to maxScore");
 }
 public static int countPlaceholders(String stem){int count=0,from=0;while((from=stem.indexOf("{{blank}}",from))>=0){count++;from+=9;}return count;}
 private static String text(JsonNode node,String field){JsonNode value=node==null?null:node.get(field);return value!=null&&value.isTextual()?value.asText().trim():"";}
 private static void fail(String message){throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED",message);}
 public record Validated(QuestionType type,double maxScore,String explanation,String sourceRef){}
}
