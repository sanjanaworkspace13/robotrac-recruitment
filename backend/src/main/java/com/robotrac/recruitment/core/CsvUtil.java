package com.robotrac.recruitment.core;

import java.util.ArrayList;
import java.util.List;

/** Small, dependency-free CSV reader/writer with spreadsheet formula-injection protection. */
public final class CsvUtil {

    private CsvUtil() {
    }

    /** Parses CSV text (RFC 4180 style quotes, CRLF or LF). A leading UTF-8 BOM is ignored. */
    public static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null) {
            return rows;
        }
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasData = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
                rowHasData = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                rowHasData = true;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (rowHasData || cell.length() > 0) {
                    row.add(cell.toString());
                    rows.add(row);
                } else {
                    rows.add(new ArrayList<>()); // keep blank lines so line numbers stay right
                }
                row = new ArrayList<>();
                cell.setLength(0);
                rowHasData = false;
            } else {
                cell.append(c);
                rowHasData = true;
            }
        }
        if (rowHasData || cell.length() > 0) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /**
     * Escapes one cell for export. Cells starting with = + - @ tab or CR are prefixed with an apostrophe so
     * Excel/Sheets treat them as text (prevents CSV formula injection).
     */
    public static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(value instanceof Number)) {
            s = "'" + s;
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static String row(Object... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(values[i]));
        }
        return sb.append("\r\n").toString();
    }
}
