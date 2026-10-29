package com.ims.unit;

import com.ims.common.util.Csv;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RFC 4180 CSV rendering. */
class CsvTest {

    @Test
    void plainFieldsNeedNoQuoting() {
        assertEquals("abc", Csv.escape("abc"));
    }

    @Test
    void nullRendersAsEmpty() {
        assertEquals("", Csv.escape(null));
    }

    @Test
    void commasAreQuoted() {
        assertEquals("\"a,b\"", Csv.escape("a,b"));
    }

    @Test
    void quotesAreDoubledAndQuoted() {
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""));
    }

    @Test
    void newlinesAreQuoted() {
        assertEquals("\"line1\nline2\"", Csv.escape("line1\nline2"));
        assertEquals("\"line1\r\nline2\"", Csv.escape("line1\r\nline2"));
    }

    @Test
    void renderProducesCrlfRows() {
        String csv = Csv.render(List.of("a", "b"),
                List.of(List.of("1", "2"), List.of("3", "4")));
        assertEquals("a,b\r\n1,2\r\n3,4\r\n", csv);
    }

    @Test
    void renderWithNullHeader() {
        String csv = Csv.render(null, List.of(List.of("x")));
        assertEquals("x\r\n", csv);
    }

    @Test
    void injectionPayloadsStayConfinedToTheirCell() {
        String evil = "=cmd|' /C calc'!A0\r\n+1,2";
        // the whole payload - including the embedded CRLF and comma - is one
        // quoted cell, so spreadsheet apps treat it as data, not a formula
        assertEquals("\"=cmd|' /C calc'!A0\r\n+1,2\"", Csv.escape(evil));
    }
}
