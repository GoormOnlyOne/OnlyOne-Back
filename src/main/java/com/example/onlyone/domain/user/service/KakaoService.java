package com.example.onlyone.domain.user.service;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import java.util.Map;

@Service
@Log4j2
public class KakaoService {
    @Value("${kakao.client.id}")
    private String clientId;

    @Value("${kakao.redirect.uri}")
    private String redirectUri;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String getAccessToken(String code) throws Exception {
        String tokenUrl = "https://kauth.kakao.com/oauth/token";

        try {
            // 요청 파라미터 설정
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type", "authorization_code");
            params.add("client_id", clientId);
            params.add("redirect_uri", redirectUri);
            params.add("code", code);

            // 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            // 요청 보내기
            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(tokenUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new CustomException(ErrorCode.KAKAO_API_ERROR);
            }

            // 응답 파싱
            Map<String, Object> responseMap = objectMapper.readValue(response.getBody(), Map.class);

            if (responseMap.containsKey("error")) {
                throw new CustomException(ErrorCode.KAKAO_AUTH_FAILED);
            }

            return (String) responseMap.get("access_token");
        } catch (RestClientException e) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    public Map<String, Object> getUserInfo(String accessToken) throws Exception {
        String userInfoUrl = "https://kapi.kakao.com/v2/user/me";

        try {
            // 헤더에 액세스 토큰 추가
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);

            // 요청 보내기
            HttpEntity<String> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    userInfoUrl,
                    HttpMethod.GET,
                    request,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new CustomException(ErrorCode.KAKAO_API_ERROR);
            }

            // 응답 파싱
            Map<String, Object> responseMap = objectMapper.readValue(response.getBody(), Map.class);

            if (responseMap.containsKey("error")) {
                throw new CustomException(ErrorCode.KAKAO_AUTH_FAILED);
            }

            return responseMap;
        } catch (RestClientException e) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }
}