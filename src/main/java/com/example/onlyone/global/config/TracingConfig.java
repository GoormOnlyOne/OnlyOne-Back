package com.example.onlyone.global.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Jaeger 분산 트레이싱 설정
 * 각 메서드별 실행 시간 및 호출 체인 추적
 */
@Slf4j
@Configuration
@EnableAspectJAutoProxy
public class TracingConfig {

    /**
     * @Observed 어노테이션 지원을 위한 AspectJ 설정
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * 모든 관찰 대상에 대한 커스텀 설정
     */
    @Bean
    public ObservationRegistryCustomizer<ObservationRegistry> observationRegistryCustomizer() {
        return registry -> {
            registry.observationConfig()
                    .observationHandler(new io.micrometer.observation.ObservationHandler<>() {
                        @Override
                        public void onStart(io.micrometer.observation.Observation.Context context) {
                            log.trace("Observation started: {}", context.getName());
                        }

                        @Override
                        public void onStop(io.micrometer.observation.Observation.Context context) {
                            log.trace("Observation stopped: {}", context.getName());
                        }

                        @Override
                        public void onError(io.micrometer.observation.Observation.Context context) {
                            log.warn("Observation error: {}", context.getError());
                        }

                        @Override
                        public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                            return true;
                        }
                    });
        };
    }

    /**
     * 트레이싱 로깅 설정
     */
    @Bean
    public ObservationRegistryCustomizer<ObservationRegistry> tracingLoggingCustomizer(Tracer tracer) {
        return registry -> {
            log.info("🔍 Jaeger Tracing initialized");
            log.info("📊 Tracer: {}", tracer.getClass().getSimpleName());
            log.info("🌐 Jaeger UI: http://localhost:16686");
            log.info("📡 Service Name: onlyone-backend");
        };
    }
}