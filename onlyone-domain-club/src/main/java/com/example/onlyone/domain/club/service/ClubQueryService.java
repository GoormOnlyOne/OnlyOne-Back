package com.example.onlyone.domain.club.service;

import com.example.onlyone.domain.club.dto.response.ClubDetailResponseDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ClubQueryService {
    private final ClubRepository clubRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;

    public ClubDetailResponseDto getClubDetail(Long clubId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        User user = userService.getCurrentUser();
        Optional<UserClub> userClub = userClubRepository.findByUserAndClub(user, club);
        int userCount = userClubRepository.countByClub_ClubId(club.getClubId());
        if (userClub.isEmpty()) {
            return ClubDetailResponseDto.from(club, userCount, ClubRole.GUEST);
        }
        return ClubDetailResponseDto.from(club, userCount, userClub.get().getClubRole());
    }
}
