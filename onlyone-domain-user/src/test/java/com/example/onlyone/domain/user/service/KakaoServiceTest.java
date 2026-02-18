package com.example.onlyone.domain.user.service;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KakaoService 단위 테스트")
class KakaoServiceTest {

    private KakaoService kakaoService;

    @Mock
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        kakaoService = new KakaoService("test-client-id", "http://localhost/callback",
                restTemplate, objectMapper);
    }

    // =========================================================================
    // getAccessToken
    // =========================================================================

    @Nested
    @DisplayName("getAccessToken")
    class GetAccessToken {

        @Test
        @DisplayName("성공: 카카오 토큰 발급")
        void success() {
            // given
            String responseBody = "{\"access_token\":\"kakao-access-token-123\"}";
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // when
            String token = kakaoService.getAccessToken("auth-code");

            // then
            assertThat(token).isEqualTo("kakao-access-token-123");
        }

        @Test
        @DisplayName("실패: 카카오 응답에 error 포함 → KAKAO_AUTH_FAILED")
        void errorResponse() {
            // given
            String responseBody = "{\"error\":\"invalid_grant\",\"error_description\":\"authorization code not found\"}";
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // when & then
            assertThatThrownBy(() -> kakaoService.getAccessToken("bad-code"))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.KAKAO_AUTH_FAILED);
        }

        @Test
        @DisplayName("실패: RestClientException → EXTERNAL_API_ERROR")
        void restClientException() {
            // given
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RestClientException("connection refused"));

            // when & then
            assertThatThrownBy(() -> kakaoService.getAccessToken("code"))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    // =========================================================================
    // getUserInfo
    // =========================================================================

    @Nested
    @DisplayName("getUserInfo")
    class GetUserInfo {

        @Test
        @DisplayName("성공: 사용자 정보 반환")
        void success() {
            // given
            String responseBody = "{\"id\":12345,\"properties\":{\"nickname\":\"test\"}}";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // when
            Map<String, Object> userInfo = kakaoService.getUserInfo("access-token");

            // then
            assertThat(userInfo).containsKey("id");
            assertThat(((Number) userInfo.get("id")).longValue()).isEqualTo(12345L);
        }

        @Test
        @DisplayName("실패: 카카오 응답에 error 포함 → KAKAO_AUTH_FAILED")
        void errorResponse() {
            // given
            String responseBody = "{\"error\":\"invalid_token\"}";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // when & then
            assertThatThrownBy(() -> kakaoService.getUserInfo("bad-token"))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.KAKAO_AUTH_FAILED);
        }

        @Test
        @DisplayName("실패: RestClientException → EXTERNAL_API_ERROR")
        void restClientException() {
            // given
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RestClientException("timeout"));

            // when & then
            assertThatThrownBy(() -> kakaoService.getUserInfo("token"))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    // =========================================================================
    // unlink
    // =========================================================================

    @Nested
    @DisplayName("unlink")
    class Unlink {

        @Test
        @DisplayName("성공: Bearer 헤더로 카카오 unlink API 호출")
        @SuppressWarnings("unchecked")
        void success() {
            // given
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("{\"id\":12345}", HttpStatus.OK));

            // when
            kakaoService.unlink("access-token-123");

            // then
            ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                    eq("https://kapi.kakao.com/v1/user/unlink"),
                    eq(HttpMethod.POST),
                    captor.capture(),
                    eq(String.class));

            String authHeader = captor.getValue().getHeaders().getFirst("Authorization");
            assertThat(authHeader).isEqualTo("Bearer access-token-123");
        }
    }

    // =========================================================================
    // logout
    // =========================================================================

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("성공: Bearer 헤더로 카카오 logout API 호출")
        @SuppressWarnings("unchecked")
        void success() {
            // given
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("{\"id\":12345}", HttpStatus.OK));

            // when
            kakaoService.logout("access-token-456");

            // then
            ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                    eq("https://kapi.kakao.com/v1/user/logout"),
                    eq(HttpMethod.POST),
                    captor.capture(),
                    eq(String.class));

            String authHeader = captor.getValue().getHeaders().getFirst("Authorization");
            assertThat(authHeader).isEqualTo("Bearer access-token-456");
        }
    }
}
