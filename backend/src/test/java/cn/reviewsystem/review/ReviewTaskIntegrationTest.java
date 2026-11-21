package cn.reviewsystem.review;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.mastery.MasteryController;
import cn.reviewsystem.mastery.MasteryEventConsumer;
import cn.reviewsystem.practice.ActiveActivityException;
import cn.reviewsystem.practice.AnswerAssetService;
import cn.reviewsystem.practice.PracticeMixConfig;
import cn.reviewsystem.recommend.RecommendationController;
import cn.reviewsystem.recommend.RecommendationService;
import cn.reviewsystem.recommend.VersionedRecommendationConfig;
import cn.reviewsystem.security.IdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @Transactional
class ReviewTaskIntegrationTest {
 @Autowired JdbcTemplate db;@Autowired ReviewTaskService tasks;@Autowired MasteryEventConsumer consumer;@Autowired ObjectMapper json;@Autowired AnswerAssetService assets;@Autowired RecommendationService recommendations;@Autowired VersionedRecommendationConfig recCfg;@Autowired RecommendationController recController;@Autowired MasteryController masteryController;

 private static final String PREFIX="m07"+UUID.randomUUID().toString().replace("-","").substring(0,10);
 private static int seq;
 private long chapter,kpA,kpB;
 private final List<Q> bank=new ArrayList<>();

 record Q(long questionId,long versionId,long knowledgeId,String type,double maxScore){}

 @BeforeEach void fixture(){
  int n=100+Math.floorMod(++seq+PREFIX.hashCode(),800);
  chapter=db.queryForObject("INSERT INTO rs_chapter(subject_id,code,name,sort_order) VALUES (1,?,?,?) RETURNING id",Long.class,PREFIX+"-C"+seq,PREFIX+"chapter"+seq,n);
  kpA=knowledge(PREFIX+"-A"+seq,1);kpB=knowledge(PREFIX+"-B"+seq,2);
  for(int i=0;i<6;i++){bank.add(question(kpA,"A"+i));bank.add(question(kpB,"B"+i));}
 }

 private long user(){return db.queryForObject("INSERT INTO rs_user(username,password_hash,role) VALUES (?,'x','STUDENT') RETURNING id",Long.class,PREFIX+"u"+UUID.randomUUID().toString().substring(0,10));}
 private long knowledge(String code,int order){return db.queryForObject("INSERT INTO rs_knowledge_point(subject_id,chapter_id,code,name,sort_order) VALUES (1,?,?,?,?) RETURNING id",Long.class,chapter,code,PREFIX+code,order);}
 private Q question(long knowledgeId,String suffix){
  long q=db.queryForObject("INSERT INTO rs_question(subject_id,question_no,origin,status) VALUES (1,?,'MANUAL','ACTIVE') RETURNING id",Long.class,(PREFIX+suffix+kpA).substring(0,Math.min(24,(PREFIX+suffix+knowledgeId).length())));
  String content=json.valueToTree(Map.of("stem",PREFIX+suffix,"options",List.of(Map.of("key","A","text","one"),Map.of("key","B","text","two")),"explanation","ex")).toString();
  String answer=json.valueToTree(Map.of("selectedOption","A")).toString();
  long v=db.queryForObject("INSERT INTO rs_question_version(question_id,version,subject_id,knowledge_point_id,type,max_score,content_json,answer_json,rubric_json,source_ref) VALUES (?,1,1,?,'CHOICE',10,CAST(? AS jsonb),CAST(? AS jsonb),NULL,?) RETURNING id",Long.class,q,knowledgeId,content,answer,PREFIX+suffix);
  db.update("UPDATE rs_question SET current_version_id=? WHERE id=?",v,q);
  return new Q(q,v,knowledgeId,"CHOICE",10);
 }

