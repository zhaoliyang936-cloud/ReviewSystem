package cn.reviewsystem.api;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import cn.reviewsystem.practice.ActiveActivityException;
@RestControllerAdvice public class GlobalExceptionHandler {
 @ExceptionHandler(BusinessException.class) ResponseEntity<ApiError> business(BusinessException e) { return ResponseEntity.status(e.status()).body(new ApiError(e.code(),e.getMessage())); }
 @ExceptionHandler(ActiveActivityException.class) ResponseEntity<ActiveActivityException.Body> activeActivity(ActiveActivityException e) { return ResponseEntity.status(HttpStatus.CONFLICT).body(new ActiveActivityException.Body("STATE_CONFLICT",e.getMessage(),e.activityId(),e.kind())); }
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<ApiError> conflict() { return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("STATE_CONFLICT", "Data conflicts with the current state")); }
 @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<ApiError> uploadTooLarge() { return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ApiError("UPLOAD_INVALID", "Image exceeds the 5 MB limit")); }
 @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<ApiError> notFound() { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", "Resource not found")); }
 @ExceptionHandler(HttpRequestMethodNotSupportedException.class) ResponseEntity<ApiError> methodNotAllowed() { return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ApiError("METHOD_NOT_ALLOWED", "Method not allowed")); }
 @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<ApiError> badRequest() { return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "Invalid request body")); }
 @ExceptionHandler(Exception.class) ResponseEntity<ApiError> unexpected() { return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("INTERNAL_ERROR", "Internal server error")); }
}
