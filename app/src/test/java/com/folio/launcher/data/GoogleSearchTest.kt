package com.folio.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleSearchTest {
    @Test
    fun package_isGoogleApp() {
        assertEquals("com.google.android.googlequicksearchbox", GoogleSearch.PACKAGE)
    }
}
