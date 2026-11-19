package cn.reviewsystem.question;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuestionImportService {
 private final ObjectMapper json;private final QuestionValidator validator;private final QuestionService questions;private final JdbcTemplate db;private final TransactionTemplate transactions;
 public QuestionImportService(ObjectMapper json,QuestionValidator validator,QuestionService questions,JdbcTemplate db,TransactionTemplate transactions){this.json=json;this.validator=validator;this.questions=questions;this.db=db;this.transactions=transactions;}
 public ImportReport importManifest(ImportManifest manifest){
  List<ItemResult> results=new ArrayList<>();if(manifest==null||manifest.sourceLabel()==null||manifest.sourceLabel().isBlank()||manifest.questions()==null)throw new IllegalArgumentException("sourceLabel and questions are required");
  Set<String> keys=new HashSet<>();Map<Integer,String> invalid=new HashMap<>();
  for(int i=0;i<manifest.questions().size();i++){ImportQuestion item=manifest.questions().get(i);try{if(item.sourceQuestionNo()==null||item.sourceQuestionNo().isBlank())throw new IllegalArgumentException("sourceQuestionNo is required");if(item.origin()!=null)throw new IllegalArgumentException("origin is server controlled and must not be supplied");String key=item.sourceQuestionNo()+"\u0000"+item.type();if(!keys.add(key))throw new IllegalArgumentException("duplicate idempotency key in manifest");validator.validate(item.request());}catch(Exception e){invalid.put(i,safe(e));}}
  int imported=0,skipped=0,failed=0;
  for(int i=0;i<manifest.questions().size();i++){ImportQuestion item=manifest.questions().get(i);if(invalid.containsKey(i)){failed++;results.add(new ItemResult(item.sourceQuestionNo(),item.type(),"failed",invalid.get(i),null));continue;}
   try{ItemResult result=transactions.execute(status->{Integer exists=db.queryForObject("SELECT count(*) FROM rs_question_import_item WHERE source_label=? AND source_question_no=? AND question_type=?",Integer.class,manifest.sourceLabel().trim(),item.sourceQuestionNo().trim(),item.type());if(exists!=null&&exists>0)return new ItemResult(item.sourceQuestionNo(),item.type(),"skipped","already imported",null);AdminQuestionDetail created=questions.create(item.request());db.update("INSERT INTO rs_question_import_item(source_label,source_question_no,question_type,question_id) VALUES (?,?,?,?)",manifest.sourceLabel().trim(),item.sourceQuestionNo().trim(),item.type(),Long.parseLong(created.id()));return new ItemResult(item.sourceQuestionNo(),item.type(),"imported",null,created.id());});results.add(result);if(result.status().equals("imported"))imported++;else skipped++;}
   catch(Exception e){failed++;results.add(new ItemResult(item.sourceQuestionNo(),item.type(),"failed",safe(e),null));}
  }
  return new ImportReport(manifest.sourceLabel().trim(),manifest.questions().size(),imported,skipped,failed,results);
 }
 public ImportReport importFile(Path manifest,Path report)throws IOException {ImportReport result=importManifest(json.readValue(manifest.toFile(),ImportManifest.class));Files.writeString(report,json.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);return result;}
 private static String safe(Exception e){if(!(e instanceof cn.reviewsystem.api.BusinessException)&&!(e instanceof IllegalArgumentException))return "database write failed";String message=e.getMessage();if(message==null||message.isBlank())return "validation failed";return message.length()>300?message.substring(0,300):message;}
 public record ImportManifest(String sourceLabel,List<ImportQuestion> questions){}
 public record ImportQuestion(String sourceQuestionNo,String knowledgePointId,String type,JsonNode content,JsonNode answer,JsonNode rubric,String explanation,String sourceRef,String origin,Double maxScore){QuestionWriteRequest request(){return new QuestionWriteRequest(knowledgePointId,type,content,answer,rubric,explanation,sourceRef,null,maxScore,null);}}
 public record ItemResult(String sourceQuestionNo,String type,String status,String message,String questionId){}
 public record ImportReport(String sourceLabel,int total,int imported,int skipped,int failed,List<ItemResult> items){public boolean successful(){return failed==0;}}
}
