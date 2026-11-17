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
package org.isoron.uhabits.receivers

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * xrnloops: a tick here earns an unlock in xrnrule, so no other app may tick a habit. Loop's
 * widget receiver was exported behind a permission named "false", which any app can declare and
 * then hold. The widgets' and notifications' PendingIntents name it and are sent as this app, so
 * they still reach it.
 */
class WidgetReceiverExportTest {
    @Test
    fun noOtherAppCanSendTheWidgetsTicks() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val receivers = manifest.getElementsByTagName("receiver")
        val widget = (0 until receivers.length).map { receivers.item(it) as Element }
            .single { it.getAttributeNS(ANDROID, "name") == ".receivers.WidgetReceiver" }
        assertEquals("false", widget.getAttributeNS(ANDROID, "exported"))
    }

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
