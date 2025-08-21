package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.club.service.ClubService;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("test")
@DataJpaTest
@Import({ScheduleService.class, UserService.class, ClubService.class})
public class ScheduleServiceTest {

    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ClubService clubService;
    @MockBean
    private UserService userService;

    @Autowired
    private ClubRepository clubRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private UserClubRepository userClubRepository;
    @Autowired
    private ChatRoomRepository chatRoomRepository;
    @Autowired
    private UserChatRoomRepository userChatRoomRepository;
    @Autowired
    private SettlementRepository settlementRepository;

    @Test
    void READY이면서_스케줄_시간이_지난_스케줄은_ENDED로_일괄_변경된다() {
        // given
        User user = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(user);

        ClubRequestDto clubRequestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                10,
                "테스트 설명...",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = clubService.createClub(clubRequestDto);

        // 지난 시간으로 스케줄 생성 (READY 상태)
        ScheduleRequestDto pastScheduleRequestDto = new ScheduleRequestDto(
                "온리원 첫 번째 정모",
                "구름스퀘어 강남",
                10000,
                10,
                LocalDateTime.now().minusHours(2)   // 지난 시간
        );
        scheduleService.createSchedule(responseDto.getClubId(), pastScheduleRequestDto);

        ScheduleRequestDto futerScheduleRequestDto = new ScheduleRequestDto(
                "온리원 두 번째 정모",
                "구름스퀘어 강남",
                10000,
                10,
                LocalDateTime.now().plusHours(2)   // 미래 시간
        );
        scheduleService.createSchedule(responseDto.getClubId(), futerScheduleRequestDto);

        // when
        scheduleService.updateScheduleStatus();

        // then
        Schedule pastSchedule = scheduleRepository.findByNameAndClub_ClubId("온리원 첫 번째 정모", responseDto.getClubId()).orElseThrow();
        Schedule futureSchedule = scheduleRepository.findByNameAndClub_ClubId("온리원 두 번째 정모", responseDto.getClubId()).orElseThrow();

        assertEquals(ScheduleStatus.ENDED, pastSchedule.getScheduleStatus());   // 지난 스케줄은 ENDED
        assertEquals(ScheduleStatus.READY, futureSchedule.getScheduleStatus()); // 미래 스케줄은 그대로 READY
    }

    @Test
    void 리더는_정기_모임을_정상_생성한다() {
        // given
        User user = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(user);

        ClubRequestDto clubRequestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                10,
                "테스트 설명...",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = clubService.createClub(clubRequestDto);

        String name = "온리원의 정모";
        String location = "구름스퀘어 강남";
        int cost = 10000;
        int userlimit = 10;
        LocalDateTime scheduleTime = LocalDateTime.now().plusHours(2);

        ScheduleRequestDto requestDto =
                new ScheduleRequestDto(name, location, cost, userlimit, scheduleTime);

        // when
        scheduleService.createSchedule(responseDto.getClubId(), requestDto);

        // then
        Schedule schedule = scheduleRepository.findByNameAndClub_ClubId("온리원의 정모", responseDto.getClubId()).orElseThrow();
        assertThat(schedule.getScheduleId()).isNotNull();
        assertThat(schedule.getScheduleStatus()).isEqualTo(ScheduleStatus.READY);

        assertThat(schedule.getName()).isEqualTo(name);
        assertThat(schedule.getLocation()).isEqualTo(location);
        assertThat(schedule.getCost()).isEqualTo(cost);
        assertThat(schedule.getUserLimit()).isEqualTo(userlimit);
        assertThat(schedule.getScheduleTime()).isEqualTo(scheduleTime);
    }

    @Test
    void 정모를_생성하면_Settlement가_생성된다() {
        // given
        User user = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(user);

        ClubRequestDto clubRequestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                10,
                "테스트 설명...",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = clubService.createClub(clubRequestDto);
        ScheduleRequestDto scheduleRequestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                100,
                100,
                LocalDateTime.now().minusHours(2)
        );

