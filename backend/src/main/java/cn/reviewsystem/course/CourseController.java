package cn.reviewsystem.course;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class CourseController {
 private final CourseService service;
 public CourseController(CourseService service){this.service=service;}
 @GetMapping("/api/course") CourseDtos.CourseView course(){return service.course();}
 @GetMapping("/api/admin/chapters") List<CourseDtos.ChapterView> chapters(){return service.chapters();}
 @PostMapping("/api/admin/chapters") @ResponseStatus(HttpStatus.CREATED) CourseDtos.ChapterView createChapter(@RequestBody CourseDtos.ChapterRequest q){return service.createChapter(q);}
 @PutMapping("/api/admin/chapters/{id}") CourseDtos.ChapterView updateChapter(@PathVariable long id,@RequestBody CourseDtos.ChapterRequest q){return service.updateChapter(id,q);}
 @GetMapping("/api/admin/knowledge-points") List<CourseDtos.KnowledgePointView> knowledgePoints(){return service.knowledgePoints();}
 @GetMapping("/api/knowledge-points") List<CourseDtos.KnowledgePointView> studentKnowledgePoints(){return service.enabledKnowledgePoints();}
 @PostMapping("/api/admin/knowledge-points") @ResponseStatus(HttpStatus.CREATED) CourseDtos.KnowledgePointView createKnowledgePoint(@RequestBody CourseDtos.KnowledgePointRequest q){return service.createKnowledgePoint(q);}
 @PutMapping("/api/admin/knowledge-points/{id}") CourseDtos.KnowledgePointView updateKnowledgePoint(@PathVariable long id,@RequestBody CourseDtos.KnowledgePointRequest q){return service.updateKnowledgePoint(id,q);}
}
