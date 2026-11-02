package com.noop.ingest

import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class HealthConnectNutritionTest {
    private fun record(energy: Energy? = null, protein: Mass? = null, carbs: Mass? = null, fat: Mass? = null) = NutritionRecord(
        startTime = Instant.parse("2026-11-02T08:00:00Z"), startZoneOffset = null,
        endTime = Instant.parse("2026-11-02T08:05:00Z"), endZoneOffset = null, metadata = Metadata(),
        energy = energy, protein = protein, totalCarbohydrate = carbs, totalFat = fat)

    @Test fun optionalFoodFieldsKeepTheirUnitsAndDoNotInventMissingValues() {
        val values = HealthConnectImporter.nutritionValues(record(Energy.kilocalories(498.0), Mass.grams(13.5), Mass.grams(23.0), Mass.grams(4.5)))
        assertEquals(mapOf("calories_in" to 498.0, "protein_g" to 13.5, "carbs_g" to 23.0, "fat_g" to 4.5), values)
        assertTrue(HealthConnectImporter.nutritionValues(record()).isEmpty())
        assertEquals(mapOf("calories_in" to 0.0), HealthConnectImporter.nutritionValues(record(energy = Energy.kilocalories(0.0))))
    }
}
