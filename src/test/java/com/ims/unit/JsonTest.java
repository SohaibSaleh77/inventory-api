package com.ims.unit;

import com.ims.common.exception.BadRequestException;
import com.ims.common.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON facade behaviour incl. malformed-input handling. */
class JsonTest {

    public record Sample(String name, int count, List<String> tags) {
    }

    @Test
    void writeAndReadRoundTrip() {
        Sample sample = new Sample("abc", 7, List.of("x", "y"));
        String json = Json.write(sample);
        Sample back = Json.read(json, Sample.class);
        assertEquals(sample, back);
    }

    @Test
    void readTreeToleratesWhitespace() {
        JsonNode node = Json.readTree("  { \"a\" : 1 } ");
        assertEquals(1, node.get("a").asInt());
    }

    @Test
    void malformedJsonThrowsBadRequest() {
        assertThrows(BadRequestException.class, () -> Json.read("{not json", Sample.class));
        assertThrows(BadRequestException.class, () -> Json.readTree("[broken"));
    }

    @Test
    void unknownFieldsAreIgnored() {
        Sample sample = Json.read(
                "{\"name\":\"n\",\"count\":1,\"tags\":[],\"surprise\":true}", Sample.class);
        assertEquals("n", sample.name());
    }

    @Test
    void snapshotSerializesMaps() {
        String json = Json.snapshot(Map.of("k", "v"));
        assertTrue(json.contains("\"k\""));
        assertEquals(null, Json.snapshot(null));
    }

    @Test
    void numbersKeepBigDecimalSemantics() {
        JsonNode node = Json.readTree("{\"value\": 1234.50}");
        // JSON numbers do not preserve trailing zeros textually; the numeric
        // value and scale-independent comparison must hold
        assertEquals(0, new BigDecimal("1234.50")
                .compareTo(node.get("value").decimalValue()));
    }

    @Test
    void prettyPrintProducesMultiline() {
        String pretty = Json.writePretty(Map.of("a", 1));
        assertTrue(pretty.contains("\n"));
    }

    @Test
    void toMapConvertsRecords() {
        Map<String, Object> map = Json.toMap(new Sample("n", 1, List.of()));
        assertNotNull(map);
        assertEquals("n", map.get("name"));
    }
}
