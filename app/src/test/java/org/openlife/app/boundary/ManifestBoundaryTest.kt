package org.openlife.app.boundary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class ManifestBoundaryTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val releaseManifest = projectDir.resolve(
        "app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml",
    )
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun releaseManifestDeclaresNoNetworkOrBroadAccessPermission() {
        val document = readReleaseManifest()
        val permissions = elements(document)
            .filter { it.localName?.startsWith("uses-permission") == true }
            .mapNotNull { it.getAttributeNS(androidNamespace, "name").takeIf(String::isNotEmpty) }
        val forbidden = permissions.filter { permission ->
            permission == "android.permission.INTERNET" ||
                permission == "android.permission.ACCESS_NETWORK_STATE" ||
                permission.startsWith("android.permission.READ_MEDIA_") ||
                permission in setOf(
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.MANAGE_EXTERNAL_STORAGE",
                    "android.permission.READ_CONTACTS",
                    "android.permission.READ_SMS",
                    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
                    "android.permission.BIND_ACCESSIBILITY_SERVICE",
                    "android.permission.CAMERA",
                )
        }
        assertTrue("forbidden release permissions: $forbidden", forbidden.isEmpty())
    }

    @Test
    fun onlyLauncherAndIntakeAreExported() {
        val document = readReleaseManifest()
        val exported = elements(document)
            .filter { it.localName in setOf("activity", "activity-alias", "service", "receiver", "provider") }
            .filter { it.getAttributeNS(androidNamespace, "exported") == "true" }
            .mapNotNull { it.getAttributeNS(androidNamespace, "name").takeIf(String::isNotEmpty) }
            .toSet()
        assertEquals(
            setOf("org.openlife.app.MainActivity", "org.openlife.app.intake.IntakeActivity"),
            exported,
        )
    }

    @Test
    fun backupIsDisabledInTheReleaseManifest() {
        val application = readReleaseManifest().documentElement.childElements("application").single()
        assertEquals("false", application.getAttributeNS(androidNamespace, "allowBackup"))
    }

    private fun readReleaseManifest(): Document {
        assertTrue("release manifest is missing: $releaseManifest", Files.isRegularFile(releaseManifest))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        return factory.newDocumentBuilder().parse(releaseManifest.toFile())
    }

    private fun elements(document: Document): List<Element> = document.getElementsByTagName("*").let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.childElements(name: String): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .filter { it.localName == name }
}
