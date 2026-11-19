package cn.reviewsystem.question;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.course.CourseService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionService {
 private final JdbcTemplate db;private final ObjectMapper json;private final QuestionValidator validator;private final CourseService courses;private final QuestionAssetService assets;
 public QuestionService(JdbcTemplate db,ObjectMapper json,QuestionValidator validator,CourseService courses,QuestionAssetService assets){this.db=db;this.json=json;this.validator=validator;this.courses=courses;this.assets=assets;}

 @Transactional public AdminQuestionDetail create(QuestionWriteRequest q){
  if(q.origin()!=null)validation("origin is server controlled and must not be supplied");var valid=validator.validate(q);long kp=parseId(q.knowledgePointId());courses.requireEnabledKnowledgePoint(kp);
  Long seq=db.queryForObject("SELECT nextval('rs_question_no_seq')",Long.class);String number="DS-%06d".formatted(seq);
  Long id=db.queryForObject("INSERT INTO rs_question(subject_id,question_no,origin) VALUES (1,?,'MANUAL') RETURNING id",Long.class,number);
  long versionId=insertVersion(id,1,kp,q,valid);db.update("UPDATE rs_question SET current_version_id=? WHERE id=?",versionId,id);return detail(id);
 }

 @Transactional public AdminQuestionDetail update(long id,QuestionWriteRequest q){
  if(q.origin()!=null)validation("origin is server controlled and must not be supplied");var valid=validator.validate(q);long kp=parseId(q.knowledgePointId());if(q.expectedVersion()==null)validation("expectedVersion is required");
  Current current=current(id);if(current.version()!=q.expectedVersion())throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Question was changed by another request");if(!Objects.equals(current.knowledgePointId(),kp))courses.requireEnabledKnowledgePoint(kp);
  String content=write(storageContent(q.content(),valid.explanation())),answer=write(q.answer()),rubric=q.rubric()==null||q.rubric().isNull()?null:write(q.rubric());
  if(Objects.equals(current.knowledgePointId(),kp)&&current.type().equals(valid.type().name())&&Double.compare(current.maxScore(),valid.maxScore())==0&&jsonEqual(current.content(),content)&&jsonEqual(current.answer(),answer)&&jsonEqual(current.rubric(),rubric)&&Objects.equals(blank(current.sourceRef()),blank(valid.sourceRef())))return detail(id);
  int next=current.version()+1;long versionId=insertVersion(id,next,kp,q,valid);assets.copyVersionAssets(current.versionId(),versionId);int n=db.update("UPDATE rs_question SET current_version_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND current_version_id=?",versionId,id,current.versionId());if(n==0)throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Question was changed by another request");return detail(id);
 }

 @Transactional public AdminQuestionDetail status(long id,boolean suspend,String reason,Integer expectedVersion){
  Current current=current(id);if(expectedVersion==null||current.version()!=expectedVersion)throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Question was changed by another request");
  String target=suspend?"SUSPENDED":"ACTIVE";if(current.status().equals(target))throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Question is already "+target);
  if(suspend&&(reason==null||reason.isBlank()))validation("Suspension reason is required");if(!suspend){validator.validate(validationRequest(current));if(current.knowledgePointId()==null)throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_BINDING","Question has no knowledge point binding");courses.requireKnowledgePoint(current.knowledgePointId());}
  db.update("UPDATE rs_question SET status=?,suspension_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",suspend?"SUSPENDED":"ACTIVE",suspend?reason.trim():null,id);return detail(id);
 }

 public AdminQuestionDetail detail(long id){
  var rows=db.query("SELECT q.id,q.question_no,q.origin,q.status,q.suspension_reason,v.id version_id,v.version,v.knowledge_point_id,v.type,v.max_score,v.content_json::text,v.answer_json::text,v.rubric_json::text,v.source_ref FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id WHERE q.id=? AND q.subject_id=1",(r,n)->mapDetail(r),id);if(rows.isEmpty())notFound();return rows.get(0);
 }

 public List<AdminQuestionDetail.VersionView> versions(long id){
  if(count("SELECT count(*) FROM rs_question WHERE id=? AND subject_id=1",id)==0)notFound();return db.query("SELECT v.id,v.version,v.content_json::text FROM rs_question_version v JOIN rs_question q ON q.id=v.question_id WHERE q.id=? AND q.subject_id=1 AND v.id<>q.current_version_id ORDER BY v.version DESC",(r,n)->new AdminQuestionDetail.VersionView(r.getInt("version"),publicContent(read(r.getString("content_json"))),assetViews(r.getLong("id"))),id);
 }

 public StudentQuestion student(long id){
  AdminQuestionDetail d=detail(id);if(!d.status().equals("ACTIVE"))notFound();return new StudentQuestion(d.id(),d.questionNo(),d.knowledgePointId(),d.type(),d.version(),d.maxScore(),publicContent(d.content()),d.assets().stream().map(a->new StudentQuestion.AssetRef(a.id(),"/api/question-assets/"+a.id())).toList());
 }

 public PageResult<QuestionListItem> list(int page,int size,String origin,String type,String status,String chapterId,String knowledgeId,String keyword){
  if(page<0)validation("page must not be negative");if(size<1)validation("size must be positive");int actualSize=Math.min(size,100);
  var where=new StringBuilder(" WHERE q.subject_id=1");var args=new ArrayList<Object>();filter(where,args,"q.origin",origin,Set.of("MANUAL","AI_GENERATED"));filter(where,args,"v.type",type,Set.of("CHOICE","FILL","SHORT_ANSWER","ALGORITHM"));filter(where,args,"q.status",status,Set.of("ACTIVE","SUSPENDED"));
  if(chapterId!=null&&!chapterId.isBlank()){where.append(" AND k.chapter_id=?");args.add(parseId(chapterId));}if(knowledgeId!=null&&!knowledgeId.isBlank()){where.append(" AND v.knowledge_point_id=?");args.add(parseId(knowledgeId));}if(keyword!=null&&!keyword.isBlank()){String pattern="%"+escapeLike(keyword.trim())+"%";where.append(" AND (q.question_no ILIKE ? ESCAPE E'\\\\' OR v.content_json->>'stem' ILIKE ? ESCAPE E'\\\\')");args.add(pattern);args.add(pattern);}
  String joins=" FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id LEFT JOIN rs_knowledge_point k ON k.id=v.knowledge_point_id";Long total=db.queryForObject("SELECT count(*)"+joins+where,Long.class,args.toArray());args.add(actualSize);args.add(page*actualSize);
  String sql="SELECT q.id,q.question_no,v.knowledge_point_id,k.name,v.type,q.origin,q.status,v.version,v.max_score,v.content_json::text"+joins+where+" ORDER BY q.id DESC LIMIT ? OFFSET ?";
  var items=db.query(sql,(r,n)->new QuestionListItem(String.valueOf(r.getLong(1)),r.getString(2),r.getObject(3)==null?null:String.valueOf(r.getLong(3)),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getInt(8),r.getDouble(9),publicContent(read(r.getString(10)))),args.toArray());return new PageResult<>(items,page,actualSize,total==null?0:total);
 }

 public CoverageReport coverage(){
  var data=new LinkedHashMap<Long,CoverageBuilder>();db.query("SELECT id,code,name FROM rs_knowledge_point WHERE subject_id=1 ORDER BY chapter_id,sort_order,id",r->{long id=r.getLong(1);data.put(id,new CoverageBuilder(String.valueOf(id),r.getString(2),r.getString(3)));});
  long missing=count("SELECT count(*) FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id WHERE q.subject_id=1 AND v.knowledge_point_id IS NULL");
  var incomplete=new ArrayList<CoverageReport.IncompleteQuestion>();long incompleteCount=0;
  var questions=db.query("SELECT q.id,q.question_no,q.origin,v.id version_id,v.version,v.knowledge_point_id,v.type,v.max_score,v.content_json::text,v.answer_json::text,v.rubric_json::text,v.source_ref,q.status FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id WHERE q.subject_id=1 ORDER BY q.id",(r,n)->mapCurrent(r));
  for(Current question:questions){CoverageBuilder builder=question.knowledgePointId()==null?null:data.get(question.knowledgePointId());if(builder!=null)builder.sawQuestion();try{validator.validate(validationRequest(question));if(question.status().equals("ACTIVE")&&builder!=null)builder.add(question.type(),question.origin(),1);}catch(BusinessException e){incompleteCount++;if(incomplete.size()<3)incomplete.add(new CoverageReport.IncompleteQuestion(String.valueOf(question.questionId()),question.questionNo(),e.getMessage()));}}
  var points=data.values().stream().map(CoverageBuilder::build).toList();var zero=points.stream().filter(CoverageReport.KnowledgeCoverage::zeroQuestion).map(p->new CoverageReport.KnowledgeSummary(p.knowledgePointId(),p.code(),p.name())).toList();var unusable=points.stream().filter(CoverageReport.KnowledgeCoverage::unusableOnlyQuestion).map(p->new CoverageReport.KnowledgeSummary(p.knowledgePointId(),p.code(),p.name())).toList();
  return new CoverageReport(points,zero,unusable,missing,incompleteCount,incomplete);
 }

 Current current(long id){var x=db.query("SELECT q.id,q.question_no,q.origin,v.id version_id,v.version,v.knowledge_point_id,v.type,v.max_score,v.content_json::text,v.answer_json::text,v.rubric_json::text,v.source_ref,q.status FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id WHERE q.id=? AND q.subject_id=1",(r,n)->mapCurrent(r),id);if(x.isEmpty())notFound();return x.get(0);}
 private long insertVersion(long questionId,int version,long kp,QuestionWriteRequest q,QuestionValidator.Validated v){String rubric=q.rubric()==null||q.rubric().isNull()?null:write(q.rubric());Long id=db.queryForObject("INSERT INTO rs_question_version(question_id,version,subject_id,knowledge_point_id,type,max_score,content_json,answer_json,rubric_json,source_ref) VALUES (?,?,1,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?) RETURNING id",Long.class,questionId,version,kp,v.type().name(),v.maxScore(),write(storageContent(q.content(),v.explanation())),write(q.answer()),rubric,v.sourceRef());return Objects.requireNonNull(id);}
 private Current mapCurrent(ResultSet r)throws SQLException {Object kp=r.getObject("knowledge_point_id");return new Current(r.getLong("id"),r.getString("question_no"),r.getString("origin"),r.getLong("version_id"),r.getInt("version"),kp==null?null:((Number)kp).longValue(),r.getString("type"),r.getDouble("max_score"),r.getString("content_json"),r.getString("answer_json"),r.getString("rubric_json"),r.getString("source_ref"),r.getString("status"));}
 private QuestionWriteRequest validationRequest(Current current){JsonNode stored=read(current.content());String explanation=stored.path("explanation").isTextual()?stored.path("explanation").asText():"";return new QuestionWriteRequest(current.knowledgePointId()==null?null:String.valueOf(current.knowledgePointId()),current.type(),publicContent(stored),read(current.answer()),readNullable(current.rubric()),explanation,current.sourceRef(),null,current.maxScore(),current.version());}
 private AdminQuestionDetail mapDetail(ResultSet r)throws SQLException {long versionId=r.getLong("version_id");JsonNode stored=read(r.getString("content_json"));String explanation=stored.path("explanation").isTextual()?stored.path("explanation").asText():"";return new AdminQuestionDetail(String.valueOf(r.getLong("id")),r.getString("question_no"),r.getObject("knowledge_point_id")==null?null:String.valueOf(r.getLong("knowledge_point_id")),r.getString("type"),r.getString("origin"),r.getString("status"),r.getString("suspension_reason"),r.getInt("version"),r.getDouble("max_score"),publicContent(stored),read(r.getString("answer_json")),readNullable(r.getString("rubric_json")),explanation,r.getString("source_ref"),assetViews(versionId));}
 private List<AdminQuestionDetail.AssetView> assetViews(long versionId){return db.query("SELECT id,position,mime,size_bytes,width,height,sha256 FROM rs_question_asset WHERE question_version_id=? ORDER BY position",(a,n)->new AdminQuestionDetail.AssetView(String.valueOf(a.getLong(1)),a.getInt(2),a.getString(3),a.getLong(4),a.getInt(5),a.getInt(6),a.getString(7)),versionId);}
 private ObjectNode storageContent(JsonNode source,String explanation){ObjectNode copy=(ObjectNode)source.deepCopy();if(explanation==null||explanation.isBlank())copy.remove("explanation");else copy.put("explanation",explanation);return copy;}
 private JsonNode publicContent(JsonNode source){if(source==null)return null;ObjectNode copy=(ObjectNode)source.deepCopy();copy.remove("explanation");return copy;}
 private boolean jsonEqual(String a,String b){if(a==null||b==null)return Objects.equals(a,b);return read(a).equals(read(b));}
 private JsonNode read(String value){try{return json.readTree(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
 private JsonNode readNullable(String value){return value==null?null:read(value);}
 private String write(JsonNode value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
 private static void filter(StringBuilder where,List<Object> args,String column,String value,Set<String> allowed){if(value==null||value.isBlank())return;if(!allowed.contains(value))validation("Invalid filter");where.append(" AND ").append(column).append("=?");args.add(value);}
 private long count(String sql,Object... args){Long n=db.queryForObject(sql,Long.class,args);return n==null?0:n;}
 private static long parseId(String value){try{return Long.parseLong(value);}catch(Exception e){throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_BINDING","Invalid knowledge point identifier");}}
 private static String blank(String s){return s==null||s.isBlank()?null:s;}
 private static String escapeLike(String value){return value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
 private static void validation(String message){throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED",message);}
 private static void notFound(){throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Question not found");}
 record Current(long questionId,String questionNo,String origin,long versionId,int version,Long knowledgePointId,String type,double maxScore,String content,String answer,String rubric,String sourceRef,String status){}
 private static class CoverageBuilder {final String id,code,name;final Map<String,long[]> counts=new LinkedHashMap<>();long totalQuestions,usableQuestions;CoverageBuilder(String id,String code,String name){this.id=id;this.code=code;this.name=name;for(var t:QuestionType.values())counts.put(t.name(),new long[2]);}void sawQuestion(){totalQuestions++;}void add(String type,String origin,long n){counts.get(type)[origin.equals("MANUAL")?0:1]+=n;usableQuestions+=n;}CoverageReport.KnowledgeCoverage build(){return new CoverageReport.KnowledgeCoverage(id,code,name,totalQuestions==0,totalQuestions>0&&usableQuestions==0,counts.entrySet().stream().map(e->new CoverageReport.TypeCount(e.getKey(),e.getValue()[0],e.getValue()[1])).toList());}}
}
