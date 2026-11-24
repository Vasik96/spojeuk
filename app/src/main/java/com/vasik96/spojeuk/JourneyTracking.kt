package com.vasik96.spojeuk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sledovaná cesta (jedna najednou). Spojení se drží jen v paměti procesu: když systém proces ukončí,
 * služba po restartu nemá co sledovat a skončí.
 */
object JourneyTracker {
    private val _current = MutableStateFlow<Journey?>(null)
    val current: StateFlow<Journey?> = _current

    /** Ťuknutí na notifikaci: aplikace má otevřít detail sledovaného spojení. */
    val openRequested = MutableStateFlow(false)

    fun isTracking(j: Journey): Boolean = _current.value?.key == j.key

    fun start(context: Context, j: Journey) {
        _current.value = j
        ContextCompat.startForegroundService(context, Intent(context, JourneyTrackingService::class.java))
    }

    fun stop(context: Context) {
        _current.value = null
        context.startService(Intent(context, JourneyTrackingService::class.java).setAction(JourneyTrackingService.ACTION_STOP))
    }

    internal fun clear() { _current.value = null }
}

/**
 * Sledování cesty jako Live Update (Android 16+: připnutá notifikace nahoře, čip ve stavovém řádku
 * s časem do odjezdu / výstupu; na starších verzích běžná trvalá notifikace). Průběh cesty je ProgressStyle:
 * úseky v barvách druhu dopravy, body = přestupy. Data (živá zpoždění) se obnovují každých 10–20 s.
 */
class JourneyTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    /** Jízda, na jejíž výstup už se upozorňovalo (jen jednou). */
    private var alightAlerted = -1
    private var lastRerouteMs = 0L

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    @Volatile private var latestLocation: Location? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val j = JourneyTracker.current.value
        if (intent?.action == ACTION_STOP || j == null) {
            finish()
            return START_NOT_STICKY
        }
        ensureChannels(this)
        val repo = TransitRepository.get(this)
        startLocationUpdates()
        // startForeground musí proběhnout hned (do 5 s od startu služby)
        val first = buildNotification(this, j, repo.journeyDetailNow(j).orEmpty(), repo.journeyLiveNow(j))
        val fgsType = if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        ServiceCompat.startForeground(this, NOTIF_ID, first, fgsType)
        alightAlerted = -1
        lastRerouteMs = 0L
        loop?.cancel()
        loop = scope.launch { run(j, repo) }
        return START_NOT_STICKY
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
                .setMinUpdateIntervalMillis(5_000L)
                .build()
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(res: LocationResult) {
                    latestLocation = res.lastLocation ?: return
                }
            }
            fusedLocationClient?.requestLocationUpdates(req, locationCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Bez přístupu k poloze se pokračuje podle jízdního řádu
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        locationCallback = null
        fusedLocationClient = null
    }

    private suspend fun run(initialJ: Journey, repo: TransitRepository) {
        val nm = NotificationManagerCompat.from(this)
        var j = initialJ
        while (JourneyTracker.current.value?.key == j.key) {
            val detail = repo.journeyDetailNow(j) ?: runCatching { repo.journeyDetail(j) }.getOrDefault(emptyList())
            val live = runCatching { repo.journeyLive(j) }.getOrDefault(emptyMap())
            val delays = live.mapValues { it.value.delay }
            val nowSec = secondsSinceMidnight(j.day)
            val phase = trackPhase(j.rawLegs, nowSec, delays)

            // 1. Kontrola zmeškaného přestupu / odjezdu a případné automatické přeplánování na nový spoj
            val reroutedJ = maybeCheckMissedTransferAndReroute(j, delays, phase, nowSec, repo)
            if (reroutedJ != null) {
                j = reroutedJ
                alightAlerted = -1
                continue
            }

            // po dojezdu ještě chvíli ukázat "v cíli", pak konec
            if (phase is TrackPhase.Arrived && nowSec > phase.arrSec + 120) break

            runCatching { nm.notify(NOTIF_ID, buildNotification(this, j, detail, live)) }
            maybeAlertAlighting(j, detail, phase, nowSec, repo)

            val soon = when (phase) {
                is TrackPhase.BeforeDeparture -> phase.depSec - nowSec < 300
                is TrackPhase.Transfer -> phase.depSec - nowSec < 300
                is TrackPhase.Riding -> phase.arrSec - nowSec < 300
                is TrackPhase.Arrived -> true
            }
            delay(if (soon) 10_000 else 20_000)
        }
        finish()
    }

    /** Jednorázové upozornění (zvuk / vibrace) před výstupem, vyhodnocované podle blízkosti i času. */
    private fun maybeAlertAlighting(j: Journey, detail: List<DetailLeg>, phase: TrackPhase, nowSec: Int, repo: TransitRepository) {
        if (phase !is TrackPhase.Riding || alightAlerted == phase.ride) return
        val leg = j.rawLegs.getOrNull(phase.ride) ?: return
        val coords = repo.stopCoords(leg.toStop)
        val userLoc = latestLocation
        val userLat = userLoc?.latitude
        val userLng = userLoc?.longitude
        val alightLat = coords?.first
        val alightLng = coords?.second

        if (!shouldTriggerAlightAlert(nowSec, phase.arrSec, leg.dep, userLat, userLng, alightLat, alightLng)) return

        alightAlerted = phase.ride
        val ride = detail.filterIsInstance<DetailLeg.Ride>().firstOrNull { it.index == phase.ride } ?: return
        val stop = ride.stops.lastOrNull()?.let { stopLabel(it.name) } ?: return
        val isLast = phase.ride == rideIndices(j.rawLegs).last()

        val distStr = if (userLat != null && userLng != null && alightLat != null && alightLng != null) {
            val distM = Geo.distanceM(userLat, userLng, alightLat, alightLng)
            if (distM < 1000) fmtDist(distM) else null
        } else null

        val title = if (distStr != null) tr(R.string.track_alert_alight_dist, stop, distStr)
        else tr(R.string.track_alert_alight, stop)

        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_transit)
            .setContentTitle(title)
            .setContentText(tr(if (isLast) R.string.track_alert_alight_final else R.string.track_alert_alight_transfer))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(4 * 60_000L)
            .setContentIntent(openIntent(this))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(ALERT_ID, n) }
    }

    /** Automatické vyhledání náhradního spojení při zmeškání přestupu. */
    private suspend fun maybeCheckMissedTransferAndReroute(
        j: Journey,
        delays: Map<Int, Int>,
        phase: TrackPhase,
        nowSec: Int,
        repo: TransitRepository,
    ): Journey? {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastRerouteMs < 60_000L) return null

        val rides = rideIndices(j.rawLegs)
        var isMissed = false
        var transferStopId: Int? = null

        // 1. Kontrola jednotlivých přestupů v cestě
        for (k in 0 until rides.size - 1) {
            val b = rides[k + 1]
            val stopId = j.rawLegs[b].fromStop
            val coords = repo.stopCoords(stopId)
            val hasLiveB = delays.containsKey(b)
            val missed = isTransferMissed(
                j.rawLegs, k, delays, nowSec,
                latestLocation?.latitude, latestLocation?.longitude,
                coords?.first, coords?.second,
                hasLiveB = hasLiveB
            )
            if (missed) {
                isMissed = true
                transferStopId = stopId
                break
            }
        }

        // 2. Kontrola, zda nebylo zmeškáno první nastoupení
        if (!isMissed) {
            val r0 = rides.firstOrNull()
            if (r0 != null) {
                val boardId = j.rawLegs[r0].fromStop
                val coords = repo.stopCoords(boardId)
                val hasLiveFirst = delays.containsKey(r0)
                if (isInitialDepartureMissed(
                        j.rawLegs, delays, nowSec,
                        latestLocation?.latitude, latestLocation?.longitude,
                        coords?.first, coords?.second,
                        hasLiveFirst = hasLiveFirst
                    )
                ) {
                    isMissed = true
                    transferStopId = boardId
                }
            }
        }

        if (!isMissed) return null

        lastRerouteMs = nowMs

        val userLoc = latestLocation
        val fromStopName: String? = if (userLoc != null) {
            val cand = repo.nearestCandidates(userLoc.latitude, userLoc.longitude, limit = 3).firstOrNull()
            if (cand != null && cand.second < 1000.0) prettyName(cand.first.name)
            else transferStopId?.let { repo.stopName(it) }
        } else transferStopId?.let { repo.stopName(it) }

        val toStopId = j.rawLegs.last().toStop
        val toStopName = repo.stopName(toStopId)

        if (fromStopName == null || toStopName == null) return null

        val altJourneys = runCatching {
            repo.searchConnections(fromStopName, toStopName, startTime = nowPrague(), count = 5)
        }.getOrDefault(emptyList())

        val newJ = altJourneys.firstOrNull { it.key != j.key && it.dep >= nowSec - 60 }
        val nm = NotificationManagerCompat.from(this)

        if (newJ != null) {
            JourneyTracker.start(this, newJ)
            val newDetail = repo.journeyDetailNow(newJ) ?: runCatching { repo.journeyDetail(newJ) }.getOrNull()
            val lineName = newDetail?.filterIsInstance<DetailLeg.Ride>()?.firstOrNull()?.line ?: "?"
            val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_transit)
                .setContentTitle(tr(R.string.track_alert_reroute_title))
                .setContentText(tr(R.string.track_alert_reroute_text, lineName, stopLabel(fromStopName), hhmm(newJ.dep)))
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(6 * 60_000L)
                .setContentIntent(openIntent(this))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            runCatching { nm.notify(ALERT_ID, n) }
            return newJ
        } else {
            val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_transit)
                .setContentTitle(tr(R.string.track_alert_reroute_title))
                .setContentText(tr(R.string.track_alert_reroute_failed))
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(6 * 60_000L)
                .setContentIntent(openIntent(this))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            runCatching { nm.notify(ALERT_ID, n) }
            return null
        }
    }

    private fun finish() {
        stopLocationUpdates()
        loop?.cancel()
        JourneyTracker.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.vasik96.spojeuk.STOP_TRACKING"
        const val EXTRA_OPEN_TRACKED = "open_tracked"
        private const val CHANNEL = "journey_tracking"
        private const val CHANNEL_ALERTS = "journey_alerts"
        private const val NOTIF_ID = 4201
        private const val ALERT_ID = 4202

        private fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr(R.string.track_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = tr(R.string.track_channel_desc)
                    setSound(null, null)          // průběžné aktualizace nepípají
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, tr(R.string.track_channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = tr(R.string.track_channel_alerts_desc)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }

        private fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TRACKED, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun min(sec: Int) = maxOf(0, (sec + 59) / 60)

        /** Notifikace podle fáze cesty: co dělat teď, kdy, a průběh celé cesty. */
        internal fun buildNotification(ctx: Context, j: Journey, detail: List<DetailLeg>, live: Map<Int, Vehicle>): android.app.Notification {
            val delays = live.mapValues { it.value.delay }
            val nowSec = secondsSinceMidnight(j.day)
            val phase = trackPhase(j.rawLegs, nowSec, delays)
            val rides = detail.filterIsInstance<DetailLeg.Ride>().associateBy { it.index }
            fun lineOf(i: Int) = rides[i]?.line ?: "?"
            fun boardOf(i: Int) = rides[i]?.stops?.firstOrNull()?.name?.let { stopLabel(it) }.orEmpty()
            fun alightOf(i: Int) = rides[i]?.stops?.lastOrNull()?.name?.let { stopLabel(it) }.orEmpty()
            fun headOf(i: Int) = rides[i]?.headsign?.let { stopLabel(it) }.orEmpty()
            fun delayNote(i: Int) = delays[i]?.takeIf { it != 0 }?.let { " " + tr(R.string.track_delay_note, it) }.orEmpty()
            val rideIdx = rideIndices(j.rawLegs)

            val title: String
            val text: String
            val chip: String
            val target: Int
            when (phase) {
                is TrackPhase.BeforeDeparture -> {
                    val m = min(phase.depSec - nowSec)
                    title = tr(R.string.track_before, lineOf(phase.ride), m)
                    text = tr(R.string.track_before_text, boardOf(phase.ride), hhmm(phase.depSec), headOf(phase.ride)) + delayNote(phase.ride)
                    chip = tr(R.string.fmt_min, m); target = phase.depSec
                }
                is TrackPhase.Riding -> {
                    val m = min(phase.arrSec - nowSec)
                    val next = rideIdx.getOrNull(rideIdx.indexOf(phase.ride) + 1)
                    title = tr(R.string.track_riding, alightOf(phase.ride), m)
                    text = (if (next != null) tr(R.string.track_riding_then, hhmm(phase.arrSec), lineOf(next))
                            else tr(R.string.track_riding_final, hhmm(phase.arrSec))) + delayNote(phase.ride)
                    chip = tr(R.string.fmt_min, m); target = phase.arrSec
                }
                is TrackPhase.Transfer -> {
                    val m = min(phase.depSec - nowSec)
                    val k = rideIdx.indexOf(phase.ride) - 1
                    val risk = transferStatus(j.rawLegs, k, delays, nowSec)?.takeIf { it.risk == TransferRisk.AT_RISK }
                    title = tr(R.string.track_transfer, lineOf(phase.ride), m)
                    text = tr(R.string.track_transfer_text, boardOf(phase.ride), hhmm(phase.depSec), headOf(phase.ride)) +
                            delayNote(phase.ride) + (risk?.let { " · " + tr(R.string.jd_transfer_at_risk, it.delayMin) }.orEmpty())
                    chip = tr(R.string.fmt_min, m); target = phase.depSec
                }
                is TrackPhase.Arrived -> {
                    title = tr(R.string.track_arrived, alightOf(rideIdx.last()))
                    text = tr(R.string.track_arrived_text, hhmm(phase.arrSec))
                    chip = hhmm(phase.arrSec); target = phase.arrSec
                }
            }

            // průběh: úseky v pořadí cesty (jízdy v barvě druhu dopravy, chůze a čekání šedě), body = přestupy
            val start = j.rawLegs.first().dep
            val end = j.rawLegs.last().arr
            val grey = 0xFF9E9E9E.toInt()
            val segments = ArrayList<NotificationCompat.ProgressStyle.Segment>()
            val points = ArrayList<NotificationCompat.ProgressStyle.Point>()
            var cursor = start
            for ((i, leg) in j.rawLegs.withIndex()) {
                if (leg.dep > cursor) segments += NotificationCompat.ProgressStyle.Segment(leg.dep - cursor).setColor(grey)
                val len = maxOf(1, leg.arr - maxOf(leg.dep, cursor))
                val color = if (leg.kind == "ride") rides[i]?.style?.color ?: grey else grey
                segments += NotificationCompat.ProgressStyle.Segment(len).setColor(color)
                cursor = maxOf(cursor, leg.arr)
                if (leg.kind == "ride" && i != rideIdx.last()) {
                    points += NotificationCompat.ProgressStyle.Point(leg.arr - start).setColor(color)
                }
            }
            val total = maxOf(1, end - start)
            // zpoždění posouvá "kde jsem" zpět (podle plánu bych byl dál)
            val curDelay = when (phase) {
                is TrackPhase.Riding -> delays[phase.ride] ?: 0
                is TrackPhase.BeforeDeparture -> delays[phase.ride] ?: 0
                is TrackPhase.Transfer -> delays[phase.ride] ?: 0
                is TrackPhase.Arrived -> 0
            }
            val progress = (nowSec - curDelay * 60 - start).coerceIn(0, total)
            val style = NotificationCompat.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressSegments(segments)
                .setProgressPoints(points)
                .setProgress(progress)
                .setProgressTrackerIcon(IconCompat.createWithResource(ctx, R.drawable.ic_stat_transit))

            val targetMs = j.day.atStartOfDay(PRAGUE_ZONE).plusSeconds(target.toLong()).toInstant().toEpochMilli()
            val stopIntent = PendingIntent.getService(
                ctx, 1,
                Intent(ctx, JourneyTrackingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_transit)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setRequestPromotedOngoing(true)       // Android 16+: Live Update (nahoře, čip ve stavovém řádku)
                .setShortCriticalText(chip)
                .setWhen(targetMs)
                .setShowWhen(false)   // "za X min" je v nadpisu i čipu, systémové "in 10m" by to jen zdvojovalo
                .setContentIntent(openIntent(ctx))
                .addAction(0, tr(R.string.track_stop), stopIntent)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        }
    }
}
