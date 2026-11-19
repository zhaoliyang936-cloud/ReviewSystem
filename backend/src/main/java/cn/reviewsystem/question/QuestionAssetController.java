package cn.reviewsystem.question;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class QuestionAssetController {
 private final QuestionAssetService service;
 public QuestionAssetController(QuestionAssetService service){this.service=service;}
 @PostMapping(value="/api/admin/questions/{id}/assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) AdminQuestionDetail.AssetView upload(@PathVariable long id,@RequestPart("file") MultipartFile file){return service.upload(id,file);}
 @DeleteMapping("/api/admin/question-assets/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@PathVariable long id){service.deleteHistorical(id);}
 @GetMapping("/api/question-assets/{id}") ResponseEntity<byte[]> read(@PathVariable long id){var asset=service.read(id);return ResponseEntity.ok().contentType(asset.mediaType()).cacheControl(CacheControl.noStore()).body(asset.bytes());}
}
