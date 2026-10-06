package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Settings → Strap status copy, in particular that an in-flight scan takes precedence over
 * bonded/connected so the user gets "Searching…" feedback the moment Re-scan is tapped (issue #1). The
 * button's `enabled = !live.scanning` relies on the same scanning flag, so a regression here is the
 * visible half of "Re-scan does nothing".
 *
 * The helpers return [DisplayText] — a pure helper a JVM test calls with no Application attached cannot
 * resolve a resource itself — so the branch is pinned by RESOURCE. The English claims the live-HR line
 * has to make are pinned against `values/strings.xml` (the idiom [AdvertisingNameProbeTest] and
 * [StepsMotionCacheTest] already use), which is where those words actually live now.
 */
class StrapStatusDetailTest {

    private fun englishValue(name: String): String {
        var root = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (!File(root, "app/src/main/res/values/strings.xml").isFile) {
            root = root.parentFile ?: error("strings.xml not found from ${System.getProperty("user.dir")}")
        }
        val xml = File(root, "app/src/main/res/values/strings.xml").readText()
        val match = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?: error("no English value for $name")
        return match.groupValues[1].replace("\\'", "'")
    }

    @Test
    fun scanning_takesPrecedence_overEveryOtherState() {
        // Even when already bonded + connected, an active scan must say "Searching…". It is the one
        // branch with no catalog source, so it is verbatim English (Dynamic) rather than a resource.
        for (state in listOf(true to true, false to false)) {
            val detail = strapStatusDetail(
                encryptedBond = state.first, bonded = state.first, connected = state.second, scanning = true,
            )
            assertTrue(
                "an active scan must say Searching…, got $detail",
                detail is DisplayText.Dynamic && detail.value.startsWith("Searching for your WHOOP"),
            )
        }
    }

    @Test
    fun nonScanning_branches_areUnchanged() {
        assertEquals(
            DisplayText.Resource(R.string.l10n_settings_logic_your_strap_is_paired_and_sending_data_op_ef06dfd4),
            strapStatusDetail(encryptedBond = true, bonded = true, connected = true, scanning = false),
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_settings_logic_connected_finishing_the_secure_pairing_h_1e56d362),
            strapStatusDetail(encryptedBond = false, bonded = false, connected = true, scanning = false),
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_settings_logic_previously_paired_but_not_currently_conn_0a84a072),
            strapStatusDetail(encryptedBond = true, bonded = true, connected = false, scanning = false),
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_settings_logic_no_strap_connected_put_your_whoop_nearby_61a155c1),
            strapStatusDetail(encryptedBond = false, bonded = false, connected = false, scanning = false),
        )
    }

    @Test
    fun `a live-HR-only link is not described as paired`() {
        // #1635/#69: the 5/MG live-HR shortcut sets bonded without any encrypted pairing, and hello
        // suppression makes that the permanent state. Telling the user their strap "is paired" there
        // contradicts the Devices screen and the buzz/alarm rows on this same screen.
        val detail = strapStatusDetail(
            encryptedBond = false, bonded = true, connected = true, scanning = false,
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_settings_logic_live_heart_rate_is_streaming_but_your_st_934807fc),
            detail,
        )
        val words = englishValue("l10n_settings_logic_live_heart_rate_is_streaming_but_your_st_934807fc")
        assertFalse(words.contains("is paired"))
        assertTrue(words.contains("not fully paired"))

        // The consequences, not just the state. This line used to name only buzz, alarms and history
        // sync — but an unbonded strap also stops sending motion, skin temperature, SpO2 and
        // respiratory rate, which drops sleep onto the HR-only stager and blanks HRV + resting HR
        // (AnalyticsEngine gates both on `!hrOnly`). A field log showed a week of blank Recovery
        // Vitals while this line pointed at three features the user was not missing.
        assertTrue(words.contains("motion"))
        // #1884 repinned the tail of this: naming HRV and resting HR as CONSEQUENCES stopped being true
        // when an HR-only night began reporting both. What survives is the reason they were named at all
        // — the strap stops sending motion, so sleep falls to the HR-only stager.
        assertTrue(words.contains("staged from heart rate alone"))
        assertFalse(words.contains("HRV and resting heart rate are unavailable"))
        // The original claim has to survive the rewrite.
        assertTrue(words.contains("history sync"))

        assertEquals(
            DisplayText.Resource(R.string.l10n_settings_logic_live_hr_not_fully_paired_6fd6ddb1),
            strapStatusTitle(encryptedBond = false, bonded = true, connected = true),
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_settings_logic_bonded_streaming_4dda60db),
            strapStatusTitle(encryptedBond = true, bonded = true, connected = true),
        )
    }

    @Test
    fun `only a real bond on a live link is a positive tone`() {
        assertEquals(StrandTone.Positive, strapTone(encryptedBond = true, bonded = true, connected = true))
        assertEquals(StrandTone.Warning, strapTone(encryptedBond = false, bonded = true, connected = true))
        assertEquals(StrandTone.Critical, strapTone(encryptedBond = false, bonded = false, connected = false))
    }
}
