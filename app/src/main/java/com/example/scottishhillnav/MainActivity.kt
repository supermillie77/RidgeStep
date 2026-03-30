// app/src/main/java/com/example/scottishhillnav/MainActivity.kt
package com.example.scottishhillnav

import android.Manifest
import android.view.GestureDetector
import android.view.MotionEvent
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.app.Activity
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import com.example.scottishhillnav.DemProvider
import com.example.scottishhillnav.hills.CarPark
import com.example.scottishhillnav.hills.Attraction
import com.example.scottishhillnav.hills.Hill
import com.example.scottishhillnav.hills.HillRepository
import com.example.scottishhillnav.hills.HillSearchService
import com.example.scottishhillnav.hills.PreferredParking
import com.example.scottishhillnav.hills.HillSuggestionService
import com.example.scottishhillnav.hills.WeatherService
import com.example.scottishhillnav.navigation.ElevationProfileModel
import com.example.scottishhillnav.navigation.GaelicPronouncer
import com.example.scottishhillnav.navigation.InstructionGenerator
import com.example.scottishhillnav.navigation.OsGridRef
import com.example.scottishhillnav.navigation.RouteIndex
import com.example.scottishhillnav.navigation.VoiceNavigator
import com.example.scottishhillnav.routing.*
import com.example.scottishhillnav.ui.ElevationProfileView
import com.example.scottishhillnav.ui.LocationDotOverlay
import com.example.scottishhillnav.ui.NearbyHillsSheet
import com.example.scottishhillnav.ui.RouteGradientOverlay
import com.example.scottishhillnav.ui.SummitInfoSheet
import com.example.scottishhillnav.ui.SummitOverlay
import com.google.android.gms.location.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var graph: Graph           // active drawing/navigation graph (may include Overpass data)
    private lateinit var bundledGraph: Graph    // original bundled graph — never mutated, used for coverage checks
    private lateinit var bundledRouter: AStarRouter  // router anchored to bundledGraph — never replaced
    private lateinit var router: AStarRouter
    private lateinit var metricsCalculator: RouteMetricsCalculator
    private lateinit var candidateGenerator: RouteCandidateGenerator
    /** Set to true once the bundled graph and all routing objects are initialised on the background thread. */
    @Volatile private var graphReady = false

    // Ordered tap points: [start, (waypoint1, waypoint2, ...,) destination]
    private val tapPoints   = mutableListOf<GeoPoint>()
    private val tapMarkers  = mutableListOf<Marker>()

    private val routeCandidates = mutableListOf<RouteCandidate>()
    private var activeIndex = 0

    private lateinit var routeOverlay: RouteGradientOverlay
    private lateinit var routeSelectorRow: LinearLayout

    // Bottom sheet UI
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var sheetTitle: TextView
    private lateinit var sheetSubtitle: TextView
    private lateinit var hillPronounceBtn: TextView
    private lateinit var statDistance: TextView
    private lateinit var statAscent: TextView
    private lateinit var statTime: TextView
    private lateinit var statTtd: TextView
    private lateinit var statHeight: TextView
    private lateinit var statSpeed: TextView
    private lateinit var statGridRef: TextView

    // GPS
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var progressMeters = 0.0

    // Route navigation index
    private var routeIndex: RouteIndex? = null
    private var elevationProfile: ElevationProfileModel? = null

    // Voice navigation
    private lateinit var voiceNavigator: VoiceNavigator
    private lateinit var instructionGenerator: InstructionGenerator
    private lateinit var voiceFab: ExtendedFloatingActionButton

    // Off-track detection
    private var offTrackCount         = 0          // consecutive GPS fixes showing off-route
    private var lastOffTrackAnnounce  = 0L         // epoch-ms of last off-route announcement

    // Hill/walk search voice input — holds the EditText that should receive spoken text
    private var pendingVoiceTarget: EditText? = null

    // Hill selection & road-navigation phase
    private enum class NavPhase { IDLE, DRIVING_TO_CARPARK, HILL_NAV }
    private var navPhase         = NavPhase.IDLE
    private var selectedHill     : HillSearchService.HillResult? = null
    private var selectedCarPark  : CarPark? = null
    /** Route ID from the pre-selection picker (e.g. "lomond_tourist"). Null = no preference. */
    private var preferredRouteId : String? = null
    private lateinit var driveBanner: FrameLayout  // shown while driving to car park
    private lateinit var routingBanner: TextView   // shown on the map while route search is running
    private lateinit var addSummitRow: LinearLayout // "＋ Add another hill" chip row in bottom sheet
    private var addingSummit = false               // true when hill search is in "add waypoint" mode
    private var hasAnimatedToUser = false          // pans map to GPS location on first fix
    private var hasCheckedWeather = false          // weather check fires once after first GPS fix
    private var programmaticScroll = false         // true during auto-zoom so scroll events don't falsely trigger locate button
    private lateinit var locationDotOverlay: LocationDotOverlay
    private lateinit var locateFab: FloatingActionButton
    private lateinit var weatherBanner: LinearLayout
    private lateinit var weatherBannerText: TextView
    private lateinit var weatherFindBtn: TextView

    // Overpass routing graph cache — reused when switching between nearby car parks so we
    // don't make a fresh Overpass call (and hit rate-limits) for every car park selection.
    private var cachedOverpassRoutingGraph: Graph? = null
    private var cachedOverpassCenterLat = 0.0
    private var cachedOverpassCenterLon = 0.0
    private var cachedOverpassRadiusM   = 0

    /** A known named route entry in the pre-selection catalogue. */
    private data class CatalogueRoute(
        val id: String,           // matches RouteCandidate.id
        val name: String,
        val description: String
    )

    private companion object {
        const val OFF_TRACK_THRESHOLD_M  = 50.0    // metres before considered off route
        const val OFF_TRACK_TRIGGER_FIXES = 3      // consecutive fixes before announcing
        const val OFF_TRACK_REANNOUNCE_MS    = 30_000L
        const val CARPARK_ARRIVAL_M          = 200.0
        const val LOCATION_PERMISSION_REQUEST = 1001
        const val SPEECH_REQUEST_CODE         = 2001
        // Ben Nevis summit — used to decide whether curated routes apply
        const val BEN_NEVIS_LAT = 56.7969
        const val BEN_NEVIS_LON = -5.0035
        // Ben Lomond summit
        const val BEN_LOMOND_LAT = 56.1904
        const val BEN_LOMOND_LON = -4.6345

        /** Known named routes per hill (case-insensitive hill name key). */
        val ROUTE_CATALOGUE: Map<String, List<CatalogueRoute>> = mapOf(
            "ben lomond" to listOf(
                CatalogueRoute("lomond_tourist",   "Tourist Route",   "South Ridge — most popular path"),
                CatalogueRoute("lomond_ptarmigan", "Ptarmigan Route", "Ptarmigan Ridge — wilder approach")
            ),
            "ben nevis" to listOf(
                CatalogueRoute("tourist", "Tourist Path",  "Main Mountain Track"),
                CatalogueRoute("cmd",     "CMD Arête",     "Carn Mòr Dearg Arête — scramble"),
                CatalogueRoute("ledge",   "Ledge Route",   "North Face — experienced only")
            )
        )
    }


    // Live popup
    private var statsDialog: Dialog? = null
    private val popupHandler = Handler(Looper.getMainLooper())
    private var popupUpdateRunnable: Runnable? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastLocation = loc
            locationDotOverlay.location = loc
            map.invalidate()
            // On the very first GPS fix: zoom map to 5-mile radius around user and check weather
            if (!hasAnimatedToUser) {
                hasAnimatedToUser = true
                val radiusM = 8047.2   // 5 miles in metres
                val latDelta = radiusM / 111320.0
                val lonDelta = radiusM / (111320.0 * Math.cos(Math.toRadians(loc.latitude)))
                val bbox = BoundingBox(
                    loc.latitude  + latDelta, loc.longitude + lonDelta,
                    loc.latitude  - latDelta, loc.longitude - lonDelta
                )
                programmaticScroll = true
                map.post {
                    map.zoomToBoundingBox(bbox, true)
                    map.postDelayed({ programmaticScroll = false }, 1_500)
                }
                preCacheTiles(loc.latitude, loc.longitude)
                if (!hasCheckedWeather) {
                    hasCheckedWeather = true
                    checkWeatherForLocation(loc.latitude, loc.longitude)
                }
            }
            if (navPhase == NavPhase.DRIVING_TO_CARPARK) {
                checkCarParkArrival(loc)
            } else {
                updateProgressFromLocation(loc)
            }
            updateLiveStatsInSheet()
            updateStatsPopupIfOpen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osm", MODE_PRIVATE))
            userAgentValue = packageName

            // Persistent tile cache — external app files dir won't be cleared by
            // the OS under storage pressure (unlike cacheDir).  Falls back to
            // internal files dir on devices with no external storage.
            osmdroidTileCache = (getExternalFilesDir("osmdroid_tiles")
                ?: java.io.File(filesDir, "osmdroid_tiles")).also { it.mkdirs() }

            // 1 GB on-disk tile cache — enough for full UK coverage at zoom 7–16
            tileFileSystemCacheMaxBytes      = 1_024L * 1_024 * 1_024        // 1 GB
            tileFileSystemCacheTrimBytes     = 900L   * 1_024 * 1_024        // trim to 900 MB

            // Tiles remain valid for 30 days — hill maps change infrequently
            expirationOverrideDuration       = 30L * 24 * 60 * 60 * 1_000   // 30 days ms

            // Keep 256 decoded tiles in memory (default is 9 — far too few)
            cacheMapTileCount                = 256.toShort()

            // 4 download threads — faster first-load in good signal areas
            tileDownloadThreads              = 4.toShort()
            tileDownloadMaxQueueSize         = 40.toShort()
        }

        DemProvider.init(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        voiceNavigator = VoiceNavigator(this)

        val root = CoordinatorLayout(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ── Map ──────────────────────────────────────────────────────────────
        map = MapView(this).apply {
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)
            val topoSource = object : OnlineTileSourceBase(
                "OpenTopoMap", 0, 17, 256, ".png",
                arrayOf(
                    "https://tile.opentopomap.org/",
                    "https://a.tile.opentopomap.org/",
                    "https://b.tile.opentopomap.org/",
                    "https://c.tile.opentopomap.org/"
                )
            ) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    val z = MapTileIndex.getZoom(pMapTileIndex)
                    val x = MapTileIndex.getX(pMapTileIndex)
                    val y = MapTileIndex.getY(pMapTileIndex)
                    val base = baseUrl.trimEnd('/')
                    return "$base/$z/$x/$y.png"
                }
            }
            setTileSource(topoSource)
            controller.setZoom(8.0)
            controller.setCenter(GeoPoint(57.0, -4.5))  // Scotland overview — GPS will refine on first fix
        }
        root.addView(map, CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            CoordinatorLayout.LayoutParams.MATCH_PARENT
        ))

        routeOverlay = RouteGradientOverlay { graph }
        map.overlays.add(routeOverlay)
        map.overlays.add(SummitOverlay(resources.displayMetrics.density))

        // ── User location overlay (blue dot fed from fused client) ───────────
        locationDotOverlay = LocationDotOverlay(resources.displayMetrics.density)
        map.overlays.add(locationDotOverlay)

        // ── SharedPreferences: track first-use of each button ────────────────
        val btnPrefs = getSharedPreferences("button_state", MODE_PRIVATE)

        // ── Stats FAB ────────────────────────────────────────────────────────
        val fab = ExtendedFloatingActionButton(this).apply {
            setIconResource(android.R.drawable.ic_menu_info_details)
            setText("Stats & info")
            contentDescription = "Navigation stats"
            if (btnPrefs.getBoolean("used_info", false)) isExtended = false
            setOnClickListener {
                if (!btnPrefs.getBoolean("used_info", false)) {
                    btnPrefs.edit().putBoolean("used_info", true).apply()
                    shrink()
                }
                showStatsPopup()
            }
        }
        val fabParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            val margin = (resources.displayMetrics.density * 16).toInt()
            val peekOffset = (resources.displayMetrics.density * 156).toInt()
            setMargins(margin, margin, margin, peekOffset)
        }
        root.addView(fab, fabParams)

        // ── Voice mute FAB (above the info FAB) ──────────────────────────────
        voiceFab = ExtendedFloatingActionButton(this).apply {
            setIconResource(android.R.drawable.ic_lock_silent_mode_off)
            setText("Voice guide")
            contentDescription = "Mute voice navigation"
            if (btnPrefs.getBoolean("used_voice", false)) isExtended = false
            setOnClickListener {
                if (!btnPrefs.getBoolean("used_voice", false)) {
                    btnPrefs.edit().putBoolean("used_voice", true).apply()
                    shrink()
                }
                toggleVoiceMute()
            }
        }
        val voiceFabParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            val margin    = (resources.displayMetrics.density * 16).toInt()
            val peekOff   = (resources.displayMetrics.density * 156).toInt()
            val fabSize   = (resources.displayMetrics.density * 56).toInt()
            setMargins(margin, margin, margin, peekOff + fabSize + margin)
        }
        root.addView(voiceFab, voiceFabParams)

        // ── Select Hill FAB (bottom-right, above voice FAB) ──────────────────
        val hillFab = ExtendedFloatingActionButton(this).apply {
            setIconResource(android.R.drawable.ic_menu_search)
            setText("Find a hill")
            contentDescription = "Find a hill"
            if (btnPrefs.getBoolean("used_find", false)) isExtended = false
            setOnClickListener {
                if (!btnPrefs.getBoolean("used_find", false)) {
                    btnPrefs.edit().putBoolean("used_find", true).apply()
                    shrink()
                }
                showHillSearch()
            }
        }
        val hillFabParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            val margin  = (resources.displayMetrics.density * 16).toInt()
            val peekOff = (resources.displayMetrics.density * 156).toInt()
            val fabSize = (resources.displayMetrics.density * 56).toInt()
            setMargins(margin, margin, margin, peekOff + (fabSize + margin) * 2)
        }
        root.addView(hillFab, hillFabParams)

        // ── Locate FAB (bottom-left) — appears when user pans away from their position ──
        locateFab = FloatingActionButton(this).apply {
            setImageResource(R.drawable.ic_my_location)
            contentDescription = "Return to my location"
            visibility = View.GONE
            setOnClickListener { returnToMyLocation() }
        }
        val locateFabParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            val margin    = (resources.displayMetrics.density * 16).toInt()
            val peekOffset = (resources.displayMetrics.density * 156).toInt()
            setMargins(margin, margin, margin, peekOffset)
        }
        root.addView(locateFab, locateFabParams)

        // ── Near me FAB (bottom-left, above locate FAB) ──────────────────────
        val nearFab = ExtendedFloatingActionButton(this).apply {
            setIconResource(android.R.drawable.ic_menu_agenda)
            setText("Hills near me")
            contentDescription = "Hills near me — see the closest summits to your location"
            if (btnPrefs.getBoolean("used_near", false)) isExtended = false
            setOnClickListener {
                if (!btnPrefs.getBoolean("used_near", false)) {
                    btnPrefs.edit().putBoolean("used_near", true).apply()
                    shrink()
                }
                showNearbyHills()
            }
        }
        val nearFabParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            val margin     = (resources.displayMetrics.density * 16).toInt()
            val peekOffset = (resources.displayMetrics.density * 156).toInt()
            val fabSize    = (resources.displayMetrics.density * 56).toInt()
            setMargins(margin, margin, margin, peekOffset + fabSize + margin)
        }
        root.addView(nearFab, nearFabParams)

        // Show locate button when user manually pans — but not during the initial GPS animation
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean {
                if (lastLocation != null && !programmaticScroll) {
                    locateFab.visibility = View.VISIBLE
                }
                return false
            }
            override fun onZoom(event: ZoomEvent): Boolean = false
        })

        // ── Top banners container: drive + weather stack vertically at screen top ─
        val topBannerContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Driving banner (hidden until driving phase)
        driveBanner = FrameLayout(this).apply { visibility = View.GONE }
        val bannerContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0D47A1.toInt())  // dark blue
            setPadding(
                (resources.displayMetrics.density * 16).toInt(),
                (resources.displayMetrics.density * 10).toInt(),
                (resources.displayMetrics.density * 12).toInt(),
                (resources.displayMetrics.density * 10).toInt()
            )
            gravity = Gravity.CENTER_VERTICAL
        }
        val bannerLabel = TextView(this).apply {
            id = android.R.id.text1
            textSize = 13.5f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val arriveBtn = TextView(this).apply {
            text = "I'm Here"
            textSize = 13f
            setTextColor(0xFF4FC3F7.toInt())
            setPadding(
                (resources.displayMetrics.density * 12).toInt(), 0,
                (resources.displayMetrics.density * 4).toInt(), 0
            )
            setOnClickListener { arriveAtCarPark() }
        }
        bannerContent.addView(bannerLabel)
        bannerContent.addView(arriveBtn)
        driveBanner.addView(bannerContent, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        topBannerContainer.addView(driveBanner)

        // Weather banner (always shown after first GPS fix; colour reflects conditions)
        weatherBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF2E4A1E.toInt())  // default: dark green (good conditions)
            val ph = (resources.displayMetrics.density * 20).toInt()
            val pv = (resources.displayMetrics.density * 18).toInt()
            setPadding(ph, pv, (resources.displayMetrics.density * 10).toInt(), pv)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (resources.displayMetrics.density * 72).toInt()
            visibility = View.GONE
        }
        weatherBannerText = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        weatherFindBtn = TextView(this).apply {
            text = "Good areas today"
            textSize = 15f
            setTextColor(0xFF80DEEA.toInt())
            val ph = (resources.displayMetrics.density * 14).toInt()
            setPadding(ph, 0, (resources.displayMetrics.density * 4).toInt(), 0)
        }
        val weatherDismissBtn = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(0xFFB0BEC5.toInt())
            val ph = (resources.displayMetrics.density * 10).toInt()
            setPadding(ph, 0, 0, 0)
            setOnClickListener { weatherBanner.visibility = View.GONE }
        }
        weatherBanner.addView(weatherBannerText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        weatherBanner.addView(weatherFindBtn)
        weatherBanner.addView(weatherDismissBtn)
        topBannerContainer.addView(weatherBanner)

        root.addView(topBannerContainer, CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })

        // ── Route-search overlay ──────────────────────────────────────────────
        // Shown in the centre of the map while A* / Overpass download is running.
        // Positioned here (above the bottom sheet) so it's always visible even
        // when the bottom sheet is fully collapsed.
        val dp2 = resources.displayMetrics.density
        routingBanner = TextView(this).apply {
            text = "🔍  Finding route…"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xDD0D0D0D.toInt())
            gravity = Gravity.CENTER
            elevation = 12f
            setPadding(
                (dp2 * 20).toInt(), (dp2 * 10).toInt(),
                (dp2 * 20).toInt(), (dp2 * 10).toInt()
            )
            visibility = View.GONE
        }
        root.addView(routingBanner, CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        // ── Bottom Sheet ─────────────────────────────────────────────────────
        val bottomSheet = FrameLayout(this).apply {
            setBackgroundColor(0xFF171717.toInt())
            elevation = 24f
        }
        val sheetContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 28)
        }

        val handle = View(this).apply { setBackgroundColor(0xFF444444.toInt()) }
        val handleParams = LinearLayout.LayoutParams(
            (resources.displayMetrics.density * 44).toInt(),
            (resources.displayMetrics.density * 5).toInt()
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = (resources.displayMetrics.density * 14).toInt()
        }
        handle.layoutParams = handleParams
        handle.alpha = 0.85f

        sheetTitle = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); text = "Route: —"
        }
        hillPronounceBtn = TextView(this).apply {
            text = "🔊"
            textSize = 22f
            setTextColor(Color.WHITE)
            val p = (resources.displayMetrics.density * 10).toInt()
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                val hill = selectedHill ?: return@setOnClickListener
                val phonetic = GaelicPronouncer.phonetic(hill.name)
                voiceNavigator.pronounce(phonetic)
                val display = if (phonetic != hill.name) "$phonetic\n(${hill.name})" else hill.name
                Toast.makeText(this@MainActivity, display, Toast.LENGTH_LONG).show()
            }
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(sheetTitle, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(hillPronounceBtn)
        sheetSubtitle = TextView(this).apply {
            textSize = 13.5f; setTextColor(0xFFBDBDBD.toInt())
            text = "Tap to cycle routes • Long-press to clear"
        }

        val primaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 16)
        }
        statDistance = statBox("Distance", "—")
        statAscent   = statBox("Ascent", "—")
        statTime     = statBox("Time", "—")
        primaryRow.addView(statDistance, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        primaryRow.addView(space(10))
        primaryRow.addView(statAscent,   LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        primaryRow.addView(space(10))
        primaryRow.addView(statTime,     LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val liveHeader = TextView(this).apply {
            textSize = 13.5f; setTextColor(0xFFE0E0E0.toInt())
            text = "Live (Navigation Mode)"; setPadding(0, 10, 0, 10)
        }
        val liveRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statTtd    = statBox("TTD", "—")
        statHeight = statBox("Height", "—")
        liveRow1.addView(statTtd,    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        liveRow1.addView(space(10))
        liveRow1.addView(statHeight, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val liveRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 0)
        }
        statSpeed   = statBox("Speed", "—")
        statGridRef = statBox("Grid Ref", "—")
        liveRow2.addView(statSpeed,   LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        liveRow2.addView(space(10))
        liveRow2.addView(statGridRef, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Route selector chips — populated after routing, hidden until then
        routeSelectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 4)
            visibility = View.GONE
        }

        // "＋ Add another hill" chip — shown when a route is active so the user can
        // chain multiple summits (e.g. two Munros in one walk) without tapping the map.
        addSummitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (resources.displayMetrics.density * 6).toInt(), 0, 0)
            visibility = View.GONE
        }
        val addSummitChip = TextView(this).apply {
            text = "＋  Add another hill"
            textSize = 12f
            setTextColor(0xFF4FC3F7.toInt())
            setBackgroundColor(0xFF1A2A3A.toInt())
            val p = (resources.displayMetrics.density * 10).toInt()
            val pv = (resources.displayMetrics.density * 6).toInt()
            setPadding(p, pv, p, pv)
            setOnClickListener {
                addingSummit = true
                showHillSearch()
            }
        }
        addSummitRow.addView(addSummitChip)

        sheetContent.addView(handle)
        sheetContent.addView(titleRow)
        sheetContent.addView(sheetSubtitle)
        sheetContent.addView(routeSelectorRow)
        sheetContent.addView(addSummitRow)
        sheetContent.addView(primaryRow)
        sheetContent.addView(divider())
        sheetContent.addView(liveHeader)
        sheetContent.addView(liveRow1)
        sheetContent.addView(liveRow2)

        bottomSheet.addView(sheetContent, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        val sheetParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM }

        val behavior = BottomSheetBehavior<FrameLayout>().apply {
            isHideable = false; isFitToContents = true; skipCollapsed = false
            peekHeight = (resources.displayMetrics.density * 140).toInt()
            state = BottomSheetBehavior.STATE_COLLAPSED
        }
        sheetParams.behavior = behavior
        bottomSheetBehavior = behavior
        root.addView(bottomSheet, sheetParams)

        sheetTitle.setOnClickListener { cycleRoute() }
        sheetTitle.setOnLongClickListener { clearRoutes(); true }
        sheetSubtitle.setOnClickListener { cycleRoute() }
        sheetSubtitle.setOnLongClickListener { clearRoutes(); true }

        setContentView(root)

        // ── Load bundled graph on background thread ───────────────────────────
        // Reading 9.2 MB of binary data + parsing 4 GPX routes previously blocked the
        // main thread for 2-4 seconds. Now the UI appears immediately and the loading
        // banner hides once the graph is ready (~1-3 s on a modern device).
        routingBanner.text = "⏳  Loading map data…"
        routingBanner.visibility = View.VISIBLE
        Thread {
            // Phase 1: binary graph (nodes + edges) — typically <200 ms
            HillRepository.initialize(this)
            val base = GraphStore.loadBase(this)
            runOnUiThread {
                initRouters(base)
                graphReady = true
                routingBanner.visibility = View.GONE
                map.invalidate()
            }
            // Phase 2: GPX route enrichment — now O(ms) with spatial grid, silent to the user
            val enriched = GraphStore.enrichWithGpx(this, base)
            runOnUiThread { initRouters(enriched) }
        }.start()

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                handleTap(p); return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                // Long-press on or near a summit triangle → show info card
                val threshold = summitTapThresholdMeters()
                val nearest = HillRepository.hills.minByOrNull {
                    haversine(p.latitude, p.longitude, it.summitLat, it.summitLon)
                }
                if (nearest != null) {
                    val dist = haversine(p.latitude, p.longitude, nearest.summitLat, nearest.summitLon)
                    if (dist < threshold) {
                        val loc = lastLocation
                        val userDist = if (loc != null)
                            haversine(loc.latitude, loc.longitude, nearest.summitLat, nearest.summitLon)
                        else -1.0
                        showSummitInfoSheet(nearest, userDist)
                        return true
                    }
                }
                // Long-press away from summits → clear current route
                clearRoutes()
                return true
            }
        }))

        // Double-tap overlay: intercepts double-tap to clear route, passes everything else through
        map.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
            private val gd = GestureDetector(this@MainActivity,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        clearRoutes()
                        return true
                    }
                })
            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                return gd.onTouchEvent(event)
            }
        })

        updateSheetForNoRoute()
        requestLocationPermission()
    }

    // ── GPS ──────────────────────────────────────────────────────────────────

    private fun requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
            return
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            // User previously denied — explain why before asking again
            AlertDialog.Builder(this)
                .setTitle("Location access needed")
                .setMessage(
                    "RidgeStep uses your location to:\n\n" +
                    "• Show where you are on the map\n" +
                    "• Check local weather conditions\n" +
                    "• Give turn-by-turn walking directions\n\n" +
                    "Your location is never shared or stored."
                )
                .setPositiveButton("Allow location") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        LOCATION_PERMISSION_REQUEST
                    )
                }
                .setNegativeButton("Not now", null)
                .show()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1500L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopPopupUpdates()
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
    }

    // ── Route progress tracking ───────────────────────────────────────────────

    private fun updateProgressFromLocation(location: Location) {
        val idx = routeIndex ?: return
        val candidate = routeCandidates.getOrNull(activeIndex) ?: return
        val nodeIds = candidate.nodeIds

        var bestSq      = Double.MAX_VALUE
        var bestCumDist = 0.0
        var nearestId   = -1
        val lat = location.latitude; val lon = location.longitude

        for (i in nodeIds.indices) {
            val node = graph.nodes[nodeIds[i]] ?: continue
            val dx = (node.lat - lat) * 111_320.0
            val dy = (node.lon - lon) * 111_320.0 * cos(Math.toRadians(lat))
            val sq = dx * dx + dy * dy
            if (sq < bestSq) {
                bestSq      = sq
                bestCumDist = idx.cumulativeDistance[i]
                nearestId   = nodeIds[i]
            }
        }
        progressMeters = bestCumDist
        voiceNavigator.onProgressUpdate(progressMeters)
        if (nearestId != -1) checkOffTrack(location, sqrt(bestSq), nearestId)
    }

    private fun checkOffTrack(location: Location, distToRouteM: Double, nearestNodeId: Int) {
        if (routeIndex == null) return

        if (distToRouteM > OFF_TRACK_THRESHOLD_M) {
            offTrackCount++
            val now = System.currentTimeMillis()
            val shouldAnnounce = offTrackCount == OFF_TRACK_TRIGGER_FIXES ||
                (offTrackCount > OFF_TRACK_TRIGGER_FIXES &&
                    now - lastOffTrackAnnounce > OFF_TRACK_REANNOUNCE_MS)

            if (shouldAnnounce) {
                lastOffTrackAnnounce = now
                val node    = graph.nodes[nearestNodeId] ?: return
                val b       = routeBearing(location.latitude, location.longitude, node.lat, node.lon)
                val dir     = compassPoint(b)
                val distStr = "${distToRouteM.toInt()} metres"
                voiceNavigator.speakNow(
                    "Off route. Head $dir for approximately $distStr to return to the path."
                )
            }
        } else {
            if (offTrackCount >= OFF_TRACK_TRIGGER_FIXES) {
                voiceNavigator.speakNow("Back on route.")
            }
            offTrackCount        = 0
            lastOffTrackAnnounce = 0L
        }
    }

    private fun updateLiveStatsInSheet() {
        val loc = lastLocation ?: return
        val idx = routeIndex

        val gridRef = OsGridRef.fromWGS84(loc.latitude, loc.longitude)
        statGridRef.text = "Grid Ref\n$gridRef"

        val altM = if (loc.hasAltitude()) "%.0f m".format(loc.altitude) else "—"
        statHeight.text = "Height\n$altM"

        val speedKmh = if (loc.hasSpeed()) "%.1f km/h".format(loc.speed * 3.6f) else "—"
        statSpeed.text = "Speed\n$speedKmh"

        if (idx != null) {
            val remaining = (idx.totalDistance - progressMeters).coerceAtLeast(0.0)
            statTtd.text = "TTD\n${formatTtd(remaining, idx.totalDistance)}"
        }
    }

    // ── Stats popup ───────────────────────────────────────────────────────────

    private fun showStatsPopup() {
        statsDialog?.dismiss()

        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (dp * 20).toInt(), (dp * 16).toInt(),
                (dp * 20).toInt(), (dp * 20).toInt()
            )
            setBackgroundColor(0xFF1E1E1E.toInt())
        }

        // Mountain name + height row
        val hill = selectedHill
        val hillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (dp * 4).toInt())
        }
        val hillName = TextView(this).apply {
            val elev = hill?.elevationM
            text = when {
                hill == null         -> "Navigation Stats"
                elev != null         -> "${hill.name}  •  ${elev} m"
                else                 -> hill.name
            }
            textSize = 17f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        hillRow.addView(hillName)
        if (hill != null) {
            val hearBtn = TextView(this).apply {
                text = "🔊"
                textSize = 26f
                setTextColor(Color.WHITE)
                // Generous padding on all sides so the touch target is easy to hit
                val p = (dp * 10).toInt()
                setPadding(p, p, p, p)
                isClickable = true
                isFocusable  = true
                // Ripple so user sees the tap was registered
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x55FFFFFF),
                    null, null
                )
                setOnClickListener {
                    val phonetic = GaelicPronouncer.phonetic(hill.name)
                    voiceNavigator.pronounce(phonetic)
                    // Always show the phonetic text — readable even if audio is muted
                    val display = if (phonetic != hill.name) "$phonetic\n(${hill.name})" else hill.name
                    Toast.makeText(this@MainActivity, display, Toast.LENGTH_LONG).show()
                }
            }
            hillRow.addView(hearBtn)
        }
        root.addView(hillRow)
        root.addView(divider())

        // Elevation profile — always created, visibility toggled in refresh()
        val profileView = ElevationProfileView(this)
        val profileViewParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dp * 130).toInt()
        ).apply { bottomMargin = (dp * 10).toInt() }
        root.addView(profileView, profileViewParams)

        val profileDivider = divider()
        root.addView(profileDivider)

        // Live stats rows
        val popTtd  = popupStatRow("Time to dest",  "—")
        val popDist = popupStatRow("Distance left",  "—")
        val popElev = popupStatRow("Elevation",      "—")
        val popGrid = popupStatRow("Grid ref",       "—")
        for (row in listOf(popTtd, popDist, popElev, popGrid)) root.addView(row)

        val scroll = ScrollView(this)
        scroll.addView(root)

        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            setContentView(scroll)
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }

        fun refresh() {
            val loc = lastLocation
            val idx = routeIndex
            // Read elevationProfile fresh each call — it may have been set since popup opened
            val profile = elevationProfile

            if (profile != null) {
                profileView.visibility = View.VISIBLE
                profileDivider.visibility = View.VISIBLE
                profileView.setProfile(profile, progressMeters)
            } else {
                profileView.visibility = View.GONE
                profileDivider.visibility = View.GONE
            }

            val gridText = if (loc != null) OsGridRef.fromWGS84(loc.latitude, loc.longitude) else "—"
            val elevText = if (loc?.hasAltitude() == true) "%.0f m".format(loc.altitude) else "—"
            popGrid.getChildAt(1)?.let { (it as? TextView)?.text = gridText }
            popElev.getChildAt(1)?.let { (it as? TextView)?.text = elevText }

            if (idx != null) {
                val remaining = (idx.totalDistance - progressMeters).coerceAtLeast(0.0)
                popTtd.getChildAt(1)?.let {
                    (it as? TextView)?.text = formatTtd(remaining, idx.totalDistance)
                }
                popDist.getChildAt(1)?.let {
                    (it as? TextView)?.text = "%.1f km".format(remaining / 1000.0)
                }
            }
        }

        refresh()

        val runnable = object : Runnable {
            override fun run() {
                if (dialog.isShowing) { refresh(); popupHandler.postDelayed(this, 2000L) }
            }
        }
        popupUpdateRunnable = runnable
        popupHandler.postDelayed(runnable, 2000L)

        dialog.setOnDismissListener { stopPopupUpdates() }
        statsDialog = dialog
        dialog.show()
    }

    private fun updateStatsPopupIfOpen() {
        // Popup refreshes via its own runnable; nothing extra needed here
    }

    private fun stopPopupUpdates() {
        popupUpdateRunnable?.let { popupHandler.removeCallbacks(it) }
        popupUpdateRunnable = null
    }

    // ── Popup helper: a label + value row ────────────────────────────────────

    private fun popupStatRow(label: String, value: String): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (dp * 6).toInt(), 0, (dp * 6).toInt())

            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(0xFFBDBDBD.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
    }

    // ── Route building ────────────────────────────────────────────────────────

    private fun handleTap(p: GeoPoint) {
        when {
            navPhase == NavPhase.HILL_NAV && tapPoints.size == 1 -> {
                // Hill was pre-selected (destination already set); first map tap = Start
                // Reorder: insert new Start before the existing destination
                val destPoint  = tapPoints.removeLast()
                val destMarker = tapMarkers.removeLast()
                map.overlays.remove(destMarker)

                tapPoints.add(p)
                tapMarkers.add(addMarker(p, "Start", 0xFF2E7D32.toInt()))

                tapPoints.add(destPoint)
                tapMarkers.add(addMarker(destPoint,
                    selectedHill?.name ?: "Destination", 0xFFC62828.toInt()))

                buildRoutes()
            }
            tapPoints.size == 0 -> {
                // First tap → Start
                tapPoints.add(p)
                tapMarkers.add(addMarker(p, "Start", 0xFF2E7D32.toInt()))
                map.invalidate()
            }
            tapPoints.size == 1 -> {
                // Second tap → Destination, start routing
                tapPoints.add(p)
                tapMarkers.add(addMarker(p, "Destination", 0xFFC62828.toInt()))
                buildRoutes()
            }
            else -> {
                // Third tap onwards → insert waypoint before the destination
                val destPoint  = tapPoints.removeLast()
                val destMarker = tapMarkers.removeLast()
                map.overlays.remove(destMarker)

                tapPoints.add(p)
                val wpLabel = "Waypoint ${tapPoints.size - 1}"
                tapMarkers.add(addMarker(p, wpLabel, 0xFF1565C0.toInt()))

                tapPoints.add(destPoint)
                tapMarkers.add(addMarker(destPoint, "Destination", 0xFFC62828.toInt()))

                // Immediate feedback if the waypoint is far from any mapped path
                warnIfWaypointOffPath(p, wpLabel)

                buildRoutes()
            }
        }
    }

    /**
     * Immediate check when a waypoint is tapped: if the tap is far from any connected
     * graph node, show a brief toast so the user knows before the route is drawn.
     * Runs on the UI thread; uses a small node pool so it stays fast.
     */
    private fun warnIfWaypointOffPath(p: GeoPoint, label: String) {
        val nearestIds = nearestConnectedNodeIds(p.latitude, p.longitude, 1, pool = 50)
        val nearestNode = nearestIds.firstOrNull()?.let { graph.nodes[it] } ?: return
        val dist = haversine(p.latitude, p.longitude, nearestNode.lat, nearestNode.lon)
        if (dist > 250.0) {
            val distStr = if (dist < 1000) "${dist.toInt()} m" else "${"%.1f".format(dist / 1000)} km"
            Toast.makeText(
                this,
                "$label is ${distStr} from the nearest mapped path — routing may not pass nearby",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Post-route check: for every intermediate waypoint, measure how far the final
     * route actually passes from it.  If any waypoint is bypassed by more than 200 m,
     * show a dialog explaining that the area may lack safe mapped paths.
     * Must be called on the UI thread after [routeCandidates] is populated.
     */
    private fun checkWaypointsOnRoute() {
        if (tapPoints.size < 3) return   // No intermediate waypoints
        val candidate = routeCandidates.getOrNull(activeIndex) ?: return
        val routeNodes = candidate.nodeIds.mapNotNull { graph.nodes[it] }
        if (routeNodes.isEmpty()) return

        // Pair<label, distanceMeters> for each bypassed waypoint
        val warnings = mutableListOf<Pair<String, Double>>()

        // tapPoints[0]=start, tapPoints[last]=destination — check everything between
        for (i in 1 until tapPoints.size - 1) {
            val wp = tapPoints[i]
            var minDist = Double.MAX_VALUE
            for (n in routeNodes) {
                val d = haversine(wp.latitude, wp.longitude, n.lat, n.lon)
                if (d < minDist) {
                    minDist = d
                    if (minDist < 200.0) break   // Close enough — skip
                }
            }
            if (minDist > 200.0) {
                warnings.add(Pair("Waypoint $i", minDist))
            }
        }

        if (warnings.isEmpty()) return

        val routeName = routeCandidates.getOrNull(activeIndex)?.name ?: "the route"
        val body = buildString {
            warnings.forEach { (label, distM) ->
                val distStr = if (distM < 1000) "${distM.toInt()} m"
                              else "${"%.1f".format(distM / 1000)} km"
                append("• $label is $distStr from the nearest point on $routeName\n")
            }
            append("\nThis area has no mapped paths — it may not be safe or accessible. ")
            append("The route follows the nearest available path instead.")
        }

        AlertDialog.Builder(this)
            .setTitle("Waypoint${if (warnings.size > 1) "s" else ""} off route")
            .setMessage(body.trim())
            .setPositiveButton("Keep waypoint") { _, _ -> /* leave as-is */ }
            .setNegativeButton("Remove it") { _, _ ->
                // Remove the first bypassed waypoint and rebuild
                val firstIdx = warnings.firstOrNull()?.first
                    ?.removePrefix("Waypoint ")?.toIntOrNull()
                    ?: return@setNegativeButton
                if (firstIdx in 1 until tapPoints.size - 1) {
                    tapPoints.removeAt(firstIdx)
                    val marker = tapMarkers.removeAt(firstIdx)
                    map.overlays.remove(marker)
                    if (tapPoints.size >= 2) buildRoutes()
                    map.invalidate()
                }
            }
            .show()
    }

    private fun buildRoutes() {
        if (!graphReady) {
            sheetTitle.text = "Still loading navigation data…"
            return
        }
        if (tapPoints.size < 2) return
        routeCandidates.clear(); activeIndex = 0; routeOverlay.clear()
        sheetTitle.text    = "Finding route…"
        sheetSubtitle.text = ""
        routingBanner.text = "🔍  Finding route…"
        routingBanner.visibility = View.VISIBLE
        val points = tapPoints.toList()
        Thread {
            try {
                val found = buildRouteForPoints(points)
                try {
                    enrichRouteElevations(found)   // file I/O — non-critical, never blocks route display
                } catch (_: Exception) { /* elevation enrichment failed — routes still shown */ }
                runOnUiThread {
                    routingBanner.visibility = View.GONE
                    routeCandidates += found
                    if (routeCandidates.isEmpty()) {
                        sheetTitle.text    = "No mapped path found"
                        sheetSubtitle.text = "No footpath data available for this area. Try a different start or destination."
                    } else {
                        // Select preferred route if the user picked one before the car park
                        val pref = preferredRouteId
                        if (pref != null) {
                            val idx = routeCandidates.indexOfFirst { it.id == pref }
                            if (idx >= 0) activeIndex = idx
                        }
                        drawActiveRoute(); buildNavigationIndex(); updateSheetForActiveRoute()
                        checkWaypointsOnRoute()
                        // Show liability acknowledgement if the auto-selected route requires it
                        val activeRoute = routeCandidates.getOrNull(activeIndex)
                        if (activeRoute != null) {
                            val grade = RouteWarningPolicy.grade(activeRoute)
                            if (RouteWarningPolicy.requiresAcknowledgement(grade)) {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle(RouteWarningPolicy.gradeLabel(grade))
                                    .setMessage(RouteWarningPolicy.liabilityText(grade))
                                    .setPositiveButton("I understand — continue", null)
                                    .setNegativeButton("Go back") { _, _ -> clearRoutes() }
                                    .show()
                            }
                        }
                    }
                    map.invalidate()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    routingBanner.visibility = View.GONE
                    sheetTitle.text = "No route found"
                    map.invalidate()
                }
            }
        }.start()
    }

    /**
     * Called on the background routing thread after a route is built.
     * Looks up SRTM elevation for every route node that still has elevation == 0.0
     * (Overpass nodes and V0-pack bundled nodes) and updates graph in-place so that
     * RouteGradientOverlay.draw() never needs to do RandomAccessFile I/O on the UI thread.
     */
    private fun enrichRouteElevations(candidates: List<RouteCandidate>) {
        val allIds = candidates.flatMap { it.nodeIds }.distinct()
        val enriched = graph.nodes.toMutableMap()
        var anyChanged = false
        for (id in allIds) {
            val n = enriched[id] ?: continue
            if (n.elevation == 0.0) {
                val elev = DemProvider.getElevation(n.lat, n.lon)
                if (elev != 0.0) { enriched[id] = n.copy(elevation = elev); anyChanged = true }
            }
        }
        if (anyChanged) {
            // Construct new objects here (background thread — safe, they are immutable).
            // Assign to fields on the UI thread to avoid a data race with overlay drawing.
            val newGraph = Graph(nodes = enriched, edges = graph.edges, landmarks = graph.landmarks)
            val newRouter = AStarRouter(newGraph)
            val newMetrics = RouteMetricsCalculator(newGraph)
            val newGen = RouteCandidateGenerator(newGraph, newRouter, newMetrics)
            runOnUiThread {
                graph             = newGraph
                router            = newRouter
                metricsCalculator = newMetrics
                candidateGenerator = newGen
            }
        }
    }

    /**
     * Two-point case: full curated candidate generation (Tourist Path, CMD Arête, Ledge Route).
     * Multi-point case: route segment-by-segment through each waypoint, stitching into one path.
     * Each segment uses curated candidate generation so the correct named route is picked up
     * as soon as a waypoint lands near a route landmark (e.g. near ledge_route_start).
     */
    private fun buildRouteForPoints(points: List<GeoPoint>): List<RouteCandidate> {
        return if (points.size == 2) {
            buildStandardCandidates(points[0], points[1])
        } else {
            buildWaypointRoute(points)
        }
    }

    /**
     * Returns the nearest node IDs that have at least one edge (i.e. are connected in the graph).
     * Searches a wider pool of [pool] candidates and takes the [k] closest connected ones.
     * Falls back to any nearest node if none have edges (shouldn't happen with valid graph data).
     */
    private fun nearestConnectedNodeIds(lat: Double, lon: Double, k: Int, pool: Int = 50): List<Int> {
        val candidates = bundledGraph.nearestNodeIds(lat, lon, pool)
        val connected  = candidates.filter { bundledGraph.edges[it]?.isNotEmpty() == true }.take(k)
        return connected.ifEmpty { candidates.take(k) }
    }

    private fun buildStandardCandidates(start: GeoPoint, end: GeoPoint): List<RouteCandidate> {
        // Use connected nodes only — always check against the bundled graph for coverage
        val startIds = nearestConnectedNodeIds(start.latitude, start.longitude, 10)
        val endIds   = nearestConnectedNodeIds(end.latitude,   end.longitude,   10)
        val sId = startIds.firstOrNull() ?: return emptyList()
        val eId = endIds.firstOrNull()   ?: return emptyList()

        // Check if the bundled graph covers this area (use bundledGraph — never mutated)
        val nearestDistToStart = bundledGraph.nodes[sId]?.let {
            haversine(start.latitude, start.longitude, it.lat, it.lon)
        } ?: Double.MAX_VALUE

        if (nearestDistToStart > 2_000.0) {
            // Bundled graph has no data here — download footpath network from Overpass
            val midLat = (start.latitude  + end.latitude)  / 2.0
            val midLon = (start.longitude + end.longitude) / 2.0
            val routeDist = haversine(start.latitude, start.longitude, end.latitude, end.longitude)
            val radius = (routeDist / 2.0 + 5_000.0).toInt().coerceIn(10_000, 25_000)

            // Reuse the cached Overpass graph when switching between nearby car parks
            // for the same mountain — avoids repeated Overpass calls that trigger rate-limits.
            val cacheCoversStart = haversine(start.latitude, start.longitude,
                cachedOverpassCenterLat, cachedOverpassCenterLon) < cachedOverpassRadiusM - 1_000
            val cacheCoversEnd   = haversine(end.latitude, end.longitude,
                cachedOverpassCenterLat, cachedOverpassCenterLon) < cachedOverpassRadiusM - 1_000

            val overpassGraph = if (cachedOverpassRoutingGraph != null && cacheCoversStart && cacheCoversEnd) {
                cachedOverpassRoutingGraph!!
            } else {
                runOnUiThread { sheetTitle.text = "Downloading map data…"; routingBanner.text = "⬇️  Downloading footpath data…" }
                try { OverpassGraphBuilder.buildForArea(midLat, midLon, radius) }
                catch (e: Exception) { null }
                ?.also {
                    cachedOverpassRoutingGraph = it
                    cachedOverpassCenterLat = midLat
                    cachedOverpassCenterLon = midLon
                    cachedOverpassRadiusM   = radius
                }
            }

            val VSTART = -1
            val VEND   = -2

            // If Overpass is unavailable or returns no data, report no route.
            if (overpassGraph == null) return emptyList()

            // Snap start/end pins to the nearest path-network nodes with edges.
            // We connect VSTART and VEND to the 5 closest nodes each so that A*
            // can find the route even when the single nearest node happens to be
            // on a disconnected component (common where OSM has small highway-type gaps).
            val connectedEntries = overpassGraph.nodes.entries
                .filter { overpassGraph.edges[it.key]?.isNotEmpty() == true }
            fun snapEdgesTo(pinLat: Double, pinLon: Double): List<Edge> =
                connectedEntries
                    .sortedBy { (_, n) -> haversine(pinLat, pinLon, n.lat, n.lon) }
                    .take(8)
                    .map { (id, n) ->
                        Edge(to = id, cost = haversine(pinLat, pinLon, n.lat, n.lon),
                             requiredMask = Capability.WALKING)
                    }

            val startSnaps = snapEdgesTo(start.latitude, start.longitude)
            val endSnaps   = snapEdgesTo(end.latitude,   end.longitude)

            // No nearby path nodes at all — no route available
            if (startSnaps.isEmpty() || endSnaps.isEmpty()) return emptyList()


            val snapNodes = (bundledGraph.nodes + overpassGraph.nodes).toMutableMap()
            snapNodes[VSTART] = Node(start.latitude, start.longitude, 0.0)
            snapNodes[VEND]   = Node(end.latitude,   end.longitude,   0.0)
            val snapEdges = (bundledGraph.edges + overpassGraph.edges).toMutableMap()
            snapEdges[VSTART] = startSnaps
            // Add reverse edges from each end-snap node → VEND
            for (snap in endSnaps) {
                snapEdges[snap.to] = (snapEdges[snap.to] ?: emptyList()) +
                    Edge(to = VEND, cost = snap.cost, requiredMask = Capability.WALKING)
            }

            val snapGraph  = Graph(nodes = snapNodes, edges = snapEdges, landmarks = graph.landmarks)
            val snapRouter = AStarRouter(snapGraph)

            var result = snapRouter.routeWithCapabilities(
                VSTART, VEND, Capability.ALL, emptySet(), emptySet()
            )

            // ── Fallback 1: bridge disconnected components ───────────────────
            // If A* found no path, the start and end sides of the network are in separate
            // connected components (OSM data gaps). Find the closest node pair across the
            // two reachable sets and add a virtual bridge edge, then retry A*.
            if (result == null || result.nodeIds.size < 2) {
                fun reachableFrom(seeds: List<Edge>): HashSet<Int> {
                    val visited = HashSet<Int>()
                    val queue   = ArrayDeque<Int>()
                    for (s in seeds) { if (visited.add(s.to)) queue.add(s.to) }
                    while (queue.isNotEmpty()) {
                        val cur = queue.removeFirst()
                        for (e in snapEdges[cur] ?: emptyList()) {
                            if (e.to >= 0 && visited.add(e.to)) queue.add(e.to)
                        }
                    }
                    return visited
                }
                val startReachable = reachableFrom(startSnaps)
                val endReachable   = reachableFrom(endSnaps)
                if (startReachable.none { it in endReachable }) {
                    // Locate closest cross-component node pair and bridge it
                    var minDist = Double.MAX_VALUE; var bFrom = -1; var bTo = -1
                    for (s in startReachable) {
                        val ns = snapNodes[s] ?: continue
                        for (e in endReachable) {
                            val ne = snapNodes[e] ?: continue
                            val d  = haversine(ns.lat, ns.lon, ne.lat, ne.lon)
                            if (d < minDist) { minDist = d; bFrom = s; bTo = e }
                        }
                    }
                    if (bFrom >= 0) {
                        snapEdges[bFrom] = (snapEdges[bFrom] ?: emptyList()) +
                            Edge(to = bTo,   cost = minDist, requiredMask = Capability.WALKING)
                        snapEdges[bTo]   = (snapEdges[bTo]   ?: emptyList()) +
                            Edge(to = bFrom, cost = minDist, requiredMask = Capability.WALKING)
                        val bridgedGraph  = Graph(nodes = snapNodes, edges = snapEdges, landmarks = graph.landmarks)
                        val bridgedRouter = AStarRouter(bridgedGraph)
                        result = bridgedRouter.routeWithCapabilities(
                            VSTART, VEND, Capability.ALL, emptySet(), emptySet()
                        )
                        // snapEdges already mutated in place above — no further action needed
                    }
                }
            }

            // If the network is so fragmented that even bridging fails, report no route.
            if (result == null || result.nodeIds.size < 2) return emptyList()

            // Promote snap graph so overlay and navigation resolve all node IDs correctly
            graph             = Graph(nodes = snapNodes, edges = snapEdges, landmarks = graph.landmarks)
            router            = AStarRouter(graph)
            metricsCalculator = RouteMetricsCalculator(graph)
            candidateGenerator = RouteCandidateGenerator(graph, router, metricsCalculator)

            val metrics = metricsCalculator.calculate(result.nodeIds)
            return listOf(RouteCandidate(
                id = "direct", familyId = "direct_family",
                name = "Best available path",
                shortDescription = "Overpass footpath route",
                nodeIds = result.nodeIds, metrics = metrics,
                difficultyProfile = DifficultyProfile(p95Slope = 0.0),
                warnings = emptyList(), isSelectable = true
            ))
        }

        // Re-anchor to the bundled graph. A previous Overpass route may have promoted
        // graph/router to a remote-area graph; using those stale objects with bundled-graph
        // node IDs would make A* fail and silently degrade to a straight-line route.
        graph              = bundledGraph
        router             = bundledRouter
        metricsCalculator  = RouteMetricsCalculator(bundledGraph)
        candidateGenerator = RouteCandidateGenerator(bundledGraph, bundledRouter, metricsCalculator)

        // Attempt curated routes when start AND end are near a supported mountain.
        val nearBenNevis =
            haversine(start.latitude, start.longitude, BEN_NEVIS_LAT, BEN_NEVIS_LON) < 15_000.0 &&
            haversine(end.latitude,   end.longitude,   BEN_NEVIS_LAT, BEN_NEVIS_LON) < 15_000.0
        val nearBenLomond =
            haversine(start.latitude, start.longitude, BEN_LOMOND_LAT, BEN_LOMOND_LON) < 15_000.0 &&
            haversine(end.latitude,   end.longitude,   BEN_LOMOND_LAT, BEN_LOMOND_LON) < 15_000.0
        if ((nearBenNevis || nearBenLomond) && sId != eId) {
            // Try the single best node pair first, then expand to 3×3 = 9 combinations.
            // Previously 10×10 = 100 combinations, each trying up to 5 named routes.
            val c0 = candidateGenerator.generateCuratedCandidates(sId, eId)
            if (c0.isNotEmpty()) return c0
            for (s in startIds.take(3)) {
                for (e in endIds.take(3)) {
                    if (s == sId && e == eId) continue   // already tried
                    val c = candidateGenerator.generateCuratedCandidates(s, e)
                    if (c.isNotEmpty()) return c
                }
            }
        }

        // Direct A* on bundled graph — tries Overpass retry if this fails
        val bundledResult = if (sId != eId) router.routeWithCapabilities(
            sId, eId, Capability.ALL, emptySet(), emptySet()
        ) else null

        if (bundledResult != null && bundledResult.nodeIds.size >= 2) {
            val metrics = metricsCalculator.calculate(bundledResult.nodeIds)
            return listOf(RouteCandidate(
                id = "direct", familyId = "direct_family",
                name = "Direct Route",
                shortDescription = "Best available path",
                nodeIds = bundledResult.nodeIds,
                metrics = metrics,
                difficultyProfile = DifficultyProfile(p95Slope = 0.0),
                warnings = emptyList(),
                isSelectable = true
            ))
        }

        // ── Overpass retry when bundled A* fails ─────────────────────────────
        // Bundled graph may have road/settlement nodes near both endpoints but not
        // the mountain footpath (e.g. Ben Lomond: B837 road is in the pack but the
        // Rowardennan–summit path is a different connected component). Try Overpass
        // before giving up so the user always gets a real walking route.
        val opMidLat = (start.latitude + end.latitude) / 2.0
        val opMidLon = (start.longitude + end.longitude) / 2.0
        val opDist   = haversine(start.latitude, start.longitude, end.latitude, end.longitude)
        val opRadius = (opDist / 2.0 + 5_000.0).toInt().coerceIn(10_000, 25_000)
        val opCacheCoversStart = haversine(start.latitude, start.longitude,
            cachedOverpassCenterLat, cachedOverpassCenterLon) < cachedOverpassRadiusM - 1_000
        val opCacheCoversEnd   = haversine(end.latitude, end.longitude,
            cachedOverpassCenterLat, cachedOverpassCenterLon) < cachedOverpassRadiusM - 1_000
        val opGraph = if (cachedOverpassRoutingGraph != null && opCacheCoversStart && opCacheCoversEnd) {
            cachedOverpassRoutingGraph!!
        } else {
            runOnUiThread { sheetTitle.text = "Downloading map data…"; routingBanner.text = "⬇️  Downloading footpath data…" }
            try { OverpassGraphBuilder.buildForArea(opMidLat, opMidLon, opRadius) }
            catch (_: Exception) { null }
            ?.also {
                cachedOverpassRoutingGraph = it
                cachedOverpassCenterLat = opMidLat
                cachedOverpassCenterLon = opMidLon
                cachedOverpassRadiusM   = opRadius
            }
        }
        if (opGraph != null) {
            val opConnected = opGraph.nodes.entries
                .filter { opGraph.edges[it.key]?.isNotEmpty() == true }
            fun opSnap(pLat: Double, pLon: Double) = opConnected
                .sortedBy { (_, n) -> haversine(pLat, pLon, n.lat, n.lon) }.take(8)
                .map { (id, n) -> Edge(to = id, cost = haversine(pLat, pLon, n.lat, n.lon),
                                       requiredMask = Capability.WALKING) }
            val OPVS = -1; val OPVE = -2
            val opSnapNodes = (bundledGraph.nodes + opGraph.nodes).toMutableMap()
            opSnapNodes[OPVS] = Node(start.latitude, start.longitude, 0.0)
            opSnapNodes[OPVE] = Node(end.latitude,   end.longitude,   0.0)
            val opSnapEdges = (bundledGraph.edges + opGraph.edges).toMutableMap()
            val opStartSnaps = opSnap(start.latitude, start.longitude)
            val opEndSnaps   = opSnap(end.latitude,   end.longitude)
            opSnapEdges[OPVS] = opStartSnaps
            opEndSnaps.forEach { s ->
                opSnapEdges[s.to] = (opSnapEdges[s.to] ?: emptyList()) +
                    Edge(to = OPVE, cost = s.cost, requiredMask = Capability.WALKING)
            }
            val opSGraph = Graph(nodes = opSnapNodes, edges = opSnapEdges,
                                 landmarks = bundledGraph.landmarks,
                                 routeSequences = bundledGraph.routeSequences)
            var opResult = AStarRouter(opSGraph).routeWithCapabilities(
                OPVS, OPVE, Capability.ALL, emptySet(), emptySet()
            )

            // ── Bridge disconnected components ────────────────────────────────
            // Road nodes (from bundledGraph or Overpass road ways) and footpath nodes
            // are often in separate connected components for Scottish hills — the car
            // park track may not be tagged as connecting to the summit path. Bridge
            // the single closest cross-component node pair and retry A*.
            if (opResult == null || opResult.nodeIds.size < 2) {
                fun opReachFrom(seeds: List<Edge>): HashSet<Int> {
                    val vis = HashSet<Int>(); val q = ArrayDeque<Int>()
                    for (s in seeds) { if (vis.add(s.to)) q.add(s.to) }
                    while (q.isNotEmpty()) {
                        val cur = q.removeFirst()
                        for (e in opSnapEdges[cur] ?: emptyList()) {
                            if (e.to >= 0 && vis.add(e.to)) q.add(e.to)
                        }
                    }
                    return vis
                }
                val opSR = opReachFrom(opStartSnaps)
                val opER = opReachFrom(opEndSnaps)
                if (opSR.none { it in opER }) {
                    var minD = Double.MAX_VALUE; var bF = -1; var bT = -1
                    for (a in opSR) {
                        val na = opSnapNodes[a] ?: continue
                        for (b in opER) {
                            val nb = opSnapNodes[b] ?: continue
                            val d = haversine(na.lat, na.lon, nb.lat, nb.lon)
                            if (d < minD) { minD = d; bF = a; bT = b }
                        }
                    }
                    if (bF >= 0) {
                        opSnapEdges[bF] = (opSnapEdges[bF] ?: emptyList()) +
                            Edge(to = bT, cost = minD, requiredMask = Capability.WALKING)
                        opSnapEdges[bT] = (opSnapEdges[bT] ?: emptyList()) +
                            Edge(to = bF, cost = minD, requiredMask = Capability.WALKING)
                        val bridgedGraph = Graph(nodes = opSnapNodes, edges = opSnapEdges,
                                                 landmarks = bundledGraph.landmarks,
                                                 routeSequences = bundledGraph.routeSequences)
                        opResult = AStarRouter(bridgedGraph).routeWithCapabilities(
                            OPVS, OPVE, Capability.ALL, emptySet(), emptySet()
                        )
                    }
                }
            }

            if (opResult != null && opResult.nodeIds.size >= 2) {
                val finalOpGraph = Graph(nodes = opSnapNodes, edges = opSnapEdges,
                                        landmarks = bundledGraph.landmarks,
                                        routeSequences = bundledGraph.routeSequences)
                graph = finalOpGraph; router = AStarRouter(finalOpGraph)
                metricsCalculator  = RouteMetricsCalculator(finalOpGraph)
                candidateGenerator = RouteCandidateGenerator(finalOpGraph, router, metricsCalculator)

                // After gaining Overpass connectivity, try curated named routes again
                // (e.g. Ben Lomond Tourist/Ptarmigan when bundled graph lacked footpath)
                if (nearBenNevis || nearBenLomond) {
                    val curatedViaOp = candidateGenerator.generateCuratedCandidates(OPVS, OPVE)
                    if (curatedViaOp.isNotEmpty()) return curatedViaOp
                }

                return listOf(RouteCandidate(
                    id = "direct", familyId = "direct_family", name = "Best available path",
                    shortDescription = "Footpath route",
                    nodeIds = opResult.nodeIds,
                    metrics = metricsCalculator.calculate(opResult.nodeIds),
                    difficultyProfile = DifficultyProfile(p95Slope = 0.0),
                    warnings = emptyList(), isSelectable = true
                ))
            }
        }

        // Both bundled A* and Overpass failed — no mapped path available.
        return emptyList()
    }

    private fun buildWaypointRoute(points: List<GeoPoint>): List<RouteCandidate> {
        // Detect if any point is outside bundled-graph coverage (nearest node > 2 km away).
        val anyRemote = points.any { p ->
            val id = bundledGraph.nearestNodeIds(p.latitude, p.longitude, 1).firstOrNull()
                ?: return@any true
            val n = bundledGraph.nodes[id] ?: return@any true
            haversine(p.latitude, p.longitude, n.lat, n.lon) > 2_000.0
        }

        if (!anyRemote) {
            // Build snap graph as the union of bundled + active graph. This ensures:
            //  a) Bundled road/path nodes are always present (handles the case where
            //     graph only contains virtual nodes -1/-2 from a previous route).
            //  b) Any Overpass footpath nodes promoted into graph are also available,
            //     allowing A* to route via downloaded mountain paths.
            val snapNodes = (bundledGraph.nodes + graph.nodes).toMutableMap()
            val snapEdges: MutableMap<Int, List<Edge>> = bundledGraph.edges.toMutableMap()
            for ((k, v) in graph.edges) {
                val existing = snapEdges[k]
                snapEdges[k] = if (existing == null) v
                               else existing + v.filter { e -> existing.none { x -> x.to == e.to } }
            }

            val virtualIds = points.mapIndexed { i, p ->
                val vid = -(10 + i)
                snapNodes[vid] = Node(p.latitude, p.longitude, 0.0)
                // Snap to nearest connected node in the combined graph (Overpass footpaths
                // + bundled roads). Skip virtual (negative) IDs from previous routes.
                val pool = snapNodes.entries
                    .filter { (id, _) -> id > 0 }
                    .sortedBy { (_, n) -> haversine(p.latitude, p.longitude, n.lat, n.lon) }
                    .take(60)
                val nearIds = pool.filter { (id, _) -> snapEdges[id]?.isNotEmpty() == true }
                    .take(5).map { it.key }
                    .ifEmpty { pool.take(5).map { it.key } }
                val snaps = nearIds.mapNotNull { nid ->
                    val n = snapNodes[nid] ?: return@mapNotNull null
                    Edge(to = nid, cost = haversine(p.latitude, p.longitude, n.lat, n.lon),
                         requiredMask = Capability.WALKING)
                }
                snapEdges[vid] = snaps
                for (s in snaps) {
                    snapEdges[s.to] = (snapEdges[s.to] ?: emptyList()) +
                        Edge(to = vid, cost = s.cost, requiredMask = Capability.WALKING)
                }
                vid
            }

            val snapGraph  = Graph(nodes = snapNodes, edges = snapEdges, landmarks = graph.landmarks)
            val snapRouter = AStarRouter(snapGraph)

            val segments = mutableListOf<List<Int>>()
            for (i in 0 until points.size - 1) {
                val fromVid = virtualIds[i]
                val toVid   = virtualIds[i + 1]

                // Try curated candidates (Ben Nevis named routes) first
                val fromIds = snapEdges[fromVid]?.map { it.to } ?: emptyList()
                val toIds   = snapEdges[toVid]?.map   { it.to } ?: emptyList()
                var seg: List<Int>? = null
                outer@ for (sId in fromIds) {
                    for (eId in toIds) {
                        val c = candidateGenerator.generateCuratedCandidates(sId, eId)
                        if (c.isNotEmpty()) { seg = c[0].nodeIds; break@outer }
                    }
                }

                // A* between virtual snap nodes
                if (seg == null) {
                    seg = snapRouter.routeWithCapabilities(
                        fromVid, toVid, Capability.ALL, emptySet(), emptySet()
                    )?.nodeIds
                }

                // Straight-line fallback — always produce a segment so the route stays visible
                if (seg == null || seg.size < 2) {
                    val dist = haversine(points[i].latitude, points[i].longitude,
                                        points[i + 1].latitude, points[i + 1].longitude)
                    snapEdges[fromVid] = (snapEdges[fromVid] ?: emptyList()) +
                        Edge(to = toVid, cost = dist, requiredMask = Capability.WALKING)
                    seg = listOf(fromVid, toVid)
                }
                segments.add(seg)
            }

            // Promote snap graph so overlay and navigation resolve all virtual node IDs.
            graph             = Graph(nodes = snapNodes, edges = snapEdges, landmarks = graph.landmarks)
            router            = AStarRouter(graph)
            metricsCalculator = RouteMetricsCalculator(graph)
            candidateGenerator = RouteCandidateGenerator(graph, router, metricsCalculator)

            val fullPath = segments.fold(listOf<Int>()) { acc, seg ->
                if (acc.isEmpty()) seg else acc.dropLast(1) + seg
            }
            if (fullPath.isEmpty()) return emptyList()
            val wps = points.size - 2
            return listOf(RouteCandidate(
                id = "waypoint_route", familyId = "waypoint_family",
                name = "Custom Route",
                shortDescription = "Via $wps waypoint${if (wps != 1) "s" else ""}",
                nodeIds = fullPath,
                metrics = metricsCalculator.calculate(fullPath),
                difficultyProfile = DifficultyProfile(p95Slope = 0.0),
                warnings = emptyList(), isSelectable = true
            ))
        }

        // Remote area — download a single Overpass graph covering the bounding box of all points.
        // This ensures all segment node IDs come from the same graph (no ID conflicts when stitching).
        runOnUiThread { sheetTitle.text = "Downloading map data…" }
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val midLat = (minLat + maxLat) / 2
        val midLon = (minLon + maxLon) / 2
        val diagDist = haversine(minLat, minLon, maxLat, maxLon)
        val radius = (diagDist / 2 + 4_000.0).toInt().coerceIn(8_000, 20_000)

        val overpassGraph = try {
            OverpassGraphBuilder.buildForArea(midLat, midLon, radius)
        } catch (e: Exception) { null }

        // If Overpass unavailable, report no route rather than drawing a straight line.
        if (overpassGraph == null) return emptyList()

        // Snap each point to the Overpass graph using unique virtual node IDs (-10, -11, …).
        val snapNodes = (bundledGraph.nodes + overpassGraph.nodes).toMutableMap()
        val snapEdges = (bundledGraph.edges + overpassGraph.edges).toMutableMap()
        val connectedEntries = overpassGraph.nodes.entries
            .filter { overpassGraph.edges[it.key]?.isNotEmpty() == true }

        fun snapEdgesTo(pinLat: Double, pinLon: Double): List<Edge> =
            connectedEntries
                .sortedBy { (_, n) -> haversine(pinLat, pinLon, n.lat, n.lon) }
                .take(5)
                .map { (id, n) ->
                    Edge(to = id, cost = haversine(pinLat, pinLon, n.lat, n.lon),
                         requiredMask = Capability.WALKING)
                }

        val virtualIds = points.mapIndexed { i, p ->
            val vid = -(10 + i)   // -10, -11, -12, … (avoids -1/-2 used by buildStandardCandidates)
            snapNodes[vid] = Node(p.latitude, p.longitude, 0.0)
            val snaps = snapEdgesTo(p.latitude, p.longitude)
            snapEdges[vid] = snaps
            for (s in snaps) {
                snapEdges[s.to] = (snapEdges[s.to] ?: emptyList()) +
                    Edge(to = vid, cost = s.cost, requiredMask = Capability.WALKING)
            }
            vid
        }

        val waypointGraph  = Graph(nodes = snapNodes, edges = snapEdges, landmarks = graph.landmarks)
        val waypointRouter = AStarRouter(waypointGraph)

        val segments = mutableListOf<List<Int>>()
        for (i in 0 until points.size - 1) {
            var seg = waypointRouter.routeWithCapabilities(
                virtualIds[i], virtualIds[i + 1], Capability.ALL, emptySet(), emptySet()
            )?.nodeIds
            if (seg == null || seg.size < 2) {
                val d = haversine(points[i].latitude, points[i].longitude,
                                  points[i + 1].latitude, points[i + 1].longitude)
                snapEdges[virtualIds[i]] = (snapEdges[virtualIds[i]] ?: emptyList()) +
                    Edge(to = virtualIds[i + 1], cost = d, requiredMask = Capability.WALKING)
                seg = listOf(virtualIds[i], virtualIds[i + 1])
            }
            segments.add(seg)
        }

        val fullPath = segments.fold(listOf<Int>()) { acc, seg ->
            if (acc.isEmpty()) seg else acc.dropLast(1) + seg
        }
        if (fullPath.isEmpty()) return emptyList()

        // Promote final snap graph so overlay and navigation resolve all node IDs correctly.
        val finalGraph    = Graph(nodes = snapNodes, edges = snapEdges, landmarks = graph.landmarks)
        graph             = finalGraph
        router            = AStarRouter(finalGraph)
        metricsCalculator = RouteMetricsCalculator(finalGraph)
        candidateGenerator = RouteCandidateGenerator(finalGraph, router, metricsCalculator)

        val wps = points.size - 2
        return listOf(RouteCandidate(
            id = "waypoint_route", familyId = "waypoint_family",
            name = "Custom Route",
            shortDescription = "Via $wps waypoint${if (wps != 1) "s" else ""}",
            nodeIds = fullPath,
            metrics = metricsCalculator.calculate(fullPath),
            difficultyProfile = DifficultyProfile(p95Slope = 0.0),
            warnings = emptyList(), isSelectable = true
        ))
    }

    private fun buildNavigationIndex() {
        val candidate = routeCandidates.getOrNull(activeIndex) ?: return
        val nodeIds = candidate.nodeIds

        val idx = RouteIndex(graph, nodeIds)
        routeIndex = idx

        // Build elevation array from V2 node elevations where available.
        // If the pack is V0 (elevation = 0 for all nodes), fall back to
        // cumulative ascent so the profile still shows a meaningful climb shape.
        val elevs = DoubleArray(nodeIds.size) { i ->
            graph.nodes[nodeIds[i]]?.elevation ?: 0.0
        }
        val elevRange = elevs.maxOrNull()!! - elevs.minOrNull()!!
        val profileElevs = if (elevRange > 20.0) {
            elevs                    // V2 pack — real altitude values
        } else {
            idx.cumulativeAscent     // V0 pack — show "metres climbed" as proxy
        }

        elevationProfile = ElevationProfileModel(
            idx.cumulativeDistance, profileElevs, idx.totalDistance
        )
        progressMeters = 0.0

        val instructions = instructionGenerator.generate(nodeIds, idx.cumulativeDistance)
        voiceNavigator.setInstructions(instructions)
    }

    private fun drawActiveRoute() {
        routeOverlay.setRoute(routeCandidates[activeIndex].nodeIds)
        routeOverlay.setInactiveRoutes(
            routeCandidates.filterIndexed { i, _ -> i != activeIndex }.map { it.nodeIds }
        )
    }

    private fun updateRouteSelector() {
        routeSelectorRow.removeAllViews()
        if (routeCandidates.size < 2) {
            routeSelectorRow.visibility = View.GONE
            return
        }
        routeSelectorRow.visibility = View.VISIBLE
        val dp = resources.displayMetrics.density
        routeCandidates.forEachIndexed { i, candidate ->
            val chip = TextView(this).apply {
                text = candidate.name
                textSize = 12f
                setPadding(
                    (dp * 12).toInt(), (dp * 6).toInt(),
                    (dp * 12).toInt(), (dp * 6).toInt()
                )
                setBackgroundColor(
                    if (i == activeIndex) 0xFF4FC3F7.toInt() else 0xFF333333.toInt()
                )
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (activeIndex != i) {
                        val grade = RouteWarningPolicy.grade(candidate)
                        if (RouteWarningPolicy.requiresAcknowledgement(grade)) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(RouteWarningPolicy.gradeLabel(grade))
                                .setMessage(RouteWarningPolicy.liabilityText(grade))
                                .setPositiveButton("I understand — continue") { _, _ ->
                                    activeIndex = i
                                    drawActiveRoute(); buildNavigationIndex()
                                    updateSheetForActiveRoute(); map.invalidate()
                                }
                                .setNegativeButton("Choose a different route", null)
                                .show()
                        } else {
                            activeIndex = i
                            drawActiveRoute()
                            buildNavigationIndex()
                            updateSheetForActiveRoute()
                            map.invalidate()
                        }
                    }
                }
            }
            routeSelectorRow.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (dp * 8).toInt() })
        }
    }

    private fun cycleRoute() {
        if (routeCandidates.size <= 1) return
        activeIndex = (activeIndex + 1) % routeCandidates.size
        drawActiveRoute()
        buildNavigationIndex()
        updateSheetForActiveRoute()
        map.invalidate()
    }

    // ── Sheet updates ─────────────────────────────────────────────────────────

    private fun updateSheetForNoRoute() {
        addSummitRow.visibility = View.GONE
        bottomSheetBehavior.peekHeight = (resources.displayMetrics.density * 140).toInt()
        sheetTitle.text    = selectedHill?.let { "To: ${it.name}" } ?: "Route: —"
        hillPronounceBtn.visibility = if (selectedHill != null) View.VISIBLE else View.GONE
        sheetSubtitle.text = when {
            navPhase == NavPhase.HILL_NAV && tapPoints.size == 1 ->
                "Tap your start point on the map"
            navPhase == NavPhase.HILL_NAV ->
                "Tap map to set Start/End • Pull up for stats"
            else ->
                "Select a hill  •  or tap map to set Start/End"
        }
        statDistance.text  = "Distance\n—"
        statAscent.text    = "Ascent\n—"
        statTime.text      = "Time\n—"
        statTtd.text       = "TTD\n—"
        statHeight.text    = "Height\n—"
        statSpeed.text     = "Speed\n—"
        statGridRef.text   = "Grid Ref\n—"
    }

    private fun updateSheetForActiveRoute() {
        val r = routeCandidates[activeIndex]
        val m = r.metrics
        val h = m.estimatedTimeMinutes / 60
        val min = m.estimatedTimeMinutes % 60
        val time = if (h > 0) "${h}h ${min}m" else "${min}m"

        val grade      = RouteWarningPolicy.grade(r)
        val gradeLabel = RouteWarningPolicy.gradeLabel(grade)

        addSummitRow.visibility = View.VISIBLE
        // Expand peek so the "Add another hill" chip and Distance/Ascent/Time stats are
        // fully visible without requiring the user to swipe up the sheet.
        bottomSheetBehavior.peekHeight = (resources.displayMetrics.density * 220).toInt()
        sheetTitle.text    = r.name
        hillPronounceBtn.visibility = if (selectedHill != null) View.VISIBLE else View.GONE
        sheetSubtitle.text = "$gradeLabel   •   ${r.shortDescription}   •   Tap ℹ for live stats"
        statDistance.text  = "Distance\n%.1f km".format(m.distanceMeters / 1000.0)
        statAscent.text    = "Ascent\n%.0f m".format(m.ascentMeters)
        statTime.text      = "Time\n$time"
        statTtd.text       = "TTD\n—"
        statHeight.text    = "Height\n—"
        statSpeed.text     = "Speed\n—"
        statGridRef.text   = "Grid Ref\n—"
        updateRouteSelector()
    }

    private fun clearRoutes() {
        tapPoints.clear()
        tapMarkers.forEach { map.overlays.remove(it) }
        tapMarkers.clear()
        routeCandidates.clear(); activeIndex = 0
        routeOverlay.clear()
        routeIndex = null; elevationProfile = null; progressMeters = 0.0
        offTrackCount = 0; lastOffTrackAnnounce = 0L
        navPhase = NavPhase.IDLE
        selectedHill = null; selectedCarPark = null; preferredRouteId = null
        cachedOverpassRoutingGraph = null
        addSummitRow.visibility = View.GONE
        routingBanner.visibility = View.GONE
        driveBanner.visibility = View.GONE
        voiceNavigator.clearInstructions()
        routeSelectorRow.removeAllViews()
        routeSelectorRow.visibility = View.GONE
        updateSheetForNoRoute()
        map.invalidate()
    }

    // ── TTD formatting ────────────────────────────────────────────────────────

    private fun formatTtd(remainingMeters: Double, totalMeters: Double): String {
        val candidate = routeCandidates.getOrNull(activeIndex) ?: return "—"
        val totalMin  = candidate.metrics.estimatedTimeMinutes
        val fraction  = if (totalMeters > 0) remainingMeters / totalMeters else 0.0
        val remMin    = (totalMin * fraction).toInt()
        val h = remMin / 60; val m = remMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    // ── Map helpers ───────────────────────────────────────────────────────────

    private fun addMarker(p: GeoPoint, title: String, color: Int = Color.RED): Marker {
        val marker = Marker(map)
        marker.position = p
        marker.title = title
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.icon = makePinDrawable(color)
        marker.setOnMarkerClickListener { m, _ -> promptRemoveMarker(m); true }
        map.overlays.add(marker)
        return marker
    }

    /** Tap a pin → offer to remove it. Rebuilds the route if enough pins remain. */
    private fun promptRemoveMarker(marker: Marker) {
        val idx = tapMarkers.indexOf(marker)
        if (idx < 0) return
        val gridRef = OsGridRef.fromWGS84(marker.position.latitude, marker.position.longitude)
        AlertDialog.Builder(this)
            .setTitle(marker.title)
            .setMessage(gridRef)
            .setPositiveButton("Remove") { _, _ ->
                tapMarkers.removeAt(idx)
                tapPoints.removeAt(idx)
                map.overlays.remove(marker)
                if (tapPoints.size >= 2) buildRoutes()
                else {
                    routeCandidates.clear(); activeIndex = 0; routeOverlay.clear()
                    updateSheetForNoRoute()
                }
                map.invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun makePinDrawable(color: Int): android.graphics.drawable.Drawable {
        val dp = resources.displayMetrics.density
        val r  = (dp * 10).toInt()
        val w  = r * 2
        val h  = (dp * 28).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv  = android.graphics.Canvas(bmp)
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeWidth = dp * 1.5f
        }
        // Circle head
        cv.drawCircle(w / 2f, r.toFloat(), r - dp, fill)
        cv.drawCircle(w / 2f, r.toFloat(), r - dp, stroke)
        // Tail triangle
        val path = android.graphics.Path().apply {
            moveTo(w / 2f - dp * 3, r.toFloat() + dp * 2)
            lineTo(w / 2f, h.toFloat())
            lineTo(w / 2f + dp * 3, r.toFloat() + dp * 2)
            close()
        }
        cv.drawPath(path, fill)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun statBox(label: String, value: String): TextView {
        return TextView(this).apply {
            textSize = 13.5f; setTextColor(Color.WHITE)
            setPadding(22, 18, 22, 18)
            setBackgroundColor(0xFF222222.toInt())
            text = "$label\n$value"
        }
    }

    private fun space(dp: Int): View {
        val v = View(this)
        val px = (resources.displayMetrics.density * dp).toInt()
        v.layoutParams = LinearLayout.LayoutParams(px, 1)
        return v
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(0xFF2A2A2A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 1).toInt()
            ).apply {
                topMargin    = (resources.displayMetrics.density * 10).toInt()
                bottomMargin = (resources.displayMetrics.density * 10).toInt()
            }
        }
    }

    /** True bearing (0-360, North = 0) from point 1 to point 2. */
    private fun routeBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val φ1   = Math.toRadians(lat1)
        val φ2   = Math.toRadians(lat2)
        val y    = sin(dLon) * cos(φ2)
        val x    = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** 8-point compass name suitable for speech. */
    private fun compassPoint(b: Double): String {
        val n = ((b % 360.0) + 360.0) % 360.0
        return when {
            n <  22.5 || n >= 337.5 -> "North"
            n <  67.5               -> "North East"
            n < 112.5               -> "East"
            n < 157.5               -> "South East"
            n < 202.5               -> "South"
            n < 247.5               -> "South West"
            n < 292.5               -> "West"
            else                    -> "North West"
        }
    }

    /** Installs [g] as the active routing graph. Safe to call from the UI thread. */
    private fun initRouters(g: Graph) {
        graph              = g
        bundledGraph       = g
        bundledRouter      = AStarRouter(g)
        router             = AStarRouter(g)
        metricsCalculator  = RouteMetricsCalculator(g)
        candidateGenerator = RouteCandidateGenerator(g, router, metricsCalculator)
        instructionGenerator = InstructionGenerator(g)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /** Returns a compass direction (N / NE / E … NW) from [fromLat,fromLon] toward [toLat,toLon]. */
    private fun carParkBearing(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String {
        val dLon = Math.toRadians(toLon - fromLon)
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[((deg + 22.5) / 45.0).toInt() % 8]
    }

    // ── Hill selection ────────────────────────────────────────────────────────

    /**
     * Builds a picker label for the "add another summit" list.
     * Format: "Name, Area  1234m   (X.X km · ~Xh Xm)"
     * Time uses Naismith's Rule: distance/speed + 1 hr per 600 m ascent.
     * [fromLoc] is the user's current GPS position; if null, distance/time are omitted.
     */
    private fun summitEtaLabel(
        result: HillSearchService.HillResult,
        fromLoc: android.location.Location?
    ): String {
        val sb = StringBuilder(result.name)
        if (result.area.isNotEmpty()) sb.append(", ${result.area}")
        result.elevationM?.let { sb.append("  ${it}m") }
        if (fromLoc != null) {
            val distM  = haversine(fromLoc.latitude, fromLoc.longitude, result.lat, result.lon)
            val distKm = distM / 1000.0
            // Use GPS-reported speed if meaningful (≥ 1 km/h), otherwise assume 4 km/h.
            val speedKmh = if (fromLoc.hasSpeed() && fromLoc.speed * 3.6 >= 1.0)
                fromLoc.speed * 3.6 else 4.0
            val currAlt  = if (fromLoc.hasAltitude()) fromLoc.altitude else 0.0
            val ascentM  = maxOf(0.0, (result.elevationM?.toDouble() ?: currAlt) - currAlt)
            val totalMins = (distKm / speedKmh * 60.0 + ascentM / 600.0 * 60.0).toInt()
            val h = totalMins / 60; val m = totalMins % 60
            val etaStr = if (h > 0) "~${h}h ${m}m" else "~${m}m"
            sb.append("   (${"%.1f".format(distKm)} km · $etaStr)")
        }
        return sb.toString()
    }

    private fun showHillSearch(initialQuery: String = "") {
        HillSearchService.clearAreaCache()
        val dp = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((dp * 16).toInt(), (dp * 8).toInt(), (dp * 16).toInt(), 0)
        }

        // Search row: text input + microphone button
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF2A2A2A.toInt())
        }
        val searchInput = EditText(this).apply {
            hint = if (addingSummit) "Next summit name…"
                   else "Mountain name, or 'Torridon hills'…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF666666.toInt())
            background = null
            setPadding((dp * 10).toInt(), (dp * 10).toInt(), (dp * 6).toInt(), (dp * 10).toInt())
        }
        // Note: setText is intentionally deferred to after dialog.show() so the
        // TextWatcher is already attached and fires the search immediately.
        val micBtn = TextView(this).apply {
            text = "🎙"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setPadding((dp * 10).toInt(), 0, (dp * 10).toInt(), 0)
            setOnClickListener {
                pendingVoiceTarget = searchInput
                @Suppress("DEPRECATION")
                startActivityForResult(
                    android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a mountain name, or 'hills near a town'…")
                    },
                    SPEECH_REQUEST_CODE
                )
            }
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        searchRow.addView(micBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
        ))
        container.addView(searchRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val statusText = TextView(this).apply {
            text = "Type a mountain name, or 'hills near [place]'…"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, (dp * 8).toInt(), 0, (dp * 4).toInt())
        }
        container.addView(statusText)

        // "Did you mean?" container — vertical; rows of 2 chips added dynamically
        val suggestContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, (dp * 4).toInt(), 0, (dp * 4).toInt())
        }
        container.addView(suggestContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val listView = ListView(this).apply {
            divider = null
            setBackgroundColor(0xFF1E1E1E.toInt())
        }
        container.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (dp * 280).toInt()
        ))

        // Radius selector — shown only when an area search ("hills near X") is active
        val radiusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, (dp * 8).toInt(), 0, (dp * 4).toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        container.addView(radiusRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val results = mutableListOf<HillSearchService.HillResult>()
        val labels  = mutableListOf<String>()
        val adapter = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, labels
        ) {
            override fun getView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(pos, cv, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.setBackgroundColor(if (pos % 2 == 0) 0xFF252525.toInt() else 0xFF1E1E1E.toInt())
                v.setPadding((dp * 12).toInt(), (dp * 14).toInt(), (dp * 12).toInt(), (dp * 14).toInt())
                return v
            }
        }
        listView.adapter = adapter

        val dialogTitle = if (addingSummit) "Add another summit" else "Find a mountain or area"
        val dialog = AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener {
            pendingVoiceTarget = null
            addingSummit = false   // reset flag if user cancels
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            val picked = results[position]
            if (addingSummit) {
                addingSummit = false
                addSummitToRoute(picked)
            } else {
                showRoutePickerOrProceed(picked)
            }
        }

        // Populates "Did you mean?" as rows of 2 chips so they wrap neatly.
        fun showSuggestions(query: String) {
            val suggestions = HillSuggestionService.suggest(query)
            suggestContainer.removeAllViews()
            if (suggestions.isEmpty()) { suggestContainer.visibility = View.GONE; return }

            suggestContainer.addView(TextView(this).apply {
                text = "Did you mean:"
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 0, 0, (dp * 4).toInt())
            })

            // Add chips 2 per row so they always fit horizontally
            suggestions.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, (dp * 4).toInt())
                }
                pair.forEach { sug ->
                    val chip = TextView(this).apply {
                        text = sug
                        textSize = 12f
                        setTextColor(0xFF4FC3F7.toInt())
                        setBackgroundColor(0xFF1A2A3A.toInt())
                        setPadding((dp * 8).toInt(), (dp * 6).toInt(), (dp * 8).toInt(), (dp * 6).toInt())
                        setOnClickListener {
                            searchInput.setText(sug)
                            searchInput.setSelection(sug.length)
                        }
                    }
                    row.addView(chip, LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginEnd = (dp * 6).toInt() })
                }
                // If odd number, last row has one chip — add an empty spacer to keep layout
                if (pair.size == 1) {
                    row.addView(View(this), LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                suggestContainer.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            suggestContainer.visibility = View.VISIBLE
        }

        // Area search state — set when query matches "hills near X" pattern
        var currentAreaName: String? = null
        var currentRadiusMiles = 5.0

        // Declared as var before updateRadiusChips so the chip lambdas can capture it by
        // reference. The real body is assigned below, after updateRadiusChips is defined.
        var applyAreaResults: (HillSearchService.NearbyHillsResult?, String, Double) -> Unit =
            { _, _, _ -> }

        // Pattern: "hills near aviemore", "munros in fort william", "peaks around glen coe",
        // also reversed: "cairngorms hills", "aviemore walks", "ben nevis area".
        val areaPattern = Regex(
            """^(?:(?:hills?|munros?|corbetts?|grahams?|peaks?|walks?|mountains?)\s+(?:near|in|around|close to|by)\s+(.+)|(.+)\s+(?:hills?|munros?|corbetts?|grahams?|peaks?|walks?|mountains?|area|walks?))$""",
            RegexOption.IGNORE_CASE
        )

        fun updateRadiusChips(selectedMiles: Double) {
            radiusRow.removeAllViews()
            radiusRow.addView(TextView(this).apply {
                text = "Radius:  "; textSize = 12f
                setTextColor(0xFF888888.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
            listOf(5.0, 10.0, 15.0, 20.0).forEach { miles ->
                val active = Math.abs(miles - selectedMiles) < 0.5
                radiusRow.addView(TextView(this).apply {
                    text = "${miles.toInt()} mi"
                    textSize = 11f
                    setTextColor(if (active) 0xFF1A1A2E.toInt() else 0xFF4FC3F7.toInt())
                    setBackgroundColor(if (active) 0xFF4FC3F7.toInt() else 0xFF1A2A3A.toInt())
                    setPadding((dp * 8).toInt(), (dp * 4).toInt(), (dp * 8).toInt(), (dp * 4).toInt())
                    setOnClickListener {
                        if (!active) {
                            currentRadiusMiles = miles
                            updateRadiusChips(miles)
                            val area = currentAreaName ?: return@setOnClickListener
                            statusText.text = "Searching hills within ${miles.toInt()} miles of $area…"
                            Thread {
                                val found = try {
                                    HillSearchService.searchHillsNearArea(area, miles * 1.60934)
                                } catch (_: Exception) { null }
                                runOnUiThread { applyAreaResults(found, area, miles) }
                            }.start()
                        }
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (dp * 4).toInt() })
            }
            // Custom distance entry
            radiusRow.addView(TextView(this).apply {
                text = "Custom…"
                textSize = 11f
                setTextColor(0xFF4FC3F7.toInt())
                setBackgroundColor(0xFF1A2A3A.toInt())
                setPadding((dp * 8).toInt(), (dp * 4).toInt(), (dp * 8).toInt(), (dp * 4).toInt())
                setOnClickListener {
                    val distInput = EditText(this@MainActivity).apply {
                        hint = "Miles"
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        setTextColor(Color.WHITE)
                        setPadding((dp * 8).toInt(), (dp * 8).toInt(), (dp * 8).toInt(), (dp * 8).toInt())
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Search radius")
                        .setMessage("Enter distance in miles:")
                        .setView(distInput)
                        .setPositiveButton("Search") { _, _ ->
                            val miles = distInput.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                            if (miles > 0) {
                                currentRadiusMiles = miles
                                updateRadiusChips(miles)
                                val area = currentAreaName ?: return@setPositiveButton
                                statusText.text = "Searching hills within %.0f miles of $area…".format(miles)
                                Thread {
                                    val found = try {
                                        HillSearchService.searchHillsNearArea(area, miles * 1.60934)
                                    } catch (_: Exception) { null }
                                    runOnUiThread { applyAreaResults(found, area, miles) }
                                }.start()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
        }

        // Real implementation — assigned after updateRadiusChips so it can safely call it.
        applyAreaResults = { found, areaName, miles ->
            if (found == null) {
                statusText.text = "Could not find \"$areaName\" — check spelling"
                radiusRow.visibility = View.GONE
            } else {
                val loc = if (addingSummit) lastLocation else null
                // In "add summit" mode: sort by distance from user's current position.
                val displayHills = if (loc != null)
                    found.hills.sortedBy { haversine(loc.latitude, loc.longitude, it.lat, it.lon) }
                else found.hills
                results.clear(); results.addAll(displayHills)
                labels.clear(); labels.addAll(displayHills.map { h ->
                    if (loc != null) {
                        summitEtaLabel(h, loc)
                    } else {
                        val distKm = haversine(h.lat, h.lon, found.areaLat, found.areaLon) / 1000.0
                        val eleStr = h.elevationM?.let { "  ${it}m" } ?: ""
                        "${h.name}$eleStr   (${"%.1f km".format(distKm)})"
                    }
                })
                adapter.notifyDataSetChanged()
                val count = found.hills.size
                val sortNote = if (loc != null) "  · nearest first" else ""
                statusText.text = "$count hill${if (count != 1) "s" else ""} within ${"%.0f".format(miles)} miles of ${found.areaDisplayName}$sortNote" +
                    if (count == 0) " — try a wider radius" else ""
                suggestContainer.visibility = View.GONE
                radiusRow.visibility = View.VISIBLE
                updateRadiusChips(miles)
            }
        }

        val searchHandler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: return
                pending?.let { searchHandler.removeCallbacks(it) }
                suggestContainer.visibility = View.GONE
                if (query.length < 2) {
                    statusText.text = "Type a mountain name, or 'hills near [place]'…"
                    results.clear(); labels.clear()
                    adapter.notifyDataSetChanged()
                    radiusRow.visibility = View.GONE
                    currentAreaName = null
                    return
                }

                val areaMatch = areaPattern.find(query)
                if (areaMatch != null) {
                    // ── Area search path ─────────────────────────────────────────
                    // Group 1: "hills near X" pattern; group 2: "X hills" pattern
                    val area = areaMatch.groupValues[1].trim().ifEmpty {
                        areaMatch.groupValues[2].trim()
                    }
                    currentAreaName = area
                    statusText.text = "Searching hills near $area…"
                    val r = Runnable {
                        Thread {
                            val found = try {
                                HillSearchService.searchHillsNearArea(area, currentRadiusMiles * 1.60934)
                            } catch (_: Exception) { null }
                            runOnUiThread { applyAreaResults(found, area, currentRadiusMiles) }
                        }.start()
                    }
                    pending = r
                    searchHandler.postDelayed(r, 600L)
                } else {
                    // ── Named hill search path ────────────────────────────────────
                    currentAreaName = null
                    radiusRow.visibility = View.GONE
                    statusText.text = "Searching…"
                    val r = Runnable {
                        Thread {
                            val found = try { HillSearchService.search(query) }
                                        catch (_: Exception) { emptyList() }
                            runOnUiThread {
                                val loc = if (addingSummit) lastLocation else null
                                // In "add summit" mode: sort nearest first and show Naismith ETAs.
                                val displayFound = if (loc != null)
                                    found.sortedBy { haversine(loc.latitude, loc.longitude, it.lat, it.lon) }
                                else found
                                results.clear(); results.addAll(displayFound)
                                labels.clear(); labels.addAll(displayFound.map { r ->
                                    if (loc != null) summitEtaLabel(r, loc) else r.displayLabel
                                })
                                adapter.notifyDataSetChanged()
                                if (found.isEmpty()) {
                                    // No named hills — automatically try area search
                                    currentAreaName = query
                                    statusText.text = "Searching hills near \"$query\"…"
                                    Thread {
                                        val areaFound = try {
                                            HillSearchService.searchHillsNearArea(query, currentRadiusMiles * 1.60934)
                                        } catch (_: Exception) { null }
                                        runOnUiThread {
                                            if (areaFound != null) {
                                                // Area geocoded — show results (may be 0 with "try wider" hint)
                                                applyAreaResults(areaFound, query, currentRadiusMiles)
                                            } else {
                                                currentAreaName = null
                                                statusText.text = "No mountains found for \"$query\""
                                                showSuggestions(query)
                                                suggestContainer.visibility = View.VISIBLE
                                            }
                                        }
                                    }.start()
                                } else {
                                    val sortNote = if (loc != null) "  · nearest first" else ""
                                    statusText.text =
                                        "${found.size} mountain${if (found.size != 1) "s" else ""} found$sortNote"
                                    suggestContainer.visibility = View.GONE
                                }
                            }
                        }.start()
                    }
                    pending = r
                    searchHandler.postDelayed(r, 500L)
                }
            }
        })

        dialog.show()
        searchInput.requestFocus()
        if (initialQuery.isNotEmpty()) {
            searchInput.post {
                searchInput.setText(initialQuery)
                searchInput.setSelection(initialQuery.length)
            }
        }
    }

    /**
     * Appends [result]'s summit as the next waypoint on the existing route.
     * The current last tapPoint (previous summit) becomes an intermediate waypoint;
     * the new summit becomes the new destination. No car park selection needed —
     * the user is already walking from the original start point.
     */
    private fun addSummitToRoute(result: HillSearchService.HillResult) {
        val summitPt = GeoPoint(result.lat, result.lon)
        tapPoints.add(summitPt)
        tapMarkers.add(addMarker(summitPt, result.name, 0xFFC62828.toInt()))
        selectedHill = result   // update pronounce button + sheet title destination
        buildRoutes()
    }

    /**
     * Show the route-picker dialog if this hill has known named routes, then proceed to
     * car park selection. If no catalogue entry exists, skip straight to car parks.
     */
    private fun showRoutePickerOrProceed(result: HillSearchService.HillResult) {
        val routes = ROUTE_CATALOGUE[result.name.trim().lowercase()]
        if (routes.isNullOrEmpty()) {
            preferredRouteId = null
            fetchCarParksAndSelect(result)
            return
        }

        val dp = resources.displayMetrics.density

        // Build a scrollable list of tappable route cards
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding((dp * 4).toInt(), (dp * 4).toInt(), (dp * 4).toInt(), (dp * 4).toInt())
        }

        var pickerDialog: AlertDialog? = null

        routes.forEach { route ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF252525.toInt())
                setPadding((dp * 16).toInt(), (dp * 14).toInt(), (dp * 16).toInt(), (dp * 14).toInt())
            }
            card.addView(TextView(this).apply {
                text = route.name
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = route.description
                textSize = 13f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(0, (dp * 2).toInt(), 0, 0)
            })
            card.setOnClickListener {
                preferredRouteId = route.id
                pickerDialog?.dismiss()
                fetchCarParksAndSelect(result)
            }
            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (dp * 4).toInt() })
        }

        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        pickerDialog = AlertDialog.Builder(this)
            .setTitle(result.name)
            .setView(scroll)
            .setNegativeButton("Any route") { _, _ ->
                preferredRouteId = null
                fetchCarParksAndSelect(result)
            }
            .create()
        pickerDialog!!.show()
    }

    /** After a route (or "any") is chosen, look up nearby car parks then proceed. */
    private fun fetchCarParksAndSelect(result: HillSearchService.HillResult) {
        cachedOverpassRoutingGraph = null   // new mountain → discard cached routing graph
        sheetTitle.text = "Finding car parks…"
        routingBanner.text = "🅿️  Finding car parks…"
        routingBanner.visibility = View.VISIBLE
        Thread {
            // Queries now run in parallel inside findNearbyCarParks; a single call is enough.
            val rawParks = try { HillSearchService.findNearbyCarParks(result.lat, result.lon) }
                           catch (_: Exception) { emptyList() }

            // Inject preferred car park at the top (looked up by nearest hill ID)
            val nearestHill = HillRepository.hills.minByOrNull {
                haversine(result.lat, result.lon, it.summitLat, it.summitLon)
            }
            val preferred = nearestHill?.let { PreferredParking.forHillId(it.id) }

            val parks: List<CarPark> = if (preferred != null) {
                // Remove any Overpass entry that is within 200 m of the preferred park (same place)
                val deduped = rawParks.filter { cp ->
                    haversine(cp.lat, cp.lon, preferred.lat, preferred.lon) > 200.0
                }
                listOf(preferred) + deduped
            } else {
                rawParks
            }

            runOnUiThread {
                routingBanner.visibility = View.GONE
                when {
                    parks.isEmpty() -> {
                        // No Overpass data — use the hill/route coordinates as a navigation target
                        onHillSelected(result, CarPark("${result.name} area", result.lat, result.lon))
                    }
                    parks.size == 1 -> onHillSelected(result, parks[0])
                    else -> {
                        val top    = parks.take(9)
                        val labels = top.map { cp ->
                            val dist = haversine(result.lat, result.lon, cp.lat, cp.lon)
                            val distStr = if (dist < 1000) "${dist.toInt()} m"
                                          else "${"%.1f".format(dist / 1000)} km"
                            val bearing = carParkBearing(result.lat, result.lon, cp.lat, cp.lon)
                            val areaStr = if (cp.area.isNotEmpty()) ", ${cp.area}" else ""
                            val prefix  = if (cp.isPreferred) "⭐ Recommended\n" else ""
                            "$prefix${cp.name}$areaStr · $bearing $distStr"
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Select start car park")
                            .setItems(labels) { _, i -> onHillSelected(result, top[i]) }
                            .setNegativeButton("Nearest") { _, _ -> onHillSelected(result, top[0]) }
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun onHillSelected(result: HillSearchService.HillResult, carPark: CarPark?) {
        selectedHill    = result
        selectedCarPark = carPark

        // Centre map on the summit immediately (no animation to avoid conflicts with arriveAtCarPark)
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(result.lat, result.lon))

        if (carPark == null) {
            navPhase = NavPhase.HILL_NAV
            tapPoints.add(GeoPoint(result.lat, result.lon))
            tapMarkers.add(addMarker(GeoPoint(result.lat, result.lon), result.name, 0xFFC62828.toInt()))
            updateSheetForNoRoute()
            Toast.makeText(this, "Tap your start point on the map.", Toast.LENGTH_LONG).show()
            map.invalidate()
            return
        }

        val loc = lastLocation
        val distToCarPark = if (loc != null)
            haversine(loc.latitude, loc.longitude, carPark.lat, carPark.lon)
        else
            Double.MAX_VALUE

        if (distToCarPark > CARPARK_ARRIVAL_M) {
            // User is not at the car park yet — offer road navigation
            val message = if (carPark.navLat != null) {
                val ferryTo   = carPark.name.substringBefore(" (ferry").trim()
                val ferryFrom = carPark.name.substringAfterLast("from ").removeSuffix(")").trim()
                "Drive to $ferryFrom and take the ferry to $ferryTo.\n\nNavigate to $ferryFrom now?"
            } else {
                "Head to ${carPark.name} to start your walk.\n\nOpen navigation now?"
            }
            AlertDialog.Builder(this)
                .setTitle("Navigate to ${result.name}")
                .setMessage(message)
                .setPositiveButton("Navigate") { _, _ ->
                    launchRoadNavigation(carPark)
                    enterDrivingPhase(result, carPark)
                }
                .setNegativeButton("I'm Already There") { _, _ ->
                    arriveAtCarPark()
                }
                .show()
        } else {
            // Already at (or near) the car park
            arriveAtCarPark()
        }
    }

    /** Launch turn-by-turn navigation to the car park (or ferry terminal if ferry access). */
    private fun launchRoadNavigation(carPark: CarPark) {
        // For ferry destinations, navigate to the mainland terminal, not the island start
        val navLat  = carPark.navLat ?: carPark.lat
        val navLon  = carPark.navLon ?: carPark.lon
        val navName = if (carPark.navLat != null)
            carPark.name.substringAfterLast("from ").removeSuffix(")").trim().ifEmpty { carPark.name }
        else carPark.name

        // geo: URI — handled by Google Maps, OsmAnd, Waze and most mapping apps
        try {
            val uri = Uri.parse("geo:$navLat,$navLon?q=$navLat,$navLon(${Uri.encode(navName)})")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            return
        } catch (e: ActivityNotFoundException) { /* fall through */ }

        // Last resort: open Google Maps directions URL in browser
        try {
            val url = "https://maps.google.com/maps?daddr=$navLat,$navLon&directionsmode=driving"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this,
                "No navigation app found. Head to: $navName",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun enterDrivingPhase(result: HillSearchService.HillResult, carPark: CarPark) {
        navPhase = NavPhase.DRIVING_TO_CARPARK
        val label = driveBanner.findViewById<TextView>(android.R.id.text1)
        val destination = if (carPark.navLat != null) {
            // Ferry access: show the mainland departure terminal name
            carPark.name.substringAfterLast("from ").removeSuffix(")").trim()
                .ifEmpty { carPark.name }
        } else carPark.name
        label?.text = "Driving to $destination  •  ${result.name}"
        driveBanner.visibility = View.VISIBLE
    }

    private fun checkCarParkArrival(loc: Location) {
        val cp = selectedCarPark ?: return
        // Ferry destinations require the user to manually tap "I'm Here" after crossing
        if (cp.navLat != null) return
        val dist = haversine(loc.latitude, loc.longitude, cp.lat, cp.lon)
        if (dist <= CARPARK_ARRIVAL_M) arriveAtCarPark()
    }

    /** Called when the user arrives at the car park (auto-detected or manual). */
    private fun arriveAtCarPark() {
        // Save references before clearRoutes() nullifies them
        val hill = selectedHill ?: return
        val cp   = selectedCarPark

        // Clear any previous route/markers without resetting hill selection
        tapPoints.clear()
        tapMarkers.forEach { map.overlays.remove(it) }
        tapMarkers.clear()
        routeCandidates.clear(); activeIndex = 0
        routeOverlay.clear()
        routeIndex = null; elevationProfile = null; progressMeters = 0.0
        offTrackCount = 0; lastOffTrackAnnounce = 0L
        voiceNavigator.clearInstructions()
        routeSelectorRow.removeAllViews()
        routeSelectorRow.visibility = View.GONE

        // Restore hill state and switch to hill navigation phase
        selectedHill    = hill
        selectedCarPark = cp
        navPhase        = NavPhase.HILL_NAV
        driveBanner.visibility = View.GONE

        val summitPt = GeoPoint(hill.lat, hill.lon)

        // Position map immediately (no animation) so markers are visible right away
        val midLat = if (cp != null) (cp.lat + hill.lat) / 2.0 else hill.lat
        val midLon = if (cp != null) (cp.lon + hill.lon) / 2.0 else hill.lon
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(midLat, midLon))

        // A synthetic car park is created at (hill.lat, hill.lon) when Overpass finds nothing.
        // Placing start at the same coords as the summit would overlap the pins and produce a
        // zero-length route, so treat that case the same as having no car park at all.
        val cpIsAtSummit = cp != null &&
            Math.abs(cp.lat - hill.lat) < 0.0001 &&
            Math.abs(cp.lon - hill.lon) < 0.0001

        // Determine the start point: prefer car park, then GPS, then ask user to tap
        val gpsPt = lastLocation?.let { GeoPoint(it.latitude, it.longitude) }
        val startPt = when {
            cp != null && !cpIsAtSummit -> GeoPoint(cp.lat, cp.lon)   // real car park
            gpsPt != null               -> gpsPt                        // GPS location
            else                        -> null                         // nothing — ask user
        }

        if (startPt != null) {
            // Add start pin first, then summit — tapPoints[0]=start, tapPoints[1]=summit
            val startLabel = when {
                cp != null && !cpIsAtSummit -> cp.name
                gpsPt != null               -> "My Location"
                else                        -> "Start"
            }
            tapPoints.add(startPt)
            tapMarkers.add(addMarker(startPt, startLabel, 0xFF2E7D32.toInt()))
            tapPoints.add(summitPt)
            tapMarkers.add(addMarker(summitPt, hill.name, 0xFFC62828.toInt()))
            // Centre map between both pins
            val centerLat = (startPt.latitude  + hill.lat) / 2.0
            val centerLon = (startPt.longitude + hill.lon) / 2.0
            map.controller.setCenter(GeoPoint(centerLat, centerLon))
            map.invalidate()
            buildRoutes()
        } else {
            // No car park and no GPS — place summit pin only and ask user to tap their start
            tapPoints.add(summitPt)
            tapMarkers.add(addMarker(summitPt, hill.name, 0xFFC62828.toInt()))
            map.invalidate()
            updateSheetForNoRoute()
            Toast.makeText(this, "Tap your start point on the map.", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleVoiceMute() {
        voiceNavigator.muted = !voiceNavigator.muted
        voiceFab.setIconResource(
            if (voiceNavigator.muted)
                android.R.drawable.ic_lock_silent_mode        // muted
            else
                android.R.drawable.ic_lock_silent_mode_off    // active
        )
        if (!voiceNavigator.muted) {
            voiceNavigator.speakNow("Voice navigation on.")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull() ?: return
            pendingVoiceTarget?.apply {
                setText(spoken)
                setSelection(spoken.length)
            }
        }
    }

    // ── Locate / return to user position ─────────────────────────────────────

    private fun returnToMyLocation() {
        val loc = lastLocation ?: return
        val radiusM  = 8047.2
        val latDelta = radiusM / 111320.0
        val lonDelta = radiusM / (111320.0 * Math.cos(Math.toRadians(loc.latitude)))
        val bbox = BoundingBox(
            loc.latitude + latDelta, loc.longitude + lonDelta,
            loc.latitude - latDelta, loc.longitude - lonDelta
        )
        programmaticScroll = true
        map.post {
            map.zoomToBoundingBox(bbox, true)
            map.postDelayed({ programmaticScroll = false }, 1_500)
        }
        locateFab.visibility = View.GONE
    }

    // ── Tile pre-caching ──────────────────────────────────────────────────────

    /**
     * Downloads and caches map tiles for the 5-mile area around [lat]/[lon]
     * at zoom levels 8–15, only when a network connection is available.
     * Runs entirely on a background thread; safe to call from the UI thread.
     */
    private fun preCacheTiles(lat: Double, lon: Double) {
        if (!isNetworkAvailable()) return
        Thread {
            val radiusM   = 8047.2   // 5 miles
            val latDelta  = radiusM / 111320.0
            val lonDelta  = radiusM / (111320.0 * Math.cos(Math.toRadians(lat)))
            val tileProvider = map.tileProvider
            for (zoom in 8..15) {
                val minX = lonToTileX(lon - lonDelta, zoom)
                val maxX = lonToTileX(lon + lonDelta, zoom)
                val minY = latToTileY(lat + latDelta, zoom)
                val maxY = latToTileY(lat - latDelta, zoom)
                for (x in minX..maxX) {
                    for (y in minY..maxY) {
                        tileProvider.getMapTile(MapTileIndex.getTileIndex(zoom, x, y))
                    }
                }
            }
        }.start()
    }

    private fun lonToTileX(lon: Double, zoom: Int): Int =
        ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val rad = Math.toRadians(lat)
        return ((1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * (1 shl zoom)).toInt()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Nearby hills discovery ────────────────────────────────────────────────

    /**
     * Shows a bottom sheet listing the 15 closest hills to the user's current
     * GPS location. If location is unknown, falls back to the map centre.
     */
    private fun showNearbyHills() {
        val loc = lastLocation
        val refLat = loc?.latitude  ?: map.mapCenter.latitude
        val refLon = loc?.longitude ?: map.mapCenter.longitude

        // Pass all hills with their distances — the sheet filters by the user's chosen radius
        val all = HillRepository.hills.map { hill ->
            NearbyHillsSheet.Entry(
                hill = hill,
                distanceM = haversine(refLat, refLon, hill.summitLat, hill.summitLon)
            )
        }

        val sheet = NearbyHillsSheet().apply {
            allEntries = all
            this.refLat = refLat
            this.refLon = refLon
            onHillPicked = { hill -> routeToHillFromRepo(hill) }
            onAttractionPicked = { attraction -> routeToAttraction(attraction) }
        }
        sheet.show(supportFragmentManager, "nearby_hills")
    }

    /**
     * Routes to [hill] using the user's current GPS position as the start.
     * If GPS is unavailable the user is prompted to tap their start on the map.
     */
    private fun routeToHillFromRepo(hill: Hill) {
        clearRoutes()
        val result = HillSearchService.HillResult(
            name       = hill.name,
            area       = hill.area,
            lat        = hill.summitLat,
            lon        = hill.summitLon,
            elevationM = hill.elevationM
        )
        val loc = lastLocation
        if (loc != null) {
            // Use GPS position as start, summit as destination
            selectedHill    = result
            selectedCarPark = null
            navPhase        = NavPhase.HILL_NAV
            val startPt   = GeoPoint(loc.latitude, loc.longitude)
            val summitPt  = GeoPoint(hill.summitLat, hill.summitLon)
            tapPoints.addAll(listOf(startPt, summitPt))
            tapMarkers.add(addMarker(startPt,  "Start",    0xFF2E7D32.toInt()))
            tapMarkers.add(addMarker(summitPt, hill.name, 0xFFC62828.toInt()))
            map.controller.setZoom(13.0)
            map.controller.setCenter(summitPt)
            buildRoutes()
        } else {
            // No GPS — fall back to the search-selected flow (user taps start)
            onHillSelected(result, null)
        }
    }

    /**
     * Routes to [attraction] using the user's current GPS position as the start.
     * Uses the same routing engine as hills — finds the nearest mapped footpath.
     */
    private fun routeToAttraction(attraction: Attraction) {
        clearRoutes()
        val loc = lastLocation
        val startPt = if (loc != null) GeoPoint(loc.latitude, loc.longitude)
                      else GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
        val destPt  = GeoPoint(attraction.lat, attraction.lon)

        navPhase = NavPhase.HILL_NAV
        tapPoints.addAll(listOf(startPt, destPt))
        tapMarkers.add(addMarker(startPt, "Start",         0xFF2E7D32.toInt()))
        tapMarkers.add(addMarker(destPt,  attraction.name, 0xFF1565C0.toInt()))

        map.controller.setZoom(13.0)
        map.controller.setCenter(destPt)
        buildRoutes()
    }

    /**
     * Shows a summit info card for [hill]. Called when the user taps a summit triangle.
     * [distanceM] is the distance from the user's location (negative = unknown).
     */
    private fun showSummitInfoSheet(hill: Hill, distanceM: Double) {
        val sheet = SummitInfoSheet().apply {
            this.hill       = hill
            this.distanceM  = distanceM
            onRouteFromHere = { h -> routeToHillFromRepo(h) }
        }
        sheet.show(supportFragmentManager, "summit_info")
    }

    /**
     * Returns the geographic distance threshold (metres) within which a tap on
     * the map is considered to be targeting a summit triangle, based on zoom level.
     */
    private fun summitTapThresholdMeters(): Double = when {
        map.zoomLevelDouble >= 14.0 -> 200.0
        map.zoomLevelDouble >= 12.0 -> 400.0
        map.zoomLevelDouble >= 10.0 -> 900.0
        map.zoomLevelDouble >= 8.0  -> 2_500.0
        else                        -> 6_000.0
    }

    // ── Weather ───────────────────────────────────────────────────────────────

    /** Checks weather at [lat]/[lon] on a background thread. Always shows a banner with current conditions. */
    private fun checkWeatherForLocation(lat: Double, lon: Double) {
        Thread {
            val current  = WeatherService.fetchCurrent(lat, lon) ?: return@Thread
            val allAreas = WeatherService.fetchAllAreasWeather()   // always fetch all 12 areas
            runOnUiThread { showWeatherBanner(current, allAreas) }
        }.start()
    }

    private fun showWeatherBanner(
        current: WeatherService.AreaWeather,
        allAreas: List<WeatherService.AreaWeather>
    ) {
        val emoji = when {
            current.code == 0       -> "\u2600"          // ☀
            current.code in 1..3    -> "\u26c5"          // ⛅
            current.code in 45..48  -> "\uD83C\uDF2B"   // 🌫
            current.code in 51..65  -> "\uD83C\uDF27"   // 🌧
            current.code in 71..77  -> "\u2744"          // ❄
            current.code >= 95      -> "\u26C8"          // ⛈
            else                    -> "\u2601"          // ☁
        }
        val windText = if (current.windKmh >= 20)
            "  \u00B7  ${current.windKmh.toInt()} km/h wind" else ""
        weatherBanner.setBackgroundColor(
            if (current.isPoor) 0xFF37474F.toInt()   // blue-grey: poor conditions
            else                0xFF2E4A1E.toInt()    // dark green: good conditions
        )
        weatherBannerText.text =
            "$emoji ${current.description.replaceFirstChar { it.uppercaseChar() }}$windText"

        val clearAreas = allAreas.filter { it.isGood }
        weatherFindBtn.visibility = View.VISIBLE
        weatherFindBtn.text = if (clearAreas.isEmpty()) "No good areas today" else "Good areas today"
        weatherFindBtn.setOnClickListener { showWeatherAreasDialog(clearAreas) }

        weatherBanner.visibility = View.VISIBLE
    }

    private fun showWeatherAreasDialog(clearAreas: List<WeatherService.AreaWeather>) {
        if (clearAreas.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No good conditions today")
                .setMessage("No UK hill areas currently have clear, calm conditions suitable for hillwalking. Check back later or monitor the forecast.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val items = clearAreas.map { area ->
            val cond = when {
                area.code == 0    -> "\u2600"    // ☀
                area.code in 1..3 -> "\u26c5"    // ⛅
                else              -> "\u26c5"    // ⛅ (all items here are isGood so code ≤ 3)
            }
            val wind = if (area.windKmh >= 10) "  ${area.windKmh.toInt()} km/h wind" else ""
            "$cond  ${area.areaName}  —  ${area.description}$wind"
        }.toTypedArray()

        val title = "${clearAreas.size} area${if (clearAreas.size == 1) "" else "s"} with good conditions today"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, idx ->
                val picked = clearAreas[idx]
                weatherBanner.visibility = View.GONE
                showHillSearch("${picked.areaName} hills")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceNavigator.shutdown()
    }

}
