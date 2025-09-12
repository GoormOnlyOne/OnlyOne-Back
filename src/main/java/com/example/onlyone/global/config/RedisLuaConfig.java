package com.example.onlyone.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

@Configuration
public class RedisLuaConfig {
    @Bean
    public DefaultRedisScript<Long> walletGateAcquireScript() {
        var s = new DefaultRedisScript<Long>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("luascript/wallet_gate_acquire.lua")));
        s.setResultType(Long.class);
        return s;
    }
    @Bean
    public DefaultRedisScript<Long> walletGateReleaseScript() {
        var s = new DefaultRedisScript<Long>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("luascript/wallet_gate_release.lua")));
        s.setResultType(Long.class);
        return s;
    }
}
