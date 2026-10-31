package com.yunx.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostOsTest {
    @Test
    fun classifiesDesktopOperatingSystems() {
        assertEquals(HostKind.WINDOWS, HostOs.kindOf("Windows 11"))
        assertEquals(HostKind.MAC, HostOs.kindOf("Mac OS X"))
        assertEquals(HostKind.LINUX, HostOs.kindOf("Linux"))
        assertEquals(HostKind.LINUX, HostOs.kindOf("Linux amd64"))
    }

    @Test
    fun javafxClassifierFollowsOsAndArch() {
        assertEquals("win", HostOs.javafxClassifier("Windows 11", "amd64"))
        assertEquals("mac-aarch64", HostOs.javafxClassifier("Mac OS X", "aarch64"))
        assertEquals("mac", HostOs.javafxClassifier("Mac OS X", "x86_64"))
        assertEquals("linux", HostOs.javafxClassifier("Linux", "amd64"))
        assertEquals("linux", HostOs.javafxClassifier("Linux", "aarch64"))
    }

    @Test
    fun gopeedAssetTokensMatchUpstreamReleaseNames() {
        assertEquals("windows", HostOs.gopeedOsToken("Windows 11"))
        assertEquals("macos", HostOs.gopeedOsToken("Mac OS X"))
        assertEquals("linux", HostOs.gopeedOsToken("Linux"))
        assertEquals("arm64", HostOs.gopeedArchToken("aarch64"))
        assertEquals("amd64", HostOs.gopeedArchToken("x86_64"))
        assertEquals("amd64", HostOs.gopeedArchToken("amd64"))
    }

    @Test
    fun linuxKindFlagsAreConsistent() {
        assertTrue(HostOs.isLinux == (HostOs.kind == HostKind.LINUX))
        assertFalse(HostOs.isWindows && HostOs.isLinux)
        assertFalse(HostOs.isMac && HostOs.isLinux)
    }
}
