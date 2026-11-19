package cn.reviewsystem.mastery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MasteryEventConsumer {
 private final JdbcTemplate db;private final ObjectMapper json;private final MasteryJudgePort judge;private final TransactionTemplate tx;
 public MasteryEventConsumer(JdbcTemplate db,ObjectMapper json,MasteryJudgePort judge,PlatformTransactionManager manager){this.db=db;this.json=json;this.judge=judge;this.tx=new TransactionTemplate(manager);}
 @Scheduled(fixedDelay=2000) public void poll(){for(int i=0;i<20;i++)if(!processNext())break;}
 public boolean processNext(){try{Long id=tx.execute(s->{var ids=db.query("SELECT id FROM rs_change_event WHERE state IN ('PENDING','FAILED') AND next_retry_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 20 FOR UPDATE SKIP LOCKED",(r,n)->r.getLong(1));if(ids.isEmpty())return null;long eventId=ids.get(0);try{apply(eventId);}catch(Exception e){throw new EventFailure(eventId,e);}return eventId;});return id!=null;}catch(EventFailure failure){tx.executeWithoutResult(s->{int attempts=db.queryForObject("SELECT attempts FROM rs_change_event WHERE id=? FOR UPDATE",Integer.class,failure.eventId)+1;db.update("UPDATE rs_change_event SET state='FAILED',attempts=?,next_retry_at=CURRENT_TIMESTAMP+(? * INTERVAL '1 second'),last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",attempts,Math.min(1L<<Math.min(attempts,30),300),root(failure),failure.eventId);});return true;}}
 private void apply(long id){var event=db.queryForMap("SELECT source_id,source_revision,state FROM rs_change_event WHERE id=?",id);long practiceId=((Number)event.get("source_id")).longValue();int revision=((Number)event.get("source_revision")).intValue();if(!"PENDING".equals(event.get("state"))&&!"FAILED".equals(event.get("state")))return;
  var practice=db.queryForMap("SELECT user_id,subject_id,submitted_at FROM rs_practice WHERE id=?",practiceId);long user=((Number)practice.get("user_id")).longValue(),subject=((Number)practice.get("subject_id")).longValue();OffsetDateTime submitted=((java.sql.Timestamp)practice.get("submitted_at")).toInstant().atOffset(ZoneOffset.UTC);
  db.update("INSERT INTO rs_student_course_guard(user_id,subject_id) VALUES (?,?) ON CONFLICT DO NOTHING",user,subject);db.queryForObject("SELECT learning_revision FROM rs_student_course_guard WHERE user_id=? AND subject_id=? FOR UPDATE",Long.class,user,subject);
  var items=db.query("SELECT question_id,question_version_id,knowledge_id,question_type,result_json->>'passed' AS passed FROM rs_practice_item WHERE practice_id=? AND grading_status='AUTO_GRADED' AND result_json->>'passed' IS NOT NULL",(r,n)->new Item(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getBoolean(5)),practiceId);
  Set<Long> affected=new LinkedHashSet<>();for(Item item:items){affected.add(item.knowledgeId());db.update("INSERT INTO rs_evidence(user_id,source_type,submission_id,question_id,question_version_id,knowledge_id,question_type,result_y,weight,source_revision,submitted_at,graded_at) VALUES (?,'PRACTICE',?,?,?,?,?,?,1.00,?,?,CURRENT_TIMESTAMP) ON CONFLICT(source_type,submission_id,question_id) DO UPDATE SET question_version_id=EXCLUDED.question_version_id,knowledge_id=EXCLUDED.knowledge_id,question_type=EXCLUDED.question_type,result_y=EXCLUDED.result_y,weight=EXCLUDED.weight,source_revision=EXCLUDED.source_revision WHERE EXCLUDED.source_revision >= rs_evidence.source_revision",user,practiceId,item.questionId(),item.versionId(),item.knowledgeId(),item.type(),item.passed()?BigDecimal.ONE:BigDecimal.ZERO,revision,submitted);}
  for(long knowledge:affected)recalculate(user,knowledge);db.update("UPDATE rs_change_event SET state='DONE',last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
 }
 public void recalculate(long user,long knowledge){var evidence=db.query("SELECT id,question_id,result_y,weight,submitted_at FROM rs_evidence WHERE user_id=? AND knowledge_id=? AND revoked=FALSE AND result_y IS NOT NULL ORDER BY submitted_at DESC,id DESC",(r,n)->new MasteryCalculation.Evidence(r.getLong(1),r.getLong(2),r.getBigDecimal(3),r.getBigDecimal(4),r.getObject(5,OffsetDateTime.class)),user,knowledge);var result=MasteryCalculation.calculate(evidence,judge,user,knowledge);List<Long> ids=result.selected().stream().map(MasteryCalculation.Evidence::id).toList();OffsetDateTime latest=result.selected().isEmpty()?null:result.selected().get(0).submittedAt();Long prior=db.query("SELECT evidence_revision FROM rs_mastery_current WHERE user_id=? AND knowledge_id=?",(r,n)->r.getLong(1),user,knowledge).stream().findFirst().orElse(0L);long rev=prior+1;try{long snapshot=Objects.requireNonNull(db.queryForObject("INSERT INTO rs_mastery_snapshot(user_id,knowledge_id,n,weight_sum,weighted_sum,rule_score,ai_score,final_score,clamped,status,ai_reason_json,ai_job_id,evidence_revision,rule_version,evidence_ids_json,latest_practice_at,cutoff_at) VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,'v1',CAST(? AS jsonb),?,CURRENT_TIMESTAMP) RETURNING id",Long.class,user,knowledge,result.n(),scale(result.weightSum()),scale(result.weightedSum()),scale(result.ruleScore()),scale(result.aiScore()),scale(result.finalScore()),result.clamped(),result.status(),result.aiReasonJson(),result.aiJobId(),rev,json.writeValueAsString(ids),latest));db.update("INSERT INTO rs_mastery_current(user_id,knowledge_id,snapshot_id,evidence_revision) VALUES (?,?,?,?) ON CONFLICT(user_id,knowledge_id) DO UPDATE SET snapshot_id=EXCLUDED.snapshot_id,evidence_revision=EXCLUDED.evidence_revision,updated_at=CURRENT_TIMESTAMP",user,knowledge,snapshot,rev);}catch(Exception e){throw new IllegalStateException(e);}}
 private static BigDecimal scale(BigDecimal value){return value==null?null:value.setScale(3,RoundingMode.HALF_UP);}
 private record Item(long questionId,long versionId,long knowledgeId,String type,boolean passed){}
 private static String root(Throwable t){while(t.getCause()!=null)t=t.getCause();String value=t.getMessage();return value==null?t.getClass().getSimpleName():value.substring(0,Math.min(2000,value.length()));}
 private static final class EventFailure extends RuntimeException {final long eventId;EventFailure(long eventId,Throwable cause){super(cause);this.eventId=eventId;}}
}
