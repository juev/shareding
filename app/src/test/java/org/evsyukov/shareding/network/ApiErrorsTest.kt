package org.evsyukov.shareding.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiErrorsTest {
    @Test fun namesRejectedTokenAndMissingApi() {
        assertEquals("linkding rejected the API token (HTTP 401)\nInvalid token.",
            ApiErrors.message(401, """{"detail":"Invalid token."}"""))
        assertEquals("linkding rejected the API token (HTTP 403)", ApiErrors.message(403, ""))
        assertEquals("linkding API not found (HTTP 404)", ApiErrors.message(404, "  "))
    }

    @Test fun showsFieldErrorsAsText() {
        assertEquals("linkding returned HTTP 400\nurl: Enter a valid URL.\ntag_names: Too long. Not a list.",
            ApiErrors.message(400, """{"url":["Enter a valid URL."],"tag_names":["Too long.","Not a list."]}"""))
        assertEquals("linkding returned HTTP 400\nThe bookmark already exists.",
            ApiErrors.message(400, """{"non_field_errors":["The bookmark already exists."]}"""))
        assertEquals("linkding returned HTTP 400\nFirst problem.\nSecond problem.",
            ApiErrors.message(400, """["First problem.","Second problem."]"""))
    }

    @Test fun keepsBodiesThatAreNotJson() {
        assertEquals("linkding returned HTTP 502\nupstream failed", ApiErrors.message(502, "upstream failed\n"))
        assertEquals("linkding returned HTTP 500\n{broken", ApiErrors.message(500, "{broken"))
        assertEquals("linkding returned HTTP 500\n{}", ApiErrors.message(500, "{}"))
        assertEquals("linkding returned HTTP 503", ApiErrors.message(503, ""))
    }
}
