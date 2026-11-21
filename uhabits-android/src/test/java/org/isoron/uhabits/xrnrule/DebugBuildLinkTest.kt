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
package org.isoron.uhabits.xrnrule

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * xrnloops: the debug build installs beside the real app under its own id, and a tick there must
 * not unlock anything. xrnrule only talks to com.xern.xrnloops; on this side the debug manifest
 * takes out the permission that guards the link, the request for it and the view of xrnrule.
 * Declaring that permission under another signing key would also refuse the install outright.
 */
class DebugBuildLinkTest {
    @Test
    fun debugBuildStaysOutOfXrnrulesLink() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/debug/AndroidManifest.xml"))
        fun removed(tag: String) = manifest.getElementsByTagName(tag).let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
                .filter { it.getAttributeNS(TOOLS, "node") == "remove" }
                .map { it.getAttributeNS(ANDROID, "name") }
        }
        assertEquals(listOf(XrnruleContract.PERMISSION), removed("permission"))
        assertEquals(listOf(XrnruleContract.PERMISSION), removed("uses-permission"))
        assertEquals(listOf(XrnruleContract.XRNRULE_PACKAGE), removed("package"))
    }

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"
        private const val TOOLS = "http://schemas.android.com/tools"
    }
}
