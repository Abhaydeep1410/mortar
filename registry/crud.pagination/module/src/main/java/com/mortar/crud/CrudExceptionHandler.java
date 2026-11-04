package com.mortar.crud;

import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns CRUD errors into the contract's JSON body. Applies only to {@link CrudController} subclasses. */
@RestControllerAdvice(assignableTypes = CrudController.class)
public class CrudExceptionHandler {

  @ExceptionHandler(InvalidListQueryException.class)
  ResponseEntity<ApiError> badQuery(InvalidListQueryException e) {
    return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .sorted()
            .collect(Collectors.joining("; "));
    return ResponseEntity.badRequest().body(new ApiError(message.isEmpty() ? "validation failed" : message));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(new ApiError("request body is missing or not valid JSON"));
  }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ApiError> notFound(NotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(e.getMessage()));
  }
}
