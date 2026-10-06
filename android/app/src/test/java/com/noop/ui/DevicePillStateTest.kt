package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Pins the Devices card's state-pill priority (#221): "Connected · not paired" must beat
 * "Active · Live" but yield to a reboot's "Reconnecting…". Mirrors the Swift
 * `DevicePillStateTests` exactly … a silent reorder on either platform would otherwise only be
 * caught by eyeballing a screenshot.
 *
 * The labels are asserted as [DisplayText] (which sentence the decision chose) together with the ENGLISH
 * wording read from the shipped resources, because that English is what the iOS twin is byte-identical to.
 * The two labels iOS leaves untranslated stay [DisplayText.Dynamic] and are asserted as such.
 */
class DevicePillStateTest {

    /** The English value of a resource, read from the shipped default resources. */
    private fun english(name: String): String {
        var root = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (!File(root, "app/src/main/res/values/strings.xml").isFile) {
            root = root.parentFile ?: error("strings.xml not found from ${System.getProperty("user.dir")}")
        }
        val xml = File(root, "app/src/main/res/values/strings.xml").readText()
        val match = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?: error("no English value for $name")
        return match.groupValues[1].replace("\\'", "'")
    }

    private val removedKey = "l10n_devices_screen_removed_b5e77c5c"
    private val pairedKey = "l10n_devices_screen_paired_3cd1b3d4"
    private val activeLiveKey = "l10n_devices_screen_active_live_4dc58f41"
    private val activeKey = "l10n_devices_screen_active_a733b809"

    @Test
    fun bondRefused_beatsActiveLive_butYieldsToReconnecting() {
        assertEquals(
            DisplayText.Dynamic("Connected · not paired"),
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = true, isLiveConnected = true,
            ).label,
        )
        assertEquals(
            DisplayText.Dynamic("Reconnecting…"),
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = true,
                bondRefused = true, isLiveConnected = true,
            ).label,
        )
    }

    @Test
    fun normalConnect_isUnaffected() {
        assertEquals(
            DisplayText.Resource(R.string.l10n_devices_screen_active_live_4dc58f41),
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = true,
            ).label,
        )
        assertEquals("Active · Live", english(activeLiveKey))
        assertEquals(
            DisplayText.Resource(R.string.l10n_devices_screen_active_a733b809),
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).label,
        )
        assertEquals("Active", english(activeKey))
    }

    @Test
    fun nonActiveAndArchived() {
        assertEquals(
            DisplayText.Resource(R.string.l10n_devices_screen_paired_3cd1b3d4),
            devicePillState(
                isArchived = false, isActive = false, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).label,
        )
        assertEquals("Paired", english(pairedKey))
        assertEquals(
            DisplayText.Resource(R.string.l10n_devices_screen_removed_b5e77c5c),
            devicePillState(
                isArchived = true, isActive = false, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).label,
        )
        assertEquals("Removed", english(removedKey))
    }

    @Test
    fun historyLayoutLine_formatsObservedHistoricalRecordVersion() {
        // The line is display copy now ("v%@ history" is translated in the catalog), so the decision returns
        // a resource and the English it renders is asserted from the shipped strings.
        assertEquals(
            DisplayText.Resource(R.string.l10n_devices_screen_v_lld_history_0f612890, listOf(25)),
            historyLayoutLine(25),
        )
        assertEquals("v%1\$d history", english("l10n_devices_screen_v_lld_history_0f612890"))
        assertNull(historyLayoutLine(null))
    }
}
