package com.example.onlyone.domain.schedule.service;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.service.ClubService;
import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleCreateResponseDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleDetailResponseDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleResponseDto;
import com.example.onlyone.domain.schedule.dto.response.ScheduleUserResponseDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
    @MockitoBean
    private UserService userService;

    @Autowired
    private ClubRepository clubRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private UserScheduleRepository userScheduleRepository;
    @Autowired
    private ChatRoomRepository chatRoomRepository;
    @Autowired
    private UserChatRoomRepository userChatRoomRepository;
    @Autowired
    private SettlementRepository settlementRepository;
    @Autowired
    private UserSettlementRepository userSettlementRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    EntityManager entityManager;

    /* 정기모임 생성 */
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
        ScheduleCreateResponseDto pastResponseDto = scheduleService.createSchedule(responseDto.getClubId(), pastScheduleRequestDto);

        ScheduleRequestDto futureScheduleRequestDto = new ScheduleRequestDto(
                "온리원 두 번째 정모",
                "구름스퀘어 강남",
                10000,
                10,
                LocalDateTime.now().plusHours(2)    // 미래 시간
        );
        ScheduleCreateResponseDto futureResponseDto = scheduleService.createSchedule(responseDto.getClubId(), futureScheduleRequestDto);

        // when
        scheduleService.updateScheduleStatus();

        // then
        Schedule pastSchedule = scheduleRepository.findById(pastResponseDto.getScheduleId()).orElseThrow();
        Schedule futureSchedule = scheduleRepository.findById(futureResponseDto.getScheduleId()).orElseThrow();

        assertEquals(ScheduleStatus.ENDED, pastSchedule.getScheduleStatus());   // 지난 스케줄은 ENDED
        assertEquals(ScheduleStatus.READY, futureSchedule.getScheduleStatus()); // 미래 스케줄은 그대로 READY
    }


    /* 정기모임 생성 */
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), requestDto);

        // then
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();
        UserSchedule userSchedule = userScheduleRepository.findByUserAndSchedule(user, schedule).orElseThrow();
        assertThat(schedule.getScheduleId()).isNotNull();
        assertThat(schedule.getScheduleStatus()).isEqualTo(ScheduleStatus.READY);
        assertThat(userSchedule.getUser()).isEqualTo(user);
        assertThat(userSchedule.getScheduleRole()).isEqualTo(ScheduleRole.LEADER);

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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);

        // then
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);

        // then
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();
        ChatRoom chatRoom = chatRoomRepository.findByTypeAndScheduleId(Type.SCHEDULE, schedule.getScheduleId()).orElseThrow();
        UserChatRoom userChatRoom = userChatRoomRepository.findByUserUserIdAndChatRoomChatRoomId(user.getUserId(), chatRoom.getChatRoomId()).orElseThrow();

        assertThat(chatRoom.getChatRoomId()).isNotNull();
        assertThat(chatRoom.getClub().getClubId()).isEqualTo(responseDto.getClubId());
        assertThat(userChatRoom.getUser().getUserId()).isEqualTo(user.getUserId());
        assertThat(userChatRoom.getChatRole().name()).isEqualTo("LEADER");
    }

    @Test
    void 리더가_아닌_멤버가_정기_모임을_추가할_경우_예외가_발생한다() {
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

    /* 정기모임 수정 */
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);

        String name = "정기모임 수정 테스트";
        String location = "우리집";
        int cost = 10000;
        int userLimit = 50;
        LocalDateTime scheduleTime = LocalDateTime.now().plusHours(4);

        ScheduleRequestDto updateRequestDto =
                new ScheduleRequestDto(name, location, cost, userLimit, scheduleTime);

        // when: updateSchedule 호출로 수정
        scheduleService.updateSchedule(responseDto.getClubId(), created.getScheduleId(), updateRequestDto);

        // then
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        assertThat(schedule.getScheduleId()).isNotNull();
        assertThat(schedule.getName()).isEqualTo(name);
        assertThat(schedule.getLocation()).isEqualTo(location);
        assertThat(schedule.getCost()).isEqualTo(cost);
        assertThat(schedule.getUserLimit()).isEqualTo(userLimit);
        assertThat(schedule.getScheduleTime()).isEqualTo(scheduleTime);
    }

    @Test
    void 정모_금액을_수정하면_모든_참여자의_정산_예약금이_변경된다() {
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);

        Long clubId = responseDto.getClubId();
        Long scheduleId = created.getScheduleId();

        User member = userRepository.findById(2L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(clubId);
        scheduleService.joinSchedule(clubId, scheduleId);

        ScheduleRequestDto updateScheduleRequestDto = new ScheduleRequestDto(
                "온리원 첫 번째 정모",
                "구름스퀘어 강남",
                200,
                10,
                LocalDateTime.now().plusHours(2)
        );
        Mockito.when(userService.getCurrentUser()).thenReturn(leader);

        // when
        scheduleService.updateSchedule(clubId, scheduleId, updateScheduleRequestDto);
        entityManager.flush();
        entityManager.clear();

        // then
        Schedule refreshSchedule = scheduleRepository.findById(scheduleId).orElse(null);
        Wallet memberWallet = walletRepository.findByUserWithoutLock(member).orElseThrow();
        entityManager.refresh(memberWallet);

        assertThat(refreshSchedule.getCost()).isEqualTo(updateScheduleRequestDto.getCost());
        assertThat(memberWallet.getPendingOut()).isEqualTo(updateScheduleRequestDto.getCost());
        long pending = walletRepository.getPendingOutByUserId(member.getUserId());
        assertThat(pending).isEqualTo(200L);
    }

    @Test
    void 리더가_아닌_멤버가_정기_모임을_수정할_경우_예외가_발생한다() {
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);

        User member = userRepository.findById(2L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        Schedule schedule = scheduleRepository.findByNameAndClub_ClubId("온리원의 정모",  responseDto.getClubId()).orElseThrow();

        ScheduleRequestDto updateScheduleRequestDto = new ScheduleRequestDto(
                "온리원의 정모 수정본",
                "역삼역",
                100,
                100,
                LocalDateTime.now().plusHours(2)
        );

        // when & then
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.updateSchedule(responseDto.getClubId(), created.getScheduleId(), updateScheduleRequestDto)
        );
        assertEquals(ErrorCode.MEMBER_CANNOT_MODIFY_SCHEDULE, exception.getErrorCode());
    }

    @Test
    void 상태가_READY인_정모만_수정이_가능하다() {
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);

        ScheduleRequestDto updateScheduleRequestDto = new ScheduleRequestDto(
                "온리원의 정모 수정본",
                "역삼역",
                150,
                50,
                LocalDateTime.now().plusHours(2)
        );

        // when
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();
        schedule.updateStatus(ScheduleStatus.READY);
        scheduleService.updateSchedule(responseDto.getClubId(), schedule.getScheduleId(), updateScheduleRequestDto);

        // then
        Schedule after = scheduleRepository.findById(created.getScheduleId()).orElseThrow();
        assertThat(after.getScheduleId()).isNotNull();
        assertThat(after.getScheduleStatus()).isEqualTo(ScheduleStatus.READY);
        assertThat(after.getName()).isEqualTo(updateScheduleRequestDto.getName());
        assertThat(after.getLocation()).isEqualTo(updateScheduleRequestDto.getLocation());
        assertThat(after.getCost()).isEqualTo(updateScheduleRequestDto.getCost());
        assertThat(after.getUserLimit()).isEqualTo(updateScheduleRequestDto.getUserLimit());
        assertThat(after.getScheduleTime()).isEqualTo(updateScheduleRequestDto.getScheduleTime());
    }

    @Test
    void 상태가_READY가_아닌_정모는_수정_불가능하다() {
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);

        ScheduleRequestDto updateScheduleRequestDto = new ScheduleRequestDto(
                "온리원의 정모 수정본",
                "역삼역",
                150,
                50,
                LocalDateTime.now().plusHours(2)
        );

        // when & then
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();
        schedule.updateStatus(ScheduleStatus.ENDED);

        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.updateSchedule(responseDto.getClubId(), schedule.getScheduleId(), updateScheduleRequestDto)
        );
        assertEquals(ErrorCode.ALREADY_ENDED_SCHEDULE, exception.getErrorCode());
    }

    /* 정기모임 참여 */
    @Test
    void 모임_멤버는_정상적으로_정모에_참여한다() {
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());

        // when
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // then
        UserSchedule userSchedule = userScheduleRepository.findByUserAndSchedule(member, schedule).orElseThrow();

        assertThat(userSchedule.getUser()).isEqualTo(member);
        assertThat(userSchedule.getScheduleRole()).isEqualTo(ScheduleRole.MEMBER);
    }

    @Test
    void 정모에_참여하면_UserSettlement가_생성되고_예약금이_지갑에_저장된다() {
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());
        Wallet wallet = walletRepository.findByUserWithoutLock(member).orElseThrow();
        int prevPendingOut = wallet.getPendingOut();

        // when
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // then
        UserSettlement userSettlement = userSettlementRepository.findByUserAndSchedule(member, schedule).orElseThrow();
        Wallet newWallet = walletRepository.findByUserWithoutLock(member).orElseThrow();
        int newPendingOut = newWallet.getPendingOut();

        assertThat(userSettlement.getUser()).isEqualTo(member);
        assertThat(userSettlement.getSettlementStatus()).isEqualTo(SettlementStatus.HOLD_ACTIVE);
        assertThat(newPendingOut - prevPendingOut).isEqualTo(schedule.getCost());
    }

    @Test
    void 정모에_참여하려는_유저의_예약금을_제외한_잔액이_부족하면_예외가_발생한다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(3L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());

        // when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId())
        );
        assertEquals(ErrorCode.WALLET_BALANCE_NOT_ENOUGH, exception.getErrorCode());
    }

    @Test
    void 이미_참여_중인_정모인_경우_예외가_발생한다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId())
        );
        assertEquals(ErrorCode.ALREADY_JOINED_SCHEDULE, exception.getErrorCode());
    }

    @Test
    void 상태가_READY인_정모는_참여가_가능하다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());

        // when
        schedule.updateStatus(ScheduleStatus.READY);
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // then
        UserSchedule userSchedule = userScheduleRepository.findByUserAndSchedule(member, schedule).orElseThrow();

        assertThat(userSchedule.getUser()).isEqualTo(member);
        assertThat(userSchedule.getScheduleRole()).isEqualTo(ScheduleRole.MEMBER);
    }

    @Test
    void 상태가_READY가_아닌_정모에_참여하면_예외가_발생한다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());

        // when & then
        schedule.updateStatus(ScheduleStatus.ENDED);
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId())
        );
        assertEquals(ErrorCode.ALREADY_ENDED_SCHEDULE, exception.getErrorCode());
    }

    @Test
    void 모임_멤버가_아닌_경우_정모에_참여하면_예외가_발생한다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);

        // when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId())
        );
        assertEquals(ErrorCode.USER_CLUB_NOT_FOUND, exception.getErrorCode());
    }

    /* 정기 모임 참여 취소 */
    @Test
    void 정모_참여자는_정상적으로_참여를_취소한다() {
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // when
        scheduleService.leaveSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // then
        Optional<UserSchedule> userSchedule = userScheduleRepository.findByUserAndSchedule(member, schedule);
        assertThat(userSchedule).isEmpty();
    }

    @Test
    void 정모_참여_취소_시_UserSettlement과_정산_예약금이_삭제된다() {
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());
        int prevPendingOut = walletRepository.findByUserWithoutLock(member).orElseThrow().getPendingOut();

        // when
        scheduleService.leaveSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // then
        Optional<UserSettlement> userSettlement = userSettlementRepository.findByUserAndSchedule(member, schedule);
        int newPendingOut = walletRepository.findByUserWithoutLock(member).orElseThrow().getPendingOut();
        assertThat(userSettlement).isEmpty();
        assertThat(prevPendingOut - newPendingOut).isEqualTo(schedule.getCost());
    }

    @Test
    void 상태가_READY인_정모는_참여_취소가_가능하다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // when
        schedule.updateStatus(ScheduleStatus.READY);
        scheduleService.leaveSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // then
        Optional<UserSchedule> userSchedule = userScheduleRepository.findByUserAndSchedule(member, schedule);
        assertThat(userSchedule).isEmpty();
    }

    @Test
    void 상태가_READY가_아닌_정모에_참여_취소하면_예외가_발생한다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // when & then
        schedule.updateStatus(ScheduleStatus.ENDED);
        scheduleRepository.saveAndFlush(schedule);
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.leaveSchedule(responseDto.getClubId(), schedule.getScheduleId())
        );
        assertEquals(ErrorCode.ALREADY_ENDED_SCHEDULE, exception.getErrorCode());
    }

    @Test
    void 리더가_정모_참여_취소하면_예외가_발생한다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        // when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.leaveSchedule(responseDto.getClubId(), schedule.getScheduleId())
        );
        assertEquals(ErrorCode.LEADER_CANNOT_LEAVE_SCHEDULE, exception.getErrorCode());
    }

    @Test
    void 정모_참여자가_아닌_경우_예외가_발생한다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        // 모임 가입 안 함

        // when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.leaveSchedule(responseDto.getClubId(), schedule.getScheduleId())
        );
        assertEquals(ErrorCode.USER_SCHEDULE_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void 이미_해제나_완료된_정모에_참여_취소하면_예외가_발생한다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());
        UserSettlement userSettlement = userSettlementRepository.findByUserAndSchedule(member, schedule).orElseThrow();
        int prevPendingOut = walletRepository.findByUserWithoutLock(member).orElseThrow().getPendingOut();

        // when & then: 정산 상태를 완료/대기 등으로 바꿔 해제 불가 상황 시뮬레이션
        userSettlement.updateStatus(SettlementStatus.PENDING);
        scheduleService.leaveSchedule(responseDto.getClubId(), schedule.getScheduleId());
        Wallet newWallet = walletRepository.findByUserWithoutLock(member).orElseThrow();
        int newPendingOut = newWallet.getPendingOut();

        assertThat(newPendingOut).isEqualTo(prevPendingOut);
        assertThat(userScheduleRepository.findByUserAndSchedule(member, schedule)).isPresent();
        assertThat(userSettlementRepository.findByUserAndSchedule(member, schedule)).isPresent();
    }

    @Test
    void 유저_지갑_홀드_해제에_실패하면_예외가_발생한다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElseThrow();
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // pending_out 값을 임의로 바꿔 일관성 충돌 유발
        entityManager.createNativeQuery("UPDATE wallet SET pending_out = pending_out - 1 WHERE user_id = :userId")
                .setParameter("userId", member.getUserId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.leaveSchedule(responseDto.getClubId(), schedule.getScheduleId())
        );
        assertEquals(ErrorCode.WALLET_HOLD_STATE_CONFLICT, exception.getErrorCode());
    }

    /* 정기 모임 삭제 */
    @Test
    void 리더가_정기_모임을_정상적으로_삭제한다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();
        entityManager.flush();
        entityManager.clear();

        // when
        scheduleService.deleteSchedule(responseDto.getClubId(), schedule.getScheduleId());
        entityManager.flush();
        entityManager.clear();

        // then
        Optional<Schedule> deletedSchedule = scheduleRepository.findById(created.getScheduleId());
        assertThat(deletedSchedule).isEmpty();
    }

    @Test
    void 정모를_삭제하면_정모_채팅방과_관련_메시지_데이터가_모두_삭제된다_() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        entityManager.flush();
        entityManager.clear();
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();
        ChatRoom chatRoom = chatRoomRepository.findByTypeAndScheduleId(Type.SCHEDULE, schedule.getScheduleId()).orElseThrow();

        // when
        scheduleService.deleteSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // then
        Optional<ChatRoom> deletedChatRoom = chatRoomRepository.findByTypeAndScheduleId(Type.SCHEDULE, schedule.getScheduleId());
        List<Message> deletedMessages = messageRepository.findByChatRoomChatRoomIdAndDeletedFalseOrderBySentAtAsc(chatRoom.getChatRoomId());

        assertThat(deletedChatRoom).isEmpty();
        assertThat(deletedMessages).isEmpty();
    }

    @Test
    void 상태가_READY이면서_정모_시간이_지나지_않은_정모는_삭제_가능하다_() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        entityManager.flush();
        entityManager.clear();
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        // when
        schedule.updateStatus(ScheduleStatus.READY);
        scheduleService.deleteSchedule(responseDto.getClubId(), schedule.getScheduleId());
        entityManager.flush();
        entityManager.clear();

        // then
        Optional<Schedule> deletedSchedule = scheduleRepository.findById(created.getScheduleId());
        assertThat(deletedSchedule).isEmpty();
    }

    @Test
    void 상태가_READY가_아니면서_정모_시간이_지난_정모_삭제_시_예외가_발생한다() {
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
                10000,
                100,
                LocalDateTime.now().minusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        entityManager.flush();
        entityManager.clear();
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        // when & then
        schedule.updateStatus(ScheduleStatus.ENDED);
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.deleteSchedule(responseDto.getClubId(), schedule.getScheduleId())
        );
        assertEquals(ErrorCode.INVALID_SCHEDULE_DELETE, exception.getErrorCode());
    }

    @Test
    void 리더가_아닌_멤버가_정모를_삭제하면_예외가_발생한다() {
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
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        entityManager.flush();
        entityManager.clear();
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // when & then
        schedule.updateStatus(ScheduleStatus.ENDED);
        CustomException exception = assertThrows(CustomException.class, () ->
                scheduleService.deleteSchedule(responseDto.getClubId(), schedule.getScheduleId())
        );
        assertEquals(ErrorCode.MEMBER_CANNOT_DELETE_SCHEDULE, exception.getErrorCode());
    }

    /* 정기 모임 목록 조회 */
    @Test
    void 모임의_정모_목록이_최근에_생성된_순서대로_정상_조회된다() throws Exception {
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
        ClubCreateResponseDto clubCreated = clubService.createClub(clubRequestDto);
        Long clubId = clubCreated.getClubId();

        ScheduleCreateResponseDto s1 = scheduleService.createSchedule(clubId, new ScheduleRequestDto(
                "온리원 정모 1",
                "구름스퀘어 강남",
                100,
                100,
                LocalDateTime.now().plusDays(1)
        ));
        Thread.sleep(10);
        ScheduleCreateResponseDto s2 = scheduleService.createSchedule(clubId, new ScheduleRequestDto(
                "온리원 정모 2",
                "구름스퀘어 강남",
                100,
                100,
                LocalDateTime.now().plusDays(2)
        ));
        Thread.sleep(10);
        ScheduleCreateResponseDto s3 = scheduleService.createSchedule(clubId, new ScheduleRequestDto(
                "온리원 정모 3",
                "구름스퀘어 강남",
                100,
                100,
                LocalDateTime.now().plusDays(3)
        ));

        entityManager.flush();
        entityManager.clear();

        // when
        List<ScheduleResponseDto> list = scheduleService.getScheduleList(clubId);

        // then
        assertThat(list).hasSize(3);
        assertThat(list).extracting("name")
                .containsExactly("온리원 정모 3", "온리원 정모 2", "온리원 정모 1");
    }

    /* 정기 모임 참여자 목록 조회 */
    @Test
    void 정모_참여자_목록이_참여한_순서대로_정상_조회된다() {
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
        ScheduleCreateResponseDto created = scheduleService.createSchedule(responseDto.getClubId(), scheduleRequestDto);
        entityManager.flush();
        entityManager.clear();
        Schedule schedule = scheduleRepository.findById(created.getScheduleId()).orElseThrow();

        User member = userRepository.findById(2L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(member);
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        User member2 = userRepository.findById(4L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(member2);
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        User member3 = userRepository.findById(3L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(member3);
        clubService.joinClub(responseDto.getClubId());
        scheduleService.joinSchedule(responseDto.getClubId(), schedule.getScheduleId());

        // when
        List<ScheduleUserResponseDto> list =
                scheduleService.getScheduleUserList(responseDto.getClubId(), schedule.getScheduleId());

        // then (정렬 기준에 맞게)
        assertThat(list).hasSize(4);
        assertThat(list).extracting("nickname").containsExactly("Alice", "Bob", "Daisy", "Charlie");
    }

    /* 정기 모임 상세 조회 */
    @Test
    void 정기_모임_상세를_정상_조회한다() {
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
        ClubCreateResponseDto clubResponse = clubService.createClub(clubRequestDto);

        ScheduleRequestDto scheduleRequestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                10000,
                100,
                LocalDateTime.now().plusHours(2)
        );
        ScheduleCreateResponseDto created = scheduleService.createSchedule(clubResponse.getClubId(), scheduleRequestDto);

        entityManager.flush();
        entityManager.clear();

        Schedule schedule = scheduleRepository
                .findByNameAndClub_ClubId("온리원의 정모", clubResponse.getClubId())
                .orElseThrow();

        // when
        ScheduleDetailResponseDto responseDto =
                scheduleService.getScheduleDetails(clubResponse.getClubId(), schedule.getScheduleId());

        // then
        assertThat(responseDto).isNotNull();
        assertThat(responseDto.getScheduleId()).isEqualTo(schedule.getScheduleId());
        assertThat(responseDto.getName()).isEqualTo("온리원의 정모");
        assertThat(responseDto.getScheduleTime()).isNotNull();
    }
}
