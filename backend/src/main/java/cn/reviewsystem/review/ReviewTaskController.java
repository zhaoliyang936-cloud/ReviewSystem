package cn.reviewsystem.review;

import cn.reviewsystem.practice.AnswerAssetService;
import cn.reviewsystem.practice.PracticeDtos;
import cn.reviewsystem.security.IdentityService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ReviewTaskController {
 private final ReviewTaskService tasks;
 private final AnswerAssetService assets;
 public ReviewTaskController(ReviewTaskService tasks,AnswerAssetService assets){this.tasks=tasks;this.assets=assets;}
 @PostMapping("/api/review/tasks") @ResponseStatus(HttpStatus.CREATED)
 public ReviewTaskService.View create(Authentication auth,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody(required=false) ReviewTaskService.CreateRequest request){return tasks.create(user(auth),key,request);}
 @GetMapping("/api/review/tasks/{id}")
 public ReviewTaskService.View detail(Authentication auth,@PathVariable long id){return tasks.detail(user(auth),id);}
 @PostMapping("/api/review/tasks/{id}/start")
 public ReviewTaskService.View start(Authentication auth,@PathVariable long id){return tasks.start(user(auth),id);}
 @PostMapping("/api/review/tasks/{id}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void cancel(Authentication auth,@PathVariable long id){tasks.cancel(user(auth),id);}
 @PutMapping("/api/review/tasks/{id}/items/{itemId}/draft")
 public ReviewTaskService.DraftView draft(Authentication auth,@PathVariable long id,@PathVariable long itemId,@RequestBody(required=false) ReviewTaskService.DraftRequest request){return tasks.draft(user(auth),id,itemId,request);}
 @PostMapping("/api/review/tasks/{id}/submit")
 public ReviewTaskService.ResultView submit(Authentication auth,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody(required=false) ReviewTaskService.SubmitRequest request){return tasks.submit(user(auth),id,key,request);}
 @GetMapping("/api/review/tasks/{id}/result")
 public ReviewTaskService.ResultView result(Authentication auth,@PathVariable long id){return tasks.result(user(auth),id);}
 @PostMapping(value="/api/review/tasks/{id}/items/{itemId}/assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
 public PracticeDtos.AssetView upload(Authentication auth,@PathVariable long id,@PathVariable long itemId,@RequestPart("file") MultipartFile file){return assets.uploadForTask(user(auth),id,itemId,file);}
 @DeleteMapping("/api/review/tasks/{id}/items/{itemId}/assets/{assetId}") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void deleteAsset(Authentication auth,@PathVariable long id,@PathVariable long itemId,@PathVariable long assetId){assets.deleteForTask(user(auth),id,itemId,assetId);}
 @GetMapping("/api/answer-assets/{assetId}")
 public ResponseEntity<byte[]> readAsset(Authentication auth,@PathVariable long assetId){var data=assets.read(user(auth),assetId);return ResponseEntity.ok().contentType(data.mediaType()).body(data.bytes());}
 private static long user(Authentication auth){return ((IdentityService.User)auth.getPrincipal()).id();}
}
