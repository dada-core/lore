package com.dadacore.lore.web;

import java.util.Map;

import com.dadacore.lore.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every API error into {@code application/problem+json}, with a stable {@code code} that
 * the interface translates.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final Map<Integer, String> CODES = Map.of(
            400, "invalid_request",
            401, "unauthenticated",
            403, "forbidden",
            404, "not_found",
            405, "method_not_allowed",
            406, "not_acceptable",
            409, "conflict",
            413, "too_large",
            415, "unsupported_media_type");

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(exception.status());
        problem.setProperty("code", exception.code());
        exception.properties().forEach(problem::setProperty);
        return problem(problem);
    }

    /** A malformed identifier in the address designates nothing: same answer as a missing one. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setProperty("code", "not_found");
        return problem(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        log.error("Unexpected error", exception);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setProperty("code", "server_error");
        return problem(problem);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(exception, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("code", CODES.getOrDefault(status.value(), "error"));
            problem.setDetail(null);
        }
        return response;
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
