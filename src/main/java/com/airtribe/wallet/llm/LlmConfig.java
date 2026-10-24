package com.airtribe.wallet.llm;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.system(ZONE);
    }

    /**
     * provider=mock (default): deterministic, offline, no API key — used by tests, CI and the demo.
     * provider=anthropic: real Claude calls.
     */
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient llmClient(@Value("${wallet.llm.provider:mock}") String provider,
                               @Value("${wallet.llm.anthropic.api-key:}") String apiKey,
                               @Value("${wallet.llm.anthropic.model:claude-opus-5-5}") String model,
                               Clock clock) {
        if ("anthropic".equalsIgnoreCase(provider)) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("wallet.llm.provider=anthropic needs ANTHROPIC_API_KEY");
            }
            return new AnthropicLlmClient(apiKey, model);
        }
        return new MockLlmClient(clock);
    }
}
