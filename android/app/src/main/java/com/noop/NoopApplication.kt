package com.noop

import android.app.Application
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import android.util.Log
import com.noop.ble.SourceCoordinator
import com.noop.ble.SourceIdentity
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import com.noop.data.DeviceRegistry
import com.noop.data.LiftLogRepository
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.ui.NoopPrefs
import com.noop.ui.AppLanguagePrefs
import com.noop.push.SelfHostedPushScheduler
import com.noop.ui.LiftSessionController
import com.noop.ui.LiftSessionPersistence
import com.noop.ui.LiftSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Application entry point.
 *
 * NOOP is a fully on-device WHOOP companion: it connects to the strap over BLE and persists
 * everything locally via Room. Network access is opt-in: the AI Coach and the experimental,
 * one-way self-hosted push are both disabled by default.
 *
 * The data layer ([WhoopRepository]) and the BLE client ([WhoopBleClient]) are owned **here**, at the
 * process level, rather than by the Activity-scoped AppViewModel. That is what lets a connection keep
 * streaming when the app is backgrounded or closed: [com.noop.ble.WhoopConnectionService] holds the
 * process up with a foreground notification, and both it and the UI share this one BLE client. The
 * macOS app gets the same outcome for free — its `AppModel` is an app-level `@StateObject` kept alive
 * by the menu-bar extra.
 */
class NoopApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguagePrefs.wrap(base))
        // UI resource lookup is intentionally available before onCreate: data-driven presentation
        // helpers (release notes, metric catalogs) can resolve a resource without becoming
        // @Composable or retaining an Activity. The Application is process-scoped, so this does not
        // leak a screen/context; configuration changes replace its Resources in place.
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        // Install before any app-owned startup work so even an early failure is preserved for the
        // recovery screen on the next launch.
        CrashCapture.install(this)
        // #1008: pin the pre-change Overnight-only default for existing installs before anything
        // reads it. Idempotent; a no-op on fresh installs and on every launch after the first.
        com.noop.ui.NoopPrefs.migrateContinuousHrvOvernightDefault(this)
        // #2185: a stress widget placed by an older version fired its `onEnabled` long before the
        // scheduler existed, so the receiver hook alone would never reach it. Enqueued with KEEP, so
        // this is a no-op once a schedule exists, and the worker retires itself when no widget is
        // placed — which is what stops this costing anything for an install that has never had one.
        com.noop.widget.StressWidgetRefresh.ensureScheduled(this)
        // #2556: the stale-battery warning needs a wake that does NOT depend on the BLE link, because the
        // case it exists for is a strap that stopped talking. Scheduled here rather than beside a widget
        // so it does not inherit that widget's lifecycle. KEEP, so this is a no-op once scheduled.
        com.noop.notif.StaleBatteryWorker.ensureScheduled(this)
        // Pick up a gym session a previous run of NOOP left going, before any screen exists: until this
        // ran on screen-open, the running-session bar showed nothing and a strap tap did nothing until the
        // Lift Log was opened. Reads one preference and parses it; a no-op when nothing is stored.
        liftSession.resumeSaved(liftSessionStore)
    }

    /** Process-wide Room-backed store. One instance shared by the UI and the background service. */
    val repository: WhoopRepository by lazy {
        WhoopRepository(WhoopDatabase.get(this))
    }

    /** Process-wide device registry over the same Room DB — the single source of the active device id. */
    val deviceRegistry: DeviceRegistry by lazy { DeviceRegistry(WhoopDatabase.get(this)) }

    /**
     * The in-app strength log's read/write façade over the same Room DB.
     *
     * Process-wide for the same reason the store and the BLE client are: a gym session outlives the
     * screen you happen to be looking at, so the thing that reads and writes it cannot be owned by a
     * screen either.
     */
    val liftLog: LiftLogRepository by lazy { LiftLogRepository(WhoopDatabase.get(this)) }

    /** The crash snapshot for an in-flight Lift session — one key in its own preferences file. */
    val liftSessionStore: LiftSessionStore by lazy { LiftSessionStore.from(this) }

    /**
     * A process-lifetime scope for the session's rest timers.
     *
     * Owned here because a rest that started in the gym has to keep its two one-shot timers (the
     * five-second warning and the end) firing whether or not any screen is alive to show it.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * A scope whose lifetime is the PROCESS, for work that must finish whatever the UI does.
     *
     * The canonical case is saving a finished session: `LiftSessionController.finish()` flips `isActive`,
     * which takes the session sheet out of the composition — and a coroutine launched from a composable's
     * `rememberCoroutineScope()` is cancelled the instant that composable leaves. Saving on a
     * composition-scoped coroutine therefore wrote the session row and silently dropped its sets, its
     * program write-back and its paired workout.
     */
    val liftScope: CoroutineScope get() = applicationScope

    /**
     * The strap's double-tap, claimed by a running Lift session for its lifetime.
     *
     * The session used to own the gesture inside the sheet that displayed it, so dismissing the sheet made
     * the strap deaf: taps did nothing and no buzz arrived, in the app or out of it. The handler now lives
     * at the process level ([LiftSessionController.claimStrap]) and the double-tap dispatch consults it
     * BEFORE the user's configured automation, which is what "while a session is running, a tap advances a
     * set" means. Null whenever no session is running.
     */
    @Volatile
    var liftStrapTap: (() -> Unit)? = null
        private set

    /** The live Lift session — the gym session's owner, above every screen. */
    val liftSession: LiftSessionController by lazy {
        LiftSessionController(
            // The same confirming buzz every other in-session cue uses. Resolved lazily, so building the
            // controller does not construct the BLE client.
            buzz = { loops -> ble.buzz(loops) },
            setStrapHandler = { handler -> liftStrapTap = handler },
            log = { line -> ble.externalLog(line) },
            persistSnapshot = { snapshot -> LiftSessionPersistence.store(liftSessionStore, snapshot) },
            clearSnapshot = { LiftSessionPersistence.store(liftSessionStore, null) },
            scope = applicationScope,
            // The Lock Screen readout: posted whenever the session's words change, cleared on teardown
            // (including the teardown of a session that was never presented).
            onChanged = { com.noop.notif.LiftSessionNotifier.update(this, liftSession) },
        )
    }

    /**
     * Active device id, resolved once at startup from the registry and falling back to the legacy
     * "my-whoop" if the registry has none yet. Read with a guarded blocking call — a one-off indexed
     * `LIMIT 1` query at composition time. Any failure (e.g. an early read before migration) is swallowed
     * and falls back, so startup can never be broken by this.
     *
     * #1303: NOT a `by lazy`. Serial adoption re-points the ACTIVE device mid-process, and a lazy is
     * frozen for the life of the process — so every consumer below kept the pre-adoption id until the next
     * cold start, and the engine went on deriving days under it. Field-confirmed on a 5/MG: the registry
     * read `whoop-<serial>` while the diagnostics export, and the scoring pass, still used the old
     * address-based id, splitting the computed history across both until the phone was restarted. Adoption
     * now calls [onActiveDeviceAdopted] and the handle follows within the process.
     */
    @Volatile
    var activeDeviceId: String = ""
        get() {
            if (field.isEmpty()) {
                field = runCatching { runBlocking { deviceRegistry.activeDeviceId() } }
                    .onFailure { Log.w("NoopApplication", "activeDeviceId resolve failed; using fallback", it) }
                    .getOrNull() ?: WhoopBleClient.DEFAULT_DEVICE_ID
            }
            return field
        }
        private set

    /**
     * Point this process at the id a strap just adopted (#1303).
     *
     * Only the handle moves: the registry write and the row migration have already happened inside
     * `adoptSerialIdentity`, and the BLE client is re-pointed by its own caller. Kept narrow and
     * idempotent so a reconnect that re-adopts the same id costs nothing.
     */
    fun onActiveDeviceAdopted(newId: String) {
        if (newId.isNotEmpty() && newId != activeDeviceId) activeDeviceId = newId
    }

    /**
     * The id the BLE client should stamp WHOOP samples with at startup (#1881).
     *
     * [activeDeviceId] answers "which device did the user select", which is NOT the same question once a
     * non-WHOOP device can be active: handing it to the client made every WHOOP live sample and historical
     * chunk persist under, say, an Oura ring. `adoptSourceIdentity` corrects the id when a strap actually
     * connects, but not for the window between construction and that connect, so the wrong value must not
     * be adopted in the first place.
     *
     * Fail-open: an unreadable registry or an unclassifiable row keeps today's behaviour. Only a
     * POSITIVELY non-WHOOP active device falls back to the legacy id. Swift twin: `BLEManager.bootstrapStore`.
     */
    private fun whoopStartupDeviceId(): String {
        val id = activeDeviceId
        val rows = runCatching { runBlocking { deviceRegistry.all() } }.getOrNull() ?: return id
        val row = rows.firstOrNull { it.id == id } ?: return id
        return if (SourceIdentity.isWhoop(row)) id else WhoopBleClient.DEFAULT_DEVICE_ID
    }

    /** Process-wide BLE client. Owns the GATT connection and outlives any single Activity/ViewModel. */
    val ble: WhoopBleClient by lazy {
        val startupId = whoopStartupDeviceId()
        WhoopBleClient(
            applicationContext,
            repository = repository,
            deviceId = startupId,
            successfulOffloadSink = {
                SelfHostedPushScheduler.enqueueAfterSuccessfulOffload(applicationContext)
            },
        ).apply {
            // #1881: the same fact seeds the connect gate, closing the launch race where the radio can
            // reach the WHOOP flow before SourceCoordinator has wired up and asserted it.
            if (startupId != activeDeviceId) setWhoopIsActiveDevice(false)
            // Apply the persisted "Debug logging" preference at the composition root so the low-level
            // client never has to read the UI/prefs layer. Default OFF — see WhoopBleClient.debugLogcat.
            debugLogcat = NoopPrefs.debugLogging(applicationContext)
        }
    }

    /**
     * Multi-source coordinator (Phase 1B): runs exactly one device's live BLE at a time, driven by the
     * registry's active device id. DORMANT whenever the active device is the WHOOP (the default and every
     * single-WHOOP install), so the existing WHOOP flow is untouched. Only when a non-WHOOP generic HR
     * strap becomes active does it pause WHOOP and run the isolated [com.noop.ble.StandardHrSource].
     *
     * Wired to the EXISTING [ble] entry points via closures — it never touches [WhoopBleClient]
     * internals. Strap live HR is pushed into the same [ble] state flow the UI observes via
     * [WhoopBleClient.publishExternalLiveHr]. [SourceCoordinator.start] reconciles once against the
     * current active id at launch (a no-op for a single-WHOOP install); the Devices screen (next task)
     * calls [SourceCoordinator.onActiveDeviceChanged] after a setActive.
     *
     * Multi-WHOOP identity adoption: AppViewModel's init collects [WhoopBleClient.connectedPeripheralAddress]
     * (distinctUntilChanged) into [SourceCoordinator.connectedPeripheralChanged] — the Kotlin analogue of
     * macOS wiring `BLEManager.connectedPeripheralUUID` into the coordinator's adoption sink. Kept beside
     * the other `ble`-flow collectors there (this Application owns no CoroutineScope of its own).
     */
    val sourceCoordinator: SourceCoordinator by lazy {
        SourceCoordinator(
            context = applicationContext,
            registry = deviceRegistry,
            repository = repository,
            liveSink = { hr, rr -> ble.publishExternalLiveHr(hr, rr) },
            // #74: reconnect on the PERSISTED family, not the WhoopModel.WHOOP4 default - otherwise a
            // 5/MG WHOOP->WHOOP switch rescans the wrong service and misses the 5/MG direct-bond fast
            // path (status=133 on an OS-bonded strap). Mirrors macOS AppModel.scan() reading the persisted
            // "selectedWhoopModel". Same-strap switches now adopt in place (no reconnect) via the
            // coordinator, so this only fires for a genuinely different WHOOP.
            // #1881: the flag rides the SAME two closures, so it inherits the coordinator's semantics
            // exactly. `stopWhoop` alone was edge-triggered: it dropped the link once and nothing stopped
            // `onBluetoothRadioOn` bringing it straight back — every Bluetooth toggle reached it, and it
            // clears `intentionalDisconnect` before reconnecting. Swift twin: AppModel.wireSourceCoordinator.
            startWhoop = { ble.setWhoopIsActiveDevice(true); ble.connect(persistedWhoopModel()) },
            stopWhoop = { ble.setWhoopIsActiveDevice(false); ble.disconnect() },
            // Multi-WHOOP (MW-2/MW-3): pin the connection to the active WHOOP's persisted address and
            // re-attribute live samples to it on a WHOOP→WHOOP switch. Both inert on the single-WHOOP
            // path — the coordinator only invokes them for a non-legacy WHOOP / a non-null peripheralId.
            setWhoopPreferredAddress = { addr -> ble.preferredAddress = addr },
            setWhoopActiveDeviceId = { id -> ble.setActiveDeviceId(id) },
            // Generic-HR connect lifecycle → the SAME in-app strap log the user exports, so a
            // "connected but no data" report (issue #421) is no longer blind to the Polar/Wahoo/etc path.
            straplog = { ble.externalLog(it) },
            // A generic strap's standard battery (0x180F) → the same live battery field the WHOOP uses.
            batterySink = { pct -> ble.publishExternalBattery(pct) },
            initialActiveDeviceId = activeDeviceId,
        )
    }

    /** The WHOOP family last seen advertising, persisted by [WhoopBleClient.persistSelectedModel] under
     *  "noop.selectedWhoopModel" in the shared noop_prefs store. Defaults to [WhoopModel.WHOOP4] when
     *  unset or unparseable (the historical connect() default), so a fresh install is unchanged. Used to
     *  reconnect on the right service after a WHOOP->WHOOP switch (#74). */
    private fun persistedWhoopModel(): WhoopModel = persistedWhoopModelOrNull() ?: WhoopModel.WHOOP4

    /**
     * The persisted family, or null when nothing has been recorded yet.
     *
     * Split from [persistedWhoopModel] because the default it applies, WHOOP4, is indistinguishable
     * from a genuine recorded WHOOP4, and a caller choosing between this and some other source needs to
     * know which it got. `AppViewModel` needs exactly that: a recorded family should beat the remembered
     * pair, while an install that predates this pref must keep falling back to it rather than being
     * silently reset to WHOOP4.
     */
    internal fun persistedWhoopModelOrNull(): WhoopModel? =
        NoopPrefs.of(this).getString("noop.selectedWhoopModel", null)
            ?.let { runCatching { WhoopModel.valueOf(it) }.getOrNull() }

    companion object {
        @Volatile private var instance: NoopApplication? = null

        /** Resolve app-owned UI copy from composable and non-composable presentation helpers alike. */
        fun localizedString(@StringRes id: Int, vararg formatArgs: Any): String {
            val app = checkNotNull(instance) { "NoopApplication is not attached" }
            return if (formatArgs.isEmpty()) app.getString(id) else app.getString(id, *formatArgs)
        }

        /** Quantity-aware twin of [localizedString]: resolves a `<plurals>` for [count] under the active
         *  locale's own plural rules. Same Application-resources path, so it stays locale-aware off the
         *  composition. */
        fun localizedPlural(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String {
            val app = checkNotNull(instance) { "NoopApplication is not attached" }
            return app.resources.getQuantityString(id, count, *formatArgs)
        }
    }
}
