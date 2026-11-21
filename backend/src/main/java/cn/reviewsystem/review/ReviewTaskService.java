package cn.reviewsystem.review;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.practice.ActiveActivityException;
import cn.reviewsystem.practice.GradingService;
import cn.reviewsystem.practice.MixSlotAllocator;
import cn.reviewsystem.practice.PracticeDtos;
import cn.reviewsystem.practice.PracticeMixConfig;
import cn.reviewsystem.recommend.RecommendationService;
import cn.reviewsystem.recommend.VersionedRecommendationConfig;
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
 private final JdbcTemplate db;private final ObjectMapper json;private final RecommendationService recommendations;private final PracticeMixConfig mixConfig;private final VersionedRecommendationConfig cfg;private final GradingService grading;
 public ReviewTaskService(JdbcTemplate db,ObjectMapper json,RecommendationService recommendations,PracticeMixConfig mixConfig,VersionedRecommendationConfig cfg,GradingService grading){this.db=db;this.json=json;this.recommendations=recommendations;this.mixConfig=mixConfig;this.cfg=cfg;this.grading=grading;}
 public record CreateRequest(Integer questionCount){}
 public record ItemView(String id,int position,String questionId,String questionVersionId,String knowledgeId,String questionType,JsonNode content,JsonNode reason){}
 public record View(String id,String activityId,String status,int questionCount,JsonNode mixRatio,List<ItemView> items){}
 public record DraftRequest(JsonNode answer,Integer draftVersion,String sourceMode,String userId,String subjectId){}
 public record DraftView(JsonNode answer,int draftVersion){}
 public record SubmitRequest(List<FinalAnswer> answers,String sourceMode,String userId,String subjectId){}
 public record FinalAnswer(String itemId,JsonNode answer,List<String> assetIds){}
 public record ResultView(String id,String activityId,String status,OffsetDateTime submittedAt,List<ResultItem> items){}
 public record ResultItem(String itemId,int position,String type,double maxScore,JsonNode content,JsonNode submittedAnswer,JsonNode correctAnswer,JsonNode rubric,String explanation,JsonNode result,List<PracticeDtos.AssetView> assets){}
 private record Row(long id,long activityId,String status,int count,String mix){}
 private record TaskStateRow(long id,long activityId,String status,String submitKey,String submitHash,OffsetDateTime submittedAt,long sourceRevision){}
 private record GradeItem(long id,String type,double maxScore,String correctAnswer){}
 @Transactional public View create(long user,String key,CreateRequest request){
  if(key==null||key.isBlank()||key.length()>120)bad("Idempotency-Key is required and must not exceed 120 characters");
  int count=request==null||request.questionCount()==null?cfg.defaultCount():request.questionCount();if(count<1||count>cfg.maxCount())bad("questionCount must be between 1 and "+cfg.maxCount());
  String hash=hash(count);db.query("SELECT pg_advisory_xact_lock(?)",r->{},user);
  var prior=db.query("SELECT id,request_hash FROM rs_review_task WHERE user_id=? AND request_key=?",(r,i)->Map.entry(r.getLong(1),r.getString(2).trim()),user,key);
  if(!prior.isEmpty()){if(!hash.equals(prior.getFirst().getValue()))throw new BusinessException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","The idempotency key was already used with a different body");return detail(user,prior.getFirst().getKey());}
  var recommendation=recommendations.recommend(user,count);var selected=recommendation.items();
  if(selected.isEmpty())throw new BusinessException(HttpStatus.BAD_REQUEST,"NO_AVAILABLE_QUESTION","No recommended questions available");
  var active=db.query("INSERT INTO rs_learning_activity(user_id,subject_id,kind,status) VALUES (?,1,'TASK','READY') ON CONFLICT (user_id,subject_id) WHERE status IN ('PREPARING','READY','IN_PROGRESS','GRADING') DO NOTHING RETURNING id",(r,i)->r.getLong(1),user);
  if(active.isEmpty()){var existing=db.queryForMap("SELECT id,kind FROM rs_learning_activity WHERE user_id=? AND subject_id=1 AND status IN ('PREPARING','READY','IN_PROGRESS','GRADING') ORDER BY id LIMIT 1",user);throw new ActiveActivityException(String.valueOf(existing.get("id")),(String)existing.get("kind"));}
  var slots=MixSlotAllocator.allocate(selected.size(),mixConfig.ai());ObjectNode mix=json.createObjectNode();mix.put("version",mixConfig.version());ObjectNode ratio=mix.putObject("ratio");ratio.put("bank",mixConfig.bank());ratio.put("ai",mixConfig.ai());ObjectNode planned=mix.putObject("planned");planned.put("bank",slots.bankSlots());planned.put("ai",slots.aiSlots());ObjectNode actual=mix.putObject("actual");actual.put("bank",selected.size());actual.put("ai",0);ArrayNode degraded=mix.putArray("degraded");for(int i=0;i<selected.size();i++)if(slots.aiPositions().get(i)){ObjectNode entry=degraded.addObject();entry.put("position",i);entry.put("reason","AI_UNAVAILABLE");}
  if(selected.size()<count){ObjectNode shortfall=mix.putObject("shortfall");shortfall.put("requested",count);shortfall.put("actual",selected.size());shortfall.put("reason","INSUFFICIENT_RECOMMENDATIONS");}
  Long revision=db.query("SELECT learning_revision FROM rs_student_course_guard WHERE user_id=? AND subject_id=1",(r,n)->r.getLong(1),user).stream().findFirst().orElse(0L);
  long task=Objects.requireNonNull(db.queryForObject("INSERT INTO rs_review_task(activity_id,user_id,subject_id,status,question_count,mix_ratio_json,rule_version,source_revision,request_key,request_hash) VALUES (?,?,1,'READY',?,CAST(? AS jsonb),?,?,?,?) RETURNING id",Long.class,active.getFirst(),user,selected.size(),write(mix),recommendation.ruleVersion(),revision==null?0:revision,key,hash));
  for(int i=0;i<selected.size();i++){var item=selected.get(i);db.update("INSERT INTO rs_task_item(task_id,question_id,question_version_id,knowledge_id,question_type,max_score,position,reason_json) SELECT ?,q.id,v.id,v.knowledge_point_id,v.type,v.max_score,?,CAST(? AS jsonb) FROM rs_question q JOIN rs_question_version v ON v.id=? WHERE q.id=?",task,i,write(json.valueToTree(item.reason())),Long.parseLong(item.questionVersionId()),Long.parseLong(item.questionId()));}
  return detail(user,task);
 }
 public View detail(long user,long id){var rows=db.query("SELECT t.id,t.activity_id,a.status,t.question_count,t.mix_ratio_json::text FROM rs_review_task t JOIN rs_learning_activity a ON a.id=t.activity_id WHERE t.user_id=? AND t.id=?",(r,i)->new Row(r.getLong(1),r.getLong(2),r.getString(3),r.getInt(4),r.getString(5)),user,id);if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Review task not found");var row=rows.getFirst();var items=db.query("SELECT i.id,i.position,i.question_id,i.question_version_id,i.knowledge_id,i.question_type,v.content_json::text,i.reason_json::text FROM rs_task_item i JOIN rs_question_version v ON v.id=i.question_version_id WHERE i.task_id=? ORDER BY i.position",(r,n)->{JsonNode content=read(r.getString(7));if(content.isObject()){ObjectNode copy=(ObjectNode)content.deepCopy();copy.remove("explanation");content=copy;}return new ItemView(String.valueOf(r.getLong(1)),r.getInt(2),String.valueOf(r.getLong(3)),String.valueOf(r.getLong(4)),String.valueOf(r.getLong(5)),r.getString(6),content,read(r.getString(8)));},id);return new View(String.valueOf(id),String.valueOf(row.activityId()),row.status(),row.count(),read(row.mix()),items);}
 @Transactional public View start(long user,long id){var rows=db.query("SELECT a.id,a.status FROM rs_review_task t JOIN rs_learning_activity a ON a.id=t.activity_id WHERE t.user_id=? AND t.id=? FOR UPDATE",(r,n)->Map.entry(r.getLong(1),r.getString(2)),user,id);if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Review task not found");var row=rows.getFirst();if(row.getValue().equals("READY")){db.update("UPDATE rs_learning_activity SET status='IN_PROGRESS',updated_at=CURRENT_TIMESTAMP WHERE id=?",row.getKey());db.update("UPDATE rs_review_task SET status='IN_PROGRESS' WHERE id=?",id);}else if(!row.getValue().equals("IN_PROGRESS"))throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Review task cannot be started from status "+row.getValue());return detail(user,id);}
 @Transactional public void cancel(long user,long id){var rows=db.query("SELECT a.id,a.status FROM rs_review_task t JOIN rs_learning_activity a ON a.id=t.activity_id WHERE t.user_id=? AND t.id=? FOR UPDATE",(r,n)->Map.entry(r.getLong(1),r.getString(2)),user,id);if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Review task not found");var row=rows.getFirst();if(row.getValue().equals("CANCELLED"))return;if(!Set.of("READY","PREPARING").contains(row.getValue()))throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Only preparing or ready tasks can be cancelled");db.update("UPDATE rs_learning_activity SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=?",row.getKey());db.update("UPDATE rs_review_task SET status='CANCELLED' WHERE id=?",id);}
 @Transactional public DraftView draft(long user,long id,long itemId,DraftRequest request){
  rejectClientScope(request==null?null:request.userId(),request==null?null:request.subjectId(),request==null?null:request.sourceMode());
  if(request==null||request.draftVersion()==null)bad("draftVersion is required");
  TaskStateRow row=taskState(user,id,true);
  if(!row.status().equals("IN_PROGRESS"))conflict("Drafts can only be changed while the review task is in progress");
  int updated=db.update("UPDATE rs_task_item SET draft_json=CAST(? AS jsonb),draft_version=draft_version+1 WHERE id=? AND task_id=? AND draft_version=?",writeNullable(request.answer()),itemId,id,request.draftVersion());
  if(updated==0){if(count("SELECT count(*) FROM rs_task_item WHERE id=? AND task_id=?",itemId,id)==0)notFound();conflict("Draft was changed by another request");}
  return new DraftView(request.answer(),request.draftVersion()+1);
 }
 @Transactional public ResultView submit(long user,long id,String key,SubmitRequest request){
  if(key==null||key.isBlank()||key.length()>120)bad("Idempotency-Key is required and must not exceed 120 characters");
  rejectClientScope(request==null?null:request.userId(),request==null?null:request.subjectId(),request==null?null:request.sourceMode());
  if(request==null)bad("Request body is required");
  String hash=hashObject(request);
  TaskStateRow row=taskState(user,id,true);
  if(row.submittedAt()!=null){
   if(key.equals(row.submitKey())&&hash.equals(row.submitHash()!=null?row.submitHash().trim():null))return result(user,id);
   if(key.equals(row.submitKey()))throw new BusinessException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","The idempotency key was already used with a different body");
   conflict("Review task has already been submitted");
  }
  if(!row.status().equals("IN_PROGRESS"))conflict("Review task must be in progress before submission");
  Map<Long,FinalAnswer> answers=new HashMap<>();
  if(request.answers()!=null)for(var answer:request.answers()){
   if(answer==null)bad("Invalid answer item");
   long itemId=parseId(answer.itemId());
   if(answers.put(itemId,answer)!=null)bad("Duplicate task item answer");
   if(count("SELECT count(*) FROM rs_task_item WHERE id=? AND task_id=?",itemId,id)==0)bad("Answer item does not belong to the review task");
  }
  var items=db.query("SELECT ti.id,ti.question_type,ti.max_score,v.answer_json::text FROM rs_task_item ti JOIN rs_question_version v ON v.id=ti.question_version_id WHERE ti.task_id=? ORDER BY ti.position",(r,n)->new GradeItem(r.getLong(1),r.getString(2),r.getDouble(3),r.getString(4)),id);
  for(GradeItem item:items){
   var submitted=answers.get(item.id());
   JsonNode answer=submitted==null||submitted.answer()==null?NullNode.getInstance():submitted.answer();
   List<Long> submittedAssets=validateSubmittedAssets(user,item.id(),submitted==null?List.of():submitted.assetIds());
   var grade=grading.grade(item.type(),item.maxScore(),answer,read(item.correctAnswer()));
   db.update("UPDATE rs_task_item SET final_answer_json=CAST(? AS jsonb),result_json=CAST(? AS jsonb),grading_status=? WHERE id=?",write(answer),write(grade.result()),grade.status(),item.id());
   for(long assetId:submittedAssets)db.update("UPDATE rs_answer_asset SET state='SUBMITTED' WHERE id=?",assetId);
  }
  db.update("UPDATE rs_review_task SET submit_key=?,submit_hash=?,submitted_at=CURRENT_TIMESTAMP,status='COMPLETED' WHERE id=?",key,hash,id);
  db.update("UPDATE rs_learning_activity SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE id=?",row.activityId());
  db.update("INSERT INTO rs_student_course_guard(user_id,subject_id) VALUES (?,1) ON CONFLICT DO NOTHING",user);
  db.update("UPDATE rs_student_course_guard SET learning_revision=learning_revision+1 WHERE user_id=? AND subject_id=1",user);
  db.update("INSERT INTO rs_change_event(event_type,source_id,source_revision,state) VALUES ('TASK_SUBMITTED',?,?,'PENDING')",id,row.sourceRevision()+1);
  return result(user,id);
 }
 public ResultView result(long user,long id){
  TaskStateRow row=taskState(user,id,false);
  if(!row.status().equals("COMPLETED"))conflict("Review task result is available only after completion");
  var items=db.query("SELECT ti.id,ti.position,ti.question_type,ti.max_score,ti.final_answer_json::text,ti.result_json::text,v.content_json::text,v.answer_json::text,v.rubric_json::text FROM rs_task_item ti JOIN rs_question_version v ON v.id=ti.question_version_id WHERE ti.task_id=? ORDER BY ti.position",(r,n)->{
   JsonNode stored=read(r.getString(7));
   String explanation=stored.path("explanation").isTextual()?stored.path("explanation").asText():"";
   return new ResultItem(String.valueOf(r.getLong(1)),r.getInt(2),r.getString(3),r.getDouble(4),publicContent(stored),readNullable(r.getString(5)),read(r.getString(8)),readNullable(r.getString(9)),explanation,readNullable(r.getString(6)),submittedAssets(user,r.getLong(1)));
  },id);
  return new ResultView(String.valueOf(row.id()),String.valueOf(row.activityId()),row.status(),row.submittedAt(),items);
 }
 private TaskStateRow taskState(long user,long id,boolean lock){
  var rows=db.query("SELECT t.id,t.activity_id,a.status,t.submit_key,t.submit_hash,t.submitted_at,t.source_revision FROM rs_review_task t JOIN rs_learning_activity a ON a.id=t.activity_id WHERE t.user_id=? AND t.id=?"+(lock?" FOR UPDATE":""),(r,n)->new TaskStateRow(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getObject(6,OffsetDateTime.class),r.getLong(7)),user,id);
  if(rows.isEmpty())notFound();
  return rows.getFirst();
 }
 private List<PracticeDtos.AssetView> submittedAssets(long user,long itemId){
  return db.query("SELECT id,position,mime,size_bytes FROM rs_answer_asset WHERE task_item_id=? AND user_id=? AND state='SUBMITTED' ORDER BY position",(r,n)->new PracticeDtos.AssetView(String.valueOf(r.getLong(1)),r.getInt(2),r.getString(3),r.getLong(4),"/api/answer-assets/"+r.getLong(1)),itemId,user);
 }
 private List<Long> validateSubmittedAssets(long user,long itemId,List<String> ids){
  if(ids==null)return List.of();
  var unique=new LinkedHashSet<Long>();
  for(String value:ids){
   long assetId=parseId(value);
   if(!unique.add(assetId))bad("Duplicate answer asset");
   if(count("SELECT count(*) FROM rs_answer_asset WHERE id=? AND user_id=? AND task_item_id=?",assetId,user,itemId)==0)bad("Answer asset does not belong to the task item");
  }
  return List.copyOf(unique);
 }
 private JsonNode publicContent(JsonNode source){if(source==null||!source.isObject())return source;ObjectNode copy=(ObjectNode)source.deepCopy();copy.remove("explanation");return copy;}
 private static void rejectClientScope(String userId,String subjectId,String sourceMode){if(userId!=null||subjectId!=null||sourceMode!=null)bad("userId, subjectId and sourceMode are server controlled");}
 private static long parseId(String value){try{return Long.parseLong(value);}catch(Exception e){bad("Invalid identifier");return 0;}}
 private long count(String sql,Object... args){Long c=db.queryForObject(sql,Long.class,args);return c==null?0:c;}
 private static void bad(String message){throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED",message);}
 private static void conflict(String message){throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT",message);}
 private static void notFound(){throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Review task not found");}
 private String hash(int count){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(Map.of("questionCount",count))));}catch(Exception e){throw new IllegalStateException(e);}}
 private String hashObject(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private String write(JsonNode node){try{return json.writeValueAsString(node);}catch(Exception e){throw new IllegalStateException(e);}}
 private String writeNullable(JsonNode node){return write(node==null?NullNode.getInstance():node);}
 private JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
 private JsonNode readNullable(String value){return value==null?null:read(value);}
}
