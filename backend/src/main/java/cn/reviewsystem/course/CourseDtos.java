package cn.reviewsystem.course;

import java.util.List;

public final class CourseDtos {
 private CourseDtos(){}
 public record CourseView(String id,String code,String name,List<ChapterView> chapters){}
 public record ChapterView(String id,String code,String name,String description,int sortOrder,boolean enabled,int version){}
 public record ChapterRequest(String code,String name,String description,Integer sortOrder,Boolean enabled,Integer expectedVersion){}
 public record KnowledgePointView(String id,String chapterId,String code,String name,String description,int sortOrder,boolean enabled,int version){}
 public record KnowledgePointRequest(String chapterId,String code,String name,String description,Integer sortOrder,Boolean enabled,Integer expectedVersion){}
}
