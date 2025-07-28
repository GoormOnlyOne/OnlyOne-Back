package com.example.onlyone.domain.club.controller;

import com.example.onlyone.domain.club.dto.request.ClubCreateRequestDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.service.ClubService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Club")
@RequiredArgsConstructor
@RequestMapping("/clubs")
public class ClubController {

    private final ClubService clubService;

    @Operation(summary = "모임 생성", description = "모임을 생성합니다.")
    @PostMapping
    public ResponseEntity<?> createClub(@RequestBody @Valid ClubCreateRequestDto requestDto) {
        clubService.createClub(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }


}
