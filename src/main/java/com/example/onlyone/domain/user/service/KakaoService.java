package com.example.onlyone.domain.user.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

@Service
public class KakaoService {
    @Value("${kakao.client.id}")
    private String clientId;

    @Value("${kakao.redirect.uri}")
    private String redirectUri;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String getAccessToken(String code) throws Exception {
        String tokenUrl = "https://kauth.kakao.com/oauth/token";

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

        // 응답 파싱
        Map<String, Object> responseMap = objectMapper.readValue(response.getBody(), Map.class);
        return (String) responseMap.get("access_token");
    }

    public Map<String, Object> getUserInfo(String accessToken) throws Exception {
        String userInfoUrl = "https://kapi.kakao.com/v2/user/me";

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

        // 응답 파싱
        return objectMapper.readValue(response.getBody(), Map.class);
    }
}
