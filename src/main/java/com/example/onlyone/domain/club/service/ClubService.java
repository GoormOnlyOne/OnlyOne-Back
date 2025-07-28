package com.example.onlyone.domain.club.service;

import com.example.onlyone.domain.club.dto.request.ClubCreateRequestDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class ClubService {
    private final ClubRepository clubRepository;
    private final InterestRepository interestRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;

    /* 모임 생성*/
    public void createClub(@Valid ClubCreateRequestDto requestDto) {
        Interest interest = interestRepository.findByCategory(Category.from(requestDto.getCategory()))
                .orElseThrow(() -> new CustomException(ErrorCode.INTEREST_NOT_FOUND));
        Club club = requestDto.toEntity(interest);
        clubRepository.save(club);
        User user = userService.getCurrentUser();
        UserClub userClub = UserClub.builder()
                .user(user)
                .club(club)
                .clubRole(ClubRole.LEADER)
                .build();
        userClubRepository.save(userClub);
    }

}
