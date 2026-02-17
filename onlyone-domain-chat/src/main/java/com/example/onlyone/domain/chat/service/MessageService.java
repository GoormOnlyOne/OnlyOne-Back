package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.dto.ChatRoomMessageResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
// TODO: 순환 의존성 방지 - Notification 도메인 의존성 제거
// import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    // TODO: 순환 의존성 방지 - Notification 도메인 의존성 제거
    // private final NotificationService notificationService;
    private final UserChatRoomRepository userChatRoomRepository;

    /**
     * 메시지 저장
     */
    @Transactional
    public ChatMessageResponse saveMessage(Long chatRoomId, Long kakaoId, String text) {
        if (text == null || text.isBlank()) throw new CustomException(ErrorCode.MESSAGE_BAD_REQUEST);

        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        User user = userRepository.findByKakaoId(kakaoId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 채팅방 미참여자 차단
        boolean joined = userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(user.getUserId(), chatRoomId);
        if (!joined) throw new CustomException(ErrorCode.FORBIDDEN_CHAT_ROOM);

        boolean isImage = text.startsWith("IMAGE::");
        String payload;
        if (isImage) {
            String after = text.substring("IMAGE::".length()).trim();
            if (after.isBlank() || after.contains(",") || after.contains(" "))
                throw new CustomException(ErrorCode.MESSAGE_BAD_REQUEST);
            if (!after.matches("(?i).+\\.(png|jpg|jpeg)$"))
                throw new CustomException(ErrorCode.INVALID_IMAGE_CONTENT_TYPE);
            payload = after;
        } else {
            payload = text.length() > 2000 ? text.substring(0, 2000) : text;
        }

        Message saved = messageRepository.save(Message.builder()
                .chatRoom(chatRoom).user(user).text(payload)
                .sentAt(LocalDateTime.now()).deleted(false).build());

        /*
        // 알림(보낸이 제외)
        notifyChatRoomMembers(chatRoom, user);
*/
        return new ChatMessageResponse(
                saved.getMessageId(),
                chatRoomId,
                user.getKakaoId(),
                user.getNickname(),
                user.getProfileImage(),
                isImage ? null : payload,
                isImage ? payload : null,
                saved.getSentAt(),
                false
        );
    }

    /**
     * 채팅방의 모든 멤버에게 CHAt 알림 생성 (보낸 사람은 제외)
     * TODO: 효율성을 위해 토픽 / bulk / 비동기 방식 등 고려 필요
     */
    private void notifyChatRoomMembers(ChatRoom chatRoom, User sender) {
        List<UserChatRoom> members = userChatRoomRepository.findAllByChatRoom(chatRoom);
        for (UserChatRoom userChatRoom : members) {
            User target = userChatRoom.getUser();
            if (target == null) continue;
            if (target.getUserId().equals(sender.getUserId())) continue;
//            notificationService.createNotification(target, com.example.onlyone.domain.notification.entity.Type.CHAT, new String[]{sender.getNickname()});
        }
    }

    /**
     * 메시지 논리적 삭제
     */
    @Transactional
    public void deleteMessage(Long messageId, Long userId) {
        Message m = messageRepository.findById(messageId)
                .orElseThrow(() -> new CustomException(ErrorCode.MESSAGE_NOT_FOUND));
        // 이미 삭제된 메시지 재삭제 차단
        if (m.isDeleted()) throw new CustomException(ErrorCode.MESSAGE_CONFLICT);
        // 본인 메세지만 삭제 가능
        if (!m.isOwnedBy(userId)) throw new CustomException(ErrorCode.MESSAGE_DELETE_ERROR);

        m.markAsDeleted();
    }

    @Transactional(readOnly = true)
    public ChatRoomMessageResponse getChatRoomMessages(
            Long chatRoomId, Integer size, Long cursorId, LocalDateTime cursorAt) {

        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        String chatRoomName = (chatRoom.getType() == ChatRoomType.SCHEDULE && chatRoom.getSchedule() != null)
                ? chatRoom.getSchedule().getName()
                : chatRoom.getClub().getName();

        int pageSize = (size == null || size <= 0) ? 50 : Math.min(size, 200);
        int fetchSize = pageSize + 1; // ★ hasMore 판단용
        Pageable limit = PageRequest.of(0, fetchSize);

        List<Message> slice;
        if (cursorId == null || cursorAt == null) {
            slice = messageRepository.findLatest(chatRoomId, limit);
        } else {
            slice = messageRepository.findOlderThan(chatRoomId, cursorAt, cursorId, limit);
        }

        boolean hasMore = slice.size() > pageSize;
        if (hasMore) {
            slice = slice.subList(0, pageSize); // 초과분 제거
        }

        // 화면은 아래쪽이 최신 → 오름차순으로 반환
        Collections.reverse(slice);

        // 다음 커서는 “이번 페이지의 가장 오래된(맨 위)” 메시지 기준
        Long nextCursorId = null;
        LocalDateTime nextCursorAt = null;
        if (!slice.isEmpty()) {
            Message oldest = slice.get(0);
            nextCursorId = oldest.getMessageId();
            nextCursorAt = oldest.getSentAt();
        }

        List<ChatMessageResponse> messages = slice.stream()
                .map(ChatMessageResponse::from)
                .toList();

        return new ChatRoomMessageResponse(
                chatRoomId,
                chatRoomName,
                messages,
                hasMore,
                nextCursorId,
                nextCursorAt
        );
    }

}
