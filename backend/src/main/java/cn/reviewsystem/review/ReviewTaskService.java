package cn.reviewsystem.review;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.practice.ActiveActivityException;
import cn.reviewsystem.practice.MixSlotAllocator;
import cn.reviewsystem.recommend.RecommendationService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewTaskService {
 private final JdbcTemplate db;private final ObjectMapper json;private final RecommendationService recommendations;
 public ReviewTaskService(JdbcTemplate db,ObjectMapper json,RecommendationService recommendations){this.db=db;this.json=json;this.recommendations=recommendations;}
 public record CreateRequest(Integer questionCount){}
 public record ItemView(String id,int position,String questionId,String questionVersionId,String knowledgeId,String questionType,JsonNode content,JsonNode reason){}
 public record View(String id,String activityId,String status,int questionCount,JsonNode mixRatio,List<ItemView> items){}
 private record Row(long id,long activityId,String status,int count,String mix){}
 @Transactional public View create(long user,String key,CreateRequest request){
  if(key==null||key.isBlank()||key.length()>120)bad("Idempotency-Key is required and must not exceed 120 characters");
  int count=request==null||request.questionCount()==null?10:request.questionCount();if(count<1||count>20)bad("questionCount must be between 1 and 20");
  String hash=hash(count);db.query("SELECT pg_advisory_xact_lock(?)",r->{},user);
  var prior=db.query("SELECT id,request_hash FROM rs_review_task WHERE user_id=? AND request_key=?",(r,i)->Map.entry(r.getLong(1),r.getString(2).trim()),user,key);
  if(!prior.isEmpty()){if(!hash.equals(prior.getFirst().getValue()))throw new BusinessException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","The idempotency key was already used with a different body");return detail(user,prior.getFirst().getKey());}
  var recommendation=recommendations.recommend(user,count);var selected=recommendation.items();
  if(selected.isEmpty())throw new BusinessException(HttpStatus.BAD_REQUEST,"NO_AVAILABLE_QUESTION","No recommended questions available");
  var active=db.query("INSERT INTO rs_learning_activity(user_id,subject_id,kind,status) VALUES (?,1,'TASK','READY') ON CONFLICT (user_id,subject_id) WHERE status IN ('PREPARING','READY','IN_PROGRESS','GRADING') DO NOTHING RETURNING id",(r,i)->r.getLong(1),user);
  if(active.isEmpty()){var existing=db.queryForMap("SELECT id,kind FROM rs_learning_activity WHERE user_id=? AND subject_id=1 AND status IN ('PREPARING','READY','IN_PROGRESS','GRADING') ORDER BY id LIMIT 1",user);throw new ActiveActivityException(String.valueOf(existing.get("id")),(String)existing.get("kind"));}
  var slots=MixSlotAllocator.allocate(selected.size(),0.3);ObjectNode mix=json.createObjectNode();mix.put("version","v1");ObjectNode ratio=mix.putObject("ratio");ratio.put("bank",0.7);ratio.put("ai",0.3);ObjectNode planned=mix.putObject("planned");planned.put("bank",slots.bankSlots());planned.put("ai",slots.aiSlots());ObjectNode actual=mix.putObject("actual");actual.put("bank",selected.size());actual.put("ai",0);ArrayNode degraded=mix.putArray("degraded");for(int i=0;i<selected.size();i++)if(slots.aiPositions().get(i)){ObjectNode entry=degraded.addObject();entry.put("position",i);entry.put("reason","AI_UNAVAILABLE");}
  if(selected.size()<count){ObjectNode shortfall=mix.putObject("shortfall");shortfall.put("requested",count);shortfall.put("actual",selected.size());shortfall.put("reason","INSUFFICIENT_RECOMMENDATIONS");}
  Long revision=db.query("SELECT learning_revision FROM rs_student_course_guard WHERE user_id=? AND subject_id=1",(r,n)->r.getLong(1),user).stream().findFirst().orElse(0L);
  long task=Objects.requireNonNull(db.queryForObject("INSERT INTO rs_review_task(activity_id,user_id,subject_id,status,question_count,mix_ratio_json,rule_version,source_revision,request_key,request_hash) VALUES (?,?,1,'READY',?,CAST(? AS jsonb),?,?,?,?) RETURNING id",Long.class,active.getFirst(),user,selected.size(),write(mix),recommendation.ruleVersion(),revision==null?0:revision,key,hash));
  for(int i=0;i<selected.size();i++){var item=selected.get(i);db.update("INSERT INTO rs_task_item(task_id,question_id,question_version_id,knowledge_id,question_type,max_score,position,reason_json) SELECT ?,q.id,v.id,v.knowledge_point_id,v.type,v.max_score,?,CAST(? AS jsonb) FROM rs_question q JOIN rs_question_version v ON v.id=? WHERE q.id=?",task,i,write(json.valueToTree(item.reason())),Long.parseLong(item.questionVersionId()),Long.parseLong(item.questionId()));}
  return detail(user,task);
 }
 public View detail(long user,long id){var rows=db.query("SELECT t.id,t.activity_id,a.status,t.question_count,t.mix_ratio_json::text FROM rs_review_task t JOIN rs_learning_activity a ON a.id=t.activity_id WHERE t.user_id=? AND t.id=?",(r,i)->new Row(r.getLong(1),r.getLong(2),r.getString(3),r.getInt(4),r.getString(5)),user,id);if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Review task not found");var row=rows.getFirst();var items=db.query("SELECT i.id,i.position,i.question_id,i.question_version_id,i.knowledge_id,i.question_type,v.content_json::text,i.reason_json::text FROM rs_task_item i JOIN rs_question_version v ON v.id=i.question_version_id WHERE i.task_id=? ORDER BY i.position",(r,n)->{JsonNode content=read(r.getString(7));if(content.isObject()){ObjectNode copy=(ObjectNode)content.deepCopy();copy.remove("explanation");content=copy;}return new ItemView(String.valueOf(r.getLong(1)),r.getInt(2),String.valueOf(r.getLong(3)),String.valueOf(r.getLong(4)),String.valueOf(r.getLong(5)),r.getString(6),content,read(r.getString(8)));},id);return new View(String.valueOf(id),String.valueOf(row.activityId()),row.status(),row.count(),read(row.mix()),items);}
 @Transactional public View start(long user,long id){var rows=db.query("SELECT a.id,a.status FROM rs_review_task t JOIN rs_learning_activity a ON a.id=t.activity_id WHERE t.user_id=? AND t.id=? FOR UPDATE",(r,n)->Map.entry(r.getLong(1),r.getString(2)),user,id);if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Review task not found");var row=rows.getFirst();if(row.getValue().equals("READY")){db.update("UPDATE rs_learning_activity SET status='IN_PROGRESS',updated_at=CURRENT_TIMESTAMP WHERE id=?",row.getKey());db.update("UPDATE rs_review_task SET status='IN_PROGRESS' WHERE id=?",id);}else if(!row.getValue().equals("IN_PROGRESS"))throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Review task cannot be started from status "+row.getValue());return detail(user,id);}
 @Transactional public void cancel(long user,long id){var rows=db.query("SELECT a.id,a.status FROM rs_review_task t JOIN rs_learning_activity a ON a.id=t.activity_id WHERE t.user_id=? AND t.id=? FOR UPDATE",(r,n)->Map.entry(r.getLong(1),r.getString(2)),user,id);if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Review task not found");var row=rows.getFirst();if(row.getValue().equals("CANCELLED"))return;if(!Set.of("READY","PREPARING").contains(row.getValue()))throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Only preparing or ready tasks can be cancelled");db.update("UPDATE rs_learning_activity SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=?",row.getKey());db.update("UPDATE rs_review_task SET status='CANCELLED' WHERE id=?",id);}
 private static void bad(String message){throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED",message);}
 private String hash(int count){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(Map.of("questionCount",count))));}catch(Exception e){throw new IllegalStateException(e);}}
 private String write(JsonNode node){try{return json.writeValueAsString(node);}catch(Exception e){throw new IllegalStateException(e);}}
 private JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
