package cn.reviewsystem.api;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
@RestControllerAdvice public class GlobalExceptionHandler {
 @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<ApiError> notFound() { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", "Resource not found")); }
 @ExceptionHandler(HttpRequestMethodNotSupportedException.class) ResponseEntity<ApiError> methodNotAllowed() { return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ApiError("METHOD_NOT_ALLOWED", "Method not allowed")); }
 @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<ApiError> badRequest() { return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "Invalid request body")); }
 @ExceptionHandler(Exception.class) ResponseEntity<ApiError> unexpected() { return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("INTERNAL_ERROR", "Internal server error")); }
}
