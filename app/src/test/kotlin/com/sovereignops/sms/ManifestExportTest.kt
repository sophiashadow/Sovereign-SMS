package com.sovereignops.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the app's own manifest (library manifests merge in later and are checked on the
 * merged APK manifest, see docs/SECURITY_REVIEW.md). Every exported component must be on this
 * list with the permission the platform uses to call it; anything new fails here first.
 */
class ManifestExportTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun components(): List<Element> {
        val f = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)
        return listOf("activity", "activity-alias", "receiver", "service", "provider").flatMap { tag ->
            val list = doc.getElementsByTagName(tag)
            (0 until list.length).map { list.item(it) as Element }
        }
    }

    private fun Element.attr(name: String) = getAttributeNS(ns, name)

    /** Exported component → permission the caller must hold ("" = none: UI entry points only). */
    private val allowed = mapOf(
        ".activities.MainActivity" to "",
        ".activities.RecycleBinConversationsActivity" to "",
        ".activities.ArchivedConversationsActivity" to "",
        ".activities.NewConversationActivity" to "",
        ".activities.SettingsActivity" to "",
        ".services.HeadlessSmsSendService" to "android.permission.SEND_RESPOND_VIA_MESSAGE",
        "com.sovereignops.sms.QuickCoverTile" to "android.permission.BIND_QUICK_SETTINGS_TILE",
        ".receivers.SmsReceiver" to "android.permission.BROADCAST_SMS",
        "com.android.mms.transaction.PushReceiver" to "android.permission.BROADCAST_WAP_PUSH",
    )

    @Test
    fun onlyAllowListedComponentsAreExported() {
        val exported = components().filter { it.attr("exported") == "true" }
        for (c in exported) {
            val name = c.attr("name")
            if (c.tagName == "activity-alias" && name.startsWith(".activities.SplashActivity.")) continue // launcher icon colours
            assertTrue("$name is exported but not on the allow-list", name in allowed)
            assertEquals("$name permission", allowed.getValue(name), c.attr("permission"))
        }
    }

    /**
     * MmsReceiver takes a file path from its intent, reads it and deletes it. Exported, any app could
     * make it delete files in this app's private storage. It is reached only by an explicit broadcast
     * from this app's own process (klinker BroadcastUtils matches it by taskAffinity), so it must stay
     * unexported and keep that taskAffinity.
     */
    @Test
    fun mmsReceiverIsPrivateAndStillDiscoverable() {
        val r = components().single { it.attr("name") == ".receivers.MmsReceiver" }
        assertEquals("false", r.attr("exported"))
        assertEquals("com.klinker.android.messaging.MMS_RECEIVED", r.attr("taskAffinity"))
        val sent = components().single { it.attr("name") == ".receivers.MmsSentReceiver" }
        assertEquals("false", sent.attr("exported"))
        assertEquals("com.klinker.android.messaging.MMS_SENT", sent.attr("taskAffinity"))
    }

    @Test
    fun sovereignComponentsAreNotExported() {
        val ours = components().filter { it.attr("name").startsWith("com.sovereignops.") && it.attr("name") != "com.sovereignops.sms.QuickCoverTile" }
        assertTrue(ours.isNotEmpty())
        for (c in ours) assertEquals("${c.attr("name")} must not be exported", "false", c.attr("exported"))
    }
}
