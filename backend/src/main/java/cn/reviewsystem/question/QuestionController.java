package cn.reviewsystem.question;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class QuestionController {
 private final QuestionService service;
 public QuestionController(QuestionService service){this.service=service;}
 @GetMapping("/api/admin/questions") PageResult<QuestionListItem> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String origin,@RequestParam(required=false) String type,@RequestParam(required=false) String status,@RequestParam(required=false) String chapterId,@RequestParam(required=false) String knowledgeId,@RequestParam(required=false) String keyword){return service.list(page,size,origin,type,status,chapterId,knowledgeId,keyword);}
 @GetMapping("/api/admin/questions/{id}") AdminQuestionDetail detail(@PathVariable long id){return service.detail(id);}
 @GetMapping("/api/admin/questions/{id}/versions") java.util.List<AdminQuestionDetail.VersionView> versions(@PathVariable long id){return service.versions(id);}
 @PostMapping("/api/admin/questions") @ResponseStatus(HttpStatus.CREATED) AdminQuestionDetail create(@RequestBody QuestionWriteRequest q){return service.create(q);}
 @PutMapping("/api/admin/questions/{id}") AdminQuestionDetail update(@PathVariable long id,@RequestBody QuestionWriteRequest q){return service.update(id,q);}
 @PostMapping("/api/admin/questions/{id}/suspend") AdminQuestionDetail suspend(@PathVariable long id,@RequestBody StatusRequest q){return service.status(id,true,q.reason(),q.expectedVersion());}
 @PostMapping("/api/admin/questions/{id}/restore") AdminQuestionDetail restore(@PathVariable long id,@RequestBody StatusRequest q){return service.status(id,false,null,q.expectedVersion());}
 @GetMapping("/api/admin/question-coverage") CoverageReport coverage(){return service.coverage();}
 @GetMapping("/api/questions/{id}") StudentQuestion student(@PathVariable long id){return service.student(id);}
 public record StatusRequest(String reason,Integer expectedVersion){}
}
