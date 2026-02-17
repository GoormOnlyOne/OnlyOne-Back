package com.example.onlyone.domain.chat.event;

import com.example.onlyone.common.event.ClubCreatedEvent;
import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Club 도메인 이벤트 리스너
 * Club 생성 시 자동으로 전체 채팅방 생성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClubEventListener {

    private final ChatRoomRepository chatRoomRepository;
    private final UserChatRoomRepository userChatRoomRepository;
    private final ClubRepository clubRepository;
    private final UserRepository userRepository;

    /**
     * Club 생성 이벤트 처리 - 전체 채팅방 자동 생성
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleClubCreatedEvent(ClubCreatedEvent event) {
        log.info("[Event.Received] type=ClubCreatedEvent, clubId={}, leaderUserId={}",
                event.clubId(), event.leaderUserId());

        try {
            // Club 조회
            Club club = clubRepository.findById(event.clubId())
                    .orElseThrow(() -> new IllegalArgumentException("Club not found: " + event.clubId()));

            // User 조회
            User user = userRepository.findById(event.leaderUserId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + event.leaderUserId()));

            // 모임 전체 채팅방 생성
            ChatRoom chatRoom = ChatRoom.builder()
                    .club(club)
                    .type(ChatRoomType.CLUB)
                    .build();
            chatRoomRepository.save(chatRoom);

            // 모임장의 UserChatRoom 생성
            UserChatRoom userChatRoom = UserChatRoom.builder()
                    .chatRoom(chatRoom)
                    .user(user)
                    .chatRole(ChatRole.LEADER)
                    .build();
            userChatRoomRepository.save(userChatRoom);

            log.info("[Event.Completed] type=ClubCreatedEvent, clubId={}, chatRoomId={}",
                    event.clubId(), chatRoom.getChatRoomId());
        } catch (Exception e) {
            log.error("[Event.Failed] type=ClubCreatedEvent, clubId={}", event.clubId(), e);
            throw e;
        }
    }
}
