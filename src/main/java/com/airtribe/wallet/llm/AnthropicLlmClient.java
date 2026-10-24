package com.airtribe.wallet.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import java.util.stream.Collectors;

/** Claude via the official Anthropic Java SDK. Enabled with wallet.llm.provider=anthropic + ANTHROPIC_API_KEY. */
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final String model;

    public AnthropicLlmClient(String apiKey, String model) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
    }

    @Override
    public String complete(String systemPrompt, String userContent) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(2048L)
                // small, well-specified extraction task: low effort keeps latency and cost down
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .system(systemPrompt)
                .addUserMessage(userContent)
                .build();
        Message response = client.messages().create(params);
        if (response.stopReason().map(r -> "refusal".equals(r.asString())).orElse(false)) {
            return ""; // treated as an unparseable answer by every caller
        }
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
    }

    @Override
    public String name() {
        return "anthropic:" + model;
    }
}