 @Test void createReplayConflictAndReason(){
  long u=user();
  var first=tasks.create(u,"same",new ReviewTaskService.CreateRequest(10));
  assertEquals(10,first.questionCount());
  assertEquals(7,first.mixRatio().path("planned").path("bank").asInt());
  assertEquals(3,first.mixRatio().path("planned").path("ai").asInt());
  assertEquals(0,first.mixRatio().path("actual").path("ai").asInt());
  assertEquals(3,first.mixRatio().path("degraded").size());
  assertEquals(0.7,first.mixRatio().path("ratio").path("bank").asDouble(),0.000001);
  for(int i=0;i<first.items().size();i++){
   var item=first.items().get(i);
   assertEquals(i,item.position());
   var facts=item.reason();
   assertTrue(facts.has("ruleVersion"));assertTrue(facts.has("weakness"));
   assertTrue(facts.has("time"));assertTrue(facts.has("error"));
   assertTrue(facts.has("evidenceIds"));assertTrue(facts.has("selectionCategory"));
   assertTrue(facts.has("finalScore"));assertTrue(facts.has("n"));
   assertFalse(item.questionVersionId().isBlank());
   assertTrue(Set.of("CHOICE","FILL","SHORT_ANSWER","ALGORITHM").contains(item.questionType()));
  }
  assertEquals(first.id(),tasks.create(u,"same",new ReviewTaskService.CreateRequest(10)).id());
  assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(BusinessException.class,()->tasks.create(u,"same",new ReviewTaskService.CreateRequest(9))).code());
  assertEquals("TASK",assertThrows(ActiveActivityException.class,()->tasks.create(u,"other",new ReviewTaskService.CreateRequest(10))).kind());
  tasks.cancel(u,Long.parseLong(first.id()));
  tasks.cancel(u,Long.parseLong(first.id()));
  assertEquals("CANCELLED",tasks.detail(u,Long.parseLong(first.id())).status());
 }

 @Test void taskRecordsGapWhenPoolIsShort(){
  long u=user();
  var view=tasks.create(u,"short",new ReviewTaskService.CreateRequest(20));
  var mix=view.mixRatio();
  if(view.questionCount()<20){
   assertEquals(20,mix.path("shortfall").path("requested").asInt());
   assertEquals(view.questionCount(),mix.path("shortfall").path("actual").asInt());
   assertEquals("INSUFFICIENT_RECOMMENDATIONS",mix.path("shortfall").path("reason").asText());
  }else{
   assertTrue(mix.path("shortfall").isMissingNode());
  }
  assertEquals(view.items().size(),view.questionCount());
  assertEquals(view.questionCount(),db.queryForObject("SELECT count(*) FROM rs_task_item WHERE task_id=?",Integer.class,Long.parseLong(view.id())));
 }

 @Test void requestWithoutQuestionsIsRejected(){
  long u=user();
  db.update("UPDATE rs_question SET status='SUSPENDED'");
  assertEquals("NO_AVAILABLE_QUESTION",assertThrows(BusinessException.class,()->tasks.create(u,"none",new ReviewTaskService.CreateRequest(5))).code());
  assertEquals(0,db.queryForObject("SELECT count(*) FROM rs_review_task WHERE user_id=?",Integer.class,u));
  assertEquals(0,db.queryForObject("SELECT count(*) FROM rs_learning_activity WHERE user_id=?",Integer.class,u));
 }

 @Test void taskSubmissionWritesTaskScopedEvidence(){
  long u=user();
  var view=tasks.create(u,"evidence",new ReviewTaskService.CreateRequest(3));
  long taskId=Long.parseLong(view.id());
  int practiceBefore=db.queryForObject("SELECT count(*) FROM rs_evidence WHERE source_type='PRACTICE' AND submission_id=?",Integer.class,taskId);
  db.update("UPDATE rs_task_item SET grading_status='AUTO_GRADED',result_json=CAST('{\"passed\":true}' AS jsonb) WHERE task_id=?",taskId);
  db.update("UPDATE rs_review_task SET submitted_at=CURRENT_TIMESTAMP,status='COMPLETED' WHERE id=?",taskId);
  db.update("INSERT INTO rs_change_event(event_type,source_id,source_revision,state) VALUES ('TASK_SUBMITTED',?,1,'PENDING')",taskId);
  assertTrue(consumer.processNext());
  assertEquals(3,db.queryForObject("SELECT count(*) FROM rs_evidence WHERE source_type='TASK' AND submission_id=?",Integer.class,taskId));
  assertEquals(practiceBefore,db.queryForObject("SELECT count(*) FROM rs_evidence WHERE source_type='PRACTICE' AND submission_id=?",Integer.class,taskId));
  assertEquals(3,db.queryForObject("SELECT count(*) FROM rs_evidence WHERE user_id=? AND result_y=1",Integer.class,u));
  assertEquals(3,db.queryForObject("SELECT count(*) FROM rs_evidence WHERE user_id=? AND source_type='TASK'",Integer.class,u));
  assertFalse(db.query("SELECT 1 FROM rs_change_event WHERE source_id=? AND state='FAILED'",(r,n)->1,taskId).iterator().hasNext());
  assertTrue(db.queryForObject("SELECT count(*) FROM rs_mastery_snapshot WHERE user_id=?",Integer.class,u)>0);
 }

 @Test void practiceAndTaskEvidenceDoNotMix(){
  long u=user();
  Q a=bank.get(0),b=bank.get(1);
  long act=db.queryForObject("INSERT INTO rs_learning_activity(user_id,subject_id,kind,status) VALUES (?,1,'PRACTICE','COMPLETED') RETURNING id",Long.class,u);
  long practice=db.queryForObject("INSERT INTO rs_practice(activity_id,user_id,subject_id,question_count,mix_ratio_json,request_key,submitted_at) VALUES (?,?,1,2,CAST('{}' AS jsonb),?,CURRENT_TIMESTAMP) RETURNING id",Long.class,act,u,PREFIX+"pk");
  db.update("INSERT INTO rs_practice_item(practice_id,question_id,question_version_id,knowledge_id,question_type,max_score,position,grading_status,result_json) VALUES (?,?,?,?,'CHOICE',10,0,'AUTO_GRADED',CAST('{\"passed\":false}' AS jsonb))",practice,a.questionId(),a.versionId(),a.knowledgeId());
  db.update("INSERT INTO rs_practice_item(practice_id,question_id,question_version_id,knowledge_id,question_type,max_score,position,grading_status,result_json) VALUES (?,?,?,?,'CHOICE',10,1,'AUTO_GRADED',CAST('{\"passed\":false}' AS jsonb))",practice,b.questionId(),b.versionId(),b.knowledgeId());
  db.update("INSERT INTO rs_change_event(event_type,source_id,source_revision,state) VALUES ('PRACTICE_SUBMITTED',?,1,'PENDING')",practice);
  assertTrue(consumer.processNext());
  var task=tasks.create(u,"mixed",new ReviewTaskService.CreateRequest(2));
  long taskId=Long.parseLong(task.id());
  int practiceRowsForTask=db.queryForObject("SELECT count(*) FROM rs_evidence WHERE source_type='PRACTICE' AND submission_id=?",Integer.class,taskId);
  int taskRowsForPractice=db.queryForObject("SELECT count(*) FROM rs_evidence WHERE source_type='TASK' AND submission_id=?",Integer.class,practice);
  db.update("UPDATE rs_task_item SET grading_status='AUTO_GRADED',result_json=CAST('{\"passed\":true}' AS jsonb) WHERE task_id=?",taskId);
  db.update("UPDATE rs_review_task SET submitted_at=CURRENT_TIMESTAMP,status='COMPLETED' WHERE id=?",taskId);
  db.update("INSERT INTO rs_change_event(event_type,source_id,source_revision,state) VALUES ('TASK_SUBMITTED',?,1,'PENDING')",taskId);
  assertTrue(consumer.processNext());
  assertEquals(2,db.queryForObject("SELECT count(*) FROM rs_evidence WHERE source_type='PRACTICE' AND submission_id=? AND result_y=0",Integer.class,practice));
  assertEquals(2,db.queryForObject("SELECT count(*) FROM rs_evidence WHERE source_type='TASK' AND submission_id=? AND result_y=1",Integer.class,taskId));
  assertEquals(practiceRowsForTask,db.queryForObject("SELECT count(*) FROM rs_evidence WHERE source_type='PRACTICE' AND submission_id=?",Integer.class,taskId));
  assertEquals(taskRowsForPractice,db.queryForObject("SELECT count(*) FROM rs_evidence WHERE source_type='TASK' AND submission_id=?",Integer.class,practice));
 }

 @Test void unknownEventTypeFailsInsteadOfTouchingPractices(){
  long u=user();
  db.update("INSERT INTO rs_change_event(event_type,source_id,source_revision,state) VALUES ('UNKNOWN_EVENT',999999,1,'PENDING')");
  consumer.processNext();
  assertTrue(db.queryForObject("SELECT attempts FROM rs_change_event WHERE event_type='UNKNOWN_EVENT'",Integer.class)>0);
  assertEquals(0,db.queryForObject("SELECT count(*) FROM rs_evidence WHERE submission_id=999999",Integer.class));
 }

 @Test void taskItemsAcceptImagesOnlyWhileInProgress(){
  long u=user();
  var view=tasks.create(u,"assets",new ReviewTaskService.CreateRequest(4));
  long taskId=Long.parseLong(view.id()),itemId=Long.parseLong(view.items().get(0).id());
  byte[] bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
  var file=new MockMultipartFile("file","a.png","image/png",bytes);
  assertEquals("STATE_CONFLICT",assertThrows(BusinessException.class,()->assets.uploadForTask(u,taskId,itemId,file)).code());
  assertEquals("IN_PROGRESS",tasks.start(u,taskId).status());
  assertEquals("IN_PROGRESS",tasks.start(u,taskId).status());
  var first=assets.uploadForTask(u,taskId,itemId,file);
  assertEquals(0,first.position());
  assertEquals("/api/answer-assets/"+first.id(),first.url());
  assets.uploadForTask(u,taskId,itemId,file);assets.uploadForTask(u,taskId,itemId,file);
  assertEquals("UPLOAD_INVALID",assertThrows(BusinessException.class,()->assets.uploadForTask(u,taskId,itemId,file)).code());
  assertEquals(3,db.queryForObject("SELECT count(*) FROM rs_answer_asset WHERE task_item_id=?",Integer.class,itemId).intValue());
  assertEquals(0,db.queryForObject("SELECT count(*) FROM rs_answer_asset WHERE task_item_id=? AND practice_item_id IS NOT NULL",Integer.class,itemId).intValue());
  long other=user();
  assertEquals("NOT_FOUND",assertThrows(BusinessException.class,()->assets.read(other,Long.parseLong(first.id()))).code());
  assertArrayEquals(bytes,assets.read(u,Long.parseLong(first.id())).bytes());
  long otherTask=Long.parseLong(tasks.create(other,"other",new ReviewTaskService.CreateRequest(2)).id());
  assertEquals("NOT_FOUND",assertThrows(BusinessException.class,()->assets.uploadForTask(other,otherTask,itemId,file)).code());
  assets.deleteForTask(u,taskId,itemId,Long.parseLong(first.id()));
  assertEquals(2,db.queryForObject("SELECT count(*) FROM rs_answer_asset WHERE task_item_id=?",Integer.class,itemId).intValue());
  assertEquals("STATE_CONFLICT",assertThrows(BusinessException.class,()->tasks.cancel(u,taskId)).code());
  assertThrows(DataIntegrityViolationException.class,()->db.update("INSERT INTO rs_answer_asset(user_id,position,storage_key,mime,size_bytes,sha256) VALUES (?,0,?,?,?,?)",u,"orphan"+UUID.randomUUID(),"image/png",8,"0".repeat(64)));
 }

 @Test void customMixConfigAndRecommendationBoundsAndTaskUpdating(){
  long u=user();
  var customTasks=new ReviewTaskService(db,json,recommendations,new PracticeMixConfig("v2",0.5,0.5),recCfg);
  var view=customTasks.create(u,"custom-mix",new ReviewTaskService.CreateRequest(6));
  assertEquals("v2",view.mixRatio().path("version").asText());
  assertEquals(0.5,view.mixRatio().path("ratio").path("bank").asDouble(),0.000001);
  assertEquals(0.5,view.mixRatio().path("ratio").path("ai").asDouble(),0.000001);
  assertEquals(3,view.mixRatio().path("planned").path("ai").asInt());
  assertEquals(3,view.mixRatio().path("degraded").size());
  var auth=new UsernamePasswordAuthenticationToken(new IdentityService.User(u,PREFIX+"u","STUDENT",true,"s"),null,List.of());
  assertEquals("VALIDATION_FAILED",assertThrows(BusinessException.class,()->recController.recommendations(auth,0)).code());
  assertEquals("VALIDATION_FAILED",assertThrows(BusinessException.class,()->recController.recommendations(auth,11)).code());
  assertEquals(10,recController.recommendations(auth,10).items().size());
  long taskId=Long.parseLong(view.id());
  db.update("INSERT INTO rs_change_event(event_type,source_id,source_revision,state) VALUES ('TASK_SUBMITTED',?,1,'PENDING')",taskId);
  assertTrue(recController.recommendations(auth,10).updating());
  var page=masteryController.overview(auth,null,0,20).items();
  assertFalse(page.isEmpty());
  assertTrue(page.stream().allMatch(MasteryController.Overview::updating));
 }
}
