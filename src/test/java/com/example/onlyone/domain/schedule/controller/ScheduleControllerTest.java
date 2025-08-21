package com.example.onlyone.domain.schedule.controller;

import com.example.onlyone.domain.club.controller.ClubController;
import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.service.ClubService;
import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
import com.example.onlyone.domain.schedule.service.ScheduleService;
import com.example.onlyone.global.filter.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebMvcTest(controllers = ScheduleController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        },
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
        })
@DisplayNameGeneration(org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores.class)
public class ScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private ClubService clubService;
    @MockBean
    private ScheduleService scheduleService;
    @MockBean(JpaMetamodelMappingContext.class)
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void 정기_모임이_정상적으로_생성된다() throws Exception {
        // given
        ScheduleRequestDto requestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                10000,
                10,
                LocalDateTime.now().plusDays(1)
        );

        // when & then
        mockMvc.perform(post("/clubs/{clubId}/schedules", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isCreated());
    }

    @Test
    void 정모_이름이_20자를_초과하면_입력값_예외가_발생한다() throws Exception {
        // given
        ScheduleRequestDto requestDto = new ScheduleRequestDto(
                "온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모",
                "구름스퀘어 강남",
                10000,
                10,
                LocalDateTime.now().plusDays(1)
        );

        // when & then
        mockMvc.perform(post("/clubs/{clubId}/schedules", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.name")
                        .value("정기 모임 이름은 20자 이내여야 합니다."));
    }

    @Test
    void 금액이_0원_미만이면_입력값_예외가_발생한다() throws Exception {
        // given
        ScheduleRequestDto requestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                -10000,
                10,
                LocalDateTime.now().plusDays(1)
        );

        // when & then
        mockMvc.perform(post("/clubs/{clubId}/schedules", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.cost")
                        .value("정기 모임 금액은 0원 이상이어야 합니다."));
    }

    @Test
    void 정모_시간을_현재_이전으로_입력하면_예외가_발생한다() throws Exception {
        // given
        ScheduleRequestDto requestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                10000,
                10,
                LocalDateTime.now().minusDays(1)
        );

        // when & then
        mockMvc.perform(post("/clubs/{clubId}/schedules", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.scheduleTime")
                        .value("현재 시간 이후만 선택할 수 있습니다."));
    }

    @Test
    void 정모_정원이_1명_미만이면_입력값_예외가_발생한다() throws Exception {
        // given
        ScheduleRequestDto requestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                10000,
                -10,
                LocalDateTime.now().minusDays(1)
        );

        // when & then
        mockMvc.perform(post("/clubs/{clubId}/schedules", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.userLimit")
                        .value("정기 모임 정원은 1명 이상이어야 합니다."));
    }

    @Test
    void 정모_정원이_100명_초과면_입력값_예외가_발생한다() throws Exception {
        // given
        ScheduleRequestDto requestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                10000,
                101,
                LocalDateTime.now().minusDays(1)
        );

        // when & then
        mockMvc.perform(post("/clubs/{clubId}/schedules", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.userLimit")
                        .value("정기 모임 정원은 100명 이하여야 합니다."));
    }

    @Test
    void 정기_모임_수정시_이름이_20자를_초과하면_입력값_예외가_발생한다() throws Exception {
        // given
        ScheduleRequestDto requestDto = new ScheduleRequestDto(
                "온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모 온리원의 정모",
                "구름스퀘어 강남",
                10000,
                10,
                LocalDateTime.now().plusDays(1)
        );

        // when & then
        mockMvc.perform(patch("/clubs/{clubId}/schedules/{scheduleId}", 1L, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.name")
                        .value("정기 모임 이름은 20자 이내여야 합니다."));
    }

    @Test
    void 정기_모임_수정시_금액이_0원_미만이면_입력값_예외가_발생한다() throws Exception {
        // given
        ScheduleRequestDto requestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                -10000,
                10,
                LocalDateTime.now().plusDays(1)
        );

        // when & then
        mockMvc.perform(patch("/clubs/{clubId}/schedules/{scheduleId}", 1L, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.cost")
                        .value("정기 모임 금액은 0원 이상이어야 합니다."));
    }

    @Test
    void 정기_모임_수정시_시간을_현재_이전으로_입력하면_예외가_발생한다() throws Exception {
        // given
        ScheduleRequestDto requestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                10000,
                10,
                LocalDateTime.now().minusDays(1)
        );

        // when & then
        mockMvc.perform(patch("/clubs/{clubId}/schedules/{scheduleId}", 1L, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.scheduleTime")
                        .value("현재 시간 이후만 선택할 수 있습니다."));
    }

    @Test
    void 정기_모임_수정시_정원이_1명_미만이면_입력값_예외가_발생한다() throws Exception {
        // given
        ScheduleRequestDto requestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                10000,
                -10,
                LocalDateTime.now().plusDays(1)
        );

        // when & then
        mockMvc.perform(patch("/clubs/{clubId}/schedules/{scheduleId}", 1L, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.userLimit")
                        .value("정기 모임 정원은 1명 이상이어야 합니다."));
    }

    @Test
    void 정기_모임_수정시_정원이_100명_초과면_입력값_예외가_발생한다() throws Exception {
        // given
        ScheduleRequestDto requestDto = new ScheduleRequestDto(
                "온리원의 정모",
                "구름스퀘어 강남",
                10000,
                101,
                LocalDateTime.now().plusDays(1)
        );

        // when & then
        mockMvc.perform(patch("/clubs/{clubId}/schedules/{scheduleId}", 1L, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest()) // HTTP 400
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"))
                .andExpect(jsonPath("$.data.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.data.validation.userLimit")
                        .value("정기 모임 정원은 100명 이하여야 합니다."));
    }


}
