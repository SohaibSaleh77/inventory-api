package com.ims.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 CSV rendering and parsing: fields containing comma, quote, CR or
 * LF are wrapped in double quotes with embedded quotes doubled.
 */
public final class Csv {

    private Csv() {
    }

    /** Escape one field. */
    public static String escape(String field) {
        if (field == null) {
            return "";
        }
        boolean needsQuoting = field.indexOf(',') >= 0
                || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0
                || field.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }

    /** Render a header row plus data rows. */
    public static String render(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        if (header != null && !header.isEmpty()) {
            sb.append(String.join(",", header.stream().map(Csv::escape).toList()))
                    .append("\r\n");
        }
        for (List<String> row : rows) {
            sb.append(String.join(",", row.stream().map(Csv::escape).toList()))
                    .append("\r\n");
        }
        return sb.toString();
    }

    /**
     * Parse RFC 4180 CSV text into rows of fields. Handles quoted fields with
     * embedded separators and doubled quotes; CR, LF and CRLF all terminate a
     * record. A trailing terminator does not produce an empty final row.
     */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
                fieldStarted = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
                fieldStarted = true;
            } else if (c == '\r' || c == '\n') {
                // CRLF counts once
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                // skip blank lines entirely
                if (field.length() == 0 && !fieldStarted && current.isEmpty()) {
                    continue;
                }
                current.add(field.toString());
                rows.add(current);
                current = new ArrayList<>();
                field.setLength(0);
                fieldStarted = false;
            } else {
                field.append(c);
                fieldStarted = true;
            }
        }
        if (field.length() > 0 || fieldStarted || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }
}
