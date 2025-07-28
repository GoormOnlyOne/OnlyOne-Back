package com.example.onlyone.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Log4j2
public class GlobalExceptionHandler {

    @ExceptionHandler({CustomException.class})
    protected ResponseEntity<ErrorDto> handleCustomException(CustomException e,
                                                             HttpServletRequest request) {
        ErrorDto errorDto = ErrorDto.builder()
                .timestamp(LocalDateTime.now().toString())
                .status(e.getErrorCode().getStatus())
                .message(e.getErrorCode().getMessage())
                .path(request.getRequestURI())
                .build();
        return new ResponseEntity<>(errorDto, HttpStatusCode.valueOf(e.getErrorCode().getStatus()));
    }

    @ExceptionHandler({BindException.class, MethodArgumentNotValidException.class})
    protected ResponseEntity<ErrorDto> handleValidationException(Exception e, HttpServletRequest request) {
        BindingResult bindingResult = null;

        if (e instanceof BindException) {
            bindingResult = ((BindException) e).getBindingResult();
        } else if (e instanceof MethodArgumentNotValidException) {
            bindingResult = ((MethodArgumentNotValidException) e).getBindingResult();
        }
        List<String> errorMessages = bindingResult.getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.toList());

        ErrorDto errorDto = ErrorDto.builder()
                .timestamp(LocalDateTime.now().toString())
                .message(String.join(", ", errorMessages))
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(errorDto, HttpStatus.BAD_REQUEST);
    }


}
