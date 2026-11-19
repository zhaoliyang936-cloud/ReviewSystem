package cn.reviewsystem.practice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class GradingService {
 private final ObjectMapper json;
 public GradingService(ObjectMapper json){this.json=json;}

 public Grade grade(String type,double maxScore,JsonNode submitted,JsonNode correct){
  return switch(type){
   case "CHOICE" -> choice(maxScore,submitted,correct);
   case "FILL" -> fill(maxScore,submitted,correct);
   case "SHORT_ANSWER","ALGORITHM" -> subjective(maxScore);
   default -> throw new IllegalArgumentException("Unsupported question type");
  };
 }

 private Grade choice(double max,JsonNode submitted,JsonNode correct){
  String selected=text(submitted,"selectedOption"),expected=text(correct,"correctOption");boolean passed=!selected.isBlank()&&selected.equals(expected);ObjectNode result=base("AUTO",max,1.0,"AUTO_GRADED");result.put("raw_score",passed?max:0);result.put("normalized_score",passed?100:0);result.put("passed",passed);return new Grade(result,"AUTO_GRADED");
 }

 private Grade fill(double max,JsonNode submitted,JsonNode correct){
  JsonNode expected=correct==null?null:correct.get("blanks"),given=submitted==null?null:submitted.get("blanks");int count=expected!=null&&expected.isArray()?expected.size():0;ArrayNode details=json.createArrayNode();boolean all=count>0&&given!=null&&given.isArray()&&given.size()==count;
  for(int i=0;i<count;i++){String value=given!=null&&given.isArray()&&i<given.size()&&given.get(i).isTextual()?given.get(i).asText():"";boolean match=false;JsonNode accepted=expected.get(i).get("accepted");if(!normalize(value).isBlank()&&accepted!=null&&accepted.isArray())for(JsonNode option:accepted)if(option.isTextual()&&normalize(value).equals(normalize(option.asText()))){match=true;break;}ObjectNode detail=json.createObjectNode();detail.put("index",i);detail.put("correct",match);details.add(detail);all&=match;}
  ObjectNode result=base("AUTO",max,1.0,"AUTO_GRADED");result.put("raw_score",all?max:0);result.put("normalized_score",all?100:0);result.put("passed",all);result.set("blank_results",details);return new Grade(result,"AUTO_GRADED");
 }

 private Grade subjective(double max){ObjectNode result=base("LLM_SUGGESTED",max,0.6,"AI_REVIEW_PENDING");result.putNull("raw_score");result.putNull("normalized_score");result.putNull("passed");result.put("provisional",true);return new Grade(result,"AI_REVIEW_PENDING");}
 private ObjectNode base(String mode,double max,double weight,String status){ObjectNode result=json.createObjectNode();result.put("grading_mode",mode);result.put("max_score",max);result.put("evidence_weight",weight);result.put("grading_status",status);return result;}
 private static String text(JsonNode node,String field){JsonNode value=node==null?null:node.get(field);return value!=null&&value.isTextual()?value.asText():"";}
 static String normalize(String source){String value=Normalizer.normalize(source==null?"":source.trim(),Normalizer.Form.NFC);StringBuilder out=new StringBuilder(value.length());for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='\u3000')c=' ';else if(c>='\uFF01'&&c<='\uFF5E')c=(char)(c-0xFEE0);if(c>='A'&&c<='Z')c=(char)(c+32);out.append(c);}return Normalizer.normalize(out.toString().trim(),Normalizer.Form.NFC);}
 public record Grade(ObjectNode result,String status){}
}
