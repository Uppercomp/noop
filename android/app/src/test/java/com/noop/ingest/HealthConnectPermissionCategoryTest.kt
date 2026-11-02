package com.noop.ingest

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import com.noop.ingest.HealthConnectImporter.ImportCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure coverage for the category consent contract in issue #645. */
class HealthConnectPermissionCategoryTest {
    private val recovery = setOf(ImportCategory.RECOVERY)
    private val activity = setOf(ImportCategory.ACTIVITY)
    private val body = setOf(ImportCategory.BODY_COMPOSITION)

    @Test
    fun categoriesPartitionEverySupportedPermissionExactlyOnce() {
        val perCategory = ImportCategory.entries.map { HealthConnectImporter.permissionsFor(setOf(it)) }

        assertEquals(perCategory.sumOf { it.size }, perCategory.flatten().toSet().size)
        assertEquals(HealthConnectImporter.PERMISSIONS, perCategory.flatten().toSet())
    }

    @Test
    fun recoveryDoesNotRequestActivityOrBodyComposition() {
        val permissions = HealthConnectImporter.permissionsFor(recovery)

        assertTrue(HealthPermission.getReadPermission(HeartRateRecord::class) in permissions)
        assertFalse(HealthPermission.getReadPermission(StepsRecord::class) in permissions)
        assertFalse(HealthPermission.getReadPermission(WeightRecord::class) in permissions)
    }

    @Test
    fun newInstallsDefaultNarrowWhileLegacyInstallsKeepExistingImports() {
        assertEquals(
            setOf(ImportCategory.NUTRITION),
            HealthConnectImporter.categoriesFromStoredKeys(null, hadLegacyPermissionSignature = false),
        )
        assertEquals(
            HealthConnectImporter.ALL_CATEGORIES,
            HealthConnectImporter.categoriesFromStoredKeys(null, hadLegacyPermissionSignature = true),
        )
    }

    @Test
    fun storedSelectionWinsOverLegacyFallback() {
        assertEquals(
            activity + body,
            HealthConnectImporter.categoriesFromStoredKeys(
                setOf(ImportCategory.ACTIVITY.storageKey, ImportCategory.BODY_COMPOSITION.storageKey),
                hadLegacyPermissionSignature = true,
            ),
        )
    }

    @Test
    fun foodOnlyScopeDoesNotReadBurnEvenWithOldAndroidGrants() {
        val nutrition = androidx.health.connect.client.records.NutritionRecord::class
        val active = androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class
        val total = androidx.health.connect.client.records.TotalCaloriesBurnedRecord::class
        val oldGrants = HealthConnectImporter.PERMISSIONS + setOf(
            HealthPermission.getReadPermission(active), HealthPermission.getReadPermission(total))
        assertEquals(setOf(nutrition), HealthConnectImporter.readableRecordTypes(
            setOf(ImportCategory.NUTRITION), oldGrants))
        assertFalse(active in HealthConnectImporter.readableRecordTypes(HealthConnectImporter.ALL_CATEGORIES, oldGrants))
        assertFalse(total in HealthConnectImporter.readableRecordTypes(HealthConnectImporter.ALL_CATEGORIES, oldGrants))
    }

    @Test
    fun narrowingNeverRepromptsAndExpandingRequestsOnlyNewCategory() {
        val recoveryPermissions = HealthConnectImporter.permissionsFor(recovery)

        assertTrue(HealthConnectImporter.unaskedPermissions(recoveryPermissions, recovery).isEmpty())
        assertEquals(
            HealthConnectImporter.permissionsFor(body),
            HealthConnectImporter.unaskedPermissions(recoveryPermissions, recovery + body),
        )
    }

    @Test
    fun partialGrantReadsOnlyGrantedTypesInsideSelectedCategories() {
        val heartRate = HealthPermission.getReadPermission(HeartRateRecord::class)
        val steps = HealthPermission.getReadPermission(StepsRecord::class)
        val readable = HealthConnectImporter.readableRecordTypes(
            categories = recovery + activity,
            grantedPermissions = setOf(heartRate, steps),
        )

        assertEquals(setOf(HeartRateRecord::class, StepsRecord::class), readable)
        assertFalse(WeightRecord::class in readable)
    }

    @Test
    fun grantFromDeselectedCategoryIsNotRead() {
        val readable = HealthConnectImporter.readableRecordTypes(
            categories = recovery,
            grantedPermissions = setOf(
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(StepsRecord::class),
            ),
        )

        assertEquals(setOf(HeartRateRecord::class), readable)
    }

    /**
     * #645 migration: a user who granted Health Connect before the selector existed has no stored
     * selection, and if they onboarded before #949 no permission signature either — the importer has
     * shipped since 2026-06-07 and that key only since 2026-07-30. Their Android grants are the only
     * honest record of what they agreed to, so the scope is read back off those rather than defaulted.
     *
     * Without this they would silently stop importing Activity and Body composition while Android still
     * showed those permissions as granted, with nothing on screen saying why steps had stopped.
     */
    @Test
    fun grantsFromBeforeTheSelectorRecoverTheirCategories() {
        val granted = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )
        assertEquals(
            setOf(ImportCategory.RECOVERY, ImportCategory.ACTIVITY),
            HealthConnectImporter.categoriesFromGrantedPermissions(granted),
        )
    }

    /** A fresh install grants nothing, so there is nothing to recover and the caller keeps its default. */
    @Test
    fun noGrantsRecoversNothing() {
        assertTrue(HealthConnectImporter.categoriesFromGrantedPermissions(emptySet()).isEmpty())
    }

    /** One granted type is enough to claim its whole category — partial grants stay supported (#150). */
    @Test
    fun oneGrantedTypeClaimsItsCategory() {
        assertEquals(
            setOf(ImportCategory.BODY_COMPOSITION),
            HealthConnectImporter.categoriesFromGrantedPermissions(
                setOf(HealthPermission.getReadPermission(WeightRecord::class)),
            ),
        )
    }
}
