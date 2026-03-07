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
import org.springframework.scheduling.annotation.Async;
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

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleClubCreatedEvent(ClubCreatedEvent event) {
        Club club = clubRepository.getReferenceById(event.clubId());
        User user = userRepository.getReferenceById(event.leaderUserId());

        ChatRoom chatRoom = chatRoomCommandService.createChatRoom(club, ChatRoomType.CLUB, null);
        chatRoomCommandService.addMember(chatRoom, user, ChatRole.LEADER);

        log.info("[ClubCreated] clubId={}, chatRoomId={}", event.clubId(), chatRoom.getChatRoomId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleClubLeftEvent(ClubLeftEvent event) {
        int deleted = userChatRoomRepository.deleteByUserIdAndClubId(
                event.userId(), event.clubId());

        log.info("[ClubLeft] clubId={}, userId={}, deletedChatRooms={}",
                event.clubId(), event.userId(), deleted);
    }
}
