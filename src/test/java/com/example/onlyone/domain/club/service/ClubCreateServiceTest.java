package com.example.onlyone.domain.club.service;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.notification.service.FcmService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import jakarta.transaction.Transactional;
import org.checkerframework.checker.units.qual.A;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("test")   // application-test.yml 적용 // 테스트 끝나면 롤백
@DataJpaTest
@Import({ClubService.class, UserService.class})
class ClubCreateServiceTest {

    @Autowired
    private ClubService clubService;
    @MockBean
    private UserService userService;

    @Autowired
    private ClubRepository clubRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InterestRepository interestRepository;
    @Autowired
    private UserClubRepository userClubRepository;
    @Autowired
    private ChatRoomRepository chatRoomRepository;
    @Autowired
    private UserChatRoomRepository  userChatRoomRepository;

    @Test
    void 리더는_모임을_정상_생성한다() {
        // given
        User user = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(user);

        String name = "온리원 첫 번째 모임";
        int userLimit = 10;
        String description = "테스트 코드 시작합시다";
        String city = "서울특별시";
        String district = "강남구";
        String category = "EXERCISE";

        ClubRequestDto requestDto =
                new ClubRequestDto(name, userLimit, description, null, city, district, category);

        // when
        ClubCreateResponseDto responseDto = clubService.createClub(requestDto);

        // then
        assertThat(responseDto).isNotNull();
        assertThat(responseDto.getClubId()).isNotNull();

        // DB에 실제 저장된 값 검증
        Club club = clubRepository.findById(responseDto.getClubId()).orElseThrow();
        assertThat(club.getName()).isEqualTo(name);
        assertThat(club.getUserLimit()).isEqualTo(userLimit);
        assertThat(club.getCity()).isEqualTo(city);
        assertThat(club.getDistrict()).isEqualTo(district);
        assertThat(club.getInterest().getCategory())
                .isEqualTo(Category.from(requestDto.getCategory()));
    }

    @Test
    void 모임을_생성하면_UserClub에_리더역할이_부여된다() {
        // given
        User user = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(user);

        ClubRequestDto requestDto =
                new ClubRequestDto(
                        "리더 모임 테스트",
                        5,
                        "리더 검증",
                        null,
                        "서울특별시",
                        "강남구",
                        "EXERCISE"
                );

        // when
        ClubCreateResponseDto responseDto = clubService.createClub(requestDto);

        // then
        Club club = clubRepository.findById(responseDto.getClubId()).orElseThrow();
        UserClub userClub = userClubRepository.findByUserAndClub(user, club)
                .orElseThrow(() -> new IllegalStateException("UserClub이 생성되지 않았습니다."));

        assertThat(userClub.getUser().getUserId()).isEqualTo(user.getUserId());
        assertThat(userClub.getClub().getClubId()).isEqualTo(responseDto.getClubId());
        assertThat(userClub.getClubRole().name()).isEqualTo("LEADER"); // enum 값 검증
    }

    @Test
    void 모임을_생성하면_전체_채팅방이_생성된다() {
        // given
        User user = userRepository.findById(1L).orElse(null);
        Mockito.when(userService.getCurrentUser()).thenReturn(user);

        ClubRequestDto requestDto =
                new ClubRequestDto(
                        "리더 모임 테스트",
                        5,
                        "리더 검증",
                        null,
                        "서울특별시",
                        "강남구",
                        "EXERCISE"
                );

        // when
        ClubCreateResponseDto responseDto = clubService.createClub(requestDto);

        // then
        Club club = clubRepository.findById(responseDto.getClubId()).orElseThrow();
        ChatRoom chatRoom = chatRoomRepository.findByTypeAndClub_ClubId(Type.CLUB, club.getClubId()).orElseThrow();
        UserChatRoom userChatRoom = userChatRoomRepository.findByUserUserIdAndChatRoomChatRoomId(user.getUserId(), chatRoom.getChatRoomId()).orElseThrow();

        assertThat(chatRoom.getClub().getClubId()).isEqualTo(responseDto.getClubId());
        assertThat(userChatRoom.getUser().getUserId()).isEqualTo(user.getUserId());
        assertThat(userChatRoom.getChatRole().name()).isEqualTo("LEADER");
    }

}