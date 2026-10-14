package com.jihad.chatviewer.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object TextFix {
    fun fix(s: String): String {
        var hasHigh = false
        for (ch in s) {
            if (ch.code > 0xFF) return s
            if (ch.code >= 0x80) hasHigh = true
        }
        if (!hasHigh) return s
        val bytes = s.toByteArray(Charsets.ISO_8859_1)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            s
        }
    }
}
