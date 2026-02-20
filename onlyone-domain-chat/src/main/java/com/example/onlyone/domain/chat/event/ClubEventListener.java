package com.example.onlyone.domain.chat.event;

import com.example.onlyone.common.event.ClubCreatedEvent;
import com.example.onlyone.common.event.ClubLeftEvent;
import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.chat.service.ChatRoomCommandService;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClubEventListener {

    private final ChatRoomCommandService chatRoomCommandService;
    private final UserChatRoomRepository userChatRoomRepository;
    private final ClubRepository clubRepository;
    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleClubCreatedEvent(ClubCreatedEvent event) {
        log.info("[Event.Received] type=ClubCreatedEvent, clubId={}, leaderUserId={}",
                event.clubId(), event.leaderUserId());

        try {
            Club club = clubRepository.findById(event.clubId())
                    .orElseThrow(() -> new IllegalArgumentException("Club not found: " + event.clubId()));

            User user = userRepository.findById(event.leaderUserId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + event.leaderUserId()));

            ChatRoom chatRoom = chatRoomCommandService.createChatRoom(club, ChatRoomType.CLUB, null);
            chatRoomCommandService.addMember(chatRoom, user, ChatRole.LEADER);

            log.info("[Event.Completed] type=ClubCreatedEvent, clubId={}, chatRoomId={}",
                    event.clubId(), chatRoom.getChatRoomId());
        } catch (Exception e) {
            log.error("[Event.Failed] type=ClubCreatedEvent, clubId={}", event.clubId(), e);
            throw e;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleClubLeftEvent(ClubLeftEvent event) {
        log.info("[Event.Received] type=ClubLeftEvent, clubId={}, userId={}",
                event.clubId(), event.userId());

        try {
            int deleted = userChatRoomRepository.deleteByUserIdAndClubId(
                    event.userId(), event.clubId());

            log.info("[Event.Completed] type=ClubLeftEvent, clubId={}, userId={}, deletedChatRooms={}",
                    event.clubId(), event.userId(), deleted);
        } catch (Exception e) {
            log.error("[Event.Failed] type=ClubLeftEvent, clubId={}, userId={}",
                    event.clubId(), event.userId(), e);
        }
    }
}
