package com.example.onlyone.domain.chat.event;

import com.example.onlyone.common.event.ScheduleCreatedEvent;
import com.example.onlyone.common.event.ScheduleDeletedEvent;
import com.example.onlyone.common.event.ScheduleJoinedEvent;
import com.example.onlyone.common.event.ScheduleLeftEvent;
import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import com.example.onlyone.domain.chat.exception.ChatErrorCode;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.service.ChatRoomCommandService;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
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
public class ChatScheduleEventListener {

    private final ChatRoomCommandService chatRoomCommandService;
    private final ChatRoomRepository chatRoomRepository;
    private final ClubRepository clubRepository;
    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleCreatedEvent(ScheduleCreatedEvent event) {
        Club club = clubRepository.getReferenceById(event.clubId());
        User user = userRepository.getReferenceById(event.leaderUserId());

        ChatRoom chatRoom = chatRoomCommandService.createChatRoom(
                club, ChatRoomType.SCHEDULE, event.scheduleId());
        chatRoomCommandService.addMember(chatRoom, user, ChatRole.LEADER);

        log.info("[ScheduleCreated] scheduleId={}, chatRoomId={}",
                event.scheduleId(), chatRoom.getChatRoomId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleJoinedEvent(ScheduleJoinedEvent event) {
        ChatRoom chatRoom = chatRoomRepository
                .findByTypeAndScheduleId(ChatRoomType.SCHEDULE, event.scheduleId())
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        User user = userRepository.getReferenceById(event.userId());
        chatRoomCommandService.addMember(chatRoom, user, ChatRole.MEMBER);

        log.info("[ScheduleJoined] scheduleId={}, userId={}, chatRoomId={}",
                event.scheduleId(), event.userId(), chatRoom.getChatRoomId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleLeftEvent(ScheduleLeftEvent event) {
        ChatRoom chatRoom = chatRoomRepository
                .findByTypeAndScheduleId(ChatRoomType.SCHEDULE, event.scheduleId())
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        chatRoomCommandService.removeMember(event.userId(), chatRoom.getChatRoomId());

        log.info("[ScheduleLeft] scheduleId={}, userId={}", event.scheduleId(), event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleDeletedEvent(ScheduleDeletedEvent event) {
        chatRoomCommandService.deleteChatRoomBySchedule(event.scheduleId());

        log.info("[ScheduleDeleted] scheduleId={}", event.scheduleId());
    }
}