        // when
        scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);

        // then
        Schedule schedule = scheduleRepository.findByNameAndClub_ClubId("온리원의 정모", responseDto.getClubId()).orElseThrow();
        Settlement settlement = settlementRepository.findBySchedule(schedule).orElseThrow();

        assertThat(settlement.getSettlementId()).isNotNull();
        assertThat(settlement.getReceiver().getUserId()).isEqualTo(user.getUserId());
        assertThat(settlement.getTotalStatus()).isEqualTo(TotalStatus.HOLDING);
    }

    @Test
    void 정모를_생성하면_정모_채팅방이_생성된다() {
        // given
        User user = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(user);

        ClubRequestDto clubRequestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                10,
                "테스트 설명...",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = clubService.createClub(clubRequestDto);
        ScheduleRequestDto scheduleRequestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                100,
                100,
                LocalDateTime.now().minusHours(2)
        );

        // when
        scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);

        // then
        Schedule schedule = scheduleRepository.findByNameAndClub_ClubId("온리원의 정모", responseDto.getClubId()).orElseThrow();
        ChatRoom chatRoom = chatRoomRepository.findByTypeAndScheduleId(Type.SCHEDULE, schedule.getScheduleId()).orElseThrow();
        UserChatRoom userChatRoom = userChatRoomRepository.findByUserUserIdAndChatRoomChatRoomId(user.getUserId(), chatRoom.getChatRoomId()).orElseThrow();

        assertThat(chatRoom.getChatRoomId()).isNotNull();
        assertThat(chatRoom.getClub().getClubId()).isEqualTo(responseDto.getClubId());
        assertThat(userChatRoom.getUser().getUserId()).isEqualTo(user.getUserId());
        assertThat(userChatRoom.getChatRole().name()).isEqualTo("LEADER");
    }

    @Test
    void 리더가_아닌_멤버가_정기_모임을_추가할_경우_예외가_발생한다() throws Exception {
        // given
        User leader = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        ClubRequestDto clubRequestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                10,
                "테스트 설명...",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = clubService.createClub(clubRequestDto);
        ScheduleRequestDto scheduleRequestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                100,
                100,
                LocalDateTime.now().plusHours(2)
        );

        User member = userRepository.findById(2L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(member);

        // when & then
        clubService.joinClub(responseDto.getClubId());
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto)
        );
        assertEquals(ErrorCode.MEMBER_CANNOT_CREATE_SCHEDULE, exception.getErrorCode());
    }

    @Test
    void 리더는_정기_모임을_정상_수정한다() {
        // given
        User leader = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        ClubRequestDto clubRequestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                10,
                "테스트 설명...",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = clubService.createClub(clubRequestDto);
        ScheduleRequestDto scheduleRequestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                100,
                100,
                LocalDateTime.now().plusHours(2)
        );
        scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);

        String name = "정기모임 수정 테스트";
        String location = "우리집";
        int cost = 10000;
        int userLimit = 50;
        LocalDateTime scheduleTime = LocalDateTime.now().plusHours(4);

        ScheduleRequestDto updateRequestDto =
                new ScheduleRequestDto(name, location, cost, userLimit, scheduleTime);

        // when
        scheduleService.createSchedule(responseDto.getClubId(), updateRequestDto);

        // then
        Schedule schedule = scheduleRepository.findByNameAndClub_ClubId("정기모임 수정 테스트", responseDto.getClubId()).orElseThrow();

        // DB에 실제 저장된 값 검증
        assertThat(schedule.getScheduleId()).isNotNull();
        assertThat(updateRequestDto.getName()).isEqualTo(name);
        assertThat(updateRequestDto.getLocation()).isEqualTo(location);
        assertThat(updateRequestDto.getCost()).isEqualTo(cost);
        assertThat(updateRequestDto.getUserLimit()).isEqualTo(userLimit);
        assertThat(updateRequestDto.getScheduleTime()).isEqualTo(scheduleTime);
    }

    @Test
    void 리더가_아닌_멤버가_정기_모임을_수정할_경우_예외가_발생한다() throws Exception {
        // given
        User leader = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        ClubRequestDto clubRequestDto = new ClubRequestDto(
                "온리원 첫 번째 모임",
                10,
                "테스트 설명...",
                null,
                "서울특별시",
                "강남구",
                "EXERCISE"
        );
        ClubCreateResponseDto responseDto = clubService.createClub(clubRequestDto);
        ScheduleRequestDto scheduleRequestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                100,
                100,
                LocalDateTime.now().plusHours(2)
        );
        scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);

        User member = userRepository.findById(2L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        Schedule schedule = scheduleRepository.findByNameAndClub_ClubId("온리원의 정모",  responseDto.getClubId()).orElseThrow();

        ScheduleRequestDto updateScheduleRequestDto = new ScheduleRequestDto(
                "온리원의 정모 수정본",
                "역삼역",
                100,
                100,
                LocalDateTime.now().minusHours(2)
        );

        // when & then
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.updateSchedule(responseDto.getClubId(), schedule.getScheduleId(), updateScheduleRequestDto)
        );
        assertEquals(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE, exception.getErrorCode());
    }


}
