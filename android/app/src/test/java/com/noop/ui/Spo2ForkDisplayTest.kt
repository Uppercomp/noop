package com.noop.ui

import com.noop.analytics.VitalBands
import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class Spo2ForkDisplayTest {
    private lateinit var app: Application

    @Before fun resetPreferences() {
        app = RuntimeEnvironment.getApplication()
        NoopPrefs.of(app).edit().clear().commit()
    }

    @Test fun getterAndFlowAgreeOnForkDefaultAndSavedChoices() = runBlocking {
        assertTrue(NoopPrefs.spo2CandidateDisplay(app))
        assertTrue(NoopPrefs.spo2CandidateDisplayFlow(app).first())
        NoopPrefs.setSpo2CandidateDisplay(app, false)
        assertFalse(NoopPrefs.spo2CandidateDisplay(app))
        assertFalse(NoopPrefs.spo2CandidateDisplayFlow(app).first())
        NoopPrefs.setSpo2CandidateDisplay(app, true)
        assertTrue(NoopPrefs.spo2CandidateDisplayFlow(app).first())
    }

    @Test fun candidatesDoNotReceiveClinicalWarningBanding() {
        for (candidate in 70..100) {
            assertEquals(VitalBands.Band.IN_RANGE, spo2DisplayBand(null, candidate.toDouble()).band)
        }
    }

    @Test fun importedValueStillWinsAndKeepsItsExistingBanding() {
        assertEquals(VitalBands.Band.OUT_OF_RANGE, spo2DisplayBand(92.0, 99.0).band)
        assertEquals(VitalBands.Band.IN_RANGE, spo2DisplayBand(99.0, 82.0).band)
    }

    @Test fun absentCandidateDoesNotInventAnOxygenPercentage() {
        assertEquals(VitalBands.Band.NO_DATA, spo2DisplayBand(null, null).band)
    }
}
