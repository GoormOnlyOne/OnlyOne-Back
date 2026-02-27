package com.example.onlyone.domain.user.service;

import com.example.onlyone.domain.user.exception.UserErrorCode;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.GlobalErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@Slf4j
public class KakaoService {

    private final String clientId;
    private final String redirectUri;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public KakaoService(
            @Value("${kakao.client.id}") String clientId,
            @Value("${kakao.redirect.uri}") String redirectUri,
            RestTemplate kakaoRestTemplate,
            ObjectMapper objectMapper) {
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.restTemplate = kakaoRestTemplate;
        this.objectMapper = objectMapper;
    }

    public String getAccessToken(String code) {
        String tokenUrl = "https://kauth.kakao.com/oauth/token";

        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type", "authorization_code");
            params.add("client_id", clientId);
            params.add("redirect_uri", redirectUri);
            params.add("code", code);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(tokenUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new CustomException(UserErrorCode.KAKAO_API_ERROR);
            }

            Map<String, Object> responseMap = objectMapper.readValue(response.getBody(), Map.class);

            if (responseMap.containsKey("error")) {
                throw new CustomException(UserErrorCode.KAKAO_AUTH_FAILED);
            }

            return (String) responseMap.get("access_token");
        } catch (RestClientException e) {
            log.warn("카카오 토큰 요청 실패: {}", e.getMessage());
            throw new CustomException(GlobalErrorCode.EXTERNAL_API_ERROR);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.warn("카카오 토큰 요청 중 예외 발생: {}", e.getMessage());
            throw new CustomException(UserErrorCode.KAKAO_API_ERROR);
        }
    }

    public Map<String, Object> getUserInfo(String accessToken) {
        String userInfoUrl = "https://kapi.kakao.com/v2/user/me";

        try {
            HttpEntity<String> request = new HttpEntity<>(createBearerHeaders(accessToken));
            ResponseEntity<String> response = restTemplate.exchange(
                    userInfoUrl, HttpMethod.GET, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new CustomException(UserErrorCode.KAKAO_API_ERROR);
            }

            Map<String, Object> responseMap = objectMapper.readValue(response.getBody(), Map.class);

            if (responseMap.containsKey("error")) {
                throw new CustomException(UserErrorCode.KAKAO_AUTH_FAILED);
            }

            return responseMap;
        } catch (RestClientException e) {
            throw new CustomException(GlobalErrorCode.EXTERNAL_API_ERROR);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(UserErrorCode.KAKAO_API_ERROR);
        }
    }

    public void logout(String accessToken) {
        String logoutUrl = "https://kapi.kakao.com/v1/user/logout";
        HttpEntity<String> request = new HttpEntity<>(createBearerHeaders(accessToken));
        restTemplate.exchange(logoutUrl, HttpMethod.POST, request, String.class);
    }

    public void unlink(String accessToken) {
        String unlinkUrl = "https://kapi.kakao.com/v1/user/unlink";
        HttpEntity<String> request = new HttpEntity<>(createBearerHeaders(accessToken));
        restTemplate.exchange(unlinkUrl, HttpMethod.POST, request, String.class);
    }

    // ========== PRIVATE HELPERS ==========

    private HttpHeaders createBearerHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
