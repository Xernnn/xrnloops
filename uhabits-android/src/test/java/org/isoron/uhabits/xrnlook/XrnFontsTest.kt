/*
 * Copyright (C) 2026 the xrnrule authors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.xrnlook

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.nio.ByteBuffer
import javax.xml.parsers.DocumentBuilderFactory

/**
 * xrnloops: MiSans Latin 4.007's static Bold and Heavy files draw an ampersand where "@" should
 * be (as on the web, web/test/fonts.test.mjs). Titles and the family's weight 800 are MiSans Bold,
 * so it comes from the variable font at its Bold, which draws "@".
 */
class XrnFontsTest {
    private val fonts = File("src/main/res/font")

    @Test
    fun noStaticBoldOrHeavyMiSansIsBundled() {
        val heavy = fonts.listFiles { f -> f.extension == "ttf" }!!
            .filter { it.name != VARIABLE && weightClass(it) >= BOLD }
            .map { it.name }
        assertEquals(emptyList<String>(), heavy)
    }

    @Test
    fun boldIsTheVariableFontAtItsBold() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        // The family's weight 800 (textFontWeight, the canvas) and the titles' own Bold.
        for ((file, weight) in listOf("xrn_misans.xml" to "800", "xrn_misans_bold.xml" to "$BOLD")) {
            val entries = factory.newDocumentBuilder().parse(File(fonts, file)).getElementsByTagName("font")
            val font = (0 until entries.length).map { entries.item(it) as Element }
                .single { it.getAttributeNS(ANDROID, "fontWeight") == weight }
            assertEquals(file, "@font/${VARIABLE.removeSuffix(".ttf")}", font.getAttributeNS(ANDROID, "font"))
            assertEquals(file, "'wght' $BOLD", font.getAttributeNS(ANDROID, "fontVariationSettings"))
        }
    }

    /** usWeightClass from a font file's OS/2 table: MiSans gives Bold 630 and Heavy 700. */
    private fun weightClass(file: File): Int {
        val data = ByteBuffer.wrap(file.readBytes())
        val tables = data.getShort(4).toInt()
        for (i in 0 until tables) {
            val record = 12 + 16 * i
            val tag = String(ByteArray(4) { data.get(record + it) }, Charsets.US_ASCII)
            if (tag == "OS/2") return data.getShort(data.getInt(record + 8) + 4).toInt() and 0xFFFF
        }
        error("${file.name} has no OS/2 table")
    }

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"
        private const val VARIABLE = "xrn_misans_vf.ttf"
        private const val BOLD = 630
    }
}
