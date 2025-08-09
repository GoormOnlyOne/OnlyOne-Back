package com.example.onlyone.domain.user.controller;

import com.example.onlyone.domain.user.dto.response.MyPageResponse;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.common.CommonResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Log4j2
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {
    
    private final UserService userService;
    
    @GetMapping("/mypage")
    public ResponseEntity<?> getMyPage() {
        MyPageResponse myPageResponse = userService.getMyPage();
        return ResponseEntity.ok(CommonResponse.success(myPageResponse));
    }
}
