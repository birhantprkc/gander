package com.arjun.gander

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * What the manifest promises about data leaving the phone by a route that is not
 * Gander's own code: a cloud backup, a transfer to a new phone, and the WebView
 * provider's own reporting. Each is one attribute or one line of meta-data, which is
 * exactly why each needs pinning. Deleting any of them changes nothing anyone would
 * see, and nothing else would fail.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        val MANIFEST = File("src/main/AndroidManifest.xml").readText()

        /** Every domain a backup rule can name, as android.app.backup.FullBackup reads them. */
        val DOMAINS = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
    }

    private class Section {
        val includes = mutableListOf<String>()
        val excludes = mutableListOf<String>()
    }

    /**
     * Each section of a rules file, with every rule written as its domain alone when it
     * covers the whole domain and as domain:path when it covers less.
     */
    private fun sections(parser: XmlResourceParser): Map<String, Section> {
        val found = linkedMapOf<String, Section>()
        var current: Section? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                // The older format has no sections: the whole file is one.
                "cloud-backup", "device-transfer", "full-backup-content" ->
                    current = Section().also { found[parser.name] = it }
                "include", "exclude" -> {
                    val domain = parser.getAttributeValue(null, "domain")
                    val path = parser.getAttributeValue(null, "path")
                    val rule = if (path.isNullOrEmpty() || path == ".") domain else "$domain:$path"
                    val into = checkNotNull(current) { "a rule outside any section" }
                    if (parser.name == "include") into.includes += rule else into.excludes += rule
                }
            }
        }
        return found
    }

    private fun metaData(): Bundle = context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData

    @Test
    fun cloudBackupIsOffOnEveryVersion() {
        assertThat(MANIFEST).contains("""android:allowBackup="false"""")
    }

    /**
     * From Android 12 allowBackup no longer covers a transfer to a new phone, which
     * reads these rules instead. A rule-less section means everything minus the
     * excludes, so the test is that every domain is excluded whole and nothing is
     * included, in both sections.
     */
    @Test
    fun neitherBackupNorATransferToANewPhoneCarriesAnything() {
        assertThat(MANIFEST).contains("""android:dataExtractionRules="@xml/data_extraction_rules"""")
        val rules = sections(context.resources.getXml(R.xml.data_extraction_rules))
        assertThat(rules.keys).containsExactly("cloud-backup", "device-transfer")
        for ((name, section) in rules) {
            assertWithMessage("includes in <$name>").that(section.includes).isEmpty()
            assertWithMessage("excludes in <$name>").that(section.excludes)
                .containsExactlyElementsIn(DOMAINS)
        }
    }

    /** Android 11 and older, where allowBackup already says the same thing. */
    @Test
    fun theOlderBackupRulesCarryNothingEither() {
        assertThat(MANIFEST).contains("""android:fullBackupContent="@xml/backup_rules"""")
        val rules = sections(context.resources.getXml(R.xml.backup_rules))
        val section = checkNotNull(rules["full-backup-content"])
        assertThat(section.includes).isEmpty()
        assertThat(section.excludes).containsExactlyElementsIn(DOMAINS)
    }

    /** The provider uploads these from its own process, which has the network access Gander does not. */
    @Test
    fun theWebViewProviderIsToldNotToReportMetrics() {
        assertThat(metaData().getBoolean("android.webkit.WebView.MetricsOptOut", false)).isTrue()
    }

    @Test
    fun safeBrowsingIsOff() {
        assertThat(metaData().getBoolean("android.webkit.WebView.EnableSafeBrowsing", true)).isFalse()
    }
}
