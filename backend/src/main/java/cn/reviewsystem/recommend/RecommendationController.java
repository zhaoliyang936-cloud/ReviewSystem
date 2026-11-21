package cn.reviewsystem.recommend;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.security.IdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class RecommendationController {
 private final RecommendationService service;private final VersionedRecommendationConfig cfg;
 public RecommendationController(RecommendationService service,VersionedRecommendationConfig cfg){this.service=service;this.cfg=cfg;}
 @GetMapping("/api/review/recommendations")
 public RecommendationDtos.Response recommendations(Authentication auth,@RequestParam(defaultValue="10") int count){
  if(count<1||count>cfg.publicMaxCount())throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","count must be between 1 and "+cfg.publicMaxCount());
  return service.recommend(((IdentityService.User)auth.getPrincipal()).id(),count);
 }
}
