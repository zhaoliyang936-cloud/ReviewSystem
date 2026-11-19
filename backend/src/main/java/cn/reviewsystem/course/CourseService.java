package cn.reviewsystem.course;

import cn.reviewsystem.api.BusinessException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {
 private final JdbcTemplate db;
 public CourseService(JdbcTemplate db){this.db=db;}
 public CourseDtos.CourseView course(){
  var subject=db.queryForMap("SELECT id,code,name FROM rs_subject WHERE id=1");
  return new CourseDtos.CourseView(subject.get("id").toString(),(String)subject.get("code"),(String)subject.get("name"),chapters());
 }
 public List<CourseDtos.ChapterView> chapters(){return db.query("SELECT id,code,name,description,sort_order,enabled,version FROM rs_chapter WHERE subject_id=1 ORDER BY sort_order,id",(r,n)->new CourseDtos.ChapterView(String.valueOf(r.getLong(1)),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getBoolean(6),r.getInt(7)));}
 public List<CourseDtos.KnowledgePointView> knowledgePoints(){return db.query("SELECT id,chapter_id,code,name,description,sort_order,enabled,version FROM rs_knowledge_point WHERE subject_id=1 ORDER BY chapter_id,sort_order,id",(r,n)->new CourseDtos.KnowledgePointView(String.valueOf(r.getLong(1)),String.valueOf(r.getLong(2)),r.getString(3),r.getString(4),r.getString(5),r.getInt(6),r.getBoolean(7),r.getInt(8)));}
 @Transactional public CourseDtos.ChapterView createChapter(CourseDtos.ChapterRequest q){validate(q.code(),q.name(),q.sortOrder(),false);db.update("INSERT INTO rs_chapter(subject_id,code,name,description,sort_order,enabled) VALUES (1,?,?,?,?,?)",q.code().trim(),q.name().trim(),text(q.description()),q.sortOrder(),enabled(q.enabled()));Long id=db.queryForObject("SELECT currval(pg_get_serial_sequence('rs_chapter','id'))",Long.class);return chapter(id);}
 @Transactional public CourseDtos.ChapterView updateChapter(long id,CourseDtos.ChapterRequest q){validate(q.code(),q.name(),q.sortOrder(),false);if(q.expectedVersion()==null)validation("expectedVersion is required");int n=db.update("UPDATE rs_chapter SET code=?,name=?,description=?,sort_order=?,enabled=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND subject_id=1 AND version=?",q.code().trim(),q.name().trim(),text(q.description()),q.sortOrder(),enabled(q.enabled()),id,q.expectedVersion());if(n==0){if(count("SELECT count(*) FROM rs_chapter WHERE id=? AND subject_id=1",id)==0)notFound();throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Chapter was changed by another request");}return chapter(id);}
 @Transactional public CourseDtos.KnowledgePointView createKnowledgePoint(CourseDtos.KnowledgePointRequest q){validate(q.code(),q.name(),q.sortOrder(),true);long chapter=parseId(q.chapterId());requireChapter(chapter);db.update("INSERT INTO rs_knowledge_point(subject_id,chapter_id,code,name,description,sort_order,enabled) VALUES (1,?,?,?,?,?,?)",chapter,q.code().trim(),q.name().trim(),text(q.description()),q.sortOrder(),enabled(q.enabled()));Long id=db.queryForObject("SELECT currval(pg_get_serial_sequence('rs_knowledge_point','id'))",Long.class);return knowledgePoint(id);}
 @Transactional public CourseDtos.KnowledgePointView updateKnowledgePoint(long id,CourseDtos.KnowledgePointRequest q){validate(q.code(),q.name(),q.sortOrder(),true);if(q.expectedVersion()==null)validation("expectedVersion is required");long chapter=parseId(q.chapterId());requireChapter(chapter);int n=db.update("UPDATE rs_knowledge_point SET chapter_id=?,code=?,name=?,description=?,sort_order=?,enabled=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND subject_id=1 AND version=?",chapter,q.code().trim(),q.name().trim(),text(q.description()),q.sortOrder(),enabled(q.enabled()),id,q.expectedVersion());if(n==0){if(count("SELECT count(*) FROM rs_knowledge_point WHERE id=?",id)==0)notFound();throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Knowledge point was changed by another request");}return knowledgePoint(id);}
 public void requireEnabledKnowledgePoint(long id){if(count("SELECT count(*) FROM rs_knowledge_point k JOIN rs_chapter c ON c.id=k.chapter_id WHERE k.id=? AND k.subject_id=1 AND k.enabled AND c.enabled",id)==0)throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_BINDING","Knowledge point does not exist or is disabled");}
 public void requireKnowledgePoint(long id){if(count("SELECT count(*) FROM rs_knowledge_point WHERE id=? AND subject_id=1",id)==0)throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_BINDING","Knowledge point does not belong to the fixed course");}
 private CourseDtos.ChapterView chapter(long id){var x=db.query("SELECT id,code,name,description,sort_order,enabled,version FROM rs_chapter WHERE id=? AND subject_id=1",(r,n)->new CourseDtos.ChapterView(String.valueOf(r.getLong(1)),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getBoolean(6),r.getInt(7)),id);if(x.isEmpty())notFound();return x.get(0);}
 private CourseDtos.KnowledgePointView knowledgePoint(long id){var x=db.query("SELECT id,chapter_id,code,name,description,sort_order,enabled,version FROM rs_knowledge_point WHERE id=? AND subject_id=1",(r,n)->new CourseDtos.KnowledgePointView(String.valueOf(r.getLong(1)),String.valueOf(r.getLong(2)),r.getString(3),r.getString(4),r.getString(5),r.getInt(6),r.getBoolean(7),r.getInt(8)),id);if(x.isEmpty())notFound();return x.get(0);}
 private void requireChapter(long id){if(count("SELECT count(*) FROM rs_chapter WHERE id=? AND subject_id=1",id)==0)throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_BINDING","Chapter does not belong to the fixed course");}
 private int count(String sql,Object... args){Integer x=db.queryForObject(sql,Integer.class,args);return x==null?0:x;}
 private static void validate(String code,String name,Integer sort,boolean knowledgePoint){String pattern=knowledgePoint?"CH[0-9]{2}-KP[0-9]{2}":"CH[0-9]{2}";if(code==null||!code.matches(pattern)||name==null||name.isBlank()||name.length()>120||sort==null||sort<0)validation("Invalid code, name or sortOrder");}
 private static long parseId(String id){try{return Long.parseLong(id);}catch(Exception e){throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_BINDING","Invalid identifier");}}
 private static String text(String s){return s==null?"":s.trim();}
 private static boolean enabled(Boolean b){return b==null||b;}
 private static void validation(String message){throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED",message);}
 private static void notFound(){throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Resource not found");}
}
