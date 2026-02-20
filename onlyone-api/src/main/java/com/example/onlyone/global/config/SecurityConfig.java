package com.example.onlyone.global.config;

import com.example.onlyone.global.filter.JwtAuthenticationFilter;
import com.example.onlyone.global.filter.RateLimitFilter;
import com.example.onlyone.global.filter.SseAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;


@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] AUTH_WHITELIST = {
            "/api/v1/signup/**",
            "/api/v1/login/**",
            "/api/v1/token",
            "/api/v1/center",
            "/api/v1/email/**",
            "/ws/**",
            "/ws-native",
            "/api/v1/kakao/**",
            "/api/v1/auth/kakao/callback",
            "/api/v1/auth/refresh",
    };

    private static final String[] SWAGGER_PATHS = {
            "/swagger-ui/**", "/swagger-ui.html",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger.html",
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SseAuthenticationFilter sseAuthenticationFilter;
    @Nullable
    private final RateLimitFilter rateLimitFilter;
    @Value("${app.cors.allowed-origins:http://localhost:8080,http://localhost:5173}")
    private String[] corsAllowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(corsAllowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With", "Cache-Control"));
        configuration.setExposedHeaders(List.of("Set-Cookie", "Location"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .deleteCookies("refreshToken")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.OK)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(sseAuthenticationFilter,
                        JwtAuthenticationFilter.class);

        if (rateLimitFilter != null) {
            http.addFilterBefore(rateLimitFilter, SseAuthenticationFilter.class);
        }

        http.sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(header -> header.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(AUTH_WHITELIST).permitAll()
                        .requestMatchers("/ws-native/**").permitAll()
                        .requestMatchers("/actuator/prometheus", "/actuator/health", "/actuator/info", "/actuator/metrics/**").permitAll()
                        .requestMatchers("/error", "/favicon.ico").permitAll()
                        .requestMatchers(SWAGGER_PATHS).permitAll()
                        .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                        .anyRequest().authenticated()
                );

        return http.build();
    }

    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter f) {
        var reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    FilterRegistrationBean<SseAuthenticationFilter> sseFilterRegistration(SseAuthenticationFilter f) {
        var reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }
}
