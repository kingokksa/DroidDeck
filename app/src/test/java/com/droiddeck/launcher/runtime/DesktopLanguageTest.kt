package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLanguageTest {
    private val release = BuildConfig.DESKTOP_KDE_URL.substringBeforeLast('/')

    @Test fun thePacksComeFromTheReleaseTheDesktopItselfCameFrom() {
        assertEquals("$release/desktop-kde.i18n.txt", DesktopLanguage.indexUrl())
        assertEquals("$release/desktop-kde-i18n-de.tar.zst", DesktopLanguage.packUrl("de"))
        assertEquals("$release/desktop-kde-i18n-zh_TW.tar.zst", DesktopLanguage.packUrl("zh_TW"))
        assertTrue(DesktopLanguage.packUrl("de").startsWith(BuildConfig.DESKTOP_KDE_URL.substringBeforeLast('/')))
    }

    @Test fun theReleaseIndexNamesEveryPackWithItsChecksum() {
        val index = "de ${"a".repeat(64)} 262144\n\nzh_CN ${"b".repeat(64)} 300000\ngarbage\n"
        val packs = DesktopLanguage.parse(index)
        assertEquals(setOf("de", "zh_CN"), packs.keys)
        assertEquals("a".repeat(64), packs.getValue("de").sha256)
        assertEquals("zh_CN", packs.getValue("zh_CN").tag)
    }

    @Test fun aLocaleNameIsNeverUsedAsAPath() {
        assertEquals("de", DesktopLanguage.sanitize("de"))
        assertEquals("zh_TW", DesktopLanguage.sanitize("zh_TW"))
        assertEquals("_.._etc_passwd", DesktopLanguage.sanitize("/../etc/passwd"))
    }
}
