package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonConfigurationPanelTest {
    @Test
    fun stremioLinksAreReadAsHttpsAddresses() {
        assertEquals("https://addon.example/abc/manifest.json", normalizeAddonAddress("  stremio://addon.example/abc/manifest.json "))
        assertEquals("https://addon.example/manifest.json", normalizeAddonAddress("https://addon.example/manifest.json"))
    }

    @Test
    fun theSameAddressIsRecognisedHoweverItIsWritten() {
        assertTrue(sameAddonAddress("stremio://Addon.example/manifest.json", "https://addon.example/manifest.json/"))
        assertFalse(sameAddonAddress("https://addon.example/a/manifest.json", "https://addon.example/b/manifest.json"))
    }

    @Test
    fun aParserReportBecomesASentence() {
        val sentence = addonAddressProblem("Unexpected JSON token at offset 23: Expected EOF after parsing\nJSON input: <html>")
        assertTrue(sentence.startsWith("What answers at that address is not an addon."))
        assertFalse(sentence.contains("offset"))
        assertEquals("Connection refused", addonAddressProblem("Connection refused\nat somewhere"))
        assertTrue(addonAddressProblem(null).startsWith("What answers"))
    }
}
