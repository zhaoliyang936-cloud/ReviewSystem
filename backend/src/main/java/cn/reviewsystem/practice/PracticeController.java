package cn.reviewsystem.practice;

import cn.reviewsystem.question.PageResult;
import cn.reviewsystem.security.IdentityService;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class PracticeController {
    private final PracticeService practices;
    private final AnswerAssetService assets;

    public PracticeController(PracticeService practices, AnswerAssetService assets) {
        this.practices = practices;
        this.assets = assets;
    }

    @PostMapping("/api/practices")
    ResponseEntity<PracticeDtos.PracticeView> create(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestParam(required = false) String sourceMode,
            @RequestBody PracticeDtos.CreateRequest request) {
        reject(sourceMode);
        PracticeDtos.PracticeView view = practices.create(user(authentication), key, request);
        HttpStatus status = "PREPARING".equals(view.status()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(view);
    }

    @GetMapping("/api/practices/history")
    PageResult<PracticeDtos.HistoryItem> history(Authentication authentication, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String sourceMode) {
        return practices.history(user(authentication), page, size, sourceMode);
    }

    @GetMapping("/api/practices/preparation/{jobId}")
    Map<String, Object> preparationStatus(Authentication authentication, @PathVariable long jobId, @RequestParam(required = false) String sourceMode) {
        reject(sourceMode);
        return practices.preparationStatus(user(authentication), jobId);
    }

    @GetMapping("/api/practices/{id}")
    PracticeDtos.PracticeView detail(Authentication authentication, @PathVariable long id, @RequestParam(required = false) String sourceMode) {
        return practices.detail(user(authentication), id, sourceMode);
    }

    @PostMapping("/api/practices/{id}/start")
    PracticeDtos.PracticeView start(Authentication authentication, @PathVariable long id, @RequestParam(required = false) String sourceMode) {
        return practices.start(user(authentication), id, sourceMode);
    }

    @PostMapping("/api/practices/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(Authentication authentication, @PathVariable long id, @RequestParam(required = false) String sourceMode) {
        practices.cancel(user(authentication), id, sourceMode);
    }

    @PutMapping("/api/practices/{id}/items/{itemId}/draft")
    PracticeDtos.DraftView draft(Authentication authentication, @PathVariable long id, @PathVariable long itemId, @RequestParam(required = false) String sourceMode, @RequestBody PracticeDtos.DraftRequest request) {
        reject(sourceMode);
        return practices.draft(user(authentication), id, itemId, request);
    }

    @PostMapping("/api/practices/{id}/submit")
    PracticeDtos.ResultView submit(Authentication authentication, @PathVariable long id, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestParam(required = false) String sourceMode, @RequestBody PracticeDtos.SubmitRequest request) {
        reject(sourceMode);
        return practices.submit(user(authentication), id, key, request);
    }

    @PostMapping("/api/practices/{id}/items/{itemId}/retry-review")
    PracticeDtos.ResultView retryReview(Authentication authentication, @PathVariable long id, @PathVariable long itemId, @RequestParam(required = false) String sourceMode) {
        reject(sourceMode);
        return practices.retryReview(user(authentication), id, itemId);
    }

    @GetMapping("/api/practices/{id}/result")
    PracticeDtos.ResultView result(Authentication authentication, @PathVariable long id, @RequestParam(required = false) String sourceMode) {
        return practices.result(user(authentication), id, sourceMode);
    }

    @PostMapping(value = "/api/practices/{id}/items/{itemId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    PracticeDtos.AssetView upload(Authentication authentication, @PathVariable long id, @PathVariable long itemId, @RequestParam(required = false) String sourceMode, @RequestPart("file") MultipartFile file) {
        reject(sourceMode);
        return assets.upload(user(authentication), id, itemId, file);
    }

    @DeleteMapping("/api/practices/{id}/items/{itemId}/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAsset(Authentication authentication, @PathVariable long id, @PathVariable long itemId, @PathVariable long assetId, @RequestParam(required = false) String sourceMode) {
        reject(sourceMode);
        assets.delete(user(authentication), id, itemId, assetId);
    }

    @GetMapping("/api/practice-assets/{assetId}")
    ResponseEntity<byte[]> readAsset(Authentication authentication, @PathVariable long assetId, @RequestParam(required = false) String sourceMode) {
        reject(sourceMode);
        var data = assets.read(user(authentication), assetId);
        return ResponseEntity.ok().contentType(data.mediaType()).body(data.bytes());
    }

    private static long user(Authentication authentication) {
        return ((IdentityService.User) authentication.getPrincipal()).id();
    }

    private static void reject(String sourceMode) {
        if (sourceMode != null) throw new cn.reviewsystem.api.BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "sourceMode is server controlled");
    }
}
