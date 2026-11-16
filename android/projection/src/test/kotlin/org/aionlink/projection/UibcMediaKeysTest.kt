package org.aionlink.projection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UibcMediaKeysTest {
    @Test fun mediaCodesRecognised() {
        for (code in intArrayOf(85, 86, 87, 88, 89, 90, 126, 127)) assertTrue(code.toString(), UibcMediaKeys.isMedia(code))
    }

    @Test fun otherCodesRejected() {
        for (code in intArrayOf(3, 4, 24, 25, 66, 187, 1000)) assertFalse(code.toString(), UibcMediaKeys.isMedia(code))
    }
}
