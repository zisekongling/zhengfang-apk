package com.tyust.course.academic.plugin

import org.junit.Assert.*
import org.junit.Test

class BrowserComponentTest {
    @Test fun originRuleIsExactSchemeAndAuthority() {
        val origin = "https://mooc1.chaoxing.com"
        assertTrue(BrowserComponent.owns(origin, "https://mooc1.chaoxing.com/mycourse?x=1"))
        for (url in listOf("http://mooc1.chaoxing.com/", "https://mooc1.chaoxing.com:8443/", "https://evil.test@mooc1.chaoxing.com/",
            "https://mooc1.chaoxing.com.evil.test/", "https://x.mooc1.chaoxing.com/", "not a url"))
            assertFalse(url, BrowserComponent.owns(origin, url))
    }

    @Test fun profileNamesAreTheHashedFormOnly() {
        assertTrue(("plugin_" + "a".repeat(64)).matches(BrowserComponent.PROFILE))
        assertFalse(("plugin_" + "a".repeat(63)).matches(BrowserComponent.PROFILE))
        assertFalse("../plugin_x".matches(BrowserComponent.PROFILE))
    }
}
