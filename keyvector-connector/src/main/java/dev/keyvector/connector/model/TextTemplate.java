package dev.keyvector.connector.model;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What text a row is embedded as.
 *
 * <p>Either a list of columns, joined by blank lines and skipping empty ones, or a template
 * such as {@code "{title}: {overview}"}. A template naming a column the row does not have
 * renders it as nothing rather than failing, because a column can be dropped from a live
 * table and the stream must keep moving.
 */
public final class TextTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private final List<String> columns;
    private final String template;
    private final int maxChars;

    private TextTemplate(List<String> columns, String template, int maxChars) {
        this.columns = columns;
        this.template = template;
        this.maxChars = maxChars;
    }

    public static TextTemplate of(List<String> columns, String template, int maxChars) {
        boolean hasTemplate = template != null && !template.isBlank();
        boolean hasColumns = columns != null && !columns.isEmpty();
        if (hasTemplate == hasColumns) {
            throw new IllegalArgumentException("Set exactly one of sync.text-columns or sync.text-template");
        }
        if (hasTemplate && !PLACEHOLDER.matcher(template).find()) {
            throw new IllegalArgumentException("sync.text-template names no column, e.g. \"{title}: {overview}\"");
        }
        if (maxChars < 1) {
            throw new IllegalArgumentException("sync.max-text-chars must be positive");
        }
        return new TextTemplate(hasColumns ? List.copyOf(columns) : null, hasTemplate ? template : null, maxChars);
    }

    /** The text to embed, trimmed and capped; empty when the row has nothing to say. */
    public String render(Map<String, Object> row) {
        if (row == null) {
            return "";
        }
        String out;
        if (columns != null) {
            StringBuilder sb = new StringBuilder();
            for (String c : columns) {
                String s = Values.displayText(row.get(c)).trim();
                if (!s.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    sb.append(s);
                }
            }
            out = sb.toString();
        } else {
            Matcher m = PLACEHOLDER.matcher(template);
            StringBuilder sb = new StringBuilder();
            boolean anyValue = false;
            while (m.find()) {
                String s = Values.displayText(row.get(m.group(1)));
                anyValue |= !s.isBlank();
                m.appendReplacement(sb, Matcher.quoteReplacement(s));
            }
            m.appendTail(sb);
            // A template whose every column is empty renders only its punctuation; that is
            // no text, not a document consisting of ": ".
            out = anyValue ? sb.toString() : "";
        }
        out = out.trim();
        return out.length() > maxChars ? out.substring(0, maxChars) : out;
    }
}
