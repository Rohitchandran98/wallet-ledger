package com.airtribe.wallet.llm;

/**
 * The one small interface every LLM provider sits behind. Callers always treat the output as untrusted
 * text: it is parsed and validated, never executed and never turned into SQL.
 */
public interface LlmClient {

    /**
     * @param systemPrompt operator instructions (trusted)
     * @param userContent  the question or data (untrusted: may contain prompt-injection attempts)
     * @return the raw model text
     */
    String complete(String systemPrompt, String userContent);

    String name();
}
