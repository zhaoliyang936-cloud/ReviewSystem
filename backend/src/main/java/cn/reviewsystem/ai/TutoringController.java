package cn.reviewsystem.ai;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.security.IdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class TutoringController {
    private final TutoringService service;

    public TutoringController(TutoringService service) {
        this.service = service;
    }

    @PostMapping("/api/review/ai/jobs")
    public ResponseEntity<TutoringService.JobView> createJob(
            Authentication authentication,
            @RequestParam(required = false) String userId,
            @RequestBody(required = false) TutoringService.CreateJobRequest request) {
        rejectUserId(userId);
        TutoringService.CreateResult result = service.createJob(user(authentication), request);
        return ResponseEntity.status(result.httpStatus()).body(result.view());
    }

    @GetMapping("/api/review/ai/jobs/{id}")
    public TutoringService.JobView getJob(
            Authentication authentication,
            @PathVariable long id,
            @RequestParam(required = false) String userId) {
        rejectUserId(userId);
        return service.getJob(user(authentication), id);
    }

    @PostMapping("/api/review/ai/jobs/{id}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public TutoringService.FeedbackView feedback(
            Authentication authentication,
            @PathVariable long id,
            @RequestParam(required = false) String userId,
            @RequestBody(required = false) TutoringService.FeedbackRequest request) {
        rejectUserId(userId);
        if (request != null && request.userId() != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "userId is server controlled");
        }
        return service.submitFeedback(user(authentication), id, request);
    }

    private static long user(Authentication authentication) {
        return ((IdentityService.User) authentication.getPrincipal()).id();
    }

    private static void rejectUserId(String userId) {
        if (userId != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "userId is server controlled");
        }
    }
}
