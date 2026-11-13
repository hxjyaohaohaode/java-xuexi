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

@RestControllerAdvice
public class ProblemHandler {
    @ExceptionHandler(DomainException.class) ResponseEntity<ProblemDetail> domain(DomainException e) { return problem(e.status(),e.code(),e.getMessage()); }
    @ExceptionHandler({ServletRequestBindingException.class,MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> input(Exception e) { return problem(400,"INVALID_INPUT","Request does not match the supported schema"); }
    @ExceptionHandler(AuthenticationException.class) ResponseEntity<ProblemDetail> auth(AuthenticationException e) { return problem(401,"INVALID_CREDENTIALS","Invalid credentials"); }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<ProblemDetail> database(DataAccessException e) { return problem(503,"DATABASE_OPERATION_FAILED","Database operation could not be completed; retry with the same idempotency key"); }
    private ResponseEntity<ProblemDetail> problem(int status,String code,String detail) {
        ProblemDetail problem=ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status),detail);
        problem.setProperty("code",code);problem.setProperty("traceId",UUID.randomUUID().toString());
        return ResponseEntity.status(status).body(problem);
    }
}
