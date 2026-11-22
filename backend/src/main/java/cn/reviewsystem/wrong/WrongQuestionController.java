package cn.reviewsystem.wrong;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.practice.PracticeDtos;
import cn.reviewsystem.question.PageResult;
import cn.reviewsystem.security.IdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WrongQuestionController {

    private final WrongQuestionService service;

    public WrongQuestionController(WrongQuestionService service) {
        this.service = service;
    }

    @GetMapping("/api/wrong-questions")
    public PageResult<WrongQuestionDtos.ListItem> list(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String knowledgeId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String userId
    ) {
        rejectUserId(userId);
        return service.list(user(authentication), status, knowledgeId, type, source, page, size);
    }

    @GetMapping("/api/wrong-questions/{id}")
    public WrongQuestionDtos.DetailView detail(
            Authentication authentication,
            @PathVariable long id,
            @RequestParam(required = false) String userId
    ) {
        rejectUserId(userId);
        return service.detail(user(authentication), id);
    }

    @PostMapping("/api/wrong-questions/{id}/redo")
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeDtos.PracticeView redo(
            Authentication authentication,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) WrongQuestionDtos.RedoRequest request
    ) {
        return service.redo(user(authentication), id, idempotencyKey, request);
    }

    @PostMapping("/api/wrong-questions/{id}/feedback")
    public WrongQuestionDtos.FeedbackView feedback(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody(required = false) WrongQuestionDtos.FeedbackRequest request
    ) {
        return service.feedback(user(authentication), id, request);
    }

    @PostMapping("/api/wrong-questions/{id}/variant")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WrongQuestionDtos.VariantJobView variant(
            Authentication authentication,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(required = false) String userId
    ) {
        rejectUserId(userId);
        return service.createVariant(user(authentication), id, idempotencyKey);
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
