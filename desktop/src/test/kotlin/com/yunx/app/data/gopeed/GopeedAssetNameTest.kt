package com.yunx.app.data.gopeed

import org.junit.Assert.assertEquals
import org.junit.Test

class GopeedAssetNameTest {
    @Test
    fun macArm64MatchesGopeedWebRelease() {
        assertEquals(
            "gopeed-web-v1.9.3-macos-arm64.zip",
            GopeedKernelProvisioner.assetName("v1.9.3", "Mac OS X", "aarch64")
        )
    }

    @Test
    fun windowsAmd64StaysOnTheExistingName() {
        assertEquals(
            "gopeed-web-v1.9.3-windows-amd64.zip",
            GopeedKernelProvisioner.assetName("v1.9.3", "Windows 11", "amd64")
        )
    }

    @Test
    fun linuxAmd64MatchesGopeedWebRelease() {
        assertEquals(
            "gopeed-web-v1.9.3-linux-amd64.zip",
            GopeedKernelProvisioner.assetName("v1.9.3", "Linux", "amd64")
        )
    }

    @Test
    fun linuxArm64UsesArm64Token() {
        assertEquals(
            "gopeed-web-v1.9.3-linux-arm64.zip",
            GopeedKernelProvisioner.assetName("v1.9.3", "Linux", "aarch64")
        )
    }
}
