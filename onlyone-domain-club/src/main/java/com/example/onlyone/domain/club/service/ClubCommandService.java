package com.example.onlyone.domain.club.service;

import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.ClubUpdateCommand;
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
import com.example.onlyone.common.event.ClubCreatedEvent;
import com.example.onlyone.common.event.ClubLeftEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ClubCommandService {
    private final ClubRepository clubRepository;
    private final InterestRepository interestRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;
    private final CacheManager cacheManager;

    public ClubCreateResponseDto createClub(ClubRequestDto requestDto) {
        Interest interest = interestRepository.findByCategory(Category.from(requestDto.category()))
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
        clubRepository.incrementMemberCount(club.getClubId());

        eventPublisher.publishEvent(new ClubCreatedEvent(
                club.getClubId(),
                user.getUserId(),
                club.getName()
        ));

        evictAccessibleClubIds(user.getUserId());
        log.info("모임 생성: clubId={}, userId={}", club.getClubId(), user.getUserId());
        return new ClubCreateResponseDto(club.getClubId());
    }

    public ClubCreateResponseDto updateClub(long clubId, ClubRequestDto requestDto) {
        Club club = findClubOrThrow(clubId);
        Interest interest = interestRepository.findByCategory(Category.from(requestDto.category()))
                .orElseThrow(() -> new CustomException(ErrorCode.INTEREST_NOT_FOUND));
        User user = userService.getCurrentUser();
        UserClub userClub = userClubRepository.findByUserAndClub(user, club)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_CLUB_NOT_FOUND));
        if (userClub.getClubRole() != ClubRole.LEADER) {
            throw new CustomException(ErrorCode.LEADER_ONLY_CLUB_MODIFY);
        }
        club.update(new ClubUpdateCommand(
                requestDto.name(),
                requestDto.userLimit(),
                requestDto.description(),
                requestDto.clubImage(),
                requestDto.city(),
                requestDto.district(),
                interest
        ));

        return new ClubCreateResponseDto(club.getClubId());
    }

    public void joinClub(Long clubId) {
        Club club = findClubOrThrow(clubId);
        int userCount = userClubRepository.countByClub_ClubId(club.getClubId());
        if (userCount >= club.getUserLimit()) {
            throw new CustomException(ErrorCode.CLUB_NOT_ENTER);
        }
        User user = userService.getCurrentUser();
        if (userClubRepository.existsByUser_UserIdAndClub_ClubId(user.getUserId(), clubId)) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_CLUB);
        }
        UserClub userClub = UserClub.builder()
                .user(user)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();
        try {
            userClubRepository.save(userClub);
            userClubRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_CLUB);
        }
        clubRepository.incrementMemberCount(club.getClubId());
        evictAccessibleClubIds(user.getUserId());
        log.info("모임 가입: clubId={}, userId={}", clubId, user.getUserId());
    }

    public void leaveClub(Long clubId) {
        User user = userService.getCurrentUser();
        Club club = findClubOrThrow(clubId);
        UserClub userClub = userClubRepository.findByUserAndClub(user, club)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_CLUB_NOT_FOUND));
        if (userClub.getClubRole() == ClubRole.GUEST) {
            throw new CustomException(ErrorCode.CLUB_NOT_LEAVE);
        }
        if (userClub.getClubRole() == ClubRole.LEADER) {
            throw new CustomException(ErrorCode.CLUB_LEADER_NOT_LEAVE);
        }
        userClubRepository.delete(userClub);
        clubRepository.decrementMemberCount(club.getClubId());
        evictAccessibleClubIds(user.getUserId());
        eventPublisher.publishEvent(new ClubLeftEvent(clubId, user.getUserId()));
        log.info("모임 탈퇴: clubId={}, userId={}", clubId, user.getUserId());
    }

    private void evictAccessibleClubIds(Long userId) {
        var cache = cacheManager.getCache("accessibleClubIds");
        if (cache != null) cache.evict(userId);
    }

    private Club findClubOrThrow(Long clubId) {
        return clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
    }
}
