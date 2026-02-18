package com.example.onlyone.domain.chat.event;

import com.example.onlyone.common.event.ScheduleCreatedEvent;
import com.example.onlyone.common.event.ScheduleDeletedEvent;
import com.example.onlyone.common.event.ScheduleJoinedEvent;
import com.example.onlyone.common.event.ScheduleLeftEvent;
import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
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
public class ChatScheduleEventListener {

    private final ChatRoomCommandService chatRoomCommandService;
    private final ChatRoomRepository chatRoomRepository;
    private final ClubRepository clubRepository;
    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleCreatedEvent(ScheduleCreatedEvent event) {
        log.info("[Event.Received] type=ScheduleCreatedEvent, scheduleId={}, scheduleName={}",
                event.scheduleId(), event.scheduleName());

        try {
            Club club = clubRepository.findById(event.clubId())
                    .orElseThrow(() -> new IllegalArgumentException("Club not found: " + event.clubId()));

            User user = userRepository.findById(event.leaderUserId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + event.leaderUserId()));

            ChatRoom chatRoom = chatRoomCommandService.createChatRoom(
                    club, ChatRoomType.SCHEDULE, event.scheduleId());
            chatRoomCommandService.addMember(chatRoom, user, ChatRole.LEADER);

            log.info("[Event.Completed] type=ScheduleCreatedEvent, scheduleId={}, chatRoomId={}",
                    event.scheduleId(), chatRoom.getChatRoomId());
        } catch (Exception e) {
            log.error("[Event.Failed] type=ScheduleCreatedEvent, scheduleId={}",
                    event.scheduleId(), e);
            throw e;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleJoinedEvent(ScheduleJoinedEvent event) {
        log.info("[Event.Received] type=ScheduleJoinedEvent, scheduleId={}, userId={}",
                event.scheduleId(), event.userId());

        try {
            ChatRoom chatRoom = chatRoomRepository
                    .findByTypeAndScheduleId(ChatRoomType.SCHEDULE, event.scheduleId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "ChatRoom not found for scheduleId: " + event.scheduleId()));

            User user = userRepository.findById(event.userId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + event.userId()));

            chatRoomCommandService.addMember(chatRoom, user, ChatRole.MEMBER);

            log.info("[Event.Completed] type=ScheduleJoinedEvent, scheduleId={}, userId={}, chatRoomId={}",
                    event.scheduleId(), event.userId(), chatRoom.getChatRoomId());
        } catch (Exception e) {
            log.error("[Event.Failed] type=ScheduleJoinedEvent, scheduleId={}, userId={}",
                    event.scheduleId(), event.userId(), e);
            throw e;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleLeftEvent(ScheduleLeftEvent event) {
        log.info("[Event.Received] type=ScheduleLeftEvent, scheduleId={}, userId={}",
                event.scheduleId(), event.userId());

        try {
            ChatRoom chatRoom = chatRoomRepository
                    .findByTypeAndScheduleId(ChatRoomType.SCHEDULE, event.scheduleId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "ChatRoom not found for scheduleId: " + event.scheduleId()));

            chatRoomCommandService.removeMember(event.userId(), chatRoom.getChatRoomId());

            log.info("[Event.Completed] type=ScheduleLeftEvent, scheduleId={}, userId={}",
                    event.scheduleId(), event.userId());
        } catch (Exception e) {
            log.error("[Event.Failed] type=ScheduleLeftEvent, scheduleId={}, userId={}",
                    event.scheduleId(), event.userId(), e);
            throw e;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleScheduleDeletedEvent(ScheduleDeletedEvent event) {
        log.info("[Event.Received] type=ScheduleDeletedEvent, scheduleId={}", event.scheduleId());

        try {
            chatRoomCommandService.deleteChatRoomBySchedule(event.scheduleId());

            log.info("[Event.Completed] type=ScheduleDeletedEvent, scheduleId={}", event.scheduleId());
        } catch (Exception e) {
            log.error("[Event.Failed] type=ScheduleDeletedEvent, scheduleId={}",
                    event.scheduleId(), e);
            throw e;
        }
    }
}
