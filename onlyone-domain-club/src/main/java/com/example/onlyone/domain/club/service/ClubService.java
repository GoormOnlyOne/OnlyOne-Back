package com.example.onlyone.domain.club.service;
// TODO: 순환 의존성 방지 - 이벤트 기반으로 변경 필요
// import com.example.onlyone.domain.chat.entity.ChatRole;
// import com.example.onlyone.domain.chat.entity.ChatRoom;
// import com.example.onlyone.domain.chat.entity.Type;
// import com.example.onlyone.domain.chat.entity.UserChatRoom;
// import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
// import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.dto.response.ClubDetailResponseDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
// import com.example.onlyone.domain.feed.repository.FeedRepository;  // TODO: 순환 의존성 방지
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
// import com.example.onlyone.domain.search.service.ClubElasticsearchService;  // TODO: 순환 의존성 방지
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.common.event.ClubCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Log4j2
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ClubService {
    private final ClubRepository clubRepository;
    private final InterestRepository interestRepository;
    private final UserClubRepository userClubRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;  // 이벤트 발행

    /* 모임 생성*/
    @Transactional
    public ClubCreateResponseDto createClub(ClubRequestDto requestDto) {
        Interest interest = interestRepository.findByCategory(Category.from(requestDto.category()))
                .orElseThrow(() -> new CustomException(ErrorCode.INTEREST_NOT_FOUND));
        Club club = requestDto.toEntity(interest);
        clubRepository.save(club);
        // 모임장의 UserClub 생성
        User user = userService.getCurrentUser();
        UserClub userClub = UserClub.builder()
                .user(user)
                .club(club)
                .clubRole(ClubRole.LEADER)
                .build();
        userClubRepository.save(userClub);
        clubRepository.incrementMemberCount(club.getClubId());

        // 이벤트 발행: Chat 도메인에서 ChatRoom 생성, Search 도메인에서 ES 인덱싱
        eventPublisher.publishEvent(new ClubCreatedEvent(
                club.getClubId(),
                user.getUserId(),
                club.getName()
        ));

        return new ClubCreateResponseDto(club.getClubId());
    }

    /* 모임 수정*/
    @Transactional
    public ClubCreateResponseDto updateClub(long clubId, ClubRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Interest interest = interestRepository.findByCategory(Category.from(requestDto.category()))
                .orElseThrow(() -> new CustomException(ErrorCode.INTEREST_NOT_FOUND));
        User user = userService.getCurrentUser();
        UserClub userClub = userClubRepository.findByUserAndClub(user,club)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_CLUB_NOT_FOUND));
        if (userClub.getClubRole() != ClubRole.LEADER) {
            throw new CustomException(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE);
        }
        club.update(
                requestDto.name(),
                requestDto.userLimit(),
                requestDto.description(),
                requestDto.clubImage(),
                requestDto.city(),
                requestDto.district(),
                interest
        );

        // TODO: 이벤트 기반으로 변경 - ClubUpdatedEvent 발행하여 ES 업데이트
        // ES 업데이트 (비동기)
        // clubElasticsearchService.updateClub(club);

        return new ClubCreateResponseDto(club.getClubId());
    }

    /* 모임 상세 조회*/
    public ClubDetailResponseDto getClubDetail(Long clubId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        User user = userService.getCurrentUser();
        Optional<UserClub> userClub = userClubRepository.findByUserAndClub(user,club);
        int userCount = userClubRepository.countByClub_ClubId(club.getClubId());
        if (userClub.isEmpty()) {
            return ClubDetailResponseDto.from(club,userCount,ClubRole.GUEST);
        }
        return ClubDetailResponseDto.from(club,userCount,userClub.get().getClubRole());
    }

    /* 모임 가입*/
    @Transactional
    public void joinClub(Long clubId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        int userCount =  userClubRepository.countByClub_ClubId(club.getClubId());
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
    }

    /* 모임 탈퇴*/
    @Transactional
    public void leaveClub(Long clubId) {
        User user = userService.getCurrentUser();
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        UserClub userClub = userClubRepository.findByUserAndClub(user,club)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_CLUB_NOT_FOUND));
        if(userClub.getClubRole() == ClubRole.GUEST){
            throw new CustomException(ErrorCode.CLUB_NOT_LEAVE);
        }
        if(userClub.getClubRole() == ClubRole.LEADER){
            throw new CustomException(ErrorCode.CLUB_LEADER_NOT_LEAVE);
        }
        userClubRepository.delete(userClub);
        clubRepository.decrementMemberCount(club.getClubId());

        // TODO: 이벤트 기반으로 변경 - ClubLeftEvent 발행하여 ES 업데이트
        // ES 업데이트 (memberCount 변경)
        // clubElasticsearchService.updateClub(club);
    }
}
