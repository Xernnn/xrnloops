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
package org.isoron.uhabits.automation

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * xrnloops: a tick here earns an unlock in xrnrule, and Loop's Tasker plugin let any app tick a
 * habit: FireSettingReceiver took FIRE_SETTING from anyone, with no permission. Tasker isn't used,
 * so neither of the plugin's components is exported; the code stays.
 */
class TaskerPluginExportTest {
    @Test
    fun noOtherAppCanReachTheTaskerPlugin() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        assertEquals(
            "FireSettingReceiver",
            "false",
            exported(manifest, "receiver", ".automation.FireSettingReceiver")
        )
        assertEquals(
            "EditSettingActivity",
            "false",
            exported(manifest, "activity", ".automation.EditSettingActivity")
        )
    }

    private fun exported(manifest: Document, tag: String, name: String): String {
        val components = manifest.getElementsByTagName(tag)
        return (0 until components.length).map { components.item(it) as Element }
            .single { it.getAttributeNS(ANDROID, "name") == name }
            .getAttributeNS(ANDROID, "exported")
    }

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
