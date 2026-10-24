package com.airtribe.wallet.support;

import com.airtribe.wallet.llm.LlmClient;
import com.airtribe.wallet.llm.MockLlmClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test LLM: returns a fixed, scripted reply when one is set (to simulate a misbehaving or hijacked model),
 * otherwise delegates to the deterministic {@link MockLlmClient}. Records every prompt it receives.
 */
public class ScriptedLlmClient implements LlmClient {

    public record Call(String system, String user) {
    }

    private final MockLlmClient mock;
    private volatile String fixedReply;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public ScriptedLlmClient(Clock clock) {
        this.mock = new MockLlmClient(clock);
    }

    public void reply(String fixed) {
        this.fixedReply = fixed;
    }

    public void reset() {
        fixedReply = null;
        calls.clear();
    }

    public List<Call> calls() {
        return new ArrayList<>(calls);
    }

    @Override
    public String complete(String systemPrompt, String userContent) {
        calls.add(new Call(systemPrompt, userContent));
        String fixed = fixedReply;
        return fixed != null ? fixed : mock.complete(systemPrompt, userContent);
    }

    @Override
    public String name() {
        return "scripted";
    }
}
