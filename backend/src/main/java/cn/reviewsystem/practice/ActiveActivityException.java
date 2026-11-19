package cn.reviewsystem.practice;

public class ActiveActivityException extends RuntimeException {
 private final String activityId;private final String kind;
 public ActiveActivityException(String activityId,String kind){super("An active learning activity already exists");this.activityId=activityId;this.kind=kind;}
 public String activityId(){return activityId;}public String kind(){return kind;}
 public record Body(String code,String message,String activityId,String kind){}
}
