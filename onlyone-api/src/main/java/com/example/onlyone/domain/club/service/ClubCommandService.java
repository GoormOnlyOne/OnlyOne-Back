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
import com.example.onlyone.domain.club.exception.ClubErrorCode;
import com.example.onlyone.domain.interest.exception.InterestErrorCode;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.common.event.ClubCreatedEvent;
import com.example.onlyone.common.event.ClubLeftEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClubCommandService {

    private static final String ACCESSIBLE_CLUB_IDS_CACHE = "accessibleClubIds";

    private final ClubRepository clubRepository;
    private final InterestRepository interestRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;
    private final CacheManager cacheManager;
    private final TransactionTemplate transactionTemplate;

    @Transactional
    public ClubCreateResponseDto createClub(ClubRequestDto requestDto) {
        Interest interest = findInterestOrThrow(requestDto.category());
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
                club.getClubId(), user.getUserId(), club.getName()));

        evictAccessibleClubIds(user.getUserId());
        log.info("모임 생성: clubId={}, userId={}", club.getClubId(), user.getUserId());
        return new ClubCreateResponseDto(club.getClubId());
    }

    @Transactional
    public ClubCreateResponseDto updateClub(long clubId, ClubRequestDto requestDto) {
        Club club = findClubOrThrow(clubId);
        Interest interest = findInterestOrThrow(requestDto.category());
        User user = userService.getCurrentUser();
        UserClub userClub = userClubRepository.findByUserAndClub(user, club)
                .orElseThrow(() -> new CustomException(ClubErrorCode.USER_CLUB_NOT_FOUND));
        if (userClub.getClubRole() != ClubRole.LEADER) {
            throw new CustomException(ClubErrorCode.LEADER_ONLY_CLUB_MODIFY);
        }
        club.update(new ClubUpdateCommand(
                requestDto.name(), requestDto.userLimit(), requestDto.description(),
                requestDto.clubImage(), requestDto.city(), requestDto.district(), interest));

        return new ClubCreateResponseDto(club.getClubId());
    }

    /**
     * 모임 가입.
     * TX1: 검증 + INSERT user_club (유저·모임 행만 lock)
     * TX2: member_count 증가 (club 행 lock — 별도 트랜잭션으로 분리하여 데드락 방지)
     */
    public void joinClub(Long clubId) {
        Long userId = transactionTemplate.execute(status -> {
            Club club = findClubOrThrow(clubId);
            validateCapacity(club);
            User user = userService.getCurrentUser();
            validateNotAlreadyJoined(user.getUserId(), clubId);
            saveUserClub(user, club, ClubRole.MEMBER);
            return user.getUserId();
        });

        incrementMemberCountSafely(clubId);
        evictAccessibleClubIds(userId);
        log.info("모임 가입: clubId={}, userId={}", clubId, userId);
    }

    /**
     * 모임 탈퇴.
     * TX1: 검증 + DELETE user_club
     * TX2: member_count 감소 (별도 트랜잭션)
     * 이벤트는 TX1 내에서 발행하여 @TransactionalEventListener(AFTER_COMMIT)가 정상 동작하도록 보장.
     */
    public void leaveClub(Long clubId) {
        Long userId = transactionTemplate.execute(status -> {
            User user = userService.getCurrentUser();
            Club club = findClubOrThrow(clubId);
            UserClub userClub = userClubRepository.findByUserAndClub(user, club)
                    .orElseThrow(() -> new CustomException(ClubErrorCode.USER_CLUB_NOT_FOUND));
            validateLeavePermission(userClub);
            userClubRepository.delete(userClub);
            eventPublisher.publishEvent(new ClubLeftEvent(clubId, user.getUserId()));
            return user.getUserId();
        });

        decrementMemberCountSafely(clubId);
        evictAccessibleClubIds(userId);
        log.info("모임 탈퇴: clubId={}, userId={}", clubId, userId);
    }

    // ── 검증 헬퍼 ──

    private void validateCapacity(Club club) {
        int userCount = userClubRepository.countByClub_ClubId(club.getClubId());
        if (userCount >= club.getUserLimit()) {
            throw new CustomException(ClubErrorCode.CLUB_NOT_ENTER);
        }
    }

    private void validateNotAlreadyJoined(Long userId, Long clubId) {
        if (userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId)) {
            throw new CustomException(ClubErrorCode.ALREADY_JOINED_CLUB);
        }
    }

    private void validateLeavePermission(UserClub userClub) {
        if (userClub.getClubRole() == ClubRole.GUEST) {
            throw new CustomException(ClubErrorCode.CLUB_NOT_LEAVE);
        }
        if (userClub.getClubRole() == ClubRole.LEADER) {
            throw new CustomException(ClubErrorCode.CLUB_LEADER_NOT_LEAVE);
        }
    }

    private void saveUserClub(User user, Club club, ClubRole role) {
        UserClub userClub = UserClub.builder()
                .user(user).club(club).clubRole(role).build();
        try {
            userClubRepository.save(userClub);
            userClubRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ClubErrorCode.ALREADY_JOINED_CLUB);
        }
    }

    // ── 카운트 헬퍼 (별도 트랜잭션) ──

    private void incrementMemberCountSafely(Long clubId) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    clubRepository.incrementMemberCount(clubId));
        } catch (Exception e) {
            log.warn("멤버 카운트 증가 실패 (가입은 정상): clubId={}, err={}", clubId, e.getMessage());
        }
    }

    private void decrementMemberCountSafely(Long clubId) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    clubRepository.decrementMemberCount(clubId));
        } catch (Exception e) {
            log.warn("멤버 카운트 감소 실패 (탈퇴는 정상): clubId={}, err={}", clubId, e.getMessage());
        }
    }

    // ── 공통 헬퍼 ──

    private Club findClubOrThrow(Long clubId) {
        return clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ClubErrorCode.CLUB_NOT_FOUND));
    }

    private Interest findInterestOrThrow(String category) {
        return interestRepository.findByCategory(Category.from(category))
                .orElseThrow(() -> new CustomException(InterestErrorCode.INTEREST_NOT_FOUND));
    }

    private void evictAccessibleClubIds(Long userId) {
        var cache = cacheManager.getCache(ACCESSIBLE_CLUB_IDS_CACHE);
        if (cache != null) {
            cache.evict(userId);
        }
    }
}
