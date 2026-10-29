package com.ims.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** If-None-Match matching per RFC 9110. */
class EtagSupportTest {

    private static final String ETAG = "\"abc123\"";

    @Test
    void exactMatch() {
        assertTrue(WebContext.etagMatches(ETAG, ETAG));
    }

    @Test
    void weakVariantMatches() {
        assertTrue(WebContext.etagMatches("W/" + ETAG, ETAG));
    }

    @Test
    void listOfEtagsMatches() {
        assertTrue(WebContext.etagMatches("\"zzz\", " + ETAG + " , \"aaa\"", ETAG));
    }

    @Test
    void wildcardMatches() {
        assertTrue(WebContext.etagMatches("*", ETAG));
    }

    @Test
    void differentEtagDoesNotMatch() {
        assertFalse(WebContext.etagMatches("\"other\"", ETAG));
    }

    @Test
    void prefixDoesNotMatch() {
        assertFalse(WebContext.etagMatches("\"abc12\"", ETAG));
    }

    @Test
    void nullOrBlankNeverMatches() {
        assertFalse(WebContext.etagMatches(null, ETAG));
        assertFalse(WebContext.etagMatches("", ETAG));
        assertFalse(WebContext.etagMatches("   ", ETAG));
    }

    @Test
    void weakListEntriesMatch() {
        assertTrue(WebContext.etagMatches("W/\"x\", W/" + ETAG, ETAG));
    }
}
