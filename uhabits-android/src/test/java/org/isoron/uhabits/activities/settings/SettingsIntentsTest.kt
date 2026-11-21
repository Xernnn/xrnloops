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
package org.isoron.uhabits.activities.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * xrnloops: an intent in preferences.xml names its package in plain text, which is the release
 * build's app id. In the debug build, installed beside it under another id, Settings' About
 * opened the release app's About page, which isn't exported: the debug app crashed. About is
 * pointed at this app's own page in [SettingsFragment] instead.
 */
class SettingsIntentsTest {
    @Test
    fun noSettingOpensAnotherAppsPage() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val prefs = factory.newDocumentBuilder().parse(File("src/main/res/xml/preferences.xml"))
        val intents = prefs.getElementsByTagName("intent")
        val packages = (0 until intents.length).map { intents.item(it) as Element }
            .filter { it.hasAttributeNS(ANDROID, "targetPackage") }
            .map { it.getAttributeNS(ANDROID, "targetPackage") }
        assertEquals(emptyList<String>(), packages)
    }

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
