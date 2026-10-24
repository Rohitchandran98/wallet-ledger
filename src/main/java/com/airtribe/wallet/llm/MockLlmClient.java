package com.airtribe.wallet.llm;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline stand-in for a real model. Deterministic, no network, no API key. It answers the two prompts
 * the app sends:
 * <ul>
 *   <li>statement Q&A — turns a question into {"type","counterparty","from","to"} with simple rules</li>
 *   <li>flag explanation — returns a fixed two-sentence explanation and label per rule</li>
 * </ul>
 * Like a real model, it only ever returns text; the callers validate it.
 */
public class MockLlmClient implements LlmClient {

    public static final String QA_MARKER = "[task:statement-question-to-json]";
    public static final String EXPLAIN_MARKER = "[task:explain-risk-flag]";

    private static final Pattern ISO_RANGE =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s*(?:and|to|until|-)\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern LAST_N_DAYS = Pattern.compile("last\\s+(\\d{1,3})\\s+days");
    private static final Pattern MONTH_NAME = Pattern.compile(
            "\\b(january|february|march|april|may|june|july|august|september|october|november|december)\\b(?:\\s+(\\d{4}))?");
    private static final Pattern COUNTERPARTY =
            Pattern.compile("\\b(?:to|from|with)\\s+([A-Z][a-zA-Z.'-]+(?:\\s+[A-Z][a-zA-Z.'-]+)?)");
    private static final Set<String> NOT_NAMES = Set.of("January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December", "Last", "This", "Today", "Yesterday");

    private final Clock clock;

    public MockLlmClient(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String complete(String systemPrompt, String userContent) {
        if (systemPrompt.contains(EXPLAIN_MARKER)) {
            return explain(userContent);
        }
        if (systemPrompt.contains(QA_MARKER)) {
            return parseQuestion(userContent);
        }
        return "{}";
    }

    @Override
    public String name() {
        return "mock";
    }

    private String explain(String data) {
        if (data.contains("\"VELOCITY\"")) {
            return "{\"explanation\":\"The wallet sent more transfers in a short window than the velocity rule allows. "
                    + "Bursts like this can mean a compromised account, so a human should look before release.\","
                    + "\"label\":\"review\"}";
        }
        if (data.contains("\"AMOUNT_SPIKE\"")) {
            return "{\"explanation\":\"The amount is far above this wallet's recent average transfer. "
                    + "A sudden spike is a common sign of account takeover, though it can also be a one-off purchase.\","
                    + "\"label\":\"review\"}";
        }
        return "{\"explanation\":\"This is the first payment to this recipient and it is above the large-amount threshold. "
                + "Nothing else in the recent history looks unusual, so it is probably legitimate.\","
                + "\"label\":\"likely_ok\"}";
    }

    private String parseQuestion(String prompt) {
        // the app sends "Today is ...\nQuestion: <text>"; only the question itself is interpreted
        int at = prompt.lastIndexOf("Question:");
        String question = (at >= 0 ? prompt.substring(at + "Question:".length()) : prompt).replace('\n', ' ').strip();
        String q = question.toLowerCase(Locale.ROOT);
        Integer type;
        if (q.matches(".*\\b(how many|number of|count)\\b.*")) {
            type = 4;
        } else if (q.matches(".*\\b(largest|biggest|highest|maximum|max)\\b.*")) {
            type = 3;
        } else if (q.matches(".*\\b(receive|received|receiving|got|credited|earn|earned)\\b.*")) {
            type = 2;
        } else if (q.matches(".*\\b(send|sent|paid|pay|spent|spend|transferred)\\b.*")) {
            type = 1;
        } else {
            return "{\"type\":null}";
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(30);
        LocalDate to = today;
        Matcher m;
        if ((m = ISO_RANGE.matcher(q)).find()) {
            from = LocalDate.parse(m.group(1));
            to = LocalDate.parse(m.group(2));
        } else if (q.contains("last month")) {
            YearMonth last = YearMonth.from(today).minusMonths(1);
            from = last.atDay(1);
            to = last.atEndOfMonth();
        } else if (q.contains("this month")) {
            from = today.withDayOfMonth(1);
        } else if (q.contains("last week")) {
            from = today.minusDays(7);
        } else if (q.contains("yesterday")) {
            from = today.minusDays(1);
            to = from;
        } else if (q.contains("today")) {
            from = today;
        } else if (q.contains("this year")) {
            from = today.withDayOfYear(1);
        } else if ((m = LAST_N_DAYS.matcher(q)).find()) {
            from = today.minusDays(Integer.parseInt(m.group(1)));
        } else if ((m = MONTH_NAME.matcher(q)).find()) {
            Month month = Month.valueOf(m.group(1).toUpperCase(Locale.ROOT));
            int year = m.group(2) != null ? Integer.parseInt(m.group(2))
                    : (month.getValue() > today.getMonthValue() ? today.getYear() - 1 : today.getYear());
            YearMonth ym = YearMonth.of(year, month);
            from = ym.atDay(1);
            to = ym.atEndOfMonth();
        }

        String counterparty = null;
        m = COUNTERPARTY.matcher(question);
        while (m.find()) {
            String candidate = m.group(1).trim();
            String first = candidate.split("\\s+")[0];
            if (!NOT_NAMES.contains(first)) {
                counterparty = candidate.split("\\s+").length > 1
                        && NOT_NAMES.contains(candidate.split("\\s+")[1]) ? first : candidate;
                break;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("counterparty", counterparty);
        out.put("from", from.toString());
        out.put("to", to.toString());
        StringBuilder sb = new StringBuilder("{");
        out.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(k).append("\":");
            sb.append(v == null ? "null" : v instanceof Integer ? v.toString() : "\"" + v + "\"");
        });
        return sb.append('}').toString();
    }
}
