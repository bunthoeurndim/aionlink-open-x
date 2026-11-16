package org.aionlink.projection

/**
 * Transport controls the car's steering wheel sends as UIBC key events.
 * Codes are Android KeyEvent codes: 85 play/pause, 86 stop, 87 next, 88 previous,
 * 89 rewind, 90 fast forward, 126 play, 127 pause. Everything else (back, home,
 * volume) is not a transport control and keeps its existing handling.
 */
object UibcMediaKeys {
    fun isMedia(keyCode: Int): Boolean = keyCode == 85 || keyCode == 86 || keyCode == 87 ||
        keyCode == 88 || keyCode == 89 || keyCode == 90 || keyCode == 126 || keyCode == 127
}
