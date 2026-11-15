package io.typeforge.operations;

import io.typeforge.shared.DomainException;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class ProblemHandler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ProblemHandler.class);
    @ExceptionHandler(DomainException.class) ResponseEntity<ProblemDetail> domain(DomainException e) { return problem(e.status(),e.code(),e.getMessage()); }
    @ExceptionHandler({ServletRequestBindingException.class,MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> input(Exception e) { return problem(400,"INVALID_INPUT","Request does not match the supported schema"); }
    @ExceptionHandler(AuthenticationException.class) ResponseEntity<ProblemDetail> auth(AuthenticationException e) { return problem(401,"INVALID_CREDENTIALS","Invalid credentials"); }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<ProblemDetail> database(DataAccessException e) { return problem(503,"DATABASE_OPERATION_FAILED","Database operation could not be completed; retry with the same idempotency key"); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<ProblemDetail> uploadSize(MaxUploadSizeExceededException e) {return problem(413,"ASSET_TOO_LARGE","Each asset must be at most 8 MiB");}
    @ExceptionHandler(MultipartException.class) ResponseEntity<ProblemDetail> upload(MultipartException e) {return problem(400,"ASSET_UPLOAD_INVALID","The multipart upload is incomplete or malformed; select the file again");}
    @ExceptionHandler(Exception.class) ResponseEntity<ProblemDetail> unexpected(Exception e) {
        LOG.error("Unhandled request failure trace={} type={}",traceId(),e.getClass().getSimpleName());
        return problem(500,"INTERNAL_ERROR","The request could not be completed; keep your draft and retry with the same idempotency key");
    }
    private ResponseEntity<ProblemDetail> problem(int status,String code,String detail) {
        ProblemDetail problem=ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status),detail);
        problem.setProperty("code",code);problem.setProperty("traceId",traceId());
        var response=ResponseEntity.status(status);
        if(code.equals("AUTH_RATE_LIMITED")) response.header("Retry-After","300");
        if(code.equals("REGISTRATION_RATE_LIMITED")) response.header("Retry-After","3600");
        return response.body(problem);
    }
    private static String traceId() {
        var attributes=RequestContextHolder.getRequestAttributes();
        if(attributes instanceof ServletRequestAttributes servlet && servlet.getRequest().getAttribute("typeforge.traceId") instanceof String id) return id;
        return UUID.randomUUID().toString();
    }
}
