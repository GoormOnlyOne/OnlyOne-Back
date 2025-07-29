package com.example.onlyone.domain.club.service;
import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;

import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
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
    private final ChatRoomRepository chatRoomRepository;
    private final UserService userService;
    private final UserChatRoomRepository userChatRoomRepository;

    /* 모임 생성*/
    public void createClub(ClubRequestDto requestDto) {
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
    }

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
}
