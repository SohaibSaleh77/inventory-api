package com.ims.unit;

import com.ims.common.util.Csv;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RFC 4180 parsing: quoting, embedded separators, line endings. */
class CsvParseTest {

    @Test
    void parsesSimpleRows() {
        List<List<String>> rows = Csv.parse("a,b,c\r\n1,2,3\r\n");
        assertEquals(List.of(List.of("a", "b", "c"), List.of("1", "2", "3")), rows);
    }

    @Test
    void acceptsLfOnlyLineEndings() {
        List<List<String>> rows = Csv.parse("a,b\n1,2\n3,4");
        assertEquals(3, rows.size());
        assertEquals("4", rows.get(2).get(1));
    }

    @Test
    void trailingTerminatorYieldsNoEmptyRow() {
        assertEquals(1, Csv.parse("only\r\n").size());
        assertEquals(1, Csv.parse("only").size());
        assertEquals(0, Csv.parse("").size());
    }

    @Test
    void quotedFieldsKeepCommasAndQuotes() {
        List<List<String>> rows = Csv.parse("\"a,b\",\"say \"\"hi\"\"\",plain");
        assertEquals(List.of("a,b", "say \"hi\"", "plain"), rows.get(0));
    }

    @Test
    void quotedFieldsMaySpanLines() {
        List<List<String>> rows = Csv.parse("\"line1\nline2\",x\r\nnext,y");
        assertEquals(2, rows.size());
        assertEquals("line1\nline2", rows.get(0).get(0));
        assertEquals("x", rows.get(0).get(1));
        assertEquals("next", rows.get(1).get(0));
    }

    @Test
    void skipsBlankLines() {
        List<List<String>> rows = Csv.parse("a,b\r\n\r\n1,2\r\n");
        assertEquals(2, rows.size());
    }

    @Test
    void singleColumnRowsWork() {
        List<List<String>> rows = Csv.parse("sku\r\nABC\r\nDEF\r\n");
        assertEquals(3, rows.size());
        assertEquals("DEF", rows.get(2).get(0));
    }

    @Test
    void emptyTrailingFieldIsPreserved() {
        List<List<String>> rows = Csv.parse("a,b,\r\n1,,3");
        assertEquals("", rows.get(0).get(2));
        assertEquals("", rows.get(1).get(1));
    }

    @Test
    void roundTripsThroughEscape() {
        String awkward = "comma, quote \" newline\nand CR\r";
        String rendered = Csv.render(null, List.of(List.of(awkward, "simple")));
        List<List<String>> parsed = Csv.parse(rendered);
        assertEquals(awkward, parsed.get(0).get(0));
        assertEquals("simple", parsed.get(0).get(1));
    }
}
