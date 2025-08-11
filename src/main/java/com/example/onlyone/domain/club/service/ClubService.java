package com.example.onlyone.domain.club.service;
import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;

import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.dto.response.ClubDetailResponseDto;
import com.example.onlyone.domain.club.dto.response.ClubNameResponseDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class ClubService {
    private final ClubRepository clubRepository;
    private final InterestRepository interestRepository;
    private final UserClubRepository userClubRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserService userService;
    private final UserChatRoomRepository userChatRoomRepository;

    /* 모임 생성*/
    public ClubCreateResponseDto createClub(ClubRequestDto requestDto) {
        Interest interest = interestRepository.findByCategory(Category.from(requestDto.getCategory()))
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
        // 모임 전체 채팅방 생성
        ChatRoom chatRoom = ChatRoom.builder()
                .club(club)
                .type(Type.CLUB)
                .build();
        chatRoomRepository.save(chatRoom);
        // 모임장의 UserChatRoom 생성
        UserChatRoom userChatRoom = UserChatRoom.builder()
                .chatRoom(chatRoom)
                .user(user)
                .chatRole(ChatRole.LEADER)
                .build();
        userChatRoomRepository.save(userChatRoom);
        return new ClubCreateResponseDto(club.getClubId());
    }

    /* 모임 수정*/
    public void updateClub(long clubId, ClubRequestDto requestDto) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Interest interest = interestRepository.findByCategory(Category.from(requestDto.getCategory()))
                .orElseThrow(() -> new CustomException(ErrorCode.INTEREST_NOT_FOUND));
        User user = userService.getCurrentUser();
        UserClub userClub = userClubRepository.findByUserAndClub(user,club)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_CLUB_NOT_FOUND));
        if (userClub.getClubRole() != ClubRole.LEADER) {
            throw new CustomException(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE);
        }
        club.update(
                requestDto.getName(),
                requestDto.getUserLimit(),
                requestDto.getDescription(),
                requestDto.getClubImage(),
                requestDto.getCity(),
                requestDto.getDistrict(),
                interest
        );
    }

    /* 모임 상세 조회*/
    @Transactional(readOnly = true)
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
    public void joinClub(Long clubId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        int userCount =  userClubRepository.countByClub_ClubId(club.getClubId());
        if (userCount >= club.getUserLimit()) {
            throw new CustomException(ErrorCode.CLUB_NOT_ENTER);
        }
        User user = userService.getCurrentUser();
        if (userClubRepository.findByUserAndClub(user, club).isPresent()) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_CLUB);
        }
        UserClub userClub = UserClub.builder()
                .user(user)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();
        userClubRepository.save(userClub);

        ChatRoom chatRoom = chatRoomRepository.findByTypeAndClub_ClubId(Type.CLUB, clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        UserChatRoom userChatRoom = UserChatRoom.builder()
                .user(user)
                .chatRoom(chatRoom)
                .chatRole(ChatRole.MEMBER)
                .build();
        userChatRoomRepository.save(userChatRoom);
    }

    /* 모임 탈퇴*/
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
    }

    /* 가입하고 있는 모임 조회*/
    public List<ClubNameResponseDto> getClubNames() {
        Long userId = userService.getCurrentUser().getUserId();

        List<UserClub> userClubs = userClubRepository.findByUserUserId(userId);

        return userClubs.stream()
                .map(UserClub::getClub)
                .filter(Objects::nonNull)
                .map(c -> ClubNameResponseDto.builder()
                        .clubId(c.getClubId())
                        .name(c.getName())
                        .build())
                .toList();
    }
}
