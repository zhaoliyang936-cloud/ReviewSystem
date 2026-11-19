package cn.reviewsystem.mastery;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.question.PageResult;
import cn.reviewsystem.security.IdentityService;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class MasteryController {
 private final JdbcTemplate db;private final ObjectMapper json;
 public MasteryController(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 public record Overview(String knowledgeId,String knowledgeCode,String knowledgeName,String chapterTitle,int n,BigDecimal ruleScore,BigDecimal aiScore,BigDecimal finalScore,boolean clamped,String status,OffsetDateTime latestPracticeAt,long evidenceRevision,boolean updating){}
 public record EvidenceView(String questionNo,String questionType,BigDecimal weight,BigDecimal resultY,OffsetDateTime submittedAt){}
 public record History(BigDecimal ruleScore,BigDecimal aiScore,BigDecimal finalScore,boolean clamped,JsonNode aiReasonJson,long evidenceRevision,String ruleVersion,OffsetDateTime createdAt,List<EvidenceView> evidence){}
 @GetMapping("/api/review/mastery") public PageResult<Overview> overview(Authentication auth,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){check(page,size);if(status!=null&&!Set.of("UNASSESSED","INSUFFICIENT","WEAK","CONSOLIDATE","STABLE").contains(status))throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Invalid status");long user=user(auth);int limit=Math.min(size,100);String where=" WHERE k.subject_id=1 AND k.enabled"+(status==null?"":" AND COALESCE(s.status,'UNASSESSED')=?");String from=" FROM rs_knowledge_point k JOIN rs_chapter c ON c.id=k.chapter_id LEFT JOIN rs_mastery_current mc ON mc.user_id=? AND mc.knowledge_id=k.id LEFT JOIN rs_mastery_snapshot s ON s.id=mc.snapshot_id";List<Object> args=new ArrayList<>();args.add(user);if(status!=null)args.add(status);long total=db.queryForObject("SELECT count(*)"+from+where,Long.class,args.toArray());String query="SELECT k.id,k.code,k.name,c.name,COALESCE(s.n,0),s.rule_score,s.ai_score,s.final_score,COALESCE(s.clamped,FALSE),COALESCE(s.status,'UNASSESSED'),s.latest_practice_at,COALESCE(s.evidence_revision,0)"+from+where+" ORDER BY c.sort_order,k.sort_order,k.id LIMIT ? OFFSET ?";args.add(limit);args.add(page*limit);boolean updating=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM rs_change_event e JOIN rs_practice p ON p.id=e.source_id WHERE e.event_type='PRACTICE_SUBMITTED' AND p.user_id=? AND e.state<>'DONE')",Boolean.class,user));var rows=db.query(query,(r,n)->new Overview(String.valueOf(r.getLong(1)),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getBigDecimal(6),r.getBigDecimal(7),r.getBigDecimal(8),r.getBoolean(9),r.getString(10),r.getObject(11,OffsetDateTime.class),r.getLong(12),updating),args.toArray());return new PageResult<>(rows,page,limit,total);}
 @GetMapping("/api/review/knowledge/{id}/history") public PageResult<History> history(Authentication auth,@PathVariable long id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){check(page,size);long user=user(auth);Long visible=db.queryForObject("SELECT count(*) FROM rs_knowledge_point WHERE id=? AND subject_id=1 AND enabled",Long.class,id);if(visible==0)throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Knowledge point not found");int limit=Math.min(size,100);long total=db.queryForObject("SELECT count(*) FROM rs_mastery_snapshot WHERE user_id=? AND knowledge_id=?",Long.class,user,id);var rows=db.query("SELECT rule_score,ai_score,final_score,clamped,ai_reason_json::text,evidence_revision,rule_version,created_at,evidence_ids_json::text FROM rs_mastery_snapshot WHERE user_id=? AND knowledge_id=? ORDER BY id DESC LIMIT ? OFFSET ?",(r,n)->new History(r.getBigDecimal(1),r.getBigDecimal(2),r.getBigDecimal(3),r.getBoolean(4),parse(r.getString(5)),r.getLong(6),r.getString(7),r.getObject(8,OffsetDateTime.class),evidence(r.getString(9),user)),user,id,limit,page*limit);return new PageResult<>(rows,page,limit,total);}
 List<EvidenceView> evidence(String ids,long user){try{JsonNode selected=json.readTree(ids);if(selected.isEmpty())return List.of();Long[] keys=new Long[selected.size()];for(int i=0;i<selected.size();i++)keys[i]=selected.get(i).asLong();Map<Long,EvidenceView> found=new HashMap<>();db.query("SELECT e.id,q.question_no,e.question_type,e.weight,e.result_y,e.submitted_at FROM rs_evidence e JOIN rs_question q ON q.id=e.question_id WHERE e.id = ANY(?) AND e.user_id=?",ps->{ps.setArray(1,ps.getConnection().createArrayOf("bigint",keys));ps.setLong(2,user);},r->{found.put(r.getLong(1),new EvidenceView(r.getString(2),r.getString(3),r.getBigDecimal(4),r.getBigDecimal(5),r.getObject(6,OffsetDateTime.class)));});List<EvidenceView> ordered=new ArrayList<>();for(long key:keys){EvidenceView value=found.get(key);if(value!=null)ordered.add(value);}return ordered;}catch(Exception e){throw new IllegalStateException(e);}}
 private JsonNode parse(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
 private static void check(int page,int size){if(page<0||size<1)throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Invalid pagination");}
 private static long user(Authentication auth){return ((IdentityService.User)auth.getPrincipal()).id();}
}
